package org.scholarchain.er

import java.nio.file.{Files, Path}

import org.scholarchain.core.*
import org.scholarchain.er.Fixture.W
import org.scholarchain.er.llm.Adjudicator
import org.scholarchain.er.policy.{Reasons, Rules}
import org.scholarchain.er.run.RunOutcome

/** Repair M1 through the whole run: REJECTED and CONFLICT pair decisions act as cannot-links, so a
  * transitive chain of accepted decisions never puts a rejected pair into one identity cluster; the
  * refused decision is recorded (rule, reason, inherited path) and counted.
  */
class CannotLinkRunSuite extends munit.FunSuite {

  private def pairDecisions(out: RunOutcome): List[MatchDecision] =
    Fixture
      .tree(out.runDir)
      .toList
      .filter(_._1.startsWith("decisions/mention_pair/"))
      .sortBy(_._1)
      .flatMap { case (rel, _) => Fixture.lines(out.runDir.resolve(rel)) }
      .map(l => Contract.decode[MatchDecision](l).fold(e => fail(e.message), identity))

  private def assignments(out: RunOutcome): Map[String, MatchDecision] =
    Fixture
      .lines(out.runDir.resolve("decisions/identity_assignment/part-00000.jsonl"))
      .map(l => Contract.decode[MatchDecision](l).fold(e => fail(e.message), identity))
      .map(d => d.left -> d)
      .toMap

  private def between(ds: List[MatchDecision], a: String, b: String) =
    ds.filter(d => Set(d.left, d.right) == Set(a, b))

  private def run(works: List[W], adj: Adjudicator, root: String): RunOutcome =
    RunFixture.run(Fixture.writeExport(works), Files.createTempDirectory(root), adj)

  // The reviewer's chain in block `j smith`, three different works.
  private val m1 = Fixture.mentionId("conf/sigir/CL1", 0) // Jane Smith (coauthor X)
  private val m2 = Fixture.mentionId("conf/sigir/CL2", 0) // J. Smith (X, Y)
  private val m3 = Fixture.mentionId("conf/sigir/CL3", 0) // John Smith (Y)
  private val chain = List(
    W("conf/sigir/CL1", List("Jane Smith", "Xavier Coa")),
    W("conf/sigir/CL2", List("J. Smith", "Xavier Coa", "Yvonne Cob")),
    W("conf/sigir/CL3", List("John Smith", "Yvonne Cob"))
  )

  test("M1: Jane Smith ~ J. Smith ~ John Smith with Jane / John rejected never forms one cluster") {
    val input = Fixture.writeExport(chain)
    def runChain(root: String) = RunFixture.run(input, Files.createTempDirectory(root))
    val out = runChain("er-cl-chain")
    val ds = pairDecisions(out)
    // preconditions: the pair evidence is the reviewer's chain
    val d13 = between(ds, m1, m3)
    assert(d13.nonEmpty && d13.forall(_.outcome == MatchOutcome.Rejected), d13.toString)
    val d12 = between(ds, m1, m2)
    val d23 = between(ds, m2, m3)
    assert(d12.nonEmpty && d23.nonEmpty)
    (d12 ++ d23).foreach(d => assert(d.rulesFired.contains(Rules.ScoreAccept), d.toString))

    // the identity clusters keep the rejected pair apart
    val as = assignments(out)
    assertNotEquals(as(m1).right, as(m3).right)
    // exactly one of the two accepted links was applied; the other is refused and recorded
    val refused = (d12 ++ d23).filter(_.rulesFired.contains(Rules.GuardRejectedPair))
    val applied = (d12 ++ d23).filter(_.outcome == MatchOutcome.Accepted)
    assertEquals(refused.size, 1)
    assertEquals(applied.size, 1)
    refused.foreach { d =>
      assertEquals(d.outcome, MatchOutcome.Unresolved)
      assertEquals(d.matchPath, MatchPath.Probabilistic) // inherited from score.accept
      assertEquals(d.rulesFired, List(Rules.GuardRejectedPair, Rules.ScoreAccept))
      assertEquals(d.reasonCodes, List(Reasons.MergeRejectedPair))
      assert(d.confidence > 0.9)
    }
    // counted in the run report
    val rep = out.report
    assertEquals(rep("pairDecisions")("mergeRefusalsByReason")(Reasons.MergeRejectedPair).num, 1.0)
    assertEquals(rep("clusters")("mergesRefusedByReason")(Reasons.MergeRejectedPair).num, 1.0)
    assertEquals(rep("clusters")("cannotLinks")("cannotLinkClusterPairs").num, 1.0)
    assertEquals(rep("clusters")("cannotLinks")("cannotLinkDecisions").num, 1.0)
    assertEquals(
      rep("clusters")("cannotLinks")("rejectedOrConflictPairsInsideOneCluster").obj.size,
      0
    )
    val split = rep("clusters")("cannotLinks")("rejectedOrConflictPairsInsideOneClusterSplit")
    assertEquals(split("acrossPriorClusters").obj.size, 0)
    assertEquals(split("withinPriorCluster").obj.size, 0)
    // the same inputs give the same bytes (merge order unchanged by the guard)
    val again = runChain("er-cl-chain-b")
    val a = Fixture.tree(out.runDir)
    val b = Fixture.tree(again.runDir)
    assertEquals(a.keySet, b.keySet)
    a.foreach { case (k, v) => assert(java.util.Arrays.equals(v, b(k)), s"$k differs") }
  }

