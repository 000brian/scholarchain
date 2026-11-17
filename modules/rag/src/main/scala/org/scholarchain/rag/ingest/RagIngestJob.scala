package org.scholarchain.rag.ingest

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import scala.collection.immutable.ListMap
import scala.util.control.NonFatal

import org.scholarchain.core.{Hashing, RunId, SemanticDocument, SemanticDocumentType}
import org.scholarchain.core.config.{ConfigLoader, EmbeddingProvider, ScholarChainConfig}
import org.scholarchain.rag.embedding.{EmbeddingModelSpec, EmbeddingProviders, InputType}
import org.scholarchain.rag.retrieval.{SemanticRetriever, SmokeQueries}
import org.scholarchain.rag.semantic.{
  ChunkingPolicy,
  ExportDocuments,
  RenderedCorpus,
  TemplateRegistry,
  TextSource
}
import org.scholarchain.storage.StorageConfig
import org.scholarchain.storage.aws.{AwsClientSettings, AwsStorageEnvironment}
import org.scholarchain.storage.ingest.{
  DocumentExtras,
  IngestReport,
  TextStore,
  VectorIngestPipeline
}
import org.scholarchain.storage.local.LocalStorageEnvironment
import org.scholarchain.storage.ports.{EmbeddingPort, MetaValue, MetadataFilter, VectorIndexPort}
import org.scholarchain.storage.source.{ExportManifest, LocalDirectorySource, RejectLog}

/** The rag ingest job: renders every registered template from a Spark JSON Lines export, embeds
  * through the configured provider and ingests through storage's
  * [[org.scholarchain.storage.ingest.VectorIngestPipeline]], `--runs` times in one storage
  * environment (the second run must skip 100%), then runs the retrieval smoke samples and writes
  * `<out>/run-report.json`.
  *
  * Stores (`--stores`): `local` (storage's in-memory fakes) with the `fake` provider only, or `aws`
  * (Phase 9, A1: storage's [[AwsStorageEnvironment]], S3, DynamoDB and S3 Vectors) with the
  * `voyage` provider only. The job refuses `fake` for AWS stores and live providers for local
  * stores. The AWS path is compiled and unit-tested with stub stores; it has never been run.
  *
  * Bounds: `<out>` must not exist (a new root per attempt); a step longer than 60 minutes or free
  * disk below 15 GB stops the run.
  *
  * {{{
  * sbt "rag/runMain org.scholarchain.rag.ingest.RagIngestJob --config FILE --export RUN_DIR --out DIR [--runs 2] [--samples-per-index 25]"
  * }}}
  */
object RagIngestJob {
  val JobName = "rag.vector-ingest"
  val CodeVersion = "rag/1"
  val SourceDataset = "dblp"
  val Label = "local fake, not representative"
  private val MinFreeBytes = 15L * 1024 * 1024 * 1024
  private val MaxStepMillis = 60L * 60 * 1000

  final class StopRun(msg: String) extends RuntimeException(msg)

  final case class Options(
      config: Path,
      exportDir: Path,
      out: Path,
      runs: Int,
      samplesPerIndex: Int,
      stores: String
  )

  def parse(args: Array[String]): Either[String, Options] = {
    if (args.length % 2 != 0) Left("arguments must be --key value pairs")
    else {
      val m = args.grouped(2).map(a => a(0) -> a(1)).toMap
      val known = Set("--config", "--export", "--out", "--runs", "--samples-per-index", "--stores")
      val unknown = m.keySet -- known
      def req(k: String) = m.get(k).toRight(s"$k is required")
      def int(k: String, d: Int, lo: Int, hi: Int) =
        m.get(k) match {
          case None    => Right(d)
          case Some(v) =>
            v.toIntOption.filter(x => x >= lo && x <= hi).toRight(s"$k must be in $lo..$hi")
        }
      for {
        _ <- Either.cond(
          unknown.isEmpty,
          (),
          s"unknown arguments: ${unknown.toList.sorted.mkString(", ")}"
        )
        c <- req("--config")
        e <- req("--export")
        o <- req("--out")
        runs <- int("--runs", 2, 1, 3)
        samples <- int("--samples-per-index", 25, 1, 500)
        stores = m.getOrElse("--stores", "local")
      } yield Options(
        Paths.get(c).toAbsolutePath,
        Paths.get(e).toAbsolutePath,
        Paths.get(o).toAbsolutePath,
        runs,
        samples,
        stores
      )
    }
  }

