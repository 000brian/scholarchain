package org.scholarchain.hadoop.blocking

import org.apache.hadoop.fs.Path
import org.apache.hadoop.io.{NullWritable, Text}
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat
import org.apache.hadoop.mapreduce.{Job, Reducer}
import org.scholarchain.core.{AuthorNameKey, CodePointOrder, Hashing, MentionId}
import org.scholarchain.hadoop.common.*
import org.scholarchain.hadoop.io.BoundedLineInputFormat
import org.scholarchain.hadoop.teams.{ContractLineMapper, MentionContext}
import upickle.default.{macroRW, read, write, ReadWriter}

/** A member of a candidate block: one author mention with the facts J06 compares (job contract
  * `scholarchain.hadoop.candidate-block`, see [[CandidateBlock]]). `coauthorNames` is the J07
  * mention context list (other names of the work, at most `scholarchain.teams.maxCoauthorContext`,
  * `coauthorsTruncated` when cut).
  */
final case class BlockMember(
    mentionId: String,
    workId: String,
    dblpKey: String,
    name: String,
    year: Int,
    orcid: Option[String],
    venueKey: Option[String],
    coauthorNames: List[String],
    coauthorsTruncated: Boolean
)
object BlockMember {
  implicit val rw: ReadWriter[BlockMember] = macroRW

  def of(c: MentionContext): BlockMember =
    BlockMember(
      c.mentionId,
      c.workId,
      c.dblpKey,
      c.name,
      c.year,
      c.orcid,
      c.venueKey,
      c.coauthorNames,
      c.coauthorsTruncated
    )
}

/** Job contract `scholarchain.hadoop.candidate-block`, version 2: mentions that share one blocking
  * key, read by J06 (candidate features). Members are distinct by mention ID and sorted by it (code
  * point); `size == members.size`.
  *
  * Written by J05 (primary families, `blockKey` is the family's name key) and by J05b (fallback
  * families `<primary family>.<secondary kind>`, `blockKey` is the JSON array of the primary key
  * followed by the secondary key parts, see [[FallbackKeys.blockKey]]).
  *
  * History: version 1 (first Phase 4 run) carried only mention ID, work ID, name and year; version
  * 2 adds the dblp key, ORCID iD, venue and coauthor context that J06 compares.
  */
final case class CandidateBlock(
    blockVersion: Int,
    family: String,
    blockKey: String,
    size: Int,
    members: List[BlockMember],
    policyVersion: String,
    runId: String
)
object CandidateBlock {
  implicit val rw: ReadWriter[CandidateBlock] = macroRW
  val Version = 2

  /** Structural problems of a block read back from J05 output (empty when well formed). */
  def problems(b: CandidateBlock): List[String] = {
    val ids = b.members.map(_.mentionId)
    List(
      Option.when(b.blockVersion != Version)(s"blockVersion ${b.blockVersion} != $Version"),
      Option.when(!b.family.matches("[a-z0-9][a-z0-9._-]*"))(s"family '${b.family}'"),
      Option.when(b.blockKey.trim.isEmpty || b.blockKey.exists(Character.isISOControl))(
        "blockKey is blank or has control characters"
      ),
      Option.when(b.size != b.members.size)(s"size ${b.size} != ${b.members.size} members"),
      Option.when(ids.zip(ids.drop(1)).exists { case (x, y) => !CodePointOrder.lessThan(x, y) })(
        "members are not distinct and sorted by mention ID"
      ),
      Option.when(!ids.forall(MentionId.isWellFormed))("a member mention ID is malformed"),
      Option.when(b.policyVersion.trim.isEmpty)("policyVersion is blank")
    ).flatten
  }

  /** Members of one block: distinct by mention ID, sorted by it (code point). */
  def normalise(members: Iterable[BlockMember]): List[BlockMember] =
    members.toList
      .distinctBy(_.mentionId)
      .sortWith((a, b) => CodePointOrder.lessThan(a.mentionId, b.mentionId))
}

/** Job contract `scholarchain.hadoop.oversize-member`, version 1: one member of a primary block
  * that exceeded the effective J05 cap ([[BlockLimit]]: `scholarchain.blocking.maxBlockSize` or the
  * pair limit `scholarchain.candidates.maxPairsPerBlock`). Written by J05 to `oversizeMembers/`,
  * read by J05b (fallback re-blocking) and J05c (coverage). `family` and `blockKey` name the
  * oversize primary block.
  */
