package org.scholarchain.spark

import java.io.File

import scala.concurrent.duration._

import org.apache.spark.sql.{Encoder, Encoders, SparkSession}
import org.scholarchain.core._
import org.scholarchain.spark.FixtureData.Slot
import org.scholarchain.spark.KnownGraphInputs._

/** Known-graph adversarial cases of the Phase 11 step-1a case sheet (test-inventory.md, Part 3),
  * with the Phase 6 F3 integrity plants. Expected values are the sheet's hand-derived values.
  *
  * Every case except S1a (an empty run) is one disjoint subgraph of ONE pipeline run: people are
  * named `<case>.<letter>`, and every per-researcher value (P, C, T, A, M, W, coreness, component,
  * community) depends only on the researcher's own subgraph. Integrity violations are global, so
  * the expected violation set is the union of the planted ones (F3a-F3d, I3); F3e (base S3) and
  * every other case must add none. The sheet's letter order is not the ResearcherId order
  * (Pipeline-level IDs follow the anchor rule), so a component letter is read as "the smallest
  * member ID of the researchers carrying that letter".
  */
class KnownGraphSuite extends munit.FunSuite {

  override val munitTimeout: Duration = 15.minutes

  private var tmp: File = _
  private var spark: SparkSession = _
  private var run: PipelineResult = _

  private val Full = "full-2015-2025"

  // ---- Inputs (case sheet "Inputs" table) -----------------------------------------------------

  private def w(c: String, code: String, year: Int, people: String*): KWork =
    KWork(s"$c-$code", year, people.map(p => Slot(s"$c $p", Some(s"$c.$p"))).toList)

  private def s3(c: String): List[KWork] =
    List(w(c, "w1", 2016, "A", "B"), w(c, "w2", 2019, "A", "B"), w(c, "w3", 2021, "C", "D"))

  private def s7(c: String): List[KWork] = List(
    w(c, "w1", 2016, "A", "B"),
    w(c, "w2", 2016, "A", "C"),
    w(c, "w3", 2017, "B", "C"),
    w(c, "w4", 2018, "D", "E"),
    w(c, "w5", 2018, "D", "F"),
    w(c, "w6", 2019, "E", "F"),
    w(c, "w7", 2020, "C", "D")
  )

  private val t30People = (1 to 30).map(i => f"R$i%02d")

  private val spec = Spec(
    works = List(
      List(w("S1b", "w1", 2014, "A", "B")),
      List(w("S2", "w1", 2016, "A"), w("S2", "w2", 2018, "A")),
      s3("S3"),
      List(
        w("S4", "w1", 2016, "A", "B"),
        w("S4", "w2", 2017, "A", "C"),
        w("S4", "w3", 2018, "A", "D"),
        w("S4", "w4", 2019, "A", "E")
      ),
      List(
        w("S5", "w1", 2016, "A", "B"),
        w("S5", "w2", 2017, "B", "C"),
        w("S5", "w3", 2018, "C", "D"),
        w("S5", "w4", 2019, "D", "E")
      ),
      List(w("S6", "w1", 2016, "A", "B", "C", "D"), w("S6", "w2", 2018, "A", "B")),
      s7("S7"),
      s7("S7w") :+ w("S7w", "w8", 2021, "C", "D"),
      List(
        KWork(
          "I1-w1",
          2016,
          List(
            Slot("I1 A", Some("I1.A")),
            Slot("I1 A Alt", Some("I1.A")),
            Slot("I1 B", Some("I1.B"))
          )
        )
      ),
      List(
        w("I2", "w1", 2016, "A", "B"),
        w("I2", "w2", 2016, "A", "B"),
        w("I2", "w3", 2019, "A", "B")
      ),
      List(w("T1", "w1", 2016, "A")),
      List(w("T2", "w1", 2016, "A", "B")),
      List(w("T3", "w1", 2016, "A", "B", "C")),
      List(w("T30", "w1", 2016, t30People: _*)),
      List(
        KWork(
          "I3-w1",
          2016,
          List(Slot("I3 A", Some("I3.A")), Slot("I3 B", Some("I3.B")), Slot("I3 Unknown", None))
        )
      ),
      List(w("I4", "w1", 2016, "A", "B"), w("I4", "w2", 2017, "B", "C")),
      s3("F3a"),
      s3("F3b"),
      s3("F3c"),
      s3("F3d"),
      List(
        w("K1", "w1", 2016, "A", "B", "C", "D", "E"),
        w("K1", "w2", 2016, "F", "G", "H", "I", "J"),
        w("K1", "w3", 2017, "E", "F")
      ),
      List(w("K2", "w1", 2016, "A", "B", "C", "D"), w("K2", "w2", 2016, "E", "F", "G", "H")),
      List(w("K3", "w1", 2016, "A", "B"), w("K3", "w2", 2017, "C"))
    ).flatten,
    identities = Map(
      "I4.A" -> Identity(0.5, MatchOutcome.Conflict),
      "I4.B" -> Identity(1.0, MatchOutcome.Accepted),
      "I4.C" -> Identity(0.9, MatchOutcome.Accepted)
    ),
    teamSizeOverride = Map("F3b-w3" -> 3),
    pairYearOverride = Map("F3c-w1" -> 2017),
    dropPairs = Set("F3d-w3"),
    duplicatePairs = Set("I2-w1"),
    orphans = List(OrphanPlant("F3a-w1", 0, 9, "F3a Zed", "F3a.Z"))
  )

