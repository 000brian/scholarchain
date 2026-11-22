package org.scholarchain.storage.load

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.time.Instant

import scala.collection.immutable.ListMap
import scala.util.control.NonFatal

import org.scholarchain.core.{Hashing, RunId}
import org.scholarchain.core.config.{ConfigLoader, ScholarChainConfig}
import org.scholarchain.storage.{Sleeper, StorageConfig, StorageVersion}
import org.scholarchain.storage.dynamo.{ArtifactRegistry, ArtifactVersion, Change, DynamoLoader}
import org.scholarchain.storage.neptune.{BulkLoadRequests, NeptuneBuildSettings, NeptuneCsvBuilder}
import org.scholarchain.storage.ports.{
  AttrValue,
  BulkLoadStartRejected,
  ObjectRef,
  ObjectStore,
  PutOutcome
}
import org.scholarchain.storage.s3.{ManifestEntry, RunManifest, S3KeyLayout}
import org.scholarchain.storage.source.{ExportSource, ExportSources, RejectLog, S3ExportSource}

/** The option names of one subcommand (without the leading `--`). */
final case class SubcommandOptions(required: List[String], optional: List[String]) {
  def all: List[String] = required ++ optional
}

/** A refused invocation (arguments, configuration or a safety rule): exit code 2. */
final class LoadRefused(message: String) extends RuntimeException(message)

/** A bulk-load status check that ended before the load finished: exit code 3. */
final class LoadNotFinished(message: String) extends RuntimeException(message)

/** Storage's loader entry point (Phase 13, decision 7a). The `ops` task runs it as
  * {{{
  * java -cp /opt/scholarchain/app.jar org.scholarchain.storage.load.StorageLoadMain SUBCOMMAND --name value ...
  * }}}
  * Subcommands and their options are [[StorageLoadMain.Options]] (the contract the scripts are
  * written from); an unknown subcommand, an unknown, repeated or missing option is refused.
  *
  *   - `dynamodb-load`: loads the DynamoDB items of a Spark JSON Lines export ([[DynamoLoader]]:
  *     conditional upsert-if-changed writes, append-only RunLedger audit), `--passes` times. Every
  *     export file is read in full and its size and sha256 checked before the first write.
  *   - `neptune-csv-build`: builds the Neptune openCypher CSV files of an export under `--out`,
  *     uploads them put-if-absent to the graph zone (`<graph>/dataset=<d>/snapshot=<s>/run=<hex>/
  *     neptune/{nodes,edges}/`), then writes the run manifest last. The run ID is derived from the
  *     export manifest's sha256 and the configuration fingerprint, so a rerun writes the same keys
  *     and finds every object already present.
  *   - `neptune-bulk-start`: checks the graph run (manifest present, every object present with its
  *     size), starts the `nodes` load and then the `edges` load (depending on the nodes load), and
  *     registers each step in the ArtifactRegistry before the next starts (a `starting` claim, the
  *     nodes load ID, an `edges-starting` claim, then both load IDs); a start the loader refused
  *     for certain is recorded (`nodes-failed`, `edges-failed`) and may be started again; a retry
  *     after the nodes start starts the edges load only; a graph run already started is refused,
  *     and so is a claim whose start has an unknown outcome (the load may exist in Neptune).
  *   - `neptune-bulk-status`: the status of one load ID, optionally waiting up to `--wait-seconds`.
  *
  * Common rules: `--config` is the configuration file plus the `SCHOLARCHAIN_CFG_*` overrides (as
  * the services load it); `--stores local` (file-system object store under `--local-root`,
  * in-memory tables, recording bulk loader; for tests) or `--stores aws` (store names from the
  * configuration, credentials from the runtime role); `--out` must not exist (a new root per
  * attempt) and receives `run-report.json`; `--code-version` is recorded in the report with the
  * jar's [[BuildIdentity]]. Everything printed or written is masked ([[Masking]]): no bucket name,
  * no account ID. AWS mode refuses any setting that would start more than one writer (Phase 7 m3).
  *
  * Exit codes ([[StorageLoadMain.Exit]]): 0 completed, 1 failed, 2 refused, 3 load not finished.
  */
object StorageLoadMain {

  val DynamoDbLoad = "dynamodb-load"
  val NeptuneCsvBuild = "neptune-csv-build"
  val NeptuneBulkStart = "neptune-bulk-start"
  val NeptuneBulkStatus = "neptune-bulk-status"

