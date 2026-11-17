package org.scholarchain.rag.runtime

import org.scholarchain.core._
import org.scholarchain.rag.retrieval.SemanticContextEntry

/** The deterministic verifier on a hand-built bundle: fabricated identifiers, planted wrong
  * numbers, years and hop counts, semantic evidence cited for numbers, and the excluded digits.
  */
class ClaimVerificationSuite extends munit.FunSuite {
  import RuntimeFixtures._
  import VerificationStatus._

  private val ctx = RuntimeContext.from(config, sparkRun, Set(Full)).toOption.get
  private val queryRun = RunId.of("rag.query", "test", "q", "c").toOption.get
  private val src = SourceRef(
    "ResearcherMetricCache:researcher#x/window#full",
    sparkRun,
    "metric-policy/1",
    "dblp-2026-11-01"
  )
  private val alice = ResearcherRef(rA, "Alice Smith")
  private val bob = ResearcherRef(rB, "Bob Jones")
  private val carol = ResearcherRef(rC, "Carol King")
  private val wei3 = ResearcherRef(rE, "Wei Wang 0003")

  private val cAlice =
    MetricFact(alice, Full, FullW, CollaborationMetric.DistinctCoauthors, 37, None, None, src, 30)
  private val aAlice = MetricFact(
    alice,
    Full,
    FullW,
    CollaborationMetric.AverageCoauthorsPerPaper,
    3.4285714,
    None,
    None,
    src,
    30
  )
  private val cWei =
    MetricFact(wei3, Full, FullW, CollaborationMetric.DistinctCoauthors, 12, None, None, src, 30)
  private val edge =
    EdgeFact(alice, bob, Full, FullW, "edge:v1:" + "a" * 32, 2.5, 3, 2016, 2022, Nil, src, 21)
  private val path = PathFact(
    List(alice, bob, carol),
    Full,
    FullW,
    "graph-path-search",
    None,
    truncated = false,
    src,
    20
  )
  private val work = WorkFact(
    WorkId.fromDblpKey("conf/acl/A16"),
    "Parsing Legal Contracts",
    2016,
    "conf/acl",
    4,
    "conf/acl/A16",
    Some(alice),
    src,
    60
  )
  private val sem = SemanticFact(
    SemanticContextEntry(
      "rp:full-2015-2025:" + rA.value,
      "researcher-profile-v1",
      0.42,
      SemanticDocumentType.ResearcherProfile,
      List(rA.value),
      Some(2015),
      Some(2025),
      "s3://example-artifact-bucket/semantic-context/x",
      "sha256:" + "b" * 64,
      "fake/fake-lexical-hash-v1/1024/document/emb-v1"
    ),
    Some(rA),
    None,
    90
  )

  private val built = new EvidenceBuilder(ctx)
    .build(queryRun, List(cAlice, aAlice, cWei, edge, path, work, sem))
    .fold(e => fail(e), identity)
  private def id(f: Fact): String = built.idOf(f).value
  private def claim(
      text: String,
      cites: List[Fact],
      numbers: List[(Double, Fact, String)] = Nil,
      extraIds: List[String] = Nil
  ): DraftClaim =
    DraftClaim(
      "claim-1",
      text,
      cites.map(id) ++ extraIds,
      numbers.map { case (v, f, k) => DraftNumber(v, id(f), k) },
      None
    )
  private def status(c: DraftClaim): VerifiedClaim =
    ClaimVerification.verify(AnswerDraft("s", List(c), Nil), built.bundle).head

  test("m-1: verified needs a passed check; nothing checkable is partially supported") {
    val qualitative =
      status(claim("this researcher is a key broker between all communities.", List(cAlice)))
    assertEquals(qualitative.status, PartiallySupported, qualitative.reasons.toString)
    assertEquals(qualitative.reasons, List(ClaimVerification.NothingCheckable))
    assertEquals(qualitative.checked, 0)
    // a descriptive semantic claim with nothing checkable: partially supported too
    val descriptive =
      status(claim("profiles similar to the question were retrieved as context only.", List(sem)))
    assertEquals(descriptive.status, PartiallySupported, descriptive.reasons.toString)
    // one passed check (a name) is enough for verified
    assertEquals(
      status(claim("Alice Smith is a key broker between all communities.", List(cAlice))).status,
      Verified
    )
    // the semantic-only rule still wins for graph claims
    assertEquals(
      status(claim("profiles show many coauthors.", List(sem))).status,
      Unsupported
    )
  }

