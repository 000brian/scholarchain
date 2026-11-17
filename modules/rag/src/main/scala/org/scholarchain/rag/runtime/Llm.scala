package org.scholarchain.rag.runtime

import java.io.IOException
import java.net.URI
import java.net.http.HttpTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}

import scala.collection.immutable.ListMap
import scala.jdk.CollectionConverters._
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
import org.scholarchain.storage.{Retry, RetryPolicy, Sleeper}
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

/** One model reply. `stopReason` is the provider's `stop_reason` (`end_turn`, `max_tokens`, ...);
  * None when the response has no such field (recorded as [[StopReasons.Unknown]]).
  */
final case class ModelReply(
    text: String,
    inputTokens: Option[Long],
    outputTokens: Option[Long],
    stopReason: Option[String] = None
)

/** A logical model call that failed after the client's bounded retries. `cause` is one of
  * `http-<status>`, [[ModelFailure.Timeout]], [[ModelFailure.Transport]],
  * [[ModelFailure.MalformedResponse]] (or [[ModelFailure.Halted]] when the early stop refused the
  * call without sending it).
  */
final case class ModelFailure(message: String, retries: Int, cause: String = ModelFailure.Transport)

object ModelFailure {
  val Timeout = "timeout"
  val Transport = "transport"
  val MalformedResponse = "malformed-response"

  /** Refused without a request because the early stop ([[LlmUsage.armEarlyStop]]) fired. */
  val Halted = "halted-after-early-stop"

  def http(status: Int): String = s"http-$status"
  private val HttpCause = "http-([0-9]{3})".r

  /** The HTTP status of an `http-<status>` cause. */
  def httpStatus(cause: String): Option[Int] = cause match {
    case HttpCause(s) => Some(s.toInt)
    case _            => None
  }

  /** A non-retryable 4xx: 400..499 except 429 (rate limit, retried by the clients). */
  def isNonRetryable4xx(cause: String): Boolean =
    httpStatus(cause).exists(s => s >= 400 && s < 500 && s != 429)
}

object StopReasons {
  val Unknown = "unknown"
  val MaxTokens = "max_tokens"

  /** The validation error recorded for an invalid output cut off at the output token limit. */
  val TruncatedError = "truncated at max_tokens"
}

/** A name-to-counter map with atomic increments (keys are fixed vocabularies, never model text). */
final class CounterMap {
  private val m = new ConcurrentHashMap[String, AtomicLong]()
  def add(key: String, n: Long = 1L): Unit =
    m.computeIfAbsent(key, _ => new AtomicLong(0L)).addAndGet(n): Unit
  def snapshot: ListMap[String, Long] =
    ListMap.from(m.asScala.toList.map { case (k, v) => k -> v.get }.sortBy(_._1))
  def total: Long = m.values.asScala.map(_.get).sum
  def toJson: ujson.Obj =
    ujson.Obj.from(snapshot.toList.map { case (k, v) => k -> ujson.Num(v.toDouble) })
}

/** R-5 counters of one task (planner or synthesis). A logical call is one [[LanguageModel.invoke]]
  * (one `complete`, including the client's own retries); HTTP attempts and retried attempts are
  * counted by the network clients. Every field is an `AtomicLong`, so one model can serve queries
  * on several threads.
  */
final class TaskCallCounters(val task: ModelTask) {
  val logicalCalls = new AtomicLong(0L)
  val succeeded = new AtomicLong(0L)
  val failed = new AtomicLong(0L)
  val failedByCause = new CounterMap

  /** Requests sent by a network client (the fake model sends none). */
  val httpAttempts = new AtomicLong(0L)

  /** HTTP attempts that failed transiently and were retried. */
  val retriedAttempts = new AtomicLong(0L)

  /** Outputs that failed schema validation (one per invalid structured attempt). */
  val invalidOutputs = new AtomicLong(0L)

  /** Replies whose stop reason is `max_tokens` (valid or not). */
  val truncatedOutputs = new AtomicLong(0L)
  val stopReasons = new CounterMap

  /** Bound numbers dropped from valid outputs (answer-claims v3), by failure class (L-1). */
  val droppedBoundNumbers = new CounterMap