  private def args(h: File, ia: File, root: String): PipelineArgs =
    PipelineArgs(h, ia, new File(tmp, root), FixtureData.config, "known-graph", 0L, Long.MaxValue)

  override def beforeAll(): Unit = {
    tmp = SparkTestSupport.tempDir("known-graph")
    spark = SparkTestSupport.session(tmp)
    val (h, ia) = KnownGraphInputs.write(new File(tmp, "inputs"), spec)
    run = Pipeline.run(spark, args(h, ia, "out"))
  }

  override def afterAll(): Unit = if (spark != null) spark.stop()

  // ---- Readers ------------------------------------------------------------------------------

  private implicit val metricsEnc: Encoder[ResearcherMetricsRow] =
    Encoders.product[ResearcherMetricsRow]
  private implicit val edgeEnc: Encoder[CoauthorEdgeRow] = Encoders.product[CoauthorEdgeRow]
  private implicit val memberEnc: Encoder[GroupMembershipRow] = Encoders.product[GroupMembershipRow]
  private implicit val metricEvEnc: Encoder[MetricEvidenceRow] = Encoders.product[MetricEvidenceRow]
  private implicit val nodeEnc: Encoder[GraphNodeRow] = Encoders.product[GraphNodeRow]
  private implicit val gEdgeEnc: Encoder[GraphEdgeRow] = Encoders.product[GraphEdgeRow]
  private implicit val mentionEnc: Encoder[AuthorMentionRow] = Encoders.product[AuthorMentionRow]
  private implicit val violEnc: Encoder[IntegrityViolationRow] =
    Encoders.product[IntegrityViolationRow]

  private val cache = scala.collection.mutable.Map.empty[String, Seq[Any]]

  private def rows[R: Encoder](rel: String): Seq[R] =
    cache
      .getOrElseUpdate(
        rel,
        spark.read.parquet(new File(run.runDir, rel).getPath).as[R].collect().toSeq
      )
      .asInstanceOf[Seq[R]]

  private def rid(p: String): String = spec.rid(p)
  private def wid(code: String): String = spec.work(code).workId.value
  private def label(window: String): String = if (window == "2015-2025") Full else window

  private def metrics(table: String, window: String): Map[String, Map[String, Double]] =
    rows[ResearcherMetricsRow](s"$table/window_label=$window")
      .map(r => r.researcherId -> r.values.map(v => v.metric -> v.value).toMap)
      .toMap

  private def breadth(window: String) = metrics("metrics/metric_type=breadth", window)
  private def coreness(window: String) =
    metrics("kcore", window).map { case (k, v) => k -> v("coreness") }
  private def components(window: String): Map[String, (String, Long)] =
    rows[GroupMembershipRow](s"components/window_label=$window")
      .map(r => r.researcherId -> (r.groupId, r.groupSize.toLong))
      .toMap
  private def communities(window: String): Map[String, String] =
    rows[GroupMembershipRow](s"communities/window_label=$window")
      .map(r => r.researcherId -> r.groupId)
      .toMap
  private def edges(window: String): Seq[CoauthorEdgeRow] =
    rows[CoauthorEdgeRow](s"coauthor_edge/window_label=$window")
  private def edge(window: String, a: String, b: String): Option[CoauthorEdgeRow] =
    edges(window).find(e => Set(e.left, e.right) == Set(rid(a), rid(b)))
  private def identityConfidence(window: String): Map[String, Double] =
    rows[ResearcherMetricsRow](s"metrics/metric_type=breadth/window_label=$window")
      .map(r => r.researcherId -> r.identityConfidence)
      .toMap
  private def minRid(people: Seq[String]): String = people.map(rid).min(CodePointOrder.ordering)

