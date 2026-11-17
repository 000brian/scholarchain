# Phase 12 step 2a report (infra-engineer)

STATUS: COMPLETE
OWNER: infra-engineer
WORK ORDER: docs/agent-runs/phase-12/prompt.md (sha256 5744ab73aa42946f1c556d1257a45031fd57868b75480de623879a9c73de7f56, re-checked with shasum at start), session 2, step 2a = decision 6 areas 1-8 plus infra/terraform/README.md.
FILES CREATED (all under /Users/brian/Developer/repos/scholarchain/infra/terraform/):
- README.md
- modules/identity/app-keys.tf, modules/identity/deployment.tf, modules/identity/emr.tf, modules/identity/neptune-loader.tf
- modules/emr/files/bootstrap.sh
FILES MODIFIED (all under /Users/brian/Developer/repos/scholarchain/infra/terraform/):
- environments/course/main.tf, variables.tf, course.tfvars (outputs.tf, providers.tf, versions.tf, local.auto.tfvars.example unchanged; root outputs are step 2b)
- modules/foundation/{main,variables,outputs}.tf
- modules/network/{main,variables,outputs}.tf
- modules/identity/{main,variables,outputs}.tf
- modules/data-lake/{main,variables,outputs}.tf
- modules/emr/{main,variables,outputs}.tf
- modules/dynamodb/{main,variables,outputs}.tf
- modules/neptune/{main,variables,outputs}.tf
- modules/vectors/{main,variables,outputs}.tf
- Not touched: STATE.md, every versions.tf, runtime/workflow/observability modules, anything outside infra/**.
COMMANDS RUN:
- `terraform -chdir=infra/terraform/environments/course validate` (probe, first) -> Success, no approval prompt
- `shasum -a 256 docs/agent-runs/phase-12/prompt.md` -> matches
- per area (x8, plus one root fix in area 3): `terraform fmt -recursive infra/terraform` (exit 0; it reformatted root main.tf once and emr/main.tf once), `terraform fmt -recursive -check infra/terraform` (exit 0), `terraform -chdir=infra/terraform/environments/course validate` (Success every time)
- `shellcheck infra/terraform/modules/emr/files/bootstrap.sh` -> exit 0 (twice)
- read-only: `cat`/`sed -n`/`grep` of repository inputs (TableSpecs.scala, index-spec-v1.json, RunFs.scala, JobRunner.scala, GraphAnalyticsJob.scala, ConfigLoader.scala, ScholarChainConfig.scala, dataset-manifest.yaml), `git status --short --ignored infra`, `git ls-files --others --exclude-standard infra`, grep scans for 12-digit numbers and dotted IPs in infra/terraform, `date`
- MCP aws-docs: search_documentation x7, read_documentation x9 (EMR managed SGs, EMR service role and v2 policy, EMR 7.x release list, emr-spark-8.1.0, S3 Vectors naming, AWS::S3Vectors::VectorBucket, S3 Vectors index mode, Neptune 1.4.0.0 release, IAM service-authorization page partial). terraform MCP: not used (no doc-search tool exposed; validate was the arbiter). aws-api MCP: not used (failed to connect this session).
TEST RESULTS: none (no sbt in this phase).
VALIDATION RESULTS: fmt check exit 0; validate "Success! The configuration is valid."; shellcheck clean on the bootstrap script; jq not applicable (no JSON written). See VALIDATION EVIDENCE at the end.
ASSUMPTIONS:
- A1. Service port 8080 (`service_port` variable): `service.port` is a configuration key with no repository value for AWS; step 2b must write the same value in config/course.json.
- A2. Prefix names follow decision 6 area 4 literally (`raw`, `curated`, `graph`, `rag`, `artifacts`, `logs`, `reports`); 2b maps `objectStore.semanticContextPrefix` to `rag` (or a sub-prefix of it) and keeps IAM and config equal.
- A3. EMR runs in the private data subnets (first one per cluster); data subnets route to the NAT for EMR control-plane and package traffic.
- A4. Hadoop and Spark (and entity resolution, if run as an EMR step) write only under `curated/` and `graph/`; Spark's `--config` is a local file on the driver.
- A5. Neptune 30-day free trial covers db.t4g.medium (decision 2; to be confirmed in COSTS.md, step 2b).
- A6. Role and instance-profile names use `name_prefix` "scholarchain-course-..." (<= 35 characters, under the IAM 38-character prefix limit), which is what the deployment role's IAM statements match.
RISKS:
- R1. Single NAT gateway (decided): an outage of its AZ removes internet egress for both AZs' private subnets (S3/DynamoDB keep working via gateway endpoints).
- R2. EMR 8.x / EMR Spark 8.x: EMR itself creates an "emr-service-vpce" interface endpoint (allowed by AmazonEMRServicePolicy_v2 in the tagged VPC). An interface endpoint bills per hour per AZ; whether EMR removes it when the cluster ends is `unverified`. Step 2b's COSTS.md and cleanup.sh should list it.
- R3. The deployment role can write inline policies on `scholarchain-course-*` roles, so it can grant those roles more than it has (privilege escalation within the project's roles). Control: the human's plan review; a permissions boundary is on "proposed later". No tag conditions (reason in deployment.tf).
- R4. KMS statement of the deployment role (CreateGrant/DescribeKey via rds and secretsmanager) is `unverified` as exactly sufficient for AWS-managed keys; the first apply runs with the human's own profile anyway.
- R5. IAM condition-key support (`iam:PassedToService`, `iam:PolicyARN`, `iam:AWSServiceName`, `kms:ViaService`, `s3:prefix`, `aws:RequestedRegion`, `aws:PrincipalArn`) is from known Service Authorization Reference content; the per-action condition tables were not re-read in this step (the IAM page is too large to page through); reviewer to spot-check.
- R6. EMR instance role `s3:ListBucket` is limited by `s3:prefix` to raw, curated, graph, artifacts, logs/emr (each with and without `/*`). If EMRFS/S3A lists the bucket root (prefix "") during a mkdirs or a status check, that call is denied; untested until the first `s3://` run (Phase 13/14).
- R7. Neptune loader minimum actions (ListBucket on graph prefix, GetBucketLocation, GetObject) are narrower than the Neptune guide's example (`s3:Get*`, `s3:List*`); `unverified` until the first load.
- R8. EMR local disks (EBS) are not encrypted: EMR at-rest local-disk encryption needs a KMS key, and decision 6 area 3 chose no customer-managed key. Recorded as a gap; see proposed later.
- R9. With `skip_final_snapshot = false` a re-create needs the old final snapshot `scholarchain-course-neptune-final` removed by hand (variable description says so). Course default is skip.
- R10. AZ names, EMR label availability in us-east-1, and quota values are `unverified` (aws-api MCP unavailable; decision 2 facts used as given).
MANUAL ACTION REQUIRED:
- None for this step. Later (human): activate the cost-allocation tags `project`, `environment`, `owner` in Billing after the first apply (README); enter the four secret values after apply; set `owner` in local.auto.tfvars and confirm `expires` before plan.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: R-1 (code-reviewer reviews modules foundation ... vectors, STATE.md, README.md); step 2b (areas 9-13, root outputs, config/course.json).

## Probe

- `terraform -chdir=infra/terraform/environments/course validate` (first command, probe of the step-F allow rule) -> ran without an approval prompt; output "Success! The configuration is valid." (skeleton state).

## Area 1: foundation (done)

- `modules/foundation`: data `aws_caller_identity`, `aws_partition`, `aws_region` (uses `.region`, the 6.x attribute); `name_prefix` = `scholarchain-course`; `account_suffix` = first 8 hex of sha256(account ID) (non-reversible unique suffix for names that have no `*_prefix` argument).
- Root `main.tf`: `locals { project, environment }` passed to foundation.
- `infra/terraform/README.md`: layout, manual budget `scholarchain-25` (not managed), default tags and the manual activation of cost-allocation tags, encryption choice, S3 Vectors limits (PutVectors <= 500, topK <= 30, metadata limits, ENHANCED default), OpenSearch not built.
- fmt check 0; validate Success.

## Area 2: network (done)

- VPC `10.40.0.0/16` (variable), DNS support/hostnames on; tagged `for-use-with-amazon-emr-managed-policies=true` (EMR v2 policy needs it on the VPC for its own SGs and the EMR 8.x service endpoint).
- 2 AZs from `data.aws_availability_zones` (state available, opt-in not required, sorted, first 2). AZ names `unverified` (aws-api MCP unavailable).
- Subnets /24: public x2 (NAT only, `map_public_ip_on_launch = false`), app x2 (ALB, ECS, VPC link), data x2 (Neptune, EMR; EMR tag).
- One IGW, ONE NAT gateway + EIP in the first public subnet (decided; single-AZ egress risk documented in code and RISKS). One public route table, one private route table for all app+data subnets (default route via NAT).
- Gateway endpoints S3 and DynamoDB on the private route table (free). No interface endpoints.
- Default security group stripped of all rules (`aws_default_security_group` with no rules).
- Security groups (all created here): `vpc_link`, `alb`, `query`, `evidence`, `graph`, `worker`, `ops`, `neptune`, `emr-primary`, `emr-core`, `emr-service-access`. Chain:
  - vpc_link egress tcp 80 -> alb; alb ingress tcp 80 from vpc_link only.
  - alb egress tcp `service_port` (8080, variable; must equal `service.port` in config) -> query/evidence/graph; each of those ingress from alb only.
  - query/evidence/graph/worker/ops: egress tcp 443 to 0.0.0.0/0 (AWS APIs, Bedrock, Anthropic, Voyage via NAT) and tcp 8182 -> neptune. worker and ops have no ingress.
  - neptune: ingress tcp 8182 from the five roles; no egress.
  - EMR (EMR Management Guide, managed SGs for private subnets): primary/core ingress all from self and from each other, tcp 8443 from service-access; egress all (EMR documented default). service-access: ingress tcp 9443 from primary (EMR <= 7.x), ingress tcp 443 from the VPC CIDR (EMR 8.x / EMR Spark 8.x service endpoint), egress tcp 8443 -> primary and core. `revoke_rules_on_delete = true` on the EMR groups.
  - No 0.0.0.0/0 ingress anywhere (the only CIDR ingress is the VPC CIDR on service-access 443). Nothing has a public IP except the NAT EIP.
- fmt check 0; validate Success.

## Area 3: identity and encryption (done)

- `identity/main.tf`: EMR service role (trust `elasticmapreduce.amazonaws.com` with `aws:SourceAccount` and `aws:SourceArn`, from the EMR guide), AWS managed `AmazonEMRServicePolicy_v2` attached (tag-scoped), inline PassRole of the instance role to `ec2.amazonaws.com*` only (v2 allows PassRole only for the default role name). EMR instance role (trust ec2) + instance profile (S3 policy in area 5). Neptune loader role (trust `rds.amazonaws.com`; S3 policy in area 7).
- `identity/deployment.tf`: deployment role (trust: account root by default, or `deployment_trusted_principal_arns`), inline policy: regional service wildcards limited by `aws:RequestedRegion`; KMS DescribeKey/CreateGrant only via rds and secretsmanager; S3 only on `scholarchain-course-*` buckets (config actions; object writes only under `artifacts/emr/`); IAM only on `role/scholarchain-course-*` and `instance-profile/scholarchain-course-*`; AttachRolePolicy/DetachRolePolicy only for listed AWS managed policies (`iam:PolicyARN`); PassRole only to ecs-tasks, rds, states, apigateway; CreateServiceLinkedRole only for listed service names; explicit Deny of `GetSecretValue`/`BatchGetSecretValue`. Tag conditions not used (reason in the file and in RISKS).
- `identity/app-keys.tf`: four `aws_secretsmanager_secret` containers (voyage, anthropic, service, service-readonly), `name_prefix` from `app_key_name_prefix` (default `scholarchain/course/`), `recovery_window_in_days` from a variable (0 in course.tfvars). No version, no value. Each carries an `env-var` tag with its decided NAME.
- Encryption: AWS-managed everywhere (no customer-managed KMS key), recorded in README.
- Root: passes every identity variable; root variables `deployment_trusted_principal_arns`, `deployment_attachable_policy_arns`, `app_key_name_prefix`, `app_key_recovery_window_days`.
- fmt check 0; validate Success.

## Area 4: data lake (done)

- ONE bucket (`bucket_prefix = "scholarchain-course-data-"`), prefixes `raw`, `curated`, `graph`, `rag`, `artifacts`, `logs`, `reports` (output `prefixes`, no leading/trailing `/`; config `objectStore.*Prefix` in 2b must use these). One bucket because every policy scopes by prefix; no policy needs a second bucket.
- Block Public Access (4 flags), `BucketOwnerEnforced`, SSE-S3 (AES256), TLS-only bucket policy (deny `aws:SecureTransport=false` and `s3:TlsVersion < 1.2`).
- Versioning ON on this bucket (holds `reports/` grading evidence and the graph-load inputs); no separate evidence bucket.
- Lifecycle: `logs/` expire after 14 days (variable), their noncurrent versions after 1 day; noncurrent versions everywhere after 7 days (variable); incomplete multipart uploads aborted after 1 day; expired delete markers removed.
- `force_destroy = false`; cleanup path documented in `main.tf` (archive evidence, empty all versions via the confirmed cleanup step, then remove; area 13 / `cleanup.sh`). No Glue.
- Output `emr_log_uri` = `s3://<bucket>/logs/emr/`.
- fmt check 0; validate Success.

## Area 5: EMR (done)

- No `aws_emr_cluster`. `modules/emr`: bootstrap script `files/bootstrap.sh` uploaded as `aws_s3_object` to `artifacts/emr/bootstrap/scholarchain-bootstrap-v1.sh` (records host role and Java version with `STEP:` markers; installs nothing; shellcheck clean); output `cluster_settings` (one object for area 11's createCluster): release labels, log URI, bootstrap URI, subnet (first data subnet), service role ARN, instance profile name, EMR SGs (primary/core/service access), instance types, core count, `ON_DEMAND`, step concurrency 1, keep-alive true, managed scaling (Instances, min 2, max 4, max On-Demand 4, max core 4), idle timeout 900 s, tags (`for-use-with-amazon-emr-managed-policies=true`).
- Release labels as variables: Hadoop `emr-7.14.0` (newest 7.x in the EMR Release Guide 7.x page, read this step), Spark `emr-spark-8.1.0` (the `emr-spark810-release.html` page exists in the Release Guide). Availability in us-east-1: `unverified` (aws-api MCP unavailable).
- Bounded capacity: m5.xlarge (4 vCPU) primary + 2 core at start, managed scaling 2..4 core, On-Demand only (no Spot: failure behaviour untested). Peak per cluster (4 core + primary) x 4 vCPU = 20 vCPU <= `vcpu_budget` 32 <= quota 64. Preconditions enforce 1 <= min <= max, min <= start count <= max, and (max+1) x vCPU <= budget. Idle auto-termination 900 s (60..3600 validated).
- EMR instance role S3 policy (`identity/emr.tf`), derived from `JobRunner.scala`, `RunFs.scala`, `GraphAnalyticsJob.scala`: ListBucket with `s3:prefix` limited to raw, curated, graph, artifacts, logs/emr; GetBucketLocation, ListBucketMultipartUploads on the bucket; GetObject on raw/curated/graph/artifacts; Put/Delete/AbortMultipartUpload/ListMultipartUploadParts on curated/ and graph/ only; Put on logs/emr/. Delete is needed ONLY on the jobs' own output prefixes: Hadoop's FileOutputCommitter and Spark committers rename by copy + delete on S3, and `JobRunner` removes an incomplete run directory when `scholarchain.output.replaceIncomplete=true`. No delete on raw/, artifacts/, logs/, rag/, reports/.
- Root: every emr variable passed (`emr_*` root variables); identity now gets `data_bucket_arn` and `data_prefixes`.
- fmt check 0; validate Success; `shellcheck infra/terraform/modules/emr/files/bootstrap.sh` exit 0.

## Area 6: DynamoDB (done)

- Six `aws_dynamodb_table` from `TableSpecs.scala`: RunLedger, EntityLinkCache, QuerySession, EvidenceBundle, ArtifactRegistry, ResearcherMetricCache -> physical names `scholarchain-course-run-ledger`, `-entity-link-cache`, `-query-session`, `-evidence-bundle`, `-artifact-registry`, `-researcher-metric-cache`. Keys: `pk` (S, hash), `sk` (S, range). No GSI/LSI (TableSpecs: "there are no secondary indexes"). TTL only on QuerySession `expiresAt`.
- PAY_PER_REQUEST; encryption with the AWS owned key (DynamoDB default, no KMS charge; within "AWS-managed encryption", noted); PITR off by variable (`course.tfvars`); deletion protection off by variable (`course.tfvars`).
- Outputs `table_names`, `table_arns` keyed by logical name.
- fmt check 0; validate Success.

## Area 7: Neptune (done)

- `aws_neptune_subnet_group` (data subnets, 2 AZs), `aws_neptune_cluster_parameter_group` and `aws_neptune_parameter_group` (family `neptune1.4`; cluster parameter `neptune_enable_audit_log = 0`), `aws_neptune_cluster` (engine 1.4.7.1, newest 1.4 release in the Neptune guide; port 8182; `storage_encrypted = true` with aws/rds; `iam_database_authentication_enabled = true`; `iam_roles = [loader role]`; backup retention 1 day; `copy_tags_to_snapshot`; `deletion_protection` variable (false in course.tfvars); `skip_final_snapshot` variable (true in course.tfvars) with final snapshot name `scholarchain-course-neptune-final` when false; `apply_immediately` true), ONE `aws_neptune_cluster_instance` db.t4g.medium, `auto_minor_version_upgrade = false`. Neptune SG from the network module (ingress only from query/evidence/graph/worker/ops). No public endpoint, no audit-log export.
- Loader role S3 policy (`identity/neptune-loader.tf`): ListBucket with `s3:prefix` graph and graph/*, GetBucketLocation, GetObject on graph/*.
- Runtime read access (`neptune-db:connect`, `neptune-db:ReadDataViaQuery`) is NOT granted here; area 10 uses output `cluster_resource_id`.
- fmt check 0; validate Success.

## Area 8: S3 Vectors (done)

- `aws_s3vectors_vector_bucket` named `scholarchain-course-vectors-<8 hex of sha256(account)>` (built at plan time; names are unique per account and Region, no prefix argument), SSE `AES256`.
- Five `aws_s3vectors_index` built from `modules/storage/src/main/resources/s3vectors/index-spec-v1.json`, read in place by the root with `jsondecode(file(...))` (no copy): researcher-profile-v1, collaboration-community-v1, paper-abstract-v1, collaboration-path-v1, topic-institution-summary-v1; float32, 1024, cosine; `non_filterable_metadata_keys` = the 7 keys of each entry. Module validations: spec format/version, unique names, <= 10 non-filterable keys.
- ENHANCED mode: buckets created after Sep 30, 2026 default to ENHANCED (S3 User Guide); nothing set.
- Access: `aws_s3vectors_vector_bucket_policy` that denies the data plane (Get/Query/Put/Delete/ListVectors) to every principal whose `aws:PrincipalArn` is not in `data_principal_arns`; created only when that list is non-empty. Root variable `vectors_data_principal_arns` (default empty) is the hook: step 2b replaces it with the runtime and ops task-role ARNs from the runtime module. Tags on bucket and indexes.
- Limits recorded in README and in output `limits` (PutVectors <= 500, topK <= 30, dimension 1024).
- validate passing here also settles step 1's RISK R1 for S3 Vectors: `aws_s3vectors_vector_bucket`, `aws_s3vectors_index`, `aws_s3vectors_vector_bucket_policy` and their arguments exist in hashicorp/aws 6.59.0.
- fmt check 0; validate Success.

## Plan summary and checksum

No plan in this step (plan is the human's, decision 9). Inferred from the code, not from a plan: what step 2a's configuration would create (all creates, no change or removal):
- network: 1 VPC, 1 default-SG takeover, 1 IGW, 6 subnets, 1 EIP, 1 NAT gateway, 2 route tables, 2 routes, 6 route-table associations, 2 gateway endpoints, 11 security groups, 35 security-group rules (23 runtime chain, 12 EMR)
- identity: 4 roles, 1 instance profile, 1 managed-policy attachment, 4 inline role policies, 4 secret containers
- data lake: 1 bucket + ownership, public-access block, encryption, versioning, lifecycle, bucket policy
- emr: 1 S3 object (bootstrap script)
- dynamodb: 6 tables
- neptune: 1 subnet group, 2 parameter groups, 1 cluster, 1 instance
- vectors: 1 vector bucket, 5 indexes (bucket policy only once 2b passes role ARNs)
Checksum: none (no plan file).

## IAM changes

- New roles: EMR service (AWS managed `AmazonEMRServicePolicy_v2` + inline PassRole of the instance role to EC2), EMR instance (+ instance profile; inline S3 data-lake access), Neptune loader (inline S3 read on graph/), deployment (inline Terraform deployment policy). No IAM user; nothing touches `scholarchain-readonly`.

### Every `*` in a policy, with its reason

| Where | Statement | `*` in | Reason |
|---|---|---|---|
| identity/deployment.tf | `RegionalServicesInRegion` | Resource (and `service:*` actions) | Terraform must create resources whose ARNs do not exist yet and call Describe/List actions that allow only `*`; limited by `aws:RequestedRegion = us-east-1`. Deployment is broad by nature (decision 6 area 3). |
| identity/deployment.tf | `AwsManagedKeysViaServices` | Resource | AWS managed key ARNs (aws/rds, aws/secretsmanager) are not known before plan; limited by `kms:ViaService` to rds and secretsmanager in the Region. |
| identity/deployment.tf | `WhoAmI` | Resource | `sts:GetCallerIdentity` supports no resource-level permission. |
| identity/deployment.tf | `NeverReadAppKeyValues` (Deny) | Resource | A Deny: wide on purpose (the deployment role never reads any secret value). |
| data-lake/main.tf | `DenyInsecureTransport`, `DenyOldTls` (Deny) | Principal | Deny for every principal without TLS / below TLS 1.2. |
| vectors/main.tf | `DataPlaneOnlyForRuntimeAndOps` (Deny) | Principal | Deny the data plane to every principal not in the allow list. |
| AWS managed `AmazonEMRServicePolicy_v2` (attached) | several | Resource | AWS-owned content; its `*` statements are tag-conditioned (`for-use-with-amazon-emr-managed-policies=true`) or read-only Describe actions. |

Pattern wildcards (not bare `*`): deployment S3 `arn:aws:s3:::scholarchain-course-*` and `.../artifacts/emr/*`; deployment IAM `role/scholarchain-course-*`, `instance-profile/scholarchain-course-*`, `role/aws-service-role/*`, `arn:aws:iam::aws:policy/*` (Get only); EMR instance and Neptune loader object ARNs `<bucket>/<prefix>/*`; S3 Vectors `<vector bucket>/index/*`; EMR service trust `aws:SourceArn arn:aws:elasticmapreduce:us-east-1:<account>:*` (from the EMR guide).

## Stateful-resource actions

Creates only: data-lake bucket (versioned, `force_destroy = false`), six DynamoDB tables (deletion protection off, PITR off, by variable), Neptune cluster and instance (deletion protection off, skip final snapshot, by variable), S3 Vectors bucket and five indexes, four secret containers (recovery window 0 in course.tfvars). No replacement or removal.

## Cost estimate (2a resources; prices and the full table belong to COSTS.md, step 2b; all `estimate (unverified)`)

- Hourly while they exist: NAT gateway (hourly + per GB) and its public IPv4 address; Neptune db.t4g.medium (free-trial hours if the trial applies; storage and I/O); four Secrets Manager secrets (monthly each).
- Per use: EMR clusters (EC2 + EMR per-second, only while a pipeline runs; m5.xlarge x 3 at start, up to x 5), EMR-created service VPC endpoint for EMR Spark 8.x (RISK R2), DynamoDB on-demand requests and storage, S3 storage/requests, S3 Vectors storage/requests.
- Free: VPC, subnets, route tables, gateway endpoints, security groups, IAM.

## Required environment variables by name

- Secret containers map to (decided names): `SCHOLARCHAIN_VOYAGE_API_KEY`, `SCHOLARCHAIN_ANTHROPIC_API_KEY`, `SCHOLARCHAIN_SERVICE_API_KEY`, `SCHOLARCHAIN_SERVICE_READONLY_API_KEY` (output `app_key_secret_arns_by_env_var`).
- Terraform inputs added (root variables): `vpc_cidr`, `service_port`, `deployment_trusted_principal_arns`, `deployment_attachable_policy_arns`, `app_key_name_prefix`, `app_key_recovery_window_days`, `logs_expiration_days`, `noncurrent_version_expiration_days`, `emr_hadoop_release_label`, `emr_spark_release_label`, `emr_primary_instance_type`, `emr_core_instance_type`, `emr_instance_vcpus`, `emr_core_instance_count`, `emr_managed_scaling_min_units`, `emr_managed_scaling_max_units`, `emr_vcpu_budget`, `emr_idle_timeout_seconds`, `emr_step_concurrency_level`, `dynamodb_point_in_time_recovery_enabled`, `dynamodb_deletion_protection_enabled`, `neptune_engine_version`, `neptune_instance_class`, `neptune_backup_retention_days`, `neptune_deletion_protection`, `neptune_skip_final_snapshot`, `neptune_apply_immediately`, `vectors_data_principal_arns` (all with defaults; course.tfvars sets `app_key_recovery_window_days = 0`, the two DynamoDB flags, the two Neptune flags).

## Proposed later

- Permissions boundary for roles the deployment role creates (closes RISK R3); needs a design of what runtime roles may hold.
- Customer-managed KMS key + `aws_emr_security_configuration` for EMR local-disk (EBS) and in-transit encryption (RISK R8); cost of a CMK (monthly) is the reason it is not built.
- VPC flow logs to CloudWatch (per-GB ingestion cost; security visibility).
- Interface endpoints (ECR api/dkr, Secrets Manager, CloudWatch Logs, STS) to take ECS traffic off the NAT: each about $0.01 per hour per AZ plus per GB (estimate (unverified)), so about $0.48/day for 2 AZs per endpoint; not justified at course traffic.
- `emr_service_vpce` cleanup: let cleanup.sh find and list EMR-created endpoints by the `for-use-with-amazon-emr-managed-policies` / `Name=emr-service-vpce` tags (step 2c).

## INTERFACE HANDOFF (outputs per module, for steps 2b and 2c)

- `module.foundation`: `account_id`, `partition`, `region`, `dns_suffix`, `name_prefix` (`scholarchain-course`), `account_suffix`.
- `module.network`: `vpc_id`, `vpc_cidr`, `availability_zones`, `public_subnet_ids`, `app_subnet_ids`, `data_subnet_ids`, `private_route_table_id`, `nat_gateway_id`, `s3_gateway_endpoint_id`, `dynamodb_gateway_endpoint_id`, `service_port`, `alb_listener_port`, `neptune_port`, `security_group_ids` (map: `vpc_link`, `alb`, `query`, `evidence`, `graph`, `worker`, `ops`, `neptune`, `emr_primary`, `emr_core`, `emr_service_access`), `emr_managed_policy_tag`.
- `module.identity`: `emr_service_role_arn`, `emr_service_role_name`, `emr_instance_role_arn`, `emr_instance_profile_name`, `emr_instance_profile_arn`, `neptune_loader_role_arn`, `deployment_role_arn`, `app_key_secret_arns` (keys `voyage`, `anthropic`, `service`, `service-readonly`), `app_key_secret_arns_by_env_var` (keys = the four env-var NAMES).
- `module.data_lake`: `bucket_name`, `bucket_arn`, `prefixes` (`raw`, `curated`, `graph`, `rag`, `artifacts`, `logs`, `reports`), `emr_log_uri`.
- `module.emr`: `cluster_settings` (object: `hadoop_release_label`, `spark_release_label`, `log_uri`, `bootstrap_script_uri`, `subnet_id`, `service_role_arn`, `instance_profile`, `security_groups{emr_managed_primary, emr_managed_core, service_access}`, `primary_instance_type`, `core_instance_type`, `core_instance_count`, `market`, `step_concurrency_level`, `keep_alive_no_steps`, `managed_scaling{unit_type, minimum_capacity_units, maximum_capacity_units, maximum_on_demand_capacity_units, maximum_core_capacity_units}`, `idle_timeout_seconds`, `tags`), `bootstrap_script_uri`, `hadoop_release_label`, `spark_release_label`.
- `module.dynamodb`: `table_names`, `table_arns` (keys: `RunLedger`, `EntityLinkCache`, `QuerySession`, `EvidenceBundle`, `ArtifactRegistry`, `ResearcherMetricCache`).
- `module.neptune`: `cluster_endpoint`, `reader_endpoint`, `port`, `cluster_id`, `cluster_arn`, `cluster_resource_id`, `instance_id`.
- `module.vectors`: `vector_bucket_name`, `vector_bucket_arn`, `index_arns` (by index name), `index_names_by_config_key` (by `vectors.*Index`), `limits`.
- Hooks for 2b: root variable `vectors_data_principal_arns` (replace with runtime + ops role ARNs); root variable `deployment_attachable_policy_arns` (add e.g. the ECS task execution managed policy if used); `service_port` must equal `service.port` in config; the Step Functions role must PassRole both `emr_service_role_arn` and `emr_instance_role_arn` and tag clusters with `emr_managed_policy_tag`.
- Thread-safety: not applicable. Limits: EMR peak 20 vCPU per cluster (budget 32, quota 64); Neptune one instance; DynamoDB on-demand; S3 Vectors PutVectors <= 500, topK <= 30.
- Not done here by design: root `outputs.tf` (step 2b), runtime/workflow roles (areas 10/11).

VALIDATION EVIDENCE
- fmt: terraform fmt -recursive -check infra/terraform -> 0, no files listed
- validate: terraform -chdir=infra/terraform/environments/course validate -> "Success! The configuration is valid."
- shellcheck: shellcheck infra/terraform/modules/emr/files/bootstrap.sh -> 0; clean
- jq: not run: no JSON file written in this step -> n/a
- finished: 2026-11-17 02:20:07 CST
- files changed after these runs: no
