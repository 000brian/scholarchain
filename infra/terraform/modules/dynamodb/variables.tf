# Inputs of the dynamodb module (decision 6 area 6).

variable "name_prefix" {
  description = "Base of table names (<project>-<environment>)."
  type        = string
}

variable "point_in_time_recovery_enabled" {
  description = "PITR on every table. Off in the course environment (cost; every item is reproducible from S3 by the loaders); turn on for a persistent environment."
  type        = bool
  default     = false
}

variable "deletion_protection_enabled" {
  description = "DynamoDB deletion protection on every table. Off in the ephemeral course environment (explicit cleanup mode); on for a persistent environment."
  type        = bool
  default     = false
}
