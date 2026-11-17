# Root variables. Non-personal values: course.tfvars (committed, passed by
# scripts/plan.sh with -var-file=course.tfvars). Personal values: local.auto.tfvars
# (gitignored, loaded automatically; see local.auto.tfvars.example).
# validate needs no values; plan does.

variable "aws_region" {
  description = "AWS Region of the course environment."
  type        = string

  validation {
    condition     = var.aws_region == "us-east-1"
    error_message = "The course environment is decided to run in us-east-1 only."
  }
}

variable "owner" {
  description = "Value of the owner tag on every resource. Personal: set it in local.auto.tfvars, never in a committed file."
  type        = string

  validation {
    condition     = can(regex("^[A-Za-z0-9 +=._:/@-]{1,256}$", var.owner))
    error_message = "owner must be 1-256 characters valid in an AWS tag value."
  }
}

variable "vpc_cidr" {
  description = "Private IPv4 range of the VPC (area 2)."
  type        = string
  default     = "10.40.0.0/16"
}

variable "service_port" {
  description = "Container port of the HTTP services (area 2; config service.port must match)."
  type        = number
  default     = 8080
}

variable "deployment_trusted_principal_arns" {
  description = "IAM principals allowed to assume the deployment role (area 3). Empty: the account root as trust principal."
  type        = list(string)
  default     = []
}

variable "deployment_attachable_policy_arns" {
  description = "Extra AWS managed policies the deployment role may attach (area 3; step 2b adds what the runtime needs)."
  type        = list(string)
  default     = []
}

variable "app_key_name_prefix" {
  description = "Name prefix of the four Secrets Manager containers (area 3)."
  type        = string
  default     = "scholarchain/course/"
}

variable "app_key_recovery_window_days" {
  description = "Recovery window of the secret containers when removed (area 3): 0 or 7-30. course.tfvars sets 0."
  type        = number
  default     = 7
}

variable "logs_expiration_days" {
  description = "Days after which objects under logs/ expire (area 4)."
  type        = number
  default     = 14
}

variable "noncurrent_version_expiration_days" {
  description = "Days after which noncurrent object versions expire (area 4)."
  type        = number
  default     = 7
}

variable "emr_hadoop_release_label" {
  description = "EMR 7.x release label of the Hadoop cluster (area 5)."
  type        = string
  default     = "emr-7.14.0"
}

variable "emr_spark_release_label" {
  description = "Release label of the Spark cluster (area 5; decided emr-spark-8.1.0)."
  type        = string
  default     = "emr-spark-8.1.0"
}

variable "emr_primary_instance_type" {
  description = "EMR primary node instance type (area 5; On-Demand)."
  type        = string
  default     = "m5.xlarge"
}

variable "emr_core_instance_type" {
  description = "EMR core node instance type (area 5; On-Demand)."
  type        = string
  default     = "m5.xlarge"
}

variable "emr_instance_vcpus" {
  description = "vCPUs per EMR instance of the chosen types (area 5; m5.xlarge = 4)."
  type        = number
  default     = 4
}

variable "emr_core_instance_count" {
  description = "Core nodes at cluster start (area 5)."
  type        = number
  default     = 2
}

variable "emr_managed_scaling_min_units" {
  description = "EMR managed scaling minimum, core instances (area 5)."
  type        = number
  default     = 2
}

variable "emr_managed_scaling_max_units" {
  description = "EMR managed scaling maximum, core instances (area 5)."
  type        = number
  default     = 4
}

variable "emr_vcpu_budget" {
  description = "Highest vCPU count one EMR cluster may reach (area 5; quota 64 shared)."
  type        = number
  default     = 32
}

variable "emr_idle_timeout_seconds" {
  description = "EMR idle auto-termination backstop in seconds (area 5)."
  type        = number
  default     = 900
}

variable "emr_step_concurrency_level" {
  description = "EMR steps run at once (area 5)."
  type        = number
  default     = 1
}

variable "dynamodb_point_in_time_recovery_enabled" {
  description = "PITR on the six tables (area 6). Off for the course environment (course.tfvars)."
  type        = bool
  default     = false
}

variable "dynamodb_deletion_protection_enabled" {
  description = "Deletion protection on the six tables (area 6). Off for the ephemeral course environment (course.tfvars)."
  type        = bool
  default     = false
}

variable "neptune_engine_version" {
  description = "Neptune engine version (area 7)."
  type        = string
  default     = "1.4.7.1"
}

variable "neptune_instance_class" {
  description = "Neptune instance class (area 7; decided db.t4g.medium)."
  type        = string
  default     = "db.t4g.medium"
}

