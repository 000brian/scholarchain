package org.scholarchain.hadoop.sources

import scala.jdk.CollectionConverters.*

import org.apache.hadoop.fs.Path
import org.apache.hadoop.io.{NullWritable, Text}
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat
import org.apache.hadoop.mapreduce.{Job, Reducer}
import org.scholarchain.hadoop.common.*
import org.scholarchain.hadoop.io.BoundedLineInputFormat
import org.scholarchain.hadoop.teams.ContractLineMapper
import upickle.default.write

/** J02: source normalizers (one run per dataset and kind).
  *
  * Map in: raw JSON lines of one kind ([[SourceNormalizers.kinds]]) through the bounded line input
  * format. Map out: `R|<source key>` -> [[NormalizedSource]] JSON; `I|<institution ID>` ->
  * Institution JSON; `T|<topic ID>` -> Topic JSON. Reduce: identical copies collapse (DUPLICATE);
  * differing copies of one key are all rejected (CONFLICT). Outputs: `normalized/`,
  * `institutions/`, `topics/`, `rejects/v1/`, `issues/v1/`.
  */
object NormalizeJob {
  val JobNamePrefix = "hadoop.j02-normalize-"
  val Normalized = "normalized"
  val Institutions = "institutions"
  val Topics = "topics"

  def spec(
      kind: String,
      input: Path,
      outputRoot: Path,
      datasetId: String,
      snapshotId: String,
      inputFingerprint: String
  ): JobSpec = {
    require(SourceNormalizers.kinds.contains(kind), s"unknown normalizer kind $kind")
    JobSpec(
      JobNamePrefix + kind,
      datasetId,
      snapshotId,
      Settings.Keys.NormalizePolicy,
      inputFingerprint,
      outputRoot,
      s"$datasetId/snapshot=$snapshotId",
      Settings.Keys.NormalizeReducers,
      List(Normalized, Institutions, Topics),
      { (job: Job) =>
        job.getConfiguration.set(Settings.Keys.NormalizeKind, kind)
        job.getConfiguration
          .set(BoundedLineInputFormat.LimitKeyKey, Settings.Keys.NormalizeMaxLineBytes)
        job.setInputFormatClass(classOf[BoundedLineInputFormat])
        FileInputFormat.addInputPath(job, input)
        job.setMapperClass(classOf[NormalizeMapper])
        job.setReducerClass(classOf[NormalizeReducer])
      }
    )
  }
}

class NormalizeMapper extends ContractLineMapper {
  private var kind = ""
  private var keepAbstracts = false

  override def setup(ctx: Ctx): Unit = {
    super.setup(ctx)
    val s = new Settings(ctx.getConfiguration)
    kind = s.string(Settings.Keys.NormalizeKind)
    keepAbstracts = s.boolean(Settings.Keys.NormalizeKeepCrossrefAbstracts)
  }

  protected def line(text: String, location: String, ctx: Ctx): Unit =
    SourceNormalizers.normalize(kind, text, io.run, keepAbstracts) match {
      case Left(r)  => io.reject(r.reason, r.detail, r.sourceRecordId, Some(location), Some(text))
      case Right(n) =>
        io.count(Counters.Accepted)
        n.issues.foreach(i =>
          io.issue(i.reason, i.detail, Some(n.record.sourceRecordId), Some(location))
        )
        emit("R|" + n.record.sourceRecordId, write(n.record), ctx)
        n.institutions.foreach(i => emit("I|" + i.institutionId, write(i), ctx))
        n.topics.foreach(t => emit("T|" + t.topicId, write(t), ctx))
    }
}

class NormalizeReducer extends Reducer[Text, Text, NullWritable, Text] {
  type Ctx = Reducer[Text, Text, NullWritable, Text]#Context
  private var io: TaskIO = null

  override def setup(ctx: Ctx): Unit = io = new TaskIO(ctx, reduceStage = true)

  override def reduce(key: Text, values: java.lang.Iterable[Text], ctx: Ctx): Unit = {
    val k = key.toString
    val all = values.asScala.map(_.toString).toList
    val distinct = all.distinct.sorted
    val (named, isRecord) = k.take(2) match {
      case "R|" => NormalizeJob.Normalized -> true
      case "I|" => NormalizeJob.Institutions -> false
      case _    => NormalizeJob.Topics -> false
    }
    if (distinct.size == 1) {
      // Institutions and topics repeat on many records by design; only source records count.
      if (all.size > 1 && isRecord) io.count(Counters.Duplicate, all.size - 1L)
      io.emit(named, named, distinct.head)
    } else {
      io.count(Counters.Conflict, distinct.size.toLong)
      distinct.foreach(v =>
        io.reject(
          Reasons.DuplicateKeyConflict,
          s"${distinct.size} different records for one key",
          Some(k.drop(2)),
          None,
          Some(v)
        )
      )
    }
  }

  override def cleanup(ctx: Ctx): Unit = io.close()
}
