package org.scholarchain.hadoop.blocking

import scala.collection.mutable

import org.apache.hadoop.fs.Path
import org.apache.hadoop.io.{NullWritable, Text}
import org.apache.hadoop.mapreduce.lib.input.{FileInputFormat, MultipleInputs}
import org.apache.hadoop.mapreduce.{Job, Reducer}
import org.scholarchain.core.{CodePointOrder, MentionId}
import org.scholarchain.hadoop.common.*
import org.scholarchain.hadoop.io.BoundedLineInputFormat
import org.scholarchain.hadoop.teams.ContractLineMapper
import upickle.default.{macroRW, read, write, ReadWriter}

/** Job contract `scholarchain.hadoop.fallback-coverage`, version 1: member `mentionId` of the
  * oversize primary block (`family`, `blockKey`) is in an emitted fallback block of
  * `fallbackFamily` with at least two members. Written by J05b to `coverage/`, read by J05c.
  */
final case class FallbackCoverage(
    coverageVersion: Int,
    family: String,
    blockKey: String,
    mentionId: String,
    fallbackFamily: String
)
object FallbackCoverage {
  implicit val rw: ReadWriter[FallbackCoverage] = macroRW
  val Version = 1
}

/** Job contract `scholarchain.hadoop.uncovered-member`, version 1: a member of the oversize primary
  * block (`family`, `blockKey`) that is in no emitted fallback block with at least two members, so
  * J06 forms no candidate for it in that family. Written by J05c to `uncoveredMembers/`. These
  * mentions still belong to their dblp-prior identity cluster downstream; the record makes them
  * identifiable (mention ID, work, dblp key, name).
  */
final case class UncoveredMember(
    uncoveredVersion: Int,
    family: String,
    blockKey: String,
    mentionId: String,
    workId: String,
    dblpKey: String,
    name: String
)
object UncoveredMember {
  implicit val rw: ReadWriter[UncoveredMember] = macroRW
  val Version = 1
}

final case class FamilyCount(family: String, members: Long)
object FamilyCount { implicit val rw: ReadWriter[FamilyCount] = macroRW }

/** Job contract `scholarchain.hadoop.oversize-key-summary`, version 1: fallback outcome of one
  * oversize primary block. `members` = distinct members, `coveredMembers + uncoveredMembers ==
  * members`; `coveredBy` counts covered members per fallback family (a member may be covered by
  * several), sorted by family. Written by J05c to `oversizeKeySummary/`; the worst keys are those
  * with the most uncovered members.
  */
final case class OversizeKeySummary(
    summaryVersion: Int,
    family: String,
    blockKey: String,
    members: Long,
    coveredMembers: Long,
    uncoveredMembers: Long,
    coveredBy: List[FamilyCount]
)
object OversizeKeySummary {
  implicit val rw: ReadWriter[OversizeKeySummary] = macroRW
  val Version = 1
}

