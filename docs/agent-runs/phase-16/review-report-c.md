# Review report R-3 (review-report-c.md): step X2 (evaluate.py `answers`, `graph`, `er`; X2 fixtures; spark-suite-table-v1.csv; suite-v1.json) and step W1 (docs/evaluation.md, docs/emulator.md)

Work order: docs/agent-runs/phase-16/prompt.md (sha256 c75cc5f8…dce7, taken from the orchestrator; I cannot hash). I read decisions 3, 13 (shared rules and X2 items 9-13), 14, 15, 16, 17 (E1, E3-E13) and 23. My tools were Read, Grep and Glob only. I ran no command and changed nothing.

VERDICT: REQUEST_CHANGES

One major finding is open (P16-C-01, `interface: no`). There are five minor findings and no critical one. I did not raise R-2's open findings P16-B-02, P16-B-04 and P16-B-05 again.

## Findings

| ID | Severity | Interface | Owner | file:line | Failure scenario and impact |
|---|---|---|---|---|---|
| P16-C-01 | major | no | experiment-engineer | experiments/evaluation/evaluate.py:2161 (`("schema_version", ["version", "schemaVersion"], ["schemaVersion"])`); fixture experiments/evaluation/fixtures/graph/happy/input/sample-a.json:3-4 with expected/provenance.csv:7 | **What happens:** `graph` counts the top-level DynamoDB attribute `version` as "schema version present". In ScholarChain, `version` is the conditional-write item version, not a schema version: `Item.Version = "version"` (modules/storage/src/main/scala/org/scholarchain/storage/ports/KeyValueStore.scala:47), used for `putIfVersion` (DynamoDbKeyValueStore.scala:73; ItemSupport.scala:63-65, :100; the ConditionalResult doc at KeyValueStore.scala:59, "its version differs"). **Real E5 input:** researcher-metric-cache-sample25.json and entity-link-cache-sample25.json contain no `schemaVersion` anywhere (Grep count 0), yet every item has `version` N "1". The tool would report schema_version 25/25 = 1 for both tables. For RunLedger it would report 4/4, but only the 2 query-audit items carry `schemaVersion`; the 2 claim markers carry only `version`. **Impact:** group 2's provenance audit against the CLAUDE.md invariant ("policy and schema versions") overstates provenance with a wrong numerator. The happy fixture writes the defect into its expected result (2/2 present from `version` alone), so E1 would pass on the broken behavior. **Repair:** drop `version` from the attributes the schema_version check accepts. Fix sample-a and its expected rows, and add a fixture item that has `version` but no `schemaVersion` and must count as absent. Then re-pin `evaluationCode` and the rating-sheet generator pin. |
| P16-C-02 | minor | no | experiment-engineer (decision for the human) | evaluate.py:2005-2007, :2045-2047 (`claim_rows` with per-attempt units, then `wilson_row` over attempts); suite-v1.json:170 | **What happens:** the tool computes intervals over units that it labels as dependent itself. (1) clip 01's 2 "answers" are two attempts at one question (sources note at :2039, suite :170 "not independent"), yet the claim rates get a bootstrap over those 2 units and the abstention rate gets a Wilson interval. (2) The 40 D-query explain rows repeat questions across levels with the same deterministic query_id (load-explain.csv:2, :12, :30 all `run:v1:a606…` for q05), and FECD-3 applies. They still get Wilson intervals as if they were 40 independent answers (0/40 gives high bound z²/(40+z²), about 0.088). **Impact:** the intervals overstate precision. This literally follows decision 3 ("answer-level rates … with Wilson intervals"), but it conflicts with the same decision's dependence rule and with decision 23 ("no interval on a non-random sample"; clip 01 is one hand-picked query). The human should decide whether these sources get `no dependence-aware interval` instead. |
| P16-C-03 | minor | no | documentation-engineer | docs/evaluation.md:218-230 (T11) | T11 says it carries the Phase 15 limitations "as listed there" (results-summary.md:435-452), but it leaves out the F limitations: user-visible impact not measured, and API event epochs that appear to be end times (FECD-7). It also leaves out the D-read attempt 2 exit-table mismatch (FECD-4). FECD-5 is listed only under missing data (:263). Either add these items or drop the claim of completeness. |
| P16-C-04 | minor | no | documentation-engineer (claim); experiment-engineer (tool gap, proposed later) | docs/evaluation.md:138, :187 | The document says answer-level rates "(abstention, the dblp.org check) use Wilson score 95 %", but no evaluate.py subcommand reads the completed rating sheet. Nothing computes the dblp.org rate, its Wilson interval, or the n and distribution of the ratings. At E13 those numbers would have to be produced by hand, against decision 3's rule that "every tool writes only values it computes". Either state that the ratings are summarized by a tool still to be named, or the human records this as a gap. |
| P16-C-05 | minor | no | documentation-engineer | docs/evaluation.md:215 (T8) | T8 says the diagnosis attributes "the lack of recovery to the missing graph-read retry". Step D says only that no retry happened (a code fact), and that "a retry would have helped" is not decidable (agent-report-storage-D.md:77, cause (a); summary :87-90). The wording overstates the diagnosis. Suggested text: "no retry was attempted; whether a retry would have recovered is not decidable". |
| P16-C-06 | minor | no | documentation-engineer (decision for the human) | docs/emulator.md:38, :74-78 | This file is the basis for the Teams post, and decision 14.2 says it must hold "nothing that encodes the architecture in detail". It lists the full resource inventory (six DynamoDB tables, one vector bucket with five indexes, two work queues with dead-letter queues, maxReceiveCount 5) and the internal steps of the re-embed and worker paths. Coarser wording would be safer, for example "the data bucket, the tables, the vector indexes and the work queues the configuration names". The human decides. |

