package org.scholarchain.hadoop

import java.nio.file.{Files, Path as JPath, Paths}

import scala.jdk.CollectionConverters.*

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.hadoop.io.{DataOutputBuffer, Text}
import org.scholarchain.core.{MentionId, RunId, WorkId}
import org.scholarchain.hadoop.blocking.*
import org.scholarchain.hadoop.common.*
import org.scholarchain.hadoop.pipeline.{BlockingChain, BlockingChainResult}
import org.scholarchain.hadoop.teams.MentionContext
import upickle.default.{read, write}

/** Phase 17 R3 (check HADOOP-04): the oversize-block strategy. The counting pre-passes (J05p,
  * J05bp) find oversize keys before J05 and J05b run; J05 re-keys oversize members by mention ID
  * and J05b applies the still-oversize policy in the mapper, so no reduce group of either job holds
  * more values than its cap. Records are those of the reducer-side cap.
  *
  * Hand-checkable fixture (one family, dblp-base-name; J05 cap 3; J05b cap 2; secondary key
  * coauthor):
  * {{{
  *   Wei Wang  W1 [Ann Lee, Cy Do]   W2 [Ann Lee]   W3 [Ann Lee]   W4 [Bo Li]   W5 [Bo Li]
  *   Bo Li     B1, B2                Cy Do  C1
  * }}}
  *   - J05p: 3 keys counted; "Wei Wang" (5 > 3) is the one oversize key.
  *   - J05: block "Bo Li" (2) emitted, "Cy Do" singleton, "Wei Wang" oversize: its 5 members are
  *     re-keyed, listed once in oversizeBlocks/ (size 5, limit 3).
  *   - J05bp over the 5 oversize members: groups Ann Lee (W1, W2, W3), Bo Li (W4, W5), Cy Do (W1);
  *     Ann Lee (3 > 2) is still oversize.
  *   - J05b: Bo Li emitted (W4, W5), Cy Do singleton, Ann Lee not formed (still-oversize policy: 3
  *     values counted, never shuffled, listed with size 3, limit 2).
  *   - J05c: W4, W5 covered; W1, W2, W3 uncovered.
  */
class OversizeBlockStrategySuite extends munit.FunSuite {

  override val munitTimeout = scala.concurrent.duration.Duration(600, "s")

  private def context(key: String, name: String, coauthors: List[String]): MentionContext = {
    val w = WorkId.fromDblpKey(key)
    MentionContext(
      1,
      MentionId.of(w, 0).value,
      w.value,
      key,
      0,
      name,
      None,
      2020,
      Some("conf/sigir"),
      None,
      "t",
      coauthors.size + 1,
      coauthors,
      false,
      "dblp",
      "s"
    )
  }

  private def mention(key: String) = MentionId.of(WorkId.fromDblpKey(key), 0).value

  private val lines: List[String] = List(
    context("conf/sigir/W1", "Wei Wang", List("Ann Lee", "Cy Do")),
    context("conf/sigir/W2", "Wei Wang", List("Ann Lee")),
    context("conf/sigir/W3", "Wei Wang", List("Ann Lee")),
    context("conf/sigir/W4", "Wei Wang", List("Bo Li")),
    context("conf/sigir/W5", "Wei Wang", List("Bo Li")),
    context("conf/kdd/B1", "Bo Li", Nil),
    context("conf/kdd/B2", "Bo Li", Nil),
    context("conf/kdd/C1", "Cy Do", Nil)
  ).map(write(_))

  private val settings = List(
    Settings.Keys.BlockingFamilies -> "dblp-base-name",
    Settings.Keys.BlockingMaxBlock -> "3",
    Settings.Keys.FallbackFamilies -> "dblp-base-name",
    Settings.Keys.FallbackSecondaryKeys -> "coauthor",
    Settings.Keys.FallbackMaxBlock -> "2",
    Settings.Keys.BlockingReducers -> "2",
    Settings.Keys.FallbackReducers -> "2"
  )

