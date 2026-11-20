package org.scholarchain.storage

import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

import org.scholarchain.core._
import org.scholarchain.storage.aws.S3VectorsIndex
import org.scholarchain.storage.dynamo.TableSpecs
import org.scholarchain.storage.ingest._
import org.scholarchain.storage.ingest.ReembedOutcome._
import org.scholarchain.storage.local._
import org.scholarchain.storage.ports._

/** The re-embed write path (Phase 15 decision 6.3 and 6.5). */
class ReembedSuite extends munit.FunSuite {
  import IngestFakes._
  import TestFixtures._

  private val run =
    StorageVersion.runId("storage.vector-ingest", "sha256:reembed", storageConfig.fingerprint)
  private val Profile = "researcher-profile-v1"
  private val sessions = storageConfig.dynamoDb.querySessionTable
  private val registryTable = storageConfig.dynamoDb.artifactRegistryTable
  private val stubModel = "storage-stub-sha256-v1"

  /** Stores holding 3 ingested profile documents (realistic metadata from the real pipeline). */
  final class Rig(clock: () => Long = () => 1_800_000_000L) {
    val env: LocalStorageEnvironment = LocalStorageEnvironment.create(storageConfig, None)
    val kv = new CountingKv(env.kv)
    val vectors = new CountingVectors(env.vectors)
    val docs: List[SemanticDocument] = (1 to 3).toList.map(profileDoc(_))
    env.pipeline(new StubEmbedder(stubModel, 1024, 8), "dblp").run(run, docs.iterator)
    def reembedder(e: EmbeddingPort, now: () => Long = clock): VectorReembedder =
      new VectorReembedder(
        e,
        vectors,
        env.textStore,
        kv,
        sessions,
        env.spec,
        storageConfig.retryPolicy,
        Sleeper.none,
        now
      )
    def stored(key: String): VectorRecord =
      env.vectors.getVectors(Profile, List(key), returnData = true).head
    def ingest(ds: List[SemanticDocument]) =
      env.pipeline(new StubEmbedder(stubModel, 1024, 8), "dblp").run(run, ds.iterator)
  }

  private def task(rig: Rig, n: Int = 0, id: String = "c-attempt-1-000001") =
    ReembedTask(id, Profile, rig.docs(n).vectorKey)

  test(
    "a re-embed writes new data under the existing key with exactly the existing metadata; " +
      "no text object, no registry version, no new key"
  ) {
    val rig = new Rig()
    val t = task(rig)
    val before = rig.stored(t.vectorKey)
    val (objects, registry, count) =
      (
        rig.env.objects.objectCount,
        rig.env.kv.itemCount(registryTable),
        rig.env.vectors.count(Profile)
      )
    val e = new FlipEmbedder()
    val out = rig.reembedder(e).reembed(List(t))
    assertEquals(
      out,
      Vector(Processed(t, ContentHash.of(rig.docs.head.text), completionRecorded = true))
    )
    val after = rig.stored(t.vectorKey)
    assertEquals(after.metadata, before.metadata)
    assertEquals(after.metadata.all.keySet, before.metadata.all.keySet)
    assert(after.metadata.all.contains("researcherId"))
    assert(!after.data.sameElements(before.data), "data must change")
    assert(after.data.sameElements(FlipEmbedder.vector(rig.docs.head.text)))
    assertEquals(
      (
        rig.env.objects.objectCount,
        rig.env.kv.itemCount(registryTable),
        rig.env.vectors.count(Profile)
      ),
      (objects, registry, count)
    )
    assertEquals(e.calls.get, 1L)
  }

  test("a second delivery after completion is replayed: no embedding call and no write") {
    val rig = new Rig()
    val t = task(rig)
    val e = new FlipEmbedder()
    val r = rig.reembedder(e)
    r.reembed(List(t))
    val puts = rig.vectors.putCalls.get
    val out = r.reembed(List(t))
    assertEquals(out, Vector(Replayed(t, ContentHash.of(rig.docs.head.text))))
    assertEquals((e.calls.get, rig.vectors.putCalls.get), (1L, puts))
    assertEquals(
      (r.counters.processed.get, r.counters.replayed.get, r.counters.tasks.get),
      (1L, 1L, 2L)
    )
  }

  test(
    "hash A, then hash B (text changed by an ingest), then a replay of B: B is processed again, the last delivery is replayed"
  ) {
    val rig = new Rig()
    val t = task(rig)
    val e = new FlipEmbedder()
    val r = rig.reembedder(e)
    val hashA = ContentHash.of(rig.docs.head.text)
    assertEquals(r.reembed(List(t)), Vector(Processed(t, hashA, true)))
    val changed = profileDoc(1, " Updated.")
    assertEquals(changed.vectorKey, t.vectorKey)
    assertEquals(rig.ingest(List(changed)).written, 1L)
    val hashB = ContentHash.of(changed.text)
    assertNotEquals(hashA, hashB)
    assertEquals(r.reembed(List(t)), Vector(Processed(t, hashB, true)), "same taskId, new text")
    assert(rig.stored(t.vectorKey).data.sameElements(FlipEmbedder.vector(changed.text)))
    val (calls, puts) = (e.calls.get, rig.vectors.putCalls.get)
    assertEquals(r.reembed(List(t)), Vector(Replayed(t, hashB)))
    assertEquals((e.calls.get, rig.vectors.putCalls.get), (calls, puts), "no embed, no write")
    assertEquals(calls, 2L)
  }

