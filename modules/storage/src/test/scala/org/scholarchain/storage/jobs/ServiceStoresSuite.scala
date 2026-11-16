package org.scholarchain.storage.jobs

import java.util.concurrent.atomic.AtomicInteger

import org.scholarchain.storage.TestFixtures
import org.scholarchain.storage.aws.{AwsClientSettings, AwsSdkClients, AwsStorageEnvironment}
import org.scholarchain.storage.local.{InMemoryWorkQueue, LocalRuntimeEnvironment}
import org.scholarchain.storage.neptune.{NeptuneBuildSettings, NeptuneCsvBuilder}
import org.scholarchain.storage.ports._
import org.scholarchain.storage.source.{LocalDirectorySource, RejectLog}
import org.scholarchain.storage.Sleeper
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider
import software.amazon.awssdk.http.{ExecutableHttpRequest, HttpExecuteRequest, SdkHttpClient}
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3vectors.S3VectorsClient

class ServiceStoresSuite extends munit.FunSuite {
  import ServiceStoreFixtures._
  import TestFixtures._

  /** Placeholder queue URL (no account, no real queue). */
  private val queueUrl = "https://queue.example.invalid/example-jobs"
  private val t0 = ManualClock.DefaultStartMillis

  private final class S3Stub extends S3Client {
    def serviceName(): String = "s3"; def close(): Unit = ()
  }
  private final class VectorsStub extends S3VectorsClient {
    def serviceName(): String = "s3vectors"; def close(): Unit = ()
  }

  private def awsEnv(dynamo: DynamoDbClient, sqs: Option[StubSqsClient]) =
    AwsStorageEnvironment.create(
      runtimeStorageConfig,
      AwsSdkClients(new S3Stub, dynamo, new VectorsStub, None, sqs),
      None,
      Sleeper.none
    )

  /** One create -> claim -> succeed and one idempotency claim through a bundle. */
  private def lifecycle(stores: ServiceStores, now: Long): JobRecord = {
    val created = stores.jobs.create(newJob("job-1", now)) match {
      case Right(JobChange.Written(j)) => j
      case other                       => fail(other.toString)
    }
    assertEquals(stores.enqueueJob(created.jobId), Right(()))
    val claim = stores.idempotency.claim(
      "full",
      "key-1",
      "sha256:f",
      created.jobId,
      created.expiresAtSeconds,
      now
    )
    assert(claim.toOption.exists(_.isInstanceOf[IdempotencyClaim.Claimed]), claim.toString)
    val received = stores.jobQueue.receive(1)
    assertEquals(received.map(JobMessages.jobId), List(Right("job-1")))
    val job = stores.jobs.get("job-1", now).toOption.flatten.get
    val running = stores.jobs.claim(job, now + 1, now + 30001) match {
      case Right(JobChange.Written(j)) => j
      case other                       => fail(other.toString)
    }
    val done = stores.jobs.succeed(running, now + 2, "{}") match {
      case Right(JobChange.Written(j)) => j
      case other                       => fail(other.toString)
    }
    stores.jobQueue.ack(received.head)
    done
  }

  test("over: in-memory fixture bundle runs a whole job lifecycle") {
    val clock = new ManualClock()
    val f = inMemory(clock)
    val done = lifecycle(f.stores, clock.millis())
    assertEquals((done.state, done.attempts, done.result), (JobState.Succeeded, 1, Some("{}")))
    assertEquals((f.queue.size, f.queue.ackedCount), (0, 1L))
    assertEquals(f.kv.itemCount(f.querySessionTable), 2L, "one job item, one idempotency item")
  }

  test("local: the QuerySession table of the local runtime environment and an in-memory queue") {
    val exportDir = writeExport(tempDir("ss-export"))
    val src = new LocalDirectorySource(exportDir)
    val csv = tempDir("ss-neptune")
    new NeptuneCsvBuilder(
      src,
      src.manifest(),
      csv,
      NeptuneBuildSettings(Full, 2, gzip = true, "researcher-profile-v1", "semantic-context"),
      new RejectLog(None)
    ).build()
    val env = LocalRuntimeEnvironment.load(runtimeStorageConfig, exportDir, csv).toOption.get
    try {
      val clock = new ManualClock()
      assert(ServiceStores.local(env, 0L, clock).isLeft)
      assert(ServiceStores.local(env, InMemoryWorkQueue.MaxVisibilityMillis + 1, clock).isLeft)
      val stores = ServiceStores.local(env, 5000L, clock).toOption.get
      assert(stores.jobQueue.isInstanceOf[InMemoryWorkQueue])
      lifecycle(stores, clock.millis())
      assertEquals(env.storage.kv.itemCount(runtimeStorageConfig.dynamoDb.querySessionTable), 2L)
      // the queue follows the injected clock
      stores.enqueueJob("job-2")
      assertEquals(stores.jobQueue.receive(1).size, 1)
      assertEquals(stores.jobQueue.receive(1), Nil)
      clock.advance(5000L)
      assertEquals(stores.jobQueue.receive(1).map(_.receiveCount), List(2))
    } finally env.close()
  }

