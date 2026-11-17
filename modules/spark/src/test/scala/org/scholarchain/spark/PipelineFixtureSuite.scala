package org.scholarchain.spark

import java.io.File
import java.nio.file.{Files, StandardCopyOption, StandardOpenOption}

import scala.concurrent.duration._

import org.apache.spark.sql.{Encoder, SparkSession}
import org.scholarchain.core._

/** End-to-end known-graph tests on the hand-checkable fixture (see [[FixtureData]]). The pipeline
  * runs twice on the same inputs (two attempt roots) for the determinism and reuse checks; every
  * other test reads the first run's exported tables.
  */
class PipelineFixtureSuite extends munit.FunSuite {

  override val munitTimeout: Duration = 15.minutes

  private var tmp: File = _
  private var spark: SparkSession = _
  private var hadoopDir: File = _
  private var identityDir: File = _
  private var r1: PipelineResult = _
  private var r2: PipelineResult = _

  private val Full = "full-2015-2025"

  private def args(root: String): PipelineArgs =
    PipelineArgs(
      hadoopDir,
      identityDir,
      new File(tmp, root),
      FixtureData.config,
      "fixture-code",
      0L,
      Long.MaxValue
    )

  override def beforeAll(): Unit = {
    tmp = SparkTestSupport.tempDir("pipeline-fixture")
    spark = SparkTestSupport.session(tmp)
    val (h, ia) = FixtureData.write(new File(tmp, "inputs"))
    hadoopDir = h
    identityDir = ia
    r1 = Pipeline.run(spark, args("attempt-01"))
    // F9 part 2: the second run goes through the EMR argument path with file:// URIs (Hadoop
    // FileSystem API for the listing, ER checks, output root, manifests and checkpoints).
    r2 = GraphAnalyticsJob.runWith(spark, uriArgv("attempt-02"))
  }

  /** EMR-style arguments with `file://` URIs; the config is the fixture's (outputPartitions 1). */
  private def uriArgv(root: String): Seq[String] =
    Seq(
      "--hadoop-export",
      hadoopDir.getAbsoluteFile.toURI.toString,
      "--identity-assignments",
      identityDir.getAbsoluteFile.toURI.toString,
      "--out-root",
      new File(tmp, root).getAbsoluteFile.toURI.toString,
      "--config",
      fixtureConfigFile.getPath,
      "--code-version",
      "fixture-code",
      "--min-free-disk-gb",
      "0"
    )

  private lazy val fixtureConfigFile: File = {
    val f = new File(tmp, "fixture-config.json")
    Files2.writeString(f, FixtureData.config.json)
    f
  }

  override def afterAll(): Unit = if (spark != null) spark.stop()

  private def rows[R: Encoder](rel: String, dir: File = null): Seq[R] =
    spark.read
      .parquet(new File(Option(dir).getOrElse(r1.runDir), rel).getPath)
      .as[R]
      .collect()
      .toSeq

  private def rid(k: String) = FixtureData.rid(k)
  private def wid(code: String) = FixtureData.work(code).workId.value
  private def sortedWorks(codes: String*) = codes.map(wid).sorted(CodePointOrder.ordering)
  private def minRid(ks: String*) = ks.map(rid).min(CodePointOrder.ordering)

  import org.apache.spark.sql.Encoders
  private implicit val metricsEnc: Encoder[ResearcherMetricsRow] =
    Encoders.product[ResearcherMetricsRow]
  private implicit val edgeEnc: Encoder[CoauthorEdgeRow] = Encoders.product[CoauthorEdgeRow]
  private implicit val memberEnc: Encoder[GroupMembershipRow] = Encoders.product[GroupMembershipRow]
  private implicit val metricEvEnc: Encoder[MetricEvidenceRow] = Encoders.product[MetricEvidenceRow]
  private implicit val pathEnc: Encoder[PathEvidenceRow] = Encoders.product[PathEvidenceRow]
  private implicit val nodeEnc: Encoder[GraphNodeRow] = Encoders.product[GraphNodeRow]
  private implicit val gEdgeEnc: Encoder[GraphEdgeRow] = Encoders.product[GraphEdgeRow]
  private implicit val authEnc: Encoder[AuthorshipRow] = Encoders.product[AuthorshipRow]
  private implicit val rejEnc: Encoder[RejectRow] = Encoders.product[RejectRow]
  private implicit val mentionEnc: Encoder[AuthorMentionRow] = Encoders.product[AuthorMentionRow]
  private implicit val sumEnc: Encoder[SemanticSummaryRow] = Encoders.product[SemanticSummaryRow]
  private implicit val resEnc: Encoder[CanonicalResearcherRow] =
    Encoders.product[CanonicalResearcherRow]

  /** researcherId -> metric wire name -> value, for one metrics table and window. */
  private def metrics(table: String, label: String): Map[String, Map[String, Double]] =
    rows[ResearcherMetricsRow](s"$table/window_label=$label")
      .map(r => r.researcherId -> r.values.map(v => v.metric -> v.value).toMap)
      .toMap

  private def breadth(label: String) = metrics("metrics/metric_type=breadth", label)

