# agent-report-infra-scripts-b (phase 13, session 2, step 7b)

STATUS: COMPLETE
OWNER: infra-engineer
WORK ORDER: docs/agent-runs/phase-13/prompt.md (sha256 60c943dccc86a6dc58ec074b95bbd63f04e2adca2f7a1c9a9abad266f28d36a7, checked with shasum), step 7b: "Two identity rules", decision 9 items 3, 4, 5, 6, 9, 10, 11, "Common to all ten", "Records".
FILES CREATED:
- /Users/brian/Developer/repos/scholarchain/scripts/README.md
FILES MODIFIED:
- /Users/brian/Developer/repos/scholarchain/scripts/lib/identity.sh (added find_plan_record :145 and require_plan_approval :178; nothing else changed)
- /Users/brian/Developer/repos/scholarchain/scripts/plan.sh
- /Users/brian/Developer/repos/scholarchain/scripts/deploy.sh
- /Users/brian/Developer/repos/scholarchain/scripts/smoke-test.sh
- /Users/brian/Developer/repos/scholarchain/scripts/run-bounded-pipeline.sh
- /Users/brian/Developer/repos/scholarchain/scripts/cleanup.sh
- rollback.sh: not changed (9.10).
COMMANDS RUN:
- shasum -a 256 docs/agent-runs/phase-13/prompt.md (matches); ls; wc -l; git status --short scripts; read-only grep -n/-o/-E on repository files (words not on the guard list).
- shellcheck (literal paths) on the ten scripts and the helper; also shellcheck -x -P SCRIPTDIR on the five changed scripts and the helper.
- terraform fmt -recursive -check infra/terraform; terraform -chdir=infra/terraform/environments/course validate.
- aws-docs MCP: read_documentation x4 (CLI ecs run-task, CLI logs get-log-events, CLI s3api head-object (options section not reached), Step Functions API TaskSucceededEventDetails); search_documentation x1. terraform MCP: not used (no HCL). aws-api MCP: 0 calls.
- OFF-LIST (read-only, no approval prompt appeared; reported as required): `cd /Users/brian/Developer/repos/scholarchain && ...` as the prefix of most Bash calls (to use repository-relative paths; the work order says agents never cd); `tr '\n' ' '`, `echo`, `printf`, and `for/while` loops inside two read-only checks (output names vs outputs.tf; options on `# contract:` lines). Nothing was written by them.
- No script was run, not even with --help; no bash, bash -n, docker, aws, curl, sbt.
TEST RESULTS: not applicable (no sbt; no Scala change).
VALIDATION RESULTS: see VALIDATION EVIDENCE. Extra static checks (read-only grep): every Terraform output name read by scripts/*.sh in the `$(out NAME)` / `.NAME.value` forms exists in outputs.tf (no MISSING line); the options on every `# contract:` line of run-bounded-pipeline.sh are in their constants and include every required one (ErMain.scala:35-55; StorageLoadMain.scala:67-85; RagIngestJob.scala:90-91).
ASSUMPTIONS:
- A1 `--snapshot ID` is required by every stage (the record's dataset snapshot); `--dblp-snapshot` is accepted as the same option so experiments/manifests/example-not-approved.json:21-28 parses; `--hadoop-output` defaults to s3://{data-bucket}/curated/hadoop (the example omits it; Hadoop jobs are run-addressed).
- A2 ER `--llm-model-id` defaults to `none-always-abstain`, `--llm-max-per-run 0` (adjudicator always-abstain; no model runs).
- A3 The ops task's `--out` is a new container-local folder `/tmp/scholarchain-LOADER-ATTEMPT_ID` (ephemeral storage 30 GiB); the program's masked summary lines are taken from the last 200 CloudWatch log events into the record (`outputs.program_lines`).
- A4 The Hadoop J09 export folder is `.../j09-graph-export/run=HEX` (JobRunner.scala:92-96 strips `hadoop.` from GraphExportJob.scala:83); every run folder with `_job.json` under the output root is also recorded (`run_ids.hadoop_runs`).
RISKS:
- R1 EMR cluster IDs are scanned (`j-[A-Z0-9]{6,}`) from TaskSucceeded outputs and TaskScheduled parameters of get-execution-history; the createCluster.sync result holds ClusterId (sfn.tf:212 Assign) but the event text shape is unverified at runtime.
- R2 smoke-test idempotency-replay requires the `Idempotent-Replayed: true` header (QueryService.scala:500) to pass through the API Gateway HTTP API; unverified; if dropped, the check fails with exit 6 (fail closed).
- R3 run-bounded-pipeline.sh --stage spark records every graph run under the out-root (`graph_runs_under_out_root`), not only this execution's: the graph job prints its run path only to the EMR step log.
- R4 One ops task at a time is a check-then-act (list-tasks then run-task), not a lock; one operator. The loader role ARN goes only into the private temporary overrides file; the record shows `{terraform output neptune_loader_role_arn}` (review-report-b.md RISKS).
- R5 ER stage compares ErMain's printed runDir with the given output root; ErRun uses toAbsolutePath.normalize (ErRun.scala:182-183), which does not resolve symlinks, so a symlinked TMPDIR should still match; unverified.
- R6 deploy.sh I-09 drain waits at most 1200 s for Application Auto Scaling to bring desired and running to 0; a slower scale-in exits 7 after the apply (counts printed; no release record).
- R7 rollback.sh is unchanged (9.10): it still cd's to the repository root instead of refusing elsewhere (common rule), and keeps its own masking. Open for R-3.
- R8 AWS CLI options marked unverified in the call table were not re-read in this step.
MANUAL ACTION REQUIRED: none in Phase 13 for these five scripts (they run in Phase 14 and later, deviation 12.1); the human runs plan.sh at the end of Phase 13 (decision 13.5). Optional `chmod +x scripts/*.sh` (files are run with `bash`).
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: R-3 (code-reviewer) reviews infra/**, config/**, scripts/** with the call tables of 7a and 7b.

## Requirement status (one line each)

- Two identity rules, plan validity, in plan.sh: done. plan.sh:249 read_git_state (PLAN_COMMIT=HEAD, PLAN_ID=new_attempt_id, name PLAN_COMMIT-PLAN_ID[-dirty], refuses an existing name), :406 record v2 (plan_commit, build_commit, plan_id; `commit` kept for readers), usage states the rule.
- Two identity rules, in deploy.sh: done. deploy.sh:267 find_plan (identity.sh:145 find_plan_record by sha256, exactly one), :299 plan_usable, :310 approval, release record :571.
- Two identity rules, in cleanup.sh: done. cleanup.sh:379 naming cleanup-PLAN_COMMIT-PLAN_ID, :444 check_cleanup_plan_local (lookup by sha256, plan_usable, approval), before any cloud call.
- Dirty trees: done. deploy.sh:253, cleanup.sh (check_clean_tree, require_clean_tree), run-bounded-pipeline.sh check_clean_tree refuse; smoke-test.sh:254 records `tree_dirty`; plan.sh names a dirty plan `-dirty` and records tree_clean false.
- 9.3 plan.sh: done (above); `Usage: scripts/plan.sh` first line; never applies.
- 9.4 deploy.sh lookup among plans/*.record.json by sha256, none or several refused: done, deploy.sh:267, identity.sh:145.
- 9.4 approval check (`git grep -E` on HEAD, anchored, only tracked human-gate.md/approvals.md under docs/agent-runs/; "proves committed, not who" in usage): done, identity.sh:178, deploy.sh:310, usage step 5.
- 9.4 I-09 runtime-off drain (bounded STABLE_TIMEOUT, counts printed, exit 7): done, deploy.sh:431, called from verify_deployment.
- 9.4 release record under releases/ (tracked), Phase 14 order in usage: done, deploy.sh:571 (write_record_atomic; identifiers checked), usage "Phase 14 order".
- 9.4 refusals before any cloud call (check.sh probe: wrong 64 hex -> exit 4 `ERROR: deploy.sh ... exit=4`): done, deploy.sh:607 main order (local prerequisites, clean tree, lookup, validity, approval, then cloud prerequisites).
- 9.5 smoke-test.sh healthz/readyz/bounded sync query with at least one claim/evidence of a returned claim/idempotency replay and conflict/401 error body: done, smoke-test.sh:438 (auth-error), :457 (query), :480 (evidence), :503 (replay and conflict); health and ready before them.
- 9.5 API key handling exact (only SCHOLARCHAIN_SERVICE_API_KEY; `printf` to `curl --config -`; bodies from files; no -v/--trace/set -x/-H key; status and error code only; control char, `"` and backslash refused): done, smoke-test.sh:202 check_key (before any network call; exit 3 unset), :319 request (:335 config line).
- 9.6 five stages: done. upload-input :849, hadoop :869, er :988 (with check_er_jar :928 run before any cloud call, verify_download :949), spark :1103 (require_er_succeeded :1089; --export-source-run/--export-root), load :1249 (check_ops_task :1163 image tag = build commit and one task; build_ops_command :1198 from the constants).
- 9.6 every stage: dirty-tree refusal, build commit as code version, jar metadata scholarchain-commit check (:630), locations inside the bucket and prefix (:594), run record evidence/runs/ATTEMPT_ID-STAGE.json written started then final by traps (:732, :744), RUN-RECORD line (:753), run_ids.execution_name and run_ids.emr_cluster_ids (:769, :784), EXECUTION line kept (:797): done.
- 9.6 ER details (listing size bound, 3x free disk, new local folder named by attempt, manifest check against _job.json outputs, ErMain with every required argument, upload to curated/er/ATTEMPT_ID/ with _manifest.json and _SUCCESS last, no resume): done, :988-1085.
- 9.9 cleanup.sh sources the helper and uses archive_keys: done, cleanup.sh:253 enter_repo_root, :295 normalize_archive_list now calls archive_root/archive_keys (identity.sh); its own copy removed.
- 9.9 plan-validity naming and approval check: done (above).
- 9.9 I-08 usage (human's own admin profile, not the deployment role): done, cleanup.sh:45 "Who runs it", requirements line :120.
- 9.9 I-15 task-definition revisions listed separately, not counted toward exit 6: done, cleanup.sh:576 expected_leftover (families from output ecs_task_definition_families), :591 list_tagged, :797 check_leftovers.
- 9.10 rollback.sh: no change (see R7).
- 9.11 scripts/README.md (prerequisites with versions, GNU coreutils, Docker with buildx, registry network for check.sh init; one table of all ten scripts; full reproduction order; zsh-safe one-line commands): done, scripts/README.md.
- Common to all ten (for the five 7b scripts): strict mode, no prompt except cleanup's confirmation, STEP:/RESULT:, exit codes in usage, timeouts on cloud and network calls, retries only for reads and put-if-absent uploads (and GET in smoke-test), masked prints, repository-root refusal: done in plan.sh, deploy.sh, smoke-test.sh, run-bounded-pipeline.sh, cleanup.sh. rollback.sh: see R7.
- Records (smoke-test.sh and run-bounded-pipeline.sh): attempt ID names, started then final via EXIT/INT/TERM traps, never overwritten, uploaded keys listed: done.

## Deviations (for the gate)

- deviation: `--export-root PREFIX` writes the export under PREFIX/ATTEMPT_ID/ (the state-machine field exportRoot is PREFIX/ATTEMPT_ID), so export keys carry the attempt ID (Records rule) and a rerun never meets JsonlExport's "existing run" refusal.
- deviation: release records are named releases/course/ATTEMPT_ID.json (was UTC-short-commit); rollback.sh reads any name.
- deviation: smoke-test.sh writes its record as evidence/runs/ATTEMPT_ID-smoke-test.json (the Records rule lists no smoke folder).
- deviation (open, R7): rollback.sh not brought to the "refuse outside the repository root" common rule (9.10 says no change unless review finds one).

## Status lines

- scripts/lib/identity.sh: static: shellcheck clean; structure reviewed: two functions added (find_plan_record, require_plan_approval); runtime: untested; human execution required.
- scripts/plan.sh: static: shellcheck clean; structure reviewed: naming PLAN_COMMIT-PLAN_ID, record fields read by deploy.sh, cleanup.sh and archive-evidence.sh; runtime: untested; human execution required.
- scripts/deploy.sh: static: shellcheck clean; structure reviewed: refusal order before cloud calls, plan_usable, approval, I-09 drain, release record fields read by rollback.sh; runtime: untested; human execution required.
- scripts/smoke-test.sh: static: shellcheck clean; structure reviewed: routes, headers, status codes, error body and idempotency against modules/services (call table); key handling; runtime: untested; human execution required.
- scripts/run-bounded-pipeline.sh: static: shellcheck clean; structure reviewed: state-machine input shape (sfn.tf), Hadoop _job.json, ER and Spark layouts, option constants, Terraform outputs; runtime: untested; human execution required.
- scripts/cleanup.sh: static: shellcheck clean; structure reviewed: archive_keys, lookup, validity, approval, I-08, I-15; runtime: untested; human execution required.
- scripts/rollback.sh: static: shellcheck clean; structure reviewed: release record fields it reads (kind, environment, region, commit, ecs_cluster, services) still written by deploy.sh; runtime: untested; human execution required.

## Script table

The authoritative table (command lines, environment variables by name, approval points, generated evidence, rollback behavior) is in /Users/brian/Developer/repos/scholarchain/scripts/README.md, section "The ten scripts". Not repeated here.

## Call table (every new or changed external call of the 7b scripts)

| Script:line | Call | Relied-on thing (file:line) | Docs |
|---|---|---|---|
| identity.sh:145 | jq `.kind`, `.plan_sha256` of plans/*.record.json | plan.sh:406 (plan record), cleanup.sh:416 (cleanup record) | n/a |
| identity.sh:178 | `git grep -h -E '^APPROVED-PLAN-SHA256: HEX$' HEAD -- ':(glob)docs/agent-runs/**/human-gate.md' ':(glob)docs/agent-runs/**/approvals.md'` | same form as run-experiment.sh:384 | n/a (git pathspec glob magic; unverified at runtime) |
| plan.sh / deploy.sh / cleanup.sh | `aws sts get-caller-identity` (.Account) | unchanged from Phase 12 | unverified (not re-read) |
| plan.sh:249, deploy.sh:253, cleanup.sh | helper head_commit, build_commit, tree_dirty_flag, require_clean_tree, plan_usable, new_attempt_id | identity.sh:50, :64, :76, :91, :109, :284 | n/a |
| deploy.sh:267-297 | record fields `.environment`, `.tree_clean`, `.region`, `.plan_file`, `.plan_commit` (fallback `.commit`), `.plan_id`, `.account_id` | plan.sh:406 | n/a |
| deploy.sh | outputs `.ecr_repository_name.value`, `.ecs_cluster_name.value`, `.ecs_service_names.value[ROLE]`, `.runtime_enabled.value` | outputs.tf:32, :37, :42, :12 | n/a |
| deploy.sh:431 | `aws ecs describe-services --cluster --services` (.services[].desiredCount/.runningCount) | unchanged use (Phase 12) | unverified (not re-read) |
| deploy.sh | `aws ecr describe-images --repository-name --image-ids imageTag=T` | ECR IMMUTABLE tags, runtime/main.tf:78 | https://docs.aws.amazon.com/cli/latest/reference/ecr/describe-images.html (read in 7a) |
| deploy.sh | `terraform show -json`, `apply -input=false PLAN`, `output -json` with `-chdir` | work order "Common to all ten" | n/a |
| deploy.sh:571 | write_record_atomic (releases/course/ATTEMPT_ID.json) | identity.sh:301; rollback.sh:189-210 reads kind, environment, region, commit, ecs_cluster | n/a |
| cleanup.sh:295 | archive_root, archive_keys | identity.sh:382, :412 | n/a |
| cleanup.sh:576 | output `.ecs_task_definition_families.value` | outputs.tf:47 | n/a |
| cleanup.sh:591 | `aws resourcegroupstaggingapi get-resources --tag-filters` (.ResourceTagMappingList[].ResourceARN) | unchanged use (Phase 12) | unverified (not re-read) |
| smoke-test.sh | output `.api_url.value` | outputs.tf:17 | n/a |
| smoke-test.sh | GET /healthz, /readyz (open), POST /v1/query (FullKey), GET /v1/evidence/{id} (AnyKey) | Routes.scala:30-52; HttpAdapter.scala:197-222 | n/a |
| smoke-test.sh:335 | header X-Api-Key via `curl --config -` | HttpAdapter.scala:220 | unverified (curl manual; no doc tool) |
| smoke-test.sh | header Idempotency-Key, key form `[A-Za-z0-9._:-]{1,128}` | HttpAdapter.scala:187; QueryService.scala:218-223 | n/a |
| smoke-test.sh:438 | 401 body `{error: "UNAUTHENTICATED", message, traceId, retryable: false, details}` | Errors.scala:25-26; HttpAdapter.scala:117, :222; Models.scala:12-18 | n/a |
| smoke-test.sh:457 | request `{question, mode: "sync", output: {includeEvidence: true}}`; response `.queryId`, `.answer.claims`, `.answer.abstained`, `.links.evidence[0]` | QueryRequestParser.scala:150-226; Models.scala:33-46, :127, :133-138; QueryService.scala:69-71 (link form `/v1/evidence/ID?window=S-E`) | n/a |
| smoke-test.sh:480 | `.evidenceId`, `.evidenceType` | Models.scala:173-176 | n/a |
| smoke-test.sh:503 | replay 200 with `Idempotent-Replayed: true`; conflict 409 `IDEMPOTENCY_CONFLICT` | QueryService.scala:488, :498-500; Errors.scala:43-48 | n/a |
| run-bounded-pipeline.sh | outputs `data_bucket_name`, `vector_bucket_name`, `data_prefixes.value.{raw,curated,graph,artifacts}`, `state_machine_arn`, `ecs_cluster_name`, `ops_task_definition_family`, `ops_container.value.{name,log_stream_prefix}`, `ecr_repository_url`, `neptune_loader_role_arn`, `app_subnet_ids`, `ops_security_group_id`, `log_group_names.value.ops` | outputs.tf:101, :116, :106, :79, :37, :52, :57, :27, :131, :62, :67, :158; modules/data-lake/main.tf:19-27 | n/a |
| run-bounded-pipeline.sh:784 | `aws stepfunctions start-execution --state-machine-arn --name --input file://` (not retried); input `{codeVersion, hadoop{jar,output,dblp,dblpSnapshot,extraArgs}, spark{jar,config,hadoopExport,identityAssignments,outRoot,exportSourceRun,exportRoot}}` | sfn.tf:24-43, :125-133 (ExportInputCheck), :228, :303, :330 | unverified (not re-read; Phase 12 use) |
| run-bounded-pipeline.sh:784 | `aws stepfunctions describe-execution --execution-arn` (.status, .error, .cause, .startDate) | Phase 12 use | unverified (not re-read) |
| run-bounded-pipeline.sh:769 | `aws stepfunctions get-execution-history --execution-arn --max-items 2000` (.events[].taskSucceededEventDetails.output, .taskScheduledEventDetails.parameters) | sfn.tf:212, :268 (ClusterId in the createCluster.sync result) | https://docs.aws.amazon.com/step-functions/latest/apireference/API_TaskSucceededEventDetails.html (event shape); CLI options unverified |
| run-bounded-pipeline.sh:630 | `aws s3api head-object --bucket --key` (.Metadata["scholarchain-commit"], ["scholarchain-sha256"]) | package.sh:744 (metadata written by --publish) | page https://docs.aws.amazon.com/cli/latest/reference/s3api/head-object.html opened, options section not reached: unverified |
| run-bounded-pipeline.sh:647 | `aws s3api put-object --bucket --key --body --if-none-match '*' --checksum-algorithm SHA256 --metadata k=v,...` | same as package.sh:741-744 | https://docs.aws.amazon.com/cli/latest/reference/s3api/put-object.html (checked in 7a) |
| run-bounded-pipeline.sh | `aws s3api list-objects-v2 --bucket --prefix` (.Contents[].Key/.Size; CLI pagination) | same as archive-evidence.sh:462 | unverified (not re-read) |
| run-bounded-pipeline.sh:988 | `aws s3api get-object --bucket --key OUTFILE` | - | unverified (not re-read) |
| run-bounded-pipeline.sh:949 | `_job.json` `.outputs[] {path, length, sha256}`, hidden `_`/`.` segments | JobRunner.scala:83, :209, :227-251, :266 | n/a |
| run-bounded-pipeline.sh:1044 | `java -Xmx<N>g -cp ER_JAR org.scholarchain.er.run.ErMain` --input --output-root --decision-time --seed --code-version --llm-model-id --llm-max-per-run --adjudicator (`# contract: ErMain`); stdout `[er] runId=`, `[er] runDir=` | ErMain.scala:35-55, :108-111, :137-139; ErRun.scala:182-183 | n/a |
| run-bounded-pipeline.sh:928 | package record `.build_commit`, `.jars[] select(.module=="entityResolution") | .path/.sha256/.build_info.{buildCommit,buildDirty}` | package.sh:418-421 | n/a |
| run-bounded-pipeline.sh:1089, :1103 | ER layout `run=HEX/decisions/identity_assignment` with `_SUCCESS`, `_manifest.json` | spark Pipeline.scala:148-151 | n/a |
| run-bounded-pipeline.sh:1103 | markers `_run_manifest.json` (graph run), `_export_manifest.json` (export run) | spark Output.scala:247; JsonlExport.scala:229 | n/a |
| run-bounded-pipeline.sh:1163 | `aws ecs describe-task-definition --task-definition FAMILY` (.taskDefinition.family/.revision/.containerDefinitions[].image) | runtime/main.tf:29 (image = repo:tag), :202-245 | unverified (not re-read) |
| run-bounded-pipeline.sh:1163 | `aws ecs list-tasks --cluster --family --desired-status RUNNING` | - | unverified (not re-read) |
| run-bounded-pipeline.sh:1212-1229 | StorageLoadMain SUBCOMMAND options (`# contract: StorageLoadMain SUB`), RagIngestJob options (`# contract: RagIngestJob`); `--config /opt/scholarchain/config/course.json` | StorageLoadMain.scala:61-85; RagIngestJob.scala:90-91; runtime/variables.tf:175-179 | n/a |
| run-bounded-pipeline.sh:1249 | `aws ecs run-task --cluster --task-definition FAMILY:REV --launch-type FARGATE --count 1 --network-configuration file:// --overrides file:// --started-by --propagate-tags TASK_DEFINITION` (not retried); overrides `{containerOverrides:[{name, command}]}`; network `{awsvpcConfiguration:{subnets, securityGroups, assignPublicIp:"DISABLED"}}` | runtime/main.tf:189-199, :227 (entryPoint java -cp app.jar) | https://docs.aws.amazon.com/cli/latest/reference/ecs/run-task.html (synopsis: all options present) |
| run-bounded-pipeline.sh:1249 | `aws ecs describe-tasks --cluster --tasks ARN` (.tasks[0].lastStatus, .containers[].exitCode, .stopCode, .stoppedReason) | - | unverified (not re-read) |
| run-bounded-pipeline.sh:1315 | `aws logs get-log-events --log-group-name --log-stream-name --limit 200 --no-start-from-head --no-paginate`; stream `PREFIX/NAME/TASK_ID`; lines `[storage-load] ...`, `[rag-ingest] ...` | outputs.tf:57 (ops_container); agent-report-storage.md handoff (stdout lines); RagIngestJob stdout lines (agent-report-rag.md handoff) | https://docs.aws.amazon.com/cli/latest/reference/logs/get-log-events.html (synopsis checked) |
| run-bounded-pipeline.sh (stdout) | `RUN-RECORD: evidence/runs/ATTEMPT_ID-STAGE.json`, `EXECUTION: name=NAME started=...`; record `.run_ids.execution_name`, `.run_ids.emr_cluster_ids` | run-experiment.sh:537-568 (consumer) | n/a |

