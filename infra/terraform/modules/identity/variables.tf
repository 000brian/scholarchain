# Inputs of the identity module (decision 6 area 3; S3 inputs added by areas 5 and 7).

variable "name_prefix" {
  description = "Base of resource names (<project>-<environment>). IAM names start with it, which is what the deployment role's IAM statements are scoped to."
  type        = string
}

variable "account_id" {
  description = "AWS account ID (plan time, from the foundation module)."
  type        = string
}

variable "partition" {
  description = "AWS partition."
  type        = string
}

variable "region" {
  description = "AWS Region the deployment role is limited to."
  type        = string
}

variable "data_bucket_arn" {
  description = "ARN of the data-lake bucket (areas 5 and 7: EMR instance role and Neptune loader S3 policies)."
  type        = string
}

variable "data_prefixes" {
  description = "Data-lake prefixes (no leading or trailing '/') from the data-lake module."
  type = object({
    raw       = string
    curated   = string
    graph     = string
    rag       = string
    artifacts = string
    logs      = string
    reports   = string
  })
}

variable "deployment_trusted_principal_arns" {
  description = "IAM principals allowed to assume the deployment role. Empty (default): any principal of this account that an identity policy allows to call sts:AssumeRole on it (account root as trust principal)."
  type        = list(string)
  default     = []
}

variable "deployment_attachable_policy_arns" {
  description = "AWS managed policies the deployment role may attach to or detach from project roles (iam:PolicyARN condition). Step 2b adds what the runtime needs."
  type        = list(string)
  default     = []
}

variable "app_key_name_prefix" {
  description = "Name prefix of the four Secrets Manager containers (name_prefix, so a re-create after cleanup never collides with a name still in its recovery window)."
  type        = string
  default     = "scholarchain/course/"

  validation {
    condition     = can(regex("^[A-Za-z0-9/_+=.@-]{1,200}$", var.app_key_name_prefix))
    error_message = "app_key_name_prefix may contain only the characters Secrets Manager allows in names."
  }
}

variable "app_key_recovery_window_days" {
  description = "Recovery window of the secret containers when they are removed: 0 (remove at once) or 7-30. The course environment uses 0 (course.tfvars)."
  type        = number
  default     = 7

  validation {
    condition     = var.app_key_recovery_window_days == 0 || (var.app_key_recovery_window_days >= 7 && var.app_key_recovery_window_days <= 30)
    error_message = "app_key_recovery_window_days must be 0 or between 7 and 30."
  }
}
