package org.scholarchain.er.data

import java.nio.file.{Files, Path}

import scala.collection.mutable

import org.scholarchain.core.{
  AuthorMention,
  CodePointOrder,
  Contract,
  ExternalIdLink,
  LinkSubjectKind,
  Venue,
  WorkRecord
}
import org.scholarchain.er.io.{DataFiles, ErError, FileDigest}
import org.scholarchain.er.normalize.{NameParts, TitleText, WorkIdentifiers}

final class IntBuf(initial: Int = 1024) {
  private var a = new Array[Int](initial)
  var size = 0
  def +=(x: Int): Unit = {
    if (size == a.length) a = java.util.Arrays.copyOf(a, a.length * 2)
    a(size) = x
    size += 1
  }
  def apply(i: Int): Int = a(i)
  def toArray: Array[Int] = java.util.Arrays.copyOf(a, size)
}

final class LongBuf(initial: Int = 1024) {
  private var a = new Array[Long](initial)
  var size = 0
  def +=(x: Long): Unit = {
    if (size == a.length) a = java.util.Arrays.copyOf(a, a.length * 2)
    a(size) = x
    size += 1
  }
  def apply(i: Int): Long = a(i)
}

/** The J09 graph-export job manifest fields entity resolution records. */
final case class ExportJob(
    jobName: String,
    runId: String,
    snapshotId: String,
    codeVersion: String,
    inputRuns: List[String]
)

/** The J09 export directory layout entity resolution reads (one `run=<id>` directory). */
object ExportLayout {
  val Mentions = "author_mention"
  val Works = "work"
  val Venues = "venue"
  val Candidates = "match_candidate"
  val Links = "external_id_link"
  val JobFile = "_job.json"
  val Success = "_SUCCESS"

  def readJob(dir: Path): ExportJob = {
    val f = dir.resolve(JobFile)
    if (!Files.isRegularFile(f)) throw new ErError(s"$f: missing J09 job manifest")
    if (!Files.isRegularFile(dir.resolve(Success)))
      throw new ErError(s"$dir: no $Success marker; the J09 export is incomplete")
    val j = ujson.read(DataFiles.readUtf8(f)).obj
    def s(k: String) = j.get(k).map(_.str).getOrElse(throw new ErError(s"$f: missing '$k'"))
    ExportJob(
      s("jobName"),
      s("runId"),
      s("snapshotId"),
      s("codeVersion"),
      j.get("inputRuns").map(_.arr.toList.map(_.str)).getOrElse(Nil)
    )
  }

  /** Data files of every entity entity resolution reads, relative to `dir`, sorted. */
  def inputFiles(dir: Path): List[(String, Path)] =
    List(Mentions, Works, Venues, Candidates, Links).flatMap(e =>
      DataFiles.list(dir.resolve(e)).map(p => dir.relativize(p).toString -> p)
    )
}

final case class LoadedInput(
    corpus: Corpus,
    workLinks: List[ExternalIdLink],
    linksPresent: Boolean,
    digests: List[FileDigest]
)

object Loader {

  private def decodeAll[A](dir: Path, root: Path, digests: mutable.ListBuffer[FileDigest])(
      f: A => Unit
  )(implicit rw: upickle.default.ReadWriter[A], v: org.scholarchain.core.Validator[A]): Unit =
    DataFiles.list(dir).foreach { file =>
      val label = root.relativize(file).toString
      digests += DataFiles.readLines(file, label) { (line, n) =>
        Contract.decode[A](line) match {
          case Right(a) => f(a)
          case Left(e)  => throw new ErError(s"$label:$n: ${e.message}")
        }
      }
    }

