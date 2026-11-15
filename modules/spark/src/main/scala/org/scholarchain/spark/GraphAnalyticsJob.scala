package org.scholarchain.spark

import java.io.File

import org.apache.spark.sql.SparkSession

/** Entry point of the Spark collaboration-analysis pipeline.
  *
  * Arguments (all required unless noted):
  * {{{
  *   --hadoop-export DIR          Hadoop J09 export run directory (run=<id>)
  *   --identity-assignments DIR   entity-resolution decisions/identity_assignment directory
  *   --out-root DIR               attempt root; outputs go to DIR/run=<run hex>
  *   --config FILE                SparkJobConfig JSON (see modules/spark/conf/)
  *   --code-version STR           code version recorded in the run ID and manifest
  *   --min-free-disk-gb N         optional, default 15: stop before a stage below this
  *   --max-stage-minutes N        optional, default 90: stop after a stage longer than this
  * }}}
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
      new File(h),
      new File(i),
      new File(o),
      cfg,
      v,
      pairs.get("min-free-disk-gb").map(_.toLong).getOrElse(15L) * (1L << 30),
      pairs.get("max-stage-minutes").map(_.toLong).getOrElse(90L) * 60000L
    )
  }

  def runWith(spark: SparkSession, argv: Seq[String]): PipelineResult =
    parseArgs(argv) match {
      case Left(e)  => throw new IllegalArgumentException(e)
      case Right(a) => Pipeline.run(spark, a)
    }

  def main(argv: Array[String]): Unit = {
    val spark = SparkSession.builder().appName("scholarchain-graph-analytics").getOrCreate()
    try {
      val r = runWith(spark, argv.toSeq)
      println(s"[scholarchain] run ${r.runId} at ${r.runDir} (reused=${r.reused})")
    } finally spark.stop()
  }
}
