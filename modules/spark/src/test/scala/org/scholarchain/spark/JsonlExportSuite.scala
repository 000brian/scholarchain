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

  private def fileHashes(dir: File): Map[String, String] = {
    val all = Files.walk(dir.toPath)
    try
      all
        .iterator()
        .asScala
        .filter(Files.isRegularFile(_))
        .map { p =>
          dir.toPath.relativize(p).toString -> Files2.sha256(p.toFile)
        }
        .toMap
    finally all.close()
  }

  test("decision 7c: a file:// URI and a plain path give identical bytes and manifests") {
    val plainRoot = new File(tmp, "export-plain")
    val uriRoot = new File(tmp, "export-uri")
    val plain = JsonlExport.runWith(
      spark,
      Seq(
        "--source-run",
        source.runDir.getAbsolutePath,
        "--out-root",
        plainRoot.getAbsolutePath,
        "--records-per-file",
        "7"
      )
    )
    val uri = JsonlExport.runWith(
      spark,
      Seq(
        "--source-run",
        "file://" + source.runDir.getAbsolutePath,
        "--out-root",
        "file://" + uriRoot.getAbsolutePath,
        "--records-per-file",
        "7",
        "--code-version",
        "0123456789abcdef0123456789abcdef01234567"
      )
    )
    assertEquals(plain.outRun.toUri.getScheme, "file")
    assertEquals(uri.outRun.toUri.getScheme, "file")
    assertEquals(ujson.write(plain.manifest), ujson.write(uri.manifest))
    assertEquals(ujson.write(plain.manifest), ujson.write(ex.manifest))
    // Every written file (data, checksum side files, manifest) is byte-identical, except the
    // report, which holds timings and the code version.
    def bytes(dir: File) = fileHashes(dir).filter(_._1 != JsonlExport.ReportName)
    val p = bytes(plain.outRunDir)
    assertEquals(p, bytes(uri.outRunDir))
    assertEquals(p, bytes(ex.outRunDir))
    assert(p.contains(JsonlExport.ManifestName))
    // The manifest on disk is the returned manifest, and it lists every data file it covers.
    assertEquals(
      Files2.readString(new File(uri.outRunDir, JsonlExport.ManifestName)),
      ujson.write(uri.manifest, indent = 2)
    )
    val listed = uri.manifest("tables").arr.flatMap(_("files").arr.map(_("path").str)).toSet
    assertEquals(Files2.listData(uri.outRunDir).map(Files2.rel(uri.outRunDir, _)).toSet, listed)
  }

  test("decision 7c: a non-file scheme is read and written through the Hadoop FileSystem only") {
    MockStoreFileSystem.register(spark.sparkContext.hadoopConfiguration)
    val local = new File(tmp, "export-mockstore")
    val r = JsonlExport.runWith(
      spark,
      Seq(
        "--source-run",
        MockStoreFileSystem.uriOf(source.runDir.getAbsolutePath),
        "--out-root",
        MockStoreFileSystem.uriOf(local.getAbsolutePath),
        "--records-per-file",
        "7"
      )
    )
    assertEquals(r.outRun.toUri.getScheme, MockStoreFileSystem.Scheme)
    intercept[UnsupportedOperationException](r.outRunDir)
    assertEquals(ujson.write(r.manifest), ujson.write(ex.manifest))
    val written = new File(local, source.runDir.getName)
    assertEquals(
      Files2.readString(new File(written, JsonlExport.ManifestName)),
      ujson.write(r.manifest, indent = 2)
    )
    def data(dir: File) = fileHashes(dir).filter { case (k, _) =>
      !k.split('/').exists(s => s.startsWith("_") || s.startsWith("."))
    }
    assertEquals(data(written), data(ex.outRunDir))
    assertEquals(
      JsonlExport.show(r.outRun),
      s"mockstore://{authority}${written.getAbsolutePath}"
    )
    // A second export to the same location is refused through the same file system.
    intercept[ExportRefusedException] {
      JsonlExport.runWith(
        spark,
        Seq(
          "--source-run",
          MockStoreFileSystem.uriOf(source.runDir.getAbsolutePath),
          "--out-root",
          MockStoreFileSystem.uriOf(local.getAbsolutePath)
        )
      )
    }
  }

  test("decision 7: the report records --code-version and the jar's build identity") {
    val out = new File(tmp, "export-report")
    val r = JsonlExport.runWith(
      spark,
      Seq(
        "--source-run",
        source.runDir.getAbsolutePath,
        "--out-root",
        out.getAbsolutePath,
        "--records-per-file",
        "7",
        "--code-version",
        "fixture-commit"
      )
    )
    val onDisk = ujson.read(Files2.readString(new File(r.outRunDir, JsonlExport.ReportName)))
    assertEquals(onDisk("codeVersion").str, "fixture-commit")
    assertEquals(onDisk("runId").str, source.runId)
    // The identity of the class path the job runs from (the generated resource when the build
    // puts it there; otherwise every field "unknown" with status "missing").
    assertEquals(onDisk("buildIdentity"), BuildIdentity.load().toJson: ujson.Value)
    assert(Set("resource", "missing")(onDisk("buildIdentity")("status").str))
    assertEquals(
      onDisk("buildIdentity").obj.keys.toList,
      List("buildCommit", "buildDirty", "module", "version", "status")
    )
    // Without --code-version the field is null; the default export also records it.
    assertEquals(
      ujson.read(Files2.readString(new File(ex.outRunDir, JsonlExport.ReportName)))("codeVersion"),
      ujson.Null
    )
  }

  test("decision 7c: an existing (even empty) target run directory is refused, untouched") {
    val root = new File(tmp, "export-existing")
    val target = new File(root, source.runDir.getName)
    assert(target.mkdirs())
    val e = intercept[ExportRefusedException] {
      JsonlExport.runWith(
        spark,
        Seq("--source-run", source.runDir.getAbsolutePath, "--out-root", "file://" + root)
      )
    }
    assert(e.getMessage.contains("already exists"), e.getMessage)
    assertEquals(Option(target.list()).map(_.toList), Some(Nil))
  }

  private def assertNothingWritten(out: File)(body: => Any): Throwable = {
    val e = intercept[Throwable](body)
    assert(!out.exists(), s"$out was created: ${e.getMessage}")
    e
  }

  test("decision 7c: nothing is written when validation fails") {
    // A table whose load-manifest schema version is not current (refused by the codec check,
    // which used to run after the output directory was created).
    val badSchema = new File(new File(tmp, "bad-schema"), source.runDir.getName)
    copyTree(source.runDir, badSchema)
    JsonlExport.Targets.foreach { t =>
      val f = new File(badSchema, s"${Manifests.LoadDir}/$t.json")
      val m = ujson.read(Files2.readString(f))
      m("tables").arr.filter(_("table").str == "authorship").foreach(_("schemaVersion") = 99)
      Files2.writeString(f, ujson.write(m, indent = 2))
    }
    val out1 = new File(tmp, "nothing-1")
    val e1 = assertNothingWritten(out1)(JsonlExport.run(spark, badSchema, out1, 7L))
    assert(e1.isInstanceOf[ExportRefusedException], e1.toString)
    assert(e1.getMessage.contains("schema version"), e1.getMessage)

    // A changed source data file (the run manifest no longer matches).
    val changed = new File(new File(tmp, "changed"), source.runDir.getName)
    copyTree(source.runDir, changed)
    val victim = Files2.listData(new File(changed, "authorship")).head
    Files.write(victim.toPath, Array[Byte](1, 2, 3))
    val out2 = new File(tmp, "nothing-2")
    val e2 = assertNothingWritten(out2)(JsonlExport.run(spark, changed, out2, 7L))
    assert(e2.isInstanceOf[ExportRefusedException], e2.toString)

    // An invalid code version, an unknown option, and an output root inside the source run.
    val out3 = new File(tmp, "nothing-3")
    val e3 = assertNothingWritten(out3)(
      JsonlExport.run(
        spark,
        RunFs.pathOf(source.runDir),
        RunFs.pathOf(out3),
        7L,
        32,
        Some("not a version/../x")
      )
    )
    assert(e3.isInstanceOf[ExportRefusedException], e3.toString)
    val out4 = new File(tmp, "nothing-4")
    val e4 = assertNothingWritten(out4)(
      JsonlExport.runWith(
        spark,
        Seq("--source-run", source.runDir.getPath, "--out-root", out4.getPath, "--extra", "1")
      )
    )
    assert(e4.isInstanceOf[IllegalArgumentException], e4.toString)
    val inside = new File(source.runDir, "nested-export")
    val e5 = assertNothingWritten(inside)(JsonlExport.run(spark, source.runDir, inside, 7L))
    assert(e5.getMessage.contains("overlap"), e5.getMessage)
  }
}
