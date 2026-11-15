package org.scholarchain.spark

import org.scholarchain.core._

// Spark-encodable mirrors of the core records written by this module.
//
// Each output table stores exactly the fields of one core record (stable IDs and enums as their
// JSON wire strings, nested records as structs). `toCore` rebuilds the core record and
// `CoreMirror` validates it with `Contract.validate` and round-trips it through
// `Contract.encode`/`Contract.decode`, so every exported row maps to a core record and schema
// version. A few tables carry extra columns next to the record (for example the hops of a path);
// those columns are documented on the table row.

final case class ProvenanceRow(
    schemaVersion: Int,
    sourceType: String,
    datasetId: String,
    snapshotId: String,
    sourceRecordIds: Seq[String],
    runId: String,
    jobName: String,
    policyVersion: String,
    contentHash: Option[String]
) {
  def toCore: Either[String, Provenance] =
    for {
      st <- SourceType.fromWire(sourceType)
      run <- RunId.parse(runId)
    } yield Provenance(
      schemaVersion,
      st,
      datasetId,
      snapshotId,
      Option(sourceRecordIds).map(_.toList).orNull,
      run,
      jobName,
      policyVersion,
      contentHash
    )
}
object ProvenanceRow {
  def fromCore(p: Provenance): ProvenanceRow = ProvenanceRow(
    p.schemaVersion,
    p.sourceType.wire,
    p.datasetId,
    p.snapshotId,
    p.sourceRecordIds,
    p.runId.value,
    p.jobName,
    p.policyVersion,
    p.contentHash
  )
}

/** Provenance context of one Spark run: every record this run writes carries it. */
final case class ProvCtx(
    runId: String,
    datasetId: String,
    snapshotId: String,
    policyVersion: String
) {
  def row(
      jobName: String,
      sourceRecordIds: Seq[String],
      contentHash: Option[String] = None
  ): ProvenanceRow =
    ProvenanceRow(
      SchemaVersions.currentOf("Provenance"),
      SourceType.Pipeline.wire,
      datasetId,
      snapshotId,
      sourceRecordIds,
      runId,
      jobName,
      policyVersion,
      contentHash
    )
}

final case class MetricValueRow(metric: String, value: Double) {
  def toCore: Either[String, MetricValue] =
    CollaborationMetric.fromWire(metric).map(MetricValue(_, value))
}

final case class SourcedExternalIdRow(
    idType: String,
    value: String,
    sourceType: String,
    datasetId: String,
    snapshotId: String,
    sourceRecordId: String
) {
  def toCore: Either[String, SourcedExternalId] =
    for {
      t <- ExternalIdType.fromWire(idType)
      s <- SourceType.fromWire(sourceType)
    } yield SourcedExternalId(t, value, s, datasetId, snapshotId, sourceRecordId)
}

private[spark] object Seqs {
  def sequence[A](xs: Seq[Either[String, A]]): Either[String, List[A]] =
    xs.foldRight[Either[String, List[A]]](Right(Nil)) { (e, acc) =>
      for { a <- e; rest <- acc } yield a :: rest
    }
  def list[A](xs: Seq[A]): List[A] = Option(xs).map(_.toList).orNull
}
import Seqs._

/** GraphNode v1. Table `nodes/node_label=<label>`. */
final case class GraphNodeRow(
    schemaVersion: Int,
    nodeId: String,
    label: String,
    displayName: String,
    properties: Map[String, String],
    provenance: ProvenanceRow
) {
  def toCore: Either[String, GraphNode] =
    for {
      l <- GraphNodeLabel.fromWire(label)
      p <- provenance.toCore
    } yield GraphNode(schemaVersion, nodeId, l, displayName, properties, p)
}

/** GraphEdge v1. Table `edges/edge_label=<label>/window_label=<w>`. */
final case class GraphEdgeRow(
    schemaVersion: Int,
    edgeId: String,
    label: String,
    from: String,
    to: String,
    window: YearWindow,
    weight: Option[Double],
    provenance: ProvenanceRow
) {
  def toCore: Either[String, GraphEdge] =
    for {
      id <- EdgeId.parse(edgeId)
      l <- GraphEdgeLabel.fromWire(label)
      p <- provenance.toCore
    } yield GraphEdge(schemaVersion, id, l, from, to, window, weight, p)
}

