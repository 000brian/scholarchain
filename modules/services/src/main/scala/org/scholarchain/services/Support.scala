package org.scholarchain.services

import java.time.{Clock, Instant}
import java.util.UUID
import java.util.concurrent.{
  ArrayBlockingQueue,
  ConcurrentLinkedQueue,
  RejectedExecutionHandler,
  ThreadFactory,
  ThreadPoolExecutor,
  TimeUnit
}
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern

import scala.jdk.CollectionConverters._

import org.slf4j.LoggerFactory

import org.scholarchain.rag.runtime.QueryWorkflow

/** Source of operational IDs: job IDs (`job-` + UUID), request IDs and trace IDs. Tests pass a
  * fixed generator so every ID in a response is predictable. Must be thread-safe.
  */
trait IdGenerator {
  def uuid(): String

  /** 32 lowercase hex characters (a W3C trace ID). */
  def traceId(): String
  def requestId(): String = uuid()
}

object IdGenerator {
  val random: IdGenerator = new IdGenerator {
    def uuid(): String = UUID.randomUUID().toString
    def traceId(): String = UUID.randomUUID().toString.replace("-", "")
  }
}

/** Header rules for request and trace IDs (decision 7i). */
object RequestIds {
  private val RequestId = Pattern.compile(ServiceLimits.RequestIdPattern)
  private val TraceParent =
    Pattern.compile("([0-9a-f]{2})-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})")
  private val IdemKey = Pattern.compile(ServiceLimits.IdempotencyKeyPattern)

  def validRequestId(s: String): Option[String] =
    Option(s).filter(x => RequestId.matcher(x).matches())

  /** The trace ID of a valid W3C `traceparent` (version not `ff`, trace and parent IDs not all
    * zeros), else None.
    */
  def traceIdOf(traceparent: String): Option[String] =
    Option(traceparent).map(_.trim).flatMap { t =>
      val m = TraceParent.matcher(t)
      if (m.matches() && m.group(1) != "ff" && m.group(2) != "0" * 32 && m.group(3) != "0" * 16)
        Some(m.group(2))
      else None
    }

  def validIdempotencyKey(s: String): Boolean = s != null && IdemKey.matcher(s).matches()
}

/** One circuit breaker per downstream (decision 7i): after `threshold` consecutive failures it
  * opens for `openMillis` (callers answer 503 `CIRCUIT_OPEN`), then lets one trial call through; a
  * success closes it, a failure opens it again. Only downstream failures are recorded (a 4xx never
  * reaches it). Time comes from the injected clock. Thread-safe.
  *
  * Phase 16, FECD-1 (breaker side, step D's B1): every state change (closed to open, open to
  * closed, a failed trial opening it again) starts a new generation, and each
  * [[CircuitBreaker.Slot]] carries the generation it was taken in. An outcome reported with a slot
  * of an earlier generation is stale (the call was admitted before the breaker last changed state,
  * typically one of the calls in flight when a concurrent burst opened it) and changes nothing: it
  * does not extend the open period, does not free or fail the half-open trial, does not close an
  * open breaker and is not counted toward the next opening. Before this, each in-flight failure
  * that returned after the opening restarted the open period and cleared the trial flag (so a
  * second trial could run beside the first), and an in-flight success closed an open breaker
  * without any trial. The threshold and the open period are unchanged.
  *
  * @param onChange
  *   called with the breaker name and the new state (`open`, `closed`) after each state change,
  *   outside the lock (the server logs it; tests record it)
  */
