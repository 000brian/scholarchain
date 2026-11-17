package org.scholarchain.services

import org.scholarchain.core.VerificationStatus
import org.scholarchain.rag.runtime.RuntimeServiceFixtures

/** POST /v1/query: the spec's required query tests (decision 7k). */
class QueryApiSuite extends munit.FunSuite {
  import ServiceTestKit._

  override val munitTimeout = scala.concurrent.duration.Duration(180, "s")

  private val h = FunFixture[Harness](_ => harness(), _.close())

  h.test("successful synchronous query: 200 with a completed, evidence-backed result") { h =>
    val r = post(h, queryBody())
    assertEquals(r.status, 200, r.body)
    val res = upickle.default.read[CompletedResult](r.body)
    val expectedId =
      h.runtime.queryRunId(RuntimeServiceFixtures.answeredRequest).fold(e => fail(e), identity)
    assertEquals(res.queryId, expectedId.value)
    assert(!res.answer.abstained)
    assert(res.answer.claims.exists(_.status == VerificationStatus.Verified))
    assert(res.answer.evidence.exists(_.nonEmpty))
    assert(res.answer.bundleId.nonEmpty)
    assertEquals(res.links.explain, s"/v1/explain/${res.queryId}")
    val cited = res.answer.claims.flatMap(_.evidenceIds).distinct
    assertEquals(res.links.evidence.size, cited.size)
    assert(
      res.links.evidence.forall(
        _.matches("/v1/evidence/evidence:v1:[0-9a-f]{32}(\\?window=[0-9]{4}-[0-9]{4})?")
      )
    )
    assertEquals(res.notes, Nil)
    assert(r.header("X-Request-Id").nonEmpty)
    assert(r.header("X-Trace-Id").exists(_.matches("[0-9a-f]{32}")))
    // includeEvidence false: the evidence field is null
    val noEv = post(h, queryBody(extra = List("output" -> ujson.Obj("includeEvidence" -> false))))
    assertEquals(noEv.status, 200)
    assertEquals(noEv.json("answer")("evidence"), ujson.Null)
  }

  h.test("asynchronous path: QUEUED, RUNNING with a stage, SUCCEEDED; result identical to sync") {
    h =>
      val ack = post(h, queryBody(mode = "async"))
      assertEquals(ack.status, 202, ack.body)
      val a = upickle.default.read[QueryAcknowledgment](ack.body)
      assertEquals(a.status, "QUEUED")
      assertEquals(a.statusUrl, s"/v1/jobs/${a.jobId}")
      assert(a.jobId.matches("job-[0-9a-f-]{36}"))
      assertEquals(get(h, a.statusUrl).json("state").str, "QUEUED")
      assertEquals(get(h, s"/v1/jobs/${a.jobId}/result").error, "JOB_NOT_READY")
      // RUNNING with a stage is observed in the next test (a gated model holds the workflow)
      assertEquals(h.server.worker.processOne(), WorkerOutcome.Succeeded)
      val status = get(h, a.statusUrl).json
      assertEquals(status("state").str, "SUCCEEDED")
      assertEquals(status("stage").str, "audit") // the last stage written while running
      assertEquals(status("attempts").num.toInt, 1)
      assertEquals(status("resultUrl").str, s"/v1/jobs/${a.jobId}/result")
      val asyncResult = get(h, s"/v1/jobs/${a.jobId}/result")
      assertEquals(asyncResult.status, 200)
      val sync = post(h, queryBody())
      assertEquals(sync.status, 200)
      assertEquals(asyncResult.body, sync.body)
      assertEquals(a.queryId, sync.json("queryId").str)
  }

  test("asynchronous path: a stage update is visible on the job while the workflow runs") {
    val gated = harness(model =
      r => new RuntimeServiceFixtures.GatedModel(new org.scholarchain.rag.runtime.FakeModel(r))
    )
    try {
      val a = upickle.default.read[QueryAcknowledgment](post(gated, queryBody(mode = "async")).body)
      val t = new Thread(() => { gated.server.worker.processOne(); () })
      t.start()
      val g = gated.model.asInstanceOf[RuntimeServiceFixtures.GatedModel]
      assert(g.awaitEntered(30000L))
      val running = get(gated, a.statusUrl).json
      assertEquals(running("state").str, "RUNNING")
      assertEquals(running("stage").str, "planner")
      assertEquals(running("resultUrl"), ujson.Null)
      g.release()
      t.join(60000L)
      assertEquals(get(gated, a.statusUrl).json("state").str, "SUCCEEDED")
    } finally gated.close()
  }

