package org.scholarchain.services

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}

import scala.jdk.CollectionConverters._

import org.scholarchain.core.config.{ConfigLoader, ScholarChainConfig, ServiceSettings}
import org.scholarchain.rag.retrieval.SemanticRetriever
import org.scholarchain.rag.runtime.{
  FakeModel,
  LanguageModel,
  RulePlanner,
  RuntimeFixtures,
  RuntimeService
}
import org.scholarchain.rag.runtime.RuntimeServiceFixtures.FlakyStores
import org.scholarchain.storage.StorageConfig
import org.scholarchain.storage.jobs.{ManualClock, ServiceStores, ToggleKeyValueStore}
import org.scholarchain.storage.local.InMemoryWorkQueue
import org.scholarchain.storage.ports.{
  ConditionalResult,
  GraphQuery,
  GraphReadError,
  GraphReadPort,
  GraphReadSettings,
  GraphResult,
  Item,
  ItemKey,
  KeyValueStore
}
import org.scholarchain.storage.runtime.RuntimeStores

/** Fixtures of the services tests: the services config, placeholder keys, fixed IDs, a harness that
  * starts a server on 127.0.0.1 port 0 over rag's fixture world (fake LLM, fake embedder, in-memory
  * stores), and a small HTTP client. No network beyond the loopback server.
  */
object ServiceTestKit {

  /** Placeholder key values (not secrets) under the variable names of the services config. */
  val FullKey = "placeholder-full-access-key"
  val ReadOnlyKey = "placeholder-read-only-key"
  val env: Map[String, String] = Map(
    "SCHOLARCHAIN_EXAMPLE_API_KEY" -> FullKey,
    "SCHOLARCHAIN_EXAMPLE_READONLY_API_KEY" -> ReadOnlyKey
  )

  def loadConfig(resource: String): ScholarChainConfig = {
    val in = getClass.getResourceAsStream(resource)
    val text =
      try new String(in.readAllBytes(), StandardCharsets.UTF_8)
      finally in.close()
    ConfigLoader.load(text, resource, Map.empty).fold(e => sys.error(e.message), _.config)
  }

  /** `services-local-v1.json`: the rag local v2 config plus a `service` section (decision 7l). */
  lazy val config: ScholarChainConfig = loadConfig("/config/services-local-v1.json")

  def withService(c: ScholarChainConfig)(
      f: ServiceSettings => ServiceSettings
  ): ScholarChainConfig =
    c.copy(service = c.service.map(f))

  /** Sequential IDs: UUIDs `00000000-0000-4000-8000-<n>`, trace IDs `<n>` in 32 hex digits. */
  final class FixedIds extends IdGenerator {
    private val u = new AtomicInteger(0)
    private val t = new AtomicInteger(0)
    private val r = new AtomicInteger(0)
    def uuid(): String = f"00000000-0000-4000-8000-${u.incrementAndGet()}%012d"
    def traceId(): String = f"${t.incrementAndGet()}%032x"
    override def requestId(): String = s"req-${r.incrementAndGet()}"
  }

  /** Runtime stores whose graph port can be slowed and counted (readiness tests), and made to throw
    * a StoreException with a given message (S-4 tests: a message naming a host and a table).
    */
  final class SlowGraphStores(base: RuntimeStores, kv: KeyValueStore, sc: StorageConfig)
      extends RuntimeStores {
    val graphDelayMillis = new AtomicLong(0L)
    val graphCalls = new AtomicLong(0L)
    val graphThrows = new java.util.concurrent.atomic.AtomicReference[Option[String]](None)

    /** Phase 16: when set, every graph call answers this classified failure (`run` its message). */
    val graphError = new java.util.concurrent.atomic.AtomicReference[Option[GraphReadError]](None)

    /** Phase 16 route time limits: key-value reads (`get`, `query`) of the evidence and audit
      * readers wait this long first (writes are never delayed).
      */
    val readDelayMillis = new AtomicLong(0L)

    def settings = base.settings
    def windowLabels = base.windowLabels
    val graph: GraphReadPort = new GraphReadPort {
      def settings: GraphReadSettings = base.graph.settings
      private def before(): Unit = {
        graphCalls.incrementAndGet()
        val d = graphDelayMillis.get
        if (d > 0) Thread.sleep(d)
        graphThrows.get.foreach(m => throw new org.scholarchain.storage.StoreException(m, true))
      }
      def run(q: GraphQuery): Either[String, GraphResult] = {
        before()
        graphError.get match {
          case Some(e) => Left(e.message)
          case None    => base.graph.run(q)
        }
      }
      override def runClassified(q: GraphQuery): Either[GraphReadError, GraphResult] = {
        before()
        graphError.get match {
          case Some(e) => Left(e)
          case None    => base.graph.runClassified(q)
        }
      }
    }
    private val slowKv: KeyValueStore = new KeyValueStore {
      private def pause(): Unit = {
        val d = readDelayMillis.get
        if (d > 0) Thread.sleep(d)
      }
      def putIfAbsent(table: String, item: Item): ConditionalResult = kv.putIfAbsent(table, item)
      def putIfVersion(table: String, item: Item, expectedVersion: Long): ConditionalResult =
        kv.putIfVersion(table, item, expectedVersion)
      def get(table: String, key: ItemKey): Option[Item] = { pause(); kv.get(table, key) }
      def query(
          table: String,
          pk: String,
          skPrefix: Option[String],
          limit: Int,
          descending: Boolean
      ): List[Item] = { pause(); kv.query(table, pk, skPrefix, limit, descending) }
    }
    private val readers: RuntimeStores = RuntimeStores
      .of(sc, base.graph, slowKv, base.vectors, base.textStore)
      .fold(e => sys.error(e), identity)
    def metrics = base.metrics
    def entityLinks = base.entityLinks
    def evidence = readers.evidence
    def audits = readers.audits
    def vectors = base.vectors
    def textStore = base.textStore
  }

