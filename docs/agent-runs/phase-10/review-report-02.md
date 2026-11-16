STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: docs/agent-runs/phase-10/prompt.md, step 7, services review (decision 8b) of modules/services/**. I read rag and storage only where I needed them to judge services.
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools: Read, Grep, Glob)
TEST RESULTS: I ran nothing. In /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/aaf86ae1-6986-4770-9813-b764b8dca793/scratchpad/svc-check.log I saw:
- line 9926: `Module tests: suites=109 executed=1861 passed=1861 failed=0 errors=0 skipped=3`
- line 9890: `Contract tests (no AWS): ... executed=336 passed=336`
- lines 9928-9929: `[success] Total time: 914 s`, `exit=0`
- Per-suite totals for the 9 services suites (lines 6271-8369) add up to 88, which matches the report.
- services is not forked (build.sbt:339-375), so the cwd-dependent thin-route test ran. The skipped count stayed at 3.
VALIDATION RESULTS: I read every main and test source file in modules/services, the frozen examples I needed, logback.xml and the services config. Each finding below was checked against the code.
ASSUMPTIONS: I can't run git. I judged ownership from the session-start git status (truncated in the untracked list), directory listings, and a grep of tests/** for services references.
RISKS:
- S-1 can't be fixed inside modules/services alone, because storage's `JobRequest` has no field for the unapplied request fields.
- The minor findings S-3 to S-6 are listed for you and are not repaired automatically.
MANUAL ACTION REQUIRED: Decide S-1: either repair it through storage plus services, or accept "async results omit `notes`" as a documented deviation.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Step 8 repair pass:
- rag-engineer: U-1 and U-2 (from the upstream review).
- service-engineer: S-1 and S-2. S-1 may need storage-engineer first.
- Then step 9 re-checks the repaired findings only.

VERDICT: REQUEST_CHANGES

Two major findings remain: S-1 and S-2. There are no blocking findings.

## Rulings (decision 8b)

### Spec gate items

1. **Routes embed no retrieval: MET.**
   - Routes.scala and HttpAdapter.scala import nothing from storage or rag.
   - Each handler calls exactly one application service (Routes.scala:29-75).
   - ComponentsSuite:171-189 checks the imports.
2. **Error responses include trace IDs: MET.**
   - Every `Left(ApiError)` becomes `ErrorBody(..., traceId, ...)`, and the same trace ID goes into the `X-Trace-Id` header (HttpAdapter.scala:114-121, 148-149).
   - Tests: HttpBehaviorSuite:179-199 (404, 405, 400, 401, unknown paths), CapacityAndFailureSuite:22 and :127 and :162 (504, 500), ReadRoutesSuite:29.
3. **Health checks expose no secrets: MET.**
   - `/healthz` returns `{status, role}`. `/readyz` returns `{status, role, checks{name: ok|fail}}` (HealthService.scala:31, 69-103).
   - HttpBehaviorSuite:61-80 checks both bodies for key values, variable names, endpoints and account IDs.
4. **Request and response examples match contract tests: MET.**
   - GoldenExamplesSuite:65-74 compares the bodies that GoldenScenario gets from a real running server with the frozen files, byte for byte.
   - Each example also round-trips through its model (:48-50).
5. **An end-to-end API test reaches an evidence-backed answer: MET.**
   - QueryApiSuite:87-112: POST, non-abstained answer, a `verified` claim, GET evidence, then explain with the same evidence IDs.
   - CompositionSuite:83-113 does the same through `RuntimeService.local`.

### Thin routes: MET
- No `aws`, `dynamo`, `neptune` or `local` import in the route or adapter files.
- No key building: the idempotency key is passed raw to `IdempotencyStore`, which hashes it.
- No query text, and graph access goes only through typed `GraphQuery` templates in GraphService.

### Trace ID on every non-2xx except /readyz; no downstream text to clients: MET
- Error messages are fixed per code (Errors.scala).
- `details` holds only field names, fixed problem texts, window labels, job error codes or job IDs.
- A rag Left becomes a fixed 502 (QueryService.scala:173-181, 339-342), and an invalid stored result becomes a fixed 500 (:114).
- Tests: CapacityAndFailureSuite:46 and :163.
- Not covered: protocol errors that the JDK HttpServer answers itself (malformed request line) never reach the handler. This is outside services' control and is an advisory note only.

### No secret, key, config value, table, bucket, endpoint or account ID in health, readiness, errors and logs
- **Health, readiness, errors:** MET.
- **Request log lines:** MET. They hold the route template, never the raw path, body or headers (HttpBehaviorSuite:238-270).
- **Redactor:** MET exactly as 7i defines it: key values, `arn:`, 12-digit numbers, `s3://` (Support.scala:152-165; test HttpBehaviorSuite:272-290).
- **Operational `detail` text:** PARTIAL, see S-4 (minor).

### Authentication: MET
- **Constant time.** Both keys are stored only as SHA-256 digests. Both `MessageDigest.isEqual` comparisons always run on fixed-length digests (Auth.scala:25-35).
- **Fails closed.** A missing or unknown key is Anonymous and gets 401 (HttpAdapter.scala:220-222).
- **Startup refusal.** Unset, blank or equal values refuse start, and the message names only the variable (Auth.scala:47-63). Tested for both `ApiKeys.load` and `ServiceServer.start` (HttpBehaviorSuite:292-336).
- **Read-only key on POST.** Gets 403 (HttpAdapter.scala:242-243; HttpBehaviorSuite:45-47).

### Idempotency and worker rules (7e, 7f): MET in code, with a test gap (S-2) and a minor edge case (S-3)
- **Sync order:** create the job, claim it to running with a `jobLeaseMillis` lease, claim the key, then run (QueryService.scala:258-286).
- **Async order:** create the job, enqueue, then claim the key (QueryService.scala:389-404).
- **Lost claim:** sync supersedes its own running job (:275-276). Async supersedes only if the job is still `queued` (:405-412).
- **Follow rules:** different fingerprint gives 409 CONFLICT; succeeded gives a 200 replay or a 202 replay; queued or live lease gives 409 IN_PROGRESS; failed gives 409 JOB_FAILED; a sync job with an expired lease is written LEASE_EXPIRED (QueryService.scala:436-477).
- **Worker** (Worker.scala:94-181):
  - succeeded or failed: ack;
  - live lease: left alone;
  - attempts at the limit: ATTEMPTS_EXHAUSTED, then ack;
  - claim or reclaim; losing it leaves the message;
  - validate rejection: WORKFLOW_FAILED, then ack;
  - success: `succeed`, then ack;
  - ResultTooLarge: failed, then ack;
  - ConditionFailed: discard and leave;
  - Left: `release`, then queue release, or ATTEMPTS_EXHAUSTED.
- **Ack only after the terminal write:** `terminal` acks only after `Written` (:117-130). Success acks after `Written` (:148-155).
- **Permits held until the workflow ends:** the permit is released in the task's own `finally` (QueryService.scala:302-304). Test: CapacityAndFailureSuite:12-34.
- **No claim of exactly-once:** the only mention, Worker.scala:53-55, says execution is "not exactly once".
- **U-7 relay:** applied. The holder carries each stage write's returned record into `succeed` and `release` (QueryService.scala:157-172; Worker.scala:145-179).
- **U-1 contract:** a Left from `workflow.run` maps to 502 (sync) or to a retryable release (worker).

### Cursor validation and the paging cap (7h): MET
- The decoder accepts only the exact key set, `v == 1` and whole-number ints (ReadServices.scala:154-188).
- It refuses a negative offset, an offset not a multiple of `limit`, or an offset not below the cap. Every field must equal the normalized request and the current graph run (:277-296).
- The service fetches `min(offset + limit, cap)` rows. Past the cap it returns `truncated: true` with no cursor (:306-332).
- Tests: ReadRoutesSuite:186-282, including a default limit with `graphResultLimit` 2.

### Every error class produced and tested: MET
| Status | Tested at |
|---|---|
| 400 | QueryApiSuite:114-159 |
| 401 and 403 | HttpBehaviorSuite:35-59 |
| 404 | ReadRoutesSuite:25-42, HttpBehaviorSuite:149-199 |
| 409 | IdempotencySuite:40-131, QueryApiSuite:51 |
| 422 | QueryApiSuite:161-236, ReadRoutesSuite:163-170 |
| 429 | HttpBehaviorSuite:113-147 |
| 502 | CapacityAndFailureSuite:36-47, 104-117 |
| 503 OVERLOADED and CIRCUIT_OPEN | CapacityAndFailureSuite:25-27, 48-50 |
| 504 | CapacityAndFailureSuite:19-21 |
| 500 INTERNAL and RESULT_TOO_LARGE | CapacityAndFailureSuite:119-165 |
| 405, 413, 415 | also covered |

### One trace ID across query role, job, worker and every stage line: MET
QueryApiSuite:238-271 checks the request line, `job.traceId`, the `job-started` and `job-succeeded` lines, and all 11 stage lines (traceId, jobId, queryId). It also checks the sync stage lines.

### Golden tests compare real server output: MET (see gate item 4)
Fixed IDs, a ManualClock, a workflow clock of 0, and fixed `traceparent` and `X-Request-Id` headers.

### Pools bounded; no unbounded thread or task creation: MET
- Every pool is a `ThreadPoolExecutor(n, n, ArrayBlockingQueue(cap))` (Support.scala:277-292).
- The pools are `http` (maxConcurrentQueries+8 threads, queue 256, caller-runs), `query` (n, queue n, with semaphore admission), `worker` (w, queue w) and `ready` (3, queue 9) (ServiceServer.scala:132-143).
- The only other thread the services create is the named shutdown-hook thread.
- See S-8 (advisory) on caller-runs.

### Shutdown leaves no scholarchain-* thread: MET for the tested path
HttpBehaviorSuite:338-358, after a sync query, readiness and the worker loop. See S-5 (minor) on the drain order and timing.

### Spec-required tests and design invariants in 7k: present
- All 10 spec-required tests are present and meaningful.
- **Weaknesses:**
  - The "async result identical to sync" test only covers a request with no unapplied fields, which hides S-1.
  - "Two concurrent first requests with one key run one workflow" is timing-dependent, and the lost-claim branch has no deterministic test (S-2).
- **Every other invariant is present and would fail without its behavior:**
  - idempotency: conflict, in progress, replay after a 504, failed replay, LEASE_EXPIRED, TTL;
  - capacity after a 504;
  - all six worker invariants (deterministic, using GatedModel and ManualClock);
  - restart;
  - cursors;
  - auth startup;
  - rate limit;
  - request shape and out-of-bounds 422;
  - trace;
  - readiness deadline and single-flight;
  - trace ID equals the header;
  - another role's route is 404;
  - redaction;
  - shutdown;
  - aws stubs with 0/0/0 calls.

### Ownership: MET
- All new files are under modules/services/**.
- docker/services/Dockerfile is not in the modified list, and tests/** has no reference to `org.scholarchain.services`.
- No constitutional file is modified. `.claude/.DS_Store` is U-9 from the upstream review, not an agent artifact.
- Caveat: the untracked part of git status was truncated.

### Orchestrator gate decisions: complied
- `--graph-run` is required with aws and refused with local (Main.scala:126-160; CompositionSuite:35-66).
- The v2 prompt set comes from the `LocalOptions` default (ReadRoutesSuite:116 asserts `answer-synthesis-v2`).
- Tests build their own ServiceSettings (`withService`).
- U-7 is applied.

### Stated deviations 1-8
1. **`--graph-run`: ACCEPTED.** It is the orchestrator's gate decision, and it is validated as a RunId.
2. **`notes` field: ACCEPTED as a field, but the stated claim "so sync and async stay identical" is FALSE.** See S-1.
3. **Spec example adaptations: ACCEPTED.** The `graphResultLimit` 100 config copy is test-only, and the example otherwise stays the spec's shape.
4. **`/readyz` `jobs` check: ACCEPTED.** It is one cheap read through a port, under the same deadline, and no value is exposed.
5. **`Location` header; bind 0.0.0.0 for aws: ACCEPTED.** The header is additive. 0.0.0.0 is needed in a container, and local still binds 127.0.0.1.
6. **Rate limit counts 404/405; anonymous requests not limited: ACCEPTED.** It is documented, and limiting anonymous requests is on the proposed-later list.
7. **Neighbours of an unknown node return 200 with no rows: ACCEPTED.** 7h requires 404 only for the node route, and this avoids an extra graph call.
8. **logback.xml in services main resources: ACCEPTED.** It is inside the owner's territory. Root is at INFO, and rag and storage main log nothing through slf4j (grep found none).

## Findings

**S-1. MAJOR. Owner: service-engineer (the repair likely needs storage-engineer first, or your acceptance). Async completed results drop `notes`, so they differ from the sync result.**
- **Evidence:**
  - Worker.scala:133 builds the query with `ValidatedQuery.ofJob(running.request, running.mode)`.
  - `ofJob` sets `retrieval = Map.empty` and `analysis = Map.empty` (QueryRequestParser.scala:73-82), so `notes` (:40-42) is always `Nil`.
  - `WorkflowRunner.run` stores `Results.completed(..., query.notes)` (QueryService.scala:183-184).
  - storage's `JobRequest` (storage/jobs/JobModel.scala:49-54) holds only question, windowLabel, topic and includeEvidence, so the worker cannot recover the notes.
  - The equality test (QueryApiSuite:42-65) uses `queryBody()`, which has no `retrieval` or `analysis`, so it passes.
  - The report's deviation 2 says the opposite of what the code does.
- **Failure scenario:**
  - POST `{"question": ..., "constraints": {"yearFrom": 2015, "yearTo": 2025}, "retrieval": {"graphLimit": 10}, "mode": "async"}`, then the worker runs, then GET /v1/jobs/{id}/result. The result has `"notes": []`.
  - The same request with `mode: sync` returns `notes: ["retrieval.graphLimit was accepted but not applied; ..."]`.
  - This breaks 7c twice: "identical to the sync result for the same request", and "the response notes list every accepted but unapplied field".
  - It also affects the spec's own example request (8 unapplied fields) whenever it is sent async.
- **Repair (pick one, your decision):**
  - (a) storage-engineer adds the list of unapplied fields to `JobRequest` (or the validated retrieval and analysis values). Then service-engineer fills it in `toJobRequest` and rebuilds the notes in `ofJob`.
  - (b) You accept "async results omit notes" as a documented deviation and correct deviation 2's text.
  - Either way, add a test that fails without the change: the spec example request, async, with the result compared to the sync result.

**S-2. MAJOR. Owner: service-engineer. The 7e lost-claim branch (another request with the same key won the claim) has no deterministic test; the async variant has no test at all.**
- **Evidence:**
  - The sync branch is at QueryService.scala:275-276 and :284 (fail own job SUPERSEDED, unadmit, follow). The async branch is at :405-412 (supersede if still queued).
  - The only test is IdempotencySuite:177-198. Its two requests may be served one after the other (the second `idempotency.get` sees the first claim), and both outcomes pass (`statuses` in {200, 409}, `runs == 1`).
  - No test asserts a `SUPERSEDED` job. The only mention is the code list in ComponentsSuite:162.
- **Failure scenario:**
  - A regression where the `Existing(rec)` branch returns `Right(Right(running))` (runs a second workflow for the same key) would pass the suite whenever the race does not hit.
  - A regression that never supersedes the losing job, or that supersedes a worker-claimed async job, passes every time.
  - "Never runs a second workflow for the same key" is the main guarantee of 7e, and law 10 plus CLAUDE.md DoD item 2 require a test that fails without it.
- **Repair:** Add deterministic tests with a wrapping `IdempotencyStore` whose `get` returns None and whose `claim` returns `Existing(rec)` for a record already in the store:
  - Sync: assert the losing request's job is `failed` with `SUPERSEDED`, the response follows the existing record, and the workflow ran 0 extra times (audit count).
  - Async: assert the losing queued job is `SUPERSEDED`, its queue message is later acked by the worker without running, and the 202 follows the winner.

**S-3. MINOR. Owner: service-engineer. If the sync final write fails, the client gets 200 but the key later answers JOB_FAILED.**
- **Evidence:** In QueryService.scala:357-365, a `succeed` that returns ConditionFailed or Left (store failure, or a stale holder after a stage write that was applied but returned Left, per U-7) is logged as `result-write-lost` and returns `Right(result)`. The job stays `running`.
- **Scenario:** A DynamoDB throttle on the final put. The client gets 200, the response is lost in transit, and the client retries with the same key. It gets 409 IN_PROGRESS until the lease ends, then JOB_FAILED LEASE_EXPIRED. That is a different answer for one key and fingerprint, against 7e's "never returns a different result for it". It happens only on a store failure.
- **Suggestion:** On Left or ConditionFailed, re-read the job. If it is still `running` with this request's lease, retry `succeed` once on the fresh version; otherwise return 502.

**S-4. MINOR. Owner: service-engineer (human decision). Logged `detail` carries downstream store and SDK text, filtered only by the 7i redactor.**
- **Evidence:**
  - `detail` is logged at QueryService.scala:179 (`workflow-failed`), :168, :363 and :493, and at Worker.scala:86 and :163.
  - The text comes from storage's `StoreException` messages, which embed AWS SDK `getMessage` (storage aws/AwsSupport.scala:74-83; RuntimeStores.scala:63; ServiceStores.scala:112).
  - The redactor removes only key values, `arn:`, 12-digit numbers and `s3://` (Support.scala:152-165).
- **Scenario:** An SDK client error such as "Unable to execute HTTP request: Connect to <neptune-cluster-host>:8182 failed" puts the Neptune endpoint host into the CloudWatch log. Decision 8b lists endpoints and tables as forbidden in logs.
- **Why minor:** The code follows 7i's redactor definition literally, account IDs are removed, and logs are internal.
- **Suggestion:** Log only the error class or a fixed cause, or extend the redactor to hostnames (`*.amazonaws.com`) and configured table and queue names.

**S-5. MINOR. Owner: service-engineer. The shutdown drain order and its total wait do not match 7i's wording.**
- **Evidence:**
  - ServiceServer.scala:83 calls `http.stop(0)`, which closes in-flight exchanges at once, so a sync request waiting for its result gets a reset instead of a 504 or 200.
  - :86 shuts the four pools down one after another, each waiting up to `syncDeadlineMillis`. With shutdownNow and the 2 s join, the worst case is about 4×syncDeadline + 6 s.
  - 7i says "waiting at most `syncDeadlineMillis`".
- **Scenario:** With `syncDeadlineMillis` 120000, close() can take about 8 minutes, longer than an ECS stop timeout (Phase 12).
- **Suggestion:** Use one shared deadline across all pools, and stop the HTTP server with a short grace period.

**S-6. MINOR. Owner: service-engineer. The thin-route source test skips silently when the working directory is not the repository root.**
- **Evidence:** ComponentsSuite.scala:173: `assume(Files.isDirectory(dir), "run from the repository root")`.
- **Scenario:** If build-engineer later forks services tests with the module as the working directory, the only automated check of the thin-route rule is skipped and the suite stays green. Today it runs: services is not forked and the skipped count is unchanged at 3.
- **Suggestion:** Resolve the path from `ThisBuild / baseDirectory` passed as a system property, or fail instead of `assume`.

**S-7. ADVISORY. Owner: service-engineer. API keys are checked only after the runtime is built.**
- Main.start builds `Composition.local` or `.aws` first (Main.scala:186-189), and `ApiKeys.load` runs inside `ServiceServer.start` (ServiceServer.scala:110).
- Startup still fails closed, but with `--stores local` it does so only after about 115 s and 4 GB of loading. Checking the keys first would fail fast.

**S-8. ADVISORY. Owner: service-engineer. Caller-runs can stall connection accepts.**
- With 256 queued requests and every HTTP thread busy, `CallerRunsPolicy` (ServiceServer.scala:128, 137) runs a handler on the JDK dispatcher thread.
- If that handler is a sync query, it blocks accepts, including `/healthz`, for up to `syncDeadlineMillis`.
- It is bounded and documented (ServiceLimits.scala:79-83), but an immediate 503 would keep liveness answering.

**S-9. ADVISORY. Owner: service-engineer. `CircuitBreaker.cancel()` clears `trialInFlight` without checking who holds it (Support.scala:84).**
- A request admitted while the breaker was closed can lose its idempotency claim after the breaker turned half-open. Its `unadmit` then frees another request's trial slot, so two trial calls can run.
- Impact is low.

**S-10. ADVISORY. Owner: service-engineer. POST /v1/query ignores query-string parameters.**
- `QueryService.submit` never reads `ctx.query`, while every GET route rejects unknown parameters with 400 (Routes.scala:22-27; ReadServices.scala).
- This is an inconsistency, not a defect.

**S-11. ADVISORY. Owner: service-engineer. Explain reads at most `ExplainAuditLimit` = 1000 audit records (ReadServices.scala:91-92).**
- After 1000 reruns of one query ID, `attemptCount` stops counting at 1000, and "latest" is the last of the first 1000 returned.

Relevant files:
- /Users/brian/Developer/repos/scholarchain/modules/services/src/main/scala/org/scholarchain/services/Worker.scala
- /Users/brian/Developer/repos/scholarchain/modules/services/src/main/scala/org/scholarchain/services/QueryRequestParser.scala
- /Users/brian/Developer/repos/scholarchain/modules/services/src/main/scala/org/scholarchain/services/QueryService.scala
- /Users/brian/Developer/repos/scholarchain/modules/services/src/main/scala/org/scholarchain/services/ServiceServer.scala
- /Users/brian/Developer/repos/scholarchain/modules/services/src/main/scala/org/scholarchain/services/Support.scala
- /Users/brian/Developer/repos/scholarchain/modules/services/src/main/scala/org/scholarchain/services/Main.scala
- /Users/brian/Developer/repos/scholarchain/modules/services/src/test/scala/org/scholarchain/services/QueryApiSuite.scala
- /Users/brian/Developer/repos/scholarchain/modules/services/src/test/scala/org/scholarchain/services/IdempotencySuite.scala
- /Users/brian/Developer/repos/scholarchain/modules/services/src/test/scala/org/scholarchain/services/ComponentsSuite.scala
- /Users/brian/Developer/repos/scholarchain/modules/storage/src/main/scala/org/scholarchain/storage/jobs/JobModel.scala
