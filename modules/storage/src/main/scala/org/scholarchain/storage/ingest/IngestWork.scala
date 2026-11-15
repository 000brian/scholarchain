package org.scholarchain.storage.ingest

import scala.util.control.NonFatal

import org.scholarchain.core.{Hashing, SemanticDocument}
import org.scholarchain.storage.dynamo.Change
import org.scholarchain.storage.ports.{AttrValue, WorkItem, WorkQueue}
import org.scholarchain.storage.source.{
  ExportFile,
  ExportManifest,
  ExportSource,
  ExportSourceException
}

/** A work item covering `lineCount` lines of one export file, starting at 1-based `startLine`. */
final case class LineRange(table: String, file: String, startLine: Long, lineCount: Int) {
  def toJson: String = ujson
    .Obj(
      "kind" -> "export-lines",
      "table" -> table,
      "file" -> file,
      "startLine" -> startLine.toDouble,
      "lineCount" -> lineCount
    )
    .render()
}

object LineRange {
  def parse(payload: String): Either[String, LineRange] =
    try {
      val o = ujson.read(payload).obj
      if (o("kind").str != "export-lines") Left(s"unknown work kind '${o("kind").str}'")
      else
        Right(
          LineRange(
            o("table").str,
            o("file").str,
            o("startLine").num.toLong,
            o("lineCount").num.toInt
          )
        )
    } catch { case NonFatal(e) => Left(s"malformed work payload: ${e.getMessage}") }
}

/** Plans embedding work as deterministic line ranges of export tables (the manifest-driven batch
  * design). Item IDs hash the source run, table, file and range, so planning twice yields the same
  * items. Each file is verified against the export manifest (bytes, sha256, line count) once here.
  */
object IngestWorkPlanner {

  def plan(
      source: ExportSource,
      manifest: ExportManifest,
      tables: List[String],
      linesPerItem: Int
  ): List[WorkItem] = {
    require(linesPerItem >= 1, "linesPerItem must be >= 1")
    tables.flatMap { name =>
      val t = manifest.require(name)
      t.files.flatMap { f =>
        val lines = countVerified(source, f)
        if (lines != f.records)
          throw new ExportSourceException(s"${f.path}: $lines lines, manifest says ${f.records}")
        (0L until lines by linesPerItem.toLong).map { start =>
          val r = LineRange(
            t.table,
            f.path,
            start + 1,
            math.min(linesPerItem.toLong, lines - start).toInt
          )
          val id = "work:" + Hashing.digest32(
            Seq(
              "ingest-work/v1",
              manifest.runId.value,
              r.table,
              r.file,
              r.startLine.toString,
              r.lineCount.toString
            )
          )
          WorkItem(id, r.toJson)
        }
      }
    }
  }

  private def countVerified(source: ExportSource, f: ExportFile): Long = {
    val it = source.lines(f)
    try {
      var n = 0L
      while (it.hasNext) { it.next(); n += 1 }
      n
    } finally it.close()
  }

  /** The lines of one range (read sequentially from the start of the file). */
  def readLines(source: ExportSource, manifest: ExportManifest, r: LineRange): Vector[String] = {
    val f = manifest
      .require(r.table)
      .files
      .find(_.path == r.file)
      .getOrElse(throw new ExportSourceException(s"no file ${r.file} in ${r.table}"))
    val it = source.lines(f)
    try it.slice((r.startLine - 1).toInt, (r.startLine - 1).toInt + r.lineCount).toVector
    finally it.close()
  }
}

final case class DrainReport(itemsDone: Long, itemsFailed: Long, documents: Long)

/** Drains a [[WorkQueue]] into one ingest session: for each received item, `documentsFor` turns its
  * payload into SemanticDocuments (rag renders; storage's local run uses the summary text), the
  * session ingests them, and the item is acknowledged. A failing item is released (retried up to
  * the queue's receive limit) and recorded in RunLedger with its receive count.
  */
final class IngestWorker(queue: WorkQueue, receiveBatch: Int, maxItems: Long) {
  require(
    receiveBatch >= 1 && receiveBatch <= 10,
    "receiveBatch must be in 1..10 (the SQS maximum)"
  )

  def drain(
      session: VectorIngestPipeline#IngestSession,
      documentsFor: String => Either[String, Iterator[SemanticDocument]]
  ): DrainReport = {
    var done, failed, docs = 0L
    var empty = false
    while (!empty && done + failed < maxItems) {
      val batch = queue.receive(math.min(receiveBatch.toLong, maxItems - done - failed).toInt)
      if (batch.isEmpty) empty = true
      batch.foreach { w =>
        val outcome =
          try
            documentsFor(w.item.payload).map { it =>
              var n = 0L
              session.ingest(it.map { d => n += 1; d })
              n
            }
          catch { case NonFatal(e) => Left(s"${e.getClass.getSimpleName}: ${e.getMessage}") }
        outcome match {
          case Right(n) =>
            docs += n
            done += 1
            queue.ack(w)
          case Left(err) =>
            failed += 1
            queue.release(w)
            val c = session.ledger.record(
              "work-failure",
              "workItemId" -> AttrValue.S(w.item.id),
              "receiveCount" -> AttrValue.n(w.receiveCount.toLong),
              "reason" -> AttrValue.S(err.take(1000))
            )
            if (c == Change.Conflict) throw new IllegalStateException("ledger conflict")
        }
      }
    }
    DrainReport(done, failed, docs)
  }
}
