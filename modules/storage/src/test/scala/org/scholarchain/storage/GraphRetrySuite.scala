package org.scholarchain.storage

import java.util.concurrent.{ConcurrentHashMap, CountDownLatch, Executors, TimeUnit}
import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}

import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.jdk.CollectionConverters._

import org.scholarchain.core.{MentionId, ResearcherId, WorkId}
import org.scholarchain.storage.aws._
import org.scholarchain.storage.local.InMemoryGraph
import org.scholarchain.storage.ports._
import software.amazon.awssdk.core.document.Document
import software.amazon.awssdk.core.exception.ApiCallTimeoutException
import software.amazon.awssdk.services.neptunedata.NeptunedataClient
import software.amazon.awssdk.services.neptunedata.model.{
  ExecuteOpenCypherQueryRequest,
  ExecuteOpenCypherQueryResponse,
  InternalFailureException,
  MalformedQueryException,
  MemoryLimitExceededException,
  QueryLimitExceededException
}

/** Phase 16, FECD-1 (recommendation S2: bounded graph-read retry) and L1 (resource limits are a
  * bounded search, with a typed error class), with fake Neptune clients and concurrent callers. No
  * network, no AWS.
  */
class GraphRetrySuite extends munit.FunSuite {
  import TestFixtures._

  /** Fake Neptune: `answer(cypher, researcherIdParameter, attemptOfThatKey)` gives the rows or
    * throws. Thread-safe: concurrent callers each have their own attempt counter by key.
    */
  private final class FakeNeptune(
      answer: (String, String, Int) => List[Map[String, String]],
      onCall: () => Unit = () => ()
  ) extends NeptunedataClient {
    val calls = new AtomicInteger()
    val timeouts = new java.util.concurrent.ConcurrentLinkedQueue[java.lang.Long]()
    private val attempts = new ConcurrentHashMap[String, AtomicInteger]()
    def serviceName(): String = "neptune-db"
    def close(): Unit = ()
    override def executeOpenCypherQuery(
        r: ExecuteOpenCypherQueryRequest
    ): ExecuteOpenCypherQueryResponse = {
      calls.incrementAndGet()
      timeouts.add(r.overrideConfiguration().get().apiCallTimeout().get().toMillis)
      onCall()
      val p = ujson.read(r.parameters())
      val key = p.obj.get("researcherId").orElse(p.obj.get("from")).map(_.str).getOrElse("") +
        "|" + depthOf(r.openCypherQuery())
      val n = attempts.computeIfAbsent(key, _ => new AtomicInteger()).incrementAndGet()
      val docs = answer(r.openCypherQuery(), key, n).map { m =>
        Document.fromMap(m.map { case (k, v) => k -> Document.fromString(v) }.asJava)
      }
      ExecuteOpenCypherQueryResponse.builder().results(Document.fromList(docs.asJava)).build()
    }
  }

  private def depthOf(cypher: String): Int =
    """\*(\d+)\.\.""".r.findFirstMatchIn(cypher).map(_.group(1).toInt).getOrElse(0)

  private def researcher(i: Int): ResearcherId =
    ResearcherId.fromAnchorMention(MentionId.of(WorkId.fromDblpKey(s"conf/retry/G$i"), 0))

  private def internal500 =
    InternalFailureException.builder().message("internal failure").statusCode(500).build()
  private def memoryLimit =
    MemoryLimitExceededException.builder().message("out of memory").statusCode(500).build()
  private def queryLimit =
    QueryLimitExceededException.builder().message("too many queries").statusCode(500).build()
  private def malformed =
    MalformedQueryException.builder().message("bad query").statusCode(400).build()

  /** Records each sleep; optionally advances a fake clock by the slept time. */
  private final class RecordingSleeper(clock: Option[AtomicLong] = None) extends Sleeper {
    val slept = new java.util.concurrent.ConcurrentLinkedQueue[java.lang.Long]()
    def sleep(millis: Long): Unit = { slept.add(millis); clock.foreach(_.addAndGet(millis)); () }
  }

