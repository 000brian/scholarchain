package org.scholarchain.services

import java.nio.charset.StandardCharsets
import java.time.{Clock, Instant}
import java.util.concurrent.{
  Callable,
  ExecutionException,
  Semaphore,
  ThreadPoolExecutor,
  TimeUnit,
  TimeoutException
}
import java.util.concurrent.atomic.AtomicReference

import scala.util.Try
import scala.util.control.NonFatal

import org.scholarchain.core._
import org.scholarchain.core.config.ServiceSettings
import org.scholarchain.rag.runtime.RuntimeService
import org.scholarchain.storage.jobs._

/** The three circuit breakers (decision 7i): the workflow, the graph port, and evidence and audit
  * reads.
  */
final class Breakers(settings: ServiceSettings, clock: Clock) {
  private def b(name: String) =
    new CircuitBreaker(
      name,
      settings.circuitFailureThreshold,
      settings.circuitOpenMillis.toLong,
      clock
    )
  val workflow: CircuitBreaker = b("workflow")
  val graph: CircuitBreaker = b("graph")
  val reads: CircuitBreaker = b("reads")
}

/** Everything the application services share. */
final class AppContext(
    val runtime: RuntimeService,
    val stores: ServiceStores,
    val settings: ServiceSettings,
    val clock: Clock,
    val ids: IdGenerator,
    val log: ServiceLog,
    val breakers: Breakers
) {
  def now(): Long = clock.millis()
  def expiresAt(nowMillis: Long): Long = nowMillis / 1000L + settings.jobTtlSeconds.toLong
  def leaseUntil(nowMillis: Long): Long = nowMillis + settings.jobLeaseMillis.toLong

  /** A read through the reads breaker: CIRCUIT_OPEN when open, DOWNSTREAM_FAILURE on a Left. */
  def read[A](f: => Either[String, A]): Either[ApiError, A] =
    breakers.reads.call(f).left.map {
      case None    => ApiError.retryable(ErrorCode.CIRCUIT_OPEN)
      case Some(_) => ApiError.downstream
    }

  /** A job or idempotency store call (no breaker; a Left is DOWNSTREAM_FAILURE). */
  def store[A](f: => Either[String, A]): Either[ApiError, A] =
    f.left.map(_ => ApiError.downstream)
}

/** Building, storing and reading back completed results (decision 7c). */
object Results {

  /** The evidence link of one cited item (with its window when it has one). */
  def evidenceLink(item: EvidenceItem): String =
    s"/v1/evidence/${item.evidenceId.value}" +
      item.window.map(w => s"?window=${w.startYear}-${w.endYear}").getOrElse("")

  def explainLink(queryId: String): String = s"/v1/explain/$queryId"

  /** The completed result of `response`, with `evidence` (the bundle's items) when asked for. */
  def completed(
      queryId: RunId,
      response: AnswerResponse,
      includeEvidence: Boolean,
      notes: List[String]
  ): CompletedResult = {
    val items = response.bundle.map(_.items).getOrElse(Nil)
    val byId = items.map(i => i.evidenceId -> i).toMap
    val cited = response.claims.flatMap(_.evidenceIds).distinct
    CompletedResult(
      queryId.value,
      AnswerView.of(response, if (includeEvidence) Some(items) else None),
      ResultLinks(
        explainLink(queryId.value),
        cited.map(id => byId.get(id).map(evidenceLink).getOrElse(s"/v1/evidence/${id.value}"))
      ),
      notes
    )
  }

  /** The stored form: the completed result without evidence items. */
  def stored(r: CompletedResult): String =
    Json.encode(r.copy(answer = r.answer.copy(evidence = None)))

  def bytes(s: String): Int = s.getBytes(StandardCharsets.UTF_8).length

  def decode(stored: String): Either[String, CompletedResult] =
    Try(upickle.default.read[CompletedResult](stored)).toEither.left.map(_ =>
      "stored result is malformed"
    )

