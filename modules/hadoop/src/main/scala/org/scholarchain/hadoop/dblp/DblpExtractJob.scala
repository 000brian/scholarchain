package org.scholarchain.hadoop.dblp

import java.nio.ByteBuffer
import java.nio.charset.{CharacterCodingException, Charset, CodingErrorAction}

import scala.jdk.CollectionConverters.*

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.hadoop.io.{LongWritable, NullWritable, Text}
import org.apache.hadoop.mapreduce.lib.input.{FileInputFormat, FileSplit}
import org.apache.hadoop.mapreduce.{Job, Mapper, Reducer}
import org.scholarchain.core.{
  AuthorMention,
  CodePointOrder,
  Contract,
  Hashing,
  SchemaVersions,
  SourceType,
  Venue,
  WorkRecord
}
import org.scholarchain.hadoop.common.*
import org.scholarchain.hadoop.io.{DblpXmlInputFormat, InputOpener, RawRecord}
import upickle.default.{macroRW, read, write, ReadWriter}

/** Shuffle value of J01 for a work key (job contract, internal to J01). */
final case class DblpParsed(work: WorkRecord, mentions: List[AuthorMention])
object DblpParsed { implicit val rw: ReadWriter[DblpParsed] = macroRW }

/** J01: dblp XML to WorkRecord, AuthorMention and Venue.
  *
  * Map in: (byte offset, [[RawRecord]] = one complete record element from [[DblpXmlInputFormat]]).
  * Map out: `W|<dblp key>` -> [[DblpParsed]] JSON; `V|<venue key>` -> venue label. Reduce: works
  * and mentions per dblp key (identical copies collapsed and counted as duplicates; differing
  * copies rejected as conflicts); one Venue per venue key (name = most frequent label, ties broken
  * by smallest label by code point). Outputs: `works/`, `mentions/`, `venues/`, `rejects/v1/`,
  * `excluded/v1/`, `issues/v1/`.
  */
object DblpExtractJob {
  val JobName = "hadoop.j01-dblp-extract"
  val DatasetId = "dblp"
  val WorkPrefix = "W|"
  val VenuePrefix = "V|"

  val Works = "works"
  val Mentions = "mentions"
  val Venues = "venues"

  /** The J01 input fingerprint: `rawFingerprint` (of the dblp files) alone when
    * `scholarchain.dblp.dtdPath` is unset; otherwise a hash over it and
    * `JobRunner.fingerprintFiles` of the DTD file, so a changed DTD content (same path) changes the
    * run ID. (The path string itself is part of the configuration fingerprint.)
    */
  def inputFingerprint(conf: Configuration, rawFingerprint: String): String =
    new Settings(Settings.withDefaults(conf)).optional(Settings.Keys.DblpDtdPath) match {
      case None      => rawFingerprint
      case Some(dtd) =>
        val dtdFp = JobRunner.fingerprintFiles(conf, List(new Path(dtd)))
        "sha256:" + Hashing.sha256Hex(s"dblp\t$rawFingerprint\ndtd\t$dtdFp")
    }

  def spec(input: Path, outputRoot: Path, snapshotId: String, inputFingerprint: String): JobSpec =
    JobSpec(
      JobName,
      DatasetId,
      snapshotId,
      Settings.Keys.DblpParsePolicy,
      inputFingerprint,
      outputRoot,
      s"$DatasetId/snapshot=$snapshotId",
      Settings.Keys.DblpReducers,
      List(Works, Mentions, Venues),
      { (job: Job) =>
        job.setInputFormatClass(classOf[DblpXmlInputFormat])
        FileInputFormat.addInputPath(job, input)
        FileInputFormat.setInputDirRecursive(job, true)
        job.setMapperClass(classOf[DblpExtractMapper])
        job.setReducerClass(classOf[DblpExtractReducer])
      }
    )
}

class DblpExtractMapper extends Mapper[LongWritable, RawRecord, Text, Text] {
  type Ctx = Mapper[LongWritable, RawRecord, Text, Text]#Context

