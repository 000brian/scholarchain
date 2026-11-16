package org.scholarchain.services

import upickle.default.{macroRW, ReadWriter}

import org.scholarchain.core._

// REST response models (decision 7j). Encoded only with Json.encode (sorted keys, indent 2); the
// frozen examples under src/test/resources/api/v1/ decode into them and re-encode unchanged.
// Option fields are written as null when empty (uPickle 4, as in core).

/** Every non-2xx body except `/readyz` (decision 7i). `message` is the code's fixed text. */
final case class ErrorBody(
    error: String,
    message: String,
    traceId: String,
    retryable: Boolean,
    details: ujson.Value
)
object ErrorBody { implicit val rw: ReadWriter[ErrorBody] = macroRW }

/** `GET /healthz`: liveness only. */
final case class HealthBody(status: String, role: String)
object HealthBody { implicit val rw: ReadWriter[HealthBody] = macroRW }

/** `GET /readyz`: `status` is `ready` or `not_ready`; each check is `ok` or `fail`. */
final case class ReadinessBody(status: String, role: String, checks: Map[String, String])
object ReadinessBody { implicit val rw: ReadWriter[ReadinessBody] = macroRW }

/** The services view of a core AnswerResponse v2: every field except `bundle`, plus `bundleId`,
  * plus `evidence` (the bundle's items) when the request asked for evidence (else null). Built only
  * from a contract-valid response.
  */
final case class AnswerView(
    schemaVersion: Int,
    question: String,
    answerText: String,
    modelId: Option[String],
    plan: QueryPlan,
    bundleId: Option[EvidenceId],
    claims: List[AnswerClaim],
    identityNotes: List[String],
    abstained: Boolean,
    abstentionReason: Option[String],
    provenance: Provenance,
    evidence: Option[List[EvidenceItem]]
)
object AnswerView {
  implicit val rw: ReadWriter[AnswerView] = macroRW

  def of(r: AnswerResponse, evidence: Option[List[EvidenceItem]]): AnswerView =
    AnswerView(
      r.schemaVersion,
      r.question,
      r.answerText,
      r.modelId,
      r.plan,
      r.bundle.map(_.bundleId),
      r.claims,
      r.identityNotes,
      r.abstained,
      r.abstentionReason,
      r.provenance,
      evidence
    )
}

/** `explain`: `/v1/explain/{queryId}`; `evidence`: one `/v1/evidence/{id}?window=<start>-<end>` per
  * cited item (no window parameter for a window-less item).
  */
final case class ResultLinks(explain: String, evidence: List[String])
object ResultLinks { implicit val rw: ReadWriter[ResultLinks] = macroRW }

/** The one representation of a finished query (decision 7c). `queryId` is rag's query run ID.
  * `notes` lists the request fields that were accepted but not applied.
  */
final case class CompletedResult(
    queryId: String,
    answer: AnswerView,
    links: ResultLinks,
    notes: List[String]
)
object CompletedResult { implicit val rw: ReadWriter[CompletedResult] = macroRW }

/** 202 answer of an async query (and of an async replay, with the job's current status). */
final case class QueryAcknowledgment(
    queryId: String,
    jobId: String,
    status: String,
    statusUrl: String
)
object QueryAcknowledgment { implicit val rw: ReadWriter[QueryAcknowledgment] = macroRW }

final case class JobErrorView(code: String, message: String)
object JobErrorView { implicit val rw: ReadWriter[JobErrorView] = macroRW }

/** `GET /v1/jobs/{jobId}`. Times are ISO-8601 UTC instants; `state` is QUEUED, RUNNING, SUCCEEDED
  * or FAILED; `resultUrl` is set when the job succeeded.
  */
final case class JobStatusView(
    jobId: String,
    queryId: String,
    mode: String,
    state: String,
    stage: Option[String],
    createdAt: String,
    updatedAt: String,
    attempts: Int,
    resultUrl: Option[String],
    error: Option[JobErrorView]
)
object JobStatusView { implicit val rw: ReadWriter[JobStatusView] = macroRW }

/** `GET /v1/evidence/{evidenceId}`: the core EvidenceItem plus `sourceUris` (a dblp record URL per
  * dblp key in `provenance.sourceRecordIds`, else a `scholarchain:` reference).
  */
final case class EvidenceView(
    schemaVersion: Int,
    evidenceId: EvidenceId,
    evidenceType: EvidenceType,
    subjectIds: List[String],
    window: Option[YearWindow],
    metric: Option[MetricValue],
    snippet: Option[String],
    provenance: Provenance,
    sourceUris: List[String]
)
object EvidenceView {
  implicit val rw: ReadWriter[EvidenceView] = macroRW

  def of(i: EvidenceItem, sourceUris: List[String]): EvidenceView =
    EvidenceView(
      i.schemaVersion,
      i.evidenceId,
      i.evidenceType,
      i.subjectIds,
      i.window,
      i.metric,
      i.snippet,
      i.provenance,
      sourceUris
    )
}

/** `GET /v1/graph/nodes/{nodeId}`: `kind` is `researcher` or `work`; `properties` are the stored
  * node properties of the graph run `graphRunId`.
  */
final case class NodeView(nodeId: String, kind: String, graphRunId: String, properties: ujson.Value)
object NodeView { implicit val rw: ReadWriter[NodeView] = macroRW }

/** `GET /v1/graph/nodes/{nodeId}/neighbors`: one page of rows. */
final case class NeighborsPage(
    nodeId: String,
    window: String,
    rows: List[ujson.Value],
    truncated: Boolean,
    nextCursor: Option[String]
)
object NeighborsPage { implicit val rw: ReadWriter[NeighborsPage] = macroRW }

/** `GET /v1/explain/{queryId}`: the latest stored `query-audit` record of the query run, without
  * question text hashes and answer hashes, plus `attemptCount` (audit records of the run).
  * `stageMillis` holds the stages executed and the `plannerFallback` / `synthesisFallback` markers.
  * Never prompt text, model output or reasoning.
  */
final case class ExplainView(
    queryId: String,
    attemptCount: Int,
    plan: QueryPlan,
    planSource: PlanSource,
    graphRunId: RunId,
    promptVersions: List[String],
    modelId: Option[String],
    evidenceIds: List[EvidenceId],
    bundleId: Option[EvidenceId],
    resolvedResearcherIds: List[ResearcherId],
    unresolvedNames: List[String],
    metricPolicyVersions: List[String],
    stageMillis: Map[String, Long],
    latencyMillis: Long,
    inputTokens: Option[Long],
    outputTokens: Option[Long],
    verificationCounts: Map[String, Int],
    abstained: Boolean,
    scoreComponents: List[String],
    notes: List[String]
)
object ExplainView {
  implicit val rw: ReadWriter[ExplainView] = macroRW

  /** The note sent with the empty `scoreComponents` (decision 7g). */
  val ScoreNote = "rankings use one stored metric; no composite score is computed"

  def of(latest: QueryAudit, attempts: Int): ExplainView =
    ExplainView(
      latest.queryRunId.value,
      attempts,
      latest.plan,
      latest.planSource,
      latest.graphRunId,
      latest.promptVersions,
      latest.modelId,
      latest.evidenceIds,
      latest.bundleId,
      latest.resolvedResearcherIds,
      latest.unresolvedNames,
      latest.metricPolicyVersions,
      latest.stageMillis,
      latest.latencyMillis,
      latest.inputTokens,
      latest.outputTokens,
      latest.verificationCounts,
      latest.abstained,
      Nil,
      List(ScoreNote)
    )
}
