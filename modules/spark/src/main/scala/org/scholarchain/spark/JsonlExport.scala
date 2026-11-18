package org.scholarchain.spark

import java.io.File
import java.security.MessageDigest

import scala.reflect.runtime.universe.TypeTag
import scala.util.control.NonFatal
import scala.util.matching.Regex

import org.apache.hadoop.fs.Path
import org.apache.spark.sql.{Encoders, SparkSession}
import org.apache.spark.sql.functions.col
import org.scholarchain.core._
import upickle.default.ReadWriter

/** Raised when a row fails core validation on the way out (no export manifest is written). */
final class ExportValidationException(msg: String) extends RuntimeException(msg)

/** Raised when an exported table's line count differs from its load-manifest count, or when the
  * load manifests disagree with each other or with the source run (no export manifest is written).
  */
final class ExportCountMismatchException(msg: String) extends RuntimeException(msg)

/** Raised when the source run or the export target is not usable (nothing is written). */
final class ExportRefusedException(msg: String) extends RuntimeException(msg)

/** What the entry point throws instead of any failure: the message is masked (no bucket name, no
  * account ID) and the cause is dropped, so the step log holds no identifier.
  */
final class JsonlExportFailedException(msg: String) extends RuntimeException(msg)

/** Line codec of one exported table kind: turns a stored row into one `Contract.encode` JSON line
  * (validated on the way out) and checks a written line by decoding it with `Contract.decode` and
  * re-encoding it to the identical text.
  *
  * Every implementation is a singleton object whose methods resolve the core uPickle and Validator
  * instances statically, so a codec can travel in a Spark closure.
  */
sealed abstract class JsonlCodec[R](
    val coreRecord: Option[String],
    /** Columns of the stored row that are not part of the core record and are not exported. */
    val sourceColumnsNotExported: Seq[String]
) extends Serializable {
  def line(r: R): Either[String, String]
  def check(line: String): Either[String, Unit]
}

object JsonlCodec {

  def encodeCore[C](c: Either[String, C])(implicit
      rw: ReadWriter[C],
      v: Validator[C]
  ): Either[String, String] = c.flatMap(x => Contract.encode(x).left.map(_.message))

  def checkCore[C](
      line: String
  )(implicit rw: ReadWriter[C], v: Validator[C]): Either[String, Unit] =
    for {
      x <- Contract.decode[C](line).left.map(_.message)
      again <- Contract.encode(x).left.map(_.message)
      _ <- if (again == line) Right(()) else Left("decode and re-encode changed the line")
    } yield ()

  private def checkJsonObject(line: String): Either[String, Unit] =
    try {
      ujson.read(line) match {
        case _: ujson.Obj => Right(())
        case _            => Left("not a JSON object")
      }
    } catch { case e: Exception => Left(s"not JSON: ${e.getMessage}") }

  object Node extends JsonlCodec[GraphNodeRow](Some("GraphNode"), Nil) {
    def line(r: GraphNodeRow) = encodeCore(r.toCore)
    def check(l: String) = checkCore[GraphNode](l)
  }
  object Edge extends JsonlCodec[GraphEdgeRow](Some("GraphEdge"), Nil) {
    def line(r: GraphEdgeRow) = encodeCore(r.toCore)
    def check(l: String) = checkCore[GraphEdge](l)
  }
  object Researcher extends JsonlCodec[CanonicalResearcherRow](Some("CanonicalResearcher"), Nil) {
    def line(r: CanonicalResearcherRow) = encodeCore(r.toCore)
    def check(l: String) = checkCore[CanonicalResearcher](l)
  }
  object Authorship extends JsonlCodec[AuthorshipRow](Some("AuthorshipRecord"), Nil) {
    def line(r: AuthorshipRow) = encodeCore(r.toCore)
    def check(l: String) = checkCore[AuthorshipRecord](l)
  }
  object Mention extends JsonlCodec[AuthorMentionRow](Some("AuthorMention"), Nil) {
    def line(r: AuthorMentionRow) = encodeCore(r.toCore)
    def check(l: String) = checkCore[AuthorMention](l)
  }
  object CoauthorEdge extends JsonlCodec[CoauthorEdgeRow](Some("CoauthorEdge"), Nil) {
    def line(r: CoauthorEdgeRow) = encodeCore(r.toCore)
    def check(l: String) = checkCore[org.scholarchain.core.CoauthorEdge](l)
  }
  object Metrics extends JsonlCodec[ResearcherMetricsRow](Some("ResearcherMetrics"), Nil) {
    def line(r: ResearcherMetricsRow) = encodeCore(r.toCore)
    def check(l: String) = checkCore[ResearcherMetrics](l)
  }
  object Membership extends JsonlCodec[GroupMembershipRow](Some("ResearcherGroupMembership"), Nil) {
    def line(r: GroupMembershipRow) = encodeCore(r.toCore)
    def check(l: String) = checkCore[ResearcherGroupMembership](l)
  }
  object MetricEvidence
      extends JsonlCodec[MetricEvidenceRow](Some("EvidenceItem"), Seq("researcherId", "works")) {
    def line(r: MetricEvidenceRow) = encodeCore(r.evidence.toCore)
    def check(l: String) = checkCore[EvidenceItem](l)
  }
  object PathEvidence
      extends JsonlCodec[PathEvidenceRow](
        Some("EvidenceItem"),
        Seq("pairKind", "pairIndex", "sourceId", "targetId", "hopCount", "hops")
      ) {
    def line(r: PathEvidenceRow) = encodeCore(r.evidence.toCore)
    def check(l: String) = checkCore[EvidenceItem](l)
  }
  object Summary extends JsonlCodec[SemanticSummaryRow](Some("EvidenceItem"), Seq("summaryKind")) {
    def line(r: SemanticSummaryRow) = encodeCore(r.evidence.toCore)
    def check(l: String) = checkCore[EvidenceItem](l)
  }

