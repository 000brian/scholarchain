package org.scholarchain.storage.jobs

import org.scholarchain.storage.local.InMemoryWorkQueue
import org.scholarchain.storage.ports.WorkItem

class InMemoryWorkQueueSuite extends munit.FunSuite {

  private val vis = 1000L
  private def setup() = {
    val clock = new ManualClock()
    (clock, new InMemoryWorkQueue(vis, clock))
  }

  test("receive hides a message; it returns after the visibility timeout with receiveCount 2") {
    val (clock, q) = setup()
    assertEquals(q.enqueue(List(WorkItem("job-1", "{}"))), 1)
    val first = q.receive(10)
    assertEquals(first.map(w => (w.item.id, w.receiveCount)), List(("job-1", 1)))
    assertEquals(q.receive(10), Nil, "hidden while in flight")
    assertEquals((q.size, q.visibleCount, q.inFlightCount), (1, 0, 1))
    clock.advance(vis - 1)
    assertEquals(q.receive(10), Nil, "still hidden 1 ms before the timeout")
    clock.advance(1)
    val again = q.receive(10)
    assertEquals(again.map(w => (w.item.id, w.receiveCount)), List(("job-1", 2)))
    assertNotEquals(again.head.receipt, first.head.receipt, "a new receipt per delivery")
  }

  test("ack consumes the message: it never returns") {
    val (clock, q) = setup()
    q.enqueue(List(WorkItem("job-1", "{}")))
    q.ack(q.receive(1).head)
    clock.advance(10 * vis)
    assertEquals(q.receive(10), Nil)
    assertEquals((q.size, q.ackedCount), (0, 1L))
  }

  test("release returns the message at once and counts the next delivery") {
    val (_, q) = setup()
    q.enqueue(List(WorkItem("job-1", "{}")))
    val w = q.receive(1).head
    q.release(w)
    val back = q.receive(1)
    assertEquals(back.map(_.receiveCount), List(2), "visible at once, without moving the clock")
  }

  test("a stale receipt is ignored: ack after a redelivery does not consume the new delivery") {
    val (clock, q) = setup()
    q.enqueue(List(WorkItem("job-1", "{}")))
    val stale = q.receive(1).head
    clock.advance(vis)
    val fresh = q.receive(1).head
    q.ack(stale)
    q.release(stale)
    assertEquals((q.size, q.inFlightCount, q.ackedCount), (1, 1, 0L))
    q.ack(fresh)
    assertEquals(q.size, 0)
  }

  test("enqueue order, max per receive, and duplicates are separate messages (at-least-once)") {
    val (_, q) = setup()
    assertEquals(q.enqueue(List("a", "b", "a").map(WorkItem(_, "{}"))), 3)
    assertEquals(q.receive(2).map(_.item.id), List("a", "b"))
    assertEquals(q.receive(5).map(_.item.id), List("a"))
    assertEquals(q.receive(0), Nil)
  }

  test("visibilityMillis is bounded like SQS (1 ms .. 12 h)") {
    val c = new ManualClock()
    intercept[IllegalArgumentException](new InMemoryWorkQueue(0, c))
    intercept[IllegalArgumentException](
      new InMemoryWorkQueue(InMemoryWorkQueue.MaxVisibilityMillis + 1, c)
    )
  }
}
