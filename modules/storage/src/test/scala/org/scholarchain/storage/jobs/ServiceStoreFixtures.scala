package org.scholarchain.storage.jobs

import java.time.{Clock, Instant, ZoneId, ZoneOffset}
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicLong}

import scala.collection.mutable
import scala.jdk.CollectionConverters._

import org.scholarchain.core.RunId
import org.scholarchain.storage.StoreException
import org.scholarchain.storage.TestFixtures
import org.scholarchain.storage.local.{InMemoryKeyValueStore, InMemoryWorkQueue}
import org.scholarchain.storage.ports._
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.{
  AttributeValue => Av,
  ConditionalCheckFailedException,
  DynamoDbException,
  GetItemRequest,
  GetItemResponse,
  PutItemRequest,
  PutItemResponse
}
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.{
  ChangeMessageVisibilityRequest,
  ChangeMessageVisibilityResponse,
  DeleteMessageRequest,
  DeleteMessageResponse,
  Message,
  MessageSystemAttributeName,
  ReceiveMessageRequest,
  ReceiveMessageResponse,
  SendMessageBatchRequest,
  SendMessageBatchResponse,
  SendMessageBatchResultEntry
}

/** A clock tests move by hand (epoch milliseconds). Thread-safe. */
final class ManualClock(startMillis: Long = ManualClock.DefaultStartMillis) extends Clock {
  private val now = new AtomicLong(startMillis)
  override def millis(): Long = now.get
  def instant(): Instant = Instant.ofEpochMilli(now.get)
  def getZone: ZoneId = ZoneOffset.UTC
  override def withZone(zone: ZoneId): Clock = this

  /** Moves the clock forward by `deltaMillis` and returns the new time. */
  def advance(deltaMillis: Long): Long = now.addAndGet(deltaMillis)
  def set(millis: Long): Unit = now.set(millis)
}

object ManualClock {

  /** 2026-11-15T00:00:00Z. */
  val DefaultStartMillis: Long = 1794700800000L
}

/** A [[KeyValueStore]] that fails every call with a [[StoreException]] while `failing` is set
  * (circuit-breaker and 502 tests); otherwise it delegates.
  */
final class ToggleKeyValueStore(underlying: KeyValueStore) extends KeyValueStore {
  val failing = new AtomicBoolean(false)
  val calls = new AtomicInteger()
  private def check(): Unit = {
    calls.incrementAndGet()
    if (failing.get) throw new StoreException("injected store failure", true)
  }
  def putIfAbsent(table: String, item: Item): ConditionalResult = {
    check(); underlying.putIfAbsent(table, item)
  }
  def putIfVersion(table: String, item: Item, expectedVersion: Long): ConditionalResult = {
    check(); underlying.putIfVersion(table, item, expectedVersion)
  }
  def get(table: String, key: ItemKey): Option[Item] = { check(); underlying.get(table, key) }
  def query(
      table: String,
      pk: String,
      skPrefix: Option[String],
      limit: Int,
      descending: Boolean
  ): List[Item] = { check(); underlying.query(table, pk, skPrefix, limit, descending) }
}

/** A DynamoDB client stub that evaluates the two condition expressions storage sends (put-if-absent
  * and put-if-version) on an in-memory map, so the DynamoDB adapter path is tested without network.
  * Any other PutItem (an unconditional put) is rejected.
  *
  * `applyThenFail(n)`: the next `n` PutItem calls are applied and then answered with HTTP 500 (a
  * lost response, so storage's retry repeats the write). Thread-safe.
  */
final class ConditionalDynamoStub extends DynamoDbClient {
  private val items = new ConcurrentHashMap[(String, String, String), java.util.Map[String, Av]]()
  val puts = new AtomicInteger()
  val gets = new AtomicInteger()
  val conditions: mutable.Buffer[String] =
    java.util.Collections.synchronizedList(new java.util.ArrayList[String]()).asScala
  private val failAfterApply = new AtomicInteger()

  def applyThenFail(n: Int): Unit = failAfterApply.set(n)
  def serviceName(): String = "dynamodb"
  def close(): Unit = ()

  private def ccf() =
    ConditionalCheckFailedException
      .builder()
      .message("The conditional request failed")
      .statusCode(400)
      .build()

  override def putItem(r: PutItemRequest): PutItemResponse = synchronized {
    puts.incrementAndGet()
    val m = r.item()
    val k = (r.tableName(), m.get("pk").s(), m.get("sk").s())
    val existing = Option(items.get(k))
    val cond = r.conditionExpression()
    conditions += cond
    cond match {
      case "attribute_not_exists(pk)" =>
        if (existing.nonEmpty) throw ccf()
      case "attribute_exists(pk) AND #v = :expected" =>
        val attr = r.expressionAttributeNames().get("#v")
        val expected = r.expressionAttributeValues().get(":expected").n()
        if (!existing.exists(e => Option(e.get(attr)).exists(_.n() == expected))) throw ccf()
      case other =>
        throw new IllegalArgumentException(s"unexpected PutItem condition: $other")
    }
    items.put(k, new java.util.HashMap[String, Av](m))
    if (failAfterApply.get > 0) {
      failAfterApply.decrementAndGet()
      throw DynamoDbException.builder().message("response lost").statusCode(500).build()
    }
    PutItemResponse.builder().build()
  }

  override def getItem(r: GetItemRequest): GetItemResponse = {
    gets.incrementAndGet()
    val k = (r.tableName(), r.key().get("pk").s(), r.key().get("sk").s())
    Option(items.get(k)) match {
      case Some(m) => GetItemResponse.builder().item(m).build()
      case None    => GetItemResponse.builder().build()
    }
  }

