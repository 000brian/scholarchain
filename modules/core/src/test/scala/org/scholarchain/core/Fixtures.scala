package org.scholarchain.core

/** Valid instances of every contract record, built from the real dblp sample record
  * `conf/sigir/Sousa0J23` (dblp release 2026-11-01, CC0) and its OpenAlex enrichment
  * (`datasets/samples/`). Run fingerprints and code versions are labelled fixture values.
  */
object Fixtures {

  private def get[A](e: Either[String, A]): A =
    e.fold(m => throw new IllegalStateException(m), a => a)

  val window: YearWindow = YearWindow(2015, 2025)

  // ---- runs -----------------------------------------------------------------------------
  val configFingerprint: String = ContentHash.of("fixture configuration")
  val parseRun: RunId = get(
    RunId.of(
      "hadoop.parse-dblp",
      "fixture-code-version",
      ContentHash.of("fixture input manifest dblp-2026-11-01 dblp-subset-v1"),
      configFingerprint
    )
  )
  val enrichRun: RunId = get(
    RunId.of(
      "hadoop.link-openalex",
      "fixture-code-version",
      ContentHash.of("fixture input manifest openalex 2026-11-12"),
      configFingerprint
    )
  )
  val erRun: RunId = get(
    RunId.of(
      "entity-resolution.resolve",
      "fixture-code-version",
      ContentHash.of("fixture input curated mentions"),
      configFingerprint
    )
  )
  val graphRun: RunId = get(
    RunId.of(
      "spark.build-graph",
      "fixture-code-version",
      ContentHash.of("fixture input resolved authorships"),
      configFingerprint
    )
  )

  // ---- identifiers ----------------------------------------------------------------------
  val dblpKey = "conf/sigir/Sousa0J23"
  val workId: WorkId = WorkId.fromDblpKey(dblpKey)
  val title = "tieval: An Evaluation Framework for Temporal Information Extraction Systems."
  val sousaName = "Hugo O. Sousa"
  val camposName = "Ricardo Campos 0001"
  val jorgeName = "Alípio Mário Jorge"
  val authorNames: List[String] = List(sousaName, camposName, jorgeName)
  val sousaMention: MentionId = MentionId.of(workId, 0)
  val camposMention: MentionId = MentionId.of(workId, 1)
  // Each researcher's only fixture mention is its anchor (earliest mention).
  val sousa: ResearcherId = ResearcherId.fromAnchorMention(sousaMention)
  val campos: ResearcherId = ResearcherId.fromAnchorMention(camposMention)
  val openAlexWork = "W4366851189"
  val (edgeLeft, edgeRight) = get(UndirectedPair.canonical(sousa.value, campos.value))
  val edgeId: EdgeId = get(EdgeId.coauthor(sousa, campos, window))

  // ---- provenance -----------------------------------------------------------------------
  def dblpProvenance(sourceRecordIds: List[String]): Provenance = Provenance(
    schemaVersion = 1,
    sourceType = SourceType.Dblp,
    datasetId = "dblp",
    snapshotId = "2026-11-01",
    sourceRecordIds = sourceRecordIds,
    runId = parseRun,
    jobName = "hadoop.parse-dblp",
    policyVersion = "parse-policy/1",
    contentHash = None
  )

  /** OpenAlex API lookup; the snapshot ID is the retrieval date of `datasets/samples/openalex`. */
  def openAlexProvenance(sourceRecordIds: List[String]): Provenance = Provenance(
    schemaVersion = 1,
    sourceType = SourceType.OpenAlex,
    datasetId = "openalex",
    snapshotId = "2026-11-12",
    sourceRecordIds = sourceRecordIds,
    runId = enrichRun,
    jobName = "hadoop.link-openalex",
    policyVersion = "link-policy/1",
    contentHash = None
  )

