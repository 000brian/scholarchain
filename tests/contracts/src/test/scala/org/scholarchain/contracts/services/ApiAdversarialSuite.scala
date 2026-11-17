package org.scholarchain.contracts.services

import java.io.ByteArrayOutputStream
import java.net.{InetSocketAddress, Socket, URI}
import java.nio.charset.StandardCharsets

import org.scholarchain.services.ServiceTestKit
import org.scholarchain.services.ServiceTestKit.*

/** Category 19 (API authentication, authorization, malformed bodies, timeouts), over a real server
  * on 127.0.0.1 port 0 (services' `ServiceTestKit.harness`, rag's fixture world). Requests that a
  * normal client cannot send (two key headers, a short body, raw escapes) go over a raw loopback
  * socket with a bounded read timeout; nothing sleeps for ordering.
  */
class ApiAdversarialSuite extends munit.FunSuite {

  override val munitTimeout = scala.concurrent.duration.Duration(300, "s")

  private val UnknownJob = "/v1/jobs/job-00000000-0000-4000-8000-000000009999"

  /** One raw HTTP/1.1 exchange. `halfClose` shuts the client's output after the bytes (the server
    * sees end of stream). Returns the status code and the whole response text.
    */
  private def raw(h: Harness, request: Array[Byte], halfClose: Boolean = true): (Int, String) = {
    val uri = URI.create(h.base)
    val s = new Socket()
    try {
      s.connect(new InetSocketAddress("127.0.0.1", uri.getPort), 5000)
      s.setSoTimeout(20000)
      val out = s.getOutputStream
      out.write(request)
      out.flush()
      if (halfClose) s.shutdownOutput()
      val in = s.getInputStream
      val buf = new ByteArrayOutputStream()
      val chunk = new Array[Byte](4096)
      var n = in.read(chunk)
      while (n >= 0) { buf.write(chunk, 0, n); n = in.read(chunk) }
      val text = new String(buf.toByteArray, StandardCharsets.UTF_8)
      val status = text.split(" ", 3).lift(1).flatMap(_.toIntOption).getOrElse(-1)
      (status, text)
    } finally s.close()
  }

  private def head(lines: String*): String = lines.mkString("", "\r\n", "\r\n\r\n")

  private def bodyOf(text: String): String = text.indexOf("\r\n\r\n") match {
    case -1 => ""
    case i  => text.substring(i + 4)
  }

  test("case-changed key, key in the query string, and no key are all 401") {
    val h = harness()
    try {
      val upper = get(h, UnknownJob, key = Some(ServiceTestKit.FullKey.toUpperCase))
      assertEquals(upper.status, 401)
      assertEquals(upper.error, "UNAUTHENTICATED")
      val inQuery =
        get(
          h,
          s"$UnknownJob?api_key=${ServiceTestKit.FullKey}&X-Api-Key=${ServiceTestKit.FullKey}",
          key = None
        )
      assertEquals(inQuery.status, 401)
      val none = get(h, UnknownJob, key = None)
      assertEquals(none.status, 401)
      // the authenticated control: the route exists and answers 404 for an unknown job
      assertEquals(get(h, UnknownJob).status, 404)
    } finally h.close()
  }

  test("the read-only key is refused on POST /v1/query (403), even with a valid body") {
    val h = harness()
    try {
      val r = post(h, queryBody(), key = Some(ServiceTestKit.ReadOnlyKey))
      assertEquals(r.status, 403)
      assertEquals(r.error, "FORBIDDEN")
      assertEquals(h.runtime.model.usage.synthesis.logicalCalls.get, 0L)
    } finally h.close()
  }

  // T-03 owner: service-engineer (HttpAdapter.dispatch reads getFirst("X-Api-Key"): a request
  // carrying two different key headers is authenticated by whichever comes first).
  test("T-03 a request with two different X-Api-Key headers is refused 401".fail) {
    val h = harness()
    try {
      val req = head(
        s"GET $UnknownJob HTTP/1.1",
        "Host: 127.0.0.1",
        s"X-Api-Key: ${ServiceTestKit.FullKey}",
        "X-Api-Key: not-a-key",
        "Connection: close"
      )
      val (status, text) = raw(h, req.getBytes(StandardCharsets.US_ASCII))
      // the specific wrong value: 404 JOB_NOT_FOUND (authenticated as full) instead of 401
      assertEquals(status, 401, text.take(300))
    } finally h.close()
  }

