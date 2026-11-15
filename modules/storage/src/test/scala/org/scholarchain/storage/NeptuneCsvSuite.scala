package org.scholarchain.storage

import java.io.{BufferedReader, InputStreamReader}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.zip.GZIPInputStream

import org.scholarchain.core._
import org.scholarchain.storage.local.InMemoryGraph
import org.scholarchain.storage.neptune._
import org.scholarchain.storage.ports.{GraphQuery, ObjectRef}
import org.scholarchain.storage.source._

class NeptuneCsvSuite extends munit.FunSuite {
  import TestFixtures._

  private def settings(gzip: Boolean) =
    NeptuneBuildSettings(Full, 2, gzip, "researcher-profile-v1", "semantic-context")

  private def build(
      gzip: Boolean = true,
      edit: Table => List[String] = _.lines
  ): (Path, NeptuneBuildReport, RejectLog) = {
    val dir = writeExport(tempDir("neptune-in"), edit)
    val src = new LocalDirectorySource(dir)
    val out = tempDir("neptune-out")
    val rejects = new RejectLog(None)
    val r = new NeptuneCsvBuilder(src, src.manifest(), out, settings(gzip), rejects).build()
    (out, r, rejects)
  }

  private def rows(
      out: Path,
      r: NeptuneBuildReport,
      name: String
  ): (Vector[String], List[Vector[String]]) = {
    val files = r.types.find(_.name == name).get.files
    val parsed = files.map { f =>
      val raw = Files.newInputStream(out.resolve(f.relativePath))
      val in = if (f.relativePath.endsWith(".gz")) new GZIPInputStream(raw) else raw
      val br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))
      try {
        val lines = Iterator.continually(br.readLine()).takeWhile(_ != null).toList
        (OpenCypherCsv.parseLine(lines.head), lines.tail.map(OpenCypherCsv.parseLine))
      } finally br.close()
    }
    (parsed.head._1, parsed.flatMap(_._2))
  }

  test("Neptune node and edge IDs equal the exported core IDs") {
    val (out, r, rejects) = build()
    assertEquals(rejects.total, 0L)
    def ids(name: String) = rows(out, r, name)._2.map(_.head).toSet
    assertEquals(ids("Researcher"), researchers.map(_.value).toSet)
    assertEquals(ids("Work"), works.map(_._1.value).toSet)
    assertEquals(ids("Venue"), Set("conf/x"))
    assertEquals(ids("AuthorMention"), authorshipRecords.map(_.mentionId.value).toSet)
    assertEquals(ids("COAUTHORED_WITH"), coauthorEdges.map(_.edgeId.value).toSet)
    assertEquals(ids("AUTHORED"), authoredEdges.map(_.edgeId.value).toSet)
    assertEquals(ids("PUBLISHED_IN"), publishedInEdges.map(_.edgeId.value).toSet)
    assert(r.types.filter(_.rows > 0).forall(_.idsMatch), r.types.map(t => t.name -> t.idsMatch))
    // text_chunk IDs are the ChunkIds of the profile documents
    val chunkIds = profileSummaries.map(e =>
      ChunkId
        .forSubjects(
          SemanticDocumentType.ResearcherProfile,
          e.subjectIds,
          Some(Full),
          0,
          e.provenance.contentHash.get
        )
        .toOption
        .get
        .value
    )
    assertEquals(ids("TextChunk"), chunkIds.toSet)
  }

  test("every COAUTHORED_WITH edge carries its supporting work IDs, window and evidence key") {
    val (out, r, _) = build()
    val (header, data) = rows(out, r, "COAUTHORED_WITH")
    assertEquals(header.take(4), Vector(":ID", ":START_ID", ":END_ID", ":TYPE"))
    def col(n: String) = header.indexWhere(_.startsWith(n + ":"))
    val byId = coauthorEdges.map(e => e.edgeId.value -> e).toMap
    assertEquals(data.size, 3)
    data.foreach { row =>
      val e = byId(row(0))
      assertEquals(row(1), e.left.value)
      assertEquals(row(2), e.right.value)
      assertEquals(row(col("supportingWorkIds")), e.supportingWorkIds.map(_.value).mkString(";"))
      assertEquals(row(col("windowLabel")), Full)
      assertEquals(row(col("windowStartYear")), "2015")
      assertEquals(row(col("windowEndYear")), "2025")
      assertEquals(row(col("jointPublicationCount")), "1")
      assertEquals(row(col("evidenceKey")), e.edgeId.value)
      assert(
        row(col("firstCollaborationYear")).nonEmpty && row(col("lastCollaborationYear")).nonEmpty
      )
      assertEquals(row(col("runId")), sparkRun.value)
    }
    assert(!header.exists(_.endsWith("[]")), "no array columns")
  }

  test("a coauthor edge whose endpoint has no node is rejected, not written") {
    val (out, r, rejects) =
      build(edit = t => if (t.name == "nodes:researcher") t.lines.drop(1) else t.lines)
    val written = rows(out, r, "COAUTHORED_WITH")._2.map(_.head).toSet
    assertEquals(written.size, 2)
    assert(rejects.count("neptune-csv", s"coauthor_edge:$Full") >= 1L)
    assert(rejects.total >= 1L)
    assert(!r.types.find(_.name == "COAUTHORED_WITH").get.idsMatch)
  }

  test("a coauthor_edge that disagrees with its graph edge is rejected") {
    val bad = graphCoauthorEdges.head.copy(weight = Some(7.0))
    val (_, r, rejects) = build(edit =
      t =>
        if (t.name == s"edges:coauthored_with:$Full")
          Contract.encode(bad).toOption.get :: t.lines.tail
        else t.lines
    )
    assertEquals(r.types.find(_.name == "COAUTHORED_WITH").get.rows, 2L)
    assertEquals(rejects.count("neptune-csv", s"coauthor_edge:$Full"), 1L)
  }

  test("a graph edge without coauthor_edge evidence is rejected") {
    val (_, r, rejects) =
      build(edit = t => if (t.name == s"coauthor_edge:$Full") t.lines.drop(1) else t.lines)
    assertEquals(r.types.find(_.name == "COAUTHORED_WITH").get.rows, 2L)
    assertEquals(rejects.count("neptune-csv", s"edges:coauthored_with:$Full"), 1L)
  }

  test("empty types are skipped and reported; files roll at the row cap; output is deterministic") {
    val (out1, r1, _) = build()
    val (_, r2, _) = build()
    val inst = r1.types.find(_.name == "Institution").get
    assertEquals(inst.rows, 0L)
    assert(inst.files.isEmpty && inst.note.exists(_.contains("skipped")))
    assertEquals(r1.types.find(_.name == "Researcher").get.files.size, 2) // 4 rows, 2 per file
    assertEquals(r1.types.map(_.files.map(_.sha256)), r2.types.map(_.files.map(_.sha256)))
    assert(Files.exists(out1.resolve("nodes/Researcher/researcher-00000.csv.gz")))
    assert(Files.exists(out1.resolve("edges/COAUTHORED_WITH/coauthored_with-00000.csv.gz")))
  }

  test("CSV quoting round-trips commas and quotes; titles survive") {
    val v = "A paper, with \"quotes\""
    assertEquals(OpenCypherCsv.parseLine(OpenCypherCsv.line(Seq("x", v, ""))), Vector("x", v, ""))
    val (out, r, _) = build(gzip = false)
    val titles = rows(out, r, "Work")._2.map(_(2)).toSet
    assertEquals(titles, workNodes.map(_.displayName).toSet)
  }

  test("loaded into the in-memory graph, the CSV answers the read-only templates") {
    val (out, r, _) = build()
    val g = new InMemoryGraph(rowCap = 10)
    List("nodes", "edges").foreach(stage =>
      r.types
        .filter(_.stage == stage)
        .flatMap(_.files)
        .foreach(f => g.loadCsv(out.resolve(f.relativePath)))
    )
    val nb = g.run(GraphQuery.CoauthorNeighbors(rB, Full, 10)).toOption.get
    assertEquals(nb.rows.map(_.values("coauthorId").str).toSet, Set(rA.value, rC.value))
    val path = g.run(GraphQuery.PathBetween(rA, rD, Full, 6)).toOption.get
    assertEquals(path.rows.head.values("researcherIds").arr.map(_.str).toList, pathSubjects)
    assertEquals(path.rows.head.values("evidenceKeys").arr.size, 3)
    assertEquals(g.run(GraphQuery.PathBetween(rA, rD, Full, 2)).toOption.get.rows, Nil)
    val edge = g.run(GraphQuery.CoauthorEdgeBetween(rA, rB, Full)).toOption.get.rows.head
    assertEquals(edge.values("properties")("supportingWorkIds").str, w1.value)
    val authors = g.run(GraphQuery.WorkAuthors(w1, 10)).toOption.get
    assertEquals(authors.rows.map(_.values("researcherId").str).toSet, Set(rA.value, rB.value))
    val prof = g.run(GraphQuery.ResearcherById(rA)).toOption.get.rows.head.values("properties")
    assertEquals(prof("publicationCount").num, 1.0)
    assertEquals(prof("metricsWindowLabel").str, Full)
  }

  test("bulk-load requests: opencypher, prefix source, nodes before edges, validated inputs") {
    val prefix =
      ObjectRef("example-graph-bucket", "graph/dataset=d/snapshot=s/run=x/neptune/nodes/")
    val role = "arn:aws:iam::123456789012:role/example-neptune-loader"
    val nodes = BulkLoadRequests.build(prefix, role, "us-east-1", Nil).toOption.get
    val edges = BulkLoadRequests
      .build(
        prefix.copy(key = prefix.key.replace("/nodes/", "/edges/")),
        role,
        "us-east-1",
        List("load-1")
      )
      .toOption
      .get
    assertEquals(nodes.format, "opencypher")
    assertEquals(
      BulkLoadRequests.toJson(edges)("dependencies").arr.map(_.str).toList,
      List("load-1")
    )
    assertEquals(BulkLoadRequests.toJson(nodes)("failOnError").str, "TRUE")
    assert(BulkLoadRequests.build(prefix.copy(key = "graph/x"), role, "us-east-1", Nil).isLeft)
    assert(BulkLoadRequests.build(prefix, "not-an-arn", "us-east-1", Nil).isLeft)
    assert(BulkLoadRequests.build(prefix, role, "nowhere", Nil).isLeft)
  }
}
