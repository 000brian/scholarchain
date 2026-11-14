package org.scholarchain.er

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import org.scholarchain.core.*
import org.scholarchain.er.Fixture.W
import org.scholarchain.er.io.{Csv, ErError}
import org.scholarchain.er.llm.{AlwaysAbstainAdjudicator, Adjudicator}
import org.scholarchain.er.policy.Policy
import org.scholarchain.er.review.{QueueSpec, ReviewQueue}
import org.scholarchain.er.run.{ErConfig, ErRun, RunOutcome}

object RunFixture {
  val oX: String = Fixture.orcid("000000021825009")
  val oY: String = Fixture.orcid("000000034567123")

  val works: List[W] = List(
    W(
      "conf/sigir/R1",
      List("John Smith", "Alice Coauthor", "Wei Wang 0001"),
      title = "Neural Ranking Models"
    ),
    W("conf/sigir/R2", List("J. Smith", "Alice Coauthor"), year = 2021, title = "Neural Ranking"),
    W(
      "conf/sigir/R3",
      List("Jane Smith", "Bob Other"),
      year = 2016,
      venue = "conf/cvpr",
      title = "Segmentation"
    ),
    W("conf/sigir/R4", List("J. Smith", "Carol Third"), year = 2019, title = "Query Logs"),
    W(
      "conf/sigir/R5",
      List("Wei Wang 0001", "Dan Fourth"),
      year = 2018,
      title = "Graph Embeddings"
    ),
    W("conf/sigir/R6", List("Wei Wang 0001", "Eve Fifth"), year = 2022, title = "Graph Learning"),
    W("conf/sigir/R7", List("Wei Wang", "Fay Sixth"), year = 2023, title = "Graph Embeddings"),
    W("conf/sigir/R8", List("Wei Wang 0002", "Gus Seventh"), year = 2017, title = "Vision"),
    W(
      "conf/sigir/R9",
      List("Ann Bell", "Kim Lee", "Kim Lee"),
      orcids = Map(0 -> oX),
      title = "Topic F"
    ),
    W("conf/sigir/R10", List("Ann Bell"), orcids = Map(0 -> oY), year = 2015, title = "Topic G"),
    W("conf/sigir/R11", List("A. Bell"), orcids = Map(0 -> oX), year = 2024, title = "Topic H"),
    W("conf/sigir/R12", List("Li Zhang", "Mo One"), title = "Oversize A"),
    W("conf/sigir/R13", List("Li Zhang", "Mo Two"), title = "Oversize B"),
    W("conf/sigir/R14", List("Li Zhang", "Mo Three"), title = "Oversize C"),
    W("conf/sigir/R15", List("Li Zhang", "Mo Four"), title = "Oversize D"),
    W("conf/sigir/R20", List("Li Zhang", "Mo Five"), title = "Oversize E"),
    W("conf/sigir/R21", List("Li Zhang", "Mo Six"), title = "Oversize F"),
    W("conf/sigir/R22", List("Li Zhang", "Mo Seven"), title = "Oversize G"),
    W("conf/sigir/R16", List("Bob Jones", "Hal Eighth"), title = "Topic K"),
    W("conf/sigir/R17", List("Bob Jones", "Ivy Ninth"), year = 2025, title = "Topic L"),
    W(
      "conf/sigir/R18",
      List("Uma Arxiv"),
      doi = Some("10.48550/arxiv.2101.00001"),
      title = "Preprint"
    ),
    W(
      "conf/sigir/R19",
      List("Uma Arxiv"),
      ee = List("https://arxiv.org/abs/2101.00001v2"),
      title = "Preprint"
    )
  )

  def config(input: Path, out: Path, seed: Long = 7L, max: Int = 2): ErConfig =
    ErConfig(
      input,
      out,
      "2026-11-14T00:00:00Z",
      seed,
      "test-code",
      "fake-model/1",
      max,
      "test",
      QueueSpec(10, 2, seed),
      None,
      None,
      Policy.erV1
    )

  def run(
      input: Path,
      out: Path,
      adj: Adjudicator = new AlwaysAbstainAdjudicator("fake-model/1"),
      seed: Long = 7L
  ): RunOutcome =
    ErRun.run(config(input, out, seed), adj, _ => ())
}

