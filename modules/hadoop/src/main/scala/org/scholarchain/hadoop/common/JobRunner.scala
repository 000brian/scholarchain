package org.scholarchain.hadoop.common

import java.nio.charset.StandardCharsets

import scala.jdk.CollectionConverters.*

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.hadoop.io.{NullWritable, Text}
import org.apache.hadoop.io.compress.CompressionCodec
import org.apache.hadoop.mapreduce.Job
import org.apache.hadoop.mapreduce.lib.output.{
  FileOutputFormat,
  LazyOutputFormat,
  MultipleOutputs,
  TextOutputFormat
}
import org.scholarchain.core.{CodePointOrder, Hashing, RunId}
import upickle.default.read

/** What a driver needs to run one ScholarChain MapReduce job.
  *
  * @param inputRuns
  *   run IDs of the upstream ScholarChain jobs whose outputs this job reads (empty for jobs that
  *   read raw source files). When non-empty, `inputFingerprint` must equal
  *   `JobRunner.fingerprintRuns(inputRuns)`; the run IDs are written in plain form to `_job.json`
  *   (`inputRuns`) so lineage between runs survives the hashing of the fingerprint.
  */
final case class JobSpec(
    jobName: String,
    datasetId: String,
    snapshotId: String,
    policyVersionKey: String,
    inputFingerprint: String,
    outputRoot: Path,
    scope: String,
    reducersKey: String,
    namedOutputs: List[String],
    configure: Job => Unit,
    alwaysPresentDirs: List[String] = Nil,
    inputRuns: List[RunId] = Nil
) {
  PathIds.requireSegment("datasetId", datasetId)
  PathIds.requireSegment("snapshotId", snapshotId)
  PathIds.requireScope(scope)
  require(
    inputRuns.isEmpty || inputFingerprint == JobRunner.fingerprintRuns(inputRuns),
    s"$jobName: inputFingerprint does not match fingerprintRuns(inputRuns)"
  )
}

/** Outcome of one job run (fresh or reused). */
final case class JobResult(
    jobName: String,
    runId: RunId,
    outputDir: Path,
    counters: Map[String, Long],
    stats: Map[String, Stat],
    reused: Boolean
) {
  def counter(group: String, name: String): Long = counters.getOrElse(s"$group:$name", 0L)
  def main(name: String): Long = counter(Counters.Group, name)
  def dir(sub: String): Path = new Path(outputDir, sub)
}

final class JobFailed(msg: String) extends RuntimeException(msg)

/** Runs jobs with idempotent, run-addressed output paths.
  *
  * Output layout: `<outputRoot>/<scope>/<job>/run=<run hex>/` where `scope` names the dataset and
  * snapshot (for example `dblp/snapshot=2026-11-01`) and the run ID is `RunId.of(jobName,
  * codeVersion, inputFingerprint, configFingerprint)`. The same inputs, code and configuration
  * always map to the same directory (byte-identical content only under the conditions stated at
  * [[Settings.OperationalKeys]]); operational keys ([[Settings.OperationalKeys]], such as
  * `scholarchain.output.replaceIncomplete`) do not change the run ID. A directory holding
  * `_SUCCESS` and `_job.json` is reused without rerunning once its record and files pass the reuse
  * check (see `load`: identity fields including the code version and inputs, and the per-file
  * `outputs` list), otherwise the run fails; a directory without them is replaced only when
  * `scholarchain.output.replaceIncomplete` is true, otherwise the run fails.
  */
object JobRunner {

  val JobRecord = "_job.json"

  def runId(conf: Configuration, jobName: String, inputFingerprint: String): RunId = {
    val code = new Settings(conf).string(Settings.Keys.CodeVersion)
    RunId
      .of(jobName, code, inputFingerprint, Settings.fingerprint(conf))
      .fold(e => throw new JobFailed(e), identity)
  }

  def outputDir(root: Path, scope: String, jobName: String, run: RunId): Path =
    new Path(
      root,
      s"$scope/${jobName.stripPrefix("hadoop.")}/run=${run.value.stripPrefix(RunId.Prefix)}"
    )

