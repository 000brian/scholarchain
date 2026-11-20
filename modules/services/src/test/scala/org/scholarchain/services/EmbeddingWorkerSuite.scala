package org.scholarchain.services

import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}

import scala.collection.mutable.ArrayBuffer

import org.scholarchain.rag.reembed.{EmbeddingTask, ReembedHandler}
import org.scholarchain.storage.StoreException
import org.scholarchain.storage.ingest.{ReembedOutcome, ReembedTask}
import org.scholarchain.storage.jobs.JobState
import org.scholarchain.storage.ports.{ReceiveQueue, ReceivedWork, WorkItem}

/** The worker's second loop on the embedding queue (Phase 15 decision 8.1, tests 8.3): actions per
  * outcome, poison released and never acknowledged, a replay acknowledged once, both loops running,
  * shutdown draining an in-flight call, one log line per message written by rag's handler.
  */
class EmbeddingWorkerSuite extends munit.FunSuite {
  import EmbeddingWorkerSuite._
  import ServiceTestKit._

  override val munitTimeout = scala.concurrent.duration.Duration(180, "s")

  private def work(q: StubQueue, reembed: Seq[ReembedTask] => Vector[ReembedOutcome]) =
    EmbeddingWork(q, log => Right(new ReembedHandler(reembed, Set(Index), 10, log)))

  private def deliveryLines(h: Harness): List[ujson.Obj] =
    h.log.all.filter(_.value.get("event").exists(_.strOpt.contains(ReembedHandler.LogEvent)))

  /** Every line but `started` and the job loop's own events (none here: no job was submitted). */
  private def otherLines(h: Harness): List[String] =
    h.log.all
      .map(_("event").str)
      .filterNot(e => e == "started" || e == ReembedHandler.LogEvent)

  test("poison is never acknowledged and is released at once on every receive (no Voyage call)") {
    val q = new StubQueue
    val calls = new AtomicInteger()
    val h = harness(
      role = Role.Worker,
      embedding = Some(work(q, ts => { calls.incrementAndGet(); ts.map(processed).toVector }))
    )
    try {
      val w = h.server.embeddingWorker.getOrElse(fail("no embedding worker"))
      q.put("not json at all: secret-looking-body")
      q.put(EmbeddingTask.render("task-poison-2", "unknown-index-v1", "rp:key"))
      (1 to 5).foreach { round =>
        val out = w.processOne()
        assertEquals(out, EmbeddingOutcome(2, 0, 2, 0, 0), s"round $round")
      }
      assertEquals(calls.get, 0)
      assertEquals(q.acked.toList, Nil)
      assertEquals(q.releasedCount, 10)
      assertEquals(q.receiveCounts, List(5, 5)) // SQS would count 5 receives and redrive
      val lines = deliveryLines(h)
      assertEquals(lines.size, 10) // exactly one line per delivery
      assert(lines.forall(l => l("outcome").str == "permanent-failure"), lines.toString)
      assert(lines.forall(l => l("action").str == "release"))
      assert(lines.forall(l => l.value.contains("time") && l("role").str == "worker"))
      assert(!h.log.rendered.exists(_.contains("secret-looking-body")))
      assertEquals(otherLines(h), Nil) // services wrote no line of its own per message
      assertEquals((w.acknowledged.get, w.released.get, w.leftForRetry.get), (0L, 10L, 0L))
    } finally h.close()
  }

  test("a replay is acknowledged exactly once and the queue is then idle") {
    val q = new StubQueue
    val h = harness(role = Role.Worker, embedding = Some(work(q, _.map(replayed).toVector)))
    try {
      val w = h.server.embeddingWorker.get
      val id = q.put(EmbeddingTask.render("task-replay-1", Index, Key))
      assertEquals(w.processOne(), EmbeddingOutcome(1, 1, 0, 0, 0))
      assertEquals(w.processOne(), EmbeddingOutcome.Idle)
      assertEquals(q.acked.toList, List(id))
      assertEquals(q.releasedCount, 0)
      val lines = deliveryLines(h)
      assertEquals(
        lines.map(l => (l("taskId").str, l("outcome").str)),
        List(("task-replay-1", "replayed"))
      )
      assertEquals(otherLines(h), Nil)
    } finally h.close()
  }

  test(
    "a transient failure is neither acknowledged nor released (left to the visibility timeout)"
  ) {
    val q = new StubQueue
    val h = harness(
      role = Role.Worker,
      embedding = Some(work(q, _.map(t => transient(t)).toVector))
    )
    try {
      val w = h.server.embeddingWorker.get
      q.put(EmbeddingTask.render("task-transient-1", Index, Key))
      assertEquals(w.processOne(), EmbeddingOutcome(1, 0, 0, 1, 0))
      assertEquals((q.acked.toList, q.releasedCount), (Nil, 0))
      assertEquals(w.processOne(), EmbeddingOutcome.Idle) // still invisible
      assertEquals(deliveryLines(h).map(_("action").str), List("leave"))
    } finally h.close()
  }

