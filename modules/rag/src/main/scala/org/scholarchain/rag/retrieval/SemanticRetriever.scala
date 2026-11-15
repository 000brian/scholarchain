package org.scholarchain.rag.retrieval

import scala.util.control.NonFatal

import org.scholarchain.core.SemanticDocumentType
import org.scholarchain.core.config.VectorSettings
import org.scholarchain.rag.embedding.{EmbeddingModelSpec, InputType}
import org.scholarchain.storage.ports.{EmbeddingPort, MetadataFilter, VectorIndexPort}
import org.scholarchain.storage.vectors.VectorMetadataCodec

/** Result of one semantic query: the compact context plus what was dropped and why. */
final case class RetrievalResult(
    context: SemanticContext,
    droppedIncompatibleModel: Int,
    droppedMalformedMetadata: List[String]
)

/** Semantic retrieval over one S3 Vectors index through storage's [[VectorIndexPort]].
  *
  * Bounds: query text at most [[SemanticRetriever.MaxQueryChars]] characters; topK in 1..
  * `vectors.queryTopK` (configuration). The query is embedded with the query-side spec; a hit whose
  * stored `embeddingModel` is not that spec's document-side ID is dropped and counted (stored and
  * query vectors must come from the same model and configuration). Hits whose metadata does not
  * decode are dropped and reported, never repaired. The result carries no text and no graph facts.
  */
final class SemanticRetriever(
    queryEmbedder: EmbeddingPort,
    querySpec: EmbeddingModelSpec,
    vectors: VectorIndexPort,
    settings: VectorSettings
) {
  require(querySpec.inputType == InputType.Query, "the retriever needs a query-side model spec")
  require(queryEmbedder.modelId == querySpec.id, "embedder and spec disagree")

  def retrieve(
      documentType: SemanticDocumentType,
      queryText: String,
      topK: Int,
      filter: Option[MetadataFilter] = None
  ): Either[String, RetrievalResult] = {
    val index = settings.indexFor(documentType)
    for {
      _ <- Either.cond(queryText.trim.nonEmpty, (), "query text is blank")
      _ <- Either.cond(
        queryText.length <= SemanticRetriever.MaxQueryChars,
        (),
        s"query text exceeds ${SemanticRetriever.MaxQueryChars} characters"
      )
      _ <- Either.cond(
        topK >= 1 && topK <= settings.queryTopK,
        (),
        s"topK $topK must be in 1..${settings.queryTopK}"
      )
      batch <- queryEmbedder
        .embed(Vector(queryText))
        .left
        .map(f => s"query embedding failed: ${f.message}")
      vector <- batch.vectors.headOption
        .filter(_.length == querySpec.dimensions)
        .toRight("query embedding has the wrong shape")
      hits <-
        try Right(vectors.query(index, vector, topK, filter, returnMetadata = true))
        catch { case NonFatal(e) => Left(s"vector query failed: ${e.getMessage}") }
    } yield {
      val decoded = hits.map { h =>
        h.metadata
          .toRight(List("no metadata returned"))
          .flatMap(VectorMetadataCodec.decode(h.key, index, _))
          .left
          .map(errs => s"${h.key}: ${errs.mkString("; ")}")
          .map(m => (m, 1.0 - h.distance))
      }
      val ok = decoded.collect { case Right(x) => x }
      val (compatible, incompatible) = ok.partition { case (m, _) =>
        querySpec.compatibleWithStored(m.embeddingModel)
      }
      RetrievalResult(
        SemanticContext(
          QueryInfo(
            index,
            querySpec.id,
            topK,
            filter.map(MetadataFilter.keys).getOrElse(Set.empty).toList.sorted
          ),
          compatible.map { case (m, s) => SemanticContext.entry(m, s) }
        ),
        incompatible.size,
        decoded.collect { case Left(e) => e }
      )
    }
  }
}

object SemanticRetriever {
  val MaxQueryChars = 2000
}
