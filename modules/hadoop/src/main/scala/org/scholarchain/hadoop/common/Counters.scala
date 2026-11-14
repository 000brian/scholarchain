package org.scholarchain.hadoop.common

/** Hadoop counter names. Group [[Counters.Group]] holds the operational counters required by the
  * Phase 4 work order; per-reason groups break rejects, exclusions and field issues down.
  *
  * Accounting rule for every job: `INPUT_RECORDS == ACCEPTED_RECORDS + EXCLUDED_RECORDS +
  * REJECTED_RECORDS` at the map stage. Reduce-stage removals are counted as DUPLICATE_RECORDS
  * (identical copies collapsed) or CONFLICT_RECORDS (rejected copies that disagree, also counted in
  * REJECTED_REDUCE_RECORDS).
  */
object Counters {
  val Group = "scholarchain"
  val RejectReasonGroup = "scholarchain.reject-reason"
  val ExcludedReasonGroup = "scholarchain.excluded-reason"
  val IssueGroup = "scholarchain.field-issue"
  val OutputGroup = "scholarchain.output-records"

  /** J06: per feature name, candidates on which the feature could not be computed (omitted). */
  val FeatureOmittedGroup = "scholarchain.feature-omitted"

  val Input = "INPUT_RECORDS"
  val Accepted = "ACCEPTED_RECORDS"
  val Excluded = "EXCLUDED_RECORDS"
  val Rejected = "REJECTED_RECORDS"
  val RejectedReduce = "REJECTED_REDUCE_RECORDS"
  val Duplicate = "DUPLICATE_RECORDS"
  val Conflict = "CONFLICT_RECORDS"
  val OversizeBlock = "OVERSIZE_BLOCKS"
  val OversizeTeam = "OVERSIZE_AUTHOR_TEAMS"
  val EmittedPair = "EMITTED_PAIRS"
  val PairInstances = "PAIR_INSTANCES_GENERATED"
  val SelfPairs = "SELF_PAIRS_SKIPPED"
  val SingleAuthorTeams = "SINGLE_AUTHOR_TEAMS"
  val PairShards = "PAIR_SHARDS"
  val MissingKey = "MISSING_KEY_RECORDS"
  val SchemaFailure = "SCHEMA_FAILURE_RECORDS"
  val FieldIssues = "FIELD_ISSUES"
  val UnmatchedKeys = "UNMATCHED_KEYS"
  val LinksEmitted = "LINKS_EMITTED"
  val SingletonBlocks = "SINGLETON_BLOCKS"
  val BlocksEmitted = "BLOCKS_EMITTED"
  val CandidateShards = "CANDIDATE_SHARDS"
  val CandidatesEmitted = "MATCH_CANDIDATES_EMITTED"
  val OversizeBlockPairsSkipped = "OVERSIZE_BLOCK_PAIRS_SKIPPED"
}
