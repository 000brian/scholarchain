package org.scholarchain.er

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.collection.mutable

import org.scholarchain.core.*
import org.scholarchain.er.data.{Corpus, Loader}

/** Synthetic J09 exports for tests (clearly synthetic names, keys and identifiers). Candidates are
  * generated like Hadoop J05/J06: blocks by `dblp-base-name` (base-name key) and `initials-surname`
  * (initials-plus-surname key), every member pair of a block of at most `maxBlock` members, with
  * the `candidate-features/1-provisional` features.
  */
object Fixture {

  final case class W(
      key: String,
      authors: List[String],
      year: Int = 2020,
      venue: String = "conf/sigir",
      title: String = "Synthetic Retrieval Models",
      orcids: Map[Int, String] = Map.empty,
      doi: Option[String] = None,
      ee: List[String] = Nil
  )

  val Run: RunId = RunId.of("test.fixture", "test", "fp", "cfg").toOption.get
  val Snapshot = "2026-11-01"

  /** A valid ORCID iD from a 15-digit base. */
  def orcid(base15: String): String = {
    val c = Orcid.checkDigit(base15)
    s"${base15.substring(0, 4)}-${base15.substring(4, 8)}-${base15.substring(8, 12)}-${base15.substring(12, 15)}$c"
  }

  private def prov(ids: List[String], job: String, policy: String) =
    Provenance(1, SourceType.Dblp, "dblp", Snapshot, ids, Run, job, policy, None)

  def workRecord(w: W): WorkRecord = {
    val ee = w.doi.map("https://doi.org/" + _).toList ++ w.ee
    WorkRecord(
      2,
      WorkId.fromDblpKey(w.key),
      w.key,
      "inproceedings",
      w.title,
      w.year,
      Option(w.venue),
      w.doi,
      ee,
      Nil,
      w.authors,
      prov(List(w.key), "test.j01", "dblp-parse/1")
    )
  }

  final case class Member(mention: AuthorMention, work: W)

  def members(works: List[W]): List[Member] =
    works.flatMap { w =>
      w.authors.zipWithIndex.map { case (n, i) =>
        val wid = WorkId.fromDblpKey(w.key)
        Member(
          AuthorMention(
            1,
            MentionId.of(wid, i),
            wid,
            i,
            n,
            w.orcids.get(i),
            prov(List(w.key), "test.j07", "dblp-parse/1")
          ),
          w
        )
      }
    }

  def features(a: Member, b: Member): List[MatchFeature] = {
    def co(m: Member) = m.work.authors.zipWithIndex
      .filter(_._2 != m.mention.position)
      .map(x => AuthorNameKey.fullNameKey(x._1))
      .toSet
    val suffix = (
      AuthorNameKey.homonymSuffix(a.mention.name),
      AuthorNameKey.homonymSuffix(b.mention.name)
    ) match {
      case (Some(x), Some(y)) => Some(if (x == y) 1.0 else 0.0)
      case _                  => None
    }
    List(
      Some(MatchFeature("coauthor_shared_count", co(a).intersect(co(b)).size.toDouble)),
      suffix.map(MatchFeature("homonym_suffix_match", _)),
      Some(
        MatchFeature(
          "name_exact_match",
          if (
            AuthorNameKey.fullNameKey(a.mention.name) == AuthorNameKey.fullNameKey(b.mention.name)
          ) 1.0
          else 0.0
        )
      ),
      for { x <- a.mention.orcid; y <- b.mention.orcid } yield MatchFeature(
        "orcid_exact_match",
        if (x == y) 1.0 else 0.0
      ),
      Some(MatchFeature("same_venue", if (a.work.venue == b.work.venue) 1.0 else 0.0)),
      Some(MatchFeature("same_work", if (a.work.key == b.work.key) 1.0 else 0.0)),
      Some(MatchFeature("year_abs_diff", math.abs(a.work.year - b.work.year).toDouble))
    ).flatten.sortWith((x, y) => CodePointOrder.lessThan(x.name, y.name))
  }

  def candidates(
      ms: List[Member],
      maxBlock: Int,
      skipSameNumbered: Boolean
  ): List[MatchCandidate] = {
    val out = List.newBuilder[MatchCandidate]
    val families: List[(String, Member => Option[String])] = List(
      "dblp-base-name" -> (m => Some(AuthorNameKey.baseNameKey(m.mention.name))),
      "initials-surname" -> (m => AuthorNameKey.initialsSurnameKey(m.mention.name))
    )
    families.foreach { case (family, keyOf) =>
      ms.flatMap(m => keyOf(m).map(_ -> m)).groupBy(_._1).toList.sortBy(_._1).foreach {
        case (key, group) =>
          val g = group.map(_._2).sortBy(_.mention.mentionId.value)
          if (g.size >= 2 && g.size <= maxBlock)
            for { i <- g.indices; j <- g.indices if i < j } {
              val (a, b) = (g(i), g(j))
              val same = AuthorNameKey.homonymSuffix(a.mention.name).isDefined &&
                AuthorNameKey
                  .fullNameKey(a.mention.name) == AuthorNameKey.fullNameKey(b.mention.name)
              if (!(skipSameNumbered && same)) {
                val l = a.mention.mentionId.value
                val r = b.mention.mentionId.value
                out += MatchCandidate(
                  1,
                  MatchCandidateId.of(MatchSubjectKind.MentionPair, l, r, family, key).toOption.get,
                  MatchSubjectKind.MentionPair,
                  l,
                  r,
                  family,
                  key,
                  features(a, b),
                  prov(
                    List(a.work.key, b.work.key).distinct,
                    "hadoop.j06-candidate-features",
                    "candidate-features/1-provisional;blocking/2+author-name-key/1"
                  )
                )
              }
            }
      }
    }
    out.result()
  }

