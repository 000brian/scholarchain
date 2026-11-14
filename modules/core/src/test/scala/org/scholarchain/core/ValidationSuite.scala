package org.scholarchain.core

import Fixtures._

/** Each validation rule, exercised against an otherwise valid fixture. */
class ValidationSuite extends munit.FunSuite {

  def errorsOf[A: Validator](a: A): List[String] = Contract.validate(a)

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

  // ---- missing provenance ---------------------------------------------------------------
  test("missing (null) provenance is rejected, not defaulted") {
    assertRejected(workRecord.copy(provenance = null), "provenance: missing (null)")
    assertRejected(coauthorEdge.copy(provenance = null), "provenance: missing (null)")
    assertRejected(answerResponse.copy(provenance = null), "provenance: missing (null)")
  }

  test("null nested provenance inside a bundle item is rejected") {
    val bundle = evidenceBundle.copy(items = List(workEvidence.copy(provenance = null)))
    assertRejected(bundle, "items[0].provenance: missing (null)")
  }

  test("provenance fields are required and well-formed") {
    assertRejected(provenance.copy(sourceRecordIds = Nil), "sourceRecordIds: must not be empty")
    assertRejected(
      provenance.copy(sourceRecordIds = List(" ")),
      "sourceRecordIds[0]: must not be blank"
    )
    assertRejected(provenance.copy(datasetId = ""), "datasetId")
    assertRejected(provenance.copy(datasetId = "DBLP"), "datasetId")
    assertRejected(provenance.copy(snapshotId = " "), "snapshotId: must not be blank")
    assertRejected(provenance.copy(jobName = ""), "jobName: must not be blank")
    assertRejected(provenance.copy(policyVersion = ""), "policyVersion: must not be blank")
    assertRejected(provenance.copy(runId = RunId("run:v1:nope")), "runId: malformed stable ID")
    assertRejected(provenance.copy(contentHash = Some("md5:abc")), "contentHash")
  }

  // ---- confidence ranges ----------------------------------------------------------------
  test("confidence outside [0, 1] or NaN is rejected") {
    List(-0.01, 1.01, Double.NaN, Double.PositiveInfinity).foreach { c =>
      assertRejected(authorshipRecord.copy(matchConfidence = c), "matchConfidence: confidence")
      assertRejected(matchDecision.copy(confidence = c), "confidence: confidence")
      assertRejected(coauthorEdge.copy(minIdentityConfidence = c), "minIdentityConfidence")
      assertRejected(researcherMetrics.copy(identityConfidence = c), "identityConfidence")
    }
  }

  test("confidence bounds 0 and 1 are accepted") {
    assertEquals(errorsOf(authorshipRecord.copy(matchConfidence = 0.0)), Nil)
    assertEquals(errorsOf(authorshipRecord.copy(matchConfidence = 1.0)), Nil)
  }

  // ---- schema versions ------------------------------------------------------------------
  test("unsupported schema versions are rejected in memory") {
    assertRejected(workRecord.copy(schemaVersion = 0), "schemaVersion: unsupported version 0")
    assertRejected(workRecord.copy(schemaVersion = 1), "schemaVersion: unsupported version 1")
    assertRejected(workRecord.copy(schemaVersion = 3), "schemaVersion: unsupported version 3")
    assertRejected(
      coauthorPairCandidate.copy(schemaVersion = 1),
      "schemaVersion: unsupported version 1"
    )
    assertRejected(
      workRecord.copy(provenance = provenance.copy(schemaVersion = 2)),
      "provenance.schemaVersion: unsupported version 2"
    )
  }

  // ---- malformed stable IDs -------------------------------------------------------------
  test("malformed stable IDs are rejected in memory") {
    assertRejected(workRecord.copy(workId = WorkId("work:v1:XYZ")), "workId: malformed stable ID")
    assertRejected(
      authorshipRecord.copy(researcherId = ResearcherId("r1")),
      "researcherId: malformed"
    )
    assertRejected(coauthorEdge.copy(edgeId = EdgeId("edge:v1:")), "edgeId: malformed")
    assertRejected(
      answerClaim.copy(evidenceIds = List(EvidenceId("evidence:v2:" + "0" * 32))),
      "evidenceIds[0]: malformed"
    )
    assertRejected(queryPlan.copy(graphRunId = RunId("")), "graphRunId: malformed")
  }

  test("well-formed IDs that do not match their formula are rejected") {
    assertRejected(workRecord.copy(dblpKey = "conf/sigir/Other23"), "workId: work:v1:")
    assertRejected(authorMention.copy(position = 1), "mentionId:")
    assertRejected(
      canonicalResearcher.copy(researcherId = campos),
      "researcherId: " + campos.value + " is not ResearcherId.fromAnchorMention"
    )
    assertRejected(canonicalResearcher.copy(anchorMentionId = camposMention), "researcherId:")
    assertRejected(workLink.copy(externalId = "W1"), "linkId:")
    assertRejected(coauthorEdge.copy(window = YearWindow(2015, 2024)), "edgeId:")
    assertRejected(workEvidence.copy(subjectIds = List("work:v1:" + "0" * 32)), "evidenceId:")
    assertRejected(evidenceBundle.copy(items = List(workEvidence)), "bundleId:")
  }

