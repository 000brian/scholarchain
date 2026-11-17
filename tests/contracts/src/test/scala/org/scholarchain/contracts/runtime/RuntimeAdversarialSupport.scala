package org.scholarchain.contracts.runtime

import java.util.concurrent.atomic.AtomicInteger

import scala.collection.mutable

import org.scholarchain.core.*
import org.scholarchain.core.config.LlmProvider
import org.scholarchain.rag.runtime.*

/** Shared pieces of the Phase 11 cross-module runtime suites: a scripted model whose planner side
  * is the rule planner (or a scripted reply) and whose synthesis side is a function of the
  * synthesis input, with a chosen stop reason. Deterministic; no network.
  */
object RuntimeAdversarialSupport {

  /** A reply text and its stop reason. */
  final case class Scripted(text: String, stopReason: Option[String] = Some("end_turn"))

  /** Planner: `planner` when given (per call index, 0-based), else the rule planner. Synthesis:
    * `synthesis` when given (per call index and input), else the fake template synthesis.
    */
  final class ScriptModel(
      rule: RulePlanner,
      planner: Option[Int => Scripted] = None,
      synthesis: Option[(Int, SynthesisInput) => Scripted] = None
  ) extends LanguageModel {
    val provider: LlmProvider = LlmProvider.Anthropic
    val modelId: String = "phase11-adversarial-model"
    val usage = new LlmUsage
    val plannerCalls = new AtomicInteger(0)
    val synthesisCalls = new AtomicInteger(0)
    val requests: mutable.ListBuffer[ModelRequest] = mutable.ListBuffer.empty

    def complete(r: ModelRequest): Either[ModelFailure, ModelReply] = synchronized {
      requests += r
      val s = r.fake match {
        case PlannerFakeInput(q) =>
          val n = plannerCalls.getAndIncrement()
          planner.map(_(n)).getOrElse(Scripted(rule.draft(q).toJson.render()))
        case SynthesisFakeInput(i) =>
          val n = synthesisCalls.getAndIncrement()
          synthesis
            .map(_(n, i))
            .getOrElse(Scripted(FakeSynthesis.render(i).toJson.render()))
      }
      Right(ModelReply(s.text, Some(10L), Some(5L), s.stopReason))
    }
  }

  /** The fake template draft of `i` with its first claim replaced by `f(first)`. */
  def firstClaimEdited(i: SynthesisInput)(f: DraftClaim => DraftClaim): Scripted = {
    val d = FakeSynthesis.render(i)
    require(d.claims.nonEmpty, "the fixture question must produce at least one template claim")
    Scripted(d.copy(claims = f(d.claims.head) :: d.claims.tail).toJson.render())
  }

  /** The fake template draft of `i` with `extra` added as the last claim. */
  def withExtraClaim(i: SynthesisInput)(extra: AnswerDraft => DraftClaim): Scripted = {
    val d = FakeSynthesis.render(i)
    Scripted(d.copy(claims = d.claims :+ extra(d)).toJson.render())
  }

  /** The whole response as JSON text (for "this string never appears" checks). */
  def responseText(r: WorkflowResult): String = upickle.default.write(r.response)

  /** The audit record as its stored JSON line. */
  def auditText(r: WorkflowResult): String =
    Contract.encode(r.audit).fold(e => sys.error(e.message), identity)

  /** A fabricated, well-formed stable ID of `kind` that no fixture record has. */
  def fabricated(kind: String): String = s"$kind:v1:deadbeefdeadbeefdeadbeefdeadbeef"

  /** The claim with the given ID in a result's response. */
  def claim(r: WorkflowResult, id: String): Option[AnswerClaim] =
    r.response.claims.find(_.claimId == id)
}