  /** One running server over a fresh fixture world.
    *
    * @param flaky
    *   failure switches of the runtime stores (graph port, key-value store)
    * @param jobKv
    *   failure switch of the jobs and idempotency table
    * @param queue
    *   the in-memory job queue (visibility `jobLeaseMillis` + margin by `clock`)
    */
  final case class Harness(
      world: RuntimeFixtures.World,
      flaky: FlakyStores,
      slow: SlowGraphStores,
      runtime: RuntimeService,
      jobKv: ToggleKeyValueStore,
      queue: InMemoryWorkQueue,
      stores: ServiceStores,
      server: ServiceServer,
      clock: ManualClock,
      log: MemoryLogSink,
      model: LanguageModel
  ) extends AutoCloseable {
    def base: String = server.baseUri
    def close(): Unit = server.close()
    def settings: ServiceSettings = server.settings
  }

  /** The runtime handle over a fresh world (fixed workflow clock: every latency is 0 ms, so explain
    * output is reproducible).
    */
  def runtimeOver(
      cfg: ScholarChainConfig,
      model: RulePlanner => LanguageModel
  ): (RuntimeFixtures.World, FlakyStores, SlowGraphStores, RuntimeService) = {
    val w = RuntimeFixtures.world()
    val sc = StorageConfig.from(cfg).fold(e => sys.error(e.mkString("; ")), identity)
    val flaky = new FlakyStores(w.env, sc)
    val slow = new SlowGraphStores(flaky.stores, flaky.kv, sc)
    val rt = RuntimeService
      .fromStores(
        cfg,
        slow,
        Some(w.retriever: SemanticRetriever),
        model(w.rule),
        RuntimeFixtures.sparkRun,
        w.env.report.graphWindowLabels.toSet,
        owned = List(w.env),
        clock = () => 0L,
        environment = Some(RuntimeService.Local(w.env))
      )
      .fold(e => sys.error(e), identity)
    (w, flaky, slow, rt)
  }

  def harness(
      role: Role = Role.All,
      model: RulePlanner => LanguageModel = r => new FakeModel(r),
      cfg: ScholarChainConfig = config,
      startWorker: Boolean = false,
      clock: ManualClock = new ManualClock(),
      keys: Map[String, String] = env,
      closeRuntime: Boolean = true,
      wrapStores: ServiceStores => ServiceStores = identity,
      embedding: Option[EmbeddingWork] = None,
      readDeadlineMillis: Long = ServiceLimits.ReadRouteDeadlineMillis,
      embeddingDrainMillis: Long = ServiceLimits.EmbeddingDrainMillis
  ): Harness = {
    val (w, flaky, slow, rt) = runtimeOver(cfg, model)
    val settings = cfg.service.get
    val sc = StorageConfig.from(cfg).fold(e => sys.error(e.mkString("; ")), identity)
    val kv = new ToggleKeyValueStore(w.env.storage.kv)
    val queue = new InMemoryWorkQueue(
      settings.jobLeaseMillis.toLong + ServiceLimits.LocalVisibilityMarginMillis,
      clock
    )
    val stores = wrapStores(ServiceStores.over(kv, sc.dynamoDb.querySessionTable, queue))
    val log = new MemoryLogSink
    val server = ServiceServer
      .start(
        rt,
        stores,
        ServerOptions(
          role = role,
          port = Some(0),
          clock = clock,
          ids = new FixedIds,
          logSink = log,
          env = keys.get,
          startWorker = startWorker,
          closeRuntime = closeRuntime,
          name = "test",
          embedding = embedding,
          readDeadlineMillis = readDeadlineMillis,
          embeddingDrainMillis = embeddingDrainMillis
        )
      )
      .fold(e => { rt.close(); sys.error(e) }, identity)
    Harness(w, flaky, slow, rt, kv, queue, stores, server, clock, log, rt.model)
  }