  test("graph node IDs must match the node label") {
    assertRejected(graphNode.copy(nodeId = workId.value), "nodeId: ")
    assertEquals(errorsOf(graphNode.copy(label = GraphNodeLabel.Work, nodeId = workId.value)), Nil)
  }

  // ---- undirected pair ordering ---------------------------------------------------------
  test("coauthor pair candidates out of canonical order are rejected") {
    val swapped = coauthorPairCandidate.copy(left = sousaName, right = jorgeName)
    assertRejected(swapped, "left/right: undirected pair")
  }

  test("coauthor pair candidates with equal names (self pairs) are rejected") {
    assertRejected(coauthorPairCandidate.copy(right = jorgeName), "left/right: undirected pair")
  }

  test("coauthor edges out of canonical order are rejected") {
    val swapped = coauthorEdge.copy(left = coauthorEdge.right, right = coauthorEdge.left)
    // The edge ID is order-independent, so the ordering rule alone rejects this record.
    assertEquals(errorsOf(swapped).size, 1, errorsOf(swapped))
    assertRejected(swapped, "left/right: undirected pair")
  }

  test("coauthor self edges are rejected") {
    assertRejected(coauthorEdge.copy(right = coauthorEdge.left), "left/right: undirected pair")
  }

  test("undirected graph edges out of canonical order are rejected; directed edges are not") {
    assertRejected(
      graphEdge.copy(from = graphEdge.to, to = graphEdge.from),
      "from/to: undirected pair"
    )
    val authored = graphEdge.copy(
      label = GraphEdgeLabel.Authored,
      from = sousa.value,
      to = workId.value,
      edgeId = EdgeId.of(GraphEdgeLabel.Authored, sousa.value, workId.value, window).toOption.get
    )
    assertEquals(errorsOf(authored), Nil)
  }

  test("directed graph edges must connect the labelled endpoint kinds") {
    val wrong = graphEdge.copy(
      label = GraphEdgeLabel.Authored,
      from = workId.value,
      to = sousa.value,
      edgeId = EdgeId.of(GraphEdgeLabel.Authored, workId.value, sousa.value, window).toOption.get
    )
    assertRejected(wrong, "from: ")
    assertRejected(wrong, "to: ")
  }

  // ---- windows --------------------------------------------------------------------------
  test("windows whose start year exceeds the end year are rejected") {
    val bad = YearWindow(2025, 2015)
    assertRejected(bad, "startYear 2025 exceeds endYear 2015")
    assertRejected(
      researcherMetrics.copy(window = bad),
      "window: startYear 2025 exceeds endYear 2015"
    )
    assertRejected(coauthorEdge.copy(window = bad), "window: startYear 2025 exceeds endYear 2015")
    assertRejected(graphEdge.copy(window = bad), "window: startYear 2025 exceeds endYear 2015")
    assertRejected(metricEvidence.copy(window = Some(bad)), "window: startYear 2025 exceeds")
    assertRejected(answerClaim.copy(window = Some(bad)), "window: startYear 2025 exceeds")
    val step = queryPlan.steps(1).copy(window = Some(bad))
    assertRejected(queryPlan.copy(steps = List(step)), "steps[0].window: startYear 2025 exceeds")
  }

  test("single-year windows are accepted") {
    assertEquals(errorsOf(YearWindow(2023, 2023)), Nil)
  }

  test("metrics state their window: graph evidence and graph queries require one") {
    assertRejected(metricEvidence.copy(window = None), "window: required for metric evidence")
    val step = queryPlan.steps(1).copy(window = None)
    assertRejected(
      queryPlan.copy(steps = List(step)),
      "steps[0].window: required for collaboration_breadth"
    )
  }

  test("edge years must lie inside the edge window") {
    assertRejected(coauthorEdge.copy(firstYear = 2014), "firstYear: 2014 is outside window")
    assertRejected(coauthorEdge.copy(firstYear = 2024, lastYear = 2023), "firstYear: 2024 exceeds")
  }

  // ---- other record rules ---------------------------------------------------------------
  test("external identifiers in records must be canonical") {
    assertRejected(workRecord.copy(doi = Some("10.1145/ABC")), "doi: ")
    assertRejected(authorMention.copy(orcid = Some("0000-0000-0000-0000")), "orcid: ")
    assertRejected(
      workLink.copy(externalId = "https://openalex.org/W1"),
      "externalId: 'https://openalex.org/W1' is not a canonical openalex_work identifier"
    )
    val badClaim = fromOpenAlex(ExternalIdType.OpenAlexAuthor, "a5080716895")
    assertRejected(
      canonicalResearcher.copy(externalIds = List(badClaim)),
      "externalIds[0].value: 'a5080716895' is not a canonical openalex_author"
    )
    assertRejected(authorMention.copy(name = "Hugo  O. Sousa"), "name: ")
  }

