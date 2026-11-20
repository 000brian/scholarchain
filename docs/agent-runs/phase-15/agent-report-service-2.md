interface: yes (new ServerOptions fields, Composition.embeddingWork, EmbeddingWorker; explain field from attempt 1)

```text
STATUS: COMPLETE
OWNER: service-engineer
WORK ORDER: docs/agent-runs/phase-15/prompt.md (sha256 3d806922c6dd74ada61bceca45d3a302740a4083f260f0ae3ca6b6b6b9dfa3e0, verified with shasum), session 2 step 4 RE-RUN, decision 8; wave closer
FILES CREATED:
  /Users/brian/Developer/repos/scholarchain/modules/services/src/main/scala/org/scholarchain/services/EmbeddingWorker.scala
  /Users/brian/Developer/repos/scholarchain/modules/services/src/test/scala/org/scholarchain/services/EmbeddingWorkerSuite.scala
FILES MODIFIED (this run):
  /Users/brian/Developer/repos/scholarchain/modules/services/src/main/scala/org/scholarchain/services/ServiceServer.scala
  /Users/brian/Developer/repos/scholarchain/modules/services/src/main/scala/org/scholarchain/services/Main.scala
  /Users/brian/Developer/repos/scholarchain/modules/services/src/main/scala/org/scholarchain/services/ServiceLimits.scala
  /Users/brian/Developer/repos/scholarchain/modules/services/src/main/scala/org/scholarchain/services/Support.scala
  /Users/brian/Developer/repos/scholarchain/modules/services/src/test/scala/org/scholarchain/services/ServiceTestKit.scala
  /Users/brian/Developer/repos/scholarchain/modules/services/src/test/scala/org/scholarchain/services/CompositionSuite.scala
  (attempt 1, kept: Models.scala, src/test/resources/api/v1/explain.json, ReadRoutesSuite.scala)
COMMANDS RUN: shasum -a 256 (work order); ls; grep -n / grep -rn (literal words); wc -l; git status --short; ls -lT; sbt -batch "services/testOnly org.scholarchain.services.EmbeddingWorkerSuite org.scholarchain.services.CompositionSuite" (twice: first a test-compile error, fixed); sbt check (background, output to the scratchpad log); sbt -batch packageCheck; head -n 5 .claude/.last-test-run. Bash calls were prefixed `cd REPO &&` (read-only effect; unplanned deviation from the no-`cd` rule, same as earlier steps). Bounded wait loops (`for i in $(seq 1 38) ... grep -q ... sleep 15`) were used to wait for the background check.
TEST RESULTS: targeted EmbeddingWorkerSuite 9/9, CompositionSuite 8/8 (live test excluded): "Passed: Total 17, Failed 0, Errors 0, Passed 17" [success] 13:06:43. Full sbt check: "Module tests: suites=139 executed=2192 passed=2192 failed=0 errors=0 skipped=3", contract_tests=399, [success] Total time: 975 s, completed 13:23:08.
VALIDATION RESULTS: sbt check [success] (format check, compile, all module tests, contract tests, markTestRun refreshed the marker); sbt -batch packageCheck "packageCheck: ok" [success] 13:23:26.
ASSUMPTIONS: see section
RISKS: see section
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer on the wave; step 5b (infra-engineer): worker-role SQS permissions on the embedding queue (ReceiveMessage, DeleteMessage, ChangeMessageVisibility; storage handoff) plus the S3/S3 Vectors/DynamoDB permissions of the re-embed path
```

## INTERFACE CHECK (done first)

