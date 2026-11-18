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

Set these once per shell (they are plain values, no secret):

1. `BUILD=$(git log -1 --format=%H -- build.sbt project modules prompts docker config .scalafmt.conf .jvmopts .sbtopts); echo "$BUILD"`
2. `export AWS_PROFILE=YOUR-ADMIN-PROFILE` (the human's own profile; use the deploy profile where the Phase 14 prompt says so)

Then:

| # | Step | Command |
|---|---|---|
| 1 | check (in a fresh clone; keep the record in the main checkout) | `REPO="$(git rev-parse --show-toplevel)"; SHA="$(git rev-parse HEAD)"; CLEAN="/tmp/sc-clean-${SHA:0:12}"; git clone --no-local "$REPO" "$CLEAN" && cd "$CLEAN" && bash scripts/check.sh --record-dir "$REPO/evidence/checks"; echo "exit=$?"; cd "$REPO"` |
| 2 | package | `bash scripts/package.sh; echo "exit=$?"` |
| 3 | plan (runtime off) | `bash scripts/plan.sh; echo "exit=$?"` |
| 4 | sign | add the whole line `APPROVED-PLAN-SHA256: HEX64` (the printed `PLAN-SHA256`) to `docs/agent-runs/phase-NN/human-gate.md` or `approvals.md` and commit it (only `docs/agent-runs/**`, so the plan stays usable) |
| 5 | deploy (runtime off) | `bash scripts/deploy.sh --plan-sha256 HEX64; echo "exit=$?"`, then commit `releases/course/ATTEMPT_ID.json` |
| 6 | publish | `bash scripts/package.sh --publish; echo "exit=$?"` |
| 7 | upload input | `bash scripts/run-bounded-pipeline.sh --stage upload-input --source dblp --snapshot SNAPSHOT --file PATH-TO-SUBSET; echo "exit=$?"` |
| 8 | Hadoop | `bash scripts/run-bounded-pipeline.sh --stage hadoop --snapshot SNAPSHOT --dblp 's3://{data-bucket}/raw/dblp/SNAPSHOT/FILE'; echo "exit=$?"` |
| 9 | entity resolution (this machine) | `bash scripts/run-bounded-pipeline.sh --stage er --snapshot SNAPSHOT --hadoop-export 's3://{data-bucket}/curated/hadoop/SCOPE/j09-graph-export/run=HEX' --decision-time 2026-11-14T00:00:00Z --seed 20261114; echo "exit=$?"` (the J09 run is in the hadoop record, `run_ids.hadoop_j09_export_runs`) |
| 10 | Spark (graph) | `bash scripts/run-bounded-pipeline.sh --stage spark --snapshot SNAPSHOT --hadoop-export 's3://{data-bucket}/curated/hadoop/SCOPE/j09-graph-export/run=HEX' --identity-assignments 's3://{data-bucket}/curated/er/ATTEMPT_ID/run=HEX/decisions/identity_assignment' --out-root 's3://{data-bucket}/graph/runs'; echo "exit=$?"` |
| 11 | Spark (export of that graph run) | the same command plus `--export-source-run 's3://{data-bucket}/graph/runs/run=HEX32' --export-root 's3://{data-bucket}/graph/exports'` (the graph run is in the spark record, `run_ids.graph_runs_under_out_root`) |
| 12 | plan (runtime on, with the graph run) and sign | `bash scripts/plan.sh --runtime-enabled true --image-tag "$BUILD" --graph-run-id run:v1:HEX32; echo "exit=$?"`, then commit its `APPROVED-PLAN-SHA256:` line in `docs/agent-runs/phase-14/approvals.md` |
| 13 | deploy (runtime on) | `bash scripts/deploy.sh --plan-sha256 HEX64; echo "exit=$?"`, then commit the release record |
| 14 | load DynamoDB | `bash scripts/run-bounded-pipeline.sh --stage load --snapshot SNAPSHOT --loader dynamodb-load --export 's3://{data-bucket}/graph/exports/ATTEMPT_ID/run=HEX32'; echo "exit=$?"` |
| 15 | build the Neptune CSVs | `bash scripts/run-bounded-pipeline.sh --stage load --snapshot SNAPSHOT --loader neptune-csv-build --dataset-id scholarchain-graph --export 's3://{data-bucket}/graph/exports/ATTEMPT_ID/run=HEX32'; echo "exit=$?"` |
| 16 | start the Neptune bulk load | `bash scripts/run-bounded-pipeline.sh --stage load --snapshot SNAPSHOT --loader neptune-bulk-start --graph-run 's3://{data-bucket}/graph/dataset=scholarchain-graph/snapshot=SNAPSHOT/run=HEX'; echo "exit=$?"` (the graph run is in the csv-build record, `outputs.program_lines`) |
| 17 | wait for the bulk load | `bash scripts/run-bounded-pipeline.sh --stage load --snapshot SNAPSHOT --loader neptune-bulk-status --load-id LOAD-ID --wait-seconds 600; echo "exit=$?"` (once per load ID) |
| 18 | vector ingest | `bash scripts/run-bounded-pipeline.sh --stage load --snapshot SNAPSHOT --loader rag-ingest --export 's3://{data-bucket}/graph/exports/ATTEMPT_ID/run=HEX32'; echo "exit=$?"` |
| 19 | smoke test | `export SCHOLARCHAIN_SERVICE_API_KEY` (already set in your shell; never on the command line), then `bash scripts/smoke-test.sh --question 'QUESTION THE LOADED GRAPH ANSWERS'; echo "exit=$?"` |
| 20 | experiments | `bash scripts/run-experiment.sh --manifest experiments/manifests/ID.json --manifest-sha256 HEX64; echo "exit=$?"` (after `APPROVED-MANIFEST-SHA256: HEX64` is committed) |
| 21 | archive | `bash scripts/archive-evidence.sh; echo "exit=$?"` (S3 mode; `--local-only` before anything is deployed) |
| 22 | cleanup list, sign, remove | `bash scripts/cleanup.sh --evidence-archive evidence/archives/NAME.tar --list; echo "exit=$?"`, commit its `APPROVED-PLAN-SHA256:` line, then `bash scripts/cleanup.sh --evidence-archive evidence/archives/NAME.tar --remove --plan-sha256 HEX64; echo "exit=$?"` (first apply a signed `--cleanup-mode true --runtime-enabled false` plan, infra/terraform/README.md "Cleanup mode") |

The Phase 14 prompt may change the order of the runtime-on plan (steps 12 and 13) and the pipeline (steps 7 to 18). Uppercase words (`SNAPSHOT`, `HEX`, `HEX32`, `HEX64`, `ATTEMPT_ID`, `SCOPE`, `LOAD-ID`, `PATH-TO-SUBSET`, `NAME`) are values you copy from the previous step's printed lines or run record; replace them before you run the line.

Phase 13 (decision 13): only check.sh (fresh clone), package.sh (no `--publish`), plan.sh and `archive-evidence.sh --local-only` are run, by the human.
