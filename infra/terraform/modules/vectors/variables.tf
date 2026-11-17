# Inputs of the vectors module (decision 6 area 8).

variable "name_prefix" {
  description = "Base of resource names (<project>-<environment>)."
  type        = string
}

variable "name_suffix" {
  description = "Unique, non-reversible suffix (foundation account_suffix); vector bucket names must be unique per account and Region and there is no name_prefix argument."
  type        = string
}

variable "index_spec" {
  description = "Decoded modules/storage/src/main/resources/s3vectors/index-spec-v1.json (owner: storage-engineer). Terraform creates one index per entry with its name, dimension, dataType, distanceMetric and nonFilterableKeys."
  type = object({
    format         = string
    formatVersion  = number
    dimension      = number
    dataType       = string
    distanceMetric = string
    indexes = list(object({
      name              = string
      configKey         = string
      nonFilterableKeys = list(string)
    }))
  })

  validation {
    condition     = var.index_spec.format == "scholarchain.storage.s3vectors-index-spec" && var.index_spec.formatVersion == 1
    error_message = "index_spec must be format scholarchain.storage.s3vectors-index-spec, formatVersion 1."
  }

  validation {
    condition     = length(var.index_spec.indexes) == length(distinct([for i in var.index_spec.indexes : i.name]))
    error_message = "index_spec index names must be unique."
  }

  validation {
    condition     = alltrue([for i in var.index_spec.indexes : length(i.nonFilterableKeys) <= 10])
    error_message = "S3 Vectors allows at most 10 non-filterable metadata keys per index."
  }
}

variable "data_principal_arns" {
  description = "IAM role ARNs allowed to use the vector data plane (Get/Query/Put/Delete/ListVectors): the runtime task roles and the ops role, passed by step 2b. Empty: no bucket policy is created yet, and only identity policies grant access."
  type        = list(string)
  default     = []
}
