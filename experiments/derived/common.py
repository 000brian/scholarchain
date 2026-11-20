"""Shared helpers of the Phase 15 derive scripts (experiments/derived/*.py).

Owner: experiment-engineer. Python 3 standard library only. Run by the human
(`python3 -I experiments/derived/NAME.py`), never by an agent.

runtime: untested

Rules every derive script follows (decision 11 item 2):
  - reads only experiments/raw/** (or, with --fixtures,
    experiments/derived/fixtures/raw/**); never writes there;
  - writes CSV tables and SVG plots into experiments/derived/out/MANIFEST_ID/,
    or, with --fixtures, only into a new temporary folder outside the
    repository whose path it prints;
  - prints every file it read with its sha256 (also written to inputs.csv)
    and every missing or empty series (also written to missing.csv);
  - never fills a gap with 0: a missing value is an empty CSV cell and a gap
    in a plot line, and missing series are named in the plot legend;
  - output is deterministic for the same raw files (no generation time in
    any output file), so D23 can re-run every script.

Raw layout read (INTERFACE: scripts/run-experiment.sh writes the first five,
the collectors and load-tests/query-load.sh the rest; each is optional and a
missing one is reported, never invented):
  raw/MANIFEST_ID/ATTEMPT_ID/run-record.json          final run record
  raw/MANIFEST_ID/ATTEMPT_ID/run-record.started.json  (only one: incomplete)
  raw/MANIFEST_ID/ATTEMPT_ID/manifest.json
  raw/MANIFEST_ID/ATTEMPT_ID/metrics/queries.json     logical queries (Id, Label, MetricStat)
  raw/MANIFEST_ID/ATTEMPT_ID/metrics/metric-data.json get-metric-data output
  raw/MANIFEST_ID/ATTEMPT_ID-metrics-UTCSTAMP/...     a later re-export (decision 9.6 f)
  plus the per-family files named in each script's docstring.
"""

import argparse
import csv
import hashlib
import json
import math
import os
import re
import sys
import tempfile
from datetime import datetime, timezone

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.dirname(os.path.dirname(SCRIPT_DIR))
RAW_ROOT = os.path.join(REPO_ROOT, "experiments", "raw")
FIXTURE_RAW_ROOT = os.path.join(SCRIPT_DIR, "fixtures", "raw")
OUT_ROOT = os.path.join(SCRIPT_DIR, "out")

REEXPORT_RE = re.compile(r"^(?P<attempt>.+)-metrics-(?P<stamp>[0-9]{8}T[0-9]{6}Z)$")
MANIFEST_ID_RE = re.compile(r"^[a-z0-9][a-z0-9-]{0,63}$")


