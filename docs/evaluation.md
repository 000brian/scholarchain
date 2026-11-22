# ScholarChain evaluation (Phase 16)

Owner: `documentation-engineer`. **Status: METHODS DRAFT (Phase 16 session 2, step W1).** This file describes how ScholarChain is evaluated. It holds no measured result and no conclusion. Every results section below is marked `RESULTS PENDING FREEZE (E12)`. The results are written at step E13, from the raw outputs frozen at step E12, and code-reviewer then reviews them against those frozen files.

Work order: `docs/agent-runs/phase-16/prompt.md` (decisions 3, 13, 14, 16, 17, 23). Evaluation suite: [`experiments/evaluation/suite-v1.json`](../experiments/evaluation/suite-v1.json). Evaluation code: [`experiments/evaluation/evaluate.py`](../experiments/evaluation/evaluate.py). Both are drafts until the human signs the suite at E3. In this draft, a "suite :N" reference is a line number in `suite-v1.json` at the time of writing. Session 2's repair round may move those lines.

## 0. How to read this document

### 0.1 Three kinds of evidence

Every metric carries one kind, and the kinds are never pooled (decision 3; suite `evidenceKinds`, suite :60-64):

| Kind | Name | What it is | What it can support |
|---|---|---|---|
| (a) | internal consistency | what ScholarChain's own verifier, invariants, tests or reports say about its own output | that the system agrees with itself. Never factual accuracy |
| (b) | independent ground truth | labels made by the human architect, or facts from a source outside the pipeline | claims of factual accuracy, and only these |
| (c) | human judgment | the human architect's ratings with the rubric | usefulness and clarity as one rater saw them |

### 0.2 Facts and interpretation

Every results section has two subsections. **Measured facts** holds values computed by a tool from a frozen file, each with its source file, the tool that produced it, the numerator, denominator and inclusion rule, and the kind. **Interpretation** holds what the facts may mean. It is labelled as interpretation and never introduces a new number.

### 0.3 Three statuses per evaluation

These three are reported separately (work order, "How the run ends"):
- **execution**: did the tool run and produce valid evidence;
- **measured outcome**: what the data show, including `not measurable from the frozen evidence (REASON)`;
- **spec item**: whether a spec work-order or gate item is satisfied.

A tool that ran correctly and reports a failed check has completed its measurement. The property is then recorded as failed; the run is not called an execution failure.

### 0.4 Numbers in this draft

The only numbers in this draft are method parameters and dataset sizes: seeds, resample counts, sample sizes, file counts and label counts. Each one cites the committed (or session 2) file it comes from. A missing value is never filled; it is written `not measurable from the frozen evidence (REASON)`.

## 1. What is evaluated and how

The spec work order has seven groups (work order, "Spec work order, Phase 16"). Every group has an entry in the suite, run by the human in session 3 (planned deviation 1: agents run nothing).

| Group | Suite entry (step) | Tool | Kind | Variability |
|---|---|---|---|---|
| 1 Entity resolution | `E4-er` (E4), suite :67-84 | `evaluate.py er` (reduced plan) | (b), with (a) recomputation checks | Wilson score 95 % (unweighted) and the stratified bootstrap of Phase 5, as printed |
| 2 Graph correctness | `E5-graph` (E5), suite :86-108; `E5-bounded-pipeline`, suite :282-290 | `evaluate.py graph`; sbt run of the Spark suites | (b) hand-worked small graphs; (a) invariant counts and provenance fields | counts and descriptive proportions only |
| 3 Retrieval | `E6-retrieval` (E6), suite :111-129 | `evaluate.py retrieval` | (a), with label caveats | percentile bootstrap over questions, headed EXPLORATORY (n = 11) |
| 4 Answer quality | `E8-answers` (E8), suite :150-172; `E11-ratings-sheet` (E11), suite :248-270 | `evaluate.py answers`; `evaluate.py ratings-sheet` plus the human's ratings | (a) verifier and answer key; (b) optional dblp.org check; (c) ratings | bootstrap over answers where per-answer values exist; Wilson for answer-level rates |
| 5 Performance and elasticity | `E6-performance` (E6), suite :131-148 | `evaluate.py performance` | (a); costs are estimates | percentiles with sample counts; "few samples" below 20 |
| 6 Agentic factory | `E9-factory` (E9), suite :174-191 | `evaluate.py factory` plus the human's 20-event audit | (a), audited by (c) | counts with denominators; audit agreement rate |
| 7 Reproducibility | `E2-clean-checkout` (E2), suite :272-280; `E5-bounded-pipeline` (E5); `E10-reproduce-historical`, `E10-release-records`, `E10-reproduce-current` (E10), suite :193-246 | `scripts/check.sh` in a fresh clone; local Hadoop pipeline and Spark suites; `evaluate.py reproduce` | (a) | exact match, or listed differences marked intended or unexplained |

