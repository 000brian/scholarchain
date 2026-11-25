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

  /** The J05b map-output key (and J05bp count key) of one fallback group:
    * `<fallback family>\t<primary key>\t<value parts, tab-separated>`.
    */
  def reduceKey(fallbackFamily: String, primaryKey: String, parts: List[String]): String =
    (fallbackFamily :: primaryKey :: parts).mkString("\t")

  /** Every fallback group key of `m` for the configured fallback `families` and `kinds` (empty when
    * its primary family has no fallback), in kind order then value order.
    */
  def reduceKeys(m: OversizeMember, families: Set[String], kinds: List[String]): List[String] =
    if (!families(m.family)) Nil
    else
      for {
        kind <- kinds
        parts <- values(kind, m.member)
      } yield reduceKey(fallbackFamily(m.family, kind), m.blockKey, parts)

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
  * [[BlockingPartitioner]] and grouped by [[BlockingGroupingComparator]] on `<family>\t<block key>`
  * (2 fields; no tab inside: families are validated names, and [[AuthorNameKey]] collapses all
  * whitespace to U+0020), so one `reduce` call receives the whole block, sorted by mention ID, then
  * content hash. Only byte-identical copies tie, so the reduce input order, and every J05 output
  * file, is the same whatever the map task layout and merge order; a block's partition equals the
  * Phase 4 one (stable hash of `<family>\t<block key>`). Oversize keys use [[memberKey]] (4-field
  * member groups) and [[sizeKey]] (3-field marker groups), see [[groupFields]].
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

  /** First field of a re-keyed oversize member key. `#` cannot start a family name (families match
    * `[a-z0-9][a-z0-9._-]*`), so tagged keys never collide with block member keys.
    */
  val MemberTag = "#member"

  /** First field of an oversize block size marker key. */
  val SizeTag = "#size"

  /** Key of a member of an oversize primary key, re-keyed by its mention ID:
    * `#member\t<family>\t<block key>\t<mention ID>\t<content hash>`. Grouped on the first four
    * fields, so one reduce call holds the copies of one mention only, never the whole block.
    */
  def memberKey(family: String, blockKey: String, mentionId: String, memberJson: String): String =
    s"$MemberTag\t${key(family, blockKey, mentionId, memberJson)}"

  /** Key of the per-map-task size marker of an oversize block: `#size\t<family>\t<block key>`; the
    * value is the number of member values the task re-keyed for it.
    */
  def sizeKey(family: String, blockKey: String): String = s"$SizeTag\t$family\t$blockKey"

  /** Number of leading tab-separated fields that form the reduce group of `key`: 4 for re-keyed
    * members, 3 for size markers, 2 for block members.
    */
  def groupFields(startsWithMember: Boolean, startsWithSize: Boolean): Int =
    if (startsWithMember) 4 else if (startsWithSize) 3 else 2

  /** The fields of `key` that form its reduce group (see [[groupFields]]). */
  def groupPrefix(key: String): String =
    fieldPrefix(
      key,
      groupFields(key.startsWith(MemberTag + "\t"), key.startsWith(SizeTag + "\t"))
    )

  /** The partitioning prefix of `key`: `<family>\t<block key>` for block members and size markers
    * (so every partition assignment of a block equals the Phase 4 one),
    * `<family>\t<block key>\t<mention ID>` for re-keyed members (spread over all reduce tasks).
    */
  def partitionPrefix(key: String): String =
    if (key.startsWith(MemberTag + "\t")) {
      val rest = key.substring(MemberTag.length + 1)
      fieldPrefix(rest, 3)
    } else if (key.startsWith(SizeTag + "\t")) key.substring(SizeTag.length + 1)
    else TwoFieldPrefixPartitioner.prefix(key)

  private def fieldPrefix(s: String, fields: Int): String = {
    var i = -1
    var n = 0
    while (n < fields) {
      i = s.indexOf('\t', i + 1)
      if (i < 0) return s
      n += 1
    }
    s.substring(0, i)
  }
}

/** J05 partitioner: stable hash of [[BlockingSort.partitionPrefix]]. */
class BlockingPartitioner extends org.apache.hadoop.mapreduce.Partitioner[Text, Text] {
  override def getPartition(key: Text, value: Text, numPartitions: Int): Int =
    StableHashPartitioner.partition(BlockingSort.partitionPrefix(key.toString), numPartitions)
}