  private val half: () => Double = () => 0.5

  private def withPool[A](n: Int)(f: ExecutionContext => A): A = {
    val pool = Executors.newFixedThreadPool(n)
    try f(ExecutionContext.fromExecutor(pool))
    finally pool.shutdown()
  }

  // ---- the retry policy ----------------------------------------------------------------

  test("GraphRetry: equal-jitter delays, capped, and the default stays far inside the budget") {
    val d = GraphRetry.Default
    assertEquals(
      (d.maxRetries, d.baseDelayMillis, d.maxDelayMillis, d.minAttemptMillis),
      (2, 200L, 800L, 250L)
    )
    assertEquals(d.delayBeforeRetry(1, 0.0), 100L)
    assertEquals(d.delayBeforeRetry(1, 0.5), 150L)
    assert(d.delayBeforeRetry(1, 1.0) <= 200L)
    assertEquals(d.delayBeforeRetry(2, 0.0), 200L)
    assert(d.delayBeforeRetry(2, 0.999) <= 400L)
    assertEquals(d.delayBeforeRetry(9, 0.0), 400L, "capped at maxDelayMillis / 2 + jitter")
    assert(d.delayBeforeRetry(9, 0.999) <= 800L)
    assertEquals(d.delayBeforeRetry(1, Double.NaN), 100L)
    assertEquals(GraphRetry.Off.maxRetries, 0)
    intercept[IllegalArgumentException](GraphRetry(4, 1, 1, 1))
    intercept[IllegalArgumentException](GraphRetry(1, 10, 5, 1))
  }

  test(
    "the AWS environment's graph port retries with GraphRetry.Default; the class default does not"
  ) {
    val env = AwsStorageEnvironment.create(
      runtimeStorageConfig,
      AwsSdkClients(null, null, null, Some(new FakeNeptune((_, _, _) => Nil))),
      None,
      Sleeper.none
    )
    assertEquals(env.graph.toOption.map(_.retry), Some(GraphRetry.Default))
    assertEquals(env.graphReadReport.map(_("calls").num), Some(0.0))
    assertEquals(
      new NeptuneGraphRead(new FakeNeptune((_, _, _) => Nil), graphSettings).retry,
      GraphRetry.Off
    )
  }

  // ---- FECD-1: transient failures under concurrent callers ------------------------------

  test("FECD-1: without the retry, one transient 500 is one failed read (the D-read behavior)") {
    val fake = new FakeNeptune((_, _, n) => if (n == 1) throw internal500 else Nil)
    val g = new NeptuneGraphRead(fake, graphSettings)
    val r = g.runClassified(GraphQuery.CoauthorNeighbors(researcher(1), Full, 5))
    assertEquals(r.left.map(_.code), Left("graph-transient"))
    assertEquals(fake.calls.get, 1)
  }

  test(
    "FECD-1: 8 concurrent callers each hit one transient 500 while all are in flight; every read recovers"
  ) {
    val callers = 8
    val allInFlight = new CountDownLatch(callers)
    val fake = new FakeNeptune((_, _, n) => {
      if (n == 1) {
        allInFlight.countDown()
        allInFlight.await(10, TimeUnit.SECONDS)
        throw internal500
      }
      List(Map("coauthorId" -> "c1"))
    })
    val sleeper = new RecordingSleeper()
    val g = new NeptuneGraphRead(
      fake,
      graphSettings,
      retry = GraphRetry.Default,
      sleeper = sleeper,
      jitter = half
    )
    val outs = withPool(callers) { implicit ec =>
      Await.result(
        Future.sequence((1 to callers).toList.map { i =>
          Future(g.runClassified(GraphQuery.CoauthorNeighbors(researcher(i), Full, 5)))
        }),
        30.seconds
      )
    }
    assert(outs.forall(_.exists(_.rows.size == 1)), outs.toString)
    assertEquals(allInFlight.getCount, 0L, "all first attempts were in flight together")
    assertEquals(fake.calls.get, 2 * callers)
    assertEquals(
      (
        g.counters.calls.get,
        g.counters.retries.get,
        g.counters.recovered.get,
        g.counters.gaveUp.get
      ),
      (16L, 8L, 8L, 0L)
    )
    assertEquals(g.counters.transientFailures.get, 8L)
    assertEquals(sleeper.slept.asScala.toList.map(_.longValue).distinct, List(150L))
  }

