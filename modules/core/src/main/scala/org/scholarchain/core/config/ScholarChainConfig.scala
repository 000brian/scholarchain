package org.scholarchain.core.config

import scala.collection.immutable.ListMap

import org.scholarchain.core.{
  Hashing,
  SemanticDocumentType,
  WireEnum,
  WireEnumCompanion,
  YearWindow
}
import upickle.default.{macroRW, write, ReadWriter}

// Configuration contract. Every value comes from a versioned configuration file or an
// environment variable (see ConfigLoader); nothing here has a default value. The Phase 7
// sections are optional as a whole (absent = None), never defaulted key by key. No secrets belong
// in configuration: it holds only the NAMES of environment variables that carry API keys, and
// AWS secrets come from AWS Secrets Manager through `storage`.

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

// ---- Optional sections (Phase 7, additive) ---------------------------------------------------
//
// Each optional section is absent (None) unless at least one of its keys is set in the file or
// in the environment; once any key of a section is set, every required key of that section must
// be set (no defaults). Values that already existed before Phase 7 are not repeated here:
//   - S3 buckets of the raw, curated and graph zones: storage.rawBucket, storage.curatedBucket,
//     storage.graphBucket;
//   - S3 Vectors bucket: storage.vectorBucket;
//   - Neptune endpoint host: storage.neptuneEndpoint;
//   - embedding model ID: models.embeddingModelId.

/** One S3 location: a bucket and a key prefix (no leading or trailing `/`). */
final case class S3Location(bucket: String, prefix: String) {
  def uri: String = s"s3://$bucket/$prefix/"
}
object S3Location { implicit val readWriter: ReadWriter[S3Location] = macroRW }

/** Ordinary S3 zones (section `objectStore`). Raw, curated and graph objects live in the existing
  * `storage.rawBucket`, `storage.curatedBucket` and `storage.graphBucket`; semantic-context,
  * reports and logs objects live in the buckets named here (they may be the same bucket). Prefixes
  * match `[a-z0-9][a-z0-9._=-]*(/[a-z0-9][a-z0-9._=-]*)*`; the six (bucket, prefix) locations are
  * distinct.
  */
final case class ObjectStoreSettings(
    rawPrefix: String,
    curatedPrefix: String,
    graphPrefix: String,
    semanticContextPrefix: String,
    reportsPrefix: String,
    logsPrefix: String,
    semanticContextBucket: String,
    reportsBucket: String,
    logsBucket: String
) {

  /** Zone name -> location, in the order raw, curated, graph, semantic-context, reports, logs. */
  def zones(storage: StorageSettings): ListMap[String, S3Location] = ListMap(
    "raw" -> S3Location(storage.rawBucket, rawPrefix),
    "curated" -> S3Location(storage.curatedBucket, curatedPrefix),
    "graph" -> S3Location(storage.graphBucket, graphPrefix),
    "semantic-context" -> S3Location(semanticContextBucket, semanticContextPrefix),
    "reports" -> S3Location(reportsBucket, reportsPrefix),
    "logs" -> S3Location(logsBucket, logsPrefix)
  )
}
object ObjectStoreSettings { implicit val readWriter: ReadWriter[ObjectStoreSettings] = macroRW }

/** DynamoDB table names (section `dynamoDb`): valid, distinct table names. */
final case class DynamoDbSettings(
    runLedgerTable: String,
    entityLinkCacheTable: String,
    querySessionTable: String,
    evidenceBundleTable: String,
    artifactRegistryTable: String,
    researcherMetricCacheTable: String
)
object DynamoDbSettings { implicit val readWriter: ReadWriter[DynamoDbSettings] = macroRW }

/** Neptune connection settings (section `neptune`); the host is `storage.neptuneEndpoint`.
  *
  * @param port
  *   1..65535
  * @param queryTimeoutMillis
  *   per-query timeout, > 0
  * @param mergeRowCap
  *   largest number of rows a parameterized MERGE upsert may write in one call, > 0 (larger loads
  *   use the bulk loader)
  */
final case class NeptuneSettings(port: Int, queryTimeoutMillis: Int, mergeRowCap: Int)
object NeptuneSettings { implicit val readWriter: ReadWriter[NeptuneSettings] = macroRW }

