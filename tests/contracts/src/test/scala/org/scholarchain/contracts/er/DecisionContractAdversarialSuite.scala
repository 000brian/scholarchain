package org.scholarchain.contracts.er

import org.scholarchain.core.*

/** Adversarial attacks on the core MatchDecision v2 and LlmAdjudication v1 contracts (Validator),
  * through the public `Contract` API. Each mutation must be rejected; the valid baselines must
  * pass, so a rejection is caused by the mutation alone.
  */
class DecisionContractAdversarialSuite extends munit.FunSuite {

  private val run = RunId.of("test-engineer.contract", "t", "fp", "cfg").toOption.get
  private val prov = Provenance(
    1,
    SourceType.Pipeline,
    "scholarchain-er",
    "dblp-2026-11-01",
    List("conf/sigir/SynthContractA"),
    run,
    "er.resolve-researchers",
    "er-v1",
    None
  )
  private val m1 = ErSynth.mid("conf/sigir/SynthContractA", 0)
  private val m2 = ErSynth.mid("conf/sigir/SynthContractB", 1)
  private val (l, r) = if (CodePointOrder.lessThan(m1, m2)) (m1, m2) else (m2, m1)
  private val cid =
    MatchCandidateId
      .of(MatchSubjectKind.MentionPair, l, r, "initials-surname", "s synth")
      .toOption
      .get
  private val researcher = ResearcherId.fromAnchorMention(MentionId(l)).value

  private val pair = MatchDecision(
    2,
    MatchSubjectKind.MentionPair,
    l,
    r,
    Some(cid),
    MatchOutcome.Accepted,
    MatchPath.Probabilistic,
    0.95,
    List(MatchFeature("a_feature", 1.0), MatchFeature("b_feature", 0.0)),
    List("score.accept"),
    Nil,
    None,
    None,
    "er-v1",
    "2026-11-14T00:00:00Z",
    prov
  )
  private val assignment = pair.copy(
    subjectKind = MatchSubjectKind.MentionResearcher,
    left = l,
    right = researcher,
    candidateId = None,
    matchPath = MatchPath.Deterministic,
    rulesFired = List("dblp-prior.cluster"),
    features = Nil
  )

  private def ok[A: Validator](a: A): Unit = assertEquals(Contract.validate(a), Nil)
  private def bad[A: Validator](a: A, fragment: String)(implicit loc: munit.Location): Unit = {
    val errs = Contract.validate(a)
    assert(
      errs.exists(_.contains(fragment)),
      s"expected an error containing '$fragment', got $errs"
    )
  }

  test("baselines are valid") {
    ok(pair)
    ok(assignment)
  }

  test("mention_pair decisions need a candidate ID and ordered mention subjects") {
    bad(pair.copy(candidateId = None), "candidateId")
    bad(pair.copy(left = r, right = l), "left/right")
    bad(pair.copy(right = researcher), "right")
    bad(pair.copy(left = l, right = l), "left/right")
  }

  test("an identity assignment is accepted or conflict, never rejected or unresolved") {
    bad(assignment.copy(outcome = MatchOutcome.Rejected), "identity assignment")
    bad(
      assignment.copy(outcome = MatchOutcome.Unresolved, reasonCodes = List("x")),
      "identity assignment"
    )
    ok(
      assignment.copy(
        outcome = MatchOutcome.Conflict,
        reasonCodes = List("cluster-multiple-orcids")
      )
    )
    bad(assignment.copy(right = l), "right")
  }

  test("llm decisions carry model and prompt versions; other paths carry neither") {
    bad(pair.copy(matchPath = MatchPath.Llm), "modelVersion")
    bad(pair.copy(matchPath = MatchPath.Llm, modelVersion = Some("m")), "promptVersion")
    ok(pair.copy(matchPath = MatchPath.Llm, modelVersion = Some("m"), promptVersion = Some("p")))
    bad(pair.copy(modelVersion = Some("m")), "modelVersion")
    bad(pair.copy(promptVersion = Some("p")), "promptVersion")
    bad(
      pair.copy(matchPath = MatchPath.Llm, modelVersion = Some(" "), promptVersion = Some("p")),
      "modelVersion"
    )
  }

  test("exact_id and deterministic decisions must name a fired rule") {
    bad(pair.copy(matchPath = MatchPath.ExactId, rulesFired = Nil), "rulesFired")
    bad(pair.copy(matchPath = MatchPath.Deterministic, rulesFired = Nil), "rulesFired")
  }

  test("unresolved and conflict decisions carry a reason code") {
    bad(pair.copy(outcome = MatchOutcome.Unresolved), "reasonCodes")
    bad(pair.copy(outcome = MatchOutcome.Conflict), "reasonCodes")
  }

  test("exact_id UNRESOLVED with a guard reason is a contract-valid decision") {
    // The real run produced 34 such decisions: an accepted exact identifier rule whose cluster merge
    // was refused by the dblp disambiguation-profile guard.
    ok(
      pair.copy(
        outcome = MatchOutcome.Unresolved,
        matchPath = MatchPath.ExactId,
        confidence = 0.99,
        rulesFired = List("cluster.guard.dblp-disambiguation-profile", "orcid.exact"),
        reasonCodes = List("merge-refused:dblp-disambiguation-profile")
      )
    )
  }

  test("rule and reason tokens are sorted, distinct and well formed") {
    bad(pair.copy(rulesFired = List("z.rule", "a.rule")), "rulesFired")
    bad(pair.copy(rulesFired = List("a.rule", "a.rule")), "rulesFired")
    bad(pair.copy(rulesFired = List("has space")), "rulesFired")
    bad(pair.copy(outcome = MatchOutcome.Conflict, reasonCodes = List("-leading")), "reasonCodes")
  }

