# Phase 12 orchestrator summary (session 2)

PHASE RESULT: COMPLETE_WITH_OPEN_ITEMS

This result comes from rule 3. Every step is `done` and the final fmt, validate and shellcheck checks pass. No critical or major finding is open, and no requirement is `missing`. Open items remain: 14 minor findings, 1 `deviation` and several `blocked-on-human` items. None of this is the human's approval. The plan (decision 9) and the human gate accept the phase.

- Start: HEAD `4ad139e`. The tree was clean except `docs/agent-runs/phase-12/` (decision 1 check passed).
- Work order: `docs/agent-runs/phase-12/prompt.md`, sha256 `5744ab73aa42946f1c556d1257a45031fd57868b75480de623879a9c73de7f56`.
- MCP: the aws-api server failed to connect this session (CONNECTION_CLOSED). terraform and aws-docs worked. Facts that needed aws-api are marked `unverified`: AZ names, release-label availability in us-east-1, quotas.

## Steps

| Step | Who | Status | Report |
|---|---|---|---|
| 0 | build-engineer | done | agent-report-build.md |
| 1 | infra-engineer | done | agent-report-infra-1.md |
| 1h | human (`init -backend=false`) | done (lock file pins hashicorp/aws 6.59.0) | – |
| 2a | infra-engineer | done | agent-report-infra-2a.md |
| R-1 | code-reviewer | done (REQUEST_CHANGES) | review-report-a.md |
| 2b | infra-engineer | done | agent-report-infra-2b.md |
| 2c | infra-engineer | done | agent-report-infra-2c.md |
| R-2 | code-reviewer | done (REQUEST_CHANGES) | review-report-b.md |
| 3 | orchestrator triage | done: 3 major findings (I-01, I-07, I-13) routed to infra-engineer; minors recorded | this file |
| 4 | infra-engineer repair | done | repair-01-infra.md |
| 5 | code-reviewer re-check | done (APPROVE) | review-report-02.md |
| 6 | orchestrator summary | done | this file |

For every writer step, my re-run of the decision-7 checks matched the agent's evidence block. No step was sent back.

## Requirements (decision 6)

| Area / item | Status | Note |
|---|---|---|
| 1 Foundation | done | Pins: Terraform `= 1.17.1`, aws `= 6.59.0`. Default tags set. `STATE.md`. README documents the manual budget and cost-allocation tags. |
| 2 Network | done | 2 AZs (names unverified), one NAT (documented single-AZ egress risk), S3 and DynamoDB gateway endpoints, 11 chained security groups, no 0.0.0.0/0 ingress |
| 3 Identity and encryption | done | Roles; AWS-managed encryption; 4 secret containers in `app-keys.tf` with no values. Deployment role has a permissions boundary and a self-modification Deny (repair of I-01). |
| 4 Data lake | done | One versioned bucket, 7 prefixes, public-access block, TLS-only, lifecycle, `force_destroy = false` |
| 5 EMR | done | No cluster in Terraform. Release labels `emr-7.14.0` and `emr-spark-8.1.0` (both in the release guide; availability in us-east-1 unverified). On-Demand, managed scaling 2..4, idle end 900 s. |
| 6 DynamoDB | done | 6 tables exactly as in TableSpecs, on-demand, TTL on QuerySession, PITR and deletion protection off by variable |
| 7 Neptune | done | One db.t4g.medium, engine 1.4.7.1 (unverified), IAM auth, encrypted, backups 1 day, protection off by variable |
| 8 S3 Vectors | done | One bucket and 5 indexes from index-spec-v1.json. The policy limits the data plane to the query, worker and ops roles. |
| 9 OpenSearch | done | Not built (README line) |
| 10 Runtime compute | done, with 1 `deviation` | ECR, ECS, 4 ARM64 services, ops task, HTTP API to VPC link to internal ALB, throttling 10/5, `runtime_enabled` switch (off), autoscaling, stopTimeout 120 s, `skip_destroy` (I-07). **Deviation:** `/healthz` and `/readyz` are routed to query (reviewer recommends accepting). |
| 11 Workflow and messaging | done | 2 queues, each with a DLQ: visibility 150 s, maxReceiveCount 5. JSONata state machine with createCluster.sync, addStep.sync and terminateCluster.sync, a catch on every state that ends the cluster, the code version passed to every step. |
| 12 Observability | done | Log groups (7 days), dashboard, alarms on default metrics, SNS topic with no subscription. The app emits no custom metrics (LLM usage, S3 Vectors clients); this is listed as a gap. |
| 13 Cost and safety | done | `cleanup_mode` variable, protection variables, `COSTS.md` (every price `estimate (unverified)`) |
| Root outputs | done | |
| config/course.json | done | Env-var names only; all 17 `SCHOLARCHAIN_CFG_*` names checked against `ConfigKey.envVarFor` |
| Six scripts | done (written) + phase-13 (finishing) | See the script status lines below |
| Config file in the image | phase-13 | build-engineer |
| Git commit passed as code version (run by the scripts) | phase-13 | The state machine already takes `codeVersion` |
| First `s3://` run (EMR jobs, loaders) | phase-13 | |
| Loader `s3://` readiness | phase-13 | RagIngestJob reads only a local export (rag-engineer). The DynamoDB and Neptune loaders have no main class (storage-engineer). Until then the ops task cannot do useful work. |
| Secret values, `owner`, `expires`, plan | blocked-on-human | See the manual actions below |

## Findings

