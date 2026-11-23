# INC-P15-F: poison message on the embedding queue (planned exercise, experiment F)

Owner: `incident-responder`. Written in Phase 17 (session 2, step N) from committed files only. No AWS call, no action and no rollback was made to write it. The AWS environment was removed in Phase 15 (Nov 21), so nothing here could be re-checked live.

**This was a planned, safe incident exercise, not an unplanned incident.** Phase 15 experiment F (`p15-f-poison-replay`) sent one poison message on purpose under a human-approved manifest, to show retry, dead-lettering, idempotent replay and recovery. It created no destructive condition in the account, as the course specification requires ("Destructive incidents must not be manufactured in a live shared course account"). It matches the specification's first example exercise: "An embedding worker receives malformed content and sends it to a dead-letter queue."

## Sources

Each field below names its source. The value is taken from that file without change. Where those files hold nothing for a field, it says `not recorded`.

| Short name | Path | Note |
|---|---|---|
| incident record | [`experiments/raw/p15-f-poison-replay/20261120T223908Z-e94df52ca8fb-b4c0/incident-F.json`](../../experiments/raw/p15-f-poison-replay/20261120T223908Z-e94df52ca8fb-b4c0/incident-F.json) | Written by the experiment F tool; sha256 `a75efa8af7e9bbfbde9f22128b1d37a0eb60b6fa5ef222325cca4cd686872b4e` (`shasum -a 256`, Phase 17). The summary gives the prefix `a75efa8a…`, which matches |
| results summary 2.1 | [`experiments/results-summary.md`](../../experiments/results-summary.md), section 2.1 "p15-f-poison-replay (experiment F)" | experiment-engineer's Phase 15 analysis |

Further committed files that results summary 2.1 cites. They are listed so a reader can follow its sources; this report took no value straight from them: [`experiments/manifests/p15-f-poison-replay.json`](../../experiments/manifests/p15-f-poison-replay.json) (the approved manifest), [`experiments/derived/out/p15-f-poison-replay/parts.csv`](../../experiments/derived/out/p15-f-poison-replay/parts.csv), [`experiments/derived/out/p15-f-poison-replay/timeline.csv`](../../experiments/derived/out/p15-f-poison-replay/timeline.csv), and the analysis report [`docs/agent-runs/phase-15/agent-report-experiment-FECD.md`](../agent-runs/phase-15/agent-report-experiment-FECD.md).

All times are UTC. Clock alignment, from results summary section 0: "aligned on UTC to within about ±1.5 s, inside 60 s metric periods".

## Required fields

