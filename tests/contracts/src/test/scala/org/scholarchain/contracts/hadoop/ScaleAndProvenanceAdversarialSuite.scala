package org.scholarchain.contracts.hadoop

import scala.collection.mutable

import org.scholarchain.contracts.hadoop.AdversarialSupport.*
import org.scholarchain.hadoop.io.RawRecord
import org.scholarchain.core.{CoauthorPairCandidate, Contract, MentionId, SourceType}
import org.scholarchain.hadoop.blocking.{BlockLimit, BlockMember, BlockingKeys, CandidateBlock}
import org.scholarchain.hadoop.candidates.CandidateFeatures
import org.scholarchain.hadoop.common.{Disposition, Reasons, Settings}
import org.scholarchain.hadoop.pairs.{ExpandedPair, PairConsolidateJob, PairExpansion, PairIndex}
import org.scholarchain.hadoop.teams.{TeamAssembler, TeamLimits}

/** Adversarial scale cases (giant teams, pair sharding, common-name blocks) and the black-box
  * operational requirements reachable without Hadoop: pair provenance and positions, determinism,
  * configuration-driven thresholds, versioned reject records.
  */
class ScaleAndProvenanceAdversarialSuite extends munit.FunSuite {

  private def names(n: Int): List[String] = (0 until n).map(i => f"Synthetic Author $i%05d").toList

  private def team(n: Int, limits: TeamLimits, key: String = "conf/sigir/Giant") = {
    val w = work(key, names(n))
    TeamAssembler.assemble(List(w), mentions(w), limits, run)
  }

  private def expandAll(n: Int, perShard: Long, key: String = "conf/sigir/Giant") = {
    val t = team(n, TeamLimits(n + 1, n + 1, 50), key).toOption.get.team
    val out = mutable.ListBuffer.empty[CoauthorPairCandidate]
    var s = 0L
    while (s < PairIndex.shardCount(n, perShard)) {
      val (from, until) = PairIndex.shardRange(n, perShard, s)
      PairIndex.foreachPair(n, from, until) { (i, j) =>
        PairExpansion.pairAt(t, i, j, run) match {
          case ExpandedPair.Candidate(p) => out += p
          case other                     => fail(s"unexpected $other")
        }
      }
      s += 1
    }
    (t, out.toList)
  }

  // ---------------------------------------------------------------- giant teams

  test("giant-team: oversize flag flips exactly above the configured threshold") {
    val limits = TeamLimits(oversizeThreshold = 10, hardLimit = 20, maxCoauthorContext = 5)
    assertEquals(team(10, limits).map(_.oversize), Right(false))
    assertEquals(team(11, limits).map(_.oversize), Right(true))
    assertEquals(team(20, limits).map(_.oversize), Right(true))
  }

  test("giant-team: a team above the hard limit is rejected with team_exceeds_hard_limit") {
    val limits = TeamLimits(10, 20, 5)
    assertEquals(team(20, limits).isRight, true)
    assertEquals(team(21, limits).left.map(_.reason), Left(Reasons.TeamExceedsHardLimit))
  }

  test("giant-team: changing the limits changes the outcome for the same input (config driven)") {
    val n = 30
    assertEquals(team(n, TeamLimits(100, 5000, 50)).map(_.oversize), Right(false))
    assertEquals(team(n, TeamLimits(29, 5000, 50)).map(_.oversize), Right(true))
    assert(team(n, TeamLimits(10, 29, 50)).isLeft)
  }

  test("giant-team: coauthor context is capped by maxCoauthorContext and marked truncated") {
    val a = team(12, TeamLimits(100, 5000, 4)).toOption.get
    a.contexts.foreach { c =>
      assertEquals(c.coauthorNames.size, 4)
      assert(c.coauthorsTruncated)
      assert(!c.coauthorNames.contains(c.name))
    }
    val b = team(5, TeamLimits(100, 5000, 4)).toOption.get
    b.contexts.foreach(c => assert(!c.coauthorsTruncated && c.coauthorNames.size == 4))
  }

