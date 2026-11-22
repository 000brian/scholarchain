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

/** The bulk loader refused a start request for certain (Phase 14 C-07): the service answered with
  * an error that means the request was not accepted, so no load was created. `errorClass` is the
  * service's error name (for example `BadRequestException`); it holds no identifier.
  *
  * `throttled` (Phase 16, storage retries): the refusal was Neptune's throttling answer
  * (`TooManyRequestsException` 429, "the request could not be processed", or `ThrottlingException`,
  * "the request was denied due to request throttling"). Such a start may be sent again after a
  * wait: it created no load.
  */
final class BulkLoadStartRejected(
    val errorClass: String,
    val httpStatus: Int,
    message: String,
    val throttled: Boolean = false
) extends RuntimeException(message)

/** Neptune bulk loader: the only path for large graph loads. */
trait GraphBulkLoader {

  /** Starts one load and returns its load ID. Throws [[BulkLoadStartRejected]] only when the
    * request was refused for certain (with `throttled` set for a throttling answer, Phase 16); any
    * other exception (timeout, network error, 5xx, a response without a load ID) means the outcome
    * is unknown: the load may exist.
    */
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

/** Why a `PathBetween` search stopped before the end of its bounds (`complete = false`), Phase 16
  * L1. `code` is an allowlisted word (never a store message); rag maps it to its abstention codes.
  */
sealed abstract class GraphStop(val code: String)

object GraphStop {

  /** The time budget (`runtime.graphTimeoutMillis`) ran out, or Neptune's own query time limit or a
    * client-side API call timeout ended a depth.
    */
  case object TimeLimit extends GraphStop("time-limit")

  /** Neptune refused a depth for a resource limit (`MemoryLimitExceededException`,
    * `QueryLimitExceededException`), after storage's bounded retry. Whether a path exists at the
    * deeper depths is unknown: a bounded search, not a store failure.
    */
  case object ResourceLimit extends GraphStop("resource-limit")

  /** Every code, in a fixed order. */
  val Codes: List[String] = List(TimeLimit.code, ResourceLimit.code)
}

/** Rows of one query.
  *
  * @param truncated
  *   more rows matched than were returned (`limit` rows returned); for `PathBetween`, more than one
  *   shortest path exists
  * @param complete
  *   the search ran to the end of its bounds. Always true for single-shot templates. For
  *   `PathBetween`: true when a path was found or every depth up to `maxDepth` was searched (so
  *   empty rows mean "no path within depth `maxDepth`"); false when the search stopped first (empty
  *   rows then mean "unknown": see `stoppedBy`)
  * @param depthSearched
  *   `PathBetween` only: the deepest depth fully searched (the path length when found; 0 when the
  *   search stopped before depth 1 finished); None for other templates
  * @param stoppedBy
  *   Phase 16 L1: why an incomplete `PathBetween` search stopped. None when `complete` is true. An
  *   incomplete result with None comes from a producer that predates this field (test fakes); read
  *   it as [[GraphStop.TimeLimit]], the only stop such producers had.
  */
final case class GraphResult(
    templateId: String,
    rows: List[GraphRow],
    truncated: Boolean,
    complete: Boolean,
    depthSearched: Option[Int],
    stoppedBy: Option[GraphStop] = None
)

object GraphResult {

  /** A single-shot template result: `rows` holds at most `limit + 1` matched rows. */
  def bounded(templateId: String, rows: List[GraphRow], limit: Int): GraphResult =
    GraphResult(templateId, rows.take(limit), rows.size > limit, complete = true, None)
}

/** Why a graph read failed (Phase 16, FECD-1 S1 and L1). `code` is an allowlisted word that callers
  * may log, count or map; `message` is the store's text and must never reach a client, a log line
  * or a model prompt unredacted (it may name an endpoint).
  */
sealed abstract class GraphReadError(val code: String) {
  def message: String
}

object GraphReadError {

  /** The query was refused before any store call (validation, unknown template, bad window). */
  final case class Invalid(message: String) extends GraphReadError("graph-invalid-query")

  /** May succeed later: HTTP 5xx other than the resource limits, throttling, 429, connection or
    * socket errors. Returned only after storage's bounded retry gave up.
    */
  final case class Transient(message: String) extends GraphReadError("graph-transient")

  /** Neptune `MemoryLimitExceededException` or `QueryLimitExceededException` (HTTP 500, documented
    * as retryable), after the bounded retry. For `PathBetween` this is never an error: the search
    * returns `complete = false` with [[GraphStop.ResourceLimit]].
    */
  final case class ResourceLimit(message: String) extends GraphReadError("graph-resource-limit")

  /** A single-shot template ran past its time budget (Neptune's query time limit or the API call
    * timeout). For `PathBetween` this is never an error (`complete = false`, time limit).
    */
  final case class Timeout(message: String) extends GraphReadError("graph-timeout")

  /** Will not succeed on retry: HTTP 4xx (malformed query, access denied, bad parameters) and every
    * other client error (credentials, configuration).
    */
  final case class Permanent(message: String) extends GraphReadError("graph-permanent")

  /** A port that reports only `run`'s String (test fakes, decorators written before Phase 16). */
  final case class Unclassified(message: String) extends GraphReadError("graph-unclassified")

  /** Every code, in a fixed order (the allowlist). */
  val Codes: List[String] = List(
    "graph-invalid-query",
    "graph-transient",
    "graph-resource-limit",
    "graph-timeout",
    "graph-permanent",
    "graph-unclassified"
  )
}

/** Read-only graph port for runtime services. Implementations use read-only credentials (IAM is
  * Phase 12, infra-engineer).
  *
  * `run` is the original contract (Left = the failure text). `runClassified` (Phase 16) gives the
  * same outcome with a typed [[GraphReadError]]; ports that do not override it report every failure
  * as [[GraphReadError.Unclassified]]. `run(q)` and `runClassified(q).left.map(_.message)` agree
  * for every port in storage.
  */
trait GraphReadPort {
  def settings: GraphReadSettings
  def run(query: GraphQuery): Either[String, GraphResult]
  def runClassified(query: GraphQuery): Either[GraphReadError, GraphResult] =
    run(query).left.map(GraphReadError.Unclassified(_))
}
