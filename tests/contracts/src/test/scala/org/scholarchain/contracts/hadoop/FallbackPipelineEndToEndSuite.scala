package org.scholarchain.contracts.hadoop

import java.nio.file.Path as JPath

import org.apache.hadoop.conf.Configuration
import org.scholarchain.contracts.er.ErSynth
import org.scholarchain.contracts.hadoop.JobHarness.*
import org.scholarchain.core.{MatchCandidate, MentionId, WorkId}
import org.scholarchain.er.llm.AlwaysAbstainAdjudicator
import org.scholarchain.hadoop.blocking.*
import org.scholarchain.hadoop.candidates.CandidateFeatureJob
import org.scholarchain.hadoop.common.{Counters, JobResult, Settings}
import org.scholarchain.hadoop.pipeline.{Pipeline, PipelineResult, PipelineSpec}
import upickle.default.read

/** End to end J01 -> J09 -> entity resolution on a synthetic corpus with oversize blocks (decisions
  * 5, 8, 17, 18): the oversize fallback is bounded and counted, every member of an oversize block
  * is accounted for (covered or listed uncovered), every mention still gets exactly one identity
  * cluster, and outputs are identical across output roots.
  *
  * Corpus (maxBlockSize 5, fallback maxBlockSize 3, secondary key coauthor):
  *   - `Hot Synthname` on works 0..7: works 0..3 with coauthor `Shared Alpha`, 4..5 with `Shared
  *     Beta`, 6 with `Lonely Gamma`, 7 alone. Primary block `dblp-base-name/Hot Synthname` has 8
  *     members (oversize).
  *   - `H. Synthname` on works 8 (with `Shared Beta`) and 9 (with `Shared Alpha`). Primary block
  *     `initials-surname/h synthname` has 10 members (oversize).
  *   - Fallback `[Hot Synthname, Shared Alpha]` has 4 members and `[h synthname, Shared Alpha]` 5:
  *     both above the fallback cap, not emitted. `[Hot Synthname, Shared Beta]` (2) and `[h
  *     synthname, Shared Beta]` (3) are emitted.
  */
class FallbackPipelineEndToEndSuite extends munit.FunSuite {

  override val munitTimeout = scala.concurrent.duration.Duration(900, "s")

  private val Snapshot = "fb-2026-11-14"
  private def key(i: Int) = f"conf/sigir/FbHot$i%02d"
  private val authors: List[List[String]] =
    (0 until 4).map(_ => List("Hot Synthname", "Shared Alpha")).toList ++
      List(
        List("Hot Synthname", "Shared Beta"),
        List("Hot Synthname", "Shared Beta"),
        List("Hot Synthname", "Lonely Gamma"),
        List("Hot Synthname"),
        List("H. Synthname", "Shared Beta"),
        List("H. Synthname", "Shared Alpha"),
        List("Num Synthx 0001", "Coa Numa"), // works 10 and 11: one homonym-numbered dblp name
        List("Num Synthx 0001", "Coa Numb")
      )
  private def mention(i: Int, pos: Int) = MentionId.of(WorkId.fromDblpKey(key(i)), pos).value

  private lazy val work = workDir("fb-e2e")
  private lazy val input = write(
    work.resolve("in"),
    "fallback.xml",
    corpus(authors.zipWithIndex.map { case (as, i) => record(key(i), as) })
  )

  private val settings = List(
    Settings.Keys.BlockingFamilies -> "dblp-base-name,initials-surname",
    Settings.Keys.BlockingMaxBlock -> "5",
    Settings.Keys.FallbackFamilies -> "dblp-base-name,initials-surname",
    Settings.Keys.FallbackSecondaryKeys -> "coauthor",
    Settings.Keys.FallbackMaxBlock -> "3"
  )

  private def pipeline(out: String): (PipelineResult, Configuration) = {
    val c = conf(work, settings*)
    (Pipeline.run(c, PipelineSpec(hpath(input), Snapshot, Nil, hpath(work.resolve(out)))), c)
  }

  private lazy val (p, c) = pipeline("out-a")
  private lazy val (p2, _) = pipeline("out-b")

  private def blocks(r: JobResult, sub: String) = lines(c, r, sub).map(read[CandidateBlock](_))