  def pipelineProvenance(
      run: RunId,
      job: String,
      policy: String,
      sourceRecordIds: List[String],
      contentHash: Option[String] = None
  ): Provenance = Provenance(
    schemaVersion = 1,
    sourceType = SourceType.Pipeline,
    datasetId = "scholarchain-graph",
    snapshotId = graphRun.value,
    sourceRecordIds = sourceRecordIds,
    runId = run,
    jobName = job,
    policyVersion = policy,
    contentHash = contentHash
  )

  val provenance: Provenance = dblpProvenance(List(dblpKey))

  // ---- pipeline records -----------------------------------------------------------------
  val eeLink = "https://doi.org/10.1145/3539618.3591892"
  val tocUrl = "db/conf/sigir/sigir2023.html#Sousa0J23"

  val workRecord: WorkRecord = WorkRecord(
    schemaVersion = 2,
    workId = workId,
    dblpKey = dblpKey,
    publicationType = "inproceedings",
    title = title,
    year = 2023,
    venueKey = Some("conf/sigir"),
    doi = Some("10.1145/3539618.3591892"),
    eeLinks = List(eeLink),
    urls = List(tocUrl),
    authorNames = authorNames,
    provenance = provenance
  )

  val authorMention: AuthorMention = AuthorMention(
    schemaVersion = 1,
    mentionId = sousaMention,
    workId = workId,
    position = 0,
    name = sousaName,
    orcid = Some("0000-0003-3226-9189"),
    provenance = provenance
  )

  // ---- external identifier links (OpenAlex work W4366851189, authorship 0) ---------------
  def link(
      kind: LinkSubjectKind,
      subject: String,
      t: ExternalIdType,
      id: String
  ): ExternalIdLink = {
    val p = openAlexProvenance(List(openAlexWork))
    ExternalIdLink(
      schemaVersion = 1,
      linkId = LinkId.of(kind, subject, t, id, p.sourceType, p.datasetId, p.snapshotId),
      subjectKind = kind,
      subjectId = subject,
      idType = t,
      externalId = id,
      provenance = p
    )
  }

  val externalIdLink: ExternalIdLink =
    link(LinkSubjectKind.Mention, sousaMention.value, ExternalIdType.OpenAlexAuthor, "A5080716895")
  val workLink: ExternalIdLink =
    link(LinkSubjectKind.Work, workId.value, ExternalIdType.OpenAlexWork, openAlexWork)
  val venueLink: ExternalIdLink =
    link(LinkSubjectKind.Venue, "conf/sigir", ExternalIdType.OpenAlexSource, "S4363608773")
  val institutionLink: ExternalIdLink =
    link(
      LinkSubjectKind.Mention,
      sousaMention.value,
      ExternalIdType.OpenAlexInstitution,
      "I182534213"
    )

  def fromOpenAlex(t: ExternalIdType, id: String): SourcedExternalId =
    SourcedExternalId(t, id, SourceType.OpenAlex, "openalex", "2026-11-12", openAlexWork)

  val canonicalResearcher: CanonicalResearcher = CanonicalResearcher(
    schemaVersion = 1,
    researcherId = sousa,
    anchorMentionId = sousaMention,
    displayName = sousaName,
    dblpNames = List(sousaName),
    externalIds = List(
      fromOpenAlex(ExternalIdType.OpenAlexAuthor, "A5080716895"),
      fromOpenAlex(ExternalIdType.OpenAlexInstitution, "I182534213"),
      fromOpenAlex(ExternalIdType.OpenAlexInstitution, "I4210166615"),
      // dblp itself publishes this ORCID on <author orcid=...>: dblp provenance is correct.
      SourcedExternalId(
        ExternalIdType.Orcid,
        "0000-0003-3226-9189",
        SourceType.Dblp,
        "dblp",
        "2026-11-01",
        dblpKey
      ),
      fromOpenAlex(ExternalIdType.Orcid, "0000-0003-3226-9189")
    ),
    provenance = pipelineProvenance(
      erRun,
      "entity-resolution.resolve",
      "match-policy/1",
      List(sousaMention.value, externalIdLink.linkId.value)
    )
  )