  /** The contract: every subcommand and its option names (without `--`). */
  val Options: ListMap[String, SubcommandOptions] = ListMap(
    DynamoDbLoad -> SubcommandOptions(
      required = List("config", "stores", "export", "out", "code-version"),
      optional = List("local-root", "parallelism", "passes", "ranking-top-k")
    ),
    NeptuneCsvBuild -> SubcommandOptions(
      required =
        List("config", "stores", "export", "out", "code-version", "dataset-id", "snapshot-id"),
      optional = List("local-root", "rows-per-file", "full-window")
    ),
    NeptuneBulkStart -> SubcommandOptions(
      required = List("config", "stores", "graph-run", "loader-role-arn", "out", "code-version"),
      optional = List("local-root")
    ),
    NeptuneBulkStatus -> SubcommandOptions(
      required = List("config", "stores", "load-id", "out", "code-version"),
      optional = List("local-root", "wait-seconds")
    )
  )

  object Exit {
    val Completed = 0
    val Failed = 1
    val Refused = 2
    val NotFinished = 3
  }

  val ReportFormat = "scholarchain.storage.load-report"
  val DynamoDbJob = "storage.dynamodb-load"
  val NeptuneCsvJob = "storage.neptune-csv"
  val NeptuneLoadArtifact = "neptune-load"

  /** Free disk `neptune-csv-build` needs under `--out` before it starts. */
  val MinFreeBytesCsvBuild: Long = 2L * 1024 * 1024 * 1024

  /** Pause between two status checks of `neptune-bulk-status --wait-seconds`. */
  val StatusPollMillis: Long = 15000L

  /** Neptune loader states that are not final (AWS Neptune Loader feed codes). */
  val PendingStatuses: Set[String] =
    Set("LOAD_NOT_STARTED", "LOAD_IN_QUEUE", "LOAD_IN_PROGRESS", "LOAD_COMMITTED_W_WRITE_CONFLICTS")
  val CompletedStatus = "LOAD_COMPLETED"

  private val CodeVersion = "[A-Za-z0-9][A-Za-z0-9._-]{0,99}".r
  private val Segment = "[A-Za-z0-9][A-Za-z0-9._=-]{0,99}".r
  private val LoadId = "[A-Za-z0-9][A-Za-z0-9-]{0,99}".r

  /** A parsed invocation: the subcommand and its option values (names without `--`). */
  final case class Invocation(subcommand: String, values: Map[String, String]) {
    def get(k: String): Option[String] = values.get(k)
    def apply(k: String): String = values(k)
  }

  /** Checks the shape of the arguments against [[Options]]; values are checked by [[settings]]. */
  def parse(args: Seq[String]): Either[String, Invocation] =
    args.toList match {
      case Nil =>
        Left(s"a subcommand is required: ${Options.keys.mkString(", ")}")
      case sub :: rest =>
        Options.get(sub) match {
          case None =>
            Left(s"unknown subcommand (expected one of ${Options.keys.mkString(", ")})")
          case Some(spec) =>
            if (rest.size % 2 != 0) Left("options must be --name value pairs")
            else {
              val pairs = rest.grouped(2).map(p => p.head -> p(1)).toList
              val bad = pairs.map(_._1).filterNot(_.startsWith("--"))
              val names = pairs.map(_._1.stripPrefix("--"))
              val dup = names.diff(names.distinct).distinct
              val unknown = names.filterNot(spec.all.contains)
              val missing = spec.required.filterNot(names.contains)
              if (bad.nonEmpty) Left("options must be --name value pairs")
              else if (unknown.nonEmpty)
                Left(s"unknown options for $sub: ${unknown.map("--" + _).mkString(", ")}")
              else if (dup.nonEmpty)
                Left(s"repeated options: ${dup.map("--" + _).mkString(", ")}")
              else if (missing.nonEmpty)
                Left(s"missing options for $sub: ${missing.map("--" + _).mkString(", ")}")
              else
                Right(Invocation(sub, pairs.map { case (k, v) => k.stripPrefix("--") -> v }.toMap))
            }
        }
    }

  /** Checked values of one invocation. */
  final case class Settings(
      subcommand: String,
      config: Path,
      stores: String,
      out: Path,
      codeVersion: String,
      localRoot: Option[Path],
      exportLocation: Option[String],
      parallelism: Int,
      passes: Int,
      rankingTopK: Option[Int],
      datasetId: Option[String],
      snapshotId: Option[String],
      rowsPerFile: Int,
      fullWindow: Option[String],
      graphRun: Option[String],
      loaderRoleArn: Option[String],
      loadId: Option[String],
      waitSeconds: Int
  )

  private def intIn(inv: Invocation, k: String, d: Int, lo: Int, hi: Int): Either[String, Int] =
    inv.get(k) match {
      case None    => Right(d)
      case Some(v) =>
        v.toIntOption
          .filter(x => x >= lo && x <= hi)
          .toRight(s"--$k must be an integer in $lo..$hi")
    }

