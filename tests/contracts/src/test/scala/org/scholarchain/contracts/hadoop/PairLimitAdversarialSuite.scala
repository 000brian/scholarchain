package org.scholarchain.contracts.hadoop

import org.apache.hadoop.conf.Configuration
import org.scholarchain.contracts.hadoop.JobHarness.*
import org.scholarchain.core.{MentionId, WorkId}
import org.scholarchain.hadoop.blocking.*
import org.scholarchain.hadoop.candidates.CandidateFeatureJob
import org.scholarchain.hadoop.common.{Counters, JobResult, Settings}
import org.scholarchain.hadoop.teams.PublicationTeamJob
import upickle.default.read

/** Adversarial attacks on the pair-limit repair of J05 and J05b (code-reviewer finding M-new-1): a
  * block is oversize when it has more members than its member cap OR more pairs than
  * `scholarchain.candidates.maxPairsPerBlock`, so that no configuration emits a block that J06
  * refuses to expand.
  *
  * The expected effective cap is computed here independently (exact integer square root, not the
  * production binary search). Under each configuration (pair limit 1, the exact boundary n(n-1)/2 ==
  * limit, a member cap of Int.MaxValue, a member-cap-only limit, and seeded random limits) the
  * suite checks, on the real jobs J05 -> J05b -> J05c -> J06:
  *
  *   - no emitted primary or fallback block exceeds the pair limit or its member cap;
  *   - every oversize listing line carries the right limit, pairs and reason;
  *   - J05: per family, every keyed mention is in an emitted block, a counted singleton or the
  *     oversize members (nothing dropped); J05b likewise for every fallback key emission;
  *   - J05c: covered + uncovered = oversize members, per key and in total; the covered set equals
  *     the members of emitted fallback blocks of two or more, computed from J05b `blocks/`;
  *   - J06 never fires its guard (no skipped block, no unexpanded member) and emits exactly the
  *     pairs of the emitted blocks;
  *   - the map-stage counter identity of every job; determinism across output roots.
  */
class PairLimitAdversarialSuite extends munit.FunSuite {

  override val munitTimeout = scala.concurrent.duration.Duration(1200, "s")

  // ---------------------------------------------------------------------------------------------
  // Independent model of the effective cap
  // ---------------------------------------------------------------------------------------------

  private val One = BigInt(1)
  private val Two = BigInt(2)

  private def pairsOf(n: Long): BigInt =
    if (n < 2) BigInt(0) else BigInt(n) * BigInt(n - 1) / Two

  /** Largest m in [1, cap] with m(m-1)/2 <= maxPairs: closed form with an exact BigInt check. */
  private def modelCap(cap: Int, maxPairs: Long): Int = {
    val p = BigInt(maxPairs)
    // m(m-1)/2 <= p  <=>  m <= (1 + sqrt(1 + 8p)) / 2
    val root = BigInt((One + p * BigInt(8)).bigInteger.sqrt())
    var m = (root + One) / Two
    while (m > One && m * (m - One) / Two > p) m = m - One
    while ((m + One) * m / Two <= p) m = m + One
    if (m >= BigInt(cap)) cap else m.toInt
  }

