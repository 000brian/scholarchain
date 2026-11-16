package org.scholarchain.storage

import java.nio.file.{Files, Path}

import scala.jdk.CollectionConverters._

import org.scholarchain.core.{CollaborationMetric, Hashing}
import org.scholarchain.storage.local._
import org.scholarchain.storage.neptune._
import org.scholarchain.storage.ports.GraphQuery
import org.scholarchain.storage.source.{LocalDirectorySource, RejectLog}

class LocalRuntimeEnvironmentSuite extends munit.FunSuite {
  import TestFixtures._

  /** The fixture export and its Neptune CSV files (built by the Neptune builder). */
  private def inputs(): (Path, Path) = {
    val exportDir = writeExport(tempDir("lre-export"))
    val src = new LocalDirectorySource(exportDir)
    val csv = tempDir("lre-neptune")
    new NeptuneCsvBuilder(
      src,
      src.manifest(),
      csv,
      NeptuneBuildSettings(Full, 2, gzip = true, "researcher-profile-v1", "semantic-context"),
      new RejectLog(None)
    ).build()
    (exportDir, csv)
  }

  /** sha256 of every file under `dir`, keyed by relative path. */
  private def digests(dir: Path): Map[String, String] = {
    val s = Files.walk(dir)
    try
      s.iterator.asScala
        .filter(Files.isRegularFile(_))
        .map(p => dir.relativize(p).toString -> Hashing.sha256Hex(Files.readAllBytes(p)))
        .toMap
    finally s.close()
  }

  test("4i: loads the graph types and tables from the fixture export, read-only toward inputs") {
    val (exportDir, csv) = inputs()
    val before = (digests(exportDir), digests(csv))
    val env = LocalRuntimeEnvironment.load(runtimeStorageConfig, exportDir, csv).toOption.get
    try {
      val r = env.report
      assertEquals(r.graphWindowLabels, List(Full), "default: runtime.defaultWindowLabel only")
      assertEquals(
        r.graphRows.map { case (k, v) => k -> v.kept }.toList,
        List(
          "Researcher" -> 4L,
          "Work" -> 3L,
          "Venue" -> 1L,
          "AUTHORED" -> 6L,
          "PUBLISHED_IN" -> 3L,
          "COAUTHORED_WITH" -> 3L
        )
      )
      assertEquals(env.graph.nodeCount, 8)
      assertEquals(env.graph.edgeCount, 12)
      // 4 metric items + 10 metrics x top 3 ranking items
      assertEquals(r.tableItems("ResearcherMetricCache"), 34L)
      assertEquals(r.tableItems("EntityLinkCache"), 5L)
      assertEquals(r.tableItems("EvidenceBundle"), 1L)
      assertEquals(r.tableItems("QuerySession"), 0L)
      assertEquals(r.rejects, 0L)
      assert(r.heapUsedBytes > 0 && r.heapMaxBytes >= r.heapUsedBytes)
      assert(r.millis("total") >= r.millis("graph"))
      assertEquals(r.toJson("graphRows")("COAUTHORED_WITH")("kept").num, 3.0)

      // the runtime stores answer through the same ports as AWS
      val path = env.graph.run(GraphQuery.PathBetween(rA, rD, Full, 4)).toOption.get
      assertEquals((path.rows.head.values("hops").num, path.complete), (3.0, true))
      assertEquals(
        env.graph.run(GraphQuery.WorkById(w2)).toOption.get.rows.head.values("dblpKey").str,
        "conf/x/B19"
      )
      assertEquals(
        env.metrics.ranking(Full, CollaborationMetric.PublicationCount, 3).map(_.size),
        Right(3)
      )
      assertEquals(
        env.entityLinks.byName("Dan Brown", 3).map(_.candidates.map(_.researcherId)),
        Right(List(rD))
      )
      assertEquals(
        env.evidence.get(pathEvidence.head.evidenceId, Some(FullWindow)),
        Right(Some(pathEvidence.head))
      )
      assertEquals(env.settings.maxBundleItems, 5)
    } finally env.close()
    assertEquals((digests(exportDir), digests(csv)), before, "inputs unchanged")
  }

  test("4i: the COAUTHORED_WITH row filter keeps only the requested windows") {
    val (exportDir, csv) = inputs()
    val env = LocalRuntimeEnvironment
      .load(runtimeStorageConfig, exportDir, csv, LocalRuntimeOptions(Some(List("w2015-2017"))))
      .toOption
      .get
    try {
      val co = env.report.graphRows("COAUTHORED_WITH")
      assertEquals((co.read, co.kept), (3L, 0L))
      assertEquals(env.report.graphRows("AUTHORED").kept, 6L, "other types are not filtered")
      assertEquals(env.graph.run(GraphQuery.CoauthorNeighbors(rB, Full, 5)).toOption.get.rows, Nil)
    } finally env.close()
  }

  test("4i: bad inputs and options are refused") {
    val (exportDir, csv) = inputs()
    assert(LocalRuntimeEnvironment.load(storageConfig, exportDir, csv).isLeft, "no runtime section")
    assert(
      LocalRuntimeEnvironment
        .load(runtimeStorageConfig, exportDir, csv, LocalRuntimeOptions(Some(List("w1999-2000"))))
        .isLeft
    )
    assert(LocalRuntimeEnvironment.load(runtimeStorageConfig, csv, csv).isLeft, "not an export")
    assert(LocalRuntimeEnvironment.load(runtimeStorageConfig, exportDir, exportDir).isLeft)
    val inside = LocalRuntimeOptions(rejectFile = Some(exportDir.resolve("rejects.jsonl")))
    assert(LocalRuntimeEnvironment.load(runtimeStorageConfig, exportDir, csv, inside).isLeft)
    assert(!Files.exists(exportDir.resolve("rejects.jsonl")))
  }

  test("loadCsv row filter: keeps matching rows, counts all rows, and needs the column") {
    val dir = tempDir("csv-filter")
    val nodes = new CsvFileSet(
      dir,
      "nodes",
      "researcher",
      OpenCypherCsv.nodeHeader(List(CsvColumn("displayName", CsvType.Str))),
      100,
      gzip = false
    )
    researchers.foreach(r => nodes.write(List(r.value, "Researcher", names(r))))
    nodes.close()
    val header = OpenCypherCsv.edgeHeader(
      List(CsvColumn("windowLabel", CsvType.Str), CsvColumn("weight", CsvType.DoubleT))
    )
    val edges = new CsvFileSet(dir, "edges", "coauthored_with", header, 100, gzip = true)
    edges.write(List("e1", rA.value, rB.value, "COAUTHORED_WITH", Full, "1"))
    edges.write(List("e2", rA.value, rB.value, "COAUTHORED_WITH", "w2015-2017", "1"))
    edges.write(List("e3", rB.value, rC.value, "COAUTHORED_WITH", "w2018-2020", "2"))
    edges.close()
    val g = new InMemoryGraph(1, graphSettings)
    g.loadCsv(dir.resolve(nodes.files.head.relativePath))
    val f = dir.resolve(edges.files.head.relativePath)
    val s = g.loadCsv(f, Some(CsvRowFilter("windowLabel", Set(Full, "w2018-2020"))))
    assertEquals(s, CsvLoadStats(3L, 2L))
    assertEquals(
      (g.edge("e1").nonEmpty, g.edge("e2").isEmpty, g.edge("e3").nonEmpty),
      (true, true, true)
    )
    intercept[IllegalArgumentException](g.loadCsv(f, Some(CsvRowFilter("nope", Set(Full)))))
  }
}
