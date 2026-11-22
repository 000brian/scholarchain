package org.scholarchain.services

import java.nio.charset.StandardCharsets
import java.util.Base64

import scala.util.Try

import org.scholarchain.core._
import org.scholarchain.storage.ports.{GraphQuery, GraphResult}

/** `GET /v1/evidence/{evidenceId}` (decision 7g). */
final class EvidenceService(app: AppContext) {
  private val WindowParam = "([0-9]{4})-([0-9]{4})".r

  def get(evidenceId: String, query: Map[String, String]): Either[ApiError, ApiResponse] = {
    val unknown = query.keySet - "window"
    for {
      _ <- Either.cond(
        unknown.isEmpty,
        (),
        ApiError.invalid(unknown.toList.sorted.map(_ -> "unknown query parameter"))
      )
      id <- EvidenceId
        .parse(evidenceId)
        .left
        .map(_ => ApiError.invalid("evidenceId", "is not an evidence ID"))
      window <- query.get("window") match {
        case None                                          => Right(None)
        case Some(WindowParam(s, e)) if s.toInt <= e.toInt =>
          Right(Some(YearWindow(s.toInt, e.toInt)))
        case Some(_) => Left(ApiError.invalid("window", "must be <startYear>-<endYear>"))
      }
      // Phase 16 route time limit: each read waits at most `ServerOptions.readDeadlineMillis`
      item <- window match {
        case Some(w) =>
          app
            .timedRead("evidence", None)(app.runtime.stores.evidence.get(id, Some(w)))
            .flatMap(
              _.toRight(ApiError(ErrorCode.EVIDENCE_NOT_FOUND))
            )
        case None =>
          app
            .timedRead("evidence", None)(
              app.runtime.stores.evidence.windows(id, ServiceLimits.EvidenceWindowLimit)
            )
            .flatMap {
              case Nil         => Left(ApiError(ErrorCode.EVIDENCE_NOT_FOUND))
              case only :: Nil => Right(only)
              case many        =>
                Left(
                  ApiError(
                    ErrorCode.WINDOW_REQUIRED,
                    ujson.Obj(
                      "windows" -> ujson.Arr
                        .from(many.map(i => ujson.Str(EvidenceService.windowText(i.window))))
                    )
                  )
                )
            }
      }
    } yield ApiResponse.json(200, EvidenceView.of(item))
  }
}

object EvidenceService {

  /** dblp record keys: `<type>/<segment>/...` with the dblp key types (no `:` or `#`, unlike the
    * `Table:pk/sk` record references of the stores).
    */
  private val DblpKey =
    "(conf|journals|books|phd|series|reference|tr|ms|persons|homepages|www|informal)(/[A-Za-z0-9._-]+)+".r

  def windowText(w: Option[YearWindow]): String =
    w.map(x => s"${x.startYear}-${x.endYear}").getOrElse("none")

  /** `https://dblp.org/rec/<key>` per dblp key, else `scholarchain:<recordRef>`; a reference that
    * is itself a URI is withheld (C-03: it may name a bucket).
    */
  def sourceUris(item: EvidenceItem): List[String] =
    item.provenance.sourceRecordIds.filterNot(ResponseGuard.isUri).map { r =>
      if (DblpKey.matches(r)) s"https://dblp.org/rec/$r" else s"scholarchain:$r"
    }
}

/** `GET /v1/explain/{queryId}` (decision 7g). */
final class ExplainService(app: AppContext) {
  def explain(queryId: String, query: Map[String, String]): Either[ApiError, ApiResponse] =
    for {
      _ <- Either.cond(
        query.isEmpty,
        (),
        ApiError.invalid(query.keys.toList.sorted.map(_ -> "unknown query parameter"))
      )
      id <- RunId.parse(queryId).left.map(_ => ApiError.invalid("queryId", "is not a query ID"))
      // Phase 16 route time limit: the audit read waits at most `ServerOptions.readDeadlineMillis`
      audits <- app.timedRead("explain", None)(
        app.runtime.stores.audits.read(id, ServiceLimits.ExplainAuditLimit)
      )
      latest <- audits.lastOption.toRight(ApiError(ErrorCode.QUERY_NOT_FOUND))
    } yield ApiResponse.json(200, ExplainView.of(latest, audits.size))
}

/** The node a path parameter names. */
sealed trait NodeRef { def id: String }
object NodeRef {
  final case class Researcher(r: ResearcherId) extends NodeRef { def id: String = r.value }
  final case class Work(w: WorkId) extends NodeRef { def id: String = w.value }