/** J05 grouping comparator: keys belong to one reduce call when their first
  * [[BlockingSort.groupFields]] tab-separated fields are equal (UTF-8 byte order, the order of the
  * default [[Text]] sort restricted to that prefix, so each group is contiguous in the sorted
  * reduce input). Block member keys group exactly as with [[TwoFieldPrefixGroupingComparator]].
  */
class BlockingGroupingComparator
    extends org.apache.hadoop.io.WritableComparator(classOf[Text], false) {

  private def prefixLength(b: Array[Byte], start: Int, len: Int): Int = {
    val fields = BlockingGroupingComparator.fieldsOf(b, start, len)
    var tabs = 0
    var i = 0
    while (i < len) {
      if (b(start + i) == '\t') {
        tabs += 1
        if (tabs == fields) return i
      }
      i += 1
    }
    len
  }

  override def compare(
      b1: Array[Byte],
      s1: Int,
      l1: Int,
      b2: Array[Byte],
      s2: Int,
      l2: Int
  ): Int = {
    val h1 = org.apache.hadoop.io.WritableUtils.decodeVIntSize(b1(s1))
    val h2 = org.apache.hadoop.io.WritableUtils.decodeVIntSize(b2(s2))
    val p1 = prefixLength(b1, s1 + h1, l1 - h1)
    val p2 = prefixLength(b2, s2 + h2, l2 - h2)
    org.apache.hadoop.io.WritableComparator.compareBytes(b1, s1 + h1, p1, b2, s2 + h2, p2)
  }

  override def compare(
      a: org.apache.hadoop.io.WritableComparable[?],
      b: org.apache.hadoop.io.WritableComparable[?]
  ): Int = {
    val x = a.asInstanceOf[Text]
    val y = b.asInstanceOf[Text]
    org.apache.hadoop.io.WritableComparator.compareBytes(
      x.getBytes,
      0,
      prefixLength(x.getBytes, 0, x.getLength),
      y.getBytes,
      0,
      prefixLength(y.getBytes, 0, y.getLength)
    )
  }
}

object BlockingGroupingComparator {
  private val member = (BlockingSort.MemberTag + "\t").getBytes("UTF-8")
  private val size = (BlockingSort.SizeTag + "\t").getBytes("UTF-8")

  private def startsWith(b: Array[Byte], start: Int, len: Int, p: Array[Byte]): Boolean =
    len >= p.length && {
      var i = 0
      while (i < p.length && b(start + i) == p(i)) i += 1
      i == p.length
    }

  def fieldsOf(b: Array[Byte], start: Int, len: Int): Int =
    BlockingSort.groupFields(startsWith(b, start, len, member), startsWith(b, start, len, size))
}

/** J05: bounded researcher blocking.
  *
  * Map in: J07 `mentionContexts/`. Map out: [[BlockingSort.key]] ([[BlockingPartitioner]] and
  * [[BlockingGroupingComparator]]: one reduce call per 2-field block `<family>\t<key>`, values in a
  * fixed order, so every output file is byte-deterministic; members of oversize keys go to 4-field
  * member groups and 3-field marker groups instead, see below) -> [[BlockMember]] JSON per
  * configured family ([[BlockingKeys]]; mentions without a key in a family are counted per family
  * in [[Counters.NoBlockKeyGroup]]). Reduce: buffers at most the effective cap of [[BlockLimit]]
  * (`min(maxBlockSize, members whose pairs fit scholarchain.candidates.maxPairsPerBlock)`) members;
  * larger blocks are not emitted (OVERSIZE_BLOCKS; those within `maxBlockSize` but over the pair
  * limit also PAIR_LIMIT_OVERSIZE_BLOCKS; `oversizeBlocks/` lists key, size, limits, pairs and
  * reason), so no emitted block is one J06 refuses to expand and a pathological common name cannot
  * produce a quadratic candidate set downstream. Every member of an oversize block is streamed to
  * `oversizeMembers/` ([[OversizeMember]], OVERSIZE_BLOCK_MEMBERS) for the fallback (J05b) and its
  * coverage accounting (J05c); memory stays bounded by the effective cap. Singleton blocks are
  * counted and emitted only when configured. Outputs: `blocks/` ([[CandidateBlock]]),
  * `oversizeBlocks/`, `oversizeMembers/`. Per-family counters: [[Counters.BlocksPerFamilyGroup]],
  * [[Counters.OversizePerFamilyGroup]]. Stats: largestBlock (including oversize blocks). `blocks/`
  * and `oversizeMembers/` always exist (possibly empty), since J06, J05b and J05c read them.
  *
  * Oversize strategy (Phase 17 R3, check HADOOP-04). The pipeline runs the counting pre-pass J05p
  * ([[BlockSizeJob]]) first and passes its `oversizeKeys/` to this job (`oversizeKeys`). Every map
  * task loads that list ([[OversizeKeySet]]); a member of a listed key is not sent to the block's
  * group but re-keyed by its mention ID ([[BlockingSort.memberKey]], OVERSIZE_MEMBERS_REKEYED), and
  * the task adds one size marker per listed key it saw ([[BlockingSort.sizeKey]]). So:
  *   - block groups hold at most the cap of members (they are only the keys J05p found within it);
  *   - a re-keyed member group holds the copies of one mention, and those groups are spread over
  *     all reduce tasks by `<family>\t<key>\t<mention ID>` ([[BlockingPartitioner]]);
  *   - a size marker group holds one value per map task that saw the key (bounded by the map task
  *     count, not by the block size); its reducer sums them to the block size and lists the block
  *     in `oversizeBlocks/` with the counters above. It is not counted in
  *     [[BlockingJob.LargestReduceGroupStat]], so that stat stays within the cap and does not
  *     depend on the map-task layout.
  * The outputs (records, counters, stats) are those of the reducer-side cap, which stays as a guard
  * for a job run without the pre-pass (`oversizeKeys` = None): there the whole oversize group
  * reaches one reduce call, is streamed and counted as REDUCER_CAPPED_BLOCKS (0 in the pipeline).
  * The fallback keys and the still-oversize policy for the re-keyed members are those of J05b.
  */