  val authorshipRecord: AuthorshipRecord = AuthorshipRecord(
    schemaVersion = 1,
    researcherId = sousa,
    workId = workId,
    mentionId = sousaMention,
    position = 0,
    year = 2023,
    matchConfidence = 0.97,
    provenance =
      pipelineProvenance(erRun, "entity-resolution.resolve", "match-policy/1", List(dblpKey))
  )

  val publicationTeam: PublicationTeam = PublicationTeam(
    schemaVersion = 1,
    workId = workId,
    year = 2023,
    authorNames = authorNames,
    teamSize = 3,
    provenance = provenance
  )

  // Jorge (position 2) sorts before Sousa (position 0): positions follow the canonical names.
  val jorgeMention: MentionId = MentionId.of(workId, 2)
  val coauthorPairCandidate: CoauthorPairCandidate = CoauthorPairCandidate(
    schemaVersion = 2,
    workId = workId,
    year = 2023,
    left = jorgeName,
    right = sousaName,
    positions = List(PairPosition(2, jorgeMention, 0, sousaMention)),
    teamSize = 3,
    provenance = provenance
  )

  // ---- synthetic homonym record conf/sigir/SyntheticA24 (datasets/samples, SYNTHETIC) ------
  // Authors: 0 "Synthetic Author 0001", 1 "Synthetic Author 0002", 2, 3, 4 "Synthetic Author 0001".
  val syntheticKey = "conf/sigir/SyntheticA24"
  val syntheticWork: WorkId = WorkId.fromDblpKey(syntheticKey)
  def syntheticMention(position: Int): MentionId = MentionId.of(syntheticWork, position)

  /** One work and name pair supported by two position pairs (the same name at positions 0 and 4).
    */
  val consolidatedPair: CoauthorPairCandidate = CoauthorPairCandidate(
    schemaVersion = 2,
    workId = syntheticWork,
    year = 2024,
    left = "Synthetic Author 0001",
    right = "Synthetic Author 0002",
    positions = List(
      PairPosition(0, syntheticMention(0), 1, syntheticMention(1)),
      PairPosition(4, syntheticMention(4), 1, syntheticMention(1))
    ),
    teamSize = 5,
    provenance = dblpProvenance(List(syntheticKey))
  )

  // ---- match candidate (SYNTHETIC mentions sharing the dblp base name "Synthetic Author") ---
  val candidateFeatureRun: RunId = get(
    RunId.of(
      "hadoop.j06-candidate-features",
      "fixture-code-version",
      ContentHash.of("fixture input candidate blocks"),
      configFingerprint
    )
  )
  val (candidateLeft, candidateRight) = get(
    UndirectedPair.canonical(
      syntheticMention(1).value,
      MentionId.of(WorkId.fromDblpKey("journals/pvldb/SyntheticE15"), 0).value
    )
  )

  val matchCandidate: MatchCandidate = MatchCandidate(
    schemaVersion = 1,
    candidateId = get(
      MatchCandidateId.of(
        MatchSubjectKind.MentionPair,
        candidateLeft,
        candidateRight,
        "fixture-block-family",
        "Synthetic Author"
      )
    ),
    subjectKind = MatchSubjectKind.MentionPair,
    left = candidateLeft,
    right = candidateRight,
    blockFamily = "fixture-block-family",
    blockKey = "Synthetic Author",
    // Labelled fixture feature names: which features exist is entity-resolution policy.
    features = List(MatchFeature("fixture_feature_a", 1.0), MatchFeature("fixture_feature_b", 9.0)),
    provenance = pipelineProvenance(
      candidateFeatureRun,
      "hadoop.j06-candidate-features",
      "fixture-feature-policy/1",
      List(candidateLeft, candidateRight)
    )
  )

