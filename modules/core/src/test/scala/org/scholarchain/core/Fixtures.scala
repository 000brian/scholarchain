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

  val matchDecision: MatchDecision = MatchDecision(
    schemaVersion = 1,
    mentionId = sousaMention,
    candidateResearcherId = Some(sousa),
    outcome = MatchOutcome.Match,
    confidence = 0.97,
    features = List(MatchFeature("orcid_equal", 1.0), MatchFeature("name_similarity", 1.0)),
    provenance =
      pipelineProvenance(erRun, "entity-resolution.resolve", "match-policy/1", List(dblpKey))
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
    schemaVersion = 1,
    claimText = claimText,
    evidenceIds = List(metricEvidence.evidenceId),
    status = VerificationStatus.Verified,
    window = Some(window),
    provenance = runtimeProvenance(List(sousa.value), Some(claimText))
  )

  val answerText =
    "Between 2015 and 2025 Hugo O. Sousa has 2 distinct coauthors in the ScholarChain graph."

  val answerResponse: AnswerResponse = AnswerResponse(
    schemaVersion = 1,
    question = question,
    answerText = answerText,
    modelId = Some("example-answer-model"),
    plan = queryPlan,
    bundle = evidenceBundle,
    claims = List(answerClaim),
    identityNotes = List("Hugo O. Sousa resolved with confidence 0.97 (match-policy/1)."),
    provenance = runtimeProvenance(List(sousa.value), Some(answerText))
  )
}