  test("one receive with mixed outcomes: each message gets its own action") {
    val q = new StubQueue
    val h = harness(
      role = Role.All,
      embedding = Some(
        work(
          q,
          _.map(t =>
            if (t.taskId.endsWith("ok")) processed(t)
            else if (t.taskId.endsWith("again")) replayed(t)
            else transient(t)
          ).toVector
        )
      )
    )
    try {
      val w = h.server.embeddingWorker.get
      val ok = q.put(EmbeddingTask.render("task-ok", Index, Key))
      val poison = q.put("{\"schema\":\"embedding-task-v9\"}")
      val again = q.put(EmbeddingTask.render("task-again", Index, Key))
      q.put(EmbeddingTask.render("task-slow", Index, Key))
      assertEquals(w.processOne(), EmbeddingOutcome(4, 2, 1, 1, 0))
      assertEquals(q.acked.toList.sorted, List(ok, again).sorted)
      assertEquals(q.released.toList, List(poison))
      assertEquals(deliveryLines(h).size, 4)
    } finally h.close()
  }

  test("both loops run in the background: an async job succeeds and an embedding task is acked") {
    val q = new StubQueue
    val h = harness(startWorker = true, embedding = Some(work(q, _.map(processed).toVector)))
    try {
      assert(h.server.embeddingLoopRunning)
      val started = h.log.all.find(_("event").str == "started").get
      assert(started("worker").bool && started("embeddingWorker").bool)
      val id = q.put(EmbeddingTask.render("task-bg-1", Index, Key))
      val r = post(h, queryBody(mode = "async"))
      assertEquals(r.status, 202, r.body)
      val jobId = r.json("jobId").str
      assert(waitUntil()(q.acked.toList == List(id)))
      assert(
        waitUntil()(
          h.stores.jobs
            .get(jobId, h.clock.millis())
            .toOption
            .flatten
            .exists(_.state == JobState.Succeeded)
        )
      )
      assertEquals(deliveryLines(h).size, 1)
    } finally h.close()
    assert(!h.server.embeddingLoopRunning)
    assert(h.server.threads.forall(!_.isAlive))
  }

  test("no embedding queue, or a role without a worker: no second loop (job loop unchanged)") {
    val none = harness(startWorker = true)
    try {
      assertEquals(none.server.embeddingWorker, None)
      assert(!none.server.embeddingLoopRunning)
      assert(!none.log.all.find(_("event").str == "started").get("embeddingWorker").bool)
    } finally none.close()
    val q = new StubQueue
    val built = new AtomicInteger()
    val query = harness(
      role = Role.Query,
      startWorker = true,
      embedding = Some(
        EmbeddingWork(
          q,
          log => {
            built.incrementAndGet();
            Right(new ReembedHandler(_.map(processed).toVector, Set(Index), 10, log))
          }
        )
      )
    )
    try {
      assertEquals(query.server.embeddingWorker, None)
      assertEquals(built.get, 0)
      assertEquals(q.receiveCalls.get, 0)
    } finally query.close()
  }

  test("shutdown lets an in-flight call finish and acknowledge, then receives nothing more") {
    val q = new StubQueue
    val entered = new CountDownLatch(1)
    val proceed = new CountDownLatch(1)
    val h = harness(
      startWorker = true,
      embedding = Some(
        work(
          q,
          ts => {
            entered.countDown()
            proceed.await(60, TimeUnit.SECONDS)
            ts.map(processed).toVector
          }
        )
      )
    )
    val id = q.put(EmbeddingTask.render("task-drain-1", Index, Key))
    assert(entered.await(20, TimeUnit.SECONDS), "the handler was not called")
    val closer = new Thread(() => h.close())
    closer.start()
    closer.join(500)
    assert(closer.isAlive, "close returned while a message was in flight")
    assertEquals(q.acked.toList, Nil)
    proceed.countDown()
    closer.join(30000)
    assert(!closer.isAlive, "close did not finish after the in-flight call ended")
    assertEquals(q.acked.toList, List(id)) // acknowledged once, after the drain
    val receives = q.receiveCalls.get
    assert(h.server.threads.forall(!_.isAlive))
    Thread.sleep(300)
    assertEquals(q.receiveCalls.get, receives)
    assertEquals(deliveryLines(h).size, 1)
  }

