package org.scholarchain.services

import java.net.URI
import java.net.http.{HttpRequest, HttpResponse}
import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}

import scala.jdk.CollectionConverters._

import org.scholarchain.rag.runtime.RuntimeFixtures

/** Authentication, health and readiness, rate limit, roles, IDs, error bodies, logs and shutdown
  * (decision 7i).
  */
class HttpBehaviorSuite extends munit.FunSuite {
  import ServiceTestKit._

  override val munitTimeout = scala.concurrent.duration.Duration(180, "s")

  private val h = FunFixture[Harness](_ => harness(), _.close())

  /** Strings no health, readiness, error body or log line may hold. */
  private def forbidden(hh: Harness): List[String] = List(
    FullKey,
    ReadOnlyKey,
    "SCHOLARCHAIN_EXAMPLE_API_KEY",
    "SCHOLARCHAIN_EXAMPLE_READONLY_API_KEY",
    "example-",
    "neptune",
    "us-east-1",
    "123456789012",
    "injected",
    "queue.example"
  )

  h.test("unauthorized: no key or a wrong key is 401; forbidden: the read-only key cannot POST") {
    h =>
      val none = post(h, queryBody(), key = None)
      assertEquals(none.status, 401)
      assertEquals(none.error, "UNAUTHENTICATED")
      val wrong =
        get(h, s"/v1/graph/nodes/${RuntimeFixtures.rC.value}", key = Some("placeholder-wrong"))
      assertEquals(wrong.status, 401)
      val prefix = get(h, s"/v1/graph/nodes/${RuntimeFixtures.rC.value}", key = Some(FullKey + "x"))
      assertEquals(prefix.status, 401)
      val ro = post(h, queryBody(), key = Some(ReadOnlyKey))
      assertEquals(ro.status, 403)
      assertEquals(ro.error, "FORBIDDEN")
      assertEquals(
        get(h, s"/v1/graph/nodes/${RuntimeFixtures.rC.value}", key = Some(ReadOnlyKey)).status,
        200
      )
      // unknown routes need a key too
      assertEquals(get(h, "/v1/unknown", key = None).status, 401)
      val lines = h.log.all.filter(_("event").str == "request")
      assertEquals(
        lines.map(_("callerClass").str).take(5),
        List("anonymous", "anonymous", "anonymous", "read-only", "read-only")
      )
  }

  h.test("health versus readiness: a failing probe gives /readyz 503 while /healthz stays 200") {
    h =>
      val ok = get(h, "/readyz", key = None)
      assertEquals(ok.status, 200, ok.body)
      assertEquals(ok.json("status").str, "ready")
      assertEquals(ok.json("checks").obj.keySet.toSet, Set("graph", "metrics", "jobs"))
      h.flaky.failGraph.set(true)
      h.clock.advance(ServiceLimits.ReadinessCacheMillis)
      val bad = get(h, "/readyz", key = None)
      assertEquals(bad.status, 503)
      assertEquals(bad.json("status").str, "not_ready")
      assertEquals(bad.json("checks")("graph").str, "fail")
      assertEquals(bad.json("checks")("metrics").str, "ok")
      assert(bad.json.obj.get("error").isEmpty) // the one exception to the error body
      val live = get(h, "/healthz", key = None)
      assertEquals(live.status, 200)
      assertEquals(live.json, ujson.Obj("role" -> "all", "status" -> "ok"))
      for (body <- List(ok.body, bad.body, live.body); s <- forbidden(h))
        assert(!body.contains(s), s"$s in $body")
  }

  h.test("readiness caches its result for 5 seconds; concurrent /readyz calls run one probe") { h =>
    h.slow.graphDelayMillis.set(500L)
    val pool = Executors.newFixedThreadPool(5)
    try {
      val start = new CountDownLatch(1)
      val fs = (1 to 5).map(_ =>
        pool.submit(() => { start.await(); get(h, "/readyz", key = None).status })
      )
      start.countDown()
      assertEquals(fs.map(_.get(30, TimeUnit.SECONDS)).toSet, Set(200))
      assertEquals(h.server.app.health.probeCount, 1)
      get(h, "/readyz", key = None)
      assertEquals(h.server.app.health.probeCount, 1)
      h.clock.advance(ServiceLimits.ReadinessCacheMillis)
      get(h, "/readyz", key = None)
      assertEquals(h.server.app.health.probeCount, 2)
    } finally pool.shutdownNow()
  }

