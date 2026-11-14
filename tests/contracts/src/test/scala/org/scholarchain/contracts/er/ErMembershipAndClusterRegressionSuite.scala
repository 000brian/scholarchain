package org.scholarchain.contracts.er

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.text.Normalizer

import org.scholarchain.contracts.er.ErSynth.*
import org.scholarchain.core.MatchOutcome
import org.scholarchain.er.eval.{GoldLabel, GoldSet, Harness, KeyRow}
import org.scholarchain.er.llm.AlwaysAbstainAdjudicator
import org.scholarchain.er.review.{QueueSpec, ReviewQueue, StratumSampler}
import org.scholarchain.er.run.Report

/** Regression attacks on the second repair round (code-reviewer m-new-1, m-new-2, m-new-3), through
  * public APIs only.
  *
  *   - m-new-1: a queue row sampled both as a dblp-prior pair and in a candidate stratum keeps each
  *     membership's own decision in the review key, and the weighted harness and its breakdowns
  *     score each membership with that decision, not with the row's.
  *   - m-new-2: the key's `leftResearcherId`, `rightResearcherId` and `sameFinalCluster` agree with
  *     the identity assignments; the queue CSV and JSONL stay label-blind; the cluster-level
  *     metrics are hand-checkable.
  *   - m-new-3: `knownRecallGaps` (`blocking.middle-name-variants`) is in the run report with
  *     hand-counted values.
  *
  * Corpus (all synthetic): `Nia Multisynth 0001` on works A (ORCID X), B (none) and C (ORCID Y), so
  * the dblp-prior cluster holds two ORCIDs and is flagged CONFLICT; `Nia Multisynth 0002` on D;
  * `José Synthmerge` precomposed on E and decomposed on F; `Nia Q. Multisynth` on G (a
  * middle-initial variant). Candidates: (A,B) accepted by the same-name rule, (A,C) conflict by
  * ORCID disagreement, (A,D) rejected as a distinct homonym, (E,F) accepted as canonically
  * equivalent (merges two dblp-prior clusters). All three prior pairs of `Nia Multisynth 0001` are
  * sampled, so rows (A,B) and (A,C) are each in a prior stratum and a candidate stratum.
  */
class ErMembershipAndClusterRegressionSuite extends munit.FunSuite {

  override val munitTimeout = scala.concurrent.duration.Duration(600, "s")

  private val X = orcid("000000021111111")
  private val Y = orcid("000000022222222")
  private val Composed = "José Synthmerge"
  private val Decomposed = "José Synthmerge"

  private val A =
    SW("conf/sigir/SynthMemA", List("Nia Multisynth 0001", "Coa Memxa"), 2011, orcids = Map(0 -> X))
  private val B = SW("conf/sigir/SynthMemB", List("Nia Multisynth 0001", "Coa Memxb"), 2012)
  private val C =
    SW("conf/sigir/SynthMemC", List("Nia Multisynth 0001", "Coa Memxc"), 2013, orcids = Map(0 -> Y))
  private val D = SW("conf/sigir/SynthMemD", List("Nia Multisynth 0002", "Coa Memxd"), 2014)
  private val E = SW("conf/sigir/SynthMemE", List(Composed, "Coa Memxe"), 2015)
  private val F = SW("conf/sigir/SynthMemF", List(Decomposed, "Coa Memxf"), 2016)
  private val G = SW("conf/sigir/SynthMemG", List("Nia Q. Multisynth", "Coa Memxg"), 2017)

  private val Base = "dblp-base-name"
  private val cAB = cand(A, 0, B, 0, Base, "Nia Multisynth")
  private val cAC = cand(A, 0, C, 0, Base, "Nia Multisynth")
  private val cAD = cand(A, 0, D, 0, Base, "Nia Multisynth")
  private val cEF = cand(E, 0, F, 0, Base, Normalizer.normalize(Composed, Normalizer.Form.NFD))

  private def m(w: SW) = mid(w.key, 0)
  private def pair(a: SW, b: SW): (String, String) =
    if (m(a) < m(b)) (m(a), m(b)) else (m(b), m(a))

  private val AccStratum = StratumSampler.stratum("deterministic", "accepted", Base, near = false)
  private val ConfStratum = StratumSampler.stratum("exact_id", "conflict", Base, near = false)
  private val RejStratum = StratumSampler.stratum("deterministic", "rejected", Base, near = false)
  private val Prior = StratumSampler.PriorStratum

