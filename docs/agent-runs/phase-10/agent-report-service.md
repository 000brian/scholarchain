Summary: I built the Phase 10 REST services (decision 7, a to m) in package `org.scholarchain.services`, under modules/services/** only. `sbt check` ran once and passed: exit=0, 914 s, module tests 1861/1861 (3 skipped), contract tests 336/336. markTestRun refreshed .claude/.last-test-run at 2026-11-16T11:08:51Z (module_tests=1861, contract_tests=336). The 88 new services tests are module tests: the count before this step was 1773, and 1773 + 88 = 1861. No background job is still running.

STATUS: COMPLETE
OWNER: service-engineer
WORK ORDER: docs/agent-runs/phase-10/prompt.md (I checked the sha256 and it matches c032785a6cbef13124c196fb3c42be52b43cfd6e10e712a1861553d1cde5f49a), step 6a, decision 7 (a to m). I applied orchestrator gate notes 1 to 6 and the U-7 and U-1 relays.

FILES CREATED (all under /Users/brian/Developer/repos/scholarchain/modules/services/):
- Main code, in src/main/scala/org/scholarchain/services/: ServiceLimits.scala, Errors.scala, Json.scala, Models.scala, Auth.scala, Support.scala, QueryRequestParser.scala, HttpAdapter.scala, Routes.scala, QueryService.scala, Worker.scala, ReadServices.scala, HealthService.scala, ServiceServer.scala, Main.scala
- src/main/resources/logback.xml: one message per line to stdout; root logger at INFO; software.amazon and org.apache at WARN.
- Test code, in src/test/scala/org/scholarchain/services/: ServiceTestKit.scala, QueryApiSuite.scala, IdempotencySuite.scala, CapacityAndFailureSuite.scala, WorkerSuite.scala, ReadRoutesSuite.scala, HttpBehaviorSuite.scala, CompositionSuite.scala, ComponentsSuite.scala, GoldenExamplesSuite.scala, GoldenScenario.scala
- src/test/resources/config/services-local-v1.json (7l): the rag local v2 config plus a `service` section that uses the variable names SCHOLARCHAIN_EXAMPLE_API_KEY and SCHOLARCHAIN_EXAMPLE_READONLY_API_KEY.
- src/test/resources/api/v1/: sync-query-request, completed-result, async-acknowledgment, job-status, evidence-item, node, neighbors-page, explain, readiness-not-ready, error-400, error-401, error-404, error-409, error-422, error-504 (all .json), plus spec-example-request.json.

FILES MODIFIED: none. I changed nothing outside modules/services/**. build.sbt shows as modified in git, but that change is build-engineer's step 1, not mine.

COMMANDS RUN:
- shasum on the work order
- read-only grep, sed -n, cat, ls, find, tail and git status
- `sbt -batch services/compile`
- `sbt -batch "services/testOnly <suites>"`, several targeted runs
- `sbt -batch services/test`, run twice in one invocation as a stability check (88/88 both times)
- `sbt -batch "services/Test/runMain org.scholarchain.services.GoldenExampleWriter <scratchpad>/golden"`. It wrote the example bodies to the scratchpad. I froze them with the Write tool and checked with shasum that all 15 frozen files match the generated ones.
- `sbt -batch check`, once, in the background, logged to /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/aaf86ae1-6986-4770-9813-b764b8dca793/scratchpad/svc-check.log
- Bounded self-ending wait loops on that log. Both background tasks have finished.

TEST RESULTS:
- `sbt check` output: "[info] Module tests: suites=109 executed=1861 passed=1861 failed=0 errors=0 skipped=3", "[info] Contract tests (no AWS): suites=22 executed=336 passed=336 failed=0 errors=0 skipped=0", "[success] Total time: 914 s", exit=0.
- Services tests: 88 run, 88 passed. One more test is tagged RequiresAws: it is compiled and never run.
  - QueryApiSuite 9
  - IdempotencySuite 8
  - CapacityAndFailureSuite 7
  - WorkerSuite 11
  - ReadRoutesSuite 9
  - HttpBehaviorSuite 14
  - CompositionSuite 5 (+1 RequiresAws)
  - ComponentsSuite 8
  - GoldenExamplesSuite 17

VALIDATION RESULTS:
- All 15 frozen examples decode into their models and re-encode to the same bytes.
- Each response example equals the running server's output byte for byte. The server runs with a fixed ID generator, a ManualClock, a workflow clock of 0 and fixed traceparent headers.
- The thin-route source check passes: Routes.scala and HttpAdapter.scala import nothing from storage or rag.
- `--stores aws` builds from stub SDK clients with 0 SDK, LLM and embedder HTTP calls.

ASSUMPTIONS:
- The sync path without an Idempotency-Key creates no job; its trace ID appears only in the log lines.
- The stage write uses rag's stage names ("planner" through "audit"). The last stage written stays on the job.
- A succeeded async replay returns the 202 acknowledgment with `status` SUCCEEDED and the header `Idempotent-Replayed: true`.

RISKS: see the deviations and known limitations below.

MANUAL ACTION REQUIRED: none for this step. The two commands in 7m below are for the human, later. I have not run them.

BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: step 7, code-reviewer services review (decision 8b), then step 8 repairs.

## 7m: commands for the human (not run)

1. Start the `all` role on the real data. Export both key variables with two different values in your own shell first. It binds 127.0.0.1:8080, and loading takes about 115 s and about 4 GB of heap, as in Phase 9.
```
sbt -batch -J-Xmx12g "services/runMain org.scholarchain.services.Main --config modules/services/src/test/resources/config/services-local-v1.json --role all --stores local --export modules/spark/target/phase-07-export/run=51b50d578d5b609842c1c21b8266a00b --neptune-csv modules/storage/target/phase-07-real/attempt-01/neptune"
```
2. One example request:
```
curl -s -X POST http://127.0.0.1:8080/v1/query -H 'Content-Type: application/json' -H "X-Api-Key: $SCHOLARCHAIN_EXAMPLE_API_KEY" -d '{"question":"Which researchers have the most distinct coauthors in 2015-2025?","constraints":{"yearFrom":2015,"yearTo":2025},"output":{"includeEvidence":false}}'
```

## Route table (role, method, path, auth level)

| Role | Method | Path | Auth |
|---|---|---|---|
| every role | GET | /healthz | none |
| every role | GET | /readyz | none |
| query | POST | /v1/query | full key |
| query | GET | /v1/jobs/{jobId} | full or read-only key |
| query | GET | /v1/jobs/{jobId}/result | full or read-only key |
| evidence | GET | /v1/evidence/{evidenceId}[?window=S-E] | full or read-only key |
| evidence | GET | /v1/explain/{queryId} | full or read-only key |
| graph | GET | /v1/graph/nodes/{nodeId} | full or read-only key |
| graph | GET | /v1/graph/nodes/{nodeId}/neighbors?direction&depth&limit&edgeTypes&window&cursor | full or read-only key |

- The `worker` role serves only the health routes and runs the worker loop.
- The `all` role serves every route and runs the worker.
- A route that belongs to another role returns 404 ROUTE_NOT_FOUND.

## Status-code mapping (every code is produced by some path and covered by a test)

- 400: INVALID_JSON, INVALID_REQUEST (`details.fieldErrors` holds one {field, problem} per problem), WINDOW_REQUIRED (`details.windows`), INVALID_CURSOR
- 401: UNAUTHENTICATED
- 403: FORBIDDEN (the read-only key on POST)
- 404: ROUTE_NOT_FOUND, QUERY_NOT_FOUND, JOB_NOT_FOUND, NODE_NOT_FOUND, EVIDENCE_NOT_FOUND
- 405: METHOD_NOT_ALLOWED, with an Allow header
- 409: IDEMPOTENCY_CONFLICT, IDEMPOTENCY_IN_PROGRESS (Retry-After), JOB_NOT_READY (Retry-After), JOB_FAILED (`details.jobError`)
- 413: PAYLOAD_TOO_LARGE
- 415: UNSUPPORTED_MEDIA_TYPE
- 422: UNSUPPORTED_CONSTRAINT (lists the windows when the years match none), UNSUPPORTED_EDGE_TYPE, UNSUPPORTED_DEPTH, UNSUPPORTED_NODE_KIND
- 429: RATE_LIMITED (Retry-After)
- 500: INTERNAL, RESULT_TOO_LARGE
- 502: DOWNSTREAM_FAILURE
- 503: OVERLOADED, CIRCUIT_OPEN (both with Retry-After)
- 504: WORKFLOW_TIMEOUT

Error bodies:
- Every non-2xx body except /readyz is `{error, message, traceId, retryable, details}`.
- `traceId` equals the X-Trace-Id header.
- `message` is a fixed text per code. Tests check that no downstream text appears.
- Job error codes are SUPERSEDED, LEASE_EXPIRED, ATTEMPTS_EXHAUSTED, RESULT_TOO_LARGE and WORKFLOW_FAILED. Each is stored with a fixed message.

## Timeout policy

- Sync queries run on the `query` pool, which has `maxConcurrentQueries` threads.
- Admission takes a permit with Semaphore.tryAcquire. There is no waiting queue: a request that gets no permit is answered 503 OVERLOADED at once.
- The request waits at most `syncDeadlineMillis`, then gets 504 WORKFLOW_TIMEOUT.
- The workflow is never interrupted. It keeps its permit until it finishes; a test shows the next request gets 503 and the permit comes back afterwards.
- With an Idempotency-Key, the finished workflow writes its result to the job, so a retry replays it. Without a key, the late result is discarded.
- Graph calls are bounded by storage's `runtime.graphTimeoutMillis`. Services add no retries.
- Readiness: one overall 2 s deadline, then cancel. Main sets `sun.net.httpserver.maxReqTime=30` (seconds).

## Dependency graph (which service calls which port)

- QueryService → `RuntimeService.workflow` (validate, queryRunId, run with onStage), `ServiceStores.jobs`, `.idempotency`, `.enqueueJob`, and `EvidenceStore.getBundle` for replays.
- JobService → `JobStore.get` and `EvidenceStore.getBundle`.
- Worker → `ServiceStores.jobQueue` (receive, ack, release), `JobStore` (get, claim, reclaim, setStage, succeed, release, fail) and `workflow.run`.
- EvidenceService → `EvidenceStore.get` and `.windows`.
- ExplainService → `QueryAuditWriter.read`.
- GraphService → `GraphReadPort.run` with `ResearcherById`, `WorkById`, `CoauthorNeighbors` and `WorkAuthors`, wrapped in `RuntimeStores.guard`.
- HealthService → `MetricCacheReader.get`, `GraphReadPort.run(ResearcherById)`, and `JobStore.get` for roles query, worker and all.
- Composition and Main → `RuntimeService.local` / `.aws` and `ServiceStores.local` / `.aws`.

Breakers:
- `workflow` covers the sync path; `graph` covers graph calls; `reads` covers evidence and audit reads.
- Job and idempotency store failures map to 502 without a breaker.

## Authentication method

- The header is `X-Api-Key`.
- At startup, the values of the variables named by `service.apiKeyEnvVar` (full) and `service.readOnlyApiKeyEnvVar` (read-only) are read and only their SHA-256 digests are kept.
- Each request is compared to both digests with MessageDigest.isEqual. Both comparisons always run (constant time). A missing or wrong key gets 401.
- The read-only key may call GET routes only; on POST it gets 403.
- The process refuses to start when either variable is unset or blank, or both hold the same value. The message names the variable, never a value.
- /healthz and /readyz are open.
- Rate limit: one token bucket per process, `requestsPerMinute`. It is taken right after authentication, for every authenticated request.

## Idempotency rules as built (7e)

- The key must be 1..128 characters of [A-Za-z0-9._:-]. The scope is the caller class.
- The fingerprint is the sha256 of the canonical, key-sorted JSON of the validated request after defaults: question, windowLabel, topic, mode, output, retrieval and analysis.
- An existing record is checked before admission, so replays need no permit.
- New key, sync: take a permit and a breaker slot, create the job (sync), claim it to running with a lease of `jobLeaseMillis`, claim the key, then run.
- New key, async: create the job (queued), enqueue it, then claim the key.
- A lost claim marks this request's own job failed with SUPERSEDED: a sync job that this request claimed, or an async job that is still queued. The request then follows the existing record.
- Following a record:
  - different fingerprint: 409 IDEMPOTENCY_CONFLICT;
  - succeeded: sync gets 200 plus `Idempotent-Replayed: true`, with evidence read back from the store; async gets the 202 acknowledgment again with status SUCCEEDED;
  - queued, or running with a live lease: 409 IDEMPOTENCY_IN_PROGRESS;
  - failed: 409 JOB_FAILED with `jobError`;
  - sync running with an expired lease: written as failed LEASE_EXPIRED, then JOB_FAILED;
  - async running with an expired lease: IN_PROGRESS (the queue recovers it).
- The job and the record share one `expiresAt`. After `jobTtlSeconds` the same key starts a new job (tested).
- Nothing claims exactly-once execution.

## Worker rules as built (7f)

- The worker receives one message and reads its job.
  - A foreign message, or an unknown or expired job: ack.
  - Succeeded or failed: ack without running.
  - Running with a live lease: leave the message alone.
  - `attempts >= jobMaxAttempts`: write failed ATTEMPTS_EXHAUSTED, then ack.
  - Queued: claim. Running with an expired lease: reclaim. If the claim does not win (ConditionFailed or Refused), leave the message.
- A request that rag's `validate` rejects is written failed WORKFLOW_FAILED, then acked.
- The run uses onStage. Per U-7, each successful `setStage` result goes into one AtomicReference, which the final `succeed` uses. A failed stage write is logged and ignored.
- On success: write `succeed`, then ack.
  - ResultTooLarge (storage's, or services' own check against the same 300,000-byte constant): failed RESULT_TOO_LARGE, then ack.
  - ConditionFailed: discard the result and leave the message.
- On a workflow Left: below the attempt limit, write `release` (running→queued), then release the message. At the limit, write failed ATTEMPTS_EXHAUSTED, then ack.
- The worker logs with the job's trace ID. One trace ID follows the query role's request line, the job, the worker's lines and every stage line (tested).

## Named constants (ServiceLimits.scala; the scaladoc gives each reason)

- ReadinessDeadlineMillis=2000 and ReadinessCacheMillis=5000
- DefaultNeighborLimit=20 (capped by runtime.graphResultLimit)
- MaxNeighborDepth=3
- MaxResultBytes=300000 (storage's QS4 limit)
- RequestIdPattern: 1..64 characters of [A-Za-z0-9._-]
- IdempotencyKeyPattern: 1..128 characters
- MaxQuestionChars=2000 and MaxTopicChars=200
- MaxJsonDepth=16
- MaxPathChars=512 and MaxQueryChars=2048
- ExplainAuditLimit=1000 and EvidenceWindowLimit=100
- RetryAfterSeconds=2
- HttpExtraThreads=8 and HttpQueueCapacity=256 (then caller-runs)
- ReadinessThreads=3 and ReadinessQueueCapacity=9
- WorkerIdleMillis=200
- LocalVisibilityMarginMillis=30000 (local queue visibility = lease + margin)
- Backlog=128
- ProbeResearcherId and ProbeJobId
- ThreadPrefix "scholarchain-"

## Deviations

1. Main has a new argument, `--graph-run RUNID` (gate note 2). It is required with `--stores aws` and refused with `--stores local`. `--stores aws` also requires `service.jobQueueUrl`.
2. The completed result has a fourth field, `notes`. It lists every accepted but unapplied field (`retrieval.*`, `analysis.*`) as decision 7c asks, and it is in the stored result, so sync and async stay identical.
3. The spec's example request needed three adaptations. These are in api/v1/spec-example-request.json and its test:
   - yearTo 2026 became 2025, a configured window (as the decision allows);
   - `rankingMetric` "DISTINCT_COAUTHOR_COUNT" is not a ranked metric wire name, so 7c makes it 422. The file uses `distinct_coauthors`;
   - `graphLimit` 100 exceeds this config's `runtime.graphResultLimit` of 50, which I kept from rag. That test uses a config copy with 100.
4. /readyz adds a `jobs` check (one JobStore.get) for roles query, worker and all, next to the metric and ResearcherById checks.
5. The 202 response adds a `Location` header. Bind host: 127.0.0.1 for `--stores local`, 0.0.0.0 for aws.
6. The rate limit counts every authenticated request, including 404 and 405 answers. Requests without a key are refused with 401 before the bucket and are not limited.
7. A neighbours request for an unknown node returns 200 with no rows; there is no extra existence call.
8. I added logback.xml to services' main resources. It is also on the contractTests classpath.

## Proposed later

- /metrics: CloudWatch metrics come with Phase 12. The deployment does not permit it yet.
- Jobs scoped to the caller. Today any authenticated caller who knows a jobId can read it.
- A rate limit for unauthenticated requests.
- Lease heartbeats for workflows longer than `jobLeaseMillis`.
- Feeding the workflow breaker from worker outcomes.
- Depth 2 and 3 neighbourhoods; the HAS_TOPIC, AFFILIATED_WITH and PUBLISHED_IN edges.
- An OpenAPI document.
- QueryAudit v2 with per-call retry counts.
- A Main end-to-end test on a fixed port. Main.start binds `service.port`; tests cover parseArgs, loadConfig and Composition instead.
- A way to stop the JDK draining oversized bodies.

## Upstream notes (no upstream edits made)

- The test double FlakyStores' graph port throws StoreException instead of returning Left. Services therefore wrap graph calls in `RuntimeStores.guard`.
- U-1: services treat a Left from `workflow.run` as 502 (retryable for the worker) and add no bundle or audit check. My lifecycle tests use working stores, so they do not depend on today's behaviour, where `run` returns Right when putBundle or the audit append fails.
- Golden explain.json and completed-result.json pin the v2 prompt sha256 values and the fake model's output. A rag repair that changes the prompts or the fake output will change these examples. Regenerate them with GoldenExampleWriter and review the diff.

INTERFACE HANDOFF

- Files changed: the files listed under FILES CREATED, all in modules/services/**.
- Public types and signatures (package org.scholarchain.services):
  - `Main.main(args)`
  - `Main.parseArgs(List[String]): Either[String, Main.Args]`
  - `Main.loadConfig(Path, Map[String,String]): Either[String, ScholarChainConfig]`
  - `Main.start(List[String], Map[String,String]): Either[String, ServiceServer]`
  - `Composition.local(config, exportDir, neptuneCsvDir, clock, options = LocalOptions()): Either[String, (RuntimeService, ServiceStores)]`
  - `Composition.overRuntime(runtime, jobLeaseMillis, clock): Either[String, ServiceStores]`
  - `Composition.aws(config, graphRunId: RunId, options = AwsOptions()): Either[String, (RuntimeService, ServiceStores)]`
  - `ServiceServer.start(runtime, stores, ServerOptions): Either[String, ServiceServer]`. The server has port, baseUri, app: Application, worker: Worker, breakers, threads, threadPrefix, close().
  - `ServerOptions(role, bindHost="127.0.0.1", port: Option[Int]=None, clock, ids: IdGenerator, logSink: LogSink, env: String=>Option[String], startWorker=true, closeRuntime=true, name)`
  - `Role` with values Query, Worker, Evidence, Graph, All, and `Role.parse`
  - `Worker.processOne(): WorkerOutcome`, one of Idle, AckedWithoutRun, LeftAlone(reason), Succeeded, Failed(code), Released
  - `QueryService.submit(RequestCtx)` and `freePermits`
  - `JobService.status/result`, `EvidenceService.get`, `ExplainService.explain`, `GraphService.node/neighbors`, `HealthService.healthz/readyz/probeCount`
  - Models: CompletedResult, AnswerView, ResultLinks, QueryAcknowledgment, JobStatusView, JobErrorView, EvidenceView, NodeView, NeighborsPage, ExplainView, ReadinessBody, HealthBody, ErrorBody. Each has a uPickle ReadWriter.
  - `Json.encode/render/canonical/depthWithin`
  - `ErrorCode` (all, status, retryable, message), `ApiError`, `JobErrorCodes`
  - `ApiKeys.load(ServiceSettings, env)`, `CallerClass`, `CircuitBreaker`, `TokenBucket`, `Redactor`, `LogSink`, `MemoryLogSink`, `ServiceLog`, `IdGenerator`, `RequestIds`, `NeighborCursor`, `NodeRef`, `QueryRequestParser`, `ValidatedQuery` (fingerprint, toQueryRequest, toJobRequest, notes), `ServiceLimits`
- Test fixtures (services test tree, package org.scholarchain.services):
  - `ServiceTestKit.harness(role, model, cfg, startWorker, clock, keys, closeRuntime, wrapStores)` returns a Harness with world, flaky, slow, runtime, jobKv toggle, queue, stores, server, clock, log and model.
  - `ServiceTestKit.runtimeOver`, `FixedIds`, `SlowGraphStores`, `call/get/post`, `queryBody`, `traceparent`, `waitUntil`, and the placeholder keys FullKey and ReadOnlyKey.
  - `BigResultStores`, `GoldenScenario.run()`, `GoldenExampleWriter`.
- Thread-safety:
  - Safe from several threads: ServiceServer (close is idempotent), HttpAdapter, QueryService, JobService, the read services, Worker (several workers may share stores), CircuitBreaker and TokenBucket (synchronized), HealthService (single-flight, synchronized), ServiceLog/MemoryLogSink and Redactor.
  - Immutable: ApiKeys and all models.
  - Pools: http, query, worker and ready are fixed-size with bounded queues. Their threads are named `scholarchain-<server>-<pool>-n`.
  - Not covered: the JDK HttpServer's own dispatcher thread, which the JDK names.
- Known limitations:
  - Deviations 1 to 8 above.
  - Jobs are not scoped to the caller.
  - A workflow that outlasts `jobLeaseMillis` can be reclaimed and run twice. This is at-least-once, as documented.
  - The JDK drains an oversized body on close; Main's maxReqTime of 30 s bounds this.
  - In the local composition, the in-memory stores and queue live in one process, so only role `all` is allowed.
