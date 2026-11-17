# neptune (decision 6 area 7): one provisioned cluster with ONE db.t4g.medium
# instance (the free-trial class; cost reason in COSTS.md, step 2b), in the
# private data subnets, with:
#   - storage encryption with the AWS managed key aws/rds (no CMK);
#   - IAM database authentication (runtime read access is granted in the
#     runtime task roles, area 10: neptune-db:connect and
#     neptune-db:ReadDataViaQuery on this cluster's resource ID);
#   - the bulk-loader role attached (iam_roles);
#   - backup retention 1 day; deletion protection and final-snapshot behaviour
#     from variables (course: off / skip, ephemeral with an explicit cleanup
#     mode; the graph is reproducible from S3);
#   - no public endpoint; the security group admits only the runtime roles and
#     ops on the Neptune port. No audit-log export (CloudWatch Logs cost).

locals {
  parameter_group_family = "neptune1.4"
}

resource "aws_neptune_subnet_group" "this" {
  name_prefix = "${var.name_prefix}-neptune-"
  description = "ScholarChain Neptune: private data subnets"
  subnet_ids  = var.subnet_ids

  tags = { Name = "${var.name_prefix}-neptune" }
}

resource "aws_neptune_cluster_parameter_group" "this" {
  name_prefix = "${var.name_prefix}-neptune-cluster-"
  family      = local.parameter_group_family
  description = "ScholarChain Neptune cluster parameters"

  parameter {
    name  = "neptune_enable_audit_log"
    value = "0"
  }

  tags = { Name = "${var.name_prefix}-neptune-cluster" }

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_neptune_parameter_group" "this" {
  name_prefix = "${var.name_prefix}-neptune-instance-"
  family      = local.parameter_group_family
  description = "ScholarChain Neptune instance parameters"

  tags = { Name = "${var.name_prefix}-neptune-instance" }

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_neptune_cluster" "this" {
  cluster_identifier_prefix            = "${var.name_prefix}-graph-"
  engine                               = "neptune"
  engine_version                       = var.engine_version
  port                                 = var.port
  neptune_subnet_group_name            = aws_neptune_subnet_group.this.name
  neptune_cluster_parameter_group_name = aws_neptune_cluster_parameter_group.this.name
  vpc_security_group_ids               = [var.security_group_id]

  storage_encrypted                   = true
  iam_database_authentication_enabled = true
  iam_roles                           = [var.loader_role_arn]

  backup_retention_period   = var.backup_retention_days
  preferred_backup_window   = "07:00-08:00"
  copy_tags_to_snapshot     = true
  deletion_protection       = var.deletion_protection
  skip_final_snapshot       = var.skip_final_snapshot
  final_snapshot_identifier = var.skip_final_snapshot ? null : "${var.name_prefix}-neptune-final"
  apply_immediately         = var.apply_immediately

  tags = { Name = "${var.name_prefix}-graph" }
}

resource "aws_neptune_cluster_instance" "this" {
  identifier_prefix            = "${var.name_prefix}-graph-1-"
  cluster_identifier           = aws_neptune_cluster.this.id
  engine                       = "neptune"
  engine_version               = var.engine_version
  instance_class               = var.instance_class
  neptune_parameter_group_name = aws_neptune_parameter_group.this.name
  auto_minor_version_upgrade   = false
  apply_immediately            = var.apply_immediately

  tags = { Name = "${var.name_prefix}-graph-1" }
}