| Name | Where (file:line) | Result |
|---|---|---|
| `ServiceStores.embeddingQueue(env: AwsStorageEnvironment, queueUrl: String, waitSeconds: Int = SqsWaitSeconds): Either[String, ReceiveQueue]` | modules/storage/src/main/scala/org/scholarchain/storage/jobs/ServiceStores.scala:118 | match |
| `trait ReceiveQueue { receive(max): List[ReceivedWork]; ack; release }` | modules/storage/src/main/scala/org/scholarchain/storage/ports/WorkQueue.scala:19 (WorkQueue extends it :33) | match |
| `ReceivedWork(item: WorkItem, receipt: String, receiveCount: Int)`, `WorkItem(id, payload)` | WorkQueue.scala:11, :7 | match |
| `ReembedHandler.fromConfig(config, reembedderOf, wiring, log, allowFake): Either[String, Built]` | modules/rag/src/main/scala/org/scholarchain/rag/reembed/ReembedHandler.scala:338; `Built` :330 | match |
| `ReembedHandler.handle(deliveries: Seq[Delivery]): Vector[HandledDelivery]` (input order, never throws per delivery) | ReembedHandler.scala:216 | match |
| `Delivery(deliveryId, body)` | ReembedHandler.scala:124 | match |
| `DeliveryAction` Acknowledge / ReleaseNow / LeaveForRetry | ReembedHandler.scala:106, :110, :115, :118 | match |
| `AwsStorageEnvironment.reembedder(embedder, sleeper)` | modules/storage/src/main/scala/org/scholarchain/storage/aws/AwsStorageEnvironment.scala:206 | match |

## Status per item (decision 8)

| Item | Status | Where |
|---|---|---|
| 8.1 second loop on the embedding queue when `service.embeddingQueueUrl` is set (roles worker, all; AWS stores) | done | Main.scala:88 `Composition.embeddingWork`, :235 (called for `--stores aws` and roles worker/all only); ServiceServer.scala:179 (handler built once at start with the server log), :253 (loop started) |
| 8.1 bounded concurrency, a setting with a default | done | `ServerOptions.embeddingConcurrency` ServiceServer.scala:48, default `ServiceLimits.EmbeddingWorkerConcurrency` = 2 (ServiceLimits.scala:103, justification in its doc), refused outside 1..8 (ServiceLimits.scala:108; ServiceServer.scala:143); own fixed pool `embedding` |
| 8.1 long polling like the job loop | done | storage's receive waits `ServiceStores.SqsWaitSeconds` (1 s), as the job queue; up to 10 messages per receive (ServiceLimits.scala:111); idle wait `WorkerIdleMillis` as the job loop (EmbeddingWorker.scala:135 `EmbeddingLoop`) |
| 8.1 calls rag's handler; ack only processed/replayed; release permanent failures at once; leave transient | done | EmbeddingWorker.scala:73 `processOne`; actions :98 Acknowledge -> `ack`, :101 ReleaseNow -> `release`, :104 LeaveForRetry -> nothing |
| 8.1 shutdown drains in-flight within the ECS stop timeout | done | ServiceServer.scala:99 `close()`: both loops stopped, every pool shut down at once and awaited from one start; embedding pool waits at most `EmbeddingDrainMillis` 60 s (ServiceLimits.scala:121), others `syncDeadlineMillis` (25 s in config/course.json); worst case about 60 s + 1 s + 2 s thread joins, under `stop_timeout_seconds` 120 |
| 8.1 one log line per message written by rag's handler, none by services | done | rag's line goes through `ServiceLog.external` (Support.scala:276), adding `time` and `role` only; services writes only one `embedding-queue-failure` line per call whose queue receive/ack/release failed (no message identity) |
| 8.1 job loop unchanged | done | `Worker`/`WorkerLoop` untouched (Worker.scala not modified); WorkerSuite passes in sbt check |
| 8.2 `abstentionReasons` on GET /v1/explain/{queryId} (attempt 1) | done | Models.scala:259, :272; explain.json v3; ReadRoutesSuite.scala:126-131, :181-208 |
| 8.3 tests | done | EmbeddingWorkerSuite.scala: :36 poison never acked, released at once on each of 5 receives, no Voyage call, one line per delivery, body never logged; :66 replay acked exactly once; :85 transient left; :103 mixed batch actions per message; :131 both loops running (async job succeeds and a task is acked); :158 no URL / query role: no second loop; :187 shutdown drains an in-flight call then acks once and receives no more; :222 queue failures counted and logged without receipt or URL; :241 start refused (handler Left, concurrency 0 or 9). CompositionSuite.scala:264 AWS composition with the URL from stub clients, zero HTTP calls, URL never in a Left or log; :320 URL with local runtime refused. Explain with/without reasons: kept from attempt 1 (ReadRoutesSuite). Fail without the change: the suites do not compile without EmbeddingWorker/EmbeddingWork/ServerOptions.embedding/Composition.embeddingWork |
| 8.4 targeted tests, full sbt check, packageCheck | done | VALIDATION EVIDENCE |
| 8.5 INTERFACE HANDOFF | done | below |

