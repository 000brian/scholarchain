package org.scholarchain.rag.runtime

import scala.collection.immutable.ListMap

import org.scholarchain.core.QueryAudit

/** One reason why a step or the whole answer could not be given: a fixed code from
  * [[AbstentionCodes]] plus the answer's text (the text may name researchers and windows from
  * evidence and configuration; the code never does).
  */
final case class Abstention(code: String, text: String)

/** The fixed abstention vocabulary (Phase 15, G-28). Every abstention site of the workflow emits
  * one of these codes next to its text; the stored [[QueryAudit]] (schema version 3) keeps the
  * codes, and `GET /v1/explain/{queryId}` shows each code with its short fixed text from [[Table]]
  * (never model prose, never a name). An abstained answer whose codes are all missing or invalid
  * gets [[Unclassified]], so no abstained audit lacks a code; no reachable site produces it.
  *
  * Two codes are context codes: they are added when the answer abstained and a shortest-path step
  * found no path ([[NoPathWithinDepth]], the search was complete) or stopped at its time budget
  * ([[PathQueryTimeout]]). They say what the path search saw; they are not themselves a failure.
  */
object AbstentionCodes {
  val UnsupportedVenueFilter = "unsupported-venue-filter"
  val UnsupportedInstitutionFilter = "unsupported-institution-filter"
  val WindowNotConfigured = "window-not-configured"
  val ResearcherNotResolved = "researcher-not-resolved"
  val PathNeedsTwoResearchers = "path-needs-two-researchers"
  val ResearcherNameMissing = "researcher-name-missing"
  val MetricsReadFailed = "metrics-read-failed"
  val MetricsMissing = "metrics-missing"
  val RankingReadFailed = "ranking-read-failed"
  val RankingMissing = "ranking-missing"
  val TopicCandidatesFailed = "topic-candidates-failed"
  val TopicMetricsFailed = "topic-metrics-failed"
  val TopicNoMatch = "topic-no-match"
  val WindowNotLoaded = "window-not-loaded"
  val PathSameResearcher = "path-same-researcher"
  val PathQueryFailed = "path-query-failed"
  val PathResultInvalid = "path-result-invalid"
  val NoPathWithinDepth = "no-path-within-depth"
  val PathQueryTimeout = "path-query-timeout"
  val EdgeLookupFailed = "edge-lookup-failed"
  val NeighborLookupFailed = "neighbor-lookup-failed"
  val CollaborationNeedsResearchers = "collaboration-needs-researchers"
  val SemanticNeedsTopicOrName = "semantic-needs-topic-or-name"
  val NoVerifiedClaim = "no-verified-claim"
  val NoSubstantiveEvidence = "no-substantive-evidence"
  val Unclassified = "unclassified"

  /** Code -> short fixed text (no names, no numbers from data, no model prose). */
  val Table: ListMap[String, String] = ListMap(
    UnsupportedVenueFilter -> "venue filters are not supported (no venue metrics exist)",
    UnsupportedInstitutionFilter -> "institution filters are not supported (no institution data exists)",
    WindowNotConfigured -> "the requested years are not a configured window",
    ResearcherNotResolved -> "a researcher name could not be resolved to exactly one researcher",
    PathNeedsTwoResearchers -> "a shortest path needs exactly two resolved researchers",
    ResearcherNameMissing -> "the operation needs a researcher name",
    MetricsReadFailed -> "stored metrics could not be read (store failure)",
    MetricsMissing -> "no stored metrics exist for the researcher in the window",
    RankingReadFailed -> "a stored ranking could not be read (store failure)",
    RankingMissing -> "no ranking is stored for the metric in the window",
    TopicCandidatesFailed -> "topic candidates could not be retrieved (semantic retrieval failure)",
    TopicMetricsFailed -> "metrics of topic candidates could not be read (store failure)",
    TopicNoMatch -> "no researcher profile in the window matched the topic",
    WindowNotLoaded -> "coauthor edges for the window are not loaded in this environment",
    PathSameResearcher -> "a path needs two different researchers",
    PathQueryFailed -> "the path search failed (graph store failure)",
    PathResultInvalid -> "the stored path does not connect the two researchers",
    NoPathWithinDepth -> "the path search was complete and found no path within the depth limit",
    PathQueryTimeout -> "the path search stopped at its time budget; whether a path exists is unknown",
    EdgeLookupFailed -> "the edge lookup failed (graph store failure)",
    NeighborLookupFailed -> "the neighbor lookup failed (graph store failure)",
    CollaborationNeedsResearchers -> "direct collaboration needs one or two resolved researchers",
    SemanticNeedsTopicOrName -> "semantic context needs a topic or a researcher name",
    NoVerifiedClaim -> "no claim could be verified against the evidence",
    NoSubstantiveEvidence -> "no graph evidence answered the question",
    Unclassified -> "the abstention reason was not classified"
  )

  /** Every code. */
  val All: List[String] = Table.keys.toList

  /** The fixed text of `code`; an unknown code reads as [[Unclassified]]'s text. */
  def text(code: String): String = Table.getOrElse(code, Table(Unclassified))

  /** `(code, text)` pairs for the explain view, in the stored order. */
  def reasons(codes: List[String]): List[(String, String)] = codes.map(c => c -> text(c))

  /** The audit's codes: distinct, valid for [[QueryAudit]] and in this table, in order, at most
    * [[QueryAudit.MaxAbstentionCodes]]; [[Unclassified]] when the answer abstained and none
    * remains; empty when it did not abstain (the QueryAudit v3 rule).
    */
  def forAudit(abstained: Boolean, codes: List[String]): List[String] =
    if (!abstained) Nil
    else {
      val ok = codes.distinct.filter(c => Table.contains(c) && QueryAudit.isValidAbstentionCode(c))
      if (ok.isEmpty) List(Unclassified) else ok.take(QueryAudit.MaxAbstentionCodes)
    }
}
