#!/usr/bin/env python3
"""Dataset validation command for ScholarChain (stdlib only; YAML via dslib).

Owner: dataset-engineer.

Checks
  1. Manifest top-level and per-dataset required fields are present and non-empty.
  2. Every "unknown" value has a non-empty sibling "<field>_unknown_reason".
  3. No unreplaced placeholders; S3 locations are {raw_bucket} templates only.
  4. Licenses and evidence: url + checked_on on every evidence entry (https only).
  5. Samples: listed files exist, sha256/bytes match, license/attribution present,
     synthetic files are named and marked synthetic, every file under
     datasets/samples is listed, total size within samples_budget_bytes.
  6. Samples parse (XML with expat, JSONL line by line) and identifiers are valid
     (DOI, ORCID check digit, OpenAlex ids, dblp keys).
  7. Linkage: enrichment sample DOIs/ORCIDs/author ids link to the DBLP sample.
  8. Subset rule applied to samples gives the manifest's expected outcomes.
  9. Script pinned constants equal manifest values (including the pinned 16 MiB
     DBLP prefix sha256); scripts contain no AWS CLI write calls and no
     credential-looking literals; `bash -n` on every *.sh.
 10. Every OpenAlex source id used by the estimate (venues and calibration)
     has a recorded display name and an evidence entry (https url naming the
     id, checked_on, observed). Synthetic samples carry evidence for their
     identifier claims; any sample evidence entry is well-formed.

Usage: validate_datasets.py [--manifest PATH]   (exit 0 = all checks passed)
"""

from __future__ import annotations

import argparse
import glob
import json
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

sys.dont_write_bytecode = True  # keep datasets/scripts free of __pycache__
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import dslib  # noqa: E402
import dblp_subset_stats  # noqa: E402

REPO = os.path.dirname(dslib.DATASETS_DIR)
TOP_REQUIRED = ["manifest_schema", "manifest_version", "checked_on", "s3_layout", "subset_rule",
                "identifiers", "datasets", "samples", "samples_budget_bytes", "open_questions"]
DATASET_REQUIRED = ["id", "name", "role", "status", "source", "license", "snapshot", "format",
                    "size_estimate", "s3_prefixes", "checksums", "subset", "schema", "linkage_keys",
                    "update_method", "limitations", "privacy", "validation_commands", "scripts"]
REQUIRED_DATASETS = {"dblp", "openalex", "crossref", "orcid"}
PRIVACY_REQUIRED = ["personal_data", "kept", "not_kept"]
SAMPLE_KINDS = {"real", "real-projection", "synthetic", "selection"}
PLACEHOLDER_RE = re.compile(r"\b(SHA|BYTES)_[A-Z_]+\b|\bPREFIX_SHA256\b|\bCALIBRATION_PER_PREFIX\b")
SECRET_RES = [re.compile(p) for p in (
    r"AKIA[0-9A-Z]{16}", r"(?i)aws_secret_access_key\s*[=:]", r"-----BEGIN [A-Z ]*PRIVATE KEY-----",
    r"(?i)api_key=[A-Za-z0-9]{8,}", r"(?i)crossref-api-key:\s*Bearer\s+[A-Za-z0-9]")]
EMAIL_RE = re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+\.[A-Za-z0-9.-]*[A-Za-z]")
AWS_WRITE_RE = re.compile(r"\baws\s+(s3|s3api|dynamodb|neptune|emr)\b")


def walk_unknowns(node, path, errors):
    if isinstance(node, dict):
        for k, v in node.items():
            if v == "unknown":
                reason = node.get(k + "_unknown_reason")
                if not (isinstance(reason, str) and reason.strip()):
                    errors.append("%s.%s is 'unknown' without %s_unknown_reason" % (path, k, k))
            walk_unknowns(v, "%s.%s" % (path, k), errors)
    elif isinstance(node, list):
        for i, v in enumerate(node):
            walk_unknowns(v, "%s[%d]" % (path, i), errors)


