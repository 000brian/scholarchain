# Phase 15 D16: analysis of experiments F, E, C and D (experiment-engineer, read-only)

Work order: docs/agent-runs/phase-15/prompt.md (sha256 3d806922c6dd74ada61bceca45d3a302740a4083f260f0ae3ca6b6b6b9dfa3e0, verified), decisions 3, 10, 11, 13 (D10 to D16), "How the run ends". HEAD d872d12, tree clean before and after. Build commit of every run: 684636ef997ee041912e2b57d1c597ded8e9778d. Region us-east-1. Dataset snapshot dblp-2026-11-01 with subset rule dblp-subset-v1, Phase 14 graph run:v1:f2625d504b12749389cf4f5b1a26aed4 (every run record).

Conventions in this report:
- **Measured** = a value read from a committed raw file (named). **Interpretation** = my reading of it, labelled. **Estimate** = labelled, with its basis.
- All times are UTC. CloudWatch values are 60 s periods labelled by their start; the exports store them as UTC-06:00 and are converted here.
- A series with no datapoints stays **missing (no datapoints)**, never 0.
- The order ran E (D11) before F (D10), a recorded order change. F ran after the collector repair L-02 (tool sha256 02a5b0ed…).
- Values marked "by hand" were computed by me from the named raw file. No committed derive script produced them yet; D23 should regenerate them with the scripts.

## 0. Attempts at a glance (every attempt listed, none dropped)

| Exp | Manifest (sha256 prefix, = approved) | Attempt | Run window (runner) | Action window | Runner exit | Record status | Commit of raw folder |
|---|---|---|---|---|---|---|---|
| E | p15-e-retrieval-modes (be3868df) | 20261120T221402Z-6a6c31a06d60-b6c7 | 22:14:02 to 22:20:15 | 22:14:02 to 22:15:14 | 0 | succeeded | 2cc5804 |
| F | p15-f-poison-replay (a39c7e45) | 20261120T223908Z-e94df52ca8fb-b4c0 | 22:39:08 to 22:49:56 | 22:39:08 to 22:44:55 | 0 | succeeded | 70876eb |
| C | p15-c-embedding-backlog (36352e8f) | 20261120T225730Z-eb786c5275a3-ab26 | 22:57:30 to 23:30:09 | 22:57:30 to 23:25:08 | 0 | succeeded | e86449a (derived ff00cfa) |
| D-query | p15-d-query-load (2af43f86) | 20261120T235521Z-ff00cfa278c8-ca0c | 23:55:21 to 00:09:22 | 23:55:21 to 00:04:21 | 0 | succeeded | abe4764 |
| D-read | p15-d-read-load (d851716d) | 20261121T001529Z-abe4764a8293-b3f0 (attempt 1) | 00:15:29 to 00:26:53 | 00:15:29 to 00:21:52 | 0 | succeeded | ee3bce8 |
| D-read | p15-d-read-load (d851716d) | 20261121T002254Z-abe4764a8293-db0d (attempt 2, unplanned) | 00:22:54 to 00:23:09 | 00:22:55 to 00:22:58 | 5 (action exit 3) | failed, failed_step metrics-settle, signal INT | ee3bce8 |

Overlaps and shared resources: C overlapped the three EMR runs (A-Hadoop attempt 2, A-Spark, B). D-query and D-read overlapped A-Hadoop attempt 2, which ended at 00:34:22. The only shared resource is the NAT gateway (decision 10). EMR touches no ECS service, queue or Neptune. E and F overlapped no EMR cluster: A-Hadoop attempt 1 failed in 1 s at 21:59 and attempt 2 started at 22:56. D-read attempt 2 started while attempt 1's runner was in its 300 s metrics wait, and it sent no request (section 4.3).

## 1. Clock offset per attempt (decision 10)

Method: offset = AWS `Date` header − midpoint of the laptop's send and receive epoch seconds. Uncertainty = ±1 s for the one-second resolution of the header and of `date -u +%s`, plus half the curl round trip (`time_total`). Source: each attempt's `clock.csv` or `load-clock.csv`.

| Attempt | Start sample: offset, RTT | End sample: offset, RTT | Reported offset |
|---|---|---|---|
| E b6c7 (clock.csv) | 0 s, 0.186 s | 0 s, 0.182 s | 0 s ± 1.09 s |
| F b4c0 (clock.csv) | 0 s, 0.156 s | 0 s, 0.156 s | 0 s ± 1.08 s |
| C ab26 (clock.csv) | 0 s, 0.157 s | 0 s, 0.145 s | 0 s ± 1.08 s |
| D-query ca0c (load-clock.csv) | 0 s, 0.146 s | +0.5 s (send 1795219457, recv 1795219458, Date 1795219458), 0.143 s | 0 to +0.5 s ± 1.07 s |
| D-read b3f0 (load-clock.csv) | 0 s, 0.147 s | +0.5 s (send 1795220499, recv 1795220500, Date 1795220500; HTTP 429 still carried Date), 0.122 s | 0 to +0.5 s ± 1.07 s |
| D-read db0d | no clock file (the tool stopped at step `key` before any request) | – | missing |

The claim is "aligned on UTC to within about ±1.1 s, inside 60 s metric periods". No drift is detectable inside any run at this resolution. Interpretation: in D-read the ALB series line up with the tool's level minutes exactly (graph 5XX per period 76+93 / 210 / 175 = the tool's 169 / 210 / 175 failures of levels 2 / 3 / 4). The Neptune per-minute series differ from the tool's minutes by up to one period (section 4.2). This is a property of the Neptune metric, not of the laptop clock.

## 2. Experiment F: failure and recovery (p15-f-poison-replay, attempt b4c0)

### 2.1 Evidence status

- tool: **ran** (runner exit 0; human `exit=0`, runbook row D10)
- action: **succeeded** (run-record.json status succeeded, action exit 0)
- metrics: **complete** (16 of 16 queries returned data, `metrics/metric-data.json`; 0 unresolved)
- observation: **seen**: retry, dead letter, idempotent replay (worker and API) and recovery. Evidence: `events.csv`, `incident-F.json`, `queue-samples.csv`, `alarm-samples.csv`, `tool-record.json` (`evidence.worker_log` yes, `processed_seen` true, `replayed_seen` true, `dlq_received` true). Side observation: a worker **scale-out 0 → 1** was seen (`service-samples.csv`). A **scale-in was not in F's window** (section 2.4).

### 2.2 Measured facts

Settings read by the tool: maxReceiveCount 5, visibility 300 s. Baseline at 22:39:16: both queues and both DLQs 0/0, worker 0/0/0, DLQ alarm OK.

