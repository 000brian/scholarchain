package org.scholarchain.rag.runtime

import org.scholarchain.core._

/** Phase 17 decision 6: one fail-closed rule for claims about the absence of a connection
  * (P16-L-10..L-15, review-report-P16-L-round2.md). Each finding test uses the reviewer's failure
  * input and asserts both verdicts: the Phase 16 one (the rule switched off, which is what the test
  * would see without the fix) and the Phase 17 one. Every verdict goes through [[VerifierOracle]],
  * so each claim here is also checked for monotonicity.
  */
class AbsenceRuleSuite extends munit.FunSuite {
  import RuntimeFixtures._
  import VerificationStatus._
  import ClaimVerification.Reasons
  import AbsenceRule._

  private val ctx = RuntimeContext.from(config, sparkRun, Set(Full)).toOption.get
  private val queryRun = RunId.of("rag.query", "test", "q", "c").toOption.get
  private val alice = ResearcherRef(rid("A"), "Alice Smith")
  private val bob = ResearcherRef(rid("B"), "Bob Jones")
  private val carol = ResearcherRef(rid("C"), "Carol King")
  private val src = SourceRef("graph:path_between_depth_6", sparkRun, "graph-v1", sparkRun.value)

  // Alice - Carol - Bob, 2 hops, in 2015-2025 (PathAbsenceSuite's `found`)
  private val found =
    PathFact(List(alice, carol, bob), Full, FullW, "graph-path-search", None, false, src, 20)
  // complete path=none for Alice Smith / Bob Jones, depth 6, in 2021-2023 (`noneW1K`)
  private val noneW1K = PathAbsentFact(alice, bob, W1, W1W, 6, true, 6, src, 20, None)
  private val carolW1 =
    MetricFact(carol, W1, W1W, CollaborationMetric.DistinctCoauthors, 5.0, None, None, src, 20)

  /** (Phase 17 verdict, Phase 16 verdict), the claim citing every fact. */
  private def both(text: String, facts: List[Fact]): (VerifiedClaim, VerifiedClaim) = {
    val built = new EvidenceBuilder(ctx).build(queryRun, facts).fold(e => fail(e), identity)
    val items = built.items.map(i => i.evidenceId -> i).toMap
    val ids = facts.map(f => built.idOf(f).value)
    VerifierOracle.compare(DraftClaim("claim-1", text, ids, Nil, None), items)
  }

  private def show(v: VerifiedClaim) = s"${v.status.wire} ${v.reasons}"

  // ---- the lists and the scope rule (one test per list entry class) ----------------------------

  private def kind(clause: String) = classify(clause, namesSomeone = true)

  test("item a: every negation word, and contractions ending n't, govern a connection predicate") {
    (Negations.toList ++ List("aren't", "isn't", "haven't", "hasn't", "didn't", "don't", "can't"))
      .foreach { n =>
        assertEquals(kind(s"Alice Smith and Bob Jones $n connected"), Absence, n)
      }
    // curly apostrophes are read as straight ones
    assertEquals(kind("Alice Smith and Bob Jones won’t be linked"), Absence)
    assert(isNegation("won't") && isNegation("couldn't") && !isNegation("known"))
  }

  test("item a: every connection predicate is an absence under a negation, else a connection") {
    (ConnectionWords.toList ++ ConnectionPhrases.map(_.mkString(" "))).foreach { p =>
      assertEquals(kind(s"Alice Smith and Bob Jones are not $p"), Absence, p)
      assertEquals(kind(s"Alice Smith and Bob Jones are $p"), Connection, p)
    }
  }

  test("item a: every absence predicate is an absence unless negated (then a connection)") {
    (AbsenceWords.toList ++ AbsencePhrases.map(_.mkString(" "))).foreach { p =>
      assertEquals(kind(s"Alice Smith and Bob Jones are $p"), Absence, p)
      assertEquals(kind(s"Alice Smith and Bob Jones are not $p"), Connection, p)
    }
  }

  test("item a: scope words may stand between the negation and its predicate") {
    ScopeWords.foreach { w =>
      assertEquals(kind(s"Alice Smith and Bob Jones are not $w connected"), Absence, w)
    }
    assertEquals(kind("no path connects Alice Smith and Bob Jones"), Absence)
    assertEquals(kind("Alice Smith and Bob Jones are not in the same connected component"), Absence)
  }

