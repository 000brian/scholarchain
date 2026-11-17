package org.scholarchain.contracts.er

import java.nio.file.{Files, Path}

import org.scholarchain.contracts.er.ErSynth.*
import org.scholarchain.core.*
import org.scholarchain.er.cluster.{CannotLinks, Clustering, EdgeCollector}
import org.scholarchain.er.data.Corpus
import org.scholarchain.er.llm.AlwaysAbstainAdjudicator
import org.scholarchain.er.policy.{DecisionEngine, Policy}

/** Cluster-level attacks on initials collisions and cannot-links (decision 18, repair M1, review
  * finding m8).
  *
  * The reviewer's chain in block `j smith`, each name on its own work: "Jane Smith" (coauthor X),
  * "J. Smith" (X and Y), "John Smith" (Y). Jane ~ J. and J. ~ John are accepted on one shared
  * coauthor; Jane ~ John is rejected (conflicting given names, no shared coauthor). Jane and John
  * must never share a ResearcherId, whichever merge comes first, whatever the input order. Also: a
  * conflict-pair chain, a rejected pair that touches only one mention of a multi-mention dblp-prior
  * cluster (the whole cluster is kept apart, never split), and an internal dblp-prior conflict that
  * must not act as a cannot-link. Every run is checked against independent recomputations of the
  * run-report cannot-link counters.
  */
class CannotLinkAdversarialSuite extends munit.FunSuite {

  override val munitTimeout = scala.concurrent.duration.Duration(600, "s")

  private val X = "Coa Xsynth"
  private val Y = "Coa Ysynth"
  private val Z = "Coa Zsynth"
  private val W = "Coa Wsynth"
  private val Block = "j smith"
  private val Fam = "initials-surname"

  private val GuardRejected = "cluster.guard.rejected-pair"
  private val GuardConflict = "cluster.guard.conflict-pair"
  private val RefusedRejected = "merge-refused:rejected-pair"
  private val RefusedConflict = "merge-refused:conflict-pair"
  private val RefusedGivenName = "merge-refused:given-name-conflict"

  // ---------------------------------------------------------------------------------------------
  // Scenarios
  // ---------------------------------------------------------------------------------------------

  /** A: the reviewer's chain; both accepted links have the same strength. */
  private val aJane = SW("conf/sigir/SynthCnJaneA", List("Jane Smith", X), year = 2018)
  private val aJ = SW("conf/sigir/SynthCnJA", List("J. Smith", X, Y), year = 2019)
  private val aJohn = SW("conf/sigir/SynthCnJohnA", List("John Smith", Y), year = 2020)
  private val aWorks = List(aJane, aJ, aJohn)
  private val aCands = List(
    cand(aJane, 0, aJ, 0, Fam, Block),
    cand(aJ, 0, aJohn, 0, Fam, Block),
    cand(aJane, 0, aJohn, 0, Fam, Block)
  )

  /** B: Jane ~ J. is stronger (two shared coauthors), so it merges first. */
  private val bJane = SW("conf/sigir/SynthCnJaneB", List("Jane Smith", X, Z), year = 2018)
  private val bJ = SW("conf/sigir/SynthCnJB", List("J. Smith", X, Y, Z), year = 2019)
  private val bJohn = SW("conf/sigir/SynthCnJohnB", List("John Smith", Y), year = 2020)
  private val bWorks = List(bJane, bJ, bJohn)
  private val bCands = List(
    cand(bJane, 0, bJ, 0, Fam, Block),
    cand(bJ, 0, bJohn, 0, Fam, Block),
    cand(bJane, 0, bJohn, 0, Fam, Block)
  )

  /** C: J. ~ John is stronger, so it merges first. */
  private val cJane = SW("conf/sigir/SynthCnJaneC", List("Jane Smith", X), year = 2018)
  private val cJ = SW("conf/sigir/SynthCnJC", List("J. Smith", X, Y, Z), year = 2019)
  private val cJohn = SW("conf/sigir/SynthCnJohnC", List("John Smith", Y, Z), year = 2020)
  private val cWorks = List(cJane, cJ, cJohn)
  private val cCands = List(
    cand(cJane, 0, cJ, 0, Fam, Block),
    cand(cJ, 0, cJohn, 0, Fam, Block),
    cand(cJane, 0, cJohn, 0, Fam, Block)
  )

