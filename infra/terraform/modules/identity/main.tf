# identity (decision 6 area 3): roles that do not belong to one later area.
#   - EMR service role and EMR EC2 instance role + instance profile (area 5 adds
#     the instance role's S3 policy, emr.tf)
#   - Neptune loader role (area 7 adds its S3 policy, neptune-loader.tf)
#   - deployment role (deployment.tf), assumed by the human in Phase 14
#   - Secrets Manager containers (app-keys.tf)
# Runtime task roles, the ECS execution role and the ops role live in the
# runtime module (area 10); the Step Functions role lives in workflow (area 11).
# Runtime and deployment permissions are separate roles.
# Every project role except the deployment role carries the permissions
# boundary of boundary.tf (repair I-01); runtime and workflow receive its ARN.

locals {
  arn_prefix = "arn:${var.partition}"
}

# ---------------------------------------------------------------- EMR service role
# Trust policy from the EMR Management Guide ("Service role for Amazon EMR"),
# with aws:SourceAccount and aws:SourceArn against the confused deputy.

data "aws_iam_policy_document" "emr_service_trust" {
  statement {
    sid     = "EmrAssume"
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["elasticmapreduce.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [var.account_id]
    }

    condition {
      test     = "ArnLike"
      variable = "aws:SourceArn"
      values   = ["${local.arn_prefix}:elasticmapreduce:${var.region}:${var.account_id}:*"]
    }
  }
}

resource "aws_iam_role" "emr_service" {
  name_prefix          = "${var.name_prefix}-emr-service-"
  description          = "EMR service role for the transient ScholarChain clusters"
  assume_role_policy   = data.aws_iam_policy_document.emr_service_trust.json
  max_session_duration = 3600
  permissions_boundary = aws_iam_policy.role_boundary.arn

  tags = { Name = "${var.name_prefix}-emr-service" }
}

# AWS managed, tag-scoped policy: EMR may act only on subnets, security groups
# and the VPC that carry for-use-with-amazon-emr-managed-policies=true (the
# network module tags them) and on instances it tags itself.
resource "aws_iam_role_policy_attachment" "emr_service_v2" {
  role       = aws_iam_role.emr_service.name
  policy_arn = "${local.arn_prefix}:iam::aws:policy/service-role/AmazonEMRServicePolicy_v2"
}

# AmazonEMRServicePolicy_v2 allows iam:PassRole only for the default role name
# EMR_EC2_DefaultRole; our instance role has its own name, so it is passed here,
# to EC2 only.
data "aws_iam_policy_document" "emr_service_pass_instance_role" {
  statement {
    sid       = "PassInstanceRoleToEc2"
    actions   = ["iam:PassRole"]
    resources = [aws_iam_role.emr_instance.arn]

    condition {
      test     = "StringLike"
      variable = "iam:PassedToService"
      values   = ["ec2.amazonaws.com*"]
    }
  }
}

resource "aws_iam_role_policy" "emr_service_pass_instance_role" {
  name   = "pass-instance-role"
  role   = aws_iam_role.emr_service.id
  policy = data.aws_iam_policy_document.emr_service_pass_instance_role.json
}

# ---------------------------------------------------------------- EMR instance role

data "aws_iam_policy_document" "ec2_trust" {
  statement {
    sid     = "Ec2Assume"
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "emr_instance" {
  name_prefix          = "${var.name_prefix}-emr-instance-"
  description          = "EC2 instance role of the transient EMR clusters (S3 data-lake access only)"
  assume_role_policy   = data.aws_iam_policy_document.ec2_trust.json
  max_session_duration = 3600
  permissions_boundary = aws_iam_policy.role_boundary.arn

  tags = { Name = "${var.name_prefix}-emr-instance" }
}

resource "aws_iam_instance_profile" "emr_instance" {
  name_prefix = "${var.name_prefix}-emr-instance-"
  role        = aws_iam_role.emr_instance.name

  tags = { Name = "${var.name_prefix}-emr-instance" }
}

# ---------------------------------------------------------------- Neptune loader role
# Neptune bulk loader: the cluster assumes this role (trust: rds.amazonaws.com,
# Neptune User Guide, "Creating an IAM role to access Amazon S3"). The S3 read
# policy on the graph prefix is in neptune-loader.tf (area 7).

data "aws_iam_policy_document" "rds_trust" {
  statement {
    sid     = "NeptuneAssume"
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["rds.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "neptune_loader" {
  name_prefix          = "${var.name_prefix}-neptune-loader-"
  description          = "Neptune bulk loader: read the graph prefix of the data lake"
  assume_role_policy   = data.aws_iam_policy_document.rds_trust.json
  max_session_duration = 3600
  permissions_boundary = aws_iam_policy.role_boundary.arn

  tags = { Name = "${var.name_prefix}-neptune-loader" }
}
