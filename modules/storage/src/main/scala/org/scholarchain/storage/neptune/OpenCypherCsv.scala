package org.scholarchain.storage.neptune

import java.io.{BufferedOutputStream, OutputStream, OutputStreamWriter, Writer}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}
import java.security.{DigestOutputStream, MessageDigest}
import java.util.zip.GZIPOutputStream

import scala.collection.mutable

import org.scholarchain.storage.source.HexBytes

/** Property column types of the Neptune openCypher CSV load format. Every property is single-valued
  * (no array columns), as edge properties must be.
  */
sealed abstract class CsvType(val name: String)
object CsvType {
  case object Str extends CsvType("String")
  case object IntT extends CsvType("Int")
  case object LongT extends CsvType("Long")
  case object DoubleT extends CsvType("Double")
  case object BoolT extends CsvType("Bool")
}

final case class CsvColumn(name: String, tpe: CsvType) {
  def header: String = s"$name:${tpe.name}"
}

/** Header layouts of the openCypher CSV format: node files start with `:ID,:LABEL`, relationship
  * files with `:ID,:START_ID,:END_ID,:TYPE`; then one `name:Type` column per property. An empty
  * field means the property is absent.
  */
object OpenCypherCsv {
  val NodeSystemColumns: List[String] = List(":ID", ":LABEL")
  val EdgeSystemColumns: List[String] = List(":ID", ":START_ID", ":END_ID", ":TYPE")

  def nodeHeader(props: List[CsvColumn]): List[String] = NodeSystemColumns ++ props.map(_.header)
  def edgeHeader(props: List[CsvColumn]): List[String] = EdgeSystemColumns ++ props.map(_.header)

  /** RFC 4180 quoting: a field containing a comma, quote, CR, LF or leading/trailing space is
    * wrapped in double quotes with inner quotes doubled.
    */
  def escape(v: String): String =
    if (v == null) ""
    else if (
      v.exists(c => c == ',' || c == '"' || c == '\n' || c == '\r') ||
      (v.nonEmpty && (v.head == ' ' || v.last == ' '))
    ) "\"" + v.replace("\"", "\"\"") + "\""
    else v

  def line(fields: Seq[String]): String = fields.map(escape).mkString(",")

  /** Splits one CSV line (no embedded newlines) back into fields; the inverse of [[line]]. */
  def parseLine(s: String): Vector[String] = {
    val out = Vector.newBuilder[String]
    val cur = new StringBuilder
    var i = 0
    var quoted = false
    while (i < s.length) {
      val c = s.charAt(i)
      if (quoted) {
        if (c == '"' && i + 1 < s.length && s.charAt(i + 1) == '"') { cur.append('"'); i += 1 }
        else if (c == '"') quoted = false
        else cur.append(c)
      } else if (c == '"') quoted = true
      else if (c == ',') { out += cur.toString; cur.clear() }
      else cur.append(c)
      i += 1
    }
    out += cur.toString
    out.result()
  }
}

/** One written CSV file. `bytes` is the size on disk, `uncompressedBytes` the CSV text size. */
final case class CsvFileStat(
    relativePath: String,
    rows: Long,
    bytes: Long,
    uncompressedBytes: Long,
    sha256: String
)

/** Writes the rows of one node or edge type into numbered files of at most `rowsPerFile` rows, each
  * with the header, optionally gzip-compressed (one CSV per `.gz`, as the loader expects). Output
  * is deterministic: rows are written in the order given.
  */
final class CsvFileSet(
    root: Path,
    relativeDir: String,
    baseName: String,
    header: List[String],
    rowsPerFile: Int,
    gzip: Boolean
) extends AutoCloseable {
  require(rowsPerFile > 0, "rowsPerFile must be > 0")
  private val stats = mutable.ArrayBuffer.empty[CsvFileStat]
  private var writer: Writer = null
  private var digest: MessageDigest = null
  private var diskCounter: CountingOutputStream = null
  private var textCounter: CountingOutputStream = null
  private var rowsInFile = 0L
  private var currentRel: String = null
  private var totalRows = 0L

  private def open(): Unit = {
    val name = f"$baseName-${stats.size}%05d.csv" + (if (gzip) ".gz" else "")
    currentRel = s"$relativeDir/$name"
    val p = root.resolve(currentRel)
    Files.createDirectories(p.getParent)
    digest = MessageDigest.getInstance("SHA-256")
    val file = Files.newOutputStream(p, StandardOpenOption.CREATE_NEW)
    diskCounter = new CountingOutputStream(new DigestOutputStream(file, digest))
    val body: OutputStream =
      if (gzip) new GZIPOutputStream(diskCounter, 1 << 16) else diskCounter
    textCounter = new CountingOutputStream(new BufferedOutputStream(body, 1 << 16))
    writer = new OutputStreamWriter(textCounter, StandardCharsets.UTF_8)
    writer.write(OpenCypherCsv.line(header))
    writer.write('\n')
    rowsInFile = 0
  }

  private def finish(): Unit = if (writer != null) {
    writer.flush()
    writer.close()
    stats += CsvFileStat(
      currentRel,
      rowsInFile,
      diskCounter.count,
      textCounter.count,
      HexBytes.of(digest.digest())
    )
    writer = null
  }

  def write(fields: Seq[String]): Unit = {
    require(
      fields.size == header.size,
      s"$baseName: ${fields.size} fields, header has ${header.size}"
    )
    if (writer == null) open()
    writer.write(OpenCypherCsv.line(fields))
    writer.write('\n')
    rowsInFile += 1
    totalRows += 1
    if (rowsInFile >= rowsPerFile) finish()
  }

  def rows: Long = totalRows

  /** Closes the current file. A type with no rows writes no file. */
  def close(): Unit = finish()
  def files: List[CsvFileStat] = stats.toList
}

final class CountingOutputStream(out: OutputStream) extends java.io.FilterOutputStream(out) {
  var count: Long = 0L
  override def write(b: Int): Unit = { out.write(b); count += 1 }
  override def write(b: Array[Byte], off: Int, len: Int): Unit = {
    out.write(b, off, len); count += len
  }
}