  // ---- Phase 5: MatchDecision v2 --------------------------------------------------------------
  test("match decision fixtures are valid: pairwise, identity assignment, llm") {
    assertEquals(errorsOf(matchDecision), Nil)
    assertEquals(errorsOf(identityAssignment), Nil)
    assertEquals(errorsOf(llmDecision), Nil)
  }

  test("match decision: every outcome and path is representable") {
    MatchOutcome.values.foreach { o =>
      val d = matchDecision.copy(outcome = o, reasonCodes = List("FIXTURE_REASON"))
      assertEquals(errorsOf(d), Nil, o.wire)
    }
    MatchPath.values.filter(_ != MatchPath.Llm).foreach { p =>
      assertEquals(errorsOf(matchDecision.copy(matchPath = p)), Nil, p.wire)
    }
  }

  test("match decision: mention pairs are mention IDs in canonical order, no self pairs") {
    val swapped = matchDecision.copy(left = matchDecision.right, right = matchDecision.left)
    assertRejected(swapped, "left/right: undirected pair")
    assertRejected(matchDecision.copy(right = matchDecision.left), "left/right: undirected pair")
    assertRejected(matchDecision.copy(right = sousa.value), "right: '")
    assertRejected(matchDecision.copy(left = workId.value), "left: '" + workId.value)
  }

  test("match decision: mention_researcher subjects are a mention and a researcher") {
    assertRejected(identityAssignment.copy(right = sousaMention.value), "right: '")
    assertRejected(identityAssignment.copy(left = sousa.value), "left: '")
  }

  test("match decision: a pairwise decision names its candidate") {
    assertRejected(matchDecision.copy(candidateId = None), "candidateId: required")
    assertRejected(
      matchDecision.copy(candidateId = Some(MatchCandidateId("match-candidate:v1:zz"))),
      "candidateId: malformed stable ID"
    )
  }

  test("match decision: an identity assignment is accepted or conflict") {
    val conflict = identityAssignment.copy(
      outcome = MatchOutcome.Conflict,
      reasonCodes = List("FIXTURE_TWO_ORCIDS")
    )
    assertEquals(errorsOf(conflict), Nil)
    List(MatchOutcome.Rejected, MatchOutcome.Unresolved).foreach { o =>
      assertRejected(
        identityAssignment.copy(outcome = o, reasonCodes = List("R")),
        "outcome: an identity assignment"
      )
    }
    // A mention_researcher decision of a real candidate may have any outcome.
    val decided = identityAssignment.copy(
      candidateId = Some(researcherCandidate(sousa)),
      outcome = MatchOutcome.Rejected
    )
    assertEquals(errorsOf(decided), Nil)
  }

  test("match decision: unresolved and conflict decisions carry a reason code") {
    List(MatchOutcome.Unresolved, MatchOutcome.Conflict).foreach { o =>
      assertRejected(
        matchDecision.copy(outcome = o, reasonCodes = Nil),
        s"reasonCodes: a ${o.wire} decision must carry"
      )
    }
    assertEquals(errorsOf(matchDecision.copy(reasonCodes = Nil)), Nil)
  }

  test("match decision: exact_id and deterministic decisions name the rule that fired") {
    List(MatchPath.ExactId, MatchPath.Deterministic).foreach { p =>
      assertRejected(
        matchDecision.copy(matchPath = p, rulesFired = Nil),
        s"rulesFired: a ${p.wire} decision must name the rule"
      )
    }
    assertEquals(errorsOf(matchDecision.copy(rulesFired = Nil)), Nil)
  }

  test("match decision: model and prompt versions exactly for the llm path") {
    assertRejected(llmDecision.copy(modelVersion = None), "modelVersion: required")
    assertRejected(llmDecision.copy(promptVersion = None), "promptVersion: required")
    assertRejected(llmDecision.copy(modelVersion = Some(" ")), "modelVersion: must not be blank")
    assertRejected(
      matchDecision.copy(modelVersion = Some(modelVersion)),
      "modelVersion: must be null for a probabilistic decision"
    )
    assertRejected(
      matchDecision.copy(promptVersion = Some(promptVersion)),
      "promptVersion: must be null"
    )
  }

  test("match decision: rules and reason codes are sorted, distinct code tokens") {
    assertRejected(matchDecision.copy(rulesFired = List("b", "a")), "rulesFired: must be sorted")
    assertRejected(matchDecision.copy(rulesFired = List("a", "a")), "rulesFired: must be sorted")
    assertRejected(matchDecision.copy(reasonCodes = List("has space")), "reasonCodes[0]:")
    assertRejected(matchDecision.copy(reasonCodes = List("")), "reasonCodes[0]:")
    assertEquals(errorsOf(matchDecision.copy(rulesFired = List("A_1", "dblp-prior/1"))), Nil)
  }

