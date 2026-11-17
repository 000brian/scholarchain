PHASE 12 REVIEW R-2: review-report-b.md

The verdict is REQUEST_CHANGES. Two new major findings (I-07 and I-13) are open, and R-1's major finding I-01 is still in the code unchanged.

STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: /Users/brian/Developer/repos/scholarchain/docs/agent-runs/phase-12/prompt.md (sha256 5744ab73aa42946f1c556d1257a45031fd57868b75480de623879a9c73de7f56, as the orchestrator gave it; I cannot re-hash without Bash). Step R-2 covers the final state of infra/** (root, 11 modules, README.md, STATE.md, COSTS.md), config/course.json, scripts/** (all six scripts) and the Terraform section of .gitignore.
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools: Read, Grep, Glob)
TEST RESULTS: not applicable. There is no sbt in this phase, and the reviewer runs nothing.
VALIDATION RESULTS: I did not re-run fmt, validate or shellcheck, because I have no Bash. The writers' VALIDATION EVIDENCE is not confirmed by me. The orchestrator's decision-7 re-run is the check for it.
ASSUMPTIONS:
- The files did not change while I read them. 2c reports that nothing under infra/** or config/** changed after 2b.
- AWS behaviour I relied on without live verification: INACTIVE task definitions cannot be set on a service (the 2c writer quotes the ECS API reference, and this matches my knowledge); Application Auto Scaling acts asynchronously; deleting a role's inline policy revokes the permissions of live sessions. I had no MCP access.
RISKS:
- R-1's RK1 is still open. The Neptune security group has no egress rule, so the bulk loader's S3 access is unverified until Phase 14.
- R-1's RK2 and RK3 are still unverified (EMR ListBucket prefix condition, release labels, the Neptune engine version).
- 2b's R5 and R9 are unverified: command-runner.jar on emr-spark-8.1.0, and Application Auto Scaling lifting desired count to the new minimum. deploy.sh R9 detection (deploy.sh:421-425) fails loudly if the lift does not happen.
- The Hadoop step passes MainClass together with the S3 jar (sfn.tf:177-180). If the Phase 13 assembly jar declares a Main-Class in its manifest, `hadoop jar` ignores the MainClass argument and passes it as the first program argument, and ScholarChainHadoop.main then exits 2. Not verified (build.sbt sets no mainClass). This needs a check when the Phase 13 jar is built.
- Every price in COSTS.md is marked estimate (unverified). The human checks them before signing.
MANUAL ACTION REQUIRED: The human decides two things at the gate. (1) Accept or refuse the 2b deviation that routes /healthz and /readyz to query; my recommendation is accept, see the checklist. (2) Decide which profile runs cleanup.sh (I-08).
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Step 3 triage. Route I-07 and I-13 (major), together with R-1's still-open I-01 (major), to infra-engineer for the step-4 repair. Record I-08 to I-14 as minor. R-1's I-02 to I-06 are still present unchanged (minor).

VERDICT: REQUEST_CHANGES

Findings (new IDs start at I-07)

| ID | Severity | File:line | Concrete failure -> impact | Owner | Suggested fix |
|---|---|---|---|---|---|
| I-07 | major | infra/terraform/modules/runtime/main.tf:133-183 (`aws_ecs_task_definition.service`), 188-229 (`ops`); used by scripts/deploy.sh:459-468 and scripts/rollback.sh:239-242 | Neither task-definition resource sets `skip_destroy = true`. A Phase 14 deploy that changes the image tag replaces the resource: Terraform registers revision N+1 and deregisters revision N, which becomes INACTIVE. deploy.sh records N as `previous_task_definition`. Running `rollback.sh --release <that record>` then always stops at rollback.sh:241 with exit 4 ("N is INACTIVE; ECS cannot point a service at an INACTIVE revision"). Impact: the spec's rollback script can never roll back a Terraform deploy, and the rollback gate cannot be met. This is the 2c writer's F-2c-01, which I confirm. | infra-engineer | Add `skip_destroy = true` to both task-definition resources (provider argument; confirm in the 6.59.0 docs). Note in README that old revisions stay ACTIVE at no cost and that cleanup leaves them behind. |
| I-08 | minor | scripts/cleanup.sh:91-95; infra/terraform/modules/identity/deployment.tf:36-43, 114-118, 227-231 | Running cleanup.sh `--remove` under the deployment role fails at the first object removal (cleanup.sh:655). The role has no `s3:DeleteObjectVersion` or `DeleteObject` outside `artifacts/emr/*`, no `elasticmapreduce:*` and no `tag:GetResources`. This confirms the gap in the 2c writer's F-2c-02, but I reject "major" and reject the fix the usage text implies ("does not yet include every action"). The removal plan itself removes `aws_iam_role_policy.deployment` and `aws_iam_role.deployment`. Once the inline policy is gone, the session applying the plan loses its permissions and the apply stops partway. So adding the actions to the deployment role would not work, and it would widen the role. Impact: nothing is lost (the script fails before removing anything), but the documented path is unclear. | infra-engineer | State in the cleanup.sh usage text and in README "Cleanup mode" that cleanup (listing, emptying and the removal-plan apply) runs with the human's own administrator profile, like the first apply, and never under the deployment role. Remove the "does not yet include" wording. Do not widen the deployment role. |
| I-09 | minor | scripts/deploy.sh:378-386, 454-456 | Plan input: a signed plan that turns the runtime off (`--runtime-enabled false` after Phase 15). Terraform sets the scaling-target max to 0; the ECS desired_count is in `ignore_changes`, so Application Auto Scaling scales in asynchronously. deploy.sh does not wait in this case (services-stable runs only when the runtime is on). It reads desiredCount once and exits 6 if it is still 1 ("desired=1 but the runtime is off"). Impact: the apply happened, no release record is written, and the plan is spent, so a new plan is needed just to get a record. | infra-engineer | When the plan turns the runtime off, poll describe-services within a bounded deadline until desired = 0 and running = 0, then verify. |
| I-10 | minor | infra/terraform/modules/identity/deployment.tf:171-180 (`apigateway.amazonaws.com`) | Answer to R-1's follow-up: no resource passes a role to API Gateway. The HTTP API, VPC link, integration, route and stage with access logs (runtime/ingress.tf:111-177) take no role argument, and HTTP API access logging uses log-delivery permissions, not a passed role. The VPC link's service-linked role is already covered by `ops.apigateway.amazonaws.com` (deployment.tf:198). Impact: an unneeded PassRole target (any `scholarchain-course-*` role, including the deployment role itself, see I-01). | infra-engineer | Remove `apigateway.amazonaws.com` from `iam:PassedToService`. |
| I-11 | minor | infra/terraform/modules/identity/deployment.tf:97-112 | The comment says "no object removal outside artifacts/emr/", but `s3:Get*` and `s3:List*` on `arn:aws:s3:::scholarchain-course-*` also match object ARNs, because the `*` matches `/`. Concrete call: under the deployment role, `s3:GetObject` on `<data bucket>/rag/...` or `/curated/...` is allowed (and `GetObjectVersion` too). Impact: the deployment role can read all runtime data, which weakens the runtime/deployment separation the header claims. | infra-engineer | Add a Deny on `s3:GetObject*` with `NotResource` `arn:aws:s3:::scholarchain-course-*/artifacts/emr/*`, limited to object ARNs. Alternatively, record it as a deviation. |
| I-12 | minor | infra/terraform/README.md:61, 93; infra/terraform/modules/vectors/main.tf:8-13 | Stale or contradictory documentation. (a) "until step 2b passes the ARNs no bucket policy exists": the root now always passes three ARNs (environments/course/main.tf:136), so the policy always exists (count 1). (b) README step 6 says the removal plan is applied "through the approved deploy path (deploy-engineer, a signed removal plan)", but cleanup.sh:670-673 applies it itself and is human-invoked only. Impact: the documentation does not match the implementation. | infra-engineer | Update both texts. Name cleanup.sh `--remove` (human) as the removal path. |
| I-13 | major | scripts/cleanup.sh:322 (with 645-657) | The guard that keeps grading evidence is a substring match: `grep -F -q -- "${key}" archive-list`. Concrete input: the S3 objects `reports/phase15/summary.json` and `reports/phase15/summary.json.sha256`, with an archive that holds only `.../summary.json.sha256`. The check passes for `summary.json` because its key is a substring of the listed entry. `empty_data_bucket` then removes every version of `reports/phase15/summary.json`. Impact: irreversible loss of a grading-evidence object that is in no archive. That breaks "preserves reports/ and grading evidence" (decision 6, cleanup.sh). | infra-engineer | Match each key exactly at the end of an entry: strip a leading `./` and an optional single archive root folder, then use `grep -F -x` on the normalized list, or awk/jq equality. Also record the archive's count of reports/ entries. |
| I-14 | minor | infra/terraform/modules/identity/deployment.tf:40 vs scripts/deploy.sh:19, 23 | The deployment role's `max_session_duration = 3600` caps every session at 1 h, but deploy.sh allows a 5400 s apply plus a 1200 s stability wait. Concrete case: a Phase 14+ apply under the role that changes Neptune or recreates the ALB runs past 60 min, the credentials expire mid-apply and Terraform stops with a partly applied state (only the exit-5 message). | infra-engineer | Either raise the role's max session to 2 h (7200 s) and document `--duration-seconds`, or cap APPLY_TIMEOUT plus STABLE_TIMEOUT below the session length and say so in the usage text. |

R-1 findings still present unchanged (cited, not repeated): I-01 (major) at identity/deployment.tf:120-181, with no Deny on the role's own ARN and no permissions boundary. I-02 at deployment.tf:63 (`secretsmanager:*`) and 218-224. I-03 at deployment.tf:11-16, 49-75. I-04 at deployment.tf:114-118. I-05 at identity/emr.tf:19-20, 51-60. I-06 at emr/main.tf:37-40.

R-1 follow-ups
- RK4: resolved. environments/course/main.tf:136 passes `concat(module.runtime.vector_principal_arns, var.vectors_data_principal_arns)`. runtime/outputs.tf:67-70 returns the query, worker and ops role ARNs, a list of known length 3, so vectors/main.tf:56,81 `count` is 1 at plan time. The Deny uses `aws:PrincipalArn`, which equals the role ARN for assumed-role sessions.
- Role name prefixes: kept. execution `${name_prefix}-ecs-exec-` (runtime/iam.tf:77), task roles `${name_prefix}-${role}-` (iam.tf:130), Step Functions `${name_prefix}-pipeline-sfn-` (workflow/sfn.tf:330). All fall under `role/scholarchain-course-*` (deployment.tf:146, 169) and are at most 33 characters, under the 38-character name_prefix limit.
- PassRole to apigateway.amazonaws.com: not needed (I-10). PassRole targets that are needed: ecs-tasks (task definitions, runtime/main.tf:141-142, 194-195), rds (Neptune `iam_roles` loader role), states (sfn.tf:442).

Writer claims and proposals
- F-2c-01: confirmed as I-07 (major).
- F-2c-02: the gap is confirmed, the proposed fix is rejected, and the severity is minor (I-08).
- 2b deviation (/healthz and /readyz routed to query, runtime/ingress.tf:83): acceptable. Recommend the human accepts it. Both routes are Open on every role (Routes.scala:30-31). The readyz body holds only status, role and ok/fail per check name, with no error text, endpoint or table name (HealthService.scala:13, 69-70, 98-102). Probes are cached and single-flight, and the stage throttle (10 burst, 5 rps) bounds them.
- 2b claim that every config env-var name is valid: confirmed, see the checklist.

Checklist (decision 8)

Spec gate items
- No unexplained stateful destroy or replace: no plan exists yet (decision 9). The configuration creates only. plan.sh:338-355 lists stateful actions and flags any stateful delete or replace in an ATTENTION list. cleanup.sh:414-416 refuses a removal plan that contains anything other than deletions. Checked, none.
- IAM scoped to actual resources and actions: I-01 (major, from R-1), plus I-02, I-03, I-04, I-05, I-10, I-11 and I-14 (minor). The 2b runtime roles match the code: DynamoDB GetItem/PutItem/Query only (DynamoDbKeyValueStore.scala:54-120); per-role tables (RuntimeStores.scala:43-51, ReadServices.scala:37-93, ServiceServer.scala:148-157); S3 Get/Head/List on rag/ only for query and worker; S3 Vectors Query and Get; SQS per role (SqsWorkQueue.scala:49-91; Worker.scala:64, 76, 179). Nothing found that is over-granted or missing (runtime/iam.tf:28-299).
- Buckets and databases not public: data lake has public-access block and Deny-only policy (data-lake/main.tf:45-52, 122-164); Neptune in data subnets; vectors Deny-unless-principal (vectors/main.tf:55-85); SQS policies name role principals only, plus a TLS Deny (workflow/main.tf:70-124); SNS allows publishing only by CloudWatch of this account (observability/alarms.tf:20-55). Checked, none.
- Default capacity bounded: see "Bounded capacity" below.
- Secrets not in source or plan: secret containers only (identity/app-keys.tf:35-43). Tasks get secrets by ARN (runtime/main.tf:163-166, 213-216). A Grep for `aws_secretsmanager_secret_version` found nothing. Checked, none.
- Human signs the plan: plan.sh records the checksum (plan.sh:290, 360-376); deploy.sh checks it (deploy.sh:223-255). The signature itself is the human gate.
- Code reviewer approves: not yet (this verdict).

IAM rule
- Every `*` resource has a comment: execution `ecr:GetAuthorizationToken` (runtime/iam.tf:85-91); Step Functions RunJobFlow (sfn.tf:342-362); log delivery (sfn.tf:406-422); Deny-for-all-principals statements (workflow/main.tf:107-123, alarms.tf:38-54). Deployment-role `*` statements are covered by R-1.
- Tag conditions only where supported: `aws:RequestTag/project` and `aws:RequestTag/for-use-with-amazon-emr-managed-policies` on RunJobFlow (sfn.tf:351-361); RunJobFlow lists `aws:RequestTag`. No other tag condition is used.
- Required read actions present: `s3:ListBucket` with a prefix condition for 404 semantics (iam.tf:182-197, 229-243); `DescribeCluster` and `DescribeStep` (sfn.tf:371-376); `GetVectors` alongside `QueryVectors` (iam.tf:168). Checked, none missing.
- Runtime and deployment separated: separate roles, separate trust (ecs-tasks and states vs the human). Weakened by I-01, I-02 and I-11.
- PassRole scoped: the Step Functions role passes only the EMR service and instance roles (sfn.tf:386-390). The EMR service role passes only the instance role (R-1). The deployment role: I-01 and I-10.

No public access, ingress chain
- ALB is internal (runtime/ingress.tf:13) and accepts traffic only from the VPC-link security group (network/main.tf:275-282). Tasks have no public IP (runtime/main.tf:253). The listener default action is a fixed 404 (ingress.tf:61-70).
- API Gateway throttling (burst 10, rate 5) at ingress.tf:152-155. Access logs carry metadata only, with no body and no headers (ingress.tf:158-174). HTTP_PROXY passes headers through unchanged (ingress.tf:127-137).

No secret, literal account ID, literal bucket name, public IP or secret_version in source
- A Grep over environments/course/*.tf and *.tfvars, modules/**, *.md, scripts/*.sh and config/*.json for 12-digit numbers, dotted IPv4 and account-bearing ARNs found only 10.40.0.0/16, 0.0.0.0/0 in routes and egress, and the Neptune engine version 1.4.7.1. Bucket names use `bucket_prefix` or names computed at plan time. Scripts mask the account and bucket names in what they print (for example cleanup.sh:139-151); raw plan and apply output is shown unmasked by design (spec: never hide plan output). Checked, none.

Bounded capacity
- EMR: On-Demand only; managed scaling min 2, max 4; idle auto-termination 900 s; a vCPU precondition (emr/main.tf:26-41, 63-73). Passed to createCluster at sfn.tf:92-101. I-06 still applies.
- ECS: maxima validated to 1..4 (runtime/variables.tf:104-124). With `runtime_enabled = false`, desired 0 and scaling targets min 0 / max 0 (runtime/main.tf:242, autoscaling.tf:15-21). A cross-variable validation plus a precondition (environments/course/variables.tf:210-229, runtime/main.tf:123-128).
- DynamoDB on-demand and one Neptune instance: covered by R-1 and unchanged. API throttling 10/5. Pipeline TimeoutSeconds validated to 900..43200 (workflow/variables.tf:45-54).

Stateful resources: protection variables and cleanup path
- `cleanup_mode` turns protection off for DynamoDB and Neptune (environments/course/main.tf:18-19) and needs the runtime off (variables.tf:273-276). ALB deletion protection is off, ECR `force_delete = false`, data lake `force_destroy = false`. The cleanup order is in README:84-94 and cleanup.sh:64-82. See I-08, I-12 and I-13.

Skeleton contract
- One `provider` block (environments/course/providers.tf:4). `module` blocks only in the root (main.tf:23-208). No random_*, null_resource or registry/git sources in modules. Exact pins: Terraform `= 1.17.1`, aws `= 6.59.0` (versions.tf:6, 11). Checked, none.

Scripts
- Strict mode: `set -Eeuo pipefail` and `umask 077` in all six (for example plan.sh:8-9, cleanup.sh:9-10).
- No prompt except cleanup's confirmation: the only `read` is cleanup.sh:561 from /dev/tty with a 300 s limit; Terraform always runs with `-input=false`. Checked, none.
- Dirty-tree refusal: deploy.sh:208-218, run-bounded-pipeline.sh:237-247, cleanup.sh:278-288. plan.sh labels a dirty plan `-dirty`, and deploy.sh:244 refuses it.
- Checksum check: deploy.sh:248-254 checks the plan file and the record. cleanup.sh:384-401 does the same for the removal plan.
- Cleanup lists before removing: `list_targets` (cleanup.sh:408-479) runs before `confirm` and before any removal (main at 701-712). It names the API Gateway, VPC link, ALB, NAT, address and VPC (418-425).
- Status lines are honest: every usage text ends with "Status: static: shellcheck clean; runtime: untested; human execution required." The 2c report gives one `structure reviewed:` line per script. I found no claim that a script works.
- No secret printed: the smoke-test key goes to curl through stdin (smoke-test.sh:172-175) and is checked for presence only (138-140). No script echoes environment values.
- Not met: I-07 (rollback cannot work), I-09, I-13, I-14.

Cost
- COSTS.md:11-30 covers everything that bills per hour: the NAT, its public IPv4, the ALB, Neptune, secrets, dashboard, alarms, logs, ECR, S3, S3 Vectors, DynamoDB, SQS, API Gateway, Step Functions and SNS. Per use: Fargate, ops, EMR, emr-service-vpce, NAT data. It gives idle and on costs per day, days left, and the switch-off list, and says plainly that idle is not free (line 7). The Neptune trial terms are marked partly unconfirmed (lines 46-49). All prices are estimate (unverified). Checked, none.

Config: names only, and env names
- config/course.json holds no resource name, account ID, bucket, table, queue URL or endpoint. It holds key NAMES only for the four API keys (lines 62, 73, 94-95). Checked, none.
- All 17 `SCHOLARCHAIN_CFG_*` names in runtime/main.tf:38-58 equal `ConfigKey.envVarFor` (ConfigLoader.scala:37-41) of real keys (ConfigLoader.scala:96-237). For example `storage.dynamoDbTable` gives STORAGE_DYNAMO_DB_TABLE, `objectStore.semanticContextBucket` gives OBJECT_STORE_SEMANTIC_CONTEXT_BUCKET, `dynamoDb.researcherMetricCacheTable` gives DYNAMO_DB_RESEARCHER_METRIC_CACHE_TABLE, and `service.jobQueueUrl` gives SERVICE_JOB_QUEUE_URL. No unknown name, so ConfigLoader.scala:304-306 would not refuse start.
- The file and environment together satisfy every required key and every section's required keys. The values are inside ConfigValidator's ranges (ScholarChainConfig.scala:300-418). The six objectStore zones are distinct by prefix (ConfigLoader.scala:661-664). `syncDeadlineMillis` is 25000, at the 25000 limit. The objectStore prefixes equal the data-lake prefixes.

Hand-made IAM user and budget
- No `aws_iam_user` and no `aws_budgets_*` resource (Grep). The names appear only in documentation and in cleanup's "never touched" text (cleanup.sh:478). Checked, none.

State machine definition
- Uses createCluster.sync, addStep.sync and terminateCluster.sync only (sfn.tf:158, 171, 190, 199, 214, 229, 247, 265, 274). `KeepJobFlowAliveWhenNoSteps` is true (emr/main.tf:65) and there is no `Steps` in createCluster. Cluster IDs are held only through Assign (sfn.tf:164, 220).
- Every state after a successful create catches States.ALL, goes to a terminateCluster.sync cleanup with Retry 3 (30 s, backoff 2), then to FailPipeline with the original Error and Cause (sfn.tf:107-131, 184, 241, 259, 284-288). A failed terminate goes to ClusterCleanupFailed (292-296). README:77 documents the idle backstop after a stop or timeout.
- The code version reaches both job steps: Hadoop `pipeline -D scholarchain.code.version=<cv>` (sfn.tf:180). ToolRunner parses `-D` after main strips "pipeline" (ScholarChainHadoop.scala:45-46). Spark `--code-version <cv>` (sfn.tf:255; GraphAnalyticsJob.scala:38). CopySparkJobConfig needs none. The Spark `--config` is copied to local disk, because the job reads it with java.io.File (GraphAnalyticsJob.scala:40). TimeoutSeconds comes from a variable (sfn.tf:136).

SQS against config
- Job queue visibility is 150 s, more than jobLeaseMillis 120000 / 1000, enforced by a precondition (workflow/main.tf:15, 59-64). maxReceiveCount = jobMaxAttempts 3 + 2 = 5 (main.tf:17). The Worker uses one receive per attempt, releases with ChangeMessageVisibility 0 and acks at the terminal state (Worker.scala:104-105, 176-185). Job retention is 86400 = jobTtlSeconds. The DLQ redrive is allowed only from its own source queue (main.tf:134-142). Checked, none.

.gitignore Terraform section
- Lines 54-70: `.terraform/`, state files, `*.tfplan`, `*.tfvars` with `!*.tfvars.example`, overrides, `!infra/terraform/environments/course/course.tfvars` and `infra/terraform/environments/course/plans/`. local.auto.tfvars.example does not end in `.tfvars`, so it stays tracked, and local.auto.tfvars is ignored. Checked, none.

Relevant paths
- /Users/brian/Developer/repos/scholarchain/infra/terraform/modules/runtime/main.tf
- /Users/brian/Developer/repos/scholarchain/infra/terraform/modules/identity/deployment.tf
- /Users/brian/Developer/repos/scholarchain/scripts/cleanup.sh
- /Users/brian/Developer/repos/scholarchain/scripts/deploy.sh
- /Users/brian/Developer/repos/scholarchain/scripts/rollback.sh
- /Users/brian/Developer/repos/scholarchain/infra/terraform/README.md
- /Users/brian/Developer/repos/scholarchain/infra/terraform/modules/vectors/main.tf
