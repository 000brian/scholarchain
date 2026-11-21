# repair-D27-infra-engineer (finding L-11, class 2 script defect)

STATUS: COMPLETE
OWNER: infra-engineer
WORK ORDER: docs/agent-runs/phase-15/prompt.md (sha256 3d806922c6dd74ada61bceca45d3a302740a4083f260f0ae3ca6b6b6b9dfa3e0, verified), decision 13 "When a step fails" class 2, D27; repair L-11, approved by the human.
FILES CREATED: none in the repository (this report only, in the scratchpad)
FILES MODIFIED: /Users/brian/Developer/repos/scholarchain/scripts/cleanup.sh (out() helper, line 362 -> 362-365)
COMMANDS RUN: shasum -a 256 on the work order; grep/sed -n reads of scripts/cleanup.sh, scripts/lib/identity.sh, scripts/check.sh, scripts/*.sh, infra/terraform/environments/course/outputs.tf; shellcheck scripts/cleanup.sh; git diff --stat; jq on literal inline fixtures (old vs new expression). No script, terraform, or aws command was run.
TEST RESULTS: no test suite applies (scripts only). jq fixture comparison: {"runtime_enabled":{"value":false}} -> new "false", old "" (the defect); value true -> both "true"; value null -> both ""; {} -> both ""; string output vpc_id -> new "vpc-x".
VALIDATION RESULTS: shellcheck scripts/cleanup.sh exit 0, no findings. git diff --stat: scripts/cleanup.sh | 5 ++++- (1 file, 4 insertions, 1 deletion).
ASSUMPTIONS: Terraform outputs read through out() on the --remove path are strings, a boolean (runtime_enabled), or null when the runtime is off; for those, the new expression differs from the old one only for boolean false.
RISKS: the fix is untested at runtime. cleanup.sh runtime line: --list tested in phase 15 (D25, exit 0); --remove: refused at D27 attempt 1 (exit 4, L-11); fix untested. scripts/** is not a plan input (SC_PLAN_INPUT_PATHS = infra/terraform and the s3vectors index spec), so the signed removal plan d636c648... still holds; plan_usable needs a clean tree, so the human must commit this fix before the re-run.
MANUAL ACTION REQUIRED: code-reviewer review; the human commits; then D27 attempt 2 of `scripts/cleanup.sh --remove` (deploy-engineer or the human, as the runbook says).
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer review of the diff, human commit, D27 re-run.

## Change

```bash
# out NAME: prints the value of terraform output NAME, or nothing when the
# output is missing or null. Not `// empty`: jq's `//` also drops boolean
# false, so `out runtime_enabled` printed "" for false (L-11, D27).
out() { jq -r --arg k "$1" '.[$k].value | if . == null then empty else . end' <<<"${OUTPUTS}"; }
```

The function name and the `$(out NAME)` call form stay the same, so check.sh gate 8 (a) still finds every output name. The new line's `.[$k].value` does not match gate 8's `.NAME.value` pattern, so no new output name comes from it.

## Audit of the --remove path (false-vs-missing trap)

| Location | Expression | Verdict |
|---|---|---|
| cleanup.sh:654 check_runtime_off, `out runtime_enabled` | out() | REAL instance (L-11), fixed |
| cleanup.sh out() calls: api_id, alb_dns_name, nat_gateway_id, vpc_id, ecr_repository_name, ecs_cluster_name, state_machine_arn, neptune_endpoint | out() | string outputs; same result as before |
| cleanup.sh:348-350 data_bucket_name, vector_bucket_name, vpc_id `// empty` | strings | not affected |
| cleanup.sh:354 `.Account // empty` | string | not affected |
| cleanup.sh:469-514 cleanup-record fields `// empty` (plan_file, environment, region, plan_commit // commit, account_sha256, account_id) | strings | not affected |
| cleanup.sh:659 `.ecs_service_names.value[$r] // empty` | strings | not affected |
| cleanup.sh:687 `.dynamodb_table_names.value // {}` | map | not affected |
| cleanup.sh:689 `.Table.DeletionProtectionEnabled // false` compared to "false" | boolean | true -> true and false -> false are correct; a missing field reads as "not protected" (fail open, see proposed later) |
| scripts/lib/identity.sh plan_usable | git only, no jq | not affected |
| scripts/lib/identity.sh archive_keys | shell string handling, no jq | not affected |
| scripts/lib/identity.sh:188 find_plan_record `.plan_sha256 // empty` | string | not affected |

## Proposed later (not on this fix, not changed)

1. cleanup.sh:689 `.Table.DeletionProtectionEnabled // false`: if the field were missing, the table would be counted as unprotected. DescribeTable always returns it, and terraform would fail on a protected table anyway, so this is a fail-open note only.
2. deploy.sh:530 `.runtime_enabled.value // false` and deploy.sh:380 `.variables.runtime_enabled.value // false`: booleans read correctly, but a missing output or variable reads as false. That is not the cleanup path.
3. Static guard in check.sh gate 8: flag `// empty` on any output whose outputs.tf value is a boolean variable (`var.*_enabled`).

## Probe coverage

check.sh gate 9 runs cleanup.sh only as `cleanup.sh --list` without `--evidence-archive` (exit 4). That probe stops in check_archive, before read_outputs, so it never reaches out(). The out() path needs terraform output and an STS call, so no offline probe can reach it without changing cleanup.sh's structure (cleanup.sh calls main unconditionally and cannot be sourced). No probe was added. A probe that fails without the fix would mean moving out() into scripts/lib/identity.sh and adding a fixture case to check.sh; that is under proposed later and needs approval, because it is more than a minimal fix.

## IAM changes
none

## Stateful-resource actions
none (no plan, apply, or AWS call)

## Plan summary and checksum
no new plan; signed removal plan d636c648... is unchanged and still valid (only scripts/** changed)

## Cost estimate
none

## Required environment variables by name
unchanged

## INTERFACE HANDOFF
- Files changed: scripts/cleanup.sh (out() only).
- Public signatures: `out NAME`, unchanged; it now prints `false` for a boolean false output (it used to print nothing). Missing or null outputs still print nothing.
- Fixtures: none in the repository; the jq checks used only inline literals.
- Thread-safety: not applicable (it reads the global OUTPUTS once loaded).
- Limits: object and array outputs print as JSON with jq -r, the same as before.

## VALIDATION EVIDENCE
- `shellcheck scripts/cleanup.sh` -> no output, exit 0 (`shellcheck rc=0`)
- `git diff --stat` -> ` scripts/cleanup.sh | 5 ++++-` / ` 1 file changed, 4 insertions(+), 1 deletion(-)`
- jq fixture: value false -> new `false`, old empty; true -> `true`/`true`; null -> empty/empty; {} -> empty/empty
files changed after these runs: none
