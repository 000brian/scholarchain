# ScholarChain infrastructure (Terraform)

Owner: `infra-engineer`. Nothing here is applied by an agent. The human runs `init` and `scripts/plan.sh`, signs the plan, and `deploy-engineer` applies the signed plan with `scripts/deploy.sh` (Phase 14 and later).

## Layout

| Path | Contents |
|---|---|
| `environments/course/` | The one root module (`course` environment, `us-east-1`), provider pin, default tags, variable files |
| `modules/foundation` | Account, partition and region data sources; the name prefix |
| `modules/network` | VPC in 2 AZs, public/app/data subnets, one NAT gateway, S3 and DynamoDB gateway endpoints, every security group |
| `modules/identity` | EMR service role and instance profile, Neptune loader role, deployment role, Secrets Manager containers (`app-keys.tf`) |
| `modules/data-lake` | The data-lake bucket and its prefixes |
| `modules/emr` | What a transient EMR cluster needs (bootstrap script, log location, release labels, bounded capacity); no cluster |
| `modules/dynamodb` | The six on-demand tables |
| `modules/neptune` | One provisioned Neptune cluster with one instance |
| `modules/vectors` | One S3 Vectors bucket, five indexes, the bucket policy |
| `modules/runtime` | ECR, ECS cluster, four Fargate ARM64 services (query, evidence, graph, worker), the `ops` task definition, internal ALB, API Gateway HTTP API + VPC link, autoscaling, runtime roles |
| `modules/workflow` | Job and embedding SQS queues with dead-letter queues, the bounded pipeline state machine and its role |
| `modules/observability` | Log groups (7 days), one dashboard, default-metric alarms, one SNS topic |
| `STATE.md` | Why the course environment uses local state; the remote-state design |
| `COSTS.md` | Every resource that bills while it exists, idle and running cost per day |

Only the `hashicorp/aws` provider is used, pinned once in `environments/course/versions.tf`. Child modules are local folders, with no nested `module` blocks.

## Budget (manual, not managed by Terraform)

- The AWS budget `scholarchain-25` was created by hand by the human architect (Nov 15) with email alerts at $10, $15 and $20. Terraform does not manage, import or reference it, and no resource here uses that name.
- The ceiling is $25 in total across AWS, Anthropic and Voyage. Budget alerts are warnings only; they stop nothing. Bounded capacity, the `runtime_enabled` switch (step 2b) and prompt cleanup are what keep the cost down.
- Cleanup of the budget itself is a Phase 17 manual item, together with the IAM user `scholarchain-readonly`.

## Tags and cost allocation

Every taggable resource gets these tags from the provider's `default_tags` (`environments/course/providers.tf`):

| Key | Value |
|---|---|
| `project` | `scholarchain` |
| `course` | `cs441` |
| `environment` | `course` |
| `owner` | `var.owner` (personal; `local.auto.tfvars`, gitignored) |
| `expires` | `var.expires` (date; `course.tfvars`) |
| `managed-by` | `terraform` |

EMR-related network resources (the VPC, the private data subnets, the three EMR security groups) also carry `for-use-with-amazon-emr-managed-policies = true`, which the AWS managed policy `AmazonEMRServicePolicy_v2` requires on resources EMR did not create.

Manual action (human, once, in the Billing console, after the first apply, because a tag key appears there only after a resource carries it): activate `project`, `environment` and `owner` as **user-defined cost allocation tags**. Activation takes up to 24 hours and is not retroactive. Terraform cannot do this without managing account-level billing settings, which this project does not do. Cost Explorer can then be filtered on `project=scholarchain`.

Leftover resources (for example after losing the local state, see `STATE.md`) are found by the tags `project=scholarchain` and `environment=course`.

## Encryption choice

AWS-managed encryption everywhere, no customer-managed KMS key (decision 6 area 3; cost: a customer-managed key bills monthly and per request, with no grading need): SSE-S3 on the data-lake bucket and the S3 Vectors bucket, the `aws/rds` key for Neptune storage, the AWS owned key for DynamoDB (the DynamoDB default, no charge), the `aws/secretsmanager` key for the four secret containers.

