# repair-E00-experiment (P16-L-02, carries P16-C-02)

runtime: untested (evaluate.py, python and sbt were not run, as the work order says; the reviewer traces the fixtures by hand and the human runs them at E1)

## Progress log

- [x] Checked the work order's sha256 (c75cc5f8…07dc8) and HEAD (d37389bf). experiments/** was clean at the start. evaluate.py's sha256 at the start was ba42ad4d…ecae, the same as the suite pin.
- [x] (1) explain-csv: the three Wilson rows are now descriptive. The rows are abstention_rate, abstention_recall_on_labelled and false_abstention_rate.
- [x] (2) audit: attempts are collapsed into one answer per question. An interval is computed only over at least 2 independent answers.
- [x] (3) Every other interval in `answers` was checked. Demo answers that repeat a question are now descriptive. A denominator of 1 never gets an interval (uniform rule). The attempt-06 Wilson rows are kept, because they cover distinct benchmark questions with one answer each.
- [x] Fixtures: happy expected outputs updated; new case `collapse-independent` added.
- [x] Suite: TOOL_VERSION is evaluate-v1-r2; both pins moved to dd5af6cf…; status, method and variability texts updated.
- [x] Validation: shasum and jq.

## Report contract

STATUS: COMPLETE
OWNER: experiment-engineer
WORK ORDER: docs/agent-runs/phase-16/prompt.md (sha256 c75cc5f895faed13880a69ec285231c5498fd50924c7b875fe7e02cce1707dc8), Phase 16 session 3, repair-E00-experiment, finding P16-L-02 (carries P16-C-02), HEAD d37389bf764c6b6fe2c579167a35919973409cb6
FILES CREATED:
- experiments/evaluation/fixtures/answers/collapse-independent/case.json
- experiments/evaluation/fixtures/answers/collapse-independent/input/audit.json
- experiments/evaluation/fixtures/answers/collapse-independent/input/demo-a.json
- experiments/evaluation/fixtures/answers/collapse-independent/input/demo-b.json
- experiments/evaluation/fixtures/answers/collapse-independent/expected/sources.csv
- experiments/evaluation/fixtures/answers/collapse-independent/expected/rates.csv
- experiments/evaluation/fixtures/answers/collapse-independent/expected/per-answer.csv
- experiments/evaluation/fixtures/answers/collapse-independent/expected/checks.csv
FILES MODIFIED:
- experiments/evaluation/evaluate.py
- experiments/evaluation/suite-v1.json
- experiments/evaluation/fixtures/answers/happy/case.json (description only; args unchanged)
- experiments/evaluation/fixtures/answers/happy/expected/rates.csv
- experiments/evaluation/fixtures/answers/happy/expected/sources.csv
- experiments/evaluation/fixtures/answers/happy/expected/per-answer.csv
COMMANDS RUN:
- shasum -a 256 (work order, evaluate.py, suite, fixture files)
- jq empty (suite-v1.json, both case.json files, the three new input JSON files)
- jq -c on the new audit.json, to parse the embedded record strings
- read-only git (rev-parse, status, diff)
- grep and sed -n reads
TEST RESULTS: none run (work order: no python, no evaluate.py, no sbt). runtime: untested.
VALIDATION RESULTS: see VALIDATION EVIDENCE.
ASSUMPTIONS:
- A1. Collapse key: questionHash, which is question identity. When a record has no questionHash, the key is queryRunId ('unknown' when absent). This errs toward collapsing more.
- A2. Kept outcome: the last attempt, meaning the largest numeric `attempt`. A record without a numeric attempt counts as earlier than any numbered one, and file order breaks ties.
- A3. "Independent answers" means at least 2 answers, each with a question reference, and no question repeated. One answer is always descriptive: a bootstrap over 1 unit gives a zero-width interval, which overstates precision. For consistency, wilson_row also makes any denominator of 1 descriptive, for every source.
- A4. Demo answers are not collapsed. Each one is a separate saved query, so it may differ. When they repeat a question, every demo rate is descriptive.
- A5. The attempt-06 abstention rows keep Wilson. They cover distinct benchmark questions with one answer each (real n = 30, 3 and 27). Decision 13 item 9 asks for Wilson on answer-level rates.
- A6. "Interval columns empty" is read as ci_low and ci_high empty. ci_method says `no interval (descriptive)`, matching the existing `no interval (aggregated)` style.
RISKS:
- R1. Untested runtime. The expected CSVs were derived by hand. The quoting follows csv.writer QUOTE_MINIMAL: fields that contain a comma are quoted, semicolons are not.
- R2. The attempt-06 Wilson rows remain over a hand-written benchmark, which is not a random sample. Decision 23's "no interval on a non-random sample" could be read to cover them. This needs a human decision if wanted; not changed here.
- R3. In collapse-independent, the demo bootstrap rows for coauthor-edge and number rely on at least one of the 50 resamples drawing answer 1 (seed 7). The happy r1 fixture already relied on the same thing.
MANUAL ACTION REQUIRED:
- Human at E1: `python3 -I experiments/evaluation/evaluate.py answers --fixtures`. 4 cases now: collapse-independent, happy, malformed, missing.
- documentation-engineer: change docs/evaluation.md lines 45, 127, 138 and 187 (see below).
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer traces both answers fixtures by hand against the new evaluate.py; the human runs E1 (the answers fixture count is now 4); then E3 signs suite-v1.json (sha256 20e4f2683cf755428a12ee15e693d226a3cefad3e19b607dcd75ace7ca210194 as of this report).

