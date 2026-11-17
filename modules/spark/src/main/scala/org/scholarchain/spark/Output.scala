package org.scholarchain.spark

import java.io.{File, FileInputStream}
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters._
import scala.reflect.runtime.universe.TypeTag

import org.apache.hadoop.fs.Path
import org.apache.spark.scheduler.{
  SparkListener,
  SparkListenerStageCompleted,
  SparkListenerStageSubmitted,
  SparkListenerTaskEnd
}
import org.apache.spark.sql.{Dataset, SparkSession}
import org.apache.spark.sql.functions.{col, length}

final case class FileEntry(path: String, bytes: Long, sha256: String)

/** One exported table: where it is, which core record and schema version it maps to, its record
  * count, and the result of validating every row (and round-tripping a sample) through the core
  * contract after it was written.
  */
final case class TableOutput(
    name: String,
    relPath: String,
    coreRecord: Option[String],
    schemaVersion: Option[Int],
    windowLabel: Option[String],
    uniqueKey: String,
    stores: Seq[String],
    records: Long,
    invalid: Long,
    invalidSamples: Seq[String],
    roundTripSampled: Int,
    roundTripFailures: Int,
    files: Seq[FileEntry]
)

/** Raised when an existing output does not match its manifest (the run refuses to reuse it). */
final class RunRefusedException(msg: String) extends RuntimeException(msg)

/** Raised when an exported table has rows failing core validation or sampled round-trip failures;
  * the run then writes no load manifests and no run manifest.
  */
final class OutputVerificationException(msg: String) extends RuntimeException(msg)

/** Raised when an upstream input lacks the provenance or completeness evidence the run requires
  * (for example an entity-resolution run without `_SUCCESS` or `_manifest.json`). No output is
  * written.
  */
final class InputRefusedException(msg: String) extends IllegalArgumentException(msg)

/** Raised when an operational limit (free disk, stage time) is hit. */
final class OperationalLimitException(msg: String) extends RuntimeException(msg)

object Files2 {

  def sha256(f: File): String = {
    val md = MessageDigest.getInstance("SHA-256")
    val in = new FileInputStream(f)
    try {
      val buf = new Array[Byte](1 << 20)
      var n = in.read(buf)
      while (n >= 0) {
        if (n > 0) md.update(buf, 0, n)
        n = in.read(buf)
      }
    } finally in.close()
    md.digest().map(b => f"${b & 0xff}%02x").mkString
  }

  /** Non-hidden regular files below `dir` (no `_` or `.` prefix in any path segment), sorted. */
  def listData(dir: File): List[File] = Inputs.dataFiles(dir)

  def rel(root: File, f: File): String = root.toPath.relativize(f.toPath).toString

  def entries(root: File, dir: File): List[FileEntry] =
    listData(dir).map(f => FileEntry(rel(root, f), f.length(), sha256(f)))

  def writeString(f: File, s: String): Unit = {
    f.getParentFile.mkdirs()
    Files.write(f.toPath, s.getBytes(StandardCharsets.UTF_8))
    ()
  }

  def readString(f: File): String = new String(Files.readAllBytes(f.toPath), StandardCharsets.UTF_8)
}

/** Writes the exported tables and verifies them after writing. Rows are hash-partitioned and sorted
  * by a stable key before writing, so file contents are deterministic for fixed inputs. Tables are
  * never overwritten (ErrorIfExists).
  */