  test("m-1: an unbound number without cited graph items is not a conflict but unsupported") {
    val v = status(claim("profiles mention 38 papers.", List(sem)))
    assertEquals(v.status, Unsupported, v.reasons.toString)
  }

  test("a correct claim is verified") {
    val v = status(
      claim(
        "In 2015-2025, Alice Smith has 37 distinct coauthors (C).",
        List(cAlice),
        List((37, cAlice, "value"))
      )
    )
    assertEquals(v.status, Verified, v.reasons.toString)
  }

  test("planted wrong number: conflicting when bound, not verified when only in the text") {
    assertEquals(
      status(
        claim(
          "In 2015-2025, Alice Smith has 38 distinct coauthors (C).",
          List(cAlice),
          List((38, cAlice, "value"))
        )
      ).status,
      Conflicting
    )
    // m-1: an unbound non-year number that matches no value of the cited graph items conflicts
    val unbound =
      status(claim("In 2015-2025, Alice Smith has 38 distinct coauthors (C).", List(cAlice)))
    assertEquals(unbound.status, Conflicting, unbound.reasons.toString)
    // Phase 11: reasons are fixed text and never repeat the model's number
    assertEquals(unbound.reasons, List(ClaimVerification.Reasons.NumberConflict))
    // rounding follows the stated precision: 3.43 and 3.4286 match 3.4285714; 3 and 3.4 do not
    assertEquals(
      status(
        claim("In 2015-2025, Alice Smith has 3.43 average coauthors per paper (A).", List(aAlice))
      ).status,
      Verified
    )
    assertEquals(
      status(
        claim("In 2015-2025, Alice Smith has 3.4286 average coauthors per paper (A).", List(aAlice))
      ).status,
      Verified
    )
    assertNotEquals(
      status(
        claim("In 2015-2025, Alice Smith has 3 average coauthors per paper (A).", List(aAlice))
      ).status,
      Verified
    )
    assertNotEquals(
      status(
        claim("In 2015-2025, Alice Smith has 3.4 average coauthors per paper (A).", List(aAlice))
      ).status,
      Verified
    )
  }

  test("planted wrong window year: conflicting") {
    val v = status(claim("In 2018-2020, Alice Smith has 37 distinct coauthors (C).", List(cAlice)))
    assertEquals(v.status, Conflicting)
    // L-2: a range is checked as a window; the reason is fixed text
    assertEquals(v.reasons, List(ClaimVerification.Reasons.RangeConflict))
    // edge years are allowed years for a cited edge
    assertEquals(
      status(
        claim(
          "In 2015-2025, Alice Smith and Bob Jones share 3 joint publications from 2016 to 2022, with edge weight 2.5.",
          List(edge)
        )
      ).status,
      Verified
    )
  }

  test("planted wrong hop count: conflicting") {
    assertEquals(
      status(
        claim(
          "Alice Smith and Carol King are connected by a path of 2 hops in 2015-2025.",
          List(path)
        )
      ).status,
      Verified
    )
    val v = status(
      claim(
        "Alice Smith and Carol King are connected by a path of 3 hops in 2015-2025.",
        List(path)
      )
    )
    assertEquals(v.status, Conflicting)
    assert(v.reasons.contains(ClaimVerification.Reasons.HopsConflict), v.reasons.toString)
  }

  test("semantic evidence cited for a number is unsupported; descriptive use is fine") {
    assertEquals(
      status(claim("Alice Smith has 37 distinct coauthors.", List(sem))).status,
      Unsupported
    )
    assertEquals(
      status(
        claim("Alice Smith has 37 distinct coauthors.", List(sem), List((37, sem, "value")))
      ).status,
      Unsupported
    )
    assertEquals(
      status(claim("Alice Smith and Bob Jones are coauthors.", List(sem))).status,
      Unsupported
    )
    // descriptive use is kept; with nothing checkable it is partially supported (m-1)
    assertEquals(
      status(
        claim("Similar profiles were retrieved as descriptive context only.", List(sem))
      ).status,
      PartiallySupported
    )
    // a number bound to semantic context alongside graph evidence is not covered
    assertEquals(
      status(
        claim(
          "In 2015-2025, Alice Smith has 37 distinct coauthors.",
          List(cAlice, sem),
          List((37, sem, "value"))
        )
      ).status,
      PartiallySupported
    )
  }