  /** A stored result with its evidence read back (`EvidenceStore.getBundle`) when asked for, passed
    * through [[ResponseGuard.result]].
    */
  def withEvidence(
      app: AppContext,
      stored: String,
      includeEvidence: Boolean
  ): Either[ApiError, CompletedResult] =
    (for {
      r <- decode(stored).left.map(_ => ApiError.internal)
      out <-
        if (!includeEvidence) Right(r)
        else
          r.answer.bundleId match {
            case None    => Right(r.copy(answer = r.answer.copy(evidence = Some(Nil))))
            case Some(b) =>
              app.read(app.runtime.stores.evidence.getBundle(b)).flatMap {
                case Some(bundle) =>
                  Right(r.copy(answer = r.answer.copy(evidence = Some(bundle.items))))
                case None => Left(ApiError.downstream)
              }
          }
    } yield out).map(ResponseGuard.result) // C-02, C-03: also results and bundles stored earlier
}

/** Runs one workflow for a query and finishes its job, if it has one. Shared by the sync path and
  * the worker, so both produce the same completed result for the same request.
  */
final class WorkflowRunner(app: AppContext) {

  sealed trait Outcome
  object Outcome {
    final case class Done(result: CompletedResult, stored: String) extends Outcome

    /** rag returned a Left for a validated request: a store or model failure (retryable). */
    case object Failed extends Outcome

    /** The result exceeds [[ServiceLimits.MaxResultBytes]] (not retryable). */
    case object TooLarge extends Outcome
  }

  /** Runs `query`. `job` holds the latest record of the job (each successful stage write replaces
    * it, since a stage write increments the job version); stage writes that fail are logged and
    * ignored. Logs one line per stage with the trace ID, job ID and query ID.
    */
  def run(
      query: ValidatedQuery,
      queryId: RunId,
      traceId: String,
      job: Option[AtomicReference[JobRecord]]
  ): Outcome = {
    val jobId = job.map(_.get.jobId)
    val listener: String => Unit = stage => {
      app.log.stage(traceId, jobId, queryId.value, stage)
      job.foreach { h =>
        val cur = h.get
        app.stores.jobs.setStage(cur, app.now(), stage) match {
          case Right(JobChange.Written(next)) => h.compareAndSet(cur, next); ()
          case other                          =>
            app.log.event(
              "stage-write-ignored",
              Some(traceId),
              List(
                "jobId" -> ujson.Str(cur.jobId),
                "stage" -> ujson.Str(stage),
                "cause" -> ujson.Str(LogCause.ofChange(other))
              )
            )
        }
      }
    }
    app.runtime.workflow.run(query.toQueryRequest, listener) match {
      case Left(e) =>
        // S-4: a fixed cause code (and rag's exception class name), never rag's text
        app.log.event(
          "workflow-failed",
          Some(traceId),
          ("queryId" -> ujson.Str(queryId.value)) :: LogCause.ofWorkflow(e)
        )
        Outcome.Failed
      case Right(r) =>
        val result = Results.completed(r.queryRunId, r.response, query.includeEvidence, query.notes)
        val stored = Results.stored(result)
        if (Results.bytes(stored) > ServiceLimits.MaxResultBytes) Outcome.TooLarge
        else Outcome.Done(result, stored)
    }
  }
}

/** `POST /v1/query` (decisions 7c, 7d, 7e). */
final class QueryService(app: AppContext, pool: ThreadPoolExecutor) {
  private val settings = app.settings
  private val permits = new Semaphore(settings.maxConcurrentQueries)
  private val runner = new WorkflowRunner(app)
  private val parser = new QueryRequestParser(
    app.runtime.config.graph.windows,
    app.runtime.context.runtime,
    app.runtime.context.vectorTopK
  )

  /** Query permits currently free (tests: a timed-out workflow keeps its permit). */
  def freePermits: Int = permits.availablePermits()

