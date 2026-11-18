STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: docs/agent-runs/phase-13/prompt.md (sha256 60c943dccc86a6dc58ec074b95bbd63f04e2adca2f7a1c9a9abad266f28d36a7), session 2, review R-3 (final state of infra/**, config/**, scripts/**). Report file: review-report-c.md
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools: Read, Grep, Glob)
TEST RESULTS: not run (I have no Bash). No script was executed. Every finding below comes from reading the code.
VALIDATION RESULTS: I did not run fmt, validate, shellcheck or jq. The writers' VALIDATION EVIDENCE is taken as reported; the orchestrator re-runs those checks under decision 10.
ASSUMPTIONS:
- Infra files that did not change since the Phase 12 review (git status shows them unchanged) were not re-read; see Coverage.
- I checked the call table against the source constants and producer code directly (decision 7: "the source is the contract"), not against the producer reports' text.
RISKS:
- Off-list commands that the writers reported themselves: 7a used `sed -n`, `cat`, `uniq` and `tr`; 7b prefixed calls with `cd` and ran shell loops. The decision 15 summary must list them. This is a process note, not a code finding.
- `infra/terraform/modules/workflow/sfn.tf:126-133`. ExportInputCheck tests only a string prefix and does not refuse `..` segments. If Hadoop `Path` normalizes `..`, a hand-made execution input could export outside `graph/`, but only into `curated/`, which the EMR role can already write. I could not verify the library behavior from repository files, so this is not a finding. The script path already refuses `..` (`run-bounded-pipeline.sh:603`).
- Phase 14 ordering: the ops task image is a placeholder while the runtime is off (`runtime/main.tf:26-29`). So `--stage load` cannot run before the runtime-on plan, and `check_ops_task` refuses it with exit 4 (`run-bounded-pipeline.sh:1178-1179`). scripts/README.md orders the loads after the runtime-on deploy (steps 12-18), so the documentation is consistent. The open Phase 14 question ("graph run ID from a first pipeline run with the loaders") is constrained by this.
- AWS CLI rows of the call tables without an aws-docs citation are `unverified` (list in the checklist).
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Step 8 triage.
- I-18 (major) goes to infra-engineer in step 9 (script only, so plans and artifacts stay valid).
- I-19 (major, already confirmed by the orchestrator) goes to factory-engineer, or to a human decision on gate-4 scope.
- Minors are recorded.

VERDICT: REQUEST_CHANGES

## Re-checks from R-2

- **C-01: RESOLVED.** Exports are pinned under `graph/` everywhere:
  - State machine: ExportInputCheck requires both fields under `graph_uri_prefix` (`sfn.tf:122-133`, `:180-196`), and `environments/course/main.tf:206-208` passes `s3://<bucket>/graph/`.
  - 7b script: `--export-source-run` and `--export-root` go through `resolve_location … P_GRAPH` (`run-bounded-pipeline.sh:1120-1123`); the loaders' `--export` and `--graph-run` too (`:1202`, `:1207`).
  - EMR instance role reads and writes `graph/` (`identity/emr.tf:19-20`, `:59`).
  - ops role: DataLakeList and GraphAndRagRead on `graph/` (`runtime/iam.tf:232-254`), plus GraphCsvWrite (`:267-271`).
- **C-03: RESOLVED.**
  - `{data-bucket}` is resolved by `resolve_s3` (`identity.sh:277-300`) through `resolve_location` (`run-bounded-pipeline.sh:594-609`) and in extra args (`:880-897`); records turn locations back into the logical form with `logical_s3`.
  - Metric placeholders are resolved at export time (`run-experiment.sh:596-675`); `queries.json` keeps the placeholder form (`:631`, `:638`).
  - `experiments/README.md:20` and `:55` are consistent with this.

## Mechanical call-table check (decision 11)

- **Terraform outputs.** Every name read in the `$(out NAME)` or `.NAME.value` forms exists in `outputs.tf`: api_url :17, ecr_repository_url :27, ecr_repository_name :32, ecs_cluster_name :37, ecs_service_names :42, ecs_task_definition_families :47, ops_task_definition_family :52, ops_container :57, app_subnet_ids :62, ops_security_group_id :67, alb_dns_name :72, state_machine_arn :79, data_bucket_name :101, data_prefixes :106 (keys raw/curated/graph/artifacts in `data-lake/main.tf:19-27`), vector_bucket_name :116, dynamodb_table_names :121, neptune_endpoint :126, neptune_loader_role_arn :131, vpc_id :138, nat_gateway_id :143, log_group_names :158, runtime_enabled :12, api_id :22. The `.variables.*.value` paths in deploy.sh and plan.sh are plan JSON, not outputs, and gate 8's regex does not match them.
- **Entry-point options.** Every option on a `# contract:` line is in its constant, and every required option is passed:
  - ErMain (`run-bounded-pipeline.sh:1044` against `ErMain.scala:35-55`).
  - StorageLoadMain dynamodb-load, neptune-csv-build, neptune-bulk-start, neptune-bulk-status (`:1212`, `:1215`, `:1220`, `:1223`, `:1225` against `StorageLoadMain.scala:67-85`).
  - RagIngestJob (`:1229` against `RagIngestJob.scala:90-91`).
  - JsonlExport from the state machine (`sfn.tf:330` against `JsonlExport.scala:234`, `:237`).
  - The Hadoop args (`sfn.tf:228`) match `ScholarChainHadoop.scala:24`, `:32`, `:55`, `:68` and `Settings.scala:159`.
- **packageCheck class list** (`project/PackageCheck.scala:18-33`). It holds every class that scripts or sfn.tf name: ErMain, StorageLoadMain, RagIngestJob, JsonlExport, GraphAnalyticsJob, ScholarChainHadoop.
- **jq paths into producers.** All match:
  - Package record `.jars[].module/.name/.path/.sha256/.build_info.{buildCommit,buildDirty}` (`package.sh:418-421`) and `.image.local_image_id` (`:517`).
  - Plan and cleanup record fields (`plan.sh:392-409`, `cleanup.sh:422-434`), read by `deploy.sh:274-280`, `identity.sh:153` and `archive-evidence.sh:383-405`.
  - Run record `.run_ids.execution_name`, `.run_ids.emr_cluster_ids` (`run-bounded-pipeline.sh:699`, `:778`, `:796`), read by `run-experiment.sh:558-559`.
  - ER record `.outputs.identity_assignments` (`:1078`, read at `:1094`).
  - Release record fields (`deploy.sh:594-601`), read by `rollback.sh:189-227`.
  - Hadoop `_job.json` `.outputs[].{path,length,sha256}` (`JobRunner.scala:209`, `:266`).
  - DockerContext layout (`project/DockerContext.scala:14-16`) and `image.java-runtime` (`project/build.properties:48`).
  - Container paths (`docker/services/Dockerfile:26-27`).
  - Build-input list `identity.sh:34` equals `project/BuildIdentity.scala:24-34`.
  - Program log prefixes `[storage-load]` and `[rag-ingest]` exist in StorageLoadMain and RagIngestJob.
- **AWS CLI citations.**
  - Cited: ecr describe-images, s3api put-object, cloudwatch get-metric-data, ecs run-task, logs get-log-events, and the Step Functions TaskSucceededEventDetails API page (event shape only).
  - `unverified` (no citation): sts get-caller-identity; ecr get-login-password; s3api head-object (page opened, options not reached), list-objects-v2, get-object; emr describe-cluster (list-clusters cites Phase 12); stepfunctions start-execution, describe-execution, and get-execution-history CLI options; ecs describe-services, describe-task-definition, list-tasks, describe-tasks; resourcegroupstaggingapi get-resources; all Docker CLI rows; the curl `--config -` header line.
- **Refusal paths before any cloud call (probe expectations).** All exist:
  - deploy with a wrong sha gives exit 4 with `ERROR: deploy.sh step=plan-lookup exit=4`: `deploy.sh:607-614`, `:269-270`; cloud prerequisites come after, at `:615`.
  - run-experiment on the example gives exit 4 "refusing: the manifest is an example": `run-experiment.sh:319-323`, order `:733-738`, before `read_outputs` at `:739`. See I-20 for the `aws` prerequisite that comes first.
  - smoke-test without the key gives exit 3 naming SCHOLARCHAIN_SERVICE_API_KEY: `smoke-test.sh:202-206`, called at `:534` before prerequisites and any network call.
  - `cleanup --list` without `--evidence-archive` gives exit 4: `cleanup.sh:242-245`, inside parse_args.
  - The `archive --local-only` layout matches `archive_keys`. It has one root folder `NAME/` (`archive-evidence.sh:570`), MANIFEST.json, `repo/` and `plans/` (`:296`, `:559`), and no top-level `reports/` in local-only mode. `identity.sh:435-478` yields keys under `repo/`, `plans/` and `MANIFEST.json` only, which is what `check.sh:762-820` asserts.
  - Other documented refusals also come before the cloud: `package.sh:531-534` and `:784-786`, `cleanup.sh:811-815`, `archive-evidence.sh:267-287`, `run-bounded-pipeline.sh:1333-1339`.

## Requirement checklist

**Decision 8**
- 8.1 I-02: done, `identity/deployment.tf:288-293`.
- 8.1 I-06: done, `emr/main.tf:38`, `emr/variables.tf:88-108`, `course/variables.tf:98-108`, `course/main.tf:90-91`. No stale `instance_vcpus` reference remains.
- 8.1 I-10: done, `deployment.tf:224-228`.
- 8.1 I-16: done, `deployment.tf:311-315`.
- 8.1 I-12: done, README.md (vectors and cleanup-mode sentences) and `vectors/main.tf:6-12`.
- 8.2 ops role: done, `runtime/iam.tf:267-271`. The boundary is not widened, and none is needed (`boundary.tf:39-46`).
- 8.3 JsonlExport on EMR: done, `sfn.tf:180-196` and `:311-336`, `workflow/variables.tf` graph_uri_prefix. Two fields (exportSourceRun plus exportRoot) use the fallback the work order allowed, stated in the report and in sfn.tf. EMR write to `graph/` was checked.
- 8.4 RK1: done, `network/main.tf:357-364`, aws-docs cited. That the loader's S3 traffic passes the Neptune SG is unverified.
- 8.5 nothing else changed: checked against the diff. The only extra is the ops_container local, which refactors without changing the rendered JSON (`runtime/main.tf` diff).
- 8.6 root outputs: done, `outputs.tf:57-60`.
- 8.7 fmt and validate: writer evidence only.

**Decision 9**
- 9.0 helper: done, `scripts/lib/identity.sh` (every listed function; `archive_keys` is unchanged from the old cleanup copy, verified against the diff).
- Common rules:
  - Strict mode, no prompt except cleanup's, STEP:/RESULT: markers, timeouts, retries only on idempotent calls, honest status lines: done in all ten.
  - Masking: printed raw Terraform output is a defect, I-21.
  - Exit-code labels: minor defect, I-24.
  - Root refusal: rollback.sh does not refuse, I-22.
  - Clean checkout: I-19, I-20.
- Records: done (attempt IDs, `.started.json` then final, never overwritten; `identity.sh:354-388`). rollback.sh is the exception, I-22.
- 9.1 check.sh: built (gates 1-9, `--record-dir`, `--fail-fast`, summary). Gate 4 fails on today's tree: defect I-19. Probe prerequisite: I-20.
- 9.2 package.sh: done. The publish record is a separate file, a deviation recorded by 7a (A1).
- 9.3 plan.sh: done (`plan.sh:249-272`, `:389-410`).
- 9.4 deploy.sh: done (lookup, validity, approval, I-09 drain, release record).
- 9.5 smoke-test.sh: defect I-18.
- 9.6 run-bounded-pipeline.sh: done, all five stages.
- 9.7 run-experiment.sh: done (refusal order, three guarantees, raw-folder move).
- 9.8 archive-evidence.sh: done. RK-E namespace rule holds: the repo copy of `reports/` goes to `repo/reports/` (`archive-evidence.sh:350-351`).
- 9.9 cleanup.sh: done (`archive_keys`, plan validity, approval, I-08, I-15).
- 9.10 rollback.sh: unchanged; finding I-22.
- 9.11 scripts/README.md: done.

**Spec work-order items**
- Item 1 check.sh: built; it exits non-zero today because of I-19.
- Items 2, 3, 4, 6, 7, 8, 9: built.
- Item 5: defect I-18.
- Automation rules: strict mode, no secrets in source, timeouts, retries and markers are met. The printed identifiers are I-21.
- "Run every non-destructive script": deviation (planned) 12.1.

**Gate items**
- Clean checkout runs all non-cloud validation: the script exists, but gate 4 fails (I-19); it also depends on I-20; the human run is pending.
- Planning reproducible and checksum archived: implemented (plan.sh record and archive `plans/`); the human run is pending.
- Deployment needs the documented approval: enforced, `deploy.sh:607-614` and `identity.sh:178-191`.
- Smoke test runs a real bounded query: phase-14, and defect I-18.
- Same scripts in the README: done in `scripts/README.md` (deviation 12.5).

**Other checklist items**
- IAM: new actions are scoped, the boundary is unchanged, and I-02, I-06, I-10 and I-16 are resolved.
- Secrets and identifiers: no literal account ID, bucket, ARN or public IP in infra, config or scripts (Grep). Raw Terraform output and plan records: I-21.
- One writer on AWS: check-then-act (`run-bounded-pipeline.sh:1184-1191`), accepted risk.
- Tests: scripts have no tests besides check.sh's probes. No probe reaches past smoke-test's key check, which is how I-18 slipped through.

## Findings

| ID | Severity | Interface | Owner | File:line | Failure input | Impact |
|---|---|---|---|---|---|---|
| I-18 | major | no | infra-engineer | scripts/smoke-test.sh:313-314, :321-322; also :336, :355, :398, :441, :510 | Any run, for example `bash scripts/smoke-test.sh --question 'Q'` against a healthy API. | `request()` sets `BODY` and `HEADERS` only inside itself, and it is always called in a `$(...)` subshell (directly or through `get_with_retry`). In the parent shell both stay `""`. So after a 200 on `/healthz`, `jq -r '.status // empty' ""` fails and the script exits 6 with "GET /healthz body status is not ok". Every run fails, and spec item 5 and the gate "smoke tests exercise a real bounded query" cannot pass in Phase 14. Fix: set `BODY` and `HEADERS` in the parent once `TMP_DIR` exists, and stop assigning them inside `request()`. |
| I-19 | major | no | factory-engineer (or a human decision on gate scope) | scripts/check.sh:466-478 (gate 4 includes `.claude/hooks/*.sh` and `.claude/hook-tests/*.sh`); cause `.claude/hooks/verify-tests-ran.sh:182` (SC1087) plus warnings. Confirmed by the orchestrator, not re-derived. | Decision 13.3: `bash scripts/check.sh` in a fresh clone of the final tree. | Gate 4 fails, so check.sh exits 6. The other gates still run (no `--fail-fast`) and the record shows `shell: failed`. The gate item "a clean checkout can run all non-cloud validation" cannot be shown passing until the hooks are shellcheck-clean or the human scopes gate 4 for `.claude/**`. |
| I-20 | minor | no | infra-engineer | scripts/run-experiment.sh:279 (`aws` and `terraform` required before the refusals); scripts/check.sh:266 (no `aws` in check.sh's prerequisites); scripts/README.md:23 | check.sh on a machine with check.sh's documented prerequisites but no AWS CLI. | Probe `run-experiment-example` gets exit 3 instead of 4, so gate 9 fails. Fix: split the prerequisites as deploy.sh does (local tools first, cloud tools after the refusals), or list `aws` for check.sh. |
| I-21 | minor | no | infra-engineer | deploy.sh:418-419; plan.sh:304-306; cleanup.sh:400-402, :791-792 (raw Terraform output to the terminal, saved in `.plan.txt` and `.apply.txt`); plan.sh:401 and cleanup.sh:425 (`account_id` unmasked in the records) | The first apply prints `Outputs:` with data_bucket_name and ARNs that contain the account ID (outputs.tf:101, :148-156). | Breaks the standing three-layer rule ("printed: account ID and bucket names masked"; "persisted … no account ID"). It is not recorded as a deviation, and deploy.sh's usage, unlike plan.sh's, gives no warning. Exposure is limited to the human's terminal and gitignored `plans/`; the archive masks it. Fix: pipe through `mask_stream` once the account is known, and store a hash of the account for the record comparison. Or record a planned deviation. |
| I-22 | minor | no | infra-engineer | scripts/rollback.sh:98-99 (no INT/TERM traps), :332-335 (`cd` instead of refusing outside the root), :313-328 (time-named record written with `>`, no attempt ID, no identifier check, check-then-write race) | `bash /path/scripts/rollback.sh …` from a subfolder, or a TERM from `timeout`. | Does not meet decision 9's "Common to all ten" and "Records" rules: TERM exits 143 instead of 7, and the record name and atomicity differ from the other nine. 7b reported this as R7 and left it for review (9.10). |
| I-23 | minor | no | infra-engineer (the rule text needs a human amendment) | scripts/lib/identity.sh:129 (`git diff --quiet PC HEAD -- infra/terraform`); Terraform reads modules/storage/src/main/resources/s3vectors/index-spec-v1.json (environments/course/main.tf:134) | A commit after the signed plan that changes index-spec-v1.json. | `plan_usable` still accepts the plan. deploy.sh applies vector indexes from the old spec while the newly built code expects the new one (for example a dimension change), so ingest and queries fail at run time. Fix: include that path in the plan-validity diff, and amend the rule. |
| I-24 | minor | no | infra-engineer | run-bounded-pipeline.sh:1117 (`conf_sha="$(check_artifact_commit …)"`: a `die 4` inside the substitution becomes ERR, exit 5); deploy.sh:375-378 (a missing ECR tag raises ImageNotFoundException in `aws_json`, exit 5) | A Spark config in S3 with the wrong `scholarchain-commit`; a runtime-on plan whose image tag is not in ECR. | The script refuses as intended, but with exit 5 ("command failed") instead of the documented 4 ("refused"). Exit codes are a stated contract. Fix: `\|\| exit "$?"` after the substitution, and map ImageNotFound to 4. |

No finding on:
- the archive layout versus cleanup's rule (RK-E);
- the C-01 layout;
- plan, cleanup and deploy kinds being kept apart (a cleanup plan cannot be applied by deploy.sh or the reverse: `identity.sh:153`, `deploy.sh:269`, `cleanup.sh:446`);
- never-overwrite behavior in package, pipeline, ER upload, archive and run-experiment;
- I-02, I-06, I-10, I-16;
- literal identifiers in files.

## Coverage

Read in full:
- docs/agent-runs/phase-13/prompt.md
- scratchpad phase13-reports: review-report-b.md, agent-report-infra-tf.md, agent-report-infra-scripts-a.md, agent-report-infra-scripts-b.md
- diff-R-3.txt lines 1-462 (all 14 infra file diffs) and the removed cleanup normalize_archive_list (655-734). The other script diff hunks were not needed because every final script was read in full.
- scripts/lib/identity.sh, scripts/check.sh, scripts/package.sh, scripts/plan.sh, scripts/deploy.sh, scripts/smoke-test.sh, scripts/run-bounded-pipeline.sh (1-1353), scripts/run-experiment.sh, scripts/archive-evidence.sh, scripts/cleanup.sh, scripts/rollback.sh, scripts/README.md
- infra/terraform/modules/workflow/sfn.tf, infra/terraform/environments/course/outputs.tf, infra/terraform/modules/runtime/iam.tf (1-319)
- config/course.json, .gitignore, experiments/manifests/example-not-approved.json

Changed infra files reviewed through their complete diff hunks plus targeted reads:
- environments/course/{main,variables}.tf
- emr/{main,variables}.tf (with a Grep for every vcpu reference)
- identity/deployment.tf (also read 190-329)
- network/main.tf (with a Grep of the SG and egress rules)
- runtime/main.tf (also read 14-53)
- runtime/outputs.tf, vectors/main.tf, workflow/variables.tf
- infra/terraform/README.md (with a Grep of the script references)

Supporting evidence read in part (outside the slice):
- project/PackageCheck.scala 1-60, project/BuildIdentity.scala 22-35, project/DockerContext.scala (Grep), project/build.properties (Grep), docker/services/Dockerfile (Grep)
- StorageLoadMain.scala 55-94, ErMain.scala 35-145, RagIngestJob.scala and JsonlExport.scala constants (Grep)
- Pipeline.scala 140-219, QueryService.scala 62-93, JobRunner.scala (Grep), ScholarChainHadoop.scala (Grep)
- identity/emr.tf, identity/boundary.tf, data-lake/main.tf (Grep), experiments/README.md 18-59

In-slice files not re-read: the infra files unchanged since their Phase 12 review (git status shows no change):
- foundation/*
- network/{variables,outputs}.tf
- identity/{app-keys,emr,neptune-loader,boundary,main,outputs,variables}.tf (emr and boundary checked by Grep only)
- data-lake/*, dynamodb/*, neptune/*, observability/*
- vectors/{variables,outputs}.tf
- runtime/{ingress,autoscaling,variables}.tf
- workflow/{main,outputs}.tf
- emr/{outputs.tf,files/bootstrap.sh}
- every versions.tf
- course.tfvars, .terraform.lock.hcl (first lines only)

Producer reports agent-report-build/storage/rag/spark/experiment.md were not opened: each call-table row was checked against the source constants and producer code instead.
