package org.scholarchain.rag.semantic

import org.scholarchain.core._
import org.scholarchain.core.config.VectorSettings

/** One author slot of a paper: dblp author position and the resolved researcher. */
final case class PaperAuthor(position: Int, researcherId: ResearcherId)

/** The exported records behind one paper summary. */
final case class PaperInputs(
    work: GraphNode,
    publishedInVenue: Option[String],
    venueName: Option[String],
    authors: List[PaperAuthor],
    displayNames: scala.collection.Map[String, String]
)

/** A rendered paper summary and the filterable extras storage puts in its metadata. */
final case class RenderedPaper(documents: Vector[SemanticDocument], year: Int, venueKey: String)

/** `paper-abstract-v1`: compact, deterministic paper summaries from dblp data only (abstracts are
  * not collected).
  *
  * Text:
  * {{{
  * Paper: <title>. Work <workId>. Venue: <venue name> (<venueKey>). Year: <year>.
  * Authors: <name>; <name>; ... . No abstract is stored for this paper.
  * }}}
  * With more than 10 distinct authors the list reads `Authors (first 10 of <n>): ...`; with none,
  * `Authors: none recorded.`
  *
  * Inputs: the work node (title = `displayName`, `properties.year`, `properties.venueKey`), its
  * `published_in` edge (must name the same venue), the venue node (name), authorship rows (ordered
  * by position; a researcher holding two positions is listed once, at the first) and canonical
  * researcher display names. Whitespace in titles and names is collapsed to single spaces and one
  * trailing period is dropped from the title. Titles and names are data, never instructions; they
  * are rendered as plain text and nothing in them changes the rendering.
  *
  * Anything missing or inconsistent (blank title, missing or invalid year, missing venue key, no or
  * disagreeing published_in edge, no venue node, an author without a canonical name) rejects the
  * paper with a reason; nothing is defaulted.
  *
  * Document: subjectIds = `[workId]`, no window (the year is metadata), vector key `pp:<workId>`;
  * provenance: dataset, snapshot and run of the work node, sourceRecordIds = the work ID followed
  * by the listed authors' researcher IDs, job [[JobName]], policy [[Version]].
  */
object PaperSummaryRenderer {
  val Version = "paper-abstract-v1"
  val JobName = "rag.render-paper-summary"
  val MaxAuthors = 10
  val Closing = "No abstract is stored for this paper."

  private def clean(s: String): String = s.replaceAll("\\s+", " ").trim

  def render(in: PaperInputs): Either[String, (String, Int, String, List[ResearcherId])] = {
    val w = in.work
    val title = clean(Option(w.displayName).getOrElse("")).stripSuffix(".").trim
    for {
      _ <- Either.cond(w.label == GraphNodeLabel.Work, (), s"node ${w.nodeId} is not a work")
      _ <- Either.cond(title.nonEmpty, (), s"${w.nodeId}: no title")
      year <- w.properties
        .get("year")
        .flatMap(_.trim.toIntOption)
        .filter(y => y >= 1 && y <= 9999)
        .toRight(s"${w.nodeId}: no valid year")
      venueKey <- w.properties
        .get("venueKey")
        .map(_.trim)
        .filter(VenueKey.isValid)
        .toRight(s"${w.nodeId}: no valid venueKey")
      _ <- in.publishedInVenue match {
        case Some(v) if v == venueKey => Right(())
        case Some(v)                  =>
          Left(s"${w.nodeId}: published_in names venue '$v' but the work says '$venueKey'")
        case None => Left(s"${w.nodeId}: no published_in edge")
      }
      venueName <- in.venueName
        .map(clean)
        .filter(_.nonEmpty)
        .toRight(s"${w.nodeId}: no venue node for '$venueKey'")
      distinct = in.authors
        .sortBy(a => (a.position, a.researcherId.value))
        .distinctBy(_.researcherId)
      listed = distinct.take(MaxAuthors)
      names <- listed.foldLeft[Either[String, List[String]]](Right(Nil)) { (acc, a) =>
        acc.flatMap { ns =>
          in.displayNames
            .get(a.researcherId.value)
            .map(clean)
            .filter(_.nonEmpty)
            .toRight(s"${w.nodeId}: author ${a.researcherId.value} has no canonical display name")
            .map(ns :+ _)
        }
      }
    } yield {
      val authorsPart =
        if (names.isEmpty) "Authors: none recorded."
        else if (distinct.size > MaxAuthors)
          s"Authors (first $MaxAuthors of ${distinct.size}): ${names.mkString("; ")}."
        else s"Authors: ${names.mkString("; ")}."
      val text =
        s"Paper: $title. Work ${w.nodeId}. Venue: $venueName ($venueKey). Year: $year. " +
          s"$authorsPart $Closing"
      (text, year, venueKey, listed.map(_.researcherId))
    }
  }

  def document(in: PaperInputs, vectors: VectorSettings): Either[String, RenderedPaper] =
    for {
      r <- render(in)
      (text, year, venueKey, authorIds) = r
      p = in.work.provenance
      prov = Provenance(
        SchemaVersions.currentOf("Provenance"),
        SourceType.Pipeline,
        p.datasetId,
        p.snapshotId,
        in.work.nodeId :: authorIds.map(_.value),
        p.runId,
        JobName,
        Version,
        None
      )
      docs <- TemplateRegistry.build(
        TemplateRegistry.PaperAbstract,
        List(in.work.nodeId),
        None,
        None,
        text,
        None,
        prov,
        vectors
      )
    } yield RenderedPaper(docs, year, venueKey)
}
