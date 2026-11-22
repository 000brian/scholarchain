# agent-report-storage-D (Phase 16, session 2, step D, storage-engineer, read-only)

STATUS: COMPLETE
OWNER: storage-engineer
WORK ORDER: docs/agent-runs/phase-16/prompt.md (sha256 c75cc5f895faed13880a69ec285231c5498fd50924c7b875fe7e02cce1707dc8, checked), HEAD 9da45ab616a764e449f74f25feca85ed77d6dfe4 (checked); step D, decision 6 item 1 only: FECD-1 candidate causes (a) to (f), testable offline or not, G-28 restatement with the 23:51 Neptune error and L1's effect on q13; recommendations for step 3 and step 7.
FILES CREATED: this report only (scratchpad). No repository file.
FILES MODIFIED: none.
COMMANDS RUN: shasum -a 256 (prompt); git rev-parse HEAD; git status --short; ls; wc -l / wc -c; head -n; cut; grep -n / grep -c (literal words) over the D-read raw folder, the D-query metric file, docs/agent-runs/phase-15/{runbook-log,human-gate,orchestrator-summary}.md, modules/storage, modules/services, modules/rag, config/course.json, infra/terraform/modules (not environments/course), scripts/smoke-test.sh. Read tool on every file cited below. aws-docs MCP: 1 search and 4 page reads (URLs below). No sbt, no script, no aws-api, no network command. Rule slips, disclosed (all read-only, nothing written): most Bash calls started with `cd <absolute repository path> &&` (`cd` is on the never list); one pipeline used `tr '\n' ' '` to join output; two `for` loops wrapped grep and cut.
TEST RESULTS: none run (read-only step; sbt forbidden for step D, decision 4).
VALIDATION RESULTS: every number below is from a committed file (named, with row numbers or JSON line numbers) or labelled `estimate`. Prompt hash and HEAD match the work order.
ASSUMPTIONS:
- A1. Neptune's `TotalServerErrorsPerSec` counts server-side (HTTP 5xx) errors only. The metric page read does not define "server error" (unverified).
- A2. Per-period counts = rate x 60 (the export stores per-second averages of 60 s periods).
- A3. The D6 run (smoke attempt 20261120T235057Z-ff00cfa278c8-a5a4) issued every Neptune request seen in the 23:51 period. Basis: the D-query metric file has 0 openCypher requests in the 23:50 and 23:52 to 23:54 periods. No other Neptune client is recorded in that window (the A-Hadoop EMR run does not touch Neptune, FECD report section 0).
- A4. db.t4g.medium has 2 vCPUs. That is the EC2 t4g.medium specification; the Neptune pages I read do not state it (unverified from aws-docs).
RISKS:
- R1. The Neptune error class behind every failure (FECD-1 and q13) was dropped by the code (see (f1)), and the service logs are gone with the environment. So causes (c), (d) and (e) cannot be separated from the frozen evidence.
- R2. Any step 3 or step 7 fix is `fixed in code, tested offline, not validated live`. Whether a bounded graph-read retry would have rescued D-read's failures cannot be decided (see (a)).
- R3. Interface: a typed graph error (recommendation S1) changes `GraphReadPort.run`'s Left type, which rag and services consume. If step 3 makes that change it is "interface: yes".
MANUAL ACTION REQUIRED: none.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: R-1a reviews this report. Step 3 (storage-engineer) reads sections 3 and 4. Step 7 (service-engineer) reads sections 2(b) and 4. Step 4 (rag-engineer) reads section 3.

---

## 1. What the D-read data show (all facts measured, with source rows)

Source folder: experiments/raw/p15-d-read-load/20261121T001529Z-abe4764a8293-b3f0/ (`LR` = load-requests.csv, 2,251 data rows; `MD` = metrics/metric-data.json; `IC` = id-check.csv; `LL` = load-levels.csv).

M1. **Every 502 came in a 1 to 2 s burst at the start of a level. Every later request in that level was a breaker rejection.**
- Level windows (LL rows 3 to 5): L2 1795220332 to 356 (24 s), L3 416 to 429 (13 s), L4 489 to 499 (10 s).
- All 28 `DOWNSTREAM_FAILURE` rows (LR rows 455, 574, 817; 1055 to 1595; 1656 to 2225) start at epoch 332 to 333 (L2), 416 to 417 (L3) and 490 to 491 (L4).
- Every neighbors `ok` in L2 to L4 also falls in those same seconds: L2 12 ok at 332 to 333, L3 9 ok at 416 to 417, L4 24 ok at 489 to 491.
- After the burst, every neighbors request that passed the gateway got 503 `CIRCUIT_OPEN`, until the level ended (for example LR rows 456 to 567 for L2 client 1).

