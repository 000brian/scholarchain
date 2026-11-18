# Every child module of the course environment, declared once in step 1 so the
# one `terraform init` stays valid for the whole phase. Sources are local
# folders only (no registry or git sources, no nested module blocks).
# Inputs are added area by area in steps 2a and 2b; whoever adds a child
# variable passes it here in the same area.

locals {
  # Same values as the provider default tags (providers.tf).
  project     = "scholarchain"
  environment = "course"

  # Area 13, explicit cleanup mode: cleanup_mode = true turns every deletion
  # protection off, whatever the per-resource variables say, so the removal
  # order in README.md ("Cleanup mode") can run. It never removes anything
  # by itself and never turns force_destroy on (the data-lake bucket and the
  # ECR repository are emptied by scripts/cleanup.sh after the evidence
  # archive and a typed human confirmation).
  dynamodb_deletion_protection = var.cleanup_mode ? false : var.dynamodb_deletion_protection_enabled
  neptune_deletion_protection  = var.cleanup_mode ? false : var.neptune_deletion_protection
}

# Area 1: account, partition, region and naming facts.
module "foundation" {
  source = "../../modules/foundation"

  project     = local.project
  environment = local.environment
}

# Area 2: VPC, subnets, one NAT gateway, gateway endpoints, every security group.
module "network" {
  source = "../../modules/network"

  name_prefix  = module.foundation.name_prefix
  region       = module.foundation.region
  vpc_cidr     = var.vpc_cidr
  service_port = var.service_port
}

# Area 3: EMR service and instance roles, Neptune loader role, deployment role,
# Secrets Manager containers (no values).
module "identity" {
  source = "../../modules/identity"

  name_prefix                       = module.foundation.name_prefix
  account_id                        = module.foundation.account_id
  partition                         = module.foundation.partition
  region                            = module.foundation.region
  deployment_trusted_principal_arns = var.deployment_trusted_principal_arns
  deployment_attachable_policy_arns = var.deployment_attachable_policy_arns
  app_key_name_prefix               = var.app_key_name_prefix
  app_key_recovery_window_days      = var.app_key_recovery_window_days

  # Areas 5 and 7: S3 policies of the EMR instance role and the Neptune loader.
  data_bucket_arn = module.data_lake.bucket_arn
  data_prefixes   = module.data_lake.prefixes
}

# Area 4: one data-lake bucket with the seven prefixes.
module "data_lake" {
  source = "../../modules/data-lake"

  name_prefix                        = module.foundation.name_prefix
  logs_expiration_days               = var.logs_expiration_days
  noncurrent_version_expiration_days = var.noncurrent_version_expiration_days
}

# Area 5: what a transient EMR cluster needs (no cluster).
module "emr" {
  source = "../../modules/emr"

  name_prefix      = module.foundation.name_prefix
  data_bucket_name = module.data_lake.bucket_name
  artifacts_prefix = module.data_lake.prefixes.artifacts
  emr_log_uri      = module.data_lake.emr_log_uri
  subnet_ids       = module.network.data_subnet_ids
  security_group_ids = {
    primary        = module.network.security_group_ids.emr_primary
    core           = module.network.security_group_ids.emr_core
    service_access = module.network.security_group_ids.emr_service_access
  }
  service_role_arn      = module.identity.emr_service_role_arn
  instance_profile_name = module.identity.emr_instance_profile_name
  cluster_tags          = module.network.emr_managed_policy_tag

  hadoop_release_label      = var.emr_hadoop_release_label
  spark_release_label       = var.emr_spark_release_label
  primary_instance_type     = var.emr_primary_instance_type
  core_instance_type        = var.emr_core_instance_type
  primary_instance_vcpus    = var.emr_primary_instance_vcpus
  core_instance_vcpus       = var.emr_core_instance_vcpus
  core_instance_count       = var.emr_core_instance_count
  managed_scaling_min_units = var.emr_managed_scaling_min_units
  managed_scaling_max_units = var.emr_managed_scaling_max_units
  vcpu_budget               = var.emr_vcpu_budget
  idle_timeout_seconds      = var.emr_idle_timeout_seconds
  step_concurrency_level    = var.emr_step_concurrency_level
}

# Area 6: the six on-demand tables of TableSpecs.scala.
module "dynamodb" {
  source = "../../modules/dynamodb"

  name_prefix                    = module.foundation.name_prefix
  point_in_time_recovery_enabled = var.dynamodb_point_in_time_recovery_enabled
  deletion_protection_enabled    = local.dynamodb_deletion_protection
}

# Area 7: one Neptune cluster with one db.t4g.medium instance.
module "neptune" {
  source = "../../modules/neptune"

  name_prefix           = module.foundation.name_prefix
  subnet_ids            = module.network.data_subnet_ids
  security_group_id     = module.network.security_group_ids.neptune
  port                  = module.network.neptune_port
  loader_role_arn       = module.identity.neptune_loader_role_arn
  engine_version        = var.neptune_engine_version
  instance_class        = var.neptune_instance_class
  backup_retention_days = var.neptune_backup_retention_days
  deletion_protection   = local.neptune_deletion_protection
  skip_final_snapshot   = var.neptune_skip_final_snapshot
  apply_immediately     = var.neptune_apply_immediately
}