  h.test("full query-to-evidence lifecycle with fakes") { h =>
    val r = post(h, queryBody())
    assertEquals(r.status, 200, r.body)
    val res = upickle.default.read[CompletedResult](r.body)
    assert(!res.answer.abstained)
    assert(res.answer.claims.exists(_.status == VerificationStatus.Verified))
    val link = res.links.evidence.head
    val ev = get(h, link)
    assertEquals(ev.status, 200, ev.body)
    val item = upickle.default.read[EvidenceView](ev.body)
    assertEquals(link.startsWith(s"/v1/evidence/${item.evidenceId.value}"), true)
    assert(item.sourceUris.nonEmpty)
    val ex = get(h, res.links.explain)
    assertEquals(ex.status, 200, ex.body)
    val explain = upickle.default.read[ExplainView](ex.body)
    assertEquals(explain.queryId, res.queryId)
    assertEquals(explain.evidenceIds, res.answer.evidence.get.map(_.evidenceId))
    assert(res.answer.claims.flatMap(_.evidenceIds).forall(explain.evidenceIds.contains))
    assertEquals(explain.scoreComponents, Nil)
    // Phase 11 explain v2: the score note, then the note on what modelCalls holds
    assertEquals(explain.notes, List(ExplainView.ScoreNote, ExplainView.AttemptsNote))
    assertEquals(explain.attemptCount, 1)
    assert(explain.stageMillis.keySet.contains("planner"))
    // no prompt text, model output or reasoning in explain
    assert(!ex.body.contains("You are"), ex.body.take(200))
    assert(!ex.body.toLowerCase.contains("reasoning"))
  }

  h.test("invalid JSON, unknown fields, oversized body, wrong media type, deep nesting") { h =>
    val bad = post(h, "{\"question\": ")
    assertEquals(bad.status, 400)
    assertEquals(bad.error, "INVALID_JSON")
    assertEquals(bad.json("traceId").str, bad.header("X-Trace-Id").get)
    val unknown = post(h, queryBody(extra = List("colour" -> ujson.Str("blue"))))
    assertEquals(unknown.status, 400)
    assertEquals(unknown.error, "INVALID_REQUEST")
    assertEquals(unknown.json("details")("fieldErrors")(0)("field").str, "colour")
    val nested =
      post(h, queryBody(extra = List("output" -> ujson.Obj("includeEvidence" -> true, "x" -> 1))))
    assertEquals(nested.status, 400)
    assertEquals(nested.json("details")("fieldErrors")(0)("field").str, "output.x")
    val big = post(h, "{\"question\": \"" + ("a" * 70000) + "\"}")
    assertEquals(big.status, 413)
    assertEquals(big.error, "PAYLOAD_TOO_LARGE")
    val media =
      call(h.base, "POST", "/v1/query", Some(queryBody()), contentType = Some("text/plain"))
    assertEquals(media.status, 415)
    assertEquals(media.error, "UNSUPPORTED_MEDIA_TYPE")
    val noMedia = call(h.base, "POST", "/v1/query", Some(queryBody()), contentType = None)
    assertEquals(noMedia.status, 415)
    val deep = post(h, "{\"question\": " + ("[" * 40) + ("]" * 40) + "}")
    assertEquals(deep.status, 400)
    assertEquals(deep.error, "INVALID_JSON")
    val notObject = post(h, "[1, 2]")
    assertEquals(notObject.status, 400)
    assertEquals(notObject.error, "INVALID_REQUEST")
    val blank = post(h, "{\"question\": \"   \"}")
    assertEquals(blank.error, "INVALID_REQUEST")
    val long = post(h, ujson.Obj("question" -> ("q" * 2001)).render())
    assertEquals(long.error, "INVALID_REQUEST")
    val mode = post(h, queryBody(mode = "later"))
    assertEquals(mode.error, "INVALID_REQUEST")
    val oneYear =
      post(h, ujson.Obj("question" -> "q", "constraints" -> ujson.Obj("yearFrom" -> 2015)).render())
    assertEquals(oneYear.error, "INVALID_REQUEST")
    val badKey = call(
      h.base,
      "POST",
      "/v1/query",
      Some(queryBody()),
      headers = List("Idempotency-Key" -> "has space")
    )
    assertEquals(badKey.error, "INVALID_REQUEST")
  }