  test("pair-count: n*(n-1)/2 for small, boundary and int-overflowing team sizes") {
    assertEquals(PairIndex.pairCount(0), 0L)
    assertEquals(PairIndex.pairCount(1), 0L)
    assertEquals(PairIndex.pairCount(2), 1L)
    assertEquals(PairIndex.pairCount(5000), 12_497_500L)
    assertEquals(PairIndex.pairCount(65_536), 2_147_450_880L)
    assertEquals(PairIndex.pairCount(100_000), 4_999_950_000L)
    assertEquals(PairIndex.pairCount(Int.MaxValue), Int.MaxValue.toLong * (Int.MaxValue - 1L) / 2)
  }

  test("pair-sharding: shards cover every (i<j) pair exactly once for any shard size") {
    for {
      n <- List(2, 3, 7, 31)
      per <- List(1L, 2L, 5L, 7L, PairIndex.pairCount(n) - 1, PairIndex.pairCount(n), 1000L)
      if per > 0
    } {
      val seen = mutable.ListBuffer.empty[(Int, Int)]
      val shards = PairIndex.shardCount(n, per)
      assertEquals(shards, (PairIndex.pairCount(n) + per - 1) / per, s"n=$n per=$per")
      (0L until shards).foreach { s =>
        val (from, until) = PairIndex.shardRange(n, per, s)
        assert(until - from <= per && until > from, s"n=$n per=$per shard=$s")
        PairIndex.foreachPair(n, from, until)((i, j) => seen += ((i, j)))
      }
      val expected = for { i <- 0 until n; j <- i + 1 until n } yield (i, j)
      assertEquals(seen.toList, expected.toList, s"n=$n per=$per")
    }
  }

  test("pair-sharding: unrank is exact at row boundaries and the last index of a giant team") {
    val n = 100_000
    assertEquals(PairIndex.unrank(n, 0L), (0, 1))
    assertEquals(PairIndex.unrank(n, (n - 1).toLong - 1), (0, n - 1))
    assertEquals(PairIndex.unrank(n, (n - 1).toLong), (1, 2))
    assertEquals(PairIndex.unrank(n, PairIndex.pairCount(n) - 1), (n - 2, n - 1))
    intercept[IllegalArgumentException](PairIndex.unrank(n, PairIndex.pairCount(n)))
    intercept[IllegalArgumentException](PairIndex.unrank(n, -1L))
  }

  test("giant-team: a 120-author team expands to 7140 distinct pairs regardless of shard size") {
    val (_, a) = expandAll(120, 100L)
    val (_, b) = expandAll(120, 7140L)
    val (_, c) = expandAll(120, 1L)
    assertEquals(a.size, 7140)
    assertEquals(a.map(p => (p.left, p.right)).distinct.size, 7140)
    assertEquals(a, b, "output must not depend on pairsPerShard")
    assertEquals(a, c)
  }

  // ---------------------------------------------------------------- pair record contents

  test("pair-record: every pair carries work ID, year, team size, positions and provenance") {
    val (t, pairs) = expandAll(9, 4L, "journals/pvldb/Prov1")
    pairs.foreach { p =>
      assertEquals(p.workId, t.workId)
      assertEquals(p.year, t.year)
      assertEquals(p.teamSize, 9)
      assertEquals(p.positions.size, 1)
      val pos = p.positions.head
      assertEquals(t.authorNames(pos.leftPosition), p.left)
      assertEquals(t.authorNames(pos.rightPosition), p.right)
      assertEquals(pos.leftMentionId, MentionId.of(t.workId, pos.leftPosition))
      assertEquals(pos.rightMentionId, MentionId.of(t.workId, pos.rightPosition))
      assertEquals(p.provenance.sourceType, SourceType.Dblp)
      assertEquals(p.provenance.sourceRecordIds, List("journals/pvldb/Prov1"))
      assertEquals(p.provenance.datasetId, t.provenance.datasetId)
      assertEquals(p.provenance.snapshotId, t.provenance.snapshotId)
      assertEquals(p.provenance.contentHash, t.provenance.contentHash)
      assert(p.provenance.contentHash.nonEmpty)
      assertEquals(p.provenance.runId, run.runId)
      assertEquals(Contract.validate(p), Nil)
      assert(Contract.encode(p).isRight)
    }
  }