  // a stand-in for the J07 run that wrote the contexts (any well-formed run ID)
  private val teamsRun: RunId =
    RunId
      .of("hadoop.j07-publication-teams", "test", "sha256:fixture", "sha256:fixture")
      .toOption
      .get

  private def writeInputs(work: JPath, files: List[(String, List[String])]): Path = {
    files.foreach { case (n, ls) =>
      TestSupport.writeFile(work, s"in/$n", ls.mkString("\n") + "\n")
    }
    new Path(work.resolve("in").toUri)
  }

  /** The chain (with pre-passes) over `files`. */
  private def chain(
      tag: String,
      files: List[(String, List[String])],
      extra: List[(String, String)] = Nil
  ): (BlockingChainResult, Configuration) = {
    val work = TestSupport.tempDir(s"oversize-$tag")
    val in = writeInputs(work, files)
    val conf = TestSupport.localConf(work, (settings ++ extra)*)
    BlockingChain.run(conf, in, teamsRun, new Path(work.resolve("out").toUri), "os") -> conf
  }

  private lazy val first = chain("a", List("contexts.jsonl" -> lines))

  /** J05 and J05b without the pre-pass (reducer-side cap only), for comparison. */
  private lazy val withoutPrePass: (JobResult, JobResult, Configuration) = {
    val work = TestSupport.tempDir("oversize-nopre")
    val in = writeInputs(work, List("contexts.jsonl" -> lines))
    val conf = TestSupport.localConf(work, settings*)
    val out = new Path(work.resolve("out").toUri)
    val j05 = JobRunner.run(conf, BlockingJob.spec(in, out, "os", "sha256:nopre"))
    val j05b = JobRunner.run(
      conf,
      JobRunner.fromRuns(List(j05.runId))(
        FallbackBlockingJob.spec(j05.dir(BlockingJob.OversizeMembers), out, "os", _)
      )
    )
    (j05, j05b, conf)
  }

  private def sortedLines(conf: Configuration, r: JobResult, sub: String): List[String] =
    TestSupport.lines(conf, r, sub).sorted

  test("J05p: counts every primary key; only the oversize key is listed, with size and limit") {
    val (c, conf) = first
    val p = c.blockSizes
    assertEquals(p.jobName, "hadoop.j05p-block-sizes")
    assertEquals(p.main(Counters.Input), 8L)
    assertEquals(p.main(Counters.Accepted), 8L)
    assertEquals(p.main(Counters.BlockKeysCounted), 3L)
    assertEquals(p.main(Counters.OversizeKeysFound), 1L)
    assertEquals(
      TestSupport.lines(conf, p, BlockSizeJob.OversizeKeys).map(read[OversizeKey](_)),
      List(OversizeKey(1, "dblp-base-name", List("Wei Wang"), "Wei Wang", 5L, 3))
    )
    assertEquals(p.stats(BlockSizeJob.LargestKeyGroupStat).value, 5L)
  }

  test("J05 with the pre-pass: oversize members re-keyed, no reduce group above the cap") {
    val (c, conf) = first
    val b = c.blocking
    assertEquals(b.main(Counters.Accepted), 8L)
    assertEquals(b.main(Counters.OversizeMembersRekeyed), 5L)
    assertEquals(b.main(Counters.OversizeMembers), 5L)
    assertEquals(b.main(Counters.OversizeBlock), 1L)
    assertEquals(b.main(Counters.ReducerCappedBlocks), 0L)
    assertEquals(b.main(Counters.BlocksEmitted), 1L)
    assertEquals(b.main(Counters.SingletonBlocks), 1L)
    assertEquals(b.counter(Counters.OversizePerFamilyGroup, "dblp-base-name"), 1L)
    // the defining property: the largest reduce call read at most the cap (3) values
    val g = b.stats(BlockingJob.LargestReduceGroupStat)
    assert(g.value <= 3L, g)
    assertEquals(b.stats("largestBlock").value, 5L)
    assertEquals(b.stats("largestBlock").subject, "dblp-base-name\tWei Wang")
    val blocks = TestSupport.lines(conf, b, BlockingJob.Blocks).map(read[CandidateBlock](_))
    assertEquals(blocks.map(x => (x.blockKey, x.size)), List("Bo Li" -> 2))
    val listed = TestSupport.lines(conf, b, BlockingJob.OversizeBlocks).map(ujson.read(_))
    assertEquals(listed.size, 1)
    assertEquals(listed.head("blockKey").str, "Wei Wang")
    assertEquals(listed.head("size").num, 5.0)
    assertEquals(listed.head("limit").num, 3.0)
    val om = TestSupport.lines(conf, b, BlockingJob.OversizeMembers).map(read[OversizeMember](_))
    assertEquals(
      om.map(_.member.mentionId).sorted,
      List("W1", "W2", "W3", "W4", "W5").map(w => mention(s"conf/sigir/$w")).sorted
    )
  }

