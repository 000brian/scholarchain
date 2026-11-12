---
description: Route diagnosis of a ScholarChain incident (alarm, failed smoke test, DLQ growth, timeouts, failed EMR step) to incident-responder for read-only investigation and an incident report
argument-hint: <environment> <symptom or alarm name> [trace/run IDs]
---

# /incident

**Responsible agent:** `incident-responder`

Arguments: `$ARGUMENTS`

You are the orchestrator. Do not diagnose with mutating commands or fix anything yourself. Delegate diagnosis.

## Gates

1. Diagnosis is read-only (logs, metrics, traces, aws-api MCP in read-only mode).
2. No mutation is autonomous. Rollback requires `/rollback` and human approval. Bounded recovery (re-driving dead-letter messages, rerunning an idempotent job) requires an approved script and human approval.
3. Escalate immediately to the human for: suspected data corruption or loss, security exposure (leaked secret, unauthorized access), any destructive action, any cost-increasing action.
4. Never manufacture destructive incidents in a shared account.
5. The incident report under `docs/incidents/` contains: incident ID, start and end time, detection source, user-visible impact, trace and run IDs, timeline, root cause, recovery action, data-integrity assessment, follow-up owner, preventive test or control.
6. Follow-up fixes are routed through the orchestrator to the owning agent per `docs/agents.md`.

## Work order to delegate

```text
Use the incident-responder agent to diagnose the following incident in environment <env>: <symptom>.
Known identifiers: <trace/run IDs>.
Investigate read-only. Do not change anything. If rollback or recovery is indicated, state the exact approved script and target and stop for approval.
Write the incident report under docs/incidents/ and return the standard report contract with follow-up owners.
```
