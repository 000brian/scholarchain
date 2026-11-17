# Ingress (decided, decision 6 area 10): API Gateway HTTP API (AWS-managed
# https://...execute-api... address) -> VPC link in the private app subnets
# -> INTERNAL ALB (HTTP listener; traffic stays inside the VPC) -> query,
# evidence, graph. The ALB accepts traffic only from the VPC link's security
# group (network module). The service's API keys are the only authentication
# (HTTP APIs have no IP allow list); API Gateway passes every header,
# including the API-key header, through unchanged (HTTP_PROXY integration).

# ---- internal ALB -----------------------------------------------------------------

resource "aws_lb" "internal" {
  name                       = "${var.name_prefix}-alb"
  internal                   = true
  load_balancer_type         = "application"
  subnets                    = var.app_subnet_ids
  security_groups            = [var.security_group_ids["alb"]]
  drop_invalid_header_fields = true
  idle_timeout               = 60
  # Course environment: removable by the cleanup path (decision 6 area 13).
  enable_deletion_protection = false

  tags = { Name = "${var.name_prefix}-alb" }
}

resource "aws_lb_target_group" "this" {
  for_each = toset(local.alb_roles)

  # name_prefix is at most 6 characters; a prefix lets a replacement be
  # created before the old group is removed.
  name_prefix          = "sc${substr(each.key, 0, 1)}-"
  port                 = var.service_port
  protocol             = "HTTP"
  target_type          = "ip"
  vpc_id               = var.vpc_id
  deregistration_delay = var.deregistration_delay_seconds

  # Liveness route of every role (Routes.scala: GET /healthz, open).
  health_check {
    enabled             = true
    path                = "/healthz"
    protocol            = "HTTP"
    matcher             = "200"
    interval            = 15
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }

  tags = { Name = "${var.name_prefix}-${each.key}", role = each.key }

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.internal.arn
  port              = var.alb_listener_port
  protocol          = "HTTP"

  # Anything that matches no rule gets a fixed 404.
  default_action {
    type = "fixed-response"

    fixed_response {
      content_type = "application/json"
      message_body = "{\"error\":{\"code\":\"NOT_FOUND\"}}"
      status_code  = "404"
    }
  }

  tags = { Name = "${var.name_prefix}-alb-http" }
}

locals {
  # Path rules (decision 6 area 10), checked against Routes.scala:
  #   query:    POST /v1/query, GET /v1/jobs/{jobId}, GET /v1/jobs/{jobId}/result
  #   evidence: GET /v1/evidence/{evidenceId}, GET /v1/explain/{queryId}
  #   graph:    GET /v1/graph/nodes/{nodeId}, GET /v1/graph/nodes/{nodeId}/neighbors
  # Deviation (reported): /healthz and /readyz (open on every role) are also
  # routed, to query, so the smoke test can reach them through API Gateway.
  route_rules = {
    query    = { priority = 10, paths = ["/v1/query", "/v1/jobs/*", "/healthz", "/readyz"] }
    evidence = { priority = 20, paths = ["/v1/evidence/*", "/v1/explain/*"] }
    graph    = { priority = 30, paths = ["/v1/graph/*"] }
  }
}

resource "aws_lb_listener_rule" "route" {
  for_each = local.route_rules

  listener_arn = aws_lb_listener.http.arn
  priority     = each.value.priority

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.this[each.key].arn
  }

  condition {
    path_pattern {
      values = each.value.paths
    }
  }

  tags = { Name = "${var.name_prefix}-${each.key}-route" }
}

# ---- API Gateway HTTP API + VPC link -----------------------------------------------

resource "aws_apigatewayv2_vpc_link" "this" {
  name               = "${var.name_prefix}-vpclink"
  subnet_ids         = var.app_subnet_ids
  security_group_ids = [var.security_group_ids["vpc_link"]]

  tags = { Name = "${var.name_prefix}-vpclink" }
}

resource "aws_apigatewayv2_api" "this" {
  name          = "${var.name_prefix}-api"
  protocol_type = "HTTP"
  description   = "ScholarChain REST API (course). Authentication: the service's API keys only."

  tags = { Name = "${var.name_prefix}-api" }
}

resource "aws_apigatewayv2_integration" "alb" {
  api_id             = aws_apigatewayv2_api.this.id
  integration_type   = "HTTP_PROXY"
  integration_method = "ANY"
  integration_uri    = aws_lb_listener.http.arn
  connection_type    = "VPC_LINK"
  connection_id      = aws_apigatewayv2_vpc_link.this.id
  # HTTP API integrations end at about 30 s; service.syncDeadlineMillis is 25 s.
  timeout_milliseconds   = 30000
  payload_format_version = "1.0"
}

# One catch-all route: the ALB decides which service answers (or 404).
resource "aws_apigatewayv2_route" "default" {
  api_id    = aws_apigatewayv2_api.this.id
  route_key = "$default"
  target    = "integrations/${aws_apigatewayv2_integration.alb.id}"
}

resource "aws_apigatewayv2_stage" "default" {
  api_id      = aws_apigatewayv2_api.this.id
  name        = "$default"
  auto_deploy = true

  # Sized for one person's smoke tests and demo.
  default_route_settings {
    throttling_burst_limit = var.api_throttling_burst_limit
    throttling_rate_limit  = var.api_throttling_rate_limit
  }

  # Access logs: request metadata only; no request bodies, no headers.
  access_log_settings {
    destination_arn = var.log_group_arns["api_access"]
    format = jsonencode({
      requestId          = "$context.requestId"
      requestTime        = "$context.requestTime"
      httpMethod         = "$context.httpMethod"
      path               = "$context.path"
      routeKey           = "$context.routeKey"
      status             = "$context.status"
      responseLength     = "$context.responseLength"
      responseLatency    = "$context.responseLatency"
      integrationStatus  = "$context.integrationStatus"
      integrationLatency = "$context.integrationLatency"
      integrationError   = "$context.integrationErrorMessage"
      sourceIp           = "$context.identity.sourceIp"
    })
  }

  tags = { Name = "${var.name_prefix}-api-default" }
}
