package org.scholarchain.contracts.runtime

import org.scholarchain.core.config.ScholarChainConfig
import org.scholarchain.rag.embedding.{EmbeddingProviders, InputType}
import org.scholarchain.rag.retrieval.SemanticRetriever
import org.scholarchain.rag.runtime.{
  FakeModel,
  LanguageModel,
  RulePlanner,
  RuntimeFixtures,
  RuntimeService
}
import org.scholarchain.services.{
  MemoryLogSink,
  Role,
  ServerOptions,
  ServiceLimits,
  ServiceServer,
  ServiceTestKit
}
import org.scholarchain.storage.StorageConfig
import org.scholarchain.storage.jobs.{ManualClock, ServiceStores}
import org.scholarchain.storage.local.InMemoryWorkQueue
import org.scholarchain.storage.ports.{GraphReadPort, KeyValueStore, VectorIndexPort}
import org.scholarchain.storage.runtime.RuntimeStores

/** A runtime and a services server over rag's fixture world in which each runtime port (graph,
  * key-value store, vector index) can be replaced by a wrapper, so a test can put a stub AWS
  * adapter (or a failing port) under the real rag workflow and the real HTTP layer. The semantic
  * retriever is rebuilt over the wrapped vector index. Loopback only.
  */
object CrossModuleHarness {

  final case class Runtime(
      world: RuntimeFixtures.World,
      stores: RuntimeStores,
      service: RuntimeService
  )

  final case class Server(
      runtime: Runtime,
      server: ServiceServer,
      stores: ServiceStores,
      clock: ManualClock,
      log: MemoryLogSink
  ) extends AutoCloseable {
    def base: String = server.baseUri
    def close(): Unit = server.close()
  }

  def runtime(
      graph: GraphReadPort => GraphReadPort = identity,
      kv: KeyValueStore => KeyValueStore = identity,
      vectors: VectorIndexPort => VectorIndexPort = identity,
      model: RulePlanner => LanguageModel = r => new FakeModel(r),
      cfg: ScholarChainConfig = ServiceTestKit.config
  ): Runtime = {
    val w = RuntimeFixtures.world()
    val sc = StorageConfig.from(cfg).fold(e => sys.error(e.mkString("; ")), identity)
    val vix = vectors(w.env.vectors)
    val stores = RuntimeStores
      .of(sc, graph(w.env.graph), kv(w.env.storage.kv), vix, w.env.textStore)
      .fold(e => sys.error(e), identity)
    val q = EmbeddingProviders
      .create(RuntimeFixtures.config, InputType.Query)
      .fold(e => sys.error(e), identity)
    val retriever =
      new SemanticRetriever(q.port, q.spec, vix, RuntimeFixtures.storageConfig.vectors)
    val rt = RuntimeService
      .fromStores(
        cfg,
        stores,
        Some(retriever),
        model(w.rule),
        RuntimeFixtures.sparkRun,
        w.env.report.graphWindowLabels.toSet,
        owned = List(w.env),
        clock = () => 0L,
        environment = Some(RuntimeService.Local(w.env))
      )
      .fold(e => sys.error(e), identity)
    Runtime(w, stores, rt)
  }

  /** A server (role `all`, no worker unless asked) over `rt`. `jobKv` holds the jobs and
    * idempotency table (default: the world's in-memory key-value store).
    */
  def serve(
      rt: Runtime,
      jobKv: Option[KeyValueStore] = None,
      cfg: ScholarChainConfig = ServiceTestKit.config,
      clock: ManualClock = new ManualClock(),
      startWorker: Boolean = false
  ): Server = {
    val settings = cfg.service.get
    val sc = StorageConfig.from(cfg).fold(e => sys.error(e.mkString("; ")), identity)
    val queue = new InMemoryWorkQueue(
      settings.jobLeaseMillis.toLong + ServiceLimits.LocalVisibilityMarginMillis,
      clock
    )
    val stores = ServiceStores.over(
      jobKv.getOrElse(rt.world.env.storage.kv),
      sc.dynamoDb.querySessionTable,
      queue
    )
    val log = new MemoryLogSink
    val server = ServiceServer
      .start(
        rt.service,
        stores,
        ServerOptions(
          role = Role.All,
          port = Some(0),
          clock = clock,
          ids = new ServiceTestKit.FixedIds,
          logSink = log,
          env = ServiceTestKit.env.get,
          startWorker = startWorker,
          closeRuntime = true,
          name = "phase11"
        )
      )
      .fold(e => { rt.service.close(); sys.error(e) }, identity)
    Server(rt, server, stores, clock, log)
  }

  /** One HTTP call to `s` (ServiceTestKit's loopback client). */
  def call(
      s: Server,
      method: String,
      path: String,
      body: Option[String] = None,
      key: Option[String] = Some(ServiceTestKit.FullKey),
      headers: List[(String, String)] = Nil
  ): ServiceTestKit.Resp = ServiceTestKit.call(s.base, method, path, body, key, headers)

  def query(
      s: Server,
      question: String,
      headers: List[(String, String)] = Nil,
      key: Option[String] = Some(ServiceTestKit.FullKey)
  ): ServiceTestKit.Resp =
    call(s, "POST", "/v1/query", Some(ServiceTestKit.queryBody(question)), key, headers)
}