  def load(dir: Path, snapshotId: String): LoadedInput = {
    val digests = mutable.ListBuffer.empty[FileDigest]

    // Works.
    val wHi = new LongBuf; val wLo = new LongBuf
    val wRecords = mutable.ArrayBuffer.empty[WorkRecord]
    decodeAll[WorkRecord](dir.resolve(ExportLayout.Works), dir, digests) { w =>
      IdCodec.parse(WorkTable.Prefix, w.workId.value) match {
        case Some((h, l)) => wHi += h; wLo += l; wRecords += w
        case None         => throw new ErError(s"malformed work ID ${w.workId}")
      }
    }
    if (wRecords.isEmpty) throw new ErError(s"$dir: no work records")
    val wOrder = (0 until wRecords.size).toArray.sortWith((a, b) =>
      IdCodec.compare(wHi(a), wLo(a), wHi(b), wLo(b)) < 0
    )
    val tokenIds = mutable.HashMap.empty[String, Int]
    def tokenArray(title: String): Array[Int] =
      TitleText.tokens(title).map(t => tokenIds.getOrElseUpdate(t, tokenIds.size)).toArray.sorted
    val nW = wRecords.size
    val sortedW = wOrder.map(wRecords)
    for (i <- 1 until nW)
      if (
        IdCodec.compare(wHi(wOrder(i - 1)), wLo(wOrder(i - 1)), wHi(wOrder(i)), wLo(wOrder(i))) == 0
      )
        throw new ErError(s"duplicate work ${sortedW(i).workId}")
    val works = new WorkTable(
      wOrder.map(wHi(_)),
      wOrder.map(wLo(_)),
      sortedW.map(_.dblpKey),
      sortedW.map(_.title),
      sortedW.map(_.year),
      sortedW.map(_.venueKey.orNull),
      sortedW.map(_.doi.orNull),
      sortedW.map(w => w.authorNames.toArray),
      sortedW.map(w => WorkIdentifiers.of(w.doi, w.eeLinks)),
      sortedW.map(w => tokenArray(w.title))
    )
    wRecords.clear()

    // Venues.
    val venues = mutable.HashMap.empty[String, String]
    decodeAll[Venue](dir.resolve(ExportLayout.Venues), dir, digests)(v =>
      venues(v.venueKey) = v.name
    )

    // Mentions.
    val mHi = new LongBuf; val mLo = new LongBuf
    val mWork = new IntBuf; val mPos = new IntBuf; val mName = new IntBuf
    val mOrcid = mutable.ArrayBuffer.empty[String]
    // dblp-prior clusters (decision 5): one per exact dblp name string as the mention record holds
    // it, homonym suffix included, with no further normalization (canonically equivalent NFC and
    // NFD spellings are different prior clusters; they join only through accepted decisions).
    val nameIndex = mutable.HashMap.empty[String, Int]
    decodeAll[AuthorMention](dir.resolve(ExportLayout.Mentions), dir, digests) { m =>
      val (h, l) = IdCodec
        .parse(MentionTable.Prefix, m.mentionId.value)
        .getOrElse(throw new ErError(s"malformed mention ID ${m.mentionId}"))
      val w = works.indexOf(m.workId.value)
      if (w < 0) throw new ErError(s"mention ${m.mentionId}: unknown work ${m.workId}")
      val k = nameIndex.getOrElseUpdate(m.name, nameIndex.size)
      mHi += h; mLo += l; mWork += w; mPos += m.position; mName += k
      mOrcid += m.orcid.orNull
    }
    val nM = mHi.size
    if (nM == 0) throw new ErError(s"$dir: no author mentions")
    val spellings = nameIndex.toArray.sortWith((a, b) => CodePointOrder.lessThan(a._1, b._1))
    val remap = new Array[Int](spellings.length)
    spellings.zipWithIndex.foreach { case ((_, old), neu) => remap(old) = neu }
    val names = new NameTable(
      spellings.map { case (s, _) => s },
      spellings.map { case (s, _) => NameParts.of(s) }
    )
    val mOrder =
      (0 until nM).toArray.sortWith((a, b) => IdCodec.compare(mHi(a), mLo(a), mHi(b), mLo(b)) < 0)
    for (i <- 1 until nM)
      if (
        IdCodec.compare(mHi(mOrder(i - 1)), mLo(mOrder(i - 1)), mHi(mOrder(i)), mLo(mOrder(i))) == 0
      )
        throw new ErError(
          s"duplicate mention ${IdCodec.format(MentionTable.Prefix, mHi(mOrder(i)), mLo(mOrder(i)))}"
        )
    val links = Array.fill[List[MentionLink]](nM)(Nil)
    val mentions = new MentionTable(
      mOrder.map(mHi(_)),
      mOrder.map(mLo(_)),
      mOrder.map(mWork(_)),
      mOrder.map(mPos(_)),
      mOrder.map(i => remap(mName(i))),
      mOrder.map(mOrcid(_)),
      links
    )

    // J04 exact links (absent in runs without enrichment data).
    val linkDir = dir.resolve(ExportLayout.Links)
    val linksPresent = Files.isDirectory(linkDir)
    val workLinks = mutable.ArrayBuffer.empty[ExternalIdLink]
    decodeAll[ExternalIdLink](linkDir, dir, digests) { l =>
      l.subjectKind match {
        case LinkSubjectKind.Mention =>
          val i = mentions.indexOf(l.subjectId)
          if (i < 0) throw new ErError(s"link ${l.linkId}: unknown mention ${l.subjectId}")
          links(i) =
            (MentionLink(l.idType.wire, l.externalId, l.provenance.sourceType.wire) :: links(
              i
            )).distinct
              .sortBy(x => (x.idType, x.value, x.sourceType))
        case LinkSubjectKind.Work  => workLinks += l
        case LinkSubjectKind.Venue => ()
      }
    }

    LoadedInput(
      new Corpus(names, works, mentions, venues.toMap, snapshotId),
      workLinks.toList.sortBy(_.linkId.value),
      linksPresent,
      digests.toList
    )
  }
}