  test("match decision: features follow the candidate feature rules but may be empty") {
    val fs = matchDecision.features
    assertRejected(matchDecision.copy(features = fs.reverse), "features: must be sorted")
    assertRejected(matchDecision.copy(features = List(fs.head, fs.head)), "features.name")
    assertRejected(
      matchDecision.copy(features = List(MatchFeature("x", Double.NaN))),
      "features[0].value: must be finite"
    )
    assertEquals(errorsOf(matchDecision.copy(features = Nil)), Nil)
  }

  test("match decision: policyVersion is the provenance policy version") {
    assertRejected(matchDecision.copy(policyVersion = "other/1"), "policyVersion: 'other/1'")
    assertRejected(matchDecision.copy(policyVersion = " "), "policyVersion: must not be blank")
  }

  test("match decision: decisionTime is a UTC instant yyyy-MM-ddTHH:mm:ssZ") {
    List(
      "",
      "2026-11-14",
      "2026-11-14T00:00:00",
      "2026-11-14T00:00:00.000Z",
      "2026-11-14T00:00:00+00:00",
      "2026-13-01T00:00:00Z",
      "2026-02-30T00:00:00Z",
      "2026-11-14T24:00:00Z"
    ).foreach { t =>
      assertRejected(matchDecision.copy(decisionTime = t), "decisionTime: '")
    }
    assertEquals(errorsOf(matchDecision.copy(decisionTime = "2024-02-29T23:59:59Z")), Nil)
  }

  test("match decision: produced by the pipeline, with provenance") {
    assertRejected(
      matchDecision.copy(provenance = provenance),
      "provenance.sourceType: a MatchDecision is produced by entity resolution"
    )
    assertRejected(matchDecision.copy(provenance = null), "provenance: missing (null)")
  }

  // ---- Phase 5: LlmAdjudication --------------------------------------------------------------
  test("LLM adjudication fixture is valid; reject and abstain choose nothing") {
    assertEquals(errorsOf(llmAdjudication), Nil)
    assertEquals(
      errorsOf(
        llmAdjudication.copy(outcome = AdjudicationOutcome.Reject, chosenCandidateId = None)
      ),
      Nil
    )
    assertEquals(
      errorsOf(
        llmAdjudication.copy(
          outcome = AdjudicationOutcome.Abstain,
          chosenCandidateId = None,
          citedInputFields = Nil,
          reasonCodes = Nil
        )
      ),
      Nil
    )
  }

  test("LLM adjudication: accept must choose one of the offered candidates") {
    assertRejected(
      llmAdjudication.copy(chosenCandidateId = None),
      "chosenCandidateId: required when outcome is accept"
    )
    val notOffered = MatchCandidateId
      .of(MatchSubjectKind.MentionPair, candidateLeft, candidateRight, "f", "k")
      .toOption
      .get
    assertRejected(
      llmAdjudication.copy(chosenCandidateId = Some(notOffered)),
      "is not one of offeredCandidateIds"
    )
    List(AdjudicationOutcome.Reject, AdjudicationOutcome.Abstain).foreach { o =>
      assertRejected(llmAdjudication.copy(outcome = o), s"must be null when outcome is ${o.wire}")
    }
  }

  test("LLM adjudication: offered candidates are non-empty, well-formed, sorted, distinct") {
    assertRejected(
      llmAdjudication.copy(offeredCandidateIds = Nil, chosenCandidateId = None),
      "offeredCandidateIds: must not be empty"
    )
    assertRejected(
      llmAdjudication.copy(offeredCandidateIds = offeredCandidates.reverse),
      "offeredCandidateIds: must be sorted"
    )
    assertRejected(
      llmAdjudication.copy(offeredCandidateIds = List(chosenCandidate, chosenCandidate)),
      "offeredCandidateIds: must be sorted"
    )
    assertRejected(
      llmAdjudication.copy(offeredCandidateIds = List(MatchCandidateId("x"))),
      "offeredCandidateIds[0]: malformed stable ID"
    )
  }

  test("LLM adjudication: accept and reject cite input fields; fields are tokens") {
    assertRejected(
      llmAdjudication.copy(citedInputFields = Nil),
      "citedInputFields: a accept adjudication must cite"
    )
    assertRejected(
      llmAdjudication.copy(citedInputFields = List("b", "a")),
      "citedInputFields: must be sorted"
    )
    assertRejected(
      llmAdjudication.copy(citedInputFields = List("two words")),
      "citedInputFields[0]:"
    )
  }

  test("LLM adjudication: the ID is the cache key of input hash and the three versions") {
    assertRejected(llmAdjudication.copy(promptVersion = "other-prompt/1"), "adjudicationId:")
    assertRejected(llmAdjudication.copy(modelVersion = "other-model"), "adjudicationId:")
    val otherHash = ContentHash.of("other input")
    assertRejected(
      llmAdjudication.copy(
        inputHash = otherHash,
        provenance = llmAdjudication.provenance.copy(contentHash = Some(otherHash))
      ),
      "adjudicationId:"
    )
    assertRejected(
      llmAdjudication.copy(adjudicationId = LlmAdjudicationId("llm-adjudication:v1:00")),
      "adjudicationId: malformed stable ID"
    )
  }

