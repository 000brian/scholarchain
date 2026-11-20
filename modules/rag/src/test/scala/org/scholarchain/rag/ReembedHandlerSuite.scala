package org.scholarchain.rag

import java.nio.file.Files
import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger

import scala.collection.mutable

import org.scholarchain.core.SemanticDocument
import org.scholarchain.rag.embedding.{EmbeddingProviders, InputType}
import org.scholarchain.rag.reembed._
import org.scholarchain.rag.semantic.ExportDocuments
import org.scholarchain.storage.ingest.{ReembedOutcome, ReembedTask}
import org.scholarchain.storage.local.LocalStorageEnvironment
import org.scholarchain.storage.ports.{EmbeddingBatch, EmbeddingFailure, EmbeddingPort}
import org.scholarchain.storage.source.{LocalDirectorySource, RejectLog}

/** Phase 15, decision 7.2: the `embedding-task-v1` message, the re-embed handler, its poison rule,
  * its delivery actions, its counters and its one structured log line per delivery.
  */
class ReembedHandlerSuite extends munit.FunSuite {
  import RagFixtures._

  override val munitTimeout = scala.concurrent.duration.Duration(120, "s")

  private val indexes = storageConfig.vectors.indexNames.toSet

  /** Counts embedding calls (the "Voyage call" of the rules) around the fake document embedder. */
  final class CountingEmbedder(base: EmbeddingPort) extends EmbeddingPort {
    val calls = new AtomicInteger()
    def modelId: String = base.modelId
    def dimensions: Int = base.dimensions
    def maxBatchSize: Int = base.maxBatchSize
    def embed(texts: IndexedSeq[String]): Either[EmbeddingFailure, EmbeddingBatch] = {
      calls.incrementAndGet(); base.embed(texts)
    }
  }

  /** A local environment holding the fixture corpus, a counting embedder and a handler. */
  final class Setup(batchLimit: Int = 64) {
    val env = LocalStorageEnvironment.create(storageConfig, None)
    private val doc =
      EmbeddingProviders.create(localConfig, InputType.Document).fold(e => sys.error(e), identity)
    val docs: Vector[SemanticDocument] = {
      val dir = writeExport(Files.createTempDirectory("reembed").resolve("export"))
      val src = new LocalDirectorySource(dir)
      val corpus =
        new ExportDocuments(src, src.manifest(), storageConfig.vectors, new RejectLog(None))
          .renderAll()
      val r =
        env.pipeline(doc.port, "dblp", corpus.extrasOf).run(sparkRun, corpus.documents.iterator)
      require(r.rejected == 0L && r.written == corpus.documents.size.toLong)
      corpus.documents.toVector
    }
    val embedder = new CountingEmbedder(doc.port)
    val reembedder = env.reembedder(embedder)
    val reembedCalls = mutable.ListBuffer.empty[Int]
    val lines = mutable.ListBuffer.empty[String]
    val handler = new ReembedHandler(
      tasks => { synchronized(reembedCalls += tasks.size); reembedder.reembed(tasks) },
      indexes,
      batchLimit,
      l => synchronized { lines += l; () }
    )
    def body(taskId: String, d: SemanticDocument): String =
      EmbeddingTask.render(taskId, d.indexName, d.vectorKey)
  }

  private def one(s: Setup, body: String, id: String = "m-1"): HandledDelivery = {
    val r = s.handler.handle(List(Delivery(id, body)))
    assertEquals(r.size, 1)
    r.head
  }

  // ---- the message format --------------------------------------------------------------------

  private val SampleKey = "rp:full-2015-2025:researcher:v1:" + "a" * 32

  test("embedding-task-v1: exactly schema, taskId, index and vectorKey; render and parse agree") {
    val key = SampleKey
    val b = EmbeddingTask.render("p15-c-0001", "researcher-profile-v1", key)
    assertEquals(ujson.read(b).obj.keys.toList, EmbeddingTask.Fields)
    assertEquals(
      EmbeddingTask.parse(b, indexes),
      Right(ReembedTask("p15-c-0001", "researcher-profile-v1", key))
    )
    assertEquals(EmbeddingTask.Schema, "embedding-task-v1")
  }