final class OutputWriter(
    spark: SparkSession,
    io: RunFs,
    val runDir: Path,
    defaultPartitions: Int
) {

  /** A local run directory (tests and local tools). */
  def this(spark: SparkSession, runDir: File, defaultPartitions: Int) =
    this(
      spark,
      new RunFs(spark.sparkContext.hadoopConfiguration),
      RunFs.pathOf(runDir),
      defaultPartitions
    )

  private val pending = ArrayBuffer.empty[() => TableOutput]

  def write[R <: Product: TypeTag](
      ds: Dataset[R],
      name: String,
      relPath: String,
      mirror: Option[CoreMirror[R]],
      windowLabel: Option[String],
      uniqueKey: String,
      stores: Seq[String],
      sortKeys: Seq[String],
      partitions: Int = -1
  ): Unit = {
    val n = if (partitions > 0) partitions else defaultPartitions
    val dir = new Path(runDir, relPath)
    val keys = sortKeys.map(col)
    ds.toDF()
      .repartition(n, keys: _*)
      .sortWithinPartitions(keys: _*)
      .write
      .mode("errorifexists")
      .parquet(dir.toString)
    pending += (() => verify[R](name, relPath, mirror, windowLabel, uniqueKey, stores))
  }

  private def verify[R <: Product: TypeTag](
      name: String,
      relPath: String,
      mirror: Option[CoreMirror[R]],
      windowLabel: Option[String],
      uniqueKey: String,
      stores: Seq[String]
  ): TableOutput = {
    import spark.implicits._
    val dir = new Path(runDir, relPath)
    val ds = spark.read.parquet(dir.toString).as[R]
    val records = ds.count()
    val (invalid, samples, sampled, rtFail) = mirror match {
      case None    => (0L, Seq.empty[String], 0, 0)
      case Some(m) =>
        val errors = ds.map(r => m.errors(r).mkString("; ")).where(length(col("value")) > 0)
        val bad = errors.count()
        val badSamples = if (bad > 0) errors.limit(5).collect().toSeq else Nil
        val sample = ds.limit(20).collect().toSeq
        val failures = sample.count(r => m.roundTrip(r).isLeft)
        (bad, badSamples, sample.size, failures)
    }
    TableOutput(
      name,
      relPath,
      mirror.map(_.schema),
      mirror.map(m => org.scholarchain.core.SchemaVersions.currentOf(m.schema)),
      windowLabel,
      uniqueKey,
      stores,
      records,
      invalid,
      samples,
      sampled,
      rtFail,
      io.entries(runDir, dir)
    )
  }

  def verifyAll(): Seq[TableOutput] = pending.toList.map(_.apply())
}

/** Sums task metrics per pipeline stage (the `scholarchain.stage` local property). */
final class StageMetricsListener extends SparkListener {
  private val groupOfStage = new ConcurrentHashMap[Int, String]()
  private val totals = new ConcurrentHashMap[String, Array[Long]]()

  // index: 0 tasks, 1 stages, 2 max tasks per stage, 3 shuffle read bytes, 4 shuffle write bytes,
  // 5 shuffle read records, 6 shuffle write records, 7 memory spill bytes, 8 disk spill bytes,
  // 9 input bytes, 10 output bytes
  private def update(g: String)(f: Array[Long] => Unit): Unit = {
    totals.putIfAbsent(g, new Array[Long](11))
    val a = totals.get(g)
    a.synchronized(f(a))
  }

  override def onStageSubmitted(e: SparkListenerStageSubmitted): Unit = {
    val g = Option(e.properties).flatMap(p => Option(p.getProperty("scholarchain.stage")))
    g.foreach { name =>
      groupOfStage.put(e.stageInfo.stageId, name)
      update(name) { a =>
        a(1) += 1
        a(2) = math.max(a(2), e.stageInfo.numTasks.toLong)
      }
    }
  }

  override def onTaskEnd(e: SparkListenerTaskEnd): Unit = {
    val g = groupOfStage.get(e.stageId)
    val m = e.taskMetrics
    if (g != null && m != null) update(g) { a =>
      a(0) += 1
      a(3) += m.shuffleReadMetrics.totalBytesRead
      a(4) += m.shuffleWriteMetrics.bytesWritten
      a(5) += m.shuffleReadMetrics.recordsRead
      a(6) += m.shuffleWriteMetrics.recordsWritten
      a(7) += m.memoryBytesSpilled
      a(8) += m.diskBytesSpilled
      a(9) += m.inputMetrics.bytesRead
      a(10) += m.outputMetrics.bytesWritten
    }
  }

  override def onStageCompleted(e: SparkListenerStageCompleted): Unit = ()

  def snapshot: Map[String, Map[String, Long]] =
    totals.asScala.toMap.map { case (g, a) =>
      val c = a.synchronized(a.clone())
      g -> Map(
        "tasks" -> c(0),
        "stages" -> c(1),
        "maxTasksPerStage" -> c(2),
        "shuffleReadBytes" -> c(3),
        "shuffleWriteBytes" -> c(4),
        "shuffleReadRecords" -> c(5),
        "shuffleWriteRecords" -> c(6),
        "memorySpillBytes" -> c(7),
        "diskSpillBytes" -> c(8),
        "inputBytes" -> c(9),
        "outputBytes" -> c(10)
      )
    }
}

/** Run manifest, load manifests, and the reuse check. */
object Manifests {

  val RunManifestName = "_run_manifest.json"
  val RunReportName = "_run_report.json"
  val LoadDir = "_load_manifests"
  val LoadTargets: Seq[String] = Seq("neptune", "dynamodb", "s3vectors", "s3")

  /** Relative paths of the control files hashed into the run manifest (`controlFiles`). */
  val ControlFiles: Seq[String] = RunReportName +: LoadTargets.map(t => s"$LoadDir/$t.json")

