# Test report: test-engineer

Phase 5, run 1 of 2. Agent reports reproduced verbatim from the orchestrator session, in the order they were returned.

---

## Report 1: test-engineer, round 1 (FAILED; findings F1-F5)

## Summary

I wrote the decision 18 adversarial tests under `tests/contracts/**` and made no production edits. They attack the code through public APIs. `sbt check` ran and **failed** on 2 of the new tests. Both expose defects in `entity-resolution-engineer` code, and both are low severity. Everything else passes:
- module tests: 982 of 982
- contract tests: 183 of 185

Because the check failed, `.claude/.last-test-run` was not refreshed (`markTestRun` refused, as designed).

Checks on the behaviours you asked about. All of these tests pass:
- **LLM cap (1,000 calls vs 1,063 llm decisions):** consistent, not a breach. The cap counts mention pairs, and every candidate ID of a selected pair is labelled `llm`. With cap 3, the test sees 5 band pairs × 2 candidates: 3 adjudicator calls, 6 llm decisions on 3 pairs, and 4 decisions on the probabilistic path with `llm-band-cap-reached`. Cap 0 gives 0 calls.
- **34 UNRESOLVED decisions on `exact_id`:** these come from an accepted `orcid.exact` (or `openalex-author.exact`) decision whose cluster merge was refused by the `cluster.guard.dblp-disambiguation-profile` guard. The decision keeps path `exact_id`, with rules `[cluster.guard.dblp-disambiguation-profile, orcid.exact]` and reason `merge-refused:dblp-disambiguation-profile`. It is contract-valid in the core Validator and does not contradict decision 9, which only covers two different ORCIDs.
- **203-member fallback block vs cap 200:** the cap holds. The `largestFallbackBlock` stat also counts blocks that were not emitted. In a synthetic run the stat reads 5 against a cap of 3, while every emitted fallback block is 3 members or fewer. Oversize fallback blocks are counted and listed. Every oversize member is covered or listed uncovered (covered + uncovered = members, for each key and in total).
- **One mention pair under several families:** every candidate of the pair gets the same outcome, path, confidence, rules, reasons and features. Checked on a synthetic export and on real pipeline output.
- **dblp prior keyed on the NFD full-name key:** the test pins what the code does. Precomposed and decomposed spellings of one name form a single prior cluster, which is not a merge. That departs from the literal decision 5 wording ("exact dblp name string") and needs a human ruling (finding F3).

## STATUS: FAILED
OWNER: test-engineer
WORK ORDER: docs/agent-runs/phase-05/prompt.md (run 1 of 2), agent 5 of 6, decision 18 adversarial tests

FILES CREATED:
- /Users/brian/Developer/repos/scholarchain/tests/contracts/src/test/scala/org/scholarchain/contracts/er/ErSynth.scala
- /Users/brian/Developer/repos/scholarchain/tests/contracts/src/test/scala/org/scholarchain/contracts/er/NameKeyAdversarialSuite.scala
- /Users/brian/Developer/repos/scholarchain/tests/contracts/src/test/scala/org/scholarchain/contracts/er/DecisionContractAdversarialSuite.scala
- /Users/brian/Developer/repos/scholarchain/tests/contracts/src/test/scala/org/scholarchain/contracts/er/IdentityAdversarialSuite.scala
- /Users/brian/Developer/repos/scholarchain/tests/contracts/src/test/scala/org/scholarchain/contracts/er/LlmAdversarialSuite.scala
- /Users/brian/Developer/repos/scholarchain/tests/contracts/src/test/scala/org/scholarchain/contracts/er/ErDeterminismAndQueueSuite.scala
- /Users/brian/Developer/repos/scholarchain/tests/contracts/src/test/scala/org/scholarchain/contracts/er/GoldAndHarnessAdversarialSuite.scala
- /Users/brian/Developer/repos/scholarchain/tests/contracts/src/test/scala/org/scholarchain/contracts/hadoop/FallbackPipelineEndToEndSuite.scala

FILES MODIFIED: none (no production, module-test, build or docs/agent-runs files touched)

COMMANDS RUN:
- read-only `git status`, `git diff`; `ls`, `cat`, `sed -n`, `grep`, `wc`, `find`, `tail`, `date`
- `sbt -batch "contractTests/Test/compile"`: first attempt failed (a name clash in my test code, which I fixed); second attempt `[success]`
- `sbt -batch "contractTests/testOnly org.scholarchain.contracts.er.*"`: 88 run, 85 passed, 3 failed. One failure was a wrong expectation of mine, now corrected (see Assumptions).
- `sbt -batch "contractTests/testOnly org.scholarchain.contracts.er.* org.scholarchain.contracts.hadoop.FallbackPipelineEndToEndSuite"`: `Failed: Total 98, Failed 2, Errors 0, Passed 96`
- `sbt -batch check` (background): exit=1. Output:
  - `Module tests: suites=38 executed=982 passed=982 failed=0 errors=0 skipped=0`
  - `Contract tests (no AWS): suites=15 executed=185 passed=183 failed=2 errors=0 skipped=0`
  - `Contract tests (no AWS) failed; .claude/.last-test-run was not updated.`
  - Total time 1477 s.

TEST RESULTS (from `sbt check`, per contract suite):

| Suite | Run | Passed | Failed | Skipped |
|---|---|---|---|---|
| er.NameKeyAdversarialSuite (new) | 10 | 10 | 0 | 0 |
| er.DecisionContractAdversarialSuite (new) | 16 | 16 | 0 | 0 |
| er.IdentityAdversarialSuite (new) | 14 | 14 | 0 | 0 |
| er.LlmAdversarialSuite (new) | 29 | 27 | 2 | 0 |
| er.ErDeterminismAndQueueSuite (new) | 8 | 8 | 0 | 0 |
| er.GoldAndHarnessAdversarialSuite (new) | 14 | 14 | 0 | 0 |
| hadoop.FallbackPipelineEndToEndSuite (new) | 7 | 7 | 0 | 0 |
| hadoop.ScaleAndProvenanceAdversarialSuite (Phase 4) | 20 | 20 | 0 | 0 |
| hadoop.RecordFieldAdversarialSuite (Phase 4) | 15 | 15 | 0 | 0 |
| hadoop.JobLevelBlockingSuite (Phase 4) | 6 | 6 | 0 | 0 |
| hadoop.JobLevelTeamScaleSuite (Phase 4) | 4 | 4 | 0 | 0 |
| hadoop.XmlBoundaryAdversarialSuite (Phase 4) | 14 | 14 | 0 | 0 |
| hadoop.JobLevelRerunSuite (Phase 4) | 8 | 8 | 0 | 0 |
| hadoop.UnicodeAdversarialSuite (Phase 4) | 14 | 14 | 0 | 0 |
| hadoop.JobLevelDblpAdversarialSuite (Phase 4) | 6 | 6 | 0 | 0 |
| **Contract total** | **185** | **183** | **2** | **0** |
| Module tests (all modules) | 982 | 982 | 0 | 0 |

