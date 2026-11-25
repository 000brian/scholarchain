package org.scholarchain.hadoop.pipeline

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.scholarchain.core.RunId
import org.scholarchain.hadoop.blocking.{
  BlockSizeJob,
  BlockingJob,
  FallbackBlockSizeJob,
  FallbackBlockingJob,
  FallbackCoverageJob
}
import org.scholarchain.hadoop.common.{JobObserver, JobResult, JobRunner}

/** Results of the blocking chain J05p -> J05 -> J05bp -> J05b -> J05c. */
final case class BlockingChainResult(
    blockSizes: JobResult,
    blocking: JobResult,
    fallbackBlockSizes: JobResult,
    fallbackBlocking: JobResult,
    fallbackCoverage: JobResult
) {
  def all: List[JobResult] =
    List(blockSizes, blocking, fallbackBlockSizes, fallbackBlocking, fallbackCoverage)
}

/** Runs the blocking jobs in order on one configuration, each counting pre-pass before the job it
  * serves (Phase 17 R3, check HADOOP-04):
  *
  * {{{
  * mentionContexts --J05p--> oversizeKeys (primary)
  * mentionContexts + J05p oversizeKeys --J05--> blocks, oversizeBlocks, oversizeMembers
  * oversizeMembers --J05bp--> oversizeKeys (fallback, still oversize)
  * oversizeMembers + J05bp oversizeKeys --J05b--> fallback blocks, fallbackOversizeBlocks, coverage
  * oversizeMembers + coverage --J05c--> uncoveredMembers, oversizeKeySummary
  * }}}
  *
  * Each job's input runs are the runs it reads (J05: J07 and J05p; J05b: J05 and J05bp), so a rerun
  * with identical inputs, code and configuration reuses every output directory, and a changed cap
  * changes the pre-pass run and with it every later run ID.
  */
object BlockingChain {

  /** @param contexts
    *   J07 `mentionContexts/`
    * @param teamsRun
    *   the J07 run that wrote `contexts`
    */
  def run(
      conf: Configuration,
      contexts: Path,
      teamsRun: RunId,
      outputRoot: Path,
      snapshotId: String,
      observer: JobObserver = JobObserver.Silent
  ): BlockingChainResult = {
    def step(name: String)(job: => JobResult): JobResult = JobObserver.around(observer, name)(job)
    val sizes = step(BlockSizeJob.JobName)(
      JobRunner.run(
        conf,
        JobRunner.fromRuns(List(teamsRun))(
          BlockSizeJob.spec(contexts, outputRoot, snapshotId, _)
        )
      )
    )
    val blocking = step(BlockingJob.JobName)(
      JobRunner.run(
        conf,
        JobRunner.fromRuns(List(teamsRun, sizes.runId))(
          BlockingJob.spec(
            contexts,
            outputRoot,
            snapshotId,
            _,
            Some(sizes.dir(BlockSizeJob.OversizeKeys))
          )
        )
      )
    )
    val oversizeMembers = blocking.dir(BlockingJob.OversizeMembers)
    val fallbackSizes = step(FallbackBlockSizeJob.JobName)(
      JobRunner.run(
        conf,
        JobRunner.fromRuns(List(blocking.runId))(
          FallbackBlockSizeJob.spec(oversizeMembers, outputRoot, snapshotId, _)
        )
      )
    )
    val fallbackBlocking = step(FallbackBlockingJob.JobName)(
      JobRunner.run(
        conf,
        JobRunner.fromRuns(List(blocking.runId, fallbackSizes.runId))(
          FallbackBlockingJob.spec(
            oversizeMembers,
            outputRoot,
            snapshotId,
            _,
            Some(fallbackSizes.dir(FallbackBlockSizeJob.OversizeKeys))
          )
        )
      )
    )
    val fallbackCoverage = step(FallbackCoverageJob.JobName)(
      JobRunner.run(
        conf,
        JobRunner.fromRuns(List(blocking.runId, fallbackBlocking.runId))(
          FallbackCoverageJob.spec(
            oversizeMembers,
            fallbackBlocking.dir(FallbackBlockingJob.Coverage),
            outputRoot,
            snapshotId,
            _
          )
        )
      )
    )
    BlockingChainResult(sizes, blocking, fallbackSizes, fallbackBlocking, fallbackCoverage)
  }
}