  def run(base: Configuration, spec: JobSpec): JobResult = {
    val conf = Settings.withDefaults(base)
    val settings = new Settings(conf)
    settings.rejectPreviewBytes // fail at configuration time, before any task starts
    val rid = runId(conf, spec.jobName, spec.inputFingerprint)
    val out = outputDir(spec.outputRoot, spec.scope, spec.jobName, rid)
    val fs = out.getFileSystem(conf)
    if (fs.exists(new Path(out, "_SUCCESS")) && fs.exists(new Path(out, JobRecord)))
      return load(fs, out, expectedIdentity(conf, spec, rid), rid)
    if (fs.exists(out)) {
      if (!settings.boolean(Settings.Keys.ReplaceIncomplete))
        throw new JobFailed(
          s"$out exists without a completion record; set ${Settings.Keys.ReplaceIncomplete}=true " +
            "to replace it"
        )
      fs.delete(out, true)
    }
    RunContext(
      rid,
      spec.jobName,
      spec.datasetId,
      spec.snapshotId,
      settings.string(spec.policyVersionKey)
    ).writeTo(conf)

    if (settings.boolean(Settings.Keys.IntermediateCompress)) {
      conf.setBoolean("mapreduce.map.output.compress", true)
      conf.set(
        "mapreduce.map.output.compress.codec",
        settings.string(Settings.Keys.IntermediateCodec)
      )
    } else conf.setBoolean("mapreduce.map.output.compress", false)
    // A task that fails is retried from its input; outputs never depend on attempt order.
    conf.setBoolean("mapreduce.map.speculative", false)
    conf.setBoolean("mapreduce.reduce.speculative", false)

    val job = Job.getInstance(conf, s"${spec.jobName} ${rid.value}")
    job.setJarByClass(classOf[StableHashPartitioner])
    job.setMapOutputKeyClass(classOf[Text])
    job.setMapOutputValueClass(classOf[Text])
    job.setOutputKeyClass(classOf[NullWritable])
    job.setOutputValueClass(classOf[Text])
    job.setPartitionerClass(classOf[StableHashPartitioner])
    job.setNumReduceTasks(settings.positiveInt(spec.reducersKey))
    LazyOutputFormat.setOutputFormatClass(job, classOf[TextOutputFormat[NullWritable, Text]])
    (spec.namedOutputs ++ TaskIO.accountingOutputs).distinct.foreach { n =>
      MultipleOutputs.addNamedOutput(
        job,
        n,
        classOf[TextOutputFormat[NullWritable, Text]],
        classOf[NullWritable],
        classOf[Text]
      )
    }
    MultipleOutputs.setCountersEnabled(job, false)
    if (settings.boolean(Settings.Keys.OutputCompress)) {
      FileOutputFormat.setCompressOutput(job, true)
      FileOutputFormat.setOutputCompressorClass(
        job,
        conf
          .getClassByName(settings.string(Settings.Keys.OutputCodec))
          .asSubclass(classOf[CompressionCodec])
      )
    }
    FileOutputFormat.setOutputPath(job, out)
    spec.configure(job)

    if (!job.waitForCompletion(false))
      throw new JobFailed(s"${spec.jobName} failed: ${diagnostics(job)}")

    // Directories downstream jobs read: created (empty) when the job wrote nothing to them, so an
    // empty result is an empty input rather than a missing path.
    spec.alwaysPresentDirs.foreach(d => fs.mkdirs(new Path(out, d)))
    val counters = job.getCounters.asScala.toList.flatMap { g =>
      if (!g.getName.startsWith(Counters.Group)) Nil
      else g.asScala.toList.map(c => s"${g.getName}:${c.getName}" -> c.getValue)
    }.toMap
    val stats = readStats(fs, out)
    val record = ujson.Obj.from(
      expectedIdentity(conf, spec, rid).value.toList ++ List(
        "counters" -> ujson.Obj.from(counters.toList.sortBy(_._1).map { case (k, v) =>
          k -> ujson.Num(v.toDouble)
        }),
        "stats" -> ujson.Obj.from(stats.toList.sortBy(_._1).map { case (k, s) =>
          k -> ujson.Obj("value" -> ujson.Num(s.value.toDouble), "subject" -> s.subject)
        }),
        OutputsField -> outputsJson(outputManifest(fs, out))
      )
    )
    val os = fs.create(new Path(out, JobRecord), false)
    try os.write(record.render(indent = 2).getBytes(StandardCharsets.UTF_8))
    finally os.close()
    JobResult(spec.jobName, rid, out, counters, stats, reused = false)
  }

