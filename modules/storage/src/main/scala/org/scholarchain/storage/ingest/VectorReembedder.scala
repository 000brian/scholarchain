package org.scholarchain.storage.ingest

import java.util.concurrent.atomic.AtomicLong

import scala.collection.mutable
import scala.util.control.NonFatal

import org.scholarchain.core.{SemanticDocumentType, VectorKey}
import org.scholarchain.storage.{RetryPolicy, S3VectorsLimits, Sleeper, StoreException}
import org.scholarchain.storage.dynamo.TableSpecs
import org.scholarchain.storage.ports._
import org.scholarchain.storage.vectors.{
  IndexSpecCatalog,
  PutVectorsBatcher,
  VectorMetadataCodec,
  VectorWriter
}

/** One re-embed task: re-embed the existing vector `vectorKey` of `index` under its existing key.
  * `taskId` names the task (the `embedding-task-v1` message's `taskId`); it is part of the
  * completion record's key.
  */
final case class ReembedTask(taskId: String, index: String, vectorKey: String)

/** What one delivery of a [[ReembedTask]] did. `code` is a fixed word (never text, URL or store
  * message): `processed`, `replayed`, or the failure reason.
  */
sealed trait ReembedOutcome {
  def task: ReembedTask
  def code: String
}

object ReembedOutcome {

  /** This delivery embedded the current text and wrote the vector. `completionRecorded` is false
    * when the completion record could not be written after the vector write (a redelivery then
    * embeds again; the final state is the same).
    */
  final case class Processed(task: ReembedTask, textHash: String, completionRecorded: Boolean)
      extends ReembedOutcome {
    def code: String = "processed"
  }

  /** A completion record for (taskId, current textHash) exists: no embedding call, no write. */
  final case class Replayed(task: ReembedTask, textHash: String) extends ReembedOutcome {
    def code: String = "replayed"
  }

  /** Never succeeds on redelivery (unknown index or key, metadata missing, model mismatch, text
    * missing, a refused write). Nothing was written. `detail` holds field names or an exception
    * class name only.
    */
  final case class PermanentFailure(task: ReembedTask, code: String, detail: String)
      extends ReembedOutcome

  /** May succeed on redelivery (throttling, 5xx, timeouts, embedding provider failure). */
  final case class TransientFailure(task: ReembedTask, code: String, detail: String)
      extends ReembedOutcome

  /** The permanent failure codes. */
  val PermanentCodes: List[String] = List(
    "invalid-task",
    "unknown-index",
    "unknown-vector-key",
    "metadata-missing",
    "metadata-invalid",
    "model-mismatch",
    "text-missing",
    "text-hash-mismatch",
    "vector-read-refused",
    "completion-read-refused",
    "text-read-refused",
    "embed-invalid",
    "vector-write-refused"
  )

  /** The transient failure codes. */
  val TransientCodes: List[String] = List(
    "vector-read-failed",
    "completion-read-failed",
    "text-read-failed",
    "embed-failed",
    "vector-write-failed"
  )
}

/** Counters of one [[VectorReembedder]], exact under concurrent callers (atomic). */
final class ReembedCounters {
  val tasks = new AtomicLong()
  val processed = new AtomicLong()
  val replayed = new AtomicLong()
  val permanentFailures = new AtomicLong()
  val transientFailures = new AtomicLong()
  val embedCalls = new AtomicLong()
  val putCalls = new AtomicLong()
  val completionWrites = new AtomicLong()
  val completionMissing = new AtomicLong()

  def toJson: ujson.Obj = ujson.Obj(
    "tasks" -> tasks.get.toDouble,
    "processed" -> processed.get.toDouble,
    "replayed" -> replayed.get.toDouble,
    "permanentFailures" -> permanentFailures.get.toDouble,
    "transientFailures" -> transientFailures.get.toDouble,
    "embedCalls" -> embedCalls.get.toDouble,
    "putCalls" -> putCalls.get.toDouble,
    "completionWrites" -> completionWrites.get.toDouble,
    "completionMissing" -> completionMissing.get.toDouble
  )
}