  private var io: TaskIO = null
  private var rule: DblpSubsetRule = null
  private var parser: DblpRecordParser = null
  private var charset: Charset = null
  private var entities: Map[String, String] = Map.empty
  private var maxExpanded = 0
  private var fileError: Option[(String, String)] = None
  private val outKey = new Text()
  private val outValue = new Text()
  private val KeyAttr = """key\s*=\s*"([^"]*)"""".r

  override def setup(ctx: Ctx): Unit = {
    io = new TaskIO(ctx, reduceStage = false)
    val conf = ctx.getConfiguration
    val s = new Settings(conf)
    rule = DblpSubsetRule.from(s)
    parser = new DblpRecordParser
    val maxProlog = s.positiveInt(Settings.Keys.DblpMaxPrologBytes)
    maxExpanded = s.positiveInt(Settings.Keys.DblpMaxExpandedChars)
    val limit = s.positiveLong(Settings.Keys.MaxDecompressedBytes)
    val path = ctx.getInputSplit.asInstanceOf[FileSplit].getPath
    val opened =
      InputOpener.open(conf, path, 0L, path.getFileSystem(conf).getFileStatus(path).getLen, limit)
    val prolog =
      try DblpEntities.readProlog(opened.stream, maxProlog)
      catch { case e: java.io.IOException => Left(s"cannot read prolog: ${e.getMessage}") }
      finally opened.close()
    val supported = s.list(Settings.Keys.DblpSupportedEncodings).map(_.toUpperCase)
    prolog match {
      case Left(e)            => fileError = Some(Reasons.PrologNotFound -> e)
      case Right((enc, text)) =>
        if (!supported.contains(enc.toUpperCase))
          fileError = Some(Reasons.UnsupportedEncoding -> s"declared encoding $enc")
        else {
          charset = Charset.forName(enc)
          val external = s.optional(Settings.Keys.DblpDtdPath).map { p =>
            val dtd = new Path(p)
            val in = dtd.getFileSystem(conf).open(dtd)
            try
              DblpEntities.readBounded(in, maxProlog) match {
                case Some(bytes) => DblpEntities.declarations(new String(bytes, charset))
                case None        =>
                  throw SettingsError(Settings.Keys.DblpDtdPath, s"DTD larger than $maxProlog")
              }
            finally in.close()
          }
          // The internal subset is read first, so its declarations bind (XML 1.0 section 4.2).
          entities = external.getOrElse(Map.empty) ++ DblpEntities.declarations(text)
        }
    }
  }

  private def keyOf(text: String): Option[String] = KeyAttr.findFirstMatchIn(text).map(_.group(1))

  override def map(offset: LongWritable, rec: RawRecord, ctx: Ctx): Unit = {
    io.count(Counters.Input)
    val loc = Some(rec.location)
    if (rec.status != RawRecord.Ok) {
      val preview = new String(rec.payload, java.nio.charset.StandardCharsets.ISO_8859_1)
      val reason = rec.status match {
        case RawRecord.Oversize           => Reasons.OversizeRecord
        case RawRecord.Truncated          => Reasons.TruncatedRecord
        case RawRecord.DecompressionLimit => Reasons.DecompressionLimit
        case _                            => Reasons.TruncatedInput
      }
      io.reject(reason, rec.detail, keyOf(preview), loc, Some(preview))
      return
    }
    fileError match {
      case Some((reason, detail)) =>
        val text = new String(rec.payload, java.nio.charset.StandardCharsets.ISO_8859_1)
        io.reject(reason, detail, keyOf(text), loc, Some(text))
        return
      case None => ()
    }
    val text =
      try
        charset
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(rec.payload))
          .toString
      catch {
        case e: CharacterCodingException =>
          val latin = new String(rec.payload, java.nio.charset.StandardCharsets.ISO_8859_1)
          io.reject(
            Reasons.InvalidEncoding,
            s"not valid $charset: ${e.getMessage}",
            keyOf(latin),
            loc,
            Some(latin)
          )
          return
      }
    val expanded = DblpEntities.expand(text, entities, maxExpanded) match {
      case Left(ExpandError.Undefined(name)) =>
        io.reject(Reasons.UndefinedEntity, s"&$name; is not declared", keyOf(text), loc, Some(text))
        return
      case Left(ExpandError.TooLarge(atLeast, max)) =>
        io.reject(
          Reasons.ExpandedRecordOversize,
          s"entity expansion reaches at least $atLeast characters, more than " +
            s"${Settings.Keys.DblpMaxExpandedChars}=$max",
          keyOf(text),
          loc,
          Some(text)
        )
        return
      case Right(x) => x
    }
    val fields = parser.parse(expanded) match {
      case Left(err) =>
        io.reject(Reasons.MalformedXml, err, keyOf(text), loc, Some(text))
        return
      case Right(f) => f
    }
    DblpRecordBuilder.build(fields, rule, io.run, text) match {
      case DblpOutcome.Rejected(reason, detail, key) =>
        io.reject(reason, detail, key, loc, Some(text))
      case DblpOutcome.Excluded(reason, key)                   => io.exclude(reason, Some(key), loc)
      case DblpOutcome.Accepted(work, mentions, label, issues) =>
        io.count(Counters.Accepted)
        issues.foreach { i =>
          if (i.reason == Reasons.ConflictingDois) io.count(Counters.Conflict)
          io.issue(i.reason, i.detail, Some(work.dblpKey), loc)
        }
        outKey.set(DblpExtractJob.WorkPrefix + work.dblpKey)
        outValue.set(write(DblpParsed(work, mentions)))
        ctx.write(outKey, outValue)
        (work.venueKey, label) match {
          case (Some(v), Some(l)) =>
            outKey.set(DblpExtractJob.VenuePrefix + v)
            outValue.set(l)
            ctx.write(outKey, outValue)
          case _ => ()
        }
    }
  }

  override def cleanup(ctx: Ctx): Unit = io.close()
}