/** J05b: oversize-block fallback re-blocking.
  *
  * Map in: J05 `oversizeMembers/` ([[OversizeMember]]; structural problems are schema_failure
  * rejects). For a member whose primary family is in `scholarchain.blocking.fallback.families`, one
  * map output per configured secondary kind and value ([[FallbackKeys.values]]):
  * `<family>.<kind>\t<primary key>\t<value parts, tab-separated>` -> [[BlockMember]] JSON
  * (FALLBACK_KEYS_EMITTED). Members without a value for a kind are counted per fallback family
  * ([[Counters.NoSecondaryKeyGroup]]); members of families without fallback per primary family
  * ([[Counters.FallbackNotConfiguredGroup]]).
  *
  * Reduce: like J05, capped by the effective [[BlockLimit]] of
  * `scholarchain.blocking.fallback.maxBlockSize` and `scholarchain.candidates.maxPairsPerBlock`:
  * larger fallback blocks are not emitted (OVERSIZE_BLOCKS; within the member cap but over the pair
  * limit also PAIR_LIMIT_OVERSIZE_BLOCKS; `fallbackOversizeBlocks/` with limits, pairs and reason),
  * so J06 expands every emitted fallback block, and their members end up covered by another
  * fallback block or uncovered (J05c); singletons are counted (emitted only with
  * `scholarchain.blocking.emitSingletons`); other blocks go to `blocks/` as [[CandidateBlock]] v2
  * with family `<family>.<kind>` and key [[FallbackKeys.blockKey]] (BLOCKS_EMITTED, per family in
  * [[Counters.BlocksPerFamilyGroup]]), and each member of an emitted block of two or more members
  * is written to `coverage/` ([[FallbackCoverage]]). `blocks/` and `coverage/` always exist.
  *
  * Stats (subject `<family>.<kind>\t<fallback block key>`):
  *   - [[FallbackBlockingJob.LargestFallbackBlockStat]] (`largestFallbackBlock`): the largest
  *     fallback group BEFORE the cap is applied, i.e. the number of map values for one fallback
  *     key, including groups over `scholarchain.blocking.fallback.maxBlockSize` that were not
  *     emitted. It may therefore exceed the cap; it is a measure of fallback key skew, not of the
  *     blocks J06 reads.
  *   - [[FallbackBlockingJob.LargestEmittedFallbackBlockStat]] (`largestEmittedFallbackBlock`): the
  *     largest fallback block actually written to `blocks/` (its [[CandidateBlock]] `size`, after
  *     duplicate members collapse). Never exceeds the cap; absent when no fallback block was
  *     emitted.
  *
  * Still-oversize policy (Phase 17 R3, check HADOOP-04). The pipeline runs the counting pre-pass
  * J05bp ([[FallbackBlockSizeJob]]) over the same `oversizeMembers/` first and passes its
  * `oversizeKeys/` (`oversizeKeys`). A fallback group listed there is still over the fallback cap
  * after re-keying. The policy, deterministic because it depends only on the group's size: such a
  * group is not formed. Its member values are never shuffled (the mapper counts them as
  * STILL_OVERSIZE_MEMBERS and sends one size marker `#<count>` per map task under the group's key);
  * (so one value per map task that saw the group, not counted in the `largestReduceGroup` stat);
  * the reducer sums the markers, counts the group as OVERSIZE_BLOCKS and STILL_OVERSIZE_BLOCKS and
  * lists it in `fallbackOversizeBlocks/` with its size, limits and reason. No further key is tried
  * for it; each of its members is covered only by its other fallback blocks (other coauthors or
  * kinds) and otherwise is counted and listed as uncovered by J05c (FALLBACK_UNCOVERED_MEMBERS,
  * `uncoveredMembers/`, `oversizeKeySummary/`), keeping its dblp-prior cluster downstream. Every
  * other fallback group is within the cap (REKEYED_BLOCKS) and is emitted or counted as a singleton
  * as above. The records written are the same as with the reducer-side cap, which stays as a guard
  * for a run without the pre-pass (`oversizeKeys` = None; such groups also count
  * REDUCER_CAPPED_BLOCKS, 0 in the pipeline).
  */
object FallbackBlockingJob {
  val JobName = "hadoop.j05b-fallback-blocking"
  val Blocks = "blocks"
  val OversizeBlocks = "fallbackOversizeBlocks"
  val Coverage = "coverage"

  /** Largest fallback group before the cap (may exceed the fallback maxBlockSize). */
  val LargestFallbackBlockStat = "largestFallbackBlock"

  /** Largest fallback block written to `blocks/` (never exceeds the fallback maxBlockSize). */
  val LargestEmittedFallbackBlockStat = "largestEmittedFallbackBlock"

  /** Prefix of a size marker value (member values are JSON objects and start with `{`). */
  val SizeMarker = "#"

