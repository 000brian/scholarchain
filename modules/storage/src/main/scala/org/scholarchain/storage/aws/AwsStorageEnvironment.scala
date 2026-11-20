package org.scholarchain.storage.aws

import java.net.URI
import java.nio.file.Path

import org.scholarchain.core.SemanticDocument
import org.scholarchain.storage._
import org.scholarchain.storage.ingest.{
  DocumentExtras,
  IngestSettings,
  TextStore,
  VectorIngestPipeline,
  VectorReembedder
}
import org.scholarchain.storage.ports.{EmbeddingPort, GraphReadSettings}
import org.scholarchain.storage.runtime.RuntimeStores
import org.scholarchain.storage.s3.S3KeyLayout
import org.scholarchain.storage.source.RejectLog
import org.scholarchain.storage.vectors.IndexSpecCatalog
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.http.SdkHttpClient
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.neptunedata.NeptunedataClient
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3vectors.S3VectorsClient
import software.amazon.awssdk.services.sqs.SqsClient

/** The synchronous SDK clients behind one [[AwsStorageEnvironment]]. Tests pass stub clients; the
  * runtime uses [[AwsSdkClients.build]]. `neptune` is None when the `neptune` section is not
  * configured. `sqs` (the Phase 10 job queue client) is always built by [[AwsSdkClients.build]];
  * stub-client tests that need no queue leave it None.
  */
final case class AwsSdkClients(
    s3: S3Client,
    dynamoDb: DynamoDbClient,
    s3Vectors: S3VectorsClient,
    neptune: Option[NeptunedataClient],
    sqs: Option[SqsClient] = None
) extends AutoCloseable {
  def close(): Unit = {
    s3.close(); dynamoDb.close(); s3Vectors.close(); neptune.foreach(_.close());
    sqs.foreach(_.close())
  }
}

object AwsSdkClients {

  /** Builds the clients from configuration (Region, Neptune endpoint host and port) and `settings`
    * (timeouts; SDK `maxAttempts = 1`). Building a client makes no network call: SDK clients
    * connect, and the default provider chain resolves credentials, only on the first request.
    *
    * @param httpClient
    *   one HTTP client shared by every SDK client; default: a new url-connection client per SDK
    *   client with the settings' timeouts. Tests pass a stub transport.
    * @param credentials
    *   default: the SDK default provider chain (the runtime IAM role). Tests pass anonymous
    *   credentials. Never a static key.
    */
  def build(
      settings: AwsClientSettings,
      config: StorageConfig,
      httpClient: Option[SdkHttpClient] = None,
      credentials: Option[AwsCredentialsProvider] = None
  ): AwsSdkClients = {
    require(settings.region == config.region, "client Region must be the configured aws.region")
    def http(): SdkHttpClient = httpClient.getOrElse(settings.httpClient())
    val s3 = {
      val b = S3Client
        .builder()
        .region(settings.awsRegion)
        .httpClient(http())
        .overrideConfiguration(settings.overrides())
      credentials.foreach(b.credentialsProvider)
      b.build()
    }
    val dynamo = {
      val b = DynamoDbClient
        .builder()
        .region(settings.awsRegion)
        .httpClient(http())
        .overrideConfiguration(settings.overrides())
      credentials.foreach(b.credentialsProvider)
      b.build()
    }
    val vectors = {
      val b = S3VectorsClient
        .builder()
        .region(settings.awsRegion)
        .httpClient(http())
        .overrideConfiguration(settings.overrides())
      credentials.foreach(b.credentialsProvider)
      b.build()
    }
    val neptune = config.neptune.map { n =>
      require(
        config.neptuneEndpoint != null && config.neptuneEndpoint.matches("[A-Za-z0-9.-]+"),
        "invalid Neptune endpoint host"
      )
      val b = NeptunedataClient
        .builder()
        .region(settings.awsRegion)
        .endpointOverride(URI.create(s"https://${config.neptuneEndpoint}:${n.port}"))
        .httpClient(http())
        .overrideConfiguration(settings.overrides())
      credentials.foreach(b.credentialsProvider)
      b.build()
    }
    val sqs = {
      val b = SqsClient
        .builder()
        .region(settings.awsRegion)
        .httpClient(http())
        .overrideConfiguration(settings.overrides())
      credentials.foreach(b.credentialsProvider)
      b.build()
    }
    AwsSdkClients(s3, dynamo, vectors, neptune, Some(sqs))
  }
}

/** The AWS twin of [[org.scholarchain.storage.local.LocalStorageEnvironment]]: ordinary S3, the six
  * DynamoDB tables, the five S3 Vectors indexes, the text store, the vector ingest pipeline factory
  * and the read-only Neptune graph, all built from [[StorageConfig]] plus SDK clients. Phase 9's
  * runtime and rag's ingest job (`--stores aws`) use this one factory.
  *
  * Retries (m2): SDK clients make exactly one attempt per call; the stores here wrap the adapters
  * in storage's bounded [[Retry]] with `config.retryPolicy` (S3 Vectors puts are retried by the
  * ingest pipeline's batcher instead). Their [[RetryCounters]] are the real request counts. Graph
  * reads are not retried.
  *
  * Construction makes no network call. Resource names, the Region and the Neptune endpoint come
  * from configuration; credentials from the runtime role.
  */
