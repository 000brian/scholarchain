package org.scholarchain.hadoop.graphexport

import scala.jdk.CollectionConverters.*

import org.apache.hadoop.fs.Path
import org.apache.hadoop.io.{NullWritable, Text}
import org.apache.hadoop.mapreduce.lib.input.MultipleInputs
import org.apache.hadoop.mapreduce.{Job, Mapper, Reducer}
import org.scholarchain.core.*
import org.scholarchain.hadoop.common.*
import org.scholarchain.hadoop.io.BoundedLineInputFormat
import org.scholarchain.hadoop.teams.ContractLineMapper
import upickle.default.{read, write, ReadWriter}

/** An exported entity: its directory name, record ID and optional partition year. */
abstract class ExportEntity[A](val name: String)(implicit
    val rw: ReadWriter[A],
    val v: Validator[A]
) {
  def id(a: A): String
  def year(a: A): Option[Int]
}

object ExportEntity {
  object Work extends ExportEntity[WorkRecord]("work") {
    def id(a: WorkRecord) = a.workId.value
    def year(a: WorkRecord) = Some(a.year)
  }
  object Mention extends ExportEntity[AuthorMention]("author_mention") {
    def id(a: AuthorMention) = a.mentionId.value
    def year(a: AuthorMention) = None
  }
  object Team extends ExportEntity[PublicationTeam]("publication_team") {
    def id(a: PublicationTeam) = a.workId.value
    def year(a: PublicationTeam) = Some(a.year)
  }
  object Pair extends ExportEntity[CoauthorPairCandidate]("coauthor_pair_candidate") {
    def id(a: CoauthorPairCandidate) = s"${a.workId.value}\u0001${a.left}\u0001${a.right}"
    def year(a: CoauthorPairCandidate) = Some(a.year)
  }
  object Link extends ExportEntity[ExternalIdLink]("external_id_link") {
    def id(a: ExternalIdLink) = a.linkId.value
    def year(a: ExternalIdLink) = None
  }
  object Inst extends ExportEntity[Institution]("institution") {
    def id(a: Institution) = a.institutionId
    def year(a: Institution) = None
  }
  object TopicE extends ExportEntity[Topic]("topic") {
    def id(a: Topic) = a.topicId
    def year(a: Topic) = None
  }
  object VenueE extends ExportEntity[Venue]("venue") {
    def id(a: Venue) = a.venueKey
    def year(a: Venue) = None
  }

  object Candidate extends ExportEntity[MatchCandidate]("match_candidate") {
    def id(a: MatchCandidate) = a.candidateId.value
    def year(a: MatchCandidate) = None
  }

  val all: List[ExportEntity[?]] =
    List(Work, Mention, Team, Pair, Link, Inst, TopicE, VenueE, Candidate)
  def byName(n: String): ExportEntity[?] =
    all.find(_.name == n).getOrElse(throw new IllegalArgumentException(s"unknown export entity $n"))
}

/** J09: graph export preparation for Spark.
  *
  * Map in: curated outputs of J01 (works, venues), J07 (authorPapers, teams), J08b (pairs), J04
  * (links), J02 (institutions, topics) and J06 (match candidates, for entity resolution), each
  * decoded and validated through the core contract (failures: SCHEMA_FAILURE_RECORDS). Map out:
  * `<entity>\t<year or empty>\t<record ID>` -> JSON. Reduce: one record per ID; identical copies
  * collapse (DUPLICATE); ExternalIdLinks with the same link ID merge their source record IDs (the
  * same venue link is asserted by many DOI groups); any other disagreement is rejected (CONFLICT).
  *
  * Partition strategy (explicit): files go to `<entity>/year=<YYYY>/` for year-bearing entities
  * (work, publication_team, coauthor_pair_candidate) and `<entity>/` otherwise; within that, the
  * stable hash of the map key picks one of `scholarchain.export.reducers` part files.
  */
object GraphExportJob {
  val JobName = "hadoop.j09-graph-export"
  val Exported = "exported"
  val EntityKey = "scholarchain.run.exportEntity"

  def spec(
      inputs: List[(ExportEntity[?], Path)],
      outputRoot: Path,
      scope: String,
      snapshotLabel: String,
      inputFingerprint: String
  ): JobSpec =
    JobSpec(
      JobName,
      "scholarchain-hadoop",
      snapshotLabel,
      Settings.Keys.ExportPolicy,
      inputFingerprint,
      outputRoot,
      scope,
      Settings.Keys.ExportReducers,
      List(Exported),
      { (job: Job) =>
        job.getConfiguration
          .set(BoundedLineInputFormat.LimitKeyKey, Settings.Keys.InternalMaxLineBytes)
        inputs.foreach { case (e, p) =>
          MultipleInputs.addInputPath(job, p, classOf[BoundedLineInputFormat], mapperFor(e))
        }
        job.setReducerClass(classOf[GraphExportReducer])
      }
    )

  private def mapperFor(e: ExportEntity[?]): Class[? <: Mapper[?, ?, ?, ?]] = e match {
    case ExportEntity.Work      => classOf[WorkExportMapper]
    case ExportEntity.Mention   => classOf[MentionExportMapper]
    case ExportEntity.Team      => classOf[TeamExportMapper]
    case ExportEntity.Pair      => classOf[PairExportMapper]
    case ExportEntity.Link      => classOf[LinkExportMapper]
    case ExportEntity.Inst      => classOf[InstitutionExportMapper]
    case ExportEntity.TopicE    => classOf[TopicExportMapper]
    case ExportEntity.Candidate => classOf[CandidateExportMapper]
    case _                      => classOf[VenueExportMapper]
  }

