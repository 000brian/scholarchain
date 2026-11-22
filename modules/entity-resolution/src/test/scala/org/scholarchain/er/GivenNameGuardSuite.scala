package org.scholarchain.er

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import org.scholarchain.core.*
import org.scholarchain.er.Fixture.W
import org.scholarchain.er.cluster.{ClusterResult, Clustering, EdgeCollector}
import org.scholarchain.er.data.Corpus
import org.scholarchain.er.policy.{Policy, Rules}
import org.scholarchain.er.run.RunOutcome

/** Phase 11 review finding A-01: two dblp names with the same folded surname and conflicting given
  * names (given_name_relation 0) never end in one identity cluster through a transitive chain when
  * no decision compared them. The oversize initials-surname fallback re-blocks by one shared
  * coauthor, so "John Smith" (coauthor X) ~ "J. Smith" (X, Y) ~ "Jane Smith" (Y) are two accepted
  * pairs while John / Jane get no candidate, no decision and no cannot-link.
  *
  * The reason code is written as a literal so that this suite compiles, and fails, without the fix.
  */
class GivenNameGuardSuite extends munit.FunSuite {
  private val GivenNameRefusal = "merge-refused:given-name-conflict"

  private def name(c: Corpus, n: String): Int = c.names.spelling.indexOf(n)
  private def cluster(c: Corpus, edges: (String, String, Double)*): ClusterResult = {
    val e = new EdgeCollector
    edges.foreach { case (a, b, conf) =>
      e.add(name(c, a), name(c, b), conf, MatchPath.Probabilistic)
    }
    Clustering.run(c, e)
  }
  private def same(c: Corpus, r: ClusterResult, a: String, b: String) =
    r.rootOf(name(c, a)) == r.rootOf(name(c, b))

  // The reviewer's chain: three different works, John and Jane share no coauthor.
  private val chain = List(
    W("conf/sigir/GN1", List("John Smith", "Xavier Coa")),
    W("conf/sigir/GN2", List("J. Smith", "Xavier Coa", "Yvonne Cob")),
    W("conf/sigir/GN3", List("Jane Smith", "Yvonne Cob"))
  )
  private val john = Fixture.mentionId("conf/sigir/GN1", 0)
  private val jdot = Fixture.mentionId("conf/sigir/GN2", 0)
  private val jane = Fixture.mentionId("conf/sigir/GN3", 0)

  test("A-01: a chain John ~ J. ~ Jane with no John / Jane decision never forms one cluster") {
    val c = Fixture.corpus(chain)._1
    val edges = List(("John Smith", "J. Smith", 0.97), ("J. Smith", "Jane Smith", 0.96))
    val r = cluster(c, edges*)
    assert(same(c, r, "John Smith", "J. Smith"))
    assert(!same(c, r, "John Smith", "Jane Smith"))
    assertEquals(r.mergesApplied, 1)
    assertEquals(
      r.refused,
      Map(EdgeCollector.key(name(c, "J. Smith"), name(c, "Jane Smith")) -> GivenNameRefusal)
    )
    // merge order decides which link survives; either way John and Jane stay apart
    val flipped = cluster(c, ("John Smith", "J. Smith", 0.95), ("J. Smith", "Jane Smith", 0.96))
    assert(same(c, flipped, "J. Smith", "Jane Smith"))
    assert(!same(c, flipped, "John Smith", "Jane Smith"))
    assertEquals(flipped.refused.values.toList, List(GivenNameRefusal))
  }