| Event (source file) | Epoch | UTC | Measured |
|---|---|---|---|
| poison sent (events.csv) | 1795214360 | 22:39:20 | taskId p15-f-poison-ATTEMPT |
| valid task sent (events.csv) | 1795214361 | 22:39:21 | taskId p15-f-replay-ATTEMPT |
| worker desired 1, pending 1 (service-samples.csv) | 1795214494 | 22:41:34 | the previous sample (22:41:14) was 0/0 |
| scale-out alarm on the embedding queue in ALARM (alarm-samples.csv) | 1795214495 | 22:41:35 | |
| valid task completed (events.csv completion record completedAt; worker log `processed` 1795214507) | 1795214506 | 22:41:46 | processed while the poison was being received |
| worker running 1 (service-samples.csv) | 1795214513 | 22:41:53 | |
| embedding DLQ = baseline + 1 (events.csv) | 1795214517 | 22:41:57 | visible=1 |
| poison taskId read back from the DLQ (events.csv) | 1795214518 | 22:41:58 | never deleted |
| duplicate of the valid task sent (events.csv) | 1795214521 | 22:42:01 | sent only after `processed` |
| worker log `replayed` (events.csv) | 1795214521 | 22:42:01 | same second as the send |
| API first POST /v1/query (events.csv) | 1795214561 | 22:42:41 | 200, t = 16.82 s, Idempotent-Replayed absent |
| API replay, same key (events.csv) | 1795214562 | 22:42:42 | 200, t = 0.177 s, Idempotent-Replayed true, same query ID |
| API same key, other body (events.csv) | 1795214563 | 22:42:43 | 409 IDEMPOTENCY_CONFLICT, t = 0.154 s |
| embedding in flight back to 0 (events.csv) | 1795214565 | 22:42:45 | recovery |
| DLQ alarm in ALARM (events.csv; alarm-samples first ALARM row 1795214693) | 1795214690 | 22:44:50 | detection source |

Durations (by hand, from the epochs above):
- poison send to DLQ: 157 s. Of this, 134 to 153 s was waiting for the worker to start from 0 (first desired-1 sample 134 s after the send, first running sample 153 s after).
- DLQ arrival to alarm ALARM: 173 s (alarm period 300 s).
- first message to worker desired 1: 114 to 134 s (between the two samples).

CloudWatch, embedding queue (metric-data.json, 60 s periods):

| Period (UTC) | Sent | Received | Deleted | Visible max | Oldest age max (s) | DLQ visible | DLQ received |
|---|---|---|---|---|---|---|---|
| 22:39 | 2 | 0 | 0 | 2 | 5 | 0 | 0 |
| 22:40 | 0 | 0 | 0 | 2 | 42 | 0 | 0 |
| 22:41 | 0 | 6 | 1 | 2 | 93 | 0 | 10 |
| 22:42 | 1 | 1 | 1 | 0 | 0 | 1 | 0 |
| 22:43 to 22:48 | 0 | 0 | 0 | 0 | 0 | 1 | 0 |

The NotVisible series was 0 in every period (all four queues). The job queue and job DLQ were 0 throughout. Worker CPU average was at most 6.76 % (22:42) and memory at most 16.3 %. Worker LiveTaskCount was 1 from 22:41 to 22:48. Query-session DynamoDB writes were 1 and 21 units (22:41, 22:42: part 3).

Interpretation (labelled):
- Received 7 = 5 poison receives (maxReceiveCount 5) + the valid task + its duplicate. Deleted 2 = the valid task and its duplicate.
- All five poison receives fall inside the 22:41 period, within about 4 to 23 s of the worker starting. This is consistent with the release-at-once rule (decision 7.2). Without it the poison would need about 5 × 300 s.
- DLQ received 10 in 22:41 is most likely the collector's own read-back polling (`receive-message --visibility-timeout 0`), not worker activity. Not verified.
- The API event epochs look like request end times: the first request took 16.8 s and the replay is recorded one second later. The collector does not say which time it records (minor, for the collector notes).

### 2.3 Incident record (incident-F.json, sha256 a75efa8a…, = tool-record entry)

All spec incident fields are present: incidentId, startUtc 22:39:19, endUtc 22:42:45, detectionSource (embedding DLQ alarm), detectedAtUtc 22:44:50, userVisibleImpact ("not measured by this tool; the embedding queue serves no API route"), traceAndRunIds (attempt, manifest, poison and replay task IDs), timeline (15 events), rootCause, recoveryAction (none by hand: redrive after 5 receives), dataIntegrityAssessment (the poison writes nothing; the valid task rewrites one vector under its key; only completion-record presence is checked), followUpOwner, preventiveTestOrControl. One small inconsistency: startUtc 22:39:19 is one second before the first `sent` event (22:39:20).

### 2.4 Scale-out and scale-in in F

- Scale-out 0 → 1: **seen** in `service-samples.csv` (0/0 ×8, then 1/0 pending 1 at 22:41:34, then 1/1 to the end). F records no scaling activities (not in its design), so this rests on describe-services samples and LiveTaskCount.
- Scale-in: **not in F's window**. The last sample at 22:44:52 shows 1/1, and LiveTaskCount is 1 through the 22:48 period in the export.
- Live read at D16, outside the raw folder and not raw evidence: aws-api `cloudwatch get-metric-statistics` LiveTaskCount for the worker from 22:44 to 23:00 returned 1 for the 22:44 to 22:48 periods and **no datapoints** from 22:49 to 22:59. The D4b release record shows worker 0/0 at 22:54:49. Interpretation: the worker scaled in between about 22:49 and 22:54:49. That is a lifecycle observation from other records, not an F measurement. The F evidence for the gate is scale-out only. C carries the gate.

### 2.5 Spec items covered by F

| Spec item (F, and the scaling-rule row "Step Functions and SQS") | Status | Evidence |
|---|---|---|
| one safe failure triggered (poison message) | covered | events.csv, incident-F.json |
| retry | covered | CloudWatch received 6 in 22:41 (5 poison receives); maxReceiveCount 5 read by the tool |
| dead-letter | covered | DLQ = baseline + 1 and taskId read back; DLQ alarm ALARM 22:44:50 |
| idempotency (worker) | covered | worker log `processed` then `replayed`; completion record present |
| idempotency (API) | covered | 200 / 200 Idempotent-Replayed true / 409 |
| recovery | covered | valid task processed while the poison was retried; in flight back to 0 |
| incident fields | covered | incident-F.json |
| user-visible impact | not measured (stated in the record) | – |
| Step Functions execution state and retries | not in F (A and B) | – |

### 2.6 Failures (F)

- None in attempt b4c0.
- Before it, the D9a `--check` preflight of F exited 5 (collector exit 3, `model-mismatch`, nothing sent, no run folder) and led to repair L-02 (runbook-log rows D9a and L-01/L-02). This was a preflight, not an attempt, but it is a reported failure.

## 3. Experiment E: retrieval modes (p15-e-retrieval-modes, attempt b6c7), EXPLORATORY

### 3.1 Evidence status

- tool: **ran** (runner exit 0; runbook-log row "D11 commit + review")
- action: **succeeded** (run record succeeded; nested load record 20261120T221406Z-6a6c31a06d60-2968 succeeded, ops task container exit 0)
- metrics: **complete** for what the manifest asked (8 of 8: AWS/Neptune ×5, AWS/DynamoDB ×3). AWS/ECS for the ops task does not apply: it is not a service and Container Insights is off. Its lifecycle is in the nested record.
- observation: **seen**: a per-mode comparison over the stated universe, labelled EXPLORATORY (11 questions, fewer than 15). Evidence: `retrieval-modes.csv`, `retrieval-eval-report.json` (sha256 cadd90b8…, byte copy, 123,562 B). No scaling evidence is expected from E.

### 3.2 Universe (retrieval-eval-report.json `evalSet`)

