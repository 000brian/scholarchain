package org.scholarchain.rag.runtime

import java.io.IOException
import java.net.URI
import java.net.http.HttpTimeoutException

import scala.util.control.NonFatal

import org.scholarchain.core.config.{LlmProvider, LlmSettings, ScholarChainConfig}
import org.scholarchain.rag.embedding.{
  BedrockInvoker,
  HttpResult,
  HttpTransport,
  JdkHttpTransport,
  RateLimiter,
  SdkBedrockInvoker
}
import org.scholarchain.storage.{Retry, RetryPolicy, Sleeper, StoreException}
import org.scholarchain.storage.aws.AwsClientSettings

/** Which runtime stage a model call serves. */
sealed abstract class ModelTask(val wire: String)
object ModelTask {
  case object Planner extends ModelTask("planner")
  case object Synthesis extends ModelTask("synthesis")
}

/** Structured input that only the deterministic fake model reads; live providers ignore it and see
  * only the rendered prompt.
  */
sealed trait FakeInput
final case class PlannerFakeInput(question: String) extends FakeInput
final case class SynthesisFakeInput(input: SynthesisInput) extends FakeInput

final case class ModelRequest(
    task: ModelTask,
    system: String,
    user: String,
    maxOutputTokens: Int,
    fake: FakeInput
)
final case class ModelReply(text: String, inputTokens: Option[Long], outputTokens: Option[Long])
final case class ModelFailure(message: String, retries: Int)

/** Usage counters of one model client (never holds text). */
final class LlmUsage {
  @volatile var requests: Long = 0L
  @volatile var retries: Long = 0L
  @volatile var inputTokens: Long = 0L
  @volatile var outputTokens: Long = 0L
  def toJson: ujson.Obj = ujson.Obj(
    "requests" -> requests.toDouble,
    "retries" -> retries.toDouble,
    "inputTokens" -> inputTokens.toDouble,
    "outputTokens" -> outputTokens.toDouble
  )
}

/** A text model behind the `llm.provider` switch. */
trait LanguageModel {
  def provider: LlmProvider
  def modelId: String
  def usage: LlmUsage
  def complete(request: ModelRequest): Either[ModelFailure, ModelReply]
}

/** The `fake` provider: deterministic. The planner side runs [[RulePlanner]]; the synthesis side
  * renders claims from the facts table with fixed templates ([[FakeSynthesis]]). Its output is JSON
  * text and goes through the same validation as a live model's.
  */
final class FakeModel(planner: RulePlanner) extends LanguageModel {
  val provider: LlmProvider = LlmProvider.Fake
  val modelId: String = FakeModel.ModelId
  val usage = new LlmUsage
  def complete(r: ModelRequest): Either[ModelFailure, ModelReply] = {
    usage.requests += 1
    r.fake match {
      case PlannerFakeInput(q)   => Right(ModelReply(planner.draft(q).toJson.render(), None, None))
      case SynthesisFakeInput(i) =>
        Right(ModelReply(FakeSynthesis.render(i).toJson.render(), None, None))
    }
  }
}

object FakeModel {
  val ModelId = "fake-rule-template-v1"
}

/** Messages API request and response shape (verify in the live benchmark run). */
object AnthropicMessages {
  val DefaultEndpoint: URI = URI.create("https://api.anthropic.com/v1/messages")
  val ApiVersion = "2023-06-01"
  val BedrockApiVersion = "bedrock-2023-05-31"

  def body(model: Option[String], r: ModelRequest, maxTokens: Int, bedrock: Boolean): String = {
    val o = ujson.Obj()
    model.foreach(m => o("model") = m)
    if (bedrock) o("anthropic_version") = BedrockApiVersion
    o("max_tokens") = math.min(maxTokens, r.maxOutputTokens)
    o("system") = r.system
    o("messages") = ujson.Arr(ujson.Obj("role" -> "user", "content" -> r.user))
    o.render()
  }

  /** `content[].text` (text blocks joined) and `usage.input_tokens` / `usage.output_tokens`. */
  def parse(body: String): Either[String, ModelReply] =
    try {
      val o = ujson.read(body).obj
      val blocks = o("content").arr.toList
      val text = blocks
        .filter(b => b.obj.get("type").flatMap(JsonSupport.str).contains("text"))
        .flatMap(b => b.obj.get("text").flatMap(JsonSupport.str))
        .mkString
      val usage = o.get("usage").map(_.obj)
      def tok(k: String) = usage.flatMap(_.get(k)).collect { case ujson.Num(n) => n.toLong }
      if (blocks.isEmpty) Left("response has no content blocks")
      else Right(ModelReply(text, tok("input_tokens"), tok("output_tokens")))
    } catch { case NonFatal(e) => Left(s"response is malformed (${e.getClass.getSimpleName})") }
}

