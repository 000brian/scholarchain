package org.scholarchain.storage.local

import java.nio.file.{Files, Path}

import scala.collection.immutable.ListMap
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

import org.scholarchain.core.Hashing
import org.scholarchain.core.config.RuntimeSettings
import org.scholarchain.storage.{StorageConfig, StorageVersion}
import org.scholarchain.storage.dynamo.{DynamoLoadReport, DynamoLoader}
import org.scholarchain.storage.ingest.TextStore
import org.scholarchain.storage.ports.{GraphReadPort, GraphReadSettings, VectorIndexPort}
import org.scholarchain.storage.runtime._
import org.scholarchain.storage.source.{ExportManifest, LocalDirectorySource, RejectLog}

/** Options of [[LocalRuntimeEnvironment.load]].
  *
  * @param windowLabels
  *   COAUTHORED_WITH windows to load into the graph (configured labels); default: only
  *   `runtime.defaultWindowLabel`
  * @param loaderParallelism
  *   concurrent conditional writes of the DynamoDB load into the in-memory tables (1..64)
  * @param rejectFile
  *   where rejected export rows are logged (never inside an input folder); default: counted only
  */
final case class LocalRuntimeOptions(
    windowLabels: Option[List[String]] = None,
    loaderParallelism: Int = 1,
    rejectFile: Option[Path] = None
)

/** What [[LocalRuntimeEnvironment.load]] loaded: rows per graph type (read and kept after the
  * window filter), items per table, heap and time.
  */
final case class LocalRuntimeLoadReport(
    exportManifestSha256: String,
    graphWindowLabels: List[String],
    graphRows: ListMap[String, CsvLoadStats],
    graphFiles: ListMap[String, Int],
    tableItems: ListMap[String, Long],
    dynamo: DynamoLoadReport,
    rejects: Long,
    heapUsedBytes: Long,
    heapMaxBytes: Long,
    millis: ListMap[String, Long]
) {
  def toJson: ujson.Obj = ujson.Obj(
    "format" -> "scholarchain.storage.local-runtime-load",
    "stores" -> "in-memory graph and tables; no AWS call, no network",
    "exportManifestSha256" -> exportManifestSha256,
    "graphWindowLabels" -> ujson.Arr.from(graphWindowLabels.map(ujson.Str(_))),
    "graphRows" -> ujson.Obj.from(graphRows.map { case (k, s) =>
      k -> ujson.Obj(
        "files" -> graphFiles.getOrElse(k, 0),
        "read" -> s.read.toDouble,
        "kept" -> s.kept.toDouble
      )
    }),
    "tableItems" -> ujson.Obj.from(tableItems.map { case (k, v) => k -> ujson.Num(v.toDouble) }),
    "dynamoLoad" -> dynamo.toJson,
    "rejects" -> rejects.toDouble,
    "heapUsedBytes" -> heapUsedBytes.toDouble,
    "heapMaxBytes" -> heapMaxBytes.toDouble,
    "millis" -> ujson.Obj.from(millis.map { case (k, v) => k -> ujson.Num(v.toDouble) })
  )
}

/** The runtime's stores on this laptop: the in-memory graph loaded from the Neptune openCypher CSV
  * files, and the in-memory DynamoDB tables loaded from the Spark export through the same
  * [[DynamoLoader]] item builders as a real load (ResearcherMetricCache for every window plus the
  * RM4 ranking items, EntityLinkCache, and EvidenceBundle with the path evidence items). Vectors
  * and texts start empty (rag ingests its documents through [[pipeline]]).
  *
  * Read-only toward both input folders.
  */
final class LocalRuntimeEnvironment private (
    val storage: LocalStorageEnvironment,
    val graph: InMemoryGraph,
    val report: LocalRuntimeLoadReport,
    stores: RuntimeStores
) extends RuntimeStores
    with AutoCloseable {
  def settings: RuntimeSettings = stores.settings
  def windowLabels: List[String] = stores.windowLabels
  def metrics: MetricCacheReader = stores.metrics
  def entityLinks: EntityLinkReader = stores.entityLinks
  def evidence: EvidenceStore = stores.evidence
  def audits: QueryAuditWriter = stores.audits
  def vectors: VectorIndexPort = storage.vectors
  def textStore: TextStore = storage.textStore

  /** The vector ingest pipeline over the in-memory vector index and text store. */
  def pipeline(
      embedder: org.scholarchain.storage.ports.EmbeddingPort,
      sourceDataset: String
  ): org.scholarchain.storage.ingest.VectorIngestPipeline =
    storage.pipeline(embedder, sourceDataset)

  def close(): Unit = storage.close()
}

object LocalRuntimeEnvironment {

  /** Graph types loaded, as (stage directory, type directory). */
  val GraphTypes: List[(String, String)] = List(
    "nodes" -> "Researcher",
    "nodes" -> "Work",
    "nodes" -> "Venue",
    "edges" -> "AUTHORED",
    "edges" -> "PUBLISHED_IN",
    "edges" -> "COAUTHORED_WITH"
  )

  private def csvFiles(dir: Path): List[Path] = {
    val s = Files.list(dir)
    try
      s.iterator.asScala
        .filter(p =>
          Files.isRegularFile(p) && {
            val n = p.getFileName.toString
            n.endsWith(".csv") || n.endsWith(".csv.gz")
          }
        )
        .toList
        .sortBy(_.getFileName.toString)
    finally s.close()
  }

  private def under(p: Path, dir: Path): Boolean =
    p.toAbsolutePath.normalize.startsWith(dir.toAbsolutePath.normalize)

