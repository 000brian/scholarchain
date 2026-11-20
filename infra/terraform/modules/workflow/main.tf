# workflow (decision 6 area 11): the job and embedding SQS queues with their
# dead-letter queues, and (sfn.tf) the bounded pipeline Step Functions state
# machine with its role. EventBridge is not used: the EMR .sync integrations
# poll the cluster and step state.
#
# SqsWorkQueue (modules/storage/.../aws/SqsWorkQueue.scala) serves both queues.
# It calls exactly: SendMessageBatch (IAM action sqs:SendMessage),
# ReceiveMessage, DeleteMessage (ack: the consumption protocol, not data
# removal) and ChangeMessageVisibility (release). It never creates, purges or
# reads queue attributes, so no queue policy or role grants more than these.

locals {
  # Visibility of the job queue: longer than the job lease (service.jobLeaseMillis),
  # so a message is never redelivered while its lease is live.
  job_visibility_seconds = var.job_lease_seconds + var.job_visibility_margin_seconds
  # A normally retried job (jobMaxAttempts receives at most) never reaches the DLQ.
  job_max_receive_count = var.job_max_attempts + 2

  queues = {
    jobs = {
      visibility        = local.job_visibility_seconds
      retention         = var.job_retention_seconds
      max_receive_count = local.job_max_receive_count
    }
    embedding = {
      visibility        = var.embedding_visibility_seconds
      retention         = 345600
      max_receive_count = var.embedding_max_receive_count
    }
  }
}

resource "aws_sqs_queue" "dlq" {
  for_each = local.queues

  name                      = "${var.name_prefix}-${each.key}-dlq"
  message_retention_seconds = var.dlq_retention_seconds
  sqs_managed_sse_enabled   = true

  tags = { Name = "${var.name_prefix}-${each.key}-dlq", queue = "${each.key}-dlq" }
}

resource "aws_sqs_queue" "this" {
  for_each = local.queues

  name                       = "${var.name_prefix}-${each.key}"
  visibility_timeout_seconds = each.value.visibility
  message_retention_seconds  = each.value.retention
  receive_wait_time_seconds  = 0
  sqs_managed_sse_enabled    = true

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.dlq[each.key].arn
    maxReceiveCount     = each.value.max_receive_count
  })

  tags = { Name = "${var.name_prefix}-${each.key}", queue = each.key }

  lifecycle {
    precondition {
      condition     = local.job_visibility_seconds > var.job_lease_seconds
      error_message = "The job queue's visibility timeout must be longer than service.jobLeaseMillis."
    }
  }
}

# Queue policies: only the actions SqsWorkQueue calls, only for the roles that
# call them, on that queue only; TLS required. The task roles' identity
# policies (runtime module) grant the same actions.
locals {
  queue_access = {
    jobs = [
      { sid = "QueryEnqueuesJobs", principals = var.job_producer_role_arns, actions = ["sqs:SendMessage"] },
      {
        sid        = "WorkerConsumesJobs"
        principals = var.job_consumer_role_arns
        actions    = ["sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:ChangeMessageVisibility"]
      },
    ]
    embedding = [
      {
        sid        = "OpsUsesEmbeddingQueue"
        principals = var.embedding_role_arns
        actions    = ["sqs:SendMessage", "sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:ChangeMessageVisibility"]
      },
      # Phase 15 decision 9.1: the worker consumes embedding tasks; it never sends.
      {
        sid        = "WorkerConsumesEmbeddingTasks"
        principals = var.embedding_consumer_role_arns
        actions    = ["sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:ChangeMessageVisibility"]
      },
    ]
  }
}

data "aws_iam_policy_document" "queue" {
  for_each = local.queues

  dynamic "statement" {
    for_each = local.queue_access[each.key]
    content {
      sid       = statement.value.sid
      actions   = statement.value.actions
      resources = [aws_sqs_queue.this[each.key].arn]

      principals {
        type        = "AWS"
        identifiers = statement.value.principals
      }
    }
  }

  statement {
    sid       = "DenyInsecureTransport"
    effect    = "Deny"
    actions   = ["sqs:*"]
    resources = [aws_sqs_queue.this[each.key].arn]

    principals {
      type        = "*"
      identifiers = ["*"]
    }

    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }
}

resource "aws_sqs_queue_policy" "this" {
  for_each = local.queues

  queue_url = aws_sqs_queue.this[each.key].id
  policy    = data.aws_iam_policy_document.queue[each.key].json
}

# Only its own source queue may use a dead-letter queue.
resource "aws_sqs_queue_redrive_allow_policy" "dlq" {
  for_each = local.queues

  queue_url = aws_sqs_queue.dlq[each.key].id
  redrive_allow_policy = jsonencode({
    redrivePermission = "byQueue"
    sourceQueueArns   = [aws_sqs_queue.this[each.key].arn]
  })
}
