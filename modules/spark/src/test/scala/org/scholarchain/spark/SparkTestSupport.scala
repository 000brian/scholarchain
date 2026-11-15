package org.scholarchain.spark

import java.io.File
import java.nio.file.Files

import org.apache.spark.sql.SparkSession

/** Small local Spark sessions for tests. Every session gets its own fresh temporary directory for
  * the warehouse, local (shuffle/spill) files and checkpoints, so suites in parallel JVMs never
  * share Spark temporary paths.
  */
object SparkTestSupport {

  def tempDir(prefix: String): File = Files.createTempDirectory(s"scholarchain-$prefix-").toFile

  def session(root: File, cores: Int = 2, shufflePartitions: Int = 2): SparkSession =
    SparkSession
      .builder()
      .master(s"local[$cores]")
      .appName("scholarchain-spark-test")
      .config("spark.ui.enabled", "false")
      .config("spark.sql.shuffle.partitions", shufflePartitions.toString)
      .config("spark.default.parallelism", shufflePartitions.toString)
      .config("spark.sql.autoBroadcastJoinThreshold", "-1")
      .config("spark.sql.adaptive.autoBroadcastJoinThreshold", "-1")
      .config("spark.sql.warehouse.dir", new File(root, "warehouse").getAbsolutePath)
      .config("spark.local.dir", new File(root, "spark-local").getAbsolutePath)
      .config("spark.driver.host", "127.0.0.1")
      .config("spark.driver.bindAddress", "127.0.0.1")
      .config("spark.cleaner.referenceTracking.cleanCheckpoints", "true")
      .getOrCreate()
}