  test("without the pre-pass the oversize group reaches one reduce call (the defect)") {
    val (j05, j05b, _) = withoutPrePass
    assertEquals(j05.stats(BlockingJob.LargestReduceGroupStat).value, 5L)
    assertEquals(j05.main(Counters.ReducerCappedBlocks), 1L)
    assertEquals(j05b.stats(BlockingJob.LargestReduceGroupStat).value, 3L)
    assertEquals(j05b.main(Counters.ReducerCappedBlocks), 1L)
    assertEquals(j05b.main(Counters.StillOversizeBlocks), 0L)
  }

  test("pre-pass and reducer-side cap write the same records and policy counters") {
    val (c, conf) = first
    val (j05, j05b, conf0) = withoutPrePass
    List(BlockingJob.Blocks, BlockingJob.OversizeBlocks, BlockingJob.OversizeMembers).foreach { d =>
      assertEquals(
        sortedLines(conf, c.blocking, d),
        sortedLines(conf0, j05, d).map(fixRun(c.blocking, j05)),
        d
      )
    }
    List(
      FallbackBlockingJob.Blocks,
      FallbackBlockingJob.OversizeBlocks,
      FallbackBlockingJob.Coverage
    ).foreach { d =>
      assertEquals(
        sortedLines(conf, c.fallbackBlocking, d),
        sortedLines(conf0, j05b, d).map(fixRun(c.fallbackBlocking, j05b)),
        d
      )
    }
    List(
      Counters.OversizeBlock,
      Counters.OversizeMembers,
      Counters.BlocksEmitted,
      Counters.SingletonBlocks,
      Counters.Duplicate
    ).foreach(n => assertEquals(c.blocking.main(n), j05.main(n), n))
    List(
      Counters.OversizeBlock,
      Counters.BlocksEmitted,
      Counters.SingletonBlocks,
      Counters.FallbackKeysEmitted
    ).foreach(n => assertEquals(c.fallbackBlocking.main(n), j05b.main(n), n))
  }

  /** Records name their own run ID; replace the other run's ID to compare contents. */
  private def fixRun(mine: JobResult, other: JobResult)(line: String): String =
    line.replace(other.runId.value, mine.runId.value)

  test("J05bp: still-oversize fallback key found before J05b") {
    val (c, conf) = first
    val p = c.fallbackBlockSizes
    assertEquals(p.jobName, "hadoop.j05bp-fallback-block-sizes")
    assertEquals(p.main(Counters.Accepted), 5L)
    assertEquals(p.main(Counters.BlockKeysCounted), 3L)
    assertEquals(p.main(Counters.OversizeKeysFound), 1L)
    assertEquals(
      TestSupport.lines(conf, p, FallbackBlockSizeJob.OversizeKeys).map(read[OversizeKey](_)),
      List(
        OversizeKey(
          1,
          "dblp-base-name.coauthor",
          List("Wei Wang", "Ann Lee"),
          FallbackKeys.blockKey("Wei Wang", List("Ann Lee")),
          3L,
          2
        )
      )
    )
  }