class Context:
    """One script run: where it reads, where it writes, what it read and missed."""

    def __init__(self, family, default_manifests, description):
        parser = argparse.ArgumentParser(description=description)
        parser.add_argument(
            "--manifest",
            action="append",
            default=None,
            help="manifest ID to derive (repeatable); default: " + ", ".join(default_manifests),
        )
        parser.add_argument(
            "--fixtures",
            action="store_true",
            help="read experiments/derived/fixtures/raw and write only to a new temporary folder outside the repository",
        )
        args = parser.parse_args()
        self.family = family
        self.fixtures = args.fixtures
        self.manifests = args.manifest or list(default_manifests)
        for m in self.manifests:
            if not MANIFEST_ID_RE.match(m):
                fail(2, "not a manifest ID: %r" % m)
        self.raw_root = FIXTURE_RAW_ROOT if self.fixtures else RAW_ROOT
        if self.fixtures:
            base = tempfile.mkdtemp(prefix="scholarchain-derived-%s-" % family)
            real = os.path.realpath(base)
            if real == os.path.realpath(REPO_ROOT) or real.startswith(os.path.realpath(REPO_ROOT) + os.sep):
                fail(5, "the temporary folder is inside the repository; refusing to write")
            self.out_base = base
            print("OUTPUT-FOLDER: %s (temporary, outside the repository)" % base)
        else:
            self.out_base = OUT_ROOT
        self.inputs = []  # (relative path, sha256)
        self.missing = []  # (manifest, attempt, item, reason)
        self.written = []

    # ---- reading -------------------------------------------------------------

    def rel(self, path):
        return os.path.relpath(path, REPO_ROOT)

    def read_bytes(self, path):
        with open(path, "rb") as fh:
            data = fh.read()
        digest = hashlib.sha256(data).hexdigest()
        entry = (self.rel(path), digest)
        if entry not in self.inputs:
            self.inputs.append(entry)
        return data

    def read_json(self, path):
        if not os.path.isfile(path):
            return None
        try:
            return json.loads(self.read_bytes(path).decode("utf-8"))
        except (ValueError, UnicodeDecodeError):
            self.missing.append(("-", "-", self.rel(path), "unreadable JSON"))
            return None

    def read_csv(self, path):
        """Rows as dicts (all values strings), or None when the file is absent."""
        if not os.path.isfile(path):
            return None
        text = self.read_bytes(path).decode("utf-8")
        return list(csv.DictReader(text.splitlines()))

    def note_missing(self, manifest, attempt, item, reason):
        entry = (manifest, attempt, item, reason)
        if entry not in self.missing:
            self.missing.append(entry)

    # ---- attempts ------------------------------------------------------------

    def attempts(self, manifest):
        """Attempt folders of a manifest, oldest first, with their chosen metrics export."""
        root = os.path.join(self.raw_root, manifest)
        if not os.path.isdir(root):
            self.note_missing(manifest, "-", "raw folder", "no attempts under %s" % self.rel(root))
            return []
        names = sorted(n for n in os.listdir(root) if os.path.isdir(os.path.join(root, n)))
        reexports = {}
        originals = []
        for n in names:
            m = REEXPORT_RE.match(n)
            if m and m.group("attempt") in names:
                reexports.setdefault(m.group("attempt"), []).append(n)
            else:
                originals.append(n)
        result = []
        for n in originals:
            folder = os.path.join(root, n)
            result.append(Attempt(self, manifest, n, folder, sorted(reexports.get(n, []))))
        return result

    def out_dir(self, manifest):
        d = os.path.join(self.out_base, manifest)
        os.makedirs(d, exist_ok=True)
        return d

    # ---- writing -------------------------------------------------------------

    def write_csv(self, manifest, name, header, rows):
        path = os.path.join(self.out_dir(manifest), name)
        with open(path, "w", newline="", encoding="utf-8") as fh:
            w = csv.writer(fh, lineterminator="\n")
            w.writerow(header)
            for r in rows:
                w.writerow(["" if v is None else v for v in r])
        self.written.append(path)
        return path

    def write_text(self, manifest, name, text):
        path = os.path.join(self.out_dir(manifest), name)
        with open(path, "w", encoding="utf-8") as fh:
            fh.write(text)
        self.written.append(path)
        return path

    def finish(self):
        """Prints inputs and missing items and writes inputs.csv and missing.csv per manifest."""
        for m in self.manifests:
            ins = [(p, s) for (p, s) in self.inputs if ("/%s/" % m) in ("/" + p.replace(os.sep, "/"))]
            self.write_csv(m, "inputs.csv", ["path", "sha256"], sorted(ins))
            miss = sorted(x for x in self.missing if x[0] in (m, "-"))
            self.write_csv(m, "missing.csv", ["manifest", "attempt", "item", "reason"], miss)
        for p, s in sorted(self.inputs):
            print("READ: %s %s" % (s, p))
        if self.missing:
            for x in sorted(self.missing):
                print("MISSING: manifest=%s attempt=%s item=%s reason=%s" % x)
        else:
            print("MISSING: none")
        for p in self.written:
            print("WROTE: %s" % (p if self.fixtures else self.rel(p)))
        if self.fixtures:
            print("OUTPUT-FOLDER: %s" % self.out_base)