  /** The provider and store combinations this job accepts: `fake` with `local` stores, and `voyage`
    * with `aws` stores (Phase 9, A1). The fake is never used for a stored index; live providers
    * never run against local stores; Bedrock embeddings are not used for the AWS index (quota 0).
    */
  def checkProviderAndStores(provider: EmbeddingProvider, stores: String): Either[String, Unit] =
    (provider, stores) match {
      case (EmbeddingProvider.Fake, "local") => Right(())
      case (EmbeddingProvider.Voyage, "aws") => Right(())
      case (EmbeddingProvider.Fake, s)       =>
        Left(
          s"the fake embedder is for local tests only and is never used for a stored index ('$s' stores)"
        )
      case (p, "local") =>
        Left(
          s"provider ${p.wire} would call a live API; local runs use the fake provider (no network)"
        )
      case (p, "aws") =>
        Left(s"provider ${p.wire} is not used for AWS stores (voyage only)")
      case (_, s) =>
        Left(s"stores '$s' are not known (local or aws)")
    }

  /** The stores one ingest run writes to: storage's in-memory environment or its AWS environment.
    */
  trait IngestStores extends AutoCloseable {
    def kind: String
    def description: String
    def pipeline(
        embedder: EmbeddingPort,
        sourceDataset: String,
        extrasOf: SemanticDocument => DocumentExtras
    ): VectorIngestPipeline
    def vectors: VectorIndexPort
    def textStore: TextStore
    def rejectsTotal: Long
    def report(cfg: StorageConfig): ujson.Obj
  }

  /** storage's in-memory fakes ([[LocalStorageEnvironment]]). */
  final class LocalIngestStores(val env: LocalStorageEnvironment, val kind: String = "local")
      extends IngestStores {
    val description = "storage in-memory fakes (LocalStorageEnvironment); no AWS call, no network"
    def pipeline(e: EmbeddingPort, s: String, x: SemanticDocument => DocumentExtras) =
      env.pipeline(e, s, extrasOf = x)
    def vectors: VectorIndexPort = env.vectors
    def textStore: TextStore = env.textStore
    def rejectsTotal: Long = env.rejects.total
    def report(cfg: StorageConfig): ujson.Obj = ujson.Obj(
      "indexCounts" -> ujson.Obj.from(
        env.spec.indexes.map(i => i.name -> ujson.Num(env.vectors.count(i.name).toDouble))
      ),
      "textObjects" -> env.objects.objectCount,
      "textBytes" -> env.objects.totalBytes.toDouble,
      "vectorPutCalls" -> env.vectors.putCalls.get.toDouble,
      "vectorsWritten" -> env.vectors.vectorsWritten.get.toDouble,
      "ledgerItems" -> env.kv.itemCount(cfg.dynamoDb.runLedgerTable).toDouble,
      "registryItems" -> env.kv.itemCount(cfg.dynamoDb.artifactRegistryTable).toDouble
    )
    def close(): Unit = env.close()
  }

  /** storage's AWS environment ([[AwsStorageEnvironment]]): S3, DynamoDB and S3 Vectors. */
  final class AwsIngestStores(env: AwsStorageEnvironment) extends IngestStores {
    val kind = "aws"
    val description =
      "AWS stores through storage's AwsStorageEnvironment (S3, DynamoDB, S3 Vectors)"
    def pipeline(e: EmbeddingPort, s: String, x: SemanticDocument => DocumentExtras) =
      env.pipeline(e, s, extrasOf = x)
    def vectors: VectorIndexPort = env.vectors
    def textStore: TextStore = env.textStore
    def rejectsTotal: Long = env.rejects.total
    def report(cfg: StorageConfig): ujson.Obj = ujson.Obj("retries" -> env.retryReport)
    def close(): Unit = env.close()
  }

  /** Builds the stores of `--stores` (`local` or `aws`). Building the AWS stores makes no network
    * call (SDK clients connect on first use).
    */
  val defaultStores: (StorageConfig, String, Path) => Either[String, IngestStores] =
    (cfg, stores, rejectFile) =>
      stores match {
        case "local" =>
          Right(new LocalIngestStores(LocalStorageEnvironment.create(cfg, Some(rejectFile))))
        case "aws" =>
          try
            Right(
              new AwsIngestStores(
                AwsStorageEnvironment.fromConfig(
                  cfg,
                  AwsClientSettings(cfg.region, 10000, 60000, 60000, maxAttempts = 1),
                  Some(rejectFile)
                )
              )
            )
          catch { case NonFatal(e) => Left(s"AWS stores could not be built: ${e.getMessage}") }
        case other => Left(s"stores '$other' are not known (local or aws)")
      }

