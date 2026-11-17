package org.scholarchain.spark

import java.io.File

import scala.reflect.runtime.universe.TypeTag

import org.apache.hadoop.fs.Path
import org.apache.spark.sql.{Dataset, Encoders, SparkSession}
import org.apache.spark.sql.functions.{col, explode}
import org.scholarchain.core._

// Input rows: the fields of the decoded core records that the graph jobs read.

final case class WorkIn(
    workId: String,
    dblpKey: String,
    publicationType: String,
    title: String,
    year: Int,
    venueKey: Option[String],
    doi: Option[String]
)
final case class TeamIn(workId: String, year: Int, teamSize: Int)

/** One supporting position pair of a consolidated CoauthorPairCandidate. */
final case class PairIn(
    workId: String,
    year: Int,
    teamSize: Int,
    leftMentionId: String,
    rightMentionId: String
)
final case class VenueIn(venueKey: String, name: String)
final case class InstitutionIn(institutionId: String, name: String, countryCode: Option[String])
final case class LinkIn(
    linkId: String,
    subjectKind: String,
    subjectId: String,
    idType: String,
    externalId: String,
    sourceType: String,
    datasetId: String,
    snapshotId: String
)

/** An identity assignment (MatchDecision v2 `mention_researcher` without candidateId). */
final case class AssignmentIn(
    mentionId: String,
    researcherId: String,
    outcome: String,
    confidence: Double,
    reasonCodes: Seq[String]
)

/** Result of decoding one input line: accepted rows, or one reject. */
final case class Decoded[R](rows: Seq[R], reject: Option[RejectRow])

/** Decodes every input line with core `Contract.decode` (which validates). Invalid lines become
  * versioned [[RejectRow]]s; nothing is skipped silently.
  */
object Inputs {

  val MaxRawChars = 2000

  /** Input tables read from the Hadoop J09 export (never `match_candidate`). */
  val HadoopTables: List[String] =
    List(
      "work",
      "author_mention",
      "publication_team",
      "coauthor_pair_candidate",
      "venue",
      "institution",
      "external_id_link"
    )

  def reject(table: String, schema: String, line: String, reasons: List[String]): RejectRow = {
    val raw = if (line == null) "" else line
    RejectRow(
      table,
      schema,
      reasons,
      Hashing.sha256Hex(raw),
      raw.length,
      if (raw.length > MaxRawChars) raw.substring(0, MaxRawChars) else raw
    )
  }

  private def result[A, R](table: String, schema: String, line: String)(
      decoded: Either[ContractError, A]
  )(f: A => Either[List[String], Seq[R]]): Decoded[R] =
    decoded.left.map(_.messages).flatMap(f) match {
      case Right(rows)   => Decoded(rows, None)
      case Left(reasons) => Decoded(Nil, Some(reject(table, schema, line, reasons)))
    }

  def decodeWork(line: String): Decoded[WorkIn] =
    result("work", "WorkRecord", line)(Contract.decode[WorkRecord](line)) { w =>
      Right(
        Seq(
          WorkIn(w.workId.value, w.dblpKey, w.publicationType, w.title, w.year, w.venueKey, w.doi)
        )
      )
    }

  def decodeMention(line: String): Decoded[AuthorMentionRow] =
    result("author_mention", "AuthorMention", line)(Contract.decode[AuthorMention](line)) { m =>
      Right(Seq(AuthorMentionRow.fromCore(m)))
    }

  def decodeTeam(line: String): Decoded[TeamIn] =
    result("publication_team", "PublicationTeam", line)(Contract.decode[PublicationTeam](line)) {
      t => Right(Seq(TeamIn(t.workId.value, t.year, t.teamSize)))
    }

  def decodePair(line: String): Decoded[PairIn] =
    result("coauthor_pair_candidate", "CoauthorPairCandidate", line)(
      Contract.decode[CoauthorPairCandidate](line)
    ) { c =>
      Right(c.positions.map { p =>
        PairIn(c.workId.value, c.year, c.teamSize, p.leftMentionId.value, p.rightMentionId.value)
      })
    }

  def decodeVenue(line: String): Decoded[VenueIn] =
    result("venue", "Venue", line)(Contract.decode[Venue](line)) { v =>
      Right(Seq(VenueIn(v.venueKey, v.name)))
    }

  def decodeInstitution(line: String): Decoded[InstitutionIn] =
    result("institution", "Institution", line)(Contract.decode[Institution](line)) { i =>
      Right(Seq(InstitutionIn(i.institutionId, i.name, i.countryCode)))
    }

  def decodeLink(line: String): Decoded[LinkIn] =
    result("external_id_link", "ExternalIdLink", line)(Contract.decode[ExternalIdLink](line)) { l =>
      Right(
        Seq(
          LinkIn(
            l.linkId.value,
            l.subjectKind.wire,
            l.subjectId,
            l.idType.wire,
            l.externalId,
            l.provenance.sourceType.wire,
            l.provenance.datasetId,
            l.provenance.snapshotId
          )
        )
      )
    }

  def decodeAssignment(line: String): Decoded[AssignmentIn] =
    result("identity_assignment", "MatchDecision", line)(Contract.decode[MatchDecision](line)) {
      d =>
        if (d.subjectKind != MatchSubjectKind.MentionResearcher || d.candidateId.nonEmpty)
          Left(
            List(
              "not an identity assignment: expected subjectKind mention_researcher without " +
                s"candidateId (got ${d.subjectKind.wire}, candidateId ${d.candidateId.map(_.value)})"
            )
          )
        else
          Right(Seq(AssignmentIn(d.left, d.right, d.outcome.wire, d.confidence, d.reasonCodes)))
    }

  /** Every regular, non-hidden file under `dir` (what Spark reads), sorted by relative path. */
  def dataFiles(dir: File): List[File] = {
    def walk(f: File): List[File] =
      if (f.getName.startsWith("_") || f.getName.startsWith(".")) Nil
      else if (f.isDirectory) Option(f.listFiles).toList.flatten.flatMap(walk)
      else if (f.isFile) List(f)
      else Nil
    if (!dir.isDirectory) Nil
    else Option(dir.listFiles).toList.flatten.flatMap(walk).sortBy(_.getPath)
  }

  /** Decodes the text files under `dir` (recursively; hidden `_`/`.` files are not read). A missing
    * directory yields no rows (recorded as absent in the run manifest).
    */
  def decodeDir[R <: Product: TypeTag](
      spark: SparkSession,
      io: RunFs,
      dir: Path,
      decode: String => Decoded[R]
  ): Dataset[Decoded[R]] = {
    import spark.implicits._
    if (io.listData(dir).isEmpty) spark.emptyDataset[Decoded[R]]
    else
      spark.read
        .option("recursiveFileLookup", "true")
        .text(dir.toString)
        .as[String](Encoders.STRING)
        .map(decode)
  }

  /** Splits a staged decode result into accepted rows and rejects. */
  def accepted[R <: Product: TypeTag](
      spark: SparkSession,
      staged: Dataset[Decoded[R]]
  ): Dataset[R] = {
    import spark.implicits._
    staged.select(explode(col("rows")).as("r")).select("r.*").as[R]
  }

  def rejects(spark: SparkSession, staged: Dataset[_]): Dataset[RejectRow] = {
    import spark.implicits._
    staged.where(col("reject").isNotNull).select("reject.*").as[RejectRow]
  }
}