Failing tests, with the observed failure message:
1. `LlmAdversarialSuite`, "duplicate JSON keys with conflicting values are rejected, not resolved last-wins" (line 245): "ambiguous reply was accepted: MatchDecision(... Accepted, Llm, 0.95 ...)". The reply `{"outcome":"abstain",...,"outcome":"accept","chosenCandidateId":"<offered>",...}` was accepted, and the pair was merged on the llm path.
2. `LlmAdversarialSuite`, "a cache file answers every pair: no adjudicator call, identical decisions" (line 328): `pairsSentToAdjudicator` obtained 1.0, expected 0.0. All earlier assertions in that test passed: zero adjudicator calls, decisions identical to the first run, `cacheHits` = 1.

VALIDATION RESULTS: `sbt check` FAILED at the contract-test stage only. Format check, `Test/compile` and module tests passed. No `.claude/hooks/validate-agent-files.sh` run was needed (no constitutional files touched).

ASSUMPTIONS:
- My first draft expected an adjudicator exception to leave a line in `llm/rejected-replies.jsonl`. The code documents that file as model replies that failed validation, and an exception produces no reply. I corrected the test to check the reason code on the decisions and the report's `llm.reasons` count instead. This is not counted as a defect.
- A spreadsheet byte-order mark on a gold CSV is deliberately stripped by `Csv.parse`. The test asserts that the row behind it is still read.
- The ER fixtures are my own, not copies of the module fixtures. Their J06 features come from the real `hadoop` `CandidateFeatures.compute`.
- `contractTests` already depends on `entityResolution` and `hadoop` in `build.sbt`, so no build change was needed.

RISKS:
- The band-pair fixtures rely on er-v1 weights placing them at log-odds of about 1.1. A precondition test (`precondition: every synthetic pair is in the LLM band`) fails loudly if the policy changes.
- The ER suites write under `tests/contracts/target/er-work/` and `job-work/` (gitignored build output). Nothing is deleted, so these directories grow with each run.
- `FallbackPipelineEndToEndSuite` adds about 110 s to `sbt check`.

MANUAL ACTION REQUIRED: human ruling on F3 (decision 5 wording versus the implementation keying the prior on NFD).

BLOCKED-ON OWNER: none (status is FAILED, with defects routed below)
BLOCKED-ON EVIDENCE: n/a

NEXT GATE: route F1 and F2 to entity-resolution-engineer (one repair round each), then rerun `sbt check` and code-reviewer (decision 19).

## Failures and findings by owning agent

| # | Finding | Evidence | Owner | Severity |
|---|---|---|---|---|
| F1 | LLM output with duplicate JSON keys is resolved last-wins instead of being rejected, so an ambiguous reply (abstain then accept) is accepted and merges clusters. Decision 12 says malformed output is rejected. | Failing test 1. `OutputValidator.validate` (modules/entity-resolution/.../llm/Adjudication.scala) parses with `ujson.read` into an object, which silently overwrites duplicate keys. | entity-resolution-engineer | Low (a real model rarely emits this, but it bypasses strict validation) |
| F2 | Run report `llm.pairsSentToAdjudicator` counts pairs answered from the cache, so it overstates how many pairs were actually sent. | Failing test 2 (obtained 1.0, expected 0.0 with zero calls). ErRun passes `selected.size` into Report.scala line 91. | entity-resolution-engineer | Low (report accuracy only) |
| F3 | The dblp prior is keyed on `AuthorNameKey.fullNameKey` (NFD, whitespace collapsed), so precomposed and decomposed spellings form one prior cluster. Decision 5 literally says "one cluster per exact dblp name string". | Pinned by passing test "decision 5 check: ..." in IdentityAdversarialSuite. A side effect: the queue, the LLM input and `clusters.jsonl` show only the code-point-smallest spelling (`NameTable.display`), not each mention's spelling as written (decision 13 asks for "the name as written"). | Human ruling, then entity-resolution-engineer if changed | Low on real dblp data (names are nearly always NFC) |
| F4 | Policy file inconsistency: rule `cluster.guard.dblp-disambiguation-profile` declares path `deterministic`, but refused decisions keep their original path, for example `exact_id`. This is the source of the 34 real-run decisions. Contract-valid. | IdentityAdversarialSuite "exact-ID UNRESOLVED ..." (passes). er-v1.json rule entry. | entity-resolution-engineer (document or align) | Info |
| F5 | The `largestFallbackBlock` stat includes fallback blocks that were not emitted, so it can exceed the cap (the 203 vs 200 seen in the real run). Emitted blocks never do. Only the stat's meaning is unclear. | FallbackPipelineEndToEndSuite: stat = 5 with cap 3, all emitted blocks ≤ 3 (passes). | hadoop-engineer (document) | Info |

## Requirement-to-test coverage (decision 18)

