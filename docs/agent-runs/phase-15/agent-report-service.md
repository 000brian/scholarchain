interface: no (no fixed interface changed; a producer gap blocks item 8.1, see BLOCKED-ON EVIDENCE)

```text
STATUS: BLOCKED-ON
OWNER: service-engineer
WORK ORDER: docs/agent-runs/phase-15/prompt.md (sha256 3d806922c6dd74ada61bceca45d3a302740a4083f260f0ae3ca6b6b6b9dfa3e0, verified with shasum), session 2 step 4, decision 8; wave closer
FILES CREATED: none
FILES MODIFIED:
  /Users/brian/Developer/repos/scholarchain/modules/services/src/main/scala/org/scholarchain/services/Models.scala (ExplainView.abstentionReasons, AbstentionReason)
  /Users/brian/Developer/repos/scholarchain/modules/services/src/test/resources/api/v1/explain.json (auditSchemaVersion 3, "abstentionReasons": [])
  /Users/brian/Developer/repos/scholarchain/modules/services/src/test/scala/org/scholarchain/services/ReadRoutesSuite.scala (v3 expectations; new abstained-explain test)
COMMANDS RUN: shasum -a 256 (work order); ls; grep -rn (literal words) over modules/ and experiments/collectors/; git status --short / git diff --stat modules/services; sbt -batch "services/testOnly org.scholarchain.services.ReadRoutesSuite org.scholarchain.services.GoldenExamplesSuite org.scholarchain.services.QueryApiSuite"; head -n 5 .claude/.last-test-run. (Bash commands were prefixed `cd REPO &&`, read-only effect: unplanned deviation, same as earlier steps.)
TEST RESULTS: services ReadRoutesSuite 12/12, GoldenExamplesSuite 17/17, QueryApiSuite 11/11: "Passed: Total 40, Failed 0, Errors 0, Passed 40" [success] 12:53:39
VALIDATION RESULTS: full sbt check: NOT RUN (blocked before the worker item; the wave close waits for the rerun). sbt -batch packageCheck: NOT RUN.
ASSUMPTIONS: see below
RISKS: see below
MANUAL ACTION REQUIRED: none from the human. Orchestrator: route repair-00-storage (below), then rerun step 4 in a fresh context.
BLOCKED-ON OWNER: storage-engineer
BLOCKED-ON EVIDENCE: see section "BLOCKED-ON EVIDENCE" (storage has no queue port that delivers a raw embedding-task-v1 SQS body to services; SqsWorkQueue.receive parses every body as {"id","payload"} and throws on the fixed message format)
NEXT GATE: repair-00-storage (targeted storage tests), then step 4 rerun: worker loop, targeted tests, full sbt check, sbt -batch packageCheck
```

## BLOCKED-ON EVIDENCE (storage-engineer)

Decision 8.1 needs the worker to receive, acknowledge and release messages of the embedding queue. Law 1 of service-engineer and CLAUDE.md ("Only `storage` talks to AWS data stores") forbid services from building an SQS client or calling the SDK; the queue must come through a storage port. The interface as handed over does not provide one:

1. `modules/storage/src/main/scala/org/scholarchain/storage/aws/SqsWorkQueue.scala:72-76`: `receive` does `val o = ujson.read(m.body()).obj` and `WorkItem(o("id").str, o("payload").str)`. The fixed message format `embedding-task-v1` (decision 7.2; rag `ReembedHandler.scala` `object EmbeddingTask`) is `{"schema","taskId","index","vectorKey"}` sent **raw** by the collectors (`experiments/collectors/f-poison-replay.sh:282` `send-message --message-body "${body}"`, `experiments/collectors/c-embedding-backlog.sh:340` `send-message-batch`). So every valid task throws `NoSuchElementException` on `o("id")` (not a `StoreException`), and the poison body that is not JSON throws in `ujson.read`. The exception loses the **whole received batch** (up to 10 messages): none can be released at once, none acknowledged; F's poison would never be released (visibility 0) and C's valid tasks would never be processed.
2. `modules/storage/src/main/scala/org/scholarchain/storage/aws/AwsStorageEnvironment.scala:219`: `private[storage] def sqsClient`; services cannot reach the SQS client.
3. `modules/storage/src/main/scala/org/scholarchain/storage/jobs/ServiceStores.scala:86-100`: `ServiceStores.aws(env, queueUrl, waitSeconds)` builds only the job queue (`new SqsWorkQueue(client, url, waitSeconds, None)`); there is no factory for a second queue.
4. storage's INTERFACE HANDOFF (agent-report-storage.md:124-168) lists no embedding-queue receive path; rag's (agent-report-rag.md:165) says "SQS Receive/Delete/ChangeMessageVisibility are the worker's (service-engineer)", which services can only do through such a port.

