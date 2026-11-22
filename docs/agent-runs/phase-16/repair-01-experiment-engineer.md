# repair-01-experiment-engineer (Phase 16, session 2, step 9 repair round)

```text
STATUS: COMPLETE
OWNER: experiment-engineer
WORK ORDER: docs/agent-runs/phase-16/prompt.md (sha256 c75cc5f895faed13880a69ec285231c5498fd50924c7b875fe7e02cce1707dc8, checked with shasum -a 256), decisions 13 and 15; repair-01-experiment-engineer: P16-B-02, P16-B-04, P16-B-05, P16-C-01 (P16-C-02 untouched, a human decision; nothing built for P16-C-04, proposed later)
FILES CREATED:
  experiments/evaluation/fixtures/factory/happy/input/agent-runs/phase-01/human-gate.md
  experiments/evaluation/fixtures/factory/happy/input/agent-runs/phase-01/orchestrator-summary-s2.md
FILES MODIFIED (all under experiments/evaluation/, untracked):
  evaluate.py
  suite-v1.json
  fixtures/factory/happy/case.json
  fixtures/factory/happy/input/agent-runs/phase-01/orchestrator-summary.md
  fixtures/factory/happy/expected/events.csv, counts-by-phase.csv, counts-by-agent.csv, totals.csv, audit-sample.csv, audit-strata.csv
  fixtures/factory/malformed/expected/totals.csv
  fixtures/graph/happy/case.json
  fixtures/graph/happy/input/sample-a.json
  fixtures/graph/happy/expected/provenance.csv
  fixtures/reproduce/release-records/input/releases/r1.json, r2.json, r3.json
  fixtures/reproduce/release-records/expected/release-records.csv
COMMANDS RUN: shasum -a 256 (work order, evaluate.py, suite-v1.json); jq empty (7 JSON files, literal paths); grep -n/-c/-rnE (read-only); wc -l; ls; head -n / tail -n (reading); git status --short experiments. No python, bash script, sbt, or network command was run.
TEST RESULTS: not run (decision 13: the human runs the fixtures at E1). Every changed expected file was worked out by hand; the trace is below. runtime: untested
VALIDATION RESULTS: jq empty passed on all 7 changed JSON files; the suite pins equal the new evaluate.py sha256 (2 occurrences, 0 left of the old 07e84842…); no run of 12 digits is left under experiments/evaluation/fixtures (grep -rnE '[0-9]{12}' count 0); suite-v1.json still has 292 lines (only the content of existing lines changed, so docs/evaluation.md's "suite :N" references still point at the same lines)
ASSUMPTIONS: see "Design choices" below (how matching works, the step a terminal report carries, one to one versus many to one)
RISKS: (1) Phrase rows are matched by phase, event type and named agent, not by the text. If two different events of the same type and agent appear, one only in the gate and the other only in a summary, they are merged, which undercounts. The E11 audit checks classification, so it would not catch this. (2) A terminal report's step comes from its file name, and an empty step matches any step. So a terminal report without a step in its name absorbs every BLOCKED-ON row of that agent in that phase, which can undercount when one agent was blocked at two steps and only one report file survived. (3) P16-B-04 has no fixture case: --fixtures skips the suite checks by design (setup_suite returns at once for fixture runs), so the exit-3 path is checked by tracing the code and at E3 only.
MANUAL ACTION REQUIRED: the human re-hashes evaluate.py and the suite at E3 before signing: evaluate.py ba42ad4dba868ab1aa2367ddb9437f841d59eb702e3444cd8f5494416208ecae, suite-v1.json b4e44b299473dc7ec48ea3dbff1f2762bef2471021552ffe971a9ce22d46a3db
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: step 10 re-check (code-reviewer: evaluate.py factory, setup_suite and PROVENANCE_CHECKS; the factory, graph and release-records fixtures; the suite pins); documentation-engineer re-checks docs/evaluation.md against the INTERFACE HANDOFF below; the human runs E1
```

## Findings

