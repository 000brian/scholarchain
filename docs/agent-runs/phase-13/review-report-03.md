STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 13, repair round 2 re-check (read-only), authorized by the human architect before the gate. Work order: docs/agent-runs/phase-13/prompt.md (sha256 60c943dccc86a6dc58ec074b95bbd63f04e2adca2f7a1c9a9abad266f28d36a7; I did not recompute the hash), "Two identity rules" and decision 9.1. Items: I-19 and I-23 (review-report-c.md), repair in repair-02-infra.md.
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools: Read, Grep, Glob)
TEST RESULTS: not applicable. I ran nothing. I relied on the orchestrator's facts: `shellcheck -x -P SCRIPTDIR` on the new gate-4 scope exits 0, and the only Terraform file reads are course/main.tf:134 and emr/main.tf:21.
VALIDATION RESULTS: Static reading only. My own Grep of infra/terraform matches the orchestrator's facts. File-reading functions appear only at environments/course/main.tf:134 (`file("${path.root}/../../../../modules/storage/src/main/resources/s3vectors/index-spec-v1.json")`) and modules/emr/main.tf:21 (`filemd5("${path.module}/files/bootstrap.sh")`). The `.tf` files contain no `source_dir`, `source_file`, `path.cwd` or other path that leaves the tree. `path.root` is infra/terraform/environments/course, so the four `../` reach the repository root, and the path in the list matches a file that exists.
ASSUMPTIONS: (1) The human's repair order is the authority for widening the plan-validity rule. The prompt.md:359 wording ("no change under `infra/terraform/`") is human-owned and is not amended here. (2) The phase-17 factory-engineer item for verify-tests-ran.sh:182 (SC1087) is recorded in repair-02-infra.md:23, which the human archives. No repository file records it, because agents cannot write under docs/agent-runs/.
RISKS: (a) SC_PLAN_INPUT_PATHS is kept by hand. If a new `file()` or `templatefile()` read outside infra/terraform is not added to it, I-23 comes back. The comment at scripts/lib/identity.sh:38-46 and the text at scripts/README.md:31 say so, but no check catches it; the infra-engineer listed an automatic gate-8 check as "proposed later". (b) For the hooks, gates 1 and 3 check syntax (`bash -n`) and run the self-tests. They do not lint the way shellcheck does. check.sh:73-74 says "covered by gates 1 and 3, not by shellcheck", and scripts/README.md:36 says "not shellchecked here". Both are accurate; until the phase-17 item is done, the hooks simply get less checking. (c) No changed script has been run (status lines: "runtime: untested").
MANUAL ACTION REQUIRED: The human runs `bash scripts/check.sh` in a fresh clone (decision 13.3) to confirm that gate 4 passes on the final tree.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Human gate for Phase 13 (the fresh-clone check.sh run, then ratification).

VERDICT: APPROVE

## Rulings