  test("pair-record: reordered author list keeps positions attached to the right names") {
    val w = work("conf/sigir/Order1", List("Zed Synthetic", "Amy Synthetic"))
    val t = TeamAssembler.assemble(List(w), mentions(w), TeamLimits(10, 10, 5), run).toOption.get
    PairExpansion.pairAt(t.team, 0, 1, run) match {
      case ExpandedPair.Candidate(p) =>
        assertEquals((p.left, p.right), ("Amy Synthetic", "Zed Synthetic"))
        assertEquals((p.positions.head.leftPosition, p.positions.head.rightPosition), (1, 0))
      case other => fail(other.toString)
    }
  }

  test("pair-record: homonyms on one work are a self pair and consolidate positions A,B,A") {
    val w = work("conf/sigir/Homo1", List("A Synthetic", "B Synthetic", "A Synthetic"))
    val t = TeamAssembler.assemble(List(w), mentions(w), TeamLimits(10, 10, 5), run).toOption.get
    val expanded =
      for { i <- 0 until 3; j <- i + 1 until 3 } yield PairExpansion.pairAt(t.team, i, j, run)
    assertEquals(expanded.count(_.isInstanceOf[ExpandedPair.SelfPair]), 1)
    val cands = expanded.collect { case ExpandedPair.Candidate(p) => p }.toList
    assertEquals(cands.size, 2)
    val merged = PairConsolidateJob.consolidate(cands.reverse).toOption.get
    assertEquals(
      merged.positions.map(p => (p.leftPosition, p.rightPosition)),
      List((0, 1), (2, 1))
    )
    assertEquals(Contract.validate(merged), Nil)
    val other = cands.head.copy(year = cands.head.year + 1)
    assert(PairConsolidateJob.consolidate(List(cands.head, other)).isLeft)
  }

  test("determinism: expansion is identical across runs; run context changes only provenance") {
    val (_, a) = expandAll(15, 10L)
    val (_, b) = expandAll(15, 10L)
    assertEquals(a, b)
    val other = run.copy(runId = org.scholarchain.core.RunId.of("x", "y", "z", "w").toOption.get)
    val w = work("conf/sigir/Giant", names(15))
    val t = TeamAssembler.assemble(List(w), mentions(w), TeamLimits(16, 16, 50), other).toOption.get
    val c = PairExpansion.pairAt(t.team, 0, 1, other).asInstanceOf[ExpandedPair.Candidate].pair
    assertEquals(c.copy(provenance = a.head.provenance), a.head)
    assertEquals(c.provenance.runId, other.runId)
  }

  // ---------------------------------------------------------------- common names

  test("common-name: homonym-numbered variants share one dblp-base-name block key") {
    val keys = List("Wei Wang", "Wei Wang 0001", "Wei Wang 0002", "Wei Wang 0137").zipWithIndex
      .flatMap { case (n, i) =>
        BlockingKeys.keys(BlockingKeys.DblpBaseName, context(s"conf/sigir/Cn$i", 0, n))
      }
    assertEquals(keys.distinct, List("Wei Wang"))
    assertEquals(
      BlockingKeys.keys(BlockingKeys.DblpBaseName, context("conf/sigir/Cx", 0, "Wei Wang 12345")),
      List("Wei Wang 12345")
    )
  }

  test("common-name: unsupported blocking family fails loudly instead of emitting nothing") {
    intercept[IllegalArgumentException](
      BlockingKeys.keys("initials", context("conf/sigir/Cy", 0, "Wei Wang"))
    )
  }

