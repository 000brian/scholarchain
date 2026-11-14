package org.scholarchain.hadoop

import org.scholarchain.core.*
import org.scholarchain.hadoop.common.{RunContext, StableHashPartitioner}
import org.scholarchain.hadoop.pairs.{ExpandedPair, PairConsolidateJob, PairExpansion, PairIndex}
import org.scholarchain.hadoop.teams.{TeamAssembler, TeamLimits}

class PairsAndTeamsSuite extends munit.FunSuite {

  private val run = RunContext(
    RunId.of("j", "c", "i", "f").toOption.get,
    "hadoop.test",
    "dblp",
    "2026-11-01",
    "dblp-parse/1"
  )

  private def prov(key: String) = run.provenance(SourceType.Dblp, List(key), None)

  private def team(names: List[String], key: String = "conf/sigir/T1") =
    PublicationTeam(1, WorkId.fromDblpKey(key), 2023, names, names.size, prov(key))

  test("pair index enumerates exactly the n(n-1)/2 pairs i<j in row-major order") {
    (0 to 40).foreach { n =>
      val expected = for { i <- 0 until n; j <- i + 1 until n } yield (i, j)
      assertEquals(PairIndex.pairCount(n), expected.size.toLong)
      val got = List.newBuilder[(Int, Int)]
      PairIndex.foreachPair(n, 0, PairIndex.pairCount(n))((i, j) => got += ((i, j)))
      assertEquals(got.result(), expected.toList, s"n=$n")
      expected.zipWithIndex.foreach { case (p, k) =>
        assertEquals(PairIndex.unrank(n, k.toLong), p)
      }
    }
    assertEquals(PairIndex.pairCount(5000), 12497500L)
  }

  test("shards partition the pair range without gaps or overlap") {
    for { n <- List(2, 3, 7, 50, 101); per <- List(1L, 2L, 7L, 100L, 1000000L) } {
      val shards = PairIndex.shardCount(n, per)
      val ranges = (0L until shards).map(s => PairIndex.shardRange(n, per, s))
      assertEquals(ranges.head._1, 0L)
      assertEquals(ranges.last._2, PairIndex.pairCount(n))
      ranges.sliding(2).foreach {
        case Seq(a, b) => assertEquals(a._2, b._1)
        case _         => ()
      }
      val got = List.newBuilder[(Int, Int)]
      ranges.foreach { case (f, u) => PairIndex.foreachPair(n, f, u)((i, j) => got += ((i, j))) }
      assertEquals(got.result().size.toLong, PairIndex.pairCount(n))
    }
    assertEquals(PairIndex.shardCount(1, 10L), 0L)
  }

  test("pairs are canonical (left < right by code point) and carry one position pair") {
    val t = team(List("Zoe", "Ann", "Zoe"))
    val p = PairExpansion.pairAt(t, 0, 1, run).asInstanceOf[ExpandedPair.Candidate].pair
    assertEquals((p.left, p.right), ("Ann", "Zoe"))
    assertEquals(p.schemaVersion, 2)
    // Ann is at position 1 and Zoe at 0: positions follow the canonical names.
    assertEquals(
      p.positions,
      List(PairPosition(1, MentionId.of(t.workId, 1), 0, MentionId.of(t.workId, 0)))
    )
    assertEquals(
      p.positions,
      List(PairPosition.forNames(t.workId, "Zoe", 0, "Ann", 1).toOption.get)
    )
    // positions are no longer encoded in provenance: only the dblp key
    assertEquals(p.provenance.sourceRecordIds, List("conf/sigir/T1"))
    assertEquals(Contract.validate(p), Nil)
    assertEquals(PairExpansion.pairAt(t, 0, 2, run), ExpandedPair.SelfPair("Zoe", 0, 2))
  }

  test("consolidation unions the positions of duplicate instances (sorted, distinct)") {
    val t = team(List("A", "B", "A"))
    val a = PairExpansion.pairAt(t, 0, 1, run).asInstanceOf[ExpandedPair.Candidate].pair
    val b = PairExpansion.pairAt(t, 1, 2, run).asInstanceOf[ExpandedPair.Candidate].pair
    assertEquals(b.positions.map(p => (p.leftPosition, p.rightPosition)), List((2, 1)))
    val merged = PairConsolidateJob.consolidate(List(b, a, b)).toOption.get
    assertEquals(
      merged.positions.map(p => (p.leftPosition, p.rightPosition)),
      List((0, 1), (2, 1))
    )
    assertEquals(merged.provenance, a.provenance)
    assertEquals(Contract.validate(merged), Nil)
    assert(PairConsolidateJob.consolidate(List(a, a.copy(year = 1999))).isLeft)
    // instances whose provenance differs are not merged
    val other = a.copy(provenance = a.provenance.copy(snapshotId = "other"))
    assert(PairConsolidateJob.consolidate(List(a, other)).isLeft)
  }