  test("LLM adjudication: input hash is well-formed and is the provenance content hash") {
    assertRejected(llmAdjudication.copy(inputHash = "md5:abc"), "inputHash: 'md5:abc'")
    assertRejected(
      llmAdjudication.copy(provenance = llmAdjudication.provenance.copy(contentHash = None)),
      "provenance.contentHash: must equal inputHash"
    )
  }

  test("LLM adjudication: versions, confidence, policy, provenance source") {
    assertRejected(llmAdjudication.copy(modelVersion = ""), "modelVersion: must not be blank")
    assertRejected(llmAdjudication.copy(promptVersion = " "), "promptVersion: must not be blank")
    assertRejected(llmAdjudication.copy(confidence = 1.5), "confidence: confidence")
    assertRejected(llmAdjudication.copy(policyVersion = "other/1"), "policyVersion: 'other/1'")
    assertRejected(
      llmAdjudication.copy(provenance =
        llmAdjudication.provenance.copy(sourceType = SourceType.Runtime)
      ),
      "provenance.sourceType: a LlmAdjudication"
    )
  }

  test("text-bearing records carry the content hash of their text") {
    assertRejected(
      workEvidence.copy(snippet = Some("changed")),
      "contentHash: does not match snippet"
    )
    assertRejected(
      answerClaim.copy(provenance = answerClaim.provenance.copy(contentHash = None)),
      "contentHash: required"
    )
  }

  test("claims cite evidence present in the bundle") {
    val stray = EvidenceId.of(EvidenceType.Work, queryRun, List("work:v1:" + "1" * 32))
    val claim = answerClaim.copy(evidenceIds = List(stray))
    assertRejected(answerResponse.copy(claims = List(claim)), "is not in bundle")
    assertRejected(
      answerClaim.copy(evidenceIds = Nil),
      "evidenceIds: a verified claim must cite evidence"
    )
  }

  test("counts must agree with their lists") {
    assertRejected(publicationTeam.copy(teamSize = 4), "teamSize: 4 != authorNames size 3")
    assertRejected(
      coauthorEdge.copy(sharedWorkCount = 2),
      "sharedWorkCount: 2 != supportingWorkIds"
    )
    assertRejected(coauthorPairCandidate.copy(teamSize = 1), "teamSize: 1 must be >= 2")
  }

  test("metric values are finite, non-negative, and not repeated") {
    val dup = researcherMetrics.values.head
    assertRejected(
      researcherMetrics.copy(values = List(dup, dup)),
      "values.metric: contains duplicates"
    )
    assertRejected(
      researcherMetrics.copy(values = List(dup.copy(value = -1.0))),
      "values[0].value(distinct_coauthors)"
    )
  }

  test("encode refuses invalid records") {
    val result = Contract.encode(workRecord.copy(schemaVersion = 9))
    assert(result.isLeft, result)
  }

  // ---- review finding 1: researcher identity anchor ---------------------------------------
  test("researchers split from one dblp name are distinct valid records with distinct IDs") {
    val m1 = MentionId.of(WorkId.fromDblpKey("conf/x/WangL19"), 0)
    val m2 = MentionId.of(WorkId.fromDblpKey("conf/z/ChenW20"), 1)
    def weiWang(anchor: MentionId) = canonicalResearcher.copy(
      researcherId = ResearcherId.fromAnchorMention(anchor),
      anchorMentionId = anchor,
      displayName = "Wei Wang",
      dblpNames = List("Wei Wang"),
      externalIds = Nil
    )
    val (a, b) = (weiWang(m1), weiWang(m2))
    assertEquals(errorsOf(a), Nil)
    assertEquals(errorsOf(b), Nil)
    assertNotEquals(a.researcherId, b.researcherId)
  }

  test("researcher anchorMentionId must be a well-formed mention ID") {
    assertRejected(
      canonicalResearcher.copy(anchorMentionId = MentionId("mention:v1:x")),
      "anchorMentionId: malformed stable ID"
    )
  }

  // ---- review finding 2: every foreign identifier names its source snapshot ---------------
  test("the external-identifier link fixtures are valid") {
    List(externalIdLink, workLink, venueLink, institutionLink).foreach(l =>
      assertEquals(errorsOf(l), Nil, l)
    )
  }

  test("an OpenAlex ID linked with dblp (or any non-OpenAlex) provenance is rejected") {
    List(SourceType.Dblp, SourceType.Crossref, SourceType.Orcid, SourceType.Pipeline).foreach {
      st =>
        val p = externalIdLink.provenance.copy(sourceType = st)
        val l = externalIdLink.copy(
          provenance = p,
          linkId = LinkId.of(
            l0.subjectKind,
            l0.subjectId,
            l0.idType,
            l0.externalId,
            st,
            p.datasetId,
            p.snapshotId
          )
        )
        assertRejected(
          l,
          s"provenance.sourceType: a openalex_author identifier cannot be asserted by source ${st.wire}"
        )
    }
  }
  private def l0 = externalIdLink

