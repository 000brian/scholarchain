package org.scholarchain.storage.runtime

import org.scholarchain.core.config.RuntimeSettings
import org.scholarchain.storage.{StorageConfig, StoreException}
import org.scholarchain.storage.ingest.TextStore
import org.scholarchain.storage.ports.{GraphReadPort, KeyValueStore, VectorIndexPort}

/** Everything the runtime workflow reads or writes, behind storage's ports and typed readers. Both
  * [[org.scholarchain.storage.local.LocalRuntimeEnvironment]] (in-memory) and
  * [[org.scholarchain.storage.aws.AwsStorageEnvironment.runtimeStores]] (AWS) provide one; code
  * written against this trait runs unchanged on either.
  */
trait RuntimeStores {
  def settings: RuntimeSettings

  /** The configured `graph.windows` labels, in configuration order. */
  def windowLabels: List[String]
  def graph: GraphReadPort
  def metrics: MetricCacheReader
  def entityLinks: EntityLinkReader
  def evidence: EvidenceStore
  def audits: QueryAuditWriter
  def vectors: VectorIndexPort
  def textStore: TextStore
}

object RuntimeStores {

  /** Readers and writers over `kv` with the table names, windows and runtime bounds of `config`.
    */
  def of(
      config: StorageConfig,
      graphPort: GraphReadPort,
      kv: KeyValueStore,
      vectorIndex: VectorIndexPort,
      texts: TextStore
  ): Either[String, RuntimeStores] =
    config.requireRuntime.map { r =>
      new RuntimeStores {
        val settings: RuntimeSettings = r
        val windowLabels: List[String] = config.windowLabels
        val graph: GraphReadPort = graphPort
        val metrics = new MetricCacheReader(
          kv,
          config.dynamoDb.researcherMetricCacheTable,
          config.windowLabels,
          r.rankingCacheTopK
        )
        val entityLinks = new EntityLinkReader(kv, config.dynamoDb.entityLinkCacheTable)
        val evidence = new EvidenceStore(kv, config.dynamoDb.evidenceBundleTable, r.maxBundleItems)
        val audits = new QueryAuditWriter(kv, config.dynamoDb.runLedgerTable)
        val vectors: VectorIndexPort = vectorIndex
        val textStore: TextStore = texts
      }
    }

  /** Runs a store call, turning a [[StoreException]] (after storage's bounded retries) into Left.
    */
  def guard[A](f: => Either[String, A]): Either[String, A] =
    try f
    catch {
      case e: StoreException =>
        Left(s"store failure (${if (e.transient) "transient" else "permanent"}): ${e.getMessage}")
    }
}
