package org.scholarchain.services

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

import org.scholarchain.storage.jobs.{JobMode, ManualClock}

/** Unit tests of the building blocks: JSON bounds, request parsing and fingerprints, circuit
  * breaker, token bucket, ID headers, path and query parsing, and the thin-route rule.
  */
class ComponentsSuite extends munit.FunSuite {
  import ServiceTestKit._

  private val cfg = config
  private val parser =
    new QueryRequestParser(cfg.graph.windows, cfg.runtime.get, cfg.vectors.get.queryTopK)
  private def parse(s: String): ValidatedQuery =
    parser.parse(s).fold(e => fail(e.toString), identity)

  test("Json: sorted keys, two-space indent, depth pre-scan ignores brackets inside strings") {
    assertEquals(
      Json.render(ujson.Obj("b" -> 1, "a" -> ujson.Obj("d" -> 1, "c" -> 2))),
      "{\n  \"a\": {\n    \"c\": 2,\n    \"d\": 1\n  },\n  \"b\": 1\n}"
    )
    assertEquals(Json.canonical(ujson.Obj("b" -> 1, "a" -> 2)), "{\"a\":2,\"b\":1}")
    assert(Json.depthWithin("{\"a\": \"[[[[[[[[[[\"}", 2))
    assert(Json.depthWithin("[[[]]]", 3))
    assert(!Json.depthWithin("[[[[]]]]", 3))
    assert(Json.depthWithin("{\"a\": \"\\\"[[[[\"}", 1))
  }

  test(
    "fingerprint: defaults applied, canonical question, mode and every request object included"
  ) {
    val base = parse(queryBody())
    assertEquals(
      parse(queryBody(extra = List("output" -> ujson.Obj("includeEvidence" -> true)))).fingerprint,
      base.fingerprint
    )
    assertEquals(
      parse(
        queryBody(question = "  How many distinct coauthors does   Carol King have in 2015-2025? ")
      ).fingerprint,
      base.fingerprint
    )
    assertEquals(
      parse(
        ujson
          .Obj(
            "question" -> base.question,
            "constraints" -> ujson.Obj("yearFrom" -> 2015, "yearTo" -> 2025)
          )
          .render()
      ).fingerprint,
      base.fingerprint
    )
    assertNotEquals(parse(queryBody(mode = "async")).fingerprint, base.fingerprint)
    assertNotEquals(
      parse(queryBody(extra = List("output" -> ujson.Obj("includeEvidence" -> false)))).fingerprint,
      base.fingerprint
    )
    assertNotEquals(
      parse(queryBody(extra = List("retrieval" -> ujson.Obj("graphLimit" -> 10)))).fingerprint,
      base.fingerprint
    )
    assertNotEquals(
      parse(queryBody(extra = List("analysis" -> ujson.Obj("includePaths" -> true)))).fingerprint,
      base.fingerprint
    )
    assertNotEquals(parse(queryBody(years = None)).fingerprint, base.fingerprint)
    assert(base.fingerprint.matches("sha256:[0-9a-f]{64}"))
    val topic = parse(
      queryBody(extra =
        List("constraints" -> ujson.Obj("topics" -> ujson.Arr("  graph   mining ")))
      )
    )
    assertEquals(topic.topic, Some("graph mining"))
    assertEquals(topic.windowLabel, None)
    assertEquals(topic.mode, JobMode.Sync)
    // only window label and topic reach rag
    assertEquals(topic.toQueryRequest.topic, Some("graph mining"))
    assertEquals(base.toQueryRequest.windowLabel, Some("full-2015-2025"))
  }

  test(
    "circuit breaker: opens after the threshold, one trial after the open period, cancel frees it"
  ) {
    val clock = new ManualClock()
    val b = new CircuitBreaker("x", 2, 1000L, clock)
    assertEquals(b.call(Left("e")), Left(Some("e")))
    assertEquals(b.state, "closed")
    assertEquals(b.call(Right(1)), Right(1)) // success resets the count
    b.call(Left("e")); b.call(Left("e"))
    assertEquals(b.state, "open")
    assertEquals(b.call(Right(1)), Left(None))
    clock.advance(1000L)
    val trial = b.acquire()
    assertEquals(trial, Some(CircuitBreaker.Slot(trial = true)))
    assert(!b.tryAcquire(), "only one trial at a time")
    b.cancel(trial.get)
    assert(b.tryAcquire())
    b.recordSuccess()
    assertEquals(b.state, "closed")
  }

  test("S-9 circuit breaker: a slot taken while closed never frees another request's trial") {
    val clock = new ManualClock()
    val b = new CircuitBreaker("x", 1, 1000L, clock)
    val early = b.acquire() // admitted while closed
    assertEquals(early, Some(CircuitBreaker.Slot(trial = false)))
    b.recordFailure() // another call fails: open
    clock.advance(1000L) // half-open
    assert(b.acquire().exists(_.trial), "the trial slot")
    b.cancel(early.get) // the early request gives its slot back (for example a lost claim)
    // the trial is still in flight: no second trial may start
    assertEquals(b.acquire(), None)
  }

