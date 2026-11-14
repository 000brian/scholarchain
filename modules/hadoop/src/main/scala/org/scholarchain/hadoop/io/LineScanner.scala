package org.scholarchain.hadoop.io

import java.io.{ByteArrayOutputStream, EOFException, IOException, InputStream}

/** Splits a byte stream into `\n`-terminated lines with a hard per-line limit.
  *
  * Split rule (same as Hadoop's LineRecordReader): a reader whose split does not start at byte 0
  * discards everything up to and including the first `\n`; it then reads every line that starts at
  * or before `end`. A trailing `\r` is removed. Unlike LineRecordReader, which silently skips lines
  * longer than its maximum, an over-long line is consumed without buffering and reported as
  * [[RawRecord.Oversize]] with a preview of at most `previewBytes` bytes, so the mapper rejects and
  * counts it.
  */
final class LineScanner(
    in: InputStream,
    startPos: Long,
    end: Long,
    maxLineBytes: Int,
    previewBytes: Int
) {

  /** Without a configuration: previews of [[RawRecord.PreviewBytes]] bytes. */
  def this(in: InputStream, startPos: Long, end: Long, maxLineBytes: Int) =
    this(in, startPos, end, maxLineBytes, RawRecord.PreviewBytes)

  require(previewBytes > 0, s"previewBytes must be > 0 (got $previewBytes)")
  private var pos = startPos
  private var done = false
  private var skippedFirst = startPos == 0

  private def read(): Int = {
    val b = in.read()
    if (b >= 0) pos += 1
    b
  }

  def next(): Option[ScannedUnit] =
    if (done) None
    else
      try scan()
      catch {
        case e: DecompressionLimitExceeded =>
          done = true
          Some(
            ScannedUnit(
              RawRecord.DecompressionLimit,
              "",
              pos,
              0L,
              Array.emptyByteArray,
              e.getMessage
            )
          )
        case e: EOFException =>
          done = true
          Some(
            ScannedUnit(
              RawRecord.InputError,
              "",
              pos,
              0L,
              Array.emptyByteArray,
              s"truncated input: ${e.getMessage}"
            )
          )
        case e: IOException =>
          done = true
          Some(
            ScannedUnit(
              RawRecord.InputError,
              "",
              pos,
              0L,
              Array.emptyByteArray,
              s"${e.getClass.getSimpleName}: ${e.getMessage}"
            )
          )
      }

  private def scan(): Option[ScannedUnit] = {
    if (!skippedFirst) {
      skippedFirst = true
      var b = read()
      while (b >= 0 && b != '\n') b = read()
      if (b < 0) { done = true; return None }
    }
    if (pos > end) { done = true; return None }
    val lineStart = pos
    val buf = new ByteArrayOutputStream(256)
    var kept = 0
    var oversize = false
    var b = read()
    if (b < 0) { done = true; return None }
    while (b >= 0 && b != '\n') {
      if (kept < maxLineBytes) { buf.write(b); kept += 1 }
      else oversize = true
      b = read()
    }
    if (b < 0) done = true
    var bytes = buf.toByteArray
    if (!oversize && bytes.nonEmpty && bytes.last == '\r') bytes = bytes.dropRight(1)
    val length = pos - lineStart - (if (b == '\n') 1 else 0)
    if (oversize)
      Some(
        ScannedUnit(
          RawRecord.Oversize,
          "",
          lineStart,
          length,
          java.util.Arrays.copyOf(bytes, math.min(bytes.length, previewBytes)),
          s"line of $length bytes exceeds $maxLineBytes"
        )
      )
    else Some(ScannedUnit(RawRecord.Ok, "", lineStart, length, bytes, ""))
  }
}
