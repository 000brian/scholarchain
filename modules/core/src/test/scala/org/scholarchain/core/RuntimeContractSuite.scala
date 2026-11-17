package org.scholarchain.core

import scala.io.Source

import upickle.default.ReadWriter

/** Phase 9 runtime contracts: QueryOperation and EvidenceType additions, the VerificationStatus
  * rename, AnswerClaim v2, AnswerResponse v2 and QueryAudit v1. Runs under Scala 3 and Scala 2.13.
  */
class RuntimeContractSuite extends munit.FunSuite {
  import Fixtures._

  def frozen(file: String, version: Int): String = {
    val path = s"/wire/v$version/$file.json"
    val stream = getClass.getResourceAsStream(path)
    assert(stream != null, s"missing frozen wire example $path")
    val src = Source.fromInputStream(stream, "UTF-8")
    try src.mkString
    finally src.close()
  }

  def mutate(json: String)(f: ujson.Value => Unit): String = {
    val tree = ujson.read(json)
    f(tree)
    tree.render()
  }

  def errorsOf[A: Validator](a: A): List[String] = Contract.validate(a)

  def assertValid[A](a: A)(implicit v: Validator[A], loc: munit.Location): Unit =
    assertEquals(Contract.validate(a), Nil)

  def assertRejected[A](a: A, fragment: String)(implicit
      v: Validator[A],
      loc: munit.Location
  ): Unit = {
    val errors = errorsOf(a)
    assert(
      errors.exists(_.contains(fragment)),
      s"expected an error containing '$fragment', got $errors"
    )
  }

  def assertDecodeError[A](json: String, fragment: String)(implicit
      rw: ReadWriter[A],
      v: Validator[A],
      loc: munit.Location
  ): Unit = {
    val result = Contract.decode[A](json)
    assert(result.left.exists(_.messages.exists(_.contains(fragment))), result)
  }

  def assertFrozenVariant[A](file: String, version: Int, value: A)(implicit
      rw: ReadWriter[A],
      v: Validator[A],
      loc: munit.Location
  ): Unit = {
    val json = frozen(file, version)
    assertEquals(Contract.decode[A](json), Right(value))
    val encoded = Contract.encode(value).fold(e => fail(e.message), identity)
    assertEquals(ujson.read(encoded), ujson.read(json))
  }

  // ---- 3a QueryOperation -----------------------------------------------------------------

  def plan(steps: QueryStep*): QueryPlan = queryPlan.copy(steps = steps.toList)
  def step(op: QueryOperation, ids: List[ResearcherId]): QueryStep =
    QueryStep(op, ids, Some(window), Map.empty)

  test("3a: researcher counts per operation (empty only for ranking, bridge and semantic)") {
    QueryOperation.values.foreach { op =>
      val empty = plan(step(op, Nil))
      val one = plan(step(op, List(sousa)))
      val two = plan(step(op, List(sousa, campos)))
      op match {
        case QueryOperation.ShortestPath =>
          assertRejected(empty, "steps[0].researcherIds: shortest_path needs exactly 2")
          assertRejected(one, "steps[0].researcherIds: shortest_path needs exactly 2")
          assertValid(two)
        case QueryOperation.ResearcherRanking | QueryOperation.BridgeResearchers |
            QueryOperation.SemanticContext =>
          assertValid(empty)
          assertValid(one)
          assertValid(two)
        case other =>
          assertRejected(empty, "steps[0].researcherIds: must not be empty")
          assertValid(one)
          assertValid(two)
          assert(!QueryOperation.mayOmitResearchers.contains(other), other.wire)
      }
    }
  }

  test("3a: every new operation requires a window; researcher_lookup still does not") {
    List(
      QueryOperation.ResearcherRanking,
      QueryOperation.MaxTeamSize,
      QueryOperation.CommunityAnalysis,
      QueryOperation.TemporalComparison,
      QueryOperation.SemanticContext
    ).foreach { op =>
      assertRejected(
        plan(QueryStep(op, List(sousa), None, Map.empty)),
        s"steps[0].window: required for ${op.wire}"
      )
    }
    assertValid(plan(QueryStep(QueryOperation.ResearcherLookup, List(sousa), None, Map.empty)))
  }

