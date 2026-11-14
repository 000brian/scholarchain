package org.scholarchain.hadoop.io

import java.io.{DataInput, DataOutput}

import org.apache.hadoop.io.{Text, Writable, WritableUtils}

/** One unit read by a ScholarChain input format: a complete dblp record or JSON line, or a marker
  * for input that could not be read completely (so the mapper can reject and count it).
  *
  * Mutable because Hadoop reuses Writable instances within one task; never shared across tasks.
  */
final class RawRecord extends Writable {
  var status: Int = RawRecord.Ok
  var element: String = ""
  var file: String = ""
  var offset: Long = 0L

  /** Total bytes of the unit in the input (may exceed `payload.length` for oversize units). */
  var length: Long = 0L

  /** Complete bytes for `Ok`; a bounded prefix otherwise. */
  var payload: Array[Byte] = Array.emptyByteArray

  /** Why a non-Ok unit is incomplete (empty for Ok). */
  var detail: String = ""

  def location: String = s"$file:$offset"

  def set(
      status: Int,
      element: String,
      file: String,
      offset: Long,
      length: Long,
      payload: Array[Byte],
      detail: String
  ): Unit = {
    this.status = status
    this.element = element
    this.file = file
    this.offset = offset
    this.length = length
    this.payload = payload
    this.detail = detail
  }

  override def write(out: DataOutput): Unit = {
    WritableUtils.writeVInt(out, status)
    Text.writeString(out, element)
    Text.writeString(out, file)
    WritableUtils.writeVLong(out, offset)
    WritableUtils.writeVLong(out, length)
    WritableUtils.writeVInt(out, payload.length)
    out.write(payload)
    Text.writeString(out, detail)
  }

  override def readFields(in: DataInput): Unit = {
    status = WritableUtils.readVInt(in)
    element = Text.readString(in)
    file = Text.readString(in)
    offset = WritableUtils.readVLong(in)
    length = WritableUtils.readVLong(in)
    val n = WritableUtils.readVInt(in)
    payload = new Array[Byte](n)
    in.readFully(payload)
    detail = Text.readString(in)
  }
}

object RawRecord {
  val Ok = 0
  val Oversize = 1
  val Truncated = 2
  val DecompressionLimit = 3
  val InputError = 4

  /** Preview bytes of a non-Ok unit for scanners constructed without a configuration (the auxiliary
    * constructors of [[DblpRecordScanner]] and [[LineScanner]], used by tools and tests). Jobs
    * never use it: the input formats size previews from `scholarchain.reject.previewChars` via
    * `Settings.rejectPreviewBytes`, so a larger configured value is honoured, not capped.
    */
  val PreviewBytes = 4096
}
