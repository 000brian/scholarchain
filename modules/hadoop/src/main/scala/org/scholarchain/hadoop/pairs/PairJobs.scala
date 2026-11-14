package org.scholarchain.hadoop.pairs

import scala.jdk.CollectionConverters.*

import org.apache.hadoop.fs.Path
import org.apache.hadoop.io.{NullWritable, Text}
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat
import org.apache.hadoop.mapreduce.{Job, Reducer}
import org.scholarchain.core.{CoauthorPairCandidate, Contract, PairPosition, PublicationTeam}
import org.scholarchain.hadoop.common.*
import org.scholarchain.hadoop.io.BoundedLineInputFormat
import org.scholarchain.hadoop.teams.ContractLineMapper
import upickle.default.{macroRW, read, write, ReadWriter}

/** Shuffle value of J08a: one shard of one team (job contract, internal to J08a). */
final case class PairShard(team: PublicationTeam, shard: Long, from: Long, until: Long)
object PairShard { implicit val rw: ReadWriter[PairShard] = macroRW }

/** J08a: coauthor pair expansion.
  *
  * Map in: J07 `teams/` (PublicationTeam). Map out: `<work ID>\t<shard>` -> [[PairShard]] for each
  * of `ceil(n(n-1)/2 / pairsPerShard)` shards; teams with fewer than two authors yield no pairs
  * (SINGLE_AUTHOR_TEAMS). The stable hash partitioner spreads the shards of one team over the
  * reducers. Reduce: streams the shard's position pairs into `pairInstances/`
  * (CoauthorPairCandidate, one per position pair, before consolidation). A team of n authors
  * generates exactly n(n-1)/2 position pairs (PAIR_INSTANCES_GENERATED); pairs of one name with
  * itself are skipped and listed in `issues/v1/` (SELF_PAIRS_SKIPPED).
  */
object PairExpandJob {
  val JobName = "hadoop.j08a-pair-expand"
  val Instances = "pairInstances"

  def spec(teams: Path, outputRoot: Path, snapshotId: String, inputFingerprint: String): JobSpec =
    JobSpec(
      JobName,
      "dblp",
      snapshotId,
      Settings.Keys.DblpParsePolicy,
      inputFingerprint,
      outputRoot,
      s"dblp/snapshot=$snapshotId",
      Settings.Keys.PairExpandReducers,
      List(Instances),
      { (job: Job) =>
        job.getConfiguration
          .set(BoundedLineInputFormat.LimitKeyKey, Settings.Keys.InternalMaxLineBytes)
        job.setInputFormatClass(classOf[BoundedLineInputFormat])
        FileInputFormat.addInputPath(job, teams)
        job.setMapperClass(classOf[PairShardMapper])
        job.setReducerClass(classOf[PairExpandReducer])
      }
    )
}

class PairShardMapper extends ContractLineMapper {
  private var perShard = 0L
  private val maxPairs = new MaxTracker("largestPairExpansion")

  override def setup(ctx: Ctx): Unit = {
    super.setup(ctx)
    perShard = new Settings(ctx.getConfiguration).positiveLong(Settings.Keys.PairsPerShard)
  }

  protected def line(text: String, location: String, ctx: Ctx): Unit =
    Contract.decode[PublicationTeam](text) match {
      case Left(e)  => io.reject(Reasons.SchemaFailure, e.message, None, Some(location), Some(text))
      case Right(t) =>
        io.count(Counters.Accepted)
        val n = t.teamSize
        if (n < 2) io.count(Counters.SingleAuthorTeams)
        else {
          maxPairs.offer(PairIndex.pairCount(n), t.workId.value)
          val shards = PairIndex.shardCount(n, perShard)
          var s = 0L
          while (s < shards) {
            val (from, until) = PairIndex.shardRange(n, perShard, s)
            io.count(Counters.PairShards)
            emit(f"${t.workId.value}\t$s%09d", write(PairShard(t, s, from, until)), ctx)
            s += 1
          }
        }
    }

  override def cleanup(ctx: Ctx): Unit = {
    maxPairs.flush(io)
    super.cleanup(ctx)
  }
}

class PairExpandReducer extends Reducer[Text, Text, NullWritable, Text] {
  type Ctx = Reducer[Text, Text, NullWritable, Text]#Context
  private var io: TaskIO = null

  override def setup(ctx: Ctx): Unit = io = new TaskIO(ctx, reduceStage = true)

  override def reduce(key: Text, values: java.lang.Iterable[Text], ctx: Ctx): Unit = {
    val shards = values.asScala.map(_.toString).toList.distinct.sorted
    if (shards.size > 1) {
      io.count(Counters.Conflict, shards.size.toLong)
      shards.foreach(s =>
        io.reject(
          Reasons.DuplicateKeyConflict,
          "different teams for one work shard",
          Some(key.toString),
          None,
          Some(s)
        )
      )
      return
    }
    val shard = read[PairShard](shards.head)
    val t = shard.team
    val indexed = PairExpansion.index(t) // once per shard: O(1) name access per pair
    PairIndex.foreachPair(t.teamSize, shard.from, shard.until) { (i, j) =>
      io.count(Counters.PairInstances)
      PairExpansion.pairAt(indexed, i, j, io.run) match {
        case ExpandedPair.Candidate(p) =>
          io.count(Counters.EmittedPair)
          io.emit(PairExpandJob.Instances, PairExpandJob.Instances, write(p))
        case ExpandedPair.Invalid(detail) =>
          io.reject(
            Reasons.SchemaFailure,
            detail,
            t.provenance.sourceRecordIds.headOption,
            None,
            None
          )
        case ExpandedPair.SelfPair(name, a, b) =>
          io.count(Counters.SelfPairs)
          io.issue(
            Reasons.SelfPair,
            s"'$name' at positions $a and $b",
            t.provenance.sourceRecordIds.headOption,
            None
          )
      }
    }
  }

