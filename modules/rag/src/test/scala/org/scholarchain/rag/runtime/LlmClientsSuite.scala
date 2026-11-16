package org.scholarchain.rag.runtime

import java.io.IOException
import java.net.URI

import scala.collection.mutable

import org.scholarchain.core.config.{LlmProvider, LlmSettings}
import org.scholarchain.rag.embedding.{BedrockInvoker, HttpResult, HttpTransport}
import org.scholarchain.storage.{Sleeper, StoreException}

/** The Anthropic and Bedrock clients against stub transports: request shape, status mapping,
  * bounded retries with Retry-After, the client-side limiter, and the key never in error text.
  */
class LlmClientsSuite extends munit.FunSuite {
  import RuntimeFixtures._

  private val Key = "sk-test-NOT-A-REAL-KEY-0123456789"
  private val settings = LlmSettings(
    "anthropic",
    512,
    1000,
    2,
    100,
    1000,
    50,
    1,
    12000,
    Some("SCHOLARCHAIN_TEST_LLM_KEY")
  )
  private val env: String => Option[String] = Map("SCHOLARCHAIN_TEST_LLM_KEY" -> Key).get

  final class StubTransport(responses: List[Either[IOException, HttpResult]])
      extends HttpTransport {
    val calls = mutable.ListBuffer.empty[(URI, List[(String, String)], String)]
    private var i = 0
    def postJson(uri: URI, headers: List[(String, String)], body: String): HttpResult = {
      calls += ((uri, headers, body))
      val r = responses(math.min(i, responses.size - 1)); i += 1
      r.fold(e => throw e, identity)
    }
  }
  final class RecordingSleeper extends Sleeper {
    val slept = mutable.ListBuffer.empty[Long]; def sleep(ms: Long): Unit = slept += ms
  }

  private val okBody = ujson
    .Obj(
      "id" -> "msg_test",
      "type" -> "message",
      "content" -> ujson.Arr(ujson.Obj("type" -> "text", "text" -> "{\"a\":1}")),
      "usage" -> ujson.Obj("input_tokens" -> 123, "output_tokens" -> 45),
      "stop_reason" -> "end_turn"
    )
    .render()
  private val req =
    ModelRequest(ModelTask.Planner, "system text", "user text", 2048, PlannerFakeInput("q"))

  private def client(t: StubTransport, s: Sleeper = Sleeper.none, clock: () => Long = () => 0L) =
    AnthropicClient
      .create("claude-haiku-5-5", settings, env, t, s, clock)
      .fold(e => fail(e), identity)

  test("anthropic: Messages API request shape and response parsing") {
    val t = new StubTransport(List(Right(HttpResult(200, okBody, None))))
    val r = client(t).complete(req).fold(e => fail(e.message), identity)
    assertEquals((r.text, r.inputTokens, r.outputTokens), ("{\"a\":1}", Some(123L), Some(45L)))
    val (uri, headers, body) = t.calls.head
    assertEquals(uri.toString, "https://api.anthropic.com/v1/messages")
    assertEquals(headers.toMap, Map("x-api-key" -> Key, "anthropic-version" -> "2023-06-01"))
    val b = ujson.read(body)
    assertEquals(b("model").str, "claude-haiku-5-5")
    assertEquals(b("max_tokens").num, 512.0, "min(llm.maxOutputTokens, request)")
    assertEquals(b("system").str, "system text")
    assertEquals(b("messages")(0)("role").str, "user")
    assertEquals(b("messages")(0)("content").str, "user text")
    assertEquals(b.obj.keySet.toSet, Set("model", "max_tokens", "system", "messages"))
  }

  private val SamplingFields = Set("temperature", "top_p", "top_k")