  private def confidence(label: String): Map[String, Double] =
    rows[ResearcherMetricsRow](s"metrics/metric_type=breadth/window_label=$label")
      .map(r => r.researcherId -> r.identityConfidence)
      .toMap

  private def assertBreadth(
      m: Map[String, Map[String, Double]],
      k: String,
      p: Double,
      c: Double,
      t: Double,
      mm: Double,
      w: Double
  )(implicit
      loc: munit.Location
  ): Unit = {
    val v = m(rid(k))
    assertEquals(v("publication_count"), p, s"P of $k")
    assertEquals(v("distinct_coauthors"), c, s"C of $k")
    assertEquals(v("total_coauthor_occurrences"), t, s"T of $k")
    assertEqualsDouble(v("average_coauthors_per_paper"), t / p, 1e-12, s"A of $k")
    assertEquals(v("max_coauthors_on_one_paper"), mm, s"M of $k")
    assertEquals(v("weighted_degree"), w, s"W of $k")
  }

  test("P, C, T, A, M and W in the full window count distinct works and distinct coauthors") {
    val m = breadth(Full)
    // a: W1(2 others) W2(1) W6(1, homonym slots) W3(1, two Dan mentions are one researcher) W4(0).
    assertBreadth(m, "a", p = 5, c = 3, t = 5, mm = 2, w = 5)
    assertBreadth(m, "b", p = 4, c = 3, t = 5, mm = 2, w = 5)
    assertBreadth(m, "c", p = 1, c = 2, t = 2, mm = 2, w = 2)
    assertBreadth(m, "d", p = 1, c = 1, t = 1, mm = 1, w = 1)
    assertBreadth(m, "e", p = 1, c = 1, t = 1, mm = 1, w = 1)
    assertBreadth(m, "h", p = 1, c = 0, t = 0, mm = 0, w = 0)
    assertBreadth(m, "s", p = 5, c = 4, t = 11, mm = 3, w = 11)
    assertBreadth(m, "x", p = 2, c = 1, t = 2, mm = 1, w = 2)
  }

  test("M_r evidence lists the works reaching M_r with distinct researchers and dblp team size") {
    val full = rows[MetricEvidenceRow](s"evidence/evidence_type=metric/window_label=$Full")
      .map(r => r.researcherId -> r)
      .toMap
    val a = full(rid("a"))
    assertEquals(a.evidence.subjectIds, Seq(rid("a"), wid("W1")))
    assertEquals(a.evidence.metric, Some(MetricValueRow("max_coauthors_on_one_paper", 2.0)))
    assertEquals(a.works, Seq(WorkTeamSize(wid("W1"), 3, 3)))
    assertEquals(
      a.evidence.evidenceId,
      EvidenceId.of(EvidenceType.Metric, RunId(r1.runId), a.evidence.subjectIds).value
    )
    // d: W3 has three dblp authors but two distinct researchers.
    assertEquals(full(rid("d")).works, Seq(WorkTeamSize(wid("W3"), 2, 3)))
    val mid = rows[MetricEvidenceRow]("evidence/evidence_type=metric/window_label=w2018-2020")
      .map(r => r.researcherId -> r)
      .toMap
    val a2 = mid(rid("a"))
    assertEquals(a2.evidence.subjectIds, rid("a") +: sortedWorks("W6", "W3"))
    assertEquals(a2.evidence.window, Some(YearWindow(2018, 2020)))
    assertEquals(
      a2.works.map(w => w.workId -> w.dblpTeamSize).toMap,
      Map(wid("W6") -> 3, wid("W3") -> 3)
    )
  }

  test("windows are applied: each window counts only its own works") {
    val early = breadth("w2015-2017")
    assertBreadth(early, "a", p = 2, c = 2, t = 3, mm = 2, w = 3)
    assertBreadth(early, "c", p = 1, c = 2, t = 2, mm = 2, w = 2)
    val mid = breadth("w2018-2020")
    assertBreadth(mid, "a", p = 3, c = 2, t = 2, mm = 1, w = 2)
    assertBreadth(mid, "b", p = 1, c = 1, t = 1, mm = 1, w = 1)
    assertEquals(breadth("w2021-2023").keySet, Set(rid("b"), rid("e")))
    assertEquals(breadth("w2024-2025"), Map.empty[String, Map[String, Double]])
  }

  test("every windowed output row states the window of its partition") {
    val windows = FixtureData.config.windows.map(w => w.label -> w.window).toMap
    val outputs =
      ujson.read(Files2.readString(new File(r1.runDir, Manifests.RunManifestName)))("outputs").arr
    val windowed = outputs.filter(o => !o("windowLabel").isNull)
    assert(windowed.size >= 5 * 10, s"windowed tables: ${windowed.size}")
    windowed.foreach { o =>
      val expected = windows(o("windowLabel").str)
      val df = spark.read.parquet(new File(r1.runDir, o("path").str).getPath)
      val col = if (df.columns.contains("window")) "window" else "evidence.window"
      val seen = df
        .select(s"$col.startYear", s"$col.endYear")
        .distinct()
        .collect()
        .map(r => YearWindow(r.getInt(0), r.getInt(1)))
        .toSet
      assert(seen.subsetOf(Set(expected)), s"${o("path").str}: $seen")
    }
  }

