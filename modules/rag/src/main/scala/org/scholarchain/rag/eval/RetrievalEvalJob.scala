package org.scholarchain.rag.eval

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import scala.util.control.NonFatal

import org.scholarchain.core.{Hashing, RunId}
import org.scholarchain.core.config.{EmbeddingProvider, ScholarChainConfig}
import org.scholarchain.rag.embedding.{EmbeddingProviders, InputType}
import org.scholarchain.rag.ingest.RagIngestJob
import org.scholarchain.rag.retrieval.SemanticRetriever
import org.scholarchain.rag.runtime.{RetrievalModes, RuntimeContext, RuntimeService}
import org.scholarchain.storage.StorageConfig
import org.scholarchain.storage.aws.AwsStorageEnvironment
import org.scholarchain.storage.load.{BuildIdentity, Masking}
import org.scholarchain.storage.ports.{ObjectRef, ObjectStore, PutOutcome}
import org.scholarchain.storage.runtime.RuntimeStores

/** `rag-retrieval-eval` (Phase 15, decision 7.4; experiment E): runs the packaged evaluation set
  * ([[RetrievalEvalSet]], derived from benchmark-v1) in three retrieval modes, graph-only,
  * vector-only and fused ([[RetrievalModes]]), with no LLM call, and writes one report:
  * `<out>/report.json` and a byte-identical copy at
  * `<semantic-context prefix>/retrieval-eval/<attempt-id>/report.json` (logical
  * `s3://{data-bucket}/rag/retrieval-eval/ATTEMPT/report.json` in the course configuration),
  * put-if-absent, with the copy's outcome in `<out>/report-copy.json`. One summary line per mode
  * goes to standard output (`[rag-retrieval-eval] summary mode=...`). Bucket names and the account
  * ID are masked in every report and printed line.
  *
  * {{{
  * java -cp app.jar org.scholarchain.rag.eval.RetrievalEvalJob --config FILE --out DIR
  *     --code-version SHA40 --attempt-id ID --graph-run RUNID
  *     [--modes graph-only,vector-only,fused] [--max-seconds 1200] [--question-timeout-seconds 60]
  * }}}
  *
  * Stores: AWS only (storage's [[AwsStorageEnvironment]]: Neptune reads, DynamoDB reads, S3 Vectors
  * queries, the S3 report write); the query embedder of `embedding.provider` (voyage; the fake is
  * refused for AWS stores). Building them makes no network call.
  *
  * Exit codes: 0 completed and the report copy created or already present; 1 failed, stopped by the
  * total time limit, or the copy failed (the local report is still written); 2 refused arguments.
  */
object RetrievalEvalJob {
  val JobName = "rag.retrieval-eval"
  val ReportFormat = "scholarchain.rag.retrieval-eval-report"
  val ReportCopyFormat = "scholarchain.rag.retrieval-eval-report-copy"

  /** Folder under the semantic-context (`rag/`) prefix that holds the report copies. */
  val ReportFolder = "retrieval-eval"

  val Required: List[String] = List("config", "out", "code-version", "attempt-id", "graph-run")
  val Optional: List[String] = List("modes", "max-seconds", "question-timeout-seconds")

  val DefaultMaxSeconds = 1200
  val MaxMaxSeconds = 3600
  val DefaultQuestionTimeoutSeconds = 60
  val MaxQuestionTimeoutSeconds = 300

  /** Largest K of the metrics (and of the vector query). */
  val TopK = 30

  private val TokenPattern = "[A-Za-z0-9][A-Za-z0-9._-]{0,99}".r

  final case class Options(
      config: Path,
      out: Path,
      codeVersion: String,
      attemptId: String,
      graphRun: RunId,
      modes: List[String],
      maxSeconds: Int,
      questionTimeoutSeconds: Int
  )

