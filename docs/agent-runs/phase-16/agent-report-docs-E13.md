# agent-report-docs-E13 (documentation-engineer, Phase 16 session 3, step E13)

STATUS: COMPLETE
OWNER: documentation-engineer
WORK ORDER: docs/agent-runs/phase-16/prompt.md (sha256 c75cc5f895faed13880a69ec285231c5498fd50924c7b875fe7e02cce1707dc8, checked with shasum), decisions 3, 14.1, 17 (E13), 23 and the verbatim spec gate; HEAD ecc13052b09e0b0396f47894e8858c6029632886 (E12 freeze, checked with git rev-parse)
FILES CREATED: none in the repository (this report only, in the scratchpad)
FILES MODIFIED: docs/evaluation.md (sha256 after edit eb41b7d200cc076b58eacd3631a1eb55419b228372e02f367850ff02448b7b81, 826 lines)
COMMANDS RUN: shasum -a 256 (prompt.md, frozen result files of both attempts, suite-v1.json, evaluate.py, docs/evaluation.md); git rev-parse HEAD; git status --short; git ls-files experiments/evaluation/raw; git log --diff-filter=A (5 files); git diff --stat 79b5583 HEAD -- check.sh inputs (empty); git diff --stat 7e551c8 HEAD -- modules/hadoop modules/spark modules/core (empty); git log 7e551c8..HEAD -- suite, evaluate.py, rubric (empty); head, tail -n, wc -l, cut, grep (-c, -n, -o, -E) on frozen files; ls -d per link; Read tool for runbook-log.md, approvals.md, prompt.md, the old docs/evaluation.md, local-run-report.json, sheet.csv, PROVENANCE.txt, review reports, storage-D report, results-summary.md :425-459. Off-list commands, all read-only, recorded honestly: one `cat` (as an `||` fallback that did run, on repro/e5-pipeline-…/run.txt), one `tr -d` inside a link-extraction pipe whose output went to /dev/null, and `rev` in the link-check pipes. No network, no sbt, no script, no python.
TEST RESULTS: not applicable (documentation only; no source file changed, so the stop hook needs no marker)
VALIDATION RESULTS: link check 96 of 96 relative links resolve (ls -d, relative to docs/); `RESULTS PENDING` occurrences 0; twelve-digit runs 0; amazonaws|arn:aws|s3://|AKIA|ASIA|sk-ant 0; every results subsection 10.1-10.7 has Measured facts and Interpretation subsections; superseded attempt-1 result files byte-identical to attempt 2 (except factory audit-sample.csv, which the human filled only in d311); suite-v1.json and evaluate.py still equal the signed pins.
ASSUMPTIONS:
- The demo answers' bootstrap and Wilson intervals (written by the tool under the signed suite :170 rule) are reported, but labelled as describing resampling of six hand-saved answers only, not a population estimate (T19). Likewise attempt-06's Wilson rows are reported as allowed by review note N1 and labelled exploratory (fixed benchmark). I did not drop these values ("never round away a value"); the work order's "no interval on non-random data" is honored by stating they are not inference intervals. The human or code-reviewer may prefer to remove them from the tables.
- The audit's "1" in both columns is recorded as yes, per the work order and runbook-log :47.
- Phase 15 limitation wording in T11 follows results-summary.md :435-452; deviation 3 (A/B different build) is from :426.
- runbook-log statements (who ran what, exit codes, the Phase 15 take 3 equality for the Hadoop replay, the orchestrator's opening of the 8 intended differences) are cited as the orchestrator's records, not re-verified by me except where noted.
RISKS:
- Interval policy for the demo source: the signed rule allows the tool's intervals; the work order line "no interval on non-random data" could be read to forbid showing them at all. Flagged for code-reviewer and the human.
- The R05 answer text shows "claim-1: conflicting", so rater blindness holds for the sheet's fields, not fully for the texts (stated in section 6 and in 10.4). Relevant to spec gate 1 at E15.
- 10.6 "BLOCKED-ON total, measurable phases only" reads 5/5/5 in totals.csv; I explained it as 5 events over 5 measurable phases, which the per-phase file supports (2+1+0+1+1, computed by hand). The file's numerator/denominator labels are terse.
- Section 3 states no suite-v2.json exists; true at HEAD ecc1305. If any evaluation code changes after E13, that line must be revisited.
MANUAL ACTION REQUIRED: copy this report with the rsync line; code-reviewer reviews docs/evaluation.md against the frozen files (E13 review, review-report-evaluation.md); the human commits docs/evaluation.md after the review.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: E13 review by code-reviewer (independent review for spec gate 1, plus the four gate items), then E15.

## Fixes made (each finding)

| Finding | Where in docs/evaluation.md | What changed |
|---|---|---|
| P16-L-09 | section 1 table group 4 row; section 4.4 sources (clip 01 collapsed, last attempt kept; demo answers not collapsed, descriptive when repeating a question); section 4.4 variability (suite :170 rule; 1 answer with claims descriptive; denominator 1 no interval; old clip 01 sentence removed); section 5 table (`wilson` at evaluate.py:201, "over independent answers only", new descriptive row) | interval rules now match suite :170 and review-report-P16-L-02.md item 7 and round 2 |
| P16-C-03 | T11 | adds F limitations (user-visible impact not measured, FECD-7), D-read attempt 2 (FECD-4), C FECD-5, FECD-1/3/8, fixtures, billing split; now the full list of results-summary.md :435-452 |
| P16-C-04 | sections 4.4, 5, 8, 10.4 | states that no tool summarizes ratings; ratings read directly from sheet.csv; summaries labelled "computed by hand", no interval |
| P16-C-05 | T8 | "No retry was attempted. Whether a retry would have recovered is not decidable." with storage-D :77, :87-90 |
| P16-K-03 | every former "S/" reference; Sources section | all replaced by links to docs/agent-runs/phase-16/ (repair-01-experiment-engineer.md included); the copy list is removed |
| L2 status | 10.9 | L-01 PARTIAL then L-03..L-06 resolved (fail-closed rule); L-07, L-08 resolved; L-10..L-13 open, decided at the human gate; L-14, L-15 Phase 17; L-16 resolved; every fix "fixed in code, tested offline, not validated live" |

## Negative results kept (where)

vector-only retrieval (10.3); E2 attempt 1 failure P16-L-16 and its repair (10.7); D overload and FECD-1 incl. 429s, 502s, 503s (10.5, T8); failed attempts A-Hadoop 1, D-read 2, D9a (10.5); superseded E4/E6/E9 attempt 1 (10.9); unreliable weighted ER metrics and zero-event bounds, probabilistic path, llm path (10.1); provenance gaps in cache tables (10.2); partially supported / conflicting claims, planted-error miss (10.4); low ratings R04, R05 (10.4); factory findings, repairs, BLOCKED-ON, unclassified (10.6); deployment not independently verified (10.7); not-measurable items (section 8); open L2 findings (10.9).

## Number -> file -> kind

RAW = experiments/evaluation/raw/. Kinds: a internal consistency, b independent ground truth, c human judgment, m method parameter or dataset size, s session fact (runbook). "hand" = computed by hand from the named file.

| Number(s) in the document | File | Kind |
|---|---|---|
| suite sha 0f7f7d33…9970; evaluate.py 8e7f3e74…96b3; suite :15, :41-42 | docs/agent-runs/phase-16/approvals.md:3; suite-v1.json; shasum | m |
| freeze ecc1305…, E3 commit 7e551c8…, 19 inputs matched | git log; runbook-log :32 | s |
| 300 / 112 / 135 / 53 gold labels; 30 questions; 11 questions, 100 labels, 19 not comparable; 40 explain rows; 4 RunLedger items; 25-item samples; 145 and 59 files; 254 .md; 7 records; 43 tests | suite-v1.json :27, :47, :140, :141, :184, :218; source files (unchanged from W1 draft) | m |
| seeds 20261114, 20261122; 1,000 and 2,000 resamples; z 1.959964 at evaluate.py:201; 0.240 USD/h (0.192 + 0.048) | suite :82, :119, :127, :138, :159, :170; evaluate.py:201 | m |
| confusion matrix 93,1,4 / 1,49,2 / 12,59,38 / 6,26,9; weighted 11155611.5, 20.0, 1514.0 | RAW/er/…-91dd/confusion.csv | b |
| 93/94 0.989362, 93/112 0.830357, 186/206 0.902913, 144/247 0.582996, 109/300 0.363333; printed 98.9, 83.0, 90.3, 58.3, 36.3 | RAW/er/…-91dd/recomputed.csv | b (value), a (equality) |
| all 13 table rows (units, %, Wilson bounds, flags) | RAW/er/…-91dd/table.csv | b |
| bootstrap and zero-event bounds (100/100, 82.9/100; 91.6/98.4, 84.9/95.3; 95.6/99.2, 83.9/97.6; 90.1/94.5, 91.8/93.4; 4.3/4.3, 4.3/5.9) | RAW/er/…-91dd/weighted-intervals.csv | b |
| 11 of 11 checks; gold sha fc39c15f… | RAW/er/…-91dd/checks.csv | a |
| code version phase05-1279c37-02-dirty; ER run 9f96… | RAW/er/…-91dd/notes.csv | m |
| 13 table rows; 5 of 5 equal | runbook-log :35 | s |
| Total 43, Failed 0, Passed 43; 15 and 28 tests | RAW/graph/e5-sbt-…/spark-suites.log :40, :76, :77; test-log.csv | b (hand-worked tests) / a |
| 15/15/0/0, 28/22/4/2, 43/37/4/2 | RAW/graph/…-a0dc/suite-summary.csv | b/a (classification by spark-engineer) |
| coverage table (12/12, 11/11, 8/8, 3/2/1, 3/3, 2/2, 6/6, 11/9/2, 4/4, 4/4, 11/8/1/2) | RAW/graph/…-a0dc/suite-coverage.csv | b/a |
| sha ad70f640…, 65 rows, run 0f02… | RAW/graph/…-a0dc/spark-files.csv | a |
| 44 checks; 4 zero, 39 equal, 1 difference | RAW/graph/…-a0dc/invariants.csv (counts: hand, grep -c) | a |
| 200484; 155978; 1612421, 104067, 248445, 569486, 826665; 33768, 58729, 94166, 116301; 64; 758328, 758314, 14 | RAW/graph/…-a0dc/invariants.csv | a |
| provenance table (25/25 … 0/4; 19/25 0.76); unparseable 0 | RAW/graph/…-a0dc/provenance.csv | a |
| graph-only/vector-only/fused recall@1/5/10/30, MRR and bootstrap bounds (all 15 cells) | RAW/retrieval/…-1864/summary.csv | a |
| unjudged 0/80, 179/180 0.994444, 139/220 0.631818 | RAW/retrieval/…-1864/summary.csv | a |
| vector-only empty 5 of 11; judged 1 (q25, 0.035714); graph-only empty 2 of 11; by-design-weak 9 and 2; fused q25 judged 1, q26 0 | RAW/retrieval/…-1864/per-question.csv (hand, grep -c) | a |
| benchmark sha b58c5e1a…; 15 equal checks | RAW/retrieval/…-1864/checks.csv | a |
| sources table (30/30, 40/19, 1/1, 6/6; versions, prompt sets, graph runs) | RAW/answers/…-8128/sources.csv | m |
| attempt-06 111/136 0.816176; 19/136 0.139706; 5/136 0.036765; 1/136 0.007353; 110/110; 4/4; 85/86 0.988372; 30/30, 2/2, 3/3, 28/28, 22/23 0.956522; 3/30 0.1 [0.0346, 0.256211]; 3/3 [0.438503, 1]; 0/27 [0, 0.124555] | RAW/answers/…-8128/rates.csv | a |
| explain 0/40 (abstention, false abstention); clip 01 2/2, 0/2, 0/1 | RAW/answers/…-8128/rates.csv | a |
| demo 28/31 0.903226 [0.71875, 1]; 2/31 0.064516 [0, 0.1875]; 1/31 0.032258 [0, 0.09375]; 0/31; 10/10 [1,1]; 1/1; 1/1; 13/15 0.866667 [0.555556, 1]; 2/15 0.133333 [0, 0.444444]; 0/6 [0, 0.390334]; 6, 3, 5 answers with claims | RAW/answers/…-8128/rates.csv | a |
| per-answer demo counts (7: 4/2/1; 2/2; 5/5; 5/5; 11/11; 1/1) | RAW/answers/…-8128/per-answer.csv | a |
| checks 3 = 3, 30, 0 outside benchmark | RAW/answers/…-8128/checks.csv | a |
| ratings R01-R06 usefulness 4,5,4,2,4,5; clarity 4,4,3,2,2,4; not rateable none | RAW/ratings/…-c89f/sheet.csv | c |
| dblp.org yes 6 of 6 | RAW/ratings/…-c89f/sheet.csv | b |
| usefulness one 2, three 4, two 5, median 4; clarity two 2, one 3, three 4, median 3.5; 0 of 6 not rateable | hand from RAW/ratings/…-c89f/sheet.csv | c |
| 6 of 6 included, 0 excluded, cap 20, seed 20261122 | RAW/ratings/…-c89f/summary.csv, excluded.csv | m |
| R04 = p15-demo-q, R05 = p15-demo-n (join) | RAW/ratings/…-c89f/key.csv with per-answer.csv | analysis (a joined to c) |
| D-read levels (clients 1/5/10/20; wall 120/24/13/10; completed 451/600/600/600; per s 3.758/25.000/46.154/60.000; ok 451/34/34/50; 429 0/397/356/375; 503 0/166/203/157; 5xx 0/3/7/18; error 0.000/0.943/0.943/0.917; p50/p95/p99 and n; success p50 0.161/0.160/0.206 with n 34/34/50) | RAW/performance/…-78bc/derived-values.csv (p15-d-read-load/levels.csv) | a |
| graph route 449 ok, 1012 429, 526 503, 28 502; evidence 120 ok, 116 429; per level 3/7/18 and 166/203/157 | RAW/performance/…-78bc/route-classes.csv | a |
| throttle 5/s burst 10, 4 queries per task | experiments/results-summary.md section 7 (5), :428 | m |
| D-query levels (1/2/4/8 clients; 127/135/51/16 s; 10/19/60/60; 0.079/0.141/1.176/3.750; 503 0/0/48/53; 504 0/1/4/0; 429 0/0/0/3; error 0.000/0.053/0.867/0.933; p50/p95/p99 and n; success p50 11.730/12.035/11.181/12.332 with n 10/18/8/4); totals 40/101/5/3 | derived-values.csv (p15-d-query-load/levels.csv); route-classes.csv | a |
| C: 0->1->2->0; out 2 in 1; 22:58:49, 23:01:39, 23:04:26, 23:18:21, 23:24:59; drain 1172; 5370; 19200; 982.935; DLQ 1/1 | derived-values.csv (p15-c-embedding-backlog/observed.csv) | a |
| 170 s, 398 s | hand from the C timestamps above | a |
| throughput by tasks 19 periods 17580 15.421053; 3 periods 1620 9; 3 periods 0 | RAW/performance/…-78bc/throughput-by-tasks.csv | a |
| F: dlq 0->1, verdicts, 200/409, 0->1, drain 151 | derived-values.csv (p15-f-poison-replay/observed.csv, parts.csv) | a |
| A/B: exit 5; 4122, 1488 + 2, 1416 + 4 s; 3/3/5 instances; 14319, 6576, 8380 instance-s; 0.9546, 0.4384, 0.558667 USD (estimates); 348 splits; 2231914; 33112 and 3866; 55573 and 3877; 0 spill; B 2 to 4 at 23:12:00 | computed.csv; derived-values.csv (attempts tables) | a (costs: estimate) |
| 767 s | hand from the B attempts rows (22:59:13 to 23:12:00) | a |
| 32 measures, 39 route rows, 51 Sum series, 2 missing, 19 WARN | runbook-log :37; row counts checked with grep -c (33, 40, 52 lines incl. header) | s |
| D9a exit 5 | experiments/results-summary.md:45 | a |
| factory totals (18 phases, 87, 38/80 0.475, 196 = 0/30/149/17, 62, 3.444444, 5, 3, 5/5, 3, 6, 0, 0, 27) | RAW/factory/…-d311/totals.csv | a |
| per-phase BLOCKED-ON 2,1,0,1,1 | RAW/factory/…-d311/counts-by-phase.csv | a |
| 388 events, 362 counted; 87+196+62+5+3+3+6 = 362 | events.csv line count; runbook-log :38; hand from audit-strata.csv | a |
| per-agent table (16 rows) | RAW/factory/…-d311/counts-by-agent.csv | a |
| strata 11/3/2/1/1/1/1; audit 20 of 20 real, 20 of 20 correctly classified, agreement 20/20 | audit-strata.csv; hand from RAW/factory/…-d311/audit-sample.csv (grep -c ',1,1,$' = 20) | c |
| E2 attempt 1: exit 6; 8 gates passed; sbt exit 1, 1038 s; null/null; lines 11085-11086, 11740-11746 | RAW/repro/20261122T150259Z-7951f94-9a3d/check-gates.txt, run.txt | a |
| 6 tests failed in the main checkout | runbook-log :27 | s |
| E2 attempt 2: exit 0; 9 gates; 1092 s; 2306 module, 399 contract; lines 11694-11707 | RAW/repro/20261122T160611Z-79b5583-4c1e/check-gates.txt, run.txt | a |
| historical 59/59/59, 0/0/0 | RAW/repro/…-d603/summary.csv | a |
| current 59/59, 51 equal, 8 different, 8 intended, 0 unexplained/missing/extra/not observed | RAW/repro/…-a9a7/summary.csv, comparison.csv | a |
| Hadoop replay exit 0, 127 s, log :476 | RAW/repro/e5-pipeline-…/run.txt, hadoop-pipeline.log | a |
| real-samples 7, 33, 33, 67; synthetic 5, 11, 7, 0 | RAW/repro/e5-pipeline-…/local-run-report.json | a |
| release records 7, 0 unreadable, 7 with checksum, 7 linked (4 Phase 14, 3 Phase 15), 0 not linked, 7 with execution fields; 10 approval lines, 3 unused | RAW/repro/…-37b5/summary.csv, release-records.csv, approvals.csv | a |
| review line refs :13-18, :59-64; storage-D :77, :87-90; FECD :477-483 | the cited review and report files | m |

## Replayed commands and outcomes

No repository command was replayed beyond read-only checks (the work order forbids running scripts or tools). Observed: `git diff --stat 79b5583 HEAD` over the check.sh inputs printed nothing; `git diff --stat 7e551c8 HEAD -- modules/hadoop modules/spark modules/core` printed nothing; `git log 7e551c8..HEAD` over the suite, evaluate.py and the rubric printed nothing; `shasum` of suite-v1.json and evaluate.py equal the signed pins.

## Link-check results

96 relative links (94 unique paths) extracted with grep -o; every one resolved with `ls -d` relative to docs/; 0 missing.

## Unverified claims

- "real-samples values equal Phase 15 take 3's reported values" (10.7): taken from runbook-log :39, not re-compared by me.
- "data points unchanged in the redrawn plots" for the 8 intended differences: from the orchestrator's check, runbook-log :51.
- E2 attempt 1 diagnosis (shell exports, 6 tests): runbook-log :27.
- Superseded attempt-1 exit codes: not recorded (runbook-log :34).

VALIDATION EVIDENCE
- shasum -a 256 docs/agent-runs/phase-16/prompt.md -> c75cc5f895faed13880a69ec285231c5498fd50924c7b875fe7e02cce1707dc8
- git rev-parse HEAD -> ecc13052b09e0b0396f47894e8858c6029632886
- link check: 96 of 96 relative links resolve (ls -d), 0 missing
- grep -c 'RESULTS PENDING' docs/evaluation.md -> 0
- grep -nE '[0-9]{12}' docs/evaluation.md -> no output
- grep -ncE 'amazonaws|arn:aws|s3://|AKIA|ASIA|sk-ant' docs/evaluation.md -> 0
- shasum suite-v1.json -> 0f7f7d33…9970 (= signed); evaluate.py -> 8e7f3e74…96b3 (= suite :15)
- attempt-1 vs attempt-2 result files: identical sha256 for retrieval (3), performance (7), factory (6 of 7; audit-sample.csv differs by the human's audit entries)
- git status --short -> ` M docs/evaluation.md`, `?? experiments/.DS_Store` (pre-existing, not mine)
- shasum -a 256 docs/evaluation.md -> eb41b7d200cc076b58eacd3631a1eb55419b228372e02f367850ff02448b7b81
files changed after these runs: none