| ID | Ruling | Evidence (file:line) |
|---|---|---|
| I-19 | RESOLVED | scripts/check.sh:467-483: `gate_shell` now loops over `scripts/*.sh scripts/lib/*.sh datasets/scripts/*.sh infra/terraform/modules/emr/files/bootstrap.sh` only (:473-474). A comment at :469-472 gives the scope. :481 runs `shellcheck -x -P SCRIPTDIR`, the form behind the orchestrator's exit 0. The hooks are still checked: gate 1 at check.sh:412-416 (validate-agent-files.sh) and gate 3 at :437-465 (`bash -n` on `.claude/hooks/*.sh` and `.claude/hook-tests/*.sh`, then both self-tests). The usage text at check.sh:70-74 and scripts/README.md:34-36 describe the same four file sets and the same gate 1 and gate 3 coverage. Gate name, marker, statuses and record fields are unchanged. The phase-17 factory-engineer item is recorded at repair-02-infra.md:23, and .claude/** was not edited. |
| I-23 | RESOLVED | scripts/lib/identity.sh:47-50: `SC_PLAN_INPUT_PATHS=(infra/terraform modules/storage/src/main/resources/s3vectors/index-spec-v1.json)` is a single variable with a comment (:38-46) saying why the outside entry is there and that the list must stay complete. :153 runs `git diff --quiet "${pc}" HEAD -- "${SC_PLAN_INPUT_PATHS[@]}"`, which is quoted array expansion, so there is no word-splitting or glob bug. The return codes are still those in the header at :128-132: 0 usable; 4 for bad hex (:135-137), a dirty tree (require_clean_tree, :139), not a commit (:140-143), not an ancestor (:145-147) or a changed input (:154-157); 5 when git fails (:148-150, :158-160, and require_clean_tree's 5). At :155, `>&2 2>/dev/null` sends the `--name-only` list to the original stderr and drops git's own errors, which is correct. A spec file that is moved, deleted or newly added shows up in the tree-to-tree diff, so the plan is refused (fail closed). The rule text is the same in five places: identity.sh:21-26, plan.sh:65-75, deploy.sh:51-58 and :93-96, cleanup.sh:80-91, scripts/README.md:31. The info lines at deploy.sh:331 and cleanup.sh:496 print the list. |

## Other checks (no finding)

- Refusals still come before any cloud call. In deploy.sh main (:658-666), clean-tree, plan lookup, plan_usable and approval all run before check_cloud_prerequisites and check_account, the first `sts` call. In cleanup.sh main (:841-849), check_cleanup_plan_local (lookup, checksum, plan_usable, approval) runs before read_outputs, which makes the first `sts` call.
- check.sh gate 8 is not affected by the new text. No new `$(out NAME)` or `.NAME.value` match: the new texts contain `.json.` followed by a space, and `/../`, neither of which fits `\.[a-z_][a-z0-9_]*\.value`. There is no new `org.scholarchain.*` class name and no new `# contract:` marker.
- Gate 9 probes are not affected. The help probe regex `^Usage: scripts/NAME\.sh` and the deploy-wrong-checksum path (find_plan exits 4 with `ERROR: deploy.sh step=plan-lookup exit=4`, or the clean-tree refusal exits 4 on a dirty tree) are unchanged.
- deploy.sh uses `${SC_PLAN_INPUT_PATHS[*]}` at :331 under `set -u`; this is safe because the array is defined and not empty (bash 3.2).
- The other descriptions of the plan rule were searched: scripts/**, infra/** (infra/terraform/README.md says nothing about plan validity) and .claude/commands/**. None still says "infra/terraform only". The only old wording left is in the human-owned prompt.md:359.

## New findings

None (I-27 onward: no verified critical, major or minor finding in the six changed files).

## Coverage

Files read in full: /Users/brian/Developer/repos/scholarchain/scripts/check.sh, /Users/brian/Developer/repos/scholarchain/scripts/lib/identity.sh, /Users/brian/Developer/repos/scholarchain/scripts/plan.sh, /Users/brian/Developer/repos/scholarchain/scripts/deploy.sh, /Users/brian/Developer/repos/scholarchain/scripts/cleanup.sh, /Users/brian/Developer/repos/scholarchain/scripts/README.md, /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/81476c99-8e4e-4aa9-aabf-b889baacaf6c/scratchpad/phase13-reports/repair-02-infra.md.

Read in part: review-report-c.md (finding table, lines 125-144); modules/storage/src/main/resources/s3vectors/index-spec-v1.json (first 3 lines, to confirm it exists). Searched with Grep: infra/terraform for file-reading functions and paths that leave the tree; scripts/**, infra/**, docs/** and .claude/commands/** for other descriptions of the plan rule.

Listed files not read: none. Not read and not needed: docs/agent-runs/phase-13/prompt.md in full (only the line 316 and 359 matches seen through Grep), and .claude/hooks/verify-tests-ran.sh (the SC1087 cause was taken as reported).