  test(
    "FECD-1: a Neptune that keeps failing gets at most maxRetries + 1 calls per read, under concurrency"
  ) {
    val callers = 6
    val fake = new FakeNeptune((_, _, _) => throw internal500)
    val sleeper = new RecordingSleeper()
    val g = new NeptuneGraphRead(
      fake,
      graphSettings,
      retry = GraphRetry.Default,
      sleeper = sleeper,
      jitter = half
    )
    val outs = withPool(callers) { implicit ec =>
      Await.result(
        Future.sequence((1 to callers).toList.map { i =>
          Future(g.runClassified(GraphQuery.ResearcherById(researcher(i))))
        }),
        30.seconds
      )
    }
    assert(outs.forall(_.left.exists(_.isInstanceOf[GraphReadError.Transient])), outs.toString)
    assertEquals(fake.calls.get, callers * 3)
    assertEquals(
      (g.counters.retries.get, g.counters.gaveUp.get, g.counters.recovered.get),
      (12L, 6L, 0L)
    )
    assertEquals(sleeper.slept.asScala.toList.map(_.longValue).sorted.distinct, List(150L, 300L))
    // run() keeps the pre-Phase 16 contract: the store's message as the Left
    val left = g.run(GraphQuery.ResearcherById(researcher(99)))
    assert(left.left.exists(_.contains("InternalFailureException")), left.toString)
  }

  test("FECD-1: 4xx and timeouts are never retried") {
    val bad = new FakeNeptune((_, _, _) => throw malformed)
    val g =
      new NeptuneGraphRead(bad, graphSettings, retry = GraphRetry.Default, sleeper = Sleeper.none)
    assertEquals(
      g.runClassified(GraphQuery.ResearcherById(researcher(1))).left.map(_.code),
      Left("graph-permanent")
    )
    assertEquals(bad.calls.get, 1)
    val slow =
      new FakeNeptune((_, _, _) => throw ApiCallTimeoutException.builder().message("slow").build())
    val t =
      new NeptuneGraphRead(slow, graphSettings, retry = GraphRetry.Default, sleeper = Sleeper.none)
    assertEquals(
      t.runClassified(GraphQuery.ResearcherById(researcher(1))).left.map(_.code),
      Left("graph-timeout")
    )
    assertEquals(slow.calls.get, 1)
    assertEquals(g.counters.gaveUp.get + t.counters.gaveUp.get, 0L)
  }

  test("FECD-1: every retry stays inside the read's time budget (fake clock)") {
    assertEquals(graphSettings.timeoutMillis, 1000)
    // first attempt takes 100 ms and fails; delay 150 ms; the retry's API timeout is 1000-100-150
    val now = new AtomicLong(0L)
    val fake = new FakeNeptune(
      (_, _, n) => if (n == 1) throw internal500 else Nil,
      onCall = () => { now.addAndGet(100L); () }
    )
    val sleeper = new RecordingSleeper(Some(now))
    val g =
      new NeptuneGraphRead(fake, graphSettings, () => now.get, GraphRetry.Default, sleeper, half)
    assert(g.runClassified(GraphQuery.ResearcherById(researcher(1))).isRight)
    assertEquals(fake.timeouts.asScala.toList.map(_.longValue), List(1000L, 750L))
    // an attempt that used 800 ms of the 1000: 1000-800-150 = 50 < 250, so no retry
    val now2 = new AtomicLong(0L)
    val slowFail = new FakeNeptune(
      (_, _, _) => throw internal500,
      onCall = () => { now2.addAndGet(800L); () }
    )
    val g2 = new NeptuneGraphRead(
      slowFail,
      graphSettings,
      () => now2.get,
      GraphRetry.Default,
      new RecordingSleeper(Some(now2)),
      half
    )
    assertEquals(
      g2.runClassified(GraphQuery.ResearcherById(researcher(1))).left.map(_.code),
      Left("graph-transient")
    )
    assertEquals((slowFail.calls.get, g2.counters.gaveUp.get), (1, 1L))
  }

