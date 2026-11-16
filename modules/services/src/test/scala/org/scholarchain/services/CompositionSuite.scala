package org.scholarchain.services

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicInteger

import org.scholarchain.core.config.ScholarChainConfig
import org.scholarchain.rag.embedding.{EmbeddingProviders, HttpResult, HttpTransport}
import org.scholarchain.rag.runtime.{LlmFactory, RuntimeFixtures, RuntimeService}
import org.scholarchain.storage.StorageConfig
import org.scholarchain.storage.aws.AwsSdkClients
import org.scholarchain.storage.jobs.ManualClock
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider
import software.amazon.awssdk.http.{
  AbortableInputStream,
  ExecutableHttpRequest,
  HttpExecuteRequest,
  HttpExecuteResponse,
  SdkHttpClient,
  SdkHttpResponse
}

/** Main's arguments, the local composition, and `--stores aws` built from stub SDK clients with
  * zero HTTP calls (decision 7k). The live smoke test is tagged `RequiresAws` and never runs here.
  */
class CompositionSuite extends munit.FunSuite {
  import ServiceTestKit._

  override val munitTimeout = scala.concurrent.duration.Duration(180, "s")

  private val RequiresAws = new munit.Tag("RequiresAws")
  private val run = RuntimeFixtures.sparkRun.value

  test("Main arguments: roles, stores, and the --graph-run and input rules") {
    val local = List(
      "--config",
      "c.json",
      "--role",
      "all",
      "--stores",
      "local",
      "--export",
      "e",
      "--neptune-csv",
      "n"
    )
    assert(Main.parseArgs(local).isRight)
    def err(args: List[String]) = Main.parseArgs(args).left.getOrElse("")
    assert(err(local.updated(3, "query")).contains("allows only --role all"))
    assert(err(local.take(6)).contains("needs --export and --neptune-csv"))
    assert(err(local ++ List("--graph-run", run)).contains("refused with --stores local"))
    val aws = List("--config", "c.json", "--role", "query", "--stores", "aws", "--graph-run", run)
    assertEquals(
      Main.parseArgs(aws).map(a => (a.role, a.stores, a.graphRun.map(_.value))),
      Right((Role.Query, "aws", Some(run)))
    )
    Role.values.foreach(r => assert(Main.parseArgs(aws.updated(3, r.wire)).isRight))
    assert(err(aws.take(6)).contains("needs --graph-run"))
    assert(err(aws ++ List("--export", "e")).contains("refused with --stores aws"))
    assert(err(aws.updated(7, "run-1")).contains("malformed run ID"))
    assert(err(aws.updated(3, "admin")).contains("unknown role"))
    assert(err(aws.updated(5, "s3")).contains("--stores must be local or aws"))
    assert(err(aws ++ List("--role", "all")).contains("given twice"))
    assert(err(aws ++ List("--port", "1")).contains("unexpected argument"))
  }

  test("Main.loadConfig reads the services config and applies SCHOLARCHAIN_CFG_ overrides only") {
    val path = Paths.get(getClass.getResource("/config/services-local-v1.json").toURI)
    val cfg = Main
      .loadConfig(path, Map("SCHOLARCHAIN_CFG_SERVICE_PORT" -> "9090", "OTHER" -> "x"))
      .fold(e => fail(e), identity)
    assertEquals(cfg.service.map(_.port), Some(9090))
    assertEquals(cfg.service.map(_.apiKeyEnvVar), Some("SCHOLARCHAIN_EXAMPLE_API_KEY"))
    assertEquals(
      cfg.service.map(_.readOnlyApiKeyEnvVar),
      Some("SCHOLARCHAIN_EXAMPLE_READONLY_API_KEY")
    )
    assertEquals(cfg.service.flatMap(_.jobQueueUrl), None)
    assert(Main.loadConfig(Paths.get("/nonexistent/config.json"), Map.empty).isLeft)
  }

  test(
    "local composition: RuntimeService.local over an export and Neptune CSV files serves a query"
  ) {
    val (exportDir, csv) = RuntimeFixtures.inputs()
    val clock = new ManualClock()
    val (rt, stores) = Composition.local(config, exportDir, csv, clock).fold(e => fail(e), identity)
    val server = ServiceServer
      .start(
        rt,
        stores,
        ServerOptions(
          role = Role.All,
          port = Some(0),
          clock = clock,
          env = env.get,
          logSink = new MemoryLogSink,
          startWorker = true
        )
      )
      .fold(e => { rt.close(); fail(e) }, identity)
    try {
      val r = call(server.baseUri, "POST", "/v1/query", Some(queryBody()))
      assertEquals(r.status, 200, r.body)
      assert(!r.json("answer")("abstained").bool)
      val ack = call(server.baseUri, "POST", "/v1/query", Some(queryBody(mode = "async")))
      assertEquals(ack.status, 202)
      val url = ack.json("statusUrl").str
      assert(waitUntil()(call(server.baseUri, "GET", url, None).json("state").str == "SUCCEEDED"))
    } finally server.close()
    assert(rt.isClosed)
  }