/** CanonicalResearcher v1. Table `canonical_researcher`. */
final case class CanonicalResearcherRow(
    schemaVersion: Int,
    researcherId: String,
    anchorMentionId: String,
    displayName: String,
    dblpNames: Seq[String],
    externalIds: Seq[SourcedExternalIdRow],
    provenance: ProvenanceRow
) {
  def toCore: Either[String, CanonicalResearcher] =
    for {
      r <- ResearcherId.parse(researcherId)
      a <- MentionId.parse(anchorMentionId)
      x <- sequence(externalIds.map(_.toCore))
      p <- provenance.toCore
    } yield CanonicalResearcher(schemaVersion, r, a, displayName, list(dblpNames), x, p)
}

/** AuthorshipRecord v1. Table `authorship`. */
final case class AuthorshipRow(
    schemaVersion: Int,
    researcherId: String,
    workId: String,
    mentionId: String,
    position: Int,
    year: Int,
    matchConfidence: Double,
    provenance: ProvenanceRow
) {
  def toCore: Either[String, AuthorshipRecord] =
    for {
      r <- ResearcherId.parse(researcherId)
      w <- WorkId.parse(workId)
      m <- MentionId.parse(mentionId)
      p <- provenance.toCore
    } yield AuthorshipRecord(schemaVersion, r, w, m, position, year, matchConfidence, p)
}

/** AuthorMention v1, unchanged from the Hadoop input (dblp provenance). Table `unresolved_mention`:
  * mentions without an identity assignment, preserved without a merge.
  */
final case class AuthorMentionRow(
    schemaVersion: Int,
    mentionId: String,
    workId: String,
    position: Int,
    name: String,
    orcid: Option[String],
    provenance: ProvenanceRow
) {
  def toCore: Either[String, AuthorMention] =
    for {
      m <- MentionId.parse(mentionId)
      w <- WorkId.parse(workId)
      p <- provenance.toCore
    } yield AuthorMention(schemaVersion, m, w, position, name, orcid, p)
}
object AuthorMentionRow {
  def fromCore(m: AuthorMention): AuthorMentionRow = AuthorMentionRow(
    m.schemaVersion,
    m.mentionId.value,
    m.workId.value,
    m.position,
    m.name,
    m.orcid,
    ProvenanceRow.fromCore(m.provenance)
  )
}

/** CoauthorEdge v1. Table `coauthor_edge/window_label=<w>`. */
final case class CoauthorEdgeRow(
    schemaVersion: Int,
    edgeId: String,
    left: String,
    right: String,
    window: YearWindow,
    weight: Double,
    sharedWorkCount: Int,
    firstYear: Int,
    lastYear: Int,
    supportingWorkIds: Seq[String],
    minIdentityConfidence: Double,
    provenance: ProvenanceRow
) {
  def toCore: Either[String, CoauthorEdge] =
    for {
      id <- EdgeId.parse(edgeId)
      l <- ResearcherId.parse(left)
      r <- ResearcherId.parse(right)
      ws <- sequence(supportingWorkIds.map(WorkId.parse))
      p <- provenance.toCore
    } yield CoauthorEdge(
      schemaVersion,
      id,
      l,
      r,
      window,
      weight,
      sharedWorkCount,
      firstYear,
      lastYear,
      ws,
      minIdentityConfidence,
      p
    )
}

/** ResearcherMetrics v1. Tables `metrics/metric_type=<t>/window_label=<w>`, `bridges/...`,
  * `kcore/...`.
  */
final case class ResearcherMetricsRow(
    schemaVersion: Int,
    researcherId: String,
    window: YearWindow,
    values: Seq[MetricValueRow],
    identityConfidence: Double,
    provenance: ProvenanceRow
) {
  def toCore: Either[String, ResearcherMetrics] =
    for {
      r <- ResearcherId.parse(researcherId)
      vs <- sequence(values.map(_.toCore))
      p <- provenance.toCore
    } yield ResearcherMetrics(schemaVersion, r, window, vs, identityConfidence, p)
}

/** ResearcherGroupMembership v1. Tables `components/window_label=<w>`,
  * `communities/window_label=<w>`.
  */
