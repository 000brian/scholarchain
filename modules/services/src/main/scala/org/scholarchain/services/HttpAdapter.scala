package org.scholarchain.services

import java.io.InputStream
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.{CharacterCodingException, CodingErrorAction, StandardCharsets}

import scala.util.control.NonFatal

import com.sun.net.httpserver.{HttpExchange, HttpHandler}
import upickle.default.Writer

/** A process role (decision 7a). */
sealed abstract class Role(val wire: String)
object Role {
  case object Query extends Role("query")
  case object Worker extends Role("worker")
  case object Evidence extends Role("evidence")
  case object Graph extends Role("graph")
  case object All extends Role("all")
  val values: List[Role] = List(Query, Worker, Evidence, Graph, All)
  def parse(s: String): Either[String, Role] =
    values.find(_.wire == s).toRight(s"unknown role '$s' (${values.map(_.wire).mkString(", ")})")
}

/** Who may call a route: `Open` (health), `AnyKey` (full or read-only key), `FullKey`. */
sealed trait AuthLevel { def wire: String }
object AuthLevel {
  case object Open extends AuthLevel { val wire = "none" }
  case object AnyKey extends AuthLevel { val wire = "full or read-only key" }
  case object FullKey extends AuthLevel { val wire = "full key" }
}

/** A response produced by a handler. */
final case class ApiResponse(status: Int, body: String, headers: List[(String, String)] = Nil)
object ApiResponse {
  def json[A: Writer](status: Int, a: A, headers: List[(String, String)] = Nil): ApiResponse =
    ApiResponse(status, Json.encode(a), headers)
}

/** What a handler sees: the parsed request, never the raw exchange. */
final case class RequestCtx(
    method: String,
    route: String,
    pathParams: Map[String, String],
    query: Map[String, String],
    body: Option[String],
    caller: CallerClass,
    traceId: String,
    requestId: String,
    idempotencyKey: Option[String]
)

/** One entry of the route table. `template` segments in braces bind path parameters. */
final case class Route(
    method: String,
    template: String,
    roles: Set[Role],
    auth: AuthLevel,
    handler: RequestCtx => Either[ApiError, ApiResponse]
) {
  val segments: List[String] = template.split("/").toList.filter(_.nonEmpty)

  def bind(path: List[String]): Option[Map[String, String]] =
    if (path.size != segments.size) None
    else {
      val pairs = segments.zip(path)
      if (pairs.forall { case (t, p) => (t.startsWith("{") && t.endsWith("}")) || t == p })
        Some(pairs.collect {
          case (t, p) if t.startsWith("{") => t.substring(1, t.length - 1) -> p
        }.toMap)
      else None
    }
}

/** The HTTP adapter (decision 7b): parses, authenticates, bounds, rate-limits and dispatches; adds
  * `X-Request-Id` and `X-Trace-Id` to every response; turns every error into the error body; writes
  * one log line per request. It holds no application logic.
  *
  * Order: health routes (open, never rate-limited) → API key (401) → rate limit (429) → route
  * lookup (404; a route of another role is 404) → method (405) → permission (403) → media type
  * (415) → body size (413) → UTF-8 (400) → handler.
  */
