package org.scholarchain.rag.runtime

import org.scholarchain.core._

/** Phase 11 grounding repairs, at the unit level:
  *   - L-1 / L-4: the number formats the validator and the verifier accept (the diagnosis table of
  *     the report, as assertions of the decided behaviour), failure classes, answer-claims v3
  *     dropping and counting a bound number that is not in its claim text;
  *   - T-01, T-10: an identifier or a researcher name the cited evidence lacks is never kept;
  *   - T-07: reasons are fixed text (no model-made number, identifier or name);
  *   - C-04: a number must be a value of the cited item of the researcher and metric the text ties
  *     it to;
  *   - L-2, L-3: years by role (live q08, rebuilt offline from attempt-06 `q-08.json`).
  */
class GroundingSuite extends munit.FunSuite {
  import RuntimeFixtures._
  import VerificationStatus._
  import ClaimVerification.Reasons

  private val ctx = RuntimeContext.from(config, sparkRun, Set(Full)).toOption.get
  private val queryRun = RunId.of("rag.query", "test", "q", "c").toOption.get
  private val windowsByLabel = windows.toMap
  private val src = SourceRef("ResearcherMetricCache:x", sparkRun, "metric-policy/1", "snap")
  private val alice = ResearcherRef(rid("A"), "Alice Smith")
  private val bob = ResearcherRef(rid("B"), "Bob Jones")
  private val carol = ResearcherRef(rid("C"), "Carol King")

  private def metric(r: ResearcherRef, m: CollaborationMetric, v: Double) =
    MetricFact(r, Full, FullW, m, v, None, None, src, 30)
  private val pAlice = metric(alice, CollaborationMetric.PublicationCount, 20)
  private val cAlice = metric(alice, CollaborationMetric.DistinctCoauthors, 57)
  private val cBob = metric(bob, CollaborationMetric.DistinctCoauthors, 12)
  private val cCarol = metric(carol, CollaborationMetric.DistinctCoauthors, 7)
  private val aAlice = metric(alice, CollaborationMetric.AverageCoauthorsPerPaper, 4.5714)
  private val big = metric(bob, CollaborationMetric.WeightedDegree, 1234)
  // another metric than cCarol: a ranked and an unranked item of one metric share an evidence ID
  private val one = MetricFact(
    carol,
    Full,
    FullW,
    CollaborationMetric.Coreness,
    3,
    Some(1),
    None,
    src,
    41
  )
  private val idFact = IdentityFact(
    carol,
    "Carol King",
    "Carol King",
    "accepted",
    Some(0.99),
    resolved = true,
    Some(9),
    Some(7),
    Nil,
    Full,
    src,
    10
  )

  private val built = new EvidenceBuilder(ctx)
    .build(queryRun, List(pAlice, cAlice, cBob, cCarol, aAlice, big, one, idFact))
    .fold(e => fail(e), identity)
  private val items = built.items.map(i => i.evidenceId -> i).toMap
  private def id(f: Fact): String = built.idOf(f).value
  private def claim(text: String, cites: List[Fact], nums: List[DraftNumber] = Nil) =
    DraftClaim("claim-1", text, cites.map(id), nums, None)
  private def verify(c: DraftClaim): VerifiedClaim =
    VerifierOracle.verifyClaim(c, items, windowsByLabel)

  // ---- L-1 / L-4: one table, both places --------------------------------------------------------

  private val rid347 = "researcher:v1:00e8ad347ecd63cf1834a0df916a41b3"

  /** (stated text, value, accepted) */
  private val table: List[(String, Double, Boolean)] = List(
    ("1,234", 1234, true),
    ("12,345,678", 12345678, true),
    ("1 234", 1234, false),
    ("95%", 0.95, false),
    ("4.6", 4.5714, false),
    ("4.57", 4.5714, true),
    ("4.5714", 4.5714, true),
    ("5", 4.5714, false),
    ("one", 1, false),
    ("a single", 1, false),
    ("first", 1, false),
    ("#1", 1, true),
    ("2015-2025", 2015, true),
    ("2015–2025", 2015, true),
    (s"($rid347)", 347, false)
  )

