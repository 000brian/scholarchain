package org.scholarchain.core

import upickle.core.Abort
import upickle.default.{readwriter, ReadWriter}

/** A closed set of values with a frozen JSON wire name (a JSON string).
  *
  * Encoded as sealed traits with case objects (not Scala 3 `enum`) so the same sources compile
  * under Scala 3 and Scala 2.13. Wire names are part of the contract: renaming a Scala case object
  * must not change its wire name; removing or renaming a wire name is a breaking change.
  */
trait WireEnum {
  def wire: String
}

abstract class WireEnumCompanion[A <: WireEnum](enumName: String) {
  def values: List[A]

  def fromWire(s: String): Either[String, A] =
    values
      .find(_.wire == s)
      .toRight(s"unknown $enumName '$s' (expected one of: ${values.map(_.wire).mkString(", ")})")

  implicit lazy val readWriter: ReadWriter[A] =
    readwriter[String].bimap[A](_.wire, s => fromWire(s).fold(e => throw new Abort(e), identity))
}

/** Where a fact originated. */
sealed trait SourceType extends WireEnum
object SourceType extends WireEnumCompanion[SourceType]("source type") {
  case object Dblp extends SourceType { val wire = "dblp" }
  case object OpenAlex extends SourceType { val wire = "openalex" }
  case object Crossref extends SourceType { val wire = "crossref" }
  case object Orcid extends SourceType { val wire = "orcid" }

  /** Computed by a ScholarChain pipeline job (Hadoop, entity resolution, Spark). */
  case object Pipeline extends SourceType { val wire = "pipeline" }

  /** Produced by a runtime collaboration-analysis agent or service. */
  case object Runtime extends SourceType { val wire = "runtime" }

  val values: List[SourceType] = List(Dblp, OpenAlex, Crossref, Orcid, Pipeline, Runtime)
}

/** Entity-resolution decision outcome of a [[MatchDecision]] (schema version 2).
  *
  * Phase 5 (breaking, with MatchDecision v2): the v1 wire names `match`, `possible_match` and
  * `non_match` were replaced by `accepted`, `rejected` and `conflict`; `unresolved` is kept.
  */
sealed trait MatchOutcome extends WireEnum
object MatchOutcome extends WireEnumCompanion[MatchOutcome]("match outcome") {

  /** The subjects are the same entity (for an identity assignment: the mention belongs to the named
    * researcher cluster).
    */
  case object Accepted extends MatchOutcome { val wire = "accepted" }

  /** The subjects are different entities. */
  case object Rejected extends MatchOutcome { val wire = "rejected" }

  /** The evidence was insufficient to decide; identity uncertainty is reported, not hidden. */
  case object Unresolved extends MatchOutcome { val wire = "unresolved" }

  /** Authoritative identifiers or structural facts disagree (for example two different valid ORCID
    * iDs, or two mentions of the same work); never merged.
    */
  case object Conflict extends MatchOutcome { val wire = "conflict" }

  val values: List[MatchOutcome] = List(Accepted, Rejected, Unresolved, Conflict)
}

/** How a [[MatchDecision]] was reached. */
sealed trait MatchPath extends WireEnum
object MatchPath extends WireEnumCompanion[MatchPath]("match path") {

  /** An exact rule over an authoritative identifier (DOI, ORCID iD, OpenAlex ID, arXiv ID, ...). */
  case object ExactId extends MatchPath { val wire = "exact_id" }

  /** A deterministic bibliographic rule (for example the dblp-prior rule). */
  case object Deterministic extends MatchPath { val wire = "deterministic" }

  /** A weighted score compared with the policy thresholds. */
  case object Probabilistic extends MatchPath { val wire = "probabilistic" }

  /** LLM adjudication of a candidate in the policy's LLM band (see [[LlmAdjudication]]). */
  case object Llm extends MatchPath { val wire = "llm" }

  val values: List[MatchPath] = List(ExactId, Deterministic, Probabilistic, Llm)
}

/** Outcome of one [[LlmAdjudication]]: the model's answer, before entity-resolution policy turns it
  * into a [[MatchOutcome]].
  */
