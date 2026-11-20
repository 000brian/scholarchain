package org.scholarchain.rag.embedding

import java.io.IOException
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse, HttpTimeoutException}
import java.nio.charset.StandardCharsets
import java.time.Duration

import scala.util.control.NonFatal

import org.scholarchain.core.config.{EmbeddingProvider, EmbeddingSettings}
import org.scholarchain.storage.{RetryPolicy, Sleeper}
import org.scholarchain.storage.ports.{EmbeddingBatch, EmbeddingFailure, EmbeddingPort}

/** One HTTP response: status, body, and the `Retry-After` header in seconds when present. */
final case class HttpResult(status: Int, body: String, retryAfterSeconds: Option[Long])

/** A minimal POST transport, so the Voyage client can be tested without a network. Implementations
  * throw `IOException` (including timeouts) on transport failure.
  */
trait HttpTransport {
  def postJson(uri: URI, headers: List[(String, String)], body: String): HttpResult
}

/** The JDK `java.net.http` transport (no extra dependency). Never logs headers or bodies. */
final class JdkHttpTransport(connectTimeoutMillis: Int, requestTimeoutMillis: Int)
    extends HttpTransport {
  private val client = HttpClient
    .newBuilder()
    .connectTimeout(Duration.ofMillis(connectTimeoutMillis.toLong))
    .followRedirects(HttpClient.Redirect.NEVER)
    .build()

  def postJson(uri: URI, headers: List[(String, String)], body: String): HttpResult = {
    val b = HttpRequest
      .newBuilder(uri)
      .timeout(Duration.ofMillis(requestTimeoutMillis.toLong))
      .header("Content-Type", "application/json")
      .header("Accept", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
    headers.foreach { case (k, v) => b.header(k, v) }
    val r = client.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
    val retryAfter = r.headers().firstValue("retry-after").map[Option[Long]] { v =>
      v.trim.toLongOption
    }
    HttpResult(r.statusCode(), r.body(), retryAfter.orElse(None))
  }
}

/** The `voyage` provider: Voyage AI embeddings API over HTTPS (`POST /v1/embeddings`).
  *
  * Request: `{"input": [...], "model": <model>, "input_type": "document"|"query",
  * "output_dimension": <dimensions>, "output_dtype": "float", "truncation": false}`. Truncation is
  * off so an over-long text fails loudly instead of being embedded partially (the chunking policy
  * keeps texts far below the limit). The response's `data[i].index` restores input order.
  *
  * The API key is read once, at construction, from the environment variable whose NAME is
  * `embedding.apiKeyEnvVar`; it is sent only in the `Authorization` header and never logged,
  * stored, or included in an error message (provider error bodies are truncated and scrubbed of
  * it). Requests are split into at most `embedding.batchSize` texts and
  * [[VoyageEmbedder.MaxEstimatedTokensPerRequest]] estimated tokens; HTTP 429 and 5xx (and
  * transport errors) are retried with bounded exponential backoff (`embedding.maxRetries`,
  * honouring `Retry-After` up to `retryMaxDelayMillis`); other statuses fail at once. A client-side
  * [[RateLimiter]] holds requests and tokens per minute to the configured limits.
  */
final class VoyageEmbedder private (
    val spec: EmbeddingModelSpec,
    settings: EmbeddingSettings,
    apiKey: String,
    transport: HttpTransport,
    endpoint: URI,
    sleeper: Sleeper,
    limiter: RateLimiter
) extends EmbeddingPort {
  val usage = new EmbeddingUsage
  private val policy: RetryPolicy = EmbeddingRetry.policy(settings)

  def modelId: String = spec.id
  def dimensions: Int = spec.dimensions
  val maxBatchSize: Int = math.min(settings.batchSize, VoyageEmbedder.MaxTextsPerRequest)

  override def toString: String = s"VoyageEmbedder(${spec.id})"

  private def scrub(s: String): String =
    (if (apiKey.nonEmpty) s.replace(apiKey, "<redacted>") else s).take(300)

  def embed(texts: IndexedSeq[String]): Either[EmbeddingFailure, EmbeddingBatch] =
    if (texts.size > maxBatchSize)
      Left(EmbeddingFailure(s"batch of ${texts.size} exceeds $maxBatchSize", 0))
    else {
      val out = Vector.newBuilder[Array[Float]]
      var retries = 0
      var failure: Option[String] = None
      val it = VoyageEmbedder.requestGroups(texts).iterator
      while (failure.isEmpty && it.hasNext) {
        val group = it.next()
        call(group) match {
          case Right((vs, r)) => out ++= vs; retries += r
          case Left((msg, r)) => failure = Some(msg); retries += r
        }
      }
      usage.addRetries(retries.toLong)
      failure match {
        case Some(m) => Left(EmbeddingFailure(m, retries))
        case None    => Right(EmbeddingBatch(out.result(), retries))
      }
    }

  private def requestBody(group: IndexedSeq[String]): String =
    ujson
      .Obj(
        "input" -> ujson.Arr.from(group.map(ujson.Str(_))),
        "model" -> spec.model,
        "input_type" -> spec.inputType.wire,
        "output_dimension" -> spec.dimensions,
        "output_dtype" -> "float",
        "truncation" -> false
      )
      .render()

  /** One request with bounded retries. Returns vectors in input order, or a message. */
  private def call(
      group: IndexedSeq[String]
  ): Either[(String, Int), (Vector[Array[Float]], Int)] = {
    val body = requestBody(group)
    val estimate = group.map(EmbeddingRetry.estimateTokens).sum
    var retries = 0
    var result: Option[Either[(String, Int), (Vector[Array[Float]], Int)]] = None
    while (result.isEmpty) {
      limiter.acquire(estimate)
      usage.addRequests(1L)
      val attempt: Either[(Boolean, String, Option[Long]), HttpResult] =
        try Right(transport.postJson(endpoint, List("Authorization" -> s"Bearer $apiKey"), body))
        catch {
          case e: HttpTimeoutException => Left((true, "voyage request timed out", None))
          case e: IOException          =>
            Left((true, s"voyage transport error: ${e.getClass.getSimpleName}", None))
          case NonFatal(e) =>
            Left((false, s"voyage client error: ${e.getClass.getSimpleName}", None))
        }
      val outcome: Either[(Boolean, String, Option[Long]), Vector[Array[Float]]] = attempt
        .flatMap { r =>
          if (r.status == 200)
            VoyageEmbedder
              .parse(r.body, group.size, spec.dimensions)
              .map { case (vs, tokens) =>
                tokens.foreach(t => usage.addReportedTokens(t))
                vs
              }
              .left
              .map(m => (false, m, None))
          else {
            val transient = r.status == 429 || r.status >= 500
            Left((transient, s"voyage HTTP ${r.status}: ${scrub(r.body)}", r.retryAfterSeconds))
          }
        }
      outcome match {
        case Right(vs) =>
          usage.addTexts(group.size.toLong)
          usage.addCharacters(group.map(_.length.toLong).sum)
          usage.addEstimatedTokens(estimate)
          result = Some(Right((vs, retries)))
        case Left((true, _, retryAfter)) if retries < policy.maxRetries =>
          retries += 1
          val backoff = policy.delayBeforeRetry(retries)
          val hinted = retryAfter.map(s => math.min(policy.maxDelayMillis, s * 1000L)).getOrElse(0L)
          sleeper.sleep(math.max(backoff, hinted))
        case Left((_, msg, _)) =>
          result = Some(Left((msg, retries)))
      }
    }
    result.get
  }
}

object VoyageEmbedder {
  val DefaultEndpoint: URI = URI.create("https://api.voyageai.com/v1/embeddings")

  /** Voyage's documented per-request list limit is 1,000 texts; the configured batch size is
    * smaller. Verify before the first live run.
    */
  val MaxTextsPerRequest = 1000

  /** Conservative per-request token cap (estimated). Voyage's per-request token limit for voyage-4
    * is larger; verify before the first live run.
    */
  val MaxEstimatedTokensPerRequest = 100000L

  /** Splits one `embed` batch into request groups under the token cap (order preserved). */
  def requestGroups(texts: IndexedSeq[String]): Vector[IndexedSeq[String]] = {
    val groups = Vector.newBuilder[IndexedSeq[String]]
    var cur = Vector.newBuilder[String]
    var curN = 0
    var curTokens = 0L
    texts.foreach { t =>
      val e = EmbeddingRetry.estimateTokens(t)
      if (curN > 0 && curTokens + e > MaxEstimatedTokensPerRequest) {
        groups += cur.result()
        cur = Vector.newBuilder[String]
        curN = 0
        curTokens = 0L
      }
      cur += t
      curN += 1
      curTokens += e
    }
    if (curN > 0) groups += cur.result()
    groups.result()
  }

  /** Parses a 200 response: exactly `n` embeddings of length `dims`, finite, indexes 0..n-1. */
  def parse(body: String, n: Int, dims: Int): Either[String, (Vector[Array[Float]], Option[Long])] =
    try {
      val root = ujson.read(body).obj
      val data = root("data").arr.toVector
      val byIndex = data.map { d =>
        val o = d.obj
        o("index").num.toInt -> o("embedding").arr.map(_.num.toFloat).toArray
      }
      val tokens = root.get("usage").flatMap(_.obj.get("total_tokens")).map(_.num.toLong)
      val indexes = byIndex.map(_._1).sorted
      if (data.size != n) Left(s"voyage returned ${data.size} embeddings for $n texts")
      else if (indexes != (0 until n).toVector) Left("voyage returned unexpected indexes")
      else {
        val vs = byIndex.sortBy(_._1).map(_._2)
        if (vs.exists(_.length != dims)) Left(s"voyage returned a vector whose length is not $dims")
        else if (vs.exists(_.exists(f => f.isNaN || f.isInfinite)))
          Left("voyage returned non-finite values")
        else Right((vs, tokens))
      }
    } catch {
      case NonFatal(e) => Left(s"voyage response is malformed: ${e.getClass.getSimpleName}")
    }

  /** Builds the embedder. The key comes from `env(settings.apiKeyEnvVar)`; a missing name or value
    * fails here, naming the variable but never revealing any value.
    */
  def create(
      spec: EmbeddingModelSpec,
      settings: EmbeddingSettings,
      env: String => Option[String],
      transport: HttpTransport,
      sleeper: Sleeper,
      clock: () => Long,
      endpoint: URI = DefaultEndpoint
  ): Either[String, VoyageEmbedder] =
    for {
      _ <- Either.cond(spec.provider == EmbeddingProvider.Voyage, (), "spec is not a voyage spec")
      name <- settings.apiKeyEnvVar.toRight("embedding.apiKeyEnvVar is required for voyage")
      key <- env(name)
        .map(_.trim)
        .filter(_.nonEmpty)
        .toRight(s"environment variable $name is not set")
    } yield new VoyageEmbedder(
      spec,
      settings,
      key,
      transport,
      endpoint,
      sleeper,
      RateLimiter.fromSettings(settings, clock, sleeper)
    )
}