## S3 Vectors limits (for the application configuration)

- One vector bucket, five indexes, created from `modules/storage/src/main/resources/s3vectors/index-spec-v1.json` (read by Terraform with `file()`; name, dimension 1024, `float32`, `cosine`, non-filterable metadata keys).
- Vector buckets created after September 30, 2026 use the `ENHANCED` index mode by default (metadata filter applied before the vector search); Terraform sets nothing for it (AWS documentation, "Changing a vector index's mode").
- Keep `PutVectors` batches at **500 vectors or fewer** per call and query `topK` at **30 or fewer** (limits given by the human architect for the application configuration; check the S3 Vectors quotas page before raising either).
- Metadata limits from the index spec: filterable metadata up to 2 KB, total metadata up to 40 KB, up to 50 keys, up to 10 non-filterable keys per index.
- Data access to the vector bucket is restricted by its bucket policy to the runtime task roles and the `ops` task role (`module.runtime.vector_principal_arns`, plus any extra principal in `vectors_data_principal_arns`). The root always passes these ARNs (`environments/course/main.tf`, module `vectors`), so the bucket policy always exists.

## OpenSearch

Not built: no hot or hybrid retrieval tier is implemented or evaluated (decision 6 area 9).

## Runtime switch (area 10)

- `runtime_enabled = false` (`course.tfvars`): the ECS services exist with desired count 0, every scaling target has min 0 and max 0, no scaling policy or worker alarm exists, and no task runs. The ALB, the API Gateway HTTP API and its VPC link still exist (and the ALB bills hourly; see `COSTS.md`).
- Phase 14 turns it on in a second signed plan with `runtime_enabled = true`, `image_tag = "<40-hex git SHA pushed to ECR>"` and `graph_run_id = "run:v1:<32 hex>"`; the plan fails if either is missing.
- Public address: output `api_url` (AWS-managed `https://...execute-api...`). The service API keys are the only authentication.
- The services image must contain `/opt/scholarchain/config/course.json` (Phase 13, build-engineer); the task command reads it.

## Pipeline state machine and EMR cluster termination (area 11)

- One execution creates at most two transient clusters, one after the other, and ends each with `terminateCluster.sync`, on success and after any failure.
- Optional JSON Lines export (Phase 13): with `spark.exportRoot` and `spark.exportSourceRun` (the full `.../run=<32 hex>` path of an earlier graph run) in the execution input, a second step on the Spark cluster runs `org.scholarchain.spark.JsonlExport` after `GraphAnalyticsJob`. Both fields come together and both must lie under the data lake's `graph/` prefix, or the execution fails at `ExportInputCheck` before any cluster exists. The input format is in the header of `modules/workflow/sfn.tf`.
- **Step Functions does not end an EMR cluster when an execution is stopped, aborted or times out.** In those cases, and when ending a cluster itself fails (`ClusterCleanupFailed`), the cluster's idle auto-termination policy (`emr_idle_timeout_seconds`, 900 s) ends it once it is idle. After stopping an execution by hand, check for clusters tagged `project=scholarchain` that are still running and end them.
- EMR on release 8.x creates an interface VPC endpoint named `emr-service-vpce` in the tagged VPC; whether EMR removes it when the cluster ends is unverified. Look for it after each run (it bills hourly; `COSTS.md`).

## Alarms (area 12)

- Alarms notify the SNS topic `scholarchain-course-alarms` (output `alarm_topic_arn`). Terraform creates no subscription. Manual action: subscribe an e-mail address by hand and confirm it.

## Deployment role and permissions boundary (repair I-01)

