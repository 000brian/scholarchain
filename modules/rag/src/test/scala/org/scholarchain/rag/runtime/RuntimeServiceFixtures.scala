package org.scholarchain.rag.runtime

import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicLong}

import org.scholarchain.core._
import org.scholarchain.core.config.{LlmProvider, ScholarChainConfig}
import org.scholarchain.rag.RagFixtures
import org.scholarchain.storage.{StorageConfig, StoreException}
import org.scholarchain.storage.ports.{
  ConditionalResult,
  GraphQuery,
  GraphReadPort,
  GraphReadSettings,
  GraphResult,
  Item,
  ItemKey,
  KeyValueStore
}
import org.scholarchain.storage.runtime.RuntimeStores

/** Fixtures for code that calls rag through [[RuntimeService]] (the services module's tests use
  * them through `rag % "test->test"`). Everything is local: the small synthetic world of
  * [[RuntimeFixtures]] (in-memory stores loaded through storage's `LocalRuntimeEnvironment`), the
  * fake LLM and the fake embedder. No network.
  *
  * {{{
  * val fx = RuntimeServiceFixtures.fixture()            // fake LLM, v2 prompts
  * val r = fx.service.workflow.run(RuntimeServiceFixtures.answeredRequest)
  * // r: non-abstained, >= 1 verified claim, >= 1 cited evidence item (see the suite)
  * fx.flaky.failGraph.set(true)                         // the next query fails: Left (502 path)
  * val slow = RuntimeServiceFixtures.fixture(m => new GatedModel(m)) // blocks until released
  * }}}
  */
object RuntimeServiceFixtures {

  /** The rag local v2 configuration (fake LLM, fake embedder; what services' config builds on). */
  lazy val configV2: ScholarChainConfig =
    RagFixtures.loadConfig("/config/rag-runtime-local-v2.json")

  /** A question the fixture world answers: not abstained, every claim `verified`, cited evidence.
    */
  val AnsweredQuestion = "How many distinct coauthors does Carol King have in 2015-2025?"

  /** The configured window label of [[AnsweredQuestion]]'s years. */
  val AnsweredWindowLabel: String = RuntimeFixtures.Full

  def answeredRequest: QueryRequest =
    QueryRequest(AnsweredQuestion, Some(AnsweredWindowLabel))

  /** A question the fixture world abstains on (an ambiguous name). */
  val AbstainedQuestion = "Who are the direct collaborators of Wei Wang in 2015-2025?"

  /** A runtime handle over one fixture world.
    *
    * @param world
    *   the loaded world (`world.env` is the `LocalRuntimeEnvironment`, for `ServiceStores.local`)
    * @param flaky
    *   the stores the workflow uses: the world's stores with failure switches (all off)
    * @param service
    *   the handle; its `localEnvironment` is `world.env`; `close()` closes the world
    */
  final case class Fixture(
      world: RuntimeFixtures.World,
      flaky: FlakyStores,
      service: RuntimeService
  )

  /** A fresh fixture world and a [[RuntimeService]] built by [[RuntimeService.fromStores]] over it.
    *
    * @param model
    *   the model, given the world's rule planner (default: the fake model); wrap it with
    *   [[GatedModel]], [[SlowModel]] or [[FailingModel]] for timeout and failure tests
    * @param config
    *   default [[configV2]] (services may pass their own config holding a `service` section)
    */
  def fixture(
      model: RulePlanner => LanguageModel = r => new FakeModel(r),
      config: ScholarChainConfig = configV2
  ): Fixture = {
    val w = RuntimeFixtures.world()
    val storageConfig = StorageConfig.from(config).fold(e => sys.error(e.mkString("; ")), identity)
    val flaky = new FlakyStores(w.env, storageConfig)
    val svc = RuntimeService
      .fromStores(
        config,
        flaky.stores,
        Some(w.retriever),
        model(w.rule),
        RuntimeFixtures.sparkRun,
        w.env.report.graphWindowLabels.toSet,
        owned = List(w.env),
        environment = Some(RuntimeService.Local(w.env))
      )
      .fold(e => sys.error(e), identity)
    Fixture(w, flaky, svc)
  }

  /** The world's runtime stores with switches that make the graph port throw or the key-value store
    * throw a transient `StoreException` (a store failure after storage's retries). The workflow
    * turns a throwing store into a Left; `EvidenceStore` and `QueryAuditWriter` reads turn a
    * key-value failure into a Left.
    */
  final class FlakyStores(env: RuntimeStores, storageConfig: StorageConfig) {
    val failGraph = new AtomicBoolean(false)
    val failKeyValue = new AtomicBoolean(false)

    /** Only writes (`putIfAbsent`, `putIfVersion`) to the evidence bundle table throw (the
      * workflow's `putBundle` returns Left; reads still work).
      */
    val failEvidenceWrites = new AtomicBoolean(false)