  /** Value checks, including the one-writer rule of AWS mode. Messages never repeat a value that
    * may be a bucket, an ARN or a path.
    */
  def settings(inv: Invocation): Either[String, Settings] =
    for {
      stores <- Either.cond(
        Set("local", "aws").contains(inv("stores")),
        inv("stores"),
        "--stores must be local or aws"
      )
      localRoot <- (stores, inv.get("local-root")) match {
        case ("local", None)    => Left("--local-root is required with --stores local")
        case ("local", Some(r)) => Right(Some(Paths.get(r).toAbsolutePath))
        case (_, Some(_))       => Left("--local-root is only for --stores local")
        case (_, None)          => Right(None)
      }
      code <- Either.cond(
        CodeVersion.matches(inv("code-version")),
        inv("code-version"),
        "--code-version must match [A-Za-z0-9][A-Za-z0-9._-]{0,99} (the build commit)"
      )
      parallelism <- intIn(inv, "parallelism", 1, 1, 64)
      _ <- Either.cond(
        stores != "aws" || parallelism == 1,
        (),
        "--parallelism above 1 starts more than one writer; AWS mode allows one writer only (Phase 7 m3: concurrent conditional writers are not proven safe on AWS)"
      )
      passes <- intIn(inv, "passes", 1, 1, 2)
      topK <- inv.get("ranking-top-k") match {
        case None    => Right(None)
        case Some(v) =>
          v.toIntOption
            .filter(x => x >= 1 && x <= 500)
            .map(Some(_))
            .toRight("--ranking-top-k must be an integer in 1..500")
      }
      rows <- intIn(inv, "rows-per-file", 500000, 1000, 5000000)
      wait <- intIn(inv, "wait-seconds", 0, 0, 3600)
      _ <- List("dataset-id", "snapshot-id").find(k =>
        inv.get(k).exists(v => !Segment.matches(v))
      ) match {
        case Some(k) => Left(s"--$k must match [A-Za-z0-9][A-Za-z0-9._=-]*")
        case None    => Right(())
      }
      _ <- Either.cond(
        inv.get("load-id").forall(LoadId.matches),
        (),
        "--load-id must match [A-Za-z0-9][A-Za-z0-9-]*"
      )
      _ <- Either.cond(
        stores != "aws" || inv.get("export").forall(_.startsWith("s3://")),
        (),
        "--export must be an s3:// location with --stores aws"
      )
    } yield Settings(
      inv.subcommand,
      Paths.get(inv("config")).toAbsolutePath,
      stores,
      Paths.get(inv("out")).toAbsolutePath,
      code,
      localRoot,
      inv.get("export"),
      parallelism,
      passes,
      topK,
      inv.get("dataset-id"),
      inv.get("snapshot-id"),
      rows,
      inv.get("full-window"),
      inv.get("graph-run"),
      inv.get("loader-role-arn"),
      inv.get("load-id"),
      wait
    )

  /** The configuration file plus the `SCHOLARCHAIN_CFG_*` overrides of `env` (environment over
    * file, as core's loader and the services do).
    */
  def loadConfig(path: Path, env: Map[String, String]): Either[String, ScholarChainConfig] =
    try {
      val text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
      ConfigLoader
        .load(text, path.getFileName.toString, env.filter(_._1.startsWith(ConfigLoader.EnvPrefix)))
        .left
        .map(_.message)
        .map(_.config)
    } catch {
      case NonFatal(e) => Left(s"configuration file unreadable: ${e.getClass.getSimpleName}")
    }

  /** Everything one invocation needs besides its arguments (tests inject stores and a sleeper). */
  final case class Wiring(
      env: Map[String, String] = sys.env,
      stores: (String, StorageConfig, Option[Path]) => Either[String, LoadStores] =
        LoadStores.default,
      sleeper: Sleeper = Sleeper.real,
      buildIdentity: () => BuildIdentity = () => BuildIdentity.load(),
      print: String => Unit = (s: String) => println(s)
  )

  def main(args: Array[String]): Unit = {
    val code = run(args.toIndexedSeq)
    if (code != Exit.Completed) sys.exit(code)
  }

  /** Runs one invocation and returns its exit code. Writes `<out>/run-report.json` once `--out` is
    * created, and prints one masked `[storage-load] report` line with the same JSON.
    */
  def run(args: Seq[String], wiring: Wiring = Wiring()): Int = {
    val inv = parse(args).flatMap(settings)
    inv match {
      case Left(err) =>
        wiring.print(s"[storage-load] refused: $err")
        Exit.Refused
      case Right(s) if Files.exists(s.out) =>
        wiring.print("[storage-load] refused: --out exists; every attempt uses a new root")
        Exit.Refused
      case Right(s) => runChecked(s, wiring)
    }
  }