  h.test("unsupported query constraints: venues, institutions, years that are not a window") { h =>
    def constraint(c: ujson.Obj) =
      post(h, ujson.Obj("question" -> "Who works with Carol King?", "constraints" -> c).render())
    val venues = constraint(ujson.Obj("venues" -> ujson.Arr("conf/kdd")))
    assertEquals(venues.status, 422)
    assertEquals(venues.error, "UNSUPPORTED_CONSTRAINT")
    assertEquals(venues.json("details")("fieldErrors")(0)("field").str, "constraints.venues")
    val inst = constraint(ujson.Obj("institutions" -> ujson.Arr("Example University")))
    assertEquals(inst.status, 422)
    assertEquals(inst.json("details")("fieldErrors")(0)("field").str, "constraints.institutions")
    val years = constraint(ujson.Obj("yearFrom" -> 2015, "yearTo" -> 2026))
    assertEquals(years.status, 422)
    val windows = years.json("details")("windows").arr.map(_("label").str).toList
    assertEquals(windows, h.runtime.config.graph.windows.map(_.label))
    val topics = constraint(ujson.Obj("topics" -> ujson.Arr("a", "b")))
    assertEquals(topics.status, 422)
    val longTopic = constraint(ujson.Obj("topics" -> ujson.Arr("t" * 201)))
    assertEquals(longTopic.status, 422)
    val emptyLists = constraint(ujson.Obj("venues" -> ujson.Arr(), "institutions" -> ujson.Arr()))
    assertEquals(emptyLists.status, 200, emptyLists.body)
  }

  test(
    "the spec's example request (with a configured window) is accepted; notes list unapplied fields"
  ) {
    // decision 7c: the spec example, with yearTo set to a configured window (2015-2025), its
    // rankingMetric written as a ranked metric wire name, and runtime.graphResultLimit raised to the
    // example's graphLimit of 100 (the services config keeps rag's 50; see the next test)
    val cfg = ServiceTestKit.config.copy(runtime =
      ServiceTestKit.config.runtime.map(_.copy(graphResultLimit = 100))
    )
    val hh = harness(cfg = cfg)
    try {
      val body = scala.io.Source.fromResource("api/v1/spec-example-request.json").mkString
      val r = post(hh, body)
      assertEquals(r.status, 200, r.body)
      val notes = r.json("notes").arr.map(_.str).toList
      assertEquals(
        notes.map(_.takeWhile(_ != ' ')),
        List(
          "analysis.includeBridgeMetrics",
          "analysis.includeCommunities",
          "analysis.includePaths",
          "analysis.includeTeamSize",
          "analysis.rankingMetric",
          "retrieval.graphLimit",
          "retrieval.maxPathDepth",
          "retrieval.vectorLimit"
        )
      )
    } finally hh.close()
  }

  test("the spec's example request sent async: the completed result equals the sync one (notes)") {
    // decision 7c: an async completed result is identical to the sync result for the same request,
    // including the notes of the accepted but unapplied fields (review finding S-1)
    val cfg = ServiceTestKit.config.copy(runtime =
      ServiceTestKit.config.runtime.map(_.copy(graphResultLimit = 100))
    )
    val hh = harness(cfg = cfg)
    try {
      val body = scala.io.Source.fromResource("api/v1/spec-example-request.json").mkString
      val asyncBody = Json.render(ujson.read(body).obj.addOne("mode" -> ujson.Str("async")))
      val ack = post(hh, asyncBody)
      assertEquals(ack.status, 202, ack.body)
      val jobId = ack.json("jobId").str
      assertEquals(hh.server.worker.processOne(), WorkerOutcome.Succeeded)
      val asyncResult = get(hh, s"/v1/jobs/$jobId/result")
      assertEquals(asyncResult.status, 200, asyncResult.body)
      val sync = post(hh, body)
      assertEquals(sync.status, 200, sync.body)
      assertEquals(sync.json("notes").arr.size, 8)
      assertEquals(asyncResult.json("notes"), sync.json("notes"))
      assertEquals(asyncResult.body, sync.body)
    } finally hh.close()
  }

