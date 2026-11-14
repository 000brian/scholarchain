package org.scholarchain.hadoop.sources

import java.util.Locale

import scala.util.control.NonFatal

import org.scholarchain.core.{
  ContentHash,
  Contract,
  Doi,
  Hashing,
  Institution,
  OpenAlexId,
  Orcid,
  SchemaVersions,
  SourceType,
  Topic
}
import org.scholarchain.hadoop.common.{Reasons, RunContext}
import org.scholarchain.hadoop.dblp.FieldIssue
import upickle.default.{macroRW, ReadWriter}

/** One author slot of a source work, with every source identifier kept. */
final case class NormalizedAuthorship(
    position: Int,
    sequence: Option[String],
    rawName: Option[String],
    displayName: Option[String],
    orcid: Option[String],
    openAlexAuthorId: Option[String],
    institutionIds: List[String],
    affiliations: List[String]
)
object NormalizedAuthorship { implicit val rw: ReadWriter[NormalizedAuthorship] = macroRW }

/** Job contract `scholarchain.hadoop.normalized-source`, version 1: one enrichment record mapped to
  * canonical identifiers without losing source-specific IDs. Produced by J02, read by J04 only.
  *
  * Shared core records are emitted alongside where a contract exists (Institution, Topic). Core has
  * no contract for a non-dblp work, author or person, so this record stays inside the Hadoop stage
  * until domain-engineer adds one; it never goes to the export zone.
  *
  * @param kind
  *   `openalex-work`, `openalex-author`, `crossref-work` or `orcid-record`
  * @param sourceRecordId
  *   the source key: OpenAlex W or A ID, DOI (Crossref), ORCID iD (ORCID)
  * @param dois
  *   canonical DOIs asserted by the record (one for works; the DOI-linked works of an ORCID record)
  * @param abstractHash
  *   `sha256:` content hash of `abstractText`
  * @param rawSha256
  *   sha256 of the raw JSON line
  */
final case class NormalizedSource(
    normalizedVersion: Int,
    kind: String,
    sourceType: String,
    datasetId: String,
    snapshotId: String,
    sourceRecordId: String,
    dois: List[String],
    openAlexWorkId: Option[String],
    openAlexAuthorId: Option[String],
    openAlexSourceId: Option[String],
    orcid: Option[String],
    title: Option[String],
    year: Option[Int],
    workType: Option[String],
    venueLabel: Option[String],
    abstractText: Option[String],
    abstractHash: Option[String],
    topicIds: List[String],
    institutionIds: List[String],
    names: List[String],
    authorships: List[NormalizedAuthorship],
    rawSha256: String
)
object NormalizedSource {
  implicit val rw: ReadWriter[NormalizedSource] = macroRW
  val Version = 1
}

/** Result of normalizing one raw line. */
final case class Normalized(
    record: NormalizedSource,
    institutions: List[Institution],
    topics: List[Topic],
    issues: List[FieldIssue]
)

final case class SourceReject(reason: String, detail: String, sourceRecordId: Option[String])

/** One normalizer per selected enrichment dataset. Pure functions of (line, run context).
  *
  * Identifier rules are the core canonical forms (`Doi`, `Orcid`, `OpenAlexId`); an identifier that
  * does not normalise is dropped from the record and reported as a field issue, never repaired. A
  * missing or invalid record key rejects the line. Records of another entity type (for example an
  * author ID in a works file) are `unsupported_record`.
  */
object SourceNormalizers {

  val OpenAlexWork = "openalex-work"
  val OpenAlexAuthor = "openalex-author"
  val CrossrefWork = "crossref-work"
  val OrcidRecord = "orcid-record"
  val kinds: List[String] = List(OpenAlexWork, OpenAlexAuthor, CrossrefWork, OrcidRecord)

  def sourceTypeOf(kind: String): SourceType = kind match {
    case OpenAlexWork | OpenAlexAuthor => SourceType.OpenAlex
    case CrossrefWork                  => SourceType.Crossref
    case OrcidRecord                   => SourceType.Orcid
    case other                         => throw new IllegalArgumentException(s"unknown kind $other")
  }

