package org.scholarchain.hadoop

import java.nio.file.{Files, Path as JPath}

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.scholarchain.core.*
import org.scholarchain.hadoop.blocking.*
import org.scholarchain.hadoop.candidates.{CandidateFeatureJob, CandidateFeatures}
import org.scholarchain.hadoop.common.*
import org.scholarchain.hadoop.pairs.PairIndex
import org.scholarchain.hadoop.pipeline.{Pipeline, PipelineResult, PipelineSpec}
import org.scholarchain.hadoop.teams.MentionContext
import upickle.default.{read, write}

/** Phase 5 blocking: the core name-key rule (author-name-key/1) in J05 and J06, the
  * initials-surname family, the oversize-block fallback (J05b, J05c) and the J06 volume rule.
  */
class FallbackBlockingSuite extends munit.FunSuite {

  override val munitTimeout = scala.concurrent.duration.Duration(900, "s")

  private def ctx(name: String, key: String = "conf/sigir/K1", pos: Int = 0): MentionContext = {
    val w = WorkId.fromDblpKey(key)
    MentionContext(
      1,
      MentionId.of(w, pos).value,
      w.value,
      key,
      pos,
      name,
      None,
      2020,
      Some("conf/sigir"),
      None,
      "t",
      2,
      Nil,
      false,
      "dblp",
      "s"
    )
  }

  private def member(
      key: String,
      name: String,
      coauthors: List[String] = Nil,
      venue: Option[String] = Some("conf/sigir"),
      pos: Int = 0
  ): BlockMember = {
    val w = WorkId.fromDblpKey(key)
    BlockMember(MentionId.of(w, pos).value, w.value, key, name, 2020, None, venue, coauthors, false)
  }

  // ------------------------------------------------------------------ unit: keys

  test("J05 keys come from the core AuthorNameKey rule; no local name normalisation remains") {
    val names = List(
      "Ricardo Campos 0001",
      "José García",
      "José García 0002",
      "Jean-Pierre O'Brien",
      "J. Smith",
      "  Wei   Wang  "
    )
    names.foreach { n =>
      assertEquals(
        BlockingKeys.keys(BlockingKeys.DblpBaseName, ctx(n)),
        List(AuthorNameKey.baseNameKey(n)),
        n
      )
      assertEquals(
        BlockingKeys.keys(BlockingKeys.InitialsSurname, ctx(n)),
        AuthorNameKey.initialsSurnameKey(n).toList,
        n
      )
    }
    // the local NFD helper is gone: BlockingKeys exposes only families and keys
    assert(!BlockingKeys.getClass.getMethods.exists(_.getName == "canonical"))
    assertEquals(BlockingKeys.supported, Set("dblp-base-name", "initials-surname"))
  }

  test("initials-surname: J. Smith, John Smith, Jane Smith and John Smith 0003 share one key") {
    val k = List("J. Smith", "John Smith", "Jane Smith", "John Smith 0003", "Jane  Smith")
      .flatMap(n => BlockingKeys.keys(BlockingKeys.InitialsSurname, ctx(n)))
    assertEquals(k.distinct, List("j smith"))
    // dblp-base-name keeps them apart
    assertEquals(
      List("J. Smith", "John Smith", "Jane Smith")
        .flatMap(n => BlockingKeys.keys(BlockingKeys.DblpBaseName, ctx(n)))
        .distinct
        .size,
      3
    )
    // Cyrillic look-alike stays apart (no cross-script folding)
    assertNotEquals(
      BlockingKeys.keys(BlockingKeys.InitialsSurname, ctx("John Smіth")),
      List("j smith")
    )
    // a name without letters or digits has no initials key (counted, never blank)
    assertEquals(BlockingKeys.keys(BlockingKeys.InitialsSurname, ctx("- . -")), Nil)
    intercept[SettingsError](BlockingKeys.keys("initials", ctx("Wei Wang")))
  }

  test("configured families and fallback keys are validated") {
    def s(kv: (String, String)*) = {
      val c = Settings.withDefaults(new Configuration(false))
      kv.foreach { case (k, v) => c.set(k, v) }
      new Settings(c)
    }
    // Phase 11 (Phase 5 m5): both families are the default, not only the Phase 4 one
    assertEquals(BlockingKeys.configured(s()), List("dblp-base-name", "initials-surname"))
    assertEquals(
      BlockingKeys.configured(s(Settings.Keys.BlockingFamilies -> "dblp-base-name")),
      List("dblp-base-name")
    )
    intercept[SettingsError](BlockingKeys.configured(s(Settings.Keys.BlockingFamilies -> "")))
    intercept[SettingsError](BlockingKeys.configured(s(Settings.Keys.BlockingFamilies -> "title")))
    assertEquals(
      FallbackKeys.configured(s()),
      (List("dblp-base-name", "initials-surname"), List("coauthor"))
    )
    assertEquals(
      FallbackKeys.configured(s(Settings.Keys.FallbackSecondaryKeys -> "")),
      (List("dblp-base-name", "initials-surname"), Nil)
    )
    intercept[SettingsError](
      FallbackKeys.configured(s(Settings.Keys.FallbackSecondaryKeys -> "coauthor,title"))
    )
    intercept[SettingsError](
      FallbackKeys.configured(s(Settings.Keys.FallbackFamilies -> "dblp-base-name,initials"))
    )
  }

