package org.scholarchain.storage

import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}

import org.scholarchain.core._
import org.scholarchain.storage.local.StubEmbedder
import org.scholarchain.storage.ports._

/** Counting and fault-injecting wrappers for the ingest and re-embed tests (G-27, Phase 15). */
object IngestFakes {
  import TestFixtures._

  /** A researcher-profile document number `i` (unique key, realistic metadata once ingested). */
  def profileDoc(i: Int, suffix: String = ""): SemanticDocument = {
    val r = ResearcherId.fromAnchorMention(MentionId.of(WorkId.fromDblpKey(s"conf/x/K$i"), 0))
    val e = summary(
      EvidenceType.Metric,
      List(r.value),
      s"Researcher number $i (${r.value}), window 2015-2025. Publications P=2.$suffix",
      Some(MetricValue(CollaborationMetric.PublicationCount, 2))
    )
    SummaryDocuments.build("researcher_profile", Full, e, storageConfig.vectors).toOption.get
  }

  /** Calls in flight now and the most ever seen at once. */
  final class InFlight(delayMillis: Long) {
    private val now = new AtomicInteger()
    val max = new AtomicInteger()
    def apply[A](f: => A): A = {
      val n = now.incrementAndGet()
      max.accumulateAndGet(n, (a, b) => math.max(a, b))
      try {
        if (delayMillis > 0) Thread.sleep(delayMillis)
        f
      } finally { now.decrementAndGet(); () }
    }
  }

  final class CountingObjectStore(u: ObjectStore, inFlight: InFlight = new InFlight(0))
      extends ObjectStore {
    val calls = new AtomicLong()
    def putIfAbsent(
        ref: ObjectRef,
        bytes: Array[Byte],
        contentType: String,
        metadata: Map[String, String]
    ): PutOutcome = {
      calls.incrementAndGet(); inFlight(u.putIfAbsent(ref, bytes, contentType, metadata))
    }
    def get(ref: ObjectRef): Option[Array[Byte]] = { calls.incrementAndGet(); u.get(ref) }
    def openStream(ref: ObjectRef): Option[InputStream] = {
      calls.incrementAndGet(); u.openStream(ref)
    }
    def head(ref: ObjectRef): Option[ObjectInfo] = { calls.incrementAndGet(); u.head(ref) }
    def list(bucket: String, prefix: String, maxKeys: Int): List[ObjectInfo] = {
      calls.incrementAndGet(); u.list(bucket, prefix, maxKeys)
    }
  }

  /** Counts calls per table; `failPutsOn(table, n, transient)` makes the next n puts on a table
    * throw.
    */
  final class CountingKv(u: KeyValueStore, inFlight: InFlight = new InFlight(0))
      extends KeyValueStore {
    private val perTable = new ConcurrentHashMap[String, AtomicLong]()
    private val failing = new ConcurrentHashMap[String, AtomicInteger]()
    @volatile private var failTransient = true
    def calls(table: String): Long = Option(perTable.get(table)).map(_.get).getOrElse(0L)
    private def count(t: String): Unit =
      perTable.computeIfAbsent(t, _ => new AtomicLong()).incrementAndGet()
    def failPutsOn(table: String, n: Int, transient: Boolean): Unit = {
      failTransient = transient
      failing.put(table, new AtomicInteger(n))
    }
    private def maybeFail(t: String): Unit =
      Option(failing.get(t)).foreach { c =>
        if (c.getAndDecrement() > 0)
          throw new StoreException("InternalServerError: injected", failTransient)
      }
    def putIfAbsent(table: String, item: Item): ConditionalResult = {
      count(table); maybeFail(table); inFlight(u.putIfAbsent(table, item))
    }
    def putIfVersion(table: String, item: Item, expectedVersion: Long): ConditionalResult = {
      count(table); maybeFail(table); inFlight(u.putIfVersion(table, item, expectedVersion))
    }
    def get(table: String, key: ItemKey): Option[Item] = {
      count(table); inFlight(u.get(table, key))
    }
    def query(
        table: String,
        pk: String,
        skPrefix: Option[String],
        limit: Int,
        descending: Boolean
    ): List[Item] = { count(table); inFlight(u.query(table, pk, skPrefix, limit, descending)) }
  }

  /** Counts GetVectors calls and keys; can drop keys from responses and fail the next n reads or
    * puts.
    */
  final class CountingVectors(u: VectorIndexPort) extends VectorIndexPort {
    val getCalls = new AtomicLong()
    val keysPerGet = new java.util.concurrent.ConcurrentLinkedQueue[Integer]()
    val putCalls = new AtomicLong()
    @volatile var drop: Set[String] = Set.empty
    private val failGets = new AtomicInteger()
    @volatile private var getTransient = true
    private val failPuts = new AtomicInteger()
    @volatile private var putTransient = true
    def failNextGets(n: Int, transient: Boolean): Unit = {
      getTransient = transient; failGets.set(n)
    }
    def failNextPuts(n: Int, transient: Boolean): Unit = {
      putTransient = transient; failPuts.set(n)
    }
    def describeIndex(index: String): Option[IndexDescription] = u.describeIndex(index)
    def putVectors(index: String, vectors: Seq[VectorRecord]): Unit = {
      putCalls.incrementAndGet()
      if (failPuts.getAndDecrement() > 0)
        throw new StoreException("ServiceUnavailableException: injected", putTransient)
      u.putVectors(index, vectors)
    }
    def getVectors(index: String, keys: Seq[String], returnData: Boolean): List[VectorRecord] = {
      getCalls.incrementAndGet()
      keysPerGet.add(keys.size)
      if (failGets.getAndDecrement() > 0)
        throw new StoreException("TooManyRequestsException: injected", getTransient)
      u.getVectors(index, keys, returnData).filterNot(v => drop(v.key))
    }
    def query(
        index: String,
        vector: Array[Float],
        topK: Int,
        filter: Option[MetadataFilter],
        returnMetadata: Boolean
    ): List[VectorHit] = u.query(index, vector, topK, filter, returnMetadata)
  }

  /** Same model ID as the stub, but different vectors (negated): a re-embed visibly changes data.
    */
  final class FlipEmbedder(
      val modelId: String = "storage-stub-sha256-v1",
      val maxBatchSize: Int = 8,
      beforeEmbed: () => Unit = () => ()
  ) extends EmbeddingPort {
    val dimensions = 1024
    val calls = new AtomicLong()
    val texts = new AtomicLong()
    @volatile var fail = false
    def embed(ts: IndexedSeq[String]): Either[EmbeddingFailure, EmbeddingBatch] = {
      calls.incrementAndGet()
      texts.addAndGet(ts.size.toLong)
      beforeEmbed()
      if (fail) Left(EmbeddingFailure("provider 503", 5))
      else Right(EmbeddingBatch(ts.map(FlipEmbedder.vector), 0))
    }
  }
  object FlipEmbedder {
    def vector(text: String): Array[Float] = StubEmbedder.vector(text, 1024).map(x => -x)
  }
}