# Area 8: one S3 Vectors bucket and the five indexes of the storage index spec.
module "vectors" {
  source = "../../modules/vectors"

  name_prefix = module.foundation.name_prefix
  name_suffix = module.foundation.account_suffix
  # The storage module's index contract, read in place (repository root is
  # four levels above this root module).
  index_spec = jsondecode(file("${path.root}/../../../../modules/storage/src/main/resources/s3vectors/index-spec-v1.json"))
  # Step 2b hook: the runtime roles that use S3 Vectors (query, worker, ops),
  # plus any extra principal the human lists.
  data_principal_arns = concat(module.runtime.vector_principal_arns, var.vectors_data_principal_arns)
}

# Area 10: ECR, ECS cluster, four Fargate ARM64 services, the ops task
# definition, internal ALB, API Gateway HTTP API + VPC link, autoscaling,
# runtime roles. Off by default (runtime_enabled = false in course.tfvars).
module "runtime" {
  source = "../../modules/runtime"

  name_prefix = module.foundation.name_prefix
  account_id  = module.foundation.account_id
  partition   = module.foundation.partition
  region      = module.foundation.region

  runtime_enabled = var.runtime_enabled
  image_tag       = var.image_tag
  graph_run_id    = var.graph_run_id

  vpc_id             = module.network.vpc_id
  app_subnet_ids     = module.network.app_subnet_ids
  security_group_ids = module.network.security_group_ids
  service_port       = module.network.service_port
  alb_listener_port  = module.network.alb_listener_port

  service_max_count          = var.runtime_service_max_count
  worker_max_count           = var.runtime_worker_max_count
  api_throttling_burst_limit = var.api_throttling_burst_limit
  api_throttling_rate_limit  = var.api_throttling_rate_limit

  app_key_secret_arns_by_env_var = module.identity.app_key_secret_arns_by_env_var
  log_group_names                = module.observability.log_group_names
  log_group_arns                 = module.observability.log_group_arns

  data_bucket_name            = module.data_lake.bucket_name
  data_bucket_arn             = module.data_lake.bucket_arn
  data_prefixes               = module.data_lake.prefixes
  table_names                 = module.dynamodb.table_names
  table_arns                  = module.dynamodb.table_arns
  vector_bucket_name          = module.vectors.vector_bucket_name
  vector_index_arns           = module.vectors.index_arns
  neptune_endpoint            = module.neptune.cluster_endpoint
  neptune_cluster_resource_id = module.neptune.cluster_resource_id
  job_queue_url               = module.workflow.queue_urls["jobs"]
  job_queue_arn               = module.workflow.queue_arns["jobs"]
  embedding_queue_arn         = module.workflow.queue_arns["embedding"]

  # Repair I-01: every runtime role carries the project permissions boundary.
  permissions_boundary_arn = module.identity.role_boundary_arn
}

# Area 11: job and embedding SQS queues with DLQs; the bounded pipeline state
# machine and its role. The SQS values mirror config/course.json (service.*).
module "workflow" {
  source = "../../modules/workflow"

  name_prefix       = module.foundation.name_prefix
  job_lease_seconds = var.job_lease_seconds
  job_max_attempts  = var.job_max_attempts

  account_id = module.foundation.account_id
  partition  = module.foundation.partition
  region     = module.foundation.region

  cluster_settings            = module.emr.cluster_settings
  emr_service_role_arn        = module.identity.emr_service_role_arn
  emr_instance_role_arn       = module.identity.emr_instance_role_arn
  state_machine_log_group_arn = module.observability.log_group_arns["states"]
  pipeline_timeout_seconds    = var.pipeline_timeout_seconds

  # Phase 13 decision 8.3: the optional JsonlExport step reads and writes only
  # under the data lake's graph/ prefix.
  graph_uri_prefix = "s3://${module.data_lake.bucket_name}/${module.data_lake.prefixes.graph}/"

  job_producer_role_arns = [module.runtime.task_role_arns["query"]]
  job_consumer_role_arns = [module.runtime.task_role_arns["worker"]]
  embedding_role_arns    = [module.runtime.task_role_arns["ops"]]

  # Repair I-01: the Step Functions role carries the project permissions boundary.
  permissions_boundary_arn = module.identity.role_boundary_arn
}

# Area 12: log groups, dashboard, default-metric alarms, SNS topic.
module "observability" {
  source = "../../modules/observability"

  name_prefix        = module.foundation.name_prefix
  region             = module.foundation.region
  account_id         = module.foundation.account_id
  log_retention_days = var.log_retention_days
  runtime_enabled    = var.runtime_enabled

  alb_arn_suffix            = module.runtime.alb_arn_suffix
  target_group_arn_suffixes = module.runtime.target_group_arn_suffixes
  ecs_cluster_name          = module.runtime.ecs_cluster_name
  ecs_service_names         = module.runtime.ecs_service_names
  api_id                    = module.runtime.api_id
  queue_names               = module.workflow.queue_names
  state_machine_arn         = module.workflow.state_machine_arn
  neptune_cluster_id        = module.neptune.cluster_id
  dynamodb_table_names      = module.dynamodb.table_names
}
