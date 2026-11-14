package org.scholarchain.hadoop.io

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.hadoop.io.LongWritable
import org.apache.hadoop.mapreduce.lib.input.{FileInputFormat, FileSplit}
import org.apache.hadoop.mapreduce.{InputSplit, JobContext, RecordReader, TaskAttemptContext}
import org.scholarchain.hadoop.common.Settings

/** Shared reader: wraps a scanner over one split and exposes `RawRecord` values. */
abstract class ScannerRecordReader extends RecordReader[LongWritable, RawRecord] {
  private var opened: OpenedInput = null
  private var nextUnit: () => Option[ScannedUnit] = () => None
  private var fileName = ""
  private var start = 0L
  private var end = 0L
  private var current = 0L
  private val key = new LongWritable()
  private val value = new RawRecord()

  protected def scanner(conf: Configuration, in: OpenedInput): () => Option[ScannedUnit]

  override def initialize(split: InputSplit, context: TaskAttemptContext): Unit = {
    val conf = context.getConfiguration
    val fileSplit = split.asInstanceOf[FileSplit]
    val settings = new Settings(conf)
    val limit = settings.positiveLong(Settings.Keys.MaxDecompressedBytes)
    opened =
      InputOpener.open(conf, fileSplit.getPath, fileSplit.getStart, fileSplit.getLength, limit)
    fileName = fileSplit.getPath.toString
    start = opened.start
    end = opened.end
    current = start
    nextUnit = scanner(conf, opened)
  }

  override def nextKeyValue(): Boolean = nextUnit() match {
    case None    => false
    case Some(u) =>
      key.set(u.offset)
      value.set(u.status, u.element, fileName, u.offset, u.length, u.payload, u.detail)
      current = u.offset + u.length
      true
  }

  override def getCurrentKey: LongWritable = key
  override def getCurrentValue: RawRecord = value

  override def getProgress: Float =
    if (opened == null || opened.compressed || end <= start) 0.0f
    else math.min(1.0f, (current - start).toFloat / (end - start).toFloat)

  override def close(): Unit = if (opened != null) opened.close()
}

/** Record-boundary-safe dblp XML input: see [[DblpRecordScanner]]. Uncompressed files are split by
  * HDFS block; compressed files (gzip is not splittable) are read whole by one reader.
  */
class DblpXmlInputFormat extends FileInputFormat[LongWritable, RawRecord] {
  override protected def isSplitable(context: JobContext, file: Path): Boolean =
    !InputOpener.isCompressed(context.getConfiguration, file)

  override def createRecordReader(
      split: InputSplit,
      context: TaskAttemptContext
  ): RecordReader[LongWritable, RawRecord] =
    new ScannerRecordReader {
      protected def scanner(conf: Configuration, in: OpenedInput): () => Option[ScannedUnit] = {
        val s = new Settings(conf)
        val sc = new DblpRecordScanner(
          in.stream,
          in.start,
          in.end,
          s.list(Settings.Keys.DblpBoundaryElements).toSet,
          s.positiveInt(Settings.Keys.DblpMaxRecordBytes),
          s.rejectPreviewBytes
        )
        () => sc.next()
      }
    }
}

/** JSON-lines input with a hard per-line limit (see [[LineScanner]]). The limit is read from the
  * key named by `scholarchain.run.maxLineBytesKey`.
  */
class BoundedLineInputFormat extends FileInputFormat[LongWritable, RawRecord] {
  override protected def isSplitable(context: JobContext, file: Path): Boolean =
    !InputOpener.isCompressed(context.getConfiguration, file)

  override def createRecordReader(
      split: InputSplit,
      context: TaskAttemptContext
  ): RecordReader[LongWritable, RawRecord] =
    new ScannerRecordReader {
      protected def scanner(conf: Configuration, in: OpenedInput): () => Option[ScannedUnit] = {
        val s = new Settings(conf)
        val limitKey = s.string(BoundedLineInputFormat.LimitKeyKey)
        val sc =
          new LineScanner(
            in.stream,
            in.start,
            in.end,
            s.positiveInt(limitKey),
            s.rejectPreviewBytes
          )
        () => sc.next()
      }
    }
}

object BoundedLineInputFormat {
  val LimitKeyKey = "scholarchain.run.maxLineBytesKey"
}