| ID | Severity | Claim | Where (file:line) |
|---|---|---|---|
| P16-B-02 | major | RESOLVED. (a) Phrase events (manual intervention, hook block, ownership violation, human rewrite) now come only from a file's own list rows: list items and table body rows, never prose, headings, table headers or separator rows. (b) One source precedence per phase (gate, then summaries in file order, then runbook logs). A phrase row is marked `duplicate_of` the first counted row that is still unmatched and has the same phase, event type and agent in a higher-precedence file. Matching is one to one, and rows of the same file never match each other. (c) BLOCKED-ON rows are deduplicated by phase, step and agent (an empty step matches any step). They are matched first against terminal reports, which now carry a step taken from their file name, then against the counted rows earlier in precedence, many to one; the duplicates are marked `duplicate_of`. Rows that name no agent stay counted. (d) The happy fixture now has a gate and a summary repeating one hook block, and two summaries repeating one BLOCKED-ON row. | evaluate.py:958-964 (PHRASE_TYPES, LIST_ROW_RE, ROLE_RANK, PHRASE_RULE), :1003-1015 (agent_and_width), :1018-1029 (own_list_row), :1032-1044 (match_step), :1200-1207 (phrase scan limited to own list rows), :1208-1225 (terminal report step), :1262-1302 (dedup), :1381-1388 (inclusion rules); suite-v1.json:187 (method); fixtures/factory/happy/** |
| P16-B-04 | minor | RESOLVED. If the suite has no 64-hex `evaluationCode.sha256`, or it differs from the tool's own sha256, the run now exits 3 ("input differs from the signed suite: …"). It used to print WARN and keep computing. A match prints `RESULT evaluation code matches the suite`. | evaluate.py:447-455 |
| P16-B-05 | minor | RESOLVED. The fake commits are now `a1a1…`, `b2b2…` and `c3c3…` (40 hex characters, digit runs of at most 1). The expected CSV was updated to match; no other cell changed. | fixtures/reproduce/release-records/input/releases/r1.json:5, r2.json:5, r3.json:5; expected/release-records.csv:2-4 |
| P16-C-01 | major | RESOLVED. `schema_version` now accepts only `schemaVersion`, as a top-level attribute or as a record JSON field. The top-level `version` attribute, the conditional-write item version, no longer counts. sample-a now has 3 items: item 1 carries `version` and `schemaVersion`; items 2 and 3 carry only `version` and count as absent. Expected TableA rows recomputed. | evaluate.py:2245-2247 (PROVENANCE_CHECKS); fixtures/graph/happy/input/sample-a.json:3-5; expected/provenance.csv:2-9; suite-v1.json:105 (method) |

## Design choices (for the reviewer)

- **Precedence key** for both kinds of deduplication: (phase index, role rank gate 0 / summary 1 / log 2, file index, line, event ID). Event IDs are still assigned in file and line order, exactly as before.
- **Phrase rows, one to one:** a gate row absorbs at most one summary row. A second summary can repeat a row from the first summary only while that row is still unmatched.
- **BLOCKED-ON rows, many to one:** the work order's key is (phase, step, agent), so every row with a compatible key collapses into the first anchor. An equal non-empty step is preferred over a match through an empty step.
- **A terminal report's step** is the file-name tokens after the agent name, for example `agent-report-storage-D` gives `D`. It is empty when the name holds no further tokens, or when the agent came from the OWNER line.

## Hand computation of every changed expected file

### factory/happy (phase-00 inputs unchanged; E00001-E00007 unchanged)

phase-01 file order (sorted by name without `.md`): fi0 agent-report-rag, fi1 agent-report-storage, fi2 human-gate, fi3 orchestrator-summary, fi4 orchestrator-summary-s2.

- agent-report-rag:1 `STATUS: BLOCKED-ON`. Tokens [rag] give rag-engineer with width 1, so the step is "". This is **E00008**, a blocked terminal report.
- human-gate:1 is a heading and :2 is prose, so neither is a list row and nothing is counted. human-gate:3 is a `- ` row that matches the hook pattern ("hook blocked"; no "no" or "never") and names no agent: **E00009**, a hook block.
- orchestrator-summary step table, rows :5-:10 (Step/Who/Status):
  - :5 storage-engineer done gives **E00010**.
  - :6 rag-engineer is blocked: execution **E00011** and blocked row **E00012** (step 2).
  - :7 code-reviewer done gives **E00013**.
  - :8 "orchestrator triage" is skipped.
  - :9 mystery-bot goes to unclassified, unchanged.
  - :10 test-engineer is blocked: execution **E00014** and blocked row **E00015** (step 6).
