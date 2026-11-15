package org.scholarchain.spark

import java.io.File

import org.apache.spark.sql.{Encoders, SparkSession}
import org.apache.spark.sql.functions.{col, count, expr, length, max, min}

/** Test-scope local runner for the JSON Lines export (Spark is a Provided dependency): `sbt
  * "sparkAnalytics/Test/runMain org.scholarchain.spark.LocalJsonlExport --source-run DIR --out-root
  * DIR [--master local[6]] [--shuffle-partitions 32] [--records-per-file N]"`.
  *
  * After the export it prints report evidence computed from the exported JSON Lines (nothing is
  * written besides the export): the property keys present on GraphNode rows per node table, and per
  * semantic-summary kind the text length (characters of `snippet`) min / median / max and the first
  * line of the first file.
  */
object LocalJsonlExport {

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
      .appName("scholarchain-jsonl-export-local")
      .config("spark.ui.enabled", "false")
      .config("spark.sql.shuffle.partitions", shuffle)
      .config("spark.default.parallelism", shuffle)
      .config("spark.sql.autoBroadcastJoinThreshold", "-1")
      .config("spark.sql.adaptive.autoBroadcastJoinThreshold", "-1")
      .config("spark.local.dir", new File(outRoot, "_spark-local").getAbsolutePath)
      .config("spark.sql.warehouse.dir", new File(outRoot, "_warehouse").getAbsolutePath)
      .config("spark.driver.host", "127.0.0.1")
      .config("spark.driver.bindAddress", "127.0.0.1")
      .getOrCreate()
    val t0 = System.nanoTime()
    try {
      val r = JsonlExportJob.runWith(spark, jobArgs)
      val wall = (System.nanoTime() - t0) / 1e9
      println(
        s"[scholarchain] export ${r.runId} dir=${r.outRunDir} wall=$wall s " +
          s"heapMax=${Runtime.getRuntime.maxMemory() >> 20} MB totals=${ujson.write(r.manifest("totals"))}"
      )
      r.tables.foreach { t =>
        println(
          s"[counts] ${t.source.table} exported=${t.records} loadManifest=${t.source.records} " +
            s"files=${t.files.size} bytes=${t.bytes}"
        )
      }
      stats(spark, r)
    } finally spark.stop()
  }

  private def stats(spark: SparkSession, r: ExportResult): Unit = {
    import spark.implicits._
    r.tables.filter(_.source.path.startsWith("nodes/")).filter(_.records > 0).foreach { t =>
      val dir = new File(r.outRunDir, t.source.path).getPath
      val keys = spark.read
        .textFile(dir)
        .flatMap(l => ujson.read(l)("properties").obj.keys.toSeq)(Encoders.STRING)
        .groupBy("value")
        .count()
        .orderBy("value")
        .collect()
      println(
        s"[properties] ${t.source.table} rows=${t.records} keys=" +
          keys.map(k => s"${k.getString(0)}:${k.getLong(1)}").mkString(", ")
      )
    }
    r.tables.filter(_.source.path.startsWith("semantic_summary/")).foreach { t =>
      val dir = new File(r.outRunDir, t.source.path).getPath
      val lens = spark.read
        .textFile(dir)
        .map(l => ujson.read(l)("snippet").str)
        .select(length(col("value")).as("len"))
        .agg(
          count("len").as("n"),
          min("len").as("min"),
          expr("percentile(len, 0.5)").as("median"),
          max("len").as("max")
        )
        .collect()
        .head
      println(
        s"[textlen] ${t.source.table} n=${lens.getLong(0)} min=${lens.get(1)} " +
          s"median=${lens.get(2)} max=${lens.get(3)}"
      )
    }
    // Text length per summary kind across all windows, and one sample line per kind.
    Seq("researcher_profile", "community", "path").foreach { kind =>
      val ts = r.tables.filter(_.source.path.startsWith(s"semantic_summary/summary_kind=$kind/"))
      val dirs = ts.map(t => new File(r.outRunDir, t.source.path).getPath)
      if (dirs.nonEmpty) {
        val lens = spark.read
          .textFile(dirs: _*)
          .map(l => ujson.read(l)("snippet").str)
          .select(length(col("value")).as("len"))
          .agg(
            count("len").as("n"),
            min("len").as("min"),
            expr("percentile(len, 0.5)").as("median"),
            max("len").as("max")
          )
          .collect()
          .head
        println(
          s"[textlen-kind] $kind n=${lens.getLong(0)} min=${lens.get(1)} " +
            s"median=${lens.get(2)} max=${lens.get(3)}"
        )
        val first = ts.flatMap(_.files).headOption.map(f => new File(r.outRunDir, f.path))
        first.foreach { f =>
          val src = scala.io.Source.fromFile(f, "UTF-8")
          try println(s"[sample] $kind ${src.getLines().next()}")
          finally src.close()
        }
      }
    }
  }
}
