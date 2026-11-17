# Phase 12 step 1 report (infra-engineer)

STATUS: COMPLETE
OWNER: infra-engineer
WORK ORDER: docs/agent-runs/phase-12/prompt.md (sha256 5744ab73aa42946f1c556d1257a45031fd57868b75480de623879a9c73de7f56, verified with shasum at start), session 2, step 1 = decision 5 in full.
FILES CREATED (52, all under /Users/brian/Developer/repos/scholarchain/infra/terraform/):
- environments/course/versions.tf, providers.tf, variables.tf, outputs.tf, main.tf, course.tfvars, local.auto.tfvars.example
- modules/{foundation,network,identity,data-lake,emr,dynamodb,neptune,vectors,runtime,workflow,observability}/{versions.tf,variables.tf,outputs.tf,main.tf} (44 files)
- STATE.md
FILES MODIFIED: none (`.gitignore` is build-engineer's step 0; not touched)
COMMANDS RUN:
- `shasum -a 256 docs/agent-runs/phase-12/prompt.md` -> matches the work-order hash
- `terraform version` -> Terraform v1.17.1 on darwin_arm64 (1.17.2 available upstream)
- `ls` of `~/.terraform.d` (no plugin cache present) and of the scratchpad
- `terraform fmt -recursive infra/terraform` -> exit 0, no file listed (nothing needed reformatting)
- `terraform fmt -recursive -check infra/terraform` -> exit 0, no file listed
- `git status --short`, `git ls-files --others --exclude-standard infra | wc -l` (52), `git status --short --ignored infra/terraform/environments/course` (`?? infra/terraform/environments/course/`: untracked, not ignored)
- grep on two saved terraform-MCP tool-result files (module metadata) to read provider constraints
- MCP: terraform `search_modules` (x7), `get_module_details` (x4), `get_provider_details` (x1, probe); aws-docs `search_documentation` (x1), `read_documentation` (x1). aws-api: not used (failed to connect this session).
TEST RESULTS: none (no sbt in this phase; no test applies to a Terraform skeleton)
VALIDATION RESULTS: fmt check exit 0. validate not run: needs init (planned step 1h). shellcheck and jq: not applicable (no scripts or JSON in this step).
ASSUMPTIONS:
- A1. `hashicorp/aws` pinned `= 6.59.0`. Evidence (terraform MCP, module metadata): `terraform-aws-modules/eks/aws` 21.30.0, published 2026-11-17, requires `hashicorp/aws >= 6.59`; `terraform-aws-modules/s3-bucket/aws` 5.17.1 (published 2026-11-16, ships an S3 Vectors `modules/vectors` submodule and `examples/vectors`) requires `>= 6.44`. So 6.59 is a released version that is newer than the version the S3 Vectors module needs. I could not read the provider's own version list (see RISKS R1).
- A2. `required_version = "= 1.17.1"`, the exact local binary (decision 5 asks for exact pins).
- A3. `expires = "2026-12-31"` in course.tfvars is my placeholder date; the human confirms or changes it before signing the plan.
- A4. `aws_region` has no default and a validation that allows only `us-east-1`; the value is in course.tfvars. `owner` and `expires` have no defaults (decision 5) and validations (tag-value characters; YYYY-MM-DD).
- A5. Tag key `expires` (decision 5) is used instead of the role file's word "expiration"; the decision wins.
- A6. Child modules have only comments in variables.tf/outputs.tf/main.tf, so the root's module blocks carry only `source`; steps 2a/2b add inputs and pass them from the root.
RISKS:
- R1. Resource-type check is `unverified` against provider docs. The terraform MCP in this session exposes only `get_provider_details` (needs a doc ID from `search_provider_docs`), `search_modules` and `get_module_details`; `search_provider_docs`, `get_latest_provider_version` and `get_provider_capabilities` are not available to this agent. The resource names below are from provider knowledge plus the indirect module evidence in A1. The definitive check is step 2a's first `validate` after init: an unknown resource type fails validate with "Invalid resource type". If that happens for an S3 Vectors type, the fix is a pin change (`init -upgrade`, the unplanned stop of decision 5).
- R2. If 6.59.0 is not a published release (the module constraint says `>= 6.59`, not `= 6.59.0`), `init` fails with "no available releases match"; then the pin is changed to the version `init` lists and init is re-run. Low likelihood.
- R3. `required_version = "= 1.17.1"`: if the human upgrades Terraform to 1.17.2 (the CLI reports it as available), every command fails until the pin is changed. Do not upgrade during Phase 12-17, or ask for the pin change.
- R4 (for step 2b, area 12): an SNS topic encrypted with the AWS-managed `aws/sns` key cannot receive CloudWatch alarm notifications (the managed key policy cannot grant `cloudwatch.amazonaws.com`). With decision 6's "no customer-managed key", the alarm topic should use no SSE or SSE via a CMK; record as a decision for 2b. `unverified` in this step (not looked up in aws-docs here).
- R5. aws-api MCP unavailable: AZ names, EMR release-label existence (`emr-spark-8.1.0`, EMR 7.x) and quotas are `unverified`; they belong to step 2a.
MANUAL ACTION REQUIRED:
- Run from the repository root: `terraform -chdir=infra/terraform/environments/course init -backend=false`
- Check that `infra/terraform/environments/course/.terraform.lock.hcl` was written (it is tracked) and that init reports `hashicorp/aws v6.59.0`.
- Later (before plan, not now): copy `local.auto.tfvars.example` to `local.auto.tfvars` and set `owner`; confirm `expires` in course.tfvars.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: step 1h (human `init`), then step 2a's first command `terraform -chdir=infra/terraform/environments/course validate` (probe of the step-F allow rule).

## Plan summary and checksum

No plan in this step (plan is the human's, decision 9). The skeleton declares no resources and no data sources: a plan of it would show 0 to add, 0 to change, 0 to remove. Checksum: none.

## IAM changes

None in this step.

## Stateful-resource actions

None in this step.

## Cost estimate

$0: the skeleton creates nothing.

## Required environment variables by name

None for Terraform in this step. Terraform input variables: `aws_region`, `expires` (course.tfvars), `owner` (local.auto.tfvars).

## Provider and versions

- Terraform: `required_version = "= 1.17.1"` (local CLI v1.17.1).
- Provider: `hashicorp/aws` `= 6.59.0`, pinned only in `environments/course/versions.tf`. Child modules require `source = "hashicorp/aws"` with no version and have no `provider` block.
- No other provider anywhere. Built-ins to use instead of extra providers: `terraform_data` (built-in `terraform` provider, needs no install), `jsonencode`, `templatefile`, `data.aws_iam_policy_document`, `bucket_prefix` / `name_prefix`.
- Backend: none declared (default local), see STATE.md.

## Default tags (providers.tf)

`project=scholarchain`, `course=cs441`, `environment=course`, `owner=var.owner` (no default; local.auto.tfvars), `expires=var.expires` (no default; course.tfvars), `managed-by=terraform`.

## Resource-type check (decision 5), by area

Status of each name: `unverified` against provider docs (RISK R1); the S3 Vectors family is supported indirectly by A1. All are expected in `hashicorp/aws` 6.59.0; none is known missing, so no replacement is proposed.

1. Foundation: data `aws_caller_identity`, `aws_region`, `aws_partition`.
2. Network: data `aws_availability_zones`; `aws_vpc`, `aws_subnet`, `aws_internet_gateway`, `aws_eip`, `aws_nat_gateway`, `aws_route_table`, `aws_route`, `aws_route_table_association`, `aws_vpc_endpoint` (Gateway type, S3 and DynamoDB), `aws_security_group`, `aws_vpc_security_group_ingress_rule`, `aws_vpc_security_group_egress_rule`, `aws_default_security_group` (to strip rules from the default group).
3. Identity and encryption: `aws_iam_role`, `aws_iam_role_policy`, `aws_iam_role_policy_attachment` (AWS-managed policies only where justified), `aws_iam_instance_profile`, data `aws_iam_policy_document`; `aws_secretsmanager_secret` (containers only; never `aws_secretsmanager_secret_version`).
4. Data lake: `aws_s3_bucket` (`bucket_prefix`), `aws_s3_bucket_public_access_block`, `aws_s3_bucket_ownership_controls`, `aws_s3_bucket_server_side_encryption_configuration`, `aws_s3_bucket_versioning`, `aws_s3_bucket_lifecycle_configuration`, `aws_s3_bucket_policy` (TLS-only), `aws_s3_object` (EMR bootstrap script, if one is needed).
5. EMR: no `aws_emr_cluster`. `aws_security_group` + rule types (primary, core, service-access), `aws_iam_role`, `aws_iam_instance_profile`, optionally `aws_emr_security_configuration` (free; local-disk and in-transit encryption; proposed for 2a to decide).
6. DynamoDB: `aws_dynamodb_table` (PAY_PER_REQUEST, `ttl`, `global_secondary_index`, `point_in_time_recovery`).
7. Neptune: `aws_neptune_cluster` (loader role via its `iam_roles` argument, `iam_database_authentication_enabled`, `storage_encrypted`, `deletion_protection`, `skip_final_snapshot`), `aws_neptune_cluster_instance`, `aws_neptune_subnet_group`, `aws_neptune_cluster_parameter_group`, `aws_neptune_parameter_group`.
8. S3 Vectors: `aws_s3vectors_vector_bucket`, `aws_s3vectors_index`, `aws_s3vectors_vector_bucket_policy` (the provider has a bucket-policy resource; use it to limit access to the runtime and ops roles).
9. OpenSearch: none (not built).
10. Runtime compute: `aws_ecr_repository`, `aws_ecr_lifecycle_policy`, `aws_ecs_cluster`, `aws_ecs_task_definition`, `aws_ecs_service`, `aws_lb` (internal), `aws_lb_target_group`, `aws_lb_listener` (fixed-response 404 default), `aws_lb_listener_rule`, `aws_apigatewayv2_api` (HTTP), `aws_apigatewayv2_vpc_link`, `aws_apigatewayv2_integration` (HTTP_PROXY over VPC_LINK to the ALB listener), `aws_apigatewayv2_route`, `aws_apigatewayv2_stage` (`$default`, throttling, access logs), `aws_appautoscaling_target`, `aws_appautoscaling_policy` (TargetTrackingScaling and StepScaling), `aws_cloudwatch_metric_alarm` (worker SQS alarms), `aws_iam_role`, `aws_iam_role_policy`, `terraform_data` or `lifecycle.precondition` (runtime_enabled check).
11. Workflow and messaging: `aws_sqs_queue` (x4: two queues, two DLQs; `redrive_policy` argument), `aws_sqs_queue_redrive_allow_policy`, `aws_sqs_queue_policy`, `aws_sfn_state_machine` (STANDARD, `logging_configuration`), `aws_iam_role`, `aws_iam_role_policy`. EventBridge: none.
12. Observability: `aws_cloudwatch_log_group`, `aws_cloudwatch_dashboard`, `aws_cloudwatch_metric_alarm`, `aws_sns_topic` (no subscription in source; see RISK R4).
13. Cost and safety: no new types; variables, `validation`, `lifecycle.precondition`.

Not used on purpose: `null_resource`, `random_*`, `aws_emr_cluster`, `aws_secretsmanager_secret_version`, `aws_budgets_budget` (budget `scholarchain-25` is hand-made, decision 2), any IAM user, `aws_api_gateway_account` (not needed for HTTP API access logs; unverified).

## Variable files

- `environments/course/course.tfvars` (committed): `aws_region = "us-east-1"`, `expires = "2026-12-31"` (A3). Loaded only through `-var-file=course.tfvars`.
- `environments/course/local.auto.tfvars.example` (committed): `owner` placeholder. Real `local.auto.tfvars` is gitignored by `*.tfvars` and auto-loaded.
- Observed: `git status --short --ignored infra/terraform/environments/course` shows the folder as untracked (`??`), not ignored, so course.tfvars is trackable with step 0's `.gitignore` as it is now.

## STATE.md

`infra/terraform/STATE.md`: local backend for the course environment (why: no bootstrap apply, one operator, short life, cost), risk table (lost laptop -> cleanup by `project`/`environment` tags), and the remote design (separate bootstrap root, S3 backend with versioning, SSE, TLS-only policy, Block Public Access, native locking `use_lockfile = true`, no DynamoDB lock table, bucket name via `-backend-config`, who may read state, migration steps).

## Proposed later

- `aws_emr_security_configuration` (free; at-rest local-disk and in-transit encryption for EMR) — for step 2a to decide under area 3/5.

## INTERFACE HANDOFF

- Files changed: the 52 created files listed above; nothing else.
- Root module blocks (names fixed for 2a/2b): `foundation`, `network`, `identity`, `data_lake` (folder `data-lake`), `emr`, `dynamodb`, `neptune`, `vectors`, `runtime`, `workflow`, `observability`; all `source = "../../modules/<folder>"`.
- Root variables: `aws_region` (string, us-east-1 only), `owner` (string, no default), `expires` (string YYYY-MM-DD, no default).
- Child modules: no variables, no outputs, no resources yet; `versions.tf` requires `hashicorp/aws` with no version.
- Contract for later steps: no other provider, no nested module blocks, no registry/git sources; every new child variable is passed from root `main.tf` in the same area, then validate.
- Thread-safety/limits: not applicable. Validate requires the human `init` first.

VALIDATION EVIDENCE
- fmt: terraform fmt -recursive -check infra/terraform -> 0, no files listed
- validate: terraform -chdir=infra/terraform/environments/course validate -> not run: needs init (planned step 1h)
- shellcheck: not run: no scripts in this step -> n/a
- jq: not run: no JSON files in this step -> n/a
- finished: 2026-11-17 02:03:15 CST
- files changed after these runs: no
