"""Queue depth with worker desired and running tasks over time (experiments C and F).

Owner: experiment-engineer. Python 3 standard library only.
Run: python3 -I experiments/derived/queue_worker.py [--manifest ID ...] [--fixtures]
runtime: untested

Reads, per attempt folder (common.py has the shared layout; the collectors of 6b write
the CSV files, every cell an extracted field, an empty cell meaning missing):
  service-samples.csv     epoch_s,service,desired,running,pending   (describe-services, every 15 s)
  queue-samples.csv       epoch_s,queue,visible,in_flight           (queue: embedding,
                          embedding-dlq, job, job-dlq; get-queue-attributes, every 15 s)
  scaling-activities.csv  start_epoch_s,end_epoch_s,service,direction,status_code
                          (direction out, in or unknown)
  metrics/metric-data.json AWS/SQS series by QueueName (visible, not visible, oldest age,
                          sent, received, deleted)
Writes into out/MANIFEST_ID/: samples.csv (long format), observed.csv (one row per
attempt), queue-ATTEMPT.svg, inputs.csv, missing.csv.

Task counts come from the service samples and the scaling activities only (Container
Insights is off; CPUUtilization sample counts are never used as a task count). The gate
of C (decision 13 D12) needs at least one scale-out and one scale-in of the worker shown
by the samples AND by the scaling activities; an alarm state alone is not a scaling event.
"""

import os
import sys

sys.dont_write_bytecode = True
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import common  # noqa: E402

DEFAULT_MANIFESTS = ["p15-c-embedding-backlog", "p15-f-poison-replay"]
QUEUES = ["embedding", "embedding-dlq", "job", "job-dlq"]
# Phase 16 L-08: larger labels in the queue plots (Phase 15 used 11 px text on a 1000 px image,
# hard to read in the demo); a wider image keeps the longer legend lines inside the frame.
QUEUE_FONT_SIZE = 14
QUEUE_WIDTH = 1200
QUEUE_LEGEND = 330


def collapse(values):
    out = []
    for v in values:
        if v is None:
            continue
        if not out or out[-1] != v:
            out.append(v)
    return out