class DblpExtractReducer extends Reducer[Text, Text, NullWritable, Text] {
  type Ctx = Reducer[Text, Text, NullWritable, Text]#Context
  private var io: TaskIO = null

  override def setup(ctx: Ctx): Unit = io = new TaskIO(ctx, reduceStage = true)

  override def reduce(key: Text, values: java.lang.Iterable[Text], ctx: Ctx): Unit = {
    val k = key.toString
    if (k.startsWith(DblpExtractJob.WorkPrefix))
      reduceWork(k.stripPrefix(DblpExtractJob.WorkPrefix), values.asScala.map(_.toString).toList)
    else reduceVenue(k.stripPrefix(DblpExtractJob.VenuePrefix), values.asScala.map(_.toString))
  }

  private def reduceWork(dblpKey: String, all: List[String]): Unit = {
    val distinct = all.distinct.sorted
    if (distinct.size == 1) {
      if (all.size > 1) io.count(Counters.Duplicate, all.size - 1L)
      val p = read[DblpParsed](distinct.head)
      io.emit(DblpExtractJob.Works, DblpExtractJob.Works, write(p.work))
      p.mentions
        .sortBy(_.position)
        .foreach(m => io.emit(DblpExtractJob.Mentions, DblpExtractJob.Mentions, write(m)))
    } else {
      io.count(Counters.Conflict, all.size.toLong)
      all.sorted.foreach(v =>
        io.reject(
          Reasons.DuplicateKeyConflict,
          s"${all.size} records share dblp key with ${distinct.size} different contents",
          Some(dblpKey),
          None,
          Some(v)
        )
      )
    }
  }

  private def reduceVenue(venueKey: String, labels: Iterable[String]): Unit = {
    val counts = scala.collection.mutable.Map.empty[String, Long]
    labels.foreach(l => counts(l) = counts.getOrElse(l, 0L) + 1L)
    val name = counts.toList
      .sortWith { case ((a, ca), (b, cb)) =>
        ca > cb || (ca == cb && CodePointOrder.lessThan(a, b))
      }
      .head
      ._1
    val venue = Venue(
      SchemaVersions.currentOf("Venue"),
      venueKey,
      name,
      io.run.provenance(SourceType.Dblp, List(venueKey), None)
    )
    Contract.encode(venue) match {
      case Right(json) => io.emit(DblpExtractJob.Venues, DblpExtractJob.Venues, json)
      case Left(e) => io.reject(Reasons.SchemaFailure, e.message, Some(venueKey), None, Some(name))
    }
  }

  override def cleanup(ctx: Ctx): Unit = io.close()
}