def walk_strings(node):
    if isinstance(node, dict):
        for v in node.values():
            yield from walk_strings(v)
    elif isinstance(node, list):
        for v in node:
            yield from walk_strings(v)
    elif isinstance(node, str):
        yield node


def empty(v) -> bool:
    return v is None or (isinstance(v, (str, list, dict)) and len(v) == 0)


def check_manifest(m: dict, errors: list) -> None:
    for k in TOP_REQUIRED:
        if k not in m or empty(m[k]):
            errors.append("manifest: missing or empty top-level field '%s'" % k)
    if errors:
        return
    walk_unknowns(m, "manifest", errors)
    raw = json.dumps(m)
    for ph in sorted(set(x.group(0) for x in PLACEHOLDER_RE.finditer(raw))):
        errors.append("manifest: unreplaced placeholder %s" % ph)
    ids = set()
    for i, d in enumerate(m["datasets"]):
        did = d.get("id", "datasets[%d]" % i)
        ids.add(did)
        for k in DATASET_REQUIRED:
            if k not in d or empty(d[k]):
                errors.append("dataset %s: missing or empty field '%s'" % (did, k))
        lic = d.get("license") or {}
        for k in ("spdx", "name", "url", "evidence"):
            if empty(lic.get(k)):
                errors.append("dataset %s: license.%s missing" % (did, k))
        evid = list(lic.get("evidence") or []) + list((d.get("source") or {}).get("evidence") or [])
        if not evid:
            errors.append("dataset %s: no evidence entries" % did)
        for e in evid:
            if not str(e.get("url", "")).startswith("https://") or empty(e.get("checked_on")) or empty(e.get("observed")):
                errors.append("dataset %s: evidence entry needs https url, checked_on, observed: %r" % (did, e))
        for k in PRIVACY_REQUIRED:
            if empty((d.get("privacy") or {}).get(k)):
                errors.append("dataset %s: privacy.%s missing" % (did, k))
        for name, loc in (d.get("s3_prefixes") or {}).items():
            for s in walk_strings(loc):
                if s.startswith("s3://") and not s.startswith("s3://{raw_bucket}/"):
                    errors.append("dataset %s: s3_prefixes.%s is not a {raw_bucket} template: %s" % (did, name, s))
        for lk in d.get("linkage_keys") or []:
            if lk.get("field") not in m["identifiers"]:
                errors.append("dataset %s: linkage field %r not defined in identifiers" % (did, lk.get("field")))
        for s in d.get("scripts") or []:
            if not os.path.exists(os.path.join(REPO, s)):
                errors.append("dataset %s: script not found: %s" % (did, s))
        sch = (d.get("schema") or {}).get("notes")
        if sch and not os.path.exists(os.path.join(REPO, sch)):
            errors.append("dataset %s: schema notes not found: %s" % (did, sch))
    for missing in sorted(REQUIRED_DATASETS - ids):
        errors.append("manifest: required dataset '%s' absent" % missing)
    for s in walk_strings(m):
        if "s3://" in s and re.search(r"s3://(?!\{raw_bucket\}|openalex/)", s):
            errors.append("manifest: S3 URI that is neither a template nor the public OpenAlex bucket: %s" % s)
    est = m["subset_rule"].get("estimate", {})
    if est.get("kind") != "estimate" or est.get("verified") is not False or empty(est.get("basis")):
        errors.append("subset_rule.estimate must have kind: estimate, verified: false and a basis")
    check_openalex_sources(m, errors)


def evidence_ok(e) -> bool:
    return (isinstance(e, dict) and str(e.get("url", "")).startswith("https://")
            and not empty(e.get("checked_on")) and not empty(e.get("observed")))