object BlockingJob {
  val JobName = "hadoop.j05-blocking"
  val Blocks = "blocks"
  val OversizeBlocks = "oversizeBlocks"
  val OversizeMembers = "oversizeMembers"

  /** Stat (J05 and J05b): the most member values one reduce call read, with its group key prefix.
    * Size marker values are not counted, and a group of size markers only is not offered: such a
    * group holds one decimal count per map task that saw its key, so its value count depends on the
    * map-task layout (at most the number of map tasks), not on the data. With the pre-pass the stat
    * therefore never exceeds the job's effective cap (block groups and fallback groups within the
    * cap, and re-keyed member groups holding the copies of one mention), and it is the same for
    * every map-task layout. Without the pre-pass (reducer-side cap only) it is the size of the
    * largest group, oversize groups included.
    */
  val LargestReduceGroupStat = "largestReduceGroup"

  /** @param oversizeKeys
    *   the `oversizeKeys/` directory of the J05p run over the same `contexts` with the same
    *   configuration (its run ID belongs in this job's input runs); None runs without the pre-pass
    *   (reducer-side cap only)
    */
  def spec(
      contexts: Path,
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
      Settings.Keys.BlockingReducers,
      List(Blocks, OversizeBlocks, OversizeMembers),
      { (job: Job) =>
        BlockingKeys.configured(new Settings(job.getConfiguration))
        BlockLimit.of(new Settings(job.getConfiguration), Settings.Keys.BlockingMaxBlock)
        job.getConfiguration
          .set(BoundedLineInputFormat.LimitKeyKey, Settings.Keys.InternalMaxLineBytes)
        job.setInputFormatClass(classOf[BoundedLineInputFormat])
        FileInputFormat.addInputPath(job, contexts)
        oversizeKeys.foreach(p =>
          job.getConfiguration.set(Settings.Keys.OversizeKeysPath, p.toString)
        )
        job.setMapperClass(classOf[BlockingMapper])
        // secondary sort (BlockingSort): one reduce call per block, values in a fixed order;
        // re-keyed oversize members group per mention, size markers per oversize block
        job.setPartitionerClass(classOf[BlockingPartitioner])
        job.setGroupingComparatorClass(classOf[BlockingGroupingComparator])
        job.setReducerClass(classOf[BlockingReducer])
      },
      alwaysPresentDirs = List(Blocks, OversizeMembers)
    )
}

class BlockingMapper extends ContractLineMapper {
  private var families: List[String] = Nil
  // `<family>\t<key>` of the oversize keys found by J05p (empty without the pre-pass)
  private var oversize: Set[String] = Set.empty
  private val diverted = new DivertedSizes

