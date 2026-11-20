package org.scholarchain.storage

import scala.collection.mutable
import scala.jdk.CollectionConverters._

import org.scholarchain.core._
import org.scholarchain.storage.dynamo.ArtifactRegistry
import org.scholarchain.storage.ingest._
import org.scholarchain.storage.local._
import org.scholarchain.storage.ports._

/** G-27 (Phase 15): batched skip check, bounded parallel writes, exact counts. */
class IngestBatchingSuite extends munit.FunSuite {
  import IngestFakes._
  import TestFixtures._

  private val run =
    StorageVersion.runId("storage.vector-ingest", "sha256:batching", storageConfig.fingerprint)
  private val otherRun =
    StorageVersion.runId("storage.vector-ingest", "sha256:other-code", storageConfig.fingerprint)
  private val registryTable = storageConfig.dynamoDb.artifactRegistryTable
  private val ledgerTable = storageConfig.dynamoDb.runLedgerTable
  private val Profile = "researcher-profile-v1"

  final class RecordingSleeper extends Sleeper {
    val delays: mutable.ArrayBuffer[Long] = mutable.ArrayBuffer.empty
    def sleep(millis: Long): Unit = synchronized(delays += millis)
  }

  /** In-memory stores behind counting wrappers, and a pipeline over them. */
  final class Rig(
      settings: IngestSettings = IngestSettings(500, storageConfig.retryPolicy, "dblp"),
      delayMillis: Long = 0L,
      wrapVectors: VectorIndexPort => VectorIndexPort = identity
  ) {
    val env: LocalStorageEnvironment = LocalStorageEnvironment.create(storageConfig, None)
    val writes = new InFlight(delayMillis)
    val objects = new CountingObjectStore(env.objects, writes)
    val kv = new CountingKv(env.kv, writes)
    val vectors = new CountingVectors(env.vectors)
    val textStore = new TextStore(objects, env.layout)
    def pipeline(
        embedder: EmbeddingPort,
        s: IngestSettings = settings,
        sleeper: Sleeper = Sleeper.none
    ): VectorIngestPipeline =
      new VectorIngestPipeline(
        embedder,
        textStore,
        wrapVectors(vectors),
        kv,
        storageConfig.dynamoDb,
        env.spec,
        storageConfig.windows,
        s,
        env.rejects,
        sleeper
      )
    def snapshot: (Long, Long, Long, Long) =
      (objects.calls.get, kv.calls(registryTable), vectors.getCalls.get, vectors.putCalls.get)
  }

  private def stub(batch: Int = 64) = new StubEmbedder("storage-stub-sha256-v1", 1024, batch)
  private def docs(n: Int): List[SemanticDocument] = (1 to n).toList.map(profileDoc(_))

  test(
    "the skip pass makes no per-document call: 250 unchanged documents cost 3 GetVectors calls, " +
      "no S3 call, no registry call, no put and no embedding, even under another run and code version"
  ) {
    val rig = new Rig()
    val first = rig.pipeline(stub()).run(run, docs(250).iterator)
    assertEquals(first.written, 250L)
    val (o0, r0, g0, p0) = rig.snapshot
    val ledgerBefore = rig.kv.calls(ledgerTable)
    val embedder = stub()
    val second = rig.pipeline(embedder).run(otherRun, docs(250).iterator)
    assertEquals((second.skipped, second.skipRatio, second.written), (250L, 1.0, 0L))
    assertEquals(second.skipReadCalls, 3L)
    assertEquals(
      rig.snapshot,
      (o0, r0, g0 + 3L, p0),
      "objects, registry, GetVectors, PutVectors"
    )
    assertEquals((second.textCreated, second.textAlreadyPresent, second.embedCalls), (0L, 0L, 0L))
    assertEquals(embedder.calls, 0L)
    assertEquals((second.registryReads, second.registryWrites), (0L, 0L))
    // RunLedger only: attempt lookup, attempt claim, summary (never per document)
    assertEquals(rig.kv.calls(ledgerTable) - ledgerBefore, 3L)
  }

  test("GetVectors calls hold at most 100 keys: 100 documents take 1 call, 101 take 2") {
    val rig = new Rig()
    val r100 = rig.pipeline(stub()).run(run, docs(100).iterator)
    assertEquals(r100.skipReadCalls, 1L)
    val rig2 = new Rig()
    val r101 = rig2.pipeline(stub()).run(run, docs(101).iterator)
    assertEquals(r101.skipReadCalls, 2L)
    assertEquals(rig2.vectors.keysPerGet.asScala.toList.map(_.intValue).sorted, List(1, 100))
    assertEquals(r101.written, 101L)
  }

  test("the skip check reads metadata only, in windows of windowDocuments") {
    val rig = new Rig(IngestSettings(500, storageConfig.retryPolicy, "dblp", windowDocuments = 30))
    val r = rig.pipeline(stub()).run(run, docs(70).iterator)
    assertEquals(r.skipReadCalls, 3L)
    assertEquals(rig.vectors.keysPerGet.asScala.toList.map(_.intValue), List(30, 30, 10))
    assertEquals(r.written, 70L)
  }

