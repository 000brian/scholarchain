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

/** Where an export lives: a local directory now, an S3 prefix later. Paths are relative to the
  * export run directory (`run=<hex>/`).
  */
trait ExportSource {
  def describe: String
  protected def open(relativePath: String): InputStream

  def manifest(): ExportManifest = {
    val in = open(ExportManifest.FileName)
    val text =
      try new String(in.readAllBytes(), StandardCharsets.UTF_8)
      finally in.close()
    ExportManifest
      .parse(text)
      .fold(e => throw new ExportSourceException(s"$describe: $e"), identity)
  }

  /** Lines of one listed file, verified against the manifest (bytes and sha256) at the end. */
  def lines(file: ExportFile): VerifiedLines =
    new VerifiedLines(open(file.path), file, s"$describe/${file.path}")
}

/** A local export directory (`.../phase-07-export/run=<hex>`). */
final class LocalDirectorySource(root: Path) extends ExportSource {
  def describe: String = root.toString
  protected def open(relativePath: String): InputStream = {
    if (relativePath.split('/').contains("..") || relativePath.startsWith("/"))
      throw new ExportSourceException(s"refusing path '$relativePath'")
    val p = root.resolve(relativePath)
    if (!Files.isRegularFile(p)) throw new ExportSourceException(s"missing export file $p")
    Files.newInputStream(p)
  }
}

/** An export copied under an S3 prefix (`<prefix>/run=<hex>/...`), read through the object-store
  * port. The bucket comes from configuration.
  */
final class ObjectStoreSource(store: ObjectStore, bucket: String, prefix: String)
    extends ExportSource {
  def describe: String = s"object-store:$prefix"
  protected def open(relativePath: String): InputStream = {
    val ref = ObjectRef(bucket, s"$prefix/$relativePath")
    store
      .openStream(ref)
      .getOrElse(throw new ExportSourceException(s"missing export object $relativePath"))
  }
}
