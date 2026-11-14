package org.scholarchain.hadoop.candidates

import scala.jdk.CollectionConverters.*

import org.apache.hadoop.fs.Path
import org.apache.hadoop.io.{NullWritable, Text}
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat
import org.apache.hadoop.mapreduce.{Job, Reducer}
import org.scholarchain.core.{
  CodePointOrder,
  Contract,
  DblpAuthorName,
  MatchCandidate,
  MatchCandidateId,
  MatchFeature,
  MatchSubjectKind,
  SchemaVersions,
  SourceType
}
import org.scholarchain.hadoop.blocking.{BlockingKeys, BlockMember, CandidateBlock}
import org.scholarchain.hadoop.common.*
import org.scholarchain.hadoop.io.BoundedLineInputFormat
import org.scholarchain.hadoop.pairs.PairIndex
import org.scholarchain.hadoop.teams.ContractLineMapper
import upickle.default.{macroRW, read, write, ReadWriter}

/** Feature set `candidate-features/1-provisional`: deterministic, policy-free comparisons of two
  * block members, computed only from the facts J05 carries. No thresholds, weights, scores or
  * decisions; which features exist and how they are used is entity-resolution policy (Phase 5).
  *
  * Name comparisons are exact under Unicode canonical equivalence (both sides in NFD,
  * [[org.scholarchain.hadoop.blocking.BlockingKeys.canonical]]): `José` precomposed and decomposed
  * are equal, nothing else is folded.
  *
  * Features (names sorted by code point in every candidate; a feature that cannot be computed is
  * omitted, never defaulted):
  *   - `coauthor_shared_count`: number of distinct exact coauthor names the two mentions share.
  *     Omitted when either coauthor context was truncated (the count would be a lower bound).
  *   - `homonym_suffix_match`: 1 when both dblp names carry the same homonym suffix (`0001`), 0
  *     when both carry different ones. Omitted unless both names carry a suffix.
  *   - `name_exact_match`: 1 when the full dblp names (suffix included) are canonically equivalent,
  *     else 0.
  *   - `orcid_exact_match`: 1 when both dblp ORCID iDs are equal, else 0. Omitted unless both
  *     mentions carry one.
  *   - `same_venue`: 1 when both works have the same dblp venue key, else 0. Omitted unless both
  *     have one.
  *   - `same_work`: 1 when both mentions are on the same work, else 0.
  *   - `year_abs_diff`: absolute difference of the two publication years.
  */
object CandidateFeatures {
  val FeatureSet = "candidate-features/1-provisional"

  val CoauthorSharedCount = "coauthor_shared_count"
  val HomonymSuffixMatch = "homonym_suffix_match"
  val NameExactMatch = "name_exact_match"
  val OrcidExactMatch = "orcid_exact_match"
  val SameVenue = "same_venue"
  val SameWork = "same_work"
  val YearAbsDiff = "year_abs_diff"

  /** Every feature name of the set, sorted by code point. */
  val names: List[String] = List(
    CoauthorSharedCount,
    HomonymSuffixMatch,
    NameExactMatch,
    OrcidExactMatch,
    SameVenue,
    SameWork,
    YearAbsDiff
  ).sorted(CodePointOrder.ordering)

  private def flag(b: Boolean): Double = if (b) 1.0 else 0.0

  /** The computable features of a pair, sorted by name (symmetric in `a` and `b`). */
  def compute(a: BlockMember, b: BlockMember): List[MatchFeature] = {
    val shared =
      if (a.coauthorsTruncated || b.coauthorsTruncated) None
      else {
        def names(m: BlockMember) = m.coauthorNames.map(BlockingKeys.canonical).toSet
        Some(names(a).intersect(names(b)).size.toDouble)
      }
    val suffix = (DblpAuthorName.split(a.name)._2, DblpAuthorName.split(b.name)._2) match {
      case (Some(x), Some(y)) => Some(flag(x == y))
      case _                  => None
    }
    def both[T](x: Option[T], y: Option[T]): Option[Double] =
      for { p <- x; q <- y } yield flag(p == q)
    List(
      shared.map(MatchFeature(CoauthorSharedCount, _)),
      suffix.map(MatchFeature(HomonymSuffixMatch, _)),
      Some(
        MatchFeature(
          NameExactMatch,
          flag(BlockingKeys.canonical(a.name) == BlockingKeys.canonical(b.name))
        )
      ),
      both(a.orcid, b.orcid).map(MatchFeature(OrcidExactMatch, _)),
      both(a.venueKey, b.venueKey).map(MatchFeature(SameVenue, _)),
      Some(MatchFeature(SameWork, flag(a.workId == b.workId))),
      Some(MatchFeature(YearAbsDiff, math.abs(a.year - b.year).toDouble))
    ).flatten.sortWith((x, y) => CodePointOrder.lessThan(x.name, y.name))
  }

