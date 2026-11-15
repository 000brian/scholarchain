package org.scholarchain.core

import upickle.default.{macroRW, ReadWriter}

// Immutable domain records shared by every pipeline stage and the runtime.
//
// Wire format: JSON via uPickle. Field names are the Scala field names (camelCase). Every field
// is required on the wire; `Option` fields are written as `null` when empty. Stable IDs and
// enums are JSON strings. Every record that represents data carries `schemaVersion` and a
// required `provenance`; small value objects (YearWindow, MetricValue, MatchFeature, QueryStep)
// are embedded in such records and carry neither.
//
// Validation rules live in `Validator`; `Contract.decode` and `Contract.encode` always apply them.
//
// One record, one source: a record's `provenance` names the single source dataset and snapshot of
// every fact it carries. dblp-sourced records carry only what dblp publishes (including DOIs from
// `<ee>` and ORCID iDs from `<author orcid=...>`). Identifiers from enrichment sources (OpenAlex,
// Crossref, ORCID) travel in `ExternalIdLink` records with their own provenance. Records that
// aggregate several sources (`CanonicalResearcher`) carry each external identifier as a
// `SourcedExternalId` naming its source dataset, snapshot, and source record.

/** Inclusive publication-year window `[startYear, endYear]`; every metric states one. */
final case class YearWindow(startYear: Int, endYear: Int) {
  def contains(year: Int): Boolean = year >= startYear && year <= endYear
}
object YearWindow { implicit val readWriter: ReadWriter[YearWindow] = macroRW }

/** Provenance of a record or claim. Required everywhere; never defaulted.
  *
  * @param datasetId
  *   dataset identifier from `datasets/dataset-manifest.yaml` (e.g. `dblp`) or a ScholarChain
  *   derived dataset (e.g. `scholarchain-graph`)
  * @param snapshotId
  *   source snapshot, release, or retrieval date (e.g. `2026-11-01`)
  * @param sourceRecordIds
  *   source record IDs (dblp key, DOI, ORCID iD, OpenAlex ID, or upstream stable IDs); non-empty
  * @param runId
  *   stable ID of the job or runtime request that produced the record
  * @param jobName
  *   producing job or component, e.g. `hadoop.parse-dblp`
  * @param policyVersion
  *   version of the policy applied (parse, match, graph, prompt policy)
  * @param contentHash
  *   `sha256:<hex>` of the text involved; required where a record carries text (see Validator)
  */
final case class Provenance(
    schemaVersion: Int,
    sourceType: SourceType,
    datasetId: String,
    snapshotId: String,
    sourceRecordIds: List[String],
    runId: RunId,
    jobName: String,
    policyVersion: String,
    contentHash: Option[String]
)
object Provenance { implicit val readWriter: ReadWriter[Provenance] = macroRW }

/** A publication as published by dblp. `workId == WorkId.fromDblpKey(dblpKey)`. Schema version 2.
  *
  * `provenance.sourceType` must be `dblp`; `doi` is the DOI from the record's `<ee>` resolver URL
  * (`Doi.fromDblpEe` of one of `eeLinks`). Identifiers from other sources (OpenAlex work IDs,
  * Crossref DOIs) are [[ExternalIdLink]]s.
  *
  * @param eeLinks
  *   the record's `<ee>` element texts (entity-decoded, trimmed), in source order, each once (a
  *   repeated value keeps its first position); empty when the record has none. Not normalized: the
  *   DOI form is `doi`.
  * @param urls
  *   the record's `<url>` element texts (dblp table-of-contents paths such as
  *   `db/conf/sigir/sigir2023.html#Sousa0J23`), same rules as `eeLinks`. Not an identifier.
  */
final case class WorkRecord(
    schemaVersion: Int,
    workId: WorkId,
    dblpKey: String,
    publicationType: String,
    title: String,
    year: Int,
    venueKey: Option[String],
    doi: Option[String],
    eeLinks: List[String],
    urls: List[String],
    authorNames: List[String],
    provenance: Provenance
)
object WorkRecord { implicit val readWriter: ReadWriter[WorkRecord] = macroRW }

/** One author slot of one dblp work as written by dblp, before entity resolution.
  *
  * `provenance.sourceType` must be `dblp`; `orcid` is dblp's `<author orcid=...>` attribute. ORCID
  * iDs and OpenAlex author or institution IDs from other sources are [[ExternalIdLink]]s.
  */
