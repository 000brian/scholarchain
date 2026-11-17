# Inputs of the data-lake module (decision 6 area 4).

variable "name_prefix" {
  description = "Base of resource names (<project>-<environment>). The bucket name is built from it with bucket_prefix (no literal bucket name)."
  type        = string
}

variable "logs_expiration_days" {
  description = "Days after which objects under logs/ expire (EMR, job and service logs)."
  type        = number
  default     = 14

  validation {
    condition     = var.logs_expiration_days >= 1 && var.logs_expiration_days <= 365
    error_message = "logs_expiration_days must be between 1 and 365."
  }
}

variable "noncurrent_version_expiration_days" {
  description = "Days after which noncurrent (overwritten or removed) object versions expire, everywhere in the bucket."
  type        = number
  default     = 7

  validation {
    condition     = var.noncurrent_version_expiration_days >= 1 && var.noncurrent_version_expiration_days <= 365
    error_message = "noncurrent_version_expiration_days must be between 1 and 365."
  }
}
