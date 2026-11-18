# agent-report-build.md (Phase 14 session 2, step 1, build-engineer, B-04)

STATUS: COMPLETE
OWNER: build-engineer
WORK ORDER: docs/agent-runs/phase-14/prompt.md (sha256 b1f951683cc708c297d3e74d6b21332f5218324e4db0086d33307ec8bf8b88a2, verified with shasum), decision 5 (B-04), HEAD c4fbf62
FILES CREATED: none in the repository (this report only)
FILES MODIFIED: /Users/brian/Developer/repos/scholarchain/.gitignore (35 insertions, 3 deletions; sha256 2c5caf7286c0314c94c9ebc9be0c3686e23393920763cfe441f9e6f90416b4f5; 108 lines)
COMMANDS RUN:
- shasum -a 256 docs/agent-runs/phase-14/prompt.md; wc -l (prompt, .gitignore)
- grep -rn 'B-04' docs/agent-runs/phase-13/review-report-a.md (Grep tool not available in this session; fallback per standing rules)
- grep -n 'tfstate' .claude/hooks/guard-dangerous.sh | head -n 10 (to mirror the guard's secret path list, guard-dangerous.sh:175-185)
- git status --short --ignored experiments releases (before and after)
- git check-ignore -v on the paths (before and after), git check-ignore without -v (after)
- git status --short; git diff --stat; shasum -a 256 .gitignore; wc -l .gitignore
- One command was BLOCKED by the guard: the before-change loop that included the literal path experiments/raw/x/.env ("reading secrets ... is denied"). Not retried in any variant.
TEST RESULTS: no sbt (decision 5: .gitignore is not a build input). Not a wave closer.
VALIDATION RESULTS: see VALIDATION EVIDENCE.
ASSUMPTIONS:
- "Secret patterns" = the existing secrets section (.gitignore:32-56) plus Terraform state (*.tfstate, *.tfstate.*), matching is_secret_path in .claude/hooks/guard-dangerous.sh:175-185, which counts Terraform state as secret material.
- Non-secret rules (.terraform/, *.tfvars, override.tf, .DS_Store, target/ ...) no longer apply under experiments/** and releases/**, as decision 5 asks ("nothing under them is ignored").
RISKS:
- The secret rules now exist twice (.gitignore:32-56 and :85-108); a new secret rule must be added to both. The comment at .gitignore:76-82 says so.
- The decision 5 expectation "non-ignored paths print nothing and exit 1" holds only without -v: `git check-ignore -v` also prints the matching negation line and exits 0 for a re-included path (observed). The plain `git check-ignore PATH` form gives "nothing, exit 1"; both are reported below.
- experiments/raw/x/.env could not be checked by command: the guard blocks any command line naming a .env path (the orchestrator's re-run will hit the same block). Evidence instead: rule `.env` at .gitignore:85 comes after `!experiments/**` at :83, and the same block's `*.key` rule (:89) was observed to win (`experiments/raw/x/a.key` and `releases/course/a.key` -> `.gitignore:89:*.key`, exit 0).
- Before and after, `git status --short --ignored experiments releases` printed nothing: no existing file changes state.
MANUAL ACTION REQUIRED: optional, the human may run `git check-ignore -v experiments/raw/x/.env` in a terminal; expected `.gitignore:85:.env	experiments/raw/x/.env`.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: R-1 review (code-reviewer) of step 1.

## Change (B-04 status: done, .gitignore:13 and :76-108)
- Removed the early negations `!experiments/**/*.log` and `!releases/**/*.log` (old .gitignore:13-15); line 13 now points to the final block.
- Added the final block at the end: comment (:76-82) stating the final rule, `!experiments/**` (:83), `!releases/**` (:84), then the secret rules repeated (:85-108).

## Version ledger, module graph, environment inputs, packaging
Unchanged (no build file touched).

INTERFACE HANDOFF
- Files changed: .gitignore only.
- Public signatures, fixtures, thread-safety: not applicable.
- Limits: final rule = everything under experiments/** and releases/** is tracked-eligible except secret material and Terraform state; any rule added after the final block would override it.

VALIDATION EVIDENCE
- fmt: not applicable
- validate: not applicable
- shellcheck: not applicable
- git check-ignore (after the change):
  - `git check-ignore -v experiments/raw/x/crash.log` -> `.gitignore:83:!experiments/**`, exit 0 (negation match = not ignored); `git check-ignore experiments/raw/x/crash.log` -> nothing, exit 1
  - `git check-ignore -v experiments/raw/x/run.log` -> `.gitignore:83:!experiments/**`, exit 0; plain -> nothing, exit 1
  - `git check-ignore -v experiments/raw/x/a.plan` -> `.gitignore:83:!experiments/**`, exit 0; plain -> nothing, exit 1
  - `git check-ignore -v experiments/raw/x/target/r.json` -> `.gitignore:83:!experiments/**`, exit 0; plain -> nothing, exit 1
  - `git check-ignore -v releases/course/x.log` -> `.gitignore:84:!releases/**`, exit 0; plain -> nothing, exit 1
  - `git check-ignore -v experiments/raw/x/.env` -> not run: blocked by the guard (secret path on the command line). Proxy: `git check-ignore -v experiments/raw/x/a.key` -> `.gitignore:89:*.key`, exit 0 (ignored); `git check-ignore -v releases/course/a.key` -> `.gitignore:89:*.key`, exit 0 (ignored)
  - `git check-ignore -v evidence/runs/x.json` -> `.gitignore:16:evidence/`, exit 0 (ignored); plain -> prints the path, exit 0
  - `git check-ignore -v infra/terraform/environments/course/plans/x.tfplan` -> `.gitignore:74:infra/terraform/environments/course/plans/`, exit 0 (ignored); plain -> prints the path, exit 0
  - Before the change, for comparison: crash.log -> `.gitignore:73:crash.log` (ignored), a.plan -> `.gitignore:66:*.plan` (ignored), target/r.json -> `.gitignore:2:target/` (ignored).
- git status --short --ignored experiments releases -> nothing, before and after
- finished: after the last command above
- files changed after these runs: no
