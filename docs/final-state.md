# ScholarChain documented final state

This document is the **documented final state** that the Phase 17 final gate refers to ("Terraform validates and the archived plan matches the deployed environment or documented final state"; Phase 17 work order, decision 3 and planned deviation 3). It traces every signed Terraform plan to what it deployed, the removal plan to what it removed, the items kept on purpose, the specification's cleanup categories one by one, and the Terraform freeze since the removal plan. The section after Phase 17 is filled in at Phase 17 step E11 from the human's read-only steps E6 and E8.

**There is no live system.** `scripts/cleanup.sh` removed the course environment on Nov 21, 2026 (Phase 15, step D27): 175 Terraform resources, plus the ECR images, the data-lake object versions and the EMR-created interface endpoint that the script removes before its Terraform step. The removal followed the instructor's teardown approval of Nov 20 (Phase 15 gate, decision 6, [`docs/agent-runs/phase-15/human-gate.md`](agent-runs/phase-15/human-gate.md)).

**Identifiers.** Commit SHAs, plan checksums and run IDs below are copied from committed records. The AWS account appears in those records only as a fingerprint (`account_sha256`) and a masked form; neither is repeated here. Region: us-east-1 (`infra/terraform/environments/course/course.tfvars:6`).

Contents:

- [1. Timeline](#1-timeline)
- [2. Signed plans](#2-signed-plans)
- [3. Release records and what each deployed](#3-release-records-and-what-each-deployed)
- [4. The removal plan and its records](#4-the-removal-plan-and-its-records)
- [5. Retained items before Phase 17](#5-retained-items-before-phase-17)
- [6. Cleanup categories](#6-cleanup-categories)
- [7. Terraform freeze since the removal plan](#7-terraform-freeze-since-the-removal-plan)
- [8. Evidence kept for grading](#8-evidence-kept-for-grading)
- [9. After Phase 17](#9-after-phase-17)

## 1. Timeline

| Date (2026) | Event | Record |
|---|---|---|
| Nov 17 | Phase 12 plan signed (174 creates); superseded before any apply | [`docs/agent-runs/phase-12/human-gate.md`](agent-runs/phase-12/human-gate.md), "Plan signature" |
| Nov 17 | Phase 13 plan signed (175 creates); its first apply stops at 153 of 175 creates (`KMSKeyNotAccessibleFault`) | [`docs/agent-runs/phase-13/human-gate.md`](agent-runs/phase-13/human-gate.md), "Plan signature"; [`docs/agent-runs/phase-14/approvals.md`](agent-runs/phase-14/approvals.md), line 5 |
| Nov 17 to 19 | Phase 14: runtime-off plans attempts 2 to 5, runtime-on plan (D10) | [`docs/agent-runs/phase-14/approvals.md`](agent-runs/phase-14/approvals.md) |
| Nov 20 to 21 | Phase 15: runtime-on plan, the L-01 plan, the runtime-off and cleanup-mode plan | [`docs/agent-runs/phase-15/approvals.md`](agent-runs/phase-15/approvals.md) |
| Nov 21 | Archive, removal plan, removal (D25 to D28) | [`docs/agent-runs/phase-15/runbook-log.md`](agent-runs/phase-15/runbook-log.md), rows D25 to D28 |
| Nov 23 onward | Phase 17 session 3: read-only state count and AWS leftover removal (steps E6, E8) | section 9 (PENDING E6/E8) |

## 2. Signed plans

Every apply used a plan whose sha256 the human signed in a committed line (`APPROVED-PLAN-SHA256:` in a `human-gate.md` or `approvals.md`; the Phase 12 signature is a signed paragraph without that line, and its plan was never applied). `scripts/deploy.sh` and `cleanup.sh --remove` accept only such a plan ([`scripts/README.md`](../scripts/README.md), "The two identity rules").

| # | Phase | Plan (purpose; change counts as recorded) | Plan sha256 | Signed in | Applied? | Result |
|---|---|---|---|---|---|---|
| P12 | 12 | first full environment, made at `80a3606c`: 174 create | `5f249df56c344b47dd17e4e88974c0175f07a2ad912f1020ee1e5dfaa68d26af` | `docs/agent-runs/phase-12/human-gate.md:60` (signed paragraph, no `APPROVED-PLAN-SHA256:` line) | no | superseded by the Phase 13 plan |
| P13 | 13 | full environment, plan commit `bc179ed`: 175 create | `aee655c7c8caa1b0e7c3912a07175f73bd12184cf198dca24983e96b70f9437d` | `docs/agent-runs/phase-13/human-gate.md:77` | yes (Phase 14, attempt 1) | stopped after 153 of 175 creates (`KMSKeyNotAccessibleFault`, first use of `aws/rds`); no release record |
| P14-2 | 14 | runtime off, attempt 2, made at `d4ddea8`: 22 create | `6d4a629c5a6af7167c4581cbe5fdc417b054838b35d5c3653579f7aaf6a70df5` | `docs/agent-runs/phase-14/approvals.md:9` | yes | stopped after 21 of 22 creates (`PutDashboard` refused the body, G-02); no release record |
| P14-3 | 14 | runtime off, attempt 3, made at `1f9a20e`: 1 create (the dashboard) | `a865a94dce63f9cc6e683e62105814475a2849f0fa065234320c44d94eab67d1` | `docs/agent-runs/phase-14/approvals.md:17` | yes | release record R1 |
| P14-4 | 14 | runtime off, attempt 4 (G-14), made at `b15dc78`: 3 in-place updates (state machine role policy, role boundary, deployment role policy) | `5c77365f770a5cf85507194a07915d7b0b7f223d6b31ca7aeb434fdaea19cc12` | `docs/agent-runs/phase-14/approvals.md:29` | yes | release record R2 |
| P14-5 | 14 | runtime off, attempt 5 (G-21), made at `20e79f3`: 1 in-place update (state machine definition) | `619576314f4bcf324397381de79c70d6fde794873770ed526a676f870fdf8975` | `docs/agent-runs/phase-14/approvals.md:37` | yes | release record R3 |
| P14-ON | 14 | runtime on (D10), made at `ba345f8`: 10 create, 8 in-place update, 5 replace (task definitions only), 0 delete | `5656145ec55ef6c08230ab4aedae778cf19fa1f99aaa11225040a2c52c7f4199` | `docs/agent-runs/phase-14/approvals.md:45` | yes | release record R4 |
| P15-ON | 15 | runtime on (D3), made at `85dacb44`: 1 create, 9 in-place updates, 5 replace (task definitions), 0 delete | `934d78c312a363ac02ee330db02372a20ab9226416f05e923041178b2d814480` | `docs/agent-runs/phase-15/approvals.md:9` | yes | release record R5 |
| P15-L01 | 15 | runtime on after repair L-01, made at `e94df52`: 1 in-place update (state machine definition) | `235b56f540bdc237ae511230736bbe184e420123609098ca809bac27b067de6d` | `docs/agent-runs/phase-15/approvals.md:65` | yes | release record R6 |
| P15-OFF | 15 | runtime off and cleanup mode (D19), made at `d872d120`: 8 update, 11 delete, 5 replace, all runtime resources | `71fe907167a22d8e83c806fcc02b044670e0dda8275561c73be2dfca66041fbd` | `docs/agent-runs/phase-15/approvals.md:73` | yes | release record R7 |
| P15-RM | 15 | removal plan from `cleanup.sh --list`, plan commit `4297e46c`: 175 removals | `d636c6482cb229475ccd0087970a6c3aa3f2bb65f7bfaed47becd0e9ec9e619e` | `docs/agent-runs/phase-15/approvals.md:76` | yes (D27 attempt 2) | the environment removed; section 4 |

## 3. Release records and what each deployed

`scripts/deploy.sh` writes one record under `releases/course/` after each verified apply; nobody edits them by hand (the guard blocks every hand write under `releases/**`, [`docs/agents.md`](agents.md)). Each record's plan sha256 equals a signed line in section 2. Values below are copied from the records (fields `applied_utc`, `plan_sha256`, `plan_commit`, `build_commit`, `runtime_enabled`, and the services' `image_tag`).

| # | Record | Applied (UTC) | Plan | Plan commit | Build commit | Runtime | Image tag of the services |
|---|---|---|---|---|---|---|---|
| R1 | [`releases/course/20261118T184244Z-39af6e4d0c51-9e3a.json`](../releases/course/20261118T184244Z-39af6e4d0c51-9e3a.json) | 2026-11-18T18:42:44Z | P14-3 (`a865a94d…`) | `1f9a20e8` | `e78bb100` | off | `placeholder-runtime-disabled` |
| R2 | [`releases/course/20261118T212004Z-b925bc4ac306-8f27.json`](../releases/course/20261118T212004Z-b925bc4ac306-8f27.json) | 2026-11-18T21:20:04Z | P14-4 (`5c77365f…`) | `b15dc789` | `e78bb100` | off | `placeholder-runtime-disabled` |
| R3 | [`releases/course/20261119T054925Z-cf28e28596c1-d63f.json`](../releases/course/20261119T054925Z-cf28e28596c1-d63f.json) | 2026-11-19T05:49:25Z | P14-5 (`61957631…`) | `20e79f35` | `f159535e` | off | `placeholder-runtime-disabled` |
| R4 | [`releases/course/20261119T193246Z-167196849087-d539.json`](../releases/course/20261119T193246Z-167196849087-d539.json) | 2026-11-19T19:32:46Z | P14-ON (`5656145e…`) | `ba345f8d` | `f159535e` | on | `f159535e498ad1a4504dad1fac0a3fb6ceee14e6` |
| R5 | [`releases/course/20261120T213452Z-94ec6b01a4ca-44da.json`](../releases/course/20261120T213452Z-94ec6b01a4ca-44da.json) | 2026-11-20T21:34:52Z | P15-ON (`934d78c3…`) | `85dacb44` | `684636ef` | on | `684636ef997ee041912e2b57d1c597ded8e9778d` |
| R6 | [`releases/course/20261120T225449Z-70876eb27b26-8ce8.json`](../releases/course/20261120T225449Z-70876eb27b26-8ce8.json) | 2026-11-20T22:54:49Z | P15-L01 (`235b56f5…`) | `e94df52c` | `684636ef` | on | `684636ef997ee041912e2b57d1c597ded8e9778d` |
| R7 | [`releases/course/20261121T052512Z-ebcbc03a90bd-01a4.json`](../releases/course/20261121T052512Z-ebcbc03a90bd-01a4.json) | 2026-11-21T05:25:12Z | P15-OFF (`71fe9071…`) | `d872d120` | `684636ef` | off | `placeholder-runtime-disabled` |

What they deployed, in short: R1 completed the runtime-off environment after the two partial applies (P13, P14-2); R2 and R3 changed only IAM and the pipeline state machine for the Phase 14 EMR runs; R4 turned the four services on with the Phase 14 image (the Phase 14 smoke tests and loads ran on it); R5 deployed the Phase 15 image and changes; R6 changed only the state machine (L-01); R7 scaled the runtime to zero and turned every deletion protection off before the removal. The image digests and per-service task-definition revisions are in each record; the release manifest of Phase 17 lists them per record (section 9). deploy-engineer's read-only check at Phase 15 step D18 found the live services matching the release (digest three-way match, load balancer targets healthy; Phase 15 gate, "Results").

## 4. The removal plan and its records

- **Removal-plan record:** [`docs/agent-runs/phase-15/cleanup/cleanup-4297e46c626f22a198952f99c6ebd0e572827848-20261121T203342Z-4297e46c626f-acb9.record.json`](agent-runs/phase-15/cleanup/cleanup-4297e46c626f22a198952f99c6ebd0e572827848-20261121T203342Z-4297e46c626f-acb9.record.json): plan ID `20261121T203342Z-4297e46c626f-acb9`, plan commit and commit `4297e46c626f22a198952f99c6ebd0e572827848`, build commit `684636ef997ee041912e2b57d1c597ded8e9778d`, `tree_clean` true, plan sha256 `d636c648…619e` (= P15-RM), the evidence archive sha256 `a7115c16…1e87`, `evidence_archive_reports_entries` 0, created `2026-11-21T20:33:57Z`, the account as fingerprint and masked form only.
- **The plan (D25 step 5):** "Plan: 0 to add, 0 to change, 175 to destroy" (only removals; the script refuses any other change), listed by the specification's categories ([`docs/agent-runs/phase-15/runbook-log.md`](agent-runs/phase-15/runbook-log.md), row "D25 step 5 cleanup.sh --list"). The signing paragraph adds 9 ECR images, the data-lake object versions and the EMR-created interface endpoint, which the script removes before its Terraform step (`docs/agent-runs/phase-15/approvals.md:75`).
- **D27 attempt 1:** refused before the confirmation prompt (L-11), nothing removed (runbook log, row "D27 attempt 1").
- **D27 attempt 2:** the human typed the confirmation; no EMR cluster to end; interface endpoints removed; 9 ECR images removed; the data-lake bucket emptied (6,620,629 object versions and delete markers); the Terraform step reported "Resources: 0 added, 0 changed, 175 destroyed". Exit 6 came from the leftover check counting tagging-API entries of resources already removed (L-15, fixed in Phase 16). `--remove` was not run again (runbook log, row "D27 attempt 2").
- **D28:** the human's per-type breakdown of the tagging-API entries and the orchestrator's read-only cross-check found every entry to be a record of a removed resource (NAT gateway in state `deleted`, EMR clusters terminated), none billing; then the on-camera check: running EMR clusters 0, NAT gateway `deleted`, live VPC endpoints 0, load balancers 0, Neptune clusters 0, ECS clusters 0, DynamoDB tables 0, S3 buckets 0, vector buckets 0, ACTIVE task definitions 20 (kept on purpose) (runbook log, rows "D28 leftover diagnosis" and "D28 leftover check on camera"; Phase 15 gate, decision 4).

## 5. Retained items before Phase 17

As recorded at the Phase 15 gate (decision 11) and in the removal plan's "never touched" list (runbook log, row "D25 step 5"):

| Item | Why it was kept | Cost |
|---|---|---|
| 20 ACTIVE ECS task-definition revisions | Terraform removes a task definition from its state only; earlier revisions stay registered (expected leftover I-15); the Phase 17 plan is to deregister them at step E8 (outcome: section 9, PENDING E8) | none (Phase 15 gate, decision 11) |
| The hand-made read-only IAM user behind the aws-api MCP profile | created by the human outside Terraform (recorded as a manual action in [`docs/agents.md`](agents.md), "MCP servers and trust boundaries"), so neither Terraform nor `cleanup.sh` touches it; it was the profile of the read-only checks after D27; kept into Phase 17, whose plan is to remove it last, at step E8 (outcome: section 9, PENDING E8) | none recorded |
| The $25 project budget with its alerts | made by the human outside Terraform (IL-20 in [`docs/service-limits.md`](service-limits.md#1-infrastructure-limits-and-region-availability)), so neither Terraform nor `cleanup.sh` touches it; kept into Phase 17, whose plan is to remove it at step E8 (outcome: section 9, PENDING E8) | none recorded |
| The local Terraform state | kept for this documented final state and the Phase 17 state count (step E6); gitignored, never committed, never read by agents ([`infra/terraform/STATE.md`](../infra/terraform/STATE.md)) | none (local file) |
| The local evidence archive `a7115c16…` and its copies | grading evidence; holds identifiers, so local only, sha256 committed (`docs/agent-runs/phase-15/approvals.md:78`) | none (local file) |

## 6. Cleanup categories

The specification's cleanup categories ("Cleanup requirements"), each with what the removal plan held, what the checks after removal found, and what is retained. Sources: runbook log rows "D25 step 5", "D27 attempt 2", "D28 leftover diagnosis" and "D28 leftover check on camera" ([`docs/agent-runs/phase-15/runbook-log.md`](agent-runs/phase-15/runbook-log.md)).

| Category | In the removal plan or removed by the script | After removal (D27, D28) | Retained |
|---|---|---|---|
| Ephemeral EMR resources | no cluster running at D25; the EMR-created interface endpoint (removed first by the script); EMR roles, instance profile and security groups in the plan | 0 running EMR clusters; tagging-API entries for 8 terminated clusters are records, not resources | nothing |
| ECS services and tasks | the cluster, 4 services, 4 scaling targets, 4 alarms, 5 log groups, 5 task definitions (state only) | 0 ECS clusters; 20 ACTIVE task-definition revisions | the 20 revisions (section 5) |
| Load balancers and networking | API Gateway API, stage, route, integration and VPC link; the load balancer, listener, 3 listener rules and 3 target groups; the VPC, 6 subnets, 2 route tables, 6 associations, 2 routes, the internet gateway, the NAT gateway and its address, 2 VPC endpoints, 11 security groups and 36 rules | NAT gateway `deleted`; 0 live VPC endpoints; 0 load balancers; no security group besides the default; 0 non-default VPCs and subnets, Elastic IPs and network interfaces | nothing |
| Neptune resources | the cluster, the instance, the subnet group, 2 parameter groups; no final snapshot (`neptune_skip_final_snapshot = true`, `infra/terraform/environments/course/course.tfvars:18`) | 0 Neptune clusters; 0 manual snapshots | nothing |
| DynamoDB tables | 6 tables (deletion protection off since D20) | 0 tables | read-only samples committed under [`docs/agent-runs/phase-15/dynamodb-sample/`](agent-runs/phase-15/dynamodb-sample/) |
| S3 Vectors indexes and vector buckets | the vector bucket, its policy, 5 indexes | 0 vector buckets | nothing (the vectors are reproducible from the graph export with the ingest loader) |
| Optional OpenSearch resources | **not built** (the optional tier was not implemented; [`docs/architecture.md`](architecture.md#6-what-was-not-built)) | none to remove | nothing |
| ECR images | the repository and its lifecycle policy; 9 images (removed first by the script) | 0 ECR repositories | nothing (images are rebuilt from the build commit by `package.sh`) |
| S3 buckets and retained evidence | the data bucket with 6 bucket settings and 1 object; 6,620,629 object versions and delete markers emptied first; the `reports/` prefix held 0 objects (D25 step 3) | 0 S3 buckets | evidence kept outside AWS (section 8): the S3-only reports were copied first ([`docs/agent-runs/phase-15/s3-copies/`](agent-runs/phase-15/s3-copies/)) |
| Terraform state | never touched except through the apply of the removal plan | the local state remains on the operator's laptop | the local state (section 5) |
| KMS and Secrets Manager retention | 4 Secrets Manager containers with a recovery window of 0 days (`infra/terraform/environments/course/course.tfvars:10`) | secrets live or scheduled for deletion: 0 and 0; tagged KMS keys: none | nothing |

Also in the removal plan: the Step Functions state machine, 4 SQS queues with 2 queue policies and 2 redrive policies, the SNS topic and its policy, the dashboard, 11 IAM roles with 11 inline policies, 1 managed policy and 1 attachment. The read-only check after D27 found 0 SQS queues, state machines, SNS topics, log groups, dashboards, and customer IAM roles and policies. The read-only profile could not list S3 Vectors, Secrets Manager, KMS, alarms, scaling targets or the tagging API at that point; those were confirmed with the human's profile at D28 (runbook log, rows "D27 attempt 2" and "D28 leftover diagnosis").

## 7. Terraform freeze since the removal plan

- The removal plan was made at commit `4297e46c` (section 4). No commit after it changes anything under `infra/terraform/` or the other plan input, the vector index spec (`git log --oneline 4297e46c..HEAD -- infra/terraform modules/storage/src/main/resources/s3vectors/index-spec-v1.json` printed nothing at commit `50ba087`, read in Phase 17 step W2). The last commit that changed `infra/terraform/` is `e94df52` (Nov 20, the L-01 repair), before the removal plan.
- Phase 17 changes no file under `infra/terraform/**` (Phase 17 work order, decision 2, "Terraform is frozen").
- So the submitted Terraform is the configuration that the last signed plans (P15-OFF, P15-RM) were made from. The final gate's "Terraform validates" is checked by `check.sh`'s Terraform gate (`init -backend=false` and `validate`) in a fresh clone (Phase 17 step E4). No new plan is applied.

## 8. Evidence kept for grading

- **In the repository:** every phase record under `docs/agent-runs/`, the seven release records under [`releases/course/`](../releases/course/), the removal-plan record (section 4), the experiment raw folders under [`experiments/raw/`](../experiments/raw/), the S3-only report copies and DynamoDB samples (section 6), the evaluation and emulator raw folders, and the archive's sha256 (`docs/agent-runs/phase-15/approvals.md:78`).
- **Local only, never committed:** the Phase 15 evidence archive (it holds identifiers in byte copies of earlier records), the Phase 16 full clean-checkout logs, and the plan files and apply output under the gitignored `plans/` folder (Phase 15 gate, decision 7; [`docs/evaluation.md`](evaluation.md#107-group-7-reproducibility)).
- The instructor was told on Nov 21 where the grading evidence lives (the repository and the local archive with its sha256; Phase 15 gate, decision 6).

## 9. After Phase 17

PENDING E6/E8. This section is filled in at Phase 17 step E11 from the human's read-only step E6 and the leftover removal of step E8 (Phase 17 work order, decision 18). Until then, nothing below is a result.

| Item | Result |
|---|---|
| Terraform state resource count (`terraform -chdir=infra/terraform/environments/course state list`, counted with `wc -l`; evidence only with `exit=0`) | PENDING E6 |
| Read-only `bash scripts/plan.sh` against the emptied account, compared with the signed plans and the removal records | PENDING E6 (optional; run only if the human approves it) |
| Read-only `bash scripts/cleanup.sh --evidence-archive PATH --list` (the "after" inventory) | not planned for E6: the Phase 17 step I1b analysis found that against today's emptied account it stops with exit 3 at `terraform-outputs`, before any AWS call or listing, because the local state has no outputs, so it gives no inventory ("safe and useful for E6: no"; `agent-report-infra-I1b.md`, copied by the human to `docs/agent-runs/phase-17/`; same note in [`docs/runbook.md`](runbook.md#10-cleanup)) |
| Account fingerprint check before any change | PENDING E8 |
| The 20 task-definition revisions: approved list, inventory before, deregistered, inventory after | PENDING E8 |
| The $25 budget: removed or kept, and why | PENDING E8 |
| The read-only IAM user: removed or kept, and why | PENDING E8 |
| The local Terraform state after Phase 17 | PENDING E8 |
| Release manifest of the release candidate (`releases/manifest/RC_SHA/`) | PENDING E10 |

The commands for E6 are listed with their replay lines in [`docs/runbook.md`](runbook.md) (sections 2 and 10). The state-count command reads only resource addresses and is run by the human; agents never read the state.
