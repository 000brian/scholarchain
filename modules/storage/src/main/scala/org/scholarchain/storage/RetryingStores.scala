package org.scholarchain.storage

import java.io.InputStream
import java.util.concurrent.atomic.AtomicLong

import org.scholarchain.storage.ports._

/** Calls and retries made through one retrying decorator. With SDK `maxAttempts = 1` every call
  * here is exactly one SDK request, so `calls` and `retries` are the real request counts (m2).
  */
final class RetryCounters {
  val calls = new AtomicLong()
  val retries = new AtomicLong()
  def toJson: ujson.Obj =
    ujson.Obj("calls" -> calls.get.toDouble, "retries" -> retries.get.toDouble)
}

private[storage] final class Retrier(policy: RetryPolicy, sleeper: Sleeper) {
  val counters = new RetryCounters

  def apply[A](op: => A): A = {
    val r = Retry.run(policy, sleeper) { counters.calls.incrementAndGet(); op }
    counters.retries.addAndGet(r.retries.toLong)
    r.result.fold(e => throw e, identity)
  }
}

/** DynamoDB tables with storage's bounded retry on transient failures (throttling, 5xx, timeouts).
  * Conditional outcomes are results, not failures, so they are never retried. A retried
  * `putIfAbsent` whose first attempt was applied server-side sees `ConditionFailed`; the
  * [[org.scholarchain.storage.dynamo.ConditionalWriter]] then reads the item and reports
  * `Unchanged` (same hash) or `Conflict`.
  */
final class RetryingKeyValueStore(underlying: KeyValueStore, policy: RetryPolicy, sleeper: Sleeper)
    extends KeyValueStore {
  private val retry = new Retrier(policy, sleeper)
  def counters: RetryCounters = retry.counters

  def putIfAbsent(table: String, item: Item): ConditionalResult =
    retry(underlying.putIfAbsent(table, item))
  def putIfVersion(table: String, item: Item, expectedVersion: Long): ConditionalResult =
    retry(underlying.putIfVersion(table, item, expectedVersion))
  def get(table: String, key: ItemKey): Option[Item] = retry(underlying.get(table, key))
  def query(
      table: String,
      pk: String,
      skPrefix: Option[String],
      limit: Int,
      descending: Boolean
  ): List[Item] = retry(underlying.query(table, pk, skPrefix, limit, descending))
}

/** Ordinary S3 with storage's bounded retry. A retried put whose first attempt was applied sees the
  * S3 conditional-write 412 and reports `AlreadyPresent` (same sha256).
  */
final class RetryingObjectStore(underlying: ObjectStore, policy: RetryPolicy, sleeper: Sleeper)
    extends ObjectStore {
  private val retry = new Retrier(policy, sleeper)
  def counters: RetryCounters = retry.counters

  def putIfAbsent(
      ref: ObjectRef,
      bytes: Array[Byte],
      contentType: String,
      metadata: Map[String, String]
  ): PutOutcome = retry(underlying.putIfAbsent(ref, bytes, contentType, metadata))
  def get(ref: ObjectRef): Option[Array[Byte]] = retry(underlying.get(ref))
  def openStream(ref: ObjectRef): Option[InputStream] = retry(underlying.openStream(ref))
  def head(ref: ObjectRef): Option[ObjectInfo] = retry(underlying.head(ref))
  def list(bucket: String, prefix: String, maxKeys: Int): List[ObjectInfo] =
    retry(underlying.list(bucket, prefix, maxKeys))
}

/** S3 Vectors with storage's bounded retry on reads (describe, get, query). `putVectors` passes
  * through unretried: the ingest pipeline's `PutVectorsBatcher` already retries each batch with the
  * same policy and records the retry counts in RunLedger, and retrying here too would hide retries
  * inside retries.
  */
final class RetryingVectorIndex(underlying: VectorIndexPort, policy: RetryPolicy, sleeper: Sleeper)
    extends VectorIndexPort {
  private val retry = new Retrier(policy, sleeper)
  def counters: RetryCounters = retry.counters

  def describeIndex(index: String): Option[IndexDescription] =
    retry(underlying.describeIndex(index))
  def putVectors(index: String, vectors: Seq[VectorRecord]): Unit =
    underlying.putVectors(index, vectors)
  def getVectors(index: String, keys: Seq[String], returnData: Boolean): List[VectorRecord] =
    retry(underlying.getVectors(index, keys, returnData))
  def query(
      index: String,
      vector: Array[Float],
      topK: Int,
      filter: Option[MetadataFilter],
      returnMetadata: Boolean
  ): List[VectorHit] = retry(underlying.query(index, vector, topK, filter, returnMetadata))
}
