package org.scholarchain.storage

import java.nio.file.{Files, Path}

import scala.collection.mutable

import org.scholarchain.core._
import org.scholarchain.storage.ingest._
import org.scholarchain.storage.local._
import org.scholarchain.storage.ports._
import org.scholarchain.storage.vectors._

class IngestPipelineSuite extends munit.FunSuite {
  import TestFixtures._

  final class RecordingSleeper extends Sleeper {
    val delays: mutable.ArrayBuffer[Long] = mutable.ArrayBuffer.empty
    def sleep(millis: Long): Unit = delays += millis
  }

  private val run =
    StorageVersion.runId("storage.vector-ingest", "sha256:fixture", storageConfig.fingerprint)

  private def docs: List[SemanticDocument] =
    profileSummaries.map(e =>
      SummaryDocuments.build("researcher_profile", Full, e, storageConfig.vectors).toOption.get
    ) ++
      communitySummaries.map(e =>
        SummaryDocuments.build("community", Full, e, storageConfig.vectors).toOption.get
      ) ++
      pathSummaries.map(e =>
        SummaryDocuments.build("path", Full, e, storageConfig.vectors).toOption.get
      )

  private def env(): (LocalStorageEnvironment, Path) = {
    val rejectFile = tempDir("ingest").resolve("vector-rejects.jsonl")
    (LocalStorageEnvironment.create(storageConfig, Some(rejectFile)), rejectFile)
  }

  private def stub(model: String = "storage-stub-sha256-v1", batch: Int = 4) =
    new StubEmbedder(model, 1024, batch)

  private def ledgerEvents(e: LocalStorageEnvironment, attempt: Int): List[Item] =
    e.kv.query(
      storageConfig.dynamoDb.runLedgerTable,
      s"run#${run.value}",
      Some(f"attempt#$attempt%06d#"),
      1000,
      descending = false
    )

  private def withText(d: SemanticDocument, text: String): SemanticDocument = {
    val h = ContentHash.of(text)
    d.copy(
      text = text,
      documentId = ChunkId.forSubjects(d.documentType, d.subjectIds, Some(Full), 0, h).toOption.get,
      provenance = d.provenance.copy(contentHash = Some(h))
    )
  }

  test("first run writes every document; the second run skips 100% and writes nothing") {
    val (e, _) = env()
    val p = e.pipeline(stub(), "dblp")
    val r1 = p.run(run, docs.iterator)
    assertEquals((r1.input, r1.written, r1.rejected, r1.textCreated), (6L, 6L, 0L, 6L))
    assertEquals(e.vectors.count("researcher-profile-v1"), 4)
    assertEquals(e.vectors.count("collaboration-community-v1"), 1)
    assertEquals(e.vectors.count("collaboration-path-v1"), 1)
    val puts = e.vectors.putCalls.get
    val objects = e.objects.objectCount
    val r2 = p.run(run, docs.iterator)
    assertEquals(r2.skipped, 6L)
    assertEquals(r2.skipRatio, 1.0)
    assertEquals(
      (r2.written, r2.embedCalls, r2.textCreated, r2.textAlreadyPresent),
      (0L, 0L, 0L, 6L)
    )
    assertEquals(e.vectors.putCalls.get, puts)
    assertEquals(e.objects.objectCount, objects)
    assertEquals(r2.attempt, 2)
  }

  test(
    "the same vector key with different text is re-embedded and the overwrite is recorded, never silent"
  ) {
    val (e, _) = env()
    val p = e.pipeline(stub(), "dblp")
    p.run(run, docs.iterator)
    val changed = withText(docs.head, docs.head.text + " Updated.")
    val r = p.run(run, Iterator(changed))
    assertEquals(r.perIndex("researcher-profile-v1").reembeddedChanged, 1L)
    val ev = ledgerEvents(e, r.attempt).filter(_.str("eventType") == "vector-overwrite")
    assertEquals(ev.size, 1)
    assertEquals(ev.head.str("oldTextHash"), ContentHash.of(docs.head.text))
    assertEquals(ev.head.str("newTextHash"), ContentHash.of(changed.text))
    val reg = new org.scholarchain.storage.dynamo.ArtifactRegistry(
      e.kv,
      storageConfig.dynamoDb.artifactRegistryTable
    )
    val latest = reg.latest("vector", s"researcher-profile-v1/${changed.vectorKey}").get
    assertEquals(latest.version, 2L)
    assertEquals(latest.item.str("previousTextHash"), ContentHash.of(docs.head.text))
    val stored = e.vectors
      .getVectors("researcher-profile-v1", List(changed.vectorKey), returnData = false)
      .head
    assertEquals(
      stored.metadata.nonFilterable("textHash"),
      MetaValue.Str(ContentHash.of(changed.text))
    )
  }