- benchmark-v1 (sha256 b58c5e1a…); unit: canonical researcher ID.
- **11 questions in the universe, 100 labels.** Targets per question: q01 10, q02 5, q03 10, q07 10, q08 10, q19 10, q20 5, q21 10, q25 10, q26 10, q30 10.
- **19 not-comparable**, each with its reason: q04, q05, q06 (metric or work values); q09 (only named subjects); q10, q11, q12, q13 (path only); q14, q15 (group values); q16, q17, q18 (group and metric values); q22, q23, q24 (metric values); q27, q28, q29 (abstention expected).
- Drift check: `labelsNotInGraph` empty and `labelsUnchecked` 0 for every question.
- nDCG not computed (no graded labels). Fusion `fusion-exp-v1`, k = 60, labelled "experiment only; the production workflow has no fused ranking". topK 30; LLM calls 0.

### 3.3 Per-mode results (measured; retrieval-modes.csv and report `modes`)

| Mode | recall@1 | @5 | @10 | @30 | MRR | scored | errors | empty | abstentions | by-design-weak | latency p50 / p95 / max / mean (ms) |
|---|---|---|---|---|---|---|---|---|---|---|---|
| graph-only | 0.100 | 0.500 | 0.818 | 0.818 | 0.818 | 11 | 0 | 2 | 2 | 2 | 31 / 970 / 970 / 224.7 |
| vector-only (S3 Vectors) | 0.000 | 0.000 | 0.000 | 0.009 | 0.003 | 11 | 0 | 5 | 0 | 9 | 204 / 879 / 879 / 330.7 |
| fused (experiment only) | 0.091 | 0.409 | 0.636 | 0.827 | 0.776 | 11 | 0 | 0 | 0 | 0 | 246 / 1608 / 1608 / 555.8 |
| recall ceiling (all modes) | 0.118 | 0.591 | 1.000 | 1.000 | – | – | – | – | – | – | – |

All three modes share the denominator (11 questions, the same K values). The ceiling is below 1 at K = 1 and 5 because most questions have 5 or 10 targets.

Per question (report `questions[].modes`: latency in ms, reciprocal rank RR; W = by-design-weak; E = empty):

| Q | category | graph-only | vector-only | fused |
|---|---|---|---|---|
| q01 | coauthor_breadth_ranking | 725, RR 1 | 879, RR 0, W | 1608, RR 1 |
| q02 | coauthor_breadth_ranking | 9, RR 1 | 172, RR 0, W, E | 181, RR 1 |
| q03 | coauthor_breadth_ranking | 12, RR 1 | 204, RR 0, W, E | 216, RR 1 |
| q07 | direct_collaboration | 970, RR 1 | 349, RR 0, W | 1319, RR 1 |
| q08 | direct_collaboration | 584, RR 1 | 616, RR 0, W | 1200, RR 0.5 |
| q19 | bridge_researchers | 73, RR 1 | 372, RR 0, W | 445, RR 1 |
| q20 | bridge_researchers | 31, RR 1 | 177, RR 0, W, E | 208, RR 1 |
| q21 | bridge_researchers | 58, RR 1 | 188, RR 0, W, E | 246, RR 1 |
| q25 | topic_filtered_ranking | 2, RR 0, W, E, abstain `topic-candidates-failed` | 295, RR 0.036 | 297, RR 0.036 |
| q26 | topic_filtered_ranking | 1, RR 0, W, E, abstain `topic-candidates-failed` | 193, RR 0 | 194, RR 0 |
| q30 | prompt_injection | 7, RR 1 | 193, RR 0, W, E | 200, RR 1 |

Embedder usage: 11 requests, 11 texts, 886 characters, 254 provider-reported tokens, 0 retries. Evaluation 9,710 ms; job total 11,390 ms; ops task about 62 s from PROVISIONING to STOPPED (action.log polls).

CloudWatch in the E window (metric-data.json):
- Neptune OpenCypherRequestsPerSec 2.10/s in the 22:15 period (about 126 requests); 0 in every other period.
- Neptune CPU maximum 19.0 % (22:15; baseline about 12.5 to 13.9 %). Server errors 0. Main request queue pending 0.
- DynamoDB reads: researcher-metric-cache 34 units (22:14), entity-link-cache 2, evidence-bundle 0.

Interpretation (labelled, exploratory, n = 11):
- graph-only finds the labelled researchers at rank 1 on 9 of 11 questions. It returns nothing on the two topic questions by design: the topic filter needs semantic candidates, recorded as abstention `topic-candidates-failed`.
- vector-only finds almost none of them. On the 2 questions where it is not weak by design it reaches RR 0.036 (rank 28) and 0.
- fused removes the empty results (0) and keeps MRR close to graph-only (0.776 vs 0.818). It lowers recall@5 and @10 (0.41 vs 0.50; 0.64 vs 0.82), because vector hits interleave with graph hits. It costs about the sum of both latencies.
- With 11 questions, one question moves MRR by about 0.09. No ranking of modes should be claimed beyond "exploratory".

### 3.4 Spec items covered by E

| Spec item | Status | Evidence |
|---|---|---|
| compare graph-only, S3-Vectors-only, fused | covered (exploratory) | retrieval-modes.csv, report |
| OpenSearch fourth mode | not applicable (not implemented; phase note) | – |
| quality metrics per mode (recall@K, MRR; nDCG where graded) | covered; nDCG not computable (no graded labels) | report `modes` |
| latency per mode | covered (per question and summary) | report |
| live S3 Vectors search returns results (G-27 part 4, also D7) | covered: vector-only returned non-empty lists on 6 of 11 questions | report `listLength`, `empty` 5 |

### 3.5 Failures (E)

None. Order note: E ran before F (recorded deviation from the planned order, runbook-log row "Order change").

## 4. Experiment D: query load

### 4.1 D-query (p15-d-query-load, attempt ca0c)

#### Evidence status

- tool: **ran** (runner exit 0, runbook-log row D13)
- action: **succeeded** (run record; d-load.sh exit 0, query-load.sh exit 0)
- metrics: **partial (list)**: 30 of 34 series have datapoints. **Missing (no datapoints):** ALB `HTTPCode_ELB_5XX_Count`; DynamoDB `ThrottledRequests` (query-session PutItem; evidence-bundle GetItem); DynamoDB `ReadThrottleEvents` (researcher-metric-cache). The runner reports `exported`. CloudWatch generally publishes these only when an event occurs, so no datapoint is consistent with zero throttles but is **not a measured zero**.
- observation: **seen**: per-level latency, throughput and error classes, plus per-stage latencies from explain. **ECS scale-out not seen**, which decision 3 did not expect. Evidence: `load-levels.csv`, `load-requests.csv` (149 requests), `load-explain.csv` (40 rows), `service-samples.csv` (108 rows), `metrics/metric-data.json`.

#### Per level (measured, load-levels.csv; nearest-rank percentiles of curl time_total; "few samples" = n < 20)