  private lazy val input = writeExport(List(A, B, C, D, E, F, G), List(cAB, cAC, cAD, cEF))
  private def erRun(tag: String) =
    run(
      erConfig(input, workRoot(s"mnew-$tag-"), queue = QueueSpec(20, 3, 9L)),
      new AlwaysAbstainAdjudicator(Model)
    )
  private lazy val r1 = erRun("a")

  private def keyFile(dir: Path) = dir.resolve("review-queue/review-key.jsonl")
  private def sortedPair(a: String, b: String): (String, String) = if (a < b) (a, b) else (b, a)

  /** Key rows by unordered mention pair (sorted), whatever orientation the key uses. */
  private lazy val keyJson: Map[(String, String), ujson.Value] =
    lines(keyFile(r1.runDir))
      .map(ujson.read(_))
      .map(o => sortedPair(o("leftMentionId").str, o("rightMentionId").str) -> o)
      .toMap
  private lazy val assigned = assignments(r1.runDir).map(a => a.left -> a).toMap
  private lazy val decided = pairDecisions(r1.runDir).map(d => d.candidateId.get.value -> d).toMap

  private def memberships(o: ujson.Value): Map[String, (String, String, String)] =
    o("memberships").arr.toList
      .map(x => x("stratum").str -> (x("outcome").str, x("matchPath").str, x("candidateId").str))
      .toMap

  // ---------------------------------------------------------------------------------------------
  // m-new-1: per-membership decisions in the key
  // ---------------------------------------------------------------------------------------------

  test("precondition: the corpus yields the intended decisions, clusters and queue rows") {
    assertEquals(decided(cAB.candidateId.value).outcome, MatchOutcome.Accepted)
    assertEquals(decided(cAC.candidateId.value).outcome, MatchOutcome.Conflict)
    assertEquals(decided(cAD.candidateId.value).outcome, MatchOutcome.Rejected)
    assertEquals(decided(cEF.candidateId.value).outcome, MatchOutcome.Accepted)
    // the 0001 cluster holds two ORCIDs: flagged CONFLICT, kept whole
    List(A, B, C).foreach(w => assertEquals(assigned(m(w)).outcome, MatchOutcome.Conflict, w.key))
    assertEquals(List(A, B, C).map(w => assigned(m(w)).right).distinct.size, 1)
    assertEquals(
      keyJson.keySet,
      Set(pair(A, B), pair(A, C), pair(B, C), pair(A, D), pair(E, F))
    )
  }

  test("m-new-1: a row sampled as a dblp-prior pair and as a candidate keeps both decisions") {
    val ab = keyJson(pair(A, B))
    assertEquals(
      memberships(ab).map { case (s, (o, p, _)) => s -> (o, p) },
      Map(Prior -> ("conflict", "deterministic"), AccStratum -> ("accepted", "deterministic"))
    )
    assertEquals(memberships(ab)(AccStratum)._3, cAB.candidateId.value)
    assertEquals(ab("candidateId").str, cAB.candidateId.value)
    assertEquals(ab("candidateIdKind").str, "match-candidate")
    // the row-level decision is the shown candidate's
    assertEquals((ab("outcome").str, ab("matchPath").str), ("accepted", "deterministic"))

    val ac = keyJson(pair(A, C))
    assertEquals(
      memberships(ac).map { case (s, (o, p, _)) => s -> (o, p) },
      Map(Prior -> ("conflict", "deterministic"), ConfStratum -> ("conflict", "exact_id"))
    )
    assertEquals((ac("outcome").str, ac("matchPath").str), ("conflict", "exact_id"))

    // every candidate membership equals the written MatchDecision of its candidate; every prior
    // membership equals the identity assignment of its cluster
    keyJson.values.foreach { o =>
      memberships(o).foreach { case (s, (outcome, path, cid)) =>
        if (s == Prior) {
          val a = assigned(o("leftMentionId").str)
          assertEquals(outcome, a.outcome.wire, o.toString)
          assertEquals(path, "deterministic")
        } else {
          val d = decided(cid)
          assertEquals((outcome, path), (d.outcome.wire, d.matchPath.wire), o.toString)
        }
      }
    }
  }

  private val labels: Map[(String, String), String] = Map(
    pair(A, B) -> "MATCH",
    pair(A, C) -> "NON_MATCH",
    pair(B, C) -> "MATCH",
    pair(A, D) -> "NON_MATCH",
    pair(E, F) -> "MATCH"
  )

