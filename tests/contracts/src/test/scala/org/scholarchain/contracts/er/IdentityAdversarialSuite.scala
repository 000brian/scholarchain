package org.scholarchain.contracts.er

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.text.Normalizer

import org.scholarchain.contracts.er.ErSynth.*
import org.scholarchain.core.*
import org.scholarchain.er.io.DataFiles
import org.scholarchain.er.llm.AlwaysAbstainAdjudicator
import org.scholarchain.er.policy.{DecisionEngine, Policy}
import org.scholarchain.er.review.QueueSpec

/** End-to-end entity-resolution attacks through `ErRun.run` on one synthetic export (decision 18):
  * conflicting ORCIDs at pair and cluster level, same-work mentions, the transitive chain, every
  * mention in exactly one cluster, consistency of one pair across block families, exact-ID
  * UNRESOLVED, missing features, cluster-guard decisions inheriting their evidence path, and the
  * dblp prior under canonical equivalence: decision 5 keys the prior on the exact dblp name string,
  * so precomposed (NFC) and decomposed (NFD) spellings start as two prior clusters and join only
  * through an accepted `name.canonical-equivalent` decision, subject to the merge guards; outputs
  * show each mention's own spelling (decision 13).
  *
  * Groups use distinct surnames, so no candidate links two groups. Non-ASCII names are written with
  * explicit escapes so the normalization form of every spelling is unambiguous.
  */
class IdentityAdversarialSuite extends munit.FunSuite {

  private val X = orcid("000000021111111")
  private val Y = orcid("000000022222222")
  private val Z = orcid("000000023333333")

  // A. pair-level ORCID conflict under one dblp name (dblp attribute on both sides)
  private val orcA =
    SW("conf/sigir/SynthOrcA", List("Orc Synthone", "Coa Oa"), orcids = Map(0 -> X))
  private val orcB =
    SW("conf/sigir/SynthOrcB", List("Orc Synthone", "Coa Ob"), orcids = Map(0 -> Y))
  // A2. ORCID conflict where one side comes from a J04 mention link
  private val orcC =
    SW("conf/sigir/SynthOrcC", List("Orc Synthtwo", "Coa Oc"), orcids = Map(0 -> X))
  private val orcD = SW("conf/sigir/SynthOrcD", List("Orc Synthtwo", "Coa Od"))

  // B. transitive chain: A ~ B, B ~ C, A and C carry different ORCIDs
  private val shared = List("Coa Chainone", "Coa Chaintwo")
  private val chA =
    SW("conf/sigir/SynthChainA", "Zoë Chainname" :: shared, orcids = Map(0 -> X))
  private val chB = SW("conf/sigir/SynthChainB", "Zoe Chainname" :: shared)
  private val chC =
    SW("conf/sigir/SynthChainC", "Zoé Chainname" :: shared, orcids = Map(0 -> Z))

  // C. same work: two different names on one work; a later accepted pair would join them
  private val swA = SW("conf/sigir/SynthWorkA", List("Kim Workname", "K. Workname", "Coa Wa"))
  private val swB = SW("conf/sigir/SynthWorkB", List("Kim Workname", "Coa Wb", "Coa Wc"))
  private val swC = SW("conf/sigir/SynthWorkC", List("Coa Wb", "K. Workname", "Coa Wc"))

  // D. orphans: mentions with no candidate at all (as after an uncovered oversize block)
  private val orA = SW("conf/sigir/SynthOrphA", List("Ophelia Orphan", "Coa Ra"), year = 2012)
  private val orB = SW("conf/sigir/SynthOrphB", List("Ophelia Orphan", "Coa Rb"), year = 2011)
  private val orC = SW("conf/sigir/SynthOrphC", List("Ophelia Orphan", "Coa Rc"), year = 2011)

  // E. canonical equivalence in the dblp prior, no candidate: precomposed U+00E9 and e + U+0301
  // are two exact dblp name strings, hence two dblp-prior clusters (decision 5)
  private val PriorNfc = "René Priorname"
  private val PriorNfd = "René Priorname"
  private val nfA = SW("conf/sigir/SynthNfA", List(PriorNfc, "Coa Na"))
  private val nfB = SW("conf/sigir/SynthNfB", List(PriorNfd, "Coa Nb"))

  // J. NFC/NFD with a candidate: name.canonical-equivalent joins the two whole prior clusters
  private val JoinNfc = "Renée Joinname"
  private val JoinNfd = "Renée Joinname"
  private val cjA = SW("conf/sigir/SynthCjA", List(JoinNfc, "Coa Ja"), year = 2015)
  private val cjB = SW("conf/sigir/SynthCjB", List(JoinNfd, "Coa Jb"), year = 2005) // earliest
  private val cjC = SW("conf/sigir/SynthCjC", List("Coa Jc", JoinNfc), year = 2016) // no candidate

