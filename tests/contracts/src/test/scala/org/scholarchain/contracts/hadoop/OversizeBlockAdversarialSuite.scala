package org.scholarchain.contracts.hadoop

import java.nio.file.Files

import scala.jdk.CollectionConverters.*

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.scholarchain.contracts.hadoop.JobHarness.*
import org.scholarchain.core.{AuthorNameKey, MentionId, WorkId}
import org.scholarchain.hadoop.blocking.*
import org.scholarchain.hadoop.candidates.CandidateFeatureJob
import org.scholarchain.hadoop.common.{Counters, JobFailed, JobResult, JobRunner, Settings}
import org.scholarchain.hadoop.pipeline.BlockingChain
import org.scholarchain.hadoop.teams.PublicationTeamJob
import upickle.default.read

/** Phase 17 R3, check HADOOP-04 (B1): adversarial and cross-module attacks on the oversize-block
  * strategy (counting pre-passes J05p and J05bp, re-keying in J05, still-oversize policy in J05b),
  * end to end from dblp XML: J01 -> J07 -> J08 (DblpChain), then `BlockingChain.run` and J06.
  *
  * Corpus (one primary family, dblp-base-name; J05 cap 5; J05b cap 3; secondary keys coauthor and
  * venue; every work is a SIGIR inproceedings, so the venue fallback group of an oversize key is as
  * large as the key itself):
  *   - `Skew Hot` on 120 works (24 times the cap). Each work has one `Skew Mate <xx>` coauthor (40
  *     mates, 3 works each: fallback groups exactly AT the fallback cap, emitted); works 0-9 also
  *     have `Skew Crowd` (a primary key of 10, oversize; the fallback group [Skew Hot, Skew Crowd]
  *     of 10 is still oversize).
  *   - `Cap Exact` on 5 works (exactly the cap: emitted) and `Cap Plus` on 6 (cap + 1: oversize).
  *   - `Fall Edge` on 7 works: coauthor `Edge Four` on 4 of them (fallback cap + 1: still oversize,
  *     never formed) and `Edge Three` on 3 (fallback cap: emitted).
  *   - 12 keys `Many Key <xx>` with 6 to 9 works each (many oversize keys at once), unique
  *     partners.
  *
  * Expected values are computed by an independent model of the grouping (below), not by the
  * production code. Where a test can fail on the code path before R3, it also runs that path (the
  * 4-argument specs, reducer-side cap only: `legacyCandidateStage`) and shows the difference.
  */
class OversizeBlockAdversarialSuite extends munit.FunSuite {

  override val munitTimeout = scala.concurrent.duration.Duration(1800, "s")

  private val Snapshot = "oversize-2026-11-24"
  private val Family = "dblp-base-name"
  private val Cap = 5
  private val FallbackCap = 3
  private val CoauthorFamily = s"$Family.coauthor"
  private val VenueFamily = s"$Family.venue"

  /** Two-letter tag `Aa`, `Ab`, ... (no digits, so no name looks like a dblp homonym number). */
  private def tag(i: Int): String = s"${('A' + i / 26).toChar}${('a' + i % 26).toChar}"

  /** (dblp key, authors) of every work. */
  private val works: List[(String, List[String])] = {
    val skew = (0 until 120).toList.map { i =>
      f"conf/sigir/OsSkew$i%03d" ->
        (List("Skew Hot", s"Skew Mate ${tag(i % 40)}") ++ (if (i < 10) List("Skew Crowd") else Nil))
    }
    val exact = (0 until 5).toList.map(i =>
      f"conf/sigir/OsExact$i%02d" -> List("Cap Exact", s"Exact Partner ${tag(i)}")
    )
    val plus = (0 until 6).toList.map(i =>
      f"conf/sigir/OsPlus$i%02d" -> List("Cap Plus", s"Plus Partner ${tag(i)}")
    )
    val edge = (0 until 7).toList.map(i =>
      f"conf/sigir/OsEdge$i%02d" -> List("Fall Edge", if (i < 4) "Edge Four" else "Edge Three")
    )
    val many = (0 until 12).toList.flatMap { k =>
      (0 until 6 + k % 4).toList.map(j =>
        f"conf/sigir/OsMany$k%02d$j%02d" -> List(
          s"Many Key ${tag(k)}",
          s"Many Partner ${tag(k)}${tag(j)}"
        )
      )
    }
    skew ++ exact ++ plus ++ edge ++ many
  }

  // ---------------------------------------------------------------------------------------------
  // Independent model
  // ---------------------------------------------------------------------------------------------

  final case class M(work: String, pos: Int, name: String, coauthors: List[String]) {
    val id: String = MentionId.of(WorkId.fromDblpKey(work), pos).value
    val key: String = AuthorNameKey.baseNameKey(name)
  }