final case class GroupMembershipRow(
    schemaVersion: Int,
    researcherId: String,
    window: YearWindow,
    groupKind: String,
    groupId: String,
    groupSize: Int,
    provenance: ProvenanceRow
) {
  def toCore: Either[String, ResearcherGroupMembership] =
    for {
      r <- ResearcherId.parse(researcherId)
      k <- GroupKind.fromWire(groupKind)
      g <- ResearcherId.parse(groupId)
      p <- provenance.toCore
    } yield ResearcherGroupMembership(schemaVersion, r, window, k, g, groupSize, p)
}

/** EvidenceItem v1 (embedded as column `evidence` of the evidence tables). */
final case class EvidenceItemRow(
    schemaVersion: Int,
    evidenceId: String,
    evidenceType: String,
    subjectIds: Seq[String],
    window: Option[YearWindow],
    metric: Option[MetricValueRow],
    snippet: Option[String],
    provenance: ProvenanceRow
) {
  def toCore: Either[String, EvidenceItem] =
    for {
      id <- EvidenceId.parse(evidenceId)
      t <- EvidenceType.fromWire(evidenceType)
      m <- metric match {
        case None    => Right(None)
        case Some(v) => v.toCore.map(Some(_))
      }
      p <- provenance.toCore
    } yield EvidenceItem(schemaVersion, id, t, list(subjectIds), window, m, snippet, p)
}
object EvidenceItemRow {

  /** A `graph_path` or `metric` evidence item; the ID follows `EvidenceId.of`. */
  def of(
      evidenceType: EvidenceType,
      subjectIds: Seq[String],
      window: YearWindow,
      metric: Option[MetricValueRow],
      snippet: Option[String],
      provenance: ProvenanceRow
  ): EvidenceItemRow = {
    val run = RunId.parse(provenance.runId).fold(e => throw new IllegalArgumentException(e), r => r)
    EvidenceItemRow(
      SchemaVersions.currentOf("EvidenceItem"),
      EvidenceId.of(evidenceType, run, subjectIds).value,
      evidenceType.wire,
      subjectIds,
      Some(window),
      metric,
      snippet,
      provenance
    )
  }
}

/** One work behind M_r with its distinct researcher count and dblp team size. */
final case class WorkTeamSize(workId: String, distinctResearchers: Int, dblpTeamSize: Int)

/** Table `evidence/evidence_type=metric/window_label=<w>`: the works behind M_r. `evidence` is a
  * `metric` EvidenceItem (subjectIds = researcher ID then the sorted work IDs); `works` lists the
  * same works with their dblp `teamSize` next to it. Unique key: (evidence.evidenceId, window).
  */
final case class MetricEvidenceRow(
    researcherId: String,
    evidence: EvidenceItemRow,
    works: Seq[WorkTeamSize]
)

/** One hop of a materialized path, copied from the CoauthorEdge of that pair and window. */
final case class PathHop(
    hop: Int,
    from: String,
    to: String,
    edgeId: String,
    weight: Double,
    firstYear: Int,
    lastYear: Int,
    supportingWorkIds: Seq[String]
)

/** Table `path_evidence/window_label=<w>`: `evidence` is a `graph_path` EvidenceItem (subjectIds
  * = researcher IDs in path order); `hops` carries each hop's edge facts. Unique key:
  * (evidence.evidenceId, window).
  */
final case class PathEvidenceRow(
    pairKind: String,
    pairIndex: Int,
    sourceId: String,
    targetId: String,
    hopCount: Int,
    evidence: EvidenceItemRow,
    hops: Seq[PathHop]
)

/** Table `semantic_summary/summary_kind=<k>/window_label=<w>`: `evidence` carries the template text
  * as `snippet` with `provenance.contentHash = ContentHash.of(snippet)`.
  *   - researcher_profile: `metric` evidence, subjectIds = [researcherId], metric = P_r.
  *   - community: `metric` evidence, subjectIds = [groupId, top members...], metric = size.
  *   - path: `graph_path` evidence, same subjectIds (and evidenceId) as the path it describes.
  *
  * Unique key: (summaryKind, evidence.evidenceId, window).
  */
final case class SemanticSummaryRow(summaryKind: String, evidence: EvidenceItemRow)

/** Versioned reject record (format `scholarchain.spark.reject`, version 1). */
final case class RejectRow(
    table: String,
    schema: String,
    reasons: Seq[String],
    rawSha256: String,
    rawLength: Int,
    raw: String
)