  private lazy val key = KeyRow.readAll(keyFile(r1.runDir))
  private lazy val keyRow: Map[(String, String), KeyRow] =
    key.values.map(k => sortedPair(k.left, k.right) -> k).toMap
  private lazy val gold = GoldSet(
    labels.toList.sorted.map { case (p, lab) =>
      val k = keyRow(p)
      GoldLabel(
        k.left,
        k.right,
        k.candidateId,
        lab,
        List("other"),
        "SYNTHETIC",
        "SYNTHETIC-LABELER",
        "2026-11-14"
      )
    },
    0
  )
  private lazy val report = Harness.evaluate(gold, key, None, 13L, 200, Some("SYNTHETIC"))

  test("m-new-1: weighted units score each membership with its own decision") {
    val units = Harness.weightedUnits(Harness.join(gold, key))
    def of(p: (String, String)) = {
      val k = keyRow(p)
      units
        .filter(_.row == s"${k.left} ${k.right}")
        .map(u => (u.stratum, u.outcome, u.weight))
        .toSet
    }
    assertEquals(of(pair(A, B)), Set((Prior, "conflict", 1.0), (AccStratum, "accepted", 1.0)))
    assertEquals(of(pair(A, C)), Set((Prior, "conflict", 1.0), (ConfStratum, "conflict", 1.0)))
    assertEquals(units.size, 7)
    // weighted: TP = (A,B) candidate + (E,F) = 2; MATCH units = (A,B) x2 + (B,C) + (E,F) = 4
    val w = report("researcherResolution")("weighted")("metrics")
    assertEqualsDouble(w("recall")("numerator").num, 2.0, 1e-12)
    assertEqualsDouble(w("recall")("denominator").num, 4.0, 1e-12)
    assertEqualsDouble(w("precision")("value").num, 1.0, 1e-12)
    // unweighted rows keep the row decision: (A,B) accepted, (B,C) conflict
    val u = report("researcherResolution")("unweighted")
    assertEqualsDouble(u("recall")("value").num, 2.0 / 3.0, 1e-12)
  }

  test("m-new-1: breakdowns by stratum, family and path use the membership's decision") {
    val rr = report("researcherResolution")
    def conf(x: ujson.Value, o: String, l: String) = x("confusion")(o)(l).num
    val prior = rr("byStratum")(Prior)
    assertEquals(prior("units").num, 3.0)
    assertEquals(conf(prior, "conflict", "MATCH"), 2.0) // (A,B), (B,C)
    assertEquals(conf(prior, "conflict", "NON_MATCH"), 1.0) // (A,C)
    assertEquals(conf(prior, "accepted", "MATCH"), 0.0, "(A,B) scored with its row decision")
    val acc = rr("byStratum")(AccStratum)
    assertEquals(conf(acc, "accepted", "MATCH"), 2.0)
    assertEquals(rr("byStratum")(ConfStratum)("units").num, 1.0)
    assertEquals(rr("byStratum")(RejStratum)("units").num, 1.0)

    val fam = rr("byFamily")
    assertEquals(fam(StratumSampler.PriorFamily)("confusion"), prior("confusion"))
    assertEquals(fam(Base)("units").num, 4.0)
    assertEquals(conf(fam(Base), "accepted", "MATCH"), 2.0)
    assertEquals(conf(fam(Base), "conflict", "NON_MATCH"), 1.0)
    assertEquals(conf(fam(Base), "rejected", "NON_MATCH"), 1.0)

    val path = rr("byMatchPath")
    assertEquals(path("exact_id")("units").num, 1.0)
    assertEquals(conf(path("exact_id"), "conflict", "NON_MATCH"), 1.0)
    // (A,C) is also on the deterministic path through its prior membership
    assertEquals(path("deterministic")("units").num, 5.0)
    assertEquals(conf(path("deterministic"), "conflict", "NON_MATCH"), 1.0)
    assertEquals(conf(path("deterministic"), "conflict", "MATCH"), 1.0)
    assertEquals(conf(path("deterministic"), "rejected", "NON_MATCH"), 1.0)
    assertEquals(conf(path("deterministic"), "accepted", "MATCH"), 2.0)
  }

  // ---------------------------------------------------------------------------------------------
  // m-new-2: final clusters in the key, label-blind queue, cluster-level metrics
  // ---------------------------------------------------------------------------------------------

