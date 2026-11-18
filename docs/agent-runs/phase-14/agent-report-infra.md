# Phase 14 session 2, step 5: infra-engineer (decision 9)

**Item 5 is case (b)** (hadoop-engineer's driver option `--dblp-dtd`): no state-machine change and no extra runtime-off plan before the Hadoop stage. The only Terraform change is item 0 (`dashboard.tf`). The D0a runtime-off plan should create only `module.observability.aws_cloudwatch_dashboard.this`.

STATUS: COMPLETE
OWNER: infra-engineer
WORK ORDER: docs/agent-runs/phase-14/prompt.md (sha256 b1f951683cc708c297d3e74d6b21332f5218324e4db0086d33307ec8bf8b88a2, checked), decision 9 items 0-8; HEAD c4fbf62 plus other owners' uncommitted changes (not touched); wave closer: no
FILES CREATED: none (this report is in the scratchpad)
FILES MODIFIED:
- /Users/brian/Developer/repos/scholarchain/infra/terraform/modules/observability/dashboard.tf (item 0)
- /Users/brian/Developer/repos/scholarchain/scripts/archive-evidence.sh (item 1)
- /Users/brian/Developer/repos/scholarchain/scripts/plan.sh (item 2)
- /Users/brian/Developer/repos/scholarchain/scripts/deploy.sh (item 3)
- /Users/brian/Developer/repos/scholarchain/scripts/run-bounded-pipeline.sh (item 5: usage text and the R-1 record note)
- /Users/brian/Developer/repos/scholarchain/scripts/README.md (items 5, 6)
COMMANDS RUN:
- shasum -a 256 (prompt); ls; wc -l; head -n 12; grep -n / grep -rn / grep -rnE / grep -rln (this session has no Grep tool; patterns used no guard-list word; one grep with a module path containing the guard word was blocked by the guard and not retried; the Read tool was used instead); git status --short; git diff --stat; git -C REPO status/diff
- terraform fmt -recursive infra/terraform; terraform fmt -recursive -check infra/terraform; terraform -chdir=infra/terraform/environments/course validate (twice each; the FIRST run was prefixed with `cd /Users/brian/Developer/repos/scholarchain &&`, which the work order forbids: my mistake, it ran without a prompt; every later run used the exact allowed forms)
- shellcheck with literal paths on the four changed scripts (three runs)
- aws-docs: search_documentation 2x, read_sections 16x (dashboard body page, 15 CLI synopses), read_documentation 1x (RegisterScalableTarget)
- aws-api: 0 calls. terraform MCP: 0 calls. No script run, no bash, no terraform init/plan/output.
TEST RESULTS: not applicable (no Scala change; no sbt)
VALIDATION RESULTS: see VALIDATION EVIDENCE
ASSUMPTIONS:
- Terraform's in-place update of `aws_appautoscaling_target` calls RegisterScalableTarget (provider source not read: unverified).
- The CLI reference pages under https://docs.aws.amazon.com/cli/latest/reference/ describe AWS CLI v2 (their synopses list v2-only global options such as `--no-cli-pager` and `--cli-error-format`).
RISKS:
- Widget 7 (`Pipeline executions and EMR clusters`) has an expression row with `yAxis` inside the expression object (dashboard.tf:124). The doc's expression format lists `expression`, `label`, `id` (plus `accountId`, `region`); `yAxis` there is not listed. The PutDashboard error only showed its first of 200 errors, so whether widget 7 is also refused is unknown (unverified). I did not change it (decision 9.0: "Change nothing else in the dashboard"). If D0a fails again at `/widgets/7/...`, the fix is to drop `yAxis` from that object (one line; new plan).
- Row order inside widgets 4 and 6 changed: all first-metric rows, then all second-metric rows (cosmetic).
- All script changes: runtime: untested.
MANUAL ACTION REQUIRED: none from this step. Session 3: D0a plan (dashboard only), signature, deploy.sh.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: R-2 code-reviewer (this step plus R-1 files changed since: none by me). The dashboard change needs a new runtime-off plan and signature at D0a.

## Item 0: I-29 dashboard (status: done)

- Fix: infra/terraform/modules/observability/dashboard.tf:17-26. `local.sqs` and `local.dynamo` are now `concat()` of two `for` lists; each element is one row (strings, options object last). `flatten()` is recursive and turned every row into loose strings and objects, which is the `/widgets/4/properties/metrics/0` "Should be array" error. Nothing else in the dashboard changed.
- Widget index (0-based, `local.widgets`): 0 API Gateway, 1 API latency, 2 ALB, 3 ECS, 4 SQS, 5 Neptune, 6 DynamoDB, 7 pipeline/EMR.

JSON shape of widgets 4 and 6 after the fix, written by hand from the code (NAME is the Terraform-supplied resource name, not written here):
- widget 4 (SQS) row: `["AWS/SQS", "ApproximateNumberOfMessagesVisible", "QueueName", "NAME", {"label": "embedding visible"}]` (second-metric rows: `["AWS/SQS", "ApproximateAgeOfOldestMessage", "QueueName", "NAME", {"label": "KEY oldest age (s)", "yAxis": "right"}]`); 4 queues x 2 = 8 rows.
- widget 6 (DynamoDB) row: `["AWS/DynamoDB", "ConsumedReadCapacityUnits", "TableName", "NAME", {"label": "KEY read", "stat": "Sum"}]` (second-metric rows the same with `ConsumedWriteCapacityUnits` and `"KEY write"`); tables x 2 rows.
- Checked against https://docs.aws.amazon.com/AmazonCloudWatch/latest/monitoring/CloudWatch-Dashboard-Body-Structure.html, section "Metric Widget: Format for Each Metric in the Array": `[ Namespace, MetricName, [{DimensionName,DimensionValue}...] {Rendering Properties Object} ]`; `label`, `stat`, `yAxis` (`left`|`right`) are listed under "Rendering Properties Object Format". Both rows match.

Every jsonencode / templatefile / flatten under infra/terraform/ (no `templatefile` exists):

| file:line | what | result |
|---|---|---|
| modules/observability/dashboard.tf:133 | `jsonencode({ widgets = local.widgets })` | checked: fixed (I-29); widgets 0-3, 5, 7 already lists of rows; widget 7 expression row: see RISKS |
| modules/identity/emr.tf:32 | `flatten([for p in ... : [p, "${p}/*"]])`, IAM condition `values` | checked: a flat list of strings is the right shape |
| modules/runtime/main.tf:96 | ECR lifecycle policy `{rules = [{...}]}` | checked: list of rule objects |
| modules/runtime/main.tf:153 | service container_definitions `[{...}]` | checked: one object; command list of strings; portMappings, environment, secrets lists of objects; logConfiguration object |
| modules/runtime/main.tf:223 | ops container_definitions `[{...}]` | checked: same shape |
| modules/runtime/ingress.tf:160 | API access-log format, flat object of strings | checked |
| modules/workflow/main.tf:52 | SQS redrive_policy object | checked |
| modules/workflow/main.tf:138 | redrive_allow_policy, `sourceQueueArns = [arn]` | checked |
| modules/workflow/sfn.tf:519 | state machine `jsonencode(local.definition)` | checked: no flatten; Tags, InstanceGroups, BootstrapActions, Catch, Retry, Choices are lists of objects (sfn.tf:68, :77-92, :101-104, :135-159, :182-185) |

## Item 1: I-25 archive record (status: done)

- scripts/archive-evidence.sh:50-53: `IDENTIFIER_WARNINGS` holds JSON text, default `"not checked: the check did not run"`, so a run that fails before the manifest no longer records `false`.
- :554-570 (write_manifest): with no registered account ID, `byte_copies_hold_identifiers` is `"not checked: account unknown"` in MANIFEST.json and the final record, with a WARN line. It is `false` or `true` only after `check_no_identifiers` ran with the account registered.
- :462-478 (read_outputs, S3 mode): the account comes from `sts get-caller-identity`, must be 12 digits (else exit 5), and is held only in `SC_ACCOUNT_ID`; the local copies are cleared; the INFO line no longer prints even the masked last four digits.
- Usage text :115-124 states the rule. runtime: untested.

## Item 2: I-26 plan record after the check (status: done)

- scripts/plan.sh:336-340 (terraform_plan): `check_no_identifiers` on `.plan.txt` right after the plan, before the sha256, summary or record. On failure: exit 6, "no record was written, so deploy.sh cannot use this plan".
- :414-416: the summary is checked before it is shown.
- :419-448: the record goes through `write_record_atomic` (valid JSON and the identifier check before the final name). On a failed check only `NAME.record.json.partial` stays, and `find_plan_record`'s `*.record.json` glob never matches it. Exit 6 on a failed check, exit 5 on a write failure.
- Usage text :61-65 and exit code 6 (:100-102) say that a failed plan gets no record and is unusable. runtime: untested.

## Item 3: I-28 runtime-on desired count (status: done)

- Before the change: desired 0 did not pass vacuously. The old check failed `desired < 1` for the ALB roles, and the digest check runs only over at least one task. But `running` was never checked on its own, and a failed or timed-out `services-stable` wait printed no counts.
- Change, scripts/deploy.sh:
  - :590-599: query, evidence and graph need desired >= 1 AND running >= 1 after the wait; otherwise an EVIDENCE line with desired, running and pending, then exit 6 through the existing `failed` path (no release record).
  - :498-511 `print_service_counts`, called at :548: when the wait fails (exit 6) or times out (exit 7), it prints every service's desired, running and pending counts and family:revision (no ARN).
  - Usage step 8 (:81-85) is updated.
- Will AWS raise the count?
  - Terraform: `desired_count` is in `ignore_changes` (modules/runtime/main.tf:290-292), so the runtime-on plan does not move the services. Only the scaling targets' min goes 0 -> 1 (modules/runtime/autoscaling.tf:15-21, :31).
  - https://docs.aws.amazon.com/autoscaling/application/APIReference/API_RegisterScalableTarget.html: "If you call the RegisterScalableTarget API operation to update an existing scalable target, Application Auto Scaling retrieves the current capacity of the resource. If it's below the minimum capacity ..., Application Auto Scaling adjusts the capacity of the scalable target to place it within these bounds". So the docs support a rise to 1. Provider behavior: unverified. runtime: untested until D11.
- Remedy (scripts/README.md:118-122, needs the human's go):
  - `aws ecs update-service ... --desired-count 1` for each of the three services.
  - Drift: none, because `desired_count` is ignored and 1 is inside the target bounds.
  - Then make a new runtime-on plan (expected: no change), sign it, and run deploy.sh to verify and write the release record (deploy.sh refuses a plan it already applied, :459).

## Item 4: AWS CLI options (status: done; no option was wrong, no change)

Every option name the scripts pass was checked against the "Synopsis" section of its CLI reference page (aws-docs read_sections). The global options used everywhere (`--region`, `--cli-connect-timeout`, `--cli-read-timeout`, `--no-cli-pager`, `--output`) appear in every synopsis below. Enum values (`FARGATE`, `TASK_DEFINITION`, `RUNNING`) and the `--tag-filters` shorthand were not read in the Options sections, so they stay `unverified`.

| call-table row | script:line | options used | page cited | result |
|---|---|---|---|---|
| sts get-caller-identity | plan.sh:293, deploy.sh:347, archive-evidence.sh:471, cleanup.sh:353, rollback.sh:239, run-bounded-pipeline.sh:582, package.sh:639, run-experiment.sh:442 | none | https://docs.aws.amazon.com/cli/latest/reference/sts/get-caller-identity.html | verified |
| ecr get-login-password | package.sh:686-687 | none | https://docs.aws.amazon.com/cli/latest/reference/ecr/get-login-password.html | verified |
| s3api head-object | run-bounded-pipeline.sh:617, package.sh:725 | --bucket --key | https://docs.aws.amazon.com/cli/latest/reference/s3api/head-object.html | verified |
| s3api list-objects-v2 | archive-evidence.sh:484, cleanup.sh:368, run-bounded-pipeline.sh:688 | --bucket --prefix | https://docs.aws.amazon.com/cli/latest/reference/s3api/list-objects-v2.html | verified |
| s3api get-object | archive-evidence.sh:507-509, run-bounded-pipeline.sh:1033 | --bucket --key, positional outfile | https://docs.aws.amazon.com/cli/latest/reference/s3api/get-object.html | verified (`<outfile>` positional) |
| emr describe-cluster | cleanup.sh:555, run-experiment.sh:598 | --cluster-id | https://docs.aws.amazon.com/cli/latest/reference/emr/describe-cluster.html | verified |
| stepfunctions start-execution | run-bounded-pipeline.sh:792-793 | --state-machine-arn --name --input (file://) | https://docs.aws.amazon.com/cli/latest/reference/stepfunctions/start-execution.html | verified |
| stepfunctions describe-execution | run-bounded-pipeline.sh:802 | --execution-arn | https://docs.aws.amazon.com/cli/latest/reference/stepfunctions/describe-execution.html | verified |
| stepfunctions get-execution-history | run-bounded-pipeline.sh:771-772 | --execution-arn --max-items | https://docs.aws.amazon.com/cli/latest/reference/stepfunctions/get-execution-history.html | verified |
| ecs describe-services | deploy.sh:447, :481, :503-504, :558; cleanup.sh:665; rollback.sh:263, :309 | --cluster --services | https://docs.aws.amazon.com/cli/latest/reference/ecs/describe-services.html | verified |
| ecs describe-task-definition | deploy.sh:570, rollback.sh:269, run-bounded-pipeline.sh:1173 | --task-definition | https://docs.aws.amazon.com/cli/latest/reference/ecs/describe-task-definition.html | verified |
| ecs list-tasks | deploy.sh:601, rollback.sh:317, run-bounded-pipeline.sh:1187 | --cluster --service-name / --family --desired-status | https://docs.aws.amazon.com/cli/latest/reference/ecs/list-tasks.html | verified (names); value RUNNING unverified |
| ecs describe-tasks | deploy.sh:608, rollback.sh:324, run-bounded-pipeline.sh:1287 | --cluster --tasks | https://docs.aws.amazon.com/cli/latest/reference/ecs/describe-tasks.html | verified |
| ecs run-task | run-bounded-pipeline.sh:1266-1268 | --cluster --task-definition --launch-type --count --network-configuration --overrides --started-by --propagate-tags | https://docs.aws.amazon.com/cli/latest/reference/ecs/run-task.html | verified (names); values FARGATE, TASK_DEFINITION unverified |
| resourcegroupstaggingapi get-resources | cleanup.sh:626-627 | --tag-filters | https://docs.aws.amazon.com/cli/latest/reference/resourcegroupstaggingapi/get-resources.html | verified (name); shorthand form unverified |

Rows still `unverified` from review-report-c: all Docker CLI rows and the curl `--config -` header line. They were not in decision 9.4's list and were not checked.

## Item 5: DTD wiring (status: done, case (b))

- No Terraform or state-machine change: the `--dblp-dtd` option travels in `extraArgs` (sfn.tf:228).
- scripts/README.md, steps D5a-D5c and D6 (the reproduction table at :57-102) and the section "DBLP DTD" (:104-106) cover:
  - the sha256 check of the DTD and the md5 check of the release;
  - the DTD upload;
  - `--max-upload-mb 1100` for the 1,064.4 MiB release;
  - the two `--hadoop-extra-arg` values in order;
  - why `-D` cannot be used;
  - the D6 check `undefined_entity` = 0.
- scripts/run-bounded-pipeline.sh:
  - The usage text (:100-105) names the two arguments.
  - The R-1 note: the record's `configuration.note` (:914-920) now names the extra driver options when any are given, and `configuration.extra_args` records them in logical form. The old fixed text "(-D scholarchain.code.version only)" is now used only when there are no extra args.

## Item 6: scripts/README.md (status: done)

- (i) reproduction order as session 3 runs it: :57-102 (D0a-D20, with check.sh as step 0 for a full reproduction, and experiments and cleanup as "later").
- (ii) first apply, `KMSKeyNotAccessibleFault` on `aws/rds`: :108-110.
- (iii) entering the four values: console only, by the container's `env-var` tag (:112-116). The `read -rs` export for smoke-test.sh is at :116 and in D14.
- (iv) rollback policy for the first runtime-on deploy (fix forward, never roll back to a placeholder; the normal policy from the second deploy on) plus the I-28 remedy and its drift: :118-122.
- (v) smoke questions A (q05) and B (q13), verbatim from agent-report-rag.md, with what a correct answer contains: :124-127; also in D14 and D15.

## Items 7, 8

- 7: nothing under infra/terraform/ changed except dashboard.tf (`git diff --stat -- infra scripts`: 1 Terraform file).
- 8: gates below.

## Call-table rows changed or cited

- Changed: none (no CLI option was wrong).
- Cited: the 15 rows in item 4. Application Auto Scaling RegisterScalableTarget is cited for I-28 (the API reference, not a CLI row). New command in the README remedy (human-run, not in any script): `aws ecs update-service --cluster --service --desired-count --query --output`. Its options were not checked against the CLI page: unverified.

## IAM changes, stateful-resource actions, cost

- IAM: none. Stateful resources: none.
- The expected D0a plan: 1 create (`module.observability.aws_cloudwatch_dashboard.this`).
- Cost: the dashboard is one of the first 3 free dashboards (estimate, unverified for this account).

## Required environment variables (names)

- `AWS_PROFILE` (all cloud scripts)
- `SCHOLARCHAIN_SERVICE_API_KEY` (smoke-test.sh; read with `read -rs`)
- The four Secrets Manager containers by `env-var` tag: `SCHOLARCHAIN_VOYAGE_API_KEY`, `SCHOLARCHAIN_ANTHROPIC_API_KEY`, `SCHOLARCHAIN_SERVICE_API_KEY`, `SCHOLARCHAIN_SERVICE_READONLY_API_KEY`

## Proposed later

- run-bounded-pipeline.sh `--stage hadoop` could check that an `s3://` extra argument exists (head-object) before it starts EMR. Today a missing DTD object is found only by the J01 tasks. Not in this work order.
- deploy.sh's usage text "Phase 14 order" (:101-111) still describes the Phase 13 plan. scripts/README.md is now authoritative; the text can be aligned in Phase 15.

## INTERFACE HANDOFF

- Files changed:
  - infra/terraform/modules/observability/dashboard.tf
  - scripts/archive-evidence.sh, plan.sh, deploy.sh, run-bounded-pipeline.sh
  - scripts/README.md
- Interfaces:
  - No option name, exit code meaning or output path changed.
  - archive record and MANIFEST.json: `byte_copies_hold_identifiers` is now `true`, `false` or a string starting with `"not checked: "` (it was boolean only).
  - plan.sh: a failed identifier check now leaves no `.record.json`; only a `.record.json.partial` may remain, plus the `.tfplan` and the masked `.plan.txt`.
  - deploy.sh runtime-on: exit 6 also when an ALB role has running 0; new `EVIDENCE: service=... desired=... running=... pending=... revision=...` lines.
  - run-bounded-pipeline.sh hadoop record: `configuration.extra_args` (logical form) is new; the `configuration.note` text depends on the extra args.
- Fixtures: none. Thread safety: not applicable.
- Limits: unchanged timeouts (deploy.sh STABLE_TIMEOUT 1200 s; `print_service_counts` uses AWS_TIMEOUT 90 s, best effort).
- Runtime lines: every changed script `runtime: untested`.
- Plan validity: the dashboard change touches `infra/terraform/`, so every older plan is unusable. The script and README changes keep plans and the image valid. Build commit: unchanged by this step.

VALIDATION EVIDENCE
- fmt: terraform fmt -recursive -check infra/terraform -> 0, no files listed
- validate: terraform -chdir=infra/terraform/environments/course validate -> "Success! The configuration is valid."
- shellcheck: shellcheck /Users/brian/Developer/repos/scholarchain/scripts/archive-evidence.sh /Users/brian/Developer/repos/scholarchain/scripts/plan.sh /Users/brian/Developer/repos/scholarchain/scripts/deploy.sh /Users/brian/Developer/repos/scholarchain/scripts/run-bounded-pipeline.sh -> 0; clean
- git check-ignore: not applicable
- finished: after the last command above
- files changed after these runs: no