  override def cleanup(ctx: Ctx): Unit = io.close()
}

/** J08b: per-work consolidation of duplicate pair instances.
  *
  * Map in: J08a `pairInstances/`. Map out: `<work ID>\t<left>\t<right>` -> pair JSON. Reduce: one
  * CoauthorPairCandidate per work and unordered name pair in `pairs/`, whose `positions` is the
  * union of its instances' positions (sorted by `PairPosition.ordering`, distinct). The
  * consolidated pair is a new record written by this job, so its provenance is stamped with the
  * J08b run (`runId`, `jobName` = `hadoop.j08b-pair-consolidate`, policy version); the source facts
  * shared by all instances (source type, dataset, snapshot, source record IDs, content hash) are
  * kept. The J08a run it read is recorded as `inputRuns` in this run's `_job.json`. Collapsed
  * instances count as DUPLICATE_RECORDS. Cross-work aggregation into edges belongs to Spark.
  */
object PairConsolidateJob {
  val JobName = "hadoop.j08b-pair-consolidate"
  val Pairs = "pairs"

  def spec(
      instances: Path,
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
      Settings.Keys.PairConsolidateReducers,
      List(Pairs),
      { (job: Job) =>
        job.getConfiguration
          .set(BoundedLineInputFormat.LimitKeyKey, Settings.Keys.InternalMaxLineBytes)
        job.setInputFormatClass(classOf[BoundedLineInputFormat])
        FileInputFormat.addInputPath(job, instances)
        job.setMapperClass(classOf[PairInstanceMapper])
        job.setReducerClass(classOf[PairConsolidateReducer])
      }
    )

  /** Merges instances of one (work, left, right): the union of their positions, sorted by
    * `PairPosition.ordering` and distinct. `Left` when they disagree on any other fact (work, year,
    * names, team size or provenance). The result still carries the instances' (J08a) provenance;
    * the job uses the overload with a [[RunContext]], which re-stamps it.
    */
  def consolidate(instances: List[CoauthorPairCandidate]): Either[String, CoauthorPairCandidate] = {
    val facts = instances.map(_.copy(positions = Nil)).distinct
    if (facts.size != 1) Left(s"${facts.size} different work facts")
    else {
      val positions = instances.flatMap(_.positions).distinct.sorted(PairPosition.ordering)
      Right(instances.head.copy(positions = positions))
    }
  }

  /** [[consolidate]], then re-stamps the merged record with the provenance of `run` (the J08b run
    * that creates it), keeping the instances' source type, source record IDs, content hash, dataset
    * and snapshot.
    */
  def consolidate(
      instances: List[CoauthorPairCandidate],
      run: RunContext
  ): Either[String, CoauthorPairCandidate] =
    consolidate(instances).map { p =>
      val src = p.provenance
      p.copy(provenance =
        run.provenance(
          src.sourceType,
          src.sourceRecordIds,
          src.contentHash,
          src.datasetId,
          src.snapshotId
        )
      )
    }
}

class PairInstanceMapper extends ContractLineMapper {
  protected def line(text: String, location: String, ctx: Ctx): Unit =
    Contract.decode[CoauthorPairCandidate](text) match {
      case Left(e)  => io.reject(Reasons.SchemaFailure, e.message, None, Some(location), Some(text))
      case Right(p) =>
        io.count(Counters.Accepted)
        emit(s"${p.workId.value}\t${p.left}\t${p.right}", text, ctx)
    }
}

class PairConsolidateReducer extends Reducer[Text, Text, NullWritable, Text] {
  type Ctx = Reducer[Text, Text, NullWritable, Text]#Context
  private var io: TaskIO = null

  override def setup(ctx: Ctx): Unit = io = new TaskIO(ctx, reduceStage = true)

  override def reduce(key: Text, values: java.lang.Iterable[Text], ctx: Ctx): Unit = {
    val all = values.asScala.map(v => read[CoauthorPairCandidate](v.toString)).toList
    PairConsolidateJob.consolidate(all, io.run) match {
      case Left(err) =>
        io.count(Counters.Conflict, all.size.toLong)
        io.reject(Reasons.DuplicateKeyConflict, err, Some(key.toString), None, None)
      case Right(p) =>
        if (all.size > 1) io.count(Counters.Duplicate, all.size - 1L)
        Contract.encode(p) match {
          case Right(json) =>
            io.count(Counters.EmittedPair)
            io.emit(PairConsolidateJob.Pairs, PairConsolidateJob.Pairs, json)
          case Left(e) =>
            io.reject(Reasons.SchemaFailure, e.message, Some(key.toString), None, None)
        }
    }
  }

  override def cleanup(ctx: Ctx): Unit = io.close()
}
