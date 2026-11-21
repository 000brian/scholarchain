VERDICT: APPROVE

This review covers repair L-11: the change to `out()` in /Users/brian/Developer/repos/scholarchain/scripts/cleanup.sh:362-365. I found no critical or major issue. The fix is correct, is limited to this one problem, and makes the script refuse nothing new except in the intended case.

Interface change: no. The `out NAME` signature and the `$(out NAME)` call form are unchanged. The only behavior change is that a boolean `false` output now prints `false` where it used to print nothing.

## Check results

**(1) The new expression `.[$k].value | if . == null then empty else . end`**, worked through using jq rules (I have no Bash, so I did not run jq):
- value `false`: prints `false`. The old `// empty` printed nothing. This was the defect.
- value `true`: prints `true`, same as before.
- string: printed as-is, same as before. An empty string `""` prints an empty line in both versions.
- number (including 0): same as before, because `//` drops only false and null.
- map or list: compact JSON under `-r`, same as before.
- missing key: `.[$k]` is null, so `.value` is null and nothing prints, same as before.
- output whose value is null: nothing prints, same as before.

The only input that behaves differently is boolean `false`.

**(2) Every caller of `out()` in cleanup.sh** (I read the whole file). The calls are `api_id` (:545), `alb_dns_name` (:547), `nat_gateway_id` (:548), `vpc_id` (:550, :569, :763), `ecr_repository_name` (:576, :778), `runtime_enabled` (:657), `ecs_cluster_name` (:660), `state_machine_arn` (:679) and `neptune_endpoint` (:697).
- I traced each one in infra/terraform/environments/course/outputs.tf and the module outputs.tf files. Every one except `runtime_enabled` comes from an always-created resource attribute that is a string (`aws_apigatewayv2_api.this.id`, `aws_lb.internal.dns_name`, `aws_nat_gateway.this.id`, `aws_vpc.this.id`, `aws_ecr_repository.services.name`, `aws_ecs_cluster.this.name`, `aws_sfn_state_machine.pipeline.arn`, `aws_neptune_cluster.this.endpoint`). None can be `false`, so no other caller changes.
- `runtime_enabled` is `var.runtime_enabled`, a boolean (outputs.tf:12-15).
- Before the fix, the check at :657 refused every time (false gave "", true gave "true"). Now it passes only on the literal `false` and still refuses on `true`, a missing output or null. That is fail-closed. Two independent checks still run after it: ECS desired 0 and running 0 (:667-672), then no running execution and protection off, all before CONFIRM.
- No caller depended on false printing as empty. The other jq reads (:348-350, :354, :472-523, :662, :690, :692, :700) do not use `out()` and were not changed.

**(3) check.sh gate 8 (a)**, scripts/check.sh:660-673:
- The first grep (`\$\(out(_json)? NAME\)`) still matches all the call sites listed above.
- The new comment text (`` `out runtime_enabled` ``, `` `// empty` ``) has no `$(`, so it adds no name.
- The second grep needs `.name.value`. `.[$k].value` starts with `[`, so it does not match, exactly as the old line did not. Gate 8 extraction is unchanged.

**(4) Nothing changed under infra/terraform.** The git status snapshot at session start shows only ` M scripts/cleanup.sh` and no untracked files. I could not run git myself (no Bash), so this rests on that snapshot. `SC_PLAN_INPUT_PATHS` (scripts/lib/identity.sh:47-50) is `infra/terraform` plus `modules/storage/src/main/resources/s3vectors/index-spec-v1.json`. `scripts/**` is not a plan input, so once this fix is committed and the tree is clean, removal plan d636c648… still passes `plan_usable`.

**(5) The author's report.** The fix, the audit verdicts, and the probe-coverage claim are accurate. I confirmed the probe at check.sh:857 runs `cleanup.sh --list` with no archive and exits 4 in check_archive, before `read_outputs`. Small inaccuracies are listed as L-13. The proposed-later item 1 (`DeletionProtectionEnabled // false` lets a missing field through) is correct, but its reasoning leaves out an ordering consequence (L-14).

