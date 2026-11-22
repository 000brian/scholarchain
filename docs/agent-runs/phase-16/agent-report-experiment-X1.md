# agent-report-experiment-X1

STATUS: COMPLETE
OWNER: experiment-engineer
WORK ORDER: Phase 16 session 2 step X1, docs/agent-runs/phase-16/prompt.md (sha256 c75cc5f895faed13880a69ec285231c5498fd50924c7b875fe7e02cce1707dc8), HEAD 9da45ab616a764e449f74f25feca85ed77d6dfe4; decision 13 shared rules and X1 items 1-8 in tier order. Wave closer: no.

FILES CREATED (all under /Users/brian/Developer/repos/scholarchain/experiments/evaluation/):
- evaluate.py (1,728 lines; sha256 4ecc9c7afba6f61924537f649a06e5177dc0ea3424f2a867258c41f58b0facc0)
- suite-v1.json (draft; sha256 a892b319cf849116b8a056b60ae3ce3e58cbfd0e932f9cb16972cf1459f7eaff at the end of X1; X2 changes it)
- rubric-v1.md (sha256 102478a0339ec3a0fd4cf0d3a805cdd418956075e9d77a599d73c7e17459d012)
- intended-differences-v1.csv (sha256 e1782852ea5f29bc8f17b3a2a8519f42cfad0b2c5b4d000c7b84e9edc6f5fab5)
- fixtures/** : 112 files, 18 cases: retrieval/{happy,malformed,missing}; performance/{happy,malformed,missing}; factory/{happy,malformed,missing}; reproduce/{happy,differences,empty-regenerated,malformed,missing,release-records}; ratings-sheet/{happy,malformed,missing}. Each case: case.json (args with a {case} placeholder for its input/ folder, expectExit), input/, expected/ (absent where the case writes no result file).

FILES MODIFIED:
- experiments/derived/common.py (time_chart: optional font_size, width, right; defaults reproduce the Phase 15 bytes)
- experiments/derived/emr_capacity.py (AB-4, J-03: Nodes metric lists, no Units names)
- experiments/derived/queue_worker.py (L-08: 14 px text, 1200 px width, 330 px legend)
- experiments/results-summary-template.md (line 75: Nodes metric names, J-03)
- experiments/README.md (layout rows for experiments/evaluation/**)

COMMANDS RUN: shasum -a 256 (work order, inputs, fixtures, tree digests via `git ls-files -- ROOT | xargs shasum -a 256 | shasum -a 256`); git rev-parse, git status --short [-uall|--ignored], git log, git diff --stat, git ls-files, git check-ignore -v; ls, wc, head, grep, cut; jq empty (33 literal paths). No python, bash script, sbt, aws-api or network call. Commands outside the list (all read-only, reported as an unplanned deviation): `cd REPO &&` prefixes in most Bash calls; one `cat` of a committed raw CSV (experiments/raw/p15-b-spark-burst/.../emr-instances.csv, through a `cat || head` fallback); two `awk 'NR>=a && NR<=b'` reads of orchestrator summaries; one `sed -n` and one `tr -d` inside read-only pipelines; `echo` in printouts. No file was written by any of them.

TEST RESULTS: none run (planned deviation 1: agents run nothing; the human runs the fixtures at E1). runtime: untested for evaluate.py and for the three changed derive scripts.

VALIDATION RESULTS: `jq empty` exit 0 on all 33 JSON files I wrote (32 fixture JSON files and suite-v1.json). Pinned hashes re-checked with shasum after the last edit (evaluate.py, rubric, intended list unchanged since pinned). `git check-ignore -v` shows the fixture `.gitkeep` is not ignored (matched by `!experiments/**`, .gitignore:83).

ASSUMPTIONS:
- The E report's `targets` and `targetRanks` define the labelled set (the report holds no target IDs); retrieval recomputes recall and RR from targetRanks and checks against the report's own numbers.
- Answer formats for ratings-sheet are unknown until rag-engineer's field table (step 4); the tool takes field paths per file, and X2 fills the E11 arguments.
- The instance-hour rate 0.240 USD comes from infra/terraform/COSTS.md (EMR row: EC2 0.192 + EMR 0.048; marked estimate, unverified there).
- Seed 20261122 for every seeded evaluation (my choice; any fixed value works, the human may change it before signing).

RISKS:
- Factory extraction is rule-based over heterogeneous report formats: findings outside markdown tables, phase 11's prose step log, and BLOCKED-ON mentions in summary bullets that repeat a step-table row (counted once against a terminal report, but a second unmatched mention counts) are known limits; unclassified.csv and the 20-event audit expose them, nothing is guessed.
- The retrieval happy fixture's bootstrap expectations rely on n = 2: the 2.5 %/97.5 % nearest-rank quantiles of 2,000 resample means equal the minimum and maximum of the two values unless fewer than 50 of 2,000 draws hit an extreme (expected about 500). If E1 shows a ci_low/ci_high mismatch only, look there first.
- Tree pins equal the git command only on a clean tree with no untracked non-dot files under the roots. E6-performance must run before E10 regenerates experiments/derived/out (its derived-out pin then differs).
- Any edit to evaluate.py changes two suite pins (evaluationCode and the rating-sheet generator); X2 must update both.
- L-08 label size: chosen sizes not visually checked (no run); the human sees the plots at E10.

MANUAL ACTION REQUIRED: none in session 2. At E1 the human runs the five fixture commands below; at E3 signs the suite after X2 completes it.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: R-2 (code-reviewer traces every fixture through evaluate.py), then X2.

## Items (decision 13, X1)

| Item | Tier | Status | Where |
|---|---|---|---|
| Shared rules (one file, stdlib only, outputs, prints, run.json apart, rounding, --fixtures, exits) | 1 | done | evaluate.py docstring and driver |
| 1 Suite draft | 1 | done (X1 entries ready; E4-er, E5-graph, E8-answers are X2 placeholders; E11 answer-file specs left for X2) | suite-v1.json |
| 2 retrieval | 1 | done | cmd_retrieval |
| 3 performance | 1 | done | cmd_performance |
| 4 factory | 1 | done | cmd_factory |
| 5 reproduce incl. --release-records | 1 | done | cmd_reproduce, release_records |
| 6 rubric + ratings-sheet | 1 | done | rubric-v1.md, cmd_ratings_sheet |
| 7 fixtures (happy, malformed, missing per subcommand + branch fixtures: release-records, intended vs unexplained, empty regenerated) | 1 | done (18 cases) | fixtures/** |
| answers, graph, er | (X2) | placeholders: exit 2 "built in step X2" | x2_placeholder |
| 8 AB-4, J-03, L-08 + intended-differences list | 2 | done (J-03's second part, ignoring points before readyEpoch, is proposed later) | derive scripts, intended-differences-v1.csv |

## E1 fixture commands (one per X1 subcommand; each must end `SUMMARY fixtures NAME: N of N passed`, exit 0)

```text
python3 -I experiments/evaluation/evaluate.py retrieval --fixtures
python3 -I experiments/evaluation/evaluate.py performance --fixtures
python3 -I experiments/evaluation/evaluate.py factory --fixtures
python3 -I experiments/evaluation/evaluate.py reproduce --fixtures
python3 -I experiments/evaluation/evaluate.py ratings-sheet --fixtures
```
Cases: retrieval 3, performance 3, factory 3, reproduce 6, ratings-sheet 3. answers, graph, er: X2 adds them.

## Real-run commands per X1 evaluation (exact text also in suite-v1.json `command`)

```text
E6  python3 -I experiments/evaluation/evaluate.py retrieval --report experiments/raw/p15-e-retrieval-modes/20261120T221402Z-6a6c31a06d60-b6c7/retrieval-eval-report.json --benchmark modules/rag/src/test/resources/benchmark/benchmark-v1.json --seed 20261122 --resamples 2000 --suite experiments/evaluation/suite-v1.json --entry E6-retrieval
E6  python3 -I experiments/evaluation/evaluate.py performance --raw-root experiments/raw --derived-root experiments/derived/out --instance-hour-usd 0.240 --rate-source 'infra/terraform/COSTS.md EMR row (EC2 0.192 + EMR 0.048 USD per instance-hour; estimate; unverified)' --suite experiments/evaluation/suite-v1.json --entry E6-performance
E9  python3 -I experiments/evaluation/evaluate.py factory --root docs/agent-runs --seed 20261122 --sample-size 20 --suite experiments/evaluation/suite-v1.json --entry E9-factory
E10 python3 -I experiments/evaluation/evaluate.py reproduce /tmp/p16-expected-out-ATTEMPT /tmp/p16-repro-ATTEMPT/experiments/derived/out --suite experiments/evaluation/suite-v1.json --entry E10-reproduce-historical
E10 python3 -I experiments/evaluation/evaluate.py reproduce --release-records --releases-dir releases/course --approvals-root docs/agent-runs --suite experiments/evaluation/suite-v1.json --entry E10-release-records
E10 python3 -I experiments/evaluation/evaluate.py reproduce /tmp/p16-current-expected-ATTEMPT experiments/derived/out --intended experiments/evaluation/intended-differences-v1.csv --suite experiments/evaluation/suite-v1.json --entry E10-reproduce-current
E11 python3 -I experiments/evaluation/evaluate.py ratings-sheet --answer-file 'PATH#items=DOTTED;id=DOTTED;question=DOTTED;answer=DOTTED' (one per source; specs from X2) --rubric experiments/evaluation/rubric-v1.md --seed 20261122 --max 20 --suite experiments/evaluation/suite-v1.json --entry E11-ratings-sheet
```
ATTEMPT is filled by the orchestrator. E6-performance must run before E10's current regeneration.

## Intended-differences list for E10 result 3 (X1 item 8)

experiments/evaluation/intended-differences-v1.csv names 8 files under experiments/derived/out: p15-a-hadoop/missing.csv, p15-a-spark/missing.csv, p15-b-spark-burst/missing.csv (4 Units "series not exported" rows each disappear; B keeps only its header); the three capacity-*.svg (first panel retitled "capacity (nodes)", legend lists the four Nodes series; B now draws TotalNodes*); p15-c-embedding-backlog/queue-20261120T225730Z-eb786c5275a3-ab26.svg and p15-f-poison-replay/queue-20261120T223908Z-e94df52ca8fb-b4c0.svg (L-08 label size). Every other derived file is expected byte-equal: attempts.csv keeps CoreNodesRunning as the classified series; time_chart's defaults reproduce the Phase 15 SVG text exactly (font 11, title 14, event labels 9, line step 14, width 1000, legend 230).

## INTERFACE HANDOFF

Files changed: as listed above. Thread-safety: single-threaded CLI. Limits: Python 3.7+ (datetime.fromisoformat), standard library only, no network, no subprocess.

Invocation: `python3 -I experiments/evaluation/evaluate.py SUBCOMMAND ARGS --suite experiments/evaluation/suite-v1.json --entry ID` (a real run needs both; the tool checks the entry's subcommand and every pinned file sha256 or tree digest before computing, exit 3 on a difference) or `SUBCOMMAND --fixtures` (no other argument). Exit 0 / 2 usage / 3 missing or differing input / 5 tool failure, malformed input or failed fixture. Output folder experiments/evaluation/raw/GROUP/RUN_ID/ with GROUP retrieval, performance, factory, repro, ratings, answers, graph, er; RUN_ID = UTC stamp-12-hex HEAD (read from .git, no git call)-4 hex. run.json: format scholarchain.evaluation-run v1, tool, toolSha256, toolVersion, subcommand, runId, startedUtc, endedUtc, argv, suite, suiteSha256, entry, inputs[{path, sha256}], outputs[{path, sha256}] (+ factory populationDigest, populationFiles; retrieval questions). Printed prefixes STEP, RESULT, WARN, ERROR, SUMMARY. Floats: at most 6 decimals, trailing zeros removed; booleans yes/no; missing = empty cell or "not measurable from the frozen evidence (REASON)".

Subcommand arguments and outputs:
- retrieval: --report PATH (scholarchain.rag.retrieval-eval-report), [--benchmark PATH], --seed INT, [--resamples 2000]. per-question.csv (label, question_id, category, mode, labelled_relevant, retrieved, judged_retrieved, unjudged_retrieved, recall_at_K..., reciprocal_rank, error, empty, by_design_weak, abstention_codes, matches_report, note); summary.csv (label, mode, metric [recall, mrr, precision, ndcg, unjudged_share], k, numerator, denominator, value, ci_low, ci_high, ci_method, inclusion_rule); checks.csv (label, check, mode, k, report_value, recomputed_value, equal). label = "EXPLORATORY (n = N)".
- performance: --raw-root, --derived-root, [--manifests CSV], [--derived-tables CSV], --instance-hour-usd FLOAT, [--rate-source TEXT]. computed.csv (manifest, attempt, measure, value, unit, source, rule), route-classes.csv, cloudwatch-sums.csv, minute-throughput.csv, throughput-by-tasks.csv, derived-values.csv (manifest, table, row, column, value), missing.csv.
- factory: --root, [--include CSV; default phase-00..phase-15, amendment-01, amendment-02], --seed INT, [--sample-size 20]. events.csv (event_id E00001.., phase, type [execution, finding, repair, blocked-terminal, blocked-row, manual-intervention, hook-block, ownership-violation, human-rewrite], detail, agent, step, attempt, finding_id, counted, duplicate_of, file, line, text), counts-by-phase.csv, counts-by-agent.csv, totals.csv (metric, numerator, denominator, value, inclusion_rule), unclassified.csv, audit-sample.csv (blank event_is_real, correctly_classified, auditor_note for E11), audit-strata.csv.
- reproduce EXPECTED_DIR REGENERATED_DIR [--intended CSV path,reason]: comparison.csv (path, status equal|different|missing|extra|absent, expected_sha256, regenerated_sha256, marking intended|unexplained|intended-not-observed, reason), summary.csv (counts, verdict). reproduce --release-records --releases-dir DIR --approvals-root DIR: release-records.csv, approvals.csv, summary.csv.
- ratings-sheet: --answer-file 'PATH[#items=DOTTED;id=DOTTED;question=DOTTED;answer=DOTTED]' (repeatable; defaults items = root list or the root object, id, question, answerText), --rubric PATH, --seed INT, [--max 20]. sheet.csv (answer_key R01.., question, answer_text, usefulness_1_to_5, clarity_1_to_5, not_rateable, dblp_names_exist, rater_note), key.csv, excluded.csv, summary.csv. Order: sha256 of "SEED:FILE_BASENAME:ITEM_ID".
- answers, graph, er: X2 placeholders, exit 2.
- Helpers ready for X2 inside the file: wilson(successes, n), bootstrap_means(series_by_key, n, seed, resamples), num(), Run.read_json/read_csv/write_csv/finish, setup_suite/check_pin, tree_digest.

Suite schema (scholarchain.evaluation-suite v1): suiteId, status, workOrder{path, sha256}, draftedAtCommit, factoryPopulationEndsAt, evaluationCode{path, version, sha256, run, fixtures}, versionedInputs[{name, path, version, sha256}] (benchmark-v1, gold labels, rubric-v1, rating-sheet generator, intended differences), treeDigestRule, outputRoot, exitCodes, evidenceKinds, evaluations[{id, step, group, groupName, subcommand, status, command, arguments, seed, inputs[{path, sha256} | {treeRoots, treeSuffix, files, treeSha256}], outputs, method, evidenceKind, variability, knownGap}]. Pins with a non-hex sha256 text (E7 demo answers: "argument path, sha256 recorded at run") are skipped by the tool.

Fixed interface used: output folders experiments/evaluation/raw/GROUP/RUN_ID/ (decision 4).

## Status lines

- runtime: untested: experiments/evaluation/evaluate.py; experiments/derived/common.py, emr_capacity.py, queue_worker.py (changed).
- Fix status (AB-4, J-03, L-08): fixed in code, tested offline: no (not run in this phase by an agent), not validated live.

## Proposed later

- J-03 second part: ignore EMR capacity points before readyEpoch when classifying a scale-out (changes classification logic; not needed for the Phase 15 data, where A is flat).
- Factory: treat several BLOCKED-ON mentions of one (phase, step, agent) episode as one row; parse prose step logs (phase 11).

## VALIDATION EVIDENCE

```text
jq empty <33 literal JSON paths under experiments/evaluation> -> jq exit 0
shasum -a 256 experiments/evaluation/evaluate.py -> 4ecc9c7afba6f61924537f649a06e5177dc0ea3424f2a867258c41f58b0facc0 (= suite evaluationCode.sha256)
shasum -a 256 experiments/evaluation/rubric-v1.md -> 102478a0339ec3a0fd4cf0d3a805cdd418956075e9d77a599d73c7e17459d012 (= suite pin)
shasum -a 256 experiments/evaluation/intended-differences-v1.csv -> e1782852ea5f29bc8f17b3a2a8519f42cfad0b2c5b4d000c7b84e9edc6f5fab5 (= suite pin)
tree pins (git ls-files -- ROOT | xargs shasum -a 256 | shasum -a 256): experiments/raw 145 files 98c2e97e...; experiments/derived/out 59 files 93b9bd89...; factory .md 254 files 9bee8d8a...; releases/course 7 files 23cc2c3f...
git check-ignore -v .../empty-regenerated/input/regenerated/.gitkeep -> matched by !experiments/** (not ignored)
files changed after these runs: no
```