  /** Versioned reject record (`scholarchain.spark.reject` v1); not a core record. */
  object Reject extends JsonlCodec[RejectRow](None, Nil) {
    def line(r: RejectRow): Either[String, String] = Right(
      ujson.write(
        ujson.Obj(
          "table" -> r.table,
          "schema" -> r.schema,
          "reasons" -> ujson.Arr(r.reasons.map(ujson.Str(_)): _*),
          "rawSha256" -> r.rawSha256,
          "rawLength" -> r.rawLength,
          "raw" -> r.raw
        )
      )
    )
    def check(l: String) = checkJsonObject(l)
  }

  /** Integrity violation (`scholarchain.spark.integrity-violation` v1); not a core record. */
  object Integrity extends JsonlCodec[IntegrityViolationRow](None, Nil) {
    def line(r: IntegrityViolationRow): Either[String, String] = Right(
      ujson.write(
        ujson.Obj(
          "check" -> r.check,
          "windowLabel" -> r.windowLabel,
          "subjectId" -> r.subjectId,
          "detail" -> r.detail
        )
      )
    )
    def check(l: String) = checkJsonObject(l)
  }
}

/** One table listed in the load manifests (merged across the four targets). */
final case class LoadTable(
    table: String,
    path: String,
    coreRecord: Option[String],
    schemaVersion: Option[Int],
    windowLabel: Option[String],
    records: Long,
    targets: Seq[String]
)

final case class ExportedFile(path: String, bytes: Long, sha256: String, records: Long)

final case class ExportedTable(
    source: LoadTable,
    sourceColumnsNotExported: Seq[String],
    files: Seq[ExportedFile],
    wallMillis: Long
) {
  def records: Long = files.map(_.records).sum
  def bytes: Long = files.map(_.bytes).sum
}

/** Result of one export. `outRun` is the qualified `<outRoot>/run=<hex>` path. */
final case class ExportResult(
    runId: String,
    outRun: Path,
    tables: Seq[ExportedTable],
    manifest: ujson.Value,
    report: ujson.Value
) {

  /** The export directory as a local file; only for `file:` exports (tests and local tools). */
  def outRunDir: File =
    if (outRun.toUri.getScheme == "file") new File(outRun.toUri)
    else throw new UnsupportedOperationException(s"${JsonlExport.show(outRun)} is not local")
}