  private def runChecked(s: Settings, wiring: Wiring): Int = {
    Files.createDirectories(s.out)
    val t0 = System.nanoTime()
    val report = ujson.Obj(
      "format" -> ReportFormat,
      "formatVersion" -> 1,
      "subcommand" -> s.subcommand,
      "codeVersion" -> s.codeVersion,
      "build" -> wiring.buildIdentity().toJson,
      "storageCodeVersion" -> StorageVersion.Code,
      "stores" -> s.stores,
      "configFile" -> s.config.getFileName.toString,
      "startedAtUtc" -> Instant.now().toString
    )
    var masking = new Masking(Nil, Nil)
    var stores: LoadStores = null
    val rejects = new RejectLog(Some(s.out.resolve("rejects.jsonl")))
    val code =
      try {
        val config = loadConfig(s.config, wiring.env).fold(e => throw new LoadRefused(e), identity)
        val cfg = StorageConfig
          .from(config)
          .fold(e => throw new LoadRefused(e.mkString("; ")), identity)
        masking = new Masking(
          cfg.zones.values.map(_.bucket) ++ List(cfg.vectorBucket),
          List(config.aws.accountId)
        )
        report("configFingerprint") = cfg.fingerprint
        report("region") = cfg.region
        stores =
          wiring.stores(s.stores, cfg, s.localRoot).fold(e => throw new LoadRefused(e), identity)
        report("storesDescription") = stores.description
        report("writers") = ujson.Obj(
          "parallelism" -> s.parallelism,
          "rule" -> "one writer on AWS (Phase 7 m3); local stores may use --parallelism up to 64"
        )
        s.subcommand match {
          case DynamoDbLoad      => dynamoDbLoad(s, cfg, stores, rejects, report)
          case NeptuneCsvBuild   => neptuneCsvBuild(s, cfg, stores, rejects, report, masking)
          case NeptuneBulkStart  => neptuneBulkStart(s, cfg, stores, report, masking, wiring)
          case NeptuneBulkStatus => neptuneBulkStatus(s, stores, report, wiring)
        }
        report("store") = stores.report(cfg)
        report("status") = "completed"
        Exit.Completed
      } catch {
        case e: LoadRefused =>
          report("status") = s"refused: ${e.getMessage}"; Exit.Refused
        case e: LoadNotFinished =>
          report("status") = s"not finished: ${e.getMessage}"; Exit.NotFinished
        case NonFatal(e) =>
          report("status") = s"failed: ${e.getClass.getSimpleName}: ${e.getMessage}"; Exit.Failed
      } finally {
        rejects.close()
        if (stores != null)
          try stores.close()
          catch { case NonFatal(_) => () }
      }
    report("rejects") = ujson.Obj(
      "total" -> rejects.total.toDouble,
      "byStageAndTable" -> ujson.Arr.from(rejects.byStageAndTable.sortBy(_._1).map {
        case ((st, t), n) => ujson.Obj("stage" -> st, "table" -> t, "count" -> n.toDouble)
      })
    )
    report("exitCode") = code
    report("totalMillis") = ((System.nanoTime() - t0) / 1000000L).toDouble
    val text = masking(report.render(indent = 2))
    Files.write(s.out.resolve("run-report.json"), text.getBytes(StandardCharsets.UTF_8))
    wiring.print(masking(s"[storage-load] ${s.subcommand} ${report("status").str}"))
    wiring.print(masking(s"[storage-load] report ${report.render()}"))
    code
  }

  // ---- exports ------------------------------------------------------------------------------

  /** Opens the export of `--export` and checks it before any write. With `verifyContent` every
    * listed file is read in full and its byte count and sha256 are checked against the manifest
    * ([[ExportSource.verifyAll]]) before this returns, so a loader that writes while it reads
    * (`dynamodb-load`) never writes an item built from bytes that fail the manifest (C-02). Without
    * it (`neptune-csv-build`, which builds locally and uploads only after its whole build
    * succeeded) only presence and sizes are checked here and content as each file is read.
    */
  private def openExport(
      s: Settings,
      cfg: StorageConfig,
      stores: LoadStores,
      report: ujson.Obj,
      verifyContent: Boolean
  ): (ExportSource, org.scholarchain.storage.source.ExportManifest, String) = {
    val location = s.exportLocation.get
    val src = ExportSources
      .open(location, Some(stores.objects))
      .fold(e => throw new LoadRefused(s"--export: $e"), identity)
    src match {
      case x: S3ExportSource if !cfg.zones.values.exists(_.bucket == x.root.bucket) =>
        throw new LoadRefused("--export must be in a bucket of the configured data-lake zones")
      case _ => ()
    }
    val (manifest, sha) = src.manifestWithSha256()
    val present = src.checkPresence(manifest)
    if (verifyContent) src.verifyAll(manifest)
    report("input") = ujson.Obj(
      "export" -> src.describe,
      "exportManifestSha256" -> sha,
      "exportRunId" -> manifest.runId.value,
      "sourceRunId" -> manifest.sourceRunId.value,
      "tables" -> manifest.tables.size,
      "records" -> manifest.tables.map(_.records).sum.toDouble,
      "files" -> present.files,
      "bytes" -> present.bytes.toDouble,
      "checks" -> (if (verifyContent)
                     "manifest present; every listed file present with its byte count; every file read in full and its bytes and sha256 checked before the first write; checked again as each file is read"
                   else
                     "manifest present; every listed file present with its byte count; bytes and sha256 checked as each file is read")
    )
    (src, manifest, sha)
  }

