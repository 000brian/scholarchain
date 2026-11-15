package org.scholarchain.storage.s3

import scala.collection.immutable.ListMap

import org.scholarchain.core.{ContentHash, Hashing, RunId}
import org.scholarchain.core.config.S3Location
import org.scholarchain.storage.ports.{ObjectRef, ObjectStore, PutOutcome}

/** The ordinary-S3 key layout. Buckets and zone prefixes come from configuration
  * (`ScholarChainConfig.s3Zones`); nothing here names a bucket.
  *
  * Run-scoped keys carry dataset, snapshot and run ID, so a rerun with a new run ID never
  * overwrites an earlier run, and a rerun with the same run ID writes byte-identical objects
  * (put-if-absent reports them as already present):
  *
  * | zone                 | key                                                                        |
  * |:---------------------|:---------------------------------------------------------------------------|
  * | raw                  | `<raw>/dataset=<d>/snapshot=<s>/<file>`                                    |
  * | curated              | `<curated>/dataset=<d>/snapshot=<s>/run=<hex>/<table path>/<file>`         |
  * | graph (export copy)  | `<graph>/dataset=<d>/snapshot=<s>/run=<hex>/export/<table path>/<file>`    |
  * | graph (Neptune load) | `<graph>/dataset=<d>/snapshot=<s>/run=<hex>/neptune/<stage>/<type>/<file>` |
  * | semantic-context     | `<semantic-context>/text/v1/index=<index>/sha256=<hex>.txt`                |
  * | reports              | `<reports>/dataset=<d>/snapshot=<s>/run=<hex>/<name>`                      |
  * | logs                 | `<logs>/dataset=<d>/snapshot=<s>/run=<hex>/<component>/<name>`             |
  * | run manifest         | `<run-scoped prefix of the zone>/_run_manifest.json`                       |
  *
  * Semantic-context text is content-addressed (the key holds the text hash and no run), so
  * unchanged text is written once and shared by every run that produces it.
  */
final class S3KeyLayout(zones: ListMap[String, S3Location]) {
  import S3KeyLayout._

  S3KeyLayout.Zones.foreach(z => require(zones.contains(z), s"zone '$z' is not configured"))

  def zone(name: String): S3Location = zones(name)

  private def runScoped(zoneName: String, datasetId: String, snapshotId: String, runId: RunId) =
    s"${zone(zoneName).prefix}/dataset=${seg(datasetId)}/snapshot=${seg(snapshotId)}/${runSegment(runId)}"

  def rawObject(datasetId: String, snapshotId: String, fileName: String): ObjectRef =
    ObjectRef(
      zone("raw").bucket,
      s"${zone("raw").prefix}/dataset=${seg(datasetId)}/snapshot=${seg(snapshotId)}/${seg(fileName)}"
    )

  def curatedObject(
      datasetId: String,
      snapshotId: String,
      runId: RunId,
      relativePath: String
  ): ObjectRef =
    ObjectRef(
      zone("curated").bucket,
      s"${runScoped("curated", datasetId, snapshotId, runId)}/${rel(relativePath)}"
    )

  def graphExportObject(
      datasetId: String,
      snapshotId: String,
      runId: RunId,
      relativePath: String
  ): ObjectRef =
    ObjectRef(
      zone("graph").bucket,
      s"${runScoped("graph", datasetId, snapshotId, runId)}/export/${rel(relativePath)}"
    )

  /** The S3 prefix of one Neptune bulk-load stage (`nodes` or `edges`); the loader reads every file
    * under it.
    */
  def neptuneStagePrefix(datasetId: String, snapshotId: String, runId: RunId, stage: String) = {
    require(NeptuneStages.contains(stage), s"unknown Neptune load stage '$stage'")
    ObjectRef(
      zone("graph").bucket,
      s"${runScoped("graph", datasetId, snapshotId, runId)}/neptune/$stage/"
    )
  }

