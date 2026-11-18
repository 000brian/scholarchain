package org.scholarchain.storage.load

import scala.util.control.NonFatal

import org.scholarchain.core.RunId
import org.scholarchain.core.config.S3Location
import org.scholarchain.storage.ports.ObjectRef
import org.scholarchain.storage.s3.{ManifestEntry, S3KeyLayout}

/** One Neptune CSV graph run in the graph zone:
  * `s3://BUCKET/<graph prefix>/dataset=<d>/snapshot=<s>/run=<32 hex>`, as written by
  * `StorageLoadMain neptune-csv-build` (the CSV files under `neptune/nodes/` and `neptune/edges/`,
  * then `_run_manifest.json`).
  *
  * @param root
  *   the run prefix, without a trailing `/`
  */
final case class GraphRun(root: ObjectRef, datasetId: String, snapshotId: String, runHex: String) {
  def manifestRef: ObjectRef = ObjectRef(root.bucket, s"${root.key}/${S3KeyLayout.RunManifestName}")
  def stagePrefix(stage: String): ObjectRef = {
    require(S3KeyLayout.NeptuneStages.contains(stage), s"unknown Neptune load stage '$stage'")
    ObjectRef(root.bucket, s"${root.key}/neptune/$stage/")
  }
}

object GraphRun {
  private val Uri = "s3://([a-z0-9][a-z0-9.-]{1,61}[a-z0-9])/(.+)".r
  private val Tail =
    "dataset=([A-Za-z0-9][A-Za-z0-9._=-]*)/snapshot=([A-Za-z0-9][A-Za-z0-9._=-]*)/run=([0-9a-f]{32})".r

  /** Parses `--graph-run`; it must lie in the configured graph zone. Messages never repeat the URI.
    */
  def parse(uri: String, graphZone: S3Location): Either[String, GraphRun] =
    Option(uri).getOrElse("") match {
      case Uri(bucket, rest) =>
        val key = rest.stripSuffix("/")
        val prefix = s"${graphZone.prefix}/"
        if (bucket != graphZone.bucket || !key.startsWith(prefix))
          Left("--graph-run must be a run prefix in the configured graph zone")
        else
          key.stripPrefix(prefix) match {
            case Tail(d, s, hex) => Right(GraphRun(ObjectRef(bucket, key), d, s, hex))
            case _               =>
              Left("--graph-run must end in dataset=<d>/snapshot=<s>/run=<32 hex>")
          }
      case _ => Left("--graph-run is not an s3://BUCKET/KEY URI")
    }

  /** The graph run of a layout and run ID (what `neptune-csv-build` writes). */
  def of(layout: S3KeyLayout, datasetId: String, snapshotId: String, runId: RunId): GraphRun = {
    val m = layout.runManifest("graph", datasetId, snapshotId, runId)
    GraphRun(
      ObjectRef(m.bucket, m.key.stripSuffix(s"/${S3KeyLayout.RunManifestName}")),
      datasetId,
      snapshotId,
      S3KeyLayout.runSegment(runId).stripPrefix("run=")
    )
  }

  /** The objects of a graph run manifest (`scholarchain.storage.run-manifest` v1, zone `graph`, job
    * `storage.neptune-csv`, run ID matching the run prefix).
    */
  def parseManifest(text: String, run: GraphRun): Either[String, List[ManifestEntry]] =
    try {
      val o = ujson.read(text).obj
      def str(k: String) = o.get(k).flatMap(_.strOpt).getOrElse("")
      val version = o.get("formatVersion").flatMap(_.numOpt).getOrElse(-1.0)
      if (str("format") != "scholarchain.storage.run-manifest" || version != 1.0)
        Left("graph run manifest: unsupported format")
      else if (str("zone") != "graph" || str("jobName") != StorageLoadMain.NeptuneCsvJob)
        Left("graph run manifest: not a Neptune CSV graph run")
      else if (str("runId").stripPrefix(RunId.Prefix) != run.runHex)
        Left("graph run manifest: run ID differs from the run prefix")
      else if (str("datasetId") != run.datasetId || str("snapshotId") != run.snapshotId)
        Left("graph run manifest: dataset or snapshot differs from the run prefix")
      else
        Right(o("objects").arr.toList.map { e =>
          val x = e.obj
          ManifestEntry(
            x("key").str,
            x("bytes").num.toLong,
            x("sha256").str,
            x("sourceRefs").arr.toList.map(_.str)
          )
        })
    } catch {
      case NonFatal(e) => Left(s"graph run manifest: malformed (${e.getClass.getSimpleName})")
    }
}