  test("item a: an emphatic 'not only' is not a negation") {
    NotEmphatic.foreach { w =>
      assertEquals(kind(s"Alice Smith and Bob Jones are not $w connected"), Connection, w)
    }
  }

  test("item a: an unclear scope, both kinds, or no named researcher is Unclear") {
    // a negation before the predicate with other words between
    assertEquals(
      kind("there is no evidence that Alice Smith and Bob Jones are disconnected"),
      Unclear
    )
    // a negation after every predicate of its clause
    assertEquals(kind("Alice Smith is connected to no one"), Unclear)
    // both kinds of predicate in one clause
    assertEquals(kind("Alice Smith and Bob Jones are not connected and apart"), Unclear)
    // an absence statement that names no researcher
    assertEquals(classify("although they never coauthored", namesSomeone = false), Unclear)
    // a negation with no listed predicate, and a clause with neither
    assertEquals(kind("Alice Smith is not a direct coauthor of Bob Jones"), NegationOnly)
    assertEquals(kind("Alice Smith has 3 coauthors in 2015-2025"), Connection)
    assertEquals(kind("Alice Smith wrote 3 papers"), Neutral)
  }

  test("item g: the closed vocabulary holds the listed words, numbers, years and window labels") {
    val pair = parts("Alice Smith") ++ parts("Bob Jones")
    val ok = (FunctionWords ++ ScopeWords ++ Allowlist ++ Negations ++ ConnectionWords ++
      AbsenceWords ++ pair).toList ++
      List("haven't", "6", "2015-2025", "w2021-2023", "full-2015-2025", "component", "same")
    ok.foreach(w => assert(inVocabulary(tokens(w).head, pair, Set.empty), w))
    List("carlos", "or", "decade", "worked", "evidence", "his").foreach { w =>
      assert(!inVocabulary(tokens(w).head, pair, Set.empty), w)
    }
    assert(inVocabulary(tokens("w2018-2020").head, pair, Set("w2018-2020")))
  }

  test("item b, d, e: third names, time qualifiers and unnamed-people words") {
    val pair = parts("Alice Smith") ++ parts("Bob Jones")
    assert(isThirdName(tokens("King").head, pair, Set.empty))
    assert(!isThirdName(tokens("Smith").head, pair, Set.empty))
    assert(!isThirdName(tokens("The").head, pair, Set.empty))
    assert(!isThirdName(tokens("January").head, pair, Set.empty))
    assert(isThirdName(tokens("carol").head, pair, Set("carol", "king")))
    assert(!isThirdName(tokens("alice").head, pair, Set("alice", "white")))
    List("before", "after", "since", "until", "prior to", "between").foreach { q =>
      assert(TimeQualifierRe.findFirstIn(s"not connected $q 2021").isDefined, q)
    }
    assert(TimeQualifierRe.findFirstIn("no path between Alice Smith and Bob Jones").isEmpty)
    List("his", "their", "common", "anyone", "others").foreach(w => assert(UnnamedWords(w), w))
  }

  // ---- the findings (reviewer's failure inputs) -------------------------------------------------

  test("P16-L-10: an absence synonym that a cited found path contradicts is conflicting") {
    List(
      "Alice Smith and Bob Jones are disconnected in 2015-2025.",
      "Alice Smith and Bob Jones are not in the same connected component in 2015-2025.",
      "Alice Smith and Bob Jones are unconnected in 2015-2025.",
      "Alice Smith and Bob Jones are in different components in 2015-2025."
    ).foreach { t =>
      assert(ClaimVerification.statesAbsence(t), t)
      val (now, before) = both(t, List(found))
      assertEquals(before.status, Verified, s"Phase 16: $t ${show(before)}")
      assertEquals(now.status, Conflicting, s"$t ${show(now)}")
      assert(now.reasons.contains(Reasons.HopsConflict), s"$t ${show(now)}")
    }
  }

  test("P16-L-11: a one-word third name in an absence claim blocks verified") {
    val t = "Alice Smith is not connected to Bob Jones or to King within 6 hops in 2021-2023."
    val (now, before) = both(t, List(noneW1K))
    assertEquals(before.status, Verified, show(before))
    assertEquals(now.status, Unsupported, show(now))
    // P17-A-05: the reason names the cause (a possible third name)
    assert(now.reasons.contains(Reasons.AbsenceThirdName), show(now))
  }

  private val pathABC =
    PathFact(List(alice, bob, carol), Full, FullW, "graph-path-search", None, false, src, 20)