  test("L-1/L-4 format table: appearsInText accepts exactly the decided formats") {
    table.foreach { case (lit, v, ok) =>
      assertEquals(AnswerClaimsSchema.appearsInText(v, s"Alice Smith has $lit things."), ok, lit)
      assertEquals(NumberFormats.appearsInText(v, s"Alice Smith has $lit things."), ok, lit)
    }
    // L-4: digits inside a stable ID, a claim ID or a dblp key never count
    assert(!NumberFormats.appearsInText(3, "see claim-3"))
    assert(!NumberFormats.appearsInText(16, "conf/acl/A16 again"))
    assert(!NumberFormats.appearsInText(3, "Wei Wang 0003 again"))
  }

  test("L-1: the verifier agrees with the validator on the same formats") {
    // a value the text states in an accepted format verifies; any other format does not
    assertEquals(
      verify(claim("In 2015-2025, Bob Jones has weighted degree 1,234.", List(big))).status,
      Verified
    )
    assertNotEquals(
      verify(claim("In 2015-2025, Bob Jones has weighted degree 1 234.", List(big))).status,
      Verified
    )
    assertEquals(
      verify(
        claim("In 2015-2025, Alice Smith has 4.5714 average coauthors per paper.", List(aAlice))
      ).status,
      Verified
    )
    assertEquals(
      verify(
        claim("In 2015-2025, Alice Smith has 4.57 average coauthors per paper.", List(aAlice))
      ).status,
      Verified
    )
    List("4.6", "5").foreach { lit =>
      assertNotEquals(
        verify(
          claim(s"In 2015-2025, Alice Smith has $lit average coauthors per paper.", List(aAlice))
        ).status,
        Verified,
        lit
      )
    }
    assertNotEquals(
      verify(
        claim("In 2015-2025, Alice Smith has 95% of 57 distinct coauthors.", List(cAlice))
      ).status,
      Verified
    )
    // "#1" is the rank literal 1
    assertEquals(
      verify(claim("Carol King is #1 by coreness in 2015-2025.", List(one))).status,
      Verified
    )
  }

  test("L-1: failure classes are computed in code from a fixed vocabulary") {
    def cls(v: Double, t: String) = NumberFormats.failureClass(v, t)
    assertEquals(cls(1234, "has 1 234 coauthors"), NumberFormats.Separator)
    assertEquals(cls(0.95, "has 95% of them"), NumberFormats.Percent)
    assertEquals(cls(0.95, "has 95 percent of them"), NumberFormats.Percent)
    assertEquals(cls(4.5714, "has 4.6 per paper"), NumberFormats.TooFewDecimals)
    assertEquals(cls(4.5714, "has 5 per paper"), NumberFormats.TooFewDecimals)
    assertEquals(cls(1, "has a single coauthor edge"), NumberFormats.Words)
    assertEquals(cls(1, "ranks first"), NumberFormats.Words)
    assertEquals(cls(3, "has three coauthors"), NumberFormats.Words)
    assertEquals(cls(347, s"see $rid347"), NumberFormats.IdDigits)
    assertEquals(cls(42, "has many coauthors"), NumberFormats.Absent)
    table.foreach { case (lit, v, ok) =>
      if (!ok) assert(NumberFormats.Classes.contains(cls(v, s"has $lit things")), lit)
    }
    assertEquals(
      NumberFormats.tally(List("words", "absent", "words")),
      Map("words" -> 2L, "absent" -> 1L)
    )
  }

  private val ev = "evidence:v1:" + "a" * 32
  private def draftJson(text: String, values: List[Double]) = ujson.Obj(
    "summary" -> "s",
    "claims" -> ujson.Arr(
      ujson.Obj(
        "claimId" -> "claim-1",
        "text" -> text,
        "evidenceIds" -> ujson.Arr(ev),
        "numbers" -> ujson.Arr.from(
          values.map(v => ujson.Obj("value" -> v, "evidenceId" -> ev, "field" -> "value"))
        ),
        "uncertainty" -> ujson.Null
      )
    ),
    "missingEvidence" -> ujson.Arr()
  )

