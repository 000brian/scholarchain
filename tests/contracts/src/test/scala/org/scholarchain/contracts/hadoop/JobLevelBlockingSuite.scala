package org.scholarchain.contracts.hadoop

import java.text.Normalizer

import org.apache.hadoop.conf.Configuration
import org.scholarchain.contracts.hadoop.JobHarness.*
import org.scholarchain.core.{MatchCandidate, MentionId, WorkId}
import org.scholarchain.hadoop.blocking.{BlockingJob, CandidateBlock}
import org.scholarchain.hadoop.candidates.{CandidateFeatureJob, CandidateFeatures}
import org.scholarchain.hadoop.common.{Counters, JobResult, Settings}
import upickle.default.read

/** Job-level hot common-name tests, end to end J01 -> J07 -> J05 -> J06.
  *
  * Twelve works share one blocking key: ten carry `José Hot` precomposed (U+00E9), one carries it
  * decomposed (`e` + U+0301), one carries the homonym `José Hot 0001`. Each work has a unique
  * partner author (twelve singleton blocks). The hot block has 12 members and 66 member pairs.
  */
class JobLevelBlockingSuite extends munit.FunSuite {

  override val munitTimeout = scala.concurrent.duration.Duration(600, "s")

  private val Snapshot = "hot-2026-11-13"
  private val Members = 12
  private val PairCount = Members.toLong * (Members - 1) / 2 // 66
  private val HotKey = Normalizer.normalize("José Hot", Normalizer.Form.NFD)
  private val Family = "dblp-base-name"

  private def key(i: Int) = f"conf/sigir/JobHot$i%02d"

  private lazy val work = workDir("j05-hot")
  private lazy val input = write(
    work.resolve("in"),
    "hot.xml",
    corpus((0 until Members).toList.map { i =>
      val hot =
        if (i == 10) "Jose&#769; Hot"
        else if (i == 11) "Jos&#233; Hot 0001"
        else "Jos&#233; Hot"
      record(key(i), List(hot, f"Synthetic Partner $i%02d"), year = 2015 + i % 10)
    })
  )

  private def run(
      out: String,
      overrides: (String, String)*
  ): (JobResult, JobResult, Configuration) = {
    val c = conf(work, overrides*)
    val ch = chain(c, input, work.resolve(out), Snapshot)
    assertEquals(ch.extract.main(Counters.Accepted), Members.toLong)
    val (j05, j06) = blockingAndCandidates(c, ch, work.resolve(out), Snapshot)
    (j05, j06, c)
  }

  private def candidates(c: Configuration, j06: JobResult): List[MatchCandidate] =
    lines(c, j06, CandidateFeatureJob.candidatesDir(Family)).map(read[MatchCandidate](_))

  private lazy val default = run("out-default")

  test(
    "default limits: one hot block of 12 members (NFC, NFD and homonym share it), 66 candidates"
  ) {
    val (j05, j06, c) = default
    assertEquals(j05.main(Counters.BlocksEmitted), 1L)
    assertEquals(j05.main(Counters.SingletonBlocks), Members.toLong) // the partners
    assertEquals(j05.main(Counters.OversizeBlock), 0L)
    assertEquals(j05.stats("largestBlock").value, Members.toLong)
    val blocks = lines(c, j05, BlockingJob.Blocks).map(read[CandidateBlock](_))
    assertEquals(blocks.size, 1)
    assertEquals(blocks.head.blockKey, HotKey)
    assertEquals(blocks.head.size, Members)
    assertEquals(
      blocks.head.members.map(_.dblpKey).sorted,
      (0 until Members).map(key).toList.sorted
    )

    assertEquals(j06.main(Counters.CandidateShards), 1L)
    assertEquals(j06.main(Counters.CandidatesEmitted), PairCount)
    assertEquals(j06.stats("largestCandidateGroup").value, PairCount)
    val cs = candidates(c, j06)
    assertEquals(cs.size.toLong, PairCount)
    assertEquals(cs.map(_.candidateId).distinct.size.toLong, PairCount)
    assertEquals(cs.map(x => Set(x.left, x.right)).distinct.size.toLong, PairCount)
    val m10 = MentionId.of(WorkId.fromDblpKey(key(10)), 0).value
    val m0 = MentionId.of(WorkId.fromDblpKey(key(0)), 0).value
    val m11 = MentionId.of(WorkId.fromDblpKey(key(11)), 0).value
    def feature(a: String, b: String, name: String): Option[Double] =
      cs.find(x => Set(x.left, x.right) == Set(a, b)).get.features.find(_.name == name).map(_.value)
    assertEquals(feature(m0, m10, CandidateFeatures.NameExactMatch), Some(1.0), "NFC vs NFD")
    assertEquals(feature(m0, m11, CandidateFeatures.NameExactMatch), Some(0.0), "homonym suffix")
    cs.foreach { x =>
      assertEquals(x.blockKey, HotKey)
      assertEquals(x.provenance.snapshotId, Snapshot)
      assertEquals(x.provenance.runId, j06.runId)
    }
    assertEquals(identity(j05)._1, identity(j05)._2)
    assertEquals(identity(j06)._1, identity(j06)._2)
  }

