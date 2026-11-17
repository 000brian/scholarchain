package org.scholarchain.er

import java.nio.charset.StandardCharsets

import org.scholarchain.core.{AuthorNameKey, MatchFeature}
import org.scholarchain.er.io.DataFiles
import org.scholarchain.er.policy.*
import org.scholarchain.er.run.ErMain
import org.scholarchain.er.work.WorkResolution

class PolicySuite extends munit.FunSuite {
  private val p = Policy.current

  test("er-v2 is final, versioned and names the shared name-key rule and J06 feature set") {
    assertEquals(p.policyVersion, "er-v2")
    assertEquals(p.status, "final")
    assertEquals(p.nameKeyVersion, "author-name-key/1")
    assertEquals(p.candidateFeatureSet, "candidate-features/1-provisional")
    assertEquals(p.thresholds, Thresholds(0.9, 0.3, 0.6, 0.8, 0.5))
    assertEquals(p.priorLogOdds, -1.0)
  }

  test("policy rules are exactly the implemented rules, including work rules") {
    assertEquals(p.rules.map(_.id).toSet, Rules.All)
    assert(
      Set(
        WorkResolution.DoiLink,
        WorkResolution.OpenAlexLink,
        WorkResolution.J04Conflict,
        WorkResolution.SharedDoi,
        WorkResolution.SharedArxiv,
        WorkResolution.SharedUrl
      ).subsetOf(Rules.WorkRules)
    )
  }

  test("score is the prior plus bin weights; reliability scales identifier evidence") {
    val fs = List(
      MatchFeature("coauthor_shared_count", 1),
      MatchFeature("given_name_relation", 1),
      MatchFeature("same_venue", 1),
      MatchFeature("venue_area_match", 1),
      MatchFeature("year_abs_diff", 2)
    )
    // -1.0 + 2.0 + 1.0 + 0.4 + 0.3 + 0.2
    assertEqualsDouble(p.logOdds(fs, 1.0), 2.9, 1e-9)
    val aff = List(MatchFeature("affiliation_overlap", 1))
    assertEqualsDouble(p.logOdds(aff, 0.85), -1.0 + 1.5 * 0.85, 1e-9)
    assertEqualsDouble(p.logOdds(Nil, 1.0), -1.0, 1e-9)
  }

  test("threshold overrides need a new policy version") {
    val t = p.thresholds.copy(accept = 0.95)
    assert(p.withThresholds("", t).isLeft)
    assert(p.withThresholds("er-v2", t).isLeft)
    val o = p.withThresholds("er-v2-acc95", t)
    assertEquals(o.map(_.policyVersion), Right("er-v2-acc95"))
    assertEquals(o.map(_.overriddenFrom), Right(Some("er-v2")))
    assert(p.withThresholds("er-v2b", t.copy(reject = 0.97)).isLeft)
  }

  test("the command line refuses threshold overrides without a policy version change") {
    val base = Map(
      "input" -> "x",
      "output-root" -> "y",
      "decision-time" -> "2026-11-14T00:00:00Z",
      "seed" -> "1",
      "code-version" -> "t",
      "llm-model-id" -> "m",
      "llm-max-per-run" -> "0",
      "adjudicator" -> "always-abstain"
    )
    intercept[org.scholarchain.er.io.ErError](ErMain.config(base + ("accept-threshold" -> "0.95")))
    intercept[org.scholarchain.er.io.ErError](ErMain.config(base + ("policy-version" -> "er-v3")))
    val (plain, _) = ErMain.config(base)
    assertEquals(plain.policy.policyVersion, "er-v2")
    val (cfg, _) =
      ErMain.config(base ++ Map("accept-threshold" -> "0.95", "policy-version" -> "er-v2-acc95"))
    assertEquals(cfg.policy.policyVersion, "er-v2-acc95")
    assertEquals(cfg.policy.overriddenFrom, Some("er-v2"))
    intercept[org.scholarchain.er.io.ErError](ErMain.config(base + ("adjudicator" -> "live")))
    intercept[org.scholarchain.er.io.ErError](ErMain.parseArgs(Seq("--bogus", "1")))
  }

  test("a policy file with an unknown key or unknown weighted feature is rejected") {
    val text = new String(DataFiles.readResource(Policy.DefaultResource), StandardCharsets.UTF_8)
    val extra =
      text.replaceFirst("\"status\": \"final\",", "\"status\": \"final\", \"labels\": \"x\",")
    assert(Policy.parse(extra.getBytes(StandardCharsets.UTF_8)).isLeft)
    val unknownFeature =
      text.replaceFirst("\"feature\": \"same_venue\"", "\"feature\": \"gold_label\"")
    assert(Policy.parse(unknownFeature.getBytes(StandardCharsets.UTF_8)).isLeft)
    assert(Policy.parse(text.getBytes(StandardCharsets.UTF_8)).isRight)
  }

