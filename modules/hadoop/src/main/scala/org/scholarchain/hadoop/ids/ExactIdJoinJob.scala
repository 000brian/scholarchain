package org.scholarchain.hadoop.ids

import scala.jdk.CollectionConverters.*

import org.apache.hadoop.fs.Path
import org.apache.hadoop.io.{NullWritable, Text}
import org.apache.hadoop.mapreduce.lib.input.MultipleInputs
import org.apache.hadoop.mapreduce.{Job, Reducer}
import org.scholarchain.hadoop.common.*
import org.scholarchain.hadoop.io.BoundedLineInputFormat
import org.scholarchain.hadoop.sources.NormalizedSource
import org.scholarchain.hadoop.teams.{ContractLineMapper, MentionContext}
import upickle.default.{read, write}

/** J03/J04: exact identifier extraction and join.
  *
  * Map in: J07 `mentionContexts/` and J02 `normalized/` directories. Map out (J03 keys,
  * [[IdentifierKeys]]): `doi\t<doi>` -> `C` + MentionContext JSON or `S` + NormalizedSource JSON.
  * Records without a DOI key count as MISSING_KEY_RECORDS (accepted, not joined). Reduce:
  * [[ExactLinker]] per DOI group, bounded by `scholarchain.idjoin.maxGroupSize`. Outputs: `links/`
  * (ExternalIdLink), `conflicts/v1/` (unjoined ambiguous groups). Counters: LINKS_EMITTED,
  * UNMATCHED_KEYS, CONFLICT_RECORDS, OVERSIZE_BLOCKS.
  */
object ExactIdJoinJob {
  val JobName = "hadoop.j04-exact-id-join"
  val Links = "links"

  def spec(
      contexts: List[Path],
      normalized: List[Path],
      outputRoot: Path,
      scope: String,
      snapshotLabel: String,
      inputFingerprint: String
  ): JobSpec =
    JobSpec(
      JobName,
      "scholarchain-hadoop",
      snapshotLabel,
      Settings.Keys.IdJoinPolicy,
      inputFingerprint,
      outputRoot,
      scope,
      Settings.Keys.IdJoinReducers,
      List(Links),
      { (job: Job) =>
        job.getConfiguration
          .set(BoundedLineInputFormat.LimitKeyKey, Settings.Keys.InternalMaxLineBytes)
        contexts.foreach(p =>
          MultipleInputs.addInputPath(
            job,
            p,
            classOf[BoundedLineInputFormat],
            classOf[ContextJoinMapper]
          )
        )
        normalized.foreach(p =>
          MultipleInputs.addInputPath(
            job,
            p,
            classOf[BoundedLineInputFormat],
            classOf[SourceJoinMapper]
          )
        )
        job.setReducerClass(classOf[ExactIdJoinReducer])
      }
    )
}

class ContextJoinMapper extends ContractLineMapper {
  protected def line(text: String, location: String, ctx: Ctx): Unit =
    scala.util.Try(read[MentionContext](text)).toEither match {
      case Left(e) =>
        io.reject(Reasons.SchemaFailure, e.getMessage, None, Some(location), Some(text))
      case Right(c) =>
        io.count(Counters.Accepted)
        val keys = IdentifierKeys.keysOf(c)
        if (keys.isEmpty) io.count(Counters.MissingKey)
        keys.foreach(k => emit(k, "C" + text, ctx))
    }
}

class SourceJoinMapper extends ContractLineMapper {
  protected def line(text: String, location: String, ctx: Ctx): Unit =
    scala.util.Try(read[NormalizedSource](text)).toEither match {
      case Left(e) =>
        io.reject(Reasons.SchemaFailure, e.getMessage, None, Some(location), Some(text))
      case Right(n) =>
        io.count(Counters.Accepted)
        val keys = IdentifierKeys.keysOf(n)
        if (keys.isEmpty) io.count(Counters.MissingKey)
        keys.foreach(k => emit(k, "S" + text, ctx))
    }
}

class ExactIdJoinReducer extends Reducer[Text, Text, NullWritable, Text] {
  type Ctx = Reducer[Text, Text, NullWritable, Text]#Context
  private var io: TaskIO = null
  private var maxGroup = 0
  private val largest = new MaxTracker("largestIdentifierGroup")

  override def setup(ctx: Ctx): Unit = {
    io = new TaskIO(ctx, reduceStage = true)
    maxGroup = new Settings(ctx.getConfiguration).positiveInt(Settings.Keys.IdJoinMaxGroup)
  }

  override def reduce(key: Text, values: java.lang.Iterable[Text], ctx: Ctx): Unit = {
    val doi = key.toString.stripPrefix(IdentifierKeys.DoiPrefix)
    val contexts = List.newBuilder[MentionContext]
    val sources = List.newBuilder[NormalizedSource]
    var n = 0L
    values.asScala.foreach { v =>
      n += 1
      if (n <= maxGroup) {
        val s = v.toString
        if (s.startsWith("C")) contexts += read[MentionContext](s.substring(1))
        else sources += read[NormalizedSource](s.substring(1))
      }
    }
    largest.offer(n, doi)
    if (n > maxGroup) {
      io.count(Counters.OversizeBlock)
      io.conflict(Reasons.GroupTooLarge, s"$n records > $maxGroup", Some(doi))
      return
    }
    val out = ExactLinker.link(doi, contexts.result(), sources.result().distinct, io.run)
    if (out.unmatched) io.count(Counters.UnmatchedKeys)
    out.conflicts.foreach(c => io.conflict(c.reason, c.detail, Some(doi)))
    out.schemaFailures.foreach(f => io.reject(Reasons.SchemaFailure, f, Some(doi), None, None))
    out.links.foreach { l =>
      io.count(Counters.LinksEmitted)
      io.emit(ExactIdJoinJob.Links, ExactIdJoinJob.Links, write(l))
    }
  }

  override def cleanup(ctx: Ctx): Unit = {
    largest.flush(io)
    io.close()
  }
}
