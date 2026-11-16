package org.scholarchain.services

import org.scholarchain.rag.runtime.FakeModel
import org.scholarchain.rag.runtime.RuntimeServiceFixtures.GatedModel

/** Sync deadlines, query permits, downstream failures and circuit breakers (decisions 7d, 7i). */
class CapacityAndFailureSuite extends munit.FunSuite {
  import ServiceTestKit._

  override val munitTimeout = scala.concurrent.duration.Duration(180, "s")

  test(
    "timeout: a workflow slower than the deadline gives 504; it keeps its permit until it ends"
  ) {
    val cfg = withService(config)(_.copy(syncDeadlineMillis = 300, maxConcurrentQueries = 1))
    val h = harness(cfg = cfg, model = r => new GatedModel(new FakeModel(r)))
    val gated = h.model.asInstanceOf[GatedModel]
    try {
      val slow = post(h, queryBody())
      assertEquals(slow.status, 504)
      assertEquals(slow.error, "WORKFLOW_TIMEOUT")
      assertEquals(slow.json("traceId").str, slow.header("X-Trace-Id").get)
      assertEquals(h.server.app.query.freePermits, 0)
      // the timed-out workflow still holds the only permit: the next request is refused at once
      val over = post(h, queryBody())
      assertEquals(over.status, 503)
      assertEquals(over.error, "OVERLOADED")
      assertEquals(over.json("retryable").bool, true)
      assert(over.header("Retry-After").nonEmpty)
      gated.release()
      assert(waitUntil()(h.server.app.query.freePermits == 1))
      assertEquals(post(h, queryBody()).status, 200)
    } finally { gated.release(); h.close() }
  }

  test("downstream failure: a store that throws gives 502; the circuit opens (503) and recovers") {
    val h = harness()
    try {
      val threshold = h.settings.circuitFailureThreshold
      h.flaky.failGraph.set(true)
      (1 to threshold).foreach { _ =>
        val r = post(h, queryBody())
        assertEquals(r.status, 502, r.body)
        assertEquals(r.error, "DOWNSTREAM_FAILURE")
        // downstream error text never reaches the client
        assert(!r.body.contains("injected"), r.body)
      }
      val open = post(h, queryBody())
      assertEquals(open.status, 503)
      assertEquals(open.error, "CIRCUIT_OPEN")
      assertEquals(h.server.breakers.workflow.state, "open")
      h.flaky.failGraph.set(false)
      // still open until circuitOpenMillis passes
      assertEquals(post(h, queryBody()).status, 503)
      h.clock.advance(h.settings.circuitOpenMillis.toLong)
      assertEquals(h.server.breakers.workflow.state, "half-open")
      val trial = post(h, queryBody())
      assertEquals(trial.status, 200, trial.body)
      assertEquals(h.server.breakers.workflow.state, "closed")
    } finally h.close()
  }

  test("a failed trial call opens the circuit again; 4xx answers never count as failures") {
    val h = harness()
    try {
      (1 to 10).foreach(_ => assertEquals(post(h, "{").status, 400))
      assertEquals(h.server.breakers.workflow.state, "closed")
      h.flaky.failGraph.set(true)
      (1 to h.settings.circuitFailureThreshold).foreach(_ => post(h, queryBody()))
      h.clock.advance(h.settings.circuitOpenMillis.toLong)
      assertEquals(post(h, queryBody()).status, 502) // the trial fails
      assertEquals(h.server.breakers.workflow.state, "open")
      assertEquals(post(h, queryBody()).error, "CIRCUIT_OPEN")
    } finally h.close()
  }

  test("graph and read circuits: store failures give 502, then 503, then recover") {
    val h = harness()
    try {
      val node = s"/v1/graph/nodes/${org.scholarchain.rag.runtime.RuntimeFixtures.rC.value}"
      assertEquals(get(h, node).status, 200)
      h.flaky.failGraph.set(true)
      (1 to h.settings.circuitFailureThreshold).foreach(_ =>
        assertEquals(get(h, node).error, "DOWNSTREAM_FAILURE")
      )
      assertEquals(get(h, node).error, "CIRCUIT_OPEN")
      h.flaky.failGraph.set(false)
      h.clock.advance(h.settings.circuitOpenMillis.toLong)
      assertEquals(get(h, node).status, 200)

      val explain = s"/v1/explain/run:v1:${"0" * 32}"
      assertEquals(get(h, explain).error, "QUERY_NOT_FOUND")
      h.flaky.failKeyValue.set(true)
      (1 to h.settings.circuitFailureThreshold).foreach(_ =>
        assertEquals(get(h, explain).status, 502)
      )
      assertEquals(get(h, explain).error, "CIRCUIT_OPEN")
      h.flaky.failKeyValue.set(false)
      h.clock.advance(h.settings.circuitOpenMillis.toLong)
      assertEquals(get(h, explain).error, "QUERY_NOT_FOUND")
    } finally h.close()
  }