  /** Parses `--name value` pairs; exactly [[Required]] and [[Optional]] (unknown and repeated names
    * refused). Messages never repeat a value.
    */
  def parse(args: Array[String]): Either[String, Options] =
    if (args.length % 2 != 0) Left("arguments must be --name value pairs")
    else {
      val pairs = args.toList.grouped(2).map(a => a.head -> a(1)).toList
      val names = pairs.map(_._1.stripPrefix("--"))
      val known = (Required ++ Optional).toSet
      val m = pairs.map { case (k, v) => k.stripPrefix("--") -> v }.toMap
      def req(k: String) = m.get(k).toRight(s"--$k is required")
      def int(k: String, d: Int, lo: Int, hi: Int) =
        m.get(k) match {
          case None    => Right(d)
          case Some(v) =>
            v.toIntOption.filter(x => x >= lo && x <= hi).toRight(s"--$k must be in $lo..$hi")
        }
      for {
        _ <- Either.cond(
          pairs.forall(_._1.startsWith("--")),
          (),
          "arguments must be --name value pairs"
        )
        unknown = names.filterNot(known)
        _ <- Either.cond(
          unknown.isEmpty,
          (),
          s"unknown options: ${unknown.distinct.sorted.map("--" + _).mkString(", ")}"
        )
        repeated = names.diff(names.distinct).distinct
        _ <- Either.cond(
          repeated.isEmpty,
          (),
          s"repeated options: ${repeated.sorted.map("--" + _).mkString(", ")}"
        )
        c <- req("config")
        o <- req("out")
        code <- req("code-version").filterOrElse(
          TokenPattern.matches,
          "--code-version must match [A-Za-z0-9][A-Za-z0-9._-]{0,99} (the build commit)"
        )
        attempt <- req("attempt-id").filterOrElse(
          TokenPattern.matches,
          "--attempt-id must match [A-Za-z0-9][A-Za-z0-9._-]{0,99}"
        )
        g <- req("graph-run").flatMap(v =>
          RunId.parse(v).left.map(_ => "--graph-run must be a run ID (run:v1:<32 hex>)")
        )
        modes <- m.get("modes") match {
          case None    => Right(RetrievalEvalSet.Modes)
          case Some(v) =>
            val ms = v.split(',').toList.map(_.trim)
            if (
              ms.isEmpty || ms
                .exists(x => !RetrievalEvalSet.Modes.contains(x)) || ms.distinct.size != ms.size
            )
              Left("--modes must be a comma list of graph-only, vector-only, fused")
            else if (
              ms.contains(RetrievalEvalSet.Fused) && !(ms.contains(RetrievalEvalSet.GraphOnly) && ms
                .contains(RetrievalEvalSet.VectorOnly))
            )
              Left("--modes: fused needs graph-only and vector-only")
            else Right(RetrievalEvalSet.Modes.filter(ms.contains))
        }
        maxS <- int("max-seconds", DefaultMaxSeconds, 60, MaxMaxSeconds)
        qS <- int(
          "question-timeout-seconds",
          DefaultQuestionTimeoutSeconds,
          5,
          MaxQuestionTimeoutSeconds
        )
      } yield Options(
        Paths.get(c).toAbsolutePath,
        Paths.get(o).toAbsolutePath,
        code,
        attempt,
        g,
        modes,
        maxS,
        qS
      )
    }

  /** The stores one evaluation reads (and where the report copy goes). */
  trait EvalStores extends AutoCloseable {
    def kind: String
    def runtime: RuntimeStores
    def objects: ObjectStore
  }

  /** storage's AWS environment. Building makes no network call. */
  val awsStores: (ScholarChainConfig, StorageConfig) => Either[String, EvalStores] =
    (config, cfg) =>
      try {
        val env = AwsStorageEnvironment.fromConfig(
          cfg,
          RuntimeService.defaultAwsClientSettings(config),
          None
        )
        env.runtimeStores match {
          case Left(e)   => env.close(); Left(e)
          case Right(rs) =>
            Right(new EvalStores {
              val kind = "aws"
              val runtime: RuntimeStores = rs
              val objects: ObjectStore = env.objects
              def close(): Unit = env.close()
            })
        }
      } catch {
        case NonFatal(e) => Left(s"AWS stores could not be built: ${e.getClass.getSimpleName}")
      }

