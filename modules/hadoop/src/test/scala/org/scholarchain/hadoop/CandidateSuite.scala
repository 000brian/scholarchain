package org.scholarchain.hadoop

import java.nio.file.Path as JPath

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.scholarchain.core.*
import org.scholarchain.hadoop.blocking.{BlockMember, BlockingJob, CandidateBlock}
import org.scholarchain.hadoop.candidates.{CandidateFeatureJob, CandidateFeatures}
import org.scholarchain.hadoop.common.*
import org.scholarchain.hadoop.pipeline.{DblpChain, Pipeline}
import org.scholarchain.hadoop.teams.{MentionContext, PublicationTeamJob}
import upickle.default.{read, write}

/** J05 block members (v2) and J06 candidate feature construction. */
class CandidateSuite extends munit.FunSuite {

  override val munitTimeout = scala.concurrent.duration.Duration(900, "s")

  private val run = RunContext(
    RunId.of("j", "c", "i", "f").toOption.get,
    CandidateFeatureJob.JobName,
    "dblp",
    "2026-11-01",
    CandidateFeatures.FeatureSet
  )
  private val orcid = "0000-0002-1825-0097"

  private def member(
      key: String,
      pos: Int,
      name: String,
      year: Int = 2020,
      orcid: Option[String] = None,
      venue: Option[String] = Some("conf/sigir"),
      coauthors: List[String] = Nil,
      truncated: Boolean = false
  ): BlockMember = {
    val w = WorkId.fromDblpKey(key)
    BlockMember(
      MentionId.of(w, pos).value,
      w.value,
      key,
      name,
      year,
      orcid,
      venue,
      coauthors,
      truncated
    )
  }

  private def block(ms: BlockMember*): CandidateBlock = {
    val sorted = ms.toList.sortWith((a, b) => CodePointOrder.lessThan(a.mentionId, b.mentionId))
    CandidateBlock(
      2,
      "dblp-base-name",
      "Wei Wang",
      sorted.size,
      sorted,
      "blocking/1-provisional",
      "r"
    )
  }

  private def features(fs: List[MatchFeature]): Map[String, Double] =
    fs.map(f => f.name -> f.value).toMap

  // ------------------------------------------------------------------ unit: features

  test("features: every computable feature, sorted by name, symmetric") {
    val a = member(
      "conf/sigir/A20",
      0,
      "Wei Wang 0001",
      2020,
      Some(orcid),
      coauthors = List("Ann Lee", "Bo Li")
    )
    val b = member(
      "conf/kdd/B21",
      0,
      "Wei Wang 0001",
      2023,
      Some(orcid),
      Some("conf/kdd"),
      List("Ann Lee", "Cy Do")
    )
    val fs = CandidateFeatures.compute(a, b)
    assertEquals(fs.map(_.name), CandidateFeatures.names)
    assertEquals(fs.map(_.name), fs.map(_.name).sorted(CodePointOrder.ordering))
    assertEquals(
      features(fs),
      Map(
        "coauthor_shared_count" -> 1.0,
        "homonym_suffix_match" -> 1.0,
        "name_exact_match" -> 1.0,
        "orcid_exact_match" -> 1.0,
        "same_venue" -> 0.0,
        "same_work" -> 0.0,
        "year_abs_diff" -> 3.0
      )
    )
    assertEquals(CandidateFeatures.compute(b, a), fs)
  }

  test("features: uncomputable features are omitted, never defaulted") {
    val a = member("conf/sigir/C22", 0, "Wei Wang", venue = None, coauthors = List("X"))
    val b = member("conf/sigir/C22", 1, "Wei Wang 0002", coauthors = List("X"), truncated = true)
    val fs = CandidateFeatures.compute(a, b)
    assertEquals(
      features(fs),
      Map("name_exact_match" -> 0.0, "same_work" -> 1.0, "year_abs_diff" -> 0.0)
    )
    assertEquals(
      CandidateFeatures.omitted(fs),
      List("coauthor_shared_count", "homonym_suffix_match", "orcid_exact_match", "same_venue")
    )
    // different suffixes and different ORCID iDs are computed as 0, not omitted
    val c = member("conf/sigir/D1", 0, "Wei Wang 0001", orcid = Some(orcid))
    val d = member("conf/sigir/D2", 0, "Wei Wang 0002", orcid = Some("0000-0003-3226-9189"))
    val cd = features(CandidateFeatures.compute(c, d))
    assertEquals(cd("homonym_suffix_match"), 0.0)
    assertEquals(cd("orcid_exact_match"), 0.0)
    assertEquals(cd("coauthor_shared_count"), 0.0)
  }

