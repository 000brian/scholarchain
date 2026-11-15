package org.scholarchain.spark

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardCopyOption}

import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

import org.apache.spark.sql.{Encoder, Encoders, SparkSession}
import org.scholarchain.core._

/** Round-trip tests of the JSON Lines exporter on the fixture run (see [[FixtureData]]). */
class JsonlExportSuite extends munit.FunSuite {

  override val munitTimeout: Duration = 15.minutes

  private var tmp: File = _
  private var spark: SparkSession = _
  private var source: PipelineResult = _
  private var ex: ExportResult = _

  override def beforeAll(): Unit = {
    tmp = SparkTestSupport.tempDir("jsonl-export")
    spark = SparkTestSupport.session(tmp)
    val (h, ia) = FixtureData.write(new File(tmp, "inputs"))
    source = Pipeline.run(
      spark,
      PipelineArgs(
        h,
        ia,
        new File(tmp, "attempt-01"),
        FixtureData.config,
        "fixture-code",
        0L,
        Long.MaxValue
      )
    )
    ex = JsonlExport.run(spark, source.runDir, new File(tmp, "export-01"), recordsPerFile = 7L)
  }

  override def afterAll(): Unit = if (spark != null) spark.stop()

  private def lines(rel: String, root: File = null): Seq[String] =
    Files2
      .listData(new File(Option(root).getOrElse(ex.outRunDir), rel))
      .flatMap(f => Files.readAllLines(f.toPath, StandardCharsets.UTF_8).asScala)

  private def rows[R: Encoder](rel: String): Seq[R] =
    spark.read.parquet(new File(source.runDir, rel).getPath).as[R].collect().toSeq

  private def decodeAll[C](rel: String)(implicit
      rw: upickle.default.ReadWriter[C],
      v: Validator[C]
  ): Seq[C] =
    lines(rel).map(l => Contract.decode[C](l).fold(e => fail(s"$rel: ${e.message}"), identity))

  private def get[A](e: Either[String, A]): A = e.fold(m => fail(m), identity)

  private def loadManifestCounts(dir: File): Map[String, Long] =
    JsonlExport.Targets.flatMap { t =>
      ujson
        .read(Files2.readString(new File(dir, s"${Manifests.LoadDir}/$t.json")))("tables")
        .arr
        .map(x => x("table").str -> x("records").num.toLong)
    }.toMap

  test("every load-manifest table is exported with its count, run ID and file hashes") {
    val expected = loadManifestCounts(source.runDir)
    val m = ex.manifest
    assertEquals(m("runId").str, source.runId)
    assertEquals(m("sourceRunId").str, source.runId)
    assertEquals(ex.outRunDir.getName, source.runDir.getName)
    val tables = m("tables").arr.map(t => t("table").str -> t).toMap
    assertEquals(tables.keySet, expected.keySet)
    expected.foreach { case (name, n) =>
      val t = tables(name)
      assertEquals(t("records").num.toLong, n, name)
      assertEquals(t("loadManifestRecords").num.toLong, n, name)
      assertEquals(lines(t("path").str).size.toLong, n, name)
      t("files").arr.foreach { f =>
        val file = new File(ex.outRunDir, f("path").str)
        assert(file.getName.matches("part-\\d{5}\\.jsonl"), file.getName)
        assertEquals(file.length(), f("bytes").num.toLong)
        assertEquals(Files2.sha256(file), f("sha256").str)
      }
    }
    assert(new File(ex.outRunDir, JsonlExport.ManifestName).isFile)
    assert(new File(ex.outRunDir, JsonlExport.ReportName).isFile)
    // Several files per table when the table exceeds recordsPerFile (7 here).
    assert(tables("nodes:researcher")("files").arr.size > 1)
  }

  test("each line is one Contract.encode record equal to the stored row's core record") {
    implicit val n: Encoder[GraphNodeRow] = Encoders.product[GraphNodeRow]
    implicit val c: Encoder[CoauthorEdgeRow] = Encoders.product[CoauthorEdgeRow]
    implicit val s: Encoder[SemanticSummaryRow] = Encoders.product[SemanticSummaryRow]
    implicit val p: Encoder[PathEvidenceRow] = Encoders.product[PathEvidenceRow]
    implicit val m: Encoder[ResearcherMetricsRow] = Encoders.product[ResearcherMetricsRow]

    val nodes = "nodes/node_label=researcher"
    assertEquals(
      decodeAll[GraphNode](nodes).toSet,
      rows[GraphNodeRow](nodes).map(r => get(r.toCore)).toSet
    )
    val works = "nodes/node_label=work"
    assertEquals(
      decodeAll[GraphNode](works).toSet,
      rows[GraphNodeRow](works).map(r => get(r.toCore)).toSet
    )
    val edges = "coauthor_edge/window_label=full-2015-2025"
    assertEquals(
      decodeAll[CoauthorEdge](edges).toSet,
      rows[CoauthorEdgeRow](edges).map(r => get(r.toCore)).toSet
    )
    val metrics = "bridges/window_label=full-2015-2025"
    assertEquals(
      decodeAll[ResearcherMetrics](metrics).toSet,
      rows[ResearcherMetricsRow](metrics).map(r => get(r.toCore)).toSet
    )
    val profiles = "semantic_summary/summary_kind=researcher_profile/window_label=full-2015-2025"
    val decoded = decodeAll[EvidenceItem](profiles)
    assert(decoded.nonEmpty)
    assertEquals(
      decoded.toSet,
      rows[SemanticSummaryRow](profiles).map(r => get(r.evidence.toCore)).toSet
    )
    decoded.foreach(e => assertEquals(e.provenance.contentHash, e.snippet.map(ContentHash.of)))
    val paths = "path_evidence/window_label=full-2015-2025"
    assertEquals(
      decodeAll[EvidenceItem](paths).toSet,
      rows[PathEvidenceRow](paths).map(r => get(r.evidence.toCore)).toSet
    )
    // The line text is exactly Contract.encode of the decoded record.
    lines(edges).foreach { l =>
      assertEquals(
        Contract.encode(get(Contract.decode[CoauthorEdge](l).left.map(_.message))),
        Right(l)
      )
    }
    // Lines are sorted within each file.
    Files2.listData(new File(ex.outRunDir, edges)).foreach { f =>
      val ls = Files.readAllLines(f.toPath, StandardCharsets.UTF_8).asScala.toList
      assertEquals(ls, ls.sorted)
    }
  }

