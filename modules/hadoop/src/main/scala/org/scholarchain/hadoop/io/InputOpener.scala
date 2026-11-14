package org.scholarchain.hadoop.io

import java.io.{BufferedInputStream, InputStream}

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.hadoop.io.compress.{CodecPool, CompressionCodecFactory, Decompressor}

/** Opens one input file (or a split of it) with the decompression guard applied.
  *
  * Compressed files are read from the start as one split; uncompressed files seek to `start`.
  */
final class OpenedInput(
    val stream: InputStream,
    val compressed: Boolean,
    val start: Long,
    val end: Long,
    decompressor: Option[Decompressor],
    val guard: GuardedInputStream
) {
  def close(): Unit =
    try stream.close()
    finally decompressor.foreach(CodecPool.returnDecompressor)
}

object InputOpener {

  def isCompressed(conf: Configuration, path: Path): Boolean =
    new CompressionCodecFactory(conf).getCodec(path) != null

  def open(conf: Configuration, path: Path, start: Long, length: Long, limit: Long): OpenedInput = {
    val fs = path.getFileSystem(conf)
    val raw = fs.open(path)
    val codec = new CompressionCodecFactory(conf).getCodec(path)
    if (codec != null) {
      val d = CodecPool.getDecompressor(codec)
      val guard = new GuardedInputStream(codec.createInputStream(raw, d), limit)
      new OpenedInput(
        new BufferedInputStream(guard, 65536),
        true,
        0L,
        Long.MaxValue,
        Option(d),
        guard
      )
    } else {
      raw.seek(start)
      val guard = new GuardedInputStream(raw, limit)
      new OpenedInput(
        new BufferedInputStream(guard, 65536),
        false,
        start,
        start + length,
        None,
        guard
      )
    }
  }
}