## Cost estimate

$0 in this step (nothing ran). Phase 14+: smoke-test.sh one bounded sync query (one model call; the replay and the conflict call do not run the workflow); run-bounded-pipeline.sh: EMR clusters bounded by Terraform (vCPU budget, managed-scaling max, idle end), one Fargate ops task per load, S3 requests and storage (cents); the ER stage runs locally.

## Required environment variables by name

- smoke-test.sh: `SCHOLARCHAIN_SERVICE_API_KEY` (required; never printed; passed to curl on standard input).
- plan.sh, deploy.sh, cleanup.sh, run-bounded-pipeline.sh: `AWS_PROFILE` or the standard AWS credential chain; `TMPDIR` (read if set; run-bounded-pipeline.sh --stage er places its kept download folder there).

## Proposed later

- An `--attempt-id` passed from run-bounded-pipeline.sh to RagIngestJob (rag's own proposal) so the report copy key carries the attempt ID.
- A DynamoDB-based single-writer lock for ops tasks (Phase 7 m3, phase-14) instead of the check-then-act task listing.

## INTERFACE HANDOFF

- Files changed: scripts/lib/identity.sh (two functions), plan.sh, deploy.sh, smoke-test.sh, run-bounded-pipeline.sh, cleanup.sh; new scripts/README.md. No sbt source.
- New helper signatures: `find_plan_record PLANS_DIR KIND SHA256` -> path on stdout; 2 malformed sha, 4 none or several (identity.sh:145). `require_plan_approval SHA256` -> 0 approved; 2 malformed; 4 no line (identity.sh:178).
- Plan record v2 (plan.sh:406) and cleanup record v2 (cleanup.sh:416): kind, version 2, environment, plan_commit, commit (= plan_commit), build_commit, plan_id, tree_clean, plan_file (`plans/NAME.tfplan`), plan_sha256, account_id (unmasked, gitignored folder), region, created_utc; plan record also variable_overrides; cleanup record also evidence_archive_sha256 and evidence_archive_reports_entries. archive-evidence.sh reads these fields unchanged.
- Release record v2 (deploy.sh:571): kind scholarchain-release, attempt_id, commit (= HEAD), head, plan_commit, build_commit, plan_id, plan_sha256, region, applied_utc, runtime_enabled, image {repository, tag, digest}, ecs_cluster, services, verified.
- Pipeline run record (run-bounded-pipeline.sh record_json): kind scholarchain-pipeline-run-record, status, attempt_id, stage, head, build_commit, code_version, dataset_snapshot, region, started_utc, ended_utc, exit_code, failed_step, signal, inputs, outputs, run_ids {execution_name, emr_cluster_ids, hadoop_runs, hadoop_j09_export_runs, er_run_id, er_output_root, graph_runs_under_out_root, export_source_run, export_runs, ops_task_id}, configuration {file, sha256}, uploaded_keys, result.
- check.sh probe contracts kept: every usage starts `Usage: scripts/NAME.sh`; deploy.sh unknown sha -> `ERROR: deploy.sh step=plan-lookup exit=4` before cloud; smoke-test.sh without the key -> exit 3 naming SCHOLARCHAIN_SERVICE_API_KEY before any network call; cleanup.sh --list without --evidence-archive -> exit 4 naming `evidence-archive` (parse_args, unchanged).
- Thread-safety: n/a (sequential scripts; one private temporary folder per run). Limits: see each usage text.

VALIDATION EVIDENCE
- fmt: terraform fmt -recursive -check infra/terraform -> 0, no files listed
- validate: terraform -chdir=infra/terraform/environments/course validate -> "Success! The configuration is valid."
- shellcheck: shellcheck scripts/check.sh scripts/package.sh scripts/plan.sh scripts/deploy.sh scripts/smoke-test.sh scripts/run-bounded-pipeline.sh scripts/run-experiment.sh scripts/archive-evidence.sh scripts/cleanup.sh scripts/rollback.sh scripts/lib/identity.sh -> 0; clean (also shellcheck -x -P SCRIPTDIR on plan, deploy, smoke-test, run-bounded-pipeline, cleanup and the helper -> 0)
- jq: not applicable (no JSON file written or changed)
- finished: after the last command above
- files changed after these runs: no
