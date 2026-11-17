package org.scholarchain.rag.runtime

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger

import org.scholarchain.core._
import org.scholarchain.core.config.ScholarChainConfig
import org.scholarchain.rag.RagFixtures
import org.scholarchain.rag.embedding.{EmbeddingProviders, HttpResult, HttpTransport}
import org.scholarchain.storage.StorageConfig
import org.scholarchain.storage.aws.{AwsClientSettings, AwsSdkClients}
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider
import software.amazon.awssdk.http.{
  AbortableInputStream,
  ExecutableHttpRequest,
  HttpExecuteRequest,
  HttpExecuteResponse,
  SdkHttpClient,
  SdkHttpResponse
}

/** Decision 5d: the runtime entry point for services ([[RuntimeService]]) and its fixtures. */
class RuntimeServiceSuite extends munit.FunSuite {
  import RuntimeServiceFixtures._

  private lazy val fx = fixture()
  override def afterAll(): Unit = fx.service.close()

  test("fixture: a non-abstained answer with a verified claim and cited, stored evidence") {
    val r = fx.service.workflow.run(answeredRequest).fold(e => fail(e), identity)
    assert(!r.response.abstained, r.response.answerText)
    assert(r.response.claims.exists(_.status == VerificationStatus.Verified))
    val cited = citedEvidence(r)
    assert(cited.nonEmpty)
    val bundle = r.response.bundle.get
    // every cited item is stored (EB1) and the bundle is readable back (EB3)
    cited.foreach { id =>
      val item = bundle.items.find(_.evidenceId == id).get
      assertEquals(
        fx.service.stores.evidence.get(id, item.window).map(_.map(_.evidenceId)),
        Right(Some(id))
      )
    }
    assertEquals(
      fx.service.stores.evidence.getBundle(bundle.bundleId).map(_.map(_.bundleId)),
      Right(Some(bundle.bundleId))
    )
    // the audit records the same evidence IDs
    val audits = fx.service.stores.audits.read(r.queryRunId, 10).fold(e => fail(e), identity)
    assertEquals(audits.last.evidenceIds, bundle.items.map(_.evidenceId))
    assertEquals(fx.service.queryRunId(answeredRequest), Right(r.queryRunId))
    assertEquals(fx.service.graphRunId, RuntimeFixtures.sparkRun)
    assertEquals(fx.service.localEnvironment, Some(fx.world.env))
    assertEquals(fx.service.awsEnvironment, None)
    assert(fx.service.prompts.synthesis.promptId == "answer-synthesis-v3")
  }

  test("fixture: an abstained question abstains") {
    val r = fx.service.workflow.run(QueryRequest(AbstainedQuestion)).fold(e => fail(e), identity)
    assert(r.response.abstained)
  }

  test("fixture: the flaky graph makes the workflow fail with a Left, and recovers") {
    val f = fixture()
    try {
      f.flaky.failGraph.set(true)
      val l = f.service.workflow.run(answeredRequest)
      assert(l.left.exists(_.contains("injected graph failure")), l.toString)
      f.flaky.failGraph.set(false)
      assert(f.service.workflow.run(answeredRequest).isRight)
      f.flaky.failKeyValue.set(true)
      assert(f.service.stores.evidence.windows(EvidenceId("evidence:v1:" + "0" * 32), 5).isLeft)
    } finally f.service.close()
  }

  test("fixture: a gated model holds the workflow until released") {
    val f = fixture(r => new GatedModel(new FakeModel(r)))
    try {
      val gated = f.service.model.asInstanceOf[GatedModel]
      val t = new Thread(() => { f.service.workflow.run(answeredRequest); () })
      t.start()
      assert(gated.awaitEntered(30000L))
      Thread.sleep(50)
      assert(t.isAlive, "the workflow waits on the model")
      gated.release()
      t.join(30000L)
      assert(!t.isAlive)
    } finally f.service.close()
  }

  test("close is idempotent and closes what the handle owns") {
    val f = fixture()
    f.service.close()
    f.service.close()
    assert(f.service.isClosed)
  }

