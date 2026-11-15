package org.scholarchain.spark

import java.io.File

import org.apache.spark.sql.SparkSession

/** Test-scope local runner for the real run (Spark is a Provided dependency, so the job runs with
  * `sbt "sparkAnalytics/Test/runMain org.scholarchain.spark.LocalRealRun ..."` in the forked test
  * JVM).
  *
  * Extra arguments besides those of [[GraphAnalyticsJob]]:
  * {{{
  *   --master local[6]            Spark master (default local[6])
  *   --shuffle-partitions 32      spark.sql.shuffle.partitions (default 32)
  * }}}
  * Spark local (shuffle and spill) files and the warehouse go under the attempt root; broadcast
  * joins are disabled so no edge table is ever broadcast.
  */
object LocalRealRun {

  def main(argv: Array[String]): Unit = {
    val pairs = argv.toSeq.grouped(2).collect { case Seq(k, v) => k -> v }.toMap
    val master = pairs.getOrElse("--master", "local[6]")
    val shuffle = pairs.getOrElse("--shuffle-partitions", "32")
    val outRoot = new File(pairs.getOrElse("--out-root", sys.error("missing --out-root")))
    outRoot.mkdirs()
    val jobArgs = argv.toSeq
      .grouped(2)
      .filterNot(p => p.head == "--master" || p.head == "--shuffle-partitions")
      .flatten
      .toSeq
    val spark = SparkSession
      .builder()
      .master(master)
      .appName("scholarchain-graph-analytics-local")
      .config("spark.ui.enabled", "false")
      .config("spark.sql.shuffle.partitions", shuffle)
      .config("spark.default.parallelism", shuffle)
      .config("spark.sql.autoBroadcastJoinThreshold", "-1")
      .config("spark.sql.adaptive.autoBroadcastJoinThreshold", "-1")
      .config("spark.local.dir", new File(outRoot, "_spark-local").getAbsolutePath)
      .config("spark.sql.warehouse.dir", new File(outRoot, "_warehouse").getAbsolutePath)
      .config("spark.driver.host", "127.0.0.1")
      .config("spark.driver.bindAddress", "127.0.0.1")
      .config("spark.cleaner.referenceTracking.cleanCheckpoints", "true")
      .getOrCreate()
    val t0 = System.nanoTime()
    try {
      val r = GraphAnalyticsJob.runWith(spark, jobArgs)
      println(
        s"[scholarchain] run ${r.runId} reused=${r.reused} dir=${r.runDir} " +
          s"wall=${(System.nanoTime() - t0) / 1e9} s heapMax=${Runtime.getRuntime.maxMemory() >> 20} MB"
      )
    } finally spark.stop()
  }
}
