# Root outputs for scripts/** (step 2c) and Phase 14. Names, IDs, ARNs and
# URLs only; no output holds a secret value. Values are resolved at plan or
# apply time and are never written into a committed file or config/**.

# ---- runtime ---------------------------------------------------------------------

output "region" {
  description = "AWS Region of the environment."
  value       = module.foundation.region
}

output "runtime_enabled" {
  description = "Whether the runtime switch is on in this configuration."
  value       = var.runtime_enabled
}

output "api_url" {
  description = "HTTPS address of the API (API Gateway HTTP API default stage; smoke-test.sh calls it)."
  value       = module.runtime.api_url
}

output "api_id" {
  description = "ID of the API Gateway HTTP API."
  value       = module.runtime.api_id
}

output "ecr_repository_url" {
  description = "Services image repository URL; push tags are the git commit SHA (immutable)."
  value       = module.runtime.ecr_repository_url
}

output "ecr_repository_name" {
  description = "Services image repository name."
  value       = module.runtime.ecr_repository_name
}

output "ecs_cluster_name" {
  description = "ECS cluster name."
  value       = module.runtime.ecs_cluster_name
}

output "ecs_service_names" {
  description = "ECS service names by role: query, evidence, graph, worker (rollback.sh, deploy.sh)."
  value       = module.runtime.ecs_service_names
}

output "ecs_task_definition_families" {
  description = "Task definition families by role: query, evidence, graph, worker, ops."
  value       = module.runtime.task_definition_families
}

output "ops_task_definition_family" {
  description = "Family of the ops task definition (run-task; the command is the main class and its arguments)."
  value       = module.runtime.ops_task_definition_family
}

output "app_subnet_ids" {
  description = "Private app subnets (run-task network configuration of the ops task; assign no public IP)."
  value       = module.network.app_subnet_ids
}

output "ops_security_group_id" {
  description = "Security group of the ops task (run-task network configuration)."
  value       = module.network.security_group_ids["ops"]
}

output "alb_dns_name" {
  description = "DNS name of the internal ALB (resolvable only inside the VPC)."
  value       = module.runtime.alb_dns_name
}

# ---- workflow ------------------------------------------------------------------------

output "state_machine_arn" {
  description = "ARN of the bounded pipeline state machine (run-bounded-pipeline.sh)."
  value       = module.workflow.state_machine_arn
}

output "job_queue_url" {
  description = "URL of the SQS job queue (service.jobQueueUrl; set by the task definitions)."
  value       = module.workflow.queue_urls["jobs"]
}

output "embedding_queue_url" {
  description = "URL of the SQS embedding work queue."
  value       = module.workflow.queue_urls["embedding"]
}

output "queue_names" {
  description = "SQS queue names: jobs, embedding, jobs_dlq, embedding_dlq."
  value       = module.workflow.queue_names
}

# ---- stores -----------------------------------------------------------------------------

output "data_bucket_name" {
  description = "Data-lake bucket name (generated from bucket_prefix)."
  value       = module.data_lake.bucket_name
}

output "data_prefixes" {
  description = "Data-lake prefixes: raw, curated, graph, rag, artifacts, logs, reports."
  value       = module.data_lake.prefixes
}

output "emr_log_uri" {
  description = "S3 location of EMR cluster logs."
  value       = module.data_lake.emr_log_uri
}

output "vector_bucket_name" {
  description = "S3 Vectors bucket name."
  value       = module.vectors.vector_bucket_name
}

output "dynamodb_table_names" {
  description = "The six DynamoDB table names by logical name (TableSpecs)."
  value       = module.dynamodb.table_names
}

output "neptune_endpoint" {
  description = "Neptune writer endpoint host (reachable only inside the VPC)."
  value       = module.neptune.cluster_endpoint
}

output "neptune_loader_role_arn" {
  description = "Neptune bulk-loader role (iamRoleArn of a load request)."
  value       = module.identity.neptune_loader_role_arn
}

# ---- operations ---------------------------------------------------------------------------

output "vpc_id" {
  description = "VPC ID (cleanup.sh lists what is left in it)."
  value       = module.network.vpc_id
}

output "nat_gateway_id" {
  description = "The single NAT gateway (cleanup.sh lists it)."
  value       = module.network.nat_gateway_id
}

output "app_key_secret_arns_by_env_var" {
  description = "ARNs (not values) of the four secret containers by environment-variable NAME; the human enters the values after apply."
  value       = module.identity.app_key_secret_arns_by_env_var
}

output "deployment_role_arn" {
  description = "Deployment role assumed by the human operator (Phase 14)."
  value       = module.identity.deployment_role_arn
}

output "log_group_names" {
  description = "CloudWatch log group names: ecs, ops, states, emr, api_access."
  value       = module.observability.log_group_names
}

output "alarm_topic_arn" {
  description = "SNS topic of every alarm (no subscription in source)."
  value       = module.observability.alarm_topic_arn
}

output "dashboard_name" {
  description = "CloudWatch dashboard name."
  value       = module.observability.dashboard_name
}
