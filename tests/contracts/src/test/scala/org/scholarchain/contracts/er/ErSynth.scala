package org.scholarchain.contracts.er

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.jdk.CollectionConverters.*

import org.scholarchain.contracts.hadoop.AdversarialSupport
import org.scholarchain.core.*
import org.scholarchain.er.data.{Corpus, Loader}
import org.scholarchain.er.llm.{AdjudicationRequest, Adjudicator}
import org.scholarchain.er.policy.Policy
import org.scholarchain.er.review.QueueSpec
import org.scholarchain.er.run.{ErConfig, ErRun, RunOutcome}
import org.scholarchain.hadoop.blocking.BlockMember
import org.scholarchain.hadoop.candidates.CandidateFeatures

/** Synthetic J09-like exports for the entity-resolution adversarial suites (test-engineer).
  *
  * Written independently of the entity-resolution module's own test fixture. Every name, key and
  * identifier is clearly synthetic. Candidates are chosen explicitly by each test (so a test
  * controls which pairs and families exist); their J06 features come from the real hadoop
  * `CandidateFeatures.compute`, so they are what J06 would emit for the same two mentions.
  */
object ErSynth {

  final case class SW(
      key: String,
      authors: List[String],
      year: Int = 2020,
      venue: Option[String] = Some("conf/sigir"),
      title: String = "Synthetic Retrieval Models",
      orcids: Map[Int, String] = Map.empty,
      truncatedCoauthors: Boolean = false
  )

  val Run: RunId = RunId.of("test-engineer.er-synth", "t", "fp", "cfg").toOption.get
  val Snapshot = "dblp-2026-11-01"
  val DecisionTime = "2026-11-14T00:00:00Z"
  val Model = "synthetic-model-1"
  val CandidatePolicy = "candidate-features/1-provisional;blocking/2+author-name-key/1"

  /** A valid ORCID iD from a 15-digit base. */
  def orcid(base15: String): String = {
    val c = Orcid.checkDigit(base15)
    s"${base15.substring(0, 4)}-${base15.substring(4, 8)}-${base15.substring(8, 12)}-${base15.substring(12, 15)}$c"
  }

  def mid(key: String, pos: Int): String = MentionId.of(WorkId.fromDblpKey(key), pos).value

  private def dblpProv(ids: List[String], job: String, policy: String) =
    Provenance(1, SourceType.Dblp, "dblp", Snapshot, ids, Run, job, policy, None)

  def workRecord(w: SW): WorkRecord =
    WorkRecord(
      2,
      WorkId.fromDblpKey(w.key),
      w.key,
      "inproceedings",
      w.title,
      w.year,
      w.venue,
      None,
      Nil,
      Nil,
      w.authors,
      dblpProv(List(w.key), "test.j01", "dblp-parse/1")
    )

  def mention(w: SW, pos: Int): AuthorMention = {
    val wid = WorkId.fromDblpKey(w.key)
    AuthorMention(
      1,
      MentionId.of(wid, pos),
      wid,
      pos,
      w.authors(pos),
      w.orcids.get(pos),
      dblpProv(List(w.key), "test.j07", "dblp-parse/1")
    )
  }

  def member(w: SW, pos: Int): BlockMember =
    BlockMember(
      mid(w.key, pos),
      WorkId.fromDblpKey(w.key).value,
      w.key,
      w.authors(pos),
      w.year,
      w.orcids.get(pos),
      w.venue,
      w.authors.indices.filter(_ != pos).map(w.authors(_)).toList,
      w.truncatedCoauthors
    )

  /** One `mention_pair` candidate of `(work a, position pa)` and `(work b, position pb)` in block
    * `(family, blockKey)`, with the real J06 features.
    */
  def cand(a: SW, pa: Int, b: SW, pb: Int, family: String, blockKey: String): MatchCandidate = {
    val ma = member(a, pa)
    val mb = member(b, pb)
    val (l, r) = if (CodePointOrder.lessThan(ma.mentionId, mb.mentionId)) (ma, mb) else (mb, ma)
    MatchCandidate(
      1,
      MatchCandidateId
        .of(MatchSubjectKind.MentionPair, l.mentionId, r.mentionId, family, blockKey)
        .fold(e => throw new IllegalArgumentException(e), identity),
      MatchSubjectKind.MentionPair,
      l.mentionId,
      r.mentionId,
      family,
      blockKey,
      CandidateFeatures.compute(l, r),
      dblpProv(
        List(l.dblpKey, r.dblpKey).distinct,
        "hadoop.j06-candidate-features",
        CandidatePolicy
      )
    )
  }

  def enc[A](a: A)(implicit rw: upickle.default.ReadWriter[A], v: Validator[A]): String =
    Contract.encode(a).fold(e => throw new IllegalArgumentException(e.message), identity)

  def writeLines(file: Path, lines: Seq[String]): Unit = {
    Files.createDirectories(file.getParent)
    Files.write(file, lines.map(_ + "\n").mkString.getBytes(StandardCharsets.UTF_8))
  }

  def workRoot(prefix: String): Path = {
    val base = AdversarialSupport.repoRoot.resolve("tests/contracts/target/er-work")
    Files.createDirectories(base)
    Files.createTempDirectory(base, prefix)
  }