  test("BlockLimit.maxMembers equals the independent model on edge and seeded random limits") {
    val edges = List(
      (1, 1L),
      (2, 1L),
      (1000, 1L),
      (1000, 2L),
      (1000, 3L),
      (12, 65L),
      (12, 66L),
      (11, 66L),
      (1000, 125000L),
      (500, 124750L),
      (1000, 124749L),
      (Int.MaxValue, 1L),
      (Int.MaxValue, 10L),
      (Int.MaxValue, Long.MaxValue),
      (Int.MaxValue, 2305843005992468481L), // exactly pairs(Int.MaxValue)
      (Int.MaxValue, 2305843005992468480L), // one below
      (3, Long.MaxValue)
    )
    val rnd = new scala.util.Random(20261114L)
    val random = List.fill(2000) {
      val cap = if (rnd.nextBoolean()) 1 + rnd.nextInt(5000) else 1 + rnd.nextInt(Int.MaxValue)
      val pairs =
        if (rnd.nextBoolean()) 1L + rnd.nextInt(200000).toLong
        else 1L + (rnd.nextLong() & Long.MaxValue) % Long.MaxValue
      (cap, pairs)
    }
    (edges ++ random).foreach { case (cap, maxPairs) =>
      val l = BlockLimit(cap, maxPairs)
      val expected = modelCap(cap, maxPairs)
      assertEquals(l.effective, expected, s"cap $cap, maxPairs $maxPairs")
      assertEquals(BlockLimit.maxMembers(cap, maxPairs), expected)
      assert(l.effective >= 1 && l.effective <= cap)
      assert(pairsOf(l.effective.toLong) <= BigInt(maxPairs))
      assert(l.effective == cap || pairsOf(l.effective.toLong + 1) > BigInt(maxPairs))
      assertEquals(l.pairBound, expected < cap)
      // reasons for blocks just above the effective cap and just above the member cap
      val over = l.effective.toLong + 1
      assertEquals(
        l.reason(over),
        if (over > cap) BlockLimit.MemberReason else BlockLimit.PairReason
      )
      assertEquals(l.reason(cap.toLong + 1), BlockLimit.MemberReason)
      assertEquals(BigInt(BlockLimit.pairs(over)), pairsOf(over))
    }
  }

