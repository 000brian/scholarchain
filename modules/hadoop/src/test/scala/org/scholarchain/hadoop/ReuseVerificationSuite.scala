package org.scholarchain.hadoop

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path as JPath, Paths}

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.scholarchain.hadoop.common.{JobFailed, JobResult, JobRunner, JobSpec, Settings}
import org.scholarchain.hadoop.dblp.DblpExtractJob

/** Phase 11 (Phase 4 finding 2): a completed run directory is reused only when its `_job.json`
  * names the current run (code version and inputs included) and its files are exactly the recorded
  * `outputs` (path, length, sha256). Anything else fails the run and leaves the directory as it is.
  */
class ReuseVerificationSuite extends munit.FunSuite {

  override val munitTimeout = scala.concurrent.duration.Duration(300, "s")

  private val input = TestSupport.sample("dblp/dblp-sample.xml")

  /** A fresh, completed J01 run in its own work directory. */
  private def completed(name: String): (Configuration, JobSpec, JobResult) = {
    val work = TestSupport.tempDir(name)
    val conf = TestSupport.localConf(work)
    val fp = JobRunner.fingerprintFiles(conf, List(input))
    val spec = DblpExtractJob.spec(input, new Path(work.resolve("out").toUri), "2026-11-01", fp)
    val r = JobRunner.run(conf, spec)
    assert(!r.reused)
    (conf, spec, r)
  }

  private def local(p: Path): JPath = Paths.get(p.toUri)

  /** Replaces a file's bytes and drops its Hadoop checksum, so the bytes are read as they are. */
  private def overwrite(p: JPath, bytes: Array[Byte]): Unit = {
    Files.write(p, bytes)
    Files.deleteIfExists(p.resolveSibling(s".${p.getFileName}.crc"))
  }

  private def record(r: JobResult): ujson.Value =
    ujson.read(Files.readString(local(r.dir(JobRunner.JobRecord))))

  private def rewriteRecord(r: JobResult)(f: ujson.Obj => Unit): Unit = {
    val rec = record(r).obj
    val o = ujson.Obj.from(rec.toList)
    f(o)
    overwrite(
      local(r.dir(JobRunner.JobRecord)),
      o.render(indent = 2).getBytes(StandardCharsets.UTF_8)
    )
  }

  private def firstPart(r: JobResult): JPath = {
    val dir = local(r.dir(DblpExtractJob.Works))
    Files
      .list(dir)
      .filter(p => p.getFileName.toString.startsWith("part-r-") && Files.size(p) > 0)
      .findFirst()
      .get
  }

  private def refused(conf: Configuration, spec: JobSpec, reason: String): Unit = {
    val e = intercept[JobFailed](JobRunner.run(conf, spec))
    assert(e.getMessage.contains("refusing to reuse"), e.getMessage)
    assert(e.getMessage.contains(reason), e.getMessage)
  }

  test("_job.json records every non-hidden output with relative path, length and sha256") {
    val (conf, spec, r) = completed("reuse-manifest")
    val outputs = record(r)(JobRunner.OutputsField).arr.toList
    val fs = r.outputDir.getFileSystem(conf)
    val manifest = JobRunner.outputManifest(fs, r.outputDir)
    assert(manifest.nonEmpty)
    assertEquals(
      outputs.map(o => (o("path").str, o("length").num.toLong, o("sha256").str)),
      manifest.map(f => (f.path, f.length, f.sha256))
    )
    assert(
      manifest.exists(_.path.startsWith(s"${DblpExtractJob.Works}/part-r-")),
      manifest.toString
    )
    assert(
      manifest.forall(f => !f.path.startsWith("/") && !f.path.contains("/_")),
      manifest.toString
    )
    assert(!manifest.exists(_.path.startsWith("_")), "hidden files are not listed")
    val bytes = Files.readAllBytes(firstPart(r))
    val rel = s"${DblpExtractJob.Works}/${firstPart(r).getFileName}"
    val hex = java.security.MessageDigest
      .getInstance("SHA-256")
      .digest(bytes)
      .map(b => f"${b & 0xff}%02x")
      .mkString
    assertEquals(
      manifest.find(_.path == rel).map(f => (f.length, f.sha256)),
      Some((bytes.length.toLong, hex))
    )
    // untouched: reused, same run ID and counters
    val again = JobRunner.run(conf, spec)
    assert(again.reused)
    assertEquals(again.runId, r.runId)
    assertEquals(again.counters, r.counters)
  }

