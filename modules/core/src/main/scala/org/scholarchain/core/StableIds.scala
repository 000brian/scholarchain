package org.scholarchain.core

import upickle.core.Abort
import upickle.default.{readwriter, ReadWriter}

/** ScholarChain stable identifiers.
  *
  * Every stable ID has the form `<kind>:v1:<h>` where `<h>` is
  * `Hashing.digest32(Seq("<kind>/v1", c1, c2, ...))`: the first 32 lowercase hex characters of
  * SHA-256 over the length-prefixed UTF-8 encoding of the domain tag and the canonical inputs (see
  * [[Hashing.canonicalEncoding]]). IDs never depend on wall-clock time, randomness, host, or
  * iteration order. Changing a formula is a breaking change and bumps the `v1` tag.
  *
  * IDs are JSON strings on the wire; reading a malformed ID fails decoding.
  */
trait StableId {
  def value: String
  def isWellFormed: Boolean
  override def toString: String = value
}

abstract class StableIdCompanion[A <: StableId](val kind: String, make: String => A) {
  val Version = "v1"
  val Prefix: String = s"$kind:$Version:"
  val DomainTag: String = s"$kind/$Version"

  def isWellFormed(s: String): Boolean =
    s != null && s.length == Prefix.length + 32 && s.startsWith(Prefix) &&
      s.substring(Prefix.length).forall(c => (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))

  def parse(s: String): Either[String, A] =
    if (isWellFormed(s)) Right(make(s))
    else Left(s"malformed $kind ID '$s' (expected $Prefix followed by 32 lowercase hex characters)")

  protected def derive(components: String*): A =
    make(Prefix + Hashing.digest32(DomainTag +: components))

  implicit lazy val readWriter: ReadWriter[A] =
    readwriter[String].bimap[A](_.value, s => parse(s).fold(e => throw new Abort(e), identity))
}

/** Work ID. Formula: `work:v1:` + digest32("work/v1", "dblp", dblpKey) with the dblp key verbatim.
  */
final case class WorkId(value: String) extends StableId {
  def isWellFormed: Boolean = WorkId.isWellFormed(value)
}
object WorkId extends StableIdCompanion[WorkId]("work", new WorkId(_)) {
  def fromDblpKey(dblpKey: String): WorkId = derive("dblp", dblpKey)
}

/** Author mention ID (one author slot of one work). Formula: `mention:v1:` + digest32(
  * "mention/v1", workId, position) with the 0-based author position in decimal.
  */
final case class MentionId(value: String) extends StableId {
  def isWellFormed: Boolean = MentionId.isWellFormed(value)
}
object MentionId extends StableIdCompanion[MentionId]("mention", new MentionId(_)) {
  def of(workId: WorkId, position: Int): MentionId = derive(workId.value, position.toString)
}

/** Researcher ID (canonical researcher after entity resolution).
  *
  * Formula: `researcher:v1:` + digest32("researcher/v1", "anchor-mention", anchorMentionId) where
  * the anchor mention is the researcher's earliest mention: the minimum of the cluster's mentions
  * ordered by ([[AnchorCandidate.year]], [[AnchorCandidate.workId]] by code point,
  * [[AnchorCandidate.position]]) ([[ResearcherId.anchorOf]]).
  *
  * Uniqueness: entity resolution assigns each mention to at most one researcher (mentions are
  * partitioned among researchers), so two distinct researchers never share an anchor mention and
  * never share an ID (up to a 128-bit hash collision), even when they share every dblp name (for
  * example when entity resolution splits the unnumbered dblp label `Wei Wang`).
  *
  * Stability: the ID depends only on the anchor mention, so it is the same for the same cluster in
  * every run, and it does not change when the researcher gains later publications, ORCID iDs,
  * OpenAlex IDs, institutions or dblp name variants. The ID changes when (1) the anchor mention
  * leaves the cluster (entity resolution splits it off or assigns it elsewhere), (2) an earlier
  * mention joins the cluster (a merge with a researcher whose earliest mention is earlier, or dblp
  * adds an older publication), (3) dblp changes the anchor work's key or year, or reorders its
  * author list. When two researchers merge, the merged researcher keeps the ID of the one with the
  * earlier anchor; the other ID disappears. Consumers that persist researcher IDs across entity
  * resolution runs must treat a vanished ID as split or merged, never reuse it.
  *
  * Cross-record invariants (checked by contract and output tests, not by a single-record
  * validator): mentions are partitioned among researchers, and the anchor is the minimum of the
  * researcher's authorships under the order above.
  */
