package org.scholarchain.hadoop.pipeline

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.scholarchain.hadoop.blocking.{BlockingJob, FallbackBlockingJob, FallbackCoverageJob}
import org.scholarchain.hadoop.candidates.CandidateFeatureJob
import org.scholarchain.hadoop.common.{JobObserver, JobResult, JobRunner, PathIds}
import org.scholarchain.hadoop.dblp.DblpExtractJob
import org.scholarchain.hadoop.graphexport.{ExportEntity, GraphExportJob}
import org.scholarchain.hadoop.ids.ExactIdJoinJob
import org.scholarchain.hadoop.pairs.PairConsolidateJob
import org.scholarchain.hadoop.sources.{NormalizeJob, SourceNormalizers}
import org.scholarchain.hadoop.teams.PublicationTeamJob

/** One raw enrichment input: normalizer kind, manifest dataset ID, snapshot (or retrieval date). */
final case class SourceInput(kind: String, datasetId: String, snapshotId: String, path: Path) {
  require(SourceNormalizers.kinds.contains(kind), s"unknown kind $kind")
  PathIds.require("dataset", datasetId)
  PathIds.require("snapshot", snapshotId)
}

/** @param dblpSnapshot
  *   path-forming identifier: must match [[PathIds.IdPattern]] (validated here, before any job
  *   touches the file system)
  */
final case class PipelineSpec(
    dblpInput: Path,
    dblpSnapshot: String,
    sources: List[SourceInput],
    outputRoot: Path,
    dblpInputFingerprint: Option[String] = None
) {
  PathIds.require("dblp snapshot", dblpSnapshot)
}

final case class PipelineResult(
    dblp: DblpChainResult,
    normalize: List[JobResult],
    join: JobResult,
    blocking: JobResult,
    fallbackBlocking: JobResult,
    fallbackCoverage: JobResult,
    candidates: JobResult,
    graphExport: JobResult
) {
  def all: List[JobResult] =
    dblp.all ++ normalize ++
      List(join, blocking, fallbackBlocking, fallbackCoverage, candidates, graphExport)
}

/** The Hadoop job graph:
  *
  * {{{
  * dblp XML --J01--> works, mentions, venues
  *   works + mentions --J07--> teams, authorPapers, mentionContexts
  *   teams --J08a--> pairInstances --J08b--> pairs
  *   mentionContexts --J05--> blocks, oversizeMembers
  *     oversizeMembers --J05b--> fallback blocks, coverage
  *     oversizeMembers + coverage --J05c--> uncoveredMembers, oversizeKeySummary
  *     blocks + fallback blocks --J06--> candidates (MatchCandidate)
  * enrichment JSONL --J02 (per kind)--> normalized, institutions, topics
  * mentionContexts + normalized --J03/J04--> links
  * works, authorPapers, teams, pairs, links, institutions, topics, venues, candidates
  *   --J09--> export
  * }}}
  *
  * Jobs run in this order on one Hadoop configuration; each output directory is addressed by its
  * run ID, so rerunning the same pipeline reuses completed outputs.
  */
object Pipeline {

  /** Path-safe label of the snapshot set a cross-dataset job reads. */
  def snapshotLabel(spec: PipelineSpec): String =
    (s"dblp-${spec.dblpSnapshot}" :: spec.sources
      .map(s => s"${s.datasetId}-${s.snapshotId}")
      .distinct
      .sorted).mkString("+")