  test("R-4: neither the Anthropic nor the Bedrock body sends temperature, top_p or top_k") {
    val t = new StubTransport(List(Right(HttpResult(200, okBody, None))))
    assert(client(t).complete(req).isRight)
    val anth = ujson.read(t.calls.head._3).obj.keySet.toSet
    assertEquals(anth & SamplingFields, Set.empty[String], s"anthropic body keys: $anth")
    assertEquals(anth, Set("model", "max_tokens", "system", "messages"))

    val inv = new StubInvoker(List(Right(okBody)))
    val bc = new BedrockClaudeClient(
      "anthropic.claude-haiku-5-5",
      settings.copy(provider = "bedrock"),
      inv,
      Sleeper.none,
      () => 0L
    )
    assert(bc.complete(req).isRight)
    val bed = ujson.read(inv.calls.head._2).obj.keySet.toSet
    assertEquals(bed & SamplingFields, Set.empty[String], s"bedrock body keys: $bed")
    assertEquals(bed, Set("anthropic_version", "max_tokens", "system", "messages"))

    List(true, false).foreach { bedrock =>
      val keys = ujson
        .read(AnthropicMessages.body(Some("m"), req, 512, bedrock))
        .obj
        .keySet
        .toSet
      assertEquals(keys & SamplingFields, Set.empty[String], s"body builder, bedrock=$bedrock")
    }
  }

  test("anthropic: 429, 529 and 5xx are retried with bounded backoff honouring Retry-After") {
    val s = new RecordingSleeper
    val t = new StubTransport(
      List(
        Right(HttpResult(429, "{}", Some(1))),
        Right(HttpResult(529, "{}", None)),
        Right(HttpResult(200, okBody, None))
      )
    )
    val c = client(t, s)
    assert(c.complete(req).isRight)
    assertEquals(t.calls.size, 3)
    assertEquals(
      s.slept.toList,
      List(1000L, 200L),
      "Retry-After 1 s (capped at retryMaxDelayMillis), then base*2"
    )
    assertEquals(c.usage.retries, 2L)
    val t2 = new StubTransport(List(Right(HttpResult(503, "{}", None))))
    val f = client(t2).complete(req)
    assert(f.isLeft)
    assertEquals(t2.calls.size, 1 + settings.maxRetries, "bounded")
    assertEquals(f.left.toOption.get.retries, settings.maxRetries)
  }

  test("anthropic: other statuses fail at once; transport errors are retried") {
    List(400, 401, 403, 404, 413).foreach { st =>
      val t = new StubTransport(List(Right(HttpResult(st, "{\"error\":{}}", None))))
      assert(client(t).complete(req).isLeft)
      assertEquals(t.calls.size, 1, s"status $st")
    }
    val t =
      new StubTransport(List(Left(new IOException("reset")), Right(HttpResult(200, okBody, None))))
    assert(client(t).complete(req).isRight)
    assertEquals(t.calls.size, 2)
  }

  test("anthropic: the key is never in error text, even when the provider echoes it") {
    val t = new StubTransport(List(Right(HttpResult(401, s"{\"error\":\"bad key $Key\"}", None))))
    val e = client(t).complete(req).left.toOption.get
    assert(!e.message.contains(Key), e.message)
    assert(e.message.contains("<redacted>"))
    assert(!client(new StubTransport(Nil)).toString.contains(Key))
  }

  test("anthropic: the key comes only from the variable named by llm.apiKeyEnvVar") {
    val noVar = AnthropicClient.create(
      "claude-haiku-5-5",
      settings,
      _ => None,
      new StubTransport(Nil),
      Sleeper.none,
      () => 0L
    )
    assertEquals(
      noVar.left.toOption,
      Some("environment variable SCHOLARCHAIN_TEST_LLM_KEY is not set")
    )
    val noName = AnthropicClient.create(
      "claude-haiku-5-5",
      settings.copy(apiKeyEnvVar = None),
      env,
      new StubTransport(Nil),
      Sleeper.none,
      () => 0L
    )
    assert(noName.isLeft)
  }

  test("anthropic: the client-side limiter holds llm.requestsPerMinute") {
    var now = 0L
    val s = new Sleeper { def sleep(ms: Long): Unit = now += ms }
    val t = new StubTransport(List(Right(HttpResult(200, okBody, None))))
    val c = AnthropicClient
      .create("claude-haiku-5-5", settings.copy(requestsPerMinute = 2), env, t, s, () => now)
      .toOption
      .get
    (1 to 3).foreach(_ => c.complete(req))
    assertEquals(now, 60000L, "the third request waits for the next minute")
  }

