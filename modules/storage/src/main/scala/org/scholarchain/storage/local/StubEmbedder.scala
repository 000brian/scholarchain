package org.scholarchain.storage.local

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

import org.scholarchain.storage.ports.{EmbeddingBatch, EmbeddingFailure, EmbeddingPort}

/** A trivial deterministic embedder for storage tests and the storage local run: the vector of a
  * text is derived from SHA-256 of the text (counter mode), L2-normalized. It has no semantics at
  * all (equal texts give equal vectors, different texts unrelated ones) and must never fill a
  * stored index outside local runs. rag provides the real embedders and its own lexical `fake`.
  */
final class StubEmbedder(val modelId: String, val dimensions: Int, val maxBatchSize: Int)
    extends EmbeddingPort {
  require(dimensions >= 1 && dimensions <= 4096, "dimensions must be in 1..4096")
  private val callCount = new AtomicLong()
  private val textCount = new AtomicLong()

  /** Calls and texts so far; exact under concurrent callers (the ingest embeds in parallel). */
  def calls: Long = callCount.get
  def texts: Long = textCount.get

  def embed(batch: IndexedSeq[String]): Either[EmbeddingFailure, EmbeddingBatch] = {
    if (batch.size > maxBatchSize)
      Left(EmbeddingFailure(s"batch of ${batch.size} exceeds $maxBatchSize", 0))
    else {
      callCount.incrementAndGet()
      textCount.addAndGet(batch.size.toLong)
      Right(EmbeddingBatch(batch.map(StubEmbedder.vector(_, dimensions)), 0))
    }
  }
}

object StubEmbedder {
  def vector(text: String, dimensions: Int): Array[Float] = {
    val seed = text.getBytes(StandardCharsets.UTF_8)
    val out = new Array[Float](dimensions)
    var i = 0
    var block = 0
    while (i < dimensions) {
      val md = MessageDigest.getInstance("SHA-256")
      md.update(seed)
      md.update(BigInt(block).toByteArray)
      val d = md.digest()
      var j = 0
      while (j + 1 < d.length && i < dimensions) {
        val v = ((d(j) << 8) | (d(j + 1) & 0xff)).toShort
        out(i) = v / 32768.0f
        i += 1
        j += 2
      }
      block += 1
    }
    val norm = math.sqrt(out.map(x => x.toDouble * x).sum).max(1e-12)
    out.map(x => (x / norm).toFloat)
  }
}
