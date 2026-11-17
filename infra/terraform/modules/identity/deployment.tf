# Deployment role (decision 6 area 3): documented; assumed by the human for
# the Phase 14 apply through scripts/deploy.sh. Its actions are broad by
# nature, because Terraform creates, reads, changes and removes every resource
# of the environment. Containment:
#   - regional services: only in var.region (aws:RequestedRegion);
#   - S3: only buckets whose names start with "<name_prefix>-";
#   - IAM: only roles and instance profiles whose names start with
#     "<name_prefix>-"; PassRole only to the services that receive project
#     roles; managed-policy attachment only for the listed AWS managed policies;
#   - it can never read secret values (explicit deny).
# Tag conditions (project=scholarchain) are not used: create actions would need
# aws:RequestTag and read/list actions do not support aws:ResourceTag
# uniformly, so a tag-conditioned policy could not run a plan (functional
# least privilege beats a policy that cannot work).
# Escalation containment (repair I-01):
#   (a) explicit Deny of every IAM write on the deployment role itself. Its ARN
#       is not referenced (that would tie this policy to the role's random
#       name suffix); the Deny uses the role's own name pattern
#       "<name_prefix>-deployment-*", known at plan time, which matches this
#       role and any look-alike (CreateRole on that pattern is denied too).
#   (b) the permissions boundary of boundary.tf: CreateRole, PutRolePolicy,
#       AttachRolePolicy, PutRolePermissionsBoundary and UpdateAssumeRolePolicy
#       are allowed only when the target role carries it (and denied
#       otherwise); removing a boundary and changing the boundary policy are
#       denied. A role this role writes can never exceed the boundary.
# Consequence: a change to this role, its inline policy or the boundary policy
# is applied with the human's own profile, never with this role. Residual
# risk (README "Deployment role"): within the boundary the role can still give
# a project role the boundary's data access and run it (for example an ECS
# task with the execution role, which reads the app key values).
# The first apply is made with the human's own profile (this role does not
# exist yet); Phase 14 may use it afterwards.

data "aws_iam_policy_document" "deployment_trust" {
  statement {
    sid     = "HumanOperatorAssume"
    actions = ["sts:AssumeRole"]

    principals {
      type = "AWS"
      identifiers = (
        length(var.deployment_trusted_principal_arns) > 0
        ? var.deployment_trusted_principal_arns
        : ["${local.arn_prefix}:iam::${var.account_id}:root"]
      )
    }
  }
}

resource "aws_iam_role" "deployment" {
  name_prefix          = "${var.name_prefix}-deployment-"
  description          = "Terraform deployment role of the ScholarChain course environment (assumed by the human operator)"
  assume_role_policy   = data.aws_iam_policy_document.deployment_trust.json
  max_session_duration = 3600

  tags = { Name = "${var.name_prefix}-deployment" }
}

