# Runtime roles (decision 6 areas 3 and 10): one ECS execution role and one
# task role per role (query, evidence, graph, worker) plus the ops role.
# Runtime roles are separate from the deployment role (identity module).
#
# Each task role's actions are derived from the code the role runs on AWS
# (services Main -> Composition.aws -> RuntimeService.aws and ServiceStores.aws;
# storage's AwsStorageEnvironment, DynamoDbKeyValueStore, S3ObjectStore,
# S3VectorsIndex, NeptuneGraphRead, NeptuneBulkLoader, SqsWorkQueue):
#   - every role builds the whole AWS runtime and its /readyz probe reads the
#     ResearcherMetricCache table (GetItem) and Neptune (openCypher read);
#   - DynamoDbKeyValueStore calls only GetItem, PutItem (conditional) and Query;
#   - S3ObjectStore calls GetObject, HeadObject (s3:GetObject) and PutObject;
#   - S3VectorsIndex calls PutVectors, GetVectors and QueryVectors;
#   - Neptune reads use openCypher queries (neptune-db:ReadDataViaQuery);
#     the bulk loader uses StartLoaderJob and GetLoaderJobStatus;
#   - SqsWorkQueue: see the workflow module.
# Bedrock is not granted: the course configuration uses the anthropic and
# voyage providers (Bedrock on-demand quota is 0, Llm.scala).

locals {
  roles         = ["query", "evidence", "graph", "worker"]
  task_roles    = concat(local.roles, ["ops"])
  arn           = "arn:${var.partition}"
  neptune_db    = "${local.arn}:neptune-db:${var.region}:${var.account_id}:${var.neptune_cluster_resource_id}/*"
  index_arns    = values(var.vector_index_arns)
  bucket_object = "${var.data_bucket_arn}/"

  # Tables per role, with the actions the code calls on them.
  read_actions  = ["dynamodb:GetItem", "dynamodb:Query"]
  write_actions = ["dynamodb:GetItem", "dynamodb:Query", "dynamodb:PutItem"]
  workflow_tables = {
    read  = ["ResearcherMetricCache", "EntityLinkCache"]
    write = ["EvidenceBundle", "RunLedger", "QuerySession"]
  }
  role_tables = {
    # query and worker run the query workflow (metrics, entity links, evidence
    # bundle writes, audit appends to RunLedger) and the job store (QuerySession).
    query  = local.workflow_tables
    worker = local.workflow_tables
    # evidence: /v1/evidence (EvidenceBundle) and /v1/explain (RunLedger audit
    # reads); ResearcherMetricCache for /readyz.
    evidence = { read = ["EvidenceBundle", "RunLedger", "ResearcherMetricCache"], write = [] }
    # graph: Neptune only; ResearcherMetricCache for /readyz.
    graph = { read = ["ResearcherMetricCache"], write = [] }
  }
}

# ---- trust -----------------------------------------------------------------

data "aws_iam_policy_document" "ecs_tasks_trust" {
  statement {
    sid     = "EcsTasksOfThisAccount"
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [var.account_id]
    }

    condition {
      test     = "ArnLike"
      variable = "aws:SourceArn"
      values   = ["${local.arn}:ecs:${var.region}:${var.account_id}:*"]
    }
  }
}

# ---- execution role: image pull, log writing, the four secret values -------

resource "aws_iam_role" "execution" {
  name_prefix          = "${var.name_prefix}-ecs-exec-"
  description          = "ECS task execution role: pulls the services image, writes task logs, reads the four app key secrets."
  assume_role_policy   = data.aws_iam_policy_document.ecs_tasks_trust.json
  permissions_boundary = var.permissions_boundary_arn

  tags = { Name = "${var.name_prefix}-ecs-exec" }
}

data "aws_iam_policy_document" "execution" {
  statement {
    sid = "EcrAuthToken"
    # The Service Authorization Reference allows only "*" for
    # ecr:GetAuthorizationToken (it has no resource type).
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }

  statement {
    sid = "PullServicesImage"
    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:BatchGetImage",
      "ecr:GetDownloadUrlForLayer",
    ]
    resources = [aws_ecr_repository.services.arn]
  }

  statement {
    sid     = "WriteTaskLogs"
    actions = ["logs:CreateLogStream", "logs:PutLogEvents"]
    resources = [
      "${var.log_group_arns["ecs"]}:log-stream:*",
      "${var.log_group_arns["ops"]}:log-stream:*",
    ]
  }

  statement {
    sid       = "ReadAppKeyValues"
    actions   = ["secretsmanager:GetSecretValue"]
    resources = values(var.app_key_secret_arns_by_env_var)
  }
}

resource "aws_iam_role_policy" "execution" {
  name   = "ecs-execution"
  role   = aws_iam_role.execution.id
  policy = data.aws_iam_policy_document.execution.json
}

# ---- task roles ---------------------------------------------------------------

resource "aws_iam_role" "task" {
  for_each = toset(local.task_roles)

  name_prefix          = "${var.name_prefix}-${each.key}-"
  description          = "ScholarChain ${each.key} task role (runtime; separate from the deployment role)."
  assume_role_policy   = data.aws_iam_policy_document.ecs_tasks_trust.json
  permissions_boundary = var.permissions_boundary_arn

  tags = { Name = "${var.name_prefix}-${each.key}-task", role = each.key }
}

