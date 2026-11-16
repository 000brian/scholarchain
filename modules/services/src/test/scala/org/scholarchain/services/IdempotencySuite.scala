package org.scholarchain.services

import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}

import org.scholarchain.core.RunId
import org.scholarchain.rag.runtime.FakeModel
import org.scholarchain.rag.runtime.RuntimeServiceFixtures.GatedModel
import org.scholarchain.storage.jobs.{JobMode, JobState}

/** Idempotency-Key behaviour (decision 7e). The workflow count is the number of audit records of
  * the query run (each workflow run appends one).
  */
class IdempotencySuite extends munit.FunSuite {
  import ServiceTestKit._

  override val munitTimeout = scala.concurrent.duration.Duration(180, "s")

  private def runs(h: Harness, queryId: String): Int =
    h.runtime.stores.audits.read(RunId(queryId), 100).fold(e => fail(e), _.size)

  private def key(k: String) = List("Idempotency-Key" -> k)

  test("sync: a repeated request with the same key replays the result; the workflow runs once") {
    val h = harness()
    try {
      val first = post(h, queryBody(), headers = key("k-sync-1"))
      assertEquals(first.status, 200, first.body)
      assertEquals(first.header("Idempotent-Replayed"), None)
      val again = post(h, queryBody(), headers = key("k-sync-1"))
      assertEquals(again.status, 200)
      assertEquals(again.header("Idempotent-Replayed"), Some("true"))
      assertEquals(again.body, first.body)
      assertEquals(runs(h, first.json("queryId").str), 1)
      // a different key runs again
      assertEquals(post(h, queryBody(), headers = key("k-sync-2")).status, 200)
      assertEquals(runs(h, first.json("queryId").str), 2)
    } finally h.close()
  }

  test("async: a repeated request with the same key returns the acknowledgment; one job, one run") {
    val h = harness()
    try {
      val first = post(h, queryBody(mode = "async"), headers = key("k-async"))
      assertEquals(first.status, 202)
      val jobId = first.json("jobId").str
      // while queued: in progress
      val queued = post(h, queryBody(mode = "async"), headers = key("k-async"))
      assertEquals(queued.status, 409)
      assertEquals(queued.error, "IDEMPOTENCY_IN_PROGRESS")
      assertEquals(queued.json("retryable").bool, true)
      assert(queued.header("Retry-After").nonEmpty)
      assertEquals(h.server.worker.processOne(), WorkerOutcome.Succeeded)
      val replay = post(h, queryBody(mode = "async"), headers = key("k-async"))
      assertEquals(replay.status, 202)
      assertEquals(replay.json("jobId").str, jobId)
      assertEquals(replay.json("status").str, "SUCCEEDED")
      assertEquals(replay.header("Idempotent-Replayed"), Some("true"))
      assertEquals(h.queue.size, 0)
      assertEquals(runs(h, first.json("queryId").str), 1)
    } finally h.close()
  }

  test("a different request with the same key is 409 IDEMPOTENCY_CONFLICT (not retryable)") {
    val h = harness()
    try {
      assertEquals(post(h, queryBody(), headers = key("k-conflict")).status, 200)
      val other = post(
        h,
        queryBody(question = "Who are the coauthors of Carol King?"),
        headers = key("k-conflict")
      )
      assertEquals(other.status, 409)
      assertEquals(other.error, "IDEMPOTENCY_CONFLICT")
      assertEquals(other.json("retryable").bool, false)
      // the mode is part of the fingerprint
      val async = post(h, queryBody(mode = "async"), headers = key("k-conflict"))
      assertEquals(async.error, "IDEMPOTENCY_CONFLICT")
      // the read-only scope is separate (and may not POST at all)
      assertEquals(
        post(h, queryBody(), key = Some(ReadOnlyKey), headers = key("k-conflict")).status,
        403
      )
    } finally h.close()
  }