  // K. NFC/NFD candidate between ORCID-less mentions whose prior clusters hold different ORCIDs on
  // other mentions: the accepted canonical-equivalent evidence must be refused by the ORCID guard
  private val GuardNfc = "André Guardname"
  private val GuardNfd = "André Guardname"
  private val kA = SW("conf/sigir/SynthKA", List(GuardNfc, "Coa Ka"))
  private val kA2 = SW("conf/sigir/SynthKA2", List(GuardNfc, "Coa Kb"), orcids = Map(0 -> X))
  private val kB = SW("conf/sigir/SynthKB", List(GuardNfd, "Coa Kc"))
  private val kB2 = SW("conf/sigir/SynthKB2", List(GuardNfd, "Coa Kd"), orcids = Map(0 -> Y))

  // L. NFC/NFD pair carrying ORCIDs itself: different -> pair-level conflict; equal -> exact merge
  private val lA =
    SW("conf/sigir/SynthLA", List("René Pairorcid", "Coa La"), orcids = Map(0 -> X))
  private val lB =
    SW("conf/sigir/SynthLB", List("René Pairorcid", "Coa Lb"), orcids = Map(0 -> Y))
  private val sA =
    SW("conf/sigir/SynthSA", List("René Sameorcid", "Coa Sa"), orcids = Map(0 -> Z))
  private val sB =
    SW("conf/sigir/SynthSB", List("René Sameorcid", "Coa Sb"), orcids = Map(0 -> Z))

  // M. NFC/NFD spellings with different homonym numbers stay different dblp people
  private val hA = SW("conf/sigir/SynthHA", List("René Homname 0001", "Coa Ha"))
  private val hB = SW("conf/sigir/SynthHB", List("René Homname 0002", "Coa Hb"))

  // N. Cyrillic look-alike: Cyrillic A (U+0410) is not canonically equivalent to Latin A
  private val cyA = SW("conf/sigir/SynthCyA", List("Аnna Lookname", "Coa Ya"))
  private val cyB = SW("conf/sigir/SynthCyB", List("Anna Lookname", "Coa Yb"))

  // F. one pair under several block families
  private val fmA = SW("conf/sigir/SynthFamA", List("Lee Famname", "Coa Fa", "Coa Fb"))
  private val fmB = SW("conf/sigir/SynthFamB", List("Lee Famname", "Coa Fa", "Coa Fb"))
  private val fsA =
    SW("conf/sigir/SynthFamC", List("Lea Famscore", "Coa Fc", "Coa Fd"), year = 2014)
  private val fsB = SW("conf/sigir/SynthFamD", List("L. Famscore", "Coa Fe", "Coa Fc"), year = 2016)

  // G. exact identifier accepted, merge refused by the dblp disambiguation-profile guard
  private val dsA = SW("conf/sigir/SynthDisA", List("Wei Synthdis", "Coa Da"), orcids = Map(0 -> Y))
  private val dsB =
    SW("conf/sigir/SynthDisB", List("Wei Synthdis 0001", "Coa Db"), orcids = Map(0 -> Y))
  private val dsC = SW("conf/sigir/SynthDisC", List("Wei Synthdis 0002", "Coa Dc"))

  // H. missing features: single author, no venue, stop-word title; truncated coauthors, unmapped venue
  private val msA =
    SW("conf/sigir/SynthMissA", List("Max Missname"), venue = None, title = "Of The")
  private val msB = SW(
    "conf/sigir/SynthMissB",
    List("M. Missname", "Coa Ma"),
    venue = Some("conf/unmappedsynth"),
    truncatedCoauthors = true
  )

  // I. one dblp name listed twice on one work, plus once elsewhere (a prior cluster with same-work)
  private val dpA = SW("conf/sigir/SynthDupA", List("Dup Samename", "Coa Ua", "Dup Samename"))
  private val dpB = SW("conf/sigir/SynthDupB", List("Dup Samename", "Coa Ub"))

  private val works =
    List(
      dpA,
      dpB,
      orcA,
      orcB,
      orcC,
      orcD,
      chA,
      chB,
      chC,
      swA,
      swB,
      swC,
      orA,
      orB,
      orC,
      nfA,
      nfB,
      cjA,
      cjB,
      cjC,
      kA,
      kA2,
      kB,
      kB2,
      lA,
      lB,
      sA,
      sB,
      hA,
      hB,
      cyA,
      cyB,
      fmA,
      fmB,
      fsA,
      fsB,
      dsA,
      dsB,
      dsC,
      msA,
      msB
    )

