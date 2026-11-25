package org.scholarchain.hadoop.blocking

import scala.collection.mutable

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.hadoop.io.{NullWritable, Text}
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat
import org.apache.hadoop.mapreduce.{Job, Reducer}
import org.scholarchain.hadoop.common.*
import org.scholarchain.hadoop.io.BoundedLineInputFormat
import org.scholarchain.hadoop.teams.{ContractLineMapper, MentionContext}
import upickle.default.{macroRW, read, write, ReadWriter}

/** Job contract `scholarchain.hadoop.oversize-key`, version 1: one block key whose group is larger
  * than the effective cap ([[BlockLimit]]) of the blocking job that reads it, found by a counting
  * pre-pass before that job runs.
  *
  *   - Written by J05p ([[BlockSizeJob]]) for J05: `family` is a primary family, `keyFields` is
  *     `List(<primary key>)`, `blockKey` is the primary key.
  *   - Written by J05bp ([[FallbackBlockSizeJob]]) for J05b: `family` is a fallback family
  *     `<primary>.<kind>`, `keyFields` is the primary key followed by the secondary key parts,
  *     `blockKey` is [[FallbackKeys.blockKey]] of them.
  *
  * `size` is the number of map values of the group (member copies included, as the reducer would
  * have counted them), `limit` the effective cap it exceeds. [[reduceKey]] is the group's
  * map-output key prefix (`<family>\t<keyFields, tab-separated>`).
  */
final case class OversizeKey(
    keyVersion: Int,
    family: String,
    keyFields: List[String],
    blockKey: String,
    size: Long,
    limit: Int
) {
  def reduceKey: String = (family :: keyFields).mkString("\t")
}
object OversizeKey {
  implicit val rw: ReadWriter[OversizeKey] = macroRW
  val Version = 1
}

/** The oversize keys a blocking mapper diverts, loaded once per map task from the pre-pass output
  * directory named by [[Settings.Keys.OversizeKeysPath]].
  *
  * Memory: one string per oversize key. A key is listed only when its group has more than `limit`
  * values, so a pre-pass over V map values lists at most V / (limit + 1) keys (for example 3
  * million mention contexts in two families at the default cap of 200 give at most about 30,000
  * keys).
  */
object OversizeKeySet {

  /** Reduce keys listed under `dir` (every `part-*` file). Fails the task when a line is not a
    * version-1 [[OversizeKey]] or was computed for another effective cap than `limit` (the pre-pass
    * and the blocking job must share one configuration).
    */
  def load(conf: Configuration, dir: Path, limit: Int): Set[String] =
    JobRunner
      .readPartLines(conf, dir)
      .iterator
      .map { l =>
        val k = scala.util
          .Try(read[OversizeKey](l))
          .getOrElse(throw new JobFailed(s"$dir: not an oversize-key record: ${l.take(200)}"))
        if (k.keyVersion != OversizeKey.Version)
          throw new JobFailed(s"$dir: keyVersion ${k.keyVersion} != ${OversizeKey.Version}")
        if (k.limit != limit)
          throw new JobFailed(
            s"$dir: oversize key ${k.reduceKey} was counted for limit ${k.limit}, this job's is $limit"
          )
        k.reduceKey
      }
      .toSet

  /** The keys configured for this task, or the empty set when the job runs without a pre-pass. */
  def configured(conf: Configuration, limit: Int): Set[String] =
    new Settings(conf)
      .optional(Settings.Keys.OversizeKeysPath)
      .map(p => load(conf, new Path(p), limit))
      .getOrElse(Set.empty)
}

/** Sums decimal counts per key: the combiner of J05p and J05bp (integer addition is associative and
  * commutative, so the reducer's sum does not depend on how often or where it runs).
  */
class CountSumCombiner extends Reducer[Text, Text, Text, Text] {
  type Ctx = Reducer[Text, Text, Text, Text]#Context
  private val out = new Text()

  override def reduce(key: Text, values: java.lang.Iterable[Text], ctx: Ctx): Unit = {
    out.set(BlockSizeCounts.sum(values).toString)
    ctx.write(key, out)
  }
}

object BlockSizeCounts {

  /** Sum of the decimal counts of one group (pre-pass map outputs are positive decimals). */
  def sum(values: java.lang.Iterable[Text]): Long = {
    var n = 0L
    val it = values.iterator()
    while (it.hasNext) n += it.next().toString.toLong
    n
  }
}

