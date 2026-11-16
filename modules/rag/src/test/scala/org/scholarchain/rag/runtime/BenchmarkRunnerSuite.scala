package org.scholarchain.rag.runtime

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import org.scholarchain.rag.benchmark.AnswerKeyGenerator

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

  test("argument parsing") {
    assert(BenchmarkRunner.parse(Array("--config", "a")).isLeft)
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