  test("3a: QueryPlan stays schema version 1; the new operations have a frozen example") {
    assertEquals(SchemaVersions.currentOf("QueryPlan"), 1)
    assertFrozenVariant("QueryPlan.runtime-operations", 1, runtimeOperationsPlan)
    assertDecodeError[QueryPlan](
      mutate(frozen("QueryPlan.runtime-operations", 1))(
        _("steps")(0)("operation") = ujson.Str("run_cypher")
      ),
      "unknown query operation 'run_cypher'"
    )
  }

  // ---- 3b EvidenceType -------------------------------------------------------------------

  test("3b: a semantic_context item needs no window and has a frozen example") {
    assert(!EvidenceType.requiresWindow.contains(EvidenceType.SemanticContext))
    assertFrozenVariant("EvidenceItem.semantic-context", 1, semanticEvidence)
    assertValid(semanticEvidence.copy(window = None))
    assertEquals(SchemaVersions.currentOf("EvidenceItem"), 1)
    assertEquals(SchemaVersions.currentOf("EvidenceBundle"), 1)
  }

  test("Phase 11: a semantic_context item carries no metric (on encode and on decode)") {
    val withMetric = semanticEvidence.copy(metric = metricEvidence.metric)
    assertRejected(withMetric, "metric: must be null for semantic_context evidence")
    assert(Contract.encode(withMetric).isLeft)
    val json = mutate(frozen("EvidenceItem.semantic-context", 1))(
      _("metric") = ujson.Obj("metric" -> "distinct_coauthors", "value" -> 2.0)
    )
    val decoded = Contract.decode[EvidenceItem](json)
    assert(
      decoded.left.exists(_.messages.exists(_.startsWith("metric: must be null"))),
      decoded
    )
    // metric evidence keeps its metric; the frozen semantic example stays valid
    assertValid(metricEvidence)
    assertValid(semanticEvidence)
  }

  test("3b: a bundle can carry graph evidence and semantic context together") {
    val ids = List(metricEvidence.evidenceId, semanticEvidence.evidenceId)
    val bundle = evidenceBundle.copy(
      bundleId = EvidenceId.forBundle(queryRun, ids),
      items = List(metricEvidence, semanticEvidence)
    )
    assertValid(bundle)
  }

  // ---- 3c VerificationStatus -------------------------------------------------------------

  test("3c: the four Phase 9 status wire names decode; the retired names are rejected") {
    val v2 = frozen("AnswerClaim", 2)
    List("verified", "partially_supported", "conflicting").foreach { s =>
      val json = mutate(v2)(_("status") = ujson.Str(s))
      assertEquals(Contract.decode[AnswerClaim](json).map(_.status.wire), Right(s))
    }
    val unsupported = mutate(v2) { j =>
      j("status") = ujson.Str("unsupported")
      j("evidenceIds") = ujson.Arr()
    }
    assertEquals(
      Contract.decode[AnswerClaim](unsupported).map(_.status),
      Right(VerificationStatus.Unsupported)
    )
    List("partially_verified", "contradicted").foreach { old =>
      assertDecodeError[AnswerClaim](
        mutate(v2)(_("status") = ujson.Str(old)),
        s"unknown verification status '$old'"
      )
    }
  }

  // ---- 3d AnswerClaim v2 -----------------------------------------------------------------

  test("3d: AnswerClaim v2 fields are frozen in order") {
    assertEquals(SchemaVersions.currentOf("AnswerClaim"), 2)
    assertEquals(
      ujson.read(frozen("AnswerClaim", 2)).obj.keys.toList,
      List(
        "schemaVersion",
        "claimId",
        "claimText",
        "evidenceIds",
        "status",
        "window",
        "uncertainty",
        "provenance"
      )
    )
  }