  /** People of one case (from the spec, plus planted ones). */
  private def peopleOf(c: String): Set[String] = spec.researcher.keySet.filter(_.startsWith(s"$c."))

  final case class Expect(
      p: Double,
      c: Double,
      t: Double,
      a: Double,
      m: Double,
      w: Double,
      core: Double,
      comp: String,
      size: Long
  )

  private def assertResearcher(window: String, person: String, e: Expect)(implicit
      loc: munit.Location
  ): Unit = {
    val v = breadth(window).getOrElse(rid(person), fail(s"no breadth row for $person in $window"))
    val got = (
      v("publication_count"),
      v("distinct_coauthors"),
      v("total_coauthor_occurrences"),
      v("average_coauthors_per_paper"),
      v("max_coauthors_on_one_paper"),
      v("weighted_degree"),
      coreness(window)(rid(person)),
      components(window)(rid(person))._2
    )
    assertEquals(got, (e.p, e.c, e.t, e.a, e.m, e.w, e.core, e.size), s"$person in $window")
  }

  // ---- Expected per-researcher values (sheet, machine-readable block) ----------------------

  private val rawSheet =
    """S2|2015-2025|A|2|0|0|0.0|0|0.0|0|A|1
      |S3|2015-2025|A|2|1|2|1.0|1|2.0|1|A|2
      |S3|2015-2025|B|2|1|2|1.0|1|2.0|1|A|2
      |S3|2015-2025|C|1|1|1|1.0|1|1.0|1|C|2
      |S3|2015-2025|D|1|1|1|1.0|1|1.0|1|C|2
      |S4|2015-2025|A|4|4|4|1.0|1|4.0|1|A|5
      |S4|2015-2025|B|1|1|1|1.0|1|1.0|1|A|5
      |S4|2015-2025|C|1|1|1|1.0|1|1.0|1|A|5
      |S4|2015-2025|D|1|1|1|1.0|1|1.0|1|A|5
      |S4|2015-2025|E|1|1|1|1.0|1|1.0|1|A|5
      |S5|2015-2025|A|1|1|1|1.0|1|1.0|1|A|5
      |S5|2015-2025|B|2|2|2|1.0|1|2.0|1|A|5
      |S5|2015-2025|C|2|2|2|1.0|1|2.0|1|A|5
      |S5|2015-2025|D|2|2|2|1.0|1|2.0|1|A|5
      |S5|2015-2025|E|1|1|1|1.0|1|1.0|1|A|5
      |S6|2015-2025|A|2|3|4|2.0|3|4.0|3|A|4
      |S6|2015-2025|B|2|3|4|2.0|3|4.0|3|A|4
      |S6|2015-2025|C|1|3|3|3.0|3|3.0|3|A|4
      |S6|2015-2025|D|1|3|3|3.0|3|3.0|3|A|4
      |S7|2015-2025|A|2|2|2|1.0|1|2.0|2|A|6
      |S7|2015-2025|B|2|2|2|1.0|1|2.0|2|A|6
      |S7|2015-2025|C|3|3|3|1.0|1|3.0|2|A|6
      |S7|2015-2025|D|3|3|3|1.0|1|3.0|2|A|6
      |S7|2015-2025|E|2|2|2|1.0|1|2.0|2|A|6
      |S7|2015-2025|F|2|2|2|1.0|1|2.0|2|A|6
      |S7|w2015-2017|A|2|2|2|1.0|1|2.0|2|A|3
      |S7|w2015-2017|B|2|2|2|1.0|1|2.0|2|A|3
      |S7|w2015-2017|C|2|2|2|1.0|1|2.0|2|A|3
      |S7|w2015-2017|D,E,F|no rows
      |S7|w2018-2020|C|1|1|1|1.0|1|1.0|1|C|4
      |S7|w2018-2020|D|3|3|3|1.0|1|3.0|2|C|4
      |S7|w2018-2020|E|2|2|2|1.0|1|2.0|2|C|4
      |S7|w2018-2020|F|2|2|2|1.0|1|2.0|2|C|4
      |S7|w2018-2020|A,B|no rows
      |S7w|2015-2025|C|4|3|4|1.0|1|4.0|2|A|6
      |S7w|2015-2025|D|4|3|4|1.0|1|4.0|2|A|6
      |S7w|2015-2025|A,B,E,F|as S7""".stripMargin
      .split("\n")
      .toList
      .map(_.split("\\|").toList)

