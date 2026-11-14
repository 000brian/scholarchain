package org.scholarchain.hadoop.blocking

import java.text.Normalizer

import scala.jdk.CollectionConverters.*

import org.apache.hadoop.fs.Path
import org.apache.hadoop.io.{NullWritable, Text}
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat
import org.apache.hadoop.mapreduce.{Job, Reducer}
import org.scholarchain.core.{CodePointOrder, DblpAuthorName, MentionId}
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
}

/** Blocking key functions. Only families whose normalisation is already defined in core are
  * implemented; the work order's other families (initials, coauthor context, title, venue,
  * affiliation, source) need key definitions from entity-resolution-engineer published through a
  * core contract (stages never share code directly).
  */
object BlockingKeys {

  /** `dblp-base-name`: the dblp name without its homonym suffix (core `DblpAuthorName.split`), in
    * Unicode canonical decomposition (see [[canonical]]), e.g. `Ricardo Campos 0001` and `Ricardo
    * Campos` share the block `Ricardo Campos`.
    */
  val DblpBaseName = "dblp-base-name"

  val supported: Set[String] = Set(DblpBaseName)

  /** The key form of a name: Unicode Normalization Form D (canonical decomposition).
    *
    * Canonically equivalent strings (for example precomposed U+00E9 and `e` + U+0301) are the same
    * text by Unicode conformance clause C6 and have one NFD form, so they share a key. This is
    * canonical equivalence only: no compatibility folding (NFKD), case folding, accent stripping or
    * transliteration, which would be matching policy. Applied to keys and comparisons only; source
    * names are kept verbatim in every record.
    */
  def canonical(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD)

  def keys(family: String, c: MentionContext): List[String] = family match {
    case DblpBaseName => List(canonical(DblpAuthorName.split(c.name)._1))
    case other => throw SettingsError(Settings.Keys.BlockingFamilies, s"unsupported family $other")
  }
}

/** J05: bounded researcher blocking.
  *
  * Map in: J07 `mentionContexts/`. Map out: `<family>\t<key>` -> [[BlockMember]] JSON per
  * configured family. Reduce: buffers at most `maxBlockSize` members; larger blocks are not emitted
  * (OVERSIZE_BLOCKS, `oversizeBlocks/` lists key and size only), so a pathological common name
  * cannot produce a quadratic candidate set downstream. Singleton blocks are counted and emitted
  * only when configured. Outputs: `blocks/` ([[CandidateBlock]]), `oversizeBlocks/`. Stats:
  * largestBlock (including oversize blocks). `blocks/` always exists (empty when every block is a
  * singleton or oversize), since J06 reads it.
  */
object BlockingJob {
  val JobName = "hadoop.j05-blocking"
  val Blocks = "blocks"
  val OversizeBlocks = "oversizeBlocks"

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
      List(Blocks, OversizeBlocks),
      { (job: Job) =>
        val families = new Settings(job.getConfiguration).list(Settings.Keys.BlockingFamilies)
        val unknown = families.filterNot(BlockingKeys.supported)
        if (families.isEmpty || unknown.nonEmpty)
          throw SettingsError(
            Settings.Keys.BlockingFamilies,
            s"unsupported ${unknown.mkString(",")}; supported: ${BlockingKeys.supported.mkString(",")}"
          )
        job.getConfiguration
          .set(BoundedLineInputFormat.LimitKeyKey, Settings.Keys.InternalMaxLineBytes)
        job.setInputFormatClass(classOf[BoundedLineInputFormat])
        FileInputFormat.addInputPath(job, contexts)
        job.setMapperClass(classOf[BlockingMapper])
        job.setReducerClass(classOf[BlockingReducer])
      },
      alwaysPresentDirs = List(Blocks)
    )
}

class BlockingMapper extends ContractLineMapper {
  private var families: List[String] = Nil

  override def setup(ctx: Ctx): Unit = {
    super.setup(ctx)
    families = new Settings(ctx.getConfiguration).list(Settings.Keys.BlockingFamilies)
  }

  protected def line(text: String, location: String, ctx: Ctx): Unit =
    scala.util.Try(read[MentionContext](text)).toEither match {
      case Left(e) =>
        io.reject(Reasons.SchemaFailure, e.getMessage, None, Some(location), Some(text))
      case Right(c) =>
        io.count(Counters.Accepted)
        val member = write(BlockMember.of(c))
        families.foreach(f => BlockingKeys.keys(f, c).foreach(k => emit(s"$f\t$k", member, ctx)))
    }
}

class BlockingReducer extends Reducer[Text, Text, NullWritable, Text] {
  type Ctx = Reducer[Text, Text, NullWritable, Text]#Context
  private var io: TaskIO = null
  private var maxBlock = 0
  private var emitSingletons = false
  private val largest = new MaxTracker("largestBlock")

  override def setup(ctx: Ctx): Unit = {
    io = new TaskIO(ctx, reduceStage = true)
    val s = new Settings(ctx.getConfiguration)
    maxBlock = s.positiveInt(Settings.Keys.BlockingMaxBlock)
    emitSingletons = s.boolean(Settings.Keys.BlockingEmitSingletons)
  }

  override def reduce(key: Text, values: java.lang.Iterable[Text], ctx: Ctx): Unit = {
    val Array(family, blockKey) = key.toString.split("\t", 2)
    val members = List.newBuilder[BlockMember]
    var n = 0L
    values.asScala.foreach { v =>
      n += 1
      if (n <= maxBlock) members += read[BlockMember](v.toString)
    }
    largest.offer(n, key.toString)
    if (n > maxBlock) {
      io.count(Counters.OversizeBlock)
      io.emit(
        BlockingJob.OversizeBlocks,
        BlockingJob.OversizeBlocks,
        ujson
          .Obj(
            "family" -> family,
            "blockKey" -> blockKey,
            "size" -> n.toDouble,
            "limit" -> maxBlock.toDouble
          )
          .render()
      )
    } else if (n == 1 && !emitSingletons) io.count(Counters.SingletonBlocks)
    else {
      val ms = members
        .result()
        .distinctBy(_.mentionId)
        .sortWith((a, b) => CodePointOrder.lessThan(a.mentionId, b.mentionId))
      if (ms.size < n) io.count(Counters.Duplicate, n - ms.size)
      io.count(Counters.BlocksEmitted)
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