def check_openalex_sources(m: dict, errors: list) -> None:
    """Every OpenAlex source id that drives the estimate has a display name and evidence."""
    entries = []
    for v in m["subset_rule"]["dblp"].get("venues", []):
        entries.append((v.get("dblp_key_prefix"), v.get("openalex_source_id"),
                        v.get("openalex_display_name"), v.get("openalex_evidence")))
    cal = (m["subset_rule"].get("estimate") or {}).get("calibration") or {}
    if empty(cal.get("openalex_sources")):
        errors.append("subset_rule.estimate.calibration.openalex_sources missing")
    for prefix, src in (cal.get("openalex_sources") or {}).items():
        src = src or {}
        entries.append((prefix, src.get("id"), src.get("display_name"), src.get("evidence")))
    for prefix, sid, name, ev in entries:
        if dslib.normalize_openalex_id(str(sid), "source") != sid:
            errors.append("openalex source for %s: invalid id %r" % (prefix, sid))
        if empty(name):
            errors.append("openalex source %s (%s): display_name missing" % (sid, prefix))
        if not evidence_ok(ev) or str(sid) not in str((ev or {}).get("url", "")):
            errors.append("openalex source %s (%s): evidence needs https url naming the id, checked_on, observed" % (sid, prefix))


def parse_dblp(path):
    """Parse a DBLP sample (internal DTD subset) and return list of record dicts."""
    tree = ET.parse(path)
    out = []
    for rec in tree.getroot():
        out.append({
            "tag": rec.tag, "key": rec.get("key"), "publtype": rec.get("publtype"),
            "authors": [(a.text or "", a.get("orcid")) for a in rec.findall("author")],
            "ees": [e.text or "" for e in rec.findall("ee")],
        })
    return out


def read_jsonl(path):
    with open(path, encoding="utf-8") as fh:
        return [json.loads(l) for l in fh if l.strip()]


def check_samples(m: dict, errors: list) -> dict:
    listed = {}
    for s in m["samples"]:
        p = s.get("path", "")
        listed[p] = s
        full = os.path.join(REPO, p)
        for k in ("dataset", "kind", "format", "license", "attribution", "provenance", "sha256", "bytes"):
            if empty(s.get(k)):
                errors.append("sample %s: missing %s" % (p, k))
        if s.get("kind") not in SAMPLE_KINDS:
            errors.append("sample %s: kind must be one of %s" % (p, sorted(SAMPLE_KINDS)))
        if (s.get("kind") == "synthetic") != ("synthetic" in os.path.basename(p)):
            errors.append("sample %s: synthetic files must be named *synthetic* and vice versa" % p)
        if not os.path.exists(full):
            errors.append("sample %s: file missing" % p)
            continue
        if dslib.file_digest(full) != s.get("sha256"):
            errors.append("sample %s: sha256 mismatch (manifest %s, file %s)" % (p, s.get("sha256"), dslib.file_digest(full)))
        if os.path.getsize(full) != s.get("bytes"):
            errors.append("sample %s: bytes mismatch (manifest %s, file %d)" % (p, s.get("bytes"), os.path.getsize(full)))
        for e in s.get("evidence") or []:
            if not evidence_ok(e):
                errors.append("sample %s: evidence entry needs https url, checked_on, observed: %r" % (p, e))
        if s.get("kind") == "synthetic" and empty(s.get("evidence")):
            errors.append("sample %s: synthetic sample needs evidence for its identifier claims" % p)
        if s.get("kind") == "synthetic":
            with open(full, encoding="utf-8", errors="replace") as fh:
                if "SYNTHETIC" not in fh.read(2048):
                    errors.append("sample %s: synthetic file lacks a SYNTHETIC banner" % p)
    on_disk = [os.path.relpath(f, REPO) for f in glob.glob(os.path.join(dslib.DATASETS_DIR, "samples", "**", "*"), recursive=True) if os.path.isfile(f)]
    total = 0
    for f in on_disk:
        total += os.path.getsize(os.path.join(REPO, f))
        if f not in listed and os.path.basename(f) != ".DS_Store":
            errors.append("samples: unlisted file %s" % f)
    if total > m["samples_budget_bytes"]:
        errors.append("samples: total %d bytes exceeds budget %d" % (total, m["samples_budget_bytes"]))
    return {"total_bytes": total, "files": len(on_disk)}


