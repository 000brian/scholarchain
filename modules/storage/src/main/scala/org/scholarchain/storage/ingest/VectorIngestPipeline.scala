package org.scholarchain.storage.ingest

import scala.collection.immutable.ListMap
import scala.collection.mutable
import scala.util.control.NonFatal

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
import org.scholarchain.storage.{RetryPolicy, S3VectorsLimits, Sleeper, StoreException}
import org.scholarchain.storage.dynamo.{ArtifactRegistry, ArtifactVersion, Change, RunLedger}
import org.scholarchain.storage.ports._
import org.scholarchain.storage.source.{Reject, RejectLog}
import org.scholarchain.storage.vectors._

/** Optional metadata a renderer can attach to a document (for example a paper's year and venue);
  * SemanticDocument does not carry these fields.
  */
final case class DocumentExtras(year: Option[Int], venueKey: Option[String])
object DocumentExtras { val none: DocumentExtras = DocumentExtras(None, None) }

/** Ingest settings. The three G-27 bounds have defaults (named constants below); every one is
  * bounded.
  *
  * @param writeParallelism
  *   store calls in flight at once for the per-document writes and reads that cannot be batched:
  *   text puts (S3 `If-None-Match`), the registry's latest-version read and the conditional
  *   registry puts (DynamoDB `attribute_not_exists`; conditional writes cannot be batched)
  * @param embedConcurrency
  *   embedding calls in flight at once (the provider's own rate limiter still applies)
  * @param windowDocuments
  *   documents read ahead per skip-check window; the skip check reads the index metadata of a
  *   window in GetVectors calls of at most 100 keys
  */
final case class IngestSettings(
    putBatchSize: Int,
    retry: RetryPolicy,
    sourceDataset: String,
    jobName: String = VectorIngestPipeline.JobName,
    writeParallelism: Int = IngestSettings.DefaultWriteParallelism,
    embedConcurrency: Int = IngestSettings.DefaultEmbedConcurrency,
    windowDocuments: Int = IngestSettings.DefaultWindowDocuments
) {
  require(putBatchSize >= 1 && putBatchSize <= S3VectorsLimits.MaxPutVectorsBatch, "putBatchSize")
  require(
    writeParallelism >= 1 && writeParallelism <= IngestSettings.MaxWriteParallelism,
    s"writeParallelism must be in 1..${IngestSettings.MaxWriteParallelism}"
  )
  require(
    embedConcurrency >= 1 && embedConcurrency <= IngestSettings.MaxEmbedConcurrency,
    s"embedConcurrency must be in 1..${IngestSettings.MaxEmbedConcurrency}"
  )
  require(
    windowDocuments >= 1 && windowDocuments <= IngestSettings.MaxWindowDocuments,
    s"windowDocuments must be in 1..${IngestSettings.MaxWindowDocuments}"
  )
}

object IngestSettings {

  /** 16 calls in flight: S3 allows 3,500 PUT/s per prefix and on-demand DynamoDB about 1,000 write
    * units/s per partition (registry keys spread over partitions), so 16 synchronous calls of about
    * 10 to 60 ms each (at most about 1,600 calls/s) stay well under both, while turning the
    * per-document latency of Phase 14 (about 52 ms per document, single writer) into about 1/16 of
    * it. The ECS task is I/O bound; 16 threads cost little memory.
    */
  val DefaultWriteParallelism: Int = 16
  val MaxWriteParallelism: Int = 64

  /** 4 embedding calls in flight: with `embedding.requestsPerMinute` 300 (5 requests/s) and about 1
    * s per call (estimate), 4 in flight stays under the request limit; the token limit (1 M/min) is
    * enforced by rag's rate limiter, which every call passes.
    */
  val DefaultEmbedConcurrency: Int = 4
  val MaxEmbedConcurrency: Int = 16

  /** 500 documents per window: exactly 5 GetVectors calls of 100 keys when a window holds one index
    * (documents arrive grouped by index), and at most 8 embedding batches of 64.
    */
  val DefaultWindowDocuments: Int = 500
  val MaxWindowDocuments: Int = 5000
}