/** The re-embed write path (Phase 15, experiments C and F): writes a new embedding of a document's
  * current text under the vector's existing key with its existing metadata. Only the vector data
  * changes; no new key, no text object, no ArtifactRegistry version.
  *
  * Per delivery (in this order):
  *   1. validate the task (taskId pattern, a spec index, a valid vector key);
  *   2. GetVectors with metadata (`returnMetadata = true`, no data) for the key; a missing key, or
  *      metadata that lacks a field later reads depend on (every key [[VectorMetadataCodec.decode]]
  *      requires, plus `researcherId` in the researcher-profile index), or a stored
  *      `embeddingModel`/`embeddingDimensions` different from the embedder's, is a permanent
  *      failure: nothing is embedded or written;
  *   3. the completion record of (taskId, current `textHash`) in QuerySession (QS6): present and
  *      not expired means `Replayed` (no embedding call, no write);
  *   4. read the text at `textS3Uri`, verified against `textHash`;
  *   5. embed (batches of the embedder's `maxBatchSize`);
  *   6. PutVectors with the new data and exactly the metadata read in step 2 (PutVectors replaces
  *      the whole vector, so the metadata is rewritten, not merged); bounded retries;
  *   7. create the completion record (put-if-absent, `expiresAt` = now + `completionTtlSeconds`).
  *
  * Guarantees: (a) final state: after any number of deliveries the vector holds the embedding of
  * the current text with unchanged metadata, provided the text does not change while a task is in
  * progress; (b) no embedding call after a completed delivery of the same (taskId, textHash). Not
  * guaranteed: exactly-once processing (two deliveries in flight at once, or a crash between the
  * vector write and the completion record, embed twice; both count as `processed`, so concurrent
  * duplicates are not observable in the counters).
  *
  * Thread-safe: no mutable state except the atomic [[counters]]. Pass the retrying ports of
  * [[org.scholarchain.storage.aws.AwsStorageEnvironment]]: reads are retried by them; PutVectors is
  * retried here (as in the ingest pipeline).
  */
