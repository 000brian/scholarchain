PHASE 12 REVIEW R-1: review-report-a.md

STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: /Users/brian/Developer/repos/scholarchain/docs/agent-runs/phase-12/prompt.md (sha256 5744ab73aa42946f1c556d1257a45031fd57868b75480de623879a9c73de7f56, as given by the orchestrator; I could not re-hash it because I have no Bash), step R-1. Scope: infra/terraform/modules/{foundation,network,identity,data-lake,emr,dynamodb,neptune,vectors}/** plus infra/terraform/STATE.md and infra/terraform/README.md.
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools)
TEST RESULTS: not applicable (no sbt in this phase; reviewer runs nothing).
VALIDATION RESULTS: I did not re-run fmt, validate or shellcheck (no Bash). The writer's VALIDATION EVIDENCE is unconfirmed by me. The orchestrator's re-run under decision 7 is the check for it.
ASSUMPTIONS:
- I reviewed the files as I read them. None of the 2a files I read looked partly written or changed while I was reading.
- The root module (environments/course/**) and runtime, workflow and observability were not reviewed. I read only course.tfvars, to check the writer's claim about variable values.
- I checked IAM condition-key support against what I know of the Service Authorization Reference. I could not look anything up (no MCP or web tools).
RISKS:
- RK1 (Neptune bulk load, not verified): the Neptune security group has no egress rule (network/main.tf:234-244; Terraform drops the AWS default allow-all egress). The loader reaches S3 through the gateway endpoint. If the cluster ENI's egress rules apply to loader traffic, the first load fails. Aurora's S3 integration needs an outbound rule; I did not confirm whether Neptune does. Check before the Phase 14 load. Possible fix: egress tcp 443 to the S3 gateway endpoint prefix list.
- RK2 (EMR listing, the writer's R6, still not verified): ListBucket is limited by s3:prefix (identity/emr.tf:25-36). A root listing, or a directory marker such as `curated_$folder$` written for a top-level prefix, would be denied. Untested until the first s3:// run.
- RK3: none of these facts is verified: the deployment policy is enough for a full apply (writer R4/R5); Neptune engine 1.4.7.1 exists; emr-7.14.0 and emr-spark-8.1.0 exist in us-east-1; the EMR 8.x service-access 443 rule is right.
- RK4: the vectors bucket policy exists only if step 2b passes role ARNs (vectors/main.tf:56,81; the default is an empty list). If 2b does not, nothing limits vector data access to the runtime and ops roles. R-2 must check this.
MANUAL ACTION REQUIRED: none from this review. The human decides on the deviations I-03 and I-05 at the gate.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: step 3 triage. Route I-01 (major) to infra-engineer for the step 4 repair round and record I-02 to I-06 as minor. R-2 must check RK4 (vectors_data_principal_arns wired from runtime), that the 2b roles keep the `scholarchain-course-` name prefix the deployment policy relies on, and whether the PassRole target `apigateway.amazonaws.com` is used at all.

VERDICT: REQUEST_CHANGES

Findings

| ID | Severity | File:line | Finding (concrete failure input -> impact) | Owner | Suggested fix |
|---|---|---|---|---|---|
| I-01 | major | infra/terraform/modules/identity/deployment.tf:120-149 (PutRolePolicy 134, UpdateAssumeRolePolicy 141, CreateRole 125), 151-164, 166-181; role name 37 | The deployment role can make itself, or any role it creates, an administrator. Its IAM write actions apply to `role/scholarchain-course-*`, and its own name_prefix is `scholarchain-course-deployment-`, so that pattern includes the deployment role itself. Example: while assuming the role, call `iam:PutRolePolicy` on its own name with policy name `terraform-deployment` and `{"Effect":"Allow","Action":"*","Resource":"*"}`. That overwrites the inline policy, including the `NeverReadAppKeyValues` deny at 218-224, and the role becomes full account admin. A second route: `iam:CreateRole` with a name like `scholarchain-course-x`, a trust policy for an external account, and an admin inline policy, which leaves a lasting backdoor. Or pass that role to ecs-tasks (allowed, 166-181) and run a task with `ecs:*` that reads the secrets. Impact: the claims in the header (lines 4-10) and the "runtime/deployment separation" do not hold. The gate "IAM policies are scoped to actual resources and actions" is not met. The writer's R3 understates this: the role can grant itself more, not only "those roles". | infra-engineer | (a) Add an explicit Deny on the deployment role's own ARN for `iam:PutRolePolicy`, `DeleteRolePolicy`, `AttachRolePolicy`, `DetachRolePolicy`, `UpdateAssumeRolePolicy`, `PutRolePermissionsBoundary`, `DeleteRolePermissionsBoundary` and `DeleteRole`. Document that changes to the role's own policy are applied with the human's profile, or move the role out of the plan it applies. (b) Require a permissions boundary on the roles it creates: a condition `iam:PermissionsBoundary` = <boundary policy ARN> on `CreateRole`, `PutRolePolicy`, `AttachRolePolicy` and `PutRolePermissionsBoundary`, plus a Deny on `DeleteRolePermissionsBoundary` and on changes to the boundary policy. The boundary allows only what the runtime, EMR, loader and Step Functions roles need. This turns "proposed later" into the fix. If the human rejects (b), at least (a) plus a documented residual risk. |
| I-02 | minor | infra/terraform/modules/identity/deployment.tf:63 (`secretsmanager:*`), 218-224 | The deny only stops this role from calling GetSecretValue itself. `secretsmanager:*` also allows `PutResourcePolicy` on the four containers. Example: grant `secretsmanager:GetSecretValue` to another principal (even another account) through a resource policy, then read the value as that principal. Impact: a second route around "deployment never reads application key values". Nothing in 2a sets a secret resource policy. | infra-engineer | Add `secretsmanager:PutResourcePolicy` (and `GetResourcePolicy`/`DeleteResourcePolicy` if not needed) to the Deny, or replace `secretsmanager:*` with the actions Terraform calls for containers (Create, Describe, Tag/Untag, Update, Delete, RestoreSecret, ListSecrets, GetResourcePolicy). |
| I-03 | minor | infra/terraform/modules/identity/deployment.tf:11-16, 49-75 | Decision 6 area 3 says to scope by the `project=scholarchain` tag "only where the reference supports it". The writer used no tag condition anywhere. Destructive actions that support `aws:ResourceTag` (for example `dynamodb:DeleteTable`, `rds:DeleteDBCluster`, `ec2:TerminateInstances`, `ec2:DeleteVpc`, `sqs:DeleteQueue`) can therefore act on any resource in us-east-1, not only project ones. The writer's reason (create actions would need RequestTag) explains why it skipped creates, not why it skipped deletes and modifications. Impact: a mistake or misuse under this role can remove resources outside the project. This is a deviation that is not reported as one. Low practical risk in a single-project account. | infra-engineer | Add a Deny statement for these destructive and modifying actions with `StringNotEquals aws:ResourceTag/project = scholarchain` (only for actions whose reference lists the key), or record it as a `deviation` for the human. |
| I-04 | minor | infra/terraform/modules/identity/deployment.tf:114-118 | The data-lake bucket is versioned (data-lake/main.tf:64-70). When provider 6.x deletes `aws_s3_object` (key change or removal), it lists the object's versions and deletes each version ID. That needs `s3:DeleteObjectVersion`, which `ProjectBootstrapObjects` does not grant. ListBucketVersions is covered by `s3:List*`. From my reading of the provider, not tested. Impact: replacing or removing the bootstrap object under the deployment role fails with AccessDenied. | infra-engineer | Add `s3:DeleteObjectVersion` (and `s3:GetObjectVersion`) on `.../artifacts/emr/*`, or mark it `unverified` in the report. |
| I-05 | minor | infra/terraform/modules/identity/emr.tf:14-20, 51-60 | Decision 6 area 5 says delete "only on its own temporary prefix". The policy grants `s3:DeleteObject` on all of `curated/*` and `graph/*`. Part of this is needed: JobRunner.scala:113 deletes the whole incomplete run directory (`fs.delete(out, true)`), and Pipeline.scala:306-307 places `_checkpoint/` and `_staging/` under the output root. Impact: a faulty or misconfigured EMR step can delete completed outputs of other runs under curated/ and graph/. (S3 versioning allows recovery for 7 days.) This is a deviation that is not reported as one. | infra-engineer | Record it as a `deviation` with the JobRunner and Pipeline line references, or narrow delete to `curated/*/run=*/*` plus `graph/*/run=*`, `graph/*/_checkpoint/*`, `graph/*/_staging/*` and `*/_temporary/*`, depending on the Spark output layout. |
| I-06 | minor | infra/terraform/modules/emr/main.tf:37-40; variables.tf:76-92 | The vCPU budget check computes `(max + 1) * instance_vcpus` with one vCPU value for both node types, although primary and core types are separate variables. Example: `emr_primary_instance_type = "m5.4xlarge"` (16 vCPU) with defaults passes the check (20 ≤ 32) while the real peak is 32. Impact: the bounded-capacity guard can be bypassed by a changed variable without any warning. | infra-engineer | Split into `primary_instance_vcpus` and `core_instance_vcpus`, and check `primary + max * core <= vcpu_budget`. |

Checklist (decision 8, applied to the 2a files)

Spec gate items
- Plan has no unexplained destroy or replacement of stateful resources: no plan in this step (it is the human's). The code creates only. Stateful resources: S3 bucket data-lake/main.tf:30-35; DynamoDB dynamodb/main.tf:46-80; Neptune neptune/main.tf:56-91; vectors vectors/main.tf:29-53; secrets identity/app-keys.tf:35-43. `create_before_destroy` is set only on security groups and parameter groups (network/main.tf:203-261, neptune/main.tf:39-41, 51-53), none of which holds state. Checked, none.
- IAM scoped to actual resources and actions: see I-01 (major), I-02, I-03, I-04, I-05. The EMR service role (identity/main.tf:43-81), EMR instance role S3 access (identity/emr.tf) and Neptune loader (identity/neptune-loader.tf) are scoped to the bucket ARN, to prefixes, and to the instance role for PassRole.
- Buckets and databases not public: data-lake public access block, all four flags (data-lake/main.tf:45-52); BucketOwnerEnforced (37-43); the bucket policy only denies (122-164). Neptune is in the private data subnets (variables.tf:8-11, network/main.tf:85-98) and has no public endpoint argument. DynamoDB has no resource policy. Vectors: the policy only denies (vectors/main.tf:55-78). Checked, none.
- Default capacity bounded: see "Bounded capacity" below.
- Secrets not in source or plan: containers only (identity/app-keys.tf:35-43). A grep of infra/terraform/modules for `secret_version` found none.
- Human signs the plan: not applicable to R-1.

IAM rule
- Each `*` has a comment: deployment.tf:46-48 (RegionalServicesInRegion), 79-81 (KMS via service), 210-211 (sts:GetCallerIdentity). Deny statements: 218-224 (deployment), data-lake/main.tf:132-135 and 153-156 (principal `*` on a Deny), vectors/main.tf:67-70 (principal `*` on a Deny). Each has a comment.
- `*` where the reference allows a resource: the RegionalServices statement (deployment.tf:49-75) is broad by decision 6 area 3. Region-limited. See I-03.
- Tag conditions only where supported: none used. Condition keys in use: `aws:RequestedRegion` (global), `kms:ViaService` (KMS CreateGrant and DescribeKey), `iam:PolicyARN` (Attach and DetachRolePolicy), `iam:PassedToService` (PassRole), `iam:AWSServiceName` (CreateServiceLinkedRole), `s3:prefix` (ListBucket), and `aws:SourceAccount`/`aws:SourceArn` in the EMR trust (identity/main.tf:29-39). All are valid for their actions as far as I know (not verified against the live reference).
- Needed read actions present: EMR GetBucketLocation, ListBucketMultipartUploads and prefixed ListBucket (emr.tf:25-43); loader prefixed ListBucket and GetBucketLocation (neptune-loader.tf:7-23); deployment Get*/List* on buckets, IAM Get/List, managed-policy reads (deployment.tf:99-112, 129-134, 204-208). See I-04.
- Runtime and deployment separated: separate roles (identity/main.tf, deployment.tf; runtime roles go in 2b). The separation does not hold because of I-01 and I-02.
- `iam:PassRole` scoped: EMR service role passes only the instance role ARN, only to `ec2.amazonaws.com*` (identity/main.tf:63-75). Correct, because AmazonEMRServicePolicy_v2 allows PassRole only for the default role name. The deployment role passes `role/scholarchain-course-*` to ecs-tasks, rds, states and apigateway (deployment.tf:166-181). That pattern includes the deployment role (I-01). `apigateway.amazonaws.com` is not needed by anything in 2a; R-2 to confirm.

