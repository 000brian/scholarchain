# repair-D7a-infra-engineer (Phase 15 session 3, finding L-01, class 4 Terraform)

Work order: docs/agent-runs/phase-15/prompt.md (sha256 3d806922c6dd74ada61bceca45d3a302740a4083f260f0ae3ca6b6b6b9dfa3e0, checked), decisions 9.3 and 13 ("When a step fails"), HEAD 6a6c31a. Write territory used: infra/terraform/modules/workflow/sfn.tf only. No scripts, manifests or experiments touched.

## Finding L-01 and the fix

Cause: ReadInput accepted `cluster.managedScalingMaxUnits` in min..max (2..4) and put it into `ManagedScalingPolicy.ComputeLimits`; with 2 the request held Min 2 = Max 2, which EMR RunJobFlow refuses ("The specified max capacity must be greater than min capacity", D7a attempt 1).

Fix (human-approved rule): when the resolved maximum equals the minimum, the cluster is created WITHOUT `ManagedScalingPolicy` (a fixed cluster at its start size = core instance group `InstanceCount`, `cluster_settings.core_instance_count`, 2 today = the minimum). Absent input and every value above the minimum give the same request as before. Applies to both cluster-creating states (CreateHadoopCluster, CreateSparkCluster); they are the only createCluster states.