  /** D: "Jane Smith" has two mentions (one dblp-prior cluster); only the second one is rejected
    * against John. The rejection must keep the whole Jane cluster apart from John, without
    * splitting the Jane cluster.
    */
  private val dJane = SW("conf/sigir/SynthCnJaneD", List("Jane Smith", X), year = 2018)
  private val dJane2 = SW("conf/sigir/SynthCnJaneD2", List("Jane Smith", W), year = 2017)
  private val dJ = SW("conf/sigir/SynthCnJD", List("J. Smith", X, Y), year = 2019)
  private val dJohn = SW("conf/sigir/SynthCnJohnD", List("John Smith", Y), year = 2020)
  private val dWorks = List(dJane, dJane2, dJ, dJohn)
  private val dCands = List(
    cand(dJane, 0, dJ, 0, Fam, Block),
    cand(dJ, 0, dJohn, 0, Fam, Block),
    cand(dJane2, 0, dJohn, 0, Fam, Block),
    cand(dJane, 0, dJane2, 0, "dblp-base-name", "Jane Smith")
  )

  /** E: one dblp name twice on one work (an internal structural conflict inside one dblp-prior
    * cluster) plus an accepted link to "K. Splitname": the internal conflict is not a cannot-link,
    * the prior cluster is not split, and the merge proceeds (flagged conflict).
    */
  private val eW1 =
    SW("conf/sigir/SynthCnSplitA", List("Kai Splitname", "Coa Sp1", "Kai Splitname"), year = 2018)
  private val eW2 =
    SW("conf/sigir/SynthCnSplitB", List("K. Splitname", "Coa Sp1", "Coa Sp2"), year = 2019)
  private val eWorks = List(eW1, eW2)
  private val eCands = List(
    cand(eW1, 0, eW1, 2, "dblp-base-name", "Kai Splitname"),
    cand(eW1, 0, eW2, 0, Fam, "k splitname"),
    cand(eW1, 2, eW2, 0, Fam, "k splitname")
  )

  /** F: conflict chain. Jane and John are on the same work (a structural conflict pair); both link
    * to J. The second merge must be refused; the same-work guard is checked before the
    * conflict-pair cannot-link, so it is the one that fires.
    */
  private val fBoth = SW("conf/sigir/SynthCnBoth", List("Jane Smith", "John Smith", X), year = 2018)
  private val fJ = SW("conf/sigir/SynthCnJF", List("J. Smith", X, Y), year = 2019)
  private val fWorks = List(fBoth, fJ)
  private val fCands = List(
    cand(fBoth, 0, fJ, 0, Fam, Block),
    cand(fJ, 0, fBoth, 1, Fam, Block),
    cand(fBoth, 0, fBoth, 1, Fam, Block)
  )

  // ---------------------------------------------------------------------------------------------
  // Running and independent checks
  // ---------------------------------------------------------------------------------------------

  private final case class Ran(
      works: List[SW],
      cands: List[MatchCandidate],
      input: Path,
      runDir: Path,
      report: ujson.Value,
      pairs: List[MatchDecision],
      assigned: List[MatchDecision]
  ) {
    lazy val corpus: Corpus = ErSynth.corpus(input)
    val nameOf: Map[String, String] =
      works.flatMap(w => w.authors.indices.map(i => mid(w.key, i) -> w.authors(i))).toMap
    def rid(m: String): String =
      assigned
        .find(_.left == m)
        .map(_.right)
        .getOrElse(throw new AssertionError(s"no assignment $m"))
    def researcher(w: SW, pos: Int): String = rid(mid(w.key, pos))
    def decision(a: SW, pa: Int, b: SW, pb: Int): MatchDecision = {
      val ids = Set(mid(a.key, pa), mid(b.key, pb))
      val ds = pairs.filter(d => Set(d.left, d.right) == ids)
      assert(ds.size == 1, s"expected one decision for $ids, got $ds")
      ds.head
    }
    def assessed(c: MatchCandidate) = DecisionEngine.assess(
      corpus,
      Policy.erV1,
      c.features,
      corpus.mentions.indexOf(c.left),
      corpus.mentions.indexOf(c.right)
    )
    def cannot: ujson.Value = report("clusters")("cannotLinks")
  }