  test("m-new-2: sameFinalCluster and the ResearcherIds agree with the identity assignments") {
    keyJson.values.foreach { o =>
      val l = assigned(o("leftMentionId").str).right
      val r = assigned(o("rightMentionId").str).right
      assertEquals(o("leftResearcherId").str, l, o.toString)
      assertEquals(o("rightResearcherId").str, r, o.toString)
      assertEquals(o("sameFinalCluster").bool, l == r, o.toString)
    }
    assertEquals(keyJson(pair(A, B))("sameFinalCluster").bool, true)
    assertEquals(keyJson(pair(A, C))("sameFinalCluster").bool, true, "conflict inside one cluster")
    assertEquals(keyJson(pair(B, C))("sameFinalCluster").bool, true)
    assertEquals(keyJson(pair(A, D))("sameFinalCluster").bool, false)
    // two different dblp names merged by an ACCEPTED decision end in one cluster
    assertEquals(keyJson(pair(E, F))("sameFinalCluster").bool, true)
  }

  test("m-new-2: the queue CSV and JSONL are label-blind: no researcher IDs, no cluster fields") {
    val csv = new String(
      Files.readAllBytes(r1.runDir.resolve("review-queue/review-queue.csv")),
      StandardCharsets.UTF_8
    )
    val jsonl = lines(r1.runDir.resolve("review-queue/review-queue.jsonl")).map(ujson.read(_).obj)
    val keyText = lines(keyFile(r1.runDir)).mkString("\n")
    val researcherIds = assigned.values.map(_.right).toSet
    // the key really carries what the queue must not (so the searches below are meaningful)
    assert(keyText.contains("sameFinalCluster"))
    researcherIds
      .filter(id => keyJson.values.exists(_("leftResearcherId").str == id))
      .foreach(id => assert(keyText.contains(id)))
    assertEquals(csv.linesIterator.next().split(',').toList, ReviewQueue.Columns)
    assertEquals(jsonl.size, keyJson.size)
    val forbidden =
      List("sameFinalCluster", "ResearcherId", "researcher:", "cluster", "stratum", "outcome")
    forbidden.foreach(f => assert(!csv.contains(f), s"CSV contains '$f'"))
    jsonl.foreach { row =>
      assertEquals(row.keys.toList, ReviewQueue.Columns)
      row.foreach { case (k, v) =>
        forbidden.foreach(f =>
          assert(!k.contains(f) && !v.str.contains(f), s"JSONL $k=$v has '$f'")
        )
        assert(!researcherIds(v.str), s"JSONL $k shows a ResearcherId")
      }
    }
    researcherIds.foreach(id => assert(!csv.contains(id), s"CSV shows ResearcherId $id"))
  }

  test("m-new-2: the strict key reader refuses inconsistent or incomplete cluster fields") {
    val line = lines(keyFile(r1.runDir)).head
    val dir = workRoot("mnew-key-")
    def bad(mutate: ujson.Obj => Unit, expect: String): Unit = {
      val o = ujson.read(line) match {
        case x: ujson.Obj => x
        case other        => fail(s"key line is not an object: $other")
      }
      mutate(o)
      val f = dir.resolve(s"key-${expect.hashCode.abs}.jsonl")
      writeLines(f, List(o.render()))
      val e = intercept[Exception](KeyRow.readAll(f))
      assert(e.getMessage.contains(expect), s"${e.getMessage} lacks '$expect'")
    }
    bad(o => o("sameFinalCluster") = !o("sameFinalCluster").bool, "disagrees")
    bad(o => o.value.remove("sameFinalCluster"), "sameFinalCluster")
    bad(o => o.value.remove("leftResearcherId"), "leftResearcherId")
    bad(o => o("memberships").arr.foreach(_.obj.remove("outcome")), "outcome")
    bad(o => o("memberships").arr.foreach(_.obj.remove("matchPath")), "matchPath")
  }