## Manifest IDs, run IDs, raw data, scale, cost

- Manifest IDs: none (no experiment run). Suite entry changed: E8-answers (method and variability text only; arguments and inputs unchanged).
- Run IDs: none. Raw-data locations: none written.
- Scale-out and scale-in evidence: not applicable.
- Failures: none.
- Cost: 0. No cloud call.

## docs/evaluation.md lines for documentation-engineer (not edited)

- :45, group 4 row, last cell "bootstrap over answers where per-answer values exist; Wilson for answer-level rates". Change to: intervals only over independent answers (bootstrap or Wilson); explain rows, clip 01's collapsed audit and repeated demo questions are descriptive.
- :127 "for two attempts at one question". Add: the two attempts are collapsed into one answer, keeping the last attempt's outcome (suite :168).
- :138, Variability. Replace "Answer-level rates (abstention, the dblp.org check) use Wilson score 95 %. Clip 01's two attempts answer one question and are not independent." with the suite :170 rule: Wilson only over independent answers; descriptive k, n and proportion for the D-query explain rows ("neither independent nor a random sample"), for clip 01 (one answer after collapsing) and for demo answers that repeat a question; no interval for a denominator of 1. (The dblp.org Wilson remains P16-C-04's open tool gap.)
- :187 `evaluate.py:198`. The function `wilson` is now at evaluate.py:200. Also narrow "group 4 answer-level rates" to "over independent answers".
- Suite line numbers did not move (suite :150-172, :168, :170, :171 still valid).

## INTERFACE HANDOFF

- Files changed: as listed above.
- Public interface: `evaluate.py answers`. The command-line arguments are unchanged. Output files and columns are unchanged: sources.csv, rates.csv, per-answer.csv and checks.csv have the same headers.
- Changed semantics:
  - rates.csv, descriptive rows: value = k/n; ci_low and ci_high empty; ci_method `no interval (descriptive)`; note gains a `descriptive; …` reason, joined to any existing note with "; ". The reasons are:
    - `descriptive; explain rows are neither independent nor a random sample` (every explain-csv rate with a value)
    - `descriptive; N answer(s) after collapsing attempts, an interval needs at least 2 independent answers` (audit)
    - `descriptive; N answer(s), an interval needs at least 2 independent answers` (demo with fewer than 2)
    - `descriptive; 1 answer(s), an interval needs at least 2 independent answers` (any wilson_row with n = 1)
    - `descriptive; answers repeat a question, so they are not independent` (demo)
    - `descriptive; an answer lacks its question reference, so distinct questions cannot be shown` (demo)
  - audit source: every rate is over collapsed answers.
    - abstention rows' inclusion_rule: `all answers of the source, ` + the collapse rule.
    - claim rows' note gains `claims of each answer's last attempt`.
    - sources.csv: granularity `per answer counts (attempts collapsed, last attempt kept); no claim texts`; answers = number of collapsed answers; note `N attempt record(s) collapsed into M answer(s): <rule>`.
    - per-answer.csv: one row per collapsed answer, answer_id `<queryRunId>#attempt-<n> (last of K attempt(s))`.
  - demo source, sources.csv note: `provenance: …` plus the descriptive reason when one applies.
  - Unchanged: attempt-06 claim rows, other rows and Wilson rows with n of at least 2; every other subcommand.
- Real E8 effect (expected, untested):
  - attempt-06: unchanged.
  - D-query: all three abstention rows descriptive.
  - clip 01: 2 records collapse into 1 answer, so the claim rates and the abstention rate are descriptive.
- New helpers in evaluate.py: `join_notes(*parts)`, `independence_reason(questions, what)`, `collapse_attempts(records)`. New optional parameter `descriptive=""` on `claim_rows(...)` and `wilson_row(...)`. Constants: `NO_INTERVAL`, `EXPLAIN_DESCRIPTIVE`, `AUDIT_COLLAPSE_RULE`. answers_audit records gain the `query` and `attempt_no` keys.
- New pins:
  - evaluationCode: version evaluate-v1-r2, sha256 dd5af6cf1d7401681396b3dc55b3bdced1cdfcd3d036e4fdbb236810506aeb0f.
  - Rating-sheet generator: version `evaluate-v1-r2 ratings-sheet`, same sha256.
  - suite-v1.json: sha256 20e4f2683cf755428a12ee15e693d226a3cefad3e19b607dcd75ace7ca210194.
- Fixtures: answers/happy (expected outputs updated); answers/collapse-independent (new):
  - 3 audit records: qa attempt 2 first in the file, qa attempt 1 last (abstained, NO_EVIDENCE), qb attempt 1. They collapse into 2 answers, keeping qa attempt 2 by number, not by file order.
  - Equal ratios, so every bootstrap bound equals the point value. Wilson 0/2 gives [0, 0.65762].
  - 2 demo answers to distinct questions, which keep the bootstrap and Wilson intervals.
- Thread-safety: single-threaded command-line tool; no change.
- Limits: standard library only; no new dependency.

## VALIDATION EVIDENCE

- `shasum -a 256 docs/agent-runs/phase-16/prompt.md` -> c75cc5f895faed13880a69ec285231c5498fd50924c7b875fe7e02cce1707dc8 (matches the work order)
- `git rev-parse HEAD` -> d37389bf764c6b6fe2c579167a35919973409cb6
- `shasum -a 256 experiments/evaluation/evaluate.py` (before) -> ba42ad4dba868ab1aa2367ddb9437f841d59eb702e3444cd8f5494416208ecae
- `shasum -a 256 experiments/evaluation/evaluate.py` (after the last code edit) -> dd5af6cf1d7401681396b3dc55b3bdced1cdfcd3d036e4fdbb236810506aeb0f; `grep` shows suite-v1.json lines :15 and :42 carry this value
- `shasum -a 256 experiments/evaluation/suite-v1.json` (final) -> 20e4f2683cf755428a12ee15e693d226a3cefad3e19b607dcd75ace7ca210194
- `jq empty` passed on: suite-v1.json, happy/case.json, collapse-independent/case.json, collapse-independent/input/audit.json, demo-a.json, demo-b.json. Each printed its OK marker.
- `jq -c` on the new audit.json parsed all 3 embedded records: ["2","sha256:qa",3,false,[]], ["1","sha256:qb",3,false,[]], ["1","sha256:qa",0,true,["NO_EVIDENCE"]]
- New fixture input sha256 values: audit.json 6afc0c3f…a212, demo-a.json e62762c2…8730, demo-b.json 644f55e3…e01a (written into collapse-independent/expected/sources.csv)
- `git status --short experiments`: 6 modified files and 1 untracked folder (collapse-independent/), exactly the listed files
- files changed after these runs: none
