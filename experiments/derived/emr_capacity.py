"""EMR capacity over time (experiments A and B).

Owner: experiment-engineer. Python 3 standard library only.
Run: python3 -I experiments/derived/emr_capacity.py [--manifest ID ...] [--fixtures]
runtime: untested

Reads, per attempt folder (common.py has the shared layout):
  metrics/metric-data.json  AWS/ElasticMapReduce series per cluster (dimension JobFlowId):
                            CoreNodesRequested, CoreNodesRunning, TotalNodesRequested,
                            TotalNodesRunning, ContainerPending, AppsPending,
                            YARNMemoryAvailablePercentage (the Phase 15 manifests query the
                            Nodes metrics; TotalUnits* and CoreUnits* are not published for
                            instance-group clusters and were never queried, so they are not
                            named: Phase 16 AB-4 and J-03)
  emr-cluster.json          (written by experiments/collectors/ab-pipeline.sh; optional)
                            {"clusters": [{"clusterId", "createdEpoch", "readyEpoch",
                              "terminationRequestedEpoch", "endedEpoch", "finalState",
                              "stateChangeReasonCode"}]}
Writes into out/MANIFEST_ID/: capacity.csv (long format), attempts.csv (one row per
attempt and cluster), capacity-ATTEMPT-CLUSTER.svg, inputs.csv, missing.csv.

Classification (decision 11.6 and D15): a scale-out is a rise of the running series above
its first value; a scale-in is a later drop of the running series while the cluster was
still running (before its termination was requested, from emr-cluster.json). A drop at or
after termination is capacity released by the cluster's end (a lifecycle record, not a
scale-in event). Without the lifecycle record a drop is "undetermined". A flat series is
"no scale-out observed". Missing series stay missing; nothing is filled with 0.
"""

import os
import sys

sys.dont_write_bytecode = True
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import common  # noqa: E402

DEFAULT_MANIFESTS = ["p15-a-hadoop", "p15-a-spark", "p15-b-spark-burst"]
# The capacity series the manifests query (Phase 16 AB-4, J-03: never-queried Units series are
# not named, so missing.csv no longer lists them as "series not exported").
UNIT_METRICS = [
    "CoreNodesRequested",
    "CoreNodesRunning",
    "TotalNodesRequested",
    "TotalNodesRunning",
]
YARN_METRICS = ["ContainerPending", "AppsPending"]
MEMORY_METRICS = ["YARNMemoryAvailablePercentage"]
# CoreNodes first: the series Phase 15 classified with (the Units series never existed), so
# attempts.csv is unchanged.
RUNNING_PREFERENCE = ["CoreNodesRunning", "TotalNodesRunning"]
REQUESTED_PREFERENCE = ["CoreNodesRequested", "TotalNodesRequested"]


def lifecycle(attempt):
    data = attempt.json("emr-cluster.json")
    out = {}
    if isinstance(data, dict):
        for c in data.get("clusters") or []:
            if isinstance(c, dict) and c.get("clusterId"):
                out[c["clusterId"]] = c
    return out


def classify(points, term_requested):
    """Returns (first, max, max_epoch, scale_out, scale_in, release_note, events)."""
    if not points:
        return None, None, None, "missing", "missing", "", []
    first = points[0][1]
    vmax, tmax = first, points[0][0]
    events = []
    prev = first
    scale_out = "no scale-out observed"
    scale_in = "no"
    release = ""
    for e, v in points[1:]:
        if v > prev:
            events.append((e, "running %g->%g" % (prev, v), "scale-out"))
            scale_out = "yes"
        elif v < prev:
            if term_requested is None:
                events.append((e, "running %g->%g (undetermined)" % (prev, v), "event"))
                if scale_in != "yes":
                    scale_in = "undetermined (no lifecycle record)"
            elif e < term_requested:
                events.append((e, "running %g->%g" % (prev, v), "scale-in"))
                scale_in = "yes"
            else:
                release = "drop at or after termination request (release, not scale-in)"
        if v > vmax:
            vmax, tmax = v, e
        prev = v
    return first, vmax, tmax, scale_out, scale_in, release, events


