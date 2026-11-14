package org.scholarchain.contracts.hadoop

import org.scholarchain.contracts.hadoop.JobHarness.*
import org.scholarchain.core.{CoauthorPairCandidate, Contract, PublicationTeam, WorkId}
import org.scholarchain.hadoop.common.{Counters, Reasons, Settings, TaskIO}
import org.scholarchain.hadoop.pairs.{PairConsolidateJob, PairExpandJob}
import org.scholarchain.hadoop.teams.PublicationTeamJob

/** Job-level giant-team tests: J07 oversize and hard-limit thresholds from configuration, and J08
  * pair expansion sharded across reducers (n(n-1)/2 pairs, each position pair exactly once).
  */
class JobLevelTeamScaleSuite extends munit.FunSuite {

  override val munitTimeout = scala.concurrent.duration.Duration(600, "s")

  private val Snapshot = "giant-2026-11-13"
  private val N = 60
  private val Pairs = N.toLong * (N - 1) / 2 // 1770
  private val GiantKey = "conf/sigir/JobGiant20"
  private val SmallKey = "conf/sigir/JobSmall20"

  private def names(n: Int): List[String] = (0 until n).map(i => f"Synthetic Giant $i%03d").toList

  private lazy val work = workDir("j07-giant")
  private lazy val input = write(
    work.resolve("in"),
    "giant.xml",
    corpus(
      List(
        record(GiantKey, names(N)),
        record(SmallKey, List("Synthetic Small A", "Synthetic Small B", "Synthetic Small C"))
      )
    )
  )

  private def teams(
      c: org.apache.hadoop.conf.Configuration,
      r: org.scholarchain.hadoop.common.JobResult
  ) =
    lines(c, r, PublicationTeamJob.Teams).map(l => Contract.decode[PublicationTeam](l).toOption.get)

  private def pairs(
      c: org.apache.hadoop.conf.Configuration,
      r: org.scholarchain.hadoop.common.JobResult,
      sub: String
  ) = lines(c, r, sub).map(l => Contract.decode[CoauthorPairCandidate](l).toOption.get)

  test("giant team: oversize counter and n(n-1)/2 pairs across shards and reducers") {
    val c = conf(
      work,
      Settings.Keys.TeamOversize -> "50",
      Settings.Keys.PairsPerShard -> "100",
      Settings.Keys.PairExpandReducers -> "4",
      Settings.Keys.PairConsolidateReducers -> "3"
    )
    val r = chain(c, input, work.resolve("out-sharded"), Snapshot)
    assertEquals(r.teams.main(Counters.OversizeTeam), 1L)
    val oversize = lines(c, r.teams, PublicationTeamJob.Oversize).map(ujson.read(_))
    assertEquals(oversize.size, 1)
    assertEquals(oversize.head("dblpKey").str, GiantKey)
    assertEquals(oversize.head("teamSize").num.toLong, N.toLong)
    assertEquals(oversize.head("pairs").num.toLong, Pairs)
    assertEquals(r.teams.stats("maxTeamSize").value, N.toLong)
    assertEquals(r.teams.stats("maxTeamPairs").value, Pairs)
    // the oversize team is still expanded
    assertEquals(teams(c, r.teams).map(_.teamSize).sorted, List(3, N))

    // J08a: ceil(1770 / 100) = 18 shards for the giant team, 1 for the small team
    assertEquals(r.expand.main(Counters.PairShards), 18L + 1L)
    assertEquals(r.expand.main(Counters.PairInstances), Pairs + 3L)
    assertEquals(r.expand.main(Counters.EmittedPair), Pairs + 3L)
    assertEquals(r.expand.stats("largestPairExpansion").value, Pairs)
    assert(
      nonEmptyParts(r.expand, PairExpandJob.Instances).count(_.startsWith("part-r-")) > 1,
      s"shards of the giant team were not spread over reducers: " +
        nonEmptyParts(r.expand, PairExpandJob.Instances)
    )
    val giantId = WorkId.fromDblpKey(GiantKey)
    val inst = pairs(c, r.expand, PairExpandJob.Instances).filter(_.workId == giantId)
    assertEquals(inst.size.toLong, Pairs)
    val positions = inst.flatMap(_.positions).map(p => (p.leftPosition, p.rightPosition))
    val expected = for { i <- 0 until N; j <- (i + 1) until N } yield (i, j)
    assertEquals(
      positions.map { case (x, y) => (math.min(x, y), math.max(x, y)) }.sorted,
      expected.toList
    )
    inst.foreach { p =>
      assertEquals(p.year, 2020)
      assertEquals(p.provenance.sourceRecordIds, List(GiantKey))
      assertEquals(p.provenance.snapshotId, Snapshot)
    }

    // J08b: distinct names, so consolidation keeps every pair
    val consolidated = pairs(c, r.consolidate, PairConsolidateJob.Pairs).filter(_.workId == giantId)
    assertEquals(consolidated.size.toLong, Pairs)
    assertEquals(consolidated.map(p => (p.left, p.right)).distinct.size.toLong, Pairs)
    assertEquals(r.consolidate.main(Counters.Duplicate), 0L)
    r.all.foreach(j => assertEquals(identity(j)._1, identity(j)._2, j.jobName))
  }

