package org.scholarchain.contracts.hadoop

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path as JPath}

import scala.jdk.CollectionConverters.*

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.scholarchain.hadoop.blocking.{
  BlockLimit,
  BlockSizeJob,
  BlockingJob,
  FallbackBlockingJob,
  FallbackCoverageJob
}
import org.scholarchain.hadoop.candidates.CandidateFeatureJob
import org.scholarchain.hadoop.common.{Counters, Disposition, JobResult, JobRunner, Settings}
import org.scholarchain.hadoop.pipeline.{BlockingChain, DblpChain, DblpChainResult}
import org.scholarchain.hadoop.teams.PublicationTeamJob
import upickle.default.read

/** Job-level harness (test-engineer): runs the real hadoop module jobs through their public entry
  * points with Hadoop's local job runner on the local file system. No AWS, no network.
  *
  * Written independently of the hadoop module's own TestSupport. Work directories live under
  * `tests/contracts/target/job-work/` (build output, gitignored).
  */
object JobHarness {

  val CodeVersion = "test-engineer-job-level-1"

  def workDir(prefix: String): JPath = {
    val base = AdversarialSupport.repoRoot.resolve("tests/contracts/target/job-work")
    Files.createDirectories(base)
    Files.createTempDirectory(base, prefix)
  }

  def hpath(p: JPath): Path = new Path(p.toUri)

  /** A local-mode configuration with only Hadoop's built-in defaults, the code version, and
    * `overrides`. ScholarChain defaults are added by the job runner itself.
    */
  def conf(work: JPath, overrides: (String, String)*): Configuration = {
    val c = new Configuration(false)
    c.set("fs.defaultFS", "file:///")
    c.set("mapreduce.framework.name", "local")
    c.set("hadoop.tmp.dir", work.resolve("hadoop-tmp").toString)
    c.set("mapreduce.jobtracker.staging.root.dir", work.resolve("staging").toString)
    c.set("mapreduce.cluster.local.dir", work.resolve("local").toString)
    c.set("mapreduce.task.io.sort.mb", "16")
    c.set(Settings.Keys.CodeVersion, CodeVersion)
    overrides.foreach { case (k, v) => c.set(k, v) }
    c
  }

  def write(dir: JPath, name: String, content: String): JPath = {
    val f = dir.resolve(name)
    Files.createDirectories(f.getParent)
    Files.write(f, content.getBytes(StandardCharsets.UTF_8))
    f
  }

  /** Copies a fixture from `tests/contracts/src/test/resources/hadoop-adversarial/`. */
  def copyFixture(dir: JPath, name: String): JPath = {
    val f = dir.resolve(name)
    Files.createDirectories(f.getParent)
    Files.write(f, AdversarialSupport.fixture(name))
    f
  }