/** The `anthropic` provider: the Messages API over `java.net.http` (POST /v1/messages, headers
  * `x-api-key`, `anthropic-version: 2023-06-01`, JSON). Body: model, max_tokens, system, messages
  * only; no sampling field (temperature, top_p, top_k) is sent (repair R-4). HTTP 429, 529 and 5xx
  * (and transport errors) are retried with bounded backoff honouring `Retry-After`; other statuses
  * fail at once. A client-side limiter enforces `llm.requestsPerMinute`. The key is read once from
  * the environment variable NAMED by `llm.apiKeyEnvVar`, sent only in its header, never logged or
  * stored, and scrubbed from error text.
  */
final class AnthropicClient private (
    val modelId: String,
    settings: LlmSettings,
    apiKey: String,
    transport: HttpTransport,
    endpoint: URI,
    sleeper: Sleeper,
    limiter: RateLimiter
) extends LanguageModel {
  val provider: LlmProvider = LlmProvider.Anthropic
  val usage = new LlmUsage
  private val policy =
    RetryPolicy(
      settings.maxRetries,
      settings.retryBaseDelayMillis.toLong,
      settings.retryMaxDelayMillis.toLong
    )

  override def toString: String = s"AnthropicClient($modelId)"

  private def scrub(s: String): String =
    (if (apiKey.nonEmpty) s.replace(apiKey, "<redacted>") else s).take(300)

  def complete(r: ModelRequest): Either[ModelFailure, ModelReply] = {
    val body = AnthropicMessages.body(Some(modelId), r, settings.maxOutputTokens, bedrock = false)
    val headers = List("x-api-key" -> apiKey, "anthropic-version" -> AnthropicMessages.ApiVersion)
    var retries = 0
    var out: Option[Either[ModelFailure, ModelReply]] = None
    while (out.isEmpty) {
      limiter.acquire(0L)
      usage.requests += 1
      val attempt: Either[(Boolean, String, Option[Long]), HttpResult] =
        try Right(transport.postJson(endpoint, headers, body))
        catch {
          case _: HttpTimeoutException => Left((true, "anthropic request timed out", None))
          case e: IOException          =>
            Left((true, s"anthropic transport error: ${e.getClass.getSimpleName}", None))
          case NonFatal(e) =>
            Left((false, s"anthropic client error: ${e.getClass.getSimpleName}", None))
        }
      val outcome = attempt.flatMap { h =>
        if (h.status == 200)
          AnthropicMessages.parse(h.body).left.map(m => (false, s"anthropic: $m", None))
        else
          Left(
            (
              AnthropicClient.transient(h.status),
              s"anthropic HTTP ${h.status}: ${scrub(h.body)}",
              h.retryAfterSeconds
            )
          )
      }
      outcome match {
        case Right(reply) =>
          reply.inputTokens.foreach(usage.inputTokens += _)
          reply.outputTokens.foreach(usage.outputTokens += _)
          out = Some(Right(reply))
        case Left((true, _, retryAfter)) if retries < policy.maxRetries =>
          retries += 1
          usage.retries += 1
          val hinted = retryAfter.map(s => math.min(policy.maxDelayMillis, s * 1000L)).getOrElse(0L)
          sleeper.sleep(math.max(policy.delayBeforeRetry(retries), hinted))
        case Left((_, msg, _)) => out = Some(Left(ModelFailure(scrub(msg), retries)))
      }
    }
    out.get
  }
}

object AnthropicClient {

  /** 429 (rate limit), 529 (overloaded) and other 5xx are retried; everything else fails at once.
    */
  def transient(status: Int): Boolean = status == 429 || status == 529 || status >= 500