  def submit(ctx: RequestCtx): Either[ApiError, ApiResponse] =
    for {
      // S-10: like every GET route, an unknown query parameter is 400 (POST takes none)
      _ <- Either.cond(
        ctx.query.isEmpty,
        (),
        ApiError.invalid(ctx.query.keys.toList.sorted.map(_ -> "unknown query parameter"))
      )
      key <- ctx.idempotencyKey match {
        case None                                         => Right(None)
        case Some(k) if RequestIds.validIdempotencyKey(k) => Right(Some(k))
        case Some(_)                                      =>
          Left(ApiError.invalid("Idempotency-Key", "must be 1..128 characters [A-Za-z0-9._:-]"))
      }
      vq <- parser.parse(ctx.body.getOrElse(""))
      _ <- app.runtime.workflow
        .validate(vq.toQueryRequest)
        .left
        .map(_ => ApiError.invalid("question", "the runtime rejected this request"))
      queryId <- app.runtime.queryRunId(vq.toQueryRequest).left.map(_ => ApiError.internal)
      out <- vq.mode match {
        case JobMode.Sync =>
          key.fold(syncWithoutKey(ctx, vq, queryId))(syncWithKey(ctx, vq, queryId, _))
        case JobMode.Async => async(ctx, vq, queryId, key)
      }
    } yield out

  // ---- sync -----------------------------------------------------------------------------------

  /** A permit and a workflow-breaker slot, or 503. */
  private def admit(): Either[ApiError, CircuitBreaker.Slot] =
    if (!permits.tryAcquire()) Left(ApiError.retryable(ErrorCode.OVERLOADED))
    else
      app.breakers.workflow.acquire() match {
        case None =>
          permits.release(); Left(ApiError.retryable(ErrorCode.CIRCUIT_OPEN))
        case Some(slot) => Right(slot)
      }

  /** Gives back this request's permit and breaker slot (S-9: only its own trial slot). */
  private def unadmit(slot: CircuitBreaker.Slot): Unit = {
    app.breakers.workflow.cancel(slot); permits.release()
  }

  private def syncWithoutKey(
      ctx: RequestCtx,
      vq: ValidatedQuery,
      queryId: RunId
  ): Either[ApiError, ApiResponse] =
    admit().flatMap(slot => runSync(ctx, vq, queryId, None, slot))

  private def syncWithKey(
      ctx: RequestCtx,
      vq: ValidatedQuery,
      queryId: RunId,
      key: String
  ): Either[ApiError, ApiResponse] = {
    val scope = ctx.caller.wire
    app.store(app.stores.idempotency.get(scope, key, app.now())).flatMap {
      case Some(rec) => follow(rec, vq)
      case None      =>
        admit().flatMap { slot =>
          val claimed: Either[ApiError, Either[IdempotencyRecord, JobRecord]] = try {
            val now = app.now()
            val exp = app.expiresAt(now)
            val jobId = "job-" + app.ids.uuid()
            for {
              created <- createJob(
                NewJob(jobId, JobMode.Sync, queryId, vq.toJobRequest, ctx.traceId, now, exp)
              )
              running <- app
                .store(app.stores.jobs.claim(created, now, app.leaseUntil(now)))
                .flatMap {
                  case JobChange.Written(j) => Right(j)
                  case _                    => Left(ApiError.internal)
                }
              c <- app.store(
                app.stores.idempotency.claim(scope, key, vq.fingerprint, jobId, exp, now)
              ) match {
                case Left(e) =>
                  failJob(running, JobErrorCodes.WorkflowFailed); Left(e)
                case Right(IdempotencyClaim.Claimed(_))    => Right(Right(running))
                case Right(IdempotencyClaim.Existing(rec)) =>
                  failJob(running, JobErrorCodes.Superseded); Right(Left(rec))
              }
            } yield c
          } catch {
            case NonFatal(e) => unadmit(slot); throw e
          }
          claimed match {
            case Left(e)           => unadmit(slot); Left(e)
            case Right(Left(rec))  => unadmit(slot); follow(rec, vq)
            case Right(Right(job)) =>
              runSync(ctx, vq, queryId, Some(new AtomicReference(job)), slot)
          }
        }
    }
  }