  /** The sheet with each "as S7" row replaced by the S7 rows of the same window and letters. */
  private val sheet: List[List[String]] = rawSheet.flatMap {
    case List(c, win, who, "as S7") =>
      who.split(",").toList.map { x =>
        rawSheet.collectFirst { case "S7" :: `win` :: `x` :: rest => c :: win :: x :: rest }.get
      }
    case row => List(row)
  }

  test("case sheet: P, C, T, A, M, W, coreness and component size per researcher and window") {
    sheet.foreach {
      case List(c, win, who, "no rows") =>
        who.split(",").foreach { x =>
          assert(!breadth(label(win)).contains(rid(s"$c.$x")), s"$c.$x must have no row in $win")
        }
      case List(c, win, x, p, cc, t, a, m, ww, core, comp, size) =>
        assertResearcher(
          label(win),
          s"$c.$x",
          Expect(
            p.toDouble,
            cc.toDouble,
            t.toDouble,
            a.toDouble,
            m.toDouble,
            ww.toDouble,
            core.toDouble,
            comp,
            size.toLong
          )
        )
      case other => fail(s"unparsed sheet row $other")
    }
  }

  test("case sheet: component IDs are the smallest member ID of each lettered component") {
    val lettered = sheet.collect { case List(c, win, x, _, _, _, _, _, _, _, comp, _) =>
      (c, label(win), comp, s"$c.$x")
    }
    lettered.groupBy(r => (r._1, r._2, r._3)).foreach { case ((c, win, comp), rs) =>
      val members = rs.map(_._4)
      val expected = minRid(members)
      members.foreach(p =>
        assertEquals(components(win)(rid(p))._1, expected, s"$c component $comp member $p in $win")
      )
    }
    // Two components in S3 (letters A and C) are distinct.
    assertNotEquals(components(Full)(rid("S3.A"))._1, components(Full)(rid("S3.C"))._1)
  }

  test("case sheet S1b: works outside every window give nodes but no rows and no edges") {
    val nodes = rows[GraphNodeRow]("nodes/node_label=researcher").map(_.nodeId).toSet
    Seq("S1b.A", "S1b.B").foreach(p => assert(nodes(rid(p)), p))
    FixtureData.config.windows.foreach { nw =>
      Seq("S1b.A", "S1b.B").foreach { p =>
        assert(!breadth(nw.label).contains(rid(p)), s"$p has a row in ${nw.label}")
      }
      assert(edge(nw.label, "S1b.A", "S1b.B").isEmpty, nw.label)
    }
  }

  // ---- Hops ------------------------------------------------------------------------------

  private val hopSheet =
    """S3|A-B|1
      |S3|A-C|none
      |S3|C-D|1
      |S4|A-E|1
      |S4|B-C|2
      |S4|B-E|2
      |S5|A-C|2
      |S5|B-D|2
      |S5|A-E|4
      |S6|A-D|1
      |S6|C-D|1
      |S7|A-B|1
      |S7|C-D|1
      |S7|A-D|2
      |S7|A-F|3
      |S7|B-E|3
      |S7(w2018-2020)|C-F|2
      |S7(w2018-2020)|A-F|none (A not a vertex)
      |S2|A-A|0 (source only)""".stripMargin.split("\n").toList.map(_.split("\\|").toList)

