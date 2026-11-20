# Inputs of the runtime module (decision 6 area 10).

# ---- naming and account facts ------------------------------------------------

variable "name_prefix" {
  description = "Name prefix of every resource (scholarchain-course); IAM role names must start with it (deployment role scope)."
  type        = string
}

variable "account_id" {
  description = "AWS account ID (from data.aws_caller_identity; never a literal)."
  type        = string
}

variable "partition" {
  description = "AWS partition."
  type        = string
}

variable "region" {
  description = "AWS Region."
  type        = string
}

# ---- the runtime switch (decided) ---------------------------------------------

variable "runtime_enabled" {
  description = "false: every desired count is 0 and every scaling target has min 0 and max 0 (no task runs). Phase 14 turns it on in a second signed plan."
  type        = bool
}

variable "image_tag" {
  description = "Git commit SHA of the services image in ECR. null while runtime_enabled is false (a placeholder tag is used then)."
  type        = string
  default     = null
}

variable "graph_run_id" {
  description = "Spark graph run loaded into Neptune and DynamoDB (run:v1:<32 hex>). null while runtime_enabled is false (a placeholder is used then)."
  type        = string
  default     = null
}

# ---- network ------------------------------------------------------------------

variable "vpc_id" {
  description = "VPC ID."
  type        = string
}

variable "app_subnet_ids" {
  description = "Private app subnets: internal ALB, ECS tasks, the API Gateway VPC link."
  type        = list(string)
}

variable "security_group_ids" {
  description = "Security groups by role from the network module (vpc_link, alb, query, evidence, graph, worker, ops, ...). This module creates none."
  type        = map(string)
}

variable "service_port" {
  description = "Container port of the HTTP services (config service.port must match)."
  type        = number
}

variable "alb_listener_port" {
  description = "Port of the internal ALB's HTTP listener."
  type        = number
}

# ---- sizing and bounds ------------------------------------------------------------

variable "task_sizes" {
  description = "Fargate task CPU units and memory MiB per role (ARM64)."
  type = map(object({
    cpu    = number
    memory = number
  }))
  default = {
    query    = { cpu = 1024, memory = 2048 }
    evidence = { cpu = 512, memory = 1024 }
    graph    = { cpu = 512, memory = 1024 }
    worker   = { cpu = 1024, memory = 2048 }
    ops      = { cpu = 1024, memory = 4096 }
  }

  validation {
    condition     = alltrue([for r in ["query", "evidence", "graph", "worker", "ops"] : contains(keys(var.task_sizes), r)])
    error_message = "task_sizes needs query, evidence, graph, worker and ops."
  }
}

variable "ops_ephemeral_storage_gib" {
  description = "Ephemeral storage of the ops task in GiB (21-200). The rag ingest job stops below 15 GB free disk."
  type        = number
  default     = 30

  validation {
    condition     = var.ops_ephemeral_storage_gib >= 21 && var.ops_ephemeral_storage_gib <= 200
    error_message = "ops_ephemeral_storage_gib must be in 21..200 (Fargate)."
  }
}

variable "service_max_count" {
  description = "Autoscaling maximum of each ALB service (query, evidence, graph) when the runtime is on."
  type        = number
  default     = 2

  validation {
    condition     = var.service_max_count >= 1 && var.service_max_count <= 4
    error_message = "service_max_count must be in 1..4 (bounded capacity)."
  }
}

variable "worker_max_count" {
  description = "Autoscaling maximum of the worker service when the runtime is on."
  type        = number
  default     = 2

  validation {
    condition     = var.worker_max_count >= 1 && var.worker_max_count <= 4
    error_message = "worker_max_count must be in 1..4 (bounded capacity)."
  }
}

variable "cpu_target_percent" {
  description = "Target average CPU utilization of the ALB services (target tracking)."
  type        = number
  default     = 60
}

