package org.scholarchain.storage.ports

import scala.collection.immutable.ListMap

import org.scholarchain.core.{ResearcherId, WorkId}
import org.scholarchain.core.config.RuntimeSettings
import org.scholarchain.storage.{GraphQueryBounds, StorageConfig}

// ---- Bulk loading -----------------------------------------------------------------------------

/** One Neptune bulk-loader job request (format `opencypher`). Built by
  * `org.scholarchain.storage.neptune.BulkLoadRequests`; the source is an S3 prefix holding the CSV
  * files of one load stage.
  *
  * Phase 9 (A2): `updateSingleCardinalityProperties` is no longer sent (the AWS pages disagree on
  * whether openCypher supports it); `userProvidedEdgeIds` is sent as TRUE because every
  * relationship file carries an `:ID` column.
  */
final case class BulkLoadJob(
    source: String,
    format: String,
    iamRoleArn: String,
    region: String,
    failOnError: Boolean,
    parallelism: String,
    queueRequest: Boolean,
    userProvidedEdgeIds: Boolean,
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

/** Bounds of the read-only graph templates, from configuration (`graph.windows` and the `runtime`
  * section). Each bound is at most the storage hard limit in [[GraphQueryBounds]].
  *
  * @param windowLabels
  *   the configured `graph.windows` labels; a query naming any other window is rejected
  * @param maxPathDepth
  *   largest `PathBetween` depth (`runtime.maxPathDepth`, 1..6)
  * @param resultLimit
  *   largest row limit of one template (`runtime.graphResultLimit`, 1..500)
  * @param timeoutMillis
  *   time budget of one query; for `PathBetween` the total over all depths
  *   (`runtime.graphTimeoutMillis`, 1..30000)
  */
final case class GraphReadSettings(
    windowLabels: Set[String],
    maxPathDepth: Int,
    resultLimit: Int,
    timeoutMillis: Int
) {
  require(windowLabels.nonEmpty, "at least one window label")
  require(
    windowLabels.forall(GraphQuery.isWindowLabel),
    s"window labels must match ${GraphQuery.WindowLabelPattern}"
  )
  require(maxPathDepth >= 1 && maxPathDepth <= GraphQueryBounds.MaxDepth, "maxPathDepth")
  require(resultLimit >= 1 && resultLimit <= GraphQueryBounds.MaxResultLimit, "resultLimit")
  require(
    timeoutMillis >= 1 && timeoutMillis <= GraphQueryBounds.MaxTimeoutMillis,
    "timeoutMillis"
  )
}

object GraphReadSettings {
  def of(windowLabels: Iterable[String], runtime: RuntimeSettings): GraphReadSettings =
    GraphReadSettings(
      windowLabels.toSet,
      runtime.maxPathDepth,
      runtime.graphResultLimit,
      runtime.graphTimeoutMillis
    )

  /** From the configured windows and the `runtime` section (Left when it is absent). */
  def from(config: StorageConfig): Either[String, GraphReadSettings] =
    config.requireRuntime.map(r => of(config.windowLabels, r))
}

/** The only graph reads runtime services may issue: typed, allowlisted, parameterized templates.
  * There is no way to pass query text. Every query is bounded by a result limit, a path depth and a
  * time budget (see [[GraphReadSettings]]).
  */
sealed trait GraphQuery {
  def templateId: String
  def limit: Int
}

object GraphQuery {

  /** Columns: `researcherId`, `properties` (every Researcher node property). */
  final case class ResearcherById(researcherId: ResearcherId) extends GraphQuery {
    val templateId = "researcher_by_id"
    val limit = 1
  }

  /** Columns: `coauthorId`, `displayName`, `weight`, `jointPublicationCount`, `evidenceKey`;
    * ordered by weight descending, then coauthorId.
    */
  final case class CoauthorNeighbors(researcherId: ResearcherId, windowLabel: String, limit: Int)
      extends GraphQuery {
    val templateId = "coauthor_neighbors"
  }

  /** Columns: `edgeId`, `properties` (every COAUTHORED_WITH edge property). */
  final case class CoauthorEdgeBetween(a: ResearcherId, b: ResearcherId, windowLabel: String)
      extends GraphQuery {
    val templateId = "coauthor_edge_between"
    val limit = 1
  }

  /** Shortest coauthorship path, searched depth 1, 2, ... up to `maxDepth` (iterative deepening).
    * Columns: `researcherIds` (path order), `evidenceKeys` (one per hop), `hops`.
    */
  final case class PathBetween(
      from: ResearcherId,
      to: ResearcherId,
      windowLabel: String,
      maxDepth: Int
  ) extends GraphQuery {
    def templateId: String = s"path_between_depth_$maxDepth"
    val limit = 1
  }

  /** Columns: `researcherId`, `displayName`; ordered by researcherId. */
  final case class WorkAuthors(workId: WorkId, limit: Int) extends GraphQuery {
    val templateId = "work_authors"
  }

  /** Columns: `workId`, `displayName` (the title), `year`, `venueKey`, `dblpKey`, `teamSize`. */
  final case class WorkById(workId: WorkId) extends GraphQuery {
    val templateId = "work_by_id"
    val limit = 1
  }

  val WindowLabelPattern: String = "[a-z0-9][a-z0-9-]*"
  private val WindowLabel = WindowLabelPattern.r

  def isWindowLabel(w: String): Boolean = w != null && WindowLabel.matches(w)

  /** Bounds and identifier checks shared by every adapter. A window label must match
    * [[WindowLabelPattern]] and be one of the configured labels; identifiers must be well-formed
    * stable IDs (so quotes, braces and Cypher keywords never pass).
    */
  def validate(q: GraphQuery, s: GraphReadSettings): Either[String, GraphQuery] = {
    def window(w: String) =
      if (!isWindowLabel(w)) List(s"windowLabel '$w' is not valid")
      else if (!s.windowLabels.contains(w)) List(s"windowLabel '$w' is not a configured window")
      else Nil
    def limit(l: Int) =
      if (l >= 1 && l <= s.resultLimit) Nil
      else List(s"limit $l is outside 1..${s.resultLimit}")
    def researcher(r: ResearcherId) =
      if (r != null && r.isWellFormed) Nil else List(s"'$r' is not a researcher ID")
    def work(w: WorkId) =
      if (w != null && w.isWellFormed) Nil else List(s"'$w' is not a work ID")
    val errors = q match {
      case null                         => List("no query")
      case ResearcherById(r)            => researcher(r)
      case CoauthorNeighbors(r, w, l)   => researcher(r) ++ window(w) ++ limit(l)
      case CoauthorEdgeBetween(a, b, w) =>
        researcher(a) ++ researcher(b) ++ window(w) ++
          (if (a == b) List("endpoints must differ") else Nil)
      case PathBetween(a, b, w, d) =>
        researcher(a) ++ researcher(b) ++ window(w) ++
          (if (d >= 1 && d <= s.maxPathDepth) Nil
           else List(s"maxDepth $d is outside 1..${s.maxPathDepth}")) ++
          (if (a == b) List("endpoints must differ") else Nil)
      case WorkAuthors(wk, l) => work(wk) ++ limit(l)
      case WorkById(wk)       => work(wk)
    }
    if (errors.isEmpty) Right(q) else Left(errors.mkString("; "))
  }
}

/** One result row: column name -> JSON value. */
final case class GraphRow(values: ListMap[String, ujson.Value])

/** Rows of one query.
  *
  * @param truncated
  *   more rows matched than were returned (`limit` rows returned); for `PathBetween`, more than one
  *   shortest path exists
  * @param complete
  *   the search ran to the end of its bounds. Always true for single-shot templates. For
  *   `PathBetween`: true when a path was found or every depth up to `maxDepth` was searched (so
  *   empty rows mean "no path within depth `maxDepth`"); false when the time budget ran out first
  *   (empty rows then mean "unknown: stopped at the time limit")
  * @param depthSearched
  *   `PathBetween` only: the deepest depth fully searched (the path length when found; 0 when the
  *   time ran out before depth 1 finished); None for other templates
  */
final case class GraphResult(
    templateId: String,
    rows: List[GraphRow],
    truncated: Boolean,
    complete: Boolean,
    depthSearched: Option[Int]
)

object GraphResult {

  /** A single-shot template result: `rows` holds at most `limit + 1` matched rows. */
  def bounded(templateId: String, rows: List[GraphRow], limit: Int): GraphResult =
    GraphResult(templateId, rows.take(limit), rows.size > limit, complete = true, None)
}

/** Read-only graph port for runtime services. Implementations use read-only credentials (IAM is
  * Phase 12, infra-engineer).
  */
trait GraphReadPort {
  def settings: GraphReadSettings
  def run(query: GraphQuery): Either[String, GraphResult]
}
