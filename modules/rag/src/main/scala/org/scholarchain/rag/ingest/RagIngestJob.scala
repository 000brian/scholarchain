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
import org.scholarchain.storage.load.{BuildIdentity, Masking}
import org.scholarchain.storage.local.LocalStorageEnvironment
import org.scholarchain.storage.ports.{
  EmbeddingPort,
  MetaValue,
  MetadataFilter,
  ObjectRef,
  ObjectStore,
  PutOutcome,
  VectorIndexPort
}
import org.scholarchain.storage.s3.S3KeyLayout
import org.scholarchain.storage.source.{ExportSources, RejectLog, S3ExportSource}

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
  * Export (`--export`): a local export directory (`.../run=<hex>`), or (Phase 13, 7b) an
  * `s3://BUCKET/PREFIX/run=<32 hex>` export read through storage's [[S3ExportSource]] (stores `aws`
  * only; the bucket must be a configured data-lake zone bucket). Either way the manifest must be
  * present, every listed file present with its size, and every byte read matches the manifest's
  * sha256.
  *
  * Report: `<out>/run-report.json` always; with stores `aws` also a byte-identical copy at
  * `<semantic-context prefix>/ingest-reports/run=<hex>/run-report-sha256=<hex>.json` (the data
  * lake's `rag/` prefix in the course configuration), put-if-absent, with the outcome in
  * `<out>/run-report-copy.json` and on standard output. The report records `--code-version` (the
  * build commit) and the jar's [[BuildIdentity]]; [[CodeVersion]] stays the run-ID input. Bucket
  * names and the account ID are masked in every report and printed line ([[Masking]]).
  *
  * Bounds: `<out>` must not exist (a new root per attempt); a step longer than [[MaxStepMillis]]
  * (120 minutes since Phase 15) or free disk below 15 GiB ([[MinFreeBytes]]) stops the run.
  * `--max-documents N` (Phase 15) limits every run and the retrieval smoke to the first N rendered
  * documents.
  *
  * {{{
  * java -cp app.jar org.scholarchain.rag.ingest.RagIngestJob --config FILE --export RUN_DIR|s3://... --out DIR --code-version SHA40 [--stores local|aws] [--runs 2] [--samples-per-index 25] [--max-documents N]
  * }}}
  */
object RagIngestJob {
  val JobName = "rag.vector-ingest"

  /** The job's code version, an input of its run ID (unchanged meaning; `--code-version` is the
    * build commit and is only recorded).
    */
  val CodeVersion = "rag/1"
  val SourceDataset = "dblp"
  val Label = "local fake, not representative"

  /** Free disk the job needs under `--out` at the start of every step (15 GiB). */
  val MinFreeBytes: Long = 15L * 1024 * 1024 * 1024

  /** A step that took longer than this stops the run (checked when the step ends). 120 minutes
    * since Phase 15 (G-27; was 60): storage's estimate of a full 251,094-document first pass at the
    * new write settings is about 31 min low, 36 central, 50 to 54 high (estimate, agent-report-
    * storage.md "ESTIMATE"), so the cap is about twice the high estimate; a skip-only pass is about
    * 25 to 100 s (estimate). Phase 14 measured 3.61 h for the old single-writer first pass.
    */
  val MaxStepMillis: Long = 120L * 60 * 1000

  /** Bounds of `--max-documents` (Phase 15, G-27 probe): at most this many rendered documents. */
  val MaxDocumentsBound: Int = 10000000

  /** The contract (decision 7): the option names, without `--`. The parser accepts exactly these;
    * scripts are written from this constant.
    */
  val Required: List[String] = List("config", "export", "out", "code-version")
  val Optional: List[String] = List("stores", "runs", "samples-per-index", "max-documents")

  val ReportFormat = "scholarchain.rag.ingest-run-report"
  val ReportCopyFormat = "scholarchain.rag.ingest-report-copy"

  /** Folder under the semantic-context (`rag/`) prefix that holds the report copies. */
  val ReportCopyFolder = "ingest-reports"

  private val CodeVersionPattern = "[A-Za-z0-9][A-Za-z0-9._-]{0,99}".r

  final class StopRun(msg: String) extends RuntimeException(msg)

  /** @param exportLocation
    *   a local export directory (absolute) or an `s3://` export URI
    * @param maxDocuments
    *   `--max-documents N` (Phase 15, G-27): every run ingests only the first N rendered documents
    *   (render order, which is deterministic), and the retrieval smoke samples only those. The
    *   whole export is still read and rendered. On unchanged data the probe writes nothing (every
    *   document is skipped), so D7 can measure the skip rate on 5,000 documents before the full
    *   pass. None: every document.
    */
  final case class Options(
      config: Path,
      exportLocation: String,
      out: Path,
      runs: Int,
      samplesPerIndex: Int,
      stores: String,
      codeVersion: String,
      maxDocuments: Option[Int] = None
  )

  /** Parses `--name value` pairs; exactly the names of [[Required]] and [[Optional]] are accepted
    * (unknown and repeated names are refused). Messages never repeat a value.
    */
  def parse(args: Array[String]): Either[String, Options] = {
    if (args.length % 2 != 0) Left("arguments must be --name value pairs")
    else {
      val pairs = args.toList.grouped(2).map(a => a.head -> a(1)).toList
      val names = pairs.map(_._1.stripPrefix("--"))
      val notOptions = pairs.map(_._1).filterNot(_.startsWith("--"))
      val known = (Required ++ Optional).toSet
      val unknown = names.filterNot(known)
      val repeated = names.diff(names.distinct).distinct
      val m = pairs.map { case (k, v) => k.stripPrefix("--") -> v }.toMap
      def req(k: String) = m.get(k).toRight(s"--$k is required")
      def int(k: String, d: Int, lo: Int, hi: Int) =
        m.get(k) match {
          case None    => Right(d)
          case Some(v) =>
            v.toIntOption.filter(x => x >= lo && x <= hi).toRight(s"--$k must be in $lo..$hi")
        }
      for {
        _ <- Either.cond(notOptions.isEmpty, (), "arguments must be --name value pairs")
        _ <- Either.cond(
          unknown.isEmpty,
          (),
          s"unknown options: ${unknown.distinct.sorted.map("--" + _).mkString(", ")}"
        )
        _ <- Either.cond(
          repeated.isEmpty,
          (),
          s"repeated options: ${repeated.sorted.map("--" + _).mkString(", ")}"
        )
        c <- req("config")
        e <- req("export")
        o <- req("out")
        code <- req("code-version").filterOrElse(
          CodeVersionPattern.matches,
          "--code-version must match [A-Za-z0-9][A-Za-z0-9._-]{0,99} (the build commit)"
        )
        runs <- int("runs", 2, 1, 3)
        samples <- int("samples-per-index", 25, 1, 500)
        maxDocs <- m.get("max-documents") match {
          case None    => Right(None)
          case Some(v) =>
            v.toIntOption
              .filter(x => x >= 1 && x <= MaxDocumentsBound)
              .map(Some(_))
              .toRight(s"--max-documents must be in 1..$MaxDocumentsBound")
        }
        stores <- Right(m.getOrElse("stores", "local"))
          .filterOrElse(Set("local", "aws"), "--stores must be local or aws")
        _ <- Either.cond(
          !e.startsWith("s3://") || stores == "aws",
          (),
          "an s3:// export needs --stores aws"
        )
      } yield Options(
        Paths.get(c).toAbsolutePath,
        if (e.startsWith("s3://")) e else Paths.get(e).toAbsolutePath.toString,
        Paths.get(o).toAbsolutePath,
        runs,
        samples,
        stores,
        code,
        maxDocs
      )
    }
  }

  /** What one invocation uses besides its options and stores (tests inject both).
    *
    * @param configEnv
    *   the environment whose `SCHOLARCHAIN_CFG_*` overrides [[loadConfig]] applies to the
    *   configuration file; default the process environment, read when the job starts (the
    *   production entry point, [[main]]). Tests pass an explicit map so their outcome never depends
    *   on the shell that runs them (P16-L-16).
    */
  final case class Hooks(
      buildIdentity: () => BuildIdentity = () => BuildIdentity.load(),
      print: String => Unit = (s: String) => println(s),
      configEnv: () => Map[String, String] = () => sys.env
  )

  /** The report and, with stores `aws`, the outcome of its copy to the data lake. */
  final case class JobResult(report: ujson.Obj, reportCopy: Option[ujson.Obj]) {

    /** The report copy, when one was due, was written or was already present. */
    def copyOk: Boolean =
      reportCopy.forall(c => Set("created", "already present").contains(c("outcome").str))
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

    /** The ordinary-S3 store (`s3://` exports are read and the report copy is written here). */
    def objects: ObjectStore
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
    def objects: ObjectStore = env.objects
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
    def objects: ObjectStore = env.objects
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

  /** Exit codes: 0 completed (and, with stores `aws`, the report copy written or already present),
    * 1 failed or stopped (or the report copy failed), 2 refused arguments.
    */
  def main(args: Array[String]): Unit =
    parse(args) match {
      case Left(e) =>
        println(s"[rag-ingest] refused: $e")
        sys.exit(2)
      case Right(opts) =>
        val result = execute(opts)
        if (!result.report("status").str.startsWith("completed") || !result.copyOk) sys.exit(1)
    }

  /** Logical key of the report copy: `<semantic-context prefix>/ingest-reports/<run>/
    * run-report-sha256=<hex>.json`, where `<run>` is `run=<32 hex>` or `no-run` when the run ID was
    * never derived (for example an unreadable export). Content-addressed, so put-if-absent never
    * meets different bytes under one key.
    */
  def reportCopyKey(prefix: String, runId: Option[RunId], sha256Hex: String): String = {
    val run = runId.map(S3KeyLayout.runSegment).getOrElse("no-run")
    s"$prefix/$ReportCopyFolder/$run/run-report-sha256=$sha256Hex.json"
  }

  /** Runs the job and returns the report (also written to `<out>/run-report.json`). */
  def run(
      opts: Options,
      storesFactory: (StorageConfig, String, Path) => Either[String, IngestStores] = defaultStores,
      wiring: EmbeddingProviders.Wiring = EmbeddingProviders.Wiring(),
      hooks: Hooks = Hooks()
  ): ujson.Obj = execute(opts, storesFactory, wiring, hooks).report

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

  /** Runs the job: writes `<out>/run-report.json` and, with stores `aws`, copies the same bytes to
    * the data lake ([[reportCopyKey]]) and writes the copy's outcome to
    * `<out>/run-report-copy.json`.
    */
  def execute(
      opts: Options,
      storesFactory: (StorageConfig, String, Path) => Either[String, IngestStores] = defaultStores,
      wiring: EmbeddingProviders.Wiring = EmbeddingProviders.Wiring(),
      hooks: Hooks = Hooks()
  ): JobResult = {
    if (Files.exists(opts.out)) sys.error(s"${opts.out} exists; every attempt uses a new root")
    Files.createDirectories(opts.out)
    val report = ujson.Obj(
      "format" -> ReportFormat,
      "formatVersion" -> 1,
      "label" -> Label,
      "codeVersion" -> opts.codeVersion,
      "jobCodeVersion" -> CodeVersion,
      "build" -> hooks.buildIdentity().toJson,
      "limits" -> ujson.Obj(
        "maxStepMillis" -> MaxStepMillis.toDouble,
        "maxDocuments" -> opts.maxDocuments.map(n => ujson.Num(n.toDouble)).getOrElse(ujson.Null),
        "runs" -> opts.runs,
        "samplesPerIndex" -> opts.samplesPerIndex
      )
    )
    val steps = ujson.Obj()
    report("steps") = steps
    var masking = new Masking(Nil, Nil)
    def say(line: String): Unit = hooks.print(masking(line))
    def disk(): Long = Files.getFileStore(opts.out).getUsableSpace
    def step[A](name: String)(f: => A): A = {
      if (disk() < MinFreeBytes) throw new StopRun(s"free disk below 15 GiB at start of $name")
      val t0 = System.nanoTime()
      say(s"[rag-ingest] step $name started")
      val a = f
      val ms = (System.nanoTime() - t0) / 1000000L
      say(s"[rag-ingest] step $name finished in $ms ms")
      steps(name) = ujson.Obj("millis" -> ms.toDouble, "freeDiskBytesAfter" -> disk().toDouble)
      if (ms > MaxStepMillis)
        throw new StopRun(s"step $name took $ms ms (> ${MaxStepMillis / 60000L} minutes)")
      a
    }
    def write(): Array[Byte] = {
      val bytes = masking(report.render(indent = 2)).getBytes(StandardCharsets.UTF_8)
      Files.write(opts.out.resolve("run-report.json"), bytes)
      bytes
    }

    val t0 = System.nanoTime()
    val renderRejects = new RejectLog(Some(opts.out.resolve("render-rejects.jsonl")))
    var env: IngestStores = null
    var storageCfg: Option[StorageConfig] = None
    var runIdOpt: Option[RunId] = None
    var copy: Option[ujson.Obj] = None
    try {
      val config = loadConfig(opts.config, hooks.configEnv()).fold(e => sys.error(e), identity)
      val cfg = StorageConfig.from(config).fold(e => sys.error(e.mkString("; ")), identity)
      masking = new Masking(
        cfg.zones.values.map(_.bucket) ++ List(cfg.vectorBucket),
        List(config.aws.accountId)
      )
      storageCfg = Some(cfg)
      val provider = cfg.embedding.providerKind.fold(e => sys.error(e), identity)
      checkProviderAndStores(provider, opts.stores).fold(e => sys.error(e), identity)
      val doc =
        EmbeddingProviders
          .create(config, InputType.Document, wiring)
          .fold(e => sys.error(e), identity)
      val query =
        EmbeddingProviders.create(config, InputType.Query, wiring).fold(e => sys.error(e), identity)

      env = storesFactory(cfg, opts.stores, opts.out.resolve("ingest-rejects.jsonl"))
        .fold(e => sys.error(e), identity)
      val src = ExportSources
        .open(opts.exportLocation, Some(env.objects))
        .fold(e => sys.error(s"--export: $e"), identity)
      src match {
        case s3: S3ExportSource if !cfg.zones.values.exists(_.bucket == s3.root.bucket) =>
          sys.error("--export must be in a bucket of the configured data-lake zones")
        case _ => ()
      }
      val (manifest, manifestSha) = src.manifestWithSha256()
      val present = src.checkPresence(manifest)
      val runId = RunId
        .of(JobName, CodeVersion, s"sha256:$manifestSha", config.fingerprint)
        .fold(e => sys.error(e), identity)
      runIdOpt = Some(runId)
      report("input") = ujson.Obj(
        "export" -> src.describe,
        "exportKind" -> (if (opts.exportLocation.startsWith("s3://")) "s3" else "local"),
        "exportManifestSha256" -> manifestSha,
        "sourceRunId" -> manifest.sourceRunId.value,
        "files" -> present.files,
        "bytes" -> present.bytes.toDouble,
        "checks" -> "manifest present; every listed file present with its byte count; bytes and sha256 checked as each file is read",
        "configFile" -> opts.config.getFileName.toString,
        "configFingerprint" -> config.fingerprint
      )
      report("environment") = ujson.Obj(
        "heapMaxBytes" -> Runtime.getRuntime.maxMemory.toDouble,
        "processors" -> Runtime.getRuntime.availableProcessors,
        "freeDiskBytesAtStart" -> disk().toDouble,
        "minFreeDiskBytes" -> MinFreeBytes.toDouble,
        "stores" -> opts.stores,
        "storesDescription" -> env.description
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

      @volatile var corpus: RenderedCorpus = null
      val pipeline = env.pipeline(doc.port, SourceDataset, extrasOf = d => corpus.extrasOf(d))
      val builder = new ExportDocuments(src, manifest, cfg.vectors, renderRejects)
      val runs = ujson.Arr()
      report("runs") = runs
      var reports = List.empty[IngestReport]
      var digests = List.empty[String]
      // --max-documents: the first N rendered documents (render order is deterministic)
      def selected(c: RenderedCorpus) = opts.maxDocuments.fold(c.documents)(c.documents.take)
      (1 to opts.runs).foreach { n =>
        corpus = null
        corpus = step(s"render-run-$n")(builder.renderAll())
        digests :+= corpus.digest
        val docs = selected(corpus)
        val docsPerIndex = docs.groupMapReduce(_.indexName)(_ => 1L)(_ + _)
        val usageBefore = doc.usage.requests
        val r = step(s"ingest-run-$n") {
          val s = pipeline.open(runId, s"rag-ingest run $n")
          s.ingest(docs.iterator)
          s.finish()
        }
        reports :+= r
        val total = r.millis.getOrElse("total", 0L)
        runs.value += ujson.Obj(
          "run" -> n,
          "corpusDigest" -> corpus.digest,
          "documents" -> corpus.documents.size.toDouble,
          "documentsIngested" -> docs.size.toDouble,
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
        val byIndex = selected(corpus).groupBy(_.indexName)
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
      report("totalMillis") = ((System.nanoTime() - t0) / 1000000L).toDouble
      val bytes = write()
      try {
        if (opts.stores == "aws") {
          val c = copyReport(bytes, storageCfg, Option(env), runIdOpt, masking)
          Files.write(
            opts.out.resolve("run-report-copy.json"),
            masking(c.render(indent = 2)).getBytes(StandardCharsets.UTF_8)
          )
          say(s"[rag-ingest] report copy: ${c("outcome").str} ${c("object").str}")
          copy = Some(c)
        }
      } finally if (env != null) env.close()
    }
    say(s"[rag-ingest] ${report("status").str}; report: ${opts.out.resolve("run-report.json")}")
    JobResult(report, copy)
  }

  /** Copies the report bytes to the data lake's semantic-context (`rag/`) prefix, put-if-absent.
    * The result names the object by its logical location (`s3://{data-bucket}/KEY`); it never fails
    * the job silently: every outcome is recorded.
    */
  private def copyReport(
      bytes: Array[Byte],
      cfg: Option[StorageConfig],
      env: Option[IngestStores],
      runId: Option[RunId],
      masking: Masking
  ): ujson.Obj = {
    val sha = Hashing.sha256Hex(bytes)
    val base = ujson.Obj(
      "format" -> ReportCopyFormat,
      "formatVersion" -> 1,
      "sha256" -> sha,
      "bytes" -> bytes.length.toDouble
    )
    def outcome(obj: String, o: String): ujson.Obj = {
      base("object") = obj
      base("outcome") = o
      base
    }
    (cfg.flatMap(_.zones.get("semantic-context")), env) match {
      case (None, _) =>
        outcome("-", "not attempted: the semantic-context zone is not configured")
      case (_, None)                  => outcome("-", "not attempted: the stores were not built")
      case (Some(zone), Some(stores)) =>
        val ref = ObjectRef(zone.bucket, reportCopyKey(zone.prefix, runId, sha))
        val logical = s"s3://${Masking.DataBucket}/${ref.key}"
        try
          stores.objects.putIfAbsent(
            ref,
            bytes,
            "application/json",
            Map(ObjectStore.Sha256MetadataKey -> sha)
          ) match {
            case PutOutcome.Created        => outcome(logical, "created")
            case PutOutcome.AlreadyPresent => outcome(logical, "already present")
            case PutOutcome.Conflict(_)    =>
              outcome(logical, "conflict: an object with other bytes exists; nothing written")
          }
        catch {
          case NonFatal(e) =>
            outcome(
              logical,
              masking(s"failed: ${e.getClass.getSimpleName}: ${Option(e.getMessage).getOrElse("")}")
            )
        }
    }
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
