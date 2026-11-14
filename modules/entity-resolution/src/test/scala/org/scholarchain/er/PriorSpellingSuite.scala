package org.scholarchain.er

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import org.scholarchain.core.*
import org.scholarchain.er.Fixture.W
import org.scholarchain.er.data.Loader
import org.scholarchain.er.io.Csv
import org.scholarchain.er.llm.LlmStage
import org.scholarchain.er.policy.{DecisionEngine, Policy, Rules}
import org.scholarchain.er.review.ReviewQueue
import org.scholarchain.er.run.RunOutcome

/** Decision 5 and decision 13 (repair finding F3): the dblp prior is one cluster per exact dblp
  * name string; canonically equivalent spellings join only through accepted decisions; every output
  * that shows a mention shows the name as written on that mention.
  */
class PriorSpellingSuite extends munit.FunSuite {
  private val Nfc = "René Spellname"
  private val Nfd = "René Spellname"

  private val works = List(
    W("conf/sigir/P1", List(Nfc, "Coa Pa"), title = "Graph Mining"),
    W("conf/sigir/P2", List(Nfd, "Coa Pb"), year = 2021, title = "Graph Search"),
    W("conf/sigir/P3", List(Nfc, "Coa Pc"), year = 2019, title = "Graph Ranking")
  )
  private val nameOf: Map[String, String] =
    works
      .flatMap(w => w.authors.indices.map(i => Fixture.mentionId(w.key, i) -> w.authors(i)))
      .toMap

  private def runOn(input: Path, root: String): RunOutcome =
    RunFixture.run(input, Files.createTempDirectory(root))

  private lazy val input = Fixture.writeExport(works)
  private lazy val outcome = runOn(input, "er-spelling-a")
  private def lines(rel: String) = Fixture.lines(outcome.runDir.resolve(rel))
  private lazy val assigned = lines("decisions/identity_assignment/part-00000.jsonl")
    .map(l => Contract.decode[MatchDecision](l).fold(e => fail(e.message), identity))
  private def assignment(key: String, pos: Int) =
    assigned.find(_.left == Fixture.mentionId(key, pos)).getOrElse(fail(s"no assignment $key"))

  test("the dblp prior has one cluster per exact source string; the core key still relates them") {
    val c = Loader.load(input, "dblp-2026-11-01").corpus
    assertEquals(c.names.spelling.toSet, Set(Nfc, Nfd, "Coa Pa", "Coa Pb", "Coa Pc"))
    val nfc = c.names.spelling.indexOf(Nfc)
    assertEquals(c.priorClusterMembers(nfc).length, 2)
    assertEquals(c.priorClusterMembers(c.names.spelling.indexOf(Nfd)).length, 1)
    assertEquals(outcome.report("clusters")("dblpPriorClusters").num, 5.0)
  }

  test(
    "an exact-string pair keeps dblp-prior.same-name; a canonically equivalent pair is its own rule"
  ) {
    val (c, cands, _) = Fixture.corpus(works)
    def assess(k1: String, k2: String) = {
      val ids = Set(Fixture.mentionId(k1, 0), Fixture.mentionId(k2, 0))
      val cand =
        cands.find(x => Set(x.left, x.right) == ids).getOrElse(fail(s"no candidate $k1/$k2"))
      DecisionEngine.assess(
        c,
        Policy.erV1,
        cand.features,
        c.mentions.indexOf(cand.left),
        c.mentions.indexOf(cand.right)
      )
    }
    val same = assess("conf/sigir/P1", "conf/sigir/P3")
    assertEquals(
      (same.outcome, same.path, same.rulesFired),
      (MatchOutcome.Accepted, MatchPath.Deterministic, List(Rules.DblpSameName))
    )
    val equiv = assess("conf/sigir/P1", "conf/sigir/P2")
    assertEquals(
      (equiv.outcome, equiv.path, equiv.rulesFired),
      (MatchOutcome.Accepted, MatchPath.Deterministic, List(Rules.CanonicalEquivalentName))
    )
  }

