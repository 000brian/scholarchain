# data-lake (decision 6 area 4): ONE bucket with the prefixes raw/, curated/,
# graph/, rag/, artifacts/, logs/, reports/. One bucket is enough: every IAM
# policy scopes access by prefix, and no policy needs a separate bucket.
#
# - Name from bucket_prefix (no literal bucket name anywhere).
# - Block Public Access (all four), BucketOwnerEnforced (ACLs off), TLS-only
#   bucket policy, SSE-S3 default encryption (AWS-managed; no KMS cost).
# - Versioning ON for this bucket: it holds the grading evidence (reports/) and
#   the inputs of the graph load, so an overwrite or removal can be undone for
#   noncurrent_version_expiration_days. No separate evidence bucket.
# - Lifecycle: logs/ expire; noncurrent versions expire; incomplete multipart
#   uploads are aborted; expired delete markers are cleaned up.
# - force_destroy = false: Terraform never removes a bucket that still holds
#   objects. Cleanup path (area 13, scripts/cleanup.sh): archive evidence
#   first, then empty the bucket (all versions) with the documented, confirmed
#   cleanup step, then remove it. No Glue catalog (not used).

locals {
  prefixes = {
    raw       = "raw"
    curated   = "curated"
    graph     = "graph"
    rag       = "rag"
    artifacts = "artifacts"
    logs      = "logs"
    reports   = "reports"
  }
}

resource "aws_s3_bucket" "data" {
  bucket_prefix = "${var.name_prefix}-data-"
  force_destroy = false

  tags = { Name = "${var.name_prefix}-data" }
}

resource "aws_s3_bucket_ownership_controls" "data" {
  bucket = aws_s3_bucket.data.id

  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_public_access_block" "data" {
  bucket = aws_s3_bucket.data.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "data" {
  bucket = aws_s3_bucket.data.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_versioning" "data" {
  bucket = aws_s3_bucket.data.id

  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "data" {
  bucket = aws_s3_bucket.data.id

  rule {
    id     = "expire-logs"
    status = "Enabled"

    filter {
      prefix = "${local.prefixes.logs}/"
    }

    expiration {
      days = var.logs_expiration_days
    }

    noncurrent_version_expiration {
      noncurrent_days = 1
    }
  }

  rule {
    id     = "expire-noncurrent-versions"
    status = "Enabled"

    filter {}

    noncurrent_version_expiration {
      noncurrent_days = var.noncurrent_version_expiration_days
    }

    abort_incomplete_multipart_upload {
      days_after_initiation = 1
    }
  }

  rule {
    id     = "remove-expired-delete-markers"
    status = "Enabled"

    filter {}

    expiration {
      expired_object_delete_marker = true
    }
  }

  # Versioning must be on before rules about noncurrent versions apply.
  depends_on = [aws_s3_bucket_versioning.data]
}

data "aws_iam_policy_document" "data_bucket" {
  statement {
    sid     = "DenyInsecureTransport"
    effect  = "Deny"
    actions = ["s3:*"]
    resources = [
      aws_s3_bucket.data.arn,
      "${aws_s3_bucket.data.arn}/*",
    ]

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

  statement {
    sid     = "DenyOldTls"
    effect  = "Deny"
    actions = ["s3:*"]
    resources = [
      aws_s3_bucket.data.arn,
      "${aws_s3_bucket.data.arn}/*",
    ]

    principals {
      type        = "*"
      identifiers = ["*"]
    }

    condition {
      test     = "NumericLessThan"
      variable = "s3:TlsVersion"
      values   = ["1.2"]
    }
  }
}

resource "aws_s3_bucket_policy" "data" {
  bucket = aws_s3_bucket.data.id
  policy = data.aws_iam_policy_document.data_bucket.json

  # Public access block first, so the policy is evaluated with it in place.
  depends_on = [aws_s3_bucket_public_access_block.data]
}
