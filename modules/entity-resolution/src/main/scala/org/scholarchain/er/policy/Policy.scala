package org.scholarchain.er.policy

import java.nio.charset.StandardCharsets

import scala.util.Try

import org.scholarchain.core.{AuthorNameKey, MatchFeature}
import org.scholarchain.er.io.{DataFiles, ErError}
import org.scholarchain.er.normalize.TitleText

/** One weight bin: a feature value `v` falls in it when `min <= v < max` (`max = None`: no upper
  * bound).
  */
final case class Bin(min: Double, max: Option[Double], weight: Double) {
  def contains(v: Double): Boolean = v >= min && max.forall(v < _)
}

final case class FeatureWeight(
    feature: String,
    bins: List[Bin],
    scaledBySourceReliability: Boolean
) {

  /** The weight of value `v`: the weight of the bin that contains it, or 0 when none does. */
  def weightOf(v: Double): Double = bins.find(_.contains(v)).fold(0.0)(_.weight)
}

final case class Thresholds(
    accept: Double,
    reject: Double,
    llmBandLower: Double,
    llmMinConfidence: Double,
    nearThresholdLogit: Double
)

final case class RuleSpec(
    id: String,
    kind: String,
    path: String,
    outcome: String,
    confidence: Option[Double]
)

/** A scoring policy (decision 11), loaded from a versioned JSON file in
  * `src/main/resources/er/policy/`. Every decision records `policyVersion`.
  *
  * @param sha256
  *   SHA-256 of the policy file bytes (recorded in the run manifest)
  * @param overriddenFrom
  *   the file's policy version when configuration overrode thresholds (then `policyVersion` is the
  *   new version named by configuration)
  */
final case class Policy(
    policyVersion: String,
    status: String,
    nameKeyVersion: String,
    candidateFeatureSet: String,
    titleTokensVersion: String,
    priorLogOdds: Double,
    thresholds: Thresholds,
    weights: List[FeatureWeight],
    rules: List[RuleSpec],
    sourceReliability: Map[String, Double],
    venueAreas: Map[String, String],
    sha256: String,
    overriddenFrom: Option[String]
) {
  private val byFeature: Map[String, FeatureWeight] = weights.map(w => w.feature -> w).toMap
  private val ruleById: Map[String, RuleSpec] = rules.map(r => r.id -> r).toMap

  def rule(id: String): RuleSpec =
    ruleById.getOrElse(id, throw new ErError(s"policy $policyVersion has no rule '$id'"))

  def ruleConfidence(id: String): Double =
    rule(id).confidence.getOrElse(throw new ErError(s"rule '$id' has no fixed confidence"))

  /** Additive log-odds of the features (sorted by name) under this policy. */
  def logOdds(features: List[MatchFeature], reliability: Double): Double =
    features.foldLeft(priorLogOdds) { (acc, f) =>
      byFeature.get(f.name) match {
        case None    => acc
        case Some(w) =>
          acc + w.weightOf(f.value) * (if (w.scaledBySourceReliability) reliability else 1.0)
      }
    }

  /** Names of the features the score reads. */
  def scoredFeatures: Set[String] = byFeature.keySet

  /** Thresholds overridden by configuration; requires a new, different policy version. */
  def withThresholds(newVersion: String, t: Thresholds): Either[String, Policy] =
    if (newVersion == null || newVersion.trim.isEmpty)
      Left("threshold overrides require a new policy version (--policy-version)")
    else if (newVersion == policyVersion)
      Left(s"threshold overrides require a policy version different from '$policyVersion'")
    else if (!newVersion.matches(Policy.VersionPattern))
      Left(s"policy version '$newVersion' must match ${Policy.VersionPattern}")
    else
      Policy
        .checkThresholds(t)
        .map(_ =>
          copy(policyVersion = newVersion, thresholds = t, overriddenFrom = Some(policyVersion))
        )
}

object Policy {

  /** The policy every run uses: er-v2 (Phase 11, finding R2-03: the cluster merge behaviour of
    * finding A-01 changed, so the label changed too).
    */
  val DefaultResource = "er/policy/er-v2.json"

