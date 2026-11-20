"""Latency percentiles and class counts per load level (experiment D).

Owner: experiment-engineer. Python 3 standard library only.
Run: python3 -I experiments/derived/load_levels.py [--manifest ID ...] [--fixtures]
runtime: untested

Reads, per attempt folder (written by load-tests/query-load.sh; its usage text has the
columns; common.py has the shared layout):
  load-requests.csv   one row per request (the primary source of latency and throughput)
  load-levels.csv     the tool's own per-level summary (used only for the level windows)
  load-explain.csv    (query mode) per-stage latencies from GET /v1/explain
  load-clock.csv      GET /healthz at start and end with the HTTP Date header
  id-check.csv        (read mode) one request per listed ID before the levels
  service-samples.csv (experiments/collectors/d-load.sh) epoch_s,service,desired,running,pending
  metrics/metric-data.json  AWS/ApplicationELB, AWS/ECS, AWS/DynamoDB, AWS/Neptune series
Writes into out/MANIFEST_ID/: levels.csv, explain-stages.csv, explain-coverage.csv,
clock.csv, tasks.csv, cloudwatch-periods.csv, id-check-summary.csv, and per attempt
latency-ATTEMPT.svg, classes-ATTEMPT.svg, tasks-ATTEMPT.svg, plus inputs.csv, missing.csv.

Rules: the denominator of every class share and of the error rate is the attempted
requests of the level; p50, p95, p99 (nearest rank) are given twice, over all attempted
requests with a measured time (failures at their measured time) and over the success
population (answered for query, ok for read); fewer than 1 value prints n/a, fewer than
20 is labelled "few samples". A 60 s CloudWatch period that is not wholly inside one
level window is marked boundary (or pause) and is not attributed to a level. The clock
offset is the HTTP Date (1 s resolution) minus the local midpoint, with an uncertainty
of 1 s plus half the local round trip.
"""

import os
import sys

sys.dont_write_bytecode = True
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import common  # noqa: E402

DEFAULT_MANIFESTS = ["p15-d-query-load", "p15-d-read-load"]
CLASSES = {
    "query": ["answered", "abstained", "throttled", "overloaded", "timeout", "other-4xx", "other-5xx",
              "client-error", "unclassified"],
    "read": ["ok", "not-found", "throttled", "overloaded", "timeout", "other-4xx", "other-5xx", "client-error",
             "unclassified"],
}
SUCCESS = {"query": "answered", "read": "ok"}
STAGES = ["latency", "planner", "plannerFallback", "resolver", "metrics", "paths", "community", "semantic",
          "evidence", "identity", "synthesis", "synthesisFallback", "verification"]
# The spec D items and the explain stage keys that cover them (QueryWorkflow.scala:176-231).
COVERAGE = [
    ("planner", "planner (+ plannerFallback when the model planner fell back)", "recorded"),
    ("graph", "resolver, metrics, paths, community (stores reached through the graph templates)", "recorded"),
    ("vector", "semantic (Voyage query embedding and S3 Vectors query together)", "recorded"),
    ("LLM", "synthesis (+ planner when the model planner ran; synthesisFallback)", "recorded"),
    ("verification", "verification", "recorded"),
    ("retries", "none: explain carries logical model calls, not HTTP retries (ExplainView.AttemptsNote)", "missing"),
    ("Neptune latency separate from the graph stage", "none", "missing"),
    ("Voyage latency separate from the S3 Vectors query", "none", "missing"),
]


def level_windows(att, reqs):
    """level -> (start, end): from the tool's load-levels.csv, else from the requests."""
    out = {}
    tool = att.csv("load-levels.csv") or []
    for r in tool:
        lv = common.to_int(r.get("level"))
        s, e = common.to_int(r.get("window_start_epoch_s")), common.to_int(r.get("window_end_epoch_s"))
        if lv is not None and s is not None and e is not None:
            out[lv] = (s, e)
    derived = {}
    for r in reqs:
        lv = common.to_int(r.get("level"))
        if lv is None or lv in out:
            continue
        s, e = common.to_int(r.get("start_epoch_s")), common.to_int(r.get("end_epoch_s"))
        if s is None or e is None:
            continue
        lo, hi = derived.get(lv, (s, e))
        derived[lv] = (min(lo, s), max(hi, e))
    for lv, w in derived.items():
        out.setdefault(lv, w)
    return out


