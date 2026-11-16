package org.scholarchain.storage

import scala.collection.immutable.ListMap

import org.scholarchain.core.RunId
import org.scholarchain.core.config._

/** The parts of [[ScholarChainConfig]] storage needs. Every value comes from configuration; there
  * are no defaults for buckets, tables, endpoints or Regions.
  */
final case class StorageConfig(
    region: String,
    zones: ListMap[String, S3Location],
    vectorBucket: String,
    neptuneEndpoint: String,
    dynamoDb: DynamoDbSettings,
    vectors: VectorSettings,
    embedding: EmbeddingSettings,
    neptune: Option[NeptuneSettings],
    windows: List[NamedWindow],
    fingerprint: String,
    runtime: Option[RuntimeSettings] = None
) {

  /** The configured `graph.windows` labels (the only window labels storage accepts). */
  def windowLabels: List[String] = windows.map(_.label)

  /** The `runtime` section, or Left when it is not configured. */
  def requireRuntime: Either[String, RuntimeSettings] =
    runtime.toRight("runtime section is not configured")

  def tableNames: Set[String] = Set(
    dynamoDb.runLedgerTable,
    dynamoDb.entityLinkCacheTable,
    dynamoDb.querySessionTable,
    dynamoDb.evidenceBundleTable,
    dynamoDb.artifactRegistryTable,
    dynamoDb.researcherMetricCacheTable
  )
  def retryPolicy: RetryPolicy = RetryPolicy.fromVectorSettings(vectors)
}

object StorageConfig {

  def from(c: ScholarChainConfig): Either[List[String], StorageConfig] = {
    val missing = List(
      Option.when(c.objectStore.isEmpty)("objectStore section is not configured"),
      Option.when(c.dynamoDb.isEmpty)("dynamoDb section is not configured"),
      Option.when(c.vectors.isEmpty)("vectors section is not configured"),
      Option.when(c.embedding.isEmpty)("embedding section is not configured")
    ).flatten
    if (missing.nonEmpty) Left(missing)
    else
      Right(
        StorageConfig(
          c.aws.region,
          c.s3Zones.get,
          c.storage.vectorBucket,
          c.storage.neptuneEndpoint,
          c.dynamoDb.get,
          c.vectors.get,
          c.embedding.get,
          c.neptune,
          c.graph.windows,
          c.fingerprint,
          c.runtime
        )
      )
  }
}

/** Storage's code version, an input of storage run IDs. */
object StorageVersion {
  val Code = "storage/1"

  /** Run ID of a storage job over one input (for example the export manifest's sha256). */
  def runId(jobName: String, inputFingerprint: String, configFingerprint: String): RunId =
    RunId
      .of(jobName, Code, inputFingerprint, configFingerprint)
      .fold(e => throw new IllegalArgumentException(e), identity)
}
