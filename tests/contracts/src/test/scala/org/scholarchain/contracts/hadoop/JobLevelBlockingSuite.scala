package org.scholarchain.contracts.hadoop

import java.text.Normalizer

import org.apache.hadoop.conf.Configuration
import org.scholarchain.contracts.hadoop.JobHarness.*
import org.scholarchain.core.{MatchCandidate, MentionId, WorkId}
import org.scholarchain.hadoop.blocking.{
  BlockLimit,
  BlockingJob,
  CandidateBlock,
  FallbackBlockingJob,
  FallbackCoverageJob,
  OversizeKeySummary,
  OversizeMember,
  UncoveredMember
}
import org.scholarchain.hadoop.candidates.{CandidateFeatureJob, CandidateFeatures}
import org.scholarchain.hadoop.common.{Counters, JobResult, JobRunner, Settings}
import org.scholarchain.hadoop.teams.PublicationTeamJob
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

  /** The suite's counts are single-family by design. Since Phase 11 (Phase 5 m5) the default is
    * `dblp-base-name,initials-surname`, so every configuration here pins the one family.
    */
  private def pinned(overrides: (String, String)*): Configuration =
    conf(work, ((Settings.Keys.BlockingFamilies -> Family) +: overrides)*)

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
    val c = pinned(overrides*)
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

  private val hotMentions: Set[String] =
    (0 until Members).map(i => MentionId.of(WorkId.fromDblpKey(key(i)), 0).value).toSet

  /** Chain plus J05 -> J05b -> J05c -> J06, all under one configuration. */
  private def runStage(
      out: String,
      overrides: (String, String)*
  ): (CandidateStage, Configuration) = {
    val c = pinned(overrides*)
    val ch = chain(c, input, work.resolve(out), Snapshot)
    assertEquals(ch.extract.main(Counters.Accepted), Members.toLong)
    (
      candidateStage(
        c,
        ch.teams.dir(PublicationTeamJob.Contexts),
        ch.teams.runId,
        work.resolve(out),
        Snapshot
      ),
      c
    )
  }

  test(
    "maxPairsPerBlock 65 under one configuration: J05 lists the 66-pair block as oversize " +
      "(max_pairs_per_block, effective limit 11); its members reach the fallback accounting; " +
      "J06 skips nothing"
  ) {
    val (s, c) = runStage("out-pairs65", Settings.Keys.CandidateMaxPairsPerBlock -> "65")
    val j05 = s.j05
    // J05: the block is oversize by the pair limit, not emitted
    assertEquals(j05.main(Counters.BlocksEmitted), 0L)
    assertEquals(lines(c, j05, BlockingJob.Blocks), Nil)
    assertEquals(j05.main(Counters.OversizeBlock), 1L)
    assertEquals(j05.main(Counters.PairLimitOversizeBlocks), 1L)
    assertEquals(j05.counter(Counters.OversizePerFamilyGroup, Family), 1L)
    assertEquals(j05.main(Counters.OversizeMembers), Members.toLong)
    val listed = lines(c, j05, BlockingJob.OversizeBlocks).map(ujson.read(_))
    assertEquals(listed.size, 1)
    val o = listed.head
    assertEquals(o("family").str, Family)
    assertEquals(o("blockKey").str, HotKey)
    assertEquals(o("size").num.toLong, Members.toLong)
    assertEquals(o("limit").num.toLong, 11L, "effective cap: 11 members (55 pairs) fit 65")
    assertEquals(o("maxBlockSize").num.toLong, 1000L)
    assertEquals(o("maxPairsPerBlock").num.toLong, 65L)
    assertEquals(o("pairs").num.toLong, PairCount)
    assertEquals(o("reason").str, BlockLimit.PairReason)
    assertEquals(BlockLimit.PairReason, "max_pairs_per_block")
    // every member goes to the fallback input, once
    val oversize = lines(c, j05, BlockingJob.OversizeMembers).map(read[OversizeMember](_))
    assertEquals(oversize.map(_.member.mentionId).toSet, hotMentions)
    assertEquals(oversize.size, Members)
    oversize.foreach(m => assertEquals((m.family, m.blockKey), (Family, HotKey)))

    // J05b: each member's coauthor is unique, so every fallback block is a singleton
    assertEquals(s.j05b.main(Counters.Accepted), Members.toLong)
    assertEquals(s.j05b.main(Counters.BlocksEmitted), 0L)
    assertEquals(s.j05b.main(Counters.SingletonBlocks), Members.toLong)
    assertEquals(lines(c, s.j05b, FallbackBlockingJob.Coverage), Nil)

    // J05c: all twelve are accounted for as uncovered, none lost
    assertEquals(s.j05c.main(Counters.CoveredMembers), 0L)
    assertEquals(s.j05c.main(Counters.UncoveredMembers), Members.toLong)
    val uncovered =
      lines(c, s.j05c, FallbackCoverageJob.Uncovered).map(read[UncoveredMember](_))
    assertEquals(uncovered.map(_.mentionId).toSet, hotMentions)
    assertEquals(uncovered.size, Members)
    val summary =
      lines(c, s.j05c, FallbackCoverageJob.Summary).map(read[OversizeKeySummary](_))
    assertEquals(
      summary.map(x => (x.family, x.blockKey, x.members, x.coveredMembers, x.uncoveredMembers)),
      List((Family, HotKey, Members.toLong, 0L, Members.toLong))
    )

    // J06 never sees a block above its limit, so its guard does not fire
    assertEquals(s.j06.main(Counters.OversizeBlock), 0L)
    assertEquals(s.j06.main(Counters.OversizeBlockPairsSkipped), 0L)
    assertEquals(s.j06.main(Counters.UnexpandedMembers), 0L)
    assertEquals(s.j06.main(Counters.CandidatesEmitted), 0L)
    assertEquals(lines(c, s.j06, CandidateFeatureJob.UnexpandedMembers), Nil)
    s.all.foreach(r => assertEquals(identity(r)._1, identity(r)._2, r.jobName))

    // one pair more and the block fits: emitted and fully expanded
    val (ok, _) = runStage("out-pairs66", Settings.Keys.CandidateMaxPairsPerBlock -> "66")
    assertEquals(ok.j05.main(Counters.OversizeBlock), 0L)
    assertEquals(ok.j05.main(Counters.PairLimitOversizeBlocks), 0L)
    assertEquals(ok.j05.main(Counters.BlocksEmitted), 1L)
    assertEquals(ok.j06.main(Counters.OversizeBlock), 0L)
    assertEquals(ok.j06.main(Counters.CandidatesEmitted), PairCount)
  }

  test(
    "J06 guard: J05 under a permissive pair limit, J06 under 65: J06 skips the block, counts " +
      "and lists every member in unexpandedMembers/"
  ) {
    val permissive = pinned()
    val ch = chain(permissive, input, work.resolve("out-guard"), Snapshot)
    val (j05, _) = blockingAndCandidates(permissive, ch, work.resolve("out-guard"), Snapshot)
    assertEquals(j05.main(Counters.BlocksEmitted), 1L)
    assertEquals(j05.main(Counters.OversizeBlock), 0L)

    val strict = pinned(Settings.Keys.CandidateMaxPairsPerBlock -> "65")
    val j06 = JobRunner.run(
      strict,
      JobRunner.fromRuns(List(j05.runId))(
        CandidateFeatureJob.spec(
          j05.dir(BlockingJob.Blocks),
          hpath(work.resolve("out-guard-strict")),
          Snapshot,
          _
        )
      )
    )
    assertEquals(j06.main(Counters.Accepted), 1L)
    assertEquals(j06.main(Counters.OversizeBlock), 1L)
    assertEquals(j06.main(Counters.OversizeBlockPairsSkipped), PairCount)
    assertEquals(j06.main(Counters.CandidatesEmitted), 0L)
    assertEquals(j06.main(Counters.CandidateShards), 0L)
    assertEquals(candidates(strict, j06), Nil)
    assertEquals(j06.main(Counters.UnexpandedMembers), Members.toLong)
    assertEquals(j06.counter(Counters.UnexpandedPerFamilyGroup, Family), Members.toLong)
    val listed = lines(strict, j06, CandidateFeatureJob.OversizeBlocks).map(ujson.read(_))
    assertEquals(listed.size, 1)
    assertEquals(listed.head("family").str, Family)
    assertEquals(listed.head("blockKey").str, HotKey)
    assertEquals(listed.head("size").num.toLong, Members.toLong)
    assertEquals(listed.head("pairs").num.toLong, PairCount)
    assertEquals(listed.head("limit").num.toLong, 65L)
    val un = lines(strict, j06, CandidateFeatureJob.UnexpandedMembers).map(read[UncoveredMember](_))
    assertEquals(un.size, Members)
    assertEquals(un.map(_.mentionId).toSet, hotMentions)
    un.foreach { u =>
      assertEquals(u.uncoveredVersion, UncoveredMember.Version)
      assertEquals((u.family, u.blockKey), (Family, HotKey))
      assertEquals(MentionId.of(WorkId.fromDblpKey(u.dblpKey), 0).value, u.mentionId)
      assertEquals(WorkId.fromDblpKey(u.dblpKey).value, u.workId)
      assert(u.name.startsWith("Jos"), u.name)
    }
    assertEquals(un.map(_.dblpKey).toSet, (0 until Members).map(key).toSet)
    assertEquals(j06.stats("largestBlockSeen").value, Members.toLong)
    assertEquals(identity(j06)._1, identity(j06)._2)
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