  test("case sheet: shortest-path hops from one batched BFS per window") {
    val index = Analytics.vertexIndex(
      spark,
      spark.read
        .parquet(new File(run.runDir, "canonical_researcher").getPath)
        .select("researcherId")
        .as[String](Encoders.STRING)
    )
    val idOf = index.collect().map(r => r.getString(1) -> r.getLong(0)).toMap
    val parsed = hopSheet.map { case List(cw, pair, hops) =>
      val (c, win) =
        if (cw.contains("(")) (cw.takeWhile(_ != '('), cw.dropWhile(_ != '(').drop(1).dropRight(1))
        else (cw, Full)
      val Array(a, b) = pair.split("-")
      val h = hops.takeWhile(_ != ' ')
      (win, s"$c.$a", s"$c.$b", if (h == "none") None else Some(h.toInt))
    }
    parsed.groupBy(_._1).foreach { case (win, qs) =>
      val es = spark.read
        .parquet(new File(run.runDir, s"coauthor_edge/window_label=$win").getPath)
        .as[CoauthorEdgeRow]
      val eIdx = Analytics.indexEdges(es, index)
      val (paths, _) = Paths.shortestPaths(
        spark,
        index,
        eIdx,
        qs.zipWithIndex.map { case ((_, a, b, _), i) => (i, idOf(rid(a)), idOf(rid(b))) },
        6,
        win,
        df => df.localCheckpoint()
      )
      qs.zipWithIndex.foreach { case ((_, a, b, hops), i) =>
        val p = paths.get(i)
        assertEquals(p.map(_.size - 1), hops, s"$a-$b in $win: $p")
        p.foreach(path => assertEquals((path.head, path.last), (rid(a), rid(b))))
      }
    }
  }

  // ---- Bridges under a stated community map (fed directly to Analytics.bridges) ----------

  private val bridgeSheet =
    """S4|X={A,B,C} Y={D,E}|A|0.5|2|1
      |S4|X={A,B,C} Y={D,E}|B|0.0|0|0
      |S4|X={A,B,C} Y={D,E}|D|0.0|1|1
      |S4|X={A,B,C} Y={D,E}|E|0.0|1|1
      |S5|X={A,B,C} Y={D,E}|A|0.0|0|0
      |S5|X={A,B,C} Y={D,E}|B|0.0|0|0
      |S5|X={A,B,C} Y={D,E}|C|0.5|1|1
      |S5|X={A,B,C} Y={D,E}|D|0.5|1|1
      |S5|X={A,B,C} Y={D,E}|E|0.0|0|0
      |S6|X={A,B} Y={C,D}|A|0.5|2|1
      |S6|X={A,B} Y={C,D}|B|0.5|2|1
      |S6|X={A,B} Y={C,D}|C|0.4444444444|2|1
      |S6|X={A,B} Y={C,D}|D|0.4444444444|2|1
      |S7|X={A,B,C} Y={D,E,F}|A|0.0|0|0
      |S7|X={A,B,C} Y={D,E,F}|C|0.4444444444|1|1
      |S7|X={A,B,C} Y={D,E,F}|D|0.4444444444|1|1
      |S7|X={A,B,C} Y={D,E,F}|F|0.0|0|0
      |S7w|X={A,B,C} Y={D,E,F}|C|0.5|1|1
      |S7w|X={A,B,C} Y={D,E,F}|D|0.5|1|1
      |S2|X={A}|A|absent (W=0)|0|0""".stripMargin.split("\n").toList.map(_.split("\\|").toList)

  test("case sheet: participation, cross-community degree and communities connected") {
    val ss = spark
    import ss.implicits._
    val index = Analytics.vertexIndex(
      spark,
      spark.read
        .parquet(new File(run.runDir, "canonical_researcher").getPath)
        .select("researcherId")
        .as[String](Encoders.STRING)
    )
    val idOf = index.collect().map(r => r.getString(1) -> r.getLong(0)).toMap
    val eIdx = Analytics.indexEdges(
      spark.read
        .parquet(new File(run.runDir, s"coauthor_edge/window_label=$Full").getPath)
        .as[CoauthorEdgeRow],
      index
    )
    bridgeSheet.groupBy(r => (r(0), r(1))).foreach { case ((c, map), rs) =>
      // "X={A,B,C} Y={D,E}" -> person -> community code
      val comm = map.split(" ").toList.zipWithIndex.flatMap { case (part, code) =>
        part.dropWhile(_ != '{').drop(1).dropRight(1).split(",").map(x => s"$c.$x" -> code.toLong)
      }
      val commDf = comm.map { case (p, code) => (idOf(rid(p)), code) }.toDF("id", "label")
      val got = Analytics
        .bridges(eIdx, commDf)
        .collect()
        .map(r =>
          r.getLong(0) -> (
            if (r.isNullAt(1)) None else Some(r.getDouble(1)),
            r.getLong(2),
            r.getLong(3)
          )
        )
        .toMap
      rs.foreach { case List(_, _, x, pc, cross, connected) =>
        val id = idOf(rid(s"$c.$x"))
        if (pc.startsWith("absent")) {
          assert(!got.contains(id), s"$c.$x has no edges, so no bridge row (absent, W=0)")
          // The pipeline's coalesce gives cross = connected = 0.
          val b = metrics("bridges", Full)(rid(s"$c.$x"))
          assert(!b.contains("participation_coefficient"), b.toString)
          assertEquals((b("cross_community_degree"), b("communities_connected")), (0.0, 0.0))
        } else {
          val (gpc, gcross, gconn) = got.getOrElse(id, fail(s"no bridge row for $c.$x"))
          assertEqualsDouble(gpc.get, pc.toDouble, 1e-9, s"PC of $c.$x")
          assertEquals((gcross, gconn), (cross.toLong, connected.toLong), s"$c.$x")
        }
      }
    }
  }

