STATUS: COMPLETE
OWNER: build-engineer
WORK ORDER: docs/agent-runs/phase-17/prompt.md (sha256 c22adfc57c7fde0cae63c8520393c2c81cd12280df7f5df773e70c2e2ec8ac24, verified with shasum), session 2 step 3a, D-01 only (decision 9.1, tier 2). Source finding: docs/agent-runs/phase-14/review-report-a.md:122.
FILES CREATED: none in the repository (scratch only: d01-before.txt, d01-after.txt, d01-tracked-before.txt in the session scratchpad)
FILES MODIFIED: /Users/brian/Developer/repos/scholarchain/.gitignore (+13 -1)
COMMANDS RUN (all read-only git or shell):
  - shasum -a 256 docs/agent-runs/phase-17/prompt.md
  - git status --short --ignored experiments releases docs datasets (before and after, saved to scratch, then diff)
  - git check-ignore -v experiments/.DS_Store experiments/evaluation/raw/ratings/.DS_Store (before and after; after also releases/x/.DS_Store, releases/._a, experiments/.idea/x)
  - git ls-files | grep -E '(^|/)(\.DS_Store|Thumbs\.db|ehthumbs\.db|Desktop\.ini|\._[^/]*|\.idea/|\.vscode/|[^/]*\.iml|...)' (no match, exit 1)
  - git ls-files -ci --exclude-standard | wc -l (0 before, 0 after: no tracked file matches any ignore rule)
  - git ls-files experiments releases (563 paths, identical before and after)
  - git check-ignore -v experiments/raw/run.log releases/course/tfplan (negation still wins for non-noise names)
  - git diff --stat -- .gitignore
  One attempt was blocked by the guard (shell write target used a $S variable, not literal); rerun with literal paths, no bypass.
TEST RESULTS: no sbt run, per work order (.gitignore is not a stop-hook build input).
VALIDATION RESULTS:
  - Before: experiments/.DS_Store, experiments/evaluation/.DS_Store, experiments/evaluation/raw/.DS_Store, experiments/evaluation/raw/ratings/.DS_Store showed as `??` (untracked); check-ignore matched `.gitignore:83:!experiments/**` (re-included).
  - After: the same four files show as `!!` (ignored); check-ignore matches `.gitignore:113:.DS_Store`. releases/x/.DS_Store -> :113, releases/._a -> :114 `._*`, experiments/.idea/x -> :118 `.idea/`.
  - Every other line of the status output is unchanged (diff shows only the four .DS_Store lines moving from ?? to !!). docs/ and datasets/ .DS_Store were already ignored and stay ignored.
  - Tracked files: 0 tracked files match the new patterns; 0 tracked files are ignored by any rule; all 563 tracked files under experiments/ and releases/ are still tracked (list identical).
  - The change only adds ignore rules after the negation, so nothing is newly un-ignored.
ASSUMPTIONS: Pattern set = the review's list (.DS_Store, .idea/, .vscode/, *.iml) plus the work order's examples (Thumbs.db, ._*) and two analogous Windows metadata names (ehthumbs.db, Desktop.ini). `._*` is safe: no tracked or untracked file under experiments/ or releases/ starts with `._`.
RISKS: Low. A future raw result or release record whose name starts with `._` or equals one of these names would be silently ignored; none exists today. Noise rules were not added to target/ or *.class (review also listed them as overridden); left re-included per decision 5's intent that raw results are kept whatever their names, and only OS/IDE noise was in scope.
MANUAL ACTION REQUIRED: none. No tracked .DS_Store exists (start check 2: 0; confirmed). The four .DS_Store files under experiments/ remain on disk (not deleted) and are now ignored; nothing to unstage.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code review (R-1) of the .gitignore diff; no sbt check required for this file.

INTERFACE HANDOFF
- Files changed: .gitignore only. Comment of the FINAL RULE block (now lines 76-85) extended to name the second exception; new block at the end (lines 112-120):
  # OS and IDE noise (D-01), repeated after the negation so it stays ignored everywhere.
  .DS_Store / ._* / Thumbs.db / ehthumbs.db / Desktop.ini / .idea/ / .vscode/ / *.iml
- Public signatures: none. Fixtures: none. Thread-safety: n/a.
- Limits: the end block must stay last after `!experiments/**` and `!releases/**` (lines 86-87); E2's fallback (`.git/info/exclude`) should no longer be needed for .DS_Store.
- B-03 not touched (not routed to build-engineer this phase).

Agent-specific sections: version ledger, module graph, environment variables, packaging: unchanged (no build file touched).

VALIDATION EVIDENCE
- git status --short --ignored experiments releases docs datasets: before 4 x `?? experiments/**/.DS_Store`; after the same 4 as `!!`; all other lines identical.
- git check-ignore -v experiments/.DS_Store experiments/evaluation/raw/ratings/.DS_Store: before `.gitignore:83:!experiments/**`; after `.gitignore:113:.DS_Store` for both (exit 0).
- git ls-files -ci --exclude-standard | wc -l: 0; tracked experiments/ + releases/ list: 563 paths, unchanged; tracked noise-pattern matches: none.
- files changed after these runs: none (only .gitignore was edited, before the validation runs)
