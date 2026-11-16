package org.scholarchain.rag.runtime

import java.io.IOException
import java.net.URI
import java.net.http.HttpTimeoutException
import java.util.concurrent.atomic.AtomicInteger

import org.scholarchain.core._
import org.scholarchain.core.config.LlmSettings
import org.scholarchain.rag.embedding.{HttpResult, HttpTransport}
import org.scholarchain.storage.{Sleeper, StoreException}

/** Decision 5b: R-5 per-task model-call counters, stop reasons, truncation, the early stop, and the
  * run status rules (the benchmark-run side is in BenchmarkRunnerSuite).
  */
class ModelCallsSuite extends munit.FunSuite {
  import RuntimeFixtures._

  private val ctx = RuntimeContext.from(config, sparkRun, Set(Full)).toOption.get
  private val rule = new RulePlanner(ctx.windows, ctx.bounds)
  private lazy val prompts = Prompts.locate().flatMap(Prompts.loadSet).fold(e => fail(e), identity)
  private val q = "Which researchers have the most distinct coauthors in 2015-2025?"
  private val goodPlan = rule.draft(q).toJson.render()

  private val Key = "placeholder-not-a-real-key"
  private val settings =
    LlmSettings(
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

  private def body(text: String, stop: Option[String], in: Long = 10, out: Long = 5) =
    ujson.Obj
      .from(
        List(
          "content" -> ujson.Arr(ujson.Obj("type" -> "text", "text" -> text)),
          "usage" -> ujson.Obj("input_tokens" -> in, "output_tokens" -> out)
        ) ++ stop.map(s => "stop_reason" -> ujson.Str(s))
      )
      .render()

  private final class Transport(respond: Int => Either[IOException, HttpResult])
      extends HttpTransport {
    val calls = new AtomicInteger()
    def postJson(uri: URI, headers: List[(String, String)], b: String): HttpResult =
      respond(calls.incrementAndGet()).fold(e => throw e, identity)
  }
  private def client(t: HttpTransport) =
    AnthropicClient
      .create(
        "claude-haiku-5-5",
        settings,
        Map("SCHOLARCHAIN_TEST_LLM_KEY" -> Key).get,
        t,
        Sleeper.none,
        () => 0L
      )
      .fold(e => fail(e), identity)
  private val req =
    ModelRequest(ModelTask.Planner, "s", "u", 512, PlannerFakeInput(q))

  test("anthropic: stop_reason is read; a missing one is unknown on the attempt") {
    val t = new Transport(_ => Right(HttpResult(200, body(goodPlan, Some("end_turn")), None)))
    assertEquals(client(t).complete(req).map(_.stopReason), Right(Some("end_turn")))
    val none = new Transport(_ => Right(HttpResult(200, body(goodPlan, None), None)))
    val m = client(none)
    assertEquals(m.complete(req).map(_.stopReason), Right(None))
    val out = new QueryPlanner(m, prompts.planner, rule, ctx).plan(q)
    assertEquals(out.attempts.map(_.stopReason), List(StopReasons.Unknown))
    assertEquals(out.attempts.head.toJson("stopReason").str, "unknown")
    assertEquals(m.usage.planner.stopReasons.snapshot.toMap, Map("unknown" -> 1L))
  }

  test("invalid output with stop_reason max_tokens gets the error 'truncated at max_tokens'") {
    val cut = "{\"operations\": [\"researcher_ranking\"], \"researcherNa"
    val m = new ScriptedModel(List(Right(cut)), stopReason = Some("max_tokens"))
    val out = new QueryPlanner(m, prompts.planner, rule, ctx).plan(q)
    assertEquals(out.attempts.map(_.error), List.fill(2)(Some(StopReasons.TruncatedError)))
    assertEquals(out.attempts.map(_.stopReason).distinct, List("max_tokens"))
    assert(out.fallbackReason.exists(_.contains("truncated at max_tokens")))
    assertEquals(m.usage.planner.truncatedOutputs.get, 2L)
    assertEquals(m.usage.planner.invalidOutputs.get, 2L)
    // the same invalid output with another stop reason keeps the validator's error
    val m2 = new ScriptedModel(List(Right(cut)), stopReason = Some("end_turn"))
    val out2 = new QueryPlanner(m2, prompts.planner, rule, ctx).plan(q)
    assert(out2.attempts.forall(!_.error.contains(StopReasons.TruncatedError)))
    assertEquals(m2.usage.planner.truncatedOutputs.get, 0L)
  }

  test("per-task counters: logical calls, failures by cause, invalid outputs, fallbacks, tokens") {
    // planner: one invalid then one valid; synthesis: one failed call
    val m = new ScriptedModel(
      List(Right("{}"), Right(goodPlan), Left(ModelFailure("x", 2, ModelFailure.http(503))))
    )
    new QueryPlanner(m, prompts.planner, rule, ctx).plan(q)
    m.invoke(ModelRequest(ModelTask.Synthesis, "s", "u", 10, PlannerFakeInput(q)))
    val p = m.usage.planner
    val s = m.usage.synthesis
    assertEquals(
      (p.logicalCalls.get, p.succeeded.get, p.failed.get, p.invalidOutputs.get),
      (2L, 2L, 0L, 1L)
    )
    assertEquals((p.inputTokens.get, p.outputTokens.get), (200L, 40L))
    assertEquals(p.fallbacks.total, 0L)
    assertEquals((s.logicalCalls.get, s.failed.get), (1L, 1L))
    assertEquals(s.failedByCause.snapshot.toMap, Map("http-503" -> 1L))
    assertEquals(m.usage.failedCalls, 1L)
    assertEquals(m.usage.failureStatus, Some("failed: 1 model calls failed (http-503: 1)"))
    val json = m.usage.modelCallsJson
    assertEquals(json("planner")("logicalCalls")("attempted").num, 2.0)
    assertEquals(json("synthesis")("logicalCalls")("failedByCause")("http-503").num, 1.0)
    assertEquals(json("total")("failed").num, 1.0)
  }

  test("fallbacks are counted by kind: call-failed versus output-invalid") {
    val failed = new ScriptedModel(List(Left(ModelFailure("x", 0, ModelFailure.Timeout))))
    new QueryPlanner(failed, prompts.planner, rule, ctx).plan(q)
    assertEquals(
      failed.usage.planner.fallbacks.snapshot.toMap,
      Map(TaskCallCounters.CallFailed -> 1L)
    )
    val invalid = new ScriptedModel(List(Right("not json")))
    new QueryPlanner(invalid, prompts.planner, rule, ctx).plan(q)
    assertEquals(
      invalid.usage.planner.fallbacks.snapshot.toMap,
      Map(TaskCallCounters.OutputInvalid -> 1L)
    )
    assertEquals(invalid.usage.failureStatus, None, "invalid-output fallbacks are not failures")
  }

  test("anthropic: HTTP attempts and retried attempts per task; failure causes") {
    // two transient failures, then success: 3 attempts, 2 retries, 1 successful logical call
    val t = new Transport(n =>
      if (n < 3) Right(HttpResult(529, "{}", None))
      else Right(HttpResult(200, body(goodPlan, Some("end_turn")), None))
    )
    val c = client(t)
    assert(c.invoke(req).isRight)
    val p = c.usage.planner
    assertEquals(
      (p.httpAttempts.get, p.retriedAttempts.get, p.logicalCalls.get, p.failed.get),
      (3L, 2L, 1L, 0L)
    )
    assertEquals(c.usage.failureStatus, None, "a retried call that succeeded does not fail a run")
    def causeOf(r: Int => Either[IOException, HttpResult]) =
      client(new Transport(r)).complete(req).left.map(_.cause)
    assertEquals(causeOf(_ => Right(HttpResult(401, "{}", None))), Left("http-401"))
    assertEquals(causeOf(_ => Right(HttpResult(500, "{}", None))), Left("http-500"))
    assertEquals(causeOf(_ => Left(new HttpTimeoutException("t"))), Left(ModelFailure.Timeout))
    assertEquals(causeOf(_ => Left(new IOException("reset"))), Left(ModelFailure.Transport))
    assertEquals(
      causeOf(_ => Right(HttpResult(200, "<html>", None))),
      Left(ModelFailure.MalformedResponse)
    )
  }

  test("bedrock: failure causes from storage's wrapped SDK message") {
    assertEquals(
      BedrockClaudeClient.causeOf(
        new StoreException("AccessDeniedException (HTTP 403): no", false)
      ),
      "http-403"
    )
    assertEquals(
      BedrockClaudeClient.causeOf(new StoreException("ApiCallTimeoutException: x", true)),
      ModelFailure.Timeout
    )
    assertEquals(
      BedrockClaudeClient.causeOf(new StoreException("SdkClientException: y", true)),
      ModelFailure.Transport
    )
  }

  private def usageWith(causes: List[Option[String]]): LlmUsage = {
    val u = new LlmUsage
    causes.foreach {
      case None    => u.recordCall(ModelTask.Planner, Right(ModelReply("{}", None, None)))
      case Some(c) => u.recordCall(ModelTask.Planner, Left(ModelFailure("x", 0, c)))
    }
    u
  }

  test("early stop: only when the first 3 logical calls fail with the same non-retryable 4xx") {
    assertEquals(usageWith(List.fill(3)(Some("http-401"))).earlyStopStatus, Some(401))
    assertEquals(
      usageWith(List.fill(3)(Some("http-401"))).earlyStopReason,
      Some("first 3 model calls rejected with HTTP 401")
    )
    assertEquals(usageWith(List.fill(2)(Some("http-401"))).earlyStopStatus, None)
    assertEquals(usageWith(List.fill(3)(Some("http-429"))).earlyStopStatus, None, "429")
    assertEquals(usageWith(List.fill(3)(Some("http-500"))).earlyStopStatus, None, "5xx")
    assertEquals(usageWith(List.fill(3)(Some("timeout"))).earlyStopStatus, None)
    assertEquals(
      usageWith(List(Some("http-401"), Some("http-403"), Some("http-401"))).earlyStopStatus,
      None,
      "different statuses"
    )
    assertEquals(
      usageWith(List(None, Some("http-401"), Some("http-401"), Some("http-401"))).earlyStopStatus,
      None,
      "only the first three calls count"
    )
  }

  test("early stop: once armed and fired, invoke sends nothing more") {
    val m = new ScriptedModel(List(Left(ModelFailure("x", 0, ModelFailure.http(400)))))
    m.usage.armEarlyStop()
    (1 to 3).foreach(_ => m.invoke(req))
    assertEquals(m.requests.size, 3)
    val refused = m.invoke(req)
    assertEquals(refused.left.map(_.cause), Left(ModelFailure.Halted))
    assertEquals(m.requests.size, 3, "no request after the early stop")
    assertEquals(m.usage.planner.logicalCalls.get, 3L, "refused calls are not counted")
    // without arming, calls continue
    val n = new ScriptedModel(List(Left(ModelFailure("x", 0, ModelFailure.http(400)))))
    (1 to 4).foreach(_ => n.invoke(req))
    assertEquals(n.requests.size, 4)
  }

  test("counters are exact when one model serves many threads") {
    val m = new ScriptedModel(List(Right(goodPlan)))
    val threads = (1 to 8).map(_ => new Thread(() => (1 to 250).foreach(_ => m.invoke(req))))
    threads.foreach(_.start()); threads.foreach(_.join())
    assertEquals(m.usage.planner.logicalCalls.get, 2000L)
    assertEquals(m.usage.planner.succeeded.get, 2000L)
    assertEquals(m.usage.planner.inputTokens.get, 200000L)
    assertEquals(m.usage.planner.stopReasons.snapshot.toMap, Map("end_turn" -> 2000L))
  }

  test("the fake model counts logical calls but no HTTP attempts and no tokens") {
    val f = new FakeModel(rule)
    new QueryPlanner(f, prompts.planner, rule, ctx).plan(q)
    assertEquals(
      (f.usage.planner.logicalCalls.get, f.usage.planner.httpAttempts.get, f.usage.inputTokens),
      (1L, 0L, 0L)
    )
    assertEquals(f.usage.requests, 1L)
  }
}
