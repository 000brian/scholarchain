# Outputs of the foundation module (decision 6 area 1). Values are resolved at
# plan time; none is a secret. The account ID is an output for building ARNs in
# other modules only; it is never written into a file or report.

output "account_id" {
  description = "AWS account ID of the caller (plan time). For building ARNs only."
  value       = data.aws_caller_identity.current.account_id
}

output "partition" {
  description = "AWS partition (aws)."
  value       = data.aws_partition.current.partition
}

output "region" {
  description = "AWS Region of the provider."
  value       = data.aws_region.current.region
}

output "dns_suffix" {
  description = "DNS suffix of the partition (amazonaws.com)."
  value       = data.aws_partition.current.dns_suffix
}

output "name_prefix" {
  description = "Base of every resource name: <project>-<environment>."
  value       = local.name_prefix
}

output "account_suffix" {
  description = "8 hex characters of sha256(account ID); a unique, non-reversible name suffix."
  value       = local.account_suffix
}