  test("P16-L-12: a found path with both researchers anywhere on it blocks the absence") {
    val t = "Alice Smith and Bob Jones are not connected within 6 hops in 2021-2023."
    val (now, before) = both(t, List(noneW1K, pathABC))
    assertEquals(before.status, Verified, show(before))
    assertEquals(now.status, Unsupported, show(now))
    // P17-A-05: the complete search for the pair and window exists; the cause is the found path
    assert(now.reasons.contains(Reasons.AbsenceFoundPath), show(now))
    assert(!now.reasons.contains(Reasons.AbsenceNotSearched), show(now))
    // inside the claim's window and depth, the same path contradicts it
    val pathW1 =
      PathFact(List(alice, bob, carol), W1, W1W, "graph-path-search", None, false, src, 20)
    val (inW1, _) = both(t, List(noneW1K, pathW1))
    assertEquals(inW1.status, Conflicting, show(inW1))
    assert(inW1.reasons.contains(Reasons.HopsConflict), show(inW1))
  }

  test("P16-L-12: a found path in the bundle that the claim does not cite also blocks it") {
    val built = new EvidenceBuilder(ctx)
      .build(queryRun, List(noneW1K, pathABC))
      .fold(e => fail(e), identity)
    val items = built.items.map(i => i.evidenceId -> i).toMap
    val c = DraftClaim(
      "claim-1",
      "Alice Smith and Bob Jones are not connected within 6 hops in 2021-2023.",
      List(built.idOf(noneW1K).value),
      Nil,
      None
    )
    val (now, before) = VerifierOracle.compare(c, items)
    assertEquals(before.status, Verified, show(before))
    assertNotEquals(now.status, Verified, show(now))
    assert(now.reasons.contains(Reasons.AbsenceFoundPath), show(now))
    assert(!now.reasons.contains(Reasons.AbsenceNotSearched), show(now))
  }

  test("P16-L-13: a year with a time qualifier, or an unparsed time phrase, blocks verified") {
    val (now, before) =
      both(
        "Alice Smith and Bob Jones were not connected within 6 hops before 2021-2023.",
        List(noneW1K)
      )
    assertEquals(before.status, Verified, show(before))
    assertEquals(now.status, Unsupported, show(now))
    assert(now.reasons.contains(Reasons.AbsenceTimeQualifier), show(now))
    assert(!now.reasons.contains(Reasons.AbsenceNotSearched), show(now))
    val (v2, b2) = both(
      "Alice Smith and Bob Jones were not connected within 6 hops in 2021-2023 or in the decade before.",
      List(noneW1K)
    )
    assertEquals(b2.status, Verified, show(b2))
    assertNotEquals(v2.status, Verified, show(v2))
    // a range that differs from the searched window anywhere in the claim
    val (v3, _) = both(
      "Alice Smith and Bob Jones are not connected within 6 hops in 2021-2023 or between 2015 and 2025.",
      List(noneW1K)
    )
    assertNotEquals(v3.status, Verified, show(v3))
  }

  test("P16-L-14: an absence claim with no stated depth is unsupported (an over-claim)") {
    val (now, before) =
      both("No path connects Alice Smith and Bob Jones in 2021-2023.", List(noneW1K))
    assertEquals(before.status, PartiallySupported, show(before))
    assertEquals(now.status, Unsupported, show(now))
    assert(!now.kept)
    assert(now.reasons.contains(Reasons.AbsenceNoDepth), show(now))
  }

  test("P16-L-15: an absence claim about unnamed researchers is unsupported") {
    val t =
      "Alice Smith is not connected to Bob Jones or to any of his coauthors within 6 hops in 2021-2023."
    val (now, before) = both(t, List(noneW1K))
    assertEquals(before.status, Verified, show(before))
    assertEquals(now.status, Unsupported, show(now))
    assert(now.reasons.contains(Reasons.AbsenceUnnamed), show(now))
    val (v2, _) =
      both("Alice Smith and Bob Jones have no common coauthors in 2021-2023.", List(noneW1K))
    assertEquals(v2.status, Unsupported, show(v2))
    assert(v2.reasons.contains(Reasons.AbsenceUnnamed), show(v2))
  }

  // ---- item f regression tests ------------------------------------------------------------------

  test("item f: a sentence-initial third name blocks verified") {
    val (now, before) =
      both(
        "King, Alice Smith and Bob Jones are not connected within 6 hops in 2021-2023.",
        List(noneW1K)
      )
    assertEquals(before.status, Verified, show(before))
    assertEquals(now.status, Unsupported, show(now))
  }