  // ---- AWS -----------------------------------------------------------------------------------

  /** The anthropic v2 config with the voyage embedder and the services section plus a placeholder
    * queue URL (no real endpoint, account or key).
    */
  private lazy val awsConfig: ScholarChainConfig = {
    val anth = loadConfig("/config/rag-runtime-anthropic-v2.json")
    val voyage = loadConfig("/config/rag-voyage-example-v1.json")
    anth.copy(
      models = anth.models.copy(embeddingModelId = voyage.models.embeddingModelId),
      embedding = voyage.embedding,
      service =
        config.service.map(_.copy(jobQueueUrl = Some("https://queue.example.invalid/example-jobs")))
    )
  }
  private val placeholderEnv: String => Option[String] = Map(
    "SCHOLARCHAIN_ANTHROPIC_API_KEY" -> "placeholder-not-a-key",
    "SCHOLARCHAIN_VOYAGE_API_KEY" -> "placeholder-not-a-key"
  ).get

  /** Counts every SDK request and answers 500 (nothing may be sent while building or serving
    * health).
    */
  private final class CountingHttp extends SdkHttpClient {
    val calls = new AtomicInteger()
    override def prepareRequest(r: HttpExecuteRequest): ExecutableHttpRequest =
      new ExecutableHttpRequest {
        def call(): HttpExecuteResponse = {
          calls.incrementAndGet()
          HttpExecuteResponse
            .builder()
            .response(SdkHttpResponse.builder().statusCode(500).build())
            .responseBody(
              AbortableInputStream.create(
                new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8))
              )
            )
            .build()
        }
        def abort(): Unit = ()
      }
    def close(): Unit = ()
  }
  private final class CountingTransport extends HttpTransport {
    val calls = new AtomicInteger()
    def postJson(uri: java.net.URI, headers: List[(String, String)], body: String): HttpResult = {
      calls.incrementAndGet(); HttpResult(500, "{}", None)
    }
  }

  private def awsOptions(http: SdkHttpClient, llm: HttpTransport, emb: HttpTransport) = {
    val cfg = StorageConfig.from(awsConfig).fold(e => fail(e.mkString), identity)
    val settings = RuntimeService.defaultAwsClientSettings(awsConfig)
    RuntimeService.AwsOptions(
      clients = Some(
        AwsSdkClients.build(settings, cfg, Some(http), Some(AnonymousCredentialsProvider.create()))
      ),
      llmWiring = LlmFactory.Wiring(env = placeholderEnv, httpTransport = _ => llm),
      embeddingWiring = EmbeddingProviders.Wiring(env = placeholderEnv, httpTransport = () => emb)
    )
  }

  test("--stores aws is built from stub SDK clients with zero HTTP calls; /healthz answers") {
    val http = new CountingHttp
    val llm = new CountingTransport
    val emb = new CountingTransport
    val (rt, stores) = Composition
      .aws(awsConfig, RuntimeFixtures.sparkRun, awsOptions(http, llm, emb))
      .fold(e => fail(e), identity)
    assert(rt.awsEnvironment.isDefined)
    Role.values.filterNot(_ == Role.All).foreach { role =>
      val server = ServiceServer
        .start(
          rt,
          stores,
          ServerOptions(
            role = role,
            port = Some(0),
            env = env.get,
            logSink = new MemoryLogSink,
            startWorker = false,
            closeRuntime = false,
            name = "aws"
          )
        )
        .fold(e => fail(e), identity)
      try
        assertEquals(
          call(server.baseUri, "GET", "/healthz", None, key = None).json("role").str,
          role.wire
        )
      finally server.close()
    }
    rt.close()
    assertEquals((http.calls.get, llm.calls.get, emb.calls.get), (0, 0, 0))
  }

  test("--stores aws needs service.jobQueueUrl") {
    val noQueue = awsConfig.copy(service = awsConfig.service.map(_.copy(jobQueueUrl = None)))
    val http = new CountingHttp
    val e = Composition.aws(
      noQueue,
      RuntimeFixtures.sparkRun,
      awsOptions(http, new CountingTransport, new CountingTransport)
    )
    assert(e.left.exists(_.contains("jobQueueUrl is required")), e.toString)
    assertEquals(http.calls.get, 0)
  }

  test("live smoke: a deployed query role answers /healthz and /readyz".tag(RequiresAws)) {
    // Phase 12 deploys the services; the base URL comes from the deployment, never from this file.
    val base = sys.env.getOrElse(
      "SCHOLARCHAIN_SERVICES_BASE_URL",
      fail("SCHOLARCHAIN_SERVICES_BASE_URL is not set")
    )
    assertEquals(call(base, "GET", "/healthz", None, key = None).status, 200)
    assertEquals(call(base, "GET", "/readyz", None, key = None).status, 200)
  }
}
