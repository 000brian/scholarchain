# agent-report-infra-scripts-a (phase 13, session 2, step 7a)

STATUS: COMPLETE
OWNER: infra-engineer
WORK ORDER: docs/agent-runs/phase-13/prompt.md (sha256 60c943dccc86a6dc58ec074b95bbd63f04e2adca2f7a1c9a9abad266f28d36a7, checked with shasum), step 7a: "Two identity rules", decision 9 items 0, 1, 2, 7, 8, "Common to all ten", "Records"; review-report-b.md C-03.
FILES CREATED:
- /Users/brian/Developer/repos/scholarchain/scripts/lib/identity.sh
- /Users/brian/Developer/repos/scholarchain/scripts/check.sh
- /Users/brian/Developer/repos/scholarchain/scripts/package.sh
- /Users/brian/Developer/repos/scholarchain/scripts/run-experiment.sh
- /Users/brian/Developer/repos/scholarchain/scripts/archive-evidence.sh
FILES MODIFIED: none (cleanup.sh untouched, as ordered; step 7b switches it to `archive_keys`).
COMMANDS RUN:
- shasum -a 256 docs/agent-runs/phase-13/prompt.md (matches); ls; wc -l; git log -1 --format=%H -- <build inputs> (read-only); grep -n/-o/-E on repository files (words not on the guard list)
- shellcheck (literal paths; also with -f gcc, -S error/warning, -x -P SCRIPTDIR) on scripts/**, .claude/hooks/*.sh, .claude/hook-tests/*.sh, datasets/scripts/*.sh, infra/terraform/modules/emr/files/bootstrap.sh (baseline for gate 4)
- terraform fmt -recursive -check infra/terraform; terraform -chdir=infra/terraform/environments/course validate
- aws-docs MCP: read_documentation x3 (CLI reference s3api put-object, cloudwatch get-metric-data, ecr describe-images). terraform MCP: not used (no HCL). aws-api MCP: 0 calls.
- OFF-LIST (read-only, no approval prompt appeared; reported as required): one `sed -n '540,610p' build.sbt` piped with grep/ls; one `cat .gitignore`; one `uniq -c` and several `tr` inside read-only pipes (counting shellcheck findings, joining grep output). Nothing was written by them.
- No script was run, not even with --help; no bash, bash -n, docker, aws, curl.
TEST RESULTS: not applicable (no sbt; no Scala change).
VALIDATION RESULTS: see VALIDATION EVIDENCE.
ASSUMPTIONS:
- A1 Records never overwritten (Records rule) vs. "package-record.json ... and (after --publish) the ECR digest and the logical S3 keys" (9.2): publish facts go into a sibling record `evidence/packages/SHA40/publish/ATTEMPT_ID.json`, which names package-record.json. Listed as `deviation` below for the gate.
- A2 Started/final records use two names: `X.started.json` then `X.json` (write_record_atomic refuses an existing file, so a started record is never replaced). Readers treat a started record without its final record as an incomplete attempt.
- A3 Repackaging the same build commit: package.sh refuses while `evidence/packages/SHA40/package-record.json` exists (the human moves the folder aside); the publish step verifies that local jars and image still match that record.
- A4 Jars are found by glob (`modules/<m>/target/scala-*/scholarchain-<m>-assembly-*.jar`, exactly one each after `sbt clean`), not by the hard-coded version from the build report.
- A5 S3 artifact keys chosen here (7b's defaults must use them): `artifacts/SHA40/jars/{hadoop,entity-resolution,spark-analytics,services}.jar`, `artifacts/SHA40/spark-conf/<file>.json` (every tracked `modules/spark/conf/*.json`, today `phase06.json`, the Spark job config sfn.tf:36 expects).
- A6 Bash 3.2 compatible code (macOS /bin/bash): no associative arrays, no mapfile, empty-array-safe expansions.
RISKS:
- R1 check.sh gate 4 (shellcheck, default severity, `-x -P SCRIPTDIR`) will FAIL today on factory-engineer's files: .claude/hooks/verify-tests-ran.sh (1 error, SC1087), guard-dangerous.sh (2 warnings SC2088 plus notes), validate-agent-files.sh and both self-tests (notes: SC2016, SC2086, SC2012, SC2329, SC1003, SC2020). scripts/**, datasets/scripts/*.sh and bootstrap.sh are clean with `-x -P SCRIPTDIR` (observed exit 0). Gap for factory-engineer, or a human decision to lower the gate's severity for `.claude/**`. Not changed by me.
- R2 check.sh's probes depend on step 7b's scripts: `Usage: scripts/NAME.sh` as the first usage line; deploy.sh exit 4 with `ERROR: deploy.sh ... exit=4` for an unknown 64-hex checksum before any cloud call; smoke-test.sh exit 3 naming SCHOLARCHAIN_SERVICE_API_KEY before any network call; cleanup.sh exit 4 naming `evidence-archive` (true today, cleanup.sh:235). Listed in INTERFACE HANDOFF.
- R3 The run-experiment example probe needs a clean tree after gates 1-8 in the fresh clone; it is reported `skipped` (not failed) on a dirty tree. If a gate leaves an untracked file in a fresh clone the probe is skipped and the record shows it.
- R4 Docker CLI behavior (buildx imagetools output "Platform: linux/arm64", RepoDigests after push with the containerd image store, `docker create --platform`) is from tool knowledge, not a document: `unverified`. If RepoDigests does not list the pushed digest, a re-publish of an already pushed tag fails closed (exit 6) instead of being a no-op.
- R5 `sc_epoch_to_utc` tries GNU `date -d @N`, then BSD `date -r N`; runtime untested on both.
- R6 check.sh gate 6 runs `terraform init -backend=false` also in the human's main checkout if run there; that re-initializes modules/providers without touching the backend (expected, unverified).
- R7 check.sh gate 8 is a text check: output names only in the forms `$(out NAME)`, `$(out_json NAME)` and `.NAME.value`; options only on lines marked `# contract: ...`. A script that reads outputs or passes options in another form escapes the gate; R-3 reviews by hand too.
MANUAL ACTION REQUIRED (human, after the run): run check.sh in a fresh clone (decision 13.3), package.sh without --publish (13.4), archive-evidence.sh --local-only (13.6); package.sh --publish and run-experiment.sh are Phase 14 and later. Optional `chmod +x scripts/*.sh` (files are mode 644; run them with `bash`).
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: step 7b (infra-engineer, fresh invocation) reads INTERFACE HANDOFF; R-3 reviews scripts/**, including the call table below.

## Requirement status (one line each)

- Two identity rules, build commit: done. scripts/lib/identity.sh:34 (path list = project/BuildIdentity.scala:24-34), :50 build_commit; package.sh:402 check_jar compares each jar's buildCommit with it.
- Two identity rules, plan validity: done (helper). identity.sh:109 plan_usable (clean tree, ancestor via `git merge-base --is-ancestor`, `git diff --quiet PLAN_COMMIT HEAD -- infra/terraform`); used by plan.sh/deploy.sh/cleanup.sh in step 7b.
- Two identity rules, dirty trees: done for 7a scripts. package.sh:270 refuses (or `--allow-dirty`, local `-dirty` tag, refused with `--publish` :222); run-experiment.sh:296 refuses; check.sh:298 and archive-evidence.sh:267 record the flag.
- 9.0 helper sourced, no load side effects: done. identity.sh:1 `# shellcheck shell=bash`, :27-30 load guard, no `set`, no trap, no output at load.
- 9.0 functions: done. build_commit :50, plan_usable :109, require_clean_tree :91, mask_account :158, logical_s3 :203, check_no_identifiers :253, new_attempt_id :284, write_record_atomic :301, archive_keys :412 (+ archive_root :382, strip_dot_slash :372, moved logic from cleanup.sh:270-326 unchanged; output to stdout instead of a temp file). Extras used by the scripts: head_commit :64, tree_dirty_flag :76, register_identifiers :145, mask_text :169, mask_stream :185, resolve_s3 :224, sc_sha256 :340, sc_bytes :349, sc_utc_now :354, sc_epoch_to_utc :359.
- Common: strict mode, exit codes 0/2/3/4/5/6/7 in usage, STEP:/RESULT: markers, timeouts on cloud/network calls, retries only for reads and uploads, masked prints, root-only: done in all four (e.g. check.sh:9, :241; package.sh:11, :229; run-experiment.sh:11, :265; archive-evidence.sh:10, :230).
- Records: started record before the first action, final via EXIT/INT/TERM traps (succeeded/failed with step and exit code/timed-out), attempt IDs in names, never overwritten: done. check.sh:298/:331; package.sh:291/:307; run-experiment.sh:436/:682; archive-evidence.sh:267/:308.
- 9.1 check.sh prerequisites (java 17, sbt, terraform 1.17.1 exactly, shellcheck, jq, git, python3, timeout/gtimeout): done, check.sh:263.
- 9.1 gates 1-7: done. constitution :411, json :417 (lists files; excludes */src/test/* and tests/*), hook-syntax :436, shell :466, datasets :480, terraform :486 (fmt, init -backend=false -input=false -lockfile=readonly, validate), sbt :525 (check, then packageCheck; counts parsed; suites from report XML newer than the gate start :504).
- 9.1 gate 8 script contracts (static): done, check.sh:643 (outputs vs outputs.tf; classes vs project/PackageCheck.scala; options on `# contract:` lines vs constants read by :571/:596; sfn.tf JsonlExport step). GraphAnalyticsJob and ScholarChainHadoop have no option constant: reported "not checked".
- 9.1 gate 9 offline probes: done, check.sh:733 probe (env -u ... AWS_CONFIG_FILE=/dev/null AWS_SHARED_CREDENTIALS_FILE=/dev/null AWS_EC2_METADATA_DISABLED=true, timeout 120, TMPDIR in the private temp folder), :822 the seven probe kinds, :762 archive layout via archive_keys.
- 9.1 summary table, non-zero exit, --fail-fast, --record-dir (gitignored if inside the repo), record content: done, check.sh:383, :871 summary, :298, :331.
- 9.2 package.sh refuse dirty / --allow-dirty / refused with --publish: done :270, :222.
- 9.2 build from scratch + exact buildx args: done :374, :475 (args = build report INTERFACE HANDOFF and project/DockerContext.scala:41-56).
- 9.2 checks of what ships (build info, app.jar equality, config = git show SHA40:config/course.json, image platform, docker create/cp/rm by ID, base image lists linux/arm64): done :402, :444, :485, :345.
- 9.2 package-record.json (atomic): done :508.
- 9.2 --publish (outputs, refusals, login via stdin, push no-op/fail on other digest, no-overwrite uploads with scholarchain-commit metadata, ECR digest and logical keys recorded): done :589, :613, :664, :728, :783. Record location: deviation (A1).
- 9.7 run-experiment.sh refusal order (dirty, untracked, sha256, example, v1 incl. region and 5 USD cap, approval): done :296, :303, :310, :319, :326, :381.
- 9.7 three guarantees in usage: done (usage "Three different guarantees").
- 9.7 declared limits printed, action under timeout maxDurationSeconds, pipeline/command: done :487, :499.
- 9.7 raw data under evidence/experiment-runs then moved to experiments/raw/MANIFEST_ID/ATTEMPT_ID read-only: done :436, :682.
- 9.7 run-record.json content, metrics get-metric-data window +-300 s, succeeded-metrics-failed: done :455, :596, :682.
- 9.7 local timeout: prints executions and tagged running EMR clusters, exit 7, stops nothing: done :572, :499.
- 9.8 archive-evidence.sh NAME.partial -> NAME -> NAME.tar + .sha256, refuse existing NAME: done :267, :563.
- 9.8 layout MANIFEST.json / repo/ / plans/ (masked, no .tfplan or plan text) / reports/ (S3) / metrics/ (S3): done :523, :347, :376, :462, :506.
- 9.8 --local-only: no AWS call, manifest says reports/metrics absent: done :523 (main skips read_outputs, copy_reports, copy_metrics).
- 9.8 key traces: done (section below).
- C-03 (minor, interface): decided and documented in run-experiment.sh usage, "Logical locations and placeholders" (section below).

## Status lines

- scripts/lib/identity.sh: static: shellcheck clean; structure reviewed: git log/status/merge-base/diff/cat-file, grep -F, jq empty, ln-based no-clobber rename, archive key rule copied from cleanup.sh:270-326; runtime: untested; human execution required.
- scripts/check.sh: static: shellcheck clean; structure reviewed: nine gates, sbt output lines (build.sbt:527, :575), test-report XML attributes, static contract parsing against outputs.tf, PackageCheck.scala and four option constants, seven probe kinds; runtime: untested; human execution required.
- scripts/package.sh: static: shellcheck clean; structure reviewed: sbt task names (build.sbt:412, :507, :601), DockerContext layout and buildx args, ledger key, aws ecr/s3api/sts options (cited below), Docker CLI (unverified); runtime: untested; human execution required.
- scripts/run-experiment.sh: static: shellcheck clean; structure reviewed: manifest v1 rules against experiments/README.md:64-81 and the example manifest, git grep approval pathspecs, get-metric-data options (cited); runtime: untested; human execution required.
- scripts/archive-evidence.sh: static: shellcheck clean; structure reviewed: layout traced against archive_keys (= cleanup.sh's rule), plan record fields of plan.sh:361-375, s3api list/get options; runtime: untested; human execution required.

## C-03 decision (for experiment-engineer's README alignment; do not edit experiments/** here)

- `{data-bucket}` in `action.args`: run-experiment.sh passes args UNCHANGED (matches experiments/README.md:55). scripts/run-bounded-pipeline.sh (step 7b) resolves `s3://{data-bucket}/KEY` with `resolve_s3` (identity.sh:224) from terraform output `data_bucket_name`, and writes logical locations back into its records (`logical_s3`). A `command` action resolves its own arguments.
- Metric placeholders: run-experiment.sh resolves them at export time: `{state-machine-arn}` from terraform output `state_machine_arn`; `{emr-cluster-id}` from the nested run records (`.run_ids.emr_cluster_ids[]`, one query per cluster). Unresolvable metrics are listed and not queried; the run becomes `succeeded-metrics-failed`. Records keep the placeholder text.
- README text to align: README:20 (who resolves), README:55 (unchanged, resolved by run-bounded-pipeline.sh), README:58 (placeholder resolution and the `RUN-RECORD:` contract), README:101 (`run-record.started.json` then `run-record.json`; nested records copied to `nested/`; `metrics/queries.json`; metrics exported only after a successful action).

## Call table (every external call of the 7a scripts)

| Script:line | Call | Relied-on thing (file:line) | Docs |
|---|---|---|---|
| identity.sh:50 | `git log -1 --format=%H -- PATHS` | project/BuildIdentity.scala:24-34 InputPaths | n/a |
| identity.sh:109 | `git cat-file -e`, `git merge-base --is-ancestor`, `git diff --quiet A B -- infra/terraform` | work order "Two identity rules" | n/a |
| check.sh:411 | `bash .claude/hooks/validate-agent-files.sh` | CLAUDE.md "Commands" | n/a |
| check.sh:436 | `bash .claude/hook-tests/guard-selftest.sh`, `stop-hook-selftest.sh` | docs/agents.md "Self-tests" row | n/a |
| check.sh:480 | `bash datasets/scripts/validate.sh` | datasets/scripts/validate.sh:1-21 | n/a |
| check.sh:486 | `terraform fmt -recursive -check`, `-chdir=... init -backend=false -input=false -lockfile=readonly`, `validate` | work order 9.1 gate 6 | n/a |
| check.sh:525 | `sbt -batch check` -> line `Updated ... (module_tests=N, contract_tests=M)` | build.sbt:575 | n/a |
| check.sh:525 | `sbt -batch packageCheck` -> `packageCheck: ok` | build.sbt:527 | n/a |
| check.sh:504 | test-report XML `<testsuite name tests failures errors skipped>` | sbt JUnit XML (docs/agents.md stop-hook row) | n/a |
| check.sh:643 | output names `^output "NAME" ` | infra/terraform/environments/course/outputs.tf | n/a |
| check.sh:643 | class list `"org.scholarchain...."` | project/PackageCheck.scala:18-33 | n/a |
| check.sh:596 | ErMain `val Required`/`val Optional` | modules/entity-resolution/.../run/ErMain.scala:35, :45 | n/a |
| check.sh:596 | JsonlExport `Required`/`Optional` | modules/spark/.../JsonlExport.scala:234, :237 | n/a |
| check.sh:596 | RagIngestJob `Required`/`Optional` | modules/rag/.../ingest/RagIngestJob.scala:90-91 | n/a |
| check.sh:596 | StorageLoadMain `val X = "sub"`, `X -> SubcommandOptions(` | modules/storage/.../load/StorageLoadMain.scala:61-85 | n/a |
| check.sh:643 | state machine JsonlExport step options | infra/terraform/modules/workflow/sfn.tf:330 | n/a |
| check.sh:822 | probes: `Usage: scripts/NAME.sh`; `ERROR: deploy.sh ... exit=4`; `refusing: the manifest is an example` (run-experiment.sh:322); `SCHOLARCHAIN_SERVICE_API_KEY` (smoke-test.sh:14, :138); `evidence-archive` (cleanup.sh:235); `ARCHIVE-TAR: ` (archive-evidence.sh:579) | as listed; 7b keeps them (handoff) | n/a |
| package.sh:374 | `sbt -batch clean packageJobs services/dockerStage packageCheck` | build.sbt:601 (alias), :412 (dockerStage), :527 | n/a |
| package.sh:402 | `unzip -p JAR scholarchain-build-info.properties`, keys buildCommit/buildDirty/module | project/BuildIdentity.scala:21, :70 | n/a |
| package.sh:444 | `modules/services/target/docker-context/{app.jar,config/course.json,context.sha256}` | project/DockerContext.scala:14-16, :35 | n/a |
| package.sh:336 | `image.java-runtime=` | project/build.properties:48 | n/a |
| package.sh:345 | `docker buildx imagetools inspect IMAGE` ("Platform: linux/arm64") | build report RISKS | unverified (Docker CLI; no doc tool) |
| package.sh:475 | `docker buildx build --platform linux/arm64 --load --file docker/services/Dockerfile --build-arg JAVA_RUNTIME_IMAGE=... --tag scholarchain-services:SHA40 modules/services/target/docker-context` | project/DockerContext.scala:41-56; build report INTERFACE HANDOFF | unverified (Docker CLI) |
| package.sh:485 | `docker image inspect --format {{.Os}}/{{.Architecture}}`, `{{.Id}}`; `docker create --platform`; `docker cp`; `docker rm ID` | docker/services/Dockerfile:26-27, :35 | unverified (Docker CLI) |
| package.sh:613 | terraform outputs `ecr_repository_url`, `data_bucket_name`, `data_prefixes` (`.data_prefixes.value.artifacts`), `vector_bucket_name` | outputs.tf:27, :101, :106, :116; modules/data-lake/main.tf:24 | n/a |
| package.sh:613 | `aws sts get-caller-identity` (.Account) | same use as plan.sh:253 | unverified (not re-read this step) |
| package.sh:641 | `aws ecr describe-images --repository-name R --image-ids imageTag=T` (.imageDetails[0].imageDigest; ImageNotFoundException) | ECR repo IMMUTABLE tags, modules/runtime/main.tf:78 | https://docs.aws.amazon.com/cli/latest/reference/ecr/describe-images.html |
| package.sh:664 | `aws ecr get-login-password` piped to `docker login --username AWS --password-stdin REGISTRY`; `docker tag`; `docker push` | - | unverified (get-login-password not re-read; Docker CLI) |
| package.sh:715 | `aws s3api head-object --bucket B --key K` (.Metadata["scholarchain-sha256"], ["scholarchain-commit"]; 404) | - | unverified (not re-read) |
| package.sh:728 | `aws s3api put-object --bucket --key --body --if-none-match '*' --checksum-algorithm SHA256 --metadata k=v,k=v` | data-lake bucket policy TLS rules, modules/data-lake/main.tf:137-161 | https://docs.aws.amazon.com/cli/latest/reference/s3api/put-object.html (synopsis: all five options present) |
| run-experiment.sh:381 | `git grep -h -E '^APPROVED-MANIFEST-SHA256: HEX$' HEAD -- ':(glob)docs/agent-runs/**/human-gate.md' ':(glob)docs/agent-runs/**/approvals.md'` | experiments/README.md:27-33 | n/a (git pathspec glob magic, unverified at runtime) |
| run-experiment.sh:326 | v1 rules | experiments/README.md:64-81; manifest-schema-v1.json | n/a |
| run-experiment.sh:417 | terraform outputs `state_machine_arn`, `data_bucket_name`, `vector_bucket_name`; `aws sts get-caller-identity` | outputs.tf:79, :101, :116 | sts unverified (not re-read) |
| run-experiment.sh:499 | `bash scripts/run-bounded-pipeline.sh ARGS` (unchanged) | step 7b contract (handoff) | n/a |
| run-experiment.sh:537 | action output lines `RUN-RECORD: evidence/runs/*.json` (NEW, 7b) and `EXECUTION: name=NAME` | run-bounded-pipeline.sh:325 (EXECUTION line); RUN-RECORD from 7b | n/a |
| run-experiment.sh:537 | jq `.run_ids.execution_name`, `.run_ids.emr_cluster_ids[]` in the nested record | step 7b contract (handoff) | n/a |
| run-experiment.sh:572 | `aws emr list-clusters --active`, `aws emr describe-cluster --cluster-id` (.Cluster.Tags, .Name, .Status.State) | same use as cleanup.sh:501-510 | Phase 12 2c cited emr list-clusters; describe-cluster unverified |
| run-experiment.sh:596 | `aws cloudwatch get-metric-data --metric-data-queries file://Q --start-time ISO --end-time ISO --scan-by TimestampAscending` (MetricDataQuery Id/Label/MetricStat{Metric{Namespace,MetricName,Dimensions[{Name,Value}]},Period,Stat}/ReturnData; paginated) | experiments/manifests/example-not-approved.json:31-59 | https://docs.aws.amazon.com/cli/latest/reference/cloudwatch/get-metric-data.html |
| archive-evidence.sh:376 | plan record fields `.account_id`, `.plan_file`, `.plan_sha256`, `.commit`/`.plan_commit`, `.build_commit`, `.plan_id`, `.tree_clean`, `.region`, `.environment`, `.created_utc`, `.variable_overrides`; `NAME.summary.txt` | plan.sh:308, :360-375 (7b adds plan_commit, build_commit, plan_id) | n/a |
| archive-evidence.sh:447 | terraform outputs `data_bucket_name`, `vector_bucket_name`; `aws sts get-caller-identity` | outputs.tf:101, :116 | sts unverified |
| archive-evidence.sh:462 | `aws s3api list-objects-v2 --bucket B --prefix reports/` (.Contents[].Key/.Size; CLI auto-pagination); `aws s3api get-object --bucket B --key K OUTFILE` | data-lake prefixes modules/data-lake/main.tf:26 | unverified (not re-read this step) |
| archive-evidence.sh:563 | `tar -cf NAME.tar -C DIR NAME` (COPYFILE_DISABLE=1) | - | n/a |