  test("primary blocks are capped: no emitted block above maxBlockSize; oversize members counted") {
    val j05 = p.blocking
    blocks(j05, BlockingJob.Blocks).foreach(b =>
      assert(b.size <= 5, s"${b.family}/${b.blockKey}: ${b.size}")
    )
    // a block of exactly maxBlockSize members is emitted
    assert(
      blocks(j05, BlockingJob.Blocks).exists(b =>
        b.family == "dblp-base-name" && b.blockKey == "Shared Alpha" && b.size == 5
      )
    )
    assertEquals(j05.main(Counters.OversizeBlock), 2L)
    assertEquals(j05.main(Counters.OversizeMembers), 18L)
    assertEquals(lines(c, j05, BlockingJob.OversizeMembers).size, 18)
  }

  test("fallback blocks are capped too; the larger ones are counted and listed, not emitted") {
    val j05b = p.fallbackBlocking
    val fb = blocks(j05b, FallbackBlockingJob.Blocks)
    fb.foreach(b => assert(b.size <= 3, s"fallback ${b.family}/${b.blockKey}: ${b.size} > cap 3"))
    assertEquals(
      fb.map(b => (b.family, b.size)).sorted,
      List(("dblp-base-name.coauthor", 2), ("initials-surname.coauthor", 3))
    )
    assertEquals(j05b.main(Counters.OversizeBlock), 2L)
    val listed = lines(c, j05b, FallbackBlockingJob.OversizeBlocks).map(ujson.read(_))
    assertEquals(listed.map(_("size").num.toInt).sorted, List(4, 5))
    listed.foreach(o => assert(o("size").num > o("limit").num))
    // the largest-fallback-block statistic includes blocks that were NOT emitted (it can exceed
    // the cap, as 203 vs 200 in the real run); the emitted blocks never do
    assertEquals(j05b.stats("largestFallbackBlock").value, 5L)
    assertEquals(j05b.counter(Counters.NoSecondaryKeyGroup, "dblp-base-name.coauthor"), 1L)
  }

  test("every oversize member is either covered or listed uncovered, per oversize key") {
    val j05c = p.fallbackCoverage
    val summaries = lines(c, j05c, FallbackCoverageJob.Summary).map(read[OversizeKeySummary](_))
    val byKey = summaries.map(s => (s.family, s.blockKey) -> s).toMap
    val hot = byKey(("dblp-base-name", "Hot Synthname"))
    assertEquals((hot.members, hot.coveredMembers, hot.uncoveredMembers), (8L, 2L, 6L))
    val ini = byKey(("initials-surname", "h synthname"))
    assertEquals((ini.members, ini.coveredMembers, ini.uncoveredMembers), (10L, 3L, 7L))
    summaries.foreach(s => assertEquals(s.coveredMembers + s.uncoveredMembers, s.members))
    assertEquals(
      j05c.main(Counters.CoveredMembers) + j05c.main(Counters.UncoveredMembers),
      p.blocking.main(Counters.OversizeMembers)
    )
    val uncovered = lines(c, j05c, FallbackCoverageJob.Uncovered).map(read[UncoveredMember](_))
    val expectedHot = (List(0, 1, 2, 3, 6, 7).map(mention(_, 0))).toSet
    assertEquals(uncovered.filter(_.family == "dblp-base-name").map(_.mentionId).toSet, expectedHot)
    assertEquals(
      uncovered.filter(_.family == "initials-surname").map(_.mentionId).toSet,
      expectedHot + mention(9, 0)
    )
  }

  private lazy val candidates: List[MatchCandidate] =
    List(
      "dblp-base-name",
      "initials-surname",
      "dblp-base-name.coauthor",
      "initials-surname.coauthor"
    )
      .flatMap(f =>
        lines(c, p.candidates, CandidateFeatureJob.candidatesDir(f)).map(read[MatchCandidate](_))
      )

  test(
    "J06 expands primary and fallback blocks; uncovered members get no candidate in their family"
  ) {
    val fams = candidates.groupBy(_.blockFamily).view.mapValues(_.size).toMap
    assertEquals(fams.get("dblp-base-name.coauthor"), Some(1))
    assertEquals(fams.get("initials-surname.coauthor"), Some(3))
    val hotUncovered = List(0, 1, 2, 3, 6, 7).map(mention(_, 0)).toSet
    candidates
      .filter(_.blockFamily.startsWith("dblp-base-name"))
      .foreach(cd => assert(!hotUncovered(cd.left) && !hotUncovered(cd.right), cd.toString))
  }