final case class OversizeMember(
    memberVersion: Int,
    family: String,
    blockKey: String,
    member: BlockMember
)
object OversizeMember {
  implicit val rw: ReadWriter[OversizeMember] = macroRW
  val Version = 1

  /** Structural problems (empty when well formed). */
  def problems(m: OversizeMember): List[String] =
    List(
      Option.when(m.memberVersion != Version)(s"memberVersion ${m.memberVersion} != $Version"),
      Option.when(!BlockingKeys.supported(m.family))(s"family '${m.family}' is not a block family"),
      Option.when(m.blockKey.trim.isEmpty || m.blockKey.exists(Character.isISOControl))(
        "blockKey is blank or has control characters"
      ),
      Option.when(!MentionId.isWellFormed(m.member.mentionId))("member mention ID is malformed")
    ).flatten
}

/** The effective member cap of an emitted block (J05 primary and J05b fallback blocks).
  *
  * J06 expands a block of m members only when its m(m-1)/2 pairs are at most
  * `scholarchain.candidates.maxPairsPerBlock`. So that no emitted block is one J06 refuses to
  * expand, J05 and J05b treat a block as oversize when it has more members than the configured
  * member cap OR more pairs than that pair limit, i.e. when m exceeds
  * `maxMembers(memberCap, maxPairsPerBlock)`, the largest m within the member cap whose pairs fit.
  * Oversize blocks are not emitted; their members are routed to the fallback (J05) or counted and
  * listed as uncovered (J05b, J05c), as for blocks over the member cap. With the defaults
  * (maxBlockSize 200, fallback maxBlockSize 200, maxPairsPerBlock 125000; Phase 14 repair G-17) the
  * member cap binds: the J05 and J05b caps are 200 members (19,900 pairs).
  */
final case class BlockLimit(memberCap: Int, maxPairs: Long) {

  /** Largest member count whose pairs fit the pair limit, at most `memberCap`. */
  val effective: Int = BlockLimit.maxMembers(memberCap, maxPairs)

  /** True when the pair limit, not the member cap, sets [[effective]]. */
  val pairBound: Boolean = effective < memberCap

  /** Why a block of `n` members is oversize: `max_block_size` (more members than the member cap) or
    * `max_pairs_per_block` (within the member cap, more pairs than the pair limit).
    */
  def reason(n: Long): String =
    if (n > memberCap) BlockLimit.MemberReason else BlockLimit.PairReason

  /** The fields of an oversize-block listing line (limits and pair count of a block of `n`). */
  def listing(n: Long): List[(String, ujson.Value)] =
    List(
      "limit" -> ujson.Num(effective.toDouble),
      "maxBlockSize" -> ujson.Num(memberCap.toDouble),
      "maxPairsPerBlock" -> ujson.Num(maxPairs.toDouble),
      "pairs" -> ujson.Num(BlockLimit.pairs(n).toDouble),
      "reason" -> ujson.Str(reason(n))
    )
}
object BlockLimit {
  val MemberReason = "max_block_size"
  val PairReason = "max_pairs_per_block"

  /** Pairs of a block of `n` members (Double-free; `n` is at most a few billion here). */
  def pairs(n: Long): Long = if (n < 2) 0L else n * (n - 1) / 2

  /** Largest m in [1, memberCap] with m(m-1)/2 <= maxPairs (binary search, no overflow: m is an
    * Int, so m(m-1)/2 fits a Long).
    */
  def maxMembers(memberCap: Int, maxPairs: Long): Int = {
    require(memberCap > 0 && maxPairs > 0, s"limits must be > 0: $memberCap, $maxPairs")
    if (pairs(memberCap.toLong) <= maxPairs) memberCap
    else {
      var lo = 1 // pairs(1) == 0 <= maxPairs
      var hi = memberCap // pairs(hi) > maxPairs
      while (hi - lo > 1) {
        val mid = lo + (hi - lo) / 2
        if (pairs(mid.toLong) <= maxPairs) lo = mid else hi = mid
      }
      lo
    }
  }

  /** The limit of a block job: `memberCapKey` with `scholarchain.candidates.maxPairsPerBlock`. */
  def of(s: Settings, memberCapKey: String): BlockLimit =
    BlockLimit(s.positiveInt(memberCapKey), s.positiveLong(Settings.Keys.CandidateMaxPairsPerBlock))
}

/** Primary blocking key families. Every name key comes from the shared core rule
  * [[org.scholarchain.core.AuthorNameKey]] (`author-name-key/1`); this module keeps no name
  * normalisation of its own.
  */