| Requirement | Tests (suite: test) | Result |
|---|---|---|
| Conflicting ORCIDs never merge (pair level) | Identity: dblp ORCIDs under one name → CONFLICT `orcid.disagree`; J04 mention-link ORCID vs dblp ORCID → CONFLICT. DecisionContract: Validator rules | pass |
| Conflicting ORCIDs never merge (cluster level) | Identity: transitive chain test; "no final cluster holds two different ORCIDs unless its prior did"; a prior cluster with two ORCIDs is kept whole and flagged on every member | pass |
| Two mentions on the same work never merge | Identity: direct `structural.same-work` CONFLICT; a later accepted pair is refused with `merge-refused:same-work`; a prior cluster with same-work mentions is kept whole and flagged | pass |
| NFC/NFD and punctuation variants share a key; Cyrillic/Latin look-alikes do not | NameKey: 10 tests, including Greek and Ukrainian look-alikes, NBSP and other whitespace, no NFKD folding, plus the same rule checked in hadoop J06/J05b and in ER NameParts | pass |
| Initials collisions | NameKey: "J. Smith", "John Smith", "Jane Smith", "John Smith 0003" share only the initials key; given-name relation 1/1/0/2 | pass |
| Transitive chain A~B, B~C, A and C conflict | Identity: exactly one link applied, the other refused by `cluster.guard.orcid`; A and C stay apart | pass |
| Every mention gets exactly one cluster, oversize-block members included | Identity: orphan mentions; FallbackPipelineEndToEnd: real J01–J09 then ER, with uncovered oversize members assigned | pass |
| Oversize fallback is bounded and counted | FallbackPipelineEndToEnd: primary and fallback caps (including the exact-cap boundary), oversize counters and listings, covered + uncovered = members, uncovered mention sets exact | pass |
| LLM: malformed JSON | Llm: malformed, markdown-fenced, two JSON values, array, empty | pass |
| LLM: fabricated ID | Llm: a well-formed unoffered candidate ID and a researcher ID → `unoffered-candidate`; core Validator rejects an unoffered `chosenCandidateId` | pass |
| LLM: attempt to change a source field | Llm: `name`, `left`, `features`, `year` → `source-field-mutation`; also schema and cited-field attacks; reply text never stored | pass |
| LLM: strict validation | Llm: duplicate-key reply | **FAIL (F1)** |
| LLM: abstention | Llm: abstain → UNRESOLVED `llm-abstain` with the adjudication recorded; low confidence → UNRESOLVED; valid accept merges on the llm path | pass |
| LLM: cache hit and miss for each of the four key parts | Llm: hit, then a miss for each of model, policy, prompt and input hash, then a hit on each variant; a rejected reply is never cached; cache file answers every pair. DecisionContract: ID changes for each of the 4 parts and only those | pass (report count in the cache-file test: **FAIL (F2)**) |
| LLM: band cap | Llm: calls ≤ cap; cap counts pairs; cap 0 makes no calls | pass |
| Determinism across output roots | ErDeterminismAndQueue: same run ID and bytes across output roots and input paths; tampered reuse refused. FallbackPipelineEndToEnd: identical J05/J05b/J05c/J06/J09 parts and ER bytes across roots | pass |
| Missing features stay missing | Identity: absent features omitted; a missing count is not scored as 0 (p(missing) > p(0)) | pass |
| Gold-label reader rejects bad files | GoldAndHarness: 7 tests (unknown, repeated and missing columns; label and evidence values; duplicate pairs in either order; pairs not in the queue; candidate or display mismatch; labeler, date, field count, half-filled rows, malformed CSV; all errors reported) | pass |
| Harness metrics on a hand-checkable synthetic set | GoldAndHarness: precision 3/4, recall 3/5, F1, coverage 7/9, unresolved 2/10, 4×3 confusion matrix, Wilson [0.301, 0.954], weighted 60/80 and 60/64, breakdowns, evidence split, FP/FN totals, 110-point threshold sweep; leakage scan finds no matching code importing `er.eval` | pass |
| Extra checks | volume rule (J06 skips exactly the same-numbered pairs, counts them, and the setting changes the run ID); queue still samples prior pairs with inclusion probability 0.5; label-blind queue; candidate contract violations refused | pass |

---

## Report 2: test-engineer, rerun after F1-F4 repair

The F1–F4 repairs hold up under the new attacks and I found no new production defects. `sbt check` passed with exit 0: module tests 995/995 and contract tests 204/204, so 1199 run, 0 failed, 0 skipped.

**1. Decision 5 test rewritten for the new behaviour.** The old test "precomposed and decomposed spellings form ONE dblp-prior cluster" is gone, and so are the stale comments in the header and at the old line 49. All non-ASCII names are now written as `\u` escapes, because the old file mixed normalization forms. The new tests in `IdentityAdversarialSuite` check:
- **No candidate:** NFC "René Priorname" and NFD "René Priorname" get different ResearcherIds, each from its own mention. Each is accepted on the deterministic path with rules exactly [dblp-prior.cluster] and cluster_prior_clusters 1. Each has its own clusters.jsonl row listing only its own spelling. Both report counts of dblp names equal the number of exact strings, which is more than the number of distinct full-name keys.
- **With a candidate:** the pair decision is accepted, deterministic, rules exactly [name.canonical-equivalent], confidence 0.95. Both whole prior clusters join, including a third mention that has no candidate. The assignments carry cluster.merged with cluster_prior_clusters 2 and cluster_mentions 3. The ResearcherId comes from the earliest mention (the NFD one, 2005). The clusters.jsonl row lists both spellings, priorClusters 2, mergePath deterministic.
- **Merge guard still blocks:** when the two prior clusters hold different ORCIDs on other mentions, the result is CONFLICT. The decision keeps the deterministic path and 0.95 confidence, its rules are {name.canonical-equivalent, cluster.guard.orcid}, its reason is merge-refused:orcid-conflict, and the clusters stay apart.
- **Edge cases:**
  - An NFC/NFD pair with its own different ORCIDs is caught first by orcid.disagree: exact_id CONFLICT, and canonical-equivalent does not fire. With equal ORCIDs it merges through orcid.exact, and the assignment path is exact_id.
  - NFC "0001" vs NFD "0002" is rejected by dblp-prior.distinct-homonym.
  - A Cyrillic look-alike fires neither name rule and is not decided on the deterministic path.
  - Across every pair, dblp-prior.same-name fires only on byte-identical strings and canonical-equivalent only on different strings with equal keys.
- **Decision 13:** I set the identity run's queue size to 200 (QueueSpec(200, 2, 20261114L)), which is larger than the candidate population. Every review-queue row's `left_name` and `right_name` equal that mention's exact source string. Both NFC/NFD candidate pairs are present, each side spelled as its own record, and the CSV keeps the decomposed bytes.

**2. Regression attacks on the repairs.**
- **F1 (duplicate keys):** five replies, each rejected with the reason llm-output-rejected:duplicate-key (never schema), nothing cached, no adjudication ID:
  - identical values repeated
  - a key repeated through a JSON escape (`outcome`)
  - a repeated key in an object inside an array
  - one deep in nested arrays and objects
  - one inside an unknown key's value

  A control test confirms keys differing only by case are not treated as duplicates (rejected as schema instead). An end-to-end run confirms a duplicate-key reply is stored by hash only (the marker text is absent) and counted in the report's reasons and `rejectedReplies`.
- **F2 (report counts):** with a partly warm cache, `pairsSelected` = sent + cached, `pairsSentToAdjudicator` equals the actual adjudicator calls, `pairsAnsweredFromCache` equals `cacheHits`, and the cached pair is never sent. With a cap of 3 and the same cache, a cached pair still counts against the cap and the four capped decisions are flagged. Rejected replies count as sent, never as cached. A cap of 0 gives all zeros.
- **F4 (guard paths):** read straight from the er-v1.json resource, all four cluster-guard rules have path "inherited" and confidence null. Every guard-refused pair decision in the identity run has exactly one guard rule, at least one evidence rule, a concrete path equal to one of its evidence rules' paths, and the fixed confidence of that rule when it has one. None stays ACCEPTED. The orcid, same-work and disambiguation-profile guards are all exercised.
- **LLM input (decision 13):** a band pair whose left mention is spelled "Jöhn" (decomposed) sends that exact string in `left`/`right.name`, not the NFC form.

