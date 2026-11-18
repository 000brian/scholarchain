# Inputs of the workflow module (decision 6 area 11).

variable "name_prefix" {
  description = "Name prefix of every resource (scholarchain-course); the state machine role name starts with it (deployment role scope)."
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

# ---- pipeline state machine -------------------------------------------------------

variable "cluster_settings" {
  description = "module.emr.cluster_settings: release labels, network, roles, log and bootstrap locations, capacity, managed scaling, idle timeout, tags."
  type        = any
}

variable "emr_service_role_arn" {
  description = "EMR service role ARN (the state machine role may pass it)."
  type        = string
}

variable "emr_instance_role_arn" {
  description = "EMR EC2 instance role ARN (the state machine role may pass it)."
  type        = string
}

variable "state_machine_log_group_arn" {
  description = "ARN of the Step Functions log group (observability module)."
  type        = string
}

variable "graph_uri_prefix" {
  description = "s3:// URI of the data lake's graph/ prefix, ending in '/'. The optional JsonlExport step accepts exportRoot and exportSourceRun only under it (the EMR instance role writes there; the ops role reads there)."
  type        = string

  validation {
    condition     = can(regex("^s3://[^/]+/[^/]+/$", var.graph_uri_prefix))
    error_message = "graph_uri_prefix must look like s3://BUCKET/PREFIX/ (one prefix level, trailing slash)."
  }
}

variable "pipeline_timeout_seconds" {
  description = "TimeoutSeconds of the whole pipeline execution (both clusters). Step Functions does not end a cluster on timeout; the EMR idle auto-termination policy is the backstop."
  type        = number
  default     = 14400

  validation {
    condition     = var.pipeline_timeout_seconds >= 900 && var.pipeline_timeout_seconds <= 43200
    error_message = "pipeline_timeout_seconds must be in 900..43200 (15 minutes to 12 hours)."
  }
}

# ---- queue access (runtime task roles) ---------------------------------------------

variable "job_producer_role_arns" {
  description = "Roles that enqueue job messages (query)."
  type        = list(string)
}

variable "job_consumer_role_arns" {
  description = "Roles that receive, acknowledge and release job messages (worker)."
  type        = list(string)
}

variable "embedding_role_arns" {
  description = "Roles that use the embedding work queue (ops)."
  type        = list(string)
}

# ---- SQS (values derived from config/course.json; keep them in step) -------------

variable "job_lease_seconds" {
  description = "service.jobLeaseMillis / 1000 of config/course.json. The job queue's visibility timeout is longer than this."
  type        = number
  default     = 120
}

variable "job_visibility_margin_seconds" {
  description = "Visibility timeout of the job queue beyond the job lease (the services' local queue uses the same 30 s margin, ServiceLimits.LocalVisibilityMarginMillis)."
  type        = number
  default     = 30
}

variable "job_max_attempts" {
  description = "service.jobMaxAttempts of config/course.json. The job queue's maxReceiveCount is this + 2."
  type        = number
  default     = 3

  validation {
    condition     = var.job_max_attempts >= 1 && var.job_max_attempts <= 5
    error_message = "job_max_attempts must be in 1..5 (ServiceSettings.MaxJobAttempts)."
  }
}

variable "job_retention_seconds" {
  description = "Message retention of the job queue (service.jobTtlSeconds of config/course.json)."
  type        = number
  default     = 86400
}

variable "embedding_visibility_seconds" {
  description = "Default visibility timeout of the embedding work queue (SqsWorkQueue also sends its own per receive)."
  type        = number
  default     = 300
}

variable "embedding_max_receive_count" {
  description = "maxReceiveCount of the embedding work queue before its dead-letter queue."
  type        = number
  default     = 5
}

variable "permissions_boundary_arn" {
  description = "Project permissions boundary (identity module, repair I-01) set on the Step Functions role."
  type        = string
}

variable "dlq_retention_seconds" {
  description = "Message retention of both dead-letter queues (14 days, the SQS maximum, so a failure can be inspected)."
  type        = number
  default     = 1209600
}
