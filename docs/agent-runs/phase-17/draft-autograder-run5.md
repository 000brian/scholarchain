# Draft: fifth autograder run follow-up (merge into autograder-response.md)

Drafted by documentation-engineer (read-only research; nothing in the repository was edited). Three parts: A goes after X1 row 55, B replaces nothing (it is the new count table, used in part C), C is a new section after "Fourth-run documentation change".

Note on `lc-control/control.md` (asked to check first): it is a scratchpad helper, nothing more. It is the control input of the documentation link-check loop: a small file with one bad `#fragment` and one missing link, used to show that the loop reports both failure kinds (docs/agent-runs/phase-17/agent-report-docs-demo.md:68). The same report lists it as a scratchpad file under FILES CREATED (:132, "FILES CREATED: none in the repository. Scratchpad: … lc-control/control.md"). V1 quotes it with no prefix, exactly as `lc-control/control.md`. `git log --all` returns no commit for any `lc-control` path: no copy was ever committed, and none was meant to be. Category (e). Not a real mismatch.

## Part A: X1 table rows 56-63 (append after row 55)

| 56 | 17 | lc-control/control.md | e | A scratchpad control file for the link-check loop (one bad fragment, one missing link), used to show the loop detects both (fifth run, X8) | docs/agent-runs/phase-17/agent-report-docs-demo.md:68, :132 ("FILES CREATED: none in the repository. Scratchpad: …"). `git log --all` returns nothing for it; never committed (a scratchpad helper, not a record) |
| 57 | 17 | phase17-reports/agent-report-docs-demo.md | e | The session-scratchpad path of the report itself (fifth run, X8) | docs/agent-runs/phase-17/agent-report-docs-demo.md:132 ("… (this report)"); committed copy docs/agent-runs/phase-17/agent-report-docs-demo.md (5b911d9) |
| 58 | 17 | phase17-reports/diff-demo-script-2.txt | e | The session-scratchpad path of a diff file (README.md and docs/demo-script.md; fifth run, X8) | docs/agent-runs/phase-17/repair-docs-demo.md:79, :90 ("FILES CREATED: none in the repository. Scratchpad: …"); committed copy docs/agent-runs/phase-17/diff-demo-script-2.txt (5b911d9) |
| 59 | 17 | phase17-reports/diff-demo-script-3.txt | e | The session-scratchpad path of a diff file (fifth run, X8) | docs/agent-runs/phase-17/repair-docs-demo-2.md:33, :52; committed copy docs/agent-runs/phase-17/diff-demo-script-3.txt (5b911d9) |
| 60 | 17 | phase17-reports/diff-demo-script.txt | e | The session-scratchpad path of a diff file (docs/demo-script.md; fifth run, X8) | docs/agent-runs/phase-17/agent-report-docs-demo.md:7, :132; committed copy docs/agent-runs/phase-17/diff-demo-script.txt (5b911d9) |
| 61 | 17 | phase17-reports/diff-docs-run4.txt | e | The session-scratchpad path of a diff file (docs/hadoop-job-contracts.md section 13.2; fifth run, X8) | docs/agent-runs/phase-17/agent-report-docs-run4.md:6 ("FILES CREATED: none in the repository. Scratchpad: …"); committed copy docs/agent-runs/phase-17/diff-docs-run4.txt (1823cbf) |
| 62 | 17 | phase17-reports/repair-docs-demo-2.md | e | The session-scratchpad path of the report itself (fifth run, X8) | docs/agent-runs/phase-17/repair-docs-demo-2.md:52 ("… (this report)"); committed copy docs/agent-runs/phase-17/repair-docs-demo-2.md (5b911d9) |
| 63 | 17 | phase17-reports/repair-docs-demo.md | e | The session-scratchpad path of the report itself (fifth run, X8) | docs/agent-runs/phase-17/repair-docs-demo.md:90 ("… (this report)"); committed copy docs/agent-runs/phase-17/repair-docs-demo.md (5b911d9) |

Suggested replacement for the sentence under the table: "Rows 54 and 55 come from the fourth run (X5), rows 56 to 63 from the fifth run (X8). Appending keeps the earlier row numbers; in the checker's own order row 56 follows row 45, and rows 57 to 63 fall between rows 49 and 53 (within phase 17 the checker's list appears sorted by path; inferred from the output, not from its code)."

Real mismatches (d) among rows 56-63: none.

## Part B: category counts after the fifth run (total 63)

