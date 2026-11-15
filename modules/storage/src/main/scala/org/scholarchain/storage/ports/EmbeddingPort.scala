package org.scholarchain.storage.ports

/** Vectors for one batch of texts, in input order, and the retries the provider call used. */
final case class EmbeddingBatch(vectors: IndexedSeq[Array[Float]], retries: Int)

/** A failed embedding call after the provider's bounded retries. */
final case class EmbeddingFailure(message: String, retries: Int)

/** The embedding port: texts in, vectors out, plus the model ID and dimensions.
  *
  * Defined by storage, implemented by rag (Voyage, Bedrock, and the `fake` lexical embedder);
  * storage tests use a trivial stub. Contract:
  *   - `modelId` is stored verbatim in every vector's metadata (`VectorMetadata.embeddingModel`):
  *     non-empty, no whitespace, at most 1024 characters. It must change whenever the vectors for
  *     the same text would change (provider, model, dimensions, input type or version), because the
  *     ingest pipeline re-embeds exactly when `modelId` or `dimensions` differ from the registered
  *     ones.
  *   - `dimensions` is in 1..4096 and equals the target index dimension.
  *   - `maxBatchSize` (> 0) is the most texts one `embed` call accepts; the pipeline never sends
  *     more.
  *   - `embed` returns exactly one vector per input text, in order, each of length `dimensions`
  *     with finite values; the pipeline rejects a batch that breaks this. Provider throttling and
  *     5xx retries happen inside `embed` (bounded by the embedding settings) and are reported in
  *     `retries`; they are recorded in RunLedger.
  *   - Implementations never log texts or API keys.
  */
trait EmbeddingPort {
  def modelId: String
  def dimensions: Int
  def maxBatchSize: Int
  def embed(texts: IndexedSeq[String]): Either[EmbeddingFailure, EmbeddingBatch]
}
