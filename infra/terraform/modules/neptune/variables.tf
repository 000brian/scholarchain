# Inputs of the neptune module (decision 6 area 7).

variable "name_prefix" {
  description = "Base of resource names (<project>-<environment>)."
  type        = string
}

variable "subnet_ids" {
  description = "Private data subnets (two AZs, required by the subnet group)."
  type        = list(string)
}

variable "security_group_id" {
  description = "Neptune security group (ingress only from query, evidence, graph, worker, ops)."
  type        = string
}

variable "port" {
  description = "Neptune port."
  type        = number
  default     = 8182
}

variable "loader_role_arn" {
  description = "Neptune bulk-loader role attached to the cluster."
  type        = string
}

variable "engine_version" {
  description = "Neptune engine version (1.4.7.1, the newest 1.4 release in the Neptune User Guide, read Nov 17, 2026; parameter group family neptune1.4)."
  type        = string
  default     = "1.4.7.1"

  validation {
    condition     = can(regex("^1\\.4\\.[0-9]+\\.[0-9]+$", var.engine_version))
    error_message = "engine_version must be a 1.4.x.y release (the parameter groups use family neptune1.4)."
  }
}

variable "instance_class" {
  description = "Instance class of the one instance (decided: db.t4g.medium, the free-trial class)."
  type        = string
  default     = "db.t4g.medium"
}

variable "backup_retention_days" {
  description = "Automated backup retention (decided: 1 day)."
  type        = number
  default     = 1

  validation {
    condition     = var.backup_retention_days >= 1 && var.backup_retention_days <= 35
    error_message = "backup_retention_days must be between 1 and 35."
  }
}

variable "deletion_protection" {
  description = "Neptune deletion protection. false in the ephemeral course environment (explicit cleanup mode); true for a persistent environment."
  type        = bool
  default     = false
}

variable "skip_final_snapshot" {
  description = "true: removing the cluster takes no final snapshot (course default: the graph is reproducible from S3 by the bulk loader, and a retained snapshot bills storage). false: a final snapshot named <name_prefix>-neptune-final is taken; it must be removed by hand before a re-create with the same setting."
  type        = bool
  default     = true
}

variable "apply_immediately" {
  description = "Apply cluster and instance changes at once instead of in the maintenance window (course environment: true)."
  type        = bool
  default     = true
}