  test("token bucket: capacity per minute, continuous refill, Retry-After in whole seconds") {
    val clock = new ManualClock()
    val t = new TokenBucket(60, clock)
    (1 to 60).foreach(_ => assertEquals(t.take(), Right(())))
    assertEquals(t.take(), Left(1))
    clock.advance(1000L)
    assertEquals(t.take(), Right(()))
    assertEquals(t.take(), Left(1))
    clock.advance(3600000L)
    (1 to 60).foreach(_ => assertEquals(t.take(), Right(())))
    assert(t.take().isLeft)
  }

  test("request IDs, trace parents and idempotency keys") {
    assertEquals(RequestIds.validRequestId("a.b_c-1"), Some("a.b_c-1"))
    assertEquals(RequestIds.validRequestId("x" * 65), None)
    assertEquals(RequestIds.validRequestId(""), None)
    assertEquals(RequestIds.traceIdOf(s"00-${"ab" * 16}-${"cd" * 8}-01"), Some("ab" * 16))
    assertEquals(RequestIds.traceIdOf(s"ff-${"ab" * 16}-${"cd" * 8}-01"), None)
    assertEquals(RequestIds.traceIdOf(s"00-${"AB" * 16}-${"cd" * 8}-01"), None)
    assertEquals(RequestIds.traceIdOf(s"00-${"ab" * 16}-${"0" * 16}-01"), None)
    assertEquals(RequestIds.traceIdOf(null), None)
    assert(RequestIds.validIdempotencyKey("a:b.c_d-e"))
    assert(!RequestIds.validIdempotencyKey("x" * 129))
    assert(!RequestIds.validIdempotencyKey("a b"))
  }

  test("path segments, query parameters and media types") {
    assertEquals(HttpAdapter.pathSegments("/v1/jobs/job-1"), Some(List("v1", "jobs", "job-1")))
    assertEquals(HttpAdapter.pathSegments("/"), Some(Nil))
    assertEquals(HttpAdapter.pathSegments("/v1//x"), None)
    assertEquals(HttpAdapter.pathSegments("/v1/x/"), None)
    assertEquals(HttpAdapter.pathSegments("/v1/a%3Ab"), Some(List("v1", "a:b")))
    assertEquals(HttpAdapter.pathSegments("/v1/a+b"), Some(List("v1", "a+b")))
    assertEquals(HttpAdapter.pathSegments("/v1/%zz"), None)
    assertEquals(HttpAdapter.queryParams("a=1&b=x%20y"), Right(Map("a" -> "1", "b" -> "x y")))
    assert(HttpAdapter.queryParams("a=1&a=2").isLeft)
    assert(HttpAdapter.queryParams("=1").isLeft)
    assert(HttpAdapter.isJsonMediaType("application/json"))
    assert(HttpAdapter.isJsonMediaType("Application/JSON; charset=UTF-8"))
    assert(!HttpAdapter.isJsonMediaType("application/json; charset=latin1"))
    assert(!HttpAdapter.isJsonMediaType("text/json"))
  }

  test(
    "error model: every code has a status, a fixed message, and the spec's error classes are covered"
  ) {
    val statuses = ErrorCode.all.map(_.status).toSet
    assertEquals(
      statuses,
      Set(400, 401, 403, 404, 405, 409, 413, 415, 422, 429, 500, 502, 503, 504)
    )
    assert(ErrorCode.all.forall(_.message.nonEmpty))
    assertEquals(ErrorCode.all.map(_.name).distinct.size, ErrorCode.all.size)
    assertEquals(
      JobErrorCodes.messages.keySet,
      Set(
        "SUPERSEDED",
        "LEASE_EXPIRED",
        "ATTEMPTS_EXHAUSTED",
        "RESULT_TOO_LARGE",
        "WORKFLOW_FAILED"
      )
    )
  }

  test("thin routes: route and adapter files import no storage adapter package and no rag stage") {
    // S-6: found from the repository root or from the module folder; never skipped silently
    val rel = "src/main/scala/org/scholarchain/services"
    val dir = List(Paths.get("modules/services").resolve(rel), Paths.get(rel))
      .find(p => Files.isDirectory(p))
      .getOrElse(fail(s"services sources not found from ${Paths.get("").toAbsolutePath}"))
    List("Routes.scala", "HttpAdapter.scala").foreach { f =>
      val text = new String(Files.readAllBytes(dir.resolve(f)), StandardCharsets.UTF_8)
      val imports = text.linesIterator.filter(_.trim.startsWith("import ")).toList
      val banned = List(
        "storage.aws",
        "storage.dynamo",
        "storage.neptune",
        "storage.local",
        "rag.runtime",
        "rag.retrieval",
        "rag.embedding",
        "storage."
      )
      imports.foreach(i => banned.foreach(b => assert(!i.contains(b), s"$f: $i")))
    }
  }
}