  // ---- dynamodb-load ------------------------------------------------------------------------

  private def dynamoDbLoad(
      s: Settings,
      cfg: StorageConfig,
      stores: LoadStores,
      rejects: RejectLog,
      report: ujson.Obj
  ): Unit = {
    val (src, manifest, sha) = openExport(s, cfg, stores, report, verifyContent = true)
    val runId = StorageVersion.runId(DynamoDbJob, s"sha256:$sha", cfg.fingerprint)
    val topK = s.rankingTopK.orElse(cfg.runtime.map(_.rankingCacheTopK))
    val loader =
      new DynamoLoader(src, manifest, stores.kv, cfg.dynamoDb, rejects, s.parallelism, topK)
    val passes = (1 to s.passes).toList.map(p => loader.load(p, runId, Nil))
    report("dynamodb") = ujson.Obj(
      "runId" -> runId.value,
      "rankingTopK" -> topK.map(ujson.Num(_)).getOrElse(ujson.Null),
      "passes" -> ujson.Arr.from(passes.map(_.toJson)),
      "idempotency" -> "items are written upsert-if-changed with conditional writes; a second pass over the same export must make 0 data changes (RunLedger gets one claim and one summary per pass)"
    )
  }

  // ---- neptune-csv-build ----------------------------------------------------------------------

  private def neptuneCsvBuild(
      s: Settings,
      cfg: StorageConfig,
      stores: LoadStores,
      rejects: RejectLog,
      report: ujson.Obj,
      masking: Masking
  ): Unit = {
    val fullWindow = s.fullWindow
      .orElse(cfg.runtime.map(_.defaultWindowLabel))
      .getOrElse(
        throw new LoadRefused("--full-window is required when the runtime section is absent")
      )
    if (!cfg.windowLabels.contains(fullWindow))
      throw new LoadRefused("--full-window is not a configured graph window")
    val free = Files.getFileStore(s.out).getUsableSpace
    if (free < MinFreeBytesCsvBuild)
      throw new LoadRefused(s"free disk under --out is ${free / (1L << 20)} MiB (< 2048 MiB)")
    val (src, manifest, sha) = openExport(s, cfg, stores, report, verifyContent = false)
    val runId = StorageVersion.runId(NeptuneCsvJob, s"sha256:$sha", cfg.fingerprint)
    val ds = s.datasetId.get
    val snap = s.snapshotId.get
    val layout = new S3KeyLayout(cfg.zones)
    val csvDir = s.out.resolve("neptune")
    val built = new NeptuneCsvBuilder(
      src,
      manifest,
      csvDir,
      NeptuneBuildSettings(
        fullWindow,
        s.rowsPerFile,
        gzip = true,
        cfg.vectors.researcherProfileIndex,
        cfg.zones("semantic-context").prefix
      ),
      rejects
    ).build()
    report("neptuneCsv") = NeptuneCsvBuilder.toJson(built)
    val mismatched = built.types.filterNot(_.idsMatch).map(t => s"${t.stage}/${t.name}")
    if (mismatched.nonEmpty)
      throw new IllegalStateException(
        s"IDs read back differ from IDs written: ${mismatched.mkString(", ")}"
      )

    val run = GraphRun.of(layout, ds, snap, runId)
    var created = 0
    var present = 0
    val entries = built.types.flatMap(_.files).map { f =>
      val stage = f.relativePath.takeWhile(_ != '/')
      val ref = ObjectRef(
        run.root.bucket,
        run.stagePrefix(stage).key + f.relativePath.drop(stage.length + 1)
      )
      val bytes = Files.readAllBytes(csvDir.resolve(f.relativePath))
      val hex = Hashing.sha256Hex(bytes)
      if (hex != f.sha256 || bytes.length.toLong != f.bytes)
        throw new IllegalStateException(s"${f.relativePath} changed on disk after the build")
      stores.objects.putIfAbsent(
        ref,
        bytes,
        if (f.relativePath.endsWith(".gz")) "application/gzip" else "text/csv",
        Map("scholarchain-run" -> runId.value)
      ) match {
        case PutOutcome.Created        => created += 1
        case PutOutcome.AlreadyPresent => present += 1
        case PutOutcome.Conflict(_)    =>
          throw new IllegalStateException(
            s"${masking.s3(ref)} exists with different content; keys are versioned, nothing was overwritten"
          )
      }
      ManifestEntry(ref.key, f.bytes, hex, List(s"export:${manifest.runId.value}"))
    }
    val manifestOutcome =
      RunManifest("graph", runId, ds, snap, NeptuneCsvJob, entries).write(stores.objects, layout)
    manifestOutcome match {
      case PutOutcome.Conflict(_) =>
        throw new IllegalStateException(
          s"${masking.s3(run.manifestRef)} exists with different content; nothing was overwritten"
        )
      case _ => ()
    }
    report("graphRun") = ujson.Obj(
      "runId" -> runId.value,
      "graphRun" -> masking.s3(run.root),
      "stagePrefixes" -> ujson.Obj.from(
        S3KeyLayout.NeptuneStages.toList.sorted.map(st =>
          st -> ujson.Str(masking.s3(run.stagePrefix(st)))
        )
      ),
      "runManifest" -> masking.s3(run.manifestRef),
      "objects" -> entries.size,
      "objectsCreated" -> created,
      "objectsAlreadyPresent" -> present,
      "runManifestOutcome" -> (manifestOutcome match {
        case PutOutcome.Created        => "created"
        case PutOutcome.AlreadyPresent => "already-present"
        case PutOutcome.Conflict(_)    => "conflict"
      }),
      "idempotency" -> "keys derive from the export manifest sha256 and the configuration fingerprint; uploads are put-if-absent and the run manifest is written last"
    )
  }