  private val mentions: List[M] = works.flatMap { case (k, as) =>
    as.zipWithIndex.map { case (a, i) => M(k, i, a, as.filterNot(_ == a)) }
  }

  /** The venue part of a venue fallback key, as the model writes it (one venue in this corpus). */
  private val V = "<venue>"

  /** (fallback family, primary key, secondary part) of a fallback group. */
  type FKey = (String, String, String)

  final case class Model(cap: Int, fallbackCap: Int) {
    val primary: Map[String, List[M]] = mentions.groupBy(_.key)
    val oversize: Map[String, List[M]] = primary.filter(_._2.size > cap)
    val emitted: Map[String, List[M]] = primary.filter(g => g._2.size >= 2 && g._2.size <= cap)
    val singletons: Int = primary.count(_._2.size == 1)
    val fallback: Map[FKey, List[M]] = oversize.toList
      .flatMap { case (k, ms) =>
        ms.flatMap { m =>
          m.coauthors
            .map(AuthorNameKey.fullNameKey)
            .distinct
            .map(c => (CoauthorFamily, k, c) -> m) :+
            ((VenueFamily, k, V) -> m)
        }
      }
      .groupBy(_._1)
      .map { case (fk, xs) => fk -> xs.map(_._2) }
    val still: Map[FKey, List[M]] = fallback.filter(_._2.size > fallbackCap)
    val fbEmitted: Map[FKey, List[M]] =
      fallback.filter(g => g._2.size >= 2 && g._2.size <= fallbackCap)
    val fbSingletons: Int = fallback.count(_._2.size == 1)
    val oversizeMembers: Set[(String, String)] =
      oversize.toList.flatMap { case (k, ms) => ms.map(m => k -> m.id) }.toSet
    val covered: Set[(String, String)] =
      fbEmitted.toList.flatMap { case ((_, k, _), ms) => ms.map(m => k -> m.id) }.toSet
    val uncovered: Set[(String, String)] = oversizeMembers -- covered
  }

  private val model = Model(Cap, FallbackCap)

  test("the model of the corpus has the shape the attacks need") {
    assertEquals(model.primary("Skew Hot").size, 120)
    assertEquals(model.primary("Cap Exact").size, Cap)
    assertEquals(model.primary("Cap Plus").size, Cap + 1)
    assertEquals(model.oversize.size, 16) // Skew Hot, Skew Crowd, Cap Plus, Fall Edge, 12 Many Key
    assert(!model.oversize.contains("Cap Exact"))
    assertEquals(model.fallback((CoauthorFamily, "Fall Edge", "Edge Four")).size, FallbackCap + 1)
    assertEquals(model.fallback((CoauthorFamily, "Fall Edge", "Edge Three")).size, FallbackCap)
    // every venue group (16), [Skew Hot, Skew Crowd] (10), [Skew Crowd, Skew Hot] (10), Edge Four
    assertEquals(model.still.size, 16 + 3)
    assertEquals(model.covered.size, 120 + 3)
  }

  // ---------------------------------------------------------------------------------------------
  // Runs
  // ---------------------------------------------------------------------------------------------

  private val settings: List[(String, String)] = List(
    Settings.Keys.BlockingFamilies -> Family,
    Settings.Keys.BlockingMaxBlock -> Cap.toString,
    Settings.Keys.FallbackFamilies -> Family,
    Settings.Keys.FallbackSecondaryKeys -> "coauthor,venue",
    Settings.Keys.FallbackMaxBlock -> FallbackCap.toString,
    Settings.Keys.BlockingReducers -> "3",
    Settings.Keys.FallbackReducers -> "3"
  )

  private lazy val work = workDir("oversize-b1")
  private lazy val input =
    write(
      work.resolve("in"),
      "oversize.xml",
      corpus(works.map { case (k, as) => record(k, as) })
    )
  private lazy val c: Configuration = conf(work, settings*)
  private lazy val ch = {
    val r = chain(c, input, work.resolve("chain"), Snapshot)
    assertEquals(r.extract.main(Counters.Accepted), works.size.toLong)
    r
  }
  private lazy val contexts: Path = ch.teams.dir(PublicationTeamJob.Contexts)

  private lazy val main: CandidateStage =
    candidateStage(c, contexts, ch.teams.runId, work.resolve("out-main"), Snapshot)

  private lazy val legacy: LegacyStage =
    legacyCandidateStage(c, contexts, ch.teams.runId, work.resolve("out-legacy"), Snapshot)

  private def blocks(r: JobResult, sub: String): List[CandidateBlock] =
    lines(c, r, sub).map(read[CandidateBlock](_))

  private def keysOf(r: JobResult, sub: String): List[OversizeKey] =
    lines(c, r, sub).map(read[OversizeKey](_))

