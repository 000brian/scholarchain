package org.scholarchain.storage.ingest

import scala.collection.immutable.ListMap
import scala.collection.mutable

import org.scholarchain.core.{
  ContentHash,
  Contract,
  OpenAlexId,
  Provenance,
  ResearcherId,
  RunId,
  SchemaVersions,
  SemanticDocument,
  SemanticDocumentType,
  SourceType,
  VectorMetadata,
  WorkId,
  YearWindow
}
import org.scholarchain.core.config.{DynamoDbSettings, NamedWindow}
import org.scholarchain.storage.{RetryPolicy, S3VectorsLimits, Sleeper}
import org.scholarchain.storage.dynamo.{ArtifactRegistry, ArtifactVersion, Change, RunLedger}
import org.scholarchain.storage.ports._
import org.scholarchain.storage.source.{Reject, RejectLog}
import org.scholarchain.storage.vectors._

/** Optional metadata a renderer can attach to a document (for example a paper's year and venue);
  * SemanticDocument does not carry these fields.
  */
final case class DocumentExtras(year: Option[Int], venueKey: Option[String])
object DocumentExtras { val none: DocumentExtras = DocumentExtras(None, None) }

final case class IngestSettings(
    putBatchSize: Int,
    retry: RetryPolicy,
    sourceDataset: String,
    jobName: String = VectorIngestPipeline.JobName
) {
  require(putBatchSize >= 1 && putBatchSize <= S3VectorsLimits.MaxPutVectorsBatch, "putBatchSize")
}

/** Counters of one index in one ingest run. */
final class IndexStats {
  var input = 0L
  var skippedUnchanged = 0L
  var embeddedNew = 0L
  var reembeddedChanged = 0L
  var written = 0L
  var rejected = 0L
  var duplicatesSameText = 0L
  def toJson: ujson.Obj = ujson.Obj(
    "input" -> input.toDouble,
    "skippedUnchanged" -> skippedUnchanged.toDouble,
    "embeddedNew" -> embeddedNew.toDouble,
    "reembeddedChanged" -> reembeddedChanged.toDouble,
    "written" -> written.toDouble,
    "rejected" -> rejected.toDouble,
    "duplicatesSameText" -> duplicatesSameText.toDouble
  )
}

/** Summary of one ingest run (one attempt). */
final case class IngestReport(
    runId: RunId,
    attempt: Int,
    perIndex: ListMap[String, IndexStats],
    textCreated: Long,
    textAlreadyPresent: Long,
    embedCalls: Long,
    embedRetries: Long,
    putCalls: Long,
    putRetries: Long,
    registryConflicts: Long,
    ledgerEvents: Long,
    millis: ListMap[String, Long]
) {
  def total(f: IndexStats => Long): Long = perIndex.values.map(f).sum
  def input: Long = total(_.input)
  def skipped: Long = total(_.skippedUnchanged)
  def rejected: Long = total(_.rejected)
  def written: Long = total(_.written)
  def skipRatio: Double = if (input == 0) 0.0 else skipped.toDouble / input

  def toJson: ujson.Obj = ujson.Obj(
    "runId" -> runId.value,
    "attempt" -> attempt,
    "input" -> input.toDouble,
    "skippedUnchanged" -> skipped.toDouble,
    "skipRatio" -> skipRatio,
    "written" -> written.toDouble,
    "rejected" -> rejected.toDouble,
    "perIndex" -> ujson.Obj.from(perIndex.map { case (k, v) => k -> v.toJson }),
    "textCreated" -> textCreated.toDouble,
    "textAlreadyPresent" -> textAlreadyPresent.toDouble,
    "embedCalls" -> embedCalls.toDouble,
    "embedRetries" -> embedRetries.toDouble,
    "putCalls" -> putCalls.toDouble,
    "putRetries" -> putRetries.toDouble,
    "registryConflicts" -> registryConflicts.toDouble,
    "ledgerEvents" -> ledgerEvents.toDouble,
    "millis" -> ujson.Obj.from(millis.map { case (k, v) => k -> ujson.Num(v.toDouble) })
  )
}

