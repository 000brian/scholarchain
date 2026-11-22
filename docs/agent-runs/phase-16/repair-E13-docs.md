# repair-E13-docs (documentation-engineer, Phase 16 session 3)

## Changes per finding (docs/evaluation.md, line numbers after the edit, 848 lines)

| Finding | Lines | Change | Frozen source (kind) |
|---|---|---|---|
| P16-L-17 | 148 (4.4), 219 (section 6), 611-612 (10.4 join), 621 (10.4 interpretation caveat), 809 (10.8 item 1) | Section 6 states that R05's text shows "claim-1: conflicting" (sheet.csv:76) and "Partially supported" on claim-2 and claim-6 (sheet.csv:68, :72); these are the only non-verified statuses in the rated set (per-answer.csv row 43; the other five all verified); **every non-verified status in the rated set was visible in the answer texts**; the rater was blind to the sheet's status fields (none), not to the texts; input to spec gate 1 and a caveat on the 10.4 join. Join bullet and interpretation carry the caveat. | ratings/…-c89f/sheet.csv:68, :72, :76; answers/…-8128/per-answer.csv:43 (a, c) |
| P16-L-18 | 621-622 (10.4 interpretation) | "Correct" is attributed to the rater (sheet.csv:86 note, kind c). New descriptive negative result (kind a): the system's abstention flag was not set: `"abstained": false` (p15-demo-n.json:3), per-answer row 43 `abstained` = no, demo abstention 0 of 6 (rates.csv:71); the answer leads with 6 claims and says "Not supported" only in the notes (sheet.csv:82). Abstention recall stays `not measurable` (no benchmark ID; benchmark-v1 has no false-premise category). The researcher's name is not quoted. | answers/p15-demo/p15-demo-n.json:3; per-answer.csv:43; rates.csv:71; sheet.csv:82, :86; benchmark-v1.json categories (grep: 15 categories, none false-premise) |
| P16-L-19 | 795 (10.7 record check) | States `verified` = yes does not mean image digests were verified; `digest_checks` reads "verified 0; not-applicable 4; no running task 0; other 0" on rows 2, 3, 4, 8 and "verified 3; not-applicable 0; no running task 1; other 0" on rows 5, 6, 7. | repro/…-37b5/release-records.csv:2-8 (a) |
| P16-L-20 | 50, 51 (section 1), 130 (4.3), 153 (4.4), 205, 208 (section 5), 472 + 480 (10.3), 542-550 (attempt-06 Wilson rows split into their own table, header "Wilson 95 % (EXPLORATORY: fixed hand-written benchmark, n = 30)"), 560 + 573 (demo table header "Tool's interval (EXPLORATORY: hand-saved demo set, non-random, n = 6)"; bootstrap cells labelled "bootstrap") | Section 5 Wilson row now names attempt-06's three abstention rows and the demo abstention row; bootstrap row notes it applies in practice to the demo answers only (rates.csv: bootstrap rows 50-57, 67-70, all `demo`). The human's plain line is added next to every EXPLORATORY interval: 8 occurrences; group 3 uses "The questions are not a random sample, …", group 4 uses the verbatim "the answers are not a random sample, so the interval only illustrates variability and is not a confidence interval for the system." No interval value changed or removed. | answers/…-8128/rates.csv:20-22, :50-71 |
| P16-L-21 | 234 (T8 source rows), 239 (f1), 240 (f2), 554 (10.4 D-query caveat) | T8 adds (f1) storage port drops the error class and (f2) rag's resolver silently swallows graph failures (about 14 Neptune server errors in D-query, no abstained and no other-5xx answer; degraded to identity outcome "unknown"; failed calls not recorded), stated as a threat to "abstention 0 of 40 rows"; the 10.4 D-query paragraph points to T8. No instance class quoted. | docs/agent-runs/phase-16/agent-report-storage-D.md:82-83 (step D reading; code facts) |
| P16-L-22 | 393 (10.1 interpretation) | Sentence limited to precision, recall and F1; coverage's zero-event bound 91.8 to 93.4 lies inside its bootstrap 90.1 to 94.5 (values already in the 10.1 table). | er/…-91dd/weighted-intervals.csv:2-5 (b) |

Also: line 3 status now says the E13 review returned APPROVE with six minors, repaired in one pass; 10.9 (lines 838-845) lists L-17 to L-22 and where each was repaired. No new number except those taken from the cited frozen files above.

