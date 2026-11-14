package org.scholarchain.er

import org.scholarchain.core.{ExternalIdType, MatchOutcome, MatchPath}
import org.scholarchain.er.Fixture.W
import org.scholarchain.er.features.PairFeatures
import org.scholarchain.er.policy.{Assessment, DecisionEngine, Policy, Reasons, Rules}

class DecisionSuite extends munit.FunSuite {
  private val policy = Policy.erV1
  private val oX = Fixture.orcid("000000021825009")
  private val oY = Fixture.orcid("000000034567123")

  private val works = List(
    W("conf/sigir/T1", List("John Smith", "Alice Coauthor"), title = "Neural Ranking Models"),
    W("conf/sigir/T2", List("J. Smith", "Alice Coauthor"), title = "Neural Ranking Models"),
    W(
      "conf/sigir/T3",
      List("Jane Smith", "Bob Other"),
      year = 2015,
      venue = "conf/cvpr",
      title = "Image Segmentation"
    ),
    W("conf/sigir/T4", List("J. Smith", "Carol Third"), title = "Query Logs"),
    W(
      "conf/sigir/T5",
      List("J. Smith", "Dan Fourth"),
      venue = "conf/sigmod",
      title = "Index Tuning"
    ),
    W("conf/sigir/T6", List("Bob Jones", "Eve Fifth"), title = "Topic A"),
    W("conf/sigir/T7", List("Bob Jones", "Fay Sixth"), title = "Topic B"),
    W("conf/sigir/T8", List("Wei Wang 0001", "Gus Seventh"), title = "Topic C"),
    W("conf/sigir/T9", List("Wei Wang 0002", "Hal Eighth"), title = "Topic D"),
    W("conf/sigir/T10", List("Kim Lee", "Kim Lee"), title = "Topic E"),
    W("conf/sigir/T11", List("Ann Bell"), orcids = Map(0 -> oX), title = "Topic F"),
    W("conf/sigir/T12", List("Ann Bell"), orcids = Map(0 -> oY), title = "Topic G"),
    W("conf/sigir/T13", List("A. Bell"), orcids = Map(0 -> oX), title = "Topic H"),
    W("conf/sigir/T14", List("Tom Gray 0001"), orcids = Map(0 -> oY), title = "Topic I"),
    W("conf/sigir/T15", List("Tom Gray 0002"), orcids = Map(0 -> oY), title = "Topic J"),
    W("conf/sigir/T16", List("Ann Bell"), title = "Topic K")
  )
  private lazy val fx = Fixture.corpus(works)
  private def corpus = fx._1
  private def cands = fx._2

  private def assess(k1: String, p1: Int, k2: String, p2: Int): Assessment = {
    val a = corpus.mentions.indexOf(Fixture.mentionId(k1, p1))
    val b = corpus.mentions.indexOf(Fixture.mentionId(k2, p2))
    val (l, r) = if (a < b) (a, b) else (b, a)
    val c = cands
      .find(c => c.left == corpus.mentions.id(l) && c.right == corpus.mentions.id(r))
      .getOrElse(fail(s"no candidate $k1/$k2"))
    DecisionEngine.assess(corpus, policy, c.features, l, r)
  }

  test("compatible initials plus a shared coauthor are accepted on the probabilistic path") {
    val as = assess("conf/sigir/T1", 0, "conf/sigir/T2", 0)
    assertEquals((as.outcome, as.path), (MatchOutcome.Accepted, MatchPath.Probabilistic))
    assertEquals(as.rulesFired, List(Rules.ScoreAccept))
    assert(as.matchProbability.exists(_ >= 0.9))
  }

  test("conflicting given names are rejected") {
    val as = assess("conf/sigir/T1", 0, "conf/sigir/T3", 0)
    assertEquals((as.outcome, as.path), (MatchOutcome.Rejected, MatchPath.Probabilistic))
    assertEquals(as.confidence, 1.0 - as.matchProbability.get)
  }

  test(
    "initials only, same venue: unresolved in the LLM band; other area: unresolved below the band"
  ) {
    val band = assess("conf/sigir/T1", 0, "conf/sigir/T4", 0)
    assertEquals(band.outcome, MatchOutcome.Unresolved)
    assert(band.inLlmBand)
    assertEquals(band.rulesFired, List(Rules.ScoreLlmBand))
    assertEquals(band.reasonCodes, List(Reasons.ScoreUnresolved))
    val low = assess("conf/sigir/T1", 0, "conf/sigir/T5", 0)
    assertEquals(low.outcome, MatchOutcome.Unresolved)
    assert(!low.inLlmBand)
    assertEquals(low.rulesFired, List(Rules.ScoreUnresolved))
  }