  private def runScenario(works: List[SW], cands: List[MatchCandidate], tag: String): Ran = {
    val input = writeExport(works, cands)
    val out = run(erConfig(input, workRoot(s"cannot-$tag-")), new AlwaysAbstainAdjudicator(Model))
    Ran(
      works,
      cands,
      input,
      out.runDir,
      out.report,
      pairDecisions(out.runDir),
      assignments(out.runDir)
    )
  }

  private lazy val a = runScenario(aWorks, aCands, "a")
  private lazy val b = runScenario(bWorks, bCands, "b")
  private lazy val c = runScenario(cWorks, cCands, "c")
  private lazy val d = runScenario(dWorks, dCands, "d")
  private lazy val e = runScenario(eWorks, eCands, "e")
  private lazy val f = runScenario(fWorks, fCands, "f")

  private def isGuard(r: String) = r.startsWith("cluster.guard.")
  private def negative(o: MatchOutcome) = o == MatchOutcome.Rejected || o == MatchOutcome.Conflict
  private def num(v: ujson.Value): Double = v.num
  private def counts(v: ujson.Value): Map[String, Double] =
    v.obj.map { case (k, x) => k -> x.num }.toMap.filter(_._2 != 0.0)

  /** Invariants every run must satisfy, recomputed from the written decisions. */
  private def invariants(r: Ran)(implicit loc: munit.Location): Unit = {
    // every mention exactly one assignment
    val all = r.works.flatMap(w => w.authors.indices.map(i => mid(w.key, i)))
    assertEquals(r.assigned.map(_.left).sorted, all.sorted)
    // one decision per candidate
    assertEquals(r.pairs.flatMap(_.candidateId).toSet, r.cands.map(_.candidateId).toSet)
    assertEquals(r.pairs.size, r.cands.size)
    // M1: no rejected or conflict decision between different dblp-prior clusters ends inside one
    // final cluster
    val across = r.pairs.filter(p => negative(p.outcome) && r.nameOf(p.left) != r.nameOf(p.right))
    across.foreach(p => assertNotEquals(r.rid(p.left), r.rid(p.right), s"cannot-link violated: $p"))
    // the report counts every rejected or conflict decision whose mentions share one final cluster
    val inside = r.pairs
      .filter(p => negative(p.outcome) && r.rid(p.left) == r.rid(p.right))
      .groupBy(_.outcome.wire)
      .map { case (k, v) => k -> v.size.toDouble }
    assertEquals(counts(r.cannot("rejectedOrConflictPairsInsideOneCluster")), inside)
    // cannot-link inputs: pre-guard rejected or conflict decisions across different dblp names
    val sources = r.pairs.filter(p =>
      negative(p.outcome) && !p.rulesFired.exists(isGuard) && r.nameOf(p.left) != r.nameOf(p.right)
    )
    assertEquals(num(r.cannot("cannotLinkDecisions")), sources.size.toDouble)
    assertEquals(
      num(r.cannot("cannotLinkClusterPairs")),
      sources.map(p => Set(r.nameOf(p.left), r.nameOf(p.right))).distinct.size.toDouble
    )
    // refusal counters: per decision, and per dblp-prior cluster pair
    val refusedDecisions = r.pairs.flatMap(_.reasonCodes.filter(_.startsWith("merge-refused:")))
    assertEquals(
      counts(r.report("pairDecisions")("mergeRefusalsByReason")),
      refusedDecisions.groupBy(identity).map { case (k, v) => k -> v.size.toDouble }
    )
    val refusedClusterPairs = r.pairs
      .filter(_.reasonCodes.exists(_.startsWith("merge-refused:")))
      .map(p =>
        (
          Set(r.nameOf(p.left), r.nameOf(p.right)),
          p.reasonCodes.find(_.startsWith("merge-refused:")).get
        )
      )
      .distinct
    assertEquals(
      counts(r.report("clusters")("mergesRefusedByReason")),
      refusedClusterPairs.groupBy(_._2).map { case (k, v) => k -> v.size.toDouble }
    )
    // a guard decision never stays accepted and carries exactly one guard rule
    r.pairs.filter(_.rulesFired.exists(isGuard)).foreach { p =>
      assertNotEquals(p.outcome, MatchOutcome.Accepted, p.toString)
      assertEquals(p.rulesFired.count(isGuard), 1, p.toString)
    }
  }