    /** Only writes to the run ledger table throw (the workflow's audit append returns Left). */
    val failAuditWrites = new AtomicBoolean(false)
    val graphCalls = new AtomicLong(0L)

    private val kvOf: KeyValueStore = env match {
      case l: org.scholarchain.storage.local.LocalRuntimeEnvironment => l.storage.kv
      case _ => sys.error("FlakyStores needs a LocalRuntimeEnvironment")
    }

    val graph: GraphReadPort = new GraphReadPort {
      def settings: GraphReadSettings = env.graph.settings
      def run(query: GraphQuery): Either[String, GraphResult] = {
        graphCalls.incrementAndGet()
        if (failGraph.get) throw new StoreException("injected graph failure", true)
        env.graph.run(query)
      }
    }

    val kv: KeyValueStore = new KeyValueStore {
      private def check(): Unit =
        if (failKeyValue.get) throw new StoreException("injected key-value failure", true)
      private def checkWrite(table: String): Unit = {
        check()
        if (failEvidenceWrites.get && table == storageConfig.dynamoDb.evidenceBundleTable)
          throw new StoreException("injected evidence write failure", true)
        if (failAuditWrites.get && table == storageConfig.dynamoDb.runLedgerTable)
          throw new StoreException("injected audit write failure", true)
      }
      def putIfAbsent(table: String, item: Item): ConditionalResult = {
        checkWrite(table); kvOf.putIfAbsent(table, item)
      }
      def putIfVersion(table: String, item: Item, expectedVersion: Long): ConditionalResult = {
        checkWrite(table); kvOf.putIfVersion(table, item, expectedVersion)
      }
      def get(table: String, key: ItemKey): Option[Item] = { check(); kvOf.get(table, key) }
      def query(
          table: String,
          pk: String,
          skPrefix: Option[String],
          limit: Int,
          descending: Boolean
      ): List[Item] = { check(); kvOf.query(table, pk, skPrefix, limit, descending) }
    }

    /** The runtime stores over [[graph]] and [[kv]] (same tables, vectors and texts as the world).
      */
    val stores: RuntimeStores = RuntimeStores
      .of(storageConfig, graph, kv, env.vectors, env.textStore)
      .fold(e => sys.error(e), identity)
  }

  /** Delegates to `delegate` after holding every call until [[release]] (or `maxWaitMillis`).
    * `entered` counts calls that started; [[awaitEntered]] waits for the first one. Use it for a
    * workflow slower than a deadline that finishes on demand.
    */
  final class GatedModel(delegate: LanguageModel, maxWaitMillis: Long = 60000L)
      extends LanguageModel {
    private val gate = new CountDownLatch(1)
    private val first = new CountDownLatch(1)
    val entered = new AtomicInteger(0)
    def provider: LlmProvider = delegate.provider
    def modelId: String = delegate.modelId
    val usage = new LlmUsage
    def release(): Unit = gate.countDown()
    def awaitEntered(millis: Long): Boolean = first.await(millis, TimeUnit.MILLISECONDS)
    def complete(r: ModelRequest): Either[ModelFailure, ModelReply] = {
      entered.incrementAndGet()
      first.countDown()
      gate.await(maxWaitMillis, TimeUnit.MILLISECONDS)
      delegate.complete(r)
    }
  }

  /** Delegates to `delegate` after sleeping `delayMillis` (adjustable) on every call. */
  final class SlowModel(delegate: LanguageModel, initialDelayMillis: Long) extends LanguageModel {
    val delayMillis = new AtomicLong(initialDelayMillis)
    def provider: LlmProvider = delegate.provider
    def modelId: String = delegate.modelId
    val usage = new LlmUsage
    def complete(r: ModelRequest): Either[ModelFailure, ModelReply] = {
      val d = delayMillis.get
      if (d > 0) Thread.sleep(d)
      delegate.complete(r)
    }
  }

  /** Every call fails after the client's retries with `cause` (default `http-500`). The workflow
    * still answers (deterministic fallbacks); use [[FlakyStores]] for a failing workflow.
    */
  final class FailingModel(cause: String = ModelFailure.http(500)) extends LanguageModel {
    val provider: LlmProvider = LlmProvider.Anthropic
    val modelId = "failing-test-model"
    val usage = new LlmUsage
    val calls = new AtomicInteger(0)
    def complete(r: ModelRequest): Either[ModelFailure, ModelReply] = {
      calls.incrementAndGet()
      Left(ModelFailure(s"injected model failure ($cause)", 0, cause))
    }
  }

  /** The evidence IDs a response cites (claims' evidence IDs, distinct, in order). */
  def citedEvidence(r: WorkflowResult): List[EvidenceId] =
    r.response.claims.flatMap(_.evidenceIds).distinct
}
