package org.scholarchain.storage.jobs

import java.nio.charset.StandardCharsets
import java.util.UUID

import scala.collection.immutable.ListMap

import org.scholarchain.core.RunId
import org.scholarchain.storage.DynamoDbLimits
import org.scholarchain.storage.dynamo.{ItemSize, TableSpecs}
import org.scholarchain.storage.ports._
import org.scholarchain.storage.runtime.RuntimeStores

/** QS4: one item per job in the QuerySession table.
  *
  * Every method returns `Left` for a malformed argument or a store failure (after storage's bounded
  * retries); expected outcomes are [[JobChange]] values and never exceptions. Storage reads no
  * clock: `nowMillis`, `leaseUntilMillis`, `createdAtMillis` and `expiresAtSeconds` come from the
  * caller.
  *
  * Every change is a put conditioned on the version of the record the caller passes (the version it
  * read). The transition is checked against that record; because the write only succeeds when the
  * stored version is still the same, the stored item is exactly that record when the check holds.
  */
trait JobStore {

  /** Put-if-absent of a new `queued` job (version 1, 0 attempts). `ConditionFailed` when the job ID
    * already exists.
    */
  def create(job: NewJob): Either[String, JobChange]

  /** The job, or None when it does not exist or its TTL has passed at `nowMillis`. */
  def get(jobId: String, nowMillis: Long): Either[String, Option[JobRecord]]

  /** queued -> running: `attempts + 1`, lease until `leaseUntilMillis` (must be after `nowMillis`).
    */
  def claim(job: JobRecord, nowMillis: Long, leaseUntilMillis: Long): Either[String, JobChange]

  /** running -> running after a lost worker: allowed only when the record's `leaseUntil` is before
    * `nowMillis`; `attempts + 1` and a new lease until `leaseUntilMillis`.
    */
  def reclaim(job: JobRecord, nowMillis: Long, leaseUntilMillis: Long): Either[String, JobChange]

  /** running -> queued (a released attempt): the lease is cleared; `attempts` is kept. */
  def release(job: JobRecord, nowMillis: Long): Either[String, JobChange]

  /** running -> succeeded with `result` in the same conditional put. `ResultTooLarge` (nothing
    * written) when the result exceeds [[JobLimits.MaxResultBytes]] UTF-8 bytes.
    */
  def succeed(job: JobRecord, nowMillis: Long, result: String): Either[String, JobChange]

  /** running -> failed or queued -> failed with `error`; the lease is cleared. */
  def fail(job: JobRecord, nowMillis: Long, error: JobError): Either[String, JobChange]

  /** The workflow stage of a `running` job (no state change, no attempt, same lease). */
  def setStage(job: JobRecord, nowMillis: Long, stage: String): Either[String, JobChange]
}

/** [[JobStore]] over any [[KeyValueStore]]: the in-memory tables locally, the DynamoDB adapter
  * (with storage's bounded retry) in AWS. Thread-safe when `kv` is (both adapters are).
  *
  * A retried put whose first attempt was applied server-side sees its own condition fail. Every
  * write therefore carries a fresh `writeToken`; on `ConditionFailed` the item is read once and a
  * stored token equal to this write's token means the write happened (`Written`). Two writers with
  * identical content still have different tokens, so exactly one of them wins.
  *
  * @param tokens
  *   source of write tokens (default: random UUIDs; tests may fix them)
  */