  private def fkeyOf(family: String, blockKeyJson: String): FKey = {
    val parts = ujson.read(blockKeyJson).arr.map(_.str).toList
    (family, parts.head, if (family == VenueFamily) V else parts(1))
  }

  // ---------------------------------------------------------------------------------------------
  // J05p and J05
  // ---------------------------------------------------------------------------------------------

  test(
    "J05p lists exactly the oversize keys (cap + 1 listed, exactly the cap not), size and limit"
  ) {
    val p = main.j05p
    assertEquals(p.jobName, BlockSizeJob.JobName)
    val listed = keysOf(p, BlockSizeJob.OversizeKeys)
    assertEquals(
      listed.map(k => (k.family, k.keyFields, k.blockKey, k.size, k.limit)).toSet,
      model.oversize.map { case (k, ms) => (Family, List(k), k, ms.size.toLong, Cap) }.toSet
    )
    assertEquals(listed.size, model.oversize.size, "a key listed twice")
    listed.foreach(k => assertEquals(k.keyVersion, OversizeKey.Version))
    assert(listed.exists(k => k.blockKey == "Cap Plus" && k.size == Cap + 1L), listed.toString)
    assert(!listed.exists(_.blockKey == "Cap Exact"), listed.toString)
    assertEquals(p.main(Counters.BlockKeysCounted), model.primary.size.toLong)
    assertEquals(p.main(Counters.OversizeKeysFound), model.oversize.size.toLong)
    assertEquals(p.stats(BlockSizeJob.LargestKeyGroupStat).value, 120L)
    assertEquals(p.stats(BlockSizeJob.LargestKeyGroupStat).subject, s"$Family\tSkew Hot")
  }

  test(
    "J05: a key 24x the cap and 15 other oversize keys at once: no reduce group above the cap; " +
      "the reducer-side-cap path reads all 120 values in one call"
  ) {
    val j05 = main.j05
    val g = j05.stats(BlockingJob.LargestReduceGroupStat)
    assert(g.value <= Cap.toLong, s"largest J05 reduce group $g exceeds the cap $Cap")
    assertEquals(j05.main(Counters.ReducerCappedBlocks), 0L)
    assertEquals(j05.main(Counters.OversizeMembersRekeyed), model.oversizeMembers.size.toLong)
    // the same assertion fails on the code path before R3 (no pre-pass)
    val old = legacy.j05.stats(BlockingJob.LargestReduceGroupStat)
    assertEquals(old.value, 120L)
    assertEquals(old.subject, s"$Family\tSkew Hot")
    assertEquals(legacy.j05.main(Counters.ReducerCappedBlocks), model.oversize.size.toLong)
    assertEquals(legacy.j05.main(Counters.OversizeMembersRekeyed), 0L)
  }

  test("J05 records equal the model: emitted blocks, oversize listings, re-keyed members") {
    val j05 = main.j05
    assertEquals(
      blocks(j05, BlockingJob.Blocks)
        .map(b => b.blockKey -> b.members.map(_.mentionId).sorted)
        .toMap,
      model.emitted.map { case (k, ms) => k -> ms.map(_.id).sorted }
    )
    assert(blocks(j05, BlockingJob.Blocks).exists(b => b.blockKey == "Cap Exact" && b.size == Cap))
    val listed = lines(c, j05, BlockingJob.OversizeBlocks).map(ujson.read(_))
    assertEquals(
      listed.map(o => (o("blockKey").str, o("size").num.toLong, o("limit").num.toLong)).toSet,
      model.oversize.map { case (k, ms) => (k, ms.size.toLong, Cap.toLong) }.toSet
    )
    assertEquals(listed.size, model.oversize.size)
    val om = lines(c, j05, BlockingJob.OversizeMembers).map(read[OversizeMember](_))
    assertEquals(om.size, model.oversizeMembers.size, "an oversize member written twice")
    assertEquals(om.map(m => m.blockKey -> m.member.mentionId).toSet, model.oversizeMembers)
    assertEquals(j05.main(Counters.BlocksEmitted), model.emitted.size.toLong)
    assertEquals(j05.main(Counters.SingletonBlocks), model.singletons.toLong)
    assertEquals(j05.main(Counters.OversizeBlock), model.oversize.size.toLong)
    assertEquals(j05.stats("largestBlock").value, 120L)
  }

  // ---------------------------------------------------------------------------------------------
  // J05bp, J05b, J05c
  // ---------------------------------------------------------------------------------------------