final class CircuitBreaker(
    val name: String,
    threshold: Int,
    openMillis: Long,
    clock: Clock,
    onChange: (String, String) => Unit = (_, _) => ()
) {
  require(threshold >= 1 && openMillis >= 1)
  private var failures = 0
  private var openUntil: Option[Long] = None
  private var trialInFlight = false
  private var generation = 0L
  private var openings = 0L
  private var stale = 0L

  /** A slot when a call may proceed (closed, or the one trial after the open period); `trial` tells
    * whether this slot is the half-open trial.
    */
  def acquire(): Option[CircuitBreaker.Slot] = synchronized {
    openUntil match {
      case None                          => Some(new CircuitBreaker.Slot(false, generation))
      case Some(t) if clock.millis() < t => None
      case Some(_) if trialInFlight      => None
      case Some(_)                       =>
        trialInFlight = true; Some(new CircuitBreaker.Slot(true, generation))
    }
  }

  /** True when a call may proceed (see [[acquire]]). The slot is not kept, so its outcome can only
    * be reported with the slot-less [[recordSuccess()]] and [[recordFailure()]].
    */
  def tryAcquire(): Boolean = acquire().nonEmpty

  /** Gives back a slot taken by [[acquire]] without an outcome (the call was never made, or its
    * outcome says nothing about availability). Only the holder of the trial slot of the current
    * generation frees the trial (S-9): a slot taken while the breaker was closed never frees
    * another request's trial.
    */
  def cancel(slot: CircuitBreaker.Slot): Unit = synchronized {
    if (slot.trial && slot.generation == generation) trialInFlight = false
  }

  /** The call of `slot` succeeded. Stale slots change nothing (see the class comment). */
  def recordSuccess(slot: CircuitBreaker.Slot): Unit = {
    val changed = synchronized {
      if (slot.generation != generation) { stale += 1; None }
      else succeeded()
    }
    changed.foreach(onChange(name, _))
  }

  /** The call of `slot` failed. Stale slots change nothing (see the class comment). */
  def recordFailure(slot: CircuitBreaker.Slot): Unit = {
    val changed = synchronized {
      if (slot.generation != generation) { stale += 1; None }
      else failed()
    }
    changed.foreach(onChange(name, _))
  }

  /** A success of a caller that holds no slot (counted in the current generation). */
  def recordSuccess(): Unit = synchronized(succeeded()).foreach(onChange(name, _))

  /** A failure of a caller that holds no slot (counted in the current generation). */
  def recordFailure(): Unit = synchronized(failed()).foreach(onChange(name, _))

  private def succeeded(): Option[String] = {
    val wasOpen = openUntil.nonEmpty
    failures = 0; openUntil = None; trialInFlight = false
    if (wasOpen) { generation += 1; Some("closed") }
    else None
  }

  private def failed(): Option[String] =
    if (openUntil.nonEmpty) {
      // the trial failed: open again
      openUntil = Some(clock.millis() + openMillis); trialInFlight = false
      generation += 1; openings += 1
      Some("open")
    } else {
      failures += 1
      if (failures >= threshold) {
        openUntil = Some(clock.millis() + openMillis)
        failures = 0; generation += 1; openings += 1
        Some("open")
      } else None
    }

  /** `closed`, `open` or `half-open` (tests and logs). */
  def state: String = synchronized {
    openUntil match {
      case None                          => "closed"
      case Some(t) if clock.millis() < t => "open"
      case Some(_)                       => "half-open"
    }
  }

  /** How often the breaker opened (a failed trial counts) since it was built. */
  def openCount: Long = synchronized(openings)

  /** Outcomes ignored because their slot was stale (see the class comment). */
  def staleOutcomes: Long = synchronized(stale)

  /** Runs `f` through the breaker: Left(None) when open; Left(Some(e)) on a downstream failure. */
  def call[A](f: => Either[String, A]): Either[Option[String], A] =
    acquire() match {
      case None       => Left(None)
      case Some(slot) =>
        val r =
          try f
          catch {
            case e: Throwable =>
              recordFailure(slot); throw e
          }
        r match {
          case Right(a) => recordSuccess(slot); Right(a)
          case Left(e)  => recordFailure(slot); Left(Some(e))
        }
    }
}

object CircuitBreaker {