class Series:
    def __init__(self, sid, namespace, metric, stat, period, dims, points, status):
        self.sid = sid
        self.namespace = namespace
        self.metric = metric
        self.stat = stat
        self.period = period
        self.dims = dims  # dict name -> logical value
        self.points = points  # sorted list of (epoch_s, value)
        self.status = status

    def label(self):
        d = ",".join("%s=%s" % (k, self.dims[k]) for k in sorted(self.dims))
        return "%s %s %s %s" % (self.namespace, self.metric, self.stat, d)


class Attempt:
    def __init__(self, ctx, manifest, name, folder, reexports):
        self.ctx = ctx
        self.manifest = manifest
        self.name = name
        self.folder = folder
        self.reexports = reexports
        rec = ctx.read_json(os.path.join(folder, "run-record.json"))
        self.complete_record = rec is not None
        if rec is None:
            rec = ctx.read_json(os.path.join(folder, "run-record.started.json"))
            if rec is None:
                ctx.note_missing(manifest, name, "run-record.json", "no run record")
                rec = {}
            else:
                ctx.note_missing(manifest, name, "run-record.json", "started record only (incomplete attempt)")
        self.record = rec
        self._series = None
        self.metrics_source = None

    def status(self):
        s = self.record.get("status")
        if not self.complete_record:
            return "incomplete" if s else "unknown"
        return s or "unknown"

    def exit_code(self):
        return self.record.get("exit_code")

    def window(self):
        w = (self.record.get("action") or {}).get("window_epoch") or {}
        return w.get("start"), w.get("end")

    def path(self, name):
        return os.path.join(self.folder, name)

    def csv(self, name):
        rows = self.ctx.read_csv(self.path(name))
        if rows is None:
            self.ctx.note_missing(self.manifest, self.name, name, "file absent")
        return rows

    def json(self, name):
        data = self.ctx.read_json(self.path(name))
        if data is None:
            self.ctx.note_missing(self.manifest, self.name, name, "file absent or unreadable")
        return data

    def series(self):
        """All metric series of the chosen export; empty series are kept (points == [])."""
        if self._series is not None:
            return self._series
        folder = self.folder
        if self.reexports:
            folder = os.path.join(os.path.dirname(self.folder), self.reexports[-1])
        self.metrics_source = self.ctx.rel(os.path.join(folder, "metrics"))
        print("METRICS-EXPORT: %s/%s uses %s" % (self.manifest, self.name, self.metrics_source))
        queries = self.ctx.read_json(os.path.join(folder, "metrics", "queries.json"))
        data = self.ctx.read_json(os.path.join(folder, "metrics", "metric-data.json"))
        by_id = {}
        if isinstance(queries, list):
            for q in queries:
                if isinstance(q, dict) and "Id" in q:
                    by_id[q["Id"]] = q
        results = []
        if isinstance(data, dict):
            results = data.get("MetricDataResults") or []
        else:
            self.ctx.note_missing(self.manifest, self.name, "metrics/metric-data.json", "no metric export")
        out = []
        seen = set()
        for r in results:
            sid = r.get("Id", "")
            seen.add(sid)
            q = by_id.get(sid, {})
            ms = q.get("MetricStat") or {}
            met = ms.get("Metric") or {}
            dims = {d.get("Name"): d.get("Value") for d in met.get("Dimensions") or []}
            ns = met.get("Namespace")
            name = met.get("MetricName")
            stat = ms.get("Stat")
            if not ns or not name:
                ns, name, stat, dims = parse_label(r.get("Label", ""))
            pts = []
            for t, v in zip(r.get("Timestamps") or [], r.get("Values") or []):
                e = parse_time(t)
                if e is not None and isinstance(v, (int, float)):
                    pts.append((e, float(v)))
            pts.sort()
            s = Series(sid, ns, name, stat, ms.get("Period"), dims, pts, r.get("StatusCode"))
            out.append(s)
            if not pts:
                self.ctx.note_missing(self.manifest, self.name, s.label(), "empty series (no datapoints)")
            elif s.status and s.status != "Complete":
                self.ctx.note_missing(self.manifest, self.name, s.label(), "series status %s" % s.status)
        for sid, q in sorted(by_id.items()):
            if sid not in seen and q.get("Label"):
                self.ctx.note_missing(self.manifest, self.name, q.get("Label"), "query not returned")
        unresolved = ((self.record.get("metrics") or {}).get("unresolved")) or []
        for u in unresolved:
            self.ctx.note_missing(self.manifest, self.name, str(u), "not queried (unresolved placeholder)")
        self._series = out
        return out

    def find(self, metric, **dims):
        res = []
        for s in self.series():
            if s.metric != metric:
                continue
            if all(str(s.dims.get(k, "")).find(v) >= 0 for k, v in dims.items()):
                res.append(s)
        return res