sealed trait AdjudicationOutcome extends WireEnum
object AdjudicationOutcome extends WireEnumCompanion[AdjudicationOutcome]("adjudication outcome") {

  /** The model chose exactly one of the offered candidates. */
  case object Accept extends AdjudicationOutcome { val wire = "accept" }

  /** The model judged that none of the offered candidates matches. */
  case object Reject extends AdjudicationOutcome { val wire = "reject" }

  /** The model declined to decide. */
  case object Abstain extends AdjudicationOutcome { val wire = "abstain" }

  val values: List[AdjudicationOutcome] = List(Accept, Reject, Abstain)
}

/** Kind of an [[EvidenceItem]].
  *
  * Phase 9 (additive; every earlier wire name is unchanged): adds `semantic_context`.
  */
sealed trait EvidenceType extends WireEnum
object EvidenceType extends WireEnumCompanion[EvidenceType]("evidence type") {
  case object Work extends EvidenceType { val wire = "work" }
  case object Authorship extends EvidenceType { val wire = "authorship" }
  case object CoauthorEdge extends EvidenceType { val wire = "coauthor_edge" }
  case object GraphPath extends EvidenceType { val wire = "graph_path" }
  case object Metric extends EvidenceType { val wire = "metric" }
  case object MatchDecision extends EvidenceType { val wire = "match_decision" }
  case object ExternalRecord extends EvidenceType { val wire = "external_record" }

  /** A pointer to a retrieved semantic document (a vector-search hit: researcher profile, community
    * summary, collaboration path summary, ...). Its window is optional (it is not in
    * [[requiresWindow]]); when the document describes a window, the item states it.
    *
    * A `semantic_context` item is descriptive only. It is never evidence for a count, an edge, a
    * path, a component, a community or a bridge score: vector similarity is not proof of a
    * coauthorship edge or of a numerical graph metric. Those facts are supported only by graph
    * evidence (`metric`, `coauthor_edge`, `graph_path`, `work`, `match_decision`).
    */
  case object SemanticContext extends EvidenceType { val wire = "semantic_context" }

  val values: List[EvidenceType] =
    List(
      Work,
      Authorship,
      CoauthorEdge,
      GraphPath,
      Metric,
      MatchDecision,
      ExternalRecord,
      SemanticContext
    )

  /** Evidence types that describe the temporal graph and therefore must state a time window. */
  val requiresWindow: Set[EvidenceType] = Set(CoauthorEdge, GraphPath, Metric)
}

/** A per-researcher, per-window metric carried in [[MetricValue]].
  *
  * Phase 6 (additive; every earlier wire name is unchanged): adds `publication_count`,
  * `total_coauthor_occurrences`, `average_coauthors_per_paper`, `max_coauthors_on_one_paper`,
  * `community_size`, `participation_coefficient`, `cross_community_degree`, `communities_connected`
  * and `coreness`. `coauthored_works` is not the publication count, and `bridge_score` is not the
  * participation coefficient; neither is an alias of a new value.
  */
sealed trait CollaborationMetric extends WireEnum
object CollaborationMetric extends WireEnumCompanion[CollaborationMetric]("collaboration metric") {

  /** C_r: distinct coauthor researchers. */
  case object DistinctCoauthors extends CollaborationMetric { val wire = "distinct_coauthors" }
  case object CoauthoredWorks extends CollaborationMetric { val wire = "coauthored_works" }

  /** W_r: sum of the researcher's coauthor-edge weights. */
  case object WeightedDegree extends CollaborationMetric { val wire = "weighted_degree" }
  case object BetweennessCentrality extends CollaborationMetric {
    val wire = "betweenness_centrality"
  }
  case object BridgeScore extends CollaborationMetric { val wire = "bridge_score" }

  /** Size of the researcher's connected component (see [[ResearcherGroupMembership]]). */
  case object ComponentSize extends CollaborationMetric { val wire = "component_size" }
  case object ClusteringCoefficient extends CollaborationMetric {
    val wire = "clustering_coefficient"
  }

  /** P_r: distinct works of the researcher, single-author works included. */
  case object PublicationCount extends CollaborationMetric { val wire = "publication_count" }