  /** @param oversizeKeys
    *   the `oversizeKeys/` directory of the J05bp run over the same `oversizeMembers` with the same
    *   configuration (its run ID belongs in this job's input runs); None runs without the pre-pass
    *   (reducer-side cap only)
    */
  def spec(
      oversizeMembers: Path,
      outputRoot: Path,
      snapshotId: String,
      inputFingerprint: String,
      oversizeKeys: Option[Path] = None
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
      List(Blocks, OversizeBlocks, Coverage),
      { (job: Job) =>
        val s = new Settings(job.getConfiguration)
        FallbackKeys.configured(s)
        BlockLimit.of(s, Settings.Keys.FallbackMaxBlock)
        job.getConfiguration
          .set(BoundedLineInputFormat.LimitKeyKey, Settings.Keys.InternalMaxLineBytes)
        oversizeKeys.foreach(p =>
          job.getConfiguration.set(Settings.Keys.OversizeKeysPath, p.toString)
        )
        job.setInputFormatClass(classOf[BoundedLineInputFormat])
        FileInputFormat.addInputPath(job, oversizeMembers)
        job.setMapperClass(classOf[FallbackBlockingMapper])
        job.setReducerClass(classOf[FallbackBlockingReducer])
      },
      alwaysPresentDirs = List(Blocks, Coverage)
    )

  /** Decodes and checks one `oversizeMembers/` line. */
  def decode(text: String): Either[String, OversizeMember] =
    scala.util
      .Try(read[OversizeMember](text))
      .toEither
      .left
      .map(e => Option(e.getMessage).getOrElse(e.getClass.getName))
      .flatMap(m =>
        OversizeMember.problems(m) match {
          case Nil  => Right(m)
          case errs => Left(errs.mkString("; "))
        }
      )
}

class FallbackBlockingMapper extends ContractLineMapper {
  private var families: Set[String] = Set.empty
  private var kinds: List[String] = Nil
  // reduce keys of the still-oversize fallback groups found by J05bp (empty without the pre-pass)
  private var stillOversize: Set[String] = Set.empty
  private val diverted = new DivertedSizes

  override def setup(ctx: Ctx): Unit = {
    super.setup(ctx)
    val s = new Settings(ctx.getConfiguration)
    val (f, k) = FallbackKeys.configured(s)
    families = f.toSet
    kinds = k
    stillOversize = OversizeKeySet.configured(
      ctx.getConfiguration,
      BlockLimit.of(s, Settings.Keys.FallbackMaxBlock).effective
    )
  }

  override def cleanup(ctx: Ctx): Unit = {
    diverted.sorted.foreach { case (k, n) =>
      emit(k, FallbackBlockingJob.SizeMarker + n.toString, ctx)
    }
    super.cleanup(ctx)
  }

  protected def line(text: String, location: String, ctx: Ctx): Unit =
    FallbackBlockingJob.decode(text) match {
      case Left(err) => io.reject(Reasons.SchemaFailure, err, None, Some(location), Some(text))
      case Right(om) =>
        io.count(Counters.Accepted)
        if (!families(om.family) || kinds.isEmpty)
          io.countIn(Counters.FallbackNotConfiguredGroup, om.family)
        else {
          val member = write(om.member)
          kinds.foreach { kind =>
            val fallback = FallbackKeys.fallbackFamily(om.family, kind)
            FallbackKeys.values(kind, om.member) match {
              case Nil    => io.countIn(Counters.NoSecondaryKeyGroup, fallback)
              case values =>
                values.foreach { parts =>
                  io.count(Counters.FallbackKeysEmitted)
                  val key = FallbackKeys.reduceKey(fallback, om.blockKey, parts)
                  if (stillOversize.contains(key)) {
                    // still-oversize policy: the group is not formed; count, never shuffle
                    io.count(Counters.StillOversizeMembers)
                    diverted.add(key)
                  } else emit(key, member, ctx)
                }
            }
          }
        }
    }
}