  def textObject(indexName: String, textHash: String): ObjectRef =
    ObjectRef(
      zone("semantic-context").bucket,
      textKey(zone("semantic-context").prefix, indexName, textHash)
    )

  def reportObject(datasetId: String, snapshotId: String, runId: RunId, name: String): ObjectRef =
    ObjectRef(
      zone("reports").bucket,
      s"${runScoped("reports", datasetId, snapshotId, runId)}/${seg(name)}"
    )

  def logObject(
      datasetId: String,
      snapshotId: String,
      runId: RunId,
      component: String,
      name: String
  ): ObjectRef =
    ObjectRef(
      zone("logs").bucket,
      s"${runScoped("logs", datasetId, snapshotId, runId)}/${seg(component)}/${seg(name)}"
    )

  def runManifest(zoneName: String, datasetId: String, snapshotId: String, runId: RunId) =
    ObjectRef(
      zone(zoneName).bucket,
      s"${runScoped(zoneName, datasetId, snapshotId, runId)}/$RunManifestName"
    )
}

object S3KeyLayout {
  val Zones: List[String] = List("raw", "curated", "graph", "semantic-context", "reports", "logs")
  val NeptuneStages: Set[String] = Set("nodes", "edges")
  val RunManifestName = "_run_manifest.json"

  private val Segment = "[A-Za-z0-9][A-Za-z0-9._=-]*".r

  def seg(s: String): String =
    if (s != null && Segment.matches(s) && s != "." && s != "..") s
    else throw new IllegalArgumentException(s"invalid S3 key segment '$s'")

  def rel(path: String): String = path.split('/').map(seg).mkString("/")

  /** `run=<32 hex>` of a run ID. */
  def runSegment(runId: RunId): String = s"run=${runId.value.stripPrefix(RunId.Prefix)}"

  /** Text key relative to the bucket: `<prefix>/text/v1/index=<index>/sha256=<hex>.txt`. */
  def textKey(prefix: String, indexName: String, textHash: String): String = {
    require(ContentHash.isWellFormed(textHash), s"invalid text hash '$textHash'")
    s"$prefix/text/v1/index=${seg(indexName)}/sha256=${textHash.stripPrefix(ContentHash.Prefix)}.txt"
  }
}

/** One object listed in a run manifest, with its content hash and source references. */
final case class ManifestEntry(key: String, bytes: Long, sha256: String, sourceRefs: List[String])

/** A run manifest: every object a run wrote under one zone, with sha256 and source references.
  * Written once with put-if-absent; the same run writes byte-identical JSON.
  */
final case class RunManifest(
    zoneName: String,
    runId: RunId,
    datasetId: String,
    snapshotId: String,
    jobName: String,
    entries: List[ManifestEntry]
) {
  def toJson: String = ujson
    .Obj(
      "format" -> "scholarchain.storage.run-manifest",
      "formatVersion" -> 1,
      "zone" -> zoneName,
      "runId" -> runId.value,
      "datasetId" -> datasetId,
      "snapshotId" -> snapshotId,
      "jobName" -> jobName,
      "objects" -> ujson.Arr.from(entries.sortBy(_.key).map { e =>
        ujson.Obj(
          "key" -> e.key,
          "bytes" -> e.bytes.toDouble,
          "sha256" -> e.sha256,
          "sourceRefs" -> ujson.Arr.from(e.sourceRefs.map(ujson.Str(_)))
        )
      })
    )
    .render(indent = 2)

  /** Writes the manifest put-if-absent; the run's objects are written before it. */
  def write(store: ObjectStore, layout: S3KeyLayout): PutOutcome = {
    val bytes = toJson.getBytes(java.nio.charset.StandardCharsets.UTF_8)
    store.putIfAbsent(
      layout.runManifest(zoneName, datasetId, snapshotId, runId),
      bytes,
      "application/json",
      Map(ObjectStore.Sha256MetadataKey -> Hashing.sha256Hex(bytes))
    )
  }
}