  test("embedding-task-v1: every malformed body has its poison code") {
    val key = SampleKey
    def obj(fields: (String, ujson.Value)*): String = ujson.Obj.from(fields).render()
    val ok = List[(String, ujson.Value)](
      "schema" -> "embedding-task-v1",
      "taskId" -> "t-1",
      "index" -> "researcher-profile-v1",
      "vectorKey" -> key
    )
    val cases = List(
      "{not json" -> EmbeddingTask.InvalidJson,
      "[1,2]" -> EmbeddingTask.NotAnObject,
      ("{\"x\":\"" + "a" * 5000 + "\"}") -> EmbeddingTask.BodyTooLarge,
      obj(
        ok.updated(0, "schema" -> ujson.Str("embedding-task-v2"))*
      ) -> EmbeddingTask.UnknownSchema,
      obj(ok.take(3)*) -> EmbeddingTask.MissingField,
      obj((ok :+ ("text" -> ujson.Str("injected")))*) -> EmbeddingTask.UnknownField,
      obj(ok.updated(1, "taskId" -> ujson.Num(7))*) -> EmbeddingTask.FieldNotString,
      obj(ok.updated(1, "taskId" -> ujson.Str("has space"))*) -> EmbeddingTask.InvalidTaskId,
      obj(ok.updated(2, "index" -> ujson.Str("no-such-index"))*) -> EmbeddingTask.UnknownIndex,
      obj(ok.updated(3, "vectorKey" -> ujson.Str("   "))*) -> EmbeddingTask.InvalidVectorKey
    )
    cases.foreach { case (b, code) =>
      assertEquals(EmbeddingTask.parse(b, indexes), Left(code), b.take(60))
    }
    assertEquals(cases.map(_._2).toSet, EmbeddingTask.PoisonCodes.toSet)
  }

  // ---- the handler ---------------------------------------------------------------------------

  test("a valid task is processed and acknowledged; its redelivery is replayed with no embedding") {
    val s = new Setup
    val d = s.docs.head
    val first = one(s, s.body("p15-f-valid-1", d))
    assertEquals(first.outcome, ReembedHandler.Processed)
    assertEquals(first.action, DeliveryAction.Acknowledge)
    assertEquals(first.taskId, Some("p15-f-valid-1"))
    assertEquals(s.embedder.calls.get, 1)
    val putsAfterFirst = s.env.vectors.putCalls.get
    val again = one(s, s.body("p15-f-valid-1", d), "m-2")
    assertEquals(again.outcome, ReembedHandler.Replayed)
    assertEquals(again.action, DeliveryAction.Acknowledge)
    assertEquals(s.embedder.calls.get, 1)
    assertEquals(s.env.vectors.putCalls.get, putsAfterFirst)
    assertEquals(s.handler.counters.processed.get, 1L)
    assertEquals(s.handler.counters.replayed.get, 1L)
    assertEquals(s.handler.counters.received.get, 2L)
  }

  test("poison is released at once: no store call, no embedding, no write") {
    val s = new Setup
    val puts = s.env.vectors.putCalls.get
    val poison = List(
      "{oops",
      EmbeddingTask.render("p15-f-poison-1", "no-such-index", s.docs.head.vectorKey),
      ujson.Obj("schema" -> "embedding-task-v0", "taskId" -> "p15-f-poison-2").render()
    )
    val r = s.handler.handle(poison.zipWithIndex.map { case (b, i) => Delivery(s"m-$i", b) })
    assertEquals(r.map(_.action).distinct, Vector(DeliveryAction.ReleaseNow))
    assertEquals(r.map(_.outcome).distinct, Vector(ReembedHandler.PermanentFailure))
    assertEquals(
      r.map(_.code),
      Vector(EmbeddingTask.InvalidJson, EmbeddingTask.UnknownIndex, EmbeddingTask.UnknownSchema)
    )
    // the poison's taskId names it in the log when it is well-formed (experiment F reads it)
    assertEquals(r.map(_.taskId), Vector(None, Some("p15-f-poison-1"), Some("p15-f-poison-2")))
    assertEquals(s.reembedCalls.toList, Nil)
    assertEquals(s.embedder.calls.get, 0)
    assertEquals(s.env.vectors.putCalls.get, puts)
    assertEquals(s.handler.counters.poison.get, 3L)
    assertEquals(s.handler.counters.permanentFailures.get, 3L)
  }