  val coauthorEdge: CoauthorEdge = CoauthorEdge(
    schemaVersion = 1,
    edgeId = edgeId,
    left = ResearcherId(edgeLeft),
    right = ResearcherId(edgeRight),
    window = window,
    weight = 0.5,
    sharedWorkCount = 1,
    firstYear = 2023,
    lastYear = 2023,
    supportingWorkIds = List(workId),
    minIdentityConfidence = 0.97,
    provenance = pipelineProvenance(graphRun, "spark.build-graph", "graph-policy/1", List(dblpKey))
  )

  val researcherMetrics: ResearcherMetrics = ResearcherMetrics(
    schemaVersion = 1,
    researcherId = sousa,
    window = window,
    values = List(
      MetricValue(CollaborationMetric.DistinctCoauthors, 2.0),
      MetricValue(CollaborationMetric.CoauthoredWorks, 1.0),
      MetricValue(CollaborationMetric.WeightedDegree, 1.0)
    ),
    identityConfidence = 0.97,
    provenance =
      pipelineProvenance(graphRun, "spark.researcher-metrics", "graph-policy/1", List(sousa.value))
  )

  /** Campos's component in the full window: {Sousa, Campos}; Sousa's ID is the smaller one. */
  val researcherGroupMembership: ResearcherGroupMembership = ResearcherGroupMembership(
    schemaVersion = 1,
    researcherId = ResearcherId(edgeRight),
    window = window,
    groupKind = GroupKind.Component,
    groupId = ResearcherId(edgeLeft),
    groupSize = 2,
    provenance = pipelineProvenance(graphRun, "spark.components", "graph-policy/1", List(edgeRight))
  )

  val venue: Venue = Venue(
    schemaVersion = 1,
    venueKey = "conf/sigir",
    name = "SIGIR",
    provenance = dblpProvenance(List("conf/sigir/2023"))
  )

  val institution: Institution = Institution(
    schemaVersion = 1,
    institutionId = "I182534213",
    name = "Universidade do Porto",
    countryCode = Some("PT"),
    provenance = openAlexProvenance(List("I182534213"))
  )

  val topic: Topic = Topic(
    schemaVersion = 1,
    topicId = "fixture-topic-temporal-information-extraction",
    name = "fixture topic name (not from a source dataset)",
    provenance = pipelineProvenance(graphRun, "spark.topics", "topic-policy/1", List(dblpKey))
  )

  // ---- match decisions (v2) and LLM adjudication (labelled fixture policy, rule and code names) --
  val matchPolicy = "fixture-match-policy/1"
  val decisionTime = "2026-11-14T00:00:00Z"

  /** A pairwise decision of the fixture candidate (probabilistic path). */
  val matchDecision: MatchDecision = MatchDecision(
    schemaVersion = 2,
    subjectKind = MatchSubjectKind.MentionPair,
    left = candidateLeft,
    right = candidateRight,
    candidateId = Some(matchCandidate.candidateId),
    outcome = MatchOutcome.Rejected,
    matchPath = MatchPath.Probabilistic,
    confidence = 0.83,
    features = matchCandidate.features,
    rulesFired = List("fixture-score-below-reject"),
    reasonCodes = List("FIXTURE_LOW_SCORE"),
    modelVersion = None,
    promptVersion = None,
    policyVersion = matchPolicy,
    decisionTime = decisionTime,
    provenance = pipelineProvenance(
      erRun,
      "entity-resolution.resolve",
      matchPolicy,
      List(candidateLeft, candidateRight)
    )
  )

  /** The identity assignment of one mention to its cluster (decision 5): no candidate. */
  val identityAssignment: MatchDecision = MatchDecision(
    schemaVersion = 2,
    subjectKind = MatchSubjectKind.MentionResearcher,
    left = sousaMention.value,
    right = sousa.value,
    candidateId = None,
    outcome = MatchOutcome.Accepted,
    matchPath = MatchPath.Deterministic,
    confidence = 1.0,
    features = Nil,
    rulesFired = List("fixture-dblp-prior"),
    reasonCodes = Nil,
    modelVersion = None,
    promptVersion = None,
    policyVersion = matchPolicy,
    decisionTime = decisionTime,
    provenance = pipelineProvenance(
      erRun,
      "entity-resolution.resolve",
      matchPolicy,
      List(sousaMention.value)
    )
  )