/** JSON Lines export of a verified Phase 6 run (work order phase-07, decision 5).
  *
  * Reads every table listed in the four load manifests (`_load_manifests/{neptune,dynamodb,
  * s3vectors,s3}.json`) of a source run and writes it as JSON Lines under
  * `<outRoot>/run=<hex>/<same table path>/part-NNNNN.jsonl`: one `Contract.encode` record per line
  * (UTF-8, LF), validated on the way out. Rows of evidence tables export their `evidence` column
  * (the core `EvidenceItem`); the other stored columns of those rows are listed in the manifest as
  * `sourceColumnsNotExported`. `rejects` and `integrity_violations` are not core records and are
  * written as their versioned JSON objects.
  *
  * Nothing is recomputed and the source is only read: the source run is first verified against its
  * `_run_manifest.json` (every file's size and sha256), and the run ID stays the same.
  *
  * Determinism: each table is hash-partitioned by the full line text into `ceil(records /
  * recordsPerFile)` files (at most `maxFiles`) and sorted by line within a file, so the bytes are
  * identical for the same source. Files are renamed to `part-00000.jsonl`, ... in Spark partition
  * order.
  *
  * Checks before `_export_manifest.json` is written (it is written last; its presence marks a
  * complete export): the load manifests agree with one another; each line count equals the
  * load-manifest count (otherwise [[ExportCountMismatchException]]); every written line decodes
  * with `Contract.decode` and re-encodes to the same text (otherwise
  * [[ExportValidationException]]). A report `_export_report.json` (timings, `codeVersion`, the
  * jar's [[BuildIdentity]]) is written just before it.
  *
  * File access (Phase 13 decision 7c): the source run and the output go through the Hadoop
  * FileSystem API ([[RunFs]]), so `s3://`, `hdfs://`, `file:///` and plain local paths work alike;
  * a plain path resolves against the default file system (local in tests, HDFS on EMR). Every check
  * that can refuse (arguments, code version, source verification, load manifests, codecs, target
  * location, an existing target) runs before anything is written.
  *
  * Entry point: `spark-submit --class org.scholarchain.spark.JsonlExport JAR` with the options of
  * [[Required]] and [[Optional]] (`--name value` pairs; anything else is refused).
  */
object JsonlExport {

  val ManifestName = "_export_manifest.json"
  val ReportName = "_export_report.json"
  val Targets: Seq[String] = Seq("neptune", "dynamodb", "s3vectors", "s3")

  /** Required options of the entry point (without the `--` prefix); the scripts' contract. */
  val Required: List[String] = List("source-run", "out-root")

  /** Optional options of the entry point (without the `--` prefix). */
  val Optional: List[String] = List("records-per-file", "code-version")

  val DefaultRecordsPerFile: Long = 250000L

  /** Allowed `--code-version` text (a build commit, a tag or a short label; never a path). */
  val CodeVersionPattern: Regex = "[A-Za-z0-9][A-Za-z0-9._+-]{0,99}".r

  final case class Args(
      sourceRun: Path,
      outRoot: Path,
      recordsPerFile: Long,
      codeVersion: Option[String]
  )

  /** Parses `--name value` pairs against [[Required]] and [[Optional]]. Refuses an odd count, a
    * name without `--`, a repeated or unknown name, a missing required name and an invalid value.
    * Values are never echoed in the messages.
    */
  def parseArgs(argv: Seq[String]): Either[String, Args] = {
    def path(k: String, v: String): Either[String, Path] =
      try {
        if (v.trim.isEmpty) Left(s"--$k is empty") else Right(new Path(v))
      } catch { case NonFatal(_) => Left(s"--$k is not a valid path or URI") }
    for {
      _ <- if (argv.size % 2 == 0) Right(()) else Left("arguments must be --name value pairs")
      pairs <- argv
        .grouped(2)
        .toList
        .foldLeft[Either[String, List[(String, String)]]](Right(Nil)) {
          case (Right(acc), Seq(k, v)) if k.startsWith("--") => Right(acc :+ (k.drop(2) -> v))
          case (Right(_), Seq(_, _)) => Left("expected --name before each value")
          case (left, _)             => left
        }
      names = pairs.map(_._1)
      dup = names.diff(names.distinct).distinct
      _ <- if (dup.isEmpty) Right(()) else Left(s"repeated arguments: ${dup.mkString(", ")}")
      unknown = names.filterNot(n => Required.contains(n) || Optional.contains(n))
      _ <- if (unknown.isEmpty) Right(()) else Left(s"unknown arguments: ${unknown.mkString(", ")}")
      missing = Required.filterNot(names.contains)
      _ <-
        if (missing.isEmpty) Right(())
        else Left(s"missing arguments: ${missing.map("--" + _).mkString(", ")}")
      m = pairs.toMap
      s <- path("source-run", m("source-run"))
      o <- path("out-root", m("out-root"))
      n <- m.get("records-per-file") match {
        case None    => Right(DefaultRecordsPerFile)
        case Some(v) =>
          scala.util.Try(v.toLong).toOption.filter(_ > 0).toRight("--records-per-file must be > 0")
      }
      v <- m.get("code-version") match {
        case None    => Right(None)
        case Some(x) =>
          if (CodeVersionPattern.pattern.matcher(x).matches()) Right(Some(x))
          else Left(s"--code-version must match ${CodeVersionPattern.regex}")
      }
    } yield Args(s, o, n, v)
  }