def main():
    ctx = common.Context("load", DEFAULT_MANIFESTS, "latency and class counts per load level (D)")
    for manifest in ctx.manifests:
        lv_rows, st_rows, clk_rows, task_rows, cw_rows, idc_rows = [], [], [], [], [], []
        mode_seen = set()
        for att in ctx.attempts(manifest):
            reqs = att.csv("load-requests.csv") or []
            mode = (reqs[0].get("mode") if reqs else None) or ("query" if "query" in manifest else "read")
            mode_seen.add(mode)
            classes = CLASSES.get(mode, CLASSES["read"])
            succ = SUCCESS.get(mode, "ok")
            windows = level_windows(att, reqs)
            levels = sorted(set(common.to_int(r.get("level")) for r in reqs if common.to_int(r.get("level"))))
            cats, p_all, p_succ, cls_counts = [], {50: [], 95: [], 99: []}, {50: [], 95: [], 99: []}, {}
            for lv in levels:
                rows = [r for r in reqs if common.to_int(r.get("level")) == lv]
                clients = common.to_int(rows[0].get("clients"))
                attempted = len(rows)
                completed = sum(1 for r in rows if r.get("curl_exit") == "0" and r.get("http_status") != "000")
                t_all = [common.to_float(r.get("time_total_s")) for r in rows]
                t_suc = [common.to_float(r.get("time_total_s")) for r in rows if r.get("class") == succ]
                n_all = len([t for t in t_all if t is not None])
                n_suc = len([t for t in t_suc if t is not None])
                ws, we = windows.get(lv, (None, None))
                wall = (we - ws) if (ws is not None and we is not None and we > ws) else None
                counts = [sum(1 for r in rows if r.get("class") == c) for c in classes]
                other = attempted - sum(counts)
                n_success = sum(1 for r in rows if r.get("class") == succ)
                err_rate = (attempted - n_success) / float(attempted) if attempted else None
                pa = [common.pct(t_all, p) for p in (50, 95, 99)]
                ps = [common.pct(t_suc, p) for p in (50, 95, 99)]
                lv_rows.append([manifest, att.name, mode, lv, clients, common.utc(ws), common.utc(we),
                                "" if wall is None else wall, attempted, completed,
                                common.fmt(completed / float(wall)) if wall else "",
                                attempted - n_all] + counts + [other, common.fmt(err_rate)]
                               + [common.fmt(x) for x in pa] + [n_all, common.sample_note(n_all)]
                               + [common.fmt(x) for x in ps] + [n_suc, common.sample_note(n_suc)])
                cats.append("L%d: %s clients" % (lv, clients))
                for i, p in enumerate((50, 95, 99)):
                    p_all[p].append(pa[i])
                    p_succ[p].append(ps[i])
                for c, n in zip(classes, counts):
                    cls_counts.setdefault(c, []).append(n)
            if not reqs:
                ctx.note_missing(manifest, att.name, "load-requests.csv rows", "no request rows")
            # explain stages (query mode)
            if mode == "query":
                ex = att.csv("load-explain.csv") or []
                for lv in levels:
                    rows = [r for r in ex if common.to_int(r.get("level")) == lv]
                    for st in STAGES:
                        vals = [common.to_float(r.get("%s_ms" % st)) for r in rows]
                        present = [v for v in vals if v is not None]
                        st_rows.append([manifest, att.name, lv, st, len(rows), len(present), len(rows) - len(present),
                                        common.fmt(common.pct(present, 50)), common.fmt(common.pct(present, 95)),
                                        common.sample_note(len(present))])
            # clock
            for r in att.csv("load-clock.csv") or []:
                s, rcv, d = (common.to_int(r.get("local_send_epoch_s")), common.to_int(r.get("local_recv_epoch_s")),
                             common.to_int(r.get("http_date_epoch_s")))
                if s is None or rcv is None or d is None:
                    clk_rows.append([manifest, att.name, r.get("phase"), s, rcv, d, "", "", "HTTP Date missing"])
                    continue
                off = (d + 0.5) - (s + rcv) / 2.0
                unc = 1.0 + (rcv - s) / 2.0
                clk_rows.append([manifest, att.name, r.get("phase"), s, rcv, d, common.fmt(off, 1),
                                 common.fmt(unc, 1), "server minus laptop; +-uncertainty"])
            # tasks per level window
            svc = att.csv("service-samples.csv") or []
            services = sorted(set(r.get("service") or "" for r in svc))
            for lv in levels:
                ws, we = windows.get(lv, (None, None))
                for sv in services:
                    inwin = [r for r in svc if r.get("service") == sv and ws is not None
                             and ws <= (common.to_int(r.get("epoch_s")) or -1) <= we]
                    des = [common.to_int(r.get("desired")) for r in inwin]
                    run = [common.to_int(r.get("running")) for r in inwin]
                    des = [x for x in des if x is not None]
                    run = [x for x in run if x is not None]
                    task_rows.append([manifest, att.name, lv, sv, len(inwin), min(des) if des else "",
                                      max(des) if des else "", min(run) if run else "", max(run) if run else ""])
            # CloudWatch periods attributed to levels
            for s in att.series():
                for e, v in s.points:
                    period = int(s.period or 60)
                    attr = "outside"
                    for lv, (ws, we) in sorted(windows.items()):
                        if ws <= e and e + period <= we + 1:
                            attr = "level %d" % lv
                            break
                        if e < we and e + period > ws:
                            attr = "boundary"
                            break
                    if attr == "outside" and windows:
                        first = min(w[0] for w in windows.values())
                        last = max(w[1] for w in windows.values())
                        if first <= e <= last:
                            attr = "pause"
                    cw_rows.append([manifest, att.name, s.label(), common.utc(e), common.fmt(v), attr])
            # ID check (read mode)
            if mode == "read":
                idc = att.csv("id-check.csv") or []
                for c in classes:
                    idc_rows.append([manifest, att.name, c, sum(1 for r in idc if r.get("class") == c)])
            # plots
            if cats:
                path = os.path.join(ctx.out_dir(manifest), "latency-%s.svg" % att.name)
                common.category_chart(
                    path, "%s %s: latency per level (nearest-rank percentiles)" % (manifest, att.name), cats,
                    [("p50 all", p_all[50]), ("p95 all", p_all[95]), ("p99 all", p_all[99]),
                     ("p50 %s" % succ, p_succ[50]), ("p95 %s" % succ, p_succ[95]), ("p99 %s" % succ, p_succ[99])],
                    "seconds (curl time_total)", "load level",
                    notes=["all = every attempted request with a measured time; n/a = no value",
                           "fewer than 20 values per population: few samples (levels.csv)"])
                ctx.written.append(path)
                path = os.path.join(ctx.out_dir(manifest), "classes-%s.svg" % att.name)
                common.category_chart(path, "%s %s: response classes per level" % (manifest, att.name), cats,
                                      [(c, cls_counts.get(c, [])) for c in classes], "requests", "load level",
                                      notes=["denominator of every share: attempted requests of the level"])
                ctx.written.append(path)
            series = []
            events = []
            for sv in services:
                pts_d = sorted((common.to_int(r.get("epoch_s")), float(common.to_int(r.get("desired"))))
                               for r in svc if r.get("service") == sv and common.to_int(r.get("epoch_s")) is not None
                               and common.to_int(r.get("desired")) is not None)
                pts_r = sorted((common.to_int(r.get("epoch_s")), float(common.to_int(r.get("running"))))
                               for r in svc if r.get("service") == sv and common.to_int(r.get("epoch_s")) is not None
                               and common.to_int(r.get("running")) is not None)
                series += [("%s desired" % sv, pts_d), ("%s running" % sv, pts_r)]
                events += common.step_events([(e, int(v)) for e, v in pts_d], "desired", sv)
            alb = [s for s in att.series() if s.namespace == "AWS/ApplicationELB" and s.metric == "TargetResponseTime"]
            cpu = [s for s in att.series() if s.namespace == "AWS/ECS" and s.metric == "CPUUtilization"]
            mem = [s for s in att.series() if s.namespace == "AWS/ECS" and s.metric == "MemoryUtilization"]
            panels = [{"title": "service tasks (describe-services samples)", "y_label": "tasks", "series": series},
                      {"title": "ALB TargetResponseTime (CloudWatch)", "y_label": "seconds",
                       "series": [(s.label().replace("AWS/ApplicationELB TargetResponseTime ", ""), s.points)
                                  for s in alb]},
                      {"title": "ECS CPUUtilization (CloudWatch)", "y_label": "percent",
                       "series": [(s.label().replace("AWS/ECS CPUUtilization ", ""), s.points) for s in cpu]},
                      {"title": "ECS MemoryUtilization (CloudWatch)", "y_label": "percent",
                       "series": [(s.label().replace("AWS/ECS MemoryUtilization ", ""), s.points) for s in mem]}]
            xs = [w for pair in windows.values() for w in pair]
            xs += [e for _, pts in series for e, _ in pts]
            xr = (min(xs) - 120, max(xs) + 120) if xs else (None, None)
            path = os.path.join(ctx.out_dir(manifest), "tasks-%s.svg" % att.name)
            common.time_chart(path, "%s %s: tasks and CloudWatch series over the levels" % (manifest, att.name),
                              panels, xr, events=sorted(events),
                              windows=[(ws, we, "level %d" % lv) for lv, (ws, we) in sorted(windows.items())],
                              notes=["shaded bands: level windows; scale events from desired-count changes",
                                     "metrics from %s" % att.metrics_source], max_gap=120)
            ctx.written.append(path)
        mode = sorted(mode_seen)[0] if mode_seen else ("query" if "query" in manifest else "read")
        classes = CLASSES.get(mode, CLASSES["read"])
        ctx.write_csv(manifest, "levels.csv",
                      ["manifest", "attempt", "mode", "level", "clients", "window_start_utc", "window_end_utc",
                       "wall_s", "attempted", "completed", "completed_per_s", "untimed"]
                      + ["n_%s" % c for c in classes] + ["n_other_class", "error_rate"]
                      + ["p50_all_s", "p95_all_s", "p99_all_s", "n_all_timed", "note_all"]
                      + ["p50_success_s", "p95_success_s", "p99_success_s", "n_success_timed", "note_success"],
                      lv_rows)
        ctx.write_csv(manifest, "explain-stages.csv",
                      ["manifest", "attempt", "level", "stage", "explain_rows", "present", "missing", "p50_ms",
                       "p95_ms", "note"], st_rows)
        ctx.write_csv(manifest, "explain-coverage.csv", ["spec_item", "explain_stage_keys", "status"],
                      [list(c) for c in COVERAGE])
        ctx.write_csv(manifest, "clock.csv",
                      ["manifest", "attempt", "phase", "local_send_epoch_s", "local_recv_epoch_s",
                       "http_date_epoch_s", "offset_s", "uncertainty_s", "note"], clk_rows)
        ctx.write_csv(manifest, "tasks.csv",
                      ["manifest", "attempt", "level", "service", "samples", "desired_min", "desired_max",
                       "running_min", "running_max"], task_rows)
        ctx.write_csv(manifest, "cloudwatch-periods.csv",
                      ["manifest", "attempt", "series", "period_start_utc", "value", "attribution"],
                      sorted(cw_rows, key=lambda r: (r[1], r[2], r[3])))
        ctx.write_csv(manifest, "id-check-summary.csv", ["manifest", "attempt", "class", "ids"], idc_rows)
    ctx.finish()


if __name__ == "__main__":
    main()
