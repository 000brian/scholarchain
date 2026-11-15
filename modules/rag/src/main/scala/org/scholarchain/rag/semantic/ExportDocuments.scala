package org.scholarchain.rag.semantic

import scala.collection.mutable

import org.scholarchain.core._
import org.scholarchain.core.config.VectorSettings
import org.scholarchain.storage.ingest.DocumentExtras
import org.scholarchain.storage.source._

/** Counts of one template in one rendering pass. */
final class TemplateStats {
  var inputRecords = 0L
  var documents = 0L
  var chunkedRecords = 0L
  var rejected = 0L

  /** Spark templates only: single-chunk documents whose text equals Spark's snippet and whose
    * provenance.contentHash equals Spark's contentHash, and those that do not.
    */
  var sparkVerbatimHashMatches = 0L
  var sparkVerbatimHashMismatches = 0L
  def toJson: ujson.Obj = ujson.Obj(
    "inputRecords" -> inputRecords.toDouble,
    "documents" -> documents.toDouble,
    "recordsSplitIntoChunks" -> chunkedRecords.toDouble,
    "rejected" -> rejected.toDouble,
    "sparkVerbatimHashMatches" -> sparkVerbatimHashMatches.toDouble,
    "sparkVerbatimHashMismatches" -> sparkVerbatimHashMismatches.toDouble
  )
}

/** All documents of one rendering pass over a Spark JSON Lines export, plus per-template counts. */
final case class RenderedCorpus(
    documents: Vector[SemanticDocument],
    paperExtras: Map[String, DocumentExtras],
    stats: Map[String, TemplateStats],
    notes: Map[String, String]
) {

  /** Extras (year, venue) for storage metadata; only paper summaries have them. */
  def extrasOf(d: SemanticDocument): DocumentExtras =
    if (d.documentType == SemanticDocumentType.PaperSummary)
      paperExtras.getOrElse(d.subjectIds.head, DocumentExtras.none)
    else DocumentExtras.none

  /** Order-independent digest of (vectorKey, textHash, documentId): equal digests mean an identical
    * corpus.
    */
  def digest: String = {
    val lines = documents
      .map(d =>
        s"${d.indexName}|${d.vectorKey}|${d.provenance.contentHash.get}|${d.documentId.value}"
      )
      .sorted
    ContentHash.of(lines.mkString("\n"))
  }
}

/** Renders every registered template from the export (read through storage's [[ExportSource]],
  * every line decoded and validated with `Contract.decode`, files checked against the manifest).
  * Rejects are recorded in `rejects` under stage `render`, never dropped silently.
  */
final class ExportDocuments(
    source: ExportSource,
    manifest: ExportManifest,
    vectors: VectorSettings,
    rejects: RejectLog
) {
  val Stage = "render"

  def renderAll(): RenderedCorpus = {
    val stats =
      mutable.LinkedHashMap(TemplateRegistry.all.map(t => t.templateId -> new TemplateStats): _*)
    val docs = Vector.newBuilder[SemanticDocument]
    sparkSummaries(stats, docs)
    val extras = papers(stats, docs)
    val notes = TemplateRegistry.all.collect {
      case t @ SemanticTemplate(_, _, TextSource.NoInput(r), _) =>
        t.templateId -> r
    }.toMap
    RenderedCorpus(docs.result(), extras, stats.toMap, notes)
  }

  private def sparkSummaries(
      stats: mutable.Map[String, TemplateStats],
      out: mutable.Builder[SemanticDocument, Vector[SemanticDocument]]
  ): Unit =
    manifest.withPrefix("semantic_summary:").foreach { table =>
      val kind = TemplateRegistry.sparkKindOfTable(table.table).getOrElse("")
      val template = TemplateRegistry.forSparkKind(kind)
      val label = table.windowLabel
      var line = 0L
      val st = template.map(t => stats(t.templateId))
      TableReader.foreach[EvidenceItem](source, table, rejects, Stage) { e =>
        line += 1
        st.foreach(_.inputRecords += 1)
        val built = for {
          _ <- template.toRight(s"no registered template for Spark summary kind '$kind'")
          l <- label.toRight(s"${table.table} has no window label")
          d <- TemplateRegistry.fromSparkSummary(kind, l, e, vectors)
        } yield d
        built match {
          case Right(ds) =>
            st.foreach { s =>
              s.documents += ds.size
              if (ds.size > 1) s.chunkedRecords += 1
              else if (
                e.snippet.contains(ds.head.text) &&
                ds.head.provenance.contentHash == e.provenance.contentHash &&
                e.provenance.contentHash.contains(ContentHash.of(ds.head.text))
              ) s.sparkVerbatimHashMatches += 1
              else s.sparkVerbatimHashMismatches += 1
            }
            out ++= ds
          case Left(err) =>
            st.foreach(_.rejected += 1)
            rejects.record(Reject(Stage, table.table, e.evidenceId.value, line, err))
        }
      }
    }

  private def papers(
      stats: mutable.Map[String, TemplateStats],
      out: mutable.Builder[SemanticDocument, Vector[SemanticDocument]]
  ): Map[String, DocumentExtras] = {
    val st = stats(TemplateRegistry.PaperAbstract.templateId)
    val venues = mutable.HashMap.empty[String, String]
    manifest.table("nodes:venue").foreach { t =>
      TableReader.foreach[GraphNode](source, t, rejects, Stage)(n =>
        venues.update(n.nodeId, n.displayName)
      )
    }
    val names = mutable.HashMap.empty[String, String]
    manifest.table("canonical_researcher").foreach { t =>
      TableReader.foreach[CanonicalResearcher](source, t, rejects, Stage) { r =>
        names.update(r.researcherId.value, r.displayName)
      }
    }
    val publishedIn = mutable.HashMap.empty[String, String]
    manifest.table("edges:published_in").foreach { t =>
      TableReader.foreach[GraphEdge](source, t, rejects, Stage) { e =>
        if (e.label == GraphEdgeLabel.PublishedIn) publishedIn.update(e.from, e.to)
      }
    }
    val authors = mutable.HashMap.empty[String, List[PaperAuthor]]
    manifest.table("authorship").foreach { t =>
      TableReader.foreach[AuthorshipRecord](source, t, rejects, Stage) { a =>
        val k = a.workId.value
        authors.update(k, PaperAuthor(a.position, a.researcherId) :: authors.getOrElse(k, Nil))
      }
    }
    val extras = mutable.HashMap.empty[String, DocumentExtras]
    manifest.table("nodes:work").foreach { t =>
      var line = 0L
      TableReader.foreach[GraphNode](source, t, rejects, Stage) { w =>
        line += 1
        st.inputRecords += 1
        val in = PaperInputs(
          w,
          publishedIn.get(w.nodeId),
          w.properties.get("venueKey").flatMap(venues.get),
          authors.getOrElse(w.nodeId, Nil),
          names
        )
        PaperSummaryRenderer.document(in, vectors) match {
          case Right(r) =>
            st.documents += r.documents.size
            if (r.documents.size > 1) st.chunkedRecords += 1
            extras.update(w.nodeId, DocumentExtras(Some(r.year), Some(r.venueKey)))
            out ++= r.documents
          case Left(err) =>
            st.rejected += 1
            rejects.record(Reject(Stage, t.table, w.nodeId, line, err))
        }
      }
    }
    extras.toMap
  }
}
