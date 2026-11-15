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
    "AnswerClaim" -> 1,
    "AnswerResponse" -> 1,
    "ScholarChainConfig" -> 1
  )

  /** Versions readers accept, per schema name. Today exactly the current version. */
  val supported: Map[String, Set[Int]] = current.map { case (k, v) => k -> Set(v) }

  def currentOf(schema: String): Int =
    current.getOrElse(schema, throw new IllegalArgumentException(s"unknown schema '$schema'"))

  def isSupported(schema: String, version: Int): Boolean =
    supported.get(schema).exists(_.contains(version))
}
