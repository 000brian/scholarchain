# S3 policy of the Neptune loader role (decision 6 area 7): read-only on the
# graph prefix of the data lake (the bulk-load files written by the graph
# export). The load is started by the ops task (step 2b) with this role's ARN
# as iamRoleArn; the cluster reaches S3 through the S3 gateway endpoint.

data "aws_iam_policy_document" "neptune_loader_s3" {
  statement {
    sid       = "ListGraphPrefix"
    actions   = ["s3:ListBucket"]
    resources = [var.data_bucket_arn]

    condition {
      test     = "StringLike"
      variable = "s3:prefix"
      values   = [var.data_prefixes.graph, "${var.data_prefixes.graph}/*"]
    }
  }

  statement {
    sid       = "BucketLocation"
    actions   = ["s3:GetBucketLocation"]
    resources = [var.data_bucket_arn]
  }

  statement {
    sid       = "ReadGraphObjects"
    actions   = ["s3:GetObject"]
    resources = ["${var.data_bucket_arn}/${var.data_prefixes.graph}/*"]
  }
}

resource "aws_iam_role_policy" "neptune_loader_s3" {
  name   = "graph-prefix-read"
  role   = aws_iam_role.neptune_loader.id
  policy = data.aws_iam_policy_document.neptune_loader_s3.json
}
