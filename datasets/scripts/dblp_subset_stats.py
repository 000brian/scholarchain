#!/usr/bin/env python3
"""Measure the DBLP subset rule on a local DBLP XML file (stdlib only).

Owner: dataset-engineer. This is a single-machine sizing and verification
tool for the subset rule in datasets/dataset-manifest.yaml. It is NOT the
production parser (that is hadoop-engineer's MapReduce job) and it writes no
normalised data, only a JSON statistics report.

Input: DBLP XML (.xml or .xml.gz). A truncated bounded prefix from
download_dblp.sh is accepted; the report then sets "truncated": true and the
numbers are partial. The real dump references an external DTD; pass --dtd
with the local dblp.dtd (only a system id matching dblp*.dtd is resolved;
nothing is fetched from the network). Samples with an internal DTD subset
need no --dtd.

Pre-entity-resolution definitions used for sizing:
  work            a record element in subset_rule.record_elements whose key
                  starts with one of subset_rule.key_prefixes, whose <year> is
                  within [year_min, year_max], and whose publtype does not
                  contain an excluded token.
  author mention  an <author> child; name = entity-decoded text with internal
                  whitespace collapsed. Duplicate names on one work count once.
  pair instance   an unordered pair of distinct names on one work.
  distinct pair   a pair instance de-duplicated across all subset works.

Usage
  dblp_subset_stats.py --input FILE [--dtd dblp.dtd] [--manifest PATH]
                       [--key-prefix P ...] [--year-min Y --year-max Y]
                       [--per-prefix] [--require-all-prefixes] [--out report.json]

Exit codes
  0  report written
  2  parse error that is not a truncation (malformed input)
  3  --require-all-prefixes was given and prefixes_with_zero_works is non-empty
     or the input was truncated. The manifest verification_plan uses this flag
     for the full-release run; a bounded prefix is expected to fail it.
"""

from __future__ import annotations

import argparse
import gzip
import itertools
import json
import os
import re
import sys
import xml.parsers.expat as expat

sys.dont_write_bytecode = True  # keep datasets/scripts free of __pycache__
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import dslib  # noqa: E402


class Stats:
    def __init__(self, prefixes, year_min, year_max, elements, excluded_publtypes, per_prefix):
        self.prefixes = tuple(prefixes)
        self.year_min, self.year_max = year_min, year_max
        self.elements = set(elements)
        self.excluded = set(excluded_publtypes)
        self.per_prefix = per_prefix
        self.records_seen = 0
        self.included_keys = []
        self.works = 0
        self.mentions = 0
        self.pair_instances = 0
        self.pairs = set()
        self.names = set()
        self.works_with_doi = 0
        self.dois = set()
        self.works_zero_authors = 0
        self.works_with_orcid = 0
        self.orcid_mentions = 0
        self.invalid_orcid_attrs = 0
        self.excluded_counts = {"element": 0, "key_prefix": 0, "year": 0, "publtype": 0}
        self.prefix_counts = {p: 0 for p in self.prefixes}
        self.prefix_pairs = {p: set() for p in self.prefixes} if per_prefix else {}
        self.prefix_instances = {p: 0 for p in self.prefixes}

    def record(self, tag, attrs, authors, orcids, year, ees):
        self.records_seen += 1
        key = attrs.get("key", "")
        if tag not in self.elements:
            self.excluded_counts["element"] += 1
            return
        prefix = next((p for p in self.prefixes if key.startswith(p)), None)
        if prefix is None:
            self.excluded_counts["key_prefix"] += 1
            return
        if year is None or not (self.year_min <= year <= self.year_max):
            self.excluded_counts["year"] += 1
            return
        if set(attrs.get("publtype", "").split()) & self.excluded:
            self.excluded_counts["publtype"] += 1
            return
        self.works += 1
        self.included_keys.append(key)
        self.prefix_counts[prefix] += 1
        uniq = sorted(set(authors))
        self.mentions += len(uniq)
        self.names.update(uniq)
        if not uniq:
            self.works_zero_authors += 1
        valid_orcids = [o for o in orcids if dslib.normalize_orcid(o)]
        self.invalid_orcid_attrs += len(orcids) - len(valid_orcids)
        self.orcid_mentions += len(valid_orcids)
        if valid_orcids:
            self.works_with_orcid += 1
        work_dois = {d for d in (dslib.doi_from_dblp_ee(e) for e in ees) if d}
        if work_dois:
            self.works_with_doi += 1
            self.dois.update(work_dois)
        for a, b in itertools.combinations(uniq, 2):
            self.pair_instances += 1
            self.pairs.add((a, b))
            self.prefix_instances[prefix] += 1
            if self.per_prefix:
                self.prefix_pairs[prefix].add((a, b))

    def report(self, truncated, error):
        r = {
            "truncated": truncated,
            "parse_error": error,
            "rule": {"key_prefixes": list(self.prefixes), "year_min": self.year_min,
                     "year_max": self.year_max, "record_elements": sorted(self.elements),
                     "excluded_publtype_tokens": sorted(self.excluded)},
            "records_seen": self.records_seen,
            "works": self.works,
            "works_zero_authors": self.works_zero_authors,
            "author_mentions": self.mentions,
            "distinct_author_names": len(self.names),
            "pair_instances": self.pair_instances,
            "distinct_pairs": len(self.pairs),
            "works_with_doi": self.works_with_doi,
            "distinct_dois": len(self.dois),
            "works_with_orcid_attr": self.works_with_orcid,
            "valid_orcid_attrs": self.orcid_mentions,
            "invalid_orcid_attrs": self.invalid_orcid_attrs,
            "excluded": self.excluded_counts,
            "works_per_prefix": self.prefix_counts,
            "prefixes_with_zero_works": [p for p, n in self.prefix_counts.items() if n == 0],
        }
        if self.per_prefix:
            r["distinct_pairs_per_prefix"] = {p: len(s) for p, s in self.prefix_pairs.items()}
            r["pair_instances_per_prefix"] = dict(self.prefix_instances)
        return r