  test("item b: a lowercase third name that is a part of another bundle researcher's name") {
    val t = "Alice Smith, Bob Jones and carol are not connected within 6 hops in 2021-2023."
    val (now, before) = both(t, List(noneW1K, carolW1))
    assertEquals(before.status, Verified, show(before))
    assertEquals(now.status, Unsupported, show(now))
    assert(now.reasons.contains(Reasons.AbsenceThirdName), show(now))
  }

  test(
    "item g: an unknown lowercase participant with a complete pairwise path=none is not verified"
  ) {
    val t = "Alice Smith, Bob Jones and carlos are not connected within 6 hops in 2021-2023."
    val (now, before) = both(t, List(noneW1K))
    assertEquals(before.status, Verified, show(before))
    assertNotEquals(now.status, Verified, show(now))
    assert(now.reasons.contains(Reasons.AbsenceAmbiguous), show(now))
  }

  test("item f: an unlisted relationship wording next to the pair is ambiguous, never verified") {
    val (now, before) =
      both("Alice Smith and Bob Jones have never met in 2021-2023.", List(noneW1K))
    assertEquals(before.status, Verified, show(before))
    assertEquals(now.status, PartiallySupported, show(now))
    assert(now.reasons.contains(Reasons.AbsenceAmbiguous), show(now))
  }

  test("item f: a contracted negation with a cited found path between the pair is not verified") {
    List(
      "Alice Smith and Bob Jones haven't collaborated in 2015-2025.",
      "Alice Smith and Bob Jones haven’t collaborated in 2015-2025."
    ).foreach { t =>
      val (now, before) = both(t, List(found))
      assertEquals(before.status, Verified, s"$t ${show(before)}")
      assertEquals(now.status, Conflicting, s"$t ${show(now)}")
    }
  }

  test("item a: an unclear negation scope is at most partially supported") {
    val t = "There is no evidence that Alice Smith and Bob Jones are disconnected in 2021-2023."
    val (now, before) = both(t, List(noneW1K))
    assertEquals(before.status, Verified, show(before))
    assertEquals(now.status, PartiallySupported, show(now))
    assert(now.reasons.contains(Reasons.AbsenceAmbiguous), show(now))
  }

  // ---- positive controls and eligible claims ----------------------------------------------------

  test("positive controls: affirmative connection claims with negation words keep their status") {
    List(
      "Alice Smith and Bob Jones are not disconnected in 2015-2025.",
      "Alice Smith and Bob Jones are not only connected but frequent coauthors in 2015-2025."
    ).foreach { t =>
      assert(!ClaimVerification.statesAbsence(t), t)
      val (now, before) = both(t, List(found))
      assertEquals(before.status, Verified, s"$t ${show(before)}")
      assertEquals(now.status, before.status, s"$t ${show(now)}")
      assertEquals(now.reasons, before.reasons, t)
    }
  }

  test("item g: the correct absence claims of the fixtures stay verified where evidence supports") {
    List(
      "Alice Smith and Bob Jones are not connected within 6 hops in 2021-2023.",
      "Alice Smith and Bob Jones are not connected within 4 hops in 2021-2023.",
      "No coauthorship path between Alice Smith and Bob Jones exists within 6 hops in 2021-2023.",
      "In 2021-2023, no coauthorship path between Alice Smith and Bob Jones exists within depth 6 (the bounded search completed).",
      "No path between Alice Smith and Bob Jones exists within depth 6 in w2021-2023.",
      "Alice Smith is not connected to Bob Jones within 6 hops in 2021-2023."
    ).foreach { t =>
      val (now, before) = both(t, List(noneW1K))
      assertEquals(before.status, Verified, s"Phase 16: $t ${show(before)}")
      assertEquals(now.status, Verified, s"$t ${show(now)}")
    }
  }

  test("item b ii: a shared first name of the pair is not a third name") {
    val john = ResearcherRef(rid("F"), "John Smith")
    val white = ResearcherRef(rid("G"), "John White")
    val none = PathAbsentFact(john, bob, W1, W1W, 6, true, 6, src, 20, None)
    val whiteW1 =
      MetricFact(white, W1, W1W, CollaborationMetric.DistinctCoauthors, 2.0, None, None, src, 20)
    val (now, _) =
      both(
        "John Smith and Bob Jones are not connected within 6 hops in 2021-2023.",
        List(none, whiteW1)
      )
    assertEquals(now.status, Verified, show(now))
  }

