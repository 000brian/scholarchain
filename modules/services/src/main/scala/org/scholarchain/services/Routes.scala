package org.scholarchain.services

/** The application services one server exposes. */
final class Application(
    val query: QueryService,
    val jobs: JobService,
    val evidence: EvidenceService,
    val explain: ExplainService,
    val graph: GraphService,
    val health: HealthService
)

/** The route table (decision 7a). Routes are thin: each handler takes path and query parameters
  * from the request and calls one application service. No storage or rag type appears here.
  */
object Routes {
  import AuthLevel._
  import Role._

  private val allRoles: Set[Role] = Role.values.toSet

  private def noQuery(ctx: RequestCtx): Either[ApiError, Unit] =
    Either.cond(
      ctx.query.isEmpty,
      (),
      ApiError.invalid(ctx.query.keys.toList.sorted.map(_ -> "unknown query parameter"))
    )

  def table(app: Application): List[Route] = List(
    Route("GET", "/healthz", allRoles, Open, _ => Right(app.health.healthz())),
    Route("GET", "/readyz", allRoles, Open, _ => Right(app.health.readyz())),
    Route("POST", "/v1/query", Set(Query), FullKey, ctx => app.query.submit(ctx)),
    Route(
      "GET",
      "/v1/jobs/{jobId}",
      Set(Query),
      AnyKey,
      ctx => noQuery(ctx).flatMap(_ => app.jobs.status(ctx.pathParams("jobId")))
    ),
    Route(
      "GET",
      "/v1/jobs/{jobId}/result",
      Set(Query),
      AnyKey,
      ctx => noQuery(ctx).flatMap(_ => app.jobs.result(ctx.pathParams("jobId")))
    ),
    Route(
      "GET",
      "/v1/evidence/{evidenceId}",
      Set(Evidence),
      AnyKey,
      ctx => app.evidence.get(ctx.pathParams("evidenceId"), ctx.query)
    ),
    Route(
      "GET",
      "/v1/explain/{queryId}",
      Set(Evidence),
      AnyKey,
      ctx => app.explain.explain(ctx.pathParams("queryId"), ctx.query)
    ),
    Route(
      "GET",
      "/v1/graph/nodes/{nodeId}",
      Set(Graph),
      AnyKey,
      ctx => app.graph.node(ctx.pathParams("nodeId"), ctx.query)
    ),
    Route(
      "GET",
      "/v1/graph/nodes/{nodeId}/neighbors",
      Set(Graph),
      AnyKey,
      ctx => app.graph.neighbors(ctx.pathParams("nodeId"), ctx.query)
    )
  )
}