final case class ResearcherId(value: String) extends StableId {
  def isWellFormed: Boolean = ResearcherId.isWellFormed(value)
}
object ResearcherId extends StableIdCompanion[ResearcherId]("researcher", new ResearcherId(_)) {
  def fromAnchorMention(anchor: MentionId): ResearcherId = derive("anchor-mention", anchor.value)

  /** Anchor order: year, then work ID by code point, then author position (all ascending). */
  val anchorOrdering: Ordering[AnchorCandidate] = new Ordering[AnchorCandidate] {
    def compare(a: AnchorCandidate, b: AnchorCandidate): Int = {
      val byYear = Integer.compare(a.year, b.year)
      if (byYear != 0) byYear
      else {
        val byWork = CodePointOrder.compare(a.workId.value, b.workId.value)
        if (byWork != 0) byWork else Integer.compare(a.position, b.position)
      }
    }
  }

  /** The anchor mention of a cluster: its minimum mention under [[anchorOrdering]]. */
  def anchorOf(cluster: Iterable[AnchorCandidate]): Option[MentionId] =
    if (cluster.isEmpty) None else Some(cluster.min(anchorOrdering).mentionId)

  /** The researcher ID of a cluster of mentions; `None` for an empty cluster. */
  def forCluster(cluster: Iterable[AnchorCandidate]): Option[ResearcherId] =
    anchorOf(cluster).map(fromAnchorMention)
}

/** One mention of a researcher cluster, with the publication year of its work (the fields of an
  * [[AuthorshipRecord]] that the anchor rule reads). Not a wire record.
  */
final case class AnchorCandidate(year: Int, workId: WorkId, position: Int) {
  def mentionId: MentionId = MentionId.of(workId, position)
}

/** Graph edge ID.
  *
  * Formula: `edge:v1:` + digest32("edge/v1", label, from, to, startYear, endYear). For undirected
  * labels (`coauthored_with`) the endpoints are first put in canonical order ([[UndirectedPair]]),
  * so `of(l, a, b, w) == of(l, b, a, w)`; self pairs are rejected.
  */
final case class EdgeId(value: String) extends StableId {
  def isWellFormed: Boolean = EdgeId.isWellFormed(value)
}
object EdgeId extends StableIdCompanion[EdgeId]("edge", new EdgeId(_)) {
  def of(
      label: GraphEdgeLabel,
      from: String,
      to: String,
      window: YearWindow
  ): Either[String, EdgeId] =
    if (label.undirected)
      UndirectedPair.canonical(from, to).map { case (l, r) => build(label, l, r, window) }
    else Right(build(label, from, to, window))

  def coauthor(a: ResearcherId, b: ResearcherId, window: YearWindow): Either[String, EdgeId] =
    of(GraphEdgeLabel.CoauthoredWith, a.value, b.value, window)

  private def build(label: GraphEdgeLabel, from: String, to: String, w: YearWindow): EdgeId =
    derive(label.wire, from, to, w.startYear.toString, w.endYear.toString)
}

/** Evidence ID (evidence items and evidence bundles share this namespace).
  *
  * Item: `evidence:v1:` + digest32("evidence/v1", evidenceType, runId, subjectId1, subjectId2, ...)
  * with subject IDs in their stated order (path order matters).
  *
  * Bundle: `evidence:v1:` + digest32("evidence/v1", "bundle", runId, itemId1, itemId2, ...) with
  * the item IDs sorted ascending and de-duplicated, so the bundle ID ignores item order.
  */
final case class EvidenceId(value: String) extends StableId {
  def isWellFormed: Boolean = EvidenceId.isWellFormed(value)
}
object EvidenceId extends StableIdCompanion[EvidenceId]("evidence", new EvidenceId(_)) {
  def of(evidenceType: EvidenceType, runId: RunId, subjectIds: Seq[String]): EvidenceId =
    derive((Seq(evidenceType.wire, runId.value) ++ subjectIds): _*)

  def forBundle(runId: RunId, itemIds: Seq[EvidenceId]): EvidenceId =
    derive((Seq("bundle", runId.value) ++ itemIds.map(_.value).distinct.sorted): _*)
}

