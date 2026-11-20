package org.scholarchain.storage.jobs

import java.net.URI
import java.time.Clock

import scala.util.Try

import org.scholarchain.storage.StoreException
import org.scholarchain.storage.aws.{AwsStorageEnvironment, SqsRawReceiveQueue, SqsWorkQueue}
import org.scholarchain.storage.local.{InMemoryWorkQueue, LocalRuntimeEnvironment}
import org.scholarchain.storage.ports.{
  KeyValueStore,
  ReceiveQueue,
  ReceivedWork,
  WorkItem,
  WorkQueue
}

/** The stores of the REST services beyond [[org.scholarchain.storage.runtime.RuntimeStores]]: jobs
  * (QS4), idempotency records (QS5) and the job queue. Services reach them only through this bundle
  * and never build a key or a queue message by hand ([[JobMessages]]).
  *
  * Thread-safety: `jobs` and `idempotency` are thread-safe; `jobQueue` is thread-safe
  * ([[InMemoryWorkQueue]] synchronizes; [[SqsWorkQueue]] uses the thread-safe SDK client).
  */
trait ServiceStores {
  def jobs: JobStore
  def idempotency: IdempotencyStore

  /** The at-least-once job queue. Each message carries one job ID ([[JobMessages]]). */
  def jobQueue: WorkQueue

  /** Enqueues one message for `jobId`. Left on a queue failure (after the SDK call). */
  def enqueueJob(jobId: String): Either[String, Unit] =
    for {
      item <- JobMessages.item(jobId)
      _ <- ServiceStores.guardQueue(jobQueue.enqueue(List(item)))
    } yield ()
}

object ServiceStores {

  /** Long-poll wait of the SQS job queue receive: short, so a worker loop notices shutdown within a
    * second, and below any reasonable `apiCallTimeoutMillis` of the SDK client.
    */
  val SqsWaitSeconds: Int = 1

  /** The bundle over one QuerySession table of `kv` and `queue` (tests, fixtures, both factories).
    */
  def over(kv: KeyValueStore, querySessionTable: String, queue: WorkQueue): ServiceStores = {
    require(kv != null && queue != null, "a key-value store and a queue are required")
    require(querySessionTable != null && querySessionTable.nonEmpty, "a table name is required")
    val j = new TableJobStore(kv, querySessionTable)
    val i = new TableIdempotencyStore(kv, querySessionTable)
    new ServiceStores {
      val jobs: JobStore = j
      val idempotency: IdempotencyStore = i
      val jobQueue: WorkQueue = queue
    }
  }

  /** Local runs: the QuerySession table of the in-memory tables of `env` and a new
    * [[InMemoryWorkQueue]] hiding received messages for `visibilityMillis` by `clock` (services
    * pass their own clock; storage reads no clock of its own).
    */
  def local(
      env: LocalRuntimeEnvironment,
      visibilityMillis: Long,
      clock: Clock
  ): Either[String, ServiceStores] =
    for {
      _ <- Either.cond(env != null, (), "no local runtime environment")
      _ <- Either.cond(clock != null, (), "no clock")
      _ <- Either.cond(
        visibilityMillis >= 1 && visibilityMillis <= InMemoryWorkQueue.MaxVisibilityMillis,
        (),
        s"visibilityMillis must be in 1..${InMemoryWorkQueue.MaxVisibilityMillis}"
      )
    } yield over(
      env.storage.kv,
      env.storage.config.dynamoDb.querySessionTable,
      new InMemoryWorkQueue(visibilityMillis, clock)
    )

  /** AWS: the QuerySession DynamoDB table of `env` (with storage's bounded retry) and the SQS job
    * queue at `queueUrl` (from `service.jobQueueUrl`; its visibility timeout is the queue's own,
    * set by Terraform and longer than `service.jobLeaseMillis`). No network call.
    *
    * Left when the URL is not an `https://` URL with a host, or `env` has no SQS client (an
    * environment over stub clients built without one).
    */
  def aws(
      env: AwsStorageEnvironment,
      queueUrl: String,
      waitSeconds: Int = SqsWaitSeconds
  ): Either[String, ServiceStores] =
    for {
      _ <- Either.cond(env != null, (), "no AWS storage environment")
      url <- validQueueUrl(queueUrl)
      _ <- Either.cond(waitSeconds >= 0 && waitSeconds <= 20, (), "waitSeconds must be in 0..20")
      client <- env.sqsClient.toRight("the AWS storage environment has no SQS client")
    } yield over(
      env.kv,
      env.config.dynamoDb.querySessionTable,
      new SqsWorkQueue(client, url, waitSeconds, None)
    )

  /** AWS: the consuming side of the SQS embedding queue at `queueUrl` (from
    * `service.embeddingQueueUrl`), for the services' re-embed worker. Bodies are delivered raw and
    * never parsed ([[SqsRawReceiveQueue]]): `WorkItem.id` is the SQS MessageId, `WorkItem.payload`
    * the message body unchanged. `ack` = DeleteMessage, `release` = ChangeMessageVisibility 0; no
    * send. Receives use the queue's own visibility timeout (Terraform). No network call.
    *
    * Left when the URL is not an `https://` URL with a host, `waitSeconds` is outside 0..20, or
    * `env` has no SQS client. No Left message names the URL. Local runs need no factory: an
    * [[InMemoryWorkQueue]] is a [[ReceiveQueue]] whose payload is the raw body.
    */
  def embeddingQueue(
      env: AwsStorageEnvironment,
      queueUrl: String,
      waitSeconds: Int = SqsWaitSeconds
  ): Either[String, ReceiveQueue] =
    for {
      _ <- Either.cond(env != null, (), "no AWS storage environment")
      url <- validQueueUrl(queueUrl, "embedding queue")
      _ <- Either.cond(waitSeconds >= 0 && waitSeconds <= 20, (), "waitSeconds must be in 0..20")
      client <- env.sqsClient.toRight("the AWS storage environment has no SQS client")
    } yield new SqsRawReceiveQueue(client, url, waitSeconds)

  private def validQueueUrl(u: String, name: String = "job queue"): Either[String, String] =
    Option(u)
      .filter(s => s.startsWith("https://") && !s.exists(_.isWhitespace))
      .flatMap(s => Try(URI.create(s)).toOption.filter(x => x.getHost != null).map(_ => s))
      .toRight(s"the $name URL must be an https:// URL with a host")

  private[jobs] def guardQueue[A](f: => A): Either[String, A] =
    try Right(f)
    catch {
      case e: StoreException =>
        Left(s"queue failure (${if (e.transient) "transient" else "permanent"}): ${e.getMessage}")
    }
}

/** The job queue message format: one job ID per message. */
object JobMessages {
  val Format = "scholarchain.job-message/1"

  /** The message for `jobId` (Left when the ID is malformed). */
  def item(jobId: String): Either[String, WorkItem] =
    JobItems
      .checkJobId(jobId)
      .map(id => WorkItem(id, ujson.Obj("format" -> Format, "jobId" -> id).render()))

  /** The job ID a received message carries (Left for a message of another format). */
  def jobId(work: ReceivedWork): Either[String, String] =
    for {
      w <- Option(work).toRight("no message")
      o <- Try(ujson.read(w.item.payload).obj).toOption.toRight("job message is not a JSON object")
      _ <- Either.cond(o.get("format").exists(_.strOpt.contains(Format)), (), "not a job message")
      id <- o.get("jobId").flatMap(_.strOpt).toRight("job message has no jobId")
      _ <- Either.cond(id == w.item.id, (), "job message ID mismatch")
      out <- JobItems.checkJobId(id)
    } yield out
}
