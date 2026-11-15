# Review report 02: code-reviewer, Phase 5 run 2

Phase 5, run 2 of 2. Agent reports reproduced verbatim from the orchestrator session, in the order they were returned.

- Review 1: REQUEST_CHANGES (findings 1-7). Repaired in repair-09.md (repairs 20-30).
- Review 2: APPROVE (minor findings M1-M4).

Human gate decisions on review 2:
1. The absent-key rule in repair-29 is kept, as the reviewer recommended.
2. Repair-28 is accepted, at the edge of decision 5.
3. M1-M4 get no repair round in this phase and are accepted risks:
   - M3: the conservative weighted precision interval [82.9%, 100.0%] is stated next to the headline number, and the table row's 100.0% [100.0%, 100.0%] interval is stated to be unreliable. The table fix is deferred to the Phase 16 re-evaluation.
   - M1 and M2: must be fixed before any adjudication run with cache hits, together with run-1 finding m1, and before a live LLM is connected.
   - M4: about 16.9% of the unresolved rate's weight is estimated, with the bound [4.3%, 5.9%].
   - test-engineer's F8 (= M4) and F9 (= M2) are MUnit expected-failure tests (test-report-02.md, report 4).

---

## Review 1

VERDICT: REQUEST_CHANGES

There is one major finding, about how the weighted precision interval is reported. Leakage, the freeze, the numbers and the spec gate items all pass. The fix is small, stays inside er/eval, and decision 5 allows it. The human could also accept it as a documented risk.

FINDINGS

| # | Severity | File:line | Failure input / evidence | Impact | Owner |
|---|---|---|---|---|---|
| 1 | major | modules/entity-resolution/src/main/scala/org/scholarchain/er/eval/Harness.scala:396-425 (bootstrapGroups percentile), rendered at Harness.scala:703-712; output summary.md:45, :58, :349 | Real run. In metrics.json:1374-1376 the weighted precision bootstrap95 is [0.999994303, 1]. The point estimate is 0.9999982 (metrics.json:619-621: 11,155,611.5 / 11,155,631.5). The only FP is row 272. It sits alone in a stratum with population 20 (review-key.jsonl:272, `"population":20,"sampled":1`). Every accepted stratum that carries the weight has zero observed NON_MATCH: deterministic accepted (2+23+30+11+18 labeled rows) and exact_id accepted (16 labeled rows). A percentile bootstrap that resamples only zero-FP rows always gives zero FP for those strata, so they add no variance. A conservative bound for 0 FP in about 100 rows (for example Clopper-Pearson) puts the precision lower bound around 96 to 97%. The printed lower bound is 99.9994%. The cluster-level weighted precision has the same problem (summary.md:349, metrics.json:8553-8555). The agent report says this is "because of rounding" (report 1, RISKS 1). That is wrong: the degenerate cell is the cause, not the rounding. | A headline 95% interval, "100.0% [100.0%, 100.0%]", understates the real uncertainty by orders of magnitude. That breaks the invariant "identity uncertainty is reported, not hidden". The number can be carried into the final report as a claim it does not support. Fix within er/eval, with a test that fails without it: either flag weighted intervals as unreliable when strata holding most of the denominator weight have zero events (name the strata and their weight share), or report a conservative alternative bound. | entity-resolution-engineer |
| 2 | minor | Harness.scala:420-425, rendered at :709-712; summary.md:58 | The weighted unresolvedRate interval is [0.04301, 0.04328] (metrics.json:1382-1385). It is not exactly zero-width, as the report says. Strata are defined by outcome, so this is almost a census quantity. Its only variation comes from collapsed groups that mix weights. | The interval looks like a sampling uncertainty statement, but the design fixes the value. It should be labelled that way. | entity-resolution-engineer |
| 3 | minor | Harness.scala:278-304 (weights population_h / labeledMemberships_h, UNSURE counted in labeled_h); summary.md:60 | summary.md never says that UNSURE rows take a share of the weight but are left out of precision and recall. That treats UNSURE as its own category, not as missing at random. The statement is only in the agent report's ASSUMPTIONS. | A modelling assumption behind the weighted P/R/coverage is not in the evidence document. | entity-resolution-engineer |
| 4 | minor | EvalMain.scala:17 (doc: "--output <new directory>") vs :94-103 (Files.write overwrites) | Existing metrics.json/summary.md from 15:16 were overwritten silently (report 1, FILES MODIFIED). | Earlier derived outputs and their provenance are lost without warning. The risk is low because the outputs are seeded and reproducible. | entity-resolution-engineer |
| 5 | minor | EvalReport.scala:162-191 | EvalReport parses fields of a Hadoop-owned run-report.json (`summary.exportDir`, `settings.scholarchain.blocking.*`, `summary.fallback.uncoveredMembers`). That format is defined in modules/hadoop (Pipeline.scala, FallbackJobs.scala), not in core. | There is no code dependency, and a missing field degrades to "not available". But it is an undeclared format coupling: a Hadoop report rename silently gives "not available". | entity-resolution-engineer (domain-engineer if a core contract is wanted later) |
| 6 | minor | EvalReport.scala:366-372 (after repair-19) | Decision 7 asks for "the always-abstain adjudicator; no live model". summary.md:31 now only says "adjudicator always-abstain, model id none/always-abstain". "No live model" is implied, not stated. | It is a weaker statement than the work order asked for. The human should decide whether the model id is enough. | entity-resolution-engineer (human decision) |
| 7 | minor | EvalReport.scala:249-250 | A policy whose "rules" field is present but not an array throws a raw exception at `rs.arr`. The agent reported this itself. | A malformed policy causes a crash instead of "not available". It is not triggered by er-v1. | entity-resolution-engineer |