```text
STATUS: COMPLETE
OWNER: test-engineer
WORK ORDER: docs/agent-runs/phase-05/prompt.md, run 1 of 2, test-engineer rerun after repair round (decisions 5, 13, 18, 21)
FILES CREATED: none
FILES MODIFIED:
  /Users/brian/Developer/repos/scholarchain/tests/contracts/src/test/scala/org/scholarchain/contracts/er/IdentityAdversarialSuite.scala (whole file rewritten; existing tests kept, stale decision-5 test replaced)
  /Users/brian/Developer/repos/scholarchain/tests/contracts/src/test/scala/org/scholarchain/contracts/er/LlmAdversarialSuite.scala (10 tests added)
COMMANDS RUN:
  sbt check (background, log at /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/a78572bd-c42b-4db3-bd71-b2631a490ee2/scratchpad/check1.log)
  read-only: grep, sed -n, ls, find (no -delete/-exec), wc, cat, tail, date
TEST RESULTS:
  sbt check: EXIT=0, "[success] Total time: 1479 s (0:24:39.0), completed Nov 14, 2026, 5:47:40 AM"
  markTestRun: "Updated .claude/.last-test-run (module_tests=995, contract_tests=204)"
  Module tests: 995 run, 995 passed, 0 failed, 0 skipped
  Contract tests: 204 run, 204 passed, 0 failed, 0 skipped (was 184/185; +19 new, 1 replaced)
VALIDATION RESULTS: scalafmt check and compilation passed as part of sbt check
ASSUMPTIONS:
  - A cache entry written from the one-pair export answers the same pair in the five-pair export (same input hash). The assertion held.
  - Which pairs fall under the band cap depends on mention-ID order, so the capped-with-cache assertion works out the expected cached count from the pairs actually adjudicated.
  - A queue larger than the candidate population shows every candidate pair (observed to hold).
RISKS:
  - fullNameKey also collapses whitespace, so name.canonical-equivalent would join two strings that differ only in whitespace, not just in Unicode normalization. dblp names should not differ this way, so I did not attack it; noted for the reviewer.
  - Run 2 output (gold-set evaluation) is not covered here.
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer full-diff review (decision 19), then the real run
```

**Per-suite results (contract tests, from the sbt check log)**

| Suite | Run | Passed | Failed |
|---|---|---|---|
| er.IdentityAdversarialSuite | 23 | 23 | 0 |
| er.LlmAdversarialSuite | 39 | 39 | 0 |
| er.DecisionContractAdversarialSuite | 16 | 16 | 0 |
| er.ErDeterminismAndQueueSuite | 8 | 8 | 0 |
| er.GoldAndHarnessAdversarialSuite | 14 | 14 | 0 |
| er.NameKeyAdversarialSuite | 10 | 10 | 0 |
| hadoop.FallbackPipelineEndToEndSuite | 7 | 7 | 0 |
| hadoop.ScaleAndProvenanceAdversarialSuite | 20 | 20 | 0 |
| hadoop.RecordFieldAdversarialSuite | 15 | 15 | 0 |
| hadoop.JobLevelBlockingSuite | 6 | 6 | 0 |
| hadoop.JobLevelTeamScaleSuite | 4 | 4 | 0 |
| hadoop.XmlBoundaryAdversarialSuite | 14 | 14 | 0 |
| hadoop.JobLevelRerunSuite | 8 | 8 | 0 |
| hadoop.UnicodeAdversarialSuite | 14 | 14 | 0 |
| hadoop.JobLevelDblpAdversarialSuite | 6 | 6 | 0 |
| **Total** | **204** | **204** | **0** |

Module tests (all modules): 995 run, 995 passed, 0 failed.

**Requirement-to-test coverage (this round)**

| Requirement | Tests |
|---|---|
| Decision 5: prior keyed on the exact string; NFC/NFD are two prior clusters | Identity: "decision 5: with no candidate ...", precondition test |
| Decision 5: joining only through an accepted decision (name.canonical-equivalent) | Identity: "an NFC/NFD candidate fires name.canonical-equivalent ..." |
| Decision 5 / 9: differing ORCIDs never merge, even across canonical equivalence | Identity: "canonical equivalence never overrides the ORCID merge guard", "NFC/NFD pair with its own different ORCIDs ..." |
| dblp homonym numbers kept apart; Cyrillic look-alikes kept apart | Identity: homonym test, Cyrillic test |
| dblp-prior.same-name only on identical strings | Identity: "dblp-prior.same-name fires only on byte-identical ..." |
| Decision 13: name as written (queue, CSV, LLM input) | Identity: "decision 13: the review queue ..."; Llm: "the adjudication input carries each mention's exact spelling ..." |
| F1 repair (duplicate keys) | Llm: 5 duplicate-key cases, case-only control, end-to-end duplicate-key run |
| F2 repair (report counts) | Llm: "report: selected = sent + cached ...", "report: rejected replies still count as sent ..." |
| F4 repair (guards inherit their evidence path) | Identity: "every guard-refused decision carries a concrete path ..." |

**Findings**

| Finding | Evidence | Owner |
|---|---|---|
| None open. F1, F2, F3 and F4 now pass these independent regression attacks. | sbt check EXIT=0; contract tests 204/204, module tests 995/995 | n/a |
| Note (not a defect): name.canonical-equivalent uses fullNameKey, which also collapses whitespace, so whitespace-only variants would be joined. | AuthorNameKey.fullNameKey = canonical(collapseWhitespace(name)); Decide.scala, canonical-equivalent branch | entity-resolution-engineer (for the reviewer to rule on) |

Failures by owning agent: none.


---

## Report 3: test-engineer, round after code-reviewer REQUEST_CHANGES (round 1)

## Test report: Phase 5, run 1, repair round (test-engineer)

`sbt check` passed: 1014 of 1014 module tests and 230 of 230 contract tests, with nothing failed or skipped. No production defect was found, so there is nothing to route to another agent. I edited only files under `tests/**` and committed nothing.

**Task 1.** The guard test in `IdentityAdversarialSuite` now expects the six guards. It also checks that `conflict-pair` resolves to conflict and `rejected-pair` to unresolved. The assertions after line 551 were reached and pass unchanged: the M1 change did not alter behaviour there.