class FallbackBlockingReducer extends Reducer[Text, Text, NullWritable, Text] {
  type Ctx = Reducer[Text, Text, NullWritable, Text]#Context
  private var io: TaskIO = null
  private var limit: BlockLimit = null
  // effective cap: min(fallback maxBlockSize, members whose pairs fit maxPairsPerBlock)
  private var maxBlock = 0
  private var emitSingletons = false
  // pre-cap: every fallback group, emitted or not (may exceed maxBlock)
  private val largest = new MaxTracker(FallbackBlockingJob.LargestFallbackBlockStat)
  // post-cap: only blocks written to blocks/ (never exceeds maxBlock)
  private val largestEmitted = new MaxTracker(FallbackBlockingJob.LargestEmittedFallbackBlockStat)
  // member values read by one reduce call, size markers excluded (at most the cap with the
  // pre-pass, whatever the map-task layout)
  private val groups = new MaxTracker(BlockingJob.LargestReduceGroupStat)

  override def setup(ctx: Ctx): Unit = {
    io = new TaskIO(ctx, reduceStage = true)
    val s = new Settings(ctx.getConfiguration)
    limit = BlockLimit.of(s, Settings.Keys.FallbackMaxBlock)
    maxBlock = limit.effective
    emitSingletons = s.boolean(Settings.Keys.BlockingEmitSingletons)
  }

  override def reduce(key: Text, values: java.lang.Iterable[Text], ctx: Ctx): Unit = {
    val fields = key.toString.split("\t", -1).toList
    val fallback = fields.head
    val primaryKey = fields(1)
    val (primaryFamily, _) = FallbackKeys.split(fallback)
    val blockKey = FallbackKeys.blockKey(primaryKey, fields.drop(2))
    val buffer = mutable.ArrayBuffer.empty[String]
    var members = 0L
    var marked = 0L // member values counted by mappers under the still-oversize policy
    val it = values.iterator()
    while (it.hasNext) {
      val v = it.next().toString
      if (v.startsWith(FallbackBlockingJob.SizeMarker))
        marked += v.substring(FallbackBlockingJob.SizeMarker.length).toLong
      else {
        members += 1
        if (members <= maxBlock) buffer += v
      }
    }
    val n = members + marked
    // only member values count: size markers are one per map task (layout-dependent), so a group
    // of markers only (a still-oversize group) is not offered
    if (members > 0) groups.offer(members, key.toString)
    if (n > maxBlock) buffer.clear()
    largest.offer(n, s"$fallback\t$blockKey")
    if (n > maxBlock) {
      if (marked > 0) io.count(Counters.StillOversizeBlocks)
      else io.count(Counters.ReducerCappedBlocks)
      io.count(Counters.OversizeBlock)
      io.countIn(Counters.OversizePerFamilyGroup, fallback)
      if (limit.reason(n) == BlockLimit.PairReason) io.count(Counters.PairLimitOversizeBlocks)
      io.emit(
        FallbackBlockingJob.OversizeBlocks,
        FallbackBlockingJob.OversizeBlocks,
        ujson.Obj
          .from(
            List[(String, ujson.Value)](
              "family" -> fallback,
              "blockKey" -> blockKey,
              "primaryFamily" -> primaryFamily,
              "primaryKey" -> primaryKey,
              "size" -> n.toDouble
            ) ++ limit.listing(n)
          )
          .render()
      )
    } else if (n == 1 && !emitSingletons) {
      io.count(Counters.RekeyedBlocks)
      io.count(Counters.SingletonBlocks)
    } else {
      io.count(Counters.RekeyedBlocks)
      val ms = CandidateBlock.normalise(buffer.map(read[BlockMember](_)))
      if (ms.size < n) io.count(Counters.Duplicate, n - ms.size)
      io.count(Counters.BlocksEmitted)
      io.countIn(Counters.BlocksPerFamilyGroup, fallback)
      largestEmitted.offer(ms.size.toLong, s"$fallback\t$blockKey")
      io.emit(
        FallbackBlockingJob.Blocks,
        FallbackBlockingJob.Blocks,
        write(
          CandidateBlock(
            CandidateBlock.Version,
            fallback,
            blockKey,
            ms.size,
            ms,
            io.run.policyVersion,
            io.run.runId.value
          )
        )
      )
      if (ms.size >= 2)
        ms.foreach { m =>
          io.emit(
            FallbackBlockingJob.Coverage,
            FallbackBlockingJob.Coverage,
            write(
              FallbackCoverage(
                FallbackCoverage.Version,
                primaryFamily,
                primaryKey,
                m.mentionId,
                fallback
              )
            )
          )
        }
    }
  }