  private def baseKey(n: String) = AuthorNameKey.baseNameKey(n)

  private val candidates = List(
    cand(orcA, 0, orcB, 0, "dblp-base-name", "Orc Synthone"),
    cand(orcC, 0, orcD, 0, "dblp-base-name", "Orc Synthtwo"),
    cand(chA, 0, chB, 0, "initials-surname", "z chainname"),
    cand(chB, 0, chC, 0, "initials-surname", "z chainname"),
    cand(chA, 0, chC, 0, "initials-surname", "z chainname"),
    cand(swA, 0, swA, 1, "initials-surname", "k workname"),
    cand(swB, 0, swC, 1, "initials-surname", "k workname"),
    cand(fmA, 0, fmB, 0, "dblp-base-name", "Lee Famname"),
    cand(fmA, 0, fmB, 0, "initials-surname", "l famname"),
    cand(fmA, 0, fmB, 0, "dblp-base-name.coauthor", """["Lee Famname","Coa Fa"]"""),
    cand(fsA, 0, fsB, 0, "initials-surname", "l famscore"),
    cand(fsA, 0, fsB, 0, "initials-surname.coauthor", """["l famscore","Coa Fc"]"""),
    cand(dsA, 0, dsB, 0, "dblp-base-name", "Wei Synthdis"),
    cand(dsB, 0, dsC, 0, "dblp-base-name", "Wei Synthdis"),
    cand(msA, 0, msB, 0, "initials-surname", "m missname"),
    cand(dpA, 0, dpA, 2, "dblp-base-name", "Dup Samename"),
    cand(dpA, 0, dpB, 0, "dblp-base-name", "Dup Samename"),
    cand(cjA, 0, cjB, 0, "dblp-base-name", baseKey(JoinNfc)),
    cand(kA, 0, kB, 0, "dblp-base-name", baseKey(GuardNfc)),
    cand(lA, 0, lB, 0, "dblp-base-name", baseKey("René Pairorcid")),
    cand(sA, 0, sB, 0, "dblp-base-name", baseKey("René Sameorcid")),
    cand(hA, 0, hB, 0, "dblp-base-name", baseKey("René Homname")),
    cand(cyA, 0, cyB, 0, "synthetic-lookalike", "lookname")
  )

  private val links = List(mentionLink(orcD.key, 0, ExternalIdType.Orcid, Y))

  private lazy val input: Path = writeExport(works, candidates, links)
  // a queue larger than the candidate population, so every candidate pair is shown
  private lazy val outcome = run(
    erConfig(input, workRoot("identity-out-"), queue = QueueSpec(200, 2, 20261114L)),
    new AlwaysAbstainAdjudicator(Model)
  )
  private lazy val pairs = pairDecisions(outcome.runDir)
  private lazy val assigned = assignments(outcome.runDir)
  private def researcher(w: SW, pos: Int): String =
    assigned
      .find(_.left == mid(w.key, pos))
      .map(_.right)
      .getOrElse(fail(s"no assignment for ${w.key}/$pos"))
  private def assignment(w: SW, pos: Int): MatchDecision =
    assigned.find(_.left == mid(w.key, pos)).getOrElse(fail(s"no assignment for ${w.key}/$pos"))
  private def decisionsOf(a: SW, pa: Int, b: SW, pb: Int): List[MatchDecision] = {
    val ids = Set(mid(a.key, pa), mid(b.key, pb))
    val ds = pairs.filter(d => Set(d.left, d.right) == ids)
    assert(ds.nonEmpty, s"no decision for ${a.key}/$pa ~ ${b.key}/$pb")
    ds
  }
  private def feature(d: MatchDecision, name: String): Option[Double] =
    d.features.find(_.name == name).map(_.value)
  private def anchorOf(w: SW, pos: Int): String =
    ResearcherId.fromAnchorMention(MentionId(mid(w.key, pos))).value
  private lazy val nameOf: Map[String, String] =
    works.flatMap(w => w.authors.indices.map(i => mid(w.key, i) -> w.authors(i))).toMap
  private lazy val clusterRows: List[ujson.Value] =
    lines(outcome.runDir.resolve("clusters/clusters.jsonl")).map(ujson.read(_))

  test("every candidate gets exactly one decision, keyed by its candidate ID") {
    assertEquals(pairs.size, candidates.size)
    assertEquals(pairs.flatMap(_.candidateId).toSet, candidates.map(_.candidateId).toSet)
    assertEquals(pairs.flatMap(_.candidateId).distinct.size, candidates.size)
    pairs.foreach { d =>
      assertEquals(d.decisionTime, DecisionTime)
      assertEquals(d.policyVersion, Policy.erV1.policyVersion)
      assertEquals(d.provenance.runId.value, outcome.runId)
    }
  }