  h.test("readiness: a probe slower than 2 seconds marks that check fail") { h =>
    h.slow.graphDelayMillis.set(ServiceLimits.ReadinessDeadlineMillis + 1500L)
    val t0 = System.nanoTime()
    val r = get(h, "/readyz", key = None)
    val ms = (System.nanoTime() - t0) / 1000000L
    assertEquals(r.status, 503, r.body)
    assertEquals(r.json("checks")("graph").str, "fail")
    assertEquals(r.json("checks")("metrics").str, "ok")
    assert(ms < ServiceLimits.ReadinessDeadlineMillis + 1000L, s"$ms ms")
    h.slow.graphDelayMillis.set(0L)
  }

  h.test(
    "G-23 readiness: a failed check logs its reason class (graph code, deadline), never the store text"
  ) { h =>
    def failures(): List[ujson.Obj] =
      h.log.all.filter(_("event").str == "readiness-check-failed")
    h.flaky.failGraph.set(true) // a thrown transient StoreException
    assertEquals(get(h, "/readyz", key = None).status, 503)
    assertEquals(
      failures().map(l => (l("check").str, l("reason").str)),
      List("graph" -> "graph-transient")
    )
    h.flaky.failGraph.set(false)
    h.slow.graphError.set(
      Some(org.scholarchain.storage.ports.GraphReadError.Permanent("AccessDenied neptune host"))
    )
    h.clock.advance(ServiceLimits.ReadinessCacheMillis)
    assertEquals(get(h, "/readyz", key = None).status, 503)
    assertEquals(failures().last("reason").str, "graph-permanent")
    h.slow.graphError.set(None)
    h.slow.graphDelayMillis.set(ServiceLimits.ReadinessDeadlineMillis + 1000L)
    h.clock.advance(ServiceLimits.ReadinessCacheMillis)
    assertEquals(get(h, "/readyz", key = None).status, 503)
    assertEquals(failures().last("reason").str, HealthService.DeadlinePassed)
    h.slow.graphDelayMillis.set(0L)
    h.clock.advance(ServiceLimits.ReadinessCacheMillis)
    assertEquals(get(h, "/readyz", key = None).status, 200)
    assertEquals(failures().size, 3) // a passing probe logs nothing
    assert(
      failures().forall(
        _.obj.keySet.toSet.subsetOf(Set("time", "role", "event", "check", "reason"))
      )
    )
    for (line <- h.log.rendered; s <- forbidden(h)) assert(!line.contains(s), s"$s in $line")
    assertEquals(HealthService.reasonOf("connect timed out to host.example"), "store-failure")
    assertEquals(HealthService.reasonOf("graph-timeout"), "graph-timeout")
  }

  test(
    "rate limit: requests over requestsPerMinute get 429 with Retry-After; health is never limited"
  ) {
    val hh = harness(cfg = withService(config)(_.copy(requestsPerMinute = 3)))
    try {
      val path = s"/v1/graph/nodes/${RuntimeFixtures.rC.value}"
      (1 to 3).foreach(_ => assertEquals(get(hh, path).status, 200))
      val limited = get(hh, path)
      assertEquals(limited.status, 429)
      assertEquals(limited.error, "RATE_LIMITED")
      assertEquals(limited.json("retryable").bool, true)
      assertEquals(limited.header("Retry-After"), Some("20"))
      (1 to 10).foreach { _ =>
        assertEquals(get(hh, "/healthz", key = None).status, 200)
        assert(Set(200, 503).contains(get(hh, "/readyz", key = None).status))
      }
      hh.clock.advance(20000L)
      assertEquals(get(hh, path).status, 200)
      assertEquals(get(hh, path).status, 429)
    } finally hh.close()
  }