  def fileJson(f: FileEntry): ujson.Value =
    ujson.Obj("path" -> f.path, "bytes" -> f.bytes.toDouble, "sha256" -> f.sha256)

  def tableJson(t: TableOutput): ujson.Obj =
    ujson.Obj(
      "table" -> t.name,
      "path" -> t.relPath,
      "coreRecord" -> t.coreRecord.map(ujson.Str(_)).getOrElse(ujson.Null),
      "schemaVersion" -> t.schemaVersion.map(v => ujson.Num(v.toDouble)).getOrElse(ujson.Null),
      "windowLabel" -> t.windowLabel.map(ujson.Str(_)).getOrElse(ujson.Null),
      "uniqueKey" -> t.uniqueKey,
      "records" -> t.records.toDouble,
      "invalidRecords" -> t.invalid.toDouble,
      "files" -> ujson.Arr(t.files.map(fileJson): _*)
    )

  /** Load manifests per store; each lists table paths, record counts, schema versions, run ID. */
  def loadManifests(
      runId: String,
      tables: Seq[TableOutput],
      policyVersions: Option[ujson.Value] = None
  ): Map[String, ujson.Value] = {
    LoadTargets.map { target =>
      val ts = tables.filter(_.stores.contains(target))
      target -> ujson.Obj.from(
        Seq[(String, ujson.Value)](
          "format" -> "scholarchain.spark.load-manifest",
          "formatVersion" -> 1,
          "target" -> target,
          "runId" -> runId
        ) ++ policyVersions.map("policyVersions" -> _) ++ Seq[(String, ujson.Value)](
          "runManifest" -> RunManifestName,
          "note" -> ("Paths are relative to the run directory. Evidence tables are keyed by " +
            "(evidenceId, window): EvidenceId.of ignores window and metric, so never " +
            "deduplicate or bundle evidence across windows."),
          "tables" -> ujson.Arr(ts.map { t =>
            val o = tableJson(t)
            o.value.remove("files")
            o: ujson.Value
          }: _*)
        )
      )
    }.toMap
  }

  /** Tables with rows failing core validation or with sampled round-trip failures. */
  def failingTables(tables: Seq[TableOutput]): Seq[TableOutput] =
    tables.filter(t => t.invalid > 0 || t.roundTripFailures > 0)

  /** Writes the run report, then, only when every table passed verification, the load manifests and
    * (last) the run manifest, whose presence marks a complete, verified run. When any table has
    * invalid rows or round-trip failures, the report records the failing counts and the run is
    * refused with [[OutputVerificationException]]: no load manifest and no run manifest is written,
    * so the output is never loaded or reused (a record without valid provenance is rejected, not
    * defaulted).
    */
  def writeVerified(
      runDir: File,
      runId: String,
      tables: Seq[TableOutput],
      runManifest: ujson.Value,
      runReport: ujson.Obj
  ): ujson.Obj =
    writeVerified(RunFs.local, RunFs.pathOf(runDir), runId, tables, runManifest, runReport)

  def writeVerified(
      io: RunFs,
      runDir: Path,
      runId: String,
      tables: Seq[TableOutput],
      runManifest: ujson.Value,
      runReport: ujson.Obj
  ): ujson.Obj = {
    val failing = failingTables(tables)
    val verification = ujson.Obj(
      "passed" -> failing.isEmpty,
      "failingTables" -> ujson.Arr(failing.map { t =>
        ujson.Obj(
          "table" -> t.name,
          "path" -> t.relPath,
          "invalid" -> t.invalid.toDouble,
          "invalidSamples" -> ujson.Arr(t.invalidSamples.map(ujson.Str(_)): _*),
          "roundTripSampled" -> t.roundTripSampled,
          "roundTripFailures" -> t.roundTripFailures
        ): ujson.Value
      }: _*)
    )
    val report = ujson.Obj.from(runReport.value.toSeq :+ ("verification" -> verification))
    io.writeString(new Path(runDir, RunReportName), ujson.write(report, indent = 2))
    if (failing.nonEmpty)
      throw new OutputVerificationException(
        s"run $runId refused: ${failing.size} exported table(s) failed core verification (" +
          failing
            .take(5)
            .map(t => s"${t.name}: invalid=${t.invalid}, roundTripFailures=${t.roundTripFailures}")
            .mkString("; ") +
          s"); no load manifests and no $RunManifestName were written, see $RunReportName"
      )
    val policyVersions = runManifest.objOpt.flatMap(_.get("policyVersions"))
    loadManifests(runId, tables, policyVersions).foreach { case (target, json) =>
      io.writeString(new Path(runDir, s"$LoadDir/$target.json"), ujson.write(json, indent = 2))
    }
    // Phase 6 F6: the run report and the load manifests are hashed into the run manifest, so a
    // reuse check can verify them (they are `_`-prefixed and not data files).
    val control = ControlFiles.map { rel =>
      val f = new Path(runDir, rel)
      FileEntry(rel, io.length(f), io.sha256(f))
    }
    val withControl = ujson.Obj.from(
      runManifest.obj.toSeq.filterNot(_._1 == "controlFiles") :+
        ("controlFiles" -> ujson.Arr(control.map(fileJson): _*))
    )
    // The run manifest is written last: its presence marks a complete, verified run.
    io.writeString(new Path(runDir, RunManifestName), ujson.write(withControl, indent = 2))
    report
  }