  /** Service stores whose job writes can be scripted to answer `Left(message)` (S-3, S-4 tests):
    * the next `succeedFailures` succeed calls, every setStage while `stageFails`, every job read
    * while `getFails`.
    */
  final class ScriptedJobStores(base: ServiceStores, message: String) extends ServiceStores {
    import org.scholarchain.storage.jobs._
    val succeedFailures = new AtomicInteger(0)
    val stageFails = new java.util.concurrent.atomic.AtomicBoolean(false)
    val getFails = new java.util.concurrent.atomic.AtomicBoolean(false)
    val succeedCalls = new AtomicInteger(0)
    val idempotency: IdempotencyStore = base.idempotency
    val jobQueue: org.scholarchain.storage.ports.WorkQueue = base.jobQueue
    val jobs: JobStore = new JobStore {
      private val j = base.jobs
      def create(job: NewJob) = j.create(job)
      def get(jobId: String, nowMillis: Long) =
        if (getFails.get) Left(message) else j.get(jobId, nowMillis)
      def claim(job: JobRecord, nowMillis: Long, leaseUntilMillis: Long) =
        j.claim(job, nowMillis, leaseUntilMillis)
      def reclaim(job: JobRecord, nowMillis: Long, leaseUntilMillis: Long) =
        j.reclaim(job, nowMillis, leaseUntilMillis)
      def release(job: JobRecord, nowMillis: Long) = j.release(job, nowMillis)
      def succeed(job: JobRecord, nowMillis: Long, result: String): Either[String, JobChange] = {
        succeedCalls.incrementAndGet()
        if (succeedFailures.getAndUpdate(n => math.max(0, n - 1)) > 0) Left(message)
        else j.succeed(job, nowMillis, result)
      }
      def fail(job: JobRecord, nowMillis: Long, error: JobError) = j.fail(job, nowMillis, error)
      def setStage(job: JobRecord, nowMillis: Long, stage: String) =
        if (stageFails.get) Left(message) else j.setStage(job, nowMillis, stage)
    }
  }

  // ---- HTTP client (loopback only) ------------------------------------------------------------

  final case class Resp(status: Int, headers: Map[String, List[String]], body: String) {
    def json: ujson.Value = ujson.read(body)
    def header(name: String): Option[String] =
      headers.get(name.toLowerCase(java.util.Locale.ROOT)).flatMap(_.headOption)
    def error: String = json("error").str
  }

  lazy val client: HttpClient = HttpClient
    .newBuilder()
    .version(HttpClient.Version.HTTP_1_1)
    .connectTimeout(Duration.ofSeconds(5))
    .build()

  def call(
      base: String,
      method: String,
      path: String,
      body: Option[String] = None,
      key: Option[String] = Some(FullKey),
      headers: List[(String, String)] = Nil,
      contentType: Option[String] = Some("application/json")
  ): Resp = {
    val b = HttpRequest
      .newBuilder(URI.create(base + path))
      .timeout(Duration.ofSeconds(60))
      .method(
        method,
        body
          .map(s => HttpRequest.BodyPublishers.ofString(s))
          .getOrElse(HttpRequest.BodyPublishers.noBody())
      )
    key.foreach(k => b.header("X-Api-Key", k))
    if (body.nonEmpty) contentType.foreach(c => b.header("Content-Type", c))
    headers.foreach { case (k, v) => b.header(k, v) }
    val r = client.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
    Resp(
      r.statusCode(),
      r.headers().map().asScala.toMap.map { case (k, v) =>
        k.toLowerCase(java.util.Locale.ROOT) -> v.asScala.toList
      },
      r.body()
    )
  }

  def get(
      h: Harness,
      path: String,
      key: Option[String] = Some(FullKey),
      headers: List[(String, String)] = Nil
  ): Resp =
    call(h.base, "GET", path, None, key, headers)

  def post(
      h: Harness,
      body: String,
      key: Option[String] = Some(FullKey),
      headers: List[(String, String)] = Nil,
      path: String = "/v1/query"
  ): Resp = call(h.base, "POST", path, Some(body), key, headers)

  /** A request body for `question` with the window of the fixture's answered question. */
  def queryBody(
      question: String = org.scholarchain.rag.runtime.RuntimeServiceFixtures.AnsweredQuestion,
      mode: String = "sync",
      years: Option[(Int, Int)] = Some(2015 -> 2025),
      extra: List[(String, ujson.Value)] = Nil
  ): String = {
    val o = ujson.Obj("question" -> question, "mode" -> mode)
    years.foreach { case (a, b) => o("constraints") = ujson.Obj("yearFrom" -> a, "yearTo" -> b) }
    extra.foreach { case (k, v) => o(k) = v }
    o.render()
  }

  def traceparent(n: Int): (String, String) =
    "traceparent" -> f"00-${n}%032x-${n}%016x-01"

  /** Waits (bounded) until `cond` holds. */
  def waitUntil(millis: Long = 20000L)(cond: => Boolean): Boolean = {
    val end = System.nanoTime() + millis * 1000000L
    var ok = cond
    while (!ok && System.nanoTime() < end) { Thread.sleep(20); ok = cond }
    ok
  }
}