How a field is omitted (docs, aws-docs MCP):
- A JSONata field whose expression is undefined is an error, not an omitted field: "JSON cannot represent an undefined value expression, so the expression `{% $data.thisFieldDoesNotExist %}` would result in an error" -> `States.QueryEvaluationError` (Step Functions developer guide, "Transforming data with JSONata", "Handling expression errors", https://docs.aws.amazon.com/step-functions/latest/dg/transforming-data.html). So the field cannot stay in an Arguments object with an "undefined" value.
- `Arguments` "will accept either a JSON object ... Or, you can use a JSONata expression directly" (same page, "Arguments and Output Fields"). So each create state now takes its whole request from one variable, built in ReadInput with `$merge` (standard JSONata 2.0.6 function; the page says all built-in functions except `$eval` are supported) of: the common static fields, the per-cluster fields, and either `{ManagedScalingPolicy: {ComputeLimits: ...}}` or `{}`.
- EMR: RunJobFlow `ManagedScalingPolicy` is "Required: No" (https://docs.aws.amazon.com/emr/latest/APIReference/API_RunJobFlow.html); ComputeLimits Min/Max/UnitType "Required: Yes" inside a policy (https://docs.aws.amazon.com/emr/latest/APIReference/API_ComputeLimits.html); ManagedScalingPolicy.ComputeLimits (https://docs.aws.amazon.com/emr/latest/APIReference/API_ManagedScalingPolicy.html). The rule "max must be greater than min" is not stated on these pages; it is taken from the observed D7a ValidationException (measured, not documented).
- `unverified`: that Step Functions' JSONata accepts Terraform `jsonencode` output as an object constructor in every case (JSON object literals are JSONata object constructors; Terraform escapes `<`, `>`, `&` as `<` etc., which JSONata string literals accept per JSON escape rules). This is exactly what the D4a test-state lines check, because the full requests are built in ReadInput.

## Changes (file:line, after fmt)

- infra/terraform/modules/workflow/sfn.tf:56-86 header comment: rule "value above the minimum -> policy; equal -> no policy", sources, why the whole request is one JSONata expression, variables `hadoopClusterRequest` / `sparkClusterRequest` (replaces `clusterComputeLimits`).
- sfn.tf:142-160 `cluster_common`: `ManagedScalingPolicy` removed (otherwise unchanged fields), comment.
- sfn.tf:162-174 new `cluster_kinds` (Name, ReleaseLabel, Applications of hadoop and spark; values moved unchanged from the two create states).
- sfn.tf:216-228 new `scaling_policy_given`, `scaling_policy_default` (Terraform: `{}` if the Terraform max equals the Terraform min, else today's default policy; with 2..4 today it is the policy), `resolve_cluster_scaling` (same validation as before, plus `X = min ? {} : policy(X)`); the old `resolve_compute_limits` is removed.
- sfn.tf:230-236 new `cluster_request` = `{% $merge([<jsonencode(cluster_common)>, <jsonencode(kind fields)>, <scaling>]) %}` per cluster.
- sfn.tf:291-307 ReadInput: Assign `hadoopClusterRequest`, `sparkClusterRequest` (instead of `clusterComputeLimits`); `sparkShuffleConf` unchanged.
- sfn.tf:340-342 CreateHadoopCluster `Arguments = "{% $hadoopClusterRequest %}"`; sfn.tf:393-395 CreateSparkCluster `Arguments = "{% $sparkClusterRequest %}"`. Resource, Assign, Catch, Next unchanged.
- Diff: 76 insertions, 34 deletions in sfn.tf; file sha256 e22b139751184a5fbeddbbec814cf583a21111065a41c7e0aca85dde2037a07c.

## Before / after (createCluster request of whichever cluster the execution creates; Terraform today: min 2, max 4, unit Instances, core InstanceCount 2)

"Rest" = every request field other than ManagedScalingPolicy (Name, ReleaseLabel, Applications, LogUri, ServiceRole, JobFlowRole, VisibleToAllUsers, BootstrapActions, Instances, StepConcurrencyLevel, AutoTerminationPolicy, Tags).

| Execution input `cluster.managedScalingMaxUnits` | Before (deployed definition) | After (this repair) |
|---|---|---|
| absent | ManagedScalingPolicy {Instances, Min 2, Max 4, OnDemand 4, Core 4}; rest as Terraform | identical: ManagedScalingPolicy {Instances, 2, 4, 4, 4}; rest identical values |
| 2 (A: 2, partitions 32) | ManagedScalingPolicy {Instances, 2, 2, 2, 2} -> EMR ValidationException, no cluster | NO ManagedScalingPolicy field; rest identical; fixed cluster: 1 primary + 2 core (InstanceCount 2), no resizes |
| 3 | {Instances, 2, 3, 3, 3} | identical: {Instances, 2, 3, 3, 3}; rest identical |
| 4 (B: 4, partitions 128) | {Instances, 2, 4, 4, 4} | identical: {Instances, 2, 4, 4, 4}; rest identical |
| invalid (5, 1, "3", 2.5) | ReadInput FAILED, States.QueryEvaluationError ("InvalidExecutionSettings: ..."), no cluster | unchanged: ReadInput FAILED, States.QueryEvaluationError, same message, no cluster |

Spark `--conf spark.sql.shuffle.partitions=N` (`sparkShuffleConf`) is unchanged in every row (absent 32, A 32, B 128; invalid partitions fail ReadInput as before).

"Byte-for-byte": the request is the same JSON value (same members, same values; D4a `restSameAsBefore` compares the new resolved request with the deployed definition's literal Arguments). The member ORDER of the request object can differ ($merge appends ManagedScalingPolicy last; before, Terraform's jsonencode sorted keys); JSON object member order carries no meaning, and how Step Functions serializes the EMR call is not visible to us (unverified, not material).

## Evidence consequence for A (p15-a-hadoop and p15-a-spark, both max 2)

- EMR Management Guide, "Understanding managed scaling metrics" (https://docs.aws.amazon.com/emr/latest/ManagementGuide/managed-scaling-metrics.html): the capacity metrics (Total/Core/Task Nodes Requested and Running) "are only available when managed scaling is enabled"; the YARN usage metrics (AppsPending, AppsRunning, ContainerPending, ContainerAllocated, YARNMemoryAvailablePercentage, ...) "are available for all Amazon EMR features, but are published at a higher resolution with data at a one-minute granularity when managed scaling is enabled".
- So for A without a policy: CoreNodesRequested, CoreNodesRunning, TotalNodesRequested, TotalNodesRunning, TaskNodesRequested, TaskNodesRunning (in the p15-a-hadoop manifest at periodSeconds 60) are expected to be EMPTY; the YARN series are expected at coarser resolution (EMR's standard interval; 5 minutes is the documented default for non-managed-scaling metrics, unverified on this page), so 60 s buckets will be sparse. CoreNodesPending: standard metric, coarse (unverified). The Step Functions series are unaffected.
- The runner may therefore classify A's metrics as `partial` (exit 6) although the action succeeded. A's fixed size is better evidenced read-only from the cluster itself (instance group CORE requested/running count 2, and `get-managed-scaling-policy` returning no policy) than from CloudWatch. This is a reading consequence only; no manifest is changed here (experiment-engineer / human decide whether A's series list or interpretation note changes; a changed manifest needs a new approval line).

## Expected new runtime-on plan contents (from the code, not a checked plan)

- update in place: `module.workflow.aws_sfn_state_machine.pipeline` (attribute `definition` only).
- nothing else: no IAM change (the role keeps `PutManagedScalingPolicy`, harmless when unused), no create, replace or delete, no stateful resource, no output change. Anything else in the plan (with the same overrides as D3/D4: runtime_enabled=true, the same image_tag and graph_run_id) is unexplained: stop and ask before signing.

## 9.3 D4a read-only line set (repair L-01; orchestrator fills PLAN_NAME from plans/*.record.json; run by the human with the admin profile, after plan.sh, before signing)

Nothing here starts an execution or changes AWS: `ValidateStateMachineDefinition` and `TestState` on the Pass state ReadInput only (never a Task state). The definition and the old Arguments contain role ARNs and S3 locations: they stay in shell variables and are never printed. `restSameAsBefore` compares the new resolved request (minus ManagedScalingPolicy) with the DEPLOYED definition's literal Arguments (plan `before`, minus its ManagedScalingPolicy), both clusters.

```
J="$(terraform -chdir=infra/terraform/environments/course show -json plans/PLAN_NAME.tfplan | jq -c '.resource_changes[] | select(.address == "module.workflow.aws_sfn_state_machine.pipeline") | .change')"; D="$(printf '%s' "$J" | jq -r '.after.definition')"; OLD="$(printf '%s' "$J" | jq -c '.before.definition | fromjson | {hadoop: (.States.CreateHadoopCluster.Arguments | del(.ManagedScalingPolicy)), spark: (.States.CreateSparkCluster.Arguments | del(.ManagedScalingPolicy))}')"; printf 'definition bytes: %s; old arguments bytes: %s\n' "${#D}" "${#OLD}"
aws stepfunctions validate-state-machine-definition --region us-east-1 --type STANDARD --severity WARNING --definition "$D" --output json | jq -c '{result, codes: [.diagnostics[]?.code]}'
for I in '{"codeVersion":"x"}' '{"codeVersion":"x","cluster":{"managedScalingMaxUnits":2},"spark":{"shufflePartitions":32}}' '{"codeVersion":"x","cluster":{"managedScalingMaxUnits":3}}' '{"codeVersion":"x","cluster":{"managedScalingMaxUnits":4},"spark":{"shufflePartitions":128}}' '{"codeVersion":"x","cluster":{"managedScalingMaxUnits":5}}'; do aws stepfunctions test-state --region us-east-1 --definition "$D" --state-name ReadInput --input "$I" --inspection-level DEBUG --output json | jq -c --argjson old "$OLD" '((.inspectionData.variables // "{}") | fromjson) as $v | {status, error, next: .nextState, hadoopPolicy: (if $v.hadoopClusterRequest == null then null else ($v.hadoopClusterRequest.ManagedScalingPolicy // "absent") end), sparkPolicy: (if $v.sparkClusterRequest == null then null else ($v.sparkClusterRequest.ManagedScalingPolicy // "absent") end), restSameAsBefore: ((($v.hadoopClusterRequest // {}) | del(.ManagedScalingPolicy)) == $old.hadoop and (($v.sparkClusterRequest // {}) | del(.ManagedScalingPolicy)) == $old.spark), conf: $v.sparkShuffleConf}'; done; unset J D OLD I
```

Expected:
- definition bytes > 0 (larger than D3's 12,931: both requests are now in ReadInput, estimate about +6 to +8 KB); old arguments bytes > 0 (0 or "null" means the plan has no `before`, i.e. the jq path is wrong: stop).
- validate: `{"result":"OK","codes":[]}`.
- absent: status SUCCEEDED, next ExportInputCheck, hadoopPolicy = sparkPolicy = `{"ComputeLimits":{"UnitType":"Instances","MinimumCapacityUnits":2,"MaximumCapacityUnits":4,"MaximumOnDemandCapacityUnits":4,"MaximumCoreCapacityUnits":4}}` (member order may differ), restSameAsBefore true, conf `spark.sql.shuffle.partitions=32`.
- A (2/32): SUCCEEDED, hadoopPolicy = sparkPolicy = `"absent"`, restSameAsBefore true, conf `...=32`.
- 3: SUCCEEDED, both policies max 3 (all three maxima 3, min 2), restSameAsBefore true, conf `...=32`.
- B (4/128): SUCCEEDED, both policies {Instances, 2, 4, 4, 4}, restSameAsBefore true, conf `...=128`.
- invalid (5): status FAILED, error `States.QueryEvaluationError`, both policies null, restSameAsBefore false (no request built), conf null.
- Any other result (in particular FAILED for a valid case: the JSON-in-JSONata request did not parse, or restSameAsBefore false for a valid case) is a Terraform repair before signing.

## IAM changes

None. The Step Functions role policy is unchanged (it still allows `elasticmapreduce:PutManagedScalingPolicy`, used only when a policy is sent).

## Stateful-resource actions

None. The state machine is stateless configuration; running executions keep the definition they started with (none expected at apply time: the human checks no execution is RUNNING before D4-style apply).

## Cost estimate

Plan/apply of one definition update: $0. A at a fixed 1 primary + 2 core is the same size A was meant to have (max 2), so its EMR cost bound is unchanged (estimate, from the manifest's own cost line). No new resource.

## Required environment variables (names only)

None new.

## Proposed later (not built)

- A Terraform precondition that `core_instance_count` equals `managed_scaling_min_units` (today both 2): otherwise a "max = min" execution would run fixed at the core count, not at the minimum. Not needed for Phase 15 values.

STATUS: COMPLETE
OWNER: infra-engineer
WORK ORDER: Phase 15 session 3 repair L-01 (D7a, class 4 Terraform); docs/agent-runs/phase-15/prompt.md sha256 3d806922c6dd74ada61bceca45d3a302740a4083f260f0ae3ca6b6b6b9dfa3e0; HEAD 6a6c31a
FILES CREATED: none in the repository (this report in the scratchpad)
FILES MODIFIED: infra/terraform/modules/workflow/sfn.tf
COMMANDS RUN: shasum -a 256 (work order); grep (read-only, sfn.tf and docs); terraform fmt -recursive infra/terraform (exit 0); terraform fmt -recursive -check infra/terraform (exit 0); terraform -chdir=infra/terraform/environments/course validate (Success, exit 0); git diff --stat/--numstat, git status --short. aws-docs MCP reads (Step Functions "Transforming data with JSONata"; EMR API ComputeLimits, ManagedScalingPolicy, RunJobFlow; EMR managed-scaling metrics). No aws-api call, no AWS CLI, no plan/init/apply, no scripts, no sbt, no docker.
TEST RESULTS: not applicable (Terraform only; no sbt tests). Runtime behavior is checked by the D4a test-state lines above (human).
VALIDATION RESULTS: fmt exit 0; fmt -check exit 0; validate "Success! The configuration is valid."
ASSUMPTIONS: (1) EMR's max > min rule is as observed in D7a (not on the API pages read). (2) Step Functions JSONata accepts the jsonencode JSON object literals and `{}` inside $merge (unverified; checked by D4a test-state on ReadInput). (3) A Task state's Arguments may be one JSONata expression for the EMR optimized integration (documented generally for Arguments; that createCluster.sync's own validation accepts it is checked by D4a validate and, finally, by the apply of the definition update). (4) TestState fills inspectionData.variables for a Pass state (confirmed at D4a in sitting 1).
RISKS: (1) If createCluster.sync rejects an expression Arguments, validate or the apply fails before any cluster exists (definition update refused, old definition stays). (2) A's managed-scaling CloudWatch capacity series are expected empty and YARN series coarse (see "Evidence consequence"); A's metrics may be `partial`. (3) Executions started before the apply use the old definition (A with max 2 would fail again).
MANUAL ACTION REQUIRED: code-reviewer review of sfn.tf; human commit; new runtime-on plan (scripts/plan.sh, same overrides as D3); D4a lines above; human signature; deploy.sh; then D7a attempt 2. Decide whether A's metric list or interpretation note needs a change (experiment-engineer; manifests not touched here).
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer review -> human commit -> plan.sh (runtime-on) -> D4a lines -> human signature -> deploy.sh -> D7a attempt 2

## INTERFACE HANDOFF

- Files changed: infra/terraform/modules/workflow/sfn.tf.
- State machine execution input: unchanged schema (`codeVersion`, `hadoop`, `spark`, `cluster.managedScalingMaxUnits`, `spark.shufflePartitions`); same bounds (2..4, 8..512), same error text and error name for invalid values. New semantics: `cluster.managedScalingMaxUnits` equal to the Terraform minimum (2) = no managed scaling policy (fixed cluster at the core InstanceCount, 2).
- Workflow variables: `clusterComputeLimits` removed; new `hadoopClusterRequest`, `sparkClusterRequest` (complete RunJobFlow requests); `sparkShuffleConf` unchanged. No script reads these (grep of scripts/ and experiments/ for clusterComputeLimits: no hit).
- Consumers: scripts/run-bounded-pipeline.sh and the AB collectors need no change (input unchanged). Collectors that read the cluster's managed-scaling policy or the managed-scaling CloudWatch series will see none for A (expected).
- Limits: managed-scaling maxima unchanged (Terraform max 4); no new variables, outputs or resources. Thread-safety: not applicable.

## VALIDATION EVIDENCE

- `terraform fmt -recursive infra/terraform` -> exit 0 (reformatted sfn.tf alignment only)
- `terraform fmt -recursive -check infra/terraform` -> exit 0, no file listed
- `terraform -chdir=infra/terraform/environments/course validate` -> "Success! The configuration is valid.", exit 0
- `git diff --numstat -- infra/terraform/modules/workflow/sfn.tf` -> 76 insertions, 34 deletions; sha256 e22b139751184a5fbeddbbec814cf583a21111065a41c7e0aca85dde2037a07c
- `git status --short` -> M infra/terraform/modules/workflow/sfn.tf (mine); M experiments/collectors/c-embedding-backlog.sh and f-poison-replay.sh (the parallel L-02 repair, not mine)
- finished: after the last command above
files changed after these runs: none