  override def cleanup(ctx: Ctx): Unit = {
    largest.flush(io)
    largestEmitted.flush(io)
    groups.flush(io)
    io.close()
  }
}

/** J05c: coverage of the oversize-block fallback.
  *
  * Map in: J05 `oversizeMembers/` (every member of every oversize primary block) and J05b
  * `coverage/`. Map out: `<family>\t<primary key>\t<mention ID>` -> `M` + [[UncoveredMember]] JSON
  * or `C` + fallback family. The [[TwoFieldPrefixPartitioner]] sends every member of one oversize
  * block to one reduce task, where its keys arrive contiguously (sorted by mention ID).
  *
  * Reduce, per member: covered when at least one coverage record exists (FALLBACK_COVERED_MEMBERS);
  * otherwise written to `uncoveredMembers/` (FALLBACK_UNCOVERED_MEMBERS, per family in
  * [[Counters.UncoveredPerFamilyGroup]]). Identical member copies collapse (DUPLICATE). A coverage
  * record without its oversize member is rejected (`coverage_without_oversize_member`). Per
  * oversize block, streamed when the block's keys end: one [[OversizeKeySummary]] line in
  * `oversizeKeySummary/` (OVERSIZE_KEYS_SUMMARISED). Stats: worstUncoveredKey (most uncovered
  * members; subject `<family>\t<key>`). Memory is constant per member and per block.
  */
object FallbackCoverageJob {
  val JobName = "hadoop.j05c-fallback-coverage"
  val Uncovered = "uncoveredMembers"
  val Summary = "oversizeKeySummary"

  def spec(
      oversizeMembers: Path,
      coverage: Path,
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
      List(Uncovered, Summary),
      { (job: Job) =>
        job.getConfiguration
          .set(BoundedLineInputFormat.LimitKeyKey, Settings.Keys.InternalMaxLineBytes)
        MultipleInputs.addInputPath(
          job,
          oversizeMembers,
          classOf[BoundedLineInputFormat],
          classOf[CoverageMemberMapper]
        )
        MultipleInputs.addInputPath(
          job,
          coverage,
          classOf[BoundedLineInputFormat],
          classOf[CoverageRecordMapper]
        )
        job.setPartitionerClass(classOf[TwoFieldPrefixPartitioner])
        job.setReducerClass(classOf[FallbackCoverageReducer])
      }
    )

  def key(family: String, blockKey: String, mentionId: String): String =
    s"$family\t$blockKey\t$mentionId"
}

class CoverageMemberMapper extends ContractLineMapper {
  protected def line(text: String, location: String, ctx: Ctx): Unit =
    FallbackBlockingJob.decode(text) match {
      case Left(err) => io.reject(Reasons.SchemaFailure, err, None, Some(location), Some(text))
      case Right(om) =>
        io.count(Counters.Accepted)
        val m = om.member
        val u = UncoveredMember(
          UncoveredMember.Version,
          om.family,
          om.blockKey,
          m.mentionId,
          m.workId,
          m.dblpKey,
          m.name
        )
        emit(FallbackCoverageJob.key(om.family, om.blockKey, m.mentionId), "M" + write(u), ctx)
    }
}

