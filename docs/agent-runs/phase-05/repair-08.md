# Repair 08: entity-resolution-engineer, test-engineer findings F1-F5 (repairs 12-19)

Phase 5, run 2 of 2. Agent reports reproduced verbatim from the orchestrator session, in the order they were returned. The test-engineer report that raised F1-F5 is in test-report-02.md (report 1).

Human decision recorded at the gate: repair-19 (removal of the hardcoded "(no live model is called by this adjudicator)" parenthetical) is KEPT. Reason: it changes only the er/eval package (EvalReport.scala) and its test (EvalReportSuite.scala, "repair-19: the run context does not claim that no live model is called"), and that test fails without it.

---

STATUS: COMPLETE
OWNER: entity-resolution-engineer
WORK ORDER: Phase 5 run 2, repair round for test-engineer findings F1-F5 (docs/agent-runs/phase-05/prompt-02.md, decisions 5 and 6; run 1 command rules in prompt.md decision 21)
FILES CREATED: none in this round. EvalReport.scala and EvalReportSuite.scala were already new and untracked from repairs 08-11.
FILES MODIFIED:
- /Users/brian/Developer/repos/scholarchain/modules/entity-resolution/src/main/scala/org/scholarchain/er/eval/EvalReport.scala
- /Users/brian/Developer/repos/scholarchain/modules/entity-resolution/src/main/scala/org/scholarchain/er/eval/EvalMain.scala
- /Users/brian/Developer/repos/scholarchain/modules/entity-resolution/src/test/scala/org/scholarchain/er/EvalReportSuite.scala (8 new tests)
- Generated outputs, not source: .../attempt-02/run=9f96c70b5115294dcc9d3b2d1d4438b9/eval/metrics.json and eval/summary.md
COMMANDS RUN: shasum (start and end); wc; cat; ls; grep; find; tail/head (including `tail -f | grep -m1` to wait for the background sbt run); git status; git diff --no-index (read-only, to compare two scratchpad logs); sbt "entityResolution/testOnly org.scholarchain.er.EvalReportSuite" (several runs, one of them against temporary mutations); sbt "entityResolution/testOnly ..." "contractTests/testOnly org.scholarchain.contracts.er.EvalReportAdversarialSuite"; sbt "entityResolution/runMain org.scholarchain.er.eval.EvalMain ..." (the real evaluation: same arguments, absolute paths and note as before); sbt check (background). Rule deviation: my first command started with `cd /Users/brian/Developer/repos/scholarchain;` before shasum/wc. `cd` is not on this round's list. Every later command used absolute paths. No banned words appeared on any command line, and no AWS or network was used.
TEST RESULTS:
- Module EvalReportSuite: 14/14 pass (6 earlier tests plus 8 new).
- contractTests EvalReportAdversarialSuite: 20/20 pass (observed "Passed: Total 20, Failed 0").
- sbt check: "[success] Total time: 1958 s". Every suite reports 0 failed (65 suite runs). Contract tests (no AWS): suites=20, executed=273, passed=273, failed=0. markTestRun updated .claude/.last-test-run (module_tests=1049, contract_tests=273). The scalafmt check and ledger check passed.
VALIDATION RESULTS: The real gold-set evaluation was re-run and exited 0. Every metric number in summary.md is identical to the previous run; exactly 2 summary.md lines changed (details below).
ASSUMPTIONS:
- The previous summary.md (21cabf...) is the markdown printed in my earlier log /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/16abe0b8-72f1-47b5-a4aa-bd54f96d6e7b/scratchpad/eval-real.log. I checked this: the markdown extracted from that log hashes to exactly 21cabfdfcced99c2b3f0683fe155d7a0f95a3940b5d6a0624d07fc85de960f36. The same extraction from the new log hashes to the new summary.md sha. So the diff below is exact.
- The previous metrics.json (c291ee...) was overwritten and no copy was kept, so it was not diffed byte for byte. The changes listed below are what the code change implies, and I confirmed them by grep in the new file.
RISKS:
- metrics.json format additions (formatVersion still 1): runContext now has three new fields. `inputsGiven` holds booleans for policy, manifest, runReport and upstreamReport. `policyOverriddenFromRecorded` is true or false, or null without a manifest. `policyThresholds` holds the policy file's thresholds. Also, runContext.thresholds no longer falls back to the policy thresholds. Anything that reads runContext.thresholds without a manifest now gets null.
- Residual: a policy whose "rules" field is present but not an array would still throw a raw exception in runContext (`rs.arr`). This is not covered by any finding; I left it as is.
- Residual: missing optional files still fail with the JDK NoSuchFileException. That exception names the path but not the --role.
MANUAL ACTION REQUIRED: none. The human decides whether to accept the extra repair-19, which goes beyond F1-F5 (below).
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer review of this round, test-engineer re-run, then the human gate for Phase 5 run 2.

REPAIRS
F1 and F2 share one code change in llmPath, numbered as two repairs so each has its own test.

- repair-12 (F1). Cause: when no labeled llm row was decided, llmPath always said the adjudicator "never accepts or rejects, so every LLM band pair stays unresolved". That is false for a live adjudicator. Fix: what the adjudicator did is now stated only from the run report's llm.outcomes. No accepted or rejected count above 0: "records no accepted or rejected pair (outcomes: ...), so every pair sent to the adjudicator stayed unresolved...". Some accepted or rejected: "records accepted or rejected pairs (outcomes: ...), but none of them is a labeled llm-path row...". Outcomes missing: "not available". With zero labeled llm rows the statement begins "...the gold set has no labeled llm-path row." The adjudicator's name is never used to infer behaviour. Test: "repair-12: the LLM statement follows the run report, not a claim that it never decides".
- repair-13 (F2). Cause and fix: same as repair-12. Without a manifest or run report, the statement now says the behaviour is "not available (run report LLM stage outcomes not given)". Test: "repair-13: without manifest and run report the adjudicator's behaviour is not asserted".
- repair-14 (F3). Cause: `atOpt(p,"weights").map(_.arr.toList).getOrElse(Nil)`. Fix: Option, which is null when "weights" is absent or not an array. weightedFeatures and recordedFeaturesWithoutWeight are both null then. The Markdown now says "Feature definitions not available: the policy file has no 'weights' list." when a policy is given without weights. Test: "repair-14: a policy without 'weights' gives not available, never an empty list".
- repair-15 (F4). Cause: `.getOrElse(Nil)` for knownRecallGaps. Fix: Option, which is null when absent or not an array. The Markdown says "- Known recall gaps: not available (the run report has no 'knownRecallGaps' list)." when a run report is given without the field. Test: "repair-15: a run report without 'knownRecallGaps' gives not available, never no gaps".
- repair-16 (F5). Cause: `.replace("not available", "none")` in runContextMd. Fix: "none" appears only when the manifest records policyOverriddenFrom as null. A manifest that lacks the field gives "not available (the run manifest does not record it)". No manifest gives "not available (run manifest not given)". Test: "repair-16: the policy override is 'none' only when the run manifest records it".
- repair-17 (optional note a). Cause: runContext.thresholds fell back to the policy thresholds, so the "(run)" sweep row was marked from the policy without a manifest. Fix: thresholds now come from the manifest only. Policy thresholds are reported apart as policyThresholds. The Markdown line reads "run thresholds not available (...); policy file thresholds ...", and no "(run)" row is marked. Test: "repair-17: without a manifest the policy thresholds never stand in for the run's".
- repair-18 (optional note b). Cause: malformed optional JSON threw a raw upickle TraceException. Fix: EvalMain wraps the parse for work-summary, policy, manifest, run-report and upstream-report, and throws IllegalArgumentException("--<role> <path>: not valid JSON (...)"). Nothing is written. Test: "repair-18: a malformed optional JSON input names its role and path".
- repair-19 (extra, not requested; same class of defect as F1). Cause: runContextMd always printed "(no live model is called by this adjudicator)" whatever the adjudicator was. Fix: the parenthetical is removed. Test: "repair-19: the run context does not claim that no live model is called". If you want this held to F1-F5 scope, it can be reverted on its own.

Failing-without-fix evidence: I made one temporary mutation run with all 8 fixes reverted at once. They are independent code paths, and each test checks only its own path. Result: "Failed: Total 14, Failed 8, Passed 6". Each new test failed on its own assertion: repair-12 and 13 on the "never accepts or rejects" text; 14 and 15 on [] instead of null; 16 on "overridden from: none"; 17 on non-null thresholds; 18 on "TraceException is not a subtype of IllegalArgumentException"; 19 on the "(no live model is called...)" line. After the revert: 14/14 and 20/20 pass.