  // T-06 owner: service-engineer (HttpAdapter.readBody: readNBytes on a body that ends before its
  // declared length throws an IOException, caught by handle's NonFatal as 500 INTERNAL plus an
  // error log line; a client's short body is reported as a server fault).
  test("T-06 a declared Content-Length longer than the body sent is a 4xx, not 500".fail) {
    val h = harness()
    try {
      val body = """{"question":"x"}"""
      val req = head(
        "POST /v1/query HTTP/1.1",
        "Host: 127.0.0.1",
        s"X-Api-Key: ${ServiceTestKit.FullKey}",
        "Content-Type: application/json",
        s"Content-Length: ${body.length + 200}",
        "Connection: close"
      ) + body
      val (status, text) = raw(h, req.getBytes(StandardCharsets.UTF_8))
      assert(status >= 400 && status < 500, s"status $status: ${text.take(400)}")
      assertEquals(h.runtime.model.usage.planner.logicalCalls.get, 0L)
    } finally h.close()
  }

  test("a number literal beyond double range in a constraint is 400, with a fixed message") {
    val h = harness()
    try {
      val r = post(
        h,
        s"""{"question":"${org.scholarchain.rag.runtime.RuntimeServiceFixtures.AnsweredQuestion}","constraints":{"yearFrom":1e400,"yearTo":2025}}"""
      )
      assertEquals(r.status, 400, r.body)
      assert(!r.body.contains("1e400") && !r.body.toLowerCase.contains("infinity"), r.body)
    } finally h.close()
  }

  // T-04 owner: service-engineer (QueryRequestParser.parse uses ujson.read, which keeps the last of
  // two equal keys; "Unknown fields anywhere are 400" but a repeated field is silently resolved).
  test("T-04 a request body with a repeated field is refused 400".fail) {
    val h = harness()
    try {
      val q = org.scholarchain.rag.runtime.RuntimeServiceFixtures.AnsweredQuestion
      val body =
        s"""{"question":"Who is Hana Ito?","question":"$q","constraints":{"yearFrom":2015,"yearTo":2025}}"""
      val r = post(h, body)
      // the specific wrong value: 200 with the answer to the second question
      assertEquals(r.status, 400, r.body.take(300))
    } finally h.close()
  }

  // T-05 owner: rag-engineer and service-engineer (QueryRequest.fingerprint and
  // ValidatedQuery.fingerprint hash String.getBytes(UTF_8), which turns a lone surrogate into '?';
  // the parser accepts "\ud800", so two different questions share one query ID and one
  // idempotency fingerprint).
  test("T-05 a question with a lone surrogate escape is refused, not hashed as '?'".fail) {
    val h = harness()
    try {
      val q = org.scholarchain.rag.runtime.RuntimeServiceFixtures.AnsweredQuestion
      require(q.endsWith("?"))
      val clean = post(h, queryBody(q), headers = List("Idempotency-Key" -> "phase11-surrogate"))
      assertEquals(clean.status, 200, clean.body.take(300))
      val escaped = q.stripSuffix("?") + "\\ud800"
      val body =
        s"""{"question":"$escaped","mode":"sync","constraints":{"yearFrom":2015,"yearTo":2025}}"""
      val r = post(h, body, headers = List("Idempotency-Key" -> "phase11-surrogate"))
      // the specific wrong value: 200 Idempotent-Replayed (the other question's stored answer)
      // instead of 400 (or 409 IDEMPOTENCY_CONFLICT for a different request under the same key)
      assert(
        r.status == 400 || r.status == 409,
        s"status ${r.status} replayed=${r.header("Idempotent-Replayed")} queryId=${scala.util
            .Try(r.json("queryId").str)
            .getOrElse("-")} cleanQueryId=${clean.json("queryId").str}"
      )
    } finally h.close()
  }

  // T-06 (same root cause, chunked framing) owner: service-engineer.
  test("T-06 an unterminated chunked body is a 4xx, not 500".fail) {
    val h = harness()
    try {
      val req = head(
        "POST /v1/query HTTP/1.1",
        "Host: 127.0.0.1",
        s"X-Api-Key: ${ServiceTestKit.FullKey}",
        "Content-Type: application/json",
        "Transfer-Encoding: chunked",
        "Connection: close"
      ) + "10\r\n{\"question\":\"x"
      val (status, text) = raw(h, req.getBytes(StandardCharsets.UTF_8))
      assert(status >= 400 && status < 500 || status == -1, s"status $status: ${text.take(300)}")
      assertEquals(h.runtime.model.usage.planner.logicalCalls.get, 0L)
      if (status > 0) assert(!bodyOf(text).contains("Exception"), text.take(400))
    } finally h.close()
  }
}