- The deployment role (`modules/identity/deployment.tf`) may write IAM roles only on the `scholarchain-course-*` name pattern, and its own name falls under that pattern. Two controls stop it from raising its own or another role's permissions:
  - an explicit Deny of every IAM write on its own name pattern `scholarchain-course-deployment-*` (`PutRolePolicy`, `DeleteRolePolicy`, `AttachRolePolicy`, `DetachRolePolicy`, `UpdateAssumeRolePolicy`, `PutRolePermissionsBoundary`, `DeleteRolePermissionsBoundary`, `DeleteRole`, `UpdateRole`, `UpdateRoleDescription`, `TagRole`, `UntagRole`, plus `CreateRole` and `PassRole`), so it cannot raise its own session duration either (Phase 13 I-16);
  - an explicit Deny of `secretsmanager:PutResourcePolicy` and `DeleteResourcePolicy` on every secret, so no resource policy can grant the app key values to another principal (Phase 13 I-02; Terraform defines no secret resource policy);
  - the managed policy `scholarchain-course-role-boundary` (`modules/identity/boundary.tf`), set as the permissions boundary of every other project role (EMR service and instance roles, Neptune loader, ECS execution role, the five task roles, the Step Functions role). The deployment role may call `CreateRole`, `PutRolePolicy`, `AttachRolePolicy`, `PutRolePermissionsBoundary` and `UpdateAssumeRolePolicy` only on roles that carry it (condition `iam:PermissionsBoundary`, and a Deny when it is absent or different); it may never remove a boundary or change the boundary policy.
- Consequence: changes to the deployment role, its inline policy or the boundary policy, and their removal in cleanup, are applied with the human's own profile, never with the deployment role.
- Residual risk (accepted, documented): inside the boundary the deployment role can still give a project role the boundary's data access and run it, for example an ECS task with the execution role, which receives the four app key values. The boundary stops administrator rights, IAM changes, other buckets and other Regions; it does not make the deployment role unable to reach the project's own data. The human's plan review stays the control for that.

## Task definition revisions and rollback (repair I-07)

- Both task-definition resources set `skip_destroy = true`: when a deploy replaces a task definition (new image tag), Terraform registers the new revision and leaves the previous one ACTIVE instead of deregistering it, so `scripts/rollback.sh` can point a service back at the revision recorded as `previous_task_definition`.
- Old revisions cost nothing. Cleanup (Terraform removal plan and `scripts/cleanup.sh`) leaves them behind as ACTIVE revisions; they bill nothing and hold no data.

## Cleanup mode (area 13)

`cleanup_mode = true` turns every deletion protection off (DynamoDB tables, Neptune) regardless of the per-resource variables and requires `runtime_enabled = false`. It removes nothing by itself. Removal order (each step a human-approved action; `scripts/cleanup.sh` lists every target first and asks for a typed confirmation):

1. Archive the grading evidence (`reports/` prefix, release records, run reports) to a local archive (`scripts/archive-evidence.sh`, Phase 13). `cleanup.sh` refuses to run without it.
2. Scale the runtime to zero: a signed plan with `runtime_enabled = false`.
3. Make sure no pipeline execution runs and no cluster tagged `project=scholarchain` is alive; remove a leftover `emr-service-vpce` endpoint.
4. A signed plan with `cleanup_mode = true` (protection off).
5. Empty what Terraform will not remove while it holds data: the ECR images, every object version in the data-lake bucket (`force_destroy` stays false), the vectors in the five indexes if the provider refuses to remove a non-empty index.
6. Remove the environment with `scripts/cleanup.sh --remove`, run by the human with the human's own profile (not `deploy-engineer`, not the deployment role), from a signed removal plan: API Gateway, VPC link, ALB, ECS, Step Functions, SQS, Neptune, DynamoDB, S3 Vectors, secrets (recovery window 0), log groups, then the NAT gateway, its address and the VPC.
7. Look for leftovers by the tags `project=scholarchain` and `environment=course`; the hand-made IAM user `scholarchain-readonly` and the budget `scholarchain-25` are Phase 17 manual items, outside Terraform.
