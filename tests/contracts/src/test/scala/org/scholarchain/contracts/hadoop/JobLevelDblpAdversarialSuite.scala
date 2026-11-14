package org.scholarchain.contracts.hadoop

import java.nio.file.Files

import org.scholarchain.contracts.hadoop.JobHarness.*
import org.scholarchain.core.{Contract, RunId, WorkRecord}
import org.scholarchain.hadoop.common.{Counters, Disposition, Reasons, TaskIO}
import org.scholarchain.hadoop.dblp.DblpExtractJob

/** Job-level black-box tests of J01 (and the chain behind it) on the adversarial corpus
  * `hadoop-adversarial/job-corpus.xml`: counters, versioned reject outputs, the J01 reducer's
  * duplicate and conflicting keys, run-addressed output paths, and byte-level determinism.
  */
class JobLevelDblpAdversarialSuite extends munit.FunSuite {

  override val munitTimeout = scala.concurrent.duration.Duration(600, "s")

  private val Snapshot = "adv-2026-11-13"

  private lazy val work = workDir("j01-adv")
  private lazy val input = copyFixture(work.resolve("in"), "job-corpus.xml")
  private lazy val c = conf(work)
  private lazy val a = chain(c, input, work.resolve("out-a"), Snapshot)
  private lazy val b = chain(c, input, work.resolve("out-b"), Snapshot)
  private lazy val a56 = blockingAndCandidates(c, a, work.resolve("out-a"), Snapshot)
  private lazy val b56 = blockingAndCandidates(c, b, work.resolve("out-b"), Snapshot)

  test("J01 counters: exact per-class counts and INPUT = ACCEPTED + EXCLUDED + REJECTED") {
    val j01 = a.extract
    assertEquals(j01.main(Counters.Input), 12L)
    assertEquals(j01.main(Counters.Accepted), 4L) // good x2, conflicting key x2
    assertEquals(j01.main(Counters.Excluded), 2L) // year 2010, www element
    assertEquals(j01.main(Counters.Rejected), 6L)
    assertEquals(identity(j01)._1, identity(j01)._2)
    assertEquals(j01.main(Counters.MissingKey), 1L)
    assertEquals(
      List(
        Reasons.MalformedXml,
        Reasons.UndefinedEntity,
        Reasons.MissingKey,
        Reasons.MissingTitle,
        Reasons.NoAuthors,
        Reasons.TruncatedRecord
      ).map(r => r -> j01.counter(Counters.RejectReasonGroup, r)).toMap,
      List(
        Reasons.MalformedXml,
        Reasons.UndefinedEntity,
        Reasons.MissingKey,
        Reasons.MissingTitle,
        Reasons.NoAuthors,
        Reasons.TruncatedRecord
      ).map(_ -> 1L).toMap
    )
    assertEquals(j01.counter(Counters.ExcludedReasonGroup, Reasons.ExYear), 1L)
    assertEquals(j01.counter(Counters.ExcludedReasonGroup, Reasons.ExElement), 1L)
  }

  test("every job of the chain plus J05/J06 keeps INPUT = ACCEPTED + EXCLUDED + REJECTED") {
    val (j05, j06) = a56
    (a.all ++ List(j05, j06)).foreach { r =>
      val (in, sum) = identity(r)
      assertEquals(in, sum, s"${r.jobName}: INPUT $in != ACCEPTED+EXCLUDED+REJECTED $sum")
    }
    // J01 rejects the 6 bad records; every downstream job reads only valid upstream output
    (a.all.tail ++ List(j05, j06)).foreach { r =>
      assertEquals(r.main(Counters.Rejected), 0L, s"${r.jobName} rejected upstream output")
    }
    assertEquals(a.teams.main(Counters.Input), 4L) // 1 work + 3 mentions
    assertEquals(a.expand.main(Counters.PairInstances), 3L)
    assertEquals(a.consolidate.main(Counters.EmittedPair), 3L)
  }

