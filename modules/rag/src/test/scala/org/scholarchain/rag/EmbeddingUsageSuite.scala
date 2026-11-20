package org.scholarchain.rag

import java.net.URI
import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}
import java.util.concurrent.atomic.AtomicLong

import org.scholarchain.core.config.EmbeddingProvider
import org.scholarchain.rag.embedding._
import org.scholarchain.storage.Sleeper

/** U-11 (Phase 15): embedding usage counters are exact under concurrent callers (the ingest's
  * `embedConcurrency` calls and the worker's re-embed loop share one embedder).
  */
class EmbeddingUsageSuite extends munit.FunSuite {

  override val munitTimeout = scala.concurrent.duration.Duration(120, "s")

  /** Runs `body(thread)` `perThread` times on `threads` threads started together. */
  private def concurrently(threads: Int, perThread: Int)(body: Int => Unit): Unit = {
    val pool = Executors.newFixedThreadPool(threads)
    val start = new CountDownLatch(1)
    try {
      val futures = (0 until threads).map { t =>
        pool.submit(new Runnable {
          def run(): Unit = { start.await(); (0 until perThread).foreach(_ => body(t)) }
        })
      }
      start.countDown()
      futures.foreach(_.get(100, TimeUnit.SECONDS))
    } finally pool.shutdownNow()
  }

  test("U-11: every counter is exact when 8 threads add at once") {
    val u = new EmbeddingUsage
    concurrently(8, 50000) { _ =>
      u.addRequests(1L); u.addTexts(2L); u.addCharacters(3L)
      u.addEstimatedTokens(4L); u.addReportedTokens(5L); u.addRetries(1L)
    }
    val n = 8L * 50000L
    assertEquals(u.requests, n)
    assertEquals(u.texts, 2 * n)
    assertEquals(u.characters, 3 * n)
    assertEquals(u.estimatedTokens, 4 * n)
    assertEquals(u.reportedTokens, 5 * n)
    assertEquals(u.retries, n)
    assertEquals(u.toJson("requests").num, n.toDouble)
  }

  test("U-11: the fake embedder counts exactly under 8 concurrent callers") {
    val spec = EmbeddingModelSpec
      .of(EmbeddingProvider.Fake, EmbeddingModelSpec.FakeModel, 16, InputType.Document)
      .toOption
      .get
    val e = new FakeLexicalEmbedder(spec, 4)
    concurrently(8, 5000)(t => assert(e.embed(Vector(s"a$t", "bc")).isRight))
    assertEquals(e.usage.requests, 40000L)
    assertEquals(e.usage.texts, 80000L)
    assertEquals(e.usage.characters, 40000L * 4L)
  }

  test("U-11: the voyage embedder counts requests, texts and provider tokens exactly") {
    val settings = RagFixtures.voyageConfig.embedding.get
    val spec =
      EmbeddingModelSpec.fromConfig(RagFixtures.voyageConfig, InputType.Document).toOption.get
    val transport = new HttpTransport {
      def postJson(uri: URI, headers: List[(String, String)], body: String): HttpResult =
        VoyageResponses.ok(ujson.read(body)("input").arr.size, spec.dimensions, tokens = 3)
    }
    // every clock read is a new minute, so the shared rate limiter never sleeps
    val clock = new AtomicLong(0L)
    val e = VoyageEmbedder
      .create(
        spec,
        settings,
        Map("SCHOLARCHAIN_VOYAGE_API_KEY" -> "test-key-not-real").get,
        transport,
        Sleeper.none,
        () => clock.addAndGet(60001L)
      )
      .fold(fail(_), identity)
    concurrently(8, 200)(_ => assert(e.embed(Vector("ab", "cd")).isRight))
    assertEquals(e.usage.requests, 1600L)
    assertEquals(e.usage.texts, 3200L)
    assertEquals(e.usage.characters, 6400L)
    assertEquals(e.usage.reportedTokens, 4800L)
    assertEquals(e.usage.retries, 0L)
  }
}
