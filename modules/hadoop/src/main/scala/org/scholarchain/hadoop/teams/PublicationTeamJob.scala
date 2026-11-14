package org.scholarchain.hadoop.teams

import scala.jdk.CollectionConverters.*

import org.apache.hadoop.fs.Path
import org.apache.hadoop.io.{LongWritable, NullWritable, Text}
import org.apache.hadoop.mapreduce.lib.input.MultipleInputs
import org.apache.hadoop.mapreduce.{Job, Mapper, Reducer}
import org.scholarchain.core.{AuthorMention, Contract, WorkRecord}
import org.scholarchain.hadoop.common.*
import org.scholarchain.hadoop.io.{BoundedLineInputFormat, RawRecord}
import upickle.default.{read, write}

/** J07: publication teams, author-paper records and mention contexts.
  *
  * Map in: J01 `works/` (WorkRecord JSON lines) and `mentions/` (AuthorMention JSON lines). Map
  * out: `<work ID>` -> `W` + WorkRecord JSON or `M` + AuthorMention JSON. Reduce (per work ID, see
  * [[TeamAssembler]]): `teams/` (PublicationTeam), `authorPapers/` (AuthorMention keyed by mention
  * and work, ordered by position), `mentionContexts/` (internal [[MentionContext]]),
  * `oversizeTeams/` (teams above the oversize threshold). Stats: maxTeamSize, maxTeamPairs.
  */
object PublicationTeamJob {
  val JobName = "hadoop.j07-publication-teams"
  val Teams = "teams"
  val AuthorPapers = "authorPapers"
  val Contexts = "mentionContexts"
  val Oversize = "oversizeTeams"

  def spec(
      works: Path,
      mentions: Path,
      outputRoot: Path,
      snapshotId: String,
      inputFingerprint: String
  ): JobSpec =
    JobSpec(
      JobName,
      "dblp",
      snapshotId,
      Settings.Keys.DblpParsePolicy,
      inputFingerprint,
      outputRoot,
      s"dblp/snapshot=$snapshotId",
      Settings.Keys.TeamReducers,
      List(Teams, AuthorPapers, Contexts, Oversize),
      { (job: Job) =>
        job.getConfiguration
          .set(BoundedLineInputFormat.LimitKeyKey, Settings.Keys.InternalMaxLineBytes)
        MultipleInputs.addInputPath(
          job,
          works,
          classOf[BoundedLineInputFormat],
          classOf[TeamWorkMapper]
        )
        MultipleInputs.addInputPath(
          job,
          mentions,
          classOf[BoundedLineInputFormat],
          classOf[TeamMentionMapper]
        )
        job.setReducerClass(classOf[PublicationTeamReducer])
      }
    )
}

/** Decodes upstream JSON lines through the core contract; failures are rejected and counted. */
abstract class ContractLineMapper extends Mapper[LongWritable, RawRecord, Text, Text] {
  type Ctx = Mapper[LongWritable, RawRecord, Text, Text]#Context
  protected var io: TaskIO = null
  protected val outKey = new Text()
  protected val outValue = new Text()

  override def setup(ctx: Ctx): Unit = io = new TaskIO(ctx, reduceStage = false)
  override def cleanup(ctx: Ctx): Unit = io.close()

  /** Handles one complete line. */
  protected def line(text: String, location: String, ctx: Ctx): Unit

  override def map(k: LongWritable, rec: RawRecord, ctx: Ctx): Unit = {
    io.count(Counters.Input)
    val text = new String(rec.payload, java.nio.charset.StandardCharsets.UTF_8)
    rec.status match {
      case RawRecord.Ok if text.trim.isEmpty =>
        io.reject(Reasons.BlankLine, "empty line", None, Some(rec.location), None)
      case RawRecord.Ok       => line(text, rec.location, ctx)
      case RawRecord.Oversize =>
        io.reject(Reasons.OversizeLine, rec.detail, None, Some(rec.location), Some(text))
      case RawRecord.DecompressionLimit =>
        io.reject(Reasons.DecompressionLimit, rec.detail, None, Some(rec.location), None)
      case _ => io.reject(Reasons.TruncatedInput, rec.detail, None, Some(rec.location), None)
    }
  }

  protected def emit(key: String, value: String, ctx: Ctx): Unit = {
    outKey.set(key)
    outValue.set(value)
    ctx.write(outKey, outValue)
  }
}