variable "neptune_backup_retention_days" {
  description = "Neptune automated backup retention in days (area 7; decided 1)."
  type        = number
  default     = 1
}

variable "neptune_deletion_protection" {
  description = "Neptune deletion protection (area 7). false for the ephemeral course environment (course.tfvars)."
  type        = bool
  default     = false
}

variable "neptune_skip_final_snapshot" {
  description = "Skip the final snapshot when the cluster is removed (area 7). true for the course environment (course.tfvars)."
  type        = bool
  default     = true
}

variable "neptune_apply_immediately" {
  description = "Apply Neptune changes immediately (area 7)."
  type        = bool
  default     = true
}

variable "vectors_data_principal_arns" {
  description = "EXTRA role ARNs allowed to use the S3 Vectors data plane (area 8). The query, worker and ops task roles are always allowed (step 2b, from the runtime module); leave empty unless a human-approved principal needs access."
  type        = list(string)
  default     = []
}

variable "runtime_enabled" {
  description = "Runtime switch (area 10). false: every ECS desired count is 0 and every scaling target has min 0 and max 0. course.tfvars sets false; Phase 14 sets true with image_tag and graph_run_id in a second signed plan."
  type        = bool
  default     = false
}

variable "image_tag" {
  description = "Services image tag = git commit SHA (40 lowercase hex). Required when runtime_enabled is true; null otherwise (a placeholder is used)."
  type        = string
  default     = null

  validation {
    condition     = var.image_tag == null || can(regex("^[0-9a-f]{40}$", var.image_tag))
    error_message = "image_tag must be a full 40-character lowercase git commit SHA."
  }

  validation {
    condition     = !var.runtime_enabled || var.image_tag != null
    error_message = "runtime_enabled = true needs image_tag (the git commit SHA of an image pushed to ECR)."
  }
}

variable "graph_run_id" {
  description = "Spark graph run loaded into Neptune and DynamoDB (run:v1:<32 hex>), passed to every service as --graph-run. Required when runtime_enabled is true; null otherwise."
  type        = string
  default     = null

  validation {
    condition     = var.graph_run_id == null || can(regex("^run:v1:[0-9a-f]{32}$", var.graph_run_id))
    error_message = "graph_run_id must look like run:v1:<32 lowercase hex>."
  }

  validation {
    condition     = !var.runtime_enabled || var.graph_run_id != null
    error_message = "runtime_enabled = true needs graph_run_id."
  }
}

variable "runtime_service_max_count" {
  description = "Autoscaling maximum of each ALB service (query, evidence, graph) when the runtime is on (area 10)."
  type        = number
  default     = 2
}

variable "runtime_worker_max_count" {
  description = "Autoscaling maximum of the worker service when the runtime is on (area 10)."
  type        = number
  default     = 2
}

variable "api_throttling_burst_limit" {
  description = "API Gateway default stage burst limit (area 10; one person's smoke tests and demo)."
  type        = number
  default     = 10
}

variable "api_throttling_rate_limit" {
  description = "API Gateway default stage rate limit, requests per second (area 10)."
  type        = number
  default     = 5
}

variable "job_lease_seconds" {
  description = "service.jobLeaseMillis / 1000 of config/course.json (area 11: job queue visibility is longer)."
  type        = number
  default     = 120
}

variable "job_max_attempts" {
  description = "service.jobMaxAttempts of config/course.json (area 11: job queue maxReceiveCount = this + 2)."
  type        = number
  default     = 3
}

variable "cleanup_mode" {
  description = "Explicit cleanup mode (area 13). true: every deletion protection (DynamoDB tables, Neptune cluster) is off regardless of the per-resource variables, and runtime_enabled must be false. It removes nothing by itself; the removal order is in infra/terraform/README.md (Cleanup mode)."
  type        = bool
  default     = false

  validation {
    condition     = !var.cleanup_mode || !var.runtime_enabled
    error_message = "cleanup_mode = true needs runtime_enabled = false (scale the runtime to zero first)."
  }
}

variable "pipeline_timeout_seconds" {
  description = "TimeoutSeconds of one pipeline execution (area 11). A timed-out execution does not end its cluster; the EMR idle auto-termination policy does."
  type        = number
  default     = 14400
}

variable "log_retention_days" {
  description = "Retention of every CloudWatch log group in days (area 12; decided 7)."
  type        = number
  default     = 7
}

variable "expires" {
  description = "Value of the expires tag on every resource: the date (YYYY-MM-DD) after which the course environment should be gone. Set it in course.tfvars."
  type        = string

  validation {
    condition     = can(regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$", var.expires))
    error_message = "expires must be a date in the form YYYY-MM-DD."
  }
}