data "aws_iam_policy_document" "service_task" {
  for_each = toset(local.roles)

  # Every role: openCypher reads (graph routes, the workflow, /readyz).
  statement {
    sid       = "NeptuneRead"
    actions   = ["neptune-db:connect", "neptune-db:ReadDataViaQuery"]
    resources = [local.neptune_db]
  }

  statement {
    sid       = "TablesRead"
    actions   = local.read_actions
    resources = [for t in local.role_tables[each.key].read : var.table_arns[t]]
  }

  dynamic "statement" {
    for_each = length(local.role_tables[each.key].write) > 0 ? [1] : []
    content {
      sid       = "TablesReadWrite"
      actions   = local.write_actions
      resources = [for t in local.role_tables[each.key].write : var.table_arns[t]]
    }
  }

  # query and worker: semantic retrieval (S3 Vectors queries and the text
  # objects under the semantic-context prefix, rag/).
  dynamic "statement" {
    for_each = contains(["query", "worker"], each.key) ? [1] : []
    content {
      sid       = "VectorQueries"
      actions   = ["s3vectors:QueryVectors", "s3vectors:GetVectors"]
      resources = local.index_arns
    }
  }

  dynamic "statement" {
    for_each = contains(["query", "worker"], each.key) ? [1] : []
    content {
      sid       = "SemanticTextRead"
      actions   = ["s3:GetObject"]
      resources = ["${local.bucket_object}${var.data_prefixes["rag"]}/*"]
    }
  }

  # ListBucket on the rag prefix only, so a missing text object is a 404
  # (HeadObject/GetObject without ListBucket returns 403).
  dynamic "statement" {
    for_each = contains(["query", "worker"], each.key) ? [1] : []
    content {
      sid       = "SemanticTextList"
      actions   = ["s3:ListBucket"]
      resources = [var.data_bucket_arn]

      condition {
        test     = "StringLike"
        variable = "s3:prefix"
        values   = [var.data_prefixes["rag"], "${var.data_prefixes["rag"]}/*"]
      }
    }
  }

  # query: POST /v1/query in async mode enqueues one job message.
  dynamic "statement" {
    for_each = each.key == "query" ? [1] : []
    content {
      sid       = "JobQueueSend"
      actions   = ["sqs:SendMessage"]
      resources = [var.job_queue_arn]
    }
  }

  # worker: receives, acknowledges (DeleteMessage) and releases job messages.
  dynamic "statement" {
    for_each = each.key == "worker" ? [1] : []
    content {
      sid       = "JobQueueConsume"
      actions   = ["sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:ChangeMessageVisibility"]
      resources = [var.job_queue_arn]
    }
  }
}

resource "aws_iam_role_policy" "service_task" {
  for_each = toset(local.roles)

  name   = "${each.key}-runtime"
  role   = aws_iam_role.task[each.key].id
  policy = data.aws_iam_policy_document.service_task[each.key].json
}

# ops: storage loaders and the rag embedding ingest (decision 6 area 10).
data "aws_iam_policy_document" "ops_task" {
  statement {
    sid       = "DataLakeList"
    actions   = ["s3:ListBucket"]
    resources = [var.data_bucket_arn]

    condition {
      test     = "StringLike"
      variable = "s3:prefix"
      values = [
        var.data_prefixes["graph"], "${var.data_prefixes["graph"]}/*",
        var.data_prefixes["rag"], "${var.data_prefixes["rag"]}/*",
      ]
    }
  }

  statement {
    sid     = "GraphAndRagRead"
    actions = ["s3:GetObject"]
    resources = [
      "${local.bucket_object}${var.data_prefixes["graph"]}/*",
      "${local.bucket_object}${var.data_prefixes["rag"]}/*",
    ]
  }

  statement {
    sid       = "RagWrite"
    actions   = ["s3:PutObject"]
    resources = ["${local.bucket_object}${var.data_prefixes["rag"]}/*"]
  }

  statement {
    sid       = "TablesLoad"
    actions   = local.write_actions
    resources = values(var.table_arns)
  }

  # PutVectors (ingest), GetVectors (dedupe), ListVectors (inspection),
  # QueryVectors (the ingest job's retrieval smoke samples).
  statement {
    sid = "VectorIngest"
    actions = [
      "s3vectors:PutVectors",
      "s3vectors:GetVectors",
      "s3vectors:ListVectors",
      "s3vectors:QueryVectors",
    ]
    resources = local.index_arns
  }

  statement {
    sid = "NeptuneBulkLoad"
    actions = [
      "neptune-db:connect",
      "neptune-db:StartLoaderJob",
      "neptune-db:GetLoaderJobStatus",
    ]
    resources = [local.neptune_db]
  }

  statement {
    sid = "EmbeddingQueue"
    actions = [
      "sqs:SendMessage",
      "sqs:ReceiveMessage",
      "sqs:DeleteMessage",
      "sqs:ChangeMessageVisibility",
    ]
    resources = [var.embedding_queue_arn]
  }
}

resource "aws_iam_role_policy" "ops_task" {
  name   = "ops-loaders"
  role   = aws_iam_role.task["ops"].id
  policy = data.aws_iam_policy_document.ops_task.json
}