  test(
    "conflicting ORCIDs under one dblp name: CONFLICT at pair level, flagged cluster not split"
  ) {
    val d = decisionsOf(orcA, 0, orcB, 0).head
    assertEquals(d.outcome, MatchOutcome.Conflict)
    assertEquals(d.matchPath, MatchPath.ExactId)
    assert(d.rulesFired.contains("orcid.disagree"), d.rulesFired)
    // the dblp prior holds both: kept as one cluster, flagged conflict on every member
    assertEquals(researcher(orcA, 0), researcher(orcB, 0))
    List(assignment(orcA, 0), assignment(orcB, 0)).foreach { a =>
      assertEquals(a.outcome, MatchOutcome.Conflict)
      assert(a.reasonCodes.contains("cluster-multiple-orcids"), a.reasonCodes)
    }
  }

  test("an ORCID from a J04 mention link conflicts with a different dblp ORCID") {
    val d = decisionsOf(orcC, 0, orcD, 0).head
    assertEquals(d.outcome, MatchOutcome.Conflict)
    assertEquals(d.matchPath, MatchPath.ExactId)
  }

  test("transitive chain A~B, B~C with A and C conflicting never puts A and C in one cluster") {
    val ac = decisionsOf(chA, 0, chC, 0).head
    assertEquals(ac.outcome, MatchOutcome.Conflict)
    assertNotEquals(researcher(chA, 0), researcher(chC, 0))
    val ab = decisionsOf(chA, 0, chB, 0).head
    val bc = decisionsOf(chB, 0, chC, 0).head
    // exactly one of the two links is applied; the other is refused by the ORCID guard
    val outcomes = List(ab.outcome, bc.outcome)
    assertEquals(outcomes.count(_ == MatchOutcome.Accepted), 1, s"$ab\n$bc")
    assertEquals(outcomes.count(_ == MatchOutcome.Conflict), 1, s"$ab\n$bc")
    val refused = if (ab.outcome == MatchOutcome.Conflict) ab else bc
    assert(refused.reasonCodes.contains("merge-refused:orcid-conflict"), refused.reasonCodes)
    assert(refused.rulesFired.contains("cluster.guard.orcid"), refused.rulesFired)
    val joined = if (ab.outcome == MatchOutcome.Accepted) chA else chC
    assertEquals(researcher(joined, 0), researcher(chB, 0))
  }

  test("no final cluster holds two different ORCIDs unless its dblp prior already did") {
    val orcidOf: Map[String, String] =
      works.flatMap(w => w.orcids.map { case (p, o) => mid(w.key, p) -> o }).toMap ++
        Map(mid(orcD.key, 0) -> Y)
    assigned.groupBy(_.right).foreach { case (rid, ms) =>
      val os = ms.flatMap(m => orcidOf.get(m.left)).distinct
      if (os.size > 1) {
        // only the deliberately conflicting dblp-prior clusters (one exact dblp name string)
        val names = ms.map(m => nameOf(m.left)).distinct
        assertEquals(names.size, 1, s"cluster $rid merged different dblp names holding ORCIDs $os")
      }
    }
  }

  test("two mentions of the same work never merge, directly or through a later accepted pair") {
    val direct = decisionsOf(swA, 0, swA, 1).head
    assertEquals(direct.outcome, MatchOutcome.Conflict)
    assert(direct.rulesFired.contains("structural.same-work"), direct.rulesFired)
    assertNotEquals(researcher(swA, 0), researcher(swA, 1))
    val later = decisionsOf(swB, 0, swC, 1).head
    assertEquals(later.outcome, MatchOutcome.Conflict, later.toString)
    assert(later.reasonCodes.contains("merge-refused:same-work"), later.reasonCodes)
    // no cluster holds two mentions of one work, except a dblp-prior cluster that already did,
    // and that one is flagged conflict on every member
    val workOf = works.flatMap(w => w.authors.indices.map(i => mid(w.key, i) -> w.key)).toMap
    assigned.groupBy(_.right).foreach { case (rid, ms) =>
      val ws = ms.map(m => workOf(m.left))
      if (ws.distinct.size != ws.size)
        ms.foreach(m =>
          assert(
            m.outcome == MatchOutcome.Conflict && m.reasonCodes
              .contains("cluster-same-work-mentions"),
            s"cluster $rid holds two mentions of one work without a conflict flag: $m"
          )
        )
    }
  }

