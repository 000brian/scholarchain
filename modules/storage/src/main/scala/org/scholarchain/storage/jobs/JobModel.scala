package org.scholarchain.storage.jobs

import org.scholarchain.core.RunId

// Records of the QuerySession access patterns QS4 (jobs) and QS5 (idempotency records). Storage
// owns their item formats and keys (see TableSpecs.QuerySession); services pass these typed values
// and never build a key. Storage reads no clock: every time below is passed in by the caller.

/** How a job was submitted. */
sealed abstract class JobMode(val wire: String)
object JobMode {
  case object Sync extends JobMode("sync")
  case object Async extends JobMode("async")
  val all: List[JobMode] = List(Sync, Async)
  def fromWire(s: String): Either[String, JobMode] =
    all.find(_.wire == s).toRight(s"unknown job mode '$s'")
}

/** Job states. Allowed transitions (anything else is `JobChange.Refused`):
  *   - queued -> running (claim), running -> running (reclaim, only after the lease expired);
  *   - running -> queued (release of a failed attempt);
  *   - running -> succeeded (the result is written in the same conditional put);
  *   - running -> failed, queued -> failed.
  *
  * A stage update on a running job (`JobStore.setStage`) changes no state.
  */
sealed abstract class JobState(val wire: String) {
  def terminal: Boolean = this == JobState.Succeeded || this == JobState.Failed
}
object JobState {
  case object Queued extends JobState("queued")
  case object Running extends JobState("running")
  case object Succeeded extends JobState("succeeded")
  case object Failed extends JobState("failed")
  val all: List[JobState] = List(Queued, Running, Succeeded, Failed)
  def fromWire(s: String): Either[String, JobState] =
    all.find(_.wire == s).toRight(s"unknown job state '$s'")
}

/** The canonical request a worker runs the job from (services validated and normalized it).
  *
  * @param question
  *   the canonical question (1..[[JobLimits.MaxQuestionChars]] characters)
  * @param windowLabel
  *   the configured window label the request named, if any
  * @param topic
  *   the one topic constraint, if any (1..[[JobLimits.MaxTopicChars]] characters)
  * @param notes
  *   the response notes for request fields that were accepted but not applied (decision 7c), in the
  *   order services produced them, so a worker's result matches the sync result: at most
  *   [[JobLimits.MaxNotes]] notes of 1..[[JobLimits.MaxNoteChars]] characters, no control
  *   characters. Stored as the `notes` list attribute; an absent attribute reads as `Nil`.
  */
final case class JobRequest(
    question: String,
    windowLabel: Option[String],
    topic: Option[String],
    includeEvidence: Boolean,
    notes: List[String] = Nil
)

/** A job error: a job error code (for example `SUPERSEDED`, `LEASE_EXPIRED`, `ATTEMPTS_EXHAUSTED`,
  * `RESULT_TOO_LARGE`, `WORKFLOW_FAILED`; pattern `[A-Z][A-Z0-9_]{0,63}`) and a fixed message (at
  * most [[JobLimits.MaxErrorMessageChars]] characters; never downstream error text).
  */
final case class JobError(code: String, message: String)

/** What `JobStore.create` needs. The job starts `queued` with 0 attempts at version 1.
  *
  * @param createdAtMillis
  *   the caller's clock, epoch milliseconds
  * @param expiresAtSeconds
  *   the DynamoDB TTL value, epoch seconds (`createdAt + jobTtlSeconds`); must be after createdAt
  */
final case class NewJob(
    jobId: String,
    mode: JobMode,
    queryRunId: RunId,
    request: JobRequest,
    traceId: String,
    createdAtMillis: Long,
    expiresAtSeconds: Long
)

/** One QS4 job item as read.
  *
  * Times: `createdAtMillis`, `updatedAtMillis` and `leaseUntilMillis` are epoch milliseconds from
  * the callers' clocks; `expiresAtSeconds` is the TTL attribute in epoch seconds. `version` is the
  * optimistic-locking version every change is conditioned on (1 at creation, +1 per change).
  * `leaseUntilMillis` is set exactly while the job is `running`. `result` is set exactly when the
  * job is `succeeded`.
  */
