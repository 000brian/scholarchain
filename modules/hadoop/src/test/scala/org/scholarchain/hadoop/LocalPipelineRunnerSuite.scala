package org.scholarchain.hadoop

import java.nio.file.Files

import org.scholarchain.hadoop.common.Settings

/** The test-scope command-line runner used for the bounded local real run. */
class LocalPipelineRunnerSuite extends munit.FunSuite {

  override val munitTimeout = scala.concurrent.duration.Duration(900, "s")

  private val synthetic =
    TestSupport.repoRoot.resolve("datasets/samples/dblp/dblp-synthetic-edge-cases.xml").toString

  test("arguments: required options, -D in both forms, limits, and refusals") {
    val o = LocalPipelineRunner.parse(
      List(
        "--output",
        "/tmp/x/out",
        "--dblp",
        "/tmp/d.xml.gz",
        "--dblp-snapshot",
        "2026-11-01",
        "-D",
        "scholarchain.code.version=v1",
        "-Dscholarchain.blocking.maxBlockSize=200",
        "--max-total-minutes",
        "180"
      )
    )
    assertEquals(o.snapshot, "2026-11-01")
    assertEquals(
      o.settings,
      List(Settings.Keys.CodeVersion -> "v1", Settings.Keys.BlockingMaxBlock -> "200")
    )
    assertEquals((o.minFreeGb, o.maxJobMinutes, o.maxTotalMinutes), (8L, 120L, 180L))
    val base = List("--output", "/o", "--dblp", "/d", "--dblp-snapshot", "s")
    // code version is required, never defaulted
    intercept[IllegalArgumentException](LocalPipelineRunner.parse(base))
    val code = List("-D", "scholarchain.code.version=v")
    LocalPipelineRunner.parse(base ++ code)
    intercept[IllegalArgumentException](LocalPipelineRunner.parse(base.drop(2) ++ code))
    intercept[IllegalArgumentException](LocalPipelineRunner.parse(base ++ code ++ code))
    intercept[IllegalArgumentException](LocalPipelineRunner.parse(base ++ code ++ List("--x", "1")))
    intercept[IllegalArgumentException](
      LocalPipelineRunner.parse(base ++ code ++ List("--max-job-minutes", "-1"))
    )
    intercept[IllegalArgumentException](LocalPipelineRunner.parse(base ++ List("-D", "novalue")))
  }

  test("watchdog limits: per job and per run, 0 disables the run limit") {
    val min = 60000L
    assertEquals(LocalPipelineRunner.overLimit(10 * min, 0, Some("j" -> 0L), 120, 0), None)
    assert(LocalPipelineRunner.overLimit(121 * min, 0, Some("j" -> 0L), 120, 0).get.contains("j"))
    assertEquals(LocalPipelineRunner.overLimit(500 * min, 0, None, 120, 0), None)
    assert(LocalPipelineRunner.overLimit(181 * min, 0, None, 120, 180).isDefined)
    assertEquals(LocalPipelineRunner.overLimit(179 * min, 0, None, 120, 180), None)
  }

  test("effective settings: defaults plus -D (override wins), -D Hadoop keys, no per-run keys") {
    val conf = TestSupport.localConf(
      TestSupport.tempDir("runner-settings"),
      Settings.Keys.CodeVersion -> "v1",
      Settings.Keys.BlockingMaxBlock -> "3",
      "mapreduce.task.timeout" -> "60000",
      Settings.Keys.RunId -> "per-run"
    )
    val overrides = List(
      Settings.Keys.CodeVersion -> "v1",
      Settings.Keys.BlockingMaxBlock -> "3",
      "mapreduce.task.timeout" -> "60000"
    )
    val eff = LocalPipelineRunner.effectiveSettings(conf, overrides)
    val m = eff.toMap
    assertEquals(eff.map(_._1), eff.map(_._1).sorted)
    assertEquals(m(Settings.Keys.CodeVersion), "v1")
    assertEquals(m(Settings.Keys.BlockingMaxBlock), "3")
    // keys left at their default (scholarchain-hadoop-defaults.xml) are recorded too
    assertEquals(m(Settings.Keys.FallbackMaxBlock), "200")
    assertEquals(m(Settings.Keys.CandidateMaxPairsPerBlock), "125000")
    assertEquals(m("mapreduce.task.timeout"), "60000")
    // per-run keys and Hadoop keys not given with -D are left out
    assert(!m.keys.exists(_.startsWith(Settings.RunPrefix)))
    assert(!m.keys.exists(_.startsWith(Settings.PathPrefix)))
    assert(!m.contains("fs.defaultFS"))
    // the caller's configuration is not changed (defaults are added to a copy)
    assertEquals(Option(conf.get(Settings.Keys.FallbackMaxBlock)), None)
  }

