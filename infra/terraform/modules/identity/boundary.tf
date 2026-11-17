# Permissions boundary of every project role (repair I-01 (b)).
#
# Every role the deployment role may create or change must carry this managed
# policy as its permissions boundary (deployment.tf: iam:PermissionsBoundary
# condition on CreateRole, PutRolePolicy, AttachRolePolicy,
# PutRolePermissionsBoundary and UpdateAssumeRolePolicy, verified against the
# Service Authorization Reference for IAM in step 4). A role's effective
# permissions are the intersection of its identity policies and this
# boundary, so even an inline policy of "*" on "*" written by the deployment
# role gives a project role no more than what is listed here.
#
# The boundary is an allow-list: the union of what the project roles need
# (EMR service and instance roles, Neptune loader, ECS execution role, the
# five task roles, the Step Functions role), with the same resources where a
# role policy scopes them. It grants no IAM write except PassRole of the two
# EMR roles and CreateServiceLinkedRole, so a bounded role can never change a
# role, a policy or a boundary. The deployment role itself does not carry it
# (it would need every Terraform action); deployment.tf denies it every write
# on itself instead.
#
# Name: fixed (not name_prefix) and outside the role/ namespace, so it is
# never matched by the role patterns of deployment.tf. Changing or removing
# it needs the human's own profile (deployment.tf denies policy-version
# writes on it).

locals {
  regional_arn = "${local.arn_prefix}:%s:${var.region}:${var.account_id}:%s"
}

