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

sealed trait EvidenceType extends WireEnum
object EvidenceType extends WireEnumCompanion[EvidenceType]("evidence type") {
  case object Work extends EvidenceType { val wire = "work" }
  case object Authorship extends EvidenceType { val wire = "authorship" }
  case object CoauthorEdge extends EvidenceType { val wire = "coauthor_edge" }
  case object GraphPath extends EvidenceType { val wire = "graph_path" }
  case object Metric extends EvidenceType { val wire = "metric" }
  case object MatchDecision extends EvidenceType { val wire = "match_decision" }
  case object ExternalRecord extends EvidenceType { val wire = "external_record" }

  val values: List[EvidenceType] =
    List(Work, Authorship, CoauthorEdge, GraphPath, Metric, MatchDecision, ExternalRecord)

  /** Evidence types that describe the temporal graph and therefore must state a time window. */
  val requiresWindow: Set[EvidenceType] = Set(CoauthorEdge, GraphPath, Metric)
}

sealed trait CollaborationMetric extends WireEnum
object CollaborationMetric extends WireEnumCompanion[CollaborationMetric]("collaboration metric") {
  case object DistinctCoauthors extends CollaborationMetric { val wire = "distinct_coauthors" }
  case object CoauthoredWorks extends CollaborationMetric { val wire = "coauthored_works" }
  case object WeightedDegree extends CollaborationMetric { val wire = "weighted_degree" }
  case object BetweennessCentrality extends CollaborationMetric {
    val wire = "betweenness_centrality"
  }
  case object BridgeScore extends CollaborationMetric { val wire = "bridge_score" }
  case object ComponentSize extends CollaborationMetric { val wire = "component_size" }
  case object ClusteringCoefficient extends CollaborationMetric {
    val wire = "clustering_coefficient"
  }

  val values: List[CollaborationMetric] = List(
    DistinctCoauthors,
    CoauthoredWorks,
    WeightedDegree,
    BetweennessCentrality,
    BridgeScore,
    ComponentSize,
    ClusteringCoefficient
  )
}

sealed trait QueryOperation extends WireEnum
object QueryOperation extends WireEnumCompanion[QueryOperation]("query operation") {
  case object ResearcherLookup extends QueryOperation { val wire = "researcher_lookup" }
  case object CollaborationBreadth extends QueryOperation { val wire = "collaboration_breadth" }
  case object CoauthorNeighborhood extends QueryOperation { val wire = "coauthor_neighborhood" }
  case object ShortestPath extends QueryOperation { val wire = "shortest_path" }
  case object ConnectedComponent extends QueryOperation { val wire = "connected_component" }
  case object BridgeResearchers extends QueryOperation { val wire = "bridge_researchers" }

  val values: List[QueryOperation] = List(
    ResearcherLookup,
    CollaborationBreadth,
    CoauthorNeighborhood,
    ShortestPath,
    ConnectedComponent,
    BridgeResearchers
  )

  /** Operations over the temporal graph; they must state a time window. */
  val requiresWindow: Set[QueryOperation] = values.toSet - ResearcherLookup
}

sealed trait VerificationStatus extends WireEnum
object VerificationStatus extends WireEnumCompanion[VerificationStatus]("verification status") {
  case object Verified extends VerificationStatus { val wire = "verified" }
  case object PartiallyVerified extends VerificationStatus { val wire = "partially_verified" }
  case object Unsupported extends VerificationStatus { val wire = "unsupported" }
  case object Contradicted extends VerificationStatus { val wire = "contradicted" }

  val values: List[VerificationStatus] =
    List(Verified, PartiallyVerified, Unsupported, Contradicted)
}

sealed trait GraphNodeLabel extends WireEnum
object GraphNodeLabel extends WireEnumCompanion[GraphNodeLabel]("graph node label") {
  case object Researcher extends GraphNodeLabel { val wire = "researcher" }
  case object Work extends GraphNodeLabel { val wire = "work" }
  case object Venue extends GraphNodeLabel { val wire = "venue" }
  case object Institution extends GraphNodeLabel { val wire = "institution" }
  case object Topic extends GraphNodeLabel { val wire = "topic" }

  val values: List[GraphNodeLabel] = List(Researcher, Work, Venue, Institution, Topic)
}

/** Graph edge labels with their endpoint labels and directedness. */
sealed abstract class GraphEdgeLabel(
    val wire: String,
    val fromLabel: GraphNodeLabel,
    val toLabel: GraphNodeLabel,
    val undirected: Boolean
) extends WireEnum
object GraphEdgeLabel extends WireEnumCompanion[GraphEdgeLabel]("graph edge label") {
  import GraphNodeLabel.{Institution, Researcher, Topic, Venue, Work}

  case object CoauthoredWith
      extends GraphEdgeLabel("coauthored_with", Researcher, Researcher, undirected = true)
  case object Authored extends GraphEdgeLabel("authored", Researcher, Work, undirected = false)
  case object PublishedIn extends GraphEdgeLabel("published_in", Work, Venue, undirected = false)
  case object AffiliatedWith
      extends GraphEdgeLabel("affiliated_with", Researcher, Institution, undirected = false)
  case object HasTopic extends GraphEdgeLabel("has_topic", Work, Topic, undirected = false)

  val values: List[GraphEdgeLabel] =
    List(CoauthoredWith, Authored, PublishedIn, AffiliatedWith, HasTopic)
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