The spec names some items that are not implemented in ScholarChain or that no frozen label supports. They are recorded as such (decision 23):
- Optional OpenSearch comparison (group 3): **not implemented**. There is no OpenSearch tier.
- nDCG (group 3): **not computed**. No graded relevance labels exist.
- Precision@K (group 3): **not measurable**. Retrieved IDs outside the labels are unjudged, not negatives.
- LLM judge: **none is used** (planned deviation 6).

## 2. Data sources

Every input is a committed file. Its sha256 is pinned in the suite, and `evaluate.py` refuses a run whose input differs from the pin (exit 3; S/agent-report-experiment-X1.md interface handoff, suite `treeDigestRule` :57). "Added in" is the commit that added the file (`git log --diff-filter=A`).

| Source | Path | Added in | Size (source of the number) | Used by |
|---|---|---|---|---|
| Entity-resolution gold labels | [`docs/agent-runs/phase-05/gold/gold-labels.csv`](agent-runs/phase-05/gold/gold-labels.csv) | 7070f6116167cd5beb8792a197a75793003b38ca | 300 pairs: 112 MATCH, 135 NON_MATCH, 53 UNSURE (suite :27; the file has 300 data lines plus a header) | group 1 |
| Phase 5 ER evaluation report (final summary reproduced verbatim) | [`docs/agent-runs/phase-05/agent-report-er-eval.md`](agent-runs/phase-05/agent-report-er-eval.md) | 7070f6116167cd5beb8792a197a75793003b38ca | Phase 5 run `run:v1:9f96c70b5115294dcc9d3b2d1d4438b9`, policy er-v1 (suite :83) | group 1 |
| Spark known-graph and fixture suites | `modules/spark/src/test/scala/org/scholarchain/spark/KnownGraphSuite.scala`, `PipelineFixtureSuite.scala` | KnownGraphSuite.scala: 29661e98c4791b3e59a1c52c641cbea6bf609df1 | 43 tests in spark-engineer's table (suite :47) | group 2 |
| Group 2 suite table | [`experiments/evaluation/spark-suite-table-v1.csv`](../experiments/evaluation/spark-suite-table-v1.csv) | session 2 (uncommitted at draft time) | 43 rows (suite :47) | group 2 |
| Phase 15 Spark output counts (A and B runs) | [`experiments/raw/p15-a-spark/20261120T225850Z-eb786c5275a3-4f7e/spark-outputs.csv`](../experiments/raw/p15-a-spark/20261120T225850Z-eb786c5275a3-4f7e/spark-outputs.csv), [`experiments/raw/p15-b-spark-burst/20261120T225913Z-eb786c5275a3-fac6/spark-outputs.csv`](../experiments/raw/p15-b-spark-burst/20261120T225913Z-eb786c5275a3-fac6/spark-outputs.csv) | e86449aabe5205ba339345b49b55c3204468700d | both pinned to the same sha256, so one observation of one graph run (suite :96-97, :105) | group 2 |
| DynamoDB samples | [`docs/agent-runs/phase-15/dynamodb-sample/`](agent-runs/phase-15/dynamodb-sample/) | ade54326a63e9c2dd00d1ae41c9b0dbb21842e06 | 25 items each of EvidenceBundle, ResearcherMetricCache, EntityLinkCache; 4 RunLedger items of clip 01 (file names; `"pk"` count per file). The selection method is not recorded (work order, "Where things stand") | groups 2 and 4 |
| Benchmark questions and labels | [`modules/rag/src/test/resources/benchmark/benchmark-v1.json`](../modules/rag/src/test/resources/benchmark/benchmark-v1.json) | 9a9174dffecf8a37ed7105d3533a507881079a27 | 30 questions (`"id": "q` count in the file) | groups 3 and 4 |
| Retrieval report of experiment E | [`experiments/raw/p15-e-retrieval-modes/20261120T221402Z-6a6c31a06d60-b6c7/retrieval-eval-report.json`](../experiments/raw/p15-e-retrieval-modes/20261120T221402Z-6a6c31a06d60-b6c7/retrieval-eval-report.json) | 2cc5804baaced06850ed4f4ce79287c6b82643c7 | universe 11 questions, 100 labels (report lines 48-49); 19 questions not comparable (work order decision 3) | group 3 |
| Phase 10 benchmark run, attempt 06 | [`docs/agent-runs/phase-10/run-report-attempt-06-anthropic.json`](agent-runs/phase-10/run-report-attempt-06-anthropic.json) | 6bc95d53775661914fad62d1588801a2905ce856 | 30 questions (report line 291); aggregated counts only (S/agent-report-rag.md field table) | group 4 |
| D-query explains | [`experiments/raw/p15-d-query-load/20261120T235521Z-ff00cfa278c8-ca0c/load-explain.csv`](../experiments/raw/p15-d-query-load/20261120T235521Z-ff00cfa278c8-ca0c/load-explain.csv) | abe4764a8293… (short form, to keep a long digit run out of the commit scan) | 40 data rows (41 lines with the header) | group 4 |
| Clip 01 query audit | [`docs/agent-runs/phase-15/dynamodb-sample/run-ledger-clip01-query.json`](agent-runs/phase-15/dynamodb-sample/run-ledger-clip01-query.json) | ade54326a63e9c2dd00d1ae41c9b0dbb21842e06 | 4 items, 2 attempts of one question (S/agent-report-rag.md field table) | group 4 |
| Saved demo answers (optional, E7) | `experiments/evaluation/raw/answers/p15-demo/` (only if the human copies them after the identifier scan) | not committed at draft time | sha256 recorded at run; provenance recorded before scoring (suite :165) | group 4 |
| Phase 15 raw experiment data A to F | [`experiments/raw/`](../experiments/raw/) | per folder: 6a6c31a, 2cc5804, 70876eb, d872d12, e86449a, abe4764, ee3bce8 ([`experiments/results-summary.md`](../experiments/results-summary.md) section 1) | 145 files under the tree pin (suite :140) | group 5 |
| Phase 15 derived tables and plots | [`experiments/derived/out/`](../experiments/derived/out/) | 08bbb9d629ef2f11eea98bdfbc699179c2f1f107 | 59 files under the tree pin (suite :141) | groups 5 and 7 |
| Agent-run records | [`docs/agent-runs/`](agent-runs/) phase-00 to phase-15, amendment-01, amendment-02 | population ends at the Phase 15 gate commit 9da45ab616a764e449f74f25feca85ed77d6dfe4 (suite :11) | 254 `.md` files (suite :184); phase-16 excluded as incomplete | group 6 |
| Release records | [`releases/course/`](../releases/course/) | written by the deploy script in Phases 13 to 15 | 7 records (suite :218) | group 7 |

