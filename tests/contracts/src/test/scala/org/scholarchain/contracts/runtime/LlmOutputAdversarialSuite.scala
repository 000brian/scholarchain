package org.scholarchain.contracts.runtime

import org.scholarchain.contracts.runtime.RuntimeAdversarialSupport.*
import org.scholarchain.core.*
import org.scholarchain.rag.runtime.*
import org.scholarchain.rag.runtime.RuntimeServiceFixtures.{answeredRequest, fixture}

/** Category 13 (LLM malformed JSON and fabricated identifiers), cross-module: rag's workflow over
  * storage's local runtime stores with a scripted model. Checks the fallback, the attempt records
  * (hash and length only, never the text), the counters, and that no model text or fabricated
  * identifier reaches the response or the stored audit.
  */
class LlmOutputAdversarialSuite extends munit.FunSuite {

  override val munitTimeout = scala.concurrent.duration.Duration(300, "s")

  private val Marker = "PHASE11-MODEL-TEXT-MARKER-7f3a"

  private def run(
      model: RulePlanner => LanguageModel
  ): (RuntimeServiceFixtures.Fixture, WorkflowResult) = {
    val fx = fixture(model)
    val r = fx.service.workflow
      .run(answeredRequest)
      .fold(e => fail(s"workflow returned Left: $e"), identity)
    (fx, r)
  }

  test("planner prose (not JSON) falls back to the rule plan; text kept only as hash and length") {
    var m: ScriptModel = null
    val reply = s"Sure! Here is the plan you asked for: $Marker"
    val (fx, r) = run { rule =>
      m = new ScriptModel(rule, planner = Some(_ => Scripted(reply)))
      m
    }
    try {
      val rule = new RulePlanner(fx.service.context.windows, fx.service.context.bounds)
      assertEquals(r.planSource, PlanSource.Rule)
      assertEquals(r.draft, rule.draft(r.request.question))
      val plannerAttempts = r.planner.attempts
      assertEquals(plannerAttempts.size, fx.service.context.maxStructuredRetries + 1)
      plannerAttempts.foreach { a =>
        assertEquals(a.outcome, ModelAttempt.Invalid)
        assertEquals(a.outputSha256, Some(ContentHash.of(reply)))
        assertEquals(a.outputLength, Some(reply.length))
        assert(!a.error.exists(_.contains(Marker)), s"attempt error holds model text: ${a.error}")
      }
      assertEquals(m.usage.planner.invalidOutputs.get, plannerAttempts.size.toLong)
      assertEquals(
        m.usage.planner.fallbacks.snapshot.get(TaskCallCounters.OutputInvalid),
        Some(1L)
      )
      assert(!responseText(r).contains(Marker))
      assert(!auditText(r).contains(Marker))
      // the retry prompt never echoes the rejected text back
      assert(m.requests.forall(!_.user.contains(Marker)))
      assert(!r.response.abstained, "a planner fallback must still answer the fixture question")
    } finally fx.service.close()
  }

  test("synthesis JSON truncated at max_tokens: truncation recorded, template fallback verified") {
    var m: ScriptModel = null
    val (fx, r) = run { rule =>
      m = new ScriptModel(
        rule,
        synthesis = Some((_, i) =>
          Scripted(FakeSynthesis.render(i).toJson.render().take(57), Some(StopReasons.MaxTokens))
        )
      )
      m
    }
    try {
      val synth = r.synthesis.attempts
      assertEquals(synth.size, fx.service.context.maxStructuredRetries + 1)
      synth.foreach { a =>
        assertEquals(a.outcome, ModelAttempt.Invalid)
        assertEquals(a.error, Some(StopReasons.TruncatedError))
        assertEquals(a.stopReason, StopReasons.MaxTokens)
      }
      assertEquals(m.usage.synthesis.truncatedOutputs.get, synth.size.toLong)
      assertEquals(r.synthesis.usedModel, false)
      assert(r.synthesis.fallbackReason.exists(_.contains(StopReasons.TruncatedError)))
      assert(r.response.claims.nonEmpty)
      assert(
        r.response.claims.forall(_.status == VerificationStatus.Verified),
        r.response.claims.map(c => c.claimId -> c.status).toString
      )
    } finally fx.service.close()
  }

  test("a claim citing a fabricated evidence ID never carries that ID in the response") {
    val fake = fabricated("evidence")
    val (fx, r) = run { rule =>
      new ScriptModel(
        rule,
        synthesis =
          Some((_, i) => firstClaimEdited(i)(c => c.copy(evidenceIds = c.evidenceIds :+ fake)))
      )
    }
    try {
      val c = claim(r, "claim-1").getOrElse(fail("claim-1 missing"))
      assert(!c.evidenceIds.map(_.value).contains(fake))
      assertNotEquals(c.status, VerificationStatus.Verified)
      assert(!r.audit.evidenceIds.map(_.value).contains(fake))
      assert(!r.response.bundle.toList.flatMap(_.items).exists(_.evidenceId.value == fake))
    } finally fx.service.close()
  }