  /** Stable ID kinds this API recognizes but does not serve as graph nodes. */
  private val otherKinds = List(
    MentionId,
    EdgeId,
    EvidenceId,
    RunId,
    LinkId,
    MatchCandidateId,
    LlmAdjudicationId,
    ChunkId
  )

  def parse(s: String): Either[ApiError, NodeRef] =
    if (ResearcherId.isWellFormed(s)) Right(Researcher(ResearcherId(s)))
    else if (WorkId.isWellFormed(s)) Right(Work(WorkId(s)))
    else if (otherKinds.exists(_.isWellFormed(s))) Left(ApiError(ErrorCode.UNSUPPORTED_NODE_KIND))
    else Left(ApiError.invalid("nodeId", "is not a stable ID"))
}

/** The fields of a neighbour cursor (decision 7h): unsigned base64url JSON of exactly these keys.
  */
final case class NeighborCursor(
    graphRunId: String,
    nodeId: String,
    direction: String,
    edgeTypes: List[String],
    window: String,
    limit: Int,
    offset: Int
) {
  def encode: String = {
    val json = Json.canonical(
      ujson.Obj(
        "v" -> 1,
        "graphRunId" -> graphRunId,
        "nodeId" -> nodeId,
        "direction" -> direction,
        "edgeTypes" -> ujson.Arr.from(edgeTypes.map(ujson.Str(_))),
        "window" -> window,
        "limit" -> limit,
        "offset" -> offset
      )
    )
    Base64.getUrlEncoder.withoutPadding.encodeToString(json.getBytes(StandardCharsets.UTF_8))
  }
}

object NeighborCursor {
  val Keys: Set[String] =
    Set("v", "graphRunId", "nodeId", "direction", "edgeTypes", "window", "limit", "offset")

  /** The cursor, or None when it is not base64url JSON of exactly the cursor shape with `v` 1. */
  def decode(s: String): Option[NeighborCursor] =
    Try {
      val bytes = Base64.getUrlDecoder.decode(s)
      val text =
        StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString
      if (!Json.depthWithin(text, 3)) None
      else
        ujson.read(text) match {
          case o: ujson.Obj if o.value.keySet.toSet == Keys =>
            def int(k: String): Option[Int] = o(k) match {
              case ujson.Num(n) if n.isWhole && n.abs < Int.MaxValue => Some(n.toInt)
              case _                                                 => None
            }
            def str(k: String): Option[String] = o(k) match {
              case ujson.Str(x) => Some(x)
              case _            => None
            }
            val types = o("edgeTypes") match {
              case ujson.Arr(xs) if xs.forall(_.isInstanceOf[ujson.Str]) =>
                Some(xs.map(_.str).toList)
              case _ => None
            }
            for {
              v <- int("v") if v == 1
              g <- str("graphRunId")
              n <- str("nodeId")
              d <- str("direction")
              t <- types
              w <- str("window")
              l <- int("limit")
              off <- int("offset")
            } yield NeighborCursor(g, n, d, t, w, l, off)
          case _ => None
        }
    }.toOption.flatten
}

/** Graph exploration (decision 7h) through storage's typed templates only. */
final class GraphService(app: AppContext) {
  import GraphService._

  private def runtime = app.runtime.context.runtime
  private def cap: Int = runtime.graphResultLimit
  private def graphRunId: String = app.runtime.graphRunId.value

  /** Through the graph breaker with storage's typed error class (Phase 16 FECD-1, [[GraphReads]]).
    */
  private def graph(q: GraphQuery): Either[ApiError, GraphResult] =
    GraphReads.run(app.breakers.graph, app.runtime.stores.graph, app.log)(q)

  def node(nodeId: String, query: Map[String, String]): Either[ApiError, ApiResponse] =
    for {
      _ <- Either.cond(
        query.isEmpty,
        (),
        ApiError.invalid(query.keys.toList.sorted.map(_ -> "unknown query parameter"))
      )
      ref <- NodeRef.parse(nodeId)
      out <- ref match {
        case NodeRef.Researcher(r) =>
          graph(GraphQuery.ResearcherById(r)).flatMap(
            _.rows.headOption.toRight(ApiError(ErrorCode.NODE_NOT_FOUND)).map { row =>
              NodeView(
                r.value,
                "researcher",
                graphRunId,
                row.values.getOrElse("properties", ujson.Obj())
              )
            }
          )
        case NodeRef.Work(w) =>
          graph(GraphQuery.WorkById(w)).flatMap(
            _.rows.headOption.toRight(ApiError(ErrorCode.NODE_NOT_FOUND)).map { row =>
              NodeView(
                w.value,
                "work",
                graphRunId,
                ujson.Obj.from(row.values.toList.filterNot(_._1 == "workId"))
              )
            }
          )
      }
    } yield ApiResponse.json(200, out)