  test(
    "fabricated identifiers: unknown evidence IDs and researcher IDs outside cited evidence are never verified"
  ) {
    val ghost = "evidence:v1:" + "0" * 32
    assertEquals(
      status(claim("Alice Smith has 37 distinct coauthors.", Nil, extraIds = List(ghost))).status,
      Unsupported
    )
    assertEquals(
      status(
        claim(
          "In 2015-2025, Alice Smith has 37 distinct coauthors.",
          List(cAlice),
          extraIds = List(ghost)
        )
      ).status,
      PartiallySupported
    )
    val other = ResearcherId.fromAnchorMention(MentionId.of(WorkId.fromDblpKey("x/y/Z"), 0))
    val v = status(
      claim(s"In 2015-2025, Alice Smith (${other.value}) has 37 distinct coauthors.", List(cAlice))
    )
    // T-01: an identifier the cited evidence lacks makes the claim unsupported (never kept), and
    // the reason never repeats the identifier
    assertEquals(v.status, Unsupported, v.reasons.toString)
    assert(v.reasons.contains(ClaimVerification.Reasons.IdNotCited), v.reasons.toString)
    assert(!v.reasons.exists(_.contains(other.value)), v.reasons.toString)
    // a researcher ID that is in the bundle but not in the cited item is not covered either
    assertNotEquals(
      status(
        claim(
          s"In 2015-2025, Alice Smith has 37 distinct coauthors, like ${rB.value}.",
          List(cAlice)
        )
      ).status,
      Verified
    )
  }

  test("names must belong to cited evidence") {
    assertNotEquals(
      status(claim("In 2015-2025, Bob Jones has 37 distinct coauthors.", List(cAlice))).status,
      Verified
    )
    assertNotEquals(
      status(
        claim("In 2015-2025, Mallory Fabricated has 37 distinct coauthors.", List(cAlice))
      ).status,
      Verified
    )
  }

  test("digits in homonym suffixes, venue names, keys and quoted titles are not numbers") {
    assertEquals(
      status(claim("In 2015-2025, Wei Wang 0003 has 12 distinct coauthors.", List(cWei))).status,
      Verified
    )
    assertEquals(
      status(
        claim(
          "\"Parsing Legal Contracts\" (2016, conf/acl) is a paper of Alice Smith with team size 4.",
          List(work, cAlice)
        )
      ).status,
      Verified
    )
    assertEquals(
      status(
        claim("\"Parsing Legal Contracts\" appeared at ACL (1) with team size 4.", List(work))
      ).status,
      Verified
    )
    assertNotEquals(
      status(claim("\"Parsing Illegal Contracts\" has team size 4.", List(work))).status,
      Verified
    )
  }

  test("R-2: a ratio, 'Name (n)' or a non-key slash token never hides a wrong number") {
    // (a) the review's exact input: 99/100 is not a dblp key, so both numbers are checked
    val a = status(claim("In 2015-2025, Alice Smith has 99/100 distinct coauthors.", List(cAlice)))
    assertNotEquals(a.status, Verified, a.reasons.toString)
    assert(a.reasons.contains(ClaimVerification.Reasons.NumberConflict), a.reasons.toString)
    // (b) the review's exact input: "Smith" is not the venue of a cited work
    val b =
      status(claim("In 2015-2025, the distinct coauthors of Alice Smith (99).", List(cAlice)))
    assertNotEquals(b.status, Verified, b.reasons.toString)
    assert(b.reasons.contains(ClaimVerification.Reasons.NumberConflict), b.reasons.toString)
    // with the right value in place of 99 the same text verifies, so 99 itself was checked
    assertEquals(
      status(
        claim("In 2015-2025, the distinct coauthors of Alice Smith (37).", List(cAlice))
      ).status,
      Verified
    )
    // a venue "(n)" counts only for the venue of a cited work (this work is conf/acl, not KDD)
    val c = status(
      claim("\"Parsing Legal Contracts\" appeared at KDD (7) with team size 4.", List(work))
    )
    assertNotEquals(c.status, Verified, c.reasons.toString)
    // a slash token that is neither a cited key nor dblp-key shaped is checked
    val d = status(
      claim("In 2015-2025, Alice Smith has 37 distinct coauthors (see conf/99).", List(cAlice))
    )
    assertNotEquals(d.status, Verified, d.reasons.toString)
    // a real dblp key keeps its digits out of the check, cited or not
    assertEquals(
      status(
        claim(
          "In 2015-2025, Alice Smith has 37 distinct coauthors; see journals/corr/abs-2101-00001.",
          List(cAlice)
        )
      ).status,
      Verified
    )
  }

  test("verifier is deterministic") {
    val c = claim("In 2018-2020, Alice Smith has 38 distinct coauthors.", List(cAlice))
    assertEquals(status(c), status(c))
  }
}