  val modelVersion = "fixture-model-version"
  val promptVersion = "fixture-adjudication-prompt/1"

  /** A pairwise decision after the LLM abstained (llm path, unresolved). */
  val llmDecision: MatchDecision = matchDecision.copy(
    outcome = MatchOutcome.Unresolved,
    matchPath = MatchPath.Llm,
    confidence = 0.5,
    rulesFired = List("fixture-llm-band"),
    reasonCodes = List("FIXTURE_LLM_ABSTAINED"),
    modelVersion = Some(modelVersion),
    promptVersion = Some(promptVersion)
  )

  // Two mention-researcher candidates for one mention, offered to the adjudicator.
  def researcherCandidate(r: ResearcherId): MatchCandidateId = get(
    MatchCandidateId.of(
      MatchSubjectKind.MentionResearcher,
      candidateLeft,
      r.value,
      "fixture-block-family",
      "Synthetic Author"
    )
  )
  val offeredCandidates: List[MatchCandidateId] =
    List(researcherCandidate(sousa), researcherCandidate(campos))
      .sortBy(_.value)(CodePointOrder.ordering)
  val chosenCandidate: MatchCandidateId = researcherCandidate(campos)
  val adjudicationInput = "fixture adjudication input (not a real prompt or source record)"
  val adjudicationInputHash: String = ContentHash.of(adjudicationInput)

  val llmAdjudication: LlmAdjudication = LlmAdjudication(
    schemaVersion = 1,
    adjudicationId =
      get(LlmAdjudicationId.of(adjudicationInputHash, promptVersion, modelVersion, matchPolicy)),
    inputHash = adjudicationInputHash,
    offeredCandidateIds = offeredCandidates,
    outcome = AdjudicationOutcome.Accept,
    chosenCandidateId = Some(chosenCandidate),
    citedInputFields = List("candidates[1].coauthors", "mention.coauthors"),
    confidence = 0.9,
    reasonCodes = List("FIXTURE_SHARED_COAUTHORS"),
    promptVersion = promptVersion,
    modelVersion = modelVersion,
    policyVersion = matchPolicy,
    provenance = pipelineProvenance(
      erRun,
      "entity-resolution.llm-adjudicate",
      matchPolicy,
      offeredCandidates.map(_.value),
      Some(adjudicationInputHash)
    )
  )

  val graphNode: GraphNode = GraphNode(
    schemaVersion = 1,
    nodeId = sousa.value,
    label = GraphNodeLabel.Researcher,
    displayName = sousaName,
    properties = Map("orcid" -> "0000-0003-3226-9189"),
    provenance =
      pipelineProvenance(graphRun, "spark.build-graph", "graph-policy/1", List(sousa.value))
  )

  val graphEdge: GraphEdge = GraphEdge(
    schemaVersion = 1,
    edgeId = edgeId,
    label = GraphEdgeLabel.CoauthoredWith,
    from = edgeLeft,
    to = edgeRight,
    window = window,
    weight = Some(0.5),
    provenance = pipelineProvenance(graphRun, "spark.build-graph", "graph-policy/1", List(dblpKey))
  )

  // ---- semantic documents and vector metadata (Phase 7) ---------------------------------
  val windowLabel = "2015-2025"
  val profileText = "Hugo O. Sousa published 1 work in 2015-2025 with 2 distinct coauthors."
  val profileHash: String = ContentHash.of(profileText)
  val profileChunk: ChunkId = get(
    ChunkId.forSubjects(
      SemanticDocumentType.ResearcherProfile,
      List(sousa.value),
      Some(windowLabel),
      0,
      profileHash
    )
  )
  val profileIndex = "researcher-profile-v1"
  val profileVectorKey: String = s"rp:$windowLabel:${sousa.value}"
  val profileEvidence: EvidenceId = EvidenceId.of(EvidenceType.Metric, graphRun, List(sousa.value))
  val ingestRun: RunId = get(
    RunId.of(
      "rag.ingest-semantic",
      "fixture-code-version",
      ContentHash.of("fixture input semantic summaries"),
      configFingerprint
    )
  )

