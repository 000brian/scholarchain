package org.scholarchain.storage.vectors

import java.nio.charset.StandardCharsets

import scala.collection.mutable

import org.scholarchain.storage.{Retry, RetryPolicy, S3VectorsLimits, Sleeper, StoreException}
import org.scholarchain.storage.ports.{VectorIndexPort, VectorRecord}

/** Splits vectors into PutVectors calls of at most `maxCount` vectors (<= 500) and an estimated
  * request payload under `maxPayloadBytes` (< 20 MiB).
  */
object PutVectorsBatcher {

  /** Upper-bound JSON size of one vector in a request: key, 16 bytes per float32 written as a JSON
    * number, twice the metadata bytes (names, quoting, punctuation) and a fixed envelope.
    */
  def estimateBytes(v: VectorRecord): Long =
    v.key.getBytes(StandardCharsets.UTF_8).length.toLong + 16L * v.data.length +
      2L * VectorMetadataCodec.sizes(v.metadata).totalBytes + 128L

  def batches(
      vectors: Seq[VectorRecord],
      maxCount: Int,
      maxPayloadBytes: Long = S3VectorsLimits.MaxPutVectorsPayloadBytes - 1024L * 1024L
  ): List[Vector[VectorRecord]] = {
    require(
      maxCount >= 1 && maxCount <= S3VectorsLimits.MaxPutVectorsBatch,
      s"batch size $maxCount is outside 1..${S3VectorsLimits.MaxPutVectorsBatch}"
    )
    require(maxPayloadBytes < S3VectorsLimits.MaxPutVectorsPayloadBytes, "payload bound too large")
    val out = mutable.ListBuffer.empty[Vector[VectorRecord]]
    var cur = Vector.empty[VectorRecord]
    var bytes = 0L
    vectors.foreach { v =>
      val b = estimateBytes(v)
      require(b < maxPayloadBytes, s"vector ${v.key} alone exceeds the payload bound")
      if (cur.nonEmpty && (cur.size >= maxCount || bytes + b > maxPayloadBytes)) {
        out += cur
        cur = Vector.empty
        bytes = 0L
      }
      cur :+= v
      bytes += b
    }
    if (cur.nonEmpty) out += cur
    out.toList
  }
}

/** Outcome of one PutVectors call after bounded retries. */
final case class PutBatchResult(written: Int, retries: Int, failure: Option[StoreException])

/** Puts one batch with bounded exponential backoff on throttling and 5xx (transient
  * `StoreException`s). A permanent failure, or a transient one that outlives the retries, fails the
  * whole batch; the caller records every vector of it as rejected.
  */
final class VectorWriter(port: VectorIndexPort, policy: RetryPolicy, sleeper: Sleeper) {
  def put(index: String, batch: Vector[VectorRecord]): PutBatchResult = {
    val r = Retry.run(policy, sleeper)(port.putVectors(index, batch))
    r.result match {
      case Right(_) => PutBatchResult(batch.size, r.retries, None)
      case Left(e)  => PutBatchResult(0, r.retries, Some(e))
    }
  }
}
