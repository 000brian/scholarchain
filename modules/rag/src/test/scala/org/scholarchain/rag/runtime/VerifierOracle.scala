package org.scholarchain.rag.runtime

import java.util.concurrent.ConcurrentLinkedQueue

import scala.jdk.CollectionConverters._

import org.scholarchain.core._

/** Phase 17 decision 6.2 (monotonic): every claim a verifier suite checks goes through here. It
  * verifies the claim with the Phase 17 absence rule ([[ClaimVerification.verifyClaim]]) and with
  * the rule switched off ([[ClaimVerification.verifyClaimPhase16]], the Phase 16 behaviour), and
  * fails when the status rose, when a Phase 16 reason is missing, or when the count of passed
  * checks changed. Every status that fell is recorded (and printed with the prefix `[status fell]`)
  * so the report can list it with the clause that lowered it.
  */
object VerifierOracle {

  /** Higher is stronger: verified > partially supported > unsupported > conflicting. */
  def rank(s: VerificationStatus): Int = s match {
    case VerificationStatus.Verified           => 3
    case VerificationStatus.PartiallySupported => 2
    case VerificationStatus.Unsupported        => 1
    case VerificationStatus.Conflicting        => 0
  }

  final case class Fall(
      text: String,
      before: VerificationStatus,
      after: VerificationStatus,
      added: List[String]
  )

  private val falls = new ConcurrentLinkedQueue[Fall]()

  /** The falls recorded so far in this JVM. */
  def recorded: List[Fall] = falls.asScala.toList

  /** Both verdicts; throws when the Phase 17 one is not monotonic. */
  def compare(
      c: DraftClaim,
      items: Map[EvidenceId, EvidenceItem],
      windows: Map[String, YearWindow] = Map.empty
  ): (VerifiedClaim, VerifiedClaim) = {
    val now = ClaimVerification.verifyClaim(c, items, windows)
    val before = ClaimVerification.verifyClaimPhase16(c, items, windows)
    def fail(why: String): Nothing =
      throw new AssertionError(
        s"monotonic: $why: '${c.text}' ${before.status.wire} ${before.reasons} -> " +
          s"${now.status.wire} ${now.reasons}"
      )
    if (rank(now.status) > rank(before.status)) fail("status rose")
    if (!before.reasons.forall(now.reasons.contains)) fail("a Phase 16 reason is missing")
    if (now.checked != before.checked) fail("the passed-check count changed")
    if (rank(now.status) < rank(before.status)) {
      val f = Fall(c.text, before.status, now.status, now.reasons.filterNot(before.reasons.toSet))
      falls.add(f)
      println(s"[status fell] ${f.before.wire} -> ${f.after.wire} ${f.added} | ${f.text}")
    }
    (now, before)
  }

  def verifyClaim(
      c: DraftClaim,
      items: Map[EvidenceId, EvidenceItem],
      windows: Map[String, YearWindow] = Map.empty
  ): VerifiedClaim = compare(c, items, windows)._1

  def verify(
      draft: AnswerDraft,
      bundle: Option[EvidenceBundle],
      windows: Map[String, YearWindow] = Map.empty
  ): List[VerifiedClaim] = {
    val items = bundle.map(_.items).getOrElse(Nil).map(i => i.evidenceId -> i).toMap
    draft.claims.map(verifyClaim(_, items, windows))
  }
}