  test(
    "coauthor edges: canonical order, duplicates aggregated, years and sorted supporting works"
  ) {
    val full = rows[CoauthorEdgeRow](s"coauthor_edge/window_label=$Full")
    assertEquals(full.size, 17)
    assertEquals(full.map(e => (e.left, e.right)).distinct.size, full.size)
    full.foreach(e => assert(CodePointOrder.lessThan(e.left, e.right), e.toString))
    def edge(x: String, y: String) = {
      val (l, r) =
        if (CodePointOrder.lessThan(rid(x), rid(y))) (rid(x), rid(y)) else (rid(y), rid(x))
      full.find(e => e.left == l && e.right == r).getOrElse(fail(s"no edge $x-$y"))
    }
    val ab = edge("a", "b")
    assertEquals((ab.weight, ab.sharedWorkCount, ab.firstYear, ab.lastYear), (3.0, 3, 2016, 2018))
    assertEquals(ab.supportingWorkIds, sortedWorks("W1", "W2", "W6"))
    assertEquals(
      ab.edgeId,
      EdgeId
        .coauthor(ResearcherId(ab.left), ResearcherId(ab.right), YearWindow(2015, 2025))
        .toOption
        .get
        .value
    )
    assertEquals(edge("s", "t").weight, 2.0)
    assertEquals(edge("t", "u").weight, 4.0)
    // W6 has two position pairs for (Alice Able, Bob Baker): still one joint publication.
    val mid = rows[CoauthorEdgeRow]("coauthor_edge/window_label=w2018-2020")
    val abMid = mid.find(e => Set(e.left, e.right) == Set(rid("a"), rid("b"))).get
    assertEquals((abMid.weight, abMid.supportingWorkIds), (1.0, Seq(wid("W6"))))
  }

  test("self pairs created by entity resolution are dropped") {
    val full = rows[CoauthorEdgeRow](s"coauthor_edge/window_label=$Full")
    val dEdges = full.filter(e => e.left == rid("d") || e.right == rid("d"))
    assertEquals(dEdges.map(e => Set(e.left, e.right)), Seq(Set(rid("a"), rid("d"))))
    assertEquals(r1.report("build")("erSelfPairsDropped").num, 1.0)
  }

  test("a CONFLICT cluster's lower confidence is carried on authorships, edges and metrics") {
    assertEquals(confidence(Full)(rid("e")), 0.5)
    assertEquals(confidence(Full)(rid("b")), 0.5) // b's C and W count the CONFLICT coauthor e
    assertEquals(confidence(Full)(rid("a")), 0.95)
    assertEquals(confidence("w2021-2023")(rid("b")), 0.5)
    assertEquals(confidence("w2015-2017")(rid("b")), 0.95)
    val be = rows[CoauthorEdgeRow](s"coauthor_edge/window_label=$Full")
      .find(e => Set(e.left, e.right) == Set(rid("b"), rid("e")))
      .get
    assertEquals(be.minIdentityConfidence, 0.5)
    assertEquals(
      rows[AuthorshipRow]("authorship").filter(_.researcherId == rid("e")).map(_.matchConfidence),
      Seq(0.5)
    )
    val node = rows[GraphNodeRow]("nodes/node_label=researcher").find(_.nodeId == rid("e")).get
    assertEquals(node.properties("identityOutcome"), "conflict")
    assertEquals(node.properties("reasonCodes"), "cluster-multiple-orcids")
    rows[ResearcherMetricsRow](s"bridges/window_label=$Full")
      .filter(_.researcherId == rid("e"))
      .foreach(r => assertEquals(r.identityConfidence, 0.5))
  }

  test("connected components: smallest member ID, sizes, singletons") {
    val comps = rows[GroupMembershipRow](s"components/window_label=$Full")
      .map(r => r.researcherId -> (r.groupId, r.groupSize))
      .toMap
    val main = Seq("p", "q", "r", "s", "t", "u", "v", "x")
    main.foreach(k => assertEquals(comps(rid(k)), (minRid(main: _*), 8), k))
    Seq("a", "b", "c", "d", "e").foreach(k =>
      assertEquals(comps(rid(k)), (minRid("a", "b", "c", "d", "e"), 5), k)
    )
    Seq("y", "z").foreach(k => assertEquals(comps(rid(k)), (minRid("y", "z"), 2), k))
    assertEquals(comps(rid("g")), (rid("g"), 1))
    assertEquals(comps(rid("h")), (rid("h"), 1))
    val w = r1.report("windows")(Full)
    assertEquals(w("singletons").num, 2.0)
    assertEquals(w("largestComponentSize").num, 8.0)
    assertEqualsDouble(w("largestComponentShare").num, 8.0 / 17.0, 1e-12)
  }

  test("k-core: coreness on the clique, triangle, pendant, pair and isolated researcher") {
    val core = metrics("kcore", Full).map { case (k, v) => k -> v("coreness") }
    Seq("p", "q", "r", "s").foreach(k => assertEquals(core(rid(k)), 3.0, k))
    Seq("t", "u", "v").foreach(k => assertEquals(core(rid(k)), 2.0, k))
    Seq("x", "y", "z").foreach(k => assertEquals(core(rid(k)), 1.0, k))
    assertEquals(core(rid("g")), 0.0)
    assertEquals(core(rid("a")), 2.0) // triangle a-b-c
  }

