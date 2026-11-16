package org.scholarchain.storage.jobs

import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

import org.scholarchain.storage.{RetryPolicy, RetryingKeyValueStore, Sleeper, TestFixtures}
import org.scholarchain.storage.aws.DynamoDbKeyValueStore
import org.scholarchain.storage.dynamo.{ItemSize, TableSpecs}
import org.scholarchain.storage.local.InMemoryKeyValueStore
import org.scholarchain.storage.ports._

/** QS4 behavior, run identically over the in-memory tables and over the DynamoDB adapter (with
  * storage's retry) on a conditional stub client.
  */
abstract class JobStoreContract extends munit.FunSuite {
  import ServiceStoreFixtures._

  /** A fresh key-value store holding the test configuration's tables. */
  def freshKv(): KeyValueStore

  protected val table: String = querySessionTable
  protected val t0: Long = ManualClock.DefaultStartMillis
  protected val lease: Long = 30000L

  protected def store(kv: KeyValueStore = freshKv()): TableJobStore = new TableJobStore(kv, table)

  protected def written(r: Either[String, JobChange]): JobRecord = r match {
    case Right(JobChange.Written(j)) => j
    case other                       => fail(s"expected Written, got $other")
  }

  protected def queued(s: JobStore, id: String = "job-1"): JobRecord =
    written(s.create(newJob(id, t0)))
  protected def running(s: JobStore, id: String = "job-1"): JobRecord =
    written(s.claim(queued(s, id), t0 + 10, t0 + 10 + lease))
  protected def stored(s: JobStore, id: String = "job-1"): JobRecord =
    s.get(id, t0).toOption.flatten.getOrElse(fail(s"job $id is not stored"))

  test(
    "create is put-if-absent: queued, version 1, 0 attempts; a second create fails its condition"
  ) {
    val s = store()
    val j = queued(s)
    assertEquals(
      (j.state, j.version, j.attempts, j.leaseUntilMillis, j.mode),
      (JobState.Queued, 1L, 0, None, JobMode.Async)
    )
    assertEquals((j.createdAtMillis, j.updatedAtMillis), (t0, t0))
    assertEquals(stored(s), j)
    assertEquals(s.create(newJob("job-1", t0 + 5, JobMode.Sync)), Right(JobChange.ConditionFailed))
    assertEquals(stored(s), j, "the losing create wrote nothing")
  }

  test("get: unknown job is None; an expired job is None (TTL removal is lazy)") {
    val s = store()
    assertEquals(s.get("job-unknown", t0), Right(None))
    val j = queued(s)
    assertEquals(s.get(j.jobId, j.expiresAtSeconds * 1000L - 1).map(_.isDefined), Right(true))
    assertEquals(s.get(j.jobId, j.expiresAtSeconds * 1000L), Right(None))
  }

  test("claim: queued -> running, attempts + 1, lease set, version + 1") {
    val s = store()
    val q = queued(s)
    val r = written(s.claim(q, t0 + 10, t0 + 10 + lease))
    assertEquals(r.state, JobState.Running)
    assertEquals(r.attempts, 1)
    assertEquals(r.leaseUntilMillis, Some(t0 + 10 + lease))
    assertEquals(r.version, 2L)
    assertEquals(r.updatedAtMillis, t0 + 10)
    assertEquals(stored(s), r)
  }

  test("two claims of the same version: exactly one wins, the other gets ConditionFailed") {
    val s = store()
    val q = queued(s)
    val a = s.claim(q, t0 + 1, t0 + 1 + lease)
    val b = s.claim(q, t0 + 1, t0 + 1 + lease) // identical content, still loses
    assert(a.toOption.exists(_.isInstanceOf[JobChange.Written]), a.toString)
    assertEquals(b, Right(JobChange.ConditionFailed))
    assertEquals(stored(s).attempts, 1)
  }

  test("eight concurrent claims of the same version: exactly one wins") {
    val s = store()
    val q = queued(s)
    val pool = Executors.newFixedThreadPool(8)
    implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(pool)
    try {
      val start = new CountDownLatch(1)
      val fs = (1 to 8).map(_ => Future { start.await(); s.claim(q, t0 + 1, t0 + 1 + lease) })
      start.countDown()
      val rs = Await.result(Future.sequence(fs), 30.seconds)
      assertEquals(rs.count(_.toOption.exists(_.isInstanceOf[JobChange.Written])), 1, rs.toString)
      assertEquals(rs.count(_ == Right(JobChange.ConditionFailed)), 7)
      assertEquals(stored(s).version, 2L)
    } finally { pool.shutdown(); pool.awaitTermination(10, TimeUnit.SECONDS) }
  }