  test(
    "FECD-1: path search retries a transient depth failure with the budget left and finds the path"
  ) {
    val now = new AtomicLong(0L)
    val fake = new FakeNeptune(
      (c, _, n) =>
        if (depthOf(c) == 2 && n == 1) throw internal500
        else if (depthOf(c) == 2) List(Map("researcherIds" -> "x", "hops" -> "2"))
        else Nil,
      onCall = () => { now.addAndGet(50L); () }
    )
    val g = new NeptuneGraphRead(
      fake,
      graphSettings,
      () => now.get,
      GraphRetry.Default,
      new RecordingSleeper(Some(now)),
      half
    )
    val r =
      g.runClassified(GraphQuery.PathBetween(researcher(1), researcher(2), Full, 4)).toOption.get
    assertEquals((r.complete, r.depthSearched, r.rows.size, r.stoppedBy), (true, Some(2), 1, None))
    // depth 1 at 1000; depth 2 at 1000-50; its retry at 1000-50-50-150
    assertEquals(fake.timeouts.asScala.toList.map(_.longValue), List(1000L, 950L, 750L))
  }

  // ---- L1: Neptune resource limits ------------------------------------------------------

  for (
    (name, err) <- List[(String, () => Throwable)](
      "MemoryLimitExceededException" -> (() => memoryLimit),
      "QueryLimitExceededException" -> (() => queryLimit)
    )
  ) {
    test(
      s"L1: $name on a path depth is a bounded search (existence unknown), not a store failure"
    ) {
      val fake = new FakeNeptune((c, _, _) => if (depthOf(c) >= 2) throw err() else Nil)
      val g = new NeptuneGraphRead(fake, graphSettings)
      val r = g.runClassified(GraphQuery.PathBetween(researcher(1), researcher(2), Full, 4))
      val res = r.fold(e => fail(s"expected a bounded result, got $e"), identity)
      assertEquals(
        (res.rows, res.complete, res.depthSearched, res.stoppedBy),
        (Nil, false, Some(1), Some(GraphStop.ResourceLimit))
      )
      assertEquals(fake.calls.get, 2)
      assertEquals(
        g.run(GraphQuery.PathBetween(researcher(3), researcher(4), Full, 4)).map(_.complete),
        Right(false)
      )
    }

    test(s"L1: $name on a single-shot template is the resource-limit error class") {
      val fake = new FakeNeptune((_, _, _) => throw err())
      val g = new NeptuneGraphRead(fake, graphSettings)
      val r = g.runClassified(GraphQuery.CoauthorNeighbors(researcher(1), Full, 5))
      assertEquals(r.left.map(_.code), Left("graph-resource-limit"))
      assert(r.left.exists(_.isInstanceOf[GraphReadError.ResourceLimit]))
      assert(
        g.run(GraphQuery.CoauthorNeighbors(researcher(1), Full, 5)).left.exists(_.contains(name))
      )
    }

    test(s"L1: $name is retried within the budget before the search is reported bounded") {
      val fake = new FakeNeptune((c, _, _) => if (depthOf(c) >= 2) throw err() else Nil)
      val g = new NeptuneGraphRead(
        fake,
        graphSettings,
        retry = GraphRetry.Default,
        sleeper = Sleeper.none,
        jitter = half
      )
      val r =
        g.runClassified(GraphQuery.PathBetween(researcher(1), researcher(2), Full, 4)).toOption.get
      assertEquals(r.stoppedBy, Some(GraphStop.ResourceLimit))
      assertEquals(fake.calls.get, 1 + 3, "depth 1 once, depth 2 three times")
      assertEquals((g.counters.resourceLimitFailures.get, g.counters.gaveUp.get), (3L, 1L))
    }
  }

