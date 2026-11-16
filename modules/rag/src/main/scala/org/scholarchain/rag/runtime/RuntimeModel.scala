package org.scholarchain.rag.runtime

import java.text.Normalizer

import org.scholarchain.core._
import org.scholarchain.core.config.{NamedWindow, RuntimeSettings, ScholarChainConfig}
import org.scholarchain.rag.retrieval.SemanticContextEntry
import org.scholarchain.storage.dynamo.RankingMetrics

// Typed inputs and outputs of the runtime stages (package org.scholarchain.rag.runtime).
// Every number, identifier, edge and path in these types comes from a storage reader; the model
// never produces one of them.

/** One question with its optional explicit constraints. User text is data: it is never turned into
  * query text, only parsed into a typed [[PlanDraft]].
  */
final case class QueryRequest(question: String, windowLabel: Option[String] = None)

object QueryRequest {
  val MaxQuestionChars = 2000

  /** NFC, trimmed, whitespace runs collapsed: the form that is hashed into the query run ID. */
  def canonicalQuestion(q: String): String =
    Normalizer.normalize(Option(q).getOrElse(""), Normalizer.Form.NFC).trim.replaceAll("\\s+", " ")

  /** `sha256:` of the canonical question plus constraints (the query run ID input fingerprint). */
  def fingerprint(r: QueryRequest): String =
    ContentHash.of(
      ujson
        .Obj(
          "question" -> canonicalQuestion(r.question),
          "windowLabel" -> r.windowLabel.map(ujson.Str(_)).getOrElse(ujson.Null)
        )
        .render()
    )
}

/** A constraint the runtime cannot apply yet (no venue metrics, no institution data, an
  * unconfigured window). `kind` is one of [[UnsupportedConstraint.Kinds]].
  */
final case class UnsupportedConstraint(kind: String, text: String)

object UnsupportedConstraint {
  val Kinds: List[String] = List("venue", "institution", "window", "other")
  val MaxTextChars = 200
}

/** The validated plan draft (schema `query-plan-draft-v1`), before identity resolution. Every field
  * is allowlisted: operations from QueryOperation, windows from `graph.windows`, metrics from the
  * RM4 ranking list, bounded integers, bounded strings.
  */
final case class PlanDraft(
    operations: List[QueryOperation],
    researcherNames: List[String],
    orcids: List[String],
    windowLabel: Option[String],
    comparisonWindowLabels: List[String],
    rankingMetric: Option[CollaborationMetric],
    limit: Option[Int],
    maxPathDepth: Option[Int],
    topic: Option[String],
    unsupportedConstraints: List[UnsupportedConstraint]
) {
  def toJson: ujson.Obj = ujson.Obj(
    "operations" -> ujson.Arr.from(operations.map(o => ujson.Str(o.wire))),
    "researcherNames" -> ujson.Arr.from(researcherNames.map(ujson.Str(_))),
    "orcids" -> ujson.Arr.from(orcids.map(ujson.Str(_))),
    "windowLabel" -> windowLabel.map(ujson.Str(_)).getOrElse(ujson.Null),
    "comparisonWindowLabels" -> ujson.Arr.from(comparisonWindowLabels.map(ujson.Str(_))),
    "rankingMetric" -> rankingMetric.map(m => ujson.Str(m.wire)).getOrElse(ujson.Null),
    "limit" -> limit.map(n => ujson.Num(n.toDouble)).getOrElse(ujson.Null),
    "maxPathDepth" -> maxPathDepth.map(n => ujson.Num(n.toDouble)).getOrElse(ujson.Null),
    "topic" -> topic.map(ujson.Str(_)).getOrElse(ujson.Null),
    "unsupportedConstraints" -> ujson.Arr.from(
      unsupportedConstraints.map(c => ujson.Obj("kind" -> c.kind, "text" -> c.text))
    )
  )
}

/** Bounds a plan draft is validated against (from configuration). */
final case class PlanBounds(
    windowLabels: List[String],
    maxResearcherNames: Int,
    rankingTopK: Int,
    maxPathDepth: Int
)

object PlanBounds {
  val MaxNameChars = 100
  val MaxTopicChars = 200
  val MaxOperations = 6
  val MaxUnsupported = 10

  /** The metrics a ranking may name (the RM4 list, decision 4g). */
  val rankingMetrics: List[CollaborationMetric] = RankingMetrics.ranked

  def of(windowLabels: List[String], r: RuntimeSettings): PlanBounds =
    PlanBounds(windowLabels, r.maxResearcherNames, r.rankingCacheTopK, r.maxPathDepth)
}

/** Everything the workflow needs besides the stores: windows, runtime bounds, the Spark graph run
  * being queried, and which windows the graph port actually holds (the local graph loads only some
  * windows; on Neptune every window is present).
  */