**Task 2 (m6).** I replaced the old `er.eval` text search with an independent detector in `LeakageDetector.scala`. It works at three levels:
- **Source code:** it strips comments, separates string literals (including code inside interpolated strings), and flags any identifier `eval`, any top-level name declared in the eval package, and any string that names the eval package or a gold-label path.
- **Class files:** it parses each class file's constant pool and flags references to `org/scholarchain/er/eval/`.
- **TASTy files:** it flags the name-table entry `eval`.

Self-checks:
- **Planted leaks:** 10 forms, kept as `.txt` files under `tests/contracts/src/test/resources/er-leakage/planted/`. All are caught, including the reviewer's `import eval.Harness`, which the old search missed.
- **Clean control:** the clean file is not flagged.
- **Real eval classes:** they serve as positive controls for the class-file and TASTy checks.
- **Synthetic class file:** checks the constant-pool parser.

Two bugs in my own detector surfaced while running and are fixed, with regression controls: "er.eval" was being read as the keyword `val`, and nested quotes inside `${...}` broke brace counting. No production source or compiled class is flagged.

**Task 3 (m8).** New `CannotLinkAdversarialSuite` (12 tests). The reviewer's chain is "Jane Smith" (X), "J. Smith" (X, Y) and "John Smith" (Y), with Jane–John rejected by `score.reject`.
- **Merge order:** whether the two links are equal, Jane–J. is stronger, or J.–John is stronger, Jane and John never share a ResearcherId. The refused link is UNRESOLVED and keeps its probabilistic path and its own confidence. Its rules are `[cluster.guard.rejected-pair, score.accept]` and its reason is `merge-refused:rejected-pair`. Both report refusal counters show 1, `cannotLinkDecisions` and `cannotLinkClusterPairs` are 1, and `rejectedOrConflictPairsInsideOneCluster` is empty.
- **Input order:** reversing works and candidates gives the same run ID, the same decisions and assignments, and byte-identical `clusters.jsonl`.
- **No split of prior clusters:** a rejection that touches one mention of a two-mention "Jane Smith" cluster keeps the whole cluster apart from John without splitting it. An internal same-work conflict is not a cannot-link: the cluster is not split, the merge proceeds, and the report shows `{"conflict":1}`.
- **Conflict chain:** Jane and John on one work never merge through J.; the same-work guard fires first, as documented.
- **Direct `Clustering.run` tests:** conflict-pair refusal, conflict winning over rejected, a control where the chain merges without cannot-links, and a self cannot-link being ignored. On a path A–B–C–D–E with A~E cannot-linked, all 24 merge orders for both kinds refuse exactly the edge that would close the path.
- Every run is checked against counts I recompute independently from the written decisions.

**Task 4.** New `ErRepairRegressionSuite` (11 tests):
- **M2:** with unequal weights, the weighted precision has `wilson95` null and uses the bootstrap method. `labeledUnits` = 20, Kish effective n is checked by hand, and the interval contains the estimate. The weighted interval is not narrower than the unweighted Wilson interval and is at least 3 times a Wilson interval computed with n = summed weights (1010). Results are deterministic per seed. Weighted n counts stratum memberships while unweighted n counts rows.
- **m1:** every candidateId cell matches `match-candidate:v1:<32hex>`, values are unique, and prior references are stable across output roots. No real candidate or decision carries a prior reference. The strict gold reader and the harness join prior rows (3 units in the prior stratum), and blanking a prior candidateId is rejected 3 times.
- **m2:** titles start with `=`, `+`, `-`, `@`, tab, CR, `'` and `''`. No CSV cell starts with a trigger character, and each trigger cell is the raw value plus one `'`. The round trip through the strict reader works as written, works when a spreadsheet drops the apostrophe (with the one edge case in finding 4), keeps a hand-typed `'quoted note` unchanged, and still rejects an edited display value.
- **m4:** the adjudicator error file records the exception class, a SHA-256 of the message (null when there is no message) and the input hash. The message text appears in no output file. `adjudicatorErrors` = 2, the errored pairs are UNRESOLVED on the llm path with `llm-output-rejected:adjudicator-error`, and errors are never cached: a rerun with the cache asks again for both pairs.
- **m5:** a same-size change to the work, author_mention or match_candidate file makes reuse refuse with "(content)". An unchanged input is still reused, and a new output root still runs.

### Per-suite results (contract tests, from the `sbt check` log)
| Suite | Tests | Failed |
|---|---|---|
| er.CannotLinkAdversarialSuite (new) | 12 | 0 |
| er.ErRepairRegressionSuite (new) | 11 | 0 |
| er.GoldAndHarnessAdversarialSuite (1 test replaced by 4) | 17 | 0 |
| er.IdentityAdversarialSuite (edited) | 23 | 0 |
| er.LlmAdversarialSuite | 39 | 0 |
| er.DecisionContractAdversarialSuite | 16 | 0 |
| er.ErDeterminismAndQueueSuite | 8 | 0 |
| er.NameKeyAdversarialSuite | 10 | 0 |
| hadoop.ScaleAndProvenanceAdversarialSuite | 20 | 0 |
| hadoop.RecordFieldAdversarialSuite | 15 | 0 |
| hadoop.XmlBoundaryAdversarialSuite | 14 | 0 |
| hadoop.UnicodeAdversarialSuite | 14 | 0 |
| hadoop.JobLevelRerunSuite | 8 | 0 |
| hadoop.FallbackPipelineEndToEndSuite | 7 | 0 |
| hadoop.JobLevelBlockingSuite | 6 | 0 |
| hadoop.JobLevelDblpAdversarialSuite | 6 | 0 |
| hadoop.JobLevelTeamScaleSuite | 4 | 0 |
| **Total** | **230** | **0** |

### Findings (none blocking; nothing to route as a defect)
| # | Finding | Evidence | Owner |
|---|---|---|---|
| 1 (minor, reporting) | The report's `rejectedOrConflictPairsInsideOneCluster` also counts expected conflicts inside a single dblp-prior cluster (same-work duplicates, ORCID disagreements in a dblp profile). On the real run it will be non-zero, so it cannot show on its own that M1 holds (zero across prior clusters). Suggest splitting it into across-prior (must be 0) and within-prior counts. | `ErRun.scala:390-412`; scenario E gives `{"conflict":1}` for a correct run | entity-resolution-engineer |
| 2 (info) | Under er-v1, `cluster.guard.conflict-pair` cannot fire end to end. Every CONFLICT pair rule (same-work, `orcid.disagree`, ORCID or OpenAlex homonym conflicts) implies a cluster property that the same-work, ORCID or distinct-homonym guard catches first. It is reachable only through `Clustering.run`, where I test it. It is defence in depth, not a defect. | `Clustering.scala` guard order; `Decide.scala` CONFLICT rules; scenario F | entity-resolution-engineer (awareness) |
| 3 (minor, statistics) | The weighted `labeledUnits` counts stratum memberships, so a row sampled in several block-family strata counts several times, and the bootstrap resamples those memberships as independent, which narrows the interval. A stratum with only one labeled unit adds no bootstrap variance. | `Harness.scala:211-249`; the "n semantics" test pins 4 units for 3 rows | entity-resolution-engineer |
| 4 (info, m2) | If a spreadsheet drops the leading apostrophe on save, a raw value that itself starts with `'` followed by a trigger (e.g. `''Twice`, `'=x`) cannot be restored. The strict reader refuses that row ("display column … differs"); it never accepts it silently. | the m2 round-trip test in `ErRepairRegressionSuite` | none (documented risk) |