  test("queue failures: a failing receive or ack is counted and logged without body or receipt") {
    val q = new StubQueue
    val h = harness(role = Role.Worker, embedding = Some(work(q, _.map(processed).toVector)))
    try {
      val w = h.server.embeddingWorker.get
      q.failReceive.set(true)
      assertEquals(w.processOne(), EmbeddingOutcome.ReceiveFailed)
      q.failReceive.set(false)
      q.failAck.set(true)
      q.put(EmbeddingTask.render("task-ackfail-1", Index, Key))
      assertEquals(w.processOne(), EmbeddingOutcome(1, 0, 0, 0, 1))
      val failures = h.log.all.filter(_("event").str == "embedding-queue-failure")
      assertEquals(failures.map(_("operation").str), List("receive", "ack"))
      assert(!h.log.rendered.exists(_.contains("receipt-")))
      assert(!h.log.rendered.exists(_.contains("example-queue-host")))
      assertEquals(w.queueFailures.get, 2L)
    } finally h.close()
  }

  test("start is refused when the handler cannot be built or the concurrency is out of range") {
    val (_, _, _, rt) = runtimeOver(config, r => new org.scholarchain.rag.runtime.FakeModel(r))
    try {
      val q = new StubQueue
      def start(e: EmbeddingWork, n: Int = ServiceLimits.EmbeddingWorkerConcurrency) =
        ServiceServer.start(
          rt,
          Composition
            .overRuntime(rt, 60000L, new org.scholarchain.storage.jobs.ManualClock())
            .fold(e => fail(e), identity),
          ServerOptions(
            role = Role.Worker,
            port = Some(0),
            env = env.get,
            logSink = new MemoryLogSink,
            startWorker = false,
            closeRuntime = false,
            embedding = Some(e),
            embeddingConcurrency = n
          )
        )
      val refused = start(EmbeddingWork(q, _ => Left("embedding section is not configured")))
      assertEquals(refused.left.toOption, Some("embedding section is not configured"))
      val ok = work(q, _.map(processed).toVector)
      assert(start(ok, 0).left.exists(_.contains("embeddingConcurrency must be in 1..8")))
      assert(start(ok, 9).isLeft)
      val s = start(ok, 1).fold(e => fail(e), identity)
      s.close()
    } finally rt.close()
  }
}

object EmbeddingWorkerSuite {
  val Index = "researcher-profile-v1"
  val Key = "rp:full-2015-2025:researcher:v1:" + "0" * 32

  def processed(t: ReembedTask): ReembedOutcome = ReembedOutcome.Processed(t, "sha256:x", true)
  def replayed(t: ReembedTask): ReembedOutcome = ReembedOutcome.Replayed(t, "sha256:x")
  def transient(t: ReembedTask): ReembedOutcome =
    ReembedOutcome.TransientFailure(t, "embed-failed", "IOException")

  /** An in-memory consuming queue with SQS semantics that the tests need: receive hides a message
    * and counts the receive; ack deletes; release makes it visible at once. Thread-safe.
    */
  final class StubQueue extends ReceiveQueue {
    private final case class Msg(id: String, body: String, var count: Int, var visible: Boolean)
    private val msgs = ArrayBuffer.empty[Msg]
    private val ids = new AtomicInteger()
    private val ackedBuf = ArrayBuffer.empty[String]
    private val releasedBuf = ArrayBuffer.empty[String]
    def acked: List[String] = synchronized(ackedBuf.toList)
    def released: List[String] = synchronized(releasedBuf.toList)
    val receiveCalls = new AtomicInteger()
    val failReceive = new AtomicBoolean(false)
    val failAck = new AtomicBoolean(false)

    def put(body: String): String = synchronized {
      val id = s"message-${ids.incrementAndGet()}"
      msgs += Msg(id, body, 0, visible = true); id
    }
    def releasedCount: Int = released.size
    def receiveCounts: List[Int] = synchronized(msgs.toList.map(_.count))

    def receive(max: Int): List[ReceivedWork] = synchronized {
      receiveCalls.incrementAndGet()
      if (failReceive.get)
        throw new StoreException("receive failed at https://example-queue-host/example", true)
      msgs.filter(_.visible).take(max).toList.map { m =>
        m.visible = false; m.count += 1
        ReceivedWork(WorkItem(m.id, m.body), s"receipt-${m.id}-${m.count}", m.count)
      }
    }
    def ack(w: ReceivedWork): Unit = synchronized {
      if (failAck.get)
        throw new StoreException("delete failed at https://example-queue-host/x", true)
      ackedBuf += w.item.id
      msgs.filterInPlace(_.id != w.item.id)
    }
    def release(w: ReceivedWork): Unit = synchronized {
      releasedBuf += w.item.id
      msgs.find(_.id == w.item.id).foreach(_.visible = true)
    }
  }
}
