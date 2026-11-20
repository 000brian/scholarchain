package org.scholarchain.storage.jobs

import scala.jdk.CollectionConverters._

import org.scholarchain.storage.{Sleeper, StoreException, TestFixtures}
import org.scholarchain.storage.aws.{AwsSdkClients, AwsStorageEnvironment, SqsRawReceiveQueue}
import org.scholarchain.storage.local.InMemoryWorkQueue
import org.scholarchain.storage.ports._
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3vectors.S3VectorsClient
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model._

/** The consuming port of the embedding queue ([[ServiceStores.embeddingQueue]]): raw bodies, never
  * a throw on body content, ack = DeleteMessage, release = ChangeMessageVisibility 0.
  */
class EmbeddingQueueSuite extends munit.FunSuite {
  import TestFixtures._

  /** Placeholder queue URL (no account, no real queue). */
  private val queueUrl = "https://queue.example.invalid/example-embedding"

  private val validTask =
    ujson
      .Obj(
        "schema" -> "embedding-task-v1",
        "taskId" -> "task-1",
        "index" -> "researcher-profile-v1",
        "vectorKey" -> "a:1"
      )
      .render()

  private final class S3Stub extends S3Client {
    def serviceName(): String = "s3"; def close(): Unit = ()
  }
  private final class VectorsStub extends S3VectorsClient {
    def serviceName(): String = "s3vectors"; def close(): Unit = ()
  }

  private def awsEnv(sqs: Option[SqsClient]) =
    AwsStorageEnvironment.create(
      runtimeStorageConfig,
      AwsSdkClients(
        new S3Stub,
        new ConditionalDynamoStub: DynamoDbClient,
        new VectorsStub,
        None,
        sqs
      ),
      None,
      Sleeper.none
    )

  private def queueOver(sqs: SqsClient): ReceiveQueue =
    ServiceStores.embeddingQueue(awsEnv(Some(sqs)), queueUrl).toOption.get

  test("a valid embedding-task-v1 body passes through raw, keyed by the SQS message ID") {
    val sqs = new StubSqsClient
    val id = sqs.putRaw(validTask)
    val got = queueOver(sqs).receive(10)
    assertEquals(
      got.map(w => (w.item.id, w.item.payload, w.receiveCount)),
      List((id, validTask, 1))
    )
    val rq = sqs.receives.head
    assertEquals(rq.queueUrl(), queueUrl)
    assertEquals(rq.maxNumberOfMessages().intValue, 10)
    assertEquals(rq.waitTimeSeconds().intValue, ServiceStores.SqsWaitSeconds)
    assertEquals(rq.visibilityTimeout(), null, "the queue's own visibility timeout applies")
    assert(
      rq.messageSystemAttributeNames()
        .asScala
        .contains(
          MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT
        )
    )
  }

  test("non-JSON, empty and foreign bodies are delivered as-is; no message of the batch is lost") {
    val sqs = new StubSqsClient
    val bodies = List(validTask, "not json {", "", """{"id":"x"}""", "\u0000￿", validTask)
    val ids = bodies.map(sqs.putRaw)
    val got = queueOver(sqs).receive(10)
    assertEquals(got.map(w => (w.item.id, w.item.payload)), ids.zip(bodies))
    assertEquals(got.map(_.receipt).distinct.size, bodies.size)
  }

  test("receive bounds max to 1..10") {
    val sqs = new StubSqsClient
    val q = queueOver(sqs)
    q.receive(0); q.receive(50)
    assertEquals(sqs.receives.map(_.maxNumberOfMessages().intValue).toList, List(1, 10))
  }