  test(
    "same key while running is 409 in progress; replay after a 504 returns the result once finished"
  ) {
    val cfg = withService(config)(_.copy(syncDeadlineMillis = 300))
    val h = harness(cfg = cfg, model = r => new GatedModel(new FakeModel(r)))
    val gated = h.model.asInstanceOf[GatedModel]
    try {
      val first = post(h, queryBody(), headers = key("k-slow"))
      assertEquals(first.status, 504, first.body)
      assertEquals(first.error, "WORKFLOW_TIMEOUT")
      assertEquals(first.json("retryable").bool, true)
      val during = post(h, queryBody(), headers = key("k-slow"))
      assertEquals(during.status, 409)
      assertEquals(during.error, "IDEMPOTENCY_IN_PROGRESS")
      gated.release()
      // the workflow keeps running after the 504 and writes its result to the job
      assert(waitUntil() {
        h.stores.idempotency
          .get("full", "k-slow", h.clock.millis())
          .toOption
          .flatten
          .flatMap(r => h.stores.jobs.get(r.jobId, h.clock.millis()).toOption.flatten)
          .exists(_.state == JobState.Succeeded)
      })
      val replay = post(h, queryBody(), headers = key("k-slow"))
      assertEquals(replay.status, 200, replay.body)
      assertEquals(replay.header("Idempotent-Replayed"), Some("true"))
      assertEquals(runs(h, replay.json("queryId").str), 1)
    } finally { gated.release(); h.close() }
  }

  test("replay of a failed job is 409 JOB_FAILED with the job error code") {
    val h = harness()
    try {
      h.flaky.failGraph.set(true)
      val first = post(h, queryBody(), headers = key("k-fail"))
      assertEquals(first.status, 502)
      assertEquals(first.error, "DOWNSTREAM_FAILURE")
      h.flaky.failGraph.set(false)
      val replay = post(h, queryBody(), headers = key("k-fail"))
      assertEquals(replay.status, 409)
      assertEquals(replay.error, "JOB_FAILED")
      assertEquals(replay.json("details")("jobError").str, JobErrorCodes.WorkflowFailed)
      assertEquals(replay.json("retryable").bool, false)
    } finally h.close()
  }

  test("a sync job whose process died (expired lease) is marked LEASE_EXPIRED, then JOB_FAILED") {
    val h = harness()
    try {
      val s = h.settings
      val now = h.clock.millis()
      val fp = new QueryRequestParser(h.runtime.config.graph.windows, h.runtime.context.runtime, 30)
        .parse(queryBody())
        .fold(e => fail(e.toString), identity)
      val qid = h.runtime.queryRunId(fp.toQueryRequest).toOption.get
      val job = h.stores.jobs
        .create(
          org.scholarchain.storage.jobs.NewJob(
            "job-dead",
            JobMode.Sync,
            qid,
            fp.toJobRequest,
            "0" * 31 + "1",
            now,
            now / 1000 + s.jobTtlSeconds
          )
        )
        .toOption
        .get
        .asInstanceOf[org.scholarchain.storage.jobs.JobChange.Written]
        .job
      h.stores.jobs.claim(job, now, now + s.jobLeaseMillis)
      h.stores.idempotency.claim(
        "full",
        "k-dead",
        fp.fingerprint,
        "job-dead",
        now / 1000 + s.jobTtlSeconds,
        now
      )
      val live = post(h, queryBody(), headers = key("k-dead"))
      assertEquals(live.error, "IDEMPOTENCY_IN_PROGRESS")
      h.clock.advance(s.jobLeaseMillis.toLong + 1)
      val dead = post(h, queryBody(), headers = key("k-dead"))
      assertEquals(dead.status, 409)
      assertEquals(dead.error, "JOB_FAILED")
      assertEquals(dead.json("details")("jobError").str, JobErrorCodes.LeaseExpired)
    } finally h.close()
  }

