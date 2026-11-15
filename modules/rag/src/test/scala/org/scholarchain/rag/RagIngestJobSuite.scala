package org.scholarchain.rag

import java.nio.file.{Files, Path, Paths}

import org.scholarchain.core.config.EmbeddingProvider
import org.scholarchain.rag.ingest.RagIngestJob
import org.scholarchain.rag.semantic.ExportDocuments
import org.scholarchain.storage.source.{LocalDirectorySource, RejectLog}

class RagIngestJobSuite extends munit.FunSuite {
  import RagFixtures._

  private def configPath(name: String): Path =
    Paths.get(getClass.getResource(s"/config/$name").toURI)

  private def runJob(
      runs: Int = 2,
      root: Path = Files.createTempDirectory("rag-job")
  ): (ujson.Obj, Path) = {
    val dir = writeExport(root.resolve("export"))
    val out = root.resolve("attempt-01")
    val opts = RagIngestJob
      .parse(
        Array(
          "--config",
          configPath("rag-local-v1.json").toString,
          "--export",
          dir.toString,
          "--out",
          out.toString,
          "--runs",
          runs.toString,
          "--samples-per-index",
          "10"
        )
      )
      .fold(fail(_), identity)
    (RagIngestJob.run(opts), out)
  }

  test("two local runs: the second run skips 100% and changes nothing") {
    val (r, out) = runJob()
    assertEquals(r("status").str, "completed", r.render(indent = 2))
    val runs = r("runs").arr
    assertEquals(runs.size, 2)
    val first = runs(0)("ingest")
    val second = runs(1)("ingest")
    assertEquals(first("input").num, 15.0)
    assertEquals(first("written").num, 15.0)
    assertEquals(first("rejected").num, 0.0)
    assertEquals(second("input").num, 15.0)
    assertEquals(second("skippedUnchanged").num, 15.0)
    assertEquals(second("skipRatio").num, 1.0)
    assertEquals(second("written").num, 0.0)
    assertEquals(second("embedCalls").num, 0.0)
    assertEquals(second("putCalls").num, 0.0)
    assertEquals(r("secondRunSkipRatio").num, 1.0)
    assertEquals(r("renderDeterministic").bool, true)
    assertEquals(runs(0)("corpusDigest").str, runs(1)("corpusDigest").str)
    assertEquals(
      r("indexCounts").obj.toMap.view.mapValues(_.num.toInt).toMap,
      Map(
        "researcher-profile-v1" -> 6,
        "collaboration-community-v1" -> 3,
        "paper-abstract-v1" -> 4,
        "collaboration-path-v1" -> 2,
        "topic-institution-summary-v1" -> 0
      )
    )
    val t1 = runs(0)("templates")
    assertEquals(t1("spark-researcher-profile/graph-v1")("sparkVerbatimHashMatches").num, 6.0)
    assertEquals(t1("spark-community/graph-v1")("sparkVerbatimHashMatches").num, 3.0)
    assertEquals(t1("spark-path/graph-v1")("sparkVerbatimHashMatches").num, 2.0)
    List("spark-researcher-profile/graph-v1", "spark-community/graph-v1", "spark-path/graph-v1")
      .foreach(k => assertEquals(t1(k)("sparkVerbatimHashMismatches").num, 0.0, k))
    assertEquals(r("rejects")("render").num, 0.0)
    assertEquals(r("rejects")("ingest").num, 0.0)
    assertEquals(r("label").str, "local fake, not representative")
    assert(Files.isRegularFile(out.resolve("run-report.json")))
    val smoke = r("retrievalSmoke").obj
    List("researcher-profile-v1", "collaboration-community-v1", "collaboration-path-v1").foreach {
      i =>
        assertEquals(smoke(i)("top1IsSelf").num, smoke(i)("samples").num, i)
        assertEquals(smoke(i)("top1IsSelfWithinOwnWindow").num, smoke(i)("samples").num, i)
        assertEquals(
          smoke(i)("top1TextFetchedByPointerMatchesHash").num,
          smoke(i)("samples").num,
          i
        )
    }
    assertEquals(
      r("embedding")("documentModelSpec").str,
      "fake/fake-lexical-hash-v1/1024/document/emb-v1"
    )
    val templates = r("templates").arr.map(t => t("templateId").str -> t("source").str).toMap
    assert(templates("topic-institution-summary-v1").startsWith("no input (0 documents)"))
  }