  private val S3Authority = "(s3[an]?://)[^/\\s\"']+".r
  private val ArnAccount = "(arn:[a-z0-9-]+:[a-z0-9-]+:[a-z0-9-]*:)[0-9]{12}:".r

  /** A printable location: the plain path for `file:` paths; otherwise the authority (bucket or
    * name node) is masked: `s3://{data-bucket}/KEY`, `hdfs://{authority}/PATH`.
    */
  def show(p: Path): String = {
    val u = p.toUri
    Option(u.getScheme) match {
      case None | Some("file")             => u.getPath
      case Some(s) if s.matches("s3[an]?") => s"$s://{data-bucket}${u.getPath}"
      case Some(s)                         => s"$s://{authority}${u.getPath}"
    }
  }

  /** Masks bucket names (`s3://NAME/`, and every literal name in `authorities`) and the account in
    * ARNs, for messages that leave the job.
    */
  def maskText(text: String, authorities: Seq[String] = Nil): String = {
    val known = authorities.filter(a => a != null && a.nonEmpty).distinct.sortBy(-_.length)
    val a = known.foldLeft(text)((t, x) => t.replace(x, "{data-bucket}"))
    val b = S3Authority.replaceAllIn(a, m => Regex.quoteReplacement(m.group(1) + "{data-bucket}"))
    ArnAccount.replaceAllIn(b, m => Regex.quoteReplacement(m.group(1) + "{account}:"))
  }

  private def authorityOf(p: Path): Option[String] =
    Option(p.toUri.getScheme).filterNot(_ == "file").flatMap(_ => Option(p.toUri.getAuthority))

  private def optStr(v: ujson.Value): Option[String] = v match {
    case ujson.Null => None
    case s          => Some(s.str)
  }
  private def optInt(v: ujson.Value): Option[Int] = v match {
    case ujson.Null => None
    case n          => Some(n.num.toInt)
  }

  /** Reads the four load manifests and merges their tables by name (first appearance order). A
    * table listed by several targets must have the same path, record, schema version, window and
    * count.
    */
  def loadTables(
      io: RunFs,
      sourceRun: Path
  ): (String, Seq[LoadTable], Map[String, String]) = {
    val dir = new Path(sourceRun, Manifests.LoadDir)
    val parsed = Targets.map { t =>
      val f = new Path(dir, s"$t.json")
      if (!io.isFile(f))
        throw new ExportRefusedException(s"missing load manifest ${Manifests.LoadDir}/$t.json")
      (t, ujson.read(io.readString(f)), io.sha256(f))
    }
    val runIds = parsed.map(_._2("runId").str).distinct
    if (runIds.size != 1)
      throw new ExportRefusedException(
        s"load manifests disagree on runId: ${runIds.mkString(", ")}"
      )
    val merged = scala.collection.mutable.LinkedHashMap.empty[String, LoadTable]
    parsed.foreach { case (target, m, _) =>
      if (m("target").str != target)
        throw new ExportRefusedException(s"$target.json has target ${m("target").str}")
      m("tables").arr.foreach { t =>
        val lt = LoadTable(
          t("table").str,
          t("path").str,
          optStr(t("coreRecord")),
          optInt(t("schemaVersion")),
          optStr(t("windowLabel")),
          t("records").num.toLong,
          Seq(target)
        )
        if (t("invalidRecords").num.toLong != 0L)
          throw new ExportRefusedException(s"${lt.table}: load manifest reports invalid records")
        merged.get(lt.table) match {
          case None       => merged(lt.table) = lt
          case Some(prev) =>
            if (prev.copy(targets = Nil) != lt.copy(targets = Nil))
              throw new ExportCountMismatchException(
                s"${lt.table}: load manifests disagree (${prev.targets.mkString(",")}: $prev; $target: $lt)"
              )
            merged(lt.table) = prev.copy(targets = prev.targets :+ target)
        }
      }
    }
    (runIds.head, merged.values.toList, parsed.map(p => p._1 -> p._3).toMap)
  }