  /** Runs the workflow on the query pool (the caller already holds a permit and a breaker slot) and
    * waits at most `syncDeadlineMillis`. The workflow is never interrupted: it keeps its permit
    * until it finishes, and with a job it writes its result there as usual.
    */
  private def runSync(
      ctx: RequestCtx,
      vq: ValidatedQuery,
      queryId: RunId,
      job: Option[AtomicReference[JobRecord]],
      slot: CircuitBreaker.Slot
  ): Either[ApiError, ApiResponse] = {
    val task: Callable[Either[ApiError, CompletedResult]] = () =>
      try finish(runner.run(vq, queryId, ctx.traceId, job), job)
      finally permits.release()
    val future =
      try pool.submit(task)
      catch {
        case NonFatal(_) =>
          unadmit(slot)
          job.foreach(h => failJob(h.get, JobErrorCodes.WorkflowFailed))
          return Left(ApiError.retryable(ErrorCode.OVERLOADED))
      }
    try
      future.get(settings.syncDeadlineMillis.toLong, TimeUnit.MILLISECONDS).map { r =>
        ApiResponse.json(200, r)
      }
    catch {
      case _: TimeoutException =>
        app.log.event(
          "sync-deadline-passed",
          Some(ctx.traceId),
          List("queryId" -> ujson.Str(queryId.value)) ++ job
            .map(h => "jobId" -> ujson.Str(h.get.jobId))
            .toList
        )
        Left(ApiError(ErrorCode.WORKFLOW_TIMEOUT))
      case e: ExecutionException =>
        app.log.failure(Some(ctx.traceId), "query", Option(e.getCause).getOrElse(e))
        Left(ApiError.internal)
    }
  }

  /** Records the breaker outcome and finishes the job. */
  private def finish(
      outcome: runner.Outcome,
      job: Option[AtomicReference[JobRecord]]
  ): Either[ApiError, CompletedResult] =
    outcome match {
      case runner.Outcome.Failed =>
        app.breakers.workflow.recordFailure()
        job.foreach(h => failJob(h.get, JobErrorCodes.WorkflowFailed))
        Left(ApiError.downstream)
      case runner.Outcome.TooLarge =>
        app.breakers.workflow.recordSuccess()
        job.foreach(h => failJob(h.get, JobErrorCodes.ResultTooLarge))
        Left(ApiError(ErrorCode.RESULT_TOO_LARGE))
      case runner.Outcome.Done(result, stored) =>
        app.breakers.workflow.recordSuccess()
        job match {
          case None    => Right(result)
          case Some(h) =>
            app.stores.jobs.succeed(h.get, app.now(), stored) match {
              case Right(JobChange.Written(_))           => Right(result)
              case Right(JobChange.ResultTooLarge(_, _)) =>
                failJob(h.get, JobErrorCodes.ResultTooLarge)
                Left(ApiError(ErrorCode.RESULT_TOO_LARGE))
              case other => succeedAgain(h.get, stored, result, other)
            }
        }
    }

  /** S-3: the final `succeed` of a keyed sync job returned ConditionFailed, Refused or Left. The
    * job is read again: when it is still `running` under this request's lease (same lease end,
    * lease live), `succeed` is retried once on the fresh version; when the fresh record already
    * holds this result as `succeeded` (a write that was applied but answered Left), the result
    * stands. Anything else is 502 DOWNSTREAM_FAILURE, so the client never gets a 200 whose key
    * later answers differently.
    */
  private def succeedAgain(
      mine: JobRecord,
      stored: String,
      result: CompletedResult,
      first: Either[String, JobChange]
  ): Either[ApiError, CompletedResult] = {
    val now = app.now()
    def lost(cause: String): Either[ApiError, CompletedResult] = {
      app.log.event(
        "result-write-lost",
        Some(mine.traceId),
        List("jobId" -> ujson.Str(mine.jobId), "cause" -> ujson.Str(cause))
      )
      Left(ApiError.downstream)
    }
    app.stores.jobs.get(mine.jobId, now) match {
      case Right(Some(j))
          if j.state == JobState.Running && j.leaseUntilMillis == mine.leaseUntilMillis &&
            j.leaseLive(now) =>
        app.stores.jobs.succeed(j, now, stored) match {
          case Right(JobChange.Written(_)) => Right(result)
          case again                       => lost(LogCause.ofChange(again))
        }
      case Right(Some(j)) if j.state == JobState.Succeeded && j.result.contains(stored) =>
        Right(result)
      case Left(_) => lost(LogCause.StoreFailure)
      case _       => lost(LogCause.ofChange(first))
    }
  }

