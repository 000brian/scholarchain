# Code review: repair-E00-experiment (P16-L-02, carries P16-C-02), Phase 16 session 3

(Saved verbatim by the orchestrator from code-reviewer's final message.)

Work order: docs/agent-runs/phase-16/prompt.md (sha256 c75cc5f8…07dc8 as given), base HEAD d37389bf. This was a read-only review. I did not compute any sha256 because I have no Bash.

## Summary

The human decision is implemented correctly where the decision names a case. Explain rows are now descriptive, clip 01's attempts collapse to one answer, and demo answers that repeat a question are descriptive.

Two problems block approval:
- **The new fixtures will fail at E1.** Both answers fixtures (happy and collapse-independent) put quotes around a `sources.csv` cell that Python's csv writer will not quote, so the byte comparison fails.
- **One interval still covers a single answer.** The bootstrap can still run over one answer with claims. That breaks the "at least 2 independent answers" rule written in suite :170, and the new fixture records this behaviour as expected.

## Findings

| ID | Severity | Interface | Owner | file:line | Failure input and impact |
|---|---|---|---|---|---|
| P16-L-07 | major | no | experiment-engineer | experiments/evaluation/fixtures/answers/happy/expected/sources.csv:4; experiments/evaluation/fixtures/answers/collapse-independent/expected/sources.csv:2 (code: evaluate.py:2196 with AUDIT_COLLAPSE_RULE at :1730-1732) | **Input:** `answers --fixtures` (E1). **What happens:** the audit `note` cell is `"%d attempt record(s) collapsed into %d answer(s): " + AUDIT_COLLAPSE_RULE`. That text has no comma, no `"` and no newline. `Run.write_csv` uses `csv.writer` with the default QUOTE_MINIMAL (:394), so the tool writes the cell without quotes. Both expected files wrap it in quotes. `compare_fixture` (:2807-2821) compares byte for byte, so it reports `sources.csv differs` for both cases. **Impact:** `answers --fixtures` would report 2 of 4 passed and exit 5, so E1 fails. The agent's own rule (R1: "fields that contain a comma are quoted") was not applied to this cell. The other quoted cells in both files all contain a comma and are correct. **Repair:** remove the surrounding quotes from those two cells only. This touches fixtures only, and fixtures are not pinned in the suite. |
| P16-L-08 | major | no | experiment-engineer | evaluate.py:1795-1805 (`claim_rows` bootstraps whenever `descriptive` is empty, however many answers have claims); fixture collapse-independent/expected/rates.csv:19-22, :32-35; suite-v1.json:170 | **Input:** two demo answers to different questions, where only one cites coauthor_edge or metric evidence. The collapse-independent fixture is exactly this case. A second example is two audit or demo answers to different questions where one abstained with no claims. **What happens:** `independence_reason` checks the source's answers, not the answers that actually have claims in the set. The bootstrap therefore runs with "1 answers with claims", and every resample gives that one answer's ratio, so ci_low equals ci_high (1,1 or 0,0). Agent note R3 confirms the result depends on the random draw. **Impact:** a zero-width interval over one answer is published as precision. This contradicts suite :170 ("answers count as independent only when there are at least 2 of them"). It also contradicts the agent's own reasoning in A3 ("a bootstrap over 1 unit gives a zero-width interval, which overstates precision") and its `wilson_row` n = 1 rule. It affects the optional E7 demo source, and any audit with two or more questions where one answer has no claims. **Repair:** make `claim_rows` descriptive when `with_claims < 2`, with a `descriptive; …` note. Update the collapse-independent expected rows and the case description. Re-pin `evaluationCode` and the rating-sheet generator. |
| P16-L-09 | minor | no | documentation-engineer | docs/evaluation.md:45, :127, :128, :138, :187 | The doc still describes the old interval policy (see the line list below). The agent named :45, :127, :138 and :187. It missed :128 (demo answers). |

## Notes (not findings)

- **N1. Attempt-06 Wilson rows (the agent's open point R2).** Decision 3 and decision 13 item 9 allow them, and the human's P16-C-02 wording keeps them:
  - prompt.md:125 (group 4) and :274 (decision 13 item 9) both say "answer-level rates with Wilson intervals".
  - The human's P16-C-02 decision limits intervals to independent answers. Attempt-06 has one answer per distinct benchmark question.
  - Group 3 (:124) shows the same intervals are accepted over a hand-written benchmark when headed EXPLORATORY.
  - The "no interval on a non-random sample" line (:473) is the general variability statement; it is spelled out for group 2's samples at :123.
  - It is not a defect. If the human wants consistency, a later option is an EXPLORATORY header like group 3's.
- **N2. Extra rule beyond the decision.** `wilson_row` treats a denominator of 1 as descriptive for every source. The decision did not ask for this. It is consistent with the decision and documented in suite :170. In the happy fixture it changes attempt-06's recall and false-abstention rows (1 of 1). On real attempt-06 data (n of about 3 and 27) it changes nothing.
- **N3. Real clip 01 input checked.** docs/agent-runs/phase-15/dynamodb-sample/run-ledger-clip01-query.json has 2 query-audit records with the same questionHash, attempts 1 and 2. They collapse to 1 answer, keeping attempt 2 (verified 2, unsupported 0). All audit rates are then descriptive, as the decision requires.

## Check results

**(1) Explain rows.** Pass. :2156-2157, :2166-2168 and :2172-2174 pass EXPLAIN_DESCRIPTIVE. Each row keeps k, n and k/n, leaves ci_low and ci_high empty, sets ci_method `no interval (descriptive)`, and carries the note.

**(2) Clip 01 collapse.** Pass. `collapse_attempts` (:1965-1983) is deterministic:
- groups by questionHash, else by queryRunId;
- keeps the largest numeric attempt (a missing attempt counts as -1);
- breaks ties by file position.

The rule is documented in AUDIT_COLLAPSE_RULE, in suite :168 and in the sources note. The collapse-independent fixture shows the attempt number wins over file order.

**(3) Remaining intervals.** One fail (P16-L-08). Otherwise:
- attempt-06 Wilson rows: allowed (N1);
- attempt-06 claim rates: aggregated, no interval;
- audit and demo bootstrap and Wilson: only when `independence_reason` returns empty.

**(4) Fixtures traced by hand.**
- **happy:**
  - rates.csv: all 71 lines match, including Wilson(2,2) low bound 0.34238, the descriptive rows and the note quoting.
  - per-answer.csv: matches.
  - checks.csv: unchanged and matches.
  - sources.csv: line 4 fails (P16-L-07).
- **collapse-independent:**
  - rates.csv: matches (Wilson(0,2) high bound 0.65762, equal-ratio bootstrap bounds, abstention codes from the kept attempts only), subject to P16-L-08.
  - per-answer.csv and checks.csv (header only): match.
  - sources.csv: line 2 fails (P16-L-07).
- **malformed** (exit 5 in `answers_run_report`, before any output) and **missing** (exit 3 in `require_file`): code paths unchanged; both still match.

**(5) Scope and hygiene.** Pass.
- The diff touches only the docstring, TOOL_VERSION and the answers section (:1726-2279). TOOL_VERSION appears only in run.json and a STEP line, so no other subcommand's output changes.
- No new import. Floats go through `num()` (6 decimals).
- The fixtures contain no real identifiers or secrets. A grep finds no run of 12 or more digits in fixtures/answers.

**(6) Pins.** The text matches:
- suite :15 and :42 both read dd5af6cf1d7401681396b3dc55b3bdced1cdfcd3d036e4fdbb236810506aeb0f;
- versions read evaluate-v1-r2 (:14) and `evaluate-v1-r2 ratings-sheet` (:41), the same as TOOL_VERSION (evaluate.py:69).

I relied on the orchestrator's sha256 check of the file and the suite. Repairing P16-L-08 changes evaluate.py, so it needs a re-pin, which changes the suite's sha256. Repairing P16-L-07 alone does not.

**(7) docs/evaluation.md lines that must change** (documentation-engineer, after the code is final):
- :45: the last cell should say intervals only over independent answers (at least 2, distinct questions); explain rows, clip 01 after collapsing, and repeated demo questions are descriptive.
- :127: the two attempts are collapsed into one answer, keeping the last attempt's outcome (suite :168).
- :128: demo answers are not collapsed and are descriptive when they repeat a question. The agent missed this line.
- :138: replace with the suite :170 rule. Explain rows are descriptive ("neither independent nor a random sample"), clip 01 has one answer so no interval, a denominator of 1 gets no interval, and after P16-L-08 fewer than 2 answers with claims gets no bootstrap. Delete the sentence "Clip 01's two attempts … not independent", which is now superseded.
- :187: `evaluate.py:198` becomes `evaluate.py:200`, and "group 4 answer-level rates" narrows to "over independent answers". Recheck the line number after the P16-L-08 repair.

## P16-L-02 / P16-C-02 verdict: PARTIAL

Both cases P16-C-02 names are resolved in code: clip 01's dependent attempts, and the D-query explain rows. They match the human decision. But the fixtures as written fail E1 (P16-L-07), and the "at least 2 independent answers" rule is not applied to the bootstrap (P16-L-08).

## Files read

Read in full:
- the repair report repair-E00-experiment.md and the diff diff-repair-E00-experiment.txt (session scratchpad, phase16-reports/);
- every file under experiments/evaluation/fixtures/answers/collapse-independent/ (case.json, input/audit.json, input/demo-a.json, input/demo-b.json, expected/sources.csv, expected/rates.csv, expected/per-answer.csv, expected/checks.csv);
- under experiments/evaluation/fixtures/answers/happy/: case.json, all six input files, all four expected files;
- experiments/evaluation/fixtures/answers/malformed/case.json and input/run-report.json;
- experiments/evaluation/fixtures/answers/missing/case.json;
- docs/agent-runs/phase-15/dynamodb-sample/run-ledger-clip01-query.json.

Read in part:
- experiments/evaluation/evaluate.py: :1-440 (helpers, wilson, Run, setup_suite), :437-500 (check_pin), :1705-2298 (the whole answers section and every helper it calls), :2776-2879 (driver and fixtures). Other subcommands were not read beyond confirming the diff does not touch them.
- experiments/evaluation/suite-v1.json: :1-50 and :145-176.
- docs/agent-runs/phase-16/review-report-c.md: P16-C-02 row and its references.
- docs/agent-runs/phase-16/prompt.md: :120-126, :274, :473.
- docs/evaluation.md: :120-139 plus grep hits.

## Report contract

STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: docs/agent-runs/phase-16/prompt.md (sha256 c75cc5f895faed13880a69ec285231c5498fd50924c7b875fe7e02cce1707dc8), Phase 16 session 3, review of repair-E00-experiment (P16-L-02 carrying P16-C-02), base HEAD d37389bf764c6b6fe2c579167a35919973409cb6
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools)
TEST RESULTS: none run. I traced the fixtures by hand: collapse-independent and happy each have one sources.csv line that will not match (P16-L-07); malformed and missing still match.
VALIDATION RESULTS: The suite's pins match evaluate.py's TOOL_VERSION and the pinned sha256 text. I did not recompute the sha256 values (no Bash) and relied on the orchestrator's check.
ASSUMPTIONS: CPython's csv QUOTE_MINIMAL quotes a field only when it contains the delimiter, the quote character, the escape character, CR or LF. The audit note contains none of these.
RISKS: The answers tool has never run, so its runtime is untested until E1. The P16-L-08 repair changes evaluate.py, which needs a re-pin of evaluationCode and the rating-sheet generator before E3, and the suite's sha256 changes.
MANUAL ACTION REQUIRED: Optional human decision: whether attempt-06's Wilson rows should carry an EXPLORATORY header like group 3 (N1). They are allowed as written.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: experiment-engineer repairs P16-L-07 and P16-L-08 in one pass and re-pins both evaluate.py entries in the suite. code-reviewer then re-checks. The human runs E1 (`answers --fixtures`, 4 cases). documentation-engineer updates docs/evaluation.md :45, :127, :128, :138 and :187 once the code is final.

VERDICT: REQUEST_CHANGES