  /** The codec for a table path; the manifest's core record must be the one the path holds. */
  private def codecFor(t: LoadTable): JsonlCodec[_] = {
    val p = t.path
    val c: JsonlCodec[_] =
      if (p.startsWith("nodes/")) JsonlCodec.Node
      else if (p.startsWith("edges/")) JsonlCodec.Edge
      else if (p == "canonical_researcher") JsonlCodec.Researcher
      else if (p == "authorship") JsonlCodec.Authorship
      else if (p == "unresolved_mention") JsonlCodec.Mention
      else if (p.startsWith("coauthor_edge/")) JsonlCodec.CoauthorEdge
      else if (p.startsWith("metrics/") || p.startsWith("bridges/") || p.startsWith("kcore/"))
        JsonlCodec.Metrics
      else if (p.startsWith("components/") || p.startsWith("communities/")) JsonlCodec.Membership
      else if (p.startsWith("evidence/evidence_type=metric/")) JsonlCodec.MetricEvidence
      else if (p.startsWith("path_evidence/")) JsonlCodec.PathEvidence
      else if (p.startsWith("semantic_summary/")) JsonlCodec.Summary
      else if (p == "rejects/v1") JsonlCodec.Reject
      else if (p == "integrity_violations/v1") JsonlCodec.Integrity
      else throw new ExportRefusedException(s"${t.table}: no exporter for table path '$p'")
    if (c.coreRecord != t.coreRecord)
      throw new ExportRefusedException(
        s"${t.table}: load manifest says core record ${t.coreRecord.getOrElse("none")}, " +
          s"path '$p' holds ${c.coreRecord.getOrElse("none")}"
      )
    t.coreRecord.foreach { r =>
      val current = SchemaVersions.currentOf(r)
      if (!t.schemaVersion.contains(current))
        throw new ExportRefusedException(
          s"${t.table}: schema version ${t.schemaVersion.getOrElse("none")} is not current $r v$current"
        )
    }
    c
  }

  /** sha256, byte count and LF count of a file, in one streaming pass. */
  private def scan(io: RunFs, f: Path): (Long, String, Long) = {
    val md = MessageDigest.getInstance("SHA-256")
    val in = io.fs(f).open(f)
    var bytes = 0L
    var lines = 0L
    var last: Byte = '\n'
    try {
      val buf = new Array[Byte](1 << 20)
      var n = in.read(buf)
      while (n >= 0) {
        var i = 0
        while (i < n) {
          if (buf(i) == '\n') lines += 1
          i += 1
        }
        if (n > 0) {
          md.update(buf, 0, n)
          bytes += n
          last = buf(n - 1)
        }
        n = in.read(buf)
      }
    } finally in.close()
    if (bytes > 0 && last != '\n')
      throw new ExportValidationException(s"${show(f)} does not end with a line feed")
    (bytes, md.digest().map(b => f"${b & 0xff}%02x").mkString, lines)
  }

  private def exportTable[R <: Product: TypeTag](
      spark: SparkSession,
      t: LoadTable,
      codec: JsonlCodec[R],
      src: Path,
      dst: Path,
      files: Int
  ): Unit = {
    import spark.implicits._
    val name = t.table
    val c = codec
    spark.read
      .parquet(src.toString)
      .as[R]
      .map(r =>
        c.line(r) match {
          case Right(l) => l
          case Left(e)  => throw new ExportValidationException(s"$name: invalid record: $e")
        }
      )(Encoders.STRING)
      .repartition(files, col("value"))
      .sortWithinPartitions(col("value"))
      .write
      .mode("errorifexists")
      .option("mapreduce.fileoutputcommitter.marksuccessfuljobs", "false")
      .text(dst.toString)
  }

  private def exportAny(
      spark: SparkSession,
      t: LoadTable,
      codec: JsonlCodec[_],
      src: Path,
      dst: Path,
      files: Int
  ): Unit = codec match {
    case JsonlCodec.Node         => exportTable(spark, t, JsonlCodec.Node, src, dst, files)
    case JsonlCodec.Edge         => exportTable(spark, t, JsonlCodec.Edge, src, dst, files)
    case JsonlCodec.Researcher   => exportTable(spark, t, JsonlCodec.Researcher, src, dst, files)
    case JsonlCodec.Authorship   => exportTable(spark, t, JsonlCodec.Authorship, src, dst, files)
    case JsonlCodec.Mention      => exportTable(spark, t, JsonlCodec.Mention, src, dst, files)
    case JsonlCodec.CoauthorEdge => exportTable(spark, t, JsonlCodec.CoauthorEdge, src, dst, files)
    case JsonlCodec.Metrics      => exportTable(spark, t, JsonlCodec.Metrics, src, dst, files)
    case JsonlCodec.Membership   => exportTable(spark, t, JsonlCodec.Membership, src, dst, files)
    case JsonlCodec.MetricEvidence =>
      exportTable(spark, t, JsonlCodec.MetricEvidence, src, dst, files)
    case JsonlCodec.PathEvidence => exportTable(spark, t, JsonlCodec.PathEvidence, src, dst, files)
    case JsonlCodec.Summary      => exportTable(spark, t, JsonlCodec.Summary, src, dst, files)
    case JsonlCodec.Reject       => exportTable(spark, t, JsonlCodec.Reject, src, dst, files)
    case JsonlCodec.Integrity    => exportTable(spark, t, JsonlCodec.Integrity, src, dst, files)
  }