Graph runs behind the data, as recorded in the sources: the benchmark labels come from the Phase 7 graph run (3r R2; suite :128). The stores in Phase 15 held Phase 14's graph run `run:v1:f2625d504b12749389cf4f5b1a26aed4` (results-summary section 0). The Phase 15 Spark A and B runs wrote `run:v1:0f02b5f22d1187932df0fc1decd7e6a5` (suite :105). Every result names the graph run of its source.

**Gone, not used:** the Phase 7 export and Neptune CSVs, the Phase 14 export (removed with the data bucket on Nov 21), and the Phase 5 ER run folder with its review queue, review key and er-v1 decision set (work order, "Where things stand"; S/agent-report-er.md group 1 plan). No graph export exists anywhere.

## 3. Versioning and freezing

- **Versioned inputs** (spec gate 2): benchmark-v1 (questions and relevance labels), the Phase 5 gold labels, [`rubric-v1.md`](../experiments/evaluation/rubric-v1.md), the rating-sheet generator (the `ratings-sheet` subcommand of `evaluate.py`), [`spark-suite-table-v1.csv`](../experiments/evaluation/spark-suite-table-v1.csv) and [`intended-differences-v1.csv`](../experiments/evaluation/intended-differences-v1.csv). Each carries a version and a sha256 in the suite (suite :19-56).
- **Approval:** the suite has no approval field. The human's line `APPROVED-EVALUATION-SUITE-SHA256: HEX64` in `docs/agent-runs/phase-16/approvals.md` (E3) is the only approval. Any change to the code, the suite, the rubric or an approved input after signing creates `suite-v2.json` with a new signed line. Runs made before the change stay in the record (decision 17, E3).
- **Freeze:** at E12 the human commits every raw output under `experiments/evaluation/raw/**`, including the completed ratings, and the regenerated derived outputs. The orchestrator records the freeze commit and the sha256 of every frozen file. After E12 no raw file changes, and a re-run writes a new run folder.
- **Run metadata:** each run writes `run.json` (tool sha256, suite sha256, entry, inputs and outputs with sha256), kept apart from the computed results (S/agent-report-experiment-X1.md interface handoff).

## 4. Methods by group

Each subsection gives the data, the metrics with their kind, the variability method and the known gap, taken from the suite entry. The results are in section 10.

### 4.1 Group 1: entity resolution

- **Plan:** the reduced plan of decision 3. The Phase 5 ER run folder, its review queue and key, and the er-v1 decision set are gone. So there is no `EvalMain` run at E4 and no comparison of er-v1 with er-v2 decisions (S/agent-report-er.md group 1 plan; suite :72).
- **Method** (suite :80): `evaluate.py er` reads the final Phase 5 `summary.md` that the committed Phase 5 report reproduces verbatim. It copies the researcher-resolution table as printed: all rows unweighted, the weighted estimate, and rows per matching path, per name family and per evidence class, each with its interval method. It also copies the weighted bootstrap line, the zero-event bounds and both confusion matrices. It then recomputes precision, recall, F1, coverage and unresolved rate for the all-rows row, with numerator and denominator from the unweighted confusion matrix, and compares them with the printed values at one decimal. Finally it checks the gold file's label counts and sha256.
- **Metrics:** precision, recall, F1, confusion matrix, coverage, unresolved rate, and metrics by matching path (decision 23). Kind (b) for the human's labels on er-v1 decisions; kind (a) for the recomputation and count checks (suite :81).
- **Variability** (suite :82): as printed in Phase 5. Wilson score 95 % for unweighted rows; stratified percentile bootstrap (seed 20261114, 1,000 resamples; `docs/agent-runs/phase-05/agent-report-er-eval.md:40`) for the weighted estimate, with its unreliable flags and zero-event bounds. Only point estimates are recomputed.
- **Known gap:** the labels judge er-v1 decisions. er-v2 accuracy is `not measurable from the frozen evidence`: equal counts are not equal decisions, and the er-v1 decision set is gone. Numerators and denominators exist only for the all-rows row. M3 and M4 are fixed in code in Phase 16 but not re-rendered, so the table keeps the Phase 5 wording (suite :83; S/agent-report-er.md risk 3).