  test("L1: any other 500 on a path depth stays a store failure (transient), never an absence") {
    val fake = new FakeNeptune((c, _, _) => if (depthOf(c) >= 2) throw internal500 else Nil)
    val r = new NeptuneGraphRead(fake, graphSettings)
      .runClassified(GraphQuery.PathBetween(researcher(1), researcher(2), Full, 4))
    assertEquals(r.left.map(_.code), Left("graph-transient"))
  }

  test("L1: a time-limit stop says so; a complete search has no stop reason") {
    var t = 0L
    val clock = () => { val now = t; t += 600; now }
    val g = new NeptuneGraphRead(new FakeNeptune((_, _, _) => Nil), graphSettings, clock)
    val r =
      g.runClassified(GraphQuery.PathBetween(researcher(1), researcher(2), Full, 4)).toOption.get
    assertEquals((r.complete, r.stoppedBy), (false, Some(GraphStop.TimeLimit)))
    val done = new NeptuneGraphRead(new FakeNeptune((_, _, _) => Nil), graphSettings)
      .runClassified(GraphQuery.PathBetween(researcher(1), researcher(2), Full, 2))
      .toOption
      .get
    assertEquals((done.complete, done.stoppedBy), (true, None))
  }

  // ---- the typed error class -------------------------------------------------------------

  test("the error and stop codes are a fixed allowlist; refused queries are Invalid") {
    assertEquals(
      GraphReadError.Codes,
      List(
        "graph-invalid-query",
        "graph-transient",
        "graph-resource-limit",
        "graph-timeout",
        "graph-permanent",
        "graph-unclassified"
      )
    )
    assertEquals(GraphStop.Codes, List("time-limit", "resource-limit"))
    val all = List(
      GraphReadError.Invalid("m"),
      GraphReadError.Transient("m"),
      GraphReadError.ResourceLimit("m"),
      GraphReadError.Timeout("m"),
      GraphReadError.Permanent("m"),
      GraphReadError.Unclassified("m")
    )
    assertEquals(all.map(_.code), GraphReadError.Codes)
    val fake = new FakeNeptune((_, _, _) => Nil)
    val g = new NeptuneGraphRead(fake, graphSettings)
    val bad =
      g.runClassified(GraphQuery.ResearcherById(ResearcherId("researcher:v1:' RETURN 1 //")))
    assertEquals(bad.left.map(_.code), Left("graph-invalid-query"))
    assertEquals(fake.calls.get, 0)
    val mem = new InMemoryGraph(10, graphSettings)
    assertEquals(
      mem
        .runClassified(GraphQuery.PathBetween(researcher(1), researcher(1), Full, 2))
        .left
        .map(_.code),
      Left("graph-invalid-query")
    )
  }

  test("a port that only implements run reports Unclassified through runClassified") {
    val legacy = new GraphReadPort {
      val settings: GraphReadSettings = graphSettings
      def run(q: GraphQuery): Either[String, GraphResult] = Left("boom")
    }
    assertEquals(
      legacy.runClassified(GraphQuery.ResearcherById(researcher(1))),
      Left(GraphReadError.Unclassified("boom"))
    )
  }

  test("run and runClassified agree on every outcome class") {
    val cases = List[(String, (String, String, Int) => List[Map[String, String]])](
      "ok" -> ((_, _, _) => List(Map("researcherId" -> "x"))),
      "transient" -> ((_, _, _) => throw internal500),
      "memory" -> ((_, _, _) => throw memoryLimit),
      "permanent" -> ((_, _, _) => throw malformed)
    )
    val seen = mutable.ListBuffer.empty[String]
    cases.foreach { case (label, f) =>
      val g = new NeptuneGraphRead(new FakeNeptune(f), graphSettings)
      val q = GraphQuery.ResearcherById(researcher(1))
      assertEquals(g.run(q), g.runClassified(q).left.map(_.message), label)
      seen += label
    }
    assertEquals(seen.size, 4)
  }
}