- orchestrator-summary:12 is a summary line containing BLOCKED-ON; the first agent it names is rag-engineer and its step is "": **E00016**, a blocked row. :13 is a `- ` row matching the hook pattern with no agent: **E00017**, a hook block.
- orchestrator-summary-s2:5 test-engineer is blocked: execution **E00018** (attempt 1 in its own table) and blocked row **E00019** (step 6).

Deduplication:

- Executions: E00018 has the same key as E00014 (phase-01, 6, test-engineer, 1), so it is a duplicate of E00014.
- Blocked rows, in precedence order E00012, E00015, E00016, E00019:
  - E00012 (rag, step 2): the only terminal is E00008, whose step "" matches, so it is a duplicate of E00008.
  - E00015 (test-engineer, 6): there is no terminal and no anchor, so it is counted and becomes the anchor.
  - E00016 (rag, step ""): it matches terminal E00008, so it is a duplicate of E00008. **Before the repair it was counted.**
  - E00019 (test-engineer, 6): it matches anchor E00015, so it is a duplicate of E00015.
- Phrase rows: E00002 and E00003 (the phase-00 gate) have keys of their own. E00009 (the gate) goes into the pool. E00017 (summary, a different file, key phase-01/hook-block/"") matches E00009, so it is a duplicate of E00009.

The counted events are E00001-06, 08, 09, 10, 11, 13, 14 and 15: 13 events, at most the sample size of 20, so the audit sample holds all of them.

Results:

- first pass: E00010 and E00013 are yes; E00001 is not (the build repair and the major finding); E00011 and E00014 are blocked.
- counts-by-phase phase-01: executions 4, done 2, first pass 2, terminal 1, rows 4 (E00012, 15, 16, 19), rows not in a terminal 1 (E00015), total 1+1 = 2, hook 1, unclassified 1. phase-00 is unchanged.
- counts-by-agent: a new row `test-engineer,1,0,0,,0,0,0,0,0,0`; the other rows are unchanged.
- totals:
  - executions 5;
  - first pass 2/3 = 0.666667;
  - blocked rows not in a terminal 1;
  - measurable total 2 over 1 phase;
  - hook_blocks 2 (E00002, E00009);
  - manual 1;
  - unclassified 2.
- audit-strata: execution 5, hook-block 2, the other types 1 each, finding 2.
- unclassified.csv is unchanged: mystery-bot is still on line 9.

### factory/malformed

Only `totals.csv` changes, and only the inclusion_rule text of 5 rows (blocked_on_rows_not_in_terminal, manual_interventions, hook_blocks, ownership_violations_caught, human_rewrites). All values stay 0.

### graph/happy provenance.csv, TableA (n = 3)