  test("J05b still-oversize policy: group not formed, counted, listed; members uncovered") {
    val (c, conf) = first
    val f = c.fallbackBlocking
    assertEquals(f.main(Counters.Accepted), 5L)
    assertEquals(f.main(Counters.FallbackKeysEmitted), 6L)
    assertEquals(f.main(Counters.StillOversizeMembers), 3L)
    assertEquals(f.main(Counters.StillOversizeBlocks), 1L)
    assertEquals(f.main(Counters.OversizeBlock), 1L)
    assertEquals(f.main(Counters.RekeyedBlocks), 2L)
    assertEquals(f.main(Counters.BlocksEmitted), 1L)
    assertEquals(f.main(Counters.SingletonBlocks), 1L)
    assertEquals(f.main(Counters.ReducerCappedBlocks), 0L)
    assert(f.stats(BlockingJob.LargestReduceGroupStat).value <= 2L)
    assertEquals(f.stats(FallbackBlockingJob.LargestFallbackBlockStat).value, 3L)
    val blocks = TestSupport.lines(conf, f, FallbackBlockingJob.Blocks).map(read[CandidateBlock](_))
    assertEquals(blocks.map(_.blockKey), List(FallbackKeys.blockKey("Wei Wang", List("Bo Li"))))
    assertEquals(
      blocks.head.members.map(_.mentionId),
      List(mention("conf/sigir/W4"), mention("conf/sigir/W5")).sorted
    )
    val listed = TestSupport.lines(conf, f, FallbackBlockingJob.OversizeBlocks).map(ujson.read(_))
    assertEquals(listed.size, 1)
    assertEquals(listed.head("blockKey").str, FallbackKeys.blockKey("Wei Wang", List("Ann Lee")))
    assertEquals(listed.head("size").num, 3.0)
    assertEquals(listed.head("limit").num, 2.0)
    val cc = c.fallbackCoverage
    assertEquals(cc.main(Counters.CoveredMembers), 2L)
    assertEquals(cc.main(Counters.UncoveredMembers), 3L)
    assertEquals(
      TestSupport
        .lines(conf, cc, FallbackCoverageJob.Uncovered)
        .map(read[UncoveredMember](_).mentionId)
        .sorted,
      List("W1", "W2", "W3").map(w => mention(s"conf/sigir/$w")).sorted
    )
  }

  test("deterministic: input order and file layout do not change any output byte") {
    val (a, _) = first
    val (b, _) = chain("b", List("contexts.jsonl" -> lines.reverse))
    val (c, _) = chain(
      "c",
      List(
        "x.jsonl" -> lines.zipWithIndex.collect { case (l, i) if i % 3 == 0 => l }.reverse,
        "y.jsonl" -> lines.zipWithIndex.collect { case (l, i) if i % 3 == 1 => l },
        "z.jsonl" -> lines.zipWithIndex.collect { case (l, i) if i % 3 == 2 => l }
      )
    )
    assertEquals(a.all.map(_.runId), b.all.map(_.runId))
    assertEquals(a.all.map(_.runId), c.all.map(_.runId))
    val reduceSide = Map(
      a.blockSizes -> List(BlockSizeJob.OversizeKeys),
      a.blocking -> List(
        BlockingJob.Blocks,
        BlockingJob.OversizeBlocks,
        BlockingJob.OversizeMembers
      ),
      a.fallbackBlockSizes -> List(FallbackBlockSizeJob.OversizeKeys),
      a.fallbackBlocking -> List(
        FallbackBlockingJob.Blocks,
        FallbackBlockingJob.OversizeBlocks,
        FallbackBlockingJob.Coverage
      ),
      a.fallbackCoverage -> List(FallbackCoverageJob.Uncovered, FallbackCoverageJob.Summary)
    )
    def bytes(r: JobResult, subs: List[String]): Map[String, Vector[Byte]] = {
      val root = Paths.get(r.outputDir.toUri)
      subs.flatMap { s =>
        val d = root.resolve(s)
        if (!Files.exists(d)) Nil
        else
          Files
            .list(d)
            .iterator()
            .asScala
            .toList
            .filter(_.getFileName.toString.startsWith("part-"))
            .map(p => s"$s/${p.getFileName}" -> Files.readAllBytes(p).toVector)
      }.toMap
    }
    a.all.zip(b.all).zip(c.all).foreach { case ((x, y), z) =>
      val subs = reduceSide(x)
      val bx = bytes(x, subs)
      assert(bx.nonEmpty || x == a.fallbackCoverage, x.jobName)
      assertEquals(bytes(y, subs), bx, x.jobName)
      assertEquals(bytes(z, subs), bx, x.jobName)
      assertEquals(y.main(Counters.OversizeBlock), x.main(Counters.OversizeBlock), x.jobName)
    }
    // re-keyed members sit sorted by family, key, mention ID, content in every part file
    val om = Paths.get(a.blocking.dir(BlockingJob.OversizeMembers).toUri)
    Files.list(om).iterator().asScala.filter(_.getFileName.toString.startsWith("part-")).foreach {
      p =>
        val keys = Files.readAllLines(p).asScala.toList.map { l =>
          val m = read[OversizeMember](l)
          BlockingSort.key(m.family, m.blockKey, m.member.mentionId, write(m.member))
        }
        assertEquals(keys, keys.sorted, p.toString)
    }
  }