  test("J05bp lists the still-oversize fallback groups (fallback cap + 1 listed, the cap not)") {
    val p = main.j05bp
    assertEquals(p.jobName, FallbackBlockSizeJob.JobName)
    val listed = keysOf(p, FallbackBlockSizeJob.OversizeKeys)
    val venues = listed.filter(_.family == VenueFamily).map(_.keyFields(1)).distinct
    assertEquals(venues.size, 1, venues.toString)
    val actual = listed.map { k =>
      val fk = (k.family, k.keyFields.head, if (k.family == VenueFamily) V else k.keyFields(1))
      assertEquals(k.blockKey, FallbackKeys.blockKey(k.keyFields.head, k.keyFields.tail))
      assertEquals(k.limit, FallbackCap)
      fk -> k.size
    }
    assertEquals(actual.toMap, model.still.map { case (fk, ms) => fk -> ms.size.toLong })
    assertEquals(actual.size, model.still.size)
    assert(actual.exists(_._1 == (CoauthorFamily, "Fall Edge", "Edge Four")))
    assert(!actual.exists(_._1 == (CoauthorFamily, "Fall Edge", "Edge Three")))
    assertEquals(p.main(Counters.BlockKeysCounted), model.fallback.size.toLong)
    assertEquals(p.main(Counters.OversizeKeysFound), model.still.size.toLong)
  }

  test(
    "J05b still-oversize groups: counted, listed, never formed (no member reaches a reducer); " +
      "fallback groups at the cap emitted; no reduce group above the fallback cap"
  ) {
    val f = main.j05b
    val fb = blocks(f, FallbackBlockingJob.Blocks)
    assertEquals(
      fb.map(b => fkeyOf(b.family, b.blockKey) -> b.members.map(_.mentionId).sorted).toMap,
      model.fbEmitted.map { case (fk, ms) => fk -> ms.map(_.id).sorted }
    )
    fb.foreach(b => assert(b.size <= FallbackCap, s"${b.blockKey}: ${b.size}"))
    val listed = lines(c, f, FallbackBlockingJob.OversizeBlocks).map(ujson.read(_))
    assertEquals(
      listed.map(o => fkeyOf(o("family").str, o("blockKey").str) -> o("size").num.toLong).toMap,
      model.still.map { case (fk, ms) => fk -> ms.size.toLong }
    )
    listed.foreach(o => assertEquals(o("limit").num.toLong, FallbackCap.toLong, o.toString))
    val stillValues = model.still.values.map(_.size.toLong).sum
    assertEquals(f.main(Counters.StillOversizeMembers), stillValues)
    assertEquals(f.main(Counters.StillOversizeBlocks), model.still.size.toLong)
    assertEquals(f.main(Counters.OversizeBlock), model.still.size.toLong)
    assertEquals(f.main(Counters.ReducerCappedBlocks), 0L)
    assertEquals(f.main(Counters.RekeyedBlocks), (model.fallback.size - model.still.size).toLong)
    assertEquals(f.stats(FallbackBlockingJob.LargestFallbackBlockStat).value, 120L)
    val g = f.stats(BlockingJob.LargestReduceGroupStat)
    assert(g.value <= FallbackCap.toLong, s"largest J05b reduce group $g exceeds $FallbackCap")
    // the code path before R3 shuffles every value of the 120-member venue group to one call
    assertEquals(legacy.j05b.stats(BlockingJob.LargestReduceGroupStat).value, 120L)
    assertEquals(legacy.j05b.main(Counters.StillOversizeBlocks), 0L)
    assertEquals(legacy.j05b.main(Counters.ReducerCappedBlocks), model.still.size.toLong)
  }

  test("J05c: still-oversize members are covered by another fallback block or listed uncovered") {
    val cc = main.j05c
    val uncovered = lines(c, cc, FallbackCoverageJob.Uncovered).map(read[UncoveredMember](_))
    assertEquals(uncovered.map(u => u.blockKey -> u.mentionId).toSet, model.uncovered)
    assertEquals(uncovered.size, model.uncovered.size, "an uncovered member listed twice")
    assertEquals(cc.main(Counters.CoveredMembers), model.covered.size.toLong)
    assertEquals(cc.main(Counters.UncoveredMembers), model.uncovered.size.toLong)
    // every Skew Hot member sits in a still-oversize venue group AND a mate block at the cap
    assert(model.oversizeMembers.filter(_._1 == "Skew Hot").subsetOf(model.covered))
    // the Edge Four members (fallback cap + 1) have no other block: uncovered, not lost
    val edgeFour = model.fallback((CoauthorFamily, "Fall Edge", "Edge Four")).map(_.id).toSet
    assertEquals(
      uncovered.filter(_.blockKey == "Fall Edge").map(_.mentionId).toSet,
      edgeFour
    )
    val summaries = lines(c, cc, FallbackCoverageJob.Summary).map(read[OversizeKeySummary](_))
    assertEquals(summaries.size, model.oversize.size)
    summaries.foreach { s =>
      assertEquals(s.members, model.oversize(s.blockKey).size.toLong, s.toString)
      assertEquals(s.coveredMembers + s.uncoveredMembers, s.members, s.toString)
    }
  }

