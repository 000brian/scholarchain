package org.scholarchain.hadoop.ids

import org.scholarchain.core.{
  CodePointOrder,
  Contract,
  ExternalIdLink,
  ExternalIdType,
  LinkId,
  LinkSubjectKind,
  SchemaVersions,
  SourceType
}
import org.scholarchain.hadoop.common.{Reasons, RunContext}
import org.scholarchain.hadoop.sources.{NormalizedSource, SourceNormalizers}
import org.scholarchain.hadoop.teams.MentionContext

/** J03: exact-identifier keys. Every key is a canonical identifier from core (`Doi`, `Orcid`,
  * `OpenAlexId`); there is no fuzzy or partial matching here.
  */
object IdentifierKeys {
  val DoiPrefix = "doi\t"

  def doiKey(doi: String): String = DoiPrefix + doi

  /** DOI keys of a mention context (its work's DOI). */
  def keysOf(c: MentionContext): List[String] = c.doi.toList.map(doiKey)

  /** DOI keys of a normalized source record: its work DOI, or every DOI an ORCID record lists.
    * OpenAlex author records carry no DOI and are not joined by DOI.
    */
  def keysOf(n: NormalizedSource): List[String] = n.dois.distinct.sorted.map(doiKey)
}

/** A conflict found while joining one identifier group. */
final case class JoinConflict(reason: String, detail: String)

final case class JoinOutcome(
    links: List[ExternalIdLink],
    conflicts: List[JoinConflict],
    unmatched: Boolean,
    schemaFailures: List[String]
)

/** J04 reduce logic for one DOI group: dblp mention contexts plus enrichment records that assert
  * the same canonical DOI.
  *
  * Links (all `ExternalIdLink`, provenance = the asserting source's dataset and snapshot):
  *   - OpenAlex work: work -> openalex_work, work -> doi, venue -> openalex_source (when the dblp
  *     work has a venue key and OpenAlex a primary source);
  *   - Crossref work and ORCID record: work -> doi;
  *   - per author: when exactly one dblp mention and exactly one source authorship on this work
  *     carry the same ORCID iD, mention -> orcid, and for OpenAlex also mention -> openalex_author
  *     and mention -> openalex_institution (affiliation on this work);
  *   - ORCID record: mention -> orcid when the dblp mention on this work carries that iD.
  *
  * Not linked, reported as conflicts: a DOI on several dblp works, a DOI on several OpenAlex works,
  * an ORCID iD on several mentions or several source authorships of one work. A DOI group without a
  * dblp work is unmatched. Links with the same link ID merge their source record IDs.
  */
object ExactLinker {