PER-ITEM RULINGS

A. Leakage: PASS.
- No matching code reads gold paths or the eval code.
  - A grep of modules/entity-resolution/src/main outside eval/ for `er.eval|gold|agent-runs|GoldLabel|Harness|EvalMain|EvalReport` finds comments only (Csv.scala:9, ReviewQueue.scala:38, :338, :438).
  - A grep for the identifier `\beval\b` outside eval/ finds nothing.
  - The dependency runs one way, eval to review.
  - The gold path reaches code only through `--gold` (EvalMain.scala:52).
  - tests/contracts/.../er/LeakageDetector.scala adds a token, class-file and TASTy check.
- er-v1.json:
  - I cannot compute hashes (read-only tools). The orchestrator's check that the current sha equals the sha of `git show 2297990:` is the byte-identity evidence.
  - The git status snapshot shows er-v1.json unmodified.
  - The policy sha recorded in the manifest equals 4946b350... (summary.md:29).
  - The file has no label-derived fields.
- The work since HEAD touches only er/eval. In the git status snapshot the only tracked source changes are er/eval/EvalMain.scala and er/eval/Harness.scala. The untracked source files are er/eval/EvalReport.scala, the module test EvalReportSuite.scala and the test-engineer suite.
- modules/core and modules/hadoop show no changes against HEAD.
- Caveat: I cannot run `git diff 2297990` myself. HEAD 68d8726 is a human docs commit, and the agent reports observing `git diff --name-only 2297990 -- modules/entity-resolution/src/main modules/core modules/hadoop` listing only the two eval files.
- Strong extra evidence: the queue and key sha256 in the eval provenance (25a43e0e..., 89a1da5d...) equal the hashes in the committed run-1 report (docs/agent-runs/phase-05/agent-report-er.md:442-443). So the decisions predate the labels and were not regenerated.

B. Numbers: PASS.
- Unweighted confusion matrix:
  - Row sums against review-key.jsonl row-level outcomes (grep counts): accepted 98, rejected 52, unresolved 109, conflict 41, which equal 93+1+4, 1+49+2, 12+59+38 and 6+26+9.
  - Column sums against gold-labels.csv: MATCH 112, NON_MATCH 135, UNSURE 53, which equal the matrix columns.
- metrics.json:592-611 equals summary.md:127-130. Headline values agree:
  - P 93/94: metrics.json:522-524
  - R 93/112: metrics.json:539-541
  - coverage 144/247: metrics.json:558-560
  - unresolved 109/300: metrics.json:575-577
  - weighted values: metrics.json:619-670 and :680-699
