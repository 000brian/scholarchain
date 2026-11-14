package org.scholarchain.contracts.hadoop

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.charset.{CharacterCodingException, Charset, CodingErrorAction, StandardCharsets}
import java.nio.file.{Files, Path, Paths}

import org.scholarchain.core.{
  AuthorMention,
  ContentHash,
  Provenance,
  RunId,
  SchemaVersions,
  SourceType,
  WorkId,
  WorkRecord,
  MentionId,
  DblpAuthorName
}
import org.scholarchain.hadoop.common.RunContext
import org.scholarchain.hadoop.dblp.*
import org.scholarchain.hadoop.io.{DblpRecordScanner, RawRecord, ScannedUnit}
import org.scholarchain.hadoop.teams.MentionContext

/** Shared fixtures for the Hadoop adversarial suites (test-engineer, tests/contracts).
  *
  * These suites drive the pure parts of the pipeline directly; the job-level suites (see
  * [[JobHarness]]) run the real MapReduce jobs with the local job runner. [[MapperModel]] chains
  * the pure parts in the order documented by `DblpExtractMapper.map`: scan, decode with the
  * declared charset, expand declared entities, parse, build. Status codes come from the real
  * `org.scholarchain.hadoop.io.RawRecord` companion.
  */
object AdversarialSupport {

  val BoundaryElements: Set[String] = Set(
    "article",
    "inproceedings",
    "proceedings",
    "book",
    "incollection",
    "phdthesis",
    "mastersthesis",
    "www",
    "data"
  )

  val run: RunContext = RunContext(
    RunId.of("hadoop.adversarial", "test", "sha256:in", "sha256:conf").toOption.get,
    "hadoop.adversarial",
    "dblp",
    "2026-11-01",
    "dblp-parse/1"
  )

  /** The default subset rule from scholarchain-hadoop-defaults.xml, restricted to fixture venues.
    */
  val rule: DblpSubsetRule = DblpSubsetRule(
    Set("article", "inproceedings"),
    enabled = true,
    List("conf/sigir/", "conf/kdd/", "journals/pvldb/"),
    2015,
    2025,
    Set("informal")
  )

  lazy val repoRoot: Path = {
    var p = Paths.get("").toAbsolutePath
    while (p != null && !Files.exists(p.resolve("datasets/dataset-manifest.yaml"))) p = p.getParent
    require(p != null, "repository root not found")
    p
  }

  def fixture(name: String): Array[Byte] =
    Files.readAllBytes(
      repoRoot.resolve("tests/contracts/src/test/resources/hadoop-adversarial").resolve(name)
    )

  def utf8(s: String): Array[Byte] = s.getBytes(StandardCharsets.UTF_8)

  /** Every unit one reader returns for the split `[start, end)` of `bytes`. */
  def scanSplit(
      bytes: Array[Byte],
      start: Int,
      end: Int,
      maxRecordBytes: Int = 1 << 20,
      elements: Set[String] = BoundaryElements
  ): List[ScannedUnit] = {
    val in = new ByteArrayInputStream(bytes, start, bytes.length - start)
    val s = new DblpRecordScanner(in, start.toLong, end.toLong, elements, maxRecordBytes)
    Iterator.continually(s.next()).takeWhile(_.nonEmpty).flatten.toList
  }

  def scanAll(bytes: Array[Byte], maxRecordBytes: Int = 1 << 20): List[ScannedUnit] =
    scanSplit(bytes, 0, bytes.length, maxRecordBytes)

  def text(u: ScannedUnit): String = new String(u.payload, StandardCharsets.UTF_8)

  /** Outcome of the modelled map step for one scanned unit. */
  sealed trait MapOutcome
  final case class Accepted(a: DblpOutcome.Accepted) extends MapOutcome
  final case class Excluded(reason: String) extends MapOutcome
  final case class Rejected(reason: String, detail: String) extends MapOutcome

  object MapperModel {
    def apply(
        u: ScannedUnit,
        entities: Map[String, String],
        charset: Charset = StandardCharsets.UTF_8,
        rule: DblpSubsetRule = AdversarialSupport.rule
    ): MapOutcome =
      if (u.status != RawRecord.Ok) Rejected(s"status-${u.status}", u.detail)
      else {
        val decoded =
          try
            Right(
              charset
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(u.payload))
                .toString
            )
          catch { case e: CharacterCodingException => Left(e.toString) }
        decoded match {
          case Left(e)     => Rejected("invalid_encoding", e)
          case Right(text) =>
            DblpEntities.expand(text, entities) match {
              case Left(name)      => Rejected("undefined_entity", name)
              case Right(expanded) =>
                new DblpRecordParser().parse(expanded) match {
                  case Left(err) => Rejected("malformed_xml", err)
                  case Right(f)  =>
                    DblpRecordBuilder.build(f, rule, run, text) match {
                      case a: DblpOutcome.Accepted       => Accepted(a)
                      case DblpOutcome.Excluded(r, _)    => Excluded(r)
                      case DblpOutcome.Rejected(r, d, _) => Rejected(r, d)
                    }
                }
            }
        }
      }
  }

  /** Builds one record string through the modelled map step. */
  def buildXml(xml: String, entities: Map[String, String] = Map.empty): MapOutcome = {
    val units = scanAll(utf8(xml))
    assert(units.size == 1, s"fixture must hold exactly one record, scanner found ${units.size}")
    MapperModel(units.head, entities)
  }

  def provenance(key: String, hash: String = "x"): Provenance =
    run.provenance(SourceType.Dblp, List(key), Some(ContentHash.of(hash)))

  /** A valid work and its mentions for `key` with `names` (author order kept). */
  def work(key: String, names: List[String], year: Int = 2020, title: String = "T"): WorkRecord = {
    val id = WorkId.fromDblpKey(key)
    WorkRecord(
      SchemaVersions.currentOf("WorkRecord"),
      id,
      key,
      "inproceedings",
      title,
      year,
      Some(key.split('/').take(2).mkString("/")),
      None,
      Nil,
      Nil,
      names,
      provenance(key)
    )
  }

  def mentions(w: WorkRecord): List[AuthorMention] =
    w.authorNames.zipWithIndex.map { case (n, i) =>
      AuthorMention(
        SchemaVersions.currentOf("AuthorMention"),
        MentionId.of(w.workId, i),
        w.workId,
        i,
        n,
        None,
        w.provenance
      )
    }

  def context(
      key: String,
      position: Int,
      name: String,
      doi: Option[String] = None
  ): MentionContext =
    MentionContext(
      MentionContext.Version,
      MentionId.of(WorkId.fromDblpKey(key), position).value,
      WorkId.fromDblpKey(key).value,
      key,
      position,
      DblpAuthorName.normalize(name).get,
      None,
      2020,
      Some(key.split('/').take(2).mkString("/")),
      doi,
      "T",
      2,
      Nil,
      coauthorsTruncated = false,
      "dblp",
      "2026-11-01"
    )
}