  /** The refused link: UNRESOLVED, keeps its own probabilistic evidence, adds the rejected-pair
    * guard.
    */
  private def assertRejectedPairRefusal(r: Ran, d: MatchDecision, c: MatchCandidate)(implicit
      loc: munit.Location
  ): Unit = {
    val as = r.assessed(c)
    assertEquals(as.outcome, MatchOutcome.Accepted, "the refused evidence itself was an accept")
    assertEquals(d.outcome, MatchOutcome.Unresolved, d.toString)
    assertEquals(d.matchPath, MatchPath.Probabilistic)
    assertEqualsDouble(d.confidence, as.confidence, 1e-12)
    assertEquals(d.rulesFired, List(GuardRejected, "score.accept"))
    assertEquals(d.reasonCodes, List(RefusedRejected))
  }

  // ---------------------------------------------------------------------------------------------
  // Preconditions: the synthetic pairs decide as designed before any guard
  // ---------------------------------------------------------------------------------------------

  test("precondition: every scenario's pair evidence decides as designed (before guards)") {
    def outcomes(r: Ran) = r.cands.map(c => r.assessed(c).outcome)
    val acc = MatchOutcome.Accepted
    val rej = MatchOutcome.Rejected
    val con = MatchOutcome.Conflict
    assertEquals(outcomes(a), List(acc, acc, rej))
    assertEquals(outcomes(b), List(acc, acc, rej))
    assertEquals(outcomes(c), List(acc, acc, rej))
    assertEquals(outcomes(d), List(acc, acc, rej, acc))
    assertEquals(outcomes(e), List(con, acc, acc))
    assertEquals(outcomes(f), List(acc, acc, con))
    // A: both links equally strong; B: Jane ~ J. stronger; C: J. ~ John stronger
    val ca = a.cands.map(x => a.assessed(x).confidence)
    assertEqualsDouble(ca(0), ca(1), 1e-12)
    val cb = b.cands.map(x => b.assessed(x).confidence)
    assert(cb(0) > cb(1), cb)
    val cc = c.cands.map(x => c.assessed(x).confidence)
    assert(cc(1) > cc(0), cc)
    // Jane ~ John is rejected by the score: conflicting given names
    List(a, b, c).foreach { r =>
      val as = r.assessed(r.cands(2))
      assertEquals(as.rulesFired, List("score.reject"))
      assertEquals(as.features.find(_.name == "given_name_relation").map(_.value), Some(0.0))
    }
  }

  // ---------------------------------------------------------------------------------------------
  // The reviewer's chain
  // ---------------------------------------------------------------------------------------------

  test("m8 chain (equal links): Jane and John never share a ResearcherId; one link refused") {
    invariants(a)
    assertNotEquals(a.researcher(aJane, 0), a.researcher(aJohn, 0))
    val janeJ = a.decision(aJane, 0, aJ, 0)
    val jJohn = a.decision(aJ, 0, aJohn, 0)
    val janeJohn = a.decision(aJane, 0, aJohn, 0)
    assertEquals(janeJohn.outcome, MatchOutcome.Rejected)
    assertEquals(janeJohn.rulesFired, List("score.reject"))
    // exactly one of the two accepted links is applied; the other is refused by the cannot-link
    val links = List(janeJ -> aCands(0), jJohn -> aCands(1))
    assertEquals(
      links.count(_._1.outcome == MatchOutcome.Accepted),
      1,
      links.map(_._1).mkString("\n")
    )
    val (refused, refusedCand) = links.find(_._1.outcome != MatchOutcome.Accepted).get
    assertRejectedPairRefusal(a, refused, refusedCand)
    val applied = links.find(_._1.outcome == MatchOutcome.Accepted).get._1
    assertEquals(a.rid(applied.left), a.rid(applied.right))
    assertNotEquals(a.rid(refused.left), a.rid(refused.right))
    // the report counts the refusal and the cannot-link
    assertEquals(counts(a.report("clusters")("mergesRefusedByReason")), Map(RefusedRejected -> 1.0))
    assertEquals(
      counts(a.report("pairDecisions")("mergeRefusalsByReason")),
      Map(RefusedRejected -> 1.0)
    )
    assertEquals(num(a.cannot("cannotLinkDecisions")), 1.0)
    assertEquals(num(a.cannot("cannotLinkClusterPairs")), 1.0)
    assertEquals(
      counts(a.cannot("rejectedOrConflictPairsInsideOneCluster")),
      Map.empty[String, Double]
    )
    assertEquals(num(a.report("clusters")("mergesApplied")), 1.0)
    // five dblp names (Jane, J., John and the coauthors X and Y), one merge applied
    assertEquals(num(a.report("clusters")("dblpPriorClusters")), 5.0)
    assertEquals(num(a.report("clusters")("total")), 4.0)
  }

