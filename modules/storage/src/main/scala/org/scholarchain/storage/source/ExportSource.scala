package org.scholarchain.storage.source

import java.io.{BufferedReader, InputStream, InputStreamReader}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.security.{DigestInputStream, MessageDigest}

import scala.util.control.NonFatal

import org.scholarchain.core.RunId
import org.scholarchain.storage.ports.{ObjectRef, ObjectStore}

/** One file of an exported table, as listed in `_export_manifest.json`. */
final case class ExportFile(path: String, bytes: Long, sha256: String, records: Long)

/** One exported table (`scholarchain.spark.jsonl-export` v1). */
final case class ExportTable(
    table: String,
    path: String,
    coreRecord: Option[String],
    schemaVersion: Option[Int],
    windowLabel: Option[String],
    records: Long,
    files: List[ExportFile]
)

/** The Spark JSON Lines export manifest. Only the fields storage needs are read. */
final case class ExportManifest(runId: RunId, sourceRunId: RunId, tables: List[ExportTable]) {
  def table(name: String): Option[ExportTable] = tables.find(_.table == name)
  def require(name: String): ExportTable =
    table(name).getOrElse(throw new ExportSourceException(s"export manifest has no table '$name'"))
  def withPrefix(prefix: String): List[ExportTable] = tables.filter(_.table.startsWith(prefix))
  def windowLabels: List[String] = tables.flatMap(_.windowLabel).distinct.sorted
}

final class ExportSourceException(message: String, cause: Throwable = null)
    extends RuntimeException(message, cause)

object ExportManifest {
  val FileName = "_export_manifest.json"
  val Format = "scholarchain.spark.jsonl-export"

  def parse(text: String): Either[String, ExportManifest] =
    try {
      val root = ujson.read(text).obj
      val format = root.get("format").map(_.str).getOrElse("")
      val version = root.get("formatVersion").map(_.num.toInt).getOrElse(-1)
      if (format != Format || version != 1)
        Left(s"unsupported export manifest format '$format' v$version (expected $Format v1)")
      else
        for {
          runId <- RunId.parse(root("runId").str)
          sourceRunId <- RunId.parse(root("sourceRunId").str)
        } yield ExportManifest(runId, sourceRunId, root("tables").arr.toList.map(parseTable))
    } catch { case NonFatal(e) => Left(s"malformed export manifest: ${e.getMessage}") }

  private def optStr(v: ujson.Value): Option[String] = v match {
    case ujson.Null => None
    case other      => Some(other.str)
  }

  private def parseTable(v: ujson.Value): ExportTable = {
    val o = v.obj
    ExportTable(
      table = o("table").str,
      path = o("path").str,
      coreRecord = optStr(o("coreRecord")),
      schemaVersion = o("schemaVersion") match {
        case ujson.Null => None
        case n          => Some(n.num.toInt)
      },
      windowLabel = optStr(o("windowLabel")),
      records = o("records").num.toLong,
      files = o("files").arr.toList.map { f =>
        val fo = f.obj
        ExportFile(
          fo("path").str,
          fo("bytes").num.toLong,
          fo("sha256").str,
          fo("records").num.toLong
        )
      }
    )
  }
}

/** A line iterator over one export file that verifies the file's byte count and sha256 against the
  * manifest when it is exhausted. Close it when done.
  */
final class VerifiedLines(in: InputStream, file: ExportFile, describe: String)
    extends Iterator[String]
    with AutoCloseable {
  private val digest = MessageDigest.getInstance("SHA-256")
  private val counting = new CountingInputStream(in)
  private val reader = new BufferedReader(
    new InputStreamReader(new DigestInputStream(counting, digest), StandardCharsets.UTF_8),
    1 << 16
  )
  private var nextLine: String = reader.readLine()
  private var verified = false

  def hasNext: Boolean = {
    if (nextLine == null && !verified) verify()
    nextLine != null
  }

  def next(): String = {
    if (!hasNext) throw new NoSuchElementException(describe)
    val l = nextLine
    nextLine = reader.readLine()
    l
  }

  private def verify(): Unit = {
    verified = true
    val hex = HexBytes.of(digest.digest())
    if (counting.count != file.bytes)
      throw new ExportSourceException(
        s"$describe: ${counting.count} bytes read, manifest says ${file.bytes}"
      )
    if (hex != file.sha256)
      throw new ExportSourceException(
        s"$describe: sha256 $hex differs from manifest ${file.sha256}"
      )
  }

  def close(): Unit = reader.close()
}