| Field (course specification) | Value | Source |
|---|---|---|
| Incident ID | `INC-P15-F` (this document's name). The recorded ID is `p15-f-20261120T223908Z-e94df52ca8fb-b4c0` | incident record `incidentId` |
| Start time | 2026-11-20T22:39:19Z | incident record `startUtc` |
| End time | 2026-11-20T22:42:45Z (embedding queue in flight back to 0) | incident record `endUtc`; results summary 2.1, "recovery" row |
| Detection source | "CloudWatch alarm on the embedding DLQ (ApproximateNumberOfMessagesVisible > 0), state ALARM at 2026-11-20T22:44:50Z" | incident record `detectionSource`, `detectedAtUtc`; results summary 2.1, "recovery" row ("detection source") |
| User-visible impact | "not measured by this tool; the embedding queue serves no API route (design, decision 8.1)". No measured user-visible impact is recorded. In the same window, the API idempotency part returned the expected statuses (see the timeline) | incident record `userVisibleImpact`, `observation.part3_api`; results summary 2.1, "incident record" row |
| Trace and run IDs | attempt `20261120T223908Z-e94df52ca8fb-b4c0`; manifest `p15-f-poison-replay`; poison task `p15-f-poison-20261120T223908Z-e94df52ca8fb-b4c0`; replay task `p15-f-replay-20261120T223908Z-e94df52ca8fb-b4c0`; repository HEAD `e94df52`, build commit `684636ef`. Distributed trace IDs (for example X-Ray): `not recorded`. API query ID: `not recorded` (the summary says only "same query ID" for the replay) | incident record `traceAndRunIds`; results summary 2.1 (attempt block, "API idempotency" row) |
| Timeline | See [Timeline](#timeline) below | incident record `timeline`; results summary 2.1 |
| Root cause | "injected by experiment F: a valid-JSON embedding-task-v1 message naming an unknown index, a permanent failure by design (decision 7.2)" | incident record `rootCause`; results summary 2.1 ("One poison message (unknown index)") |
| Recovery action | "none by hand: SQS moves the message to the dead-letter queue after maxReceiveCount (5) receives; the worker keeps processing valid tasks". No rollback, no redrive and no script run was needed. The poison message was left in the DLQ ("never deleted") | incident record `recoveryAction`, `observation.part1_poison`; results summary 2.1, "poison" row |
| Data-integrity assessment | "the poison names no existing index, so nothing is written for it; the valid task rewrites one existing vector under its existing key and metadata (storage design, decision 6.3); this tool checks only the completion record presence". Limit: no check of vector contents is recorded; only the completion record's presence was checked | incident record `dataIntegrityAssessment` |
| Follow-up owner | "experiment-engineer (analysis, D16); rag-engineer or service-engineer for any part not seen". The record names no part as not seen (every `observation` entry says "seen" or "yes"), so the analysis by experiment-engineer was the only follow-up. It was done in `experiments/results-summary.md` section 2.1 | incident record `followUpOwner`, `observation`; results summary 2.1 |
| Preventive test or control | "dead-letter queue with maxReceiveCount, the DLQ alarm, the release-at-once rule for permanent failures (decision 7.2), the completion record (decision 6.3) and the ReembedSuite unit tests". Recorded caps: embedding `maxReceiveCount` 5, visibility timeout 300 s, `worker_max_count` 2 | incident record `preventiveTestOrControl`; results summary 2.1, "Real caps" row |

## Timeline

The epoch seconds come from the incident record's `timeline`. They were converted to UTC by hand. The anchor 1795214360 = 22:39:20 agrees with results summary 2.1 ("sent 22:39:20"), and every other row whose time the summary prints agrees as well. The rows are in time order. In the incident record, the worker-log `processed` row (1795214507) comes after two later rows.

| UTC (2026-11-20) | Epoch s | Part | Source | Event, outcome |
|---|---|---|---|---|
| 22:39:08 | not recorded | run | run record | Experiment action starts (results summary 2.1: "action 22:39:08 to 22:44:55") |
| 22:39:19 | not recorded | – | incident record | `startUtc` |
| 22:39:20 | 1795214360 | poison | collector | sent |
| 22:39:21 | 1795214361 | replay | collector | valid task sent |
| 22:41:34 to 22:41:53 | not recorded | side | results summary 2.1 | worker scale-out 0 to 1 (desired 1 at 22:41:34, running 1 at 22:41:53) |
| 22:41 period | not recorded | poison | CloudWatch | Received 6 in the 22:41 period (5 poison receives and the valid task), results summary 2.1 |
| 22:41:47 | 1795214507 | replay | worker log | delivery, `processed` |
| 22:41:57 | 1795214517 | poison | SQS | DLQ count = baseline + 1 |
| 22:41:58 | 1795214518 | poison | SQS | poison taskId read back from the DLQ (`dlq-received`, never deleted) |
| 22:41:58 | 1795214518 | replay | collector | wait for `processed` starts |
| 22:42:00 | 1795214520 | replay | completion record | present, `processed` |
| 22:42:01 | 1795214521 | replay | collector | duplicate sent again; wait for `replayed` starts |
| 22:42:01 | 1795214521 | replay | worker log | delivery, `replayed` |
| 22:42:41 | 1795214561 | api | API | first request, 200 |
| 22:42:42 | 1795214562 | api | API | idempotent replay, 200 (`Idempotent-Replayed: true`, per results summary 2.1) |
| 22:42:43 | 1795214563 | api | API | changed body, 409 (`IDEMPOTENCY_CONFLICT`, per results summary 2.1) |
| 22:42:45 | 1795214565 | recovery | SQS | embedding queue in flight 0 (`endUtc`) |
| 22:44:50 | 1795214690 | recovery | CloudWatch | DLQ alarm in ALARM (detection) |
| 22:44:55 | not recorded | run | run record | Experiment action ends (results summary 2.1) |

Detection came 125 s after the recorded end (22:44:50 minus 22:42:45, computed by hand from the incident record). The alarm uses 60 s CloudWatch periods. The results summary records no alarm evaluation settings, so this report does not explain the delay further.

## Interpretation (labelled, from results summary 2.1)

Results summary 2.1 interprets the result this way: "all five poison receives fall within the 22:41 period, so the release-at-once rule worked. Without it the poison would take about 5 × 300 s to reach the DLQ." It also says that DLQ "received 10" in the 22:41 period "is most likely the collector's own read-back (unverified)".

## What this report does not cover

- No live re-check: the environment was removed in Phase 15, so nothing above was observed again in Phase 17.
- No measured user-visible impact and no distributed trace IDs are recorded (see the table).
- The data-integrity check is limited to the completion record's presence, as the incident record states.
