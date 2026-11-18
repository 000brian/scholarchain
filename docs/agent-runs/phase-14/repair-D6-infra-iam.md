# Repair G-14 (D6 attempt 2): EMR AddTags IAM defect

STATUS: COMPLETE
OWNER: infra-engineer
WORK ORDER: docs/agent-runs/phase-14/prompt-s3.md (sha256 c0bd687e92015df11475ce159307b1c78f8955175cbcf9b86a9e0fe6540fe83e), decision 12 class 4, repair G-14; HEAD 24f7d84660692a9589c913a93277030f9e60b8b6
FILES CREATED: none in the repository (this report only)
FILES MODIFIED:
- /Users/brian/Developer/repos/scholarchain/infra/terraform/modules/workflow/sfn.tf (+33 lines: statement TagProjectClustersOnCreate, lines 440-471)
- /Users/brian/Developer/repos/scholarchain/infra/terraform/modules/identity/boundary.tf (+17 lines: statement TagProjectClusters, lines 190-205)
COMMANDS RUN:
- `terraform fmt -recursive infra/terraform` -> exit 0, no files rewritten in output
- `terraform fmt -recursive -check infra/terraform` -> exit 0
- `terraform -chdir=infra/terraform/environments/course validate` -> "Success! The configuration is valid." exit 0
- read-only: grep on infra/terraform/modules/**, scripts/README.md, scripts/deploy.sh; ls of environments/course; Read of environments/course/providers.tf (exact file); git status/diff --stat
- aws-docs MCP: Step Functions connect-emr.html (IAM section), Service Authorization Reference list_emr.html, EMR Management Guide emr-fine-grained-cluster-access.html
- aws-api MCP: 0 calls
TEST RESULTS: not applicable (no sbt, no Scala source)
VALIDATION RESULTS: fmt clean, fmt check clean, validate success (see VALIDATION EVIDENCE)
ASSUMPTIONS:
- The project default tag value is "scholarchain" (environments/course/providers.tf:9), the value the existing RunJobFlow condition (sfn.tf:427-431) already requires.
- The implied AddTags authorization on RunJobFlow is evaluated with the request's tags as aws:RequestTag / aws:TagKeys (documented condition keys of AddTags; EMR guide example pairs RunJobFlow and AddTags on cluster/*).
RISKS:
- R1 (blocking for the apply path): the boundary policy change is a new managed-policy version. deployment.tf:351-363 (NeverChangeRoleBoundary) denies the deploy role iam:CreatePolicyVersion / DeletePolicyVersion / SetDefaultPolicyVersion on the boundary, and deployment.tf:26-27 says boundary changes are applied with the human's own profile. An apply with the deploy profile would fail on the boundary and leave the role policy updated but still bounded -> the same AccessDenied. The apply carrying this plan must use the human's admin profile (as for the first apply, scripts/README.md:26).
- R2: aws_iam_policy keeps at most 5 versions; if the boundary already has 5, the provider deletes the oldest non-default version (DeletePolicyVersion), also only possible with the human profile.
- R3: the ForAllValues aws:TagKeys condition lists keys(local.cluster_tag_map) at plan time. If default tags or cluster_settings.tags gain a key, the policy changes with it in the same plan (same map), so no drift; a key added outside Terraform cannot be sent anyway.
- R4 (residual, unchanged by this repair): no EMR condition key distinguishes "tag on create" from a later AddTags call, so the role can also AddTags on an existing cluster of this account/Region, limited to the conditions below.
MANUAL ACTION REQUIRED:
- Human: run the next runtime-off plan (scripts/plan.sh), check the change kind below, and approve an apply made with the human's own profile (R1). No README edit made; scripts/README.md was not changed.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer review (security change), then human runtime-off plan and approval, then deploy-engineer apply (human profile), then D6 attempt 3.

## Per-item status (both policies)

Sources: [SF] https://docs.aws.amazon.com/step-functions/latest/dg/connect-emr.html "IAM policies for calling Amazon EMR"; [SAR] https://docs.aws.amazon.com/service-authorization/latest/reference/list_emr.html; [TBAC] https://docs.aws.amazon.com/emr/latest/ManagementGuide/emr-fine-grained-cluster-access.html.

| Action | Needed by | Role policy (sfn.tf) | Boundary (boundary.tf) |
|---|---|---|---|
| elasticmapreduce:RunJobFlow | createCluster [SF] | present :424 | present :180 |
| elasticmapreduce:AddTags | RunJobFlow with Tags [SAR operations table: RunJobFlow -> AddTags; TBAC "To create a cluster with a tag, you must also have permission for the AddTags action"] | ADDED :450-471 | ADDED :196-205 |
| iam:PassRole (EMR service role, instance role) | createCluster [SF]; RunJobFlow -> iam:PassRole, PassedToService elasticmapreduce/ec2/application-autoscaling [SAR] | present :497 (resources :498) | present :227-231 |
| elasticmapreduce:DescribeCluster | createCluster.sync, terminateCluster.sync [SF] | present :481 | present :210 |
| elasticmapreduce:TerminateJobFlows | createCluster, terminateCluster [SF] | present :482 | present :211 |
| elasticmapreduce:AddJobFlowSteps | addStep [SF] | present :483 | present :212 |
| elasticmapreduce:DescribeStep | addStep.sync [SF] | present :484 | present :213 |
| elasticmapreduce:CancelSteps | addStep [SF] | present :485 | present :214 |
| elasticmapreduce:SetTerminationProtection | only the setClusterTerminationProtection integration [SF]; not used by this state machine; TerminationProtected=false is a RunJobFlow field, and the RunJobFlow operation row lists no such action [SAR] | not needed | not needed |
| events:PutTargets / PutRule / DescribeRule | not needed: [SF] lists no EventBridge actions for createCluster, addStep or terminateCluster (EMR .sync is polled with DescribeCluster/DescribeStep) | not needed | not needed |
| elasticmapreduce:PutManagedScalingPolicy | not required for the caller on create: RunJobFlow operation row lists only AddTags, RunJobFlow, iam:PassRole [SAR] | present :489 (harmless, left) | present :218 |
| elasticmapreduce:Put/Get/RemoveAutoTerminationPolicy | same as above (AutoTerminationPolicy is a RunJobFlow field) [SAR] | present :486-488 (left) | present :215-217 |
| Security configuration | none in the cluster spec (no SecurityConfiguration field, sfn.tf:96-118) | not needed | not needed |
| iam:CreateServiceLinkedRole (EMR cleanup SLR) | EMR guide (existing citation) | present :505 | present :236 |
| Bootstrap, LogUri S3 access | read/written by the EMR service and instance roles, not the caller | not needed | not needed |

EMR service role / instance profile (identity/main.tf:46-60, :101-111): nothing new needed. Cluster tags are not checked on the service role's own calls by IAM for the caller; AmazonEMRServicePolicy_v2 (attached main.tf:60) needs the for-use-with-amazon-emr-managed-policies=true tag, which the cluster already carries (sfn.tf:61-68) and which EMR propagates to the EC2 resources it creates via ec2:CreateTags (boundary EmrServiceCeiling, ec2:CreateTags present). The instance role does not tag anything.

## IAM diff

1. sfn.tf, data.aws_iam_policy_document.sfn (inline policy "pipeline-emr" of the pipeline Step Functions role), new statement:
   - Sid TagProjectClustersOnCreate, Effect Allow
   - Action: elasticmapreduce:AddTags
   - Resource: arn:PARTITION:elasticmapreduce:REGION:ACCOUNT:cluster/* (local.cluster_arns)
   - Condition: StringEquals aws:RequestTag/project = scholarchain; StringEquals aws:RequestTag/for-use-with-amazon-emr-managed-policies = true; ForAllValues:StringEquals aws:TagKeys = sorted keys of local.cluster_tag_map (default tag keys + cluster_settings.tags keys + managed-by)
2. boundary.tf, data.aws_iam_policy_document.role_boundary (managed policy NAME_PREFIX-role-boundary), new statement:
   - Sid TagProjectClusters, Effect Allow
   - Action: elasticmapreduce:AddTags
   - Resource: arn:PARTITION:elasticmapreduce:REGION:ACCOUNT:cluster/*
   - Condition: StringEquals aws:RequestTag/project = scholarchain
No statement removed or widened; no other action added.

## Threat note

New capability beyond today: the Step Functions role can add tags to EMR clusters of this account and Region, only in a request that sets project=scholarchain and for-use-with-amazon-emr-managed-policies=true and uses only the project's tag keys. Beyond create-time tagging, it could re-tag an existing (non-project) cluster with those values; it can overwrite the values of those keys on any cluster. Impact is low: the role can already describe, step and terminate any cluster in the Region (DriveProjectClusters, cluster/*, no tag condition), so tags grant it nothing new; another principal whose access depends on project=scholarchain on EMR clusters could gain reach to a re-tagged cluster (none in this repository). Other bounded roles gain nothing: no role policy other than the sfn role grants AddTags, and the boundary is only a ceiling. No RemoveTags, no IAM write, no new PassRole.
Proposed later (not done; out of scope): tag-scope DriveProjectClusters with aws:ResourceTag/project; add iam:PassedToService conditions to PassEmrRoles; drop the unneeded Put*Policy actions.

## Expected change kind of the next runtime-off plan

- ~ update in-place: module.workflow aws_iam_role_policy.sfn (policy JSON)
- ~ update in-place: module.identity aws_iam_policy.role_boundary (policy JSON; new default version)
- Nothing to add, replace or destroy. aws_sfn_state_machine.pipeline: no change (definition untouched). No stateful resource (S3, DynamoDB, Neptune, KMS, secrets, ECR, log groups) touched; any create, replace, destroy or stateful change in the plan is a stop. Possible extra in-place drift unrelated to this repair (for example expires/owner tag values) must be explained before approval.

## INTERFACE HANDOFF

- Files changed: infra/terraform/modules/workflow/sfn.tf, infra/terraform/modules/identity/boundary.tf
- Public interface: no variable, output or resource address added or renamed; two policy documents gain one statement each.
- Fixtures: none. Thread-safety: not applicable. Limits: the AddTags key set follows local.cluster_tag_map; apply needs the human profile (R1).

## Plan summary and checksum

No plan run (work order: no plan). Checksum: none. Cost: no cost change (IAM only). Required environment variables: none new.

## VALIDATION EVIDENCE

```
$ terraform fmt -recursive infra/terraform
fmt exit 0
$ terraform fmt -recursive -check infra/terraform
fmt-check exit 0
$ terraform -chdir=infra/terraform/environments/course validate
Success! The configuration is valid.
validate exit 0
$ git status --short
 M infra/terraform/modules/identity/boundary.tf
 M infra/terraform/modules/workflow/sfn.tf
$ git diff --stat
 infra/terraform/modules/identity/boundary.tf | 17 ++++++++++++++
 infra/terraform/modules/workflow/sfn.tf      | 33 ++++++++++++++++++++++++++++
 2 files changed, 50 insertions(+)
```
