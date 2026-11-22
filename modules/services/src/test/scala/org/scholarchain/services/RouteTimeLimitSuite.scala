package org.scholarchain.services

/** Phase 16 (Phase 12 item): the time limit of the evidence and explain routes, a server setting
  * (`ServerOptions.readDeadlineMillis`) with a default (`ServiceLimits.ReadRouteDeadlineMillis`).
  */
class RouteTimeLimitSuite extends munit.FunSuite {
  import ServiceTestKit._

  override val munitTimeout = scala.concurrent.duration.Duration(120, "s")

  private val Deadline = 300L
  private val Slow = 1500L
  private val evidence = s"/v1/evidence/evidence:v1:${"0" * 32}"
  private val explain = s"/v1/explain/run:v1:${"0" * 32}"

  private def timed(h: Harness, path: String): (Resp, Long) = {
    val t0 = System.nanoTime()
    val r = get(h, path)
    (r, (System.nanoTime() - t0) / 1000000L)
  }

  test("the default limit is 10 s; start refuses a limit outside 1..60000 ms") {
    assertEquals(ServerOptions(Role.All).readDeadlineMillis, ServiceLimits.ReadRouteDeadlineMillis)
    assertEquals(ServiceLimits.ReadRouteDeadlineMillis, 10000L)
    val (_, _, _, rt) = runtimeOver(config, r => new org.scholarchain.rag.runtime.FakeModel(r))
    try {
      def start(ms: Long) =
        ServiceServer.start(
          rt,
          Composition
            .overRuntime(rt, 60000L, new org.scholarchain.storage.jobs.ManualClock())
            .fold(e => fail(e), identity),
          ServerOptions(
            role = Role.Evidence,
            port = Some(0),
            env = env.get,
            logSink = new MemoryLogSink,
            closeRuntime = false,
            readDeadlineMillis = ms
          )
        )
      assert(start(0L).left.exists(_.contains("readDeadlineMillis must be in 1..60000")))
      assert(start(60001L).isLeft)
      start(60000L).fold(e => fail(e), identity).close()
    } finally rt.close()
  }

  test(
    "evidence and explain: a read slower than the limit answers 504 READ_TIMEOUT (retryable) within the limit"
  ) {
    val h = harness(readDeadlineMillis = Deadline)
    try {
      // fast reads answer as before
      assertEquals(get(h, evidence).error, "EVIDENCE_NOT_FOUND")
      assertEquals(get(h, explain).error, "QUERY_NOT_FOUND")
      h.slow.readDelayMillis.set(Slow)
      for (path <- List(evidence, explain)) {
        val (r, ms) = timed(h, path)
        assertEquals(r.status, 504, r.body)
        assertEquals(r.error, "READ_TIMEOUT")
        assertEquals(r.json("retryable").bool, true)
        assert(r.header("Retry-After").nonEmpty)
        assertEquals(r.json("traceId").str, r.header("X-Trace-Id").get)
        assert(ms < Slow, s"$ms ms: the request waited for the slow read")
      }
      val lines = h.log.all.filter(_("event").str == "read-deadline-passed")
      assertEquals(lines.map(_("route").str), List("evidence", "explain"))
      h.slow.readDelayMillis.set(0L)
      assertEquals(get(h, evidence).error, "EVIDENCE_NOT_FOUND")
    } finally h.close()
  }

  test("timed-out reads count as reads-breaker failures: the circuit opens, then recovers") {
    val h = harness(readDeadlineMillis = Deadline)
    try {
      h.slow.readDelayMillis.set(Slow)
      (1 to h.settings.circuitFailureThreshold).foreach(_ =>
        assertEquals(get(h, explain).error, "READ_TIMEOUT")
      )
      assertEquals(get(h, explain).error, "CIRCUIT_OPEN")
      assertEquals(get(h, evidence).error, "CIRCUIT_OPEN")
      h.slow.readDelayMillis.set(0L)
      h.clock.advance(h.settings.circuitOpenMillis.toLong)
      assertEquals(get(h, explain).error, "QUERY_NOT_FOUND")
      assertEquals(h.server.breakers.reads.state, "closed")
    } finally h.close()
  }

  test("graph routes are not under the read limit (they keep runtime.graphTimeoutMillis)") {
    val h = harness(readDeadlineMillis = Deadline)
    try {
      h.slow.graphDelayMillis.set(Deadline + 300L)
      val r = get(h, s"/v1/graph/nodes/${org.scholarchain.rag.runtime.RuntimeFixtures.rC.value}")
      assertEquals(r.status, 200, r.body)
    } finally h.close()
  }
}