  private def counts(v: ujson.Value): Map[String, Double] =
    v.obj.toMap.map { case (k, x) => k -> x.num }

  test(
    "finding 1: an expected within-prior conflict is split from across-prior pairs in one cluster"
  ) {
    // One dblp name twice on one work: a CONFLICT pair inside one dblp-prior cluster. It is kept
    // (flagged, not split) and merges with "K. Splitname" through accepted links. It counts in
    // the unchanged total and in withinPriorCluster; acrossPriorClusters stays empty (M1 holds).
    val works = List(
      W("conf/sigir/SPA", List("Kai Splitname", "Coa Spone", "Kai Splitname"), year = 2018),
      W("conf/sigir/SPB", List("K. Splitname", "Coa Spone", "Coa Sptwo"), year = 2019)
    )
    val out = run(works, new llm.AlwaysAbstainAdjudicator("fake-model/1"), "er-cl-within")
    val ds = pairDecisions(out)
    val internal = between(
      ds,
      Fixture.mentionId("conf/sigir/SPA", 0),
      Fixture.mentionId("conf/sigir/SPA", 2)
    )
    assert(internal.nonEmpty && internal.forall(_.outcome == MatchOutcome.Conflict), ds.toString)
    val as = assignments(out)
    assertEquals(
      as(Fixture.mentionId("conf/sigir/SPA", 0)).right,
      as(Fixture.mentionId("conf/sigir/SPB", 0)).right
    )
    val cl = out.report("clusters")("cannotLinks")
    // one decision per block family that emitted the internal pair (dblp-base-name and
    // initials-surname here)
    assertEquals(internal.size, 2)
    val n = internal.size.toDouble
    assertEquals(counts(cl("rejectedOrConflictPairsInsideOneCluster")), Map("conflict" -> n))
    val split = cl("rejectedOrConflictPairsInsideOneClusterSplit")
    assertEquals(counts(split("withinPriorCluster")), Map("conflict" -> n))
    assertEquals(counts(split("acrossPriorClusters")), Map.empty[String, Double])
  }

  test("M1: an LLM rejection is a cannot-link too") {
    // A and C share folded names (scored into the LLM band); B links to both through coauthors.
    val a = Fixture.mentionId("conf/cvpr/LA", 0)
    val b = Fixture.mentionId("conf/sigir/LB", 0)
    val c = Fixture.mentionId("conf/sigir/LC", 0)
    val works = List(
      W(
        "conf/cvpr/LA",
        List("JOHN SMITH", "Xavier Coa"),
        venue = "conf/cvpr",
        title = "Vision Segmentation Nets"
      ),
      W("conf/sigir/LB", List("J. Smith", "Xavier Coa", "Yvonne Cob"), title = "Query Rewriting"),
      W("conf/sigir/LC", List("John Smith", "Yvonne Cob"), title = "Graph Mining Methods")
    )
    val reject = new ScriptedAdjudicator("fake-model/1")(_ =>
      """{"outcome":"reject","chosenCandidateId":null,"citedInputFields":["left.name"],"confidence":0.95,"reasonCodes":[]}"""
    )
    val out = run(works, reject, "er-cl-llm")
    val ds = pairDecisions(out)
    val ac = between(ds, a, c)
    assert(
      ac.nonEmpty && ac.forall(d =>
        d.outcome == MatchOutcome.Rejected && d.matchPath == MatchPath.Llm
      ),
      ac.toString
    )
    val as = assignments(out)
    assertNotEquals(as(a).right, as(c).right)
    val refused = (between(ds, a, b) ++ between(ds, b, c))
      .filter(_.rulesFired.contains(Rules.GuardRejectedPair))
    assertEquals(refused.size, 1)
    assertEquals(
      out.report("clusters")("mergesRefusedByReason")(Reasons.MergeRejectedPair).num,
      1.0
    )
  }

  test(
    "M1: a conflict pair's mentions never end in one cluster through a chain; refusals recorded"
  ) {
    // Same ORCID on A and C, but A and C carry two different homonym numbers of one base name:
    // orcid.agree-dblp-homonym-differs is a CONFLICT pair decision. B links to both by coauthors.
    val o = Fixture.orcid("000000021825009")
    val a = Fixture.mentionId("conf/sigir/KA", 0)
    val c = Fixture.mentionId("conf/sigir/KC", 0)
    val works = List(
      W("conf/sigir/KA", List("Kai Synthcon 0001", "Xavier Coa"), orcids = Map(0 -> o)),
      W("conf/sigir/KB", List("K. Synthcon", "Xavier Coa", "Yvonne Cob")),
      W("conf/sigir/KC", List("Kai Synthcon 0002", "Yvonne Cob"), orcids = Map(0 -> o))
    )
    val out = run(works, new llm.AlwaysAbstainAdjudicator("fake-model/1"), "er-cl-conflict")
    val ds = pairDecisions(out)
    assert(between(ds, a, c).exists(_.outcome == MatchOutcome.Conflict))
    val as = assignments(out)
    assertNotEquals(as(a).right, as(c).right)
    // the distinct-homonym guard is checked first; whichever guard refuses, the refusal is recorded
    val guarded = ds.filter(_.rulesFired.exists(_.startsWith("cluster.guard.")))
    assert(guarded.nonEmpty)
    guarded.foreach(d => assert(d.reasonCodes.exists(_.startsWith("merge-refused:")), d.toString))
  }
}
