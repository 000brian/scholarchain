"""Failure timeline (experiment F: poison message, sequential replay, API idempotency).

Owner: experiment-engineer. Python 3 standard library only.
Run: python3 -I experiments/derived/failure_timeline.py [--manifest ID ...] [--fixtures]
runtime: untested

Reads, per attempt folder (common.py has the shared layout; experiments/collectors/
f-poison-replay.sh of step 6b writes the CSV files with extracted fields only):
  events.csv           epoch_s,part,source,event,task_id,outcome,code
                       part: poison, replay or api; source: collector, worker-log,
                       completion-record, sqs or api
  queue-samples.csv    epoch_s,queue,visible,in_flight
  alarm-samples.csv    epoch_s,alarm,state   (state OK, ALARM or INSUFFICIENT_DATA)
  service-samples.csv  epoch_s,service,desired,running,pending
  incident-F.json      the incident record (only its top-level keys are listed here)
  metrics/metric-data.json  AWS/SQS series
Writes into out/MANIFEST_ID/: timeline.csv, parts.csv, incident-keys.csv,
timeline-ATTEMPT.svg, inputs.csv, missing.csv.

Evidence rule (decision 11.4 F): "processed" and "replayed" of the replay part count only
from source worker-log or completion-record; queue counts alone never prove a replay, so
without those sources the part is "unverified".
"""

import os
import sys

sys.dont_write_bytecode = True
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import common  # noqa: E402

DEFAULT_MANIFESTS = ["p15-f-poison-replay"]
PARTS = ["poison", "replay", "api"]
PROOF_SOURCES = ("worker-log", "completion-record")


def main():
    ctx = common.Context("failure", DEFAULT_MANIFESTS, "failure timeline (F)")
    for manifest in ctx.manifests:
        tl_rows, part_rows, key_rows = [], [], []
        for att in ctx.attempts(manifest):
            ev = att.csv("events.csv") or []
            qs = att.csv("queue-samples.csv") or []
            al = att.csv("alarm-samples.csv") or []
            svc = att.csv("service-samples.csv") or []
            inc = att.json("incident-F.json")
            events = []
            for r in ev:
                e = common.to_int(r.get("epoch_s"))
                tl_rows.append([manifest, att.name, "" if e is None else e, common.utc(e), "event", r.get("part", ""),
                                r.get("source", ""), r.get("event", ""), r.get("task_id", ""), r.get("outcome", ""),
                                r.get("code", "")])
                if e is not None:
                    events.append((e, "%s %s %s" % (r.get("part", ""), r.get("event", ""), r.get("outcome", "")),
                                   "event"))
            for r in al:
                e = common.to_int(r.get("epoch_s"))
                tl_rows.append([manifest, att.name, "" if e is None else e, common.utc(e), "alarm", "",
                                "cloudwatch", r.get("alarm", ""), "", r.get("state", ""), ""])
            for part in PARTS:
                rows = [r for r in ev if r.get("part") == part]
                times = [common.to_int(r.get("epoch_s")) for r in rows if common.to_int(r.get("epoch_s")) is not None]
                outcomes = sorted(set(r.get("outcome", "") for r in rows if r.get("outcome")))
                if part == "replay":
                    proven = set(r.get("outcome") for r in rows if r.get("source") in PROOF_SOURCES)
                    if "processed" in proven and "replayed" in proven:
                        verdict = "processed then replayed (worker log or completion record)"
                    elif rows:
                        verdict = "unverified (no worker-log or completion-record source for both outcomes)"
                    else:
                        verdict = "missing"
                elif part == "poison":
                    verdict = "dlq-received" if "dlq-received" in outcomes else ("seen" if rows else "missing")
                else:
                    verdict = "seen" if rows else "missing"
                if not rows:
                    ctx.note_missing(manifest, att.name, "events.csv part %s" % part, "no events")
                part_rows.append([manifest, att.name, att.status(), part, len(rows),
                                  common.utc(min(times)) if times else "", common.utc(max(times)) if times else "",
                                  ";".join(outcomes), verdict])
            if isinstance(inc, dict):
                for k in sorted(inc.keys()):
                    key_rows.append([manifest, att.name, k, "present"])
            qser = {}
            for r in qs:
                e, q = common.to_int(r.get("epoch_s")), r.get("queue") or ""
                for f in ("visible", "in_flight"):
                    v = common.to_int(r.get(f))
                    if e is not None and v is not None:
                        qser.setdefault("%s %s" % (q, f), []).append((e, float(v)))
            for k in qser:
                qser[k].sort()
            alarm_pts = {}
            for r in al:
                e = common.to_int(r.get("epoch_s"))
                st = r.get("state")
                if e is None or st not in ("OK", "ALARM"):
                    continue
                alarm_pts.setdefault(r.get("alarm") or "alarm", []).append((e, 1.0 if st == "ALARM" else 0.0))
            worker = sorted((common.to_int(r.get("epoch_s")), float(common.to_int(r.get("running"))))
                            for r in svc if "worker" in (r.get("service") or "")
                            and common.to_int(r.get("epoch_s")) is not None
                            and common.to_int(r.get("running")) is not None)
            events += common.step_events([(e, int(v)) for e, v in worker], "running", "worker")
            panels = [
                {"title": "queues (collector samples)", "y_label": "messages",
                 "series": [(k, qser[k]) for k in sorted(qser)] or [("queue samples", [])]},
                {"title": "alarm state (1 = ALARM, 0 = OK; other states not drawn)", "y_label": "state",
                 "series": [(k, sorted(v)) for k, v in sorted(alarm_pts.items())] or [("alarm samples", [])]},
                {"title": "worker running tasks (samples)", "y_label": "tasks", "series": [("running", worker)]},
            ]
            xs = [e for _, pts in qser.items() for e, _ in pts] + [e for e, _, _ in events]
            w0, w1 = att.window()
            if w0 is not None:
                xs += [w0, w1 or w0]
            xr = (min(xs), max(xs)) if xs else (None, None)
            path = os.path.join(ctx.out_dir(manifest), "timeline-%s.svg" % att.name)
            common.time_chart(path, "%s %s: failure timeline" % (manifest, att.name), panels, xr,
                              events=sorted(events), notes=["status %s; purple dashed = collector events"
                                                            % att.status()], max_gap=60)
            ctx.written.append(path)
        ctx.write_csv(manifest, "timeline.csv",
                      ["manifest", "attempt", "epoch_s", "utc", "kind", "part", "source", "event", "task_id",
                       "outcome", "code"],
                      sorted(tl_rows, key=lambda r: (r[1], str(r[2]), r[4], r[7])))
        ctx.write_csv(manifest, "parts.csv",
                      ["manifest", "attempt", "status", "part", "events", "first_utc", "last_utc", "outcomes",
                       "verdict"], part_rows)
        ctx.write_csv(manifest, "incident-keys.csv", ["manifest", "attempt", "key", "presence"], key_rows)
    ctx.finish()


if __name__ == "__main__":
    main()
