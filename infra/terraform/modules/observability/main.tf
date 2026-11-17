# observability (decision 6 area 12): CloudWatch log groups (retention 7
# days), one dashboard, alarms on metrics AWS publishes by default (no
# Container Insights: it is paid), one SNS topic without subscription.
# /metrics of the services is replaced by CloudWatch.
#
# Log groups are created here (not by the services or AWS) so that their
# retention is bounded and they are removed with the environment. They are
# encrypted with the CloudWatch Logs service key (AWS-managed, decision 6
# area 3).

locals {
  log_groups = {
    # ECS services (query, evidence, graph, worker), one stream prefix per role.
    ecs = "/scholarchain/course/ecs/services"
    # The ops task definition (loaders, rag ingest), started by hand with run-task.
    ops = "/scholarchain/course/ecs/ops"
    # Step Functions vended logs: the name must start with /aws/vendedlogs/ so
    # the CloudWatch Logs resource policy Step Functions writes stays small.
    states = "/aws/vendedlogs/states/${var.name_prefix}-pipeline"
    # EMR: clusters write their step and node logs to S3 (LogUri, logs/emr/
    # in the data lake, area 4). Nothing writes here by default; the group is
    # the bounded destination for a CloudWatch agent on EMR (proposed later).
    emr = "/scholarchain/course/emr"
    # API Gateway HTTP API access logs (no request bodies, no headers).
    api_access = "/scholarchain/course/apigateway/access"
  }
}

resource "aws_cloudwatch_log_group" "this" {
  for_each = local.log_groups

  name              = each.value
  retention_in_days = var.log_retention_days

  tags = { Name = each.value, purpose = each.key }
}
