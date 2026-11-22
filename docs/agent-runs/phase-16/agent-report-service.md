# agent-report-service (Phase 16, session 2, step 7, service-engineer, wave closer)

STATUS: COMPLETE
OWNER: service-engineer
WORK ORDER: docs/agent-runs/phase-16/prompt.md (sha256 c75cc5f895faed13880a69ec285231c5498fd50924c7b875fe7e02cce1707dc8, checked with shasum), HEAD 9da45ab616a764e449f74f25feca85ed77d6dfe4 (checked) plus uncommitted session 2 work. Decision 7 in tier order. Tier 1: 7.1a FECD-1 breaker side, 7.1b EmbeddingWorkerEmulatorSuite. Tier 2: FECD-2, H-04, H-05, H-06, K-01, G-23, route time limits, and the rag code `path-query-bounded` for the explain contract resources. Decision 2 status lines: S-8, C-09, C-12, C-13, custom metrics. Wave closer: yes.
FILES CREATED:
- /Users/brian/Developer/repos/scholarchain/modules/services/src/main/scala/org/scholarchain/services/GraphReads.scala
- /Users/brian/Developer/repos/scholarchain/modules/services/src/test/scala/org/scholarchain/services/GraphBreakerSuite.scala
- /Users/brian/Developer/repos/scholarchain/modules/services/src/test/scala/org/scholarchain/services/RouteTimeLimitSuite.scala
- /Users/brian/Developer/repos/scholarchain/modules/services/src/test/scala/org/scholarchain/services/EmbeddingWorkerEmulatorSuite.scala
FILES MODIFIED (all under /Users/brian/Developer/repos/scholarchain/modules/services/src/):
- main/.../Support.scala: CircuitBreaker generations, `onChange` callback, `openCount`, `staleOutcomes`, Slot class; H-06 redaction in `ServiceLog.external`
- main/.../QueryService.scala: Breakers `onChange`, `ReadTimeLimit`, `AppContext.readLimit` and `timedRead`; workflow breaker outcomes reported with the request's own slot
- main/.../ReadServices.scala: graph routes now go through `GraphReads`; evidence and explain reads go through `timedRead`
- main/.../HealthService.scala: G-23 reason lines, `HealthService.reasonOf`
- main/.../ServiceServer.scala: `readDeadlineMillis`, `embeddingDrainMillis`, a `read` pool, `circuit-state` lines, a classified graph readiness check, health log, worker indexes
- main/.../EmbeddingWorker.scala: H-04 handling in two calls; `EmbeddingWork.indexes`
- main/.../Main.scala: Composition passes the index names
- main/.../Errors.scala: `READ_TIMEOUT`
- main/.../ServiceLimits.scala: `ReadRouteDeadlineMillis`, `MaxReadRouteDeadlineMillis`
- test/.../ServiceTestKit.scala: SlowGraphStores gains `graphError` (a classified failure) and `readDelayMillis`; new harness options
- test/.../EmbeddingWorkerSuite.scala (H-04, H-05, H-06), HttpBehaviorSuite.scala (G-23), ReadRoutesSuite.scala (`path-query-bounded`)
COMMANDS RUN:
- sbt, one process at a time:
  - `sbt -batch "services/Test/compile"` (twice; the second together with `"services/EmulatorTest/compile"`)
  - `sbt -batch "services/testOnly org.scholarchain.services.*"`
  - `sbt -batch "services/testOnly org.scholarchain.services.GraphBreakerSuite"` (mutation check)
  - `sbt -batch "services/testOnly org.scholarchain.services.EmbeddingWorkerSuite"` (mutation check)
  - `sbt -batch "services/testOnly org.scholarchain.services.*" "contractTests/testOnly org.scholarchain.contracts.runtime.*"` (final targeted run)
  - `sbt check` (background, output to a scratchpad log, waited for with bounded grep loops)
  - `sbt -batch packageCheck "services/EmulatorTest/compile"`