final class VectorReembedder(
    embedder: EmbeddingPort,
    vectors: VectorIndexPort,
    textStore: TextStore,
    kv: KeyValueStore,
    completionTable: String,
    spec: IndexSpecCatalog,
    retry: RetryPolicy,
    sleeper: Sleeper,
    nowSeconds: () => Long = () => System.currentTimeMillis() / 1000L,
    completionTtlSeconds: Long = VectorReembedder.CompletionTtlSeconds
) {
  import ReembedOutcome._
  import VectorReembedder._

  require(completionTable != null && completionTable.nonEmpty, "a completion table is required")
  require(completionTtlSeconds > 0, "completionTtlSeconds must be > 0")
  require(embedder.maxBatchSize > 0, "embedder maxBatchSize must be > 0")

  val counters = new ReembedCounters
  private val writer = new VectorWriter(vectors, retry, sleeper)

  private final case class Ready(
      task: ReembedTask,
      stored: StoredMetadata,
      textHash: String,
      uri: String
  )

  private def failure(e: Throwable, transientCode: String, permanentCode: String)(
      t: ReembedTask
  ): ReembedOutcome = e match {
    case s: StoreException if s.transient =>
      TransientFailure(t, transientCode, e.getClass.getSimpleName)
    case _ => PermanentFailure(t, permanentCode, e.getClass.getSimpleName)
  }

  /** Processes up to [[MaxTasksPerCall]] tasks; outcomes in input order. */
  def reembed(tasks: Seq[ReembedTask]): Vector[ReembedOutcome] = {
    require(tasks.size <= MaxTasksPerCall, s"at most $MaxTasksPerCall tasks per call")
    counters.tasks.addAndGet(tasks.size.toLong)
    val out = mutable.LinkedHashMap.empty[Int, ReembedOutcome]
    val indexed = tasks.toVector.zipWithIndex

    // 1. validation
    val valid = indexed.filter { case (t, i) =>
      validate(t) match {
        case Some(f) => out.update(i, f); false
        case None    => true
      }
    }

    // 2. vector metadata, one GetVectors per index (at most 100 distinct keys)
    val ready = mutable.ArrayBuffer.empty[(Ready, Int)]
    valid.groupBy(_._1.index).toVector.sortBy(_._1).foreach { case (index, group) =>
      val keys = group.map(_._1.vectorKey).distinct
      val read =
        try Right(vectors.getVectors(index, keys, returnData = false))
        catch { case NonFatal(e) => Left(e) }
      read match {
        case Left(e) =>
          group.foreach { case (t, i) =>
            out.update(i, failure(e, "vector-read-failed", "vector-read-refused")(t))
          }
        case Right(found) =>
          val byKey = found.map(v => v.key -> v.metadata).toMap
          group.foreach { case (t, i) =>
            byKey.get(t.vectorKey) match {
              case None    => out.update(i, PermanentFailure(t, "unknown-vector-key", ""))
              case Some(m) =>
                checkMetadata(t, m) match {
                  case Left(f)  => out.update(i, f)
                  case Right(r) => ready += ((r, i))
                }
            }
          }
      }
    }

    // 3. completion records
    val now = nowSeconds()
    val toEmbed = ready.filter { case (r, i) =>
      try
        kv.get(completionTable, completionKey(r.task.taskId, r.textHash)) match {
          case Some(item) if item.long(ExpiresAtAttr).forall(_ > now) =>
            out.update(i, Replayed(r.task, r.textHash)); false
          case _ => true
        }
      catch {
        case NonFatal(e) =>
          out.update(i, failure(e, "completion-read-failed", "completion-read-refused")(r.task))
          false
      }
    }

    // 4. texts
    val withText = toEmbed.flatMap { case (r, i) =>
      val text =
        try
          textStore.fetch(r.uri, r.textHash) match {
            case Right(s)                            => Right(s)
            case Left(m) if m.startsWith("text at ") =>
              Left(PermanentFailure(r.task, "text-hash-mismatch", "textHash"))
            case Left(_) => Left(PermanentFailure(r.task, "text-missing", "textS3Uri"))
          }
        catch {
          case NonFatal(e) => Left(failure(e, "text-read-failed", "text-read-refused")(r.task))
        }
      text match {
        case Left(f)  => out.update(i, f); None
        case Right(s) => Some((r, i, s))
      }
    }

    // 5. embeddings
    val embedded = mutable.ArrayBuffer.empty[(Ready, Int, Array[Float])]
    withText.grouped(embedder.maxBatchSize).foreach { batch =>
      counters.embedCalls.incrementAndGet()
      val result =
        try embedder.embed(batch.map(_._3).toIndexedSeq)
        catch {
          case NonFatal(e) => Left(EmbeddingFailure(e.getClass.getSimpleName, 0))
        }
      result match {
        case Left(_) =>
          batch.foreach { case (r, i, _) =>
            out.update(i, TransientFailure(r.task, "embed-failed", "embedding provider"))
          }
        case Right(b)
            if b.vectors.size != batch.size || b.vectors.exists(v =>
              v.length != embedder.dimensions || v.exists(f => f.isNaN || f.isInfinite)
            ) =>
          batch.foreach { case (r, i, _) =>
            out.update(i, PermanentFailure(r.task, "embed-invalid", "vector shape"))
          }
        case Right(b) =>
          batch.zip(b.vectors).foreach { case ((r, i, _), v) => embedded += ((r, i, v)) }
      }
    }

    // 6. vector writes (exact stored metadata), 7. completion records
    embedded.groupBy(_._1.task.index).toVector.sortBy(_._1).foreach { case (index, group) =>
      // one record per key: two deliveries of one task in one call write the same vector
      val records = group
        .map { case (r, _, v) => VectorRecord(r.task.vectorKey, v, r.stored) }
        .groupBy(_.key)
        .values
        .map(_.head)
        .toVector
        .sortBy(_.key)
      val failedKeys = mutable.HashMap.empty[String, StoreException]
      PutVectorsBatcher.batches(records, S3VectorsLimits.MaxPutVectorsBatch).foreach { batch =>
        counters.putCalls.incrementAndGet()
        writer
          .put(index, batch)
          .failure
          .foreach(e => batch.foreach(v => failedKeys.update(v.key, e)))
      }
      group.foreach { case (r, i, _) =>
        failedKeys.get(r.task.vectorKey) match {
          case Some(e) =>
            out.update(
              i,
              if (e.transient) TransientFailure(r.task, "vector-write-failed", "PutVectors")
              else PermanentFailure(r.task, "vector-write-refused", "PutVectors")
            )
          case None => out.update(i, Processed(r.task, r.textHash, recordCompletion(r, now)))
        }
      }
    }

    val result = indexed.map { case (_, i) => out(i) }
    result.foreach {
      case p: Processed =>
        counters.processed.incrementAndGet()
        if (!p.completionRecorded) counters.completionMissing.incrementAndGet()
      case _: Replayed         => counters.replayed.incrementAndGet()
      case _: PermanentFailure => counters.permanentFailures.incrementAndGet()
      case _: TransientFailure => counters.transientFailures.incrementAndGet()
    }
    result
  }

  private def validate(t: ReembedTask): Option[ReembedOutcome] =
    if (t.taskId == null || !t.taskId.matches(TaskIdPattern))
      Some(PermanentFailure(t, "invalid-task", "taskId"))
    else if (t.index == null || spec.forName(t.index).isEmpty)
      Some(PermanentFailure(t, "unknown-index", "index"))
    else if (t.vectorKey == null || !VectorKey.isValid(t.vectorKey))
      Some(PermanentFailure(t, "invalid-task", "vectorKey"))
    else None

  /** The metadata must hold every field later reads depend on and name the embedder's model. */
  private def checkMetadata(t: ReembedTask, m: StoredMetadata): Either[ReembedOutcome, Ready] = {
    val entry = spec.forName(t.index).get
    val required =
      RequiredKeys ++ (if (entry.documentType == SemanticDocumentType.ResearcherProfile)
                         Set("researcherId")
                       else Set.empty[String])
    val missing = required -- m.all.keySet
    if (missing.nonEmpty)
      Left(PermanentFailure(t, "metadata-missing", missing.toList.sorted.mkString(",")))
    else
      VectorMetadataCodec.decode(t.vectorKey, t.index, m) match {
        case Left(_) => Left(PermanentFailure(t, "metadata-invalid", ""))
        case Right(meta) if meta.documentType != entry.documentType =>
          Left(PermanentFailure(t, "metadata-invalid", "documentType"))
        case Right(meta)
            if meta.embeddingModel != embedder.modelId ||
              meta.embeddingDimensions != embedder.dimensions =>
          Left(PermanentFailure(t, "model-mismatch", "embeddingModel,embeddingDimensions"))
        case Right(meta) => Right(Ready(t, m, meta.textHash, meta.textS3Uri))
      }
  }

  /** Create-only; an existing record (a concurrent delivery won) also counts as recorded. */
  private def recordCompletion(r: Ready, now: Long): Boolean = {
    val k = completionKey(r.task.taskId, r.textHash)
    val item = Item(
      Item.Pk -> AttrValue.S(k.pk),
      Item.Sk -> AttrValue.S(k.sk),
      "taskId" -> AttrValue.S(r.task.taskId),
      "textHash" -> AttrValue.S(r.textHash),
      "indexName" -> AttrValue.S(r.task.index),
      "vectorKey" -> AttrValue.S(r.task.vectorKey),
      "embeddingModel" -> AttrValue.S(embedder.modelId),
      "completedAt" -> AttrValue.n(now),
      ExpiresAtAttr -> AttrValue.n(now + completionTtlSeconds)
    )
    try {
      kv.putIfAbsent(completionTable, item) match {
        case ConditionalResult.Written         => counters.completionWrites.incrementAndGet()
        case ConditionalResult.ConditionFailed => ()
      }
      true
    } catch { case NonFatal(_) => false }
  }
}