/** S3 Vectors settings (section `vectors`); the vector bucket is `storage.vectorBucket`.
  *
  * Index names match `VectorIndexName.Pattern` and are distinct. `putBatchSize` is 1..500 vectors
  * per PutVectors call; `maxRetries` (>= 0) bounds retries of throttled and 5xx calls with
  * exponential backoff from `retryBaseDelayMillis` (> 0) capped at `retryMaxDelayMillis` (>= base);
  * `queryTopK` (> 0) is the number of neighbours requested per query.
  */
final case class VectorSettings(
    researcherProfileIndex: String,
    collaborationCommunityIndex: String,
    paperAbstractIndex: String,
    collaborationPathIndex: String,
    topicInstitutionSummaryIndex: String,
    putBatchSize: Int,
    maxRetries: Int,
    retryBaseDelayMillis: Int,
    retryMaxDelayMillis: Int,
    queryTopK: Int
) {

  /** The configured index of each document type. */
  def indexFor(documentType: SemanticDocumentType): String = documentType match {
    case SemanticDocumentType.ResearcherProfile       => researcherProfileIndex
    case SemanticDocumentType.CommunitySummary        => collaborationCommunityIndex
    case SemanticDocumentType.PaperSummary            => paperAbstractIndex
    case SemanticDocumentType.CollaborationPath       => collaborationPathIndex
    case SemanticDocumentType.TopicInstitutionSummary => topicInstitutionSummaryIndex
  }

  /** The five index names in the order of [[SemanticDocumentType.values]]. */
  def indexNames: List[String] = SemanticDocumentType.values.map(indexFor)
}
object VectorSettings { implicit val readWriter: ReadWriter[VectorSettings] = macroRW }

/** Embedding provider named by `embedding.provider`. */
sealed trait EmbeddingProvider extends WireEnum
object EmbeddingProvider extends WireEnumCompanion[EmbeddingProvider]("embedding provider") {
  case object Voyage extends EmbeddingProvider { val wire = "voyage" }
  case object Bedrock extends EmbeddingProvider { val wire = "bedrock" }

  /** Deterministic local embedder; for tests and local runs only. */
  case object Fake extends EmbeddingProvider { val wire = "fake" }

  val values: List[EmbeddingProvider] = List(Voyage, Bedrock, Fake)
}

/** Embedding settings (section `embedding`); the model ID is `models.embeddingModelId`.
  *
  * @param provider
  *   wire name of an [[EmbeddingProvider]]: `voyage`, `bedrock` or `fake`
  * @param dimensions
  *   output vector dimension, 1..4096
  * @param apiKeyEnvVar
  *   the NAME (never the value) of the environment variable holding the provider API key; matches
  *   `[A-Z][A-Z0-9_]*`, must not start with `SCHOLARCHAIN_CFG_`; required for `voyage`
  * @param batchSize
  *   texts per embedding request, > 0
  * @param maxRetries
  *   retries of throttled (429) and 5xx requests, >= 0
  * @param retryBaseDelayMillis
  *   first backoff delay, > 0
  * @param retryMaxDelayMillis
  *   backoff cap, >= retryBaseDelayMillis
  * @param requestsPerMinute
  *   client-side request rate limit, > 0
  * @param tokensPerMinute
  *   client-side token rate limit, > 0
  */
final case class EmbeddingSettings(
    provider: String,
    dimensions: Int,
    apiKeyEnvVar: Option[String],
    batchSize: Int,
    maxRetries: Int,
    retryBaseDelayMillis: Int,
    retryMaxDelayMillis: Int,
    requestsPerMinute: Int,
    tokensPerMinute: Int
) {
  def providerKind: Either[String, EmbeddingProvider] = EmbeddingProvider.fromWire(provider)
}
object EmbeddingSettings { implicit val readWriter: ReadWriter[EmbeddingSettings] = macroRW }

// ---- Optional sections (Phase 9, additive) ---------------------------------------------------
//
// Same rules as the Phase 7 sections. The answer model ID is the existing models.answerModelId;
// the window labels are the existing graph.windows labels.

/** LLM provider named by `llm.provider` (new in Phase 9). */
sealed trait LlmProvider extends WireEnum
object LlmProvider extends WireEnumCompanion[LlmProvider]("llm provider") {

  /** Anthropic Messages API; needs `llm.apiKeyEnvVar`. */
  case object Anthropic extends LlmProvider { val wire = "anthropic" }