  test("m7: the thresholds rationale states what the policy weights actually do") {
    val p0 = Policy.current
    def p(fs: (String, Double)*): Double =
      Policy.logistic(p0.logOdds(fs.toList.map { case (n, v) => MatchFeature(n, v) }, 1.0))
    val accept = p0.thresholds.accept
    val equalNames =
      List("given_name_relation" -> 2.0, "surname_equal" -> 1.0, "name_similarity" -> 1.0)
    // (1) equal folded names alone (name_similarity co-fires) are accepted without other evidence
    assertEqualsDouble(p(equalNames*), Policy.logistic(2.3), 1e-12)
    assert(p(equalNames*) >= accept)
    // (2) zero shared coauthors puts them in the LLM band; the same venue alone lifts them over
    val noCoauthors = equalNames :+ ("coauthor_shared_count" -> 0.0)
    assert(p(noCoauthors*) < accept && p(noCoauthors*) >= p0.thresholds.llmBandLower)
    assert(p((noCoauthors :+ ("same_venue" -> 1.0))*) >= accept)
    // (3) compatible names with one shared coauthor stay unresolved; two are accepted
    val compatible = List("given_name_relation" -> 1.0, "surname_equal" -> 1.0)
    assertEqualsDouble(
      p((compatible :+ ("coauthor_shared_count" -> 1.0))*),
      Policy.logistic(2.0),
      1e-12
    )
    assert(p((compatible :+ ("coauthor_shared_count" -> 1.0))*) < accept)
    assert(p((compatible :+ ("coauthor_shared_count" -> 2.0))*) >= accept)
    // a given-name conflict without shared coauthors is rejected even if everything else agrees
    val best = List(
      "given_name_relation" -> 0.0,
      "surname_equal" -> 1.0,
      "name_similarity" -> 1.0,
      "coauthor_shared_count" -> 0.0,
      "same_venue" -> 1.0,
      "venue_area_match" -> 1.0,
      "year_abs_diff" -> 0.0,
      "title_similarity" -> 1.0,
      "author_role_match" -> 1.0,
      "affiliation_overlap" -> 1.0
    )
    assert(p(best*) < p0.thresholds.reject)
    // ... and the file says so, instead of claiming accept needs one more agreeing signal
    val text = new String(DataFiles.readResource(Policy.DefaultResource), StandardCharsets.UTF_8)
    List(
      "accepted on the name alone, without any independent evidence",
      "the same venue outweighs the absence of shared coauthors",
      "reach 2.0 (p 0.881) and stay unresolved"
    ).foreach(s => assert(text.contains(s), s))
    assert(!text.contains("equal given names plus one more agreeing signal"))
  }

  test("m-new-3: the thresholds rationale states the middle-name blocking recall gap") {
    val text = new String(DataFiles.readResource(Policy.DefaultResource), StandardCharsets.UTF_8)
    // the blocking cannot produce a pair that differs only by a missing middle initial ...
    assertNotEquals(
      AuthorNameKey.initialsSurnameKey("John A. Smith"),
      AuthorNameKey.initialsSurnameKey("John Smith")
    )
    assertNotEquals(
      AuthorNameKey.baseNameKey("John A. Smith"),
      AuthorNameKey.baseNameKey("John Smith")
    )
    // ... so the rationale must not cite it as an example, and states the gap instead
    assert(!text.contains("only a middle initial missing"))
    List(
      "a missing middle name or middle initial is not such a case, because blocking never pairs it",
      "(j a smith, j smith)",
      "a known blind spot of the blocking (pairs it can never offer), not of these weights"
    ).foreach(s => assert(text.contains(s), s))
    // the text change leaves every weight, rule and threshold as it was
    assertEquals(p.policyVersion, "er-v2")
    assertEquals(p.thresholds, Thresholds(0.9, 0.3, 0.6, 0.8, 0.5))
  }

  test("R2-03: the A-01 merge behaviour runs under er-v2; er-v1 stays the frozen Phase 5 file") {
    // the current policy is er-v2 and declares the A-01 rules (implied cannot-link, given-name flag)
    assertEquals(Policy.DefaultResource, "er/policy/er-v2.json")
    assertEquals(Policy.current.policyVersion, "er-v2")
    assert(Rules.All.contains(Rules.FlagGivenNameConflict))
    assertEquals(Policy.current.rule(Rules.FlagGivenNameConflict).outcome, "conflict")
    val v2 = new String(DataFiles.readResource(Policy.DefaultResource), StandardCharsets.UTF_8)
    assert(v2.contains(Reasons.MergeGivenNameConflict), "er-v2 names the implied cannot-link")
    // the legacy accessor is the current policy, never a second er-v1 policy
    assert(Policy.erV1 eq Policy.current)
    // er-v1.json is byte-identical to the Phase 5 file whose sha256 every er-v1 manifest records
    val v1 = DataFiles.readResource(Policy.ErV1Resource)
    assertEquals(DataFiles.sha256OfBytes(v1), Policy.ErV1Sha256)
    val v1Text = new String(v1, StandardCharsets.UTF_8)
    assert(v1Text.contains("\"policyVersion\": \"er-v1\""))
    assert(!v1Text.contains(Rules.FlagGivenNameConflict))
    assert(!v1Text.contains(Reasons.MergeGivenNameConflict))
    // ... and the code, which implements the A-01 rules, refuses to run it under the label er-v1
    val refused = Policy.parse(v1)
    assert(refused.isLeft, refused)
    assert(refused.left.exists(_.contains("do not match the implemented rules")), refused)
  }
}
