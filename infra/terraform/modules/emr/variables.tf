# Inputs of the emr module (decision 6 area 5). No cluster is created here:
# the pipeline state machine (area 11) creates and ends transient clusters
# with these values.

variable "name_prefix" {
  description = "Base of resource names (<project>-<environment>)."
  type        = string
}

variable "data_bucket_name" {
  description = "Data-lake bucket name (bootstrap script and log location)."
  type        = string
}

variable "artifacts_prefix" {
  description = "Data-lake prefix for artifacts (jars, bootstrap)."
  type        = string
}

variable "emr_log_uri" {
  description = "S3 location of EMR logs (from the data-lake module)."
  type        = string
}

variable "subnet_ids" {
  description = "Private data subnets; the state machine launches each cluster in the first one (single-AZ cluster)."
  type        = list(string)
}

variable "security_group_ids" {
  description = "EMR custom managed security groups: primary, core, service_access."
  type = object({
    primary        = string
    core           = string
    service_access = string
  })
}

variable "service_role_arn" {
  description = "EMR service role ARN."
  type        = string
}

variable "instance_profile_name" {
  description = "EMR EC2 instance profile name."
  type        = string
}

variable "cluster_tags" {
  description = "Extra tags every cluster carries (the EMR v2 managed-policy tag)."
  type        = map(string)
}

variable "hadoop_release_label" {
  description = "EMR release label of the Hadoop MapReduce cluster (EMR 7.x). emr-7.14.0 is the newest 7.x label in the EMR Release Guide (read Nov 17, 2026); availability in us-east-1 unverified."
  type        = string
  default     = "emr-7.14.0"

  validation {
    condition     = can(regex("^emr-7\\.[0-9]+\\.[0-9]+$", var.hadoop_release_label))
    error_message = "hadoop_release_label must be an EMR 7.x label (emr-7.N.N)."
  }
}

variable "spark_release_label" {
  description = "EMR release label of the Spark cluster (decided: emr-spark-8.1.0; listed in the EMR Release Guide; availability in us-east-1 unverified)."
  type        = string
  default     = "emr-spark-8.1.0"

  validation {
    condition     = can(regex("^emr-spark-8\\.[0-9]+\\.[0-9]+$", var.spark_release_label))
    error_message = "spark_release_label must be an EMR Spark 8.x label (emr-spark-8.N.N)."
  }
}

variable "primary_instance_type" {
  description = "Instance type of the primary node (On-Demand)."
  type        = string
  default     = "m5.xlarge"
}

variable "core_instance_type" {
  description = "Instance type of the core nodes (On-Demand)."
  type        = string
  default     = "m5.xlarge"
}

variable "instance_vcpus" {
  description = "vCPUs per instance of the chosen types (m5.xlarge: 4). Used only for the quota check below."
  type        = number
  default     = 4
}

variable "core_instance_count" {
  description = "Core nodes at cluster start (On-Demand)."
  type        = number
  default     = 2

  validation {
    condition     = var.core_instance_count >= 1 && var.core_instance_count <= 4
    error_message = "core_instance_count must be between 1 and 4 (bounded default capacity)."
  }
}

variable "managed_scaling_min_units" {
  description = "Managed scaling minimum (instances, core nodes; the primary node is not counted)."
  type        = number
  default     = 2
}

variable "managed_scaling_max_units" {
  description = "Managed scaling maximum (instances, core nodes; On-Demand only; no task or Spot nodes)."
  type        = number
  default     = 4
}

variable "vcpu_budget" {
  description = "Highest vCPU count one cluster may reach (the account's On-Demand Standard quota is 64 vCPU, shared by every running cluster; 32 leaves room)."
  type        = number
  default     = 32

  validation {
    condition     = var.vcpu_budget >= 8 && var.vcpu_budget <= 64
    error_message = "vcpu_budget must be between 8 and the 64 vCPU On-Demand Standard quota."
  }
}

variable "idle_timeout_seconds" {
  description = "Idle auto-termination of every cluster (backstop if the pipeline cannot end it, or if an execution is stopped or times out). 60-3600 seconds."
  type        = number
  default     = 900

  validation {
    condition     = var.idle_timeout_seconds >= 60 && var.idle_timeout_seconds <= 3600
    error_message = "idle_timeout_seconds must be between 60 and 3600 (bounded backstop)."
  }
}

variable "step_concurrency_level" {
  description = "Steps a cluster runs at once (1: the pipeline's steps are sequential)."
  type        = number
  default     = 1
}
