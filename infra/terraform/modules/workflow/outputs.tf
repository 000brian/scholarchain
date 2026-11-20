# Outputs of the workflow module (decision 6 area 11).

output "queue_urls" {
  description = "SQS queue URLs: jobs (service.jobQueueUrl, set by the task definitions) and embedding."
  value       = { for k, q in aws_sqs_queue.this : k => q.url }
}

output "queue_arns" {
  description = "SQS queue ARNs: jobs and embedding."
  value       = { for k, q in aws_sqs_queue.this : k => q.arn }
}

output "dlq_urls" {
  description = "SQS dead-letter queue URLs by source queue: jobs and embedding (Phase 15: run-experiment.sh registers them as identifiers; never printed)."
  value       = { for k, q in aws_sqs_queue.dlq : k => q.url }
}

output "queue_names" {
  description = "SQS queue names: jobs, embedding, jobs_dlq, embedding_dlq."
  value = merge(
    { for k, q in aws_sqs_queue.this : k => q.name },
    { for k, q in aws_sqs_queue.dlq : "${k}_dlq" => q.name },
  )
}

output "state_machine_arn" {
  description = "ARN of the bounded pipeline state machine (scripts/run-bounded-pipeline.sh starts executions of it)."
  value       = aws_sfn_state_machine.pipeline.arn
}

output "state_machine_name" {
  description = "Name of the bounded pipeline state machine."
  value       = aws_sfn_state_machine.pipeline.name
}

output "state_machine_role_arn" {
  description = "ARN of the state machine's role."
  value       = aws_iam_role.sfn.arn
}

output "job_visibility_timeout_seconds" {
  description = "Visibility timeout of the job queue (longer than service.jobLeaseMillis)."
  value       = local.job_visibility_seconds
}

output "job_max_receive_count" {
  description = "maxReceiveCount of the job queue (service.jobMaxAttempts + 2)."
  value       = local.job_max_receive_count
}