  test("pre-pass and reducer-side cap write the same records on the adversarial corpus") {
    def norm(mine: JobResult, other: JobResult, r: JobResult, sub: String): List[String] =
      lines(c, r, sub).map(_.replace(other.runId.value, mine.runId.value)).sorted
    List(BlockingJob.Blocks, BlockingJob.OversizeBlocks, BlockingJob.OversizeMembers).foreach { d =>
      assertEquals(
        norm(main.j05, legacy.j05, main.j05, d),
        norm(main.j05, legacy.j05, legacy.j05, d),
        d
      )
    }
    List(
      FallbackBlockingJob.Blocks,
      FallbackBlockingJob.OversizeBlocks,
      FallbackBlockingJob.Coverage
    ).foreach { d =>
      assertEquals(
        norm(main.j05b, legacy.j05b, main.j05b, d),
        norm(main.j05b, legacy.j05b, legacy.j05b, d),
        d
      )
    }
    List(FallbackCoverageJob.Uncovered, FallbackCoverageJob.Summary).foreach { d =>
      assertEquals(lines(c, main.j05c, d).sorted, lines(c, legacy.j05c, d).sorted, d)
    }
    assertEquals(
      main.j06.main(Counters.CandidatesEmitted),
      legacy.j06.main(Counters.CandidatesEmitted)
    )
  }

  // ---------------------------------------------------------------------------------------------
  // Counters add up
  // ---------------------------------------------------------------------------------------------

  private def pairsOf(n: Long): Long = n * (n - 1) / 2

  /** Counter identities of one stage (no duplicate member copies in these corpora). */
  private def countersAddUp(s: CandidateStage, cf: Configuration, clue: String): Unit = {
    val (p, b, fp, f, cc, j6) = (s.j05p, s.j05, s.j05bp, s.j05b, s.j05c, s.j06)
    def eq(a: Long, e: Long, what: String): Unit = assertEquals(a, e, s"$clue: $what")
    eq(p.main(Counters.Input), b.main(Counters.Input), "J05p input = J05 input")
    eq(p.main(Counters.Accepted), b.main(Counters.Accepted), "J05p accepted = J05 accepted")
    eq(b.main(Counters.Duplicate), 0L, "no duplicates in this corpus")
    eq(
      p.main(Counters.BlockKeysCounted),
      b.main(Counters.BlocksEmitted) + b.main(Counters.SingletonBlocks) +
        b.main(Counters.OversizeBlock),
      "keys counted = emitted + singletons + oversize"
    )
    eq(p.main(Counters.OversizeKeysFound), b.main(Counters.OversizeBlock), "J05p found = J05")
    val listed = lines(cf, b, BlockingJob.OversizeBlocks).map(ujson.read(_))
    val om = lines(cf, b, BlockingJob.OversizeMembers)
    eq(b.main(Counters.OversizeMembersRekeyed), b.main(Counters.OversizeMembers), "re-keyed")
    eq(b.main(Counters.OversizeMembers), om.size.toLong, "oversize member lines")
    eq(listed.map(_("size").num.toLong).sum, om.size.toLong, "listed sizes = members")
    eq(
      p.stats(BlockSizeJob.LargestKeyGroupStat).value,
      b.stats("largestBlock").value,
      "largestKeyGroup = largestBlock"
    )
    eq(b.main(Counters.ReducerCappedBlocks), 0L, "J05 reducer-capped")
    eq(fp.main(Counters.Input), om.size.toLong, "J05bp input")
    eq(f.main(Counters.Input), om.size.toLong, "J05b input")
    eq(
      fp.main(Counters.BlockKeysCounted),
      f.main(Counters.RekeyedBlocks) + f.main(Counters.StillOversizeBlocks),
      "fallback keys counted = re-keyed + still oversize"
    )
    eq(
      f.main(Counters.RekeyedBlocks),
      f.main(Counters.BlocksEmitted) + f.main(Counters.SingletonBlocks),
      "re-keyed = emitted + singletons"
    )
    eq(fp.main(Counters.OversizeKeysFound), f.main(Counters.StillOversizeBlocks), "J05bp found")
    eq(f.main(Counters.StillOversizeBlocks), f.main(Counters.OversizeBlock), "still = oversize")
    eq(f.main(Counters.ReducerCappedBlocks), 0L, "J05b reducer-capped")
    val fbListed = lines(cf, f, FallbackBlockingJob.OversizeBlocks).map(ujson.read(_))
    val fb = lines(cf, f, FallbackBlockingJob.Blocks).map(read[CandidateBlock](_))
    eq(
      fbListed.map(_("size").num.toLong).sum,
      f.main(Counters.StillOversizeMembers),
      "listed fallback sizes = still-oversize members"
    )
    eq(
      f.main(Counters.FallbackKeysEmitted),
      fb.map(_.size.toLong).sum + f.main(Counters.SingletonBlocks) +
        f.main(Counters.StillOversizeMembers),
      "fallback keys emitted = block members + singletons + still-oversize members"
    )
    eq(
      cc.main(Counters.CoveredMembers) + cc.main(Counters.UncoveredMembers),
      b.main(Counters.OversizeMembers),
      "covered + uncovered = oversize members"
    )
    val primary = lines(cf, b, BlockingJob.Blocks).map(read[CandidateBlock](_))
    eq(
      j6.main(Counters.CandidatesEmitted),
      (primary ++ fb).map(x => pairsOf(x.size.toLong)).sum,
      "J06 candidates = pairs of emitted blocks"
    )
    eq(j6.main(Counters.OversizeBlock), 0L, "J06 guard")
    s.all.foreach(r => eq(identity(r)._1, identity(r)._2, s"map identity ${r.jobName}"))
  }

