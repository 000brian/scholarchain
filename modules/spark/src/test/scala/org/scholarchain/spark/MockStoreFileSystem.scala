package org.scholarchain.spark

import java.net.URI

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.RawLocalFileSystem

/** Test-only Hadoop file system with its own scheme (`mockstore://example-bucket/ABS/PATH` is the
  * local file `/ABS/PATH`). Code that reaches a `mockstore:` path through `java.io.File` (or treats
  * it as `file:`) fails, so an export through it proves the export uses only the Hadoop FileSystem
  * API, as it must for `s3://` on EMR. Register with [[MockStoreFileSystem.register]].
  */
class MockStoreFileSystem extends RawLocalFileSystem {
  override def getUri: URI = MockStoreFileSystem.Root
  override def getScheme: String = MockStoreFileSystem.Scheme
}

object MockStoreFileSystem {
  val Scheme = "mockstore"
  val Root: URI = URI.create(s"$Scheme://example-bucket/")

  def register(conf: Configuration): Unit = {
    conf.set(s"fs.$Scheme.impl", classOf[MockStoreFileSystem].getName)
    conf.setBoolean(s"fs.$Scheme.impl.disable.cache", true)
  }

  /** The `mockstore:` location of an absolute local path. */
  def uriOf(absolutePath: String): String = s"$Scheme://example-bucket$absolutePath"
}
