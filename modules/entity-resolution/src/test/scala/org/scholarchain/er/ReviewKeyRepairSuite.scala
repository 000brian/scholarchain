package org.scholarchain.er

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import org.scholarchain.core.*
import org.scholarchain.er.Fixture.W
import org.scholarchain.er.eval.*
import org.scholarchain.er.review.*
import org.scholarchain.er.run.Report

/** Repair round for code-reviewer findings m-new-1 (per-membership decisions in the review key),
  * m-new-2 (final-cluster assignment in the review key and the harness) and m-new-3 (the blocking
  * recall gap for middle-name variants). Synthetic data only.
  */
class ReviewKeyRepairSuite extends munit.FunSuite {

  private def tmp(text: String): Path = {
    val f = Files.createTempFile("er-review-key-", ".jsonl")
    Files.write(f, text.getBytes(StandardCharsets.UTF_8))
    f
  }

  private def gold(k: KeyRow, label: String) =
    GoldLabel(
      k.left,
      k.right,
      k.candidateId,
      label,
      List("other"),
      "SYNTHETIC",
      "SYNTHETIC-TEST-LABELER",
      "2026-11-14"
    )

  private val CandStratum =
    StratumSampler.stratum("probabilistic", "unresolved", "initials-surname", near = false)

  test(
    "m-new-1: a dblp-prior pair also sampled by a candidate stratum keeps each membership's own decision"
  ) {
    val (c, _, _) = Fixture.corpus(
      List(
        W("conf/sigir/K1", List("Wei Wang 0001")),
        W("conf/sigir/K2", List("Wei Wang 0001"), year = 2021)
      )
    )
    val a = c.mentions.indexOf(Fixture.mentionId("conf/sigir/K1", 0))
    val b = c.mentions.indexOf(Fixture.mentionId("conf/sigir/K2", 0))
    val spec = QueueSpec(3, 1, 5L)
    val sampler = new StratumSampler(spec)
    val candId = "match-candidate:v1:" + "c" * 32
    sampler.observe(
      CandStratum,
      candId,
      a,
      b,
      SampleInfo(
        "unresolved",
        "probabilistic",
        "initials-surname",
        Some(0.5),
        0.5,
        false,
        Nil,
        List("score.unresolved"),
        List("score-between-thresholds")
      )
    )
    val prior = SampleInfo(
      "accepted",
      "deterministic",
      StratumSampler.PriorFamily,
      None,
      0.99,
      false,
      Nil,
      List("dblp-prior.cluster"),
      Nil
    )
    // the dblp-prior pair is inserted first, then joined by the candidate membership
    val built = ReviewQueue.build(c, spec, sampler, (_, _) => prior)
    assertEquals(built.rows.size, 1)
    val row = built.rows.head
    assertEquals(
      row.memberships.map(m => (m.stratum, m.outcome, m.path)).toSet,
      Set(
        (StratumSampler.PriorStratum, "accepted", "deterministic"),
        (CandStratum, "unresolved", "probabilistic")
      )
    )
    // the row shows the real candidate, and its row-level decision is that candidate's
    assertEquals(row.candidateId, candId)
    assertEquals((row.info.outcome, row.info.path), ("unresolved", "probabilistic"))

    val k = KeyRow
      .readAll(tmp(ReviewQueue.key(c, built.rows, _ => "researcher:v1:" + "d" * 32)))
      .values
      .head
    val expected =
      Map(StratumSampler.PriorStratum -> "accepted", CandStratum -> "unresolved")
    assertEquals(k.decisions.map(d => d.stratum -> d.outcome).toMap, expected)
    assertEquals(
      k.decisions.map(d => d.stratum -> d.path).toMap,
      Map(StratumSampler.PriorStratum -> "deterministic", CandStratum -> "probabilistic")
    )
    // the weighted harness scores each membership with its own decision
    val units = Harness.weightedUnits(List(Harness.Labeled(gold(k, "MATCH"), k)))
    assertEquals(units.map(u => u.stratum -> u.outcome).toMap, expected)
    // the label file stays label-blind: no decision or cluster column
    ReviewQueue.Columns.foreach { col =>
      List("outcome", "path", "stratum", "researcher", "cluster").foreach(f =>
        assert(!col.toLowerCase.contains(f), s"column $col reveals $f")
      )
    }
    assertEquals(ReviewQueue.values(c, row, 1).size, ReviewQueue.Columns.size)
  }

