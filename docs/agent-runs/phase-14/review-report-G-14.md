# Review report G-14 (Phase 14 session 3, security review, class 4 Terraform IAM)

(Saved verbatim by the orchestrator from code-reviewer's final message.)

I found no critical or major findings. The repair should clear the AddTags AccessDenied from D6 attempt 2. I found nothing else on the caller side that would stop the next EMR call. Two minor findings, neither blocking.

VERDICT: APPROVE

## Findings

| ID | Severity | file:line | Failure input | Impact | Owner | Interface |
|---|---|---|---|---|---|---|
| G-15 | minor | infra/terraform/modules/identity/boundary.tf:195-205 | Some future identity policy on any bounded role grants `elasticmapreduce:AddTags` on `cluster/*`, then calls AddTags with `project=scholarchain` plus any other key/value pair (for example it overwrites `owner` or `expires`). | The ceiling has no `aws:TagKeys` limit, so it allows any extra tag keys as long as `project=scholarchain` is in the request. Nothing can exploit this today: only the sfn role grants AddTags (grep shows no other `elasticmapreduce` grant in any module), and that role is narrowed by its own TagKeys condition. Optional hardening: copy the role's `ForAllValues:StringEquals aws:TagKeys` condition into the boundary, or accept the risk. It does not block D6. | infra-engineer | no |
| G-16 | minor | owner report repair-D6-infra-iam.md, threat note | none (documentation accuracy) | The note says the role "can overwrite the values of those keys on any cluster". More precisely, `project` and `for-use-with-amazon-emr-managed-policies` must be sent with fixed values, so only `course`, `environment`, `owner`, `expires` and `managed-by` can be set to arbitrary values. Setting `for-use-with-amazon-emr-managed-policies=true` on a non-project cluster widens nothing: AmazonEMRServicePolicy_v2's tag conditions apply to EC2 and network resources, not to the cluster. The note is otherwise correct. | infra-engineer (report only) | no |

## Checklist

### 1. New sfn statement (sfn.tf:449-471)

**Resource:** `local.cluster_arns` = `arn:${var.partition}:elasticmapreduce:${var.region}:${var.account_id}:cluster/*` (sfn.tf:414). The AccessDenied was on cluster/*, so this ARN matches.

**Tags actually sent:** `Tags = local.cluster_tags` (sfn.tf:117). This is built from `cluster_tag_map` (sfn.tf:63-68), with nothing else added in the createCluster Arguments (sfn.tf:207-211, 263-267). The map merges three sources:
- **Provider default tags** (environments/course/providers.tf:7-16): `project="scholarchain"`, `course="cs441"`, `environment="course"`, `owner=var.owner`, `expires=var.expires`, `managed-by="terraform"`.
- **`local.cs.tags`:** this comes from `var.cluster_tags` (emr/main.tf:74), which is `module.network.emr_managed_policy_tag` (course/main.tf:84). Its default is `{"for-use-with-amazon-emr-managed-policies" = "true"}` (network/variables.tf:58-61). The network module call (course/main.tf:31-38) does not override it.
- **`managed-by = "step-functions"`:** this overrides the default tag's value; the key stays the same.

**Result:** seven keys, sorted: course, environment, expires, for-use-with-amazon-emr-managed-policies, managed-by, owner, project.
- `StringEquals aws:RequestTag/project = "scholarchain"`: satisfied (same lowercase value).
- `StringEquals aws:RequestTag/for-use-with-amazon-emr-managed-policies = "true"`: satisfied. The value is the string "true" from a `map(string)`.
- `ForAllValues:StringEquals aws:TagKeys` against `sort(keys(local.cluster_tag_map))`: satisfied. The condition list is the same map as the tags sent, so key names and case match by construction and cannot drift.
- The existing RunJobFlow statement (sfn.tf:422-438) uses the same two aws:RequestTag conditions, and it evidently passed in attempt 2 (the deny came at the next authorization, AddTags). That supports the claim that EMR fills aws:RequestTag from this request.

**Tags added by EMR or Step Functions:** neither adds a tag in the request; Step Functions passes Arguments through unchanged. I cannot check from the repository whether the implied AddTags check could carry a key that is not in the request (for example an `aws:`-prefixed system tag), so that stays under RISKS. Nothing documented or seen in the code suggests it.

**Missing tag key:** not an explicit deny. If `aws:RequestTag/project` is absent, StringEquals is false and the statement does not apply, so the result is an implicit deny. There is no Deny statement in the sfn policy or the boundary. With no tags at all, ForAllValues would be vacuously true, but the two StringEquals conditions still fail.

### 2. Boundary statement (boundary.tf:195-205)

**Consistency with the role policy:** it covers the role statement: same action, same ARN form (`format(local.regional_arn, "elasticmapreduce", "cluster/*")`, boundary.tf:27), and a subset of the conditions. The intersection of boundary and role policy is therefore the role statement.

**Breadth:** not broader than the existing ceilings, apart from G-15 (no TagKeys limit).

**Other roles with this boundary:** EMR service, EMR instance, Neptune loader, ECS execution, the task and ops roles, and the sfn role.
- None of their identity policies grant `elasticmapreduce:AddTags`. Grep finds `elasticmapreduce:` actions only in sfn.tf and boundary.tf.
- The only attachable managed policy is AmazonEMRServicePolicy_v2 (deployment.tf:372-375; the variable default is `[]`, course/variables.tf:44-47), and it grants no `elasticmapreduce:` actions.
- So no role gains any new permission today. The new ceiling only matters if the deployment role later writes AddTags into a bounded role's inline policy (see G-15).

### 3. What createCluster.sync, addStep.sync and terminateCluster.sync need

I checked the owner's per-action table against the files. Every line number it cites matches.

**RunJobFlow calls:**
- `elasticmapreduce:RunJobFlow`: role policy sfn.tf:424, boundary boundary.tf:180. The boundary's `aws:RequestedRegion` condition is satisfied in us-east-1.
- AddTags: added in this repair.
- `iam:PassRole`:
  - Role policy (sfn.tf:497-498): the exact ARNs of the service role and the instance role. `var.emr_instance_role_arn` is the role ARN (identity/outputs.tf:14-17), not the profile ARN, which is right for the PassRole check behind `JobFlowRole`.
  - Boundary (boundary.tf:229-230): name patterns `-emr-service-*` and `-emr-instance-*`. These match the `name_prefix` roles in identity/main.tf:46 and :101.
- Auto-termination: `PutAutoTerminationPolicy` is present in both (sfn.tf:486, boundary.tf:215).
- Managed scaling: `PutManagedScalingPolicy` is present in both (sfn.tf:489, boundary.tf:218).
- `iam:CreateServiceLinkedRole` for the EMR cleanup role: present in both (sfn.tf:503-513, boundary.tf:234-238).

**.sync polling and termination:**
- DescribeCluster: sfn.tf:481, boundary.tf:210.
- DescribeStep: sfn.tf:484, boundary.tf:213.
- TerminateJobFlows: sfn.tf:482, boundary.tf:211.
- AddJobFlowSteps: sfn.tf:483, boundary.tf:212.
- CancelSteps: sfn.tf:485, boundary.tf:214.

**Not needed:** the cluster spec has no SecurityConfiguration field (sfn.tf:96-118), so no security-configuration permissions are needed.

I found nothing more the caller needs. The EMR service role's own provisioning calls are bounded by EmrServiceCeiling (boundary.tf:245-287). They have never been exercised, because no cluster has been created yet. They are outside this diff and are the next place a first-launch failure could happen (RISKS).

### 4. Threat note, wildcards and identifiers

- Threat note: correct, apart from the precision point in G-16. DriveProjectClusters really has no tag condition (sfn.tf:478-492), so the "low impact" reasoning holds.
- Wildcards: no wildcard actions.
- IAM: no new PassRole and no IAM write.
- Identifiers: no literal account ID, ARN, bucket or secret in the diff. All ARNs come from `var.*`/`local.*`.

### 5. Apply profile

- **Deploy role is denied:** confirmed. deployment.tf:352-364 (NeverChangeRoleBoundary) denies `iam:CreatePolicyVersion`, `DeletePolicyVersion` and `SetDefaultPolicyVersion` on `aws_iam_policy.role_boundary.arn`. Updating an `aws_iam_policy` is a CreatePolicyVersion call, so the deploy role would fail on the boundary. deployment.tf:26-27 says the same.
- **Human profile is planned:** Phase 14 planned deviation 2 (prompt-s3.md:119 and :294) has the human's `default` admin profile run every script, so this needs no extra step. The owner's R1 is correct.
- **5-version limit:** it cannot block this apply.
  - The boundary shows as `create` only in the first applied plan (bc179edb…summary.txt:17).
  - The two later plan summaries show no change to it (d4ddea8d…summary.txt: IAM changes are runtime policies only; 1f9a20e8…summary.txt: "IAM and resource-policy changes: none").
  - So the policy should have one version, and this apply makes v2.
  - Even at 5 versions, the provider deletes the oldest non-default version first, which the admin profile is allowed to do.
  - Assumption: nobody changed the policy outside Terraform.

### 6. Expected plan

From the code, I expect exactly two in-place updates:
- `module.workflow.aws_iam_role_policy.sfn` (policy JSON)
- `module.identity.aws_iam_policy.role_boundary` (policy JSON, new default version)

Nothing else should change:
- `local.definition` (sfn.tf:161-373) and `aws_sfn_state_machine.pipeline` (sfn.tf:548-564) are untouched. `depends_on` does not cause a diff.
- The boundary ARN does not change, so the roles that reference it (identity/main.tf:50, :105, :139; runtime; workflow) and the deployment policy (deployment.tf:182, 200, 264, 339, 363) do not change.
- git status shows only the two files.

Any create, replace, destroy or other update in the plan is a stop.

## Files read

**In full:** diff-G-14.txt; repair-D6-infra-iam.md; infra/terraform/modules/workflow/sfn.tf; infra/terraform/modules/identity/boundary.tf; infra/terraform/modules/identity/deployment.tf; infra/terraform/modules/identity/main.tf; infra/terraform/environments/course/providers.tf; infra/terraform/environments/course/main.tf; plans/1f9a20e8…-0877.summary.txt; plans/d4ddea8d…-18bb.summary.txt.

**In part:** infra/terraform/modules/emr/main.tf:40-79; infra/terraform/modules/runtime/iam.tf:75-104; docs/agent-runs/phase-14/prompt-s3.md:234-263, plus a grep; identity/outputs.tf, network/variables.tf, network/outputs.tf, emr/variables.tf, environments/course/variables.tf: grep context only.

**Not read:** environments/course/versions.tf, outputs.tf and tfvars; the rest of the network, emr, runtime and workflow modules; scripts/**. No *.tfstate* or .terraform/ files were read.

**Disclosure:** one Grep (pattern `deployment_attachable_policy_arns|aws_iam_role_policy_attachment` over infra/terraform, excluding only .terraform/) happened to print a few matching lines from plans/*.plan.txt and *.apply.txt files. These were resource IDs with an `<account>` placeholder. I did not use them as evidence and do not repeat them here.

STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: docs/agent-runs/phase-14/prompt-s3.md (sha256 c0bd687e92015df11475ce159307b1c78f8955175cbcf9b86a9e0fe6540fe83e), session 3, security review of repair G-14 (decision 11 checklist; docs/agents.md escalation rule "security change: IAM")
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools)
TEST RESULTS: not applicable (Terraform IAM only; owner reports fmt, fmt check and validate passing, which I did not rerun)
VALIDATION RESULTS: every checklist item 1-6 checked against the files above with file:line; 0 critical, 0 major, 2 minor (G-15, G-16)
ASSUMPTIONS: EMR evaluates the AddTags implied by RunJobFlow with the RunJobFlow request's tags as aws:RequestTag and aws:TagKeys (the documented keys of AddTags; consistent with the RunJobFlow aws:RequestTag statement already passing); the boundary policy has not been changed outside Terraform (one version today)
RISKS: (a) if the implied AddTags check carries a tag key that is not in the request (none documented), ForAllValues would fail and D6 would fail again with AddTags AccessDenied; dropping that one condition would be the fallback. (b) IAM changes propagate within seconds after apply; start D6 after the apply finishes. (c) This is the first EMR launch: the EMR service role's provisioning calls (AmazonEMRServicePolicy_v2 under the EmrServiceCeiling, boundary.tf:245-287) have never run, so a later service-side permission failure is possible and outside this diff.
MANUAL ACTION REQUIRED: human: runtime-off plan via scripts/plan.sh; confirm exactly two in-place updates (module.workflow.aws_iam_role_policy.sfn, module.identity.aws_iam_policy.role_boundary) and nothing else; sign; apply with the `default` admin profile (planned deviation 2); commit; then D6 attempt 3
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: human commit of G-14, then runtime-off plan review and signature, apply, then D6 again. G-15 and G-16 are recorded as minor (optional hardening or accepted risk, at the human's choice)

VERDICT: APPROVE
