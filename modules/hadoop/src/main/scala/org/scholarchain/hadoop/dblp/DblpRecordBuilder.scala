package org.scholarchain.hadoop.dblp

import org.scholarchain.core.{
  AuthorMention,
  ContentHash,
  Contract,
  DblpAuthorName,
  DblpKey,
  Doi,
  MentionId,
  Orcid,
  SchemaVersions,
  SourceType,
  VenueKey,
  WorkId,
  WorkRecord
}
import org.scholarchain.hadoop.common.{Reasons, RunContext, Settings}

/** dblp subset rule (dataset manifest `subset_rule.dblp`), from configuration. */
final case class DblpSubsetRule(
    recordElements: Set[String],
    enabled: Boolean,
    keyPrefixes: List[String],
    yearMin: Int,
    yearMax: Int,
    excludedPubltypeTokens: Set[String]
)

object DblpSubsetRule {
  def from(s: Settings): DblpSubsetRule = {
    import Settings.Keys.*
    DblpSubsetRule(
      s.list(DblpRecordElements).toSet,
      s.boolean(DblpSubsetEnabled),
      s.list(DblpKeyPrefixes),
      s.int(DblpYearMin),
      s.int(DblpYearMax),
      s.list(DblpExcludePubltype).toSet
    )
  }
}

/** A field-level problem: the record is still emitted without the field. */
final case class FieldIssue(reason: String, detail: String)

sealed trait DblpOutcome
object DblpOutcome {
  final case class Accepted(
      work: WorkRecord,
      mentions: List[AuthorMention],
      venueLabel: Option[String],
      issues: List[FieldIssue]
  ) extends DblpOutcome
  final case class Excluded(reason: String, key: String) extends DblpOutcome
  final case class Rejected(reason: String, detail: String, key: Option[String]) extends DblpOutcome
}

/** Turns parsed dblp fields into validated core records.
  *
  * Decision order (first match wins), matching `datasets/scripts/dblp_subset_stats.py` for the
  * subset checks: missing key, invalid key, element, key prefix, year, publtype; then the record
  * rules: conflicting singleton fields, missing title, no authors, invalid author names; finally
  * the core validators (`Contract.encode`).
  *
  * Year: a missing year excludes the record (reason `year`, as in the subset script); a year that
  * is present but not four ASCII digits rejects it (`invalid_year`).
  *
  * Links (WorkRecord v2 `eeLinks`, `urls`): every `<ee>` and `<url>` text, entity-decoded by the
  * parser and trimmed, in source order; a repeated value keeps its first position. A value that is
  * blank or still contains a control character after trimming (for example an embedded newline) is
  * dropped with a field issue (`blank_source_link`, `invalid_source_link`). The DOI is derived only
  * from the kept `eeLinks` (`Doi.fromDblpEe`), so `doi` is always the DOI of one of them.
  *
  * Field issues (record kept): an `orcid` attribute that is not a valid ORCID iD is dropped
  * (`invalid_orcid_attr`); `<ee>` values that normalise to more than one distinct DOI leave `doi`
  * empty (`conflicting_dois`, counted as a conflict).
  */
object DblpRecordBuilder {
  import DblpOutcome.*

  private val FourDigits = "[0-9]{4}".r

  private def collapse(s: String): String = s.replaceAll("(?U)\\s+", " ").trim

  /** WorkRecord v2 link list rule (see the object comment); issues name the field and position. */
  def sourceLinks(field: String, raw: List[String]): (List[String], List[FieldIssue]) = {
    val issues = List.newBuilder[FieldIssue]
    val kept = raw.zipWithIndex.flatMap { case (v, i) =>
      val t = v.trim
      if (t.isEmpty) {
        issues += FieldIssue(Reasons.BlankSourceLink, s"$field position $i is blank")
        None
      } else if (t.exists(Character.isISOControl)) {
        issues += FieldIssue(
          Reasons.InvalidSourceLink,
          s"$field position $i contains a control character"
        )
        None
      } else Some(t)
    }
    (kept.distinct, issues.result())
  }

