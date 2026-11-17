# S3 policy of the EMR EC2 instance role (decision 6 area 5).
# What the jobs do (read from the code, Phase 12 step 2a):
#   - Hadoop (modules/hadoop .../common/JobRunner.scala): reads raw/ (and its
#     upstream runs under curated/); writes run-addressed output under
#     curated/ through FileOutputFormat (task commit renames _temporary files:
#     on S3 a rename is copy + delete); removes an incomplete run directory
#     (fs.delete) when scholarchain.output.replaceIncomplete is true.
#   - Spark (modules/spark .../RunFs.scala, GraphAnalyticsJob.scala): reads
#     the Hadoop export and identity assignments (curated/), writes the graph
#     run under graph/ through the Hadoop FileSystem API and Spark writers
#     (manifests overwritten with create(p, true); committer renames).
#   - Both read jars and the bootstrap script from artifacts/; EMR writes
#     cluster logs to logs/emr/.
# So: delete ONLY on the jobs' own output prefixes curated/ and graph/
# (needed for rename-by-copy-and-delete and replaceIncomplete); no delete on
# raw/, artifacts/, logs/, rag/ or reports/. Untested on s3:// until Phase 13/14.

locals {
  emr_read_prefixes  = [var.data_prefixes.raw, var.data_prefixes.curated, var.data_prefixes.graph, var.data_prefixes.artifacts]
  emr_write_prefixes = [var.data_prefixes.curated, var.data_prefixes.graph]
}

data "aws_iam_policy_document" "emr_instance_s3" {
  statement {
    sid       = "ListOwnPrefixes"
    actions   = ["s3:ListBucket"]
    resources = [var.data_bucket_arn]

    condition {
      test     = "StringLike"
      variable = "s3:prefix"
      values = flatten([
        for p in concat(local.emr_read_prefixes, ["${var.data_prefixes.logs}/emr"]) : [p, "${p}/*"]
      ])
    }
  }

  # Bucket-level reads EMRFS/S3A and the committers call; they take no prefix.
  statement {
    sid       = "BucketMetadata"
    actions   = ["s3:GetBucketLocation", "s3:ListBucketMultipartUploads"]
    resources = [var.data_bucket_arn]
  }

  statement {
    sid       = "ReadInputs"
    actions   = ["s3:GetObject"]
    resources = [for p in local.emr_read_prefixes : "${var.data_bucket_arn}/${p}/*"]
  }

  statement {
    sid = "WriteOwnOutputs"
    actions = [
      "s3:PutObject",
      "s3:DeleteObject",
      "s3:AbortMultipartUpload",
      "s3:ListMultipartUploadParts",
    ]
    resources = [for p in local.emr_write_prefixes : "${var.data_bucket_arn}/${p}/*"]
  }

  statement {
    sid       = "WriteClusterLogs"
    actions   = ["s3:PutObject", "s3:AbortMultipartUpload"]
    resources = ["${var.data_bucket_arn}/${var.data_prefixes.logs}/emr/*"]
  }
}

resource "aws_iam_role_policy" "emr_instance_s3" {
  name   = "data-lake-access"
  role   = aws_iam_role.emr_instance.id
  policy = data.aws_iam_policy_document.emr_instance_s3.json
}
