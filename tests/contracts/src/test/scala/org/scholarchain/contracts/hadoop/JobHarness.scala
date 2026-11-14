package org.scholarchain.contracts.hadoop

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path as JPath}

import scala.jdk.CollectionConverters.*

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.scholarchain.hadoop.blocking.BlockingJob
import org.scholarchain.hadoop.candidates.CandidateFeatureJob
import org.scholarchain.hadoop.common.{Counters, Disposition, JobResult, JobRunner, Settings}
import org.scholarchain.hadoop.pipeline.{DblpChain, DblpChainResult}
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

  /** Runs J05 then J06 on the J07 mention contexts of `chain`. */
  def blockingAndCandidates(
      c: Configuration,
      ch: DblpChainResult,
      out: JPath,
      snapshot: String
  ): (JobResult, JobResult) = {
    val j05 = JobRunner.run(
      c,
      BlockingJob.spec(
        ch.teams.dir(PublicationTeamJob.Contexts),
        hpath(out),
        snapshot,
        JobRunner.fingerprintRuns(List(ch.teams.runId))
      )
    )
    val j06 = JobRunner.run(
      c,
      CandidateFeatureJob.spec(
        j05.dir(BlockingJob.Blocks),
        hpath(out),
        snapshot,
        JobRunner.fingerprintRuns(List(j05.runId))
      )
    )
    (j05, j06)
  }

  /** J05, J05b, J05c and J06 of one configuration (the candidate stage of the pipeline). */
  final case class CandidateStage(
      j05: JobResult,
      j05b: JobResult,
      j05c: JobResult,
      j06: JobResult
  ) {
    def all: List[JobResult] = List(j05, j05b, j05c, j06)
  }

  /** Runs J05 -> J05b -> J05c -> J06 on `contexts` (J07 `mentionContexts/` of run `teamsRun`) with
    * configuration `c`, wired as `Pipeline.run` wires them.
    */
  def candidateStage(
      c: Configuration,
      contexts: Path,
      teamsRun: org.scholarchain.core.RunId,
      out: JPath,
      snapshot: String
  ): CandidateStage = {
    import org.scholarchain.hadoop.blocking.{FallbackBlockingJob, FallbackCoverageJob}
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
    val j06 = JobRunner.run(
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
    CandidateStage(j05, j05b, j05c, j06)
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
