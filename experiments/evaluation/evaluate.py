#!/usr/bin/env python3
"""ScholarChain Phase 16 evaluation tool: one self-contained file.

Owner: experiment-engineer. Python 3 standard library only; no sibling module is imported
(`python3 -I` leaves this folder off the import path). Run only by the human, from the
repository root:

    python3 -I experiments/evaluation/evaluate.py SUBCOMMAND ARGS --suite experiments/evaluation/suite-v1.json --entry ID
    python3 -I experiments/evaluation/evaluate.py SUBCOMMAND --fixtures

runtime: untested

Subcommands (X1 builds the first five; X2 builds the last three):
  retrieval      group 3: per-question recall@K and reciprocal rank from the E report, bootstrap over questions
  performance    group 5: Phase 15 derived values plus the by-hand totals of results-summary.md section 8
  factory        group 6: deduplicated factory events from docs/agent-runs, unclassified rows, audit sample
  reproduce      group 7: folder comparison by sha256 (intended or unexplained), or --release-records
  ratings-sheet  group 4 (c): the blind rating sheet built from answer files
  answers        group 4 (a): verifier-reported claim rates, abstention rates and codes, answer-key agreement
                 where the frozen fields hold it, per source (never pooled); intervals only over
                 independent answers (P16-C-02): bootstrap over answers where per-answer values exist and
                 at least 2 answers have claims in the set (P16-L-08), Wilson for answer-level rates,
                 else descriptive (k, n, proportion); explain rows are
                 always descriptive; clip 01's attempts are collapsed into one answer per question
  graph          group 2: spark-engineer's suite table summarized (hand-worked coverage per check), the
                 invariant counts of the Phase 15 spark-outputs.csv files, provenance fields per sampled
                 DynamoDB item per table (descriptive; no interval), optional E5 sbt summary line
  er             group 1 (reduced plan): the committed Phase 5 report's final table, intervals, confusion
                 matrices and the all-rows metrics recomputed from the unweighted confusion matrix

Shared rules (decision 13 of docs/agent-runs/phase-16/prompt.md):
  - reads only committed files or the paths given as arguments; writes only under
    experiments/evaluation/raw/GROUP/RUN_ID/ (RUN_ID = UTC stamp, short HEAD, 4 random hex);
  - prints its own sha256, the suite's sha256, every input with its sha256 and every output;
  - run metadata (run ID, times, paths, hashes) goes to run.json; computed results go to CSV
    files only, so a fixture comparison covers the results and never run.json;
  - printed lines start with STEP, RESULT, WARN, ERROR or SUMMARY;
  - never fills a missing value: a missing value is an empty cell or the text
    "not measurable from the frozen evidence (REASON)";
  - every rate is written with its numerator, denominator and inclusion rule;
  - every float is written rounded to at most 6 decimals, trailing zeros removed;
  - --fixtures reads only experiments/evaluation/fixtures/SUBCOMMAND/** and writes only into
    a new mktemp folder outside the repository, whose path it prints; each case's result files
    are compared byte for byte with the case's expected/ folder (run.json excluded);
  - exit 0 success, 2 usage, 3 missing input (or an input that differs from the signed suite),
    5 tool failure (also a malformed input file or a failed fixture comparison).

A real run needs --suite and --entry. The tool then checks every input the suite entry pins
(a file sha256, or a tree digest) before it computes anything. Tree digest (same bytes as
`git ls-files -- ROOT | xargs shasum -a 256 | shasum -a 256` for a clean tree): sha256 of the
lines "SHA256  REPO_RELATIVE_PATH\\n" of every file below the roots (names starting with "."
skipped; an optional file-name suffix filter), sorted by path.
"""

import argparse
import csv
import hashlib
import io
import json
import math
import os
import random
import re
import sys
import tempfile
from datetime import datetime, timezone

sys.dont_write_bytecode = True

TOOL_VERSION = "evaluate-v1-r3"
SCRIPT_PATH = os.path.abspath(__file__)
EVAL_DIR = os.path.dirname(SCRIPT_PATH)
REPO_ROOT = os.path.dirname(os.path.dirname(EVAL_DIR))
FIXTURE_ROOT = os.path.join(EVAL_DIR, "fixtures")
RAW_OUT_ROOT = os.path.join(EVAL_DIR, "raw")

GROUPS = {
    "retrieval": "retrieval",
    "performance": "performance",
    "factory": "factory",
    "reproduce": "repro",
    "ratings-sheet": "ratings",
    "answers": "answers",
    "graph": "graph",
    "er": "er",
}

EXIT_OK = 0
EXIT_USAGE = 2
EXIT_MISSING = 3
EXIT_FAILURE = 5

NOT_MEASURABLE = "not measurable from the frozen evidence (%s)"


class ToolExit(Exception):
    def __init__(self, code, message):
        Exception.__init__(self, message)
        self.code = code
        self.message = message


class Parser(argparse.ArgumentParser):
    """argparse that raises ToolExit(2) instead of exiting, so fixture runs continue."""

    def error(self, message):
        raise ToolExit(EXIT_USAGE, "usage: %s: %s" % (self.prog, message))


# ---- small helpers ---------------------------------------------------------------------


def say(kind, text):
    print("%s %s" % (kind, text))
    sys.stdout.flush()


def num(v):
    """Cell text: '' for None, yes/no for booleans, floats rounded to 6 decimals."""
    if v is None:
        return ""
    if isinstance(v, bool):
        return "yes" if v else "no"
    if isinstance(v, int):
        return str(v)
    if isinstance(v, float):
        if math.isnan(v) or math.isinf(v):
            return ""
        s = "%.6f" % round(v, 6)
        if "." in s:
            s = s.rstrip("0").rstrip(".")
        if s in ("-0", ""):
            s = "0"
        return s
    return str(v)


def sha256_bytes(data):
    return hashlib.sha256(data).hexdigest()


def sha256_text(text):
    return sha256_bytes(text.encode("utf-8"))


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        while True:
            chunk = fh.read(1 << 20)
            if not chunk:
                break
            h.update(chunk)
    return h.hexdigest()