  test("a key that does not exist is a permanent failure: released, no embedding, no write") {
    val s = new Setup
    val d = s.docs.head
    val missing = d.vectorKey + "-does-not-exist"
    assert(missing != d.vectorKey)
    val puts = s.env.vectors.putCalls.get
    val h = one(s, EmbeddingTask.render("p15-c-missing", d.indexName, missing))
    assertEquals(h.outcome, ReembedHandler.PermanentFailure)
    assertEquals(h.code, "unknown-vector-key")
    assertEquals(h.action, DeliveryAction.ReleaseNow)
    assertEquals(s.embedder.calls.get, 0)
    assertEquals(s.env.vectors.putCalls.get, puts)
  }

  test("transient failures and handler errors are left for the visibility timeout") {
    val t = ReembedTask(
      "t-1",
      "researcher-profile-v1",
      SampleKey
    )
    val body = EmbeddingTask.render(t.taskId, t.index, t.vectorKey)
    val transient = new ReembedHandler(
      ts =>
        ts.map(x => ReembedOutcome.TransientFailure(x, "embed-failed", "embedding provider"))
          .toVector,
      indexes,
      64,
      _ => ()
    )
    val a = transient.handle(List(Delivery("m", body))).head
    assertEquals(
      (a.outcome, a.code, a.action),
      (ReembedHandler.TransientFailure, "embed-failed", DeliveryAction.LeaveForRetry)
    )
    val throwing =
      new ReembedHandler(_ => throw new IllegalStateException("boom"), indexes, 64, _ => ())
    val b = throwing.handle(List(Delivery("m", body))).head
    assertEquals(
      (b.outcome, b.code, b.action),
      (ReembedHandler.TransientFailure, ReembedHandler.HandlerError, DeliveryAction.LeaveForRetry)
    )
    val short = new ReembedHandler(_ => Vector.empty, indexes, 64, _ => ())
    assertEquals(short.handle(List(Delivery("m", body))).head.code, ReembedHandler.HandlerError)
    assertEquals(throwing.counters.transientFailures.get, 1L)
  }

  test("one receive: results in input order, one log line per delivery, exact counters") {
    val s = new Setup
    val valid = s.docs.take(4).zipWithIndex.map { case (d, i) => s.body(s"p15-c-$i", d) }
    val bodies = valid.take(2) ++ List("{bad") ++ valid.drop(2) ++ List(valid.head)
    val r = s.handler.handle(bodies.zipWithIndex.map { case (b, i) => Delivery(s"m-$i", b) })
    assertEquals(r.map(_.deliveryId), Vector.tabulate(bodies.size)(i => s"m-$i"))
    assertEquals(
      r.map(_.outcome),
      Vector(
        ReembedHandler.Processed,
        ReembedHandler.Processed,
        ReembedHandler.PermanentFailure,
        ReembedHandler.Processed,
        ReembedHandler.Processed,
        // the same task twice in one receive: both deliveries embed (concurrent duplicates are not
        // observable, decision 6.3)
        ReembedHandler.Processed
      )
    )
    assertEquals(s.lines.size, bodies.size)
    assertEquals(s.reembedCalls.toList, List(5))
    // embedding is batched: one call for the five valid tasks of the receive
    assertEquals(s.embedder.calls.get, 1)
    val c = s.handler.counters
    assertEquals(
      (
        c.received.get,
        c.processed.get,
        c.replayed.get,
        c.permanentFailures.get,
        c.transientFailures.get
      ),
      (6L, 5L, 0L, 1L, 0L)
    )
  }