  /** Amazon Bedrock InvokeModel. */
  case object Bedrock extends LlmProvider { val wire = "bedrock" }

  /** Deterministic local model; for tests and local runs only. */
  case object Fake extends LlmProvider { val wire = "fake" }

  val values: List[LlmProvider] = List(Anthropic, Bedrock, Fake)
}

/** Runtime LLM settings (section `llm`); the model ID is `models.answerModelId`.
  *
  * @param provider
  *   wire name of an [[LlmProvider]]: `anthropic`, `bedrock` or `fake`
  * @param maxOutputTokens
  *   output token cap per request, 1..8192
  * @param requestTimeoutMillis
  *   per-request timeout, > 0
  * @param maxRetries
  *   retries of throttled and 5xx requests, >= 0
  * @param retryBaseDelayMillis
  *   first backoff delay, > 0
  * @param retryMaxDelayMillis
  *   backoff cap, >= retryBaseDelayMillis
  * @param requestsPerMinute
  *   client-side request rate limit, > 0
  * @param maxStructuredRetries
  *   retries of a model output that fails structured validation, 0..2
  * @param contextTokenBudget
  *   prompt input token budget, > 0
  * @param apiKeyEnvVar
  *   the NAME (never the value) of the environment variable holding the provider API key; same
  *   rules as `embedding.apiKeyEnvVar` (`[A-Z][A-Z0-9_]*`, not starting with `SCHOLARCHAIN_CFG_`);
  *   required for `anthropic`
  */
final case class LlmSettings(
    provider: String,
    maxOutputTokens: Int,
    requestTimeoutMillis: Int,
    maxRetries: Int,
    retryBaseDelayMillis: Int,
    retryMaxDelayMillis: Int,
    requestsPerMinute: Int,
    maxStructuredRetries: Int,
    contextTokenBudget: Int,
    apiKeyEnvVar: Option[String]
) {
  def providerKind: Either[String, LlmProvider] = LlmProvider.fromWire(provider)
}
object LlmSettings {
  implicit val readWriter: ReadWriter[LlmSettings] = macroRW

  val MaxOutputTokens: Int = 8192
  val MaxStructuredRetries: Int = 2
}

/** Runtime query bounds (section `runtime`).
  *
  * @param defaultWindowLabel
  *   window used when a question names none; one of the `graph.windows` labels
  * @param rankingCacheTopK
  *   ranking items cached per window and metric, 1..500
  * @param maxBundleItems
  *   evidence items per answer bundle, 1..400
  * @param maxPathDepth
  *   largest path search depth (coauthor edges), 1..6
  * @param graphResultLimit
  *   rows returned by one graph query template, 1..500
  * @param graphTimeoutMillis
  *   total time of one graph search, 1..30000
  * @param maxResearcherNames
  *   researcher names accepted from one question, 1..10
  */
final case class RuntimeSettings(
    defaultWindowLabel: String,
    rankingCacheTopK: Int,
    maxBundleItems: Int,
    maxPathDepth: Int,
    graphResultLimit: Int,
    graphTimeoutMillis: Int,
    maxResearcherNames: Int
)
object RuntimeSettings {
  implicit val readWriter: ReadWriter[RuntimeSettings] = macroRW

  val MaxRankingCacheTopK: Int = 500
  val MaxBundleItems: Int = 400
  val MaxPathDepth: Int = 6
  val MaxGraphResultLimit: Int = 500
  val MaxGraphTimeoutMillis: Int = 30000
  val MaxResearcherNames: Int = 10
}

// ---- Optional section (Phase 10, additive) ---------------------------------------------------
//
// Same rules as the Phase 7 and Phase 9 sections: absent unless a key is set, then every required
// key is set, no defaults.