  /** Renames Spark's part files to `part-NNNNN.jsonl` in partition order (through the Hadoop file
    * system, which also renames local checksum files) and scans them.
    */
  private def finalizeFiles(
      spark: SparkSession,
      io: RunFs,
      outRun: Path,
      dir: Path
  ): Seq[ExportedFile] = {
    // The checksum-aware file system for local paths, so `.crc` side files are renamed too.
    val fs = dir.getFileSystem(spark.sparkContext.hadoopConfiguration)
    val parts =
      if (!fs.exists(dir)) Nil
      else fs.listStatus(dir).toList.map(_.getPath).filter(_.getName.startsWith("part-"))
    parts.sortBy(_.getName).zipWithIndex.foreach { case (p, i) =>
      val target = new Path(dir, f"part-$i%05d.jsonl")
      if (!fs.rename(p, target))
        throw new ExportRefusedException(s"could not rename ${show(p)} to ${show(target)}")
    }
    io.listData(dir).map { f =>
      val (bytes, sha, lines) = scan(io, f)
      ExportedFile(io.rel(outRun, f), bytes, sha, lines)
    }
  }

  /** Decodes every written line; returns (lines, failures, up to 5 failure samples). */
  private def verifyLines(
      spark: SparkSession,
      io: RunFs,
      codec: JsonlCodec[_],
      dst: Path
  ): (Long, Long, Seq[String]) = {
    if (io.listData(dst).isEmpty) return (0L, 0L, Nil)
    val c = codec
    val lines = spark.read.textFile(dst.toString)
    val errors = lines
      .map(l => c.check(l).fold(e => s"$e: ${l.take(200)}", _ => ""))(Encoders.STRING)
      .where(col("value") =!= "")
    val bad = errors.count()
    (lines.count(), bad, if (bad > 0) errors.limit(5).collect().toSeq else Nil)
  }

  def fileJson(f: ExportedFile): ujson.Value =
    ujson.Obj(
      "path" -> f.path,
      "bytes" -> f.bytes.toDouble,
      "sha256" -> f.sha256,
      "records" -> f.records.toDouble
    )

  def manifestJson(
      runId: String,
      loadManifestSha: Map[String, String],
      sourceRunManifestSha: String,
      recordsPerFile: Long,
      tables: Seq[ExportedTable]
  ): ujson.Value = {
    def opt(o: Option[String]) = o.map(ujson.Str(_)).getOrElse(ujson.Null)
    ujson.Obj(
      "format" -> "scholarchain.spark.jsonl-export",
      "formatVersion" -> 1,
      "sourceRunId" -> runId,
      "runId" -> runId,
      "sourceRunManifestSha256" -> sourceRunManifestSha,
      "sourceLoadManifestsSha256" -> ujson.Obj.from(
        Targets.map(t => s"${Manifests.LoadDir}/$t.json" -> ujson.Str(loadManifestSha(t)))
      ),
      "encoding" -> ("JSON Lines, UTF-8, LF: one Contract.encode record per line (core record " +
        "tables) or one versioned JSON object per line (rejects, integrity_violations)"),
      "lineOrder" -> ("hash-partitioned by line text into ceil(records / recordsPerFile) files " +
        "(at most 32), sorted by line text within a file"),
      "recordsPerFile" -> recordsPerFile.toDouble,
      "tables" -> ujson.Arr(tables.map { t =>
        val s = t.source
        ujson.Obj(
          "table" -> s.table,
          "path" -> s.path,
          "targets" -> ujson.Arr(s.targets.map(ujson.Str(_)): _*),
          "coreRecord" -> opt(s.coreRecord),
          "schemaVersion" -> s.schemaVersion.map(v => ujson.Num(v.toDouble)).getOrElse(ujson.Null),
          "windowLabel" -> opt(s.windowLabel),
          "records" -> t.records.toDouble,
          "loadManifestRecords" -> s.records.toDouble,
          "sourceColumnsNotExported" -> ujson.Arr(t.sourceColumnsNotExported.map(ujson.Str(_)): _*),
          "files" -> ujson.Arr(t.files.map(fileJson): _*)
        ): ujson.Value
      }: _*),
      "totals" -> ujson.Obj(
        "tables" -> tables.size,
        "records" -> tables.map(_.records).sum.toDouble,
        "files" -> tables.map(_.files.size).sum,
        "bytes" -> tables.map(_.bytes).sum.toDouble
      )
    )
  }