  test("3d: claimId must match claim-[0-9]{1,4}") {
    List("claim-0", "claim-1", "claim-42", "claim-9999").foreach(id =>
      assertValid(answerClaim.copy(claimId = id))
    )
    List("claim-10000", "claim-", "Claim-1", "claim-1a", "claim_1", " claim-1", "", "1").foreach {
      id => assertRejected(answerClaim.copy(claimId = id), "claimId:")
    }
    assertDecodeError[AnswerClaim](
      mutate(frozen("AnswerClaim", 2))(_.obj.remove("claimId")),
      "decode:"
    )
  }

  test("3d: uncertainty is optional but non-blank when present") {
    assertValid(
      answerClaim.copy(uncertainty = Some("Identity conflict flagged (confidence 0.95)."))
    )
    assertRejected(answerClaim.copy(uncertainty = Some("  ")), "uncertainty: must not be blank")
    assertDecodeError[AnswerClaim](
      mutate(frozen("AnswerClaim", 2))(_.obj.remove("uncertainty")),
      "decode:"
    )
  }

  test("3d: only an unsupported claim may cite no evidence") {
    assertValid(unsupportedClaim)
    List(
      VerificationStatus.Verified,
      VerificationStatus.PartiallySupported,
      VerificationStatus.Conflicting
    ).foreach { s =>
      assertRejected(
        unsupportedClaim.copy(status = s),
        s"evidenceIds: a ${s.wire} claim must cite evidence"
      )
      assertValid(unsupportedClaim.copy(status = s, evidenceIds = List(metricEvidence.evidenceId)))
    }
  }

  test("3d: superseded AnswerClaim v1 is rejected (missing claimId, then the version)") {
    assertDecodeError[AnswerClaim](frozen("AnswerClaim", 1), "decode:")
    assertDecodeError[AnswerClaim](
      mutate(frozen("AnswerClaim", 2))(_("schemaVersion") = ujson.Num(1)),
      "schemaVersion: unsupported version 1 for AnswerClaim"
    )
  }

  // ---- 3e AnswerResponse v2 --------------------------------------------------------------

  test("3e: AnswerResponse v2 fields are frozen in order") {
    assertEquals(SchemaVersions.currentOf("AnswerResponse"), 2)
    assertEquals(
      ujson.read(frozen("AnswerResponse", 2)).obj.keys.toList,
      List(
        "schemaVersion",
        "question",
        "answerText",
        "modelId",
        "plan",
        "bundle",
        "claims",
        "identityNotes",
        "abstained",
        "abstentionReason",
        "provenance"
      )
    )
  }

  test("3e: an abstaining response may have no bundle and no claims (frozen example)") {
    assertFrozenVariant("AnswerResponse.abstained", 2, abstainedResponse)
    val json = ujson.read(frozen("AnswerResponse.abstained", 2))
    assertEquals(json("bundle"), ujson.Null)
    assertEquals(json("claims"), ujson.Arr())
    assertEquals(json("abstained"), ujson.Bool(true))
  }

  test("3e: abstentionReason is present exactly when abstained is true") {
    assertRejected(
      abstainedResponse.copy(abstentionReason = None),
      "abstentionReason: required when abstained is true"
    )
    assertRejected(
      abstainedResponse.copy(abstentionReason = Some(" ")),
      "abstentionReason: must not be blank"
    )
    assertRejected(
      answerResponse.copy(abstentionReason = Some("ambiguous researcher name")),
      "abstentionReason: must be null when abstained is false"
    )
    // An abstaining response may still carry a bundle and claims.
    assertValid(
      answerResponse.copy(abstained = true, abstentionReason = Some("no verified claim"))
    )
  }

  test("3e: no bundle or no claims only when the response abstains") {
    assertRejected(answerResponse.copy(bundle = None), "bundle: required unless the response")
    assertRejected(answerResponse.copy(claims = Nil), "claims: must not be empty unless")
    assertValid(abstainedResponse.copy(claims = List(unsupportedClaim)))
  }

