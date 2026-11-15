package org.scholarchain.storage

import scala.util.control.NonFatal

/** A store call failed. `transient` failures (throttling, HTTP 5xx, timeouts) may be retried;
  * permanent ones (validation, access denied, conditional-check failures) never are.
  */
final class StoreException(message: String, val transient: Boolean, cause: Throwable = null)
    extends RuntimeException(message, cause)

/** Bounded exponential backoff: the delay before retry `n` (1-based) is `baseDelayMillis * 2^(n-1)`
  * capped at `maxDelayMillis`. No jitter, so tests and local runs are deterministic.
  */
final case class RetryPolicy(maxRetries: Int, baseDelayMillis: Long, maxDelayMillis: Long) {
  require(maxRetries >= 0, "maxRetries must be >= 0")
  require(baseDelayMillis > 0, "baseDelayMillis must be > 0")
  require(maxDelayMillis >= baseDelayMillis, "maxDelayMillis must be >= baseDelayMillis")

  def delayBeforeRetry(retry: Int): Long = {
    val shift = math.min(math.max(retry - 1, 0), 30)
    math.min(maxDelayMillis, baseDelayMillis * (1L << shift))
  }
}

object RetryPolicy {
  def fromVectorSettings(v: org.scholarchain.core.config.VectorSettings): RetryPolicy =
    RetryPolicy(v.maxRetries, v.retryBaseDelayMillis.toLong, v.retryMaxDelayMillis.toLong)
}

/** Sleeps between retries; tests inject a recording sleeper. */
trait Sleeper { def sleep(millis: Long): Unit }
object Sleeper {
  val real: Sleeper = (millis: Long) => Thread.sleep(millis)
  val none: Sleeper = (_: Long) => ()
}

/** Result of a retried call: the value or the last failure, and how many retries were used. */
final case class Retried[A](result: Either[StoreException, A], retries: Int)

object Retry {

  /** Runs `op`, retrying transient [[StoreException]]s up to `policy.maxRetries` times. Any other
    * non-fatal exception is wrapped as a permanent failure and not retried.
    */
  def run[A](policy: RetryPolicy, sleeper: Sleeper)(op: => A): Retried[A] = {
    var retries = 0
    var out: Option[Retried[A]] = None
    while (out.isEmpty) {
      val attempt: Either[StoreException, A] =
        try Right(op)
        catch {
          case e: StoreException => Left(e)
          case NonFatal(e)       =>
            Left(new StoreException(s"${e.getClass.getSimpleName}: ${e.getMessage}", false, e))
        }
      attempt match {
        case Left(e) if e.transient && retries < policy.maxRetries =>
          retries += 1
          sleeper.sleep(policy.delayBeforeRetry(retries))
        case other => out = Some(Retried(other, retries))
      }
    }
    out.get
  }
}