  def create(
      modelId: String,
      settings: LlmSettings,
      env: String => Option[String],
      transport: HttpTransport,
      sleeper: Sleeper,
      clock: () => Long,
      endpoint: URI = AnthropicMessages.DefaultEndpoint
  ): Either[String, AnthropicClient] =
    for {
      _ <- settings.providerKind.filterOrElse(
        _ == LlmProvider.Anthropic,
        "llm.provider is not anthropic"
      )
      name <- settings.apiKeyEnvVar.toRight("llm.apiKeyEnvVar is required for anthropic")
      key <- env(name)
        .map(_.trim)
        .filter(_.nonEmpty)
        .toRight(s"environment variable $name is not set")
      _ <- Either.cond(
        modelId.matches("[a-z0-9][a-z0-9.-]*"),
        (),
        s"model ID '$modelId' is malformed"
      )
    } yield new AnthropicClient(
      modelId,
      settings,
      key,
      transport,
      endpoint,
      sleeper,
      new RateLimiter(settings.requestsPerMinute, Int.MaxValue, clock, sleeper)
    )
}

/** The `bedrock` provider: InvokeModel with the Anthropic Messages body on Bedrock (SDK
  * `maxAttempts = 1`; this client's bounded retry is the only one). Kept as the switch; this
  * account's Bedrock on-demand quota is 0.
  */
final class BedrockClaudeClient(
    val modelId: String,
    settings: LlmSettings,
    invoker: BedrockInvoker,
    sleeper: Sleeper,
    clock: () => Long
) extends LanguageModel {
  val provider: LlmProvider = LlmProvider.Bedrock
  val usage = new LlmUsage
  private val policy =
    RetryPolicy(
      settings.maxRetries,
      settings.retryBaseDelayMillis.toLong,
      settings.retryMaxDelayMillis.toLong
    )
  private val limiter = new RateLimiter(settings.requestsPerMinute, Int.MaxValue, clock, sleeper)

  def complete(r: ModelRequest): Either[ModelFailure, ModelReply] = {
    val body = AnthropicMessages.body(None, r, settings.maxOutputTokens, bedrock = true)
    val res = Retry.run(policy, sleeper) {
      limiter.acquire(0L)
      usage.requests += 1
      invoker.invoke(modelId, body)
    }
    usage.retries += res.retries
    res.result
      .flatMap(b => AnthropicMessages.parse(b).left.map(new StoreException(_, false)))
      .map { reply =>
        reply.inputTokens.foreach(usage.inputTokens += _)
        reply.outputTokens.foreach(usage.outputTokens += _)
        reply
      }
      .left
      .map(e => ModelFailure(s"bedrock: ${e.getMessage}".take(300), res.retries))
  }
}

object BedrockClaudeClient {

  /** The Bedrock model ID for `models.answerModelId` (decision 7: `anthropic.claude-haiku-5-5`). */
  def bedrockModelId(answerModelId: String): String =
    if (answerModelId.startsWith("anthropic.")) answerModelId else s"anthropic.$answerModelId"
}

/** Builds the model of `llm.provider`. Network clients are created only for the selected provider;
  * creating one makes no request.
  */
object LlmFactory {
  final case class Wiring(
      env: String => Option[String] = sys.env.get,
      sleeper: Sleeper = Sleeper.real,
      clock: () => Long = () => System.currentTimeMillis(),
      httpTransport: LlmSettings => HttpTransport = s =>
        new JdkHttpTransport(ConnectTimeoutMillis, s.requestTimeoutMillis),
      bedrockInvoker: AwsClientSettings => BedrockInvoker = SdkBedrockInvoker.create(_)
  )
  val ConnectTimeoutMillis = 10000

  def create(
      config: ScholarChainConfig,
      planner: RulePlanner,
      wiring: Wiring = Wiring()
  ): Either[String, LanguageModel] =
    for {
      s <- config.llm.toRight("llm section is not configured")
      p <- s.providerKind
      m <- p match {
        case LlmProvider.Fake      => Right(new FakeModel(planner))
        case LlmProvider.Anthropic =>
          AnthropicClient.create(
            config.models.answerModelId,
            s,
            wiring.env,
            wiring.httpTransport(s),
            wiring.sleeper,
            wiring.clock
          )
        case LlmProvider.Bedrock =>
          val aws = AwsClientSettings(
            config.aws.region,
            ConnectTimeoutMillis,
            s.requestTimeoutMillis,
            s.requestTimeoutMillis,
            maxAttempts = 1
          )
          Right(
            new BedrockClaudeClient(
              BedrockClaudeClient.bedrockModelId(config.models.answerModelId),
              s,
              wiring.bedrockInvoker(aws),
              wiring.sleeper,
              wiring.clock
            )
          )
      }
    } yield m
}