  test("m8 chain, Jane ~ J. stronger: Jane and J. merge, J. ~ John is refused") {
    invariants(b)
    assertEquals(b.researcher(bJane, 0), b.researcher(bJ, 0))
    assertNotEquals(b.researcher(bJohn, 0), b.researcher(bJane, 0))
    assertEquals(b.decision(bJane, 0, bJ, 0).outcome, MatchOutcome.Accepted)
    assertRejectedPairRefusal(b, b.decision(bJ, 0, bJohn, 0), bCands(1))
    // John stays alone in its dblp-prior cluster: assignment without cluster.merged
    val john = b.assigned.find(_.left == mid(bJohn.key, 0)).get
    assertEquals(john.rulesFired, List("dblp-prior.cluster"))
    assertEquals(john.right, ResearcherId.fromAnchorMention(MentionId(mid(bJohn.key, 0))).value)
  }

  test("m8 chain, J. ~ John stronger: J. and John merge, Jane ~ J. is refused") {
    invariants(c)
    assertEquals(c.researcher(cJ, 0), c.researcher(cJohn, 0))
    assertNotEquals(c.researcher(cJane, 0), c.researcher(cJohn, 0))
    assertEquals(c.decision(cJ, 0, cJohn, 0).outcome, MatchOutcome.Accepted)
    assertRejectedPairRefusal(c, c.decision(cJane, 0, cJ, 0), cCands(0))
  }

  test("m8 chain: input order (works and candidates reversed) changes no decision or cluster") {
    val rev = runScenario(aWorks.reverse, aCands.reverse, "a-rev")
    invariants(rev)
    // same content, same paths and sizes: same run ID
    assertEquals(rev.runDir.getFileName.toString, a.runDir.getFileName.toString)
    def shape(r: Ran) = r.pairs
      .map(p =>
        p.candidateId.get.value -> (
          p.outcome,
          p.matchPath,
          p.confidence,
          p.rulesFired,
          p.reasonCodes
        )
      )
      .toMap
    assertEquals(shape(rev), shape(a))
    assertEquals(
      rev.assigned.map(x => x.left -> x.right).toMap,
      a.assigned.map(x => x.left -> x.right).toMap
    )
    assertEquals(
      new String(Files.readAllBytes(rev.runDir.resolve("clusters/clusters.jsonl")), "UTF-8"),
      new String(Files.readAllBytes(a.runDir.resolve("clusters/clusters.jsonl")), "UTF-8")
    )
    def sortedLines(r: Ran) =
      lines(r.runDir.resolve("decisions/mention_pair/part-00000.jsonl")).sorted
    assertEquals(sortedLines(rev), sortedLines(a))
  }

  test("a rejected pair on one mention keeps the whole dblp-prior cluster apart, never split") {
    invariants(d)
    val jane = d.researcher(dJane, 0)
    assertEquals(d.researcher(dJane2, 0), jane, "the Jane dblp-prior cluster must not be split")
    assertNotEquals(d.researcher(dJohn, 0), jane)
    // the Jane ~ Jane pair stays an accepted same-name decision
    assertEquals(d.decision(dJane, 0, dJane2, 0).outcome, MatchOutcome.Accepted)
    // the cannot-link comes from Jane(2) ~ John, yet it blocks the whole Jane cluster
    val links =
      List(d.decision(dJane, 0, dJ, 0) -> dCands(0), d.decision(dJ, 0, dJohn, 0) -> dCands(1))
    assertEquals(links.count(_._1.outcome == MatchOutcome.Accepted), 1)
    val (refused, rc) = links.find(_._1.outcome != MatchOutcome.Accepted).get
    assertRejectedPairRefusal(d, refused, rc)
  }