## Checklist (decision 15)

**X2 item 9, `answers`:** done, apart from P16-C-02.
- Every value is kind (a) (:1695-1719).
- The claim support rate is verified ÷ all claims (:1625, :1703).
- The contradiction rate uses `conflicting`, and is `not available` when the file lacks that status (:1694-1696).
- The abstention codes rows are :1985, :2026 and :2052-2063.
- Abstention quality on abstain-labelled questions: :1979, :2013-2020.
- Answer-key agreement per claim is `not available` (:1943-1944). The answer key against the evidence bundle is labelled "retrieval agreement, not claim accuracy" (:1948-1951).
- The type breakdown is `not measurable` where the frozen fields hold no type (:1707-1712, :2105-2112).
- Rates are never pooled: there is one block per source (:1926, :1994, :2031, :2069).
- Aggregated claim rates get no interval (:1683).
- Source labels give their origin (file, argument or none) (:1738-1749).

**X2 item 10, `graph`:** done, apart from P16-C-01.
- The suite table is recounted from the CSV, not copied (:2313-2325).
- Invariants are computed once per distinct sha256 (:2328-2339).
- Provenance is reported per table with numerator and denominator, and the note says the samples are non-random (:2343-2362).
- The table names in spark_invariants match the real spark-outputs.csv (65 rows checked).

**X2 item 11, `er`:** done on the reduced path, with the source labelled (:2485). `--compare-decisions` is not built, which is justified because the er-v1 decision set is gone (agent-report-er.md group 1 plan). `er_v2_accuracy` is written `not measurable` (:2590).

**X2 item 12, suite:** checked against each parser.
- E4: --phase5-report, --start-marker, --end-marker, --gold, --note.
- E5: --suite-table, --spark-outputs ×2, --sample ×4, --test-log.
- E8: --run-report, --benchmark, --explain-csv, --audit, --label ×2 (valid SOURCE.FIELD), --seed, --resamples.
- E11: the answer-file option keys id, question and answer are valid.
- Placeholder pins with a non-hex sha256 are skipped by check_pin (:480-481).
- The variability text at suite :82 matches agent-report-er-eval.md:40 (seed 20261114, 1000 reps).
- Checked, none.

**X2 item 13:** jq results come from the producer report; I cannot run jq. The E1 command list has 8 lines, with case counts 3/3/3/6/3/3/3/4.

**W1 item 1, evaluation.md:**
- No measured result or conclusion appears in any section marked pending (§10 is markers only).
- Measured facts and interpretation are separate subsections in §10.1-10.7.
- The threats list covers 14.1: T1-T7, FECD-1 (T8), FECD-3 (T9), the non-random DynamoDB samples (T10), the Phase 15 and A/B limitations (T11, see P16-C-03), FECD-6/8/9 (T12-T14).
- The missing-data list is §8. Negative results are named as kept in §9.
- Every "suite :N" line reference matches suite-v1.json as of the R-3 snapshot (I spot-checked more than 30 of them). The FECD citations (:477, :480, :482, :483) and rubric-v1.md:8 match.

