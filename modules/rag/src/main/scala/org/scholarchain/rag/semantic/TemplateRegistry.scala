package org.scholarchain.rag.semantic

import org.scholarchain.core._
import org.scholarchain.core.config.VectorSettings
import org.scholarchain.storage.vectors.VectorKeys

/** Where a template's text comes from. */
sealed trait TextSource
object TextSource {

  /** Spark's own summary text, reused verbatim (never re-rendered), so one subject never has two
    * texts. Identified by the Spark summary kind (`summary_kind=` path segment), the Spark job name
    * and the graph policy version recorded in the summary's provenance.
    */
  final case class SparkVerbatim(summaryKind: String, jobName: String, policyVersion: String)
      extends TextSource

  /** Rendered by rag from exported records; `jobName` and `policyVersion` go into the document's
    * provenance.
    */
  final case class RagRendered(jobName: String, policyVersion: String) extends TextSource

  /** Defined, but there is no input yet: 0 documents, for the stated reason. */
  final case class NoInput(reason: String) extends TextSource
}

/** One registered rendering template. */
final case class SemanticTemplate(
    templateId: String,
    documentType: SemanticDocumentType,
    source: TextSource,
    chunking: ChunkingPolicy
)

/** The rag template registry: every way a [[SemanticDocument]] can be produced.
  *
  * | templateId                          | document type             | text source                                   |
  * |:------------------------------------|:--------------------------|:----------------------------------------------|
  * | `spark-researcher-profile/graph-v1` | researcher_profile        | Spark `researcher_profile`, verbatim          |
  * | `spark-community/graph-v1`          | community_summary         | Spark `community`, verbatim                   |
  * | `spark-path/graph-v1`               | collaboration_path        | Spark `path`, verbatim                        |
  * | `paper-abstract-v1`                 | paper_summary             | rag [[PaperSummaryRenderer]] (dblp data only) |
  * | `topic-institution-summary-v1`      | topic_institution_summary | none yet: 0 documents (no enrichment data)    |
  *
  * A Spark summary whose job name or policy version differs from the registered one is rejected: a
  * new Spark template version must be registered here first.
  */
object TemplateRegistry {
  val SparkJob = "spark.semantic-summaries"
  val GraphPolicy = "graph-v1"

  val ResearcherProfile: SemanticTemplate = SemanticTemplate(
    "spark-researcher-profile/graph-v1",
    SemanticDocumentType.ResearcherProfile,
    TextSource.SparkVerbatim("researcher_profile", SparkJob, GraphPolicy),
    ChunkingPolicy.current
  )
  val Community: SemanticTemplate = SemanticTemplate(
    "spark-community/graph-v1",
    SemanticDocumentType.CommunitySummary,
    TextSource.SparkVerbatim("community", SparkJob, GraphPolicy),
    ChunkingPolicy.current
  )
  val Path: SemanticTemplate = SemanticTemplate(
    "spark-path/graph-v1",
    SemanticDocumentType.CollaborationPath,
    TextSource.SparkVerbatim("path", SparkJob, GraphPolicy),
    ChunkingPolicy.current
  )
  val PaperAbstract: SemanticTemplate = SemanticTemplate(
    "paper-abstract-v1",
    SemanticDocumentType.PaperSummary,
    TextSource.RagRendered(PaperSummaryRenderer.JobName, PaperSummaryRenderer.Version),
    ChunkingPolicy.current
  )
  val TopicInstitution: SemanticTemplate = SemanticTemplate(
    "topic-institution-summary-v1",
    SemanticDocumentType.TopicInstitutionSummary,
    TextSource.NoInput(
      "no enrichment input: the Phase 6 graph has 0 topic and 0 institution nodes and no " +
        "affiliated_with or has_topic edges (OpenAlex, ORCID and Crossref enrichment is not loaded " +
        "yet), so there is nothing to summarize without inventing facts"
    ),
    ChunkingPolicy.current
  )

  val all: List[SemanticTemplate] =
    List(ResearcherProfile, Community, Path, PaperAbstract, TopicInstitution)

  def forDocumentType(t: SemanticDocumentType): SemanticTemplate =
    all.find(_.documentType == t).get

  def forSparkKind(kind: String): Option[SemanticTemplate] = all.find(_.source match {
    case TextSource.SparkVerbatim(k, _, _) => k == kind
    case _                                 => false
  })

  /** The Spark summary kind of an export table name `semantic_summary:<kind>:<window>`. */
  def sparkKindOfTable(table: String): Option[String] = table.split(':').toList match {
    case "semantic_summary" :: kind :: _ => Some(kind)
    case _                               => None
  }

  /** Builds the document(s) of one Spark summary: the text verbatim, split only by the chunking
    * policy (one chunk for every current summary). Single-chunk documents keep Spark's provenance
    * unchanged, so `provenance.contentHash` is Spark's contentHash; a split chunk carries the hash
    * of its own text and points back to the summary through `sourceEvidenceId`.
    */
  def fromSparkSummary(
      kind: String,
      windowLabel: String,
      e: EvidenceItem,
      vectors: VectorSettings
  ): Either[String, Vector[SemanticDocument]] =
    for {
      template <- forSparkKind(kind).toRight(
        s"no registered template for Spark summary kind '$kind'"
      )
      src = template.source.asInstanceOf[TextSource.SparkVerbatim]
      _ <- Either.cond(
        e.provenance.jobName == src.jobName && e.provenance.policyVersion == src.policyVersion,
        (),
        s"summary was produced by ${e.provenance.jobName}/${e.provenance.policyVersion}, " +
          s"but ${template.templateId} expects ${src.jobName}/${src.policyVersion}"
      )
      text <- e.snippet.filter(_.trim.nonEmpty).toRight("summary has no text")
      _ <- Either.cond(
        e.provenance.contentHash.contains(ContentHash.of(text)),
        (),
        "summary provenance.contentHash does not match its text"
      )
      _ <- Either.cond(e.window.isDefined, (), "summary has no window")
      docs <- build(
        template,
        e.subjectIds,
        Some(windowLabel),
        e.window,
        text,
        Some(e.evidenceId),
        e.provenance,
        vectors
      )
    } yield docs

  /** Chunks `text` and builds one validated document per chunk. */
  def build(
      template: SemanticTemplate,
      subjectIds: List[String],
      windowLabel: Option[String],
      window: Option[YearWindow],
      text: String,
      sourceEvidenceId: Option[EvidenceId],
      provenance: Provenance,
      vectors: VectorSettings
  ): Either[String, Vector[SemanticDocument]] = {
    val t = template.documentType
    val chunks = template.chunking.split(text)
    val built = chunks.zipWithIndex.map { case (chunk, ordinal) =>
      val hash = ContentHash.of(chunk)
      for {
        id <- ChunkId.forSubjects(t, subjectIds, windowLabel, ordinal, hash)
        key <- VectorKeys.derive(t, windowLabel, subjectIds, ordinal)
        doc = SemanticDocument(
          SchemaVersions.currentOf("SemanticDocument"),
          id,
          t,
          vectors.indexFor(t),
          key,
          subjectIds,
          window,
          chunk,
          sourceEvidenceId,
          provenance.copy(contentHash = Some(hash))
        )
        _ <- Contract.validate(doc) match {
          case Nil  => Right(())
          case errs => Left(errs.mkString("; "))
        }
      } yield doc
    }
    built.collectFirst { case Left(e) => e } match {
      case Some(e) => Left(e)
      case None    => Right(built.collect { case Right(d) => d })
    }
  }
}
