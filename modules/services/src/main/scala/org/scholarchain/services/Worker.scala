package org.scholarchain.services

import java.util.concurrent.{ThreadPoolExecutor, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

import scala.util.control.NonFatal

import org.scholarchain.storage.jobs._
import org.scholarchain.storage.ports.ReceivedWork

/** What one [[Worker.processOne]] call did (tests assert on it). */
sealed trait WorkerOutcome
object WorkerOutcome {

  /** The queue had no visible message. */
  case object Idle extends WorkerOutcome

  /** The message was acked without running: the job was already succeeded or failed, unknown or
    * expired, or the message was not a job message.
    */
  case object AckedWithoutRun extends WorkerOutcome

  /** The message was left alone (neither acked nor released): a live lease, a lost claim, a store
    * failure, or a final write that lost the version race (its result discarded).
    */
  final case class LeftAlone(reason: String) extends WorkerOutcome

  /** The job ran and was stored `succeeded`; the message was acked. */
  case object Succeeded extends WorkerOutcome

  /** The job was stored `failed` with `code`; the message was acked. */
  final case class Failed(code: String) extends WorkerOutcome

  /** A retryable failure: the job went back to `queued` and the message was released. */
  case object Released extends WorkerOutcome
}

/** The async worker (decision 7f): at-least-once queue, at most one live execution per job.
  *
  *   - Receive one message, read its job. `succeeded`/`failed` (or unknown): ack. `running` with a
  *     live lease: leave the message (the visibility timeout, longer than the lease, brings it
  *     back). `queued`: claim; `running` with an expired lease: reclaim. A claim or reclaim that
  *     loses (`ConditionFailed`): leave the message.
  *   - `attempts` already at `jobMaxAttempts`: mark `failed` `ATTEMPTS_EXHAUSTED` and ack.
  *   - Run the workflow; each stage updates the job's stage and writes a log line.
  *   - Success: write `succeeded` with the result in one conditional put, then ack. Too large:
  *     `failed` `RESULT_TOO_LARGE`, then ack. A final write that returns `ConditionFailed`: discard
  *     the result and leave the message.
  *   - A store or model failure: `running → queued`, then release (attempts below the limit), else
  *     `failed` `ATTEMPTS_EXHAUSTED` and ack. A request rag rejects: `failed` `WORKFLOW_FAILED`,
  *     ack.
  *
  * Two executions of one job happen only after a lease expires; both produce the same query run ID
  * (the bundle write is put-if-absent; the audit is appended twice). Execution is at least once,
  * not exactly once. The worker logs with the trace ID stored on the job.
  */
final class Worker(app: AppContext) {
  private val runner = new WorkflowRunner(app)
  private val settings = app.settings

  /** Receives and processes at most one message. Never throws for a store failure. */
  def processOne(): WorkerOutcome = {
    val received =
      try app.stores.jobQueue.receive(1)
      catch {
        case NonFatal(e) =>
          app.log.failure(None, "worker.receive", e)
          return WorkerOutcome.LeftAlone("queue receive failed")
      }
    received.headOption match {
      case None    => WorkerOutcome.Idle
      case Some(w) => process(w)
    }
  }

  private def ack(w: ReceivedWork): Unit = app.stores.jobQueue.ack(w)

  private def process(w: ReceivedWork): WorkerOutcome =
    JobMessages.jobId(w) match {
      case scala.util.Left(_) =>
        app.log.event("worker-foreign-message", None, Nil)
        ack(w); WorkerOutcome.AckedWithoutRun
      case scala.util.Right(jobId) =>
        app.stores.jobs.get(jobId, app.now()) match {
          case scala.util.Left(e) =>
            app.log.event("worker-store-failure", None, List("jobId" -> ujson.Str(jobId)), Some(e))
            WorkerOutcome.LeftAlone("job read failed")
          case scala.util.Right(None) =>
            ack(w); WorkerOutcome.AckedWithoutRun
          case scala.util.Right(Some(job)) => handle(w, job)
        }
    }

  private def handle(w: ReceivedWork, job: JobRecord): WorkerOutcome = {
    val now = app.now()
    job.state match {
      case JobState.Succeeded | JobState.Failed =>
        ack(w); WorkerOutcome.AckedWithoutRun
      case JobState.Running if job.leaseLive(now)       => WorkerOutcome.LeftAlone("lease is live")
      case _ if job.attempts >= settings.jobMaxAttempts =>
        terminal(w, job, JobErrorCodes.AttemptsExhausted)
      case state =>
        val claimed =
          if (state == JobState.Queued) app.stores.jobs.claim(job, now, app.leaseUntil(now))
          else app.stores.jobs.reclaim(job, now, app.leaseUntil(now))
        claimed match {
          case scala.util.Right(JobChange.Written(running)) => execute(w, running)
          case other                                        =>
            WorkerOutcome.LeftAlone(
              s"claim not won: ${other.fold(_ => "store failure", _.toString)}"
            )
        }
    }
  }

  /** Marks the job failed with `code` and acks; a write that does not happen leaves the message. */
  private def terminal(w: ReceivedWork, job: JobRecord, code: String): WorkerOutcome =
    app.stores.jobs.fail(job, app.now(), JobErrorCodes.error(code)) match {
      case scala.util.Right(JobChange.Written(_)) =>
        app.log.event(
          "job-failed",
          Some(job.traceId),
          List("jobId" -> ujson.Str(job.jobId), "jobError" -> ujson.Str(code))
        )
        ack(w); WorkerOutcome.Failed(code)
      case other =>
        WorkerOutcome.LeftAlone(
          s"fail not written: ${other.fold(_ => "store failure", _.toString)}"
        )
    }

  private def execute(w: ReceivedWork, running: JobRecord): WorkerOutcome = {
    val query = ValidatedQuery.ofJob(running.request, running.mode)
    app.log.event(
      "job-started",
      Some(running.traceId),
      List(
        "jobId" -> ujson.Str(running.jobId),
        "queryId" -> ujson.Str(running.queryRunId.value),
        "attempts" -> ujson.Num(running.attempts)
      )
    )
    if (app.runtime.workflow.validate(query.toQueryRequest).isLeft)
      return terminal(w, running, JobErrorCodes.WorkflowFailed)
    val holder = new AtomicReference(running)
    runner.run(query, running.queryRunId, running.traceId, Some(holder)) match {
      case runner.Outcome.Done(_, stored) =>
        app.stores.jobs.succeed(holder.get, app.now(), stored) match {
          case scala.util.Right(JobChange.Written(_)) =>
            app.log.event(
              "job-succeeded",
              Some(running.traceId),
              List("jobId" -> ujson.Str(running.jobId))
            )
            ack(w); WorkerOutcome.Succeeded
          case scala.util.Right(JobChange.ResultTooLarge(_, _)) =>
            terminal(w, holder.get, JobErrorCodes.ResultTooLarge)
          case other =>
            app.log.event(
              "job-result-discarded",
              Some(running.traceId),
              List("jobId" -> ujson.Str(running.jobId)),
              Some(other.fold(identity, _.toString))
            )
            WorkerOutcome.LeftAlone("final write lost; result discarded")
        }
      case runner.Outcome.TooLarge => terminal(w, holder.get, JobErrorCodes.ResultTooLarge)
      case runner.Outcome.Failed   =>
        val cur = holder.get
        if (cur.attempts < settings.jobMaxAttempts)
          app.stores.jobs.release(cur, app.now()) match {
            case scala.util.Right(JobChange.Written(_)) =>
              app.stores.jobQueue.release(w); WorkerOutcome.Released
            case other =>
              WorkerOutcome.LeftAlone(
                s"release not written: ${other.fold(_ => "store failure", _.toString)}"
              )
          }
        else terminal(w, cur, JobErrorCodes.AttemptsExhausted)
    }
  }
}

/** Runs [[Worker.processOne]] on `threads` threads of `pool` until [[stop]]. Idle threads wait
  * [[ServiceLimits.WorkerIdleMillis]] between polls. A thrown exception is logged and the loop goes
  * on.
  */
final class WorkerLoop(worker: Worker, pool: ThreadPoolExecutor, threads: Int, log: ServiceLog) {
  private val running = new AtomicBoolean(false)
  private val lock = new Object

  def start(): Unit =
    if (running.compareAndSet(false, true))
      (1 to threads).foreach(_ => pool.execute(() => loop()))

  def stop(): Unit = {
    running.set(false)
    lock.synchronized(lock.notifyAll())
  }

  def isRunning: Boolean = running.get

  private def loop(): Unit =
    while (running.get && !Thread.currentThread().isInterrupted) {
      val out =
        try worker.processOne()
        catch {
          case NonFatal(e) =>
            log.failure(None, "worker", e); WorkerOutcome.LeftAlone("exception")
        }
      if (out == WorkerOutcome.Idle || out.isInstanceOf[WorkerOutcome.LeftAlone])
        try lock.synchronized(if (running.get) lock.wait(ServiceLimits.WorkerIdleMillis))
        catch { case _: InterruptedException => Thread.currentThread().interrupt() }
    }

  /** Used by tests to wait without sleeping in a loop elsewhere. */
  def awaitStopped(pool: ThreadPoolExecutor, millis: Long): Boolean =
    pool.awaitTermination(millis, TimeUnit.MILLISECONDS)
}