  test("a part file changed after completion (other length) is refused and left in place") {
    val (conf, spec, r) = completed("reuse-length")
    val part = firstPart(r)
    overwrite(part, "STALE OUTPUT FROM ANOTHER BUILD\n".getBytes(StandardCharsets.UTF_8))
    refused(conf, spec, "has length")
    assertEquals(Files.readString(part), "STALE OUTPUT FROM ANOTHER BUILD\n", "nothing is replaced")
    assert(Files.exists(local(r.dir("_SUCCESS"))))
    // replaceIncomplete does not apply to a completed directory that fails the check
    // (an operational key: same run ID, same directory)
    val replacing = new Configuration(conf)
    replacing.set(Settings.Keys.ReplaceIncomplete, "true")
    refused(replacing, spec, "has length")
    assertEquals(Files.readString(part), "STALE OUTPUT FROM ANOTHER BUILD\n", "nothing is replaced")
  }

  test("a part file with the same length but other bytes is refused (sha256)") {
    val (conf, spec, r) = completed("reuse-sha")
    val part = firstPart(r)
    val bytes = Files.readAllBytes(part)
    bytes(0) = (if (bytes(0) == 'X'.toByte) 'Y' else 'X').toByte
    overwrite(part, bytes)
    refused(conf, spec, "sha256 differs")
  }

  test("an unrecorded extra file and a missing recorded file are both refused") {
    val (conf, spec, r) = completed("reuse-extra")
    val extra = local(r.dir(DblpExtractJob.Works)).resolve("part-r-99999")
    Files.write(extra, "extra\n".getBytes(StandardCharsets.UTF_8))
    refused(conf, spec, "is not recorded")

    val (conf2, spec2, r2) = completed("reuse-missing")
    val part = firstPart(r2)
    Files.delete(part)
    Files.deleteIfExists(part.resolveSibling(s".${part.getFileName}.crc"))
    refused(conf2, spec2, "is missing")
  }

  test("a record naming another code version or other inputs is refused") {
    val (conf, spec, r) = completed("reuse-code")
    rewriteRecord(r)(_("codeVersion") = "other-build")
    refused(conf, spec, "codeVersion")

    val (conf2, spec2, r2) = completed("reuse-input")
    rewriteRecord(r2)(_("inputFingerprint") = "sha256:other-input")
    refused(conf2, spec2, "inputFingerprint")

    val (conf3, spec3, r3) = completed("reuse-config")
    rewriteRecord(r3)(_("configFingerprint") = "sha256:other-config")
    refused(conf3, spec3, "configFingerprint")
  }

  test(
    "a record without the outputs list (pre-Phase-11) or with an identity field missing is refused"
  ) {
    val (conf, spec, r) = completed("reuse-old")
    rewriteRecord(r)(_.value.remove(JobRunner.OutputsField))
    refused(conf, spec, s"no ${JobRunner.OutputsField}")

    val (conf2, spec2, r2) = completed("reuse-noinput")
    rewriteRecord(r2)(_.value.remove("inputRuns"))
    refused(conf2, spec2, "no inputRuns")

    val (conf3, spec3, r3) = completed("reuse-garbage")
    overwrite(local(r3.dir(JobRunner.JobRecord)), "not json".getBytes(StandardCharsets.UTF_8))
    refused(conf3, spec3, "not valid JSON")
  }
}