### 4.2 Group 2: graph correctness

- **Method** (suite :105), in four parts:
  1. A recount of spark-engineer's suite table: per group 2 check (degree, weighted degree, components, paths, communities, bridges, k-core, integrity, duplicates, orphans), the tests asserting it, split by how the expected value was obtained (worked out by hand, mixed, or a property of the code's own output).
  2. The invariant checks of the Phase 15 `spark-outputs.csv`, computed once per distinct file content.
  3. For each DynamoDB sample table, the share of sampled items that carry each provenance field of the `CLAUDE.md` provenance invariant (run ID, snapshot, dataset, source record IDs, policy version, schema version, item hash, content hash). Schema version counts only the `schemaVersion` attribute or record field, never the top-level `version` attribute, which is the storage layer's conditional-write item version.
  4. Optionally, the sbt summary line of the E5 run of the two Spark suites.
- **Kinds** (suite :106): (b) for hand-worked small graphs. The E5 sbt run shows whether those tests pass, and spark-engineer's classification says which expected values were worked out by hand. (a) for invariant counts and provenance-field presence.
- **Variability** (suite :107): counts and descriptive proportions with numerator and denominator. **No confidence interval**: the DynamoDB samples were not drawn at random, and the two `spark-outputs.csv` files are one observation.
- **Known gap** (suite :108): full-graph referential integrity, duplicate rate, orphan rate and coauthor-edge support rate are `not measurable` beyond what the invariant counts cover, because no export exists. The invariant table names what each count measures (S/agent-report-spark.md invariant table). Duplicate edge rows, edge support and per-check integrity counts are listed there as not measurable from the CSV.

### 4.3 Group 3: retrieval

- **Method** (suite :125): recomputed from experiment E's `targetRanks`. For each universe question and each mode (graph-only, vector-only, fused), recall@K for K = 1, 5, 10, 30 is the number of labelled targets ranked at or above K divided by the question's labelled targets. Reciprocal rank is 1 / best labelled rank, or 0 if none. Means are taken over questions. The tool also checks that its recomputation equals the report's own numbers.
- **Universe:** 11 questions with 100 labelled researcher IDs (report lines 48-49). The same 11 questions and labels are the denominator for every mode. 19 further questions are labelled only with works, paths or numbers; they are `not-comparable` and are excluded from every mode by Phase 15's design (decision 3).
- **Kind:** (a), with label caveats (suite :126).
- **Not measured:** precision@K is `not measurable (unjudged retrievals)`. nDCG is not computed (no graded labels). No OpenSearch tier exists.
- **Variability** (suite :127): percentile bootstrap over questions, paired across modes and metrics, seed 20261122, 2,000 resamples, nearest-rank 2.5 % and 97.5 %. Every table is headed **EXPLORATORY (n = 11)** and is never read as generalization.
- **Known gap** (suite :128): see threats T1 and T2. Fused ranking is experiment-only (`fusion-exp-v1`; results-summary section 7, deviation 4).

### 4.4 Group 4: answer quality

- **Sources, never pooled** (suite :168). Each source is labelled with its code version, prompt set and graph run, and with the origin of each label (from the file, from an argument, or not available):
  - Phase 10 attempt-06: aggregated claim counts only; abstention fields; planted-error counts.
  - D-query explains: one row per explain read; an `abstained` column; no verification counts or codes.
  - Clip 01's query audit: per-answer verification counts, abstained flag and abstention codes for two attempts at one question.
  - The E7 demo answers, if copied: per-claim statuses.
- **Metrics** (S/agent-report-experiment-X2.md interface handoff):
  - verifier-reported claim support rate (verified ÷ all claims) and contradiction rate. The verifier's contradiction status is named `conflicting` (S/agent-report-rag.md field table);
  - shares of the other statuses;
  - abstention rate, abstention recall on the questions benchmark-v1 labels to abstain, and false-abstention rate;
  - abstention codes;
  - for attempt-06: answer-key agreement per claim (`not available`: the report holds no per-claim values); answer key against the evidence bundle (retrieval agreement, not claim accuracy); planted-error catch rate (a test of the verifier);
  - breakdown by claim or evidence type (coauthor-edge, graph-path, supporting-publication, identifier, number), `not measurable` wherever the frozen fields hold no type.
