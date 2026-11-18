# One dashboard (decision 6 area 12) over metrics AWS publishes by default:
# API Gateway (requests, errors, latency), the internal ALB, ECS services
# (CPU and memory; no Container Insights), SQS queues and DLQs, Neptune,
# DynamoDB, Step Functions and EMR (a SEARCH expression: cluster IDs exist
# only while a pipeline runs).
#
# Metric gaps (the application emits no custom CloudWatch metric today; none
# is invented here): LLM usage (tokens, calls), S3 Vectors client metrics
# (calls, retries), embedding calls, per-query graph and retry counts. The
# services' in-process counters are not exported; see the step 2b report.

locals {
  w = { width = 12, height = 6 }

  ecs_cpu = [for r, s in var.ecs_service_names : ["AWS/ECS", "CPUUtilization", "ClusterName", var.ecs_cluster_name, "ServiceName", s, { label = "${r} CPU" }]]
  ecs_mem = [for r, s in var.ecs_service_names : ["AWS/ECS", "MemoryUtilization", "ClusterName", var.ecs_cluster_name, "ServiceName", s, { label = "${r} memory" }]]
  # I-29: each metric row must stay a list. flatten() is recursive and turned
  # the rows into one list of strings, so the rows are joined with concat().
  sqs = concat(
    [for k, q in var.queue_names : ["AWS/SQS", "ApproximateNumberOfMessagesVisible", "QueueName", q, { label = "${k} visible" }]],
    [for k, q in var.queue_names : ["AWS/SQS", "ApproximateAgeOfOldestMessage", "QueueName", q, { label = "${k} oldest age (s)", yAxis = "right" }]],
  )
  dynamo = concat(
    [for k, t in var.dynamodb_table_names : ["AWS/DynamoDB", "ConsumedReadCapacityUnits", "TableName", t, { label = "${k} read", stat = "Sum" }]],
    [for k, t in var.dynamodb_table_names : ["AWS/DynamoDB", "ConsumedWriteCapacityUnits", "TableName", t, { label = "${k} write", stat = "Sum" }]],
  )

  widgets = [
    {
      type = "metric", x = 0, y = 0, width = local.w.width, height = local.w.height
      properties = {
        title  = "API Gateway: requests and errors"
        region = var.region
        stat   = "Sum"
        period = 300
        metrics = [
          ["AWS/ApiGateway", "Count", "ApiId", var.api_id, "Stage", "$default"],
          ["AWS/ApiGateway", "4xx", "ApiId", var.api_id, "Stage", "$default"],
          ["AWS/ApiGateway", "5xx", "ApiId", var.api_id, "Stage", "$default"],
        ]
      }
    },
    {
      type = "metric", x = 12, y = 0, width = local.w.width, height = local.w.height
      properties = {
        title  = "API latency (ms, p90)"
        region = var.region
        stat   = "p90"
        period = 300
        metrics = [
          ["AWS/ApiGateway", "Latency", "ApiId", var.api_id, "Stage", "$default"],
          ["AWS/ApiGateway", "IntegrationLatency", "ApiId", var.api_id, "Stage", "$default"],
        ]
      }
    },
    {
      type = "metric", x = 0, y = 6, width = local.w.width, height = local.w.height
      properties = {
        title  = "Internal ALB"
        region = var.region
        stat   = "Sum"
        period = 300
        metrics = [
          ["AWS/ApplicationELB", "RequestCount", "LoadBalancer", var.alb_arn_suffix],
          ["AWS/ApplicationELB", "HTTPCode_ELB_5XX_Count", "LoadBalancer", var.alb_arn_suffix],
          ["AWS/ApplicationELB", "HTTPCode_Target_5XX_Count", "LoadBalancer", var.alb_arn_suffix],
          ["AWS/ApplicationELB", "TargetResponseTime", "LoadBalancer", var.alb_arn_suffix, { stat = "p90", yAxis = "right" }],
        ]
      }
    },
    {
      type = "metric", x = 12, y = 6, width = local.w.width, height = local.w.height
      properties = {
        title   = "ECS services: CPU and memory (%)"
        region  = var.region
        stat    = "Average"
        period  = 300
        metrics = concat(local.ecs_cpu, local.ecs_mem)
      }
    },
    {
      type = "metric", x = 0, y = 12, width = local.w.width, height = local.w.height
      properties = {
        title   = "SQS queues and DLQs"
        region  = var.region
        stat    = "Maximum"
        period  = 300
        metrics = local.sqs
      }
    },
    {
      type = "metric", x = 12, y = 12, width = local.w.width, height = local.w.height
      properties = {
        title  = "Neptune"
        region = var.region
        stat   = "Average"
        period = 300
        metrics = [
          ["AWS/Neptune", "CPUUtilization", "DBClusterIdentifier", var.neptune_cluster_id],
          ["AWS/Neptune", "MainRequestQueuePendingRequests", "DBClusterIdentifier", var.neptune_cluster_id, { yAxis = "right" }],
        ]
      }
    },
    {
      type = "metric", x = 0, y = 18, width = local.w.width, height = local.w.height
      properties = {
        title   = "DynamoDB consumed capacity (on-demand)"
        region  = var.region
        period  = 300
        metrics = local.dynamo
      }
    },
    {
      type = "metric", x = 12, y = 18, width = local.w.width, height = local.w.height
      properties = {
        title  = "Pipeline executions and EMR clusters"
        region = var.region
        stat   = "Sum"
        period = 300
        metrics = [
          ["AWS/States", "ExecutionsStarted", "StateMachineArn", var.state_machine_arn],
          ["AWS/States", "ExecutionsSucceeded", "StateMachineArn", var.state_machine_arn],
          ["AWS/States", "ExecutionsFailed", "StateMachineArn", var.state_machine_arn],
          [{ expression = "SEARCH('{AWS/ElasticMapReduce,JobFlowId} MetricName=\"AppsRunning\"', 'Maximum', 300)", id = "emrapps", label = "EMR apps running", yAxis = "right" }],
        ]
      }
    },
  ]
}

resource "aws_cloudwatch_dashboard" "this" {
  dashboard_name = "${var.name_prefix}-overview"
  dashboard_body = jsonencode({ widgets = local.widgets })
}