### Requirement-to-test coverage
| Requirement | Tests |
|---|---|
| Decision 18 initials collisions at cluster level (m8) | `CannotLinkAdversarialSuite` (all 12 tests) |
| M1 cannot-links, report counters, order independence, prior clusters never split | `CannotLinkAdversarialSuite` |
| Six guards with inherited path, null confidence, outcomes | `IdentityAdversarialSuite` guard test |
| m6 leakage (source, class files, TASTy, with self-checks) | `GoldAndHarnessAdversarialSuite` (4 tests) |
| M2 weighted intervals and n | `ErRepairRegressionSuite` (4 tests) |
| m1 opaque prior reference and gold join | `ErRepairRegressionSuite` (2 tests) |
| m2 formula injection round trip | `ErRepairRegressionSuite` (2 tests) |
| m4 adjudicator errors | `ErRepairRegressionSuite` (precondition + 1 test) |
| m5 reuse re-verifies input contents | `ErRepairRegressionSuite` (1 test) |

```text
STATUS: COMPLETE
OWNER: test-engineer
WORK ORDER: docs/agent-runs/phase-05/prompt.md (run 1 of 2), repair round after code-reviewer REQUEST_CHANGES; decisions 5, 14, 18, 19, 21
FILES CREATED:
  /Users/brian/Developer/repos/scholarchain/tests/contracts/src/test/scala/org/scholarchain/contracts/er/CannotLinkAdversarialSuite.scala
  /Users/brian/Developer/repos/scholarchain/tests/contracts/src/test/scala/org/scholarchain/contracts/er/ErRepairRegressionSuite.scala
  /Users/brian/Developer/repos/scholarchain/tests/contracts/src/test/scala/org/scholarchain/contracts/er/LeakageDetector.scala
  /Users/brian/Developer/repos/scholarchain/tests/contracts/src/test/resources/er-leakage/planted/01-relative-import.scala.txt … 10-package-clause.scala.txt (10 files)
  /Users/brian/Developer/repos/scholarchain/tests/contracts/src/test/resources/er-leakage/clean/01-comments-and-near-names.scala.txt
FILES MODIFIED:
  /Users/brian/Developer/repos/scholarchain/tests/contracts/src/test/scala/org/scholarchain/contracts/er/IdentityAdversarialSuite.scala (guard set 4 -> 6, outcome checks for the two new guards)
  /Users/brian/Developer/repos/scholarchain/tests/contracts/src/test/scala/org/scholarchain/contracts/er/GoldAndHarnessAdversarialSuite.scala (text-search leakage test replaced by 4 detector tests)
COMMANDS RUN (all in the background, one at a time; outputs observed):
  sbt -batch "contractTests/Test/compile"  -> success
  sbt -batch "contractTests/testOnly org.scholarchain.contracts.er.*"  -> run 1: 136 total, 2 failed (both my own test mistakes, fixed); run 2: 136 total, 2 failed (a bug in my detector, fixed); run 3: Passed: Total 136, Failed 0
  sbt -batch check  -> [success] Total time: 1476 s; Ledger check passed; scalafmt check ok
  Read-only: grep, sed, cat, tail, wc, find, git status --short tests/
TEST RESULTS:
  Module tests: suites=41 executed=1014 passed=1014 failed=0 errors=0 skipped=0
  Contract tests (no AWS): suites=17 executed=230 passed=230 failed=0 errors=0 skipped=0
  .claude/.last-test-run updated by markTestRun (module_tests=1014, contract_tests=230)
VALIDATION RESULTS: sbt check passed (format, compile, module tests, contract tests, markTestRun)
ASSUMPTIONS:
  - Weighted n means stratum memberships, matching strata.json ("a row can hold several memberships"); I pinned that and flagged the statistical risk (finding 3).
  - When a spreadsheet drops the apostrophe, the expected behaviour is to refuse loudly; I pinned that (finding 4).
  - tests/contracts/.../hadoop/FallbackPipelineEndToEndSuite.scala is untracked but was not created or edited by me in this run.
RISKS: findings 1 to 4 above. The source leakage check cannot see references built at runtime by joining strings, but those would still show up in the compiled-class scan as constant strings.
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer re-review of the repair round; the human decides whether findings 1 and 3 need an entity-resolution-engineer change before the final real run.
```

---

## Report 4: test-engineer, rerun after code-reviewer round 2 repairs (FAILED; Finding 1, later repaired in repair-07; sbt check then passed, observed by hadoop-engineer)

STATUS: FAILED
OWNER: test-engineer
WORK ORDER: docs/agent-runs/phase-05/prompt.md (run 1 of 2), decision 18. This was the test-engineer rerun after code-reviewer repair round 2 (M-new-1 in hadoop; m-new-1, m-new-2 and m-new-3 in entity-resolution).

Summary: the old silent-skip test is replaced and passes, and the pair-limit invariants hold under every limit I tried. `sbt check` still fails on one new contract test, which exposes a hadoop determinism defect: J05 writes `oversizeMembers/` in a different line order in different output roots. All other module and contract tests pass.

FILES CREATED:
- /Users/brian/Developer/repos/scholarchain/tests/contracts/src/test/scala/org/scholarchain/contracts/hadoop/PairLimitAdversarialSuite.scala
- /Users/brian/Developer/repos/scholarchain/tests/contracts/src/test/scala/org/scholarchain/contracts/er/ErMembershipAndClusterRegressionSuite.scala

