package org.scholarchain.hadoop.common

import org.scholarchain.core.Hashing
import upickle.default.{macroRW, write, ReadWriter}

/** Versioned record of an input that was not turned into an output record, or of a field that was
  * dropped from one. Written to `rejects/v1/`, `excluded/v1/`, `issues/v1/` and `conflicts/v1/`.
  *
  * Format `scholarchain.hadoop.disposition`, version 1. Owned by hadoop-engineer; it is a Hadoop
  * job contract (operational accounting), not a shared domain record.
  *
  * @param disposition
  *   `rejected` (input record not emitted), `excluded` (valid but outside the configured subset),
  *   `field_issue` (record emitted, one field dropped), or `conflict` (identifier group not joined)
  * @param reason
  *   machine-readable reason, see [[Reasons]]
  * @param location
  *   `file:byteOffset` of the input when known
  * @param inputSha256
  *   sha256 of the complete offending input (the preview may be cut)
  */
final case class Disposition(
    format: String,
    formatVersion: Int,
    disposition: String,
    reason: String,
    detail: String,
    jobName: String,
    runId: String,
    datasetId: String,
    snapshotId: String,
    sourceRecordId: Option[String],
    location: Option[String],
    inputSha256: Option[String],
    preview: Option[String]
)

object Disposition {
  implicit val rw: ReadWriter[Disposition] = macroRW

  val Format = "scholarchain.hadoop.disposition"
  val Version = 1

  val Rejected = "rejected"
  val Excluded = "excluded"
  val FieldIssue = "field_issue"
  val ConflictKind = "conflict"

  def toJson(d: Disposition): String = write(d)

  def preview(text: String, maxChars: Int): String =
    if (text.length <= maxChars) text else text.substring(0, maxChars)

  def make(
      run: RunContext,
      disposition: String,
      reason: String,
      detail: String,
      sourceRecordId: Option[String],
      location: Option[String],
      input: Option[String],
      previewChars: Int
  ): Disposition =
    Disposition(
      Format,
      Version,
      disposition,
      reason,
      detail,
      run.jobName,
      run.runId.value,
      run.datasetId,
      run.snapshotId,
      sourceRecordId,
      location,
      input.map(Hashing.sha256Hex),
      input.map(preview(_, previewChars))
    )
}

/** Reason codes used in dispositions and per-reason counters. */
object Reasons {
  // input format
  val OversizeRecord = "oversize_record"
  val OversizeLine = "oversize_line"
  val TruncatedRecord = "truncated_record"
  val TruncatedInput = "truncated_input"
  val DecompressionLimit = "decompression_limit_exceeded"
  val BlankLine = "blank_line"
  val InvalidEncoding = "invalid_encoding"
  val UnsupportedEncoding = "unsupported_encoding"
  val PrologNotFound = "prolog_not_found"
  // parsing
  val MalformedXml = "malformed_xml"
  val UndefinedEntity = "undefined_entity"
  val ExpandedRecordOversize = "expanded_record_oversize"
  val MalformedJson = "malformed_json"
  val NotAnObject = "not_a_json_object"
  val MissingKey = "missing_key"
  val InvalidKey = "invalid_key"
  val UnsupportedRecord = "unsupported_record"
  val ConflictingField = "conflicting_field"
  val InvalidYear = "invalid_year"
  val MissingTitle = "missing_title"
  val NoAuthors = "no_authors"
  val InvalidAuthorName = "invalid_author_name"
  val SchemaFailure = "schema_failure"
  // reduce side
  val DuplicateKeyConflict = "duplicate_key_conflict"
  val OrphanMention = "orphan_mention"
  val TeamMentionMismatch = "team_mention_mismatch"
  val TeamExceedsHardLimit = "team_exceeds_hard_limit"
  val GroupTooLarge = "identifier_group_too_large"
  val OversizeBlock = "oversize_block"
  // subset exclusions (dataset manifest names)
  val ExElement = "element"
  val ExKeyPrefix = "key_prefix"
  val ExYear = "year"
  val ExPubltype = "publtype"
  // field issues
  val InvalidOrcidAttr = "invalid_orcid_attr"
  val BlankSourceLink = "blank_source_link"
  val InvalidSourceLink = "invalid_source_link"
  val ConflictingDois = "conflicting_dois"
  val InvalidDoi = "invalid_doi"
  val InvalidOrcid = "invalid_orcid"
  val InvalidOpenAlexId = "invalid_openalex_id"
  val InvalidCountryCode = "invalid_country_code"
  val TopicWithoutName = "topic_without_name"
  val SelfPair = "self_pair"
  val DroppedAbstract = "abstract_dropped_by_policy"
  // identifier joins
  val DoiMultipleWorks = "doi_maps_to_multiple_dblp_works"
  val DoiMultipleOpenAlexWorks = "doi_maps_to_multiple_openalex_works"
  val OrcidMultipleMentions = "orcid_on_multiple_mentions_of_work"
  val OrcidMultipleAuthorships = "orcid_on_multiple_source_authorships"
  // oversize-block fallback (J05c)
  val CoverageWithoutMember = "coverage_without_oversize_member"
}