- Read-only: shasum, git rev-parse, git status --short, git diff --stat, grep, head, tail -n, wc, ls.
- Commands not on the list (disclosed): most Bash calls started with `cd <repo> &&`. I used one `find … | xargs wc -l` and one `sed -n 55,75p` (both read-only). The waits were `for … sleep 15` loops over grep, the form the work order allows the orchestrator. No script, Docker, emulator, AWS, network, `sbt clean`, `runMain`, or EmulatorTest/AwsTest test run.
TEST RESULTS (all observed):
- Targeted, after the last source change: services "Passed: Total 136, Failed 0, Errors 0, Passed 136, Ignored 1" (the ignored suite is EmbeddingWorkerEmulatorSuite, tagged and excluded from Test). contractTests runtime.* "Passed: Total 43, Failed 0".
- New and changed suites: GraphBreakerSuite 7/7, RouteTimeLimitSuite 4/4, EmbeddingWorkerSuite 12/12, HttpBehaviorSuite 17/17, ReadRoutesSuite 13/13, ComponentsSuite 9/9, CapacityAndFailureSuite 9/9.
- Mutation checks, run and seen failing, then reverted (grep confirmed the revert):
  - Stale-slot guard turned off in `recordFailure(slot)`: GraphBreakerSuite "Failed: Total 7, Failed 3" (the two B1 tests and the B5 no-retry test).
  - H-04 split and H-06 redaction both turned off: EmbeddingWorkerSuite "Failed: Total 12, Failed 2" (the H-04 and H-06 tests).
- Full `sbt check`: exit=0, "[success] Total time: 988 s", 182 suite runs, 0 with failures. The marker now reads `timestamp=2026-11-22T03:57:30.283150Z`, `module_tests=2282`, `contract_tests=399`.
- `sbt -batch packageCheck`: "packageCheck: ok", [success].
- `sbt -batch "services/EmulatorTest/compile"`: [success].
VALIDATION RESULTS: see VALIDATION EVIDENCE. No failure outside my territory. F8 and F9 pass inside the full check (contract tests 399, 0 failed).
ASSUMPTIONS:
- A1. The breaker defect is in its state handling, not in its settings. The threshold (3) and open period (30 s) stay as configured. The 30 s open period being longer than the load window (step D B2) is configuration, not a code defect. Nothing was raised.
- A2. `graph-permanent` (a 4xx: the store answered) and `graph-invalid-query` (refused before any store call) are not availability failures, so they do not trip the breaker. Both still answer 502 DOWNSTREAM_FAILURE, so the error model is unchanged. `graph-unclassified` still counts, as it did before Phase 16.
- A3. The emulator returns `https://` queue URLs (storage A4). The embedding queue pair is `scholarchain-emu-embedding` and `-dlq` with maxReceiveCount 5 (OPERATIONS.md:79). The value is read from RedrivePolicy, and 5 is used only if that read fails.
- A4. The poison body is storage's job-queue envelope `{"id","payload"}`, sent through `SqsWorkQueue.enqueue`. That way the services test sends nothing with the raw SDK, and rag refuses the body as `missing-field`. Because of this, the worker log line shows `taskId` null. State isolation uses the run-tagged `id` inside the body.
- A5. The 10 s read limit is an engineering choice (`estimate`). It sits above storage's worst-case key-value retry wait of about 6.2 s (5 retries, 200 ms doubling, from Retry.scala:19-22 and the `vectors` settings in config/course.json:55-57) and below syncDeadlineMillis (25 s).
RISKS:
- R1. Everything here is `fixed in code, tested offline, not validated live`. Whether the breaker change would have cut the 526 rejections of D-read cannot be decided from frozen data. It removes the extension of the open period by in-flight failures and the double trial, not the opening itself.
- R2. EmbeddingWorkerEmulatorSuite has been compiled only (`runtime: untested`). Its first run is at E14.
- R3. The new `read` pool adds threads (maxConcurrentQueries + 8) per process. A full pool answers 503 OVERLOADED on the evidence and explain routes.
- R4. The emulator suite calls the SQS SDK directly for `GetQueueUrl` and `GetQueueAttributes`, as storage's handoff prescribes (agent-report-storage.md:148), in test code only. Services production code makes no SDK call.
MANUAL ACTION REQUIRED: at E14, after `source scripts/emulator/env.sh` and `scripts/emulator/setup.sh` on a fresh emulator, run `sbt services/EmulatorTest/test` (or `sbt emulatorTest`) in the [EMULATOR] terminal.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: R-1b (code-reviewer) reviews step 7, which closes the wave. The full check and packageCheck passed after the last source change.

