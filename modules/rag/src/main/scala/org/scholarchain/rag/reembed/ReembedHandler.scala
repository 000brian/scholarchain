package org.scholarchain.rag.reembed

import java.util.concurrent.atomic.AtomicLong

import scala.util.control.NonFatal

import org.scholarchain.core.VectorKey
import org.scholarchain.core.config.{EmbeddingProvider, ScholarChainConfig}
import org.scholarchain.rag.embedding.{EmbeddingProviders, EmbeddingUsage, InputType}
import org.scholarchain.storage.ingest.{ReembedOutcome, ReembedTask, VectorReembedder}
import org.scholarchain.storage.ports.EmbeddingPort

/** The `embedding-task-v1` message (Phase 15, decision 7.2): a JSON object with exactly the string
  * fields `schema` (= [[Schema]]), `taskId`, `index` and `vectorKey`, nothing else (no text, no
  * URL). The experiment tools build it; the worker passes each SQS body here unchanged.
  */
object EmbeddingTask {
  val Schema = "embedding-task-v1"
  val Fields: List[String] = List("schema", "taskId", "index", "vectorKey")

  /** A body longer than this many characters is poison (a valid message is well under 1 KiB). */
  val MaxBodyChars = 4096

  /** Poison codes: the body can never become a valid task, whatever the number of deliveries. */
  val InvalidJson = "invalid-json"
  val NotAnObject = "not-an-object"
  val BodyTooLarge = "body-too-large"
  val UnknownSchema = "unknown-schema"
  val MissingField = "missing-field"
  val UnknownField = "unknown-field"
  val FieldNotString = "field-not-string"
  val InvalidTaskId = "invalid-task-id"
  val UnknownIndex = "unknown-index"
  val InvalidVectorKey = "invalid-vector-key"
  val PoisonCodes: List[String] = List(
    InvalidJson,
    NotAnObject,
    BodyTooLarge,
    UnknownSchema,
    MissingField,
    UnknownField,
    FieldNotString,
    InvalidTaskId,
    UnknownIndex,
    InvalidVectorKey
  )

  private val TaskIdRegex = VectorReembedder.TaskIdPattern.r

  /** The task ID of `body`, when it is a JSON object whose `taskId` is a well-formed task ID (used
    * only to name a poison delivery in its log line; never echoes anything else of the body).
    */
  def taskIdOf(body: String): Option[String] =
    if (body == null || body.length > MaxBodyChars) None
    else
      try
        ujson
          .read(body)
          .objOpt
          .flatMap(_.get("taskId"))
          .flatMap(_.strOpt)
          .filter(TaskIdRegex.matches)
      catch { case NonFatal(_) => None }

  /** The task, or the poison code. `indexes` are the configured index names. */
  def parse(body: String, indexes: Set[String]): Either[String, ReembedTask] =
    if (body == null) Left(InvalidJson)
    else if (body.length > MaxBodyChars) Left(BodyTooLarge)
    else {
      val json =
        try Right(ujson.read(body))
        catch { case NonFatal(_) => Left(InvalidJson) }
      json.flatMap { j =>
        j.objOpt match {
          case None    => Left(NotAnObject)
          case Some(o) =>
            val keys = o.keys.toSet
            def str(k: String): Either[String, String] =
              o.get(k).flatMap(_.strOpt).toRight(FieldNotString)
            // the schema first: another version is `unknown-schema` whatever its fields are
            for {
              _ <- Either.cond(keys.contains("schema"), (), MissingField)
              schema <- str("schema")
              _ <- Either.cond(schema == Schema, (), UnknownSchema)
              _ <- Either.cond(Fields.forall(keys.contains), (), MissingField)
              _ <- Either.cond(keys.subsetOf(Fields.toSet), (), UnknownField)
              taskId <- str("taskId")
              _ <- Either.cond(TaskIdRegex.matches(taskId), (), InvalidTaskId)
              index <- str("index")
              _ <- Either.cond(indexes.contains(index), (), UnknownIndex)
              key <- str("vectorKey")
              _ <- Either.cond(VectorKey.isValid(key), (), InvalidVectorKey)
            } yield ReembedTask(taskId, index, key)
        }
      }
    }