  def normalize(
      kind: String,
      line: String,
      run: RunContext,
      keepCrossrefAbstracts: Boolean
  ): Either[SourceReject, Normalized] = {
    val json =
      try ujson.read(line)
      catch {
        case NonFatal(e) =>
          return Left(SourceReject(Reasons.MalformedJson, Option(e.getMessage).getOrElse(""), None))
      }
    val obj = json match {
      case o: ujson.Obj => o
      case _            => return Left(SourceReject(Reasons.NotAnObject, "top level", None))
    }
    val ctx = new Ctx(kind, run, Hashing.sha256Hex(line))
    try
      kind match {
        case OpenAlexWork   => openAlexWork(obj, ctx)
        case OpenAlexAuthor => openAlexAuthor(obj, ctx)
        case CrossrefWork   => crossrefWork(obj, ctx, keepCrossrefAbstracts)
        case OrcidRecord    => orcidRecord(obj, ctx)
      }
    catch {
      case e: ShapeError => Left(SourceReject(Reasons.SchemaFailure, e.getMessage, None))
    }
  }

  // ------------------------------------------------------------------------------------------

  private final class ShapeError(msg: String) extends RuntimeException(msg)

  private final class Ctx(val kind: String, val run: RunContext, val rawSha256: String) {
    val issues = List.newBuilder[FieldIssue]
    def issue(reason: String, detail: String): Unit = issues += FieldIssue(reason, detail)
    def base(sourceRecordId: String): NormalizedSource =
      NormalizedSource(
        NormalizedSource.Version,
        kind,
        sourceTypeOf(kind).wire,
        run.datasetId,
        run.snapshotId,
        sourceRecordId,
        Nil,
        None,
        None,
        None,
        None,
        None,
        None,
        None,
        None,
        None,
        None,
        Nil,
        Nil,
        Nil,
        Nil,
        rawSha256
      )
  }

  private def field(o: ujson.Obj, name: String): Option[ujson.Value] =
    o.value.get(name).filter(_ != ujson.Null)

  private def obj(o: ujson.Obj, name: String): Option[ujson.Obj] = field(o, name).map {
    case x: ujson.Obj => x
    case other        =>
      throw new ShapeError(s"$name: expected an object, found ${other.getClass.getSimpleName}")
  }

  private def arr(o: ujson.Obj, name: String): List[ujson.Value] = field(o, name) match {
    case None               => Nil
    case Some(a: ujson.Arr) => a.value.toList
    case Some(other)        => throw new ShapeError(s"$name: expected an array")
  }

  private def objs(o: ujson.Obj, name: String): List[ujson.Obj] = arr(o, name).collect {
    case x: ujson.Obj => x
  }

  private def text(v: ujson.Value): Option[String] = v match {
    case ujson.Str(s) => Some(s.replaceAll("(?U)\\s+", " ").trim).filter(_.nonEmpty)
    case o: ujson.Obj => o.value.get("value").flatMap(text) // ORCID {"value": ...} wrappers
    case _            => None
  }

  private def str(o: ujson.Obj, name: String): Option[String] = field(o, name).flatMap(text)

  private def int(o: ujson.Obj, name: String): Option[Int] = field(o, name).flatMap {
    case ujson.Num(n) if n.isWhole => Some(n.toInt)
    case ujson.Str(s)              => s.trim.toIntOption
    case o: ujson.Obj              => o.value.get("value").flatMap(text).flatMap(_.toIntOption)
    case _                         => None
  }

  private def canonical(
      ctx: Ctx,
      raw: Option[String],
      reason: String,
      f: String => Option[String]
  ): Option[String] =
    raw.flatMap { r =>
      val n = f(r)
      if (n.isEmpty) ctx.issue(reason, s"'$r'")
      n
    }

  private def key(
      ctx: Ctx,
      raw: Option[String],
      what: String,
      f: String => Option[String],
      otherEntity: String => Boolean
  ): Either[SourceReject, String] = raw match {
    case None    => Left(SourceReject(Reasons.MissingKey, s"no $what", None))
    case Some(r) =>
      f(r) match {
        case Some(k)                => Right(k)
        case None if otherEntity(r) =>
          Left(SourceReject(Reasons.UnsupportedRecord, s"'$r' is not a $what", Some(r)))
        case None => Left(SourceReject(Reasons.InvalidKey, s"'$r' is not a valid $what", Some(r)))
      }
  }

  private def isOpenAlexKey(r: String): Boolean =
    OpenAlexId.entities.exists(_.normalize(r).nonEmpty)