  /** Failure details of a finished job: failure info plus every failed attempt's diagnostics. */
  private def diagnostics(job: Job): String = {
    val events = Iterator
      .iterate(0)(_ + 100)
      .map(i => Option(job.getTaskCompletionEvents(i, 100)).map(_.toList).getOrElse(Nil))
      .takeWhile(_.nonEmpty)
      .flatten
      .toList
    val failed = events.filter(_.getStatus.toString != "SUCCEEDED").flatMap { e =>
      Option(job.getTaskDiagnostics(e.getTaskAttemptId)).toList.flatten
        .map(d => s"${e.getTaskAttemptId}: $d")
    }
    (Option(job.getStatus.getFailureInfo).toList ++ failed).mkString("\n")
  }

  /** `_job.json` field listing every output file of the run (see [[outputManifest]]). */
  val OutputsField = "outputs"

  /** Identity fields of `_job.json`, in written order: what a completed directory must record to be
    * reused by the current run (job, run ID, dataset, snapshot, code version, inputs, config).
    */
  private def expectedIdentity(conf: Configuration, spec: JobSpec, rid: RunId): ujson.Obj =
    ujson.Obj(
      "jobName" -> spec.jobName,
      "runId" -> rid.value,
      "datasetId" -> spec.datasetId,
      "snapshotId" -> spec.snapshotId,
      "codeVersion" -> new Settings(conf).string(Settings.Keys.CodeVersion),
      "inputFingerprint" -> spec.inputFingerprint,
      "inputRuns" -> ujson.Arr.from(spec.inputRuns.map(_.value).distinct.sorted.map(ujson.Str(_))),
      "configFingerprint" -> Settings.fingerprint(conf)
    )

  /** One output file of a run: path relative to the run directory, length and sha256 (hex). */
  final case class OutputFile(path: String, length: Long, sha256: String)

  /** Every file under the run directory `out` except hidden ones (a path segment starting with `_`
    * or `.`, such as `_SUCCESS`, `_job.json` and checksum files), sorted by relative path (code
    * point order). Relative paths keep the record independent of the output root.
    */
  def outputManifest(fs: FileSystem, out: Path): List[OutputFile] =
    outputListing(fs, out).map { case (rel, p, len) => OutputFile(rel, len, sha256Of(fs, p)) }

  /** (relative path, path, length) of the non-hidden files under `out`, sorted by relative path. */
  private def outputListing(fs: FileSystem, out: Path): List[(String, Path, Long)] = {
    val base = fs.makeQualified(out).toUri.getPath.stripSuffix("/") + "/"
    val it = fs.listFiles(out, true)
    val b = List.newBuilder[(String, Path, Long)]
    while (it.hasNext) {
      val st = it.next()
      val abs = st.getPath.toUri.getPath
      if (!abs.startsWith(base))
        throw new JobFailed(s"$out: listed file ${st.getPath} is outside the run directory")
      val rel = abs.substring(base.length)
      if (!rel.split('/').exists(s => s.startsWith("_") || s.startsWith(".")))
        b += ((rel, st.getPath, st.getLen))
    }
    b.result().sortWith((x, y) => CodePointOrder.lessThan(x._1, y._1))
  }