final class TableJobStore(
    kv: KeyValueStore,
    table: String,
    tokens: () => String = () => UUID.randomUUID().toString
) extends JobStore {
  import JobItems._

  def create(job: NewJob): Either[String, JobChange] =
    RuntimeStores.guard(for {
      _ <- validateNew(job)
      record = JobRecord(
        job.jobId,
        job.mode,
        JobState.Queued,
        None,
        job.createdAtMillis,
        job.createdAtMillis,
        0,
        None,
        job.queryRunId,
        job.request,
        job.traceId,
        None,
        None,
        1L,
        job.expiresAtSeconds
      )
      token = tokens()
      item <- encode(record, token)
      out = kv.putIfAbsent(table, item) match {
        case ConditionalResult.Written         => JobChange.Written(record)
        case ConditionalResult.ConditionFailed =>
          if (storedToken(item.key).contains(token)) JobChange.Written(record)
          else JobChange.ConditionFailed
      }
    } yield out)

  def get(jobId: String, nowMillis: Long): Either[String, Option[JobRecord]] =
    RuntimeStores.guard(for {
      id <- checkJobId(jobId)
      rec <- kv.get(table, key(id)) match {
        case None    => Right(None)
        case Some(i) => decode(i).map(Some(_))
      }
    } yield rec.filterNot(_.isExpired(nowMillis)))

  def claim(job: JobRecord, nowMillis: Long, leaseUntilMillis: Long): Either[String, JobChange] =
    change(job, nowMillis) {
      if (job.state != JobState.Queued) refuse(s"claim needs a queued job; it is ${job.state.wire}")
      else if (leaseUntilMillis <= nowMillis) refuse("leaseUntil must be after now")
      else
        ok(
          job.copy(
            state = JobState.Running,
            attempts = job.attempts + 1,
            leaseUntilMillis = Some(leaseUntilMillis),
            error = None
          )
        )
    }

  def reclaim(job: JobRecord, nowMillis: Long, leaseUntilMillis: Long): Either[String, JobChange] =
    change(job, nowMillis) {
      if (job.state != JobState.Running)
        refuse(s"reclaim needs a running job; it is ${job.state.wire}")
      else if (!job.leaseExpired(nowMillis))
        refuse(s"the lease is live until ${job.leaseUntilMillis.getOrElse(0L)}")
      else if (leaseUntilMillis <= nowMillis) refuse("leaseUntil must be after now")
      else
        ok(job.copy(attempts = job.attempts + 1, leaseUntilMillis = Some(leaseUntilMillis)))
    }

  def release(job: JobRecord, nowMillis: Long): Either[String, JobChange] =
    change(job, nowMillis) {
      if (job.state != JobState.Running)
        refuse(s"release needs a running job; it is ${job.state.wire}")
      else ok(job.copy(state = JobState.Queued, leaseUntilMillis = None))
    }

  def succeed(job: JobRecord, nowMillis: Long, result: String): Either[String, JobChange] =
    change(job, nowMillis) {
      if (job.state != JobState.Running)
        refuse(s"succeed needs a running job; it is ${job.state.wire}")
      else if (result == null) Left("no result")
      else {
        val bytes = result.getBytes(StandardCharsets.UTF_8).length
        if (bytes > JobLimits.MaxResultBytes)
          Right(Left(JobChange.ResultTooLarge(bytes, JobLimits.MaxResultBytes)))
        else
          ok(
            job.copy(
              state = JobState.Succeeded,
              leaseUntilMillis = None,
              error = None,
              result = Some(result)
            )
          )
      }
    }

  def fail(job: JobRecord, nowMillis: Long, error: JobError): Either[String, JobChange] =
    change(job, nowMillis) {
      if (job.state != JobState.Running && job.state != JobState.Queued)
        refuse(s"fail needs a queued or running job; it is ${job.state.wire}")
      else
        checkError(error).map(e =>
          Right(job.copy(state = JobState.Failed, leaseUntilMillis = None, error = Some(e)))
        )
    }

  def setStage(job: JobRecord, nowMillis: Long, stage: String): Either[String, JobChange] =
    change(job, nowMillis) {
      if (job.state != JobState.Running)
        refuse(s"a stage is set only on a running job; it is ${job.state.wire}")
      else
        checkPattern("stage", stage, JobLimits.StagePattern).map(s =>
          Right(job.copy(stage = Some(s)))
        )
    }

  // ---- internals ----------------------------------------------------------------------------

  /** Inner Left: a refusal outcome (nothing written); inner Right: the next record. */
  private type Next = Either[String, Either[JobChange, JobRecord]]
  private def refuse(reason: String): Next = Right(Left(JobChange.Refused(reason)))
  private def ok(next: JobRecord): Next = Right(Right(next))

  private def change(job: JobRecord, nowMillis: Long)(next: => Next): Either[String, JobChange] =
    RuntimeStores.guard(for {
      _ <- Either.cond(job != null, (), "no job")
      _ <- checkJobId(job.jobId)
      _ <- Either.cond(nowMillis >= 0, (), "now must be >= 0")
      n <- next
      out <- n match {
        case Left(outcome) => Right(outcome)
        case Right(rec)    =>
          val stamped = rec.copy(updatedAtMillis = nowMillis, version = job.version + 1)
          val token = tokens()
          encode(stamped, token).map { item =>
            kv.putIfVersion(table, item, job.version) match {
              case ConditionalResult.Written         => JobChange.Written(stamped)
              case ConditionalResult.ConditionFailed =>
                if (storedToken(item.key).contains(token)) JobChange.Written(stamped)
                else JobChange.ConditionFailed
            }
          }
      }
    } yield out)

  private def storedToken(k: ItemKey): Option[String] =
    kv.get(table, k).flatMap(_.strOpt(WriteToken))
}

