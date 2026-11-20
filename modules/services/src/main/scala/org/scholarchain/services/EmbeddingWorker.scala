package org.scholarchain.services

import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}

import scala.util.control.NonFatal

import org.scholarchain.rag.reembed.{Delivery, DeliveryAction, ReembedHandler}
import org.scholarchain.storage.ports.{ReceiveQueue, ReceivedWork}

/** What the worker role runs on the embedding queue (Phase 15 decision 8.1): the consuming side of
  * the queue (storage's `ServiceStores.embeddingQueue`) and rag's re-embed handler, built by
  * `handlerOf` with the log function of the server (so rag's one line per delivery goes through the
  * server's log sink). `handlerOf` is called once when the server starts; a Left refuses the start.
  */
final case class EmbeddingWork(
    queue: ReceiveQueue,
    handlerOf: (String => Unit) => Either[String, ReembedHandler]
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
  * Thread-safe: no mutable state but atomic counters; the queue and the handler are thread-safe.
  */
final class EmbeddingWorker(
    val queue: ReceiveQueue,
    val handler: ReembedHandler,
    log: ServiceLog
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

  private def handleAll(work: Vector[ReceivedWork]): EmbeddingOutcome = {
    // the delivery ID is the position in this batch: results come back in input order, and the
    // position never repeats (an SQS message ID could, for a duplicate delivery in one batch)
    val deliveries = work.zipWithIndex.map { case (w, i) =>
      Delivery(i.toString, Option(w.item.payload).getOrElse(""))
    }
    val results = handler.handle(deliveries)
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
    EmbeddingOutcome(work.size, acks, releases, lefts, failures)
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