  test("rate limit: every authenticated request takes a token, including 404 and 405 answers") {
    val hh = harness(cfg = withService(config)(_.copy(requestsPerMinute = 3)))
    try {
      assertEquals(get(hh, "/v1/unknown").status, 404)
      assertEquals(get(hh, "/v1/query").status, 405)
      assertEquals(get(hh, s"/v1/graph/nodes/${RuntimeFixtures.rC.value}").status, 200)
      assertEquals(get(hh, "/v1/unknown").status, 429)
      assertEquals(
        get(hh, "/v1/unknown", key = None).status,
        401
      ) // anonymous: refused before the bucket
    } finally hh.close()
  }

  test("a route of another role is 404; every role serves /healthz and /readyz") {
    val graph = harness(role = Role.Graph)
    try {
      assertEquals(post(graph, queryBody()).error, "ROUTE_NOT_FOUND")
      assertEquals(get(graph, s"/v1/evidence/evidence:v1:${"0" * 32}").error, "ROUTE_NOT_FOUND")
      assertEquals(get(graph, "/v1/jobs/job-1").error, "ROUTE_NOT_FOUND")
      assertEquals(get(graph, s"/v1/graph/nodes/${RuntimeFixtures.rC.value}").status, 200)
      assertEquals(get(graph, "/healthz", key = None).json("role").str, "graph")
      assertEquals(
        get(graph, "/readyz", key = None).json("checks").obj.keySet.toSet,
        Set("graph", "metrics")
      )
    } finally graph.close()
    val ev = harness(role = Role.Evidence)
    try {
      assertEquals(get(ev, s"/v1/graph/nodes/${RuntimeFixtures.rC.value}").error, "ROUTE_NOT_FOUND")
      assertEquals(get(ev, s"/v1/explain/run:v1:${"0" * 32}").error, "QUERY_NOT_FOUND")
    } finally ev.close()
    val q = harness(role = Role.Query)
    try {
      assertEquals(post(q, queryBody()).status, 200)
      assertEquals(get(q, s"/v1/explain/run:v1:${"0" * 32}").error, "ROUTE_NOT_FOUND")
    } finally q.close()
    val w = harness(role = Role.Worker)
    try {
      assertEquals(post(w, queryBody()).error, "ROUTE_NOT_FOUND")
      assertEquals(get(w, "/healthz", key = None).status, 200)
    } finally w.close()
  }

  h.test("every error body carries the response's trace ID; 404, 405 and unknown paths") { h =>
    val responses = List(
      get(h, "/nothing/here"),
      get(h, "/v1/query"),
      call(h.base, "DELETE", "/healthz", None),
      call(h.base, "PUT", "/v1/query", Some("{}")),
      post(h, "{"),
      post(h, queryBody(), key = None),
      get(h, "/v1//jobs"),
      get(h, "/v1/jobs/" + "x" * 600)
    )
    responses.foreach { r =>
      assert(r.status >= 400, r.body)
      assertEquals(r.json("traceId").str, r.header("X-Trace-Id").get)
      assert(r.json("message").str.nonEmpty)
      assert(r.json.obj.contains("details"))
      assert(r.json.obj.contains("retryable"))
    }
    assertEquals(responses.map(_.status), List(404, 405, 405, 405, 400, 401, 404, 404))
    assertEquals(responses(1).header("Allow"), Some("POST"))
  }

  h.test("request and trace IDs: valid headers are used, invalid ones replaced") { h =>
    val tp = traceparent(5)
    val r = get(h, "/healthz", key = None, headers = List("X-Request-Id" -> "client-req.1", tp))
    assertEquals(r.header("X-Request-Id"), Some("client-req.1"))
    assertEquals(r.header("X-Trace-Id"), Some(f"${5}%032x"))
    val bad = get(
      h,
      "/healthz",
      key = None,
      headers = List("X-Request-Id" -> "bad id!", "traceparent" -> "00-zz-1-01")
    )
    assert(bad.header("X-Request-Id").exists(_.startsWith("req-")))
    assert(bad.header("X-Trace-Id").exists(_.matches("[0-9a-f]{32}")))
    val zeros = get(
      h,
      "/healthz",
      key = None,
      headers = List("traceparent" -> s"00-${"0" * 32}-${"1" * 16}-01")
    )
    assertNotEquals(zeros.header("X-Trace-Id"), Some("0" * 32))
  }

