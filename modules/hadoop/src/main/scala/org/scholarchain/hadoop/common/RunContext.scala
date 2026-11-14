package org.scholarchain.hadoop.common

import org.apache.hadoop.conf.Configuration
import org.scholarchain.core.{Provenance, RunId, SchemaVersions, SourceType}

/** Identity of one job run, carried to every task through the job configuration.
  *
  * All fields are fixed by the driver before submission; tasks only read them, so there is no
  * mutable state shared between map or reduce tasks.
  */
final case class RunContext(
    runId: RunId,
    jobName: String,
    datasetId: String,
    snapshotId: String,
    policyVersion: String
) {

  def provenance(
      sourceType: SourceType,
      sourceRecordIds: List[String],
      contentHash: Option[String],
      datasetId: String = datasetId,
      snapshotId: String = snapshotId
  ): Provenance =
    Provenance(
      SchemaVersions.currentOf("Provenance"),
      sourceType,
      datasetId,
      snapshotId,
      sourceRecordIds,
      runId,
      jobName,
      policyVersion,
      contentHash
    )

  def writeTo(conf: Configuration): Unit = {
    conf.set(Settings.Keys.RunId, runId.value)
    conf.set(Settings.Keys.RunJobName, jobName)
    conf.set(Settings.Keys.RunDatasetId, datasetId)
    conf.set(Settings.Keys.RunSnapshotId, snapshotId)
    conf.set(Settings.Keys.RunPolicyVersion, policyVersion)
  }
}

object RunContext {
  def from(conf: Configuration): RunContext = {
    val s = new Settings(conf)
    RunContext(
      RunId
        .parse(s.string(Settings.Keys.RunId))
        .fold(e => throw SettingsError("run id", e), identity),
      s.string(Settings.Keys.RunJobName),
      s.string(Settings.Keys.RunDatasetId),
      s.string(Settings.Keys.RunSnapshotId),
      s.string(Settings.Keys.RunPolicyVersion)
    )
  }
}