  // ---- async ----------------------------------------------------------------------------------

  private def async(
      ctx: RequestCtx,
      vq: ValidatedQuery,
      queryId: RunId,
      key: Option[String]
  ): Either[ApiError, ApiResponse] = {
    val scope = ctx.caller.wire
    val existing = key match {
      case None    => Right(None)
      case Some(k) => app.store(app.stores.idempotency.get(scope, k, app.now()))
    }
    existing.flatMap {
      case Some(rec) => follow(rec, vq)
      case None      =>
        val now = app.now()
        val exp = app.expiresAt(now)
        val jobId = "job-" + app.ids.uuid()
        for {
          job <- createJob(
            NewJob(jobId, JobMode.Async, queryId, vq.toJobRequest, ctx.traceId, now, exp)
          )
          // enqueue before the claim: a claimed key never points at a job no worker will see
          _ <- app.store(app.stores.enqueueJob(jobId)).left.map { e =>
            failJob(job, JobErrorCodes.WorkflowFailed); e
          }
          out <- key match {
            case None    => Right(ack(queryId, jobId, JobState.Queued, replayed = false))
            case Some(k) =>
              app
                .store(app.stores.idempotency.claim(scope, k, vq.fingerprint, jobId, exp, now))
                .flatMap {
                  case IdempotencyClaim.Claimed(_) =>
                    Right(ack(queryId, jobId, JobState.Queued, replayed = false))
                  case IdempotencyClaim.Existing(rec) =>
                    // a concurrent request won: supersede this job if no worker has claimed it yet
                    app.stores.jobs.get(jobId, app.now()) match {
                      case Right(Some(j)) if j.state == JobState.Queued =>
                        failJob(j, JobErrorCodes.Superseded)
                      case _ => ()
                    }
                    follow(rec, vq)
                }
          }
        } yield out
    }
  }

  private def ack(
      queryId: RunId,
      jobId: String,
      state: JobState,
      replayed: Boolean
  ): ApiResponse = {
    val url = s"/v1/jobs/$jobId"
    ApiResponse.json(
      202,
      QueryAcknowledgment(queryId.value, jobId, JobService.stateWire(state), url),
      List("Location" -> url) ++ (if (replayed) List("Idempotent-Replayed" -> "true") else Nil)
    )
  }

  // ---- idempotency ----------------------------------------------------------------------------

  /** Answers a request whose key already has a record (decision 7e). */
  private def follow(
      rec: IdempotencyRecord,
      vq: ValidatedQuery,
      round: Int = 0
  ): Either[ApiError, ApiResponse] =
    if (rec.fingerprint != vq.fingerprint) Left(ApiError(ErrorCode.IDEMPOTENCY_CONFLICT))
    else
      app.store(app.stores.jobs.get(rec.jobId, app.now())).flatMap {
        case None      => Left(ApiError.retryable(ErrorCode.IDEMPOTENCY_IN_PROGRESS))
        case Some(job) =>
          val now = app.now()
          job.state match {
            case JobState.Succeeded =>
              val stored = job.result.getOrElse("")
              job.mode match {
                case JobMode.Sync =>
                  Results.withEvidence(app, stored, job.request.includeEvidence).map { r =>
                    ApiResponse.json(200, r, List("Idempotent-Replayed" -> "true"))
                  }
                case JobMode.Async =>
                  Right(ack(job.queryRunId, job.jobId, job.state, replayed = true))
              }
            case JobState.Failed => Left(JobService.failed(job))
            case JobState.Queued => Left(ApiError.retryable(ErrorCode.IDEMPOTENCY_IN_PROGRESS))
            case JobState.Running if job.leaseLive(now) =>
              Left(ApiError.retryable(ErrorCode.IDEMPOTENCY_IN_PROGRESS))
            case JobState.Running if job.mode == JobMode.Async =>
              // the queue brings the message back and a worker reclaims it (decision 7f)
              Left(ApiError.retryable(ErrorCode.IDEMPOTENCY_IN_PROGRESS))
            case JobState.Running =>
              // a sync job whose process died
              app
                .store(
                  app.stores.jobs.fail(job, now, JobErrorCodes.error(JobErrorCodes.LeaseExpired))
                )
                .flatMap {
                  case JobChange.Written(f) => Left(JobService.failed(f))
                  case _ if round < 3       => follow(rec, vq, round + 1)
                  case _ => Left(ApiError.retryable(ErrorCode.IDEMPOTENCY_IN_PROGRESS))
                }
          }
      }