- **Kinds:** every verifier-derived and answer-key value is (a). The answer key is a deterministic generator over the same Phase 7 graph: independent of the model, not of the pipeline's data. The optional "named people and papers exist on dblp.org" column on the rating sheet is (b). The ratings are (c).
- **Human ratings** (E11, suite :248-270): rubric-v1 rates usefulness and clarity from 1 to 5, with an anchor sentence per score and a "not rateable" option. The sheet shows only the question and the answer text, never a verification status. At most 20 answers, in an order set by the suite's seed. Only the E7 demo answers hold answer texts: attempt-06 is aggregated and clip 01 holds only an answer hash (suite :253, :261-264). Without E7, the rating result is `not measurable from the frozen evidence (no answer text)`.
- **Variability** (suite :170): claim rates use a percentile bootstrap over answers (ratio of sums, seed 20261122, 2,000 resamples), only where per-answer counts exist; otherwise the result is written `no dependence-aware interval`. Claims within one answer are not independent. Answer-level rates (abstention, the dblp.org check) use Wilson score 95 %. Clip 01's two attempts answer one question and are not independent. The ratings have no interval (one rater; n is reported).
- **Known gap:** independent factual accuracy of answers is `not independently measurable`, except through the optional dblp.org existence check (decision 3; suite :171).

### 4.5 Group 5: performance and elasticity

- **Method** (suite :144): copies the Phase 15 derived summary tables verbatim. It then computes, from the raw folders, the by-hand totals of `results-summary.md` section 8 as a script-produced table:
  - Spark task, stage, shuffle and spill totals;
  - EMR step durations;
  - Hadoop map splits and per-family output records;
  - instance-seconds and an estimated EMR cost;
  - per-route request classes of both D runs;
  - totals of every CloudWatch Sum series;
  - per-period worker throughput grouped by running task count.
- **Metrics:** throughput, p50, p95, p99, errors, scaling delay, recovery time, estimated cost.
- **Kind:** (a), measured by the system's own tools and CloudWatch. Costs are **estimates**, from the rate 0.240 USD per instance-hour taken from `infra/terraform/COSTS.md` (EC2 0.192 + EMR 0.048; itself marked unverified; suite :138).
- **Variability:** percentiles with sample counts, as derived in Phase 15. Periods below 20 samples are marked "few samples". No new runs are made.
- **Known gap:** the Phase 15 limitations list applies (threat T11).

### 4.6 Group 6: agentic software factory

- **Method** (suite :187): the extractor counts deduplicated canonical events in the agent-run records:
  - a finding is counted once, under its ID in the review report that raised it;
  - an agent execution is phase + step + agent + attempt;
  - a repair is one `repair-*.md` file;
  - BLOCKED-ON is two numbers: terminal BLOCKED-ON reports, and summary or runbook rows deduplicated by phase, step and agent (an empty step matches any step). A row that matches a terminal report, or an earlier row in source precedence (summaries, then runbook logs), is marked `duplicate_of` and not counted. Rows naming no agent are counted;
  - first-pass acceptance is an execution with no repair and no blocking or major finding against it;
  - manual interventions, hook blocks, ownership violations and human rewrites come from the gate's or summary's own list rows (list items and table body rows, never prose). A row repeating a still unmatched row of the same phase, event type and agent in a higher-precedence file (the gate, then summaries in file order) is marked `duplicate_of` and not counted, one to one.
  
  Rows the rules cannot place go to `unclassified.csv`, never guessed. Session 2's repair round changed the extractor's deduplication as described above (review finding P16-B-02; S/repair-01-experiment-engineer.md); the frozen method is the one in the signed suite.
- **Audit:** a fixed-seed sample of 20 counted events (suite :181), stratified by event type. The human checks each event for being real and correctly classified (E11). The audit measures classification agreement, not completeness of extraction.
- **Kind:** (a), the project's own records, audited by (c) (suite :188).
- **Variability:** counts with denominators. No interval: the records are a full population, not a random sample. The audit's agreement rate is reported (suite :189).
- **Known gap** (suite :190): report formats changed over the phases. Findings outside markdown tables, the prose step log of phase 11, and per-agent first-pass attribution at phase granularity are limits of the rules.

### 4.7 Group 7: reproducibility

There are four separate results plus one record check, never merged:
1. **Clean-checkout build and tests of the current commit** (E2): `scripts/check.sh` in a fresh local clone. The orchestrator records the tested SHA. If a later commit changes a `check.sh` input, E2 is repeated at the final HEAD (decision 17).
2. **Historical regeneration** (E10, suite :193-207): the five derive scripts in a fresh clone at 08bbb9d629ef2f11eea98bdfbc699179c2f1f107, compared file by file by sha256 with the committed outputs. Every expected file must be regenerated and byte-equal, with no intended-differences list. An empty or missing regenerated folder is a failure.
3. **Current-version regeneration** (E10, suite :230-246): the current derive scripts in this working tree. Each difference is marked intended if it is on `intended-differences-v1.csv` (8 files changed by AB-4, J-03 and L-08: 8 data lines plus a header in that file), else unexplained.
4. **Bounded pipeline** (E5): a fresh local Hadoop pipeline run, as Phase 15 clip 19c ran it, plus the Spark fixture suites. Phase 14's and Phase 15's committed AWS runs are cited as historical evidence only.
5. **Deployment from approved plans** (E10, suite :209-228): per release record, its plan checksum against the signed `APPROVED-PLAN-SHA256:` lines, and what the record itself says about the deploy run. This is worded as record consistency; `deployment not independently verified` applies wherever the record lacks execution evidence.