  h.test("a body that is not UTF-8 is 400 INVALID_JSON") { h =>
    val req = HttpRequest
      .newBuilder(URI.create(h.base + "/v1/query"))
      .header("X-Api-Key", FullKey)
      .header("Content-Type", "application/json")
      .POST(
        HttpRequest.BodyPublishers
          .ofByteArray(Array[Byte]('{', '"', 'q', '"', ':', '"', 0xc3.toByte, 0x28, '"', '}'))
      )
      .build()
    val r = client.send(req, HttpResponse.BodyHandlers.ofString())
    assertEquals(r.statusCode(), 400)
    assertEquals(ujson.read(r.body())("error").str, "INVALID_JSON")
  }

  h.test(
    "logs: one JSON line per request with the route template, never bodies, headers or paths"
  ) { h =>
    val id = RuntimeFixtures.rC.value
    get(h, s"/v1/graph/nodes/$id?limit=5", headers = List("X-Request-Id" -> "log-1"))
    post(h, queryBody(question = "Secret-looking question text 42"))
    val lines = h.log.all.filter(_("event").str == "request")
    val node = lines.find(_("requestId").str == "log-1").get
    assertEquals(
      node.obj.keySet.toSet,
      Set(
        "time",
        "role",
        "event",
        "traceId",
        "requestId",
        "method",
        "route",
        "status",
        "latencyMillis",
        "errorCode",
        "callerClass"
      )
    )
    assertEquals(node("route").str, "/v1/graph/nodes/{nodeId}")
    assertEquals(node("status").num.toInt, 400) // limit is not a node parameter
    assertEquals(node("errorCode").str, "INVALID_REQUEST")
    assertEquals(node("callerClass").str, "full")
    val rendered = h.log.rendered.mkString("\n")
    assert(!rendered.contains(id), "a raw path reached the log")
    assert(!rendered.contains("Secret-looking"), "a body reached the log")
    forbidden(h).filterNot(_ == "injected").foreach(s => assert(!rendered.contains(s), s))
  }

  test("log redaction: key values, arn: strings, 12-digit numbers and s3:// URIs are removed") {
    val text =
      s"failed with $FullKey and $ReadOnlyKey at arn:aws:iam::123456789012:role/example for s3://example-bucket/key/x account 210987654321 keep 4444444444444"
    val line = new Redactor(List(FullKey, ReadOnlyKey))(text)
    List(FullKey, ReadOnlyKey, "arn:aws", "123456789012", "210987654321", "s3://", "example-bucket")
      .foreach { s =>
        assert(!line.contains(s), s"$s in $line")
      }
    assert(line.contains("4444444444444"), line) // a 13-digit number is not an account ID
    assert(line.contains("[redacted]"))
  }

  test("S-4 an exception is logged as a fixed cause and its class name only, never its message") {
    val sink = new MemoryLogSink
    val clock = new org.scholarchain.storage.jobs.ManualClock()
    val log = new ServiceLog(sink, "all", clock, new Redactor(List(FullKey, ReadOnlyKey)))
    val host = "db-prod-7.cluster-ro-q1w2e3.us-east-1.neptune.amazonaws.com"
    val table = "scholarchain-prod-run-ledger"
    log.failure(
      Some("0" * 32),
      "test",
      new org.scholarchain.storage.StoreException(s"connect to $host for $table failed", true)
    )
    val line = sink.all.head
    assertEquals(line("cause").str, LogCause.UnexpectedException)
    assertEquals(line("exception").str, "org.scholarchain.storage.StoreException")
    assert(!line.obj.contains("detail"), line.render())
    assert(!line.render().contains(host) && !line.render().contains(table), line.render())
  }