  test("label propagation: weighted communities, smallest-label ties, sizes") {
    val comm = rows[GroupMembershipRow](s"communities/window_label=$Full")
      .map(r => r.researcherId -> (r.groupId, r.groupSize))
      .toMap
    Seq("p", "q", "r", "s").foreach(k =>
      assertEquals(comm(rid(k)), (minRid("p", "q", "r", "s"), 4), k)
    )
    // The triangle takes its smallest label in one round (self vote 4 ties the two neighbours).
    // The pendant x ties its own label with v's (self vote 2 = the v-x weight), so it joins the
    // triangle's community exactly when that label is the smaller one.
    val tri = minRid("t", "u", "v")
    val xJoins = CodePointOrder.lessThan(tri, rid("x"))
    val triSize = if (xJoins) 4 else 3
    Seq("t", "u", "v").foreach(k => assertEquals(comm(rid(k)), (tri, triSize), k))
    assertEquals(comm(rid("x")), if (xJoins) (tri, 4) else (rid("x"), 1))
    Seq("y", "z").foreach(k => assertEquals(comm(rid(k)), (minRid("y", "z"), 2), k))
    assertEquals(comm(rid("g")), (rid("g"), 1))
    val sizes = metrics("metrics/metric_type=group_size", Full)
    assertEquals(sizes(rid("s"))("community_size"), 4.0)
    assertEquals(sizes(rid("s"))("component_size"), 8.0)
    val conv =
      r1.report("windows")(Full)("convergence").arr.find(_("algorithm").str.startsWith("label"))
    assert(conv.exists(_("converged").bool), conv.toString)
  }

  test(
    "bridges: participation coefficient (weights), cross-community degree (edges), communities connected"
  ) {
    val b = metrics("bridges", Full)
    // s: weights 3,3,3 inside, 2 to t's community: 1 - (9^2 + 2^2) / 11^2.
    assertEqualsDouble(b(rid("s"))("participation_coefficient"), 36.0 / 121.0, 1e-12)
    assertEquals(b(rid("s"))("cross_community_degree"), 1.0)
    assertEquals(b(rid("s"))("communities_connected"), 1.0)
    assertEqualsDouble(b(rid("t"))("participation_coefficient"), 1.0 - (64.0 + 4.0) / 100.0, 1e-12)
    assertEquals(b(rid("t"))("cross_community_degree"), 1.0)
    // v: weights 4, 4 inside the triangle and 2 to x (inside or outside, see the pendant rule).
    val xJoins = CodePointOrder.lessThan(minRid("t", "u", "v"), rid("x"))
    assertEqualsDouble(b(rid("v"))("participation_coefficient"), if (xJoins) 0.0 else 0.32, 1e-12)
    assertEquals(b(rid("v"))("cross_community_degree"), if (xJoins) 0.0 else 1.0)
    assertEquals(b(rid("p"))("participation_coefficient"), 0.0)
    assert(!b(rid("g")).contains("participation_coefficient"), "no participation without edges")
    assertEquals(b(rid("g"))("communities_connected"), 0.0)
  }

  test("shortest paths from one batched BFS") {
    val index = Analytics.vertexIndex(
      spark,
      spark.read
        .parquet(new File(r1.runDir, "canonical_researcher").getPath)
        .select("researcherId")
        .as[String](Encoders.STRING)
    )
    val edges = spark.read
      .parquet(new File(r1.runDir, s"coauthor_edge/window_label=$Full").getPath)
      .as[CoauthorEdgeRow]
    val eIdx = Analytics.indexEdges(edges, index)
    val idOf = index.collect().map(r => r.getString(1) -> r.getLong(0)).toMap
    val queries = Seq(("p", "x"), ("q", "u"), ("y", "p"), ("a", "e"))
    val (paths, stats) = Paths.shortestPaths(
      spark,
      index,
      eIdx,
      queries.zipWithIndex.map { case ((a, b), i) => (i, idOf(rid(a)), idOf(rid(b))) },
      6,
      Full,
      df => df.localCheckpoint()
    )
    assertEquals(paths.get(0), Some(Seq("p", "s", "t", "v", "x").map(rid)))
    assertEquals(paths.get(1), Some(Seq("q", "s", "t", "u").map(rid)))
    assertEquals(paths.get(2), None)
    assertEquals(paths.get(3), Some(Seq("a", "b", "e").map(rid)))
    assert(stats.iterations <= 6)
  }