  final case class Hooks(
      buildIdentity: () => BuildIdentity = () => BuildIdentity.load(),
      print: String => Unit = (s: String) => println(s),
      evalSet: () => Either[String, RetrievalEvalSet] = () => RetrievalEvalSet.load(),
      clock: () => Long = () => System.nanoTime()
  )

  final case class JobResult(report: ujson.Obj, reportCopy: ujson.Obj) {
    def copyOk: Boolean = Set("created", "already present").contains(reportCopy("outcome").str)
    def completed: Boolean = report("status").str == "completed"
  }

  def main(args: Array[String]): Unit =
    parse(args) match {
      case Left(e) =>
        println(s"[rag-retrieval-eval] refused: $e")
        sys.exit(2)
      case Right(opts) =>
        val r = execute(opts)
        if (!r.completed || !r.copyOk) sys.exit(1)
    }

  /** Logical key of the report copy. */
  def reportKey(prefix: String, attemptId: String): String =
    s"$prefix/$ReportFolder/$attemptId/report.json"

  def execute(
      opts: Options,
      storesFactory: (ScholarChainConfig, StorageConfig) => Either[String, EvalStores] = awsStores,
      wiring: EmbeddingProviders.Wiring = EmbeddingProviders.Wiring(),
      hooks: Hooks = Hooks()
  ): JobResult = {
    if (Files.exists(opts.out)) sys.error(s"${opts.out} exists; every attempt uses a new root")
    Files.createDirectories(opts.out)
    val t0 = hooks.clock()
    var masking = new Masking(Nil, Nil)
    def say(line: String): Unit = hooks.print(masking(line))
    val report = ujson.Obj(
      "format" -> ReportFormat,
      "formatVersion" -> 1,
      "job" -> JobName,
      "codeVersion" -> opts.codeVersion,
      "build" -> hooks.buildIdentity().toJson,
      "attemptId" -> opts.attemptId,
      "graphRunId" -> opts.graphRun.value,
      "llmCalls" -> 0,
      "limits" -> ujson.Obj(
        "maxSeconds" -> opts.maxSeconds,
        "questionTimeoutSeconds" -> opts.questionTimeoutSeconds,
        "topK" -> TopK,
        "ks" -> ujson.Arr.from(RetrievalMetrics.Ks.map(ujson.Num(_)))
      ),
      "modesRequested" -> ujson.Arr.from(opts.modes.map(ujson.Str(_))),
      "fusion" -> ujson.Obj(
        "version" -> RetrievalModes.FusionVersion,
        "k" -> RetrievalModes.FusionK,
        "label" -> RetrievalModes.FusionLabel
      )
    )
    var stores: EvalStores = null
    var embedder: Option[AutoCloseable] = None
    var cfgOpt: Option[StorageConfig] = None
    var copy: ujson.Obj =
      ujson.Obj("format" -> ReportCopyFormat, "outcome" -> "not attempted", "object" -> "-")
    try {
      val config = RagIngestJob.loadConfig(opts.config).fold(e => sys.error(e), identity)
      val cfg = StorageConfig.from(config).fold(e => sys.error(e.mkString("; ")), identity)
      cfgOpt = Some(cfg)
      masking = new Masking(
        cfg.zones.values.map(_.bucket) ++ List(cfg.vectorBucket),
        List(config.aws.accountId)
      )
      val set = hooks.evalSet().fold(e => sys.error(e), identity)
      report("label") =
        if (set.exploratory)
          s"EXPLORATORY: ${set.questions.size} questions in the universe (fewer than ${RetrievalEvalSet.MinNonExploratory})"
        else s"${set.questions.size} questions in the universe"
      report("exploratory") = set.exploratory
      report("evalSet") = ujson.Obj(
        "benchmarkId" -> set.benchmarkId,
        "benchmarkSha256" -> set.benchmarkSha256,
        "rule" -> ujson.Arr.from(set.rule.map(ujson.Str(_))),
        "questions" -> set.questions.size,
        "labels" -> set.labelCount,
        "notComparableCount" -> set.notComparable.size,
        "notComparable" -> ujson.Arr.from(set.notComparable.map(_.toJson))
      )
      val query =
        EmbeddingProviders.create(config, InputType.Query, wiring).fold(e => sys.error(e), identity)
      query.port match {
        case c: AutoCloseable => embedder = Some(c)
        case _                => ()
      }
      stores = storesFactory(config, cfg).fold(e => sys.error(e), identity)
      if (stores.kind == "aws" && query.spec.provider == EmbeddingProvider.Fake)
        sys.error("the fake embedder is refused with AWS stores")
      report("embedding") = ujson.Obj("queryModelSpec" -> query.spec.id)
      report("stores") = stores.kind
      val ctx = RuntimeContext
        .from(config, opts.graphRun, config.graph.windows.map(_.label).toSet)
        .fold(e => sys.error(e), identity)
      val pathIndex = cfg.vectors.collaborationPathIndex
      val retriever =
        new SemanticRetriever(query.port, query.spec, stores.runtime.vectors, cfg.vectors)
      val modes = new RetrievalModes(stores.runtime, ctx, Some(retriever), pathIndex)
      val topK = math.min(TopK, cfg.vectors.queryTopK)
      val result = RetrievalEval.run(
        set,
        modes,
        EvalOptions(opts.modes, topK, opts.maxSeconds * 1000L, opts.questionTimeoutSeconds * 1000L),
        hooks.clock,
        say
      )
      result.report.value.foreach { case (k, v) => report(k) = v }
      report("embedderUsage") = query.usage.toJson
      report("status") = result.stopped.map(s => s"stopped: $s").getOrElse("completed")
    } catch {
      // only this job's own refusals (sys.error) carry their text; any other exception is named by
      // its class (store and SDK messages can hold hosts or ARNs)
      case NonFatal(e) if e.getClass == classOf[RuntimeException] =>
        report("status") =
          s"failed: ${e.getClass.getSimpleName}: ${Option(e.getMessage).getOrElse("").take(300)}"
      case NonFatal(e) => report("status") = s"failed: ${e.getClass.getSimpleName}"
    } finally {
      embedder.foreach(c =>
        try c.close()
        catch { case NonFatal(_) => () }
      )
      report("totalMillis") = ((hooks.clock() - t0) / 1000000L).toDouble
      val bytes = masking(report.render(indent = 2)).getBytes(StandardCharsets.UTF_8)
      Files.write(opts.out.resolve("report.json"), bytes)
      try {
        copy = copyReport(bytes, cfgOpt, Option(stores), opts.attemptId, masking)
        Files.write(
          opts.out.resolve("report-copy.json"),
          masking(copy.render(indent = 2)).getBytes(StandardCharsets.UTF_8)
        )
        say(s"[rag-retrieval-eval] report copy: ${copy("outcome").str} ${copy("object").str}")
      } finally if (stores != null) stores.close()
    }
    say(s"[rag-retrieval-eval] ${report("status").str}; report: ${opts.out.resolve("report.json")}")
    JobResult(report, copy)
  }

  private def copyReport(
      bytes: Array[Byte],
      cfg: Option[StorageConfig],
      stores: Option[EvalStores],
      attemptId: String,
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
      base("object") = obj; base("outcome") = o; base
    }
    (cfg.flatMap(_.zones.get("semantic-context")), stores) match {
      case (None, _) => outcome("-", "not attempted: the semantic-context zone is not configured")
      case (_, None) => outcome("-", "not attempted: the stores were not built")
      case (Some(zone), Some(s)) =>
        val ref = ObjectRef(zone.bucket, reportKey(zone.prefix, attemptId))
        val logical = s"s3://${Masking.DataBucket}/${ref.key}"
        try
          s.objects.putIfAbsent(
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
          case NonFatal(e) => outcome(logical, masking(s"failed: ${e.getClass.getSimpleName}"))
        }
    }
  }
}
