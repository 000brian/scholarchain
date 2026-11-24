# ScholarChain runbook

This runbook is the operator's guide: deployment (plan, sign, deploy), smoke tests, the bounded pipeline, experiments, common failures, rollback, incident escalation, evidence archiving, cleanup and the AWS Emulator. **The full contract of every script (all options, exit codes, records, environment-variable names) is its `--help` text, summarized in [`scripts/README.md`](../scripts/README.md)**; this page gives the order, the commands that were actually used, and what went wrong in the recorded runs.

**No live system exists.** The course environment was removed on Nov 21, 2026 (Phase 15, steps D24 to D28; [`docs/final-state.md`](final-state.md)). Every command that needs the environment is marked `not replayable after teardown`, with the archived evidence of its last real run. The scripts and the Terraform are unchanged since that run except where this page says so, so the same commands would work against a new environment (a new account starts at [2.1](#21-first-apply-on-a-new-account)).

**Rules for every command:**

- One zsh line, run from the repository root. Uppercase words (`HEX64`, `ATTEMPT_ID`, `BUILD`, `RUN_HEX`, `NAME`, `STAMP`, `PATH_TO_ARCHIVE`) are values you copy from the previous step's printed lines or records; replace them before running the line.
- Logical names only: `{data-bucket}` is the data-lake bucket and `{api-url}` the API address; the scripts resolve them from Terraform outputs and never print the real values. Account IDs, bucket names and hosts never go into a command you paste into a report.
- Keep a log of every long run by wrapping the command in this pattern (a template around another command, not a command of its own; each use carries the replay line of the command it wraps): `caffeinate -i COMMAND 2>&1 | tee /tmp/LOG-NAME.log; echo "exit=${pipestatus[1]}" | tee -a /tmp/LOG-NAME.log`. A log without its `exit=` line means the command did not finish.
- No agent runs any of these commands. The human runs every script (Phases 13 to 17); agents write the scripts and read the records.

**Replay lines.** Every command carries exactly one line, as the Phase 17 work order requires ("How the run ends (results)", replay lines): `replayed earlier (phase NN, EVIDENCE PATH)`, `not replayable after teardown (archived evidence: PATH)`, `not replayed in phase 17 (REASON)`, or `replayed in phase 17 (ENN, exit CODE)` for the commands the human replayed in Phase 17 steps E3 to E6, E10 and E12 (filled in with the observed result at steps E11 and E12). The evidence of every `replayed in phase 17` line is the named step's row in the human's runbook log, `docs/agent-runs/phase-17/runbook-log.md` (written "runbook-log" below; E5's sub-steps are numbered E5.01 to E5.47 there); the release candidate was `b4d8f1229fc145e1492e1a8cbb8a44a368d026af`. E3, E4, most of E5 and E12 ran in the fresh clone `/tmp/p17-clone-20261123T1850Z`; the E5.40 release-manifest dry run, E6 (the Terraform state count and `plan.sh`) and E10 ran in the main working tree at that commit, as each line says. The raw terminal logs stayed local, not committed. No command on this page has been replayed by an agent (Phase 17 planned deviation 1).

Contents:

- [1. Local checks before any cloud step](#1-local-checks-before-any-cloud-step)
- [2. Deployment: plan, sign, deploy](#2-deployment-plan-sign-deploy)
- [3. Smoke tests](#3-smoke-tests)
- [4. The bounded pipeline](#4-the-bounded-pipeline)
- [5. Experiments](#5-experiments)
- [6. Common failures and their fixes](#6-common-failures-and-their-fixes)
- [7. Rollback](#7-rollback)
- [8. Incident escalation](#8-incident-escalation)
- [9. Evidence archiving and the release manifest](#9-evidence-archiving-and-the-release-manifest)
- [10. Cleanup](#10-cleanup)
- [11. The AWS Emulator (local, no account)](#11-the-aws-emulator-local-no-account)

## 1. Local checks before any cloud step

| Step | Command | Replay |
|---|---|---|
| Full repository check in a fresh clone (9 gates: agent files, JSON, hook syntax and self-tests, shellcheck, datasets, Terraform `init -backend=false` and `validate`, `sbt check` and `packageCheck`, script contracts, offline probes) | `bash scripts/check.sh --record-dir "$REPO/evidence/checks"` (inside the clone; `REPO` is this checkout's absolute path) | replayed in phase 17 (E4, exit 0); evidence: runbook-log row E4 (all 9 gates passed, `module_tests=2352 contract_tests=399`) |
| Build the four jars and the local service image (no upload) | `bash scripts/package.sh` | replayed in phase 17 (E5, exit 0); evidence: runbook-log E5.36 (`RESULT: package.sh ok`) |
| Build the four mandatory jars only, if one is missing in a clone | `sbt -batch packageCheck` | replayed in phase 17 (E5, exit 0); evidence: runbook-log E5.39 (`packageCheck: ok`) |

Prerequisites (bash 3.2 or later, git, jq, GNU `timeout`, Java 17, sbt, Terraform exactly 1.17.1, shellcheck 0.11.0, python3, Docker with buildx for `package.sh`, AWS CLI v2 for the cloud scripts) are listed in [`scripts/README.md`](../scripts/README.md), "Prerequisites"; the versions used are in [`docs/tool-versions.md`](tool-versions.md). `package.sh` runs `sbt clean` and rebuilds the jars, so never run it between the release manifest (section 9) and the recomputation of its jar checksums.

## 2. Deployment: plan, sign, deploy

The only apply path is `scripts/deploy.sh` with a plan whose sha256 the human signed in a committed line. Agents never run `terraform apply`; the guard and the permission rules block it ([`docs/agents.md`](agents.md), "Escalation rules").

| Step | Command or action | Replay |
|---|---|---|
| 1. Make a plan (runtime off, the default) | `bash scripts/plan.sh` | replayed in phase 17 (E6, exit 0); evidence: runbook-log row "E6 step 3" (on the clean tree at the release candidate, `RESULT: plan.sh ok`, create=175 update=0 delete=0 replace=0; Phase 17 step I1b had found it read-only, its outputs gitignored and masked; the resulting plan is never signed; [`final-state.md`](final-state.md#9-after-phase-17)) |
| 1. Make a plan (runtime on; the graph run ID is the Spark graph run, never the Neptune CSV folder) | `bash scripts/plan.sh --runtime-enabled true --image-tag BUILD --graph-run-id run:v1:RUN_HEX` | not replayable after teardown (archived evidence: `docs/agent-runs/phase-15/approvals.md`, runtime-on plan D3; `docs/agent-runs/phase-14/approvals.md`, runtime-on plan D10) |
| 2. Read the plan summary | read `infra/terraform/environments/course/plans/*.summary.txt` (gitignored; no stateful delete or replace may appear unless that is the plan's purpose) | not replayable after teardown (archived evidence: the plan descriptions in `docs/agent-runs/phase-14/approvals.md` and `docs/agent-runs/phase-15/approvals.md`) |
| 3. Sign | add the whole line `APPROVED-PLAN-SHA256: HEX64` to `docs/agent-runs/phase-NN/approvals.md`, then commit only `docs/agent-runs/**` (human only) | not replayable after teardown (archived evidence: the ten signed lines listed in [`docs/final-state.md`](final-state.md#2-signed-plans)) |
| 4. Deploy | `bash scripts/deploy.sh --plan-sha256 HEX64`, then commit the new `releases/course/ATTEMPT_ID.json` | not replayable after teardown (archived evidence: `releases/course/`, seven records) |
| 5. Publish the image and jars (before a runtime-on plan) | `bash scripts/package.sh --publish` | not replayable after teardown (archived evidence: `docs/agent-runs/phase-14/runbook-log.md`, rows D4) |

`deploy.sh` refuses (exit 4) a dirty tree, an unsigned plan, a plan whose inputs changed since it was made, and an account that does not match the plan's fingerprint; it writes no release record for a failed apply. The plan inputs are `infra/terraform/` plus the vector index spec (`scripts/lib/identity.sh`, `SC_PLAN_INPUT_PATHS`).

### 2.1 First apply on a new account

On Nov 17 (Phase 14, attempt 1) the first runtime-off apply stopped after 153 of 175 creates with `KMSKeyNotAccessibleFault` for the AWS managed key `aws/rds`, on its first use in the account. Fix: wait a few minutes, make a **new** runtime-off plan (the old one is stale after a partial apply), sign it and deploy it (`docs/agent-runs/phase-14/approvals.md:5`; [`scripts/README.md`](../scripts/README.md), "First apply (new account)"). After the first apply, enter the four application keys in the Secrets Manager console, never on a command line ([`scripts/README.md`](../scripts/README.md), "Entering the four key values").

### 2.2 First runtime-on deploy: rollback policy and the ECS circuit breaker (E-04)

- The previous task-definition revisions are the runtime-off placeholders (image tag `placeholder-runtime-disabled`, never pushed). On a failure of the **first** runtime-on deploy, fix forward (repair, then a new package, plan or deploy); never roll back to a placeholder ([`scripts/README.md`](../scripts/README.md), "First runtime-on deploy (D11)").
- **The ECS circuit breaker rolls back by itself.** Every service has `deployment_circuit_breaker { enable = true, rollback = true }` (`infra/terraform/modules/runtime/main.tf:293-296`). If the new tasks of the first runtime-on deploy fail (a bad image, a failing health check), ECS itself rolls the service back to the last completed deployment, which is the runtime-off placeholder revision (desired count 0, so it counts as completed). The evidence of the failed `deploy.sh` run (exit 6 or 7) may therefore show the placeholder revision and failing image pulls. That is ECS's automatic rollback, not an operator rollback, and fix forward still applies. `deploy.sh` still fails correctly: the running revision differs from the latest, exit 6, no release record. Source: [`docs/agent-runs/phase-14/review-report-b.md`](agent-runs/phase-14/review-report-b.md), row E-04 (routed to this runbook by the Phase 14 gate).
- Desired count: Terraform ignores `desired_count` after a service exists, so turning the runtime on moves only the scaling targets' minimum from 0 to 1. If the count stays at 0 after the wait, `deploy.sh` exits 6 and prints every service's counts. Remedy, with the human's go, per service: `aws ecs update-service --cluster CLUSTER --service ROLE --desired-count 1 --query 'service.[serviceName,desiredCount]' --output text` (CLUSTER from `terraform output -raw ecs_cluster_name`), then a new runtime-on plan (no change expected), sign, deploy. Replay: not replayable after teardown (archived evidence: `releases/course/20261119T193246Z-167196849087-d539.json`). The remedy was never needed: that Phase 14 runtime-on deploy and the Phase 15 one (`releases/course/20261120T213452Z-94ec6b01a4ca-44da.json`) both completed and wrote their release records without it.

## 3. Smoke tests

Export the full-access service key without echoing it, then run one bounded query through the public API. The script resolves `{api-url}` from the Terraform output `api_url` and strips its trailing `/` (G-22, [`docs/api.md`](api.md#1-base-address-and-the-trailing-slash-g-22)).

| Step | Command | Replay |
|---|---|---|
| Export the key (zsh, hidden input) | `read -rs "SCHOLARCHAIN_SERVICE_API_KEY?Service API key: "; export SCHOLARCHAIN_SERVICE_API_KEY` | not replayable after teardown (archived evidence: `docs/agent-runs/phase-14/runbook-log.md`, row D14) |
| Question A (spec gate, benchmark q05) | `bash scripts/smoke-test.sh --question 'Which paper of Wanli Ouyang had the most coauthors on one paper between 2015 and 2025?'` | not replayable after teardown (archived evidence: `docs/agent-runs/phase-14/runbook-log.md`, row D14; `docs/agent-runs/phase-15/runbook-log.md`, row D5) |
| Question B (Neptune path check, benchmark q13) | `bash scripts/smoke-test.sh --question 'Find the shortest path between Tommaso Green and Yiannis Demiris in the coauthorship graph for 2015-2025.'` | not replayable after teardown (archived evidence: `docs/agent-runs/phase-14/runbook-log.md`, row D15; `docs/agent-runs/phase-15/runbook-log.md`, row "D6 G-28 q13 re-run") |

What a correct answer looks like is in [`scripts/README.md`](../scripts/README.md), "Smoke questions". In Phase 14, A passed 7 of 7 checks and was answered with evidence; B passed 7 of 7 but abstained (G-28; Phase 14 gate, [`docs/agent-runs/phase-14/human-gate.md`](agent-runs/phase-14/human-gate.md), "Results"). The smoke records hold `api_url: "{api-url}"`, never the host (G-29, fixed in Phase 15).

## 4. The bounded pipeline

### 4.1 Local bounded pipeline (no AWS)

These are the commands Phase 16 step E5 ran (Phase 16 runbook log, rows "E5 part 1" and "E5 part 2", [`docs/agent-runs/phase-16/runbook-log.md`](agent-runs/phase-16/runbook-log.md)); Phase 17 step E5 runs them again in a fresh clone, each into a new output folder.

| Step | Command | Replay |
|---|---|---|
| Local Hadoop pipeline (LocalJobRunner over the committed samples: the real dblp sample plus the OpenAlex, Crossref and ORCID projections, then the synthetic dblp edge cases; writes `local-run-report.json` into the output folder, which must not exist yet) | `sbt "hadoop/Test/runMain org.scholarchain.hadoop.LocalBoundedPipeline /tmp/p17-hadoop-STAMP"` | replayed in phase 17 (E5, exit 0); evidence: runbook-log E5.13 (`[success] Total time: 130 s`, `local-run-report.json` written) |
| Spark fixture suites (known small graphs and the pipeline fixture) | `sbt -batch "sparkAnalytics/testOnly org.scholarchain.spark.KnownGraphSuite org.scholarchain.spark.PipelineFixtureSuite"` | replayed in phase 17 (E5, exit 0); evidence: runbook-log E5.07 (43/43) |

Phase 16's results, for comparison: the Hadoop runner exited 0 ([`experiments/evaluation/raw/repro/e5-pipeline-20261122T1715Z-79b5583/run.txt`](../experiments/evaluation/raw/repro/e5-pipeline-20261122T1715Z-79b5583/run.txt)) in 127 s ([`hadoop-pipeline.log`](../experiments/evaluation/raw/repro/e5-pipeline-20261122T1715Z-79b5583/hadoop-pipeline.log), line 476) with maximum team size 7, 33 pair instances, 33 consolidated pairs and 67 links ([`local-run-report.json`](../experiments/evaluation/raw/repro/e5-pipeline-20261122T1715Z-79b5583/local-run-report.json); [`docs/evaluation.md`](evaluation.md#107-group-7-reproducibility)); the two Spark suites passed 43 of 43 tests (Phase 16 runbook log, row "E5 part 1"; [`experiments/evaluation/raw/graph/e5-sbt-20261122T1715Z-7e551c8/spark-suites.log`](../experiments/evaluation/raw/graph/e5-sbt-20261122T1715Z-7e551c8/spark-suites.log)). The runner refuses an output folder that holds an incomplete earlier run (Phase 15 runbook log, row "D16 clip 19c"): use a fresh folder each time.

### 4.2 Cloud pipeline (EMR, ECS ops task)

The order is fixed: upload the inputs, Hadoop on EMR, entity resolution on the operator's machine, Spark graph and export on EMR, then the store loads. The complete Phase 14 sequence with every value is in [`scripts/README.md`](../scripts/README.md), "Full reproduction, in order" (steps D5a to D13f); the lines below are its stage commands. Before each EMR stage the human approves the environment, the bounded inputs and the cost bound. After each EMR stage, no cluster may stay active.

| Stage | Command | Replay |
|---|---|---|
| Check the inputs against the dataset manifest | `shasum -a 256 'LOCAL_DIR/dblp.dtd'` must print the manifest's `dtd_sha256` (`datasets/dataset-manifest.yaml:406`) | replayed earlier (phase 14, `docs/agent-runs/phase-14/runbook-log.md`, rows D5a) |
| Upload the DTD | `bash scripts/run-bounded-pipeline.sh --stage upload-input --source dblp --snapshot dblp-2026-11-01 --file 'LOCAL_DIR/dblp.dtd'` | not replayable after teardown (archived evidence: `docs/agent-runs/phase-14/runbook-log.md`, row D5b) |
| Upload the release (1,064.4 MiB, a multipart upload of 17 parts) | `bash scripts/run-bounded-pipeline.sh --stage upload-input --source dblp --snapshot dblp-2026-11-01 --max-upload-mb 1100 --file 'LOCAL_DIR/dblp-2026-11-01.xml.gz'` | not replayable after teardown (archived evidence: `docs/agent-runs/phase-14/runbook-log.md`, row D5c) |
| Hadoop on EMR (the DTD goes through two extra arguments, in this order) | `bash scripts/run-bounded-pipeline.sh --stage hadoop --snapshot dblp-2026-11-01 --dblp 's3://{data-bucket}/raw/dblp/dblp-2026-11-01/dblp-2026-11-01.xml.gz' --hadoop-extra-arg '--dblp-dtd' --hadoop-extra-arg 's3://{data-bucket}/raw/dblp/dblp-2026-11-01/dblp.dtd'` | not replayable after teardown (archived evidence: `docs/agent-runs/phase-14/runbook-log.md`, row "D6 Hadoop, attempt 4"; `experiments/raw/p15-a-hadoop/`) |
| No EMR cluster left | `aws emr list-clusters --active --query 'length(Clusters)' --output text` must print `0` | not replayable after teardown (archived evidence: `docs/agent-runs/phase-15/runbook-log.md`, row "D28 leftover check on camera") |
| Entity resolution (this machine) | `bash scripts/run-bounded-pipeline.sh --stage er --snapshot dblp-2026-11-01 --hadoop-export 's3://{data-bucket}/curated/hadoop/SCOPE/j09-graph-export/run=HEX' --decision-time 2026-11-14T00:00:00Z --seed 20261114` | not replayable after teardown (archived evidence: `docs/agent-runs/phase-14/runbook-log.md`, row "D7 ER") |
| Spark graph on EMR | `bash scripts/run-bounded-pipeline.sh --stage spark --snapshot dblp-2026-11-01 --hadoop-export 's3://{data-bucket}/curated/hadoop/SCOPE/j09-graph-export/run=HEX' --identity-assignments 's3://{data-bucket}/curated/er/ATTEMPT_ID/run=HEX/decisions/identity_assignment' --out-root 's3://{data-bucket}/graph/runs-2'` | not replayable after teardown (archived evidence: `docs/agent-runs/phase-14/runbook-log.md`, rows D8; `experiments/raw/p15-a-spark/`) |
| DynamoDB load (ECS ops task) | `bash scripts/run-bounded-pipeline.sh --stage load --snapshot dblp-2026-11-01 --loader dynamodb-load --export 's3://{data-bucket}/graph/exports/ATTEMPT_ID/run=RUN_HEX'` | not replayable after teardown (archived evidence: `docs/agent-runs/phase-14/runbook-log.md`, row "D13a DynamoDB load, attempt 2") |
| Vector ingest (after the human approves the estimate) | `bash scripts/run-bounded-pipeline.sh --stage load --snapshot dblp-2026-11-01 --loader rag-ingest --export 's3://{data-bucket}/graph/exports/ATTEMPT_ID/run=RUN_HEX'` | not replayable after teardown (archived evidence: `docs/agent-runs/phase-15/runbook-log.md`, rows "D7 probe (5,000 documents)" and "D7 full pass"; `docs/agent-runs/phase-15/s3-copies/rag/ingest-reports/`) |

The Spark export (D9) and the three Neptune loader steps (D13b to D13e) follow the same pattern; their exact lines are in [`scripts/README.md`](../scripts/README.md), "Full reproduction, in order". Run IDs and counts of the recorded runs are in [`docs/data-lineage.md`](data-lineage.md#9-run-ids-of-the-recorded-runs).

## 5. Experiments

Every experiment has a committed manifest whose sha256 the human signs (`APPROVED-MANIFEST-SHA256: HEX64` in `approvals.md`) before it runs; the runner refuses a dirty tree, an unsigned or changed manifest and an existing run folder, and moves the finished folder to `experiments/raw/MANIFEST_ID/ATTEMPT_ID/` ([`scripts/README.md`](../scripts/README.md), "The experiment runner"; [`experiments/README.md`](../experiments/README.md)).

| Step | Command | Replay |
|---|---|---|
| Dry check of a signed manifest (refusals, Terraform outputs, the action's preflight; no run folder) | `bash scripts/run-experiment.sh --manifest experiments/manifests/ID.json --manifest-sha256 HEX64 --check` | not replayable after teardown (archived evidence: `docs/agent-runs/phase-15/runbook-log.md`, rows D9a) |
| Run one experiment | `caffeinate -i bash scripts/run-experiment.sh --manifest experiments/manifests/ID.json --manifest-sha256 HEX64` | not replayable after teardown (archived evidence: `experiments/raw/`, ten attempt folders of the eight Phase 15 manifests) |
| Regenerate a derived table or plot (local) | `python3 -I experiments/derived/NAME.py` (one line per derive script; the README lists the five scripts by name) | replayed in phase 17 (E5, exit 0); evidence: runbook-log E5.22 to E5.26 (each of the five, exit 0) and E5.27 (`git status --short` empty: the regenerated files equal the committed ones) |

Results: [`experiments/results-summary.md`](../experiments/results-summary.md) and [`docs/evaluation.md`](evaluation.md). The planned safe failure exercise (experiment F) has its incident report in [`docs/incidents/INC-P15-F-poison-message.md`](incidents/INC-P15-F-poison-message.md).

## 6. Common failures and their fixes

Every failure below happened in a recorded run and is kept in the record; none was edited out. "Fixed" means fixed in the repository with a review; Phase 16 and 17 fixes are fixed in code, tested offline, not validated live.

### 6.1 Harmless `ERROR … line=N` lines (G-06, G-08)

`package.sh` and `run-bounded-pipeline.sh` run under `set -Eeuo pipefail` with an `ERR` trap (`on_err`: `scripts/package.sh:160`, trap at `:193`; `scripts/run-bounded-pipeline.sh:321`, trap at `:353`). Under `set -E` the trap also fires inside command substitutions, so an **expected** non-zero result inside `$(...)` prints a line such as `ERROR … line=N exit=5: command failed`, and the script then carries on correctly.

- **G-06 (`package.sh`):** printed when the expected "not found" answers come back: the ECR image does not exist yet (`ImageNotFoundException`) or an S3 key does not exist yet (`head-object` 404). Phase 14 step D4b printed six such lines for five keys and still ended with exit 0 ([`docs/agent-runs/phase-14/runbook-log.md`](agent-runs/phase-14/runbook-log.md), row G-06). **How to tell it is harmless:** the script continues, later steps succeed, and the `RESULT` line and the exit code are 0.
- **G-08 (`run-bounded-pipeline.sh`, multipart uploads):** (a) a failed upload-part try that is then retried prints a spurious `ERROR … line=N exit=5` line before the `WARN` retry line, during a run that then succeeds; the already-present path prints one for the expected `NoSuchUpload` answer. (b) A part that times out five times in a row ends `failed` with exit 5, not `timed-out` with exit 7: the "status 7 on time-out" in the usage text cannot be reached on that path ([`docs/agent-runs/phase-14/review-report-G-07.md`](agent-runs/phase-14/review-report-G-07.md), row G-08). **How to tell:** judge the run by its `SUMMARY:` lines, its run record and its exit code, not by `ERROR` lines followed by a `WARN` retry.

Both are minor and were recorded, not repaired. The Phase 14 gate noted them as "documented in `scripts/README.md` until then"; `scripts/README.md` at commit `50ba087` holds no such note, so this section is their documentation.

### 6.2 Failures in the Phase 14 and Phase 15 runs

| ID | Symptom | Cause | Fix | Source |
|---|---|---|---|---|
| first apply | Neptune `CreateDBCluster` returns `KMSKeyNotAccessibleFault`; apply stops at 153 of 175 creates | first use of the AWS managed key `aws/rds` in the account | wait, new runtime-off plan, sign, deploy (section 2.1) | `docs/agent-runs/phase-14/approvals.md:5` |
| G-02 | `PutDashboard` refuses the dashboard body; attempt 2 stops at 21 of 22 creates | dashboard widget key | fixed in the dashboard Terraform; plan attempt 3 created only the dashboard | `docs/agent-runs/phase-14/approvals.md:13`; [`review-report-G-01.md`](agent-runs/phase-14/review-report-G-01.md) |
| G-07 | the 1.1 GB release sent as one `put-object` is lost to one dropped connection | single PUT over a slow uplink | every file above 64 MiB goes as a multipart upload ([`scripts/README.md`](../scripts/README.md), "Large uploads") | [`repair-D5-infra.md`](agent-runs/phase-14/repair-D5-infra.md); [`review-report-G-07.md`](agent-runs/phase-14/review-report-G-07.md) |
| DTD | Hadoop succeeds but rejects every record with a named entity as `undefined_entity` | the release needs `dblp.dtd` | pass `--dblp-dtd` through two `--hadoop-extra-arg` values; check that the J01 counter `undefined_entity` is 0 | [`scripts/README.md`](../scripts/README.md), "DBLP DTD" |
| G-12 | a script string replacement misbehaves under macOS bash 3.2 | a quoted replacement form that bash 3.2 handles differently | `replace_literal` helper; no other bash-4-only construct | [`repair-D6-infra.md`](agent-runs/phase-14/repair-D6-infra.md); [`review-report-G-12.md`](agent-runs/phase-14/review-report-G-12.md) |
| G-14 | Hadoop execution fails at once with `AccessDeniedException` | the pipeline state machine role lacked `elasticmapreduce:AddTags` | permission added; plan attempt 4 | [`repair-D6-infra-iam.md`](agent-runs/phase-14/repair-D6-infra-iam.md); [`review-report-G-14.md`](agent-runs/phase-14/review-report-G-14.md) |
| G-17 | the first good Hadoop run makes 2.3 times as many candidates as Phase 5 | other blocking defaults than Phase 5's evaluated values | defaults set to Phase 5's values; the re-run matched Phase 5 on every counter | [`repair-D6-hadoop.md`](agent-runs/phase-14/repair-D6-hadoop.md); [`review-report-G-17.md`](agent-runs/phase-14/review-report-G-17.md) |
| G-21 | the Spark graph step fails: driver out of memory | default 2 GiB driver heap | 6 GiB driver, 32 shuffle partitions, UI retention limits; plan attempt 5 | [`repair-D8-infra.md`](agent-runs/phase-14/repair-D8-infra.md); [`review-report-G-21.md`](agent-runs/phase-14/review-report-G-21.md) |
| Spark rerun | the rerun refuses its output root | the failed first run left `graph/runs/run=<hex>` without a manifest, which the job refuses | rerun with a new root, `graph/runs-2` | [`scripts/README.md`](../scripts/README.md), "Full reproduction, in order", step D8 |
| G-01 | the full entity-resolution upload (about 21 GB) would take hours on a slow uplink | only the identity assignments are read downstream | option `--upload identity-assignments-only` (D7 used the full upload in the end) | [`repair-DER-infra.md`](agent-runs/phase-14/repair-DER-infra.md); [`scripts/README.md`](../scripts/README.md), "ER upload size" |
| G-24 | the DynamoDB load fails at `run-task` with `jq: Unknown option --config` | a `jq --args` form that the installed jq rejected | one `--arg` per word | [`repair-D13a-infra.md`](agent-runs/phase-14/repair-D13a-infra.md); [`review-report-G-24.md`](agent-runs/phase-14/review-report-G-24.md) |
| G-23 | `/readyz` answers 503 (graph check) on a cold call, then 200 | cold graph check, cause not logged | fixed in Phase 16 | Phase 14 runbook log, rows G-23 and "D12 readiness, recheck"; Phase 16 gate, "Spec checklist" |
| G-22 | requests to `{api-url}` plus `/v1/...` get the load balancer's fixed 404 | `api_url` ends in `/`, giving `//v1/` | strip the trailing slash (`smoke-test.sh` does) | [`docs/api.md`](api.md#1-base-address-and-the-trailing-slash-g-22) |
| G-27 | the vector ingest is too slow to finish inside its own limits | per-item writes | batched writes and the re-embed path (Phase 15); the skip-only pass wrote nothing and the first live vector search ran | Phase 15 gate, "Items earlier gates sent to Phase 15" |
| G-28 | the path question (q13) abstains | a graph-store failure in the path query; reason now recorded as `path-query-failed` | reason codes (Phase 15); cause narrowed in Phase 16 | Phase 15 gate; Phase 16 gate, "Spec checklist" |
| G-29 | smoke records hold the API host | written before masking | records hold `api_url: "{api-url}"` (Phase 15) | Phase 15 gate |
| D11 count | after the runtime-on deploy a service stays at desired 0 | Terraform ignores `desired_count` after creation | section 2.2 remedy (not needed in the recorded runs) | [`scripts/README.md`](../scripts/README.md), "First runtime-on deploy (D11)" |
| L-01 | an experiment A Hadoop attempt fails: EMR refuses managed scaling with maximum equal to minimum | the state machine always attached a managed-scaling policy | no managed-scaling policy when max = min; plan `235b56f5…` | [`repair-D7a-infra-engineer.md`](agent-runs/phase-15/repair-D7a-infra-engineer.md); [`review-report-L-01-02.md`](agent-runs/phase-15/review-report-L-01-02.md) |
| L-02 | the F preflight (D9a) fails | the C and F collectors used a short embedding model ID | full embedding model ID | [`repair-D10-experiment-engineer.md`](agent-runs/phase-15/repair-D10-experiment-engineer.md); [`review-report-L-01-02.md`](agent-runs/phase-15/review-report-L-01-02.md) |
| L-09 | `check.sh` gate 8 (script contracts) fails in a fresh clone | `RetrievalEvalJob` missing from `project/PackageCheck.scala` | added in Phase 16; gate 8 passed in the Phase 16 clean-checkout run | Phase 15 runbook log, row "D16 clip 19b"; Phase 16 gate, "Spec checklist" |
| L-11 | `cleanup.sh --remove` refuses before the confirmation prompt: "runtime_enabled is not false" | jq's `//` operator read the boolean `false` output as missing | `out()` reads `false` correctly; nothing was removed by the refused attempt | [`repair-D27-infra-engineer.md`](agent-runs/phase-15/repair-D27-infra-engineer.md); [`review-report-L-11.md`](agent-runs/phase-15/review-report-L-11.md) |
| L-15 | a correct removal ends with exit 6 ("unexpected tagged resources") | `check-leftovers` counted tagging-API entries of resources already removed | fixed in Phase 16 | Phase 15 runbook log, rows D27 attempt 2 and D28; Phase 16 gate, "Spec checklist" |
| P16-L-16 | rag tests fail in a fresh clone from the operator's shell | tests read leftover `SCHOLARCHAIN_CFG_*` exports | tests made hermetic (Phase 16); remove the exports from the shell profile | [`docs/evaluation.md`](evaluation.md#107-group-7-reproducibility) |

## 7. Rollback

- **Services:** `scripts/rollback.sh` points each service back to the previous task-definition revision named in a release record, and writes `releases/course/ATTEMPT_ID-rollback.json` only after a verified rollback ([`scripts/README.md`](../scripts/README.md), table "The ten scripts", row `rollback.sh`). A rollback beyond the recorded previous revision needs the human's approval (human gate 3, [`docs/agents.md`](agents.md), "Human gates"). Never roll back to a runtime-off placeholder (section 2.2).

  | Command | Replay |
  |---|---|
  | `bash scripts/rollback.sh --release releases/course/RECORD.json --role ROLE` | not replayable after teardown (archived evidence: `releases/course/`). `rollback.sh` was never needed in Phases 14 and 15: that folder holds the release records of every deploy and no `-rollback.json` record. |

- **Infrastructure:** there is no Terraform rollback; fix forward with a new signed plan. The next plan after a service rollback shows ECS drift.
- **ECS's own rollback:** the deployment circuit breaker can roll a failing deployment back by itself (section 2.2, E-04).

## 8. Incident escalation

1. **Detect:** CloudWatch alarms (dead-letter queue, healthy hosts, worker), smoke-test failures, or `deploy.sh` exit codes. The Phase 15 alarm table and what each resting state means are in the Phase 15 gate ("Results", D18).
2. **Stop and ask:** an agent that meets an incident returns `STATUS: BLOCKED-ON` with evidence; the orchestrator asks the human. Data, security and destructive actions always go to the human ([`docs/agents.md`](agents.md), "Escalation rules").
3. **Recover:** `incident-responder` owns diagnosis reports under `docs/incidents/**` and runs only approved rollback and bounded recovery actions through `scripts/rollback.sh` ([`docs/agents.md`](agents.md), "Artifact ownership").
4. **Report:** every incident report carries the specification's fields (incident ID, start and end time, detection source, user-visible impact, trace and run IDs, timeline, root cause, recovery action, data-integrity assessment, follow-up owner, preventive test or control). The one recorded incident is the planned, safe exercise of experiment F: [`docs/incidents/INC-P15-F-poison-message.md`](incidents/INC-P15-F-poison-message.md).
5. **Never** manufacture a destructive incident in the live course account (course specification, "Incident and rollback requirements").

## 9. Evidence archiving and the release manifest

| Step | Command | Replay |
|---|---|---|
| Local archive (repository evidence and masked plan records; no AWS) | `bash scripts/archive-evidence.sh --local-only` | replayed in phase 17 (E12, exit 0); evidence: runbook-log row "E12r.6 `archive-evidence.sh --local-only` (README:319)" (`RESULT: archive-evidence.sh ok`, 170 files; one WARN by design of this mode: account unknown, byte copies not checked for identifiers) |
| Full archive with the S3 `reports/` prefix (identifier check includes the API host) | `bash scripts/archive-evidence.sh` | not replayable after teardown (archived evidence: `docs/agent-runs/phase-15/runbook-log.md`, row "D25 step 3"; archive sha256 in `docs/agent-runs/phase-15/approvals.md:78`) |
| Release manifest, dry run into a temporary folder | `bash scripts/release-manifest.sh --clone /tmp/p17-clone-STAMP --marker /tmp/p17-E3-marker.txt --out-dir /tmp/p17-manifest-dryrun-STAMP` | replayed in phase 17 (E5, exit 0); evidence: runbook-log row "E5.40 release-manifest dry run" |
| Release manifest, real run (writes `releases/manifest/RC_SHA/release-manifest.json` and `.md`) | `bash scripts/release-manifest.sh --clone /tmp/p17-clone-STAMP --marker /tmp/p17-E3-marker.txt --out-dir releases/manifest` | replayed in phase 17 (E10, exit 0); evidence: runbook-log row "E10 release manifest"; output [`releases/manifest/b4d8f1229fc145e1492e1a8cbb8a44a368d026af/`](../releases/manifest/b4d8f1229fc145e1492e1a8cbb8a44a368d026af/) |

- `archive-evidence.sh --local-only` changed in Phase 17 (finding E-03; `scripts/archive-evidence.sh:580-586`): when the account is known and no account ID is found, it now writes the field `byte_copies_hold_identifiers` as a string ("account only: no account ID found; bucket names not checked (--local-only)") instead of `false`. The mode ran in Phase 13 ([`docs/agent-runs/phase-13/human-gate.md`](agent-runs/phase-13/human-gate.md), "Archive (`--local-only`): passed, 13 files"), before this change, so that run is not evidence for the current code. The row above was replayed once with the current code at Phase 17 step E12, in the fresh clone, exit 0 (runbook-log row "E12r.6 `archive-evidence.sh --local-only` (README:319)"; `scripts/README.md` is not changed after the release candidate and does not record this run), so the current code of this mode is runtime tested once, in local-only mode. That run found no account and took the "account unknown" branch (its WARN), so the E-03 branch itself, which needs a known account, has still not run.
- The Phase 15 archive (`a7115c16…`) holds identifiers in byte copies of earlier records, so it stays **local only** and is never shared as is; only its sha256 is committed (`docs/agent-runs/phase-15/approvals.md:77-78`; Phase 15 gate, decision 7).
- `release-manifest.sh` (new in Phase 17; `runtime: tested in phase 17 (E10, exit 0)`, after the E5.40 dry run also exited 0, runbook-log rows "E5.40 release-manifest dry run" and "E10 release manifest"; `scripts/README.md:59` still reads `runtime: untested`, a recorded open item, P17-M-04, owned by infra-engineer and not changed after the release candidate): read-only, no network and no AWS call. It refuses a dirty tree, a clone whose HEAD differs from this tree's HEAD, a missing mandatory jar, and an existing output folder; exit codes 0 written, 2 usage, 3 missing prerequisite, 4 refused, 5 command failed or interrupted, 6 check failed, 7 stopped by TERM, 8 an identifier would be written (`scripts/release-manifest.sh`, `--help` text). On a failure after its temporary folder exists it prints `INCOMPLETE-FOLDER: PATH`: never commit that folder; remove it and run again. The four mandatory jars (hadoop, entity-resolution, spark-analytics, services) are built in the clone by `check.sh` gate 7; if one is missing, build all four with the `packageCheck` line of section 1 (the `packageJobs` alias does not build the services jar). Full contract: [`scripts/README.md`](../scripts/README.md), "Release manifest (Phase 17, decision 7 item 1)".

## 10. Cleanup

Cleanup must not remove evidence needed for grading before its archival location is approved (course specification, "Cleanup requirements"). The Phase 15 order, as run (Nov 21; [`scripts/README.md`](../scripts/README.md), "Cleanup order (D24 to D28)"):

| Step | Command or action | Replay |
|---|---|---|
| D24 | tell the instructor where the grading evidence lives (the repository and the local archive with its sha256) | not replayable after teardown (archived evidence: `docs/agent-runs/phase-15/runbook-log.md`, rows D24) |
| D25 | inventory first; archive (section 9); then the "before" inventory and the removal plan: `bash scripts/cleanup.sh --evidence-archive PATH_TO_ARCHIVE --list` | not replayable after teardown (archived evidence: `docs/agent-runs/phase-15/runbook-log.md`, row "D25 step 5 cleanup.sh --list"; not planned for Phase 17 step E6: the Phase 17 step I1b analysis found that against today's emptied account it stops with exit 3 at `terraform-outputs`, before any AWS call or listing, because the local state has no outputs, so it gives no inventory; "safe and useful for E6: no") |
| D26 | sign the removal plan (`APPROVED-PLAN-SHA256: HEX64` in `approvals.md`, commit) | not replayable after teardown (archived evidence: `docs/agent-runs/phase-15/approvals.md:75-76`) |
| D27 | remove; the human types the confirmation `REMOVE course FIRST12HEX` at the terminal: `bash scripts/cleanup.sh --evidence-archive PATH_TO_ARCHIVE --remove --plan-sha256 HEX64` | not replayable after teardown (archived evidence: `docs/agent-runs/phase-15/cleanup/`; `docs/agent-runs/phase-15/runbook-log.md`, rows "D27 attempt 1" and "D27 attempt 2") |
| D28 | copy the masked cleanup record into `docs/agent-runs/phase-15/`; read-only checks that no EMR cluster, interface endpoint, NAT gateway, load balancer, Neptune cluster or vector bucket is left; billing once more | not replayable after teardown (archived evidence: `docs/agent-runs/phase-15/runbook-log.md`, rows D28) |

After the removal, the human counts what the local Terraform state still holds (Phase 17 step E6; resource addresses only go into the counted file, diagnostics into the `.err` file; the count is evidence only when the `exit=` line says 0; agents never read the state):

```sh
terraform -chdir=infra/terraform/environments/course state list > /tmp/p17-E6-state.txt 2> /tmp/p17-E6-state.err; echo "exit=$?" | tee /tmp/p17-E6-state.exit; wc -l < /tmp/p17-E6-state.txt
```

Replay: replayed in phase 17 (E6, exit 0); evidence: runbook-log row "E6 step 2" (count 0: no managed resource address in the local state).

- If `--remove` stops partway: never a second `--remove` with the same plan; make a new `--list`, a new plan and a new signature.
- Before the removal plan, apply a signed runtime-off, cleanup-mode plan (`bash scripts/plan.sh --runtime-enabled false --cleanup-mode true`; replay: not replayable after teardown (archived evidence: `docs/agent-runs/phase-15/approvals.md:67-73`; `releases/course/20261121T052512Z-ebcbc03a90bd-01a4.json`)), which turns every deletion protection off and scales the runtime to zero.
- What was removed, what was retained and why, category by category: [`docs/final-state.md`](final-state.md#6-cleanup-categories).

## 11. The AWS Emulator (local, no account)

The Phase 16 bonus track runs ScholarChain's storage adapters and worker against AWS's local emulator in Docker: no account, no cost. The complete 15-step procedure, the safety contract and the exit codes are in [`scripts/README.md`](../scripts/README.md), "AWS Emulator (Phase 16, decision 12; run book step E14)"; results and limits are in [`docs/emulator.md`](emulator.md). The main commands:

| Step | Command | Replay |
|---|---|---|
| Build the image | `docker build -t scholarchain-emulator docker/emulator` | replayed earlier (phase 16, `docs/agent-runs/phase-16/runbook-log.md`, row "E14 part 1") |
| Start it (emulator terminal; loopback only, telemetry off) | `docker run --rm --memory=2g --memory-swap=2g -p 127.0.0.1:9297:9297 -e HOME="$HOME" -v "$HOME/.aws/emulator:$HOME/.aws/emulator" scholarchain-emulator -p 9297 --telemetry off` | replayed earlier (phase 16, `docs/agent-runs/phase-16/runbook-log.md`, row "E14 part 1") |
| Point a second terminal at it | `source scripts/emulator/env.sh` | replayed earlier (phase 16, `docs/agent-runs/phase-16/runbook-log.md`, row "E14 part 2 (setup)") |
| Create the resources | `bash scripts/emulator/setup.sh` | replayed earlier (phase 16, `experiments/emulator/raw/20261122T1845Z-ecc1305-7d2e/setup.jsonl`) |
| Storage suite against the emulator | `sbt storage/EmulatorTest/test` | replayed earlier (phase 16, `experiments/emulator/raw/20261122T1845Z-ecc1305-7d2e/results-EmulatorAdaptersEmulatorSuite.jsonl`) |
| Services (worker) suite against the emulator | `sbt services/EmulatorTest/test` | replayed earlier (phase 16, `experiments/emulator/raw/20261122T1845Z-ecc1305-7d2e/results-EmbeddingWorkerEmulatorSuite.jsonl`) |
| Probes | `bash scripts/emulator/probes.sh` | replayed earlier (phase 16, `experiments/emulator/raw/20261122T1845Z-ecc1305-7d2e/probes.jsonl`) |

- Since Phase 17 (step I1b; commit pending), `env.sh`, when sourced, also unsets every exported `SCHOLARCHAIN_CFG_*` variable and prints only how many it cleared (`INFO: env.sh: cleared N exported SCHOLARCHAIN_CFG_* variable(s) (count only; no name or value is printed)`); it then still unsets the fixed list of names the configuration loader knows (`scripts/emulator/env.sh:80-106`, `:108` onward). In Phase 16 the human cleared these variables by hand before the setup (Phase 16 gate, unplanned deviation 4 of session 3); that hand step is no longer needed ([`scripts/README.md`](../scripts/README.md), "AWS Emulator (Phase 16, decision 12; run book step E14)", step 8). The Phase 17 change has never run: `runtime: untested` (`scripts/emulator/env.sh:55-58`). The `env.sh` replay line above is the Phase 16 version.
- Since Phase 17 (step I1b, P16-B-03), `probes.sh` no longer stops on an unbound variable when `SCHOLARCHAIN_EMULATOR_RESULTS_DIR` is unset after an earlier safety rule failed; it refuses with exit 4 instead (`scripts/emulator/probes.sh:198-202`; `runtime: untested`). The `probes.sh` replay line above is the Phase 16 version.
- A re-run starts with a restarted emulator, a new run ID and a new results folder; the scripts refuse a results folder that already holds their file.
