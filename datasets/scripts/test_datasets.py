#!/usr/bin/env python3
"""Dataset validation tests (unittest, stdlib only). Owner: dataset-engineer.

Run: python3 datasets/scripts/test_datasets.py -v
"""

from __future__ import annotations

import contextlib
import copy
import http.server
import io
import json
import os
import subprocess
import sys
import tempfile
import threading
import unittest
import urllib.parse

sys.dont_write_bytecode = True  # keep datasets/scripts free of __pycache__
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import dslib  # noqa: E402
import dblp_subset_stats  # noqa: E402
import estimate_subset_openalex  # noqa: E402
import fetch_enrichment  # noqa: E402
import validate_datasets  # noqa: E402

LIB_SH = os.path.join(dslib.SCRIPTS_DIR, "lib.sh")


def bash_lib(snippet: str, *args: str) -> subprocess.CompletedProcess:
    """Run `snippet` in bash with lib.sh sourced and the download scripts' shell options."""
    return subprocess.run(["bash", "-c", 'set -euo pipefail; . "$0"; ' + snippet, LIB_SH, *args],
                          capture_output=True, text=True)

SAMPLES = os.path.join(dslib.DATASETS_DIR, "samples")


def manifest_copy():
    return copy.deepcopy(dslib.load_manifest())


def write_manifest(m: dict) -> str:
    # JSON is valid YAML 1.2, so the YAML loader reads it.
    fd, p = tempfile.mkstemp(suffix=".yaml")
    with os.fdopen(fd, "w", encoding="utf-8") as fh:
        json.dump(m, fh)
    return p


class DoiTests(unittest.TestCase):
    def test_resolver_prefixes_and_case(self):
        for raw in ("https://doi.org/10.1109/DAC56929.2023.10247992",
                    "http://dx.doi.org/10.1109/DAC56929.2023.10247992",
                    "DOI:10.1109/dac56929.2023.10247992",
                    "  10.1109/DAC56929.2023.10247992 "):
            self.assertEqual(dslib.normalize_doi(raw), "10.1109/dac56929.2023.10247992")

    def test_percent_decoding_only_for_urls(self):
        self.assertEqual(dslib.normalize_doi("https://doi.org/10.5555/a%2Db"), "10.5555/a-b")
        self.assertEqual(dslib.normalize_doi("10.5555/a%2Db"), "10.5555/a%2db")

    def test_ascii_only_lowercasing(self):
        # Non-ASCII letters are not case-folded (DOI Handbook: Basic Latin only).
        self.assertEqual(dslib.normalize_doi("10.1234/ÄBC"), "10.1234/Äbc")

    def test_rejects_non_dois(self):
        for raw in (None, "", "https://www.wikidata.org/entity/Q1", "11.1234/x", "10.1234", "10.abc/x"):
            self.assertIsNone(dslib.normalize_doi(raw))

    def test_dblp_ee_only_doi_urls(self):
        self.assertIsNone(dslib.doi_from_dblp_ee("https://www.wikidata.org/entity/Q131012499"))
        self.assertEqual(dslib.doi_from_dblp_ee("https://doi.org/10.1145/3539618.3591892"), "10.1145/3539618.3591892")


class OrcidTests(unittest.TestCase):
    REAL = ["0000-0003-3226-9189", "0000-0002-8767-8126", "0000-0002-5475-1382",
            "0000-0001-8209-698X", "0009-0006-1229-2612"]

    def test_real_ids_from_samples_are_valid(self):
        for o in self.REAL:
            self.assertEqual(dslib.normalize_orcid(o), o)

    def test_uri_and_lowercase_x(self):
        self.assertEqual(dslib.normalize_orcid("https://orcid.org/0000-0001-8209-698x"), "0000-0001-8209-698X")

    def test_bad_check_digit_and_shape(self):
        for raw in ("0000-0000-0000-0000", "0000-0003-3226-9188", "0000000332269189", "0000-0003-3226-918", None):
            self.assertIsNone(dslib.normalize_orcid(raw))

    def test_non_ascii_digits_rejected(self):
        # Finding 13: Unicode decimal digits must not pass (Scala contract uses [0-9]).
        arabic_indic = "".join(chr(0x0660 + int(c)) if c.isdigit() else c for c in "0000-0003-3226-9189")
        fullwidth = "".join(chr(0xFF10 + int(c)) if c.isdigit() else c for c in "0000-0003-3226-9189")
        mixed = "0000-0003-3226-918\u0669"  # Arabic-Indic nine as the check digit
        for raw in (arabic_indic, fullwidth, mixed, "https://orcid.org/" + fullwidth):
            self.assertIsNone(dslib.normalize_orcid(raw), repr(raw))
        self.assertIsNone(dslib.ORCID_RE.fullmatch(fullwidth))
        with self.assertRaises(ValueError):
            dslib.orcid_check_digit("\u0660" * 15)

    def test_trailing_newline_rejected(self):
        self.assertIsNone(dslib.ORCID_RE.fullmatch("0000-0003-3226-9189\n"))