  private def heapUsed(): Long = {
    val rt = Runtime.getRuntime
    System.gc()
    rt.totalMemory - rt.freeMemory
  }

  /** Loads the local runtime stores. Left when the `runtime` section is missing, a window label is
    * not configured, an input folder or graph type directory is missing, or the reject file would
    * be written inside an input folder.
    *
    * @param exportDir
    *   the Spark export run directory (`.../run=<hex>/`, holding `_export_manifest.json`)
    * @param neptuneCsvDir
    *   the Neptune CSV root (holding `nodes/` and `edges/`)
    */
  def load(
      config: StorageConfig,
      exportDir: Path,
      neptuneCsvDir: Path,
      options: LocalRuntimeOptions = LocalRuntimeOptions()
  ): Either[String, LocalRuntimeEnvironment] =
    for {
      runtime <- config.requireRuntime
      labels = options.windowLabels.getOrElse(List(runtime.defaultWindowLabel))
      _ <- Either.cond(labels.nonEmpty, (), "at least one graph window label")
      _ <- labels.find(l => !config.windowLabels.contains(l)) match {
        case Some(l) => Left(s"window '$l' is not a configured window")
        case None    => Right(())
      }
      _ <- Either.cond(
        Files.isRegularFile(exportDir.resolve(ExportManifest.FileName)),
        (),
        s"$exportDir has no ${ExportManifest.FileName}"
      )
      _ <- Either.cond(Files.isDirectory(neptuneCsvDir), (), s"$neptuneCsvDir is not a directory")
      missing = GraphTypes
        .map { case (s, t) => neptuneCsvDir.resolve(s).resolve(t) }
        .filterNot(Files.isDirectory(_))
      _ <- Either.cond(
        missing.isEmpty,
        (),
        s"missing graph type directories: ${missing.mkString(", ")}"
      )
      _ <- Either.cond(
        options.rejectFile.forall(f => !under(f, exportDir) && !under(f, neptuneCsvDir)),
        (),
        "the reject file must not be inside an input folder"
      )
      _ <- Either.cond(
        options.loaderParallelism >= 1 && options.loaderParallelism <= 64,
        (),
        "loaderParallelism must be in 1..64"
      )
      env <-
        try Right(build(config, runtime, exportDir, neptuneCsvDir, labels, options))
        catch {
          case NonFatal(e) =>
            Left(s"local runtime load failed: ${e.getClass.getSimpleName}: ${e.getMessage}")
        }
    } yield env

  private def build(
      config: StorageConfig,
      runtime: RuntimeSettings,
      exportDir: Path,
      neptuneCsvDir: Path,
      labels: List[String],
      options: LocalRuntimeOptions
  ): LocalRuntimeEnvironment = {
    val t0 = System.nanoTime()
    val storage = LocalStorageEnvironment.create(config, None)
    val graphSettings = GraphReadSettings.of(config.windowLabels, runtime)
    val graph = new InMemoryGraph(rowCap = 1, graphSettings)
    val rows = ListMap.newBuilder[String, CsvLoadStats]
    val files = ListMap.newBuilder[String, Int]
    GraphTypes.foreach { case (stage, tpe) =>
      val fs = csvFiles(neptuneCsvDir.resolve(stage).resolve(tpe))
      val filter =
        if (tpe == "COAUTHORED_WITH") Some(CsvRowFilter("windowLabel", labels.toSet)) else None
      rows += tpe -> fs.foldLeft(CsvLoadStats.zero)((acc, f) => acc + graph.loadCsv(f, filter))
      files += tpe -> fs.size
    }
    val tGraph = System.nanoTime()

    val rejects = new RejectLog(options.rejectFile)
    val src = new LocalDirectorySource(exportDir)
    val manifest = src.manifest()
    val manifestSha =
      Hashing.sha256Hex(Files.readAllBytes(exportDir.resolve(ExportManifest.FileName)))
    val runId =
      StorageVersion.runId("storage.local-runtime-load", s"sha256:$manifestSha", config.fingerprint)
    val dynamo =
      try
        new DynamoLoader(
          src,
          manifest,
          storage.kv,
          config.dynamoDb,
          rejects,
          options.loaderParallelism,
          Some(runtime.rankingCacheTopK)
        ).load(1, runId, Nil)
      finally rejects.close()
    val tKv = System.nanoTime()

    val stores = RuntimeStores
      .of(config, graph, storage.kv, storage.vectors, storage.textStore)
      .fold(e => throw new IllegalStateException(e), identity)
    val heap = heapUsed()
    val d = config.dynamoDb
    val report = LocalRuntimeLoadReport(
      manifestSha,
      labels,
      rows.result(),
      files.result(),
      ListMap(
        "ResearcherMetricCache" -> storage.kv.itemCount(d.researcherMetricCacheTable),
        "EntityLinkCache" -> storage.kv.itemCount(d.entityLinkCacheTable),
        "EvidenceBundle" -> storage.kv.itemCount(d.evidenceBundleTable),
        "RunLedger" -> storage.kv.itemCount(d.runLedgerTable),
        "ArtifactRegistry" -> storage.kv.itemCount(d.artifactRegistryTable),
        "QuerySession" -> storage.kv.itemCount(d.querySessionTable)
      ),
      dynamo,
      rejects.total,
      heap,
      Runtime.getRuntime.maxMemory,
      ListMap(
        "graph" -> (tGraph - t0) / 1000000L,
        "tables" -> (tKv - tGraph) / 1000000L,
        "total" -> (System.nanoTime() - t0) / 1000000L
      )
    )
    new LocalRuntimeEnvironment(storage, graph, report, stores)
  }
}