/** Run ID for a pipeline job or runtime request.
  *
  * Formula: `run:v1:` + digest32("run/v1", jobName, codeVersion, inputFingerprint,
  * configFingerprint). The same job over the same inputs with the same code and configuration
  * always has the same run ID (idempotent reruns). Fingerprints are content hashes (for example
  * SHA-256 of an input manifest or of the resolved configuration), never timestamps.
  */
final case class RunId(value: String) extends StableId {
  def isWellFormed: Boolean = RunId.isWellFormed(value)
}
object RunId extends StableIdCompanion[RunId]("run", new RunId(_)) {
  def of(
      jobName: String,
      codeVersion: String,
      inputFingerprint: String,
      configFingerprint: String
  ): Either[String, RunId] = {
    val parts = Seq(
      "jobName" -> jobName,
      "codeVersion" -> codeVersion,
      "inputFingerprint" -> inputFingerprint,
      "configFingerprint" -> configFingerprint
    )
    parts.collectFirst { case (n, v) if v == null || v.trim.isEmpty => n } match {
      case Some(n) => Left(s"run ID input $n must not be blank")
      case None    => Right(derive(jobName, codeVersion, inputFingerprint, configFingerprint))
    }
  }
}

/** External-identifier link ID.
  *
  * Formula: `link:v1:` + digest32("link/v1", subjectKind, subjectId, idType, externalId,
  * sourceType, datasetId, snapshotId) with the wire names of the enums and the dataset and snapshot
  * of the asserting source. The same assertion from another snapshot is a different link (OpenAlex
  * IDs are not stable across snapshots); rerunning the same linking job yields the same ID.
  */
final case class LinkId(value: String) extends StableId {
  def isWellFormed: Boolean = LinkId.isWellFormed(value)
}
object LinkId extends StableIdCompanion[LinkId]("link", new LinkId(_)) {
  def of(
      subjectKind: LinkSubjectKind,
      subjectId: String,
      idType: ExternalIdType,
      externalId: String,
      sourceType: SourceType,
      datasetId: String,
      snapshotId: String
  ): LinkId =
    derive(
      subjectKind.wire,
      subjectId,
      idType.wire,
      externalId,
      sourceType.wire,
      datasetId,
      snapshotId
    )
}

/** Match candidate ID (one compared subject pair within one block).
  *
  * Formula: `match-candidate:v1:` + digest32("match-candidate/v1", subjectKind, left, right,
  * blockFamily, blockKey) with the subject kind's wire name. For `mention_pair` the two mention IDs
  * are first put in canonical order ([[UndirectedPair]]), so `of(k, a, b, f, key) == of(k, b, a, f,
  * key)` and self pairs are rejected; for `mention_researcher` `left` is the mention and `right`
  * the researcher. The run, policy, and feature values are not inputs: the same subjects in the
  * same block have the same ID in every run, so review queues and gold-set labels can refer to it.
  * The same subjects co-occurring in two blocks are two candidates.
  */
final case class MatchCandidateId(value: String) extends StableId {
  def isWellFormed: Boolean = MatchCandidateId.isWellFormed(value)
}
object MatchCandidateId
    extends StableIdCompanion[MatchCandidateId]("match-candidate", new MatchCandidateId(_)) {
  def of(
      subjectKind: MatchSubjectKind,
      left: String,
      right: String,
      blockFamily: String,
      blockKey: String
  ): Either[String, MatchCandidateId] = {
    val subjects = subjectKind match {
      case MatchSubjectKind.MentionPair       => UndirectedPair.canonical(left, right)
      case MatchSubjectKind.MentionResearcher => Right((left, right))
    }
    subjects.map { case (l, r) => derive(subjectKind.wire, l, r, blockFamily, blockKey) }
  }
}

/** LLM adjudication ID: the adjudication cache key.
  *
  * Formula: `llm-adjudication:v1:` + digest32("llm-adjudication/v1", inputHash, promptVersion,
  * modelVersion, policyVersion) with the four values verbatim. The same input under the same
  * prompt, model, and policy versions has the same ID (a cache hit); changing any one of the four
  * gives a different ID (a cache miss). The outcome is not an input.
  */