final case class JobRecord(
    jobId: String,
    mode: JobMode,
    state: JobState,
    stage: Option[String],
    createdAtMillis: Long,
    updatedAtMillis: Long,
    attempts: Int,
    leaseUntilMillis: Option[Long],
    queryRunId: RunId,
    request: JobRequest,
    traceId: String,
    error: Option[JobError],
    result: Option[String],
    version: Long,
    expiresAtSeconds: Long
) {

  /** The TTL has passed at `nowMillis` (DynamoDB removes expired items lazily, so readers check).
    */
  def isExpired(nowMillis: Long): Boolean = expiresAtSeconds * 1000L <= nowMillis

  /** `running` with a lease that has not passed at `nowMillis` (`leaseUntil >= now`). */
  def leaseLive(nowMillis: Long): Boolean =
    state == JobState.Running && leaseUntilMillis.exists(_ >= nowMillis)

  /** `running` with a lease before `nowMillis`: the only state `JobStore.reclaim` accepts. */
  def leaseExpired(nowMillis: Long): Boolean =
    state == JobState.Running && leaseUntilMillis.forall(_ < nowMillis)
}

/** Outcome of a job write. Store failures (after storage's bounded retries) and malformed arguments
  * are the `Left` of the surrounding `Either`; these are the expected outcomes.
  */
sealed trait JobChange
object JobChange {

  /** The write happened; `job` is the stored record (with its new version). */
  final case class Written(job: JobRecord) extends JobChange

  /** The version (or, for create, the absence) condition did not hold: another writer changed the
    * job since the caller read it, or the job ID already exists. Nothing was written.
    */
  case object ConditionFailed extends JobChange

  /** The transition is not allowed from the caller's record (wrong state, lease still live, lease
    * end not after now). Nothing was written.
    */
  final case class Refused(reason: String) extends JobChange

  /** `succeed` with a result over [[JobLimits.MaxResultBytes]] UTF-8 bytes. Nothing was written. */
  final case class ResultTooLarge(bytes: Int, limitBytes: Int) extends JobChange
}

/** One QS5 idempotency record: the request fingerprint, the job it points to, and its TTL (epoch
  * seconds). It holds no state and no result; both live only on the job.
  */
final case class IdempotencyRecord(fingerprint: String, jobId: String, expiresAtSeconds: Long) {
  def isExpired(nowMillis: Long): Boolean = expiresAtSeconds * 1000L <= nowMillis
}

/** Outcome of an idempotency claim. */
sealed trait IdempotencyClaim
object IdempotencyClaim {

  /** This caller now holds the key; `record` is what was stored. */
  final case class Claimed(record: IdempotencyRecord) extends IdempotencyClaim

  /** Another request holds the key (and its record has not expired); `record` is the stored one.
    */
  final case class Existing(record: IdempotencyRecord) extends IdempotencyClaim
}

/** Bounds storage enforces on QS4 and QS5 values. */
object JobLimits {

  /** Largest stored result in UTF-8 bytes: DynamoDB items are limited to 400 KB, and the rest of a
    * job item is bounded by the limits below (well under 100 KB, notes included: at most 32 x 500
    * characters, under 64 KB in UTF-8).
    */
  val MaxResultBytes: Int = 300000

  /** Same bound as rag's `QueryRequest.MaxQuestionChars` (decision 7c). */
  val MaxQuestionChars: Int = 2000

  /** One topic constraint (decision 7c). */
  val MaxTopicChars: Int = 200

  /** Accepted-but-unapplied request notes on one job (decision 7c): count and characters each. */
  val MaxNotes: Int = 32
  val MaxNoteChars: Int = 500
  val MaxErrorMessageChars: Int = 1000

  /** Job IDs: services use `job-` plus a UUID; storage accepts any ID of this shape. */
  val JobIdPattern: String = "[A-Za-z0-9._-]{1,128}"

  /** W3C trace IDs (32 lowercase hex characters). */
  val TraceIdPattern: String = "[0-9a-f]{32}"
  val StagePattern: String = "[A-Za-z0-9._:-]{1,64}"
  val WindowLabelPattern: String = "[A-Za-z0-9._:-]{1,128}"
  val ErrorCodePattern: String = "[A-Z][A-Z0-9_]{0,63}"

  /** Idempotency caller scope (the caller class, for example `full` or `read-only`). */
  val ScopePattern: String = "[a-z0-9-]{1,32}"

  /** The `Idempotency-Key` header value (decision 7e). */
  val IdempotencyKeyPattern: String = "[A-Za-z0-9._:-]{1,128}"

  /** The request fingerprint (for example `sha256:<hex>`). */
  val FingerprintPattern: String = "[A-Za-z0-9:._-]{1,128}"
}
