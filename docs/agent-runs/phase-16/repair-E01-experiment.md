# repair-E01-experiment (P16-L-07, P16-L-08)

runtime: untested (evaluate.py, python, sbt and scripts were not run, as the work order says; the expected outputs were worked out by hand, and the human runs E1)

## Progress log

- [x] Checked the work order sha256 (c75cc5f8…07dc8, matches) and HEAD (6647034). The tree was clean at the start.
- [x] P16-L-07: removed the quotes from the two audit `note` cells. Then scanned every expected CSV in fixtures/answers for a quoted cell that has no comma, quote, CR or LF. None remain.
- [x] P16-L-08: `claim_rows` gives no bootstrap unless at least 2 answers have claims in the set. With exactly 1 the rows are descriptive and carry a note. With 0 the output is unchanged (the rows are not measurable).
- [x] Fixtures: updated the collapse-independent expected rates (8 rows) and its description; added the new case `one-with-claims`.
- [x] Re-pinned: TOOL_VERSION is evaluate-v1-r3; both suite pins and versions updated; status and variability texts updated.

## Report contract

STATUS: COMPLETE
OWNER: experiment-engineer
WORK ORDER: docs/agent-runs/phase-16/prompt.md (sha256 c75cc5f895faed13880a69ec285231c5498fd50924c7b875fe7e02cce1707dc8), Phase 16 session 3, repair-E01-experiment, findings P16-L-07 and P16-L-08, HEAD 6647034
FILES CREATED:
- experiments/evaluation/fixtures/answers/one-with-claims/case.json
- experiments/evaluation/fixtures/answers/one-with-claims/input/audit.json
- experiments/evaluation/fixtures/answers/one-with-claims/expected/sources.csv
- experiments/evaluation/fixtures/answers/one-with-claims/expected/rates.csv
- experiments/evaluation/fixtures/answers/one-with-claims/expected/per-answer.csv
- experiments/evaluation/fixtures/answers/one-with-claims/expected/checks.csv
FILES MODIFIED:
- experiments/evaluation/evaluate.py (claim_rows, docstring, TOOL_VERSION)
- experiments/evaluation/suite-v1.json (evaluationCode and rating-sheet version and sha256; status text; E8-answers variability text)
- experiments/evaluation/fixtures/answers/happy/expected/sources.csv (line 4, note cell unquoted)
- experiments/evaluation/fixtures/answers/happy/case.json (description only)
- experiments/evaluation/fixtures/answers/collapse-independent/expected/sources.csv (line 2, note cell unquoted)
- experiments/evaluation/fixtures/answers/collapse-independent/expected/rates.csv (lines 19-22 and 32-35)
- experiments/evaluation/fixtures/answers/collapse-independent/case.json (description only)
COMMANDS RUN:
- shasum -a 256 (work order, evaluate.py, suite-v1.json, new audit.json)
- jq empty (suite-v1.json, all three changed or new case.json files, new audit.json)
- read-only git (rev-parse, status, diff)
- Read-only shell reads beyond the work order's list, disclosed here: ls, cat, grep, sed -n and one awk pipeline, all used only to inspect fixtures and code. No file was written through the shell.
TEST RESULTS: none run (the work order forbids python, evaluate.py and sbt). runtime: untested.
VALIDATION RESULTS: see VALIDATION EVIDENCE.
ASSUMPTIONS:
- A1. "Answers that contribute to the denominator" means the units in the set with at least one claim (`with_claims`). Independence of those answers follows from the source-level check: when all of a source's answers answer distinct questions, any subset of them does too.
- A2. A set with 0 answers with claims is left as before: every row is "not measurable (no claims)", method empty, note unchanged. I did not add a "descriptive" note to rows that have no value.
- A3. In the new case, qd is an answer with no claims that did not abstain. I chose this so that the abstention row stays Wilson 0 of 2, whose bounds (0, 0.65762) were already traced by the reviewer. That keeps every hand-derived number already verified.
RISKS:
- R1. The runtime is untested and every expected row was derived by hand. The new note contains a comma, so it is quoted in rates.csv. The audit sources note has no comma, so it is unquoted.
- R2. The embedded record strings in the new audit.json were checked only with `jq empty` on the outer file. They follow the same escaping as collapse-independent/input/audit.json.
- R3. Removed: R3 of repair-E00 (collapse-independent's coauthor-edge and number bootstrap depended on the random draw). Those rows no longer bootstrap.
MANUAL ACTION REQUIRED:
- Human at E1: `python3 -I experiments/evaluation/evaluate.py answers --fixtures`, which now has 5 cases: collapse-independent, happy, malformed, missing, one-with-claims.
- documentation-engineer (P16-L-09, after E13): docs/evaluation.md :138 should also state that fewer than 2 answers with claims in a set means no bootstrap. Recheck the evaluate.py line number cited at :187: `wilson` is now at evaluate.py:201. I did not edit docs/evaluation.md.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer re-checks P16-L-07 and P16-L-08 (traces collapse-independent and one-with-claims by hand). The human runs E1 (5 answers cases). Then E3 signs suite-v1.json at sha256 0f7f7d336433e00363a94d522a2bbbfdc93274cd32605cb411d445b98a339970 (still unsigned).

## Which expected line fails without each fix

- P16-L-07 (no code change, fixture-only). Before this repair, the old expected lines happy/expected/sources.csv:4 and collapse-independent/expected/sources.csv:2 wrapped the audit note in quotes. csv.writer QUOTE_MINIMAL does not quote that cell, because it has no comma, quote, CR or LF. Both cases would report `sources.csv differs`. The corrected lines are the evidence. The new one-with-claims/expected/sources.csv:2 follows the same rule (note unquoted; the granularity cell has a comma and is quoted).
- P16-L-08. Under evaluate-v1-r2, these lines fail:
  - collapse-independent/expected/rates.csv:19-22 (coauthor-edge) and :32-35 (number). The old code writes ci_low and ci_high (1,1 or 0,0), the method `percentile bootstrap …; 1 answers with claims`, and the note without the descriptive reason. The new expected cells are empty, the method is `no interval (descriptive)`, and the note ends with `descriptive; 1 answer(s) with claims in this set, an interval needs at least 2 independent answers`. Only demo-a of the 2 independent demo answers cites coauthor_edge or metric evidence.
  - one-with-claims/expected/rates.csv:2-5 (audit, all claims). There are 2 independent answers (qc, qd) and only qc has claims. The old code writes the zero-width interval 1,1 (or 0,0) with `1 answers with claims`. The new code writes no interval and the descriptive note. This covers the reviewer's second example: an audit with 2 questions where one answer has no claims.
- Rows that do not change: the happy fixture's audit and demo sets were already descriptive, and its run-report has no per-answer units, so its rates.csv is unchanged. The collapse-independent audit and demo "all claims" rows keep the bootstrap, because 2 answers have claims. Graph-path and supporting-publication rows have 0 answers with claims and stay not measurable, unchanged.

## Manifest IDs, run IDs, raw data, scale, cost

- Manifest IDs: none (no experiment run). Suite entry changed: E8-answers (variability text only; arguments and inputs unchanged).
- Run IDs: none. Raw-data locations: none written.
- Scale-out and scale-in evidence: not applicable.
- Failures: none.
- Cost: 0. No cloud call.

## INTERFACE HANDOFF

- Files changed: as listed above.
- Public interface: `evaluate.py answers`. The command-line arguments, output files and headers are unchanged.
- Changed semantics, in `claim_rows(src, breakdown, totals, units, has_conflicting, seed, resamples, note="", descriptive="")` (signature unchanged):
  - When `units` is not None, no descriptive note is given, and exactly 1 unit has claims, the rows are descriptive: ci_low and ci_high empty, ci_method `no interval (descriptive)`, note joined with `descriptive; 1 answer(s) with claims in this set, an interval needs at least 2 independent answers`.
  - With 0 units with claims there is no bootstrap, and the rows are as before (not measurable).
  - With 2 or more units with claims, the bootstrap runs as before.
  - This applies to the audit "all claims" set, the demo "all claims" set and every demo claim-type set. Run-report (aggregated) rows are unchanged.
- Real E8 effect (expected, untested): none. Attempt-06 is aggregated, clip 01 is already descriptive (1 answer after collapsing), and the explain CSV has no claim rows. Optional E7 demo answers: a claim type cited by only one answer becomes descriptive.
- New pins:
  - evaluationCode: version evaluate-v1-r3, sha256 8e7f3e7478266a3de43ece14e40c351aa44262a61fd46e0058e01f95e5df96b3.
  - Rating-sheet generator: version `evaluate-v1-r3 ratings-sheet`, same sha256.
  - suite-v1.json: sha256 0f7f7d336433e00363a94d522a2bbbfdc93274cd32605cb411d445b98a339970 (unsigned).
- Fixtures:
  - answers/one-with-claims (new): one audit file, 2 records with questions qc (verified 2 of 2) and qd (no claims), neither abstained, seed 7, 50 resamples. Input sha256 b10f9a0d9da442cb0f029b3a2750d599c891a44aeb2326adf83be3626d12f5f5.
  - answers/collapse-independent: 8 rate rows and the sources note changed.
  - answers/happy: the sources note changed.
- Thread-safety: single-threaded command-line tool; no change.
- Limits: standard library only; no new import.

## VALIDATION EVIDENCE

- `shasum -a 256 docs/agent-runs/phase-16/prompt.md` -> c75cc5f895faed13880a69ec285231c5498fd50924c7b875fe7e02cce1707dc8 (matches)
- `git rev-parse --short HEAD` -> 6647034; `git status --short` at the start: clean
- `shasum -a 256 experiments/evaluation/evaluate.py` (after the last code edit) -> 8e7f3e7478266a3de43ece14e40c351aa44262a61fd46e0058e01f95e5df96b3; grep shows suite-v1.json :15 and :42 carry it; versions read evaluate-v1-r3 (:14) and `evaluate-v1-r3 ratings-sheet` (:41), matching TOOL_VERSION (evaluate.py:70)
- `shasum -a 256 experiments/evaluation/suite-v1.json` (final) -> 0f7f7d336433e00363a94d522a2bbbfdc93274cd32605cb411d445b98a339970
- `shasum -a 256 …/one-with-claims/input/audit.json` -> b10f9a0d9da442cb0f029b3a2750d599c891a44aeb2326adf83be3626d12f5f5 (written into its expected sources.csv)
- `jq empty` printed JQ-OK for suite-v1.json, happy/case.json, collapse-independent/case.json, one-with-claims/case.json and one-with-claims/input/audit.json
- Quote scan `grep -nE '"[^",]*"' experiments/evaluation/fixtures/answers/*/expected/*.csv` -> no match (exit 1). No `""` and no CR in any expected CSV.
- `grep -rn evaluate-v1-r2 experiments` -> only the suite status history sentence (:5), which is intended
- `git status --short` (final): 7 modified files under experiments/evaluation plus the untracked folder one-with-claims/. Nothing outside experiments/**; no modules/rag file is in progress in the tree, so the stop hook does not block.
- files changed after these runs: none