  test("a dblp-prior cluster already holding two mentions of one work is kept whole and flagged") {
    val direct = decisionsOf(dpA, 0, dpA, 2).head
    assertEquals(direct.outcome, MatchOutcome.Conflict)
    assert(direct.rulesFired.contains("structural.same-work"), direct.rulesFired)
    val rs = List(researcher(dpA, 0), researcher(dpA, 2), researcher(dpB, 0)).distinct
    assertEquals(rs.size, 1, "the prior cluster must not be split")
    List(assignment(dpA, 0), assignment(dpA, 2), assignment(dpB, 0)).foreach { a =>
      assertEquals(a.outcome, MatchOutcome.Conflict)
      assert(a.reasonCodes.contains("cluster-same-work-mentions"), a.reasonCodes)
    }
  }

  test("every subset mention gets exactly one identity assignment, orphans included") {
    val all = works.flatMap(w => w.authors.indices.map(i => mid(w.key, i)))
    assertEquals(assigned.size, all.size)
    assertEquals(assigned.map(_.left).toSet, all.toSet)
    assertEquals(assigned.map(_.left).distinct.size, all.size)
    assigned.foreach { a =>
      assertEquals(a.subjectKind, MatchSubjectKind.MentionResearcher)
      assertEquals(a.candidateId, None)
      assert(a.outcome == MatchOutcome.Accepted || a.outcome == MatchOutcome.Conflict, a.toString)
      assert(a.rulesFired.contains("dblp-prior.cluster"), a.rulesFired)
    }
  }

  test("an orphan dblp-prior cluster keeps one ResearcherId from its earliest paper") {
    val ids = List(orA, orB, orC).map(researcher(_, 0)).distinct
    assertEquals(ids.size, 1)
    val earliest = List(orB, orC)
      .map(w => (w.year, WorkId.fromDblpKey(w.key).value, mid(w.key, 0)))
      .min(Ordering.Tuple3(Ordering.Int, CodePointOrder.ordering, CodePointOrder.ordering))
    assertEquals(ids.head, ResearcherId.fromAnchorMention(MentionId(earliest._3)).value)
    List(orA, orB, orC).foreach { w =>
      val a = assignment(w, 0)
      assertEquals(a.outcome, MatchOutcome.Accepted)
      assertEquals(a.matchPath, MatchPath.Deterministic)
    }
  }

  // ------------------------------------------------------------------------------------------
  // Decision 5 under canonical equivalence (F3 repair): prior keyed on the exact name string
  // ------------------------------------------------------------------------------------------

  test("precondition: the synthetic NFC and NFD spellings differ as strings but not canonically") {
    List(PriorNfc -> PriorNfd, JoinNfc -> JoinNfd, GuardNfc -> GuardNfd).foreach { case (c, d) =>
      assertNotEquals(c, d)
      assertEquals(Normalizer.normalize(c, Normalizer.Form.NFC), c)
      assertEquals(Normalizer.normalize(d, Normalizer.Form.NFD), d)
      assertEquals(Normalizer.normalize(d, Normalizer.Form.NFC), c)
      assertEquals(AuthorNameKey.fullNameKey(c), AuthorNameKey.fullNameKey(d))
    }
    assertNotEquals(
      AuthorNameKey.fullNameKey("Аnna Lookname"),
      AuthorNameKey.fullNameKey("Anna Lookname")
    )
  }

  test("decision 5: with no candidate, NFC and NFD spellings are two dblp-prior clusters") {
    assertNotEquals(researcher(nfA, 0), researcher(nfB, 0))
    // each prior cluster takes its ResearcherId from its own (only) mention
    assertEquals(researcher(nfA, 0), anchorOf(nfA, 0))
    assertEquals(researcher(nfB, 0), anchorOf(nfB, 0))
    List(assignment(nfA, 0), assignment(nfB, 0)).foreach { a =>
      assertEquals(a.outcome, MatchOutcome.Accepted)
      assertEquals(a.matchPath, MatchPath.Deterministic)
      assertEquals(a.rulesFired, List("dblp-prior.cluster"))
      assertEquals(feature(a, "cluster_prior_clusters"), Some(1.0))
      assertEquals(feature(a, "cluster_mentions"), Some(1.0))
    }
    // clusters.jsonl lists each spelling as written, in its own cluster
    List(nfA -> PriorNfc, nfB -> PriorNfd).foreach { case (w, spelling) =>
      val row = clusterRows
        .find(_("researcherId").str == researcher(w, 0))
        .getOrElse(fail(s"no cluster row for ${w.key}"))
      assertEquals(row("dblpNames").arr.map(_.str).toList, List(spelling))
      assertEquals(row("priorClusters").num, 1.0)
      assertEquals(row("mergePath"), ujson.Null)
    }
    // the report counts exact strings, not canonical keys
    val exact = works.flatMap(_.authors).distinct.size
    assertEquals(outcome.report("inputs")("dblpNames").num, exact.toDouble)
    assertEquals(outcome.report("clusters")("dblpPriorClusters").num, exact.toDouble)
    assert(exact > works.flatMap(_.authors).map(AuthorNameKey.fullNameKey).distinct.size)
  }