/** Integrity violation (format `scholarchain.spark.integrity-violation`, version 1). */
final case class IntegrityViolationRow(
    check: String,
    windowLabel: String,
    subjectId: String,
    detail: String
)

/** Validation and JSON round trip of a mirror row through the core contract.
  *
  * Every implementation is a singleton object whose methods resolve the core uPickle and Validator
  * instances statically, so a mirror can travel in a Spark closure without serializing them.
  */
sealed abstract class CoreMirror[R](val schema: String) extends Serializable {
  def errors(r: R): List[String]
  def roundTrip(r: R): Either[String, Unit]
}

object CoreMirror {
  import upickle.default.ReadWriter

  def errorsOf[C](c: Either[String, C])(implicit v: Validator[C]): List[String] =
    c.fold(e => List(e), x => Contract.validate(x))

  def roundTripOf[C](c: Either[String, C])(implicit
      rw: ReadWriter[C],
      v: Validator[C]
  ): Either[String, Unit] =
    for {
      x <- c
      json <- Contract.encode(x).left.map(_.message)
      back <- Contract.decode[C](json).left.map(_.message)
      _ <- if (back == x) Right(()) else Left("round trip changed the record")
    } yield ()

  object GraphNodeM extends CoreMirror[GraphNodeRow]("GraphNode") {
    def errors(r: GraphNodeRow) = errorsOf(r.toCore)
    def roundTrip(r: GraphNodeRow) = roundTripOf(r.toCore)
  }
  object GraphEdgeM extends CoreMirror[GraphEdgeRow]("GraphEdge") {
    def errors(r: GraphEdgeRow) = errorsOf(r.toCore)
    def roundTrip(r: GraphEdgeRow) = roundTripOf(r.toCore)
  }
  object ResearcherM extends CoreMirror[CanonicalResearcherRow]("CanonicalResearcher") {
    def errors(r: CanonicalResearcherRow) = errorsOf(r.toCore)
    def roundTrip(r: CanonicalResearcherRow) = roundTripOf(r.toCore)
  }
  object AuthorshipM extends CoreMirror[AuthorshipRow]("AuthorshipRecord") {
    def errors(r: AuthorshipRow) = errorsOf(r.toCore)
    def roundTrip(r: AuthorshipRow) = roundTripOf(r.toCore)
  }
  object MentionM extends CoreMirror[AuthorMentionRow]("AuthorMention") {
    def errors(r: AuthorMentionRow) = errorsOf(r.toCore)
    def roundTrip(r: AuthorMentionRow) = roundTripOf(r.toCore)
  }
  object CoauthorEdgeM extends CoreMirror[CoauthorEdgeRow]("CoauthorEdge") {
    def errors(r: CoauthorEdgeRow) = errorsOf(r.toCore)
    def roundTrip(r: CoauthorEdgeRow) = roundTripOf(r.toCore)
  }
  object MetricsM extends CoreMirror[ResearcherMetricsRow]("ResearcherMetrics") {
    def errors(r: ResearcherMetricsRow) = errorsOf(r.toCore)
    def roundTrip(r: ResearcherMetricsRow) = roundTripOf(r.toCore)
  }
  object MembershipM extends CoreMirror[GroupMembershipRow]("ResearcherGroupMembership") {
    def errors(r: GroupMembershipRow) = errorsOf(r.toCore)
    def roundTrip(r: GroupMembershipRow) = roundTripOf(r.toCore)
  }
  object MetricEvidenceM extends CoreMirror[MetricEvidenceRow]("EvidenceItem") {
    def errors(r: MetricEvidenceRow) = errorsOf(r.evidence.toCore)
    def roundTrip(r: MetricEvidenceRow) = roundTripOf(r.evidence.toCore)
  }
  object PathEvidenceM extends CoreMirror[PathEvidenceRow]("EvidenceItem") {
    def errors(r: PathEvidenceRow) = errorsOf(r.evidence.toCore)
    def roundTrip(r: PathEvidenceRow) = roundTripOf(r.evidence.toCore)
  }
  object SummaryM extends CoreMirror[SemanticSummaryRow]("EvidenceItem") {
    def errors(r: SemanticSummaryRow) = errorsOf(r.evidence.toCore)
    def roundTrip(r: SemanticSummaryRow) = roundTripOf(r.evidence.toCore)
  }
}