  /** Writes a J09-like export (one `run=` directory) and returns it. */
  def writeExport(
      works: List[SW],
      candidates: List[MatchCandidate],
      links: List[ExternalIdLink] = Nil,
      dir: Option[Path] = None
  ): Path = {
    val root = dir.getOrElse(workRoot("export-").resolve("run=synthetic"))
    writeLines(root.resolve("work/year=2020/part-r-00000"), works.map(w => enc(workRecord(w))))
    writeLines(
      root.resolve("author_mention/part-r-00000"),
      works.flatMap(w => w.authors.indices.map(i => enc(mention(w, i))))
    )
    writeLines(root.resolve("match_candidate/part-r-00000"), candidates.map(enc(_)))
    val venues = works.flatMap(_.venue).distinct.sorted
    writeLines(
      root.resolve("venue/part-r-00000"),
      venues.map(v =>
        enc(
          Venue(1, v, v.split('/').last.toUpperCase, dblpProv(List(v), "test.j01", "dblp-parse/1"))
        )
      )
    )
    if (links.nonEmpty) writeLines(root.resolve("external_id_link/part-r-00000"), links.map(enc(_)))
    writeLines(
      root.resolve("_job.json"),
      List(
        ujson
          .Obj(
            "jobName" -> "hadoop.j09-graph-export",
            "runId" -> Run.value,
            "snapshotId" -> Snapshot,
            "codeVersion" -> "test-engineer",
            "inputRuns" -> ujson.Arr(Run.value)
          )
          .render()
      )
    )
    Files.write(root.resolve("_SUCCESS"), Array.emptyByteArray)
    root
  }

  def corpus(dir: Path): Corpus = Loader.load(dir, Snapshot).corpus

  def mentionLink(key: String, pos: Int, idType: ExternalIdType, value: String): ExternalIdLink = {
    val subject = mid(key, pos)
    val p = Provenance(
      1,
      SourceType.OpenAlex,
      "openalex",
      "2026-11-12",
      List("W-SYNTHETIC-1"),
      Run,
      "hadoop.j04-exact-id-join",
      "idjoin/1",
      None
    )
    ExternalIdLink(
      1,
      LinkId.of(
        LinkSubjectKind.Mention,
        subject,
        idType,
        value,
        SourceType.OpenAlex,
        "openalex",
        "2026-11-12"
      ),
      LinkSubjectKind.Mention,
      subject,
      idType,
      value,
      p
    )
  }

  /** A scripted adjudicator that counts its calls. */
  final class Scripted(val modelVersion: String, reply: AdjudicationRequest => String)
      extends Adjudicator {
    @volatile var calls = 0
    val requests = scala.collection.mutable.ArrayBuffer.empty[AdjudicationRequest]
    def adjudicate(request: AdjudicationRequest): String = synchronized {
      calls += 1
      requests += request
      reply(request)
    }
  }

  def erConfig(
      input: Path,
      out: Path,
      llmMax: Int = 1000,
      adjudicatorName: String = "scripted",
      cache: Option[Path] = None,
      policy: Policy = Policy.erV1,
      queue: QueueSpec = QueueSpec(20, 2, 20261114L)
  ): ErConfig =
    ErConfig(
      input,
      out,
      DecisionTime,
      20261114L,
      "test-engineer-er-1",
      Model,
      llmMax,
      adjudicatorName,
      queue,
      cache,
      None,
      policy
    )

  def run(c: ErConfig, adj: Adjudicator): RunOutcome = ErRun.run(c, adj, _ => ())

  def lines(file: Path): List[String] =
    if (!Files.isRegularFile(file)) Nil
    else
      new String(Files.readAllBytes(file), StandardCharsets.UTF_8)
        .split('\n')
        .filter(_.nonEmpty)
        .toList

  private def decodeAll(dir: Path): List[MatchDecision] =
    if (!Files.isDirectory(dir)) Nil
    else {
      val s = Files.list(dir)
      try
        s.iterator.asScala.toList
          .filter(_.getFileName.toString.endsWith(".jsonl"))
          .sortBy(_.getFileName.toString)
          .flatMap(lines)
          .map(l =>
            Contract
              .decode[MatchDecision](l)
              .fold(e => throw new AssertionError(s"invalid MatchDecision: ${e.message}"), identity)
          )
      finally s.close()
    }

  def pairDecisions(runDir: Path): List[MatchDecision] =
    decodeAll(runDir.resolve("decisions/mention_pair"))

  def assignments(runDir: Path): List[MatchDecision] =
    decodeAll(runDir.resolve("decisions/identity_assignment"))

  /** Relative path -> bytes of every regular file below `dir`. */
  def tree(dir: Path): Map[String, Array[Byte]] = {
    val s = Files.walk(dir)
    try
      s.iterator.asScala
        .filter(Files.isRegularFile(_))
        .map(p => dir.relativize(p).toString -> Files.readAllBytes(p))
        .toMap
    finally s.close()
  }

  /** Copies a directory tree (regular files only). */
  def copyTree(from: Path, to: Path): Path = {
    tree(from).foreach { case (rel, bytes) =>
      val f = to.resolve(rel)
      Files.createDirectories(f.getParent)
      Files.write(f, bytes)
    }
    to
  }
}