  val semanticDocument: SemanticDocument = SemanticDocument(
    schemaVersion = 1,
    documentId = profileChunk,
    documentType = SemanticDocumentType.ResearcherProfile,
    indexName = profileIndex,
    vectorKey = profileVectorKey,
    subjectIds = List(sousa.value),
    window = Some(window),
    text = profileText,
    sourceEvidenceId = Some(profileEvidence),
    provenance = pipelineProvenance(
      ingestRun,
      "rag.render-documents",
      "researcher-profile-template/1",
      List(profileEvidence.value),
      Some(profileHash)
    )
  )

  val vectorMetadata: VectorMetadata = VectorMetadata(
    schemaVersion = 1,
    vectorKey = profileVectorKey,
    indexName = profileIndex,
    documentId = profileChunk,
    documentType = SemanticDocumentType.ResearcherProfile,
    researcherId = Some(sousa),
    communityId = None,
    startResearcherId = None,
    endResearcherId = None,
    workId = None,
    topicId = None,
    institutionId = None,
    windowStartYear = Some(2015),
    windowEndYear = Some(2025),
    year = None,
    venueKey = None,
    pathLength = None,
    sourceDataset = "dblp",
    textS3Uri = "s3://example-semantic-context-bucket/semantic-context/text/" +
      profileHash.stripPrefix(ContentHash.Prefix) + ".txt",
    textHash = profileHash,
    sourceEvidenceId = Some(profileEvidence),
    embeddingModel = "fixture-embedding-model",
    embeddingDimensions = 1024,
    runId = ingestRun,
    provenance = pipelineProvenance(
      ingestRun,
      "storage.vector-ingest",
      "vector-ingest-policy/1",
      List(profileChunk.value),
      Some(profileHash)
    )
  )

  // ---- runtime records ------------------------------------------------------------------
  val question = "Who has Hugo O. Sousa collaborated with between 2015 and 2025?"
  val queryRun: RunId = get(
    RunId.of("rag.answer", "fixture-code-version", ContentHash.of(question), configFingerprint)
  )

  def runtimeProvenance(sourceRecordIds: List[String], text: Option[String]): Provenance =
    Provenance(
      schemaVersion = 1,
      sourceType = SourceType.Runtime,
      datasetId = "scholarchain-graph",
      snapshotId = graphRun.value,
      sourceRecordIds = sourceRecordIds,
      runId = queryRun,
      jobName = "rag.answer",
      policyVersion = "prompt-policy/1",
      contentHash = text.map(ContentHash.of)
    )

  val workEvidence: EvidenceItem = EvidenceItem(
    schemaVersion = 1,
    evidenceId = EvidenceId.of(EvidenceType.Work, queryRun, List(workId.value)),
    evidenceType = EvidenceType.Work,
    subjectIds = List(workId.value),
    window = None,
    metric = None,
    snippet = Some(title),
    provenance = runtimeProvenance(List(dblpKey), Some(title))
  )

  val metricEvidence: EvidenceItem = EvidenceItem(
    schemaVersion = 1,
    evidenceId = EvidenceId.of(EvidenceType.Metric, queryRun, List(sousa.value)),
    evidenceType = EvidenceType.Metric,
    subjectIds = List(sousa.value),
    window = Some(window),
    metric = Some(MetricValue(CollaborationMetric.DistinctCoauthors, 2.0)),
    snippet = None,
    provenance = runtimeProvenance(List(sousa.value), None)
  )

  val evidenceItem: EvidenceItem = workEvidence