  test(
    "common-name: a hot block at an explicit maxBlockSize 1000 exceeds the default " +
      "maxPairsPerBlock, so the pair limit binds (cap 500)"
  ) {
    // An explicit maxBlockSize 1000 (the pre-G-17 default, still allowed by -D) gives 499500
    // pairs; J06 must then skip such a block (> 125000), and a 500-member block (124750 pairs)
    // must still be expanded. J05 and J05b therefore cap blocks at 500 by the pair limit.
    val d = DefaultsXml.values
    val hotCap = 1000
    val maxPairs = d(Settings.Keys.CandidateMaxPairsPerBlock).toLong
    assertEquals(maxPairs, 125000L)
    assert(PairIndex.pairCount(hotCap) > maxPairs)
    assert(PairIndex.pairCount(500) <= maxPairs)
    assert(PairIndex.pairCount(501) > maxPairs)
    val hot = BlockLimit(hotCap, maxPairs)
    assertEquals(hot.effective, 500)
    assert(hot.pairBound, "with maxBlockSize 1000 the pair limit, not the member cap, binds")
    assertEquals(hot.reason(501L), BlockLimit.PairReason)
    assertEquals(hot.reason(1001L), BlockLimit.MemberReason)
    val perShard = d(Settings.Keys.CandidatePairsPerShard).toLong
    assertEquals(PairIndex.shardCount(500, perShard), 3L)
  }

  test(
    "common-name: with the G-17 defaults (member caps 200) the member cap binds, not the pair limit"
  ) {
    // Phase 14 repair G-17: maxBlockSize and fallback.maxBlockSize default to 200 (19900 pairs),
    // within the default maxPairsPerBlock 125000, so every emitted block J06 reads is expandable
    // and the effective J05 and J05b caps are the member caps.
    val d = DefaultsXml.values
    val maxPairs = d(Settings.Keys.CandidateMaxPairsPerBlock).toLong
    List(Settings.Keys.BlockingMaxBlock, Settings.Keys.FallbackMaxBlock).foreach { k =>
      val cap = d(k).toInt
      assertEquals(cap, 200, k)
      assertEquals(PairIndex.pairCount(cap), 19900L, k)
      assert(PairIndex.pairCount(cap) <= maxPairs, k)
      val limit = BlockLimit(cap, maxPairs)
      assertEquals(limit.effective, cap, k)
      assert(!limit.pairBound, s"$k: the member cap must bind with the defaults")
      assertEquals(limit.reason(cap + 1L), BlockLimit.MemberReason, k)
    }
  }

  test("common-name: J06 features are symmetric and the candidate is canonical") {
    val members = List("Wei Wang 0001", "Wei Wang 0002", "Wei Wang").zipWithIndex
      .map { case (n, i) =>
        BlockMember.of(context(s"conf/sigir/Cf$i", 0, n))
      }
      .sortBy(_.mentionId)
    val block = CandidateBlock(
      CandidateBlock.Version,
      BlockingKeys.DblpBaseName,
      "Wei Wang",
      members.size,
      members,
      "blocking/1-provisional",
      run.runId.value
    )
    assertEquals(CandidateBlock.problems(block), Nil)
    assert(CandidateBlock.problems(block.copy(members = members.reverse)).nonEmpty)
    assert(CandidateBlock.problems(block.copy(size = 2)).nonEmpty)
    for { a <- members; b <- members if a != b } {
      assertEquals(CandidateFeatures.compute(a, b), CandidateFeatures.compute(b, a))
      val c1 = CandidateFeatures.candidate(block, a, b, run).toOption.get
      val c2 = CandidateFeatures.candidate(block, b, a, run).toOption.get
      assertEquals(c1, c2)
      assert(c1.provenance.sourceRecordIds.nonEmpty)
      assert(c1.provenance.policyVersion.contains("blocking/1-provisional"))
    }
  }

