package org.scholarchain.spark

import java.io.File

import org.apache.hadoop.fs.Path
import org.apache.spark.sql.SparkSession

/** Entry point of the Spark collaboration-analysis pipeline.
  *
  * Arguments (all required unless noted):
  * {{{
  *   --hadoop-export DIR          Hadoop J09 export run directory (run=<id>)
  *   --identity-assignments DIR   entity-resolution decisions/identity_assignment directory
  *   --out-root DIR               attempt root; outputs go to DIR/run=<run hex>
  *   --config FILE                SparkJobConfig JSON on the driver's local disk (modules/spark/conf/)
  *   --code-version STR           code version recorded in the run ID and manifest
  *   --min-free-disk-gb N         optional, default 15: stop before a stage below this
  *   --max-stage-minutes N        optional, default 90: stop after a stage longer than this
  * }}}
  *
  * The three directories are Hadoop paths (`s3://...`, `hdfs://...`, `file:///...`); a path without
  * a scheme resolves against the cluster's default file system (HDFS on EMR), so pass full URIs.
  *
  * On a cluster the session comes from spark-submit; locally use the test-scope runner
  * `LocalRealRun` (Spark is a Provided dependency).
  */
object GraphAnalyticsJob {

  def parseArgs(argv: Seq[String]): Either[String, PipelineArgs] = {
    val pairs =
      argv.grouped(2).collect { case Seq(k, v) if k.startsWith("--") => k.drop(2) -> v }.toMap
    def req(k: String) = pairs.get(k).toRight(s"missing --$k")
    for {
      h <- req("hadoop-export")
      i <- req("identity-assignments")
      o <- req("out-root")
      c <- req("config")
      v <- req("code-version")
      cfg <- SparkJobConfig
        .parse(Files2.readString(new File(c)))
        .left
        .map(e => s"invalid config $c: ${e.mkString("; ")}")
    } yield PipelineArgs(
      new Path(h),
      new Path(i),
      new Path(o),
      cfg,
      v,
      pairs.get("min-free-disk-gb").map(_.toLong).getOrElse(15L) * (1L << 30),
      pairs.get("max-stage-minutes").map(_.toLong).getOrElse(90L) * 60000L
    )
  }

  /** Runtime SQL settings the pipeline needs on every session (Phase 6 F9 part 1): automatic
    * broadcast joins are off, so no edge table is ever broadcast (the local runner and the tests
    * set the same values).
    */
  val RequiredSqlConf: Seq[(String, String)] = Seq(
    "spark.sql.autoBroadcastJoinThreshold" -> "-1",
    "spark.sql.adaptive.autoBroadcastJoinThreshold" -> "-1"
  )

  /** Applies [[RequiredSqlConf]] to the session (runtime SQL settings; they take effect at once).
    */
  def configure(spark: SparkSession): Unit =
    RequiredSqlConf.foreach { case (k, v) => spark.conf.set(k, v) }

  /** The EMR entry point's path: applies [[RequiredSqlConf]], then parses and runs. */
  def runWith(spark: SparkSession, argv: Seq[String]): PipelineResult = {
    configure(spark)
    parseArgs(argv) match {
      case Left(e)  => throw new IllegalArgumentException(e)
      case Right(a) => Pipeline.run(spark, a)
    }
  }

  def main(argv: Array[String]): Unit = {
    val builder = SparkSession.builder().appName("scholarchain-graph-analytics")
    val spark =
      RequiredSqlConf.foldLeft(builder) { case (b, (k, v)) => b.config(k, v) }.getOrCreate()
    try {
      val r = runWith(spark, argv.toSeq)
      println(s"[scholarchain] run ${r.runId} at ${r.runPath} (reused=${r.reused})")
    } finally spark.stop()
  }
}