/** The embedding ingest pipeline (spec Phase 8, storage work). For each [[SemanticDocument]]:
  *
  *   1. write the text to the text store (S3 key containing the text hash, put-if-absent);
  *   2. look up the vector key in ArtifactRegistry;
  *   3. if it holds the same textHash, embeddingModel and dimensions, skip it;
  *   4. if the text or model differs, re-embed and overwrite, and record the old and new hash (and
  *      model) in RunLedger; never overwrite silently;
  *   5. put the vector with its metadata (batches of at most `putBatchSize` <= 500, under 20 MiB,
  *      bounded retries on throttling and 5xx), then register the new version in ArtifactRegistry
  *      (after the put, so the registry never claims a vector that was not written).
  *
  * Rejected documents and vectors go to the reject file and RunLedger; failures and retry counts go
  * to RunLedger. Duplicate vector keys inside one run: the same text is counted and skipped;
  * different text is rejected (never written silently).
  */
final class VectorIngestPipeline(
    embedder: EmbeddingPort,
    textStore: TextStore,
    vectors: VectorIndexPort,
    kv: KeyValueStore,
    tables: DynamoDbSettings,
    spec: IndexSpecCatalog,
    windows: List[NamedWindow],
    settings: IngestSettings,
    rejects: RejectLog,
    sleeper: Sleeper,
    extrasOf: SemanticDocument => DocumentExtras = _ => DocumentExtras.none
) {
  require(
    embedder.dimensions == spec.dimension,
    s"embedder dimensions ${embedder.dimensions} differ from the index dimension ${spec.dimension}"
  )
  require(
    embedder.modelId != null && embedder.modelId.matches("\\S+") &&
      embedder.modelId.length <= VectorMetadata.MaxFieldChars,
    "embedder modelId must be non-empty, without whitespace, at most 1024 characters"
  )
  require(embedder.maxBatchSize > 0, "embedder maxBatchSize must be > 0")

  private val labelOf: Map[YearWindow, String] = windows.map(w => w.window -> w.label).toMap

  def open(runId: RunId, label: String): IngestSession = new IngestSession(runId, label)

  /** Runs one complete ingest of `documents` under `runId` (one RunLedger attempt). */
  def run(runId: RunId, documents: Iterator[SemanticDocument]): IngestReport = {
    val s = open(runId, "vector-ingest")
    s.ingest(documents)
    s.finish()
  }

  private final case class Pending(
      doc: SemanticDocument,
      meta: VectorMetadata,
      record: VectorRecord,
      prior: Option[ArtifactVersion]
  )

  final class IngestSession private[VectorIngestPipeline] (val runId: RunId, label: String) {
    val ledger: RunLedger = RunLedger.open(kv, tables.runLedgerTable, runId, label)
    private val registry = new ArtifactRegistry(kv, tables.artifactRegistryTable)
    private val writer = new VectorWriter(vectors, settings.retry, sleeper)
    private val stats = ListMap(spec.indexes.map(i => i.name -> new IndexStats): _*)
    private val seen = mutable.HashMap.empty[(String, String), String]
    private val pending =
      mutable.LinkedHashMap(spec.indexes.map(i => i.name -> mutable.ArrayBuffer.empty[Pending]): _*)
    private var textCreated, textPresent, embedCalls, embedRetries, putCalls, putRetries,
        conflicts = 0L
    private val nanos =
      mutable.LinkedHashMap("text" -> 0L, "registry" -> 0L, "embed" -> 0L, "put" -> 0L)
    private val started = System.nanoTime()
    private var docNo = 0L

    private def timed[A](k: String)(f: => A): A = {
      val t0 = System.nanoTime()
      try f
      finally nanos.update(k, nanos(k) + (System.nanoTime() - t0))
    }

    private def reject(doc: Option[SemanticDocument], index: String, reason: String): Unit = {
      stats.get(index).foreach(_.rejected += 1)
      rejects.record(
        Reject("vector-ingest", index, doc.map(_.vectorKey).getOrElse("-"), docNo, reason)
      )
      ledger.record(
        "vector-reject",
        "indexName" -> AttrValue.S(index),
        "vectorKey" -> AttrValue.S(doc.map(_.vectorKey).getOrElse("-").take(1024)),
        "reason" -> AttrValue.S(reason.take(1000))
      )
    }

    def ingest(documents: Iterator[SemanticDocument]): Unit =
      documents.grouped(embedder.maxBatchSize).foreach(chunk => processChunk(chunk.toVector))

    private def processChunk(chunk: Vector[SemanticDocument]): Unit = {
      val toEmbed = mutable.ArrayBuffer.empty[(SemanticDocument, TextPut, Option[ArtifactVersion])]
      chunk.foreach { doc =>
        docNo += 1
        val entry = spec.forType(doc.documentType)
        val st = stats(entry.name)
        st.input += 1
        val windowLabel = doc.window.flatMap(labelOf.get)
        val problems: List[String] =
          Contract.validate(doc) ++
            (if (doc.indexName != entry.name)
               List(
                 s"indexName '${doc.indexName}' is not the ${doc.documentType.wire} index '${entry.name}'"
               )
             else Nil) ++
            (if (doc.window.isDefined && windowLabel.isEmpty)
               List(s"window ${doc.window.get} is not a configured graph window")
             else Nil) ++
            (if (doc.window.isEmpty || windowLabel.isDefined)
               VectorKeys.check(doc, windowLabel).left.toSeq
             else Nil)
        if (problems.nonEmpty) reject(Some(doc), entry.name, problems.mkString("; "))
        else {
          val hash = ContentHash.of(doc.text)
          seen.get((entry.name, doc.vectorKey)) match {
            case Some(h) if h == hash => st.duplicatesSameText += 1
            case Some(_)              =>
              reject(Some(doc), entry.name, "duplicate vector key with different text in one run")
            case None =>
              seen.update((entry.name, doc.vectorKey), hash)
              val put = timed("text")(textStore.put(entry.name, doc.text))
              put.outcome match {
                case PutOutcome.Conflict(_) =>
                  reject(Some(doc), entry.name, s"text object conflict at ${put.ref.key}")
                case o =>
                  if (o == PutOutcome.Created) textCreated += 1 else textPresent += 1
                  val prior = timed("registry")(
                    registry.latest(
                      VectorIngestPipeline.ArtifactKind,
                      artifactKey(entry.name, doc.vectorKey)
                    )
                  )
                  val unchanged = prior.exists { p =>
                    p.item.strOpt("textHash").contains(hash) &&
                    p.item.strOpt("embeddingModel").contains(embedder.modelId) &&
                    p.item.long("embeddingDimensions").contains(embedder.dimensions.toLong)
                  }
                  if (unchanged) st.skippedUnchanged += 1
                  else toEmbed += ((doc, put, prior))
              }
          }
        }
      }
      if (toEmbed.nonEmpty) embedAndQueue(toEmbed.toVector)
      flush(force = false)
    }

    private def embedAndQueue(
        batch: Vector[(SemanticDocument, TextPut, Option[ArtifactVersion])]
    ): Unit = {
      embedCalls += 1
      val result = timed("embed")(embedder.embed(batch.map(_._1.text)))
      val checked: Either[(String, Int), IndexedSeq[Array[Float]]] = result match {
        case Left(f)  => Left((f.message, f.retries))
        case Right(b) =>
          embedRetries += b.retries
          if (b.retries > 0)
            ledger.record(
              "embed-retried",
              "retries" -> AttrValue.n(b.retries.toLong),
              "texts" -> AttrValue.n(batch.size.toLong)
            )
          if (b.vectors.size != batch.size)
            Left(
              (s"embedder returned ${b.vectors.size} vectors for ${batch.size} texts", b.retries)
            )
          else if (
            b.vectors.exists(v =>
              v.length != embedder.dimensions || v.exists(f => f.isNaN || f.isInfinite)
            )
          )
            Left(
              (
                "embedder returned a vector of the wrong dimension or with non-finite values",
                b.retries
              )
            )
          else Right(b.vectors)
      }
      checked match {
        case Left((msg, retries)) =>
          embedRetries += (if (result.isLeft) retries else 0)
          ledger.record(
            "embed-failure",
            "reason" -> AttrValue.S(msg.take(1000)),
            "retries" -> AttrValue.n(retries.toLong),
            "texts" -> AttrValue.n(batch.size.toLong)
          )
          batch.foreach { case (doc, _, _) =>
            reject(Some(doc), doc.indexName, s"embedding failed: $msg")
          }
        case Right(vs) =>
          batch.zip(vs).foreach { case ((doc, put, prior), v) =>
            val entry = spec.forType(doc.documentType)
            val meta = metadataFor(doc, put)
            VectorMetadataCodec.encode(meta, entry) match {
              case Left(errs) => reject(Some(doc), entry.name, s"metadata: ${errs.mkString("; ")}")
              case Right(stored) =>
                VectorMetadataCodec.checkLimits(stored, entry, Some(doc.text)) match {
                  case Nil =>
                    pending(entry.name) += Pending(
                      doc,
                      meta,
                      VectorRecord(doc.vectorKey, v, stored),
                      prior
                    )
                  case errs => reject(Some(doc), entry.name, s"metadata: ${errs.mkString("; ")}")
                }
            }
          }
      }
    }

    private def metadataFor(doc: SemanticDocument, put: TextPut): VectorMetadata = {
      import SemanticDocumentType._
      val t = doc.documentType
      val head = doc.subjectIds.head
      val extras = extrasOf(doc)
      val isInstitution = t == TopicInstitutionSummary && OpenAlexId.Institution.isCanonical(head)
      VectorMetadata(
        schemaVersion = SchemaVersions.currentOf("VectorMetadata"),
        vectorKey = doc.vectorKey,
        indexName = doc.indexName,
        documentId = doc.documentId,
        documentType = t,
        researcherId = Option.when(t == ResearcherProfile)(ResearcherId(head)),
        communityId = Option.when(t == CommunitySummary)(ResearcherId(head)),
        startResearcherId = Option.when(t == CollaborationPath)(ResearcherId(head)),
        endResearcherId = Option.when(t == CollaborationPath)(ResearcherId(doc.subjectIds.last)),
        workId = Option.when(t == PaperSummary)(WorkId(head)),
        topicId = Option.when(t == TopicInstitutionSummary && !isInstitution)(head),
        institutionId = Option.when(isInstitution)(head),
        windowStartYear = doc.window.map(_.startYear),
        windowEndYear = doc.window.map(_.endYear),
        year = extras.year,
        venueKey = extras.venueKey,
        pathLength = Option.when(t == CollaborationPath)(doc.subjectIds.size - 1),
        sourceDataset = settings.sourceDataset,
        textS3Uri = put.ref.uri,
        textHash = put.textHash,
        sourceEvidenceId = doc.sourceEvidenceId,
        embeddingModel = embedder.modelId,
        embeddingDimensions = embedder.dimensions,
        runId = runId,
        provenance = Provenance(
          SchemaVersions.currentOf("Provenance"),
          SourceType.Pipeline,
          doc.provenance.datasetId,
          doc.provenance.snapshotId,
          doc.provenance.sourceRecordIds,
          runId,
          settings.jobName,
          doc.provenance.policyVersion,
          Some(put.textHash)
        )
      )
    }

    private def flush(force: Boolean): Unit =
      pending.foreach { case (index, buf) =>
        if (buf.nonEmpty && (force || buf.size >= settings.putBatchSize)) {
          val items = buf.toVector
          buf.clear()
          val byKey = items.map(p => p.record.key -> p).toMap
          PutVectorsBatcher.batches(items.map(_.record), settings.putBatchSize).foreach { batch =>
            putCalls += 1
            val r = timed("put")(writer.put(index, batch))
            putRetries += r.retries
            r.failure match {
              case Some(e) =>
                ledger.record(
                  "put-failure",
                  "indexName" -> AttrValue.S(index),
                  "vectors" -> AttrValue.n(batch.size.toLong),
                  "retries" -> AttrValue.n(r.retries.toLong),
                  "transient" -> AttrValue.Bool(e.transient),
                  "reason" -> AttrValue.S(Option(e.getMessage).getOrElse("").take(1000))
                )
                batch.foreach(v =>
                  reject(
                    Some(byKey(v.key).doc),
                    index,
                    s"PutVectors failed after ${r.retries} retries: ${e.getMessage}"
                  )
                )
              case None =>
                if (r.retries > 0)
                  ledger.record(
                    "put-retried",
                    "indexName" -> AttrValue.S(index),
                    "retries" -> AttrValue.n(r.retries.toLong)
                  )
                batch.foreach(v => registerWritten(index, byKey(v.key)))
            }
          }
        }
      }

    private def registerWritten(index: String, p: Pending): Unit = {
      val st = stats(index)
      st.written += 1
      val previousVersion = p.prior.map(_.version).getOrElse(0L)
      val prevAttrs = p.prior.toList.flatMap { pr =>
        List(
          pr.item.strOpt("textHash").map("previousTextHash" -> AttrValue.S(_)),
          pr.item.strOpt("embeddingModel").map("previousEmbeddingModel" -> AttrValue.S(_)),
          pr.item.long("embeddingDimensions").map("previousEmbeddingDimensions" -> AttrValue.n(_))
        ).flatten
      }
      val change = timed("registry")(
        registry.register(
          VectorIngestPipeline.ArtifactKind,
          artifactKey(index, p.meta.vectorKey),
          previousVersion,
          (List(
            "indexName" -> AttrValue.S(index),
            "vectorKey" -> AttrValue.S(p.meta.vectorKey),
            "documentId" -> AttrValue.S(p.meta.documentId.value),
            "textHash" -> AttrValue.S(p.meta.textHash),
            "textS3Uri" -> AttrValue.S(p.meta.textS3Uri),
            "embeddingModel" -> AttrValue.S(p.meta.embeddingModel),
            "embeddingDimensions" -> AttrValue.n(p.meta.embeddingDimensions.toLong),
            "runId" -> AttrValue.S(runId.value)
          ) ++ prevAttrs): _*
        )
      )
      if (change == Change.Conflict) {
        conflicts += 1
        ledger.record(
          "registry-conflict",
          "indexName" -> AttrValue.S(index),
          "vectorKey" -> AttrValue.S(p.meta.vectorKey)
        )
      }
      p.prior match {
        case None     => st.embeddedNew += 1
        case Some(pr) =>
          st.reembeddedChanged += 1
          ledger.record(
            "vector-overwrite",
            (List(
              "indexName" -> AttrValue.S(index),
              "vectorKey" -> AttrValue.S(p.meta.vectorKey),
              "newTextHash" -> AttrValue.S(p.meta.textHash),
              "newEmbeddingModel" -> AttrValue.S(p.meta.embeddingModel),
              "newEmbeddingDimensions" -> AttrValue.n(p.meta.embeddingDimensions.toLong),
              "previousVersion" -> AttrValue.n(pr.version)
            ) ++ prevAttrs.map { case (k, v) => k.replace("previous", "old") -> v }): _*
          )
      }
    }

    def finish(): IngestReport = {
      flush(force = true)
      val millis = ListMap.from(nanos.map { case (k, v) => k -> v / 1000000L }) +
        ("total" -> (System.nanoTime() - started) / 1000000L)
      val report = IngestReport(
        runId,
        ledger.attempt,
        stats,
        textCreated,
        textPresent,
        embedCalls,
        embedRetries,
        putCalls,
        putRetries,
        conflicts,
        0L,
        millis
      )
      ledger.record(
        "ingest-summary",
        "input" -> AttrValue.n(report.input),
        "skippedUnchanged" -> AttrValue.n(report.skipped),
        "written" -> AttrValue.n(report.written),
        "rejected" -> AttrValue.n(report.rejected),
        "embedRetries" -> AttrValue.n(embedRetries),
        "putRetries" -> AttrValue.n(putRetries)
      )
      report.copy(ledgerEvents = ledger.eventsAppended)
    }
  }

  private def artifactKey(index: String, vectorKey: String) = s"$index/$vectorKey"
}

object VectorIngestPipeline {
  val JobName = "storage.vector-ingest"
  val ArtifactKind = "vector"
}