  test("a full run on the synthetic sample writes the report; a non-empty output is refused") {
    val work = TestSupport.tempDir("runner")
    val out = work.resolve("attempt-01")
    val args = List(
      "--output",
      out.toString,
      "--dblp",
      synthetic,
      "--dblp-snapshot",
      "synthetic-2026-11-12",
      "-D",
      "scholarchain.code.version=runner-test",
      "-D",
      "scholarchain.blocking.keyFamilies=dblp-base-name,initials-surname",
      "-D",
      "scholarchain.blocking.maxBlockSize=3",
      "-D",
      "scholarchain.candidates.skipSameNumberedName=true",
      "--min-free-gb",
      "0"
    )
    val report = LocalPipelineRunner.run(LocalPipelineRunner.parse(args))
    assertEquals(report("status").str, "complete")
    val saved = ujson.read(Files.readString(out.resolve("run-report.json")))
    assertEquals(saved("status").str, "complete")
    val jobs = saved("jobs").arr.map(_("job").str).toList
    assertEquals(
      jobs,
      List(
        "hadoop.j01-dblp-extract",
        "hadoop.j07-publication-teams",
        "hadoop.j08a-pair-expand",
        "hadoop.j08b-pair-consolidate",
        "hadoop.j04-exact-id-join",
        "hadoop.j05-blocking",
        "hadoop.j05b-fallback-blocking",
        "hadoop.j05c-fallback-coverage",
        "hadoop.j06-candidate-features",
        "hadoop.j09-graph-export"
      )
    )
    assert(saved("jobs").arr.forall(_("wallSeconds").num >= 0))
    val s = saved("summary")
    assert(s("works").num > 0)
    assert(s("mentions").num > 0)
    // the 4 subset mentions of "Synthetic Author" (base name) and "s author" (initials) exceed 3
    assertEquals(s("blocking")("oversizeBlocksPerFamily")("dblp-base-name").num, 1.0)
    assertEquals(s("blocking")("oversizeBlocksPerFamily")("initials-surname").num, 1.0)
    assertEquals(s("blocking")("oversizeMembers").num, 8.0)
    assertEquals(s("fallback")("worstKeys").arr.size, 2)
    // both fallback size stats are surfaced; the emitted one never exceeds the fallback cap
    assert(s("fallback").obj.contains("largestFallbackBlock"))
    assert(s("fallback").obj.contains("largestEmittedFallbackBlock"))
    // G-19: settings holds effective values, so the fallback cap left at its default is recorded
    // (default 200 since Phase 14 G-17, scholarchain-hadoop-defaults.xml); overrides holds the -D
    assertEquals(saved("settings")(Settings.Keys.FallbackMaxBlock).str, "200")
    assertEquals(saved("settings")(Settings.Keys.BlockingMaxBlock).str, "3")
    assertEquals(saved("settings")(Settings.Keys.CandidateMaxPairsPerBlock).str, "125000")
    assertEquals(
      saved("overrides").obj.keySet.toSet,
      Set(
        Settings.Keys.CodeVersion,
        Settings.Keys.BlockingFamilies,
        Settings.Keys.BlockingMaxBlock,
        Settings.Keys.CandidateSkipSameNumberedName
      )
    )
    assertEquals(saved("overrides")(Settings.Keys.BlockingMaxBlock).str, "3")
    val emittedFallback = s("fallback")("largestEmittedFallbackBlock")
    if (!emittedFallback.isNull) {
      val cap = saved("settings")(Settings.Keys.FallbackMaxBlock).str.toDouble
      assert(emittedFallback("value").num <= cap)
      assert(emittedFallback("value").num <= s("fallback")("largestFallbackBlock")("value").num)
    }
    assertEquals(
      s("fallback")("coveredMembers").num + s("fallback")("uncoveredMembers").num,
      8.0
    )
    assertEquals(saved("settings")(Settings.Keys.CodeVersion).str, "runner-test")
    // every attempt needs a new, empty output root
    val again = intercept[IllegalArgumentException](
      LocalPipelineRunner.run(LocalPipelineRunner.parse(args))
    )
    assert(again.getMessage.contains("not empty"))
    // the disk guard stops the run before the first job
    val full = work.resolve("attempt-02")
    val stopped = intercept[LocalPipelineRunner.RunStopped](
      LocalPipelineRunner.run(
        LocalPipelineRunner.parse(
          args.map(a => if (a == out.toString) full.toString else a).dropRight(1) :+ "1000000"
        )
      )
    )
    assert(stopped.getMessage.contains("free space"))
    assertEquals(
      ujson.read(Files.readString(full.resolve("run-report.json")))("status").str,
      "stopped"
    )
    assert(!Files.exists(full.resolve("dblp")))
  }
}
