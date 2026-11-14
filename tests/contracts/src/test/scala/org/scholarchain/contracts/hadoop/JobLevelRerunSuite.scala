package org.scholarchain.contracts.hadoop

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, StandardOpenOption}

import org.apache.hadoop.fs.Path
import org.scholarchain.contracts.hadoop.JobHarness.*
import org.scholarchain.hadoop.common.{JobFailed, JobRunner, Settings, SettingsError}
import org.scholarchain.hadoop.dblp.DblpExtractJob

/** Job-level idempotent-rerun tests: what makes `JobRunner` reuse a completed output directory.
  *
  * Reuse is keyed on the run ID `RunId.of(jobName, scholarchain.code.version, inputFingerprint,
  * configFingerprint)`. The code version is a caller-supplied configuration string, not derived
  * from the built code, and a completed directory is reused without checking its contents. The last
  * two tests document that observable behaviour (see the test-engineer report, finding on
  * code-version reuse).
  */
class JobLevelRerunSuite extends munit.FunSuite {

  override val munitTimeout = scala.concurrent.duration.Duration(600, "s")

  private val Snapshot = "rerun-2026-11-13"

  private lazy val work = workDir("rerun")
  private lazy val input = write(
    work.resolve("in"),
    "small.xml",
    corpus(
      List(
        JobHarness.record("conf/sigir/JobRerunA20", List("Synthetic One", "Synthetic Two")),
        JobHarness.record("conf/kdd/JobRerunB21", List("Synthetic Two", "Synthetic Three"), 2021)
      )
    )
  )

  test("same inputs and configuration: every job is reused, same run IDs, files untouched") {
    val out = work.resolve("out-same")
    val c = conf(work)
    val first = chain(c, input, out, Snapshot)
    assert(first.all.forall(!_.reused))
    val partsBefore = first.all.map(r => partFiles(r.outputDir).view.mapValues(_.toList).toMap)
    val mtimes = first.all.map(r => Files.getLastModifiedTime(local(r.dir("_job.json"))))
    val second = chain(conf(work), input, out, Snapshot)
    assert(second.all.forall(_.reused), second.all.map(r => r.jobName -> r.reused).toString)
    assertEquals(second.all.map(_.runId), first.all.map(_.runId))
    assertEquals(second.all.map(_.counters), first.all.map(_.counters))
    assertEquals(
      second.all.map(r => partFiles(r.outputDir).view.mapValues(_.toList).toMap),
      partsBefore
    )
    assertEquals(
      second.all.map(r => Files.getLastModifiedTime(local(r.dir("_job.json")))),
      mtimes
    )
  }

  test("a changed threshold gives new run IDs and new directories; old outputs stay") {
    val out = work.resolve("out-config")
    val base = chain(conf(work), input, out, Snapshot)
    val changed = chain(conf(work, Settings.Keys.TeamOversize -> "2"), input, out, Snapshot)
    assert(changed.all.forall(!_.reused))
    base.all.zip(changed.all).foreach { case (x, y) =>
      assertNotEquals(x.runId, y.runId, x.jobName)
      assertNotEquals(x.outputDir, y.outputDir, x.jobName)
      assert(Files.exists(local(x.dir("_SUCCESS"))), s"${x.jobName}: old output removed")
    }
    // The configuration fingerprint covers every scholarchain.* key, so a J07-only threshold also
    // changes the run ID of J01, which does not read it.
    assertNotEquals(base.extract.runId, changed.extract.runId)
    assertEquals(changed.teams.main("OVERSIZE_AUTHOR_TEAMS"), 0L) // teams of 2 authors, 2 > 2 false
    val changed1 = chain(conf(work, Settings.Keys.TeamOversize -> "1"), input, out, Snapshot)
    assertEquals(changed1.teams.main("OVERSIZE_AUTHOR_TEAMS"), 2L)
  }

  test("scholarchain.code.version participates in the run ID and is recorded in _job.json") {
    val out = work.resolve("out-code")
    val v1 = chain(conf(work), input, out, Snapshot)
    val v2 = chain(conf(work, Settings.Keys.CodeVersion -> "other-build"), input, out, Snapshot)
    assert(v2.all.forall(!_.reused))
    v1.all.zip(v2.all).foreach { case (x, y) => assertNotEquals(x.runId, y.runId, x.jobName) }
    val rec = ujson.read(Files.readString(local(v2.extract.dir("_job.json"))))
    assertEquals(rec("codeVersion").str, "other-build")
  }

