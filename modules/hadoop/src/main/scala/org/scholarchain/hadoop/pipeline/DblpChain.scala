package org.scholarchain.hadoop.pipeline

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.scholarchain.hadoop.common.{JobObserver, JobResult, JobRunner, PathIds}
import org.scholarchain.hadoop.dblp.DblpExtractJob
import org.scholarchain.hadoop.pairs.{PairConsolidateJob, PairExpandJob}
import org.scholarchain.hadoop.teams.PublicationTeamJob

/** Results of the dblp chain J01 -> J07 -> J08a -> J08b. */
final case class DblpChainResult(
    extract: JobResult,
    teams: JobResult,
    expand: JobResult,
    consolidate: JobResult
) {
  def all: List[JobResult] = List(extract, teams, expand, consolidate)
}

/** Runs the dblp jobs in order. Each downstream job's input fingerprint is its upstream run ID
  * (recorded in plain form as `inputRuns` in its `_job.json`), so a rerun with identical inputs,
  * code and configuration reuses every output directory.
  *
  * @param inputFingerprint
  *   fingerprint of the raw dblp input; when absent it is computed from the file contents. Either
  *   way, when `scholarchain.dblp.dtdPath` is set the DTD file's content is added (see
  *   [[DblpExtractJob.inputFingerprint]]).
  * @param observer
  *   callbacks around each job (timing, disk checks); observation only
  */
object DblpChain {
  def run(
      conf: Configuration,
      dblpInput: Path,
      outputRoot: Path,
      snapshotId: String,
      inputFingerprint: Option[String] = None,
      observer: JobObserver = JobObserver.Silent
  ): DblpChainResult = {
    PathIds.require("dblp snapshot", snapshotId)
    val raw = inputFingerprint.getOrElse(JobRunner.fingerprintFiles(conf, List(dblpInput)))
    val fp = DblpExtractJob.inputFingerprint(conf, raw)
    val j01 = JobObserver.around(observer, DblpExtractJob.JobName)(
      JobRunner.run(conf, DblpExtractJob.spec(dblpInput, outputRoot, snapshotId, fp))
    )
    val j07 = JobObserver.around(observer, PublicationTeamJob.JobName)(
      JobRunner.run(
        conf,
        JobRunner.fromRuns(List(j01.runId))(
          PublicationTeamJob.spec(
            j01.dir(DblpExtractJob.Works),
            j01.dir(DblpExtractJob.Mentions),
            outputRoot,
            snapshotId,
            _
          )
        )
      )
    )
    val j08a = JobObserver.around(observer, PairExpandJob.JobName)(
      JobRunner.run(
        conf,
        JobRunner.fromRuns(List(j07.runId))(
          PairExpandJob.spec(j07.dir(PublicationTeamJob.Teams), outputRoot, snapshotId, _)
        )
      )
    )
    val j08b = JobObserver.around(observer, PairConsolidateJob.JobName)(
      JobRunner.run(
        conf,
        JobRunner.fromRuns(List(j08a.runId))(
          PairConsolidateJob.spec(j08a.dir(PairExpandJob.Instances), outputRoot, snapshotId, _)
        )
      )
    )
    DblpChainResult(j01, j07, j08a, j08b)
  }
}