  test("A-01: compatible given names and a direct accepted decision still merge") {
    // initials and a missing middle name are compatible (relation 1): the chain is applied
    val c = Fixture
      .corpus(
        List(
          W("conf/sigir/GC1", List("Ann Bell", "Coa Ga")),
          W("conf/sigir/GC2", List("A. Bell", "Coa Ga", "Coa Gb")),
          W("conf/sigir/GC3", List("Ann M. Bell", "Coa Gb"))
        )
      )
      ._1
    val ok = cluster(c, ("Ann Bell", "A. Bell", 0.97), ("A. Bell", "Ann M. Bell", 0.96))
    assert(same(c, ok, "Ann Bell", "Ann M. Bell"))
    assertEquals(ok.refused, Map.empty[Long, String])
    // a direct accepted decision between the two conflicting names is the policy's own verdict on
    // that pair and is not overridden; the chain through it then merges as well
    val sc = Fixture.corpus(chain)._1
    val direct = cluster(
      sc,
      ("John Smith", "Jane Smith", 0.98),
      ("John Smith", "J. Smith", 0.97),
      ("J. Smith", "Jane Smith", 0.96)
    )
    assert(same(sc, direct, "John Smith", "Jane Smith"))
    assert(same(sc, direct, "J. Smith", "Jane Smith"))
    assertEquals(direct.refused, Map.empty[Long, String])
    // different folded surnames are not compared by this guard (chain without a direct edge)
    val other = Fixture
      .corpus(
        List(
          W("conf/sigir/GS1", List("John Smyth")),
          W("conf/sigir/GS2", List("J. Smith")),
          W("conf/sigir/GS3", List("Jane Smith"))
        )
      )
      ._1
    val o = cluster(other, ("John Smyth", "J. Smith", 0.97), ("J. Smith", "Jane Smith", 0.96))
    assert(same(other, o, "John Smyth", "Jane Smith"))
    assertEquals(o.refused, Map.empty[Long, String])
  }

  private def decisions(out: RunOutcome, dir: String): List[MatchDecision] =
    Fixture
      .tree(out.runDir)
      .toList
      .filter(_._1.startsWith(dir))
      .sortBy(_._1)
      .flatMap { case (rel, _) => Fixture.lines(out.runDir.resolve(rel)) }
      .map(l => Contract.decode[MatchDecision](l).fold(e => fail(e.message), identity))

  /** The chain export with every John / Jane candidate removed, as the coauthor-keyed fallback
    * blocks `["j smith","X"]` = {John, J.} and `["j smith","Y"]` = {J., Jane} emit it.
    */
  private def fallbackExport(
      works: List[W] = chain,
      x: String = john,
      mid: String = jdot,
      y: String = jane
  ): Path = {
    val dir = Fixture.writeExport(works)
    val kept = Fixture
      .readCandidates(dir)
      .filterNot(m => Set(m.left, m.right) == Set(x, y))
    assert(kept.exists(m => Set(m.left, m.right) == Set(x, mid)))
    assert(kept.exists(m => Set(m.left, m.right) == Set(mid, y)))
    val (c1, c2) = kept.splitAt(kept.size / 2)
    def write(part: String, cs: List[MatchCandidate]): Unit =
      Files.write(
        dir.resolve("match_candidate").resolve(part),
        cs.map(m => Contract.encode(m).fold(e => fail(e.message), identity) + "\n")
          .mkString
          .getBytes(StandardCharsets.UTF_8)
      )
    write("part-r-00000", c1)
    write("part-r-00001", c2)
    dir
  }

  test("A-01: through the run, the refused link is unresolved, recorded and counted") {
    val input = fallbackExport()
    val out = RunFixture.run(input, Files.createTempDirectory("er-given-name"))
    val ds = decisions(out, "decisions/mention_pair/")
    def between(a: String, b: String) = ds.filter(d => Set(d.left, d.right) == Set(a, b))
    // preconditions: the reviewer's evidence, no John / Jane decision at all
    assertEquals(between(john, jane), Nil)
    val links = between(john, jdot) ++ between(jdot, jane)
    assertEquals(links.size, 2, links.toString)
    links.foreach(d => assert(d.rulesFired.contains(Rules.ScoreAccept), d.toString))

    // John and Jane receive different ResearcherIds
    val as = decisions(out, "decisions/identity_assignment/").map(d => d.left -> d).toMap
    assertNotEquals(as(john).right, as(jane).right)

    // one link applied, the other refused: unresolved, inherited probabilistic path, reason code
    val refused = links.filter(_.reasonCodes.contains(GivenNameRefusal))
    assertEquals(refused.size, 1, links.toString)
    assertEquals(links.count(_.outcome == MatchOutcome.Accepted), 1)
    refused.foreach { d =>
      assertEquals(d.outcome, MatchOutcome.Unresolved)
      assertEquals(d.matchPath, MatchPath.Probabilistic)
      assertEquals(d.rulesFired, List(Rules.GuardRejectedPair, Rules.ScoreAccept))
      assertEquals(d.reasonCodes, List(GivenNameRefusal))
      assert(d.confidence > 0.9, d.toString)
      // the rule it names is declared by the policy (cluster guard, inherited path)
      assertEquals(Policy.current.rule(d.rulesFired.head).kind, "cluster-guard")
    }
    val rep = out.report
    assertEquals(rep("pairDecisions")("mergeRefusalsByReason")(GivenNameRefusal).num, 1.0)
    assertEquals(rep("clusters")("mergesRefusedByReason")(GivenNameRefusal).num, 1.0)
    // no cluster is flagged: the refusal kept the conflict out
    assert(as.values.forall(!_.reasonCodes.contains(GivenNameFlag)), as.toString)
  }