  /** Feature names left out of `features` (for the per-feature omission counters). */
  def omitted(features: List[MatchFeature]): List[String] =
    names.filterNot(features.map(_.name).toSet)

  /** One `mention_pair` candidate for members `a` and `b` of `block`.
    *
    * Provenance: the dblp keys of the two mentions' works (left first, distinct) under this job's
    * run; `policyVersion` is `<feature set>;<block policy version>`. No content hash: the record
    * carries no source text.
    */
  def candidate(
      block: CandidateBlock,
      a: BlockMember,
      b: BlockMember,
      run: RunContext
  ): Either[String, MatchCandidate] = {
    val (l, r) = if (CodePointOrder.lessThan(a.mentionId, b.mentionId)) (a, b) else (b, a)
    MatchCandidateId
      .of(MatchSubjectKind.MentionPair, l.mentionId, r.mentionId, block.family, block.blockKey)
      .flatMap { id =>
        val prov = run
          .provenance(SourceType.Dblp, List(l.dblpKey, r.dblpKey).distinct, None)
          .copy(policyVersion = s"${run.policyVersion};${block.policyVersion}")
        val c = MatchCandidate(
          SchemaVersions.currentOf("MatchCandidate"),
          id,
          MatchSubjectKind.MentionPair,
          l.mentionId,
          r.mentionId,
          block.family,
          block.blockKey,
          compute(l, r),
          prov
        )
        Contract.validate(c) match {
          case Nil  => Right(c)
          case errs => Left(errs.mkString("; "))
        }
      }
  }
}

/** Shuffle value of J06: one shard of one block (job contract, internal to J06). */
final case class BlockShard(block: CandidateBlock, shard: Long, from: Long, until: Long)
object BlockShard { implicit val rw: ReadWriter[BlockShard] = macroRW }

/** J06: candidate feature construction.
  *
  * Map in: J05 `blocks/` ([[CandidateBlock]] v2; structural problems are rejected as
  * schema_failure). A block of m members has m(m-1)/2 member pairs: blocks with fewer than two
  * members yield none (SINGLETON_BLOCKS); blocks above `scholarchain.candidates.maxPairsPerBlock`
  * pairs are not expanded (OVERSIZE_BLOCKS, OVERSIZE_BLOCK_PAIRS_SKIPPED, `oversizeBlocks/` lists
  * family, key, size and pairs). Map out: `<family>\t<blockKey>\t<shard>` -> [[BlockShard]] for
  * each of ceil(pairs / pairsPerShard) shards (CANDIDATE_SHARDS); the stable hash partitioner
  * spreads the shards of one block over the reducers.
  *
  * Reduce: streams the shard's member pairs (row-major over members sorted by mention ID) into
  * `candidates/family=<family>/` as [[MatchCandidate]] records (`mention_pair`,
  * MATCH_CANDIDATES_EMITTED); per-feature omissions are counted in
  * [[Counters.FeatureOmittedGroup]]. Identical copies of a shard collapse (DUPLICATE); different
  * blocks under one key are rejected (CONFLICT). Stats: largestCandidateGroup (most candidates from
  * one expanded block), largestBlockSeen (largest block read, including oversize ones).
  */
object CandidateFeatureJob {
  val JobName = "hadoop.j06-candidate-features"
  val Candidates = "candidates"
  val OversizeBlocks = "oversizeBlocks"

  def candidatesDir(family: String): String = s"$Candidates/family=$family"

  def spec(
      blocks: Path,
      outputRoot: Path,
      snapshotId: String,
      inputFingerprint: String
  ): JobSpec =
    JobSpec(
      JobName,
      "dblp",
      snapshotId,
      Settings.Keys.CandidateFeatureSet,
      inputFingerprint,
      outputRoot,
      s"dblp/snapshot=$snapshotId",
      Settings.Keys.CandidateReducers,
      List(Candidates, OversizeBlocks),
      { (job: Job) =>
        checkFeatureSet(new Settings(job.getConfiguration))
        job.getConfiguration
          .set(BoundedLineInputFormat.LimitKeyKey, Settings.Keys.InternalMaxLineBytes)
        job.setInputFormatClass(classOf[BoundedLineInputFormat])
        FileInputFormat.addInputPath(job, blocks)
        job.setMapperClass(classOf[BlockShardMapper])
        job.setReducerClass(classOf[CandidateFeatureReducer])
      }
    )