| ID | Severity | Owner | Status |
|---|---|---|---|
| I-01 | major | infra-engineer | RESOLVED (repair 01) |
| I-07 | major | infra-engineer | RESOLVED (repair 01) |
| I-13 | major | infra-engineer | RESOLVED (repair 01) |
| I-02 | minor | infra-engineer | open (`secretsmanager:*` allows PutResourcePolicy on the secrets) |
| I-03 | minor | infra-engineer | open (no tag-conditioned Deny on destructive actions; deviation for the human) |
| I-04 | minor | infra-engineer | open (no `s3:DeleteObjectVersion` for the bootstrap object) |
| I-05 | minor | infra-engineer | open (EMR delete on all of curated/* and graph/*; deviation for the human) |
| I-06 | minor | infra-engineer | open (vCPU budget check uses one vCPU value for both node types) |
| I-08 | minor | infra-engineer | open (cleanup.sh usage text must say the human's own profile; README partly fixed) |
| I-09 | minor | infra-engineer | open (deploy.sh does not wait for scale-in when the runtime is turned off) |
| I-10 | minor | infra-engineer | open (unneeded PassRole to apigateway; smaller impact after I-01) |
| I-11 | minor | infra-engineer | open (deployment role can read data-lake objects) |
| I-12 | minor | infra-engineer | open (README text out of date or contradictory) |
| I-14 | minor | infra-engineer | open (1 h session limit vs deploy.sh timeouts) |
| I-15 | minor | infra-engineer | open (because of `skip_destroy`, cleanup always ends with exit 6 on leftover task-definition revisions) |
| I-16 | minor | infra-engineer | open (deployment role can still UpdateRole and Tag itself, for example to raise its session cap) |
| I-17 | minor | infra-engineer | open (a bounded role can be given an external trust; residual risk not documented) |

Open reviewer risks for the plan review:
- RK-C: measure the size of the boundary and deployment policies in the first plan.
- Check that all seven roles show `permissions_boundary`.
- RK1: the Neptune security group has no egress rule; the loader's S3 access is unverified.
- RK-E: the archive layout is a Phase 13 contract.
- The Hadoop jar's Main-Class must not be set in its manifest (Phase 13).

## Validation evidence (orchestrator re-run after the repair)

- `terraform fmt -recursive -check infra/terraform`: exit 0, no files listed
- `terraform -chdir=infra/terraform/environments/course validate -no-color`: "Success! The configuration is valid."
- `shellcheck scripts/plan.sh scripts/deploy.sh scripts/rollback.sh scripts/smoke-test.sh scripts/run-bounded-pipeline.sh scripts/cleanup.sh`: exit 0, clean
- `jq empty config/course.json`: exit 0
- `git status --short --ignored -uall infra/terraform/environments/course`:
  - `course.tfvars`, `local.auto.tfvars.example` and `.terraform.lock.hcl`: `??` (untracked, not ignored), as required
  - `.terraform/`: `!!` (ignored), as required
  - no `local.auto.tfvars` exists yet

## Script status lines (all six: plan, deploy, rollback, smoke-test, run-bounded-pipeline, cleanup)

- static: shellcheck clean
- structure reviewed: command shapes read against the Terraform, AWS CLI, ECS API and services code, per script in agent-report-infra-2c.md
- runtime: untested
- human execution required

## NEEDS INIT items

None. Validate never needed init after step 1h.

## Approval prompts seen

None. The validate allow rule from step F worked (probed in 2a).

Command-list departures with no prompt:
- 2b used `cat`, `sed -n`, `find` and `date` for read-only inspection.
- The repair used one `cd` and one `sed -n`.
- No script, AWS CLI call, init or plan was run by any agent.

## Gaps routed to other owners (not repaired in this phase)

- spark-engineer: JsonlExport handles local paths only.
- service-engineer: `GET /v1/evidence/*` and `/v1/explain/*` have no route deadline under the ~30 s API Gateway limit.
- rag-engineer and storage-engineer: no custom CloudWatch metrics.
- domain-engineer: no configuration key for the embedding queue URL (only if that queue gets used).

## Manual actions for the human

1. `init`: done (step 1h).
2. Before the plan: set `owner` in `infra/terraform/environments/course/local.auto.tfvars` (copy the `.example`). Confirm `expires = "2026-12-31"` in `course.tfvars`. Check every price in `COSTS.md` and the Neptune free-trial terms.
3. Plan (decision 9):
   - Run `bash scripts/plan.sh`. A plan from the uncommitted tree is labelled `-dirty`, and deploy.sh refuses it, so sign a plan made from the committed tree.
   - Check the summary against the gate, `COSTS.md`, RK-C (policy sizes) and the `permissions_boundary` on all seven roles.
   - Optional: run a scanner.
   - Sign the checksum in `human-gate.md`.
4. Gate decisions: accept or refuse the `/healthz` and `/readyz` deviation; decide on I-03 and I-05 as deviations; decide which profile runs cleanup (I-08: the reviewer says your own profile).
5. After the first apply: enter the four secret values; subscribe an address to the SNS alarm topic; activate the cost-allocation tags.
6. Phase 14: a second signed plan with `runtime_enabled = true`, `image_tag` and `graph_run_id`, after the image with `/opt/scholarchain/config/course.json` is pushed.
7. Record in `human-gate.md`: the Nov 16 ALB probe, the hand-made IAM user `scholarchain-readonly` and the budget `scholarchain-25` (Phase 17 cleanup items, outside Terraform).
8. Cost: idle is not free. About $1.80/day while the Neptune trial lasts (about 13 days of the money left), about $3.90/day after it (about 6 days); all estimates. Turn the runtime off right after Phase 15, and run cleanup right after Phase 16.
9. Restore the aws-api MCP connection (it failed this session) before Phase 14 work that needs it.