  test("fromStores without owned closeables closes nothing of the caller's") {
    val w = RuntimeFixtures.world()
    val s = RuntimeService
      .fromStores(
        configV2,
        w.env,
        Some(w.retriever),
        new FakeModel(w.rule),
        RuntimeFixtures.sparkRun,
        w.env.report.graphWindowLabels.toSet
      )
      .fold(e => fail(e), identity)
    assertEquals(s.localEnvironment, Some(w.env))
    s.close()
    // the caller's environment still answers
    assert(w.workflow().run(answeredRequest).isRight)
    w.env.close()
  }

  test("onStage: every stage is reported in order, a throwing listener never breaks the query") {
    val seen = scala.collection.mutable.ListBuffer.empty[String]
    val r = fx.service.workflow.run(answeredRequest, s => { seen += s; () })
    assert(r.isRight)
    assertEquals(seen.toList, QueryWorkflow.StageNames)
    val throwing = fx.service.workflow.run(answeredRequest, _ => throw new RuntimeException("boom"))
    assert(throwing.isRight, throwing.toString)
    assertEquals(throwing.toOption.get.response, r.toOption.get.response)
  }

  test("local: builds what the benchmark runner needs from an export and Neptune CSV files") {
    val (exportDir, csv) = RuntimeFixtures.inputs()
    val steps = scala.collection.mutable.ListBuffer.empty[String]
    val timer = new RuntimeService.StepTimer {
      def apply[A](name: String)(f: => A): A = { steps += name; f }
    }
    val s = RuntimeService
      .local(configV2, exportDir, csv, RuntimeService.LocalOptions(steps = timer))
      .fold(e => fail(e), identity)
    try {
      assertEquals(steps.toList, List("load-runtime-environment", "render-and-ingest-summaries"))
      assert(s.localEnvironment.isDefined)
      assert(s.ingestReport.exists(_("documents").num > 0))
      assertEquals(s.graphRunId, RuntimeFixtures.sparkRun)
      assertEquals(s.prompts.claims, AnswerClaimsSpec.V3)
      val r = s.workflow.run(answeredRequest).fold(e => fail(e), identity)
      assert(!r.response.abstained)
      val v1 = RuntimeService
        .local(
          configV2,
          exportDir,
          csv,
          RuntimeService.LocalOptions(promptSet = Prompts.V1)
        )
        .fold(e => fail(e), identity)
      assertEquals(v1.prompts.synthesis.promptId, "answer-synthesis-v1")
      v1.close()
    } finally s.close()
    assert(s.isClosed)
  }

  test("local: a live embedder is refused with local stores") {
    val (exportDir, csv) = RuntimeFixtures.inputs()
    val e = RuntimeService.local(awsConfig, exportDir, csv)
    assert(e.left.exists(_.contains("refused with local stores")), e.toString)
  }

  // ---- AWS ---------------------------------------------------------------------------------

  /** The anthropic v2 config with the voyage embedder (placeholder key variable names only). */
  private lazy val awsConfig: ScholarChainConfig = {
    val anth = RagFixtures.loadConfig("/config/rag-runtime-anthropic-v2.json")
    val voyage = RagFixtures.loadConfig("/config/rag-voyage-example-v1.json")
    anth.copy(
      models = anth.models.copy(embeddingModelId = voyage.models.embeddingModelId),
      embedding = voyage.embedding
    )
  }
  private val placeholderEnv: String => Option[String] =
    Map(
      "SCHOLARCHAIN_ANTHROPIC_API_KEY" -> "placeholder-not-a-key",
      "SCHOLARCHAIN_VOYAGE_API_KEY" -> "placeholder-not-a-key"
    ).get