  test("J05 maxBlockSize below the hot block: oversize, listed, not emitted, no candidates") {
    val (j05, j06, c) = run("out-max11", Settings.Keys.BlockingMaxBlock -> "11")
    assertEquals(j05.main(Counters.OversizeBlock), 1L)
    assertEquals(j05.main(Counters.BlocksEmitted), 0L)
    assertEquals(lines(c, j05, BlockingJob.Blocks), Nil)
    val listed = lines(c, j05, BlockingJob.OversizeBlocks).map(ujson.read(_))
    assertEquals(listed.size, 1)
    assertEquals(listed.head("blockKey").str, HotKey)
    assertEquals(listed.head("size").num.toLong, Members.toLong)
    assertEquals(listed.head("limit").num.toLong, 11L)
    assertEquals(j05.stats("largestBlock").value, Members.toLong)
    assertEquals(j06.main(Counters.Input), 0L)
    assertEquals(j06.main(Counters.CandidatesEmitted), 0L)
    assertNotEquals(j05.runId, default._1.runId)
  }

  test("J05 maxBlockSize equal to the hot block size emits it (boundary)") {
    val (j05, j06, _) = run("out-max12", Settings.Keys.BlockingMaxBlock -> "12")
    assertEquals(j05.main(Counters.OversizeBlock), 0L)
    assertEquals(j05.main(Counters.BlocksEmitted), 1L)
    assertEquals(j06.main(Counters.CandidatesEmitted), PairCount)
  }

  test("J06 maxPairsPerBlock below 66: block not expanded, pairs counted as skipped") {
    val (j05, j06, c) = run("out-pairs65", Settings.Keys.CandidateMaxPairsPerBlock -> "65")
    assertEquals(j05.main(Counters.BlocksEmitted), 1L)
    assertEquals(j06.main(Counters.OversizeBlock), 1L)
    assertEquals(j06.main(Counters.OversizeBlockPairsSkipped), PairCount)
    assertEquals(j06.main(Counters.CandidatesEmitted), 0L)
    assertEquals(candidates(c, j06), Nil)
    val listed = lines(c, j06, CandidateFeatureJob.OversizeBlocks).map(ujson.read(_))
    assertEquals(listed.map(_("pairs").num.toLong), List(PairCount))
    assertEquals(listed.map(_("limit").num.toLong), List(65L))
    assertEquals(j06.stats("largestBlockSeen").value, Members.toLong)

    val (_, j06ok, _) = run("out-pairs66", Settings.Keys.CandidateMaxPairsPerBlock -> "66")
    assertEquals(j06ok.main(Counters.OversizeBlock), 0L)
    assertEquals(j06ok.main(Counters.CandidatesEmitted), PairCount)
  }

  test("J06 pairsPerShard 10: 7 shards over several reducers, same candidates as one shard") {
    val (_, j06, c) = run(
      "out-shard10",
      Settings.Keys.CandidatePairsPerShard -> "10",
      Settings.Keys.CandidateReducers -> "4"
    )
    assertEquals(j06.main(Counters.CandidateShards), 7L)
    assertEquals(j06.main(Counters.CandidatesEmitted), PairCount)
    val dir = CandidateFeatureJob.candidatesDir(Family)
    assert(
      nonEmptyParts(j06, dir).count(_.startsWith("part-r-")) > 1,
      s"shards not spread: ${nonEmptyParts(j06, dir)}"
    )
    val sharded = candidates(c, j06)
    val (_, j06d, cd) = default
    val one = candidates(cd, j06d)
    def facts(xs: List[MatchCandidate]) =
      xs.map(x => (x.candidateId.value, x.left, x.right, x.features)).sortBy(_._1)
    assertEquals(facts(sharded), facts(one))
  }

  test("determinism with non-empty J05 and J06 output: second root is byte-identical") {
    val (j05a, j06a, _) = default
    val (j05b, j06b, _) = run("out-default-b")
    List(j05a -> j05b, j06a -> j06b).foreach { case (x, y) =>
      assertEquals(x.runId, y.runId, x.jobName)
      assert(!y.reused, s"${y.jobName} reused")
      val fx = partFiles(x.outputDir)
      val fy = partFiles(y.outputDir)
      assert(fx.values.exists(_.nonEmpty), s"${x.jobName}: no output bytes")
      assertEquals(fx.keySet, fy.keySet, x.jobName)
      fx.foreach { case (rel, bytes) =>
        assert(java.util.Arrays.equals(bytes, fy(rel)), s"${x.jobName} $rel differs")
      }
    }
  }
}