## INTERFACE CHECK (every name used from the producers, file:line; no mismatch)

Step D (agent-report-storage-D.md):
- B1-B5 at :159-163. The code facts they rest on were re-read at HEAD+wave: Support.scala's acquire/record split (now :84 onward), ReadServices.scala's graph mapping, and QueryService.scala's single graph breaker.

Step 3 (agent-report-storage.md):
- Graph port:
  - `GraphReadPort.runClassified` (:129; GraphPorts.scala:339-340).
  - `GraphReadError` codes (:130; GraphPorts.scala:284-325). The match on Invalid, Transient, ResourceLimit, Timeout, Permanent and Unclassified is exhaustive.
  - `GraphStop` (:132; GraphPorts.scala:227-244), not used directly.
- Retry:
  - `GraphRetry.Default` and `Off` (:135).
  - The `NeptuneGraphRead(client, settings, clock, retry, sleeper, jitter)` constructor (:136; NeptuneAdapters.scala:255-262).
  - Counters `retries` and `recovered` (:137).
- Emulator wiring (storage test tree):
  - `EmulatorSafety.check`, `usableResultsDir`, `SuiteRules` (:144; EmulatorSafety.scala:73-77, :97-102, :150-155).
  - `EmulatorClients.credentials` and `forEmulator` (:145; EmulatorClients.scala:61-67, :114).
  - `EmulatorResultWriter.open`, `writePreflight` and `demonstration` (pattern at EmulatorResults.scala:115).
  - `EmulatorRecorder` (call, observed, failures, allPassed, failureSummary, recorded), `EmulatorChain`, `EmulatorFailure.classify`, `EmulatorOutcome` (:146; EmulatorResults.scala:22-34, :176-364).
  - `MaskedFailures` and `MaskedTestFailure` (:147; FailureMasking.scala:50-55).
  - `EmulatorAdaptersEmulatorSuite.maxReceiveCountOf` and `AssumedMaxReceiveCount` (suite :552, :558).
- Storage composition:
  - `AwsStorageEnvironment.create(cfg, clients, None, sleeper)` (:148; AwsStorageEnvironment.scala:251-257).
  - `reembedder(embedder, sleeper)` (:215).
  - `ServiceStores.embeddingQueue(env, url, waitSeconds)` (ServiceStores.scala:118-128).
- Two differences, not mismatches:
  - The handoff route is unchanged. `env.sqsClient` is `private[storage]` (AwsStorageEnvironment.scala:228), so the suite uses the public `AwsSdkClients.sqs` from `forEmulator`.
  - `Recording` is `private[emulator]`, so the services suite has its own decorator built from `EmulatorRecorder.observed` and `EmulatorFailure.classify`.
- Environment variable names: :150 (EmulatorSafety.scala:33-50).
- Result-line format: :152 (EmulatorResults.scala:44-67).

rag (agent-report-rag.md):
- `path-query-bounded` = `AbstentionCodes.PathQueryBounded` (:184; Abstentions.scala:50), with its text at Abstentions.scala:80.
- `PathQueryFailed` (Abstentions.scala:40), `Unclassified` (:57), `Table` (:60).

Build (agent-report-build.md):
- `RequiresEmulator` (build.sbt:115), `EmulatorTest` (:117-118, include/exclude :146-149, Test exclusion :175).
- Applied to services at build.sbt:453 and :462. Forked group and trust store at :136-137 and :150-156 (working directory = module folder, so SCHOLARCHAIN_CONFIG_FILE and SCHOLARCHAIN_EMULATOR_RESULTS_DIR must be absolute; EmulatorSafety enforces both). Alias at :708.
- `storage % "compile->compile;test->test"` at build.sbt:457.