  /** The message body for a task (what the experiment tools send). */
  def render(taskId: String, index: String, vectorKey: String): String =
    ujson
      .Obj("schema" -> Schema, "taskId" -> taskId, "index" -> index, "vectorKey" -> vectorKey)
      .render()
}

/** What the worker does with one delivery after [[ReembedHandler.handle]]. */
sealed abstract class DeliveryAction(val wire: String)
object DeliveryAction {

  /** Processed or replayed: delete the message. */
  case object Acknowledge extends DeliveryAction("acknowledge")

  /** Permanent failure (poison included): never delete; set its visibility to 0 at once, so SQS
    * counts the receives quickly and moves it to the dead-letter queue after `maxReceiveCount`.
    */
  case object ReleaseNow extends DeliveryAction("release")

  /** Transient failure: do nothing; the message reappears after the queue's visibility timeout. */
  case object LeaveForRetry extends DeliveryAction("leave")
}

/** One SQS delivery as the worker received it. `deliveryId` is the worker's own handle for the
  * message (for example its SQS message ID); it is returned unchanged and never logged.
  */
final case class Delivery(deliveryId: String, body: String)

/** The result of one delivery.
  *
  * @param outcome
  *   one of [[ReembedHandler.Outcomes]]: `processed`, `replayed`, `permanent-failure`,
  *   `transient-failure`
  * @param code
  *   `processed`, `replayed`, a storage failure code ([[ReembedOutcome.PermanentCodes]],
  *   [[ReembedOutcome.TransientCodes]]), a poison code ([[EmbeddingTask.PoisonCodes]]) or
  *   [[ReembedHandler.HandlerError]]
  * @param logLine
  *   the one structured log line of this delivery (already written through the handler's `log`)
  */
final case class HandledDelivery(
    deliveryId: String,
    taskId: Option[String],
    index: Option[String],
    outcome: String,
    code: String,
    action: DeliveryAction,
    latencyMillis: Long,
    logLine: String
)

/** Counters of one handler, exact under concurrent callers (atomic). `received` counts deliveries
  * (bodies), including poison that never becomes a task; `permanentFailures` includes `poison`.
  */
final class ReembedHandlerCounters {
  val received = new AtomicLong()
  val processed = new AtomicLong()
  val replayed = new AtomicLong()
  val permanentFailures = new AtomicLong()
  val poison = new AtomicLong()
  val transientFailures = new AtomicLong()

  def toJson: ujson.Obj = ujson.Obj(
    "received" -> received.get.toDouble,
    "processed" -> processed.get.toDouble,
    "replayed" -> replayed.get.toDouble,
    "permanentFailures" -> permanentFailures.get.toDouble,
    "poison" -> poison.get.toDouble,
    "transientFailures" -> transientFailures.get.toDouble
  )
}