  test("reclaim is refused while the lease is live (including at leaseUntil) and allowed after") {
    val s = store()
    val r = running(s)
    val until = r.leaseUntilMillis.get
    assert(
      s.reclaim(r, until - 1, until + lease).toOption.exists(_.isInstanceOf[JobChange.Refused])
    )
    assert(s.reclaim(r, until, until + lease).toOption.exists(_.isInstanceOf[JobChange.Refused]))
    assertEquals(stored(s), r, "a refused reclaim writes nothing")
    val re = written(s.reclaim(r, until + 1, until + 1 + lease))
    assertEquals(
      (re.state, re.attempts, re.leaseUntilMillis),
      (JobState.Running, 2, Some(until + 1 + lease))
    )
    assertEquals(re.version, r.version + 1)
    // the previous holder's record is stale now: its writes lose the version check
    assertEquals(s.succeed(r, until + 2, "late"), Right(JobChange.ConditionFailed))
    assertEquals(s.release(r, until + 2), Right(JobChange.ConditionFailed))
  }

  test("two reclaims of the same expired lease: exactly one wins") {
    val s = store()
    val r = running(s)
    val now = r.leaseUntilMillis.get + 1
    val a = s.reclaim(r, now, now + lease)
    val b = s.reclaim(r, now, now + lease)
    assert(a.toOption.exists(_.isInstanceOf[JobChange.Written]), a.toString)
    assertEquals(b, Right(JobChange.ConditionFailed))
    assertEquals(stored(s).attempts, 2)
  }

  test("claim and reclaim need a lease end after now") {
    val s = store()
    val q = queued(s)
    assert(s.claim(q, t0 + 10, t0 + 10).toOption.exists(_.isInstanceOf[JobChange.Refused]))
    val r = written(s.claim(q, t0 + 10, t0 + 11))
    assert(s.reclaim(r, t0 + 20, t0 + 19).toOption.exists(_.isInstanceOf[JobChange.Refused]))
  }

  test(
    "release: running -> queued, lease cleared, attempts kept; the next claim counts attempt 2"
  ) {
    val s = store()
    val r = running(s)
    val q = written(s.release(r, t0 + 20))
    assertEquals((q.state, q.leaseUntilMillis, q.attempts), (JobState.Queued, None, 1))
    val r2 = written(s.claim(q, t0 + 30, t0 + 30 + lease))
    assertEquals(r2.attempts, 2)
  }

  test("succeed writes the state and the result in one conditional put") {
    val s = store()
    val r = running(s)
    val result = """{"queryId":"q","answer":{}}"""
    val done = written(s.succeed(r, t0 + 50, result))
    assertEquals(
      (done.state, done.result, done.leaseUntilMillis),
      (JobState.Succeeded, Some(result), None)
    )
    assertEquals(done.version, r.version + 1, "one write: state and result share one version")
    assertEquals(stored(s), done)
    // a stale succeed writes neither the state nor the result
    val s2 = store()
    val r2 = running(s2)
    written(s2.setStage(r2, t0 + 20, "planner"))
    assertEquals(s2.succeed(r2, t0 + 50, result), Right(JobChange.ConditionFailed))
    val after = stored(s2)
    assertEquals((after.state, after.result), (JobState.Running, None))
  }

  test("ResultTooLarge: over 300,000 UTF-8 bytes is refused and nothing is written") {
    val s = store()
    val r = running(s)
    assertEquals(
      s.succeed(r, t0 + 50, "x" * 300001),
      Right(JobChange.ResultTooLarge(300001, JobLimits.MaxResultBytes))
    )
    // bytes, not characters: 150,001 two-byte characters
    assertEquals(
      s.succeed(r, t0 + 50, "é" * 150001),
      Right(JobChange.ResultTooLarge(300002, JobLimits.MaxResultBytes))
    )
    assertEquals(stored(s), r)
    val done = written(s.succeed(r, t0 + 50, "x" * JobLimits.MaxResultBytes))
    assertEquals(done.result.map(_.length), Some(JobLimits.MaxResultBytes))
  }