  /** The configuration file plus the `SCHOLARCHAIN_CFG_*` overrides of `env` (D-01: environment
    * over file, as core's loader and services do; default the process environment), so a cloud
    * ingest takes real bucket names from the environment, never from a committed file.
    */
  def loadConfig(
      path: Path,
      env: Map[String, String] = sys.env
  ): Either[String, ScholarChainConfig] = {
    val text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
    ConfigLoader
      .load(
        text,
        path.getFileName.toString,
        env.filter(_._1.startsWith(ConfigLoader.EnvPrefix))
      )
      .left
      .map(_.message)
      .map(_.config)
  }

  def main(args: Array[String]): Unit = {
    val opts = parse(args).fold(e => sys.error(e), identity)
    val report = run(opts)
    println(s"[rag-ingest] ${report("status").str}; report: ${opts.out.resolve("run-report.json")}")
    if (!report("status").str.startsWith("completed")) sys.exit(1)
  }

  private def stats(xs: Seq[Double]): ujson.Obj =
    if (xs.isEmpty) ujson.Obj("n" -> 0)
    else {
      val s = xs.sorted
      def pct(p: Double) = s(math.min(s.size - 1, math.ceil(p * s.size).toInt - 1).max(0))
      ujson.Obj(
        "n" -> s.size,
        "min" -> s.head,
        "median" -> pct(0.5),
        "p95" -> pct(0.95),
        "max" -> s.last
      )
    }