  test("two concurrent first requests with one key run one workflow") {
    val h = harness(model = r => new GatedModel(new FakeModel(r)))
    val gated = h.model.asInstanceOf[GatedModel]
    val pool = Executors.newFixedThreadPool(2)
    try {
      val start = new CountDownLatch(1)
      val futures = (1 to 2).map { _ =>
        pool.submit(() => { start.await(); post(h, queryBody(), headers = key("k-race")) })
      }
      start.countDown()
      assert(gated.awaitEntered(30000L))
      Thread.sleep(200)
      gated.release()
      val results = futures.map(_.get(60, TimeUnit.SECONDS))
      val statuses = results.map(_.status).sorted
      // one ran (200); the other either replayed it (200) or saw it in progress (409)
      assert(statuses.contains(200), statuses.toString)
      assert(statuses.forall(s => s == 200 || s == 409), results.map(_.body).toString)
      val qid = results.find(_.status == 200).get.json("queryId").str
      assertEquals(runs(h, qid), 1)
    } finally { gated.release(); pool.shutdownNow(); h.close() }
  }

  // ---- the lost claim (decision 7e), made deterministic: `get` misses the winner's record, then
  // the put-if-absent claim finds it (review finding S-2)

  private def jobOf(h: Harness, jobId: String) =
    h.stores.jobs.get(jobId, h.clock.millis()).fold(e => fail(e), identity).getOrElse(fail(jobId))

  test("sync, lost claim: own job SUPERSEDED, response follows the winner, no extra workflow run") {
    val lost = new LostClaimStores()
    val h = harness(wrapStores = lost.over)
    try {
      val first = post(h, queryBody(), headers = key("k-lost-sync"))
      assertEquals(first.status, 200, first.body)
      val qid = first.json("queryId").str
      assertEquals(runs(h, qid), 1)
      val winner =
        h.stores.idempotency.get("full", "k-lost-sync", h.clock.millis()).toOption.flatten
      lost.blindGet.set(true)
      val loser = post(h, queryBody(), headers = key("k-lost-sync"))
      // the branch was taken: the claim found the winner's record
      assertEquals(lost.losers.size, 1)
      val loserJob = jobOf(h, lost.losers.get(0))
      assertNotEquals(Some(loserJob.jobId), winner.map(_.jobId))
      assertEquals(loserJob.mode, JobMode.Sync)
      assertEquals(loserJob.state, JobState.Failed)
      assertEquals(loserJob.error.map(_.code), Some(JobErrorCodes.Superseded))
      // the response follows the existing record: the winner's result, replayed
      assertEquals(loser.status, 200, loser.body)
      assertEquals(loser.header("Idempotent-Replayed"), Some("true"))
      assertEquals(loser.body, first.body)
      // no second workflow, and the permit and breaker slot came back
      assertEquals(runs(h, qid), 1)
      assertEquals(h.server.app.query.freePermits, h.settings.maxConcurrentQueries)
      // the key still points at the winner's job
      lost.blindGet.set(false)
      assertEquals(
        h.stores.idempotency.get("full", "k-lost-sync", h.clock.millis()).toOption.flatten,
        winner
      )
    } finally h.close()
  }

  test(
    "async, lost claim: queued job SUPERSEDED, its message acked unrun, 202 follows the winner"
  ) {
    val lost = new LostClaimStores()
    val h = harness(wrapStores = lost.over)
    try {
      val first = post(h, queryBody(mode = "async"), headers = key("k-lost-async"))
      assertEquals(first.status, 202, first.body)
      val winnerJob = first.json("jobId").str
      assertEquals(h.server.worker.processOne(), WorkerOutcome.Succeeded)
      val qid = first.json("queryId").str
      assertEquals(runs(h, qid), 1)
      lost.blindGet.set(true)
      val loser = post(h, queryBody(mode = "async"), headers = key("k-lost-async"))
      assertEquals(lost.losers.size, 1)
      val loserId = lost.losers.get(0)
      assertNotEquals(loserId, winnerJob)
      val loserJob = jobOf(h, loserId)
      assertEquals(loserJob.state, JobState.Failed)
      assertEquals(loserJob.error.map(_.code), Some(JobErrorCodes.Superseded))
      assertEquals(loserJob.attempts, 0)
      // the 202 follows the winner (a replay of its acknowledgment)
      assertEquals(loser.status, 202, loser.body)
      assertEquals(loser.json("jobId").str, winnerJob)
      assertEquals(loser.json("status").str, "SUCCEEDED")
      assertEquals(loser.header("Idempotent-Replayed"), Some("true"))
      // the loser's message was enqueued before the claim; the worker acks it without running
      assertEquals(h.queue.size, 1)
      val acked = h.queue.ackedCount
      assertEquals(h.server.worker.processOne(), WorkerOutcome.AckedWithoutRun)
      assertEquals(h.queue.ackedCount, acked + 1)
      assertEquals(h.queue.size, 0)
      assertEquals(jobOf(h, loserId).error.map(_.code), Some(JobErrorCodes.Superseded))
      assertEquals(runs(h, qid), 1)
    } finally h.close()
  }