  test("fallback secondary values: coauthor full-name keys, venue, and both; JSON block keys") {
    val nfd = "René Müller"
    val m = member("conf/sigir/F1", "Wei Wang", List("Bo Li", "René Müller", nfd, "Bo Li 0001"))
    assertEquals(
      FallbackKeys.values(FallbackKeys.Coauthor, m),
      List(List("Bo Li"), List("Bo Li 0001"), List(nfd))
    )
    assertEquals(FallbackKeys.values(FallbackKeys.Venue, m), List(List("conf/sigir")))
    assertEquals(
      FallbackKeys.values(FallbackKeys.CoauthorVenue, m),
      List(List("Bo Li", "conf/sigir"), List("Bo Li 0001", "conf/sigir"), List(nfd, "conf/sigir"))
    )
    val bare = member("conf/sigir/F2", "Wei Wang", Nil, None)
    FallbackKeys.supported.foreach(k => assertEquals(FallbackKeys.values(k, bare), Nil, k))
    intercept[SettingsError](FallbackKeys.values("title", m))
    val key = FallbackKeys.blockKey("Wei Wang", List("Bo \"Li\"", "conf/kdd"))
    assertEquals(ujson.read(key).arr.map(_.str).toList, List("Wei Wang", "Bo \"Li\"", "conf/kdd"))
    assert(!key.exists(Character.isISOControl))
    assertEquals(
      FallbackKeys.split("initials-surname.coauthor-venue"),
      ("initials-surname", "coauthor-venue")
    )
  }

  test("BlockLimit: effective cap is min(member cap, largest m with m(m-1)/2 <= maxPairs)") {
    // defaults: maxBlockSize 1000, maxPairsPerBlock 125000 -> 500 (124,750 pairs); 501 -> 125,250
    val d = BlockLimit(1000, 125000L)
    assertEquals(d.effective, 500)
    assert(d.pairBound)
    assertEquals(d.reason(501), BlockLimit.PairReason)
    assertEquals(d.reason(1000), BlockLimit.PairReason)
    assertEquals(d.reason(1001), BlockLimit.MemberReason)
    assertEquals(BlockLimit(1000, 499500L).effective, 1000)
    assert(!BlockLimit(1000, 499500L).pairBound)
    assertEquals(BlockLimit(1000, 499499L).effective, 999)
    assertEquals(BlockLimit(200, 125000L).effective, 200) // the planned real-run setting
    assertEquals(BlockLimit(10, 44L).effective, 9)
    assertEquals(BlockLimit(10, 45L).effective, 10)
    assertEquals(BlockLimit(3, 1L).effective, 2)
    assertEquals(BlockLimit(1, 1L).effective, 1)
    assertEquals(BlockLimit(Int.MaxValue, Long.MaxValue).effective, Int.MaxValue)
    assertEquals(BlockLimit(Int.MaxValue, 1L).effective, 2)
    // exhaustive on small values: the cap is the largest admissible size
    for { cap <- 1 to 40; p <- 1L to 50L } {
      val e = BlockLimit(cap, p).effective
      assert(e <= cap && PairIndex.pairCount(e) <= p, s"$cap $p $e")
      assert(e == cap || PairIndex.pairCount(e + 1) > p, s"$cap $p $e")
    }
    val l = d.listing(700).toMap
    assertEquals(l("limit").num, 500.0)
    assertEquals(l("pairs").num, 244650.0)
    assertEquals(l("reason").str, "max_pairs_per_block")
    // the defaults resource composes this way
    val s = new Settings(Settings.withDefaults(new Configuration(false)))
    assertEquals(BlockLimit.of(s, Settings.Keys.BlockingMaxBlock).effective, 500)
    assertEquals(BlockLimit.of(s, Settings.Keys.FallbackMaxBlock).effective, 500)
  }

  test("volume rule: only pairs with the same homonym-numbered dblp name qualify") {
    val a = member("conf/sigir/V1", "Wei Wang 0001")
    val b = member("conf/sigir/V2", "Wei Wang 0001")
    val c = member("conf/sigir/V3", "Wei Wang 0002")
    val d = member("conf/sigir/V4", "Wei Wang")
    val e = member("conf/sigir/V5", "Wei Wang")
    val nfd = member("conf/sigir/V6", "José Hot 0001")
    val nfc = member("conf/sigir/V7", "José Hot 0001")
    assert(CandidateFeatures.sameNumberedName(a, b))
    assert(CandidateFeatures.sameNumberedName(nfd, nfc))
    assert(!CandidateFeatures.sameNumberedName(a, c))
    assert(!CandidateFeatures.sameNumberedName(a, d))
    assert(!CandidateFeatures.sameNumberedName(d, e), "unnumbered names are never skipped")
  }