  test(
    "consolidation with a run context re-stamps provenance with that run, keeping source facts"
  ) {
    val t = team(List("A", "B", "A"))
    val a = PairExpansion.pairAt(t, 0, 1, run).asInstanceOf[ExpandedPair.Candidate].pair
    val b = PairExpansion.pairAt(t, 1, 2, run).asInstanceOf[ExpandedPair.Candidate].pair
    val j08b = RunContext(
      RunId.of("hadoop.j08b-pair-consolidate", "c", "i2", "f").toOption.get,
      PairConsolidateJob.JobName,
      "dblp",
      "2026-11-01",
      "dblp-parse/1"
    )
    val merged = PairConsolidateJob.consolidate(List(b, a), j08b).toOption.get
    assertEquals(merged.provenance.runId, j08b.runId)
    assertEquals(merged.provenance.jobName, "hadoop.j08b-pair-consolidate")
    assertEquals(merged.provenance.sourceRecordIds, a.provenance.sourceRecordIds)
    assertEquals(merged.provenance.sourceType, a.provenance.sourceType)
    assertEquals(merged.provenance.contentHash, a.provenance.contentHash)
    assertEquals(merged.provenance.datasetId, a.provenance.datasetId)
    assertEquals(merged.provenance.snapshotId, a.provenance.snapshotId)
    assertEquals(
      merged.positions,
      PairConsolidateJob.consolidate(List(b, a)).toOption.get.positions
    )
    assertEquals(Contract.validate(merged), Nil)
    assert(PairConsolidateJob.consolidate(List(a, a.copy(year = 1999)), j08b).isLeft)
  }

  test("an indexed team has O(1) name access and expands exactly like the team itself") {
    val names = (0 until 40).map(i => f"Member $i%03d").toList :+ "Member 000"
    val t = team(names)
    val indexed = PairExpansion.index(t)
    assert(indexed.names.isInstanceOf[IndexedSeq[?]])
    assert(!indexed.names.isInstanceOf[List[?]])
    assertEquals(indexed.names.toList, t.authorNames)
    PairIndex.foreachPair(t.teamSize, 0, PairIndex.pairCount(t.teamSize)) { (i, j) =>
      assertEquals(PairExpansion.pairAt(indexed, i, j, run), PairExpansion.pairAt(t, i, j, run))
    }
  }

  test("team assembly: mismatch, orphan, hard limit, oversize flag and coauthor context") {
    val key = "conf/sigir/T2"
    val w = WorkRecord(
      2,
      WorkId.fromDblpKey(key),
      key,
      "inproceedings",
      "t",
      2023,
      Some("conf/sigir"),
      None,
      Nil,
      Nil,
      List("A", "B", "C"),
      prov(key)
    )
    val ms = w.authorNames.zipWithIndex.map { case (n, i) =>
      AuthorMention(1, MentionId.of(w.workId, i), w.workId, i, n, None, prov(key))
    }
    val limits = TeamLimits(oversizeThreshold = 2, hardLimit = 3, maxCoauthorContext = 1)
    val ok = TeamAssembler.assemble(List(w), ms.reverse, limits, run).toOption.get
    assertEquals(ok.team.authorNames, List("A", "B", "C"))
    assert(ok.oversize)
    assertEquals(ok.contexts.map(_.coauthorNames), List(List("B"), List("A"), List("A")))
    assert(ok.contexts.forall(_.coauthorsTruncated))
    assertEquals(
      TeamAssembler.assemble(List(w), ms.take(2), limits, run).left.map(_.reason),
      Left("team_mention_mismatch")
    )
    assertEquals(
      TeamAssembler.assemble(Nil, ms, limits, run).left.map(_.reason),
      Left("orphan_mention")
    )
    assertEquals(
      TeamAssembler.assemble(List(w), ms, limits.copy(hardLimit = 2), run).left.map(_.reason),
      Left("team_exceeds_hard_limit")
    )
  }

  test("stable hash partitioner is deterministic and in range") {
    val keys = (0 until 1000).map(i => s"W|conf/x/$i")
    val parts = keys.map(StableHashPartitioner.partition(_, 7))
    assert(parts.forall(p => p >= 0 && p < 7))
    assertEquals(keys.map(StableHashPartitioner.partition(_, 7)), parts)
    assert(parts.distinct.size == 7)
  }
}