final case class AuthorMention(
    schemaVersion: Int,
    mentionId: MentionId,
    workId: WorkId,
    position: Int,
    name: String,
    orcid: Option[String],
    provenance: Provenance
)
object AuthorMention { implicit val readWriter: ReadWriter[AuthorMention] = macroRW }

/** An external identifier together with the source dataset snapshot and source record that assert
  * it. `sourceType` must be one of `idType.sources`.
  *
  * @param snapshotId
  *   source snapshot, release, or retrieval date (OpenAlex API lookups use the retrieval date)
  * @param sourceRecordId
  *   the source record asserting the identifier (dblp key, OpenAlex work ID, DOI, ORCID iD)
  */
final case class SourcedExternalId(
    idType: ExternalIdType,
    value: String,
    sourceType: SourceType,
    datasetId: String,
    snapshotId: String,
    sourceRecordId: String
)
object SourcedExternalId {
  implicit val readWriter: ReadWriter[SourcedExternalId] = macroRW

  /** Canonical order: idType, value, sourceType, datasetId, snapshotId, sourceRecordId (wire names
    * and strings compared by code point).
    */
  val ordering: Ordering[SourcedExternalId] = new Ordering[SourcedExternalId] {
    private def key(x: SourcedExternalId): List[String] =
      List(x.idType.wire, x.value, x.sourceType.wire, x.datasetId, x.snapshotId, x.sourceRecordId)
    def compare(a: SourcedExternalId, b: SourcedExternalId): Int =
      key(a)
        .zip(key(b))
        .map { case (x, y) => CodePointOrder.compare(x, y) }
        .find(_ != 0)
        .getOrElse(0)
  }
}

/** A resolved researcher (an entity-resolution cluster of mentions).
  *
  * `researcherId == ResearcherId.fromAnchorMention(anchorMentionId)`; the anchor is the cluster's
  * earliest mention (see [[ResearcherId]]). `dblpNames` is sorted by code point, distinct, and
  * non-empty; it is descriptive and not an ID input. `externalIds` holds the researcher's ORCID iD
  * (at most one distinct value), OpenAlex author IDs, and OpenAlex institution IDs, each with the
  * source snapshot that asserts it; it is distinct and sorted by [[SourcedExternalId.ordering]].
  */
final case class CanonicalResearcher(
    schemaVersion: Int,
    researcherId: ResearcherId,
    anchorMentionId: MentionId,
    displayName: String,
    dblpNames: List[String],
    externalIds: List[SourcedExternalId],
    provenance: Provenance
)
object CanonicalResearcher { implicit val readWriter: ReadWriter[CanonicalResearcher] = macroRW }

/** Links a resolved researcher to a work through one mention. */
final case class AuthorshipRecord(
    schemaVersion: Int,
    researcherId: ResearcherId,
    workId: WorkId,
    mentionId: MentionId,
    position: Int,
    year: Int,
    matchConfidence: Double,
    provenance: Provenance
)
object AuthorshipRecord { implicit val readWriter: ReadWriter[AuthorshipRecord] = macroRW }

/** The author list of one work, in source order. */
final case class PublicationTeam(
    schemaVersion: Int,
    workId: WorkId,
    year: Int,
    authorNames: List[String],
    teamSize: Int,
    provenance: Provenance
)
object PublicationTeam { implicit val readWriter: ReadWriter[PublicationTeam] = macroRW }

/** The author positions of one supporting occurrence of a coauthor pair.
  *
  * `leftPosition` is the 0-based author position of the pair's `left` name and `rightPosition` that
  * of its `right` name, so positions follow the name canonicalization of [[CoauthorPairCandidate]]
  * (the left position can be greater than the right one). Mention IDs are
  * `MentionId.of(workId, position)`. Not a wire record on its own (no provenance).
  */
final case class PairPosition(
    leftPosition: Int,
    leftMentionId: MentionId,
    rightPosition: Int,
    rightMentionId: MentionId
)
object PairPosition {
  implicit val readWriter: ReadWriter[PairPosition] = macroRW

  /** Order of `CoauthorPairCandidate.positions`: by leftPosition, then rightPosition. */
  val ordering: Ordering[PairPosition] = new Ordering[PairPosition] {
    def compare(a: PairPosition, b: PairPosition): Int = {
      val l = Integer.compare(a.leftPosition, b.leftPosition)
      if (l != 0) l else Integer.compare(a.rightPosition, b.rightPosition)
    }
  }