  test("L-1: answer-claims v3 drops and counts an unmatched bound number; v2 still rejects") {
    val text = "Carol King has a single coauthor edge and 1,234 occurrences."
    val v3 = AnswerClaimsSchema.checkJson(draftJson(text, List(1, 1234)), AnswerClaimsSpec.V3)
    val d = v3.result.fold(e => fail(e), identity)
    assertEquals(d.claims.head.numbers.map(_.value), List(1234.0))
    assertEquals(v3.droppedNumbers, List(NumberFormats.Words))
    assertEquals(v3.numberErrors, Nil)
    val v2 = AnswerClaimsSchema.checkJson(draftJson(text, List(1, 1234)), AnswerClaimsSpec.V2)
    assert(v2.result.isLeft)
    assertEquals(v2.numberErrors, List(NumberFormats.Words))
    // the v2 error names the position, never the value
    assert(!v2.result.left.toOption.get.contains("1234"), v2.result.toString)
    // both accept 1,234 for 1234 now (it used to be rejected)
    assert(
      AnswerClaimsSchema.checkJson(draftJson(text, List(1234)), AnswerClaimsSpec.V2).result.isRight
    )
  }

  // ---- T-01, T-10, T-07 ------------------------------------------------------------------------

  private val fakeResearcher = "researcher:v1:deadbeefdeadbeefdeadbeefdeadbeef"
  private val fakeEvidence = "evidence:v1:deadbeefdeadbeefdeadbeefdeadbeef"

  test("T-01: an identifier the cited evidence lacks makes a claim unsupported, never kept") {
    val text =
      s"Carol King (${carol.id.value}) is the researcher matched for this question (identity outcome accepted, confidence 0.9900); see $fakeResearcher and $fakeEvidence."
    val v = verify(claim(text, List(idFact)))
    assertEquals(v.status, Unsupported, v.reasons.toString)
    assert(!v.kept)
    assert(v.reasons.contains(Reasons.IdNotCited) && v.reasons.contains(Reasons.EvidenceIdNotCited))
    // the same claim without the fabricated identifiers verifies
    assertEquals(
      verify(claim(text.takeWhile(_ != ';') + ".", List(idFact))).status,
      Verified
    )
  }

  test("T-10: a researcher name the cited evidence lacks makes a claim unsupported") {
    val v = verify(claim("Carol King collaborated with Igor Petrov.", List(cCarol)))
    assertEquals(v.status, Unsupported, v.reasons.toString)
    assertEquals(v.reasons, List(Reasons.NameNotCited))
    assert(!v.reasons.exists(_.contains("Igor")))
    // names that are in the cited items still verify
    assertEquals(
      verify(claim("Carol King has 7 distinct coauthors.", List(cCarol))).status,
      Verified
    )
  }

  test("T-07: every reason is fixed text; no model number, identifier or name is repeated") {
    val claims = List(
      claim("This cites nothing real.", Nil).copy(evidenceIds = List(fakeEvidence)),
      claim(
        s"In 2015-2025, Alice Smith has 99 distinct coauthors; see $fakeResearcher.",
        List(cAlice)
      ),
      claim("In 1999-2003, Mallory Fabricated has 57 distinct coauthors.", List(cAlice)),
      claim("Alice Smith reaches Bob Jones in 9 hops.", List(cAlice)),
      claim("\"A Made Up Title\" is by Alice Smith.", List(cAlice))
    )
    claims.foreach { c =>
      val v = verify(c)
      assert(v.reasons.forall(Reasons.all.contains), v.reasons.toString)
      List("99", "9 hops", "1999", "Mallory", "deadbeef", "Made Up").foreach(s =>
        assert(!v.reasons.exists(_.contains(s)), s"$s in ${v.reasons}")
      )
    }
  }

  // ---- C-04 ------------------------------------------------------------------------------------

