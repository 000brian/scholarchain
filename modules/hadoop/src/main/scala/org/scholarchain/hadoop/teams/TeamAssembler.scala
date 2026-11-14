package org.scholarchain.hadoop.teams

import org.scholarchain.core.{
  AuthorMention,
  Contract,
  PublicationTeam,
  SchemaVersions,
  SourceType,
  WorkRecord
}
import org.scholarchain.hadoop.common.{Reasons, RunContext}
import upickle.default.{macroRW, ReadWriter}

/** Job contract `scholarchain.hadoop.mention-context`, version 1: one author mention with the work
  * facts that blocking and exact-identifier joins need. Produced by J07, read by J04 and J05 only;
  * it is not a curated or exported record.
  */
final case class MentionContext(
    contextVersion: Int,
    mentionId: String,
    workId: String,
    dblpKey: String,
    position: Int,
    name: String,
    orcid: Option[String],
    year: Int,
    venueKey: Option[String],
    doi: Option[String],
    title: String,
    teamSize: Int,
    coauthorNames: List[String],
    coauthorsTruncated: Boolean,
    datasetId: String,
    snapshotId: String
)
object MentionContext {
  implicit val rw: ReadWriter[MentionContext] = macroRW
  val Version = 1
}

final case class TeamLimits(oversizeThreshold: Int, hardLimit: Int, maxCoauthorContext: Int)

/** Outputs for one retained work. */
final case class AssembledTeam(
    team: PublicationTeam,
    authorPapers: List[AuthorMention],
    contexts: List[MentionContext],
    oversize: Boolean
)

/** J07 reduce logic for one work ID: the work record and its mentions, checked against each other.
  *
  * Rules: exactly one distinct work; mentions must cover positions `0 until teamSize` exactly once
  * with the work's names in order (`team_mention_mismatch` otherwise); mentions without a work are
  * `orphan_mention`; teams larger than `hardLimit` are rejected. Identical duplicate mentions are
  * collapsed (the caller counts them).
  */
object TeamAssembler {

  final case class Rejection(reason: String, detail: String)

  def assemble(
      works: List[WorkRecord],
      mentions: List[AuthorMention],
      limits: TeamLimits,
      run: RunContext
  ): Either[Rejection, AssembledTeam] = {
    val distinctWorks = works.distinct
    if (distinctWorks.isEmpty)
      return Left(Rejection(Reasons.OrphanMention, s"${mentions.size} mention(s) without a work"))
    if (distinctWorks.size > 1)
      return Left(Rejection(Reasons.DuplicateKeyConflict, s"${distinctWorks.size} different works"))
    val w = distinctWorks.head
    val n = w.authorNames.size
    if (n > limits.hardLimit)
      return Left(Rejection(Reasons.TeamExceedsHardLimit, s"team of $n > ${limits.hardLimit}"))
    val ms = mentions.distinct.sortBy(_.position)
    val positionsOk = ms.map(_.position) == (0 until n).toList
    val namesOk = positionsOk && ms.map(_.name) == w.authorNames
    if (!namesOk)
      return Left(
        Rejection(
          Reasons.TeamMentionMismatch,
          s"work has $n authors; mentions cover positions ${ms.map(_.position).mkString(",")}"
        )
      )

    val prov = run.provenance(
      SourceType.Dblp,
      List(w.dblpKey),
      w.provenance.contentHash,
      w.provenance.datasetId,
      w.provenance.snapshotId
    )
    val team = PublicationTeam(
      SchemaVersions.currentOf("PublicationTeam"),
      w.workId,
      w.year,
      w.authorNames,
      n,
      prov
    )
    val papers = ms.map(_.copy(provenance = prov))
    val contexts = ms.map { m =>
      val others = w.authorNames.zipWithIndex.collect { case (a, i) if i != m.position => a }
      MentionContext(
        MentionContext.Version,
        m.mentionId.value,
        w.workId.value,
        w.dblpKey,
        m.position,
        m.name,
        m.orcid,
        w.year,
        w.venueKey,
        w.doi,
        w.title,
        n,
        others.take(limits.maxCoauthorContext),
        others.size > limits.maxCoauthorContext,
        w.provenance.datasetId,
        w.provenance.snapshotId
      )
    }
    val errors = Contract.validate(team) ++ papers.flatMap(p => Contract.validate(p))
    if (errors.nonEmpty) Left(Rejection(Reasons.SchemaFailure, errors.mkString("; ")))
    else Right(AssembledTeam(team, papers, contexts, n > limits.oversizeThreshold))
  }
}