/** The QS4 item format. */
private[jobs] object JobItems {
  val WriteToken = "writeToken"

  /** The list attribute of accepted-but-unapplied request notes; written only when non-empty. */
  val Notes = "notes"

  def key(jobId: String): ItemKey = ItemKey(TableSpecs.jobPk(jobId), TableSpecs.JobSk)

  def checkPattern(name: String, v: String, pattern: String): Either[String, String] =
    Either.cond(v != null && v.matches(pattern), v, s"$name does not match $pattern")

  def checkJobId(id: String): Either[String, String] =
    checkPattern("jobId", id, JobLimits.JobIdPattern)

  def checkError(e: JobError): Either[String, JobError] =
    for {
      _ <- Either.cond(e != null, (), "no error")
      _ <- checkPattern("error code", e.code, JobLimits.ErrorCodePattern)
      _ <- Either.cond(
        e.message != null && e.message.length <= JobLimits.MaxErrorMessageChars,
        (),
        s"error message must be at most ${JobLimits.MaxErrorMessageChars} characters"
      )
    } yield e

  def checkRequest(r: JobRequest): Either[String, JobRequest] =
    for {
      _ <- Either.cond(r != null, (), "no request")
      _ <- Either.cond(
        r.question != null && r.question.nonEmpty &&
          r.question.length <= JobLimits.MaxQuestionChars,
        (),
        s"question must have 1..${JobLimits.MaxQuestionChars} characters"
      )
      _ <- r.windowLabel match {
        case Some(w) => checkPattern("windowLabel", w, JobLimits.WindowLabelPattern)
        case None    => Right("")
      }
      _ <- Either.cond(
        r.topic.forall(t => t != null && t.nonEmpty && t.length <= JobLimits.MaxTopicChars),
        (),
        s"topic must have 1..${JobLimits.MaxTopicChars} characters"
      )
      _ <- checkNotes(r.notes)
    } yield r

  /** At most [[JobLimits.MaxNotes]] notes, each 1..[[JobLimits.MaxNoteChars]] characters with no
    * control character.
    */
  def checkNotes(notes: List[String]): Either[String, List[String]] =
    for {
      _ <- Either.cond(notes != null, (), "notes must be a list (Nil when there are none)")
      _ <- Either.cond(
        notes.length <= JobLimits.MaxNotes,
        (),
        s"at most ${JobLimits.MaxNotes} notes"
      )
      _ <- Either.cond(
        notes.forall(n => n != null && n.nonEmpty && n.length <= JobLimits.MaxNoteChars),
        (),
        s"each note must have 1..${JobLimits.MaxNoteChars} characters"
      )
      _ <- Either.cond(
        notes.forall(n => !n.exists(Character.isISOControl)),
        (),
        "a note must not contain a control character"
      )
    } yield notes

  def validateNew(j: NewJob): Either[String, NewJob] =
    for {
      _ <- Either.cond(j != null, (), "no job")
      _ <- checkJobId(j.jobId)
      _ <- Either.cond(j.mode != null, (), "no mode")
      _ <- Either.cond(
        j.queryRunId != null && j.queryRunId.isWellFormed,
        (),
        "queryRunId is not a run ID"
      )
      _ <- checkRequest(j.request)
      _ <- checkPattern("traceId", j.traceId, JobLimits.TraceIdPattern)
      _ <- Either.cond(j.createdAtMillis >= 0, (), "createdAt must be >= 0")
      _ <- Either.cond(
        j.expiresAtSeconds > 0 && j.expiresAtSeconds * 1000L > j.createdAtMillis,
        (),
        "expiresAt must be after createdAt"
      )
    } yield j

  def encode(r: JobRecord, token: String): Either[String, Item] = {
    def s(v: String) = AttrValue.S(v)
    def n(v: Long) = AttrValue.n(v)
    val attrs = ListMap.newBuilder[String, AttrValue]
    attrs += Item.Pk -> s(TableSpecs.jobPk(r.jobId))
    attrs += Item.Sk -> s(TableSpecs.JobSk)
    attrs += "jobId" -> s(r.jobId)
    attrs += "mode" -> s(r.mode.wire)
    attrs += "state" -> s(r.state.wire)
    r.stage.foreach(v => attrs += "stage" -> s(v))
    attrs += "createdAt" -> n(r.createdAtMillis)
    attrs += "updatedAt" -> n(r.updatedAtMillis)
    attrs += "attempts" -> n(r.attempts.toLong)
    r.leaseUntilMillis.foreach(v => attrs += "leaseUntil" -> n(v))
    attrs += "queryRunId" -> s(r.queryRunId.value)
    attrs += "question" -> s(r.request.question)
    r.request.windowLabel.foreach(v => attrs += "windowLabel" -> s(v))
    r.request.topic.foreach(v => attrs += "topic" -> s(v))
    attrs += "includeEvidence" -> AttrValue.Bool(r.request.includeEvidence)
    if (r.request.notes.nonEmpty) attrs += Notes -> AttrValue.L(r.request.notes.map(s))
    attrs += "traceId" -> s(r.traceId)
    r.error.foreach { e =>
      attrs += "errorCode" -> s(e.code)
      attrs += "errorMessage" -> s(e.message)
    }
    r.result.foreach(v => attrs += "result" -> s(v))
    attrs += Item.Version -> n(r.version)
    attrs += "expiresAt" -> n(r.expiresAtSeconds)
    attrs += WriteToken -> s(token)
    val item = Item(attrs.result())
    Either.cond(
      ItemSize.of(item) <= DynamoDbLimits.MaxItemBytes,
      item,
      s"job item exceeds the DynamoDB item size limit (${ItemSize.of(item)} bytes)"
    )
  }

  def decode(i: Item): Either[String, JobRecord] = {
    def str(k: String) = i.strOpt(k).toRight(s"$k is missing")
    def num(k: String) = i.get(k) match {
      case Some(AttrValue.N(v)) => v.toLongOption.toRight(s"$k '$v' is not an integer")
      case other                => Left(s"$k is not a number: $other")
    }
    def optNum(k: String) = i.get(k) match {
      case None => Right(None)
      case _    => num(k).map(Some(_))
    }
    (for {
      id <- str("jobId")
      mode <- str("mode").flatMap(JobMode.fromWire)
      state <- str("state").flatMap(JobState.fromWire)
      created <- num("createdAt")
      updated <- num("updatedAt")
      attempts <- num("attempts")
      lease <- optNum("leaseUntil")
      _ <- Either.cond(
        lease.isDefined == (state == JobState.Running),
        (),
        "leaseUntil must be set exactly while running"
      )
      run <- str("queryRunId").flatMap(RunId.parse)
      question <- str("question")
      include <- i.get("includeEvidence") match {
        case Some(AttrValue.Bool(b)) => Right(b)
        case other                   => Left(s"includeEvidence is not a boolean: $other")
      }
      notes <- i.get(Notes) match {
        case None                  => Right(Nil)
        case Some(AttrValue.L(xs)) =>
          val strs = xs.collect { case AttrValue.S(v) => v }
          if (strs.length == xs.length) Right(strs) else Left("notes must be a list of strings")
        case Some(other) => Left(s"notes is not a list: $other")
      }
      trace <- str("traceId")
      error = for { c <- i.strOpt("errorCode"); m <- i.strOpt("errorMessage") } yield JobError(c, m)
      result = i.strOpt("result")
      _ <- Either.cond(
        result.isDefined == (state == JobState.Succeeded),
        (),
        "result must be set exactly when succeeded"
      )
      version <- num(Item.Version)
      expires <- num("expiresAt")
    } yield JobRecord(
      id,
      mode,
      state,
      i.strOpt("stage"),
      created,
      updated,
      attempts.toInt,
      lease,
      run,
      JobRequest(question, i.strOpt("windowLabel"), i.strOpt("topic"), include, notes),
      trace,
      error,
      result,
      version,
      expires
    )).left.map(e => s"malformed QuerySession job item ${i.strOpt(Item.Pk).getOrElse("?")}: $e")
  }
}