  /** T_r: sum over the researcher's works of the number of distinct other researchers on it. */
  case object TotalCoauthorOccurrences extends CollaborationMetric {
    val wire = "total_coauthor_occurrences"
  }

  /** A_r = T_r / P_r. */
  case object AverageCoauthorsPerPaper extends CollaborationMetric {
    val wire = "average_coauthors_per_paper"
  }

  /** M_r: maximum over the researcher's works of the distinct other researchers on one work. The
    * works that reach it travel as a `metric` [[EvidenceItem]] (see its scaladoc).
    */
  case object MaxCoauthorsOnOnePaper extends CollaborationMetric {
    val wire = "max_coauthors_on_one_paper"
  }

  /** Size of the researcher's community (see [[ResearcherGroupMembership]]). */
  case object CommunitySize extends CollaborationMetric { val wire = "community_size" }

  /** Participation coefficient of the researcher over the communities of its neighbours. */
  case object ParticipationCoefficient extends CollaborationMetric {
    val wire = "participation_coefficient"
  }

  /** Coauthor edges (or their weight, as the producing policy states) leaving the community. */
  case object CrossCommunityDegree extends CollaborationMetric {
    val wire = "cross_community_degree"
  }

  /** Number of distinct communities directly connected to the researcher. */
  case object CommunitiesConnected extends CollaborationMetric {
    val wire = "communities_connected"
  }

  /** k-core number (coreness) of the researcher. */
  case object Coreness extends CollaborationMetric { val wire = "coreness" }

  val values: List[CollaborationMetric] = List(
    DistinctCoauthors,
    CoauthoredWorks,
    WeightedDegree,
    BetweennessCentrality,
    BridgeScore,
    ComponentSize,
    ClusteringCoefficient,
    PublicationCount,
    TotalCoauthorOccurrences,
    AverageCoauthorsPerPaper,
    MaxCoauthorsOnOnePaper,
    CommunitySize,
    ParticipationCoefficient,
    CrossCommunityDegree,
    CommunitiesConnected,
    Coreness
  )
}

/** Kind of researcher group a [[ResearcherGroupMembership]] names (new in Phase 6). */
sealed trait GroupKind extends WireEnum
object GroupKind extends WireEnumCompanion[GroupKind]("group kind") {

  /** A connected component of the coauthorship graph in the window. */
  case object Component extends GroupKind { val wire = "component" }

  /** A community (for example a label-propagation community) in the window. */
  case object Community extends GroupKind { val wire = "community" }

  val values: List[GroupKind] = List(Component, Community)
}

/** An allowlisted operation of a [[QueryStep]].
  *
  * Phase 9 (additive; every earlier wire name is unchanged): adds `researcher_ranking`,
  * `max_team_size`, `community_analysis`, `temporal_comparison` and `semantic_context`.
  *
  * Researcher counts per step (checked by the `QueryPlan` validator): `shortest_path` needs exactly
  * 2; `researcher_ranking`, `bridge_researchers` and `semantic_context` may have none
  * ([[mayOmitResearchers]]); every other operation needs at least 1.
  */
sealed trait QueryOperation extends WireEnum
object QueryOperation extends WireEnumCompanion[QueryOperation]("query operation") {
  case object ResearcherLookup extends QueryOperation { val wire = "researcher_lookup" }
  case object CollaborationBreadth extends QueryOperation { val wire = "collaboration_breadth" }
  case object CoauthorNeighborhood extends QueryOperation { val wire = "coauthor_neighborhood" }
  case object ShortestPath extends QueryOperation { val wire = "shortest_path" }
  case object ConnectedComponent extends QueryOperation { val wire = "connected_component" }
  case object BridgeResearchers extends QueryOperation { val wire = "bridge_researchers" }

  /** Rank researchers by one stored metric in one window (top-K). */
  case object ResearcherRanking extends QueryOperation { val wire = "researcher_ranking" }

  /** Maximum team size (M_r) of researchers and the works that reach it. */
  case object MaxTeamSize extends QueryOperation { val wire = "max_team_size" }

  /** Community membership, size and cross-community position of researchers. */
  case object CommunityAnalysis extends QueryOperation { val wire = "community_analysis" }