  test("an ORCID iD linked from an enrichment source is accepted; a dblp-sourced link is not") {
    def orcidLink(st: SourceType, dataset: String) = {
      val p = externalIdLink.provenance.copy(sourceType = st, datasetId = dataset)
      val id = "0000-0003-3226-9189"
      externalIdLink.copy(
        idType = ExternalIdType.Orcid,
        externalId = id,
        provenance = p,
        linkId = LinkId.of(
          LinkSubjectKind.Mention,
          l0.subjectId,
          ExternalIdType.Orcid,
          id,
          st,
          dataset,
          p.snapshotId
        )
      )
    }
    assertEquals(errorsOf(orcidLink(SourceType.OpenAlex, "openalex")), Nil)
    assertEquals(errorsOf(orcidLink(SourceType.Crossref, "crossref")), Nil)
    assertRejected(
      orcidLink(SourceType.Dblp, "dblp"),
      "dblp-published identifiers are carried on WorkRecord.doi and AuthorMention.orcid"
    )
  }

  test("the link ID binds the asserting snapshot: changing the snapshot needs a new link ID") {
    val moved = externalIdLink.copy(
      provenance = externalIdLink.provenance.copy(snapshotId = "2026-10-14")
    )
    assertRejected(moved, "linkId: ")
  }

  test("link identifier kinds must fit the subject kind and subject ID") {
    assertRejected(
      workLink.copy(subjectKind = LinkSubjectKind.Mention),
      "idType: openalex_work cannot be linked to a mention"
    )
    assertRejected(
      workLink.copy(subjectId = "conf/sigir"),
      "subjectId: 'conf/sigir' is not a work ID"
    )
    assertRejected(venueLink.copy(subjectId = "conf"), "subjectId: 'conf' is not a dblp venue key")
  }

  test("dblp-published records must have dblp provenance (their DOI and ORCID are dblp's)") {
    val oa = openAlexProvenance(List(openAlexWork))
    assertRejected(workRecord.copy(provenance = oa), "provenance.sourceType: WorkRecord carries")
    assertRejected(authorMention.copy(provenance = oa), "provenance.sourceType: AuthorMention")
    assertRejected(venue.copy(provenance = oa), "provenance.sourceType: Venue carries")
    // The dblp-published ORCID on a mention is legitimate with dblp provenance.
    assertEquals(errorsOf(authorMention), Nil)
  }

  test("an institution keyed by an OpenAlex ID must have OpenAlex provenance") {
    assertRejected(
      institution.copy(provenance = dblpProvenance(List(dblpKey))),
      "provenance.sourceType: a openalex_institution identifier cannot be asserted by source dblp"
    )
  }

  test("researcher external IDs must name a source that can assert them, with a snapshot") {
    val oaByDblp = SourcedExternalId(
      ExternalIdType.OpenAlexAuthor,
      "A5080716895",
      SourceType.Dblp,
      "dblp",
      "2026-11-01",
      dblpKey
    )
    assertRejected(
      canonicalResearcher.copy(externalIds = List(oaByDblp)),
      "externalIds[0].sourceType: a openalex_author identifier cannot be asserted by source dblp"
    )
    val viaPipeline = fromOpenAlex(ExternalIdType.OpenAlexAuthor, "A5080716895")
      .copy(sourceType = SourceType.Pipeline)
    assertRejected(
      canonicalResearcher.copy(externalIds = List(viaPipeline)),
      "externalIds[0].sourceType: a openalex_author identifier cannot be asserted by source pipeline"
    )
    val noSnapshot =
      fromOpenAlex(ExternalIdType.OpenAlexAuthor, "A5080716895").copy(snapshotId = " ")
    assertRejected(
      canonicalResearcher.copy(externalIds = List(noSnapshot)),
      "externalIds[0].snapshotId: must not be blank"
    )
    val noRecord =
      fromOpenAlex(ExternalIdType.OpenAlexAuthor, "A5080716895").copy(sourceRecordId = "")
    assertRejected(
      canonicalResearcher.copy(externalIds = List(noRecord)),
      "externalIds[0].sourceRecordId: must not be blank"
    )
    val badDataset = fromOpenAlex(ExternalIdType.OpenAlexAuthor, "A5080716895").copy(datasetId = "")
    assertRejected(
      canonicalResearcher.copy(externalIds = List(badDataset)),
      "externalIds[0].datasetId"
    )
  }

  test("a dblp-published ORCID on a researcher keeps dblp provenance") {
    val dblpOrcid = canonicalResearcher.externalIds.filter(_.sourceType == SourceType.Dblp)
    assertEquals(dblpOrcid.map(_.idType), List(ExternalIdType.Orcid))
    assertEquals(errorsOf(canonicalResearcher.copy(externalIds = dblpOrcid)), Nil)
  }