  test(
    "canonically equivalent prior clusters merge only through the accepted decision, recorded as a merge"
  ) {
    val a1 = assignment("conf/sigir/P1", 0)
    val a2 = assignment("conf/sigir/P2", 0)
    assertEquals(a1.right, a2.right)
    List(a1, a2).foreach { a =>
      assertEquals(a.outcome, MatchOutcome.Accepted)
      assertEquals(a.matchPath, MatchPath.Deterministic)
      assert(a.rulesFired.contains(Rules.ClusterMerged), a.rulesFired)
      assertEquals(a.features.find(_.name == "cluster_prior_clusters").map(_.value), Some(2.0))
    }
    val cluster = lines("clusters/clusters.jsonl")
      .map(ujson.read(_))
      .find(_("researcherId").str == a1.right)
      .get
    // the clusters output lists every spelling it holds
    assertEquals(cluster("dblpNames").arr.map(_.str).toSet, Set(Nfc, Nfd))
    assertEquals(cluster("priorClusters").num, 2.0)
    assertEquals(outcome.report("clusters")("mergedBeyondDblpPrior").num, 1.0)
  }

  test(
    "without any decision, canonically equivalent spellings stay in separate dblp-prior clusters"
  ) {
    val noCandidates = Fixture.writeExport(works, maxBlock = 1)
    assertEquals(Fixture.readCandidates(noCandidates), Nil)
    val r = runOn(noCandidates, "er-spelling-b")
    val as = Fixture
      .lines(r.runDir.resolve("decisions/identity_assignment/part-00000.jsonl"))
      .map(l => Contract.decode[MatchDecision](l).toOption.get)
    def rid(key: String) = as.find(_.left == Fixture.mentionId(key, 0)).get
    assertNotEquals(rid("conf/sigir/P1").right, rid("conf/sigir/P2").right)
    assertEquals(rid("conf/sigir/P1").right, rid("conf/sigir/P3").right)
    List("conf/sigir/P1", "conf/sigir/P2").foreach { k =>
      assertEquals(rid(k).matchPath, MatchPath.Deterministic)
      assert(!rid(k).rulesFired.contains(Rules.ClusterMerged))
    }
  }

  test("decision 13: the review queue and the LLM input show each mention's own spelling") {
    val csv = Csv
      .parse(
        new String(
          Files.readAllBytes(outcome.runDir.resolve("review-queue/review-queue.csv")),
          StandardCharsets.UTF_8
        )
      )
      .toOption
      .get
    val col = csv.head.zipWithIndex.toMap
    val rows = csv.tail
    assert(rows.nonEmpty)
    rows.foreach { r =>
      assertEquals(r(col("left_name")), nameOf(r(col("leftMentionId"))))
      assertEquals(r(col("right_name")), nameOf(r(col("rightMentionId"))))
    }
    val ids = Set(Fixture.mentionId("conf/sigir/P1", 0), Fixture.mentionId("conf/sigir/P2", 0))
    val row = rows
      .find(r => Set(r(col("leftMentionId")), r(col("rightMentionId"))) == ids)
      .getOrElse(fail("the canonically equivalent pair is not in the queue"))
    assertEquals(Set(row(col("left_name")), row(col("right_name"))), Set(Nfc, Nfd))
    Fixture
      .lines(outcome.runDir.resolve("review-queue/review-queue.jsonl"))
      .map(ujson.read(_))
      .foreach { j =>
        assertEquals(j("left_name").str, nameOf(j("leftMentionId").str))
        assertEquals(j("right_name").str, nameOf(j("rightMentionId").str))
      }

    val c = Loader.load(input, "dblp-2026-11-01").corpus
    val a = c.mentions.indexOf(Fixture.mentionId("conf/sigir/P1", 0))
    val b = c.mentions.indexOf(Fixture.mentionId("conf/sigir/P2", 0))
    val in = LlmStage.input(c, a, b, Nil, Nil)
    assertEquals(in("left")("name").str, nameOf(c.mentions.id(a)))
    assertEquals(in("right")("name").str, nameOf(c.mentions.id(b)))
    assertEquals(ReviewQueue.side(c, b).head, Nfd)
  }
}