object HexBytes {
  private val Digits = "0123456789abcdef".toCharArray
  def of(bytes: Array[Byte]): String = {
    val out = new Array[Char](bytes.length * 2)
    var i = 0
    while (i < bytes.length) {
      out(2 * i) = Digits((bytes(i) & 0xff) >>> 4)
      out(2 * i + 1) = Digits(bytes(i) & 0x0f)
      i += 1
    }
    new String(out)
  }
}

final class CountingInputStream(in: InputStream) extends java.io.FilterInputStream(in) {
  var count: Long = 0L
  override def read(): Int = {
    val b = super.read()
    if (b >= 0) count += 1
    b
  }
  override def read(b: Array[Byte], off: Int, len: Int): Int = {
    val n = super.read(b, off, len)
    if (n > 0) count += n
    n
  }
}

/** Result of a whole-export check: files and bytes checked. */
final case class ExportCheck(files: Int, bytes: Long)

/** Where an export lives: a local directory ([[LocalDirectorySource]]) or an S3 prefix read through
  * the object-store port ([[S3ExportSource]]). Paths are relative to the export run directory
  * (`run=<hex>/`). Both kinds make the same checks: the manifest must be present and well formed,
  * every listed file must exist with the manifest's byte count ([[checkPresence]]), and every byte
  * read is checked against the manifest's size and sha256 ([[lines]] at the end of each file,
  * [[verifyAll]] for the whole export).
  */
trait ExportSource {
  def describe: String

  /** The file at a relative path, or None when it does not exist. Paths are checked first. */
  protected def find(relativePath: String): Option[InputStream]

  /** The byte size of the file at a relative path, or None when it does not exist. */
  protected def sizeOf(relativePath: String): Option[Long]

  protected final def checkedPath(relativePath: String): String = {
    if (
      relativePath == null || relativePath.isEmpty || relativePath.startsWith("/") ||
      relativePath.split('/').exists(s => s.isEmpty || s == "." || s == "..")
    )
      throw new ExportSourceException(s"refusing path '$relativePath'")
    relativePath
  }

  protected final def open(relativePath: String): InputStream =
    find(checkedPath(relativePath)).getOrElse(
      throw new ExportSourceException(s"$describe: missing export file $relativePath")
    )

  /** The raw bytes of `_export_manifest.json`; a missing manifest is an [[ExportSourceException]].
    */
  def manifestBytes(): Array[Byte] = {
    val in = find(ExportManifest.FileName).getOrElse(
      throw new ExportSourceException(
        s"$describe: export manifest ${ExportManifest.FileName} is missing"
      )
    )
    try in.readAllBytes()
    finally in.close()
  }

  def manifest(): ExportManifest = parseManifest(manifestBytes())

  /** The manifest and the hex sha256 of its bytes (the input fingerprint of a load). */
  def manifestWithSha256(): (ExportManifest, String) = {
    val bytes = manifestBytes()
    (parseManifest(bytes), org.scholarchain.core.Hashing.sha256Hex(bytes))
  }

  private def parseManifest(bytes: Array[Byte]): ExportManifest =
    ExportManifest
      .parse(new String(bytes, StandardCharsets.UTF_8))
      .fold(e => throw new ExportSourceException(s"$describe: $e"), identity)

  /** Every listed file exists and has the manifest's byte count. Reads no content (one size lookup
    * per file); fails on the first problem.
    */
  def checkPresence(m: ExportManifest): ExportCheck = {
    val files = m.tables.flatMap(_.files)
    files.foreach { f =>
      sizeOf(checkedPath(f.path)) match {
        case None =>
          throw new ExportSourceException(s"$describe: missing export file ${f.path}")
        case Some(n) if n != f.bytes =>
          throw new ExportSourceException(
            s"$describe/${f.path}: $n bytes present, manifest says ${f.bytes}"
          )
        case Some(_) => ()
      }
    }
    ExportCheck(files.size, files.map(_.bytes).sum)
  }