  test("a new embedding model re-embeds every key and records old and new model") {
    val (e, _) = env()
    e.pipeline(stub(), "dblp").run(run, docs.iterator)
    val r = e.pipeline(stub("storage-stub-sha256-v2"), "dblp").run(run, docs.iterator)
    assertEquals(r.total(_.reembeddedChanged), 6L)
    val ev = ledgerEvents(e, r.attempt).filter(_.str("eventType") == "vector-overwrite")
    assertEquals(ev.map(_.str("oldEmbeddingModel")).distinct, List("storage-stub-sha256-v1"))
    assertEquals(ev.map(_.str("newEmbeddingModel")).distinct, List("storage-stub-sha256-v2"))
  }

  test(
    "duplicate vector keys in one run: same text counted once, different text rejected and recorded"
  ) {
    val (e, rejectFile) = env()
    val d = docs.head
    val r = e
      .pipeline(stub(), "dblp")
      .run(run, Iterator(d, d, withText(d, "Another text for the same key.")))
    e.close()
    val st = r.perIndex("researcher-profile-v1")
    assertEquals((st.written, st.duplicatesSameText, st.rejected), (1L, 1L, 1L))
    assert(Files.readString(rejectFile).contains("duplicate vector key with different text"))
    assert(ledgerEvents(e, r.attempt).exists(_.str("eventType") == "vector-reject"))
  }

  test("documents that break the key rule, the index mapping or a configured window are rejected") {
    val (e, _) = env()
    val d = docs.head
    val badKey = d.copy(vectorKey = s"rp:$Full:${rB.value}")
    val badIndex = d.copy(indexName = "collaboration-path-v1")
    val badWindow = withText(d, d.text).copy(window = Some(YearWindow(2010, 2011)))
    val r = e.pipeline(stub(), "dblp").run(run, Iterator(badKey, badIndex, badWindow))
    assertEquals(r.rejected, 3L)
    assertEquals(r.written, 0L)
  }

  test("throttled PutVectors calls are retried with bounded backoff and the retries are recorded") {
    val (e, _) = env()
    val sleeper = new RecordingSleeper
    e.vectors.failNext(2, transient = true)
    val r = e.pipeline(stub(batch = 8), "dblp", sleeper = sleeper).run(run, docs.take(4).iterator)
    assertEquals(r.written, 4L)
    assertEquals(r.putRetries, 2L)
    assertEquals(sleeper.delays.toList, List(200L, 400L))
    assert(
      ledgerEvents(e, r.attempt).exists(i =>
        i.str("eventType") == "put-retried" && i.long("retries").contains(2L)
      )
    )
  }

  test(
    "retries are bounded: a batch still failing after maxRetries is rejected and recorded, then retried by the next run"
  ) {
    val (e, rejectFile) = env()
    val sleeper = new RecordingSleeper
    e.vectors.failNext(100, transient = true)
    val r = e.pipeline(stub(batch = 8), "dblp", sleeper = sleeper).run(run, docs.take(4).iterator)
    assertEquals(r.written, 0L)
    assertEquals(r.rejected, 4L)
    assertEquals(sleeper.delays.size, 5)
    assert(sleeper.delays.forall(_ <= 10000L))
    val failure = ledgerEvents(e, r.attempt).find(_.str("eventType") == "put-failure").get
    assertEquals(failure.long("retries"), Some(5L))
    e.close()
    assertEquals(Files.readAllLines(rejectFile).size, 4)
    e.vectors.failNext(0, transient = true)
    val again = e.pipeline(stub(batch = 8), "dblp").run(run, docs.take(4).iterator)
    assertEquals(again.written, 4L, "nothing was registered, so the next run writes them")
  }

  test("a permanent PutVectors failure is not retried") {
    val (e, _) = env()
    val sleeper = new RecordingSleeper
    e.vectors.failNext(1, transient = false)
    val r = e.pipeline(stub(batch = 8), "dblp", sleeper = sleeper).run(run, docs.take(2).iterator)
    assertEquals(r.rejected, 2L)
    assertEquals(sleeper.delays.size, 0)
  }