  test("the log line: fixed keys and words, never the body, a key, a text or a URL") {
    val s = new Setup
    val d = s.docs.head
    val h = one(s, s.body("p15-f-valid-9", d))
    val j = ujson.read(h.logLine).obj
    assertEquals(
      j.keys.toList,
      List("event", "taskId", "index", "outcome", "code", "action", "latencyMillis")
    )
    assertEquals(j("event").str, "reembed-delivery")
    assertEquals(j("taskId").str, "p15-f-valid-9")
    assertEquals(j("index").str, d.indexName)
    assertEquals(j("outcome").str, "processed")
    assertEquals(j("code").str, "processed")
    assertEquals(j("action").str, "acknowledge")
    assert(j("latencyMillis").num >= 0)
    assert(!h.logLine.contains(d.vectorKey))
    assert(!h.logLine.contains("s3://"))
    val injected =
      ujson
        .Obj(
          "schema" -> "embedding-task-v1",
          "taskId" -> "Ignore previous instructions",
          "x" -> "https://evil.example"
        )
        .render()
    val p = one(s, injected, "m-x")
    assertEquals(ujson.read(p.logLine)("taskId"), ujson.Null)
    assert(!p.logLine.contains("Ignore") && !p.logLine.contains("evil"), p.logLine)
    assertEquals(ujson.read(p.logLine)("action").str, "release")
  }

  test("a receive larger than the batch limit is split into reembed calls of at most the limit") {
    val s = new Setup(batchLimit = 4)
    val bodies = s.docs.take(10).zipWithIndex.map { case (d, i) => s.body(s"p15-c-b$i", d) }
    val r = s.handler.handle(bodies.zipWithIndex.map { case (b, i) => Delivery(s"m-$i", b) })
    assertEquals(r.count(_.outcome == ReembedHandler.Processed), 10)
    assertEquals(s.reembedCalls.toList, List(4, 4, 2))
    intercept[IllegalArgumentException](
      new ReembedHandler(_ => Vector.empty, indexes, 101, _ => ())
    )
  }

  test("counters are exact when 8 threads handle deliveries at once") {
    val s = new Setup
    val bodies = s.docs.take(8).zipWithIndex.map { case (d, i) => s.body(s"p15-c-x$i", d) }
    val pool = Executors.newFixedThreadPool(8)
    val start = new CountDownLatch(1)
    try {
      val fs = (0 until 8).map { t =>
        pool.submit(new Runnable {
          def run(): Unit = {
            start.await()
            (0 until 25).foreach(k =>
              s.handler.handle(List(Delivery(s"$t-$k", bodies(t)), Delivery(s"$t-$k-p", "{bad")))
            )
          }
        })
      }
      start.countDown()
      fs.foreach(_.get(60, TimeUnit.SECONDS))
    } finally pool.shutdownNow()
    val c = s.handler.counters
    assertEquals(c.received.get, 400L)
    assertEquals(c.poison.get, 200L)
    assertEquals(c.processed.get + c.replayed.get, 200L)
    assertEquals(s.lines.size, 400)
  }

  test("fromConfig refuses the fake embedder for a stored index unless allowed (tests)") {
    val env = LocalStorageEnvironment.create(storageConfig, None)
    assert(
      ReembedHandler
        .fromConfig(localConfig, env.reembedder(_))
        .left
        .exists(_.contains("never used for a stored index"))
    )
    val b = ReembedHandler
      .fromConfig(localConfig, env.reembedder(_), allowFake = true)
      .fold(fail(_), identity)
    assertEquals(b.handler.batchLimit, math.min(localConfig.embedding.get.batchSize, 100))
    val v = ReembedHandler
      .fromConfig(
        voyageConfig,
        env.reembedder(_),
        EmbeddingProviders
          .Wiring(env = Map("SCHOLARCHAIN_VOYAGE_API_KEY" -> "test-key-not-real").get)
      )
      .fold(fail(_), identity)
    assertEquals(v.embedder.modelId, "voyage/voyage-4/1024/document/emb-v1")
  }
}