  test(
    "aws: DynamoDB QuerySession table and the SQS queue at the given URL; no visibility override"
  ) {
    val dyn = new ConditionalDynamoStub
    val sqs = new StubSqsClient
    val env = awsEnv(dyn, Some(sqs))
    val stores = ServiceStores.aws(env, queueUrl).toOption.get
    val done = lifecycle(stores, t0)
    assertEquals(done.state, JobState.Succeeded)
    assertEquals(dyn.itemCount, 2)
    assertEquals((sqs.sends.get, sqs.acks.get, sqs.size), (1, 1, 0))
    val rq = sqs.receives.head
    assertEquals(rq.queueUrl(), queueUrl)
    assertEquals(
      rq.visibilityTimeout(),
      null,
      "the queue's own visibility timeout (Terraform) applies"
    )
    assertEquals(rq.waitTimeSeconds().intValue, ServiceStores.SqsWaitSeconds)
    // release is a visibility change to 0
    stores.enqueueJob("job-9")
    stores.jobQueue.release(stores.jobQueue.receive(1).head)
    assertEquals(
      stores.jobQueue.receive(1).map(w => (w.item.id, w.receiveCount)),
      List(("job-9", 2))
    )
  }

  test("aws: refused without an SQS client or with a URL that is not https") {
    val env = awsEnv(new ConditionalDynamoStub, None)
    assert(ServiceStores.aws(env, queueUrl).left.exists(_.contains("SQS")))
    val env2 = awsEnv(new ConditionalDynamoStub, Some(new StubSqsClient))
    List("http://sqs.example/q", "", null, "https:// space", "https:///nohost").foreach(u =>
      assert(ServiceStores.aws(env2, u).isLeft, String.valueOf(u))
    )
    assert(ServiceStores.aws(env2, queueUrl, waitSeconds = 21).isLeft)
  }

  test("aws: built over real SDK clients, the bundle makes no network call") {
    val calls = new AtomicInteger()
    val http = new SdkHttpClient {
      def prepareRequest(r: HttpExecuteRequest): ExecutableHttpRequest = {
        calls.incrementAndGet(); throw new IllegalStateException("no request expected")
      }
      def close(): Unit = ()
    }
    val clients = AwsSdkClients.build(
      AwsClientSettings("us-east-1", 1000, 1000, 5000, 1),
      runtimeStorageConfig,
      Some(http),
      Some(AnonymousCredentialsProvider.create())
    )
    assert(clients.sqs.nonEmpty, "build creates the SQS client")
    val env = AwsStorageEnvironment.create(runtimeStorageConfig, clients, None)
    assert(ServiceStores.aws(env, queueUrl).isRight)
    assertEquals(calls.get, 0)
    env.close()
  }

  test("job messages: one job ID per message; a foreign message is Left") {
    val item = JobMessages.item("job-1").toOption.get
    assertEquals(JobMessages.jobId(ReceivedWork(item, "r", 1)), Right("job-1"))
    assert(JobMessages.item("job 1").isLeft)
    assert(JobMessages.jobId(ReceivedWork(WorkItem("job-1", "{}"), "r", 1)).isLeft)
    assert(JobMessages.jobId(ReceivedWork(WorkItem("job-2", item.payload), "r", 1)).isLeft)
    assert(JobMessages.jobId(ReceivedWork(WorkItem("job-1", "not json"), "r", 1)).isLeft)
  }

  test("a queue failure on enqueue is Left") {
    val failing = new WorkQueue {
      def enqueue(items: Seq[WorkItem]): Int =
        throw new org.scholarchain.storage.StoreException("queue down", true)
      def receive(max: Int): List[ReceivedWork] = Nil
      def ack(work: ReceivedWork): Unit = ()
      def release(work: ReceivedWork): Unit = ()
    }
    val f = inMemory(new ManualClock())
    val stores = ServiceStores.over(f.kv, f.querySessionTable, failing)
    assert(stores.enqueueJob("job-1").left.exists(_.contains("queue failure")))
  }
}