  test("counters add up (one family, caps 5 / 3)") {
    countersAddUp(main, c, "main")
    // on the code path before R3 the J05bp identity cannot hold: nothing is still oversize
    assertEquals(legacy.j05b.main(Counters.StillOversizeBlocks), 0L)
    assert(legacy.j05b.main(Counters.OversizeBlock) > 0L)
  }

  test("counters add up (two families, three secondary kinds, caps 4 / 2)") {
    val c2 = conf(
      work,
      Settings.Keys.BlockingFamilies -> "dblp-base-name,initials-surname",
      Settings.Keys.FallbackFamilies -> "dblp-base-name,initials-surname",
      Settings.Keys.FallbackSecondaryKeys -> "coauthor,venue,coauthor-venue",
      Settings.Keys.BlockingMaxBlock -> "4",
      Settings.Keys.FallbackMaxBlock -> "2",
      Settings.Keys.BlockingReducers -> "2",
      Settings.Keys.FallbackReducers -> "5"
    )
    val s = candidateStage(c2, contexts, ch.teams.runId, work.resolve("out-dual"), Snapshot)
    assert(s.j05.main(Counters.OversizeBlock) >= 30L, s.j05.counters.toString)
    assert(s.j05b.main(Counters.StillOversizeBlocks) > 0L)
    assert(s.j05b.main(Counters.RekeyedBlocks) > 0L)
    countersAddUp(s, c2, "dual")
    assert(
      s.j05.stats(BlockingJob.LargestReduceGroupStat).value <= effectiveCap(
        c2,
        Settings.Keys.BlockingMaxBlock
      )
    )
  }

  // ---------------------------------------------------------------------------------------------
  // Determinism across input order and split layouts
  // ---------------------------------------------------------------------------------------------

  private lazy val contextLines: List[String] = JobRunner.readPartLines(c, contexts)

  /** Writes `files` under a fresh directory and returns it. */
  private def layout(name: String, files: List[(String, List[String])]): Path = {
    val d = work.resolve(s"layout-$name")
    files.foreach { case (n, ls) => write(d, n, ls.mkString("\n") + "\n") }
    hpath(d)
  }

  /** Every `part-r-*` file below each job's output directory, with `run` IDs normalised. */
  private def reduceBytes(r: JobResult, mine: JobResult): Map[String, String] = {
    val root = local(r.outputDir)
    Files
      .walk(root)
      .iterator()
      .asScala
      .filter(p => Files.isRegularFile(p) && p.getFileName.toString.startsWith("part-r-"))
      .map(p =>
        root.relativize(p).toString ->
          new String(Files.readAllBytes(p), "UTF-8").replace(r.runId.value, mine.runId.value)
      )
      .toMap
  }

  private def chainOn(in: Path, root: String, cf: Configuration = c) =
    BlockingChain.run(cf, in, ch.teams.runId, hpath(work.resolve(root)), Snapshot)

  private lazy val smallSplits: Configuration =
    conf(work, (settings :+ ("mapreduce.input.fileinputformat.split.maxsize" -> "8192"))*)

  private lazy val base = chainOn(contexts, "out-det-base")

  private lazy val variants = {
    val rnd = new scala.util.Random(20261124L)
    val shuffled = rnd.shuffle(contextLines)
    List(
      "reversed" -> chainOn(
        layout("reversed", List("all.jsonl" -> contextLines.reverse)),
        "out-rev"
      ),
      "five-files" -> chainOn(
        layout(
          "five",
          shuffled.zipWithIndex.groupBy(_._2 % 5).toList.map { case (i, xs) =>
            s"f$i.jsonl" -> xs.map(_._1)
          }
        ),
        "out-five"
      ),
      "small-splits" -> chainOn(
        layout("small", List("all.jsonl" -> shuffled)),
        "out-small",
        smallSplits
      )
    )
  }