## Archive-layout key traces (decision 9.8; rule: identity.sh:382-425 = cleanup.sh normalize_archive_list)

1. Local-only (`--local-only`). tar -tf: `N/`, `N/MANIFEST.json`, `N/plans/`, `N/plans/P.json`, `N/plans/P.summary.txt`, `N/repo/`, `N/repo/.claude/.last-test-run`, `N/repo/evidence/checks/check-A.json`, ... First segments all `N` (not `reports`) -> root `N` stripped; entries ending in `/` dropped. Keys: `MANIFEST.json`, `plans/P.json`, `plans/P.summary.txt`, `repo/.claude/.last-test-run`, `repo/evidence/checks/check-A.json`, ... `grep -c '^reports/.'` = 0. This is exactly what check.sh's probe asserts (check.sh:762).
2. S3 mode, two report objects `reports/final/summary.json`, `reports/runs/r1.json`. tar -tf adds `N/reports/`, `N/reports/final/`, `N/reports/final/summary.json`, `N/reports/runs/`, `N/reports/runs/r1.json` (and `N/metrics/...` if given). Root `N` stripped -> keys include `reports/final/summary.json`, `reports/runs/r1.json`: byte-identical to the S3 keys, so cleanup.sh:389 `grep -F -x KEY` finds both; ARCHIVE_REPORTS_COUNT = 2.
3. S3 mode with the repository's own `reports/final-report.md` plus the two objects of (2). The repo copy is at `N/repo/reports/final-report.md` -> key `repo/reports/final-report.md` (does not start with `reports/`, so it is neither counted nor able to satisfy a check for an S3 key `reports/final-report.md`); keys `reports/final/summary.json`, `reports/runs/r1.json` as in (2); count = 2. Namespace rule held.

