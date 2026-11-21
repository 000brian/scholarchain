# ScholarChain Phase 15 results summary

Owner: `experiment-engineer`. Written at D23 (decision 13) from `experiments/results-summary-template.md`, read-only from the committed raw folders (`experiments/raw/**`), the committed derived outputs (`experiments/derived/out/**`, commit 08bbb9d) and the run, release and runbook records. Analysis reports: `docs/agent-runs/phase-15/agent-report-experiment-FECD.md` (F, E, C, D; D16) and `agent-report-experiment-AB.md` (A, B; D22; copied there by the human).

Rules used throughout:
- **Measured** = read from a committed file (named). **Interpretation** = labelled and kept in its own paragraph. **Estimate** = labelled, with its basis. "by hand" = summed or computed by the analyst from the named committed file; no committed derive script prints that value (proposed later, section 8).
- A value that does not exist is `missing` (with the reason), never 0. A CloudWatch series with no datapoints is **"no datapoints"**, never a measured 0.
- All times UTC. CloudWatch values are 60 s periods labelled by their start.
- Status words: step status `done | not needed | skipped | failed | not run`; requirement status `done | defect (ID) | deviation | missing | phase-16 | phase-17 | blocked-on-human`. For every experiment, three things stay apart: **execution** (step status by last attempt), **measured outcome**, and **spec gate item**.

## 0. Identity of the phase

| Field | Value | Source |
|---|---|---|
| Work order | `docs/agent-runs/phase-15/prompt.md`, sha256 3d806922c6dd74ada61bceca45d3a302740a4083f260f0ae3ca6b6b6b9dfa3e0 | `shasum -a 256` |
| Session 2 commit (tools, manifests) | 684636ef997ee041912e2b57d1c597ded8e9778d | `git log` |
| Manifest approvals | 8 `APPROVED-MANIFEST-SHA256` lines, commit b0c92f5 (D9); every run record's manifest sha256 equals its line | `docs/agent-runs/phase-15/approvals.md`, run records |
| Build commit (image, jars, `--code-version`) | 684636ef997ee041912e2b57d1c597ded8e9778d (every run record `build_commit`) | package record (runbook-log D1), run records |
| Runtime-on plan and release | plan 934d78c3… / release 20261120T213452Z-94ec6b01a4ca-44da (D3, D4); after repair L-01: plan 235b56f5… / release 20261120T225449Z-70876eb27b26-8ce8 (D3b, D4b; state machine only) | `releases/course/*.json`, runbook-log |
| Runtime-off plan and release | plan 71fe9071… / release 20261121T052512Z-ebcbc03a90bd-01a4 (D19, D20) | runbook-log |
| Region | `us-east-1` | every run record |
| Dataset snapshot | `dblp-2026-11-01` with `dblp.dtd`, subset rule `dblp-subset-v1` | manifests, run records |
| Graph run in the stores | `run:v1:f2625d504b12749389cf4f5b1a26aed4` (Phase 14) | Phase 14 records |
| Clock | Derive script (`experiments/derived/out/p15-d-*/clock.csv`): laptop offset +0.5 to +1.0 s, uncertainty ±1.0 to ±1.5 s (the script takes the middle of the HTTP `Date` second). Hand method of the FECD report (Date minus the send and receive midpoint): 0 to +0.5 s, ±1.07 to ±1.09 s. Every other attempt with a clock file (E, F, C, A, B): 0 s at start, 0 to +0.5 s at end (round trips 0.12 to 0.26 s). CloudWatch data in 60 s periods | `clock.csv` / `load-clock.csv` in each raw folder |

Timestamps claim: "aligned on UTC to within about ±1.5 s, inside 60 s metric periods". This is not millisecond synchronization (decision 10). Both methods agree within their uncertainty.

## 1. Attempt index (every attempt, failed ones included)

`evidence/experiment-runs/` held no started-only record at the sitting 3 resumption check (runbook-log, sitting 3). Ten attempt folders exist, all committed.

| Manifest | Attempt ID | Runner exit | tool | action | metrics | observation (evidence file) | Raw folder (commit) |
|---|---|---|---|---|---|---|---|
| p15-a-hadoop | 20261120T215912Z-b0c92f533c70-5a01 | 5 (action exit 6) | failed | failed | partial (13 EMR series unresolved: no cluster) | not applicable: no cluster (action.log RESULT-CAUSE) | experiments/raw/p15-a-hadoop/20261120T215912Z-b0c92f533c70-5a01/ (6a6c31a) |
| p15-e-retrieval-modes | 20261120T221402Z-6a6c31a06d60-b6c7 | 0 | ran | succeeded | complete (8 of 8) | seen, exploratory (retrieval-modes.csv) | …/p15-e-retrieval-modes/20261120T221402Z-6a6c31a06d60-b6c7/ (2cc5804) |
| p15-f-poison-replay | 20261120T223908Z-e94df52ca8fb-b4c0 | 0 | ran | succeeded | complete (16 of 16) | seen (events.csv, incident-F.json) | …/p15-f-poison-replay/20261120T223908Z-e94df52ca8fb-b4c0/ (70876eb) |
| p15-a-hadoop | 20261120T225632Z-eb786c5275a3-5c75 | 0 | ran | succeeded | partial by design (5 node series with no datapoints, no policy) | flat baseline seen (capacity.csv) | …/p15-a-hadoop/20261120T225632Z-eb786c5275a3-5c75/ (d872d12) |
| p15-c-embedding-backlog | 20261120T225730Z-eb786c5275a3-ab26 | 0 | ran | succeeded | complete (15 of 15) | seen: 0 → 1 → 2 → 0 (service-samples.csv, scaling-activities.csv) | …/p15-c-embedding-backlog/20261120T225730Z-eb786c5275a3-ab26/ (e86449a) |
| p15-a-spark | 20261120T225850Z-eb786c5275a3-4f7e | 0 | ran | succeeded | partial by design (same 5 series) | flat baseline seen (capacity.csv) | …/p15-a-spark/20261120T225850Z-eb786c5275a3-4f7e/ (e86449a) |
| p15-b-spark-burst | 20261120T225913Z-eb786c5275a3-fac6 | 0 | ran | succeeded | complete (18 of 18 queried) | scale-out seen; scale-in while running not seen (capacity.csv) | …/p15-b-spark-burst/20261120T225913Z-eb786c5275a3-fac6/ (e86449a) |
| p15-d-query-load | 20261120T235521Z-ff00cfa278c8-ca0c | 0 | ran | succeeded | partial (30 of 34: 4 series no datapoints) | seen; ECS scale-out not seen, not expected (levels.csv) | …/p15-d-query-load/20261120T235521Z-ff00cfa278c8-ca0c/ (abe4764) |
| p15-d-read-load | 20261121T001529Z-abe4764a8293-b3f0 | 0 | ran | succeeded | partial (23 of 27: 4 series no datapoints) | seen; ECS scale-out not seen, not expected (levels.csv) | …/p15-d-read-load/20261121T001529Z-abe4764a8293-b3f0/ (ee3bce8) |
| p15-d-read-load | 20261121T002254Z-abe4764a8293-db0d | 5 (action exit 3) | failed | failed | none (`not-run`) | not applicable: nothing sent (tool-record status prerequisite-not-met) | …/p15-d-read-load/20261121T002254Z-abe4764a8293-db0d/ (ee3bce8) |

