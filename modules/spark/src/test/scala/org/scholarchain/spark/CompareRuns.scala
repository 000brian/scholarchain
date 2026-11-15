package org.scholarchain.spark

import java.io.File

import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.functions.{array_sort, col, lit, map_entries, regexp_replace, udf, when}
import org.scholarchain.core.{EvidenceId, EvidenceType, RunId}

/** Test-scope evidence tool: compares the rows of every table listed in two run manifests
  * (exceptAll both ways plus counts). Parquet file bytes can differ between JVMs only in footer
  * metadata (the order of the per-column encodings set), so determinism is checked on rows.
  *
  * `sbt "sparkAnalytics/Test/runMain org.scholarchain.spark.CompareRuns <runDirA> <runDirB>
  * [--ignore-run-id]"`
  *
  * With `--ignore-run-id`, each row is compared as its JSON text with the run's own run ID replaced
  * by a fixed placeholder, so two runs whose run IDs differ only because the code version differs
  * (the code version is part of the run ID, which every provenance row carries) can be shown to
  * have otherwise identical rows.
  */
object CompareRuns {

  def main(argv: Array[String]): Unit = {
    val Array(a, b) = argv.take(2).map(new File(_))
    val ignoreRunId = argv.drop(2).contains("--ignore-run-id")
    val tmp = SparkTestSupport.tempDir("compare-runs")
    val spark: SparkSession = SparkTestSupport.session(tmp, cores = 6, shufflePartitions = 32)
    try {
      val ma = ujson.read(Files2.readString(new File(a, Manifests.RunManifestName)))
      val mb = ujson.read(Files2.readString(new File(b, Manifests.RunManifestName)))
      val (ra, rb) = (ma("runId").str, mb("runId").str)
      println(s"[compare] runId A=$ra B=$rb ignoreRunId=$ignoreRunId")
      val pa = ma("outputs").arr.map(_("path").str).toList
      val pb = mb("outputs").arr.map(_("path").str).toList
      println(s"[compare] tables A=${pa.size} B=${pb.size} sameList=${pa == pb}")
      var different = 0
      pa.foreach { p =>
        // Set operations do not accept map columns; compare maps as sorted entry arrays.
        def load(root: File, runId: String): DataFrame = {
          val df = spark.read.parquet(new File(root, p).getPath)
          val noMaps = df.schema.fields.foldLeft(df) { (d, f) =>
            f.dataType match {
              case _: org.apache.spark.sql.types.MapType =>
                d.withColumn(f.name, array_sort(map_entries(col(f.name))))
              case _ => d
            }
          }
          // Evidence IDs are EvidenceId.of(type, runId, subjectIds): mask an ID only when it is
          // exactly that formula for this run, so any other difference still shows.
          val masked =
            if (!ignoreRunId || !noMaps.columns.contains("evidence")) noMaps
            else {
              val derived = udf { (t: String, ids: Seq[String]) =>
                val et = EvidenceType.fromWire(t).fold(e => sys.error(e), identity)
                EvidenceId.of(et, RunId.parse(runId).fold(e => sys.error(e), identity), ids).value
              }
              val ok = col("evidence.evidenceId") ===
                derived(col("evidence.evidenceType"), col("evidence.subjectIds"))
              noMaps.withColumn(
                "evidence",
                col("evidence").withField(
                  "evidenceId",
                  when(ok, lit("<derived-from-runId>")).otherwise(col("evidence.evidenceId"))
                )
              )
            }
          if (!ignoreRunId) masked
          else
            masked.toJSON
              .toDF("row")
              .select(regexp_replace(col("row"), lit(runId), lit("<runId>")).as("row"))
        }
        val da = load(a, ra)
        val db = load(b, rb)
        val onlyA = da.exceptAll(db).count()
        val onlyB = db.exceptAll(da).count()
        val (ca, cb) = (da.count(), db.count())
        if (onlyA != 0 || onlyB != 0 || ca != cb) different += 1
        println(s"[compare] $p rows=$ca/$cb onlyA=$onlyA onlyB=$onlyB")
      }
      println(s"[compare] tables with different rows: $different")
    } finally spark.stop()
  }
}