  /** Producer helper: the position pair for author `nameA` at `positionA` and `nameB` at
    * `positionB` of `workId`, with the positions attached to the canonical (left, right) names.
    * `Left` for equal names (self pair), equal positions, or a negative position.
    */
  def forNames(
      workId: WorkId,
      nameA: String,
      positionA: Int,
      nameB: String,
      positionB: Int
  ): Either[String, PairPosition] =
    if (positionA < 0 || positionB < 0) Left(s"negative position ($positionA, $positionB)")
    else if (positionA == positionB) Left(s"equal positions ($positionA)")
    else
      UndirectedPair.canonical(nameA, nameB).map { case (left, _) =>
        val (l, r) = if (left == nameA) (positionA, positionB) else (positionB, positionA)
        PairPosition(l, MentionId.of(workId, l), r, MentionId.of(workId, r))
      }
}

/** Unordered coauthor pair of mention names from one work; `left < right` by code point. Schema
  * version 2.
  *
  * `positions` lists every supporting position pair, sorted by [[PairPosition.ordering]] and
  * distinct. A pair instance (one position pair before consolidation; a team of n authors yields
  * n(n-1)/2 of them, minus self pairs) has exactly one. A consolidated pair (one per work and name
  * pair) carries the union of its instances' position pairs, so homonyms on the same work (the same
  * name at several positions) keep every position: names `A, B, A` at positions 0, 1, 2 give one
  * consolidated pair (A, B) with positions `[(0, 1), (2, 1)]`. Every position is `< teamSize`, and
  * no position appears on both the left and the right (left positions hold the left name, right
  * positions the right name).
  */
final case class CoauthorPairCandidate(
    schemaVersion: Int,
    workId: WorkId,
    year: Int,
    left: String,
    right: String,
    positions: List[PairPosition],
    teamSize: Int,
    provenance: Provenance
)
object CoauthorPairCandidate {
  implicit val readWriter: ReadWriter[CoauthorPairCandidate] = macroRW
}

/** Undirected weighted coauthor edge between resolved researchers within a window.
  *
  * `left < right` by code point; `edgeId == EdgeId.coauthor(left, right, window)`.
  * `minIdentityConfidence` is the lowest match confidence among the authorships supporting the
  * edge, so identity uncertainty travels with the edge.
  */
final case class CoauthorEdge(
    schemaVersion: Int,
    edgeId: EdgeId,
    left: ResearcherId,
    right: ResearcherId,
    window: YearWindow,
    weight: Double,
    sharedWorkCount: Int,
    firstYear: Int,
    lastYear: Int,
    supportingWorkIds: List[WorkId],
    minIdentityConfidence: Double,
    provenance: Provenance
)
object CoauthorEdge { implicit val readWriter: ReadWriter[CoauthorEdge] = macroRW }

final case class MetricValue(metric: CollaborationMetric, value: Double)
object MetricValue { implicit val readWriter: ReadWriter[MetricValue] = macroRW }

/** Deterministic metrics for one researcher in one window, computed by Spark. */
final case class ResearcherMetrics(
    schemaVersion: Int,
    researcherId: ResearcherId,
    window: YearWindow,
    values: List[MetricValue],
    identityConfidence: Double,
    provenance: Provenance
)
object ResearcherMetrics { implicit val readWriter: ReadWriter[ResearcherMetrics] = macroRW }

/** Membership of one researcher in one group (connected component or community) in one window.
  * Schema version 1 (Phase 6, new). Computed by Spark.
  *
  * `groupId` is a [[ResearcherId]] chosen deterministically, so the same inputs and configuration
  * always give the same group IDs:
  *   - `component`: the component's smallest member ResearcherId by Unicode code point. Hence
  *     `groupId <= researcherId` by code point, and a singleton component (`groupSize == 1`) has
  *     `groupId == researcherId`.
  *   - `community`: the ResearcherId of the community's final label (label propagation with ties
  *     broken by the smallest label). It need not be `<= researcherId`.
  *
  * `groupSize` (>= 1) is the number of researchers in the group in `window`; it equals the
  * `component_size` or `community_size` metric of the researcher in that window.
  */
final case class ResearcherGroupMembership(
    schemaVersion: Int,
    researcherId: ResearcherId,
    window: YearWindow,
    groupKind: GroupKind,
    groupId: ResearcherId,
    groupSize: Int,
    provenance: Provenance
)
object ResearcherGroupMembership {
  implicit val readWriter: ReadWriter[ResearcherGroupMembership] = macroRW
}