No public access
- No 0.0.0.0/0 ingress: every ingress rule references a security group, except service-access tcp 443 from `var.vpc_cidr` (network/main.tf:413-420, private). 0.0.0.0/0 appears only as egress (322, 398) and in routes (132, 152). Checked, none.
- No public IP except the NAT: `map_public_ip_on_launch = false` on all subnets (69, 80, 91); EIP only for the NAT (107-122). The default security group has no rules (49-53).
- ALB internal, VPC link only: ALB ingress only from the VPC link security group (network/main.tf:275-282). The ALB itself, API Gateway throttling and access logs are 2b scope, for R-2.
- Vector access only from runtime and ops: deny-unless-listed policy (vectors/main.tf:55-85). Only active once 2b passes ARNs (RK4). Resource ARNs (bucket and `/index/*`) and the data-plane action list are correct.

No secret, literal account ID, literal bucket name, public IP or secret_version
- Grep of infra/terraform (excluding .terraform) for 12-digit numbers and dotted IPv4: only private CIDRs (10.40.0.0/16), the engine version 1.4.7.1 and 0.0.0.0/0. The bucket uses `bucket_prefix` (data-lake/main.tf:31). The vector bucket name is computed at plan time from a sha256 of the account ID (foundation/main.tf:19, vectors/main.tf:17). Account ID and partition come from data sources (foundation/main.tf:5-9). Checked, none.