  /** The same facts compared across windows (one step per window). */
  case object TemporalComparison extends QueryOperation { val wire = "temporal_comparison" }

  /** Descriptive semantic context (vector search); never evidence for graph facts. */
  case object SemanticContext extends QueryOperation { val wire = "semantic_context" }

  val values: List[QueryOperation] = List(
    ResearcherLookup,
    CollaborationBreadth,
    CoauthorNeighborhood,
    ShortestPath,
    ConnectedComponent,
    BridgeResearchers,
    ResearcherRanking,
    MaxTeamSize,
    CommunityAnalysis,
    TemporalComparison,
    SemanticContext
  )

  /** Operations over the temporal graph; they must state a time window. Every operation except
    * `researcher_lookup`.
    */
  val requiresWindow: Set[QueryOperation] = values.toSet - ResearcherLookup

  /** Operations whose step may name no researcher (an empty `researcherIds`). */
  val mayOmitResearchers: Set[QueryOperation] =
    Set(ResearcherRanking, BridgeResearchers, SemanticContext)
}

/** Outcome of deterministic claim verification of an [[AnswerClaim]] (the spec's four outcomes).
  *
  * Phase 9 (breaking, wire names only, with AnswerClaim v2): `partially_verified` was renamed
  * `partially_supported` and `contradicted` was renamed `conflicting`; `verified` and `unsupported`
  * are unchanged.
  */
sealed trait VerificationStatus extends WireEnum
object VerificationStatus extends WireEnumCompanion[VerificationStatus]("verification status") {

  /** Every check against the cited evidence passes. */
  case object Verified extends VerificationStatus { val wire = "verified" }

  /** Some numbers or names are verified; others are not covered by the cited evidence. */
  case object PartiallySupported extends VerificationStatus { val wire = "partially_supported" }

  /** No valid supporting evidence. The only status that may cite no evidence. */
  case object Unsupported extends VerificationStatus { val wire = "unsupported" }

  /** A number or name contradicts its cited evidence. */
  case object Conflicting extends VerificationStatus { val wire = "conflicting" }

  val values: List[VerificationStatus] =
    List(Verified, PartiallySupported, Unsupported, Conflicting)
}

/** How the [[QueryPlan]] recorded in a [[QueryAudit]] was produced (new in Phase 9). */
sealed trait PlanSource extends WireEnum
object PlanSource extends WireEnumCompanion[PlanSource]("plan source") {

  /** A model-produced plan draft that passed allowlist validation. */
  case object Llm extends PlanSource { val wire = "llm" }

  /** The deterministic rule planner (also the fallback after failed model drafts). */
  case object Rule extends PlanSource { val wire = "rule" }

  val values: List[PlanSource] = List(Llm, Rule)
}

/** Graph node labels. The `nodeId` format of each label is checked by the `GraphNode` validator.
  *
  * Phase 7 (additive; every earlier wire name is unchanged): adds `author_mention` (nodeId is a
  * [[MentionId]]), `dataset_snapshot` (nodeId is non-empty without whitespace, for example
  * `dblp:2026-11-01`) and `text_chunk` (nodeId is a [[ChunkId]]).
  */
sealed trait GraphNodeLabel extends WireEnum
object GraphNodeLabel extends WireEnumCompanion[GraphNodeLabel]("graph node label") {
  case object Researcher extends GraphNodeLabel { val wire = "researcher" }
  case object Work extends GraphNodeLabel { val wire = "work" }
  case object Venue extends GraphNodeLabel { val wire = "venue" }
  case object Institution extends GraphNodeLabel { val wire = "institution" }
  case object Topic extends GraphNodeLabel { val wire = "topic" }

  /** One author slot of one work, before entity resolution; nodeId is a [[MentionId]]. */
  case object AuthorMention extends GraphNodeLabel { val wire = "author_mention" }

  /** One source dataset snapshot; nodeId is non-empty with no whitespace (`dblp:2026-11-01`). */
  case object DatasetSnapshot extends GraphNodeLabel { val wire = "dataset_snapshot" }

  /** One chunk of a semantic document; nodeId is a [[ChunkId]]. */
  case object TextChunk extends GraphNodeLabel { val wire = "text_chunk" }