FILES MODIFIED:
- /Users/brian/Developer/repos/scholarchain/tests/contracts/src/test/scala/org/scholarchain/contracts/hadoop/JobLevelBlockingSuite.scala: removed the old test "J06 maxPairsPerBlock below 66 ..." (lines 123-137) and added two tests, described under task 1.
- /Users/brian/Developer/repos/scholarchain/tests/contracts/src/test/scala/org/scholarchain/contracts/hadoop/JobHarness.scala: added `CandidateStage` and `candidateStage(...)`, which run J05 -> J05b -> J05c -> J06 wired the way `Pipeline.run` wires them.

No production, module-test, build or docs/agent-runs files were touched. Nothing was committed.

COMMANDS RUN:
- Read-only shell (ls, cat, grep, wc, tail, find, shasum) and git status.
- `sbt "contractTests/Test/compile"`: success.
- `sbt "contractTests/testOnly ...ErMembershipAndClusterRegressionSuite ...PairLimitAdversarialSuite ...JobLevelBlockingSuite"` (background): 29 run, 21 passed, 8 failed. All 8 failures were my own fixture mistake: years before 2015 fall outside the subset, so only 6 of 21 works were accepted. I fixed it to `year = 2015 + i % 7`.
- `sbt "contractTests/testOnly ...PairLimitAdversarialSuite"` (background): 10 run, 9 passed, 1 failed (the determinism test, the real finding).
- `sbt check` (background, alone): exit=1. "[error] Contract tests (no AWS) failed suite: org.scholarchain.contracts.hadoop.PairLimitAdversarialSuite ... .claude/.last-test-run was not updated." Total time 1936 s, finished 11:18:29.

TEST RESULTS (`sbt check`, observed):
- Module tests, all passed, 0 ignored:
  - core: 414 tests per build, run for both builds (828).
  - entity-resolution: 104.
  - hadoop: 100.
- Contract tests: 19 suites, 253 run, 252 passed, 1 failed, 0 ignored.
- Total observed: 1285 run, 1284 passed, 1 failed, 0 skipped.

Per-suite table (contract tests):

| Suite | Run | Pass | Fail |
|---|---|---|---|
| er.CannotLinkAdversarialSuite | 12 | 12 | 0 |
| er.DecisionContractAdversarialSuite | 16 | 16 | 0 |
| er.ErDeterminismAndQueueSuite | 8 | 8 | 0 |
| er.ErMembershipAndClusterRegressionSuite (new) | 12 | 12 | 0 |
| er.ErRepairRegressionSuite | 11 | 11 | 0 |
| er.GoldAndHarnessAdversarialSuite | 17 | 17 | 0 |
| er.IdentityAdversarialSuite | 23 | 23 | 0 |
| er.LlmAdversarialSuite | 39 | 39 | 0 |
| er.NameKeyAdversarialSuite | 10 | 10 | 0 |
| hadoop.FallbackPipelineEndToEndSuite | 7 | 7 | 0 |
| hadoop.JobLevelBlockingSuite (updated) | 7 | 7 | 0 |
| hadoop.JobLevelDblpAdversarialSuite | 6 | 6 | 0 |
| hadoop.JobLevelRerunSuite | 8 | 8 | 0 |
| hadoop.JobLevelTeamScaleSuite | 4 | 4 | 0 |
| hadoop.PairLimitAdversarialSuite (new) | 10 | 9 | 1 |
| hadoop.RecordFieldAdversarialSuite | 15 | 15 | 0 |
| hadoop.ScaleAndProvenanceAdversarialSuite | 20 | 20 | 0 |
| hadoop.UnicodeAdversarialSuite | 14 | 14 | 0 |
| hadoop.XmlBoundaryAdversarialSuite | 14 | 14 | 0 |

Module suites (all 0 failed):
- core, once per build: AuthorNameKey 14, ConfigLoader 17, ExternalIds 20, PairOrdering 5, SchemaVersions 5, StableIds 27, Validation 77, WireContract 249.
- er: Boundary 5, CannotLinkRun 4, Clustering 13, Decision 10, Eval 11, GuardPath 3, HarnessDesign 5, Llm 12, Normalize 7, Policy 8, PriorSpelling 5, ReviewKeyRepair 4, Run 14, WorkResolution 3.
- hadoop: Candidate 11, DblpChain 7, DblpExtractJob 7, DblpParsing 11, FallbackBlocking 17, LinkAndBlock 6, LocalPipelineRunner 3, Normalizer 6, PairsAndTeams 8, Pipeline 4, RunRecovery 1, Scanner 14, Settings 5.

VALIDATION RESULTS: formatting and compilation passed inside `sbt check`. The gate failed only on the contract test above, so `.claude/.last-test-run` was not refreshed.

## Task coverage

**Task 1: JobLevelBlockingSuite (passes, 7 of 7)**
- New test, maxPairsPerBlock 65 under one configuration, all jobs passing:
  - J05: BLOCKS_EMITTED is 0, OVERSIZE_BLOCKS is 1, PAIR_LIMIT_OVERSIZE_BLOCKS is 1, and OVERSIZE_BLOCK_MEMBERS is 12.
  - The listing line reads: limit 11, maxBlockSize 1000, maxPairsPerBlock 65, pairs 66, reason `max_pairs_per_block`.
  - All 12 mentions are in `oversizeMembers/`.
  - J05b: 12 singleton fallback blocks and no coverage.
  - J05c: 0 covered and 12 uncovered, the same 12 mentions; the summary reads 12/0/12.
  - J06: guard counters are 0, there are no unexpanded members, and the counter identity holds in all four jobs.
  - Control: with limit 66 the block is emitted and expanded into 66 candidates.
- New test, J06 guard (J05 under the default limit, J06 under 65):
  - OVERSIZE_BLOCKS is 1, OVERSIZE_BLOCK_PAIRS_SKIPPED is 66, and CANDIDATE_SHARDS and MATCH_CANDIDATES_EMITTED are both 0.
  - UNEXPANDED_BLOCK_MEMBERS is 12, and the per-family counter for dblp-base-name is 12.
  - `unexpandedMembers/` holds 12 valid UncoveredMember lines: correct mention, work and dblp key, all 12 keys present.
  - The listing reads pairs 66, limit 65.

**Task 2: PairLimitAdversarialSuite (9 of 10 pass)**
- `BlockLimit.maxMembers` matches an independent closed-form BigInt model on 17 edge cases and 2000 seeded random cases, including Int.MaxValue, Long.MaxValue and the exact value of pairs(Int.MaxValue). Reasons and `pairBound` are correct, and non-positive limits are refused.
- At job level the invariants hold under six configurations:
  - pairs 1 (effective cap 2);
  - exact boundary of 6 pairs (the 4-member blocks are emitted, `Five Synthy` is listed with reason pairs, limit 4);
  - member cap Int.MaxValue with pairs 10 (5-member primary and fallback blocks emitted exactly at the boundary);
  - member cap only (3/2): reason is always `max_block_size` and the pair-limit counter is 0;
  - two seeded random configurations.
