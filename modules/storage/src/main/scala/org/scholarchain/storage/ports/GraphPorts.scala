package org.scholarchain.storage.ports

import scala.collection.immutable.ListMap

import org.scholarchain.core.{ResearcherId, WorkId}
import org.scholarchain.storage.GraphQueryBounds

// ---- Bulk loading -----------------------------------------------------------------------------

/** One Neptune bulk-loader job request (format `opencypher`). Built by
  * `org.scholarchain.storage.neptune.BulkLoadRequests`; the source is an S3 prefix holding the CSV
  * files of one load stage.
  */
final case class BulkLoadJob(
    source: String,
    format: String,
    iamRoleArn: String,
    region: String,
    failOnError: Boolean,
    parallelism: String,
    queueRequest: Boolean,
    updateSingleCardinalityProperties: Boolean,
    dependencies: List[String]
)

final case class BulkLoadStatus(loadId: String, status: String, totalRecords: Long, errors: Long)

/** Neptune bulk loader: the only path for large graph loads. */
trait GraphBulkLoader {
  def start(job: BulkLoadJob): String
  def status(loadId: String): BulkLoadStatus
}

// ---- Bounded upserts -----------------------------------------------------------------------

/** One node row for a parameterized MERGE upsert. `label` is one of the allowlisted Neptune labels.
  */
final case class NodeUpsert(id: String, properties: ListMap[String, ujson.Value])

/** Parameterized MERGE upserts for small, bounded writes (at most `neptune.mergeRowCap` rows per
  * call). Large loads must use [[GraphBulkLoader]]: MERGE at scale is slow and expensive.
  */
trait GraphUpsertPort {
  def rowCap: Int

  /** Left when `rows` exceeds `rowCap`, the label is not allowlisted, or an ID is blank. */
  def upsertNodes(label: String, rows: Seq[NodeUpsert]): Either[String, Int]
}

// ---- Read-only queries ---------------------------------------------------------------------

/** The only graph reads runtime services may issue: typed, allowlisted, parameterized templates.
  * There is no way to pass query text. Every query is bounded by a result limit (at most
  * [[GraphQueryBounds.MaxResultLimit]]), path depth (at most [[GraphQueryBounds.MaxDepth]]) and a
  * timeout (at most [[GraphQueryBounds.MaxTimeoutMillis]]).
  */
sealed trait GraphQuery {
  def templateId: String
  def limit: Int
}

object GraphQuery {
  final case class ResearcherById(researcherId: ResearcherId) extends GraphQuery {
    val templateId = "researcher_by_id"
    val limit = 1
  }
  final case class CoauthorNeighbors(researcherId: ResearcherId, windowLabel: String, limit: Int)
      extends GraphQuery {
    val templateId = "coauthor_neighbors"
  }
  final case class CoauthorEdgeBetween(a: ResearcherId, b: ResearcherId, windowLabel: String)
      extends GraphQuery {
    val templateId = "coauthor_edge_between"
    val limit = 1
  }
  final case class PathBetween(
      from: ResearcherId,
      to: ResearcherId,
      windowLabel: String,
      maxDepth: Int
  ) extends GraphQuery {
    def templateId: String = s"path_between_depth_$maxDepth"
    val limit = 1
  }
  final case class WorkAuthors(workId: WorkId, limit: Int) extends GraphQuery {
    val templateId = "work_authors"
  }

  private val WindowLabel = "[a-z0-9][a-z0-9-]*".r

  /** Bounds and identifier checks shared by every adapter. */
  def validate(q: GraphQuery): Either[String, GraphQuery] = {
    def window(w: String) =
      if (w != null && WindowLabel.matches(w)) Nil else List(s"windowLabel '$w' is not valid")
    def limit(l: Int) =
      if (l >= 1 && l <= GraphQueryBounds.MaxResultLimit) Nil
      else List(s"limit $l is outside 1..${GraphQueryBounds.MaxResultLimit}")
    def researcher(r: ResearcherId) =
      if (r != null && r.isWellFormed) Nil else List(s"'$r' is not a researcher ID")
    val errors = q match {
      case ResearcherById(r)            => researcher(r)
      case CoauthorNeighbors(r, w, l)   => researcher(r) ++ window(w) ++ limit(l)
      case CoauthorEdgeBetween(a, b, w) =>
        researcher(a) ++ researcher(b) ++ window(w) ++
          (if (a == b) List("endpoints must differ") else Nil)
      case PathBetween(a, b, w, d) =>
        researcher(a) ++ researcher(b) ++ window(w) ++
          (if (d >= 1 && d <= GraphQueryBounds.MaxDepth) Nil
           else List(s"maxDepth $d is outside 1..${GraphQueryBounds.MaxDepth}")) ++
          (if (a == b) List("endpoints must differ") else Nil)
      case WorkAuthors(wk, l) =>
        (if (wk != null && wk.isWellFormed) Nil else List(s"'$wk' is not a work ID")) ++ limit(l)
    }
    if (errors.isEmpty) Right(q) else Left(errors.mkString("; "))
  }
}

/** One result row: column name -> JSON value. */
final case class GraphRow(values: ListMap[String, ujson.Value])

/** Rows of one query; `truncated` when the row limit was reached. */
final case class GraphResult(templateId: String, rows: List[GraphRow], truncated: Boolean)

/** Read-only graph port for runtime services. Implementations use read-only credentials (IAM is
  * Phase 12, infra-engineer).
  */
trait GraphReadPort {
  def run(query: GraphQuery): Either[String, GraphResult]
}