  private def sha256Of(fs: FileSystem, p: Path): String = {
    val in = fs.open(p)
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    try {
      val buf = new Array[Byte](65536)
      var n = in.read(buf)
      while (n > 0) { digest.update(buf, 0, n); n = in.read(buf) }
    } finally in.close()
    digest.digest().map(b => f"${b & 0xff}%02x").mkString
  }

  private def outputsJson(files: List[OutputFile]): ujson.Arr =
    ujson.Arr.from(files.map { f =>
      ujson.Obj("path" -> f.path, "length" -> ujson.Num(f.length.toDouble), "sha256" -> f.sha256)
    })

  /** Reuses a completed run directory only after checking it (Phase 4 finding 2): every identity
    * field of its `_job.json` (code version and inputs included) must equal the current run's, and
    * its non-hidden files must be exactly the recorded `outputs` (same paths, lengths and sha256).
    * Otherwise the run fails with [[JobFailed]] naming the first difference; nothing is removed or
    * rewritten, so the operator chooses a new output root or code version. A record without
    * `outputs` (written before Phase 11) is refused the same way.
    */
  private def load(fs: FileSystem, out: Path, expected: ujson.Obj, rid: RunId): JobResult = {
    val jobName = expected("jobName").str
    def refuse(why: String): Nothing =
      throw new JobFailed(s"$jobName: refusing to reuse $out: $why")
    val text = scala.util
      .Try {
        val in = fs.open(new Path(out, JobRecord))
        try new String(in.readAllBytes(), StandardCharsets.UTF_8)
        finally in.close()
      }
      .getOrElse(refuse(s"$JobRecord cannot be read"))
    val json = scala.util.Try(ujson.read(text)).getOrElse(refuse(s"$JobRecord is not valid JSON"))
    if (!json.objOpt.isDefined) refuse(s"$JobRecord is not a JSON object")
    val rec = json.obj
    expected.value.foreach { case (k, v) =>
      rec.get(k) match {
        case None                  => refuse(s"$JobRecord has no $k")
        case Some(got) if got != v =>
          refuse(s"$JobRecord $k ${got.render()} differs from the current run's ${v.render()}")
        case _ => ()
      }
    }
    val recorded = rec.get(OutputsField) match {
      case Some(ujson.Arr(items)) =>
        scala.util
          .Try(items.toList.map { o =>
            OutputFile(o("path").str, o("length").num.toLong, o("sha256").str)
          })
          .getOrElse(refuse(s"$JobRecord $OutputsField is malformed"))
      case _ =>
        refuse(s"$JobRecord has no $OutputsField list (written before output verification)")
    }
    val listing = outputListing(fs, out)
    val byPath = recorded.map(f => f.path -> f).toMap
    if (byPath.size != recorded.size) refuse(s"$JobRecord $OutputsField repeats a path")
    val present = listing.map(_._1).toSet
    byPath.keys.toList.sorted.find(!present.contains(_)).foreach { p =>
      refuse(s"recorded output $p is missing")
    }
    listing.foreach { case (rel, p, len) =>
      byPath.get(rel) match {
        case None                       => refuse(s"output $rel is not recorded in $JobRecord")
        case Some(f) if f.length != len =>
          refuse(s"output $rel has length $len, recorded ${f.length}")
        case Some(f) =>
          val sha = scala.util
            .Try(sha256Of(fs, p))
            .getOrElse(refuse(s"output $rel cannot be read back"))
          if (sha != f.sha256) refuse(s"output $rel sha256 differs from the recorded one")
      }
    }
    scala.util
      .Try {
        val counters = rec("counters").obj.map { case (k, v) => k -> v.num.toLong }.toMap
        val stats = rec("stats").obj.map { case (k, v) =>
          k -> Stat(k, v("value").num.toLong, v("subject").str)
        }.toMap
        JobResult(jobName, rid, out, counters, stats, reused = true)
      }
      .getOrElse(refuse(s"$JobRecord counters or stats are malformed"))
  }