**W1 item 2, emulator.md:**
- Code is limited to one 4-line command block (:45-50). No prompt.
- Results are `PENDING E14`.
- The emulator limitations match 14.2 (:161-166), and so do ours (:169-170).
- The comparison plan covers D17, F, D4a and M-01. The coverage table is by outcome class.
- The OPERATIONS.md counts (3 / 29 / 2) match OPERATIONS.md:62.
- For architecture detail, see P16-C-06.

**W1 item 3:** the relative-link check is from the producer report. I spot-checked several targets and all exist.

**evaluate.py is stdlib-only and self-contained:** the imports at :52-63 are argparse, csv, hashlib, io, json, math, os, random, re, sys, tempfile and datetime. There is no sibling import and no subprocess. Writes go only to raw/GROUP/RUN_ID (:372-386) or to a mkdtemp folder outside the repository (:2663-2666). Checked, none.

**Fixture trace, every X2 case.** I traced each case by hand through the code. Every result cell matches the expected files exactly. The sha256 cells are the exception: I cannot compute them, but they are consistent where the same content appears twice.
- **answers/happy:** sources.csv (4 rows, label origins, distinct questions 2/2/1/1) matches. rates.csv rows 2-71 match:
  - run report: 5/8, 1/8 ×3, 6/8, 3/4, planted 3/4 with byKind sorted wrong-number then wrong-year;
  - Wilson bounds: 2/2 → 0.34238; 1/1 → 0.206549; 0/4 → 0.489891; 0/3 → 0.561497; 0/2 → 0.65762;
  - the audit's equal-ratio bootstrap gives lo = hi;
  - demo type rows: coauthor-edge 1/1, number 0/1 and 1/1, graph-path and supporting-publication 0/0 `no claims`.

  per-answer.csv and checks.csv also match, including q9 unknown → `no`. This fixture does not exercise P16-C-01.
- **answers/malformed:** exit 5 ("five" is not a count), no files. **answers/missing:** exit 3.
- **graph/happy:** all 7 files match. That includes coverage per check, KG 2/2/0/0 and PF 2/0/1/1, the second CSV treated as byte-identical, invalid total 2 → nonzero, w2024 7 against 6 → different, communities:w2024 absent → not measurable, difference 1, the TableB counts with 1 unparseable record and the NULL itemHash not present, and test-log line 3. The expected TableA schema_version 2/2 is correct for the current code but is the defect in P16-C-01.
- **graph/malformed:** exit 5 (planarity). **graph/missing:** exit 3.
- **er/happy:** all 6 files match. The decoys outside the markers are ignored. The weighted flag is parsed with its nested bracket. The bootstrap item regex consumes the precision parenthetical, so no stray "bound" item appears. The zero bounds match. Recomputation: 8/9, 8/10, 16/19, 14/16, and 4/20 against a printed 25.0 → `no`. All gold checks match.
- **er/phase5-table:** the input is verbatim from agent-report-er-eval.md:742, 753, 779, 782, 786-808, 812-816, 881-897 (I compared line by line). All 6 expected files match. 93/94, 93/112, 186/206, 144/247 and 109/300 are each within 0.05 percentage points of the printed value. The definitions at :788 confirm that unresolved excludes conflict. Gold is `not available` because the Inputs table is left out of the fixture. The real run will find the gold row at :761.
- **er/malformed:** exit 5. **er/missing:** exit 3.

**spark-suite-table-v1.csv against agent-report-spark.md:45-87:** all 43 rows are identical, with pipes turned into commas. Tallies: independent 37, mixed 4 (:187, :368, :455, :497), property 2 (:524, :717). KnownGraphSuite has 15 rows and PipelineFixtureSuite 28. No cell holds a comma. Checked, none.

**Metric kinds, numerators, denominators, inclusion rules and sources:**
- answers: every row has kind, numerator, denominator and rule.
- graph: suite coverage is b where independent and a otherwise. Invariants and provenance are a.
- er: table rows are b. Recomputation and checks are a, and numerators exist only for the all-rows row (stated in notes.csv).

Checked, none apart from P16-C-01 and P16-C-02.

**Claims never pooled across sources:** checked, none.

**No interval on non-random samples or aggregate-only data:** the DynamoDB provenance has no interval. Run-report claim rates have no interval. The answer-level Wilson intervals are covered by P16-C-02.

**`not available` and `not measurable` never filled:** checked, none.

**Pins:** I cannot hash files. The orchestrator verified evaluate.py 07e84842…, the suite 679a7170… and the spark table 91184acf…. The suite's evaluationCode pin and generator pin equal 07e84842…. Fixing P16-C-01 (and R-2's queued repairs) moves both pins.