  /** The configured feature set must be the one this code implements. */
  def checkFeatureSet(s: Settings): Unit = {
    val configured = s.string(Settings.Keys.CandidateFeatureSet)
    if (configured != CandidateFeatures.FeatureSet)
      throw SettingsError(
        Settings.Keys.CandidateFeatureSet,
        s"'$configured' is not implemented (implemented: ${CandidateFeatures.FeatureSet})"
      )
  }
}

class BlockShardMapper extends ContractLineMapper {
  private var maxPairs = 0L
  private var perShard = 0L
  private val largestSeen = new MaxTracker("largestBlockSeen")
  private val largestGroup = new MaxTracker("largestCandidateGroup")

  override def setup(ctx: Ctx): Unit = {
    super.setup(ctx)
    val s = new Settings(ctx.getConfiguration)
    CandidateFeatureJob.checkFeatureSet(s)
    maxPairs = s.positiveLong(Settings.Keys.CandidateMaxPairsPerBlock)
    perShard = s.positiveLong(Settings.Keys.CandidatePairsPerShard)
  }

  protected def line(text: String, location: String, ctx: Ctx): Unit =
    scala.util
      .Try(read[CandidateBlock](text))
      .toEither
      .left
      .map(e => Option(e.getMessage).getOrElse(e.getClass.getName))
      .flatMap(b =>
        CandidateBlock.problems(b) match {
          case Nil  => Right(b)
          case errs => Left(errs.mkString("; "))
        }
      ) match {
      case Left(err) => io.reject(Reasons.SchemaFailure, err, None, Some(location), Some(text))
      case Right(b)  =>
        io.count(Counters.Accepted)
        val subject = s"${b.family}\t${b.blockKey}"
        val pairs = PairIndex.pairCount(b.size)
        largestSeen.offer(b.size.toLong, subject)
        if (pairs == 0) io.count(Counters.SingletonBlocks)
        else if (pairs > maxPairs) {
          io.count(Counters.OversizeBlock)
          io.count(Counters.OversizeBlockPairsSkipped, pairs)
          io.emit(
            CandidateFeatureJob.OversizeBlocks,
            CandidateFeatureJob.OversizeBlocks,
            ujson
              .Obj(
                "family" -> b.family,
                "blockKey" -> b.blockKey,
                "size" -> b.size.toDouble,
                "pairs" -> pairs.toDouble,
                "limit" -> maxPairs.toDouble
              )
              .render()
          )
        } else {
          largestGroup.offer(pairs, subject)
          val shards = PairIndex.shardCount(b.size, perShard)
          var s = 0L
          while (s < shards) {
            val (from, until) = PairIndex.shardRange(b.size, perShard, s)
            io.count(Counters.CandidateShards)
            emit(f"$subject\t$s%09d", write(BlockShard(b, s, from, until)), ctx)
            s += 1
          }
        }
    }

  override def cleanup(ctx: Ctx): Unit = {
    largestSeen.flush(io)
    largestGroup.flush(io)
    super.cleanup(ctx)
  }
}

class CandidateFeatureReducer extends Reducer[Text, Text, NullWritable, Text] {
  type Ctx = Reducer[Text, Text, NullWritable, Text]#Context
  private var io: TaskIO = null

  override def setup(ctx: Ctx): Unit = io = new TaskIO(ctx, reduceStage = true)

  override def reduce(key: Text, values: java.lang.Iterable[Text], ctx: Ctx): Unit = {
    val all = values.asScala.map(_.toString).toList
    val shards = all.distinct.sorted
    if (all.size > shards.size) io.count(Counters.Duplicate, (all.size - shards.size).toLong)
    if (shards.size > 1) {
      io.count(Counters.Conflict, shards.size.toLong)
      shards.foreach(s =>
        io.reject(
          Reasons.DuplicateKeyConflict,
          "different blocks for one block shard",
          Some(key.toString),
          None,
          Some(s)
        )
      )
      return
    }
    val shard = read[BlockShard](shards.head)
    val b = shard.block
    val members = b.members.toArray
    val dir = CandidateFeatureJob.candidatesDir(b.family)
    PairIndex.foreachPair(members.length, shard.from, shard.until) { (i, j) =>
      CandidateFeatures.candidate(b, members(i), members(j), io.run) match {
        case Right(c) =>
          io.count(Counters.CandidatesEmitted)
          CandidateFeatures
            .omitted(c.features)
            .foreach(n => io.countIn(Counters.FeatureOmittedGroup, n))
          io.emit(CandidateFeatureJob.Candidates, dir, write(c))
        case Left(err) =>
          io.reject(
            Reasons.SchemaFailure,
            s"${members(i).mentionId} / ${members(j).mentionId}: $err",
            Some(s"${b.family}\t${b.blockKey}"),
            None,
            None
          )
      }
    }
  }

  override def cleanup(ctx: Ctx): Unit = io.close()
}