  test("volume rule: skipSameNumberedName drops exactly the same-numbered pairs and counts them") {
    val numbered = Set(mention(10, 0), mention(11, 0))
    val emittedOff = candidates.filter(cd => Set(cd.left, cd.right) == numbered)
    assertEquals(emittedOff.map(_.blockFamily).sorted, List("dblp-base-name", "initials-surname"))
    val cSkip = conf(work, (settings :+ (Settings.Keys.CandidateSkipSameNumberedName -> "true"))*)
    val j06 = org.scholarchain.hadoop.common.JobRunner.run(
      cSkip,
      org.scholarchain.hadoop.common.JobRunner.fromRuns(
        List(p.blocking.runId, p.fallbackBlocking.runId)
      )(
        CandidateFeatureJob.spec(
          List(
            p.blocking.dir(BlockingJob.Blocks),
            p.fallbackBlocking.dir(FallbackBlockingJob.Blocks)
          ),
          hpath(work.resolve("out-skip")),
          Snapshot,
          _
        )
      )
    )
    assertNotEquals(j06.runId, p.candidates.runId, "the setting must change the run ID")
    assertEquals(j06.main(Counters.SameNumberedNameSkipped), 2L)
    assertEquals(
      j06.main(Counters.CandidatesEmitted) + 2L,
      p.candidates.main(Counters.CandidatesEmitted)
    )
    val skipped = List(
      "dblp-base-name",
      "initials-surname",
      "dblp-base-name.coauthor",
      "initials-surname.coauthor"
    ).flatMap(f =>
      lines(cSkip, j06, CandidateFeatureJob.candidatesDir(f)).map(read[MatchCandidate](_))
    )
    assert(!skipped.exists(cd => Set(cd.left, cd.right) == numbered))
    assertEquals(skipped.size.toLong + 2L, candidates.size.toLong)
  }

  private def er(pr: PipelineResult, out: String) =
    ErSynth.run(
      ErSynth.erConfig(local(pr.graphExport.outputDir), work.resolve(out)),
      new AlwaysAbstainAdjudicator(ErSynth.Model)
    )
  private lazy val erA = er(p, "er-a")

  test(
    "entity resolution assigns every mention to exactly one cluster, uncovered members included"
  ) {
    val assigned = ErSynth.assignments(erA.runDir)
    val all = authors.zipWithIndex.flatMap { case (as, i) => as.indices.map(mention(i, _)) }
    assertEquals(assigned.size, all.size)
    assertEquals(assigned.map(_.left).toSet, all.toSet)
    val rid = assigned.map(a => a.left -> a.right).toMap
    // all Hot Synthname mentions, covered or not, share their dblp-prior cluster
    assertEquals((0 until 8).map(i => rid(mention(i, 0))).distinct.size, 1)
    val ds = ErSynth.pairDecisions(erA.runDir)
    assertEquals(ds.size, candidates.size)
    // the pair (Hot@4, Hot@5) sits in two fallback families: one consistent outcome
    val pair = Set(mention(4, 0), mention(5, 0))
    val same = ds.filter(d => Set(d.left, d.right) == pair)
    assertEquals(same.size, 2)
    assertEquals(same.map(d => (d.outcome, d.matchPath, d.rulesFired)).distinct.size, 1)
  }

  private def candidateStageParts(pr: PipelineResult): Map[String, Seq[Byte]] =
    List(pr.blocking, pr.fallbackBlocking, pr.fallbackCoverage, pr.candidates, pr.graphExport)
      .flatMap(r => partFiles(r.outputDir).map { case (k, v) => s"${r.jobName}/$k" -> v.toSeq })
      .toMap

  test("determinism across output roots: J05, J05b, J05c, J06, J09 and entity resolution") {
    assertEquals(p2.all.map(_.runId), p.all.map(_.runId))
    assertEquals(candidateStageParts(p2), candidateStageParts(p))
    val erB = er(p2, "er-b")
    assertEquals(erB.runId, erA.runId)
    val skip = Set("_manifest.json")
    def bytes(d: JPath) = ErSynth.tree(d).collect { case (k, v) if !skip(k) => k -> v.toSeq }
    assertEquals(bytes(erB.runDir), bytes(erA.runDir))
  }
}