  /** Counts every SDK request; answers none (the build must not send any). */
  private final class CountingHttp extends SdkHttpClient {
    val calls = new AtomicInteger()
    override def prepareRequest(r: HttpExecuteRequest): ExecutableHttpRequest =
      new ExecutableHttpRequest {
        def call(): HttpExecuteResponse = {
          calls.incrementAndGet()
          val bytes = "{}".getBytes(StandardCharsets.UTF_8)
          HttpExecuteResponse
            .builder()
            .response(SdkHttpResponse.builder().statusCode(500).build())
            .responseBody(AbortableInputStream.create(new ByteArrayInputStream(bytes)))
            .build()
        }
        def abort(): Unit = ()
      }
    def close(): Unit = ()
  }
  private final class CountingTransport extends HttpTransport {
    val calls = new AtomicInteger()
    def postJson(
        uri: java.net.URI,
        headers: List[(String, String)],
        body: String
    ): HttpResult = { calls.incrementAndGet(); HttpResult(500, "{}", None) }
  }

  private def awsBuild(config: ScholarChainConfig, settings: Option[AwsClientSettings] = None) = {
    val http = new CountingHttp
    val llmT = new CountingTransport
    val embT = new CountingTransport
    val cfg = StorageConfig.from(config).fold(e => fail(e.mkString), identity)
    val s = settings.getOrElse(RuntimeService.defaultAwsClientSettings(config))
    val clients = AwsSdkClients.build(
      s.copy(region = cfg.region),
      cfg,
      Some(http),
      Some(AnonymousCredentialsProvider.create())
    )
    val out = RuntimeService.aws(
      config,
      s,
      RuntimeFixtures.sparkRun,
      RuntimeService.AwsOptions(
        clients = Some(clients),
        llmWiring = LlmFactory.Wiring(env = placeholderEnv, httpTransport = _ => llmT),
        embeddingWiring =
          EmbeddingProviders.Wiring(env = placeholderEnv, httpTransport = () => embT)
      )
    )
    (out, http, llmT, embT)
  }

  test("aws: builds from stub SDK clients with no network call; close is idempotent") {
    val (out, http, llmT, embT) = awsBuild(awsConfig)
    val s = out.fold(e => fail(e), identity)
    assertEquals((http.calls.get, llmT.calls.get, embT.calls.get), (0, 0, 0))
    assert(s.awsEnvironment.isDefined)
    assertEquals(s.localEnvironment, None)
    assertEquals(s.graphRunId, RuntimeFixtures.sparkRun)
    assertEquals(s.context.graphWindowLabels, awsConfig.graph.windows.map(_.label).toSet)
    assertEquals(s.model.modelId, "claude-haiku-5-5")
    assert(s.queryRunId(answeredRequest).isRight)
    s.close()
    s.close()
    assert(s.isClosed)
    assertEquals(http.calls.get, 0)
  }

  test("aws: refuses the fake LLM, the fake embedder and short SDK timeouts") {
    val fakeLlm = awsConfig.copy(llm = configV2.llm, models = awsConfig.models)
    assert(awsBuild(fakeLlm)._1.left.exists(_.contains("fake LLM is refused")))
    val fakeEmb = awsConfig.copy(
      embedding = configV2.embedding,
      models = awsConfig.models.copy(embeddingModelId = configV2.models.embeddingModelId)
    )
    assert(awsBuild(fakeEmb)._1.left.exists(_.contains("fake embedder is refused")))
    val short = RuntimeService.defaultAwsClientSettings(awsConfig).copy(apiCallTimeoutMillis = 1000)
    assert(awsBuild(awsConfig, Some(short))._1.left.exists(_.contains("timeouts must be")))
    // the default settings sit above the SQS long poll
    assert(
      RuntimeService.defaultAwsClientSettings(awsConfig).apiCallTimeoutMillis >=
        RuntimeService.MinAwsCallTimeoutMillis
    )
  }

  test("aws: a missing key variable is refused at build, naming the variable only") {
    val http = new CountingHttp
    val cfg = StorageConfig.from(awsConfig).fold(e => fail(e.mkString), identity)
    val s = RuntimeService.defaultAwsClientSettings(awsConfig)
    val clients =
      AwsSdkClients.build(s, cfg, Some(http), Some(AnonymousCredentialsProvider.create()))
    val out = RuntimeService.aws(
      awsConfig,
      s,
      RuntimeFixtures.sparkRun,
      RuntimeService.AwsOptions(
        clients = Some(clients),
        llmWiring = LlmFactory.Wiring(env = _ => None)
      )
    )
    assertEquals(
      out.left.toOption,
      Some("environment variable SCHOLARCHAIN_ANTHROPIC_API_KEY is not set")
    )
    assertEquals(http.calls.get, 0)
  }

