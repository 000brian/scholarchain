package org.scholarchain.storage.local

import java.time.Clock

import scala.collection.mutable

import org.scholarchain.storage.ports.{ReceivedWork, WorkItem, WorkQueue}

/** An in-memory at-least-once queue with SQS-like visibility semantics, for the job queue of local
  * runs and tests. Time comes only from the injected `clock`, so tests are deterministic.
  *
  *   - `enqueue` adds one message per item (no de-duplication, like an SQS standard queue).
  *   - `receive` hands out visible messages in enqueue order, hides each for `visibilityMillis` and
  *     counts its deliveries (`ReceivedWork.receiveCount`). Each delivery gets a new receipt.
  *   - A message that is neither acked nor released becomes visible again once the clock reaches
  *     its delivery time plus `visibilityMillis`.
  *   - `ack` consumes the message; `release` makes it visible at once. Both act only with the
  *     receipt of the latest delivery; a stale receipt (the message was delivered again since) is
  *     ignored.
  *
  * There is no poison handling: callers bound attempts themselves (services: `jobMaxAttempts`).
  * Thread-safe (every operation is synchronized).
  */
final class InMemoryWorkQueue(visibilityMillis: Long, clock: Clock) extends WorkQueue {
  require(
    visibilityMillis >= 1 && visibilityMillis <= InMemoryWorkQueue.MaxVisibilityMillis,
    s"visibilityMillis must be in 1..${InMemoryWorkQueue.MaxVisibilityMillis}"
  )
  require(clock != null, "a clock is required")

  private final class Message(val seq: Long, val item: WorkItem) {
    var visibleAt: Long = Long.MinValue
    var receives: Int = 0
    var receipt: Option[String] = None
  }

  private val messages = mutable.LinkedHashMap.empty[Long, Message]
  private var nextSeq: Long = 0L
  private var acks: Long = 0L

  def enqueue(items: Seq[WorkItem]): Int = synchronized {
    items.foreach { i =>
      require(i != null, "null work item")
      nextSeq += 1
      messages.update(nextSeq, new Message(nextSeq, i))
    }
    items.size
  }

  def receive(max: Int): List[ReceivedWork] = synchronized {
    val now = clock.millis()
    messages.valuesIterator.filter(_.visibleAt <= now).take(math.max(0, max)).toList.map { m =>
      m.receives += 1
      m.visibleAt = now + visibilityMillis
      val r = s"${m.seq}:${m.receives}"
      m.receipt = Some(r)
      ReceivedWork(m.item, r, m.receives)
    }
  }

  private def current(work: ReceivedWork): Option[Message] =
    Option(work)
      .flatMap(w => w.receipt.split(':').headOption.flatMap(_.toLongOption))
      .flatMap(messages.get)
      .filter(_.receipt.contains(work.receipt))

  def ack(work: ReceivedWork): Unit = synchronized {
    current(work).foreach { m =>
      messages.remove(m.seq)
      acks += 1
    }
  }

  def release(work: ReceivedWork): Unit = synchronized {
    current(work).foreach(_.visibleAt = Long.MinValue)
  }

  /** Messages not yet acked (visible or hidden). */
  def size: Int = synchronized(messages.size)

  /** Messages a `receive` at the clock's current time would return (up to its `max`). */
  def visibleCount: Int = synchronized {
    val now = clock.millis()
    messages.valuesIterator.count(_.visibleAt <= now)
  }

  /** Messages received and hidden at the clock's current time. */
  def inFlightCount: Int = synchronized(size - visibleCount)

  /** Messages consumed by `ack` since creation. */
  def ackedCount: Long = synchronized(acks)
}

object InMemoryWorkQueue {

  /** The SQS maximum visibility timeout (12 hours), kept so local and AWS runs accept the same
    * values.
    */
  val MaxVisibilityMillis: Long = 12L * 60L * 60L * 1000L
}