  test("a missing body, ID or unreadable receive count does not throw") {
    val odd = new SqsClient {
      def serviceName(): String = "sqs"; def close(): Unit = ()
      override def receiveMessage(r: ReceiveMessageRequest): ReceiveMessageResponse =
        ReceiveMessageResponse
          .builder()
          .messages(
            Message.builder().receiptHandle("r1").build(),
            Message
              .builder()
              .messageId("m2")
              .receiptHandle("r2")
              .body("b")
              .attributes(Map(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT -> "x").asJava)
              .build(),
            Message
              .builder()
              .messageId("m3")
              .receiptHandle("r3")
              .body("c")
              .attributes(Map(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT -> "4").asJava)
              .build()
          )
          .build()
    }
    val got = new SqsRawReceiveQueue(odd, queueUrl, 0).receive(10)
    assertEquals(
      got.map(w => (w.item.id, w.item.payload, w.receipt, w.receiveCount)),
      List(("", "", "r1", 1), ("m2", "b", "r2", 1), ("m3", "c", "r3", 4))
    )
  }

  test("ack is DeleteMessage on the receipt handle; release is ChangeMessageVisibility 0") {
    val sqs = new StubSqsClient
    sqs.putRaw(validTask)
    sqs.putRaw("poison")
    val q = queueOver(sqs)
    val List(task, poison) = q.receive(10): @unchecked
    q.release(poison)
    assertEquals(sqs.visibilityTimeouts.toList, List(0))
    assertEquals((sqs.visibilityChanges.get, sqs.acks.get), (1, 0))
    q.ack(task)
    assertEquals(sqs.deletedReceipts.toList, List(task.receipt))
    assertEquals(sqs.size, 1, "only the acknowledged message is gone")
    // the released message is visible again with its receive count advanced
    assertEquals(
      q.receive(10).map(w => (w.item.payload, w.receiveCount)),
      List(("poison", 2))
    )
    assertEquals(sqs.sends.get, 0, "the consumer never sends")
  }

  test("an SQS failure surfaces as a StoreException that does not name the queue URL") {
    val failing = new SqsClient {
      def serviceName(): String = "sqs"; def close(): Unit = ()
      override def receiveMessage(r: ReceiveMessageRequest): ReceiveMessageResponse =
        throw SqsException.builder().statusCode(503).message("unavailable").build()
    }
    val e = intercept[StoreException](queueOver(failing).receive(1))
    assert(e.transient)
    assert(!e.getMessage.contains(queueUrl))
  }

  test("refused without an SQS client, a non-https URL or a bad wait; Left never names the URL") {
    val noSqs = ServiceStores.embeddingQueue(awsEnv(None), queueUrl)
    assert(noSqs.left.exists(_.contains("SQS")), noSqs.toString)
    val env = awsEnv(Some(new StubSqsClient))
    List("http://sqs.example/q", "", null, "https:// space", "https:///nohost").foreach { u =>
      val r = ServiceStores.embeddingQueue(env, u)
      assert(r.left.exists(_.contains("embedding queue URL")), String.valueOf(u))
      assert(!r.left.exists(m => u != null && u.nonEmpty && m.contains(u)), String.valueOf(u))
    }
    assert(ServiceStores.embeddingQueue(env, queueUrl, waitSeconds = 21).isLeft)
    assert(ServiceStores.embeddingQueue(env, queueUrl, waitSeconds = -1).isLeft)
    assert(ServiceStores.embeddingQueue(null, queueUrl).isLeft)
  }

  test("the job queue still parses its own message format (unchanged)") {
    val sqs = new StubSqsClient
    val stores = ServiceStores.aws(awsEnv(Some(sqs)), queueUrl).toOption.get
    assertEquals(stores.enqueueJob("job-1"), Right(()))
    assertEquals(stores.jobQueue.receive(1).map(JobMessages.jobId), List(Right("job-1")))
    assert(ServiceStores.aws(awsEnv(Some(sqs)), "").left.exists(_.contains("job queue URL")))
  }

  test("locally an InMemoryWorkQueue is a ReceiveQueue whose payload is the raw body") {
    val q = new InMemoryWorkQueue(1000L, new ManualClock())
    q.enqueue(List(WorkItem("d1", "not json")))
    val rq: ReceiveQueue = q
    assertEquals(rq.receive(1).map(_.item.payload), List("not json"))
  }
}