  // ---- Identity and team cases ------------------------------------------------------------

  test("I1: a self pair created by identity resolution is dropped; team size stays the dblp one") {
    assertEquals(run.report("build")("erSelfPairsDropped").num, 1.0)
    val ab = edge(Full, "I1.A", "I1.B").getOrElse(fail("no I1 A-B edge"))
    assertEquals((ab.weight, ab.supportingWorkIds), (1.0, Seq(wid("I1-w1"))))
    assertEquals(
      edges(Full).count(e => Set(e.left, e.right).subsetOf(Set(rid("I1.A"), rid("I1.B")))),
      1
    )
    assertResearcher(Full, "I1.A", Expect(1, 1, 1, 1.0, 1, 1.0, 1, "A", 2))
    val ev = rows[MetricEvidenceRow](s"evidence/evidence_type=metric/window_label=$Full")
      .find(_.researcherId == rid("I1.A"))
      .get
    assertEquals(ev.works, Seq(WorkTeamSize(wid("I1-w1"), 2, 3)))
    val authored = rows[GraphEdgeRow]("edges/edge_label=authored")
      .filter(e => e.from == rid("I1.A") && e.to == wid("I1-w1"))
    assertEquals(authored.size, 1)
    val wk = spec.work("I1-w1")
    assertEquals(
      authored.head.provenance.sourceRecordIds,
      Seq(wk.mention(0).value, wk.mention(1).value).sorted
    )
  }

  test("I2: the same pair on several works counts distinct works; a duplicate pair row adds none") {
    val e = edge(Full, "I2.A", "I2.B").getOrElse(fail("no I2 edge"))
    assertEquals(
      (e.weight, e.sharedWorkCount, e.firstYear, e.lastYear, e.supportingWorkIds),
      (3.0, 3, 2016, 2019, Seq("I2-w1", "I2-w2", "I2-w3").map(wid).sorted(CodePointOrder.ordering))
    )
    assertResearcher(Full, "I2.A", Expect(3, 1, 3, 1.0, 1, 3.0, 1, "A", 2))
    val mid = edge("w2018-2020", "I2.A", "I2.B").getOrElse(fail("no I2 edge in w2018-2020"))
    assertEquals(
      (mid.weight, mid.firstYear, mid.lastYear, mid.supportingWorkIds),
      (1.0, 2019, 2019, Seq(wid("I2-w3")))
    )
  }

  test("T1, T2, T3, T30: one work with n researchers gives n(n-1)/2 edges and C = T = M = n - 1") {
    def caseEdges(c: String) = {
      val ids = peopleOf(c).map(rid)
      edges(Full).filter(e => ids(e.left) && ids(e.right))
    }
    assertEquals(caseEdges("T1").size, 0)
    assertResearcher(Full, "T1.A", Expect(1, 0, 0, 0.0, 0, 0.0, 0, "A", 1))
    assertEquals(caseEdges("T2").size, 1)
    Seq("A", "B").foreach(x =>
      assertResearcher(Full, s"T2.$x", Expect(1, 1, 1, 1.0, 1, 1.0, 1, "A", 2))
    )
    assertEquals(caseEdges("T3").size, 3)
    Seq("A", "B", "C").foreach(x =>
      assertResearcher(Full, s"T3.$x", Expect(1, 2, 2, 2.0, 2, 2.0, 2, "A", 3))
    )
    val t30 = caseEdges("T30")
    assertEquals(t30.size, 435)
    t30.foreach(e => assertEquals((e.weight, e.supportingWorkIds), (1.0, Seq(wid("T30-w1")))))
    t30People.foreach(x =>
      assertResearcher(Full, s"T30.$x", Expect(1, 29, 29, 29.0, 29, 29.0, 29, "R01", 30))
    )
    assertEquals(
      components(Full)(rid("T30.R01"))._1,
      minRid(t30People.map(x => s"T30.$x"))
    )
    val ev = rows[MetricEvidenceRow](s"evidence/evidence_type=metric/window_label=$Full")
      .find(_.researcherId == rid("T30.R07"))
      .get
    assertEquals(ev.works, Seq(WorkTeamSize(wid("T30-w1"), 30, 30)))
  }