## Route table

No route changed in this run. `GET /v1/explain/{queryId}` body gained `abstentionReasons` (attempt 1); status codes unchanged.

## Error model coverage

Unchanged; no new HTTP error code. Start-up refusals (process exit 2 through Main): "service.embeddingQueueUrl needs the AWS stores (--stores aws)" (only if called with a local runtime; Main does not call it for local), storage's "the embedding queue URL must be an https:// URL with a host" (never names the URL), rag's fromConfig Lefts (for example "embedding section is not configured", "vectors section is not configured", missing Voyage key), "the re-embed handler did not build: <ExceptionClass>", "embeddingConcurrency must be in 1..8".

## Limits

- embedding loop threads: default 2, range 1..8 (`ServerOptions.embeddingConcurrency`); pool queue = thread count.
- messages per receive: 10 (SQS maximum); long poll 1 s (storage).
- one handler call per receive; rag batches up to `embedding.batchSize` 64 (capped at 100) per Voyage request, so one receive is one Voyage request.
- drain on close: at most 60 s for the embedding pool, measured from the same start as the other pools.

## INTERFACE HANDOFF

- Files: listed above. New public types (package `org.scholarchain.services`): `EmbeddingWork(queue: ReceiveQueue, handlerOf: (String => Unit) => Either[String, ReembedHandler])`; `EmbeddingWorker(queue, handler, log)` with `processOne(): EmbeddingOutcome` and atomic counters `acknowledged`, `released`, `leftForRetry`, `queueFailures`; `EmbeddingOutcome(received, acknowledged, released, left, queueFailures)`; `EmbeddingLoop`; `Composition.embeddingWork(config, runtime, wiring = EmbeddingProviders.Wiring()): Either[String, Option[EmbeddingWork]]`; `ServerOptions.embedding: Option[EmbeddingWork] = None`, `ServerOptions.embeddingConcurrency: Int = 2`; `ServiceServer.embeddingWorker: Option[EmbeddingWorker]`, `embeddingLoopRunning`; `ServiceLog.external(line, fallbackEvent)`.
- Worker settings and defaults: `service.embeddingQueueUrl` (core, optional; absent = job loop only); embedding loop concurrency 2 (services constant default, no config key); drain 60 s; receive 10; long poll 1 s; handler batch limit = `embedding.batchSize` (rag).
- Environment variables the worker task needs (names only): `SCHOLARCHAIN_CFG_SERVICE_EMBEDDING_QUEUE_URL` (the queue URL, worker task only, decision 5); the Voyage key variable named by `embedding.apiKeyEnvVar` (`SCHOLARCHAIN_VOYAGE_API_KEY` in config/course.json); plus the existing ones (`SCHOLARCHAIN_SERVICE_API_KEY`, `SCHOLARCHAIN_SERVICE_READONLY_API_KEY`, the job queue override, the configuration file). The handler is built at start: a missing Voyage key refuses the worker's start when the embedding URL is set.
- AWS calls the worker role makes for the embedding queue (storage handoff): sqs:ReceiveMessage, sqs:DeleteMessage, sqs:ChangeMessageVisibility; no SendMessage. Plus the re-embed path's reads/writes (rag/storage handoffs).
- Log line format and writer: one line per delivery, written by rag's `ReembedHandler` through the server log sink (slf4j logger `org.scholarchain.services`, one JSON object per line): `{"time","role","event":"reembed-delivery","taskId","index","outcome","code","action","latencyMillis"}` (`time` and `role` added by services; the rest is rag's line unchanged; `outcome` in processed|replayed|permanent-failure|transient-failure; `action` in acknowledge|release|leave). Never body, text, vector key, receipt or URL. Services' own lines: `started` gains `"embeddingWorker": true|false`; `embedding-queue-failure` (`operation` receive|ack|release, `count`, `cause`, `exception` class name) per failing call.
- Explain field: `abstentionReasons`, JSON array of `{"code","text"}` (attempt 1 handoff unchanged).
- Thread-safety: EmbeddingWorker holds only atomic counters; queue (SQS client) and handler are thread-safe; each loop thread handles its own batch.
- Fixtures: `EmbeddingWorkerSuite.StubQueue` (test-only, SQS-like receive/ack/release with receive counts); `ServiceTestKit.harness(embedding = ...)`.