Kind (a) throughout. Variability: exact match or listed differences. No new cloud deployment is possible.

## 5. Variability methods (summary)

| Method | Where | Parameters (source) |
|---|---|---|
| Wilson score 95 % | group 1 unweighted rows (as printed); group 4 answer-level rates | z = 1.959964 (`evaluate.py:198`, `wilson`) |
| Stratified percentile bootstrap (Phase 5, as printed) | group 1 weighted estimate | seed 20261114, 1,000 resamples (`agent-report-er-eval.md:40`; suite :82) |
| Percentile bootstrap over questions, paired across modes | group 3 | seed 20261122, 2,000 resamples, nearest-rank 2.5 % / 97.5 % (suite :118-119, :127) |
| Percentile bootstrap over answers, ratio of sums | group 4 claim rates, only where per-answer counts exist | seed 20261122, 2,000 resamples (suite :158-159, :170) |
| Percentiles with sample counts | group 5 | "few samples" below 20 (suite :146) |
| Counts and descriptive proportions, no interval | groups 2, 6 and 7 | non-random samples, full populations or single observations (suite :107, :189, :205) |

No repeated live runs are feasible (there is no AWS environment after Nov 21), so no interval describes run-to-run variation (decision 23).

## 6. Independence (spec gate 1)

- No evaluation value is produced by the prompt or model that produced an answer. No LLM judge is used.
- Independent evidence (b) is only: the human's gold labels, made before the matcher saw them (decision 3); the optional dblp.org existence check (E11).
- The human's ratings (c) are made blind to verification statuses: the sheet hides them, and the rubric tells the rater not to look (suite :266; `rubric-v1.md:8`).
- The verifier and the answer key are code. They are reported as internal consistency (a), never as accuracy.
- code-reviewer reviews the final version of this document against the frozen outputs (E13).

## 7. Threats to validity

Each threat names the evaluations it affects. None is resolved by this draft.

- **T1. Benchmark labels from the pipeline's own rankings.** benchmark-v1's relevance labels were generated from the Phase 7 graph's own rankings, so graph-only retrieval agrees with them partly by construction (groups 3, 4; suite :128).
- **T2. Labels from a different graph run.** The labels come from the Phase 7 graph run, while Phase 15 ran against the deployed Phase 14 graph (3r R2). A labelled researcher or path may be absent from the deployed graph. This data drift is also the likely underlying cause named in the G-28 statement (S/agent-report-rag.md, G-28 final statement). Groups 3, 4.
- **T3. Small retrieval universe.** Experiment E's comparable universe is 11 questions, below 15. Every retrieval table is exploratory and is never read as generalization (group 3).
- **T4. Answer accuracy not independently measurable.** Verifier statuses and answer-key agreement are internal consistency. Only the optional dblp.org existence check is independent, and it checks existence, not correctness (group 4).
- **T5. One human rater.** There is no inter-rater agreement (planned deviation 6). The ratings are the judgment of one person, and n is small; it is reported (group 4).
- **T6. er-v1 gold labels against er-v2.** The gold metrics are Phase 5's labels on er-v1 decisions. The deployed code applies er-v2, and er-v2 accuracy cannot be measured from the frozen evidence (planned deviation 5; group 1).
- **T7. No live system after Nov 21.** Phase 15 removed the AWS environment. Every Phase 16 code fix (FECD-1, L1, L2, M3, M4, N-1 and others) is `fixed in code, tested offline, not validated live`. Measured Phase 15 values describe the code of build 684636ef, not the Phase 16 code (planned deviations 2, 3; all groups).
- **T8. FECD-1.** Under 5 or more concurrent clients, the Phase 15 graph neighbors route returned 502 and then 503 `CIRCUIT_OPEN`. The Phase 16 diagnosis attributes the Neptune errors most plausibly to the instance's concurrent-query limit. It attributes the rejections to the shared circuit breaker, and the lack of recovery to the missing graph-read retry and the dropped error class. The Neptune-side cause is not decidable from the frozen evidence (S/agent-report-storage-D.md, section 2 summary). The load shape (a synchronized burst after 60 s idle at each level) means steady-state Neptune behavior under concurrency was never measured (same report, f3). Group 5 D-read results describe the old code. The fixes are not validated live (T7).
- **T9. FECD-3: explains show the latest execution only.** Explain is keyed by a deterministic query ID per question, so per-stage latencies and explain rows show only the latest execution of a question (`agent-report-experiment-FECD.md:477`). This affects D-query rows in groups 4 and 5.
- **T10. Non-random DynamoDB samples.** The 25-item samples and clip 01's 4 RunLedger items were copied by hand, and the selection method is not recorded. Provenance shares are descriptive, per table, with no confidence interval (groups 2, 4).
- **T11. Phase 15 limitations** (`experiments/results-summary.md` section 7, carried as listed there):
  - A: no requested EMR series by design (maximum = minimum sends no managed-scaling policy); sparse CoreNodes points, cause not determined.
  - B: confounded and n = 1 (partitions and maximum changed together; one run each; clusters overlapped). B is observational, and compared only with A-Spark.
  - A's Spark half and B read Phase 14's export and ER output at a different build; A's two halves are not one chained run.
  - Hadoop shuffle counters and reduce task counts are not logged; map tasks are planned splits.
  - Collector field misreadings AB-1 to AB-4 (raw unchanged).
  - Step-end to Step Functions lag (AB-5, cause not determined).
  - D: several series have no datapoints (not measured zeros); Neptune latency not separable; vector-query latency has 7 samples.
  - Task counts from 15 s samples (Container Insights off).
  - Clock resolution of 1 s, and CloudWatch 60 s periods.
  - C's re-embed rate covers the re-embed path only.
  - No Neptune CPU alarm.
  - Per-experiment costs are estimates from unverified rates.