class RunSuite extends munit.FunSuite {
  import RunFixture.*

  private lazy val input = Fixture.writeExport(works, maxBlock = 6)
  private lazy val first = run(input, Files.createTempDirectory("er-out-a"))
  private def file(rel: String) = first.runDir.resolve(rel)

  private def decisions(dir: Path): List[MatchDecision] =
    Fixture
      .tree(dir)
      .toList
      .filter(_._1.startsWith("decisions/mention_pair/"))
      .sortBy(_._1)
      .flatMap { case (_, b) =>
        new String(b, StandardCharsets.UTF_8)
          .split('\n')
          .filter(_.nonEmpty)
          .map(l => Contract.decode[MatchDecision](l).fold(e => fail(e.message), identity))
      }

  test("every match candidate gets exactly one valid decision with every policy field") {
    val cands = Fixture.readCandidates(input)
    val ds = decisions(first.runDir)
    assertEquals(ds.size, cands.size)
    assertEquals(ds.flatMap(_.candidateId).map(_.value).toSet, cands.map(_.candidateId.value).toSet)
    assertEquals(ds.flatMap(_.candidateId).distinct.size, ds.size)
    ds.foreach { d =>
      assertEquals(d.policyVersion, "er-v1")
      assertEquals(d.decisionTime, "2026-11-14T00:00:00Z")
      assertEquals(d.provenance.runId.value, first.runId)
      assert(d.provenance.sourceRecordIds.nonEmpty)
      assert(d.features.nonEmpty)
    }
    val outcomes = ds.map(_.outcome).toSet
    assert(outcomes.contains(MatchOutcome.Accepted))
    assert(outcomes.contains(MatchOutcome.Rejected))
    assert(outcomes.contains(MatchOutcome.Conflict))
    assert(outcomes.contains(MatchOutcome.Unresolved))
  }

  test(
    "conflicting ORCIDs and same-work mentions never end accepted; pair conflicts carry reason codes"
  ) {
    val ds = decisions(first.runDir)
    val bell = (Fixture.mentionId("conf/sigir/R9", 0), Fixture.mentionId("conf/sigir/R10", 0))
    val d = ds.filter(x => Set(x.left, x.right) == Set(bell._1, bell._2))
    assert(d.nonEmpty)
    assert(d.forall(_.outcome == MatchOutcome.Conflict))
    val kim = ds.filter(x =>
      Set(x.left, x.right) == Set(
        Fixture.mentionId("conf/sigir/R9", 1),
        Fixture.mentionId("conf/sigir/R9", 2)
      )
    )
    assert(
      kim.nonEmpty && kim.forall(x =>
        x.outcome == MatchOutcome.Conflict && x.reasonCodes.contains("same-work-mentions")
      )
    )
  }

  test(
    "the LLM band goes to the adjudicator up to the cap; abstentions end unresolved on the llm path"
  ) {
    val ds = decisions(first.runDir)
    val llm = ds.filter(_.matchPath == MatchPath.Llm)
    assert(llm.nonEmpty)
    llm.foreach { d =>
      assertEquals(d.outcome, MatchOutcome.Unresolved)
      assert(d.reasonCodes.contains("llm-abstain"))
      assertEquals(d.modelVersion, Some("fake-model/1"))
      assertEquals(d.promptVersion, Some("er-adjudication-prompt/1"))
    }
    val pairs = llm.map(d => (d.left, d.right)).distinct
    assert(pairs.size <= 2)
  }