Bounded capacity
- EMR: On-Demand only (emr/main.tf:63); core count 1-4 (variables.tf:99-102); managed scaling min 2, max 4 with preconditions (main.tf:27-40); vCPU budget ≤ 64 (variables.tf:122-125); idle auto-termination 900 s, range 60-3600 (128-137). No cluster resource. See I-06.
- DynamoDB: PAY_PER_REQUEST (dynamodb/main.tf:50).
- Neptune: one `aws_neptune_cluster_instance`, db.t4g.medium (neptune/main.tf:80-91; variables.tf:40-44).
- ECS maximums and `runtime_enabled`: 2b scope, for R-2.

Stateful resources: protection variables and cleanup path
- S3: `force_destroy = false` (data-lake/main.tf:32). Cleanup path is described in a comment (13-16), and the script itself comes in 2c.
- DynamoDB: `deletion_protection_enabled` variable (dynamodb/main.tf:54; variables.tf:14-18); PITR variable (75-77); both false in course.tfvars:13-14.
- Neptune: `deletion_protection` and `skip_final_snapshot` variables (neptune/main.tf:72-74; course.tfvars:17-18); backup retention 1 day (variables.tf:46-55).
- Secrets: `recovery_window_in_days` variable, 0 in course.tfvars:10.
- S3 Vectors: no protection variable, and no cleanup note in the module. The cleanup order belongs to area 13 (2b/2c), for R-2.