  test("features: names and coauthors compare under canonical equivalence (NFC = NFD)") {
    val nfc = "Jos\u00e9 Garc\u00eda"
    val nfd = "Jose\u0301 Garci\u0301a"
    val a = member("conf/sigir/N1", 0, nfc + " 0001", coauthors = List("Ren\u00e9 M\u00fcller"))
    val b = member("conf/kdd/N2", 0, nfd + " 0001", coauthors = List("Rene\u0301 Mu\u0308ller"))
    val fs = features(CandidateFeatures.compute(a, b))
    assertEquals(fs("name_exact_match"), 1.0)
    assertEquals(fs("homonym_suffix_match"), 1.0)
    assertEquals(fs("coauthor_shared_count"), 1.0)
    assertEquals(CandidateFeatures.compute(b, a), CandidateFeatures.compute(a, b))
    // Not equivalent: case and accent differences stay unequal.
    val c = member("conf/kdd/N3", 0, "Jose Garcia 0001", coauthors = List("Rene Muller"))
    val ac = features(CandidateFeatures.compute(a, c))
    assertEquals(ac("name_exact_match"), 0.0)
    assertEquals(ac("coauthor_shared_count"), 0.0)
  }

  test("candidate: canonical mention order, stable ID, provenance, valid MatchCandidate") {
    val a = member("conf/sigir/A20", 0, "Wei Wang 0001")
    val b = member("conf/kdd/B21", 2, "Wei Wang")
    val blk = block(a, b)
    val c = CandidateFeatures.candidate(blk, a, b, run).toOption.get
    assertEquals(CandidateFeatures.candidate(blk, b, a, run), Right(c))
    assert(CodePointOrder.lessThan(c.left, c.right))
    assertEquals(Set(c.left, c.right), Set(a.mentionId, b.mentionId))
    assertEquals(
      c.candidateId,
      MatchCandidateId
        .of(MatchSubjectKind.MentionPair, a.mentionId, b.mentionId, "dblp-base-name", "Wei Wang")
        .toOption
        .get
    )
    assertEquals(c.subjectKind, MatchSubjectKind.MentionPair)
    assertEquals(c.schemaVersion, 1)
    val (l, r) = if (c.left == a.mentionId) (a, b) else (b, a)
    assertEquals(c.provenance.sourceRecordIds, List(l.dblpKey, r.dblpKey))
    assertEquals(c.provenance.sourceType, SourceType.Dblp)
    assertEquals(
      c.provenance.policyVersion,
      "candidate-features/1-provisional;blocking/1-provisional"
    )
    assertEquals(c.provenance.contentHash, None)
    assertEquals(Contract.validate(c), Nil)
    // two mentions of one work name the work's dblp key once
    val s0 = member("conf/sigir/C22", 0, "Wei Wang")
    val s1 = member("conf/sigir/C22", 1, "Wei Wang 0002")
    val same = CandidateFeatures.candidate(block(s0, s1), s0, s1, run).toOption.get
    assertEquals(same.provenance.sourceRecordIds, List("conf/sigir/C22"))
    // a self pair has no candidate
    assert(CandidateFeatures.candidate(block(a), a, a, run).isLeft)
  }

  test("candidate block contract v2: well-formed blocks pass; structural problems are listed") {
    val a = member("conf/sigir/A20", 0, "Wei Wang 0001")
    val b = member("conf/kdd/B21", 0, "Wei Wang")
    val ok = block(a, b)
    assertEquals(CandidateBlock.problems(ok), Nil)
    assertEquals(read[CandidateBlock](write(ok)), ok)
    assertEquals(CandidateBlock.problems(ok.copy(blockVersion = 1)).size, 1)
    assertEquals(CandidateBlock.problems(ok.copy(size = 3)).size, 1)
    assertEquals(CandidateBlock.problems(ok.copy(members = ok.members.reverse)).size, 1)
    assertEquals(CandidateBlock.problems(ok.copy(members = List(a, a), size = 2)).size, 1)
    assertEquals(CandidateBlock.problems(ok.copy(family = "Bad Family")).size, 1)
    assertEquals(CandidateBlock.problems(ok.copy(blockKey = " ")).size, 1)
  }

  test("J05 block members carry the mention context J06 compares") {
    val w = WorkId.fromDblpKey("conf/sigir/A20")
    val c = MentionContext(
      1,
      MentionId.of(w, 1).value,
      w.value,
      "conf/sigir/A20",
      1,
      "Wei Wang 0001",
      Some(orcid),
      2020,
      Some("conf/sigir"),
      None,
      "t",
      60,
      List("Ann Lee"),
      true,
      "dblp",
      "2026-11-01"
    )
    assertEquals(
      BlockMember.of(c),
      BlockMember(
        c.mentionId,
        w.value,
        "conf/sigir/A20",
        "Wei Wang 0001",
        2020,
        Some(orcid),
        Some("conf/sigir"),
        List("Ann Lee"),
        true
      )
    )
  }