def main():
    ctx = common.Context("emr", DEFAULT_MANIFESTS, "EMR capacity over time (A, B)")
    for manifest in ctx.manifests:
        long_rows, att_rows = [], []
        for att in ctx.attempts(manifest):
            series = [s for s in att.series() if s.namespace == "AWS/ElasticMapReduce"]
            life = lifecycle(att)
            clusters = sorted(set(s.dims.get("JobFlowId", "") for s in series) | set(life.keys()))
            if not clusters:
                ctx.note_missing(manifest, att.name, "AWS/ElasticMapReduce series", "no cluster series")
                att_rows.append([manifest, att.name, att.status(), att.exit_code(), common.utc(att.window()[0]),
                                 common.utc(att.window()[1]), "", "", "", "", "", "", "missing", "missing", "",
                                 "missing", att.metrics_source])
            for cid in clusters:
                cs = [s for s in series if s.dims.get("JobFlowId", "") == cid]
                for s in cs:
                    for e, v in s.points:
                        long_rows.append([manifest, att.name, cid, s.metric or "", s.stat or "", e, common.utc(e),
                                          common.fmt(v)])
                by_metric = {}
                for s in cs:
                    by_metric.setdefault(s.metric, s)
                for m in UNIT_METRICS + YARN_METRICS + MEMORY_METRICS:
                    if m not in by_metric:
                        ctx.note_missing(manifest, att.name, "AWS/ElasticMapReduce %s JobFlowId=%s" % (m, cid),
                                         "series not exported")
                running = next((by_metric[m] for m in RUNNING_PREFERENCE if m in by_metric and by_metric[m].points),
                               None)
                requested = next((by_metric[m] for m in REQUESTED_PREFERENCE
                                  if m in by_metric and by_metric[m].points), None)
                lc = life.get(cid, {})
                term = common.to_int(lc.get("terminationRequestedEpoch"))
                ended = common.to_int(lc.get("endedEpoch"))
                first, vmax, tmax, s_out, s_in, release, events = classify(
                    running.points if running else [], term)
                req_max = max(v for _, v in requested.points) if requested else None
                if ended is not None:
                    release = (release + "; " if release else "") + "cluster ended %s (lifecycle record)" % common.utc(
                        ended)
                if running is None:
                    verdict = "missing (no running-capacity series)"
                elif s_out == "yes" and s_in == "yes":
                    verdict = "scale-out and scale-in while running"
                elif s_out == "yes":
                    verdict = "scale-out observed; no scale-in while running"
                else:
                    verdict = "no scale-out observed"
                att_rows.append([manifest, att.name, att.status(), att.exit_code(), common.utc(att.window()[0]),
                                 common.utc(att.window()[1]), cid, running.metric if running else "",
                                 common.fmt(first), common.fmt(vmax), common.utc(tmax), common.fmt(req_max),
                                 s_out, s_in, release, verdict, att.metrics_source])
                for key, label in (("createdEpoch", "created"), ("readyEpoch", "ready"),
                                   ("terminationRequestedEpoch", "termination requested"), ("endedEpoch", "ended")):
                    e = common.to_int(lc.get(key))
                    if e is not None:
                        events.append((e, "cluster %s" % label, "lifecycle"))
                if not lc:
                    ctx.note_missing(manifest, att.name, "emr-cluster.json %s" % cid, "no lifecycle record")
                xs = [e for s in cs for e, _ in s.points] + [e for e, _, _ in events]
                w0, w1 = att.window()
                if w0 is not None:
                    xs += [w0 - 300, (w1 or w0) + 300]
                xr = (min(xs), max(xs)) if xs else (None, None)
                panels = [
                    {"title": "capacity (nodes)", "y_label": "nodes",
                     "series": [(m, by_metric[m].points if m in by_metric else []) for m in UNIT_METRICS]},
                    {"title": "YARN pending", "y_label": "count",
                     "series": [(m, by_metric[m].points if m in by_metric else []) for m in YARN_METRICS]},
                    {"title": "YARN memory available", "y_label": "percent",
                     "series": [(m, by_metric[m].points if m in by_metric else []) for m in MEMORY_METRICS]},
                ]
                path = os.path.join(ctx.out_dir(manifest), "capacity-%s-%s.svg" % (att.name, cid or "none"))
                common.time_chart(path, "%s %s cluster %s: %s" % (manifest, att.name, cid, verdict), panels, xr,
                                  events=sorted(events),
                                  notes=["status %s; metrics from %s" % (att.status(), att.metrics_source),
                                         "released capacity at cluster end is a lifecycle record, not a measured zero"],
                                  max_gap=150)
                ctx.written.append(path)
        ctx.write_csv(manifest, "capacity.csv",
                      ["manifest", "attempt", "cluster", "metric", "stat", "epoch_s", "utc", "value"],
                      sorted(long_rows))
        ctx.write_csv(manifest, "attempts.csv",
                      ["manifest", "attempt", "status", "exit_code", "action_start_utc", "action_end_utc", "cluster",
                       "running_metric", "running_first", "running_max", "running_max_utc", "requested_max",
                       "scale_out", "scale_in_while_running", "release", "classification", "metrics_source"],
                      att_rows)
    ctx.finish()


if __name__ == "__main__":
    main()