/** A dblp stream (conference series or journal), e.g. `conf/sigir`. `provenance.sourceType` must be
  * `dblp`; the OpenAlex source ID of a venue is an [[ExternalIdLink]].
  */
final case class Venue(
    schemaVersion: Int,
    venueKey: String,
    name: String,
    provenance: Provenance
)
object Venue { implicit val readWriter: ReadWriter[Venue] = macroRW }

/** An institution keyed by its OpenAlex institution ID; `countryCode` is ISO 3166-1 alpha-2.
  * `provenance.sourceType` must be `openalex` (the source that asserts the key).
  */
final case class Institution(
    schemaVersion: Int,
    institutionId: String,
    name: String,
    countryCode: Option[String],
    provenance: Provenance
)
object Institution { implicit val readWriter: ReadWriter[Institution] = macroRW }

/** A research topic. When `provenance.sourceType` names a source dataset (e.g. `openalex`),
  * `topicId` is that source's key; when it is `pipeline`, `topicId` is a key assigned by the
  * producing job named in `provenance.jobName`.
  */
final case class Topic(
    schemaVersion: Int,
    topicId: String,
    name: String,
    provenance: Provenance
)
object Topic { implicit val readWriter: ReadWriter[Topic] = macroRW }

/** An external identifier from an enrichment source linked to a dblp-anchored record.
  *
  * The link is asserted by exactly one source snapshot, named in `provenance` (dataset, snapshot or
  * retrieval date, and the source record, e.g. the OpenAlex work whose authorship lists the author
  * ID). Rules: `idType` is allowed for `subjectKind`; `externalId` is canonical for `idType`;
  * `provenance.sourceType` is one of `idType.sources` and is not `dblp` (dblp-published DOIs and
  * ORCID iDs are carried on [[WorkRecord]] and [[AuthorMention]]); `linkId ==
  * LinkId.of(subjectKind, subjectId, idType, externalId, provenance.sourceType,
  * provenance.datasetId, provenance.snapshotId)`.
  */
final case class ExternalIdLink(
    schemaVersion: Int,
    linkId: LinkId,
    subjectKind: LinkSubjectKind,
    subjectId: String,
    idType: ExternalIdType,
    externalId: String,
    provenance: Provenance
)
object ExternalIdLink { implicit val readWriter: ReadWriter[ExternalIdLink] = macroRW }

final case class MatchFeature(name: String, value: Double)
object MatchFeature { implicit val readWriter: ReadWriter[MatchFeature] = macroRW }

/** A candidate comparison produced by blocking and feature construction, before any decision.
  *
  * It carries no outcome, label, score, or confidence: deciding is entity resolution's job and is
  * recorded in [[MatchDecision]]. Which block families and features exist is entity-resolution
  * policy (`provenance.policyVersion`); this contract only fixes their shape.
  *
  * Rules: for `mention_pair`, `left` and `right` are [[MentionId]]s with `left < right` by code
  * point; for `mention_researcher`, `left` is a [[MentionId]] and `right` a [[ResearcherId]].
  * `candidateId == MatchCandidateId.of(subjectKind, left, right, blockFamily, blockKey)`.
  * `blockFamily` matches `[a-z0-9][a-z0-9._-]*`; `blockKey` is non-blank without control
  * characters. `features` is non-empty, sorted by name (code point), with distinct non-blank names
  * and finite values; a feature that cannot be computed (for example a missing ORCID iD) is
  * omitted, never defaulted.
  */
final case class MatchCandidate(
    schemaVersion: Int,
    candidateId: MatchCandidateId,
    subjectKind: MatchSubjectKind,
    left: String,
    right: String,
    blockFamily: String,
    blockKey: String,
    features: List[MatchFeature],
    provenance: Provenance
)
object MatchCandidate { implicit val readWriter: ReadWriter[MatchCandidate] = macroRW }

