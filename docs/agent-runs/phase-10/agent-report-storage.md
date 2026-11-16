STATUS: COMPLETE
OWNER: storage-engineer
WORK ORDER: docs/agent-runs/phase-10/prompt.md (sha256 c032785a6cbef13124c196fb3c42be52b43cfd6e10e712a1861553d1cde5f49a), step 3, decision 6
FILES CREATED:
- modules/storage/src/main/scala/org/scholarchain/storage/jobs/JobModel.scala
- modules/storage/src/main/scala/org/scholarchain/storage/jobs/JobStore.scala
- modules/storage/src/main/scala/org/scholarchain/storage/jobs/IdempotencyStore.scala
- modules/storage/src/main/scala/org/scholarchain/storage/jobs/ServiceStores.scala
- modules/storage/src/main/scala/org/scholarchain/storage/local/InMemoryWorkQueue.scala
- modules/storage/src/test/scala/org/scholarchain/storage/jobs/ServiceStoreFixtures.scala
- modules/storage/src/test/scala/org/scholarchain/storage/jobs/JobStoreSuite.scala
- modules/storage/src/test/scala/org/scholarchain/storage/jobs/IdempotencyStoreSuite.scala
- modules/storage/src/test/scala/org/scholarchain/storage/jobs/InMemoryWorkQueueSuite.scala
- modules/storage/src/test/scala/org/scholarchain/storage/jobs/ServiceStoresSuite.scala
FILES MODIFIED:
- modules/storage/src/main/scala/org/scholarchain/storage/dynamo/TableSpecs.scala (QS3 text, QS4, QS5, key functions)
- modules/storage/src/main/scala/org/scholarchain/storage/aws/SqsWorkQueue.scala (visibility override now Option; Int auxiliary constructor kept)
- modules/storage/src/main/scala/org/scholarchain/storage/aws/AwsStorageEnvironment.scala (AwsSdkClients.sqs: Option[SqsClient] = None; build() creates it; private[storage] sqsClient)
- modules/storage/src/test/scala/org/scholarchain/storage/DynamoSuite.scala (one QuerySession QS4/QS5 spec test)
COMMANDS RUN:
- sbt -batch "storage/compile" -> success
- sbt -batch "storage/testOnly org.scholarchain.storage.jobs.* org.scholarchain.storage.DynamoSuite org.scholarchain.storage.AwsEnvironmentSuite org.scholarchain.storage.WorkQueueSuite" (3 runs; first 2 failed to compile in test code, fixed) -> Passed: Total 102, Failed 0
- sbt -batch check (background, once) -> exit 0, [success] Total time: 918 s
TEST RESULTS:
- Targeted: 102 passed, 0 failed (InMemoryWorkQueueSuite 6, InMemoryIdempotencyStoreSuite 8, DynamoIdempotencyStoreSuite 10, InMemoryJobStoreSuite 19, DynamoJobStoreSuite 21, ServiceStoresSuite 7, DynamoSuite 13, WorkQueueSuite 3, AwsEnvironmentSuite 15)
- sbt check: module_tests=1734 (was 1662; +72 new), contract tests suites=22 executed=336 passed=336 failed=0 errors=0 skipped=0; .claude/.last-test-run updated by markTestRun at 23:58
VALIDATION RESULTS: format check, compile, tests and markTestRun all passed inside sbt check. No RequiresAws test was added or run. No network, no AWS.
ASSUMPTIONS:
- ServiceStores.local takes a third parameter `clock: java.time.Clock`. Decision 6 gives `local(env, visibilityMillis)` but also says the queue is "driven by an injected clock" and storage reads no clock, so the clock has to be passed in. Services already has a clock (decision 7b).
- ServiceStores.aws has an optional `waitSeconds` (default ServiceStores.SqsWaitSeconds = 1). It returns Either, because the queue URL is checked and the environment needs an SQS client.
- Stage writes (`setStage`, running only) are a field update, not a state transition. Decision 7f needs them.
- Job items carry a `writeToken` attribute and QS5 items a `version` attribute. Both are write mechanics, not state or result: the token lets a retried put that was applied server-side report Written, while two writers with identical content still get exactly one winner. The version is needed to replace an expired record under a condition.
- Readers treat an expired item (expiresAt * 1000 <= now) as absent, because DynamoDB TTL removal is lazy. An expired idempotency record is replaced at its version, so the same key starts a new job (decision 7e).
RISKS:
- SQS long-poll wait (1 s) must stay below the SDK apiCallTimeoutMillis that rag's RuntimeService.aws uses. The job queue's visibility timeout and redrive policy are Terraform's (Phase 12) and must exceed jobLeaseMillis.
- In-memory queue has no poison handling. Services must cap attempts with jobMaxAttempts.
MANUAL ACTION REQUIRED: none (the human runs the cp command below).
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: step 4 (rag-engineer), then the orchestrator's interface gate (step 5); code-reviewer upstream review 8a.