  test(
    "an embedder that returns the wrong number of vectors fails the batch, recorded in RunLedger"
  ) {
    val (e, _) = env()
    val broken = new EmbeddingPort {
      val modelId = "broken-v1"; val dimensions = 1024; val maxBatchSize = 8
      def embed(texts: IndexedSeq[String]) =
        Right(EmbeddingBatch(texts.drop(1).map(StubEmbedder.vector(_, 1024)), 1))
    }
    val r = e.pipeline(broken, "dblp").run(run, docs.take(3).iterator)
    assertEquals(r.rejected, 3L)
    assert(
      ledgerEvents(e, r.attempt).exists(i =>
        i.str("eventType") == "embed-failure" && i.long("retries").contains(1L)
      )
    )
    intercept[IllegalArgumentException](e.pipeline(new StubEmbedder("x", 512, 4), "dblp"))
  }

  test("the vector result leads to its source text through the ordinary S3 pointer") {
    val (e, _) = env()
    e.pipeline(stub(), "dblp").run(run, docs.iterator)
    val d = docs(1)
    val hits = e.vectors.query(
      "researcher-profile-v1",
      StubEmbedder.vector(d.text, 1024),
      3,
      None,
      returnMetadata = true
    )
    assertEquals(hits.head.key, d.vectorKey)
    val meta = VectorMetadataCodec
      .decode(hits.head.key, "researcher-profile-v1", hits.head.metadata.get)
      .toOption
      .get
    assertEquals(meta.embeddingModel, "storage-stub-sha256-v1")
    assertEquals(meta.sourceEvidenceId, d.sourceEvidenceId)
    assertEquals(e.textStore.fetch(meta.textS3Uri, meta.textHash), Right(d.text))
    assert(e.textStore.fetch(meta.textS3Uri, ContentHash.of("other")).isLeft)
    assert(!hits.head.metadata.get.all.values.exists {
      case MetaValue.Str(s) => s.contains(d.text)
      case _                => false
    })
  }

  test("metadata filters work on filterable keys and refuse non-filterable ones") {
    val (e, _) = env()
    e.pipeline(stub(), "dblp").run(run, docs.iterator)
    val q = StubEmbedder.vector("anything", 1024)
    val f = MetadataFilter.And(
      List(
        MetadataFilter.Eq("researcherId", MetaValue.Str(rC.value)),
        MetadataFilter.Gte("windowStartYear", 2015)
      )
    )
    val hits = e.vectors.query("researcher-profile-v1", q, 10, Some(f), returnMetadata = false)
    assertEquals(hits.map(_.key), List(s"rp:$Full:${rC.value}"))
    intercept[StoreException](
      e.vectors.query(
        "researcher-profile-v1",
        q,
        10,
        Some(MetadataFilter.Eq("textHash", MetaValue.Str("x"))),
        false
      )
    )
  }

  test("PutVectors batches hold at most 500 vectors and stay under the payload bound") {
    val v = VectorRecord(
      "k",
      new Array[Float](1024),
      StoredMetadata(
        scala.collection.immutable.ListMap.empty,
        scala.collection.immutable.ListMap.empty
      )
    )
    val bs = PutVectorsBatcher.batches(Vector.fill(1200)(v), 500)
    assertEquals(bs.map(_.size), List(500, 500, 200))
    val small = PutVectorsBatcher.batches(
      Vector.fill(10)(v),
      500,
      maxPayloadBytes = 3 * PutVectorsBatcher.estimateBytes(v)
    )
    assertEquals(small.map(_.size), List(3, 3, 3, 1))
    intercept[IllegalArgumentException](PutVectorsBatcher.batches(Vector(v), 501))
    val (e, _) = env()
    val p = new VectorIngestPipeline(
      stub(),
      e.textStore,
      e.vectors,
      e.kv,
      storageConfig.dynamoDb,
      e.spec,
      storageConfig.windows,
      IngestSettings(2, storageConfig.retryPolicy, "dblp"),
      e.rejects,
      Sleeper.none
    )
    p.run(run, docs.take(4).iterator)
    assertEquals(e.vectors.putCalls.get, 2L)
  }
}