  test("I3: an unassigned mention is reported, never merged, and creates no researcher") {
    val unresolved = rows[AuthorMentionRow]("unresolved_mention")
    assertEquals(unresolved.filter(_.workId == wid("I3-w1")).map(_.name), Seq("I3 Unknown"))
    assertEquals(edges(Full).count(e => peopleOf("I3").map(rid).contains(e.left)), 1)
    Seq("A", "B").foreach(x =>
      assertResearcher(Full, s"I3.$x", Expect(1, 1, 1, 1.0, 1, 1.0, 1, "A", 2))
    )
    val ev = rows[MetricEvidenceRow](s"evidence/evidence_type=metric/window_label=$Full")
      .find(_.researcherId == rid("I3.A"))
      .get
    assertEquals(ev.works, Seq(WorkTeamSize(wid("I3-w1"), 2, 3)))
    val i3Nodes = rows[GraphNodeRow]("nodes/node_label=researcher").filter(n =>
      rows[GraphEdgeRow]("edges/edge_label=authored").exists(e =>
        e.from == n.nodeId && e.to == wid("I3-w1")
      )
    )
    assertEquals(i3Nodes.size, 2)
  }

  test("I4 / Phase 6 F3: a CONFLICT endpoint is kept, and its confidence is carried") {
    assertEquals(edge(Full, "I4.A", "I4.B").map(_.minIdentityConfidence), Some(0.5))
    assertEquals(edge(Full, "I4.B", "I4.C").map(_.minIdentityConfidence), Some(0.9))
    val nodes = rows[GraphNodeRow]("nodes/node_label=researcher").map(n => n.nodeId -> n).toMap
    assertEquals(nodes(rid("I4.A")).properties("identityOutcome"), "conflict")
    assertEquals(nodes(rid("I4.B")).properties("identityOutcome"), "accepted")
    assertEquals(nodes(rid("I4.C")).properties("identityOutcome"), "accepted")
    val conf = identityConfidence(Full)
    assertEquals(
      (conf(rid("I4.A")), conf(rid("I4.B")), conf(rid("I4.C"))),
      (0.5, 0.5, 0.9)
    )
    val b = breadth(Full)
    def pcw(x: String) = {
      val v = b(rid(s"I4.$x"))
      (v("publication_count"), v("distinct_coauthors"), v("weighted_degree"))
    }
    assertEquals(pcw("A"), (1.0, 1.0, 1.0))
    assertEquals(pcw("B"), (2.0, 2.0, 2.0))
    assertEquals(b(rid("I4.B"))("total_coauthor_occurrences"), 2.0)
    assertEquals(pcw("C"), (1.0, 1.0, 1.0))
  }

  // ---- Phase 6 F3: planted integrity checks ------------------------------------------------

