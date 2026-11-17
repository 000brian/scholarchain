# Phase 12 human gate: AWS infrastructure in Terraform

## Gate

**ACCEPT.** The whole AWS environment is written in Terraform, with the six scripts. Nothing was applied. My plan run shows 174 resources to create and nothing to change, delete or replace. I signed it below. The reviewer approved after one repair round fixed all three major findings. `terraform fmt`, `terraform validate` and `shellcheck` all pass. Some things only show up on AWS; they are checked in Phases 13–15 (see "Remaining risks").

## Spec checklist

| Item | Status | Source |
|---|---|---|
| 1 Foundation: pinned versions, state design, tags, budget | Done. Terraform 1.17.1 and AWS provider 6.59.0 pinned; local state, with the remote design in `STATE.md`; tags on everything. The budget was made by hand and is not managed by Terraform | agent-report-infra-1, -2a |
| 2 Network: 2 AZs, private subnets, controlled egress, security groups by role | Done. One NAT gateway (cost; single-AZ risk recorded), free S3 and DynamoDB endpoints, no open inbound rule | agent-report-infra-2a, review-report-a |
| 3 Identity and encryption | Done. Separate roles for EMR, ECS, Step Functions, the Neptune loader and deployment, with a permissions boundary; AWS-managed encryption; 4 empty secret containers | review-report-02 |
| 4 Data lake | Done. One bucket with 7 prefixes, versioning, encryption, public access blocked, lifecycle rules, no forced delete. No Glue catalog (not used) | agent-report-infra-2a, review-report-a |
| 5 EMR | Done. No cluster sits in Terraform; the pipeline starts it. Hadoop on `emr-7.14.0`, Spark on `emr-spark-8.1.0`; logs to S3 `logs/emr/`; a bootstrap script that only records versions. On-Demand only (no Spot: failure handling untested), 2 to 4 core nodes by managed scaling, ends after 15 idle minutes | agent-report-infra-2a, -2b |
| 6 DynamoDB | Done. The six tables exactly as in the code, on-demand; point-in-time recovery off (data can be rebuilt) | review-report-a |
| 7 Neptune | Done. One db.t4g.medium (free trial), private, encrypted, IAM login, 1-day backups, subnet and parameter groups. A loader role, and read-only Neptune access for the runtime roles (the IAM part Phase 7 sent here). Deletion protection is off by a variable, because the course environment is temporary | agent-report-infra-2a, -2b |
| 8 S3 Vectors | Done. One bucket and the 5 indexes from the index spec; only the query, worker and ops roles can use it | review-report-b |
| 9 OpenSearch (optional) | Not built: nothing uses it | agent-report-infra-2b |
| 10 Runtime compute | Done. ECR with immutable tags (git SHA) and scan on push; an ECS cluster on Fargate ARM64; 4 services (query, evidence, graph, worker) plus an `ops` task; API Gateway with HTTPS in front of an internal load balancer. The runtime starts switched off. Consolidation: the spec's vector retrieval, ranking, synthesis and verification run inside query (the Phase 10 service design); each of the 4 services scales on its own. No service discovery: the services never call each other, only the shared stores and queue. One deviation (decision 1) | agent-report-infra-2b |
| 11 Workflow and messaging | Done. Job and embedding queues, each with a dead-letter queue; one state machine that runs the EMR pipeline and always ends the cluster. No EventBridge rules (the EMR steps poll) | agent-report-infra-2b, review-report-b |
| 12 Observability | Partly done. Log groups, a dashboard, alarms, an alarm topic. AWS's free default metrics cover EMR, ECS, SQS, Neptune, DynamoDB, API latency and errors. The app sends no custom metrics for LLM usage or S3 Vectors calls yet (Phase 16) | agent-report-infra-2b |
| 13 Cost and safety | Done. Limits everywhere, a cleanup mode, `COSTS.md`. The committed variable file `course.tfvars` has no secrets; my personal value sits in an untracked file. Deletion protection is a variable, off for this temporary environment | agent-report-infra-2b |
| The six scripts | Written and shellcheck-clean. Each script requirement is met in the code (strict mode, no prompts except cleanup's typed confirmation, dirty tree refused, git SHA tags, `STEP:` markers, revision check, loud failure, full plan output, cleanup lists everything first). None has run yet; Phase 13 finishes them | agent-report-infra-2c |
| fmt, validate, shell checks | Pass. The security scan is shellcheck plus the reviewer's checklist; I ran no Terraform scanner (optional) | orchestrator-summary |
| Plan summary, counts, costs, IAM changes, human actions | Done: my plan run and this gate | this gate |
| **Gate:** no unexplained delete or replace of stateful resources | Met: the plan only creates | my plan run |
| **Gate:** IAM limited to real resources and actions | Met. Small risks remain on the deployment role, which I don't use (decision 4) | review-report-b, -02 |
| **Gate:** buckets and databases not public | Met | review-report-a, -b |
| **Gate:** default capacity limited | Met | review-report-a, -b |
| **Gate:** no secrets in code or plan | Met: only empty secret containers | review-report-a, -b, my plan run |
| **Gate:** I review and sign the plan before any apply | Met (below) | this gate |
| **Gate:** code reviewer approves | Met: APPROVE | review-report-02 |

**Items earlier gates sent to Phase 12:**

| From | Item | Status |
|---|---|---|
| Phase 1, decision 5 | Pin the MCP versions | Done in amendment-02. Step F added `mcp<2`, which is a version bound, not a pin: accepted |
| Amendment-02 | Fix the MCP startup and redo probe 9 | Done in step F. Probe 9 again: terraform and aws-docs connect; aws-api does not (`CONNECTION_CLOSED`). Phase 13 |
| Amendment-02 | Remove the `bash -n` wording in `docs/agents.md:143` and the dataset-engineer and infra-engineer files | Done in step F |
| Phase 2 | ARM or Intel on Fargate | ARM64, matching my image |
| Phase 7 | AWS resources: vector indexes, tables, Neptune loader role, SQS queue, read-only Neptune access | Done (areas 6, 7, 8, 11) |
| Phase 10 | `/metrics` | Replaced by CloudWatch; custom metrics in Phase 16 |
| Phase 10 | S-5 shutdown time vs the ECS stop timeout | Done: ECS waits 120 s; the service needs at most about 106 s |
| Phase 10 | Step Functions or SQS only | Both: Step Functions runs the EMR pipeline, SQS carries the query jobs |
| Phase 11 | Prompt rules: no `sleep` waits; a fallback for agents without Grep | In the Phase 12 prompt; no approval prompts this phase |
| Phase 11 | A-04 (advisory) | Decision 8 |

## Results

- **Plan** (`bash scripts/plan.sh` on commit `80a3606`, clean tree, my admin profile, us-east-1): 174 to create, 0 to change, 0 to delete, 0 to replace.
- **What it creates:** 4 ECS services and 5 task definitions; 1 state machine; 1 NAT gateway; 1 internal load balancer; 1 HTTP API; 6 tables; 1 Neptune cluster and instance; 1 vector bucket with 5 indexes; 1 data bucket; 4 empty secrets; 11 IAM roles; 4 alarms; 1 dashboard. No secret values. The scaling policies and 3 health alarms come later with the runtime switch.
- **IAM:** 10 of the 11 roles carry the permissions boundary (a ceiling on what they can ever do). The deployment role doesn't, by design: it creates the others, may not change itself, and may only create roles that carry the boundary. I checked that every role's permissions fit inside the boundary. Policy sizes are estimates (boundary about 5,000 of 6,144 characters; deployment about 8,000 of 10,240); AWS checks them at apply.
- **Cost** (estimates; no price page was reachable): idle about $1.80/day during the Neptune trial, about $3.90/day after it. Idle costs come from the NAT gateway, the load balancer, Neptune after its trial, the secrets and alarms. EMR is about $1.50 per pipeline run. This phase cost $0.
- **Checked by me:** both EMR versions exist in us-east-1 (`emr-7.14.0`, `emr-spark-8.1.0`); provider 6.59.0 has every resource type used; Neptune is enabled; Neptune engine 1.4.7.1: exists.

**Plan signature:**
> I approve this plan to be applied in Phase 14 with my own admin profile.
> Plan file `plans/80a3606ce513a6c782b87cffbc2c9d361bc0fc3f.tfplan`, sha256 `5f249df56c344b47dd17e4e88974c0175f07a2ad912f1020ee1e5dfaa68d26af`, commit `80a3606ce513a6c782b87cffbc2c9d361bc0fc3f`: 174 create, 0 change, 0 delete, 0 replace.
> If the code, the lock file or the variables change before then, I sign a new plan.
> Signed: Brian, Nov 17, 2026

## What I reviewed

The prompt, the session 1 factory report, review and diff, the build report, the four infra reports, the three review reports, the repair report, the orchestrator summary, my plan output, `boundary.tf`, `runtime/iam.tf`, the Phase 11 gate and summaries (for items sent to Phase 12), and the spec's Phase 12 section. 

## What happened

- **Session 1:** factory-engineer fixed `.mcp.json`, removed `bash -n` from `docs/agents.md` and the dataset-engineer and infra-engineer files, and added two allow rules (`terraform validate` with `-chdir`, `terraform fmt`). The reviewer approved with three minor findings. I ratified and committed it (`4ad139e`), then restarted Claude Code and ran `/mcp` (probe 9): terraform and aws-docs connect, aws-api does not.
- **Session 2** ran on its own from about 2:13 to 5:16 AM, with one planned stop where I ran `terraform init`. At that stop I also checked the provider's resource list myself, because the terraform MCP server couldn't search the provider docs.
- **The reviews found three major problems:**
  - I-01: the deployment role could make itself an admin.
  - I-07: `rollback.sh` could never work (old task versions were removed).
  - I-13: cleanup's evidence check could pass a file that was in no archive.

  All three were fixed and approved.
- **No approval prompts.** Some agents used read-only commands that weren't on the list (`cat`, `sed -n`, `find`, `date`, one `cd`). They wrote nothing, but the `cd` breaks a standing rule.
- **The aws-api MCP server never connected,** so availability zones, quotas and prices stayed unchecked. I checked the EMR versions and Neptune myself afterwards.
- **EMR was blocked:** EMR calls failed with `SubscriptionRequiredException` because AWS's free plan doesn't include EMR. I upgraded the account to a paid plan on Nov 17, and EMR works since. Charges are now real; the budget alerts only warn.

## Decisions

1. **`/healthz` and `/readyz` go to the query service** (the work order said other paths get a 404): accepted. The smoke test needs them; they show only ok or fail per check.
2. **I-03, no rule stopping the deployment role from deleting non-project resources:** accepted. This account holds only this project, and I don't use that role (decision 4).
3. **I-05, EMR may delete in `curated/` and `graph/`:** accepted. The jobs need it to replace half-finished output; old versions stay recoverable for 7 days.
4. **I apply and clean up with my own admin profile, never the deployment role.** The deployment role stays in the code as the design for a long-lived setup. So its findings (I-02, I-04, I-10, I-11, I-14, I-16, I-17) and the reviewer's unchecked points about it (RK-A, RK-B, and whether its policy is enough for a full apply) are accepted risks for this course. For I-17, I look at every trust-policy change in future plans.
5. **Own encryption key:** not built (it costs every month). EMR disks and the alarm topic stay unencrypted at rest; the topic only carries alarm messages.
6. **Not built, for cost or because not needed:** VPC flow logs, private endpoints for ECR and other services, a monitoring agent on EMR, a shared script helper file, tag rules on the state machine's EMR permissions.
7. **`expires = "2026-12-31"`:** confirmed. My `owner` value is in my untracked `local.auto.tfvars`.
8. **Phase 11 A-04** (the dataset script reads the OpenAlex key from a fixed variable name): doesn't touch Phase 12; that script runs only on my laptop. Stays for the Phase 17 review.
9. **Cost:** this phase $0 on all three providers. Total so far: AWS $0 (the bill shows $0.00 on Nov 17, including the Nov 16 test load balancer), Anthropic about $0.12, Voyage $0; together about $0.12.

**Minor findings:**

| Finding | Decision |
|---|---|
| I-02, I-10, I-16 deployment role: secret access policy, extra pass-role target, can still edit some of its own settings | Accepted (decision 4); fix in Phase 13 if quick (one line each) |
| I-04, I-11, I-14, I-17 other deployment-role gaps | Accepted (decision 4) |
| I-06 EMR CPU check uses one size for both node types | Phase 13 |
| I-08 cleanup's help text must say "your own admin profile" | Phase 13 |
| I-09 deploy doesn't wait for tasks to stop when the runtime is turned off | Phase 13 |
| I-12 README out of date | Phase 13 |
| I-15 cleanup always ends with an error because old task versions stay | Phase 13 |
| F-01 `infra-engineer.md` still says plain `terraform validate` | Phase 13 (factory-engineer) |
| F-02, F-03 fmt wording; the fmt rule works for everyone | Accept |

## Manual changes

No code changes. All code is agent-written: step F and the `.gitignore` change accepted as written; the Terraform and scripts written by infra-engineer, repaired by it in repair 01, and accepted. I ran `terraform init`, set `owner` in my local variable file, ran `plan.sh`, checked the plan and the EMR versions, and copied the reports into this folder. The raw plan output and `plans/*.record.json` contain my account ID; they stay in the untracked `plans/` folder, and I deleted my extra copy (`/tmp/p12plan.json`).

## Deferred

- **Phase 13:**
  - config file inside the image;
  - finish the scripts (pass the git commit as the code version; more smoke tests);
  - first `s3://` run;
  - loaders that work on AWS: today the ingest job reads only local files, the DynamoDB and Neptune loaders have no entry point, and the Spark export writes only to local disk (rag, storage, spark engineers; a queue setting for domain-engineer if needed);
  - if the loaders need batch writes, widen the boundary (only my own profile can, through a new plan);
  - Hadoop jar without a main class in its manifest;
  - the evidence archive format for cleanup;
  - container health check if quick;
  - the pipeline: the Spark half needs the Hadoop output of an earlier run, so one run can't chain both; entity resolution is not an EMR step (2b R8);
  - install GNU coreutils (`timeout`), which the scripts need;
  - fix the aws-api and terraform MCP servers;
  - plus earlier items: Phase 4 finding 2 and Phase 6 F6 (code version), A9, R3-06, D-07.
- **After the Phase 14 apply:**
  - enter the four secret values;
  - subscribe my email to the alarm topic;
  - turn on cost-allocation tags;
  - push the image, then sign a second plan that turns the runtime on.
- **Phase 16:** custom metrics; a time limit on the evidence and explain routes; items earlier gates sent there.
- **Phase 17:** remove the hand-made IAM user `scholarchain-readonly` and budget `scholarchain-25`; EMR's own endpoint and security group; cleanup by tag if my state is lost; final repository review. (The Nov 16 test load balancer is already gone; it cost a few cents.)

## Remaining risks

- Nothing has been applied; every script is untested.
- Prices in `COSTS.md` are estimates. I check real charges after the Phase 14 apply, turn the runtime off right after Phase 15, and clean up right after Phase 16.
- Neptune's security group allows no outgoing traffic; the Neptune loader may not reach S3 (Phase 14).
- The first EMR cluster may fail on a missing permission, a folder listing or a step detail on `emr-spark-8.1.0` (Phase 15).
- Turning the runtime on should start one task per service; task sizes may need tuning (Phase 14).
- Starting a Neptune load may need one more permission (Phase 14).
- EMR makes its own endpoint (costs by the hour) and maybe a security group that blocks removing the VPC (Phases 15 and 17).
- If one IAM policy turns out too big, the apply fails on it, and I fix it with a new plan.
- Neptune's free-trial terms beyond "750 hours for t3/t4g" are unconfirmed.
- After any pipeline run I stop by hand, I check for running EMR clusters and an `emr-service-vpce` endpoint.
- Terraform is pinned to 1.17.1: I don't upgrade it before Phase 17, or every command fails.

## Acceptance

I ratify the step F changes to `.mcp.json`, `.claude/settings.json`, `docs/agents.md` and the dataset-engineer and infra-engineer files (commit 4ad139e63ec05be4cebf38bf46655f00ae5ff9f2).

I accept Phase 12 as of commits 4ad139e63ec05be4cebf38bf46655f00ae5ff9f2 and 80a3606ce513a6c782b87cffbc2c9d361bc0fc3f, and I sign the plan above.