  test(
    "keys missing from a GetVectors response are written again and the rest skipped; the rewrite is a recorded overwrite"
  ) {
    val rig = new Rig()
    val ds = docs(10)
    rig.pipeline(stub()).run(run, ds.iterator)
    rig.vectors.drop = ds.take(3).map(_.vectorKey).toSet
    val r = rig.pipeline(stub()).run(run, ds.iterator)
    assertEquals((r.skipped, r.written), (7L, 3L))
    assertEquals(r.perIndex(Profile).reembeddedChanged, 3L)
    val reg = new ArtifactRegistry(rig.env.kv, registryTable)
    assertEquals(reg.latest("vector", s"$Profile/${ds.head.vectorKey}").map(_.version), Some(2L))
  }

  test("a throttled GetVectors batch is retried with bounded backoff and the counts stay exact") {
    val sleeper = new RecordingSleeper
    val rig =
      new Rig(wrapVectors = v => new RetryingVectorIndex(v, storageConfig.retryPolicy, sleeper))
    rig.pipeline(stub()).run(run, docs(150).iterator)
    rig.vectors.failNextGets(1, transient = true)
    val calls0 = rig.vectors.getCalls.get
    val r = rig.pipeline(stub()).run(run, docs(150).iterator)
    assertEquals((r.skipped, r.written, r.rejected), (150L, 0L, 0L))
    assertEquals(r.skipReadCalls, 2L, "logical calls")
    assertEquals(rig.vectors.getCalls.get - calls0, 3L, "two requests plus one retry")
    assertEquals(sleeper.delays.toList, List(200L))
  }

  test(
    "a skip read that keeps failing rejects its documents, is recorded, and the next run writes them"
  ) {
    val rig = new Rig()
    rig.vectors.failNextGets(1, transient = false)
    val r = rig.pipeline(stub()).run(run, docs(5).iterator)
    assertEquals((r.rejected, r.written, r.embedCalls), (5L, 0L, 0L))
    val events = rig.env.kv
      .query(ledgerTable, s"run#${run.value}", Some(f"attempt#${r.attempt}%06d#"), 100, false)
      .map(_.str("eventType"))
    assert(events.contains("skip-read-failure"), events.toString)
    val again = rig.pipeline(stub()).run(run, docs(5).iterator)
    assertEquals(again.written, 5L)
  }

  test(
    "parallel writes keep counts exact: 300 documents, 16 store calls and 4 embeddings in flight at most"
  ) {
    val rig = new Rig(delayMillis = 2L)
    val embedder = stub(batch = 8)
    val r = rig.pipeline(embedder).run(run, docs(300).iterator)
    assertEquals((r.input, r.written, r.rejected, r.skipped), (300L, 300L, 0L, 0L))
    assertEquals((r.textCreated, r.registryReads, r.registryWrites), (300L, 300L, 300L))
    assertEquals(r.perIndex(Profile).embeddedNew, 300L)
    assertEquals(r.embedCalls, 38L) // windows of 300: ceil(300 / 8)
    assertEquals(embedder.calls, r.embedCalls)
    assertEquals(embedder.texts, 300L)
    assertEquals(rig.env.objects.objectCount, 300)
    assertEquals(rig.env.kv.itemCount(registryTable), 300L)
    assertEquals(rig.env.vectors.count(Profile), 300)
    assert(rig.writes.max.get > 1, s"no parallel store call (max ${rig.writes.max.get})")
    assert(rig.writes.max.get <= 16, s"more than 16 store calls in flight (${rig.writes.max.get})")
  }

  test("writeParallelism 1 keeps every store call on one thread (the bound is honoured)") {
    val rig =
      new Rig(IngestSettings(500, storageConfig.retryPolicy, "dblp", writeParallelism = 1), 1L)
    val r = rig.pipeline(stub(batch = 8)).run(run, docs(40).iterator)
    assertEquals(r.written, 40L)
    assertEquals(rig.writes.max.get, 1)
  }

  test("every bound is checked") {
    val p = storageConfig.retryPolicy
    intercept[IllegalArgumentException](IngestSettings(500, p, "d", writeParallelism = 0))
    intercept[IllegalArgumentException](IngestSettings(500, p, "d", writeParallelism = 65))
    intercept[IllegalArgumentException](IngestSettings(500, p, "d", embedConcurrency = 0))
    intercept[IllegalArgumentException](IngestSettings(500, p, "d", embedConcurrency = 17))
    intercept[IllegalArgumentException](IngestSettings(500, p, "d", windowDocuments = 0))
    intercept[IllegalArgumentException](IngestSettings(500, p, "d", windowDocuments = 5001))
    val d = IngestSettings(500, p, "d")
    assertEquals((d.writeParallelism, d.embedConcurrency, d.windowDocuments), (16, 4, 500))
  }

  test("the registry claims only written vectors: a failed PutVectors registers nothing") {
    val rig = new Rig()
    rig.vectors.failNextPuts(1, transient = false)
    val r = rig.pipeline(stub()).run(run, docs(20).iterator)
    assertEquals((r.written, r.rejected, r.registryWrites), (0L, 20L, 0L))
    assertEquals(rig.env.kv.itemCount(registryTable), 0L)
  }
}