| Level | Clients | Window (UTC) | Stop | Attempted | Completed/s | answered | timeout (504 WORKFLOW_TIMEOUT) | overloaded (503 OVERLOADED) | throttled (429) | Error rate | p50 / p95 / p99 all (s) | p50 / p95 / p99 answered (s) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 1 | 23:55:24 to 23:57:31 | duration | 10 | 0.079 | 10 | 0 | 0 | 0 | 0 % | 11.73 / 18.51 / 18.51 (few) | 11.73 / 18.51 / 18.51 (few) |
| 2 | 2 | 23:58:37 to 00:00:52 | duration | 19 | 0.141 | 18 | 1 | 0 | 0 | 5.3 % | 12.93 / 25.17 / 25.17 (few) | 12.03 / 21.55 / 21.55 (few) |
| 3 | 4 | 00:02:03 to 00:02:54 | cap 60 | 60 | 1.176 | 8 | 4 | 48 | 0 | 86.7 % | 0.160 / 25.16 / 25.37 | 11.18 / 17.57 / 17.57 (few) |
| 4 | 8 | 00:03:59 to 00:04:15 | cap 60 | 60 | 3.750 | 4 | 0 | 53 | 3 | 93.3 % | 0.153 / 8.25 / 16.35 | 12.33 / 16.35 / 16.35 (few) |

- Answered throughput (by hand: answered / level wall time): 0.079, 0.133, 0.157 and 0.25 per second.
- All 5 timeouts were 504 `WORKFLOW_TIMEOUT` at 25.16 to 25.37 s (server side; curl max 35 s). All 101 overloaded answers carry error code `OVERLOADED`. The 3 throttled answers (level 4) carry no error field (API Gateway).
- No `abstained`, `other-4xx`, `other-5xx` or `client-error` answers. Readiness at start: healthz and readyz 200 on the first try.

Task counts: `service-samples.csv` has 108 samples (query, evidence and graph, every 15 s), **all desired 1, running 1, pending 0**. CloudWatch LiveTaskCount was 1 for all three services in every period.

CloudWatch (metric-data.json). Periods are attributed to a level only when fully inside it; otherwise they are marked boundary:

| Period | Attribution | ALB query TargetResponseTime p50 / p95 / p99 (s) | ALB query RequestCount | Target 5XX | Query CPU avg % | Query mem % |
|---|---|---|---|---|---|---|
| 23:55 | L1 | 0.001 / 15.94 / 15.98 | 8 | – | 2.01 | 7.76 |
| 23:56 | L1 | 11.36 / 14.63 / 14.69 | 5 | – | 6.73 | 8.33 |
| 23:57 | boundary | 15.12 / 18.16 / 18.26 | 3 | – | 1.28 | 8.53 |
| 23:58 | boundary | 10.83 / 15.92 / 15.93 | 2 | – | 0.12 | 8.54 |
| 23:59 | L2 | 13.11 / 24.29 / 24.88 | 8 | 1 | 9.76 | 9.03 |
| 00:00 | boundary | 11.64 / 21.25 / 21.35 | 9 | – | 3.63 | 9.55 |
| 00:02 | L3 | 0.006 / 23.67 / 24.77 | 60 | 52 | 3.79 | 9.67 |
| 00:03 | boundary | 0.007 / 0.061 / 0.062 | 12 | 12 | 2.21 | 9.76 |
| 00:04 | L4 | 0.006 / 12.74 / 16.09 | 46 | 41 | 2.38 | 9.80 |

- Target 5XX total 106 = 101 OVERLOADED + 5 timeouts (by hand). ELB 5XX: missing (no datapoints).
- Evidence service: CPU at most 2.03 %, memory at most 21.2 %. Graph service: CPU at most 0.30 %, memory at most 18.8 %. The explain reads go to the evidence target group: RequestCount 41 in the window against 40 explain reads.
- DynamoDB SuccessfulRequestLatency averages: query-session PutItem 2.8 to 5.0 ms; query-session GetItem 1.5 to 11.6 ms; evidence-bundle GetItem 0.9 to 1.7 ms; researcher-metric-cache GetItem 1.5 to 3.9 ms.
- Consumed capacity: query-session writes up to 222 units per minute; reads up to 60.
- Neptune: CPU maximum 18.9 % (00:01); OpenCypherRequestsPerSec up to 1.25/s; MainRequestQueuePendingRequests 0. TotalServerErrorsPerSec nonzero in 00:02 (0.117/s, about 7), 00:03 (about 4) and 00:04 (about 3). Also 0.0167/s (about 1) in 23:51, which is outside this run.

Per-stage latencies from `GET /v1/explain` (load-explain.csv, 40 rows = every answered query; stage keys per load-tool-record.json). Stats computed by hand with nearest rank:

| Spec item | Explain field used | n | p50 | p95 | max | Status |
|---|---|---|---|---|---|---|
| LLM latency | `synthesis_ms` | 40 | 9,656 ms | 15,389 ms | 19,681 ms | covered (stage includes the model call and its handling; not a pure provider latency) |
| planner stage | `planner_ms` | 40 | 904 ms | 2,308 ms | 7,970 ms | reported; whether it includes a model call is not stated by the explain fields |
| vector-query latency | `semantic_ms` (q25, q26: the only graph-and-vector questions) | 7 | 345 ms (range 305 to 501) | – | 501 ms | **partial**: query embedding plus S3 Vectors search together; 2 questions only; no S3 Vectors metric |
| Neptune latency | – | – | – | – | – | **missing** as a separate measure. Graph-store time is inside `paths_ms` (non-zero on 7 rows, 233 to 460 ms, median 405), `resolver_ms` and `community_ms`, which also include service work. No Neptune latency metric was exported. |
| verification | `verification_ms` | 40 | 5 ms | 32 ms | 33 ms | reported |

Tokens (measured, load-explain.csv): 187,187 input and 104,982 output over the 40 answered queries, a mean of 4,680 input and 2,625 output per query.

Measurement limit found (interpretation): query IDs are deterministic per question. q05 has the same ID `run:v1:a606935b…` in levels 1, 2 and 3 and in the D5 smoke. The explain read therefore returns the latest stored execution of that question. Here explain rows equal answered queries per level, but a question that ran twice inside one level would show only one execution.

Interpretation (labelled):
- Level 3 (4 clients, equal to `service.maxConcurrentQueries` 4) produced 48 OVERLOADED answers. All of them came after the 4 workflow timeouts that ended at 00:02:46 to 00:02:54 (first OVERLOADED at 00:02:46). Hypothesis, not verified: a workflow that timed out at the API keeps its concurrency permit for a while.
- Level 4 is beyond the 4 permits and shows the expected OVERLOADED answers plus 3 API Gateway throttles.
- CPU never came near the 60 % target, so no scale-out was expected or seen (decision 3, a stated limitation).

#### Spec items, D-query

| Spec item | Status | Source |
|---|---|---|
| ≥ 4 concurrency levels (adjusted for budget: 1, 2, 4, 8; deviation 5) | covered | load-levels.csv |
| p50, p95, p99 latency | covered (tool, all and answered); ALB p50/p95/p99 support it | load-levels.csv; metric-data.json |
| throughput | covered (completed/s; answered/s by hand) | load-levels.csv |
| error rate | covered | load-levels.csv |
| ECS task count | covered (flat 1/1) | service-samples.csv; LiveTaskCount |
| CPU, memory | covered | AWS/ECS series |
| Neptune latency | **missing** (see above) | – |
| DynamoDB throttles | **missing (no datapoints)**; consumed capacity and request latency present | metric-data.json |
| vector-query latency | **partial** (semantic stage, n = 7) | load-explain.csv |
| LLM latency | covered (synthesis stage) | load-explain.csv |