  /** Entities that several jobs or identifier groups may assert: their copies merge when they agree
    * on every fact.
    */
  val mergeable: Set[String] =
    Set(ExportEntity.Link.name, ExportEntity.Inst.name, ExportEntity.TopicE.name)

  /** Merges copies of one record that differ only in provenance `runId`, `jobName` and
    * `sourceRecordIds` (for example an institution normalized from both OpenAlex works and OpenAlex
    * authors of the same snapshot, or a venue link asserted by several DOI groups).
    *
    * The merged record keeps the provenance of the copy with the smallest (jobName, runId) by code
    * point and the union of all source record IDs, sorted by code point. Copies that disagree on
    * any other field (dataset, snapshot, policy, values) are not merged.
    */
  def mergeCopies(copies: List[String]): Either[String, String] = {
    val parsed = copies.map(ujson.read(_))
    def facts(v: ujson.Value): ujson.Value = {
      val c = ujson.copy(v)
      val p = c("provenance").obj
      p.remove("runId"); p.remove("jobName"); p.remove("sourceRecordIds")
      c
    }
    val distinctFacts = parsed.map(facts).distinct
    if (distinctFacts.size != 1) Left(s"${copies.size} copies disagree on record facts")
    else {
      def rank(v: ujson.Value) = (v("provenance")("jobName").str, v("provenance")("runId").str)
      val keep = ujson.copy(parsed.sortWith { (a, b) =>
        val (ja, ra) = rank(a)
        val (jb, rb) = rank(b)
        val c = CodePointOrder.compare(ja, jb)
        c < 0 || (c == 0 && CodePointOrder.lessThan(ra, rb))
      }.head)
      val ids = parsed
        .flatMap(_("provenance")("sourceRecordIds").arr.map(_.str))
        .distinct
        .sorted(CodePointOrder.ordering)
      keep("provenance")("sourceRecordIds") = ujson.Arr.from(ids.map(ujson.Str(_)))
      Right(keep.render())
    }
  }
}

abstract class ExportMapper[A](entity: ExportEntity[A]) extends ContractLineMapper {
  protected def line(text: String, location: String, ctx: Ctx): Unit =
    Contract.decode[A](text)(entity.rw, entity.v) match {
      case Left(e)  => io.reject(Reasons.SchemaFailure, e.message, None, Some(location), Some(text))
      case Right(a) =>
        io.count(Counters.Accepted)
        emit(s"${entity.name}\t${entity.year(a).fold("")(_.toString)}\t${entity.id(a)}", text, ctx)
    }
}

class WorkExportMapper extends ExportMapper(ExportEntity.Work)
class MentionExportMapper extends ExportMapper(ExportEntity.Mention)
class TeamExportMapper extends ExportMapper(ExportEntity.Team)
class PairExportMapper extends ExportMapper(ExportEntity.Pair)
class LinkExportMapper extends ExportMapper(ExportEntity.Link)
class InstitutionExportMapper extends ExportMapper(ExportEntity.Inst)
class TopicExportMapper extends ExportMapper(ExportEntity.TopicE)
class VenueExportMapper extends ExportMapper(ExportEntity.VenueE)
class CandidateExportMapper extends ExportMapper(ExportEntity.Candidate)

class GraphExportReducer extends Reducer[Text, Text, NullWritable, Text] {
  type Ctx = Reducer[Text, Text, NullWritable, Text]#Context
  private var io: TaskIO = null

  override def setup(ctx: Ctx): Unit = io = new TaskIO(ctx, reduceStage = true)

  override def reduce(key: Text, values: java.lang.Iterable[Text], ctx: Ctx): Unit = {
    val Array(entity, year, id) = key.toString.split("\t", 3)
    val dir = if (year.isEmpty) entity else s"$entity/year=$year"
    val all = values.asScala.map(_.toString).toList
    val distinct = all.distinct.sorted
    if (all.size > 1) io.count(Counters.Duplicate, all.size - distinct.size.toLong)
    val merged: Either[String, String] =
      if (distinct.size == 1) Right(distinct.head)
      else if (GraphExportJob.mergeable(entity))
        GraphExportJob
          .mergeCopies(distinct)
          .flatMap(json => revalidate(entity, json))
      else Left(s"${distinct.size} different records share one ID")
    merged match {
      case Right(json) =>
        if (distinct.size > 1) io.count(Counters.Duplicate, distinct.size - 1L)
        io.emit(GraphExportJob.Exported, dir, json)
      case Left(err) =>
        io.count(Counters.Conflict, distinct.size.toLong)
        distinct.foreach(v => io.reject(Reasons.DuplicateKeyConflict, err, Some(id), None, Some(v)))
    }
  }

  private def revalidate(entity: String, json: String): Either[String, String] = {
    val e = ExportEntity.byName(entity)
    def check[A](x: ExportEntity[A]): Either[String, String] =
      Contract
        .decode[A](json)(x.rw, x.v)
        .left
        .map(_.message)
        .flatMap(a => Contract.encode(a)(x.rw, x.v).left.map(_.message))
    check(e)
  }

  override def cleanup(ctx: Ctx): Unit = io.close()
}