  /** Runs the job and returns the report (also written to `<out>/run-report.json`). */
  def run(
      opts: Options,
      storesFactory: (StorageConfig, String, Path) => Either[String, IngestStores] = defaultStores,
      wiring: EmbeddingProviders.Wiring = EmbeddingProviders.Wiring()
  ): ujson.Obj = {
    if (Files.exists(opts.out)) sys.error(s"${opts.out} exists; every attempt uses a new root")
    Files.createDirectories(opts.out)
    val report = ujson.Obj(
      "format" -> "scholarchain.rag.ingest-run-report",
      "formatVersion" -> 1,
      "label" -> Label
    )
    val steps = ujson.Obj()
    report("steps") = steps
    def disk(): Long = Files.getFileStore(opts.out).getUsableSpace
    def step[A](name: String)(f: => A): A = {
      if (disk() < MinFreeBytes) throw new StopRun(s"free disk below 15 GB at start of $name")
      val t0 = System.nanoTime()
      println(s"[rag-ingest] step $name started")
      val a = f
      val ms = (System.nanoTime() - t0) / 1000000L
      println(s"[rag-ingest] step $name finished in $ms ms")
      steps(name) = ujson.Obj("millis" -> ms.toDouble, "freeDiskBytesAfter" -> disk().toDouble)
      if (ms > MaxStepMillis) throw new StopRun(s"step $name took $ms ms (> 60 minutes)")
      a
    }
    def write(): Unit =
      Files.write(
        opts.out.resolve("run-report.json"),
        report.render(indent = 2).getBytes(StandardCharsets.UTF_8)
      )

    val t0 = System.nanoTime()
    val renderRejects = new RejectLog(Some(opts.out.resolve("render-rejects.jsonl")))
    var env: IngestStores = null
    try {
      val config = loadConfig(opts.config).fold(e => sys.error(e), identity)
      val cfg = StorageConfig.from(config).fold(e => sys.error(e.mkString("; ")), identity)
      val provider = cfg.embedding.providerKind.fold(e => sys.error(e), identity)
      checkProviderAndStores(provider, opts.stores).fold(e => sys.error(e), identity)
      val doc =
        EmbeddingProviders
          .create(config, InputType.Document, wiring)
          .fold(e => sys.error(e), identity)
      val query =
        EmbeddingProviders.create(config, InputType.Query, wiring).fold(e => sys.error(e), identity)

      val src = new LocalDirectorySource(opts.exportDir)
      val manifest = src.manifest()
      val manifestSha =
        Hashing.sha256Hex(Files.readAllBytes(opts.exportDir.resolve(ExportManifest.FileName)))
      val runId = RunId
        .of(JobName, CodeVersion, s"sha256:$manifestSha", config.fingerprint)
        .fold(e => sys.error(e), identity)
      report("input") = ujson.Obj(
        "exportDir" -> opts.exportDir.toString,
        "exportManifestSha256" -> manifestSha,
        "sourceRunId" -> manifest.sourceRunId.value,
        "configFile" -> opts.config.getFileName.toString,
        "configFingerprint" -> config.fingerprint
      )
      report("environment") = ujson.Obj(
        "heapMaxBytes" -> Runtime.getRuntime.maxMemory.toDouble,
        "processors" -> Runtime.getRuntime.availableProcessors,
        "freeDiskBytesAtStart" -> disk().toDouble,
        "stores" -> opts.stores
      )
      report("embedding") = ujson.Obj(
        "provider" -> provider.wire,
        "documentModelSpec" -> doc.spec.id,
        "queryModelSpec" -> query.spec.id,
        "model" -> doc.spec.model,
        "dimensions" -> doc.spec.dimensions,
        "maxBatchSize" -> doc.port.maxBatchSize,
        "note" -> "fake = deterministic lexical feature hashing; NOT semantic"
      )
      report("templates") = ujson.Arr.from(TemplateRegistry.all.map { t =>
        ujson.Obj(
          "templateId" -> t.templateId,
          "documentType" -> t.documentType.wire,
          "index" -> cfg.vectors.indexFor(t.documentType),
          "chunking" -> s"${t.chunking.version} (maxChars ${t.chunking.maxChars})",
          "source" -> (t.source match {
            case TextSource.SparkVerbatim(k, j, p) =>
              s"spark verbatim: summaryKind=$k, job=$j, policy=$p"
            case TextSource.RagRendered(j, p) => s"rag rendered: job=$j, policy=$p"
            case TextSource.NoInput(r)        => s"no input (0 documents): $r"
          })
        )
      })
      report("runId") = runId.value
      write()

      env = storesFactory(cfg, opts.stores, opts.out.resolve("ingest-rejects.jsonl"))
        .fold(e => sys.error(e), identity)
      report("environment")("storesDescription") = env.description
      @volatile var corpus: RenderedCorpus = null
      val pipeline = env.pipeline(doc.port, SourceDataset, extrasOf = d => corpus.extrasOf(d))
      val builder = new ExportDocuments(src, manifest, cfg.vectors, renderRejects)
      val runs = ujson.Arr()
      report("runs") = runs
      var reports = List.empty[IngestReport]
      var digests = List.empty[String]
      (1 to opts.runs).foreach { n =>
        corpus = null
        corpus = step(s"render-run-$n")(builder.renderAll())
        digests :+= corpus.digest
        val docsPerIndex = corpus.documents.groupMapReduce(_.indexName)(_ => 1L)(_ + _)
        val usageBefore = doc.usage.requests
        val r = step(s"ingest-run-$n") {
          val s = pipeline.open(runId, s"rag-ingest run $n")
          s.ingest(corpus.documents.iterator)
          s.finish()
        }
        reports :+= r
        val total = r.millis.getOrElse("total", 0L)
        runs.value += ujson.Obj(
          "run" -> n,
          "corpusDigest" -> corpus.digest,
          "documents" -> corpus.documents.size.toDouble,
          "documentsPerIndex" -> ujson.Obj.from(
            cfg.vectors.indexNames.map(i => i -> ujson.Num(docsPerIndex.getOrElse(i, 0L).toDouble))
          ),
          "templates" -> ujson.Obj.from(corpus.stats.toList.sortBy(_._1).map { case (k, v) =>
            k -> v.toJson
          }),
          "ingest" -> r.toJson,
          "duplicatesSameText" -> r.total(_.duplicatesSameText).toDouble,
          "embedRequestsThisRun" -> (doc.usage.requests - usageBefore).toDouble,
          "docsPerSecondLocalFakeNotRepresentative" -> (if (total > 0) r.input * 1000.0 / total
                                                        else 0.0)
        )
        write()
      }
      report("renderDeterministic") = digests.distinct.size == 1
      report("secondRunSkipRatio") =
        reports.lift(1).map(r => ujson.Num(r.skipRatio)).getOrElse(ujson.Null)

      // ---- retrieval smoke samples on the real corpus (fake provider: lexical, not semantic) ----
      val retriever = new SemanticRetriever(query.port, query.spec, env.vectors, cfg.vectors)
      val smoke = step("retrieval-smoke") {
        val byIndex = corpus.documents.groupBy(_.indexName)
        ujson.Obj.from(cfg.vectors.indexNames.flatMap { idx =>
          byIndex.get(idx).map { docs =>
            val sorted = docs.sortBy(_.vectorKey)
            val stride = math.max(1, sorted.size / opts.samplesPerIndex)
            val samples = sorted.indices.by(stride).take(opts.samplesPerIndex).map(sorted)
            val sq = new SmokeQueries(docs.map(_.text))
            val k = math.min(10, cfg.vectors.queryTopK)
            val results = samples.map { d =>
              val q = sq.query(d.text)
              val q0 = System.nanoTime()
              // windowed documents are always queried within their own window (Phase 9: the
              // retriever refuses a windowed query without a window filter)
              val r = retriever.retrieve(d.documentType, q, k, RagIngestJob.windowFilter(d))
              val ms = (System.nanoTime() - q0) / 1e6
              val entries = r.toOption.map(_.context.entries).getOrElse(Nil)
              val rank = entries.indexWhere(_.vectorKey == d.vectorKey)
              val textOk =
                entries.headOption.exists(e => env.textStore.fetch(e.textS3Uri, e.textHash).isRight)
              val windowRank = rank
              val top1 = entries.headOption.map(_.vectorKey).getOrElse("-")
              (d, q, rank, ms, textOk, r, windowRank, top1)
            }
            val first = results.head
            idx -> ujson.Obj(
              "samples" -> results.size,
              "top1IsSelf" -> results.count(_._3 == 0),
              "selfInTop10" -> results.count(_._3 >= 0),
              "top1IsSelfWithinOwnWindow" -> results.count(_._7 == 0),
              "top1TextFetchedByPointerMatchesHash" -> results.count(_._5),
              "queryMillisLocalFakeNotRepresentative" -> stats(results.map(_._4)),
              "misses" -> ujson.Arr.from(
                results
                  .filter(_._3 != 0)
                  .take(5)
                  .map(x =>
                    ujson.Obj(
                      "vectorKey" -> x._1.vectorKey,
                      "rank" -> x._3,
                      "top1VectorKey" -> x._8,
                      "rankWithinOwnWindow" -> x._7
                    )
                  )
              ),
              "exampleQueryTokens" -> first._2,
              "exampleContext" -> first._6.fold(e => ujson.Str(e), _.context.toJson)
            )
          }
        })
      }
      report("retrievalSmoke") = smoke
      val storeReport = env.report(cfg)
      storeReport.value.get("indexCounts").foreach(c => report("indexCounts") = c)
      report("store") = ujson.Obj.from(storeReport.value.filterNot(_._1 == "indexCounts"))
      report("embedderUsage") =
        ujson.Obj("document" -> doc.usage.toJson, "query" -> query.usage.toJson)
      report("rejects") = ujson.Obj(
        "render" -> renderRejects.total.toDouble,
        "renderByTable" -> ujson.Arr.from(renderRejects.byStageAndTable.map { case ((s, t), n) =>
          ujson.Obj("stage" -> s, "table" -> t, "count" -> n.toDouble)
        }),
        "ingest" -> env.rejectsTotal.toDouble
      )
      report("status") = "completed"
    } catch {
      case e: StopRun   => report("status") = s"stopped: ${e.getMessage}"
      case e: Throwable =>
        report("status") = s"failed: ${e.getClass.getSimpleName}: ${e.getMessage}"
    } finally {
      renderRejects.close()
      if (env != null) env.close()
      report("totalMillis") = ((System.nanoTime() - t0) / 1000000L).toDouble
      write()
    }
    report
  }

  /** A metadata filter on a document's own window (windowStartYear and windowEndYear), if any. */
  def windowFilter(d: SemanticDocument): Option[MetadataFilter] =
    d.window.map(w =>
      MetadataFilter.And(
        List(
          MetadataFilter.Eq("windowStartYear", MetaValue.Num(w.startYear.toLong)),
          MetadataFilter.Eq("windowEndYear", MetaValue.Num(w.endYear.toLong))
        )
      )
    )

  /** Document counts per index of a corpus (for tests). */
  def countsByType(docs: Seq[SemanticDocument]): ListMap[SemanticDocumentType, Int] =
    ListMap.from(SemanticDocumentType.values.map(t => t -> docs.count(_.documentType == t)))

  /** The chunking policy and the model spec of the configuration, for reports. */
  def describe(config: ScholarChainConfig): Either[String, String] =
    EmbeddingModelSpec
      .fromConfig(config, InputType.Document)
      .map(s => s"${s.id}; ${ChunkingPolicy.current.version}")
}
