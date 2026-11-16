package org.scholarchain.services

import java.nio.charset.StandardCharsets

import upickle.default.{read, ReadWriter}

/** The frozen request and response examples (decision 7j; the spec gate "request and response
  * examples match contract tests"):
  *   - each example decodes into its REST model and re-encodes, with the shared encoder, to the
  *     same bytes;
  *   - each response example equals, byte for byte, what the running server returns for the fixture
  *     request that produced it ([[GoldenScenario]]: fixed IDs, clock and trace headers).
  */
class GoldenExamplesSuite extends munit.FunSuite {

  override val munitTimeout = scala.concurrent.duration.Duration(180, "s")

  private def resource(name: String): String = {
    val in = getClass.getResourceAsStream(s"/api/v1/$name")
    assert(in != null, s"missing example $name")
    try new String(in.readAllBytes(), StandardCharsets.UTF_8).stripSuffix("\n")
    finally in.close()
  }

  private def roundTrip[A: ReadWriter](name: String): Unit = {
    val text = resource(name)
    assertEquals(Json.encode(read[A](text)), text, name)
  }

  /** Example file -> its model. */
  private val responses: List[(String, String => Unit)] = List(
    "completed-result.json" -> (n => roundTrip[CompletedResult](n)),
    "async-acknowledgment.json" -> (n => roundTrip[QueryAcknowledgment](n)),
    "job-status.json" -> (n => roundTrip[JobStatusView](n)),
    "evidence-item.json" -> (n => roundTrip[EvidenceView](n)),
    "node.json" -> (n => roundTrip[NodeView](n)),
    "neighbors-page.json" -> (n => roundTrip[NeighborsPage](n)),
    "explain.json" -> (n => roundTrip[ExplainView](n)),
    "readiness-not-ready.json" -> (n => roundTrip[ReadinessBody](n)),
    "error-400.json" -> (n => roundTrip[ErrorBody](n)),
    "error-401.json" -> (n => roundTrip[ErrorBody](n)),
    "error-404.json" -> (n => roundTrip[ErrorBody](n)),
    "error-409.json" -> (n => roundTrip[ErrorBody](n)),
    "error-422.json" -> (n => roundTrip[ErrorBody](n)),
    "error-504.json" -> (n => roundTrip[ErrorBody](n))
  )

  responses.foreach { case (name, check) =>
    test(s"$name decodes into its model and re-encodes to the same bytes")(check(name))
  }

  test("sync-query-request.json is a valid request in the shared encoding") {
    val text = resource("sync-query-request.json")
    assertEquals(Json.render(ujson.read(text)), text)
    val cfg = ServiceTestKit.config
    val parsed =
      new QueryRequestParser(cfg.graph.windows, cfg.runtime.get, cfg.vectors.get.queryTopK)
        .parse(text)
    assertEquals(
      parsed.map(q => (q.windowLabel, q.includeEvidence)),
      Right((Some("full-2015-2025"), true))
    )
  }

  test("every response example equals the running server's output for its fixture request") {
    val produced = GoldenScenario.run()
    assertEquals(
      produced.map(_._1).sorted,
      ("sync-query-request.json" :: responses.map(_._1)).sorted
    )
    produced.foreach { case (name, body) =>
      assertEquals(body, resource(name), name)
    }
  }

  test("the error examples cover 400, 401, 404, 409, 422 and 504, each with the trace ID") {
    val statuses = List("400", "401", "404", "409", "422", "504").map { s =>
      val body = read[ErrorBody](resource(s"error-$s.json"))
      val code = ErrorCode.all.find(_.name == body.error).get
      assertEquals(body.message, code.message)
      assertEquals(body.retryable, code.retryable)
      assert(body.traceId.matches("[0-9a-f]{32}"))
      code.status.toString
    }
    assertEquals(statuses, List("400", "401", "404", "409", "422", "504"))
  }
}