/** Counters of one index in one ingest run. Updated only on the session's thread. */
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
    millis: ListMap[String, Long],
    skipReadCalls: Long = 0L,
    registryReads: Long = 0L,
    registryWrites: Long = 0L
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
    "skipReadCalls" -> skipReadCalls.toDouble,
    "registryReads" -> registryReads.toDouble,
    "registryWrites" -> registryWrites.toDouble,
    "registryConflicts" -> registryConflicts.toDouble,
    "ledgerEvents" -> ledgerEvents.toDouble,
    "millis" -> ujson.Obj.from(millis.map { case (k, v) => k -> ujson.Num(v.toDouble) })
  )
}

/** The embedding ingest pipeline (spec Phase 8, storage work; G-27 batched and parallel in Phase
  * 15). Documents are taken in windows of `windowDocuments`; for each window:
  *
  *   1. validate every document and drop in-run duplicates (same text counted, different text
  *      rejected);
  *   2. skip check, batched: read the index metadata of the window's keys with GetVectors (at most
  *      100 keys per call, no vector data); a key whose stored `textHash`, `embeddingModel` and
  *      `embeddingDimensions` equal the document's is skipped. A skipped document makes no
  *      per-document call at all (no text put, no registry read, no write);
  *   3. for every other document, in parallel (`writeParallelism`): write the text (S3 key holding
  *      the text hash, put-if-absent) and read the latest ArtifactRegistry version;
  *   4. embed in batches of the embedder's `maxBatchSize`, `embedConcurrency` calls at once;
  *   5. put the vectors with their metadata (batches of at most `putBatchSize` <= 500, under 20
  *      MiB, bounded retries), then register the new versions in ArtifactRegistry in parallel
  *      conditional puts (after the put, so the registry never claims a vector that was not
  *      written). A changed text or model is re-embedded and its old and new hash (and model) are
  *      recorded in RunLedger; never overwritten silently.
  *
  * Rejected documents and vectors go to the reject file and RunLedger; failures and retry counts go
  * to RunLedger. Every counter is updated on the session's thread from the results of the parallel
  * calls, so counts are exact. An [[IngestSession]] is used from one thread.
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

  private final case class Accepted(doc: SemanticDocument, index: String, hash: String)

  private final case class ToEmbed(
      doc: SemanticDocument,
      put: TextPut,
      prior: Option[ArtifactVersion]
  )

  final class IngestSession private[VectorIngestPipeline] (val runId: RunId, label: String) {
    val ledger: RunLedger = RunLedger.open(kv, tables.runLedgerTable, runId, label)
    private val registry = new ArtifactRegistry(kv, tables.artifactRegistryTable)
    private val writer = new VectorWriter(vectors, settings.retry, sleeper)
    private val writePool = new BoundedPool("ingest-write", settings.writeParallelism)
    private val embedPool = new BoundedPool("ingest-embed", settings.embedConcurrency)
    private val stats = ListMap(spec.indexes.map(i => i.name -> new IndexStats): _*)
    private val seen = mutable.HashMap.empty[(String, String), String]
    private val pending =
      mutable.LinkedHashMap(spec.indexes.map(i => i.name -> mutable.ArrayBuffer.empty[Pending]): _*)
    private var textCreated, textPresent, embedCalls, embedRetries, putCalls, putRetries, conflicts,
        skipReadCalls, registryReads, registryWrites = 0L
    private val nanos = mutable.LinkedHashMap(
      "skipRead" -> 0L,
      "text" -> 0L,
      "embed" -> 0L,
      "put" -> 0L,
      "registry" -> 0L
    )
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
      documents.grouped(settings.windowDocuments).foreach(w => processWindow(w.toVector))

    private def processWindow(window: Vector[SemanticDocument]): Unit = {
      val accepted = window.flatMap(accept)
      val toWrite = skipCheck(accepted)
      if (toWrite.nonEmpty) {
        val toEmbed = prepare(toWrite)
        if (toEmbed.nonEmpty) embedAndQueue(toEmbed)
      }
      flush(force = false)
    }

    /** Validation and in-run duplicates (no store call). */
    private def accept(doc: SemanticDocument): Option[Accepted] = {
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
      if (problems.nonEmpty) {
        reject(Some(doc), entry.name, problems.mkString("; "))
        None
      } else {
        val hash = ContentHash.of(doc.text)
        seen.get((entry.name, doc.vectorKey)) match {
          case Some(h) if h == hash =>
            st.duplicatesSameText += 1
            None
          case Some(_) =>
            reject(Some(doc), entry.name, "duplicate vector key with different text in one run")
            None
          case None =>
            seen.update((entry.name, doc.vectorKey), hash)
            Some(Accepted(doc, entry.name, hash))
        }
      }
    }

    /** The batched skip check: GetVectors of at most 100 keys per call (metadata only), at most
      * `writeParallelism` calls at once. Returns the documents to write. A read that fails (after
      * the port's bounded retries) rejects the documents of that call; they are retried by the next
      * run.
      */
    private def skipCheck(accepted: Vector[Accepted]): Vector[Accepted] = {
      val calls: Vector[(String, Vector[Accepted])] =
        accepted
          .groupBy(_.index)
          .toVector
          .sortBy(_._1)
          .flatMap { case (index, as) =>
            as.grouped(S3VectorsLimits.MaxGetVectorsBatch).map(index -> _)
          }
      val results = timed("skipRead")(writePool.map(calls) { case (index, as) =>
        try Right(vectors.getVectors(index, as.map(_.doc.vectorKey), returnData = false))
        catch { case NonFatal(e) => Left(e) }
      })
      skipReadCalls += calls.size
      val unchanged = mutable.HashSet.empty[(String, String)]
      val failed = mutable.HashMap.empty[(String, String), String]
      calls.zip(results).foreach {
        case ((index, as), Left(e)) =>
          val transient = e match {
            case s: StoreException => s.transient
            case _                 => false
          }
          ledger.record(
            "skip-read-failure",
            "indexName" -> AttrValue.S(index),
            "vectors" -> AttrValue.n(as.size.toLong),
            "transient" -> AttrValue.Bool(transient),
            "reason" -> AttrValue.S(e.getClass.getSimpleName)
          )
          as.foreach(a => failed.update((index, a.doc.vectorKey), e.getClass.getSimpleName))
        case ((index, as), Right(stored)) =>
          val byKey = stored.map(v => v.key -> v.metadata).toMap
          as.foreach { a =>
            if (
              byKey
                .get(a.doc.vectorKey)
                .exists(m => VectorIngestPipeline.sameVector(m, a.hash, embedder))
            )
              unchanged += ((index, a.doc.vectorKey))
          }
      }
      accepted.filter { a =>
        val k = (a.index, a.doc.vectorKey)
        failed.get(k) match {
          case Some(cls) =>
            reject(Some(a.doc), a.index, s"skip check failed: GetVectors $cls")
            false
          case None if unchanged(k) =>
            stats(a.index).skippedUnchanged += 1
            false
          case None => true
        }
      }
    }

    /** Text puts and latest-version reads of the documents to write, in parallel. */
    private def prepare(toWrite: Vector[Accepted]): Vector[ToEmbed] = {
      val results = timed("text")(writePool.map(toWrite) { a =>
        val put = textStore.put(a.index, a.doc.text)
        val prior = put.outcome match {
          case PutOutcome.Conflict(_) => None
          case _                      =>
            registry.latest(
              VectorIngestPipeline.ArtifactKind,
              artifactKey(a.index, a.doc.vectorKey)
            )
        }
        (put, prior)
      })
      toWrite.zip(results).flatMap { case (a, (put, prior)) =>
        put.outcome match {
          case PutOutcome.Conflict(_) =>
            reject(Some(a.doc), a.index, s"text object conflict at ${put.ref.key}")
            None
          case o =>
            if (o == PutOutcome.Created) textCreated += 1 else textPresent += 1
            registryReads += 1
            Some(ToEmbed(a.doc, put, prior))
        }
      }
    }

    private def embedAndQueue(all: Vector[ToEmbed]): Unit = {
      val batches = all.grouped(embedder.maxBatchSize).toVector
      // an embedder that throws (a defect: the port returns Left) fails the run, as before
      val results = timed("embed")(embedPool.map(batches)(b => embedder.embed(b.map(_.doc.text))))
      embedCalls += batches.size
      batches.zip(results).foreach { case (b, r) => queueBatch(b, r) }
    }

    private def queueBatch(
        batch: Vector[ToEmbed],
        result: Either[EmbeddingFailure, EmbeddingBatch]
    ): Unit = {
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
          batch.foreach(t => reject(Some(t.doc), t.doc.indexName, s"embedding failed: $msg"))
        case Right(vs) =>
          batch.zip(vs).foreach { case (t, v) =>
            val entry = spec.forType(t.doc.documentType)
            val meta = metadataFor(t.doc, t.put)
            VectorMetadataCodec.encode(meta, entry) match {
              case Left(errs) =>
                reject(Some(t.doc), entry.name, s"metadata: ${errs.mkString("; ")}")
              case Right(stored) =>
                VectorMetadataCodec.checkLimits(stored, entry, Some(t.doc.text)) match {
                  case Nil =>
                    pending(entry.name) += Pending(
                      t.doc,
                      meta,
                      VectorRecord(t.doc.vectorKey, v, stored),
                      t.prior
                    )
                  case errs => reject(Some(t.doc), entry.name, s"metadata: ${errs.mkString("; ")}")
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
                registerWritten(index, batch.map(v => byKey(v.key)))
            }
          }
        }
      }

    private def prevAttrs(p: Pending): List[(String, AttrValue)] =
      p.prior.toList.flatMap { pr =>
        List(
          pr.item.strOpt("textHash").map("previousTextHash" -> AttrValue.S(_)),
          pr.item.strOpt("embeddingModel").map("previousEmbeddingModel" -> AttrValue.S(_)),
          pr.item.long("embeddingDimensions").map("previousEmbeddingDimensions" -> AttrValue.n(_))
        ).flatten
      }

    /** Conditional registry puts of one written batch, in parallel; counters and ledger events
      * afterwards, on this thread.
      */
    private def registerWritten(index: String, written: Vector[Pending]): Unit = {
      val changes = timed("registry")(writePool.map(written) { p =>
        registry.register(
          VectorIngestPipeline.ArtifactKind,
          artifactKey(index, p.meta.vectorKey),
          p.prior.map(_.version).getOrElse(0L),
          (List(
            "indexName" -> AttrValue.S(index),
            "vectorKey" -> AttrValue.S(p.meta.vectorKey),
            "documentId" -> AttrValue.S(p.meta.documentId.value),
            "textHash" -> AttrValue.S(p.meta.textHash),
            "textS3Uri" -> AttrValue.S(p.meta.textS3Uri),
            "embeddingModel" -> AttrValue.S(p.meta.embeddingModel),
            "embeddingDimensions" -> AttrValue.n(p.meta.embeddingDimensions.toLong),
            "runId" -> AttrValue.S(runId.value)
          ) ++ prevAttrs(p)): _*
        )
      })
      registryWrites += written.size
      written.zip(changes).foreach { case (p, change) =>
        val st = stats(index)
        st.written += 1
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
              ) ++ prevAttrs(p).map { case (k, v) => k.replace("previous", "old") -> v }): _*
            )
        }
      }
    }

    def finish(): IngestReport =
      try {
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
          millis,
          skipReadCalls,
          registryReads,
          registryWrites
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
      } finally {
        writePool.close()
        embedPool.close()
      }
  }

  private def artifactKey(index: String, vectorKey: String) = s"$index/$vectorKey"
}

object VectorIngestPipeline {
  val JobName = "storage.vector-ingest"
  val ArtifactKind = "vector"

  /** The skip rule: the stored vector's metadata holds the document's `textHash` and the embedder's
    * `embeddingModel` and `embeddingDimensions`. Nothing else is compared (not the run ID, code
    * version or job), so a new build alone never forces re-embedding.
    */
  def sameVector(stored: StoredMetadata, textHash: String, embedder: EmbeddingPort): Boolean = {
    val m = stored.all
    m.get("textHash").contains(MetaValue.Str(textHash)) &&
    m.get("embeddingModel").contains(MetaValue.Str(embedder.modelId)) &&
    m.get("embeddingDimensions").contains(MetaValue.Num(embedder.dimensions.toLong))
  }
}
