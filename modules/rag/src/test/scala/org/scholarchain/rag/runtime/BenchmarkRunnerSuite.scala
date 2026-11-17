package org.scholarchain.rag.runtime

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import org.scholarchain.rag.benchmark.AnswerKeyGenerator
import org.scholarchain.rag.embedding.{HttpResult, HttpTransport}

/** The answer-key generator and the benchmark runner on the fixture export (fake model, local
  * stores). The key is computed from the export tables only.
  */
class BenchmarkRunnerSuite extends munit.FunSuite {
  import RuntimeFixtures._

  private def configPath(name: String): Path =
    Paths.get(getClass.getResource(s"/config/$name").toURI)
  private lazy val (exportDir, csvDir) = inputs()
  private lazy val key = AnswerKeyGenerator.generate(exportDir, 5000)

  test("answer key: computed from the export tables, independent of the runtime code") {
    val qs = key("questions").arr.map(q => q("id").str -> q).toMap
    // q01: ranking by distinct coauthors, ties by researcher ID
    val q01 = qs("q01")("expected")("values").arr
    assertEquals(q01.head("value").num, 3.0)
    assertEquals(q01.map(_("rank").num.toInt).toList, (1 to q01.size).toList)
    // precomputed Spark path A..D: 3 hops
    val spark = qs.values.filter(_("category").str == "shortest_path_precomputed").toList
    assert(spark.exists(_("expected")("path")("hops").num == 3.0), key("skipped").render())
    // the ambiguous name is Wei Wang with two candidates
    assertEquals(qs("q28")("question").str.contains("Wei Wang"), true)
    assertEquals(qs("q28")("expected")("candidateCount").num, 2.0)
    assertEquals(qs("q29")("expected")("identity").str, "unknown")
    assert(key("semanticQueries").arr.nonEmpty)
  }

  private def writeKey(root: Path): Path = {
    val p = root.resolve("benchmark-fixture.json")
    Files.write(p, key.render(indent = 2).getBytes(StandardCharsets.UTF_8))
    p
  }

  private def opts(root: Path, config: String = "rag-runtime-local-v1.json", llm: String = "fake") =
    BenchmarkRunner
      .parse(
        Array(
          "--config",
          configPath(config).toString,
          "--export",
          exportDir.toString,
          "--neptune-csv",
          csvDir.toString,
          "--questions",
          writeKey(root).toString,
          "--out",
          root.resolve("attempt-01").toString,
          "--llm",
          llm
        )
      )
      .fold(fail(_), identity)

  test(
    "benchmark runner (fake): completes, labels the report, and writes one result per question"
  ) {
    val root = Files.createTempDirectory("rag-bench")
    val o = opts(root)
    val r = BenchmarkRunner.run(o)
    assertEquals(r("status").str, "completed", r.render(indent = 2).take(4000))
    assertEquals(r("label").str, "local stores, fake embeddings")
    val res = r("results")
    val n = key("questions").arr.size
    assertEquals(res("questions").num.toInt, n)
    assertEquals(res("workflowErrors").num, 0.0)
    assertEquals(
      res("semanticRetrieval")("researcher_profile")("label").str,
      "fake lexical embedder, not semantic"
    )
    assertEquals(res("identityResolution")("silentChoicesOnAmbiguousNames").num, 0.0)
    assertEquals(res("answers")("missedAbstentions").arr.size, 0, res("answers").render())
    assertEquals(
      res("answers")("plantedErrors")("catchRate").num,
      1.0,
      res("answers")("plantedErrors").render()
    )
    // L-2: one record per planted case
    val cases = res("answers")("plantedErrors")("cases").arr
    assertEquals(cases.size, res("answers")("plantedErrors")("planted").num.toInt)
    val fields =
      Set("question", "claimId", "kind", "originalLiteral", "plantedLiteral", "status")
    assert(cases.forall(c => c.obj.keySet == fields), cases.take(2).toString)
    val years = cases.filter(_("kind").str == "wrong-window-year")
    assert(years.nonEmpty)
    assert(
      years.forall(c =>
        c("status").str != "verified" && c("originalLiteral") != c("plantedLiteral")
      )
    )
    assertEquals(
      res("deterministicGraphRetrieval")("exactMatchRate").num,
      1.0,
      res("deterministicGraphRetrieval").render(indent = 2).take(3000)
    )
    assert(r("runtimeEnvironment")("graphRows")("COAUTHORED_WITH")("kept").num > 0)
    assert(r.obj.contains("versions"))
    assertEquals(Files.list(o.out).toArray.count(_.toString.matches(".*/q-[0-9]{2}\\.json$")), n)
    // a second run into the same root is refused
    intercept[RuntimeException](BenchmarkRunner.run(o))
  }