Failures outside attempt folders, reported too: D9a `--check` of p15-f-poison-replay exited 5 (collector preflight exit 3, `model-mismatch`, nothing sent, no folder). This led to repair L-02 before F ran. Run order differed from the plan: E (D11) ran before F (D10) (runbook-log "Order change").

## 2. Per experiment

### 2.1 p15-f-poison-replay (experiment F)

**Manifest:** `experiments/manifests/p15-f-poison-replay.json`, sha256 a39c7e453f3c9264…, approved b0c92f5. One poison message (unknown index), one valid task sent twice, one API idempotency replay.

| Item | Value | Source |
|---|---|---|
| Declared limits | `costBoundUsd` 0.1, `maxDurationSeconds` 4800 | manifest, run-record `declared_not_enforced` |
| Real caps | embedding `maxReceiveCount` 5 (infra/terraform/modules/workflow/variables.tf:138-141), visibility 300 s; `worker_max_count` 2 (modules/runtime/variables.tf:115-118) | tool-record, Terraform |
| Tool sha256 | 02a5b0ed… (f-poison-replay.sh after L-02) | tool-record.json |

```text
20261120T223908Z-e94df52ca8fb-b4c0
  tool: ran (runner exit 0)
  action: succeeded (run-record.json)
  metrics: complete (16 of 16 series)
  observation: seen - retry, dead letter, worker and API idempotency, recovery (events.csv, incident-F.json, queue-samples.csv, alarm-samples.csv)
  HEAD e94df52, build 684636ef, 22:39:08 to 22:49:56 UTC (action 22:39:08 to 22:44:55)
```

**Measured results** (`experiments/derived/out/p15-f-poison-replay/parts.csv`, `timeline.csv`; FECD section 2):

| Part | Result |
|---|---|
| poison | sent 22:39:20; embedding DLQ = baseline + 1 at 22:41:57; poison taskId read back 22:41:58 (never deleted); CloudWatch Received 6 in the 22:41 period (5 poison receives + the valid task) |
| sequential replay | worker log `processed` 22:41:47, completion record present; duplicate sent 22:42:01, worker log `replayed` 22:42:01 |
| API idempotency | 200 (16.82 s), replay 200 with `Idempotent-Replayed: true` (0.177 s, same query ID), changed body 409 `IDEMPOTENCY_CONFLICT` |
| recovery | valid task processed while the poison was retried; in flight back to 0 at 22:42:45; DLQ alarm in ALARM at 22:44:50 (detection source) |
| incident record | `incident-F.json` (sha256 a75efa8a…) holds every spec incident field; user-visible impact "not measured by this tool" |
| side observation | worker scale-out 0 → 1 (desired 1 at 22:41:34, running 1 at 22:41:53); scale-in not in F's window |

**Plots:** `experiments/derived/out/p15-f-poison-replay/queue-20261120T223908Z-e94df52ca8fb-b4c0.svg`, `timeline-20261120T223908Z-e94df52ca8fb-b4c0.svg`.

**Evidence sources:** CloudWatch: SQS sent, received, deleted, visible, oldest age, DLQ; ECS CPU, memory, LiveTaskCount. Tool records: events, worker log lines, completion record, describe-services samples, alarm samples. Missing: `scaling-activities.csv` (F does not collect it, by design; derived missing.csv).

**Failures:** none in this attempt. The earlier D9a preflight failure (L-02) is listed in section 1.

**Cost:** about $0.01 (estimate, FECD section 7: 1 LLM query about $0.0023; worker about 0.13 task-hours × $0.04).

**Interpretation:** all five poison receives fall within the 22:41 period, so the release-at-once rule worked. Without it the poison would take about 5 × 300 s to reach the DLQ. DLQ "received 10" in 22:41 is most likely the collector's own read-back (unverified).

### 2.2 p15-e-retrieval-modes (experiment E), EXPLORATORY

**Manifest:** sha256 be3868dff8802e0c…, approved b0c92f5. One `rag-retrieval-eval` ops task, no LLM call. Three modes over the benchmark-v1 universe.

| Item | Value | Source |
|---|---|---|
| Declared limits | `costBoundUsd` 0.2, `maxDurationSeconds` 3600 | manifest |
| Tool sha256 | cb15ece7… (e-retrieval-modes.sh) | tool-record.json |

```text
20261120T221402Z-6a6c31a06d60-b6c7
  tool: ran (runner exit 0)
  action: succeeded (nested load record 20261120T221406Z-6a6c31a06d60-2968, ops container exit 0)
  metrics: complete (8 of 8: AWS/Neptune x5, AWS/DynamoDB x3; the ops task has no ECS service series)
  observation: seen - per-mode comparison, EXPLORATORY (retrieval-modes.csv, retrieval-eval-report.json sha256 cadd90b8…)
  HEAD 6a6c31a, build 684636ef, 22:14:02 to 22:20:15 UTC
```

**Measured results** (`experiments/derived/out/p15-e-retrieval-modes/modes.csv`). Universe: 11 questions, 100 labels; not-comparable 19; same questions and K for every mode; nDCG not computed (no graded labels). **Exploratory: universe 11 < 15.**

| Mode | recall@1 | @5 | @10 | @30 | MRR | errors | empty | abstentions | by-design-weak | latency p50 / p95 ms |
|---|---|---|---|---|---|---|---|---|---|---|
| graph-only | 0.100 | 0.500 | 0.818 | 0.818 | 0.818 | 0 | 2 | 2 | 2 | 31 / 970 |
| vector-only (S3 Vectors) | 0.000 | 0.000 | 0.000 | 0.009 | 0.003 | 0 | 5 | 0 | 9 | 204 / 879 |
| fused (experiment only; the production workflow has no fused ranking) | 0.091 | 0.409 | 0.636 | 0.827 | 0.776 | 0 | 0 | 0 | 0 | 246 / 1608 |

No OpenSearch tier exists (not implemented).

**Plots:** `experiments/derived/out/p15-e-retrieval-modes/recall-20261120T221402Z-6a6c31a06d60-b6c7.svg`, `mrr-20261120T221402Z-6a6c31a06d60-b6c7.svg`.