  test("an internal dblp-prior conflict is not a cannot-link: no split, merge proceeds, counted") {
    invariants(e)
    val r = e.researcher(eW1, 0)
    assertEquals(e.researcher(eW1, 2), r)
    assertEquals(e.researcher(eW2, 0), r)
    assertEquals(e.decision(eW1, 0, eW1, 2).outcome, MatchOutcome.Conflict)
    assertEquals(e.decision(eW1, 0, eW2, 0).outcome, MatchOutcome.Accepted)
    assertEquals(e.decision(eW1, 2, eW2, 0).outcome, MatchOutcome.Accepted)
    assertEquals(num(e.cannot("cannotLinkDecisions")), 0.0)
    assertEquals(num(e.cannot("cannotLinkClusterPairs")), 0.0)
    // the internal conflict is the one rejected-or-conflict pair inside one final cluster
    assertEquals(
      counts(e.cannot("rejectedOrConflictPairsInsideOneCluster")),
      Map("conflict" -> 1.0)
    )
    List(eW1 -> 0, eW1 -> 2, eW2 -> 0).foreach { case (w, p) =>
      val asg = e.assigned.find(_.left == mid(w.key, p)).get
      assertEquals(asg.outcome, MatchOutcome.Conflict)
      assert(asg.reasonCodes.contains("cluster-same-work-mentions"), asg.reasonCodes)
    }
  }

  test("conflict chain: Jane and John on one work never merge through J.; same-work guard first") {
    invariants(f)
    assertNotEquals(f.researcher(fBoth, 0), f.researcher(fBoth, 1))
    val direct = f.decision(fBoth, 0, fBoth, 1)
    assertEquals(direct.outcome, MatchOutcome.Conflict)
    val links = List(f.decision(fBoth, 0, fJ, 0), f.decision(fJ, 0, fBoth, 1))
    assertEquals(links.count(_.outcome == MatchOutcome.Accepted), 1)
    val refused = links.find(_.outcome != MatchOutcome.Accepted).get
    assertEquals(refused.outcome, MatchOutcome.Conflict)
    assertEquals(refused.reasonCodes, List("merge-refused:same-work"))
    assert(refused.rulesFired.contains("cluster.guard.same-work"), refused.rulesFired)
    assert(!refused.rulesFired.contains(GuardConflict), refused.rulesFired)
    assertEquals(num(f.cannot("cannotLinkDecisions")), 1.0)
  }

  // ---------------------------------------------------------------------------------------------
  // Clustering.run directly: the conflict-pair guard and its precedence
  // ---------------------------------------------------------------------------------------------

  private lazy val aCorpus: Corpus = a.corpus
  private def nameIdx(corpus: Corpus, w: SW, pos: Int): Int =
    corpus.mentions.name(corpus.mentions.indexOf(mid(w.key, pos)))
  private lazy val (nJane, nJ, nJohn) =
    (nameIdx(aCorpus, aJane, 0), nameIdx(aCorpus, aJ, 0), nameIdx(aCorpus, aJohn, 0))

  private def chainEdges(first: (Int, Int), second: (Int, Int)): EdgeCollector = {
    val ec = new EdgeCollector
    ec.add(second._1, second._2, 0.95, MatchPath.Probabilistic)
    ec.add(first._1, first._2, 0.99, MatchPath.Probabilistic)
    ec
  }
  private def links(kinds: (Int, Int, Int)*): CannotLinks = {
    val bl = new CannotLinks.Builder(aCorpus.names.size)
    kinds.foreach { case (x, y, k) => bl.add(x, y, k) }
    bl.build()
  }

  test("Clustering: a conflict cannot-link refuses the closing merge with conflict-pair") {
    val res = Clustering.run(
      aCorpus,
      chainEdges((nJane, nJ), (nJ, nJohn)),
      links((nJane, nJohn, CannotLinks.Conflict))
    )
    assertEquals(res.mergesApplied, 1)
    assertEquals(res.rootOf(nJane), res.rootOf(nJ))
    assertNotEquals(res.rootOf(nJohn), res.rootOf(nJane))
    assertEquals(res.refused, Map(EdgeCollector.key(nJ, nJohn) -> RefusedConflict))
  }