/** One entity-resolution decision. Schema version 2 (Phase 5, breaking: v1 held only a mention, an
  * optional researcher, an outcome, a confidence and features).
  *
  * Subjects use the [[MatchCandidate]] convention:
  *   - `mention_pair` (a pairwise decision): `left` and `right` are [[MentionId]]s with `left <
  *     right` by code point; `candidateId` is required and names the decided [[MatchCandidate]]
  *     (every candidate gets exactly one decision per run).
  *   - `mention_researcher`: `left` is a [[MentionId]] and `right` a [[ResearcherId]]. With a
  *     `candidateId` it decides that candidate. Without one it is the identity assignment: the
  *     mention `left` belongs to the cluster whose ID is `right`; its outcome is `accepted` or
  *     `conflict` (a conflict-flagged cluster is kept, not split, and the reason is recorded).
  *     Every subset mention gets exactly one identity assignment per run (cross-record invariant).
  *
  * Entity linkage policy fields: source record ID = `left`; candidate target ID = `right`; features
  * used = `features`; rules fired = `rulesFired`; model and prompt version = `modelVersion`,
  * `promptVersion`; confidence; decision = `outcome`; reason codes = `reasonCodes`; timestamp =
  * `decisionTime`; run ID = `provenance.runId`.
  *
  * Rules:
  *   - `features` (the features the decision used; may be empty, e.g. for an exact-identifier rule
  *     or an identity assignment) follows the [[MatchCandidate]] feature rules: sorted by name,
  *     distinct non-blank names, finite values; a feature that was not computed is omitted.
  *   - `rulesFired` and `reasonCodes` are code tokens (`[A-Za-z0-9][A-Za-z0-9._:/-]*`), sorted by
  *     code point and distinct. `exact_id` and `deterministic` decisions name at least one fired
  *     rule; `unresolved` and `conflict` decisions carry at least one reason code.
  *   - `llm` decisions carry `modelVersion` and `promptVersion`; other paths carry neither.
  *   - `policyVersion` equals `provenance.policyVersion`; `provenance.sourceType` is `pipeline`.
  *   - `decisionTime` is a UTC instant `yyyy-MM-ddTHH:mm:ssZ`. It is one value per run, supplied by
  *     configuration and recorded in the run manifest, never read from the clock, so the same
  *     inputs and configuration give the same output bytes.
  */
final case class MatchDecision(
    schemaVersion: Int,
    subjectKind: MatchSubjectKind,
    left: String,
    right: String,
    candidateId: Option[MatchCandidateId],
    outcome: MatchOutcome,
    matchPath: MatchPath,
    confidence: Double,
    features: List[MatchFeature],
    rulesFired: List[String],
    reasonCodes: List[String],
    modelVersion: Option[String],
    promptVersion: Option[String],
    policyVersion: String,
    decisionTime: String,
    provenance: Provenance
)
object MatchDecision { implicit val readWriter: ReadWriter[MatchDecision] = macroRW }

/** The validated result of one LLM adjudication of a bounded set of match candidates. Schema
  * version 1 (Phase 5, new). Stored in the adjudication cache keyed by `adjudicationId`.
  *
  * The model chooses among `offeredCandidateIds` only; it can never introduce an identifier.
  * Free-text model reasoning is not stored (no hidden reasoning is kept as evidence); the model's
  * justification is the list of input fields it cites.
  *
  * Rules:
  *   - `inputHash` is the `sha256:<hex>` content hash of the exact adjudication input and equals
  *     `provenance.contentHash`.
  *   - `adjudicationId == LlmAdjudicationId.of(inputHash, promptVersion, modelVersion,
  *     policyVersion)`.
  *   - `offeredCandidateIds` is non-empty, sorted by code point, and distinct.
  *   - `accept` names `chosenCandidateId`, which must be one of `offeredCandidateIds`; `reject` and
  *     `abstain` name none.
  *   - `citedInputFields` (input field paths the model relied on) and `reasonCodes` are code tokens
  *     (`[A-Za-z0-9][A-Za-z0-9._:/\[\]-]*` and `[A-Za-z0-9][A-Za-z0-9._:/-]*`), sorted by code
  *     point and distinct; `accept` and `reject` cite at least one input field.
  *   - `confidence` is in `[0, 1]`; `promptVersion`, `modelVersion` and `policyVersion` are
  *     non-blank; `policyVersion` equals `provenance.policyVersion`; `provenance.sourceType` is
  *     `pipeline`.
  */
final case class LlmAdjudication(
    schemaVersion: Int,
    adjudicationId: LlmAdjudicationId,
    inputHash: String,
    offeredCandidateIds: List[MatchCandidateId],
    outcome: AdjudicationOutcome,
    chosenCandidateId: Option[MatchCandidateId],
    citedInputFields: List[String],
    confidence: Double,
    reasonCodes: List[String],
    promptVersion: String,
    modelVersion: String,
    policyVersion: String,
    provenance: Provenance
)
object LlmAdjudication { implicit val readWriter: ReadWriter[LlmAdjudication] = macroRW }