  private def institution(ctx: Ctx, o: ujson.Obj): Option[Institution] =
    canonical(ctx, str(o, "id"), Reasons.InvalidOpenAlexId, OpenAlexId.Institution.normalize)
      .flatMap { id =>
        str(o, "display_name").flatMap { name =>
          val cc = str(o, "country_code").flatMap { c =>
            if (c.matches("[A-Z]{2}")) Some(c)
            else { ctx.issue(Reasons.InvalidCountryCode, s"$id: '$c'"); None }
          }
          val inst = Institution(
            SchemaVersions.currentOf("Institution"),
            id,
            name,
            cc,
            ctx.run.provenance(SourceType.OpenAlex, List(id), None)
          )
          if (Contract.validate(inst).isEmpty) Some(inst) else None
        }
      }

  /** A topic object: its canonical ID (core `OpenAlexId.Topic`) and, when it has a display name,
    * its Topic record. A valid ID without a name stays in `topicIds` and is reported
    * (`topic_without_name`); an invalid ID is dropped and reported (`invalid_openalex_id`).
    */
  private def topic(ctx: Ctx, o: ujson.Obj): Option[(String, Option[Topic])] =
    canonical(ctx, str(o, "id"), Reasons.InvalidOpenAlexId, OpenAlexId.Topic.normalize).map { id =>
      val record = str(o, "display_name") match {
        case Some(name) =>
          Some(
            Topic(
              SchemaVersions.currentOf("Topic"),
              id,
              name,
              ctx.run.provenance(SourceType.OpenAlex, List(id), None)
            )
          )
        case None =>
          ctx.issue(Reasons.TopicWithoutName, s"'$id' has no display_name; no Topic record")
          None
      }
      id -> record
    }

  /** OpenAlex `abstract_inverted_index` (word -> positions) back to text. */
  def invertedAbstract(index: ujson.Obj): Option[String] = {
    val words = index.value.toList.flatMap { case (w, ps) =>
      ps match {
        case a: ujson.Arr =>
          a.value.toList.collect { case ujson.Num(p) if p.isWhole => p.toInt -> w }
        case _ => Nil
      }
    }
    if (words.isEmpty) None else Some(words.sortBy(_._1).map(_._2).mkString(" "))
  }

  private def withAbstract(n: NormalizedSource, text: Option[String]): NormalizedSource =
    n.copy(abstractText = text, abstractHash = text.map(ContentHash.of))

  private def openAlexWork(o: ujson.Obj, ctx: Ctx): Either[SourceReject, Normalized] =
    key(ctx, str(o, "id"), "OpenAlex work ID", OpenAlexId.Work.normalize, isOpenAlexKey).map { w =>
      val doi = canonical(ctx, str(o, "doi"), Reasons.InvalidDoi, Doi.normalize)
      val loc = obj(o, "primary_location")
      val src = loc.flatMap(obj(_, "source"))
      val sourceId = canonical(
        ctx,
        src.flatMap(str(_, "id")),
        Reasons.InvalidOpenAlexId,
        OpenAlexId.Source.normalize
      )
      val institutions = List.newBuilder[Institution]
      val authorships = objs(o, "authorships").zipWithIndex.map { case (a, i) =>
        val author = obj(a, "author")
        val insts = objs(a, "institutions")
        val instRecords = insts.flatMap(institution(ctx, _))
        institutions ++= instRecords
        NormalizedAuthorship(
          i,
          str(a, "author_position"),
          str(a, "raw_author_name"),
          author.flatMap(str(_, "display_name")),
          canonical(ctx, author.flatMap(str(_, "orcid")), Reasons.InvalidOrcid, Orcid.normalize),
          canonical(
            ctx,
            author.flatMap(str(_, "id")),
            Reasons.InvalidOpenAlexId,
            OpenAlexId.Author.normalize
          ),
          instRecords.map(_.institutionId).distinct,
          arr(a, "raw_affiliation_strings").flatMap(text)
        )
      }
      val topicEntries = (objs(o, "topics") ++ obj(o, "primary_topic").toList)
        .flatMap(topic(ctx, _))
      val topicIds = topicEntries.map(_._1).distinct
      val topics = topicEntries.flatMap(_._2).distinctBy(_.topicId)
      val abstractText = obj(o, "abstract_inverted_index").flatMap(invertedAbstract)
      val rec = withAbstract(ctx.base(w), abstractText).copy(
        dois = doi.toList,
        openAlexWorkId = Some(w),
        openAlexSourceId = sourceId,
        title = str(o, "display_name").orElse(str(o, "title")),
        year = int(o, "publication_year"),
        workType = str(o, "type"),
        venueLabel =
          src.flatMap(str(_, "display_name")).orElse(loc.flatMap(str(_, "raw_source_name"))),
        topicIds = topicIds,
        institutionIds = authorships.flatMap(_.institutionIds).distinct.sorted,
        names = authorships.flatMap(a => a.rawName.orElse(a.displayName)),
        authorships = authorships
      )
      Normalized(
        rec,
        institutions.result().distinctBy(_.institutionId),
        topics,
        ctx.issues.result()
      )
    }

