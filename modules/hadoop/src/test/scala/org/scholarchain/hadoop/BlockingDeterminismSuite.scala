package org.scholarchain.hadoop

import java.nio.file.{Files, Path as JPath, Paths}

import scala.jdk.CollectionConverters.*

import org.apache.hadoop.fs.Path
import org.apache.hadoop.io.{DataOutputBuffer, Text}
import org.scholarchain.core.{CodePointOrder, MentionId, WorkId}
import org.scholarchain.hadoop.blocking.*
import org.scholarchain.hadoop.common.*
import org.scholarchain.hadoop.teams.MentionContext
import upickle.default.{read, write}

/** J05 output bytes do not depend on the order in which reduce values arrive (Phase 5 repair of the
  * test-engineer finding on `oversizeMembers/`): the same mention contexts in different input
  * orders and file layouts give byte-identical `blocks/`, `oversizeBlocks/` and `oversizeMembers/`
  * files.
  */
class BlockingDeterminismSuite extends munit.FunSuite {

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

  // 12 "Wei Wang" mentions (oversize at maxBlockSize 3 in both families), one byte-identical
  // duplicate line, one same-mention copy with different content, and a small emitted block.
  private val lines: List[String] = {
    val big = (1 to 12).toList.map(i =>
      write(context(f"conf/sigir/W$i%02d", "Wei Wang", List(s"Co Author $i")))
    )
    val dup = big(4)
    val variant = write(context("conf/sigir/W07", "Wei Wang", List("Someone Else")))
    val small = List("conf/kdd/S1", "conf/kdd/S2").map(k => write(context(k, "Bo Li", Nil)))
    big ++ List(dup, variant) ++ small
  }

  private val settings = List(
    Settings.Keys.BlockingFamilies -> "dblp-base-name,initials-surname",
    Settings.Keys.BlockingMaxBlock -> "3",
    Settings.Keys.BlockingReducers -> "2"
  )

  /** Runs J05 on `files` (name -> lines) under a fresh work directory and output root. */
  private def runJ05(tag: String, files: List[(String, List[String])]): (JobResult, JPath) = {
    val work = TestSupport.tempDir(s"j05-order-$tag")
    files.foreach { case (n, ls) =>
      TestSupport.writeFile(work, s"in/$n", ls.mkString("\n") + "\n")
    }
    val conf = TestSupport.localConf(work, settings*)
    val r = JobRunner.run(
      conf,
      BlockingJob.spec(
        new Path(work.resolve("in").toUri),
        new Path(work.resolve("out").toUri),
        "det",
        "sha256:j05-order-test"
      )
    )
    r -> work
  }

  /** Relative path -> bytes of every part file under the named outputs of `r`. */
  private def partBytes(r: JobResult): Map[String, Vector[Byte]] = {
    val root = Paths.get(r.outputDir.toUri)
    List(BlockingJob.Blocks, BlockingJob.OversizeBlocks, BlockingJob.OversizeMembers).flatMap {
      sub =>
        val d = root.resolve(sub)
        if (!Files.exists(d)) Nil
        else
          Files
            .list(d)
            .iterator()
            .asScala
            .filter(_.getFileName.toString.startsWith("part-"))
            .map(p => s"$sub/${p.getFileName}" -> Files.readAllBytes(p).toVector)
            .toList
    }.toMap
  }

  test("J05 output bytes are identical whatever the value arrival order") {
    val (a, _) = runJ05("a", List("contexts.jsonl" -> lines))
    val (b, _) = runJ05("b", List("contexts.jsonl" -> lines.reverse))
    // several map tasks: the merge order of map outputs differs again
    val (c, _) = runJ05(
      "c",
      List(
        "x.jsonl" -> lines.zipWithIndex.collect { case (l, i) if i % 3 == 0 => l }.reverse,
        "y.jsonl" -> lines.zipWithIndex.collect { case (l, i) if i % 3 == 1 => l },
        "z.jsonl" -> lines.zipWithIndex.collect { case (l, i) if i % 3 == 2 => l }.reverse
      )
    )
    assertEquals(a.runId, b.runId)
    assertEquals(a.runId, c.runId)
    val pa = partBytes(a)
    assert(pa.keys.exists(_.startsWith(BlockingJob.OversizeMembers)), pa.keys)
    List(b, c).foreach { o =>
      val po = partBytes(o)
      assertEquals(po.keySet, pa.keySet)
      pa.foreach { case (f, bytes) => assert(po(f) == bytes, s"$f differs") }
    }
    assertEquals(a.counters, b.counters)
    assertEquals(a.counters, c.counters)
    assertEquals(a.main(Counters.OversizeMembers), 28L) // (12 + 2 copies) in each of 2 families
  }

  test("J05 oversizeMembers/ lines are sorted by family, block key, mention ID, then content") {
    val (a, work) = runJ05("sorted", List("contexts.jsonl" -> lines.reverse))
    val conf = TestSupport.localConf(work, settings*)
    val dir = a.dir(BlockingJob.OversizeMembers)
    val fs = dir.getFileSystem(conf)
    fs.listStatus(dir).map(_.getPath).filter(_.getName.startsWith("part-")).foreach { p =>
      val ls = JobRunner.readLines(fs, p)
      val keys = ls.map { l =>
        val m = read[OversizeMember](l)
        BlockingSort.key(m.family, m.blockKey, m.member.mentionId, write(m.member))
      }
      assertEquals(keys, keys.sorted(CodePointOrder.ordering), p.toString)
    }
  }

  test("two-field grouping comparator: raw and object comparison agree, suffix ignored") {
    val cmp = new TwoFieldPrefixGroupingComparator
    def ser(s: String): Array[Byte] = {
      val out = new DataOutputBuffer()
      new Text(s).write(out)
      java.util.Arrays.copyOf(out.getData, out.getLength)
    }
    val keys = List(
      "dblp-base-name\tWei Wang\tm1\th1",
      "dblp-base-name\tWei Wang\tm2\th0",
      "dblp-base-name\tWei Wang 2\tm1\th1",
      "dblp-base-name\tWei\tm1\th1",
      "initials-surname\tw wang\tm1\th1",
      "nofields",
      "one\tfield",
      "Ünïcode\tnämé\tx"
    )
    for { x <- keys; y <- keys } {
      val bx = ser(x)
      val by = ser(y)
      val raw = Integer.signum(cmp.compare(bx, 0, bx.length, by, 0, by.length))
      val obj = Integer.signum(cmp.compare(new Text(x), new Text(y)))
      val expected = Integer.signum(
        new Text(TwoFieldPrefixPartitioner.prefix(x))
          .compareTo(new Text(TwoFieldPrefixPartitioner.prefix(y)))
      )
      assertEquals(raw, expected, s"$x | $y")
      assertEquals(obj, expected, s"$x | $y")
    }
  }
}