  test("default oversize threshold (100) does not flag a 60-author team; override changes run ID") {
    val c = conf(work)
    val r = chain(c, input, work.resolve("out-default"), Snapshot)
    assertEquals(r.teams.main(Counters.OversizeTeam), 0L)
    assertEquals(lines(c, r.teams, PublicationTeamJob.Oversize), Nil)
    assertEquals(r.expand.main(Counters.PairShards), 2L) // default pairsPerShard 100000
    assertEquals(r.expand.main(Counters.PairInstances), Pairs + 3L)
    val c50 = conf(work, Settings.Keys.TeamOversize -> "50")
    val r50 = chain(c50, input, work.resolve("out-default"), Snapshot)
    assertNotEquals(r50.teams.runId, r.teams.runId)
    assertEquals(r50.teams.main(Counters.OversizeTeam), 1L)
  }

  test("hard limit (assemble path): team of 60 with hardLimit 59 is rejected, no pairs") {
    val c = conf(work, Settings.Keys.TeamHardLimit -> "59", Settings.Keys.TeamOversize -> "50")
    val r = chain(c, input, work.resolve("out-hard59"), Snapshot)
    assertEquals(r.teams.main(Counters.OversizeTeam), 1L)
    assertEquals(r.teams.counter(Counters.Group, Counters.RejectedReduce), 1L)
    assertEquals(r.teams.counter(Counters.RejectReasonGroup, Reasons.TeamExceedsHardLimit), 1L)
    val rej = dispositions(c, r.teams, TaskIO.RejectsDir)
    assertEquals(rej.map(_.reason), List(Reasons.TeamExceedsHardLimit))
    assertEquals(rej.head.sourceRecordId, Some(GiantKey))
    assertEquals(rej.head.detail, s"team of $N > 59")
    assertEquals(teams(c, r.teams).map(_.teamSize), List(3))
    assertEquals(lines(c, r.teams, PublicationTeamJob.Oversize), Nil)
    assertEquals(r.expand.main(Counters.PairInstances), 3L)
  }

  test("hard limit (overflow path): mentions beyond hardLimit + 1 stop buffering and reject") {
    val c = conf(work, Settings.Keys.TeamHardLimit -> "10", Settings.Keys.TeamOversize -> "5")
    val r = chain(c, input, work.resolve("out-hard10"), Snapshot)
    assertEquals(r.teams.main(Counters.OversizeTeam), 1L)
    val rej = dispositions(c, r.teams, TaskIO.RejectsDir)
    assertEquals(rej.map(_.reason), List(Reasons.TeamExceedsHardLimit))
    assertEquals(rej.head.detail, s"$N mentions > 10")
    assertEquals(rej.head.sourceRecordId, Some(GiantKey))
    assertEquals(teams(c, r.teams).map(_.teamSize), List(3))
    assertEquals(r.expand.main(Counters.PairInstances), 3L)
    // the 3-author team is not oversize (3 <= 5); only the rejected giant counts
    assertEquals(lines(c, r.teams, PublicationTeamJob.Oversize), Nil)
  }
}
