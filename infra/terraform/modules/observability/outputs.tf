# Outputs of the observability module (decision 6 area 12).

output "log_group_names" {
  description = "Log group names by purpose: ecs, ops, states, emr, api_access."
  value       = { for k, g in aws_cloudwatch_log_group.this : k => g.name }
}

output "alarm_topic_arn" {
  description = "SNS topic of every alarm (no subscription in source; the human subscribes by hand)."
  value       = aws_sns_topic.alarms.arn
}

output "dashboard_name" {
  description = "Name of the CloudWatch dashboard."
  value       = aws_cloudwatch_dashboard.this.dashboard_name
}

output "log_group_arns" {
  description = "Log group ARNs (without the trailing :*) by purpose: ecs, ops, states, emr, api_access."
  value       = { for k, g in aws_cloudwatch_log_group.this : k => g.arn }
}