/** The re-embed handler (Phase 15, decision 7.2): the worker calls [[handle]] with the deliveries
  * of one receive from the embedding queue. Each body is parsed as `embedding-task-v1`; valid tasks
  * are re-embedded through storage's [[VectorReembedder]] (the document's text read from its
  * recorded `textS3Uri`, embedded with the configured model, written under its existing key with
  * its existing metadata), in calls of at most `batchLimit` tasks (the embedding batch size, at
  * most [[VectorReembedder.MaxTasksPerCall]]).
  *
  * Rules:
  *   - poison (a body that is not valid JSON, not an object, too large, with an unknown `schema`, a
  *     missing, extra or non-string field, a malformed `taskId` or `vectorKey`, or an unknown
  *     index) is a permanent failure decided before any store or embedding call: no Voyage call, no
  *     write, [[DeliveryAction.ReleaseNow]];
  *   - a key that does not exist is found by storage's metadata read (`unknown-vector-key`): no
  *     Voyage call, no write, [[DeliveryAction.ReleaseNow]]; so is every other storage permanent
  *     failure;
  *   - processed and replayed: [[DeliveryAction.Acknowledge]];
  *   - a transient failure (Voyage or store throttling, 5xx, timeout) and an unexpected exception
  *     ([[ReembedHandler.HandlerError]]): [[DeliveryAction.LeaveForRetry]] (the queue's visibility
  *     timeout is the backoff).
  *
  * Logging: exactly one line per delivery, a JSON object ([[ReembedHandler.logLine]]): `event`,
  * `taskId` (null when the body has no well-formed task ID), `index` (null unless a configured
  * index), `outcome`, `code`, `action`, `latencyMillis`. Never the body, a text, a vector key, a
  * URL or a store message.
  *
  * Thread-safe when the reembed function is (storage's [[VectorReembedder]] is): no shared mutable
  * state except the atomic counters.
  */
final class ReembedHandler(
    reembed: Seq[ReembedTask] => Vector[ReembedOutcome],
    indexes: Set[String],
    val batchLimit: Int,
    log: String => Unit,
    clock: () => Long = () => System.nanoTime()
) {
  import ReembedHandler._

  require(
    batchLimit >= 1 && batchLimit <= VectorReembedder.MaxTasksPerCall,
    s"batchLimit must be in 1..${VectorReembedder.MaxTasksPerCall}"
  )
  require(indexes.nonEmpty, "at least one index is required")

  val counters = new ReembedHandlerCounters

  /** Handles the deliveries of one receive; results in input order. Never throws for a delivery. */
  def handle(deliveries: Seq[Delivery]): Vector[HandledDelivery] = {
    val t0 = clock()
    counters.received.addAndGet(deliveries.size.toLong)
    val parsed = deliveries.toVector.map(d => d -> EmbeddingTask.parse(d.body, indexes))
    val tasks = parsed.zipWithIndex.collect { case ((_, Right(t)), i) => (t, i) }
    val outcomes = scala.collection.mutable.HashMap.empty[Int, Either[String, ReembedOutcome]]
    tasks.grouped(batchLimit).foreach { chunk =>
      val result =
        try {
          val r = reembed(chunk.map(_._1))
          if (r.size == chunk.size) Right(r) else Left(HandlerError)
        } catch { case NonFatal(_) => Left(HandlerError) }
      result match {
        case Right(r) => chunk.zip(r).foreach { case ((_, i), o) => outcomes(i) = Right(o) }
        case Left(e)  => chunk.foreach { case (_, i) => outcomes(i) = Left(e) }
      }
    }
    val elapsed = math.max(0L, (clock() - t0) / 1000000L)
    parsed.zipWithIndex.map { case ((d, p), i) =>
      val h = p match {
        case Left(poison) =>
          counters.poison.incrementAndGet()
          counters.permanentFailures.incrementAndGet()
          result(d, EmbeddingTask.taskIdOf(d.body), None, PermanentFailure, poison, elapsed)
        case Right(t) =>
          outcomes(i) match {
            case Left(err) =>
              counters.transientFailures.incrementAndGet()
              result(d, Some(t.taskId), Some(t.index), TransientFailure, err, elapsed)
            case Right(o: ReembedOutcome.Processed) =>
              counters.processed.incrementAndGet()
              result(d, Some(t.taskId), Some(t.index), Processed, o.code, elapsed)
            case Right(o: ReembedOutcome.Replayed) =>
              counters.replayed.incrementAndGet()
              result(d, Some(t.taskId), Some(t.index), Replayed, o.code, elapsed)
            case Right(o: ReembedOutcome.PermanentFailure) =>
              counters.permanentFailures.incrementAndGet()
              result(d, Some(t.taskId), Some(t.index), PermanentFailure, o.code, elapsed)
            case Right(o: ReembedOutcome.TransientFailure) =>
              counters.transientFailures.incrementAndGet()
              result(d, Some(t.taskId), Some(t.index), TransientFailure, o.code, elapsed)
          }
      }
      try log(h.logLine)
      catch { case NonFatal(_) => () }
      h
    }
  }

  private def result(
      d: Delivery,
      taskId: Option[String],
      index: Option[String],
      outcome: String,
      code: String,
      ms: Long
  ): HandledDelivery = {
    val action = actionOf(outcome)
    HandledDelivery(
      d.deliveryId,
      taskId,
      index,
      outcome,
      code,
      action,
      ms,
      logLine(taskId, index, outcome, code, action, ms)
    )
  }
}