  test("an NFC/NFD candidate fires name.canonical-equivalent and joins both whole prior clusters") {
    val d = decisionsOf(cjA, 0, cjB, 0).head
    assertEquals(d.outcome, MatchOutcome.Accepted)
    assertEquals(d.matchPath, MatchPath.Deterministic)
    assertEquals(d.rulesFired, List("name.canonical-equivalent"))
    assertEquals(d.reasonCodes, Nil)
    assertEqualsDouble(d.confidence, 0.95, 1e-12)
    // the cjC mention has no candidate; it joins because its whole prior cluster (JoinNfc) joins
    val rid = researcher(cjA, 0)
    assertEquals(researcher(cjB, 0), rid)
    assertEquals(researcher(cjC, 1), rid)
    // earliest paper of the merged cluster is the NFD mention (2005)
    assertEquals(rid, anchorOf(cjB, 0))
    List(assignment(cjA, 0), assignment(cjB, 0), assignment(cjC, 1)).foreach { a =>
      assertEquals(a.outcome, MatchOutcome.Accepted, a.toString)
      assertEquals(a.matchPath, MatchPath.Deterministic)
      assertEquals(a.rulesFired.toSet, Set("dblp-prior.cluster", "cluster.merged"))
      assertEquals(feature(a, "cluster_prior_clusters"), Some(2.0))
      assertEquals(feature(a, "cluster_mentions"), Some(3.0))
    }
    val row = clusterRows.find(_("researcherId").str == rid).getOrElse(fail("no cluster row"))
    assertEquals(
      row("dblpNames").arr.map(_.str).toList,
      List(JoinNfc, JoinNfd).sorted(CodePointOrder.ordering)
    )
    assertEquals(row("priorClusters").num, 2.0)
    assertEquals(row("mentions").num, 3.0)
    assertEquals(row("mergePath").str, "deterministic")
    assertEquals(row("anchorMentionId").str, mid(cjB.key, 0))
  }

  test("canonical equivalence never overrides the ORCID merge guard: CONFLICT, clusters apart") {
    val d = decisionsOf(kA, 0, kB, 0).head
    assertEquals(d.outcome, MatchOutcome.Conflict, d.toString)
    // the refused evidence keeps its own path and confidence (guards declare path "inherited")
    assertEquals(d.matchPath, MatchPath.Deterministic)
    assertEqualsDouble(d.confidence, 0.95, 1e-12)
    assertEquals(d.rulesFired.toSet, Set("name.canonical-equivalent", "cluster.guard.orcid"))
    assertEquals(d.reasonCodes, List("merge-refused:orcid-conflict"))
    assertNotEquals(researcher(kA, 0), researcher(kB, 0))
    assertEquals(researcher(kA, 0), researcher(kA2, 0)) // one exact string, one prior cluster
    assertEquals(researcher(kB, 0), researcher(kB2, 0))
    List(kA, kA2, kB, kB2).foreach { w =>
      val a = assignment(w, 0)
      assertEquals(a.outcome, MatchOutcome.Accepted, a.toString) // one ORCID per cluster
      assert(!a.rulesFired.contains("cluster.merged"), a.rulesFired)
      assertEquals(feature(a, "cluster_prior_clusters"), Some(1.0))
    }
  }

  test(
    "an NFC/NFD pair with its own different ORCIDs is a pair-level CONFLICT; equal ORCIDs merge"
  ) {
    val diff = decisionsOf(lA, 0, lB, 0).head
    assertEquals(diff.outcome, MatchOutcome.Conflict)
    assertEquals(diff.matchPath, MatchPath.ExactId)
    assert(diff.rulesFired.contains("orcid.disagree"), diff.rulesFired)
    assert(!diff.rulesFired.contains("name.canonical-equivalent"), diff.rulesFired)
    assertNotEquals(researcher(lA, 0), researcher(lB, 0))

    val same = decisionsOf(sA, 0, sB, 0).head
    assertEquals(same.outcome, MatchOutcome.Accepted)
    assertEquals(same.matchPath, MatchPath.ExactId)
    assertEquals(same.rulesFired, List("orcid.exact"))
    assertEquals(researcher(sA, 0), researcher(sB, 0))
    val a = assignment(sA, 0)
    assertEquals(a.matchPath, MatchPath.ExactId) // the only merge was exact_id
    assertEquals(feature(a, "cluster_prior_clusters"), Some(2.0))
  }

