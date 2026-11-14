package org.scholarchain.hadoop.io

import java.io.{FilterInputStream, IOException, InputStream}

/** Thrown when a file yields more bytes than allowed (decompression-bomb guard). */
final class DecompressionLimitExceeded(val limit: Long)
    extends IOException(s"input exceeded $limit bytes after decompression")

/** Counts bytes read and fails with [[DecompressionLimitExceeded]] past `limit`. */
final class GuardedInputStream(in: InputStream, limit: Long) extends FilterInputStream(in) {
  private var total = 0L

  def bytesRead: Long = total

  private def account(n: Int): Int = {
    if (n > 0) {
      total += n
      if (total > limit) throw new DecompressionLimitExceeded(limit)
    }
    n
  }

  override def read(): Int = {
    val b = super.read()
    if (b >= 0) account(1)
    b
  }

  override def read(b: Array[Byte], off: Int, len: Int): Int = account(super.read(b, off, len))

  override def skip(n: Long): Long = {
    val s = super.skip(n)
    if (s > 0) account(s.toInt)
    s
  }
}