  test("F3a-F3e: each planted integrity violation is reported once; base S3 and other cases none") {
    val got = rows[IntegrityViolationRow]("integrity_violations/v1")
      .map(v => (v.check, v.windowLabel, v.subjectId))
      .sorted
    def pairKey(a: String, b: String) = {
      val (x, y) = (rid(a), rid(b))
      if (CodePointOrder.lessThan(x, y)) s"$x|$y" else s"$y|$x"
    }
    val f3a = spec.work("F3a-w1")
    val i3 = spec.work("I3-w1")
    val expected = Seq(
      ("assignment_mention_missing", "", f3a.mention(9).value),
      ("pair_not_in_team", "", pairKey("F3a.A", "F3a.Z")),
      ("coauthor_edge_endpoint_missing", Full, rid("F3a.Z")),
      ("coauthor_edge_endpoint_missing", "w2015-2017", rid("F3a.Z")),
      ("coauthor_edge_endpoint_inactive", Full, rid("F3a.Z")),
      ("coauthor_edge_endpoint_inactive", "w2015-2017", rid("F3a.Z")),
      ("team_size_mismatch", "", wid("F3b-w3")),
      ("pair_year_mismatch", "", wid("F3c-w1")),
      ("team_pair_without_candidate", "", pairKey("F3d.C", "F3d.D")),
      ("pair_mention_unassigned", "", i3.workId.value),
      ("pair_mention_unassigned", "", i3.workId.value)
    ).sorted
    assertEquals(got, expected)
    val details = rows[IntegrityViolationRow]("integrity_violations/v1")
    assertEquals(
      details.filter(_.check == "team_size_mismatch").map(_.detail),
      Seq("teamSize 3 vs mentions 2")
    )
    assertEquals(
      details.filter(_.check == "pair_not_in_team").map(_.detail),
      Seq(s"work ${wid("F3a-w1")}")
    )
    assertEquals(
      details.filter(_.check == "team_pair_without_candidate").map(_.detail),
      Seq(s"work ${wid("F3d-w3")}")
    )
  }

  test("F3a: the edge to an endpoint without authorship is counted in C and W, not in P and T") {
    val v = breadth(Full)(rid("F3a.A"))
    assertEquals(
      (
        v("publication_count"),
        v("distinct_coauthors"),
        v("total_coauthor_occurrences"),
        v("weighted_degree")
      ),
      (2.0, 2.0, 2.0, 3.0)
    )
    assert(edge(Full, "F3a.A", "F3a.Z").isDefined)
  }

  test("F3c and F3d: a wrong pair year keeps the work year; a missing pair row gives no edge") {
    val e = edge(Full, "F3c.A", "F3c.B").getOrElse(fail("no F3c edge"))
    assertEquals((e.weight, e.firstYear, e.lastYear), (2.0, 2016, 2019))
    assert(edge(Full, "F3d.C", "F3d.D").isEmpty)
    Seq("C", "D").foreach(x =>
      assertResearcher(Full, s"F3d.$x", Expect(1, 0, 1, 1.0, 1, 0.0, 0, x, 1))
    )
  }

  // ---- Community cases (same / different only) --------------------------------------------

  test("K1-K3: structure-determined community answers") {
    val comm = communities(Full)
    def same(c: String, xs: String*): Unit =
      assertEquals(xs.map(x => comm(rid(s"$c.$x"))).distinct.size, 1, s"$c ${xs.mkString(",")}")
    def different(c: String, a: String, b: String): Unit =
      assertNotEquals(comm(rid(s"$c.$a")), comm(rid(s"$c.$b")), s"$c $a vs $b")
    same("K1", "A", "B", "C", "D", "E")
    same("K1", "F", "G", "H", "I", "J")
    different("K1", "E", "F")
    same("K2", "A", "B", "C", "D")
    same("K2", "E", "F", "G", "H")
    for (a <- Seq("A", "B", "C", "D"); b <- Seq("E", "F", "G", "H")) different("K2", a, b)
    different("K3", "C", "A")
    different("K3", "C", "B")
    val sizes = rows[GroupMembershipRow](s"communities/window_label=$Full")
      .map(r => r.researcherId -> r.groupSize)
      .toMap
    assertEquals(sizes(rid("K3.C")), 1)
    assertEquals(comm(rid("K3.C")), rid("K3.C"))
  }

  // ---- S1a: an empty run --------------------------------------------------------------------

  test("S1a: an empty input completes with empty tables and a manifest") {
    val (h, ia) = KnownGraphInputs.write(new File(tmp, "inputs-empty"), Spec(Nil))
    val r = Pipeline.run(spark, args(h, ia, "out-empty"))
    assert(!r.reused)
    assert(new File(r.runDir, Manifests.RunManifestName).isFile)
    FixtureData.config.windows.foreach { nw =>
      Seq(
        s"coauthor_edge/window_label=${nw.label}",
        s"metrics/metric_type=breadth/window_label=${nw.label}",
        s"components/window_label=${nw.label}",
        s"communities/window_label=${nw.label}"
      ).foreach { rel =>
        val dir = new File(r.runDir, rel)
        assert(dir.isDirectory, s"$rel exists")
        assertEquals(spark.read.parquet(dir.getPath).count(), 0L, rel)
      }
    }
    assertEquals(spark.read.parquet(new File(r.runDir, "canonical_researcher").getPath).count(), 0L)
  }
}