  test("3e: claim IDs are unique within the response") {
    val second = answerClaim.copy(claimId = "claim-2")
    assertValid(answerResponse.copy(claims = List(answerClaim, second)))
    assertRejected(
      answerResponse.copy(claims = List(answerClaim, answerClaim)),
      "claims.claimId: claim IDs must be unique within the response (duplicates: claim-1)"
    )
  }

  test("3e: every cited evidence ID is in the bundle; without a bundle nothing can be cited") {
    val stray = EvidenceId.of(EvidenceType.Work, queryRun, List("work:v1:" + "1" * 32))
    assertRejected(
      answerResponse.copy(claims = List(answerClaim.copy(evidenceIds = List(stray)))),
      s"claims[0].evidenceIds: $stray is not in bundle"
    )
    assertRejected(
      abstainedResponse.copy(claims = List(answerClaim)),
      s"claims[0].evidenceIds: ${metricEvidence.evidenceId} is not in bundle"
    )
  }

  test("3e: superseded AnswerResponse v1 is rejected") {
    assertDecodeError[AnswerResponse](frozen("AnswerResponse", 1), "decode:")
    assertDecodeError[AnswerResponse](
      mutate(frozen("AnswerResponse", 2))(_("schemaVersion") = ujson.Num(1)),
      "schemaVersion: unsupported version 1 for AnswerResponse"
    )
  }

  // ---- 3f QueryAudit v1 ------------------------------------------------------------------

  test("3f: QueryAudit v1 fields are frozen in order and carry no reasoning text") {
    val json = ujson.read(frozen("QueryAudit", 1))
    assertEquals(
      json.obj.keys.toList,
      List(
        "schemaVersion",
        "queryRunId",
        "questionHash",
        "plan",
        "planSource",
        "resolvedResearcherIds",
        "unresolvedNames",
        "evidenceIds",
        "bundleId",
        "graphRunId",
        "metricPolicyVersions",
        "promptVersions",
        "modelId",
        "inputTokens",
        "outputTokens",
        "latencyMillis",
        "stageMillis",
        "verificationCounts",
        "answerHash",
        "abstained",
        "provenance"
      )
    )
    val forbidden = Set("reasoning", "rationale", "thoughts", "chainofthought", "answertext")
    assertEquals(json.obj.keys.filter(k => forbidden.contains(k.toLowerCase)).toList, Nil)
    assertEquals(json("provenance")("runId"), json("queryRunId"))
    assertEquals(json("planSource"), ujson.Str("rule"))
  }

  test("3f: prompt versions are <promptId>@sha256:<hex> of the prompt text") {
    assertEquals(
      QueryAudit.promptVersion("query-planner-v1", plannerPromptText),
      "query-planner-v1@" + ContentHash.of(plannerPromptText)
    )
    assert(QueryAudit.isValidPromptVersion(queryAudit.promptVersions.head))
    List(
      "query-planner-v1",
      "query-planner-v1@sha256:abc",
      "Query-Planner@" + ContentHash.of("x"),
      "query planner@" + ContentHash.of("x"),
      ContentHash.of("x")
    ).foreach { p =>
      assertRejected(queryAudit.copy(promptVersions = List(p)), "promptVersions[0]:")
    }
    val dup = queryAudit.promptVersions.head
    assertRejected(
      queryAudit.copy(promptVersions = List(dup, dup)),
      "promptVersions: contains duplicates"
    )
  }

  test("3f: hashes must be well formed") {
    assertRejected(queryAudit.copy(questionHash = "sha256:XYZ"), "questionHash: 'sha256:XYZ'")
    assertRejected(
      queryAudit.copy(answerHash = Hashing.sha256Hex(answerText)),
      "answerHash: '"
    )
  }