  test(
    "materialized path evidence: shortest, hop facts from the window's CoauthorEdge, stable IDs"
  ) {
    val edges = rows[CoauthorEdgeRow](s"coauthor_edge/window_label=$Full")
    val byPair = edges.map(e => (e.left, e.right) -> e).toMap
    val adj = edges.flatMap(e => Seq(e.left -> e.right, e.right -> e.left)).groupBy(_._1).map {
      case (k, v) => k -> v.map(_._2)
    }
    def dist(a: String, b: String): Option[Int] = { // test-only BFS over the tiny fixture graph
      var frontier = Set(a); var seen = Set(a); var d = 0
      while (frontier.nonEmpty && !frontier.contains(b)) {
        frontier = frontier.flatMap(adj.getOrElse(_, Nil)) -- seen; seen ++= frontier; d += 1
      }
      if (frontier.contains(b)) Some(d) else None
    }
    val paths = rows[PathEvidenceRow](s"path_evidence/window_label=$Full")
    val p = r1.report("paths")
    assertEquals(
      p("queries").num,
      49.0
    ) // 45 top-10 pairs + 4 seeded pairs from the 8-member component
    assertEquals(paths.size.toDouble, p("found").num)
    assertEquals(p("notFoundSameComponent").num, 0.0)
    assert(paths.nonEmpty)
    paths.foreach { row =>
      val ids = row.evidence.subjectIds
      assertEquals((ids.head, ids.last), (row.sourceId, row.targetId))
      assertEquals(Some(row.hopCount), dist(row.sourceId, row.targetId))
      assertEquals(row.hops.size, ids.size - 1)
      assertEquals(
        row.evidence.evidenceId,
        EvidenceId.of(EvidenceType.GraphPath, RunId(r1.runId), ids).value
      )
      assertEquals(row.evidence.window, Some(YearWindow(2015, 2025)))
      row.hops.zip(ids.zip(ids.drop(1))).foreach { case (h, (x, y)) =>
        val key = if (CodePointOrder.lessThan(x, y)) (x, y) else (y, x)
        val e = byPair(key)
        assertEquals(
          (h.from, h.to, h.edgeId, h.weight, h.firstYear, h.lastYear, h.supportingWorkIds),
          (x, y, e.edgeId, e.weight, e.firstYear, e.lastYear, e.supportingWorkIds)
        )
      }
    }
  }

  test("every edge endpoint exists as a node; only the planted integrity violations are reported") {
    val researchers = rows[GraphNodeRow]("nodes/node_label=researcher").map(_.nodeId).toSet
    val worksN = rows[GraphNodeRow]("nodes/node_label=work").map(_.nodeId).toSet
    FixtureData.config.windows.foreach { w =>
      rows[CoauthorEdgeRow](s"coauthor_edge/window_label=${w.label}").foreach { e =>
        assert(researchers.contains(e.left) && researchers.contains(e.right), e.edgeId)
      }
    }
    rows[GraphEdgeRow]("edges/edge_label=authored").foreach(e =>
      assert(researchers(e.from) && worksN(e.to), e.edgeId)
    )
    // h-Uma's pair has an unassigned endpoint; one assignment names a mention that does not exist.
    val v = r1.report("integrityViolations").obj.map { case (k, n) => k -> n.num }.toMap
    assertEquals(v, Map("assignment_mention_missing|" -> 1.0, "pair_mention_unassigned|" -> 1.0))
  }

  test(
    "invalid input records are counted, rejected to a versioned output, never dropped silently"
  ) {
    val rej = rows[RejectRow]("rejects/v1")
    assertEquals(
      rej.groupBy(_.table).map { case (t, xs) => t -> xs.size },
      Map("work" -> 2, "identity_assignment" -> 1)
    )
    assert(rej.exists(r => r.raw == "{not json" && r.reasons.head.startsWith("decode:")))
    assert(
      rej.exists(r => r.table == "work" && r.reasons.exists(_.contains("WorkId.fromDblpKey"))),
      rej.toString
    )
    assert(
      rej.exists(r =>
        r.table == "identity_assignment" && r.reasons.head.startsWith("not an identity assignment")
      )
    )
    val counts = r1.report("rejects").obj.map { case (k, n) => k -> n.num }.toMap
    assertEquals(counts("work"), 2.0)
    assertEquals(counts("identity_assignment"), 1.0)
    assertEquals(counts("coauthor_pair_candidate"), 0.0)
    val unresolved = rows[AuthorMentionRow]("unresolved_mention")
    assertEquals(unresolved.map(_.name), Seq("Uma Unknown"))
  }