  /** A call slot of [[CircuitBreaker.acquire]]; `trial` is true for the half-open trial. The
    * generation the slot was taken in is internal bookkeeping: two slots are equal when their
    * `trial` flags are.
    */
  final class Slot private[services] (val trial: Boolean, private[services] val generation: Long) {
    override def equals(o: Any): Boolean = o match {
      case s: Slot => s.trial == trial
      case _       => false
    }
    override def hashCode: Int = trial.hashCode
    override def toString: String = s"Slot(trial = $trial)"
  }

  object Slot {

    /** A slot of generation 0 (tests compare slots by their `trial` flag). */
    def apply(trial: Boolean): Slot = new Slot(trial, 0L)
  }
}

/** One token bucket per process for authenticated routes (`service.requestsPerMinute`). The bucket
  * holds at most `perMinute` tokens and refills continuously. Time from the injected clock.
  * Thread-safe.
  */
final class TokenBucket(perMinute: Int, clock: Clock) {
  require(perMinute > 0)
  private val capacity = perMinute.toDouble
  private val perMilli = perMinute.toDouble / 60000.0
  private var tokens = capacity
  private var last = clock.millis()

  /** Right(()) when a token was taken, else Left(seconds until the next token). */
  def take(): Either[Int, Unit] = synchronized {
    val now = clock.millis()
    if (now > last) {
      tokens = math.min(capacity, tokens + (now - last) * perMilli)
      last = now
    }
    if (tokens >= 1.0) { tokens -= 1.0; Right(()) }
    else Left(math.max(1, math.ceil((1.0 - tokens) / perMilli / 1000.0).toInt))
  }
}

/** Removes secrets and account details from text: the configured API key values, `arn:` strings,
  * 12-digit numbers (account IDs) and `s3://` URIs. Since Phase 11 (S-4) log lines carry no
  * downstream text at all; the redactor stays as a last guard on the fields that are logged.
  */
final class Redactor(keyValues: List[String]) {
  private val values = keyValues.filter(_.nonEmpty).sortBy(-_.length)
  private val patterns = List(
    Pattern.compile("arn:[^\\s\"',;]+"),
    Pattern.compile("(?<![0-9])[0-9]{12}(?![0-9])"),
    Pattern.compile("s3://[^\\s\"',;]+")
  )

  def apply(text: String): String = {
    val s0 = Option(text).getOrElse("")
    val s1 = values.foldLeft(s0)((s, v) => s.replace(v, "[redacted]"))
    patterns.foldLeft(s1)((s, p) => p.matcher(s).replaceAll("[redacted]"))
  }
}

/** Where the services write their JSON log lines (one object per line). */
trait LogSink {
  def write(line: ujson.Obj): Unit
}

object LogSink {

  /** slf4j (logback at runtime), logger `org.scholarchain.services`. */
  def slf4j: LogSink = new LogSink {
    private val log = LoggerFactory.getLogger("org.scholarchain.services")
    def write(line: ujson.Obj): Unit = log.info(line.render())
  }
}

/** Collects log lines in memory (tests). Thread-safe. */
final class MemoryLogSink extends LogSink {
  private val lines = new ConcurrentLinkedQueue[ujson.Obj]()
  def write(line: ujson.Obj): Unit = lines.add(line)
  def all: List[ujson.Obj] = lines.asScala.toList
  def rendered: List[String] = all.map(_.render())
}

/** The structured log of one server: every line carries the time and role. */
final class ServiceLog(sink: LogSink, role: String, clock: Clock, redactor: Redactor) {
  private def base(event: String): ujson.Obj =
    ujson.Obj(
      "time" -> Instant.ofEpochMilli(clock.millis()).toString,
      "role" -> role,
      "event" -> event
    )

  private def emit(o: ujson.Obj): Unit =
    try sink.write(o)
    catch { case scala.util.control.NonFatal(_) => () }