object BlockingKeys {

  /** `dblp-base-name`: [[AuthorNameKey.baseNameKey]], the dblp name without its homonym suffix in
    * Unicode canonical decomposition (case and punctuation kept), e.g. `Ricardo Campos 0001` and
    * `Ricardo Campos` share the block `Ricardo Campos`.
    */
  val DblpBaseName = "dblp-base-name"

  /** `initials-surname`: [[AuthorNameKey.initialsSurnameKey]], the folded initials of every token
    * but the last plus the last token, e.g. `J. Smith`, `John Smith`, `Jane Smith 0003` all share
    * `j smith`. A name without letters or digits has no key (counted in
    * [[Counters.NoBlockKeyGroup]]).
    */
  val InitialsSurname = "initials-surname"

  val supported: Set[String] = Set(DblpBaseName, InitialsSurname)

  /** The keys of `c` in `family` (at most one today; empty when the family yields none). Blank keys
    * are never returned. Unsupported families fail with [[SettingsError]].
    */
  def keys(family: String, c: MentionContext): List[String] = {
    val k = family match {
      case DblpBaseName    => Some(AuthorNameKey.baseNameKey(c.name))
      case InitialsSurname => AuthorNameKey.initialsSurnameKey(c.name)
      case other           =>
        throw SettingsError(Settings.Keys.BlockingFamilies, s"unsupported family $other")
    }
    k.filter(_.trim.nonEmpty).toList
  }

  /** The configured primary families; fails when empty or unsupported. */
  def configured(s: Settings): List[String] = {
    val families = s.list(Settings.Keys.BlockingFamilies)
    val unknown = families.filterNot(supported)
    if (families.isEmpty || unknown.nonEmpty)
      throw SettingsError(
        Settings.Keys.BlockingFamilies,
        s"unsupported ${unknown.mkString(",")}; supported: ${supported.toList.sorted.mkString(",")}"
      )
    families.distinct
  }
}

/** Secondary keys of the oversize-block fallback (J05b). A member of an oversize primary block
  * `(family, key)` is re-blocked under fallback family `<family>.<kind>` by the primary key plus
  * one secondary value per kind:
  *
  *   - `coauthor`: each distinct [[AuthorNameKey.fullNameKey]] of the member's J07 coauthor names
  *     (one fallback block per shared coauthor dblp name, homonym suffix included). Members without
  *     coauthors have none.
  *   - `venue`: the work's dblp venue key, when present.
  *   - `coauthor-venue`: each (coauthor full-name key, venue key) combination; none without a venue
  *     or without coauthors.
  *
  * Values with control characters (none in J07 output) are dropped, so the tab-separated reduce
  * keys stay unambiguous.
  */
object FallbackKeys {
  val Coauthor = "coauthor"
  val Venue = "venue"
  val CoauthorVenue = "coauthor-venue"

  val supported: Set[String] = Set(Coauthor, Venue, CoauthorVenue)

  def fallbackFamily(primary: String, kind: String): String = s"$primary.$kind"

  /** (primary family, kind) of a fallback family name; primary family names contain no `.`. */
  def split(fallbackFamily: String): (String, String) = {
    val i = fallbackFamily.lastIndexOf('.')
    (fallbackFamily.substring(0, i), fallbackFamily.substring(i + 1))
  }

  private def clean(s: String): Boolean = s.trim.nonEmpty && !s.exists(Character.isISOControl)

  /** Secondary values of `m` for `kind`: distinct, sorted (code point), each a list of parts. */
  def values(kind: String, m: BlockMember): List[List[String]] = {
    lazy val coauthors =
      m.coauthorNames.map(AuthorNameKey.fullNameKey).filter(clean).distinct
    lazy val venue = m.venueKey.filter(clean).toList
    val parts: List[List[String]] = kind match {
      case Coauthor      => coauthors.map(List(_))
      case Venue         => venue.map(List(_))
      case CoauthorVenue => for { c <- coauthors; v <- venue } yield List(c, v)
      case other         =>
        throw SettingsError(Settings.Keys.FallbackSecondaryKeys, s"unsupported secondary $other")
    }
    parts.distinct.sortWith((a, b) => CodePointOrder.lessThan(a.mkString("\t"), b.mkString("\t")))
  }

  /** The block key of a fallback block: the JSON array `[primaryKey, part...]`, which is
    * unambiguous for any key text and has no control characters.
    */
  def blockKey(primaryKey: String, parts: List[String]): String =
    ujson.Arr.from((primaryKey :: parts).map(ujson.Str(_))).render()