  private def writeLines(file: Path, lines: Seq[String]): Unit = {
    Files.createDirectories(file.getParent)
    Files.write(file, lines.map(_ + "\n").mkString.getBytes(StandardCharsets.UTF_8))
  }

  private def enc[A](a: A)(implicit rw: upickle.default.ReadWriter[A], v: Validator[A]): String =
    Contract.encode(a).fold(e => throw new IllegalArgumentException(e.message), identity)

  /** Writes a J09-like export into a new temporary directory and returns it. */
  def writeExport(
      works: List[W],
      maxBlock: Int = 50,
      skipSameNumbered: Boolean = true,
      links: Option[List[ExternalIdLink]] = None,
      dir: Option[Path] = None
  ): Path = {
    val root = dir.getOrElse(Files.createTempDirectory("er-fixture-"))
    val ms = members(works)
    val cands = candidates(ms, maxBlock, skipSameNumbered)
    writeLines(root.resolve("work/year=2020/part-r-00000"), works.map(w => enc(workRecord(w))))
    writeLines(root.resolve("author_mention/part-r-00000"), ms.map(m => enc(m.mention)))
    val (c1, c2) = cands.splitAt(cands.size / 2)
    writeLines(root.resolve("match_candidate/part-r-00000"), c1.map(enc(_)))
    writeLines(root.resolve("match_candidate/part-r-00001"), c2.map(enc(_)))
    val venues = works.map(_.venue).distinct.sorted
    writeLines(
      root.resolve("venue/part-r-00000"),
      venues.map(v =>
        enc(Venue(1, v, v.split('/').last.toUpperCase, prov(List(v), "test.j01", "dblp-parse/1")))
      )
    )
    links.foreach(ls => writeLines(root.resolve("external_id_link/part-r-00000"), ls.map(enc(_))))
    writeLines(
      root.resolve("_job.json"),
      List(
        ujson
          .Obj(
            "jobName" -> "hadoop.j09-graph-export",
            "runId" -> Run.value,
            "snapshotId" -> "dblp-2026-11-01",
            "codeVersion" -> "test",
            "inputRuns" -> ujson.Arr(Run.value)
          )
          .render()
      )
    )
    Files.write(root.resolve("_SUCCESS"), Array.emptyByteArray)
    root
  }

  def corpus(
      works: List[W],
      links: Option[List[ExternalIdLink]] = None
  ): (Corpus, List[MatchCandidate], Path) = {
    val dir = writeExport(works, links = links)
    (Loader.load(dir, "dblp-2026-11-01").corpus, readCandidates(dir), dir)
  }

  def readCandidates(dir: Path): List[MatchCandidate] =
    List("part-r-00000", "part-r-00001").flatMap { p =>
      val f = dir.resolve("match_candidate").resolve(p)
      new String(Files.readAllBytes(f), StandardCharsets.UTF_8)
        .split('\n')
        .filter(_.nonEmpty)
        .map(l => Contract.decode[MatchCandidate](l).toOption.get)
        .toList
    }

  def mentionId(key: String, position: Int): String =
    MentionId.of(WorkId.fromDblpKey(key), position).value

  def mentionLink(
      key: String,
      position: Int,
      idType: ExternalIdType,
      value: String,
      source: SourceType = SourceType.OpenAlex
  ): ExternalIdLink = {
    val subject = mentionId(key, position)
    val p = Provenance(
      1,
      source,
      source.wire,
      "2026-11-12",
      List("W1"),
      Run,
      "hadoop.j04-exact-id-join",
      "idjoin/1",
      None
    )
    ExternalIdLink(
      1,
      LinkId.of(LinkSubjectKind.Mention, subject, idType, value, source, source.wire, "2026-11-12"),
      LinkSubjectKind.Mention,
      subject,
      idType,
      value,
      p
    )
  }

  def workLink(
      key: String,
      idType: ExternalIdType,
      value: String,
      source: SourceType = SourceType.OpenAlex
  ): ExternalIdLink = {
    val subject = WorkId.fromDblpKey(key).value
    val p = Provenance(
      1,
      source,
      source.wire,
      "2026-11-12",
      List("W1"),
      Run,
      "hadoop.j04-exact-id-join",
      "idjoin/1",
      None
    )
    ExternalIdLink(
      1,
      LinkId.of(LinkSubjectKind.Work, subject, idType, value, source, source.wire, "2026-11-12"),
      LinkSubjectKind.Work,
      subject,
      idType,
      value,
      p
    )
  }

  /** All lines of every regular file below `dir`, keyed by relative path. */
  def tree(dir: Path): Map[String, Array[Byte]] = {
    val s = Files.walk(dir)
    try {
      import scala.jdk.CollectionConverters.*
      s.iterator.asScala
        .filter(Files.isRegularFile(_))
        .map(p => dir.relativize(p).toString -> Files.readAllBytes(p))
        .toMap
    } finally s.close()
  }

  def lines(file: Path): List[String] =
    new String(Files.readAllBytes(file), StandardCharsets.UTF_8)
      .split('\n')
      .filter(_.nonEmpty)
      .toList

  val counter = mutable.HashMap.empty[String, Int]
}