  test(
    "every exported table validates through core and sample rows round-trip through Contract.decode"
  ) {
    val outputs = r1.report("outputs").arr
    assert(outputs.size > 60, s"${outputs.size} tables")
    outputs.foreach { o =>
      assertEquals(o("invalid").num, 0.0, o("table").str + " " + o("invalidSamples").toString)
      assertEquals(o("roundTripFailures").num, 0.0, o("table").str)
    }
    // Independent round trip in the test.
    rows[ResearcherMetricsRow](s"metrics/metric_type=breadth/window_label=$Full").foreach(r =>
      assertEquals(CoreMirror.MetricsM.roundTrip(r), Right(()))
    )
    rows[CoauthorEdgeRow](s"coauthor_edge/window_label=$Full").foreach(r =>
      assertEquals(CoreMirror.CoauthorEdgeM.roundTrip(r), Right(()))
    )
    rows[GroupMembershipRow](s"communities/window_label=$Full").foreach(r =>
      assertEquals(CoreMirror.MembershipM.roundTrip(r), Right(()))
    )
    rows[PathEvidenceRow](s"path_evidence/window_label=$Full").foreach(r =>
      assertEquals(CoreMirror.PathEvidenceM.roundTrip(r), Right(()))
    )
    rows[CanonicalResearcherRow]("canonical_researcher").foreach(r =>
      assertEquals(CoreMirror.ResearcherM.roundTrip(r), Right(()))
    )
    rows[GraphNodeRow]("nodes/node_label=work").foreach(r =>
      assertEquals(CoreMirror.GraphNodeM.roundTrip(r), Right(()))
    )
    Seq("neptune", "dynamodb", "s3vectors", "s3").foreach { t =>
      val lm = ujson.read(Files2.readString(new File(r1.runDir, s"${Manifests.LoadDir}/$t.json")))
      assertEquals(lm("runId").str, r1.runId)
      assert(lm("tables").arr.nonEmpty, t)
      lm("tables").arr.foreach(x =>
        assert(!x("schemaVersion").isNull || x("coreRecord").isNull, x.toString)
      )
    }
    // Provenance: every metric row carries this run's ID.
    rows[ResearcherMetricsRow](s"kcore/window_label=$Full").foreach(r =>
      assertEquals(r.provenance.runId, r1.runId)
    )
  }

  test("semantic summaries are filled from computed data with a content hash") {
    val prof = rows[SemanticSummaryRow](
      s"semantic_summary/summary_kind=researcher_profile/window_label=$Full"
    )
    assertEquals(
      prof.map(_.evidence.subjectIds.head).toSet,
      Set("a", "b", "p", "q", "r", "s", "t", "u", "v").map(rid)
    )
    val a = prof.find(_.evidence.subjectIds == Seq(rid("a"))).get.evidence
    val text = a.snippet.get
    Seq("P=5", "C=3", "T=5", "A=1.0000", "M=2", "Alice Able", "Fixture Conference (5)").foreach(s =>
      assert(text.contains(s), text)
    )
    assertEquals(a.provenance.contentHash, Some(ContentHash.of(text)))
    val paths = rows[SemanticSummaryRow](s"semantic_summary/summary_kind=path/window_label=$Full")
    assertEquals(paths.size, rows[PathEvidenceRow](s"path_evidence/window_label=$Full").size)
    FixtureData.config.windows.foreach { w =>
      val bigCommunities = rows[GroupMembershipRow](s"communities/window_label=${w.label}")
        .filter(_.groupSize >= 5)
        .map(_.groupId)
        .distinct
      val cs =
        rows[SemanticSummaryRow](s"semantic_summary/summary_kind=community/window_label=${w.label}")
      assertEquals(cs.map(_.evidence.subjectIds.head).sorted, bigCommunities.sorted, w.label)
    }
  }

  test("determinism: the same inputs and configuration give the same run ID and identical tables") {
    assertEquals(r2.runId, r1.runId)
    assert(!r1.reused && !r2.reused)
    val outputs =
      ujson.read(Files2.readString(new File(r1.runDir, Manifests.RunManifestName)))("outputs").arr
    outputs.foreach { o =>
      val p = o("path").str
      def content(dir: File) =
        spark.read.parquet(new File(dir, p).getPath).toJSON.collect().sorted.toSeq
      assertEquals(content(r2.runDir), content(r1.runDir), p)
    }
  }

  test(
    "a table with a row failing core validation refuses the run: no load or run manifest is written"
  ) {
    val good = rows[ResearcherMetricsRow](s"metrics/metric_type=breadth/window_label=$Full")
    assert(good.size >= 2)
    // Plant one row whose identity confidence is outside [0, 1] and one with an emptied run ID
    // in its provenance; both must fail Contract.validate through the core mirror.
    val planted = good.head.copy(identityConfidence = 2.0) +:
      good(1).copy(provenance = good(1).provenance.copy(runId = "")) +: good.drop(2)
    val root = new File(tmp, "refused")
    val runDir = new File(root, r1.runDir.getName)
    val out = new OutputWriter(spark, runDir, 1)
    out.write(
      spark.createDataset(planted),
      "metrics:breadth:planted",
      s"metrics/metric_type=breadth/window_label=$Full",
      Some(CoreMirror.MetricsM),
      Some(Full),
      "(researcherId, window)",
      Seq("neptune", "dynamodb", "s3"),
      Seq("researcherId")
    )
    val tables = out.verifyAll()
    assertEquals(tables.map(_.invalid), Seq(2L))
    val manifest = ujson.read(Files2.readString(new File(r1.runDir, Manifests.RunManifestName)))
    val e = intercept[OutputVerificationException](
      Manifests.writeVerified(runDir, r1.runId, tables, manifest, ujson.Obj("runId" -> r1.runId))
    )
    assert(e.getMessage.contains("invalid=2"), e.getMessage)
    assert(!new File(runDir, Manifests.LoadDir).exists(), "no load manifests on refusal")
    assert(!new File(runDir, Manifests.RunManifestName).exists(), "no run manifest on refusal")
    val report = ujson.read(Files2.readString(new File(runDir, Manifests.RunReportName)))
    assertEquals(report("verification")("passed").bool, false)
    val failing = report("verification")("failingTables").arr
    assertEquals(failing.map(_("invalid").num).toList, List(2.0))
    assertEquals(failing.head("invalidSamples").arr.size, 2)
    // The refused directory is never reused: a later run in the same root refuses it.
    intercept[RunRefusedException](Pipeline.run(spark, args("refused")))

    // A sampled round-trip failure alone refuses the run too.
    val rtDir = new File(new File(tmp, "refused-rt"), r1.runDir.getName)
    rtDir.mkdirs()
    val rt = tables.map(_.copy(invalid = 0L, roundTripFailures = 1))
    intercept[OutputVerificationException](
      Manifests.writeVerified(rtDir, r1.runId, rt, manifest, ujson.Obj("runId" -> r1.runId))
    )
    assert(!new File(rtDir, Manifests.LoadDir).exists())
    assert(!new File(rtDir, Manifests.RunManifestName).exists())

    // A passing run's load manifests list each table's invalid-record count (zero).
    val lm =
      ujson.read(Files2.readString(new File(r1.runDir, s"${Manifests.LoadDir}/neptune.json")))
    lm("tables").arr.foreach(t => assertEquals(t("invalidRecords").num, 0.0, t.toString))
    val r1Report = ujson.read(Files2.readString(new File(r1.runDir, Manifests.RunReportName)))
    assertEquals(r1Report("verification")("passed").bool, true)
  }