final class HttpAdapter(
    role: Role,
    routes: List[Route],
    keys: ApiKeys,
    limiter: TokenBucket,
    maxRequestBytes: Int,
    ids: IdGenerator,
    log: ServiceLog
) extends HttpHandler {

  private val roleRoutes = routes.filter(r => r.roles.contains(role) || role == Role.All)

  def handle(ex: HttpExchange): Unit = {
    val started = System.nanoTime()
    val requestId = RequestIds
      .validRequestId(ex.getRequestHeaders.getFirst("X-Request-Id"))
      .getOrElse(ids.requestId())
    val traceId =
      RequestIds.traceIdOf(ex.getRequestHeaders.getFirst("traceparent")).getOrElse(ids.traceId())
    var routeName = "unmatched"
    var caller: CallerClass = CallerClass.Anonymous
    val method = Option(ex.getRequestMethod).getOrElse("").take(16)
    val result: Either[ApiError, ApiResponse] =
      try {
        dispatch(ex, method, traceId, requestId, r => routeName = r, c => caller = c)
      } catch {
        case NonFatal(e) =>
          log.failure(Some(traceId), routeName, e)
          Left(ApiError.internal)
      }
    val (status, body, headers, code) = result match {
      case Right(r) => (r.status, r.body, r.headers, None)
      case Left(e)  =>
        val b = ErrorBody(e.code.name, e.code.message, traceId, e.code.retryable, e.details)
        val h = e.retryAfterSeconds.map(s => "Retry-After" -> s.toString).toList ++ e.extraHeaders
        (e.code.status, Json.encode(b), h, Some(e.code.name))
    }
    try send(ex, status, body, headers, requestId, traceId)
    catch { case NonFatal(e) => log.failure(Some(traceId), routeName, e) }
    finally ex.close()
    log.request(
      traceId,
      requestId,
      method,
      routeName,
      status,
      (System.nanoTime() - started) / 1000000L,
      code,
      caller.wire
    )
  }

  private def send(
      ex: HttpExchange,
      status: Int,
      body: String,
      headers: List[(String, String)],
      requestId: String,
      traceId: String
  ): Unit = {
    val h = ex.getResponseHeaders
    h.set("Content-Type", "application/json; charset=utf-8")
    h.set("Cache-Control", "no-store")
    h.set("X-Content-Type-Options", "nosniff")
    h.set("X-Request-Id", requestId)
    h.set("X-Trace-Id", traceId)
    headers.foreach { case (k, v) => h.set(k, v) }
    val bytes = body.getBytes(StandardCharsets.UTF_8)
    ex.sendResponseHeaders(status, if (bytes.isEmpty) -1L else bytes.length.toLong)
    if (bytes.nonEmpty) {
      val out = ex.getResponseBody
      try out.write(bytes)
      finally out.close()
    }
  }

  private def dispatch(
      ex: HttpExchange,
      method: String,
      traceId: String,
      requestId: String,
      setRoute: String => Unit,
      setCaller: CallerClass => Unit
  ): Either[ApiError, ApiResponse] = {
    val uri = ex.getRequestURI
    val rawPath = Option(uri.getRawPath).getOrElse("/")
    val rawQuery = Option(uri.getRawQuery).getOrElse("")
    def ctx(
        route: Route,
        params: Map[String, String],
        query: Map[String, String],
        body: Option[String],
        caller: CallerClass
    ) =
      RequestCtx(
        method,
        route.template,
        params,
        query,
        body,
        caller,
        traceId,
        requestId,
        Option(ex.getRequestHeaders.getFirst("Idempotency-Key"))
      )
    if (rawPath.length > ServiceLimits.MaxPathChars)
      return Left(ApiError(ErrorCode.ROUTE_NOT_FOUND))
    val segments = HttpAdapter.pathSegments(rawPath) match {
      case Some(s) => s
      case None    => return Left(ApiError(ErrorCode.ROUTE_NOT_FOUND))
    }

    // open health routes
    roleRoutes.filter(_.auth == AuthLevel.Open).find(_.bind(segments).nonEmpty) match {
      case Some(_) =>
        roleRoutes.find(r =>
          r.auth == AuthLevel.Open && r.method == method && r.bind(segments).nonEmpty
        ) match {
          case Some(r) =>
            setRoute(r.template)
            return r.handler(ctx(r, Map.empty, Map.empty, None, CallerClass.Anonymous))
          case None =>
            setRoute(
              roleRoutes
                .find(r => r.auth == AuthLevel.Open && r.bind(segments).nonEmpty)
                .get
                .template
            )
            return Left(
              ApiError(ErrorCode.METHOD_NOT_ALLOWED, extraHeaders = List("Allow" -> "GET"))
            )
        }
      case None => ()
    }

    // authentication
    val caller = keys.classify(Option(ex.getRequestHeaders.getFirst("X-Api-Key")))
    setCaller(caller)
    if (caller == CallerClass.Anonymous) return Left(ApiError(ErrorCode.UNAUTHENTICATED))

    // one token bucket for every authenticated request (health routes returned above)
    limiter.take() match {
      case Left(seconds) =>
        return Left(ApiError(ErrorCode.RATE_LIMITED, retryAfterSeconds = Some(seconds)))
      case Right(()) => ()
    }

    // route lookup (routes of other roles do not exist here)
    val matching = roleRoutes.filter(r => r.auth != AuthLevel.Open && r.bind(segments).nonEmpty)
    if (matching.isEmpty) return Left(ApiError(ErrorCode.ROUTE_NOT_FOUND))
    val route = matching.find(_.method == method) match {
      case Some(r) => r
      case None    =>
        setRoute(matching.head.template)
        val allow = matching.map(_.method).distinct.sorted.mkString(", ")
        return Left(ApiError(ErrorCode.METHOD_NOT_ALLOWED, extraHeaders = List("Allow" -> allow)))
    }
    setRoute(route.template)
    if (route.auth == AuthLevel.FullKey && caller != CallerClass.Full)
      return Left(ApiError(ErrorCode.FORBIDDEN))

    if (rawQuery.length > ServiceLimits.MaxQueryChars)
      return Left(ApiError.invalid("(query)", "the query string is too long"))
    val query = HttpAdapter.queryParams(rawQuery) match {
      case Right(q) => q
      case Left(p)  => return Left(ApiError.invalid("(query)", p))
    }

    val body =
      if (method != "POST") Right(None)
      else readBody(ex).map(Some(_))
    body.flatMap(b => route.handler(ctx(route, route.bind(segments).get, query, b, caller)))
  }

  private def readBody(ex: HttpExchange): Either[ApiError, String] = {
    val ct = Option(ex.getRequestHeaders.getFirst("Content-Type")).getOrElse("")
    if (!HttpAdapter.isJsonMediaType(ct)) return Left(ApiError(ErrorCode.UNSUPPORTED_MEDIA_TYPE))
    val declared =
      Option(ex.getRequestHeaders.getFirst("Content-Length")).flatMap(_.trim.toLongOption)
    if (declared.exists(_ > maxRequestBytes)) return Left(ApiError(ErrorCode.PAYLOAD_TOO_LARGE))
    val in: InputStream = ex.getRequestBody
    val bytes =
      try in.readNBytes(maxRequestBytes + 1)
      finally in.close()
    if (bytes.length > maxRequestBytes) Left(ApiError(ErrorCode.PAYLOAD_TOO_LARGE))
    else
      try
        Right(
          StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString
        )
      catch { case _: CharacterCodingException => Left(ApiError(ErrorCode.INVALID_JSON)) }
  }
}