  /** Every `part-*` file under `subs` of `r`, by relative path. */
  private def partBytes(r: JobResult, subs: List[String]): Map[String, Vector[Byte]] = {
    val root = Paths.get(r.outputDir.toUri)
    subs.flatMap { s =>
      val d = root.resolve(s)
      if (!Files.exists(d)) Nil
      else
        Files
          .list(d)
          .iterator()
          .asScala
          .toList
          .filter(_.getFileName.toString.startsWith("part-"))
          .map(p => s"$s/${p.getFileName}" -> Files.readAllBytes(p).toVector)
    }.toMap
  }

  test(
    "largestReduceGroup counts member values only: within the cap and identical stats/ when " +
      "many map tasks carry one oversize key"
  ) {
    val (a, _) = first
    // one input file per line: J05 runs 8 map tasks, 5 of them carry "Wei Wang" (5 size markers
    // for a cap of 3); tiny splits give J05b one map task per oversize member line, so the
    // still-oversize group "Wei Wang / Ann Lee" gets one marker per member (3 for a cap of 2)
    val (m, _) = chain(
      "many-maps",
      lines.zipWithIndex.map { case (l, i) => s"c$i.jsonl" -> List(l) },
      List("mapreduce.input.fileinputformat.split.maxsize" -> "128")
    )
    assertEquals(m.all.map(_.runId), a.all.map(_.runId))
    List(m.blocking -> 3L, m.fallbackBlocking -> 2L).foreach { case (r, cap) =>
      val g = r.stats(BlockingJob.LargestReduceGroupStat)
      assert(g.value <= cap, s"${r.jobName}: $g exceeds the cap $cap")
    }
    a.all.zip(m.all).foreach { case (x, y) =>
      assertEquals(
        partBytes(y, List(TaskIO.StatsDir)),
        partBytes(x, List(TaskIO.StatsDir)),
        s"${x.jobName} stats/"
      )
      assertEquals(y.stats, x.stats, x.jobName)
    }
    List(
      Counters.Accepted,
      Counters.OversizeBlock,
      Counters.OversizeMembers,
      Counters.OversizeMembersRekeyed,
      Counters.StillOversizeBlocks,
      Counters.StillOversizeMembers,
      Counters.BlocksEmitted,
      Counters.SingletonBlocks,
      Counters.ReducerCappedBlocks
    ).foreach { n =>
      assertEquals(m.blocking.main(n), a.blocking.main(n), s"J05 $n")
      assertEquals(m.fallbackBlocking.main(n), a.fallbackBlocking.main(n), s"J05b $n")
    }
    val records = List(
      m.blocking -> List(
        BlockingJob.Blocks,
        BlockingJob.OversizeBlocks,
        BlockingJob.OversizeMembers
      ),
      m.fallbackBlocking -> List(
        FallbackBlockingJob.Blocks,
        FallbackBlockingJob.OversizeBlocks,
        FallbackBlockingJob.Coverage
      )
    )
    records.zip(List(a.blocking, a.fallbackBlocking)).foreach { case ((y, subs), x) =>
      assertEquals(partBytes(y, subs), partBytes(x, subs), x.jobName)
    }
  }