## Cost estimate

$0 in this step (nothing ran). Phase 14+: package.sh --publish costs ECR storage of one image and S3 storage of four jars (cents); run-experiment.sh costs whatever the approved manifest's action costs (cap 5 USD declared per run) plus CloudWatch GetMetricData requests (cents).

## Required environment variables by name

- None required by the four scripts. Read if set: `TMPDIR` (temp folder), `AWS_PROFILE` and the standard AWS credential chain (aws CLI; package.sh --publish, run-experiment.sh, archive-evidence.sh S3 mode). Set for children: `CHECKPOINT_DISABLE` (check.sh, terraform), `PYTHONDONTWRITEBYTECODE` (check.sh gate 5), `COPYFILE_DISABLE` (archive tar), `SCHOLARCHAIN_EXPERIMENT_RUN_DIR`, `SCHOLARCHAIN_EXPERIMENT_ATTEMPT_ID`, `SCHOLARCHAIN_EXPERIMENT_MANIFEST_ID` (run-experiment.sh action). check.sh probes unset `SCHOLARCHAIN_SERVICE_API_KEY` and every AWS credential variable for the probe command only.

## Deviations and proposed later

- deviation: publish facts are recorded in `evidence/packages/SHA40/publish/ATTEMPT_ID.json`, not inside package-record.json (A1; records are never overwritten).
- Proposed later: a shellcheck severity decision for `.claude/**` in check.sh gate 4 (R1); exporting metrics for failed/timed-out experiment runs (today the window is recorded, nothing exported).