  test("a reused output must match its manifest, otherwise the run refuses") {
    val again = Pipeline.run(spark, args("attempt-01"))
    assert(again.reused)
    assertEquals(again.runId, r1.runId)
    // Tamper with one exported file of the second attempt.
    val m = ujson.read(Files2.readString(new File(r2.runDir, Manifests.RunManifestName)))
    val first = m("outputs").arr.flatMap(_("files").arr).head("path").str
    Files.write(new File(r2.runDir, first).toPath, Array[Byte](1), StandardOpenOption.APPEND)
    intercept[RunRefusedException](Pipeline.run(spark, args("attempt-02")))
    // A run directory without a manifest (an interrupted run) is refused too.
    new File(new File(tmp, "attempt-03"), r1.runDir.getName).mkdirs()
    intercept[RunRefusedException](Pipeline.run(spark, args("attempt-03")))
  }

  // ---- Phase 11 repairs: B-02 (ER provenance), Phase 6 F6 (reuse check), F9 part 1 ----------

  private def copyTree(from: File, to: File): Unit = {
    val src = from.toPath
    val stream = Files.walk(src)
    try
      stream.forEach { p =>
        val target = to.toPath.resolve(src.relativize(p).toString)
        if (Files.isDirectory(p)) Files.createDirectories(target)
        else Files.copy(p, target, StandardCopyOption.COPY_ATTRIBUTES)
      }
    finally stream.close()
  }

  private def erRunDir: File = identityDir.getAbsoluteFile.getParentFile.getParentFile

  /** A copy of the fixture ER run under `name`; returns its identity assignment directory. */
  private def erCopy(name: String)(change: File => Unit): File = {
    val er = new File(new File(tmp, s"er-copies/$name"), "run=fixture")
    copyTree(erRunDir, er)
    change(er)
    new File(er, "decisions/identity_assignment")
  }

  private def refusedInput(root: String, ia: File, expected: String): Unit = {
    val e = intercept[InputRefusedException](
      Pipeline.run(spark, args(root).copy(identityAssignments = RunFs.pathOf(ia)))
    )
    assert(e.getMessage.contains(expected), e.getMessage)
    assert(!new File(tmp, root).exists(), s"no output root is created for a refused input ($root)")
  }

  test("B-02: identity assignments outside <erRun>/decisions/identity_assignment are refused") {
    val elsewhere = new File(tmp, "er-copies/loose/assignments")
    copyTree(identityDir, elsewhere)
    refusedInput("b02-layout", elsewhere, "decisions/identity_assignment")
  }

  test("B-02: an ER run without _manifest.json or without _SUCCESS is refused (not defaulted)") {
    val noManifest =
      erCopy("no-manifest")(er => Files.delete(new File(er, "_manifest.json").toPath))
    refusedInput("b02-no-manifest", noManifest, "no _manifest.json")
    val noSuccess = erCopy("no-success")(er => Files.delete(new File(er, "_SUCCESS").toPath))
    refusedInput("b02-no-success", noSuccess, "no _SUCCESS")
  }

  test("B-02: ER manifest fields are required and its output hashes must match the files read") {
    def editManifest(name: String)(f: ujson.Value => Unit): File = erCopy(name) { er =>
      val mf = new File(er, "_manifest.json")
      val m = ujson.read(Files2.readString(mf))
      f(m)
      Files2.writeString(mf, ujson.write(m))
    }
    refusedInput(
      "b02-no-policy",
      editManifest("no-policy")(_.obj.remove("policyVersion")),
      "'policyVersion'"
    )
    refusedInput(
      "b02-bad-format",
      editManifest("bad-format")(_("format") = "something-else"),
      "format"
    )
    refusedInput(
      "b02-no-outputs",
      editManifest("no-outputs")(_("outputs") = ujson.Arr()),
      "lists no output"
    )
    // A partial or changed assignment file: its size and hash no longer match the manifest.
    val changed = erCopy("changed") { er =>
      val part = new File(er, "decisions/identity_assignment/part-00000.jsonl").toPath
      Files.write(part, "\n".getBytes("UTF-8"), StandardOpenOption.APPEND)
    }
    refusedInput("b02-changed", changed, "size differs")
    // A file the ER run did not write.
    val extra = erCopy("extra") { er =>
      Files2.writeString(new File(er, "decisions/identity_assignment/part-00001.jsonl"), "")
    }
    refusedInput("b02-extra", extra, "unlisted file")
  }

