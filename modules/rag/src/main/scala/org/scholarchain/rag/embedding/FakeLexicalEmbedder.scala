package org.scholarchain.rag.embedding

import java.nio.charset.StandardCharsets

import org.scholarchain.core.config.EmbeddingProvider
import org.scholarchain.storage.ports.{EmbeddingBatch, EmbeddingFailure, EmbeddingPort}

/** The `fake` provider: a deterministic lexical embedder for local runs and tests only.
  *
  * Tokens are maximal runs of letters and digits, lower-cased (Unicode-aware). Each token is hashed
  * with 64-bit FNV-1a into one of `dimensions` buckets with a sign bit (feature hashing); the
  * weight of a token is `1 + ln(term frequency)`; the vector is L2-normalized. Similar vectors mean
  * shared words, nothing more: it is NOT semantic, and its results only show that the plumbing
  * (rendering, ingest, query, text pointer) works. It must never fill a stored index outside tests;
  * the ingest job refuses it for anything but local in-memory stores.
  */
final class FakeLexicalEmbedder(val spec: EmbeddingModelSpec, val maxBatchSize: Int)
    extends EmbeddingPort {
  require(spec.provider == EmbeddingProvider.Fake, "FakeLexicalEmbedder needs a fake spec")
  require(maxBatchSize > 0, "maxBatchSize must be > 0")
  val usage = new EmbeddingUsage

  def modelId: String = spec.id
  def dimensions: Int = spec.dimensions

  def embed(texts: IndexedSeq[String]): Either[EmbeddingFailure, EmbeddingBatch] =
    if (texts.size > maxBatchSize)
      Left(EmbeddingFailure(s"batch of ${texts.size} exceeds $maxBatchSize", 0))
    else {
      usage.requests += 1
      usage.texts += texts.size
      usage.characters += texts.map(_.length.toLong).sum
      Right(EmbeddingBatch(texts.map(FakeLexicalEmbedder.vector(_, dimensions)), 0))
    }
}

object FakeLexicalEmbedder {

  /** Lower-cased letter-or-digit runs, in order. */
  def tokens(text: String): Vector[String] = {
    val out = Vector.newBuilder[String]
    val sb = new java.lang.StringBuilder
    var i = 0
    while (i < text.length) {
      val cp = text.codePointAt(i)
      if (Character.isLetterOrDigit(cp)) sb.appendCodePoint(Character.toLowerCase(cp))
      else if (sb.length > 0) { out += sb.toString; sb.setLength(0) }
      i += Character.charCount(cp)
    }
    if (sb.length > 0) out += sb.toString
    out.result()
  }

  private def fnv1a64(s: String): Long = {
    var h = 0xcbf29ce484222325L
    val bytes = s.getBytes(StandardCharsets.UTF_8)
    var i = 0
    while (i < bytes.length) {
      h ^= (bytes(i) & 0xff).toLong
      h *= 0x100000001b3L
      i += 1
    }
    h
  }

  def vector(text: String, dimensions: Int): Array[Float] = {
    val tf = tokens(text).groupMapReduce(identity)(_ => 1)(_ + _)
    val acc = new Array[Double](dimensions)
    tf.foreach { case (t, n) =>
      val h = fnv1a64(t)
      val bucket = java.lang.Long.remainderUnsigned(h >>> 1, dimensions.toLong).toInt
      val sign = if ((h & 1L) == 0L) 1.0 else -1.0
      acc(bucket) += sign * (1.0 + math.log(n.toDouble))
    }
    val norm = math.sqrt(acc.map(x => x * x).sum)
    if (norm == 0.0) {
      // A text without letters or digits still gets a valid unit vector (bucket 0).
      val v = new Array[Float](dimensions)
      v(0) = 1.0f
      v
    } else acc.map(x => (x / norm).toFloat)
  }
}
