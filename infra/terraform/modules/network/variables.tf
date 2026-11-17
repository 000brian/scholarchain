# Inputs of the network module (decision 6 area 2).

variable "name_prefix" {
  description = "Base of resource names (<project>-<environment>)."
  type        = string
}

variable "region" {
  description = "AWS Region (for the gateway endpoint service names)."
  type        = string
}

variable "vpc_cidr" {
  description = "Private IPv4 range of the VPC."
  type        = string
  default     = "10.40.0.0/16"

  validation {
    condition     = can(cidrnetmask(var.vpc_cidr))
    error_message = "vpc_cidr must be a valid IPv4 CIDR block."
  }
}

variable "az_count" {
  description = "Number of Availability Zones used (decided: 2)."
  type        = number
  default     = 2

  validation {
    condition     = var.az_count == 2
    error_message = "The course environment is decided to use exactly 2 Availability Zones."
  }
}

variable "service_port" {
  description = "TCP port the ScholarChain HTTP services listen on (config key service.port; config/course.json must use the same value)."
  type        = number
  default     = 8080

  validation {
    condition     = var.service_port >= 1024 && var.service_port <= 65535
    error_message = "service_port must be an unprivileged port (1024-65535)."
  }
}

variable "alb_listener_port" {
  description = "Port of the internal ALB's HTTP listener (traffic stays inside the VPC)."
  type        = number
  default     = 80
}

variable "neptune_port" {
  description = "Neptune endpoint port."
  type        = number
  default     = 8182
}

variable "emr_managed_policy_tag" {
  description = "Tag that AmazonEMRServicePolicy_v2 requires on the VPC, subnets and security groups that EMR did not create."
  type        = map(string)
  default     = { "for-use-with-amazon-emr-managed-policies" = "true" }
}
