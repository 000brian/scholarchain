# Inputs of the observability module (decision 6 area 12).

variable "name_prefix" {
  description = "Name prefix of every resource (scholarchain-course)."
  type        = string
}

variable "region" {
  description = "AWS Region (dashboard widgets)."
  type        = string
}

variable "account_id" {
  description = "AWS account ID (SNS topic policy condition; from data.aws_caller_identity)."
  type        = string
}

variable "runtime_enabled" {
  description = "The runtime switch (area 10): the ALB HealthyHostCount alarms exist only when the runtime is on."
  type        = bool
}

variable "alb_arn_suffix" {
  description = "ARN suffix of the internal ALB (CloudWatch dimension LoadBalancer)."
  type        = string
}

variable "target_group_arn_suffixes" {
  description = "ARN suffixes of the ALB target groups by role (query, evidence, graph)."
  type        = map(string)
}

variable "ecs_cluster_name" {
  description = "ECS cluster name (default AWS/ECS service metrics)."
  type        = string
}

variable "ecs_service_names" {
  description = "ECS service names by role (query, evidence, graph, worker)."
  type        = map(string)
}

variable "queue_names" {
  description = "SQS queue names: jobs, embedding, jobs_dlq, embedding_dlq."
  type        = map(string)
}

variable "neptune_cluster_id" {
  description = "Neptune cluster identifier (dimension DBClusterIdentifier)."
  type        = string
}

variable "neptune_cpu_threshold_percent" {
  description = "Neptune CPUUtilization alarm threshold."
  type        = number
  default     = 80
}

variable "dynamodb_table_names" {
  description = "DynamoDB table names by logical name."
  type        = map(string)
}

variable "state_machine_arn" {
  description = "ARN of the pipeline state machine."
  type        = string
}

variable "api_id" {
  description = "ID of the HTTP API."
  type        = string
}

variable "log_retention_days" {
  description = "Retention of every log group in days (decided: 7)."
  type        = number
  default     = 7

  validation {
    condition     = contains([1, 3, 5, 7, 14, 30], var.log_retention_days)
    error_message = "log_retention_days must be a short CloudWatch retention value (1, 3, 5, 7, 14 or 30)."
  }
}
