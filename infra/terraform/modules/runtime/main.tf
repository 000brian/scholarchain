# runtime (decision 6 area 10): ECR, one ECS cluster, one image run as four
# Fargate ARM64 services (one per `Main --role`: query, evidence, graph behind
# the internal ALB, worker driven by SQS), the ops task definition (no
# service), the internal ALB and the API Gateway HTTP API with its VPC link
# (ingress.tf), Application Auto Scaling (autoscaling.tf) and the runtime
# roles (iam.tf). Role `all` is never used on AWS (it also runs the worker).
#
# Consolidation (justified, decision 6 area 10): the spec's per-stage services
# (query, retrieval, ranking, synthesis, verification) run inside the query
# role's workflow; query, evidence, graph and worker are independently
# deployable and each scales on its own.
#
# The services do not call each other (they share SQS and the stores; checked
# in modules/services: no HTTP client, Composition.aws builds only stores and
# model clients), so there is no Cloud Map; the internal ALB's DNS name is the
# one internal address.
#
# Switch runtime_enabled (false in course.tfvars): every desired count is 0
# and every scaling target has min 0 and max 0. While image_tag and
# graph_run_id are null the task definitions use fixed placeholders (a tag
# and a run that do not exist; harmless at desired count 0).

locals {
  # A tag that is never pushed (image tags are 40-hex git SHAs) and a run ID
  # that no graph run has (all zeros).
  placeholder_image_tag    = "placeholder-runtime-disabled"
  placeholder_graph_run_id = "run:v1:00000000000000000000000000000000"

  image        = "${aws_ecr_repository.services.repository_url}:${coalesce(var.image_tag, local.placeholder_image_tag)}"
  graph_run_id = coalesce(var.graph_run_id, local.placeholder_graph_run_id)

  alb_roles = ["query", "evidence", "graph"]

  # Configuration keys that are resource names or the account ID: never in
  # config/**; each task sets them from Terraform values under the variable
  # ConfigKey.envVarFor gives (SCHOLARCHAIN_CFG_ + UPPER_SNAKE path).
  config_environment = {
    SCHOLARCHAIN_CFG_AWS_ACCOUNT_ID                          = var.account_id
    SCHOLARCHAIN_CFG_STORAGE_RAW_BUCKET                      = var.data_bucket_name
    SCHOLARCHAIN_CFG_STORAGE_CURATED_BUCKET                  = var.data_bucket_name
    SCHOLARCHAIN_CFG_STORAGE_GRAPH_BUCKET                    = var.data_bucket_name
    SCHOLARCHAIN_CFG_STORAGE_VECTOR_BUCKET                   = var.vector_bucket_name
    SCHOLARCHAIN_CFG_STORAGE_NEPTUNE_ENDPOINT                = var.neptune_endpoint
    SCHOLARCHAIN_CFG_STORAGE_DYNAMO_DB_TABLE                 = var.table_names["RunLedger"]
    SCHOLARCHAIN_CFG_OBJECT_STORE_SEMANTIC_CONTEXT_BUCKET    = var.data_bucket_name
    SCHOLARCHAIN_CFG_OBJECT_STORE_REPORTS_BUCKET             = var.data_bucket_name
    SCHOLARCHAIN_CFG_OBJECT_STORE_LOGS_BUCKET                = var.data_bucket_name
    SCHOLARCHAIN_CFG_DYNAMO_DB_RUN_LEDGER_TABLE              = var.table_names["RunLedger"]
    SCHOLARCHAIN_CFG_DYNAMO_DB_ENTITY_LINK_CACHE_TABLE       = var.table_names["EntityLinkCache"]
    SCHOLARCHAIN_CFG_DYNAMO_DB_QUERY_SESSION_TABLE           = var.table_names["QuerySession"]
    SCHOLARCHAIN_CFG_DYNAMO_DB_EVIDENCE_BUNDLE_TABLE         = var.table_names["EvidenceBundle"]
    SCHOLARCHAIN_CFG_DYNAMO_DB_ARTIFACT_REGISTRY_TABLE       = var.table_names["ArtifactRegistry"]
    SCHOLARCHAIN_CFG_DYNAMO_DB_RESEARCHER_METRIC_CACHE_TABLE = var.table_names["ResearcherMetricCache"]
  }

  # The services also need the job queue URL (Composition.aws requires it).
  service_environment = merge(local.config_environment, {
    SCHOLARCHAIN_CFG_SERVICE_JOB_QUEUE_URL = var.job_queue_url
  })

  # Secrets per task, by the env-var NAME the code reads (config/course.json).
  # Every service role needs all four: Main refuses to start without both
  # service keys, and RuntimeService.aws builds the anthropic model and the
  # voyage query embedder for every role. ops runs the voyage ingest only.
  service_secret_names = [
    "SCHOLARCHAIN_VOYAGE_API_KEY",
    "SCHOLARCHAIN_ANTHROPIC_API_KEY",
    "SCHOLARCHAIN_SERVICE_API_KEY",
    "SCHOLARCHAIN_SERVICE_READONLY_API_KEY",
  ]
  ops_secret_names = ["SCHOLARCHAIN_VOYAGE_API_KEY"]
}

# ---- ECR -------------------------------------------------------------------------

resource "aws_ecr_repository" "services" {
  name                 = "${var.name_prefix}-services"
  image_tag_mutability = "IMMUTABLE"
  # Removing the repository with images in it is refused (cleanup.sh lists
  # and removes images first, with human confirmation).
  force_delete = false

  image_scanning_configuration {
    scan_on_push = true
  }

  encryption_configuration {
    encryption_type = "AES256"
  }

  tags = { Name = "${var.name_prefix}-services" }
}