**Tracked diff, R-3 against R-2:** only experiments/README.md changed (blob 1615a80 → ac692db). It gains one added row for spark-suite-table-v1.csv. The blobs of common.py, emr_capacity.py, queue_worker.py and results-summary-template.md are unchanged. diff-R-3.txt holds no tracked docs/** change; both docs are untracked.

**Secrets and identifiers:**
- No account IDs and no bucket names other than the `scholarchain-emu-` prefix.
- The only host besides 127.0.0.1 is docs.aws.amazon.com, in documentation citations, which 14.2 requires. `0.0.0.0` appears only as the documented bind setting inside the container.
- A Grep for runs of 12 digits under experiments/evaluation matches only R-2's X1 release-record fixtures (P16-B-05). Both docs have none.

**Unsupported claims in docs:** P16-C-04 and P16-C-05. Statements about emulator behavior rest on the documentation as quoted in OPERATIONS.md and scripts/README.md (no web access, unverified by me).

## Files read

**Read in full, all 57 paths in files-R-3.txt:**
- docs/emulator.md and docs/evaluation.md;
- experiments/evaluation/evaluate.py (lines 1-2711);
- every file of the 10 fixture cases: answers happy, malformed, missing; graph happy, malformed, missing; er happy, malformed, missing, phase5-table;
- spark-suite-table-v1.csv and suite-v1.json.

None is missing.

**Tracked files:** diff-R-3.txt was read in full and compared with diff-R-2.txt. I reviewed these files through their diff hunks, not in full.

**Supporting reads:**
- reports: review-report-b.md, agent-report-experiment-X2.md, agent-report-docs-W1.md, agent-report-spark.md:30-174, agent-report-rag.md:90-134, agent-report-storage-D.md:40-91;
- Phase 5 report: agent-report-er-eval.md:36-43 and :738-902;
- data files: attempt-06:270-439, the real spark-outputs.csv, load-explain.csv (head plus Grep), run-ledger-clip01-query.json in full, the heads of the 3 cache samples;
- results-summary.md:11-46 and :421-453, agent-report-experiment-FECD.md:474-485;
- KeyValueStore.scala:30-60 and Grep of storage `version` uses;
- OPERATIONS.md and scripts/README.md (Grep), rubric-v1.md:1-14 (Grep), phase-15 runbook-log (Grep);
- the work order decisions listed above.

I did not read agent-report-er.md or agent-report-experiment-X1.md in full. I used only the lines the producer reports cite; R-2 covered X1.

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: docs/agent-runs/phase-16/prompt.md (sha256 c75cc5f895faed13880a69ec285231c5498fd50924c7b875fe7e02cce1707dc8), session 2 review R-3 (X2 and W1)
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools)
TEST RESULTS: not applicable (no test run). Every X2 fixture case (10) was traced by hand; all expected result cells match the code. The sha256 cells cannot be computed by me. The graph/happy schema_version expectation is correct for the code but encodes P16-C-01.
VALIDATION RESULTS: 57 of 57 untracked slice files read in full. Tracked diff compared with R-2: only experiments/README.md changed (one row). 1 major and 5 minor verified findings.
ASSUMPTIONS: the orchestrator's hashes of evaluate.py, the suite and the spark table are taken as given. Emulator documentation facts are as quoted by OPERATIONS.md and scripts/README.md. The E7 demo-answer shape (A3) is to be confirmed by the orchestrator before E8 and E11.
RISKS: P16-C-01 would publish a false 100 % schema-version provenance share for two or three tables at E5 and E13. Any repair to evaluate.py (P16-C-01 plus the queued P16-B-02, P16-B-04, P16-B-05) changes the evaluationCode pin and the rating-sheet generator pin before E3. P16-C-02 and P16-C-04 need a human decision on interval policy and on how the ratings are summarized.
MANUAL ACTION REQUIRED: human decisions on P16-C-02 (intervals over dependent attempts and rows) and P16-C-06 (level of architecture detail in emulator.md for the Teams post).
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: step 8 triage. P16-C-01 goes to experiment-engineer, in the same repair pass as P16-B-02/04/05, then a re-pin. P16-C-03, P16-C-04 and P16-C-05 go to documentation-engineer (minors, recorded). P16-C-02 and P16-C-06 need human decisions. Step 10 re-checks evaluate.py, the graph fixture and the suite pins.
```
