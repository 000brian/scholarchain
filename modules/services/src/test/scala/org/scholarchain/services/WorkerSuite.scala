package org.scholarchain.services

import org.scholarchain.rag.runtime.FakeModel
import org.scholarchain.rag.runtime.RuntimeServiceFixtures.GatedModel
import org.scholarchain.storage.jobs.{JobChange, JobMessages, JobRecord, JobState}

/** The async worker (decision 7f). Tests drive `Worker.processOne` directly (no worker loop). */
class WorkerSuite extends munit.FunSuite {
  import ServiceTestKit._

  override val munitTimeout = scala.concurrent.duration.Duration(180, "s")

  private def submit(h: Harness): String = {
    val r = post(h, queryBody(mode = "async"))
    assertEquals(r.status, 202, r.body)
    r.json("jobId").str
  }
  private def job(h: Harness, id: String): JobRecord =
    h.stores.jobs.get(id, h.clock.millis()).toOption.flatten.get

  test("a duplicate delivery of a finished job is acked without running") {
    val h = harness()
    try {
      val id = submit(h)
      h.stores.enqueueJob(id) // a duplicate message
      assertEquals(h.server.worker.processOne(), WorkerOutcome.Succeeded)
      val calls = h.flaky.graphCalls.get
      assertEquals(h.server.worker.processOne(), WorkerOutcome.AckedWithoutRun)
      assertEquals(h.flaky.graphCalls.get, calls) // nothing ran
      assertEquals(h.queue.size, 0)
      assertEquals(h.server.worker.processOne(), WorkerOutcome.Idle)
    } finally h.close()
  }

  test("two workers racing on one job run it once") {
    val h = harness(model = r => new GatedModel(new FakeModel(r)))
    val gated = h.model.asInstanceOf[GatedModel]
    try {
      val id = submit(h)
      h.stores.enqueueJob(id) // the second worker gets a duplicate delivery
      val second = new Worker(appOf(h))
      val t = new Thread(() => { h.server.worker.processOne(); () })
      t.start()
      assert(gated.awaitEntered(30000L))
      // the job is running with a live lease: the second worker leaves its message alone
      assertEquals(second.processOne(), WorkerOutcome.LeftAlone("lease is live"))
      gated.release()
      t.join(60000L)
      assertEquals(job(h, id).state, JobState.Succeeded)
      assertEquals(job(h, id).attempts, 1)
      // the left message comes back after the visibility timeout and is acked without running
      h.clock.advance(h.settings.jobLeaseMillis + ServiceLimits.LocalVisibilityMarginMillis + 1)
      assertEquals(second.processOne(), WorkerOutcome.AckedWithoutRun)
      assertEquals(h.queue.size, 0)
      assertEquals(h.runtime.stores.audits.read(job(h, id).queryRunId, 10).toOption.get.size, 1)
    } finally { gated.release(); h.close() }
  }

  test("two claims of the same queued version: exactly one wins") {
    val h = harness()
    try {
      val id = submit(h)
      val j = job(h, id)
      val now = h.clock.millis()
      val a = h.stores.jobs.claim(j, now, now + 1000)
      val b = h.stores.jobs.claim(j, now, now + 1000)
      assert(a.exists(_.isInstanceOf[JobChange.Written]))
      assertEquals(b, Right(JobChange.ConditionFailed))
    } finally h.close()
  }

  test(
    "a worker that claims and stops: after the lease the redelivered message reclaims it (attempts 2)"
  ) {
    val h = harness()
    try {
      val id = submit(h)
      // a worker receives and claims the job, then its process dies (no ack, no result)
      val w = h.queue.receive(1).head
      assertEquals(JobMessages.jobId(w), Right(id))
      val now = h.clock.millis()
      h.stores.jobs.claim(job(h, id), now, now + h.settings.jobLeaseMillis)
      assertEquals(h.server.worker.processOne(), WorkerOutcome.Idle) // still hidden
      h.clock.advance(h.settings.jobLeaseMillis + ServiceLimits.LocalVisibilityMarginMillis + 1)
      assertEquals(h.server.worker.processOne(), WorkerOutcome.Succeeded)
      assertEquals(job(h, id).state, JobState.Succeeded)
      assertEquals(job(h, id).attempts, 2)
      assertEquals(get(h, s"/v1/jobs/$id/result").status, 200)
    } finally h.close()
  }

  test("attempts at the limit: ATTEMPTS_EXHAUSTED and ack instead of claiming") {
    val h = harness()
    try {
      val id = submit(h)
      h.flaky.failGraph.set(true)
      (1 until h.settings.jobMaxAttempts).foreach { _ =>
        assertEquals(h.server.worker.processOne(), WorkerOutcome.Released)
        assertEquals(job(h, id).state, JobState.Queued)
      }
      // the last allowed attempt fails too: failed, acked
      assertEquals(
        h.server.worker.processOne(),
        WorkerOutcome.Failed(JobErrorCodes.AttemptsExhausted)
      )
      assertEquals(job(h, id).attempts, h.settings.jobMaxAttempts)
      assertEquals(h.queue.size, 0)
      val res = get(h, s"/v1/jobs/$id/result")
      assertEquals(res.status, 409)
      assertEquals(res.error, "JOB_FAILED")
      assertEquals(res.json("details")("jobError").str, JobErrorCodes.AttemptsExhausted)
      val status = get(h, s"/v1/jobs/$id").json
      assertEquals(status("state").str, "FAILED")
      assertEquals(status("error")("code").str, JobErrorCodes.AttemptsExhausted)
    } finally h.close()
  }