def main():
    ctx = common.Context("queue", DEFAULT_MANIFESTS, "queue depth and worker tasks over time (C, F)")
    for manifest in ctx.manifests:
        long_rows, obs_rows = [], []
        for att in ctx.attempts(manifest):
            svc = att.csv("service-samples.csv") or []
            qs = att.csv("queue-samples.csv") or []
            acts = att.csv("scaling-activities.csv")
            worker = list(
                (common.to_int(r.get("epoch_s")), common.to_int(r.get("desired")), common.to_int(r.get("running")),
                 common.to_int(r.get("pending")))
                for r in svc if "worker" in (r.get("service") or "") and common.to_int(r.get("epoch_s")) is not None)
            worker.sort(key=lambda t: t[0])
            for e, d, ru, p in worker:
                for f, v in (("desired", d), ("running", ru), ("pending", p)):
                    long_rows.append([manifest, att.name, e, common.utc(e), "worker", f, "" if v is None else v])
            qser = {q: {"visible": [], "in_flight": []} for q in QUEUES}
            for r in qs:
                e = common.to_int(r.get("epoch_s"))
                q = r.get("queue") or ""
                if e is None or q not in qser:
                    continue
                for f in ("visible", "in_flight"):
                    v = common.to_int(r.get(f))
                    long_rows.append([manifest, att.name, e, common.utc(e), "queue:" + q, f, "" if v is None else v])
                    if v is not None:
                        qser[q][f].append((e, float(v)))
            for q in QUEUES:
                for f in ("visible", "in_flight"):
                    qser[q][f].sort()
                    if not qser[q][f]:
                        att.ctx.note_missing(manifest, att.name, "queue-samples %s %s" % (q, f), "no samples")
            # observed sequence (samples)
            desired_seq = collapse([d for _, d, _, _ in worker])
            running_seq = collapse([ru for _, _, ru, _ in worker])
            events = common.step_events([(e, d) for e, d, _, _ in worker], "desired", "worker")
            t_out1 = next((e for e, lab, k in events if k == "scale-out"), None)
            reached2 = next((e for e, d, _, _ in worker if d is not None and d >= 2), None)
            t_in0 = None
            if t_out1 is not None:
                t_in0 = next((e for e, d, _, _ in worker if e > t_out1 and d == 0), None)
            # scaling activities
            n_out = n_in = 0
            if acts is None:
                act_note = "missing"
            else:
                for r in acts:
                    if "worker" not in (r.get("service") or "worker"):
                        continue
                    e = common.to_int(r.get("start_epoch_s"))
                    d = (r.get("direction") or "unknown").strip()
                    if d == "out":
                        n_out += 1
                    elif d == "in":
                        n_in += 1
                    if e is not None:
                        events.append((e, "activity %s %s" % (d, r.get("status_code") or ""),
                                       "scale-out" if d == "out" else ("scale-in" if d == "in" else "event")))
                act_note = "out=%d in=%d" % (n_out, n_in)
            samples_ok = t_out1 is not None and t_in0 is not None
            if not worker or acts is None:
                gate = "missing evidence"
            elif samples_ok and n_out >= 1 and n_in >= 1:
                gate = "met (samples and scaling activities)"
            else:
                gate = "not met"
            extended = "reached" if (reached2 is not None and t_in0 is not None and t_in0 > reached2) else "not reached"
            # drain of the embedding queue (samples)
            vis = qser["embedding"]["visible"]
            inf = dict(qser["embedding"]["in_flight"])
            t_first = next((e for e, v in vis if v > 0), None)
            t_drained = None
            if t_first is not None:
                t_drained = next((e for e, v in vis if e > t_first and v == 0 and inf.get(e) == 0), None)
            drain_s = (t_drained - t_first) if (t_first is not None and t_drained is not None) else None
            dlq = qser["embedding-dlq"]["visible"]
            deleted = [s for s in att.find("NumberOfMessagesDeleted") if "dlq" not in str(s.dims.get("QueueName", ""))
                       and "embedding" in str(s.dims.get("QueueName", ""))]
            del_sum = sum(v for s in deleted for _, v in s.points) if deleted and any(s.points for s in deleted) else None
            per_min = (del_sum / (drain_s / 60.0)) if (del_sum is not None and drain_s) else None
            obs_rows.append([
                manifest, att.name, att.status(), att.exit_code(),
                "->".join(str(x) for x in desired_seq), "->".join(str(x) for x in running_seq),
                common.utc(t_out1), common.utc(reached2), common.utc(t_in0), act_note, gate, extended,
                common.fmt(max((v for _, v in vis), default=None)), common.utc(t_first), common.utc(t_drained),
                "" if drain_s is None else drain_s,
                common.fmt(dlq[0][1]) if dlq else "", common.fmt(dlq[-1][1]) if dlq else "",
                common.fmt(del_sum), common.fmt(per_min), att.metrics_source,
            ])
            cw_vis = [s for s in att.find("ApproximateNumberOfMessagesVisible")
                      if "embedding" in str(s.dims.get("QueueName", "")) and "dlq" not in str(s.dims.get("QueueName", ""))]
            panels = [
                {"title": "embedding queue (collector samples; CloudWatch for comparison)", "y_label": "messages",
                 "series": [("visible (samples)", vis), ("in flight (samples)", qser["embedding"]["in_flight"])]
                 + [("visible (CloudWatch %s)" % s.stat, s.points) for s in cw_vis]},
                {"title": "worker service tasks (describe-services samples)", "y_label": "tasks",
                 "series": [("desired", [(e, float(d)) for e, d, _, _ in worker if d is not None]),
                            ("running", [(e, float(r)) for e, _, r, _ in worker if r is not None]),
                            ("pending", [(e, float(p)) for e, _, _, p in worker if p is not None])]},
                {"title": "dead-letter and job queues (samples)", "y_label": "messages",
                 "series": [("embedding DLQ visible", dlq), ("job visible", qser["job"]["visible"]),
                            ("job in flight", qser["job"]["in_flight"])]},
            ]
            xs = [e for e, _, _, _ in worker] + [e for e, _ in vis] + [e for e, _, _ in events]
            w0, w1 = att.window()
            if w0 is not None:
                xs += [w0, w1 or w0]
            xr = (min(xs), max(xs)) if xs else (None, None)
            path = os.path.join(ctx.out_dir(manifest), "queue-%s.svg" % att.name)
            common.time_chart(path, "%s %s: worker %s; gate %s" % (manifest, att.name,
                                                                   "->".join(str(x) for x in desired_seq) or "no samples",
                                                                   gate),
                              panels, xr, events=sorted(events),
                              notes=["status %s; scaling activities %s; metrics from %s" % (att.status(), act_note,
                                                                                            att.metrics_source)],
                              max_gap=60, font_size=QUEUE_FONT_SIZE, width=QUEUE_WIDTH, right=QUEUE_LEGEND)
            ctx.written.append(path)
        ctx.write_csv(manifest, "samples.csv", ["manifest", "attempt", "epoch_s", "utc", "stream", "field", "value"],
                      sorted(long_rows, key=lambda r: (r[1], r[2], r[4], r[5])))
        ctx.write_csv(manifest, "observed.csv",
                      ["manifest", "attempt", "status", "exit_code", "worker_desired_sequence",
                       "worker_running_sequence", "first_scale_out_utc", "desired_2_utc", "back_to_0_utc",
                       "scaling_activities", "gate_scale_out_and_in", "extended_0_1_2_0", "embedding_visible_max",
                       "backlog_first_utc", "drained_utc", "drain_s", "embedding_dlq_first", "embedding_dlq_last",
                       "deleted_messages_cloudwatch", "deleted_per_minute", "metrics_source"],
                      obs_rows)
    ctx.finish()


if __name__ == "__main__":
    main()