- Error rows checked in both the key and the gold file:
  - Row 272 (FP): key line 272 has accepted/probabilistic/initials-surname.coauthor, p 0.9002495108803148, the same features and score.accept. Gold line 273 has NON_MATCH, evidence orcid;other. The weighted FP of 20.0 equals population 20 divided by 1 labeled row.
  - Row 44: key unresolved/exact_id with cluster.guard.dblp-disambiguation-profile, orcid.exact. Gold MATCH, orcid.
  - Row 225: key conflict/deterministic/dblp-prior with cluster-multiple-orcids. Gold MATCH, dblp_numbered_name.
  - Row 136: key rejected/probabilistic, p 0.23147521650098232, population 1514. Gold MATCH. The weighted rejected×MATCH of 1514.0 is consistent with this.

C. Spec gate items:
- Exact-ID conflicts do not silently merge: PASS.
  - er-v1.json:128 (orcid.disagree gives conflict, never merged), :141 (cluster.guard.orcid refuses the merge), :149 (cluster.flag.multiple-orcids is flagged conflict).
  - In the gold set, 15 exact_id conflict rows are recorded.
  - Rows 247, 225 and 123 stay inside one dblp-prior cluster (sameFinalCluster true, key line 225) but are flagged conflict with a reason code. That is not silent. It is the run-1 design, and the human should be aware of it.
- The LLM can abstain: PASS.
  - The schema enum (adjudication-output-schema-v1.json:10), the prompt (adjudication-prompt-v1.txt:15) and the mapping in LlmStage.scala:215-216 (Abstain gives Unresolved with llm-abstain) all allow it.
  - The run report shows unresolved 1000 with reason llm-abstain 1000.
