package org.scholarchain.storage.ports

/** One unit of embedding work. `id` is deterministic (derived from what the item covers), so
  * enqueuing the same plan twice yields the same items; `payload` is a small JSON document that
  * references the input (it never carries document text).
  */
final case class WorkItem(id: String, payload: String)

/** A received item; `receipt` is what `ack` and `release` need. `receiveCount` counts deliveries.
  */
final case class ReceivedWork(item: WorkItem, receipt: String, receiveCount: Int)

/** The consuming side of a queue: receive, acknowledge, release. A worker that only consumes (the
  * services' re-embed worker on the embedding queue) holds this type and cannot send.
  *
  *   - `receive` returns at most `max` items and hides them until `ack` or `release`.
  *   - `ack` marks an item done. `release` makes it visible again at once.
  */
trait ReceiveQueue {
  def receive(max: Int): List[ReceivedWork]
  def ack(work: ReceivedWork): Unit
  def release(work: ReceivedWork): Unit
}

/** A bounded embedding work queue (manifest-driven locally, SQS in AWS).
  *
  *   - `enqueue` is idempotent for the manifest queue (same IDs) and de-duplicated by consumers
  *     through the ArtifactRegistry for SQS (at-least-once delivery).
  *   - `receive` returns at most `max` items and hides them until `ack` or `release`.
  *   - `ack` marks an item done. `release` makes it visible again (a failed attempt).
  *   - An item delivered more than the queue's receive limit is moved aside as poison and reported.
  */
trait WorkQueue extends ReceiveQueue {
  def enqueue(items: Seq[WorkItem]): Int
  def receive(max: Int): List[ReceivedWork]
  def ack(work: ReceivedWork): Unit
  def release(work: ReceivedWork): Unit
}