def utc_now():
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def utc(epoch):
    if epoch is None:
        return ""
    return datetime.fromtimestamp(int(epoch), tz=timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def parse_time(text):
    """ISO 8601 (with Z or an offset) or epoch seconds, to epoch seconds; None if unreadable."""
    if text is None or isinstance(text, bool):
        return None
    if isinstance(text, (int, float)):
        return int(text)
    t = str(text).strip()
    if re.match(r"^[0-9]+$", t):
        return int(t)
    if t.endswith("Z"):
        t = t[:-1] + "+00:00"
    try:
        dt = datetime.fromisoformat(t)
    except ValueError:
        return None
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    return int(dt.timestamp())


def to_int(v):
    if v is None:
        return None
    s = str(v).strip()
    if re.match(r"^-?[0-9]+$", s):
        return int(s)
    return None


def nearest_rank_index(p, m):
    """0-based index of the nearest-rank p-quantile (rank ceil(p * m)) in a sorted list of m values."""
    return min(max(int(math.ceil(p * m)) - 1, 0), m - 1)


def wilson(successes, n, z=1.959964):
    """Wilson score interval (95 % by default); (None, None) when n is 0. For X2's answer-level rates."""
    if n <= 0:
        return None, None
    p = successes / float(n)
    den = 1.0 + z * z / n
    centre = (p + z * z / (2.0 * n)) / den
    half = z * math.sqrt(p * (1.0 - p) / n + z * z / (4.0 * n * n)) / den
    return max(0.0, centre - half), min(1.0, centre + half)


def bootstrap_means(series_by_key, n, seed, resamples):
    """Paired percentile bootstrap of means over n units (questions or answers).

    series_by_key: key -> list of n values (None = unit has no value for this key, skipped).
    Every resample draws n unit indices with replacement from random.Random(seed); the same
    indices serve every key. Returns key -> (low, high): the nearest-rank 2.5 % and 97.5 %
    quantiles of the resample means, or (None, None) when no resample had a value.
    """
    rng = random.Random(seed)
    draws = dict((k, []) for k in series_by_key)
    for _ in range(resamples):
        idx = [rng.randrange(n) for _ in range(n)]
        for k, vals in series_by_key.items():
            picked = [vals[i] for i in idx if vals[i] is not None]
            if picked:
                draws[k].append(sum(picked) / float(len(picked)))
    out = {}
    for k, d in draws.items():
        if not d:
            out[k] = (None, None)
            continue
        d.sort()
        out[k] = (d[nearest_rank_index(0.025, len(d))], d[nearest_rank_index(0.975, len(d))])
    return out


def listing(root, suffix=None):
    """Files below root (recursive) as {posix path relative to root: absolute path}; dot names skipped."""
    out = {}
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = sorted(d for d in dirnames if not d.startswith("."))
        for f in filenames:
            if f.startswith("."):
                continue
            if suffix and not f.endswith(suffix):
                continue
            full = os.path.join(dirpath, f)
            rel = os.path.relpath(full, root).replace(os.sep, "/")
            out[rel] = full
    return out


def repo_rel(path):
    a = os.path.abspath(path)
    r = os.path.relpath(a, REPO_ROOT)
    if r == ".." or r.startswith(".." + os.sep):
        return a
    return r.replace(os.sep, "/")


def tree_digest(roots, suffix=None):
    """(digest, file count) over every file below the roots; see the module docstring."""
    entries = []
    for root in roots:
        for _, full in listing(root, suffix).items():
            entries.append((repo_rel(full), sha256_file(full)))
    entries.sort()
    text = "".join("%s  %s\n" % (s, p) for p, s in entries)
    return sha256_text(text), len(entries)


def read_head():
    """Short HEAD (12 hex) read from .git without running git; 'nohead' if unreadable."""
    git = os.path.join(REPO_ROOT, ".git")
    try:
        with open(os.path.join(git, "HEAD"), "r") as fh:
            head = fh.read().strip()
        if head.startswith("ref:"):
            ref = head.split(":", 1)[1].strip()
            ref_path = os.path.join(git, *ref.split("/"))
            if os.path.isfile(ref_path):
                with open(ref_path, "r") as fh:
                    head = fh.read().strip()
            else:
                head = ""
                packed = os.path.join(git, "packed-refs")
                if os.path.isfile(packed):
                    with open(packed, "r") as fh:
                        for line in fh:
                            parts = line.strip().split(" ")
                            if len(parts) == 2 and parts[1] == ref:
                                head = parts[0]
        if re.match(r"^[0-9a-f]{40}$", head):
            return head[:12]
    except OSError:
        pass
    return "nohead"


def make_run_id():
    return "%s-%s-%s" % (
        datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ"),
        read_head(),
        os.urandom(2).hex(),
    )


def require_file(path, what):
    if not path or not os.path.isfile(path):
        raise ToolExit(EXIT_MISSING, "missing input (%s): %s" % (what, path))


def require_dir(path, what):
    if not path or not os.path.isdir(path):
        raise ToolExit(EXIT_MISSING, "missing input (%s): %s" % (what, path))


TOOL_SHA = sha256_file(SCRIPT_PATH)


# ---- one run ------------------------------------------------------------------------------


class Run:
    """Inputs read (with sha256), outputs written, and the output folder of one subcommand run."""

    def __init__(self, sub, argv, fixture_out=None):
        self.sub = sub
        self.argv = list(argv)
        self.fixture_out = fixture_out
        self.inputs = []
        self._seen = set()
        self.outputs = []
        self.out_dir = None
        self.run_id = "fixture" if fixture_out is not None else None
        self.started = utc_now()
        self.suite_path = None
        self.suite_sha = None
        self.entry = None
        self.meta = {}

    def read_bytes(self, path):
        with open(path, "rb") as fh:
            data = fh.read()
        digest = sha256_bytes(data)
        key = repo_rel(path)
        if key not in self._seen:
            self._seen.add(key)
            self.inputs.append((key, digest))
            say("STEP", "input sha256=%s path=%s" % (digest, key))
        return data

    def read_text(self, path):
        data = self.read_bytes(path)
        try:
            return data.decode("utf-8")
        except UnicodeDecodeError:
            raise ToolExit(EXIT_FAILURE, "malformed input (not UTF-8): %s" % repo_rel(path))

    def read_json(self, path):
        text = self.read_text(path)
        try:
            return json.loads(text)
        except ValueError:
            raise ToolExit(EXIT_FAILURE, "malformed input (not JSON): %s" % repo_rel(path))

    def read_csv(self, path):
        """(field names, rows as dicts); ([], []) for an empty file."""
        text = self.read_text(path)
        reader = csv.DictReader(io.StringIO(text, newline=""))
        rows = list(reader)
        return list(reader.fieldnames or []), rows

    def prepare_out(self):
        if self.out_dir is not None:
            return self.out_dir
        if self.fixture_out is not None:
            d = self.fixture_out
            os.makedirs(d)
        else:
            self.run_id = make_run_id()
            d = os.path.join(RAW_OUT_ROOT, GROUPS[self.sub], self.run_id)
            if os.path.exists(d):
                raise ToolExit(EXIT_FAILURE, "output folder exists: %s" % repo_rel(d))
            os.makedirs(d)
        self.out_dir = d
        say("STEP", "output folder %s" % repo_rel(d))
        return d

    def write_csv(self, name, header, rows):
        d = self.prepare_out()
        path = os.path.join(d, name)
        with open(path, "w", newline="", encoding="utf-8") as fh:
            w = csv.writer(fh, lineterminator="\n")
            w.writerow(header)
            for r in rows:
                w.writerow([num(v) for v in r])
        self.outputs.append(path)

    def finish(self):
        d = self.prepare_out()
        outs = []
        for p in self.outputs:
            s = sha256_file(p)
            outs.append({"path": repo_rel(p), "sha256": s})
            say("STEP", "output sha256=%s path=%s" % (s, repo_rel(p)))
        meta = {
            "format": "scholarchain.evaluation-run",
            "formatVersion": 1,
            "tool": repo_rel(SCRIPT_PATH),
            "toolSha256": TOOL_SHA,
            "toolVersion": TOOL_VERSION,
            "subcommand": self.sub,
            "runId": self.run_id,
            "startedUtc": self.started,
            "endedUtc": utc_now(),
            "argv": self.argv,
            "suite": self.suite_path,
            "suiteSha256": self.suite_sha,
            "entry": self.entry,
            "inputs": [{"path": p, "sha256": s} for p, s in self.inputs],
            "outputs": outs,
        }
        meta.update(self.meta)
        path = os.path.join(d, "run.json")
        with open(path, "w", encoding="utf-8") as fh:
            json.dump(meta, fh, indent=2, sort_keys=True)
            fh.write("\n")
        say("STEP", "output path=%s (run metadata; never compared)" % repo_rel(path))


def add_common(parser):
    parser.add_argument("--suite", help="the evaluation suite file (required for a real run)")
    parser.add_argument("--entry", help="the suite entry ID of this run (required for a real run)")


def setup_suite(run, ns):
    """Real run: load the suite, find the entry, check every pinned input. Fixture run: skipped."""
    if run.fixture_out is not None:
        return None
    if not ns.suite or not ns.entry:
        raise ToolExit(EXIT_USAGE, "usage: --suite and --entry are required for a real run")
    require_file(ns.suite, "suite")
    suite = run.read_json(ns.suite)
    run.suite_path = repo_rel(ns.suite)
    run.suite_sha = sha256_file(ns.suite)
    run.entry = ns.entry
    say("STEP", "suite sha256=%s path=%s entry=%s" % (run.suite_sha, run.suite_path, ns.entry))
    code = (suite.get("evaluationCode") or {}) if isinstance(suite, dict) else {}
    code_sha = code.get("sha256") if isinstance(code, dict) else None
    # P16-B-04: the tool is a pinned input like any other: a missing pin or a different sha256 exits 3.
    if not isinstance(code_sha, str) or not re.match(r"^[0-9a-f]{64}$", code_sha):
        raise ToolExit(EXIT_MISSING, "input differs from the signed suite: the suite has no evaluationCode.sha256 (64 hex)")
    if code_sha != TOOL_SHA:
        raise ToolExit(EXIT_MISSING, "input differs from the signed suite: %s (expected %s, found %s)"
                       % (repo_rel(SCRIPT_PATH), code_sha, TOOL_SHA))
    say("RESULT", "evaluation code matches the suite: %s" % repo_rel(SCRIPT_PATH))
    entries = suite.get("evaluations") if isinstance(suite, dict) else None
    entry = None
    for e in entries or []:
        if isinstance(e, dict) and e.get("id") == ns.entry:
            entry = e
    if entry is None:
        raise ToolExit(EXIT_USAGE, "usage: entry %s is not in the suite" % ns.entry)
    if entry.get("subcommand") != run.sub:
        raise ToolExit(EXIT_USAGE, "usage: entry %s is for subcommand %s" % (ns.entry, entry.get("subcommand")))
    for pin in entry.get("inputs") or []:
        check_pin(pin)
    return entry


def check_pin(pin):
    if not isinstance(pin, dict):
        return
    if "treeRoots" in pin:
        roots = [os.path.join(REPO_ROOT, r) for r in pin.get("treeRoots") or []]
        for r in roots:
            require_dir(r, "pinned tree root")
        digest, count = tree_digest(roots, pin.get("treeSuffix") or None)
        expected = pin.get("treeSha256")
        if digest != expected:
            raise ToolExit(EXIT_MISSING, "input differs from the signed suite: tree %s (expected %s, found %s over %d files)"
                           % (" ".join(pin.get("treeRoots") or []), expected, digest, count))
        say("RESULT", "pinned tree matches: %s (%d files)" % (" ".join(pin.get("treeRoots") or []), count))
        return
    path = pin.get("path")
    expected = pin.get("sha256")
    if not path or not isinstance(expected, str) or not re.match(r"^[0-9a-f]{64}$", expected):
        return
    full = os.path.join(REPO_ROOT, path)
    require_file(full, "pinned input")
    found = sha256_file(full)
    if found != expected:
        raise ToolExit(EXIT_MISSING, "input differs from the signed suite: %s (expected %s, found %s)" % (path, expected, found))
    say("RESULT", "pinned input matches: %s" % path)


# ---- retrieval (group 3) ------------------------------------------------------------------


def cmd_retrieval(run, argv):
    p = Parser(prog="evaluate.py retrieval")
    add_common(p)
    p.add_argument("--report", required=True, help="retrieval-eval-report.json of experiment E")
    p.add_argument("--benchmark", help="benchmark-v1.json (its sha256 is compared with the report's)")
    p.add_argument("--seed", type=int, required=True)
    p.add_argument("--resamples", type=int, default=2000)
    ns = p.parse_args(argv)
    setup_suite(run, ns)
    if ns.resamples < 1:
        raise ToolExit(EXIT_USAGE, "usage: --resamples must be at least 1")
    require_file(ns.report, "retrieval report")
    if ns.benchmark:
        require_file(ns.benchmark, "benchmark")
    report = run.read_json(ns.report)
    bench_sha = sha256_bytes(run.read_bytes(ns.benchmark)) if ns.benchmark else None

    bad = "malformed input (retrieval report: %s): " + repo_rel(ns.report)
    if not isinstance(report, dict) or report.get("format") != "scholarchain.rag.retrieval-eval-report":
        raise ToolExit(EXIT_FAILURE, bad % "format is not scholarchain.rag.retrieval-eval-report")
    questions = report.get("questions")
    modes = report.get("modesRequested")
    ks = (report.get("limits") or {}).get("ks") if isinstance(report.get("limits"), dict) else None
    if not isinstance(questions, list) or not questions:
        raise ToolExit(EXIT_FAILURE, bad % "no questions array")
    if not isinstance(modes, list) or not modes or not all(isinstance(m, str) for m in modes):
        raise ToolExit(EXIT_FAILURE, bad % "no modesRequested")
    if not isinstance(ks, list) or not ks or not all(isinstance(k, int) and not isinstance(k, bool) and k >= 1 for k in ks):
        raise ToolExit(EXIT_FAILURE, bad % "no limits.ks")
    ks = sorted(set(ks))
    for q in questions:
        if (not isinstance(q, dict) or not isinstance(q.get("id"), str) or not isinstance(q.get("targets"), int)
                or isinstance(q.get("targets"), bool) or q.get("targets") < 1 or not isinstance(q.get("modes"), dict)):
            raise ToolExit(EXIT_FAILURE, bad % "a question lacks id, targets >= 1 or modes")
        for m in modes:
            r = q["modes"].get(m)
            if r is not None and (not isinstance(r, dict) or not isinstance(r.get("targetRanks"), list)
                                  or not isinstance(r.get("top") or [], list)):
                raise ToolExit(EXIT_FAILURE, bad % ("question %s mode %s lacks targetRanks or top" % (q["id"], m)))

    questions = sorted(questions, key=lambda q: q["id"])
    n = len(questions)
    label = "EXPLORATORY (n = %d)" % n
    per_rows = []
    series = {}  # (mode, metric, k) -> list of n values or None
    unjudged = dict((m, [0, 0]) for m in modes)
    for m in modes:
        for k in ks:
            series[(m, "recall", k)] = [None] * n
        series[(m, "mrr", "")] = [None] * n
    for qi, q in enumerate(questions):
        targets = q["targets"]
        for m in modes:
            r = q["modes"].get(m)
            if r is None:
                per_rows.append([label, q["id"], q.get("category", ""), m, targets] + [None] * (4 + len(ks))
                                + [None, None, None, None, None, "mode result absent"])
                say("WARN", "question %s has no result for mode %s (excluded from that mode's means)" % (q["id"], m))
                continue
            ranks = [x for x in r["targetRanks"] if isinstance(x, int) and not isinstance(x, bool) and x >= 1]
            top = r.get("top") or []
            retrieved = len(top)
            judged = len(ranks)
            unj = max(retrieved - judged, 0)
            unjudged[m][0] += unj
            unjudged[m][1] += retrieved
            recalls = []
            for k in ks:
                v = sum(1 for x in ranks if x <= k) / float(targets)
                recalls.append(v)
                series[(m, "recall", k)][qi] = v
            rr = 1.0 / min(ranks) if ranks else 0.0
            series[(m, "mrr", "")][qi] = rr
            ra = r.get("recallAt") if isinstance(r.get("recallAt"), dict) else {}
            rep_rr = r.get("reciprocalRank")
            if all(str(k) in ra for k in ks) and isinstance(rep_rr, (int, float)):
                same = all(abs(float(ra[str(k)]) - recalls[i]) <= 1e-9 for i, k in enumerate(ks)) and abs(float(rep_rr) - rr) <= 1e-9
                matches = "yes" if same else "no"
            else:
                matches = "not in report"
            note = ""
            if isinstance(r.get("listLength"), int) and r.get("listLength") != retrieved:
                note = "listLength %d differs from top length %d" % (r.get("listLength"), retrieved)
            codes = r.get("abstentionCodes") if isinstance(r.get("abstentionCodes"), list) else []
            per_rows.append([label, q["id"], q.get("category", ""), m, targets, retrieved, judged, unj] + recalls
                            + [rr, r.get("error") is not None, bool(r.get("empty")), bool(r.get("byDesignWeak")),
                               ";".join(str(c) for c in codes), matches, note])
    run.meta["questions"] = n
    ci = bootstrap_means(series, n, ns.seed, ns.resamples)
    ci_method = "percentile bootstrap over questions; seed %d; %d resamples" % (ns.seed, ns.resamples)
    rule_mean = "questions of the report universe with a result for this mode; errored or empty results score 0"
    rule_unj = "retrieved IDs over all questions of this mode; unjudged = retrieved IDs outside the labels"
    sum_rows = []
    means = {}
    for m in modes:
        for key in [(m, "recall", k) for k in ks] + [(m, "mrr", "")]:
            vals = [v for v in series[key] if v is not None]
            total = sum(vals) if vals else None
            mean = (total / float(len(vals))) if vals else None
            means[key] = mean
            lo, hi = ci[key]
            sum_rows.append([label, m, key[1], key[2], total, len(vals), mean, lo, hi, ci_method, rule_mean])
        sum_rows.append([label, m, "precision", "", None, None, "not measurable (unjudged retrievals)", None, None, "",
                         "retrieved IDs outside the labels are unjudged; never counted as negatives"])
        sum_rows.append([label, m, "ndcg", "", None, None, "not computed (no graded relevance labels)", None, None, "", ""])
        u, t = unjudged[m]
        sum_rows.append([label, m, "unjudged_share", "", u, t, (u / float(t)) if t else None, None, None, "", rule_unj])

    checks = []
    ev = report.get("evalSet") if isinstance(report.get("evalSet"), dict) else {}
    rep_b = ev.get("benchmarkSha256")
    if bench_sha is None:
        checks.append([label, "benchmark sha256 equals report evalSet.benchmarkSha256", "", "", rep_b, "not checked (no --benchmark)", ""])
    else:
        checks.append([label, "benchmark sha256 equals report evalSet.benchmarkSha256", "", "", rep_b, bench_sha, rep_b == bench_sha])
    checks.append([label, "universe size equals report evalSet.questions", "", "", ev.get("questions"), n,
                   ev.get("questions") == n if isinstance(ev.get("questions"), int) else "not in report"])
    rep_modes = report.get("modes") if isinstance(report.get("modes"), dict) else {}
    for m in modes:
        rm = rep_modes.get(m) if isinstance(rep_modes.get(m), dict) else {}
        ra = rm.get("recallAt") if isinstance(rm.get("recallAt"), dict) else {}
        for k in ks:
            rv = ra.get(str(k))
            mine = means[(m, "recall", k)]
            eq = (abs(float(rv) - mine) <= 1e-9) if (isinstance(rv, (int, float)) and mine is not None) else "not in report"
            checks.append([label, "mean recall equals report modes summary", m, k, rv, mine, eq])
        rv = rm.get("mrr")
        mine = means[(m, "mrr", "")]
        eq = (abs(float(rv) - mine) <= 1e-9) if (isinstance(rv, (int, float)) and mine is not None) else "not in report"
        checks.append([label, "MRR equals report modes summary", m, "", rv, mine, eq])

    run.write_csv("per-question.csv",
                  ["label", "question_id", "category", "mode", "labelled_relevant", "retrieved", "judged_retrieved",
                   "unjudged_retrieved"] + ["recall_at_%d" % k for k in ks]
                  + ["reciprocal_rank", "error", "empty", "by_design_weak", "abstention_codes", "matches_report", "note"],
                  per_rows)
    run.write_csv("summary.csv",
                  ["label", "mode", "metric", "k", "numerator", "denominator", "value", "ci_low", "ci_high", "ci_method",
                   "inclusion_rule"], sum_rows)
    run.write_csv("checks.csv", ["label", "check", "mode", "k", "report_value", "recomputed_value", "equal"], checks)
    run.finish()
    for row in sum_rows:
        if row[2] in ("recall", "mrr"):
            say("RESULT", "%s %s %s@%s = %s (CI %s to %s)" % (label, row[1], row[2], row[3], num(row[6]), num(row[7]), num(row[8])))


# ---- performance (group 5) ----------------------------------------------------------------

PERF_MANIFESTS = [
    "p15-a-hadoop", "p15-a-spark", "p15-b-spark-burst", "p15-c-embedding-backlog",
    "p15-d-query-load", "p15-d-read-load", "p15-e-retrieval-modes", "p15-f-poison-replay",
]
PERF_DERIVED = [
    "p15-a-hadoop/attempts.csv", "p15-a-spark/attempts.csv", "p15-b-spark-burst/attempts.csv",
    "p15-c-embedding-backlog/observed.csv", "p15-d-query-load/levels.csv", "p15-d-read-load/levels.csv",
    "p15-e-retrieval-modes/modes.csv", "p15-f-poison-replay/observed.csv", "p15-f-poison-replay/parts.csv",
]
_SPARK_FILES = ["emr-instances.csv", "emr-steps.csv", "spark-stage-metrics.csv", "spark-stage-timings.csv", "metrics"]
PERF_EXPECTED = {
    "p15-a-hadoop": ["emr-instances.csv", "hadoop-step-counters.csv", "hadoop-job-counters.csv", "metrics"],
    "p15-a-spark": _SPARK_FILES,
    "p15-b-spark-burst": _SPARK_FILES,
    "p15-c-embedding-backlog": ["service-samples.csv", "metrics"],
    "p15-d-query-load": ["load-requests.csv", "metrics"],
    "p15-d-read-load": ["load-requests.csv", "metrics"],
    "p15-e-retrieval-modes": ["metrics"],
    "p15-f-poison-replay": ["service-samples.csv", "metrics"],
}
SPARK_SUMS = [
    ("tasks", "spark_tasks_total", "tasks"),
    ("stages", "spark_stages_total", "stages"),
    ("shuffleReadBytes", "spark_shuffle_read_bytes_total", "bytes"),
    ("shuffleWriteBytes", "spark_shuffle_write_bytes_total", "bytes"),
    ("diskSpillBytes", "spark_disk_spill_bytes_total", "bytes"),
    ("memorySpillBytes", "spark_memory_spill_bytes_total", "bytes"),
]
YEAR_COUNTER_RE = re.compile(r"^scholarchain\.output-records:([^/]+)/year=[0-9]{4}$")


def parse_label(label):
    parts = str(label).split(" ", 3)
    while len(parts) < 4:
        parts.append("")
    dims = {}
    for kv in parts[3].split(","):
        if "=" in kv:
            k, v = kv.split("=", 1)
            dims[k] = v
    return parts[0], parts[1], parts[2], dims


def read_series(run, adir):
    qpath = os.path.join(adir, "metrics", "queries.json")
    dpath = os.path.join(adir, "metrics", "metric-data.json")
    queries = run.read_json(qpath)
    data = run.read_json(dpath)
    if not isinstance(queries, list) or not isinstance(data, dict) or not isinstance(data.get("MetricDataResults"), list):
        raise ToolExit(EXIT_FAILURE, "malformed input (metric export): %s" % repo_rel(os.path.join(adir, "metrics")))
    by_id = {}
    for q in queries:
        if isinstance(q, dict) and "Id" in q:
            by_id[q["Id"]] = q
    out = []
    for r in data["MetricDataResults"]:
        if not isinstance(r, dict):
            continue
        sid = r.get("Id", "")
        ms = (by_id.get(sid) or {}).get("MetricStat") or {}
        met = ms.get("Metric") or {}
        ns_, name, stat = met.get("Namespace"), met.get("MetricName"), ms.get("Stat")
        dims = {}
        for d in met.get("Dimensions") or []:
            if isinstance(d, dict):
                dims[d.get("Name")] = d.get("Value")
        if not ns_ or not name:
            ns_, name, stat, dims = parse_label(r.get("Label", ""))
        pts = []
        for t, v in zip(r.get("Timestamps") or [], r.get("Values") or []):
            e = parse_time(t)
            if e is not None and isinstance(v, (int, float)) and not isinstance(v, bool):
                pts.append((e, float(v)))
        pts.sort()
        period = ms.get("Period") if isinstance(ms.get("Period"), int) else 60
        out.append({"id": sid, "ns": ns_ or "", "metric": name or "", "stat": stat or "", "dims": dims,
                    "points": pts, "period": period})
    return out


def cmd_performance(run, argv):
    p = Parser(prog="evaluate.py performance")
    add_common(p)
    p.add_argument("--raw-root", required=True, help="experiments/raw")
    p.add_argument("--derived-root", required=True, help="experiments/derived/out")
    p.add_argument("--manifests", default=",".join(PERF_MANIFESTS))
    p.add_argument("--derived-tables", default=",".join(PERF_DERIVED))
    p.add_argument("--instance-hour-usd", type=float, required=True)
    p.add_argument("--rate-source", default="infra/terraform/COSTS.md EMR row (estimate; unverified)")
    ns = p.parse_args(argv)
    setup_suite(run, ns)
    require_dir(ns.raw_root, "raw root")
    require_dir(ns.derived_root, "derived root")
    manifests = [m for m in ns.manifests.split(",") if m]
    tables = [t for t in ns.derived_tables.split(",") if t]
    rate = ns.instance_hour_usd

    missing, computed, routes, sums, minutes, bytask, derived = [], [], [], [], [], [], []
    for manifest in manifests:
        mdir = os.path.join(ns.raw_root, manifest)
        if not os.path.isdir(mdir):
            missing.append([manifest, "-", "raw folder", NOT_MEASURABLE % "folder absent"])
            continue
        attempts = sorted(a for a in os.listdir(mdir) if not a.startswith(".") and os.path.isdir(os.path.join(mdir, a)))
        if not attempts:
            missing.append([manifest, "-", "attempt folders", NOT_MEASURABLE % "no attempt folder"])
        for att in attempts:
            adir = os.path.join(mdir, att)

            def has(name, adir=adir):
                return os.path.isfile(os.path.join(adir, name))

            has_metrics = has("metrics/metric-data.json") and has("metrics/queries.json")
            for item in PERF_EXPECTED.get(manifest, ["metrics"]):
                if item == "metrics":
                    if not has_metrics:
                        missing.append([manifest, att, "metrics/metric-data.json and metrics/queries.json",
                                        NOT_MEASURABLE % "file absent"])
                elif not has(item):
                    missing.append([manifest, att, item, NOT_MEASURABLE % "file absent"])
            series = read_series(run, adir) if has_metrics else []

            if has("spark-stage-metrics.csv"):
                _, rows = run.read_csv(os.path.join(adir, "spark-stage-metrics.csv"))
                totals, groups = {}, set()
                for r in rows:
                    groups.add(r.get("stage_group") or "")
                    v = to_int(r.get("value"))
                    if v is not None:
                        totals[r.get("metric") or ""] = totals.get(r.get("metric") or "", 0) + v
                for metric, measure, unit in SPARK_SUMS:
                    if metric in totals:
                        computed.append([manifest, att, measure, totals[metric], unit, "spark-stage-metrics.csv",
                                         "sum of %s over %d stage groups" % (metric, len(groups))])
            if has("spark-stage-timings.csv"):
                _, rows = run.read_csv(os.path.join(adir, "spark-stage-timings.csv"))
                ms = [to_int(r.get("millis")) for r in rows]
                ms = [x for x in ms if x is not None]
                if ms:
                    computed.append([manifest, att, "spark_stage_timings_seconds_total", sum(ms) / 1000.0, "s",
                                     "spark-stage-timings.csv", "sum of millis / 1000 over %d stages" % len(ms)])
            if has("emr-steps.csv"):
                _, rows = run.read_csv(os.path.join(adir, "emr-steps.csv"))
                for r in rows:
                    s, e = to_int(r.get("start_epoch")), to_int(r.get("end_epoch"))
                    if s is not None and e is not None:
                        computed.append([manifest, att, "step_seconds:%s" % (r.get("step_name") or ""), e - s, "s",
                                         "emr-steps.csv", "end_epoch minus start_epoch"])
            if has("hadoop-step-counters.csv"):
                _, rows = run.read_csv(os.path.join(adir, "hadoop-step-counters.csv"))
                splits = [to_int(r.get("value")) for r in rows if (r.get("counter") or "") == "map_input_splits"]
                splits = [x for x in splits if x is not None]
                if splits:
                    computed.append([manifest, att, "hadoop_map_input_splits_total", sum(splits), "map tasks (planned)",
                                     "hadoop-step-counters.csv", "sum of map_input_splits over %d jobs" % len(splits)])
            if has("hadoop-job-counters.csv"):
                _, rows = run.read_csv(os.path.join(adir, "hadoop-job-counters.csv"))
                fam = {}
                for r in rows:
                    m = YEAR_COUNTER_RE.match(r.get("name") or "")
                    v = to_int(r.get("value"))
                    if m and v is not None:
                        key = (r.get("job_name") or "", m.group(1))
                        tot, cnt = fam.get(key, (0, 0))
                        fam[key] = (tot + v, cnt + 1)
                for (job, family) in sorted(fam):
                    tot, cnt = fam[(job, family)]
                    computed.append([manifest, att, "%s output-records %s" % (job, family), tot, "records",
                                     "hadoop-job-counters.csv", "sum over %d year counters" % cnt])
            if has("emr-instances.csv"):
                _, rows = run.read_csv(os.path.join(adir, "emr-instances.csv"))
                total, used = 0, 0
                for i, r in enumerate(rows, 1):
                    c, e = to_int(r.get("created_epoch")), to_int(r.get("ended_epoch"))
                    if c is None or e is None:
                        missing.append([manifest, att, "emr-instances.csv row %d" % i,
                                        NOT_MEASURABLE % "created_epoch or ended_epoch empty"])
                        continue
                    total += e - c
                    used += 1
                if used:
                    computed.append([manifest, att, "instance_count", used, "instances", "emr-instances.csv",
                                     "rows with created_epoch and ended_epoch"])
                    computed.append([manifest, att, "instance_seconds", total, "s", "emr-instances.csv",
                                     "sum of ended_epoch minus created_epoch"])
                    computed.append([manifest, att, "cost_estimate_usd", total / 3600.0 * rate, "USD",
                                     "emr-instances.csv",
                                     "estimate: instance_seconds / 3600 x %s USD per instance-hour (%s); EBS excluded"
                                     % (num(rate), ns.rate_source)])
            if has("load-requests.csv"):
                _, rows = run.read_csv(os.path.join(adir, "load-requests.csv"))
                counts = {}
                for r in rows:
                    key = (r.get("level") or "", r.get("route") or "", r.get("class") or "",
                           r.get("http_status") or "", r.get("error_code") or "")
                    counts[key] = counts.get(key, 0) + 1
                    allkey = ("all",) + key[1:]
                    counts[allkey] = counts.get(allkey, 0) + 1
                for key in sorted(counts, key=lambda k: (k[0] == "all", k[0], k[1], k[2], k[3], k[4])):
                    routes.append([manifest, att] + list(key) + [counts[key]])
            for s in series:
                if s["stat"] != "Sum":
                    continue
                dims = ";".join("%s=%s" % (k, s["dims"][k]) for k in sorted(s["dims"], key=lambda x: str(x)))
                pts = s["points"]
                sums.append([manifest, att, s["id"], s["ns"], s["metric"], s["stat"], dims, len(pts),
                             sum(v for _, v in pts) if pts else None,
                             utc(pts[0][0]) if pts else "", utc(pts[-1][0]) if pts else "",
                             "" if pts else "no datapoints (not a measured 0)"])
            if has("service-samples.csv"):
                _, rows = run.read_csv(os.path.join(adir, "service-samples.csv"))
                worker = []
                for r in rows:
                    e, ru = to_int(r.get("epoch_s")), to_int(r.get("running"))
                    if "worker" in (r.get("service") or "") and e is not None and ru is not None:
                        worker.append((e, ru))
                deleted = [s for s in series if s["metric"] == "NumberOfMessagesDeleted" and s["stat"] == "Sum"
                           and "embedding" in str(s["dims"].get("QueueName", ""))
                           and "dlq" not in str(s["dims"].get("QueueName", "")).lower()]
                if worker and deleted:
                    groups = {}
                    for s in deleted:
                        per = s["period"]
                        for t, v in s["points"]:
                            samp = [ru for (e, ru) in worker if t <= e < t + per]
                            lo = min(samp) if samp else None
                            hi = max(samp) if samp else None
                            minutes.append([manifest, att, utc(t), v, v / float(per), lo, hi, len(samp)])
                            if samp and lo == hi:
                                mins, tot = groups.get(hi, (0, 0.0))
                                groups[hi] = (mins + 1, tot + v)
                            elif not samp:
                                say("WARN", "%s/%s period %s has no worker sample" % (manifest, att, utc(t)))
                    for k in sorted(groups):
                        mins, tot = groups[k]
                        bytask.append([manifest, att, k, mins, tot, tot / (60.0 * mins),
                                       "few samples" if mins < 20 else "ok",
                                       "periods in which every worker sample shows this running count"])
    for table in tables:
        manifest = table.split("/")[0]
        path = os.path.join(ns.derived_root, table)
        if not os.path.isfile(path):
            missing.append([manifest, "-", "derived " + table, NOT_MEASURABLE % "file absent"])
            continue
        fields, rows = run.read_csv(path)
        for i, r in enumerate(rows, 1):
            for col in fields:
                derived.append([manifest, table, i, col, r.get(col)])

    run.write_csv("computed.csv", ["manifest", "attempt", "measure", "value", "unit", "source", "rule"], computed)
    run.write_csv("route-classes.csv", ["manifest", "attempt", "level", "route", "class", "http_status", "error_code",
                                        "requests"], routes)
    run.write_csv("cloudwatch-sums.csv", ["manifest", "attempt", "metric_id", "namespace", "metric", "stat",
                                          "dimensions", "datapoints", "sum", "first_period_utc", "last_period_utc",
                                          "note"], sums)
    run.write_csv("minute-throughput.csv", ["manifest", "attempt", "period_start_utc", "deleted", "per_second",
                                            "worker_running_min", "worker_running_max", "worker_samples"], minutes)
    run.write_csv("throughput-by-tasks.csv", ["manifest", "attempt", "worker_running", "periods", "deleted_total",
                                              "per_second_mean", "sample_note", "rule"], bytask)
    run.write_csv("derived-values.csv", ["manifest", "table", "row", "column", "value"], derived)
    run.write_csv("missing.csv", ["manifest", "attempt", "item", "reason"], missing)
    run.finish()
    say("RESULT", "computed %d measures; %d route rows; %d Sum series; %d missing items" % (
        len(computed), len(routes), len(sums), len(missing)))


# ---- factory (group 6) --------------------------------------------------------------------

FACTORY_INCLUDE = ["phase-%02d" % i for i in range(16)] + ["amendment-01", "amendment-02"]
AGENTS = [
    "factory-engineer", "build-engineer", "domain-engineer", "dataset-engineer", "hadoop-engineer",
    "entity-resolution-engineer", "spark-engineer", "storage-engineer", "rag-engineer", "service-engineer",
    "test-engineer", "code-reviewer", "infra-engineer", "deploy-engineer", "experiment-engineer",
    "documentation-engineer", "incident-responder", "dependency-updater",
]
SHORT = {
    "factory": "factory-engineer", "build": "build-engineer", "domain": "domain-engineer",
    "dataset": "dataset-engineer", "hadoop": "hadoop-engineer", "er": "entity-resolution-engineer",
    "er-eval": "entity-resolution-engineer", "entity-resolution": "entity-resolution-engineer",
    "spark": "spark-engineer", "storage": "storage-engineer", "rag": "rag-engineer",
    "service": "service-engineer", "services": "service-engineer", "test": "test-engineer",
    "tests": "test-engineer", "review": "code-reviewer", "reviewer": "code-reviewer",
    "infra": "infra-engineer", "deploy": "deploy-engineer", "experiment": "experiment-engineer",
    "docs": "documentation-engineer", "documentation": "documentation-engineer",
    "incident": "incident-responder", "dependency": "dependency-updater",
}
SEVERITY = {
    "critical": "critical", "blocker": "critical", "major": "major", "minor": "minor", "nit": "minor",
    "advisory": "advisory", "info": "advisory", "informational": "advisory",
}
RECHECK = {
    "resolved", "not", "partial", "partially", "open", "fixed", "closed", "accepted", "deferred",
    "unresolved", "verified", "done", "withdrawn", "superseded", "still", "n/a",
}
FINDING_ID_RE = re.compile(r"^[A-Za-z][A-Za-z0-9]{0,7}(?:-[A-Za-z0-9]{1,8}){0,2}$")
STATUS_RE = re.compile(r"^[\s>*`#-]*STATUS\s*:\s*\**\s*`?([A-Z][A-Z-]*)")
OWNER_RE = re.compile(r"^[\s>*`#-]*OWNER\s*:\s*\**\s*`?([a-z][a-z-]*)")
STEP_LIKE_RE = re.compile(r"^(?:[0-9]+[a-z]?|[A-Z][A-Za-z0-9]*)$")
SEPARATOR_RE = re.compile(r"^:?-{3,}:?$")
PHRASES = [
    ("manual-intervention", re.compile(r"manual intervention", re.I), re.compile(r"\bno manual intervention", re.I)),
    ("hook-block", re.compile(r"\b(?:guard|hook)\b[^.|]{0,40}\bblocked\b|\bblocked by the (?:guard|hook|stop hook)\b", re.I),
     re.compile(r"\b(?:no|never)\b[^.|]{0,20}\bblocked\b", re.I)),
    ("ownership-violation", re.compile(r"ownership violation|outside (?:its|their|the agent's) (?:write )?territory", re.I),
     re.compile(r"\bno ownership violation|\bnone\b", re.I)),
    ("human-rewrite", re.compile(r"\b(?:I|human) (?:rewrote|re-wrote)\b|\bsubstantial(?:ly)? (?:human )?rewr", re.I), None),
]
BLOCKED_NEGATION_RE = re.compile(
    r"\b(?:no|not|never|none|without)\b[^.|]{0,40}BLOCKED-ON|BLOCKED-ON[^.|]{0,40}\b(?:none|not needed)\b")
TYPE_ORDER = {"execution": 0, "blocked-terminal": 1, "finding": 2, "blocked-row": 3, "repair": 4,
              "manual-intervention": 5, "hook-block": 5, "ownership-violation": 5, "human-rewrite": 5}
PHRASE_TYPES = set(t for t, _, _ in PHRASES)
LIST_ROW_RE = re.compile(r"^\s*(?:[-*+]|[0-9]+[.)])\s+\S")
# Source precedence inside one phase (P16-B-02): the gate, then orchestrator summaries, then runbook
# logs; within a role, file order; within a file, line order.
ROLE_RANK = {"gate": 0, "summary": 1, "log": 2}
PHRASE_RULE = ("; list items and table body rows only (prose not counted); a row matching an unmatched row of the same"
               " phase type and agent in a higher-precedence file (gate then summaries) is duplicate_of and not counted")


def split_row(line):
    """Cells of a markdown table row ('\\|' kept inside a cell), or None if the line is not a row."""
    s = line.strip()
    if not s.startswith("|"):
        return None
    s = s.replace("\\|", "\x00")
    parts = s.split("|")
    cells = parts[1:-1] if s.endswith("|") and len(parts) > 2 else parts[1:]
    return [c.replace("\x00", "|").strip() for c in cells]


def clean(cell):
    return cell.strip().strip("`*").strip()


def is_separator(cells):
    return bool(cells) and all(SEPARATOR_RE.match(c.replace(" ", "")) for c in cells if c) and any(cells)


def first_word(cell):
    for tok in re.split(r"[^a-z/]+", clean(cell).lower()):
        if tok:
            return tok
    return ""


def find_agent(text):
    low = text.lower()
    best, pos = None, None
    for a in AGENTS:
        i = low.find(a)
        if i >= 0 and (pos is None or i < pos):
            best, pos = a, i
    return best


def agent_and_width(tokens):
    """(agent, number of leading tokens its name uses) for the longest known name; (None, 0) if none."""
    for n in range(len(tokens), 0, -1):
        cand = "-".join(tokens[:n])
        if cand in AGENTS:
            return cand, n
        if cand in SHORT:
            return SHORT[cand], n
    return None, 0


def agent_from_tokens(tokens):
    return agent_and_width(tokens)[0]


def own_list_row(lines, i):
    """True if lines[i] (0-based) is one of the file's own list rows: a list item ('- ', '* ', '+ ',
    '1. ', '1) ') or a markdown table body row (not a header row, not a separator row). Prose
    lines and headings are not list rows (P16-B-02)."""
    line = lines[i]
    if LIST_ROW_RE.match(line):
        return True
    cells = split_row(line)
    if not cells or is_separator(cells):
        return False
    nxt = split_row(lines[i + 1]) if i + 1 < len(lines) else None
    return not (nxt and is_separator(nxt))


def match_step(step, candidates):
    """First candidate event whose step equals `step` (both non-empty, case-insensitive); else the
    first whose step or `step` is empty (an empty step matches any step); else None."""
    s = (step or "").lower()
    for c in candidates:
        cs = (c["step"] or "").lower()
        if s and cs and s == cs:
            return c
    for c in candidates:
        if not s or not (c["step"] or ""):
            return c
    return None


def owner_line(lines):
    for line in lines:
        m = OWNER_RE.match(line)
        if m and m.group(1) in AGENTS:
            return m.group(1)
    return None


def role_of(name):
    if name == "human-gate.md":
        return "gate"
    if name.startswith("agent-report"):
        return "agent-report"
    if name.startswith("review-report") or re.match(r"^round[0-9]+-review-report", name):
        return "review"
    if name.startswith("repair-") or re.match(r"^round[0-9]+-repair-", name):
        return "repair"
    if name.startswith("orchestrator-summary") or re.match(r"^round[0-9]+-summary", name):
        return "summary"
    if name.startswith("runbook-log") or name.startswith("orchestrator-log"):
        return "log"
    return "other"


def status_class(text):
    low = clean(text).lower()
    if "blocked" in low:
        return "blocked"
    if "fail" in low or "stalled" in low:
        return "failed"
    if "not needed" in low or "skipped" in low or "not run" in low or "dropped" in low:
        return "not-run"
    if low.startswith(("done", "complete", "approve", "pass", "ok", "success")):
        return "done"
    return "other"


def cmd_factory(run, argv):
    p = Parser(prog="evaluate.py factory")
    add_common(p)
    p.add_argument("--root", required=True, help="docs/agent-runs")
    p.add_argument("--include", default=",".join(FACTORY_INCLUDE))
    p.add_argument("--seed", type=int, required=True)
    p.add_argument("--sample-size", type=int, default=20)
    ns = p.parse_args(argv)
    setup_suite(run, ns)
    require_dir(ns.root, "agent-runs root")
    include = [x for x in ns.include.split(",") if x]

    events, unclassified, phase_info = [], [], []
    for pi, phase in enumerate(include):
        pdir = os.path.join(ns.root, phase)
        info = {"phase": phase, "step_table": False, "log": False}
        phase_info.append(info)
        if not os.path.isdir(pdir):
            unclassified.append([phase, "", "", "phase folder absent", ""])
            say("WARN", "phase folder absent: %s" % phase)
            continue
        # sorted by path without ".md", so review-report.md comes before review-report-02.md
        files = sorted(listing(pdir, ".md").items(), key=lambda kv: kv[0][:-3])
        candidates = []
        for fi, (rel, full) in enumerate(files):
            fname = os.path.basename(rel)
            shown = phase + "/" + rel
            role = role_of(fname)
            text = run.read_text(full)
            lines = [ln.rstrip("\r") for ln in text.split("\n")]

            def ev(etype, line, detail="", agent="", step="", attempt="", fid="", text_="",
                   fi=fi, shown=shown, pi=pi, phase=phase, role=role):
                events.append({"pi": pi, "fi": fi, "line": line, "type": etype, "detail": detail, "agent": agent,
                               "step": step, "attempt": attempt, "fid": fid, "file": shown, "phase": phase,
                               "role": role, "text": text_.strip()[:200]})

            table_lines = set()
            if role == "review":
                found = recheck = 0
                for ln, line in enumerate(lines, 1):
                    cells = split_row(line)
                    if not cells or len(cells) < 2:
                        continue
                    c0 = clean(cells[0])
                    if not FINDING_ID_RE.match(c0) or not any(ch.isdigit() for ch in c0):
                        continue
                    w = first_word(cells[1])
                    if w in SEVERITY:
                        owner = ""
                        for c in cells[2:]:
                            if clean(c).lower() in AGENTS:
                                owner = clean(c).lower()
                                break
                        ev("finding", ln, SEVERITY[w], owner, fid=c0.upper(), text_=line)
                        found += 1
                    elif w in RECHECK:
                        recheck += 1
                    else:
                        unclassified.append([phase, shown, ln, "finding-like row with an unknown second cell", line.strip()[:200]])
                if found == 0 and recheck == 0 and re.search(r"finding", text, re.I):
                    unclassified.append([phase, shown, 1, "review report without a parseable finding table", ""])
            if role == "summary":
                i = 0
                while i < len(lines) - 1:
                    head = split_row(lines[i])
                    sep = split_row(lines[i + 1])
                    if head and sep and is_separator(sep):
                        hdr = [clean(c).lower() for c in head]
                        who_i = next((k for k, h in enumerate(hdr) if h in ("who", "agent", "owner")), None)
                        st_i = next((k for k, h in enumerate(hdr) if h.startswith("status") or h == "result"), None)
                        if hdr and hdr[0] == "step" and who_i is not None and st_i is not None:
                            info["step_table"] = True
                            ordinal = {}
                            j = i + 2
                            while j < len(lines):
                                cells = split_row(lines[j])
                                if cells is None:
                                    break
                                table_lines.add(j + 1)
                                step = clean(cells[0]) if cells else ""
                                who = cells[who_i] if who_i < len(cells) else ""
                                status = cells[st_i] if st_i < len(cells) else ""
                                agent = find_agent(who)
                                if agent is None:
                                    m = re.search(r"repair-[0-9]+-([a-z]+(?:-[a-z]+)*)", who.lower())
                                    if m:
                                        agent = agent_from_tokens(m.group(1).split("-"))
                                low = clean(who).lower()
                                if agent is None:
                                    if low in ("", "-", "–", "—") or any(k in low for k in ("orchestrator", "human", "triage")):
                                        j += 1
                                        continue
                                    unclassified.append([phase, shown, j + 1, "step-table row without a known agent",
                                                         lines[j].strip()[:200]])
                                    j += 1
                                    continue
                                ordinal[(step, agent)] = ordinal.get((step, agent), 0) + 1
                                sc = status_class(status)
                                ev("execution", j + 1, sc, agent, step, ordinal[(step, agent)], text_=lines[j])
                                if sc == "blocked":
                                    ev("blocked-row", j + 1, "step table", agent, step, text_=lines[j])
                                j += 1
                            i = j
                            continue
                    i += 1
            if role == "log":
                info["log"] = True
            if role in ("summary", "log"):
                for ln, line in enumerate(lines, 1):
                    if ln in table_lines or "BLOCKED-ON" not in line or line.lstrip().startswith("#"):
                        continue
                    if BLOCKED_NEGATION_RE.search(line):
                        continue
                    cells = split_row(line)
                    ev("blocked-row", ln, "log line" if role == "log" else "summary line", find_agent(line) or "none",
                       clean(cells[0]) if cells else "", text_=line)
            if role in ("gate", "summary"):
                for ln, line in enumerate(lines, 1):
                    if not own_list_row(lines, ln - 1):
                        continue
                    for etype, pat, neg in PHRASES:
                        if pat.search(line) and not (neg and neg.search(line)):
                            ev(etype, ln, "phrase", find_agent(line) or "", text_=line)
                            break
            if role == "agent-report":
                stem = fname[:-3]
                tokens = stem[len("agent-report"):].strip("-").split("-") if stem != "agent-report" else []
                agent, width = agent_and_width(tokens)
                # the report's step: file-name tokens after the agent name (agent-report-storage-D -> D);
                # empty when the name carries none or the agent comes from the OWNER line (matches any step)
                rstep = "-".join(t for t in tokens[width:] if t) if agent is not None else ""
                if agent is None:
                    agent = owner_line(lines)
                status, sline = "", 1
                for ln, line in enumerate(lines, 1):
                    m = STATUS_RE.match(line)
                    if m:
                        status, sline = m.group(1), ln
                if agent is None:
                    unclassified.append([phase, shown, 1, "agent report without a known agent", ""])
                if status.startswith("BLOCKED"):
                    ev("blocked-terminal", sline, status, agent or "unknown", rstep, text_=lines[sline - 1])
                cls = {"COMPLETE": "done", "FAILED": "failed"}.get(status, "blocked" if status.startswith("BLOCKED") else "other")
                candidates.append((fi, sline, agent or "unknown", stem, cls, shown, lines[sline - 1] if lines else ""))
            if role == "repair":
                stem = fname[:-3]
                rest = re.sub(r"^round[0-9]+-repair-?", "", stem) if stem.startswith("round") else stem[len("repair"):]
                tokens = [t for t in rest.split("-") if t]
                if tokens and STEP_LIKE_RE.match(tokens[0]):
                    tokens = tokens[1:]
                agent = agent_from_tokens(tokens) or owner_line(lines)
                if agent is None:
                    unclassified.append([phase, shown, 1, "repair file without a known agent", ""])
                ev("repair", 1, "repair file", agent or "unknown", text_=lines[0] if lines else "")
        if not info["step_table"]:
            for (fi, sline, agent, stem, cls, shown, txt) in candidates:
                events.append({"pi": pi, "fi": fi, "line": sline, "type": "execution", "detail": cls, "agent": agent,
                               "step": stem, "attempt": 1, "fid": "", "file": shown, "phase": phase,
                               "role": "agent-report", "text": txt.strip()[:200]})
        info["source"] = "step table" if info["step_table"] else ("agent-report files (no step table)" if candidates else "none")

    events.sort(key=lambda e: (e["pi"], e["fi"], e["line"], TYPE_ORDER[e["type"]]))
    for i, e in enumerate(events, 1):
        e["id"] = "E%05d" % i
        e["counted"] = True
        e["dup"] = ""
    seen_f, seen_x, terminals = {}, {}, {}
    for e in events:
        if e["type"] == "finding":
            key = (e["phase"], e["fid"])
            if key in seen_f:
                e["counted"], e["dup"] = False, seen_f[key]
            else:
                seen_f[key] = e["id"]
        elif e["type"] == "execution":
            key = (e["phase"], e["step"], e["agent"], e["attempt"])
            if key in seen_x:
                e["counted"], e["dup"] = False, seen_x[key]
            else:
                seen_x[key] = e["id"]
        elif e["type"] == "blocked-terminal":
            terminals.setdefault((e["phase"], e["agent"]), []).append(e)

    def precedence(e):
        return (e["pi"], ROLE_RANK.get(e["role"], len(ROLE_RANK)), e["fi"], e["line"], e["id"])

    # BLOCKED-ON rows (P16-B-02): deduplicated by phase, step and agent (an empty step matches any
    # step), against terminal reports first, then against the counted rows before them in source
    # precedence (summaries, then runbook logs), many to one. Rows naming no known agent stay counted.
    anchors = {}
    for e in sorted((x for x in events if x["type"] == "blocked-row"), key=precedence):
        if e["agent"] in ("none", "unknown", ""):
            continue
        key = (e["phase"], e["agent"])
        hit = match_step(e["step"], terminals.get(key, []))
        if hit is None:
            hit = match_step(e["step"], anchors.get(key, []))
        if hit is not None:
            e["counted"], e["dup"] = False, hit["id"]
        else:
            anchors.setdefault(key, []).append(e)

    # Phrase events (P16-B-02): only own list rows were collected; one source precedence per phase
    # (the gate, then summaries in file order). A row is a duplicate of the first still unmatched
    # counted row of the same phase, type and agent in a higher-precedence file, one to one; rows of
    # one file never match each other.
    pool, used = {}, set()
    for e in sorted((x for x in events if x["type"] in PHRASE_TYPES), key=precedence):
        key = (e["phase"], e["type"], e["agent"])
        hit = None
        for c in pool.get(key, []):
            if c["id"] not in used and c["fi"] != e["fi"]:
                hit = c
                break
        if hit is not None:
            used.add(hit["id"])
            e["counted"], e["dup"] = False, hit["id"]
        else:
            pool.setdefault(key, []).append(e)

    counted = [e for e in events if e["counted"]]
    repair_keys = set((e["phase"], e["agent"]) for e in counted if e["type"] == "repair")
    major_keys = set((e["phase"], e["agent"]) for e in counted
                     if e["type"] == "finding" and e["detail"] in ("critical", "major") and e["agent"])
    for e in counted:
        if e["type"] == "execution":
            e["first_pass"] = e["detail"] == "done" and (e["phase"], e["agent"]) not in repair_keys \
                and (e["phase"], e["agent"]) not in major_keys

    def count(phase=None, agent=None, etype=None, detail=None, pred=None):
        c = 0
        for e in counted:
            if phase is not None and e["phase"] != phase:
                continue
            if agent is not None and e["agent"] != agent:
                continue
            if etype is not None and e["type"] != etype:
                continue
            if detail is not None and e["detail"] != detail:
                continue
            if pred is not None and not pred(e):
                continue
            c += 1
        return c

    phase_rows = []
    measurable_total, measurable_phases = 0, 0
    for info in phase_info:
        ph = info["phase"]
        bt = count(ph, etype="blocked-terminal")
        br_all = len([e for e in events if e["phase"] == ph and e["type"] == "blocked-row"])
        br = count(ph, etype="blocked-row")
        if info["step_table"] or info["log"]:
            total = bt + br
            measurable_total += total
            measurable_phases += 1
        else:
            total = "not measurable (no summary step table or runbook log)"
        phase_rows.append([
            ph, info.get("source", "none"), count(ph, etype="execution"), count(ph, etype="execution", detail="done"),
            count(ph, etype="execution", pred=lambda e: e.get("first_pass")),
            count(ph, etype="finding"), count(ph, etype="finding", detail="critical"),
            count(ph, etype="finding", detail="major"), count(ph, etype="finding", detail="minor"),
            count(ph, etype="finding", detail="advisory"), count(ph, etype="repair"), bt, br_all, br, total,
            count(ph, etype="manual-intervention"), count(ph, etype="hook-block"),
            count(ph, etype="ownership-violation"), count(ph, etype="human-rewrite"),
            len([u for u in unclassified if u[0] == ph]),
        ])

    agents = sorted(set(e["agent"] for e in counted if e["agent"] and e["agent"] != "none"))
    agent_rows = []
    for a in agents:
        done = count(agent=a, etype="execution", detail="done")
        acc = count(agent=a, etype="execution", pred=lambda e: e.get("first_pass"))
        agent_rows.append([a, count(agent=a, etype="execution"), done, acc, (acc / float(done)) if done else None,
                           count(agent=a, etype="repair"), count(agent=a, etype="finding", detail="critical"),
                           count(agent=a, etype="finding", detail="major"), count(agent=a, etype="finding", detail="minor"),
                           count(agent=a, etype="finding", detail="advisory"), count(agent=a, etype="blocked-terminal")])

    nph = len(include)
    done_all = count(etype="execution", detail="done")
    acc_all = count(etype="execution", pred=lambda e: e.get("first_pass"))
    rep_all = count(etype="repair")
    totals = [
        ["phases_in_population", None, None, nph, "folders named by --include"],
        ["executions", None, None, count(etype="execution"),
         "step-table rows naming a known agent (deduplicated by phase step agent attempt); agent-report files where a phase has no step table"],
        ["first_pass_acceptance", acc_all, done_all, (acc_all / float(done_all)) if done_all else None,
         "done executions with no repair file and no critical or major finding for the same agent in the same phase"],
        ["findings_total", None, None, count(etype="finding"), "finding rows of review reports; first mention of an ID per phase"],
        ["findings_critical", None, None, count(etype="finding", detail="critical"), "severity critical or blocker"],
        ["findings_major", None, None, count(etype="finding", detail="major"), "severity major"],
        ["findings_minor", None, None, count(etype="finding", detail="minor"), "severity minor or nit"],
        ["findings_advisory", None, None, count(etype="finding", detail="advisory"), "severity advisory or info"],
        ["repairs_total", None, None, rep_all, "one repair-*.md file = one repair invocation"],
        ["repairs_per_phase", rep_all, nph, (rep_all / float(nph)) if nph else None, "repair files over phases in the population"],
        ["blocked_on_terminal_reports", None, None, count(etype="blocked-terminal"), "final STATUS line of an agent report is BLOCKED-ON"],
        ["blocked_on_rows_not_in_terminal", None, None, count(etype="blocked-row"),
         "BLOCKED-ON rows of summaries and logs deduplicated by phase step and agent (empty step matches any): rows matching a terminal report or an earlier row (summaries then runbook logs) are duplicate_of and not counted; rows naming no agent counted"],
        ["blocked_on_total_measurable_phases", measurable_total, measurable_phases, measurable_total,
         "terminal reports plus unmatched rows; only phases with a summary step table or a runbook log"],
        ["manual_interventions", None, None, count(etype="manual-intervention"), "gate or summary list rows naming a manual intervention" + PHRASE_RULE],
        ["hook_blocks", None, None, count(etype="hook-block"), "gate or summary list rows saying a guard or hook blocked something" + PHRASE_RULE],
        ["ownership_violations_caught", None, None, count(etype="ownership-violation"), "gate or summary list rows naming an ownership violation" + PHRASE_RULE],
        ["human_rewrites", None, None, count(etype="human-rewrite"), "gate or summary list rows saying the human rewrote an artifact" + PHRASE_RULE],
        ["unclassified_rows", None, None, len(unclassified), "rows and files the rules cannot place (never guessed)"],
    ]

    size = max(ns.sample_size, 0)
    def hkey(e):
        return sha256_text("%d:%s" % (ns.seed, e["id"]))
    if len(counted) <= size:
        sample = list(counted)
    else:
        chosen, ids = [], set()
        for t in sorted(set(e["type"] for e in counted)):
            pick = min((e for e in counted if e["type"] == t), key=hkey)
            chosen.append(pick)
            ids.add(pick["id"])
        for e in sorted((e for e in counted if e["id"] not in ids), key=hkey):
            if len(chosen) >= size:
                break
            chosen.append(e)
            ids.add(e["id"])
        sample = chosen[:size]
    sample.sort(key=lambda e: e["id"])
    strata = []
    for t in sorted(set(e["type"] for e in counted)):
        strata.append([t, len([e for e in counted if e["type"] == t]), len([e for e in sample if e["type"] == t])])

    digest, nfiles = tree_digest([os.path.join(ns.root, ph) for ph in include if os.path.isdir(os.path.join(ns.root, ph))], ".md")
    run.meta["populationDigest"] = digest
    run.meta["populationFiles"] = nfiles
    say("RESULT", "population: %d .md files; digest %s" % (nfiles, digest))

    run.write_csv("events.csv", ["event_id", "phase", "type", "detail", "agent", "step", "attempt", "finding_id",
                                 "counted", "duplicate_of", "file", "line", "text"],
                  [[e["id"], e["phase"], e["type"], e["detail"], e["agent"], e["step"], e["attempt"], e["fid"],
                    e["counted"], e["dup"], e["file"], e["line"], e["text"]] for e in events])
    run.write_csv("counts-by-phase.csv", ["phase", "execution_source", "executions", "executions_done",
                                          "first_pass_accepted", "findings_total", "findings_critical", "findings_major",
                                          "findings_minor", "findings_advisory", "repairs", "blocked_on_terminal",
                                          "blocked_on_rows", "blocked_on_rows_not_in_terminal", "blocked_on_total",
                                          "manual_interventions", "hook_blocks", "ownership_violations",
                                          "human_rewrites", "unclassified"], phase_rows)
    run.write_csv("counts-by-agent.csv", ["agent", "executions", "executions_done", "first_pass_accepted",
                                          "first_pass_rate", "repairs", "findings_owned_critical",
                                          "findings_owned_major", "findings_owned_minor", "findings_owned_advisory",
                                          "blocked_on_terminal"], agent_rows)
    run.write_csv("totals.csv", ["metric", "numerator", "denominator", "value", "inclusion_rule"], totals)
    run.write_csv("unclassified.csv", ["phase", "file", "line", "reason", "text"], unclassified)
    run.write_csv("audit-sample.csv", ["sample_no", "event_id", "type", "detail", "phase", "agent", "file", "line",
                                       "text", "event_is_real", "correctly_classified", "auditor_note"],
                  [[i, e["id"], e["type"], e["detail"], e["phase"], e["agent"], e["file"], e["line"], e["text"],
                    "", "", ""] for i, e in enumerate(sample, 1)])
    run.write_csv("audit-strata.csv", ["type", "events_counted", "sampled"], strata)
    run.finish()
    say("RESULT", "events %d (counted %d); unclassified %d; audit sample %d" % (len(events), len(counted), len(unclassified), len(sample)))


# ---- reproduce (group 7) ------------------------------------------------------------------

APPROVAL_RE = re.compile(r"^APPROVED-PLAN-SHA256: ([0-9a-f]{64})$")


def cmd_reproduce(run, argv):
    p = Parser(prog="evaluate.py reproduce")
    add_common(p)
    p.add_argument("--release-records", action="store_true")
    p.add_argument("--releases-dir", help="releases/course (with --release-records)")
    p.add_argument("--approvals-root", help="docs/agent-runs (with --release-records)")
    p.add_argument("--intended", help="CSV path,reason of intended differences")
    p.add_argument("paths", nargs="*", help="EXPECTED_DIR REGENERATED_DIR")
    ns = p.parse_args(argv)
    setup_suite(run, ns)
    if ns.release_records:
        if ns.paths or ns.intended:
            raise ToolExit(EXIT_USAGE, "usage: --release-records takes no folders and no --intended")
        if not ns.releases_dir or not ns.approvals_root:
            raise ToolExit(EXIT_USAGE, "usage: --release-records needs --releases-dir and --approvals-root")
        release_records(run, ns)
        return
    if len(ns.paths) != 2:
        raise ToolExit(EXIT_USAGE, "usage: reproduce EXPECTED_DIR REGENERATED_DIR [--intended CSV]")
    exp_dir, reg_dir = ns.paths
    require_dir(exp_dir, "expected folder")
    require_dir(reg_dir, "regenerated folder; a missing regenerated folder is a failure and never a pass")
    intended = {}
    if ns.intended:
        require_file(ns.intended, "intended-differences list")
        fields, rows = run.read_csv(ns.intended)
        if "path" not in fields or "reason" not in fields:
            raise ToolExit(EXIT_FAILURE, "malformed input (intended list needs the columns path and reason): %s" % repo_rel(ns.intended))
        for i, r in enumerate(rows, 1):
            path, reason = (r.get("path") or "").strip(), (r.get("reason") or "").strip()
            if not path or not reason:
                raise ToolExit(EXIT_FAILURE, "malformed input (intended list row %d lacks path or reason): %s" % (i, repo_rel(ns.intended)))
            intended[path] = reason
    exp = listing(exp_dir)
    reg = listing(reg_dir)
    shas = {}
    for side, files in (("e", exp), ("r", reg)):
        for rel in sorted(files):
            shas[(side, rel)] = sha256_bytes(run.read_bytes(files[rel]))
    rows = []
    c = dict(equal=0, different=0, missing=0, extra=0, intended=0, unexplained=0, not_observed=0)
    for rel in sorted(set(exp) | set(reg) | set(intended)):
        es, rs = shas.get(("e", rel)), shas.get(("r", rel))
        if es is not None and rs is not None:
            status = "equal" if es == rs else "different"
        elif es is not None:
            status = "missing"
        elif rs is not None:
            status = "extra"
        else:
            status = "absent"
        if status in ("equal", "absent"):
            if status == "equal":
                c["equal"] += 1
            marking = "intended-not-observed" if rel in intended else ""
            if marking:
                c["not_observed"] += 1
        else:
            c[status] += 1
            marking = "intended" if rel in intended else "unexplained"
            c[marking] += 1
        rows.append([rel, status, es, rs, marking, intended.get(rel, "")])
    if not reg:
        verdict = "failed: regenerated folder is empty"
    elif c["unexplained"] == 0 and c["intended"] == 0:
        verdict = "reproduced exactly"
    elif c["unexplained"] == 0:
        verdict = "reproduced except intended differences"
    else:
        verdict = "not reproduced: %d unexplained differences" % c["unexplained"]
    if c["not_observed"]:
        say("WARN", "%d intended differences were not observed" % c["not_observed"])
    summary = [["expected_files", len(exp)], ["regenerated_files", len(reg)], ["equal", c["equal"]],
               ["different", c["different"]], ["missing", c["missing"]], ["extra", c["extra"]],
               ["intended", c["intended"]], ["unexplained", c["unexplained"]],
               ["intended_not_observed", c["not_observed"]], ["verdict", verdict]]
    run.write_csv("comparison.csv", ["path", "status", "expected_sha256", "regenerated_sha256", "marking", "reason"], rows)
    run.write_csv("summary.csv", ["metric", "value"], summary)
    run.finish()
    say("RESULT", "reproduce: %s" % verdict)


def release_records(run, ns):
    require_dir(ns.releases_dir, "release records folder")
    require_dir(ns.approvals_root, "approvals root")
    approvals = []
    for d in sorted(os.listdir(ns.approvals_root)):
        dp = os.path.join(ns.approvals_root, d)
        if d.startswith(".") or not os.path.isdir(dp):
            continue
        for fname in ("approvals.md", "human-gate.md"):
            fp = os.path.join(dp, fname)
            if not os.path.isfile(fp):
                continue
            for ln, line in enumerate(run.read_text(fp).split("\n"), 1):
                m = APPROVAL_RE.match(line.rstrip("\r"))
                if m:
                    approvals.append((m.group(1), "%s/%s:%d" % (d, fname, ln)))
    where = {}
    for s, w in approvals:
        where.setdefault(s, []).append(w)
    used = {}
    rows = []
    cnt = dict(unreadable=0, with_sha=0, linked=0, not_linked=0, evidence=0)
    for f in sorted(x for x in os.listdir(ns.releases_dir) if x.endswith(".json") and not x.startswith(".")):
        raw = run.read_bytes(os.path.join(ns.releases_dir, f))
        try:
            data = json.loads(raw.decode("utf-8"))
            if not isinstance(data, dict):
                raise ValueError("not an object")
        except (ValueError, UnicodeDecodeError):
            cnt["unreadable"] += 1
            rows.append([f, "", "", "", "", "", "unreadable record", "", "", "", "",
                         "deployment not independently verified (record unreadable)", "record consistency only"])
            continue
        plan = data.get("plan_sha256")
        if not isinstance(plan, str) or not plan:
            link, at = "no plan checksum in record", ""
        else:
            cnt["with_sha"] += 1
            if plan in where:
                link, at = "signed approval line found", ";".join(where[plan])
                cnt["linked"] += 1
                used.setdefault(plan, []).append(f)
            else:
                link, at = "no signed approval line with this checksum", ""
                cnt["not_linked"] += 1
        services = data.get("services") if isinstance(data.get("services"), dict) else None
        digest = ""
        if services is not None:
            k = dict(verified=0, na=0, none=0, other=0)
            for name in sorted(services):
                dc = str((services[name] or {}).get("digest_check", "")) if isinstance(services[name], dict) else ""
                if dc.startswith("verified"):
                    k["verified"] += 1
                elif dc.startswith("not-applicable"):
                    k["na"] += 1
                elif dc.startswith("no running task"):
                    k["none"] += 1
                else:
                    k["other"] += 1
            digest = "verified %d; not-applicable %d; no running task %d; other %d" % (k["verified"], k["na"], k["none"], k["other"])
        applied = data.get("applied_utc") if isinstance(data.get("applied_utc"), str) else ""
        verified = data.get("verified") if isinstance(data.get("verified"), bool) else None
        if verified is True and applied:
            evidence = "record reports applied_utc and verified=true (written by the deploy script; not independently verified)"
            cnt["evidence"] += 1
        else:
            evidence = "deployment not independently verified (record lacks verified=true or applied_utc)"
        rows.append([f, data.get("attempt_id", ""), data.get("environment", ""), data.get("commit", ""),
                     data.get("plan_id", ""), plan if isinstance(plan, str) else "", link, at, applied, verified, digest,
                     evidence, "record consistency only"])
    arows = [[s, w, ";".join(used.get(s, [])) or "none"] for s, w in approvals]
    summary = [["records", len(rows)], ["records_unreadable", cnt["unreadable"]],
               ["records_with_plan_checksum", cnt["with_sha"]],
               ["records_linked_to_signed_approval", cnt["linked"]], ["records_not_linked", cnt["not_linked"]],
               ["records_with_execution_fields", cnt["evidence"]], ["approval_lines", len(approvals)],
               ["approval_lines_unused", len([a for a in arows if a[2] == "none"])],
               ["wording", "record consistency only; no deployment can be re-run or independently verified"]]
    run.write_csv("release-records.csv", ["record", "attempt_id", "environment", "commit", "plan_id", "plan_sha256",
                                          "plan_approval", "approval_where", "applied_utc", "verified", "digest_checks",
                                          "execution_evidence", "wording"], rows)
    run.write_csv("approvals.csv", ["plan_sha256", "where", "used_by_records"], arows)
    run.write_csv("summary.csv", ["metric", "value"], summary)
    run.finish()
    say("RESULT", "release records: %d; linked to a signed approval line: %d" % (len(rows), cnt["linked"]))


# ---- ratings sheet (group 4, kind c) --------------------------------------------------------


def get_path(obj, dotted):
    if dotted == "":
        return obj
    for part in dotted.split("."):
        if isinstance(obj, dict) and part in obj:
            obj = obj[part]
        elif isinstance(obj, list) and part.isdigit() and int(part) < len(obj):
            obj = obj[int(part)]
        else:
            return None
    return obj


def cmd_ratings_sheet(run, argv):
    p = Parser(prog="evaluate.py ratings-sheet")
    add_common(p)
    p.add_argument("--answer-file", action="append", required=True,
                   help="PATH or PATH#items=DOTTED;id=DOTTED;question=DOTTED;answer=DOTTED (repeatable)")
    p.add_argument("--rubric", required=True, help="experiments/evaluation/rubric-v1.md")
    p.add_argument("--seed", type=int, required=True)
    p.add_argument("--max", type=int, default=20)
    ns = p.parse_args(argv)
    setup_suite(run, ns)
    specs = []
    for spec in ns.answer_file:
        path, opts = (spec.split("#", 1) + [""])[:2] if "#" in spec else (spec, "")
        o = {"items": None, "id": "id", "question": "question", "answer": "answerText"}
        for kv in [x for x in opts.split(";") if x]:
            if "=" not in kv or kv.split("=", 1)[0] not in o:
                raise ToolExit(EXIT_USAGE, "usage: bad --answer-file option %r (keys: items id question answer)" % kv)
            k, v = kv.split("=", 1)
            o[k] = v
        specs.append((path, o))
    for path, _ in specs:
        require_file(path, "answer file")
    require_file(ns.rubric, "rubric")
    rubric_sha = sha256_bytes(run.read_bytes(ns.rubric))
    found, excluded = [], []
    for path, o in specs:
        data = run.read_json(path)
        src = os.path.basename(path)
        src_sha = sha256_file(path)
        if o["items"] is not None:
            items = get_path(data, o["items"])
            if not isinstance(items, list):
                raise ToolExit(EXIT_FAILURE, "malformed input (items path %s is not a list): %s" % (o["items"], repo_rel(path)))
        else:
            items = data if isinstance(data, list) else [data]
        for i, item in enumerate(items):
            v = get_path(item, o["id"])
            iid = str(v) if isinstance(v, (str, int)) and not isinstance(v, bool) and str(v) != "" else "item-%d" % (i + 1)
            q = get_path(item, o["question"])
            a = get_path(item, o["answer"])
            if not isinstance(a, str) or not a.strip():
                excluded.append([src, iid, "no answer text at %s" % o["answer"]])
                continue
            if not isinstance(q, str) or not q.strip():
                excluded.append([src, iid, "no question text at %s" % o["question"]])
                continue
            found.append({"src": src, "src_sha": src_sha, "id": iid, "q": q, "a": a,
                          "key": sha256_text("%d:%s:%s" % (ns.seed, src, iid))})
    found.sort(key=lambda x: x["key"])
    cap = max(ns.max, 0)
    included = found[:cap]
    for x in found[cap:]:
        excluded.append([x["src"], x["id"], "over the cap of %d answers" % cap])
    sheet, keyrows = [], []
    for i, x in enumerate(included, 1):
        k = "R%02d" % i
        sheet.append([k, x["q"], x["a"], "", "", "", "", ""])
        keyrows.append([k, x["src"], x["src_sha"], x["id"], sha256_text(x["a"]), len(x["a"])])
    n_ans = len([e for e in excluded if e[2].startswith("no answer")])
    n_q = len([e for e in excluded if e[2].startswith("no question")])
    summary = [["answer_files", len(specs)], ["answers_found", len(found)], ["answers_included", len(included)],
               ["excluded_no_answer_text", n_ans], ["excluded_no_question_text", n_q],
               ["excluded_over_cap", len(found) - len(included)], ["cap", cap], ["seed", ns.seed],
               ["rubric_sha256", rubric_sha]]
    run.write_csv("sheet.csv", ["answer_key", "question", "answer_text", "usefulness_1_to_5", "clarity_1_to_5",
                                "not_rateable", "dblp_names_exist", "rater_note"], sheet)
    run.write_csv("key.csv", ["answer_key", "source_file", "source_sha256", "item_id", "answer_sha256", "answer_chars"], keyrows)
    run.write_csv("excluded.csv", ["source_file", "item_id", "reason"], excluded)
    run.write_csv("summary.csv", ["metric", "value"], summary)
    run.finish()
    say("RESULT", "rating sheet: %d answers (of %d found); open sheet.csv only, never key.csv, while rating" % (len(included), len(found)))


# ---- answers (group 4, kind a) --------------------------------------------------------------

CLAIM_STATUSES = ["verified", "partially_supported", "unsupported", "conflicting"]
CLAIM_METRICS = [("claim_support_rate", "verified"), ("contradiction_rate", "conflicting"),
                 ("partially_supported_share", "partially_supported"), ("unsupported_share", "unsupported")]
# Claim types of decision 13.9, each read from the evidence types a claim cites (a proxy); no
# evidence type identifies an identifier claim, so that type is never measurable here.
CLAIM_TYPES = [
    ("coauthor-edge", ["coauthor_edge"]),
    ("graph-path", ["graph_path"]),
    ("supporting-publication", ["work", "authorship"]),
    ("identifier", []),
    ("number", ["metric"]),
]
LABEL_SOURCES = ["run-report", "explain-csv", "audit", "demo"]
LABEL_FIELDS = ["code_version", "prompt_set", "graph_run"]
NOT_AVAILABLE = "not available (%s)"
CLAIM_RULE = "all claims of the source's answers, every verification status; claims of one answer are not independent"
# P16-C-02 (human decision): intervals only over independent answers. A row over dependent units
# keeps k, n and the proportion, leaves ci_low and ci_high empty and carries a "descriptive; ..." note.
NO_INTERVAL = "no interval (descriptive)"
EXPLAIN_DESCRIPTIVE = "descriptive; explain rows are neither independent nor a random sample"
AUDIT_COLLAPSE_RULE = ("one answer per question: attempt records grouped by questionHash (by queryRunId when a record "
                       "has no questionHash); each answer keeps its last attempt's outcome (largest numeric attempt; a "
                       "record without a numeric attempt counts as earlier; file order breaks ties)")


def join_notes(*parts):
    return "; ".join(p for p in parts if p)


def independence_reason(questions, what):
    """'' when the answers can count as independent (at least 2 answers, every one with a question
    reference, no question repeated), else the 'descriptive; ...' note saying why not."""
    if len(questions) < 2:
        return "descriptive; %d %s, an interval needs at least 2 independent answers" % (len(questions), what)
    if not all(questions):
        return "descriptive; an answer lacks its question reference, so distinct questions cannot be shown"
    if len(set(questions)) < len(questions):
        return "descriptive; answers repeat a question, so they are not independent"
    return ""


def nonneg_int(v):
    return isinstance(v, int) and not isinstance(v, bool) and v >= 0


def bootstrap_ratios(series_by_key, n, seed, resamples):
    """Paired percentile bootstrap of ratios of sums over n units (answers).

    series_by_key: key -> list of n (numerator, denominator) pairs, or None for a unit without a
    value. Every resample draws n unit indices with replacement from random.Random(seed); the same
    indices serve every key; a resample whose picked denominators sum to 0 is skipped for that key.
    Returns key -> (low, high), the nearest-rank 2.5 % and 97.5 % quantiles, or (None, None).
    """
    rng = random.Random(seed)
    draws = dict((k, []) for k in series_by_key)
    for _ in range(resamples):
        idx = [rng.randrange(n) for _ in range(n)]
        for k, vals in series_by_key.items():
            s_num, s_den = 0, 0
            for i in idx:
                if vals[i] is not None:
                    s_num += vals[i][0]
                    s_den += vals[i][1]
            if s_den > 0:
                draws[k].append(s_num / float(s_den))
    out = {}
    for k, d in draws.items():
        if not d:
            out[k] = (None, None)
            continue
        d.sort()
        out[k] = (d[nearest_rank_index(0.025, len(d))], d[nearest_rank_index(0.975, len(d))])
    return out


def claim_rows(src, breakdown, totals, units, has_conflicting, seed, resamples, note="", descriptive=""):
    """Rate rows for one set of claims. totals: status -> pooled count. units: None when only
    aggregated counts exist (no interval), else one status -> count map per answer (None for an
    answer without claims in this set), resampled by bootstrap_ratios. descriptive: a non-empty
    'descriptive; ...' note when the answers are not independent (P16-C-02): no bootstrap, the note
    is added to the note of every row of this set. P16-L-08: the bootstrap also needs at least 2
    answers with claims in this set (the units that carry the rate's denominator); with exactly 1
    the set is descriptive with its own note; with 0 every row is not measurable (no claims) and
    the note is left as it is."""
    total = sum(totals.values())
    metrics = list(CLAIM_METRICS) + [("other_status_share:" + s, s) for s in sorted(totals) if s not in CLAIM_STATUSES]
    ci = {}
    method = "no dependence-aware interval (aggregated counts only; no per-answer values)"
    with_claims = len([u for u in units if u and sum(u.values()) > 0]) if units is not None else 0
    if units is not None and not descriptive and with_claims == 1:
        descriptive = "descriptive; 1 answer(s) with claims in this set, an interval needs at least 2 independent answers"
    if units is not None and descriptive:
        method = NO_INTERVAL
        note = join_notes(note, descriptive)
    elif units is not None and with_claims < 2:
        method = NO_INTERVAL
    elif units is not None:
        series = {}
        for _, status in metrics:
            series[status] = [((u.get(status, 0), sum(u.values())) if u and sum(u.values()) > 0 else None) for u in units]
        ci = bootstrap_ratios(series, len(units), seed, resamples)
        method = "percentile bootstrap over answers (ratio of sums); seed %d; %d resamples; %d answers with claims" % (
            seed, resamples, with_claims)
    rows = []
    for metric, status in metrics:
        if status == "conflicting" and not has_conflicting:
            rows.append([src, metric, breakdown, "a", None, None, NOT_AVAILABLE % "the file has no conflicting status",
                         None, None, "", CLAIM_RULE, note])
            continue
        k = totals.get(status, 0)
        if total == 0:
            rows.append([src, metric, breakdown, "a", k, total, NOT_MEASURABLE % "no claims", None, None, "", CLAIM_RULE, note])
            continue
        lo, hi = ci.get(status, (None, None))
        rows.append([src, metric, breakdown, "a", k, total, k / float(total), lo, hi, method, CLAIM_RULE, note])
    return rows


def type_rows_not_measurable(src, reason):
    rows = []
    for name, _ in CLAIM_TYPES:
        rows.append([src, "claim_support_rate", "claim type: %s" % name, "a", None, None, NOT_MEASURABLE % reason,
                     None, None, "", CLAIM_RULE, ""])
    return rows


def wilson_row(src, metric, breakdown, k, n, rule, note="", descriptive=""):
    """Answer-level rate. Wilson score 95 % only over independent answers; with a non-empty
    'descriptive; ...' note (P16-C-02) the row keeps k, n and the proportion, ci_low and ci_high
    stay empty and ci_method says no interval. A denominator of 1 is always descriptive: like
    independence_reason, an interval needs at least 2 independent answers."""
    if n == 1 and not descriptive:
        descriptive = "descriptive; 1 answer(s), an interval needs at least 2 independent answers"
    if n <= 0:
        return [src, metric, breakdown, "a", k, n, NOT_MEASURABLE % "empty denominator", None, None, "", rule,
                join_notes(note, descriptive)]
    if descriptive:
        return [src, metric, breakdown, "a", k, n, k / float(n), None, None, NO_INTERVAL, rule, join_notes(note, descriptive)]
    lo, hi = wilson(k, n)
    return [src, metric, breakdown, "a", k, n, k / float(n), lo, hi, "Wilson score 95% (answer-level)", rule, note]


def parse_labels(items):
    labels = {}
    for item in items or []:
        key = item.split("=", 1)[0] if "=" in item else ""
        if "." not in key:
            raise ToolExit(EXIT_USAGE, "usage: --label SOURCE.FIELD=TEXT (sources: %s; fields: %s)"
                           % (" ".join(LABEL_SOURCES), " ".join(LABEL_FIELDS)))
        src, field = key.split(".", 1)
        text = item.split("=", 1)[1].strip()
        if src not in LABEL_SOURCES or field not in LABEL_FIELDS or not text:
            raise ToolExit(EXIT_USAGE, "usage: --label SOURCE.FIELD=TEXT (sources: %s; fields: %s)"
                           % (" ".join(LABEL_SOURCES), " ".join(LABEL_FIELDS)))
        labels[(src, field)] = text
    return labels


def label_cells(src, from_file, labels):
    """[value, origin] for each LABEL_FIELDS entry: the file's value, else the --label argument, else not available."""
    cells = []
    for field in LABEL_FIELDS:
        v = from_file.get(field)
        if v:
            cells += [v, "file"]
        elif (src, field) in labels:
            cells += [labels[(src, field)], "argument"]
        else:
            cells += [NOT_AVAILABLE % "not in the file and no --label given", "none"]
    return cells


def answers_benchmark(run, path):
    data = run.read_json(path)
    bad = "malformed input (benchmark: %s): " + repo_rel(path)
    if not isinstance(data, dict) or data.get("format") != "scholarchain.rag.benchmark" or not isinstance(data.get("questions"), list):
        raise ToolExit(EXIT_FAILURE, bad % "format is not scholarchain.rag.benchmark or no questions")
    labels = {}
    for q in data["questions"]:
        exp = q.get("expected") if isinstance(q, dict) else None
        if not isinstance(q, dict) or not isinstance(q.get("id"), str) or not isinstance(exp, dict) \
                or not isinstance(exp.get("abstain"), bool):
            raise ToolExit(EXIT_FAILURE, bad % "a question lacks id or expected.abstain")
        labels[q["id"]] = exp["abstain"]
    return labels


def answers_run_report(run, path):
    data = run.read_json(path)
    bad = "malformed input (run report: %s): " + repo_rel(path)
    if not isinstance(data, dict) or data.get("format") != "scholarchain.rag.benchmark-run-report":
        raise ToolExit(EXIT_FAILURE, bad % "format is not scholarchain.rag.benchmark-run-report")
    res = data.get("results") if isinstance(data.get("results"), dict) else {}
    ans = res.get("answers") if isinstance(res.get("answers"), dict) else {}
    cc = ans.get("claimCounts")
    if not isinstance(cc, dict) or not cc or not all(nonneg_int(v) for v in cc.values()):
        raise ToolExit(EXIT_FAILURE, bad % "results.answers.claimCounts is not a map of counts")
    if not nonneg_int(res.get("questions")):
        raise ToolExit(EXIT_FAILURE, bad % "results.questions is not a count")
    return data


def answers_explain(run, path):
    fields, rows = run.read_csv(path)
    bad = "malformed input (explain CSV: %s): " + repo_rel(path)
    for col in ("question", "query_id", "abstained"):
        if col not in fields:
            raise ToolExit(EXIT_FAILURE, bad % ("no %s column" % col))
    for r in rows:
        if (r.get("abstained") or "").strip().lower() not in ("true", "false"):
            raise ToolExit(EXIT_FAILURE, bad % "an abstained cell is neither true nor false")
    return fields, rows


def dyn_s(item, name):
    v = item.get(name)
    if isinstance(v, dict) and isinstance(v.get("S"), str):
        return v["S"]
    if isinstance(v, dict) and isinstance(v.get("N"), str):
        return v["N"]
    return None


def answers_audit(run, path):
    data = run.read_json(path)
    bad = "malformed input (query audit export: %s): " + repo_rel(path)
    items = data.get("Items") if isinstance(data, dict) else None
    if not isinstance(items, list):
        raise ToolExit(EXIT_FAILURE, bad % "no Items list")
    out = []
    for it in items:
        if not isinstance(it, dict):
            raise ToolExit(EXIT_FAILURE, bad % "an item is not an object")
        if dyn_s(it, "eventType") != "query-audit":
            continue
        try:
            rec = json.loads(dyn_s(it, "record") or "")
        except ValueError:
            raise ToolExit(EXIT_FAILURE, bad % "a query-audit record is not JSON")
        vc = rec.get("verificationCounts") if isinstance(rec, dict) else None
        if not isinstance(vc, dict) or not all(nonneg_int(v) for v in vc.values()) or not isinstance(rec.get("abstained"), bool):
            raise ToolExit(EXIT_FAILURE, bad % "a query-audit record lacks verificationCounts or abstained")
        codes = rec.get("abstentionCodes")
        att = dyn_s(it, "attempt")
        qh = rec.get("questionHash")
        qr = rec.get("queryRunId")
        out.append({
            "id": "%s#attempt-%s" % (qr or "unknown", att or "unknown"),
            "query": qr if isinstance(qr, str) and qr else "unknown",
            "attempt_no": to_int(att) if isinstance(att, str) and re.match(r"^[0-9]+$", att) else None,
            "question": qh if isinstance(qh, str) else "",
            "counts": dict(vc),
            "abstained": rec["abstained"],
            "codes": [str(c) for c in codes] if isinstance(codes, list) else None,
            "prompts": [str(p) for p in rec.get("promptVersions") or []],
            "graph": rec.get("graphRunId") or "",
        })
    if not out:
        raise ToolExit(EXIT_FAILURE, bad % "no query-audit record")
    return out


def collapse_attempts(records):
    """AUDIT_COLLAPSE_RULE: one answer per question, in order of first appearance. Each answer is
    the kept record (its last attempt) plus 'attempts', the number of records collapsed into it.
    Records without questionHash group by queryRunId ('unknown' when absent), so two such records
    of one query never count as two answers."""
    groups, order = {}, []
    for pos, a in enumerate(records):
        key = ("questionHash", a["question"]) if a["question"] else ("queryRunId", a["query"])
        if key not in groups:
            groups[key] = []
            order.append(key)
        groups[key].append((a["attempt_no"] if a["attempt_no"] is not None else -1, pos, a))
    out = []
    for key in order:
        ranked = sorted(groups[key], key=lambda t: (t[0], t[1]))
        kept = dict(ranked[-1][2])
        kept["attempts"] = len(ranked)
        out.append(kept)
    return out


def answers_demo(run, path):
    data = run.read_json(path)
    bad = "malformed input (demo answer: %s): " + repo_rel(path)
    ans = data.get("answer") if isinstance(data, dict) else None
    if not isinstance(ans, dict) or not isinstance(ans.get("abstained"), bool) or not isinstance(ans.get("claims"), list):
        raise ToolExit(EXIT_FAILURE, bad % "not a completed query result (answer.claims and answer.abstained)")
    claims = []
    for c in ans["claims"]:
        if not isinstance(c, dict) or not isinstance(c.get("status"), str) or not isinstance(c.get("evidenceIds") or [], list):
            raise ToolExit(EXIT_FAILURE, bad % "a claim lacks a status or its evidenceIds is not a list")
        claims.append((c["status"], [str(e) for e in c.get("evidenceIds") or []]))
    ev = ans.get("evidence")
    types = None
    if isinstance(ev, list):
        types = {}
        for item in ev:
            if isinstance(item, dict) and isinstance(item.get("evidenceId"), str) and isinstance(item.get("evidenceType"), str):
                types[item["evidenceId"]] = item["evidenceType"]
    elif ev is not None:
        raise ToolExit(EXIT_FAILURE, bad % "answer.evidence is neither a list nor null")
    plan = ans.get("plan") if isinstance(ans.get("plan"), dict) else {}
    qid = data.get("queryId")
    return {
        "id": qid if isinstance(qid, str) and qid else os.path.basename(path),
        "file": os.path.basename(path),
        "question": ans.get("question") if isinstance(ans.get("question"), str) else "",
        "claims": claims,
        "types": types,
        "abstained": ans["abstained"],
        "graph": plan.get("graphRunId") if isinstance(plan.get("graphRunId"), str) else "",
    }


def status_counts(statuses):
    out = {}
    for s in statuses:
        out[s] = out.get(s, 0) + 1
    return out


def per_answer_row(src, aid, qref, counts, abstained, codes, types_cited, untyped):
    if counts is None:
        cells = [None] * 6
    else:
        other = sum(v for s, v in counts.items() if s not in CLAIM_STATUSES)
        cells = [sum(counts.values())] + [counts.get(s, 0) for s in CLAIM_STATUSES] + [other]
    return [src, aid, qref] + cells + [abstained, codes, types_cited, untyped]


def cmd_answers(run, argv):
    p = Parser(prog="evaluate.py answers")
    add_common(p)
    p.add_argument("--run-report", help="benchmark run report (attempt-06): aggregated counts")
    p.add_argument("--benchmark", help="benchmark-v1.json: abstain labels per question")
    p.add_argument("--explain-csv", help="load-explain.csv of the D-query run")
    p.add_argument("--audit", help="DynamoDB RunLedger export holding query-audit records (clip 01)")
    p.add_argument("--demo-answer", action="append", default=[], help="saved completed query result (E7; repeatable)")
    p.add_argument("--demo-provenance", help="provenance of the demo answers, recorded before scoring (required with --demo-answer)")
    p.add_argument("--label", action="append", default=[], help="SOURCE.FIELD=TEXT for a version label the file lacks")
    p.add_argument("--seed", type=int, required=True)
    p.add_argument("--resamples", type=int, default=2000)
    ns = p.parse_args(argv)
    setup_suite(run, ns)
    if ns.resamples < 1:
        raise ToolExit(EXIT_USAGE, "usage: --resamples must be at least 1")
    if not (ns.run_report or ns.explain_csv or ns.audit or ns.demo_answer):
        raise ToolExit(EXIT_USAGE, "usage: give at least one answer source (--run-report, --explain-csv, --audit, --demo-answer)")
    if ns.demo_answer and not (ns.demo_provenance or "").strip():
        raise ToolExit(EXIT_USAGE, "usage: --demo-provenance is required with --demo-answer (provenance is recorded before scoring)")
    labels = parse_labels(ns.label)
    for path, what in [(ns.run_report, "run report"), (ns.benchmark, "benchmark"), (ns.explain_csv, "explain CSV"),
                       (ns.audit, "query audit export")] + [(d, "demo answer") for d in ns.demo_answer]:
        if path:
            require_file(path, what)

    # Read and check every input before anything is computed or written.
    bench = answers_benchmark(run, ns.benchmark) if ns.benchmark else None
    bench_sha = sha256_file(ns.benchmark) if ns.benchmark else None
    report = answers_run_report(run, ns.run_report) if ns.run_report else None
    explain = answers_explain(run, ns.explain_csv) if ns.explain_csv else None
    audit = answers_audit(run, ns.audit) if ns.audit else None
    demos = [answers_demo(run, d) for d in ns.demo_answer]
    seed, reps = ns.seed, ns.resamples

    sources, rates, per, checks = [], [], [], []
    abst_rule_all = "all answers of the source"
    rule_lab = "answers to questions benchmark-v1 labels to abstain"
    rule_unlab = "answers to questions benchmark-v1 labels not to abstain"

    if report is not None:
        src = "run-report"
        res = report["results"]
        ans = res["answers"]
        ver = report.get("versions") if isinstance(report.get("versions"), dict) else {}
        inp = report.get("input") if isinstance(report.get("input"), dict) else {}
        prompts = ver.get("prompts") if isinstance(ver.get("prompts"), list) else []
        from_file = {
            "code_version": ver.get("codeVersion") if isinstance(ver.get("codeVersion"), str) else "",
            "prompt_set": " ".join(str(x) for x in prompts),
            "graph_run": ("export manifest sha256 %s" % inp["exportManifestSha256"]) if isinstance(inp.get("exportManifestSha256"), str) else "",
        }
        n_q = res["questions"]
        sources.append([src, repo_rel(ns.run_report), sha256_file(ns.run_report), "aggregated counts only", n_q, n_q]
                       + label_cells(src, from_file, labels) + [""])
        rates += claim_rows(src, "all claims", dict(ans["claimCounts"]), None, "conflicting" in ans["claimCounts"], seed, reps)
        rates += type_rows_not_measurable(src, "no claim or evidence type stored per claim")
        rates.append([src, "answer_key_agreement_per_claim", "all claims", "a", None, None,
                      NOT_AVAILABLE % "aggregated report; no per-claim values", None, None, "", CLAIM_RULE, ""])
        dgr = res.get("deterministicGraphRetrieval") if isinstance(res.get("deterministicGraphRetrieval"), dict) else {}
        if nonneg_int(dgr.get("expectedValues")) and nonneg_int(dgr.get("exactMatches")) and dgr["expectedValues"] > 0:
            misses = dgr.get("misses") if isinstance(dgr.get("misses"), list) else []
            rates.append([src, "answer_key_vs_evidence_bundle", "expected values", "a", dgr["exactMatches"], dgr["expectedValues"],
                          dgr["exactMatches"] / float(dgr["expectedValues"]), None, None, "no interval (aggregated)",
                          "answer-key values of the benchmark questions",
                          "compares the answer key with the evidence bundle's items, not with the claims: retrieval agreement, not claim accuracy; misses listed: %d" % len(misses)])
        else:
            rates.append([src, "answer_key_vs_evidence_bundle", "expected values", "a", None, None,
                          NOT_AVAILABLE % "no deterministicGraphRetrieval counts", None, None, "", "", ""])
        pas = dgr.get("pathsAgainstSparkEvidence") if isinstance(dgr.get("pathsAgainstSparkEvidence"), dict) else {}
        if nonneg_int(pas.get("checked")) and nonneg_int(pas.get("matched")) and pas["checked"] > 0:
            rates.append([src, "answer_key_vs_evidence_bundle", "paths against Spark path evidence", "a", pas["matched"], pas["checked"],
                          pas["matched"] / float(pas["checked"]), None, None, "no interval (aggregated)",
                          "path questions checked against Spark path evidence",
                          "evidence-bundle paths, not claims"])
        pe = ans.get("plantedErrors") if isinstance(ans.get("plantedErrors"), dict) else {}
        if nonneg_int(pe.get("planted")) and nonneg_int(pe.get("caught")) and pe["planted"] > 0:
            rates.append([src, "planted_error_catch_rate", "all kinds", "a", pe["caught"], pe["planted"], pe["caught"] / float(pe["planted"]),
                          None, None, "no interval (aggregated)", "errors planted into answers by the benchmark runner",
                          "verifier test on planted errors (internal); not answer accuracy"])
            kinds = pe.get("byKind") if isinstance(pe.get("byKind"), dict) else {}
            for kind in sorted(kinds):
                kv = kinds[kind] if isinstance(kinds[kind], dict) else {}
                if nonneg_int(kv.get("planted")) and nonneg_int(kv.get("caught")) and kv["planted"] > 0:
                    rates.append([src, "planted_error_catch_rate", "kind: %s" % kind, "a", kv["caught"], kv["planted"],
                                  kv["caught"] / float(kv["planted"]), None, None, "no interval (aggregated)",
                                  "errors of this kind planted by the benchmark runner",
                                  "verifier test on planted errors (internal); not answer accuracy"])
        e_, h_ = ans.get("abstentionsExpected"), ans.get("expectedAbstentionsHappened")
        u_, m_ = ans.get("unexpectedAbstentions"), ans.get("missedAbstentions")
        if nonneg_int(e_) and nonneg_int(h_) and isinstance(u_, list) and isinstance(m_, list):
            rates.append(wilson_row(src, "abstention_rate", "all answers", h_ + len(u_), n_q, abst_rule_all,
                                    "abstained = expectedAbstentionsHappened + unexpectedAbstentions"))
            rates.append(wilson_row(src, "abstention_recall_on_labelled", "abstain-labelled questions", h_, e_, rule_lab))
            rates.append(wilson_row(src, "false_abstention_rate", "questions labelled not to abstain", len(u_), n_q - e_, rule_unlab))
            checks.append([src, "expectedAbstentionsHappened + missedAbstentions equals abstentionsExpected", e_, h_ + len(m_), e_ == h_ + len(m_)])
        else:
            rates.append([src, "abstention_rate", "all answers", "a", None, None, NOT_AVAILABLE % "no abstention counts",
                          None, None, "", abst_rule_all, ""])
        rates.append([src, "abstention_code", "all answers", "a", None, None, NOT_AVAILABLE % "the file holds no abstention codes",
                      None, None, "", abst_rule_all, ""])
        if bench is not None:
            n_lab = len([q for q in bench if bench[q]])
            checks.append([src, "abstentionsExpected equals the benchmark's abstain-labelled questions", n_lab, e_, n_lab == e_])
            checks.append([src, "questions equals the benchmark's question count", len(bench), n_q, len(bench) == n_q])
            qs = inp.get("questionsSha256")
            checks.append([src, "input.questionsSha256 equals the benchmark file's sha256", bench_sha, qs, qs == bench_sha])

    if explain is not None:
        src = "explain-csv"
        fields, erows = explain
        qset = sorted(set((r.get("question") or "").strip() for r in erows))
        sources.append([src, repo_rel(ns.explain_csv), sha256_file(ns.explain_csv), "per explain row; no claim counts",
                        len(erows), len(qset)] + label_cells(src, {}, labels) + [""])
        has_counts = any(s in fields for s in CLAIM_STATUSES)
        why = "verification-count columns present but not read by this tool version" if has_counts else "no verification-count column"
        for metric, _ in CLAIM_METRICS:
            rates.append([src, metric, "all claims", "a", None, None, NOT_AVAILABLE % why, None, None, "", CLAIM_RULE, ""])
        rates += type_rows_not_measurable(src, "no claim or evidence type in the file")
        abst = [(r.get("abstained") or "").strip().lower() == "true" for r in erows]
        rule_rows = "explain rows (one per explain read of an answered or abstained query; questions repeat across levels)"
        rates.append(wilson_row(src, "abstention_rate", "all explain rows", sum(1 for a in abst if a), len(erows), rule_rows,
                                descriptive=EXPLAIN_DESCRIPTIVE))
        if bench is None:
            for metric, bd in (("abstention_recall_on_labelled", "abstain-labelled questions"),
                               ("false_abstention_rate", "questions labelled not to abstain")):
                rates.append([src, metric, bd, "a", None, None, NOT_MEASURABLE % "no --benchmark", None, None, "", "", ""])
        else:
            lab = [a for r, a in zip(erows, abst) if bench.get((r.get("question") or "").strip()) is True]
            unl = [a for r, a in zip(erows, abst) if bench.get((r.get("question") or "").strip()) is False]
            if lab:
                rates.append(wilson_row(src, "abstention_recall_on_labelled", "abstain-labelled questions",
                                        sum(1 for a in lab if a), len(lab), rule_lab + " (explain rows)",
                                        descriptive=EXPLAIN_DESCRIPTIVE))
            else:
                rates.append([src, "abstention_recall_on_labelled", "abstain-labelled questions", "a", 0, 0,
                              NOT_MEASURABLE % "no explain row for an abstain-labelled question", None, None, "", rule_lab, ""])
            rates.append(wilson_row(src, "false_abstention_rate", "questions labelled not to abstain",
                                    sum(1 for a in unl if a), len(unl), rule_unlab + " (explain rows)",
                                    descriptive=EXPLAIN_DESCRIPTIVE))
            unknown = len([r for r in erows if (r.get("question") or "").strip() not in bench])
            checks.append([src, "explain rows whose question is not in the benchmark", 0, unknown, unknown == 0])
        codes_why = "abstention codes present but not read by this tool version" if "abstention_codes" in fields else "the file holds no abstention codes"
        rates.append([src, "abstention_code", "all explain rows", "a", None, None, NOT_AVAILABLE % codes_why, None, None, "", rule_rows, ""])
        for i, (r, a) in enumerate(zip(erows, abst), 1):
            per.append(per_answer_row(src, "row %d %s" % (i, (r.get("query_id") or "").strip()), (r.get("question") or "").strip(),
                                      None, a, NOT_AVAILABLE % "no column", "", None))

    if audit is not None:
        src = "audit"
        prompts = sorted(set(p_ for a in audit for p_ in a["prompts"]))
        graphs = sorted(set(a["graph"] for a in audit if a["graph"]))
        from_file = {"code_version": "", "prompt_set": " ".join(prompts), "graph_run": " ".join(graphs)}
        dq = len(set(a["question"] for a in audit))
        records = len(audit)
        # P16-C-02: attempts of one question count as one answer; every rate below is over answers.
        audit = collapse_attempts(audit)
        abst_rule_audit = "all answers of the source, " + AUDIT_COLLAPSE_RULE
        sources.append([src, repo_rel(ns.audit), sha256_file(ns.audit),
                        "per answer counts (attempts collapsed, last attempt kept); no claim texts",
                        len(audit), dq] + label_cells(src, from_file, labels)
                       + ["%d attempt record(s) collapsed into %d answer(s): %s" % (records, len(audit), AUDIT_COLLAPSE_RULE)])
        audit_desc = independence_reason([a["question"] for a in audit], "answer(s) after collapsing attempts")
        totals = {}
        for a in audit:
            for s, v in a["counts"].items():
                totals[s] = totals.get(s, 0) + v
        has_conf = any("conflicting" in a["counts"] for a in audit)
        rates += claim_rows(src, "all claims", totals, [a["counts"] for a in audit], has_conf, seed, reps,
                            "claims of each answer's last attempt", audit_desc)
        rates += type_rows_not_measurable(src, "no claim or evidence type stored per claim")
        rates.append(wilson_row(src, "abstention_rate", "all answers", sum(1 for a in audit if a["abstained"]), len(audit),
                                abst_rule_audit, descriptive=audit_desc))
        for metric, bd in (("abstention_recall_on_labelled", "abstain-labelled questions"),
                           ("false_abstention_rate", "questions labelled not to abstain")):
            rates.append([src, metric, bd, "a", None, None, NOT_MEASURABLE % "the audit holds no benchmark question ID",
                          None, None, "", "", ""])
        if any(a["codes"] is None for a in audit):
            rates.append([src, "abstention_code", "all answers", "a", None, None, NOT_AVAILABLE % "a record lacks abstentionCodes",
                          None, None, "", abst_rule_audit, ""])
        else:
            code_set = sorted(set(c for a in audit for c in a["codes"]))
            if not code_set:
                rates.append([src, "abstention_code", "(none)", "a", 0, len(audit), 0.0, None, None, "", abst_rule_audit,
                              "no answer carries an abstention code"])
            for c in code_set:
                k = len([a for a in audit if c in a["codes"]])
                rates.append([src, "abstention_code", c, "a", k, len(audit), k / float(len(audit)), None, None, "",
                              abst_rule_audit, "descriptive"])
        for a in audit:
            per.append(per_answer_row(src, "%s (last of %d attempt(s))" % (a["id"], a["attempts"]), a["question"],
                                      a["counts"], a["abstained"],
                                      ";".join(a["codes"]) if a["codes"] is not None else NOT_AVAILABLE % "not recorded",
                                      NOT_AVAILABLE % "no evidence types", None))

    if demos:
        src = "demo"
        graphs = sorted(set(d["graph"] for d in demos if d["graph"]))
        from_file = {"code_version": "", "prompt_set": "", "graph_run": " ".join(graphs)}
        # P16-C-02: saved answers are not collapsed (each is its own query); intervals only when they
        # answer distinct questions, else every rate of this source is descriptive.
        demo_desc = independence_reason([d["question"] for d in demos], "answer(s)")
        sources.append([src, ";".join(repo_rel(x) for x in ns.demo_answer), ";".join(sha256_file(x) for x in ns.demo_answer),
                        "per claim", len(demos), len(set(d["question"] for d in demos))]
                       + label_cells(src, from_file, labels)
                       + [join_notes("provenance: %s" % ns.demo_provenance.strip(), demo_desc)])
        units, totals = [], {}
        type_units = dict((name, []) for name, _ in CLAIM_TYPES)
        type_totals = dict((name, {}) for name, _ in CLAIM_TYPES)
        for d in demos:
            counts = status_counts(s for s, _ in d["claims"])
            units.append(counts)
            for s, v in counts.items():
                totals[s] = totals.get(s, 0) + v
            cited_all, untyped = set(), 0
            per_type = dict((name, []) for name, _ in CLAIM_TYPES)
            for status, eids in d["claims"]:
                cited = set(d["types"].get(e) for e in eids if d["types"] and d["types"].get(e)) if d["types"] is not None else set()
                if not cited:
                    untyped += 1
                cited_all |= cited
                for name, wires in CLAIM_TYPES:
                    if cited & set(wires):
                        per_type[name].append(status)
            for name, _ in CLAIM_TYPES:
                c = status_counts(per_type[name]) if d["types"] is not None else {}
                type_units[name].append(c if c else None)
                for s, v in c.items():
                    type_totals[name][s] = type_totals[name].get(s, 0) + v
            per.append(per_answer_row(src, d["id"], d["file"], counts, d["abstained"],
                                      NOT_AVAILABLE % "the answer view holds a reason text, not codes",
                                      ";".join(sorted(cited_all)) if d["types"] is not None else NOT_AVAILABLE % "evidence not included",
                                      untyped))
        rates += claim_rows(src, "all claims", totals, units, True, seed, reps,
                            "conflicting is a status of the answer view's verifier vocabulary", demo_desc)
        for name, wires in CLAIM_TYPES:
            if not wires:
                rates.append([src, "claim_support_rate", "claim type: %s" % name, "a", None, None,
                              NOT_MEASURABLE % "no evidence type identifies identifier claims", None, None, "", CLAIM_RULE, ""])
                continue
            rates += claim_rows(src, "claim type: %s (claims citing %s evidence)" % (name, "/".join(wires)),
                                type_totals[name], type_units[name], True, seed, reps,
                                "by cited evidence type (proxy); answers without included evidence are left out", demo_desc)
        rates.append(wilson_row(src, "abstention_rate", "all answers", sum(1 for d in demos if d["abstained"]), len(demos), abst_rule_all,
                                descriptive=demo_desc))
        for metric, bd in (("abstention_recall_on_labelled", "abstain-labelled questions"),
                           ("false_abstention_rate", "questions labelled not to abstain")):
            rates.append([src, metric, bd, "a", None, None, NOT_MEASURABLE % "the saved answers hold no benchmark question ID",
                          None, None, "", "", ""])
        rates.append([src, "abstention_code", "all answers", "a", None, None,
                      NOT_AVAILABLE % "the answer view holds a reason text, not codes", None, None, "", abst_rule_all, ""])

    run.write_csv("sources.csv", ["source", "file", "sha256", "granularity", "answers", "distinct_questions"]
                  + [x for f in LABEL_FIELDS for x in (f, f + "_origin")] + ["note"], sources)
    run.write_csv("rates.csv", ["source", "metric", "breakdown", "kind", "numerator", "denominator", "value", "ci_low",
                                "ci_high", "ci_method", "inclusion_rule", "note"], rates)
    run.write_csv("per-answer.csv", ["source", "answer_id", "question_ref", "claims"] + CLAIM_STATUSES
                  + ["other_statuses", "abstained", "abstention_codes", "evidence_types_cited", "untyped_claims"], per)
    run.write_csv("checks.csv", ["source", "check", "expected", "found", "equal"], checks)
    run.finish()
    for r in rates:
        if r[1] in ("claim_support_rate", "contradiction_rate", "abstention_rate") and r[2] in ("all claims", "all answers", "all explain rows"):
            say("RESULT", "%s %s = %s (%s of %s)" % (r[0], r[1], num(r[6]), num(r[4]), num(r[5])))


# ---- graph (group 2) ---------------------------------------------------------------------------

SUITE_TABLE_COLUMNS = ["suite", "test_ref", "test_title", "group2_checks", "expectation_source", "evidence", "note"]
GROUP2_CHECKS = ["degree", "weighted_degree", "components", "paths", "communities", "bridges", "kcore", "integrity",
                 "duplicates", "orphans", "none"]
EXPECTATION_SOURCES = ["independent", "mixed", "property"]
SPARK_COLUMNS = ["run_id", "table", "window_label", "records", "invalid_records"]
WINDOW_METRIC_TABLES = ["metrics:group_size", "bridges", "kcore", "components", "communities", "evidence:metric"]
ZERO_TABLES = [
    ("integrity_violations", "sum of the graph integrity checks (referential, orphan, duplicate assignment, consistency); per-check counts are not in this file"),
    ("rejects", "invalid input records (decode or contract failure)"),
    ("unresolved_mention", "mentions without an identity assignment"),
]
GRAPH_NOT_MEASURABLE = [
    ("referential integrity rate over the full graph", "no graph export exists; the run CSV carries table row counts and the integrity total only"),
    ("duplicate coauthor-edge rate", "no graph export exists; uniqueness is a construction rule tested by the Spark suites, not counted in the CSV"),
    ("orphan rate", "no graph export exists; only the integrity total (0 means every orphan check is 0) is in the CSV"),
    ("coauthor-edge support rate (supporting works per edge)", "no graph export exists"),
    ("per-check integrity breakdown", "the CSV holds the integrity total only"),
]
# (check, DynamoDB attribute names, paths inside the item's `record` JSON); presence only.
PROVENANCE_CHECKS = [
    ("run_id", ["runId"], ["provenance.runId"]),
    ("snapshot", ["snapshotId"], ["provenance.snapshotId"]),
    ("dataset", ["datasetId"], ["provenance.datasetId"]),
    ("source_record_ids", ["sourceRecordIds"], ["provenance.sourceRecordIds"]),
    ("policy_version", ["policyVersion"], ["provenance.policyVersion"]),
    # P16-C-01: only `schemaVersion`; the top-level `version` attribute is the storage layer's
    # conditional-write item version (KeyValueStore Item.Version, putIfVersion), not a schema version.
    ("schema_version", ["schemaVersion"], ["schemaVersion"]),
    ("item_hash", ["itemHash"], []),
    ("content_hash", ["contentHash"], ["provenance.contentHash"]),
]


def dyn_present(v):
    if not isinstance(v, dict) or len(v) != 1:
        return False
    k, x = list(v.items())[0]
    if k in ("S", "N", "B"):
        return isinstance(x, str) and x != ""
    if k == "BOOL":
        return isinstance(x, bool)
    if k in ("L", "SS", "NS", "BS"):
        return isinstance(x, list) and len(x) > 0
    if k == "M":
        return isinstance(x, dict) and len(x) > 0
    return False


def json_present(v):
    return v is not None and v != "" and v != [] and v != {}


def graph_suite_table(run, path):
    fields, rows = run.read_csv(path)
    bad = "malformed input (suite table: %s): " + repo_rel(path)
    if fields != SUITE_TABLE_COLUMNS:
        raise ToolExit(EXIT_FAILURE, bad % ("columns are not %s" % ",".join(SUITE_TABLE_COLUMNS)))
    if not rows:
        raise ToolExit(EXIT_FAILURE, bad % "no rows")
    seen = set()
    out = []
    for i, r in enumerate(rows, 2):
        if None in r or any(v is None for v in r.values()):
            raise ToolExit(EXIT_FAILURE, bad % ("line %d: not exactly %d cells" % (i, len(SUITE_TABLE_COLUMNS))))
        ref = (r.get("test_ref") or "").strip()
        checks = [c.strip() for c in (r.get("group2_checks") or "").split(";") if c.strip()]
        src = (r.get("expectation_source") or "").strip()
        if not ref or ref in seen or not (r.get("suite") or "").strip():
            raise ToolExit(EXIT_FAILURE, bad % ("line %d: empty suite or test_ref, or a repeated test_ref" % i))
        if not checks or any(c not in GROUP2_CHECKS for c in checks):
            raise ToolExit(EXIT_FAILURE, bad % ("line %d: group2_checks outside the vocabulary" % i))
        if src not in EXPECTATION_SOURCES:
            raise ToolExit(EXIT_FAILURE, bad % ("line %d: expectation_source outside the vocabulary" % i))
        seen.add(ref)
        out.append({"suite": r["suite"].strip(), "checks": checks, "source": src})
    return out


def graph_spark_csv(run, path):
    fields, rows = run.read_csv(path)
    bad = "malformed input (spark outputs: %s): " + repo_rel(path)
    if fields != SPARK_COLUMNS:
        raise ToolExit(EXIT_FAILURE, bad % ("columns are not %s" % ",".join(SPARK_COLUMNS)))
    out = []
    for i, r in enumerate(rows, 2):
        rec, inv = to_int(r.get("records")), to_int(r.get("invalid_records"))
        if rec is None or inv is None or rec < 0 or inv < 0 or not (r.get("table") or "").strip():
            raise ToolExit(EXIT_FAILURE, bad % ("line %d: table, records or invalid_records unreadable" % i))
        out.append({"run": (r.get("run_id") or "").strip(), "table": r["table"].strip(),
                    "window": (r.get("window_label") or "").strip(), "records": rec, "invalid": inv})
    if not out:
        raise ToolExit(EXIT_FAILURE, bad % "no rows")
    return out


def spark_invariants(obs, rows):
    recs = {}
    for r in rows:
        recs[r["table"]] = r["records"]
    out = []

    def eq(left, right, prop):
        lv, rv = recs.get(left), recs.get(right)
        if lv is None or rv is None:
            res = NOT_MEASURABLE % ("table absent: %s" % (left if lv is None else right))
        else:
            res = "equal" if lv == rv else "different"
        out.append([obs, "equal counts", left, lv, right, rv, res, prop, "a"])

    inv = sum(r["invalid"] for r in rows)
    out.append([obs, "invalid_records total", "all tables", inv, "tables", len(rows), "zero" if inv == 0 else "nonzero",
                "schema validity of every output row (core contract check); rejects and integrity_violations have no mirror, so their invalid_records are 0 by construction", "a"])
    for t, prop in ZERO_TABLES:
        v = recs.get(t)
        res = NOT_MEASURABLE % "table absent" if v is None else ("zero" if v == 0 else "nonzero")
        out.append([obs, "records", t, v, "", None, res, prop, "a"])
    eq("nodes:researcher", "canonical_researcher", "every canonical researcher is a node")
    eq("nodes:work", "edges:published_in", "every work has one venue edge")
    windows = []
    for r in rows:
        if r["table"].startswith("coauthor_edge:"):
            w = r["table"].split(":", 1)[1]
            if w not in windows:
                windows.append(w)
    for w in windows:
        eq("coauthor_edge:" + w, "edges:coauthored_with:" + w, "every coauthor edge has its graph edge (window %s)" % w)
    for w in windows:
        for t in WINDOW_METRIC_TABLES:
            eq("metrics:breadth:" + w, t + ":" + w, "one metric row per active researcher in every metric table (window %s)" % w)
    for w in windows:
        if w.startswith("full"):
            eq("canonical_researcher", "metrics:breadth:" + w, "every canonical researcher has a full-window metric row")
    for r in rows:
        if r["table"].startswith("path_evidence:"):
            w = r["table"].split(":", 1)[1]
            eq("path_evidence:" + w, "semantic_summary:path:" + w, "one path summary per path evidence row (window %s)" % w)
    a, e = recs.get("authorship"), recs.get("edges:authored")
    res = NOT_MEASURABLE % "table absent" if a is None or e is None else "difference %d" % (a - e)
    out.append([obs, "difference (not an invariant)", "authorship", a, "edges:authored", e, res,
                "authored edges are one per researcher and work; authorship rows are one per mention: a difference is possible where a researcher has two mentions on one work (not checked further)", "a"])
    return out


def cmd_graph(run, argv):
    p = Parser(prog="evaluate.py graph")
    add_common(p)
    p.add_argument("--suite-table", required=True, help="spark-engineer's suite table as CSV (experiments/evaluation/spark-suite-table-v1.csv)")
    p.add_argument("--spark-outputs", action="append", required=True, help="spark-outputs.csv of a Phase 15 run (repeatable)")
    p.add_argument("--sample", action="append", default=[], help="TABLE=PATH of a DynamoDB sample export (repeatable)")
    p.add_argument("--test-log", help="sbt log of the E5 Spark suite run (optional)")
    ns = p.parse_args(argv)
    setup_suite(run, ns)
    samples = []
    for s in ns.sample:
        if "=" not in s or not s.split("=", 1)[0].strip() or not s.split("=", 1)[1].strip():
            raise ToolExit(EXIT_USAGE, "usage: --sample TABLE=PATH")
        samples.append((s.split("=", 1)[0].strip(), s.split("=", 1)[1].strip()))
    require_file(ns.suite_table, "suite table")
    for sp in ns.spark_outputs:
        require_file(sp, "spark outputs CSV")
    for _, sp in samples:
        require_file(sp, "DynamoDB sample")
    if ns.test_log:
        require_file(ns.test_log, "E5 test log")

    # Read and check every input before anything is computed or written.
    tests = graph_suite_table(run, ns.suite_table)
    spark = [(sp, sha256_file(sp), graph_spark_csv(run, sp)) for sp in ns.spark_outputs]
    sample_items = []
    for table, sp in samples:
        data = run.read_json(sp)
        items = data.get("Items") if isinstance(data, dict) else None
        if not isinstance(items, list) or not all(isinstance(it, dict) for it in items):
            raise ToolExit(EXIT_FAILURE, "malformed input (DynamoDB sample: no Items list of objects): %s" % repo_rel(sp))
        sample_items.append((table, sp, items))
    log_lines = run.read_text(ns.test_log).split("\n") if ns.test_log else None

    # Suite table: hand-worked coverage per group 2 check.
    cov, summ = [], []
    for chk in GROUP2_CHECKS:
        t = [x for x in tests if chk in x["checks"]]
        k = dict((s, len([x for x in t if x["source"] == s])) for s in EXPECTATION_SOURCES)
        cov.append([chk, len(t), k["independent"], k["mixed"], k["property"],
                    "not a group 2 check" if chk == "none" else (k["independent"] > 0),
                    "b where independent (hand-worked), a where mixed or property; classification by spark-engineer, not re-verified by this tool"])
    suites = []
    for x in tests:
        if x["suite"] not in suites:
            suites.append(x["suite"])
    for s in suites + ["all"]:
        t = [x for x in tests if s == "all" or x["suite"] == s]
        summ.append([s, len(t)] + [len([x for x in t if x["source"] == e]) for e in EXPECTATION_SOURCES])

    # Spark invariant counts, once per distinct file content.
    files, invs = [], []
    first_by_sha = {}
    for sp, sha, rows in spark:
        runs = sorted(set(r["run"] for r in rows))
        if sha in first_by_sha:
            files.append([repo_rel(sp), sha, len(rows), " ".join(runs),
                          "byte-identical to %s (same sha256): the same observation, not an independent one" % first_by_sha[sha]])
            continue
        obs = "observation %d" % (len(first_by_sha) + 1)
        first_by_sha[sha] = obs
        files.append([repo_rel(sp), sha, len(rows), " ".join(runs), obs])
        invs += spark_invariants(obs, rows)

    # Provenance fields present per sampled item, per table.
    prov = []
    note = "descriptive proportion of the sampled items; the samples were not drawn at random (selection not recorded): no confidence interval; presence only, values not validated"
    for table, sp, items in sample_items:
        recs, unparse = [], 0
        for it in items:
            rec = None
            if "record" in it:
                try:
                    rec = json.loads(dyn_s(it, "record") or "")
                except ValueError:
                    rec = None
                if not isinstance(rec, dict):
                    rec, unparse = None, unparse + 1
            recs.append(rec)
        for chk, attrs, paths in PROVENANCE_CHECKS:
            top = [any(dyn_present(it.get(a)) for a in attrs) for it in items]
            inrec = [rec is not None and any(json_present(get_path(rec, pth)) for pth in paths) for rec in recs]
            pres = len([1 for a, b in zip(top, inrec) if a or b])
            n = len(items)
            prov.append([table, repo_rel(sp), chk, n, pres, len([1 for a in top if a]), len([1 for b in inrec if b]),
                         (pres / float(n)) if n else NOT_MEASURABLE % "no items", unparse, note])

    tl = []
    if log_lines is not None:
        pat = re.compile(r"(Passed|Failed): Total ([0-9]+), Failed ([0-9]+), Errors ([0-9]+), Passed ([0-9]+)")
        for i, line in enumerate(log_lines, 1):
            m = pat.search(line)
            if m:
                total = int(m.group(2))
                tl.append([i, m.group(1).lower(), total, int(m.group(3)), int(m.group(4)), int(m.group(5)), len(tests), total == len(tests)])
        if not tl:
            tl.append([None, NOT_MEASURABLE % "no sbt summary line in the log", None, None, None, None, len(tests), None])

    run.write_csv("suite-coverage.csv", ["group2_check", "tests", "independent", "mixed", "property", "covered_by_independent", "kind"], cov)
    run.write_csv("suite-summary.csv", ["suite", "tests"] + EXPECTATION_SOURCES, summ)
    run.write_csv("spark-files.csv", ["file", "sha256", "data_rows", "run_ids", "observation"], files)
    run.write_csv("invariants.csv", ["observation", "check", "left_table", "left_value", "right_table", "right_value",
                                     "result", "integrity_property", "kind"], invs)
    run.write_csv("not-measurable.csv", ["item", "reason"], [[a, NOT_MEASURABLE % b] for a, b in GRAPH_NOT_MEASURABLE])
    run.write_csv("provenance.csv", ["table", "file", "check", "items", "present", "present_top_level", "present_in_record",
                                     "proportion", "records_unparseable", "note"], prov)
    if log_lines is not None:
        run.write_csv("test-log.csv", ["line", "outcome", "total", "failed", "errors", "passed", "suite_table_tests",
                                       "total_equals_table"], tl)
    run.finish()
    say("RESULT", "graph: %d suite tests; %d spark files (%d distinct); %d sample tables" % (len(tests), len(files), len(first_by_sha), len(sample_items)))


# ---- er (group 1, reduced plan) ------------------------------------------------------------------

ER_TABLE_HEADER = ["Set", "Units", "Precision (95%)", "Recall (95%)", "F1", "Coverage", "Unresolved"]
CONFUSION_HEADER = ["Decision", "MATCH", "NON_MATCH", "UNSURE"]
CONFUSION_DECISIONS = ["accepted", "rejected", "unresolved", "conflict"]
_PCT = r"([0-9]+(?:\.[0-9]+)?)%"
PCT_CELL_RE = re.compile(r"^(?:n/a|" + _PCT + r")(?: (?:n/a|\[" + _PCT + r", " + _PCT + r"\]))?(?: \(([^()]*(?:\[[^\]]*\])?[^()]*)\))?$")
BOOT_ITEM_RE = re.compile(r"(\w+) \[" + _PCT + r", " + _PCT + r"\](?: \(([^()]*(?:\[[^\]]*\])?[^()]*)\))?")
ZERO_BOUND_RE = re.compile(r"^- (\w+): bootstrap .*Zero-event bound \[" + _PCT + r", " + _PCT + r"\]\.?\s*$")


def pct_cell(cell, bad):
    """(value, low, high, flag) as printed (percent text without '%'; 'n/a' kept)."""
    m = PCT_CELL_RE.match(cell.strip())
    if not m:
        raise ToolExit(EXIT_FAILURE, bad % ("unreadable cell %r" % cell))
    value = m.group(1) if m.group(1) is not None else "n/a"
    has_ci = "[" in cell.split("(")[0]
    low = m.group(2) if has_ci else ("n/a" if " n/a" in cell else "")
    high = m.group(3) if has_ci else ("n/a" if " n/a" in cell else "")
    return value, low, high, m.group(4) or ""


def md_table(lines, start, header, bad):
    """Rows (cell lists) of the first markdown table at or after index start whose header equals header."""
    i = start
    while i < len(lines):
        if split_row(lines[i]) == header:
            j = i + 1
            if j < len(lines) and is_separator(split_row(lines[j]) or []):
                j += 1
            rows = []
            while j < len(lines):
                c = split_row(lines[j])
                if c is None:
                    break
                if len(c) != len(header):
                    raise ToolExit(EXIT_FAILURE, bad % ("a row of the table %s has %d cells" % (header[0], len(c))))
                rows.append(c)
                j += 1
            return rows
        i += 1
    raise ToolExit(EXIT_FAILURE, bad % ("no table with header %s" % " | ".join(header)))


def find_line(lines, text, bad, start=0):
    for i in range(start, len(lines)):
        if lines[i].strip() == text:
            return i
    raise ToolExit(EXIT_FAILURE, bad % ("no line %r" % text))


def set_kind(name):
    for prefix, kind in (("All rows", "all rows (unweighted)"), ("Weighted", "weighted estimate"), ("path ", "matching path"),
                         ("family ", "name family"), ("evidence", "evidence")):
        if name.startswith(prefix):
            return kind
    return "other"


def cmd_er(run, argv):
    p = Parser(prog="evaluate.py er")
    add_common(p)
    p.add_argument("--phase5-report", required=True, help="docs/agent-runs/phase-05/agent-report-er-eval.md (reduced plan)")
    p.add_argument("--start-marker", required=True, help="the line after which the final summary.md starts")
    p.add_argument("--end-marker", help="the line at which the final summary.md section ends")
    p.add_argument("--gold", help="the gold-label CSV: its label counts are compared with the report")
    p.add_argument("--note", action="append", default=[], help="a note written to notes.csv (repeatable)")
    ns = p.parse_args(argv)
    setup_suite(run, ns)
    require_file(ns.phase5_report, "Phase 5 ER evaluation report")
    if ns.gold:
        require_file(ns.gold, "gold labels")
    src = repo_rel(ns.phase5_report)
    bad = "malformed input (Phase 5 report: %s): " + src
    lines = [ln.rstrip("\r") for ln in run.read_text(ns.phase5_report).split("\n")]
    start = find_line(lines, ns.start_marker, bad)
    end = find_line(lines, ns.end_marker, bad, start + 1) if ns.end_marker else len(lines)
    sec = lines[start + 1:end]

    policy = run_id = code = gold_in_report = None
    labelled = None
    for ln in sec:
        m = re.match(r"^- Policy: (\S+)", ln)
        if m and policy is None:
            policy = m.group(1)
        m = re.match(r"^- Run: (run:v1:[0-9a-f]+), code version ([^,]+),", ln)
        if m and run_id is None:
            run_id, code = m.group(1), m.group(2)
        m = re.match(r"^\| gold \| .* \| ([0-9a-f]{64}) \|$", ln)
        if m and gold_in_report is None:
            gold_in_report = m.group(1)
        m = re.match(r"^- Labeled rows: ([0-9]+) \(MATCH ([0-9]+), NON_MATCH ([0-9]+), UNSURE ([0-9]+)\)", ln)
        if m and labelled is None:
            labelled = [int(x) for x in m.groups()]
    label = "phase-05 committed report (policy %s, run %s)" % (policy or "not stated", run_id or "not stated")

    main = md_table(sec, find_line(sec, "## Researcher resolution", bad), ER_TABLE_HEADER, bad)
    table = []
    for c in main:
        units = to_int(c[1])
        if units is None:
            raise ToolExit(EXIT_FAILURE, bad % ("units %r is not a count" % c[1]))
        pv, pl, ph, pf = pct_cell(c[2], bad)
        rv, rl, rh, rf = pct_cell(c[3], bad)
        f1, cov, unr = pct_cell(c[4], bad)[0], pct_cell(c[5], bad)[0], pct_cell(c[6], bad)[0]
        kind = set_kind(c[0])
        method = ("stratified bootstrap percentile 95% (as printed in the report)" if kind == "weighted estimate"
                  else "Wilson score 95% (as printed in the report)")
        table.append([label, c[0], kind, units, pv, pl, ph, pf, rv, rl, rh, rf, f1, cov, unr, method,
                      "b (human gold labels on er-v1 decisions)"])

    boot = {}
    for ln in sec:
        if ln.startswith("Weighted bootstrap 95%:"):
            for m in BOOT_ITEM_RE.finditer(ln):
                boot[m.group(1)] = (m.group(2), m.group(3), m.group(4) or "")
            break
    zero = {}
    for ln in sec:
        m = ZERO_BOUND_RE.match(ln)
        if m and m.group(1) not in zero:  # first per metric: the pair-level paragraph comes before the cluster-level one
            zero[m.group(1)] = (m.group(2), m.group(3))
    wrows = []
    for metric in sorted(set(boot) | set(zero)):
        b = boot.get(metric, ("", "", ""))
        z = zero.get(metric, ("", ""))
        wrows.append([metric, b[0], b[1], b[2], z[0], z[1], "as printed in the report (bootstrap line and reliability paragraph); not recomputed"])

    conf = []
    unw = {}
    for heading, which, integer in (("### Confusion matrix (unweighted rows)", "unweighted", True),
                                    ("### Confusion matrix (weighted: estimated population decisions)", "weighted", False)):
        rows = md_table(sec, find_line(sec, heading, bad), CONFUSION_HEADER, bad)
        for c in rows:
            vals = c[1:]
            if integer:
                ints = [to_int(v) for v in vals]
                if any(v is None or v < 0 for v in ints):
                    raise ToolExit(EXIT_FAILURE, bad % ("unweighted confusion cell is not a count in row %s" % c[0]))
                unw[c[0]] = ints
            elif not all(re.match(r"^[0-9]+(?:\.[0-9]+)?$", v) for v in vals):
                raise ToolExit(EXIT_FAILURE, bad % ("weighted confusion cell is not a number in row %s" % c[0]))
            conf.append([which, c[0]] + vals)
    if any(d not in unw for d in CONFUSION_DECISIONS):
        raise ToolExit(EXIT_FAILURE, bad % "the unweighted confusion matrix lacks a decision row (accepted, rejected, unresolved, conflict)")

    acc, rej, unr_row = unw["accepted"], unw["rejected"], unw["unresolved"]
    col = [sum(unw[d][i] for d in unw) for i in range(3)]
    tp, fp = acc[0], acc[1]
    fn = col[0] - tp
    all_units = sum(col)
    decided = acc[0] + acc[1] + rej[0] + rej[1]
    allrow = next((r for r in table if r[2] == "all rows (unweighted)"), None)
    rec = []
    for metric, k, n, idx, rule in (
            ("precision", tp, tp + fp, 4, "accepted pairs labelled MATCH or NON_MATCH; positive = accepted and MATCH"),
            ("recall", tp, col[0], 8, "pairs labelled MATCH"),
            ("f1", 2 * tp, 2 * tp + fp + fn, 12, "2 TP / (2 TP + FP + FN), FN = MATCH pairs not accepted"),
            ("coverage", decided, col[0] + col[1], 13, "pairs labelled MATCH or NON_MATCH; covered = accepted or rejected"),
            ("unresolved_rate", sum(unr_row), all_units, 14, "all labelled pairs, UNSURE included")):
        val = (k / float(n)) if n else None
        printed = allrow[idx] if allrow else ""
        if val is None or not printed or printed == "n/a":
            same = "not comparable"
        else:
            same = abs(val * 100.0 - float(printed)) <= 0.05 + 1e-9
        rec.append([metric, k, n, val, printed, same, rule])

    checks = []
    if allrow:
        checks.append(["All rows units equal the unweighted confusion matrix total", allrow[3], all_units, allrow[3] == all_units])
    if labelled:
        checks.append(["report Labeled rows equal the confusion matrix total", labelled[0], all_units, labelled[0] == all_units])
        for name, i in (("MATCH", 0), ("NON_MATCH", 1), ("UNSURE", 2)):
            checks.append(["report %s count equals the confusion matrix column sum" % name, labelled[i + 1], col[i], labelled[i + 1] == col[i]])
    if ns.gold:
        gf, grows = run.read_csv(ns.gold)
        if "label" not in gf:
            raise ToolExit(EXIT_FAILURE, "malformed input (gold labels: no label column): %s" % repo_rel(ns.gold))
        gc = {}
        for r in grows:
            lab = (r.get("label") or "").strip()
            gc[lab] = gc.get(lab, 0) + 1
        gsha = sha256_file(ns.gold)
        checks.append(["gold file rows equal the confusion matrix total", len(grows), all_units, len(grows) == all_units])
        for name, i in (("MATCH", 0), ("NON_MATCH", 1), ("UNSURE", 2)):
            checks.append(["gold file %s labels equal the confusion matrix column sum" % name, gc.get(name, 0), col[i], gc.get(name, 0) == col[i]])
        other = sum(v for k_, v in gc.items() if k_ not in ("MATCH", "NON_MATCH", "UNSURE"))
        checks.append(["gold file labels outside MATCH, NON_MATCH, UNSURE", 0, other, other == 0])
        checks.append(["gold file sha256 equals the report's gold input sha256", gold_in_report or "not stated", gsha,
                       gold_in_report == gsha])
    else:
        checks.append(["gold file label counts", "not checked (no --gold)", "", ""])

    notes = [["source", label], ["source_file", src], ["source_sha256", sha256_file(ns.phase5_report)],
             ["policy_version", policy or NOT_AVAILABLE % "no Policy line"], ["er_run", run_id or NOT_AVAILABLE % "no Run line"],
             ["code_version", code or NOT_AVAILABLE % "no Run line"],
             ["gold_sha256_in_report", gold_in_report or NOT_AVAILABLE % "no gold input row"],
             ["numerators_and_denominators", "given for the all-rows (unweighted) row only, recomputed from the unweighted confusion matrix; the other rows are printed percentages without counts"],
             ["er_v2_accuracy", NOT_MEASURABLE % "no gold-pair decision comparison input was given; this tool version has no --compare-decisions"]]
    notes += [["note", n_.strip()] for n_ in ns.note if n_.strip()]

    run.write_csv("table.csv", ["source", "set", "set_kind", "units", "precision_pct", "precision_ci_low_pct", "precision_ci_high_pct",
                                "precision_flag", "recall_pct", "recall_ci_low_pct", "recall_ci_high_pct", "recall_flag", "f1_pct",
                                "coverage_pct", "unresolved_pct", "interval_method", "evidence_kind"], table)
    run.write_csv("weighted-intervals.csv", ["metric", "bootstrap_low_pct", "bootstrap_high_pct", "flag", "zero_event_low_pct",
                                             "zero_event_high_pct", "note"], wrows)
    run.write_csv("confusion.csv", ["matrix", "decision", "MATCH", "NON_MATCH", "UNSURE"], conf)
    run.write_csv("recomputed.csv", ["metric", "numerator", "denominator", "recomputed_value", "report_pct",
                                     "equal_at_report_precision", "inclusion_rule"], rec)
    run.write_csv("checks.csv", ["check", "expected", "found", "equal"], checks)
    run.write_csv("notes.csv", ["item", "value"], notes)
    run.finish()
    say("RESULT", "er (reduced plan): %d table rows from %s; recomputed all-rows metrics equal at report precision: %d of %d"
        % (len(table), label, len([r for r in rec if r[5] is True]), len(rec)))


HANDLERS = {
    "retrieval": cmd_retrieval,
    "performance": cmd_performance,
    "factory": cmd_factory,
    "reproduce": cmd_reproduce,
    "ratings-sheet": cmd_ratings_sheet,
    "answers": cmd_answers,
    "graph": cmd_graph,
    "er": cmd_er,
}


# ---- driver -----------------------------------------------------------------------------------


def invoke(sub, argv, fixture_out=None):
    run = Run(sub, argv, fixture_out)
    try:
        HANDLERS[sub](run, argv)
    except ToolExit as e:
        say("ERROR", e.message)
        say("SUMMARY", "%s exit %d" % (sub, e.code))
        return e.code
    except Exception as e:  # a defect of this tool: never a result
        say("ERROR", "tool failure: %s: %s" % (type(e).__name__, e))
        say("SUMMARY", "%s exit %d" % (sub, EXIT_FAILURE))
        return EXIT_FAILURE
    say("SUMMARY", "%s exit 0 run %s" % (sub, run.run_id))
    return EXIT_OK


def compare_fixture(expected_dir, out_dir):
    exp = listing(expected_dir) if os.path.isdir(expected_dir) else {}
    got = listing(out_dir) if os.path.isdir(out_dir) else {}
    got.pop("run.json", None)
    problems = []
    for rel in sorted(set(exp) | set(got)):
        if rel not in got:
            problems.append("missing result file %s" % rel)
        elif rel not in exp:
            problems.append("unexpected result file %s" % rel)
        else:
            with open(exp[rel], "rb") as a, open(got[rel], "rb") as b:
                if a.read() != b.read():
                    problems.append("%s differs" % rel)
    return problems


def run_fixtures(sub):
    base = os.path.join(FIXTURE_ROOT, sub)
    if not os.path.isdir(base):
        raise ToolExit(EXIT_MISSING, "no fixtures for %s (%s)" % (sub, repo_rel(base)))
    cases = sorted(c for c in os.listdir(base) if os.path.isfile(os.path.join(base, c, "case.json")))
    if not cases:
        raise ToolExit(EXIT_MISSING, "no fixture cases under %s" % repo_rel(base))
    tmp = tempfile.mkdtemp(prefix="scholarchain-eval-%s-" % sub)
    real, repo = os.path.realpath(tmp), os.path.realpath(REPO_ROOT)
    if real == repo or real.startswith(repo + os.sep):
        raise ToolExit(EXIT_FAILURE, "the temporary folder is inside the repository; refusing to write")
    say("STEP", "fixture output folder %s (temporary; outside the repository)" % tmp)
    failed = 0
    for case in cases:
        cdir = os.path.join(base, case)
        with open(os.path.join(cdir, "case.json"), "rb") as fh:
            spec = json.loads(fh.read().decode("utf-8"))
        args = [a.replace("{case}", os.path.join(cdir, "input")) for a in spec["args"]]
        say("STEP", "fixture %s/%s: %s" % (sub, case, spec.get("description", "")))
        code = invoke(sub, args, fixture_out=os.path.join(tmp, case))
        problems = compare_fixture(os.path.join(cdir, "expected"), os.path.join(tmp, case))
        if code != spec.get("expectExit"):
            problems.insert(0, "exit %d (expected %s)" % (code, spec.get("expectExit")))
        if problems:
            failed += 1
            say("RESULT", "fixture %s/%s: fail (%s)" % (sub, case, "; ".join(problems)))
        else:
            say("RESULT", "fixture %s/%s: pass" % (sub, case))
    say("SUMMARY", "fixtures %s: %d of %d passed; outputs in %s" % (sub, len(cases) - failed, len(cases), tmp))
    return EXIT_OK if failed == 0 else EXIT_FAILURE


def main(argv):
    say("STEP", "tool %s sha256=%s path=%s" % (TOOL_VERSION, TOOL_SHA, repo_rel(SCRIPT_PATH)))
    if not argv or argv[0] not in HANDLERS:
        say("ERROR", "usage: evaluate.py SUBCOMMAND ARGS | SUBCOMMAND --fixtures (subcommands: %s)" % " ".join(sorted(HANDLERS)))
        say("SUMMARY", "exit %d" % EXIT_USAGE)
        return EXIT_USAGE
    sub, rest = argv[0], argv[1:]
    if "--fixtures" in rest:
        if rest != ["--fixtures"]:
            say("ERROR", "usage: --fixtures takes no other argument")
            say("SUMMARY", "exit %d" % EXIT_USAGE)
            return EXIT_USAGE
        try:
            return run_fixtures(sub)
        except ToolExit as e:
            say("ERROR", e.message)
            say("SUMMARY", "fixtures %s exit %d" % (sub, e.code))
            return e.code
    return invoke(sub, rest)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