  def itemCount: Int = items.size
}

/** An SQS client stub: a FIFO list of messages with receive counts and no visibility timeout (a
  * received message stays hidden until `changeMessageVisibility(0)`); records every request. No
  * network. Thread-safe.
  */
final class StubSqsClient extends SqsClient {
  private final case class Msg(id: String, body: String, var receives: Int, var hidden: Boolean)
  private val queue = mutable.LinkedHashMap.empty[String, Msg]
  private var seq = 0
  val sends = new AtomicInteger()
  val receives: mutable.Buffer[ReceiveMessageRequest] = mutable.ArrayBuffer.empty
  val acks = new AtomicInteger()
  val visibilityChanges = new AtomicInteger()

  def serviceName(): String = "sqs"
  def close(): Unit = ()

  override def sendMessageBatch(r: SendMessageBatchRequest): SendMessageBatchResponse =
    synchronized {
      sends.incrementAndGet()
      val ok = r.entries().asScala.toList.map { e =>
        seq += 1
        val id = s"m$seq"
        queue.update(id, Msg(id, e.messageBody(), 0, hidden = false))
        SendMessageBatchResultEntry.builder().id(e.id()).messageId(id).build()
      }
      SendMessageBatchResponse.builder().successful(ok.asJava).failed(java.util.List.of()).build()
    }

  override def receiveMessage(r: ReceiveMessageRequest): ReceiveMessageResponse = synchronized {
    receives += r
    val got = queue.values.filterNot(_.hidden).take(r.maxNumberOfMessages().intValue).toList
    val msgs = got.map { m =>
      m.receives += 1
      m.hidden = true
      Message
        .builder()
        .messageId(m.id)
        .receiptHandle(s"${m.id}:${m.receives}")
        .body(m.body)
        .attributes(
          Map(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT -> m.receives.toString).asJava
        )
        .build()
    }
    ReceiveMessageResponse.builder().messages(msgs.asJava).build()
  }

  override def deleteMessage(r: DeleteMessageRequest): DeleteMessageResponse = synchronized {
    acks.incrementAndGet()
    deletedReceipts += r.receiptHandle()
    queue.remove(r.receiptHandle().takeWhile(_ != ':'))
    DeleteMessageResponse.builder().build()
  }

  override def changeMessageVisibility(
      r: ChangeMessageVisibilityRequest
  ): ChangeMessageVisibilityResponse = synchronized {
    visibilityChanges.incrementAndGet()
    visibilityTimeouts += r.visibilityTimeout().intValue
    if (r.visibilityTimeout().intValue == 0)
      queue.get(r.receiptHandle().takeWhile(_ != ':')).foreach(_.hidden = false)
    ChangeMessageVisibilityResponse.builder().build()
  }

  def size: Int = synchronized(queue.size)

  /** Puts one message with `body` exactly as given (a producer outside storage sending raw);
    * returns its message ID.
    */
  def putRaw(body: String): String = synchronized {
    seq += 1
    val id = s"m$seq"
    queue.update(id, Msg(id, body, 0, hidden = false))
    id
  }

  /** The visibility timeouts of every ChangeMessageVisibility call, in order. */
  val visibilityTimeouts: mutable.Buffer[Int] = mutable.ArrayBuffer.empty

  /** The receipt handles of every DeleteMessage call, in order. */
  val deletedReceipts: mutable.Buffer[String] = mutable.ArrayBuffer.empty
}

/** In-memory service stores and the parts behind them, for tests. */
final case class InMemoryServiceStores(
    kv: InMemoryKeyValueStore,
    queue: InMemoryWorkQueue,
    stores: ServiceStores,
    querySessionTable: String
)

/** Reusable fixtures for jobs, idempotency and the job queue (services' tests use them too: the
  * storage test tree is on services' test classpath).
  */
object ServiceStoreFixtures {

  /** The QuerySession table of the storage test configuration (a placeholder name). */
  def querySessionTable: String = TestFixtures.storageConfig.dynamoDb.querySessionTable

  /** Default visibility timeout of the in-memory job queue in tests. */
  val VisibilityMillis: Long = 60000L

  /** Fresh in-memory tables of the test configuration and a job queue driven by `clock`. */
  def inMemory(clock: Clock, visibilityMillis: Long = VisibilityMillis): InMemoryServiceStores = {
    val kv = new InMemoryKeyValueStore(TestFixtures.storageConfig.tableNames)
    val q = new InMemoryWorkQueue(visibilityMillis, clock)
    InMemoryServiceStores(kv, q, ServiceStores.over(kv, querySessionTable, q), querySessionTable)
  }

  val queryRunId: RunId =
    RunId.of("rag.query", "rag-runtime/2", "sha256:q", "sha256:c").toOption.get
  val traceId: String = "0af7651916cd43dd8448eb211c80319c"
  val request: JobRequest = JobRequest(
    "Who has the most distinct coauthors?",
    Some("full-2015-2025"),
    None,
    includeEvidence = true
  )

  /** A new job created at `nowMillis` that expires one hour later. */
  def newJob(
      jobId: String,
      nowMillis: Long,
      mode: JobMode = JobMode.Async,
      ttlSeconds: Long = 3600L
  ): NewJob =
    NewJob(jobId, mode, queryRunId, request, traceId, nowMillis, nowMillis / 1000L + ttlSeconds)
}