def check_sample_content(m: dict, errors: list) -> dict:
    S = os.path.join(dslib.DATASETS_DIR, "samples")
    info = {}
    real = parse_dblp(os.path.join(S, "dblp", "dblp-sample.xml"))
    synth = parse_dblp(os.path.join(S, "dblp", "dblp-synthetic-edge-cases.xml"))
    dblp_dois, dblp_orcids = set(), set()
    for r in real:
        if not dslib.is_dblp_key(r["key"] or ""):
            errors.append("dblp sample: malformed key %r" % r["key"])
        for _, o in r["authors"]:
            if o is not None:
                n = dslib.normalize_orcid(o)
                if n is None:
                    errors.append("dblp sample: invalid ORCID %r on %s" % (o, r["key"]))
                else:
                    dblp_orcids.add(n)
        for e in r["ees"]:
            d = dslib.doi_from_dblp_ee(e)
            if d:
                dblp_dois.add(d)
    info["dblp_real_records"] = len(real)
    info["dblp_synthetic_records"] = len(synth)
    names = [a for r in real for a, _ in r["authors"]]
    if not any(dslib.split_dblp_name(n)[1] for n in names):
        errors.append("dblp sample: no homonym-numbered author name")
    if not any(any(ord(c) > 127 for c in n) for n in names):
        errors.append("dblp sample: no non-ASCII (entity-decoded) author name")
    if not any(len(r["authors"]) >= 3 for r in real):
        errors.append("dblp sample: no multi-author record")

    works = read_jsonl(os.path.join(S, "openalex", "works.jsonl"))
    authors = read_jsonl(os.path.join(S, "openalex", "authors.jsonl"))
    cr = read_jsonl(os.path.join(S, "crossref", "works.jsonl"))
    orc = read_jsonl(os.path.join(S, "orcid", "records.jsonl"))
    work_author_ids = set()
    for w in works:
        if dslib.normalize_openalex_id(w.get("id"), "work") is None:
            errors.append("openalex works: bad id %r" % w.get("id"))
        d = dslib.normalize_doi(w.get("doi"))
        if d not in dblp_dois:
            errors.append("openalex works: DOI %r does not link to the DBLP sample" % w.get("doi"))
        for au in w.get("authorships", []):
            aid = (au.get("author") or {}).get("id")
            if aid is not None:
                if dslib.normalize_openalex_id(aid, "author") is None:
                    errors.append("openalex works: bad author id %r" % aid)
                work_author_ids.add(dslib.normalize_openalex_id(aid, "author"))
            o = (au.get("author") or {}).get("orcid")
            if o is not None and dslib.normalize_orcid(o) is None:
                errors.append("openalex works: invalid ORCID %r" % o)
    for a in authors:
        aid = dslib.normalize_openalex_id(a.get("id"), "author")
        if aid not in work_author_ids:
            errors.append("openalex authors: %r not on a sample work" % a.get("id"))
        if a.get("orcid") and dslib.normalize_orcid(a["orcid"]) is None:
            errors.append("openalex authors: invalid ORCID %r" % a.get("orcid"))
    for c in cr:
        if dslib.normalize_doi(c.get("DOI")) not in dblp_dois:
            errors.append("crossref works: DOI %r does not link to the DBLP sample" % c.get("DOI"))
        if "abstract" in c:
            errors.append("crossref works: abstract present (excluded by policy) in %s" % c.get("DOI"))
        for au in c.get("author", []):
            if au.get("ORCID") and dslib.normalize_orcid(au["ORCID"]) is None:
                errors.append("crossref works: invalid ORCID %r" % au["ORCID"])
    for o in orc:
        oid = dslib.normalize_orcid(o.get("orcid-path"))
        if oid not in dblp_orcids:
            errors.append("orcid records: %r does not link to the DBLP sample" % o.get("orcid-path"))
        banned = {"biography", "emails", "addresses", "keywords", "researcher-urls", "external-identifiers"}
        if banned & set(o):
            errors.append("orcid records: privacy-excluded sections present: %s" % sorted(banned & set(o)))
    info.update(openalex_works=len(works), openalex_authors=len(authors), crossref_works=len(cr), orcid_records=len(orc))

    rule = m["subset_rule"]["dblp"]
    prefixes = [v["dblp_key_prefix"] for v in rule["venues"]]
    for s in m["samples"]:
        exp = s.get("expected")
        if not exp:
            continue
        st = dblp_subset_stats.Stats(prefixes, rule["year_min"], rule["year_max"], rule["record_elements"],
                                     rule["exclude_publtype_tokens"], False)
        truncated, err = dblp_subset_stats.run(os.path.join(REPO, s["path"]), None, st)
        if err:
            errors.append("subset check %s: parse error %s" % (s["path"], err))
            continue
        if sorted(st.included_keys) != sorted(exp["in_subset_keys"]):
            errors.append("subset check %s: included %s, expected %s" % (s["path"], st.included_keys, exp["in_subset_keys"]))
        if "distinct_pairs" in exp and len(st.pairs) != exp["distinct_pairs"]:
            errors.append("subset check %s: distinct_pairs %d != %d" % (s["path"], len(st.pairs), exp["distinct_pairs"]))
        if "invalid_orcid_attrs" in exp and st.invalid_orcid_attrs != exp["invalid_orcid_attrs"]:
            errors.append("subset check %s: invalid_orcid_attrs %d != %d" % (s["path"], st.invalid_orcid_attrs, exp["invalid_orcid_attrs"]))
        if "distinct_dois" in exp and sorted(st.dois) != sorted(exp["distinct_dois"]):
            errors.append("subset check %s: dois %s != %s" % (s["path"], sorted(st.dois), exp["distinct_dois"]))
        info["subset_" + os.path.basename(s["path"])] = {"included": len(st.included_keys), "distinct_pairs": len(st.pairs)}
    return info