  def build(
      f: DblpRawFields,
      rule: DblpSubsetRule,
      run: RunContext,
      rawRecordText: String
  ): DblpOutcome = {
    val key = f.key.getOrElse("")
    if (f.key.forall(_.trim.isEmpty)) return Rejected(Reasons.MissingKey, "no key attribute", None)
    if (!DblpKey.isValid(key))
      return Rejected(Reasons.InvalidKey, s"'$key' is not a dblp key", Some(key))
    if (!rule.recordElements.contains(f.element)) return Excluded(Reasons.ExElement, key)
    if (rule.enabled && !rule.keyPrefixes.exists(p => key.startsWith(p)))
      return Excluded(Reasons.ExKeyPrefix, key)

    val years = f.years.map(_.trim).distinct
    if (years.size > 1)
      return Rejected(Reasons.ConflictingField, s"year: ${years.mkString(", ")}", Some(key))
    val year = years.headOption match {
      case None                             => return Excluded(Reasons.ExYear, key)
      case Some(y) if FourDigits.matches(y) => y.toInt
      case Some(y) => return Rejected(Reasons.InvalidYear, s"year '$y'", Some(key))
    }
    if (rule.enabled && (year < rule.yearMin || year > rule.yearMax))
      return Excluded(Reasons.ExYear, key)
    val tokens = f.publtype.toList.flatMap(_.split("\\s+").toList).filter(_.nonEmpty).toSet
    if (rule.enabled && tokens.intersect(rule.excludedPubltypeTokens).nonEmpty)
      return Excluded(Reasons.ExPubltype, key)

    val titles = f.titles.map(collapse).distinct
    if (titles.size > 1)
      return Rejected(Reasons.ConflictingField, s"title: ${titles.size} values", Some(key))
    val title = titles.headOption.getOrElse("")
    if (title.isEmpty) return Rejected(Reasons.MissingTitle, "no title text", Some(key))
    if (f.authors.isEmpty) return Rejected(Reasons.NoAuthors, "no <author> elements", Some(key))

    val names = f.authors.map(a => DblpAuthorName.normalize(a.text))
    val badName = names.indexWhere(_.isEmpty)
    if (badName >= 0)
      return Rejected(
        Reasons.InvalidAuthorName,
        s"author position $badName is empty or has " +
          "control characters",
        Some(key)
      )

    val issues = List.newBuilder[FieldIssue]
    val orcids = f.authors.zipWithIndex.map { case (a, i) =>
      a.orcid.flatMap { raw =>
        val n = Orcid.normalize(raw)
        if (n.isEmpty) issues += FieldIssue(Reasons.InvalidOrcidAttr, s"position $i: '$raw'")
        n
      }
    }
    val (eeLinks, eeIssues) = sourceLinks("ee", f.ees)
    val (urls, urlIssues) = sourceLinks("url", f.urls)
    issues ++= eeIssues
    issues ++= urlIssues
    val dois = eeLinks.flatMap(Doi.fromDblpEe).distinct
    val doi =
      if (dois.size <= 1) dois.headOption
      else {
        issues += FieldIssue(Reasons.ConflictingDois, dois.sorted.mkString(", "))
        None
      }
    val venueLabels = (f.booktitles ++ f.journals).map(collapse).filter(_.nonEmpty).distinct
    val stream = DblpKey.streamKey(key)
    val venueKey = if (VenueKey.isValid(stream)) Some(stream) else None

    val workId = WorkId.fromDblpKey(key)
    val prov = run.provenance(SourceType.Dblp, List(key), Some(ContentHash.of(rawRecordText)))
    val work = WorkRecord(
      SchemaVersions.currentOf("WorkRecord"),
      workId,
      key,
      f.element,
      title,
      year,
      venueKey,
      doi,
      eeLinks,
      urls,
      names.flatten,
      prov
    )
    val mentions = names.flatten.zipWithIndex.map { case (n, i) =>
      AuthorMention(
        SchemaVersions.currentOf("AuthorMention"),
        MentionId.of(workId, i),
        workId,
        i,
        n,
        orcids(i),
        prov
      )
    }
    val errors = Contract.validate(work) ++ mentions.flatMap(m => Contract.validate(m))
    if (errors.nonEmpty) Rejected(Reasons.SchemaFailure, errors.mkString("; "), Some(key))
    else
      Accepted(
        work,
        mentions,
        venueLabels.headOption.filter(_ => venueKey.nonEmpty),
        issues.result()
      )
  }
}
