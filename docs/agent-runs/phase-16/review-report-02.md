# review-report-02: Phase 16, session 2, step 10 re-check of repair round 01 (code-reviewer)

## Summary

All 7 findings in scope are **RESOLVED**: P16-A-09, P16-B-01, P16-A-02, P16-B-02, P16-B-04, P16-B-05 and P16-C-01. The consumer interface re-check (docs/evaluation.md :107, :162, :164, :166) is also correct.

The overall verdict is still **REQUEST_CHANGES**, because of one new major finding, P16-K-01. It is in rag's L2 absence check, which step 4 wrote. The repair round did not cause it: the code before the repair also marks the same input `verified`. It falls in the same impact class as P16-A-09, and P16-A-09's own new test builds the bundle that triggers it. Two new minor documentation findings: P16-K-02 and P16-K-03.

## Verdict per finding

| ID | Verdict | Evidence |
|---|---|---|
| P16-A-09 (rag) | RESOLVED | I traced by hand against ClaimVerification.scala:145-183 and :346-390. **Reviewer input 1** ("…not a direct coauthor of Bob Jones; they are connected within 3 hops.") matches no `AbsencePhraseRe` branch: "not a direct": `a` is an allowed filler word, but `direct` is not one of the path words the negation must reach. The "Although they never coauthored…" variant fails the same way: `coauthored` is neither a filler nor a target. So `spans` is empty. "3 hops" then goes through the hop check at :384-389. `paths` is empty and completeDepths is [6], and 6 >= 3, so the result is HopsConflict and **Conflicting**. **Reviewer input 2** ("connected by 3 hops in 2015-2025, not within 6 hops in 2021-2023."): the third branch matches "not within 6 hops". The clause runs from the `, ` to the final `.`. "6 hops" is inside it (6 <= 6, checked). "3 hops" is outside it, and against paths=[2] that gives HopsConflict and **Conflicting**. With "2 hops" the claim is checked and the result is Verified. L2's intended cases still behave the same (tests :65-129). The system's own sentences (Synthesis.scala:623, Stages.scala:666) still count as absence statements (:191-192). The rag report shows that a temporary revert fails exactly the 3 new tests. No threshold, reason text or `Reasons.all` entry changed. The only new false-verified path I found already existed before the repair; see P16-K-01. |
| P16-B-01 (build) | RESOLVED | docker/emulator/Dockerfile:40-47 matches ref-emulator-docker-doc.md:8-14 line for line. It uses the documented aarch64 substitution (ADD :41), `rpm -i`, `mkdir`/`chown 1000`, `USER 1000`, `ENV HOME=/home/emulator EMULATOR_BIND_ADDRESS=0.0.0.0` and `ENTRYPOINT ["aws-emulator"]`. The only additions are `--platform=linux/arm64`, `EXPOSE 9297` and comments. The guessed S3 host, tar/curl, useradd and `-b` are gone. Hosts in the file: docs.aws.amazon.com (comment :4), downloads.aws.com (ADD :41), and public.ecr.aws, the documented base image in FROM. No 12-digit runs. The ENV-instead-of-`-b` deviation is recorded in the header (:10-16) for the human gate. Runtime is untested (E14). |
| P16-A-02 (build) | RESOLVED | build.sbt:711 is now `"; storage/EmulatorTest/test ; services/EmulatorTest/test"`, which runs the two suites in sequence and stops after a storage failure. buildPolicyCheck (build.sbt:642) checks the registered aliases with `BuildSelfTest.aliasProblems` (BuildSelfTest.scala:326-342). It rejects a missing alias, a duplicate, the word `all`, and any text other than the exact form. `aliasCaseProblems` (:345-355) runs in buildSelfTest (:229-230) and covers the sequential form, the old `all` form and a missing alias. buildPolicyCheck is part of markTestRun stage 2 (:660), so `check` fails with the old form, which counts as the test that fails without the fix. |
| P16-B-02 (experiment) | RESOLVED | evaluate.py:958-964, :1018-1044, :1200-1207, :1208-1225, :1267-1302, :1381-1388. Phrase events now come only from `own_list_row` (list items and table body rows; headers, separators, headings and prose are excluded). Dedup uses source precedence (gate before summary before log), one to one, and never matches rows of the same file. BLOCKED-ON rows are keyed by (phase, agent) plus `match_step` and are matched against terminal reports first, then against earlier anchors. I re-traced **factory/happy** in full by hand (both phases, every input file). It gives E00001-E00019 with types, steps, `counted` and `duplicate_of` exactly as in expected/events.csv. Expected counts-by-phase, counts-by-agent (including `test-engineer,1,0,0,,0,0,0,0,0,0`), totals (5, 2/3, 1, 2/1, manual 1, hook 2, unclassified 2), audit-sample (13 of 13) and audit-strata all match. In the malformed fixture's totals.csv only the inclusion-rule text changed; the rule texts contain no commas, so CSV quoting is unchanged. I also checked the real-population case from the original finding: phase-11 human-gate.md:168 and orchestrator-summary.md:125 are both list rows with agent "", so the summary row is now `duplicate_of` the gate row. No real terminal report has a step suffix in its file name; I checked the 5 BLOCKED-ON reports in the population. So the new terminal step changes nothing on real data. |
| P16-B-04 (experiment) | RESOLVED | evaluate.py:447-455. A missing or non-64-hex `evaluationCode.sha256` exits with EXIT_MISSING (3), and so does any value other than TOOL_SHA; a match prints a RESULT line. There is no fixture case because fixture runs skip `setup_suite` by design (:437-438). This is disclosed, and E3 covers it. |
| P16-B-05 (experiment) | RESOLVED | r1/r2/r3.json:5 now hold `a1a1…`, `b2b2…`, `c3c3…` (40 hex characters). expected/release-records.csv:2-4 match. Grep `[0-9]{12}` over experiments/evaluation finds 0 matches. |
| P16-C-01 (experiment) | RESOLVED | evaluate.py:2247: `("schema_version", ["schemaVersion"], ["schemaVersion"])`. I re-traced **graph/happy**: sample-a has 3 items and no `record`. The results are run_id 3/3; snapshot, policy, schema_version and item_hash 1/3 each (item 2's `""` does not count as present); dataset, source_record_ids and content_hash 0/3. This matches provenance.csv:2-9. TableB (:10-17) is unchanged, and sample-b has no `version` attribute. Suite :105 states the rule. |
| Pins | CONFIRMED | suite-v1.json:15 and :42 both hold `ba42ad4dba868ab1aa2367ddb9437f841d59eb702e3444cd8f5494416208ecae`. Versions at :14 and :41 are `evaluate-v1-r1`, which matches TOOL_VERSION (evaluate.py:67). The old `07e84842` value appears 0 times. I cannot hash, so the suite's own sha256 (b4e44b29…) is accepted from the orchestrator. |
| Consumer interface re-check | RESOLVED, with two minors | docs/evaluation.md:107 matches suite :105 and evaluate.py:2247. :162 and :164 match suite :187 and evaluate.py:1270-1302. :166 records the change. :58 (exit 3) is still correct. The `evaluate.py:198` (wilson) reference has not moved. Stale text the re-check missed: P16-K-02 and P16-K-03. |

## New findings

| ID | Severity | Interface | Owner | File:line | Failure input and impact |
|---|---|---|---|---|---|
| P16-K-01 | major | no | rag-engineer | modules/rag/src/main/scala/org/scholarchain/rag/runtime/ClaimVerification.scala:364-375 (absence depths checked only against `completeDepths`), :384 (`filterNot(inAbsence)` exempts in-clause hop counts from the found-path check), :397-410 (a year range may match the window of any cited item) | **What goes wrong:** an absence statement is checked only for "some cited complete `path=none` item has `maxDepth >= N`". The check never compares the stated window with that item's window, and it never asks whether a cited found path contradicts the absence. A year range in the claim passes if it equals the window of any cited item.<br>**Input (built from PathAbsenceSuite's own P16-A-09 fixtures, `found` = 2-hop PathFact in FullW 2015-2025 and `noneW1` = complete path=none, depth 6, W1 2021-2023, both cited):** "Alice Smith and Bob Jones are not connected within 6 hops in 2015-2025."<br>**Trace:** "not connected" matches `AbsencePhraseRe`, and there is no separator before the final `.`, so the whole sentence is the absence clause. stated = [6] and completeDepths = [6], so the claim is checked. "6 hops" lies inside the clause, so the hop check at :384 skips it. RangeRe matches 2015-2025, which is in `allowedRanges` because it is `found`'s window. Both names are cited. No conflict and nothing uncovered, so the status is **Verified**.<br>**Why it is wrong:** the cited evidence holds a 2-hop path in 2015-2025, and the only complete search covered 2021-2023. The same thing happens with no found path at all: cite `noneW1` plus any 2015-2025 item, such as a metric. A second input, again with `noneW1` cited: "No other path is shorter than the 2 hops between Alice Smith and Bob Jones." Here "2 hops" counts as an absence depth (6 >= 2), so the result is Verified, although the claim states a 2-hop connection.<br>**Before the repair:** the same result, Verified. This is not a regression; step 4's L2 code has had it since it was written, and R-1b did not report it.<br>**Impact:** an absence claim that the cited evidence contradicts, or that covers a wider window than the search, is kept as verified. That breaks the grounding invariant and "every metric states its time window", which is the same impact class as P16-A-09 (major).<br>**Repair direction (owner decides):** support an absence clause only with a complete path=none item whose window equals the clause's stated window, or the claim's range when there is one, and whose pair is the one named. Treat any cited found path for the same pair and window as a conflict. Add the inputs above as tests that fail today. Under the one-repair-round rule, this goes to the human. |
| P16-K-02 | minor | no | documentation-engineer | docs/emulator.md:24, :178 | :24 says the Dockerfile "is being repaired to the documented form above". :178 says "under repair to the documented form (P16-B-01)". The repair is done: Dockerfile:40-47 is now the documented form. repair-01-docs-recheck.md marked docs/emulator.md "STILL CORRECT", which missed these two lines. Impact: the Teams-post basis describes an open repair that is closed. Suggested status line: "repaired to the documented form (static review against the documentation); runtime: untested". |
| P16-K-03 | minor | no | documentation-engineer | docs/evaluation.md:166 vs :376 | :166 cites `S/repair-01-experiment-engineer.md`, but the list of scratchpad reports the human must copy and E13 must link (:376) does not include that file. Impact: a dangling "S/" reference may survive E13. Fix: add the file to the :376 list. |