  test("deterministic synthesis wording: path absence stays verified; edge absence is ambiguous") {
    // Synthesis.scala path=none template: verified, as before
    val pathText =
      s"In ${Fact.windowText(W1W)}, no coauthorship path between Alice Smith and Bob Jones exists within depth 6 (the bounded search completed)."
    val (p, pb) = both(pathText, List(noneW1K))
    assertEquals((pb.status, p.status), (Verified, Verified), show(p))
    // Synthesis.scala edge=none template: a negation beside the pair with no listed predicate
    // (item a, item f), so at most partially supported (a listed fall, never verified)
    val edgeNone = EdgeAbsentFact(alice, bob, W1, W1W, src, 21)
    val edgeText =
      s"In ${Fact.windowText(W1W)}, Alice Smith and Bob Jones have no direct coauthor edge."
    val (e, eb) = both(edgeText, List(edgeNone))
    assertEquals(eb.status, Verified, show(eb))
    assertEquals(e.status, PartiallySupported, show(e))
    assert(e.kept)
    assertEquals(e.reasons, List(Reasons.AbsenceAmbiguous), show(e))
  }

  // ---- Phase 17 repair round (repair-01-rag): P17-A-01, A-03, A-04, A-05 -----------------------

  test("P17-A-01: the reviewer's unlisted absence wordings against a found path are not verified") {
    // the reviewer's two failure inputs: now recognised (lack, distinct components) and
    // contradicted by the cited found path
    List(
      "Alice Smith and Bob Jones lack any coauthorship path in 2015-2025.",
      "Alice Smith and Bob Jones are in distinct components in 2015-2025."
    ).foreach { t =>
      val (now, before) = both(t, List(found))
      assertEquals(before.status, Verified, s"Phase 16: $t ${show(before)}")
      assertEquals(now.status, Conflicting, s"$t ${show(now)}")
      assert(now.reasons.contains(Reasons.HopsConflict), s"$t ${show(now)}")
    }
  }

  test("P17-A-01: the structural rule catches absence wordings that no list holds") {
    // none of these words is on any list; each claim names the pair, cites a found path between
    // them, and its names and window check: never verified, whatever the wording
    List(
      "Alice Smith and Bob Jones are devoid of any coauthorship path in 2015-2025.",
      "Alice Smith and Bob Jones are strangers in 2015-2025.",
      "Alice Smith and Bob Jones sit in disjoint parts of the graph in 2015-2025.",
      "Alice Smith and Bob Jones fail to have a path in 2015-2025."
    ).foreach { t =>
      val (now, before) = both(t, List(found))
      assertEquals(before.status, Verified, s"Phase 16: $t ${show(before)}")
      assertEquals(now.status, PartiallySupported, s"$t ${show(now)}")
      assert(now.reasons.contains(Reasons.ConnectionUnconfirmed), s"$t ${show(now)}")
    }
    // the rule also covers a claim that names a third researcher on the path
    val (three, threeBefore) =
      both("Alice Smith, Carol King and Bob Jones are strangers in 2015-2025.", List(found))
    assertEquals(threeBefore.status, Verified, show(threeBefore))
    assertNotEquals(three.status, Verified, show(three))
    // and a coauthor edge (one hop) connects the pair as a found path does
    val edge =
      EdgeFact(alice, bob, Full, FullW, "edge:v1:" + "a" * 32, 2.5, 3, 2016, 2022, Nil, src, 21)
    val (e, eb) = both("Alice Smith and Bob Jones are strangers in 2015-2025.", List(edge))
    assertEquals(eb.status, Verified, show(eb))
    assertEquals(e.status, PartiallySupported, show(e))
    assert(e.reasons.contains(Reasons.ConnectionUnconfirmed), show(e))
  }

  test("P17-A-01: the extended lists (lack, absent, unrelated, distinct components)") {
    List("lack", "lacks", "lacked", "lacking").foreach(n => assert(isNegation(n), n))
    assertEquals(kind("Alice Smith and Bob Jones lack any coauthorship path"), Absence)
    assertEquals(kind("a coauthorship path is absent"), Unclear)
    assertEquals(kind("Alice Smith and Bob Jones are unrelated"), Absence)
    assertEquals(kind("Alice Smith and Bob Jones are in distinct connected components"), Absence)
    assertEquals(kind("Alice Smith and Bob Jones are not in distinct components"), Connection)
  }