- Evaluation examples are traceable: PASS. Every error row carries its queue row number, dblp keys and URLs (summary.md:153 onward), joined from the label-blind queue (EvalReport.scala:72-87).
- Gold labels come from the human: PASS, as far as I can check.
  - The file is untracked, so there is no git history. Its sha fc39c15f... was reported unchanged at the start and end of both rounds, and the orchestrator checked it.
  - It has one labeler, and the labeledAt column is filled.
  - The guard hook blocks agent writes to docs/agent-runs/** (guard-dangerous.sh:90, :350).
  - The harness writes only to `--output` (EvalMain.scala:94-103).
- Precision and recall are reported separately: PASS (separate columns, summary.md:42-56).
- No data leakage: PASS (see A).

D. Decision 7 completeness: PASS. Each item is in summary.md:
- P/R/F1, the 4×3 confusion matrix, coverage and unresolved rate: :44, :127
- breakdowns by path, family and stratum, and weighted estimates with intervals: :45-121, :136. The interval quality is finding 1.
- LLM-empty statement: :143-145
- dblp_numbered_name-only reported separately: :55-56
- UNSURE excluded and counted (53), unlabeled rows 0: :11
- error analysis (1 FP and 10 of 19 FN, with features, rules, reason codes and dblp keys): :147 onward
- threshold sweep marked reporting only, with the "(run)" row: :223, :314
- work resolution exact-only, coverage, conflicts, and the "not implemented (finding 11)" statement: :367-375
- policy, thresholds, feature definitions, prompt and schema versions, model id: :29-31, :377-427
- gold set size and scope (16 venues): :3, :9-10, :34
- m3 (6 of 30 dblp-prior memberships are conflict): :431-432
- m4: :433
- middle-name gap (6447 groups / 97681 names): :434
- 43466 uncovered members, cap 200: :435

E. Questions raised during the run:
1. Reading the Hadoop run report: acceptable under CLAUDE.md. There is no code dependency on `hadoop`, it is a read-only optional file input, it is hashed in provenance, and its run ID is cross-checked (upstreamRunMatchesErInputRun true, metrics.json:8663). It is report-only and never enters matching. The format coupling is finding 5 (minor).
2. repair-19: within decision 5 in substance. It removed a claim the inputs did not support, a provenance fix of the same class as F1. It has a test that fails without it, and nothing in matching changed. It was self-initiated beyond the routed scope, so the human should ratify it, as the agent flagged. See finding 6 for the wording now required.
3. Statistics:
   - Wilson score: correct formula and z (Harness.scala:134-143), with n equal to unweighted labeled units. Correct.
   - Stratified bootstrap of labeled rows with fixed design weights, and collapsing of single-row strata: an acceptable, disclosed approximation. Pooling strata with different weights makes group totals vary across replicates, which is conservative.
   - Percentile indices: fine.
   - Weights population_h / labeled_h with UNSURE in the denominator: defensible. It treats UNSURE as its own category rather than missing at random, but summary.md does not state it (finding 3).
   - Weighted precision [100.0%, 100.0%] with 20 estimated FP: the cause is the degenerate zero-event cells, not rounding (finding 1, major).
   - Weighted unresolved interval: not exactly zero-width, and design-determined (finding 2).
4. Pre-existing eval outputs: no leakage concern. The outputs are derived, seeded and reproducible, and they were byte-identical to the unchanged-harness baseline. The decisions predate the labels (queue and key hashes match the committed run-1 report). Their origin is unexplained, and the likely cause is someone running the documented command at agent-report-er.md:170. The human should confirm whether they ran it. The silent overwrite is finding 4.
5. Command deviations: none can be verified from the reports, because exact command lines were not quoted. These forms could write: `sed -i` (only `sed -n` is reported), `sort -o`, `uniq IN OUT` with a second operand, `echo` or anything with `>`/`>>` redirection, and `git diff --output=`. `cut`, `paste`, `tail -f | grep -m1`, `ps`, `cd` and `git diff --no-index` without --output are read-only. `git status` may refresh .git/index, which is harmless. The agents also wrote logs to the scratchpad (eval-real.log), which is allowed. Nothing shows a write outside the scratchpad, er/eval, the module test tree or the eval output folder.

STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 5 run 2, step 3: final review under docs/agent-runs/phase-05/prompt-02.md decision 9 (plus orchestrator questions A-E)
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools: Read, Grep, Glob)
TEST RESULTS: Not run (no Bash). I did not verify the reported sbt check (1049 module tests, 273 contract tests) by observation. I read EvalReportSuite.scala: 14 tests with concrete assertions, none of which would pass if the behaviour were broken.
VALIDATION RESULTS:
- Confusion matrix marginals match review-key.jsonl and gold-labels.csv.
- Four error rows (272, 44, 225, 136) match the key and the gold file.
- summary.md matches metrics.json.
- Queue and key sha256 match the committed run-1 report.
- No reference to eval or gold from matching code.
ASSUMPTIONS:
- er-v1.json byte-identity and the gold sha rest on the orchestrator's shasum and `git show` check. I cannot hash.
- "Diff vs 2297990 touches only er/eval" rests on the git status snapshot and the agent-observed `git diff --name-only`. HEAD 68d8726 is assumed to be docs-only.
RISKS:
- Finding 1 would let an overconfident weighted precision interval into the phase evidence.
- test-engineer may still be adding tests under tests/contracts/.../er/. I did not review those in depth.
MANUAL ACTION REQUIRED:
- Human: confirm the origin of the 15:16 eval outputs.
- Human: ratify or reject repair-19 and decide on finding 6.
- Human: decide whether to repair finding 1 or accept it as a documented risk.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: The orchestrator routes finding 1 (and optionally 2 to 7) to entity-resolution-engineer for one repair round under decision 5 (er/eval only, a test that fails without the change, sbt check). Then test-engineer if harness code changes, then a fresh code-reviewer pass, then the human gate.

Relevant files:
- /Users/brian/Developer/repos/scholarchain/modules/entity-resolution/src/main/scala/org/scholarchain/er/eval/Harness.scala
- /Users/brian/Developer/repos/scholarchain/modules/entity-resolution/src/main/scala/org/scholarchain/er/eval/EvalReport.scala
- /Users/brian/Developer/repos/scholarchain/modules/entity-resolution/src/main/scala/org/scholarchain/er/eval/EvalMain.scala
- /Users/brian/Developer/repos/scholarchain/modules/entity-resolution/target/phase-05-real/attempt-02/run=9f96c70b5115294dcc9d3b2d1d4438b9/eval/metrics.json
- /Users/brian/Developer/repos/scholarchain/modules/entity-resolution/target/phase-05-real/attempt-02/run=9f96c70b5115294dcc9d3b2d1d4438b9/eval/summary.md
- /Users/brian/Developer/repos/scholarchain/modules/entity-resolution/target/phase-05-real/attempt-02/run=9f96c70b5115294dcc9d3b2d1d4438b9/review-queue/review-key.jsonl
- /Users/brian/Developer/repos/scholarchain/docs/agent-runs/phase-05/gold/gold-labels.csv
- /Users/brian/Developer/repos/scholarchain/docs/agent-runs/phase-05/agent-report-er.md

---

## Review 2

VERDICT: APPROVE

I found no critical or major problems. I verified four minor findings. They should be routed to entity-resolution-engineer for a later round or recorded as accepted risk. None changes a number in this run's outputs.

## Findings

| # | Severity | File:line | Failure input / evidence | Impact | Owner |
|---|---|---|---|---|---|
| M1 | minor | modules/entity-resolution/src/main/scala/org/scholarchain/er/eval/EvalReport.scala:132-136 and 196-197 | `runDecided` decides "no accepted or rejected pair" from the accepted/rejected keys being absent. It does not check that the counts add up to pairs sent. Input: run report `llm = {pairsSentToAdjudicator: 10, outcomes: {unresolved: 4}}`. The statement then says "records no accepted or rejected pair (outcomes: unresolved 4), so every pair sent to the adjudicator stayed unresolved". In the same paragraph, the repair-29 count sentence says "the accepted count is not available". The test at EvalRepairRound2Suite.scala:554-557 asserts only the count sentence. | Two claims in one statement contradict each other. The "every pair stayed unresolved" claim treats an absent key as 0, which breaks the never-defaulted rule. The real run is not affected: its outcomes add up to 1000 sent. | entity-resolution-engineer |
| M2 | minor | EvalReport.scala:127-130, 148-149, 165; producer at run/Report.scala:94-98 | `outcomes` and `reasons` are counted over every LLM value, cached ones included (Report.scala:96, 98). `pairsSentToAdjudicator` counts non-cached pairs only (Report.scala:94). The "all sent pairs are accounted for" check compares these two. It also accepts non-whole counts (`validCount` allows 999.5 plus 0.5). Example: if cache hits are above 0, the check fails, so the result is safely "not available". But `llm-abstain` can then be larger than sent, and the "all N abstained" branch can be wrong. | Edge cases only. This run has `pairsAnsweredFromCache: 0` (metrics.json:7996), so its sentence is right. | entity-resolution-engineer |
| M3 | minor | Harness.scala:725 (`line`); summary.md:52 | The headline weighted row still prints `100.0% [100.0%, 100.0%] (unreliable)` in the column headed "Precision (95%)". The cell gives neither the zero-event bound nor a pointer to it; the bound appears only in the reliability paragraph (summary.md:69). The agent's repair-20 description says "The bound is shown in the table rows". That claim does not match the code or the output. | Anyone who quotes the table row without the reliability paragraph keeps a collapsed interval. "(unreliable)" is right next to it, so the risk is limited. Recommendation: add "zero-event bound [82.9%, 100.0%]" to the cell, or a footnote marker. | entity-resolution-engineer |
| M4 | minor | IntervalReliability.scala:227-228 (compare line 161) | `designDetermined` is set for the whole metric without checking that every stratum's memberships agree with that stratum's outcome. That check happens only per stratum, at line 161. In this run, unresolvedRate has 16.9% zero-event share (metrics.json:2282) and a bound of [4.3%, 5.9%], so part of it is estimated. The note still says the rate is "fixed by the strata's population decision counts". `designDetermined` also always overrides the unreliable flag. | The wording overstates how fixed the rate is. It does not hide anything here: the 16.9% share is below the 50% flag line, and the m3 note mentions mismatched memberships. | entity-resolution-engineer |

## Ruling A: leakage (decision 9)
- **Imports.** Grep for `er\.eval`, `eval\.`, `Harness`, `GoldSet`, `EvalUnit` and `org.scholarchain.er._` across modules/*/src/main matches only the five files inside er/eval. IntervalReliability imports only `scala.annotation.tailrec` and uses only `Harness.fieldOf` and `EvalUnit`, both inside eval.
- **Gold path.** Outside eval, "gold" appears only in comments: ReviewQueue.scala:338, Csv.scala:9 and core StableIds.scala. No matching code reads the gold path.
- **er-v1.json.** I cannot hash files with read-only tools. I rely on the orchestrator's check (4946b350…2807ac at start and end), which agrees with the agent's report.
- **Diff scope.** I cannot run `git diff`. The session git status shows tracked changes in modules/*/src/main only to er/eval/EvalMain.scala and Harness.scala. The untracked main sources are only er/eval/EvalReport.scala and IntervalReliability.scala. The diff therefore stays inside er/eval.
- **Ruling: no leakage.**

## Ruling B: numbers
Confusion matrix (summary.md:142-145):
- **Row totals.** I counted the top-level outcome in review-key.jsonl: accepted 98, rejected 52, unresolved 109, conflict 41. These equal the matrix rows.
- **Column totals.** The gold file has MATCH 112, NON_MATCH 135, UNSURE 53. These equal the matrix columns.
- **Unweighted metrics, recomputed from the matrix:**
  - P = 93/94 = 98.9
  - R = 93/112 = 83.0
  - F1 = 90.3
  - coverage = 144/247 = 58.3
  - unresolved = 109/300 = 36.3
- **Weighted metrics, recomputed from the weighted matrix:**
  - P = 11155611.5/11155631.5 = 100.0
  - R = 11155611.5/11709536.7 = 95.3
  - F1 = 97.6
  - coverage = 17585020.1/19013240.3 = 92.5
  - unresolved = 873558.0/20248979.0 = 4.3

These are unchanged from round 2.

Error rows, checked against the key and the gold file:
- **Row 272 (FP):** key says accepted, probabilistic, initials-surname.coauthor, p 0.9002495108803148, score.accept, no reason codes. Gold line 273 says NON_MATCH, evidence orcid;other.
- **Row 44 (FN):** key says unresolved, exact_id, dblp-base-name, rules cluster.guard.dblp-disambiguation-profile and orcid.exact, reason merge-refused:dblp-disambiguation-profile. Gold line 45 says MATCH, orcid.
- **Row 247 (FN):** key says conflict, deterministic, dblp-prior.numbered-name, rule dblp-prior.cluster, reason cluster-multiple-orcids. Gold line 248 says MATCH.
- Mention IDs and candidate IDs match for all three.

Summary.md against metrics.json:
- All eight zero-event bounds in summary.md:69-73 and 368-370 match metrics.json, at lines 1402-1404, 1520-1522, 1918-1920, 2284-2286, 2301-2303, 9505-9507, 9951-9953 and 10384-10386.
- runCounts (8009-8014) and replacedStatus (10521) match.
- formatVersion is 1.

Ruling: the reported numbers match the metrics file and the decision files.

## Ruling C: spec gate items
- **Exact-ID conflicts do not silently merge.** The matrix has a separate conflict row (41). Row 44, an ORCID exact match, is unresolved with merge-refused. The work-resolution section says shared identifiers become conflicts and are never merged. Pass.
- **The LLM can abstain.** The run report shows 1000 pairs sent and llm-abstain 1000, and all 7 labeled llm rows are unresolved with llm-abstain. Pass.
- **Examples are traceable to source records.** Error rows carry dblp keys, dblp URLs, mention IDs and candidate IDs. Pass.
- **Gold labels are human.** The gold file is human-owned and untracked in git, with one labeler. Its sha is unchanged (orchestrator check), and no agent report claims to have written it. Pass.
- **Precision and recall are reported separately.** Separate columns at every level. Pass.
- **No leakage.** See A. Pass.

## Ruling D: previous findings, F6 and F7
- **Finding 1 (repair-20): resolved.**
  - **Detection rule:** sound. A share in an estimated part of the metric counts as a zero event when x = 0, x = m, or m = 0. Shares fixed by the design are excluded.
  - **Clopper-Pearson:** correctly applied. `1 - 0.05^(1/m)` is the exact one-sided 95% limit for 0 events in m (and by symmetry for m in m). It is applied per stratum and per share; each share's base is the right subset (u1, u2, u3).
  - **Combining strata:** done by Dinkelbach fractional programming over independent weight and rate boxes, which gives the correct minimum and maximum of the ratio. Moving every zero-event stratum to its limit at once is a conservative corner, not a joint interval. The text says exactly that.
  - **50% flag threshold:** arbitrary but documented.
  - **Plausibility of the bounds:**
    - Precision lower bound: my hand estimate is about 83%; reported 82.9%. Strata at n = 22, 22, 18, 11 and 7 give limits of about 0.873, 0.873, 0.847, 0.762 and 0.652, and the small strata pull it a little lower.
    - Recall upper bound = point estimate 95.3%. This is expected: no non-accepted stratum is all-MATCH.
    - Cluster precision [73.1%, 100%] is wider than pair level. This fits: no share is fixed at cluster level.
    - F1 = f1(0.829, 0.849) = 0.839, consistent.
  - **Labelling:** adequate, with M3 open.
- **Finding 2 (repair-21): resolved,** with the wording issue in M4.
- **Finding 3 (repair-22): resolved.** The weight rule is in design.weightRule and on the "Weighted sample" line.
- **Finding 4 (repair-23): resolved.** The documentation (EvalMain.scala:23-27) now matches the behaviour, and the sha256 of each replaced file is recorded.
- **Finding 5 (repair-24): resolved.** EvalReport.scala:39-50 names the undeclared upstream format and lists the fields read. A field of another type gives "not available".
- **Finding 6 (repair-29 plus repair-19): resolved under the human ruling.** See E.
- **Finding 7 (repair-25): resolved.** A non-array 'rules' field throws IllegalArgumentException naming the role and field (EvalReport.scala:352-358), before anything is written.
- **F6 (repair-26): resolved.** A count that is not finite and non-negative makes the outcomes not available (EvalReport.scala:127-133).
- **F7 (repair-27): resolved.** Both outputs are rendered before `createDirectories` or any write (EvalMain.scala:110-114). A residual risk remains: an I/O failure between the two `Files.write` calls is not atomic.

## Ruling E: repair-29
- **The count sentence is grounded.** It reads only `llm.pairsSentToAdjudicator`, `llm.reasons.llm-abstain` and `llm.outcomes`. Nothing comes from the adjudicator's name or model ID (EvalReport.scala:141-183).
- **Abstention is stated only from the llm-abstain count.** "All" appears only when that count equals pairs sent.
- **"no live model" is gone.** Grep finds "live model" or "attest" 0 times in eval/metrics.json and eval/summary.md.
- **The attestation argument is fully removed.** "attestation" appears nowhere in modules or tests, and EvalMain's allowed arguments (lines 43-57) have no such option. "no live model" remains only as a forbidden-phrase assertion in a test (EvalReportRepairRoundAdversarialSuite.scala:321) and in the repair-19 negative test (EvalReportSuite.scala:396-399).
- **Recommendation on the absent-key rule:** keep it. It is a derivation, not a default:
  - The writer (Report.scala:96, `groupMapReduce`) emits only outcomes that occurred, so an absent key means 0 by construction.
  - The rule requires an independent conservation check (the counts add up to pairs sent).
  - The output text and `runCounts.rule` both state it.
  - Before relying on it in a run with cache hits, fix M1 so the older evidence sentence obeys the same rule, and M2 so the check compares against sent plus cached and requires whole counts.
  - Switching to "always not available" would also be acceptable. It would discard an inference the data supports.

## Ruling F: repair-30 and stale outputs
- **Stale label works.** Replaced files are labelled stale and "not a source of any number" in metrics.json (`outputs.replacedStatus`, line 10521) and in summary.md line 32.
- **Source files are named.** The agent's report names the 21:37 metrics.json and summary.md, with sha256, as the source of every number.
- **Files on disk.** Glob shows only those two files under the run's eval/. The three earlier eval/ copies were replaced in place under the documented overwrite rule; only their sha256 survive. In the scratchpad, eval-baseline/, eval-verify/ and eval-verify-final/ are all present. Without hashing I cannot confirm that eval-baseline holds the 15:14-15:16 outputs (3b991c32…/0cfbc327…).
- **Nothing was deleted with a command.** The agent's command list has no delete.
- **Decision 5:**
  - Both repairs change only er/eval and its tests, have tests that fail without them, and are reported as repairs.
  - Repair-30 carries out a human ruling, so it is within scope.
  - Repair-28 (the off-grid note) improves reporting clarity rather than "making the evaluation run correctly", so it is at the edge of decision 5. It is harmless and does not affect matching. I recommend the human record its acceptance.
- **Repair-19:** keeping it is consistent with the human rule, and with repair-29.

## Ruling G: new defects from round 3
- **Renderer test seam.** `EvalMain.run(args, render)` is public and `main` always passes `Harness.markdown`. It is acceptable and changes no production behaviour.
- **All-or-nothing writes.** Correct for computation and rendering failures. Writes are still not atomic against I/O failure; I list this as a risk, not a finding.
- **Schema additions with formatVersion 1 unchanged.** All additions are new fields. No code reads `scholarchain.er.evaluation` (grep), and it is not a core contract. Acceptable; a version bump is optional.
- **New defects:** only M1-M4.

STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 5 run 2, step 3: fresh final review after the round-3 repair (docs/agent-runs/phase-05/prompt-02.md, decision 9)
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools)
TEST RESULTS: not run by the reviewer. /Users/brian/Developer/repos/scholarchain/.claude/.last-test-run reads timestamp 2026-11-15T04:11:01Z (22:11 CST), module_tests=1065, contract_tests=299. That agrees with the agent's reported `sbt check` success. test-engineer may add tests after this, which would make the marker stale.
VALIDATION RESULTS: The confusion matrix margins match review-key.jsonl and gold-labels.csv by count. Three error rows (272, 44, 247) match the key and the gold file field by field. Recomputed unweighted and weighted metrics match. summary.md bounds and counts match metrics.json. I found no import of the eval code and no gold-path read outside er/eval.
ASSUMPTIONS:
- File sha256 values, including er-v1.json, the gold file and the outputs, are taken from the orchestrator. I cannot hash files.
- The diff scope comes from the session git status snapshot, because I cannot run git.
- The contents of the 15:14 stale copy are as the agent reports.
RISKS:
- M1/M2 would make the LLM statement contradict itself, or misstate counts, in a future run that has cache hits or incomplete outcome counts.
- Output writes are not atomic against I/O failure.
- The headline weighted precision interval is collapsed and still printed (M3).
MANUAL ACTION REQUIRED: The human decides:
- (1) the absent-key rule (recommendation: keep it, and fix M1/M2 later);
- (2) whether to accept repair-28 as within decision 5;
- (3) whether M1-M4 are repaired now or recorded as accepted risk.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Human phase gate for Phase 5 run 2, with optional routing of M1-M4 to entity-resolution-engineer.

Files reviewed:
- /Users/brian/Developer/repos/scholarchain/modules/entity-resolution/src/main/scala/org/scholarchain/er/eval/IntervalReliability.scala
- /Users/brian/Developer/repos/scholarchain/modules/entity-resolution/src/main/scala/org/scholarchain/er/eval/EvalReport.scala
- /Users/brian/Developer/repos/scholarchain/modules/entity-resolution/src/main/scala/org/scholarchain/er/eval/EvalMain.scala
- /Users/brian/Developer/repos/scholarchain/modules/entity-resolution/src/main/scala/org/scholarchain/er/eval/Harness.scala
- /Users/brian/Developer/repos/scholarchain/modules/entity-resolution/src/main/scala/org/scholarchain/er/run/Report.scala
- /Users/brian/Developer/repos/scholarchain/modules/entity-resolution/src/test/scala/org/scholarchain/er/EvalRepairRound2Suite.scala
- /Users/brian/Developer/repos/scholarchain/modules/entity-resolution/target/phase-05-real/attempt-02/run=9f96c70b5115294dcc9d3b2d1d4438b9/eval/metrics.json
- /Users/brian/Developer/repos/scholarchain/modules/entity-resolution/target/phase-05-real/attempt-02/run=9f96c70b5115294dcc9d3b2d1d4438b9/eval/summary.md
- /Users/brian/Developer/repos/scholarchain/modules/entity-resolution/target/phase-05-real/attempt-02/run=9f96c70b5115294dcc9d3b2d1d4438b9/review-queue/review-key.jsonl
- /Users/brian/Developer/repos/scholarchain/docs/agent-runs/phase-05/gold/gold-labels.csv (read only)
