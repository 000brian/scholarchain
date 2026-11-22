package org.scholarchain.services

import java.util.concurrent.{ConcurrentHashMap, CountDownLatch, Executors, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.jdk.CollectionConverters._

import org.scholarchain.core.{MentionId, ResearcherId, WorkId}
import org.scholarchain.rag.runtime.RuntimeFixtures
import org.scholarchain.storage.{Sleeper, TestFixtures}
import org.scholarchain.storage.aws.{GraphRetry, NeptuneGraphRead}
import org.scholarchain.storage.jobs.ManualClock
import org.scholarchain.storage.ports.{
  GraphQuery,
  GraphReadError,
  GraphReadPort,
  GraphReadSettings,
  GraphResult
}
import software.amazon.awssdk.core.document.Document
import software.amazon.awssdk.services.neptunedata.NeptunedataClient
import software.amazon.awssdk.services.neptunedata.model.{
  ExecuteOpenCypherQueryRequest,
  ExecuteOpenCypherQueryResponse,
  InternalFailureException
}

/** Phase 16, FECD-1 breaker side (step D's B1, B3, B4, B5): the graph circuit breaker under
  * concurrent callers, with stub graph ports and with storage's retried Neptune port over a fake
  * client. No network, no AWS.
  */
class GraphBreakerSuite extends munit.FunSuite {
  import GraphBreakerSuite._
  import ServiceTestKit._

  override val munitTimeout = scala.concurrent.duration.Duration(120, "s")

  private val Threshold = 3
  private val OpenMillis = 30000L

  private def withPool[A](n: Int)(f: ExecutionContext => A): A = {
    val pool = Executors.newFixedThreadPool(n)
    try f(ExecutionContext.fromExecutor(pool))
    finally pool.shutdownNow()
  }

  private def log(): (MemoryLogSink, ServiceLog) = {
    val sink = new MemoryLogSink
    (sink, new ServiceLog(sink, "graph", new ManualClock(), new Redactor(Nil)))
  }

  private def researcher(i: Int): ResearcherId =
    ResearcherId.fromAnchorMention(MentionId.of(WorkId.fromDblpKey(s"conf/brk/G$i"), 0))

  private def neighbors(i: Int): GraphQuery =
    GraphQuery.CoauthorNeighbors(researcher(i), TestFixtures.Full, 5)

  // ---- B1: in-flight outcomes after the breaker changed state ----------------------------------

  test(
    "B1: 10 concurrent callers fail while all in flight; the 7 failures after the opening neither extend the open period nor count"
  ) {
    val callers = 10
    val clock = new ManualClock()
    val changes = new java.util.concurrent.ConcurrentLinkedQueue[String]()
    val breaker =
      new CircuitBreaker("graph", Threshold, OpenMillis, clock, (_, s) => { changes.add(s); () })
    val allInFlight = new CountDownLatch(callers)
    val secondWave = new CountDownLatch(1)
    val arrived = new AtomicInteger()
    val succeed = new AtomicBoolean(false)
    val port = new StubPort(q => {
      if (succeed.get) Right(GraphResult.bounded(q.templateId, Nil, 5))
      else {
        allInFlight.countDown()
        allInFlight.await(20, TimeUnit.SECONDS)
        // the first `Threshold` callers fail at once; the others fail after the clock moved on
        if (arrived.incrementAndGet() > Threshold) secondWave.await(20, TimeUnit.SECONDS)
        Left(GraphReadError.Transient("neptune-host.example: internal failure"))
      }
    })
    val (sink, l) = log()
    val opened = clock.millis()
    val outs = withPool(callers) { implicit ec =>
      val fs =
        (1 to callers).toList.map(i => Future(GraphReads.run(breaker, port, l)(neighbors(i))))
      assert(waitUntil()(breaker.openCount == 1L), "the breaker did not open")
      clock.advance(1000L) // the in-flight failures now return one second after the opening
      secondWave.countDown()
      Await.result(Future.sequence(fs), 30.seconds)
    }
    // every caller was admitted while closed, so each one got a store answer: 502, never 503
    assertEquals(outs.map(_.left.map(_.code)).distinct, List(Left(ErrorCode.DOWNSTREAM_FAILURE)))
    assertEquals(breaker.openCount, 1L)
    assertEquals(breaker.staleOutcomes, (callers - Threshold).toLong)
    // the open period runs from the opening, not from the last in-flight failure
    clock.set(opened + OpenMillis - 1)
    assertEquals(breaker.state, "open")
    clock.set(opened + OpenMillis)
    assertEquals(breaker.state, "half-open")
    succeed.set(true)
    assert(GraphReads.run(breaker, port, l)(neighbors(1)).isRight)
    assertEquals(breaker.state, "closed")
    assertEquals(changes.asScala.toList, List("open", "closed"))
    // one failure line per failed read, with the allowlisted code and never the store's text
    val lines = sink.all.filter(_("event").str == "graph-read-failed")
    assertEquals(lines.size, callers)
    assert(lines.forall(_("code").str == "graph-transient"))
    assert(lines.forall(_("template").str == "coauthor_neighbors"))
    assert(!sink.rendered.exists(_.contains("neptune-host")), sink.rendered.toString)
  }

  test("B1: a stale failure never frees or fails the half-open trial (one trial at a time)") {
    val clock = new ManualClock()
    val b = new CircuitBreaker("graph", 1, OpenMillis, clock)
    val early = b.acquire().get // admitted while closed, still in flight
    val failing = b.acquire().get
    b.recordFailure(failing) // opens
    clock.advance(OpenMillis)
    val trial = b.acquire()
    assert(trial.exists(_.trial))
    b.recordFailure(early) // the early call fails now, long after the opening
    assertEquals(b.state, "half-open", "a stale failure must not open the breaker again")
    assertEquals(b.acquire(), None, "the trial is still in flight: no second trial")
    b.recordSuccess(trial.get)
    assertEquals(b.state, "closed")
    assertEquals((b.openCount, b.staleOutcomes), (1L, 1L))
  }

  test("B1: a stale success does not close an open breaker without a trial") {
    val clock = new ManualClock()
    val b = new CircuitBreaker("graph", 1, OpenMillis, clock)
    val slow = b.acquire().get
    b.recordFailure(b.acquire().get) // opens
    b.recordSuccess(slow) // admitted before the opening
    assertEquals(b.state, "open")
    assertEquals(b.acquire(), None)
  }

  // ---- B3, B4: only availability failures count; each failure is logged by class ----------------

  test(
    "B3: permanent and invalid graph errors answer 502 but never open the breaker; transient and resource-limit errors do"
  ) {
    val h = harness()
    try {
      val node = s"/v1/graph/nodes/${RuntimeFixtures.rC.value}"
      val n = h.settings.circuitFailureThreshold + 2
      for (
        err <- List(
          GraphReadError.Permanent("AccessDenied at neptune-host.example"),
          GraphReadError.Invalid("window w9 is not configured")
        )
      ) {
        h.slow.graphError.set(Some(err))
        (1 to n).foreach { _ =>
          val r = get(h, node)
          assertEquals(r.status, 502, r.body)
          assertEquals(r.error, "DOWNSTREAM_FAILURE")
        }
        assertEquals(h.server.breakers.graph.state, "closed", err.code)
      }
      h.slow.graphError.set(Some(GraphReadError.ResourceLimit("out of memory")))
      (1 to h.settings.circuitFailureThreshold).foreach(_ => assertEquals(get(h, node).status, 502))
      assertEquals(get(h, node).error, "CIRCUIT_OPEN")
      assertEquals(h.server.breakers.graph.state, "open")
      h.slow.graphError.set(None)
      h.clock.advance(h.settings.circuitOpenMillis.toLong)
      assertEquals(get(h, node).status, 200)
      val lines = h.log.all.filter(_("event").str == "graph-read-failed")
      assertEquals(
        lines.map(l => (l("code").str, l("counted").bool)).distinct,
        List(
          ("graph-permanent", false),
          ("graph-invalid-query", false),
          ("graph-resource-limit", true)
        )
      )
      assert(lines.forall(_("template").str == "researcher_by_id"))
      assert(lines.forall(l => l.obj.keySet.toSet.subsetOf(LineKeys)), lines.toString)
      val states = h.log.all.filter(_("event").str == "circuit-state").map(_("state").str)
      assertEquals(states, List("open", "closed"))
      assert(!h.log.rendered.exists(l => l.contains("neptune-host") || l.contains("w9")))
    } finally h.close()
  }

  test("B3: a thrown permanent StoreException does not count; a thrown transient one does") {
    val clock = new ManualClock()
    val b = new CircuitBreaker("graph", 1, OpenMillis, clock)
    val (_, l) = log()
    val permanent = new StubPort(_ =>
      throw new org.scholarchain.storage.StoreException("denied", transient = false)
    )
    assert(GraphReads.run(b, permanent, l)(neighbors(1)).isLeft)
    assertEquals(b.state, "closed")
    val transient =
      new StubPort(_ => throw new org.scholarchain.storage.StoreException("busy", transient = true))
    assert(GraphReads.run(b, transient, l)(neighbors(1)).isLeft)
    assertEquals(b.state, "open")
  }

  // ---- B5: against storage's retried port ---------------------------------------------------

  private def burstAgainstNeptune(retry: GraphRetry, callers: Int) = {
    val allInFlight = new CountDownLatch(callers)
    val attempts = new ConcurrentHashMap[String, AtomicInteger]()
    val fake = new FakeNeptune(r => {
      val key = ujson.read(r.parameters()).obj.get("researcherId").map(_.str).getOrElse("")
      val n = attempts.computeIfAbsent(key, _ => new AtomicInteger()).incrementAndGet()
      if (n == 1) {
        // every first attempt is in flight together, then fails with one transient 500
        allInFlight.countDown()
        allInFlight.await(20, TimeUnit.SECONDS)
        throw InternalFailureException
          .builder()
          .message("internal failure")
          .statusCode(500)
          .build()
      }
      List(Map("coauthorId" -> "c1"))
    })
    val port = new NeptuneGraphRead(
      fake,
      TestFixtures.graphSettings,
      retry = retry,
      sleeper = Sleeper.none,
      jitter = () => 0.5
    )
    val breaker = new CircuitBreaker("graph", Threshold, OpenMillis, new ManualClock())
    val (sink, l) = log()
    val outs = withPool(callers) { implicit ec =>
      Await.result(
        Future.sequence((1 to callers).toList.map { i =>
          Future(GraphReads.run(breaker, port, l)(neighbors(i)))
        }),
        30.seconds
      )
    }
    (outs, breaker, port, sink)
  }

  test(
    "B5: with storage's bounded retry, 8 concurrent transient 500s become 8 answers and the breaker stays closed"
  ) {
    val (outs, breaker, port, sink) = burstAgainstNeptune(GraphRetry.Default, 8)
    assert(outs.forall(_.exists(_.rows.size == 1)), outs.toString)
    assertEquals((breaker.state, breaker.openCount), ("closed", 0L))
    assertEquals((port.counters.retries.get, port.counters.recovered.get), (8L, 8L))
    assertEquals(sink.all.count(_("event").str == "graph-read-failed"), 0)
  }

  test(
    "B5: without the retry (the Phase 15 behavior) the same burst is 8 failures and opens the breaker once"
  ) {
    val (outs, breaker, _, sink) = burstAgainstNeptune(GraphRetry.Off, 8)
    assertEquals(outs.count(_.isLeft), 8)
    assertEquals((breaker.state, breaker.openCount), ("open", 1L))
    assertEquals(breaker.staleOutcomes, 5L)
    assertEquals(
      sink.all.filter(_("event").str == "graph-read-failed").map(_("code").str).distinct,
      List("graph-transient")
    )
  }
}

object GraphBreakerSuite {

  /** The keys a `graph-read-failed` line may have. */
  val LineKeys: Set[String] =
    Set("time", "role", "event", "template", "code", "counted", "breaker")

  /** A graph port whose classified answer comes from `answer` (thread-safe when `answer` is). */
  final class StubPort(answer: GraphQuery => Either[GraphReadError, GraphResult])
      extends GraphReadPort {
    def settings: GraphReadSettings = TestFixtures.graphSettings
    def run(q: GraphQuery): Either[String, GraphResult] = runClassified(q).left.map(_.message)
    override def runClassified(q: GraphQuery): Either[GraphReadError, GraphResult] = answer(q)
  }

  /** Fake Neptune data client: `answer(request)` gives the rows or throws. */
  final class FakeNeptune(answer: ExecuteOpenCypherQueryRequest => List[Map[String, String]])
      extends NeptunedataClient {
    def serviceName(): String = "neptune-db"
    def close(): Unit = ()
    override def executeOpenCypherQuery(
        r: ExecuteOpenCypherQueryRequest
    ): ExecuteOpenCypherQueryResponse = {
      val docs = answer(r).map { m =>
        Document.fromMap(m.map { case (k, v) => k -> Document.fromString(v) }.asJava)
      }
      ExecuteOpenCypherQueryResponse.builder().results(Document.fromList(docs.asJava)).build()
    }
  }
}