object HttpAdapter {

  /** `application/json`, optionally with `charset=utf-8` (case-insensitive). */
  def isJsonMediaType(header: String): Boolean = {
    val parts = header.split(";").map(_.trim.toLowerCase(java.util.Locale.ROOT)).toList
    parts match {
      case "application/json" :: params =>
        params.forall(p => p.isEmpty || p == "charset=utf-8" || p == "charset=\"utf-8\"")
      case _ => false
    }
  }

  /** Percent-decoded path segments (no empty segment except a trailing slash is refused). */
  def pathSegments(rawPath: String): Option[List[String]] =
    try {
      if (!rawPath.startsWith("/")) None
      else {
        val raw = rawPath.substring(1).split("/", -1).toList
        val segs = if (raw == List("")) Nil else raw
        if (segs.exists(_.isEmpty)) None
        else Some(segs.map(s => URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8)))
      }
    } catch { case NonFatal(_) => None }

  /** Query parameters; a repeated or empty-named parameter is refused. */
  def queryParams(raw: String): Either[String, Map[String, String]] =
    try {
      if (raw.isEmpty) Right(Map.empty)
      else {
        val pairs = raw.split("&", -1).toList.map { p =>
          val i = p.indexOf('=')
          val (k, v) = if (i < 0) (p, "") else (p.substring(0, i), p.substring(i + 1))
          URLDecoder
            .decode(k, StandardCharsets.UTF_8) -> URLDecoder.decode(v, StandardCharsets.UTF_8)
        }
        if (pairs.exists(_._1.isEmpty)) Left("a query parameter has no name")
        else {
          val dup = pairs.groupBy(_._1).collect { case (k, xs) if xs.size > 1 => k }.toList.sorted
          if (dup.nonEmpty) Left(s"repeated query parameter: ${dup.mkString(", ")}")
          else Right(pairs.toMap)
        }
      }
    } catch { case NonFatal(_) => Left("the query string is not valid") }
}