  // ---------------------------------------------------------------- configuration and rejects

  test("config: every threshold key has a documented positive default in the defaults resource") {
    val d = DefaultsXml.values
    List(
      Settings.Keys.TeamOversize,
      Settings.Keys.TeamHardLimit,
      Settings.Keys.TeamMaxCoauthorContext,
      Settings.Keys.PairsPerShard,
      Settings.Keys.BlockingMaxBlock,
      Settings.Keys.CandidateMaxPairsPerBlock,
      Settings.Keys.CandidatePairsPerShard,
      Settings.Keys.IdJoinMaxGroup,
      Settings.Keys.DblpMaxRecordBytes,
      Settings.Keys.DblpMaxPrologBytes,
      Settings.Keys.MaxDecompressedBytes,
      Settings.Keys.NormalizeMaxLineBytes
    ).foreach { k =>
      val v = d.get(k)
      assert(v.flatMap(_.toLongOption).exists(_ > 0), s"$k = $v")
    }
    assert(d(Settings.Keys.TeamOversize).toInt < d(Settings.Keys.TeamHardLimit).toInt)
  }

  test("config: scanner maxRecordBytes is a parameter, not a constant") {
    val corpus = fixture("split-corpus.xml")
    val sizes = scanAll(corpus).map(_.length)
    val limit = sizes.max.toInt - 1
    assert(scanAll(corpus, limit).exists(_.status == RawRecord.Oversize))
    assert(!scanAll(corpus, limit + 1).exists(_.status != RawRecord.Ok))
  }

  test("config: subset year window comes from the rule, not from code") {
    val xml =
      """<inproceedings key="conf/sigir/Cfg1"><author>A</author><title>T</title><year>2014</year></inproceedings>"""
    val u = scanAll(utf8(xml)).head
    assertEquals(MapperModel(u, Map.empty), Excluded(Reasons.ExYear))
    assert(MapperModel(u, Map.empty, rule = rule.copy(yearMin = 2014)).isInstanceOf[Accepted])
  }

  test("rejects: disposition records are versioned, carry the reason, run, snapshot and hash") {
    val input = "x" * 2000
    val d = Disposition.make(
      run,
      Disposition.Rejected,
      Reasons.TruncatedRecord,
      "end of input",
      Some("conf/sigir/R1"),
      Some("file:/in.xml:42"),
      Some(input),
      previewChars = 512
    )
    assertEquals(d.format, "scholarchain.hadoop.disposition")
    assertEquals(d.formatVersion, 1)
    assertEquals(d.reason, Reasons.TruncatedRecord)
    assertEquals(d.runId, run.runId.value)
    assertEquals(d.snapshotId, run.snapshotId)
    assertEquals(d.datasetId, run.datasetId)
    assertEquals(d.preview.map(_.length), Some(512))
    assertEquals(d.inputSha256.map(_.length), Some(64))
    val back = upickle.default.read[Disposition](Disposition.toJson(d))
    assertEquals(back, d)
  }
}

/** Reads `scholarchain-hadoop-defaults.xml` from the classpath with the JDK XML parser. */
object DefaultsXml {
  lazy val values: Map[String, String] = {
    val in = getClass.getClassLoader.getResourceAsStream(Settings.DefaultsResource)
    require(in != null, s"${Settings.DefaultsResource} is not on the classpath")
    try {
      val f = javax.xml.parsers.DocumentBuilderFactory.newInstance()
      f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
      val doc = f.newDocumentBuilder().parse(in)
      val props = doc.getElementsByTagName("property")
      (0 until props.getLength).map { i =>
        val e = props.item(i).asInstanceOf[org.w3c.dom.Element]
        def t(n: String) =
          Option(e.getElementsByTagName(n).item(0)).map(_.getTextContent.trim).getOrElse("")
        t("name") -> t("value")
      }.toMap
    } finally in.close()
  }
}
