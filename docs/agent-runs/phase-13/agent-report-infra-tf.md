# agent-report-infra-tf (phase 13, session 2, step 6)

STATUS: COMPLETE
OWNER: infra-engineer
WORK ORDER: docs/agent-runs/phase-13/prompt.md (sha256 60c943dccc86a6dc58ec074b95bbd63f04e2adca2f7a1c9a9abad266f28d36a7, checked), decision 8 items 1-7
FILES CREATED: none
FILES MODIFIED:
- infra/terraform/modules/identity/deployment.tf (I-02, I-10, I-16)
- infra/terraform/modules/emr/main.tf, infra/terraform/modules/emr/variables.tf (I-06)
- infra/terraform/environments/course/variables.tf (I-06), infra/terraform/environments/course/main.tf (I-06, 8.3), infra/terraform/environments/course/outputs.tf (8.6)
- infra/terraform/README.md, infra/terraform/modules/vectors/main.tf (comment only) (I-12; README also documents I-02, I-16, 8.3)
- infra/terraform/modules/runtime/iam.tf (8.2)
- infra/terraform/modules/workflow/sfn.tf, infra/terraform/modules/workflow/variables.tf (8.3)
- infra/terraform/modules/network/main.tf (8.4)
- infra/terraform/modules/runtime/main.tf, infra/terraform/modules/runtime/outputs.tf (8.6)
- config/**: no change
COMMANDS RUN:
- shasum -a 256 docs/agent-runs/phase-13/prompt.md; wc -l; ls; read-only git status; grep -rn/-E (words not on the guard list) on repository files
- terraform -chdir=infra/terraform/environments/course validate: after each item (7 runs, every one "Success! The configuration is valid.") and once at the end
- terraform fmt -recursive infra/terraform (once, exit 0, no file listed); terraform fmt -recursive -check infra/terraform (exit 0); terraform version (v1.17.1)
- aws-docs MCP: 2 searches, 3 page reads (below). terraform MCP: not used (validate checks argument names). aws-api MCP: 0 calls.
TEST RESULTS: not applicable (no sbt source changed; Terraform only)
VALIDATION RESULTS: see VALIDATION EVIDENCE
ASSUMPTIONS:
- A1 (8.3, field shape): per the fallback, the state machine takes the FULL source-run path. Because JsonlExport needs both `--source-run` and `--out-root`, the Spark half gets two optional fields that come together: `exportSourceRun` (full path) and `exportRoot` (out-root). `exportRoot` keeps the name the work order gave.
- A2: exports live under the data lake's `graph/` prefix (state machine refuses anything else). So the EMR instance role (already read/write/delete/list on graph/) and the ops role (already read/list on graph/) need no new export grant; the storage and rag CONDITIONAL grants are not needed.
- A3: StartLoaderJob needs no `iam:PassRole` for the caller: the StartLoaderJob API page names only `neptune-db:StartLoaderJob`; the loader role is attached to the cluster (`iam_roles`). Not added; unverified until Phase 14.
RISKS:
- R1 (8.3): the JSONata conditions and the new states are not checked by `terraform validate` (ASL is a string to Terraform). First check: Step Functions validates the definition at apply (Phase 14); first run Phase 14. runtime: untested.
- R2 (8.3): an export needs an earlier graph run's path, so the export execution re-runs the Spark half; GraphAnalyticsJob then reuses the verified run (Pipeline.scala:286-303) only if the spark inputs, config and codeVersion are identical. Costs one more cluster start (bounded by the same managed-scaling max and idle end). spark-engineer's proposed `--source-attempt-root` would remove this (proposed later, theirs).
- R3 (8.4): whether the Neptune loader's S3 traffic passes the cluster's security group is unverified; the rule is cheap and scoped to HTTPS to the S3 prefix list.
- R4: `ExportInputCheck` uses `$type` before `$substring`; if JSONata does not short-circuit `and`, a non-string field raises a Choice error, which still fails the execution before any cluster exists.
MANUAL ACTION REQUIRED: none in this step (the human's Phase 13 plan, decision 13.5, shows these changes).
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: R-2 runs alongside (its IAM/EMR-step repairs come to infra-engineer in step 9); step 7a reads INTERFACE HANDOFF below; R-3 reviews infra/**.

## Items (one line each; each validated before the next)
- 8.1 I-02: done. identity/deployment.tf `NeverSetSecretResourcePolicies` (Deny secretsmanager:PutResourcePolicy, DeleteResourcePolicy on *); no secret policy exists in Terraform, so applies are unaffected; GetResourcePolicy (refresh) still allowed. validate: Success.
- 8.1 I-10: done. identity/deployment.tf `PassProjectRoles`: `apigateway.amazonaws.com` removed from iam:PassedToService. validate: Success.
- 8.1 I-16: done. identity/deployment.tf `NeverChangeOwnRole` adds iam:TagRole, UntagRole, UpdateRole, UpdateRoleDescription on the role's own name pattern. validate: Success.
- 8.1 I-06: done. emr/variables.tf `primary_instance_vcpus`, `core_instance_vcpus` (replace `instance_vcpus`, each >= 1); emr/main.tf precondition `primary + max * core <= vcpu_budget`; root `emr_primary_instance_vcpus`, `emr_core_instance_vcpus` (replace `emr_instance_vcpus`; defaults 4; not set in course.tfvars; no script used the old name). Defaults: 4 + 4*4 = 20 <= 32. validate: Success.
- 8.1 I-12: done. README "S3 Vectors limits": the policy always exists (root always passes the ARNs); README "Cleanup mode" step 6: `scripts/cleanup.sh --remove` by the human with the human's own profile; README "Deployment role" now names the I-16 and I-02 denies; vectors/main.tf header comment (a finding location) corrected. validate: Success.
- 8.2 ops role: done. runtime/iam.tf `GraphCsvWrite` (s3:PutObject on `<data bucket>/graph/*`), the one extra action storage listed; rag listed none; DynamoDB BatchWriteItem not needed (storage). validate: Success.
- 8.3 JsonlExport on EMR: done. workflow/sfn.tf states `ExportInputCheck`, `InvalidExportInput`, `SparkExportRequested`, `SparkJsonlExport`; `SparkGraphAnalytics.Next` -> `SparkExportRequested`; same Catch to `CleanupSparkCluster`; new variable `graph_uri_prefix` (workflow/variables.tf), passed in environments/course/main.tf. Options from JsonlExport.scala:234 (Required) and :237 (Optional). EMR instance role: checked, no change (identity/emr.tf graph/ in read, list and write prefixes). validate: Success.
- 8.4 RK1: done. network/main.tf `aws_vpc_security_group_egress_rule.neptune_to_s3_gateway` (TCP 443 to `aws_vpc_endpoint.s3.prefix_list_id`). Sources: https://docs.aws.amazon.com/neptune/latest/userguide/bulk-load-tutorial-vpc.html ("The Neptune loader requires a VPC endpoint of type Gateway for Amazon S3"); https://docs.aws.amazon.com/vpc/latest/privatelink/gateway-endpoints.html, "Security" (security groups must allow traffic to the service; example outbound rule to the prefix list). That the loader's traffic is subject to the cluster SG: unverified. The Neptune SG description still says "no egress": left unchanged on purpose (a description change replaces the group). validate: Success.
- 8.5 nothing else changed: runtime switch, task sizes, scaling, Neptune, DynamoDB, S3 Vectors resources untouched; config/** untouched.
- 8.6 root outputs: done. `ops_container` ({name, log_stream_prefix}, from a new local in runtime/main.tf used by the ops container definition; the rendered JSON is unchanged). Everything else the scripts need already exists (`data_prefixes.artifacts` for `package.sh --publish`, `ecr_repository_url`, `data_bucket_name`, `state_machine_arn`, `ops_task_definition_family`, `app_subnet_ids`, `ops_security_group_id`, `ecs_cluster_name`, `log_group_names`, `neptune_loader_role_arn`). validate: Success.
- 8.7 end: formatter once (exit 0, no file rewritten), fmt check exit 0, validate Success.

## Changed resources and reasons
- module.identity aws_iam_role_policy.deployment (policy text): I-02, I-10, I-16.
- module.runtime aws_iam_role_policy.ops_task (policy text): neptune-csv-build writes under graph/.
- module.workflow aws_sfn_state_machine.pipeline (definition): optional JsonlExport step and input check.
- module.network aws_vpc_security_group_egress_rule.neptune_to_s3_gateway (new): RK1.
- module.emr aws_s3_object.bootstrap: precondition text only (I-06); no attribute change.
- module.runtime aws_ecs_task_definition.ops: source refactor to a local; container JSON identical, so no change expected (inferred).
- module.vectors: comment only.

## IAM changes
- Deployment role inline policy: +Deny secretsmanager:PutResourcePolicy, DeleteResourcePolicy (Resource *); +Deny iam:TagRole, UntagRole, UpdateRole, UpdateRoleDescription on `role/scholarchain-course-deployment-*`; PassRole target list now ecs-tasks, rds, states. Only narrows.
- Ops task role: +s3:PutObject on `<data bucket>/graph/*`. Boundary: NOT widened; boundary.tf `DataLakeObjects` already allows s3:PutObject on `<data bucket>/*`.
- EMR instance role, Step Functions role, boundary: no change (the export step uses the existing addStep.sync permissions and graph/ grants).

## Stateful resources
None changed: data-lake bucket, DynamoDB tables, Neptune cluster and instance, S3 Vectors bucket and indexes, secrets, ECR untouched. The new egress rule is a separate rule resource; the Neptune security group itself is not modified.

## What the next plan should show (INFERRED from the code, not from a plan)
Nothing is applied, so the human's next plan is again create-only; compared with the Nov 17 plan: +1 resource (`module.network.aws_vpc_security_group_egress_rule.neptune_to_s3_gateway`); changed content inside resources already created (deployment and ops inline policies, state machine definition); 0 change, 0 destroy, 0 replace. Against a deployed environment it would be: 1 add, 3 in-place updates (deployment policy, ops policy, state machine), 0 replace.

## Cost estimate
$0 in this step. The changes add no billed resource (a security-group rule and policy text are free). An export execution adds one Spark cluster start, bounded by the existing EMR limits (vCPU budget 32, max 4 core nodes, idle end 900 s).

## Required environment variables by name
None new.

## Proposed later
- A root output `pipeline_graph_uri_prefix` is not added: scripts build it from `data_bucket_name` and `data_prefixes.graph`.
- Neptune SG description update (needs a replacement; only worth it together with another Neptune SG change).

## INTERFACE HANDOFF (for step 7a/7b)
- Files changed: listed above. No script or config file.
- State-machine input, Spark half, two NEW optional fields (sfn.tf header, the authoritative copy):
  - `spark.exportSourceRun`: string, the FULL graph-run path `s3://{data-bucket}/graph/.../run=<32 hex>` of an earlier execution (the graph job prints `[scholarchain] run <runId> at <runPath>`).
  - `spark.exportRoot`: string, a NEW per-attempt out-root under `s3://{data-bucket}/graph/` (for example `s3://{data-bucket}/graph/exports/ATTEMPT_ID`); JsonlExport writes `<exportRoot>/run=<hex>/` and refuses an existing one.
  - Both or neither; both must start with `s3://<data bucket>/graph/` and be longer than it; otherwise the execution fails with error `InvalidExportInput` before any cluster. All other spark fields stay required, and with the same values as the run being exported (so GraphAnalyticsJob reuses it).
  - Step run: `spark-submit --deploy-mode client --class org.scholarchain.spark.JsonlExport <spark.jar> --source-run <exportSourceRun> --out-root <exportRoot> --code-version <codeVersion>`; EMR step name `scholarchain-spark-jsonl-export`.
- New root output: `ops_container` = object `{ name = "ops", log_stream_prefix = "ops" }` (jq: `.ops_container.value.name`, `.ops_container.value.log_stream_prefix`); the log stream is `PREFIX/NAME/TASK_ID` in the group `.log_group_names.value.ops`.
- Renamed root variables (not set by any script today): `emr_instance_vcpus` -> `emr_primary_instance_vcpus`, `emr_core_instance_vcpus`.
- Thread-safety and limits: not applicable (Terraform); existing EMR bounds unchanged.

VALIDATION EVIDENCE
- fmt: terraform fmt -recursive -check infra/terraform -> 0, no files listed
- validate: terraform -chdir=infra/terraform/environments/course validate -> "Success! The configuration is valid."
- shellcheck: not applicable
- jq: not applicable (no JSON file changed)
- finished: after the last command above
- files changed after these runs: no
