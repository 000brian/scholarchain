# repair-E00-rag (Phase 16 session 3, finding P16-L-01 carrying P16-K-01)

STATUS: COMPLETE
OWNER: rag-engineer
WORK ORDER: docs/agent-runs/phase-16/prompt.md (sha256 c75cc5f895faed13880a69ec285231c5498fd50924c7b875fe7e02cce1707dc8, checked), HEAD d37389bf764c6b6fe2c579167a35919973409cb6, repair-E00-rag, finding P16-L-01 (carries P16-K-01, review-report-02.md:27). Human approved this repair.
FILES CREATED: none
FILES MODIFIED:
- /Users/brian/Developer/repos/scholarchain/modules/rag/src/main/scala/org/scholarchain/rag/runtime/ClaimVerification.scala
- /Users/brian/Developer/repos/scholarchain/modules/rag/src/test/scala/org/scholarchain/rag/runtime/PathAbsenceSuite.scala
COMMANDS RUN:
- shasum -a 256 on the work order; git rev-parse HEAD; git status/diff (read-only)
- sbt -batch "rag/testOnly org.scholarchain.rag.runtime.PathAbsenceSuite" (fixed code): Passed 25/25
- temporary revert run (same command, HEAD ClaimVerification.scala copied back from the scratchpad plus only a placeholder AbsenceNotSearched constant): Failed 6 of 25
- sbt -batch "rag/testOnly PathAbsenceSuite ClaimVerificationSuite GroundingSuite StagesSuite QueryWorkflowSuite RuntimeRepairsSuite RuntimeServiceSuite BenchmarkRunnerSuite" (FQCNs): Passed 127/127
- sbt check (wave closer): success, exit 0, 948 s
- sbt -batch packageCheck: "packageCheck: ok", success
TEST RESULTS:
- PathAbsenceSuite 25/25 (19 existing + 6 new). Targeted set: 127 passed, 0 failed (ClaimVerification 15, Grounding 12, PathAbsence 25, Stages 14, RuntimeRepairs 14, QueryWorkflow 25, BenchmarkRunner 8, RuntimeService 14).
- sbt check: every suite run reported "finished: 0 failed" (182 suite lines, 0 others); markTestRun updated .claude/.last-test-run (module_tests=2291, contract_tests=399), 00:11:02.
- Temporary revert: these 6 failed: the K-01 window test (:223, Verified), the found-path conflict test (:233, Verified instead of Conflicting), the in-clause "2 hops" test (:243, Verified), the other-pair test (:253, Verified), the no-window ambiguity test (:262, Verified), and the reasons-list test (placeholder text). The positive test (matching window/pair/depth still verified) passes on both versions, as expected for a regression guard. Revert removed: grep TEMPREVERT returned 0.
VALIDATION RESULTS: all existing L2 and P16-A-09 tests pass unchanged (the P16-A-09 "connected by 2 hops in 2015-2025, not within 6 hops in 2021-2023" case is still Verified, and the 3-hop version is still Conflicting). Groundedness evaluation (group 4) runs on frozen data and was not rerun; this work order does not name it.
ASSUMPTIONS:
- "A found path whose window overlaps the clause window is a conflict" is narrowed to: the found path's window lies inside the clause window, and its hop count is within the stated depth (or no depth is stated). A literal overlap rule would mark the existing, kept P16-A-09 test (found path 2015-2025, absence 2021-2023) Conflicting. The narrower rule also matches the temporal meaning: a path in a sub-window exists in the wider window, but not the other way round.
- No stated range (conservative choice): every cited path item (found or none) for the named pair must share one window, and that window becomes the clause's window. If they do not share one, the result is AbsenceNotSearched. Any cited found path for the pair within the depth is a conflict.
- Clause window: the year ranges stated in the clause, otherwise the ranges stated anywhere in the claim. Every one of them must be supported.
- Pair: the endpoints of a cited path item (nameA/nameB, or the first and last pathNames, homonym numbers removed) whose two names appear in the clause. When the clause names fewer than two endpoints, the whole claim is used.
- Inside an absence clause, only "within/up to/at most N hops" or "depth N" is a searched depth. Any other hop count goes through the positive hop check.
RISKS:
- Adds one reason code, so Reasons.all grows by one entry. The work order says both "no change to Reasons.all" and "no new reason code unless needed". I judged it needed: no existing reason describes a pair or window mismatch truthfully (AbsenceBeyondDepth names depth; AbsenceBoundedSearch says the search stopped early). GroundingSuite requires every reason to be in Reasons.all, so the entry had to be added there. The human may prefer to reuse an existing reason instead.
- Known grounding gaps, all fail closed (removed, never wrongly kept): "No other path is shorter than the 2 hops ..." citing the true 2-hop found path is Conflicting, because it counts as an absence clause plus a found path for the pair; absence phrased with a hop count but no bound word ("no path of 6 hops or fewer") is read as a positive hop count; a fronted depth clause behaves as before (repair-01).
- The local val `windows` inside the absence loop shadows the method parameter `windows` (used later at the allowedRanges line, outside the loop, so the right value is used). This is cosmetic. I left it so I would not change the source after the passing sbt check.
MANUAL ACTION REQUIRED: the human decides whether the added reason code AbsenceNotSearched is acceptable, given the "no change to Reasons.all" wording.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer re-check of P16-K-01 / P16-L-01; human gate.

Fix status: fixed in code, tested offline, not validated live.

## Prompt and model versions
Unchanged. No prompts/** change, no model ID change, and no threshold, limit or default changed.

## INTERFACE HANDOFF
- Interface change: yes (additive only). New public `ClaimVerification.Reasons.AbsenceNotSearched = "an absence claim states a researcher pair or window that no cited path search covered"` (fixed text, no digits), appended to `Reasons.all` before `NothingCheckable`. It is produced as an unbacked reason, so the claim status is Unsupported and it is never kept.
- Signatures unchanged: `verify`, `verifyClaim`, `VerifiedClaim`, `absenceSpans`, `statesAbsence`.
- Behavior changes for consumers:
  - an absence clause needs a complete path=none item for the named pair whose window equals the stated window, else AbsenceNotSearched (or AbsenceBoundedSearch when the only search for that pair and window was bounded);
  - a cited found path for the pair inside the stated window, within the stated depth, makes the claim Conflicting (HopsConflict);
  - inside an absence clause, a hop count with no bound word is now a positive hop count.
- New private regex `BoundHopRe`. Thread-safety: pure function, no shared mutable state. Limits: none added.
- Fixtures: PathAbsenceSuite gained `noneW1K` (complete path=none, Alice/Bob, W1 2021-2023, depth 6) and `metricFull` (DistinctCoauthors metric, 2015-2025).

## VALIDATION EVIDENCE
- 23:54:09 sbt -batch "rag/testOnly org.scholarchain.rag.runtime.PathAbsenceSuite": Passed: Total 25, Failed 0
- 23:54:43 temporary revert, same command: Failed: Total 25, Failed 6, Passed 19 (the 5 new negative K-01 tests and the reasons-list test); revert removed
- targeted 8 suites: Passed: Total 127, Failed 0, Errors 0
- 00:11:02 sbt check: [success] Total time 948 s, exit 0; .last-test-run updated (module_tests=2291, contract_tests=399)
- 00:11:26 sbt -batch packageCheck: packageCheck: ok, [success]
files changed after these runs: none (ClaimVerification.scala last written 23:54 when restored after the revert, before the targeted run and sbt check; PathAbsenceSuite.scala 23:52)
