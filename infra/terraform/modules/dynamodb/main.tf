# dynamodb (decision 6 area 6): the six tables exactly as
# modules/storage/src/main/scala/org/scholarchain/storage/dynamo/TableSpecs.scala
# defines them: every table has string attributes pk (partition key) and sk
# (sort key) and NO secondary index. Only QuerySession has a TTL attribute
# (expiresAt, epoch seconds).
#
# - Billing: PAY_PER_REQUEST (on-demand). Cost reason: no idle capacity
#   charge, no capacity planning; course traffic is tiny and bursty.
# - Encryption at rest: always on, with the AWS owned key (the DynamoDB
#   default; no KMS charge). No customer-managed key (decision 6 area 3).
# - PITR: off by variable for the course environment (cost; data is
#   reproducible from S3). Deletion protection: by variable, off (ephemeral).

locals {
  # logical name (TableSpecs) => settings. The physical name is
  # "<name_prefix>-<kebab name>"; the task definitions (step 2b) pass these
  # names to the services through SCHOLARCHAIN_CFG_DYNAMO_DB_*_TABLE.
  tables = {
    RunLedger = {
      name = "run-ledger"
      ttl  = null
    }
    EntityLinkCache = {
      name = "entity-link-cache"
      ttl  = null
    }
    QuerySession = {
      name = "query-session"
      ttl  = "expiresAt"
    }
    EvidenceBundle = {
      name = "evidence-bundle"
      ttl  = null
    }
    ArtifactRegistry = {
      name = "artifact-registry"
      ttl  = null
    }
    ResearcherMetricCache = {
      name = "researcher-metric-cache"
      ttl  = null
    }
  }
}

resource "aws_dynamodb_table" "this" {
  for_each = local.tables

  name                        = "${var.name_prefix}-${each.value.name}"
  billing_mode                = "PAY_PER_REQUEST"
  hash_key                    = "pk"
  range_key                   = "sk"
  table_class                 = "STANDARD"
  deletion_protection_enabled = var.deletion_protection_enabled

  attribute {
    name = "pk"
    type = "S"
  }

  attribute {
    name = "sk"
    type = "S"
  }

  dynamic "ttl" {
    for_each = each.value.ttl == null ? [] : [each.value.ttl]

    content {
      attribute_name = ttl.value
      enabled        = true
    }
  }

  point_in_time_recovery {
    enabled = var.point_in_time_recovery_enabled
  }

  tags = { Name = "${var.name_prefix}-${each.value.name}", "logical-table" = each.key }
}