  /** Bound numbers that made an output invalid (answer-claims v2), by failure class (L-1). */
  val boundNumberErrors = new CounterMap

  /** Deterministic fallbacks by kind: [[TaskCallCounters.CallFailed]],
    * [[TaskCallCounters.OutputInvalid]], [[TaskCallCounters.PromptRender]].
    */
  val fallbacks = new CounterMap
  val inputTokens = new AtomicLong(0L)
  val outputTokens = new AtomicLong(0L)

  def toJson: ujson.Obj = ujson.Obj(
    "logicalCalls" -> ujson.Obj(
      "attempted" -> logicalCalls.get.toDouble,
      "succeeded" -> succeeded.get.toDouble,
      "failed" -> failed.get.toDouble,
      "failedByCause" -> failedByCause.toJson
    ),
    "httpAttempts" -> httpAttempts.get.toDouble,
    "retriedAttempts" -> retriedAttempts.get.toDouble,
    "invalidOutputs" -> invalidOutputs.get.toDouble,
    "truncatedOutputs" -> truncatedOutputs.get.toDouble,
    "stopReasons" -> stopReasons.toJson,
    "droppedBoundNumbers" -> droppedBoundNumbers.toJson,
    "boundNumberErrors" -> boundNumberErrors.toJson,
    "fallbacks" -> ujson.Obj.from(
      List(
        TaskCallCounters.CallFailed,
        TaskCallCounters.OutputInvalid,
        TaskCallCounters.PromptRender
      )
        .map(k => k -> ujson.Num(fallbacks.snapshot.getOrElse(k, 0L).toDouble))
    ),
    "inputTokens" -> inputTokens.get.toDouble,
    "outputTokens" -> outputTokens.get.toDouble
  )
}

object TaskCallCounters {
  val CallFailed = "call-failed"
  val OutputInvalid = "output-invalid"
  val PromptRender = "prompt-render"
}

/** Usage counters of one model client (never holds text): R-5 per-task counters plus the record of
  * the first logical calls for the early stop. Safe to update from several threads.
  */
final class LlmUsage {
  val planner = new TaskCallCounters(ModelTask.Planner)
  val synthesis = new TaskCallCounters(ModelTask.Synthesis)
  def of(task: ModelTask): TaskCallCounters = task match {
    case ModelTask.Planner   => planner
    case ModelTask.Synthesis => synthesis
  }
  def tasks: List[TaskCallCounters] = List(planner, synthesis)

  // outcome (None = success, Some(cause) = failure) of the first EarlyStopCalls logical calls
  private val first = scala.collection.mutable.ListBuffer.empty[Option[String]]
  private val armed = new AtomicBoolean(false)

  /** Turns on the early stop: once [[earlyStopStatus]] is defined, [[LanguageModel.invoke]] refuses
    * every further call without sending it. Used by live benchmark runs; services do not arm it.
    */
  def armEarlyStop(): Unit = armed.set(true)

  /** One logical call and its outcome (called by [[LanguageModel.invoke]]). */
  def recordCall(task: ModelTask, result: Either[ModelFailure, ModelReply]): Unit = {
    val c = of(task)
    c.logicalCalls.incrementAndGet()
    result match {
      case Right(r) =>
        c.succeeded.incrementAndGet()
        val stop = r.stopReason.filter(_.nonEmpty).getOrElse(StopReasons.Unknown)
        c.stopReasons.add(stop)
        if (stop == StopReasons.MaxTokens) c.truncatedOutputs.incrementAndGet()
        r.inputTokens.foreach(c.inputTokens.addAndGet)
        r.outputTokens.foreach(c.outputTokens.addAndGet)
      case Left(f) =>
        c.failed.incrementAndGet()
        c.failedByCause.add(f.cause)
    }
    first.synchronized {
      if (first.size < LlmUsage.EarlyStopCalls) first += result.left.toOption.map(_.cause)
    }
  }