  test(
    "every subset mention gets exactly one identity assignment, including oversize-block members without candidates"
  ) {
    val as = Fixture
      .lines(file("decisions/identity_assignment/part-00000.jsonl"))
      .map(l => Contract.decode[MatchDecision](l).toOption.get)
    val mentions = Fixture.members(works).map(_.mention.mentionId.value)
    assertEquals(as.map(_.left).sorted, mentions.sorted)
    as.foreach { a =>
      assertEquals(a.subjectKind, MatchSubjectKind.MentionResearcher)
      assertEquals(a.candidateId, None)
      assert(Set[MatchOutcome](MatchOutcome.Accepted, MatchOutcome.Conflict)(a.outcome))
    }
    val report = first.report
    assert(report("inputs")("mentionsWithoutCandidates").num >= 7)
    val zhang = as.filter(a =>
      works
        .filter(_.authors.contains("Li Zhang"))
        .map(w => Fixture.mentionId(w.key, 0))
        .contains(a.left)
    )
    assertEquals(zhang.size, 7)
    assertEquals(zhang.map(_.right).distinct.size, 1)
    val kimCluster = as.find(_.left == Fixture.mentionId("conf/sigir/R9", 1)).get
    assertEquals(kimCluster.outcome, MatchOutcome.Conflict)
    assert(kimCluster.reasonCodes.contains("cluster-same-work-mentions"))
  }

  test("same inputs and configuration give the same bytes in a different output root") {
    val second = run(input, Files.createTempDirectory("er-out-b"))
    assertEquals(second.runId, first.runId)
    val a = Fixture.tree(first.runDir)
    val b = Fixture.tree(second.runDir)
    assertEquals(a.keySet, b.keySet)
    a.foreach { case (k, v) => assert(java.util.Arrays.equals(v, b(k)), s"$k differs") }
  }

  test(
    "a completed output is reused only when it matches its manifest; tampered or incomplete outputs are refused"
  ) {
    val root = Files.createTempDirectory("er-out-c")
    val r1 = run(input, root)
    assert(!r1.reused)
    val r2 = run(input, root)
    assert(r2.reused)
    assertEquals(r2.runId, r1.runId)
    Files.write(
      r1.runDir.resolve("clusters/clusters.jsonl"),
      "tampered\n".getBytes(StandardCharsets.UTF_8)
    )
    intercept[ErError](run(input, root))
    val root2 = Files.createTempDirectory("er-out-d")
    Files.createDirectories(root2.resolve("run=" + r1.runId.stripPrefix("run:v1:")))
    intercept[ErError](run(input, root2))
  }

  test("the run ID changes with the seed and the manifest records inputs, config and outputs") {
    val other = run(input, Files.createTempDirectory("er-out-e"), seed = 8L)
    assertNotEquals(other.runId, first.runId)
    val m =
      ujson.read(new String(Files.readAllBytes(file("_manifest.json")), StandardCharsets.UTF_8))
    assertEquals(m("runId").str, first.runId)
    assertEquals(m("decisionTime").str, "2026-11-14T00:00:00Z")
    assertEquals(m("seed").str, "7")
    assertEquals(m("inputRun")("runId").str, Fixture.Run.value)
    assert(m("inputRuns").arr.map(_.str).contains(Fixture.Run.value))
    assert(m("inputFiles").arr.exists(_("path").str.startsWith("match_candidate/")))
    assert(m("outputs").arr.exists(_("path").str == "review-queue/review-queue.csv"))
    assertEquals(m("config")("policyVersion").str, "er-v1")
    assert(Files.isRegularFile(file("_SUCCESS")))
  }

  test("an invalid decision time or a model mismatch is refused before any output") {
    val root = Files.createTempDirectory("er-out-f")
    intercept[ErError](
      ErRun.run(
        config(input, root).copy(decisionTime = "2026-11-14 00:00"),
        new AlwaysAbstainAdjudicator("fake-model/1"),
        _ => ()
      )
    )
    intercept[ErError](
      ErRun.run(config(input, root), new AlwaysAbstainAdjudicator("other-model"), _ => ())
    )
    assertEquals(Fixture.tree(root).size, 0)
  }

  test(
    "work resolution: no external_id_link directory is zero coverage; shared arXiv IDs are conflicts"
  ) {
    val s = ujson.read(
      new String(Files.readAllBytes(file("work_resolution/summary.json")), StandardCharsets.UTF_8)
    )
    assertEquals(s("externalIdLinkDirectoryPresent").bool, false)
    assertEquals(s("exactDoiLinkCoverage")("works").num, 0.0)
    assertEquals(s("dblpIdentifierCollisions")("arxivId")("groups").num, 1.0)
    val ds = Fixture.lines(file("work_resolution/decisions.jsonl")).map(ujson.read(_))
    assert(ds.exists(d => d("idType").str == "arxiv" && d("outcome").str == "conflict"))
  }