  test("anthropic: a malformed 200 body is a failure, not text") {
    val t = new StubTransport(List(Right(HttpResult(200, "<html>", None))))
    assert(client(t).complete(req).isLeft)
  }

  final class StubInvoker(answers: List[Either[StoreException, String]]) extends BedrockInvoker {
    val calls = mutable.ListBuffer.empty[(String, String)]
    private var i = 0
    def invoke(modelId: String, jsonBody: String): String = {
      calls += ((modelId, jsonBody))
      val a = answers(math.min(i, answers.size - 1)); i += 1
      a.fold(e => throw e, identity)
    }
  }

  test("bedrock: InvokeModel body, throttling retried, permanent errors not") {
    val inv =
      new StubInvoker(List(Left(new StoreException("ThrottlingException", true)), Right(okBody)))
    val c = new BedrockClaudeClient(
      "anthropic.claude-haiku-5-5",
      settings.copy(provider = "bedrock"),
      inv,
      Sleeper.none,
      () => 0L
    )
    val r = c.complete(req).fold(e => fail(e.message), identity)
    assertEquals(r.inputTokens, Some(123L))
    assertEquals(inv.calls.size, 2)
    assertEquals(inv.calls.head._1, "anthropic.claude-haiku-5-5")
    val b = ujson.read(inv.calls.head._2)
    assertEquals(b("anthropic_version").str, "bedrock-2023-05-31")
    assert(!b.obj.contains("model"))
    assertEquals(b.obj.keySet.toSet, Set("anthropic_version", "max_tokens", "system", "messages"))
    val perm = new StubInvoker(List(Left(new StoreException("AccessDeniedException", false))))
    assert(
      new BedrockClaudeClient("anthropic.claude-haiku-5-5", settings, perm, Sleeper.none, () => 0L)
        .complete(req)
        .isLeft
    )
    assertEquals(perm.calls.size, 1)
    assertEquals(
      BedrockClaudeClient.bedrockModelId("claude-haiku-5-5"),
      "anthropic.claude-haiku-5-5"
    )
  }

  test("the provider switch builds only the selected client, without any request") {
    val rule = new RulePlanner(
      RuntimeContext.from(config, sparkRun, Set(Full)).toOption.get.windows,
      PlanBounds(List(Full), 5, 100, 6)
    )
    assertEquals(LlmFactory.create(config, rule).map(_.provider), Right(LlmProvider.Fake))
    val anth = RagFixturesConfig.anthropic
    var built = 0
    val wiring = LlmFactory.Wiring(
      env = Map("SCHOLARCHAIN_ANTHROPIC_API_KEY" -> Key).get,
      httpTransport = _ => { built += 1; new StubTransport(Nil) }
    )
    val m = LlmFactory.create(anth, rule, wiring).fold(e => fail(e), identity)
    assertEquals((m.provider, m.modelId, built), (LlmProvider.Anthropic, "claude-haiku-5-5", 1))
    assert(
      LlmFactory
        .create(anth, rule, wiring.copy(env = _ => None))
        .left
        .exists(_.contains("SCHOLARCHAIN_ANTHROPIC_API_KEY"))
    )
  }

  test("the fake model is deterministic and its outputs validate") {
    val ctx = RuntimeContext.from(config, sparkRun, Set(Full)).toOption.get
    val fake = new FakeModel(new RulePlanner(ctx.windows, ctx.bounds))
    val q = PlannerFakeInput("Which researchers have the most distinct coauthors in 2015-2025?")
    val a = fake.complete(req.copy(fake = q)).toOption.get.text
    assertEquals(fake.complete(req.copy(fake = q)).toOption.get.text, a)
    assert(PlanDraftSchema.validate(a, ctx.bounds).isRight)
  }
}

object RagFixturesConfig {
  lazy val anthropic =
    org.scholarchain.rag.RagFixtures.loadConfig("/config/rag-runtime-anthropic-v1.json")
}