  def recordHttpAttempt(task: ModelTask): Unit = { of(task).httpAttempts.incrementAndGet(); () }
  def recordRetries(task: ModelTask, n: Long): Unit = { of(task).retriedAttempts.addAndGet(n); () }
  def recordInvalidOutput(task: ModelTask): Unit = { of(task).invalidOutputs.incrementAndGet(); () }
  def recordFallback(task: ModelTask, kind: String): Unit = of(task).fallbacks.add(kind)

  /** Failure classes ([[NumberFormats.Classes]]) of the bound numbers one output dropped (v3) or
    * was rejected for (v2).
    */
  def recordNumbers(task: ModelTask, dropped: List[String], errors: List[String]): Unit = {
    val c = of(task)
    dropped.foreach(c.droppedBoundNumbers.add(_))
    errors.foreach(c.boundNumberErrors.add(_))
  }

  /** The HTTP status when the first [[LlmUsage.EarlyStopCalls]] logical calls all failed with the
    * same non-retryable 4xx (not 429).
    */
  def earlyStopStatus: Option[Int] = first.synchronized {
    val causes = first.toList
    if (causes.size < LlmUsage.EarlyStopCalls) None
    else
      causes.flatten match {
        case cs @ (c :: _)
            if cs.size == LlmUsage.EarlyStopCalls && cs.forall(_ == c) &&
              ModelFailure.isNonRetryable4xx(c) =>
          ModelFailure.httpStatus(c)
        case _ => None
      }
  }

  /** True when the early stop is armed and has fired. */
  def halted: Boolean = armed.get && earlyStopStatus.isDefined

  /** Logical calls that failed after their retries, all tasks. */
  def failedCalls: Long = tasks.map(_.failed.get).sum

  /** Failed logical calls by cause, all tasks. */
  def failedCauses: ListMap[String, Long] =
    ListMap.from(
      tasks
        .flatMap(_.failedByCause.snapshot.toList)
        .groupMapReduce(_._1)(_._2)(_ + _)
        .toList
        .sortBy(_._1)
    )

  /** Logical calls plus retried attempts (for a network client: its requests). */
  def requests: Long = tasks.map(t => t.logicalCalls.get + t.retriedAttempts.get).sum
  def retries: Long = tasks.map(_.retriedAttempts.get).sum
  def inputTokens: Long = tasks.map(_.inputTokens.get).sum
  def outputTokens: Long = tasks.map(_.outputTokens.get).sum

  /** Totals (the Phase 9 `llmUsage` shape). */
  def toJson: ujson.Obj = ujson.Obj(
    "requests" -> requests.toDouble,
    "retries" -> retries.toDouble,
    "inputTokens" -> inputTokens.toDouble,
    "outputTokens" -> outputTokens.toDouble
  )

  /** The run report's `modelCalls` section: per task and in total. */
  def modelCallsJson: ujson.Obj = ujson.Obj(
    "planner" -> planner.toJson,
    "synthesis" -> synthesis.toJson,
    "total" -> ujson.Obj(
      "logicalCalls" -> tasks.map(_.logicalCalls.get).sum.toDouble,
      "succeeded" -> tasks.map(_.succeeded.get).sum.toDouble,
      "failed" -> failedCalls.toDouble,
      "failedByCause" -> ujson.Obj.from(failedCauses.toList.map { case (k, v) =>
        k -> ujson.Num(v.toDouble)
      }),
      "httpAttempts" -> tasks.map(_.httpAttempts.get).sum.toDouble,
      "retriedAttempts" -> retries.toDouble,
      "invalidOutputs" -> tasks.map(_.invalidOutputs.get).sum.toDouble,
      "truncatedOutputs" -> tasks.map(_.truncatedOutputs.get).sum.toDouble,
      "droppedBoundNumbers" -> tasks.map(_.droppedBoundNumbers.total).sum.toDouble,
      "boundNumberErrors" -> tasks.map(_.boundNumberErrors.total).sum.toDouble,
      "fallbacks" -> tasks.map(_.fallbacks.total).sum.toDouble,
      "inputTokens" -> inputTokens.toDouble,
      "outputTokens" -> outputTokens.toDouble
    ),
    "earlyStopArmed" -> armed.get,
    "earlyStopStatus" -> earlyStopStatus.map(s => ujson.Num(s.toDouble)).getOrElse(ujson.Null)
  )