  test("a pre-pass computed for another cap is refused") {
    val (c, _) = first
    val work = TestSupport.tempDir("oversize-mismatch")
    val in = writeInputs(work, List("contexts.jsonl" -> lines))
    val conf = TestSupport.localConf(work, (settings :+ (Settings.Keys.BlockingMaxBlock -> "4"))*)
    intercept[JobFailed](
      JobRunner.run(
        conf,
        BlockingJob.spec(
          in,
          new Path(work.resolve("out").toUri),
          "os",
          "sha256:mismatch",
          Some(c.blockSizes.dir(BlockSizeJob.OversizeKeys))
        )
      )
    )
  }

  test("J05 keys: partition and grouping of block members, re-keyed members and size markers") {
    val member = "{}"
    val b1 = BlockingSort.key("dblp-base-name", "Wei Wang", "m1", member)
    val b2 = BlockingSort.key("dblp-base-name", "Wei Wang", "m2", member)
    val r1 = BlockingSort.memberKey("dblp-base-name", "Wei Wang", "m1", member)
    val r1b = BlockingSort.memberKey("dblp-base-name", "Wei Wang", "m1", "{\"x\":1}")
    val r2 = BlockingSort.memberKey("dblp-base-name", "Wei Wang", "m2", member)
    val s = BlockingSort.sizeKey("dblp-base-name", "Wei Wang")
    // block members and the size marker keep the Phase 4 partition of the block
    val blockPartition = StableHashPartitioner.partition("dblp-base-name\tWei Wang", 7)
    List(b1, b2, s).foreach(k =>
      assertEquals(BlockingSort.partitionPrefix(k), "dblp-base-name\tWei Wang", k)
    )
    val part = new BlockingPartitioner
    assertEquals(part.getPartition(new Text(b1), new Text(""), 7), blockPartition)
    // re-keyed members partition by mention: spread over reduce tasks
    assertEquals(BlockingSort.partitionPrefix(r1), "dblp-base-name\tWei Wang\tm1")
    val cmp = new BlockingGroupingComparator
    def ser(x: String): Array[Byte] = {
      val out = new DataOutputBuffer()
      new Text(x).write(out)
      java.util.Arrays.copyOf(out.getData, out.getLength)
    }
    def same(x: String, y: String): Boolean = {
      val (bx, by) = (ser(x), ser(y))
      val raw = cmp.compare(bx, 0, bx.length, by, 0, by.length) == 0
      val obj = cmp.compare(new Text(x), new Text(y)) == 0
      assertEquals(raw, obj, s"$x | $y")
      raw
    }
    assert(same(b1, b2)) // one block, one group
    assert(same(r1, r1b)) // copies of one mention
    assert(!same(r1, r2)) // two mentions: two groups
    assert(!same(r1, b1))
    assert(!same(s, b1))
    assert(!same(s, r1))
    // group order agrees with the sort order of the keys
    val keys = List(b1, b2, r1, r1b, r2, s).sorted
    keys.zip(keys.tail).foreach { case (x, y) =>
      val (bx, by) = (ser(x), ser(y))
      assert(cmp.compare(bx, 0, bx.length, by, 0, by.length) <= 0, s"$x | $y")
    }
  }
}