  /** Every reduce output file, `stats/` included with its `largestReduceGroup` line, is compared
    * byte for byte (after the run-ID substitution); no line is filtered out.
    */
  test(
    "deterministic: reversed input, shuffled files and small splits give identical records, " +
      "listings, counters and size stats"
  ) {
    val total = base.all.map(r => reduceBytes(r, r).size).sum
    assert(total > 0, "no reduce output at all")
    // the byte comparison must actually cover the largestReduceGroup stat of J05 and J05b
    val statKey = "\"" + BlockingJob.LargestReduceGroupStat + "\""
    List(base.blocking, base.fallbackBlocking).foreach { r =>
      assert(
        reduceBytes(r, r).exists { case (rel, s) =>
          rel.startsWith("stats/") && s.contains(statKey)
        },
        s"${r.jobName}: no stats/ part file carries $statKey"
      )
    }
    // collect every differing file before failing
    val diffs = variants.flatMap { case (name, v) =>
      base.all.zip(v.all).flatMap { case (x, y) =>
        assert(!y.reused, s"$name ${y.jobName} reused")
        assertEquals(y.runId, x.runId, s"$name ${x.jobName}")
        assertEquals(y.main(Counters.Accepted), x.main(Counters.Accepted), s"$name ${x.jobName}")
        val bx = reduceBytes(x, x)
        val by = reduceBytes(y, x)
        assertEquals(by.keySet, bx.keySet, s"$name ${x.jobName}")
        List(
          Counters.OversizeBlock,
          Counters.OversizeMembers,
          Counters.StillOversizeBlocks,
          Counters.StillOversizeMembers,
          Counters.BlocksEmitted,
          Counters.SingletonBlocks,
          Counters.OversizeKeysFound,
          Counters.BlockKeysCounted
        ).foreach(n => assertEquals(y.main(n), x.main(n), s"$name ${x.jobName} $n"))
        bx.toList
          .sortBy(_._1)
          .flatMap { case (rel, s) =>
            Option.when(by(rel) != s)(s"$name ${x.jobName} $rel differs")
          }
      }
    }
    assertEquals(diffs, Nil, diffs.mkString("\n"))
  }

  /** The `largestReduceGroup` stat (J05 and J05b) of the four layouts. The production scaladoc
    * (BlockingJob.LargestReduceGroupStat) says that with the pre-pass it never exceeds the job's
    * effective cap. A size-marker group holds one value per map task, so this attacks it with many
    * map tasks (8 KiB splits) and compares the stat across layouts.
    */
  test("largestReduceGroup stays within the cap and does not depend on the map-task layout") {
    val runs = ("base" -> base) :: variants
    val problems = runs.flatMap { case (name, r) =>
      val j05 = r.blocking.stats(BlockingJob.LargestReduceGroupStat)
      val j05b = r.fallbackBlocking.stats(BlockingJob.LargestReduceGroupStat)
      List(
        Option.when(j05.value > Cap)(s"$name: J05 largestReduceGroup $j05 > cap $Cap"),
        Option.when(j05b.value > FallbackCap)(
          s"$name: J05b largestReduceGroup $j05b > fallback cap $FallbackCap"
        )
      ).flatten
    }
    val j05Values = runs.map { case (n, r) =>
      n -> r.blocking.stats(BlockingJob.LargestReduceGroupStat).value
    }
    val j05bValues = runs.map { case (n, r) =>
      n -> r.fallbackBlocking.stats(BlockingJob.LargestReduceGroupStat).value
    }
    val layoutDependent = List(
      Option.when(j05Values.map(_._2).distinct.size > 1)(s"J05 stat by layout: $j05Values"),
      Option.when(j05bValues.map(_._2).distinct.size > 1)(s"J05b stat by layout: $j05bValues")
    ).flatten
    val all = problems ++ layoutDependent
    assertEquals(all, Nil, all.mkString("\n"))
  }

  // ---------------------------------------------------------------------------------------------
  // Refusals and the guard
  // ---------------------------------------------------------------------------------------------

  private def j05With(cf: Configuration, keys: Path, root: String): JobResult =
    JobRunner.run(
      cf,
      BlockingJob.spec(
        contexts,
        hpath(work.resolve(root)),
        Snapshot,
        "sha256:adversarial",
        Some(keys)
      )
    )

