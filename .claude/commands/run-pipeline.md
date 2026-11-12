---
description: Run a bounded ScholarChain Hadoop and Spark data pipeline in an approved environment through scripts/run-bounded-pipeline.sh, executed by deploy-engineer
argument-hint: <environment> <dataset-manifest-or-subset> <approval-reference>
---

# /run-pipeline

**Responsible agent:** `deploy-engineer`

Arguments: `$ARGUMENTS`

You are the orchestrator. Do not submit EMR steps, create clusters, or run the pipeline yourself. Check the gates and delegate.

Scope note: local, development-time sample runs are part of the gate of `hadoop-engineer` and `spark-engineer` and do not use this command. Measured experiments use `/experiment`.

## Gates

1. The environment is deployed from an approved plan and named in the human approval.
2. The input is a bounded subset recorded in `datasets/dataset-manifest.yaml` (snapshot, subset rule, checksum). No full-corpus run without explicit human approval and cost estimate.
3. EMR capacity stays within the Terraform-defined minimum and maximum. No manual scale-out or termination commands (blocked by hooks).
4. The commit SHA is approved and the working tree is clean.
5. Execution uses only `scripts/run-bounded-pipeline.sh` (the permission policy asks for confirmation).
6. The run records dataset snapshot, run IDs, configuration, output locations, counters, rejects, duration, and estimated cost.

## Work order to delegate

```text
Use the deploy-engineer agent to run the bounded ScholarChain pipeline in environment <env> on manifest subset <subset> at commit <sha>.
Approval reference: <approval-reference>. Cost bound: <bound>.
Execute only scripts/run-bounded-pipeline.sh. Do not edit scripts, Terraform, or code. Do not change cluster capacity.
Return the standard report contract plus run IDs, output locations, counters, rejects, duration, and cost.
```