final class AwsStorageEnvironment private (
    val config: StorageConfig,
    clients: AwsSdkClients,
    rejectFile: Option[Path],
    sleeper: Sleeper
) extends AutoCloseable {
  val spec: IndexSpecCatalog = IndexSpecCatalog.load()
  spec.checkConfig(config.vectors, config.embedding.dimensions) match {
    case Nil  => ()
    case errs => throw new IllegalArgumentException(errs.mkString("; "))
  }
  private val policy = config.retryPolicy

  val objects: RetryingObjectStore =
    new RetryingObjectStore(new S3ObjectStore(clients.s3), policy, sleeper)
  val kv: RetryingKeyValueStore =
    new RetryingKeyValueStore(new DynamoDbKeyValueStore(clients.dynamoDb), policy, sleeper)
  val vectors: RetryingVectorIndex = new RetryingVectorIndex(
    new S3VectorsIndex(
      clients.s3Vectors,
      config.vectorBucket,
      n => spec.forName(n).map(_.nonFilterableKeys.toSet).getOrElse(Set.empty)
    ),
    policy,
    sleeper
  )
  val layout = new S3KeyLayout(config.zones)
  val textStore = new TextStore(objects, layout)
  val rejects = new RejectLog(rejectFile)

  /** The read-only Neptune graph, when the `neptune` and `runtime` sections are configured. */
  val graph: Either[String, NeptuneGraphRead] =
    for {
      client <- clients.neptune.toRight("neptune section is not configured")
      s <- GraphReadSettings.from(config)
    } yield new NeptuneGraphRead(client, s)

  /** The Neptune bulk loader (`StartLoaderJob`, `GetLoaderJobStatus`), when the `neptune` section
    * is configured. Used by `org.scholarchain.storage.load.StorageLoadMain`; not retried (a start
    * is not idempotent).
    */
  val bulkLoader: Either[String, NeptuneBulkLoader] =
    clients.neptune.toRight("neptune section is not configured").map(new NeptuneBulkLoader(_))

  /** Runtime readers and writers over these stores (needs the `neptune` and `runtime` sections). */
  lazy val runtimeStores: Either[String, RuntimeStores] =
    graph.flatMap(g => RuntimeStores.of(config, g, kv, vectors, textStore))

  def pipeline(
      embedder: EmbeddingPort,
      sourceDataset: String,
      extrasOf: SemanticDocument => DocumentExtras = _ => DocumentExtras.none,
      sleeper: Sleeper = this.sleeper
  ): VectorIngestPipeline =
    new VectorIngestPipeline(
      embedder,
      textStore,
      vectors,
      kv,
      config.dynamoDb,
      spec,
      config.windows,
      IngestSettings(config.vectors.putBatchSize, policy, sourceDataset),
      rejects,
      sleeper,
      extrasOf
    )

  /** The re-embed write path (Phase 15) over these stores: GetVectors and PutVectors on the
    * configured indexes, GetObject on the text objects, GetItem and PutItem on the QuerySession
    * table (completion records, QS6).
    */
  def reembedder(embedder: EmbeddingPort, sleeper: Sleeper = this.sleeper): VectorReembedder =
    new VectorReembedder(
      embedder,
      vectors,
      textStore,
      kv,
      config.dynamoDb.querySessionTable,
      spec,
      policy,
      sleeper
    )

  /** The SQS client for the job queue (see [[org.scholarchain.storage.jobs.ServiceStores.aws]]). */
  private[storage] def sqsClient: Option[SqsClient] = clients.sqs

  /** Calls and retries per store since construction. */
  def retryReport: ujson.Obj = ujson.Obj(
    "s3" -> objects.counters.toJson,
    "dynamoDb" -> kv.counters.toJson,
    "s3VectorsReads" -> vectors.counters.toJson
  )

  def close(): Unit = {
    rejects.close()
    clients.close()
  }
}

object AwsStorageEnvironment {

  /** The environment over given SDK clients (stub clients in tests). No network call. */
  def create(
      config: StorageConfig,
      clients: AwsSdkClients,
      rejectFile: Option[Path],
      sleeper: Sleeper = Sleeper.real
  ): AwsStorageEnvironment =
    new AwsStorageEnvironment(config, clients, rejectFile, sleeper)

  /** The environment over real SDK clients built from configuration and `settings`. No network call
    * until the first store operation.
    */
  def fromConfig(
      config: StorageConfig,
      settings: AwsClientSettings,
      rejectFile: Option[Path]
  ): AwsStorageEnvironment =
    create(config, AwsSdkClients.build(settings, config), rejectFile, Sleeper.real)
}
