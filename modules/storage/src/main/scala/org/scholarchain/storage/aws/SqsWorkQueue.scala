package org.scholarchain.storage.aws

import scala.jdk.CollectionConverters._

import org.scholarchain.storage.ports.{ReceivedWork, WorkItem, WorkQueue}
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model._

/** The embedding work queue on SQS (standard queue, at-least-once delivery). The queue URL comes
  * from configuration or the deploy environment. Poison handling is SQS's redrive policy (a
  * dead-letter queue with maxReceiveCount), defined in Terraform. Duplicated deliveries are
  * harmless: the ingest pipeline skips vectors the ArtifactRegistry already holds.
  *
  * `ack` acknowledges a processed message (SQS DeleteMessage on its receipt handle); this is the
  * queue's consumption protocol, not data removal. `release` sets the visibility timeout to 0.
  *
  * @param visibilitySeconds
  *   the visibility timeout sent with every receive; None uses the queue's own visibility timeout
  *   (the Phase 10 job queue: set by Terraform, longer than `service.jobLeaseMillis`)
  */
final class SqsWorkQueue(
    client: SqsClient,
    queueUrl: String,
    waitSeconds: Int,
    visibilitySeconds: Option[Int]
) extends WorkQueue {
  require(waitSeconds >= 0 && waitSeconds <= 20, "waitSeconds must be in 0..20")
  require(
    visibilitySeconds.forall(v => v >= 0 && v <= 43200),
    "visibilitySeconds must be in 0..43200"
  )

  /** A queue that sends `visibilitySeconds` with every receive (the embedding work queue). */
  def this(client: SqsClient, queueUrl: String, waitSeconds: Int, visibilitySeconds: Int) =
    this(client, queueUrl, waitSeconds, Some(visibilitySeconds))

  def enqueue(items: Seq[WorkItem]): Int =
    items
      .grouped(10)
      .map { group =>
        val entries = group.zipWithIndex.map { case (i, n) =>
          SendMessageBatchRequestEntry
            .builder()
            .id(n.toString)
            .messageBody(ujson.Obj("id" -> i.id, "payload" -> i.payload).render())
            .build()
        }
        val r = AwsErrors.guard(
          client.sendMessageBatch(
            SendMessageBatchRequest.builder().queueUrl(queueUrl).entries(entries.asJava).build()
          )
        )
        if (!r.failed().isEmpty)
          throw new org.scholarchain.storage.StoreException(
            s"${r.failed().size} of ${group.size} messages were not sent: ${r.failed().asScala.map(_.code()).distinct.mkString(",")}",
            true
          )
        r.successful().size
      }
      .sum

  def receive(max: Int): List[ReceivedWork] = {
    val b = ReceiveMessageRequest
      .builder()
      .queueUrl(queueUrl)
      .maxNumberOfMessages(math.max(1, math.min(10, max)))
      .waitTimeSeconds(waitSeconds)
      .messageSystemAttributeNames(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT)
    visibilitySeconds.foreach(v => b.visibilityTimeout(v))
    val r = AwsErrors.guard(client.receiveMessage(b.build()))
    r.messages().asScala.toList.map { m =>
      val o = ujson.read(m.body()).obj
      val count = Option(m.attributes().get(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT))
        .map(_.toInt)
        .getOrElse(1)
      ReceivedWork(WorkItem(o("id").str, o("payload").str), m.receiptHandle(), count)
    }
  }

  def ack(work: ReceivedWork): Unit = {
    AwsErrors.guard(
      client.deleteMessage(
        DeleteMessageRequest.builder().queueUrl(queueUrl).receiptHandle(work.receipt).build()
      )
    )
    ()
  }

  def release(work: ReceivedWork): Unit = {
    AwsErrors.guard(
      client.changeMessageVisibility(
        ChangeMessageVisibilityRequest
          .builder()
          .queueUrl(queueUrl)
          .receiptHandle(work.receipt)
          .visibilityTimeout(0)
          .build()
      )
    )
    ()
  }
}

object SqsWorkQueue {
  def create(
      settings: AwsClientSettings,
      queueUrl: String,
      waitSeconds: Int,
      visibilitySeconds: Int
  ): SqsWorkQueue =
    new SqsWorkQueue(
      SqsClient
        .builder()
        .region(settings.awsRegion)
        .httpClient(settings.httpClient())
        .overrideConfiguration(settings.overrides())
        .build(),
      queueUrl,
      waitSeconds,
      visibilitySeconds
    )
}