  // ------------------------------------------------------------------ local integration

  private val xml: String =
    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<dblp>\n" +
      s"<inproceedings key=\"conf/sigir/A20\"><author orcid=\"$orcid\">Wei Wang 0001</author>" +
      "<author>Ann Lee</author><author>Bo Li</author><title>A.</title><year>2020</year>" +
      "</inproceedings>\n" +
      s"<inproceedings key=\"conf/kdd/B21\"><author orcid=\"$orcid\">Wei Wang 0001</author>" +
      "<author>Ann Lee</author><title>B.</title><year>2021</year></inproceedings>\n" +
      "<article key=\"journals/pvldb/C22\"><author>Wei Wang</author><author>Wei Wang 0002</author>" +
      "<author>Bo Li</author><title>C.</title><year>2022</year></article>\n</dblp>\n"

  private final case class Run(
      blocking: JobResult,
      candidates: JobResult,
      list: List[MatchCandidate]
  )

  private def runJ05J06(work: JPath, conf: Configuration, outName: String): Run = {
    val in = TestSupport.writeFile(work, "in/homonyms.xml", xml)
    val out = new Path(work.resolve(outName).toUri)
    val chain = DblpChain.run(conf, in, out, "h")
    val blocking = JobRunner.run(
      conf,
      BlockingJob.spec(
        chain.teams.dir(PublicationTeamJob.Contexts),
        out,
        "h",
        JobRunner.fingerprintRuns(List(chain.teams.runId))
      )
    )
    val candidates = JobRunner.run(
      conf,
      CandidateFeatureJob.spec(
        blocking.dir(BlockingJob.Blocks),
        out,
        "h",
        JobRunner.fingerprintRuns(List(blocking.runId))
      )
    )
    val list = JobRunner
      .readPartLines(
        conf,
        new Path(candidates.dir(CandidateFeatureJob.candidatesDir("dblp-base-name")).toString)
      )
      .map(l => Contract.decode[MatchCandidate](l).fold(e => fail(e.message), identity))
    Run(blocking, candidates, list)
  }

  test("J06 local run: all within-block pairs, counters, layout, determinism across sharding") {
    val work = TestSupport.tempDir("j06")
    val conf = TestSupport.localConf(work)
    val r = runJ05J06(work, conf, "out")
    // blocks: Wei Wang (4 mentions, 6 pairs), Ann Lee (2, 1), Bo Li (2, 1)
    assertEquals(r.blocking.main(Counters.BlocksEmitted), 3L)
    val c = r.candidates
    assertEquals(c.main(Counters.Input), 3L)
    assertEquals(c.main(Counters.Accepted), 3L)
    assertEquals(c.main(Counters.Rejected), 0L)
    assertEquals(c.main(Counters.CandidatesEmitted), 8L)
    assertEquals(c.main(Counters.CandidateShards), 3L)
    assertEquals(c.main(Counters.OversizeBlock), 0L)
    assertEquals(c.stats("largestCandidateGroup").value, 6L)
    assertEquals(c.stats("largestCandidateGroup").subject, "dblp-base-name\tWei Wang")
    assertEquals(c.stats("largestBlockSeen").value, 4L)
    assertEquals(r.list.size, 8)
    assertEquals(r.list.map(_.candidateId).distinct.size, 8)
    assert(r.list.forall(x => Contract.validate(x).isEmpty))
    assertEquals(r.list.map(_.blockKey).distinct.sorted, List("Ann Lee", "Bo Li", "Wei Wang"))
    // per-feature omission counters agree with the records
    CandidateFeatures.names.foreach { n =>
      assertEquals(
        c.counter(Counters.FeatureOmittedGroup, n),
        r.list.count(x => !x.features.exists(_.name == n)).toLong,
        n
      )
    }
    // the two ORCID-bearing "Wei Wang 0001" mentions
    val a20 = MentionId.of(WorkId.fromDblpKey("conf/sigir/A20"), 0).value
    val b21 = MentionId.of(WorkId.fromDblpKey("conf/kdd/B21"), 0).value
    val ww = r.list.find(x => Set(x.left, x.right) == Set(a20, b21)).get
    assertEquals(
      features(ww.features),
      Map(
        "coauthor_shared_count" -> 1.0,
        "homonym_suffix_match" -> 1.0,
        "name_exact_match" -> 1.0,
        "orcid_exact_match" -> 1.0,
        "same_venue" -> 0.0,
        "same_work" -> 0.0,
        "year_abs_diff" -> 1.0
      )
    )
    // no thresholds, scores or decisions: features only
    assert(r.list.forall(_.features.map(_.name).forall(CandidateFeatures.names.contains)))

    // one pair per shard: same candidates (as a set), spread over several reducers
    val sharded =
      runJ05J06(
        work,
        TestSupport.localConf(work, "scholarchain.candidates.pairsPerShard" -> "1"),
        "sharded"
      )
    assertEquals(sharded.candidates.main(Counters.CandidateShards), 8L)
    assertEquals(
      sharded.list
        .map(x => write(x.copy(provenance = x.provenance.copy(runId = run.runId))))
        .sorted,
      r.list.map(x => write(x.copy(provenance = x.provenance.copy(runId = run.runId)))).sorted
    )
    val parts =
      TestFiles.partFiles(sharded.candidates.outputDir).keys.filter(_.startsWith("candidates/"))
    assert(parts.size > 1, s"shards of one block should land on several reducers: $parts")

    // byte-identical outputs across output roots
    val again = runJ05J06(work, conf, "again")
    assertEquals(again.candidates.runId, r.candidates.runId)
    val fx = TestFiles.partFiles(r.candidates.outputDir)
    val fy = TestFiles.partFiles(again.candidates.outputDir)
    assertEquals(fx.keySet, fy.keySet)
    fx.foreach { case (rel, bytes) => assert(java.util.Arrays.equals(bytes, fy(rel)), rel) }
  }