  /** The er-v1 policy file as frozen in Phase 5 (sha256 [[ErV1Sha256]], recorded by every er-v1 run
    * manifest). Kept only as the record of what er-v1 outputs were produced under; the code no
    * longer implements it (er-v2 added the rule `cluster.flag.given-name-conflict`), so it does not
    * load, and no run uses it.
    */
  val ErV1Resource = "er/policy/er-v1.json"
  val ErV1Sha256 = "4946b3504759d5f9a61f60646e6ca9d6cd27b0dd6ef26e57a1729c2fb32807ac"
  val VersionPattern = "[A-Za-z0-9][A-Za-z0-9._:/-]*"

  /** Path declared by cluster-guard rules: the refused decision keeps the match path (and
    * confidence) of the pair decision that produced the accepted evidence, so the decision record
    * stays honest about which path produced it (e.g. `exact_id` with `orcid.exact`).
    */
  val InheritedPath = "inherited"

  /** Allowed rule `path` values: the four match paths, `any` (assignment rules whose path depends
    * on the cluster) and [[InheritedPath]].
    */
  val RulePaths: Set[String] =
    Set("exact_id", "deterministic", "probabilistic", "llm", "any", InheritedPath)

  def logistic(x: Double): Double = 1.0 / (1.0 + math.exp(-x))
  def logit(p: Double): Double = math.log(p / (1.0 - p))

  /** The current frozen policy, er-v2 ([[DefaultResource]]). */
  lazy val current: Policy = fromResource(DefaultResource)

  /** Legacy accessor name, kept so `tests/contracts` compiles unchanged: it is the CURRENT policy
    * ([[current]], er-v2), not er-v1. Prefer [[current]].
    */
  def erV1: Policy = current

  def fromResource(name: String): Policy =
    parse(DataFiles.readResource(name)).fold(e => throw new ErError(s"$name: $e"), identity)

  def checkThresholds(t: Thresholds): Either[String, Unit] = {
    def unit(x: Double) = x > 0.0 && x < 1.0
    if (!unit(t.accept) || !unit(t.reject) || !unit(t.llmBandLower) || !unit(t.llmMinConfidence))
      Left("thresholds must lie strictly between 0 and 1")
    else if (!(t.reject <= t.llmBandLower && t.llmBandLower < t.accept))
      Left(s"thresholds must satisfy reject <= llmBandLower < accept (got $t)")
    else if (!(t.nearThresholdLogit >= 0.0)) Left("nearThresholdLogit must be >= 0")
    else Right(())
  }

  private def strictKeys(obj: ujson.Obj, where: String, allowed: Set[String]): Unit = {
    val unknown = obj.value.keySet.toSet -- allowed
    if (unknown.nonEmpty)
      throw new ErError(s"$where: unknown keys ${unknown.toList.sorted.mkString(", ")}")
    val missing =
      allowed.filterNot(k => obj.value.contains(k) || k == "rationale" || k == "description")
    if (missing.nonEmpty)
      throw new ErError(s"$where: missing keys ${missing.toList.sorted.mkString(", ")}")
  }