  def link(
      doi: String,
      contexts: List[MentionContext],
      sources: List[NormalizedSource],
      run: RunContext
  ): JoinOutcome = {
    val conflicts = List.newBuilder[JoinConflict]
    val works = contexts.map(_.workId).distinct.sorted
    if (works.isEmpty) return JoinOutcome(Nil, Nil, unmatched = true, Nil)
    if (works.size > 1)
      return JoinOutcome(
        Nil,
        List(
          JoinConflict(
            Reasons.DoiMultipleWorks,
            s"$doi: ${works.size} dblp works (${contexts.map(_.dblpKey).distinct.sorted.mkString(", ")})"
          )
        ),
        unmatched = false,
        Nil
      )
    val workId = works.head
    val ms = contexts.distinctBy(_.mentionId)
    val venueKey = ms.flatMap(_.venueKey).headOption
    val raw =
      List.newBuilder[(LinkSubjectKind, String, ExternalIdType, String, NormalizedSource, String)]
    def add(
        kind: LinkSubjectKind,
        subject: String,
        t: ExternalIdType,
        value: String,
        src: NormalizedSource,
        srcId: String
    ): Unit = raw += ((kind, subject, t, value, src, srcId))

    def mentionLinks(src: NormalizedSource, srcId: String, extra: Boolean): Unit = {
      val byOrcid = src.authorships.filter(_.orcid.nonEmpty).groupBy(_.orcid.get)
      byOrcid.toList.sortBy(_._1).foreach { case (orcid, auths) =>
        val matches = ms.filter(_.orcid.contains(orcid))
        if (matches.size > 1)
          conflicts += JoinConflict(
            Reasons.OrcidMultipleMentions,
            s"$doi: $orcid on ${matches.size} mentions"
          )
        else if (matches.size == 1 && auths.size > 1)
          conflicts += JoinConflict(
            Reasons.OrcidMultipleAuthorships,
            s"$doi: $orcid on ${auths.size} ${src.kind} authorships"
          )
        else if (matches.size == 1) {
          val m = matches.head.mentionId
          val a = auths.head
          add(LinkSubjectKind.Mention, m, ExternalIdType.Orcid, orcid, src, srcId)
          if (extra) {
            a.openAlexAuthorId.foreach(
              add(LinkSubjectKind.Mention, m, ExternalIdType.OpenAlexAuthor, _, src, srcId)
            )
            a.institutionIds.foreach(
              add(LinkSubjectKind.Mention, m, ExternalIdType.OpenAlexInstitution, _, src, srcId)
            )
          }
        }
      }
    }

    val openAlexWorks = sources.filter(_.kind == SourceNormalizers.OpenAlexWork)
    val distinctW = openAlexWorks.flatMap(_.openAlexWorkId).distinct.sorted
    if (distinctW.size > 1)
      conflicts += JoinConflict(
        Reasons.DoiMultipleOpenAlexWorks,
        s"$doi: ${distinctW.mkString(", ")}"
      )
    else
      openAlexWorks.sortBy(_.sourceRecordId).foreach { s =>
        val w = s.openAlexWorkId.get
        add(LinkSubjectKind.Work, workId, ExternalIdType.OpenAlexWork, w, s, w)
        add(LinkSubjectKind.Work, workId, ExternalIdType.Doi, doi, s, w)
        for { v <- venueKey; src <- s.openAlexSourceId }
          add(LinkSubjectKind.Venue, v, ExternalIdType.OpenAlexSource, src, s, w)
        mentionLinks(s, w, extra = true)
      }
    sources.filter(_.kind == SourceNormalizers.CrossrefWork).sortBy(_.sourceRecordId).foreach { s =>
      add(LinkSubjectKind.Work, workId, ExternalIdType.Doi, doi, s, s.sourceRecordId)
      mentionLinks(s, s.sourceRecordId, extra = false)
    }
    sources.filter(_.kind == SourceNormalizers.OrcidRecord).sortBy(_.sourceRecordId).foreach { s =>
      val orcid = s.orcid.get
      add(LinkSubjectKind.Work, workId, ExternalIdType.Doi, doi, s, orcid)
      val matches = ms.filter(_.orcid.contains(orcid))
      if (matches.size > 1)
        conflicts += JoinConflict(
          Reasons.OrcidMultipleMentions,
          s"$doi: $orcid on ${matches.size} mentions"
        )
      else
        matches.foreach(m =>
          add(LinkSubjectKind.Mention, m.mentionId, ExternalIdType.Orcid, orcid, s, orcid)
        )
    }

    val failures = List.newBuilder[String]
    val links = raw
      .result()
      .groupBy { case (k, subj, t, v, src, _) =>
        (k, subj, t, v, src.sourceType, src.datasetId, src.snapshotId)
      }
      .toList
      .flatMap { case ((k, subj, t, v, st, ds, snap), group) =>
        val sourceType =
          SourceType.fromWire(st).fold(e => throw new IllegalStateException(e), identity)
        val srcIds = group.map(_._6).distinct.sorted(CodePointOrder.ordering)
        val link = ExternalIdLink(
          SchemaVersions.currentOf("ExternalIdLink"),
          LinkId.of(k, subj, t, v, sourceType, ds, snap),
          k,
          subj,
          t,
          v,
          run.provenance(sourceType, srcIds, None, ds, snap)
        )
        Contract.validate(link) match {
          case Nil  => List(link)
          case errs =>
            failures += s"${link.linkId}: ${errs.mkString("; ")}"
            Nil
        }
      }
      .sortBy(_.linkId.value)
    JoinOutcome(links, conflicts.result(), unmatched = false, failures.result())
  }
}