data "aws_iam_policy_document" "deployment" {
  # Resource "*": Terraform must create resources whose ARNs do not exist yet
  # and call Describe*/List* actions that the Service Authorization Reference
  # allows only on "*". Limited to the environment's Region.
  statement {
    sid = "RegionalServicesInRegion"
    actions = [
      "apigateway:*",
      "application-autoscaling:*",
      "cloudwatch:*",
      "dynamodb:*",
      "ec2:*",
      "ecr:*",
      "ecs:*",
      "elasticloadbalancing:*",
      "logs:*",
      "rds:*",
      "s3vectors:*",
      "secretsmanager:*",
      "sns:*",
      "sqs:*",
      "states:*",
    ]
    resources = ["*"]

    condition {
      test     = "StringEquals"
      variable = "aws:RequestedRegion"
      values   = [var.region]
    }
  }

  # AWS managed keys (aws/rds for Neptune storage, aws/secretsmanager): the
  # creating principal needs DescribeKey and CreateGrant through the service.
  # Resource "*": the AWS managed key ARNs are not known before plan; the
  # kms:ViaService condition limits use to these two services.
  # (Requirement unverified for this exact pair; reviewer/human to confirm.)
  statement {
    sid       = "AwsManagedKeysViaServices"
    actions   = ["kms:CreateGrant", "kms:DescribeKey"]
    resources = ["*"]

    condition {
      test     = "StringEquals"
      variable = "kms:ViaService"
      values = [
        "rds.${var.region}.amazonaws.com",
        "secretsmanager.${var.region}.amazonaws.com",
      ]
    }
  }

  # Data-lake bucket(s) of this environment only. Bucket configuration and the
  # EMR bootstrap object; no object removal outside artifacts/emr/.
  statement {
    sid = "ProjectBuckets"
    actions = [
      "s3:CreateBucket",
      "s3:DeleteBucket",
      "s3:DeleteBucketPolicy",
      "s3:Get*",
      "s3:List*",
      "s3:PutBucket*",
      "s3:PutEncryptionConfiguration",
      "s3:PutLifecycleConfiguration",
    ]
    resources = ["${local.arn_prefix}:s3:::${var.name_prefix}-*"]
  }

  statement {
    sid       = "ProjectBootstrapObjects"
    actions   = ["s3:PutObject", "s3:GetObject", "s3:GetObjectTagging", "s3:PutObjectTagging", "s3:DeleteObject"]
    resources = ["${local.arn_prefix}:s3:::${var.name_prefix}-*/artifacts/emr/*"]
  }

  # Role and instance-profile actions that cannot add permissions. The
  # permission-granting role writes are in BoundedProjectRoleWrites below.
  statement {
    sid = "ProjectRolesAndProfiles"
    actions = [
      "iam:AddRoleToInstanceProfile",
      "iam:CreateInstanceProfile",
      "iam:DeleteInstanceProfile",
      "iam:DeleteRole",
      "iam:DeleteRolePolicy",
      "iam:GetInstanceProfile",
      "iam:GetRole",
      "iam:GetRolePolicy",
      "iam:ListAttachedRolePolicies",
      "iam:ListInstanceProfilesForRole",
      "iam:ListRolePolicies",
      "iam:RemoveRoleFromInstanceProfile",
      "iam:TagInstanceProfile",
      "iam:TagRole",
      "iam:UntagInstanceProfile",
      "iam:UntagRole",
      "iam:UpdateRole",
      "iam:UpdateRoleDescription",
    ]
    resources = [
      "${local.arn_prefix}:iam::${var.account_id}:role/${var.name_prefix}-*",
      "${local.arn_prefix}:iam::${var.account_id}:instance-profile/${var.name_prefix}-*",
    ]
  }

  # Repair I-01 (b): permission-granting role writes only on roles that carry
  # the project boundary (iam:PermissionsBoundary is listed for these four
  # actions and AttachRolePolicy in the Service Authorization Reference). For CreateRole and
  # PutRolePermissionsBoundary the key is the boundary in the request; for
  # the others it is the boundary the role already carries.
  statement {
    sid = "BoundedProjectRoleWrites"
    actions = [
      "iam:CreateRole",
      "iam:PutRolePolicy",
      "iam:PutRolePermissionsBoundary",
      "iam:UpdateAssumeRolePolicy",
    ]
    resources = ["${local.arn_prefix}:iam::${var.account_id}:role/${var.name_prefix}-*"]

    condition {
      test     = "StringEquals"
      variable = "iam:PermissionsBoundary"
      values   = [aws_iam_policy.role_boundary.arn]
    }
  }

  statement {
    sid       = "AttachListedManagedPoliciesToBoundedRoles"
    actions   = ["iam:AttachRolePolicy"]
    resources = ["${local.arn_prefix}:iam::${var.account_id}:role/${var.name_prefix}-*"]

    condition {
      test     = "ArnEquals"
      variable = "iam:PolicyARN"
      values   = local.deployment_attachable_policy_arns
    }

    condition {
      test     = "StringEquals"
      variable = "iam:PermissionsBoundary"
      values   = [aws_iam_policy.role_boundary.arn]
    }
  }

  statement {
    sid       = "DetachListedManagedPolicies"
    actions   = ["iam:DetachRolePolicy"]
    resources = ["${local.arn_prefix}:iam::${var.account_id}:role/${var.name_prefix}-*"]

    condition {
      test     = "ArnEquals"
      variable = "iam:PolicyARN"
      values   = local.deployment_attachable_policy_arns
    }
  }

  statement {
    sid       = "PassProjectRoles"
    actions   = ["iam:PassRole"]
    resources = ["${local.arn_prefix}:iam::${var.account_id}:role/${var.name_prefix}-*"]

    condition {
      test     = "StringEquals"
      variable = "iam:PassedToService"
      values = [
        "ecs-tasks.amazonaws.com",
        "rds.amazonaws.com",
        "states.amazonaws.com",
        "apigateway.amazonaws.com",
      ]
    }
  }

  # Service-linked roles the first apply may need (ECS, ELB, Application Auto
  # Scaling for ECS, Neptune/RDS). Resource scoped to service-linked role paths.
  statement {
    sid       = "ServiceLinkedRoles"
    actions   = ["iam:CreateServiceLinkedRole"]
    resources = ["${local.arn_prefix}:iam::${var.account_id}:role/aws-service-role/*"]

    condition {
      test     = "StringEquals"
      variable = "iam:AWSServiceName"
      values = [
        "ecs.amazonaws.com",
        "elasticloadbalancing.amazonaws.com",
        "ecs.application-autoscaling.amazonaws.com",
        "rds.amazonaws.com",
        "ops.apigateway.amazonaws.com",
      ]
    }
  }

  # Read AWS managed policies that are attached (Terraform reads them on plan).
  statement {
    sid       = "ReadAwsManagedPolicies"
    actions   = ["iam:GetPolicy", "iam:GetPolicyVersion"]
    resources = ["${local.arn_prefix}:iam::aws:policy/*"]
  }

  # Read the boundary policy (Terraform refreshes it on plan). Read only:
  # this role cannot create, change or remove it.
  statement {
    sid       = "ReadRoleBoundary"
    actions   = ["iam:GetPolicy", "iam:GetPolicyVersion", "iam:ListPolicyVersions"]
    resources = [aws_iam_policy.role_boundary.arn]
  }

  # Resource "*": sts:GetCallerIdentity supports no resource-level permission
  # (the provider calls it on every plan for data.aws_caller_identity).
  statement {
    sid       = "WhoAmI"
    actions   = ["sts:GetCallerIdentity"]
    resources = ["*"]
  }

  # Deployment never reads application key values (runtime/deployment separation).
  statement {
    sid       = "NeverReadAppKeyValues"
    effect    = "Deny"
    actions   = ["secretsmanager:GetSecretValue", "secretsmanager:BatchGetSecretValue"]
    resources = ["*"]
  }

  # Repair I-01 (a): never change, pass or remove itself (or a look-alike
  # role on its name pattern). Without this, PutRolePolicy on its own name
  # with "*" on "*" would make it an account administrator.
  statement {
    sid    = "NeverChangeOwnRole"
    effect = "Deny"
    actions = [
      "iam:AttachRolePolicy",
      "iam:CreateRole",
      "iam:DeleteRole",
      "iam:DeleteRolePermissionsBoundary",
      "iam:DeleteRolePolicy",
      "iam:DetachRolePolicy",
      "iam:PassRole",
      "iam:PutRolePermissionsBoundary",
      "iam:PutRolePolicy",
      "iam:UpdateAssumeRolePolicy",
    ]
    resources = [local.deployment_role_pattern]
  }

  # Repair I-01 (b): the permission-granting writes are denied on any role
  # that does not carry the project boundary (a negated operator matches
  # when the key is absent, so a role without a boundary is covered).
  # Resource "*": the Deny must hold whatever role ARN is named.
  statement {
    sid    = "BoundaryRequiredOnRoleWrites"
    effect = "Deny"
    actions = [
      "iam:AttachRolePolicy",
      "iam:CreateRole",
      "iam:PutRolePermissionsBoundary",
      "iam:PutRolePolicy",
      "iam:UpdateAssumeRolePolicy",
    ]
    resources = ["*"]

    condition {
      test     = "StringNotEquals"
      variable = "iam:PermissionsBoundary"
      values   = [aws_iam_policy.role_boundary.arn]
    }
  }

  # Resource "*": no role may lose its boundary through this role.
  statement {
    sid       = "NeverRemoveBoundaries"
    effect    = "Deny"
    actions   = ["iam:DeleteRolePermissionsBoundary"]
    resources = ["*"]
  }

  # The boundary policy is changed or removed only with the human's profile.
  statement {
    sid    = "NeverChangeRoleBoundary"
    effect = "Deny"
    actions = [
      "iam:CreatePolicyVersion",
      "iam:DeletePolicy",
      "iam:DeletePolicyVersion",
      "iam:SetDefaultPolicyVersion",
      "iam:TagPolicy",
      "iam:UntagPolicy",
    ]
    resources = [aws_iam_policy.role_boundary.arn]
  }
}

locals {
  # The deployment role's own name pattern (its name_prefix plus the random
  # suffix IAM adds): known at plan time, independent of the role resource.
  deployment_role_pattern = "${local.arn_prefix}:iam::${var.account_id}:role/${var.name_prefix}-deployment-*"

  deployment_attachable_policy_arns = concat(
    ["${local.arn_prefix}:iam::aws:policy/service-role/AmazonEMRServicePolicy_v2"],
    var.deployment_attachable_policy_arns
  )
}

resource "aws_iam_role_policy" "deployment" {
  name   = "terraform-deployment"
  role   = aws_iam_role.deployment.id
  policy = data.aws_iam_policy_document.deployment.json
}