  override def setup(ctx: Ctx): Unit = {
    super.setup(ctx)
    val s = new Settings(ctx.getConfiguration)
    families = BlockingKeys.configured(s)
    oversize = OversizeKeySet.configured(
      ctx.getConfiguration,
      BlockLimit.of(s, Settings.Keys.BlockingMaxBlock).effective
    )
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
              ks.foreach { k =>
                if (oversize.contains(s"$f\t$k")) {
                  io.count(Counters.OversizeMembersRekeyed)
                  diverted.add(s"$f\t$k")
                  emit(BlockingSort.memberKey(f, k, c.mentionId, member), member, ctx)
                } else emit(BlockingSort.key(f, k, c.mentionId, member), member, ctx)
              }
          }
        }
    }

  override def cleanup(ctx: Ctx): Unit = {
    diverted.sorted.foreach { case (fk, n) =>
      val (f, k) = BlockingSort.block(fk)
      emit(BlockingSort.sizeKey(f, k), n.toString, ctx)
    }
    super.cleanup(ctx)
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
  // member values read by one reduce call, size marker groups excluded (at most the cap with the
  // pre-pass, whatever the map-task layout)
  private val groups = new MaxTracker(BlockingJob.LargestReduceGroupStat)

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
    val k = key.toString
    // Size marker groups are not offered to `largestReduceGroup`: they hold one decimal count per
    // map task that saw the key, so their value count depends on the split layout, not on the data
    // (their summed size is reported by largestBlock and oversizeBlocks/).
    if (k.startsWith(BlockingSort.SizeTag + "\t")) sizeMarkers(k, values)
    else {
      val counted = new CountingValues(values)
      if (k.startsWith(BlockingSort.MemberTag + "\t")) rekeyedMember(k, counted)
      else block(k, counted)
      groups.offer(counted.count, BlockingSort.groupPrefix(k))
    }
  }

  /** The copies of one re-keyed member of an oversize block (pre-pass path): streamed out. */
  private def rekeyedMember(k: String, values: java.lang.Iterable[Text]): Unit = {
    val (family, blockKey) = BlockingSort.block(k.substring(BlockingSort.MemberTag.length + 1))
    val it = values.iterator()
    while (it.hasNext) oversizeMember(family, blockKey, it.next().toString)
  }

  /** The size markers of one oversize block (pre-pass path): one value per map task. */
  private def sizeMarkers(k: String, values: java.lang.Iterable[Text]): Unit = {
    val (family, blockKey) = BlockingSort.block(k.substring(BlockingSort.SizeTag.length + 1))
    val n = BlockSizeCounts.sum(values)
    largest.offer(n, s"$family\t$blockKey")
    oversizeBlock(family, blockKey, n)
  }

  private def block(k: String, values: java.lang.Iterable[Text]): Unit = {
    // `k` is the first key of the group; values arrive sorted by mention ID, then content hash
    val (family, blockKey) = BlockingSort.block(k)
    val subject = s"$family\t$blockKey"
    val buffer = scala.collection.mutable.ArrayBuffer.empty[String]
    var n = 0L
    val it = values.iterator()
    while (it.hasNext) {
      val v = it.next().toString
      n += 1
      if (n <= maxBlock) buffer += v
      else {
        // Guard for a run without the pre-pass: the oversize group reached this reduce call;
        // stream its members out instead of buffering them.
        if (n == maxBlock + 1L) {
          buffer.foreach(oversizeMember(family, blockKey, _))
          buffer.clear()
        }
        oversizeMember(family, blockKey, v)
      }
    }
    largest.offer(n, subject)
    if (n > maxBlock) {
      io.count(Counters.ReducerCappedBlocks)
      oversizeBlock(family, blockKey, n)
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

  /** Counts and lists an oversize block of `n` member values. */
  private def oversizeBlock(family: String, blockKey: String, n: Long): Unit = {
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
  }

  override def cleanup(ctx: Ctx): Unit = {
    largest.flush(io)
    groups.flush(io)
    io.close()
  }
}

/** Reduce values that count how many were read (for the `largestReduceGroup` stat). */
final class CountingValues(values: java.lang.Iterable[Text]) extends java.lang.Iterable[Text] {
  var count = 0L
  override def iterator(): java.util.Iterator[Text] = {
    val it = values.iterator()
    new java.util.Iterator[Text] {
      def hasNext: Boolean = it.hasNext
      def next(): Text = { count += 1; it.next() }
    }
  }
}