  test("malformed, truncated, undeclared-entity and missing-field records land in rejects/v1") {
    val rejects = dispositions(c, a.extract, TaskIO.RejectsDir)
    assert(
      Files.isDirectory(local(a.extract.dir("rejects/v1"))),
      "rejects/v1 directory missing"
    )
    val mapStage = rejects.filter(_.reason != Reasons.DuplicateKeyConflict)
    assertEquals(
      mapStage.map(d => d.reason -> d.sourceRecordId).toSet,
      Set(
        Reasons.MalformedXml -> Some("conf/sigir/JobMalformed22"),
        Reasons.UndefinedEntity -> Some("conf/sigir/JobEntity22"),
        Reasons.MissingKey -> None,
        Reasons.MissingTitle -> Some("conf/sigir/JobNoTitle22"),
        Reasons.NoAuthors -> Some("conf/sigir/JobNoAuthors22"),
        Reasons.TruncatedRecord -> Some("conf/sigir/JobTruncated23")
      )
    )
    rejects.foreach { d =>
      assertEquals(d.format, Disposition.Format)
      assertEquals(d.formatVersion, 1)
      assertEquals(d.disposition, Disposition.Rejected)
      assertEquals(d.jobName, DblpExtractJob.JobName)
      assertEquals(d.runId, a.extract.runId.value)
      assertEquals(d.datasetId, "dblp")
      assertEquals(d.snapshotId, Snapshot)
      assert(d.detail.nonEmpty, s"${d.reason}: empty detail")
      assert(d.inputSha256.exists(_.matches("[0-9a-f]{64}")), s"${d.reason}: no input hash")
    }
    mapStage.foreach { d =>
      assert(
        d.location.exists(l => l.contains("job-corpus.xml:") && l.split(':').last.toLong > 0),
        s"${d.reason}: location ${d.location}"
      )
    }
    assert(
      mapStage.find(_.reason == Reasons.UndefinedEntity).get.detail.contains("nosuchentity"),
      "undefined_entity detail does not name the entity"
    )
    val excluded = dispositions(c, a.extract, TaskIO.ExcludedDir)
    assertEquals(
      excluded.map(d => d.reason -> d.sourceRecordId).toSet,
      Set(
        Reasons.ExYear -> Some("conf/sigir/JobOld10"),
        Reasons.ExElement -> Some("homepages/00/0000")
      )
    )
  }

  test("J01 reducer: identical duplicate key collapses, conflicting key is rejected") {
    val j01 = a.extract
    assertEquals(j01.main(Counters.Duplicate), 1L)
    assertEquals(j01.main(Counters.Conflict), 2L)
    assertEquals(j01.counter(Counters.Group, Counters.RejectedReduce), 2L)
    assertEquals(j01.counter(Counters.RejectReasonGroup, Reasons.DuplicateKeyConflict), 2L)
    val works = lines(c, j01, DblpExtractJob.Works).map(l => Contract.decode[WorkRecord](l))
    assert(works.forall(_.isRight), s"undecodable work: $works")
    assertEquals(works.flatMap(_.toOption).map(_.dblpKey), List("conf/sigir/JobGood20"))
    assertEquals(lines(c, j01, DblpExtractJob.Mentions).size, 3)
    val conflicts = dispositions(c, j01, TaskIO.RejectsDir)
      .filter(_.reason == Reasons.DuplicateKeyConflict)
    assertEquals(conflicts.size, 2)
    assert(conflicts.forall(_.sourceRecordId.contains("conf/sigir/JobConflict21")))
    assertEquals(conflicts.flatMap(_.inputSha256).distinct.size, 2, "both versions kept")
  }

  test("output paths contain dataset, snapshot, job name and the run ID") {
    val (j05, j06) = a56
    (a.all ++ List(j05, j06)).foreach { r =>
      val hex = r.runId.value.stripPrefix(RunId.Prefix)
      val p = r.outputDir.toString
      assert(p.contains(s"/dblp/snapshot=$Snapshot/"), s"${r.jobName}: $p")
      assert(p.endsWith(s"/run=$hex"), s"${r.jobName}: $p")
      assert(p.contains(s"/${r.jobName.stripPrefix("hadoop.")}/"), s"${r.jobName}: $p")
      val rec = ujson.read(Files.readString(local(r.dir("_job.json"))))
      assertEquals(rec("runId").str, r.runId.value)
      assertEquals(rec("snapshotId").str, Snapshot)
      assertEquals(rec("codeVersion").str, CodeVersion)
    }
    assertEquals(a.all.map(_.runId).distinct.size, 4, "each job has its own run ID")
  }

  test("determinism: two output roots give identical run IDs and byte-identical part files") {
    val all_a = a.all ++ List(a56._1, a56._2)
    val all_b = b.all ++ List(b56._1, b56._2)
    assertEquals(all_a.map(_.runId), all_b.map(_.runId))
    assert(all_b.forall(!_.reused), "second root must be a fresh run, not a reuse")
    all_a.zip(all_b).foreach { case (x, y) =>
      val fx = partFiles(x.outputDir)
      val fy = partFiles(y.outputDir)
      // J06 has no input blocks here (no shared names); the hot-name suite covers it with data
      if (x.jobName != "hadoop.j06-candidate-features")
        assert(fx.nonEmpty, s"${x.jobName}: no part files")
      assertEquals(fx.keySet, fy.keySet, x.jobName)
      fx.foreach { case (rel, bytes) =>
        assert(java.util.Arrays.equals(bytes, fy(rel)), s"${x.jobName} $rel differs")
      }
      assertEquals(
        Files.readString(local(x.dir("_job.json"))),
        Files.readString(local(y.dir("_job.json"))),
        s"${x.jobName} _job.json"
      )
    }
  }
}
