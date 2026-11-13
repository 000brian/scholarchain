#!/usr/bin/env python3
"""Bounded, idempotent enrichment fetcher for OpenAlex, Crossref and ORCID APIs.

Owner: dataset-engineer. Python 3 standard library only.

Bounds
  * Default --max-records 25. Without --full, --max-records may not exceed
    HARD_CAP (1000). --full removes the cap (use only for the approved subset
    run; see datasets/dataset-manifest.yaml subset_rule).
  * Input identifiers are normalised (dslib) and de-duplicated; invalid ones
    are reported and skipped, never "repaired".

Completeness and verification (exit 1 if any check fails)
  * List requests page until the collected results reach meta.count
    (OpenAlex, page/per_page) or message.total-results (Crossref,
    offset/rows); an empty page before that point is a truncation failure.
  * Every returned item must belong to the request: OpenAlex works and
    Crossref works by normalised DOI in the requested batch, OpenAlex authors
    by id in the requested batch.
  * Any request that finally fails (HTTP error after retries, network error,
    invalid JSON) is a failure. Failures are listed on stderr and the exit
    status is 1; nothing is silently skipped.

Idempotency
  * Each HTTP response is cached as <cache>/<source>/<sha256(url)[:24]>.json.
    If the file exists and its sha256 matches the index entry, the request is
    skipped. The index <cache>/<source>/requests.jsonl records url (without any
    credential), retrieved_at (UTC), http status, sha256 and byte length.

Credentials and contact details
  * No credentials are required. Optional environment variables (names only):
      SCHOLARCHAIN_OPENALEX_API_KEY  - sent as "Authorization: Bearer <key>"
                                       (never logged, never in the cache URL)
      SCHOLARCHAIN_CONTACT_MAILTO    - appended to the User-Agent (Crossref
                                       polite pool); unset by default
  * This script never touches AWS.

Usage
  fetch_enrichment.py openalex-works   --ids FILE --cache DIR [--max-records N] [--full]
  fetch_enrichment.py openalex-authors --ids FILE --cache DIR [...]
  fetch_enrichment.py crossref-works   --ids FILE --cache DIR [...]
  fetch_enrichment.py orcid            --ids FILE --cache DIR [...]
  fetch_enrichment.py sample-set --selection datasets/samples/selection.json --cache DIR
  Add --dry-run to print the planned requests without network access.
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

sys.dont_write_bytecode = True  # keep datasets/scripts free of __pycache__
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import dslib  # noqa: E402

HARD_CAP = 1000
DEFAULT_MAX = 25
# Limits checked 2026-11-12:
#   https://help.openalex.org/how-to-use-the-api/get-lists-of-entities/paging
#     per_page 1-100; page * per_page must not exceed 10,000
#   https://api.crossref.org/swagger-docs
#     list requests return up to 1000 items; offset up to 10,000
OPENALEX_PER_PAGE = 100
CROSSREF_ROWS = 100
MAX_PAGES = 100           # safety stop per batch; a batch is at most 50 identifiers

OPENALEX_WORK_SELECT = (
    "id,doi,title,display_name,publication_year,publication_date,ids,type,"
    "primary_location,authorships,updated_date,created_date"
)
OPENALEX_AUTHOR_SELECT = (
    "id,orcid,display_name,display_name_alternatives,ids,works_count,"
    "cited_by_count,last_known_institutions,updated_date,created_date"
)
CROSSREF_WORK_SELECT = (
    "DOI,title,author,container-title,event,published,issued,type,publisher,"
    "prefix,member,URL,ISBN,indexed,deposited,created,license"
)

# Seconds between requests per source (stay well under documented limits).
PACING = {"openalex": 0.25, "crossref": 1.0, "orcid": 0.5}


def utc_now() -> str:
    return dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


class Fetcher:
    def __init__(self, cache: str, dry_run: bool):
        self.cache = cache
        self.dry_run = dry_run
        self._last = {}
        self.failures = []

    def fail(self, msg: str) -> None:
        self.failures.append(msg)
        print("FAIL: %s" % msg, file=sys.stderr)

    def _index_path(self, source: str) -> str:
        return os.path.join(self.cache, source, "requests.jsonl")

    def _load_index(self, source: str) -> dict:
        idx = {}
        p = self._index_path(source)
        if os.path.exists(p):
            with open(p, encoding="utf-8") as fh:
                for line in fh:
                    if line.strip():
                        e = json.loads(line)
                        idx[e["url"]] = e
        return idx

    def get_json(self, source: str, url: str, headers: dict) -> dict | None:
        key = hashlib.sha256(url.encode("utf-8")).hexdigest()[:24]
        out_dir = os.path.join(self.cache, source)
        out = os.path.join(out_dir, key + ".json")
        idx = self._load_index(source)
        if os.path.exists(out) and url in idx:
            if dslib.file_digest(out) == idx[url]["sha256"]:
                print("skip (cached, checksum ok): %s" % url)
                with open(out, encoding="utf-8") as fh:
                    return json.load(fh)
        if self.dry_run:
            print("plan: GET %s" % url)
            return None
        os.makedirs(out_dir, exist_ok=True)
        wait = PACING[source] - (time.monotonic() - self._last.get(source, 0.0))
        if wait > 0:
            time.sleep(wait)
        req_headers = {"User-Agent": dslib.http_user_agent(), "Accept": "application/json"}
        req_headers.update(headers)
        body, status, err = None, None, None
        for attempt in range(5):
            try:
                with urllib.request.urlopen(
                    urllib.request.Request(url, headers=req_headers), timeout=60
                ) as resp:
                    status = resp.status
                    body = resp.read()
                break
            except urllib.error.HTTPError as e:
                status, err = e.code, "HTTP %s" % e.code
                if e.code in (429, 500, 502, 503, 504) and attempt < 4:
                    time.sleep(2 ** attempt * 2)
                    continue
                break
            except (urllib.error.URLError, OSError) as e:
                status, err = None, "network error: %s" % e
                if attempt < 4:
                    time.sleep(2 ** attempt * 2)
                    continue
                break
        self._last[source] = time.monotonic()
        if body is None or status != 200:
            self.fail("%s for %s" % (err or "HTTP %s" % status, url))
            return None
        try:
            parsed = json.loads(body)
        except ValueError as e:
            self.fail("invalid JSON (%s) for %s" % (e, url))
            return None
        tmp = out + ".part"
        with open(tmp, "wb") as fh:
            fh.write(body)
        os.replace(tmp, out)
        entry = {
            "url": url,
            "path": os.path.basename(out),
            "retrieved_at": utc_now(),
            "http_status": status,
            "sha256": hashlib.sha256(body).hexdigest(),
            "bytes": len(body),
        }
        with open(self._index_path(source), "a", encoding="utf-8") as fh:
            fh.write(json.dumps(entry, sort_keys=True) + "\n")
        print("fetched: %s (%d bytes)" % (url, len(body)))
        return parsed


def read_ids(path: str) -> list:
    with open(path, encoding="utf-8") as fh:
        return [l.strip() for l in fh if l.strip() and not l.startswith("#")]


def bound(ids: list, max_records: int, full: bool) -> list:
    if not full and max_records > HARD_CAP:
        sys.exit("refusing --max-records %d > %d without --full" % (max_records, HARD_CAP))
    if full:
        return ids
    if len(ids) > max_records:
        print("bounded: using first %d of %d identifiers (pass --full for all)" % (max_records, len(ids)))
    return ids[:max_records]


def normalise(ids: list, kind: str) -> list:
    seen, good = set(), []
    for raw in ids:
        if kind == "doi":
            n = dslib.normalize_doi(raw)
        elif kind == "orcid":
            n = dslib.normalize_orcid(raw)
        else:
            n = dslib.normalize_openalex_id(raw, kind)
        if n is None:
            print("rejected invalid %s: %r" % (kind, raw), file=sys.stderr)
            continue
        if n not in seen:
            seen.add(n)
            good.append(n)
    return good


def chunks(xs: list, n: int):
    for i in range(0, len(xs), n):
        yield xs[i:i + n]


def openalex_headers() -> dict:
    key = os.environ.get("SCHOLARCHAIN_OPENALEX_API_KEY", "").strip()
    return {"Authorization": "Bearer " + key} if key else {}


def openalex_list(f: Fetcher, path: str, params: dict) -> list | None:
    """All results of an OpenAlex list query; pages until meta.count is reached."""
    results, page = [], 1
    while True:
        q = dict(params, per_page=str(OPENALEX_PER_PAGE), page=str(page))
        url = "https://api.openalex.org/%s?%s" % (path, urllib.parse.urlencode(q))
        r = f.get_json("openalex", url, openalex_headers())
        if r is None:
            return None  # failure already recorded (or dry run)
        try:
            count = int(r["meta"]["count"])
            batch = list(r["results"])
        except (KeyError, TypeError, ValueError):
            f.fail("OpenAlex response lacks meta.count/results: %s" % url)
            return None
        results.extend(batch)
        if len(results) >= count:
            return results
        if not batch or page >= MAX_PAGES:
            f.fail("OpenAlex truncated: %d of meta.count %d after page %d: %s" % (len(results), count, page, url))
            return None
        page += 1


def crossref_list(f: Fetcher, params: dict) -> list | None:
    """All items of a Crossref list query; pages until message.total-results is reached."""
    items, offset, pages = [], 0, 0
    while True:
        q = dict(params, rows=str(CROSSREF_ROWS), offset=str(offset))
        url = "https://api.crossref.org/works?" + urllib.parse.urlencode(q)
        r = f.get_json("crossref", url, {})
        if r is None:
            return None
        pages += 1
        try:
            total = int(r["message"]["total-results"])
            batch = list(r["message"]["items"])
        except (KeyError, TypeError, ValueError):
            f.fail("Crossref response lacks message.total-results/items: %s" % url)
            return None
        items.extend(batch)
        if len(items) >= total:
            return items
        if not batch or pages >= MAX_PAGES:
            f.fail("Crossref truncated: %d of total-results %d at offset %d: %s" % (len(items), total, offset, url))
            return None
        offset += len(batch)


def check_membership(f: Fetcher, label: str, returned: list, requested: list) -> None:
    """Every returned identifier must be one of the requested identifiers."""
    want = set(requested)
    for got in returned:
        if got not in want:
            f.fail("%s: returned item %r is not in the requested set" % (label, got))


def fetch_openalex_works(f: Fetcher, dois: list, batch: int) -> None:
    batchable = [d for d in dois if "," not in d and "|" not in d]
    singles = [d for d in dois if d not in batchable]
    for group in chunks(batchable, batch):
        res = openalex_list(f, "works", {"filter": "doi:" + "|".join(group), "select": OPENALEX_WORK_SELECT})
        if res is not None:
            check_membership(f, "openalex works", [dslib.normalize_doi(w.get("doi")) for w in res], group)
    for d in singles:
        url = "https://api.openalex.org/works/doi:%s?%s" % (
            urllib.parse.quote(d, safe="/"), urllib.parse.urlencode({"select": OPENALEX_WORK_SELECT}))
        w = f.get_json("openalex", url, openalex_headers())
        if w is not None:
            check_membership(f, "openalex works", [dslib.normalize_doi(w.get("doi"))], [d])


def fetch_openalex_authors(f: Fetcher, ids: list, batch: int) -> None:
    for group in chunks(ids, batch):
        res = openalex_list(f, "authors", {"filter": "openalex:" + "|".join(group), "select": OPENALEX_AUTHOR_SELECT})
        if res is not None:
            check_membership(f, "openalex authors",
                             [dslib.normalize_openalex_id(a.get("id"), "author") for a in res], group)


def fetch_crossref_works(f: Fetcher, dois: list, batch: int) -> None:
    batchable = [d for d in dois if "," not in d]
    singles = [d for d in dois if d not in batchable]
    for group in chunks(batchable, batch):
        res = crossref_list(f, {"filter": ",".join("doi:" + d for d in group), "select": CROSSREF_WORK_SELECT})
        if res is not None:
            check_membership(f, "crossref works", [dslib.normalize_doi(i.get("DOI")) for i in res], group)
    for d in singles:
        r = f.get_json("crossref", "https://api.crossref.org/works/" + urllib.parse.quote(d, safe="/"), {})
        if r is not None:
            check_membership(f, "crossref works", [dslib.normalize_doi((r.get("message") or {}).get("DOI"))], [d])


def fetch_orcid(f: Fetcher, orcids: list, sections: list) -> None:
    for o in orcids:
        for s in sections:
            f.get_json("orcid", "https://pub.orcid.org/v3.0/%s/%s" % (o, s), {})


def main(argv=None, fetcher_factory=Fetcher) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("command", choices=["openalex-works", "openalex-authors", "crossref-works", "orcid", "sample-set"])
    ap.add_argument("--ids", help="file with one identifier per line")
    ap.add_argument("--selection", help="sample selection JSON (sample-set only)")
    ap.add_argument("--cache", required=True, help="cache directory (outside the repository)")
    ap.add_argument("--max-records", type=int, default=DEFAULT_MAX)
    ap.add_argument("--batch-size", type=int, default=25)
    ap.add_argument("--full", action="store_true", help="remove the record cap (approved subset runs only)")
    ap.add_argument("--dry-run", action="store_true")
    a = ap.parse_args(argv)

    cache = os.path.abspath(a.cache)
    if dslib.inside_repo(cache):
        sys.exit("refusing to cache API responses inside the repository")
    f = fetcher_factory(cache, a.dry_run)
    batch = max(1, min(a.batch_size, 50))

    if a.command == "sample-set":
        if not a.selection:
            sys.exit("--selection is required for sample-set")
        with open(a.selection, encoding="utf-8") as fh:
            sel = json.load(fh)
        fetch_openalex_works(f, normalise(sel["openalex_works"]["dois"], "doi"), batch)
        fetch_openalex_authors(f, normalise(sel["openalex_authors"]["ids"], "author"), batch)
        fetch_crossref_works(f, normalise(sel["crossref_works"]["dois"], "doi"), batch)
        fetch_orcid(f, normalise(sel["orcid"]["orcids"], "orcid"), sel["orcid"]["sections"])
        return finish(f)

    if not a.ids:
        sys.exit("--ids is required")
    raw = read_ids(a.ids)
    if a.command == "openalex-works":
        fetch_openalex_works(f, bound(normalise(raw, "doi"), a.max_records, a.full), batch)
    elif a.command == "openalex-authors":
        fetch_openalex_authors(f, bound(normalise(raw, "author"), a.max_records, a.full), batch)
    elif a.command == "crossref-works":
        fetch_crossref_works(f, bound(normalise(raw, "doi"), a.max_records, a.full), batch)
    elif a.command == "orcid":
        fetch_orcid(f, bound(normalise(raw, "orcid"), a.max_records, a.full), ["person", "works"])
    return finish(f)


def finish(f: Fetcher) -> int:
    if f.failures:
        print("RESULT: FAIL (%d failure(s))" % len(f.failures), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
