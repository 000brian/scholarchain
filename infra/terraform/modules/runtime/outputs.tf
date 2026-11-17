# Outputs of the runtime module (decision 6 area 10). Names, IDs, ARNs and
# URLs only; never a secret value.

output "api_url" {
  description = "HTTPS address of the HTTP API's default stage (AWS-managed execute-api domain)."
  value       = aws_apigatewayv2_stage.default.invoke_url
}

output "api_id" {
  description = "ID of the HTTP API."
  value       = aws_apigatewayv2_api.this.id
}

output "ecr_repository_url" {
  description = "URL of the services image repository (tags are git commit SHAs, immutable)."
  value       = aws_ecr_repository.services.repository_url
}

output "ecr_repository_name" {
  description = "Name of the services image repository."
  value       = aws_ecr_repository.services.name
}

output "ecs_cluster_name" {
  description = "ECS cluster name."
  value       = aws_ecs_cluster.this.name
}

output "ecs_service_names" {
  description = "ECS service names by role: query, evidence, graph, worker."
  value       = { for k, s in aws_ecs_service.this : k => s.name }
}

output "task_definition_families" {
  description = "Task definition families by role: query, evidence, graph, worker, ops."
  value = merge(
    { for k, t in aws_ecs_task_definition.service : k => t.family },
    { ops = aws_ecs_task_definition.ops.family },
  )
}

output "ops_task_definition_family" {
  description = "Family of the ops task definition (run-task; command = main class and its arguments)."
  value       = aws_ecs_task_definition.ops.family
}

output "alb_dns_name" {
  description = "DNS name of the internal ALB (the one internal address; resolvable only inside the VPC)."
  value       = aws_lb.internal.dns_name
}

output "alb_arn_suffix" {
  description = "ARN suffix of the internal ALB (CloudWatch dimension)."
  value       = aws_lb.internal.arn_suffix
}

output "target_group_arn_suffixes" {
  description = "ARN suffixes of the target groups by role (CloudWatch dimension)."
  value       = { for k, t in aws_lb_target_group.this : k => t.arn_suffix }
}

output "task_role_arns" {
  description = "Task role ARNs by role: query, evidence, graph, worker, ops."
  value       = { for k, r in aws_iam_role.task : k => r.arn }
}

output "vector_principal_arns" {
  description = "Roles that use the S3 Vectors data plane (query, worker, ops); the vector bucket policy allows only these."
  value       = [for r in ["query", "worker", "ops"] : aws_iam_role.task[r].arn]
}

output "execution_role_arn" {
  description = "ECS task execution role ARN."
  value       = aws_iam_role.execution.arn
}