  test("fail: running -> failed and queued -> failed with the error; the lease is cleared") {
    val s = store()
    val err = JobError("WORKFLOW_FAILED", "the workflow failed")
    val f1 = written(s.fail(running(s, "job-a"), t0 + 40, err))
    assertEquals((f1.state, f1.error, f1.leaseUntilMillis), (JobState.Failed, Some(err), None))
    val f2 = written(s.fail(queued(s, "job-b"), t0 + 40, JobError("SUPERSEDED", "superseded")))
    assertEquals((f2.state, f2.error.map(_.code)), (JobState.Failed, Some("SUPERSEDED")))
    assertEquals(stored(s, "job-b"), f2)
  }

  test("setStage: a running job's stage changes; attempts and lease stay") {
    val s = store()
    val r = running(s)
    val st = written(s.setStage(r, t0 + 15, "synthesis"))
    assertEquals(
      (st.stage, st.attempts, st.leaseUntilMillis, st.state),
      (Some("synthesis"), 1, r.leaseUntilMillis, JobState.Running)
    )
    assertEquals(st.version, r.version + 1)
  }

  test("only the allowed transitions: every operation from every state") {
    val err = JobError("WORKFLOW_FAILED", "failed")
    def inState(s: JobStore, st: JobState, id: String): JobRecord = st match {
      case JobState.Queued    => queued(s, id)
      case JobState.Running   => running(s, id)
      case JobState.Succeeded => written(s.succeed(running(s, id), t0 + 20, "r"))
      case JobState.Failed    => written(s.fail(queued(s, id), t0 + 20, err))
    }
    // the running fixture's lease ends at t0 + 10 + lease; `late` is after it
    val late = t0 + 20 + lease
    val ops: List[(String, (JobStore, JobRecord) => Either[String, JobChange], Set[JobState])] =
      List(
        ("claim", (s, j) => s.claim(j, late, late + lease), Set(JobState.Queued)),
        ("reclaim", (s, j) => s.reclaim(j, late, late + lease), Set(JobState.Running)),
        ("release", (s, j) => s.release(j, late), Set(JobState.Running)),
        ("succeed", (s, j) => s.succeed(j, late, "r"), Set(JobState.Running)),
        ("fail", (s, j) => s.fail(j, late, err), Set(JobState.Queued, JobState.Running)),
        ("setStage", (s, j) => s.setStage(j, late, "audit"), Set(JobState.Running))
      )
    for {
      (name, op, allowed) <- ops
      st <- JobState.all
    } {
      val s = store()
      val j = inState(s, st, s"job-$name-${st.wire}")
      val out = op(s, j)
      if (allowed(st))
        assert(
          out.toOption.exists(_.isInstanceOf[JobChange.Written]),
          s"$name from ${st.wire}: $out"
        )
      else {
        assert(
          out.toOption.exists(_.isInstanceOf[JobChange.Refused]),
          s"$name from ${st.wire}: $out"
        )
        assertEquals(stored(s, j.jobId), j, s"$name from ${st.wire} wrote nothing")
      }
    }
  }

  test("a version mismatch is ConditionFailed for every change, never an exception") {
    val s = store()
    val r = running(s)
    val newer = written(s.setStage(r, t0 + 11, "planner"))
    assert(newer.version > r.version)
    val stale = r
    val now = r.leaseUntilMillis.get + 1
    assertEquals(s.release(stale, t0 + 12), Right(JobChange.ConditionFailed))
    assertEquals(s.succeed(stale, t0 + 12, "x"), Right(JobChange.ConditionFailed))
    assertEquals(s.fail(stale, t0 + 12, JobError("X", "x")), Right(JobChange.ConditionFailed))
    assertEquals(s.setStage(stale, t0 + 12, "metrics"), Right(JobChange.ConditionFailed))
    assertEquals(s.reclaim(stale, now, now + lease), Right(JobChange.ConditionFailed))
    // a record of a job that does not exist
    assertEquals(
      s.release(r.copy(jobId = "job-missing"), t0 + 12),
      Right(JobChange.ConditionFailed)
    )
  }