- **T12. FECD-6: C's drain time under concurrent enqueue.** Experiment C measured drain while messages were still being sent, so the drain time is bounded by the send duration. It is not a pre-filled-backlog drain (`agent-report-experiment-FECD.md:480`). Group 5.
- **T13. FECD-8: Neptune metric alignment.** The Neptune per-minute series differ from the tool's minutes by up to one period, so Neptune values are attributed to load levels with that caveat (`agent-report-experiment-FECD.md:482`). The Phase 16 diagnosis refines this: the lag is tens of seconds and not constant, and Neptune documents 5-minute aggregation for some metrics (S/agent-report-storage-D.md M3, M6). Group 5.
- **T14. FECD-9: worker counters not exported.** The worker's in-process counters (U-11) are not exported. Exactness of the C re-embed run rests on log lines, SQS counts and DynamoDB counts agreeing, not on a counter (`agent-report-experiment-FECD.md:483`). Group 5.
- **T15. Answer key and verifier share the pipeline's data.** The attempt-06 answer key is generated from the same Phase 7 graph the answers were built from. Agreement with it shows consistency with that graph, not with the world (group 4).
- **T16. Code-version mix in group 4.** attempt-06 is rag-runtime/2 with Phase 10 prompts on the Phase 7 export with fake embeddings. D-query and clip 01 are Phase 15 code on the Phase 14 graph. Rates are never pooled across sources. Some labels (D-query prompt set, clip 01 code version) are not recorded in the frozen files and are reported `not available` (suite :171).
- **T17. Factory records were not designed for measurement.** Report formats changed across phases; the extractor's rules have known limits, and the 20-event audit checks classification, not completeness (group 6).
- **T18. Hand-copied inputs.** The group 2 suite table is spark-engineer's classification, copied verbatim. Whether an expected value was "worked out by hand" rests on the suite's own statement plus spark-engineer's spot checks (S/agent-report-spark.md assumptions). Groups 1 and 4 read values printed in committed reports, not recomputed from lost run folders.

## 8. Missing data

Each item is reported as `not measurable from the frozen evidence (REASON)` or `not available`, never filled.

| Item | Group | Reason |
|---|---|---|
| er-v2 accuracy on the gold pairs | 1 | er-v1 decision set, Phase 5 review queue and key are gone; no decision comparison is possible |
| Numerators and denominators for rows other than all-rows | 1 | the committed Phase 5 table prints percentages only |
| M3 and M4 wording in the group 1 table | 1 | fixed in code, but the Phase 5 summary cannot be re-rendered (inputs gone) |
| Full-graph referential integrity, duplicate, orphan and edge-support rates | 2 | no graph export exists; only the invariant counts of `spark-outputs.csv` |
| Per-check integrity counts | 2 | the CSV carries only the total; `_run_report.json` is not in the frozen data |
| Confidence intervals on DynamoDB provenance shares | 2 | samples not drawn at random |
| Precision@K | 3 | retrieved IDs outside the labels are unjudged |
| nDCG | 3 | no graded relevance labels |
| OpenSearch comparison | 3 | not implemented |
| Per-claim statuses, claim texts, numbers and identifiers | 4 | attempt-06 is aggregated; D-query has no claim columns; clip 01 has counts only |
| Answer-key agreement per claim | 4 | attempt-06 holds no per-claim values |
| Claim-type breakdown | 4 | no source stores a claim or evidence type per claim, except the cited evidence types in the E7 demo answers if copied; `identifier` is never measurable |
| Abstention codes for attempt-06 and D-query | 4 | codes began in Phase 15 (attempt-06 is rag-runtime/2); the D-query CSV has no code column |
| Verification counts for D-query explains | 4 | no column in the CSV |
| Human ratings, if E7 does not copy the demo answers | 4 | no other source holds answer texts |
| Independent factual accuracy of answers | 4 | not independently measurable beyond the optional dblp.org existence check |
| The attempt-07 live benchmark | 4 | not run: its inputs (the Phase 7 export) are gone |
| Any new performance run; Neptune steady-state behavior under concurrency | 5 | no AWS environment; Phase 15 load shape (T8) |
| Hadoop shuffle and reduce counters; scale-in end status of C (FECD-5) | 5 | not logged or not collected in Phase 15 |
| BLOCKED-ON incidence for phases without a summary step table or runbook log | 6 | never inferred from prose |
| Any new cloud deployment | 7 | no environment; deployment is assessed from historical release records only |