  /** [[verifyExisting]] for a local run directory (the JSON Lines export and local tools). */
  def verifyExisting(
      runDir: File,
      runId: String,
      expected: Seq[(String, String)],
      requireControlFiles: Boolean
  ): ujson.Value =
    verifyExisting(RunFs.local, RunFs.pathOf(runDir), runId, expected, requireControlFiles)

  def verifyExisting(runDir: File, runId: String): ujson.Value =
    verifyExisting(runDir, runId, Nil, requireControlFiles = false)

  /** Verifies an existing run directory against its manifest. Returns the manifest when every
    * listed file exists with the recorded size and sha256, no unlisted data file exists, the run ID
    * matches and every `expected` manifest field (for example `codeVersion`) holds the given
    * string; otherwise refuses with [[RunRefusedException]].
    *
    * With `requireControlFiles` (the pipeline's reuse check, Phase 6 F6), the manifest must also
    * list `controlFiles` (`_run_report.json` and every `_load_manifests/<target>.json`) with sizes
    * and sha256 that match, and `_load_manifests/` must hold no unlisted file. A manifest written
    * before control files were recorded is refused.
    */
  def verifyExisting(
      io: RunFs,
      runDir: Path,
      runId: String,
      expected: Seq[(String, String)],
      requireControlFiles: Boolean
  ): ujson.Value = {
    val mf = new Path(runDir, RunManifestName)
    if (!io.isFile(mf))
      throw new RunRefusedException(
        s"$runDir exists without $RunManifestName (an incomplete or foreign output); " +
          "refusing to reuse it. Use a new attempt root."
      )
    val m = ujson.read(io.readString(mf))
    if (m("runId").str != runId)
      throw new RunRefusedException(s"$runDir manifest runId ${m("runId").str} != expected $runId")
    expected.foreach { case (k, v) =>
      val recorded = m.obj.get(k).flatMap(_.strOpt)
      if (!recorded.contains(v))
        throw new RunRefusedException(
          s"$runDir manifest $k ${recorded.getOrElse("(absent)")} != current $v; " +
            "refusing to reuse it"
        )
    }
    def entries(field: String) = m(field).arr.toSeq.map { f =>
      (f("path").str, f("bytes").num.toLong, f("sha256").str)
    }
    val listed = m("outputs").arr.toSeq.flatMap(_("files").arr).map { f =>
      (f("path").str, f("bytes").num.toLong, f("sha256").str)
    }
    def check(xs: Seq[(String, Long, String)]) = xs.flatMap { case (p, bytes, sha) =>
      val f = new Path(runDir, p)
      if (!io.isFile(f)) Some(s"missing $p")
      else if (io.length(f) != bytes) Some(s"size changed $p")
      else if (io.sha256(f) != sha) Some(s"content changed $p")
      else None
    }
    val controlProblems =
      if (!requireControlFiles) Nil
      else if (!m.obj.contains("controlFiles"))
        List(s"no controlFiles in $RunManifestName (written before F6)")
      else {
        val control = entries("controlFiles")
        val names = control.map(_._1).toSet
        val missingNames = ControlFiles.filterNot(names).map(p => s"control file not listed $p")
        val present = io
          .listFiles(new Path(runDir, LoadDir))
          .map(f => s"$LoadDir/${f.getName}")
          .filterNot(p => p.startsWith(s"$LoadDir/.")) // local checksum files
        val unlisted = present.filterNot(names).sorted.map(p => s"unlisted control file $p")
        missingNames ++ check(control) ++ unlisted
      }
    val listedPaths = listed.map(_._1).toSet
    val extra = io.listData(runDir).map(io.rel(runDir, _)).filterNot(listedPaths)
    val all =
      check(listed).toList ++ extra.map(p => s"unlisted file $p") ++ controlProblems
    if (all.nonEmpty)
      throw new RunRefusedException(
        s"$runDir does not match its manifest (${all.size} problems, first: ${all.take(5).mkString(", ")}); refusing to reuse it"
      )
    m
  }
}