  val values: List[GraphNodeLabel] =
    List(Researcher, Work, Venue, Institution, Topic, AuthorMention, DatasetSnapshot, TextChunk)
}

/** Graph edge labels with their endpoint labels and directedness.
  *
  * Phase 7 (additive; every earlier wire name is unchanged): adds the directed labels `resolves_to`
  * (author_mention -> researcher), `derived_from` (work -> dataset_snapshot) and `supported_by`
  * (researcher -> text_chunk).
  */
sealed abstract class GraphEdgeLabel(
    val wire: String,
    val fromLabel: GraphNodeLabel,
    val toLabel: GraphNodeLabel,
    val undirected: Boolean
) extends WireEnum
object GraphEdgeLabel extends WireEnumCompanion[GraphEdgeLabel]("graph edge label") {
  import GraphNodeLabel.{
    AuthorMention,
    DatasetSnapshot,
    Institution,
    Researcher,
    TextChunk,
    Topic,
    Venue,
    Work
  }

  case object CoauthoredWith
      extends GraphEdgeLabel("coauthored_with", Researcher, Researcher, undirected = true)
  case object Authored extends GraphEdgeLabel("authored", Researcher, Work, undirected = false)
  case object PublishedIn extends GraphEdgeLabel("published_in", Work, Venue, undirected = false)
  case object AffiliatedWith
      extends GraphEdgeLabel("affiliated_with", Researcher, Institution, undirected = false)
  case object HasTopic extends GraphEdgeLabel("has_topic", Work, Topic, undirected = false)

  /** An author mention resolved (by entity resolution) to a researcher. */
  case object ResolvesTo
      extends GraphEdgeLabel("resolves_to", AuthorMention, Researcher, undirected = false)

  /** A work derived from (published in) a source dataset snapshot. */
  case object DerivedFrom
      extends GraphEdgeLabel("derived_from", Work, DatasetSnapshot, undirected = false)

  /** A researcher described by a text chunk (for example a researcher-profile document). */
  case object SupportedBy
      extends GraphEdgeLabel("supported_by", Researcher, TextChunk, undirected = false)

  val values: List[GraphEdgeLabel] =
    List(
      CoauthoredWith,
      Authored,
      PublishedIn,
      AffiliatedWith,
      HasTopic,
      ResolvesTo,
      DerivedFrom,
      SupportedBy
    )
}

/** Kind of a [[SemanticDocument]] (new in Phase 7). Each kind has its own vector index (see
  * `VectorSettings.indexFor` in the configuration contract).
  */
sealed trait SemanticDocumentType extends WireEnum
object SemanticDocumentType
    extends WireEnumCompanion[SemanticDocumentType]("semantic document type") {

  /** Profile of one researcher in one window. */
  case object ResearcherProfile extends SemanticDocumentType { val wire = "researcher_profile" }

  /** Summary of one community in one window. */
  case object CommunitySummary extends SemanticDocumentType { val wire = "community_summary" }

  /** Description of one materialized collaboration path in one window. */
  case object CollaborationPath extends SemanticDocumentType { val wire = "collaboration_path" }

  /** Compact summary of one work. */
  case object PaperSummary extends SemanticDocumentType { val wire = "paper_summary" }

  /** Summary of one topic or institution. */
  case object TopicInstitutionSummary extends SemanticDocumentType {
    val wire = "topic_institution_summary"
  }

  val values: List[SemanticDocumentType] =
    List(
      ResearcherProfile,
      CommunitySummary,
      CollaborationPath,
      PaperSummary,
      TopicInstitutionSummary
    )

  /** Kinds that describe the temporal graph and therefore must state a time window. */
  val requiresWindow: Set[SemanticDocumentType] =
    Set(ResearcherProfile, CommunitySummary, CollaborationPath)
}

/** Kinds of external (source-dataset) identifiers, with the source datasets allowed to assert each
  * one. An identifier of a kind is only accepted with provenance from one of `sources`.
  *
  * DOIs and ORCID iDs are published by dblp itself (`<ee>` DOI URLs, `<author orcid=...>`) and by
  * the enrichment sources; OpenAlex keys are asserted only by OpenAlex (and are unstable across
  * OpenAlex snapshots, so they are always stored with the snapshot or retrieval date).
  */
