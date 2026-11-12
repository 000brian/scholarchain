---
description: Route an approved ScholarChain deployment to deploy-engineer (approved commit, plan checksum, account, Region, environment)
argument-hint: <environment> <commit-sha> <plan-checksum> <approval-reference>
---

# /deploy

**Responsible agent:** `deploy-engineer`

Arguments: `$ARGUMENTS`

You are the orchestrator. Do not deploy, apply Terraform, or run deployment scripts yourself. Your job is to check the gates below and, only if all pass, delegate to `deploy-engineer` with a work order.

## Gates

All must hold before delegation. If any is missing, stop and ask the human; do not infer it.

1. The human has recorded approval naming: environment, commit SHA, Terraform plan checksum produced by `scripts/plan.sh`, AWS account, Region, and cost bound.
2. The human confirmed the plan has no unexplained destroy or replacement.
3. `code-reviewer` returned `APPROVE` for the commit, and required tests passed (`sbt check`).
4. The working tree is clean and `HEAD` equals the approved commit.
5. Execution uses only approved scripts: `scripts/deploy.sh`, then `scripts/smoke-test.sh`. No direct `terraform apply`, no ad-hoc AWS mutation (blocked by hooks).
6. Rollback gate: on failed smoke tests or stabilization timeout, `deploy-engineer` may roll back only to the recorded prior revision via the approved path; anything else escalates to the human (see `/rollback`, `/incident`).

## Work order to delegate

```text
Use the deploy-engineer agent to deploy commit <sha> with approved plan checksum <checksum> to environment <env> (account <account>, Region <region>).
Approval reference: <approval-reference>.
Refuse a dirty tree or any mismatch. Execute only scripts/deploy.sh and scripts/smoke-test.sh.
Wait for stabilization with a bounded timeout. Verify image digests and task revisions.
On failure, collect evidence and follow the approved rollback policy. No destructive data repair.
Return the standard report contract plus the release record.
```

After the report, present it to the human. The human archives it under `docs/agent-runs/`.
