# Alarms (decision 6 area 12), only on metrics AWS publishes by default:
#   - DLQ ApproximateNumberOfMessagesVisible > 0 (both dead-letter queues);
#   - ALB 5xx (load balancer and target 5xx, one alarm);
#   - ALB HealthyHostCount < 1 per target group (only when runtime_enabled);
#   - Neptune CPUUtilization.
# Every alarm notifies one SNS topic that has no subscription in source: the
# human subscribes by hand (manual action). Each alarm bills monthly
# (COSTS.md).

# Not encrypted with an AWS-managed key on purpose: CloudWatch alarms cannot
# publish to a topic encrypted with alias/aws/sns (its key policy cannot be
# changed), and a customer-managed key is out of scope (decision 6 area 3).
# The topic carries alarm metadata only.
resource "aws_sns_topic" "alarms" {
  name = "${var.name_prefix}-alarms"

  tags = { Name = "${var.name_prefix}-alarms" }
}

data "aws_iam_policy_document" "alarms_topic" {
  statement {
    sid       = "CloudWatchAlarmsOfThisAccount"
    actions   = ["sns:Publish"]
    resources = [aws_sns_topic.alarms.arn]

    principals {
      type        = "Service"
      identifiers = ["cloudwatch.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [var.account_id]
    }
  }

  statement {
    sid       = "DenyInsecureTransport"
    effect    = "Deny"
    actions   = ["sns:Publish", "sns:Subscribe"]
    resources = [aws_sns_topic.alarms.arn]

    principals {
      type        = "*"
      identifiers = ["*"]
    }

    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }
}

resource "aws_sns_topic_policy" "alarms" {
  arn    = aws_sns_topic.alarms.arn
  policy = data.aws_iam_policy_document.alarms_topic.json
}

locals {
  alarm_actions = [aws_sns_topic.alarms.arn]
  dlqs          = { jobs = var.queue_names["jobs_dlq"], embedding = var.queue_names["embedding_dlq"] }
}

resource "aws_cloudwatch_metric_alarm" "dlq" {
  for_each = local.dlqs

  alarm_name          = "${var.name_prefix}-${each.key}-dlq-not-empty"
  alarm_description   = "A ${each.key} message reached its dead-letter queue (maxReceiveCount exceeded)."
  namespace           = "AWS/SQS"
  metric_name         = "ApproximateNumberOfMessagesVisible"
  dimensions          = { QueueName = each.value }
  statistic           = "Maximum"
  period              = 300
  evaluation_periods  = 1
  comparison_operator = "GreaterThanThreshold"
  threshold           = 0
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions

  tags = { Name = "${var.name_prefix}-${each.key}-dlq-not-empty" }
}

resource "aws_cloudwatch_metric_alarm" "alb_5xx" {
  alarm_name          = "${var.name_prefix}-alb-5xx"
  alarm_description   = "Internal ALB returned 5xx (load balancer or targets) in the last 5 minutes."
  evaluation_periods  = 1
  comparison_operator = "GreaterThanThreshold"
  threshold           = 0
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions

  metric_query {
    id          = "total5xx"
    expression  = "FILL(elb5xx, 0) + FILL(target5xx, 0)"
    label       = "ALB plus target 5xx"
    return_data = true
  }

  metric_query {
    id = "elb5xx"
    metric {
      namespace   = "AWS/ApplicationELB"
      metric_name = "HTTPCode_ELB_5XX_Count"
      dimensions  = { LoadBalancer = var.alb_arn_suffix }
      stat        = "Sum"
      period      = 300
    }
  }

  metric_query {
    id = "target5xx"
    metric {
      namespace   = "AWS/ApplicationELB"
      metric_name = "HTTPCode_Target_5XX_Count"
      dimensions  = { LoadBalancer = var.alb_arn_suffix }
      stat        = "Sum"
      period      = 300
    }
  }

  tags = { Name = "${var.name_prefix}-alb-5xx" }
}

resource "aws_cloudwatch_metric_alarm" "healthy_hosts" {
  for_each = var.runtime_enabled ? var.target_group_arn_suffixes : {}

  alarm_name          = "${var.name_prefix}-${each.key}-no-healthy-target"
  alarm_description   = "Target group ${each.key} has no healthy target."
  namespace           = "AWS/ApplicationELB"
  metric_name         = "HealthyHostCount"
  dimensions          = { LoadBalancer = var.alb_arn_suffix, TargetGroup = each.value }
  statistic           = "Minimum"
  period              = 60
  evaluation_periods  = 3
  datapoints_to_alarm = 3
  comparison_operator = "LessThanThreshold"
  threshold           = 1
  treat_missing_data  = "breaching"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions

  tags = { Name = "${var.name_prefix}-${each.key}-no-healthy-target" }
}

resource "aws_cloudwatch_metric_alarm" "neptune_cpu" {
  alarm_name          = "${var.name_prefix}-neptune-cpu-high"
  alarm_description   = "Neptune CPUUtilization above ${var.neptune_cpu_threshold_percent} % for 15 minutes."
  namespace           = "AWS/Neptune"
  metric_name         = "CPUUtilization"
  dimensions          = { DBClusterIdentifier = var.neptune_cluster_id }
  statistic           = "Average"
  period              = 300
  evaluation_periods  = 3
  datapoints_to_alarm = 3
  comparison_operator = "GreaterThanThreshold"
  threshold           = var.neptune_cpu_threshold_percent
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions

  tags = { Name = "${var.name_prefix}-neptune-cpu-high" }
}
