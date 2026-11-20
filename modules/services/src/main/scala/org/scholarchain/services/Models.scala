package org.scholarchain.services

import upickle.default.{macroRW, ReadWriter}

import org.scholarchain.core._
import org.scholarchain.rag.runtime.AbstentionCodes

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

  /** The view of `r`, passed through [[ResponseGuard.answer]] (C-02, C-03). */
  def of(r: AnswerResponse, evidence: Option[List[EvidenceItem]]): AnswerView =
    ResponseGuard.answer(
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
    )
}

/** The last guard on what answer and evidence responses show (Phase 11 C-02, C-03). rag builds the
  * texts and provenance; services adds no downstream text and checks what it passes through:
  *   - C-03: a source record reference that is a URI (`s3://…`, `https://…`: any `<scheme>://`) is
  *     withheld from `provenance.sourceRecordIds` of the answer, its claims and every evidence item
  *     (and so from `sourceUris`). rag stopped emitting the text's S3 URI in Phase 11; this also
  *     covers bundles and results stored before that change. Stable IDs, dblp keys and
  *     `Table:pk/sk` record references are not URIs and pass unchanged.
  *   - C-02: an `answerText`, identity note, abstention reason or claim uncertainty note that holds
  *     a store or provider reference (an `arn:aws` string, an `s3://` URI, an `amazonaws.com` host,
  *     or a stand-alone 12-digit number such as an account ID) is replaced by a fixed text. rag
  *     produces only fixed texts there since Phase 11, so this never changes a normal answer.
  */
object ResponseGuard {
  private val UriScheme = "^[A-Za-z][A-Za-z0-9+.-]*://".r
  private val Downstream =
    "(?i)(arn:aws|s3://|amazonaws\\.com|(?<![0-9A-Za-z])[0-9]{12}(?![0-9A-Za-z]))".r

  /** Replaces an answer text that held a store or provider reference. */
  val WithheldAnswerText: String =
    "The answer text was withheld because it held a store or provider reference."

  /** Replaces a note (identity note, abstention reason, uncertainty) that held one. */
  val WithheldNote: String = "A note was withheld because it held a store or provider reference."

  /** True for a source record reference that is a URI (it may name a bucket or a host). */
  def isUri(ref: String): Boolean = UriScheme.findPrefixOf(ref).nonEmpty

  /** True when `text` holds a store or provider reference. */
  def hasDownstreamText(text: String): Boolean = Downstream.findFirstIn(text).nonEmpty

  def provenance(p: Provenance): Provenance =
    if (p.sourceRecordIds.exists(isUri))
      p.copy(sourceRecordIds = p.sourceRecordIds.filterNot(isUri))
    else p

  def item(i: EvidenceItem): EvidenceItem = i.copy(provenance = provenance(i.provenance))

  private def note(s: String): String = if (hasDownstreamText(s)) WithheldNote else s

  def answer(v: AnswerView): AnswerView =
    v.copy(
      answerText = if (hasDownstreamText(v.answerText)) WithheldAnswerText else v.answerText,
      claims = v.claims.map(c =>
        c.copy(uncertainty = c.uncertainty.map(note), provenance = provenance(c.provenance))
      ),
      identityNotes = v.identityNotes.map(note),
      abstentionReason = v.abstentionReason.map(note),
      provenance = provenance(v.provenance),
      evidence = v.evidence.map(_.map(item))
    )

  def result(r: CompletedResult): CompletedResult = r.copy(answer = answer(r.answer))
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

  /** The view of a stored item; URI source references are withheld first (C-03), so neither
    * `provenance.sourceRecordIds` nor `sourceUris` can name a bucket.
    */
  def of(stored: EvidenceItem): EvidenceView = {
    val i = ResponseGuard.item(stored)
    EvidenceView(
      i.schemaVersion,
      i.evidenceId,
      i.evidenceType,
      i.subjectIds,
      i.window,
      i.metric,
      i.snippet,
      i.provenance,
      EvidenceService.sourceUris(i)
    )
  }
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
  *
  * QueryAudit v2 (Phase 11): `auditSchemaVersion` is the stored record's schema version;
  * `queryTemplateIds`, `indexesQueried`, `metadataFilterKeys` (keys only), `modelCalls` (per model
  * task: logical calls, invalid and truncated outputs, dropped bound numbers, fallbacks) and
  * `cacheHits` (per cache) come from the record. A version 1 record did not record them: they are
  * empty and a note says so.
  *
  * QueryAudit v3 (Phase 15, G-28): `abstentionReasons` lists the stored abstention codes, each with
  * rag's fixed short text ([[org.scholarchain.rag.runtime.AbstentionCodes]]; an unknown code reads
  * as the `unclassified` text). Always present: `[]` when the answer did not abstain or the record
  * is older than version 3 (which stored no codes). Never model prose.
  */
final case class ExplainView(
    queryId: String,
    attemptCount: Int,
    auditSchemaVersion: Int,
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
    abstentionReasons: List[AbstentionReason],
    queryTemplateIds: List[String],
    indexesQueried: List[String],
    metadataFilterKeys: List[String],
    modelCalls: Map[String, ModelCallCounts],
    cacheHits: Map[String, Long],
    scoreComponents: List[String],
    notes: List[String]
)

/** One entry of `abstentionReasons` in `GET /v1/explain/{queryId}`: a stored abstention code and
  * its fixed short text.
  */
final case class AbstentionReason(code: String, text: String)
object AbstentionReason { implicit val rw: ReadWriter[AbstentionReason] = macroRW }

object ExplainView {
  implicit val rw: ReadWriter[ExplainView] = macroRW

  /** The note sent with the empty `scoreComponents` (decision 7g). */
  val ScoreNote = "rankings use one stored metric; no composite score is computed"

  /** The note sent for a schema version 1 audit record. */
  val V1Note =
    "this query's audit record is schema version 1: queryTemplateIds, indexesQueried, metadataFilterKeys, modelCalls and cacheHits were not recorded"

  /** The note on what `modelCalls` does not hold. */
  val AttemptsNote =
    "modelCalls counts logical model calls per task; HTTP attempts and retries are counted per process, not per query"

  def of(latest: QueryAudit, attempts: Int): ExplainView =
    ExplainView(
      latest.queryRunId.value,
      attempts,
      latest.schemaVersion,
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
      AbstentionCodes.reasons(latest.abstentionCodes).map { case (c, t) =>
        AbstentionReason(c, t)
      },
      latest.queryTemplateIds,
      latest.indexesQueried,
      latest.metadataFilterKeys,
      latest.modelCalls,
      latest.cacheHits,
      Nil,
      List(ScoreNote, if (latest.schemaVersion < 2) V1Note else AttemptsNote)
    )
}