  /** `failed: <n> model calls failed (<cause>: <count>, ...)` when any logical call failed. */
  def failureStatus: Option[String] =
    if (failedCalls == 0L) None
    else
      Some(
        s"failed: $failedCalls model calls failed (${failedCauses.map { case (k, v) => s"$k: $v" }.mkString(", ")})"
      )

  /** `stopped: first 3 model calls rejected with HTTP <status>` when the early stop condition
    * holds.
    */
  def earlyStopReason: Option[String] =
    earlyStopStatus.map(s => s"first ${LlmUsage.EarlyStopCalls} model calls rejected with HTTP $s")
}

object LlmUsage {

  /** The early stop looks at this many first logical calls (decision 5b). */
  val EarlyStopCalls = 3
}

/** A text model behind the `llm.provider` switch. Implementations must be safe to call from several
  * threads (the clients here are: their only shared state is atomic counters and a synchronized
  * rate limiter).
  */
trait LanguageModel {
  def provider: LlmProvider
  def modelId: String
  def usage: LlmUsage

  /** One request, including the client's own bounded retries. Stages call [[invoke]]. */
  def complete(request: ModelRequest): Either[ModelFailure, ModelReply]

  /** One logical call: [[complete]] plus the R-5 counting in [[usage]]. When the armed early stop
    * has fired, the call is refused without a request (cause [[ModelFailure.Halted]], not counted).
    */
  final def invoke(request: ModelRequest): Either[ModelFailure, ModelReply] =
    if (usage.halted)
      Left(ModelFailure("model calls stopped by the early stop", 0, ModelFailure.Halted))
    else {
      val result = complete(request)
      usage.recordCall(request.task, result)
      result
    }
}

/** The `fake` provider: deterministic. The planner side runs [[RulePlanner]]; the synthesis side
  * renders claims from the facts table with fixed templates ([[FakeSynthesis]], within the limits
  * of the claims schema in use). Its output is JSON text and goes through the same validation as a
  * live model's. It reports no stop reason and no tokens.
  */
final class FakeModel(planner: RulePlanner) extends LanguageModel {
  val provider: LlmProvider = LlmProvider.Fake
  val modelId: String = FakeModel.ModelId
  val usage = new LlmUsage
  def complete(r: ModelRequest): Either[ModelFailure, ModelReply] =
    r.fake match {
      case PlannerFakeInput(q)   => Right(ModelReply(planner.draft(q).toJson.render(), None, None))
      case SynthesisFakeInput(i) =>
        Right(ModelReply(FakeSynthesis.render(i).toJson.render(), None, None))
    }
}

object FakeModel {
  val ModelId = "fake-rule-template-v1"
}