resource "aws_ecr_lifecycle_policy" "services" {
  repository = aws_ecr_repository.services.name
  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "Keep the ${var.ecr_keep_images} most recent images"
      selection = {
        tagStatus   = "any"
        countType   = "imageCountMoreThan"
        countNumber = var.ecr_keep_images
      }
      action = { type = "expire" }
    }]
  })
}

# ---- ECS cluster ---------------------------------------------------------------------

resource "aws_ecs_cluster" "this" {
  name = "${var.name_prefix}-runtime"

  # Container Insights is paid; only the default AWS/ECS metrics are used.
  setting {
    name  = "containerInsights"
    value = "disabled"
  }

  tags = { Name = "${var.name_prefix}-runtime" }

  lifecycle {
    precondition {
      condition     = !var.runtime_enabled || (var.image_tag != null && var.graph_run_id != null)
      error_message = "runtime_enabled = true needs image_tag and graph_run_id."
    }
  }
}

# ---- task definitions ----------------------------------------------------------------

resource "aws_ecs_task_definition" "service" {
  for_each = toset(local.roles)

  family                   = "${var.name_prefix}-${each.key}"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = tostring(var.task_sizes[each.key].cpu)
  memory                   = tostring(var.task_sizes[each.key].memory)
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task[each.key].arn
  # Keep the previous revision ACTIVE when a deploy replaces this resource
  # (new image tag): scripts/rollback.sh points the service back at it.
  # Old revisions cost nothing; cleanup leaves them (README "Rollback").
  skip_destroy = true

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "ARM64"
  }

  container_definitions = jsonencode([{
    name      = "app"
    image     = local.image
    essential = true
    # The image's entry point is `java -jar /opt/scholarchain/app.jar`.
    command = [
      "--config", var.config_file_path,
      "--role", each.key,
      "--stores", "aws",
      "--graph-run", local.graph_run_id,
    ]
    # Every role serves /healthz and /readyz; only the ALB roles get traffic.
    portMappings = [{ containerPort = var.service_port, protocol = "tcp" }]
    environment  = [for k, v in local.service_environment : { name = k, value = v }]
    secrets = [for n in local.service_secret_names : {
      name      = n
      valueFrom = var.app_key_secret_arns_by_env_var[n]
    }]
    # Shutdown (S-5): ServiceServer.close() stops the worker loop and the HTTP
    # server, then shuts its four pools down one after another, each waiting up
    # to service.syncDeadlineMillis (25 s) plus 1 s, then joins threads for up
    # to 2 s: at most about 4 x 26 s + 2 s = 106 s. 120 s is the Fargate maximum.
    stopTimeout = var.stop_timeout_seconds
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = var.log_group_names["ecs"]
        awslogs-region        = var.region
        awslogs-stream-prefix = each.key
      }
    }
  }])

  tags = { Name = "${var.name_prefix}-${each.key}", role = each.key }
}

# ops: same image, no service. Started by hand with run-task (Phases 14-15);
# run-task overrides only the command, so the entry point here is the class
# path and the command is `<main class> <arguments>` given at run time.
resource "aws_ecs_task_definition" "ops" {
  family                   = "${var.name_prefix}-ops"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = tostring(var.task_sizes["ops"].cpu)
  memory                   = tostring(var.task_sizes["ops"].memory)
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task["ops"].arn
  # Same as the service task definitions: the prior revision stays ACTIVE.
  skip_destroy = true

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "ARM64"
  }

  # RagIngestJob stops when free disk at its --out root is below 15 GB.
  ephemeral_storage {
    size_in_gib = var.ops_ephemeral_storage_gib
  }

  container_definitions = jsonencode([{
    name        = "ops"
    image       = local.image
    essential   = true
    entryPoint  = ["java", "-cp", var.app_jar_path]
    environment = [for k, v in local.config_environment : { name = k, value = v }]
    secrets = [for n in local.ops_secret_names : {
      name      = n
      valueFrom = var.app_key_secret_arns_by_env_var[n]
    }]
    stopTimeout = var.stop_timeout_seconds
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = var.log_group_names["ops"]
        awslogs-region        = var.region
        awslogs-stream-prefix = "ops"
      }
    }
  }])

  tags = { Name = "${var.name_prefix}-ops", role = "ops" }
}

# ---- services ----------------------------------------------------------------------------

resource "aws_ecs_service" "this" {
  for_each = toset(local.roles)

  name            = each.key
  cluster         = aws_ecs_cluster.this.id
  task_definition = aws_ecs_task_definition.service[each.key].arn
  launch_type     = "FARGATE"
  # ALB roles start at 1 when the runtime is on (CPU target tracking cannot
  # start a service from 0); the worker starts at 0 and scales on its queue.
  desired_count                      = var.runtime_enabled && contains(local.alb_roles, each.key) ? 1 : 0
  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200
  health_check_grace_period_seconds  = contains(local.alb_roles, each.key) ? 120 : null
  enable_execute_command             = false
  propagate_tags                     = "SERVICE"
  wait_for_steady_state              = false

  network_configuration {
    subnets          = var.app_subnet_ids
    security_groups  = [var.security_group_ids[each.key]]
    assign_public_ip = false
  }

  dynamic "load_balancer" {
    for_each = contains(local.alb_roles, each.key) ? [1] : []
    content {
      target_group_arn = aws_lb_target_group.this[each.key].arn
      container_name   = "app"
      container_port   = var.service_port
    }
  }

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  tags = { Name = "${var.name_prefix}-${each.key}", role = each.key }

  # Application Auto Scaling owns the desired count after creation; the
  # switch moves the scaling targets' min and max (autoscaling.tf).
  lifecycle {
    ignore_changes = [desired_count]
  }

  depends_on = [aws_lb_listener_rule.route]
}