  private def openAlexAuthor(o: ujson.Obj, ctx: Ctx): Either[SourceReject, Normalized] =
    key(ctx, str(o, "id"), "OpenAlex author ID", OpenAlexId.Author.normalize, isOpenAlexKey).map {
      a =>
        val orcid = canonical(ctx, str(o, "orcid"), Reasons.InvalidOrcid, Orcid.normalize)
        val insts = objs(o, "last_known_institutions").flatMap(institution(ctx, _))
        val names = (str(o, "display_name").toList ++
          arr(o, "display_name_alternatives").flatMap(text)).distinct
        val rec = ctx
          .base(a)
          .copy(
            openAlexAuthorId = Some(a),
            orcid = orcid,
            institutionIds = insts.map(_.institutionId).distinct.sorted,
            names = names
          )
        Normalized(rec, insts.distinctBy(_.institutionId), Nil, ctx.issues.result())
    }

  private def crossrefWork(
      o: ujson.Obj,
      ctx: Ctx,
      keepAbstracts: Boolean
  ): Either[SourceReject, Normalized] =
    key(ctx, str(o, "DOI"), "DOI", Doi.normalize, _ => false).map { doi =>
      val authors = objs(o, "author").zipWithIndex.map { case (a, i) =>
        val name = (str(a, "given").toList ++ str(a, "family").toList).mkString(" ")
        NormalizedAuthorship(
          i,
          str(a, "sequence"),
          Some(name).filter(_.nonEmpty).orElse(str(a, "name")),
          None,
          canonical(ctx, str(a, "ORCID"), Reasons.InvalidOrcid, Orcid.normalize),
          None,
          Nil,
          objs(a, "affiliation").flatMap(str(_, "name"))
        )
      }
      val year = obj(o, "issued")
        .flatMap(i => field(i, "date-parts"))
        .collect {
          case ujson.Arr(parts) if parts.nonEmpty =>
            parts.head match {
              case ujson.Arr(ys) if ys.nonEmpty =>
                ys.head match { case ujson.Num(y) if y.isWhole => Some(y.toInt); case _ => None }
              case _ => None
            }
        }
        .flatten
      val abs = str(o, "abstract")
      if (abs.nonEmpty && !keepAbstracts) ctx.issue(Reasons.DroppedAbstract, doi)
      val rec = withAbstract(ctx.base(doi), abs.filter(_ => keepAbstracts)).copy(
        dois = List(doi),
        title = arr(o, "title").flatMap(text).headOption,
        year = year,
        workType = str(o, "type"),
        venueLabel = arr(o, "container-title").flatMap(text).headOption,
        names = authors.flatMap(_.rawName),
        authorships = authors
      )
      Normalized(rec, Nil, Nil, ctx.issues.result())
    }

  private def orcidRecord(o: ujson.Obj, ctx: Ctx): Either[SourceReject, Normalized] =
    key(ctx, str(o, "orcid-path"), "ORCID iD", Orcid.normalize, _ => false).map { id =>
      val name = obj(o, "name")
      val givenNames = name.flatMap(str(_, "given-names"))
      val family = name.flatMap(str(_, "family-name"))
      val credit = name.flatMap(str(_, "credit-name"))
      val full = (givenNames.toList ++ family.toList).mkString(" ")
      val names = (credit.toList ++ List(full).filter(_.nonEmpty)).distinct
      def doisOf(container: ujson.Obj): List[String] =
        obj(container, "external-ids").toList
          .flatMap(objs(_, "external-id"))
          .filter { e =>
            str(e, "external-id-type").map(_.toLowerCase(Locale.ROOT)).contains("doi")
          }
          .flatMap(e =>
            canonical(ctx, str(e, "external-id-value"), Reasons.InvalidDoi, Doi.normalize)
          )
      val groups = objs(o, "works-group-projection")
      val dois = groups.flatMap(g => doisOf(g) ++ objs(g, "work-summary").flatMap(doisOf))
      val rec = ctx.base(id).copy(orcid = Some(id), dois = dois.distinct.sorted, names = names)
      Normalized(rec, Nil, Nil, ctx.issues.result().distinct)
    }
}