  test("identical dblp names are accepted by the dblp-prior rule") {
    val as = assess("conf/sigir/T6", 0, "conf/sigir/T7", 0)
    assertEquals(
      (as.outcome, as.path, as.rulesFired),
      (MatchOutcome.Accepted, MatchPath.Deterministic, List(Rules.DblpSameName))
    )
  }

  test("different homonym numbers of one base name are rejected") {
    val as = assess("conf/sigir/T8", 0, "conf/sigir/T9", 0)
    assertEquals(
      (as.outcome, as.rulesFired),
      (MatchOutcome.Rejected, List(Rules.DblpDistinctHomonym))
    )
  }

  test("two mentions on the same work are a conflict, never accepted") {
    val as = assess("conf/sigir/T10", 0, "conf/sigir/T10", 1)
    assertEquals(
      (as.outcome, as.rulesFired, as.reasonCodes),
      (MatchOutcome.Conflict, List(Rules.SameWork), List(Reasons.SameWork))
    )
  }

  test("different ORCID iDs are a conflict even for identical dblp names") {
    val as = assess("conf/sigir/T11", 0, "conf/sigir/T12", 0)
    assertEquals(
      (as.outcome, as.path, as.rulesFired),
      (MatchOutcome.Conflict, MatchPath.ExactId, List(Rules.OrcidDisagree))
    )
    assertEquals(as.reasonCodes, List(Reasons.OrcidDisagree))
  }

  test(
    "one shared ORCID iD is accepted by the exact rule; with different homonym numbers it is a conflict"
  ) {
    val ok = assess("conf/sigir/T11", 0, "conf/sigir/T13", 0)
    assertEquals(
      (ok.outcome, ok.path, ok.rulesFired),
      (MatchOutcome.Accepted, MatchPath.ExactId, List(Rules.OrcidExact))
    )
    val c = assess("conf/sigir/T14", 0, "conf/sigir/T15", 0)
    assertEquals(
      (c.outcome, c.rulesFired),
      (MatchOutcome.Conflict, List(Rules.OrcidHomonymConflict))
    )
  }

  test(
    "missing features stay missing: no ORCID feature when one side has none, no ratio without a count"
  ) {
    val as = assess("conf/sigir/T11", 0, "conf/sigir/T16", 0)
    val names = as.features.map(_.name)
    assert(!names.contains("orcid_match"))
    assert(!names.contains("orcid_exact_match"))
    assert(!names.contains("affiliation_overlap"))
    assert(!names.contains("openalex_author_match"))
    val a = corpus.mentions.indexOf(Fixture.mentionId("conf/sigir/T1", 0))
    val b = corpus.mentions.indexOf(Fixture.mentionId("conf/sigir/T2", 0))
    val noJ06 =
      PairFeatures.compute(corpus, policy, Nil, math.min(a, b), math.max(a, b)).features.map(_.name)
    assert(!noJ06.contains("coauthor_shared_count"))
    assert(!noJ06.contains("coauthor_overlap_ratio"))
    assertEquals(noJ06, noJ06.sorted)
    // Single-author works: no author_role_match.
    assert(!names.contains("author_role_match"))
  }

  test(
    "OpenAlex author links: equal IDs accepted by the exact rule, affiliation feature from links only"
  ) {
    val links = List(
      Fixture.mentionLink("conf/sigir/T4", 0, ExternalIdType.OpenAlexAuthor, "A123"),
      Fixture.mentionLink("conf/sigir/T5", 0, ExternalIdType.OpenAlexAuthor, "A123"),
      Fixture.mentionLink("conf/sigir/T4", 0, ExternalIdType.OpenAlexInstitution, "I9"),
      Fixture.mentionLink("conf/sigir/T5", 0, ExternalIdType.OpenAlexInstitution, "I9")
    )
    val (c2, cs2, _) = Fixture.corpus(works, Some(links))
    val a = c2.mentions.indexOf(Fixture.mentionId("conf/sigir/T4", 0))
    val b = c2.mentions.indexOf(Fixture.mentionId("conf/sigir/T5", 0))
    val (l, r) = (math.min(a, b), math.max(a, b))
    val cand = cs2.find(c => c.left == c2.mentions.id(l) && c.right == c2.mentions.id(r)).get
    val as = DecisionEngine.assess(c2, policy, cand.features, l, r)
    assertEquals((as.outcome, as.rulesFired), (MatchOutcome.Accepted, List(Rules.OpenAlexExact)))
    assert(as.features.exists(f => f.name == "affiliation_overlap" && f.value == 1.0))
    assert(as.features.exists(f => f.name == "source_reliability" && f.value == 0.85))
  }
}