  /** The configured fallback families and secondary kinds; fails on unsupported values. */
  def configured(s: Settings): (List[String], List[String]) = {
    val families = s.list(Settings.Keys.FallbackFamilies).distinct
    val kinds = s.list(Settings.Keys.FallbackSecondaryKeys).distinct
    val badF = families.filterNot(BlockingKeys.supported)
    if (badF.nonEmpty)
      throw SettingsError(Settings.Keys.FallbackFamilies, s"unsupported ${badF.mkString(",")}")
    val badK = kinds.filterNot(supported)
    if (badK.nonEmpty)
      throw SettingsError(
        Settings.Keys.FallbackSecondaryKeys,
        s"unsupported ${badK.mkString(",")}; supported: ${supported.toList.sorted.mkString(",")}"
      )
    (families, kinds)
  }
}

/** The J05 map-output key: a secondary sort that fixes the order of the values of one block.
  *
  * Key: `<family>\t<block key>\t<mention ID>\t<sha256 hex of the member JSON>`. Partitioned by
  * [[TwoFieldPrefixPartitioner]] and grouped by [[TwoFieldPrefixGroupingComparator]] on
  * `<family>\t<block key>` (neither contains a tab: families are validated names, and every block
  * key comes from [[AuthorNameKey]], which collapses all whitespace to U+0020), so one `reduce`
  * call receives the whole block, sorted by mention ID and then by the content hash. Only
  * byte-identical member copies tie, so the reduce input order, and every J05 output file, is the
  * same whatever the map task layout and merge order. Partition assignment equals the Phase 4 one
  * (the stable hash of `<family>\t<block key>`).
  */
object BlockingSort {
  def key(family: String, blockKey: String, mentionId: String, memberJson: String): String =
    s"$family\t$blockKey\t$mentionId\t${Hashing.sha256Hex(memberJson)}"

  /** (family, block key) of a J05 map-output key. */
  def block(key: String): (String, String) = {
    val f = key.indexOf('\t')
    val k = key.indexOf('\t', f + 1)
    (key.substring(0, f), if (k < 0) key.substring(f + 1) else key.substring(f + 1, k))
  }
}

/** J05: bounded researcher blocking.
  *
  * Map in: J07 `mentionContexts/`. Map out: [[BlockingSort.key]] (`<family>\t<key>` plus the
  * mention ID and content hash as secondary sort fields; one reduce call per `<family>\t<key>`,
  * values in that fixed order, so every output file is byte-deterministic) -> [[BlockMember]] JSON
  * per configured family ([[BlockingKeys]]; mentions without a key in a family are counted per
  * family in [[Counters.NoBlockKeyGroup]]). Reduce: buffers at most the effective cap of
  * [[BlockLimit]] (`min(maxBlockSize, members whose pairs fit
  * scholarchain.candidates.maxPairsPerBlock)`) members; larger blocks are not emitted
  * (OVERSIZE_BLOCKS; those within `maxBlockSize` but over the pair limit also
  * PAIR_LIMIT_OVERSIZE_BLOCKS; `oversizeBlocks/` lists key, size, limits, pairs and reason), so no
  * emitted block is one J06 refuses to expand and a pathological common name cannot produce a
  * quadratic candidate set downstream. Every member of an oversize block is streamed to
  * `oversizeMembers/` ([[OversizeMember]], OVERSIZE_BLOCK_MEMBERS) for the fallback (J05b) and its
  * coverage accounting (J05c); memory stays bounded by the effective cap. Singleton blocks are
  * counted and emitted only when configured. Outputs: `blocks/` ([[CandidateBlock]]),
  * `oversizeBlocks/`, `oversizeMembers/`. Per-family counters: [[Counters.BlocksPerFamilyGroup]],
  * [[Counters.OversizePerFamilyGroup]]. Stats: largestBlock (including oversize blocks). `blocks/`
  * and `oversizeMembers/` always exist (possibly empty), since J06, J05b and J05c read them.
  */