  test("an existing output root is refused (a new root per attempt)") {
    val root = Files.createTempDirectory("rag-job")
    Files.createDirectories(root.resolve("attempt-01"))
    intercept[RuntimeException](runJob(root = root))
  }

  test("the fake provider is refused for non-local stores; live providers are refused locally") {
    assert(RagIngestJob.checkProviderAndStores(EmbeddingProvider.Fake, "local").isRight)
    assert(
      RagIngestJob
        .checkProviderAndStores(EmbeddingProvider.Fake, "aws")
        .left
        .exists(_.contains("never used for a stored index"))
    )
    assert(
      RagIngestJob
        .checkProviderAndStores(EmbeddingProvider.Voyage, "local")
        .left
        .exists(_.contains("live API"))
    )
    assert(RagIngestJob.checkProviderAndStores(EmbeddingProvider.Bedrock, "aws").isLeft)
  }

  test("a voyage configuration fails before any network use in a local run") {
    val root = Files.createTempDirectory("rag-job")
    val dir = writeExport(root.resolve("export"))
    val opts = RagIngestJob
      .parse(
        Array(
          "--config",
          configPath("rag-voyage-example-v1.json").toString,
          "--export",
          dir.toString,
          "--out",
          root.resolve("a").toString
        )
      )
      .toOption
      .get
    val r = RagIngestJob.run(opts)
    assert(r("status").str.contains("live API"), r("status").str)
  }

  test("the smoke window filter uses the document's own window; papers have none") {
    val p = org.scholarchain.rag.semantic.TemplateRegistry
      .fromSparkSummary(
        "community",
        W1,
        communitySummaries.find(_._1 == W1).get._2,
        localConfig.vectors.get
      )
      .toOption
      .get
      .head
    assertEquals(
      RagIngestJob.windowFilter(p),
      Some(
        org.scholarchain.storage.ports.MetadataFilter.And(
          List(
            org.scholarchain.storage.ports.MetadataFilter
              .Eq("windowStartYear", org.scholarchain.storage.ports.MetaValue.Num(2021L)),
            org.scholarchain.storage.ports.MetadataFilter
              .Eq("windowEndYear", org.scholarchain.storage.ports.MetaValue.Num(2023L))
          )
        )
      )
    )
    val paper = p.copy(window = None)
    assertEquals(RagIngestJob.windowFilter(paper), None)
  }

  test("argument parsing") {
    assert(RagIngestJob.parse(Array("--config", "c", "--export", "e")).isLeft)
    assert(
      RagIngestJob
        .parse(Array("--config", "c", "--export", "e", "--out", "o", "--bogus", "1"))
        .isLeft
    )
    assert(
      RagIngestJob
        .parse(Array("--config", "c", "--export", "e", "--out", "o", "--runs", "9"))
        .isLeft
    )
    assertEquals(
      RagIngestJob.parse(Array("--config", "c", "--export", "e", "--out", "o")).map(_.runs),
      Right(2)
    )
  }

  test("render rejects are recorded, never dropped: a work without a venue node") {
    val root = Files.createTempDirectory("rag-rej")
    val ts = tables.map(t => if (t.name == "nodes:venue") t.copy(lines = t.lines.take(1)) else t)
    val src = new LocalDirectorySource(writeExport(root, ts))
    val rejects = new RejectLog(None)
    val c = new ExportDocuments(src, src.manifest(), localConfig.vectors.get, rejects).renderAll()
    assertEquals(rejects.total, 2L)
    assertEquals(c.stats("paper-abstract-v1").rejected, 2L)
    assertEquals(c.stats("paper-abstract-v1").documents, 2L)
    assertEquals(c.stats("spark-researcher-profile/graph-v1").documents, 6L)
  }

  test("a tampered summary line is rejected at decode and counted") {
    val root = Files.createTempDirectory("rag-rej2")
    val ts = tables.map(t =>
      if (t.name.startsWith("semantic_summary:path"))
        t.copy(lines = t.lines.head.replace("2 hops", "9 hops") :: t.lines.tail)
      else t
    )
    val src = new LocalDirectorySource(writeExport(root, ts))
    val rejects = new RejectLog(None)
    val c = new ExportDocuments(src, src.manifest(), localConfig.vectors.get, rejects).renderAll()
    assertEquals(rejects.total, 1L)
    assertEquals(c.stats("spark-path/graph-v1").documents, 1L)
  }
}