/** Reduce side of both pre-passes: one call per block key with its summed size; keys over the cap
  * become [[OversizeKey]] lines in `oversizeKeys/`.
  */
abstract class BlockSizeReducer extends Reducer[Text, Text, NullWritable, Text] {
  type Ctx = Reducer[Text, Text, NullWritable, Text]#Context
  protected var io: TaskIO = null
  protected var limit: BlockLimit = null
  private val largest = new MaxTracker(BlockSizeJob.LargestKeyGroupStat)

  /** The member-cap key of the blocking job this pre-pass serves. */
  protected def memberCapKey: String

  /** `blockKey` of the listed record for the reduce-key fields after the family. */
  protected def blockKeyOf(fields: List[String]): String

  override def setup(ctx: Ctx): Unit = {
    io = new TaskIO(ctx, reduceStage = true)
    limit = BlockLimit.of(new Settings(ctx.getConfiguration), memberCapKey)
  }

  override def reduce(key: Text, values: java.lang.Iterable[Text], ctx: Ctx): Unit = {
    val n = BlockSizeCounts.sum(values)
    val fields = key.toString.split("\t", -1).toList
    io.count(Counters.BlockKeysCounted)
    largest.offer(n, key.toString)
    if (n > limit.effective) {
      io.count(Counters.OversizeKeysFound)
      io.emit(
        BlockSizeJob.OversizeKeys,
        BlockSizeJob.OversizeKeys,
        write(
          OversizeKey(
            OversizeKey.Version,
            fields.head,
            fields.tail,
            blockKeyOf(fields.tail),
            n,
            limit.effective
          )
        )
      )
    }
  }

  override def cleanup(ctx: Ctx): Unit = {
    largest.flush(io)
    io.close()
  }
}

/** J05p: counting pre-pass of J05 (primary block sizes).
  *
  * Map in: J07 `mentionContexts/` (same input as J05). Map out: `<family>\t<key>` -> `1` for every
  * configured family and key ([[BlockingKeys]]), the same groups J05 forms. Combiner:
  * [[CountSumCombiner]]. Reduce: the size of every group (BLOCK_KEYS_COUNTED); groups over the
  * effective J05 cap (`scholarchain.blocking.maxBlockSize` with
  * `scholarchain.candidates.maxPairsPerBlock`) are listed in `oversizeKeys/` ([[OversizeKey]],
  * OVERSIZE_KEYS_FOUND). Stat `largestKeyGroup`. J05 reads `oversizeKeys/` in every map task and
  * re-keys the members of those keys, so no J05 reduce group is larger than the cap. Shuffle: one
  * count per map task and key after combining.
  */
object BlockSizeJob {
  val JobName = "hadoop.j05p-block-sizes"
  val OversizeKeys = "oversizeKeys"
  val LargestKeyGroupStat = "largestKeyGroup"

  def spec(
      contexts: Path,
      outputRoot: Path,
      snapshotId: String,
      inputFingerprint: String
  ): JobSpec =
    JobSpec(
      JobName,
      "dblp",
      snapshotId,
      Settings.Keys.BlockingPolicy,
      inputFingerprint,
      outputRoot,
      s"dblp/snapshot=$snapshotId",
      Settings.Keys.BlockingReducers,
      List(OversizeKeys),
      { (job: Job) =>
        val s = new Settings(job.getConfiguration)
        BlockingKeys.configured(s)
        BlockLimit.of(s, Settings.Keys.BlockingMaxBlock)
        job.getConfiguration
          .set(BoundedLineInputFormat.LimitKeyKey, Settings.Keys.InternalMaxLineBytes)
        job.setInputFormatClass(classOf[BoundedLineInputFormat])
        FileInputFormat.addInputPath(job, contexts)
        job.setMapperClass(classOf[BlockSizeMapper])
        job.setCombinerClass(classOf[CountSumCombiner])
        job.setReducerClass(classOf[PrimaryBlockSizeReducer])
      },
      alwaysPresentDirs = List(OversizeKeys)
    )
}

class BlockSizeMapper extends ContractLineMapper {
  private var families: List[String] = Nil

  override def setup(ctx: Ctx): Unit = {
    super.setup(ctx)
    families = BlockingKeys.configured(new Settings(ctx.getConfiguration))
  }