| Check | Items present | Count | Proportion |
|---|---|---|---|
| run_id | 1, 2, 3 | 3/3 | 1 |
| snapshot | 1 | 1/3 | 0.333333 |
| dataset | none | 0/3 | 0 |
| source_record_ids | none | 0/3 | 0 |
| policy_version | 1 | 1/3 | 0.333333 |
| schema_version | 1 only (`schemaVersion` N "2"); items 2 and 3 carry only `version` and count as absent | 1/3 | 0.333333 |
| item_hash | 1 (item 2's "" is not present; item 3 has none) | 1/3 | 0.333333 |
| content_hash | none | 0/3 | 0 |

Every TableA value is a top-level value (present_in_record 0), and records_unparseable is 0. The TableB rows are unchanged; that table has no top-level `version` attribute. The proportion 1/3 is written as round(0.3333…, 6) = 0.333333.

### reproduce/release-records

Only the commit cells of rows 2-4 change, to the new input values. The tool copies `commit` verbatim (evaluate.py `release_records`, the `data.get("commit", "")` cell); no other column depends on it.

## E1 command list

Unchanged. The same 8 `--fixtures` lines, with the same case counts (retrieval 3, performance 3, factory 3, reproduce 6, ratings-sheet 3, answers 3, graph 3, er 4). No case was added or removed; the factory and graph happy cases changed their contents only.

## INTERFACE HANDOFF

- **Files changed:** as listed above. No file outside experiments/** was touched.
- **Output columns:** no column was added or removed in any output file. `events.csv` already had `duplicate_of`; it is now also filled for (a) phrase events that repeat a higher-precedence row and (b) BLOCKED-ON rows that repeat an earlier row (before, only findings, executions and terminal-matched rows were filled).
- **Changed values and meaning:**
  - factory `events.csv`:
    - blocked-terminal rows now carry the report's step in the `step` column (from the file name; empty if none);
    - phrase events now come only from list rows, so prose lines no longer produce events and event IDs can shift in the real run;
    - more rows have `counted` = no.
  - factory `totals.csv` `inclusion_rule` text of 5 metrics:
    - blocked_on_rows_not_in_terminal is now "BLOCKED-ON rows of summaries and logs deduplicated by phase step and agent (empty step matches any): rows matching a terminal report or an earlier row (summaries then runbook logs) are duplicate_of and not counted; rows naming no agent counted";
    - manual_interventions, hook_blocks, ownership_violations_caught and human_rewrites now read "gate or summary list rows …" plus "; list items and table body rows only (prose not counted); a row matching an unmatched row of the same phase type and agent in a higher-precedence file (gate then summaries) is duplicate_of and not counted".
  - factory `counts-by-phase.csv`: `blocked_on_rows` still counts every row; `blocked_on_rows_not_in_terminal` and `blocked_on_total` now also exclude row-to-row duplicates; `manual_interventions` … `human_rewrites` now exclude prose and duplicates.
  - graph `provenance.csv`: the `schema_version` check counts only `schemaVersion` (top-level attribute or record field); the top-level `version` attribute is ignored.
- **Exit codes:** a real run exits 3 when the suite's `evaluationCode.sha256` is missing or differs from the tool's sha256. It used to warn.
- **Version:** TOOL_VERSION is `evaluate-v1-r1` (was `evaluate-v1-x2`). Suite line 14 (evaluationCode.version) and line 41 (generator version) were updated to match.
- **Pins:**
  - suite-v1.json:15 and :42 are now `ba42ad4dba868ab1aa2367ddb9437f841d59eb702e3444cd8f5494416208ecae` (shasum -a 256 of evaluate.py);
  - no other pinned input changed: the factory tree digest covers docs/agent-runs only, and the fixtures are not pinned in the suite;
  - the suite's own sha256 is now b4e44b299473dc7ec48ea3dbff1f2762bef2471021552ffe971a9ce22d46a3db.
- **For documentation-engineer to re-check:**
  - docs/evaluation.md:162 ("summary or runbook rows not matched to a terminal report") should mention deduplication by phase, step and agent, against terminal reports and earlier rows;
  - :164 ("come from the gate's or summary's own lists") is still right, but could add "list rows only, never prose; repeats across gate and summaries counted once";
  - :107 ("schema version") could say "the `schemaVersion` field only, not the conditional-write `version` attribute".
  - Suite line numbers did not move (292 lines).
- **Thread-safety:** not applicable (a single-threaded command-line tool).
- **Limits:** see RISKS. Python 3 standard library only; no new import.

## Proposed later

- A fixture mode for the real-run suite checks (pinned inputs and evaluationCode), so the exit-3 paths of P16-B-04 and check_pin have their own fixture case. Not built, because the work order's fixture rule covers only the subcommands.
- P16-C-04: a ratings summarizer (the dblp.org rate with its Wilson interval, plus the n and distribution of the ratings). The work order routes it to proposed later.

## VALIDATION EVIDENCE

```text
shasum -a 256 docs/agent-runs/phase-16/prompt.md                 -> c75cc5f895faed13880a69ec285231c5498fd50924c7b875fe7e02cce1707dc8
shasum -a 256 experiments/evaluation/evaluate.py (before)        -> 07e84842779c18fb779ae5fa51c39a8de792d509f15e913e5360778c8f1f2900
shasum -a 256 experiments/evaluation/evaluate.py (after)         -> ba42ad4dba868ab1aa2367ddb9437f841d59eb702e3444cd8f5494416208ecae
shasum -a 256 experiments/evaluation/suite-v1.json (after)       -> b4e44b299473dc7ec48ea3dbff1f2762bef2471021552ffe971a9ce22d46a3db
jq empty suite-v1.json, graph/happy/input/sample-a.json, graph/happy/case.json, factory/happy/case.json, release-records r1.json r2.json r3.json -> all 7 ok
grep -c ba42ad4d…08ecae suite-v1.json -> 2 ; grep -c 07e84842 suite-v1.json -> 0
grep -rnE '[0-9]{12}' experiments/evaluation/fixtures | wc -l -> 0
wc -l suite-v1.json -> 292 (unchanged)
fixtures: not run (E1, human); runtime: untested
files changed after these runs: none
```