  test("a J05p pre-pass computed for another member cap is refused (4 vs 5 and 6 vs 5)") {
    val keys = main.j05p.dir(BlockSizeJob.OversizeKeys)
    List("4", "6").foreach { cap =>
      val cf = conf(work, (settings :+ (Settings.Keys.BlockingMaxBlock -> cap))*)
      val e = intercept[JobFailed](j05With(cf, keys, s"out-refuse-$cap"))
      assert(e.getMessage.nonEmpty)
    }
  }

  test("a pre-pass for the same member cap but another pair limit (effective cap 4) is refused") {
    // maxPairsPerBlock 6 gives an effective cap of 4 under member cap 5: the pre-pass of `main`
    // (limit 5) does not fit
    val cf = conf(work, (settings :+ (Settings.Keys.CandidateMaxPairsPerBlock -> "6"))*)
    assertEquals(effectiveCap(cf, Settings.Keys.BlockingMaxBlock), 4)
    intercept[JobFailed](j05With(cf, main.j05p.dir(BlockSizeJob.OversizeKeys), "out-refuse-pairs"))
  }

  test("a J05bp pre-pass computed for another fallback cap is refused") {
    val cf = conf(work, (settings :+ (Settings.Keys.FallbackMaxBlock -> "2"))*)
    intercept[JobFailed](
      JobRunner.run(
        cf,
        FallbackBlockingJob.spec(
          main.j05.dir(BlockingJob.OversizeMembers),
          hpath(work.resolve("out-refuse-fb")),
          Snapshot,
          "sha256:adversarial",
          Some(main.j05bp.dir(FallbackBlockSizeJob.OversizeKeys))
        )
      )
    )
  }

  test("a corrupt or wrong-version oversize-key list is refused, never read as empty") {
    val bad = work.resolve("bad-keys")
    write(bad, "part-r-00000", "{not json}\n")
    intercept[JobFailed](j05With(c, hpath(bad), "out-refuse-corrupt"))
    val v2 = work.resolve("v2-keys")
    write(
      v2,
      "part-r-00000",
      upickle.default.write(OversizeKey(2, Family, List("Skew Hot"), "Skew Hot", 120L, Cap)) + "\n"
    )
    intercept[JobFailed](j05With(c, hpath(v2), "out-refuse-v2"))
  }

  test("a pre-pass that misses an oversize key: the guard counts it, records stay the same") {
    // a stale list from another input: only Cap Plus is listed, at the right limit
    val stale = work.resolve("stale-keys")
    write(
      stale,
      "part-r-00000",
      upickle.default.write(OversizeKey(1, Family, List("Cap Plus"), "Cap Plus", 6L, Cap)) + "\n"
    )
    val r = j05With(c, hpath(stale), "out-stale")
    assertEquals(r.main(Counters.OversizeMembersRekeyed), 6L)
    assertEquals(r.main(Counters.ReducerCappedBlocks), model.oversize.size.toLong - 1L)
    assertEquals(r.main(Counters.OversizeBlock), model.oversize.size.toLong)
    assertEquals(r.stats(BlockingJob.LargestReduceGroupStat).value, 120L)
    def norm(x: JobResult, sub: String) =
      lines(c, x, sub).map(_.replace(x.runId.value, "RUN")).sorted
    List(BlockingJob.Blocks, BlockingJob.OversizeBlocks, BlockingJob.OversizeMembers).foreach(d =>
      assertEquals(norm(r, d), norm(main.j05, d), d)
    )
  }

  test("BlockingChain records the pre-pass runs as inputs; a changed cap changes every run ID") {
    assertEquals(main.j05p.jobName, BlockSizeJob.JobName)
    assertEquals(main.j05bp.jobName, FallbackBlockSizeJob.JobName)
    val again = candidateStage(c, contexts, ch.teams.runId, work.resolve("out-main"), Snapshot)
    assert(again.all.forall(_.reused), again.all.map(r => r.jobName -> r.reused).toString)
    val c6 = conf(work, (settings :+ (Settings.Keys.BlockingMaxBlock -> "6"))*)
    val other = candidateStage(c6, contexts, ch.teams.runId, work.resolve("out-main"), Snapshot)
    main.all.zip(other.all).foreach { case (x, y) =>
      assertNotEquals(y.runId, x.runId, x.jobName)
      assert(!y.reused, y.jobName)
    }
    // at cap 6 `Cap Plus` (6) is emitted and no longer listed
    assert(!keysOf(other.j05p, BlockSizeJob.OversizeKeys).exists(_.blockKey == "Cap Plus"))
    assert(
      lines(c6, other.j05, BlockingJob.Blocks)
        .map(read[CandidateBlock](_))
        .exists(b => b.blockKey == "Cap Plus" && b.size == 6)
    )
    assertEquals(other.j06.main(Counters.OversizeBlock), 0L)
    assert(lines(c6, other.j06, CandidateFeatureJob.candidatesDir(Family)).nonEmpty)
  }
}