  // ------------------------------------------------------------------ local job runs

  private def rec(key: String, authors: List[String], year: Int = 2020): String = {
    val tag = if (key.startsWith("journals/")) "article" else "inproceedings"
    s"<$tag key=\"$key\">" + authors.map(a => s"<author>$a</author>").mkString +
      s"<title>T $key.</title><year>$year</year></$tag>\n"
  }

  /** Six "Wei Wang" mentions (oversize at maxBlockSize 3) plus the initials family cases. */
  private val corpus: String =
    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<dblp>\n" + List(
      rec("conf/sigir/W1", List("Wei Wang 0001", "Ann Lee")),
      rec("conf/sigir/W2", List("Wei Wang 0001", "Ann Lee")),
      rec("conf/sigir/W3", List("Wei Wang", "Ann Lee")),
      rec("conf/kdd/W4", List("Wei Wang 0002", "Bo Li")),
      rec("conf/kdd/W5", List("Wei Wang", "Bo Li")),
      rec("conf/www/W6", List("Wei Wang 0003")),
      rec("conf/icde/W7", List("J. Smith", "Cy Do")),
      rec("conf/icde/W8", List("John Smith", "Cy Do")),
      rec("conf/icde/W9", List("Jane Smith"))
    ).mkString + "</dblp>\n"

  private val settings: List[(String, String)] = List(
    Settings.Keys.BlockingFamilies -> "dblp-base-name,initials-surname",
    Settings.Keys.BlockingMaxBlock -> "3",
    Settings.Keys.FallbackSecondaryKeys -> "coauthor,venue",
    Settings.Keys.FallbackMaxBlock -> "2"
  )

  private lazy val work: JPath = TestSupport.tempDir("fallback")
  private lazy val input: Path = TestSupport.writeFile(work, "in/fallback.xml", corpus)

  private def pipeline(out: String, extra: (String, String)*): (PipelineResult, Configuration) = {
    val conf = TestSupport.localConf(work, (settings ++ extra)*)
    Pipeline.run(conf, PipelineSpec(input, "fb", Nil, new Path(work.resolve(out).toUri))) -> conf
  }

  private lazy val first = pipeline("a")

  private def mention(key: String, pos: Int = 0) = MentionId.of(WorkId.fromDblpKey(key), pos).value

  private def inputRuns(j: JobResult): List[String] =
    ujson
      .read(Files.readString(java.nio.file.Paths.get(j.dir("_job.json").toUri)))("inputRuns")
      .arr
      .map(_.str)
      .toList

  private def accounted(j: JobResult): Unit =
    assertEquals(
      j.main(Counters.Input),
      j.main(Counters.Accepted) + j.main(Counters.Excluded) + j.main(Counters.Rejected),
      j.jobName
    )

  test("J05: two families, oversize members streamed to oversizeMembers/, per-family counters") {
    val (p, conf) = first
    val b = p.blocking
    accounted(b)
    assertEquals(b.main(Counters.Accepted), 16L)
    assertEquals(b.main(Counters.BlocksEmitted), 7L)
    assertEquals(b.main(Counters.OversizeBlock), 2L)
    assertEquals(b.main(Counters.SingletonBlocks), 3L)
    assertEquals(b.main(Counters.OversizeMembers), 12L)
    assertEquals(b.counter(Counters.BlocksPerFamilyGroup, "dblp-base-name"), 3L)
    assertEquals(b.counter(Counters.BlocksPerFamilyGroup, "initials-surname"), 4L)
    assertEquals(b.counter(Counters.OversizePerFamilyGroup, "dblp-base-name"), 1L)
    assertEquals(b.counter(Counters.OversizePerFamilyGroup, "initials-surname"), 1L)
    val blocks = TestSupport.lines(conf, b, BlockingJob.Blocks).map(read[CandidateBlock](_))
    assert(blocks.forall(x => CandidateBlock.problems(x).isEmpty))
    assertEquals(
      blocks.filter(_.family == "initials-surname").map(_.blockKey).sorted,
      List("a lee", "b li", "c do", "j smith")
    )
    assertEquals(blocks.find(_.blockKey == "j smith").get.size, 3)
    assertEquals(blocks.head.policyVersion, "blocking/2+author-name-key/1")
    val om = TestSupport.lines(conf, b, BlockingJob.OversizeMembers).map(read[OversizeMember](_))
    assertEquals(om.size, 12)
    assert(om.forall(x => OversizeMember.problems(x).isEmpty))
    assertEquals(
      om.groupBy(x => (x.family, x.blockKey)).view.mapValues(_.map(_.member.mentionId).toSet).toMap,
      Map(
        ("dblp-base-name", "Wei Wang") -> Set(
          mention("conf/sigir/W1"),
          mention("conf/sigir/W2"),
          mention("conf/sigir/W3"),
          mention("conf/kdd/W4"),
          mention("conf/kdd/W5"),
          mention("conf/www/W6")
        ),
        ("initials-surname", "w wang") -> Set(
          mention("conf/sigir/W1"),
          mention("conf/sigir/W2"),
          mention("conf/sigir/W3"),
          mention("conf/kdd/W4"),
          mention("conf/kdd/W5"),
          mention("conf/www/W6")
        )
      )
    )
    // the Phase 4 listing is unchanged
    val listed = TestSupport.lines(conf, b, BlockingJob.OversizeBlocks).map(ujson.read(_))
    assertEquals(listed.map(_("size").num).toSet, Set(6.0))
  }

  test("J05b: fallback blocks by coauthor and venue, capped, singletons counted, coverage") {
    val (p, conf) = first
    val f = p.fallbackBlocking
    accounted(f)
    assertEquals(f.main(Counters.Accepted), 12L)
    assertEquals(f.main(Counters.FallbackKeysEmitted), 22L)
    assertEquals(f.counter(Counters.NoSecondaryKeyGroup, "dblp-base-name.coauthor"), 1L)
    assertEquals(f.counter(Counters.NoSecondaryKeyGroup, "initials-surname.coauthor"), 1L)
    assertEquals(f.main(Counters.BlocksEmitted), 4L)
    assertEquals(f.main(Counters.OversizeBlock), 4L)
    assertEquals(f.main(Counters.SingletonBlocks), 2L)
    List("dblp-base-name.coauthor", "dblp-base-name.venue", "initials-surname.coauthor").foreach {
      fam =>
        assertEquals(f.counter(Counters.BlocksPerFamilyGroup, fam), 1L, fam)
        assertEquals(f.counter(Counters.OversizePerFamilyGroup, fam), 1L, fam)
    }
    // pre-cap: the largest fallback group (3) exceeds the cap (2) and was not emitted
    assertEquals(f.stats("largestFallbackBlock").value, 3L)
    val blocks = TestSupport.lines(conf, f, FallbackBlockingJob.Blocks).map(read[CandidateBlock](_))
    // post-cap: the largest block written to blocks/ never exceeds the cap
    val emitted = f.stats(FallbackBlockingJob.LargestEmittedFallbackBlockStat)
    assertEquals(FallbackBlockingJob.LargestEmittedFallbackBlockStat, "largestEmittedFallbackBlock")
    assertEquals(emitted.value, 2L)
    assertEquals(emitted.value, blocks.map(_.size.toLong).max)
    val tie = blocks.map(b => s"${b.family}\t${b.blockKey}").sorted(CodePointOrder.ordering).head
    assertEquals(emitted.subject, tie)
    assert(emitted.value < f.stats(FallbackBlockingJob.LargestFallbackBlockStat).value)
    assert(blocks.forall(x => CandidateBlock.problems(x).isEmpty))
    val kdd = Set(mention("conf/kdd/W4"), mention("conf/kdd/W5"))
    assert(blocks.forall(_.members.map(_.mentionId).toSet == kdd))
    val coauthorBlock = blocks.find(_.family == "dblp-base-name.coauthor").get
    assertEquals(coauthorBlock.blockKey, FallbackKeys.blockKey("Wei Wang", List("Bo Li")))
    assertEquals(
      blocks.find(_.family == "initials-surname.coauthor").get.blockKey,
      "[\"w wang\",\"Bo Li\"]"
    )
    val oversize =
      TestSupport.lines(conf, f, FallbackBlockingJob.OversizeBlocks).map(ujson.read(_))
    assertEquals(oversize.size, 4)
    assert(oversize.forall(o => o("size").num == 3.0 && o("limit").num == 2.0))
    assertEquals(
      oversize.filter(_("family").str == "dblp-base-name.coauthor").map(_("blockKey").str),
      List(FallbackKeys.blockKey("Wei Wang", List("Ann Lee")))
    )
    val cov =
      TestSupport.lines(conf, f, FallbackBlockingJob.Coverage).map(read[FallbackCoverage](_))
    assertEquals(cov.size, 8)
    assertEquals(cov.map(_.mentionId).toSet, kdd)
    assertEquals(inputRuns(f), List(p.blocking.runId.value))
  }

  test("J05c: uncovered members counted and listed; per-key summary; worst key stat") {
    val (p, conf) = first
    val c = p.fallbackCoverage
    accounted(c)
    assertEquals(c.main(Counters.Accepted), 20L)
    assertEquals(c.main(Counters.CoveredMembers), 4L)
    assertEquals(c.main(Counters.UncoveredMembers), 8L)
    assertEquals(c.main(Counters.OversizeKeys), 2L)
    assertEquals(c.counter(Counters.UncoveredPerFamilyGroup, "dblp-base-name"), 4L)
    assertEquals(c.counter(Counters.UncoveredPerFamilyGroup, "initials-surname"), 4L)
    val uncovered =
      TestSupport.lines(conf, c, FallbackCoverageJob.Uncovered).map(read[UncoveredMember](_))
    val expected = Set(
      mention("conf/sigir/W1"),
      mention("conf/sigir/W2"),
      mention("conf/sigir/W3"),
      mention("conf/www/W6")
    )
    assertEquals(
      uncovered.filter(_.family == "dblp-base-name").map(_.mentionId).toSet,
      expected
    )
    assertEquals(uncovered.size, 8)
    val w6 = uncovered.find(u => u.mentionId == mention("conf/www/W6")).get
    assertEquals((w6.dblpKey, w6.name), ("conf/www/W6", "Wei Wang 0003"))
    val summary = TestSupport
      .lines(conf, c, FallbackCoverageJob.Summary)
      .map(read[OversizeKeySummary](_))
      .sortBy(_.family)
    assertEquals(
      summary,
      List(
        OversizeKeySummary(
          1,
          "dblp-base-name",
          "Wei Wang",
          6,
          2,
          4,
          List(
            FamilyCount("dblp-base-name.coauthor", 2),
            FamilyCount("dblp-base-name.venue", 2)
          )
        ),
        OversizeKeySummary(
          1,
          "initials-surname",
          "w wang",
          6,
          2,
          4,
          List(
            FamilyCount("initials-surname.coauthor", 2),
            FamilyCount("initials-surname.venue", 2)
          )
        )
      )
    )
    assertEquals(c.stats("worstUncoveredKey").value, 4L)
    assertEquals(c.stats("worstUncoveredKey").subject, "dblp-base-name\tWei Wang")
    assertEquals(inputRuns(c), List(p.blocking.runId.value, p.fallbackBlocking.runId.value).sorted)
  }

  test("J06 reads J05 and J05b blocks: same features for every family; J09 exports them all") {
    val (p, conf) = first
    val j = p.candidates
    accounted(j)
    assertEquals(j.main(Counters.Accepted), 11L)
    assertEquals(j.main(Counters.CandidatesEmitted), 17L)
    val perFamily = Map(
      "dblp-base-name" -> 5L,
      "initials-surname" -> 8L,
      "dblp-base-name.coauthor" -> 1L,
      "dblp-base-name.venue" -> 1L,
      "initials-surname.coauthor" -> 1L,
      "initials-surname.venue" -> 1L
    )
    perFamily.foreach { case (fam, n) =>
      assertEquals(j.counter(Counters.OutputGroup, CandidateFeatureJob.candidatesDir(fam)), n, fam)
    }
    assertEquals(inputRuns(j), List(p.blocking.runId.value, p.fallbackBlocking.runId.value).sorted)
    val all = perFamily.keys.toList.flatMap { fam =>
      TestSupport
        .lines(conf, j, CandidateFeatureJob.candidatesDir(fam))
        .map(l => Contract.decode[MatchCandidate](l).fold(e => fail(e.message), identity))
    }
    assert(all.forall(_.features.map(_.name).forall(CandidateFeatures.names.contains)))
    val fb = all.filter(_.blockFamily == "dblp-base-name.coauthor").head
    assertEquals(Set(fb.left, fb.right), Set(mention("conf/kdd/W4"), mention("conf/kdd/W5")))
    assertEquals(
      fb.features.map(f => f.name -> f.value).toMap,
      Map(
        "coauthor_shared_count" -> 1.0,
        "name_exact_match" -> 0.0,
        "same_venue" -> 1.0,
        "same_work" -> 0.0,
        "year_abs_diff" -> 0.0
      )
    )
    assertEquals(
      fb.provenance.policyVersion,
      "candidate-features/1-provisional;blocking/2+author-name-key/1"
    )
    // the initials family pairs J. Smith with John Smith
    val js = all.filter(x => x.blockFamily == "initials-surname" && x.blockKey == "j smith")
    assertEquals(js.size, 3)
    val exported = JobRunner
      .readPartLines(conf, new Path(p.graphExport.outputDir, "match_candidate"))
      .map(l => Contract.decode[MatchCandidate](l).fold(e => fail(e.message), identity))
    assertEquals(
      exported.map(_.candidateId).sortBy(_.value),
      all.map(_.candidateId).sortBy(_.value)
    )
    p.all.foreach(accounted)
  }

  test("determinism across output roots: J05, J05b, J05c and J06 are byte-identical") {
    val (a, _) = first
    val (b, _) = pipeline("b")
    List(
      a.blocking -> b.blocking,
      a.fallbackBlocking -> b.fallbackBlocking,
      a.fallbackCoverage -> b.fallbackCoverage,
      a.candidates -> b.candidates
    ).foreach { case (x, y) =>
      assertEquals(x.runId, y.runId, x.jobName)
      assert(!y.reused, y.jobName)
      val fx = TestFiles.partFiles(x.outputDir)
      val fy = TestFiles.partFiles(y.outputDir)
      assert(fx.values.exists(_.nonEmpty), x.jobName)
      assertEquals(fx.keySet, fy.keySet, x.jobName)
      fx.foreach { case (rel, bytes) =>
        assert(java.util.Arrays.equals(bytes, fy(rel)), s"${x.jobName} $rel")
      }
    }
  }

  // ------------------------------------------------------------------ pair limit in J05 / J05b

  private def blocksOf(conf: Configuration, j: JobResult, dir: String): List[CandidateBlock] =
    TestSupport.lines(conf, j, dir).map(read[CandidateBlock](_))

  private def blockFacts(bs: List[CandidateBlock]) =
    bs.map(b => (b.family, b.blockKey, b.members)).sortBy(x => s"${x._1}\t${x._2}")

  test("block within maxBlockSize but over maxPairsPerBlock: oversize in J05, fallback, no loss") {
    // maxBlockSize 1000 would emit the 6-member Wei Wang blocks (15 pairs); maxPairsPerBlock 3
    // makes the effective cap 3, so the result must equal the maxBlockSize-3 run (`first`).
    val (base, baseConf) = first
    val (p, conf) = pipeline(
      "pair-limit",
      Settings.Keys.BlockingMaxBlock -> "1000",
      Settings.Keys.CandidateMaxPairsPerBlock -> "3"
    )
    p.all.foreach(accounted)
    val b = p.blocking
    assertEquals(b.main(Counters.OversizeBlock), 2L)
    assertEquals(b.main(Counters.PairLimitOversizeBlocks), 2L)
    assertEquals(b.main(Counters.OversizeMembers), 12L)
    assertEquals(b.counter(Counters.OversizePerFamilyGroup, "dblp-base-name"), 1L)
    assertEquals(b.counter(Counters.OversizePerFamilyGroup, "initials-surname"), 1L)
    val listed = TestSupport.lines(conf, b, BlockingJob.OversizeBlocks).map(ujson.read(_))
    assertEquals(listed.size, 2)
    listed.foreach { l =>
      assertEquals(l("size").num, 6.0)
      assertEquals(l("limit").num, 3.0)
      assertEquals(l("maxBlockSize").num, 1000.0)
      assertEquals(l("maxPairsPerBlock").num, 3.0)
      assertEquals(l("pairs").num, 15.0)
      assertEquals(l("reason").str, "max_pairs_per_block")
    }
    assertEquals(
      blockFacts(blocksOf(conf, b, BlockingJob.Blocks)),
      blockFacts(blocksOf(baseConf, base.blocking, BlockingJob.Blocks))
    )
    def members(c: Configuration, j: JobResult) =
      TestSupport
        .lines(c, j, BlockingJob.OversizeMembers)
        .map(read[OversizeMember](_))
        .sortBy(write(_))
    assertEquals(members(conf, b), members(baseConf, base.blocking))

    // J05b: fallback member cap 2 < pair-bound cap 3, so its oversize blocks are member-bound
    val f = p.fallbackBlocking
    assertEquals(f.main(Counters.OversizeBlock), 4L)
    assertEquals(f.main(Counters.PairLimitOversizeBlocks), 0L)
    TestSupport
      .lines(conf, f, FallbackBlockingJob.OversizeBlocks)
      .map(ujson.read(_))
      .foreach(o => assertEquals(o("reason").str, "max_block_size"))
    assertEquals(
      blockFacts(blocksOf(conf, f, FallbackBlockingJob.Blocks)),
      blockFacts(blocksOf(baseConf, base.fallbackBlocking, FallbackBlockingJob.Blocks))
    )

    // J05c: the members reach the fallback and the worst-key accounting
    val c = p.fallbackCoverage
    assertEquals(c.main(Counters.CoveredMembers), 4L)
    assertEquals(c.main(Counters.UncoveredMembers), 8L)
    assertEquals(c.stats("worstUncoveredKey").subject, "dblp-base-name\tWei Wang")
    assertEquals(c.stats("worstUncoveredKey").value, 4L)

    // J06 skips nothing; same candidates per family as the maxBlockSize-3 run
    val j = p.candidates
    assertEquals(j.main(Counters.OversizeBlock), 0L)
    assertEquals(j.main(Counters.OversizeBlockPairsSkipped), 0L)
    assertEquals(j.main(Counters.UnexpandedMembers), 0L)
    assertEquals(
      j.main(Counters.CandidatesEmitted),
      base.candidates.main(Counters.CandidatesEmitted)
    )
    assertEquals(inputRuns(j), List(p.blocking.runId.value, p.fallbackBlocking.runId.value).sorted)
    assertEquals(inputRuns(c), List(p.blocking.runId.value, p.fallbackBlocking.runId.value).sorted)
    assertEquals(inputRuns(f), List(p.blocking.runId.value))
    assertNotEquals(p.blocking.runId, base.blocking.runId)
  }

  private lazy val tight = pipeline(
    "tight",
    Settings.Keys.BlockingMaxBlock -> "1000",
    Settings.Keys.FallbackMaxBlock -> "1000",
    Settings.Keys.CandidateMaxPairsPerBlock -> "1"
  )

  test("any limits: no emitted block exceeds maxPairsPerBlock; every oversize member accounted") {
    val (p, conf) = tight
    p.all.foreach(accounted)
    val b = p.blocking
    val f = p.fallbackBlocking
    // maxPairsPerBlock 1: effective cap 2 for primary and fallback blocks
    val emitted =
      blocksOf(conf, b, BlockingJob.Blocks) ++ blocksOf(conf, f, FallbackBlockingJob.Blocks)
    assert(emitted.nonEmpty)
    assert(emitted.forall(x => PairIndex.pairCount(x.size) <= 1L), emitted.map(_.size).toString)
    assert(b.main(Counters.OversizeBlock) > 0L)
    assertEquals(b.main(Counters.PairLimitOversizeBlocks), b.main(Counters.OversizeBlock))
    assert(f.main(Counters.OversizeBlock) > 0L)
    assertEquals(f.main(Counters.PairLimitOversizeBlocks), f.main(Counters.OversizeBlock))

    // J06 expands every block it reads
    val j = p.candidates
    assertEquals(j.main(Counters.OversizeBlock), 0L)
    assertEquals(j.main(Counters.UnexpandedMembers), 0L)
    assertEquals(j.main(Counters.Accepted), emitted.size.toLong)

    // every oversize member is either covered (in a J06 candidate of a fallback family of its
    // block) or listed as uncovered by J05c; never both, never neither
    val om = TestSupport
      .lines(conf, b, BlockingJob.OversizeMembers)
      .map(read[OversizeMember](_))
      .map(x => (x.family, x.blockKey, x.member.mentionId))
      .distinct
    val c = p.fallbackCoverage
    val uncovered = TestSupport
      .lines(conf, c, FallbackCoverageJob.Uncovered)
      .map(read[UncoveredMember](_))
      .map(u => (u.family, u.blockKey, u.mentionId))
      .toSet
    val families = emitted.map(_.family).distinct.filter(_.contains("."))
    val fallbackCandidates = families.flatMap { fam =>
      TestSupport
        .lines(conf, j, CandidateFeatureJob.candidatesDir(fam))
        .map(l => Contract.decode[MatchCandidate](l).fold(e => fail(e.message), identity))
    }
    val covered = fallbackCandidates.flatMap { x =>
      val primary = FallbackKeys.split(x.blockFamily)._1
      val key = ujson.read(x.blockKey).arr.head.str
      List((primary, key, x.left), (primary, key, x.right))
    }.toSet
    om.foreach { m =>
      assert(covered(m) != uncovered(m), s"$m covered=${covered(m)} uncovered=${uncovered(m)}")
    }
    assertEquals(
      c.main(Counters.CoveredMembers) + c.main(Counters.UncoveredMembers),
      om.size.toLong
    )
    assertEquals(c.main(Counters.CoveredMembers), om.count(covered).toLong)
  }

  test("determinism across output roots under the pair-bound caps") {
    val (a, _) = tight
    val (b, _) = pipeline(
      "tight-again",
      Settings.Keys.BlockingMaxBlock -> "1000",
      Settings.Keys.FallbackMaxBlock -> "1000",
      Settings.Keys.CandidateMaxPairsPerBlock -> "1"
    )
    List(
      a.blocking -> b.blocking,
      a.fallbackBlocking -> b.fallbackBlocking,
      a.fallbackCoverage -> b.fallbackCoverage,
      a.candidates -> b.candidates
    ).foreach { case (x, y) =>
      assertEquals(x.runId, y.runId, x.jobName)
      assert(!y.reused, y.jobName)
      val fx = TestFiles.partFiles(x.outputDir)
      val fy = TestFiles.partFiles(y.outputDir)
      assertEquals(fx.keySet, fy.keySet, x.jobName)
      fx.foreach { case (rel, bytes) =>
        assert(java.util.Arrays.equals(bytes, fy(rel)), s"${x.jobName} $rel")
      }
    }
  }

  test("fallback disabled: every oversize member is uncovered and counted, nothing re-blocked") {
    val (p, conf) = first
    val off =
      TestSupport.localConf(work, (settings :+ (Settings.Keys.FallbackSecondaryKeys -> ""))*)
    val out = new Path(work.resolve("off").toUri)
    val f = JobRunner.run(
      off,
      JobRunner.fromRuns(List(p.blocking.runId))(
        FallbackBlockingJob.spec(p.blocking.dir(BlockingJob.OversizeMembers), out, "fb", _)
      )
    )
    assertEquals(f.main(Counters.Accepted), 12L)
    assertEquals(f.main(Counters.BlocksEmitted), 0L)
    assertEquals(f.counter(Counters.FallbackNotConfiguredGroup, "dblp-base-name"), 6L)
    assertEquals(TestSupport.lines(off, f, FallbackBlockingJob.Blocks), Nil)
    val c = JobRunner.run(
      off,
      JobRunner.fromRuns(List(p.blocking.runId, f.runId))(
        FallbackCoverageJob.spec(
          p.blocking.dir(BlockingJob.OversizeMembers),
          f.dir(FallbackBlockingJob.Coverage),
          out,
          "fb",
          _
        )
      )
    )
    assertEquals(c.main(Counters.UncoveredMembers), 12L)
    assertEquals(c.main(Counters.CoveredMembers), 0L)
    assertEquals(TestSupport.lines(off, c, FallbackCoverageJob.Uncovered).size, 12)

    val bad = TestSupport.localConf(work, Settings.Keys.FallbackSecondaryKeys -> "title")
    val err = intercept[SettingsError](
      JobRunner.run(
        bad,
        FallbackBlockingJob.spec(p.blocking.dir(BlockingJob.OversizeMembers), out, "fb", "sha256:x")
      )
    )
    assertEquals(err.key, Settings.Keys.FallbackSecondaryKeys)
    assert(conf ne null)
  }

  test("J05c rejects malformed lines and coverage without an oversize member, with reasons") {
    val dir = TestSupport.tempDir("j05c-bad")
    val conf = TestSupport.localConf(dir)
    val m = member("conf/sigir/R1", "Wei Wang")
    val om = OversizeMember(1, "dblp-base-name", "Wei Wang", m)
    TestSupport.writeFile(
      dir,
      "members/part-r-00000",
      List(write(om), write(om), "{oops", write(om.copy(family = "title"))).mkString("\n") + "\n"
    )
    val orphan = mention("conf/sigir/R2")
    TestSupport.writeFile(
      dir,
      "coverage/part-r-00000",
      List(
        write(
          FallbackCoverage(1, "dblp-base-name", "Wei Wang", m.mentionId, "dblp-base-name.venue")
        ),
        write(FallbackCoverage(1, "dblp-base-name", "Wei Wang", orphan, "dblp-base-name.venue")),
        write(FallbackCoverage(1, "dblp-base-name", "Wei Wang", orphan, "initials-surname.venue"))
      ).mkString("\n") + "\n"
    )
    val c = JobRunner.run(
      conf,
      FallbackCoverageJob.spec(
        new Path(dir.resolve("members").toUri),
        new Path(dir.resolve("coverage").toUri),
        new Path(dir.resolve("out").toUri),
        "x",
        "sha256:test"
      )
    )
    assertEquals(c.main(Counters.Input), 7L)
    assertEquals(c.main(Counters.Accepted), 4L)
    assertEquals(c.main(Counters.Rejected), 3L)
    assertEquals(c.counter(Counters.RejectReasonGroup, "schema_failure"), 3L)
    assertEquals(c.main(Counters.Duplicate), 1L)
    assertEquals(c.main(Counters.CoveredMembers), 1L)
    assertEquals(c.main(Counters.UncoveredMembers), 0L)
    assertEquals(c.counter(Counters.Group, Counters.RejectedReduce), 1L)
    assertEquals(c.counter(Counters.RejectReasonGroup, "coverage_without_oversize_member"), 1L)
    val rejects = TestSupport.lines(conf, c, "rejects/v1").map(read[Disposition](_))
    assertEquals(
      rejects.find(_.reason == "coverage_without_oversize_member").get.sourceRecordId,
      Some(orphan)
    )
  }

  test("J06 volume rule: same homonym-numbered name pairs are skipped and counted when enabled") {
    val dir = TestSupport.tempDir("j06-skip")
    val ms = CandidateBlock.normalise(
      List(
        member("conf/sigir/S1", "Wei Wang 0001"),
        member("conf/kdd/S2", "Wei Wang 0001"),
        member("conf/www/S3", "Wei Wang"),
        member("conf/icde/S4", "Wei Wang")
      )
    )
    val blk = CandidateBlock(2, "dblp-base-name", "Wei Wang", 4, ms, "blocking/2", "r")
    val in = TestSupport.writeFile(dir, "blocks/part-r-00000", write(blk) + "\n")
    def j06(name: String, skip: String) = {
      val conf = TestSupport.localConf(dir, Settings.Keys.CandidateSkipSameNumberedName -> skip)
      val r = JobRunner.run(
        conf,
        CandidateFeatureJob.spec(
          List(new Path(in.getParent.toString)),
          new Path(dir.resolve(name).toUri),
          "x",
          "sha256:test"
        )
      )
      r -> TestSupport
        .lines(conf, r, CandidateFeatureJob.candidatesDir("dblp-base-name"))
        .map(read[MatchCandidate](_))
    }
    val (off, all) = j06("off", "false")
    assertEquals(off.main(Counters.CandidatesEmitted), 6L)
    assertEquals(off.main(Counters.SameNumberedNameSkipped), 0L)
    val (on, kept) = j06("on", "true")
    assertEquals(on.main(Counters.CandidatesEmitted), 5L)
    assertEquals(on.main(Counters.SameNumberedNameSkipped), 1L)
    assertNotEquals(on.runId, off.runId)
    val numbered = Set(
      ms.find(_.dblpKey == "conf/sigir/S1").get.mentionId,
      ms.find(_.dblpKey == "conf/kdd/S2").get.mentionId
    )
    assert(all.exists(c => Set(c.left, c.right) == numbered))
    assert(!kept.exists(c => Set(c.left, c.right) == numbered))
    // the unnumbered same-name pair is kept
    val bare = Set(
      ms.find(_.dblpKey == "conf/www/S3").get.mentionId,
      ms.find(_.dblpKey == "conf/icde/S4").get.mentionId
    )
    assert(kept.exists(c => Set(c.left, c.right) == bare))
  }
}