  // ---- close() closes what the handle owns, exactly once (Phase 10 U-2) ----------------------

  /** Counts its `close()` calls; throws on close when `throwOnClose`. */
  private final class CountingCloseable(throwOnClose: Boolean = false) extends AutoCloseable {
    val closes = new AtomicInteger()
    def close(): Unit = {
      closes.incrementAndGet()
      if (throwOnClose) throw new IllegalStateException("injected close failure")
    }
  }

  test("U-2: fromStores closes each owned closeable exactly once over two close() calls") {
    val w = RuntimeFixtures.world()
    val a = new CountingCloseable(throwOnClose = true)
    val b = new CountingCloseable
    val s = RuntimeService
      .fromStores(
        configV2,
        w.env,
        Some(w.retriever),
        new FakeModel(w.rule),
        RuntimeFixtures.sparkRun,
        w.env.report.graphWindowLabels.toSet,
        owned = List(a, b)
      )
      .fold(e => fail(e), identity)
    try {
      assertEquals((a.closes.get, b.closes.get), (0, 0))
      s.close()
      // a throwing closeable does not stop the others
      assertEquals((a.closes.get, b.closes.get), (1, 1))
      s.close()
      assertEquals((a.closes.get, b.closes.get), (1, 1))
      assert(s.isClosed)
    } finally w.env.close()
  }

  test("U-2: aws closes every SDK client it was given exactly once over two close() calls") {
    val closes = scala.collection.mutable.LinkedHashMap.empty[String, AtomicInteger]
    def counter(name: String): AtomicInteger = closes.getOrElseUpdate(name, new AtomicInteger())
    val s3 = new software.amazon.awssdk.services.s3.S3Client {
      def serviceName(): String = "s3"
      def close(): Unit = { counter("s3").incrementAndGet(); () }
    }
    val dynamo = new software.amazon.awssdk.services.dynamodb.DynamoDbClient {
      def serviceName(): String = "dynamodb"
      def close(): Unit = { counter("dynamodb").incrementAndGet(); () }
    }
    val vectors = new software.amazon.awssdk.services.s3vectors.S3VectorsClient {
      def serviceName(): String = "s3vectors"
      def close(): Unit = { counter("s3vectors").incrementAndGet(); () }
    }
    val neptune = new software.amazon.awssdk.services.neptunedata.NeptunedataClient {
      def serviceName(): String = "neptune-db"
      def close(): Unit = { counter("neptune").incrementAndGet(); () }
    }
    val sqs = new software.amazon.awssdk.services.sqs.SqsClient {
      def serviceName(): String = "sqs"
      def close(): Unit = { counter("sqs").incrementAndGet(); () }
    }
    List("s3", "dynamodb", "s3vectors", "neptune", "sqs").foreach(counter)
    val clients = AwsSdkClients(s3, dynamo, vectors, Some(neptune), Some(sqs))
    val llmT = new CountingTransport
    val embT = new CountingTransport
    val s = RuntimeService
      .aws(
        awsConfig,
        RuntimeService.defaultAwsClientSettings(awsConfig),
        RuntimeFixtures.sparkRun,
        RuntimeService.AwsOptions(
          clients = Some(clients),
          llmWiring = LlmFactory.Wiring(env = placeholderEnv, httpTransport = _ => llmT),
          embeddingWiring =
            EmbeddingProviders.Wiring(env = placeholderEnv, httpTransport = () => embT)
        )
      )
      .fold(e => fail(e), identity)
    assert(s.awsEnvironment.isDefined)
    assert(closes.values.forall(_.get == 0), closes.toString)
    s.close()
    s.close()
    assert(s.isClosed)
    assertEquals(
      closes.toList.map { case (k, v) => k -> v.get },
      List("s3" -> 1, "dynamodb" -> 1, "s3vectors" -> 1, "neptune" -> 1, "sqs" -> 1)
    )
    assertEquals((llmT.calls.get, embT.calls.get), (0, 0))
  }
}