  test("P17-A-01 positive controls: affirmed connections and pair quantities stay verified") {
    val edge =
      EdgeFact(alice, bob, Full, FullW, "edge:v1:" + "a" * 32, 2.5, 3, 2016, 2022, Nil, src, 21)
    List(
      // the step 1 positive controls (negation words, affirmative)
      ("Alice Smith and Bob Jones are not disconnected in 2015-2025.", List(found)),
      (
        "Alice Smith and Bob Jones are not only connected but frequent coauthors in 2015-2025.",
        List(found)
      ),
      ("Alice Smith and Bob Jones are connected within 2 hops in 2015-2025.", List(found)),
      // Synthesis.scala path template
      (
        s"In ${Fact.windowText(FullW)}, Alice Smith and Bob Jones are connected by a shortest coauthorship path of 2 hops: Alice Smith -> Carol King -> Bob Jones.",
        List(found)
      ),
      // Synthesis.scala edge template: no predicate, closed-vocabulary words only
      (
        s"In ${Fact.windowText(FullW)}, Alice Smith and Bob Jones share 3 joint publications from 2016 to 2022, with edge weight 2.5.",
        List(edge)
      )
    ).foreach { case (t, facts) =>
      val (now, before) = both(t, facts)
      assertEquals(before.status, Verified, s"Phase 16: $t ${show(before)}")
      assertEquals(now.status, Verified, s"$t ${show(now)}")
      assertEquals(now.reasons, before.reasons, t)
    }
  }

  test("P17-A-03: affirmative claims with a negated side clause fall to partially supported") {
    // both are true claims (a 2-hop path, no direct edge); the rule requires the fall (item 1a
    // "both kinds of predicate in one claim", item 1f), and these tests pin it
    val sideNegations = List(
      // a negation beside the pair with no listed predicate ("coauthor" in the singular)
      "Alice Smith is not a direct coauthor of Bob Jones; they are connected within 2 hops in 2015-2025.",
      // an absence clause that names nobody: unclear
      "Alice Smith and Bob Jones are connected within 2 hops in 2015-2025, although they are not direct coauthors."
    )
    sideNegations.foreach { t =>
      val (now, before) = both(t, List(found))
      assertEquals(before.status, Verified, s"Phase 16: $t ${show(before)}")
      assertEquals(now.status, PartiallySupported, s"$t ${show(now)}")
      assert(now.kept, t)
      assertEquals(now.reasons, List(Reasons.AbsenceAmbiguous), s"$t ${show(now)}")
    }
  }

  test("P17-A-04: a possible absence contradicted by its own cited path is not kept") {
    // the clause "are disconnected" names nobody (unclear), so it is not an absence span; the
    // found path inside the claim's window contradicts it
    List(
      "Alice Smith and Bob Jones, in 2015-2025, are disconnected.",
      "Alice Smith and Bob Jones: disconnected in 2015-2025."
    ).foreach { t =>
      val (now, before) = both(t, List(found))
      assertEquals(before.status, Verified, s"Phase 16: $t ${show(before)}")
      assertEquals(now.status, Conflicting, s"$t ${show(now)}")
      assert(!now.kept, t)
      assert(now.reasons.contains(Reasons.HopsConflict), s"$t ${show(now)}")
    }
    // the same wording against a path=none search only (no found path) stays at most partial
    val (n, _) = both("Alice Smith and Bob Jones, in 2021-2023, are disconnected.", List(noneW1K))
    assertEquals(n.status, PartiallySupported, show(n))
  }

  test("P17-A-05: the reasons name their cause; every new reason is fixed text in Reasons.all") {
    val added = List(
      Reasons.AbsenceAmbiguous,
      Reasons.AbsenceThirdName,
      Reasons.AbsenceUnnamed,
      Reasons.AbsenceTimeQualifier,
      Reasons.AbsenceFoundPath,
      Reasons.ConnectionUnconfirmed
    )
    added.foreach { r =>
      assert(Reasons.all.contains(r), r)
      assert(!r.exists(_.isDigit), r)
    }
    assertEquals(Reasons.all.distinct.size, Reasons.all.size)
    // a cause-specific reason never stands in for "no cited search covered the pair or window"
    val t = "Alice Smith is not connected to Bob Jones or to King within 6 hops in 2021-2023."
    val (now, _) = both(t, List(noneW1K))
    assert(now.reasons.contains(Reasons.AbsenceThirdName), show(now))
  }
}
