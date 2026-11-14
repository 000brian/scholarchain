package org.scholarchain.er.policy

import org.scholarchain.core.{MatchFeature, MatchOutcome, MatchPath}
import org.scholarchain.er.data.Corpus
import org.scholarchain.er.features.{PairEvidence, PairFeatures}

/** Feature names (decision 10).
  *
  * J06 (`candidate-features/1-provisional`, reused verbatim): coauthor_shared_count,
  * homonym_suffix_match, name_exact_match, orcid_exact_match, same_venue, same_work, year_abs_diff.
  *
  * Entity resolution:
  *   - given_name_relation: 2 equal folded given names, 1 compatible (initials, missing middle
  *     names), 0 conflicting ([[org.scholarchain.er.normalize.NameParts.givenNameRelation]]).
  *   - surname_equal: 1 when the folded surnames (last tokens) are equal, else 0.
  *   - name_similarity: Jaro-Winkler of the folded names (omitted when either has no tokens).
  *   - coauthor_overlap_ratio: coauthor_shared_count / (smaller team size - 1), capped at 1;
  *     omitted when the count is omitted or either work has a single author.
  *   - venue_area_match: 1 when both venues map to the same research area of the policy
  *     `venueAreas`, else 0; omitted when either venue is unknown or unmapped.
  *   - title_similarity: Jaccard of the two titles' content-word tokens (`title-tokens/1`); omitted
  *     when either title has no tokens.
  *   - author_role_match: 1 when both mentions are first authors or both are last authors, else 0;
  *     omitted when either work has a single author.
  *   - orcid_match: 1 when the two mentions' ORCID iDs (dblp attribute plus J04 mention links) are
  *     one single iD, 0 otherwise; omitted unless both have one.
  *   - openalex_author_match, affiliation_overlap: from J04 mention links only (OpenAlex author
  *     IDs, OpenAlex institutions at publication time); omitted unless both mentions have them.
  *   - source_reliability: lowest policy source reliability among the sources the features read.
  *
  * Not applicable (no code): citation-neighborhood evidence (no citation data), Semantic Scholar
  * IDs (not a ScholarChain dataset).
  */
object PairFeatureNames {
  val CoauthorSharedCount = "coauthor_shared_count"
  val HomonymSuffixMatch = "homonym_suffix_match"
  val NameExactMatch = "name_exact_match"
  val OrcidExactMatch = "orcid_exact_match"
  val SameVenue = "same_venue"
  val SameWork = "same_work"
  val YearAbsDiff = "year_abs_diff"

  val GivenNameRelation = "given_name_relation"
  val SurnameEqual = "surname_equal"
  val NameSimilarity = "name_similarity"
  val CoauthorOverlapRatio = "coauthor_overlap_ratio"
  val VenueAreaMatch = "venue_area_match"
  val TitleSimilarity = "title_similarity"
  val AuthorRoleMatch = "author_role_match"
  val OrcidMatch = "orcid_match"
  val OpenAlexAuthorMatch = "openalex_author_match"
  val AffiliationOverlap = "affiliation_overlap"
  val SourceReliability = "source_reliability"

  val J06: Set[String] =
    Set(
      CoauthorSharedCount,
      HomonymSuffixMatch,
      NameExactMatch,
      OrcidExactMatch,
      SameVenue,
      SameWork,
      YearAbsDiff
    )

  val ErComputed: Set[String] = Set(
    GivenNameRelation,
    SurnameEqual,
    NameSimilarity,
    CoauthorOverlapRatio,
    VenueAreaMatch,
    TitleSimilarity,
    AuthorRoleMatch,
    OrcidMatch,
    OpenAlexAuthorMatch,
    AffiliationOverlap,
    SourceReliability
  )

  /** Features the policy may weight (ORCID and source reliability act through rules and scaling).
    */
  val Scored: Set[String] = (J06 ++ ErComputed) -- Set(
    OrcidMatch,
    OrcidExactMatch,
    SourceReliability,
    NameExactMatch,
    SameWork,
    HomonymSuffixMatch
  )
}

/** The policy's verdict on one mention pair before LLM adjudication and cluster merge guards.
  *
  * @param matchProbability
  *   the scored probability that the mentions are one person (probabilistic path only)
  * @param inLlmBand
  *   unresolved with `matchProbability` in `[llmBandLower, accept)`
  */
final case class Assessment(
    outcome: MatchOutcome,
    path: MatchPath,
    confidence: Double,
    matchProbability: Option[Double],
    features: List[MatchFeature],
    rulesFired: List[String],
    reasonCodes: List[String],
    inLlmBand: Boolean,
    nearThreshold: Boolean
)

