package org.scholarchain.services

import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}

import scala.util.control.NonFatal

import org.scholarchain.rag.reembed.{Delivery, DeliveryAction, EmbeddingTask, ReembedHandler}
import org.scholarchain.storage.ports.{ReceiveQueue, ReceivedWork}

/** What the worker role runs on the embedding queue (Phase 15 decision 8.1): the consuming side of
  * the queue (storage's `ServiceStores.embeddingQueue`) and rag's re-embed handler, built by
  * `handlerOf` with the log function of the server (so rag's one line per delivery goes through the
  * server's log sink). `handlerOf` is called once when the server starts; a Left refuses the start.
  */
final case class EmbeddingWork(
    queue: ReceiveQueue,
    handlerOf: (String => Unit) => Either[String, ReembedHandler],
    indexes: Set[String] = Set.empty
)

/** What one [[EmbeddingWorker.processOne]] call did (tests assert on it). Counts are per call. */
final case class EmbeddingOutcome(
    received: Int,
    acknowledged: Int,
    released: Int,
    left: Int,
    queueFailures: Int
) {
  def idle: Boolean = received == 0
}

object EmbeddingOutcome {
  val Idle: EmbeddingOutcome = EmbeddingOutcome(0, 0, 0, 0, 0)

  /** The receive call itself failed (nothing was received). */
  val ReceiveFailed: EmbeddingOutcome = EmbeddingOutcome(0, 0, 0, 0, 1)
}

/** The embedding-queue consumer (decision 8.1). One call receives at most
  * [[ServiceLimits.EmbeddingReceiveMax]] messages (long polling: the queue's receive waits
  * `ServiceStores.SqsWaitSeconds`), hands all bodies to rag's handler in one call, then:
  *
  *   - [[DeliveryAction.Acknowledge]] (processed or replayed): `ack` (delete), once;
  *   - [[DeliveryAction.ReleaseNow]] (permanent failure, poison included): `release` (visibility 0)
  *     at once, never `ack`, so the queue redrives it to its dead-letter queue after
  *     `maxReceiveCount` receives;
  *   - [[DeliveryAction.LeaveForRetry]] (transient failure): nothing; the queue's visibility
  *     timeout brings it back.
  *
  * Logging: rag's handler writes exactly one line per delivery (event `reembed-delivery`); this
  * class writes none per message. It writes one `embedding-queue-failure` line for a call whose
  * queue receive, ack or release failed (fixed fields and counts only, never a body, a receipt or a
  * URL).
  *
  * Phase 16 H-04: a poison delivery no longer waits for the re-embed calls of the rest of its
  * batch. Before the batch is handed over, each body is checked with rag's own parser
  * (`EmbeddingTask.parse`) against `knownIndexes`; the deliveries it already refuses go to the
  * handler first, in one call that makes no store or embedding request, and are released at once;
  * then the rest go in one call as before (so the embedding batch is unchanged). With no known
  * indexes, only refusals decided before the index check count (`unknown-index` is left to the
  * handler). The handler decides every outcome in both calls, so this check changes only the order,
  * never an action: a delivery the check misses is still released after the second call.
  *
  * Thread-safe: no mutable state but atomic counters; the queue and the handler are thread-safe.
  *
  * @param knownIndexes
  *   the configured vector index names (`Composition.embeddingWork`: the same set rag's handler is
  *   built with); empty: see above
  */