M2. **Neptune saw exactly the burst requests and nothing else.**
- Per burst, Neptune calls = neighbors ok + 502: L2 12 + 3 = 15, L3 9 + 7 = 16, L4 24 + 18 = 42.
- MD `m23_0` OpenCypherRequestsPerSec (lines 657 to 695), times 60: 00:19 = 15.0, 00:20 = 0, 00:21 = 16.0, 00:22 = 42.0.
- MD `m26_0` TotalServerErrorsPerSec (lines 780 to 817), times 60: 00:19 = 3.0, 00:21 = 7.0, 00:22 = 18.0, 0 elsewhere.
- So all 28 failures are Neptune-counted server errors (1:1), and none of the 526 `CIRCUIT_OPEN` answers reached Neptune.
- MD timestamps are UTC-06:00. 18:19 local = 00:19 UTC.

M3. **The Neptune series lag the request log.** Each burst appears in the period after the one it ran in:
- L2 at 00:18:52 appears in 00:19;
- L3 at 00:20:16 appears in 00:21;
- L4 at 00:21:30 appears in 00:22.

L1's steady stream (00:15:52 to 00:17:52, about 3.4 neighbors/s) appears as 107 / 197 / 128 requests in the 00:16, 00:17 and 00:18 periods. In seconds, that is about 32 / 58 / 38 s of L1 traffic per period, consistent with a lag of tens of seconds that is not constant (my reading, by hand). Neptune's metric page says its aggregation granularity is 5 minutes (https://docs.aws.amazon.com/neptune/latest/userguide/cw-metrics.html). This refines FECD-8.

M4. **Failures need concurrency.**
- Zero failures in 430 sequential neighbors calls: IC (26 neighbors rows) plus L1's 404 ok (LL row 2).
- Every ID that failed later succeeded 15 to 16 times in L1 and once in the ID check: df6166…, 0a0f6a…, 32ad9a…, 98afa4…, fe1177…, 95b5fd… (grep counts over LR and IC).
- In the bursts the failing fraction was 3/15 (20 %) at 5 clients, 7/16 (44 %) at 10 and 18/42 (43 %) at 20.
- The same pattern holds in D-query (FECD report section 4.1): Neptune server errors only at levels 3 and 4 (4 and 8 clients), none at 1 and 2 clients.

M5. **Failures came back fast.**
- 502 times on the client side (curl `time_total`): 0.204 to 0.758 s.
- Burst ok times: 0.167 to 0.623 s.
- 503 `CIRCUIT_OPEN`: about 0.12 to 0.21 s, which is the path cost without Neptune.
- No 502 came near any configured timeout: Neptune call budget 20,000 ms (`runtime.graphTimeoutMillis`, config/course.json:89); SDK socket and API call 60,000 ms (RuntimeService.scala:173-174).

M6. **The coarse metrics cannot see a 1 to 2 s burst.**
- Neptune CPU Maximum per 60 s period was at most 23.0 % (MD `m22_0`, 00:17, during L1, not a burst).
- MainRequestQueuePendingRequests Maximum was 0 in every period (MD `m25_0`).
- Neptune documents this queue metric at five-minute granularity (https://docs.aws.amazon.com/neptune/latest/userguide/access-graph-queuing.html).
- So "CPU at most 23 %, queue 0" does not rule out saturation for 1 to 2 s at the start of each level. It shows only that there was no sustained saturation, and there was no sustained load either (M1, M2).

M7. **Concurrency reaching Neptune was capped by the graph service.** The service has `maxConcurrentQueries + 8` = 12 HTTP handler threads (ServiceServer.scala:56; config/course.json:98). Neptune concurrency in the L4 burst was therefore at most about 12. Estimate from M1 and M5: about 42 calls x 0.35 s over about 1.5 s gives an average of about 10 in flight in L4, and about 4 to 5 in L2.

## 2. FECD-1 candidate causes

Table: one row per cause, giving its code path, what the data say, whether a test without the live graph can show it, and whether the frozen evidence decides it.

| # | Cause | Code path (HEAD 9da45ab) | D-read and other frozen data: for / against | Testable offline? | Decidable from frozen evidence? |
|---|---|---|---|---|---|
| (a) | Graph reads are never retried: one transient Neptune 5xx under concurrency becomes one 502 | NeptuneAdapters.scala:123-125 (doc: "Graph reads are not retried"), :160-171 (`run`: one `execute`, StoreException -> Left), :144-158 (`execute`, `AwsErrors.guard`); AwsStorageEnvironment.scala:126-129 ("Graph reads are not retried"), :165-169 (the bare `NeptuneGraphRead`, no retrying decorator, unlike S3/DynamoDB/S3 Vectors at :147-159); AwsSupport.scala:26-29, :60-62 (SDK `maxAttempts` = 1, so no SDK retry either); ReadServices.scala:201-205 (one `graph.run` per request) | **For:** every 502 is one Neptune server error, 1:1 (M2), and each returned in under 0.76 s (M5), far inside the 20 s budget, so a bounded retry had time. Neptune documents `QueryLimitExceededException` ("can be retried when the system is less busy") and `MemoryLimitExceededException` ("can be retried") as HTTP 500 (https://docs.aws.amazon.com/neptune/latest/data-api/API_ExecuteOpenCypherQuery.html). **Against or unknown:** whether a retry 0.2 to 1 s later would have succeeded is unmeasured, because the breaker sent nothing to Neptune after each burst (M1, M2). (a) is the amplifier that turns each Neptune error into a 502; it does not cause the Neptune error. | **Yes, as code behavior:** a fake `NeptunedataClient` that throws one 500 and then succeeds shows `run` returns Left after exactly one call (no retry), for single-shot templates and for one `PathBetween` depth. | **Partly.** "No retry happened" is shown by code and data. "A retry would have helped" is not decidable. |
| (b) | The shared graph circuit breaker opens after `circuitFailureThreshold` = 3 failures for `circuitOpenMillis` = 30,000 ms and turns 28 failures into 526 rejections | Support.scala:64-136 (`CircuitBreaker`; `acquire` :78-85 before the call, `recordFailure` :102-110 after it, `call` :122-135); QueryService.scala:26-37 (one `graph` breaker per process for every graph template); ReadServices.scala:201-205 (`Some(_)` -> 502 `DOWNSTREAM_FAILURE`, `None` -> 503 `CIRCUIT_OPEN`); config/course.json:103-104 | **For, measured:** all 526 503s fall after the burst, and none reached Neptune (M1, M2). The 30 s open time is longer than the rest of each level (L2 about 23 s, L3 about 12 s, L4 about 9 s), so **no half-open trial ever ran inside a level**. Each new level started with the trial left over from the open period that expired in the 60 s gap: L3's first request at 416 got 503 while a trial was in flight (LR row 1053), then closed state, then 7 failures. **Failures recorded per opening grew with client count: 3 / 7 / 18.** The breaker hands out slots while closed and counts failures only when calls return, so every request in flight when it opens still fails and is counted. Under concurrency, "3 consecutive failures" is really "threshold plus in-flight". **Not a Neptune cause:** (b) explains 526 of the 554 errors, but none of the 28 Neptune errors. | **Yes:** a stub graph port that fails under N concurrent callers, with a fake clock, reproduces the burst counts and the full-window rejection (service-engineer, decision 7.1a). Replaying the LR sequence against `CircuitBreaker` should reproduce the 503 pattern (estimate, not run). | **Yes** for the rejection mechanism. Its counts follow from the code plus the LR timeline. |
| (c) | The HTTP client: one JDK url-connection client per SDK client, under 5 to 20 concurrent requests | AwsSupport.scala:49-54 (`UrlConnectionHttpClient`, connect 10 s, socket 60 s; no connection or concurrency setting); AwsStorageEnvironment.scala:66, :94-107 (a new client per SDK client unless one is injected) | **Against, as the direct cause:** every failure is a Neptune-counted server error (M2). A client-side failure (connection refused, reset, socket timeout, TLS) never produces an HTTP status at Neptune, so it would not appear in TotalServerErrorsPerSec (A1). Failures returned in 0.2 to 0.76 s (M5), not at the 10 s connect or 60 s socket timeout. **Not excluded as a contributor:** each burst starts after 60 s idle. Whether the JDK keep-alive cache still held connections, or 5 to 12 fresh TLS and SigV4 connections opened at once (IAM database auth is on, infra/terraform/modules/neptune/main.tf:66), is not recorded. The url-connection client has no in-process concurrency cap, so all 5 to 12 handler-thread calls reach Neptune at once. | **Partly:** a test can show the client imposes no concurrency limit and that nothing queues in process. It cannot show any Neptune reaction. | **No** as a contributor. **Against** as the direct cause (M2, M5). |
| (d) | Neptune-side limits of db.t4g.medium for concurrent openCypher requests | Instance class: infra/terraform/modules/neptune/variables.tf:40-43 (default `db.t4g.medium`), main.tf:1 ("ONE db.t4g.medium"); one instance, no reader. Endpoint and port from configuration (AwsStorageEnvironment.scala:94-107). | **For:** Neptune runs "twice the number of virtual CPU cores" queries at once and queues the rest up to 8,192 (https://docs.aws.amazon.com/neptune/latest/userguide/access-graph-queuing.html; also "two (2) query execution threads per vCPU", https://docs.aws.amazon.com/neptune/latest/userguide/instance-types.html). With 2 vCPUs (A4, unverified) that is **4 concurrent queries**. Failures start exactly where concurrency passes about 4: none at 1 to 2 clients in D-read L1 and D-query L1 to L2; failures from 4 to 5 clients up (M4, M7). `QueryLimitExceededException` (500) is "raised when the number of active queries exceeds what the server can process". The instance-types page says t3 and t4g instances "have very constrained resources" and "are not intended for use in a production environment"; per-thread memory is the smallest at this size, so `MemoryLimitExceededException` (500) under concurrent `ORDER BY` on high-degree nodes is also possible. **Against or unknown:** the docs say excess queries are *queued*, not rejected, and they do not say when `QueryLimitExceededException` is raised instead of queuing (unverified). CPU and queue metrics cannot see the burst (M6). | **No.** A fake can throw these exceptions, but it cannot show that Neptune raises them at this concurrency. | **No.** The error class was dropped (f1) and the logs are gone (R1). The docs make (d) the best-supported explanation (my reading), not a proven one. |
| (e) | The neighbors query itself | NeptuneModel.scala:52-57 (`coauthor_neighbors`: match on `id(r)`, filter `e.windowLabel = $windowLabel`, `ORDER BY weight DESC, coauthorId ASC LIMIT 51`); :122-131 (compile, limit 50 from the route, ReadServices.scala:257) | **Against a per-query defect:** each failing ID succeeded 15 to 16 times sequentially (M4), so the query text, its parameters and per-node size are each valid and fit alone. **Unknown:** the query reads every window-filtered edge of the node before `ORDER BY` and `LIMIT`. Several such queries on high-degree nodes at once on the smallest instance could exceed per-thread memory or the active-query limit, which makes (e) a factor inside (d), not a separate cause. | **Partly:** existing template tests show the text and bounds. Neptune's memory per query cannot be shown offline. | **No** for the concurrent interaction. **Excluded** as a standalone defect (M4). |
| (f1) | **The storage port drops the error class**, so no layer can tell transient from permanent | NeptuneAdapters.scala:170 (`catch { case e: StoreException => Left(e.getMessage) }`), :194 and :198 (`Left(Some(e.getMessage))`, then `Left(err)`); GraphPorts.scala:246-249 (`run: Either[String, GraphResult]`). `AwsErrors.wrap` does compute `transient` (AwsSupport.scala:77-92), but it is lost here; `RuntimeStores.guard` (RuntimeStores.scala:59-64) only sees *thrown* StoreExceptions, so for graph reads it never adds its "(transient)/(permanent)" label | **For (code fact):** neither a retry layer (a) nor the breaker (b) can treat a transient 5xx differently from a permanent 400 or 403 or a resource-limit error. This is also why FECD-1 and q13 have no recorded error class: GraphService discards the Left text (ReadServices.scala:202-205 `case Some(_) => ApiError.downstream`, no log line) and rag keeps only an allowlisted code (Stages.scala:659-660). | **Yes:** a fake client throwing a 500 and a 400 gives two Left strings that the port's type cannot distinguish. | **Yes** (code fact). |
| (f2) | rag silently swallows graph failures in the resolver, which hides Neptune errors in answers | ResearcherResolver.scala:187-190 (`ResearcherById ... .toOption`), :197-208 (`CoauthorNeighbors ... .toOption ... getOrElse(Nil)`) | **For:** D-query had about 14 Neptune server errors in the 00:02 to 00:04 periods (FECD report section 4.1; D-query metrics/metric-data.json `m33_0`, lines 1166-1210: 0.1167, 0.0667, 0.0500 /s), yet no abstained answer and no other-5xx answer. Failures inside the resolver degrade silently to `identityOutcome` "unknown" with no top coauthors. Which calls failed is not recorded. Owner: rag-engineer (reported, not mine to change). | **Yes** (rag test with a failing stub graph port). | **Partly:** the counts are consistent, but the failed calls cannot be attributed. |
| (f3) | Load shape: every level starts with a synchronized burst after 60 s idle, and the breaker then hides Neptune | Not a code path. Load tool behavior (LL, LR) together with (b). | **For:** M1. Neptune's behavior under **sustained** concurrency of 5 to 20 clients was never measured: in every level it served about 1 to 2 s of traffic and nothing more. FECD-1's "fails from 5 clients" therefore means "fails in the first second of a 5-client burst". Whether it would recover after a backoff is unknown. | Not applicable (a measurement limit). | Decides only that steady-state behavior is **not measured**. |
| (f4) | Metric resolution and lag | Not a code path. | M3, M6: the Neptune series lag by tens of seconds and are aggregated at 1 to 5 minutes, so per-level attribution and saturation claims are bounded. | Not applicable. | Limitation. |

**Summary of FECD-1 (my reading):**
- The 28 Neptune server errors arose in short synchronized bursts once concurrency passed about 4 (M4, M7). That matches the documented 2 x vCPU worker-thread limit of db.t4g.medium (d), possibly worsened by the neighbors query's full edge scan before `ORDER BY` (e).
- Storage passed each error through unretried (a) and without its class (f1). The service breaker then turned them into 526 rejections that never reached Neptune (b).
- (a), (b) and (f1) are code facts that a test can show offline. (c) is unlikely as the direct cause. (d) and (e) cannot be decided from the frozen evidence.

## 3. G-28: 3r's ranking restated with the FECD 23:51 Neptune error

**New evidence since 3r:**
- E1. D6 (phase-15 runbook-log.md row 55) recorded the abstention code `path-query-failed`. Other values from that row: templates researcher_by_id, coauthor_neighbors, path_between_depth_6; `stageMillis.paths` 4,868 ms; planner 1,992 ms; resolver 183 ms; synthesis 8,498 ms. Smoke record evidence/runs/20261120T235057Z-ff00cfa278c8-a5a4-smoke-test.json:11-12: run 23:50:57Z to 23:51:16Z, 7/7, query 200, 4 claims, 2 evidence links.
- E2. D-query metrics/metric-data.json (an extra committed input I read to check FECD's 23:51 note; it is not on decision 6's list):
  - `m30_0` OpenCypherRequestsPerSec (lines 1026-1069): 23:50 = 0, **23:51 = 0.1167/s (7 requests)**, 23:52 to 23:54 = 0, 23:55 = 1 request.
  - `m33_0` TotalServerErrorsPerSec (lines 1166-1210): **23:51 = 0.0167/s (1 error)**, 0 in every other period up to 00:01.
  - So the D6 run is the only Neptune traffic between 23:50 and 23:54 (A3). Its whole Neptune activity is **7 requests with 1 server error**. This is stronger than FECD's "consistent with": the 23:51 error belongs to the D6 run, whatever the lag of M3.
- E3. The code path at HEAD. `Stages.scala:617-660`: a Left from `stores.graph.run(PathBetween…)` gives abstention `PathQueryFailed`. In `NeptuneAdapters.scala:190-198`, a Left comes only from a StoreException that is **not** a timeout. `TimeLimitExceededException` and API-call timeouts give `complete = false` (:193, :197, :221-227), which Stages turns into a note, not this code (Stages.scala:661-667).
- E4. D6 ran **one** workflow, whose graph calls are sequential, so Neptune concurrency was about 1 (estimate from the code; no concurrent client in E2). D-read L1 had 0 failures in 430 sequential calls (M4).

**3r's causes restated:**

| 3r # | Cause | 3r verdict (D15, Phase 14) | Restated with D6 + 23:51 (for the D6 run) |
|---|---|---|---|
| 1 | No path within depth 6 (search complete) | POSSIBLE | **Excluded for D6:** the code was `path-query-failed`, not `no-path-within-depth` (E1, E3). |
| 2 | Time budget used up | EXCLUDED | **Excluded:** paths 4,868 ms < 20,000 ms, and timeouts do not produce this code (E3). |
| 3 | Path query failed with a non-timeout error | MOST CONSISTENT | **Shown as the mechanism for D6** (E1, E3). **Narrowed:** the run's single failed Neptune call was a Neptune-counted server error (E2, A1), so a client-side I/O error and the 4xx classes (`MalformedQueryException` 400, `QueryLimitException` 400, `ThrottlingException` 400 per https://docs.aws.amazon.com/neptune/latest/data-api/CommonErrors.html, `TooManyRequestsException` 429) are unlikely. The remaining 500 classes are `MemoryLimitExceededException`, `QueryLimitExceededException`, `FailureByQueryException` and `InternalFailure` (`CancelledByUserException` and `ConcurrentModificationException` do not fit a read with no cancel). Caveat: the resolver swallows graph failures (f2), so the 1 server error *could* have been a resolver call, with the path failure something not counted by Neptune. That needs two independent failures in one 19 s run; I rate it unlikely (judgment). |
| 3-sub | ... caused by FECD-1's concurrency mechanism | (not in 3r) | **Unlikely:** concurrency was about 1 (E4), and FECD-1 failures occur only above about 4 concurrent queries (M4). `QueryLimitExceededException` at concurrency 1 is implausible (my reading of the doc text). |
| 3-sub | ... caused by a resource limit on a heavy variable-length path query (memory) | (3r named the classes) | **Most consistent:** the paths stage took 4,868 ms, against 233 to 460 ms on the 7 D-query rows with non-zero `paths_ms` (FECD report section 4.1, `load-explain.csv`). That suggests a deep or heavy exact-depth query ran for seconds before failing. db.t4g.medium has the smallest per-thread memory ("OOM exceptions occur when one thread needs more than the maximum memory allocated to it", instance-types page). Not proven: the class is not recorded (f1). |
| 4 | Name not resolved | EXCLUDED | Excluded (the path template ran). |
| 4b | Identity uncertainty | NOT A CAUSE | Unchanged. |
| 5 | Path found, claims removed | EXCLUDED | Excluded (a found path would not give `path-query-failed`). |
| 6 | Path rows malformed | UNLIKELY | **Excluded for D6:** that site emits `path-result-invalid` (3r code table; Stages.scala:668-669 onward), not `path-query-failed`. |
| 7 | Data drift (labels from the Phase 7 graph) | LIKELY UNDERLYING | **Still likely underlying:** the search went past depth 1 (the request counts allow depth 1 empty, then a failure at depth 2 or 3; see the split below). The Phase 7 label's 2-hop path (3r R2) may not be in the Phase 14 window graph, which drives the search to the deeper, heavier depths where a resource limit is likely. |

Request split for the 7 requests (an estimate, not measured):
- If readyz's graph check reached Neptune in this run (1 request) and the resolver made ResearcherById + CoauthorNeighbors for each of 2 names (4 requests), 2 path requests remain: depth 1 empty, depth 2 failed.
- If readyz did not reach Neptune (its result may be cached, HealthService.scala:10-14), depths 1 to 3 ran and depth 3 failed.
- The identity stage could add more ResearcherById calls. I cannot decide the split from the frozen evidence.

**Does L1 change q13's outcome?**
- If the error class was `MemoryLimitExceededException` or `QueryLimitExceededException` (L1's classes), L1 turns the Left into a bounded result: `complete = false` with `depthSearched` = the last finished depth (1 or 2 by the split above). Stages then records `PathAbsentFact(complete = false)` and the note "whether a path exists is unknown" (Stages.scala:661-667; the wording today says "time limit", which rag step 4 must adapt), plus a graph_path evidence item.
  - The abstention reason changes from `path-query-failed` to L1's bounded code (rag step 4, for example `path-query-bounded`).
  - The answer still holds **no hop count and no path**: the search stopped before it found one.
  - Whether the answer still abstains depends on synthesis and on L2 (a bounded item must never support an absence claim, decision 8.1). The likely result is still an abstention, with an honest reason (my reading).
- If the class was `FailureByQueryException` or `InternalFailure`, L1 changes nothing.
- Which applies cannot be decided from the frozen evidence (R1).
- So: **L1 changes q13's recorded reason and evidence, not its substantive outcome.** Only a deep path search that completes (more memory or a different query plan) or the drift fix (cause 7) could make q13 answer "2 hops".
- A bounded retry (step 3) would most likely fail again for a deterministic memory-heavy query at concurrency 1 (my reading). So the retry does not fix q13, and L1 is the right handling for it.

**G-28 statement (for rag step 4 to adopt or revise):** cause narrowed to: a server-side failure of one Neptune query during the path search (causes 1, 2, 4, 5 and 6 excluded for the D6 run), most consistent with a resource limit on a deeper variable-length path query, reached because the expected 2-hop path is likely absent from the Phase 14 graph (cause 7). FECD-1's concurrency mechanism is unlikely (concurrency about 1). The exact error class is not decidable from the frozen evidence.

## 4. Recommendations, phrased as evidence (no code)

### Step 3 (storage-engineer): storage fix candidates

- **S1 (defect, testable offline, interface: yes):** `NeptuneGraphRead` drops the error class: NeptuneAdapters.scala:170, :194, :198; GraphPorts.scala:248. Both FECD-1 (a, b) and L1 need a class at the port:
  - transient (5xx other than the resource limits, throttling, I/O);
  - resource-limit (`MemoryLimitExceededException`, `QueryLimitExceededException`);
  - timeout (already `complete = false` for paths);
  - permanent (4xx).
  
  Evidence: (f1) and section 3 row 3. Consumers are rag (Stages.scala:659-660, ResearcherResolver.scala:187-208) and services (ReadServices.scala:201-205). The allowlisted class name, never the message, is what makes the next live run decidable (R1).
- **S2 (defect, testable offline): no retry for graph reads** (NeptuneAdapters.scala:125; AwsStorageEnvironment.scala:128-129, :165-169).
  - Evidence for a bounded retry: 28 of 28 failures were single-attempt Neptune 500s that returned in at most 0.76 s (M2, M5), and AWS documents both resource-limit classes as retryable.
  - Bounds the evidence supports: inside the existing budget (`settings.timeoutMillis`; for paths, the remaining budget at NeptuneAdapters.scala:187); transient and resource-limit classes only; never timeouts or 4xx.
  - Jitter: the failures were synchronized (all inside 1 to 2 s, M1), and `RetryPolicy` has no jitter by design (Retry.scala:11-13), so retries by in-flight callers would land together again. That is a reason to consider jitter, or a per-call stagger, for graph reads only.
  - Counters: graph calls are absent from `retryReport` (AwsStorageEnvironment.scala:222-226).
  - Whether the retry rescues requests is **not decidable** (a).
- **S3 (candidate, not shown as a defect):** an in-process concurrency bound on Neptune calls (for example a semaphore), so that excess calls wait in the service instead of reaching a 4-thread instance (d, M7).
  - Evidence: failures only above about 4 concurrent queries.
  - It is a new bound tied to the instance class and needs a configuration contract (core config is domain-engineer's). I list it as "proposed later" unless the orchestrator routes it, because step D cannot show that Neptune rejects rather than queues (the docs disagree on this; (d)).
- **S4 (L1, tier 1):** the sites re-checked at HEAD are NeptuneAdapters.scala:193-194 (catch), :196-198 (Left), :221-227 (`isTimeout`), unchanged from 3r's lines. For **single-shot templates** (:166-170, the neighbors route), a resource-limit error is not a "bounded search": it should keep the retryable store-failure meaning for services. The bounded meaning applies to `PathBetween` only (section 3).

### Step 7 (service-engineer): breaker candidates

- **B1 (defect candidate, testable offline):** under concurrency, the breaker counts every in-flight failure. The failures recorded per opening were 3 / 7 / 18 at 5 / 10 / 20 clients (M1; Support.scala:78-85 acquire before the call, :102-110 count after it). Decision 7.1a names this case.
- **B2 (measured):** a 30 s open period longer than the rest of the load window meant no half-open trial ran inside any level (M1). Neptune's recovery time is therefore unmeasured, and the breaker alone produced 526 of the 554 graph-route errors (M2).
- **B3 (code fact):** the breaker trips on every Left alike (Support.scala:131-134; ReadServices.scala:202-205), including permanent errors and, after L1, bounded outcomes. With S1's class it could count only availability failures. One breaker serves every graph template per process (QueryService.scala:35).
- **B4 (observability, code fact):** GraphService writes no log line and no counter for the downstream error (ReadServices.scala:204, `case Some(_) => ApiError.downstream`). An allowlisted error-class counter or log field (no message, no host) is what would decide (c), (d) and (e) in a future live run.
- **B5 (interaction):** if S2's bounded retry absorbs transient errors, the breaker sees fewer Lefts. Step 7 should test the breaker against the post-retry port behavior (decision 7.1a: "a 502 for a transient error that step 3's bounded retry now absorbs").

### Proposed later (not in this step's scope)

- rag (f2): the resolver's `.toOption` hides graph failures (ResearcherResolver.scala:187-208). A note or counter when a candidate's graph context failed. Owner: rag-engineer.
- experiment-engineer: a future D-read design should sustain load past the breaker's open period, or disable the breaker for the measurement, and record per-request service-side error classes (f3).

## INTERFACE HANDOFF (read-only step: no files changed)

- Files changed: none.
- Facts handed to step 3 and step 7, each with file:line above:
  - the port's Left type `String` (GraphPorts.scala:248);
  - the dropped transient flag (NeptuneAdapters.scala:170, :194, :198);
  - the L1 sites (NeptuneAdapters.scala:193-198, :221-227);
  - the breaker's acquire and record split (Support.scala:78-110);
  - GraphService's error mapping (ReadServices.scala:201-205);
  - the settings `circuitFailureThreshold` 3 and `circuitOpenMillis` 30000 (config/course.json:103-104), `graphTimeoutMillis` 20000 (:89), `maxConcurrentQueries` 4 (:98);
  - 12 HTTP threads (ServiceServer.scala:56).
- Thread-safety: `CircuitBreaker` is synchronized (Support.scala:67); `NeptuneGraphRead` holds no mutable state besides the injected clock.
- Limits: Neptune worker threads = 2 x vCPU (cited); db.t4g.medium vCPU count = 2 (unverified).

## aws-docs sources

- https://docs.aws.amazon.com/neptune/latest/userguide/access-graph-queuing.html: 2 x vCPU worker threads; queue of 8,192 then `ThrottlingException`; MainRequestQueuePendingRequests at five-minute granularity.
- https://docs.aws.amazon.com/neptune/latest/userguide/instance-types.html: two execution threads per vCPU; per-thread memory and OOM; t3 and t4g are constrained and not for production.
- https://docs.aws.amazon.com/neptune/latest/data-api/API_ExecuteOpenCypherQuery.html: `MemoryLimitExceededException` 500 (retryable), `QueryLimitExceededException` 500 (active-query limit, retryable), `TimeLimitExceededException` 500, `FailureByQueryException` 500, `TooManyRequestsException` 429, `QueryLimitException` 400.
- https://docs.aws.amazon.com/neptune/latest/data-api/CommonErrors.html: `InternalFailure` 500, `ThrottlingException` 400, `ServiceUnavailable` 503.
- https://docs.aws.amazon.com/neptune/latest/userguide/cw-metrics.html: metrics sent only when non-zero; aggregation granularity 5 minutes.
- db.t4g.medium concurrency figure for openCypher specifically: unverified (no page names a per-class number; derived as 2 x vCPU with vCPU = 2 unverified).

## VALIDATION EVIDENCE

- `shasum -a 256 docs/agent-runs/phase-16/prompt.md` -> c75cc5f895faed13880a69ec285231c5498fd50924c7b875fe7e02cce1707dc8 (matches the work order).
- `git rev-parse HEAD` -> 9da45ab616a764e449f74f25feca85ed77d6dfe4.
- `git status --short` (at the end) -> ` M build.sbt`, ` M project/PackageCheck.scala`, `?? config/emulator.json`, `?? docs/agent-runs/phase-16/`, `?? project/BuildSelfTest.scala`, `?? scripts/emulator/`: other steps' in-progress files (step 1 build-engineer, I1 infra-engineer); none written by this step.
- `wc -l` D-read: load-requests.csv 2,252 lines (2,251 rows + header), load-levels.csv 5, id-check.csv 30, service-samples.csv 79.
- `grep -n DOWNSTREAM_FAILURE load-requests.csv` -> 28 rows (3 in L2, 7 in L3, 18 in L4), epochs 332-333 / 416-417 / 490-491.
- metric-data.json (D-read) `m23_0` x 60 at 00:19 / 00:21 / 00:22 = 15 / 16 / 42; `m26_0` x 60 = 3 / 7 / 18.
- metric-data.json (D-query) `m30_0` 23:51 = 0.1167/s (7 requests), 23:50 and 23:52 to 23:54 = 0; `m33_0` 23:51 = 0.0167/s (1 error).
- No sbt, no script, no aws-api, no network command; aws-docs 1 search + 4 reads.
files changed after these runs: none