## Other checks the work order asked for

- **Production limit, threshold, prompt or default changes:** none. rag changed regexes and the hop and absence logic only; `Reasons.all`, prompts and limits are unchanged. build.sbt changed the alias and the policy check only. evaluate.py's sample size, seeds and z value are unchanged.
- **Secrets and identifiers:** I grepped for twelve-digit runs and `AKIA`/`ASIA` over the Dockerfile, BuildSelfTest.scala, PathAbsenceSuite.scala, evaluate.py and experiments/evaluation/**: no matches. The only hosts in the Dockerfile are the three listed above.
- **Each behavior change has a test that fails without it:**
  - rag: the revert run fails 3 tests.
  - build: the alias self-test, plus buildPolicyCheck inside `check`.
  - experiment: factory/happy and graph/happy now expect the new behavior, so the old code would fail them.
  - P16-B-04 has no fixture case; this is disclosed and checked at E3.
  - The Dockerfile cannot be tested by agents (E14).
- **Full-check evidence:** marker 2026-11-22T04:40:57Z, module_tests 2285, contract_tests 399, per the build-engineer report and the orchestrator's listing. I cannot check file times myself.

## Files read

**In full:**
- modules/rag/src/main/scala/org/scholarchain/rag/runtime/ClaimVerification.scala
- modules/rag/src/test/scala/org/scholarchain/rag/runtime/PathAbsenceSuite.scala
- docker/emulator/Dockerfile
- docs/evaluation.md
- docs/emulator.md
- factory/happy: every input file (phase-00 and phase-01), case.json, and every expected file (events, counts-by-phase, counts-by-agent, totals, audit-sample, audit-strata, unclassified)
- factory/malformed/expected/totals.csv
- graph/happy: case.json, input/sample-a.json, input/sample-b.json, expected/provenance.csv
- reproduce/release-records: input/releases/r1.json, r2.json, r3.json, expected/release-records.csv
- In the scratchpad folder: repair-01-rag.md, repair-01-experiment-engineer.md, repair-01-build-engineer.md, repair-01-docs-recheck.md, ref-emulator-docker-doc.md

**In part:**
- experiments/evaluation/evaluate.py: lines 115-130, 194-201, 380-480, 900-1458, 2225-2334, 2405-2470
- experiments/evaluation/suite-v1.json: lines 1-50, 100-191
- build.sbt: lines 625-717
- project/BuildSelfTest.scala: lines 215-357
- review-report-a1/a2/b/c.md: the finding rows in scope
- docs/agent-runs/phase-11/human-gate.md: 164-170
- docs/agent-runs/phase-11/orchestrator-summary.md: 121-127
- Grep checks over modules/rag/src/main (callers of `statesAbsence`, the system's own absence sentences) and over docs/agent-runs (BLOCKED-ON terminal reports)

**Not read:**
- the rest of evaluate.py, suite-v1.json and build.sbt
- scripts/README.md
- RuntimeFixtures.scala and EvidenceBuilder.scala: the window of each path item is inferred from PathAbsenceSuite:179-183 passing with both ranges
- the other fixtures, which the repair did not change

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: docs/agent-runs/phase-16/prompt.md (sha256 c75cc5f895faed13880a69ec285231c5498fd50924c7b875fe7e02cce1707dc8), session 2 step 10, re-check of repair round 01 (decision 15)
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools)
TEST RESULTS: none run; repairers' reported runs accepted as reported (rag 296/296 plus revert 3 failing; full check 2285 + 399 per marker)
VALIDATION RESULTS: P16-A-09, B-01, A-02, B-02, B-04, B-05, C-01 RESOLVED; consumer re-check RESOLVED with minors K-02 and K-03; new major P16-K-01 (existed before the repair, not caused by it)
ASSUMPTIONS: traces done by hand, not by running code. Path item windows inferred from PathAbsenceSuite:169-184. The suite's own sha256 is taken from the orchestrator. The ref doc is verbatim documentation.
RISKS: P16-K-01 lets a contradicted or wider-window absence claim be shown as verified in any future live answer. evaluate.py fixtures have not run (E1). The Dockerfile has not been built (E14).
MANUAL ACTION REQUIRED: the human decides how to route P16-K-01 (the one repair round is used up; the finding existed before the repair); the human records the ENV-instead-of-`-b` deviation (P16-B-01) at the gate
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: orchestrator triage of P16-K-01 (rag-engineer) and K-02/K-03 (documentation-engineer) with the human
```

VERDICT: REQUEST_CHANGES

| Severity | file:line | Failure input | Impact | Owner |
|---|---|---|---|---|
| major | modules/rag/src/main/scala/org/scholarchain/rag/runtime/ClaimVerification.scala:364-375, :384, :397-410 | "Alice Smith and Bob Jones are not connected within 6 hops in 2015-2025." citing `found` (2 hops, 2015-2025) and `noneW1` (complete, depth 6, 2021-2023) | Verified, although the claim is contradicted and covers a wider window than the search | rag-engineer |
| minor | docs/emulator.md:24, :178 | text says the Dockerfile is "being repaired" / "under repair" | stale status in the Teams-post basis | documentation-engineer |
| minor | docs/evaluation.md:166 vs :376 | S/repair-01-experiment-engineer.md is cited but not on the copy list | dangling S/ reference at E13 | documentation-engineer |