- The invariants checked in each configuration:
  - No emitted primary or fallback block exceeds the pair limit or its member cap.
  - Listing fields are correct.
  - J05 member accounting (blocks + singletons + oversize = families × mentions) and J05b key accounting hold.
  - covered + uncovered equals the oversize members, per key and in total.
  - The covered set equals the members of emitted fallback blocks with two or more members.
  - J06 guard counters are 0, and candidates equal the sum of pairs.
  - The counter identity holds in every job.
- Changing the pair limit changes run IDs, so an output root is never wrongly reused.
- Fails: byte determinism across output roots (Finding 1).

**Task 3: ErMembershipAndClusterRegressionSuite (12 of 12 pass)**
- m-new-1:
  - Rows (A,B) and (A,C) are each sampled both as a dblp-prior pair and in a candidate stratum. They keep separate per-membership decisions: (A,B) has prior conflict/deterministic and candidate accepted/deterministic; (A,C) has prior conflict/deterministic and candidate conflict/exact_id.
  - Every candidate membership equals its written MatchDecision, and every prior membership equals its identity assignment.
  - `weightedUnits` scores each membership with its own decision: weighted recall is 2/4, where the old behaviour would give 3/4.
  - byStratum, byFamily and byMatchPath are hand-checked.
- m-new-2:
  - `left/rightResearcherId` and `sameFinalCluster` equal the identity assignments for every key row. This includes a conflict pair inside one cluster (true), a distinct homonym (false), and an NFC/NFD merge (true).
  - The queue CSV and JSONL headers are exactly `ReviewQueue.Columns`, and contain no ResearcherId values, no `researcher:`, `sameFinalCluster`, `cluster`, `stratum` or `outcome`. The key does contain them.
  - `KeyRow.readAll` refuses a flipped sameFinalCluster or a missing sameFinalCluster, leftResearcherId, membership outcome or membership matchPath.
  - Cluster metrics on the real key match hand counts: precision 3/4, recall 1, F1 6/7, the 2x3 confusion matrix and the pair-by-cluster table.
  - A synthetic 8-row key matches hand counts: unweighted P/R/F1 are 2/3, the Wilson interval is checked, coverage and unresolvedRate are absent, and one row without cluster info is counted. Weighted values: 20/40, 30, F1 4/7, weighted confusion matrix, Wilson null, and a bootstrap interval containing 0.5.
- m-new-3: `knownRecallGaps` contains `blocking.middle-name-variants` with 1 group and 3 names, both hand-counted. `Report.middleInitialSplit` returns (2,7) on a hand list.
- The review key is byte-identical in a second output root.

ASSUMPTIONS:
- Only J05's `oversizeMembers/` line order breaks byte determinism; the content is the same. J05b, J05c and J06 bytes were identical in the same comparison, because they sort or key their output downstream.
- The dblp-prior sampling stratum is named `outcome=accepted` even when a membership records `conflict`. I treated it as a sampling-design label, not a defect (see Finding 2).

RISKS:
- Finding 1 is order-dependent. It reproduced in both runs (the targeted rerun and `sbt check`), but whether it shows depends on map-output merge order. The existing FallbackPipelineEndToEndSuite determinism test passes, probably because its blocks are small. The real run could produce J05 output that differs between attempts.
- The Hadoop job-level suites are slow: `sbt check` took 32 minutes.
- Background work left behind: 11 idle shell watchers (`tail -f <log> | grep -m1 | cut`) that I started to wait on sbt. Their task IDs are b0yc5tm4z, bj1oonink, b25k1sgcs, bx8y4r4mo, bk95qoij3, blvkkd2jm, b13pi117a, b4tdtiag6, b1a5o36eb, bhm2nz784 and bxihuifj9. They follow a log file that has since been replaced, so they will not exit on their own. They are not sbt or JVM processes and hold no locks; every sbt run has finished. Under decision 21 I have no command I am allowed to use to stop them, so the orchestrator should stop them with TaskStop.

MANUAL ACTION REQUIRED: the orchestrator should stop the 11 watcher tasks listed under RISKS.

BLOCKED-ON OWNER: none. The failure is a production defect, routed below.

BLOCKED-ON EVIDENCE: see Finding 1.

NEXT GATE: hadoop-engineer repairs Finding 1, then test-engineer reruns `sbt check` in a fresh context.

## Failures by owning agent
- hadoop-engineer: 1 failing test, PairLimitAdversarialSuite "determinism across output roots under the pair limit (J05, J05b, J05c, J06 bytes)" (PairLimitAdversarialSuite.scala:460).

## Findings

**Finding 1 (major; owner: hadoop-engineer). J05 `oversizeMembers/` is not byte-deterministic across output roots.**
- Decision 8 requires keeping determinism across output roots.
- Evidence: same run ID `run=197bce6385b884b41ee91e004190a190`, under `tests/contracts/target/job-work/pairlimit14908836269810174287/out-pairs-1-{a,b}/.../j05-blocking/.../oversizeMembers/part-r-00000`:
  - Both files have 13 lines, but they differ (sha1 prefixes 44a9ad4aa820 and 1e02db9f81a2).
  - Their sorted mention IDs are identical (sha b8a39693...).
  - Only the order of lines differs.
  - The test output reads: "hadoop.j05-blocking oversizeMembers/part-r-00000 differs (same lines in another order: true)". This is the only file that differed.
- Cause, from reading the code: `BlockingReducer.reduce` (modules/hadoop/src/main/scala/org/scholarchain/hadoop/blocking/BlockingJob.scala, lines 406-423) writes each oversize member in the order Hadoop delivers the reduce values. That order is not sorted and depends on how map outputs merge. Emitted blocks are safe because they are sorted by `CandidateBlock.normalise`; the oversize stream is not sorted.
- Possible fixes: a secondary sort on mention ID, or sorting before writing. Either must keep memory bounded.

**Finding 2 (observation, no failing test; owner: entity-resolution-engineer, for a decision).**
- `StratumSampler.PriorStratum` is fixed as `path=deterministic;outcome=accepted;family=dblp-prior.numbered-name;band=far`.
- When the prior cluster is conflict-flagged, its memberships record outcome `conflict`, so `byStratum` shows conflict units under a stratum named `outcome=accepted`. In the test corpus, all 3 prior units are conflict.
- The metrics themselves are correct. Only the label is misleading, and the human may want it renamed or documented.

No other findings: M-new-1, m-new-1, m-new-2 and m-new-3 behave as described under every attack above.
