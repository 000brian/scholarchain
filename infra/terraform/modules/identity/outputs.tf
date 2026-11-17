# Outputs of the identity module (decision 6 area 3). ARNs and names only;
# never a secret value.

output "emr_service_role_arn" {
  description = "EMR service role (ServiceRole on createCluster)."
  value       = aws_iam_role.emr_service.arn
}

output "emr_service_role_name" {
  description = "EMR service role name."
  value       = aws_iam_role.emr_service.name
}

output "emr_instance_role_arn" {
  description = "EMR EC2 instance role (the Step Functions role passes it)."
  value       = aws_iam_role.emr_instance.arn
}

output "emr_instance_profile_name" {
  description = "EMR EC2 instance profile name (JobFlowRole on createCluster)."
  value       = aws_iam_instance_profile.emr_instance.name
}

output "emr_instance_profile_arn" {
  description = "EMR EC2 instance profile ARN."
  value       = aws_iam_instance_profile.emr_instance.arn
}

output "neptune_loader_role_arn" {
  description = "Neptune bulk-loader role (attached to the cluster; iamRoleArn of a load request)."
  value       = aws_iam_role.neptune_loader.arn
}

output "deployment_role_arn" {
  description = "Deployment role assumed by the human operator (Phase 14)."
  value       = aws_iam_role.deployment.arn
}

output "role_boundary_arn" {
  description = "Permissions boundary every project role must carry (runtime and workflow roles set it; the deployment role may create or change only roles that carry it)."
  value       = aws_iam_policy.role_boundary.arn
}

output "app_key_secret_arns" {
  description = "ARNs of the four secret containers, by key: voyage, anthropic, service, service-readonly."
  value       = { for k, s in aws_secretsmanager_secret.app_key : k => s.arn }
}

output "app_key_secret_arns_by_env_var" {
  description = "ARNs of the four secret containers, by the environment-variable NAME a task receives the value under."
  value       = { for k, s in aws_secretsmanager_secret.app_key : local.app_keys[k].env_var => s.arn }
}