  test("scholarchain.code.version has no default: a run without it fails before any output") {
    val out = work.resolve("out-nocode")
    val c = conf(work)
    c.unset(Settings.Keys.CodeVersion)
    val e = intercept[SettingsError](chain(c, input, out, Snapshot))
    assertEquals(e.key, Settings.Keys.CodeVersion)
    assert(!Files.exists(out.resolve("dblp")), "output written without a code version")
  }

  test("documented gap: same code.version string reuses a completed output without validation") {
    // Simulates output left by a different build that was run with the same code.version string:
    // the completed J01 works file is replaced, then the identical rerun reuses it as is.
    val out = work.resolve("out-stale")
    val first = chain(conf(work), input, out, Snapshot)
    val works = local(first.extract.dir(DblpExtractJob.Works))
    val part = Files
      .list(works)
      .filter(_.getFileName.toString.startsWith("part-r-"))
      .filter(Files.size(_) > 0)
      .findFirst()
      .get
    Files.write(
      part,
      "STALE OUTPUT FROM ANOTHER BUILD\n".getBytes(StandardCharsets.UTF_8),
      StandardOpenOption.TRUNCATE_EXISTING
    )
    val again = chain(conf(work), input, out, Snapshot)
    assert(again.extract.reused, "J01 was rerun")
    assertEquals(again.extract.runId, first.extract.runId)
    assertEquals(Files.readString(part), "STALE OUTPUT FROM ANOTHER BUILD\n")
    assert(again.all.forall(_.reused), "downstream jobs keyed on the reused run ID are reused too")
  }

  /** An incomplete J01 directory for the run ID that `c` gives; returns (spec, run ID, dir). */
  private def incomplete(c: org.apache.hadoop.conf.Configuration, outName: String) = {
    val out = work.resolve(outName)
    val fp = JobRunner.fingerprintFiles(c, List(hpath(input)))
    val rid = JobRunner.runId(Settings.withDefaults(c), DblpExtractJob.JobName, fp)
    val spec = DblpExtractJob.spec(hpath(input), hpath(out), Snapshot, fp)
    val dir = JobRunner.outputDir(spec.outputRoot, spec.scope, spec.jobName, rid)
    Files.createDirectories(local(dir))
    write(local(dir), "part-r-00000", "partial\n")
    (spec, rid, dir)
  }

  test("incomplete output directory with the default replaceIncomplete=false: run fails") {
    val c = conf(work)
    val (spec, _, dir) = incomplete(c, "out-incomplete-default")
    val e = intercept[JobFailed](JobRunner.run(c, spec))
    assert(e.getMessage.contains(Settings.Keys.ReplaceIncomplete), e.getMessage)
    assertEquals(Files.readString(local(new Path(dir, "part-r-00000"))), "partial\n")
  }

  test("incomplete output directory left by a run with replaceIncomplete=true is replaced") {
    val c = conf(work, Settings.Keys.ReplaceIncomplete -> "true")
    val (spec, rid, dir) = incomplete(c, "out-incomplete-true")
    val r = JobRunner.run(c, spec)
    assert(!r.reused)
    assertEquals(r.runId, rid)
    assertEquals(r.outputDir, dir)
    assert(!Files.exists(local(new Path(dir, "part-r-00000"))), "partial file survived")
    assert(Files.exists(local(new Path(dir, "_SUCCESS"))))
  }

  test(
    "recovery: setting replaceIncomplete=true replaces the incomplete directory of a failed run"
  ) {
    // Settings documentation: "When an output directory for the same run ID exists without a
    // completion record, fail (false) or replace it (true)." The operator recovers a failed
    // default run by rerunning it with replaceIncomplete=true.
    val (spec, rid, dir) = incomplete(conf(work), "out-incomplete-recover")
    val r = JobRunner.run(conf(work, Settings.Keys.ReplaceIncomplete -> "true"), spec)
    assertEquals(
      r.runId,
      rid,
      "replaceIncomplete is part of the configuration fingerprint, so the recovery run gets a " +
        "different run ID and the incomplete directory of the failed run is never replaced"
    )
    assertEquals(r.outputDir, dir)
    assert(Files.exists(local(new Path(dir, "_SUCCESS"))))
  }
}
