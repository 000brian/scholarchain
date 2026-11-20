package org.scholarchain.services

import java.nio.file.Paths

import org.scholarchain.core.config.ScholarChainConfig
import org.scholarchain.rag.runtime.{FakeModel, RuntimeFixtures, RuntimeService}

/** Phase 15 review finding H-01: every service role must resolve the same configuration, the
  * embedding queue URL included, because the query run ID carries the resolved config's
  * fingerprint.
  *
  * The query role computes the async ack `queryId`; the worker role runs the workflow and stores
  * the result, audit and bundle under its own query run ID; the evidence role serves explain for
  * the ack's ID. Each role here is a separate composition: its own config loaded by
  * [[Main.loadConfig]] from an environment, its own runtime handle and its own server. They share
  * only what the deployed roles share: the runtime data (the fixture world: audits, bundles, graph)
  * and the job table and queue.
  *
  * The fix is in Terraform (every role gets `SCHOLARCHAIN_CFG_SERVICE_EMBEDDING_QUEUE_URL`); these
  * tests guard the invariant and document the hazard.
  */
class RoleConfigParitySuite extends munit.FunSuite {
  import ServiceTestKit._

  override val munitTimeout = scala.concurrent.duration.Duration(120, "s")

  private val UrlVar = "SCHOLARCHAIN_CFG_SERVICE_EMBEDDING_QUEUE_URL"
  private val PlaceholderUrl = "https://queue.example.invalid/example-embedding-tasks"
  private lazy val configPath =
    Paths.get(getClass.getResource("/config/services-local-v1.json").toURI)

  /** The services config as one role resolves it from `env` (a fresh load per role). */
  private def roleConfig(env: Map[String, String]): ScholarChainConfig =
    Main.loadConfig(configPath, env).fold(e => fail(e), identity)

  /** A second runtime handle over the harness's runtime data, with its own config. */
  private def runtimeFor(h: Harness, cfg: ScholarChainConfig): RuntimeService =
    RuntimeService
      .fromStores(
        cfg,
        h.slow,
        Some(h.world.retriever),
        new FakeModel(h.world.rule),
        RuntimeFixtures.sparkRun,
        h.world.env.report.graphWindowLabels.toSet,
        clock = () => 0L,
        environment = Some(RuntimeService.Local(h.world.env))
      )
      .fold(e => fail(e), identity)

  private def serverFor(h: Harness, rt: RuntimeService, role: Role): ServiceServer =
    ServiceServer
      .start(
        rt,
        h.stores,
        ServerOptions(
          role = role,
          port = Some(0),
          clock = h.clock,
          ids = new FixedIds,
          logSink = new MemoryLogSink,
          env = env.get,
          startWorker = role == Role.Worker,
          closeRuntime = false,
          name = s"h01-${role.wire}"
        )
      )
      .fold(e => fail(e), identity)

  /** What the three roles report for one async request. */
  private final case class CrossRole(
      ackQueryId: String,
      jobQueryId: String,
      resultQueryId: String,
      resultExplainLink: String,
      explainOfAck: Int,
      workerRunId: String
  )

  /** Query role with `queryEnv`, worker role with `workerEnv`, evidence role with `queryEnv`; one
    * async request through them.
    */
  private def crossRole(
      queryEnv: Map[String, String],
      workerEnv: Map[String, String]
  ): CrossRole = {
    val queryCfg = roleConfig(queryEnv)
    val workerCfg = roleConfig(workerEnv)
    val evidenceCfg = roleConfig(queryEnv)
    val h = harness(role = Role.Query, cfg = queryCfg)
    try {
      val workerRt = runtimeFor(h, workerCfg)
      val evidenceRt = runtimeFor(h, evidenceCfg)
      val worker = serverFor(h, workerRt, Role.Worker)
      val evidence = serverFor(h, evidenceRt, Role.Evidence)
      try {
        val ack = post(h, queryBody(mode = "async"))
        assertEquals(ack.status, 202, ack.body)
        val jobUrl = ack.json("statusUrl").str
        assert(
          waitUntil()(get(h, jobUrl).json("state").str == "SUCCEEDED"),
          get(h, jobUrl).body
        )
        val result = get(h, s"$jobUrl/result")
        assertEquals(result.status, 200, result.body)
        val explain = call(evidence.baseUri, "GET", s"/v1/explain/${ack.json("queryId").str}")
        val stored = upickle.default.read[CompletedResult](result.body)
        // the run ID the worker's own config gives this request
        val parsed = new QueryRequestParser(
          workerRt.config.graph.windows,
          workerRt.context.runtime,
          workerRt.context.vectorTopK
        ).parse(queryBody(mode = "async")).fold(e => fail(e.toString), identity)
        CrossRole(
          ackQueryId = ack.json("queryId").str,
          jobQueryId = get(h, jobUrl).json("queryId").str,
          resultQueryId = stored.queryId,
          resultExplainLink = stored.links.explain,
          explainOfAck = explain.status,
          workerRunId = workerRt.queryRunId(parsed.toQueryRequest).fold(e => fail(e), _.value)
        )
      } finally {
        worker.close(); evidence.close()
      }
    } finally h.close()
  }

  test(
    "H-01: query and worker compositions from the same environment (embedding queue URL " +
      "included) give the ack queryId, the job's queryId and the stored result's queryId alike"
  ) {
    val shared = Map(UrlVar -> PlaceholderUrl)
    // both roles really resolved the URL
    assertEquals(roleConfig(shared).service.flatMap(_.embeddingQueueUrl), Some(PlaceholderUrl))
    val r = crossRole(queryEnv = shared, workerEnv = shared)
    assertEquals(r.resultQueryId, r.ackQueryId)
    assertEquals(r.jobQueryId, r.ackQueryId)
    assertEquals(r.resultExplainLink, s"/v1/explain/${r.ackQueryId}")
    // the evidence role finds the audit the worker wrote under the ack's ID
    assertEquals(r.explainOfAck, 200)
  }

  test(
    "H-01 hazard: roles whose configs differ only in service.embeddingQueueUrl give different " +
      "query run IDs, so the ack queryId does not name the stored result (every role needs the " +
      "same environment)"
  ) {
    val withUrl = roleConfig(Map(UrlVar -> PlaceholderUrl))
    val withoutUrl = roleConfig(Map.empty)
    // the configs differ only in that key ...
    assertEquals(
      withUrl.copy(service = withUrl.service.map(_.copy(embeddingQueueUrl = None))),
      withoutUrl
    )
    // ... yet their fingerprints, and so every query run ID, differ
    assertNotEquals(withUrl.fingerprint, withoutUrl.fingerprint)
    // Phase 14 Terraform: the URL on the worker only
    val r = crossRole(queryEnv = Map.empty, workerEnv = Map(UrlVar -> PlaceholderUrl))
    assertNotEquals(r.resultQueryId, r.ackQueryId)
    assertEquals(r.jobQueryId, r.ackQueryId)
    assertEquals(r.resultQueryId, r.workerRunId)
    // explain of the ID the client was given finds no audit
    assertEquals(r.explainOfAck, 404)
  }
}