### 4.2 D-read (p15-d-read-load, attempt 1 b3f0)

#### Evidence status

- tool: **ran** (runner exit 0, runbook-log row D14)
- action: **succeeded**
- metrics: **partial (list)**: 23 of 27 series have datapoints. **Missing (no datapoints):** ALB `HTTPCode_Target_5XX_Count` for the evidence target group; ALB `HTTPCode_ELB_5XX_Count`; DynamoDB `ThrottledRequests` (evidence-bundle GetItem); DynamoDB `ReadThrottleEvents` (evidence-bundle).
- observation: **seen**: per-level latency and error classes. **ECS scale-out not seen** (not expected). Evidence: `load-levels.csv`, `load-requests.csv` (2,251 rows), `id-check.csv` (29 of 29 IDs answered 200 before the load), `service-samples.csv` (78 rows), `metrics/metric-data.json`.

#### Per level (measured, load-levels.csv)

| Level | Clients | Window (UTC) | Stop | Attempted | Completed/s | ok | throttled (429) | overloaded (503) | other-5xx (502) | Error rate | p50 / p95 / p99 all (s) | p50 / p95 / p99 ok (s) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 1 | 00:15:52 to 00:17:52 | duration | 451 | 3.758 | 451 | 0 | 0 | 0 | 0 % | 0.198 / 0.307 / 0.455 | 0.198 / 0.307 / 0.455 |
| 2 | 5 | 00:18:52 to 00:19:16 | cap 600 | 600 | 25.0 | 34 | 397 | 166 | 3 | 94.3 % | 0.139 / 0.186 / 0.282 | 0.161 / 0.394 / 0.397 |
| 3 | 10 | 00:20:16 to 00:20:29 | cap 600 | 600 | 46.2 | 34 | 356 | 203 | 7 | 94.3 % | 0.138 / 0.191 / 0.278 | 0.160 / 0.342 / 0.382 |
| 4 | 20 | 00:21:29 to 00:21:39 | cap 600 | 600 | 60.0 | 50 | 375 | 157 | 18 | 91.7 % | 0.152 / 0.280 / 0.541 | 0.206 / 0.505 / 0.623 |

By route and error code (by hand, from the class and error_code fields of load-requests.csv):

| Route | Total | ok | throttled | 503 (error code) | 502 (error code) |
|---|---|---|---|---|---|
| GET /v1/graph/nodes/{nodeId}/neighbors (graph service, Neptune) | 2,015 | 449 | 1,012 | 526 `CIRCUIT_OPEN` | 28 `DOWNSTREAM_FAILURE` |
| GET /v1/evidence/{evidenceId} (evidence service, DynamoDB) | 236 | 120 | 116 | 0 | 0 |

- ok per level, neighbors / evidence: L1 404 / 47; L2 12 / 22; L3 9 / 25; L4 24 / 26.
- 502 per level: 3 / 7 / 18. Each level's first failures are 502s, followed by 503 CIRCUIT_OPEN (first 502 at 00:18:52, first 503 at 00:18:53).
- ok throughput (by hand): 3.76, 1.42, 2.62 and 5.0 per second.
- One slow ok in the ID check: 3.18 s for one neighbors ID at 00:15:47.

Task counts: 78 samples, **all 1/1/0** for query, evidence and graph; LiveTaskCount 1 throughout.

CloudWatch:
- Graph target group: target 5XX per period 76 (00:18), 93 (00:19), 210 (00:20), 175 (00:21); total 554 = 526 + 28. RequestCount up to 219 per minute. TargetResponseTime p95 per period 0.002 to 1.99 s (1.99 s at 00:14, during the ID check).
- Evidence target group: p95 at most 0.081 s.
- Graph service CPU at most 8.57 % (00:15) and memory at most 25.5 %. Evidence service CPU at most 1.58 %.
- DynamoDB evidence-bundle: GetItem latency 1.1 to 8.7 ms; Query latency 1.4 to 20.4 ms.
- Neptune: CPU maximum 23.0 % (00:17); MainRequestQueuePendingRequests 0. TotalServerErrorsPerSec 0.050/s (00:19, about 3), 0.117/s (00:21, about 7) and 0.300/s (00:22, about 18).

Interpretation (labelled):
- The API Gateway stage throttle (5 rps, burst 10; decision 3) dominates every level above 1 client.
- Separately, the **graph route** fails at the Neptune layer as soon as 5 clients run. The graph service returns 502 `DOWNSTREAM_FAILURE`, then its circuit breaker answers 503 `CIRCUIT_OPEN`. The evidence route (DynamoDB) has no 5xx at any level.
- Neptune's server-error counts (about 3, 7, 18) equal the per-level 502 counts (3, 7, 18). For levels 3 and 4 they appear one 60 s period later than the tool's level minute, while the ALB series line up with the tool exactly.
- Neptune CPU (at most 23 %) and its request queue (0) show no saturation. The cause is not determined from these files: candidates include a connection or concurrency limit of the db.t4g.medium instance or of the client pool, or the query itself. This is a measured outcome, not an experiment defect.
- The same metric shows about one Neptune server error in the 23:51 period, the minute of D6's q13 re-run at 23:50:57, whose recorded abstention reason was `path-query-failed` ("graph store failure", runbook-log row D6). This is consistent with, not proof of, a Neptune-side error behind G-28.

#### Spec items, D-read (no LLM)