  h.test("out-of-bounds retrieval values are 422; analysis types and metric names are checked") {
    h =>
      val graph = post(h, queryBody(extra = List("retrieval" -> ujson.Obj("graphLimit" -> 100))))
      assertEquals(graph.status, 422)
      assertEquals(graph.json("details")("fieldErrors")(0)("field").str, "retrieval.graphLimit")
      val vec = post(h, queryBody(extra = List("retrieval" -> ujson.Obj("vectorLimit" -> 31))))
      assertEquals(vec.status, 422)
      val depth = post(h, queryBody(extra = List("retrieval" -> ujson.Obj("maxPathDepth" -> 7))))
      assertEquals(depth.status, 422)
      val zero = post(h, queryBody(extra = List("retrieval" -> ujson.Obj("graphLimit" -> 0))))
      assertEquals(zero.status, 400)
      val metric = post(
        h,
        queryBody(extra =
          List("analysis" -> ujson.Obj("rankingMetric" -> "DISTINCT_COAUTHOR_COUNT"))
        )
      )
      assertEquals(metric.status, 422)
      val typed = post(h, queryBody(extra = List("analysis" -> ujson.Obj("includePaths" -> "yes"))))
      assertEquals(typed.status, 400)
      val scores = post(h, queryBody(extra = List("output" -> ujson.Obj("includeScores" -> false))))
      assertEquals(scores.status, 422)
  }

  h.test(
    "one trace ID follows an async query: query line, job, worker lines and every stage line"
  ) { h =>
    val tp = traceparent(77)
    val trace = f"${77}%032x"
    val ack = post(h, queryBody(mode = "async"), headers = List(tp))
    assertEquals(ack.status, 202)
    assertEquals(ack.header("X-Trace-Id"), Some(trace))
    val jobId = ack.json("jobId").str
    val job = h.stores.jobs.get(jobId, h.clock.millis()).toOption.flatten.get
    assertEquals(job.traceId, trace)
    assertEquals(h.server.worker.processOne(), WorkerOutcome.Succeeded)
    val lines = h.log.all
    val queryLine =
      lines.find(l => l("event").str == "request" && l("route").str == "/v1/query").get
    assertEquals(queryLine("traceId").str, trace)
    val stageLines = lines.filter(_("event").str == "stage")
    assertEquals(
      stageLines.map(_("stage").str),
      org.scholarchain.rag.runtime.QueryWorkflow.StageNames
    )
    assert(stageLines.forall(l => l("traceId").str == trace && l("jobId").str == jobId))
    assert(stageLines.forall(_("queryId").str == ack.json("queryId").str))
    val workerLines = lines.filter(l => Set("job-started", "job-succeeded")(l("event").str))
    assertEquals(workerLines.size, 2)
    assert(workerLines.forall(_("traceId").str == trace))
    // a sync query writes stage lines with its trace ID and no job
    val tp2 = traceparent(78)
    assertEquals(post(h, queryBody(), headers = List(tp2)).status, 200)
    val syncStages =
      h.log.all.filter(l => l("event").str == "stage" && l("traceId").str == f"${78}%032x")
    assertEquals(syncStages.size, org.scholarchain.rag.runtime.QueryWorkflow.StageNames.size)
    assert(syncStages.forall(_("jobId") == ujson.Null))
  }

  h.test("S-10 POST /v1/query with a query-string parameter is 400 and runs nothing") { h =>
    val r = post(h, queryBody(), path = "/v1/query?debug=1")
    assertEquals(r.status, 400, r.body)
    assertEquals(r.error, "INVALID_REQUEST")
    assert(r.body.contains("debug"), r.body)
    assert(!h.log.all.exists(_("event").str == "stage"), "the workflow ran")
    assertEquals(post(h, queryBody()).status, 200)
  }
}
