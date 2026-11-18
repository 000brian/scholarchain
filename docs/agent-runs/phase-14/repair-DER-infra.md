# repair-DER-infra (Phase 14 session 3, step D-ER)

STATUS: COMPLETE
OWNER: infra-engineer
WORK ORDER: docs/agent-runs/phase-14/prompt-s3.md (sha256 c0bd687e92015df11475ce159307b1c78f8955175cbcf9b86a9e0fe6540fe83e, checked), decision 12 "D-ER"; standing rules and decision 10 read. HEAD e78bb100b11ca8eb8092c27de4573f660875e18d (checked).
FILES CREATED: none in the repository (this report only)
FILES MODIFIED:
- /Users/brian/Developer/repos/scholarchain/scripts/run-bounded-pipeline.sh
- /Users/brian/Developer/repos/scholarchain/scripts/README.md
- /Users/brian/Developer/repos/scholarchain/infra/terraform/modules/observability/dashboard.tf
COMMANDS RUN: shasum -a 256 on the work order; git rev-parse HEAD; read-only grep/ls/wc/cut over modules/, scripts/, infra/, docs/agent-runs/phase-05/; shellcheck -x -P scripts scripts/run-bounded-pipeline.sh; terraform fmt -recursive infra/terraform; terraform fmt -recursive -check infra/terraform; terraform -chdir=infra/terraform/environments/course validate; git status --short; git diff --stat. aws-docs MCP: search_documentation (1), read_sections (2). No aws-api call, no script run, no sbt.
TEST RESULTS: not applicable (no Scala change; no script run by rule)
VALIDATION RESULTS: shellcheck exit 0 (clean); fmt check exit 0, no files listed; validate "Success! The configuration is valid."
ASSUMPTIONS:
- The identity-assignment share of the ER output is estimated from Phase 5 counts (758,328 of about 17.58 million decision lines, so roughly 4 percent, about 0.8-1 GB of 21 GB); not measured.
- 30 Mbit/s sustained uplink as the human measured on Nov 17.
RISKS:
- Process slip: my first recursive grep over infra/ also matched lines in infra/terraform/environments/course/terraform.tfstate and .backup (files agents never read). The output was truncated to a state-machine "definition" prefix with no account ID, ARN or bucket in what I saw; I did not open those files and later greps excluded them. Recorded for the human.
- Full upload (default): each mention_pair part is about 4.25 GB (Phase 5: four parts, about 17 GB). That is under the 5 GB PutObject limit but takes about 19 minutes per part at 30 Mbit/s, inside TRANSFER_TIMEOUT 1800 s only while the uplink stays above about 19 Mbit/s. A slower link would time out (exit 7). Not changed (outside the work order); see proposed later.
- With identity-assignments-only, the S3 copy of the ER run is incomplete on purpose: _manifest.json lists files that are not in S3. Only Spark reads it, and Spark checks only the entries under decisions/identity_assignment/ (Pipeline.scala:201-215).
- The left-out files (review queue, pair decisions, run-report.json) exist only on the laptop; archive-evidence.sh does not collect them.
MANUAL ACTION REQUIRED: the human chooses whether D7 adds `--upload identity-assignments-only`; the human keeps the local folder ${TMPDIR:-/tmp}/scholarchain-er-ATTEMPT_ID/ as ER evidence. code-reviewer reviews both changes; the human commits before any script runs.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer review of the three-file diff; then the human's commit; then D0a (a new runtime-off plan, because dashboard.tf changed).

## Task A: ER upload size (status: done, option added)

Who reads the ER output root after D7 (file:line):
- Spark is the only reader. GraphAnalyticsJob takes `--identity-assignments DIR` (modules/spark/src/main/scala/org/scholarchain/spark/GraphAnalyticsJob.scala:13, :35).
  - Pipeline.scala:148-151 sets the layout: `decisions/identity_assignment`, `_manifest.json`, `_SUCCESS`.
  - Pipeline.scala:176-200 (checkErRun) reads `<erRun>/_SUCCESS` and `<erRun>/_manifest.json`, the parent of the parent of the assignments directory (:239-245).
  - Pipeline.scala:201-225 checks only the manifest's outputs that start with `decisions/identity_assignment/`.
  - Pipeline.scala:130-135 and RunFs.scala:91-108 hash the non-hidden files below the assignments directory; Pipeline.scala:421 decodes them.
- The state machine passes only `spark.identityAssignments` (infra/terraform/modules/workflow/sfn.tf:38, :303).
- The script's spark stage checks only the local ER run record's `outputs.identity_assignments` (scripts/run-bounded-pipeline.sh require_er_succeeded, about line 1146) and the path form (about line 1170).
- No reader in storage, rag or services: StorageLoadMain (modules/storage/.../load/StorageLoadMain.scala:43-58, :78-89) and RagIngestJob (modules/rag/.../ingest/RagIngestJob.scala:42-57) read only Spark's export run. Their `run-report.json` matches are their own reports. EvidenceBuilder.scala:353-356 builds MatchDecision evidence from store facts (IdentityFact), not from curated/er. S3KeyLayout.curatedObject (modules/storage/.../s3/S3KeyLayout.scala:46) has no production caller. No `curated` reference in modules/{rag,services,spark}/src/main.
- The ER-local tools EvalMain and ReviewQueue (modules/entity-resolution/.../eval/EvalMain.scala:15-16) read review-queue files from a local run directory. They are not pipeline readers after D7, and the local folder keeps those files.
- Layout source: ErRun.scala:77-87; the assignments file is ErRun.scala:480.