R-1a P16-A-08 (runClassified not overridden by rag's trace wrapper, Stages.scala:106-147):
- Not hit. Services call `runClassified` on `app.runtime.stores.graph`. For AWS that is `env.runtimeStores.graph`, the bare NeptuneGraphRead, which overrides it (RuntimeService.scala:393; RuntimeStores.scala:42). It is not rag's per-query traced copy.
- If a future runtime handed services a traced store, every failure would read `graph-unclassified`. That still counts against the breaker (fail-safe), but its class would be lost. Noted for R-1b.

## Items and fix status

| Item | Tier | Status | Where | Fix status | Test that fails without it |
|---|---|---|---|---|---|
| 7.1a FECD-1 B1: stale outcomes from in-flight calls changed breaker state (extended the open period, freed or failed the half-open trial, closed an open breaker) | 1 | done (defect shown offline) | Support.scala CircuitBreaker (generations, slot outcomes); QueryService.scala finish(…, slot); GraphReads.scala | fixed in code, tested offline, not validated live | GraphBreakerSuite "B1: 10 concurrent callers…", "B1: a stale failure never frees…", "B1: a stale success…" (mutation run: 3 of 7 fail) |
| 7.1a B3: breaker tripped on every Left alike | 1 | done | GraphReads.counts/classified/run | fixed in code, tested offline, not validated live | GraphBreakerSuite "B3: permanent and invalid…", "B3: a thrown permanent StoreException…" (old code opens on permanent) |
| 7.1a B4: no log for graph downstream errors | 1 | done | `graph-read-failed` and `circuit-state` lines | fixed in code, tested offline, not validated live | B1 and B3 tests assert the lines (absent before) |
| 7.1a B5: breaker against the retried port | 1 | done (test of the interaction; no extra code) | GraphBreakerSuite B5 with NeptuneGraphRead and a fake client | fixed in code, tested offline, not validated live | "B5: with storage's bounded retry…" (closed, 8 answers) next to "B5: without the retry…" (opens once, 5 stale) |
| 7.1a B2: 30 s open period longer than the load window | 1 | not a code defect (configuration; never raised) | – | – | – |
| "no half-open probe" | 1 | not a defect: a half-open trial exists (Support.scala acquire) | – | – | ComponentsSuite circuit tests |
| 7.1b EmbeddingWorkerEmulatorSuite | 1 | done (compile only) | EmbeddingWorkerEmulatorSuite.scala | fixed in code, tested offline, not validated live | under Test: ignored by tag; `services/EmulatorTest/compile` success |
| path-query-bounded in explain contract resources | 1 | done: no resource lists codes (api/v1/explain.json has `abstentionReasons: []`); a test was added | ReadRoutesSuite "Phase 16: explain shows rag's new code…" | tested offline | asserts rag's own text, not the unclassified text |
| FECD-2 permit after syncDeadlineMillis | 2 | not a defect | QueryService.scala:388-389: the permit is released when the workflow ends (`finally permits.release()`), not at the deadline. Releasing it at the deadline would let more than maxConcurrentQueries workflows run. The existing test CapacityAndFailureSuite "timeout: … it keeps its permit until it ends" shows the release. Residual: a workflow that never ends would keep its permit; it is bounded only by rag's own time limits | – | existing test |
| H-04 poison release waits for the batch | 2 | done | EmbeddingWorker.scala:101-126 | fixed in code, tested offline, not validated live | EmbeddingWorkerSuite "H-04: …" (mutation run fails) |
| H-05 no test of the stop-time bound | 2 | done | ServerOptions.embeddingDrainMillis (default 60000; longer is refused) | fixed in code, tested offline, not validated live | EmbeddingWorkerSuite "H-05: close returns within the drain bound…" |
| H-06 taskId logged without redaction | 2 | done | Support.scala ServiceLog.external | fixed in code, tested offline, not validated live | EmbeddingWorkerSuite "H-06: …" (mutation run fails) |
| K-01 service task definitions carry the same environment | 2 | not mine: a Terraform guard. Owner is infra-engineer (phase-15 orchestrator-summary-s2.md:117). Routed, not built | – | – | – |
| G-23 /readyz graph check does not log why | 2 | done; no retry added (reasons in the HealthService doc) | HealthService.scala reasonOf and `readiness-check-failed`; ServiceServer graph check through runClassified | fixed in code, tested offline, not validated live | HttpBehaviorSuite "G-23 readiness: …" |
| Route time limits, evidence and explain | 2 | done | AppContext.timedRead; ServerOptions.readDeadlineMillis; READ_TIMEOUT | fixed in code, tested offline, not validated live | RouteTimeLimitSuite (4 tests) |

## Route table (changed behavior only)

| Route | New answers |
|---|---|
| GET /v1/evidence/{id}, GET /v1/explain/{queryId} | 504 READ_TIMEOUT (retryable, Retry-After 2) past `readDeadlineMillis`; 503 OVERLOADED when the read pool is full. Unchanged: 503 CIRCUIT_OPEN, 502 DOWNSTREAM_FAILURE |
| GET /v1/graph/nodes/{id}, …/neighbors | Unchanged statuses (502, 503). Breaker counting now uses the error class |
| GET /readyz | Unchanged body and statuses. Adds one log line per failed check |

## Error model coverage
- New code `READ_TIMEOUT` (504, retryable). ComponentsSuite's status set still holds, since 504 was already present.
- No other code changed.

## Limits
- `ServiceLimits.ReadRouteDeadlineMillis` = 10000. The setting `ServerOptions.readDeadlineMillis` is accepted in 1..60000 (`MaxReadRouteDeadlineMillis`).
- `ServerOptions.embeddingDrainMillis` defaults to 60000 and is accepted in 1..60000, so it is never longer than the default.
- `read` pool: maxConcurrentQueries + 8 threads and the same queue.
- Breaker threshold and open period unchanged.

## INTERFACE HANDOFF

Settings and defaults:
- `ServerOptions(…, readDeadlineMillis: Long = ServiceLimits.ReadRouteDeadlineMillis /*10000*/, embeddingDrainMillis: Long = ServiceLimits.EmbeddingDrainMillis /*60000*/)`. `ServiceServer.start` returns Left outside the ranges above.

Public signatures:
- `CircuitBreaker(name, threshold, openMillis, clock, onChange: (String, String) => Unit = no-op)`, with:
  - `recordSuccess(slot)`, `recordFailure(slot)`;
  - the slot-less `recordSuccess()` and `recordFailure()`, kept;
  - `openCount`, `staleOutcomes`;
  - `CircuitBreaker.Slot` is now a class, equal by `trial`, with `Slot(trial)`.
- `Breakers(settings, clock, onChange = no-op)`.
- `final class ReadTimeLimit(pool, deadlineMillis)`.
- `AppContext(…, readLimit: Option[ReadTimeLimit] = None)` and `timedRead(route, traceId)(f)`.
- `object GraphReads { counts; classified; run(breaker, port, log)(q) }`.
- `HealthService(role, checks, pool, clock, log: Option[ServiceLog] = None)` and `HealthService.reasonOf`.
- `EmbeddingWork(queue, handlerOf, indexes: Set[String] = Set.empty)`.
- `EmbeddingWorker(queue, handler, log, knownIndexes = Set.empty)`.

Error codes:
- New: `READ_TIMEOUT` (504, retryable).
- Allowlisted log values: `graph-*` from storage's `GraphReadError.Codes`, `deadline-passed`, `rejected`, `store-failure`, `unexpected-exception`.

Log line formats (each also carries `time`, `role`, `event`; never store text):
- `graph-read-failed`: `template`, `code`, `counted` (bool), `breaker` (state after the failure).
- `circuit-state`: `breaker` (workflow|graph|reads), `state` (open|closed).
- `read-deadline-passed`: `route` (evidence|explain), plus `traceId` when known.
- `readiness-check-failed`: `check`, `reason`, and `exception` (a class name) only for `unexpected-exception`.
- `reembed-delivery` (rag's line): every string value now passes the Redactor.

Emulator suite:
- Command: `sbt services/EmulatorTest/test` (or `sbt emulatorTest` for both projects).
- Files written only when SCHOLARCHAIN_EMULATOR_RESULTS_DIR is set: `results-EmbeddingWorkerEmulatorSuite.jsonl` and `demonstration-EmbeddingWorkerEmulatorSuite.txt`.
- Demonstration line: `demonstration dead-letter: demonstrated` or `demonstration dead-letter: not demonstrated (REASON)`.
- Result-line step: `worker-dead-letter`.
- Variables read (names only): the storage contract's list, plus SCHOLARCHAIN_CONFIG_FILE, SCHOLARCHAIN_EMULATOR_RESULTS_DIR and SCHOLARCHAIN_EMULATOR_RUN_ID.

Fixtures: none on disk. Tests use stub ports, a fake NeptunedataClient, StubQueue, and the in-memory harness.

Thread-safety:
- CircuitBreaker is synchronized; `onChange` is called outside the lock.
- GraphReads and EmbeddingWorker hold no shared state beyond atomics.
- RecordingReceiveQueue uses atomics.

Limits: see "Limits" above.

## Runtime lines
- CircuitBreaker generations, GraphReads, timedRead, readiness reasons, EmbeddingWorker two-call handling, ServiceLog redaction: runtime: untested (unit-tested offline in Test).
- EmbeddingWorkerEmulatorSuite (`sbt services/EmulatorTest/test`): runtime: untested (compile only; first run E14).

## Decision 2 status lines (service-engineer)
- S-8: still present. ServiceServer.scala:196 (`callerRuns = true`): when every HTTP thread is busy, the dispatcher runs the request itself and stops accepting connections. There is no 503 accept-side path.
- C-09: still present. QueryService.scala:388-389: a half-open trial task that throws (ExecutionException) reports no outcome and does not cancel its slot, so the workflow breaker's `trialInFlight` stays set. The generation change does not clear it, because the trial is the current generation.
- C-12: still present. ServiceServer.scala:252-265: readiness checks only metrics, graph and jobs. Role `evidence` does not check the evidence or run-ledger tables, and role `worker` does not check the embedding queue.
- C-13: still present. HttpAdapter.scala:220-227: authentication runs before the token bucket, so unauthenticated requests are not rate-limited.
- Custom metrics: still present. modules/services/src/main has no CloudWatch metric emission (grep for cloudwatch/PutMetricData/EMF: none). Only structured log lines exist, now including `graph-read-failed`, `circuit-state`, `read-deadline-passed` and `readiness-check-failed`.

## Proposed later
- A `finally`-style cancel of the workflow trial slot on a thrown task (C-09). It is small with the Slot type, but out of scope here.
- Readiness checks per role (C-12).
- Optionally counting breaker rejections (503s) as a counter, to make B2 measurable live.

## VALIDATION EVIDENCE
- `shasum -a 256 docs/agent-runs/phase-16/prompt.md` -> c75cc5f895faed13880a69ec285231c5498fd50924c7b875fe7e02cce1707dc8; `git rev-parse HEAD` -> 9da45ab616a764e449f74f25feca85ed77d6dfe4.
- `sbt -batch "services/testOnly org.scholarchain.services.*" "contractTests/testOnly org.scholarchain.contracts.runtime.*"` -> "Passed: Total 136, Failed 0, Errors 0, Passed 136, Ignored 1"; "Passed: Total 43, Failed 0, Errors 0, Passed 43" (21:40:52).
- `sbt check` -> "[success] Total time: 988 s (0:16:28.0), completed Nov 21, 2026, 9:57:30 PM", exit=0. 182 suite runs, 0 with failures. Log line: "Updated …/.claude/.last-test-run (module_tests=2282, contract_tests=399)". `head -n 5 .claude/.last-test-run` -> timestamp=2026-11-22T03:57:30.283150Z, module_tests=2282, contract_tests=399.
- `sbt -batch packageCheck "services/EmulatorTest/compile"` -> "packageCheck: ok", [success], [success] (21:57:48).
- Mutation checks were reverted before these runs (grep for the mutation text: no match).
- `git status --short modules/services` -> the 13 modified and 4 new files listed above. No file outside modules/services was written by this step.
files changed after these runs: none
