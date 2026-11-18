package org.scholarchain.spark

import java.io.{File, FileNotFoundException}
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, LocalFileSystem, Path}

/** File access of the pipeline through the Hadoop FileSystem API (Phase 6 F9 part 2), so the same
  * code reads and writes `file://`, `hdfs://` and `s3://` locations: the input listing and hashes,
  * the entity-resolution checks, the output root, run and load manifests, the reuse check and the
  * checkpoint and staging directories.
  *
  * Local paths go through the raw local file system, so no `.crc` side files are written next to
  * the manifests and a changed file is reported as changed (not as a checksum error).
  *
  * Not thread-safe beyond what the underlying FileSystem guarantees; the pipeline uses it from the
  * driver only.
  */
final class RunFs(conf: Configuration) {

  def fs(p: Path): FileSystem = p.getFileSystem(conf) match {
    case l: LocalFileSystem => l.getRawFileSystem
    case other              => other
  }

  /** The fully qualified path (scheme and authority; relative paths against the working dir). */
  def qualify(p: Path): Path = fs(p).makeQualified(p)

  private def status(p: Path) =
    try Some(fs(p).getFileStatus(p))
    catch { case _: FileNotFoundException => None }

  def exists(p: Path): Boolean = status(p).isDefined
  def isFile(p: Path): Boolean = status(p).exists(_.isFile)
  def isDirectory(p: Path): Boolean = status(p).exists(_.isDirectory)
  def length(p: Path): Long = fs(p).getFileStatus(p).getLen
  def mkdirs(p: Path): Unit = { fs(p).mkdirs(p); () }

  def readBytes(p: Path): Array[Byte] = {
    val in = fs(p).open(p)
    try in.readAllBytes()
    finally in.close()
  }

  def readString(p: Path): String = new String(readBytes(p), StandardCharsets.UTF_8)

  def readIfFile(p: Path): Option[String] = if (isFile(p)) Some(readString(p)) else None

  def writeString(p: Path, s: String): Unit = {
    val out = fs(p).create(p, true)
    try out.write(s.getBytes(StandardCharsets.UTF_8))
    finally out.close()
  }

  /** Writes a new file; fails (FileAlreadyExistsException) when `p` already exists. */
  def writeNewString(p: Path, s: String): Unit = {
    val out = fs(p).create(p, false)
    try out.write(s.getBytes(StandardCharsets.UTF_8))
    finally out.close()
  }

  def sha256(p: Path): String = {
    val md = MessageDigest.getInstance("SHA-256")
    val in = fs(p).open(p)
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

  /** Path of `p` relative to `root` (both qualified the same way), with `/` separators. */
  def rel(root: Path, p: Path): String = {
    val r = qualify(root).toUri.getPath.stripSuffix("/")
    val s = qualify(p).toUri.getPath
    if (!s.startsWith(r + "/")) throw new IllegalArgumentException(s"$p is not below $root")
    s.substring(r.length + 1)
  }

  /** Regular files directly in `dir` (any name), sorted by name; empty when `dir` is absent. */
  def listFiles(dir: Path): List[Path] =
    if (!isDirectory(dir)) Nil
    else fs(dir).listStatus(dir).toList.filter(_.isFile).map(_.getPath).sortBy(_.getName)

  /** Non-hidden regular files below `dir` (no `_` or `.` prefix in any path segment below it), the
    * files Spark reads, sorted by relative path. Empty when `dir` is absent.
    */
  def listData(dir: Path): List[Path] = {
    def hidden(name: String) = name.startsWith("_") || name.startsWith(".")
    def walk(d: Path): List[Path] =
      fs(d).listStatus(d).toList.filterNot(s => hidden(s.getPath.getName)).flatMap { s =>
        if (s.isDirectory) walk(s.getPath)
        else if (s.isFile) List(s.getPath)
        else Nil
      }
    val q = qualify(dir)
    if (!isDirectory(q)) Nil else walk(q).sortBy(rel(q, _))
  }

  /** (relative path, bytes, sha256) of every data file below `dir`, relative to `root`. */
  def entries(root: Path, dir: Path): List[FileEntry] =
    listData(dir).map(f => FileEntry(rel(root, f), length(f), sha256(f)))

  /** Free space for the stage guard: usable local disk for `file:` paths, else the file system's
    * reported remaining capacity (object stores report a nominal value; the guard is local-disk).
    */
  def usableSpace(p: Path): Long =
    if (qualify(p).toUri.getScheme == "file") new File(qualify(p).toUri).getUsableSpace
    else fs(p).getStatus(p).getRemaining
}

object RunFs {

  /** A RunFs with a default Hadoop configuration (local callers such as the JSONL export). */
  def local: RunFs = new RunFs(new Configuration())

  /** The `file:` path of a local file (no trailing separator, so `getName` is the last segment). */
  def pathOf(f: File): Path = new Path("file", null, f.getAbsolutePath)

  /** A readable location: the plain absolute path for `file:` paths, else the URI. */
  def display(p: Path): String =
    if (p.toUri.getScheme == "file") p.toUri.getPath.stripSuffix("/") else p.toString
}