object VectorReembedder {

  /** At most this many tasks per [[VectorReembedder.reembed]] call (one GetVectors call per index
    * holds at most 100 keys).
    */
  val MaxTasksPerCall: Int = S3VectorsLimits.MaxGetVectorsBatch

  /** Completion records expire after 7 days (DynamoDB TTL on `expiresAt`, QS3); a task is never
    * redelivered that late (SQS retention and the experiments are shorter).
    */
  val CompletionTtlSeconds: Long = 7L * 24L * 3600L

  val ExpiresAtAttr = "expiresAt"

  /** Task IDs: 1 to 128 characters, letters, digits and `._:-`, starting with a letter or digit. */
  val TaskIdPattern = "[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"

  /** Metadata keys every later read needs ([[VectorMetadataCodec.decode]]'s required keys). */
  val RequiredKeys: Set[String] = Set(
    "documentType",
    "sourceType",
    "documentId",
    "runId",
    "textHash",
    "sourceRecordIds",
    "datasetId",
    "snapshotId",
    "jobName",
    "policyVersion",
    "sourceDataset",
    "textS3Uri",
    "embeddingModel",
    "embeddingDimensions",
    "schemaVersion"
  )

  def completionKey(taskId: String, textHash: String): ItemKey =
    ItemKey(TableSpecs.reembedPk(taskId, textHash), TableSpecs.ReembedSk)
}