  test(
    "review queue: label-blind CSV and JSONL, keyed by mention pair, strata and inclusion probabilities apart"
  ) {
    val csv = Csv
      .parse(
        new String(
          Files.readAllBytes(file("review-queue/review-queue.csv")),
          StandardCharsets.UTF_8
        )
      )
      .toOption
      .get
    assertEquals(csv.head.toList, ReviewQueue.Columns)
    val forbidden = List(
      "score",
      "decision",
      "outcome",
      "stratum",
      "family",
      "band",
      "probability",
      "confidence",
      "path",
      "rule",
      "reason"
    )
    csv.head.foreach(h =>
      forbidden.foreach(f => assert(!h.toLowerCase.contains(f), s"column $h reveals $f"))
    )
    val rows = csv.tail
    assertEquals(rows.size, 10)
    val json = Fixture.lines(file("review-queue/review-queue.jsonl")).map(ujson.read(_))
    assertEquals(json.map(j => ReviewQueue.Columns.map(c => j(c).str)), rows.map(_.toList).toList)
    rows.foreach { r =>
      assert(CodePointOrder.lessThan(r(1), r(2)))
      assert(r(6).startsWith("https://dblp.org/rec/conf/sigir/R"))
      assertEquals(r.takeRight(5).toList, List("", "", "", "", ""))
    }
    assertEquals(rows.map(r => (r(1), r(2))).distinct.size, rows.size)
    val key = Fixture.lines(file("review-queue/review-key.jsonl")).map(ujson.read(_))
    assertEquals(
      key.map(k => (k("leftMentionId").str, k("rightMentionId").str)),
      rows.map(r => (r(1), r(2))).toList
    )
    key.foreach(k =>
      k("memberships").arr.foreach(m =>
        assert(m("inclusionProbability").num > 0 && m("inclusionProbability").num <= 1)
      )
    )
    val prior =
      key.filter(_("memberships").arr.exists(_("stratum").str.contains("dblp-prior.numbered-name")))
    assertEquals(prior.size, 2)
    // m1: a dblp-prior row is not recognisable by its candidateId: same form as every other row
    val idForm = "match-candidate:v1:[0-9a-f]{32}"
    rows.foreach(r => assert(r(3).matches(idForm), s"candidateId '${r(3)}'"))
    assertEquals(rows.map(_(3)).distinct.size, rows.size)
    prior.foreach { p =>
      assertEquals(p("candidateIdKind").str, "dblp-prior-reference")
      assert(p("candidateId").str.matches(idForm))
      p("memberships").arr.foreach(m => assertEquals(m("candidateId").str, p("candidateId").str))
    }
    key
      .filterNot(prior.contains)
      .foreach(k => assertEquals(k("candidateIdKind").str, "match-candidate"))
    // no decision carries a reference ID: it is a review-queue reference only
    val decided = decisions(first.runDir).flatMap(_.candidateId).map(_.value).toSet
    prior.foreach(p => assert(!decided(p("candidateId").str)))
    val strata = ujson.read(
      new String(Files.readAllBytes(file("review-queue/strata.json")), StandardCharsets.UTF_8)
    )
    assertEquals(strata("seed").str, "7")
    assert(strata("strata").arr.exists(_("stratum").str.contains("outcome=unresolved")))
  }

