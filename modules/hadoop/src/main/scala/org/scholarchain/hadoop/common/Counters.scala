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

  /** J05, J05b: per block family, blocks written to `blocks/`. */
  val BlocksPerFamilyGroup = "scholarchain.blocks-per-family"

  /** J05, J05b: per block family, blocks above the size limit (not emitted). */
  val OversizePerFamilyGroup = "scholarchain.oversize-blocks-per-family"

  /** J05: per block family, mentions for which the family yields no key (never blocked there). */
  val NoBlockKeyGroup = "scholarchain.no-block-key"

  /** J05b: per fallback family, oversize members without a secondary key value. */
  val NoSecondaryKeyGroup = "scholarchain.no-secondary-key"

  /** J05b: per primary family, oversize members whose family has no fallback configured. */
  val FallbackNotConfiguredGroup = "scholarchain.fallback-not-configured"

  /** J05c: per primary family, oversize members left in no emitted fallback block. */
  val UncoveredPerFamilyGroup = "scholarchain.uncovered-members-per-family"

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
  val OversizeMembers = "OVERSIZE_BLOCK_MEMBERS"
  val FallbackKeysEmitted = "FALLBACK_KEYS_EMITTED"
  val CoveredMembers = "FALLBACK_COVERED_MEMBERS"
  val UncoveredMembers = "FALLBACK_UNCOVERED_MEMBERS"
  val OversizeKeys = "OVERSIZE_KEYS_SUMMARISED"
  val SameNumberedNameSkipped = "SAME_NUMBERED_NAME_PAIRS_SKIPPED"

  /** J05, J05b: oversize blocks within the member cap whose pairs exceed
    * `scholarchain.candidates.maxPairsPerBlock` (a subset of OVERSIZE_BLOCKS).
    */
  val PairLimitOversizeBlocks = "PAIR_LIMIT_OVERSIZE_BLOCKS"

  /** J05p, J05bp (block-size pre-passes): distinct block keys whose size was counted. */
  val BlockKeysCounted = "BLOCK_KEYS_COUNTED"

  /** J05p, J05bp: block keys over the effective cap, listed in `oversizeKeys/` (known before the
    * blocking step that reads them).
    */
  val OversizeKeysFound = "OVERSIZE_KEYS_FOUND"

  /** J05 mapper: member values of oversize primary keys re-keyed by mention ID (one reduce group
    * per mention, never one per oversize block); equals OVERSIZE_BLOCK_MEMBERS.
    */
  val OversizeMembersRekeyed = "OVERSIZE_MEMBERS_REKEYED"

  /** J05b: fallback groups (re-keyed oversize members) within the fallback cap. */
  val RekeyedBlocks = "REKEYED_BLOCKS"

  /** J05b: fallback groups still over the fallback cap after re-keying (still-oversize policy: not
    * formed, listed in `fallbackOversizeBlocks/`; a subset of OVERSIZE_BLOCKS).
    */
  val StillOversizeBlocks = "STILL_OVERSIZE_BLOCKS"

  /** J05b mapper: fallback key values of still-oversize groups, counted and never shuffled. */
  val StillOversizeMembers = "STILL_OVERSIZE_MEMBERS"

  /** J05, J05b: groups that reached one reduce call with more members than the cap. Always 0 when
    * the job reads its pre-pass (`oversizeKeys/`); non-zero only for runs without the pre-pass.
    */
  val ReducerCappedBlocks = "REDUCER_CAPPED_BLOCKS"

  /** J06: members of blocks J06 did not expand (listed in `unexpandedMembers/`). */
  val UnexpandedMembers = "UNEXPANDED_BLOCK_MEMBERS"

  /** J06: per block family, members of blocks J06 did not expand. */
  val UnexpandedPerFamilyGroup = "scholarchain.unexpanded-members-per-family"
}