  test("benchmark runner refuses a provider mismatch and anthropic without its key variable") {
    val local = BenchmarkRunner.loadConfig(configPath("rag-runtime-local-v1.json")).toOption.get
    val anth = BenchmarkRunner.loadConfig(configPath("rag-runtime-anthropic-v1.json")).toOption.get
    assert(
      BenchmarkRunner
        .preflight(local, "anthropic", _ => None)
        .left
        .exists(_.contains("does not match"))
    )
    assertEquals(
      BenchmarkRunner.preflight(anth, "anthropic", _ => None),
      Left("environment variable SCHOLARCHAIN_ANTHROPIC_API_KEY is not set; refusing to start")
    )
    assert(
      BenchmarkRunner
        .preflight(anth, "anthropic", Map("SCHOLARCHAIN_ANTHROPIC_API_KEY" -> "x").get)
        .isRight
    )
    assert(BenchmarkRunner.preflight(local, "fake", _ => None).isRight)
    val root = Files.createTempDirectory("rag-bench-anth")
    val r =
      BenchmarkRunner.run(opts(root, "rag-runtime-anthropic-v1.json", "anthropic"), env = _ => None)
    assert(r("status").str.contains("is not set; refusing to start"), r("status").str)
    assert(!r.obj.contains("runtimeEnvironment"), "nothing was loaded")
  }

  // ---- R-5: model-call counting and run status (stub transport, placeholder key) -------------

  private val placeholderEnv: String => Option[String] =
    Map("SCHOLARCHAIN_ANTHROPIC_API_KEY" -> "placeholder-not-a-key").get

  private final class StubTransport(respond: Int => HttpResult) extends HttpTransport {
    val calls = new java.util.concurrent.atomic.AtomicInteger()
    def postJson(uri: java.net.URI, headers: List[(String, String)], body: String): HttpResult =
      respond(calls.incrementAndGet())
  }

  /** A run with the anthropic v2 config against a stub transport, with a virtual clock. */
  private def liveRun(respond: Int => HttpResult): (ujson.Obj, StubTransport, Path) = {
    val root = Files.createTempDirectory("rag-bench-live")
    val t = new StubTransport(respond)
    var now = 0L
    val wiring = LlmFactory.Wiring(
      env = placeholderEnv,
      sleeper = (ms: Long) => now += ms,
      clock = () => now,
      httpTransport = _ => t
    )
    val o = opts(root, "rag-runtime-anthropic-v2.json", "anthropic")
    val r = BenchmarkRunner.run(o, env = placeholderEnv, llmWiring = wiring)
    (r, t, o.out)
  }

  test("R-5 early stop: the first 3 calls rejected with the same 4xx stop the run at once") {
    val (r, t, _) =
      liveRun(_ => HttpResult(401, "{\"error\":{\"type\":\"authentication_error\"}}", None))
    assertEquals(r("status").str, "stopped: first 3 model calls rejected with HTTP 401")
    assertEquals(t.calls.get, 3, "no request after the third rejection")
    assertEquals(r("modelCalls")("total")("failed").num, 3.0)
    assertEquals(r("modelCalls")("earlyStopStatus").num, 401.0)
    assert(!r("status").str.startsWith("completed"), "main exits non-zero")
  }

  test("R-5: a run in which logical calls failed after their retries ends failed, with causes") {
    val (r, t, _) = liveRun(_ => HttpResult(500, "{}", None))
    val failed = r("modelCalls")("total")("failed").num.toLong
    assert(failed > 0L)
    assertEquals(r("status").str, s"failed: $failed model calls failed (http-500: $failed)")
    // every logical call made 1 + maxRetries (3) attempts
    assertEquals(t.calls.get.toLong, failed * 4)
    assertEquals(r("modelCalls")("total")("retriedAttempts").num.toLong, failed * 3)
    assert(r.obj.contains("results"), "the run still evaluates every question")
  }