class CoverageRecordMapper extends ContractLineMapper {
  protected def line(text: String, location: String, ctx: Ctx): Unit =
    scala.util
      .Try(read[FallbackCoverage](text))
      .toEither
      .left
      .map(e => Option(e.getMessage).getOrElse(e.getClass.getName))
      .flatMap { c =>
        val problems = List(
          Option.when(c.coverageVersion != FallbackCoverage.Version)(
            s"coverageVersion ${c.coverageVersion}"
          ),
          Option.when(!BlockingKeys.supported(c.family))(s"family '${c.family}'"),
          Option.when(c.blockKey.trim.isEmpty || c.blockKey.exists(Character.isISOControl))(
            "blockKey is blank or has control characters"
          ),
          Option.when(!MentionId.isWellFormed(c.mentionId))("mention ID is malformed"),
          Option.when(!c.fallbackFamily.startsWith(c.family + "."))(
            s"fallbackFamily '${c.fallbackFamily}'"
          )
        ).flatten
        if (problems.isEmpty) Right(c) else Left(problems.mkString("; "))
      } match {
      case Left(err) => io.reject(Reasons.SchemaFailure, err, None, Some(location), Some(text))
      case Right(c)  =>
        io.count(Counters.Accepted)
        emit(
          FallbackCoverageJob.key(c.family, c.blockKey, c.mentionId),
          "C" + c.fallbackFamily,
          ctx
        )
    }
}

class FallbackCoverageReducer extends Reducer[Text, Text, NullWritable, Text] {
  type Ctx = Reducer[Text, Text, NullWritable, Text]#Context
  private var io: TaskIO = null
  private val worst = new MaxTracker("worstUncoveredKey")

  // the oversize block whose members are being streamed
  private var current: Option[(String, String)] = None
  private var members = 0L
  private var covered = 0L
  private var uncovered = 0L
  private val coveredBy = mutable.Map.empty[String, Long]

  override def setup(ctx: Ctx): Unit = io = new TaskIO(ctx, reduceStage = true)

  private def flush(): Unit = {
    current.foreach { case (family, blockKey) =>
      io.count(Counters.OversizeKeys)
      worst.offer(uncovered, s"$family\t$blockKey")
      val by = coveredBy.toList
        .sortWith((a, b) => CodePointOrder.lessThan(a._1, b._1))
        .map { case (f, n) => FamilyCount(f, n) }
      io.emit(
        FallbackCoverageJob.Summary,
        FallbackCoverageJob.Summary,
        write(
          OversizeKeySummary(
            OversizeKeySummary.Version,
            family,
            blockKey,
            members,
            covered,
            uncovered,
            by
          )
        )
      )
    }
    current = None
    members = 0L
    covered = 0L
    uncovered = 0L
    coveredBy.clear()
  }

  override def reduce(key: Text, values: java.lang.Iterable[Text], ctx: Ctx): Unit = {
    val Array(family, blockKey, mentionId) = key.toString.split("\t", 3)
    val block = (family, blockKey)
    var memberCopies = List.empty[String]
    val fallbacks = mutable.TreeSet.empty[String]
    val it = values.iterator()
    while (it.hasNext) {
      val v = it.next().toString
      if (v.startsWith("M")) {
        val m = v.substring(1)
        if (!memberCopies.contains(m)) memberCopies = m :: memberCopies
        else io.count(Counters.Duplicate)
      } else fallbacks += v.substring(1)
    }
    memberCopies.sorted(CodePointOrder.ordering) match {
      case Nil =>
        io.reject(
          Reasons.CoverageWithoutMember,
          s"coverage by ${fallbacks.mkString(",")} for a mention that is not an oversize member",
          Some(mentionId),
          None,
          Some(key.toString)
        )
      case member :: rest =>
        if (rest.nonEmpty) {
          // different member records for one key cannot come from one J05 run
          io.conflict(
            Reasons.DuplicateKeyConflict,
            "different oversize member records",
            Some(key.toString)
          )
        }
        if (!current.contains(block)) {
          flush()
          current = Some(block)
        }
        members += 1
        if (fallbacks.nonEmpty) {
          covered += 1
          io.count(Counters.CoveredMembers)
          fallbacks.foreach(f => coveredBy.update(f, coveredBy.getOrElse(f, 0L) + 1))
        } else {
          uncovered += 1
          io.count(Counters.UncoveredMembers)
          io.countIn(Counters.UncoveredPerFamilyGroup, family)
          io.emit(FallbackCoverageJob.Uncovered, FallbackCoverageJob.Uncovered, member)
        }
    }
  }

  override def cleanup(ctx: Ctx): Unit = {
    flush()
    worst.flush(io)
    io.close()
  }
}