  private def createJob(j: NewJob): Either[ApiError, JobRecord] =
    app.store(app.stores.jobs.create(j)).flatMap {
      case JobChange.Written(r) => Right(r)
      case _                    => Left(ApiError.internal)
    }

  private def failJob(job: JobRecord, code: String): Unit =
    app.stores.jobs.fail(job, app.now(), JobErrorCodes.error(code)) match {
      case Right(JobChange.Written(_)) => ()
      case other                       =>
        app.log.event(
          "job-fail-ignored",
          Some(job.traceId),
          List(
            "jobId" -> ujson.Str(job.jobId),
            "jobError" -> ujson.Str(code),
            "cause" -> ujson.Str(LogCause.ofChange(other))
          )
        )
    }
}

/** `GET /v1/jobs/{jobId}` and `GET /v1/jobs/{jobId}/result` (decision 7f). */
final class JobService(app: AppContext) {
  private val JobIdShape = JobLimits.JobIdPattern.r

  private def load(jobId: String): Either[ApiError, JobRecord] =
    if (!JobIdShape.matches(jobId)) Left(ApiError.invalid("jobId", "is not a job ID"))
    else
      app.store(app.stores.jobs.get(jobId, app.now())).flatMap {
        case Some(j) => Right(j)
        case None    => Left(ApiError(ErrorCode.JOB_NOT_FOUND))
      }

  def status(jobId: String): Either[ApiError, ApiResponse] =
    load(jobId).map(j => ApiResponse.json(200, JobService.view(j)))

  def result(jobId: String): Either[ApiError, ApiResponse] =
    load(jobId).flatMap { j =>
      j.state match {
        case JobState.Succeeded =>
          Results
            .withEvidence(app, j.result.getOrElse(""), j.request.includeEvidence)
            .map(r => ApiResponse.json(200, r))
        case JobState.Failed => Left(JobService.failed(j))
        case _ => Left(ApiError.retryable(ErrorCode.JOB_NOT_READY, ujson.Obj("jobId" -> j.jobId)))
      }
    }
}

object JobService {
  def stateWire(s: JobState): String = s.wire.toUpperCase(java.util.Locale.ROOT)

  def view(j: JobRecord): JobStatusView =
    JobStatusView(
      j.jobId,
      j.queryRunId.value,
      j.mode.wire,
      stateWire(j.state),
      j.stage,
      Instant.ofEpochMilli(j.createdAtMillis).toString,
      Instant.ofEpochMilli(j.updatedAtMillis).toString,
      j.attempts,
      if (j.state == JobState.Succeeded) Some(s"/v1/jobs/${j.jobId}/result") else None,
      j.error.map(e => JobErrorView(e.code, e.message))
    )

  /** 409 JOB_FAILED with the job's error code in `details`. */
  def failed(j: JobRecord): ApiError =
    ApiError(
      ErrorCode.JOB_FAILED,
      ujson.Obj(
        "jobId" -> j.jobId,
        "jobError" -> j.error.map(e => ujson.Str(e.code)).getOrElse(ujson.Null)
      )
    )
}
