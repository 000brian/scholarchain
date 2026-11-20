interface: yes

```text
STATUS: COMPLETE
OWNER: storage-engineer
WORK ORDER: docs/agent-runs/phase-15/prompt.md (sha256 3d806922c6dd74ada61bceca45d3a302740a4083f260f0ae3ca6b6b6b9dfa3e0, as given by the orchestrator; not re-hashed), session 2 repair-00-storage (decisions 4 "Interface gate", 7.2, 8.1); not the wave closer
FILES CREATED:
  /Users/brian/Developer/repos/scholarchain/modules/storage/src/main/scala/org/scholarchain/storage/aws/SqsRawReceiveQueue.scala
  /Users/brian/Developer/repos/scholarchain/modules/storage/src/test/scala/org/scholarchain/storage/jobs/EmbeddingQueueSuite.scala
FILES MODIFIED:
  /Users/brian/Developer/repos/scholarchain/modules/storage/src/main/scala/org/scholarchain/storage/ports/WorkQueue.scala (new trait ReceiveQueue; WorkQueue extends ReceiveQueue)
  /Users/brian/Developer/repos/scholarchain/modules/storage/src/main/scala/org/scholarchain/storage/jobs/ServiceStores.scala (ServiceStores.embeddingQueue; validQueueUrl takes a queue name, job-queue text unchanged)
  /Users/brian/Developer/repos/scholarchain/modules/storage/src/test/scala/org/scholarchain/storage/jobs/ServiceStoreFixtures.scala (StubSqsClient: putRaw, deletedReceipts, visibilityTimeouts)
COMMANDS RUN:
  grep -n / grep -rn / ls / git status --short (read-only; Bash calls were prefixed `cd REPO &&`, read-only effect)
  sbt -batch "storage/testOnly org.scholarchain.storage.jobs.EmbeddingQueueSuite org.scholarchain.storage.jobs.ServiceStoresSuite org.scholarchain.storage.jobs.InMemoryWorkQueueSuite org.scholarchain.storage.WorkQueueSuite"
  sbt -batch "rag/Test/compile" "services/Test/compile" "contractTests/Test/compile"
TEST RESULTS: EmbeddingQueueSuite 9/9, ServiceStoresSuite 7/7, InMemoryWorkQueueSuite 6/6, WorkQueueSuite 3/3: "Passed: Total 25, Failed 0, Errors 0, Passed 25" [success] 12:57:50
VALIDATION RESULTS: rag/Test/compile [success] 12:57:57; services/Test/compile [success] 12:58:01; contractTests/Test/compile [success] 12:58:08. Full sbt check: not run (not the wave closer).
ASSUMPTIONS: the worker only consumes (no SendMessage); body size limits are the consumer's (rag MaxBodyChars 4096); the queue's visibility timeout and redrive policy are Terraform's.
RISKS: an SDK exception's own text (wrapped by AwsErrors) could in principle carry the endpoint host; storage adds no URL to any message. "Fails without the change": EmbeddingQueueSuite does not compile without ServiceStores.embeddingQueue/SqsRawReceiveQueue/ReceiveQueue; the old SqsWorkQueue receive path would throw on the non-JSON and task bodies the suite feeds.
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: step 4 rerun (service-engineer, wave closer): worker loop over ServiceStores.embeddingQueue, targeted tests, full sbt check
```

## INTERFACE HANDOFF

- Port (modules/storage/src/main/scala/org/scholarchain/storage/ports/WorkQueue.scala:19):
  `trait ReceiveQueue { def receive(max: Int): List[ReceivedWork]; def ack(work: ReceivedWork): Unit; def release(work: ReceivedWork): Unit }`
  `trait WorkQueue extends ReceiveQueue` (:33; source-compatible, every existing WorkQueue, including InMemoryWorkQueue, is a ReceiveQueue).
- Factory (modules/storage/src/main/scala/org/scholarchain/storage/jobs/ServiceStores.scala:118):
  `ServiceStores.embeddingQueue(env: AwsStorageEnvironment, queueUrl: String, waitSeconds: Int = ServiceStores.SqsWaitSeconds): Either[String, ReceiveQueue]`
  Left (never naming the URL) when env is null, the URL is not https with a host / has whitespace ("the embedding queue URL must be an https:// URL with a host"), waitSeconds outside 0..20, or env has no SQS client. No network call when built.
- Received shape: `ReceivedWork(WorkItem(id = SQS MessageId, payload = raw body unchanged), receipt = receipt handle, receiveCount = ApproximateReceiveCount)`. Missing body -> "", missing MessageId -> "", missing/unparseable/<1 count -> 1. Bodies are never parsed: any bytes, invalid JSON, empty and foreign formats pass through; no message of a batch is lost to body content. Map to rag `Delivery(deliveryId, body)` as e.g. `Delivery(w.item.id or w.receipt, w.item.payload)` (worker's choice).
- receive(max): max clamped to 1..10; waitTimeSeconds = waitSeconds; requests ApproximateReceiveCount; no visibilityTimeout sent (queue's own, Terraform).
- ack = DeleteMessage(receiptHandle); release = ChangeMessageVisibility(receiptHandle, 0). SDK failures -> StoreException (transient for throttling/5xx/IO), via AwsErrors.guard.
- AWS calls for infra's permission matrix (embedding queue, worker role): sqs:ReceiveMessage SqsRawReceiveQueue.scala:39; sqs:DeleteMessage :56; sqs:ChangeMessageVisibility :65. No sqs:SendMessage.
- Job queue: SqsWorkQueue and ServiceStores.aws unchanged in behavior (ServiceStoresSuite 7/7; EmbeddingQueueSuite "the job queue still parses its own message format").
- Local: no factory; InMemoryWorkQueue is a ReceiveQueue with payload = raw body (test in EmbeddingQueueSuite).
- Fixtures: StubSqsClient.putRaw(body): String (message ID), .deletedReceipts, .visibilityTimeouts (storage test tree, on services' test classpath).
- Thread-safety: SqsRawReceiveQueue is stateless over the thread-safe SDK client.
- Limits: at most 10 messages per receive; wait 0..20 s.

## VALIDATION EVIDENCE

- storage/testOnly (4 suites): "Passed: Total 25, Failed 0, Errors 0, Passed 25" [success] Nov 20, 2026, 12:57:50 PM
- rag/Test/compile [success] 12:57:57; services/Test/compile [success] 12:58:01; contractTests/Test/compile [success] 12:58:08
- last source edit 12:57:32 (EmbeddingQueueSuite.scala), before the test run
files changed after these runs: none