  test("NFC/NFD spellings with different homonym numbers are rejected as distinct dblp people") {
    val d = decisionsOf(hA, 0, hB, 0).head
    assertEquals(d.outcome, MatchOutcome.Rejected)
    assertEquals(d.rulesFired, List("dblp-prior.distinct-homonym"))
    assertNotEquals(researcher(hA, 0), researcher(hB, 0))
  }

  test("a Cyrillic look-alike is not canonically equivalent and is not joined by a name rule") {
    val d = decisionsOf(cyA, 0, cyB, 0).head
    assert(!d.rulesFired.contains("name.canonical-equivalent"), d.rulesFired)
    assert(!d.rulesFired.contains("dblp-prior.same-name"), d.rulesFired)
    assertNotEquals(d.matchPath, MatchPath.Deterministic, d.toString)
  }

  test("dblp-prior.same-name fires only on byte-identical exact name strings") {
    pairs.filter(_.rulesFired.contains("dblp-prior.same-name")).foreach { d =>
      assertEquals(nameOf(d.left), nameOf(d.right), d.toString)
    }
    pairs.filter(_.rulesFired.contains("name.canonical-equivalent")).foreach { d =>
      assertNotEquals(nameOf(d.left), nameOf(d.right), d.toString)
      assertEquals(
        AuthorNameKey.fullNameKey(nameOf(d.left)),
        AuthorNameKey.fullNameKey(nameOf(d.right))
      )
    }
    assert(pairs.exists(_.rulesFired.contains("dblp-prior.same-name")))
  }

  test("decision 13: the review queue shows each mention's own spelling, byte for byte") {
    val rows = lines(outcome.runDir.resolve("review-queue/review-queue.jsonl")).map(ujson.read(_))
    assert(rows.nonEmpty)
    rows.foreach { r =>
      List("left", "right").foreach { side =>
        val m = r(s"${side}MentionId").str
        assertEquals(r(s"${side}_name").str, nameOf(m), s"$side name of $m")
      }
    }
    // the NFC/NFD candidate pairs are in the queue, each side spelled as its own record
    List((cjA, cjB, JoinNfc, JoinNfd), (kA, kB, GuardNfc, GuardNfd)).foreach {
      case (a, b, sa, sb) =>
        val row = rows
          .find(r =>
            Set(r("leftMentionId").str, r("rightMentionId").str) ==
              Set(mid(a.key, 0), mid(b.key, 0))
          )
          .getOrElse(
            fail(s"pair ${a.key} ~ ${b.key} missing from a queue larger than the population")
          )
        assertEquals(Set(row("left_name").str, row("right_name").str), Set(sa, sb))
    }
    // the CSV carries the decomposed bytes unchanged
    val csv = new String(
      java.nio.file.Files.readAllBytes(outcome.runDir.resolve("review-queue/review-queue.csv")),
      StandardCharsets.UTF_8
    )
    assert(csv.contains(JoinNfd) && csv.contains(JoinNfc), "CSV must keep both spellings")
  }

  // ------------------------------------------------------------------------------------------
  // Cluster guards (F4 repair): path "inherited" means the evidence rule's own path
  // ------------------------------------------------------------------------------------------

  test("every guard-refused decision carries a concrete path and confidence of its evidence rule") {
    val policy = ujson.read(
      new String(DataFiles.readResource(Policy.DefaultResource), StandardCharsets.UTF_8)
    )
    val rules = policy("rules").arr.map(r => r("id").str -> r).toMap
    val guards = rules.filter(_._2("kind").str == "cluster-guard").keySet
    // four structural guards plus the two cannot-link guards of repair M1
    assertEquals(
      guards,
      Set(
        "cluster.guard.orcid",
        "cluster.guard.same-work",
        "cluster.guard.dblp-distinct-homonym",
        "cluster.guard.dblp-disambiguation-profile",
        "cluster.guard.conflict-pair",
        "cluster.guard.rejected-pair"
      )
    )
    guards.foreach { g =>
      assertEquals(rules(g)("path").str, "inherited", g)
      assertEquals(rules(g)("confidence"), ujson.Null, g)
    }
    // a refused cannot-link merge never stays accepted: conflict-pair -> conflict,
    // rejected-pair -> unresolved
    assertEquals(rules("cluster.guard.conflict-pair")("outcome").str, "conflict")
    assertEquals(rules("cluster.guard.rejected-pair")("outcome").str, "unresolved")
    val concrete = Set("exact_id", "deterministic", "probabilistic", "llm")
    val guarded = pairs.filter(_.rulesFired.exists(guards))
    val seen = guarded.flatMap(_.rulesFired.filter(guards)).toSet
    assert(
      Set(
        "cluster.guard.orcid",
        "cluster.guard.same-work",
        "cluster.guard.dblp-disambiguation-profile"
      ).subsetOf(seen),
      seen
    )
    guarded.foreach { d =>
      assertEquals(d.rulesFired.count(guards), 1, d.toString)
      val evidence = d.rulesFired.filterNot(guards)
      assert(evidence.nonEmpty, s"guard decision without its evidence rule: $d")
      assert(concrete(d.matchPath.wire), d.toString)
      val evidencePaths = evidence.map(e => rules(e)("path").str).toSet
      assert(evidencePaths.contains(d.matchPath.wire), s"path ${d.matchPath.wire} vs $evidence")
      // a fixed-confidence evidence rule hands its confidence to the refused decision
      evidence.flatMap(e => rules(e)("confidence").numOpt) match {
        case List(c) => assertEqualsDouble(d.confidence, c, 1e-12, d.toString)
        case _       => assert(d.confidence > 0.0 && d.confidence <= 1.0, d.toString)
      }
      // a refused merge never stays ACCEPTED
      assertNotEquals(d.outcome, MatchOutcome.Accepted, d.toString)
    }
  }

