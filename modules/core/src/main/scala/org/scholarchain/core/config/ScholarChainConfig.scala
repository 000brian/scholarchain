package org.scholarchain.core.config

import org.scholarchain.core.{Hashing, YearWindow}
import upickle.default.{macroRW, write, ReadWriter}

// Configuration contract. Every value is required and comes from a versioned configuration file
// or an environment variable (see ConfigLoader); nothing here has a default. No secrets belong in
// configuration: secrets come from AWS Secrets Manager through `storage`.

final case class AwsSettings(region: String, accountId: String)
object AwsSettings { implicit val readWriter: ReadWriter[AwsSettings] = macroRW }

/** Data-store locations. Bucket values are bare S3 bucket names; `neptuneEndpoint` is a host. */
final case class StorageSettings(
    rawBucket: String,
    curatedBucket: String,
    graphBucket: String,
    vectorBucket: String,
    vectorIndex: String,
    neptuneEndpoint: String,
    dynamoDbTable: String
) {

  /** Placeholders resolved from configuration in S3 location templates such as the dataset
    * manifest's `s3://{raw_bucket}/raw/dblp/...`.
    */
  def bucketPlaceholders: Map[String, String] = Map(
    "{raw_bucket}" -> rawBucket,
    "{curated_bucket}" -> curatedBucket,
    "{graph_bucket}" -> graphBucket,
    "{vector_bucket}" -> vectorBucket
  )

  /** Substitutes bucket placeholders. Other placeholders (for example `{yyyy-mm-dd}`) are left for
    * the caller; an unknown `{..._bucket}` placeholder is an error.
    */
  def resolveTemplate(template: String): Either[String, String] = {
    val resolved = bucketPlaceholders.foldLeft(template) { case (t, (k, v)) => t.replace(k, v) }
    val unknown = """\{[a-z0-9_]*_bucket\}""".r.findAllIn(resolved).toList.distinct
    if (unknown.isEmpty) Right(resolved)
    else Left(s"unknown bucket placeholder(s) ${unknown.mkString(", ")} in '$template'")
  }
}
object StorageSettings { implicit val readWriter: ReadWriter[StorageSettings] = macroRW }

/** Entity-resolution thresholds; `0 <= reviewThreshold <= matchThreshold <= 1`. */
final case class EntityResolutionSettings(
    policyVersion: String,
    matchThreshold: Double,
    reviewThreshold: Double
)
object EntityResolutionSettings {
  implicit val readWriter: ReadWriter[EntityResolutionSettings] = macroRW
}

/** Model identifiers (for example Amazon Bedrock model IDs). */
final case class ModelSettings(embeddingModelId: String, answerModelId: String)
object ModelSettings { implicit val readWriter: ReadWriter[ModelSettings] = macroRW }

final case class NamedWindow(label: String, startYear: Int, endYear: Int) {
  def window: YearWindow = YearWindow(startYear, endYear)
}
object NamedWindow { implicit val readWriter: ReadWriter[NamedWindow] = macroRW }

/** Graph windows analysed by Spark and the minimum edge weight kept in the graph. */
final case class GraphSettings(windows: List[NamedWindow], minEdgeWeight: Double)
object GraphSettings { implicit val readWriter: ReadWriter[GraphSettings] = macroRW }

/** Bridge-detection algorithm parameters. */
final case class BridgeSettings(algorithm: String, sampleSize: Int, randomSeed: Int, topK: Int)
object BridgeSettings { implicit val readWriter: ReadWriter[BridgeSettings] = macroRW }

final case class ScholarChainConfig(
    configVersion: Int,
    environment: String,
    aws: AwsSettings,
    storage: StorageSettings,
    entityResolution: EntityResolutionSettings,
    models: ModelSettings,
    graph: GraphSettings,
    bridge: BridgeSettings
) {

  /** `sha256:<hex>` of the compact JSON of the resolved configuration. Use it as the
    * `configFingerprint` input of `RunId.of`.
    */
  def fingerprint: String = "sha256:" + Hashing.sha256Hex(write(this))
}
object ScholarChainConfig {
  implicit val readWriter: ReadWriter[ScholarChainConfig] = macroRW
}