final case class RuntimeContext(
    windows: List[NamedWindow],
    runtime: RuntimeSettings,
    vectorTopK: Int,
    graphRunId: RunId,
    graphWindowLabels: Set[String],
    configFingerprint: String,
    maxStructuredRetries: Int,
    contextTokenBudget: Int,
    maxOutputTokens: Int
) {
  def windowOf(label: String): Option[NamedWindow] = windows.find(_.label == label)
  def yearWindow(label: String): Option[YearWindow] =
    windowOf(label).map(w => YearWindow(w.startYear, w.endYear))
  def windowLabels: List[String] = windows.map(_.label)

  /** The widest configured window (smallest start, then largest end): the "full" window used for
    * resolver context.
    */
  def fullWindowLabel: String =
    windows.sortBy(w => (w.startYear, -w.endYear)).head.label
  def bounds: PlanBounds = PlanBounds.of(windowLabels, runtime)
}

object RuntimeContext {
  def from(
      config: ScholarChainConfig,
      graphRunId: RunId,
      graphWindowLabels: Set[String]
  ): Either[String, RuntimeContext] =
    for {
      r <- config.runtime.toRight("runtime section is not configured")
      l <- config.llm.toRight("llm section is not configured")
      v <- config.vectors.toRight("vectors section is not configured")
    } yield RuntimeContext(
      config.graph.windows,
      r,
      v.queryTopK,
      graphRunId,
      graphWindowLabels,
      config.fingerprint,
      l.maxStructuredRetries,
      l.contextTokenBudget,
      l.maxOutputTokens
    )
}

/** What happened to one model call. Model text that fails validation is never kept: only its
  * sha256, its length and the validation error.
  */
final case class ModelAttempt(
    task: String,
    attempt: Int,
    outcome: String,
    outputSha256: Option[String],
    outputLength: Option[Int],
    error: Option[String],
    inputTokens: Option[Long],
    outputTokens: Option[Long]
) {
  def toJson: ujson.Obj = ujson.Obj(
    "task" -> task,
    "attempt" -> attempt,
    "outcome" -> outcome,
    "outputSha256" -> outputSha256.map(ujson.Str(_)).getOrElse(ujson.Null),
    "outputLength" -> outputLength.map(n => ujson.Num(n.toDouble)).getOrElse(ujson.Null),
    "error" -> error.map(ujson.Str(_)).getOrElse(ujson.Null),
    "inputTokens" -> inputTokens.map(n => ujson.Num(n.toDouble)).getOrElse(ujson.Null),
    "outputTokens" -> outputTokens.map(n => ujson.Num(n.toDouble)).getOrElse(ujson.Null)
  )
}

object ModelAttempt {
  val Valid = "valid"
  val Invalid = "invalid"
  val CallFailed = "call-failed"

  /** The record of a model output that failed validation: hash, length, error; never the text. */
  def invalid(
      task: String,
      n: Int,
      text: String,
      error: String,
      in: Option[Long],
      out: Option[Long]
  ): ModelAttempt =
    ModelAttempt(
      task,
      n,
      Invalid,
      Some(ContentHash.of(text)),
      Some(text.length),
      Some(error.take(300)),
      in,
      out
    )
}

// ---- retrieved facts (each becomes one evidence item) ----------------------------------------

/** Where a stored value came from: the item reference, the Spark run and policy that produced it.
  */
final case class SourceRef(
    recordRef: String,
    sparkRunId: RunId,
    policyVersion: String,
    snapshotId: String
)

/** A resolved researcher, as cited in answers. */
final case class ResearcherRef(id: ResearcherId, displayName: String)

sealed trait Fact {

  /** Lower sorts first when the context budget forces rows to be dropped (lowest-ranked last). */
  def priority: Int
}

/** One stored metric of one researcher in one window (optionally a ranking position). `groupId` is
  * the component or community ID for `component_size` and `community_size`.
  */
final case class MetricFact(
    researcher: ResearcherRef,
    windowLabel: String,
    window: YearWindow,
    metric: CollaborationMetric,
    value: Double,
    rank: Option[Int],
    groupId: Option[ResearcherId],
    source: SourceRef,
    priority: Int
) extends Fact

/** One coauthor edge (a direct collaboration or a path hop). */
final case class EdgeFact(
    a: ResearcherRef,
    b: ResearcherRef,
    windowLabel: String,
    window: YearWindow,
    edgeId: String,
    weight: Double,
    jointPublicationCount: Long,
    firstYear: Int,
    lastYear: Int,
    supportingWorkIds: List[String],
    source: SourceRef,
    priority: Int
) extends Fact

/** No coauthor edge between two researchers in one window (a bounded single-shot lookup). */
final case class EdgeAbsentFact(
    a: ResearcherRef,
    b: ResearcherRef,
    windowLabel: String,
    window: YearWindow,
    source: SourceRef,
    priority: Int
) extends Fact