/** Pair decision rules and scoring of policy er-v1. Rules are tried in order; the first that fires
  * decides; otherwise the weighted score decides:
  *
  *   1. structural.same-work: same work, conflict (deterministic).
  *   1. orcid.disagree: both have ORCID iDs that are not one single iD, conflict (exact_id).
  *   1. orcid.agree-dblp-homonym-differs / orcid.exact: one shared ORCID iD; conflict when dblp
  *      gives the same base name different homonym numbers, else accepted (exact_id).
  *   1. openalex-author.agree-dblp-homonym-differs / openalex-author.exact: likewise for one shared
  *      OpenAlex author ID. Different OpenAlex author IDs are only a weighted feature.
  *   1. dblp-prior.same-name: identical exact dblp name strings (one dblp-prior cluster), accepted
  *      (deterministic).
  *   1. name.canonical-equivalent: different exact strings with equal author-name-key/1 full-name
  *      keys (canonically equivalent spellings such as precomposed and decomposed `é`, same homonym
  *      suffix), accepted (deterministic). The two strings are different dblp-prior clusters
  *      (decision 5); this accepted decision is what may join them, subject to the merge guards.
  *   1. dblp-prior.distinct-homonym: same base name, different homonym numbers, rejected
  *      (deterministic).
  *   1. score: p = logistic(prior + sum of weights); accepted when p >= accept, rejected when p <
  *      reject, else unresolved (in the LLM band when p >= llmBandLower).
  *
  * Confidence: rule decisions carry the rule's fixed confidence; scored decisions carry p when
  * accepted or unresolved and 1 - p when rejected.
  */
object DecisionEngine {

  def homonymDiffers(corpus: Corpus, a: Int, b: Int): Boolean = {
    val na = corpus.names.parts(corpus.mentions.name(a))
    val nb = corpus.names.parts(corpus.mentions.name(b))
    na.baseKey == nb.baseKey && na.suffix.isDefined && nb.suffix.isDefined && na.suffix != nb.suffix
  }

  def assess(
      corpus: Corpus,
      policy: Policy,
      j06: List[MatchFeature],
      a: Int,
      b: Int
  ): Assessment = {
    val ev = PairFeatures.compute(corpus, policy, j06, a, b)
    decide(corpus, policy, ev, a, b)
  }

  def decide(corpus: Corpus, policy: Policy, ev: PairEvidence, a: Int, b: Int): Assessment = {
    val m = corpus.mentions
    def rule(id: String, outcome: MatchOutcome, path: MatchPath, reasons: List[String] = Nil) =
      Assessment(
        outcome,
        path,
        policy.ruleConfidence(id),
        None,
        ev.features,
        List(id),
        reasons.sorted,
        false,
        false
      )
    lazy val homonym = homonymDiffers(corpus, a, b)
    val oa = m.orcids(a)
    val ob = m.orcids(b)
    val aa = m.linked(a, "openalex_author").map(_.value).distinct
    val ab = m.linked(b, "openalex_author").map(_.value).distinct
    if (m.work(a) == m.work(b))
      rule(Rules.SameWork, MatchOutcome.Conflict, MatchPath.Deterministic, List(Reasons.SameWork))
    else if (oa.nonEmpty && ob.nonEmpty && (oa ++ ob).distinct.size > 1)
      rule(
        Rules.OrcidDisagree,
        MatchOutcome.Conflict,
        MatchPath.ExactId,
        List(Reasons.OrcidDisagree)
      )
    else if (oa.nonEmpty && ob.nonEmpty && homonym)
      rule(
        Rules.OrcidHomonymConflict,
        MatchOutcome.Conflict,
        MatchPath.ExactId,
        List(Reasons.OrcidHomonym)
      )
    else if (oa.nonEmpty && ob.nonEmpty)
      rule(Rules.OrcidExact, MatchOutcome.Accepted, MatchPath.ExactId)
    else if (aa.size == 1 && ab.size == 1 && aa == ab && homonym)
      rule(
        Rules.OpenAlexHomonymConflict,
        MatchOutcome.Conflict,
        MatchPath.ExactId,
        List(Reasons.OpenAlexHomonym)
      )
    else if (aa.size == 1 && ab.size == 1 && aa == ab)
      rule(Rules.OpenAlexExact, MatchOutcome.Accepted, MatchPath.ExactId)
    else if (m.name(a) == m.name(b))
      rule(Rules.DblpSameName, MatchOutcome.Accepted, MatchPath.Deterministic)
    else if (corpus.names.parts(m.name(a)).fullKey == corpus.names.parts(m.name(b)).fullKey)
      rule(Rules.CanonicalEquivalentName, MatchOutcome.Accepted, MatchPath.Deterministic)
    else if (homonym)
      rule(Rules.DblpDistinctHomonym, MatchOutcome.Rejected, MatchPath.Deterministic)
    else score(policy, ev)
  }

  def score(policy: Policy, ev: PairEvidence): Assessment = {
    val t = policy.thresholds
    val lo = policy.logOdds(ev.features, ev.reliability)
    val p = Policy.logistic(lo)
    val near =
      math.abs(lo - Policy.logit(t.accept)) <= t.nearThresholdLogit ||
        math.abs(lo - Policy.logit(t.reject)) <= t.nearThresholdLogit
    if (p >= t.accept)
      Assessment(
        MatchOutcome.Accepted,
        MatchPath.Probabilistic,
        p,
        Some(p),
        ev.features,
        List(Rules.ScoreAccept),
        Nil,
        false,
        near
      )
    else if (p < t.reject)
      Assessment(
        MatchOutcome.Rejected,
        MatchPath.Probabilistic,
        1.0 - p,
        Some(p),
        ev.features,
        List(Rules.ScoreReject),
        Nil,
        false,
        near
      )
    else {
      val band = p >= t.llmBandLower
      Assessment(
        MatchOutcome.Unresolved,
        MatchPath.Probabilistic,
        p,
        Some(p),
        ev.features,
        List(if (band) Rules.ScoreLlmBand else Rules.ScoreUnresolved),
        List(Reasons.ScoreUnresolved),
        band,
        near
      )
    }
  }
}
