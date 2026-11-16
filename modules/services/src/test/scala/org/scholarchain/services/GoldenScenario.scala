package org.scholarchain.services

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

import org.scholarchain.rag.runtime.{FakeModel, RuntimeFixtures, RuntimeServiceFixtures}
import org.scholarchain.rag.runtime.RuntimeServiceFixtures.GatedModel

/** The fixture requests behind the frozen examples under `src/test/resources/api/v1/` (decision
  * 7j). Every request carries a fixed `traceparent` and `X-Request-Id`; the server has fixed IDs, a
  * manual clock and a workflow clock of 0, so every response is reproducible byte for byte.
  */
object GoldenScenario {
  import ServiceTestKit._

  /** Example file name -> (model name, body). The request example comes first. */
  def run(): List[(String, String)] = {
    val out = List.newBuilder[(String, String)]
    val h = harness()
    var n = 0
    def hdrs(extra: List[(String, String)] = Nil) = {
      n += 1
      List(traceparent(n), "X-Request-Id" -> s"golden-$n") ++ extra
    }
    try {
      val request = Json.render(
        ujson.Obj(
          "question" -> RuntimeServiceFixtures.AnsweredQuestion,
          "constraints" -> ujson.Obj("yearFrom" -> 2015, "yearTo" -> 2025),
          "mode" -> "sync",
          "output" -> ujson.Obj("includeEvidence" -> true)
        )
      )
      out += "sync-query-request.json" -> request
      val sync = post(h, request, headers = hdrs())
      out += "completed-result.json" -> sync.body
      val asyncBody = Json.render(ujson.read(request).obj.addOne("mode" -> ujson.Str("async")))
      val ack = post(h, asyncBody, headers = hdrs())
      out += "async-acknowledgment.json" -> ack.body
      h.server.worker.processOne()
      out += "job-status.json" -> get(h, ack.json("statusUrl").str, headers = hdrs()).body
      val link = sync.json("links")("evidence")(0).str
      out += "evidence-item.json" -> get(h, link, headers = hdrs()).body
      val g = RuntimeFixtures.rG.value
      out += "node.json" -> get(h, s"/v1/graph/nodes/$g", headers = hdrs()).body
      out += "neighbors-page.json" -> get(
        h,
        s"/v1/graph/nodes/$g/neighbors?limit=2",
        headers = hdrs()
      ).body
      out += "explain.json" -> get(h, sync.json("links")("explain").str, headers = hdrs()).body
      out += "error-400.json" -> post(h, "{", headers = hdrs()).body
      out += "error-401.json" -> get(h, s"/v1/graph/nodes/$g", key = None, headers = hdrs()).body
      out += "error-404.json" -> get(
        h,
        s"/v1/evidence/evidence:v1:${"0" * 32}",
        headers = hdrs()
      ).body
      post(h, request, headers = hdrs(List("Idempotency-Key" -> "golden-key")))
      val other = Json.render(
        ujson
          .read(request)
          .obj
          .addOne("question" -> ujson.Str("Who are the coauthors of Carol King?"))
      )
      out += "error-409.json" -> post(
        h,
        other,
        headers = hdrs(List("Idempotency-Key" -> "golden-key"))
      ).body
      val venues = Json.render(
        ujson.Obj(
          "question" -> RuntimeServiceFixtures.AnsweredQuestion,
          "constraints" -> ujson.Obj("venues" -> ujson.Arr("conf/kdd"))
        )
      )
      out += "error-422.json" -> post(h, venues, headers = hdrs()).body
      h.flaky.failGraph.set(true)
      out += "readiness-not-ready.json" -> get(h, "/readyz", key = None, headers = hdrs()).body
    } finally h.close()
    val slow = harness(
      cfg = withService(config)(_.copy(syncDeadlineMillis = 200)),
      model = r => new GatedModel(new FakeModel(r))
    )
    try {
      n += 1
      val r = post(
        slow,
        ujson.Obj("question" -> RuntimeServiceFixtures.AnsweredQuestion).render(),
        headers = List(traceparent(n), "X-Request-Id" -> s"golden-$n")
      )
      out += "error-504.json" -> r.body
    } finally { slow.model.asInstanceOf[GatedModel].release(); slow.close() }
    out.result()
  }
}

/** Writes the examples into a directory for review (`services/Test/runMain
  * org.scholarchain.services.GoldenExampleWriter DIR`); the frozen copies live in
  * `src/test/resources/api/v1/` and are compared by [[GoldenExamplesSuite]].
  */
object GoldenExampleWriter {
  def main(args: Array[String]): Unit = {
    val dir = Paths.get(args.head)
    Files.createDirectories(dir)
    GoldenScenario.run().foreach { case (name, body) =>
      Files.write(dir.resolve(name), (body + "\n").getBytes(StandardCharsets.UTF_8))
    }
  }
}