  test("C-04: a number of another researcher's cited item is a conflict") {
    val wrong =
      verify(claim("Bob Jones has 57 distinct coauthors in 2015-2025.", List(cAlice, cBob)))
    assertEquals(wrong.status, Conflicting, wrong.reasons.toString)
    assertEquals(wrong.reasons, List(Reasons.NumberMisattributed))
    // bound to the item that holds 57 (Alice's): still a conflict, the text names Bob
    val bound = verify(
      claim(
        "Bob Jones has 57 distinct coauthors in 2015-2025.",
        List(cAlice, cBob),
        List(DraftNumber(57, id(cAlice), "value"))
      )
    )
    assertEquals(bound.status, Conflicting, bound.reasons.toString)
    assertEquals(
      verify(claim("Bob Jones has 12 distinct coauthors in 2015-2025.", List(cAlice, cBob))).status,
      Verified
    )
    // two researchers in one claim: each number is tied to the researcher of its clause
    assertEquals(
      verify(
        claim(
          "In 2015-2025, Alice Smith has 57 distinct coauthors, and Bob Jones has 12.",
          List(cAlice, cBob)
        )
      ).status,
      Verified
    )
    assertEquals(
      verify(
        claim(
          "In 2015-2025, Alice Smith has 12 distinct coauthors, and Bob Jones has 57.",
          List(cAlice, cBob)
        )
      ).status,
      Conflicting
    )
  }

  test("C-04: a number of another metric's cited item is a conflict") {
    val wrong = verify(claim("Alice Smith has 57 publications in 2015-2025.", List(pAlice, cAlice)))
    assertEquals(wrong.status, Conflicting, wrong.reasons.toString)
    val bound = verify(
      claim(
        "Alice Smith has 57 publications in 2015-2025.",
        List(pAlice, cAlice),
        List(DraftNumber(57, id(cAlice), "value"))
      )
    )
    assertEquals(bound.status, Conflicting, bound.reasons.toString)
    assertEquals(
      verify(
        claim(
          "In 2015-2025, Alice Smith has 20 publications (P), 57 distinct coauthors (C).",
          List(pAlice, cAlice)
        )
      ).status,
      Verified
    )
    assertEquals(
      verify(
        claim(
          "Alice Smith has 20 publications and 57 distinct coauthors in 2015-2025.",
          List(pAlice, cAlice)
        )
      ).status,
      Verified
    )
  }

  // ---- L-2, L-3: q08 of attempt-06, rebuilt offline --------------------------------------------

  private val elie =
    ResearcherRef(ResearcherId("researcher:v1:00e8ad347ecd63cf1834a0df916a41b3"), "Elie Bakouch")
  private val loubna =
    ResearcherRef(
      ResearcherId("researcher:v1:d00807037bc74924b35ec1e70e78f261"),
      "Loubna Ben Allal"
    )
  private val guilherme =
    ResearcherRef(
      ResearcherId("researcher:v1:10247694dc557bd3c943ec86b7f8d0df"),
      "Guilherme Penedo"
    )
  private val q08Edge = EdgeFact(
    elie,
    loubna,
    Full,
    FullW,
    "edge:v1:5c0725f7ad5223a1502a13c7c6ba68f8",
    2,
    2,
    2024,
    2025,
    List("work:v1:ab6d282ab7826e7d7a3d1f23b834aee5", "work:v1:d3d8eb7f3b99e77d234888909a8a5b55"),
    src,
    50
  )
  private val earlierEdge = EdgeFact(
    elie,
    guilherme,
    Full,
    FullW,
    "edge:v1:ae7f60626b7fd040b6345671b02057d7",
    1,
    1,
    2022,
    2025,
    Nil,
    src,
    51
  )
  private val q08Identity = IdentityFact(
    elie,
    "Elie Bakouch",
    "Elie Bakouch",
    "accepted",
    Some(0.95),
    resolved = true,
    Some(2),
    Some(30),
    List(loubna, guilherme),
    Full,
    src,
    10
  )
  private val q08 = new EvidenceBuilder(ctx)
    .build(queryRun, List(q08Identity, q08Edge, earlierEdge))
    .fold(e => fail(e), identity)
  private val q08Items = q08.items.map(i => i.evidenceId -> i).toMap
  private def q08Claim(text: String, f: Fact*) =
    DraftClaim("claim-2", text, f.toList.map(x => q08.idOf(x).value), Nil, None)

