package org.scholarchain.core

import scala.collection.immutable.ListMap

/** Schema versions of every versioned contract in `core`.
  *
  * A record's `schemaVersion` must be in its supported set; anything else is rejected. A breaking
  * change (removed or renamed field, changed meaning, changed ID formula or wire name) bumps the
  * current version of the affected schema and is called out in the agent report.
  */
object SchemaVersions {

  /** Current version written by producers, per schema name. */
  val current: ListMap[String, Int] = ListMap(
    "Provenance" -> 1,
    "WorkRecord" -> 1,
    "AuthorMention" -> 1,
    "ExternalIdLink" -> 1,
    "CanonicalResearcher" -> 1,
    "AuthorshipRecord" -> 1,
    "PublicationTeam" -> 1,
    "CoauthorPairCandidate" -> 1,
    "CoauthorEdge" -> 1,
    "ResearcherMetrics" -> 1,
    "Venue" -> 1,
    "Institution" -> 1,
    "Topic" -> 1,
    "MatchDecision" -> 1,
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