  /** [[run]] for local directories (tests and local tools). */
  def run(
      spark: SparkSession,
      sourceRunDir: File,
      outRoot: File,
      recordsPerFile: Long,
      maxFiles: Int
  ): ExportResult =
    run(spark, RunFs.pathOf(sourceRunDir), RunFs.pathOf(outRoot), recordsPerFile, maxFiles, None)

  def run(
      spark: SparkSession,
      sourceRunDir: File,
      outRoot: File,
      recordsPerFile: Long
  ): ExportResult =
    run(spark, sourceRunDir, outRoot, recordsPerFile, 32)

  def run(spark: SparkSession, sourceRunDir: File, outRoot: File): ExportResult =
    run(spark, sourceRunDir, outRoot, DefaultRecordsPerFile, 32)

  /** Exports the source run (`.../run=<hex>`) to `<outRoot>/run=<hex>`, both Hadoop paths.
    *
    * @param codeVersion
    *   the `--code-version` the scripts pass (the build commit); recorded in the report, `null`
    *   when absent
    * @param build
    *   the jar's build identity, recorded in the report
    */
  def run(
      spark: SparkSession,
      sourceRun: Path,
      outRoot: Path,
      recordsPerFile: Long,
      maxFiles: Int,
      codeVersion: Option[String],
      build: BuildIdentity = BuildIdentity.load()
  ): ExportResult = {
    require(recordsPerFile > 0 && maxFiles > 0, "recordsPerFile and maxFiles must be positive")
    val t0 = System.nanoTime()
    val io = new RunFs(spark.sparkContext.hadoopConfiguration)
    // Every check that can refuse runs before the first write.
    codeVersion.foreach { v =>
      if (!CodeVersionPattern.pattern.matcher(v).matches())
        throw new ExportRefusedException(s"code version must match ${CodeVersionPattern.regex}")
    }
    val src = io.qualify(sourceRun)
    if (!io.isDirectory(src))
      throw new ExportRefusedException(s"source run ${show(src)} is not a directory")
    val (runId, tables, loadSha) = loadTables(io, src)
    val hex = RunId
      .parse(runId)
      .fold(e => throw new ExportRefusedException(e), _.value.stripPrefix(RunId.Prefix))
    if (src.getName != s"run=$hex")
      throw new ExportRefusedException(s"source directory ${src.getName} is not run=$hex")
    // The source must be the complete, unchanged run: every file matches its run manifest.
    try Manifests.verifyExisting(io, src, runId, Nil, requireControlFiles = false)
    catch {
      case e: RunRefusedException =>
        throw new ExportRefusedException(maskText(e.getMessage, authorityOf(src).toSeq))
    }
    val runManifestSha = io.sha256(new Path(src, Manifests.RunManifestName))
    val codecs = tables.map(t => t -> codecFor(t))
    val outRun = new Path(io.qualify(outRoot), s"run=$hex")
    val (srcText, outText) = (src.toString.stripSuffix("/"), outRun.toString.stripSuffix("/"))
    if (
      outText == srcText || outText.startsWith(srcText + "/") || srcText.startsWith(outText + "/")
    )
      throw new ExportRefusedException(
        s"export ${show(outRun)} and source ${show(src)} overlap; use an output root outside the run"
      )
    if (io.exists(outRun))
      throw new ExportRefusedException(
        s"${show(outRun)} already exists; refusing to overwrite an export. Use a new output root."
      )
    val tVerified = System.nanoTime()
    io.mkdirs(outRun)
    val exported = codecs.map { case (t, codec) =>
      val ts = System.nanoTime()
      val from = new Path(src, t.path)
      val dst = new Path(outRun, t.path)
      val files =
        math.max(1L, math.min(maxFiles.toLong, (t.records + recordsPerFile - 1) / recordsPerFile))
      exportAny(spark, t, codec, from, dst, files.toInt)
      val written = finalizeFiles(spark, io, outRun, dst)
      val et = ExportedTable(
        t,
        codec.sourceColumnsNotExported,
        written,
        (System.nanoTime() - ts) / 1000000L
      )
      if (et.records != t.records)
        throw new ExportCountMismatchException(
          s"${t.table}: exported ${et.records} lines, load manifest lists ${t.records}; " +
            s"no $ManifestName was written"
        )
      val (lines, bad, samples) = verifyLines(spark, io, codec, dst)
      if (bad > 0)
        throw new ExportValidationException(
          s"${t.table}: $bad written line(s) failed decode/re-encode (${samples.mkString(" | ")})"
        )
      if (lines != t.records)
        throw new ExportCountMismatchException(
          s"${t.table}: Spark read $lines lines, load manifest lists ${t.records}"
        )
      println(
        s"[export] ${t.table} records=${et.records} files=${written.size} bytes=${et.bytes} " +
          s"ms=${(System.nanoTime() - ts) / 1000000L}"
      )
      et.copy(wallMillis = (System.nanoTime() - ts) / 1000000L)
    }
    val manifest = manifestJson(runId, loadSha, runManifestSha, recordsPerFile, exported)
    val wall = (System.nanoTime() - t0) / 1000000L
    val report = ujson.Obj(
      "format" -> "scholarchain.spark.jsonl-export-report",
      "formatVersion" -> 1,
      "runId" -> runId,
      "codeVersion" -> codeVersion.map(ujson.Str(_)).getOrElse(ujson.Null),
      "buildIdentity" -> build.toJson,
      "wallMillis" -> wall.toDouble,
      "sourceVerifyMillis" -> ((tVerified - t0) / 1000000L).toDouble,
      "tables" -> ujson.Arr(exported.map { t =>
        ujson.Obj(
          "table" -> t.source.table,
          "records" -> t.records.toDouble,
          "files" -> t.files.size,
          "bytes" -> t.bytes.toDouble,
          "wallMillis" -> t.wallMillis.toDouble
        ): ujson.Value
      }: _*),
      "totals" -> manifest("totals")
    )
    io.writeNewString(new Path(outRun, ReportName), ujson.write(report, indent = 2))
    // Written last: its presence marks a complete, verified export.
    io.writeNewString(new Path(outRun, ManifestName), ujson.write(manifest, indent = 2))
    ExportResult(runId, outRun, exported, manifest, report)
  }