final class EmbeddingWorker(
    val queue: ReceiveQueue,
    val handler: ReembedHandler,
    log: ServiceLog,
    knownIndexes: Set[String] = Set.empty
) {
  val acknowledged = new AtomicLong()
  val released = new AtomicLong()
  val leftForRetry = new AtomicLong()
  val queueFailures = new AtomicLong()

  private val receiveMax: Int =
    math.max(1, math.min(handler.batchLimit, ServiceLimits.EmbeddingReceiveMax))

  /** Receives and handles at most [[ServiceLimits.EmbeddingReceiveMax]] messages. Never throws for
    * a queue failure or a message.
    */
  def processOne(): EmbeddingOutcome = {
    val received =
      try queue.receive(receiveMax)
      catch {
        case NonFatal(e) =>
          queueFailures.incrementAndGet()
          failureLine("receive", 1, e)
          return EmbeddingOutcome.ReceiveFailed
      }
    if (received.isEmpty) EmbeddingOutcome.Idle
    else handleAll(received.toVector)
  }

  /** True when rag's parser already refuses `body` (poison), with the known indexes (H-04). */
  private def poisonBeforeHandling(body: String): Boolean =
    EmbeddingTask.parse(body, knownIndexes) match {
      case Left(code) => knownIndexes.nonEmpty || code != EmbeddingTask.UnknownIndex
      case Right(_)   => false
    }

  private def handleAll(work: Vector[ReceivedWork]): EmbeddingOutcome = {
    // the delivery ID is the position in this batch: results come back in input order, and the
    // position never repeats (an SQS message ID could, for a duplicate delivery in one batch)
    val deliveries = work.zipWithIndex.map { case (w, i) =>
      Delivery(i.toString, Option(w.item.payload).getOrElse(""))
    }
    // H-04: refused bodies first (no store or embedding call), acted on before the rest
    val (poison, rest) = deliveries.partition(d => poisonBeforeHandling(d.body))
    val first = if (poison.isEmpty) None else Some(act(work, handler.handle(poison)))
    val second = if (rest.isEmpty) None else Some(act(work, handler.handle(rest)))
    val parts = first.toList ++ second.toList
    EmbeddingOutcome(
      work.size,
      parts.map(_.acknowledged).sum,
      parts.map(_.released).sum,
      parts.map(_.left).sum,
      parts.map(_.queueFailures).sum
    )
  }

  /** Acts on the results of one handler call (counts `received` as the results' size). */
  private def act(
      work: Vector[ReceivedWork],
      results: Vector[org.scholarchain.rag.reembed.HandledDelivery]
  ): EmbeddingOutcome = {
    var acks, releases, lefts, failures = 0
    var lastFailure: Option[(String, Throwable)] = None
    results.foreach { r =>
      val w = work(r.deliveryId.toInt)
      r.action match {
        case DeliveryAction.Acknowledge =>
          try { queue.ack(w); acks += 1 }
          catch { case NonFatal(e) => failures += 1; lastFailure = Some("ack" -> e) }
        case DeliveryAction.ReleaseNow =>
          try { queue.release(w); releases += 1 }
          catch { case NonFatal(e) => failures += 1; lastFailure = Some("release" -> e) }
        case DeliveryAction.LeaveForRetry =>
          lefts += 1
      }
    }
    acknowledged.addAndGet(acks.toLong)
    released.addAndGet(releases.toLong)
    leftForRetry.addAndGet(lefts.toLong)
    queueFailures.addAndGet(failures.toLong)
    lastFailure.foreach { case (op, e) => failureLine(op, failures, e) }
    EmbeddingOutcome(results.size, acks, releases, lefts, failures)
  }

  private def failureLine(op: String, count: Int, e: Throwable): Unit =
    log.event(
      "embedding-queue-failure",
      None,
      List(
        "operation" -> ujson.Str(op),
        "count" -> ujson.Num(count),
        "cause" -> ujson.Str(LogCause.UnexpectedException),
        "exception" -> ujson.Str(LogCause.className(e))
      )
    )
}

/** Runs [[EmbeddingWorker.processOne]] on `threads` threads of `pool` until [[stop]]. A thread that
  * received nothing (or whose receive failed) waits [[ServiceLimits.WorkerIdleMillis]] before the
  * next receive. [[stop]] lets every thread finish the call it is in (handle, then ack or release)
  * and start no new one: the server's close waits for that at most
  * [[ServiceLimits.EmbeddingDrainMillis]].
  */
final class EmbeddingLoop(
    worker: EmbeddingWorker,
    pool: ThreadPoolExecutor,
    threads: Int,
    log: ServiceLog
) {
  private val running = new AtomicBoolean(false)
  private val lock = new Object

  def start(): Unit =
    if (running.compareAndSet(false, true))
      (1 to threads).foreach(_ => pool.execute(() => loop()))

  def stop(): Unit = {
    running.set(false)
    lock.synchronized(lock.notifyAll())
  }

  def isRunning: Boolean = running.get

  private def loop(): Unit =
    while (running.get && !Thread.currentThread().isInterrupted) {
      val out =
        try worker.processOne()
        catch {
          case NonFatal(e) =>
            log.failure(None, "embedding-worker", e); EmbeddingOutcome.ReceiveFailed
        }
      if (out.idle)
        try lock.synchronized(if (running.get) lock.wait(ServiceLimits.WorkerIdleMillis))
        catch { case _: InterruptedException => Thread.currentThread().interrupt() }
    }
}