object BlockingJob {
  val JobName = "hadoop.j05-blocking"
  val Blocks = "blocks"
  val OversizeBlocks = "oversizeBlocks"
  val OversizeMembers = "oversizeMembers"

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
      List(Blocks, OversizeBlocks, OversizeMembers),
      { (job: Job) =>
        BlockingKeys.configured(new Settings(job.getConfiguration))
        BlockLimit.of(new Settings(job.getConfiguration), Settings.Keys.BlockingMaxBlock)
        job.getConfiguration
          .set(BoundedLineInputFormat.LimitKeyKey, Settings.Keys.InternalMaxLineBytes)
        job.setInputFormatClass(classOf[BoundedLineInputFormat])
        FileInputFormat.addInputPath(job, contexts)
        job.setMapperClass(classOf[BlockingMapper])
        // secondary sort (BlockingSort): one reduce call per block, values in a fixed order
        job.setPartitionerClass(classOf[TwoFieldPrefixPartitioner])
        job.setGroupingComparatorClass(classOf[TwoFieldPrefixGroupingComparator])
        job.setReducerClass(classOf[BlockingReducer])
      },
      alwaysPresentDirs = List(Blocks, OversizeMembers)
    )
}

class BlockingMapper extends ContractLineMapper {
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
        val member = write(BlockMember.of(c))
        families.foreach { f =>
          BlockingKeys.keys(f, c) match {
            case Nil => io.countIn(Counters.NoBlockKeyGroup, f)
            case ks  =>
              ks.foreach(k => emit(BlockingSort.key(f, k, c.mentionId, member), member, ctx))
          }
        }
    }
}

class BlockingReducer extends Reducer[Text, Text, NullWritable, Text] {
  type Ctx = Reducer[Text, Text, NullWritable, Text]#Context
  private var io: TaskIO = null
  private var limit: BlockLimit = null
  // effective member cap: min(maxBlockSize, members whose pairs fit maxPairsPerBlock)
  private var maxBlock = 0
  private var emitSingletons = false
  private val largest = new MaxTracker("largestBlock")

  override def setup(ctx: Ctx): Unit = {
    io = new TaskIO(ctx, reduceStage = true)
    val s = new Settings(ctx.getConfiguration)
    limit = BlockLimit.of(s, Settings.Keys.BlockingMaxBlock)
    maxBlock = limit.effective
    emitSingletons = s.boolean(Settings.Keys.BlockingEmitSingletons)
  }

  private def oversizeMember(family: String, blockKey: String, json: String): Unit = {
    io.count(Counters.OversizeMembers)
    io.emit(
      BlockingJob.OversizeMembers,
      BlockingJob.OversizeMembers,
      write(OversizeMember(OversizeMember.Version, family, blockKey, read[BlockMember](json)))
    )
  }

  override def reduce(key: Text, values: java.lang.Iterable[Text], ctx: Ctx): Unit = {
    // `key` is the first key of the group; values arrive sorted by mention ID, then content hash
    val (family, blockKey) = BlockingSort.block(key.toString)
    val subject = s"$family\t$blockKey"
    val buffer = scala.collection.mutable.ArrayBuffer.empty[String]
    var n = 0L
    val it = values.iterator()
    while (it.hasNext) {
      val v = it.next().toString
      n += 1
      if (n <= maxBlock) buffer += v
      else {
        // The block is oversize: stream its members out instead of buffering them.
        if (n == maxBlock + 1L) {
          buffer.foreach(oversizeMember(family, blockKey, _))
          buffer.clear()
        }
        oversizeMember(family, blockKey, v)
      }
    }
    largest.offer(n, subject)
    if (n > maxBlock) {
      io.count(Counters.OversizeBlock)
      io.countIn(Counters.OversizePerFamilyGroup, family)
      if (limit.reason(n) == BlockLimit.PairReason) io.count(Counters.PairLimitOversizeBlocks)
      io.emit(
        BlockingJob.OversizeBlocks,
        BlockingJob.OversizeBlocks,
        ujson.Obj
          .from(
            List[(String, ujson.Value)](
              "family" -> family,
              "blockKey" -> blockKey,
              "size" -> n.toDouble
            ) ++ limit.listing(n)
          )
          .render()
      )
    } else if (n == 1 && !emitSingletons) io.count(Counters.SingletonBlocks)
    else {
      val ms = CandidateBlock.normalise(buffer.map(read[BlockMember](_)))
      if (ms.size < n) io.count(Counters.Duplicate, n - ms.size)
      io.count(Counters.BlocksEmitted)
      io.countIn(Counters.BlocksPerFamilyGroup, family)
      io.emit(
        BlockingJob.Blocks,
        BlockingJob.Blocks,
        write(
          CandidateBlock(
            CandidateBlock.Version,
            family,
            blockKey,
            ms.size,
            ms,
            io.run.policyVersion,
            io.run.runId.value
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