  test("J06 oversize blocks are counted and listed, not expanded; feature set is checked") {
    val work = TestSupport.tempDir("j06-oversize")
    val conf = TestSupport.localConf(work, "scholarchain.candidates.maxPairsPerBlock" -> "5")
    val r = runJ05J06(work, conf, "out")
    val c = r.candidates
    assertEquals(c.main(Counters.OversizeBlock), 1L)
    assertEquals(c.main(Counters.OversizeBlockPairsSkipped), 6L)
    assertEquals(c.main(Counters.CandidatesEmitted), 2L)
    assertEquals(c.stats("largestBlockSeen").value, 4L)
    assertEquals(c.stats("largestCandidateGroup").value, 1L)
    val listed = TestSupport.lines(conf, c, CandidateFeatureJob.OversizeBlocks).map(ujson.read(_))
    assertEquals(listed.map(_("blockKey").str), List("Wei Wang"))
    assertEquals(listed.map(_("pairs").num), List(6.0))
    assertEquals(c.main(Counters.Input), c.main(Counters.Accepted) + c.main(Counters.Rejected))

    val bad = TestSupport.localConf(work, "scholarchain.candidates.featureSet" -> "features/9")
    val err = intercept[SettingsError](runJ05J06(work, bad, "bad"))
    assertEquals(err.key, "scholarchain.candidates.featureSet")
  }

  test("J06 rejects malformed block lines with a reason and counts them") {
    val work = TestSupport.tempDir("j06-rejects")
    val conf = TestSupport.localConf(work)
    val good =
      block(member("conf/sigir/A20", 0, "Wei Wang 0001"), member("conf/kdd/B21", 0, "Wei Wang"))
    val lines = List(
      write(good),
      write(good), // identical copy: collapsed
      "{not json",
      write(good.copy(blockVersion = 1)),
      ""
    )
    val in = TestSupport.writeFile(work, "blocks/part-r-00000", lines.mkString("\n") + "\n")
    val c = JobRunner.run(
      conf,
      CandidateFeatureJob.spec(
        new Path(in.getParent.toString),
        new Path(work.resolve("out").toUri),
        "x",
        "sha256:test"
      )
    )
    assertEquals(c.main(Counters.Input), 5L)
    assertEquals(c.main(Counters.Accepted), 2L)
    assertEquals(c.main(Counters.Rejected), 3L)
    assertEquals(c.counter(Counters.RejectReasonGroup, "schema_failure"), 2L)
    assertEquals(c.counter(Counters.RejectReasonGroup, "blank_line"), 1L)
    assertEquals(c.main(Counters.Duplicate), 1L)
    assertEquals(c.main(Counters.CandidatesEmitted), 1L)
    val rejects = TestSupport.lines(conf, c, "rejects/v1").map(read[Disposition](_))
    assert(rejects.forall(_.jobName == CandidateFeatureJob.JobName))
  }

  test("J09 exports J06 candidates as match_candidate (synthetic pipeline)") {
    val work = TestSupport.tempDir("j06-export")
    val conf = TestSupport.localConf(work)
    val p =
      Pipeline.run(conf, LocalBoundedPipeline.syntheticSpec(new Path(work.resolve("o").toUri)))
    val emitted = p.candidates.main(Counters.CandidatesEmitted)
    assert(emitted > 0L, "synthetic homonyms should yield candidates")
    val exported = JobRunner
      .readPartLines(conf, new Path(p.graphExport.outputDir, "match_candidate"))
      .map(l => Contract.decode[MatchCandidate](l).fold(e => fail(e.message), identity))
    assertEquals(exported.size.toLong, emitted)
    assertEquals(exported.map(_.candidateId).distinct.size.toLong, emitted)
  }
}
