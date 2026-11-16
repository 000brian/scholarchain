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
  */
final class CircuitBreaker(val name: String, threshold: Int, openMillis: Long, clock: Clock) {
  require(threshold >= 1 && openMillis >= 1)
  private var failures = 0
  private var openUntil: Option[Long] = None
  private var trialInFlight = false

  /** True when a call may proceed (closed, or the one trial after the open period). */
  def tryAcquire(): Boolean = synchronized {
    openUntil match {
      case None                          => true
      case Some(t) if clock.millis() < t => false
      case Some(_) if trialInFlight      => false
      case Some(_)                       => trialInFlight = true; true
    }
  }

  /** Gives back a slot taken by [[tryAcquire]] without an outcome (the call was never made). */
  def cancel(): Unit = synchronized { trialInFlight = false }

  def recordSuccess(): Unit = synchronized {
    failures = 0; openUntil = None; trialInFlight = false
  }

  def recordFailure(): Unit = synchronized {
    if (openUntil.nonEmpty) {
      // the trial failed: open again
      openUntil = Some(clock.millis() + openMillis); trialInFlight = false
    } else {
      failures += 1
      if (failures >= threshold) openUntil = Some(clock.millis() + openMillis)
    }
  }

  /** `closed`, `open` or `half-open` (tests and logs). */
  def state: String = synchronized {
    openUntil match {
      case None                          => "closed"
      case Some(t) if clock.millis() < t => "open"
      case Some(_)                       => "half-open"
    }
  }

  /** Runs `f` through the breaker: Left(None) when open; Left(Some(e)) on a downstream failure. */
  def call[A](f: => Either[String, A]): Either[Option[String], A] =
    if (!tryAcquire()) Left(None)
    else {
      val r =
        try f
        catch {
          case e: Throwable =>
            recordFailure(); throw e
        }
      r match {
        case Right(a) => recordSuccess(); Right(a)
        case Left(e)  => recordFailure(); Left(Some(e))
      }
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

/** Removes secrets and account details from text that is logged (exception messages): the
  * configured API key values, `arn:` strings, 12-digit numbers (account IDs) and `s3://` URIs.
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

  /** An operational event with fixed fields (no free text from downstream except `detail`, which
    * passes the redactor).
    */
  def event(
      name: String,
      traceId: Option[String],
      fields: List[(String, ujson.Value)],
      detail: Option[String] = None
  ): Unit = {
    val o = base(name)
    traceId.foreach(t => o("traceId") = t)
    fields.foreach { case (k, v) => o(k) = v }
    detail.foreach(d => o("detail") = redactor(d).take(500))
    emit(o)
  }

  /** An unexpected exception: its class and redacted message. */
  def failure(traceId: Option[String], where: String, e: Throwable): Unit =
    event(
      "error",
      traceId,
      List("where" -> ujson.Str(where), "exception" -> ujson.Str(e.getClass.getName)),
      Some(Option(e.getMessage).getOrElse(""))
    )
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
