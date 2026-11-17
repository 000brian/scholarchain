# emr (decision 6 area 5): NO running cluster in Terraform. This module holds
# only what a transient cluster needs and hands it to the pipeline state
# machine (area 11) as one object (output cluster_settings):
#   - roles: EMR service role and instance profile (identity module; the
#     instance role's S3 policy is identity/emr.tf);
#   - security groups incl. service access (network module);
#   - log location (logs/emr/ in the data lake) and the bootstrap script below;
#   - release labels: Hadoop on EMR 7.x, Spark on emr-spark-8.1.0;
#   - bounded On-Demand capacity with managed-scaling min/max and an idle
#     auto-termination backstop. No Spot (the jobs' behaviour under Spot loss
#     is untested). A cluster costs money only while a pipeline runs.

locals {
  bootstrap_key = "${var.artifacts_prefix}/emr/bootstrap/scholarchain-bootstrap-v1.sh"
}

resource "aws_s3_object" "bootstrap" {
  bucket       = var.data_bucket_name
  key          = local.bootstrap_key
  source       = "${path.module}/files/bootstrap.sh"
  etag         = filemd5("${path.module}/files/bootstrap.sh")
  content_type = "text/x-shellscript"

  tags = { Name = "${var.name_prefix}-emr-bootstrap" }

  lifecycle {
    precondition {
      condition     = var.managed_scaling_min_units >= 1 && var.managed_scaling_min_units <= var.managed_scaling_max_units
      error_message = "EMR managed scaling needs 1 <= min <= max."
    }

    precondition {
      condition     = var.core_instance_count >= var.managed_scaling_min_units && var.core_instance_count <= var.managed_scaling_max_units
      error_message = "core_instance_count must lie between the managed-scaling min and max."
    }

    precondition {
      condition     = (var.managed_scaling_max_units + 1) * var.instance_vcpus <= var.vcpu_budget
      error_message = "At full scale (max core nodes + primary) one cluster would exceed vcpu_budget."
    }
  }
}

locals {
  # Everything the state machine passes to createCluster (area 11 builds the
  # request from this; values here are not hard-coded there).
  cluster_settings = {
    hadoop_release_label = var.hadoop_release_label
    spark_release_label  = var.spark_release_label
    log_uri              = var.emr_log_uri
    bootstrap_script_uri = "s3://${var.data_bucket_name}/${local.bootstrap_key}"
    subnet_id            = var.subnet_ids[0]
    service_role_arn     = var.service_role_arn
    instance_profile     = var.instance_profile_name
    security_groups = {
      emr_managed_primary = var.security_group_ids.primary
      emr_managed_core    = var.security_group_ids.core
      service_access      = var.security_group_ids.service_access
    }
    primary_instance_type  = var.primary_instance_type
    core_instance_type     = var.core_instance_type
    core_instance_count    = var.core_instance_count
    market                 = "ON_DEMAND"
    step_concurrency_level = var.step_concurrency_level
    keep_alive_no_steps    = true
    managed_scaling = {
      unit_type                        = "Instances"
      minimum_capacity_units           = var.managed_scaling_min_units
      maximum_capacity_units           = var.managed_scaling_max_units
      maximum_on_demand_capacity_units = var.managed_scaling_max_units
      maximum_core_capacity_units      = var.managed_scaling_max_units
    }
    idle_timeout_seconds = var.idle_timeout_seconds
    tags                 = var.cluster_tags
  }
}