## Per-store role and key design (QuerySession table, TTL on expiresAt in epoch seconds)
- QS4 job: pk = `job#<jobId>`, sk = `job`. GetItem; PutItem attribute_not_exists(pk) (create); PutItem `attribute_exists(pk) AND #v = :expected` (every change).
  Attributes: jobId, mode, state, stage?, createdAt, updatedAt (ms), attempts, leaseUntil? (ms, set exactly while running), queryRunId, question, windowLabel?, topic?, includeEvidence, traceId, errorCode?, errorMessage?, result? (set exactly when succeeded), version, expiresAt (s), writeToken.
- QS5 idempotency: pk = `idempotency#<sha256 hex of Hashing.canonicalEncoding(Seq(scope, key))>`, sk = `idempotency`. Attributes: fingerprint, jobId, expiresAt, version. The raw key is not stored.
- Job queue: local InMemoryWorkQueue; AWS SqsWorkQueue at the configured URL, with no per-receive visibility override (the queue's own setting applies).

## Idempotency evidence
- Create is put-if-absent; a second create of the same ID gives ConditionFailed and writes nothing.
- Exactly one of two (and of eight concurrent) claims or reclaims of one version wins.
- A retried applied write gives Written; this is proven on the DynamoDB stub with applyThenFail.
- A second idempotency claim returns the first record unchanged. Of eight concurrent first claims, exactly one is Claimed.

## Bounded load results
Not applicable. No loader changed and no bulk load was added.

## INTERFACE HANDOFF
Files changed: see FILES CREATED / MODIFIED above. RuntimeStores trait: unchanged.

Package org.scholarchain.storage.jobs (services imports only this package, plus org.scholarchain.storage.ports for WorkQueue/ReceivedWork/WorkItem):

```scala
sealed abstract class JobMode(val wire: String)        // JobMode.Sync "sync", JobMode.Async "async"; fromWire
sealed abstract class JobState(val wire: String) { def terminal: Boolean } // Queued, Running, Succeeded, Failed; fromWire
final case class JobRequest(question: String, windowLabel: Option[String], topic: Option[String], includeEvidence: Boolean)
final case class JobError(code: String, message: String)   // code [A-Z][A-Z0-9_]{0,63}, message <= 1000 chars
final case class NewJob(jobId: String, mode: JobMode, queryRunId: RunId, request: JobRequest,
                        traceId: String, createdAtMillis: Long, expiresAtSeconds: Long)
final case class JobRecord(jobId: String, mode: JobMode, state: JobState, stage: Option[String],
    createdAtMillis: Long, updatedAtMillis: Long, attempts: Int, leaseUntilMillis: Option[Long],
    queryRunId: RunId, request: JobRequest, traceId: String, error: Option[JobError],
    result: Option[String], version: Long, expiresAtSeconds: Long) {
  def isExpired(nowMillis: Long): Boolean; def leaseLive(nowMillis: Long): Boolean; def leaseExpired(nowMillis: Long): Boolean
}
sealed trait JobChange   // Written(job: JobRecord) | ConditionFailed | Refused(reason: String) | ResultTooLarge(bytes: Int, limitBytes: Int)

trait JobStore {
  def create(job: NewJob): Either[String, JobChange]                                  // Written | ConditionFailed
  def get(jobId: String, nowMillis: Long): Either[String, Option[JobRecord]]          // None if absent or expired
  def claim(job: JobRecord, nowMillis: Long, leaseUntilMillis: Long): Either[String, JobChange]   // queued->running
  def reclaim(job: JobRecord, nowMillis: Long, leaseUntilMillis: Long): Either[String, JobChange] // running, leaseUntil < now
  def release(job: JobRecord, nowMillis: Long): Either[String, JobChange]             // running->queued
  def succeed(job: JobRecord, nowMillis: Long, result: String): Either[String, JobChange] // running->succeeded (+result)
  def fail(job: JobRecord, nowMillis: Long, error: JobError): Either[String, JobChange]  // queued|running->failed
  def setStage(job: JobRecord, nowMillis: Long, stage: String): Either[String, JobChange] // running only
}
final case class IdempotencyRecord(fingerprint: String, jobId: String, expiresAtSeconds: Long) { def isExpired(nowMillis: Long): Boolean }
sealed trait IdempotencyClaim   // Claimed(record) | Existing(record)
trait IdempotencyStore {
  def claim(scope: String, idempotencyKey: String, fingerprint: String, jobId: String,
            expiresAtSeconds: Long, nowMillis: Long): Either[String, IdempotencyClaim]
  def get(scope: String, idempotencyKey: String, nowMillis: Long): Either[String, Option[IdempotencyRecord]]
}
trait ServiceStores {
  def jobs: JobStore; def idempotency: IdempotencyStore; def jobQueue: WorkQueue
  def enqueueJob(jobId: String): Either[String, Unit]
}
object ServiceStores {
  val SqsWaitSeconds: Int = 1
  def over(kv: KeyValueStore, querySessionTable: String, queue: WorkQueue): ServiceStores
  def local(env: LocalRuntimeEnvironment, visibilityMillis: Long, clock: java.time.Clock): Either[String, ServiceStores]
  def aws(env: AwsStorageEnvironment, queueUrl: String, waitSeconds: Int = SqsWaitSeconds): Either[String, ServiceStores] // no network call
}
object JobMessages { def item(jobId: String): Either[String, WorkItem]; def jobId(work: ReceivedWork): Either[String, String] }
object JobLimits { MaxResultBytes = 300000; MaxQuestionChars = 2000; MaxTopicChars = 200; MaxErrorMessageChars = 1000;
  JobIdPattern "[A-Za-z0-9._-]{1,128}"; TraceIdPattern "[0-9a-f]{32}"; StagePattern "[A-Za-z0-9._:-]{1,64}";
  ScopePattern "[a-z0-9-]{1,32}"; IdempotencyKeyPattern "[A-Za-z0-9._:-]{1,128}"; FingerprintPattern "[A-Za-z0-9:._-]{1,128}" }
```
Job queue (org.scholarchain.storage.ports.WorkQueue): `enqueue(items: Seq[WorkItem]): Int`, `receive(max: Int): List[ReceivedWork]`, `ack(work)`, `release(work)`; `ReceivedWork(item, receipt, receiveCount)`.
Local implementation: `org.scholarchain.storage.local.InMemoryWorkQueue(visibilityMillis: Long, clock: Clock)`, with `size`, `visibleCount`, `inFlightCount`, `ackedCount` and `MaxVisibilityMillis` (12 h).

Semantics: `Left` = malformed argument or store failure (after storage's retry; map to 502). JobChange values are the expected outcomes and are never thrown. Transition checks run against the caller's record; the write only succeeds at that record's version. Each change sets updatedAt = now and version + 1. Claim and reclaim increment attempts and set leaseUntil. Release, succeed and fail clear the lease. Claim and reclaim are Refused when leaseUntil <= now. Reclaim is Refused while leaseUntil >= now. ResultTooLarge is checked after the state check; it counts UTF-8 bytes and writes nothing. Times: millis except expiresAtSeconds (epoch seconds, TTL).

Usage example:
```scala
val stores = ServiceStores.local(env, visibilityMillis = 60000L, clock).fold(sys.error, identity)
val now = clock.millis()
val exp = now / 1000L + ttlSeconds
stores.jobs.create(NewJob(jobId, JobMode.Async, runId, JobRequest(q, Some(window), None, true), traceId, now, exp)) // Written(queued)
stores.enqueueJob(jobId)                                                       // enqueue before the claim
stores.idempotency.claim("full", idemKey, fingerprint, jobId, exp, now) match {
  case Right(IdempotencyClaim.Claimed(_))   => // ours
  case Right(IdempotencyClaim.Existing(r))  => // compare r.fingerprint; follow r.jobId; fail own job with SUPERSEDED
  case Left(e)                              => // 502
}
// worker
val w = stores.jobQueue.receive(1).head
val id = JobMessages.jobId(w).toOption.get
val job = stores.jobs.get(id, clock.millis()).toOption.flatten.get
stores.jobs.claim(job, clock.millis(), clock.millis() + leaseMillis) match {
  case Right(JobChange.Written(running)) =>
    stores.jobs.succeed(running, clock.millis(), resultJson) match {
      case Right(JobChange.Written(_))            => stores.jobQueue.ack(w)
      case Right(JobChange.ResultTooLarge(_, _))  => /* fail RESULT_TOO_LARGE, then ack */
      case Right(JobChange.ConditionFailed)       => /* discard result; leave the message */
      case other                                  => ()
    }
  case Right(JobChange.ConditionFailed) => () // another worker won; leave the message
  case _ => ()
}
```

Test fixtures (storage test tree, on services' test classpath), package org.scholarchain.storage.jobs:
- `ManualClock(startMillis = ManualClock.DefaultStartMillis)`: a java.time.Clock with `advance(ms)` and `set(ms)`.
- `ServiceStoreFixtures.inMemory(clock, visibilityMillis = 60000)` returns `InMemoryServiceStores(kv, queue, stores, querySessionTable)`. Also `newJob(jobId, nowMillis, mode, ttlSeconds)`, `queryRunId`, `traceId`, `request`, `querySessionTable`.
- `ToggleKeyValueStore(underlying)`: set `failing` to make every call throw a transient StoreException (502 and circuit tests).
- `ConditionalDynamoStub`: a DynamoDbClient that evaluates storage's two conditions, with `applyThenFail(n)`, `puts`, `gets` and `conditions`.
- `StubSqsClient`: an SqsClient FIFO stub with `sends`, `receives`, `acks`, `visibilityChanges` and `size`.

Thread-safety: TableJobStore and TableIdempotencyStore are stateless over a thread-safe KeyValueStore, so they are safe. InMemoryWorkQueue synchronizes every operation. SqsWorkQueue uses the thread-safe SDK client. Every ServiceStores bundle is safe to share across threads. ManualClock, ConditionalDynamoStub and StubSqsClient are thread-safe.

Known limitations / proposed later: no poison queue locally; SQS redrive comes from Terraform in Phase 12. Expired items stay until DynamoDB TTL removes them, so readers filter them. ServiceStores has no close(); the SQS client closes with AwsStorageEnvironment.close() through AwsSdkClients. The job error codes themselves are services' vocabulary: storage checks only their shape.