  private def onlyFabricated(fake: String): RulePlanner => LanguageModel = rule =>
    new ScriptModel(
      rule,
      synthesis = Some((_, _) =>
        Scripted(
          AnswerDraft(
            "Overview.",
            List(
              DraftClaim("claim-1", "Carol King has 7 distinct coauthors.", List(fake), Nil, None)
            ),
            Nil
          ).toJson.render()
        )
      )
    )

  test("a synthesis reply whose only claim cites only fabricated IDs is never verified") {
    val fake = fabricated("evidence")
    val (fx, r) = run(onlyFabricated(fake))
    try {
      val c = claim(r, "claim-1").getOrElse(fail("claim-1 missing"))
      assertEquals(c.status, VerificationStatus.Unsupported)
      assertEquals(c.evidenceIds, Nil)
      assert(!r.audit.evidenceIds.map(_.value).contains(fake))
      assert(!r.response.bundle.toList.flatMap(_.items).exists(_.evidenceId.value == fake))
      // no claim verified: the answer abstains
      assert(r.response.abstained, "an answer with no verified claim must abstain")
    } finally fx.service.close()
  }

  // T-07 owner: rag-engineer (QueryWorkflow.finish writes a removed claim's text as "Removed
  // (<status>): <first verification reason>", and ClaimVerification's reason quotes the cited ID,
  // so a model-made identifier is shown in the response). Repaired in Phase 11 step 3f
  // (fixed-text verifier reasons); the .fail mark was removed in step 4.
  test("T-07 a removed claim's text never quotes the fabricated ID it cited") {
    val fake = fabricated("evidence")
    val (fx, r) = run(onlyFabricated(fake))
    try {
      val c = claim(r, "claim-1").getOrElse(fail("claim-1 missing"))
      // the specific wrong value: "Removed (unsupported): cites <fake>, which is not in the
      // evidence bundle"
      assert(!c.claimText.contains(fake), c.claimText)
    } finally fx.service.close()
  }

  // T-01 owner: rag-engineer (ClaimVerification: an identifier the evidence does not hold only
  // makes the claim partially_supported, so the model-made identifier is shown in the answer).
  // Repaired in Phase 11 step 3f; the .fail mark was removed in step 4.
  test("T-01 a fabricated researcher or evidence ID in kept claim text reaches the response") {
    val fakeResearcher = fabricated("researcher")
    val fakeEvidence = fabricated("evidence")
    val (fx, r) = run { rule =>
      new ScriptModel(
        rule,
        synthesis = Some((_, i) =>
          firstClaimEdited(i)(c =>
            c.copy(text = c.text.stripSuffix(".") + s"; see $fakeResearcher and $fakeEvidence.")
          )
        )
      )
    }
    try {
      val c = claim(r, "claim-1").getOrElse(fail("claim-1 missing"))
      val shown = responseText(r)
      // the specific wrong value: the claim is kept (partially_supported) with both IDs in its text
      assert(
        !shown.contains(fakeResearcher) && !shown.contains(fakeEvidence),
        s"claim-1 status=${c.status.wire} kept text=${c.claimText}"
      )
    } finally fx.service.close()
  }

  // T-02 owner: rag-engineer (AnswerClaimsSchema / PlanDraftSchema via JsonSupport.parseObject:
  // ujson keeps the last of two equal keys, so an ambiguous model object is accepted).
  test("T-02 model output with a duplicate JSON key is rejected, not resolved last-wins".fail) {
    val claims =
      """{"summary":"First summary.","summary":"Second summary.","claims":[],"missingEvidence":[]}"""
    val ctx = RuntimeFixtures.world().ctx
    val plan =
      """{"operations":["collaboration_breadth"],"operations":["shortest_path"],"researcherNames":["Carol King"],"orcids":[],"windowLabel":null,"comparisonWindowLabels":[],"rankingMetric":null,"limit":null,"maxPathDepth":null,"topic":null,"unsupportedConstraints":[]}"""
    val a = AnswerClaimsSchema.validate(claims, AnswerClaimsSpec.V2)
    val p = PlanDraftSchema.validate(plan, ctx.bounds)
    assert(a.isLeft && p.isLeft, s"answer-claims: $a; plan draft: $p")
  }

  test("a valid draft whose summary carries digits and IDs is sanitized before it is shown") {
    val (fx, r) = run { rule =>
      new ScriptModel(
        rule,
        synthesis = Some((_, i) =>
          Scripted(
            FakeSynthesis
              .render(i)
              .copy(summary = s"Carol King has 42 coauthors per ${fabricated("researcher")}.")
              .toJson
              .render()
          )
        )
      )
    }
    try {
      assert(!responseText(r).contains("42 coauthors"))
      assert(!responseText(r).contains(fabricated("researcher")))
      assert(r.synthesis.sanitizeNotes.nonEmpty)
    } finally fx.service.close()
  }
}