  test(
    "m1: the gold reader and harness join a label file made from the queue, prior rows included"
  ) {
    val queueCsv = file("review-queue/review-queue.csv")
    val rows = Csv
      .parse(new String(Files.readAllBytes(queueCsv), StandardCharsets.UTF_8))
      .fold(e => fail(e), identity)
    val header = rows.head.toList
    val labeled = rows.tail.map { r =>
      val v = header.zip(r).toMap ++ Map(
        "label" -> "UNSURE",
        "labeler" -> "SYNTHETIC-TEST-LABELER",
        "labeledAt" -> "2026-11-14"
      )
      Csv.row(header.map(v))
    }
    val labelFile = Files.createTempFile("er-synthetic-labels", ".csv")
    Files.write(
      labelFile,
      (Csv.row(header) +: labeled).mkString("", "\r\n", "\r\n").getBytes(StandardCharsets.UTF_8)
    )
    val gold = eval.GoldLabels
      .read(labelFile, file("review-queue/review-queue.jsonl"))
      .fold(e => fail(e.mkString("; ")), identity)
    assertEquals(gold.labels.size, 10)
    val key = eval.KeyRow.readAll(file("review-queue/review-key.jsonl"))
    val joined = eval.Harness.join(gold, key)
    assertEquals(joined.size, 10)
    joined.foreach(j => assertEquals(j.gold.candidateId, j.key.candidateId))
    assertEquals(
      joined.count(_.key.memberships.exists(_._1.contains("dblp-prior.numbered-name"))),
      2
    )
    // a label file whose prior-row candidateId was blanked is refused, not joined silently
    val blanked = rows.tail.map { r =>
      val v = header.zip(r).toMap
      val k = key((v("leftMentionId"), v("rightMentionId")))
      val isPrior = k.memberships.exists(_._1.contains("dblp-prior.numbered-name"))
      Csv.row(header.map(h => if (h == "candidateId" && isPrior) "" else v(h)))
    }
    Files.write(
      labelFile,
      (Csv.row(header) +: blanked).mkString("", "\r\n", "\r\n").getBytes(StandardCharsets.UTF_8)
    )
    assert(eval.GoldLabels.read(labelFile, file("review-queue/review-queue.jsonl")).isLeft)
  }

  test(
    "m5: reuse re-verifies input content; an input changed in place with the same size is refused"
  ) {
    val in = Fixture.writeExport(works, maxBlock = 6)
    val root = Files.createTempDirectory("er-out-m5")
    val r1 = run(in, root)
    assert(run(in, root).reused)
    val wf = in.resolve("work/year=2020/part-r-00000")
    val text = new String(Files.readAllBytes(wf), StandardCharsets.UTF_8)
    val changed = text.replace("Neural Ranking Models", "Neural Rankinq Models")
    assertNotEquals(changed, text)
    assertEquals(changed.length, text.length)
    Files.write(wf, changed.getBytes(StandardCharsets.UTF_8))
    val e = intercept[ErError](run(in, root))
    assert(e.getMessage.contains("work/year=2020/part-r-00000 (content)"), e.getMessage)
    assert(Files.isRegularFile(r1.runDir.resolve("_SUCCESS"))) // nothing removed or overwritten
  }

  test(
    "run report: pairsSentToAdjudicator counts only real adjudicator calls; cache answers are reported apart"
  ) {
    val llm = first.report("llm")
    assert(llm("pairsSelected").num > 0)
    assertEquals(llm("pairsSentToAdjudicator").num, llm("pairsSelected").num)
    assertEquals(llm("pairsAnsweredFromCache").num, 0.0)
    val cacheFile = file("llm/adjudications.jsonl")
    val never = new ScriptedAdjudicator("fake-model/1")(_ =>
      throw new AssertionError("the cache answers every pair")
    )
    val cached = ErRun.run(
      config(input, Files.createTempDirectory("er-out-h")).copy(llmCache = Some(cacheFile)),
      never,
      _ => ()
    )
    val l2 = cached.report("llm")
    assertEquals(never.calls, 0)
    assertEquals(l2("pairsSelected").num, llm("pairsSelected").num)
    assertEquals(l2("pairsSentToAdjudicator").num, 0.0)
    assertEquals(l2("pairsAnsweredFromCache").num, llm("pairsSelected").num)
    assertEquals(l2("cacheHits").num, llm("pairsSelected").num)
  }

  test("the review queue order is seeded: another seed gives another queue") {
    val other = run(input, Files.createTempDirectory("er-out-g"), seed = 99L)
    val a = Fixture.lines(file("review-queue/review-queue.jsonl"))
    val b = Fixture.lines(other.runDir.resolve("review-queue/review-queue.jsonl"))
    assertNotEquals(a, b)
  }
}
