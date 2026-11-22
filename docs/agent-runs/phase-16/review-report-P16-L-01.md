# Re-check of repair-E00-rag: P16-L-01 (carries P16-K-01)

(Saved verbatim by the orchestrator from code-reviewer's final message.)

The repair fixes every input named in K-01 and keeps the earlier L2 and P16-A-09 behaviour. But I found two new major false-Verified paths. Both are inside the human's two cases ("a cited path already connects the pair") and the pair-mismatch question in this work order. Both existed before this repair. The repair does not reach them.

## Verdict per finding

| ID | Verdict | Evidence |
|---|---|---|
| P16-L-01 / P16-K-01 (rag) | **PARTIAL** | All three K-01 inputs now fail closed, and the earlier cases behave as before (traces below). Two cases are still Verified when they should not be. **(a)** A cited found path contradicts an absence claim only when a `path=none` item is also cited (P16-L-03). **(b)** The pair can be taken from a clause that names a different researcher (P16-L-04). |

### Hand traces (work-order items 1 to 3)

**Item 1: the K-01 inputs** (ClaimVerification.scala:363-454)
- **K-01 input A** ("…not connected within 6 hops in 2015-2025.", citing `found` (2 hops, 2015-2025) and `noneW1K` (complete, depth 6, 2021-2023)):
  - The whole sentence is the absence clause, the depth is 6 (BoundHopRe), and both names are endpoints, so the pair comes from the clause.
  - The stated range is {2015-2025}. No `path=none` item has that window, so :435 adds AbsenceNotSearched.
  - :444-453: `found` has 2 hops (≤6) and its window lies inside 2015-2025, so the result is HopsConflict and the status is **Conflicting**.
  - With `metricFull` cited instead of `found`, the result is AbsenceNotSearched and the status is **Unsupported**.
- **K-01 input B** ("No other path is shorter than the 2 hops between …", citing `noneW1K`):
  - No bound word governs "2 hops", so `depths` is empty. The window is taken from the single cited window {W1}, giving AbsenceNoDepth.
  - The hop check at :462 now sees "2 hops". `paths` is empty and completeDepths is [6], so the result is HopsConflict and the status is **Conflicting**.
  - If `found` is also cited, the result is still Conflicting (the agent discloses this; it fails closed).

**Item 2: earlier cases**
- **L2 tests :65-129 (single `completeNone`, 2015-2025):** the window is taken from the single cited item. The results are unchanged:
  - "within 6 hops" and "within 4 hops": Verified;
  - "within 8 hops": AbsenceBeyondDepth;
  - no depth: AbsenceNoDepth, not Verified;
  - time-limited and resource-limited searches: AbsenceBoundedSearch;
  - "did not complete, but no path…": the clause after "but" names both endpoints, so the result is AbsenceBoundedSearch;
  - "whether a path exists is unknown": no spans, so the result is unchanged.
- **P16-A-09 (review-report-02 :13):**
  - Input 1 and the "never coauthored" variant: no spans, the hop check gives Conflicting.
  - Combined claim "connected by 2 hops in 2015-2025, not within 6 hops in 2021-2023":
    - The clause " not within 6 hops in 2021-2023" names no endpoint, so the pair comes from the whole claim (Alice/Bob).
    - The stated window is {2021-2023}, matched by `noneW1`, and depth 6 ≤ 6, so the depth is checked.
    - `found` (2015-2025) is not inside 2021-2023, so there is no conflict.
    - "2 hops" is outside the depth match, and `paths` contains 2, so it is checked. Both ranges are allowed.
    - Result: **Verified**. With "3 hops", the result is **Conflicting**. Unchanged.

**Item 3: the agent's narrowing and the no-window rule**
- **Narrowing "overlap" to "found path inside the clause window":**
  - The narrowing is temporally sound. A path found in a sub-window also exists in the wider window. A path found in a wider window does not contradict an absence in a narrower one.
  - Found path in a sub-window (absence 2015-2025, `found` in W1, complete `path=none` for Full): the result is HopsConflict. Correct.
- **Wider-window claim citing a narrower complete search:** the result is AbsenceNotSearched. Correct.
- **Two ranges in one clause:** every window must be supported, so the result fails closed.
- **Range stated outside the clause:** `rangesIn(stripped)` is used. This is conservative and fails closed.
- **No stated window, with two windows cited for the pair:** the windows are empty, so the result is AbsenceNotSearched. Correct.
- **Other pair, where the clause names a non-endpoint and no other clause names the second endpoint (test :248):** the result is AbsenceNotSearched. Correct.
- **Residual false-Verified paths:** see P16-L-03, P16-L-04 and P16-L-05.

## New findings

| ID | Severity | Interface | Owner | File:line | Failure input and impact |
|---|---|---|---|---|---|
| P16-L-03 | major | no | rag-engineer | modules/rag/src/main/scala/org/scholarchain/rag/runtime/ClaimVerification.scala:369 (`spans` computed only when `absentItems.nonEmpty`), :444-453 (found-path contradiction only inside `spans.foreach`) | **Input:** "No path connects Alice Smith and Bob Jones." citing only PathAbsenceSuite's `found` (Alice–Carol–Bob, 2 hops, 2015-2025). "Alice Smith and Bob Jones are not connected in 2015-2025." gives the same result.<br>**Trace:** `absentItems` is empty, so `spans` = Nil. That skips the absence check and the found-path contradiction. There is no "N hops" in the text, so the hop check never runs. Both names are in `pathNames`, so checked = 2. The range 2015-2025 is `found`'s window. No conflict and nothing uncovered, so the status is **Verified**.<br>**Impact:** this is exactly the human's case "a cited path already connects the pair". A claim that the cited evidence directly contradicts is kept as verified. Every new contradiction test also cites `noneW1K`, so none covers this. It existed before the repair, but it is inside the repair's stated scope.<br>**Repair direction:** detect absence spans whenever a cited GraphPath item exists (found or none), not only when a `path=none` item is cited. Add the inputs above as tests. |
| P16-L-04 | major | no | rag-engineer | ClaimVerification.scala:413-420 (`endpointNames` counts only path-item endpoints; with fewer than 2 in the clause, `nameText` falls back to the whole claim) | **Input:** "Unlike Bob Jones, Carol King is not connected to Alice Smith within 6 hops in 2021-2023." citing `noneW1K` (Alice/Bob, complete, depth 6, W1) and `found` (whose `pathNames` hold Carol King). A metric item for Carol King works the same way.<br>**Trace:**<br>1. The clause after ", " names one endpoint (Alice). Carol is not an endpoint, so she is not counted.<br>2. `nameText` falls back to the whole claim. That text holds Alice and Bob, so `pairNone` = [noneW1K] and `windows` = {W1}.<br>3. Depth 6 ≤ 6, so the depth is checked. `found` (2015-2025) is not inside W1, so there is no conflict.<br>4. All three names are cited and 2021-2023 is allowed, so the status is **Verified**.<br>**Second input:** "Alice Smith, Bob Jones and Carol King are not connected within 6 hops in 2021-2023." The clause after ", " names Bob only (Carol is not an endpoint), so the pair again falls back to the whole claim (Alice/Bob), and the status is **Verified**. That covers only one of the stated pairs.<br>**Impact:** an absence is shown as verified for a pair that no cited search covered. That is the pair-mismatch class the K-01 repair direction named ("whose pair is the one named").<br>**Repair direction:** use every cited researcher name in the clause, not only path endpoints. Reject the claim when the clause names a researcher outside the matched search's pair. Fall back to the whole claim only when the clause names no researcher. Add both inputs as tests. |
| P16-L-05 | minor | no | rag-engineer | ClaimVerification.scala:391-397, :418-420 (`baseName` strips homonym numbers; `stripped` has already blanked them via HomonymRe at :340) | **Input:** "Alice Smith 0001 and Bob Jones are not connected within 6 hops in 2021-2023." citing a complete `path=none` item for "Alice Smith 0002"/Bob (W1, depth 6) and a W1 metric for "Alice Smith 0001".<br>**Trace:** the pair matches on the base name "alice smith", and the names check passes because 0001 is cited. Result: **Verified**.<br>**Impact:** two homonymous researchers are merged in the pair check, which hides identity uncertainty. It needs both homonyms cited in one bundle, so it is minor.<br>**Repair direction:** match the pair on `researcherA`/`researcherB` IDs of the items whose full names, homonym numbers included, appear in the text. |
| P16-L-06 | minor | no | rag-engineer | ClaimVerification.scala:409-412, :423-427 (only `RangeRe`/`BetweenRe` count as stated windows) | **Input:** "Alice Smith and Bob Jones were not connected within 6 hops from 2015 to 2025." citing `noneW1K` plus any cited edge with firstYear 2015 and lastYear 2025.<br>**Trace:** no range is detected, so the window is taken from the single cited window, W1. 2015 and 2025 pass as single event years. Result: **Verified**, although the search covered only 2021-2023.<br>**Impact:** a wider window phrased as "from X to Y" passes. This is a gap in the general L-2 year-role design (a metric claim "from 2015 to 2025" behaves the same way), and it existed before the repair. It needs coincidental edge years, so it is minor.<br>**Repair direction:** inside an absence clause, treat "from X to Y" and "X through Y" as a stated window. |

## Other checks

- **(4) Reasons.all consumers:**
  - Grep over modules/services/src, tests/contracts/src and prompts/** finds no consumer of claim reason texts or of `Reasons.all`. `abstentionReasons` in services Models.scala:234-310 is the separate abstention-code table.
  - The rag consumers are QueryWorkflow.scala:305, :326, :422 and BenchmarkRunner.scala:513. All use the text freely, with no allowlist.
  - GroundingSuite.scala:251 requires every emitted reason to be in `Reasons.all`, and the new entry satisfies it.
  - The new text has no digits (PathAbsenceSuite:278-289).
  - The change is additive and breaks nothing.
  - Note: the work order says "no change to Reasons.all"; the agent flags the human decision in MANUAL ACTION REQUIRED. AbsenceNotSearched is unbacked (status Unsupported) and never kept.
- **(5) Tests fail without the fix:**
  - I accept the agent's revert evidence: 6 of 25 failed, namely the 5 negative K-01 tests and the reasons-list test, and the positive guard passed on both versions.
  - My traces agree: on the old code, tests :218, :228, :238, :248 and :257 would each give Verified.
  - No test covers P16-L-03 or P16-L-04.
- **(6) Scope:**
  - The diff touches only these two files. There is no threshold, limit, prompt, model ID or default change. BoundHopRe is a new private regex.
  - I found no secrets, account IDs or bucket names; the fixtures use the names Alice, Bob and Carol.
  - The `windows` shadowing (:424) is cosmetic. Line :487 is outside the loop and uses the method parameter, which is correct.
- **Observation, not a finding:** an absence claim that states no window is verified against the cited search's single window (the L2 test :65 design, unchanged). Whether that meets "every metric states its time window" is a human policy question.
- **Gate evidence:** accepted from the orchestrator: full sbt check 00:11:02, module_tests=2291, contract_tests=399, packageCheck ok, marker 2026-11-22T06:11:02Z. I cannot check file times.

## Files read

**In full:**
- docs/agent-runs/phase-16/review-report-02.md
- /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/15acd480-beb2-4248-b3ae-3f168fefe386/scratchpad/phase16-reports/repair-E00-rag.md
- /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/15acd480-beb2-4248-b3ae-3f168fefe386/scratchpad/phase16-reports/diff-repair-E00-rag.txt
- /Users/brian/Developer/repos/scholarchain/modules/rag/src/main/scala/org/scholarchain/rag/runtime/ClaimVerification.scala
- /Users/brian/Developer/repos/scholarchain/modules/rag/src/test/scala/org/scholarchain/rag/runtime/PathAbsenceSuite.scala

**In part:**
- modules/rag/src/main/scala/org/scholarchain/rag/runtime/EvidenceBuilder.scala: lines 1-110 (EvidenceValues) and 240-349 (path item snippets: `nameA`/`nameB`, `pathNames`, `found`, `complete`, `maxDepth`, `hops`)
- NumberFormats.scala:27 (HomonymRe)
- Grep results over the repository for `Reasons.all`, the reason texts and `.reasons` consumers

**Not read:**
- RuntimeFixtures.scala (FullW = 2015-2025 and W1W = 2021-2023 are taken from the suite's comments)
- RulePlanner.nameRuns (the fact that "No" is not a name is taken from the positive test :265 passing)
- GroundingSuite beyond line 251

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: docs/agent-runs/phase-16/prompt.md (sha256 c75cc5f895faed13880a69ec285231c5498fd50924c7b875fe7e02cce1707dc8), base HEAD d37389bf764c6b6fe2c579167a35919973409cb6, session 3 re-check of repair-E00-rag, finding P16-L-01 (carries P16-K-01)
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools)
TEST RESULTS: none run; accepted from the agent and orchestrator: PathAbsenceSuite 25/25, targeted 127/127, revert run 6 of 25 failing, full sbt check 00:11:02 (module_tests=2291, contract_tests=399), packageCheck ok
VALIDATION RESULTS: P16-L-01/K-01 PARTIAL (all K-01 inputs fixed, earlier L2 and P16-A-09 behaviour preserved; residual false-Verified paths P16-L-03 major and P16-L-04 major); new minors P16-L-05 and P16-L-06; Reasons.all addition breaks no consumer
ASSUMPTIONS: traces done by hand, not by running code; fixture windows taken from suite comments; "No" at sentence start is not a name run (the positive test passes); gate evidence taken from the orchestrator
RISKS: until L-03 and L-04 are fixed, a live answer can show an absence claim as verified when a cited found path connects the pair (with no path=none item cited), or when the absence is about a pair no search covered
MANUAL ACTION REQUIRED: the human routes P16-L-03 and P16-L-04 (this repair round was used for L-01; the one-round rule needs a human decision); the human decides whether adding Reasons.AbsenceNotSearched is acceptable against the "no change to Reasons.all" wording
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: orchestrator triage with the human: P16-L-03, P16-L-04 (rag-engineer, major); P16-L-05, P16-L-06 (rag-engineer, minor)
```

| Severity | file:line | Failure input | Impact | Owner |
|---|---|---|---|---|
| major | ClaimVerification.scala:369, :444-453 | "No path connects Alice Smith and Bob Jones." citing only `found` (2-hop path Alice–Carol–Bob) | Verified, although the cited path contradicts it | rag-engineer |
| major | ClaimVerification.scala:413-420 | "Unlike Bob Jones, Carol King is not connected to Alice Smith within 6 hops in 2021-2023." citing `noneW1K` and `found` | Verified for a pair (Carol/Alice) that no cited search covered | rag-engineer |
| minor | ClaimVerification.scala:391-397, :418-420 | "Alice Smith 0001 and Bob Jones are not connected within 6 hops in 2021-2023." citing a search for Alice Smith 0002/Bob and a metric for 0001 | homonyms merged in the pair check; Verified | rag-engineer |
| minor | ClaimVerification.scala:409-412, :423-427 | "…not connected within 6 hops from 2015 to 2025." citing `noneW1K` and an edge with firstYear 2015, lastYear 2025 | a wider window passes as Verified (pre-existing L-2 year-role gap) | rag-engineer |

VERDICT: REQUEST_CHANGES