  private val GivenNameFlag = "cluster-given-name-conflict"

  test("A-01: a chain of direct name-conflict acceptances merges and is flagged conflict") {
    // unit level: every edge is itself a given-name conflict accepted on direct evidence
    val c = Fixture
      .corpus(
        List(
          W("conf/sigir/GF1", List("Pa Synthflag")),
          W("conf/sigir/GF2", List("Pb Synthflag")),
          W("conf/sigir/GF3", List("Pc Synthflag")),
          W("conf/sigir/GF4", List("Qa Synthother")),
          W("conf/sigir/GF5", List("Q. Synthother"))
        )
      )
      ._1
    val r = cluster(
      c,
      ("Pa Synthflag", "Pb Synthflag", 0.97),
      ("Pb Synthflag", "Pc Synthflag", 0.96),
      ("Qa Synthother", "Q. Synthother", 0.95)
    )
    assert(same(c, r, "Pa Synthflag", "Pc Synthflag"))
    assertEquals(r.refused, Map.empty[Long, String])
    assertEquals(r.clusters(r.rootOf(name(c, "Pa Synthflag"))).flags, List(GivenNameFlag))
    // a compatible merge is not flagged
    assertEquals(r.clusters(r.rootOf(name(c, "Qa Synthother"))).flags, Nil)

    // through the run: one shared ORCID iD accepts Pat ~ Pete and Pete ~ Paul (orcid.exact, given
    // names in conflict); Pat / Paul get no candidate. The cluster is kept and every assignment
    // is conflict with the flag rule.
    val o = Fixture.orcid("000000021825009")
    val works = List(
      W("conf/sigir/GR1", List("Pat Synthorc", "Coa Ra"), orcids = Map(0 -> o), year = 2018),
      W("conf/sigir/GR2", List("Pete Synthorc", "Coa Rb"), orcids = Map(0 -> o), year = 2019),
      W("conf/sigir/GR3", List("Paul Synthorc", "Coa Rc"), orcids = Map(0 -> o), year = 2020)
    )
    val pat = Fixture.mentionId("conf/sigir/GR1", 0)
    val pete = Fixture.mentionId("conf/sigir/GR2", 0)
    val paul = Fixture.mentionId("conf/sigir/GR3", 0)
    val out = RunFixture.run(
      fallbackExport(works, pat, pete, paul),
      Files.createTempDirectory("er-given-name-flag")
    )
    val as = decisions(out, "decisions/identity_assignment/").map(d => d.left -> d).toMap
    assertEquals(Set(as(pat).right, as(pete).right, as(paul).right).size, 1)
    List(pat, pete, paul).foreach { m =>
      assertEquals(as(m).outcome, MatchOutcome.Conflict, as(m).toString)
      assert(as(m).rulesFired.contains(Rules.FlagGivenNameConflict), as(m).toString)
      assert(!as(m).rulesFired.contains(Rules.FlagSameWork), as(m).toString)
      assertEquals(as(m).reasonCodes, List(GivenNameFlag))
    }
    assertEquals(out.report("clusters")("conflictFlagged").num, 1.0)
  }
}
