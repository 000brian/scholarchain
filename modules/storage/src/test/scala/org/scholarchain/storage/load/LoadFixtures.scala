package org.scholarchain.storage.load

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.collection.mutable
import scala.jdk.CollectionConverters._

import org.scholarchain.core.RunId
import org.scholarchain.storage.TestFixtures
import org.scholarchain.storage.ports.{ObjectRef, ObjectStore}

/** Shared fixtures of the loader tests: a configuration file with the runtime section (placeholder
  * names only), the synthetic export copied into an object store, and a print capture.
  */
object LoadFixtures {

  /** Placeholder bucket names of the test configuration (`storage-local-v1.json`). */
  val GraphBucket = "example-graph-bucket"
  val AllBuckets: List[String] = List(
    "example-raw-bucket",
    "example-curated-bucket",
    "example-graph-bucket",
    "example-vector-bucket",
    "example-artifact-bucket"
  )
  val AccountId = "123456789012"
  val RoleArn = s"arn:aws:iam::$AccountId:role/example-neptune-loader"

  val runHex: String = TestFixtures.sparkRun.value.stripPrefix(RunId.Prefix)

  /** The export key prefix used in the tests (`graph/exports/run=<hex>`). */
  val ExportKey = s"graph/exports/run=$runHex"
  val ExportUri = s"s3://$GraphBucket/$ExportKey"

  /** Writes the test configuration (with the runtime section) to `dir/config.json`. */
  def configFile(dir: Path): Path = {
    val in = getClass.getResourceAsStream("/config/storage-local-v1.json")
    val tree =
      try ujson.read(new String(in.readAllBytes(), StandardCharsets.UTF_8))
      finally in.close()
    tree("runtime") = TestFixtures.runtimeSection
    val p = dir.resolve("config.json")
    Files.write(p, tree.render(indent = 2).getBytes(StandardCharsets.UTF_8))
    p
  }

  /** Every file of a local export directory, as (relative path, bytes). */
  def exportFiles(dir: Path): List[(String, Array[Byte])] = {
    val s = Files.walk(dir)
    try
      s.iterator.asScala
        .filter(Files.isRegularFile(_))
        .map(p => dir.relativize(p).toString.replace(java.io.File.separatorChar, '/'))
        .toList
        .sorted
        .map(rel => rel -> Files.readAllBytes(dir.resolve(rel)))
    finally s.close()
  }

  /** Copies the files of a local export into `store` under `bucket/key/`, replacing the bytes of a
    * relative path with `replace(rel)` when defined and leaving out every path in `skip`.
    */
  def upload(
      store: ObjectStore,
      exportDir: Path,
      bucket: String = GraphBucket,
      key: String = ExportKey,
      replace: PartialFunction[String, Array[Byte]] = PartialFunction.empty,
      skip: Set[String] = Set.empty
  ): Unit =
    exportFiles(exportDir).foreach { case (rel, bytes) =>
      if (!skip.contains(rel))
        store.putIfAbsent(
          ObjectRef(bucket, s"$key/$rel"),
          replace.applyOrElse(rel, (_: String) => bytes),
          "application/octet-stream",
          Map.empty
        )
    }

  /** The relative path of the first data file of a table in the synthetic export. */
  def dataFile(tablePath: String): String = s"$tablePath/part-00000.jsonl"

  /** Collects printed lines. */
  final class Printed {
    val lines: mutable.ListBuffer[String] = mutable.ListBuffer.empty
    val print: String => Unit = (s: String) => synchronized { lines += s }
    def text: String = synchronized(lines.mkString("\n"))
  }

  def readReport(out: Path): (String, ujson.Value) = {
    val text =
      new String(Files.readAllBytes(out.resolve("run-report.json")), StandardCharsets.UTF_8)
    (text, ujson.read(text))
  }
}