  val evidenceBundle: EvidenceBundle = EvidenceBundle(
    schemaVersion = 1,
    bundleId =
      EvidenceId.forBundle(queryRun, List(workEvidence.evidenceId, metricEvidence.evidenceId)),
    items = List(workEvidence, metricEvidence),
    provenance = runtimeProvenance(List(workId.value, sousa.value), None)
  )

  val queryPlan: QueryPlan = QueryPlan(
    schemaVersion = 1,
    question = question,
    graphRunId = graphRun,
    steps = List(
      QueryStep(QueryOperation.ResearcherLookup, List(sousa), None, Map.empty),
      QueryStep(
        QueryOperation.CollaborationBreadth,
        List(sousa),
        Some(window),
        Map("metric" -> "distinct_coauthors")
      )
    ),
    provenance = runtimeProvenance(List(sousa.value), Some(question))
  )

  val claimText = "Hugo O. Sousa has 2 distinct coauthors in 2015-2025."

  val answerClaim: AnswerClaim = AnswerClaim(
    schemaVersion = 2,
    claimId = "claim-1",
    claimText = claimText,
    evidenceIds = List(metricEvidence.evidenceId),
    status = VerificationStatus.Verified,
    window = Some(window),
    uncertainty = None,
    provenance = runtimeProvenance(List(sousa.value), Some(claimText))
  )

  val answerText =
    "Between 2015 and 2025 Hugo O. Sousa has 2 distinct coauthors in the ScholarChain graph."

  val answerResponse: AnswerResponse = AnswerResponse(
    schemaVersion = 2,
    question = question,
    answerText = answerText,
    modelId = Some("example-answer-model"),
    plan = queryPlan,
    bundle = Some(evidenceBundle),
    claims = List(answerClaim),
    identityNotes = List("Hugo O. Sousa resolved with confidence 0.97 (match-policy/1)."),
    abstained = false,
    abstentionReason = None,
    provenance = runtimeProvenance(List(sousa.value), Some(answerText))
  )

  // ---- Phase 9 runtime records ----------------------------------------------------------

  /** A claim the verifier could not support: cites nothing and states its uncertainty. */
  val unsupportedClaimText = "Hugo O. Sousa is the most central researcher in the graph."
  val unsupportedClaim: AnswerClaim = AnswerClaim(
    schemaVersion = 2,
    claimId = "claim-2",
    claimText = unsupportedClaimText,
    evidenceIds = Nil,
    status = VerificationStatus.Unsupported,
    window = None,
    uncertainty = Some("No centrality evidence exists in the bundle."),
    provenance = runtimeProvenance(List(sousa.value), Some(unsupportedClaimText))
  )

  val ambiguousQuestion =
    "Which researcher named Wei Wang has the most distinct coauthors in 2015-2025?"
  val ambiguousPlan: QueryPlan = QueryPlan(
    schemaVersion = 1,
    question = ambiguousQuestion,
    graphRunId = graphRun,
    steps = List(
      QueryStep(
        QueryOperation.ResearcherRanking,
        Nil,
        Some(window),
        Map("metric" -> "distinct_coauthors", "limit" -> "10")
      )
    ),
    provenance = runtimeProvenance(List(graphRun.value), Some(ambiguousQuestion))
  )

  val abstainText =
    "The name 'Wei Wang' matches several researchers in the ScholarChain graph, so no answer is " +
      "given for it."

  /** An abstaining response: no bundle, no claims, a reason. */
  val abstainedResponse: AnswerResponse = AnswerResponse(
    schemaVersion = 2,
    question = ambiguousQuestion,
    answerText = abstainText,
    modelId = None,
    plan = ambiguousPlan,
    bundle = None,
    claims = Nil,
    identityNotes = List("'Wei Wang' has several candidate researchers; none was chosen."),
    abstained = true,
    abstentionReason = Some("ambiguous researcher name"),
    provenance = runtimeProvenance(List(graphRun.value), Some(abstainText))
  )

