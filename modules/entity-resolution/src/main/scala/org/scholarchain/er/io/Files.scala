package org.scholarchain.er.io

import java.io.{
  BufferedReader,
  BufferedWriter,
  InputStream,
  InputStreamReader,
  OutputStream,
  OutputStreamWriter
}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files => JFiles, Path}
import java.security.{DigestInputStream, DigestOutputStream, MessageDigest}

import scala.jdk.CollectionConverters.*

import org.scholarchain.core.CodePointOrder

/** A failure of an entity-resolution run: bad input, bad configuration or a refused reuse. The
  * message names the file, line or setting.
  */
final class ErError(message: String) extends RuntimeException(message)

/** A data file that was read, with its size and content hash (for the run manifest). */
final case class FileDigest(path: String, bytes: Long, sha256: String)

object DataFiles {

  private val HexDigits = "0123456789abcdef".toCharArray

  def hex(bytes: Array[Byte]): String = {
    val out = new Array[Char](bytes.length * 2)
    var i = 0
    while (i < bytes.length) {
      val b = bytes(i) & 0xff
      out(2 * i) = HexDigits(b >>> 4)
      out(2 * i + 1) = HexDigits(b & 0x0f)
      i += 1
    }
    new String(out)
  }

  /** Data files below `dir` (recursive), skipping hidden and marker files (names starting with `.`
    * or `_`, such as Hadoop `.crc`, `_SUCCESS` and `_job.json`), sorted by relative path (code
    * point). Empty when `dir` does not exist.
    */
  def list(dir: Path): List[Path] =
    if (!JFiles.isDirectory(dir)) Nil
    else {
      val stream = JFiles.walk(dir)
      try
        stream.iterator.asScala
          .filter(p => JFiles.isRegularFile(p))
          .filter { p =>
            val rel = dir.relativize(p)
            rel.iterator.asScala.forall { seg =>
              val s = seg.toString
              !s.startsWith(".") && !s.startsWith("_")
            }
          }
          .toList
          .sortWith((a, b) =>
            CodePointOrder.lessThan(dir.relativize(a).toString, dir.relativize(b).toString)
          )
      finally stream.close()
    }

  /** Reads every line of `file` (UTF-8), calling `f(line, lineNumber)`; returns the file digest.
    * `label` is the path recorded in the digest.
    */
  def readLines(file: Path, label: String)(f: (String, Long) => Unit): FileDigest = {
    val md = MessageDigest.getInstance("SHA-256")
    val in: InputStream = new DigestInputStream(JFiles.newInputStream(file), md)
    val reader =
      new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 1 << 20)
    var n = 0L
    try {
      var line = reader.readLine()
      while (line != null) {
        n += 1
        f(line, n)
        line = reader.readLine()
      }
    } finally reader.close()
    FileDigest(label, JFiles.size(file), hex(md.digest()))
  }

  def sha256OfFile(file: Path): String = {
    val md = MessageDigest.getInstance("SHA-256")
    val in = new DigestInputStream(JFiles.newInputStream(file), md)
    try {
      val buf = new Array[Byte](1 << 16)
      while (in.read(buf) >= 0) ()
    } finally in.close()
    hex(md.digest())
  }

  def sha256OfBytes(bytes: Array[Byte]): String =
    hex(MessageDigest.getInstance("SHA-256").digest(bytes))

  def readResource(name: String): Array[Byte] = {
    val in = Option(getClass.getClassLoader.getResourceAsStream(name))
      .getOrElse(throw new ErError(s"missing resource $name"))
    try in.readAllBytes()
    finally in.close()
  }

  def readUtf8(file: Path): String = new String(JFiles.readAllBytes(file), StandardCharsets.UTF_8)
}

/** A UTF-8 line writer that hashes what it writes, for the run manifest. Lines end with `\n`. */
final class HashingLineWriter(val file: Path, val label: String) {
  JFiles.createDirectories(file.getParent)
  private val md = MessageDigest.getInstance("SHA-256")
  private val counting = new CountingStream(JFiles.newOutputStream(file))
  private val out = new BufferedWriter(
    new OutputStreamWriter(new DigestOutputStream(counting, md), StandardCharsets.UTF_8),
    1 << 20
  )
  private var lines = 0L

  def line(s: String): Unit = {
    out.write(s)
    out.write('\n')
    lines += 1
  }

  def records: Long = lines

  /** Closes the file and returns its digest. */
  def close(): OutputFile = {
    out.close()
    OutputFile(label, counting.count, DataFiles.hex(md.digest()), lines)
  }
}

/** A written output file. `records` is its number of lines. */
final case class OutputFile(path: String, bytes: Long, sha256: String, records: Long)

private final class CountingStream(underlying: OutputStream) extends OutputStream {
  var count = 0L
  override def write(b: Int): Unit = { underlying.write(b); count += 1 }
  override def write(b: Array[Byte], off: Int, len: Int): Unit = {
    underlying.write(b, off, len); count += len
  }
  override def flush(): Unit = underlying.flush()
  override def close(): Unit = underlying.close()
}

/** Writes a whole UTF-8 text file through [[HashingLineWriter]] semantics (content as given). */
object TextOutput {
  def write(root: Path, rel: String, content: String): OutputFile = {
    val file = root.resolve(rel)
    JFiles.createDirectories(file.getParent)
    val bytes = content.getBytes(StandardCharsets.UTF_8)
    JFiles.write(file, bytes)
    OutputFile(rel, bytes.length.toLong, DataFiles.sha256OfBytes(bytes), content.count(_ == '\n'))
  }
}