  test("B-02: the ER run and policy version are recorded in the run manifest, report and loads") {
    val m = ujson.read(Files2.readString(new File(r1.runDir, Manifests.RunManifestName)))
    val ia = m("inputs")("identityAssignments")
    assertEquals(ia("runId").str, FixtureData.erRun.value)
    assertEquals(ia("jobName").str, "er.resolve")
    assertEquals(ia("codeVersion").str, "fixture")
    assertEquals(ia("policyVersion").str, "er-v1")
    val pv = ujson.Obj("graph" -> FixtureData.config.policyVersion, "entityResolution" -> "er-v1")
    assertEquals(m("policyVersions"), pv: ujson.Value)
    assertEquals(r1.report("policyVersions"), pv: ujson.Value)
    Manifests.LoadTargets.foreach { t =>
      val lm = ujson.read(Files2.readString(new File(r1.runDir, s"${Manifests.LoadDir}/$t.json")))
      assertEquals(lm("policyVersions"), pv: ujson.Value, t)
    }
  }

  /** A copy of the first run's directory under a new attempt root, changed by `change`. */
  private def runCopy(root: String)(change: File => Unit): Unit = {
    val dir = new File(new File(tmp, root), r1.runDir.getName)
    copyTree(r1.runDir, dir)
    change(dir)
  }

  private def refusedReuse(root: String, expected: String): Unit = {
    val e = intercept[RunRefusedException](Pipeline.run(spark, args(root)))
    assert(e.getMessage.contains(expected), e.getMessage)
  }

  test("F6: the run manifest hashes _run_report.json and every load manifest") {
    val m = ujson.read(Files2.readString(new File(r1.runDir, Manifests.RunManifestName)))
    val control = m("controlFiles").arr.map(f => f("path").str -> f("sha256").str).toMap
    assertEquals(control.keySet, Manifests.ControlFiles.toSet)
    control.foreach { case (p, sha) =>
      assertEquals(Files2.sha256(new File(r1.runDir, p)), sha, p)
    }
    // An unchanged copy is reused.
    runCopy("f6-clean")(_ => ())
    assert(Pipeline.run(spark, args("f6-clean")).reused)
  }

  test("F6: reuse is refused when the run report or a load manifest changed") {
    runCopy("f6-report") { d =>
      Files.write(
        new File(d, Manifests.RunReportName).toPath,
        " ".getBytes("UTF-8"),
        StandardOpenOption.APPEND
      )
    }
    refusedReuse("f6-report", Manifests.RunReportName)
    runCopy("f6-load") { d =>
      val f = new File(d, s"${Manifests.LoadDir}/s3.json")
      val lm = ujson.read(Files2.readString(f))
      lm("runId") = "run:v1:0000"
      Files2.writeString(f, ujson.write(lm, indent = 2))
    }
    refusedReuse("f6-load", s"${Manifests.LoadDir}/s3.json")
    runCopy("f6-load-extra") { d =>
      Files2.writeString(new File(d, s"${Manifests.LoadDir}/opensearch.json"), "{}")
    }
    refusedReuse("f6-load-extra", "unlisted control file")
  }

  test("F6: reuse is refused when the recorded code version differs or control hashes are absent") {
    def editManifest(root: String)(f: ujson.Value => Unit): Unit = runCopy(root) { d =>
      val mf = new File(d, Manifests.RunManifestName)
      val m = ujson.read(Files2.readString(mf))
      f(m)
      Files2.writeString(mf, ujson.write(m, indent = 2))
    }
    editManifest("f6-code")(_("codeVersion") = "other-code")
    refusedReuse("f6-code", "codeVersion other-code != current fixture-code")
    editManifest("f6-old")(_.obj.remove("controlFiles"))
    refusedReuse("f6-old", "no controlFiles")
  }

  test("F9 part 1: the EMR entry point turns automatic broadcast joins off") {
    val keys = Seq(
      "spark.sql.autoBroadcastJoinThreshold",
      "spark.sql.adaptive.autoBroadcastJoinThreshold"
    )
    keys.foreach(k => spark.conf.set(k, "10485760"))
    try {
      // runWith is main's path; it applies the settings before it parses the arguments.
      intercept[IllegalArgumentException](GraphAnalyticsJob.runWith(spark, Nil))
      keys.foreach(k => assertEquals(spark.conf.get(k), "-1", k))
      assertEquals(
        GraphAnalyticsJob.RequiredSqlConf.toMap,
        keys.map(_ -> "-1").toMap
      )
    } finally keys.foreach(k => spark.conf.set(k, "-1"))
  }
}