object ReembedHandler {

  /** Outcome words (log field `outcome`; experiment F filters on `processed` and `replayed`). */
  val Processed = "processed"
  val Replayed = "replayed"
  val PermanentFailure = "permanent-failure"
  val TransientFailure = "transient-failure"
  val Outcomes: List[String] = List(Processed, Replayed, PermanentFailure, TransientFailure)

  /** Code of a chunk whose reembed call threw or returned the wrong number of outcomes. */
  val HandlerError = "handler-error"

  /** Value of the log line's `event` field. */
  val LogEvent = "reembed-delivery"

  def actionOf(outcome: String): DeliveryAction = outcome match {
    case Processed | Replayed => DeliveryAction.Acknowledge
    case PermanentFailure     => DeliveryAction.ReleaseNow
    case _                    => DeliveryAction.LeaveForRetry
  }

  /** The structured log line: one compact JSON object, fixed keys, fixed vocabularies. */
  def logLine(
      taskId: Option[String],
      index: Option[String],
      outcome: String,
      code: String,
      action: DeliveryAction,
      latencyMillis: Long
  ): String =
    ujson
      .Obj(
        "event" -> LogEvent,
        "taskId" -> taskId.map(ujson.Str(_)).getOrElse(ujson.Null),
        "index" -> index.map(ujson.Str(_)).getOrElse(ujson.Null),
        "outcome" -> outcome,
        "code" -> code,
        "action" -> action.wire,
        "latencyMillis" -> latencyMillis.toDouble
      )
      .render()

  /** A handler and the usage counters of its document embedder. */
  final case class Built(handler: ReembedHandler, usage: EmbeddingUsage, embedder: EmbeddingPort)

  /** Builds the handler from configuration: the configured document embedder (input type
    * `document`, the model the index was written with) and storage's re-embed path from
    * `reembedderOf` (for the worker: `AwsStorageEnvironment.reembedder`). Refuses the `fake`
    * provider unless `allowFake` (tests): a fake embedding must never fill a stored index. The
    * batch limit is `embedding.batchSize`, at most [[VectorReembedder.MaxTasksPerCall]].
    */
  def fromConfig(
      config: ScholarChainConfig,
      reembedderOf: EmbeddingPort => VectorReembedder,
      wiring: EmbeddingProviders.Wiring = EmbeddingProviders.Wiring(),
      log: String => Unit = (s: String) => println(s),
      allowFake: Boolean = false
  ): Either[String, Built] =
    for {
      settings <- config.embedding.toRight("embedding section is not configured")
      created <- EmbeddingProviders.create(config, InputType.Document, wiring)
      _ <- Either.cond(
        allowFake || created.spec.provider != EmbeddingProvider.Fake,
        (),
        "the fake embedder is never used for a stored index"
      )
      indexes = config.vectors.toList.flatMap(_.indexNames).toSet
      _ <- Either.cond(indexes.nonEmpty, (), "vectors section is not configured")
    } yield {
      val r = reembedderOf(created.port)
      val limit = math.max(1, math.min(settings.batchSize, VectorReembedder.MaxTasksPerCall))
      Built(new ReembedHandler(r.reembed, indexes, limit, log), created.usage, created.port)
    }
}