## INTERFACE HANDOFF (for step 7b)

Files: scripts/lib/identity.sh (new), scripts/check.sh, scripts/package.sh, scripts/run-experiment.sh, scripts/archive-evidence.sh (new). Thread-safety: n/a (sequential scripts, one private temp folder per run).

Sourcing (every script, after its root check): `# shellcheck source=/dev/null` then `. scripts/lib/identity.sh` (see check.sh:241-252). The helper sets no option and no trap; functions return 0/2/3/4/5/6 and print `REASON: ...` on stderr; callers decide (`fn || die N "..."`).

Helper signatures (scripts/lib/identity.sh):
- `SC_BUILD_INPUT_PATHS` (array) :34
- `build_commit` -> stdout 40 hex; 3 on failure :50
- `head_commit` -> stdout 40 hex; 3 :64
- `tree_dirty_flag` -> stdout `true|false`; 5 :76
- `require_clean_tree` -> 0 clean; 4 dirty (paths on stderr); 5 :91
- `plan_usable PLAN_COMMIT` -> 0 usable; 4 not usable (reason); 5 git error :109
- `register_identifiers ACCOUNT DATA_BUCKET VECTOR_BUCKET` (any may be empty) -> 0; 2 bad account :145
- `mask_account ACCOUNT` -> `********NNNN` or `<unknown>` :158
- `mask_text TEXT` -> text with `<account>`, `<data-bucket>`, `<vector-bucket>` :169
- `mask_stream` (stdin -> stdout, same masking) :185
- `logical_s3 URI` -> `s3://{data-bucket}/KEY` / `s3://{vector-bucket}/KEY` / unchanged :203
- `resolve_s3 URI` -> real URI; 3 bucket unknown; 4 unknown placeholder :224
- `check_no_identifiers PATH...` (files or dirs) -> 0; 6 found (file + kind on stderr, never the value) :253
- `new_attempt_id` -> `YYYYMMDDTHHMMSSZ-<12 hex HEAD>-<4 hex>` :284
- `write_record_atomic FILE` (JSON on stdin; FILE.partial, jq empty, identifier check, no-clobber link) -> 0; 4 exists; 5; 6 :301. Use `NAME.started.json` then `NAME.json`.
- `sc_sha256 FILE`, `sc_bytes FILE`, `sc_utc_now`, `sc_epoch_to_utc EPOCH` :340-366
- `strip_dot_slash ENTRY`, `archive_root LISTING_FILE`, `archive_keys LISTING_FILE` (one key per line on stdout) :372-425. cleanup.sh: replace normalize_archive_list by `archive_keys "${ARCHIVE_LIST}" >"${ARCHIVE_KEYS}"`, `ARCHIVE_ROOT="$(archive_root "${ARCHIVE_LIST}")"`, and keep the `grep -c -E '^reports/.'` count.