/** Property-graph vertex. `nodeId` format depends on `label` (see Validator). */
final case class GraphNode(
    schemaVersion: Int,
    nodeId: String,
    label: GraphNodeLabel,
    displayName: String,
    properties: Map[String, String],
    provenance: Provenance
)
object GraphNode { implicit val readWriter: ReadWriter[GraphNode] = macroRW }

/** Property-graph edge. `edgeId == EdgeId.of(label, from, to, window)`; undirected labels store
  * `from < to` by code point.
  */
final case class GraphEdge(
    schemaVersion: Int,
    edgeId: EdgeId,
    label: GraphEdgeLabel,
    from: String,
    to: String,
    window: YearWindow,
    weight: Option[Double],
    provenance: Provenance
)
object GraphEdge { implicit val readWriter: ReadWriter[GraphEdge] = macroRW }

/** One piece of machine-readable evidence.
  *
  * `evidenceId == EvidenceId.of(evidenceType, provenance.runId, subjectIds)`. Graph evidence
  * (`coauthor_edge`, `graph_path`, `metric`) states its window; `metric` evidence carries its
  * metric value. A `snippet` requires `provenance.contentHash == ContentHash.of(snippet)`.
  *
  * Graph-analytics shapes (Phase 6; documentation only, the schema is unchanged):
  *   - Materialized path: `evidenceType = graph_path`; `subjectIds` are the [[ResearcherId]]s in
  *     path order (source first, target last); `window` is the path's window; `metric` is `None`.
  *     Each hop's weight, years and supporting work IDs are those of the [[CoauthorEdge]] of that
  *     pair and window.
  *   - Works behind M_r: `evidenceType = metric`; `subjectIds` are the researcher's
  *     [[ResearcherId]] followed by the [[WorkId]]s that reach M_r; `metric` is
  *     `MetricValue(max_coauthors_on_one_paper, M_r)`; `window` is the metric's window.
  *
  * `subjectIds` are checked only for being non-blank (not for ID kind), and the evidence ID does
  * not include `window` or `metric`: two items of the same type, run and `subjectIds` in different
  * windows share an `evidenceId`.
  */
final case class EvidenceItem(
    schemaVersion: Int,
    evidenceId: EvidenceId,
    evidenceType: EvidenceType,
    subjectIds: List[String],
    window: Option[YearWindow],
    metric: Option[MetricValue],
    snippet: Option[String],
    provenance: Provenance
)
object EvidenceItem { implicit val readWriter: ReadWriter[EvidenceItem] = macroRW }

/** Evidence assembled for one answer. `bundleId == EvidenceId.forBundle(runId, item IDs)`. */
final case class EvidenceBundle(
    schemaVersion: Int,
    bundleId: EvidenceId,
    items: List[EvidenceItem],
    provenance: Provenance
)
object EvidenceBundle { implicit val readWriter: ReadWriter[EvidenceBundle] = macroRW }

final case class QueryStep(
    operation: QueryOperation,
    researcherIds: List[ResearcherId],
    window: Option[YearWindow],
    parameters: Map[String, String]
)
object QueryStep { implicit val readWriter: ReadWriter[QueryStep] = macroRW }

/** Plan for answering a question against the graph produced by `graphRunId`. */
final case class QueryPlan(
    schemaVersion: Int,
    question: String,
    graphRunId: RunId,
    steps: List[QueryStep],
    provenance: Provenance
)
object QueryPlan { implicit val readWriter: ReadWriter[QueryPlan] = macroRW }

/** One claim in an answer; numbers and identifiers in a claim come from cited evidence. */
final case class AnswerClaim(
    schemaVersion: Int,
    claimText: String,
    evidenceIds: List[EvidenceId],
    status: VerificationStatus,
    window: Option[YearWindow],
    provenance: Provenance
)
object AnswerClaim { implicit val readWriter: ReadWriter[AnswerClaim] = macroRW }

/** A complete answer: plan, evidence, verified claims, and identity caveats. */
final case class AnswerResponse(
    schemaVersion: Int,
    question: String,
    answerText: String,
    modelId: Option[String],
    plan: QueryPlan,
    bundle: EvidenceBundle,
    claims: List[AnswerClaim],
    identityNotes: List[String],
    provenance: Provenance
)
object AnswerResponse { implicit val readWriter: ReadWriter[AnswerResponse] = macroRW }