/** A shortest coauthorship path. Hops come only from the path result or the Spark path evidence
  * item (never from vector metadata).
  */
final case class PathFact(
    researchers: List[ResearcherRef],
    windowLabel: String,
    window: YearWindow,
    origin: String,
    sparkEvidenceId: Option[EvidenceId],
    truncated: Boolean,
    source: SourceRef,
    priority: Int
) extends Fact {
  def hops: Int = researchers.size - 1
}

/** A bounded path search that found nothing: complete (no path within `maxDepth`) or stopped at the
  * time limit (unknown).
  */
final case class PathAbsentFact(
    from: ResearcherRef,
    to: ResearcherRef,
    windowLabel: String,
    window: YearWindow,
    maxDepth: Int,
    complete: Boolean,
    depthSearched: Int,
    source: SourceRef,
    priority: Int
) extends Fact

/** A publication (title, year, venue, team size) from the WorkById template. */
final case class WorkFact(
    workId: WorkId,
    title: String,
    year: Int,
    venueKey: String,
    teamSize: Int,
    dblpKey: String,
    owner: Option[ResearcherRef],
    source: SourceRef,
    priority: Int
) extends Fact

/** Identity resolution of one researcher (resolved, or one of several candidates). */
final case class IdentityFact(
    researcher: ResearcherRef,
    askedName: String,
    matchedValue: String,
    outcome: String,
    confidence: Option[Double],
    resolved: Boolean,
    publicationCount: Option[Double],
    distinctCoauthors: Option[Double],
    topCoauthors: List[ResearcherRef],
    fullWindowLabel: String,
    source: SourceRef,
    priority: Int
) extends Fact

/** One retrieved semantic document: a pointer (and, for the model only, a verified excerpt). */
final case class SemanticFact(
    entry: SemanticContextEntry,
    researcherId: Option[ResearcherId],
    excerpt: Option[String],
    priority: Int
) extends Fact

object Fact {

  /** Display form of a stored value: integers without decimals, others with 4 decimals. */
  def fmt(v: Double): String =
    if (v.isWhole && math.abs(v) < 1e15) v.toLong.toString
    else java.math.BigDecimal.valueOf(v).setScale(4, java.math.RoundingMode.HALF_UP).toPlainString

  def windowText(w: YearWindow): String = s"${w.startYear}-${w.endYear}"

  /** The researchers a fact is about (metric and ranking subjects, edge endpoints, every path hop,
    * a work's owner, an identity candidate). Group IDs (component, community) are not researchers
    * here.
    */
  def researchers(f: Fact): List[ResearcherId] = f match {
    case m: MetricFact     => List(m.researcher.id)
    case e: EdgeFact       => List(e.a.id, e.b.id)
    case e: EdgeAbsentFact => List(e.a.id, e.b.id)
    case p: PathFact       => p.researchers.map(_.id)
    case p: PathAbsentFact => List(p.from.id, p.to.id)
    case w: WorkFact       => w.owner.map(_.id).toList
    case i: IdentityFact   => List(i.researcher.id)
    case _: SemanticFact   => Nil
  }

  /** Display names of the researchers a fact is about, where the fact carries them. */
  def researcherRefs(f: Fact): List[ResearcherRef] = f match {
    case m: MetricFact     => List(m.researcher)
    case e: EdgeFact       => List(e.a, e.b)
    case e: EdgeAbsentFact => List(e.a, e.b)
    case p: PathFact       => p.researchers
    case p: PathAbsentFact => List(p.from, p.to)
    case w: WorkFact       => w.owner.toList
    case i: IdentityFact   => List(i.researcher)
    case _: SemanticFact   => Nil
  }

  /** Human phrase of a metric. */
  def phrase(m: CollaborationMetric): String = m match {
    case CollaborationMetric.PublicationCount         => "publications (P)"
    case CollaborationMetric.DistinctCoauthors        => "distinct coauthors (C)"
    case CollaborationMetric.TotalCoauthorOccurrences => "total coauthor occurrences (T)"
    case CollaborationMetric.AverageCoauthorsPerPaper => "average coauthors per paper (A)"
    case CollaborationMetric.MaxCoauthorsOnOnePaper   => "maximum coauthors on one paper (M)"
    case CollaborationMetric.WeightedDegree           => "weighted degree (W)"
    case CollaborationMetric.Coreness                 => "coreness"
    case CollaborationMetric.ParticipationCoefficient => "participation coefficient"
    case CollaborationMetric.CrossCommunityDegree     => "coauthors in other communities"
    case CollaborationMetric.CommunitiesConnected     => "other communities connected"
    case CollaborationMetric.ComponentSize            => "connected component size"
    case CollaborationMetric.CommunitySize            => "community size"
    case other                                        => other.wire.replace('_', ' ')
  }
}