  test("3f: counts and times are non-negative") {
    assertRejected(queryAudit.copy(inputTokens = Some(-1L)), "inputTokens: -1 must be >= 0")
    assertRejected(queryAudit.copy(outputTokens = Some(-2L)), "outputTokens: -2 must be >= 0")
    assertRejected(queryAudit.copy(latencyMillis = -3L), "latencyMillis: -3 must be >= 0")
    assertRejected(
      queryAudit.copy(stageMillis = Map("planner" -> -4L)),
      "stageMillis[planner]: -4 must be >= 0"
    )
    assertRejected(queryAudit.copy(stageMillis = Map(" " -> 1L)), "stageMillis[0]: must not")
    assertRejected(
      queryAudit.copy(verificationCounts = Map("verified" -> -1)),
      "verificationCounts[verified]: -1 must be >= 0"
    )
    assertValid(
      queryAudit.copy(inputTokens = None, outputTokens = None, modelId = None, latencyMillis = 0L)
    )
  }

  test("3f: verification count keys are VerificationStatus wire names") {
    List("partially_verified", "contradicted", "VERIFIED").foreach { k =>
      assertRejected(
        queryAudit.copy(verificationCounts = Map(k -> 1)),
        s"verificationCounts: '$k' is not a verification status"
      )
    }
    assertValid(queryAudit.copy(verificationCounts = Map.empty))
  }

  test("3f: provenance is runtime and its runId is the query run") {
    assertRejected(
      queryAudit.copy(provenance = queryAudit.provenance.copy(runId = graphRun)),
      s"provenance.runId: $graphRun must equal queryRunId $queryRun"
    )
    assertRejected(
      queryAudit.copy(provenance = queryAudit.provenance.copy(sourceType = SourceType.Pipeline)),
      "provenance.sourceType: a QueryAudit is produced by the runtime"
    )
  }

  test("3f: IDs are well formed, distinct, and the plan is against graphRunId") {
    assertRejected(queryAudit.copy(queryRunId = RunId("run:v1:nothex")), "queryRunId: malformed")
    assertRejected(
      queryAudit.copy(resolvedResearcherIds = List(sousa, sousa)),
      "resolvedResearcherIds: contains duplicates"
    )
    assertRejected(
      queryAudit.copy(evidenceIds = List(metricEvidence.evidenceId, metricEvidence.evidenceId)),
      "evidenceIds: contains duplicates"
    )
    assertRejected(
      queryAudit.copy(bundleId = Some(EvidenceId("evidence:v1:short"))),
      "bundleId: malformed"
    )
    assertRejected(queryAudit.copy(unresolvedNames = List("")), "unresolvedNames[0]: must not")
    assertRejected(queryAudit.copy(metricPolicyVersions = List(" ")), "metricPolicyVersions[0]")
    assertRejected(queryAudit.copy(modelId = Some("")), "modelId: must not be blank")
    assertRejected(
      queryAudit.copy(graphRunId = parseRun),
      s"plan.graphRunId: $graphRun must equal graphRunId $parseRun"
    )
    assertRejected(queryAudit.copy(plan = queryPlan.copy(steps = Nil)), "plan.steps: must not")
  }

  test("3f: token counts beyond 32 bits round-trip as JSON numbers") {
    val big = queryAudit.copy(inputTokens = Some(5000000000L), latencyMillis = 3000000000L)
    val json = Contract.encode(big).fold(e => fail(e.message), identity)
    assertEquals(ujson.read(json)("inputTokens"), ujson.Num(5000000000.0))
    assertEquals(Contract.decode[QueryAudit](json), Right(big))
  }

  test("3f: an ambiguous-name abstention audit is valid") {
    val audit = queryAudit.copy(
      questionHash = ContentHash.of(ambiguousQuestion),
      plan = ambiguousPlan,
      planSource = PlanSource.Llm,
      resolvedResearcherIds = Nil,
      unresolvedNames = List("Wei Wang"),
      evidenceIds = Nil,
      bundleId = None,
      verificationCounts = Map.empty,
      answerHash = ContentHash.of(abstainText),
      abstained = true
    )
    assertValid(audit)
  }
}