  /** One line per request (decision 7i): never a body, a header or the raw path. */
  def request(
      traceId: String,
      requestId: String,
      method: String,
      route: String,
      status: Int,
      latencyMillis: Long,
      errorCode: Option[String],
      callerClass: String
  ): Unit = {
    val o = base("request")
    o("traceId") = traceId
    o("requestId") = requestId
    o("method") = method
    o("route") = route
    o("status") = status
    o("latencyMillis") = latencyMillis.toDouble
    o("errorCode") = errorCode.map(ujson.Str(_)).getOrElse(ujson.Null)
    o("callerClass") = callerClass
    emit(o)
  }

  /** One line per workflow stage, sync and async (decision 7f). */
  def stage(traceId: String, jobId: Option[String], queryId: String, stage: String): Unit = {
    val o = base("stage")
    o("traceId") = traceId
    o("jobId") = jobId.map(ujson.Str(_)).getOrElse(ujson.Null)
    o("queryId") = queryId
    o("stage") = stage
    emit(o)
  }

  /** An operational event with fixed fields only (Phase 11 S-4): never free text from downstream
    * (no store, SDK or provider message). A failure is described by a [[LogCause]] code and, for an
    * exception, its class name.
    */
  def event(
      name: String,
      traceId: Option[String],
      fields: List[(String, ujson.Value)]
  ): Unit = {
    val o = base(name)
    traceId.foreach(t => o("traceId") = t)
    fields.foreach { case (k, v) => o(k) = v }
    emit(o)
  }

  /** A structured line another module built (rag's `reembed-delivery` line, Phase 15 decision 8.1),
    * written as one line of this log: `time` and `role` are added, the line's own fields (its
    * `event` included) are kept. Phase 16 H-06: every string value of the line passes the redactor
    * (the configured key values, `arn:` strings, 12-digit numbers and `s3://` URIs become
    * `[redacted]`). rag validates a task ID only by shape (`[A-Za-z0-9][A-Za-z0-9._:-]{0,127}`), so
    * a sender could put an ARN or an account number in it; an ordinary task ID such as
    * `p15-f-poison-20261121T001529Z` is unchanged, so log filters on a task ID prefix still work.
    * The fixed-vocabulary fields (`event`, `outcome`, `code`, `action`, `index`) hold none of these
    * patterns. A line that is not a JSON object is written as `{"event": "<event>", "code":
    * "log-line-unreadable"}` so the one-line-per-delivery count holds.
    */
  def external(line: String, fallbackEvent: String): Unit = {
    val o = base(fallbackEvent)
    val parsed =
      try ujson.read(line).objOpt
      catch { case scala.util.control.NonFatal(_) => None }
    parsed match {
      case Some(fields) =>
        fields.foreach {
          case (k, ujson.Str(s)) => o(k) = redactor(s)
          case (k, v)            => o(k) = v
        }
      case None => o("code") = "log-line-unreadable"
    }
    emit(o)
  }

  /** An unexpected exception: the fixed cause `unexpected-exception` and the exception's class name
    * only (never its message, which may hold downstream text). The class name passes the redactor
    * as a last guard.
    */
  def failure(traceId: Option[String], where: String, e: Throwable): Unit =
    event(
      "error",
      traceId,
      List(
        "where" -> ujson.Str(where),
        "cause" -> ujson.Str(LogCause.UnexpectedException),
        "exception" -> ujson.Str(redactor(LogCause.className(e)))
      )
    )
}

/** Fixed cause codes of operational log lines (Phase 11 S-4). Log lines carry these codes and, for
  * an exception, its class name; never the text of a store, SDK, provider or workflow failure.
  */
object LogCause {
  val UnexpectedException = "unexpected-exception"
  val StoreFailure = "store-failure"
  val ConditionFailed = "condition-failed"
  val Refused = "refused"
  val ResultTooLarge = "result-too-large"
  val Written = "written"

  /** Causes of a rag workflow `Left` (classified by rag's fixed prefixes only). */
  val WorkflowException = "workflow-exception"
  val BundleWriteFailed = "evidence-bundle-write-failed"
  val AuditWriteFailed = "query-audit-write-failed"
  val ResponseContractFailed = "answer-response-contract-failed"
  val AuditContractFailed = "query-audit-contract-failed"
  val WorkflowFailed = "workflow-failed"