  test("evidence tables record the stored columns that are not part of the core record") {
    val t = ex.manifest("tables").arr.map(t => t("table").str -> t).toMap
    assertEquals(
      t("path_evidence:full-2015-2025")("sourceColumnsNotExported").arr.map(_.str).toList,
      List("pairKind", "pairIndex", "sourceId", "targetId", "hopCount", "hops")
    )
    assertEquals(t("nodes:researcher")("sourceColumnsNotExported").arr.size, 0)
  }

  test("determinism: a second export of the same run has identical files and manifest") {
    val again =
      JsonlExport.run(spark, source.runDir, new File(tmp, "export-02"), recordsPerFile = 7L)
    assertEquals(ujson.write(again.manifest), ujson.write(ex.manifest))
  }

  test("an existing export directory is never overwritten") {
    intercept[ExportRefusedException] {
      JsonlExport.run(spark, source.runDir, new File(tmp, "export-01"), recordsPerFile = 7L)
    }
  }

  private def copyTree(from: File, to: File): Unit = {
    val src = from.toPath
    val stream = Files.walk(src)
    try
      stream.iterator().asScala.foreach { (p: Path) =>
        val target = to.toPath.resolve(src.relativize(p).toString)
        if (Files.isDirectory(p)) Files.createDirectories(target)
        else Files.copy(p, target, StandardCopyOption.COPY_ATTRIBUTES)
      }
    finally stream.close()
  }

  test("a count that differs from the load manifest fails the export without a manifest") {
    val copy = new File(new File(tmp, "tampered"), source.runDir.getName)
    copyTree(source.runDir, copy)
    val s3 = new File(copy, s"${Manifests.LoadDir}/s3.json")
    val m = ujson.read(Files2.readString(s3))
    val auth = m("tables").arr.find(_("table").str == "authorship").get
    auth("records") = auth("records").num + 1
    Files2.writeString(s3, ujson.write(m, indent = 2))
    val out = new File(tmp, "export-tampered")
    val e = intercept[ExportCountMismatchException] {
      JsonlExport.run(spark, copy, out, recordsPerFile = 7L)
    }
    assert(e.getMessage.contains("authorship"), e.getMessage)
    assert(!new File(out, s"${source.runDir.getName}/${JsonlExport.ManifestName}").exists())
  }

  test("load manifests that disagree on a shared table are refused") {
    val copy = new File(new File(tmp, "disagree"), source.runDir.getName)
    copyTree(source.runDir, copy)
    val f = new File(copy, s"${Manifests.LoadDir}/neptune.json")
    val m = ujson.read(Files2.readString(f))
    val t = m("tables").arr.find(_("table").str == "nodes:venue").get
    t("records") = t("records").num + 1
    Files2.writeString(f, ujson.write(m, indent = 2))
    intercept[ExportCountMismatchException] {
      JsonlExport.run(spark, copy, new File(tmp, "export-disagree"), recordsPerFile = 7L)
    }
  }

  test("a row failing core validation is not encoded") {
    implicit val n: Encoder[GraphNodeRow] = Encoders.product[GraphNodeRow]
    val row = rows[GraphNodeRow]("nodes/node_label=researcher").head
    assert(JsonlCodec.Node.line(row).isRight)
    assert(JsonlCodec.Node.line(row.copy(nodeId = "not-an-id")).isLeft)
    assert(JsonlCodec.Node.line(row.copy(label = "planet")).isLeft)
    assert(JsonlCodec.Node.check("{\"schemaVersion\":1}").isLeft)
  }

  test("argument parsing") {
    assertEquals(
      JsonlExportJob.parseArgs(Seq("--source-run", "a", "--out-root", "b")).map(_.recordsPerFile),
      Right(250000L)
    )
    assert(JsonlExportJob.parseArgs(Seq("--out-root", "b")).isLeft)
    assert(JsonlExportJob.parseArgs(Seq("--source-run", "a", "--out-root", "b", "--x", "1")).isLeft)
    assert(
      JsonlExportJob
        .parseArgs(Seq("--source-run", "a", "--out-root", "b", "--records-per-file", "0"))
        .isLeft
    )
  }
}