  test("malformed arguments are Left and write nothing") {
    val s = store()
    assert(s.create(newJob("job id with spaces", t0)).isLeft)
    assert(s.create(newJob("job-1", t0).copy(traceId = "not-a-trace")).isLeft)
    assert(s.create(newJob("job-1", t0).copy(request = request.copy(question = "q" * 2001))).isLeft)
    assert(s.create(newJob("job-1", t0).copy(request = request.copy(question = ""))).isLeft)
    assert(
      s.create(newJob("job-1", t0).copy(request = request.copy(topic = Some("t" * 201)))).isLeft
    )
    assert(s.create(newJob("job-1", t0).copy(expiresAtSeconds = t0 / 1000L)).isLeft)
    assertEquals(s.get("job-1", t0), Right(None))
    val r = running(s)
    assert(s.fail(r, t0 + 20, JobError("lower-case", "x")).isLeft)
    assert(s.fail(r, t0 + 20, JobError("X", "m" * 1001)).isLeft)
    assert(s.setStage(r, t0 + 20, "bad stage").isLeft)
    assertEquals(stored(s), r)
  }

  test("notes round-trip through create, get and every later write, in order") {
    val kv = freshKv()
    val s = store(kv)
    val notes = List(
      "retrieval.graphLimit was accepted but not applied; the plan comes from the question",
      "analysis.rankingMetric was accepted but not applied; the plan comes from the question",
      "é中 accepted"
    )
    val j = written(s.create(newJob("job-n", t0).copy(request = request.copy(notes = notes))))
    assertEquals(j.request.notes, notes)
    assertEquals(stored(s, "job-n"), j)
    val item = kv.get(table, ItemKey("job#job-n", "job")).get
    assertEquals(item.get("notes"), Some(AttrValue.L(notes.map(AttrValue.S(_)))))
    val r = written(s.claim(j, t0 + 1, t0 + 1 + lease))
    val done = written(s.succeed(r, t0 + 2, "done"))
    assertEquals(stored(s, "job-n").request, j.request)
    assertEquals(done.request.notes, notes)
  }

  test("no notes: the attribute is absent and an absent attribute reads as Nil") {
    val kv = freshKv()
    val s = store(kv)
    val j = queued(s)
    assertEquals(j.request.notes, Nil)
    val item = kv.get(table, ItemKey("job#job-1", "job")).get
    assertEquals(item.get("notes"), None, "Nil is not written")
    assertEquals(stored(s).request.notes, Nil)
    // an item written before the field existed (no notes attribute) still decodes
    assertEquals(JobItems.decode(item).map(_.request.notes), Right(Nil))
  }

  test("a stored notes attribute that is not a list of strings is a malformed item") {
    val item = JobItems.encode(queued(store()), "tok").toOption.get
    assert(JobItems.decode(item).isRight)
    assert(JobItems.decode(item.updated("notes", AttrValue.S("x"))).isLeft)
    assert(JobItems.decode(item.updated("notes", AttrValue.L(List(AttrValue.n(1L))))).isLeft)
  }

  test("notes bounds: count, length, empty, null and control characters are Left") {
    val s = store()
    def withNotes(id: String, n: List[String]) =
      s.create(newJob(id, t0).copy(request = request.copy(notes = n)))
    val maxNotes = List.fill(JobLimits.MaxNotes)("n" * JobLimits.MaxNoteChars)
    assertEquals(written(withNotes("job-max", maxNotes)).request.notes, maxNotes)
    assert(withNotes("job-a", List.fill(JobLimits.MaxNotes + 1)("n")).isLeft, "too many")
    assert(withNotes("job-b", List("n" * (JobLimits.MaxNoteChars + 1))).isLeft, "too long")
    assert(withNotes("job-c", List("")).isLeft, "empty")
    assert(withNotes("job-d", List("ok", null)).isLeft, "null note")
    assert(withNotes("job-e", null).isLeft, "null list")
    assert(withNotes("job-f", List("line\nbreak")).isLeft, "newline")
    assert(withNotes("job-g", List("tab\tx")).isLeft, "tab")
    assert(withNotes("job-h", List("nul\u0000")).isLeft, "NUL")
    assert(withNotes("job-i", List("del\u007f")).isLeft, "DEL")
    assert(withNotes("job-j", List("c1\u0085")).isLeft, "C1 control")
    for (id <- List("a", "b", "c", "d", "e", "f", "g", "h", "i", "j"))
      assertEquals(s.get(s"job-$id", t0), Right(None), s"job-$id was not written")
  }