  test("non-positive limits are refused, never treated as unlimited") {
    List((0, 10L), (-1, 10L), (10, 0L), (10, -5L)).foreach { case (cap, p) =>
      intercept[IllegalArgumentException](BlockLimit.maxMembers(cap, p))
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Job level
  // ---------------------------------------------------------------------------------------------

  private val Snapshot = "pairlimit-2026-11-14"
  private def key(i: Int) = f"conf/sigir/PlHot$i%02d"

  /** Blocks of many sizes in two primary families and two fallback kinds:
    *   - `Big Synthname` (9 works) and `Big Synthname 0001` (1): base-name block of 10; with `B.
    *     Synthname` (2) the initials block `b synthname` has 12.
    *   - coauthors: `Co Alpha` on 6 works, `Co Beta` on 4, `Co Gamma` on 1; work 8 has none.
    *   - `Four Synthx` on 4 works with `Co Delta` (blocks of exactly 4 = 6 pairs).
    *   - `Five Synthy` on 5 works with `Co Eps` (3) and `Co Zeta` (2): blocks of 5 = 10 pairs.
    *   - every work is in venue conf/sigir, so a `venue` fallback block is as large as its oversize
    *     primary block.
    */
  private val authors: List[List[String]] =
    List.fill(4)(List("Big Synthname", "Co Alpha")) ++
      List.fill(3)(List("Big Synthname", "Co Beta")) ++
      List(List("Big Synthname", "Co Gamma"), List("Big Synthname")) ++
      List(List("B. Synthname", "Co Alpha"), List("B. Synthname", "Co Beta")) ++
      List.fill(4)(List("Four Synthx", "Co Delta")) ++
      List.fill(3)(List("Five Synthy", "Co Eps")) ++
      List.fill(2)(List("Five Synthy", "Co Zeta")) ++
      List(List("Big Synthname 0001", "Co Alpha"))

  private val allMentions: Set[String] = authors.zipWithIndex.flatMap { case (as, i) =>
    as.indices.map(p => MentionId.of(WorkId.fromDblpKey(key(i)), p).value)
  }.toSet

  private val Families = List("dblp-base-name", "initials-surname")

  private lazy val work = workDir("pairlimit")
  private lazy val input = write(
    work.resolve("in"),
    "pairlimit.xml",
    corpus(authors.zipWithIndex.map { case (as, i) => record(key(i), as, year = 2015 + i % 7) })
  )
  private lazy val base = conf(work)
  private lazy val ch = {
    val r = chain(base, input, work.resolve("chain"), Snapshot)
    assertEquals(r.extract.main(Counters.Accepted), authors.size.toLong)
    r
  }

  final case class Limits(name: String, maxBlock: Int, fallbackMax: Int, maxPairs: Long) {
    def settings: List[(String, String)] = List(
      Settings.Keys.BlockingFamilies -> Families.mkString(","),
      Settings.Keys.FallbackFamilies -> Families.mkString(","),
      Settings.Keys.FallbackSecondaryKeys -> "coauthor,venue",
      Settings.Keys.BlockingMaxBlock -> maxBlock.toString,
      Settings.Keys.FallbackMaxBlock -> fallbackMax.toString,
      Settings.Keys.CandidateMaxPairsPerBlock -> maxPairs.toString
    )
    def primaryCap: Int = modelCap(maxBlock, maxPairs)
    def fallbackCap: Int = modelCap(fallbackMax, maxPairs)
  }

  private val PairsOne = Limits("pairs-1", 1000, 500, 1L)
  private val Boundary6 = Limits("boundary-6", 1000, 1000, 6L)
  private val HugeCap = Limits("huge-member-cap", Int.MaxValue, Int.MaxValue, 10L)
  private val MemberOnly = Limits("member-cap-only", 3, 2, 125000L)
  private val Random: List[Limits] = {
    val rnd = new scala.util.Random(5150L)
    (1 to 2).toList.map { i =>
      Limits(
        s"random-$i",
        2 + rnd.nextInt(11),
        2 + rnd.nextInt(11),
        1L + rnd.nextInt(40).toLong
      )
    }
  }

  private val runs = scala.collection.mutable.Map.empty[String, (CandidateStage, Configuration)]

  private def stage(l: Limits, root: String = "a"): (CandidateStage, Configuration) =
    runs.synchronized {
      runs.getOrElseUpdate(
        s"${l.name}/$root", {
          val c = conf(work, l.settings*)
          (
            candidateStage(
              c,
              ch.teams.dir(PublicationTeamJob.Contexts),
              ch.teams.runId,
              work.resolve(s"out-${l.name}-$root"),
              Snapshot
            ),
            c
          )
        }
      )
    }

  private def blocks(c: Configuration, r: JobResult, sub: String): List[CandidateBlock] =
    lines(c, r, sub).map(read[CandidateBlock](_))

  /** Checks every M-new-1 invariant for one configuration; returns the stage for extra checks. */
  private def invariants(l: Limits): (CandidateStage, Configuration) = {
    val (s, c) = stage(l)
    val ctx = s"[${l.name}: maxBlock ${l.maxBlock}, fallbackMax ${l.fallbackMax}, " +
      s"maxPairs ${l.maxPairs}; caps ${l.primaryCap}/${l.fallbackCap}]"

    // emitted blocks never exceed the pair limit or their member cap
    val primary = blocks(c, s.j05, BlockingJob.Blocks)
    val fallback = blocks(c, s.j05b, FallbackBlockingJob.Blocks)
    primary.foreach { b =>
      assertEquals(CandidateBlock.problems(b), Nil, s"$ctx ${b.blockKey}")
      assert(pairsOf(b.size.toLong) <= BigInt(l.maxPairs), s"$ctx primary ${b.blockKey}: ${b.size}")
      assert(b.size <= l.maxBlock && b.size <= l.primaryCap, s"$ctx primary ${b.blockKey}")
      assert(Families.contains(b.family), s"$ctx ${b.family}")
    }
    fallback.foreach { b =>
      assertEquals(CandidateBlock.problems(b), Nil, s"$ctx ${b.blockKey}")
      assert(
        pairsOf(b.size.toLong) <= BigInt(l.maxPairs),
        s"$ctx fallback ${b.blockKey}: ${b.size}"
      )
      assert(b.size <= l.fallbackMax && b.size <= l.fallbackCap, s"$ctx fallback ${b.blockKey}")
    }

    // oversize listings: limit, pairs and reason
    def checkListing(o: ujson.Value, memberCap: Int, effective: Int): Unit = {
      val n = o("size").num.toLong
      assert(n > effective, s"$ctx listed block of $n within cap $effective")
      assertEquals(o("limit").num.toLong, effective.toLong, s"$ctx $o")
      assertEquals(o("maxBlockSize").num.toLong, memberCap.toLong, s"$ctx $o")
      assertEquals(o("maxPairsPerBlock").num.toLong, l.maxPairs, s"$ctx $o")
      assertEquals(BigInt(o("pairs").num.toLong), pairsOf(n), s"$ctx $o")
      assertEquals(
        o("reason").str,
        if (n > memberCap) "max_block_size" else "max_pairs_per_block",
        s"$ctx $o"
      )
    }
    val listed = lines(c, s.j05, BlockingJob.OversizeBlocks).map(ujson.read(_))
    listed.foreach(checkListing(_, l.maxBlock, l.primaryCap))
    val fbListed = lines(c, s.j05b, FallbackBlockingJob.OversizeBlocks).map(ujson.read(_))
    fbListed.foreach(checkListing(_, l.fallbackMax, l.fallbackCap))
    assertEquals(s.j05.main(Counters.OversizeBlock), listed.size.toLong, ctx)
    assertEquals(
      s.j05.main(Counters.PairLimitOversizeBlocks),
      listed.count(_("reason").str == "max_pairs_per_block").toLong,
      ctx
    )
    assertEquals(s.j05b.main(Counters.OversizeBlock), fbListed.size.toLong, ctx)
    assertEquals(
      s.j05b.main(Counters.PairLimitOversizeBlocks),
      fbListed.count(_("reason").str == "max_pairs_per_block").toLong,
      ctx
    )

    // J05: nothing dropped. Every mention has a key in both families here.
    val oversize = lines(c, s.j05, BlockingJob.OversizeMembers).map(read[OversizeMember](_))
    assertEquals(s.j05.main(Counters.Accepted), allMentions.size.toLong, ctx)
    assertEquals(
      primary.map(_.size.toLong).sum + s.j05.main(Counters.SingletonBlocks) + oversize.size,
      Families.size.toLong * allMentions.size,
      s"$ctx J05 member accounting"
    )
    assertEquals(s.j05.main(Counters.OversizeMembers), oversize.size.toLong, ctx)
    assertEquals(listed.map(_("size").num.toLong).sum, oversize.size.toLong, ctx)
    val oversizeKeys = oversize.map(m => (m.family, m.blockKey, m.member.mentionId))
    assertEquals(oversizeKeys.distinct.size, oversizeKeys.size, s"$ctx duplicate oversize member")
    assertEquals(
      oversize.map(m => (m.family, m.blockKey)).toSet,
      listed.map(o => (o("family").str, o("blockKey").str)).toSet,
      ctx
    )
    // in each family, the emitted blocks, singletons and oversize members partition the mentions
    Families.foreach { f =>
      val inBlocks = primary.filter(_.family == f).flatMap(_.members.map(_.mentionId))
      val inOversize = oversize.filter(_.family == f).map(_.member.mentionId)
      assertEquals(inBlocks.toSet.intersect(inOversize.toSet), Set.empty[String], s"$ctx $f")
      assert(
        (inBlocks ++ inOversize).toSet.subsetOf(allMentions),
        s"$ctx $f: unknown mention in blocks"
      )
    }

    // J05b: every fallback key emission lands in an emitted block, a singleton or a listing
    assertEquals(s.j05b.main(Counters.Accepted), oversize.size.toLong, ctx)
    assertEquals(
      fallback.map(_.size.toLong).sum + s.j05b.main(Counters.SingletonBlocks) +
        fbListed.map(_("size").num.toLong).sum,
      s.j05b.main(Counters.FallbackKeysEmitted),
      s"$ctx J05b key accounting"
    )

    // J05c: covered + uncovered = oversize members; covered = members of emitted fallback blocks
    val covered = s.j05c.main(Counters.CoveredMembers)
    val uncovered = s.j05c.main(Counters.UncoveredMembers)
    assertEquals(covered + uncovered, oversize.size.toLong, s"$ctx covered + uncovered")
    val uncoveredLines =
      lines(c, s.j05c, FallbackCoverageJob.Uncovered).map(read[UncoveredMember](_))
    assertEquals(uncoveredLines.size.toLong, uncovered, ctx)
    val expectedCovered = fallback
      .filter(_.size >= 2)
      .flatMap { b =>
        val (pf, _) = FallbackKeys.split(b.family)
        val primaryKey = ujson.read(b.blockKey).arr.head.str
        b.members.map(m => (pf, primaryKey, m.mentionId))
      }
      .toSet
    val uncoveredSet = uncoveredLines.map(u => (u.family, u.blockKey, u.mentionId)).toSet
    assertEquals(uncoveredSet.size.toLong, uncovered, s"$ctx duplicate uncovered line")
    assertEquals(expectedCovered.intersect(uncoveredSet), Set.empty[(String, String, String)], ctx)
    assertEquals(expectedCovered ++ uncoveredSet, oversizeKeys.toSet, s"$ctx coverage partition")
    assertEquals(expectedCovered.size.toLong, covered, s"$ctx covered count")
    val summaries =
      lines(c, s.j05c, FallbackCoverageJob.Summary).map(read[OversizeKeySummary](_))
    assertEquals(summaries.size, listed.size, ctx)
    summaries.foreach { x =>
      assertEquals(x.coveredMembers + x.uncoveredMembers, x.members, s"$ctx $x")
      assertEquals(
        x.members,
        oversize.count(m => m.family == x.family && m.blockKey == x.blockKey).toLong,
        s"$ctx $x"
      )
    }

    // J06 never meets a block above its limit and expands every emitted block
    assertEquals(s.j06.main(Counters.OversizeBlock), 0L, ctx)
    assertEquals(s.j06.main(Counters.OversizeBlockPairsSkipped), 0L, ctx)
    assertEquals(s.j06.main(Counters.UnexpandedMembers), 0L, ctx)
    assertEquals(lines(c, s.j06, CandidateFeatureJob.UnexpandedMembers), Nil, ctx)
    val expectedPairs = (primary ++ fallback).map(b => pairsOf(b.size.toLong)).sum
    assertEquals(BigInt(s.j06.main(Counters.CandidatesEmitted)), expectedPairs, ctx)
    val emittedCandidates = (primary ++ fallback)
      .map(_.family)
      .distinct
      .flatMap(f => lines(c, s.j06, CandidateFeatureJob.candidatesDir(f)))
    assertEquals(BigInt(emittedCandidates.size), expectedPairs, ctx)

    s.all.foreach(r => assertEquals(identity(r)._1, identity(r)._2, s"$ctx ${r.jobName}"))
    (s, c)
  }

  test("pair limit 1: effective cap 2; only two-member blocks are emitted; nothing is lost") {
    assertEquals(PairsOne.primaryCap, 2)
    val (s, c) = invariants(PairsOne)
    val all = blocks(c, s.j05, BlockingJob.Blocks) ++ blocks(c, s.j05b, FallbackBlockingJob.Blocks)
    all.foreach(b => assertEquals(b.size, 2, b.blockKey))
    assert(s.j05.main(Counters.PairLimitOversizeBlocks) > 0)
    // with the member cap at 1000, every J05 oversize block is oversize by the pair limit
    assertEquals(s.j05.main(Counters.PairLimitOversizeBlocks), s.j05.main(Counters.OversizeBlock))
    assertEquals(s.j06.main(Counters.CandidatesEmitted), all.size.toLong)
  }

  test("exact boundary n(n-1)/2 == limit (6): the 4-member blocks are emitted, 5 are oversize") {
    assertEquals(Boundary6.primaryCap, 4)
    val (s, c) = invariants(Boundary6)
    val primary = blocks(c, s.j05, BlockingJob.Blocks)
    def size(f: String, k: String) = primary.find(b => b.family == f && b.blockKey == k).map(_.size)
    assertEquals(size("dblp-base-name", "Four Synthx"), Some(4))
    assertEquals(size("dblp-base-name", "Co Delta"), Some(4))
    assertEquals(size("dblp-base-name", "Co Beta"), Some(4))
    val listed = lines(c, s.j05, BlockingJob.OversizeBlocks).map(ujson.read(_))
    val five =
      listed.find(o => o("family").str == "dblp-base-name" && o("blockKey").str == "Five Synthy")
    assert(five.isDefined, listed.toString)
    assertEquals(five.get("reason").str, "max_pairs_per_block")
    assertEquals(five.get("pairs").num.toLong, 10L)
    assertEquals(five.get("limit").num.toLong, 4L)
  }

  test("member cap Int.MaxValue, pair limit 10: effective cap 5; the 5-member blocks fit exactly") {
    assertEquals(HugeCap.primaryCap, 5)
    val (s, c) = invariants(HugeCap)
    val primary = blocks(c, s.j05, BlockingJob.Blocks)
    assert(
      primary.exists(b => b.blockKey == "Five Synthy" && b.size == 5),
      primary.map(b => (b.blockKey, b.size)).toString
    )
    val listed = lines(c, s.j05, BlockingJob.OversizeBlocks).map(ujson.read(_))
    assert(listed.nonEmpty)
    listed.foreach(o => assertEquals(o("reason").str, "max_pairs_per_block"))
    assertEquals(s.j05.main(Counters.PairLimitOversizeBlocks), listed.size.toLong)
    // the fallback [Big Synthname, Co Alpha] has 5 members: emitted at the boundary
    val fb = blocks(c, s.j05b, FallbackBlockingJob.Blocks)
    assert(
      fb.exists(b => b.family == "dblp-base-name.coauthor" && b.size == 5),
      fb.map(b => (b.family, b.blockKey, b.size)).toString
    )
  }

  test("member cap only (3 / 2, pair limit 125000): reason max_block_size, no pair-limit count") {
    val (s, c) = invariants(MemberOnly)
    assertEquals(s.j05.main(Counters.PairLimitOversizeBlocks), 0L)
    assertEquals(s.j05b.main(Counters.PairLimitOversizeBlocks), 0L)
    (lines(c, s.j05, BlockingJob.OversizeBlocks) ++
      lines(c, s.j05b, FallbackBlockingJob.OversizeBlocks))
      .map(ujson.read(_))
      .foreach(o => assertEquals(o("reason").str, "max_block_size"))
  }

  Random.foreach { l =>
    test(
      s"seeded random limits ${l.name} (maxBlock ${l.maxBlock}, fallbackMax ${l.fallbackMax}, " +
        s"maxPairs ${l.maxPairs}): every invariant holds"
    ) {
      invariants(l)
    }
  }

  test("different pair limits give different run IDs (no wrong reuse in one output root)") {
    val ids = List(PairsOne, Boundary6, HugeCap, MemberOnly).map(l => stage(l)._1)
    List[CandidateStage => JobResult](_.j05, _.j05b, _.j05c, _.j06).foreach { pick =>
      val rs = ids.map(pick(_).runId)
      assertEquals(rs.distinct.size, rs.size, rs.toString)
    }
    // the same configuration in one root reuses; a changed pair limit in the same root does not
    val sameRoot = work.resolve("out-shared")
    def at(l: Limits) = candidateStage(
      conf(work, l.settings*),
      ch.teams.dir(PublicationTeamJob.Contexts),
      ch.teams.runId,
      sameRoot,
      Snapshot
    )
    val first = at(Boundary6)
    val other = at(Boundary6.copy(name = "boundary-7", maxPairs = 7L))
    assert(!other.j05.reused && !other.j05b.reused && !other.j06.reused)
    assertNotEquals(other.j05.runId, first.j05.runId)
    val again = at(Boundary6)
    assert(again.all.forall(_.reused), again.all.map(r => r.jobName -> r.reused).toString)
  }

  test("determinism across output roots under the pair limit (J05, J05b, J05c, J06 bytes)") {
    val (a, _) = stage(PairsOne)
    val (b, _) = stage(PairsOne, "b")
    // collect every difference before failing, so the report names all affected files
    val diffs = a.all.zip(b.all).flatMap { case (x, y) =>
      assertEquals(x.runId, y.runId, x.jobName)
      assert(!y.reused, s"${y.jobName} reused")
      val fx = partFiles(x.outputDir)
      val fy = partFiles(y.outputDir)
      assertEquals(fx.keySet, fy.keySet, x.jobName)
      fx.toList.sortBy(_._1).collect {
        case (rel, bytes) if !java.util.Arrays.equals(bytes, fy(rel)) =>
          val sameLines =
            new String(bytes, "UTF-8").split('\n').sorted.toList ==
              new String(fy(rel), "UTF-8").split('\n').sorted.toList
          s"${x.jobName} $rel differs (same lines in another order: $sameLines)"
      }
    }
    assert(partFiles(a.j05.outputDir).values.exists(_.nonEmpty))
    assert(partFiles(a.j05c.outputDir).values.exists(_.nonEmpty))
    assertEquals(diffs, Nil, diffs.mkString("\n"))
  }
}
