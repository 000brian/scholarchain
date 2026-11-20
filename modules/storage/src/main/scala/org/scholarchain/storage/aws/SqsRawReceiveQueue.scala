package org.scholarchain.storage.aws

import scala.jdk.CollectionConverters._

import org.scholarchain.storage.ports.{ReceiveQueue, ReceivedWork, WorkItem}
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model._

/** The consuming side of an SQS queue whose message bodies are delivered raw (the embedding queue,
  * message format `embedding-task-v1`, sent by producers outside storage).
  *
  * `receive` never interprets a body: each message becomes `WorkItem(<SQS MessageId>, <body>)` with
  * the body unchanged (a missing body is the empty string), so a body that is not JSON, empty, or
  * of a foreign format is delivered as-is and never costs the other messages of the batch. The
  * consumer classifies bodies (rag's `ReembedHandler`). A missing or unreadable
  * `ApproximateReceiveCount` is read as 1.
  *
  * `ack` is SQS DeleteMessage on the receipt handle (the queue's consumption protocol, not data
  * removal); `release` is ChangeMessageVisibility 0. There is no send: the worker only consumes.
  * Every receive uses the queue's own visibility timeout (Terraform). No message built here names
  * the queue URL (an SDK failure is wrapped by [[AwsErrors]] with the SDK's own text).
  *
  * Thread-safety: thread-safe (stateless over the thread-safe SDK client).
  */
final class SqsRawReceiveQueue(client: SqsClient, queueUrl: String, waitSeconds: Int)
    extends ReceiveQueue {
  require(client != null, "an SQS client is required")
  require(queueUrl != null && queueUrl.nonEmpty, "a queue URL is required")
  require(waitSeconds >= 0 && waitSeconds <= 20, "waitSeconds must be in 0..20")

  def receive(max: Int): List[ReceivedWork] = {
    val rq = ReceiveMessageRequest
      .builder()
      .queueUrl(queueUrl)
      .maxNumberOfMessages(math.max(1, math.min(10, max)))
      .waitTimeSeconds(waitSeconds)
      .messageSystemAttributeNames(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT)
      .build()
    val r = AwsErrors.guard(client.receiveMessage(rq))
    Option(r.messages()).map(_.asScala.toList).getOrElse(Nil).filter(_ != null).map { m =>
      val count = Option(m.attributes())
        .flatMap(a => Option(a.get(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT)))
        .flatMap(_.trim.toIntOption)
        .filter(_ >= 1)
        .getOrElse(1)
      ReceivedWork(
        WorkItem(Option(m.messageId()).getOrElse(""), Option(m.body()).getOrElse("")),
        Option(m.receiptHandle()).getOrElse(""),
        count
      )
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
