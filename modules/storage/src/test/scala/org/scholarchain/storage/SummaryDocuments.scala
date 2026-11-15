package org.scholarchain.storage

import org.scholarchain.core._
import org.scholarchain.core.config.VectorSettings
import org.scholarchain.storage.vectors.VectorKeys

/** Storage's local stand-in for rag's renderer (test scope only): builds one SemanticDocument per
  * Spark semantic summary, reusing the summary text verbatim (ordinal 0, one chunk). rag owns the
  * real template registry; this exists so storage can run its own ingest locally.
  */
object SummaryDocuments {

  /** Spark summary kinds (the `summary_kind=` path segment) and their document types. */
  val kinds: Map[String, SemanticDocumentType] = Map(
    "researcher_profile" -> SemanticDocumentType.ResearcherProfile,
    "community" -> SemanticDocumentType.CommunitySummary,
    "path" -> SemanticDocumentType.CollaborationPath
  )

  def kindOfTable(table: String): Option[String] = table.split(':').toList match {
    case "semantic_summary" :: kind :: _ => Some(kind)
    case _                               => None
  }

  def build(
      kind: String,
      windowLabel: String,
      e: EvidenceItem,
      vectors: VectorSettings
  ): Either[String, SemanticDocument] =
    for {
      t <- kinds.get(kind).toRight(s"unknown summary kind '$kind'")
      text <- e.snippet.toRight("summary without text")
      hash = ContentHash.of(text)
      chunk <- ChunkId.forSubjects(t, e.subjectIds, Some(windowLabel), 0, hash)
      key <- VectorKeys.derive(t, Some(windowLabel), e.subjectIds, 0)
      doc = SemanticDocument(
        1,
        chunk,
        t,
        vectors.indexFor(t),
        key,
        e.subjectIds,
        e.window,
        text,
        Some(e.evidenceId),
        e.provenance
      )
      _ <- Contract.validate(doc) match {
        case Nil  => Right(())
        case errs => Left(errs.mkString("; "))
      }
    } yield doc
}