Contracts step 7b must keep (check.sh gate 8/9 and run-experiment.sh rely on them):
- Every usage text starts with `Usage: scripts/NAME.sh` and `--help` exits 0 before any root check or cloud call.
- deploy.sh: `--plan-sha256 <64 hex with no matching plans/*.record.json>` -> exit 4 with an `ERROR: deploy.sh step=... exit=4: ...` line, before any cloud call, also with no AWS credentials.
- smoke-test.sh `--question probe --api-url https://example.invalid` with SCHOLARCHAIN_SERVICE_API_KEY unset -> exit 3, message naming SCHOLARCHAIN_SERVICE_API_KEY, before any network call.
- cleanup.sh `--list` without `--evidence-archive` -> exit 4, message containing `evidence-archive`.
- Terraform output reads in the forms `$(out NAME)`, `$(out_json NAME)` or a jq path `.NAME.value` (gate 8a).
- Every option passed to ErMain, RagIngestJob, StorageLoadMain or JsonlExport sits on a line ending in `# contract: ErMain`, `# contract: RagIngestJob`, `# contract: StorageLoadMain SUBCOMMAND` (for example `# contract: StorageLoadMain dynamodb-load`) or `# contract: JsonlExport`; options written as `--name`. A script that names one of these classes must have at least one such line (gate 8c).
- run-bounded-pipeline.sh: accepts logical `s3://{data-bucket}/KEY` arguments (resolve with `resolve_s3`) and real ones; prints one line `RUN-RECORD: evidence/runs/ATTEMPT_ID-STAGE.json` per record it finalizes; each run record has `run_ids.execution_name` (string, when an execution started) and `run_ids.emr_cluster_ids` (array of strings, possibly empty); keeps the `EXECUTION: name=NAME ...` line (run-bounded-pipeline.sh:325).
- package.sh --publish keys (jar defaults for run-bounded-pipeline.sh): `s3://{data-bucket}/artifacts/SHA40/jars/hadoop.jar`, `.../jars/entity-resolution.jar`, `.../jars/spark-analytics.jar`, `.../jars/services.jar`, `.../spark-conf/phase06.json`; object metadata `scholarchain-commit` = SHA40 and `scholarchain-sha256`; ER jar locally: `jq -r '.jars[] | select(.module == "entityResolution") | .path' evidence/packages/SHA40/package-record.json`.
- plan records read by archive-evidence.sh: `infra/terraform/environments/course/plans/*.record.json` with `plan_sha256`, `plan_file` (relative to the environment folder), `account_id`, and `plan_commit` (falls back to `commit`), `build_commit`, `plan_id`, `tree_clean`, `region`; summary at the same base name `.summary.txt`.

VALIDATION EVIDENCE
- fmt: terraform fmt -recursive -check infra/terraform -> 0, no files listed
- validate: terraform -chdir=infra/terraform/environments/course validate -> "Success! The configuration is valid."
- shellcheck: shellcheck scripts/check.sh scripts/package.sh scripts/run-experiment.sh scripts/archive-evidence.sh scripts/lib/identity.sh scripts/plan.sh scripts/deploy.sh scripts/rollback.sh scripts/smoke-test.sh scripts/run-bounded-pipeline.sh scripts/cleanup.sh -> 0; clean (also `shellcheck -x -P SCRIPTDIR` on the five new files -> 0)
- jq: not applicable (no JSON file written or changed)
- finished: after the last command above
- files changed after these runs: no