p50/p95/p99, throughput, error rate, ECS task count, CPU and memory: covered. Neptune latency: missing as a separate measure (the neighbors route's end-to-end ok latency is the closest proxy: p50 about 0.2 s at 1 client). DynamoDB throttles: missing (no datapoints). Vector and LLM latency: not applicable (no LLM, no vector route).

### 4.3 D-read attempt 2 (db0d): failed, kept

- tool: **failed** (runner exit 5)
- action: **failed** (collector exit 3, class 2 temporary unmet prerequisite: `SCHOLARCHAIN_SERVICE_API_KEY is not set in this shell`, step `key`; action window 00:22:55 to 00:22:58; no request sent; no load files, no clock file)
- metrics: **none** (run record `metrics.status` not-run)
- observation: **not applicable**.
- Files: action.log, manifest.json, run-record.json, run-record.started.json, tool-record.json (status prerequisite-not-met), service-samples.csv (6 rows, all 1/1/0).
- Recorded as a human manual intervention: started by accident while attempt 1's runner was still in its metrics wait.
- Two runner facts to note for infra-engineer (minor, no repair asked here):
  - The run record says `failed_step` "metrics-settle" and `signal` "INT" with exit 5. The runner was interrupted during its settle wait, while the decision 10 table lists signal or TERM endings under exit 7.
  - This folder's files are not read-only (-rw-------), unlike the other five folders (-r--------). Immutability of this folder rests on the git commit ee3bce8.

## 5. Experiment C: embedding backlog (p15-c-embedding-backlog, attempt ab26)

### 5.1 Evidence status

- tool: **ran** (runner exit 0 by the log's `RESULT ok`, runbook-log row D12)
- action: **succeeded**
- metrics: **complete** (15 of 15 series with data; derived `missing.csv` empty)
- observation, three results kept apart as D12 asks:
  1. **spec gate, at least one scale-out and one scale-in of the worker: met.** Service samples 0 → 1 → 2 → 0 **and** scaling activities: out (23:01:25 to 23:01:56, Successful), out (23:04:25 to 23:04:58, Successful), in (started 23:24:39, status InProgress when recorded).
  2. **extended objective 0 → 1 → 2 → 0: reached.**
  3. **C fully observed: seen**, with two gaps: the scale-in activity's end status was not recorded, and the worker's in-process counters and Voyage token usage are not in the raw folder (5.4).
- Evidence: `service-samples.csv`, `scaling-activities.csv`, `queue-samples.csv`, `sends.csv`, `worker-outcomes.csv`, `metrics/metric-data.json`; derived `experiments/derived/out/p15-c-embedding-backlog/` (observed.csv, samples.csv, queue SVG; inputs.csv sha256 = raw files).

### 5.2 Measured facts

Setup:
- N = 19,200 keys of researcher-profile-v1, the first N unique keys in ArtifactRegistry scan order (20 scan calls, 98,000 items scanned; keys sha256 57c3981c…).
- Registry sample 20 of 20 eligible (L-02 model check).
- Settle: met on poll 1 (22:57:32). Baseline embedding DLQ 1 (after F), alarm ALARM.
- Sends: 19,200 ok, 0 failed, 1,920 batch calls in 1,164 s (22:58:11 to 23:17:35; 16.5 messages/s).

Timeline:

| Event | UTC | Source | Lag |
|---|---|---|---|
| first send | 22:58:11 | tool-record `first_send_epoch_s` | 0 |
| backlog first seen (visible 280) | 22:58:49 | queue-samples.csv | +38 s |
| scale-out 1 activity start | 23:01:25 | scaling-activities.csv | +194 s |
| desired 1 (pending 1) | 23:01:39 | service-samples.csv | +208 s |
| running 1 | 23:01:58 | service-samples.csv | +227 s |
| scale-out 2 activity start | 23:04:25 | scaling-activities.csv | +180 s after the first (cooldown 120 s) |
| desired 2 | 23:04:26 | service-samples.csv | |
| running 2 | 23:05:03 | service-samples.csv | |
| backlog peak, visible 5,370 (in flight 40) | 23:05:39 and 23:06:17 | queue-samples.csv (CloudWatch max 5,220 in 23:05) | |
| last send | 23:17:35 | 1,164 s after the first | |
| queue empty, first 0/0 sample | 23:18:21 | queue-samples.csv | +46 s after the last send |
| scale-in activity start | 23:24:39 | scaling-activities.csv | +378 s after empty |
| worker 0/0 | 23:24:59 | service-samples.csv | +398 s after empty |

Drain time (three definitions, all measured):
- (a) first send to queue empty, the collector's poll: **1,214 s** (`drain_seconds`).
- (b) backlog first seen to the first empty sample: **1,172 s** (derived observed.csv).
- (c) last send to empty: about 46 s (±19 s sampling).
- First send to worker back at 0: 1,608 s. Collector `scale_in_after_drain_seconds` 392.

Throughput: SQS NumberOfMessagesDeleted per minute (CloudWatch, by hand):

| Phase | Periods | Deleted per minute | Rate |
|---|---|---|---|
| 1 task running | 23:02 to 23:04 (3 full minutes) | 590, 490, 540 (mean 540) | about 9.0 messages/s |
| transition | 23:05 | 950 | – |
| 2 tasks running | 23:06 to 23:16 (11 full minutes) | 1,180 to 1,748 (mean 1,439) | about 24.0 messages/s |
| tail | 23:17 | 798 | – |
| overall | first running (23:01:58) to empty (23:18:21), 983 s | 19,200 total (derived mean 982.9 per minute) | about 19.5 messages/s |

Counts and retries (CloudWatch sums over the run, by hand):
- Sent 19,200 = Received 19,200 = Deleted 19,200, so no redelivery was counted. **Retry count 0.**
- Embedding DLQ visible 1 in every period = its baseline. **New dead letters 0.**
- Empty receives began at 23:17 (65, then about 199 per minute while 2 tasks idled).
- Query-session DynamoDB: writes 19,200 units and reads 19,200 units.
- Job queue 0 throughout.
- Worker CPU average 29.2 % at 23:02, then 3.7 to 14.2 % while processing. Memory at most 23.4 %.
- LiveTaskCount: 1 (23:01 to 23:04), 2 (23:05 to 23:24).

U-11 (counters exact under load):
- `worker-outcomes.csv`: `processed` 19,200 lines with 19,200 distinct taskIds = `sent_ok` 19,200. No `replayed` and no failure outcome lines; the tool lists 1 outcome kind.
- These agree exactly with SQS Deleted (19,200) and with the query-session writes (19,200 units). Interpretation: one completion record per task.
- Status: **observed exact for the per-delivery log lines and the three independent counts.** The worker's in-process counters (received, processed, replayed, permanent, transient; decision 7.2) are not exported into the raw folder, so their live values are **missing**.

Interpretation (labelled):
- The send rate (16.5 messages/s) was above one task's rate (about 9/s) and below two tasks' rate (about 24/s). The backlog grew until the second task ran, then shrank while sends continued.
- So C measures drain **under concurrent enqueue**. Its drain time (1,214 s) is bounded below by the send duration (1,164 s), and it is not the time to drain a pre-filled backlog.
- That two tasks reached about 2.7 times one task's rate suggests start-up effects in the first minutes. One task's figure rests on 3 minutes only.
- Workload failures (bad selected documents): 0.
- Write rate of the re-embed path only (G-27 rule): about 24 vectors/s at 2 tasks. This is not a first-ingest rate.

### 5.3 Spec items covered by C

| Spec item | Status | Evidence |
|---|---|---|
| controlled number of text chunks (N existing documents; phase note) | covered: N = 19,200 | tool-record.json, sends.csv |
| SQS queue depth | covered | queue-samples.csv; CloudWatch visible, not visible, oldest age (max 351 s at 23:06 and 23:07) |
| embedding-worker task count | covered: desired and running every 15 s, plus scaling activities | service-samples.csv, scaling-activities.csv |
| throughput | covered | CloudWatch Deleted per minute |
| retry count | covered: 0 (Received = Sent = Deleted), DLQ = baseline | metric-data.json |
| drain time | covered (three definitions) | tool-record.json, observed.csv |
| scaling-rule row "Embedding workers" (queue depth, desired and running, drain time) | covered | as above |

### 5.4 Failures and gaps (C)

- No failure.
- Gaps:
  - The scale-in activity is recorded only as `InProgress`, because the collector stops when the worker is back at 0. Its end time and status are missing. My aws-api read of the scaling activities was denied (`ViewOnlyAccess` has no `application-autoscaling:DescribeScalingActivities`). A one-line read-only command for the human is under MANUAL ACTION REQUIRED.
  - Worker in-process counters and Voyage token counts are not captured.

## 6. Spec gate items as far as F, E, C, D carry them (judged on observations, not exit codes)

| Gate item | From these four | Evidence |
|---|---|---|
| at least one scale-out and one scale-in | **met by C** (0 → 1 → 2 → 0; samples and activities). F: scale-out only in its own data. D: no scale-out (expected). E: not applicable. | C service-samples.csv, scaling-activities.csv |
| synchronized timestamps | **met as measured**: offsets 0 to +0.5 s, ±1.07 to ±1.09 s, in 60 s periods (section 1). D-read attempt 2 has no clock (sent nothing). | clock.csv, load-clock.csv |
| raw data preserved | **met**: all six attempt folders are tracked (13, 11, 17, 13, 13 and 6 files) at the commits in section 0; C derived output tracked (5 files, ff00cfa) | git ls-files |
| costs and limits stated | limits: each run record's `declared_not_enforced` (cost bound, duration, resource limits, Terraform sources). Costs: section 7 (estimates). Billing numbers are pending (D22 and D23). | run-record.json |
| failures reported | D-read attempt 2 (failed, kept); F's D9a preflight failure behind L-02; no failure in E, F b4c0, C or D-query | section 0, 2.6, 4.3 |

Scaling-rule table rows these runs touch:
- **Embedding workers:** C, covered.
- **Graph retriever** (graph service): task count, CPU and p95 covered in D-read; the latency alarm was not examined; no scale-out.
- **Vector retriever:** there is no separate vector service. Task count of the query service is covered; query latency is partial (semantic stage); errors are covered.
- **Rank and synthesis** (query service): task count, p95 and LLM latency covered; queue: not applicable.
- **DynamoDB:** consumed capacity covered; throttles missing (no datapoints).
- **Step Functions and SQS:** retries and dead letters covered by F (and C's 0). Step Functions execution states come from A and B, not here.

## 7. Cost per experiment (all **estimates**)

Basis: infra/terraform/COSTS.md rates, which are themselves estimates; measured counts from the raw files; the manifests' per-query LLM basis (about $0.0023 per query, Phase 10 attempt 06). The always-on runtime (Fargate minimum about $0.079/h, ALB, NAT) is not attributed per experiment; it is in the orchestrator's runtime-hours line.

| Attempt | Estimate | Basis (measured count × rate) | Manifest bound |
|---|---|---|---|
| E b6c7 | about $0.01 | ops task about 62 s at about $0.05/h (under $0.001); 11 Voyage query embeddings, 254 tokens (free allowance); about 126 Neptune openCypher requests and 36 DynamoDB read units (negligible) | $0.20 |
| F b4c0 | about $0.01 | 1 LLM query (part 3 first request; the replay is served from storage and the 409 runs no query) about $0.0023; worker 1 task from about 22:41:34 to its scale-in about 22:49 (live read), about 0.13 task-h × $0.04 ≈ $0.005; 2 Voyage embeddings (free); 3 SQS messages, 22 DynamoDB write units (negligible) | $0.10 |
| C ab26 | about $0.08 to $0.10 | worker about 23.3 + 20.6 task-minutes ≈ 0.73 task-h × $0.04 ≈ $0.03; DynamoDB 19,200 write units × $0.625/M ≈ $0.012 and 19,200 read units plus the 98,000-item registry scan (≤ about 0.05 M read units) × $0.125/M ≈ $0.01; S3 Vectors PutVectors (manifest estimate) about $0.02; SQS about 1,920 sends + receives + deletes + about 1,400 empty receives (free tier or under $0.01); Voyage about 1.7 M tokens (manifest estimate; actual tokens not captured) inside the free allowance | $0.50 |
| D-query ca0c | about $0.10 | 40 answered queries × $0.0023 ≈ $0.09 (measured tokens: 187,187 input, 104,982 output); the 5 timed-out queries may have used model calls, up to about +$0.012; 101 overloaded and 3 throttled answers made no model call (interpretation); 40 explain reads, ALB and API Gateway requests under $0.001 | $1.00 |
| D-read b3f0 | about $0.01 | 2,251 + 29 API requests × $1/M ≈ $0.002; about 470 Neptune openCypher requests (I/O within the trial or negligible); about 126 DynamoDB read units | $0.20 |
| D-read db0d | $0 | no request sent, no metric export | $0.20 |
| metric exports (all five) | under $0.002 | 100 metrics requested in total (16 + 8 + 15 + 34 + 27) | – |
| **Total F, E, C, D** | **about $0.21 to $0.24** | | sum of bounds $2.20 |

## 8. Missing items (stay missing, never 0)

- F: scale-in not in its window; no scaling-activity records (by design); user-visible impact not measured.
- E: per-stage Neptune time inside graph-only not measured separately; no ECS series for the ops task (not a service).
- C: the scale-in activity's end status; the worker's in-process counters; Voyage token usage of the re-embed.
- D-query: Neptune latency; DynamoDB `ThrottledRequests` ×2 and `ReadThrottleEvents` (no datapoints); ALB ELB 5XX (no datapoints); vector-query latency only partial (n = 7, embedding and search together).
- D-read: Neptune latency; DynamoDB `ThrottledRequests` and `ReadThrottleEvents` (no datapoints); ALB evidence-target 5XX and ELB 5XX (no datapoints).
- D-read attempt 2: clock, requests and metrics (nothing ran).

## 9. Findings for routing (observations, not repairs; the orchestrator routes them)

- **FECD-1 (major, measured outcome; phase-16 candidate; owners for diagnosis: storage-engineer for the Neptune adapter, service-engineer for the circuit breaker).** The graph neighbors route returns 502 `DOWNSTREAM_FAILURE` and then 503 `CIRCUIT_OPEN` from 5 concurrent clients onward: 28 and 526 of 2,015 requests. Neptune server errors equal the 502 counts per level (3, 7, 18), while Neptune CPU is at most 23 % and its queue is 0. The evidence route has no 5xx. This possibly relates to G-28 (one Neptune server error in the 23:51 period of the q13 re-run). Evidence: D-read load-requests.csv, metric-data.json.
- **FECD-2 (minor, hypothesis; owners service-engineer or rag-engineer).** In D-query level 3 (4 clients, equal to the 4 permits), 48 OVERLOADED answers began right after 4 `WORKFLOW_TIMEOUT` 504s at about 25.2 s. A timed-out workflow may keep its permit. Evidence: D-query load-requests.csv.
- **FECD-3 (minor, measurement limit; experiment-engineer and documentation).** Explain is keyed by a deterministic query ID per question, so per-stage latencies show only the latest execution of a question. This is to be stated in the results summary's limitations.
- **FECD-4 (minor; infra-engineer).** The D-read attempt 2 run record has exit 5 with `signal` INT and `failed_step` metrics-settle, against the decision 10 exit table (signal or TERM endings under 7), and its folder was not made read-only.
- **FECD-5 (minor; experiment-engineer, later).** The C collector stops before the scale-in activity ends, so its end status is missing. A short post-zero poll of the activity would close it.
- **FECD-6 (limitation, documentation).** C's drain time was measured under concurrent enqueue and is bounded by the send duration (section 5.2).
- **FECD-7 (minor; experiment-engineer, later).** F's API event epochs appear to be end times; the collector notes should say which.
- **FECD-8 (limitation).** The Neptune per-minute series differ from the tool's minutes by up to one period (D-read); the ALB series line up. Neptune values are attributed to levels with this caveat.
- **FECD-9 (limitation).** U-11 in-process counters are not exported. Live exactness rests on log lines, SQS and DynamoDB counts agreeing (19,200 each).

---

STATUS: COMPLETE
OWNER: experiment-engineer
WORK ORDER: docs/agent-runs/phase-15/prompt.md (sha256 3d806922c6dd74ada61bceca45d3a302740a4083f260f0ae3ca6b6b6b9dfa3e0), session 3 step D16, analysis report for experiments F, E, C and D (read-only)
FILES CREATED: /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/8119bd02-01ec-478f-b947-f2f035600de8/scratchpad/phase15-reports/agent-report-experiment-FECD.md (scratchpad only; no repository file)
FILES MODIFIED: none
COMMANDS RUN: read-only git (rev-parse, status --short, ls-files, log -1); shasum -a 256 (work order, manifests, raw files); ls -la; wc -l; head -n; cut; jq empty (20 run and tool records, all ok); grep -c / grep -n / grep -o / grep -m on raw files. Commands not on the work order's list, all read-only and writing nothing: grep in general (the Grep tool was not available in this session, so bash grep stood in for it), one `sed -n` and one `sort | uniq -c` inside read-only pipes, and shell `for` loops around grep, jq and git. No approval prompt was seen. No script, python, bash script, aws CLI or sbt was run. aws-api: 2 read-only calls of the 40 allowed: (1) `application-autoscaling describe-scaling-activities` for the worker: AccessDenied (not in ViewOnlyAccess; the error text named an ARN and account, which are not copied here); (2) `cloudwatch get-metric-statistics` AWS/ECS LiveTaskCount for the worker, 22:44 to 23:00: ok (section 2.4).
TEST RESULTS: not applicable (no source change; no tests run)
VALIDATION RESULTS: work order sha256 matches; each manifest's sha256 matches its run record (a39c7e45, be3868df, 36352e8f, 2af43f86, d851716d); raw file hashes match their tool-record entries (incident-F.json a75efa8a…, retrieval-eval-report.json cadd90b8…, C metric-data.json 82d5ddc9… = derived inputs.csv); jq empty ok on 20 JSON records; all six attempt folders and the C derived folder are tracked; git status --short empty before and after.
ASSUMPTIONS: CloudWatch period timestamps are period starts; "no datapoints" means missing, not zero. Per-query LLM cost uses the manifests' $0.0023 basis (the Haiku rates of Phase 10), not a rate I verified. The F worker scale-in time comes from a live read outside the raw folder and is labelled so. Section 0's commit column comes from `git log -1` per folder.
RISKS: by-hand statistics (synthesis, planner and semantic percentiles, CloudWatch sums, per-route counts) are not yet produced by a committed derive script, and D23 should reproduce them. E's sample is 11 questions (exploratory). C's drain time is not a pre-filled-backlog drain. FECD-1 may affect the demo's graph route under concurrent use.
MANUAL ACTION REQUIRED: optional, to close C's scale-in end status (read-only, prints no ARN or account): `aws application-autoscaling describe-scaling-activities --service-namespace ecs --resource-id service/scholarchain-course-runtime/worker --region us-east-1 --max-items 10 --query "ScalingActivities[].[StartTime,EndTime,StatusCode]" --output text`. The output is a live read and goes in the results summary as such, never into the raw folder.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: orchestrator reads this report for D18/D22; D23 re-runs the derive scripts (deterministic output) and the results summary takes these tables, the missing list and the FECD findings; the human gate decides routing of FECD-1 to FECD-9.

## Manifest IDs
p15-f-poison-replay, p15-e-retrieval-modes, p15-c-embedding-backlog, p15-d-query-load, p15-d-read-load.

## Run IDs
F 20261120T223908Z-e94df52ca8fb-b4c0; E 20261120T221402Z-6a6c31a06d60-b6c7 (nested load 20261120T221406Z-6a6c31a06d60-2968); C 20261120T225730Z-eb786c5275a3-ab26; D-query 20261120T235521Z-ff00cfa278c8-ca0c; D-read 20261121T001529Z-abe4764a8293-b3f0 (succeeded) and 20261121T002254Z-abe4764a8293-db0d (failed).

## Raw-data locations
experiments/raw/p15-f-poison-replay/20261120T223908Z-e94df52ca8fb-b4c0/; experiments/raw/p15-e-retrieval-modes/20261120T221402Z-6a6c31a06d60-b6c7/; experiments/raw/p15-c-embedding-backlog/20261120T225730Z-eb786c5275a3-ab26/; experiments/raw/p15-d-query-load/20261120T235521Z-ff00cfa278c8-ca0c/; experiments/raw/p15-d-read-load/20261121T001529Z-abe4764a8293-b3f0/ and .../20261121T002254Z-abe4764a8293-db0d/; derived experiments/derived/out/p15-c-embedding-backlog/.

## Scale-out and scale-in evidence
- C: out 23:01:25 (Successful), out 23:04:25 (Successful), in 23:24:39 (InProgress when recorded); samples 0 → 1 (23:01:39) → 2 (23:04:26) → 0 (23:24:59).
- F: out 0 → 1 (22:41:34, samples). Scale-in not in F's window (live read: no LiveTaskCount datapoint after the 22:48 period).
- D: none (flat 1/1, CPU far below 60 %).
- E: not applicable.

## Failures
- D-read attempt 2 (class 2, key not exported, nothing sent).
- F's D9a preflight (exit 5, collector exit 3, model-mismatch) before L-02.
- No failures in E, F b4c0, C or D-query.

## Cost
About $0.21 to $0.24 for F, E, C and D together (estimate, section 7), against $2.20 of manifest bounds.

## VALIDATION EVIDENCE
- `shasum -a 256 docs/agent-runs/phase-15/prompt.md` → 3d806922c6dd74ada61bceca45d3a302740a4083f260f0ae3ca6b6b6b9dfa3e0 (matches)
- `git rev-parse --short HEAD` → d872d12; `git status --short` → empty (before the first read and after the last)
- manifest sha256 prefixes: c 36352e8fcdf6287a, d-query 2af43f86a39c31b0, d-read d851716dfbcd909f, e be3868dff8802e0c, f a39c7e453f3c9264 (= run records)
- raw sha256 prefixes: F run-record 4b3788d2e2ab4102, incident-F a75efa8af7e9bbfb; E run-record df33261cf29eaeac, report cadd90b82da7fa8c; C run-record 5704ecd3f7d6f4f5, metric-data 82d5ddc9c538d8d4; D-query run-record 993bfa3182688f7f, load-explain 53273fe7d01893e1; D-read b3f0 run-record 7ea8a7c543b95788, load-requests a16d17290b3ecb76; D-read db0d run-record 379ccbc0b671d0fc
- `jq empty` on 20 run-record.json and tool-record.json files under experiments/raw/p15-*/ → 20 ok
- `git ls-files` counts: F 13, E 11, C 17, D-query 13, D-read b3f0 13, D-read db0d 6, C derived 5
- metric series with data / queried: F 16/16, E 8/8, C 15/15, D-query 30/34, D-read 23/27
files changed after these runs: none
