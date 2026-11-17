# Phase 12 step 2c report (infra-engineer)

STATUS: COMPLETE
OWNER: infra-engineer
WORK ORDER: docs/agent-runs/phase-12/prompt.md (sha256 5744ab73aa42946f1c556d1257a45031fd57868b75480de623879a9c73de7f56, re-checked with shasum at start), session 2, step 2c = decision 6 "Step 2c: the six scripts".
FILES CREATED (absolute):
- /Users/brian/Developer/repos/scholarchain/scripts/plan.sh
- /Users/brian/Developer/repos/scholarchain/scripts/deploy.sh
- /Users/brian/Developer/repos/scholarchain/scripts/rollback.sh
- /Users/brian/Developer/repos/scholarchain/scripts/smoke-test.sh
- /Users/brian/Developer/repos/scholarchain/scripts/run-bounded-pipeline.sh
- /Users/brian/Developer/repos/scholarchain/scripts/cleanup.sh
FILES MODIFIED: none. Nothing under infra/**, config/** or releases/** was created or edited.
COMMANDS RUN:
- `shasum -a 256 docs/agent-runs/phase-12/prompt.md` -> matches the work order
- `shellcheck scripts/<each>.sh` after each script, then the exact six-path command below -> exit 0 every time
- `terraform fmt -recursive -check infra/terraform` -> exit 0; `terraform -chdir=infra/terraform/environments/course validate -no-color` -> Success
- read-only: ls, wc, head, grep, sed -n (reading inputs), `git status --short`, `date`
- No script was run, not even with --help (standing rule).
- MCP aws-docs: search_documentation x5, read_documentation x6 (AWS CLI reference: stepfunctions start-execution, describe-execution; ecs wait services-stable; emr list-clusters; ECS API DeregisterTaskDefinition; S3 Vectors delete search results). aws-api MCP: not connected (as the work order says); not used. terraform MCP: not needed (no HCL written).
TEST RESULTS: none (no sbt in this phase; scripts are never run by an agent).
VALIDATION RESULTS: shellcheck exit 0 (clean, all six); fmt check exit 0; validate Success. See VALIDATION EVIDENCE.
ASSUMPTIONS:
- A1. The human's machine has bash (3.2 compatible code: no associative arrays, no mapfile, empty-array-safe expansions), AWS CLI v2, jq 1.6+, curl 7.55+ (header from stdin), and GNU `timeout` or `gtimeout`. Every script refuses with exit 3 if a tool is missing; on macOS `gtimeout` comes from coreutils.
- A2. The scripts are run as `bash scripts/<name>.sh` (decision 9 says so). The Write tool created them with mode 644; I may not chmod. If the human wants `./scripts/x.sh`, `chmod +x` is a manual step.
- A3. Archive format of Phase 13's `archive-evidence.sh` is unknown: cleanup.sh accepts anything `tar -tf` can list (tar, tar.gz; bsdtar also zip) and checks by substring that every `reports/` object key of the data lake appears in the listing.
- A4. `emr-service-vpce` naming is `unverified` (no AWS doc page found). cleanup.sh therefore targets every Interface endpoint in the environment's VPC; Terraform creates only the two Gateway endpoints (S3, DynamoDB), so any Interface endpoint there is EMR-made (2a RISK R2).
- A5. A plan made from a dirty tree (as decision 9's first plan.sh run will be, before the phase commit) is written as `<commit>-dirty.tfplan` and recorded `tree_clean: false`: fine for review and signing a review, but deploy.sh accepts only a clean-tree plan. Phase 14's apply therefore needs a plan.sh run on the committed tree, and its checksum is the one to sign for the apply.
RISKS:
- R1. rollback.sh only works while the previous task-definition revision is ACTIVE. Terraform deregisters replaced revisions (no `skip_destroy` in modules/runtime/main.tf), so today a rollback after a Terraform deploy will be refused (exit 4). Finding F-2c-01 below.
- R2. The deployment role (identity/deployment.tf) cannot do everything cleanup.sh does: it has no `s3:DeleteObject`/`s3:DeleteObjectVersion` outside `artifacts/emr/*`, no `elasticmapreduce:*` (list/describe/terminate clusters), no `tag:GetResources`. run-bounded-pipeline.sh needs only `states:*` (present). Finding F-2c-02.
- R3. The EMR-created interface endpoint may come with an EMR-created security group; if one is left in the VPC, the VPC removal in the plan apply fails loudly (exit 5) and `--list` shows the rest. Unverified.
- R4. 2b RISK R9 is now checked: deploy.sh fails with exit 6 (evidence printed) if, with the runtime on, query, evidence or graph stays at desired 0 after the apply.
- R5. The raw `terraform plan`/`apply` output is printed unchanged (spec: never hide plan output) and kept under the gitignored `plans/` folder; it can contain the account ID. Every other line the scripts print masks the account ID and bucket names.
- R6. Timeouts: each terraform call and each AWS call runs under `timeout --kill-after=30`; AWS calls also use `--cli-connect-timeout 10 --cli-read-timeout 60`. An apply cut by its 5400 s timeout leaves a partly applied state (Terraform stops on SIGTERM and saves state); the script says so and writes no release record.
MANUAL ACTION REQUIRED (human):
- Run the scripts only as described in their usage text; agents never run them. Optional `chmod +x scripts/*.sh`.
- Install GNU coreutils if `timeout`/`gtimeout` is missing.
- Export `SCHOLARCHAIN_SERVICE_API_KEY` in your own shell before smoke-test.sh.
- Phase 14: run plan.sh on the committed, clean tree and sign that checksum for the apply (A5).
- Decide the cleanup profile (F-2c-02) before Phase 17.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: R-2 (code-reviewer over infra/**, config/**, scripts/**, COSTS.md), then triage with F-2c-01 and F-2c-02.

## Findings for the repair round (infra/** not edited in 2c; both in infra-engineer territory)

- F-2c-01 (proposed major; rollback): `infra/terraform/modules/runtime/main.tf` task definitions (`aws_ecs_task_definition` at about lines 136 and 189) have no `skip_destroy = true`. When Terraform registers a new revision it deregisters the old one, which becomes INACTIVE. ECS API Reference, DeregisterTaskDefinition (read 2026-11-17 with aws-docs, https://docs.aws.amazon.com/AmazonECS/latest/APIReference/API_DeregisterTaskDefinition.html): "you can't update an existing service to reference an INACTIVE task definition". Repair: `skip_destroy = true` on the five task definitions (argument from provider knowledge; confirm in the provider docs during the repair). rollback.sh already checks the status and refuses (exit 4) on an INACTIVE revision.
- F-2c-02 (proposed major or human decision; cleanup permissions): `infra/terraform/modules/identity/deployment.tf` statement `ProjectBootstrapObjects` allows object removal only under `artifacts/emr/*`, and no statement grants `elasticmapreduce:ListClusters`, `DescribeCluster`, `TerminateJobFlows` or `tag:GetResources`. cleanup.sh `--remove` needs `s3:ListBucketVersions` (covered by `s3:List*`), `s3:DeleteObject` and `s3:DeleteObjectVersion` on the data-lake bucket, the three EMR actions on this environment's clusters, `ec2:DeleteVpcEndpoints` (covered by `ec2:*`), `ecr:BatchDeleteImage` (covered by `ecr:*`) and `tag:GetResources`. Either add a separate cleanup statement (or role) to the deployment role, or the human runs cleanup with an administrator profile. Not changed here.

## Per-script sections

Common to all six (spec "Script requirements" and decision 6 "Common"):
- `set -Eeuo pipefail`, `umask 077`; an ERR trap prints `ERROR: <script> step=<step> line=<n>` and exits 5; an EXIT trap removes the private temp folder and prints `RESULT: <script> ok` or `RESULT: <script> failed exit=<n> step=<step>`.
- Greppable markers: `STEP: <script> <step>`, plus `INFO:`, `WARN:`, `ERROR:`, `EVIDENCE:`, `VERIFY:`, `SMOKE:`, `TARGET:`, `POLL:`, `RESULT-*:`, `PLAN-SHA256:`, `RELEASE-RECORD:`, `CLEANUP-PLAN-SHA256:`.
- Explicit exit codes, the same table in every usage text: 0 ok, 2 usage, 3 missing prerequisite, 4 refused by a safety check, 5 terraform/AWS failure, 6 verification failed (evidence printed), 7 timed out.
- Timeouts on every cloud call (`timeout`/`gtimeout --kill-after=30 <s>`, AWS CLI connect/read timeouts, curl `--connect-timeout`/`--max-time`); no unbounded loop (every poll loop has a deadline or a round limit).
- Terraform always called as `terraform -chdir=infra/terraform/environments/course ...` with `-input=false` on init, plan and apply (`output` and `show` take no input).
- No prompt except cleanup.sh's one typed confirmation. No secret printed: AWS error text is captured and printed with the account ID (and bucket names, where known) masked; the API key never appears on a command line.
- Each usage text states the script's contract and ends with "Status: static: shellcheck clean; runtime: untested; human execution required."

### plan.sh
- static: shellcheck clean
- structure reviewed: terraform `init -input=false -lockfile=readonly`, `plan -input=false -lock-timeout -detailed-exitcode -var-file=course.tfvars -out=plans/...`, `show -json` of the saved plan and the jq paths `.resource_changes[].change.actions`, `.variables.aws_region.value` (from Terraform's JSON output format, provider knowledge; not re-read in docs this step); `aws sts get-caller-identity`
- runtime: untested
- human execution required
- Contract: init (local backend) + plan with `-var-file=course.tfvars` (and optional `--runtime-enabled`, `--image-tag`, `--graph-run-id`, `--cleanup-mode`, format-checked). Writes only `infra/terraform/environments/course/plans/<commit>[-dirty].{tfplan,plan.txt,summary.txt,record.json}` (gitignored by step 0). Full plan output shown via `tee`. Record: plan sha256, commit, tree clean, unmasked account (gitignored file only), Region, overrides. Printed summary: masked account, create/update/delete/replace/no-op counts, Terraform-style add/change/destroy, IAM and resource-policy changes, network-access changes, stateful-resource actions, and an `ATTENTION stateful delete or replace` list. Refuses a Region other than us-east-1. Never applies.

### deploy.sh
- static: shellcheck clean
- structure reviewed: `git status --porcelain`; terraform `show -json`, `output -json`, `apply -input=false <saved plan>`; AWS CLI `ecr describe-images --image-ids imageTag=`, `ecs describe-services`, `ecs describe-task-definition`, `ecs list-tasks --desired-status RUNNING`, `ecs describe-tasks`, `ecs wait services-stable` (read in the CLI reference: polls every 15 s, exits 255 after 40 checks; wrapped in a 1200 s timeout)
- runtime: untested
- human execution required
- Contract: refuses a dirty tree; `--plan-sha256` must equal the sha256 of `plans/<HEAD>.tfplan` AND the record's checksum; the record must be a clean-tree record of HEAD, environment course, us-east-1, and the caller account must equal the recorded one. If the plan turns the runtime on, its `image_tag` must be a 40-hex git SHA present in ECR (images tagged by git SHA). Applies only that saved file. Verifies the deployed revision per service (service revision = latest ACTIVE revision of its family; image tag = plan's tag; when on: services stable, every running task on that revision with the ECR digest, ALB services at desired >= 1). Writes `releases/course/<UTC>-<short commit>.json` (noclobber check): commit, plan checksum, Region, image repository/tag/digest, cluster name, per service `task_definition` and `previous_task_definition` as `family:revision` (no ARN, no account ID). The usage text says the human's signature in human-gate.md is checked by deploy-engineer before the run; the script proves only that the file is the reviewed one.

### rollback.sh
- static: shellcheck clean
- structure reviewed: AWS CLI `ecs describe-services`, `ecs describe-task-definition` (status ACTIVE/INACTIVE), `ecs update-service --task-definition family:revision`, `ecs wait services-stable`, `ecs list-tasks`, `ecs describe-tasks`; the INACTIVE restriction read in the ECS API reference (DeregisterTaskDefinition)
- runtime: untested
- human execution required
- Contract: `--release <record> [--role ROLE]...`. Refuses unless each selected service currently runs the record's `task_definition`, the `previous_task_definition` is of the same family and ACTIVE. Updates only the ECS services, waits stable, verifies service and running tasks, writes `releases/course/<UTC>-rollback.json`. Usage text: never touches stateful resources, data or Terraform state; not an infrastructure rollback; the next plan shows drift, which is expected.

### smoke-test.sh
- static: shellcheck clean
- structure reviewed: routes and bodies read in `modules/services/**` (`Routes.scala`: `GET /healthz`, `GET /readyz` open; `POST /v1/query` full key; `GET /v1/evidence/{id}` any key; header `X-Api-Key` from `HttpAdapter.scala`; `HealthBody(status "ok")`, `ReadinessBody(status "ready", 503 otherwise)`, `CompletedResult.queryId`, `links.evidence`, `EvidenceView.evidenceId` in `Models.scala`; body fields `question`, `mode`, `output.includeEvidence` in `QueryRequestParser.scala`); curl options `--write-out '%{http_code}'`, `--header @-`
- runtime: untested
- human execution required
- Contract: `--question TEXT [--api-url URL]`; API URL from Terraform output `api_url` (must be https). Checks health, readiness, one bounded sync query (curl stops at 35 s; API Gateway ~30 s; `syncDeadlineMillis` 25 s), and one evidence fetch from the answer's first evidence link (no cited evidence = fail). Refuses (exit 3) when `SCHOLARCHAIN_SERVICE_API_KEY` is unset; the key goes to curl on stdin and is never printed; bodies stay in a private temp folder. Phase 13 completes it (for example a 401 check without a key, the async job path, graph routes).

### run-bounded-pipeline.sh
- static: shellcheck clean
- structure reviewed: execution input shape read from `infra/terraform/modules/workflow/sfn.tf` header and its JSONata (`codeVersion`; `hadoop.{jar,output,dblp,dblpSnapshot,extraArgs?}`; `spark.{jar,config,hadoopExport,identityAssignments,outRoot}`; each half optional); AWS CLI `stepfunctions start-execution --state-machine-arn --name --input` and `describe-execution` (status, error, cause, output, stopDate) read in the CLI reference
- runtime: untested
- human execution required
- Contract: refuses a dirty tree (code version = HEAD commit, passed as `codeVersion` to every Hadoop and Spark step). Reads `state_machine_arn` and `data_bucket_name` outputs. Every `s3://` argument must be inside this environment's data-lake bucket under its prefix (`artifacts/` jars and config, `raw/` DBLP, `curated/` Hadoop output, export and identity assignments, `graph/` Spark output). Starts ONE execution with a unique name, polls every 60 s up to `--wait-timeout` (default 15000 s, max 44000 s), prints status/error/cause/output masked. Its own timeout leaves the execution running (exit 7, said in usage); the state machine's TimeoutSeconds and the EMR idle policy bound it. Phase 13 completes it.

### cleanup.sh
- static: shellcheck clean
- structure reviewed: terraform `plan -destroy -input=false -var-file=course.tfvars -var=cleanup_mode=true -var=runtime_enabled=false -out=...`, `show -json`, `apply <saved plan>`; AWS CLI `emr list-clusters --active` (CLI reference read), `emr describe-cluster`, `emr terminate-clusters`, `emr wait cluster-terminated`, `ec2 describe-vpc-endpoints` (filters `vpc-id`, `vpc-endpoint-type`), `ec2 delete-vpc-endpoints`, `ecr list-images`, `ecr batch-delete-image` (100 per call), `s3api list-objects-v2`, `s3api list-object-versions --max-items 1000`, `s3api delete-objects` (1000 per call, Quiet), `s3vectors list-indexes`, `stepfunctions list-executions --status-filter RUNNING`, `dynamodb describe-table` (`DeletionProtectionEnabled`), `neptune describe-db-clusters` (`DeletionProtection`), `resourcegroupstaggingapi get-resources --tag-filters` (option and field names from CLI/API knowledge except the pages listed under COMMANDS RUN)
- runtime: untested
- human execution required
- Contract: both modes refuse without an existing, non-empty, tar-listable evidence archive that holds every `reports/` object of the data lake, and refuse a dirty tree. `--list` changes nothing in AWS: makes and shows the removal plan, lists every target (Terraform removal list; load balancers and networking by name: API Gateway HTTP API, VPC link, internal ALB, NAT gateway and its address, VPC; outside Terraform: running EMR clusters of this environment, every Interface endpoint in the VPC = `emr-service-vpce`, ECR image count, data-lake object versions, vector indexes; everything tagged project/environment; what is never touched), records the removal plan checksum. `--remove --plan-sha256` checks the checksum, record and account, lists every target again, refuses unless the runtime is off, no execution is running, and DynamoDB/Neptune protection is off; then ONE typed confirmation `REMOVE course <12 hex>` from /dev/tty (300 s); then in order: end EMR clusters (wait), remove interface endpoints (wait), delete ECR images, delete every object version and delete marker in the data-lake bucket, apply the saved removal plan, list leftovers by tag (exit 6 if any). Refuses a removal plan containing anything but removals. Preserves the repository (`reports/`, `docs/agent-runs/`, `releases/`), the archive, the hand-made IAM user and budget.

## How each spec "Script requirement" is met

| Requirement | Where |
|---|---|
| bash strict mode | all six: `set -Eeuo pipefail` |
| No interactive prompts inside agent-run scripts | none prompts except cleanup.sh's typed confirmation; cleanup is explicit human invocation only (usage text, first line) |
| Refuse dirty source trees before deployment | deploy.sh `check_clean_tree` (exit 4); also run-bounded-pipeline.sh and cleanup.sh; plan.sh records dirty plans as `-dirty`, which deploy.sh refuses |
| Tag images by Git SHA | deploy.sh refuses a runtime-on plan whose `image_tag` is not a 40-hex SHA present in ECR; ECR tags are immutable (2b); plan.sh validates `--image-tag`. Building and pushing the image is Phase 13's package script (not one of these six) |
| Print greppable step markers | `STEP: <script> <step>` and the markers listed above |
| Verify the deployed revision | deploy.sh `verify_deployment` (revision, image tag, running-task digest, desired count); rollback.sh `verify-revision` |
| Fail loudly with evidence | `ERROR:` with step and exit code, `EVIDENCE:` lines, `RESULT: failed exit=<n> step=<s>`, AWS error text masked and printed |
| Never hide terraform plan output | plan.sh, deploy.sh and cleanup.sh `tee` the full plan/apply output to the terminal and to `plans/` |
| Cleanup requires explicit human invocation and lists affected resources before deletion | cleanup.sh: archive + checksum + typed confirmation; `list_targets` runs before any removal in both modes |

## INTERFACE HANDOFF

- Files changed: the six scripts under `/Users/brian/Developer/repos/scholarchain/scripts/` (created; mode 644; run with `bash`).
- Public interfaces (command lines):
  - `scripts/plan.sh [--runtime-enabled true|false] [--image-tag SHA40] [--graph-run-id run:v1:HEX32] [--cleanup-mode true|false]`
  - `scripts/deploy.sh --plan-sha256 HEX64`
  - `scripts/rollback.sh --release releases/course/<record>.json [--role query|evidence|graph|worker]...`
  - `scripts/smoke-test.sh --question TEXT [--api-url https://...]` (env `SCHOLARCHAIN_SERVICE_API_KEY`)
  - `scripts/run-bounded-pipeline.sh [--hadoop-jar --hadoop-output --dblp --dblp-snapshot [--hadoop-extra-arg]...] [--spark-jar --spark-config --hadoop-export --identity-assignments --out-root] [--wait-timeout S]`
  - `scripts/cleanup.sh --evidence-archive PATH (--list | --remove --plan-sha256 HEX64)`
- Files the scripts write: `infra/terraform/environments/course/plans/<commit>[-dirty].{tfplan,plan.txt,summary.txt,record.json}`, `<commit>.apply.txt`, `cleanup-<commit>.{tfplan,plan.txt,record.json,apply.txt}` (all gitignored); `releases/course/<UTC>-<short commit>.json` (deploy) and `releases/course/<UTC>-rollback.json` (rollback), JSON `kind` `scholarchain-release` / `scholarchain-rollback`, version 1, no ARN or account ID.
- Root outputs read: `api_url`, `api_id`, `alb_dns_name`, `ecr_repository_name`, `ecs_cluster_name`, `ecs_service_names`, `runtime_enabled`, `state_machine_arn`, `data_bucket_name`, `vector_bucket_name`, `dynamodb_table_names`, `neptune_endpoint`, `vpc_id`, `nat_gateway_id`. None missing; no output needs to be added. ECS container name `app` used for digest checks.
- Root variables passed: `runtime_enabled`, `image_tag`, `graph_run_id`, `cleanup_mode` (plan.sh options; cleanup.sh's removal plan passes `cleanup_mode=true runtime_enabled=false`).
- Environment variables by name: `SCHOLARCHAIN_SERVICE_API_KEY` (smoke-test.sh; presence only), `AWS_PROFILE` (name printed, optional), `TMPDIR` (optional).
- Limits: AWS call 90-120 s; init 600 s; plan 1800 s; apply 5400 s; services-stable 1200 s; EMR wait 2400 s; endpoint wait 900 s; confirmation 300 s; smoke requests 15 s / query 35 s; pipeline wait default 15000 s, max 44000 s; S3 removal at most 20000 rounds of 1000.
- Thread-safety: not applicable (sequential scripts; one temp folder per run).

## Plan summary and checksum

No plan in this step (decision 9: the human runs plan.sh). Checksum: none. Step 2c adds no Terraform resource.

## IAM changes

None in 2c. Needed for the scripts: see F-2c-02 (cleanup actions missing from the deployment role).

## Stateful-resource actions

None in 2c. cleanup.sh `--remove` (human only, after archive, signed removal-plan checksum and typed confirmation) empties ECR and the data-lake bucket and applies a removal plan for every stateful resource.

## Cost estimate

No new billing resource. run-bounded-pipeline.sh starts EMR clusters per use (COSTS.md: about $1.50 per run, estimate (unverified)); cleanup.sh `--list` and smoke-test.sh cost only API calls.

## Required environment variables by name

`SCHOLARCHAIN_SERVICE_API_KEY` (human shell, smoke-test.sh). No other script reads a secret.

## Proposed later

- A shared `scripts/lib/common.sh` for the helper functions duplicated in the six scripts (kept self-contained here so each script is reviewed and shellchecked alone).
- smoke-test.sh: a 401 check without the key, the async job path, a graph route (Phase 13 completes it).
- cleanup.sh: removal by tag when the local state is lost (today it refuses with exit 3 and points to the README).

VALIDATION EVIDENCE
- fmt: terraform fmt -recursive -check infra/terraform -> 0, no files listed
- validate: terraform -chdir=infra/terraform/environments/course validate -no-color -> "Success! The configuration is valid."
- shellcheck: shellcheck scripts/plan.sh scripts/deploy.sh scripts/rollback.sh scripts/smoke-test.sh scripts/run-bounded-pipeline.sh scripts/cleanup.sh -> 0; clean
- jq: not run (no JSON file written or changed in step 2c)
- finished: 2026-11-17 02:58:36 CST
- files changed after these runs: no