  test(
    "two concurrent deliveries of one task: both processed, one completion record, final state correct"
  ) {
    val rig = new Rig()
    val t = task(rig)
    val bothInside = new CountDownLatch(2)
    val e = new FlipEmbedder(beforeEmbed = () => {
      bothInside.countDown()
      bothInside.await(10, TimeUnit.SECONDS); ()
    })
    val r = rig.reembedder(e)
    val pool = Executors.newFixedThreadPool(2)
    implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(pool)
    try {
      val outs = Await.result(
        Future.sequence(List(Future(r.reembed(List(t))), Future(r.reembed(List(t))))),
        30.seconds
      )
      assertEquals(outs.flatten.map(_.code), List("processed", "processed"))
      assert(outs.flatten.forall { case p: Processed => p.completionRecorded; case _ => false })
      assertEquals((e.calls.get, r.counters.processed.get), (2L, 2L))
      assertEquals(r.counters.completionWrites.get, 1L)
      assert(rig.stored(t.vectorKey).data.sameElements(FlipEmbedder.vector(rig.docs.head.text)))
      assertEquals(r.reembed(List(t)).map(_.code), Vector("replayed"))
    } finally pool.shutdown()
  }

  test(
    "a failure between the vector write and the completion record: the redelivery re-embeds, final state correct"
  ) {
    val rig = new Rig()
    val t = task(rig)
    val before = rig.stored(t.vectorKey)
    val e = new FlipEmbedder()
    val r = rig.reembedder(e)
    rig.kv.failPutsOn(sessions, 1, transient = true)
    val hash = ContentHash.of(rig.docs.head.text)
    assertEquals(r.reembed(List(t)), Vector(Processed(t, hash, completionRecorded = false)))
    assert(rig.stored(t.vectorKey).data.sameElements(FlipEmbedder.vector(rig.docs.head.text)))
    assertEquals(r.reembed(List(t)), Vector(Processed(t, hash, completionRecorded = true)))
    assertEquals(e.calls.get, 2L)
    val after = rig.stored(t.vectorKey)
    assertEquals(after.metadata, before.metadata)
    assert(after.data.sameElements(FlipEmbedder.vector(rig.docs.head.text)))
    assertEquals(r.counters.completionMissing.get, 1L)
    assertEquals(r.reembed(List(t)).map(_.code), Vector("replayed"))
  }

  test("refuses to write when the stored metadata lacks a field later reads need") {
    val rig = new Rig()
    val t = task(rig)
    val good = rig.stored(t.vectorKey)
    val stripped = good.copy(
      metadata = good.metadata.copy(nonFilterable = good.metadata.nonFilterable - "textS3Uri")
    )
    rig.env.vectors.putVectors(Profile, List(stripped))
    val noResearcher = rig.stored(rig.docs(1).vectorKey)
    rig.env.vectors.putVectors(
      Profile,
      List(
        noResearcher.copy(metadata =
          noResearcher.metadata.copy(filterable = noResearcher.metadata.filterable - "researcherId")
        )
      )
    )
    val e = new FlipEmbedder()
    val r = rig.reembedder(e)
    val puts = rig.vectors.putCalls.get
    val out = r.reembed(List(t, task(rig, 1, "c-attempt-1-000002")))
    assertEquals(
      out.map(o => (o.code, o)).map {
        case (c, o: PermanentFailure) => (c, o.detail); case (c, _) => (c, "")
      },
      Vector(("metadata-missing", "textS3Uri"), ("metadata-missing", "researcherId"))
    )
    assertEquals((e.calls.get, rig.vectors.putCalls.get), (0L, puts))
    assertEquals(rig.stored(t.vectorKey).metadata, stripped.metadata, "nothing written")
    assertEquals(r.counters.permanentFailures.get, 2L)
  }

  test(
    "unknown index, unknown key, a bad task ID and a model mismatch are permanent, with no embedding and no write"
  ) {
    val rig = new Rig()
    val e = new FlipEmbedder()
    val r = rig.reembedder(e)
    val out = r.reembed(
      List(
        ReembedTask("t-1", "no-such-index-v1", rig.docs.head.vectorKey),
        ReembedTask("t-2", Profile, s"rp:$Full:${rC.value}"),
        ReembedTask("bad id with spaces", Profile, rig.docs.head.vectorKey),
        ReembedTask("t-4", Profile, "")
      )
    )
    assertEquals(
      out.map(_.code),
      Vector("unknown-index", "unknown-vector-key", "invalid-task", "invalid-task")
    )
    val other = rig.reembedder(new FlipEmbedder(modelId = "voyage-other-v9"))
    assertEquals(other.reembed(List(task(rig))).map(_.code), Vector("model-mismatch"))
    assertEquals((e.calls.get, rig.vectors.putCalls.get), (0L, 0L))
  }