## Findings

| ID | Severity | file:line | Failure input | Impact | Owner |
|---|---|---|---|---|---|
| L-12 | minor | scripts/cleanup.sh:365; scripts/check.sh:857 | Put `// empty` back in `out()` | No automated probe or test catches it: gate 9 stops before `read_outputs`. The defect would come back silently and only show at a live `--remove`. The author already lists this (move `out()` into scripts/lib/identity.sh and add a fixture probe) as proposed later, needing approval. | infra-engineer |
| L-13 | minor | repair-D27-infra-engineer.md, audit table | Reading the audit against the edited file | The line numbers are from before the edit; each is now 3 higher (654→657, 659→662, 687→690, 689→692, 469-514→472-517). The audit also leaves out the Neptune check at cleanup.sh:700 (`.DeletionProtection == true`), which lets a missing field through the same way as :692. This is a report accuracy issue only; the code is not affected. | infra-engineer |
| L-14 | minor (existed before this repair, not caused by it) | scripts/cleanup.sh:692, :700, :866-871 | DescribeTable or DescribeDBClusters returns no protection field while protection is actually on | The protection check passes, and after CONFIRM the script ends EMR clusters, removes endpoints and deletes every ECR image and every object version in the data-lake bucket before `terraform apply` fails on the protected resource. The author's statement that "terraform would fail on a protected table anyway" is true, but that failure comes after the data has been deleted. The chance is low, and reports/ is protected by the evidence-archive check. This belongs on the proposed-later list: treat a missing field as protected. | infra-engineer |

Note, outside the work order: scripts/run-bounded-pipeline.sh:791 still has `.[$k].value // empty`. Every output it reads is a string (state_machine_arn, ecs_cluster_name, ops_task_definition_family, ecr_repository_url, neptune_loader_role_arn, ops_security_group_id), so it has no defect today. It is a trap for any future boolean output.

Files read in full: /Users/brian/Developer/repos/scholarchain/scripts/cleanup.sh and /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/557a49c9-c5ae-4042-894f-f8d8fd8288bc/scratchpad/phase15-reports/repair-D27-infra-engineer.md.
Files read in part:
- /Users/brian/Developer/repos/scholarchain/scripts/check.sh:630-699
- /Users/brian/Developer/repos/scholarchain/scripts/lib/identity.sh:40-59
- greps of /Users/brian/Developer/repos/scholarchain/infra/terraform/environments/course/outputs.tf and infra/terraform/modules/{runtime,network,neptune,workflow}/outputs.tf
- a grep of scripts/** for `out()` definitions and callers

STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: docs/agent-runs/phase-15/prompt.md decision 13 (D27), decision 12 checklist; review of repair L-11 (scripts/cleanup.sh out()).
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools)
TEST RESULTS: none run (reviewer). No automated test covers out() (L-12).
VALIDATION RESULTS: jq results worked through by hand from jq rules for false, true, string, number, map, missing key and null; gate 8 regexes checked against the new line; output types traced through Terraform outputs.tf; plan inputs checked in identity.sh:47-50.
ASSUMPTIONS: The git status snapshot from session start (only scripts/cleanup.sh modified, no untracked files) is still current. The Terraform state's runtime_enabled output is the boolean false applied at D20.
RISKS: The fix has not been tested at runtime until the D27 re-run. L-14 (a missing protection field is treated as unprotected) existed before this repair.
MANUAL ACTION REQUIRED: The human commits the fix (plan_usable needs a clean tree), then D27 attempt 2 of `scripts/cleanup.sh --remove --plan-sha256 d636c648…`.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Human commit of scripts/cleanup.sh, then the D27 re-run. L-12, L-13 and L-14 are minor and go on the proposed-later list, with no repair round.