  test("m-new-1: weighted and per-stratum metrics score each membership with its own decision") {
    val sp = StratumSampler.PriorStratum
    val sc = StratumSampler.stratum("probabilistic", "rejected", "initials-surname", near = false)
    val k = KeyRow(
      "mention:v1:" + "0" * 31 + "1",
      "mention:v1:" + "0" * 31 + "2",
      "match-candidate:v1:" + "0" * 31 + "1",
      List(sp -> 100.0, sc -> 10.0),
      "rejected",
      "probabilistic",
      "initials-surname",
      Some(0.1),
      0.9,
      Nil,
      Nil,
      Nil,
      membershipDecisions = List("accepted" -> "deterministic", "rejected" -> "probabilistic"),
      leftResearcherId = Some("researcher:v1:r1"),
      rightResearcherId = Some("researcher:v1:r1"),
      sameFinalCluster = Some(true)
    )
    val m = Harness.evaluate(
      GoldSet(List(gold(k, "MATCH")), 0),
      Map((k.left, k.right) -> k),
      None,
      3L,
      50,
      Some("SYNTHETIC")
    )
    val rr = m("researcherResolution")
    // weighted: the prior membership (weight 100) is a hit, the candidate membership (10) a miss
    assertEqualsDouble(rr("weighted")("metrics")("recall")("value").num, 100.0 / 110, 1e-12)
    assertEquals(rr("byStratum")(sp)("recall")("value").num, 1.0)
    assertEquals(rr("byStratum")(sc)("recall")("value").num, 0.0)
    assertEquals(rr("byMatchPath").obj.keySet.toSet, Set("deterministic", "probabilistic"))
    assertEquals(rr("byMatchPath")("deterministic")("recall")("value").num, 1.0)
    // the unweighted row metric uses the row decision (the shown candidate's)
    assertEquals(rr("unweighted")("recall")("value").num, 0.0)
    // m-new-2: the pair was rejected, yet both mentions share one final cluster
    assertEquals(m("researcherClusterAssignment")("unweighted")("recall")("value").num, 1.0)
  }

  test(
    "m-new-2 and m-new-3: the review key records the final ResearcherIds; the run report records the middle-name recall gap"
  ) {
    val works = RunFixture.works :+ W(
      "conf/sigir/R30",
      List("John A. Smith", "Zed Coauthor"),
      title = "Neural Ranking Models"
    )
    val input = Fixture.writeExport(works, maxBlock = 6)
    val out = RunFixture.run(input, Files.createTempDirectory("er-out-repair"))
    val assigned = Fixture
      .lines(out.runDir.resolve("decisions/identity_assignment/part-00000.jsonl"))
      .map(l => Contract.decode[MatchDecision](l).fold(e => fail(e.message), identity))
      .map(d => d.left -> d.right)
      .toMap
    val raw = Fixture.lines(out.runDir.resolve("review-queue/review-key.jsonl")).map(ujson.read(_))
    assert(raw.nonEmpty)
    raw.foreach { r =>
      val l = r("leftMentionId").str
      val rt = r("rightMentionId").str
      assertEquals(r("leftResearcherId").str, assigned(l))
      assertEquals(r("rightResearcherId").str, assigned(rt))
      assertEquals(r("sameFinalCluster").bool, assigned(l) == assigned(rt))
      r("memberships").arr.foreach { m =>
        assert(m.obj.contains("outcome") && m.obj.contains("matchPath"), m.render())
      }
      // dblp-prior rows share one cluster by construction
      if (r("candidateIdKind").str == CandidateIdKind.PriorReference)
        assert(r("sameFinalCluster").bool, r.render())
    }
    val key = KeyRow.readAll(out.runDir.resolve("review-queue/review-key.jsonl"))
    assert(key.values.forall(_.sameFinalCluster.isDefined))
    // the queue files stay label-blind
    List("review-queue/review-queue.csv", "review-queue/review-queue.jsonl").foreach { f =>
      val text =
        new String(Files.readAllBytes(out.runDir.resolve(f)), StandardCharsets.UTF_8)
      assert(!text.contains("researcher:v1:"), f)
      assert(!text.contains("sameFinalCluster"), f)
    }

    // m-new-3: "John A. Smith" never shares a block with "John Smith" or "J. Smith"
    val jas = Fixture.mentionId("conf/sigir/R30", 0)
    val js = Set(Fixture.mentionId("conf/sigir/R1", 0), Fixture.mentionId("conf/sigir/R2", 0))
    val cands = Fixture.readCandidates(input)
    assert(
      !cands.exists(c => Set(c.left, c.right).contains(jas) && js.exists(Set(c.left, c.right)))
    )
    assertNotEquals(
      AuthorNameKey.initialsSurnameKey("John A. Smith"),
      AuthorNameKey.initialsSurnameKey("John Smith")
    )
    val gaps = out.report("knownRecallGaps").arr
    val gap = gaps.find(_("id").str == "blocking.middle-name-variants").getOrElse(fail("no gap"))
    assert(gap("description").str.contains("John A. Smith -> j a smith"))
    assert(gap("description").str.contains("human decision"))
    // (j, smith) holds the keys "j smith" (John Smith, J. Smith, Jane Smith) and "j a smith"
    assertEquals(gap("firstInitialSurnameGroupsSplitByMiddleInitials").num, 1.0)
    assertEquals(gap("dblpNamesInThoseGroups").num, 4.0)
  }

  test("m-new-3: the middle-initial split count is hand-checkable") {
    assertEquals(
      Report.middleInitialSplit(
        List(
          "John A. Smith",
          "John Smith",
          "J. Smith",
          "Jane Smith",
          "Wei Wang 0001",
          "Wei Wang",
          "W. X. Wang 0002",
          "Plato"
        )
      ),
      (2, 7)
    )
    assertEquals(Report.middleInitialSplit(List("Ann Bell", "A. Bell", "Wei Wang 0001")), (0, 0))
  }
}