  test("transient failures write nothing: provider failure, throttled read, failed write") {
    val rig = new Rig()
    val t = task(rig)
    val before = rig.stored(t.vectorKey)
    val e = new FlipEmbedder()
    e.fail = true
    val r = rig.reembedder(e)
    assertEquals(r.reembed(List(t)).map(_.code), Vector("embed-failed"))
    e.fail = false
    rig.vectors.failNextGets(1, transient = true)
    assertEquals(r.reembed(List(t)).map(_.code), Vector("vector-read-failed"))
    rig.vectors.failNextPuts(100, transient = true)
    assertEquals(r.reembed(List(t)).map(_.code), Vector("vector-write-failed"))
    rig.vectors.failNextPuts(0, transient = true)
    assert(rig.stored(t.vectorKey).data.sameElements(before.data), "nothing written")
    assertEquals(
      rig.env.kv.get(
        sessions,
        VectorReembedder.completionKey(t.taskId, ContentHash.of(rig.docs.head.text))
      ),
      None
    )
    assertEquals(r.counters.transientFailures.get, 3L)
    assertEquals(r.reembed(List(t)).map(_.code), Vector("processed"))
  }

  test("the ingest skip check still skips every vector after a re-embed") {
    val rig = new Rig()
    val out =
      rig.reembedder(new FlipEmbedder()).reembed(rig.docs.indices.map(i => task(rig, i, s"t-$i")))
    assertEquals(out.map(_.code).distinct, Vector("processed"))
    val again = rig.ingest(rig.docs)
    assertEquals((again.skipRatio, again.written, again.embedCalls), (1.0, 0L, 0L))
  }

  test(
    "the completion record lives in QuerySession under its own prefix, with an expiry; an expired one is not a replay"
  ) {
    var now = 1_800_000_000L
    val rig = new Rig(() => now)
    val t = task(rig)
    val e = new FlipEmbedder()
    val r = rig.reembedder(e)
    r.reembed(List(t))
    val hash = ContentHash.of(rig.docs.head.text)
    val k = VectorReembedder.completionKey(t.taskId, hash)
    assert(k.pk.startsWith("reembed#") && k.pk.length == "reembed#".length + 64, k.pk)
    assertEquals(k.sk, TableSpecs.ReembedSk)
    assertNotEquals(k.pk, VectorReembedder.completionKey(t.taskId, ContentHash.of("other")).pk)
    val item = rig.env.kv.get(sessions, k).get
    assertEquals(item.long("expiresAt"), Some(now + VectorReembedder.CompletionTtlSeconds))
    assertEquals(
      (item.str("taskId"), item.str("textHash"), item.str("indexName")),
      (t.taskId, hash, Profile)
    )
    now += VectorReembedder.CompletionTtlSeconds + 1
    assertEquals(r.reembed(List(t)).map(_.code), Vector("processed"))
    assertEquals(e.calls.get, 2L)
    assert(TableSpecs.QuerySession.accessPatterns.exists(_.id == "QS6"))
  }

  test("counters are exact under concurrent callers") {
    val rig = new Rig()
    val e = new FlipEmbedder()
    val r = rig.reembedder(e)
    val t = task(rig)
    r.reembed(List(t))
    val pool = Executors.newFixedThreadPool(8)
    implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(pool)
    try {
      val calls = (1 to 8).map(_ =>
        Future((1 to 50).foreach { _ =>
          r.reembed(List(t, ReembedTask("bad id", Profile, t.vectorKey)))
        })
      )
      Await.result(Future.sequence(calls), 60.seconds)
      assertEquals(r.counters.tasks.get, 801L)
      assertEquals(r.counters.replayed.get, 400L)
      assertEquals(r.counters.permanentFailures.get, 400L)
      assertEquals(r.counters.processed.get, 1L)
    } finally pool.shutdown()
  }

  test("GetVectors asks for metadata (the API default is false)") {
    val req =
      S3VectorsIndex.getVectorsRequest("example-vectors", Profile, List("k1"), returnData = false)
    assertEquals(req.returnMetadata(), java.lang.Boolean.TRUE)
    assertEquals(req.returnData(), java.lang.Boolean.FALSE)
  }

  test("more than 100 tasks per call are refused") {
    val rig = new Rig()
    intercept[IllegalArgumentException](
      rig
        .reembedder(new FlipEmbedder())
        .reembed((1 to 101).map(i => ReembedTask(s"t-$i", Profile, rig.docs.head.vectorKey)))
    )
  }
}