  def parse(bytes: Array[Byte]): Either[String, Policy] =
    Try {
      val json = ujson.read(new String(bytes, StandardCharsets.UTF_8)).obj
      strictKeys(
        json,
        "policy",
        Set(
          "policyVersion",
          "status",
          "description",
          "nameKeyVersion",
          "candidateFeatureSet",
          "titleTokensVersion",
          "priorLogOdds",
          "priorRationale",
          "thresholds",
          "weights",
          "rules",
          "sourceReliability",
          "venueAreas",
          "notApplicable"
        )
      )
      val t = json("thresholds").obj
      strictKeys(
        t,
        "thresholds",
        Set(
          "accept",
          "reject",
          "llmBandLower",
          "llmMinConfidence",
          "nearThresholdLogit",
          "rationale"
        )
      )
      val thresholds = Thresholds(
        t("accept").num,
        t("reject").num,
        t("llmBandLower").num,
        t("llmMinConfidence").num,
        t("nearThresholdLogit").num
      )
      val weights = json("weights").arr.toList.map { w0 =>
        val w = w0.obj
        strictKeys(w, "weights[]", Set("feature", "bins", "scaledBySourceReliability", "rationale"))
        val bins = w("bins").arr.toList.map { b0 =>
          val b = b0.obj
          strictKeys(b, s"weights[${w("feature").str}].bins[]", Set("min", "max", "weight"))
          Bin(
            b("min").num,
            b("max") match { case ujson.Null => None; case v => Some(v.num) },
            b("weight").num
          )
        }
        FeatureWeight(w("feature").str, bins, w("scaledBySourceReliability").bool)
      }
      val rules = json("rules").arr.toList.map { r0 =>
        val r = r0.obj
        strictKeys(r, "rules[]", Set("id", "kind", "path", "outcome", "confidence", "description"))
        RuleSpec(
          r("id").str,
          r("kind").str,
          r("path").str,
          r("outcome").str,
          r("confidence") match { case ujson.Null => None; case v => Some(v.num) }
        )
      }
      val rel = json("sourceReliability").obj.value.toMap.collect {
        case (k, v) if k != "rationale" => k -> v.num
      }
      val areas = json("venueAreas").obj.value.toMap.map { case (k, v) => k -> v.str }
      val p = Policy(
        json("policyVersion").str,
        json("status").str,
        json("nameKeyVersion").str,
        json("candidateFeatureSet").str,
        json("titleTokensVersion").str,
        json("priorLogOdds").num,
        thresholds,
        weights,
        rules,
        rel,
        areas,
        DataFiles.sha256OfBytes(bytes),
        None
      )
      validate(p).fold(e => throw new ErError(e), identity)
    }.toEither.left.map(e => Option(e.getMessage).getOrElse(e.toString))

  private def validate(p: Policy): Either[String, Policy] = {
    val ruleIds = p.rules.map(_.id)
    if (!p.policyVersion.matches(VersionPattern)) Left(s"bad policyVersion '${p.policyVersion}'")
    else if (p.nameKeyVersion != AuthorNameKey.Version)
      Left(s"nameKeyVersion ${p.nameKeyVersion} != implemented ${AuthorNameKey.Version}")
    else if (p.titleTokensVersion != TitleText.Version)
      Left(s"titleTokensVersion ${p.titleTokensVersion} != implemented ${TitleText.Version}")
    else if (ruleIds.distinct.size != ruleIds.size) Left("duplicate rule IDs")
    else if (ruleIds.toSet != Rules.All)
      Left(
        s"policy rules ${ruleIds.sorted.mkString(",")} do not match the implemented rules " +
          Rules.All.toList.sorted.mkString(",")
      )
    else if (p.rules.exists(r => !Policy.RulePaths(r.path)))
      Left(
        s"rule paths must be one of ${Policy.RulePaths.toList.sorted.mkString(",")}: " +
          p.rules.filterNot(r => Policy.RulePaths(r.path)).map(_.id).mkString(",")
      )
    else if (p.rules.exists(r => (r.kind == "cluster-guard") != (r.path == Policy.InheritedPath)))
      Left(
        s"cluster-guard rules, and only they, declare path '${Policy.InheritedPath}' " +
          "(a refused merge keeps the path of the pair decision whose evidence was refused)"
      )
    else if (p.rules.exists(r => r.kind == "cluster-guard" && r.confidence.isDefined))
      Left("cluster-guard rules keep the refused decision's confidence; their confidence is null")
    else if (p.weights.map(_.feature).distinct.size != p.weights.size) Left("duplicate weights")
    else if (!p.weights.map(_.feature).toSet.subsetOf(PairFeatureNames.Scored))
      Left(
        s"weights name unknown features: ${(p.weights.map(_.feature).toSet -- PairFeatureNames.Scored).mkString(",")}"
      )
    else if (!Set("dblp", "orcid", "crossref", "openalex").subsetOf(p.sourceReliability.keySet))
      Left("sourceReliability must name dblp, orcid, crossref and openalex")
    else if (p.sourceReliability.values.exists(v => !(v > 0.0 && v <= 1.0)))
      Left("sourceReliability values must be in (0, 1]")
    else checkThresholds(p.thresholds).map(_ => p)
  }
}