  test("the largest job item stays under the DynamoDB item size limit") {
    val kv = freshKv()
    val s = store(kv)
    val big = newJob("job-" + "a" * 120, t0).copy(request =
      request.copy(
        question = "中" * JobLimits.MaxQuestionChars,
        topic = Some("t" * 200),
        notes = List.fill(JobLimits.MaxNotes)("中" * JobLimits.MaxNoteChars)
      )
    )
    val r = written(s.claim(written(s.create(big)), t0 + 1, t0 + 1 + lease))
    val done = written(s.succeed(r, t0 + 2, "é" * (JobLimits.MaxResultBytes / 2)))
    val item = kv.get(table, ItemKey(TableSpecs.jobPk(done.jobId), TableSpecs.JobSk)).get
    assert(ItemSize.of(item) < 400 * 1024, ItemSize.of(item).toString)
  }

  test("a store failure is Left, not an exception") {
    val toggle = new ToggleKeyValueStore(freshKv())
    val s = store(toggle)
    val q = queued(s)
    toggle.failing.set(true)
    assert(s.claim(q, t0 + 1, t0 + 1 + lease).left.exists(_.contains("store failure")))
    assert(s.get(q.jobId, t0).isLeft)
    assert(s.create(newJob("job-2", t0)).isLeft)
  }

  test("QS4 key format and stored attributes") {
    val kv = freshKv()
    val s = store(kv)
    val r = written(s.claim(written(s.create(newJob("job-7", t0))), t0 + 1, t0 + 1 + lease))
    val item = kv.get(table, ItemKey("job#job-7", "job")).get
    assertEquals(item.long("expiresAt"), Some(r.expiresAtSeconds), "TTL attribute in epoch seconds")
    assertEquals(item.long("leaseUntil"), r.leaseUntilMillis)
    assertEquals(item.long(Item.Version), Some(2L))
    assertEquals(item.strOpt("state"), Some("running"))
    assertEquals(item.strOpt("queryRunId"), Some(queryRunId.value))
    assert(item.strOpt("writeToken").nonEmpty)
  }
}

class InMemoryJobStoreSuite extends JobStoreContract {
  def freshKv(): KeyValueStore = new InMemoryKeyValueStore(TestFixtures.storageConfig.tableNames)
}

class DynamoJobStoreSuite extends JobStoreContract {
  import ServiceStoreFixtures.newJob
  private val policy = RetryPolicy(2, 1, 1)
  def freshKv(): KeyValueStore =
    new RetryingKeyValueStore(
      new DynamoDbKeyValueStore(new ConditionalDynamoStub),
      policy,
      Sleeper.none
    )

  test("DynamoDB: only the two conditional puts are sent (no unconditional put)") {
    val stub = new ConditionalDynamoStub
    val s = store(new DynamoDbKeyValueStore(stub))
    val r = running(s)
    written(s.succeed(r, t0 + 20, "done"))
    assertEquals(
      stub.conditions.toList,
      List(
        "attribute_not_exists(pk)",
        "attribute_exists(pk) AND #v = :expected",
        "attribute_exists(pk) AND #v = :expected"
      )
    )
  }

  test("DynamoDB: a retried write whose first attempt was applied reports Written") {
    val stub = new ConditionalDynamoStub
    val s = store(new RetryingKeyValueStore(new DynamoDbKeyValueStore(stub), policy, Sleeper.none))
    val q = queued(s)
    stub.applyThenFail(1)
    val r = written(s.claim(q, t0 + 1, t0 + 1 + lease))
    assertEquals(r.attempts, 1)
    assertEquals(stub.puts.get, 3, "create, the applied claim, its retry")
    stub.applyThenFail(1)
    assertEquals(s.create(newJob("job-2", t0)).map(_.isInstanceOf[JobChange.Written]), Right(true))
    // a different writer at the same version still loses
    assertEquals(s.claim(q, t0 + 1, t0 + 1 + lease), Right(JobChange.ConditionFailed))
  }
}