  test("features are sorted, distinct and finite") {
    bad(pair.copy(features = List(MatchFeature("b", 1), MatchFeature("a", 1))), "features")
    bad(pair.copy(features = List(MatchFeature("a", 1), MatchFeature("a", 2))), "features")
    bad(pair.copy(features = List(MatchFeature("a", Double.NaN))), "finite")
    bad(pair.copy(features = List(MatchFeature("a", Double.PositiveInfinity))), "finite")
  }

  test("policy version, decision time and provenance source are pinned") {
    bad(pair.copy(policyVersion = "er-v2"), "policyVersion")
    bad(pair.copy(decisionTime = "2026-11-14T00:00:00.123Z"), "decisionTime")
    bad(pair.copy(decisionTime = "2026-11-14T00:00:00+01:00"), "decisionTime")
    bad(pair.copy(decisionTime = "2026-02-30T00:00:00Z"), "decisionTime")
    bad(pair.copy(provenance = prov.copy(sourceType = SourceType.Dblp)), "sourceType")
    bad(pair.copy(confidence = 1.5), "confidence")
  }

  test("MatchDecision v1 wire is refused (schema version and outcome names)") {
    bad(pair.copy(schemaVersion = 1), "schemaVersion")
    val v1Outcome = Contract.encode(pair).toOption.get.replace("\"accepted\"", "\"match\"")
    assert(Contract.decode[MatchDecision](v1Outcome).isLeft)
  }

  // ------------------------------------------------------------------------------------------
  // LlmAdjudication v1
  // ------------------------------------------------------------------------------------------

  private val inputHash = ContentHash.of("""{"task":"same-researcher","synthetic":true}""")
  private val cid2 =
    MatchCandidateId.of(MatchSubjectKind.MentionPair, l, r, "dblp-base-name", "Synth").toOption.get
  private val offered =
    List(cid, cid2).sortWith((a, b) => CodePointOrder.lessThan(a.value, b.value))
  private val adjId = LlmAdjudicationId.of(inputHash, "prompt/1", "model-1", "er-v1").toOption.get
  private val adj = LlmAdjudication(
    1,
    adjId,
    inputHash,
    offered,
    AdjudicationOutcome.Accept,
    Some(offered.head),
    List("left.name", "right.name"),
    0.9,
    List("same-coauthors"),
    "prompt/1",
    "model-1",
    "er-v1",
    prov.copy(contentHash = Some(inputHash), jobName = "er.llm-adjudication")
  )

  test("LlmAdjudication baseline and abstention are valid") {
    ok(adj)
    ok(
      adj.copy(
        outcome = AdjudicationOutcome.Abstain,
        chosenCandidateId = None,
        citedInputFields = Nil
      )
    )
  }

  test("an adjudication can never choose an identifier that was not offered") {
    val fabricated =
      MatchCandidateId.of(MatchSubjectKind.MentionPair, l, r, "fabricated", "x").toOption.get
    bad(adj.copy(chosenCandidateId = Some(fabricated)), "chosenCandidateId")
    bad(adj.copy(chosenCandidateId = None), "chosenCandidateId")
    bad(adj.copy(outcome = AdjudicationOutcome.Reject), "chosenCandidateId")
    bad(adj.copy(outcome = AdjudicationOutcome.Abstain), "chosenCandidateId")
  }

  test("offered IDs are non-empty, sorted, distinct") {
    bad(
      adj.copy(
        offeredCandidateIds = Nil,
        chosenCandidateId = None,
        outcome = AdjudicationOutcome.Reject
      ),
      "offeredCandidateIds"
    )
    bad(adj.copy(offeredCandidateIds = offered.reverse), "offeredCandidateIds")
    bad(adj.copy(offeredCandidateIds = List(offered.head, offered.head)), "offeredCandidateIds")
  }

  test("adjudication ID, input hash and content hash must agree") {
    bad(
      adj.copy(adjudicationId =
        LlmAdjudicationId.of(inputHash, "prompt/2", "model-1", "er-v1").toOption.get
      ),
      "adjudicationId"
    )
    bad(adj.copy(provenance = adj.provenance.copy(contentHash = None)), "contentHash")
    bad(adj.copy(inputHash = "sha256:XYZ"), "inputHash")
    bad(adj.copy(citedInputFields = Nil), "citedInputFields")
    bad(adj.copy(modelVersion = ""), "modelVersion")
    bad(adj.copy(policyVersion = "er-v9"), "policyVersion")
  }

  test("adjudication cache key changes with each of its four parts and only those") {
    val base = LlmAdjudicationId.of(inputHash, "prompt/1", "model-1", "er-v1")
    assertEquals(base, LlmAdjudicationId.of(inputHash, "prompt/1", "model-1", "er-v1"))
    val variants = List(
      LlmAdjudicationId.of(ContentHash.of("other input"), "prompt/1", "model-1", "er-v1"),
      LlmAdjudicationId.of(inputHash, "prompt/2", "model-1", "er-v1"),
      LlmAdjudicationId.of(inputHash, "prompt/1", "model-2", "er-v1"),
      LlmAdjudicationId.of(inputHash, "prompt/1", "model-1", "er-v1-override")
    )
    variants.foreach(v => assertNotEquals(v, base))
    assertEquals(variants.distinct.size, 4)
    // field boundaries cannot be shifted between parts
    assertNotEquals(
      LlmAdjudicationId.of(inputHash, "prompt/1a", "model-1", "er-v1"),
      LlmAdjudicationId.of(inputHash, "prompt/1", "amodel-1", "er-v1")
    )
    assert(LlmAdjudicationId.of(inputHash, " ", "model-1", "er-v1").isLeft)
    assert(LlmAdjudicationId.of(inputHash, "prompt/1", null, "er-v1").isLeft)
  }
}