/** Every rule ID the code implements; the policy file must list exactly these. */
object Rules {
  val SameWork = "structural.same-work"
  val OrcidDisagree = "orcid.disagree"
  val OrcidHomonymConflict = "orcid.agree-dblp-homonym-differs"
  val OrcidExact = "orcid.exact"
  val OpenAlexHomonymConflict = "openalex-author.agree-dblp-homonym-differs"
  val OpenAlexExact = "openalex-author.exact"
  val DblpSameName = "dblp-prior.same-name"
  val CanonicalEquivalentName = "name.canonical-equivalent"
  val DblpDistinctHomonym = "dblp-prior.distinct-homonym"
  val ScoreAccept = "score.accept"
  val ScoreReject = "score.reject"
  val ScoreUnresolved = "score.unresolved"
  val ScoreLlmBand = "score.llm-band"
  val LlmAdjudicated = "llm.adjudicated"
  val GuardOrcid = "cluster.guard.orcid"
  val GuardSameWork = "cluster.guard.same-work"
  val GuardDistinctHomonym = "cluster.guard.dblp-distinct-homonym"
  val GuardDisambiguation = "cluster.guard.dblp-disambiguation-profile"
  val GuardConflictPair = "cluster.guard.conflict-pair"
  val GuardRejectedPair = "cluster.guard.rejected-pair"
  val PriorCluster = "dblp-prior.cluster"
  val ClusterMerged = "cluster.merged"
  val FlagMultipleOrcids = "cluster.flag.multiple-orcids"
  val FlagSameWork = "cluster.flag.same-work-mentions"
  val FlagGivenNameConflict = "cluster.flag.given-name-conflict"

  lazy val All: Set[String] = Set(
    SameWork,
    OrcidDisagree,
    OrcidHomonymConflict,
    OrcidExact,
    OpenAlexHomonymConflict,
    OpenAlexExact,
    DblpSameName,
    CanonicalEquivalentName,
    DblpDistinctHomonym,
    ScoreAccept,
    ScoreReject,
    ScoreUnresolved,
    ScoreLlmBand,
    LlmAdjudicated,
    GuardOrcid,
    GuardSameWork,
    GuardDistinctHomonym,
    GuardDisambiguation,
    GuardConflictPair,
    GuardRejectedPair,
    PriorCluster,
    ClusterMerged,
    FlagMultipleOrcids,
    FlagSameWork,
    FlagGivenNameConflict
  ) ++ WorkRules

  /** Work-resolution rules (decision 16), implemented in
    * [[org.scholarchain.er.work.WorkResolution]].
    */
  val WorkRules: Set[String] = Set(
    "work.doi.exact-link",
    "work.openalex-work.exact-link",
    "work.j04-conflict",
    "work.doi.shared-by-dblp-works",
    "work.arxiv.shared-by-dblp-works",
    "work.landing-url.shared-by-dblp-works"
  )
}

/** Reason codes written on decisions. */
object Reasons {
  val SameWork = "same-work-mentions"
  val OrcidDisagree = "orcid-disagree"
  val OrcidHomonym = "orcid-agrees-dblp-homonym-differs"
  val OpenAlexHomonym = "openalex-author-agrees-dblp-homonym-differs"
  val ScoreUnresolved = "score-between-thresholds"
  val LlmCap = "llm-band-cap-reached"
  val LlmAbstain = "llm-abstain"
  val LlmLowConfidence = "llm-low-confidence"
  def llmRejected(why: String): String = s"llm-output-rejected:$why"
  val MergeOrcid = "merge-refused:orcid-conflict"
  val MergeSameWork = "merge-refused:same-work"
  val MergeDistinctHomonym = "merge-refused:dblp-distinct-homonym"
  val MergeDisambiguation = "merge-refused:dblp-disambiguation-profile"
  val MergeConflictPair = "merge-refused:conflict-pair"
  val MergeRejectedPair = "merge-refused:rejected-pair"

  /** Implied cannot-link (Phase 11 finding A-01): same folded surname, conflicting given names, no
    * direct accepted decision between the two dblp names. Recorded under
    * [[Rules.GuardRejectedPair]].
    */
  val MergeGivenNameConflict = "merge-refused:given-name-conflict"
  val ClusterMultipleOrcids = "cluster-multiple-orcids"
  val ClusterSameWork = "cluster-same-work-mentions"

  /** The final cluster holds two dblp names with the same folded surname and conflicting given
    * names that no accepted decision joins directly (finding A-01; see
    * [[Rules.FlagGivenNameConflict]]).
    */
  val ClusterGivenNameConflict = "cluster-given-name-conflict"
}