/** Messages API request and response shape. The request fields (model, max_tokens, system,
  * messages) and the response fields (`content[].text`, `usage.input_tokens`,
  * `usage.output_tokens`) were verified live by the human architect on 2026-11-15; `stop_reason` is
  * read when present (Bedrock returns the same body).
  */
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

  /** `content[].text` (text blocks joined), `usage.input_tokens` / `usage.output_tokens` and
    * `stop_reason` (a missing or non-string value is None, recorded as `unknown`; only a short
    * token of `[a-z_]` is kept, never other text).
    */
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
      val stop = o.get("stop_reason").flatMap(JsonSupport.str).filter(_.matches("[a-z_]{1,40}"))
      if (blocks.isEmpty) Left("response has no content blocks")
      else Right(ModelReply(text, tok("input_tokens"), tok("output_tokens"), stop))
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
    // (transient, message, Retry-After seconds, cause)
    type Failed = (Boolean, String, Option[Long], String)
    while (out.isEmpty) {
      limiter.acquire(0L)
      usage.recordHttpAttempt(r.task)
      val attempt: Either[Failed, HttpResult] =
        try Right(transport.postJson(endpoint, headers, body))
        catch {
          case _: HttpTimeoutException =>
            Left((true, "anthropic request timed out", None, ModelFailure.Timeout))
          case e: IOException =>
            Left(
              (
                true,
                s"anthropic transport error: ${e.getClass.getSimpleName}",
                None,
                ModelFailure.Transport
              )
            )
          case NonFatal(e) =>
            Left(
              (
                false,
                s"anthropic client error: ${e.getClass.getSimpleName}",
                None,
                ModelFailure.Transport
              )
            )
        }
      val outcome: Either[Failed, ModelReply] = attempt.flatMap { h =>
        if (h.status == 200)
          AnthropicMessages
            .parse(h.body)
            .left
            .map(m => (false, s"anthropic: $m", None, ModelFailure.MalformedResponse))
        else
          Left(
            (
              AnthropicClient.transient(h.status),
              // C-02: the status and the provider's short error type only, never the body text
              s"anthropic HTTP ${h.status}${AnthropicClient.errorType(h.body).map(t => s" ($t)").getOrElse("")}",
              h.retryAfterSeconds,
              ModelFailure.http(h.status)
            )
          )
      }
      outcome match {
        case Right(reply) => out = Some(Right(reply))
        case Left((true, _, retryAfter, _)) if retries < policy.maxRetries =>
          retries += 1
          usage.recordRetries(r.task, 1L)
          val hinted = retryAfter.map(s => math.min(policy.maxDelayMillis, s * 1000L)).getOrElse(0L)
          sleeper.sleep(math.max(policy.delayBeforeRetry(retries), hinted))
        case Left((_, msg, _, cause)) => out = Some(Left(ModelFailure(scrub(msg), retries, cause)))
      }
    }
    out.get
  }
}

object AnthropicClient {

  /** 429 (rate limit), 529 (overloaded) and other 5xx are retried; everything else fails at once.
    */
  def transient(status: Int): Boolean = status == 429 || status == 529 || status >= 500

  /** The `error.type` token of an error body (`rate_limit_error`, ...) when it is a short `[a-z_]`
    * word; nothing else of the body is ever kept (C-02).
    */
  def errorType(body: String): Option[String] =
    try
      ujson
        .read(body)
        .obj
        .get("error")
        .flatMap(_.obj.get("type"))
        .flatMap(JsonSupport.str)
        .filter(_.matches("[a-z_]{1,40}"))
    catch { case NonFatal(_) => None }

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
) extends LanguageModel
    with AutoCloseable {
  val provider: LlmProvider = LlmProvider.Bedrock
  val usage = new LlmUsage

  /** Closes the invoker when it is closeable (the SDK invoker holds a Bedrock Runtime client), so
    * the runtime that owns this model releases it (U-5).
    */
  def close(): Unit = invoker match {
    case c: AutoCloseable => c.close()
    case _                => ()
  }
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
      usage.recordHttpAttempt(r.task)
      invoker.invoke(modelId, body)
    }
    usage.recordRetries(r.task, res.retries.toLong)
    res.result.left
      .map { e =>
        val cause = BedrockClaudeClient.causeOf(e)
        // C-02: the cause code and exception class only, never the SDK message (ARNs, accounts)
        ModelFailure(
          s"bedrock call failed ($cause, ${e.getClass.getSimpleName})",
          res.retries,
          cause
        )
      }
      .flatMap(b =>
        AnthropicMessages
          .parse(b)
          .left
          .map(m =>
            ModelFailure(s"bedrock: $m".take(300), res.retries, ModelFailure.MalformedResponse)
          )
      )
  }
}

object BedrockClaudeClient {
  private val HttpStatus = "\\(HTTP ([0-9]{3})\\)".r

  /** The R-5 cause of a Bedrock failure: `http-<status>` from storage's wrapped SDK message, else
    * `timeout` for a timeout class, else `transport`.
    */
  def causeOf(e: Throwable): String = {
    val msg = Option(e.getMessage).getOrElse("")
    HttpStatus.findFirstMatchIn(msg) match {
      case Some(m) => ModelFailure.http(m.group(1).toInt)
      case None if msg.toLowerCase(java.util.Locale.ROOT).contains("timeout") =>
        ModelFailure.Timeout
      case None => ModelFailure.Transport
    }
  }

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