  val Prolog: String =
    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!DOCTYPE dblp [\n" +
      "<!ENTITY eacute \"&#233;\">\n]>\n<dblp>\n"

  /** One in-subset inproceedings record (SIGIR, year 2020 by default). */
  def record(key: String, authors: List[String], year: Int = 2020, title: String = "T"): String =
    s"""<inproceedings mdate="2026-11-13" key="$key">
       |${authors.map(a => s"<author>$a</author>").mkString("\n")}
       |<title>[SYNTHETIC] $title</title>
       |<year>$year</year>
       |<booktitle>SIGIR</booktitle>
       |</inproceedings>
       |""".stripMargin

  def corpus(records: List[String]): String = Prolog + records.mkString + "</dblp>\n"

  /** Runs J01 -> J07 -> J08a -> J08b. */
  def chain(c: Configuration, input: JPath, out: JPath, snapshot: String): DblpChainResult =
    DblpChain.run(c, hpath(input), hpath(out), snapshot)

  /** The effective member cap (member cap `capKey` with the pair limit) that `c` gives a blocking
    * job, computed from `c` and the built-in defaults (`DefaultsXml`), independently of the job.
    */
  def effectiveCap(c: Configuration, capKey: String): Int = {
    def v(k: String): String = Option(c.get(k)).getOrElse(DefaultsXml.values(k))
    BlockLimit(v(capKey).toInt, v(Settings.Keys.CandidateMaxPairsPerBlock).toLong).effective
  }

  /** Phase 17 R3 (HADOOP-04): the counting pre-pass of `blocking` worked. No oversize group reached
    * one reduce call (REDUCER_CAPPED_BLOCKS 0), every re-keyed member value was streamed out as an
    * oversize member, and the pre-pass listed exactly the oversize blocks the job counted.
    */
  def assertPrePassEffective(prePass: JobResult, blocking: JobResult, clue: String): Unit = {
    def eq(a: Long, b: Long, what: String): Unit =
      if (a != b) throw new AssertionError(s"$clue ${blocking.jobName}: $what: $a != $b")
    eq(blocking.main(Counters.ReducerCappedBlocks), 0L, "REDUCER_CAPPED_BLOCKS")
    eq(prePass.main(Counters.OversizeKeysFound), blocking.main(Counters.OversizeBlock), "keys")
  }

  /** Runs J05p -> J05 (with the pre-pass) on the J07 mention contexts of `ch`; returns both. */
  def primaryBlocking(
      c: Configuration,
      ch: DblpChainResult,
      out: JPath,
      snapshot: String
  ): (JobResult, JobResult) = {
    val root = hpath(out)
    val contexts = ch.teams.dir(PublicationTeamJob.Contexts)
    val j05p = JobRunner.run(
      c,
      JobRunner.fromRuns(List(ch.teams.runId))(BlockSizeJob.spec(contexts, root, snapshot, _))
    )
    val j05 = JobRunner.run(
      c,
      JobRunner.fromRuns(List(ch.teams.runId, j05p.runId))(
        BlockingJob.spec(contexts, root, snapshot, _, Some(j05p.dir(BlockSizeJob.OversizeKeys)))
      )
    )
    assertPrePassEffective(j05p, j05, "primaryBlocking")
    if (j05.main(Counters.OversizeMembersRekeyed) != j05.main(Counters.OversizeMembers))
      throw new AssertionError(s"J05: re-keyed values != oversize members: ${j05.counters}")
    (j05p, j05)
  }

  /** Runs J05p -> J05 (with the counting pre-pass, as `BlockingChain` wires it) then J06 on the J07
    * mention contexts of `chain`.
    */
  def blockingAndCandidates(
      c: Configuration,
      ch: DblpChainResult,
      out: JPath,
      snapshot: String
  ): (JobResult, JobResult) = {
    val (_, j05) = primaryBlocking(c, ch, out, snapshot)
    val j06 = JobRunner.run(
      c,
      JobRunner.fromRuns(List(j05.runId))(
        CandidateFeatureJob.spec(j05.dir(BlockingJob.Blocks), hpath(out), snapshot, _)
      )
    )
    (j05, j06)
  }

  /** J05p, J05, J05bp, J05b, J05c and J06 of one configuration (the candidate stage of the
    * pipeline).
    */
  final case class CandidateStage(
      j05p: JobResult,
      j05: JobResult,
      j05bp: JobResult,
      j05b: JobResult,
      j05c: JobResult,
      j06: JobResult
  ) {
    def all: List[JobResult] = List(j05p, j05, j05bp, j05b, j05c, j06)
  }

  /** J06 over the primary and fallback blocks of `j05` and `j05b`. */
  private def candidates(
      c: Configuration,
      j05: JobResult,
      j05b: JobResult,
      root: Path,
      snapshot: String
  ): JobResult =
    JobRunner.run(
      c,
      JobRunner.fromRuns(List(j05.runId, j05b.runId))(
        CandidateFeatureJob.spec(
          List(j05.dir(BlockingJob.Blocks), j05b.dir(FallbackBlockingJob.Blocks)),
          root,
          snapshot,
          _
        )
      )
    )

  /** Runs the blocking chain (`BlockingChain.run`: J05p -> J05 -> J05bp -> J05b -> J05c, the
    * production wiring of `Pipeline.run`) and then J06 on `contexts` (J07 `mentionContexts/` of run
    * `teamsRun`) with configuration `c`. Checks [[assertPrePassEffective]] for J05 and J05b.
    */
  def candidateStage(
      c: Configuration,
      contexts: Path,
      teamsRun: org.scholarchain.core.RunId,
      out: JPath,
      snapshot: String
  ): CandidateStage = {
    val root = hpath(out)
    val b = BlockingChain.run(c, contexts, teamsRun, root, snapshot)
    assertPrePassEffective(b.blockSizes, b.blocking, "candidateStage")
    assertPrePassEffective(b.fallbackBlockSizes, b.fallbackBlocking, "candidateStage")
    val j06 = candidates(c, b.blocking, b.fallbackBlocking, root, snapshot)
    CandidateStage(
      b.blockSizes,
      b.blocking,
      b.fallbackBlockSizes,
      b.fallbackBlocking,
      b.fallbackCoverage,
      j06
    )
  }

  /** J05, J05b, J05c and J06 through the 4-argument specs, WITHOUT the counting pre-passes (the
    * reducer-side cap only, the code path before Phase 17 R3). Used only to show that a test fails
    * on the old path.
    */
  final case class LegacyStage(j05: JobResult, j05b: JobResult, j05c: JobResult, j06: JobResult)

  def legacyCandidateStage(
      c: Configuration,
      contexts: Path,
      teamsRun: org.scholarchain.core.RunId,
      out: JPath,
      snapshot: String
  ): LegacyStage = {
    val root = hpath(out)
    val j05 = JobRunner.run(
      c,
      JobRunner.fromRuns(List(teamsRun))(BlockingJob.spec(contexts, root, snapshot, _))
    )
    val j05b = JobRunner.run(
      c,
      JobRunner.fromRuns(List(j05.runId))(
        FallbackBlockingJob.spec(j05.dir(BlockingJob.OversizeMembers), root, snapshot, _)
      )
    )
    val j05c = JobRunner.run(
      c,
      JobRunner.fromRuns(List(j05.runId, j05b.runId))(
        FallbackCoverageJob.spec(
          j05.dir(BlockingJob.OversizeMembers),
          j05b.dir(FallbackBlockingJob.Coverage),
          root,
          snapshot,
          _
        )
      )
    )
    LegacyStage(j05, j05b, j05c, candidates(c, j05, j05b, root, snapshot))
  }

  /** All lines of `part-*` files directly under `<job output>/<sub>`. */
  def lines(c: Configuration, r: JobResult, sub: String): List[String] =
    JobRunner.readPartLines(c, r.dir(sub))

  def dispositions(c: Configuration, r: JobResult, sub: String): List[Disposition] =
    lines(c, r, sub).map(read[Disposition](_))

  def local(p: Path): JPath = java.nio.file.Paths.get(p.toUri)

  /** Relative path -> bytes of every `part-*` file below `dir` (recursive). */
  def partFiles(dir: Path): Map[String, Array[Byte]] = {
    val root = local(dir)
    Files
      .walk(root)
      .iterator()
      .asScala
      .filter(p => Files.isRegularFile(p) && p.getFileName.toString.startsWith("part-"))
      .map(p => root.relativize(p).toString -> Files.readAllBytes(p))
      .toMap
  }

  /** Non-empty `part-*` files directly under `<job output>/<sub>`. */
  def nonEmptyParts(r: JobResult, sub: String): List[String] = {
    val d = local(r.dir(sub))
    if (!Files.isDirectory(d)) Nil
    else
      Files
        .list(d)
        .iterator()
        .asScala
        .filter(p => p.getFileName.toString.startsWith("part-") && Files.size(p) > 0)
        .map(_.getFileName.toString)
        .toList
        .sorted
  }

  /** The map-stage accounting identity: (input, accepted + excluded + rejected). */
  def identity(r: JobResult): (Long, Long) =
    (
      r.main(Counters.Input),
      r.main(Counters.Accepted) + r.main(Counters.Excluded) + r.main(Counters.Rejected)
    )
}
