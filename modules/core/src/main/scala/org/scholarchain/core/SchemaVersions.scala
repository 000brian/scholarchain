package org.scholarchain.core

import scala.collection.immutable.ListMap

/** Schema versions of every versioned contract in `core`.
  *
  * A record's `schemaVersion` must be in its supported set; anything else is rejected. A breaking
  * change (removed or renamed field, changed meaning, changed ID formula or wire name) bumps the
  * current version of the affected schema and is called out in the agent report.
  */
object SchemaVersions {

  /** Current version written by producers, per schema name.
    *
    * History:
    *   - WorkRecord 2 (Phase 4, breaking): adds required `eeLinks` and `urls` (dblp `<ee>` and
    *     `<url>` in source order) and requires `doi` to be the DOI of one of the `eeLinks`.
    *   - CoauthorPairCandidate 2 (Phase 4, breaking): adds required `positions` (author positions
    *     and mention IDs of every supporting position pair).
    *   - MatchCandidate 1 (Phase 4, new).
    *   - MatchDecision 2 (Phase 5, breaking): subjects follow the MatchCandidate convention
    *     (`subjectKind`, `left`, `right` replace `mentionId` and `candidateResearcherId`); adds
    *     `candidateId`, `matchPath`, `rulesFired`, `reasonCodes`, `modelVersion`, `promptVersion`,
    *     `policyVersion`, `decisionTime`; `outcome` wire names are now `accepted`, `rejected`,
    *     `unresolved`, `conflict`.
    *   - LlmAdjudication 1 (Phase 5, new).
    *   - ResearcherGroupMembership 1 (Phase 6, new).
    *   - SemanticDocument 1, VectorMetadata 1 (Phase 7, new). Phase 7 also adds graph labels and
    *     optional configuration sections without changing any existing version (additive).
    *   - AnswerClaim 2 (Phase 9, breaking): adds required `claimId` (`claim-[0-9]{1,4}`) and
    *     `uncertainty`; VerificationStatus wire names are now `verified`, `partially_supported`,
    *     `unsupported`, `conflicting`.
    *   - AnswerResponse 2 (Phase 9, breaking): claims are AnswerClaim v2; adds `abstained` and
    *     `abstentionReason`; `bundle` is optional (only for abstaining responses).
    *   - QueryAudit 1 (Phase 9, new). Phase 9 also adds QueryOperation and EvidenceType wire names
    *     and the optional `llm` and `runtime` configuration sections (additive; QueryPlan,
    *     EvidenceItem and ScholarChainConfig keep version 1).
    */
  val current: ListMap[String, Int] = ListMap(
    "Provenance" -> 1,
    "WorkRecord" -> 2,
    "AuthorMention" -> 1,
    "ExternalIdLink" -> 1,
    "CanonicalResearcher" -> 1,
    "AuthorshipRecord" -> 1,
    "PublicationTeam" -> 1,
    "CoauthorPairCandidate" -> 2,
    "CoauthorEdge" -> 1,
    "ResearcherMetrics" -> 1,
    "ResearcherGroupMembership" -> 1,
    "Venue" -> 1,
    "Institution" -> 1,
    "Topic" -> 1,
    "MatchCandidate" -> 1,
    "MatchDecision" -> 2,
    "LlmAdjudication" -> 1,
    "GraphNode" -> 1,
    "GraphEdge" -> 1,
    "EvidenceItem" -> 1,
    "EvidenceBundle" -> 1,
    "QueryPlan" -> 1,
    "AnswerClaim" -> 2,
    "AnswerResponse" -> 2,
    "ScholarChainConfig" -> 1,
    "SemanticDocument" -> 1,
    "VectorMetadata" -> 1,
    "QueryAudit" -> 1
  )

  /** Versions readers accept, per schema name. Today exactly the current version. */
  val supported: Map[String, Set[Int]] = current.map { case (k, v) => k -> Set(v) }

  def currentOf(schema: String): Int =
    current.getOrElse(schema, throw new IllegalArgumentException(s"unknown schema '$schema'"))

  def isSupported(schema: String, version: Int): Boolean =
    supported.get(schema).exists(_.contains(version))
}