  test("L-2: q08 claim-2 with a planted window year is not verified; the original stays verified") {
    val original =
      "Loubna Ben Allal is a coauthor with edge weight 2 and 2 joint publications, active from 2024 to 2025."
    val planted =
      "Loubna Ben Allal is a coauthor with edge weight 2 and 2 joint publications, active from 2015 to 2025."
    val ok = VerifierOracle.verifyClaim(q08Claim(original, q08Edge), q08Items, windowsByLabel)
    assertEquals(ok.status, Verified, ok.reasons.toString)
    val bad = VerifierOracle.verifyClaim(q08Claim(planted, q08Edge), q08Items, windowsByLabel)
    assertNotEquals(bad.status, Verified)
    assertEquals(bad.reasons, List(Reasons.YearConflict))
    // the template claims (a window range plus edge years) stay verified
    List(
      "In 2015-2025, Elie Bakouch and Loubna Ben Allal share 2 joint publications from 2024 to 2025, with edge weight 2." -> q08Edge,
      "In 2015-2025, Elie Bakouch and Guilherme Penedo share 1 joint publications from 2022 to 2025, with edge weight 1." -> earlierEdge,
      "In 2015–2025, Elie Bakouch and Loubna Ben Allal share 2 joint publications since 2024." -> q08Edge
    ).foreach { case (t, e) =>
      val v = VerifierOracle.verifyClaim(q08Claim(t, e), q08Items, windowsByLabel)
      assertEquals(v.status, Verified, s"$t: ${v.reasons}")
    }
    // a window year alone never supports a single year of an edge claim
    assertNotEquals(
      ClaimVerification
        .verifyClaim(
          q08Claim("Elie Bakouch and Loubna Ben Allal coauthored in 2015.", q08Edge),
          q08Items,
          windowsByLabel
        )
        .status,
      Verified
    )
  }

  test(
    "L-2: the planted year steps down out of the cited years and replaces the matched year only"
  ) {
    assertEquals(
      BenchmarkEvaluation.plantedYear(2024, Set(2015, 2025, 2024)),
      2014 // 2024 - 9 = 2015 is a cited window year, so it steps down once
    )
    assertEquals(BenchmarkEvaluation.plantedYear(2025, Set(2015, 2016, 2025)), 2014)
    assertEquals(BenchmarkEvaluation.plantedYear(2020, Set(2015, 2025)), 2011)
    val idWithYear = "researcher:v1:" + "0" * 12 + "2015" + "0" * 16
    val text = s"Elie Bakouch ($idWithYear) coauthored in 2015."
    val m = "\\b((?:19|20)[0-9]{2})\\b".r.findFirstMatchIn(text).get
    val out = BenchmarkEvaluation.replaceYear(text, m, 2006)
    assertEquals(out, s"Elie Bakouch ($idWithYear) coauthored in 2006.")
  }

  test("L-3: a window range is accepted for an identity-only claim through its context window") {
    val t = "Elie Bakouch has 30 distinct coauthors between 2015 and 2025."
    val v = VerifierOracle.verifyClaim(q08Claim(t, q08Identity), q08Items, windowsByLabel)
    assertEquals(v.status, Verified, v.reasons.toString)
    val dash = "Elie Bakouch has 30 distinct coauthors in 2015-2025."
    assertEquals(
      VerifierOracle.verifyClaim(q08Claim(dash, q08Identity), q08Items, windowsByLabel).status,
      Verified
    )
    // another range is still a conflict
    val other = "Elie Bakouch has 30 distinct coauthors between 2016 and 2025."
    assertEquals(
      VerifierOracle.verifyClaim(q08Claim(other, q08Identity), q08Items, windowsByLabel).status,
      Conflicting
    )
  }
}