  test("a job store failure gives 502 DOWNSTREAM_FAILURE (async and job reads)") {
    val h = harness()
    try {
      h.jobKv.failing.set(true)
      val r = post(h, queryBody(mode = "async"))
      assertEquals(r.status, 502)
      assertEquals(r.error, "DOWNSTREAM_FAILURE")
      assertEquals(get(h, "/v1/jobs/job-x").status, 502)
      val keyed = post(h, queryBody(), headers = List("Idempotency-Key" -> "k"))
      assertEquals(keyed.status, 502)
      h.jobKv.failing.set(false)
      assertEquals(get(h, "/v1/jobs/job-x").error, "JOB_NOT_FOUND")
    } finally h.close()
  }

  test("a result over the stored limit gives 500 RESULT_TOO_LARGE and records the job failed") {
    val h = harness(wrapStores = s => new BigResultStores(s))
    try {
      val k = List("Idempotency-Key" -> "k-big")
      val r = post(h, queryBody(), headers = k)
      assertEquals(r.status, 500, r.body)
      assertEquals(r.error, "RESULT_TOO_LARGE")
      assertEquals(r.json("retryable").bool, false)
      assertEquals(r.json("traceId").str, r.header("X-Trace-Id").get)
      val rec = h.stores.idempotency.get("full", "k-big", h.clock.millis()).toOption.flatten.get
      val job = h.stores.jobs.get(rec.jobId, h.clock.millis()).toOption.flatten.get
      assertEquals(job.error.map(_.code), Some(JobErrorCodes.ResultTooLarge))
      val replay = post(h, queryBody(), headers = k)
      assertEquals(replay.error, "JOB_FAILED")
      assertEquals(replay.json("details")("jobError").str, JobErrorCodes.ResultTooLarge)
    } finally h.close()
  }

  test("an unexpected internal failure gives 500 INTERNAL with the trace ID and no internal text") {
    val h = harness()
    try {
      // a succeeded job whose stored result cannot be decoded
      val now = h.clock.millis()
      val qid = h.runtime
        .queryRunId(org.scholarchain.rag.runtime.RuntimeServiceFixtures.answeredRequest)
        .toOption
        .get
      val req = org.scholarchain.storage.jobs.JobRequest("q", None, None, false)
      import org.scholarchain.storage.jobs._
      val created = h.stores.jobs.create(
        NewJob("job-broken", JobMode.Async, qid, req, "0" * 31 + "3", now, now / 1000 + 3600)
      )
      val queued = created.toOption.get.asInstanceOf[JobChange.Written].job
      val running = h.stores.jobs
        .claim(queued, now, now + 1000)
        .toOption
        .get
        .asInstanceOf[JobChange.Written]
        .job
      h.stores.jobs.succeed(running, now, "{not a completed result")
      val r = get(h, "/v1/jobs/job-broken/result")
      assertEquals(r.status, 500)
      assertEquals(r.error, "INTERNAL")
      assertEquals(r.json("traceId").str, r.header("X-Trace-Id").get)
      assert(!r.body.contains("not a completed result"))
    } finally h.close()
  }
}

/** Service stores whose `succeed` answers `ResultTooLarge` (storage's 300,000-byte limit), so the
  * services' handling is tested without a 300 KB answer.
  */
final class BigResultStores(base: org.scholarchain.storage.jobs.ServiceStores)
    extends org.scholarchain.storage.jobs.ServiceStores {
  import org.scholarchain.storage.jobs._
  val idempotency: IdempotencyStore = base.idempotency
  val jobQueue: org.scholarchain.storage.ports.WorkQueue = base.jobQueue
  val jobs: JobStore = new JobStore {
    private val j = base.jobs
    def create(job: NewJob) = j.create(job)
    def get(jobId: String, nowMillis: Long) = j.get(jobId, nowMillis)
    def claim(job: JobRecord, nowMillis: Long, leaseUntilMillis: Long) =
      j.claim(job, nowMillis, leaseUntilMillis)
    def reclaim(job: JobRecord, nowMillis: Long, leaseUntilMillis: Long) =
      j.reclaim(job, nowMillis, leaseUntilMillis)
    def release(job: JobRecord, nowMillis: Long) = j.release(job, nowMillis)
    def succeed(job: JobRecord, nowMillis: Long, result: String): Either[String, JobChange] =
      Right(JobChange.ResultTooLarge(JobLimits.MaxResultBytes + 1, JobLimits.MaxResultBytes))
    def fail(job: JobRecord, nowMillis: Long, error: JobError) = j.fail(job, nowMillis, error)
    def setStage(job: JobRecord, nowMillis: Long, stage: String) = j.setStage(job, nowMillis, stage)
  }
}
