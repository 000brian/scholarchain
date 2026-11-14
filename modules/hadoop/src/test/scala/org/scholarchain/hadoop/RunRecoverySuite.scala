package org.scholarchain.hadoop

import java.nio.file.Files

import org.apache.hadoop.fs.Path
import org.scholarchain.hadoop.common.{JobFailed, JobRunner, Settings}
import org.scholarchain.hadoop.dblp.DblpExtractJob

/** Recovery of a failed run: the incomplete directory left by a default-configuration run is
  * replaced by a rerun with `scholarchain.output.replaceIncomplete=true` under the same run ID.
  */
class RunRecoverySuite extends munit.FunSuite {

  override val munitTimeout = scala.concurrent.duration.Duration(300, "s")

  test("replaceIncomplete=true keeps the run ID and replaces the failed run's directory") {
    val work = TestSupport.tempDir("recovery")
    val input = TestSupport.sample("dblp/dblp-sample.xml")
    val out = new Path(work.resolve("out").toUri)
    val default = TestSupport.localConf(work)
    val fp = JobRunner.fingerprintFiles(default, List(input))
    val spec = DblpExtractJob.spec(input, out, "2026-11-01", fp)

    // A failed default run: its run directory exists without _SUCCESS and _job.json.
    val rid = JobRunner.runId(Settings.withDefaults(default), spec.jobName, fp)
    val dir = JobRunner.outputDir(out, spec.scope, spec.jobName, rid)
    val partial = java.nio.file.Paths.get(new Path(dir, "works/part-r-00000").toUri)
    Files.createDirectories(partial.getParent)
    Files.write(partial, "partial".getBytes("UTF-8"))
    val failed = intercept[JobFailed](JobRunner.run(default, spec))
    assert(failed.getMessage.contains(Settings.Keys.ReplaceIncomplete), failed.getMessage)

    // Recovery: same run ID, same directory, partial output replaced by a complete one.
    val recover = TestSupport.localConf(work, Settings.Keys.ReplaceIncomplete -> "true")
    val r = JobRunner.run(recover, spec)
    assert(!r.reused)
    assertEquals(r.runId, rid)
    assertEquals(r.outputDir, dir)
    assert(Files.exists(java.nio.file.Paths.get(new Path(dir, "_SUCCESS").toUri)))
    assert(Files.exists(java.nio.file.Paths.get(new Path(dir, JobRunner.JobRecord).toUri)))
    assert(!TestSupport.lines(recover, r, "works").contains("partial"))
    assertEquals(TestSupport.lines(recover, r, "works").size, 4)

    // Later default-configuration reruns reuse the recovered output instead of failing.
    val again = JobRunner.run(default, spec)
    assert(again.reused)
    assertEquals(again.runId, rid)
  }
}