  /** Reads every listed file in full and checks its byte count and sha256 against the manifest;
    * fails on the first problem.
    */
  def verifyAll(m: ExportManifest): ExportCheck = {
    val files = m.tables.flatMap(_.files)
    val buffer = new Array[Byte](1 << 16)
    files.foreach { f =>
      val digest = MessageDigest.getInstance("SHA-256")
      val in = new DigestInputStream(open(f.path), digest)
      var count = 0L
      try {
        var n = in.read(buffer)
        while (n >= 0) { count += n; n = in.read(buffer) }
      } finally in.close()
      if (count != f.bytes)
        throw new ExportSourceException(
          s"$describe/${f.path}: $count bytes read, manifest says ${f.bytes}"
        )
      val hex = HexBytes.of(digest.digest())
      if (hex != f.sha256)
        throw new ExportSourceException(
          s"$describe/${f.path}: sha256 $hex differs from manifest ${f.sha256}"
        )
    }
    ExportCheck(files.size, files.map(_.bytes).sum)
  }

  /** Lines of one listed file, verified against the manifest (bytes and sha256) at the end. */
  def lines(file: ExportFile): VerifiedLines =
    new VerifiedLines(open(file.path), file, s"$describe/${file.path}")
}

/** A local export directory (`.../phase-07-export/run=<hex>`). */
final class LocalDirectorySource(root: Path) extends ExportSource {
  def describe: String = root.toString
  protected def find(relativePath: String): Option[InputStream] = {
    val p = root.resolve(checkedPath(relativePath))
    if (Files.isRegularFile(p)) Some(Files.newInputStream(p)) else None
  }
  protected def sizeOf(relativePath: String): Option[Long] = {
    val p = root.resolve(checkedPath(relativePath))
    if (Files.isRegularFile(p)) Some(Files.size(p)) else None
  }
}

/** A Spark JSON Lines export (`scholarchain.spark.jsonl-export` v1, the Phase 7 layout) under an S3
  * prefix `s3://BUCKET/PREFIX/run=<32 hex>/`, read through the [[ObjectStore]] port (`get` streams,
  * `head` sizes). Same checks as [[LocalDirectorySource]]. The bucket is never printed: `describe`
  * shows it as [[S3ExportSource.MaskedBucket]].
  */
final class S3ExportSource private (store: ObjectStore, val root: ObjectRef) extends ExportSource {
  def describe: String = s"s3://${S3ExportSource.MaskedBucket}/${root.key}"
  private def ref(relativePath: String) = ObjectRef(root.bucket, s"${root.key}/$relativePath")
  protected def find(relativePath: String): Option[InputStream] =
    store.openStream(ref(checkedPath(relativePath)))
  protected def sizeOf(relativePath: String): Option[Long] =
    store.head(ref(checkedPath(relativePath))).map(_.size)
}

object S3ExportSource {

  /** How the bucket of an export appears in messages and reports. */
  val MaskedBucket = "{data-bucket}"

  private val Uri = "s3://([a-z0-9][a-z0-9.-]{1,61}[a-z0-9])/(.+)".r
  private val RunSegment = "run=[0-9a-f]{32}".r
  private val Segment = "[A-Za-z0-9][A-Za-z0-9._=-]*".r

  /** An export at `s3://BUCKET/PREFIX/run=<32 hex>` (a trailing `/` is allowed). Messages never
    * repeat the URI (it holds a bucket name).
    */
  def fromUri(store: ObjectStore, uri: String): Either[String, S3ExportSource] =
    Option(uri).getOrElse("") match {
      case Uri(bucket, rest) =>
        val key = rest.stripSuffix("/")
        val segments = key.split('/').toList
        if (!segments.forall(Segment.matches))
          Left("export location has an invalid key segment")
        else if (!RunSegment.matches(segments.last))
          Left("export location must end in run=<32 hex> (the export run directory)")
        else Right(new S3ExportSource(store, ObjectRef(bucket, key)))
      case _ => Left("export location is not an s3://BUCKET/PREFIX/run=<32 hex> URI")
    }
}

/** Opens an export by location: `s3://...` through the object store, anything else as a local
  * directory.
  */
object ExportSources {
  def open(location: String, objects: Option[ObjectStore]): Either[String, ExportSource] =
    if (location != null && location.startsWith("s3://"))
      objects
        .toRight("an s3:// export needs an object store (--stores)")
        .flatMap(S3ExportSource.fromUri(_, location))
    else {
      val p = java.nio.file.Paths.get(Option(location).getOrElse("")).toAbsolutePath
      if (Files.isDirectory(p)) Right(new LocalDirectorySource(p))
      else Left(s"export directory $p does not exist")
    }
}