  /** A plan using every Phase 9 operation (researcher counts per operation rule). */
  val runtimeQuestion =
    "Compare Hugo O. Sousa and Ricardo Campos 0001 across windows, with their team sizes, " +
      "communities, the top coauthor-breadth ranking and some context."
  val runtimeOperationsPlan: QueryPlan = QueryPlan(
    schemaVersion = 1,
    question = runtimeQuestion,
    graphRunId = graphRun,
    steps = List(
      QueryStep(
        QueryOperation.ResearcherRanking,
        Nil,
        Some(window),
        Map("limit" -> "10", "metric" -> "distinct_coauthors")
      ),
      QueryStep(QueryOperation.MaxTeamSize, List(sousa), Some(window), Map.empty),
      QueryStep(QueryOperation.CommunityAnalysis, List(sousa, campos), Some(window), Map.empty),
      QueryStep(
        QueryOperation.TemporalComparison,
        List(sousa),
        Some(YearWindow(2015, 2019)),
        Map("metric" -> "distinct_coauthors")
      ),
      QueryStep(QueryOperation.SemanticContext, Nil, Some(window), Map("topic" -> "temporal"))
    ),
    provenance = runtimeProvenance(List(sousa.value, campos.value), Some(runtimeQuestion))
  )

  /** A semantic-context evidence item: a pointer to the researcher-profile chunk, no window rule.
    */
  val semanticEvidence: EvidenceItem = EvidenceItem(
    schemaVersion = 1,
    evidenceId =
      EvidenceId.of(EvidenceType.SemanticContext, queryRun, List(profileChunk.value, sousa.value)),
    evidenceType = EvidenceType.SemanticContext,
    subjectIds = List(profileChunk.value, sousa.value),
    window = Some(window),
    metric = None,
    snippet = None,
    provenance = runtimeProvenance(List(profileVectorKey, profileChunk.value), None)
  )

  val plannerPromptText = "fixture query-planner prompt text"
  val synthesisPromptText = "fixture answer-synthesis prompt text"

  val queryAudit: QueryAudit = QueryAudit(
    schemaVersion = 1,
    queryRunId = queryRun,
    questionHash = ContentHash.of(question),
    plan = queryPlan,
    planSource = PlanSource.Rule,
    resolvedResearcherIds = List(sousa),
    unresolvedNames = Nil,
    evidenceIds = List(workEvidence.evidenceId, metricEvidence.evidenceId),
    bundleId = Some(evidenceBundle.bundleId),
    graphRunId = graphRun,
    metricPolicyVersions = List("metric-policy/1"),
    promptVersions = List(
      QueryAudit.promptVersion("query-planner-v1", plannerPromptText),
      QueryAudit.promptVersion("answer-synthesis-v1", synthesisPromptText)
    ),
    modelId = Some("example-answer-model"),
    inputTokens = Some(1200L),
    outputTokens = Some(180L),
    latencyMillis = 42L,
    stageMillis = Map("planner" -> 3L, "resolver" -> 5L, "verification" -> 1L),
    verificationCounts =
      Map("verified" -> 1, "partially_supported" -> 0, "unsupported" -> 0, "conflicting" -> 0),
    answerHash = ContentHash.of(answerText),
    abstained = false,
    provenance = runtimeProvenance(List(sousa.value, evidenceBundle.bundleId.value), None)
  )

  /** QueryAudit v2 (Phase 11): the v1 fixture plus the explain fields. `cacheHits` keys and the
    * task names are examples of the producer's vocabulary, not part of the contract.
    */
  val queryAuditV2: QueryAudit = queryAudit.copy(
    schemaVersion = 2,
    queryTemplateIds = List("researcher_by_id", "coauthor_neighbors"),
    indexesQueried = List("researcher-profile-v1"),
    metadataFilterKeys = List("windowStartYear", "windowEndYear"),
    modelCalls = Map(
      "planner" -> ModelCallCounts(1L, 0L, 0L, 0L, 0L),
      "synthesis" -> ModelCallCounts(2L, 1L, 1L, 1L, 0L)
    ),
    cacheHits = Map("researcher-metric-cache" -> 2L)
  )
}