final case class LlmAdjudicationId(value: String) extends StableId {
  def isWellFormed: Boolean = LlmAdjudicationId.isWellFormed(value)
}
object LlmAdjudicationId
    extends StableIdCompanion[LlmAdjudicationId]("llm-adjudication", new LlmAdjudicationId(_)) {
  def of(
      inputHash: String,
      promptVersion: String,
      modelVersion: String,
      policyVersion: String
  ): Either[String, LlmAdjudicationId] = {
    val parts = Seq(
      "inputHash" -> inputHash,
      "promptVersion" -> promptVersion,
      "modelVersion" -> modelVersion,
      "policyVersion" -> policyVersion
    )
    parts.collectFirst { case (n, v) if v == null || v.trim.isEmpty => n } match {
      case Some(n) => Left(s"LLM adjudication ID input $n must not be blank")
      case None    => Right(derive(inputHash, promptVersion, modelVersion, policyVersion))
    }
  }
}

/** Chunk ID: one chunk of one semantic document. It is the `documentId` of a [[SemanticDocument]]
  * and the nodeId of a `text_chunk` graph node. New in Phase 7.
  *
  * Formula: `chunk:v1:` + digest32("chunk/v1", documentType, subjectKey, windowLabel or "-",
  * ordinal, textHash) where
  *   - `documentType` is the [[SemanticDocumentType]] wire name;
  *   - `subjectKey` is the document's subject key; producers use [[ChunkId.subjectKeyOf]] (the
  *     document's `subjectIds` joined by `,` in their stated order) through
  *     [[ChunkId.forSubjects]];
  *   - `windowLabel` is the configured graph-window label (`graph.windows[].label`, pattern
  *     `[a-z0-9][a-z0-9-]*`) verbatim, or the literal `-` for a document without a window (no valid
  *     label equals `-`, so the two never collide);
  *   - `ordinal` is the 0-based chunk position within the document, in decimal;
  *   - `textHash` is `ContentHash.of(chunk text)` (`sha256:<64 lowercase hex>`).
  *
  * The ID changes when the text changes (the text hash is an input). Vector keys, which must stay
  * stable across reruns, are not chunk IDs.
  */
final case class ChunkId(value: String) extends StableId {
  def isWellFormed: Boolean = ChunkId.isWellFormed(value)
}
object ChunkId extends StableIdCompanion[ChunkId]("chunk", new ChunkId(_)) {

  /** The component used for a document without a window. */
  val NoWindow = "-"

  private val WindowLabelPattern = "[a-z0-9][a-z0-9-]*"

  def of(
      documentType: SemanticDocumentType,
      subjectKey: String,
      windowLabel: Option[String],
      ordinal: Int,
      textHash: String
  ): Either[String, ChunkId] =
    if (subjectKey == null || subjectKey.trim.isEmpty) Left("chunk ID subjectKey must not be blank")
    else if (windowLabel.exists(l => l == null || !l.matches(WindowLabelPattern)))
      Left(s"chunk ID windowLabel '${windowLabel.orNull}' must match $WindowLabelPattern")
    else if (ordinal < 0) Left(s"chunk ID ordinal $ordinal must be >= 0")
    else if (!ContentHash.isWellFormed(textHash))
      Left(s"chunk ID textHash '$textHash' must be sha256:<64 lowercase hex>")
    else
      Right(
        derive(
          documentType.wire,
          subjectKey,
          windowLabel.getOrElse(NoWindow),
          ordinal.toString,
          textHash
        )
      )

  /** The subject key of a document: its subject IDs joined by `,` in their stated order. Each ID
    * must be non-blank and must not contain `,`.
    */
  def subjectKeyOf(subjectIds: Seq[String]): Either[String, String] =
    if (subjectIds.isEmpty) Left("chunk ID subjectIds must not be empty")
    else
      subjectIds.find(s => s == null || s.trim.isEmpty || s.contains(",")) match {
        case Some(bad) => Left(s"chunk ID subject ID '$bad' must be non-blank without ','")
        case None      => Right(subjectIds.mkString(","))
      }

  /** `of(documentType, subjectKeyOf(subjectIds), windowLabel, ordinal, textHash)`. */
  def forSubjects(
      documentType: SemanticDocumentType,
      subjectIds: Seq[String],
      windowLabel: Option[String],
      ordinal: Int,
      textHash: String
  ): Either[String, ChunkId] =
    subjectKeyOf(subjectIds).flatMap(of(documentType, _, windowLabel, ordinal, textHash))
}