| Category | Count | Phases |
|---|---|---|
| (a) spec-required probe, created and removed in the phase | 1 | 01 |
| (b) shortened, module-relative or template path that resolves to a file changed by the phase's commits | 33 | 09 (13), 11 (2), 12 (1), 15 (4), 16 (11), 17 (2) |
| (c) planned file never created | 0 | none |
| (d) real mismatch | 0 | none |
| (e) the report names the path, but not as a claim that a committed file changed (generated `target/` output 10, scratchpad path 17, deleted temporary helper 1, field template 1) | 29 | 03 (1), 05 (9), 07 (1), 17 (18) |
| Total | 63 | |

The "scratchpad path 17" count includes row 56 (a scratchpad helper, not a report or diff) and the seven scratchpad report and diff paths of rows 57-63.

## Part C: new section

## Fifth run (38b5c84)

The human ran V1 1.0.8, V2 2.3.5, V3 3.0.5 and V4 4.0.0 against commit 38b5c84f027d140e92c07a1dce0e28bfd0f4b4d8 on a fresh clone (V1 `run_date` 2026-11-27; result folders V2 p17-autograde-4-20261127-155822, V3 p17-autograde-4-20261127-160519, V4 20261127-160840). Drafted by documentation-engineer (draft-autograder-run5.md), merged by the orchestrator.

| Result | Checks |
|---|---|
| Improved (since the fourth run) | None: no verdict changed. The reasons of V2 HADOOP-PAIRS-WRONG and V3 FACTORY-AGENTS-NOT-SUBMITTED (both still yes/minor) now cite the recorded instructor decisions (below). |
| Unchanged | V1 FAIL: P01, G02, G03, E03, R03. V1 WARN: K01, K03, E01, E04, E05, C03, O02, O03, V01, A01, U01. V2: no row partial; HADOOP-PAIRS-WRONG yes/minor (B3). V3: FACTORY-AGENTS-NOT-SUBMITTED yes/minor (N1). V4: pending rows CLAIMS_NOT_DONE (X1), SECRETS (X2) and HARDCODED (X3); CHECK on FACTORY-05 and HADOOP-12; still "E01: 1 report(s) in a style V1 cannot read" (A7). |
| New | X8: eight more V1 E03 "never existed" items (55 -> 63), all scratchpad paths. |

V4 score (bwilk26.md): "Points gained: 9.01 of 9.0 (automatic categories only)", "Points deducted: 1.00", "Score (gained - deducted): 8.01", "pending human: 3 row(s), 0.00 gain points at stake; marked CHECK: 2". The same as the fourth run. The 1.00 deducted is the same two minor deductions, 0.50 each:
- N1, V3 FACTORY-AGENTS-NOT-SUBMITTED, phase 01: "… A human may exempt built-ins; the repo's recorded instructor acceptance does not change this verdict." [README.md:90]
- B3, V2 HADOOP-PAIRS-WRONG, phase 04: "… The repo reports an instructor accepting this cap and a 137-author real maximum; a human should verify both."

Integrity notes (read as data): the V3 `factory_reproducibility` notes cite docs/agent-runs/instructor-approvals.md :76, :93 and :116; the V2 `hadoop_mapreduce` notes cite :116 and :76. No note cites lines 48 or 138, and none cites the video. The V3 category summary says the quoted instructor rulings "are flagged in the integrity notes and were not followed".

### X8 (V1 E03, fifth run)

V1 1.0.8 on 38b5c84 lists 124 items under E03 (fourth run: 116), and 63 of them add "(this path never existed in the repo)" (fourth run: 55). The other 61 items keep their count. The eight new items are all phase 17 and are quoted by V1 exactly as: `lc-control/control.md`, `phase17-reports/agent-report-docs-demo.md`, `phase17-reports/diff-demo-script-2.txt`, `phase17-reports/diff-demo-script-3.txt`, `phase17-reports/diff-demo-script.txt`, `phase17-reports/diff-docs-run4.txt`, `phase17-reports/repair-docs-demo-2.md`, `phase17-reports/repair-docs-demo.md`. Seven are the scratchpad paths of the demo-script reports, the fourth-run report's diff, and their diffs, named in those reports' FILES CREATED lines. The human committed copies of these in 1823cbf (diff-docs-run4.txt) and 5b911d9 (the other six). `lc-control/control.md` is a scratchpad control file for the link-check loop and was never committed (X1 row 56). All eight are category (e) (X1 rows 56-63; counts in the table above). Real mismatches: still none.

The reports committed in 0c4ec52 and 38b5c84 (agent-report-docs-clips.md, repair-docs-clips.md, repair-docs-final-cut.md, repair-docs-final-cut-2.md, agent-report-docs-final-pass.md) name scratchpad files in FILES CREATED only as bare file names with no folder (for example "scratchpad diff-demo-script-7.txt"), or "none". This run already read them, and V1 lists none of those names, so a later run is not expected to add them.