  test("researcher external IDs: allowed kinds, sorted, distinct, at most one ORCID iD") {
    val doi = fromOpenAlex(ExternalIdType.Doi, "10.1145/3539618.3591892")
    assertRejected(
      canonicalResearcher.copy(externalIds = List(doi)),
      "externalIds[0].idType: doi is not a researcher identifier"
    )
    assertRejected(
      canonicalResearcher.copy(externalIds = canonicalResearcher.externalIds.reverse),
      "externalIds: must be distinct and sorted"
    )
    val first = canonicalResearcher.externalIds.head
    assertRejected(
      canonicalResearcher.copy(externalIds = List(first, first)),
      "externalIds: must be distinct and sorted"
    )
    val twoOrcids = List(
      fromOpenAlex(ExternalIdType.Orcid, "0000-0001-8209-698X"),
      fromOpenAlex(ExternalIdType.Orcid, "0000-0003-3226-9189")
    )
    assertRejected(
      canonicalResearcher.copy(externalIds = twoOrcids),
      "externalIds: at most one ORCID iD per researcher"
    )
  }

  // ---- Phase 4: WorkRecord v2 eeLinks and urls ---------------------------------------------
  test("WorkRecord eeLinks and urls keep source order; empty lists are valid") {
    val wikidata = "https://www.wikidata.org/entity/Q131012499"
    assertEquals(errorsOf(workRecord.copy(eeLinks = List(eeLink, wikidata))), Nil)
    assertEquals(errorsOf(workRecord.copy(eeLinks = List(wikidata, eeLink))), Nil)
    assertNotEquals(
      workRecord.copy(eeLinks = List(eeLink, wikidata)),
      workRecord.copy(eeLinks = List(wikidata, eeLink))
    )
    assertEquals(errorsOf(workRecord.copy(doi = None, eeLinks = Nil, urls = Nil)), Nil)
  }

  test("WorkRecord eeLinks and urls entries must be trimmed, non-blank, without controls") {
    val bel = 7.toChar.toString
    List(" ", "", " https://x.org/a", "https://x.org/a ", s"https://x.org/${bel}a").foreach { v =>
      assertRejected(workRecord.copy(eeLinks = List(eeLink, v)), "eeLinks[1]:")
      assertRejected(workRecord.copy(urls = List(v)), "urls[0]:")
    }
  }

  test("WorkRecord eeLinks and urls must not repeat an entry") {
    assertRejected(workRecord.copy(eeLinks = List(eeLink, eeLink)), "eeLinks: contains duplicates")
    assertRejected(workRecord.copy(urls = List(tocUrl, tocUrl)), "urls: contains duplicates")
  }

  test("WorkRecord doi must be the DOI of one of its eeLinks") {
    assertRejected(workRecord.copy(eeLinks = Nil), "doi: '10.1145/3539618.3591892' is not")
    assertRejected(
      workRecord.copy(eeLinks = List("https://www.wikidata.org/entity/Q131012499")),
      "doi: "
    )
    // A doi.org link in another spelling still yields the same canonical DOI.
    assertEquals(
      errorsOf(workRecord.copy(eeLinks = List("HTTPS://DX.DOI.ORG/10.1145/3539618.3591892"))),
      Nil
    )
    // An ee DOI without a WorkRecord doi is allowed (conflicting DOIs are dropped, not guessed).
    assertEquals(errorsOf(workRecord.copy(doi = None)), Nil)
  }

  // ---- Phase 4: CoauthorPairCandidate v2 positions -------------------------------------------
  test("pair positions: the fixture and a consolidated homonym pair are valid") {
    assertEquals(errorsOf(coauthorPairCandidate), Nil)
    assertEquals(errorsOf(consolidatedPair), Nil)
  }

  test("pair positions must not be empty") {
    assertRejected(coauthorPairCandidate.copy(positions = Nil), "positions: must not be empty")
  }

  test("pair positions are non-negative and inside the team") {
    def at(l: Int, r: Int) =
      coauthorPairCandidate.copy(positions =
        List(PairPosition(l, MentionId.of(workId, l), r, MentionId.of(workId, r)))
      )
    assertRejected(at(-1, 0), "positions[0].leftPosition: -1 must be >= 0")
    assertRejected(at(2, -1), "positions[0].rightPosition: -1 must be >= 0")
    assertRejected(at(3, 0), "positions[0].leftPosition: 3 must be < teamSize 3")
    assertRejected(at(2, 3), "positions[0].rightPosition: 3 must be < teamSize 3")
  }

  test("pair positions within one position pair are distinct") {
    val p = PairPosition(1, MentionId.of(workId, 1), 1, MentionId.of(workId, 1))
    assertRejected(
      coauthorPairCandidate.copy(positions = List(p)),
      "positions[0]: leftPosition and rightPosition must differ"
    )
  }

  test("pair mention IDs must be MentionId.of(workId, position)") {
    val p = coauthorPairCandidate.positions.head
    assertRejected(
      coauthorPairCandidate.copy(positions = List(p.copy(leftMentionId = sousaMention))),
      "positions[0].leftMentionId:"
    )
    assertRejected(
      coauthorPairCandidate.copy(positions = List(p.copy(rightMentionId = jorgeMention))),
      "positions[0].rightMentionId:"
    )
    assertRejected(
      coauthorPairCandidate.copy(positions = List(p.copy(leftMentionId = MentionId("m")))),
      "positions[0].leftMentionId: malformed stable ID"
    )
  }

