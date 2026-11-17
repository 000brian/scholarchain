package org.scholarchain.contracts.runtime

import java.util.concurrent.atomic.AtomicInteger

import scala.collection.mutable

import org.scholarchain.contracts.runtime.CrossModuleHarness.*
import org.scholarchain.storage.{RetryingVectorIndex, RetryPolicy, Sleeper, StoreException}
import org.scholarchain.storage.aws.S3VectorsIndex
import org.scholarchain.storage.ports.VectorIndexPort
import software.amazon.awssdk.services.s3vectors.S3VectorsClient
import software.amazon.awssdk.services.s3vectors.model.{
  QueryVectorsRequest,
  QueryVectorsResponse,
  TooManyRequestsException,
  ValidationException
}

/** Category 17 (S3 Vectors throttling or transient failure): storage's real `S3VectorsIndex` and
  * `RetryingVectorIndex` over a stub `S3VectorsClient`, then under rag's workflow and the services
  * HTTP layer. Deterministic: a recording sleeper, no real waits. No network.
  */
class S3VectorsThrottlingStubSuite extends munit.FunSuite {

  override val munitTimeout = scala.concurrent.duration.Duration(300, "s")

  private val Bucket = "placeholder-vector-bucket"
  private val Index = "researcher-profile-v1"

  /** QueryVectors throttles (HTTP 429) `throttles` times, then answers with no hits; `-1` throttles
    * forever. `permanent` answers 400 ValidationException instead.
    */
  final class StubVectors(throttles: Int, permanent: Boolean = false) extends S3VectorsClient {
    val calls = new AtomicInteger(0)
    def serviceName(): String = "s3vectors"
    def close(): Unit = ()
    override def queryVectors(r: QueryVectorsRequest): QueryVectorsResponse = {
      val n = calls.incrementAndGet()
      if (permanent)
        throw ValidationException
          .builder()
          .message("Invalid query vector")
          .statusCode(400)
          .build()
          .asInstanceOf[Throwable]
      if (throttles < 0 || n <= throttles)
        throw TooManyRequestsException
          .builder()
          .message("Rate exceeded")
          .statusCode(429)
          .build()
          .asInstanceOf[Throwable]
      QueryVectorsResponse.builder().vectors(java.util.List.of()).build()
    }
  }

  final class RecordingSleeper extends Sleeper {
    val slept: mutable.ListBuffer[Long] = mutable.ListBuffer.empty
    def sleep(millis: Long): Unit = synchronized { slept += millis; () }
  }

  private val policy = RetryPolicy(maxRetries = 3, baseDelayMillis = 100L, maxDelayMillis = 1000L)

  private def retrying(stub: S3VectorsClient, sleeper: Sleeper): RetryingVectorIndex =
    new RetryingVectorIndex(new S3VectorsIndex(stub, Bucket, _ => Set.empty), policy, sleeper)

  private def q(ix: VectorIndexPort) =
    ix.query(Index, Array.fill(8)(0.1f), 5, None, returnMetadata = true)

  test("QueryVectors throttled twice is retried with bounded backoff and then answers") {
    val stub = new StubVectors(throttles = 2)
    val sleeper = new RecordingSleeper
    val ix = retrying(stub, sleeper)
    assertEquals(q(ix), Nil)
    assertEquals(stub.calls.get, 3)
    assertEquals(ix.counters.retries.get, 2L)
    assertEquals(ix.counters.calls.get, 3L)
    assertEquals(sleeper.slept.toList, List(100L, 200L))
  }

  test("QueryVectors throttled every time stops after maxRetries with a transient failure") {
    val stub = new StubVectors(throttles = -1)
    val sleeper = new RecordingSleeper
    val ix = retrying(stub, sleeper)
    val e = intercept[StoreException](q(ix))
    assert(e.transient)
    assertEquals(stub.calls.get, policy.maxRetries + 1)
    assertEquals(sleeper.slept.toList, List(100L, 200L, 400L))
  }

  test("a permanent QueryVectors failure (400) is never retried") {
    val stub = new StubVectors(throttles = 0, permanent = true)
    val sleeper = new RecordingSleeper
    val ix = retrying(stub, sleeper)
    val e = intercept[StoreException](q(ix))
    assert(!e.transient)
    assertEquals(stub.calls.get, 1)
    assertEquals(sleeper.slept.toList, Nil)
  }

  test("vectors throttled for good: the HTTP answer is 200, cites no semantic item, says why") {
    val stub = new StubVectors(throttles = -1)
    val s = serve(runtime(vectors = _ => retrying(stub, Sleeper.none)))
    try {
      val r = query(
        s,
        "Which researchers working on Dan Brown have the most distinct coauthors in 2015-2025?"
      )
      assertEquals(r.status, 200, r.body.take(300))
      assert(stub.calls.get >= policy.maxRetries + 1, s"calls ${stub.calls.get}")
      assertEquals(stub.calls.get % (policy.maxRetries + 1), 0)
      val a = r.json("answer")
      val text = a("answerText").str
      assert(text.contains("Semantic context unavailable") || a("abstained").bool, text)
      val evidence = a("evidence").arr
      val semanticIds = evidence
        .filter(_("evidenceType").str == "semantic_context")
        .map(_("evidenceId").str)
        .toSet
      assertEquals(semanticIds, Set.empty[String])
      assert(
        a("claims").arr.forall(c =>
          c("status").str != "verified" || !c("claimText").str.contains("profile")
        ),
        a("claims").render()
      )
    } finally s.close()
  }
}