  test("m-new-2: cluster-level metrics on the real key: hand counts") {
    val ca = report("researcherClusterAssignment")
    assertEquals(ca("labeledRowsWithClusterInfo").num, 5.0)
    assertEquals(ca("labeledRowsWithoutClusterInfo").num, 0.0)
    val u = ca("unweighted")
    // same final cluster: (A,B) M, (A,C) N, (B,C) M, (E,F) M; different: (A,D) N
    assertEqualsDouble(u("precision")("value").num, 3.0 / 4.0, 1e-12)
    assertEqualsDouble(u("recall")("value").num, 1.0, 1e-12)
    assertEqualsDouble(u("f1").num, 6.0 / 7.0, 1e-12)
    assertEquals(u("confusion")(Harness.SameCluster)("MATCH").num, 3.0)
    assertEquals(u("confusion")(Harness.SameCluster)("NON_MATCH").num, 1.0)
    assertEquals(u("confusion")(Harness.DifferentClusters)("NON_MATCH").num, 1.0)
    assertEquals(u("confusion")(Harness.DifferentClusters)("MATCH").num, 0.0)
    val byPair = ca("pairOutcomeByFinalCluster")
    assertEquals(byPair("accepted")(Harness.SameCluster).num, 2.0)
    assertEquals(byPair("conflict")(Harness.SameCluster).num, 2.0)
    assertEquals(byPair("rejected")(Harness.DifferentClusters).num, 1.0)
    // weighted: every membership carries the row's cluster outcome: TP 4 (A,B x2, B,C, E,F), FP 2
    val w = ca("weighted")("metrics")
    assertEqualsDouble(w("precision")("value").num, 4.0 / 6.0, 1e-12)
    assertEqualsDouble(w("recall")("value").num, 1.0, 1e-12)
  }

  private def wilson(p: Double, n: Double): (Double, Double) = {
    val z = 1.959963984540054
    val centre = (p + z * z / (2 * n)) / (1 + z * z / n)
    val half = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / (1 + z * z / n)
    (centre - half, centre + half)
  }

  test("m-new-2: cluster-level metrics on a small synthetic key: hand counts, weights, intervals") {
    def mm(i: Int) = f"mention:v1:$i%032x"
    def cc(i: Int) = f"match-candidate:v1:$i%032x"
    val S1 = "path=probabilistic;outcome=accepted;family=f1;band=far"
    val S2 = "path=probabilistic;outcome=rejected;family=f2;band=far"
    val S3 = "path=probabilistic;outcome=accepted;family=f3;band=far"
    // (label, pair outcome, same final cluster, stratum, population)
    val rows = List(
      ("MATCH", "accepted", Some(true), S1, 30.0),
      ("MATCH", "unresolved", Some(true), S1, 30.0),
      ("NON_MATCH", "accepted", Some(true), S2, 80.0),
      ("MATCH", "accepted", Some(false), S1, 30.0),
      ("NON_MATCH", "rejected", Some(false), S2, 80.0),
      ("UNSURE", "unresolved", Some(true), S2, 80.0),
      ("UNSURE", "rejected", Some(false), S2, 80.0),
      ("MATCH", "accepted", None, S3, 5.0) // built in memory without cluster information
    )
    val g = GoldSet(
      rows.zipWithIndex.map { case (r, i) =>
        GoldLabel(
          mm(2 * i + 1),
          mm(2 * i + 2),
          cc(i),
          r._1,
          List("other"),
          "SYNTHETIC",
          "SYNTHETIC-LABELER",
          "2026-11-14"
        )
      },
      0
    )
    val k = rows.zipWithIndex.map { case ((_, o, same, s, pop), i) =>
      val l = mm(2 * i + 1)
      val r = mm(2 * i + 2)
      val (lr, rr) = same match {
        case Some(true)  => (Some(s"researcher:v1:${"a" * 32}"), Some(s"researcher:v1:${"a" * 32}"))
        case Some(false) => (Some(s"researcher:v1:${"a" * 32}"), Some(s"researcher:v1:${"b" * 32}"))
        case None        => (None, None)
      }
      (l, r) -> KeyRow(
        l,
        r,
        cc(i),
        List(s -> pop),
        o,
        "probabilistic",
        "f",
        Some(0.5),
        0.5,
        Nil,
        Nil,
        Nil,
        Nil,
        lr,
        rr,
        same
      )
    }.toMap
    val rep = Harness.evaluate(g, k, None, 5L, 300, Some("SYNTHETIC"))
    val ca = rep("researcherClusterAssignment")
    assertEquals(ca("labeledRowsWithClusterInfo").num, 7.0)
    assertEquals(ca("labeledRowsWithoutClusterInfo").num, 1.0)

    val u = ca("unweighted")
    assertEquals(u("units").num, 7.0)
    assertEqualsDouble(u("precision")("value").num, 2.0 / 3.0, 1e-12)
    assertEqualsDouble(u("recall")("value").num, 2.0 / 3.0, 1e-12)
    assertEqualsDouble(u("f1").num, 2.0 / 3.0, 1e-12)
    val (lo, hi) = wilson(2.0 / 3.0, 3.0)
    assertEqualsDouble(u("precision")("wilson95").arr(0).num, lo, 1e-9)
    assertEqualsDouble(u("precision")("wilson95").arr(1).num, hi, 1e-9)
    assert(!u.obj.contains("coverage") && !u.obj.contains("unresolvedRate"), u.toString)
    def cm(x: ujson.Value, k: String) =
      Harness.LabelOrder.map(l => x("confusion")(k)(l).num)
    assertEquals(cm(u, Harness.SameCluster), List(2.0, 1.0, 1.0))
    assertEquals(cm(u, Harness.DifferentClusters), List(1.0, 1.0, 1.0))
    val byPair = ca("pairOutcomeByFinalCluster")
    def bp(o: String) =
      (byPair(o)(Harness.SameCluster).num, byPair(o)(Harness.DifferentClusters).num)
    assertEquals(bp("accepted"), (2.0, 1.0))
    assertEquals(bp("unresolved"), (2.0, 0.0))
    assertEquals(bp("rejected"), (0.0, 2.0))
    assertEquals(bp("conflict"), (0.0, 0.0))

    // weighted: S1 30 / 3 labeled = 10 per unit, S2 80 / 4 = 20 per unit (row 8 left out)
    val w = ca("weighted")("metrics")
    assertEqualsDouble(w("precision")("numerator").num, 20.0, 1e-9)
    assertEqualsDouble(w("precision")("denominator").num, 40.0, 1e-9)
    assertEqualsDouble(w("recall")("denominator").num, 30.0, 1e-9)
    assertEqualsDouble(w("f1").num, 4.0 / 7.0, 1e-12)
    assertEquals(cm(w, Harness.SameCluster), List(20.0, 20.0, 20.0))
    assertEquals(cm(w, Harness.DifferentClusters), List(10.0, 20.0, 20.0))
    assertEquals(w("precision")("wilson95"), ujson.Null)
    w("precision")("interval95") match {
      case ujson.Arr(iv) =>
        assert(iv(0).num <= 0.5 && 0.5 <= iv(1).num, iv.toString)
      case other => fail(s"weighted cluster precision has no bootstrap interval: $other")
    }

    // the pair-level metrics are separate: precision 3/4 over rows 1, 3, 4, 8
    val pairLevel = rep("researcherResolution")("unweighted")
    assertEqualsDouble(pairLevel("precision")("value").num, 3.0 / 4.0, 1e-12)
    assert(Harness.markdown(rep).contains("final cluster assignment"))
  }