sealed abstract class ExternalIdType(val wire: String, val sources: Set[SourceType])
    extends WireEnum {
  def isCanonical(s: String): Boolean
}
object ExternalIdType extends WireEnumCompanion[ExternalIdType]("external ID type") {
  import SourceType.{Crossref, Dblp, OpenAlex}

  case object Doi extends ExternalIdType("doi", Set(Dblp, OpenAlex, Crossref, SourceType.Orcid)) {
    def isCanonical(s: String): Boolean = _root_.org.scholarchain.core.Doi.isCanonical(s)
  }
  case object Orcid
      extends ExternalIdType("orcid", Set(Dblp, OpenAlex, Crossref, SourceType.Orcid)) {
    def isCanonical(s: String): Boolean = _root_.org.scholarchain.core.Orcid.isCanonical(s)
  }
  case object OpenAlexWork extends ExternalIdType("openalex_work", Set(OpenAlex)) {
    def isCanonical(s: String): Boolean = OpenAlexId.Work.isCanonical(s)
  }
  case object OpenAlexAuthor extends ExternalIdType("openalex_author", Set(OpenAlex)) {
    def isCanonical(s: String): Boolean = OpenAlexId.Author.isCanonical(s)
  }
  case object OpenAlexSource extends ExternalIdType("openalex_source", Set(OpenAlex)) {
    def isCanonical(s: String): Boolean = OpenAlexId.Source.isCanonical(s)
  }
  case object OpenAlexInstitution extends ExternalIdType("openalex_institution", Set(OpenAlex)) {
    def isCanonical(s: String): Boolean = OpenAlexId.Institution.isCanonical(s)
  }

  val values: List[ExternalIdType] =
    List(Doi, Orcid, OpenAlexWork, OpenAlexAuthor, OpenAlexSource, OpenAlexInstitution)

  /** Identifier kinds a [[CanonicalResearcher]] may carry (each as a [[SourcedExternalId]]). */
  def forResearcher: Set[ExternalIdType] = Set(Orcid, OpenAlexAuthor, OpenAlexInstitution)
}

/** The dblp-anchored record an [[ExternalIdLink]] attaches an external identifier to. */
sealed trait LinkSubjectKind extends WireEnum {

  /** External identifier kinds that may be linked to this kind of subject. */
  def idTypes: Set[ExternalIdType]
}
object LinkSubjectKind extends WireEnumCompanion[LinkSubjectKind]("link subject kind") {
  import ExternalIdType._

  /** `subjectId` is a [[WorkId]]. */
  case object Work extends LinkSubjectKind {
    val wire = "work"
    def idTypes: Set[ExternalIdType] = Set(Doi, OpenAlexWork)
  }

  /** `subjectId` is a [[MentionId]]; author-level and affiliation-at-publication identifiers. */
  case object Mention extends LinkSubjectKind {
    val wire = "mention"
    def idTypes: Set[ExternalIdType] = Set(Orcid, OpenAlexAuthor, OpenAlexInstitution)
  }

  /** `subjectId` is a dblp venue (stream) key. */
  case object Venue extends LinkSubjectKind {
    val wire = "venue"
    def idTypes: Set[ExternalIdType] = Set(OpenAlexSource)
  }

  val values: List[LinkSubjectKind] = List(Work, Mention, Venue)
}

/** What a [[MatchCandidate]] compares. */
sealed trait MatchSubjectKind extends WireEnum
object MatchSubjectKind extends WireEnumCompanion[MatchSubjectKind]("match subject kind") {

  /** Two author mentions (`left`, `right` are [[MentionId]]s, an unordered canonical pair). */
  case object MentionPair extends MatchSubjectKind { val wire = "mention_pair" }

  /** A mention and a resolved researcher (`left` is a [[MentionId]], `right` a [[ResearcherId]]).
    */
  case object MentionResearcher extends MatchSubjectKind { val wire = "mention_researcher" }

  val values: List[MatchSubjectKind] = List(MentionPair, MentionResearcher)
}