  test("a message for a job already at the attempt limit is failed without claiming") {
    val h = harness()
    try {
      val id = submit(h)
      // simulate earlier lost attempts: claim/release up to the limit
      (1 to h.settings.jobMaxAttempts).foreach { _ =>
        val now = h.clock.millis()
        val c = h.stores.jobs
          .claim(job(h, id), now, now + 1000)
          .toOption
          .get
          .asInstanceOf[JobChange.Written]
        h.stores.jobs.release(c.job, now)
      }
      val calls = h.flaky.graphCalls.get
      assertEquals(
        h.server.worker.processOne(),
        WorkerOutcome.Failed(JobErrorCodes.AttemptsExhausted)
      )
      assertEquals(h.flaky.graphCalls.get, calls)
    } finally h.close()
  }

  test("a final write that loses the version race discards its result and leaves the message") {
    val h = harness(model = r => new GatedModel(new FakeModel(r)))
    val gated = h.model.asInstanceOf[GatedModel]
    try {
      val id = submit(h)
      val out = new java.util.concurrent.atomic.AtomicReference[WorkerOutcome]()
      val t = new Thread(() => out.set(h.server.worker.processOne()))
      t.start()
      assert(gated.awaitEntered(30000L))
      // the lease passes and another worker reclaims the job
      h.clock.advance(h.settings.jobLeaseMillis + 1)
      val now = h.clock.millis()
      val r = h.stores.jobs.reclaim(job(h, id), now, now + h.settings.jobLeaseMillis)
      assert(r.exists(_.isInstanceOf[JobChange.Written]), r.toString)
      gated.release()
      t.join(60000L)
      assertEquals(out.get, WorkerOutcome.LeftAlone("final write lost; result discarded"))
      val j = job(h, id)
      assertEquals(j.state, JobState.Running)
      assertEquals(j.result, None)
      assertEquals(j.attempts, 2)
      assertEquals(h.queue.inFlightCount, 1) // not acked
    } finally { gated.release(); h.close() }
  }

  test("an oversized result: failed RESULT_TOO_LARGE, then ack") {
    val h = harness()
    try {
      val id = submit(h)
      val worker = new Worker(appOf(h, new BigResultStores(h.stores)))
      assertEquals(worker.processOne(), WorkerOutcome.Failed(JobErrorCodes.ResultTooLarge))
      assertEquals(job(h, id).error.map(_.code), Some(JobErrorCodes.ResultTooLarge))
      assertEquals(h.queue.size, 0)
    } finally h.close()
  }

  test("a request rag rejects is failed WORKFLOW_FAILED and acked (not retried)") {
    val h = harness()
    try {
      val now = h.clock.millis()
      val qid = h.runtime
        .queryRunId(org.scholarchain.rag.runtime.RuntimeServiceFixtures.answeredRequest)
        .toOption
        .get
      val req = org.scholarchain.storage.jobs.JobRequest("q", Some("w1999-2000"), None, true)
      h.stores.jobs.create(
        org.scholarchain.storage.jobs.NewJob(
          "job-bad",
          org.scholarchain.storage.jobs.JobMode.Async,
          qid,
          req,
          "0" * 31 + "2",
          now,
          now / 1000 + 3600
        )
      )
      h.stores.enqueueJob("job-bad")
      assertEquals(h.server.worker.processOne(), WorkerOutcome.Failed(JobErrorCodes.WorkflowFailed))
      assertEquals(h.queue.size, 0)
    } finally h.close()
  }

  test("restart: a new server over the same ServiceStores returns a finished async result") {
    // the first server keeps the runtime open on close (the stores outlive it, as DynamoDB does)
    val first = harness(closeRuntime = false)
    val id = submit(first)
    assertEquals(first.server.worker.processOne(), WorkerOutcome.Succeeded)
    val before = get(first, s"/v1/jobs/$id/result")
    assertEquals(before.status, 200)
    first.server.close()
    assert(first.server.threads.forall(!_.isAlive))
    // a new process over the same stores
    val second = ServiceServer
      .start(
        first.runtime,
        first.stores,
        ServerOptions(
          role = Role.Query,
          port = Some(0),
          clock = first.clock,
          ids = new FixedIds,
          logSink = new MemoryLogSink,
          env = env.get,
          closeRuntime = false,
          name = "restart"
        )
      )
      .fold(e => fail(e), identity)
    try {
      val after = call(second.baseUri, "GET", s"/v1/jobs/$id/result", None)
      assertEquals(after.status, 200)
      assertEquals(after.body, before.body)
      val status = call(second.baseUri, "GET", s"/v1/jobs/$id", None)
      assertEquals(status.json("state").str, "SUCCEEDED")
    } finally { second.close(); first.runtime.close() }
  }

  test("the worker loop runs queued jobs in the background and stops on close") {
    val h = harness(startWorker = true)
    try {
      val id = submit(h)
      assert(waitUntil()(job(h, id).state == JobState.Succeeded))
    } finally h.close()
  }

  /** The application context of a harness (a second worker over the same stores). */
  private def appOf(
      h: Harness,
      stores: org.scholarchain.storage.jobs.ServiceStores = null
  ): AppContext =
    new AppContext(
      h.runtime,
      Option(stores).getOrElse(h.stores),
      h.settings,
      h.clock,
      new FixedIds,
      new ServiceLog(h.log, "worker", h.clock, new Redactor(Nil)),
      new Breakers(h.settings, h.clock)
    )
}