class TeamWorkMapper extends ContractLineMapper {
  protected def line(text: String, location: String, ctx: Ctx): Unit =
    Contract.decode[WorkRecord](text) match {
      case Left(e)  => io.reject(Reasons.SchemaFailure, e.message, None, Some(location), Some(text))
      case Right(w) =>
        io.count(Counters.Accepted)
        emit(w.workId.value, "W" + text, ctx)
    }
}

class TeamMentionMapper extends ContractLineMapper {
  protected def line(text: String, location: String, ctx: Ctx): Unit =
    Contract.decode[AuthorMention](text) match {
      case Left(e)  => io.reject(Reasons.SchemaFailure, e.message, None, Some(location), Some(text))
      case Right(m) =>
        io.count(Counters.Accepted)
        emit(m.workId.value, "M" + text, ctx)
    }
}

class PublicationTeamReducer extends Reducer[Text, Text, NullWritable, Text] {
  type Ctx = Reducer[Text, Text, NullWritable, Text]#Context
  private var io: TaskIO = null
  private var limits: TeamLimits = null
  private val maxTeam = new MaxTracker("maxTeamSize")
  private val maxPairs = new MaxTracker("maxTeamPairs")

  override def setup(ctx: Ctx): Unit = {
    io = new TaskIO(ctx, reduceStage = true)
    val s = new Settings(ctx.getConfiguration)
    limits = TeamLimits(
      s.positiveInt(Settings.Keys.TeamOversize),
      s.positiveInt(Settings.Keys.TeamHardLimit),
      s.positiveInt(Settings.Keys.TeamMaxCoauthorContext)
    )
  }

  override def reduce(key: Text, values: java.lang.Iterable[Text], ctx: Ctx): Unit = {
    val works = List.newBuilder[WorkRecord]
    val mentions = List.newBuilder[AuthorMention]
    var mentionCount = 0L
    var overflow = false
    values.asScala.foreach { v =>
      val s = v.toString
      if (s.startsWith("W")) works += read[WorkRecord](s.substring(1))
      else {
        mentionCount += 1
        // Bounded memory: stop buffering past the hard limit; the team is rejected anyway.
        if (mentionCount <= limits.hardLimit.toLong + 1)
          mentions += read[AuthorMention](s.substring(1))
        else overflow = true
      }
    }
    val ws = works.result()
    val ms = mentions.result()
    val dups = ms.size - ms.distinct.size
    if (dups > 0) io.count(Counters.Duplicate, dups.toLong)
    val result =
      if (overflow)
        Left(
          TeamAssembler.Rejection(
            Reasons.TeamExceedsHardLimit,
            s"$mentionCount mentions > ${limits.hardLimit}"
          )
        )
      else TeamAssembler.assemble(ws, ms, limits, io.run)
    result match {
      case Left(r) =>
        if (r.reason == Reasons.TeamExceedsHardLimit) io.count(Counters.OversizeTeam)
        if (r.reason == Reasons.DuplicateKeyConflict) io.count(Counters.Conflict)
        io.reject(
          r.reason,
          r.detail,
          ws.headOption.map(_.dblpKey).orElse(Some(key.toString)),
          None,
          None
        )
      case Right(t) =>
        val n = t.team.teamSize.toLong
        maxTeam.offer(n, t.team.workId.value)
        maxPairs.offer(n * (n - 1) / 2, t.team.workId.value)
        io.emit(PublicationTeamJob.Teams, PublicationTeamJob.Teams, write(t.team))
        t.authorPapers.foreach(p =>
          io.emit(PublicationTeamJob.AuthorPapers, PublicationTeamJob.AuthorPapers, write(p))
        )
        t.contexts.foreach(c =>
          io.emit(PublicationTeamJob.Contexts, PublicationTeamJob.Contexts, write(c))
        )
        if (t.oversize) {
          io.count(Counters.OversizeTeam)
          val report = ujson.Obj(
            "workId" -> t.team.workId.value,
            "dblpKey" -> t.team.provenance.sourceRecordIds.head,
            "teamSize" -> n.toDouble,
            "pairs" -> (n * (n - 1) / 2).toDouble,
            "year" -> t.team.year.toDouble
          )
          io.emit(PublicationTeamJob.Oversize, PublicationTeamJob.Oversize, report.render())
        }
    }
  }

  override def cleanup(ctx: Ctx): Unit = {
    maxTeam.flush(io)
    maxPairs.flush(io)
    io.close()
  }
}