```text
STATUS: COMPLETE
OWNER: documentation-engineer
WORK ORDER: docs/agent-runs/phase-16/prompt.md (sha256 c75cc5f895faed13880a69ec285231c5498fd50924c7b875fe7e02cce1707dc8, checked with shasum), repair-E13-docs, findings P16-L-17 to P16-L-22 (one pass approved by the human); HEAD ecc13052b09e0b0396f47894e8858c6029632886
FILES CREATED: none in the repository (this report only, in the scratchpad)
FILES MODIFIED: docs/evaluation.md (826 -> 848 lines; sha256 50b5ab9d393746df11d94900e5dc4cbb63ef0e0695f30267ec374e06e8132160)
COMMANDS RUN: git rev-parse HEAD; git status --short; git diff --stat; shasum -a 256 (prompt.md, docs/evaluation.md); wc -l; grep (-n, -c, -o, -E) and cut and head on frozen files and docs/evaluation.md; ls (link check); one `sort | uniq -c` over grep output of benchmark-v1.json categories (read-only). One command was blocked by the guard and did not run: a stray `sed -n '' /dev/null` prefix in a grep command (my mistake; the work order forbids sed); I reran it without sed. No cat, find, cd outside cwd-prefixing, tr, network, sbt, script or python.
TEST RESULTS: not applicable (documentation only; no relevant source changed)
VALIDATION RESULTS: see VALIDATION EVIDENCE; 3 new link targets resolve; frozen files unchanged (git status shows only docs/evaluation.md modified among tracked files)
ASSUMPTIONS: "about 14 Neptune server errors" and the (f1)/(f2) code facts are cited as step D's reading (storage-D :82-83), not re-derived; the plain line was also placed in the method summaries (sections 1, 4.3, 4.4, 5) because they describe the same EXPLORATORY intervals; R05's researcher name is not quoted, consistent with the rest of the document
RISKS: the plain line now appears 8 times, which the human may find repetitive; section 9 (named before the freeze) was not edited, its "missed abstentions" item already covers L-18; whether rag's (f2) was changed in Phase 16 is not stated (not in the cited evidence)
MANUAL ACTION REQUIRED: copy this report with cp -n; optional code-reviewer re-check of the six repairs; the human commits docs/evaluation.md
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: orchestrator check of the repairs (or code-reviewer re-check), then E14 docs/emulator.md and E15 gate
```

## Replayed commands and outcomes

None replayed (documentation repair; no repository command documented anew).

## Link-check results

New link targets checked with `ls` relative to docs/: `../experiments/evaluation/raw/ratings/20261122T173416Z-7e551c8003c3-c89f/sheet.csv`, `../experiments/evaluation/raw/answers/p15-demo/p15-demo-n.json`, `agent-runs/phase-16/agent-report-storage-D.md`: all 3 exist.

## Unverified claims

- "about 14 Neptune server errors" in D-query and the "no other-5xx answer" statement: step D's reading (storage-D :83), not recomputed by me.

VALIDATION EVIDENCE
- git rev-parse HEAD -> ecc13052b09e0b0396f47894e8858c6029632886
- shasum -a 256 docs/agent-runs/phase-16/prompt.md -> c75cc5f895faed13880a69ec285231c5498fd50924c7b875fe7e02cce1707dc8
- grep -cE '[0-9]{12}' docs/evaluation.md -> 0
- grep -cE 'amazonaws|arn:aws|s3://|AKIA|ASIA|sk-ant|db\.t4g|db\.r[0-9]|j-[A-Z0-9]{8,}' docs/evaluation.md -> 0
- grep -cE 'Wanli|Ouyang|Nobel' docs/evaluation.md -> 0
- grep -c 'RESULTS PENDING' docs/evaluation.md -> 0
- grep -c 'not a confidence interval for the system' docs/evaluation.md -> 8 (lines 50, 51, 130, 153, 205, 480, 550, 573)
- ls of 3 new link targets -> all exist
- git status --short -> ` M docs/evaluation.md` plus pre-existing untracked `experiments/.DS_Store`, `experiments/emulator/`, `experiments/evaluation/**/.DS_Store` (not mine)
- wc -l docs/evaluation.md -> 848
- shasum -a 256 docs/evaluation.md -> 50b5ab9d393746df11d94900e5dc4cbb63ef0e0695f30267ec374e06e8132160
files changed after these runs: none