/** REST service settings (section `service`).
  *
  * The two API-key fields hold only environment variable NAMES; the configuration loader never
  * reads their values (the services module does, at startup).
  *
  * @param port
  *   listening port, 1..65535
  * @param apiKeyEnvVar
  *   the NAME of the environment variable holding the full-access API key; same rules as
  *   `llm.apiKeyEnvVar` (`[A-Z][A-Z0-9_]*`, not starting with `SCHOLARCHAIN_CFG_`)
  * @param readOnlyApiKeyEnvVar
  *   the NAME of the environment variable holding the read-only API key; same rules, and different
  *   from `apiKeyEnvVar`. Both service key names (Phase 11) also differ from `llm.apiKeyEnvVar` and
  *   `embedding.apiKeyEnvVar`, and no API-key name may be one of
  *   `ConfigValidator.ReservedEnvVarNames` (HOSTNAME, PATH, AWS_REGION, SCHOLARCHAIN_CONFIG_FILE,
  *   ...)
  * @param maxRequestBytes
  *   largest accepted request body, 1024..1048576 bytes
  * @param syncDeadlineMillis
  *   deadline of a synchronous query, 1..120000
  * @param maxConcurrentQueries
  *   synchronous queries running at once, 1..64
  * @param workerConcurrency
  *   asynchronous job workers, 1..64
  * @param jobMaxAttempts
  *   attempts of one asynchronous job, 1..5
  * @param jobLeaseMillis
  *   job lease, 10000..900000, and greater than `syncDeadlineMillis`
  * @param jobTtlSeconds
  *   lifetime of a job record and of an idempotency record, 3600..604800, and
  *   `jobTtlSeconds * 1000 > jobLeaseMillis`
  * @param circuitFailureThreshold
  *   consecutive failures that open the circuit breaker, 1..100
  * @param circuitOpenMillis
  *   time the circuit stays open, 1000..600000
  * @param requestsPerMinute
  *   per-process rate limit over all authenticated routes, > 0
  * @param jobQueueUrl
  *   optional `https://` URL of the job queue; whether it is required (for example by the AWS store
  *   wiring) is decided by the services module, not by core
  */
final case class ServiceSettings(
    port: Int,
    apiKeyEnvVar: String,
    readOnlyApiKeyEnvVar: String,
    maxRequestBytes: Int,
    syncDeadlineMillis: Int,
    maxConcurrentQueries: Int,
    workerConcurrency: Int,
    jobMaxAttempts: Int,
    jobLeaseMillis: Int,
    jobTtlSeconds: Int,
    circuitFailureThreshold: Int,
    circuitOpenMillis: Int,
    requestsPerMinute: Int,
    jobQueueUrl: Option[String]
)
object ServiceSettings {
  implicit val readWriter: ReadWriter[ServiceSettings] = macroRW

  val MinMaxRequestBytes: Int = 1024
  val MaxMaxRequestBytes: Int = 1048576
  val MaxSyncDeadlineMillis: Int = 120000
  val MaxConcurrentQueries: Int = 64
  val MaxWorkerConcurrency: Int = 64
  val MaxJobAttempts: Int = 5
  val MinJobLeaseMillis: Int = 10000
  val MaxJobLeaseMillis: Int = 900000
  val MinJobTtlSeconds: Int = 3600
  val MaxJobTtlSeconds: Int = 604800
  val MaxCircuitFailureThreshold: Int = 100
  val MinCircuitOpenMillis: Int = 1000
  val MaxCircuitOpenMillis: Int = 600000
}

/** The resolved configuration. The Phase 7, Phase 9 and Phase 10 sections default to `None`;
  * uPickle omits fields equal to their default, so a configuration without them serializes (and
  * fingerprints) exactly as before Phase 7.
  */
final case class ScholarChainConfig(
    configVersion: Int,
    environment: String,
    aws: AwsSettings,
    storage: StorageSettings,
    entityResolution: EntityResolutionSettings,
    models: ModelSettings,
    graph: GraphSettings,
    bridge: BridgeSettings,
    objectStore: Option[ObjectStoreSettings] = None,
    dynamoDb: Option[DynamoDbSettings] = None,
    neptune: Option[NeptuneSettings] = None,
    vectors: Option[VectorSettings] = None,
    embedding: Option[EmbeddingSettings] = None,
    llm: Option[LlmSettings] = None,
    runtime: Option[RuntimeSettings] = None,
    service: Option[ServiceSettings] = None
) {

  /** `sha256:<hex>` of the compact JSON of the resolved configuration. Use it as the
    * `configFingerprint` input of `RunId.of`.
    */
  def fingerprint: String = "sha256:" + Hashing.sha256Hex(write(this))

  /** S3 zone locations when the `objectStore` section is configured. */
  def s3Zones: Option[ListMap[String, S3Location]] = objectStore.map(_.zones(storage))
}
object ScholarChainConfig {
  implicit val readWriter: ReadWriter[ScholarChainConfig] = macroRW
}
