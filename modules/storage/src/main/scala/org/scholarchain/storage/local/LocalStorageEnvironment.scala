package org.scholarchain.storage.local

import java.nio.file.Path

import org.scholarchain.core.SemanticDocument
import org.scholarchain.storage.{Sleeper, StorageConfig}
import org.scholarchain.storage.ingest.{
  DocumentExtras,
  IngestSettings,
  TextStore,
  VectorIngestPipeline,
  VectorReembedder
}
import org.scholarchain.storage.ports.EmbeddingPort
import org.scholarchain.storage.s3.S3KeyLayout
import org.scholarchain.storage.source.RejectLog
import org.scholarchain.storage.vectors.IndexSpecCatalog

/** In-memory storage for local runs and tests: ordinary S3, the six DynamoDB tables, and the five
  * S3 Vectors indexes of the index spec, behind the same ports as the AWS adapters. Keep one
  * environment for both runs of a twice-run ingest so the second run sees the first run's state.
  *
  * Typical use (rag's ingest main):
  * {{{
  * val env = LocalStorageEnvironment.create(storageConfig, Some(rejectFile))
  * val pipeline = env.pipeline(embedder, sourceDataset = "dblp")
  * val first = pipeline.run(runId, documents())
  * val second = pipeline.run(runId, documents()) // second.skipRatio == 1.0 when nothing changed
  * env.close()
  * }}}
  */
final class LocalStorageEnvironment private (val config: StorageConfig, rejectFile: Option[Path])
    extends AutoCloseable {
  val spec: IndexSpecCatalog = IndexSpecCatalog.load()
  spec.checkConfig(config.vectors, config.embedding.dimensions) match {
    case Nil  => ()
    case errs => throw new IllegalArgumentException(errs.mkString("; "))
  }
  val objects = new InMemoryObjectStore
  val kv = new InMemoryKeyValueStore(config.tableNames)
  val vectors = new InMemoryVectorIndex(spec)
  val layout = new S3KeyLayout(config.zones)
  val textStore = new TextStore(objects, layout)
  val rejects = new RejectLog(rejectFile)

  def pipeline(
      embedder: EmbeddingPort,
      sourceDataset: String,
      extrasOf: SemanticDocument => DocumentExtras = _ => DocumentExtras.none,
      sleeper: Sleeper = Sleeper.none
  ): VectorIngestPipeline =
    new VectorIngestPipeline(
      embedder,
      textStore,
      vectors,
      kv,
      config.dynamoDb,
      spec,
      config.windows,
      IngestSettings(config.vectors.putBatchSize, config.retryPolicy, sourceDataset),
      rejects,
      sleeper,
      extrasOf
    )

  /** The re-embed write path over the in-memory stores (completion records in QuerySession). */
  def reembedder(
      embedder: EmbeddingPort,
      sleeper: Sleeper = Sleeper.none,
      nowSeconds: () => Long = () => System.currentTimeMillis() / 1000L
  ): VectorReembedder =
    new VectorReembedder(
      embedder,
      vectors,
      textStore,
      kv,
      config.dynamoDb.querySessionTable,
      spec,
      config.retryPolicy,
      sleeper,
      nowSeconds
    )

  def close(): Unit = rejects.close()
}

object LocalStorageEnvironment {
  def create(config: StorageConfig, rejectFile: Option[Path]): LocalStorageEnvironment =
    new LocalStorageEnvironment(config, rejectFile)
}