  test("PairPosition.forNames attaches positions to the canonical names") {
    // A single record does not carry the team's names, so "left position holds the left name" is
    // a cross-record invariant; PairPosition.forNames is the producer helper that guarantees it.
    val p = coauthorPairCandidate.positions.head
    assertEquals(PairPosition.forNames(workId, jorgeName, 2, sousaName, 0), Right(p))
    assertEquals(PairPosition.forNames(workId, sousaName, 0, jorgeName, 2), Right(p))
    assert(PairPosition.forNames(workId, sousaName, 0, sousaName, 2).isLeft)
    assert(PairPosition.forNames(workId, jorgeName, 1, sousaName, 1).isLeft)
    assert(PairPosition.forNames(workId, jorgeName, -1, sousaName, 1).isLeft)
  }

  test("consolidated pair positions are sorted and distinct") {
    val ps = consolidatedPair.positions
    assertRejected(consolidatedPair.copy(positions = ps.reverse), "positions: must be sorted")
    assertRejected(
      consolidatedPair.copy(positions = List(ps.head, ps.head)),
      "positions: must be sorted"
    )
  }

  test("a position cannot carry both names of a pair across position pairs") {
    val bad = consolidatedPair.copy(
      positions = List(
        PairPosition(0, syntheticMention(0), 1, syntheticMention(1)),
        PairPosition(1, syntheticMention(1), 4, syntheticMention(4))
      )
    )
    assertRejected(bad, "positions: positions [1] appear on both the left and the right")
  }

  // ---- Phase 4: MatchCandidate --------------------------------------------------------------
  test("match candidate fixture is valid") {
    assertEquals(errorsOf(matchCandidate), Nil)
  }

  test("match candidate: mention pairs are in canonical order, no self pairs") {
    val swapped = matchCandidate.copy(left = matchCandidate.right, right = matchCandidate.left)
    assertRejected(swapped, "left/right: undirected pair")
    assertRejected(matchCandidate.copy(right = matchCandidate.left), "left/right: undirected pair")
  }

  test("match candidate subjects must match the subject kind") {
    assertRejected(matchCandidate.copy(left = workId.value), "left: '" + workId.value)
    val researcher = matchCandidate.copy(subjectKind = MatchSubjectKind.MentionResearcher)
    assertRejected(researcher, "right: '")
    val r = sousa.value
    val ok = MatchCandidate(
      1,
      MatchCandidateId
        .of(MatchSubjectKind.MentionResearcher, matchCandidate.left, r, "f", "k")
        .toOption
        .get,
      MatchSubjectKind.MentionResearcher,
      matchCandidate.left,
      r,
      "f",
      "k",
      matchCandidate.features,
      matchCandidate.provenance
    )
    assertEquals(errorsOf(ok), Nil)
  }

  test("match candidate ID must follow MatchCandidateId.of") {
    assertRejected(matchCandidate.copy(blockKey = "Other"), "candidateId:")
    assertRejected(matchCandidate.copy(blockFamily = "other-family"), "candidateId:")
    assertRejected(
      matchCandidate.copy(candidateId = MatchCandidateId("match-candidate:v1:zz")),
      "candidateId: malformed stable ID"
    )
  }

  test("match candidate block family and key are well-formed") {
    assertRejected(matchCandidate.copy(blockFamily = "Bad Family"), "blockFamily: 'Bad Family'")
    assertRejected(matchCandidate.copy(blockFamily = ""), "blockFamily:")
    assertRejected(matchCandidate.copy(blockKey = " "), "blockKey: must not be blank")
    assertRejected(
      matchCandidate.copy(blockKey = "a" + 0.toChar + "b"),
      "blockKey: must not contain"
    )
  }

  test("match candidate features: non-empty, named, finite, distinct, sorted by name") {
    val fs = matchCandidate.features
    assertRejected(matchCandidate.copy(features = Nil), "features: must not be empty")
    assertRejected(matchCandidate.copy(features = fs.reverse), "features: must be sorted")
    assertRejected(
      matchCandidate.copy(features = List(fs.head, fs.head)),
      "features.name: contains duplicates"
    )
    assertRejected(
      matchCandidate.copy(features = List(MatchFeature(" ", 1.0))),
      "features[0].name: must not be blank"
    )
    List(Double.NaN, Double.PositiveInfinity, Double.NegativeInfinity).foreach { v =>
      assertRejected(
        matchCandidate.copy(features = List(MatchFeature("x", v))),
        "features[0].value: must be finite"
      )
    }
    // Negative values are allowed (for example signed differences); only finiteness is required.
    assertEquals(errorsOf(matchCandidate.copy(features = List(MatchFeature("x", -1.0)))), Nil)
  }

  test("match candidate without provenance is rejected") {
    assertRejected(matchCandidate.copy(provenance = null), "provenance: missing (null)")
  }
}