## 9. Negative results named in advance

These results stay in this document and in the submission whatever their size (spec gate "Negative results remain in the submission"; decision 23). They are named here before the freeze so that none can be dropped later:
- Experiment E's vector-only retrieval result and any mode with low recall or MRR (group 3).
- Experiment D's overload behavior and FECD-1 (graph route 502 and 503 under concurrency) (group 5).
- Failed attempts: A-Hadoop attempt 1, D-read attempt 2, and the D9a preflight failure (results-summary section 1). Also any failed or repaired run in session 3, which counts by its last attempt with every earlier attempt listed.
- Any group 1 metric the Phase 5 report flags as unreliable, with its zero-event bound.
- Any invariant count, provenance share or Spark suite test that fails or is not covered by hand-worked expectations (group 2).
- Any verifier status other than verified (partially supported, unsupported, conflicting), missed or false abstentions, and planted errors the verifier did not catch (group 4).
- Low human ratings and "not rateable" answers (group 4).
- Factory events: reviewer findings, repairs, BLOCKED-ON events, hook blocks, ownership violations, human rewrites, and unclassified rows (group 6).
- Any failed clean-checkout gate (pre-existing failures included), unexplained regeneration differences, release records without a matching signed plan, and `deployment not independently verified` (group 7).
- Every `not measurable` and `not available` item of section 8.
- Emulator `not-implemented` operations and `not demonstrated` demonstrations (see [`emulator.md`](emulator.md); not part of the seven groups).

## 10. Results

`RESULTS PENDING FREEZE (E12)` applies to every subsection below. Nothing is written here until the freeze commit exists. Then each subsection is filled from the frozen files only, each number with its file, tool and kind.

### 10.1 Group 1: entity resolution

RESULTS PENDING FREEZE (E12).

#### Measured facts

RESULTS PENDING FREEZE (E12).

#### Interpretation

RESULTS PENDING FREEZE (E12).

### 10.2 Group 2: graph correctness

RESULTS PENDING FREEZE (E12).

#### Measured facts

RESULTS PENDING FREEZE (E12).

#### Interpretation

RESULTS PENDING FREEZE (E12).

### 10.3 Group 3: retrieval (EXPLORATORY, n = 11)

RESULTS PENDING FREEZE (E12).

#### Measured facts

RESULTS PENDING FREEZE (E12).

#### Interpretation

RESULTS PENDING FREEZE (E12).

### 10.4 Group 4: answer quality

RESULTS PENDING FREEZE (E12).

#### Measured facts

RESULTS PENDING FREEZE (E12).

#### Interpretation

RESULTS PENDING FREEZE (E12).

### 10.5 Group 5: performance and elasticity

RESULTS PENDING FREEZE (E12).

#### Measured facts

RESULTS PENDING FREEZE (E12).

#### Interpretation

RESULTS PENDING FREEZE (E12).

### 10.6 Group 6: agentic software factory

RESULTS PENDING FREEZE (E12).

#### Measured facts

RESULTS PENDING FREEZE (E12).

#### Interpretation

RESULTS PENDING FREEZE (E12).

### 10.7 Group 7: reproducibility

RESULTS PENDING FREEZE (E12).

#### Measured facts

RESULTS PENDING FREEZE (E12).

#### Interpretation

RESULTS PENDING FREEZE (E12).

### 10.8 Spec gate items (checked at E15)

RESULTS PENDING FREEZE (E12). Four items, each to be stated `met (evidence)` or `not met`: evaluation data not generated by the answering prompt; benchmark questions and labels versioned; facts separated from interpretation; negative results kept.

## Sources not yet in the repository

Some references above point to Phase 16 session 2 reports that are in the session scratchpad at draft time. The human copies them to `docs/agent-runs/phase-16/` under the same names. "S/" in the text stands for that folder. They are not linked here because the files do not exist in the repository yet: `agent-report-experiment-X1.md`, `agent-report-experiment-X2.md`, `agent-report-er.md`, `agent-report-rag.md`, `agent-report-spark.md`, `agent-report-storage-D.md`. E13 replaces each "S/" reference with a link once the copies exist.