  test("R-5: retried attempts that succeed and invalid-output fallbacks do not fail the run") {
    // odd requests: 529 (retried); even requests: 200 with output cut off at max_tokens
    val cut = ujson
      .Obj(
        "content" -> ujson.Arr(ujson.Obj("type" -> "text", "text" -> "{\"operations\": [")),
        "usage" -> ujson.Obj("input_tokens" -> 100, "output_tokens" -> 4096),
        "stop_reason" -> "max_tokens"
      )
      .render()
    val (r, _, out) =
      liveRun(n => if (n % 2 == 1) HttpResult(529, "{}", None) else HttpResult(200, cut, None))
    assertEquals(r("status").str, "completed", r("status").str)
    val mc = r("modelCalls")
    assertEquals(mc("total")("failed").num, 0.0)
    assert(mc("total")("retriedAttempts").num > 0)
    assertEquals(mc("total")("invalidOutputs").num, mc("total")("logicalCalls").num)
    assertEquals(mc("total")("truncatedOutputs").num, mc("total")("logicalCalls").num)
    assert(mc("planner")("fallbacks")("output-invalid").num > 0)
    assert(mc("synthesis")("fallbacks")("output-invalid").num > 0)
    assertEquals(
      mc("planner")("stopReasons")("max_tokens").num,
      mc("planner")("logicalCalls")("attempted").num
    )
    // q files record the stop reason and the truncation error, never the rejected text
    val q1 = Files.readString(out.resolve("q-01.json"))
    val attempts = ujson.read(q1)("modelAttempts").arr
    assert(attempts.nonEmpty)
    assert(attempts.forall(_("stopReason").str == "max_tokens"), attempts.toString)
    assert(attempts.forall(_("error").str == StopReasons.TruncatedError), attempts.toString)
    assert(!q1.contains("{\\\"operations\\\": ["), "rejected model text is not stored")
  }

  test("the fake run reports modelCalls with no failure and no HTTP attempt") {
    val root = Files.createTempDirectory("rag-bench-calls")
    val r = BenchmarkRunner.run(opts(root, "rag-runtime-local-v2.json"))
    assertEquals(r("status").str, "completed")
    val mc = r("modelCalls")
    assertEquals(mc("total")("failed").num, 0.0)
    assertEquals(mc("total")("httpAttempts").num, 0.0)
    assert(mc("total")("logicalCalls").num >= key("questions").arr.size * 2.0)
    assertEquals(mc("earlyStopArmed").bool, false)
    assertEquals(r("versions")("prompts")(0).str.takeWhile(_ != '@'), "query-planner-v2")
    // Phase 11: the default prompt set is v3 (planner v2, synthesis and claims v3)
    assertEquals(r("versions")("schemas")(1).str, "answer-claims-v3")
    assertEquals(r("input")("promptSet").str, "v3")
    assertEquals(r("versions")("codeVersion").str, "rag-runtime/3")
    assertEquals(mc("total")("droppedBoundNumbers").num, 0.0)
  }

  test("argument parsing") {
    assert(BenchmarkRunner.parse(Array("--config", "a")).isLeft)
    val base = Array(
      "--config",
      "a",
      "--export",
      "b",
      "--neptune-csv",
      "c",
      "--questions",
      "d",
      "--out",
      "e",
      "--llm",
      "fake"
    )
    assertEquals(BenchmarkRunner.parse(base).map(_.prompts), Right(Prompts.V3))
    assertEquals(
      BenchmarkRunner.parse(base ++ Array("--prompts", "v1")).map(_.prompts),
      Right(Prompts.V1)
    )
    assertEquals(
      BenchmarkRunner.parse(base ++ Array("--prompts", "v2")).map(_.prompts),
      Right(Prompts.V2)
    )
    assert(BenchmarkRunner.parse(base ++ Array("--prompts", "v9")).isLeft)
    assert(
      BenchmarkRunner
        .parse(
          Array(
            "--config",
            "a",
            "--export",
            "b",
            "--neptune-csv",
            "c",
            "--questions",
            "d",
            "--out",
            "e",
            "--llm",
            "bedrock"
          )
        )
        .isLeft
    )
    assert(
      BenchmarkRunner
        .parse(
          Array(
            "--config",
            "a",
            "--export",
            "b",
            "--neptune-csv",
            "c",
            "--questions",
            "d",
            "--out",
            "e",
            "--llm",
            "fake",
            "--x",
            "y"
          )
        )
        .isLeft
    )
  }
}