  // ---------------------------------------------------------------------------------------------
  // m-new-3: knownRecallGaps
  // ---------------------------------------------------------------------------------------------

  test("m-new-3: knownRecallGaps is in the run report with hand-counted values") {
    val gaps =
      ujson.read(Files.readAllBytes(r1.runDir.resolve("run-report.json")))("knownRecallGaps")
    val g = gaps.arr.find(_("id").str == "blocking.middle-name-variants")
    assert(g.isDefined, gaps.toString)
    // (n, multisynth): `n multisynth` (0001, 0002) and `n q multisynth` (G): 1 group, 3 names
    assertEquals(g.get("firstInitialSurnameGroupsSplitByMiddleInitials").num, 1.0)
    assertEquals(g.get("dblpNamesInThoseGroups").num, 3.0)
    assert(g.get("description").str.nonEmpty && g.get("measurement").str.nonEmpty)
    assertEquals(r1.report("knownRecallGaps"), gaps)
  }

  test("m-new-3: the middle-initial split counts groups and names by hand") {
    val names = List(
      "John A. Smith",
      "John Smith",
      "John Smith 0001",
      "Jane Smith",
      "J. B. Smith",
      "Mary Jones",
      "M. Jones",
      "Ann Lee",
      "Ann B. Lee",
      "Plato"
    )
    // (j, smith): j a smith, j smith, j b smith -> 5 names; (a, lee): a lee, a b lee -> 2 names;
    // (m, jones): one key only; Plato has no given name
    assertEquals(Report.middleInitialSplit(names), (2, 7))
    assertEquals(Report.middleInitialSplit(List("Mary Jones", "M. Jones", "Plato")), (0, 0))
  }

  test("the review key is byte-identical in another output root") {
    val r2 = erRun("b")
    assertEquals(r2.runId, r1.runId)
    assertEquals(
      Files.readAllBytes(keyFile(r2.runDir)).toSeq,
      Files.readAllBytes(keyFile(r1.runDir)).toSeq
    )
  }
}