  test("S-4 workflow failures become fixed cause codes; only an identifier-shaped class is kept") {
    val sensitive = "connect timed out to db.example.amazonaws.com reading scholarchain-prod-table"
    def fields(e: String) = ujson.Obj.from(LogCause.ofWorkflow(e)).render()
    assertEquals(
      ujson.read(fields(s"workflow failed: StoreException: $sensitive")),
      ujson.Obj("cause" -> "workflow-exception", "exception" -> "StoreException")
    )
    assertEquals(
      ujson.read(fields(s"evidence bundle write failed: $sensitive")),
      ujson.Obj("cause" -> "evidence-bundle-write-failed")
    )
    assertEquals(
      ujson.read(fields(s"query audit write failed: $sensitive")),
      ujson.Obj("cause" -> "query-audit-write-failed")
    )
    assertEquals(
      ujson.read(fields(s"workflow failed: db.example.amazonaws.com: $sensitive")),
      ujson.Obj("cause" -> "workflow-exception")
    )
    assertEquals(ujson.read(fields(sensitive)), ujson.Obj("cause" -> "workflow-failed"))
    import org.scholarchain.storage.jobs.JobChange
    assertEquals(LogCause.ofChange(Left(sensitive)), "store-failure")
    assertEquals(LogCause.ofChange(Right(JobChange.ConditionFailed)), "condition-failed")
    assertEquals(LogCause.ofChange(Right(JobChange.Refused(sensitive))), "refused")
  }

  test("auth startup: unset, blank or equal key values refuse start; the message holds no value") {
    val cases = List(
      Map(
        "SCHOLARCHAIN_EXAMPLE_READONLY_API_KEY" -> ReadOnlyKey
      ) -> "SCHOLARCHAIN_EXAMPLE_API_KEY is not set",
      Map(
        "SCHOLARCHAIN_EXAMPLE_API_KEY" -> FullKey
      ) -> "SCHOLARCHAIN_EXAMPLE_READONLY_API_KEY is not set",
      Map(
        "SCHOLARCHAIN_EXAMPLE_API_KEY" -> "  ",
        "SCHOLARCHAIN_EXAMPLE_READONLY_API_KEY" -> ReadOnlyKey
      ) -> "SCHOLARCHAIN_EXAMPLE_API_KEY is blank",
      Map(
        "SCHOLARCHAIN_EXAMPLE_API_KEY" -> FullKey,
        "SCHOLARCHAIN_EXAMPLE_READONLY_API_KEY" -> FullKey
      ) -> "hold the same value"
    )
    cases.foreach { case (keys, expected) =>
      val e = ApiKeys.load(config.service.get, keys.get)
      assert(e.left.exists(_.contains(expected)), e.toString)
      assert(e.left.forall(m => !m.contains(FullKey) && !m.contains(ReadOnlyKey)), e.toString)
      // the server refuses to start too
      val (_, _, _, rt) = runtimeOver(config, r => new org.scholarchain.rag.runtime.FakeModel(r))
      try {
        val stores = Composition
          .overRuntime(rt, 120000L, new org.scholarchain.storage.jobs.ManualClock())
          .fold(e => fail(e), identity)
        val s = ServiceServer.start(
          rt,
          stores,
          ServerOptions(
            role = Role.All,
            port = Some(0),
            env = keys.get,
            logSink = new MemoryLogSink
          )
        )
        assert(s.left.exists(_.contains(expected)), s.toString)
      } finally rt.close()
    }
    assertEquals(
      ApiKeys.load(config.service.get, env.get).map(_.toString),
      Right("ApiKeys(<hidden>)")
    )
  }

  test("shutdown: after close() no scholarchain-* thread of the server is alive") {
    val hh = harness(startWorker = true)
    assertEquals(post(hh, queryBody()).status, 200)
    assertEquals(get(hh, "/readyz", key = None).status, 200)
    val prefix = hh.server.threadPrefix
    assert(prefix.startsWith("scholarchain-"))
    assert(hh.server.threads.nonEmpty)
    assert(hh.server.threads.forall(_.getName.startsWith(prefix)))
    hh.close()
    hh.close() // idempotent
    assert(
      hh.server.threads.forall(!_.isAlive),
      hh.server.threads.filter(_.isAlive).map(_.getName).toString
    )
    val alive =
      Thread.getAllStackTraces.keySet.asScala.filter(t => t.getName.startsWith(prefix) && t.isAlive)
    assertEquals(alive.map(_.getName).toSet, Set.empty[String])
    assert(hh.runtime.isClosed)
    // the port no longer answers
    intercept[java.io.IOException](get(hh, "/healthz", key = None))
  }
}
