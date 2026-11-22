package org.scholarchain.storage.emulator

import java.io.InputStream

import scala.util.control.NonFatal

import org.scholarchain.storage.ports._

/** Port decorators for the emulator suites: each call writes one result line (its outcome class by
  * [[EmulatorFailure.classify]]) and the original result or exception passes through unchanged, so
  * application code (for example `VectorReembedder`) behaves exactly as over the bare adapter.
  */
private[emulator] object Recording {
  def apply[A](rec: EmulatorRecorder, service: String, operation: String)(f: => A): A = {
    val t0 = System.nanoTime()
    def ms = (System.nanoTime() - t0) / 1000000L
    try {
      val a = f
      rec.observed(service, operation, EmulatorOutcome.ImplementedPassed, None, ms, "")
      a
    } catch {
      case NonFatal(e) =>
        val (cls, status, code) = EmulatorFailure.classify(e)
        rec.observed(service, operation, cls, status, ms, "", code)
        throw e
    }
  }
}

final class RecordingObjectStore(u: ObjectStore, rec: EmulatorRecorder) extends ObjectStore {
  def putIfAbsent(
      ref: ObjectRef,
      bytes: Array[Byte],
      contentType: String,
      metadata: Map[String, String]
  ): PutOutcome =
    Recording(rec, "s3", "PutObject")(u.putIfAbsent(ref, bytes, contentType, metadata))
  def get(ref: ObjectRef): Option[Array[Byte]] = Recording(rec, "s3", "GetObject")(u.get(ref))
  def openStream(ref: ObjectRef): Option[InputStream] =
    Recording(rec, "s3", "GetObject")(u.openStream(ref))
  def head(ref: ObjectRef): Option[ObjectInfo] = Recording(rec, "s3", "HeadObject")(u.head(ref))
  def list(bucket: String, prefix: String, maxKeys: Int): List[ObjectInfo] =
    Recording(rec, "s3", "ListObjectsV2")(u.list(bucket, prefix, maxKeys))
}

final class RecordingKeyValueStore(u: KeyValueStore, rec: EmulatorRecorder) extends KeyValueStore {
  def putIfAbsent(table: String, item: Item): ConditionalResult =
    Recording(rec, "dynamodb", "PutItem")(u.putIfAbsent(table, item))
  def putIfVersion(table: String, item: Item, expectedVersion: Long): ConditionalResult =
    Recording(rec, "dynamodb", "PutItem")(u.putIfVersion(table, item, expectedVersion))
  def get(table: String, key: ItemKey): Option[Item] =
    Recording(rec, "dynamodb", "GetItem")(u.get(table, key))
  def query(
      table: String,
      pk: String,
      skPrefix: Option[String],
      limit: Int,
      descending: Boolean
  ): List[Item] =
    Recording(rec, "dynamodb", "Query")(u.query(table, pk, skPrefix, limit, descending))
}

final class RecordingVectorIndex(u: VectorIndexPort, rec: EmulatorRecorder)
    extends VectorIndexPort {
  def describeIndex(index: String): Option[IndexDescription] =
    Recording(rec, "s3vectors", "GetIndex")(u.describeIndex(index))
  def putVectors(index: String, vectors: Seq[VectorRecord]): Unit =
    Recording(rec, "s3vectors", "PutVectors")(u.putVectors(index, vectors))
  def getVectors(index: String, keys: Seq[String], returnData: Boolean): List[VectorRecord] =
    Recording(rec, "s3vectors", "GetVectors")(u.getVectors(index, keys, returnData))
  def query(
      index: String,
      vector: Array[Float],
      topK: Int,
      filter: Option[MetadataFilter],
      returnMetadata: Boolean
  ): List[VectorHit] =
    Recording(rec, "s3vectors", "QueryVectors")(
      u.query(index, vector, topK, filter, returnMetadata)
    )
}