  // ---- neptune-bulk-start ---------------------------------------------------------------------

  private def neptuneBulkStart(
      s: Settings,
      cfg: StorageConfig,
      stores: LoadStores,
      report: ujson.Obj,
      masking: Masking,
      wiring: Wiring
  ): Unit = {
    val run = GraphRun
      .parse(s.graphRun.get, cfg.zones("graph"))
      .fold(e => throw new LoadRefused(e), identity)
    val roleArn = s.loaderRoleArn.get
    report("graphRun") = masking.s3(run.root)
    report("loaderRoleArn") = masking(roleArn)
    // Build and validate both requests before anything is started.
    def request(stage: String, deps: List[String]) =
      BulkLoadRequests
        .build(run.stagePrefix(stage), roleArn, cfg.region, deps)
        .fold(e => throw new LoadRefused(masking(e)), identity)
    request("nodes", Nil)
    request("edges", List("pending"))
    val loader = stores.bulkLoader.fold(e => throw new LoadRefused(e), identity)

    val manifestText = stores.objects
      .get(run.manifestRef)
      .map(b => new String(b, StandardCharsets.UTF_8))
      .getOrElse(
        throw new LoadRefused(
          "graph run manifest is missing: the CSV build did not finish, or --graph-run is wrong"
        )
      )
    val entries =
      GraphRun.parseManifest(manifestText, run).fold(e => throw new LoadRefused(e), identity)
    val byStage = S3KeyLayout.NeptuneStages.toList.sorted.map { st =>
      st -> entries.filter(_.key.startsWith(run.stagePrefix(st).key))
    }.toMap
    byStage.foreach { case (st, es) =>
      if (es.isEmpty) throw new LoadRefused(s"graph run has no $st files")
    }
    entries.foreach { e =>
      stores.objects.head(ObjectRef(run.root.bucket, e.key)) match {
        case None => throw new LoadRefused(s"graph run object missing: ${e.key}")
        case Some(i) if i.size != e.bytes || i.sha256.exists(_ != e.sha256) =>
          throw new LoadRefused(s"graph run object differs from its manifest: ${e.key}")
        case Some(_) => ()
      }
    }
    // Registry protocol (C-04, C-06, C-07): every step is registered before the next one starts,
    // as a new append-only version written with a conditional put, so a retry never starts a load
    // twice. States, in order (each a new version):
    //   starting        claim written before the nodes start
    //   nodes-failed    the nodes start was refused for certain (BulkLoadStartRejected), startError
    //   nodes-started   written right after the nodes start, with nodesLoadId
    //   edges-starting  claim written before the edges start, with nodesLoadId
    //   edges-failed    the edges start was refused for certain, with nodesLoadId and startError
    //   started         written after the edges start, with both load IDs
    // A retry from nodes-failed starts again from the nodes claim; from nodes-started or
    // edges-failed it starts only the edges load (depending on the registered nodes load). A retry
    // from starting or edges-starting is refused: that start's outcome is unknown (timeout,
    // network error, throttling, 5xx, or the version after it was not written), so the load may
    // exist in Neptune. A retry from started is refused (already started).
    val registry = new ArtifactRegistry(stores.kv, cfg.dynamoDb.artifactRegistryTable)
    val graphRunId = s"${RunId.Prefix}${run.runHex}"
    def register(previous: Long, state: String, ids: (String, String)*): Long = {
      val attrs = List(
        "loadState" -> AttrValue.S(state),
        "graphRunId" -> AttrValue.S(graphRunId),
        "codeVersion" -> AttrValue.S(s.codeVersion)
      ) ++ ids.map { case (k, v) => k -> AttrValue.S(v) }
      registry.register(NeptuneLoadArtifact, run.root.key, previous, attrs*) match {
        case Change.Conflict =>
          val started = ids.map { case (k, v) => s"$k=$v" }.mkString(", ")
          throw new IllegalStateException(
            s"the load registry entry (version ${previous + 1}, $state) was written by another writer (one writer only); started by this run: ${
                if (started.isEmpty) "nothing" else started
              }"
          )
        case _ => previous + 1
      }
    }
    // Starts one stage after its claim (version `claim`). A throttling refusal (Phase 16) is sent
    // again under the same claim at most ThrottleRetries times, after ThrottleDelayMillis * 2^n:
    // each throttled request created no load, so this never starts a second load. A definite
    // refusal (including the last throttling answer) appends `failedState` (masked error class,
    // HTTP status) and fails the run, so a later start may try again; any other exception
    // propagates and leaves the claim as the latest version (outcome unknown).
    var throttleRetries = 0
    def startStage(
        stage: String,
        deps: List[String],
        claim: Long,
        failedState: String,
        ids: (String, String)*
    ): String = {
      @annotation.tailrec
      def attempt(n: Int): String = {
        val r =
          try Right(loader.start(request(stage, deps)))
          catch { case e: BulkLoadStartRejected if e.throttled && n < ThrottleRetries => Left(e) }
        r match {
          case Right(id) => id
          case Left(e)   =>
            val wait = ThrottleDelayMillis * (1L << n)
            wiring.print(
              s"[storage-load] the $stage start was throttled (${masking(e.errorClass)}, HTTP ${e.httpStatus}); no load was created; trying again in $wait ms (${n + 1} of $ThrottleRetries)"
            )
            wiring.sleeper.sleep(wait)
            throttleRetries += 1
            report("bulkLoadThrottleRetries") = throttleRetries.toDouble
            attempt(n + 1)
        }
      }
      try attempt(0)
      catch {
        case e: BulkLoadStartRejected =>
          val errorClass = masking(e.errorClass)
          val dependencyMissing = stage == "edges" && e.errorClass == DependencyNotFound
          val recorded =
            try {
              val v = register(
                claim,
                failedState,
                (ids ++ List(
                  "startError" -> errorClass,
                  "startHttpStatus" -> e.httpStatus.toString
                ))*
              )
              if (dependencyMissing)
                s"registry version $v records $failedState; Neptune does not know the registered nodes load, so no retry of this graph run can succeed: a new start needs a new export or a new configuration (a new graph run)"
              else
                s"registry version $v records $failedState, so a new start of this graph run is allowed once the cause is fixed"
            } catch {
              case NonFatal(r) =>
                s"recording $failedState failed (${r.getClass.getSimpleName}), so the claim (version $claim) stays and a new start is refused"
            }
          report("bulkLoadFailure") = ujson.Obj(
            "stage" -> stage,
            "errorClass" -> errorClass,
            "httpStatus" -> e.httpStatus.toDouble,
            "registryState" -> failedState
          )
          throw new BulkLoadStartRejected(
            e.errorClass,
            e.httpStatus,
            s"the $stage start was refused by the loader ($errorClass, HTTP ${e.httpStatus}); $recorded: ${e.getMessage}"
          )
      }
    }
    def unknownOutcome(v: ArtifactVersion, stage: String, known: String) =
      new LoadRefused(
        s"a $stage start of this graph run was begun (registry version ${v.version}, ${v.item.strOpt("loadState").getOrElse("no state")}) and its outcome is unknown: the $stage load may exist in Neptune.$known Check Neptune's loader list for a load of this graph run (with a known load ID: $NeptuneBulkStatus --load-id ID). The registry is append-only and is never edited by hand; the only other way to a new start is a new export or a new configuration (a new graph run)"
      )
    def startNodes(previous: Long): (String, Long) = {
      val claim = register(previous, StateStarting)
      val id = startStage("nodes", Nil, claim, StateNodesFailed)
      wiring.print(s"[storage-load] started nodes load $id")
      (id, register(claim, StateNodesStarted, "nodesLoadId" -> id))
    }
    val latest = registry.latest(NeptuneLoadArtifact, run.root.key)
    val (nodesId, fromVersion, resumed) = latest match {
      case None =>
        val (id, v) = startNodes(0L)
        (id, v, false)
      case Some(v) if v.item.strOpt("edgesLoadId").isDefined =>
        val ids =
          List("nodesLoadId", "edgesLoadId").flatMap(k => v.item.strOpt(k).map(x => s"$k=$x"))
        throw new LoadRefused(
          s"this graph run was already started (registry version ${v.version}: ${ids.mkString(", ")}); check it with $NeptuneBulkStatus. Reloading a cluster needs an empty cluster and a new graph run"
        )
      case Some(v) =>
        (v.item.strOpt("loadState"), v.item.strOpt("nodesLoadId")) match {
          case (Some(StateNodesFailed), _) =>
            wiring.print(
              s"[storage-load] retrying: the nodes start was refused (registry version ${v.version}, ${v.item.strOpt("startError").getOrElse("unknown error")}); starting the nodes load again"
            )
            val (id, nv) = startNodes(v.version)
            (id, nv, true)
          case (Some(StateEdgesFailed), Some(id))
              if v.item.strOpt("startError").contains(DependencyNotFound) =>
            // D-02 (Phase 14): Neptune refused the edges start because it does not know the
            // registered nodes load; every retry would send the same dependency and be refused
            throw new LoadRefused(
              s"the edges start of this graph run was refused because Neptune does not know the registered nodes load $id (registry version ${v.version}, $DependencyNotFound); no retry of this graph run can succeed. Check it with $NeptuneBulkStatus --load-id $id. The registry is append-only and is never edited by hand; a new start needs a new export or a new configuration (a new graph run)"
            )
          case (Some(StateNodesStarted | StateEdgesFailed), Some(id)) =>
            wiring.print(
              s"[storage-load] resuming: nodes load $id is registered (registry version ${v.version}); starting the edges load only"
            )
            (id, v.version, true)
          case (Some(StateEdgesStarting), Some(id)) =>
            throw unknownOutcome(v, "edges", s" The nodes load is $id.")
          case _ =>
            throw unknownOutcome(v, "nodes", "")
        }
    }
    val edgesClaim = register(fromVersion, StateEdgesStarting, "nodesLoadId" -> nodesId)
    val edgesId =
      startStage("edges", List(nodesId), edgesClaim, StateEdgesFailed, "nodesLoadId" -> nodesId)
    wiring.print(s"[storage-load] started edges load $edgesId (depends on $nodesId)")
    val finalVersion =
      register(edgesClaim, StateStarted, "nodesLoadId" -> nodesId, "edgesLoadId" -> edgesId)
    report("bulkLoad") = ujson.Obj(
      "files" -> ujson.Obj.from(byStage.toList.sortBy(_._1).map { case (k, v) =>
        k -> ujson.Num(v.size)
      }),
      "nodesLoadId" -> nodesId,
      "edgesLoadId" -> edgesId,
      "resumed" -> resumed,
      "request" -> masking(BulkLoadRequests.toJson(request("edges", List(nodesId))).render()),
      "registry" -> "registered",
      "registryVersion" -> finalVersion.toDouble
    )
  }

  /** `loadState` values of the `neptune-load` registry entries (see [[neptuneBulkStart]]). */
  val StateStarting = "starting"
  val StateNodesFailed = "nodes-failed"
  val StateNodesStarted = "nodes-started"
  val StateEdgesStarting = "edges-starting"
  val StateEdgesFailed = "edges-failed"
  val StateStarted = "started"

  /** Throttled `StartLoaderJob` answers sent again under the same claim (Phase 16, storage
    * retries), after 2 s then 4 s (`ThrottleDelayMillis * 2^n`): at most 6 s of waiting per stage.
    */
  val ThrottleRetries = 2
  val ThrottleDelayMillis = 2000L

  /** The edges start's refusal when Neptune does not know the nodes load it depends on (D-02). */
  val DependencyNotFound = "BulkLoadIdNotFoundException"

  // ---- neptune-bulk-status --------------------------------------------------------------------

  private def neptuneBulkStatus(
      s: Settings,
      stores: LoadStores,
      report: ujson.Obj,
      wiring: Wiring
  ): Unit = {
    val loader = stores.bulkLoader.fold(e => throw new LoadRefused(e), identity)
    val id = s.loadId.get
    val deadline = System.nanoTime() + s.waitSeconds.toLong * 1000000000L
    var st = loader.status(id)
    var checks = 1
    while (PendingStatuses.contains(st.status) && System.nanoTime() < deadline) {
      wiring.sleeper.sleep(StatusPollMillis)
      st = loader.status(id)
      checks += 1
    }
    report("bulkLoadStatus") = ujson.Obj(
      "loadId" -> st.loadId,
      "status" -> st.status,
      "totalRecords" -> st.totalRecords.toDouble,
      "errors" -> st.errors.toDouble,
      "checks" -> checks,
      "waitSeconds" -> s.waitSeconds
    )
    wiring.print(s"[storage-load] load $id status ${st.status}")
    if (PendingStatuses.contains(st.status))
      throw new LoadNotFinished(s"load is ${st.status} after ${s.waitSeconds} s")
    if (st.status != CompletedStatus)
      throw new IllegalStateException(s"load ended with ${st.status} (${st.errors} errors)")
  }
}