  test("async, lost claim: a losing job a worker already claimed is not superseded") {
    val lost = new LostClaimStores()
    val h = harness(wrapStores = lost.over)
    try {
      val first = post(h, queryBody(mode = "async"), headers = key("k-lost-claimed"))
      assertEquals(first.status, 202, first.body)
      lost.blindGet.set(true)
      // between the enqueue and the lost claim, a worker claims the losing job
      lost.beforeExisting.set { jobId =>
        val j = jobOf(h, jobId)
        val now = h.clock.millis()
        h.stores.jobs.claim(j, now, now + h.settings.jobLeaseMillis); ()
      }
      val loser = post(h, queryBody(mode = "async"), headers = key("k-lost-claimed"))
      assertEquals(lost.losers.size, 1)
      val loserJob = jobOf(h, lost.losers.get(0))
      assertEquals(loserJob.state, JobState.Running)
      assertEquals(loserJob.error, None)
      // the winner is still queued: in progress
      assertEquals(loser.status, 409, loser.body)
      assertEquals(loser.error, "IDEMPOTENCY_IN_PROGRESS")
    } finally h.close()
  }

  test("after jobTtlSeconds the same key starts a new job") {
    val h = harness()
    try {
      val first = post(h, queryBody(mode = "async"), headers = key("k-ttl"))
      assertEquals(first.status, 202)
      h.clock.advance(h.settings.jobTtlSeconds * 1000L + 1)
      val later = post(h, queryBody(mode = "async"), headers = key("k-ttl"))
      assertEquals(later.status, 202)
      assertNotEquals(later.json("jobId").str, first.json("jobId").str)
    } finally h.close()
  }
}

/** Service stores whose idempotency `get` can be made to miss (`blindGet`), so a request reaches
  * the put-if-absent claim although a record is already stored: the claim then returns
  * `Existing(record)`, exactly as when a concurrent request won between the two calls. The job IDs
  * of such lost claims are kept in `losers`; `beforeExisting` runs just before a lost claim
  * returns.
  */
final class LostClaimStores {
  import org.scholarchain.storage.jobs._

  val blindGet = new java.util.concurrent.atomic.AtomicBoolean(false)
  val losers = new java.util.concurrent.CopyOnWriteArrayList[String]()
  val beforeExisting = new java.util.concurrent.atomic.AtomicReference[String => Unit](_ => ())

  def over(base: ServiceStores): ServiceStores = new ServiceStores {
    val jobs: JobStore = base.jobs
    val jobQueue: org.scholarchain.storage.ports.WorkQueue = base.jobQueue
    val idempotency: IdempotencyStore = new IdempotencyStore {
      def claim(
          scope: String,
          idempotencyKey: String,
          fingerprint: String,
          jobId: String,
          expiresAtSeconds: Long,
          nowMillis: Long
      ): Either[String, IdempotencyClaim] =
        base.idempotency.claim(
          scope,
          idempotencyKey,
          fingerprint,
          jobId,
          expiresAtSeconds,
          nowMillis
        ) match {
          case r @ Right(IdempotencyClaim.Existing(_)) =>
            losers.add(jobId)
            beforeExisting.get()(jobId)
            r
          case other => other
        }
      def get(
          scope: String,
          idempotencyKey: String,
          nowMillis: Long
      ): Either[String, Option[IdempotencyRecord]] =
        if (blindGet.get()) Right(None) else base.idempotency.get(scope, idempotencyKey, nowMillis)
    }
  }
}