**Evidence sources:** tool's report (primary); CloudWatch Neptune and DynamoDB support it. Missing: none (derived missing.csv empty).

**Failures:** none. **Cost:** about $0.01 (estimate: ops task about 62 s; 254 Voyage tokens, free allowance).

**Interpretation (exploratory, n = 11):** graph-only ranks a labelled researcher first on 9 of 11 questions. It is empty on the two topic questions by design (`topic-candidates-failed`). Vector-only finds almost none. Fused removes the empty results and keeps MRR near graph-only, but lowers recall@5 and @10. One question moves MRR by about 0.09. No ranking of modes is claimed.

### 2.3 p15-c-embedding-backlog (experiment C)

**Manifest:** sha256 36352e8fcdf6287a…, approved b0c92f5. N = 19,200 existing researcher-profile-v1 documents re-embedded under their existing keys. No `-2` manifest was needed (the gate was met on attempt 1).

| Item | Value | Source |
|---|---|---|
| Declared limits | `costBoundUsd` 0.5, `maxDurationSeconds` 7700; collector wait limit 5,500 s | manifest, runbook-log |
| Real caps | worker 0 to 2 tasks (`worker_max_count` 2, modules/runtime/variables.tf:115-118; autoscaling.tf:36); step scaling, cooldowns 120 s and 60 s (autoscaling.tf:88, :109); scale-in after `worker_scale_in_minutes` with both queues empty (autoscaling.tf:166-167) | Terraform |
| Tool sha256 | 5a9e8951… (c-embedding-backlog.sh after L-02) | tool-record.json |

```text
20261120T225730Z-eb786c5275a3-ab26
  tool: ran (runner exit 0)
  action: succeeded
  metrics: complete (15 of 15)
  observation: seen - (1) gate: scale-out and scale-in shown by samples AND scaling activities; (2) extended objective 0 -> 1 -> 2 -> 0 reached; (3) fully observed, with gaps listed below (service-samples.csv, scaling-activities.csv, queue-samples.csv, worker-outcomes.csv)
  HEAD eb786c5, build 684636ef, 22:57:30 to 23:30:09 UTC (action to 23:25:08)
```

**Measured results** (`experiments/derived/out/p15-c-embedding-backlog/observed.csv`, `samples.csv`; FECD section 5):

| Measure | Value |
|---|---|
| worker desired / running sequence | 0 → 1 → 2 → 0 / 0 → 1 → 2 → 0 |
| scale-out 1 | activity 23:01:25 (Successful); desired 1 at 23:01:39; running 1 at 23:01:58 |
| scale-out 2 | activity 23:04:25 (Successful); desired 2 at 23:04:26; running 2 at 23:05:03 |
| scale-in | activity started 23:24:39 (recorded InProgress); worker 0/0 at 23:24:59 |
| sends | 19,200 ok, 0 failed, 22:58:11 to 23:17:35 (16.5 messages/s) |
| embedding queue visible max | 5,370 (samples); CloudWatch 5,220 in the 23:05 period |
| drain | backlog first seen 22:58:49, empty 23:18:21: 1,172 s (observed.csv); first send to empty 1,214 s (tool-record) |
| throughput (CloudWatch Deleted, by hand) | about 9.0 messages/s with 1 task (3 minutes); about 24.0 with 2 tasks (11 minutes); derived mean 982.9 per minute |
| retries / new dead letters | Sent = Received = Deleted = 19,200 → retries 0; embedding DLQ 1 = its baseline (after F) |
| counters (U-11) | 19,200 `processed` log lines, 19,200 distinct taskIds = SQS deleted = query-session writes; 0 replayed, 0 failures |

**Plots:** `experiments/derived/out/p15-c-embedding-backlog/queue-20261120T225730Z-eb786c5275a3-ab26.svg` (labels small, L-08).

**Evidence sources:** tool records (describe-services samples, scaling activities, queue samples, worker outcomes) are primary. CloudWatch SQS, ECS and DynamoDB support them. Missing: the scale-in activity's end status (the collector stops at worker 0; FECD-5); the worker's in-process counters and Voyage token counts (FECD-9).

**Failures:** none. Workload failures (bad selected documents): 0.

**Cost:** about $0.08 to $0.10 (estimate, FECD section 7: worker about 0.73 task-hours; DynamoDB units; S3 Vectors writes; Voyage within the free allowance).

**Interpretation:** the send rate (16.5/s) lay between the one-task and two-task rates, so C measured drain under concurrent enqueue. The drain time is bounded by the send duration (1,164 s); it is not the drain of a pre-filled backlog (FECD-6). The re-embed write rate (about 24 vectors/s at 2 tasks) applies only to the re-embed path. It is not a first-ingest rate.

### 2.4 p15-d-query-load (experiment D, query)

**Manifest:** sha256 2af43f86a39c31b0…, approved b0c92f5. Levels 1, 2, 4, 8 clients, 120 s each, at most 60 queries per level.

| Item | Value | Source |
|---|---|---|
| Declared limits | `costBoundUsd` 1.0, `maxDurationSeconds` 2400 | manifest |
| Real caps (not raised) | API Gateway stage 5 requests/s, burst 10 (modules/runtime/variables.tf:161-170, ingress.tf:153-154); `maxConcurrentQueries` 4 per query task (config/course.json:98); LLM 19 requests/min per task (config/course.json:79); query, evidence, graph 1 to 2 tasks on 60 % average CPU (autoscaling.tf:35, :65; variables.tf:126-129) | files |
| Tool sha256 | d-load.sh 8932309c…, query-load.sh 05b255ad… | tool-record.json, load-tool-record.json |

```text
20261120T235521Z-ff00cfa278c8-ca0c
  tool: ran (runner exit 0)
  action: succeeded
  metrics: partial - no datapoints: ALB HTTPCode_ELB_5XX_Count; DynamoDB ThrottledRequests (query-session PutItem, evidence-bundle GetItem); ReadThrottleEvents (researcher-metric-cache)
  observation: seen - latency, throughput, error classes per level, explain stages; ECS scale-out not seen (not expected) (load-levels.csv, load-explain.csv, service-samples.csv)
  HEAD ff00cfa, build 684636ef, 23:55:21 to 00:09:22 UTC (load 23:55:24 to 00:04:15)
```

**Measured results** (`experiments/derived/out/p15-d-query-load/levels.csv`; percentiles of curl `time_total`):