class OpenAlexIdTests(unittest.TestCase):
    def test_normalise(self):
        self.assertEqual(dslib.normalize_openalex_id("https://openalex.org/w4366851189", "work"), "W4366851189")
        self.assertEqual(dslib.normalize_openalex_id("A5089440969", "author"), "A5089440969")

    def test_wrong_entity_or_shape(self):
        self.assertIsNone(dslib.normalize_openalex_id("A5089440969", "work"))
        self.assertIsNone(dslib.normalize_openalex_id("W0123", "work"))
        self.assertIsNone(dslib.normalize_openalex_id(None, "work"))


class DblpTests(unittest.TestCase):
    def test_homonym_split(self):
        self.assertEqual(dslib.split_dblp_name("Ricardo Campos 0001"), ("Ricardo Campos", "0001"))
        # Non-ASCII digits are not a homonym suffix (Scala Homonym uses [0-9]{4}).
        self.assertEqual(dslib.split_dblp_name("Ricardo Campos \uff10\uff10\uff10\uff11"),
                         ("Ricardo Campos \uff10\uff10\uff10\uff11", None))
        self.assertEqual(dslib.split_dblp_name("Hamed Zamani"), ("Hamed Zamani", None))
        self.assertEqual(dslib.split_dblp_name("Agent 007"), ("Agent 007", None))

    def test_keys_and_prefix(self):
        self.assertTrue(dslib.is_dblp_key("conf/sigir/0003SM23"))
        self.assertEqual(dslib.dblp_stream_prefix("journals/pvldb/X15"), "journals/pvldb/")
        self.assertFalse(dslib.is_dblp_key("not a key"))

    def test_pair_canonicalisation(self):
        self.assertEqual(dslib.canonical_pair("b", "a"), ("a", "b"))

    def _stats(self, path):
        m = dslib.load_manifest()
        rule = m["subset_rule"]["dblp"]
        st = dblp_subset_stats.Stats([v["dblp_key_prefix"] for v in rule["venues"]], rule["year_min"],
                                     rule["year_max"], rule["record_elements"], rule["exclude_publtype_tokens"], True)
        truncated, err = dblp_subset_stats.run(path, None, st)
        self.assertIsNone(err)
        self.assertFalse(truncated)
        return st

    def test_synthetic_subset_expectations(self):
        st = self._stats(os.path.join(SAMPLES, "dblp", "dblp-synthetic-edge-cases.xml"))
        self.assertEqual(sorted(st.included_keys),
                         ["conf/sigir/SyntheticA24", "conf/sigir/SyntheticB25", "journals/pvldb/SyntheticE15"])
        self.assertEqual(len(st.pairs), 7)  # duplicate author removed; 6 + 0 + 1
        self.assertEqual(st.invalid_orcid_attrs, 1)
        self.assertEqual(sorted(st.dois), ["10.5555/scholarchain-synthetic-0001", "10.5555/scholarchain-synthetic-0005"])
        self.assertIn("李明 Synthetic", st.names)  # numeric character references decoded
        self.assertEqual(st.excluded_counts, {"element": 0, "key_prefix": 0, "year": 1, "publtype": 1})

    def test_real_sample_subset_expectations(self):
        st = self._stats(os.path.join(SAMPLES, "dblp", "dblp-sample.xml"))
        self.assertEqual(st.works, 4)
        self.assertIn("Alípio Mário Jorge", st.names)  # named entities decoded
        self.assertEqual(st.excluded_counts, {"element": 2, "key_prefix": 2, "year": 1, "publtype": 0})

    def test_gzip_detected_by_magic_not_extension(self):
        import gzip
        src = os.path.join(SAMPLES, "dblp", "dblp-synthetic-edge-cases.xml")
        with tempfile.TemporaryDirectory() as d:
            p = os.path.join(d, "dblp.xml.gz.prefix-123")
            with open(src, "rb") as fi, gzip.open(p, "wb") as fo:
                fo.write(fi.read())
            st = self._stats(p)
        self.assertEqual(len(st.included_keys), 3)
        self.assertEqual(len(st.pairs), 7)

    def _cli(self, *args):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            rc = dblp_subset_stats.main(list(args))
        return rc, out.getvalue(), err.getvalue()

    def test_require_all_prefixes_fails_on_zero_work_prefix(self):
        # Finding 5: the synthetic sample has works only under conf/sigir/ and journals/pvldb/.
        path = os.path.join(SAMPLES, "dblp", "dblp-synthetic-edge-cases.xml")
        rc, out, err = self._cli("--input", path, "--require-all-prefixes")
        self.assertEqual(rc, 3, err)
        self.assertIn("conf/kdd/", json.loads(out)["prefixes_with_zero_works"])
        self.assertIn("--require-all-prefixes", err)
        rc, _, _ = self._cli("--input", path)  # without the flag: report only
        self.assertEqual(rc, 0)
        rc, _, err = self._cli("--input", path, "--require-all-prefixes",
                               "--key-prefix", "conf/sigir/", "--key-prefix", "journals/pvldb/")
        self.assertEqual(rc, 0, err)

    def test_require_all_prefixes_fails_on_truncated_input(self):
        import gzip
        src = os.path.join(SAMPLES, "dblp", "dblp-synthetic-edge-cases.xml")
        with tempfile.TemporaryDirectory() as d:
            p = os.path.join(d, "trunc.xml.gz")
            with open(src, "rb") as fi:
                data = gzip.compress(fi.read())
            with open(p, "wb") as fo:
                fo.write(data[: len(data) // 2])
            rc, out, _ = self._cli("--input", p, "--require-all-prefixes", "--key-prefix", "conf/sigir/")
        self.assertEqual(rc, 3)
        self.assertTrue(json.loads(out)["truncated"])

    def test_manifest_verification_plan_uses_flag(self):
        m = dslib.load_manifest()
        self.assertIn("--require-all-prefixes", m["subset_rule"]["verification_plan"])

    def test_external_entities_refused_without_dtd(self):
        with tempfile.NamedTemporaryFile("w", suffix=".xml", delete=False) as fh:
            fh.write('<?xml version="1.0"?>\n<!DOCTYPE dblp SYSTEM "evil.dtd">\n<dblp></dblp>\n')
        st = dblp_subset_stats.Stats(["conf/x/"], 2000, 2001, ["article"], [], False)
        with self.assertRaises(ValueError):
            dblp_subset_stats.run(fh.name, None, st)
        os.unlink(fh.name)


class ValidatorTests(unittest.TestCase):
    def test_repository_manifest_passes(self):
        errors, info = validate_datasets.validate()
        self.assertEqual(errors, [])
        self.assertEqual(sorted(info["datasets"]), ["crossref", "dblp", "openalex", "orcid"])

    def test_missing_dataset_field_fails(self):
        m = manifest_copy()
        del m["datasets"][0]["privacy"]
        p = write_manifest(m)
        try:
            errors, _ = validate_datasets.validate(p)
        finally:
            os.unlink(p)
        self.assertTrue(any("missing or empty field 'privacy'" in e for e in errors), errors)

    def test_unknown_without_reason_fails(self):
        m = manifest_copy()
        m["datasets"][0]["size_estimate"]["record_count_unknown_reason"] = ""
        p = write_manifest(m)
        try:
            errors, _ = validate_datasets.validate(p)
        finally:
            os.unlink(p)
        self.assertTrue(any("record_count is 'unknown'" in e for e in errors), errors)

    def test_literal_bucket_fails(self):
        m = manifest_copy()
        m["datasets"][0]["s3_prefixes"]["raw"] = "s3://my-real-bucket/raw/dblp/"
        p = write_manifest(m)
        try:
            errors, _ = validate_datasets.validate(p)
        finally:
            os.unlink(p)
        self.assertTrue(any("not a {raw_bucket} template" in e for e in errors), errors)

    def test_estimate_must_not_claim_verification(self):
        m = manifest_copy()
        m["subset_rule"]["estimate"]["verified"] = True
        p = write_manifest(m)
        try:
            errors, _ = validate_datasets.validate(p)
        finally:
            os.unlink(p)
        self.assertTrue(any("kind: estimate, verified: false" in e for e in errors), errors)

    def test_sample_checksum_mismatch_fails(self):
        m = manifest_copy()
        m["samples"][0]["sha256"] = "0" * 64
        p = write_manifest(m)
        try:
            errors, _ = validate_datasets.validate(p)
        finally:
            os.unlink(p)
        self.assertTrue(any("sha256 mismatch" in e for e in errors), errors)


class ManifestEvidenceTests(unittest.TestCase):
    def _errors(self, m):
        p = write_manifest(m)
        try:
            errors, _ = validate_datasets.validate(p)
        finally:
            os.unlink(p)
        return errors

    def test_all_19_openalex_sources_have_names_and_evidence(self):
        m = dslib.load_manifest()
        src = estimate_subset_openalex.manifest_sources(m)
        self.assertEqual(len(src), 19)
        self.assertEqual(len({s for _, s, _ in src}), 19)
        for v in m["subset_rule"]["dblp"]["venues"]:
            self.assertIn(v["openalex_source_id"], v["openalex_evidence"]["url"])
            self.assertTrue(v["openalex_display_name"])

    def test_venue_without_display_name_fails(self):
        m = manifest_copy()
        del m["subset_rule"]["dblp"]["venues"][3]["openalex_display_name"]
        self.assertTrue(any("display_name missing" in e for e in self._errors(m)))

    def test_calibration_source_without_evidence_fails(self):
        m = manifest_copy()
        del m["subset_rule"]["estimate"]["calibration"]["openalex_sources"]["conf/dac/"]["evidence"]
        self.assertTrue(any("S4210231368" in e and "evidence" in e for e in self._errors(m)))

    def test_synthetic_sample_needs_evidence(self):
        # Finding 12: the 10.5555 test-prefix claim must carry evidence.
        m = manifest_copy()
        s = next(x for x in m["samples"] if x["kind"] == "synthetic")
        self.assertTrue(any("api.crossref.org/prefixes/10.5555" in e["url"] for e in s["evidence"]))
        del s["evidence"]
        self.assertTrue(any("synthetic sample needs evidence" in e for e in self._errors(m)))


class SourceMappingCheckTests(unittest.TestCase):
    """Finding 4: estimate_subset_openalex.py aborts on a wrong source mapping (offline fakes)."""

    def test_match(self):
        self.assertEqual(estimate_subset_openalex.check_source(
            "S4363608773", "SIGIR", {"id": "https://openalex.org/S4363608773", "display_name": "SIGIR"}), [])

    def test_name_mismatch_and_redirect_and_failure(self):
        self.assertTrue(estimate_subset_openalex.check_source(
            "S4363608773", "SIGIR", {"id": "https://openalex.org/S4363608773", "display_name": "KDD"}))
        self.assertTrue(estimate_subset_openalex.check_source(
            "S4363608773", "SIGIR", {"id": "https://openalex.org/S1", "display_name": "SIGIR"}))
        self.assertTrue(estimate_subset_openalex.check_source("S4363608773", "SIGIR", None))

    def test_verify_sources_uses_manifest_names(self):
        m = dslib.load_manifest()
        names = {sid: name for _, sid, name in estimate_subset_openalex.manifest_sources(m)}

        def fake(url):
            sid = url.split("/sources/")[1].split("?")[0]
            name = names[sid] if sid != "S4363606243" else "International Conference on Machine Learning"
            return {"id": "https://openalex.org/" + sid, "display_name": name}

        with contextlib.redirect_stdout(io.StringIO()):
            errs = estimate_subset_openalex.verify_sources(m, False, fetch=fake)
        self.assertEqual(len(errs), 1)
        self.assertIn("S4363606243", errs[0])


class FakeFetcher(fetch_enrichment.Fetcher):
    """Offline Fetcher: answers list queries from canned per-page data."""

    def __init__(self, cache, dry_run, openalex=None, crossref=None, fail_urls=()):
        super().__init__(cache, dry_run)
        self.openalex = openalex or {}
        self.crossref = crossref or {}
        self.fail_urls = fail_urls
        self.urls = []

    def get_json(self, source, url, headers):
        self.urls.append(url)
        if any(f in url for f in self.fail_urls):
            self.fail("HTTP 500 for %s" % url)
            return None
        q = dict(urllib.parse.parse_qsl(urllib.parse.urlsplit(url).query))
        if source == "openalex":
            count, pages = self.openalex
            page = pages[int(q["page"]) - 1] if int(q["page"]) <= len(pages) else []
            return {"meta": {"count": count}, "results": page}
        if source == "crossref":
            total, items = self.crossref
            off, rows = int(q["offset"]), int(q["rows"])
            return {"message": {"total-results": total, "items": items[off:off + rows]}}
        return {}


class EnrichmentCompletenessTests(unittest.TestCase):
    """Finding 6: paging to meta.count, DOI membership, non-zero exit on failure."""

    def fetcher(self, **kw):
        return FakeFetcher("/nonexistent-cache", False, **kw)

    def quiet(self, fn, *a):
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            return fn(*a)

    def test_openalex_pages_until_meta_count(self):
        w = lambda i: {"id": "https://openalex.org/W%d" % i, "doi": "https://doi.org/10.1/a"}
        f = self.fetcher(openalex=(3, [[w(1), w(2)], [w(3)]]))
        self.quiet(fetch_enrichment.fetch_openalex_works, f, ["10.1/a"], 50)
        self.assertEqual(len(f.urls), 2)
        self.assertIn("page=2", f.urls[1])
        self.assertEqual(f.failures, [])

    def test_openalex_truncation_fails(self):
        w = {"id": "https://openalex.org/W1", "doi": "https://doi.org/10.1/a"}
        f = self.fetcher(openalex=(5, [[w]]))
        self.quiet(fetch_enrichment.fetch_openalex_works, f, ["10.1/a"], 50)
        self.assertTrue(any("truncated" in x for x in f.failures), f.failures)

    def test_openalex_unrequested_doi_fails(self):
        f = self.fetcher(openalex=(1, [[{"id": "https://openalex.org/W1", "doi": "https://doi.org/10.1/zzz"}]]))
        self.quiet(fetch_enrichment.fetch_openalex_works, f, ["10.1/a"], 50)
        self.assertTrue(any("not in the requested set" in x for x in f.failures), f.failures)

    def test_openalex_unrequested_author_fails(self):
        f = self.fetcher(openalex=(1, [[{"id": "https://openalex.org/A999"}]]))
        self.quiet(fetch_enrichment.fetch_openalex_authors, f, ["A5080716895"], 50)
        self.assertTrue(any("A999" in x for x in f.failures), f.failures)

    def test_crossref_pages_and_membership(self):
        items = [{"DOI": "10.1/a"}] * 150  # same DOI repeated: 2 pages of 100
        f = self.fetcher(crossref=(150, items))
        self.quiet(fetch_enrichment.fetch_crossref_works, f, ["10.1/a"], 50)
        self.assertEqual(len(f.urls), 2)
        self.assertIn("offset=100", f.urls[1])
        self.assertEqual(f.failures, [])

    def test_crossref_unrequested_doi_fails(self):
        f = self.fetcher(crossref=(2, [{"DOI": "10.1/A"}, {"DOI": "10.1/other"}]))
        self.quiet(fetch_enrichment.fetch_crossref_works, f, ["10.1/a"], 50)
        self.assertEqual(len(f.failures), 1)
        self.assertIn("10.1/other", f.failures[0])

    def test_crossref_truncation_fails(self):
        f = self.fetcher(crossref=(3, [{"DOI": "10.1/a"}]))
        self.quiet(fetch_enrichment.fetch_crossref_works, f, ["10.1/a"], 50)
        self.assertTrue(any("truncated" in x for x in f.failures), f.failures)

    def test_failed_request_gives_nonzero_exit(self):
        with tempfile.TemporaryDirectory() as d:
            ids = os.path.join(d, "ids.txt")
            with open(ids, "w") as fh:
                fh.write("10.1145/3539618.3591892\n")

            def factory(cache, dry):
                return FakeFetcher(cache, dry, crossref=(0, []), fail_urls=("api.crossref.org",))

            rc = self.quiet(fetch_enrichment.main, ["crossref-works", "--ids", ids, "--cache", os.path.join(d, "c")],
                            factory)
            self.assertEqual(rc, 1)

            def ok_factory(cache, dry):
                return FakeFetcher(cache, dry, crossref=(1, [{"DOI": "10.1145/3539618.3591892"}]))

            rc = self.quiet(fetch_enrichment.main, ["crossref-works", "--ids", ids, "--cache", os.path.join(d, "c")],
                            ok_factory)
            self.assertEqual(rc, 0)

    def test_cache_inside_repository_refused(self):
        with self.assertRaises(SystemExit):
            self.quiet(fetch_enrichment.main, ["crossref-works", "--ids", "x", "--cache",
                                               os.path.join(dslib.REPO_DIR, "modules", "tmp-cache"), "--dry-run"])
        self.assertFalse(os.path.exists(os.path.join(dslib.REPO_DIR, "modules", "tmp-cache")))


class _RangeServer(http.server.BaseHTTPRequestHandler):
    """Local test server. Paths: /honour (206), /ignore-cl (200 + Content-Length), /ignore-chunked
    (200, no length, close-delimited). Counts body bytes actually written."""
    TOTAL = 64 * 1024 * 1024
    sent = {}

    def log_message(self, *a):
        pass

    def _body(self, n):
        chunk = bytes(range(256)) * 256  # 64 KiB, deterministic
        written = 0
        try:
            while written < n:
                part = chunk[: min(len(chunk), n - written)]
                self.wfile.write(part)
                written += len(part)
                _RangeServer.sent[self.path] = written
        except (BrokenPipeError, ConnectionResetError):
            pass

    def do_GET(self):
        _RangeServer.sent[self.path] = 0
        if self.path == "/honour":
            rng = self.headers.get("Range", "")
            end = int(rng.split("-")[1])
            self.send_response(206)
            self.send_header("Content-Range", "bytes 0-%d/%d" % (end, self.TOTAL))
            self.send_header("Content-Length", str(end + 1))
            self.end_headers()
            self._body(end + 1)
        elif self.path == "/ignore-cl":
            self.send_response(200)
            self.send_header("Content-Length", str(self.TOTAL))
            self.end_headers()
            self._body(self.TOTAL)
        else:
            self.send_response(200)
            self.send_header("Connection", "close")
            self.end_headers()
            self._body(self.TOTAL)


class PrefixByteCapTests(unittest.TestCase):
    """Finding 3: the bounded DBLP prefix fetch can never write more than N bytes."""
    CAP = 1024 * 1024

    @classmethod
    def setUpClass(cls):
        cls.httpd = http.server.ThreadingHTTPServer(("127.0.0.1", 0), _RangeServer)
        cls.thread = threading.Thread(target=cls.httpd.serve_forever, daemon=True)
        cls.thread.start()
        cls.base = "http://127.0.0.1:%d" % cls.httpd.server_address[1]

    @classmethod
    def tearDownClass(cls):
        cls.httpd.shutdown()
        cls.httpd.server_close()

    def fetch(self, path):
        d = tempfile.mkdtemp()
        out = os.path.join(d, "prefix")
        r = bash_lib('ds_fetch_prefix "$1" "$2" "$3" "$4"', self.base + path, out, str(self.CAP),
                     str(_RangeServer.TOTAL))
        return r, out

    def test_honoured_range_succeeds_with_exact_length(self):
        r, out = self.fetch("/honour")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(os.path.getsize(out), self.CAP)

    def test_ignored_range_with_length_is_refused(self):
        r, out = self.fetch("/ignore-cl")
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("unexpected range response", r.stderr)
        self.assertFalse(os.path.exists(out) or os.path.exists(out + ".part"))
        self.assertLess(_RangeServer.sent.get("/ignore-cl", 0), 16 * self.CAP)

    def test_ignored_range_without_length_is_cut_at_cap(self):
        r, out = self.fetch("/ignore-chunked")
        self.assertNotEqual(r.returncode, 0)
        self.assertFalse(os.path.exists(out) or os.path.exists(out + ".part"))
        # The transfer was aborted far below the 64 MiB body (socket buffers allow some slack).
        self.assertLess(_RangeServer.sent.get("/ignore-chunked", 0), 16 * self.CAP)

    def test_dblp_script_uses_capped_fetch(self):
        with open(os.path.join(dslib.SCRIPTS_DIR, "download_dblp.sh"), encoding="utf-8") as fh:
            sh = fh.read()
        self.assertIn('ds_fetch_prefix "$DBLP_RELEASE_URL"', sh)
        self.assertNotIn("curl -fsS --retry 3 -A \"$(ds_user_agent)\" -r", sh)
        with open(LIB_SH, encoding="utf-8") as fh:
            lib = fh.read()
        self.assertIn('--max-filesize "$n"', lib)
        self.assertIn('head -c "$n"', lib)


class ShellGuardTests(unittest.TestCase):
    def test_pinned_prefix_sha_mismatch_fails(self):
        # Finding 8: a prefix that differs from the pinned sha256 is rejected and quarantined.
        with tempfile.TemporaryDirectory() as d:
            f = os.path.join(d, "p")
            with open(f, "wb") as fh:
                fh.write(b"not the dblp prefix")
            r = bash_lib('ds_verify_pinned_sha256 "$1" "$2"', f, "62d59ff664ae15fdf4a3dfb0a64b4e1e2559642198477b09555d92658ca07c2f")
            self.assertEqual(r.returncode, 5)
            self.assertIn("PINNED CHECKSUM MISMATCH", r.stderr)
            self.assertTrue(os.path.exists(f + ".bad"))
            with open(f, "wb") as fh:
                fh.write(b"abc")
            r = bash_lib('ds_verify_pinned_sha256 "$1" "$2"', f, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
            self.assertEqual(r.returncode, 0, r.stderr)

    def test_dblp_script_checks_pinned_prefix(self):
        with open(os.path.join(dslib.SCRIPTS_DIR, "download_dblp.sh"), encoding="utf-8") as fh:
            sh = fh.read()
        self.assertIn('ds_verify_pinned_sha256 "$out" "$pinned_sha"', sh)
        m = dslib.load_manifest()
        cal = m["subset_rule"]["estimate"]["calibration"]["measured_dblp_2015_2024"]
        self.assertIn('DBLP_PREFIX_16MIB_SHA256="%s"' % cal["prefix_sha256"], sh)
        self.assertIn('DBLP_PREFIX_16MIB_BYTES="%d"' % cal["prefix_bytes"], sh)

    def test_repo_guard_covers_whole_repository(self):
        # Finding 8: destinations anywhere in the repository (not only datasets/) are refused.
        for rel in ("modules/tmp-refuse", "tmp-refuse", "."):
            target = os.path.normpath(os.path.join(dslib.REPO_DIR, rel))
            r = bash_lib('ds_refuse_repo_dest "$1"', target)
            self.assertEqual(r.returncode, 2, rel + r.stderr)
            self.assertIn("refusing destination inside the repository", r.stderr)
        self.assertFalse(os.path.exists(os.path.join(dslib.REPO_DIR, "modules", "tmp-refuse")))
        self.assertFalse(os.path.exists(os.path.join(dslib.REPO_DIR, "tmp-refuse")))
        with tempfile.TemporaryDirectory() as d:
            r = bash_lib('ds_refuse_repo_dest "$1"', os.path.join(d, "ok"))
            self.assertEqual(r.returncode, 0, r.stderr)

    def test_openalex_key_validation(self):
        # Finding 7: manifest keys are validated before use as local paths.
        good = "data/jsonl/works/updated_date=2016-06-24/part_0000.gz"
        self.assertEqual(bash_lib('ds_openalex_key_ok "$1" works', good).returncode, 0)
        for bad, ent in (("../../x", "works"), ("data/jsonl/works/../../../etc/x.gz", "works"),
                         ("/etc/passwd", "works"), ("data/jsonl/works/updated_date=2016-06-24/part_0000.gz/..", "works"),
                         ("data/jsonl/authors/updated_date=2016-06-24/part_0000.gz", "works"),
                         ("data/jsonl/works/updated_date=2016-06-24/part_0000.gz\n../x", "works"),
                         ("data/jsonl/works/updated_date=../part_0.gz", "works")):
            self.assertNotEqual(bash_lib('ds_openalex_key_ok "$1" "$2"', bad, ent).returncode, 0, bad)
        with open(os.path.join(dslib.SCRIPTS_DIR, "download_openalex_snapshot.sh"), encoding="utf-8") as fh:
            self.assertIn('ds_openalex_key_ok "$key" "$entity"', fh.read())


class ScriptBoundTests(unittest.TestCase):
    SCRIPTS = dslib.SCRIPTS_DIR

    def run_script(self, *args, env=None):
        e = dict(os.environ)
        e.update(env or {})
        return subprocess.run(list(args), capture_output=True, text=True, env=e)

    def test_dblp_dry_run_is_bounded_by_default(self):
        r = self.run_script("bash", os.path.join(self.SCRIPTS, "download_dblp.sh"), "--dry-run")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn("bytes 0-16777215", r.stdout)
        self.assertNotIn("1116142729 bytes", r.stdout)

    def test_dblp_prefix_cap(self):
        r = self.run_script("bash", os.path.join(self.SCRIPTS, "download_dblp.sh"), "--dry-run",
                            "--prefix-bytes", str(65 * 1024 * 1024))
        self.assertEqual(r.returncode, 2)

    def test_dblp_refuses_repo_destination(self):
        r = self.run_script("bash", os.path.join(self.SCRIPTS, "download_dblp.sh"),
                            "--dest", os.path.join(dslib.DATASETS_DIR, "samples", "tmp-refuse"))
        self.assertEqual(r.returncode, 2, r.stdout + r.stderr)
        self.assertIn("refusing destination inside the repository", r.stderr)
        self.assertFalse(os.path.exists(os.path.join(dslib.DATASETS_DIR, "samples", "tmp-refuse")))

    def test_orcid_data_requires_full(self):
        r = self.run_script("bash", os.path.join(self.SCRIPTS, "download_orcid_public_data_file.sh"),
                            "--file", "ORCID_2026_10_summaries.tar.gz", "--dry-run")
        self.assertEqual(r.returncode, 2)

    def test_openalex_parts_cap(self):
        r = self.run_script("bash", os.path.join(self.SCRIPTS, "download_openalex_snapshot.sh"),
                            "--max-parts", "3", "--dry-run")
        self.assertEqual(r.returncode, 2)

    def test_enrichment_cap_without_full(self):
        with tempfile.TemporaryDirectory() as d:
            ids = os.path.join(d, "ids.txt")
            with open(ids, "w") as fh:
                fh.write("10.1145/3539618.3591892\n")
            r = self.run_script(sys.executable, os.path.join(self.SCRIPTS, "fetch_enrichment.py"), "crossref-works",
                                "--ids", ids, "--cache", os.path.join(d, "c"), "--max-records", "5000", "--dry-run")
            self.assertNotEqual(r.returncode, 0)
            self.assertIn("without --full", r.stdout + r.stderr)

    def test_enrichment_dry_run_rejects_invalid_ids(self):
        with tempfile.TemporaryDirectory() as d:
            ids = os.path.join(d, "ids.txt")
            with open(ids, "w") as fh:
                fh.write("0000-0003-3226-9189\n0000-0000-0000-0000\n")
            r = self.run_script(sys.executable, os.path.join(self.SCRIPTS, "fetch_enrichment.py"), "orcid",
                                "--ids", ids, "--cache", os.path.join(d, "c"), "--dry-run")
            self.assertEqual(r.returncode, 0, r.stderr)
            self.assertIn("rejected invalid orcid", r.stderr)
            self.assertEqual(r.stdout.count("plan: GET"), 2)


if __name__ == "__main__":
    unittest.main()
