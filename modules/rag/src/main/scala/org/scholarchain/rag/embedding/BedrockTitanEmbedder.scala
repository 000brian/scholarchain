package org.scholarchain.rag.embedding

import scala.util.control.NonFatal

import org.scholarchain.core.config.{EmbeddingProvider, EmbeddingSettings}
import org.scholarchain.storage.{Retry, RetryPolicy, Sleeper, StoreException}
import org.scholarchain.storage.aws.{AwsClientSettings, AwsErrors}
import org.scholarchain.storage.ports.{EmbeddingBatch, EmbeddingFailure, EmbeddingPort}
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest

/** Invokes one Bedrock model with a JSON body and returns the JSON response body. Failures are
  * [[StoreException]]s (throttling and 5xx transient).
  */
trait BedrockInvoker {
  def invoke(modelId: String, jsonBody: String): String
}

/** The real invoker: the synchronous Bedrock Runtime client on url-connection-client, credentials
  * from the default provider chain, Region from configuration. SDK retries are limited to one
  * attempt so the embedder's own bounded retry policy is the only one.
  */
final class SdkBedrockInvoker(client: BedrockRuntimeClient)
    extends BedrockInvoker
    with AutoCloseable {
  def invoke(modelId: String, jsonBody: String): String =
    AwsErrors.guard {
      client
        .invokeModel(
          InvokeModelRequest
            .builder()
            .modelId(modelId)
            .contentType("application/json")
            .accept("application/json")
            .body(SdkBytes.fromUtf8String(jsonBody))
            .build()
        )
        .body()
        .asUtf8String()
    }
  def close(): Unit = client.close()
}

object SdkBedrockInvoker {
  def create(aws: AwsClientSettings): SdkBedrockInvoker =
    new SdkBedrockInvoker(
      BedrockRuntimeClient
        .builder()
        .region(aws.awsRegion)
        .httpClient(aws.httpClient())
        .overrideConfiguration(aws.overrides())
        .build()
    )
}

/** The `bedrock` provider: Amazon Titan Text Embeddings V2 (`amazon.titan-embed-text-v2:0`), one
  * text per InvokeModel call, body `{"inputText": t, "dimensions": d, "normalize": true}`, response
  * `{"embedding": [...], "inputTextTokenCount": n}`. Kept as the switch the instructor asked for;
  * this account's on-demand quota is 0 (decision 8), so it is not the default. Throttling and 5xx
  * are retried with the bounded embedding retry policy; a client-side [[RateLimiter]] applies.
  */
final class BedrockTitanEmbedder(
    val spec: EmbeddingModelSpec,
    settings: EmbeddingSettings,
    invoker: BedrockInvoker,
    sleeper: Sleeper,
    clock: () => Long
) extends EmbeddingPort {
  require(spec.provider == EmbeddingProvider.Bedrock, "BedrockTitanEmbedder needs a bedrock spec")
  val usage = new EmbeddingUsage
  private val policy: RetryPolicy = EmbeddingRetry.policy(settings)
  private val limiter = RateLimiter.fromSettings(settings, clock, sleeper)

  def modelId: String = spec.id
  def dimensions: Int = spec.dimensions
  val maxBatchSize: Int = settings.batchSize

  def embed(texts: IndexedSeq[String]): Either[EmbeddingFailure, EmbeddingBatch] =
    if (texts.size > maxBatchSize)
      Left(EmbeddingFailure(s"batch of ${texts.size} exceeds $maxBatchSize", 0))
    else {
      val out = Vector.newBuilder[Array[Float]]
      var retries = 0
      var failure: Option[String] = None
      val it = texts.iterator
      while (failure.isEmpty && it.hasNext) {
        val t = it.next()
        val body = ujson
          .Obj("inputText" -> t, "dimensions" -> spec.dimensions, "normalize" -> true)
          .render()
        val est = EmbeddingRetry.estimateTokens(t)
        val r = Retry.run(policy, sleeper) {
          limiter.acquire(est)
          usage.addRequests(1L)
          invoker.invoke(spec.model, body)
        }
        retries += r.retries
        r.result.flatMap(b =>
          BedrockTitanEmbedder.parse(b, spec.dimensions).left.map(new StoreException(_, false))
        ) match {
          case Right((v, tokens)) =>
            out += v
            usage.addTexts(1L)
            usage.addCharacters(t.length.toLong)
            usage.addEstimatedTokens(est)
            tokens.foreach(n => usage.addReportedTokens(n))
          case Left(e) => failure = Some(s"bedrock: ${e.getMessage}".take(300))
        }
      }
      usage.addRetries(retries.toLong)
      failure match {
        case Some(m) => Left(EmbeddingFailure(m, retries))
        case None    => Right(EmbeddingBatch(out.result(), retries))
      }
    }
}

object BedrockTitanEmbedder {
  def parse(body: String, dims: Int): Either[String, (Array[Float], Option[Long])] =
    try {
      val o = ujson.read(body).obj
      val v = o("embedding").arr.map(_.num.toFloat).toArray
      val tokens = o.get("inputTextTokenCount").map(_.num.toLong)
      if (v.length != dims) Left(s"titan returned ${v.length} values, expected $dims")
      else if (v.exists(f => f.isNaN || f.isInfinite)) Left("titan returned non-finite values")
      else Right((v, tokens))
    } catch {
      case NonFatal(e) => Left(s"titan response is malformed: ${e.getClass.getSimpleName}")
    }
}