data "aws_iam_policy_document" "role_boundary" {
  # ---- data lake (EMR instance role, Neptune loader, task roles) ----------
  statement {
    sid       = "DataLakeBucket"
    actions   = ["s3:ListBucket", "s3:GetBucketLocation", "s3:ListBucketMultipartUploads"]
    resources = [var.data_bucket_arn]
  }

  statement {
    sid = "DataLakeObjects"
    actions = [
      "s3:GetObject",
      "s3:PutObject",
      "s3:AbortMultipartUpload",
      "s3:ListMultipartUploadParts",
    ]
    resources = ["${var.data_bucket_arn}/*"]
  }

  # Object removal only on the EMR jobs' output prefixes (emr.tf); never on
  # raw/, artifacts/, logs/, rag/ or reports/ (grading evidence).
  statement {
    sid     = "DataLakeOutputDelete"
    actions = ["s3:DeleteObject"]
    resources = [
      "${var.data_bucket_arn}/${var.data_prefixes.curated}/*",
      "${var.data_bucket_arn}/${var.data_prefixes.graph}/*",
    ]
  }

  # ---- runtime data stores (task roles) -----------------------------------
  statement {
    sid       = "ProjectTableItems"
    actions   = ["dynamodb:GetItem", "dynamodb:Query", "dynamodb:PutItem"]
    resources = [format(local.regional_arn, "dynamodb", "table/${var.name_prefix}-*")]
  }

  statement {
    sid = "NeptuneData"
    actions = [
      "neptune-db:connect",
      "neptune-db:ReadDataViaQuery",
      "neptune-db:StartLoaderJob",
      "neptune-db:GetLoaderJobStatus",
    ]
    resources = [format(local.regional_arn, "neptune-db", "*/*")]
  }

  statement {
    sid = "ProjectVectorData"
    actions = [
      "s3vectors:PutVectors",
      "s3vectors:GetVectors",
      "s3vectors:ListVectors",
      "s3vectors:QueryVectors",
    ]
    resources = [format(local.regional_arn, "s3vectors", "bucket/${var.name_prefix}-vectors-*")]
  }

  statement {
    sid = "ProjectQueueMessages"
    actions = [
      "sqs:SendMessage",
      "sqs:ReceiveMessage",
      "sqs:DeleteMessage",
      "sqs:ChangeMessageVisibility",
    ]
    resources = [format(local.regional_arn, "sqs", "${var.name_prefix}-*")]
  }

  # ---- ECS execution role ---------------------------------------------------
  # Resource "*": ecr:GetAuthorizationToken has no resource type.
  statement {
    sid       = "EcrAuthToken"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]

    condition {
      test     = "StringEquals"
      variable = "aws:RequestedRegion"
      values   = [var.region]
    }
  }

  statement {
    sid = "ProjectImagePull"
    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:BatchGetImage",
      "ecr:GetDownloadUrlForLayer",
    ]
    resources = [format(local.regional_arn, "ecr", "repository/${var.name_prefix}-*")]
  }

  statement {
    sid       = "AppKeyValues"
    actions   = ["secretsmanager:GetSecretValue"]
    resources = [format(local.regional_arn, "secretsmanager", "secret:${var.app_key_name_prefix}*")]
  }

  # The aws/secretsmanager key is used through Secrets Manager on the
  # caller's behalf. Resource "*": the AWS managed key ARN is not known
  # before plan; kms:ViaService limits it to Secrets Manager. No role policy
  # grants this today (the key policy does); the boundary must not block it.
  # (Requirement unverified; harmless as a ceiling.)
  statement {
    sid       = "AppKeyDecryptViaSecretsManager"
    actions   = ["kms:Decrypt"]
    resources = ["*"]

    condition {
      test     = "StringEquals"
      variable = "kms:ViaService"
      values   = ["secretsmanager.${var.region}.amazonaws.com"]
    }
  }

  statement {
    sid       = "WriteLogStreams"
    actions   = ["logs:CreateLogStream", "logs:PutLogEvents"]
    resources = [format(local.regional_arn, "logs", "log-group:*")]
  }

  # ---- Step Functions role ----------------------------------------------------
  # Resource "*": these log-delivery actions support no resource type (Step
  # Functions guide, "IAM Policies for logging to CloudWatch Logs").
  statement {
    sid = "StateMachineLogDelivery"
    actions = [
      "logs:CreateLogDelivery",
      "logs:GetLogDelivery",
      "logs:UpdateLogDelivery",
      "logs:DeleteLogDelivery",
      "logs:ListLogDeliveries",
      "logs:PutResourcePolicy",
      "logs:DescribeResourcePolicies",
      "logs:DescribeLogGroups",
    ]
    resources = ["*"]

    condition {
      test     = "StringEquals"
      variable = "aws:RequestedRegion"
      values   = [var.region]
    }
  }

  # Resource "*": RunJobFlow has no resource type.
  statement {
    sid       = "CreateClusters"
    actions   = ["elasticmapreduce:RunJobFlow"]
    resources = ["*"]

    condition {
      test     = "StringEquals"
      variable = "aws:RequestedRegion"
      values   = [var.region]
    }
  }

  statement {
    sid = "DriveClusters"
    actions = [
      "elasticmapreduce:DescribeCluster",
      "elasticmapreduce:TerminateJobFlows",
      "elasticmapreduce:AddJobFlowSteps",
      "elasticmapreduce:DescribeStep",
      "elasticmapreduce:CancelSteps",
      "elasticmapreduce:PutAutoTerminationPolicy",
      "elasticmapreduce:GetAutoTerminationPolicy",
      "elasticmapreduce:RemoveAutoTerminationPolicy",
      "elasticmapreduce:PutManagedScalingPolicy",
    ]
    resources = [format(local.regional_arn, "elasticmapreduce", "cluster/*")]
  }

  # Only the two EMR roles may be passed (Step Functions passes both; the
  # EMR service role passes the instance role to EC2). Never another role.
  statement {
    sid     = "PassEmrRoles"
    actions = ["iam:PassRole"]
    resources = [
      "${local.arn_prefix}:iam::${var.account_id}:role/${var.name_prefix}-emr-service-*",
      "${local.arn_prefix}:iam::${var.account_id}:role/${var.name_prefix}-emr-instance-*",
    ]
  }

  statement {
    sid       = "ServiceLinkedRoles"
    actions   = ["iam:CreateServiceLinkedRole"]
    resources = ["${local.arn_prefix}:iam::${var.account_id}:role/aws-service-role/*"]
  }

  # ---- EMR service role -----------------------------------------------------
  # The actions of AWS managed AmazonEMRServicePolicy_v2 (policy version v6,
  # read in step 4 from the AWS Managed Policy Reference). The managed policy
  # keeps its own tag conditions; this is only the ceiling. Resource "*":
  # the managed policy uses "*" or cross-account ARN patterns for these.
  statement {
    sid = "EmrServiceCeiling"
    actions = [
      "ec2:AuthorizeSecurityGroupEgress",
      "ec2:AuthorizeSecurityGroupIngress",
      "ec2:CreateFleet",
      "ec2:CreateLaunchTemplate",
      "ec2:CreateLaunchTemplateVersion",
      "ec2:CreateNetworkInterface",
      "ec2:CreatePlacementGroup",
      "ec2:CreateSecurityGroup",
      "ec2:CreateTags",
      "ec2:CreateVpcEndpoint",
      "ec2:DeleteLaunchTemplate",
      "ec2:DeleteNetworkInterface",
      "ec2:DeletePlacementGroup",
      "ec2:DeleteTags",
      "ec2:Describe*",
      "ec2:ModifyInstanceAttribute",
      "ec2:ModifyVpcEndpoint",
      "ec2:RevokeSecurityGroupEgress",
      "ec2:RevokeSecurityGroupIngress",
      "ec2:RunInstances",
      "ec2:TerminateInstances",
      "application-autoscaling:DeleteScalingPolicy",
      "application-autoscaling:DeregisterScalableTarget",
      "application-autoscaling:DescribeScalableTargets",
      "application-autoscaling:DescribeScalingPolicies",
      "application-autoscaling:PutScalingPolicy",
      "application-autoscaling:RegisterScalableTarget",
      "resource-groups:ListGroupResources",
      "cloudwatch:PutMetricAlarm",
      "cloudwatch:DeleteAlarms",
      "cloudwatch:DescribeAlarms",
    ]
    resources = ["*"]

    condition {
      test     = "StringEquals"
      variable = "aws:RequestedRegion"
      values   = [var.region]
    }
  }
}

resource "aws_iam_policy" "role_boundary" {
  name        = "${var.name_prefix}-role-boundary"
  description = "Permissions boundary of every ScholarChain project role (the deployment role may create or change only roles that carry it)"
  policy      = data.aws_iam_policy_document.role_boundary.json

  tags = { Name = "${var.name_prefix}-role-boundary" }
}
