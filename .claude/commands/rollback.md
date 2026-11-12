---
description: Route a ScholarChain service rollback to incident-responder, limited to the recorded prior revision through scripts/rollback.sh with human approval
argument-hint: <environment> <target-prior-revision> <reason>
---

# /rollback

**Responsible agent:** `incident-responder`

Arguments: `$ARGUMENTS`

You are the orchestrator. Do not run rollback scripts or AWS commands yourself. Check the gates and delegate.

Scope note: a rollback inside an in-progress `/deploy` (failed smoke test or stabilization timeout) is part of that deployment and stays with `deploy-engineer`. Every other rollback goes through this command.

## Gates

1. An incident record or failure evidence exists (smoke failure, alarm, error-rate spike). If not, run `/incident` first.
2. The target is the recorded prior revision from a release record (image digest and task revision). No other target is autonomous.
3. The human approves this rollback explicitly, naming environment, current revision, and target revision.
4. Execution uses only `scripts/rollback.sh` (the permission policy asks for confirmation). No Terraform apply, no data changes, no schema changes, no deletion.
5. Anything beyond reverting service revisions (data repair, infrastructure change, scale change, secret rotation) stops and escalates to the human.

## Limits on autonomous action

- Allowed after approval: one execution of `scripts/rollback.sh` to the approved revision, then health checks and `scripts/smoke-test.sh`.
- Not allowed: retries to other revisions, manual console changes, destructive or data-modifying actions.

## Work order to delegate

```text
Use the incident-responder agent to roll back environment <env> from revision <current> to approved prior revision <target>.
Approval reference: <approval-reference>. Reason: <reason>.
Execute only scripts/rollback.sh, then verify health and run scripts/smoke-test.sh.
Do not change data, schemas, infrastructure, or code. Escalate anything else.
Write or update the incident report under docs/incidents/ and return the standard report contract.
```