**What services needs from storage (suggested shape; storage decides the details):**
- a factory such as `ServiceStores.embeddingQueue(env: AwsStorageEnvironment, queueUrl: String, waitSeconds: Int = ServiceStores.SqsWaitSeconds): Either[String, WorkQueue]` (validated like the job queue URL; message never names the URL; no network call when built), returning a queue whose
  - `receive(max)` (max 1..10, `ApproximateReceiveCount` requested) returns `ReceivedWork(WorkItem(<SQS MessageId>, <raw body, unparsed>), receiptHandle, receiveCount)` and **never throws for a body's content** (a non-JSON or foreign body is delivered as-is; the rag handler classifies it as poison);
  - `ack` = DeleteMessage, `release` = ChangeMessageVisibility 0 (both exist today in `SqsWorkQueue.scala:80-101`); the queue's own visibility timeout (Terraform, 300 s) applies to receives (`visibilitySeconds = None`);
  - optional: a body length bound with a defined behaviour (rag's handler already refuses bodies over 4,096 characters as `body-too-large`, poison).
- a test with a stub SQS client: raw body passed through unchanged, a non-JSON body delivered (no throw), receive count read, ack/release calls made with the receipt handle.
- Local tests in services can use `org.scholarchain.storage.local.InMemoryWorkQueue` (payload = raw body), so no local factory is required.

No producer file was edited by this agent.

## INTERFACE CHECK (done first)

| Name | Where (file:line) | Result |
|---|---|---|
| `service.embeddingQueueUrl` key | modules/core/src/main/scala/org/scholarchain/core/config/ConfigLoader.scala:240; validation :894-895 | match |
| `ServiceSettings.embeddingQueueUrl: Option[String] = None` | modules/core/src/main/scala/org/scholarchain/core/config/ScholarChainConfig.scala:408 | match |
| env `SCHOLARCHAIN_CFG_SERVICE_EMBEDDING_QUEUE_URL` | domain report (ServiceConfigSuite:408); orchestrator grep vs 5a runtime/main.tf:68 | match |
| `QueryAudit.abstentionCodes: List[String] = Nil` | modules/core/src/main/scala/org/scholarchain/core/Records.scala:752; `V3Fields` :761 | match |
| `ReembedHandler.fromConfig(...)` | modules/rag/src/main/scala/org/scholarchain/rag/reembed/ReembedHandler.scala:338; `Built` :330 | match |
| `handle(deliveries: Seq[Delivery]): Vector[HandledDelivery]` | ReembedHandler.scala:216; `Delivery(deliveryId, body)` :124; `HandledDelivery` :138 | match |
| `DeliveryAction` Acknowledge / ReleaseNow / LeaveForRetry | ReembedHandler.scala:107, :110, :115, :118 | match |
| log event `reembed-delivery` (keys event, taskId, index, outcome, code, action, latencyMillis) | ReembedHandler.scala:300 (orchestrator grep: keys at :320-322) | match |
| `AwsStorageEnvironment.reembedder(embedder, sleeper)` | modules/storage/src/main/scala/org/scholarchain/storage/aws/AwsStorageEnvironment.scala:206; local LocalStorageEnvironment.scala:67 | match |
| `AbstentionCodes.reasons(codes): List[(String, String)]`, `text`, `Table`, `Unclassified` | modules/rag/src/main/scala/org/scholarchain/rag/runtime/Abstentions.scala:88, :85, :52, :49 | match |
| explain field name `abstentionReasons` (array of {code, text}) | agent-report-rag.md:161 | match, implemented |
| **an embedding-queue receive/ack/release port reachable by services** | none: SqsWorkQueue.scala:72-76 parses `{"id","payload"}`; AwsStorageEnvironment.scala:219 `private[storage] sqsClient`; ServiceStores.scala:86-100 job queue only | **MISMATCH -> BLOCKED-ON storage-engineer** |

## Status per item (decision 8)

| Item | Status | Where |
|---|---|---|
| 8.1 worker second loop on the embedding queue | blocked-on storage-engineer (not started; no services file for it changed) | evidence above |
| 8.2 `abstentionReasons` on GET /v1/explain/{queryId} | done | Models.scala:259 (field), :269-273 (`AbstentionReason`), :310-312 (built with `AbstentionCodes.reasons(latest.abstentionCodes)`) |
| 8.2 `api/v1/explain.json` and ReadRoutesSuite.scala:126 (audit v3) | done | explain.json: `"abstentionReasons": []`, `"auditSchemaVersion": 3`; ReadRoutesSuite.scala:126-131 |
| 8.3 tests: explain with and without reasons | done | without: ReadRoutesSuite.scala:131 (v3, not abstained), :178 (v1 record); with: :181-208 (two table codes plus an unknown well-formed code reading as `unclassified`; round-trip into `ExplainView`). Fails without the change: the field does not exist (`ex("abstentionReasons")` throws; `view.abstentionReasons` does not compile), and :126-127 fail on schema version 2 vs 3 |
| 8.3 tests: both loops, poison never acknowledged and released, replay acknowledged once, shutdown | blocked (needs 8.1) | |
| 8.4 targeted tests | partial: explain suites 40/40 | VALIDATION EVIDENCE |
| 8.4 full sbt check, packageCheck | not run (blocked) | |
| 8.5 INTERFACE HANDOFF | partial (explain field final; worker settings proposed only) | below |

## Route table change

`GET /v1/explain/{queryId}`: unchanged status codes (200, 400 INVALID_REQUEST, 404 QUERY_NOT_FOUND, 502/503 store failures and CIRCUIT_OPEN as before); body gains `abstentionReasons`. No other route changed.

## Error model coverage

Unchanged (no new error code).

## Limits

Unchanged. `abstentionReasons` holds at most 8 entries (core `QueryAudit.MaxAbstentionCodes`), each code at most 64 characters.

## ASSUMPTIONS

- A1. `abstentionReasons` is always present (an empty array when not abstained or for a v1/v2 record), so the frozen example and clients see one shape; no new note is added for v1/v2 records (they stored no codes; `auditSchemaVersion` tells the client).
- A2. Worker design for the rerun (proposed, not built): a separate `EmbeddingLoop` beside the unchanged `WorkerLoop`, its own fixed pool; concurrency a named constant `ServiceLimits.EmbeddingWorkerConcurrency` = 2 (a config key would be core/domain-engineer work); each iteration receives up to 10 messages with the job loop's 1 s long poll (`ServiceStores.SqsWaitSeconds`), calls `handler.handle` once, then acknowledges `Acknowledge`, releases `ReleaseNow`, does nothing for `LeaveForRetry`. Basis for 2: Voyage 300 RPM = 5 requests/s per task; one handler call of up to 10 deliveries makes one Voyage call and takes about 1.0-4.1 s (rag estimate, agent-report-rag.md:109-114), so 2 loops make at most about 2 calls/s, under 5/s with room for the job loop's query embeddings; the worker task's CPU and memory to be read from Terraform at the rerun (not read now). The log line is rag's (written through the `log` function services passes); services would not write a second per-message line.

## RISKS

- R1. Until repair-00-storage, the wave has no full `sbt check`; storage, rag and core changes of this wave are unchecked by the full gate.
- R2. The explain model change is additive for JSON clients; a strict client that rejects unknown fields would see a new key (none known).

## INTERFACE HANDOFF (partial)

- Explain field (final): `GET /v1/explain/{queryId}` body key `abstentionReasons`: JSON array of `{"code": String, "text": String}` in the stored order, from `QueryAudit.abstentionCodes` through `AbstentionCodes.reasons`; `[]` when not abstained or the record is schema version 1 or 2; unknown codes read as the `unclassified` text. Model: `org.scholarchain.services.ExplainView.abstentionReasons: List[AbstentionReason]`, `AbstentionReason(code, text)` (Models.scala:259, :272). `auditSchemaVersion` is 3 for records rag writes now.
- Worker settings, environment variables (names only) and the log line format: to be handed over by the rerun after repair-00-storage. Expected environment variable for the worker task: `SCHOLARCHAIN_CFG_SERVICE_EMBEDDING_QUEUE_URL` (decision 5), plus the Voyage key variable named by `embedding.apiKeyEnvVar` (`SCHOLARCHAIN_VOYAGE_API_KEY`, already injected into service tasks per decision 9.1). Log line: rag's `reembed-delivery` JSON (ReembedHandler.scala:300), written once per delivery by rag's handler through the logger services passes.
- Files changed: the three listed under FILES MODIFIED. Thread-safety: `ExplainView` is an immutable value built per request. Fixtures: none new.

## Proposed later

- none.

## VALIDATION EVIDENCE

```text
work order sha256: 3d806922c6dd74ada61bceca45d3a302740a4083f260f0ae3ca6b6b6b9dfa3e0 (matches)
sbt -batch "services/testOnly org.scholarchain.services.ReadRoutesSuite org.scholarchain.services.GoldenExamplesSuite org.scholarchain.services.QueryApiSuite":
  GoldenExamplesSuite finished: 0 failed, 0 ignored, 17 total
  ReadRoutesSuite finished: 0 failed, 0 ignored, 12 total
  QueryApiSuite finished: 0 failed, 0 ignored, 11 total
  [info] Passed: Total 40, Failed 0, Errors 0, Passed 40 -- [success] Total time: 11 s, completed Nov 20, 2026, 12:53:39 PM
  reports: modules/services/target/test-reports/TEST-...{GoldenExamplesSuite,QueryApiSuite,ReadRoutesSuite}.xml written 12:53
full sbt check: NOT RUN (BLOCKED-ON storage-engineer before item 8.1)
sbt -batch packageCheck: NOT RUN
head -n 5 .claude/.last-test-run:
  timestamp=2026-11-19T00:09:26.123512Z
  module_tests=2076
  contract_tests=399
  (the full marker is from Phase 14; not refreshed by this step)
finished: after the last command above
files changed after these runs: none
```
