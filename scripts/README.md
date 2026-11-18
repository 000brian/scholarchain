# ScholarChain scripts: reproduction guide

Owner: infra-engineer. This file is the reproduction guide until the root `README.md` links to it (documentation-engineer, Phase 17; planned deviation 12.5). The scripts are the only interface: there are no GitHub Actions (planned deviation 12.4). Each script's `--help` text is its full contract; this page summarizes them and gives the order of a full reproduction.

Status of every script: `static: shellcheck clean`, `runtime: untested`, `human execution required`. No agent runs any script.

## Prerequisites

| Tool | Version | Needed by |
|---|---|---|
| bash | 3.2 or later (macOS `/bin/bash` works) | all |
| git | any recent | all |
| jq | 1.6 or later (tested target 1.8.2) | all |
| GNU coreutils `timeout` (or `gtimeout`) | any | all (every cloud and long call has a time limit) |
| `shasum` or `sha256sum` | any | all |
| Java (Temurin) | 17 | check.sh, package.sh (sbt), run-bounded-pipeline.sh `--stage er` |
| sbt runner | the one in the repository's `project/build.properties` ledger | check.sh, package.sh |
| Terraform | exactly 1.17.1 | check.sh, plan.sh, deploy.sh, cleanup.sh; `terraform output` in the other cloud scripts |
| shellcheck | 0.11.0 | check.sh |
| python3 | 3.x | check.sh (dataset validation) |
| tar | any | check.sh, archive-evidence.sh, cleanup.sh |
| Docker with buildx (daemon running) | 29.x | package.sh |
| AWS CLI | v2 | every cloud script (deploy.sh and run-experiment.sh check it only after their refusals, so check.sh's probes need no AWS CLI) |
| curl | 7.55 or later | smoke-test.sh |

Network: check.sh runs `terraform init -backend=false` (Terraform registry) and sbt (Maven repositories on a first run); package.sh reads the base image index (`docker buildx imagetools inspect`). AWS credentials: the human's own administrator profile for the first apply and for cleanup.sh (I-08); the deploy profile otherwise. Every command runs from the repository root.

## The two identity rules (scripts/lib/identity.sh)

- Build commit: `git log -1 --format=%H -- build.sbt project modules prompts docker config .scalafmt.conf .jvmopts .sbtopts`. Image tags, artifact keys (`artifacts/BUILD_COMMIT/`) and the `--code-version` of every job use it. Commits that change only scripts, Terraform, tests or evidence keep it.
- Plan validity: plan.sh names a plan `PLAN_COMMIT-PLAN_ID.tfplan`. deploy.sh and `cleanup.sh --remove` find the plan by its sha256 and use it only on a clean tree, with `PLAN_COMMIT` equal to HEAD or an ancestor, and with no plan input changed since. The plan inputs are `SC_PLAN_INPUT_PATHS` in `scripts/lib/identity.sh`: `infra/terraform/` plus every repository file outside it that Terraform reads (`file()`, `templatefile()`, `filebase64()` and similar), today `modules/storage/src/main/resources/s3vectors/index-spec-v1.json` (read by `infra/terraform/environments/course/main.tf`); a new such read must be added to that list. The approval is a committed whole line `APPROVED-PLAN-SHA256: HEX64` in a `human-gate.md` or `approvals.md` under `docs/agent-runs/`.
- Dirty trees: package.sh, plan.sh (for a usable plan), deploy.sh, cleanup.sh, run-bounded-pipeline.sh and run-experiment.sh refuse a dirty tree; check.sh, smoke-test.sh and archive-evidence.sh record the dirty flag; rollback.sh keeps its Phase 12 rule.

## check.sh gate 4 (shellcheck) scope

Gate 4 runs `shellcheck -x -P SCRIPTDIR` on `scripts/*.sh`, `scripts/lib/*.sh`, `datasets/scripts/*.sh` and `infra/terraform/modules/emr/files/bootstrap.sh` only. The hooks and hook self-tests under `.claude/**` (owned by factory-engineer) are not shellchecked here; they are covered by gate 1 (`.claude/hooks/validate-agent-files.sh`) and gate 3 (`bash -n` on every hook and self-test, then both self-tests). The full gate list is in `scripts/check.sh --help`.

## The ten scripts

Exit codes everywhere: 0 ok, 2 usage, 3 missing prerequisite, 4 refused by a safety check, 5 a command failed, 6 a check failed, 7 timed out. Records under `evidence/**` are gitignored and never overwritten (a `NAME.started.json` first, then `NAME.json`).

| Script | Command line | Reads | Environment variables (names) | Human approval points | Writes | Cloud | Rollback behavior |
|---|---|---|---|---|---|---|---|
| check.sh | `bash scripts/check.sh [--fail-fast] [--record-dir PATH]` | the repository; Terraform registry (init) | none required; unsets `AWS_PROFILE`, `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_SESSION_TOKEN`, `SCHOLARCHAIN_SERVICE_API_KEY` and more for its probes | none | `evidence/checks/check-ATTEMPT_ID.json`; `.claude/.last-test-run` (sbt); `infra/terraform/environments/course/.terraform/` (init) | no | nothing to undo |
| package.sh | `bash scripts/package.sh [--allow-dirty]`; `bash scripts/package.sh --publish` | build inputs, `project/build.properties` (`image.java-runtime`); with `--publish`: Terraform outputs `ecr_repository_url`, `data_bucket_name`, `data_prefixes` | `AWS_PROFILE` (with `--publish`) | the human runs `--publish` (Phase 14) | `evidence/packages/BUILD_COMMIT/package-record.json`, `evidence/packages/BUILD_COMMIT/publish/ATTEMPT_ID.json`; local image `scholarchain-services:BUILD_COMMIT`; ECR tag `BUILD_COMMIT`; `s3://{data-bucket}/artifacts/BUILD_COMMIT/` | `--publish` only | tags and keys are immutable; a new build commit gives new ones; nothing is overwritten |
| plan.sh | `bash scripts/plan.sh [--runtime-enabled true\|false] [--image-tag BUILD_COMMIT] [--graph-run-id run:v1:HEX32] [--cleanup-mode true\|false]` | Terraform configuration and state; `sts get-caller-identity` | `AWS_PROFILE` | the human signs the printed sha256 (`APPROVED-PLAN-SHA256:` line) | `infra/terraform/environments/course/plans/PLAN_COMMIT-PLAN_ID.{tfplan,plan.txt,summary.txt,record.json}` (gitignored) | read only (plan) | never applies; nothing to undo |
| deploy.sh | `bash scripts/deploy.sh --plan-sha256 HEX64` | plan records; the approval line at HEAD; Terraform outputs; ECS and ECR reads | `AWS_PROFILE` | the committed `APPROVED-PLAN-SHA256: HEX64` line (checked); `/deploy` | `releases/course/ATTEMPT_ID.json` (tracked: the human commits it); `plans/...apply.txt` | yes (terraform apply of the approved plan) | rollback.sh points services back to the previous revision in the release record; infrastructure: fix forward with a new signed plan |
| smoke-test.sh | `bash scripts/smoke-test.sh --question 'TEXT' [--api-url https://HOST]` | Terraform output `api_url` | `SCHOLARCHAIN_SERVICE_API_KEY` (required; never printed, passed to curl on standard input) | none | `evidence/runs/ATTEMPT_ID-smoke-test.json` | yes (HTTPS calls to the API; one bounded query) | nothing to undo (one job and one idempotency record, both expiring) |
| run-bounded-pipeline.sh | `bash scripts/run-bounded-pipeline.sh --stage STAGE --snapshot ID ...` (stages upload-input, hadoop, er, spark, load; see `--help`) | Terraform outputs (`data_bucket_name`, `data_prefixes`, `state_machine_arn`, `ecs_cluster_name`, `ops_task_definition_family`, `ops_container`, `app_subnet_ids`, `ops_security_group_id`, `log_group_names`, `neptune_loader_role_arn`, `ecr_repository_url`, `vector_bucket_name`); `evidence/packages/BUILD_COMMIT/package-record.json` (er) | `AWS_PROFILE`; `TMPDIR` (er download folder) | the human approves the environment, the bounded inputs and the cost bound (`/run-pipeline`) | `evidence/runs/ATTEMPT_ID-STAGE.json`; S3 `raw/SOURCE/SNAPSHOT/FILE`, `curated/er/ATTEMPT_ID/`, `graph/.../ATTEMPT_ID/`; local `${TMPDIR:-/tmp}/scholarchain-er-ATTEMPT_ID/` (er; kept) | yes (Step Functions, EMR, ECS ops task, S3) | nothing is overwritten; a failed stage keeps its record and partial outputs under its own attempt prefix; run a new attempt |
| run-experiment.sh | `bash scripts/run-experiment.sh --manifest experiments/manifests/ID.json --manifest-sha256 HEX64` | the manifest (tracked, sha256), the approval line at HEAD, Terraform outputs, CloudWatch | `AWS_PROFILE`; sets `SCHOLARCHAIN_EXPERIMENT_RUN_DIR`, `SCHOLARCHAIN_EXPERIMENT_ATTEMPT_ID`, `SCHOLARCHAIN_EXPERIMENT_MANIFEST_ID` for its action | the committed `APPROVED-MANIFEST-SHA256: HEX64` line (checked) | `evidence/experiment-runs/ATTEMPT_ID/`, moved to `experiments/raw/MANIFEST_ID/ATTEMPT_ID/` (read-only; the human commits it) | yes (through its action) | none; remote work is bounded by Terraform limits; failed runs are kept |
| archive-evidence.sh | `bash scripts/archive-evidence.sh --local-only [--out-dir DIR]`; `bash scripts/archive-evidence.sh [--out-dir DIR] [--max-mb N] [--metrics-file PATH]` | repository evidence, plan records (masked), `evidence/**`; S3 `reports/` (S3 mode) | `AWS_PROFILE` (S3 mode) | none | `evidence/archives/ATTEMPT_ID/`, `.tar`, `.tar.sha256` | S3 mode only (reads) | nothing to undo |
| cleanup.sh | `bash scripts/cleanup.sh --evidence-archive PATH --list`; `bash scripts/cleanup.sh --evidence-archive PATH --remove --plan-sha256 HEX64` | the evidence archive (`archive_keys`), Terraform state and outputs, live AWS listings | `AWS_PROFILE` (the human's own administrator profile, I-08) | the committed `APPROVED-PLAN-SHA256:` line for the removal plan; a typed confirmation `REMOVE course FIRST12HEX` at the terminal | `plans/cleanup-PLAN_COMMIT-PLAN_ID.{tfplan,plan.txt,record.json,apply.txt}` | `--list`: reads and a plan; `--remove`: removes the environment | none: removal is final; the evidence archive, `reports/`, `docs/agent-runs/` and `releases/` stay; expected leftovers (task-definition revisions, I-15) are listed |
| rollback.sh | `bash scripts/rollback.sh --release releases/course/RECORD.json [--role ROLE]` | the release record; Terraform outputs; ECS | `AWS_PROFILE` | approval of a rollback beyond the recorded previous revision (human gate 3) | `releases/course/ATTEMPT_ID-rollback.json` (only after a verified rollback; never overwritten) | yes (ECS update-service) | it is the rollback; the next plan shows ECS drift; fix forward with a new signed plan |

`{data-bucket}` is the logical name of the data-lake bucket: records and printed lines never hold the real bucket name or the account ID, and the scripts resolve `s3://{data-bucket}/KEY` from `terraform output data_bucket_name`. The Terraform plan and apply output that plan.sh, deploy.sh and cleanup.sh show and save (`.plan.txt`, `.apply.txt`) is masked line by line (`<account>`, `<data-bucket>`, `<vector-bucket>`, including buckets an apply is creating); plan and removal-plan records keep the account only as `account_sha256` (sha256 of `scholarchain-account-v1:ACCOUNT_ID`) and `account_masked`, and deploy.sh and `cleanup.sh --remove` compare that fingerprint with the caller's. The unmasked plan exists only inside the binary `.tfplan` file.

## Full reproduction, in order (zsh, one line each, from the repository root)

This is the order Phase 14 session 3 runs (Phase 14 work order, decision 12; step IDs D0a to D20). Pipeline before the runtime-on plan: the plan needs the Spark graph run ID. A full reproduction from nothing starts with check.sh in a fresh clone and the first runtime-off apply (see "First apply" below); experiments (Phase 15) and cleanup come after D20.

Set these once per shell (plain values, no secret):

1. `BUILD=$(git log -1 --format=%H -- build.sbt project modules prompts docker config .scalafmt.conf .jvmopts .sbtopts); echo "$BUILD"`
2. `export AWS_PROFILE=YOUR-ADMIN-PROFILE` (Phase 14: the human's admin profile for every script, planned deviation 2)

Run each line as `COMMAND 2>&1 | tee /tmp/p14-DNN.log; echo "exit=${pipestatus[1]}"` to keep the log.

| Step | What | Command |
|---|---|---|
| 0 | check (full reproduction only; fresh clone, record kept in the main checkout) | `REPO="$(git rev-parse --show-toplevel)"; SHA="$(git rev-parse HEAD)"; CLEAN="/tmp/sc-clean-${SHA:0:12}"; git clone --no-local "$REPO" "$CLEAN" && cd "$CLEAN" && bash scripts/check.sh --record-dir "$REPO/evidence/checks"; echo "exit=$?"; cd "$REPO"` |
| D0a | runtime-off plan, sign, deploy | `bash scripts/plan.sh`; read `plans/*.summary.txt` (Phase 14 attempt 3: only `module.observability.aws_cloudwatch_dashboard.this` is created; nothing changed, replaced or deleted); add the whole line `APPROVED-PLAN-SHA256: HEX64` to `docs/agent-runs/phase-NN/approvals.md` and commit only `docs/agent-runs/**`; then `bash scripts/deploy.sh --plan-sha256 HEX64` and commit `releases/course/ATTEMPT_ID.json` (every service desired 0, running 0) |
| D0b | cost-allocation tags | Billing and Cost Management console, Cost allocation tags, user-defined: activate `project` and `environment` (may take up to 24 hours to be listed) |
| D1 | commit check | `git status --short` prints nothing; HEAD holds the last reviewed commit |
| D2 | package | `bash scripts/package.sh` (the build commit is `$BUILD`; four jars with sha256 in `evidence/packages/BUILD/package-record.json`) |
| D3 | cost bound | add the bound sentence to `approvals.md`, commit only `docs/agent-runs/**` |
| D4 | publish | `bash scripts/package.sh --publish` (image `BUILD` to ECR, jars to `s3://{data-bucket}/artifacts/BUILD/`) |
| D5a | check the inputs | `md5 -q 'LOCAL_DIR/dblp-2026-11-01.xml.gz'` must print the manifest's `snapshot_md5` (`datasets/dataset-manifest.yaml`); `shasum -a 256 'LOCAL_DIR/dblp.dtd'` must print the manifest's `dtd_sha256` (`2d864498f9eef9298662df11e3874171ff24cd4a21ab5974f1369c90acdb1ef6`) |
| D5b | upload the DTD | `bash scripts/run-bounded-pipeline.sh --stage upload-input --source dblp --snapshot dblp-2026-11-01 --file 'LOCAL_DIR/dblp.dtd'` (prints `INPUT-LOCATION: s3://{data-bucket}/raw/dblp/dblp-2026-11-01/dblp.dtd`) |
| D5c | upload the release | `bash scripts/run-bounded-pipeline.sh --stage upload-input --source dblp --snapshot dblp-2026-11-01 --max-upload-mb 1100 --file 'LOCAL_DIR/dblp-2026-11-01.xml.gz'` (the file is 1,064.4 MiB, above the 1024 MiB default) |
| D6 | Hadoop (EMR) | `bash scripts/run-bounded-pipeline.sh --stage hadoop --snapshot dblp-2026-11-01 --dblp 's3://{data-bucket}/raw/dblp/dblp-2026-11-01/dblp-2026-11-01.xml.gz' --hadoop-extra-arg '--dblp-dtd' --hadoop-extra-arg 's3://{data-bucket}/raw/dblp/dblp-2026-11-01/dblp.dtd'` (see "DBLP DTD" below); then `aws emr list-clusters --active --query 'length(Clusters)' --output text` prints `0` |
| D7 | entity resolution (this machine) | `bash scripts/run-bounded-pipeline.sh --stage er --snapshot dblp-2026-11-01 --hadoop-export 's3://{data-bucket}/curated/hadoop/SCOPE/j09-graph-export/run=HEX' --decision-time 2026-11-14T00:00:00Z --seed 20261114` (the J09 run is in the hadoop record, `run_ids.hadoop_j09_export_runs`) |
| D8 | Spark graph (EMR) | `bash scripts/run-bounded-pipeline.sh --stage spark --snapshot dblp-2026-11-01 --hadoop-export 's3://{data-bucket}/curated/hadoop/SCOPE/j09-graph-export/run=HEX' --identity-assignments 's3://{data-bucket}/curated/er/ATTEMPT_ID/run=HEX/decisions/identity_assignment' --out-root 's3://{data-bucket}/graph/runs'` (exactly one graph run in `run_ids.graph_runs_under_out_root`); the EMR check as in D6 |
| D9 | Spark export (EMR) | the D8 command plus `--export-source-run 's3://{data-bucket}/graph/runs/run=HEX32' --export-root 's3://{data-bucket}/graph/exports'` (the graph job is reused; the export run equals the graph run); the EMR check as in D6 |
| D10 | runtime-on plan and sign | `bash scripts/plan.sh --runtime-enabled true --image-tag "$BUILD" --graph-run-id run:v1:HEX32` (HEX32 = the SPARK graph run of D8/D9, never the Neptune CSV folder); no stateful delete or replace in the summary; sign in `approvals.md` as in D0a |
| D11 | runtime-on deploy | `bash scripts/deploy.sh --plan-sha256 HEX64`, then commit the release record. Rollback policy: see "First runtime-on deploy" below |
| D12 | readiness (information) | `/readyz` answers 200 on empty stores (it proves reachability and IAM, not loaded data) |
| D13a | load DynamoDB | `bash scripts/run-bounded-pipeline.sh --stage load --snapshot dblp-2026-11-01 --loader dynamodb-load --export 's3://{data-bucket}/graph/exports/ATTEMPT_ID/run=HEX32'` |
| D13b | build the Neptune CSVs | `bash scripts/run-bounded-pipeline.sh --stage load --snapshot dblp-2026-11-01 --loader neptune-csv-build --dataset-id scholarchain-graph --export 's3://{data-bucket}/graph/exports/ATTEMPT_ID/run=HEX32'` |
| D13c | start the Neptune bulk load | `bash scripts/run-bounded-pipeline.sh --stage load --snapshot dblp-2026-11-01 --loader neptune-bulk-start --graph-run 's3://{data-bucket}/graph/dataset=scholarchain-graph/snapshot=dblp-2026-11-01/run=HEX'` (the CSV folder from D13b's `outputs.program_lines`, not the Spark ID) |
| D13d, D13e | wait for each bulk load | `bash scripts/run-bounded-pipeline.sh --stage load --snapshot dblp-2026-11-01 --loader neptune-bulk-status --load-id LOAD-ID --wait-seconds 600`, repeated until `LOAD_COMPLETED` or a failure; nodes load ID first, then the edges load ID |
| D13f | vector ingest (after the human approves the estimate) | `bash scripts/run-bounded-pipeline.sh --stage load --snapshot dblp-2026-11-01 --loader rag-ingest --export 's3://{data-bucket}/graph/exports/ATTEMPT_ID/run=HEX32'` |
| D14 | smoke test, question A (spec gate) | `read -rs "SCHOLARCHAIN_SERVICE_API_KEY?Service API key: "; export SCHOLARCHAIN_SERVICE_API_KEY` then `bash scripts/smoke-test.sh --question 'Which paper of Wanli Ouyang had the most coauthors on one paper between 2015 and 2025?'` |
| D15 | smoke test, question B (Neptune path check) | `bash scripts/smoke-test.sh --question 'Find the shortest path between Tommaso Green and Yiannis Demiris in the coauthorship graph for 2015-2025.'` |
| D16 | live suites | the sbt commands in the Phase 14 storage and rag reports, each after the human's go |
| D17 | deployment report | deploy-engineer checks the records and the live state read-only |
| D18 | archive | `bash scripts/archive-evidence.sh` (S3 mode; it learns the account from the caller and runs the identifier check, I-25) |
| D19 | runtime stays on | `aws emr list-clusters --active --query 'length(Clusters)' --output text` prints `0`, and no `ops` task runs |
| D20 | costs | billing console (AWS), Anthropic and Voyage dashboards |
| later | experiments | `bash scripts/run-experiment.sh --manifest experiments/manifests/ID.json --manifest-sha256 HEX64` (after `APPROVED-MANIFEST-SHA256: HEX64` is committed) |
| later | cleanup list, sign, remove | `bash scripts/cleanup.sh --evidence-archive evidence/archives/NAME.tar --list`, commit its `APPROVED-PLAN-SHA256:` line, then `bash scripts/cleanup.sh --evidence-archive evidence/archives/NAME.tar --remove --plan-sha256 HEX64` (first apply a signed `--cleanup-mode true --runtime-enabled false` plan, infra/terraform/README.md "Cleanup mode") |

Uppercase words (`LOCAL_DIR`, `SCOPE`, `HEX`, `HEX32`, `HEX64`, `ATTEMPT_ID`, `BUILD`, `LOAD-ID`, `NAME`) are values you copy from the previous step's printed lines or run record; replace them before you run the line.

## DBLP DTD (Phase 14 decision 7)

The release declares no entities itself and writes non-ASCII characters as DTD named entities (`&eacute;`), so the Hadoop parse needs `dblp.dtd`. Without it the Hadoop stage still SUCCEEDS but rejects every record with a named entity as `undefined_entity`. The driver option `--dblp-dtd PATH` (modules/hadoop, `ScholarChainHadoop`) sets `scholarchain.dblp.dtdPath`; it goes through two `--hadoop-extra-arg` values, in this order: `'--dblp-dtd'`, then `'s3://{data-bucket}/raw/dblp/dblp-2026-11-01/dblp.dtd'`. Do not pass `-D scholarchain.dblp.dtdPath=...` that way: the state machine appends the extra arguments after the application options, and the driver refuses a trailing `-D`. The D6 check: the J01 counter `scholarchain.reject-reason:undefined_entity` is 0. The run record's `configuration.note` and `configuration.extra_args` name the options passed.

## First apply (new account)

On Nov 17 (Phase 14, attempt 1) the first runtime-off apply stopped at the Neptune cluster with `KMSKeyNotAccessibleFault` for the AWS managed key `aws/rds`: it was the first use of that key in the account. A few minutes later the key was enabled. The fix is to wait a few minutes, then make a NEW runtime-off plan (`bash scripts/plan.sh`; the old plan is stale after a partial apply), sign it and run deploy.sh with it. No release record is written for a failed apply.

## Entering the four key values (after the first apply)

Terraform creates only the four Secrets Manager containers (`infra/terraform/modules/identity/app-keys.tf`); no value is in Terraform, its state or a plan. Enter each value in the AWS console, never on a command line (a command line stays in the shell history and the process list): Secrets Manager, open the container whose tag `env-var` is the variable name (`SCHOLARCHAIN_VOYAGE_API_KEY`, `SCHOLARCHAIN_ANTHROPIC_API_KEY`, `SCHOLARCHAIN_SERVICE_API_KEY`, `SCHOLARCHAIN_SERVICE_READONLY_API_KEY`), "Retrieve secret value", "Set secret value", Plaintext, paste the value, Save. Tasks read the value when they start.

For smoke-test.sh, export the full-access service key in your shell without echoing it: `read -rs "SCHOLARCHAIN_SERVICE_API_KEY?Service API key: "; export SCHOLARCHAIN_SERVICE_API_KEY` (zsh). smoke-test.sh passes it to curl on standard input and never prints it.

## First runtime-on deploy (D11): rollback policy and desired count

- Rollback: the previous task-definition revisions are the runtime-off placeholders (image tag `placeholder-runtime-disabled`, never pushed), so rollback.sh has nothing good to go back to. On a failure of the first runtime-on deploy, fix forward (a script, code or Terraform repair, then a new package, plan or deploy as the repair needs); never roll back to a placeholder. From the second runtime-on deploy on, the normal policy applies: rollback.sh to the release record's previous revision; anything beyond that goes to the human.
- Desired count (I-28): Terraform ignores `desired_count` after a service exists, so turning the runtime on moves only the scaling targets' minimum of query, evidence and graph from 0 to 1. The Application Auto Scaling API reference (RegisterScalableTarget) says an update of an existing scalable target moves the current capacity into the new bounds, so the count should rise to 1 (untested here). deploy.sh exits 6 and prints every service's desired, running and pending counts when any of the three has desired 0 or running 0 after the wait; no release record is written.
- Remedy if the count stays at 0 (with the human's go): for each of the three services, `aws ecs update-service --cluster CLUSTER --service ROLE --desired-count 1 --query 'service.[serviceName,desiredCount]' --output text` (CLUSTER from `terraform output -raw ecs_cluster_name`). Drift: none in the next plan, because `desired_count` is in `ignore_changes` and 1 is inside the scaling target's bounds (min 1). Then make a new runtime-on plan from the same values (it should show no change), sign it, and run deploy.sh with it to verify the services and write the release record (deploy.sh refuses to re-run a plan it already applied).

## Smoke questions (Phase 14 rag-engineer report, from benchmark-v1)

- A (researcher, spec gate; benchmark q05): `Which paper of Wanli Ouyang had the most coauthors on one paper between 2015 and 2025?` A correct answer resolves Wanli Ouyang to one researcher, window 2015-2025, metric max_coauthors_on_one_paper = 19, the 2024 work "Lumina-Next : Making Lumina-T2X Stronger and Faster with Next-DiT." (team size 20), and at least one verified claim whose evidence ID `GET /v1/evidence/ID` returns. It needs the DynamoDB and Neptune loads, not the vectors.
- B (collaboration path, Neptune path template; benchmark q13): `Find the shortest path between Tommaso Green and Yiannis Demiris in the coauthorship graph for 2015-2025.` A correct answer resolves both researchers, has a path of exactly 2 hops through one shared coauthor and no direct edge, with a COAUTHORED_WITH edge fact per hop and at least one verified claim. A failure here is a finding, not a gate failure.

Phase 13 (decision 13): only check.sh (fresh clone), package.sh (no `--publish`), plan.sh and `archive-evidence.sh --local-only` were run, by the human.