Skeleton contract
- Every module's versions.tf has only `required_providers { aws = { source = "hashicorp/aws" } }`, with no version and no `provider` block (for example vectors/versions.tf:3-9). A grep for `provider "`, `module "`, `random_` and `null_resource` in infra/terraform/modules found none. The only `source =` outside versions.tf is the S3 object file source (emr/main.tf:20), which is not a module source.

Hand-made IAM user and budget
- No `aws_iam_user` and no `aws_budgets_*` resource. The names appear only in README.md:25-27, as "not managed". Checked, none.

STATE.md and README.md claims
- STATE.md:8, "agents are denied reading it": confirmed. .claude/settings.json:109-110 and guard-dangerous.sh:179 deny reading tfstate files.
- STATE.md: the remote design (use_lockfile, versioning, separate bootstrap root, who may read state) and the local-state risk table are present, as decision 5 requires.
- README.md: manual budget, cost-allocation tags, encryption choice, S3 Vectors limits (PutVectors ≤ 500, topK ≤ 30) and the OpenSearch line are present. It does not mention the deployment role's remaining escalation risk; that falls under I-01.

Writer's claims checked against the ground truth
- DynamoDB: six tables, pk/sk string keys, no indexes, TTL only on QuerySession `expiresAt`. Matches TableSpecs.scala:14-16, 93 and 222-230.
- Vectors: five indexes, float32, dimension 1024, cosine, seven non-filterable keys each, read from index-spec-v1.json:14-52 through `index_spec`. Matches.
- EMR S3 actions: delete is needed (JobRunner.scala:107-114 replaceIncomplete delete). Spark checkpoint and staging live under the output root (Pipeline.scala:306-307). RunFs writes with `create(p, true)` (RunFs.scala:51-55). These match the writer's statement, except for the decision wording (I-05).
- "Delete only on curated/graph", "no delete on raw/artifacts/logs/rag/reports": confirmed (emr.tf:20, 51-66).