## ASSUMPTIONS

- A1. The concurrency "setting" is `ServerOptions.embeddingConcurrency` with a named default, not a configuration key: a `service.*` key is core (domain-engineer) work not in this wave. Justification of 2: one handler call per receive = one Voyage request of about 1-4 s (estimate, rag report), so 2 threads send about 0.5-2 requests/s, under 300 RPM = 5/s (the embedder's rate limiter enforces it anyway); worker task 1 vCPU / 2 GB (Terraform `task_sizes.worker`) also runs 2 job threads.
- A2. `close()` now shuts all pools down at once and waits from a common start (before: one after another, worst case up to 4 x 25 s). Behaviour for the job loop is the same (it is stopped first, as before); only the worst-case total shutdown time is shorter.
- A3. rag's line is not passed through the redactor (its 12-digit rule could alter a task ID); its fields are fixed vocabularies and validated IDs (rag contract).
- A4. `--stores local` ignores `service.embeddingQueueUrl` (no SQS locally); `Composition.embeddingWork` refuses it with a local runtime if called.
- A5. Readiness (`/readyz`) does not probe the embedding queue (not asked; see proposed later).

## RISKS

- R1. A Voyage call cut off after 60 s on shutdown leaves its messages for the visibility timeout (300 s); they are handled again (replayed if their write and completion record finished). At least once, as designed.
- R2. The first live run of the loop is session 3; the AWS path is tested only with stub SDK clients (zero HTTP calls).
- R3. packageCheck took 4 s (assemblies up to date from sbt check's compile); its "ok" covers the services jar including the new class (not inspected beyond packageCheck).

## Proposed later

- a `service.embeddingWorkerConcurrency` configuration key (domain-engineer) if the default needs tuning per environment;
- a readiness check of the embedding queue for the worker role.

## VALIDATION EVIDENCE

```text
work order sha256: 3d806922c6dd74ada61bceca45d3a302740a4083f260f0ae3ca6b6b6b9dfa3e0 (matches)
targeted: sbt -batch "services/testOnly org.scholarchain.services.EmbeddingWorkerSuite org.scholarchain.services.CompositionSuite"
  CompositionSuite finished: 0 failed, 0 ignored, 8 total
  EmbeddingWorkerSuite finished: 0 failed, 0 ignored, 9 total
  [info] Passed: Total 17, Failed 0, Errors 0, Passed 17 -- [success] completed Nov 20, 2026, 1:06:43 PM
  (attempt 1: ReadRoutesSuite 12, GoldenExamplesSuite 17, QueryApiSuite 11 passed 12:53:39; all rerun in sbt check)
full: sbt check
  scalafmt check of every project; Module tests: suites=139 executed=2192 passed=2192 failed=0 errors=0 skipped=3
  Updated .claude/.last-test-run (module_tests=2192, contract_tests=399)
  [success] Total time: 975 s (0:16:15.0), completed Nov 20, 2026, 1:23:08 PM
package: sbt -batch packageCheck
  Built spark, hadoop, entity-resolution, services assemblies; "packageCheck: ok"; [success] completed Nov 20, 2026, 1:23:26 PM
head -n 5 .claude/.last-test-run:
  timestamp=2026-11-20T19:23:08.260971Z
  module_tests=2192
  contract_tests=399
last source edit: 13:06:24 (EmbeddingWorkerSuite.scala), before the check
finished: after the last command above
files changed after these runs: none
```
