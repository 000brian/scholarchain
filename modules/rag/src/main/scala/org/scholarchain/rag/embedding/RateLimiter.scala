package org.scholarchain.rag.embedding

import org.scholarchain.core.config.EmbeddingSettings
import org.scholarchain.storage.{RetryPolicy, Sleeper}

/** Client-side rate limit for one provider: at most `requestsPerMinute` requests and
  * `tokensPerMinute` (estimated) tokens per fixed one-minute window. When the next request would
  * exceed either budget, [[acquire]] sleeps until the window ends. A single request larger than the
  * whole token budget is let through alone in an empty window (otherwise it could never be sent).
  * Deterministic with an injected clock and sleeper.
  */
final class RateLimiter(
    requestsPerMinute: Int,
    tokensPerMinute: Int,
    clock: () => Long,
    sleeper: Sleeper
) {
  require(requestsPerMinute > 0, "requestsPerMinute must be > 0")
  require(tokensPerMinute > 0, "tokensPerMinute must be > 0")
  private val WindowMillis = 60000L
  private var windowStart = Long.MinValue
  private var requests = 0
  private var tokens = 0L
  private var slept = 0L

  /** Total milliseconds this limiter has slept. */
  def sleptMillis: Long = synchronized(slept)

  def acquire(estimatedTokens: Long): Unit = synchronized {
    var done = false
    var guard = 0
    while (!done) {
      val now = clock()
      if (windowStart == Long.MinValue || now - windowStart >= WindowMillis) {
        windowStart = now
        requests = 0
        tokens = 0L
      }
      val fitsRequests = requests + 1 <= requestsPerMinute
      val fitsTokens = tokens + estimatedTokens <= tokensPerMinute || (requests == 0 && tokens == 0)
      if (fitsRequests && fitsTokens) {
        requests += 1
        tokens += estimatedTokens
        done = true
      } else {
        val wait = math.max(1L, windowStart + WindowMillis - now)
        slept += wait
        sleeper.sleep(wait)
        guard += 1
        // After one full window every budget is empty, so more than two waits means a broken clock.
        if (guard > 3) throw new IllegalStateException("rate limiter clock does not advance")
      }
    }
  }
}

object RateLimiter {
  def fromSettings(s: EmbeddingSettings, clock: () => Long, sleeper: Sleeper): RateLimiter =
    new RateLimiter(s.requestsPerMinute, s.tokensPerMinute, clock, sleeper)
}

object EmbeddingRetry {

  /** Bounded backoff for provider calls (429 and 5xx), from the embedding settings. */
  def policy(s: EmbeddingSettings): RetryPolicy =
    RetryPolicy(s.maxRetries, s.retryBaseDelayMillis.toLong, s.retryMaxDelayMillis.toLong)

  /** A conservative token estimate for rate limiting: about 3 characters per token, plus one. The
    * provider's own count (when it reports one) is used for cost reporting.
    */
  def estimateTokens(text: String): Long = text.length / 3L + 1L
}

/** Usage counters of one embedder, for run reports and cost estimates. Never holds text. */
final class EmbeddingUsage {
  @volatile var requests: Long = 0L
  @volatile var texts: Long = 0L
  @volatile var estimatedTokens: Long = 0L
  @volatile var reportedTokens: Long = 0L
  @volatile var retries: Long = 0L
  @volatile var characters: Long = 0L

  def toJson: ujson.Obj = ujson.Obj(
    "requests" -> requests.toDouble,
    "texts" -> texts.toDouble,
    "characters" -> characters.toDouble,
    "estimatedTokens" -> estimatedTokens.toDouble,
    "providerReportedTokens" -> reportedTokens.toDouble,
    "retries" -> retries.toDouble
  )
}
