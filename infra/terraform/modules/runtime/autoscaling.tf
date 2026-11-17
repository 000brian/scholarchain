# Application Auto Scaling (decision 6 area 10). Phase 15 measures scale-out
# and scale-in. Scaling targets always exist; with runtime_enabled = false
# their min and max are 0 (nothing runs). Policies and the worker's alarms
# exist only when the runtime is on (each alarm bills monthly).
#
#   query, evidence, graph: target tracking on average CPU (target
#     var.cpu_target_percent), min 1 (CPU target tracking cannot start a
#     service from 0 tasks), max var.service_max_count.
#   worker: step scaling from 0 on the job queue's SQS metrics (published by
#     SQS even with no task): +1 task per step while messages are visible, up to
#     var.worker_max_count; to 0 only when visible plus in-flight messages
#     have been 0 for var.worker_scale_in_minutes minutes.

locals {
  service_bounds = {
    for r in local.roles : r => (
      contains(local.alb_roles, r)
      ? { min = var.runtime_enabled ? 1 : 0, max = var.runtime_enabled ? var.service_max_count : 0 }
      : { min = 0, max = var.runtime_enabled ? var.worker_max_count : 0 }
    )
  }
  job_queue_name = element(split(":", var.job_queue_arn), 5)
}

resource "aws_appautoscaling_target" "service" {
  for_each = toset(local.roles)

  service_namespace  = "ecs"
  scalable_dimension = "ecs:service:DesiredCount"
  resource_id        = "service/${aws_ecs_cluster.this.name}/${aws_ecs_service.this[each.key].name}"
  min_capacity       = local.service_bounds[each.key].min
  max_capacity       = local.service_bounds[each.key].max

  tags = { Name = "${var.name_prefix}-${each.key}-scaling", role = each.key }
}

resource "aws_appautoscaling_policy" "cpu" {
  for_each = var.runtime_enabled ? toset(local.alb_roles) : toset([])

  name               = "${var.name_prefix}-${each.key}-cpu"
  policy_type        = "TargetTrackingScaling"
  service_namespace  = aws_appautoscaling_target.service[each.key].service_namespace
  scalable_dimension = aws_appautoscaling_target.service[each.key].scalable_dimension
  resource_id        = aws_appautoscaling_target.service[each.key].resource_id

  target_tracking_scaling_policy_configuration {
    target_value       = var.cpu_target_percent
    scale_out_cooldown = 60
    scale_in_cooldown  = 180

    predefined_metric_specification {
      predefined_metric_type = "ECSServiceAverageCPUUtilization"
    }
  }
}

# ---- worker: step scaling on the job queue ------------------------------------

resource "aws_appautoscaling_policy" "worker_out" {
  count = var.runtime_enabled ? 1 : 0

  name               = "${var.name_prefix}-worker-out"
  policy_type        = "StepScaling"
  service_namespace  = aws_appautoscaling_target.service["worker"].service_namespace
  scalable_dimension = aws_appautoscaling_target.service["worker"].scalable_dimension
  resource_id        = aws_appautoscaling_target.service["worker"].resource_id

  step_scaling_policy_configuration {
    adjustment_type         = "ChangeInCapacity"
    cooldown                = 120
    metric_aggregation_type = "Maximum"

    step_adjustment {
      metric_interval_lower_bound = 0
      scaling_adjustment          = 1
    }
  }
}

resource "aws_appautoscaling_policy" "worker_in" {
  count = var.runtime_enabled ? 1 : 0

  name               = "${var.name_prefix}-worker-in"
  policy_type        = "StepScaling"
  service_namespace  = aws_appautoscaling_target.service["worker"].service_namespace
  scalable_dimension = aws_appautoscaling_target.service["worker"].scalable_dimension
  resource_id        = aws_appautoscaling_target.service["worker"].resource_id

  step_scaling_policy_configuration {
    adjustment_type         = "ExactCapacity"
    cooldown                = 60
    metric_aggregation_type = "Maximum"

    step_adjustment {
      metric_interval_upper_bound = 0
      scaling_adjustment          = 0
    }
  }
}

resource "aws_cloudwatch_metric_alarm" "worker_out" {
  count = var.runtime_enabled ? 1 : 0

  alarm_name          = "${var.name_prefix}-worker-scale-out"
  alarm_description   = "Job queue has visible messages: add a worker task (step scaling)."
  namespace           = "AWS/SQS"
  metric_name         = "ApproximateNumberOfMessagesVisible"
  dimensions          = { QueueName = local.job_queue_name }
  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 1
  datapoints_to_alarm = 1
  comparison_operator = "GreaterThanOrEqualToThreshold"
  threshold           = 1
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_appautoscaling_policy.worker_out[0].arn]

  tags = { Name = "${var.name_prefix}-worker-scale-out" }
}

resource "aws_cloudwatch_metric_alarm" "worker_in" {
  count = var.runtime_enabled ? 1 : 0

  alarm_name          = "${var.name_prefix}-worker-scale-in"
  alarm_description   = "Job queue empty (visible + in flight = 0) for several minutes: scale the worker to 0."
  evaluation_periods  = var.worker_scale_in_minutes
  datapoints_to_alarm = var.worker_scale_in_minutes
  comparison_operator = "LessThanThreshold"
  threshold           = 1
  # SQS may stop publishing metrics for an idle queue: no data means empty.
  treat_missing_data = "breaching"
  alarm_actions      = [aws_appautoscaling_policy.worker_in[0].arn]

  metric_query {
    id          = "total"
    expression  = "visible + inflight"
    label       = "Visible plus in-flight job messages"
    return_data = true
  }

  metric_query {
    id = "visible"
    metric {
      namespace   = "AWS/SQS"
      metric_name = "ApproximateNumberOfMessagesVisible"
      dimensions  = { QueueName = local.job_queue_name }
      stat        = "Maximum"
      period      = 60
    }
  }

  metric_query {
    id = "inflight"
    metric {
      namespace   = "AWS/SQS"
      metric_name = "ApproximateNumberOfMessagesNotVisible"
      dimensions  = { QueueName = local.job_queue_name }
      stat        = "Maximum"
      period      = 60
    }
  }

  tags = { Name = "${var.name_prefix}-worker-scale-in" }
}