def check_scripts(m: dict, errors: list) -> dict:
    sdir = dslib.SCRIPTS_DIR
    ds = {d["id"]: d for d in m["datasets"]}
    dblp = ds["dblp"]
    with open(os.path.join(sdir, "download_dblp.sh"), encoding="utf-8") as fh:
        sh = fh.read()
    pins = dict(re.findall(r'^(DBLP_[A-Z0-9_]+)="([^"]*)"', sh, re.M))
    expect = {
        "DBLP_RELEASE_ID": dblp["snapshot"]["id"], "DBLP_RELEASE_URL": dblp["snapshot"]["url"],
        "DBLP_RELEASE_MD5": dblp["checksums"]["snapshot_md5"],
        "DBLP_RELEASE_BYTES": str(dblp["size_estimate"]["compressed_bytes"]),
        "DBLP_DTD_SHA256": dblp["checksums"]["dtd_sha256"],
        "DBLP_PREFIX_16MIB_BYTES": str(m["subset_rule"]["estimate"]["calibration"]["measured_dblp_2015_2024"]["prefix_bytes"]),
        "DBLP_PREFIX_16MIB_SHA256": m["subset_rule"]["estimate"]["calibration"]["measured_dblp_2015_2024"]["prefix_sha256"],
    }
    for k, v in expect.items():
        if pins.get(k) != v:
            errors.append("download_dblp.sh: %s=%r but manifest has %r" % (k, pins.get(k), v))
    with open(os.path.join(sdir, "lib.sh"), encoding="utf-8") as fh:
        lib = fh.read()
    if '--max-filesize "$n"' not in lib or 'head -c "$n"' not in lib or "ds_fetch_prefix " not in sh:
        errors.append("download_dblp.sh/lib.sh: bounded prefix fetch must use ds_fetch_prefix with --max-filesize and head -c")
    with open(os.path.join(sdir, "download_openalex_snapshot.sh"), encoding="utf-8") as fh:
        oa = fh.read()
    if 'OPENALEX_SNAPSHOT_DATE="%s"' % ds["openalex"]["snapshot"]["id"] not in oa:
        errors.append("download_openalex_snapshot.sh: snapshot date differs from manifest")
    with open(os.path.join(sdir, "download_orcid_public_data_file.sh"), encoding="utf-8") as fh:
        oc = fh.read()
    pinned = set(re.findall(r"^(ORCID_2026[^|\n]+)\|(\d+)\|([0-9a-f]{32})\|(\S+)$", oc, re.M))
    manifest_files = {(f["name"], str(f["bytes"]), f["md5"], f["url"]) for f in ds["orcid"]["snapshot"]["files"]}
    if pinned != manifest_files:
        errors.append("download_orcid_public_data_file.sh: pinned file list differs from manifest")
    total = sum(f["bytes"] for f in ds["orcid"]["snapshot"]["files"])
    if total != ds["orcid"]["size_estimate"]["all_files_compressed_bytes"]:
        errors.append("orcid: all_files_compressed_bytes %s != sum of files %d" % (ds["orcid"]["size_estimate"]["all_files_compressed_bytes"], total))

    texts = [os.path.join(dslib.DATASETS_DIR, "dataset-manifest.yaml")]
    texts += glob.glob(os.path.join(sdir, "*"))
    texts += glob.glob(os.path.join(dslib.DATASETS_DIR, "schemas", "*"))
    texts += glob.glob(os.path.join(dslib.DATASETS_DIR, "samples", "**", "*"), recursive=True)
    for p in texts:
        if not os.path.isfile(p) or p.endswith(".pyc"):
            continue
        with open(p, encoding="utf-8", errors="replace") as fh:
            t = fh.read()
        for rx in SECRET_RES:
            if rx.search(t):
                errors.append("credential-like literal in %s (%s)" % (os.path.relpath(p, REPO), rx.pattern))
        for em in EMAIL_RE.findall(t):
            errors.append("email address literal in %s: %s" % (os.path.relpath(p, REPO), em))
        if p.endswith(".sh") or p.endswith(".py"):
            for line in t.splitlines():
                if AWS_WRITE_RE.search(line) and not line.lstrip().startswith("#"):
                    errors.append("AWS CLI call in %s: %s" % (os.path.relpath(p, REPO), line.strip()))
    shells = sorted(glob.glob(os.path.join(sdir, "*.sh")))
    for s in shells:
        r = subprocess.run(["bash", "-n", s], capture_output=True, text=True)
        if r.returncode != 0:
            errors.append("bash -n failed for %s: %s" % (os.path.relpath(s, REPO), r.stderr.strip()))
    return {"shell_scripts_checked": len(shells)}


def validate(manifest_path: str = dslib.MANIFEST_PATH) -> tuple:
    errors = []
    info = {}
    try:
        m = dslib.load_manifest(manifest_path)
    except Exception as e:  # noqa: BLE001
        return ["manifest: cannot load: %s" % e], info
    if not isinstance(m, dict):
        return ["manifest: top level is not a mapping"], info
    check_manifest(m, errors)
    if errors:
        return errors, info
    info.update(check_samples(m, errors))
    try:
        info.update(check_sample_content(m, errors))
    except (ET.ParseError, json.JSONDecodeError, OSError) as e:
        errors.append("sample parse failure: %s" % e)
    info.update(check_scripts(m, errors))
    info["datasets"] = [d["id"] for d in m["datasets"]]
    return errors, info


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--manifest", default=dslib.MANIFEST_PATH)
    a = ap.parse_args()
    errors, info = validate(a.manifest)
    for k, v in info.items():
        print("info: %s = %s" % (k, v))
    for e in errors:
        print("ERROR: %s" % e)
    print("RESULT: %s (%d error(s))" % ("PASS" if not errors else "FAIL", len(errors)))
    return 0 if not errors else 1


if __name__ == "__main__":
    sys.exit(main())