variable "worker_scale_in_minutes" {
  description = "Minutes the job queue must hold no visible and no in-flight message before the worker scales to 0."
  type        = number
  default     = 5
}

variable "stop_timeout_seconds" {
  description = "Container stopTimeout (Fargate maximum 120 s); at least the service's shutdown time."
  type        = number
  default     = 120

  validation {
    condition     = var.stop_timeout_seconds >= 2 && var.stop_timeout_seconds <= 120
    error_message = "stop_timeout_seconds must be in 2..120 (Fargate)."
  }
}

variable "deregistration_delay_seconds" {
  description = "ALB target group deregistration delay: longer than service.syncDeadlineMillis so a running sync query finishes."
  type        = number
  default     = 30
}

variable "ecr_keep_images" {
  description = "ECR lifecycle: number of most recent images kept."
  type        = number
  default     = 10
}

variable "api_throttling_burst_limit" {
  description = "API Gateway default stage burst limit (requests)."
  type        = number
  default     = 10
}

variable "api_throttling_rate_limit" {
  description = "API Gateway default stage steady-state rate limit (requests per second)."
  type        = number
  default     = 5
}

# ---- application wiring -------------------------------------------------------------

variable "config_file_path" {
  description = "Path of the ScholarChain configuration file inside the image (phase-13: build-engineer adds config/course.json there)."
  type        = string
  default     = "/opt/scholarchain/config/course.json"
}

variable "app_jar_path" {
  description = "Path of the application jar inside the image (ops task class path)."
  type        = string
  default     = "/opt/scholarchain/app.jar"
}

variable "app_key_secret_arns_by_env_var" {
  description = "Secrets Manager container ARNs by the environment-variable NAME a task receives the value under."
  type        = map(string)
}

variable "log_group_names" {
  description = "CloudWatch log group names by purpose (observability module): ecs, ops, api_access."
  type        = map(string)
}

variable "log_group_arns" {
  description = "CloudWatch log group ARNs by purpose (observability module)."
  type        = map(string)
}

variable "data_bucket_name" {
  description = "Data-lake bucket name (task environment only; never in config/**)."
  type        = string
}

variable "data_bucket_arn" {
  description = "Data-lake bucket ARN."
  type        = string
}

variable "data_prefixes" {
  description = "Data-lake prefixes (raw, curated, graph, rag, artifacts, logs, reports)."
  type        = map(string)
}

variable "table_names" {
  description = "DynamoDB table names by logical name (TableSpecs)."
  type        = map(string)
}

variable "table_arns" {
  description = "DynamoDB table ARNs by logical name."
  type        = map(string)
}

variable "vector_bucket_name" {
  description = "S3 Vectors bucket name."
  type        = string
}

variable "vector_index_arns" {
  description = "S3 Vectors index ARNs by index name."
  type        = map(string)
}

variable "neptune_endpoint" {
  description = "Neptune writer endpoint host."
  type        = string
}

variable "neptune_cluster_resource_id" {
  description = "Neptune cluster resource ID (neptune-db IAM resources)."
  type        = string
}

variable "job_queue_url" {
  description = "URL of the SQS job queue (service.jobQueueUrl)."
  type        = string
}

variable "job_queue_arn" {
  description = "ARN of the SQS job queue."
  type        = string
}

variable "embedding_queue_arn" {
  description = "ARN of the SQS embedding work queue (ops sends and consumes; Phase 15: the worker consumes, never sends)."
  type        = string
}

variable "embedding_queue_url" {
  description = "URL of the SQS embedding work queue (Phase 15 decision 5: service.embeddingQueueUrl, set on all four service tasks through SCHOLARCHAIN_CFG_SERVICE_EMBEDDING_QUEUE_URL so every role resolves the same config fingerprint (review finding H-01); only the worker consumes; never in config/**)."
  type        = string
}

variable "permissions_boundary_arn" {
  description = "Project permissions boundary (identity module, repair I-01) set on the execution role and every task role."
  type        = string
}