def run(path, dtd, stats):
    p = expat.ParserCreate()
    p.buffer_text = True
    p.SetParamEntityParsing(expat.XML_PARAM_ENTITY_PARSING_UNLESS_STANDALONE)

    def ext_ref(context, base, system_id, public_id):
        if dtd is None or system_id is None or not re.match(r"^dblp[-0-9]*\.dtd$", os.path.basename(system_id)):
            raise ValueError("refusing to resolve external entity %r (pass --dtd for the dblp DTD)" % system_id)
        sub = p.ExternalEntityParserCreate(context)
        with open(dtd, "rb") as fh:
            sub.ParseFile(fh)
        return 1

    p.ExternalEntityRefHandler = ext_ref
    st = {"depth": 0, "tag": None, "attrs": None, "field": None, "text": [],
          "authors": [], "orcids": [], "year": None, "ees": []}

    def start(name, attrs):
        st["depth"] += 1
        if st["depth"] == 2:
            st.update(tag=name, attrs=dict(attrs), authors=[], orcids=[], year=None, ees=[])
        elif st["depth"] == 3:
            st["field"] = name
            st["text"] = []
            if name == "author" and "orcid" in attrs:
                st["orcids"].append(attrs["orcid"])

    def chars(data):
        if st["depth"] >= 3 and st["field"] is not None:
            st["text"].append(data)

    def end(name):
        if st["depth"] == 3:
            txt = " ".join("".join(st["text"]).split())
            if name == "author":
                st["authors"].append(txt)
            elif name == "year" and txt.isdigit():
                st["year"] = int(txt)
            elif name == "ee":
                st["ees"].append(txt)
            st["field"] = None
        elif st["depth"] == 2:
            stats.record(st["tag"], st["attrs"], st["authors"], st["orcids"], st["year"], st["ees"])
        st["depth"] -= 1

    p.StartElementHandler = start
    p.EndElementHandler = end
    p.CharacterDataHandler = chars
    opener = gzip.open if dslib.is_gzip(path) else open
    truncated, error = False, None
    try:
        with opener(path, "rb") as fh:
            p.ParseFile(fh)
    except (EOFError, gzip.BadGzipFile) as e:
        truncated, error = True, "compressed input ended early: %s" % e
    except expat.ExpatError as e:
        msg = str(e)
        truncated = "no element found" in msg or "unclosed token" in msg
        error = msg
    return truncated, error


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--input", required=True)
    ap.add_argument("--dtd")
    ap.add_argument("--manifest", default=dslib.MANIFEST_PATH)
    ap.add_argument("--key-prefix", action="append", help="override subset key prefixes")
    ap.add_argument("--year-min", type=int)
    ap.add_argument("--year-max", type=int)
    ap.add_argument("--per-prefix", action="store_true", help="also report distinct pairs per prefix")
    ap.add_argument("--require-all-prefixes", action="store_true",
                    help="exit 3 if any subset key prefix has zero works or the input is truncated")
    ap.add_argument("--out")
    a = ap.parse_args(argv)
    rule = dslib.load_manifest(a.manifest)["subset_rule"]["dblp"]
    prefixes = a.key_prefix or [v["dblp_key_prefix"] for v in rule["venues"]]
    stats = Stats(prefixes,
                  a.year_min if a.year_min is not None else rule["year_min"],
                  a.year_max if a.year_max is not None else rule["year_max"],
                  rule["record_elements"], rule["exclude_publtype_tokens"], a.per_prefix)
    truncated, error = run(a.input, a.dtd, stats)
    rep = stats.report(truncated, error)
    rep["input"] = os.path.basename(a.input)
    text = json.dumps(rep, indent=2, sort_keys=True)
    if a.out:
        with open(a.out, "w", encoding="utf-8") as fh:
            fh.write(text + "\n")
    print(text)
    if error and not truncated:
        return 2
    if a.require_all_prefixes and (rep["prefixes_with_zero_works"] or truncated):
        print("FAIL: --require-all-prefixes: prefixes_with_zero_works=%s truncated=%s"
              % (rep["prefixes_with_zero_works"], truncated), file=sys.stderr)
        return 3
    return 0


if __name__ == "__main__":
    sys.exit(main())
