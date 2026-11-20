# agent-report-infra-5a (Phase 15, session 2, step 5a)

interface: no (every fixed interface of decisions 5 and 9.3 is built as written)

STATUS: COMPLETE
OWNER: infra-engineer
WORK ORDER: docs/agent-runs/phase-15/prompt.md (sha256 3d806922c6dd74ada61bceca45d3a302740a4083f260f0ae3ca6b6b6b9dfa3e0), step 5a: decision 9 items 1 (SQS access and env var only), 2, 3, 5, 6 (a)-(e) and (f), 8, 9. HEAD 2cbfbc72c704c40085e2876479d510b90062a12a.
FILES CREATED: none
FILES MODIFIED:
- infra/terraform/environments/course/main.tf
- infra/terraform/environments/course/outputs.tf
- infra/terraform/modules/runtime/autoscaling.tf
- infra/terraform/modules/runtime/iam.tf
- infra/terraform/modules/runtime/main.tf
- infra/terraform/modules/runtime/variables.tf
- infra/terraform/modules/workflow/main.tf
- infra/terraform/modules/workflow/outputs.tf
- infra/terraform/modules/workflow/sfn.tf
- infra/terraform/modules/workflow/variables.tf
- infra/terraform/COSTS.md
- scripts/lib/identity.sh
- scripts/run-bounded-pipeline.sh
- scripts/run-experiment.sh
- scripts/smoke-test.sh
- scripts/archive-evidence.sh
COMMANDS RUN: `terraform fmt -recursive infra/terraform` (once, after the last .tf edit); `terraform fmt -recursive -check infra/terraform`; `terraform -chdir=infra/terraform/environments/course validate`; `shellcheck` on the five changed scripts plus scripts/lib/identity.sh; read-only git (`git status --short`); `shasum -a 256` (before/after checksums of every changed file); `grep`, `ls`, `wc`, `cut` for reading. No sbt, no script, no aws CLI, no terraform init/plan/apply/output. aws-api MCP: 0 calls. aws-docs MCP: EMR ComputeLimits and managed-scaling pages, Step Functions TestState, ValidateStateMachineDefinition and JSONata pages, CloudWatch missing-data page, Application Auto Scaling step-scaling page.
TEST RESULTS: not applicable (no Scala source; scripts are never run by agents in this phase). Every script change: runtime: untested.
VALIDATION RESULTS: fmt-check rc=0; validate "Success! The configuration is valid." rc=0; shellcheck rc=0 (no finding) on all six shell files. Details in VALIDATION EVIDENCE.
ASSUMPTIONS:
- A1. Step Functions supports the standard JSONata function `$error` (only `$eval` is listed as unsupported on the "Transforming data with JSONata" page). If its validator refuses it, D4a's `validate-state-machine-definition` shows it before the plan is signed (a Terraform repair then: replace `$error(...)` by a documented "undefined result" error).
- A2. A JSONata Pass state with no `Output` passes its input through unchanged (ReadInput already relied on this before Phase 15; the resolution reads only `$states.input`, so it does not depend on it).
- A3. `TestState` with `--inspection-level DEBUG` returns the Assign result in `inspectionData.variables` (the field exists in the API response syntax; that it is filled for a Pass state is unverified). If it is empty, the D4a line prints `null` and D7a is the first live check.
- A4. The domain key matches decision 5: `service.embeddingQueueUrl` with override `SCHOLARCHAIN_CFG_SERVICE_EMBEDDING_QUEUE_URL` (modules/core/src/main/scala/org/scholarchain/core/config/ConfigLoader.scala:240 and ScholarChainConfig.scala:388, read-only check of step 1's in-progress files).
RISKS: see "First-run risks of run-experiment.sh" and the alarm limits below. Main ones: (R1) `$error` acceptance (A1); (R2) Ctrl-C reaches the runner but not the action (GNU timeout's own process group), so a Ctrl-C can leave a collector or nested pipeline running; (R3) the runner now waits 300 s after every action before the export (contract of decision 10, newly implemented), so each run takes 5 minutes longer; (R4) archive-evidence.sh will now flag the Phase 14 smoke records under evidence/ (they hold `api_host`) as byte copies with identifiers (`byte_copies_hold_identifiers: true`) - expected, it is G-29's point.
MANUAL ACTION REQUIRED:
- experiments/README.md (experiment-engineer territory) must take the confirmed exit-code table below, including exit 8 and the corrections marked there.
- scripts/README.md (5b, item 7) must document the new options, `--check`, `--export-metrics`, the 300 s metrics wait and the exit table.
- D3/D4a: the human runs the D4a line set below on the saved runtime-on plan before signing.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: R-2 review (code-reviewer) of 5a with 6a and 6b; orchestrator's literal comparison of the script options against decision 9.3 and 6b's manifests; 5b (permission matrix, rag-retrieval-eval loader, README).

## Item status (one line per requirement, file:line)

- 9.1 SQS access, identity policy: done - infra/terraform/modules/runtime/iam.tf:222-234 (statement `EmbeddingQueueConsume`, worker only: ReceiveMessage, DeleteMessage, ChangeMessageVisibility on the embedding queue ARN; no SendMessage).
- 9.1 SQS access, queue policy: done - infra/terraform/modules/workflow/main.tf:86-91 (`WorkerConsumesEmbeddingTasks`, the same three actions, principals `embedding_consumer_role_arns`), variable infra/terraform/modules/workflow/variables.tf:83, wired at infra/terraform/environments/course/main.tf:216 (`[module.runtime.task_role_arns["worker"]]`). The ops statement `OpsUsesEmbeddingQueue` is unchanged.
- 9.1 boundary check: done (no change needed) - infra/terraform/modules/identity/boundary.tf:89-98 (`ProjectQueueMessages` allows the four SQS message actions on `arn:...:sqs:REGION:ACCOUNT:NAME_PREFIX-*`, which covers the embedding queue).
- 9.1 environment variable: done - infra/terraform/modules/runtime/main.tf:65-71 (`role_environment`: the worker gets `SCHOLARCHAIN_CFG_SERVICE_EMBEDDING_QUEUE_URL` from `var.embedding_queue_url`; query, evidence and graph keep exactly today's environment), used at main.tf:178; variable runtime/variables.tf:262; wired from the existing queue output at course/main.tf:183 (`module.workflow.queue_urls["embedding"]`; never a literal). The ops task is unchanged.
- 9.1 permission matrix of the whole re-embed chain: phase-15 step 5b (not this step).
- 9.2 worker scaling on both queues: done - new scale-out alarm infra/terraform/modules/runtime/autoscaling.tf:141-160 (`worker_out_embedding`, same `worker_out` policy, same statistic, period, threshold, `notBreaching`); scale-in alarm autoscaling.tf:162-224 (expression `visible + inflight + emb_visible + emb_inflight` at :178, four metrics, `treat_missing_data = "breaching"`, periods unchanged); citations in the header autoscaling.tf:14-30. `worker_max_count` 2, `worker_scale_in_minutes` 5, cooldowns and thresholds unchanged.
- 9.3 execution-input fields: done - resolution in ReadInput infra/terraform/modules/workflow/sfn.tf:250-260 (`clusterComputeLimits`, `sparkShuffleConf`), expressions sfn.tf:168-200, ComputeLimits use sfn.tf:137-140, Spark arguments sfn.tf:155-166 (both Spark steps use `spark_submit_runtime_jsonata`), default variable workflow/variables.tf:90-99, contract comment sfn.tf:45-75.
- 9.3 script options: done - scripts/run-bounded-pipeline.sh:431-432 (parsing), :477-490 (`check_execution_settings`), :495-501 (`execution_settings_json`), :505-513 (`add_execution_settings`), hadoop input :1341, record :1353, spark record :1589, spark input :1603; usage text in the hadoop and spark sections.
- 9.5 G-29 smoke-test.sh: done - record field `api_url: "{api-url}"` scripts/smoke-test.sh:264 (field `api_host` removed; `api_url_source` added), host registered and masked :328 and :339, curl error text kept private :376 and :379, every info/die line masked.
- 9.5 G-29 archive-evidence.sh: done - scripts/archive-evidence.sh:484 (`register_output_identifiers`: API host and the other endpoint identifiers join the masking and `check_no_identifiers`).
- 9.5 run-experiment.sh identifier list: done - scripts/run-experiment.sh:557 (API host, job, embedding and both DLQ URLs, ALB DNS name, Neptune endpoint), used by `mask_stream` (action.log) and the final folder check (finalize, :983-1019); shared code scripts/lib/identity.sh:252 (`register_extra_identifier`), :287 (`register_output_identifiers`), :313 (`sc_mask_extras`), :494 (`check_no_identifiers` extended). DLQ URLs come from the new output `dlq_urls` (course/outputs.tf:99, workflow/outputs.tf:13).
- 9.6(a) `{attempt-id}`: done - refusal of any other placeholder scripts/run-experiment.sh:484 (`check_placeholders`, exit 4 before any cloud call), resolution :566 (`resolve_args`), recorded as `action.resolved_args` (base_record), used by the one invocation path :666 (`invoke_action`).
- 9.6(b) metric placeholders: done - `{alb-dimension}` and `{alb-target-group-ROLE}` resolved at export, scripts/run-experiment.sh:784-800 (`METRIC_QUERY_JQ`), from the new outputs `alb_arn_suffix` and `target_group_arn_suffixes` (course/outputs.tf:104-112; ARN suffixes, no account ID). Coordination with experiment-engineer: built as the decision names them; 6b uses them or not.
- 9.6(c) `--check`: done - scripts/run-experiment.sh:888 (`metric_placeholder_report`), :911-955 (`run_check`; preflight through `invoke_action` with `--preflight`, temporary run folder outside the repository, at most 600 s, no run folder, no export).
- 9.6(d) metrics for failed and timed-out actions: done - `run_action` no longer stops the runner; `wait_for_metrics` (:808) and `export_metrics` run for every ended action; `finish_action` (:706) exits 5 or 7 afterwards. Only a signal-killed runner exports nothing.
- 9.6(e) exit 8: done - finalize returns 8 when the record or the run folder holds an identifier (:1010, :1019); documented in the usage text.
- 9.6(f) `--export-metrics ATTEMPT_ID`: done (cheap enough) - :957 (`prepare_export`), main's export branch; new folder `experiments/raw/MANIFEST_ID/ATTEMPT_ID-metrics-UTCSTAMP/`, record field `metrics_export_of`; never touches the original folder.
- 9.6 extra (found reading the flow): the 300 s post-action wait that decision 10 describes did not exist in the code (the export ran at once, so the last minutes had no data yet); added as `wait_for_metrics` (:808, `METRICS_SETTLE_SECONDS=300`). The action's tracked/executable refusal now runs before the run folder exists (main, :1064), so exit 4 never leaves a raw folder.
- 9.8 COSTS.md: done - infra/terraform/COSTS.md alarm row (3 worker alarms, 6 metrics) and two new per-use rows (worker on the embedding queue; 128-partition Spark run at up to 4 core nodes), each `estimate (unverified)`.
- 9.9 validation and expected plan: done - see VALIDATION EVIDENCE and "Expected D3 plan contents".

## 9.2 Alarm results per case (for R-2)

Alarms: `NAME-worker-scale-out` (job queue visible, Maximum, 60 s, 1 of 1, >= 1, notBreaching), `NAME-worker-scale-out-embedding` (same on the embedding queue), `NAME-worker-scale-in` (sum of visible and not-visible of both queues, Maximum per metric, 60 s, 5 of 5, < 1, breaching). Sources: CloudWatch metric-math page (https://docs.aws.amazon.com/AmazonCloudWatch/latest/monitoring/using-metric-math.html; quotation as given in decision 9.2, "missing values in a time series are treated as 0" in basic arithmetic; I re-read only its opening, so the quotation is the decision's), CloudWatch missing-data page (https://docs.aws.amazon.com/AmazonCloudWatch/latest/monitoring/alarms-and-missing-data.html, read: when fewer real datapoints than evaluation periods exist in the evaluation range, the missing ones are filled with the treat-missing-data value; when enough real points exist in the wider range, the most recent real points are used and the setting is ignored), Application Auto Scaling step-scaling page (https://docs.aws.amazon.com/autoscaling/application/userguide/step-scaling-policy-overview.html, read: "The adjustments can continue for additional alarm breaches until the alarm state returns to OK"; a scale-out policy "won't increase the desired capacity again unless either a larger scale out is triggered or the cooldown period ends"; "if another alarm triggers a scale-out activity during the scale-in cooldown period, Application Auto Scaling scales out the target immediately").

1. Job queue idle, embedding queue active: job metrics 0 or missing; sum = 0 + emb visible + emb in flight >= 1, not breaching: scale-in alarm OK. Embedding scale-out alarm ALARM while visible >= 1: +1 per breach after each 120 s cooldown, up to 2. Job scale-out alarm OK (missing = notBreaching).
2. Embedding idle, job queue active: the mirror image: job scale-out alarm drives the scale-out; the sum >= 1 while any job message is visible or in flight: no scale-in. Embedding scale-out alarm OK.
3. Both idle: each period the sum is 0 (both report 0, or one reports 0 and the other is missing, counted as 0) or missing (no queue reports): 0 < 1 is breaching, and missing is breaching by `treat_missing_data`. After 5 consecutive periods: ALARM, `worker_in` sets the desired count to 0 (intended).
4. A delayed datapoint during a backlog: (a) scale-out: a late embedding datapoint is missing at evaluation, `notBreaching`, so the alarm stays OK for that minute and scales out one evaluation later: a delay, never a wrong action. (b) scale-in: if the job queue reports 0 while the embedding queue's datapoint is late, that one period's sum is 0 (breaching); if the job queue reports nothing, the period is missing and the alarm uses the most recent real (non-breaching) points. Either way ALARM needs 5 consecutive breaching periods, so a delay shorter than about 5 minutes cannot scale the worker in.
5. Scale-in while a message is in flight: not-visible >= 1 makes the sum >= 1 (not breaching): no scale-in while any message of either queue is in flight.

Remaining limit (stated in autoscaling.tf:23-25): if SQS stopped publishing an ACTIVE queue's metrics for 5 minutes while the other queue reports 0 or nothing, the worker would scale in (SQS publishes active queues every minute). In-flight work would then be cut by the task stop (stopTimeout 120 s), and the messages return after their visibility timeout.

## 9.2 Expected timeline of a backlog for experiment C (each step an estimate, not measured)

- t0: the collector sends N messages (10 per send-message-batch call).
- t0 + about 1-3 min: the first one-minute SQS datapoint with visible >= 1 reaches CloudWatch (SQS metric delay, estimate), the embedding scale-out alarm goes to ALARM, `worker_out` +1: desired 0 -> 1.
- + about 1-2 min: the Fargate task starts (image pull through the NAT, JVM start; estimate) and the worker's embedding loop starts receiving; in-flight >= 1 from then on.
- first scale-out + 120 s (the `worker_out` cooldown): if visible >= 1 is still held in every one-minute period, the alarm is still in ALARM and a second +1 is applied: desired 1 -> 2 (cap `worker_max_count` 2), about t0 + 3-5 min; the second task runs about t0 + 5-7 min.
- drain at the 1-task or 2-task rate (step 3's handler rate; 6c sets N).
- when both queues have visible + in flight = 0: 5 consecutive breaching periods plus about 1-2 min of metric delay, then `worker_in` sets 0; tasks stop within the 120 s stopTimeout: scale-in about 6-9 min after the last message is deleted.
- Conditions for 0 -> 1 -> 2 -> 0: visible messages >= 1 in each one-minute period until after the second scale-out (so N must outlast first scale-out + cooldown + task start at the high processing-rate estimate); the job queue empty throughout (a job message only adds to the same policy; it would not break the sequence but would mix causes); both queues empty (visible plus in flight) for 5 minutes at the end. The observed sequence is C's outcome, not a promise.

## 9.3 State machine before and after (absent, A, B, invalid)

Unit type `Instances`: one unit is one EC2 instance; the limits apply to core and task nodes only, never the primary node (EMR API Reference, ComputeLimits: https://docs.aws.amazon.com/emr/latest/APIReference/API_ComputeLimits.html; Management Guide, "Using managed scaling", https://docs.aws.amazon.com/emr/latest/ManagementGuide/emr-managed-scaling.html: On-Demand limit and maximum core nodes default to MaximumCapacityUnits; the "Scale core nodes only" scenario sets both equal to the maximum, as here). Terraform values today: min 2, max 4, core count at start 2 (course/variables.tf:110-126). The Spark arguments' other entries (`--driver-memory 6g`, the four UI retention confs) are unchanged in every case.

| Execution input | Before (Phase 14 definition) ComputeLimits; Spark `--conf` | After ComputeLimits (resolved in ReadInput); Spark `--conf` |
|---|---|---|
| absent (no `cluster`, no `spark.shufflePartitions`) | {Instances, Min 2, Max 4, OnDemand 4, Core 4}; `spark.sql.shuffle.partitions=32` | {Instances, Min 2, Max 4, OnDemand 4, Core 4} (taken from the Terraform values, byte-for-byte the same request); `spark.sql.shuffle.partitions=32` |
| A: `cluster.managedScalingMaxUnits` 2, `spark.shufflePartitions` 32 | fields ignored: {Instances, 2, 4, 4, 4}; 32 | {Instances, Min 2, Max 2, OnDemand 2, Core 2}; `spark.sql.shuffle.partitions=32` |
| B: 4 and 128 | fields ignored: {Instances, 2, 4, 4, 4}; 32 | {Instances, Min 2, Max 4, OnDemand 4, Core 4}; `spark.sql.shuffle.partitions=128` |
| invalid (for example max units 5 or 1, a string "3", 2.5; partitions 4 or 1024) | fields ignored, clusters created | ReadInput fails with `States.QueryEvaluationError` (cause carries "InvalidExecutionSettings: ..."); a Pass state has no Catch, so the execution FAILS in its first state, before any cluster exists |

Rules and sources: the Terraform bounds (2..4 for units, 8..512 for partitions) are interpolated into the JSONata at plan time; the script also refuses partitions outside 8..512 and units outside 1..64 (usage error 2). The three maxima equal the resolved value, which is at least `MinimumCapacityUnits`, so neither limit is below the minimum. JSONata: the Pass state's Assign reads only `$states.input` (every Assign of one state sees the variables from before the state); expression errors raise `States.QueryEvaluationError` (Step Functions developer guide, "Transforming data with JSONata", "Handling expression errors": https://docs.aws.amazon.com/step-functions/latest/dg/transforming-data.html). Query language: the whole definition is JSONata at the top level (`QueryLanguage = "JSONata"`, sfn.tf `definition`), so the new expressions sit in the same query language as every surrounding state; no per-state override is needed.

## 9.3 D4a read-only line set (for the orchestrator to fill PLAN_NAME from plans/*.record.json; run by the human with the admin profile, after plan.sh, before signing)

Neither call starts an execution or changes AWS. `TestState` "Accepts the definition of a single state and executes it"; with `stateName` the definition "must contain a fully-formed state machine definition"; `roleArn` is "Required: No" (API page https://docs.aws.amazon.com/step-functions/latest/apireference/API_TestState.html). ReadInput is a Pass state that touches no AWS resource, so no `--role-arn` is passed (no role output needed). `ValidateStateMachineDefinition` returns `result` OK or FAIL and `diagnostics[].code` (https://docs.aws.amazon.com/step-functions/latest/apireference/API_ValidateStateMachineDefinition.html). The definition holds role ARNs and S3 locations: it stays in the shell variable and is never printed.

```
D="$(terraform -chdir=infra/terraform/environments/course show -json plans/PLAN_NAME.tfplan | jq -r '.resource_changes[] | select(.address == "module.workflow.aws_sfn_state_machine.pipeline") | .change.after.definition')"; printf 'definition bytes: %s\n' "${#D}"
aws stepfunctions validate-state-machine-definition --region us-east-1 --type STANDARD --severity WARNING --definition "$D" --output json | jq -c '{result, codes: [.diagnostics[]?.code]}'
for I in '{"codeVersion":"x"}' '{"codeVersion":"x","cluster":{"managedScalingMaxUnits":2},"spark":{"shufflePartitions":32}}' '{"codeVersion":"x","cluster":{"managedScalingMaxUnits":4},"spark":{"shufflePartitions":128}}' '{"codeVersion":"x","cluster":{"managedScalingMaxUnits":5}}'; do aws stepfunctions test-state --region us-east-1 --definition "$D" --state-name ReadInput --input "$I" --inspection-level DEBUG --output json | jq -c '{status, error, next: .nextState, limits: ((.inspectionData.variables // "{}") | fromjson | .clusterComputeLimits), conf: ((.inspectionData.variables // "{}") | fromjson | .sparkShuffleConf)}'; done; unset D I
```

Expected: definition bytes > 0; `{"result":"OK","codes":[]}` (warnings would be listed by code only); absent -> SUCCEEDED, next ExportInputCheck, limits {Instances, 2, 4, 4, 4}, conf `spark.sql.shuffle.partitions=32`; A -> limits max 2 (all three maxima 2), conf `...=32`; B -> limits max 4, conf `...=128`; invalid -> status FAILED, error `States.QueryEvaluationError`, limits and conf null. A wrong result is a Terraform repair before signing. If the `variables` field is empty for a Pass state (A3), limits and conf print null for the valid cases: then only the validate line and the invalid case are checked here, and D7a (`p15-a-hadoop`) is the first live check of the values (its cluster's managed-scaling policy can be read afterwards with `aws emr get-managed-scaling-policy --cluster-id ID --query 'ManagedScalingPolicy.ComputeLimits'`).

## Exit-code table, confirmed against scripts/run-experiment.sh after 9.6(d)(e) (for experiments/README.md; corrections marked)

| Runner exit | Meaning | Collector exit behind it | Final folder in experiments/raw/ | Remote work may still run | Overnight chain |
|---|---|---|---|---|---|
| 0 | action succeeded, metrics exported (or none declared); `--check`: all checks and the preflight passed | 0 | yes (`--check`: no folder) | no | continue |
| 2 | usage error | - | no | no | stop |
| 3 | missing prerequisite or Terraform output (runner's own) | - | no | no | stop |
| 4 | refused (dirty tree, untracked manifest, sha256, example, v1 check, placeholder other than {attempt-id}/s3://{data-bucket}/, approval, run or raw folder exists, action path untracked or not executable) | - | no (correction: the action-path refusal now also happens before the folder exists) | no | stop |
| 5 | the action failed (metrics exported after the 300 s wait; status `failed`); also a runner command failure after the start, Ctrl-C (INT, status `failed`, signal INT, no export), and `--check`'s failed preflight | any non-zero other than 124/137, including 3, 5, 7, or the nested pipeline's failure | yes (status `failed`), except `--check` (no folder) | possibly | continue only after the active-cluster check |
| 6 | action succeeded, metrics failed or partial (`succeeded-metrics-failed`) | 0 | yes | no | continue |
| 7 | the runner's time limit (`maxDurationSeconds`; status `timed-out`, metrics exported, MAY-STILL-RUN lines) or TERM (status `timed-out`, signal TERM, no export); `--check`: preflight past 600 s | - (the action was killed) | yes (time limit); yes or only a `started` record (TERM) | yes | stop |
| 8 | an identifier (account ID, bucket name, API host, queue or DLQ URL, ALB or Neptune host) in the run record or the run folder; the folder stays under evidence/experiment-runs/ | - | no | no | stop |
| anything else | unknown | - | - | - | stop |

Notes: a collector's own exit 7 (its wait limit) becomes runner exit 5 (the runner reports only "the action failed"); runner exit 7 always means the runner's limit or TERM. Exit 4 can also come from finalize when the raw destination appeared meanwhile (the run stays under evidence/). `--export-metrics` exits 0, 6 or 8 (plus 2, 3, 4, 5).

## First-run risks of run-experiment.sh (read end to end once more)

1. Ctrl-C: GNU timeout runs the action in its own process group, so a terminal Ctrl-C reaches the runner (INT -> exit 5, record `failed`, signal INT) but not the action, which may keep running until its next write to the closed pipe. After a Ctrl-C, check for a running collector or nested pipeline (and EMR clusters) before the next step. (Pre-existing; proposed later: forward INT/TERM to the action.)
2. Every run now takes 300 s longer (metrics wait, also after a failed action). A Ctrl-C during this wait records `failed` with signal INT even if the action succeeded, and exports nothing; `--export-metrics` can recover the export later from the committed folder.
3. The ALB placeholders and `dlq_urls` need the new outputs, which exist only after D4 applies the D3 plan. Before that, ALB queries are unresolved (exit 6, partial) and the DLQ URLs are not registered (they still contain the account ID, which is registered and masked).
4. A collector must print `RUN-RECORD: evidence/runs/...json` at the start of a line (the runner greps `^RUN-RECORD: `) and must not wrap the nested pipeline's output with prefixes; otherwise no nested records and no `{emr-cluster-id}` resolution (EMR metrics unresolved, exit 6).
5. The action's executable bit is a human step before the commit (decision 16); without it the runner refuses with exit 4 (now before any folder).
6. `--check` runs the collector's `--preflight` with a sample attempt ID through the real path; a collector that does not accept `--preflight` as the LAST argument fails the check (exit 5). Pipeline-kind manifests skip the preflight (said so).
7. The placeholder refusal rejects `{vector-bucket}` and any `{...}` other than `{attempt-id}` and `s3://{data-bucket}/` in action.args (decision 9.6(a) rule); a manifest that uses another brace text is refused with exit 4 before any cloud call.
8. The identifier check is `grep -F` on the run folder: a collector that writes an API host, queue URL, ALB or Neptune host anywhere (including error text it captured) makes the run exit 8 with the folder kept under evidence/; the raw results then need a repair and a new attempt.
9. CloudWatch `get-metric-data` paging relies on the AWS CLI's automatic pagination (default on); a manifest with many series over a long window returns more pages, not an error (unverified for the largest windows).

## Expected D3 plan contents from 5a's changes (from the code, not a checked number)

- update in place: `module.runtime.aws_iam_role_policy.service_task["worker"]` (statement `EmbeddingQueueConsume`).
- update in place: `module.workflow.aws_sqs_queue_policy.this["embedding"]` (statement `WorkerConsumesEmbeddingTasks`).
- create: `module.runtime.aws_cloudwatch_metric_alarm.worker_out_embedding[0]` (one new alarm).
- update in place: `module.runtime.aws_cloudwatch_metric_alarm.worker_in[0]` (two new metric queries, new expression, description).
- update in place: `module.workflow.aws_sfn_state_machine.pipeline` (definition only).
- replace (new revision; skip_destroy keeps the old one ACTIVE): the five task definitions `module.runtime.aws_ecs_task_definition.service["query"|"evidence"|"graph"|"worker"]` and `module.runtime.aws_ecs_task_definition.ops`, from the new image tag (the worker's also from the new environment variable); update in place: the four `module.runtime.aws_ecs_service.this[...]` (task definition).
- outputs added: `dlq_urls`, `alb_arn_suffix`, `target_group_arn_suffixes` (no resource).
- no create, replace or delete of any stateful resource (no SQS queue, table, bucket, index, Neptune or ECR change). 5b will add its own permission changes (at least `s3vectors:PutVectors` for the worker, `neptune-db:ReadDataViaQuery` for ops, the completion-record table access if needed).

## IAM changes

- worker task role, inline policy `worker-runtime`: + `sqs:ReceiveMessage`, `sqs:DeleteMessage`, `sqs:ChangeMessageVisibility` on the embedding queue only. Within the boundary (`ProjectQueueMessages`).
- embedding queue resource policy: + statement allowing the same three actions to the worker role ARN only. TLS deny statement unchanged.
- No other role, no boundary change, no SendMessage for the worker.

## Stateful-resource actions

None. Queue attributes, DLQs, tables, buckets, indexes and Neptune are untouched.

## Cost estimate

- new alarm and 2 more alarm metrics: about $0.01 per day (estimate (unverified), COSTS.md alarm rate).
- worker on the embedding queue during C and F: about $0.04 per task-hour (estimate (unverified), Fargate rates in COSTS.md); request costs well under $0.05 per 5 000 documents (estimate).
- B at up to 4 core nodes: at most about $1.20 per hour plus EBS (estimate (unverified), EMR row of COSTS.md); A at maximum 2: about $0.72 per hour.

## Required environment variables (names only)

- `SCHOLARCHAIN_CFG_SERVICE_EMBEDDING_QUEUE_URL` (worker task; value from Terraform at plan time).
- `SCHOLARCHAIN_EXPERIMENT_RUN_DIR`, `SCHOLARCHAIN_EXPERIMENT_ATTEMPT_ID`, `SCHOLARCHAIN_EXPERIMENT_MANIFEST_ID` (set by run-experiment.sh for the action; unchanged names).
- `SCHOLARCHAIN_SERVICE_API_KEY` (smoke-test.sh; unchanged).

## Proposed later (not built)

- Forward INT/TERM from run-experiment.sh to the action's process group (risk 1).
- A `--preflight` mode for run-bounded-pipeline.sh, so pipeline-kind manifests can be checked by `--check` (today only command-kind collectors are).

## INTERFACE HANDOFF

Files changed: as listed under FILES MODIFIED.

run-bounded-pipeline.sh (decision 9.3, fixed interface, as written):
- `--emr-max-units N`: stages hadoop and spark only (any other stage: exit 2); integer 1..64 without leading zero (exit 2 otherwise); the state machine refuses outside the Terraform min..max (today 2..4) by failing the execution in ReadInput. Execution-input field `cluster.managedScalingMaxUnits` (JSON number).
- `--spark-shuffle-partitions N`: stage spark only; integer 8..512 (exit 2 otherwise). Execution-input field `spark.shufflePartitions` (JSON number).
- Absent options add nothing to the input (byte-for-byte the Phase 14 input).
- Record: `configuration.execution_settings` = `{emr_max_units: N|null, spark_shuffle_partitions: N|null, note}` (hadoop and spark records); `inputs.execution_input` (logical) also carries the two fields when given.

State machine (workflow/sfn.tf): variables `clusterComputeLimits` (object: UnitType, MinimumCapacityUnits, MaximumCapacityUnits, MaximumOnDemandCapacityUnits, MaximumCoreCapacityUnits) and `sparkShuffleConf` (string `spark.sql.shuffle.partitions=N`), both set in state `ReadInput`; invalid input -> `States.QueryEvaluationError`, cause text starting `InvalidExecutionSettings:`; default partitions variable `spark_shuffle_partitions_default` = 32.

run-experiment.sh:
- Options: `--manifest PATH --manifest-sha256 HEX64` [`--check` | `--export-metrics ATTEMPT_ID`] (the two are exclusive; exit 2).
- action.args placeholders: `{attempt-id}` (anywhere, replaced by the runner with the run's attempt ID); `{data-bucket}` only as `s3://{data-bucket}/...` (passed unchanged); anything else: exit 4 before any cloud call.
- Metric dimension placeholders: `{state-machine-arn}`, `{emr-cluster-id}`, `{alb-dimension}` (value `app/NAME/ID`), `{alb-target-group-query}`, `{alb-target-group-evidence}`, `{alb-target-group-graph}` (value `targetgroup/NAME/ID`).
- Environment passed to the action: `SCHOLARCHAIN_EXPERIMENT_RUN_DIR`, `SCHOLARCHAIN_EXPERIMENT_ATTEMPT_ID`, `SCHOLARCHAIN_EXPERIMENT_MANIFEST_ID`; standard input /dev/null; `--check` appends `--preflight` as the LAST argument and points RUN_DIR to a temporary folder outside the repository.
- Exit codes: table above (0, 2, 3, 4, 5, 6, 7, 8).
- Record (`scholarchain-experiment-run-record`, version 1) new fields: `action.resolved_args` (array or null), `action.run` (false for an export-only record), `metrics_export_of` (attempt ID or null); `metrics.status` one of `exported`, `partial`, `failed`, `none-declared`, `not-run`.
- Printed markers: `ATTEMPT:`, `DECLARED (not enforced):`, `PLACEHOLDER-CHECK:`, `CHECK:`, `PREFLIGHT:`, `METRICS-WAIT:`, `MAY-STILL-RUN:`, `IDENTIFIER-CHECK:`, `RUN-STATUS:`, `RAW-RESULTS:`, `RESULT:`.
- Paths: run folder `evidence/experiment-runs/ATTEMPT_ID/`, final `experiments/raw/MANIFEST_ID/ATTEMPT_ID/`; export-only `experiments/raw/MANIFEST_ID/ATTEMPT_ID-metrics-YYYYMMDDTHHMMSSZ/`.

scripts/lib/identity.sh: `register_extra_identifier KIND VALUE` (KIND `[a-z0-9][a-z0-9-]{0,39}`, VALUE at least 8 characters, at most 32 values; masked as `<KIND>`); `register_output_identifiers OUTPUTS_JSON` (kinds `api-host`, `job-dlq-url`, `embedding-dlq-url`, `job-queue-url`, `embedding-queue-url`, `alb-host`, `neptune-host`); `check_no_identifiers` now also reports these kinds.

smoke-test.sh record: field `api_host` removed; `api_url` = `"{api-url}"`, `api_url_source` = `terraform-output` | `--api-url`.

Terraform outputs added: `dlq_urls` (map jobs, embedding), `alb_arn_suffix`, `target_group_arn_suffixes` (map query, evidence, graph). Alarm names: `scholarchain-course-worker-scale-out`, `scholarchain-course-worker-scale-out-embedding` (new), `scholarchain-course-worker-scale-in`.

Thread-safety and limits: shell, single process; every cloud call bounded by timeout; preflight at most 600 s; metrics wait 300 s.

## VALIDATION EVIDENCE

- `terraform fmt -recursive infra/terraform` -> rc=0, no file listed (run once, after the last .tf edit).
- `terraform fmt -recursive -check infra/terraform` -> rc=0.
- `terraform -chdir=infra/terraform/environments/course validate` -> "Success! The configuration is valid." rc=0.
- `shellcheck scripts/archive-evidence.sh scripts/lib/identity.sh scripts/run-bounded-pipeline.sh scripts/run-experiment.sh scripts/smoke-test.sh` -> rc=0, no output (an earlier run showed two SC2015 infos in run-bounded-pipeline.sh; fixed with if-statements before the final run).
- `shasum -a 256` of all 16 changed files before the final fmt-check/validate/shellcheck and after: combined digest f1b0ebe06075eb0d3ec56a1290678fd2d5ccc561410cb44a96b211d7bcbd3c01 both times.
- `git status --short infra scripts` -> the 16 files above, all ` M`; nothing untracked under infra/ or scripts/.
files changed after these runs: none