Answer: only `run=HEX/decisions/identity_assignment/**`, `run=HEX/_manifest.json` and `run=HEX/_SUCCESS` are read. So I added the option.

Change (finding G-01, a script defect: an unbounded upload of unread data):
- scripts/run-bounded-pipeline.sh:119-132: usage text for `--upload full|identity-assignments-only`. Lines 139-140 update the upload sentence.
- :303: `A_ER_UPLOAD="full"` (the default is unchanged).
- :353: argument parsing.
- :426-431: `--upload` is allowed only for `--stage er`, and its value is checked (exit 2).
- :1097-1131 (er-upload):
  - In identity-assignments-only mode, only files under `run=HEX/decisions/identity_assignment/` are uploaded with put_once, then `_manifest.json`, then `_SUCCESS` (order unchanged).
  - Every other file is written as path and bytes to a TMP_DIR list.
  - If no data file was uploaded, the run stops with exit 6 before the manifest and `_SUCCESS` go up.
  - New record fields `outputs.upload_mode` and `outputs.left_out` = {mode, files, bytes, kept_in_local_folder, note, items:[{path, bytes}]}.
  - A new printed line: `SUMMARY: er-upload mode=M data_files_uploaded=N left_out_files=K left_out_bytes=B`.
  - Full mode uploads exactly as before; it only adds the two record fields, with an empty left_out.
- scripts/README.md:81: the D7 row points to the option. New section "ER upload size" at :108.

Upload time at 30 Mbit/s (estimate, untested):
- Full upload: 21 GB is about 1.6 h (21e9 B) to 1.7 h (21 GiB), plus per-object overhead.
- identity-assignments-only: about 0.8-1 GB, roughly 4-5 minutes (unverified size).

## Task B: dashboard keys (status: done)

Source: https://docs.aws.amazon.com/AmazonCloudWatch/latest/monitoring/CloudWatch-Dashboard-Body-Structure.html
- Section "Metric Widget: Format for Each Metric in the Array": an expression is `[ {"expression", ["label"], ["id"]} ]`. A single metric is `[Namespace, MetricName, dims..., {Rendering Properties Object}]`.
- Section "Dashboard Widget Object: Rendering Properties Object Format" allows color, label, period, stat, visible and yAxis (left|right). The item-level fields accountId, id, label and region are also listed.
- Section "Properties of a Metric Widget Object" covers the widget-level keys.

Every row and object (infra/terraform/modules/observability/dashboard.tf):
- :15 ecs_cpu `{label}` OK; :16 ecs_mem `{label}` OK.
- :20 sqs `{label}` OK; :21 `{label, yAxis="right"}` OK (rendering object of a single metric).
- :24, :25 dynamo `{label, stat}` OK.
- :37-39, :51-52 API Gateway rows: no options object, OK.
- :64-66 ALB rows: no object, OK; :67 `{stat, yAxis}` OK.
- :99 Neptune: no object, OK; :100 `{yAxis}` OK.
- :121-123 States rows: no object, OK.
- :124 EMR SEARCH expression object: `expression`, `id`, `label` OK; `yAxis` is NOT allowed in an expression object. Fixed as G-02 (a Terraform defect): I dropped `yAxis = "right"`, because the page gives an expression object no per-series axis key, so there was nowhere to move it. The EMR series now draws on the left axis.
- Widget-level properties (title, region, stat, period, metrics) are all listed in "Properties of a Metric Widget Object". OK.
- Nothing else changed in dashboard.tf (diff: one line).

## IAM changes
none

## Stateful-resource actions
none. The next runtime-off plan (D0a) should still create only module.observability.aws_cloudwatch_dashboard.this, now with the corrected body. Any earlier D0a plan is stale (dashboard.tf is a plan input).

## Cost estimate
No AWS cost change. The option makes the D7 upload about 20x smaller (S3 PUT count and storage of the left-out roughly 20 GB avoided, at about $0.023/GB-month, unverified).

## Required environment variables (names)
unchanged: AWS_PROFILE, TMPDIR (er)

## Proposed later
- Use multipart upload, or a per-object timeout that scales with size, for the full-mode ER upload. The roughly 4.25 GB mention_pair parts are close to the 5 GB PutObject limit and need more than 1800 s below about 19 Mbit/s.

## INTERFACE HANDOFF
- Files changed: scripts/run-bounded-pipeline.sh, scripts/README.md, infra/terraform/modules/observability/dashboard.tf.
- Option change: yes. A new optional `--upload full|identity-assignments-only` for `--stage er` only (default full; with another stage it is refused with exit 2). No existing option changed.
- Record field change: yes, additions only in the er record. `outputs.upload_mode` (string) and `outputs.left_out` (object: mode, files, bytes, kept_in_local_folder, note, items[{path, bytes}]). `outputs.identity_assignments`, `outputs.er_output_root` and `run_ids.*` are unchanged, so `--stage spark` and require_er_succeeded are unaffected.
- New printed line: `SUMMARY: er-upload ...` (matched by the human's paste grep).
- S3 layout: unchanged keys; in identity-assignments-only mode a subset of the same keys.
- Terraform: the dashboard body changes (one key removed), so a new plan is needed. No other resource.
- Thread-safety: not applicable. Limits: unchanged (TRANSFER_TIMEOUT 1800 s per object, MAX_TRIES 3).

VALIDATION EVIDENCE
- fmt: terraform fmt -recursive -check infra/terraform -> exit 0, no files listed
- validate: terraform -chdir=infra/terraform/environments/course validate -> "Success! The configuration is valid."
- shellcheck: shellcheck -x -P scripts scripts/run-bounded-pipeline.sh -> exit 0; clean
- finished: after the last command above
- files changed after these runs: no