  /** Parses and runs on the given session (the entry point's path, and the local test runner). */
  def runWith(spark: SparkSession, argv: Seq[String]): ExportResult =
    parseArgs(argv) match {
      case Left(e)  => throw new IllegalArgumentException(e)
      case Right(a) =>
        run(spark, a.sourceRun, a.outRoot, a.recordsPerFile, 32, a.codeVersion)
    }

  /** The spark-submit entry point (`--class org.scholarchain.spark.JsonlExport`). Arguments are
    * checked before a session starts. Prints the run ID and the masked export location; any failure
    * is rethrown as [[JsonlExportFailedException]] with a masked message and no cause.
    */
  def main(argv: Array[String]): Unit = {
    val args = parseArgs(argv.toSeq).fold(e => throw new IllegalArgumentException(e), identity)
    val known = (authorityOf(args.sourceRun) ++ authorityOf(args.outRoot)).toSeq
    val spark = SparkSession.builder().appName("scholarchain-jsonl-export").getOrCreate()
    try {
      val r =
        try run(spark, args.sourceRun, args.outRoot, args.recordsPerFile, 32, args.codeVersion)
        catch {
          case NonFatal(e) =>
            throw new JsonlExportFailedException(
              maskText(s"${e.getClass.getSimpleName}: ${e.getMessage}", known)
            )
        }
      println(s"[scholarchain] export ${r.runId} at ${show(r.outRun)}")
    } finally spark.stop()
  }
}

/** Older name of the entry point, kept for the local runner; same options as [[JsonlExport]]. */
object JsonlExportJob {

  def parseArgs(argv: Seq[String]): Either[String, JsonlExport.Args] = JsonlExport.parseArgs(argv)

  def runWith(spark: SparkSession, argv: Seq[String]): ExportResult =
    JsonlExport.runWith(spark, argv)

  def main(argv: Array[String]): Unit = JsonlExport.main(argv)
}
