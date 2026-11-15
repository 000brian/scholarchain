package org.scholarchain.storage.source

import java.io.{BufferedWriter, OutputStreamWriter}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}

import scala.collection.mutable

import org.scholarchain.core.{Contract, Validator}
import upickle.default.ReadWriter

/** One rejected input line or record: never dropped silently. */
final case class Reject(stage: String, table: String, file: String, line: Long, reason: String)

/** Counts rejects per (stage, table) and, when given a path, appends each one as a JSON line. The
  * offending line itself is not copied (only its location), so the file stays small.
  */
final class RejectLog(sink: Option[Path]) extends AutoCloseable {
  private val counts = mutable.LinkedHashMap.empty[(String, String), Long]
  private val writer: Option[BufferedWriter] = sink.map { p =>
    Files.createDirectories(p.getParent)
    new BufferedWriter(
      new OutputStreamWriter(
        Files.newOutputStream(p, StandardOpenOption.CREATE, StandardOpenOption.APPEND),
        StandardCharsets.UTF_8
      )
    )
  }

  def record(r: Reject): Unit = synchronized {
    counts.update((r.stage, r.table), counts.getOrElse((r.stage, r.table), 0L) + 1)
    writer.foreach { w =>
      val o = ujson.Obj(
        "stage" -> r.stage,
        "table" -> r.table,
        "file" -> r.file,
        "line" -> r.line.toDouble,
        "reason" -> r.reason.take(2000)
      )
      w.write(o.render())
      w.write('\n')
    }
  }

  def total: Long = synchronized(counts.values.sum)
  def count(stage: String, table: String): Long = synchronized(counts.getOrElse((stage, table), 0L))
  def byStageAndTable: List[((String, String), Long)] = synchronized(counts.toList)
  def close(): Unit = writer.foreach(_.close())
}

/** Decode statistics of one table read. */
final case class ReadStats(table: String, lines: Long, decoded: Long, rejected: Long)

object TableReader {

  /** Decodes every line of `table` with `Contract.decode` (validation included) and hands each
    * record to `f`. A line that fails to decode is recorded in `rejects` and skipped. Fails when
    * the number of lines differs from the manifest's record count, or a file's bytes or sha256
    * differ from the manifest.
    */
  def foreach[A](source: ExportSource, table: ExportTable, rejects: RejectLog, stage: String)(
      f: A => Unit
  )(implicit rw: ReadWriter[A], v: Validator[A]): ReadStats = {
    var lines = 0L
    var decoded = 0L
    var rejected = 0L
    table.files.foreach { file =>
      val it = source.lines(file)
      try {
        var lineNo = 0L
        while (it.hasNext) {
          val line = it.next()
          lineNo += 1
          lines += 1
          Contract.decode[A](line) match {
            case Right(a) =>
              decoded += 1
              f(a)
            case Left(err) =>
              rejected += 1
              rejects.record(Reject(stage, table.table, file.path, lineNo, err.message))
          }
        }
      } finally it.close()
    }
    if (lines != table.records)
      throw new ExportSourceException(
        s"${table.table}: read $lines lines, manifest says ${table.records}"
      )
    ReadStats(table.table, lines, decoded, rejected)
  }
}