| Level (clients) | Attempted | Completed/s | answered | timeout | overloaded | throttled | Error rate | p50 / p95 / p99 all (s) | p50 / p95 / p99 answered (s) |
|---|---|---|---|---|---|---|---|---|---|
| 1 (1) | 10 | 0.079 | 10 | 0 | 0 | 0 | 0 % | 11.73 / 18.51 / 18.51 (few samples) | 11.73 / 18.51 / 18.51 (few samples) |
| 2 (2) | 19 | 0.141 | 18 | 1 | 0 | 0 | 5.3 % | 12.93 / 25.17 / 25.17 (few samples) | 12.03 / 21.55 / 21.55 (few samples) |
| 3 (4) | 60 (cap, 51 s) | 1.176 | 8 | 4 | 48 | 0 | 86.7 % | 0.160 / 25.16 / 25.37 | 11.18 / 17.57 / 17.57 (few samples) |
| 4 (8) | 60 (cap, 16 s) | 3.750 | 4 | 0 | 53 | 3 | 93.3 % | 0.153 / 8.25 / 16.35 | 12.33 / 16.35 / 16.35 (few samples) |

Task counts (`tasks.csv`, 108 samples): query, evidence and graph at desired 1, running 1 throughout. CPU: query at most 9.76 % (CloudWatch). Explain stages (`explain-stages.csv`, 40 rows; FECD 4.1): synthesis (LLM stage) p50 9,656 ms, p95 15,389 ms; planner p50 904 ms; semantic (vector) 7 samples, about 305 to 501 ms (partial); verification p50 5 ms. Neptune latency as a separate measure: **missing** (`explain-coverage.csv`).

**Plots:** `latency-…-ca0c.svg`, `classes-…-ca0c.svg`, `tasks-…-ca0c.svg` under `experiments/derived/out/p15-d-query-load/`.

**Evidence sources:** tool records (requests, explain) are primary. CloudWatch ALB, ECS, DynamoDB and Neptune support them; 60 s periods that span a level boundary are marked `boundary` (`cloudwatch-periods.csv`). Missing: the four no-datapoint series above, Neptune latency, retry counts per query.

**Failures:** none of the tool. 504, 503 and 429 answers are results under the deployed limits, not defects (decision 3).

**Cost:** about $0.10 (estimate: 40 answered queries × about $0.0023, Phase 10 attempt-06 basis; measured tokens 187,187 in, 104,982 out).

**Interpretation:** from 4 clients on (= the 4 permits) the service answers OVERLOADED. The 48 OVERLOADED answers at level 3 began right after 4 workflow timeouts; FECD-2 hypothesizes that a timed-out workflow keeps its permit (unverified). CPU never neared 60 %, so no scale-out.

### 2.5 p15-d-read-load (experiment D, read, no LLM)

**Manifest:** sha256 d851716dfbcd909f…, approved b0c92f5. Levels 1, 5, 10, 20 clients, 120 s each, at most 600 requests per level. Caps and tools as 2.4.

```text
20261121T001529Z-abe4764a8293-b3f0 (attempt 1)
  tool: ran (runner exit 0)
  action: succeeded
  metrics: partial - no datapoints: ALB HTTPCode_ELB_5XX_Count; ALB HTTPCode_Target_5XX_Count (evidence target group); DynamoDB ThrottledRequests and ReadThrottleEvents (evidence-bundle)
  observation: seen - per-level latency and error classes; ECS scale-out not seen (not expected) (load-levels.csv, load-requests.csv, id-check.csv 29/29 ok)
  HEAD abe4764, build 684636ef, 00:15:29 to 00:26:53 UTC

20261121T002254Z-abe4764a8293-db0d (attempt 2, unplanned, failed, kept)
  tool: failed (runner exit 5)
  action: failed (collector exit 3: SCHOLARCHAIN_SERVICE_API_KEY not set in that shell; class 2 temporary unmet prerequisite; no request sent)
  metrics: none (run-record metrics.status not-run)
  observation: not applicable
  HEAD abe4764, 00:22:54 to 00:23:09 UTC; run record failed_step metrics-settle, signal INT (FECD-4); started by accident during attempt 1's metrics wait (manual intervention)
```

**Measured results** (`experiments/derived/out/p15-d-read-load/levels.csv`):

| Level (clients) | Attempted | Completed/s | ok | throttled | overloaded | other-5xx | Error rate | p50 / p95 / p99 all (s) | p50 / p95 / p99 ok (s) |
|---|---|---|---|---|---|---|---|---|---|
| 1 (1) | 451 | 3.758 | 451 | 0 | 0 | 0 | 0 % | 0.198 / 0.307 / 0.455 | 0.198 / 0.307 / 0.455 |
| 2 (5) | 600 (cap, 24 s) | 25.0 | 34 | 397 | 166 | 3 | 94.3 % | 0.139 / 0.186 / 0.282 | 0.161 / 0.394 / 0.397 |
| 3 (10) | 600 (cap, 13 s) | 46.2 | 34 | 356 | 203 | 7 | 94.3 % | 0.138 / 0.191 / 0.278 | 0.160 / 0.342 / 0.382 |
| 4 (20) | 600 (cap, 10 s) | 60.0 | 50 | 375 | 157 | 18 | 91.7 % | 0.152 / 0.280 / 0.541 | 0.206 / 0.505 / 0.623 |

By route (FECD 4.2, by hand): graph neighbors 2,015 requests: 449 ok, 1,012 throttled, 526 `CIRCUIT_OPEN` (503), 28 `DOWNSTREAM_FAILURE` (502). Evidence 236 requests: 120 ok, 116 throttled, no 5xx. Task counts 1/1 throughout (78 samples). Graph service CPU at most 8.57 %; Neptune CPU at most 23.0 %, request queue 0, server errors about 3, 7, 18 per level (= the 502 counts).

**Plots:** `latency-…-b3f0.svg`, `classes-…-b3f0.svg`, `tasks-…-b3f0.svg`, `tasks-…-db0d.svg` under `experiments/derived/out/p15-d-read-load/`.

**Evidence sources:** as 2.4. Missing: the four no-datapoint series; Neptune latency; attempt 2 has no load, clock or metric files (derived missing.csv).

**Failures:** attempt 2 (class 2, kept). **Cost:** about $0.01 (estimate); attempt 2 $0.

