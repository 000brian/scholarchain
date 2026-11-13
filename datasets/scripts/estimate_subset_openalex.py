#!/usr/bin/env python3
"""Reproduce the subset-size ESTIMATE from OpenAlex counts (stdlib only).

Owner: dataset-engineer. Output is an estimate, never a measurement: the
measurement is dblp_subset_stats.py on the full pinned DBLP release.

Bounded: 1 source lookup per OpenAlex source (19), then 2 requests per subset
venue plus 1 per calibration venue (about 55 requests in total, each a single
source, a filtered count or a 100-work sample with select=authorships).
No credentials are required; SCHOLARCHAIN_OPENALEX_API_KEY is used if set.

Source mapping check (runs first, always): every OpenAlex source id in the
manifest (subset_rule.dblp.venues[] and estimate.calibration.openalex_sources)
is resolved at https://api.openalex.org/sources/<id>; the returned id must equal
the requested id and display_name must equal the manifest's recorded
display name exactly. Any mismatch or failed lookup aborts with exit 3 before
any count is used.

Inputs from datasets/dataset-manifest.yaml:
  subset_rule.dblp.venues[].openalex_source_id / openalex_display_name,
  year_min, year_max; subset_rule.estimate.calibration (DBLP-measured counts,
  ratios and openalex_sources)

Usage: estimate_subset_openalex.py [--seed 20261112] [--verify-sources-only] [--dry-run]
"""

from __future__ import annotations

import argparse
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

API = "https://api.openalex.org/works?"
SOURCES_API = "https://api.openalex.org/sources/"


def manifest_sources(m: dict) -> list:
    """[(label, source_id, expected_display_name)] for every OpenAlex source the estimate uses."""
    out = []
    for v in m["subset_rule"]["dblp"]["venues"]:
        out.append((v["dblp_key_prefix"], v["openalex_source_id"], v["openalex_display_name"]))
    cal = m["subset_rule"]["estimate"]["calibration"]
    for prefix, src in cal["openalex_sources"].items():
        out.append((prefix, src["id"], src["display_name"]))
    return out


def check_source(source_id: str, expected_name: str, response) -> list:
    """Return mismatch messages (empty list = verified)."""
    if not isinstance(response, dict):
        return ["%s: lookup failed" % source_id]
    errs = []
    got_id = dslib.normalize_openalex_id(response.get("id"), "source")
    if got_id != source_id:
        errs.append("%s: API returned id %r (merged or redirected?)" % (source_id, response.get("id")))
    if response.get("display_name") != expected_name:
        errs.append("%s: display_name %r != manifest %r" % (source_id, response.get("display_name"), expected_name))
    return errs


def get_url(url: str):
    headers = {"User-Agent": dslib.http_user_agent()}
    key = os.environ.get("SCHOLARCHAIN_OPENALEX_API_KEY", "").strip()
    if key:
        headers["Authorization"] = "Bearer " + key
    time.sleep(0.25)
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=60) as r:
            return json.load(r)
    except (urllib.error.URLError, OSError, ValueError) as e:
        print("request failed: %s (%s)" % (url, e), file=sys.stderr)
        return None


def verify_sources(m: dict, dry: bool, fetch=get_url) -> list:
    errs = []
    for label, sid, name in manifest_sources(m):
        url = SOURCES_API + sid + "?" + urllib.parse.urlencode({"select": "id,display_name"})
        if dry:
            print("plan: GET %s" % url)
            continue
        e = check_source(sid, name, fetch(url))
        errs.extend(e)
        print("source %-16s %s %s" % (label, sid, "MISMATCH" if e else "ok (%s)" % name))
    return errs


def get(params: dict, dry: bool):
    url = API + urllib.parse.urlencode(params)
    if dry:
        print("plan: GET %s" % url)
        return None
    headers = {"User-Agent": dslib.http_user_agent()}
    key = os.environ.get("SCHOLARCHAIN_OPENALEX_API_KEY", "").strip()
    if key:
        headers["Authorization"] = "Bearer " + key
    time.sleep(0.25)
    with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=60) as r:
        return json.load(r)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--seed", default="20261112")
    ap.add_argument("--verify-sources-only", action="store_true",
                    help="only resolve and check the 19 OpenAlex source ids, then exit")
    ap.add_argument("--dry-run", action="store_true")
    a = ap.parse_args()
    m = dslib.load_manifest()
    errs = verify_sources(m, a.dry_run)
    if errs:
        for e in errs:
            print("SOURCE MAPPING ERROR: %s" % e, file=sys.stderr)
        return 3
    if a.verify_sources_only:
        return 0
    rule = m["subset_rule"]["dblp"]
    cal = m["subset_rule"]["estimate"]["calibration"]
    years = "%d-%d" % (rule["year_min"], rule["year_max"])

    ratios = []
    for prefix, measured in cal["measured_dblp_2015_2024"]["per_prefix"].items():
        src = cal["openalex_sources"].get(prefix)
        sid = src["id"] if src else next(
            v["openalex_source_id"] for v in rule["venues"] if v["dblp_key_prefix"] == prefix)
        r = get({"filter": "primary_location.source.id:%s,publication_year:2015-2024" % sid,
                 "per_page": "1", "select": "id"}, a.dry_run)
        if r:
            n = r["meta"]["count"]
            ratios.append(measured["works"] / n)
            print("calibration %-14s dblp=%d openalex=%d ratio=%.3f" % (prefix, measured["works"], n, measured["works"] / n))
    dpi = [v["distinct_per_instance"] for v in cal["measured_dblp_2015_2024"]["per_prefix"].values()]

    total, pairs = 0, 0.0
    for v in rule["venues"]:
        sid = v["openalex_source_id"]
        flt = "primary_location.source.id:%s,publication_year:%s" % (sid, years)
        c = get({"filter": flt, "per_page": "1", "select": "id"}, a.dry_run)
        s = get({"filter": flt, "sample": "100", "seed": a.seed, "per_page": "100", "select": "id,authorships"}, a.dry_run)
        if c and s:
            n = c["meta"]["count"]
            ks = [len(w["authorships"]) for w in s["results"]]
            ppw = sum(k * (k - 1) / 2 for k in ks) / len(ks)
            total += n
            pairs += n * ppw
            print("%-16s %s works=%d sample_n=%d mean_authors=%.2f pair_instances_per_work=%.2f"
                  % (v["dblp_key_prefix"], sid, n, len(ks), sum(ks) / len(ks), ppw))
    if a.dry_run:
        return 0
    out = {
        "kind": "estimate",
        "openalex_works": total,
        "dblp_works_low": round(total * min(ratios)),
        "dblp_works_high": round(total * max(ratios)),
        "pair_instances": round(pairs),
        "distinct_pairs_low": round(pairs * min(dpi)),
        "distinct_pairs_high": round(pairs * max(dpi)),
        "note": "OpenAlex counts change over time; compare with subset_rule.estimate (computed 2026-11-12).",
    }
    print(json.dumps(out, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