  private val JavaIdentifier = "[A-Za-z_$][A-Za-z0-9_$]{0,100}"
  private val ClassName = s"$JavaIdentifier(\\.$JavaIdentifier){0,30}".r
  private val WorkflowExceptionShape = s"workflow failed: ($JavaIdentifier): .*".r

  /** The class name of `e` when it is a well-formed Java class name, else `unknown`. */
  def className(e: Throwable): String = {
    val n = Option(e).map(_.getClass.getName).getOrElse("")
    if (ClassName.matches(n)) n else "unknown"
  }

  /** The cause code of a job or idempotency store result that was not the expected write. */
  def ofChange(r: Either[String, org.scholarchain.storage.jobs.JobChange]): String = {
    import org.scholarchain.storage.jobs.JobChange
    r match {
      case Left(_)                               => StoreFailure
      case Right(JobChange.ConditionFailed)      => ConditionFailed
      case Right(JobChange.Refused(_))           => Refused
      case Right(JobChange.ResultTooLarge(_, _)) => ResultTooLarge
      case Right(JobChange.Written(_))           => Written
    }
  }

  /** The log fields of a rag workflow `Left`: a fixed `cause` and, when rag's text names the
    * exception (`workflow failed: <SimpleName>: ...`), that simple class name (a Java identifier:
    * no dots, dashes or spaces, so never a host or table name). The rest of the text is dropped.
    */
  def ofWorkflow(e: String): List[(String, ujson.Value)] = {
    val text = Option(e).getOrElse("")
    val cause =
      if (text.startsWith(QueryWorkflow.BundleWriteFailedPrefix)) BundleWriteFailed
      else if (text.startsWith(QueryWorkflow.AuditWriteFailedPrefix)) AuditWriteFailed
      else if (text.startsWith("answer response failed its contract")) ResponseContractFailed
      else if (text.startsWith("query audit failed its contract")) AuditContractFailed
      else if (text.startsWith("workflow failed:")) WorkflowException
      else WorkflowFailed
    val exception = text match {
      case WorkflowExceptionShape(name) => List("exception" -> ujson.Str(name))
      case _                            => Nil
    }
    ("cause" -> ujson.Str(cause)) :: exception
  }
}

/** Names threads `scholarchain-<server>-<pool>-<n>` and remembers them, so `close()` can wait for
  * every thread it created.
  */
final class NamedThreadFactory(prefix: String) extends ThreadFactory {
  private val n = new AtomicInteger(0)
  private val created = new ConcurrentLinkedQueue[Thread]()
  def newThread(r: Runnable): Thread = {
    val t = new Thread(r, s"$prefix-${n.incrementAndGet()}")
    t.setDaemon(true)
    created.add(t)
    t
  }
  def threads: List[Thread] = created.asScala.toList
}

/** Bounded pools: fixed size, bounded queue. */
object Pools {
  def fixed(
      threads: Int,
      queueCapacity: Int,
      factory: ThreadFactory,
      onReject: RejectedExecutionHandler = new ThreadPoolExecutor.AbortPolicy()
  ): ThreadPoolExecutor =
    new ThreadPoolExecutor(
      threads,
      threads,
      0L,
      TimeUnit.MILLISECONDS,
      new ArrayBlockingQueue[Runnable](math.max(1, queueCapacity)),
      factory,
      onReject
    )

  /** Stops accepting, waits at most `millis` for running tasks, then interrupts what is left. */
  def shutdown(pool: ThreadPoolExecutor, millis: Long): Unit = {
    pool.shutdown()
    try {
      if (!pool.awaitTermination(math.max(0L, millis), TimeUnit.MILLISECONDS)) {
        pool.shutdownNow()
        pool.awaitTermination(1000L, TimeUnit.MILLISECONDS)
      }
    } catch {
      case _: InterruptedException =>
        pool.shutdownNow(); Thread.currentThread().interrupt()
    }
  }
}