def parse_label(label):
    """'NAMESPACE METRIC STAT dim=v,dim=v' (the runner's label form)."""
    parts = label.split(" ", 3)
    while len(parts) < 4:
        parts.append("")
    dims = {}
    for kv in parts[3].split(","):
        if "=" in kv:
            k, v = kv.split("=", 1)
            dims[k] = v
    return parts[0] or None, parts[1] or None, parts[2] or None, dims


def parse_time(text):
    """ISO 8601 (as the AWS CLI prints) or epoch seconds, to epoch seconds; None if unreadable."""
    if text is None:
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
    v = str(v).strip()
    if v == "" or v == "-":
        return None
    try:
        return int(float(v))
    except ValueError:
        return None


def to_float(v):
    if v is None:
        return None
    v = str(v).strip()
    if v == "" or v == "-" or v == "n/a":
        return None
    try:
        return float(v)
    except ValueError:
        return None


def utc(epoch):
    if epoch is None:
        return ""
    return datetime.fromtimestamp(int(epoch), tz=timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def pct(values, p):
    """Nearest-rank percentile (rank ceil(p/100 * n)); None for an empty list."""
    vals = sorted(v for v in values if v is not None)
    if not vals:
        return None
    r = int(math.ceil(p / 100.0 * len(vals)))
    r = min(max(r, 1), len(vals))
    return vals[r - 1]


def sample_note(n):
    if n < 1:
        return "n/a"
    if n < 20:
        return "few samples"
    return "ok"


def fmt(v, digits=3):
    if v is None:
        return ""
    if isinstance(v, float):
        return ("%." + str(digits) + "f") % v
    return str(v)


def fail(code, message):
    print("ERROR: %s" % message, file=sys.stderr)
    sys.exit(code)


# ---- SVG (written here; no plotting library) ---------------------------------

PALETTE = ["#1f77b4", "#d62728", "#2ca02c", "#9467bd", "#ff7f0e", "#8c564b", "#17becf", "#7f7f7f"]
EVENT_COLORS = {"scale-out": "#2ca02c", "scale-in": "#d62728", "lifecycle": "#7f7f7f", "event": "#9467bd"}


def esc(text):
    return (
        str(text).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace('"', "&quot;")
    )


def nice_ticks(lo, hi, n=5):
    if hi <= lo:
        hi = lo + 1.0
    raw = (hi - lo) / n
    mag = 10 ** math.floor(math.log10(raw))
    step = mag
    for m in (1, 2, 2.5, 5, 10):
        if raw <= m * mag:
            step = m * mag
            break
    start = math.floor(lo / step) * step
    ticks = []
    v = start
    # the last tick is at or above hi, so no value is drawn outside the frame
    while len(ticks) < 25:
        ticks.append(round(v, 10))
        if v >= hi - step * 0.001:
            break
        v += step
    return ticks


TIME_STEPS = [15, 30, 60, 120, 300, 600, 900, 1800, 3600, 7200, 10800, 21600, 43200, 86400]


def time_chart(path, title, panels, x_range, events=(), windows=(), notes=(), max_gap=180):
    """Stacked time panels on one UTC x axis.

    panels: list of dicts {"title", "y_label", "series": [(name, [(epoch, value)])]}; a series
            with no points is listed as missing in the legend; a gap longer than max_gap seconds
            breaks the line (a gap is never drawn as 0).
    events: (epoch, label, kind) drawn as vertical lines in every panel; kind scale-out,
            scale-in, lifecycle or event.
    windows: (start, end, label) shaded bands (for example load levels).
    """
    width, left, right, top, panel_h, gap_h = 1000, 70, 230, 50, 200, 45
    x0, x1 = x_range
    if x0 is None or x1 is None:
        x0, x1 = 0, 60
    if x1 <= x0:
        x1 = x0 + 60
    plot_w = width - left - right
    height = top + len(panels) * (panel_h + gap_h) + 40 + 14 * len(notes)

    def xp(e):
        return left + (e - x0) * plot_w / float(x1 - x0)

    out = [
        '<svg xmlns="http://www.w3.org/2000/svg" width="%d" height="%d" font-family="sans-serif" font-size="11">'
        % (width, height),
        '<rect x="0" y="0" width="%d" height="%d" fill="white"/>' % (width, height),
        '<text x="%d" y="20" font-size="14" font-weight="bold">%s</text>' % (left, esc(title)),
        '<text x="%d" y="36">x axis: time (UTC), %s to %s</text>' % (left, esc(utc(x0)), esc(utc(x1))),
    ]
    step = TIME_STEPS[-1]
    for s in TIME_STEPS:
        if (x1 - x0) / float(s) <= 10:
            step = s
            break
    for pi, panel in enumerate(panels):
        ptop = top + pi * (panel_h + gap_h)
        pbot = ptop + panel_h
        vals = [v for _, pts in panel["series"] for _, v in pts if v is not None]
        lo = min(vals + [0.0])
        hi = max(vals + [1.0])
        yt = nice_ticks(lo, hi * 1.05 if hi > 0 else 1.0)
        ylo, yhi = yt[0], yt[-1] if yt[-1] > yt[0] else yt[0] + 1

        def yp(v, ylo=ylo, yhi=yhi, ptop=ptop, pbot=pbot):
            return pbot - (v - ylo) * (pbot - ptop) / float(yhi - ylo)

        out.append(
            '<text x="%d" y="%d" font-weight="bold">%s</text>' % (left, ptop - 6, esc(panel.get("title", "")))
        )
        for (ws, we, wl) in windows:
            if ws is None or we is None:
                continue
            a, b = max(ws, x0), min(we, x1)
            if b > a:
                out.append(
                    '<rect x="%.1f" y="%d" width="%.1f" height="%d" fill="#eeeeee"/>'
                    % (xp(a), ptop, xp(b) - xp(a), panel_h)
                )
                out.append('<text x="%.1f" y="%d" fill="#555">%s</text>' % (xp(a) + 2, ptop + 12, esc(wl)))
        out.append(
            '<rect x="%d" y="%d" width="%d" height="%d" fill="none" stroke="#333"/>'
            % (left, ptop, plot_w, panel_h)
        )
        for t in yt:
            y = yp(t)
            out.append('<line x1="%d" y1="%.1f" x2="%d" y2="%.1f" stroke="#ddd"/>' % (left, y, left + plot_w, y))
            out.append('<text x="%d" y="%.1f" text-anchor="end">%s</text>' % (left - 4, y + 4, esc(fmt_tick(t))))
        out.append(
            '<text x="14" y="%d" transform="rotate(-90 14 %d)" text-anchor="middle">%s</text>'
            % ((ptop + pbot) // 2, (ptop + pbot) // 2, esc(panel.get("y_label", "")))
        )
        t = int(math.ceil(x0 / float(step)) * step)
        while t <= x1:
            x = xp(t)
            out.append('<line x1="%.1f" y1="%d" x2="%.1f" y2="%d" stroke="#eee"/>' % (x, ptop, x, pbot))
            lab = datetime.fromtimestamp(t, tz=timezone.utc).strftime("%H:%M" if step >= 60 else "%H:%M:%S")
            out.append('<text x="%.1f" y="%d" text-anchor="middle">%s</text>' % (x, pbot + 14, lab))
            t += step
        for (ee, el, ek) in events:
            if ee is None or ee < x0 or ee > x1:
                continue
            c = EVENT_COLORS.get(ek, "#9467bd")
            x = xp(ee)
            out.append(
                '<line x1="%.1f" y1="%d" x2="%.1f" y2="%d" stroke="%s" stroke-dasharray="4,3"/>'
                % (x, ptop, x, pbot, c)
            )
            if pi == 0:
                out.append(
                    '<text x="%.1f" y="%d" fill="%s" font-size="9" transform="rotate(-30 %.1f %d)">%s</text>'
                    % (x + 2, ptop + 22, c, x + 2, ptop + 22, esc(el))
                )
        ly = ptop + 4
        for si, (name, pts) in enumerate(panel["series"]):
            color = PALETTE[si % len(PALETTE)]
            ly += 14
            if not pts:
                out.append(
                    '<text x="%d" y="%d" fill="#999">%s: missing (no data)</text>' % (left + plot_w + 10, ly, esc(name))
                )
                continue
            out.append(
                '<line x1="%d" y1="%d" x2="%d" y2="%d" stroke="%s" stroke-width="2"/>'
                % (left + plot_w + 10, ly - 4, left + plot_w + 28, ly - 4, color)
            )
            out.append('<text x="%d" y="%d">%s</text>' % (left + plot_w + 32, ly, esc(name)))
            segs, cur, prev = [], [], None
            for e, v in pts:
                if prev is not None and e - prev > max_gap:
                    segs.append(cur)
                    cur = []
                cur.append((e, v))
                prev = e
            segs.append(cur)
            for seg in segs:
                if len(seg) == 1:
                    e, v = seg[0]
                    out.append('<circle cx="%.1f" cy="%.1f" r="2.5" fill="%s"/>' % (xp(e), yp(v), color))
                else:
                    pts_s = " ".join("%.1f,%.1f" % (xp(e), yp(v)) for e, v in seg)
                    out.append(
                        '<polyline points="%s" fill="none" stroke="%s" stroke-width="1.5"/>' % (pts_s, color)
                    )
    ny = top + len(panels) * (panel_h + gap_h) + 10
    legend = "events: green dashed = scale-out, red dashed = scale-in, gray dashed = lifecycle record"
    out.append('<text x="%d" y="%d" fill="#555">%s</text>' % (left, ny, esc(legend)))
    for i, n in enumerate(notes):
        out.append('<text x="%d" y="%d" fill="#555">%s</text>' % (left, ny + 14 * (i + 1), esc(n)))
    out.append("</svg>")
    with open(path, "w", encoding="utf-8") as fh:
        fh.write("\n".join(out) + "\n")


def category_chart(path, title, categories, series, y_label, x_label, notes=()):
    """Lines over categories (load levels, K values); a None value is not drawn and is listed."""
    width, left, right, top, plot_h = 900, 70, 230, 50, 300
    plot_w = width - left - right
    height = top + plot_h + 60 + 14 * len(notes)
    vals = [v for _, vs in series for v in vs if v is not None]
    yt = nice_ticks(min(vals + [0.0]), max(vals + [1.0]) * 1.05)
    ylo, yhi = yt[0], yt[-1] if yt[-1] > yt[0] else yt[0] + 1
    n = max(len(categories), 1)

    def xp(i):
        return left + (i + 0.5) * plot_w / float(n)

    def yp(v):
        return top + plot_h - (v - ylo) * plot_h / float(yhi - ylo)

    out = [
        '<svg xmlns="http://www.w3.org/2000/svg" width="%d" height="%d" font-family="sans-serif" font-size="11">'
        % (width, height),
        '<rect x="0" y="0" width="%d" height="%d" fill="white"/>' % (width, height),
        '<text x="%d" y="20" font-size="14" font-weight="bold">%s</text>' % (left, esc(title)),
        '<rect x="%d" y="%d" width="%d" height="%d" fill="none" stroke="#333"/>' % (left, top, plot_w, plot_h),
    ]
    for t in yt:
        y = yp(t)
        out.append('<line x1="%d" y1="%.1f" x2="%d" y2="%.1f" stroke="#ddd"/>' % (left, y, left + plot_w, y))
        out.append('<text x="%d" y="%.1f" text-anchor="end">%s</text>' % (left - 4, y + 4, esc(fmt_tick(t))))
    for i, c in enumerate(categories):
        out.append('<text x="%.1f" y="%d" text-anchor="middle">%s</text>' % (xp(i), top + plot_h + 14, esc(c)))
    out.append(
        '<text x="%d" y="%d" text-anchor="middle">%s</text>' % (left + plot_w // 2, top + plot_h + 30, esc(x_label))
    )
    out.append(
        '<text x="14" y="%d" transform="rotate(-90 14 %d)" text-anchor="middle">%s</text>'
        % (top + plot_h // 2, top + plot_h // 2, esc(y_label))
    )
    ly = top
    for si, (name, vs) in enumerate(series):
        color = PALETTE[si % len(PALETTE)]
        ly += 14
        absent = [categories[i] for i, v in enumerate(vs) if v is None]
        label = name + ("" if not absent else " (n/a at %s)" % ", ".join(str(a) for a in absent))
        out.append(
            '<line x1="%d" y1="%d" x2="%d" y2="%d" stroke="%s" stroke-width="2"/>'
            % (left + plot_w + 10, ly - 4, left + plot_w + 28, ly - 4, color)
        )
        out.append('<text x="%d" y="%d">%s</text>' % (left + plot_w + 32, ly, esc(label)))
        seg = []
        for i, v in enumerate(vs):
            if v is None:
                if len(seg) > 1:
                    out.append(
                        '<polyline points="%s" fill="none" stroke="%s" stroke-width="1.5"/>' % (" ".join(seg), color)
                    )
                seg = []
                continue
            seg.append("%.1f,%.1f" % (xp(i), yp(v)))
            out.append('<circle cx="%.1f" cy="%.1f" r="3" fill="%s"/>' % (xp(i), yp(v), color))
        if len(seg) > 1:
            out.append('<polyline points="%s" fill="none" stroke="%s" stroke-width="1.5"/>' % (" ".join(seg), color))
    for i, nt in enumerate(notes):
        out.append('<text x="%d" y="%d" fill="#555">%s</text>' % (left, top + plot_h + 48 + 14 * i, esc(nt)))
    out.append("</svg>")
    with open(path, "w", encoding="utf-8") as fh:
        fh.write("\n".join(out) + "\n")


def fmt_tick(v):
    if abs(v - round(v)) < 1e-9:
        return str(int(round(v)))
    return ("%.3f" % v).rstrip("0").rstrip(".")


def step_events(samples, key, label):
    """Scale events from consecutive samples of one integer field: (epoch, label, kind)."""
    events = []
    prev = None
    for e, v in samples:
        if v is None:
            continue
        if prev is not None and v != prev:
            kind = "scale-out" if v > prev else "scale-in"
            events.append((e, "%s %s %d->%d" % (label, key, prev, v), kind))
        prev = v
    return events