**Interpretation:** the API Gateway throttle dominates every level above 1 client (stated limit, not raised). Separately, the graph route fails at the Neptune layer from 5 clients on (502, then the circuit breaker's 503), while Neptune CPU stays low. The cause is not determined. This is a measured outcome, routed as FECD-1 (major, phase-16 candidate).

### 2.6 p15-a-hadoop (experiment A, Hadoop half)

**Manifest:** sha256 1254a11cd8431bfd…, approved b0c92f5. Hadoop J01 to J09 on DBLP `dblp-2026-11-01` with `dblp.dtd`, a new run at build 684636ef into its own output root `curated/hadoop-p15/ATTEMPT_ID`, managed-scaling maximum 2 (= minimum).

| Item | Value | Source |
|---|---|---|
| Action and arguments | `experiments/collectors/ab-pipeline.sh --stage hadoop --snapshot dblp-2026-11-01 --dblp 's3://{data-bucket}/raw/dblp/dblp-2026-11-01/dblp-2026-11-01.xml.gz' --hadoop-extra-arg --dblp-dtd --hadoop-extra-arg 's3://{data-bucket}/raw/dblp/dblp-2026-11-01/dblp.dtd' --hadoop-output 's3://{data-bucket}/curated/hadoop-p15/ATTEMPT_ID' --emr-max-units 2 --wait-timeout 14700` | run-record `.action.resolved_args` |
| Declared limits | `costBoundUsd` 2.5, `maxDurationSeconds` 18600, EMR min 2 / max 2 core units, state machine timeout 14400 s, EMR idle end 900 s | run-record `declared_not_enforced` |
| Real caps | managed-scaling maximum bounded in state ReadInput; at max = min no ManagedScalingPolicy (L-01, infra/terraform/modules/workflow/sfn.tf:213-220); state machine `TimeoutSeconds` (sfn.tf:289) | Terraform |
| Tool sha256 | 725cebae… (ab-pipeline.sh) | tool-record.json |

```text
20261120T215912Z-b0c92f533c70-5a01 (attempt 1, failed, kept)
  tool: failed (runner exit 5; ab-pipeline exit 6)
  action: failed (execution FAILED 1 s after start: EMR ValidationException "The specified max capacity must be greater than min capacity"; class 4 Terraform defect L-01, repaired e94df52, plan 235b56f5)
  metrics: partial (13 EMR series unresolved: no cluster; AWS/States ExecutionsFailed 1 in the 21:59 period)
  observation: not applicable (no cluster)
  HEAD b0c92f5, build 684636ef, 21:59:12 to 22:04:22 UTC; execution 20261120T215916Z-b0c92f533c70-c950-hadoop; no EMR cluster

20261120T225632Z-eb786c5275a3-5c75 (attempt 2)
  tool: ran (runner exit 0)
  action: succeeded
  metrics: partial by design (CoreNodesRequested, TotalNodesRequested/Running, TaskNodesRequested/Running: no datapoints without a policy, L-03)
  observation: flat capacity baseline seen (CoreNodesRunning 2 at all 14 points)
  HEAD eb786c5, build 684636ef, 22:56:32 to 00:34:22 UTC; execution 20261120T225635Z-eb786c5275a3-cbc4-hadoop; cluster j-05949161IHEKG5O4KFOD (emr-7.14.0); J09 run:v1:148c659f567b96ffd2eec4b04f818600
```

**Measured results** (attempt 2; `hadoop-job-counters.csv`, `hadoop-step-counters.csv`, `emr-*.csv`, `sfn-events.csv`; `experiments/derived/out/p15-a-hadoop/attempts.csv`, `capacity.csv`):

| Checklist item | Value |
|---|---|
| input records | J01 INPUT_RECORDS 13,073,341 |
| output records | J01 works 155,978, mentions 758,328, venues 16, rejects 99 (all no_authors), excluded 12,917,264; J09 20,125,392 records (match_candidate 16,823,178; author_mention 758,328; coauthor_pair_candidate 2,231,914, summed by hand over 2015 to 2025; work 155,978; publication_team 155,978; venue 16) |
| task counts | partial: map tasks planned per job 1, 12, 4, 24, 8, 8, 4, 8, 16, 263 = 348 (by hand); reduce counts **missing** (not logged) |
| duration | execution 4,875 s (22:56:37 to 00:17:52); EMR step 4,122 s (23:01:23 to 00:10:05); cluster 22:56:38 to 00:17:23 |
| shuffle | **missing** (MapReduce shuffle counters are not logged by the driver) |
| failures | none: execution SUCCEEDED, step COMPLETED (`failure_reported` yes is a collector misreading, AB-1) |
| estimated cost | 14,319 instance-seconds × $0.240 per instance-hour = $0.95 (estimate) |
| capacity | CoreNodesRunning 2 flat; CoreNodesPending 0; ContainerPending peak 514 (23:35); YARN memory available 0 % in most minutes |
| released | cluster ended 00:17:23 (lifecycle record) |

Reference only, not a comparison (decision 11.5): Phase 14 D6 attempt 4 (build f159535e, managed scaling up to 4 with three scale-out and scale-in cycles) had the same counters on the same DBLP input (dblp_sha256 0376ad01…). Its pipeline record spans 3,767 s, against 4,926 s here (nested records).

**Plots:** `experiments/derived/out/p15-a-hadoop/capacity-20261120T225632Z-eb786c5275a3-5c75-j-05949161IHEKG5O4KFOD.svg`.

**Evidence sources:** CloudWatch EMR series (running, pending, YARN), AWS/States; tool records (counters, step logs, instance lifecycle, execution history). Missing: requested series (no policy); reduce task counts; shuffle; per-group instance counts (AB-3).

**Failures:** attempt 1 (L-01), kept. **Cost:** attempt 1 $0 (no instance); attempt 2 $0.95 (estimate, EBS excluded).

**Interpretation:** the Hadoop output is reproducible at a new build commit (counters equal Phase 14's). At fixed 2 core nodes the cluster ran with hundreds of pending containers and no free YARN memory. That is the condition under which managed scaling would add nodes if the maximum allowed it.

### 2.7 p15-a-spark (experiment A, Spark half)

**Manifest:** sha256 1875db33a1f57632…, approved b0c92f5. Spark graph stage at build 684636ef on inputs reused from Phase 14 (planned deviation 3): J09 export run=ce46078e05b80d2d5e119939b7d04306 and ER run:v1:23b913cc4dab1155d7a75c505eff5d56. Out-root `graph/runs-p15-a/ATTEMPT_ID`, maximum 2, 32 shuffle partitions, no export.

| Item | Value | Source |
|---|---|---|
| Declared limits | `costBoundUsd` 1.5, `maxDurationSeconds` 17400, EMR max 2, `sparkShufflePartitions` 32 | manifest, run record |
| Configuration | nested record `execution_settings` emr_max_units 2, spark_shuffle_partitions 32; spark-conf.json `spark.sql.shuffle.partitions` 32, adaptive on; config file sha256 9509d47d… | nested record, spark-conf.json |

```text
20261120T225850Z-eb786c5275a3-4f7e
  tool: ran (runner exit 0)
  action: succeeded
  metrics: partial by design (the same 5 series with no datapoints, L-03)
  observation: flat capacity baseline seen (CoreNodesRunning 2 at all 6 points, 23:07 to 23:32)
  HEAD eb786c5, build 684636ef, 22:58:50 to 23:41:42 UTC; execution 20261120T225853Z-eb786c5275a3-f35e-spark; cluster j-0156143328PILM02OMF9; graph run:v1:0f02b5f22d1187932df0fc1decd7e6a5
```

Measured results: section 2.8's comparison table (A column).

**Plots:** `experiments/derived/out/p15-a-spark/capacity-20261120T225850Z-eb786c5275a3-4f7e-j-0156143328PILM02OMF9.svg`.

**Evidence sources:** CloudWatch EMR; tool records (Spark run report stage metrics and timings, run manifest outputs, instance lifecycle). Missing: record counts of the reused inputs (not in the raw folder); requested series (no policy); per-group instance counts (AB-3).

**Failures:** none. **Cost:** 6,576 instance-seconds → $0.44 (estimate).

### 2.8 p15-b-spark-burst (experiment B, Spark half, observational)

**Manifest:** sha256 1b869c94c0370810…, approved b0c92f5. Same stage, inputs and code as p15-a-spark. Differs only in shuffle partitions 128 (A: 32) and managed-scaling maximum 4 (A: 2). Out-root `graph/runs-p15-b/ATTEMPT_ID`.

| Item | Value | Source |
|---|---|---|
| Declared limits | `costBoundUsd` 2.0, `maxDurationSeconds` 17400, EMR max 4, `sparkShufflePartitions` 128 | manifest, run record |
| Configuration | `managedScalingComputeLimits` UnitType Instances, min 2, max 4, on-demand max 4, core max 4 (emr-cluster.json); nested `execution_settings` 4 and 128 | raw |

```text
20261120T225913Z-eb786c5275a3-fac6
  tool: ran (runner exit 0)
  action: succeeded
  metrics: complete (all 18 queried series have data)
  observation: scale-out observed; no scale-in while running; release at termination is a lifecycle record (capacity.csv, attempts.csv classification "scale-out observed; no scale-in while running")
  HEAD eb786c5, build 684636ef, 22:59:13 to 23:38:03 UTC; execution 20261120T225916Z-eb786c5275a3-17c6-spark; cluster j-01156902UW7NYAFY518T; graph run:v1:0f02b5f22d1187932df0fc1decd7e6a5 (same as A-Spark)
```

**Measured capacity over time** (`experiments/derived/out/p15-b-spark-burst/capacity.csv`, 1-minute points 23:05 to 23:30):
- requested: CoreNodesRequested (= TotalNodesRequested) 2 until 23:07, **4 from 23:08**, one point at 2 at 23:12, then 4 to 23:30.
- provisioned (running): CoreNodesRunning (= TotalNodesRunning) 2 until 23:11, **4 from 23:12 through the last point 23:30**.
- lifecycle: 3 instances created at 22:59:28; 2 more created at 23:08:48 and ready at 23:11:26. Graph step 23:06:09 to 23:29:45. Termination requested 23:29:54. All 5 instances and the cluster ended at 23:31:08 (`emr-instances.csv`, `emr-cluster.json`, `sfn-events.csv`).
- released: at 23:31:08 by termination: **lifecycle record, not a measured zero and not a scale-in event.** The console chart that showed 2 at 23:30 is a shutdown or coarse-period display. The exported 1-minute series shows 4 in the 23:30 period.
- pending work: ContainerPending 30 at 23:07 and 23:08, peaks 96 (23:10) and 729 (23:27); AppsPending 0; ContainerAllocated 3 → 5 at 23:11; YARN memory available 2.4 to 6.5 %.

**A-Spark vs B** (like with like; agent-report-experiment-AB.md section 5; "by hand" sums over `spark-stage-metrics.csv` and `spark-stage-timings.csv`):

| Measure | A-Spark (32, max 2) | B (128, max 4) |
|---|---|---|
| core nodes running | 2 flat | 2, then 4 from 23:12 (about 75 % of the graph step) |
| graph step (emr-steps.csv) | 1,488 s | 1,416 s (−4.8 %) |
| stage timings sum (by hand) | 1,391.6 s | 1,341.3 s |
| execution (sfn-events.csv) | 2,231 s | 1,952 s, of which 242 s is the difference in step-end-to-TaskSucceeded lag (251 s vs 9 s, AB-5) |
| tasks / stages (by hand) | 33,112 / 3,866 | 55,573 / 3,877 |
| shuffle read / write (by hand) | 28,879,414,972 / 26,418,246,425 B | 29,752,447,007 / 27,303,870,371 B |
| spill | 0 | 0 |
| output records (spark-outputs.csv) | researchers 200,484; works 155,978; authorship 758,328; full-window coauthor edges 1,612,421; invalid 0 | identical (file sha256 ad70f640… in both) |
| instance-seconds; cost (estimate) | 6,576; $0.44 | 8,380; $0.56 |

The Spark shuffle values come from the run report's StageMetricsListener totals (`_run_report.json` fields extracted by ab-pipeline.sh), not from the Spark event log.

**Plots:** `experiments/derived/out/p15-b-spark-burst/capacity-20261120T225913Z-eb786c5275a3-fac6-j-01156902UW7NYAFY518T.svg`.

**Evidence sources:** CloudWatch EMR node, container and YARN series (requested, running, pending); tool records (instance lifecycle for provisioned and released, execution history, Spark metrics). Missing: per-group instance counts (AB-3). The "…Units… series not exported" lines in derived `missing.csv` are a reporting gap: the manifests queried the Nodes metrics (`metrics/queries.json`), never Units (AB-4).

**Failures:** none. **Cost:** $0.56 (estimate).

**Interpretation:** EMR managed scaling raised requested and provisioned core capacity from 2 to 4 within about 2 minutes of pending containers appearing, and held it until termination. The one-minute requested dip at 23:12 had no matching change in running capacity or instances. Two settings changed together with n = 1 on overlapping clusters, so the 5 % shorter step cannot be split between the extra nodes and the partitions. More partitions raised the task count by 68 % and the shuffle volume by about 3 %. B is observational and was not re-run.

## 3. Spec scaling-rule table

| Component | Required rule or policy | This project's rule (file:line) | Required evidence | Evidence found (file) | Status |
|---|---|---|---|---|---|
| EMR | Managed scaling or justified fleet policy with bounded minimum and maximum | managed scaling, UnitType Instances, min 2, max 4 by default, execution-input maximum bounded in ReadInput, no policy at max = min (infra/terraform/modules/workflow/sfn.tf:56-57, :213-220) | Node or capacity units over time, pending work, duration | B: CoreNodes/TotalNodes requested and running per minute, ContainerPending, step and execution durations (p15-b-spark-burst capacity.csv, attempts.csv, emr-steps.csv); A: flat 2 with pending work (p15-a-* capacity.csv) | evidence found (B scale-out; no managed scale-in while running observed in Phase 15) |
| Embedding workers | ECS target tracking or step scaling on SQS backlog per task | step scaling from 0 on the embedding and job queues, maximum 2 (modules/runtime/autoscaling.tf:36, :88, :109, :166-167; variables.tf:115-118) | Queue depth, desired and running tasks, drain time | C: queue-samples.csv, service-samples.csv, scaling-activities.csv, observed.csv (0 → 1 → 2 → 0, drain 1,172 s) | evidence found (scale-in activity end status missing) |
| Graph retriever | ECS target tracking on CPU or request count with latency alarm | CPU target tracking 60 %, 1 to 2 tasks (autoscaling.tf:35, :49-50, :65; variables.tf:126-129) | Task count, CPU, p95 latency | D-read: tasks.csv (1/1), graph CPU ≤ 8.57 %, levels.csv p95 | partial: task count, CPU, p95 found; no scale-out (not expected); latency alarm not examined: missing |
| Vector retriever | ECS target tracking on CPU or request count | no separate service: the semantic stage runs in the query service (CPU target tracking as above) | Task count, query latency, errors | D-query: tasks.csv, explain-stages.csv semantic (7 samples, embedding and search together); E: modes.csv latency (vector-only p50 204 ms, p95 879 ms), errors 0 | partial (vector-query latency only 7 samples in D) |
| Rank and synthesis services | Queue depth, CPU, or request-based scaling with bounded maximum | query service CPU target tracking, maximum 2 (autoscaling.tf:35, :65) | Task count, queue, p95 latency, LLM latency | D-query: tasks.csv, levels.csv p95, explain-stages.csv synthesis p50 9,656 ms / p95 15,389 ms | evidence found for task count, p95, LLM latency; queue: not applicable (synchronous service, no queue metric) |
| DynamoDB | On-demand or autoscaled provisioned capacity | on demand (`PAY_PER_REQUEST`, modules/dynamodb/main.tf:50) | Consumed capacity and throttles | D: cloudwatch-periods.csv consumed read and write units; C: 19,200 writes | partial: consumed capacity found; throttle series **no datapoints** (not 0) |
| Step Functions and SQS | Retry and dead-letter policy rather than manual polling loops | SQS redrive, embedding `maxReceiveCount` 5 (modules/workflow/variables.tf:138-141, main.tf:28, :54); state machine `TimeoutSeconds` (sfn.tf:289), cluster terminate retry 3 attempts (sfn.tf:279-284) | Execution state, retries, dead-letter count | F: parts.csv, timeline.csv (5 receives, DLQ + 1); C: retries 0, DLQ at baseline; A and B: execution states in sfn-events.csv (A-Hadoop attempt 1 FAILED, CloudWatch ExecutionsFailed 1; the other three SUCCEEDED); no state-machine retry occurred | evidence found |

## 4. Spec gate (D15 and D29 rule: observations with evidence files, not exit codes)

| Gate item | Status | Evidence |
|---|---|---|
| At least one scale-out and one scale-in event are shown | **met** (C) | C: describe-services samples 0 → 1 (23:01:39) → 2 (23:04:26) → 0 (23:24:59) **and** scaling activities out 23:01:25, out 23:04:25, in 23:24:39 (`service-samples.csv`, `scaling-activities.csv`, derived `observed.csv` "met (samples and scaling activities)"). B adds an EMR scale-out only; its release at termination is not a scale-in. |
| Metrics use synchronized timestamps | **met** (as designed and measured) | epoch seconds in every tool record plus HTTP `Date` samples; offset within about ±1.5 s, CloudWatch in 60 s periods (section 0) |
| Raw experiment data is preserved | **met** | 10 attempt folders tracked (`git ls-files experiments/raw`: 17, 17, 19, 19, 17, 13, 13, 6, 11, 13 files) at commits 6a6c31a, 2cc5804, 70876eb, e86449a, abe4764, ee3bce8, d872d12; run-record.json files read-only (-r--------) except D-read attempt 2 (-rw-------; immutability rests on the commit) |
| Costs and limits are stated | **met** | each manifest's `costBoundUsd` and limits (run-record `declared_not_enforced`), the real caps with file:line (section 2), Billing numbers and estimates (section 6) |
| Failures are reported, not edited out of existence | **met** | section 1 lists A-Hadoop attempt 1 and D-read attempt 2 with their causes, plus the D9a preflight failure behind L-02; F's incident record |

## 5. Runs that overlapped

| Run | Window (UTC) | Overlapped with | Shared resources | Effect on attribution |
|---|---|---|---|---|
| A-Hadoop attempt 1 | 21:59:12 to 22:04:22 | D7 ingest pass (21:59:02 to 22:06:19, not an experiment) | none (no cluster) | none |
| E | 22:14:02 to 22:20:15 | none | – | none |
| F | 22:39:08 to 22:49:56 | none | – | none |
| A-Hadoop attempt 2 | 22:56:32 to 00:34:22 (cluster 22:56:38 to 00:17:23) | C, A-Spark, B, D-query, D-read 1 | NAT gateway; account EMR vCPU quota (3 clusters at once, within 64 vCPU) | EMR clusters are separate; C and D use ECS, SQS, Neptune and DynamoDB, which EMR does not use. NAT sharing not quantified |
| C | 22:57:30 to 23:30:09 | A-Hadoop 2, A-Spark, B | NAT gateway | none on the worker, queue or alarm evidence |
| A-Spark | 22:58:50 to 23:41:42 (cluster 22:58:56 to 23:35:37) | C, A-Hadoop 2, B | NAT gateway, S3 | A-Spark and B ran at the same time under similar conditions; S3 and NAT contention not measured |
| B | 22:59:13 to 23:38:03 (cluster 22:59:19 to 23:31:08) | C, A-Hadoop 2, A-Spark | NAT gateway, S3 | as above |
| D-query | 23:55:21 to 00:09:22 | A-Hadoop 2 | NAT gateway | none on ALB, ECS, DynamoDB, Neptune |
| D-read attempt 1 | 00:15:29 to 00:26:53 | A-Hadoop 2 (cluster ended 00:17:23), D-read attempt 2 | NAT gateway | attempt 2 sent no request |
| D-read attempt 2 | 00:22:54 to 00:23:09 | D-read attempt 1 (metrics wait) | – | none |

## 6. Cost

| Provider | Phase 15 measured (Billing, human reading Nov 21 about 17:00Z) | Not yet billed (estimate, basis) | Per experiment (estimate) |
|---|---|---|---|
| AWS | month to date $19.64, against the Phase 14 baseline reading $9.92: delta $9.72. The delta mixes Phase 15 usage with Phase 14 usage the baseline reading lagged (Neptune I/O about $1.16 plus Phase 14 runtime and idle hours before 16:00Z Nov 20, up to about $5.5). Phase 15 AWS from Billing: about $3.0 to $8.6 (runbook-log, sitting 3 "Billing") | idle hours after the reading until the removal and the removal itself (about $0.4, orchestrator estimate at sitting 3); billing lag on Nov 21 hours | A-Hadoop 1 $0; A-Hadoop 2 $0.95; A-Spark $0.44; B $0.56 (instance-seconds × $0.240 per instance-hour, COSTS.md:39, EBS excluded); C $0.08 to $0.10; D-query AWS part under $0.01; D-read $0.01; E $0.01; F about $0.005. The always-on runtime (about 13.4 h × $0.15 ≈ $2.0) and idle hours (≈ $0.9 to 17:00Z) are not attributed per experiment (orchestrator running estimate) |
| Anthropic | $0.24, against the baseline about $0.12: about $0.12 | negligible | D-query about $0.09 to $0.10 (40 answered queries × about $0.0023, Phase 10 attempt-06 basis); F about $0.002; demo and smoke queries outside the experiments |
| Voyage | $0.00 | $0 (free allowance) | C about 1.7 M tokens (manifest estimate; actual not captured), E 254 tokens, F 2 embeddings: inside the free allowance |

Experiments total (estimate): about $2.2 (EMR $1.95 + F, E, C, D about $0.21 to $0.24). Orchestrator Phase 15 running estimate at sitting 3: about $6.1 including runtime and idle hours, inside the Billing-derived range. Phase 15 bound $9 across all providers; project ceiling $25 (project about $20 to $21 by the sitting 3 cost line). Items moved out of "not yet billed": the orchestrator kept all Phase 15 usage as not yet billed until the $19.64 reading, which now covers usage through about Nov 21 (exact catch-up per item unverified because of billing lag).

## 7. Limitations

Planned deviations (decision 15), as they happened:
- (1) The human, not experiment-engineer, executed every experiment through `scripts/run-experiment.sh`; experiment-engineer wrote the tools and manifests and analysed the results read-only.
- (2) The human's admin profile ran every script.
- (3) A's Spark half (and B) read Phase 14's J09 export and ER output (build f159535e) while running at build 684636ef. A's two halves are not one chained run.
- (4) E's fused mode is experiment-only (`fusion-exp-v1`).
- (5) D ran under the deployed limits (API throttle 5/s burst 10, 4 concurrent queries per task, 19 LLM requests per minute per task) at levels 1, 2, 4, 8 and 1, 5, 10, 20.
- (6) The cleanup is planned right after the experiments and the demo recording (D25 to D27). It had not run when this summary was written.
- (7) One runtime-off plan with cleanup mode on (D19, D20, done).
- (11) Every EMR run was attended in the evening. D21 (overnight fallback) was not needed.

A and B rules: results compare only like with like (A-Spark with B; A-Hadoop alone, Phase 14 D6 attempt 4 as a reference only); no total pipeline duration across A and B. B is observational. Released capacity at cluster end is a lifecycle record.

Limitations the runs showed:
- **A: no requested series by design.** At maximum = minimum the state machine sends no managed-scaling policy (L-01), so CoreNodesRequested, TotalNodes* and TaskNodes* have no datapoints (L-03). CoreNodes* in A have only 6 and 14 points at about 5-minute spacing; the cause is not determined.
- **B: confounded and n = 1.** Partitions and maximum changed together; one run each; clusters overlapped.
- **Hadoop:** shuffle counters and reduce task counts are not logged by the driver (`missing`). Map tasks are planned splits from step logs.
- **Collector field readings (raw unchanged):** `emr-steps.csv` `failure_reported` "yes" on COMPLETED steps is a misreading. EMR returns an empty FailureDetails object (Reason and Message null, live read) and ab-pipeline.sh:392 tests only that the key exists (AB-1). `emr-instances.csv` `state_change_code` INSTANCE_FAILURE is EMR's code at termination, with the message "Instance was terminated." (live read), not a run failure (AB-2). `emr-instance-groups.csv` is empty (list-instance-groups exit 252 ParamValidation, cause not determined), so per-group counts and instance group types are missing (AB-3). Derived "…Units… series not exported" is a reporting gap: the Units series were never queried (AB-4).
- **Step end to Step Functions TaskSucceeded lag:** 285 s and 251 s in the A runs, 9 s in B. Clusters ran about 4 to 5 minutes longer (AB-5, cause not determined).
- **D:** ALB ELB 5XX, the evidence target-group 5XX (D-read) and DynamoDB throttle series have **no datapoints**. CloudWatch publishes these on events, so this is consistent with zero throttles but is not a measured zero (documentation not checked in this step: unverified). Neptune latency is not separable (`explain-coverage.csv`). Vector-query latency has 7 samples. Explain is keyed by a deterministic query ID per question, so it shows only the latest execution of a question (FECD-3). Neptune per-minute series can lag the tool's minutes by one period (FECD-8).
- **D-read graph route:** 502 then 503 `CIRCUIT_OPEN` from 5 clients on, with low Neptune CPU (FECD-1, major, phase-16 candidate). This is a measured outcome, not an experiment defect.
- **C:** drain measured under concurrent enqueue (FECD-6). The scale-in activity's end status is missing (FECD-5). The worker's in-process counters are not exported; exactness rests on three agreeing counts (FECD-9).
- **F:** user-visible impact not measured; API event epochs appear to be end times (FECD-7); overlapping duplicates are a unit-test matter and concurrent duplicates are not observable.
- **D-read attempt 2:** started by accident without the key. Its run record has exit 5 with signal INT at `metrics-settle`, against the exit table's signal row (FECD-4).
- **Task counts:** Container Insights is off. Counts come from 15 s describe-services samples and scaling activities, so a task that lived under 15 s may go unseen.
- **Clock:** 1 s resolution of `date` and the HTTP `Date` header; CloudWatch 60 s periods that can arrive minutes late.
- **E:** universe 11 questions, exploratory; researcher IDs only.
- **C re-embed rate** is the re-embed path only. G-27's first-pass speed remains an estimate.
- **No Neptune CPU alarm** (Phase 14 decision 8): Neptune load is read from its CloudWatch series only.
- **Fixtures** under `experiments/derived/fixtures/**` are hand-written test data, never results.
- **Costs** per experiment are estimates from COSTS.md rates (themselves unverified). Billing cannot yet split Phase 14 lag from Phase 15 usage.

## 8. Proposed later (not done now)

- L-08: larger labels in `experiments/derived/queue_worker.py` SVGs (hard to read in the demo).
- AB-1 to AB-4: collector and derive-script fixes (FailureDetails test, instance state message class, the list-instance-groups call, Units names only when queried).
- FECD-5, FECD-7: post-zero poll of C's scale-in activity; F's API event time semantics.
- A derive-script table for the by-hand totals (Spark shuffle and task totals, Hadoop map-task sum, CloudWatch per-route and per-minute sums), so every number here comes from a script.
