package org.scholarchain.er

import java.nio.charset.StandardCharsets
import java.nio.file.Files

import org.scholarchain.core.*
import org.scholarchain.er.Fixture.W
import org.scholarchain.er.io.DataFiles
import org.scholarchain.er.policy.{Policy, Reasons, Rules}

/** Repair finding F4: a decision refused by a cluster merge guard keeps the match path (and
  * confidence) of the pair decision whose accepted evidence was refused, and er-v1 declares exactly
  * that (`path: inherited`, `confidence: null`) for every cluster-guard rule.
  */
class GuardPathSuite extends munit.FunSuite {
  private val oY = Fixture.orcid("000000034567123")

  test("er-v1 declares path 'inherited' and no fixed confidence for every cluster-guard rule") {
    val guards = Policy.current.rules.filter(_.kind == "cluster-guard")
    assertEquals(
      guards.map(_.id).toSet,
      Set(
        Rules.GuardOrcid,
        Rules.GuardSameWork,
        Rules.GuardDistinctHomonym,
        Rules.GuardDisambiguation,
        Rules.GuardConflictPair,
        Rules.GuardRejectedPair
      )
    )
    assertEquals(Policy.current.rule(Rules.GuardRejectedPair).outcome, "unresolved")
    assertEquals(Policy.current.rule(Rules.GuardConflictPair).outcome, "conflict")
    guards.foreach { g =>
      assertEquals(g.path, Policy.InheritedPath, g.id)
      assertEquals(g.confidence, None, g.id)
    }
    assert(
      Policy.current.rules
        .filterNot(_.kind == "cluster-guard")
        .forall(_.path != Policy.InheritedPath)
    )
    assert(Policy.current.rules.forall(r => Policy.RulePaths(r.path)))
  }

  test("a policy file that gives a cluster guard a fixed path or confidence is rejected") {
    val text = new String(DataFiles.readResource(Policy.DefaultResource), StandardCharsets.UTF_8)
    val guard =
      "\"id\": \"cluster.guard.dblp-disambiguation-profile\", \"kind\": \"cluster-guard\", "
    assert(text.contains(guard + "\"path\": \"inherited\""))
    val fixedPath =
      text.replace(guard + "\"path\": \"inherited\"", guard + "\"path\": \"deterministic\"")
    assert(Policy.parse(fixedPath.getBytes(StandardCharsets.UTF_8)).isLeft)
    val orcidGuard =
      "\"id\": \"cluster.guard.orcid\", \"kind\": \"cluster-guard\", \"path\": \"inherited\", \"outcome\": \"conflict\", "
    assert(text.contains(orcidGuard + "\"confidence\": null"))
    val fixedConf =
      text.replace(orcidGuard + "\"confidence\": null", orcidGuard + "\"confidence\": 1.0")
    assert(Policy.parse(fixedConf.getBytes(StandardCharsets.UTF_8)).isLeft)
    val badPath = text.replace("\"path\": \"exact_id\"", "\"path\": \"exact-id\"")
    assert(Policy.parse(badPath.getBytes(StandardCharsets.UTF_8)).isLeft)
  }

  test("a refused exact-ID acceptance stays on the exact_id path with its own confidence") {
    val works = List(
      W("conf/sigir/G1", List("Wei Synthdis", "Coa Ga"), orcids = Map(0 -> oY), title = "Topic A"),
      W(
        "conf/sigir/G2",
        List("Wei Synthdis 0001", "Coa Gb"),
        orcids = Map(0 -> oY),
        title = "Topic B"
      ),
      W("conf/sigir/G3", List("Wei Synthdis 0002", "Coa Gc"), title = "Topic C")
    )
    val input = Fixture.writeExport(works)
    val out = RunFixture.run(input, Files.createTempDirectory("er-guard-path"))
    val ds = Fixture
      .tree(out.runDir)
      .toList
      .filter(_._1.startsWith("decisions/mention_pair/"))
      .flatMap { case (_, b) =>
        new String(b, StandardCharsets.UTF_8).split('\n').filter(_.nonEmpty).toList
      }
      .map(l => Contract.decode[MatchDecision](l).fold(e => fail(e.message), identity))
    val ids = Set(Fixture.mentionId("conf/sigir/G1", 0), Fixture.mentionId("conf/sigir/G2", 0))
    val refused = ds.filter(d => Set(d.left, d.right) == ids)
    assert(refused.nonEmpty)
    refused.foreach { d =>
      assertEquals(d.outcome, MatchOutcome.Unresolved)
      assertEquals(d.matchPath, MatchPath.ExactId)
      assertEquals(d.rulesFired, List(Rules.GuardDisambiguation, Rules.OrcidExact))
      assertEquals(d.reasonCodes, List(Reasons.MergeDisambiguation))
      assertEquals(d.confidence, Policy.current.ruleConfidence(Rules.OrcidExact))
    }
    // every guarded decision's path is the path of the rule that produced its evidence
    val declared = Policy.current.rules.map(r => r.id -> r.path).toMap
    ds.filter(_.rulesFired.exists(_.startsWith("cluster.guard."))).foreach { d =>
      val evidence = d.rulesFired.filterNot(_.startsWith("cluster.guard."))
      assert(evidence.nonEmpty, d.toString)
      assert(evidence.exists(r => declared(r) == d.matchPath.wire), d.toString)
    }
  }
}
