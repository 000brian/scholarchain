package org.scholarchain.rag.embedding

import org.scholarchain.core.config.{EmbeddingProvider, ScholarChainConfig}

/** Which side of retrieval a vector is for. Voyage embeds documents and queries differently
  * (`input_type`); Titan V2 and the fake embedder ignore it, but it is still part of the spec so
  * the stored model ID always says how the vector was produced.
  */
sealed trait InputType { def wire: String }
object InputType {
  case object Document extends InputType { val wire = "document" }
  case object Query extends InputType { val wire = "query" }
}

/** The embedding model spec stored (as [[id]]) in every vector's metadata
  * (`VectorMetadata.embeddingModel`).
  *
  * `id` = `<provider>/<model>/<dimensions>/<inputType>/<specVersion>`, for example
  * `voyage/voyage-4/1024/document/emb-v1`. Every field that changes the vector of a given text is
  * in the ID, so changing any of them changes the ID, and storage's ingest pipeline then re-embeds
  * every key (ArtifactRegistry holds the previous ID) and the index is rebuilt in place. A change
  * of request parameters that is not a field (Voyage `truncation`, `output_dtype`; Titan
  * `normalize`) bumps [[EmbeddingModelSpec.SpecVersion]].
  *
  * Stored vectors are always document-side ([[InputType.Document]]); a query vector is compatible
  * with a stored vector exactly when the query spec's [[documentId]] equals the stored ID.
  */
final case class EmbeddingModelSpec(
    provider: EmbeddingProvider,
    model: String,
    dimensions: Int,
    inputType: InputType,
    specVersion: String
) {
  def id: String = s"${provider.wire}/$model/$dimensions/${inputType.wire}/$specVersion"

  /** The ID of the document-side spec this spec pairs with (equal to [[id]] for documents). */
  def documentId: String = copy(inputType = InputType.Document).id

  def forInput(t: InputType): EmbeddingModelSpec = copy(inputType = t)

  /** Whether a vector stored under `storedModelId` may be compared with vectors of this spec. */
  def compatibleWithStored(storedModelId: String): Boolean = storedModelId == documentId

  /** The largest input this model accepts, in tokens (provider documentation, decision 8). */
  def maxInputTokens: Int = EmbeddingModelSpec.maxInputTokens(provider)
}

object EmbeddingModelSpec {

  /** Version of the request shape. Bump when request parameters that change vectors change. */
  val SpecVersion = "emb-v1"

  val VoyageDefaultModel = "voyage-4"
  val BedrockTitanModel = "amazon.titan-embed-text-v2:0"
  val FakeModel = "fake-lexical-hash-v1"

  /** Allowed output dimensions per provider (decision 8). */
  val VoyageDimensions: Set[Int] = Set(256, 512, 1024, 2048)
  val TitanDimensions: Set[Int] = Set(256, 512, 1024)

  /** Input limits (decision 8): voyage-4 32,000 tokens; Titan Text Embeddings V2 8,192 tokens (or
    * 50,000 characters); the fake embedder is held to the Titan limit so local runs never accept
    * text a real provider would refuse.
    */
  def maxInputTokens(p: EmbeddingProvider): Int = p match {
    case EmbeddingProvider.Voyage  => 32000
    case EmbeddingProvider.Bedrock => 8192
    case EmbeddingProvider.Fake    => 8192
  }

  /** Validates a provider, model and dimension combination and builds the spec. */
  def of(
      provider: EmbeddingProvider,
      model: String,
      dimensions: Int,
      inputType: InputType
  ): Either[String, EmbeddingModelSpec] = {
    val problems: List[String] = provider match {
      case EmbeddingProvider.Voyage =>
        List(
          Option.when(!model.matches("voyage-[a-z0-9.-]+"))(
            s"voyage model '$model' must look like voyage-<name>"
          ),
          Option.when(!VoyageDimensions.contains(dimensions))(
            s"voyage dimensions $dimensions must be one of ${VoyageDimensions.toList.sorted.mkString(", ")}"
          )
        ).flatten
      case EmbeddingProvider.Bedrock =>
        List(
          Option.when(model != BedrockTitanModel)(
            s"bedrock model '$model' is not supported (only $BedrockTitanModel)"
          ),
          Option.when(!TitanDimensions.contains(dimensions))(
            s"bedrock dimensions $dimensions must be one of ${TitanDimensions.toList.sorted.mkString(", ")}"
          )
        ).flatten
      case EmbeddingProvider.Fake =>
        List(
          Option.when(!model.matches("fake-[a-z0-9.-]+"))(
            s"fake model '$model' must look like fake-<name> so it is never mistaken for a real model"
          ),
          Option.when(dimensions < 1 || dimensions > 4096)(s"dimensions $dimensions not in 1..4096")
        ).flatten
    }
    if (problems.nonEmpty) Left(problems.mkString("; "))
    else Right(EmbeddingModelSpec(provider, model, dimensions, inputType, SpecVersion))
  }

  /** The spec from configuration: `embedding.provider`, `models.embeddingModelId` and
    * `embedding.dimensions`.
    */
  def fromConfig(c: ScholarChainConfig, inputType: InputType): Either[String, EmbeddingModelSpec] =
    for {
      e <- c.embedding.toRight("embedding section is not configured")
      p <- e.providerKind
      s <- of(p, c.models.embeddingModelId, e.dimensions, inputType)
    } yield s
}