er-v1.json sha256: start 4946b3504759d5f9a61f60646e6ca9d6cd27b0dd6ef26e57a1729c2fb32807ac / end 4946b3504759d5f9a61f60646e6ca9d6cd27b0dd6ef26e57a1729c2fb32807ac (unchanged)
gold-labels.csv sha256: start fc39c15fba1ab7b8cb2ed8337a439683dce4e457ed52d08b294cfb2feba71fa9 / end fc39c15fba1ab7b8cb2ed8337a439683dce4e457ed52d08b294cfb2feba71fa9 (unchanged; read only)

Src files changed this round: EvalReport.scala, EvalMain.scala (main); EvalReportSuite.scala (test). Harness.scala was not changed this round; it shows as modified only from repairs 08-11. Nothing was changed in tests/**, modules/core, modules/hadoop, er-v1.json, or docs/agent-runs.

Evaluation outputs:
- metrics.json: old c291eddbeab98732530f48b33411b6fbf1fc87739dcf06b23d45cfbabca63238 -> new 57ea4311b8e9cfefa7fdc4367a424d1f057d2c1e94acef5432c4f1da5c0d947a. Changes: runContext gained inputsGiven (all true), policyOverriddenFromRecorded (true) and policyThresholds; researcherResolution.llmPath.statement has the new text. runContext.thresholds is the same, because it came from the manifest before too. All metric values are unchanged, which is what the identical summary.md tables show.
- summary.md: old 21cabfdfcced99c2b3f0683fe155d7a0f95a3940b5d6a0624d07fc85de960f36 -> new 5b97d3b26573d98a619f3234a094faac7b97bb2634bc7480efb8881503764cee.

summary.md diff: exactly 2 lines changed. Every metric number, table, interval, confusion matrix, threshold sweep and error row is identical.
1. Run context, "- LLM stage:" line: "model id none/always-abstain (no live model is called by this adjudicator); prompt ..." became "model id none/always-abstain; prompt ..." (repair-19).
2. LLM-adjudicated path statement: "...with model id 'none/always-abstain', which never accepts or rejects, so every LLM band pair stays unresolved and precision and recall of LLM decisions cannot be estimated in this phase." became "...with model id 'none/always-abstain'. The run report's LLM stage records no accepted or rejected pair (outcomes: unresolved 1000), so every pair sent to the adjudicator stayed unresolved and precision and recall of LLM decisions cannot be estimated in this phase." (repairs 12 and 13).
"overridden from: none" is unchanged, because the real manifest records policyOverriddenFrom: null. The thresholds line, the "(run)" row and the recall-gap line are unchanged, because those inputs are present.

POLICY VERSION: er-v1 (frozen; sha256 unchanged). THRESHOLDS: accept 0.9, reject 0.3, llmBandLower 0.6, llmMinConfidence 0.8, nearThresholdLogit 0.5 (from the manifest). METRICS (unchanged): unweighted P 98.9% / R 83.0% / F1 90.3% / coverage 58.3% / unresolved 36.3%; weighted P 100.0% / R 95.3% / F1 97.6% / coverage 92.5% / unresolved 4.3%.

FULL NEW summary.md (verbatim):

# Entity-resolution gold-set evaluation

Note: Phase 5 run 2: gold-set evaluation of ER attempt-02 under frozen policy er-v1. Scope: dblp-subset-v1 (all 16 venues of the subset, dblp snapshot 2026-11-01). Gold labels: the human architect's file, read only.

Labeled rows: 300; unlabeled queue rows: 0.

## Gold set

- Review-queue rows: 300; rows in the gold file: 300; queue rows absent from the gold file: 0.
- Labeled rows: 300 (MATCH 112, NON_MATCH 135, UNSURE 53); unlabeled rows: 0.
- UNSURE labels are excluded from precision, recall and coverage and counted here: 53. They stay in the unresolved-rate denominator (all labeled units).
- Distinct labelers in the gold file: 1. Labels are human decisions read from the human-owned gold file; the harness never writes it.

## Inputs (read only)

| Role | Path | sha256 |
|---|---|---|
| gold | /Users/brian/Developer/repos/scholarchain/docs/agent-runs/phase-05/gold/gold-labels.csv | fc39c15fba1ab7b8cb2ed8337a439683dce4e457ed52d08b294cfb2feba71fa9 |
| queue | /Users/brian/Developer/repos/scholarchain/modules/entity-resolution/target/phase-05-real/attempt-02/run=9f96c70b5115294dcc9d3b2d1d4438b9/review-queue/review-queue.jsonl | 25a43e0e069eae78ec8b40228f608eaf0762adf61e005dca19bb3b4d0c6a84e9 |
| key | /Users/brian/Developer/repos/scholarchain/modules/entity-resolution/target/phase-05-real/attempt-02/run=9f96c70b5115294dcc9d3b2d1d4438b9/review-queue/review-key.jsonl | 89a1da5d31dc7d068bd34cfb23a3cea947160e0c190700fcbc89582d96889e04 |
| work-summary | /Users/brian/Developer/repos/scholarchain/modules/entity-resolution/target/phase-05-real/attempt-02/run=9f96c70b5115294dcc9d3b2d1d4438b9/work_resolution/summary.json | e55935b92b290bf93a7e0540b0e9666d69c01eb6f0b4af8a4002bf9e2ef6e0a1 |
| policy | /Users/brian/Developer/repos/scholarchain/modules/entity-resolution/src/main/resources/er/policy/er-v1.json | 4946b3504759d5f9a61f60646e6ca9d6cd27b0dd6ef26e57a1729c2fb32807ac |
| manifest | /Users/brian/Developer/repos/scholarchain/modules/entity-resolution/target/phase-05-real/attempt-02/run=9f96c70b5115294dcc9d3b2d1d4438b9/_manifest.json | e21e98b3be36b3164021eca48b84011c4f5e2d6c0a0006319805c7a838d91255 |
| run-report | /Users/brian/Developer/repos/scholarchain/modules/entity-resolution/target/phase-05-real/attempt-02/run=9f96c70b5115294dcc9d3b2d1d4438b9/run-report.json | 7b33dac9da4b67aaf3c2d920f9b0067b513f2aa229b391b6c1f25dc802760430 |
| upstream-report | /Users/brian/Developer/repos/scholarchain/modules/hadoop/target/phase-05-real/attempt-02/run-report.json | 7fc7f7ebf0ff20b5efe72a985d6edea3a27a5dd228d15e2b478cf0b7ace2bf1b |

## Run context

- Policy: er-v1 (status final); policy sha256 recorded in the run manifest: 4946b3504759d5f9a61f60646e6ca9d6cd27b0dd6ef26e57a1729c2fb32807ac; overridden from: none.
- Thresholds: llmBandLower 0.6, reject 0.3, nearThresholdLogit 0.5, llmMinConfidence 0.8, accept 0.9; prior log-odds -1.
- LLM stage: adjudicator always-abstain, model id none/always-abstain; prompt er-adjudication-prompt/1 (sha256 2b1a999a3fcc79297f75f5f36b3d1fc32ec56415ac688a667e27aedc974938fd); output schema er-adjudication-output/1 (sha256 2c96d80586583dfd8ea41f407dddcbf7611b95b1dc64f2262a069647b16dc431).
- Run: run:v1:9f96c70b5115294dcc9d3b2d1d4438b9, code version phase05-1279c37-02-dirty, decision time 2026-11-14T00:00:00Z; name key author-name-key/1; candidate feature set candidate-features/1-provisional.
- Input run: hadoop.j09-graph-export run:v1:32bda243c0ffba15037d61992f360e3e, snapshot dblp-2026-11-01.
- Scope (run report inputs): venues 16, works 155978, authorMentions 758328, dblpNames 201074, matchCandidates 16823178, mentionsWithCandidates 669665, mentionsWithoutCandidates 88663.

## Researcher resolution

Definitions (positive = decision accepted and label MATCH): precision = accepted MATCH / accepted labeled MATCH or NON_MATCH; recall = accepted MATCH / all MATCH (a MATCH left rejected, unresolved or conflict is missed); F1 = harmonic mean; coverage = accepted or rejected / labeled MATCH or NON_MATCH; unresolved rate = unresolved / all labeled units (UNSURE included). UNSURE labels never enter precision, recall or coverage.

Precision and recall show a 95% interval: Wilson score (n = labeled units) for unweighted rows; stratified bootstrap percentile for the weighted estimate (marked "bootstrap").

| Set | Units | Precision (95%) | Recall (95%) | F1 | Coverage | Unresolved |
|---|---|---|---|---|---|---|
| All rows (unweighted) | 300 | 98.9% [94.2%, 99.8%] | 83.0% [75.0%, 88.9%] | 90.3% | 58.3% | 36.3% |
| Weighted estimate (bootstrap) | 300 | 100.0% [100.0%, 100.0%] | 95.3% [91.6%, 98.4%] | 97.6% | 92.5% | 4.3% |
| path deterministic | 106 | 100.0% [95.1%, 100.0%] | 94.9% [87.7%, 98.0%] | 97.4% | 91.9% | 0.0% |
| path exact_id | 34 | 100.0% [80.6%, 100.0%] | 80.0% [58.4%, 91.9%] | 88.9% | 48.5% | 8.8% |
| path llm | 7 | n/a n/a | n/a n/a | n/a | 0.0% | 100.0% |
| path probabilistic | 153 | 66.7% [20.8%, 93.9%] | 15.4% [4.3%, 42.2%] | 25.0% | 33.9% | 64.7% |
| family dblp-base-name | 122 | 100.0% [88.3%, 100.0%] | 90.6% [75.8%, 96.8%] | 95.1% | 45.6% | 53.3% |
| family dblp-base-name.coauthor | 10 | 100.0% [43.9%, 100.0%] | 100.0% [43.9%, 100.0%] | 100.0% | 66.7% | 20.0% |
| family dblp-prior.numbered-name | 30 | 100.0% [85.1%, 100.0%] | 84.6% [66.5%, 93.8%] | 91.7% | 84.6% | 0.0% |
| family initials-surname | 87 | 100.0% [86.7%, 100.0%] | 69.4% [53.1%, 82.0%] | 82.0% | 69.3% | 26.4% |
| family initials-surname.coauthor | 51 | 93.3% [70.2%, 98.8%] | 93.3% [70.2%, 98.8%] | 93.3% | 50.0% | 37.3% |
| evidence: dblp_numbered_name only | 4 | 100.0% [20.7%, 100.0%] | 50.0% [9.5%, 90.5%] | 66.7% | 75.0% | 0.0% |
| evidence: independent | 243 | 98.9% [94.2%, 99.8%] | 83.6% [75.6%, 89.4%] | 90.6% | 58.0% | 29.2% |

Weighted bootstrap 95%: coverage [90.1%, 94.5%], f1 [95.6%, 99.2%], precision [100.0%, 100.0%], recall [91.6%, 98.4%], unresolvedRate [4.3%, 4.3%]

Weighted sample: 300 labeled rows (the resampling unit) in 300 stratum memberships (the weighted units). The resampling unit is the labeled row (one review-queue pair), never a stratum membership: a row sampled in several strata is drawn once and brings all its memberships, each with its fixed design weight population_h / labeledMemberships_h. Each row is resampled within its resampling stratum, the first of its membership strata in string order. A resampling stratum with a single labeled row would contribute no bootstrap variance, so it is collapsed: all single-row resampling strata are pooled into one group; if exactly one exists, it joins the smallest other group (fewest rows, then name). With fewer than two labeled rows in total no interval is reported.

Strata with a single labeled row (flagged): path=deterministic;outcome=conflict;family=dblp-base-name.coauthor;band=far, path=deterministic;outcome=conflict;family=dblp-base-name;band=far, path=deterministic;outcome=conflict;family=initials-surname;band=far, path=deterministic;outcome=rejected;family=dblp-base-name.coauthor;band=far, path=deterministic;outcome=rejected;family=initials-surname.coauthor;band=far, path=exact_id;outcome=accepted;family=dblp-base-name.coauthor;band=far, path=exact_id;outcome=conflict;family=dblp-base-name.coauthor;band=far, path=exact_id;outcome=unresolved;family=dblp-base-name;band=far, path=exact_id;outcome=unresolved;family=initials-surname.coauthor;band=far, path=exact_id;outcome=unresolved;family=initials-surname;band=far, path=llm;outcome=unresolved;family=dblp-base-name;band=far, path=llm;outcome=unresolved;family=initials-surname.coauthor;band=far, path=llm;outcome=unresolved;family=initials-surname.coauthor;band=near, path=llm;outcome=unresolved;family=initials-surname;band=far, path=llm;outcome=unresolved;family=initials-surname;band=near, path=probabilistic;outcome=accepted;family=initials-surname.coauthor;band=far, path=probabilistic;outcome=accepted;family=initials-surname.coauthor;band=near, path=probabilistic;outcome=accepted;family=initials-surname;band=far, path=probabilistic;outcome=accepted;family=initials-surname;band=near, path=probabilistic;outcome=conflict;family=dblp-base-name.coauthor;band=far, path=probabilistic;outcome=conflict;family=dblp-base-name.coauthor;band=near, path=probabilistic;outcome=conflict;family=initials-surname.coauthor;band=far, path=probabilistic;outcome=conflict;family=initials-surname.coauthor;band=near, path=probabilistic;outcome=conflict;family=initials-surname;band=far, path=probabilistic;outcome=conflict;family=initials-surname;band=near, path=probabilistic;outcome=rejected;family=initials-surname;band=near, path=probabilistic;outcome=unresolved;family=dblp-base-name.coauthor;band=far, path=probabilistic;outcome=unresolved;family=dblp-base-name.coauthor;band=near. Collapsed bootstrap groups: collapsed:path=deterministic;outcome=conflict;family=dblp-base-name.coauthor;band=far + path=deterministic;outcome=conflict;family=dblp-base-name;band=far + path=deterministic;outcome=conflict;family=initials-surname;band=far + path=deterministic;outcome=rejected;family=dblp-base-name.coauthor;band=far + path=deterministic;outcome=rejected;family=initials-surname.coauthor;band=far + path=exact_id;outcome=accepted;family=dblp-base-name.coauthor;band=far + path=exact_id;outcome=conflict;family=dblp-base-name.coauthor;band=far + path=exact_id;outcome=unresolved;family=dblp-base-name;band=far + path=exact_id;outcome=unresolved;family=initials-surname.coauthor;band=far + path=exact_id;outcome=unresolved;family=initials-surname;band=far + path=llm;outcome=unresolved;family=dblp-base-name;band=far + path=llm;outcome=unresolved;family=initials-surname.coauthor;band=far + path=llm;outcome=unresolved;family=initials-surname.coauthor;band=near + path=llm;outcome=unresolved;family=initials-surname;band=far + path=llm;outcome=unresolved;family=initials-surname;band=near + path=probabilistic;outcome=accepted;family=initials-surname.coauthor;band=far + path=probabilistic;outcome=accepted;family=initials-surname.coauthor;band=near + path=probabilistic;outcome=accepted;family=initials-surname;band=far + path=probabilistic;outcome=accepted;family=initials-surname;band=near + path=probabilistic;outcome=conflict;family=dblp-base-name.coauthor;band=far + path=probabilistic;outcome=conflict;family=dblp-base-name.coauthor;band=near + path=probabilistic;outcome=conflict;family=initials-surname.coauthor;band=far + path=probabilistic;outcome=conflict;family=initials-surname.coauthor;band=near + path=probabilistic;outcome=conflict;family=initials-surname;band=far + path=probabilistic;outcome=conflict;family=initials-surname;band=near + path=probabilistic;outcome=rejected;family=initials-surname;band=near + path=probabilistic;outcome=unresolved;family=dblp-base-name.coauthor;band=far + path=probabilistic;outcome=unresolved;family=dblp-base-name.coauthor;band=near.

### By stratum (unweighted labeled rows; Wilson 95%)

| Stratum | Units | Precision (95%) | Recall (95%) | F1 | Coverage | Unresolved |
|---|---|---|---|---|---|---|
| path=deterministic;outcome=accepted;family=dblp-base-name.coauthor;band=far | 2 | 100.0% [34.2%, 100.0%] | 100.0% [34.2%, 100.0%] | 100.0% | 100.0% | 0.0% |
| path=deterministic;outcome=accepted;family=dblp-base-name;band=far | 23 | 100.0% [85.1%, 100.0%] | 100.0% [85.1%, 100.0%] | 100.0% | 100.0% | 0.0% |
| path=deterministic;outcome=accepted;family=dblp-prior.numbered-name;band=far | 30 | 100.0% [85.1%, 100.0%] | 84.6% [66.5%, 93.8%] | 91.7% | 84.6% | 0.0% |
| path=deterministic;outcome=accepted;family=initials-surname.coauthor;band=far | 11 | 100.0% [74.1%, 100.0%] | 100.0% [74.1%, 100.0%] | 100.0% | 100.0% | 0.0% |
| path=deterministic;outcome=accepted;family=initials-surname;band=far | 18 | 100.0% [82.4%, 100.0%] | 100.0% [82.4%, 100.0%] | 100.0% | 100.0% | 0.0% |
| path=deterministic;outcome=conflict;family=dblp-base-name.coauthor;band=far | 1 | n/a n/a | n/a n/a | n/a | 0.0% | 0.0% |
| path=deterministic;outcome=conflict;family=dblp-base-name;band=far | 1 | n/a n/a | n/a n/a | n/a | n/a | 0.0% |
| path=deterministic;outcome=conflict;family=initials-surname.coauthor;band=far | 2 | n/a n/a | n/a n/a | n/a | 0.0% | 0.0% |
| path=deterministic;outcome=conflict;family=initials-surname;band=far | 1 | n/a n/a | n/a n/a | n/a | 0.0% | 0.0% |
| path=deterministic;outcome=rejected;family=dblp-base-name.coauthor;band=far | 1 | n/a n/a | n/a n/a | n/a | 100.0% | 0.0% |
| path=deterministic;outcome=rejected;family=dblp-base-name;band=far | 12 | n/a n/a | n/a n/a | n/a | 100.0% | 0.0% |
| path=deterministic;outcome=rejected;family=initials-surname.coauthor;band=far | 1 | n/a n/a | n/a n/a | n/a | 100.0% | 0.0% |
| path=deterministic;outcome=rejected;family=initials-surname;band=far | 3 | n/a n/a | n/a n/a | n/a | 100.0% | 0.0% |
| path=exact_id;outcome=accepted;family=dblp-base-name.coauthor;band=far | 1 | 100.0% [20.7%, 100.0%] | 100.0% [20.7%, 100.0%] | 100.0% | 100.0% | 0.0% |
| path=exact_id;outcome=accepted;family=dblp-base-name;band=far | 7 | 100.0% [64.6%, 100.0%] | 100.0% [64.6%, 100.0%] | 100.0% | 100.0% | 0.0% |
| path=exact_id;outcome=accepted;family=initials-surname.coauthor;band=far | 3 | 100.0% [43.9%, 100.0%] | 100.0% [43.9%, 100.0%] | 100.0% | 100.0% | 0.0% |
| path=exact_id;outcome=accepted;family=initials-surname;band=far | 5 | 100.0% [56.6%, 100.0%] | 100.0% [56.6%, 100.0%] | 100.0% | 100.0% | 0.0% |
| path=exact_id;outcome=conflict;family=dblp-base-name.coauthor;band=far | 1 | n/a n/a | n/a n/a | n/a | n/a | 0.0% |
| path=exact_id;outcome=conflict;family=dblp-base-name;band=far | 5 | n/a n/a | n/a n/a | n/a | 0.0% | 0.0% |
| path=exact_id;outcome=conflict;family=initials-surname.coauthor;band=far | 2 | n/a n/a | n/a n/a | n/a | 0.0% | 0.0% |
| path=exact_id;outcome=conflict;family=initials-surname;band=far | 7 | n/a n/a | 0.0% [0.0%, 79.3%] | n/a | 0.0% | 0.0% |
| path=exact_id;outcome=unresolved;family=dblp-base-name;band=far | 1 | n/a n/a | 0.0% [0.0%, 79.3%] | n/a | 0.0% | 100.0% |
| path=exact_id;outcome=unresolved;family=initials-surname.coauthor;band=far | 1 | n/a n/a | 0.0% [0.0%, 79.3%] | n/a | 0.0% | 100.0% |
| path=exact_id;outcome=unresolved;family=initials-surname;band=far | 1 | n/a n/a | 0.0% [0.0%, 79.3%] | n/a | 0.0% | 100.0% |
| path=llm;outcome=unresolved;family=dblp-base-name;band=far | 1 | n/a n/a | n/a n/a | n/a | n/a | 100.0% |
| path=llm;outcome=unresolved;family=dblp-base-name;band=near | 2 | n/a n/a | n/a n/a | n/a | 0.0% | 100.0% |
| path=llm;outcome=unresolved;family=initials-surname.coauthor;band=far | 1 | n/a n/a | n/a n/a | n/a | 0.0% | 100.0% |
| path=llm;outcome=unresolved;family=initials-surname.coauthor;band=near | 1 | n/a n/a | n/a n/a | n/a | 0.0% | 100.0% |
| path=llm;outcome=unresolved;family=initials-surname;band=far | 1 | n/a n/a | n/a n/a | n/a | 0.0% | 100.0% |
| path=llm;outcome=unresolved;family=initials-surname;band=near | 1 | n/a n/a | n/a n/a | n/a | 0.0% | 100.0% |
| path=probabilistic;outcome=accepted;family=initials-surname.coauthor;band=far | 1 | n/a n/a | n/a n/a | n/a | n/a | 0.0% |
| path=probabilistic;outcome=accepted;family=initials-surname.coauthor;band=near | 1 | 0.0% [0.0%, 79.3%] | n/a n/a | n/a | 100.0% | 0.0% |
| path=probabilistic;outcome=accepted;family=initials-surname;band=far | 1 | 100.0% [20.7%, 100.0%] | 100.0% [20.7%, 100.0%] | 100.0% | 100.0% | 0.0% |
| path=probabilistic;outcome=accepted;family=initials-surname;band=near | 1 | 100.0% [20.7%, 100.0%] | 100.0% [20.7%, 100.0%] | 100.0% | 100.0% | 0.0% |
| path=probabilistic;outcome=conflict;family=dblp-base-name.coauthor;band=far | 1 | n/a n/a | n/a n/a | n/a | n/a | 0.0% |
| path=probabilistic;outcome=conflict;family=dblp-base-name.coauthor;band=near | 1 | n/a n/a | n/a n/a | n/a | 0.0% | 0.0% |
| path=probabilistic;outcome=conflict;family=dblp-base-name;band=far | 2 | n/a n/a | n/a n/a | n/a | 0.0% | 0.0% |
| path=probabilistic;outcome=conflict;family=dblp-base-name;band=near | 7 | n/a n/a | n/a n/a | n/a | 0.0% | 0.0% |
| path=probabilistic;outcome=conflict;family=initials-surname.coauthor;band=far | 1 | n/a n/a | n/a n/a | n/a | 0.0% | 0.0% |
| path=probabilistic;outcome=conflict;family=initials-surname.coauthor;band=near | 1 | n/a n/a | n/a n/a | n/a | 0.0% | 0.0% |
| path=probabilistic;outcome=conflict;family=initials-surname;band=far | 1 | n/a n/a | 0.0% [0.0%, 79.3%] | n/a | 0.0% | 0.0% |
| path=probabilistic;outcome=conflict;family=initials-surname;band=near | 1 | n/a n/a | n/a n/a | n/a | n/a | 0.0% |
| path=probabilistic;outcome=rejected;family=initials-surname.coauthor;band=far | 6 | n/a n/a | n/a n/a | n/a | 100.0% | 0.0% |
| path=probabilistic;outcome=rejected;family=initials-surname.coauthor;band=near | 3 | n/a n/a | n/a n/a | n/a | 100.0% | 0.0% |
| path=probabilistic;outcome=rejected;family=initials-surname;band=far | 25 | n/a n/a | n/a n/a | n/a | 100.0% | 0.0% |
| path=probabilistic;outcome=rejected;family=initials-surname;band=near | 1 | n/a n/a | 0.0% [0.0%, 79.3%] | n/a | 100.0% | 0.0% |
| path=probabilistic;outcome=unresolved;family=dblp-base-name.coauthor;band=far | 1 | n/a n/a | n/a n/a | n/a | n/a | 100.0% |
| path=probabilistic;outcome=unresolved;family=dblp-base-name.coauthor;band=near | 1 | n/a n/a | n/a n/a | n/a | n/a | 100.0% |
| path=probabilistic;outcome=unresolved;family=dblp-base-name;band=far | 17 | n/a n/a | 0.0% [0.0%, 65.8%] | n/a | 0.0% | 100.0% |
| path=probabilistic;outcome=unresolved;family=dblp-base-name;band=near | 44 | n/a n/a | n/a n/a | n/a | 0.0% | 100.0% |
| path=probabilistic;outcome=unresolved;family=initials-surname.coauthor;band=far | 7 | n/a n/a | n/a n/a | n/a | 0.0% | 100.0% |
| path=probabilistic;outcome=unresolved;family=initials-surname.coauthor;band=near | 9 | n/a n/a | n/a n/a | n/a | 0.0% | 100.0% |
| path=probabilistic;outcome=unresolved;family=initials-surname;band=far | 6 | n/a n/a | 0.0% [0.0%, 56.1%] | n/a | 0.0% | 100.0% |
| path=probabilistic;outcome=unresolved;family=initials-surname;band=near | 14 | n/a n/a | 0.0% [0.0%, 49.0%] | n/a | 0.0% | 100.0% |

### Confusion matrix (unweighted rows)

| Decision | MATCH | NON_MATCH | UNSURE |
|---|---|---|---|
| accepted | 93 | 1 | 4 |
| rejected | 1 | 49 | 2 |
| unresolved | 12 | 59 | 38 |
| conflict | 6 | 26 | 9 |

### Confusion matrix (weighted: estimated population decisions)

| Decision | MATCH | NON_MATCH | UNSURE |
|---|---|---|---|
| accepted | 11155611.5 | 20.0 | 413734.3 |
| rejected | 1514.0 | 6427874.6 | 232357.4 |
| unresolved | 65198.2 | 482811.1 | 325548.7 |
| conflict | 487213.0 | 392997.9 | 264098.3 |

### LLM-adjudicated path

LLM-adjudicated metrics are empty: none of the 7 labeled llm-path rows was accepted or rejected by the adjudicator (their outcomes: unresolved; reason codes: llm-abstain, score-between-thresholds). The run used adjudicator 'always-abstain' with model id 'none/always-abstain'. The run report's LLM stage records no accepted or rejected pair (outcomes: unresolved 1000), so every pair sent to the adjudicator stayed unresolved and precision and recall of LLM decisions cannot be estimated in this phase. The path llm row shows only their coverage and unresolved rate.

Labeled llm-path rows: 7; accepted or rejected by the adjudicator: 0; outcomes: unresolved 7; reason codes: llm-abstain 7, score-between-thresholds 7. Run LLM stage: unresolved 1000 (reasons llm-abstain 1000; pairs sent 1000 of 729723 band candidates).

### Error analysis

Selection: all if at most 10, else the first 10 in (leftMentionId, rightMentionId) code-point order; totals give the full count.

#### False positives (accepted, labeled NON_MATCH): 1 total, 1 shown

1. Queue row 272: left Wen Wang 0022 (dblp key conf/cvpr/ChahineCCPPFTDX22, https://dblp.org/rec/conf/cvpr/ChahineCCPPFTDX22.html); right Wenqi Wang (dblp key conf/cvpr/LiuMSZZTZGCKDJLZCSMAHLCFZLWWZZMSM23, https://dblp.org/rec/conf/cvpr/LiuMSZZTZGCKDJLZCSMAHLCFZLWWZZMSM23.html).
   - Mentions mention:v1:2cfbcf8a9e33779de8d1aa818e19b4ac / mention:v1:cf657b84d2205eb6fb767339733855c3; candidate match-candidate:v1:6163f9335be280e4e978dfb8b197e29a.
   - Label NON_MATCH (evidence: orcid, other); note: Different given names (Wen vs Wenqi) and different ORCIDs.
   - Decision accepted, path probabilistic, family initials-surname.coauthor, match probability 0.9002495108803148.
   - Rules fired: score.accept; reason codes: none.
   - Features: author_role_match=0, coauthor_overlap_ratio=0.2, coauthor_shared_count=6, given_name_relation=0, name_exact_match=0, name_similarity=0.9533, same_venue=1, same_work=0, source_reliability=1, surname_equal=1, title_similarity=0.3636, venue_area_match=1, year_abs_diff=1.

#### False negatives (labeled MATCH, not accepted): 19 total, 10 shown

1. Queue row 44: left Sheng Liu 0017 (dblp key conf/aaai/LiuLWY022, https://dblp.org/rec/conf/aaai/LiuLWY022.html); right Sheng Liu (dblp key conf/cvpr/LiuHCAH23, https://dblp.org/rec/conf/cvpr/LiuHCAH23.html).
   - Mentions mention:v1:051d7f931d0cfc56ec26676a8f9365f5 / mention:v1:173025df812e61e16a2eda0a158178d0; candidate match-candidate:v1:1aba3e4ba8ecea85ec764530f548dbf0.
   - Label MATCH (evidence: orcid); note: Both list ORCID 0009-0003-9039-6968 and each ORCID lists the other paper.
   - Decision unresolved, path exact_id, family dblp-base-name, match probability not available.
   - Rules fired: cluster.guard.dblp-disambiguation-profile, orcid.exact; reason codes: merge-refused:dblp-disambiguation-profile.
   - Features: author_role_match=1, coauthor_overlap_ratio=0, coauthor_shared_count=0, given_name_relation=2, name_exact_match=0, name_similarity=1, orcid_exact_match=1, orcid_match=1, same_venue=0, same_work=0, source_reliability=1, surname_equal=1, title_similarity=0, venue_area_match=0, year_abs_diff=1.
2. Queue row 247: left Zhiyuan Liu 0001 (dblp key conf/acl/ZhouLCWCLW0T0S025, https://dblp.org/rec/conf/acl/ZhouLCWCLW0T0S025.html); right Zhiyuan Liu 0001 (dblp key conf/emnlp/HuangQWYSLL22, https://dblp.org/rec/conf/emnlp/HuangQWYSLL22.html).
   - Mentions mention:v1:08386ce1e99b3ca85bad9a09816a3102 / mention:v1:4a40d3f6935e9099c913bc85dbfb15da; candidate match-candidate:v1:c43397bd707fa43c8dc11c7dedaafe6d.
   - Label MATCH (evidence: dblp_numbered_name, openalex_author, orcid); note: Both ORCID 0000-0002-7709-2543, dblp Zhiyuan Liu 0001, shared coauthor Maosong Sun.
   - Decision conflict, path deterministic, family dblp-prior.numbered-name, match probability not available.
   - Rules fired: dblp-prior.cluster; reason codes: cluster-multiple-orcids.
   - Features: author_role_match=0, given_name_relation=2, name_similarity=1, source_reliability=1, surname_equal=1, title_similarity=0, venue_area_match=1.
3. Queue row 135: left Ser Nam Lim (dblp key conf/nips/CsabaZ0L0B24, https://dblp.org/rec/conf/nips/CsabaZ0L0B24.html); right Ser-Nam Lim (dblp key conf/cvpr/MukhotiLPWSTL23, https://dblp.org/rec/conf/cvpr/MukhotiLPWSTL23.html).
   - Mentions mention:v1:0f66d128fad9b681ff2701bb43f0e384 / mention:v1:7dde8c4e695c738b1bf72e931da58789; candidate match-candidate:v1:058ead422d0140de3e5ecbd77f174d78.
   - Label MATCH (evidence: openalex_author, orcid); note: Both ORCID 0000-0001-9525-0288 and each ORCID lists the other paper.
   - Decision unresolved, path probabilistic, family initials-surname, match probability 0.8455347349164652.
   - Rules fired: score.llm-band; reason codes: llm-band-cap-reached, score-between-thresholds.
   - Features: author_role_match=0, coauthor_overlap_ratio=0, coauthor_shared_count=0, given_name_relation=2, name_exact_match=0, name_similarity=1, same_venue=0, same_work=0, source_reliability=1, surname_equal=1, title_similarity=0.0833, venue_area_match=0, year_abs_diff=1.
4. Queue row 277: left In So Kweon (dblp key conf/cvpr/ShinPK23, https://dblp.org/rec/conf/cvpr/ShinPK23.html); right In-So Kweon (dblp key conf/iccv/ImHCJJK15, https://dblp.org/rec/conf/iccv/ImHCJJK15.html).
   - Mentions mention:v1:10c1b605abc55e8c8d4c7eb118c4a429 / mention:v1:403aa515888da904160f7e50b8ea50f8; candidate match-candidate:v1:058896d927e5f3faa7f02656879bf5a9.
   - Label MATCH (evidence: institution, openalex_author, orcid); note: Both ORCID 0000-0001-9626-5983, same OpenAlex author at KAIST.
   - Decision unresolved, path probabilistic, family initials-surname, match probability 0.8698915256370021.
   - Rules fired: score.llm-band; reason codes: llm-band-cap-reached, score-between-thresholds.
   - Features: author_role_match=1, coauthor_overlap_ratio=0, coauthor_shared_count=0, given_name_relation=2, name_exact_match=0, name_similarity=1, same_venue=0, same_work=0, source_reliability=1, surname_equal=1, title_similarity=0, venue_area_match=1, year_abs_diff=8.
5. Queue row 281: left Angel X. Chang (dblp key conf/iccv/0019GC23, https://dblp.org/rec/conf/iccv/0019GC23.html); right Angel Xuan Chang (dblp key conf/cvpr/YadavRRGTGMCBSC23, https://dblp.org/rec/conf/cvpr/YadavRRGTGMCBSC23.html).
   - Mentions mention:v1:138c20b2d2c5b562e9dd5bec257acdf7 / mention:v1:e9dc91c641bf47a21ddea5c74ca937ef; candidate match-candidate:v1:29d898b22afe9eaa20de4fb450fde2a4.
   - Label MATCH (evidence: institution); note: Compatible names (Angel X. vs Angel Xuan Chang), both at Simon Fraser University.
   - Decision unresolved, path probabilistic, family initials-surname, match probability 0.5.
   - Rules fired: score.unresolved; reason codes: score-between-thresholds.
   - Features: author_role_match=0, coauthor_overlap_ratio=0, coauthor_shared_count=0, given_name_relation=1, name_exact_match=0, name_similarity=0.924, same_venue=0, same_work=0, source_reliability=1, surname_equal=1, title_similarity=0.0909, venue_area_match=1, year_abs_diff=0.
6. Queue row 225: left Kai Wang 0036 (dblp key conf/aaai/LiuWXZXSY20, https://dblp.org/rec/conf/aaai/LiuWXZXSY20.html); right Kai Wang 0036 (dblp key conf/aaai/0005WYSWW25, https://dblp.org/rec/conf/aaai/0005WYSWW25.html).
   - Mentions mention:v1:18857e0424decbed3abf40024a50ad26 / mention:v1:f6d6ced472fa68a732da506dee0352c7; candidate match-candidate:v1:29bba33b9db4714529b0faaecdeaf359.
   - Label MATCH (evidence: dblp_numbered_name); note: Both mentions are dblp Kai Wang 0036; no conflicting ID evidence.
   - Decision conflict, path deterministic, family dblp-prior.numbered-name, match probability not available.
   - Rules fired: dblp-prior.cluster; reason codes: cluster-multiple-orcids.
   - Features: author_role_match=0, given_name_relation=2, name_similarity=1, source_reliability=1, surname_equal=1, title_similarity=0, venue_area_match=1.
7. Queue row 123: left Yongdong Zhang 0001 (dblp key conf/aaai/LiuXZMYZ20, https://dblp.org/rec/conf/aaai/LiuXZMYZ20.html); right Yongdong Zhang 0001 (dblp key conf/aaai/WuXLZSZ20, https://dblp.org/rec/conf/aaai/WuXLZSZ20.html).
   - Mentions mention:v1:204c770fd0fc6e9d1844d1a007e457c3 / mention:v1:dfdb43a85fbaaa7b30600e371f3efc01; candidate match-candidate:v1:eb5446801a0cb76c260a945a6362a285.
   - Label MATCH (evidence: dblp_numbered_name, openalex_author, orcid, shared_coauthors); note: Both ORCID 0000-0002-1151-1792 and 3 shared coauthors at USTC.
   - Decision conflict, path deterministic, family dblp-prior.numbered-name, match probability not available.
   - Rules fired: dblp-prior.cluster; reason codes: cluster-multiple-orcids.
   - Features: author_role_match=1, given_name_relation=2, name_similarity=1, source_reliability=1, surname_equal=1, title_similarity=0, venue_area_match=1.
8. Queue row 136: left Sercan Ömer Arik (dblp key conf/nips/ArikCPPZ18, https://dblp.org/rec/conf/nips/ArikCPPZ18.html); right Sercan Ö. Arik (dblp key conf/acl/YoonCAP24, https://dblp.org/rec/conf/acl/YoonCAP24.html).
   - Mentions mention:v1:20ed19a4f30ae793f869bdbfb0b0dff1 / mention:v1:e2ea6bdeeb49ac9801d159e23a47806d; candidate match-candidate:v1:7db149551b66364c90bf5f7865782f20.
   - Label MATCH (evidence: openalex_author, orcid); note: Same ORCID 0000-0001-6333-1729 and OpenAlex author for rare name Sercan Arik (left OpenAlex raw name "Arik, Sercan O.").
   - Decision rejected, path probabilistic, family initials-surname, match probability 0.23147521650098232.
   - Rules fired: score.reject; reason codes: none.
   - Features: author_role_match=0, coauthor_overlap_ratio=0, coauthor_shared_count=0, given_name_relation=1, name_exact_match=0, name_similarity=0.9394, same_venue=0, same_work=0, source_reliability=1, surname_equal=1, title_similarity=0, venue_area_match=0, year_abs_diff=6.
9. Queue row 118: left Liang Ding 0006 (dblp key conf/cvpr/ZhangS0TD22, https://dblp.org/rec/conf/cvpr/ZhangS0TD22.html); right Liang Ding (dblp key conf/emnlp/WuDLX20, https://dblp.org/rec/conf/emnlp/WuDLX20.html).
   - Mentions mention:v1:34deda6ef521c7e73df3cdbcffc4b8c4 / mention:v1:c988cc9d63e88576ed9cdfcc1a7e11dc; candidate match-candidate:v1:052bff0b3e0697cc5be5ebe73df35321.
   - Label MATCH (evidence: institution, openalex_author, orcid); note: Both ORCID 0000-0001-8976-2084 and each ORCID lists the other paper.
   - Decision unresolved, path probabilistic, family initials-surname, match probability 0.8455347349164652.
   - Rules fired: score.llm-band; reason codes: llm-band-cap-reached, score-between-thresholds.
   - Features: author_role_match=0, coauthor_overlap_ratio=0, coauthor_shared_count=0, given_name_relation=2, name_exact_match=0, name_similarity=1, same_venue=0, same_work=0, source_reliability=1, surname_equal=1, title_similarity=0.1, venue_area_match=0, year_abs_diff=2.
10. Queue row 161: left Julian John McAuley (dblp key conf/sigir/0003HLZM23, https://dblp.org/rec/conf/sigir/0003HLZM23.html); right Julian J. McAuley (dblp key conf/wsdm/HeXSLJKM25, https://dblp.org/rec/conf/wsdm/HeXSLJKM25.html).
   - Mentions mention:v1:38778398d04edea59466b2c780c425b8 / mention:v1:502b03698c12435372d3798d62efd9d0; candidate match-candidate:v1:6e090d83a4a1db14171c45f2edf621f1.
   - Label MATCH (evidence: openalex_author, orcid); note: Both ORCID 0000-0003-0955-7588 and each ORCID lists the other paper.
   - Decision unresolved, path exact_id, family initials-surname, match probability not available.
   - Rules fired: cluster.guard.rejected-pair, orcid.exact; reason codes: merge-refused:rejected-pair.
   - Features: author_role_match=1, coauthor_overlap_ratio=0.25, coauthor_shared_count=1, given_name_relation=1, name_exact_match=0, name_similarity=0.9684, orcid_exact_match=1, orcid_match=1, same_venue=0, same_work=0, source_reliability=1, surname_equal=1, title_similarity=0, venue_area_match=1, year_abs_diff=2.

### Threshold sensitivity (reporting only)

Reporting only; never changes er-v1. Scored rows re-thresholded on their match probability; other rows keep their outcome. The row marked "run" uses the thresholds recorded in the run manifest, when given.

| accept | reject | precision | recall | coverage | unresolved |
|---|---|---|---|---|---|
| 0.5 | 0.05 | 69.4% | 91.1% | 76.9% | 10.3% |
| 0.5 | 0.1 | 69.4% | 91.1% | 76.9% | 10.3% |
| 0.5 | 0.15 | 69.4% | 91.1% | 77.7% | 9.7% |
| 0.5 | 0.2 | 69.4% | 91.1% | 78.1% | 9.3% |
| 0.5 | 0.25 | 69.4% | 91.1% | 79.8% | 8.0% |
| 0.5 | 0.3 | 69.4% | 91.1% | 79.8% | 8.0% |
| 0.5 | 0.35 | 69.4% | 91.1% | 80.6% | 7.3% |
| 0.5 | 0.4 | 69.4% | 91.1% | 82.6% | 5.7% |
| 0.5 | 0.45 | 69.4% | 91.1% | 83.4% | 5.0% |
| 0.5 | 0.5 | 69.4% | 91.1% | 84.2% | 4.3% |
| 0.55 | 0.05 | 69.2% | 90.2% | 76.5% | 10.7% |
| 0.55 | 0.1 | 69.2% | 90.2% | 76.5% | 10.7% |
| 0.55 | 0.15 | 69.2% | 90.2% | 77.3% | 10.0% |
| 0.55 | 0.2 | 69.2% | 90.2% | 77.7% | 9.7% |
| 0.55 | 0.25 | 69.2% | 90.2% | 79.4% | 8.3% |
| 0.55 | 0.3 | 69.2% | 90.2% | 79.4% | 8.3% |
| 0.55 | 0.35 | 69.2% | 90.2% | 80.2% | 7.7% |
| 0.55 | 0.4 | 69.2% | 90.2% | 82.2% | 6.0% |
| 0.55 | 0.45 | 69.2% | 90.2% | 83.0% | 5.3% |
| 0.55 | 0.5 | 69.2% | 90.2% | 83.8% | 4.7% |
| 0.6 | 0.05 | 69.2% | 90.2% | 76.5% | 10.7% |
| 0.6 | 0.1 | 69.2% | 90.2% | 76.5% | 10.7% |
| 0.6 | 0.15 | 69.2% | 90.2% | 77.3% | 10.0% |
| 0.6 | 0.2 | 69.2% | 90.2% | 77.7% | 9.7% |
| 0.6 | 0.25 | 69.2% | 90.2% | 79.4% | 8.3% |
| 0.6 | 0.3 | 69.2% | 90.2% | 79.4% | 8.3% |
| 0.6 | 0.35 | 69.2% | 90.2% | 80.2% | 7.7% |
| 0.6 | 0.4 | 69.2% | 90.2% | 82.2% | 6.0% |
| 0.6 | 0.45 | 69.2% | 90.2% | 83.0% | 5.3% |
| 0.6 | 0.5 | 69.2% | 90.2% | 83.8% | 4.7% |
| 0.65 | 0.05 | 69.2% | 90.2% | 76.5% | 10.7% |
| 0.65 | 0.1 | 69.2% | 90.2% | 76.5% | 10.7% |
| 0.65 | 0.15 | 69.2% | 90.2% | 77.3% | 10.0% |
| 0.65 | 0.2 | 69.2% | 90.2% | 77.7% | 9.7% |
| 0.65 | 0.25 | 69.2% | 90.2% | 79.4% | 8.3% |
| 0.65 | 0.3 | 69.2% | 90.2% | 79.4% | 8.3% |
| 0.65 | 0.35 | 69.2% | 90.2% | 80.2% | 7.7% |
| 0.65 | 0.4 | 69.2% | 90.2% | 82.2% | 6.0% |
| 0.65 | 0.45 | 69.2% | 90.2% | 83.0% | 5.3% |
| 0.65 | 0.5 | 69.2% | 90.2% | 83.8% | 4.7% |
| 0.7 | 0.05 | 69.7% | 90.2% | 76.1% | 11.0% |
| 0.7 | 0.1 | 69.7% | 90.2% | 76.1% | 11.0% |
| 0.7 | 0.15 | 69.7% | 90.2% | 76.9% | 10.3% |
| 0.7 | 0.2 | 69.7% | 90.2% | 77.3% | 10.0% |
| 0.7 | 0.25 | 69.7% | 90.2% | 78.9% | 8.7% |
| 0.7 | 0.3 | 69.7% | 90.2% | 78.9% | 8.7% |
| 0.7 | 0.35 | 69.7% | 90.2% | 79.8% | 8.0% |
| 0.7 | 0.4 | 69.7% | 90.2% | 81.8% | 6.3% |
| 0.7 | 0.45 | 69.7% | 90.2% | 82.6% | 5.7% |
| 0.7 | 0.5 | 69.7% | 90.2% | 83.4% | 5.0% |
| 0.75 | 0.05 | 70.6% | 90.2% | 75.3% | 11.7% |
| 0.75 | 0.1 | 70.6% | 90.2% | 75.3% | 11.7% |
| 0.75 | 0.15 | 70.6% | 90.2% | 76.1% | 11.0% |
| 0.75 | 0.2 | 70.6% | 90.2% | 76.5% | 10.7% |
| 0.75 | 0.25 | 70.6% | 90.2% | 78.1% | 9.3% |
| 0.75 | 0.3 | 70.6% | 90.2% | 78.1% | 9.3% |
| 0.75 | 0.35 | 70.6% | 90.2% | 78.9% | 8.7% |
| 0.75 | 0.4 | 70.6% | 90.2% | 81.0% | 7.0% |
| 0.75 | 0.45 | 70.6% | 90.2% | 81.8% | 6.3% |
| 0.75 | 0.5 | 70.6% | 90.2% | 82.6% | 5.7% |
| 0.8 | 0.05 | 73.5% | 89.3% | 72.5% | 14.3% |
| 0.8 | 0.1 | 73.5% | 89.3% | 72.5% | 14.3% |
| 0.8 | 0.15 | 73.5% | 89.3% | 73.3% | 13.7% |
| 0.8 | 0.2 | 73.5% | 89.3% | 73.7% | 13.3% |
| 0.8 | 0.25 | 73.5% | 89.3% | 75.3% | 12.0% |
| 0.8 | 0.3 | 73.5% | 89.3% | 75.3% | 12.0% |
| 0.8 | 0.35 | 73.5% | 89.3% | 76.1% | 11.3% |
| 0.8 | 0.4 | 73.5% | 89.3% | 78.1% | 9.7% |
| 0.8 | 0.45 | 73.5% | 89.3% | 78.9% | 9.0% |
| 0.8 | 0.5 | 73.5% | 89.3% | 79.8% | 8.3% |
| 0.85 | 0.05 | 88.0% | 84.8% | 61.1% | 32.0% |
| 0.85 | 0.1 | 88.0% | 84.8% | 61.1% | 32.0% |
| 0.85 | 0.15 | 88.0% | 84.8% | 61.9% | 31.3% |
| 0.85 | 0.2 | 88.0% | 84.8% | 62.3% | 31.0% |
| 0.85 | 0.25 | 88.0% | 84.8% | 64.0% | 29.7% |
| 0.85 | 0.3 | 88.0% | 84.8% | 64.0% | 29.7% |
| 0.85 | 0.35 | 88.0% | 84.8% | 64.8% | 29.0% |
| 0.85 | 0.4 | 88.0% | 84.8% | 66.8% | 27.3% |
| 0.85 | 0.45 | 88.0% | 84.8% | 67.6% | 26.7% |
| 0.85 | 0.5 | 88.0% | 84.8% | 68.4% | 26.0% |
| 0.9 | 0.05 | 98.9% | 83.0% | 55.5% | 38.7% |
| 0.9 | 0.1 | 98.9% | 83.0% | 55.5% | 38.7% |
| 0.9 | 0.15 | 98.9% | 83.0% | 56.3% | 38.0% |
| 0.9 | 0.2 | 98.9% | 83.0% | 56.7% | 37.7% |
| 0.9 | 0.25 | 98.9% | 83.0% | 58.3% | 36.3% |
| 0.9 (run) | 0.3 | 98.9% | 83.0% | 58.3% | 36.3% |
| 0.9 | 0.35 | 98.9% | 83.0% | 59.1% | 35.7% |
| 0.9 | 0.4 | 98.9% | 83.0% | 61.1% | 34.0% |
| 0.9 | 0.45 | 98.9% | 83.0% | 61.9% | 33.3% |
| 0.9 | 0.5 | 98.9% | 83.0% | 62.8% | 32.7% |
| 0.95 | 0.05 | 100.0% | 81.3% | 54.3% | 39.7% |
| 0.95 | 0.1 | 100.0% | 81.3% | 54.3% | 39.7% |
| 0.95 | 0.15 | 100.0% | 81.3% | 55.1% | 39.0% |
| 0.95 | 0.2 | 100.0% | 81.3% | 55.5% | 38.7% |
| 0.95 | 0.25 | 100.0% | 81.3% | 57.1% | 37.3% |
| 0.95 | 0.3 | 100.0% | 81.3% | 57.1% | 37.3% |
| 0.95 | 0.35 | 100.0% | 81.3% | 57.9% | 36.7% |
| 0.95 | 0.4 | 100.0% | 81.3% | 59.9% | 35.0% |
| 0.95 | 0.45 | 100.0% | 81.3% | 60.7% | 34.3% |
| 0.95 | 0.5 | 100.0% | 81.3% | 61.5% | 33.7% |
| 0.99 | 0.05 | 100.0% | 81.3% | 54.3% | 39.7% |
| 0.99 | 0.1 | 100.0% | 81.3% | 54.3% | 39.7% |
| 0.99 | 0.15 | 100.0% | 81.3% | 55.1% | 39.0% |
| 0.99 | 0.2 | 100.0% | 81.3% | 55.5% | 38.7% |
| 0.99 | 0.25 | 100.0% | 81.3% | 57.1% | 37.3% |
| 0.99 | 0.3 | 100.0% | 81.3% | 57.1% | 37.3% |
| 0.99 | 0.35 | 100.0% | 81.3% | 57.9% | 36.7% |
| 0.99 | 0.4 | 100.0% | 81.3% | 59.9% | 35.0% |
| 0.99 | 0.45 | 100.0% | 81.3% | 60.7% | 34.3% |
| 0.99 | 0.5 | 100.0% | 81.3% | 61.5% | 33.7% |

## Researcher resolution: final cluster assignment (reported apart from pair decisions)

Cluster level: the label against whether the two mentions share a final ResearcherId (the identity assignment Spark consumes). Positive = same final cluster and MATCH. Reported apart from the pair-level decision metrics: an unresolved or rejected pair can still end in one cluster (for example inside one dblp-prior cluster), and an accepted pair can end in two clusters when a merge guard refused it.

Labeled rows with cluster information: 300; without: 0.

| Set | Units | Precision (95%) | Recall (95%) | F1 |
|---|---|---|---|---|
| Cluster level, all rows (unweighted) | 300 | 99.0% [94.8%, 99.8%] | 92.0% [85.4%, 95.7%] | 95.4% |
| Cluster level, weighted estimate (bootstrap) | 300 | 100.0% [100.0%, 100.0%] | 99.4% [98.8%, 99.9%] | 99.7% |

### Cluster-level confusion matrix (unweighted rows)

| Final clusters | MATCH | NON_MATCH | UNSURE |
|---|---|---|---|
| same final cluster | 103 | 1 | 9 |
| different final clusters | 9 | 134 | 44 |

### Pair decision by final cluster (unweighted rows)

| Pair decision | same final cluster | different final clusters |
|---|---|---|
| accepted | 98 | 0 |
| rejected | 0 | 52 |
| unresolved | 7 | 102 |
| conflict | 8 | 33 |

## Work resolution (exact identifiers only)

none: the review queue holds researcher mention pairs only, so work-resolution precision and recall cannot be estimated.

- Subset works: 155978; external-ID link directory present: false.
- Exact-link coverage: DOI 0 works (share 0); OpenAlex work 0 works (share 0); links outside the subset: 0.
- Conflicts: J04 conflicts directory given: false; J04 conflicts by reason: none; identifiers shared by several dblp works (each recorded as a conflict decision, never merged): doi 0 groups / 0 works; arxivId 0 groups / 0 works; landingUrl 1 groups / 2 works.
- Identifier availability: dblpDoi 124661 works (share 0.7992216851094385); arxivId 0 works (share 0); landingUrl 82131 works (share 0.5265550269909859); semanticScholarId: not applicable: Semantic Scholar is not a ScholarChain dataset.
- Probabilistic work matching: not implemented: there is no shared contract for non-dblp works yet (finding 11); work resolution uses exact identifiers only.

## Feature definitions and rules (from the policy file)

| Feature | Bins (min to max: log-odds weight) | Scaled by source reliability | Definition |
|---|---|---|---|
| given_name_relation | 0 to 1: -4; 1 to 2: 1; 2 to open: 3 | false | 2 = folded given names equal (accent, case, hyphen or punctuation variants of one name): strong agreement. 1 = compatible (initial vs full name, missing middle name): weak agreement, many people share an initial. 0 = conflicting given names (Jane vs John, Wei vs Wen): strong disagreement. |
| surname_equal | 0 to 1: -3; 1 to open: 0 | false | Blocking keys share the surname, so 0 only occurs through folding edge cases; a different folded surname is strong disagreement. |
| name_similarity | 0.95 to open: 0.3 | false | Jaro-Winkler of the folded names. Near-identical spellings add a little; lower values add nothing because given_name_relation already carries the disagreement. |
| coauthor_shared_count | 0 to 1: -0.5; 1 to 2: 2; 2 to 3: 3.5; 3 to open: 5 | false | Sharing an exact coauthor name is the strongest bibliographic evidence for a same-name pair; each further shared coauthor adds less. No shared coauthor is weak disagreement (people change collaborators). |
| coauthor_overlap_ratio | 0.5 to open: 0.5 | false | Shared coauthors divided by the smaller coauthor list: at least half of the smaller team shared adds a little on top of the count. |
| same_venue | 1 to open: 0.4 | false | Same dblp venue: mild agreement (researchers return to their venues). |
| venue_area_match | 0 to 1: -0.3; 1 to open: 0.3 | false | Venue compatibility through the fixed research-area map below: same area mild agreement, different area mild disagreement. |
| year_abs_diff | 0 to 3: 0.2; 6 to open: -0.4 | false | Publications close in time are slightly more likely from one active career phase; six or more years apart slightly less. |
| title_similarity | 0.15 to 0.3: 0.5; 0.3 to open: 1 | false | Jaccard of the content words of the two titles: a shared topic vocabulary is moderate agreement; low overlap is not disagreement (people work on several topics). |
| author_role_match | 1 to open: 0.2 | false | Author order and position: both first authors or both last (senior) authors is slight agreement. |
| affiliation_overlap | 0 to 1: -0.5; 1 to open: 1.5 | true | Shared OpenAlex institution at publication time (J04 mention links only): moderate agreement; disjoint institutions weak disagreement (people move). Scaled by the reliability of the asserting source. |
| openalex_author_match | 0 to 1: -1 | true | Different OpenAlex author IDs: moderate disagreement only (OpenAlex author IDs are unstable and often split one person). Equal IDs are handled by the exact rule openalex-author.exact. |

Features recorded in the review key without a policy weight (inputs to rules or reporting only): homonym_suffix_match, name_exact_match, orcid_exact_match, orcid_match, same_work, source_reliability.

| Rule | Kind | Path | Outcome |
|---|---|---|---|
| structural.same-work | pair | deterministic | conflict |
| orcid.disagree | pair | exact_id | conflict |
| orcid.agree-dblp-homonym-differs | pair | exact_id | conflict |
| orcid.exact | pair | exact_id | accepted |
| openalex-author.agree-dblp-homonym-differs | pair | exact_id | conflict |
| openalex-author.exact | pair | exact_id | accepted |
| dblp-prior.same-name | pair | deterministic | accepted |
| name.canonical-equivalent | pair | deterministic | accepted |
| dblp-prior.distinct-homonym | pair | deterministic | rejected |
| score.accept | pair | probabilistic | accepted |
| score.reject | pair | probabilistic | rejected |
| score.unresolved | pair | probabilistic | unresolved |
| score.llm-band | pair | probabilistic | unresolved |
| llm.adjudicated | pair | llm | any |
| cluster.guard.orcid | cluster-guard | inherited | conflict |
| cluster.guard.same-work | cluster-guard | inherited | conflict |
| cluster.guard.dblp-distinct-homonym | cluster-guard | inherited | conflict |
| cluster.guard.dblp-disambiguation-profile | cluster-guard | inherited | unresolved |
| cluster.guard.conflict-pair | cluster-guard | inherited | conflict |
| cluster.guard.rejected-pair | cluster-guard | inherited | unresolved |
| dblp-prior.cluster | assignment | deterministic | accepted |
| cluster.merged | assignment | any | accepted |
| cluster.flag.multiple-orcids | assignment | any | conflict |
| cluster.flag.same-work-mentions | assignment | any | conflict |
| work.doi.exact-link | work | exact_id | accepted |
| work.openalex-work.exact-link | work | exact_id | accepted |
| work.j04-conflict | work | exact_id | conflict |
| work.doi.shared-by-dblp-works | work | exact_id | conflict |
| work.arxiv.shared-by-dblp-works | work | exact_id | conflict |
| work.landing-url.shared-by-dblp-works | work | exact_id | conflict |

## Known limitations

- m3: a stratum name records the path and outcome of the sampling frame; memberships whose own decision differs are listed. Metrics always use each membership's own decision, so only the stratum names are affected.
  - path=deterministic;outcome=accepted;family=dblp-prior.numbered-name;band=far: 6 of 30 labeled memberships have decision deterministic/conflict 6.
- m4: weighted metrics count decisions (stratum memberships), not distinct mention pairs; a pair that is both a candidate and a dblp-prior pair is counted once per membership. In this gold set: 300 labeled rows, 300 memberships, 0 rows with several memberships.
- Recall gap blocking.middle-name-variants (run report): firstInitialSurnameGroupsSplitByMiddleInitials 6447, dblpNamesInThoseGroups 97681. Middle-name and middle-initial variants are never compared: the initials-surname key keeps every given-name initial (John A. Smith -> j a smith, John Smith -> j smith) and the dblp-base-name key keeps the full base name, so two spellings that differ only by a middle name or middle initial share no block, get no MatchCandidate and stay in their own dblp-prior clusters. This is a recall gap of the blocking (author-name-key/1 and the configured families), not of the er-v1 weights; adding a blocking family (for example first initial plus surname) is a human decision.
- Blocking coverage (upstream run report): 43466 oversize-block members are in no emitted block and get no match candidates (per family: dblp-base-name 1770, initials-surname 41696); fallback max block size 200, max block size 200. They keep only their dblp-prior cluster. Upstream run is the ER input run: true. ER run report, mentions without any candidate: 88663.

(The file ends with two newline characters after the last line.)