  test("one mention pair under several block families gets one consistent outcome") {
    List((fmA, fmB), (fsA, fsB)).foreach { case (a, b) =>
      val ds = decisionsOf(a, 0, b, 0)
      assert(ds.size >= 2, ds.toString)
      assertEquals(ds.flatMap(_.candidateId).distinct.size, ds.size)
      val shapes =
        ds.map(d => (d.outcome, d.matchPath, d.confidence, d.rulesFired, d.reasonCodes, d.features))
      assertEquals(shapes.distinct.size, 1, shapes.mkString("\n"))
    }
    assertEquals(decisionsOf(fmA, 0, fmB, 0).head.outcome, MatchOutcome.Accepted)
  }

  test(
    "exact-ID UNRESOLVED comes from an accepted ORCID rule refused by the disambiguation guard"
  ) {
    val d = decisionsOf(dsA, 0, dsB, 0).head
    assertEquals(d.matchPath, MatchPath.ExactId)
    assertEquals(d.outcome, MatchOutcome.Unresolved)
    assertEquals(
      d.rulesFired,
      List("cluster.guard.dblp-disambiguation-profile", "orcid.exact")
    )
    assertEquals(d.reasonCodes, List("merge-refused:dblp-disambiguation-profile"))
    assertNotEquals(researcher(dsA, 0), researcher(dsB, 0))
    val homonyms = decisionsOf(dsB, 0, dsC, 0).head
    assertEquals(homonyms.outcome, MatchOutcome.Rejected)
    assertNotEquals(researcher(dsB, 0), researcher(dsC, 0))
  }

  test("missing features stay missing on the written decision") {
    val d = decisionsOf(msA, 0, msB, 0).head
    val names = d.features.map(_.name).toSet
    val absent = Set(
      "coauthor_shared_count", // truncated coauthor context
      "coauthor_overlap_ratio",
      "same_venue", // one work has no venue
      "venue_area_match",
      "author_role_match", // single-author work
      "title_similarity", // stop-word-only title has no tokens
      "orcid_exact_match",
      "orcid_match",
      "homonym_suffix_match",
      "openalex_author_match",
      "affiliation_overlap"
    )
    assertEquals(names.intersect(absent), Set.empty[String], d.features.toString)
    Set("given_name_relation", "surname_equal", "name_exact_match", "same_work", "year_abs_diff")
      .foreach(n => assert(names(n), s"$n missing from ${d.features}"))
  }

  test("a missing feature is not scored as zero") {
    val corpus = ErSynth.corpus(input)
    val c = cand(fsA, 0, fsB, 0, "initials-surname", "l famscore")
    val a = corpus.mentions.indexOf(c.left)
    val b = corpus.mentions.indexOf(c.right)
    val withZero =
      c.features.map(f => if (f.name == "coauthor_shared_count") f.copy(value = 0.0) else f)
    val missing = c.features.filterNot(_.name == "coauthor_shared_count")
    val pz = DecisionEngine.assess(corpus, Policy.erV1, withZero, a, b).matchProbability.get
    val pm = DecisionEngine.assess(corpus, Policy.erV1, missing, a, b).matchProbability.get
    assert(pm > pz, s"missing ($pm) must not be scored like 0 ($pz)")
    val fm = DecisionEngine.assess(corpus, Policy.erV1, missing, a, b).features.map(_.name)
    assert(!fm.contains("coauthor_shared_count") && !fm.contains("coauthor_overlap_ratio"), fm)
  }
}
