---
description: Route a controlled ScholarChain data, elasticity, retrieval, or failure experiment to experiment-engineer under an approved, committed manifest with immutable raw results
argument-hint: <experiment-manifest-path> <environment> <approval-reference>
---

# /experiment

**Responsible agent:** `experiment-engineer`

Arguments: `$ARGUMENTS`

You are the orchestrator. Do not run load generators, change capacity, or edit results yourself. Check the gates and delegate.

## Gates

1. A committed YAML or JSON manifest under `experiments/` defines the experiment, resource limits, concurrency levels, duration, and cost bound, and the human has approved it.
2. The environment and commit SHA are approved; the working tree is clean.
3. Execution uses only the approved runner (`scripts/run-experiment.sh` or the documented equivalent; the permission policy asks for confirmation). Scale changes come only from the manifest and Terraform-defined scaling policies; direct scale commands are blocked by hooks.
4. Production algorithms, defaults, Terraform, and scripts are not modified during or for the experiment.
5. Raw results are immutable after collection; failed runs are reported, not discarded.
6. Failure experiments are safe and bounded; never destructive in a shared account.

## Work order to delegate

```text
Use the experiment-engineer agent to execute approved manifest <manifest-path> in environment <env> at commit <sha>.
Approval reference: <approval-reference>. Cost bound: <bound>.
Use only the approved experiment runner. Do not modify production code, Terraform, or scripts.
Record commit SHA, dataset snapshot, configuration, resource limits, start and end times, Region, and run IDs.
Return the standard report contract plus raw-data locations, results, scale events, failures, and cost.
```