  /** Largest value per stat name across `stats/part-*` (ties: smallest subject by code point). */
  def readStats(fs: FileSystem, out: Path): Map[String, Stat] = {
    val dir = new Path(out, TaskIO.StatsDir)
    if (!fs.exists(dir)) return Map.empty
    val all =
      fs.listStatus(dir).toList.filter(_.getPath.getName.startsWith("part-")).flatMap { st =>
        readLines(fs, st.getPath).map(l => read[Stat](l))
      }
    all.groupBy(_.name).map { case (n, ss) =>
      n -> ss.sortWith { (a, b) =>
        a.value > b.value || (a.value == b.value && CodePointOrder.lessThan(a.subject, b.subject))
      }.head
    }
  }

  def readLines(fs: FileSystem, p: Path): List[String] = {
    val in = fs.open(p)
    try
      new String(in.readAllBytes(), StandardCharsets.UTF_8).split('\n').toList.filter(_.nonEmpty)
    finally in.close()
  }

  /** All lines of `part-*` files directly under `dir` (sorted file names); empty if absent. */
  def readPartLines(conf: Configuration, dir: Path): List[String] = {
    val fs = dir.getFileSystem(conf)
    if (!fs.exists(dir)) Nil
    else
      fs.listStatus(dir)
        .toList
        .map(_.getPath)
        .filter(_.getName.startsWith("part-"))
        .sortBy(_.getName)
        .flatMap(readLines(fs, _))
  }

  /** `sha256:<hex>` over sorted `name\tlength\tsha256` of every file under `paths` (recursive),
    * where `name` is the file's base name. Used as the input fingerprint of jobs that read raw
    * source files. The directory is not part of it: the same files elsewhere keep the run ID,
    * although disposition `location` strings (absolute paths) differ; see
    * [[Settings.OperationalKeys]] for what a run ID does and does not fix.
    */
  def fingerprintFiles(conf: Configuration, paths: List[Path]): String = {
    val entries = paths.flatMap { p =>
      val fs = p.getFileSystem(conf)
      val it = fs.listFiles(p, true)
      val b = List.newBuilder[String]
      while (it.hasNext) {
        val st = it.next()
        val name = st.getPath.getName
        if (!name.startsWith("_") && !name.startsWith(".")) {
          val in = fs.open(st.getPath)
          val digest = java.security.MessageDigest.getInstance("SHA-256")
          try {
            val buf = new Array[Byte](65536)
            var n = in.read(buf)
            while (n > 0) { digest.update(buf, 0, n); n = in.read(buf) }
          } finally in.close()
          val hex = digest.digest().map(b => f"${b & 0xff}%02x").mkString
          b += s"$name\t${st.getLen}\t$hex"
        }
      }
      b.result()
    }
    "sha256:" + Hashing.sha256Hex(entries.sorted.mkString("\n"))
  }

  /** Input fingerprint of a job that reads upstream ScholarChain outputs: their run IDs. */
  def fingerprintRuns(runs: List[RunId]): String =
    "sha256:" + Hashing.sha256Hex(runs.map(_.value).sorted.mkString("\n"))

  /** A spec for a job that reads the outputs of the upstream `runs`: its input fingerprint is
    * [[fingerprintRuns]] of them and they are recorded as [[JobSpec.inputRuns]].
    */
  def fromRuns(runs: List[RunId])(mk: String => JobSpec): JobSpec =
    mk(fingerprintRuns(runs)).copy(inputRuns = runs)
}

/** Callbacks around each job of a pipeline (for example a local runner that times jobs and checks
  * free disk space between them). They observe only; they never change job inputs, configuration or
  * outputs. `starting` may throw to stop the pipeline before the named job runs.
  */
trait JobObserver {
  def starting(jobName: String): Unit = ()
  def finished(result: JobResult, wallMillis: Long): Unit = ()
}

object JobObserver {
  val Silent: JobObserver = new JobObserver {}

  /** Runs one job between the observer's callbacks. */
  def around(o: JobObserver, jobName: String)(run: => JobResult): JobResult = {
    o.starting(jobName)
    val t0 = System.nanoTime()
    val r = run
    o.finished(r, (System.nanoTime() - t0) / 1000000L)
    r
  }
}