  /** Runs every job in order.
    *
    * @param observer
    *   callbacks around each job (timing, disk checks); observation only, outputs never depend on
    *   it
    */
  def run(
      conf: Configuration,
      spec: PipelineSpec,
      observer: JobObserver = JobObserver.Silent
  ): PipelineResult = {
    def step(name: String)(job: => JobResult): JobResult = JobObserver.around(observer, name)(job)
    val dblp = DblpChain.run(
      conf,
      spec.dblpInput,
      spec.outputRoot,
      spec.dblpSnapshot,
      spec.dblpInputFingerprint,
      observer
    )
    val normalize = spec.sources.map { s =>
      step(NormalizeJob.JobNamePrefix + s.kind)(
        JobRunner.run(
          conf,
          NormalizeJob.spec(
            s.kind,
            s.path,
            spec.outputRoot,
            s.datasetId,
            s.snapshotId,
            JobRunner.fingerprintFiles(conf, List(s.path))
          )
        )
      )
    }
    val label = snapshotLabel(spec)
    val scope = s"linked/$label"
    val join = step(ExactIdJoinJob.JobName)(
      JobRunner.run(
        conf,
        JobRunner.fromRuns(dblp.teams.runId :: normalize.map(_.runId))(
          ExactIdJoinJob.spec(
            List(dblp.teams.dir(PublicationTeamJob.Contexts)),
            normalize.map(_.dir(NormalizeJob.Normalized)),
            spec.outputRoot,
            scope,
            label,
            _
          )
        )
      )
    )
    val blocking = step(BlockingJob.JobName)(
      JobRunner.run(
        conf,
        JobRunner.fromRuns(List(dblp.teams.runId))(
          BlockingJob.spec(
            dblp.teams.dir(PublicationTeamJob.Contexts),
            spec.outputRoot,
            spec.dblpSnapshot,
            _
          )
        )
      )
    )
    val fallbackBlocking = step(FallbackBlockingJob.JobName)(
      JobRunner.run(
        conf,
        JobRunner.fromRuns(List(blocking.runId))(
          FallbackBlockingJob.spec(
            blocking.dir(BlockingJob.OversizeMembers),
            spec.outputRoot,
            spec.dblpSnapshot,
            _
          )
        )
      )
    )
    val fallbackCoverage = step(FallbackCoverageJob.JobName)(
      JobRunner.run(
        conf,
        JobRunner.fromRuns(List(blocking.runId, fallbackBlocking.runId))(
          FallbackCoverageJob.spec(
            blocking.dir(BlockingJob.OversizeMembers),
            fallbackBlocking.dir(FallbackBlockingJob.Coverage),
            spec.outputRoot,
            spec.dblpSnapshot,
            _
          )
        )
      )
    )
    val candidates = step(CandidateFeatureJob.JobName)(
      JobRunner.run(
        conf,
        JobRunner.fromRuns(List(blocking.runId, fallbackBlocking.runId))(
          CandidateFeatureJob.spec(
            List(
              blocking.dir(BlockingJob.Blocks),
              fallbackBlocking.dir(FallbackBlockingJob.Blocks)
            ),
            spec.outputRoot,
            spec.dblpSnapshot,
            _
          )
        )
      )
    )
    val exportInputs: List[(ExportEntity[?], Path)] =
      List(
        ExportEntity.Work -> dblp.extract.dir(DblpExtractJob.Works),
        ExportEntity.VenueE -> dblp.extract.dir(DblpExtractJob.Venues),
        ExportEntity.Mention -> dblp.teams.dir(PublicationTeamJob.AuthorPapers),
        ExportEntity.Team -> dblp.teams.dir(PublicationTeamJob.Teams),
        ExportEntity.Pair -> dblp.consolidate.dir(PairConsolidateJob.Pairs),
        ExportEntity.Link -> join.dir(ExactIdJoinJob.Links),
        // one directory per block family: candidates/family=<family>/
        ExportEntity.Candidate -> new Path(
          candidates.dir(CandidateFeatureJob.Candidates),
          "family=*"
        )
      ) ++ normalize.flatMap(n =>
        List(
          ExportEntity.Inst -> n.dir(NormalizeJob.Institutions),
          ExportEntity.TopicE -> n.dir(NormalizeJob.Topics)
        )
      )
    val existing = exportInputs.filter { case (_, p) =>
      Option(p.getFileSystem(conf).globStatus(p)).exists(_.nonEmpty)
    }
    val graphExport = step(GraphExportJob.JobName)(
      JobRunner.run(
        conf,
        JobRunner.fromRuns(
          List(
            dblp.extract.runId,
            dblp.teams.runId,
            dblp.consolidate.runId,
            join.runId,
            candidates.runId
          ) ++ normalize.map(_.runId)
        )(GraphExportJob.spec(existing, spec.outputRoot, scope, label, _))
      )
    )
    PipelineResult(
      dblp,
      normalize,
      join,
      blocking,
      fallbackBlocking,
      fallbackCoverage,
      candidates,
      graphExport
    )
  }
}
