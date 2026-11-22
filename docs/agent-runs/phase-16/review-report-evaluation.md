# Review: E13 independent review of docs/evaluation.md (Phase 16 session 3)

(Saved verbatim by the orchestrator from code-reviewer's final message.)

I found no critical or major problems, so the verdict is APPROVE. There are six minor findings, all owned by documentation-engineer. I checked every number in section 10 against the frozen files and none differs. The minors are wording, attribution and completeness gaps.

Read-only review at HEAD ecc13052b09e0b0396f47894e8858c6029632886. I used only the Read, Grep and Glob tools and wrote no file. The document read is the 826-line version the writer reports (sha256 eb41b7d2…7b81 as the writer stated it; I cannot compute hashes).

## Findings

| ID | Severity | Interface | Owner | File:line | What is wrong | Frozen file that shows it |
|---|---|---|---|---|---|---|
| P16-L-17 | minor | no | documentation-engineer | docs/evaluation.md:218 (section 6), :597 (10.4 join) | The rater-blindness limit is understated. R05's text shows "claim-1: conflicting". It also shows "Partially supported: a number is bound to a field…" twice, on claim-2 and claim-6. The other five rated answers have every claim verified, and their texts say "lists only claims checked against ScholarChain graph evidence". So every non-verified status in the rated set was visible in the answer texts. The document should say this, both as the section 6 limit and as a caveat on the 10.4 statement that verification and usefulness measure different things. It is an input to spec gate 1. | experiments/evaluation/raw/ratings/20261122T173416Z-7e551c8003c3-c89f/sheet.csv:68, :72, :76; answers/…-8128/per-answer.csv:43 |
| P16-L-18 | minor | no | documentation-engineer | docs/evaluation.md:606 (10.4 interpretation) | "a false-premise question that was correctly refused" is the rater's judgment (kind c), written as if it were a system fact. The system did not abstain: `abstained: false`, per-answer `abstained` = no, and demo abstention is 0 of 6. The answer opens with 6 claims and puts "Not supported" only in the notes. Fix: attribute "correct" to the rater. Add, as a descriptive negative result, that the system's abstention flag was not set on this false-premise question. Formal abstention recall stays not measurable (no benchmark ID; the benchmark has no false-premise category). | answers/p15-demo/p15-demo-n.json:3; answers/…-8128/per-answer.csv:43; rates.csv:71; sheet.csv:82 |
| P16-L-19 | minor | no | documentation-engineer | docs/evaluation.md:779 (10.7 record check) | "All 7 records carry execution fields and report `verified` = yes" leaves out the `digest_checks` column. On 4 of 7 records it reads "verified 0; not-applicable 4"; on 3 it reads "verified 3; no running task 1". A reader may take `verified = yes` to mean the image digests were verified. The digest counts should be stated. | repro/20261122T181639Z-7e551c8003c3-37b5/release-records.csv:2-8 |
| P16-L-20 | minor | no | documentation-engineer | docs/evaluation.md:204 (section 5), :536-538, :548-559 | (1) Section 5's Wilson row names attempt-06 only, but the demo abstention row also carries a Wilson interval: [0, 0.390334] in rates.csv:71, shown at :559. (2) Ruling on the writer's open point is below. Keep the values, but put the non-random status in the table headers themselves, as group 3 does: "EXPLORATORY: fixed hand-written benchmark, n = 30" on the attempt-06 Wilson rows, and "EXPLORATORY: hand-saved demo set, non-random, n = 6" on the demo table. Today the caveat sits only in prose under the tables. | answers/…-8128/rates.csv:20-22, :50-71 |
| P16-L-21 | minor | no | documentation-engineer | docs/evaluation.md:233-239 (T8) | T8 leaves out two code facts from step D. (f1): the storage port drops the error class. The W1 draft had it; the final text removed it. (f2): rag's resolver silently swallows graph failures. D-query had about 14 Neptune server errors with no abstained and no 5xx answer, so answers fell back silently to identity "unknown". (f2) is a threat to the D-query reading "abstention 0 of 40" in group 4 (10.4 :542), and it appears nowhere in sections 7 to 10. Add it to T8 or as its own threat. | docs/agent-runs/phase-16/agent-report-storage-D.md:82-83 |
| P16-L-22 | minor | no | documentation-engineer | docs/evaluation.md:390 (10.1 interpretation) | "the zero-event bounds are much wider than the bootstrap intervals" is not true for coverage: its zero-event bound 91.8–93.4 lies inside the bootstrap interval 90.1–94.5. Limit the sentence to the three metrics flagged unreliable (precision, recall, F1). | er/20261122T170516Z-7e551c8003c3-91dd/weighted-intervals.csv:2-5 |

### Ruling on the writer's open point (intervals on six hand-picked demo answers and on attempt-06)

Allowed as written. Only the labelling changes (P16-L-20). This is not a defect against the work order, for four reasons:
- Decision 3's group 4 row (prompt.md:125) and decision 13 item 9 (prompt.md:274) call for a bootstrap over answers where per-answer values exist, and Wilson intervals for answer-level rates. None of the group 4 sources is a random sample, so the human necessarily expected intervals over non-random answer sets.
- The human's P16-C-02 decision (runbook-log :10) limits intervals to independent answers, not to random ones. The signed suite (suite-v1.json:170, approvals.md:3) turns that into a rule. The rule gives intervals to demo answers that answer distinct questions, and the six demo answers do (sources.csv:5: 6 answers, 6 distinct questions).
- prompt.md:473 ("no interval on a non-random sample") is the general variability statement. It is spelled out for group 2's samples at :123. review-report-P16-L-02.md N1 (lines 25-30) reads it the same way, says attempt-06's Wilson rows are allowed, and offers an EXPLORATORY header as the consistency option.
- The text already says the demo intervals "describe resampling of these six answers only" and that attempt-06's are exploratory (:540, :563).

The human may still choose to drop the demo interval column at the gate.

## Numbers checked (all equal to the frozen files unless a finding says otherwise)

I checked every section 10 number, well over 40, across all 7 groups.

- **Group 1:** confusion.csv: the unweighted matrix (12 cells) and the three weighted cells. recomputed.csv: all five rows (numerator, denominator, value, printed %, equal). table.csv: all 13 rows, all columns, flags included. weighted-intervals.csv: all five rows. checks.csv: 11 of 11, and the gold sha256. notes.csv: code version and ER run.
- **Group 2:** spark-suites.log:40, :76, :77 (15, 28, 43/0/0/43) and test-log.csv. suite-summary.csv (3 rows) and suite-coverage.csv (11 rows). spark-files.csv: sha256 and 65 rows. invariants.csv: 44 rows, of which 4 zero (rows 2-5), 39 equal (rows 6-44) and 1 difference. Also 200484, 155978, the five window edge counts, the four window researcher counts, 64, and 758328/758314/14. provenance.csv: all 32 cells, including 19/25 = 0.76 and "unparseable 0". not-measurable.csv.
- **Group 3:** summary.csv: all 15 values with their bootstrap bounds, plus the unjudged shares 0/80, 179/180 and 139/220. per-question.csv: vector-only empty on q02, q03, q20, q21, q30; q25 judged 1 at 0.035714; graph-only empty on q25 and q26 with `topic-candidates-failed`; "by design weak" 9 and 2; fused q25 and q26. checks.csv: 17 rows. The universe is 11 and the labels 100 (retrieval-eval-report.json:48-49).
- **Group 4:** sources.csv: 4 rows. rates.csv: attempt-06: the 4 claim rows, 110/110, 4/4, 85/86 and the 5 planted kinds, and the 3 Wilson rows; explain: 0/40 twice; audit: 2/2, 0/2 three times, 0/1; demo: all rows with their bootstrap bounds and Wilson [0, 0.390334]. per-answer.csv: the 6 demo rows (7: 4/2/1, 2, 5, 5, 11, 1; evidence types giving 3, 1, 1 and 5 answers). T19's query-ID matches: q is run:v1:4f74…; q02, q11 and q17 match explain rows 25, 27 and 28. The T9 repeated query IDs. checks.csv. sheet.csv: usefulness 4,5,4,2,4,5 and clarity 4,4,3,2,2,4 (medians 4 and 3.5 recomputed by me), dblp "yes" on 6 of 6, rater notes R04 and R05. key.csv: R04 is q.json and R05 is n.json. summary.csv and excluded.csv.
- **Group 5:** derived-values.csv, both D levels tables: wall time, completed, per second, error rate, p50/p95/p99 for all requests and for successes, n and the few-samples notes. route-classes.csv: all 39 rows. I recomposed ok as 34/34/50 and 429 as 397/356/375; totals 449/1012/526/28, 120/116, 40/101/5/3. C observed rows: 22:58:49, 23:01:39, 23:04:26, 23:18:21, 23:24:59; 1172, 5370, 19200, 982.935; DLQ 1/1. I recomputed 170 s and 398 s. F observed and parts rows. The A and B attempts rows. I recomputed 767 s. computed.csv: all 32 measures, including costs and spill. throughput-by-tasks.csv and missing.csv. results-summary.md:45 (D9a), :428 (limits) and :435-452 (T11 complete).
- **Group 6:** totals.csv: all 18 rows. counts-by-agent.csv: all 16 rows, every column used. counts-by-phase.csv: per-phase BLOCKED-ON 2,1,0,1,1, and terminal and row counts that sum to 5 and 3. audit-strata.csv: 11/3/2/1/1/1/1, summing to 362. audit-sample.csv: 20 rows ending `,1,1,`. events.csv: 389 lines, so 388 events.
- **Group 7:** Both check-gates.txt files (the eight gates, sbt 1038 s and 1092 s, module_tests and contract_tests null versus 2306/399, the line numbers cited) and both run.txt files. The d603 and a9a7 summary.csv files, and comparison.csv's 8 intended rows (3 missing.csv, 3 capacity SVGs, 2 queue SVGs). The 37b5 summary.csv, approvals.csv (10 lines, 3 unused) and release-records.csv. e5-pipeline run.txt, hadoop-pipeline.log:476 (127 s), and local-run-report.json (7/33/33/67 and 5/11/7/0).
- **Methods and citations:** suite :15, :19-56, :82, :107, :127, :140-141, :170, :184, :218 and :227. evaluate.py:201 `wilson`; approvals.md:3; rubric-v1.md:8. FECD report :477-483; storage-D :77-90; review-report-P16-L-round2.md :13-18 and :59-64. load-explain.csv (41 lines); intended-differences-v1.csv (9 lines).

**Not verified (no tool for it):** the "Added in" commit hashes in section 2; the writer's `shasum` statement that attempt-1 folders are byte-identical to attempt 2 (10.9); session facts taken from runbook-log (exit codes, the Phase 15 take 3 equality, the E2 diagnosis), which I checked only against the log's own text.

## Other checks

1. **Kind labelling:** correct. No kind (a) value is presented as accuracy. The verifier, answer key, invariants, retrieval and factory values are all labelled (a). Gold labels and dblp.org are (b); ratings and the audit are (c).
2. **Facts and interpretation:** separated in 10.1 to 10.7. Interpretations repeat numbers but introduce none. The one exception in spirit is P16-L-18's attribution.
3. **Negative results:** all present: vector-only retrieval (10.3); E2 attempt 1 / P16-L-16 (10.7); D overload and FECD-1 (10.5, T8); A-Hadoop attempt 1, D-read attempt 2, D9a, and the superseded attempts (10.5, 10.9); not-measurable items (section 8); P16-L-10 to L-13 open, L-14 and L-15 moved to Phase 17 (10.9); low ratings R04 and R05 (10.4); partially supported and conflicting claims, and the planted-error miss. Gaps are P16-L-18, L-19 and L-21. The emulator `not-implemented` operations are deferred to E14 (`docs/emulator.md`).
4. **Intervals:** within decision 3, suite :170 and P16-C-02 (ruling above). No interval appears on group 2, the explain rows, clip 01, the ratings or the factory counts.
5. **Threats and missing data:** every decision 14.1 threat is present (T1–T11), with T12–T19 beyond it. Gaps are P16-L-17 (blindness, not stated as a threat) and P16-L-21.
6. **Secrets and architecture:** no account ID, bucket name, ARN, host, key or twelve-digit run found by reading. No EMR cluster ID or instance class appears. Architecture detail is limited to what the evaluation needs (the breaker and retry in T8).
7. **Links:** I spot-checked 24 relative targets (all phase-16 reports cited, approvals.md, emulator.md, the Phase 5, 10 and 15 inputs, the suite inputs, every RAW folder). All exist. The writer reports 96 of 96 resolving.

## Spec gate items (input to E15, not a phase verdict)

| # | Gate item | Status | Evidence |
|---|---|---|---|
| 1 | Evaluation data is not generated by the answering prompt without independent review | met (evidence) | No LLM judge (sections 1 and 6). Kind (b) is only the gold labels and the dblp.org column. sheet.csv has no status, queryId or run column (its header line 1). rubric-v1.md:8. This E13 review checked the document against the frozen files. Caveat: P16-L-17, the answer texts expose R05's statuses. |
| 2 | Benchmark questions and relevance labels are versioned | met (evidence) | suite-v1.json:19-56 gives version and sha256 for benchmark-v1, the gold labels, rubric-v1 and the sheet generator. Signed at approvals.md:3. Benchmark sha256 b58c5e1a… matched in retrieval checks.csv:2 and answers checks.csv:5. Gold sha256 matched in er checks.csv:12. |
| 3 | The report separates measured facts from interpretation | met (evidence) | 10.1 to 10.7 each have "Measured facts" and "Interpretation" subsections, and hand-computed values are labelled. Caveat: P16-L-18 attribution. |
| 4 | Negative results remain in the submission | met (evidence) for the seven groups; the emulator part is pending E14 | Section 9 list and 10.1 to 10.9 (see "Other checks" item 3). Emulator `not-implemented` operations depend on `docs/emulator.md` at E14. Completeness caveats: P16-L-18, P16-L-19, P16-L-21. |

## Files read

Read in full: docs/evaluation.md (826 lines); the writer's report agent-report-docs-E13.md (scratchpad phase16-reports/); docs/agent-runs/phase-16/runbook-log.md; docs/agent-runs/phase-16/approvals.md; experiments/evaluation/suite-v1.json; experiments/evaluation/rubric-v1.md; every result CSV, TXT and JSON in er/…-91dd, graph/…-a0dc, retrieval/…-1864, answers/…-8128, ratings/…-c89f, repro/…-9a3d, …-4c1e, …-d603, …-37b5 and e5-pipeline; factory/…-d311: totals.csv, counts-by-agent.csv, counts-by-phase.csv, audit-strata.csv; performance/…-78bc: computed.csv, route-classes.csv, throughput-by-tasks.csv, missing.csv.

Read in part: prompt.md lines 24-133 and 257-475; diff-E13.txt lines 1-340 (the rest reviewed in the final file); performance derived-values.csv (D, C, F and A/B rows); spark-suites.log summary lines; hadoop-pipeline.log:470-476; factory audit-sample.csv (line count by pattern and first rows); factory events.csv (line count only); p15-demo-n.json (abstention fields only); benchmark-v1.json (category and abstain fields only); results-summary.md:420-459 plus matched lines; agent-report-storage-D.md:60-94; agent-report-experiment-FECD.md:474-485; review-report-P16-L-02.md N1 context; review-report-P16-L-round2.md:1-70; retrieval-eval-report.json:44-51; evaluate.py `wilson` lines.

Not read: performance cloudwatch-sums.csv and minute-throughput.csv; factory unclassified.csv; all run.json files; the superseded attempt-1 folders; the other five demo answer JSON files (beyond sources.csv and per-answer.csv); PROVENANCE.txt.

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: docs/agent-runs/phase-16/prompt.md (sha256 c75cc5f895faed13880a69ec285231c5498fd50924c7b875fe7e02cce1707dc8, as given), E13 independent review of docs/evaluation.md; decisions 3, 14.1, 17 (E13, E15), 23 and the verbatim spec gate; HEAD ecc13052b09e0b0396f47894e8858c6029632886
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools)
TEST RESULTS: not applicable (documentation review; no test run)
VALIDATION RESULTS: all section 10 numbers equal the frozen files; 6 minor findings (P16-L-17 to P16-L-22), no critical or major; 24 link targets checked and present; no identifier found by reading
ASSUMPTIONS: "Added in" commits, shasum statements and runbook-log session facts accepted as recorded (no git or hash tool); the writer's sha256 of docs/evaluation.md taken as given
RISKS: P16-L-17 (rater could see R05's statuses in the answer text) weakens the blindness part of gate 1; P16-L-21 (silent resolver degradation) weakens the D-query abstention reading; gate item 4's emulator part depends on E14
MANUAL ACTION REQUIRED: the human decides whether the minors get a repair round (documentation-engineer, one pass) and whether the demo interval column stays (ruling: allowed, add an EXPLORATORY header)
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: optional one-pass repair of P16-L-17 to P16-L-22 by documentation-engineer, then the human commits docs/evaluation.md; E14 docs/emulator.md review; E15 gate check using the table above
```

VERDICT: APPROVE