  def neighbors(nodeId: String, query: Map[String, String]): Either[ApiError, ApiResponse] = {
    val problems = scala.collection.mutable.ListBuffer.empty[(String, String)]
    (query.keySet -- Params).toList.sorted.foreach(k =>
      problems += (k -> "unknown query parameter")
    )
    def intParam(name: String, default: Int, min: Int, max: Int): Int =
      query.get(name) match {
        case None    => default
        case Some(s) =>
          s.toIntOption.filter(n => n >= min && n <= max).getOrElse {
            problems += (name -> s"must be an integer in $min..$max"); default
          }
      }
    val direction = query.getOrElse("direction", "both")
    if (!Directions.contains(direction)) problems += ("direction" -> "must be in, out or both")
    val depth = intParam("depth", 1, 1, ServiceLimits.MaxNeighborDepth)
    val limit = intParam("limit", math.min(ServiceLimits.DefaultNeighborLimit, cap), 1, cap)
    val window = query.getOrElse("window", runtime.defaultWindowLabel)
    if (!app.runtime.config.graph.windows.exists(_.label == window))
      problems += ("window" -> "must be a configured window label")
    val requestedTypes: Option[List[String]] = query.get("edgeTypes").map { s =>
      val ts = s.split(",", -1).toList.map(_.trim)
      if (ts.exists(t => !EdgeTypes.contains(t))) {
        problems += ("edgeTypes" -> s"each must be one of ${EdgeTypes.mkString(", ")}"); Nil
      } else ts.distinct.sorted
    }
    val parsedNode = NodeRef.parse(nodeId)
    parsedNode match {
      case Left(e) if e.code == ErrorCode.INVALID_REQUEST => return Left(e)
      case _                                              => ()
    }
    if (problems.nonEmpty) return Left(ApiError.invalid(problems.toList))
    for {
      ref <- parsedNode
      edgeTypes = requestedTypes.getOrElse(ref match {
        case _: NodeRef.Researcher => List(CoauthoredWith)
        case _: NodeRef.Work       => List(Authored)
      })
      offset <- query.get("cursor") match {
        case None    => Right(0)
        case Some(c) =>
          NeighborCursor
            .decode(c)
            .filter { cur =>
              cur.offset >= 0 && cur.limit >= 1 && cur.offset % cur.limit == 0 && cur.offset < cap &&
              cur == NeighborCursor(
                graphRunId,
                ref.id,
                direction,
                edgeTypes,
                window,
                limit,
                cur.offset
              )
            }
            .map(_.offset)
            .toRight(ApiError(ErrorCode.INVALID_CURSOR))
      }
      _ <- Either.cond(depth == 1, (), ApiError(ErrorCode.UNSUPPORTED_DEPTH))
      q <- (ref, edgeTypes, direction) match {
        case (NodeRef.Researcher(r), List(CoauthoredWith), _) =>
          Right((n: Int) => GraphQuery.CoauthorNeighbors(r, window, n))
        case (NodeRef.Work(w), List(Authored), d) if d != "out" =>
          Right((n: Int) => GraphQuery.WorkAuthors(w, n))
        case _ =>
          Left(ApiError(ErrorCode.UNSUPPORTED_EDGE_TYPE, ujson.Obj("supported" -> SupportedText)))
      }
      wanted = math.min(offset + limit, cap)
      result <- graph(q(wanted))
    } yield {
      val edgeType = edgeTypes.head
      val page = result.rows.slice(offset, offset + limit).map { row =>
        ujson.Obj.from(row.values.toList :+ ("edgeType" -> ujson.Str(edgeType)))
      }
      val more = result.truncated
      val nextOffset = offset + limit
      val (truncated, next) =
        if (!more) (false, None)
        else if (nextOffset < cap)
          (
            false,
            Some(
              NeighborCursor(
                graphRunId,
                ref.id,
                direction,
                edgeTypes,
                window,
                limit,
                nextOffset
              ).encode
            )
          )
        else (true, None)
      ApiResponse.json(200, NeighborsPage(ref.id, window, page, truncated, next))
    }
  }
}

object GraphService {
  val CoauthoredWith = "COAUTHORED_WITH"
  val Authored = "AUTHORED"
  val EdgeTypes: List[String] =
    List(CoauthoredWith, Authored, "HAS_TOPIC", "AFFILIATED_WITH", "PUBLISHED_IN")
  val Directions: Set[String] = Set("in", "out", "both")
  val Params: Set[String] = Set("direction", "depth", "limit", "edgeTypes", "window", "cursor")
  val SupportedText: String =
    "researcher: COAUTHORED_WITH (any direction); work: AUTHORED (in or both)"
}
