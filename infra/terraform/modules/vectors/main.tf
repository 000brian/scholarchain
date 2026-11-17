# vectors (decision 6 area 8): one S3 Vectors bucket and the five indexes of
# modules/storage/src/main/resources/s3vectors/index-spec-v1.json (read by the
# root with file(); not copied, so the storage contract stays the one source).
#
# - Encryption: SSE-S3 (AES256), AWS-managed (no CMK).
# - Index mode: vector buckets created after September 30, 2026 default to
#   ENHANCED (S3 User Guide, "Changing a vector index's mode"); nothing to set.
# - Access: the bucket policy denies the vector DATA plane to every principal
#   except the runtime task roles and the ops role (data_principal_arns, from
#   step 2b). Control-plane actions (create/describe/remove indexes, the
#   policy itself) stay with the deployment path. Until step 2b passes the
#   ARNs, no bucket policy exists and nothing but identity policies grants
#   access (no role has such a policy before step 2b).
# - Application limits (README): PutVectors batches <= 500, topK <= 30.

locals {
  vector_bucket_name = "${var.name_prefix}-vectors-${var.name_suffix}"
  indexes            = { for i in var.index_spec.indexes : i.name => i }

  data_plane_actions = [
    "s3vectors:DeleteVectors",
    "s3vectors:GetVectors",
    "s3vectors:ListVectors",
    "s3vectors:PutVectors",
    "s3vectors:QueryVectors",
  ]
}

resource "aws_s3vectors_vector_bucket" "this" {
  vector_bucket_name = local.vector_bucket_name

  encryption_configuration {
    sse_type = "AES256"
  }

  tags = { Name = local.vector_bucket_name }
}

resource "aws_s3vectors_index" "this" {
  for_each = local.indexes

  vector_bucket_name = aws_s3vectors_vector_bucket.this.vector_bucket_name
  index_name         = each.key
  data_type          = var.index_spec.dataType
  dimension          = var.index_spec.dimension
  distance_metric    = var.index_spec.distanceMetric

  metadata_configuration {
    non_filterable_metadata_keys = each.value.nonFilterableKeys
  }

  tags = { Name = each.key, "config-key" = each.value.configKey }
}

data "aws_iam_policy_document" "vector_bucket" {
  count = length(var.data_principal_arns) > 0 ? 1 : 0

  statement {
    sid     = "DataPlaneOnlyForRuntimeAndOps"
    effect  = "Deny"
    actions = local.data_plane_actions
    resources = [
      aws_s3vectors_vector_bucket.this.vector_bucket_arn,
      "${aws_s3vectors_vector_bucket.this.vector_bucket_arn}/index/*",
    ]

    principals {
      type        = "*"
      identifiers = ["*"]
    }

    condition {
      test     = "ArnNotEquals"
      variable = "aws:PrincipalArn"
      values   = var.data_principal_arns
    }
  }
}

resource "aws_s3vectors_vector_bucket_policy" "this" {
  count = length(var.data_principal_arns) > 0 ? 1 : 0

  vector_bucket_arn = aws_s3vectors_vector_bucket.this.vector_bucket_arn
  policy            = data.aws_iam_policy_document.vector_bucket[0].json
}