  protected def line(text: String, location: String, ctx: Ctx): Unit =
    scala.util.Try(read[MentionContext](text)).toEither match {
      case Left(e) =>
        io.reject(Reasons.SchemaFailure, e.getMessage, None, Some(location), Some(text))
      case Right(c) =>
        io.count(Counters.Accepted)
        families.foreach(f => BlockingKeys.keys(f, c).foreach(k => emit(s"$f\t$k", "1", ctx)))
    }
}

class PrimaryBlockSizeReducer extends BlockSizeReducer {
  protected def memberCapKey: String = Settings.Keys.BlockingMaxBlock
  protected def blockKeyOf(fields: List[String]): String = fields.mkString("\t")
}

/** J05bp: counting pre-pass of J05b (fallback block sizes).
  *
  * Map in: J05 `oversizeMembers/` (same input as J05b; malformed lines are schema_failure rejects).
  * Map out: [[FallbackKeys.reduceKey]] -> `1` for every configured fallback family, secondary kind
  * and value, the same groups J05b forms. Combiner: [[CountSumCombiner]]. Reduce: groups over the
  * effective J05b cap (`scholarchain.blocking.fallback.maxBlockSize` with
  * `scholarchain.candidates.maxPairsPerBlock`) are listed in `oversizeKeys/` ([[OversizeKey]] with
  * a fallback family, OVERSIZE_KEYS_FOUND). These are the still-oversize groups: J05b reads the
  * list and applies its still-oversize policy (see [[FallbackBlockingJob]]) in the mapper, so no
  * J05b reduce group is larger than the cap.
  */
object FallbackBlockSizeJob {
  val JobName = "hadoop.j05bp-fallback-block-sizes"
  val OversizeKeys: String = BlockSizeJob.OversizeKeys

  def spec(
      oversizeMembers: Path,
      outputRoot: Path,
      snapshotId: String,
      inputFingerprint: String
  ): JobSpec =
    JobSpec(
      JobName,
      "dblp",
      snapshotId,
      Settings.Keys.BlockingPolicy,
      inputFingerprint,
      outputRoot,
      s"dblp/snapshot=$snapshotId",
      Settings.Keys.FallbackReducers,
      List(OversizeKeys),
      { (job: Job) =>
        val s = new Settings(job.getConfiguration)
        FallbackKeys.configured(s)
        BlockLimit.of(s, Settings.Keys.FallbackMaxBlock)
        job.getConfiguration
          .set(BoundedLineInputFormat.LimitKeyKey, Settings.Keys.InternalMaxLineBytes)
        job.setInputFormatClass(classOf[BoundedLineInputFormat])
        FileInputFormat.addInputPath(job, oversizeMembers)
        job.setMapperClass(classOf[FallbackBlockSizeMapper])
        job.setCombinerClass(classOf[CountSumCombiner])
        job.setReducerClass(classOf[FallbackBlockSizeReducer])
      },
      alwaysPresentDirs = List(OversizeKeys)
    )
}

class FallbackBlockSizeMapper extends ContractLineMapper {
  private var families: Set[String] = Set.empty
  private var kinds: List[String] = Nil

  override def setup(ctx: Ctx): Unit = {
    super.setup(ctx)
    val (f, k) = FallbackKeys.configured(new Settings(ctx.getConfiguration))
    families = f.toSet
    kinds = k
  }

  protected def line(text: String, location: String, ctx: Ctx): Unit =
    FallbackBlockingJob.decode(text) match {
      case Left(err) => io.reject(Reasons.SchemaFailure, err, None, Some(location), Some(text))
      case Right(om) =>
        io.count(Counters.Accepted)
        FallbackKeys.reduceKeys(om, families, kinds).foreach(k => emit(k, "1", ctx))
    }
}

class FallbackBlockSizeReducer extends BlockSizeReducer {
  protected def memberCapKey: String = Settings.Keys.FallbackMaxBlock
  protected def blockKeyOf(fields: List[String]): String =
    FallbackKeys.blockKey(fields.head, fields.tail)
}

/** Per-map-task sizes of the groups a mapper diverts (oversize keys only, so bounded by the
  * [[OversizeKeySet]]), written as size markers in `cleanup` in code point order of the key.
  */
final class DivertedSizes {
  private val sizes = mutable.HashMap.empty[String, Long]
  def add(key: String): Unit = sizes.update(key, sizes.getOrElse(key, 0L) + 1L)
  def sorted: List[(String, Long)] =
    sizes.toList.sortWith((a, b) => org.scholarchain.core.CodePointOrder.lessThan(a._1, b._1))
}