  test(
    "Clustering: conflict wins over rejected on one cluster pair; rejected alone gives rejected"
  ) {
    val both = Clustering.run(
      aCorpus,
      chainEdges((nJ, nJohn), (nJane, nJ)),
      links((nJane, nJohn, CannotLinks.Rejected), (nJohn, nJane, CannotLinks.Conflict))
    )
    assertEquals(both.refused, Map(EdgeCollector.key(nJane, nJ) -> RefusedConflict))
    val rejected = Clustering.run(
      aCorpus,
      chainEdges((nJ, nJohn), (nJane, nJ)),
      links((nJane, nJohn, CannotLinks.Rejected))
    )
    assertEquals(rejected.refused, Map(EdgeCollector.key(nJane, nJ) -> RefusedRejected))
    assertNotEquals(rejected.rootOf(nJane), rejected.rootOf(nJohn))
  }

  // Inverted in Phase 11 step 4 (finding A-01, repaired by entity-resolution-engineer in step 3b):
  // this test used to assert that without cannot-links the chain Jane ~ J. ~ John merges into one
  // cluster. The implied given-name guard now keeps Jane and John apart with no cannot-link.
  test("Clustering: without cannot-links the given-name guard still separates Jane and John") {
    val free = Clustering.run(aCorpus, chainEdges((nJane, nJ), (nJ, nJohn)))
    assertEquals(free.mergesApplied, 1)
    assertEquals(free.rootOf(nJane), free.rootOf(nJ))
    assertNotEquals(free.rootOf(nJane), free.rootOf(nJohn))
    assertEquals(free.refused, Map(EdgeCollector.key(nJ, nJohn) -> RefusedGivenName))
    // a cannot-link of a dblp-prior cluster with itself is ignored, not an error: the run is the
    // same as the run without cannot-links
    val bl = new CannotLinks.Builder(aCorpus.names.size)
    bl.add(nJane, nJane, CannotLinks.Conflict)
    assertEquals(bl.size, 0)
    val self = Clustering.run(aCorpus, chainEdges((nJane, nJ), (nJ, nJohn)), bl.build())
    assertEquals(self.mergesApplied, free.mergesApplied)
    assertEquals(self.refused, free.refused)
  }

  /** Five single-mention dblp names on a path A-B-C-D-E, cannot-link A ~ E. */
  private lazy val pathWorks: List[SW] = (0 until 5).toList.map(i =>
    SW(
      s"conf/sigir/SynthCnPath$i",
      List(s"P${('a' + i).toChar} Pathsynth", s"Coa Path$i"),
      year = 2010 + i
    )
  )
  private lazy val pathCorpus: Corpus = ErSynth.corpus(writeExport(pathWorks, Nil))

  test("Clustering: on a path with A ~ E cannot-linked, every merge order keeps A and E apart") {
    val n = pathWorks.map(w => nameIdx(pathCorpus, w, 0))
    val edges = (0 until 4).map(i => (n(i), n(i + 1))).toList
    val rnd = new scala.util.Random(20261114L)
    val orders = (edges.permutations.toList ++ List.fill(10)(rnd.shuffle(edges))).distinct
    assertEquals(orders.size, 24)
    List(CannotLinks.Rejected, CannotLinks.Conflict).foreach { kind =>
      orders.foreach { order =>
        val ec = new EdgeCollector
        // confidence by position: the first edge of `order` merges first
        order.zipWithIndex.foreach { case ((x, y), i) =>
          ec.add(x, y, 0.99 - i * 0.01, MatchPath.Probabilistic)
        }
        val bl = new CannotLinks.Builder(pathCorpus.names.size)
        bl.add(n(4), n(0), kind)
        val res = Clustering.run(pathCorpus, ec, bl.build())
        assertNotEquals(res.rootOf(n(0)), res.rootOf(n(4)), s"order $order kind $kind")
        // exactly one merge is refused, three applied (the path minus one edge)
        assertEquals(res.mergesApplied, 3, s"order $order")
        assertEquals(res.refused.size, 1, s"order $order")
        val expected = if (kind == CannotLinks.Conflict) RefusedConflict else RefusedRejected
        assertEquals(res.refused.values.toList, List(expected))
        // the refused edge is the last of the order that would close A ~ E
        assertEquals(res.refused.keySet, Set(EdgeCollector.key(order.last._1, order.last._2)))
      }
    }
  }
}
