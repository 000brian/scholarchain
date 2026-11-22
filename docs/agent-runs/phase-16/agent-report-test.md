# Phase 16 step 6: test-engineer report

STATUS: COMPLETE
OWNER: test-engineer
WORK ORDER: docs/agent-runs/phase-16/prompt.md (sha256 c75cc5f895faed13880a69ec285231c5498fd50924c7b875fe7e02cce1707dc8, re-checked with shasum), decision 10 (tier 1: remove the F8 and F9 expected-failure marks) and decision 2 (status lines for N-3, N-4, D-09, R2-01, R2-02, R3-02, R3-03, R3-07, test-plan items 3 rest, 11, 12 rest, 13, 15, 16, IdentityCrossRecordSuite, T-02 to T-06; plus the `Policy.erV1` contract uses). HEAD 9da45ab616a764e449f74f25feca85ed77d6dfe4 plus uncommitted session 2 work. Wave closer: no (no full `sbt check` run).
FILES CREATED: none in the repository (this report only, in the scratchpad)
FILES MODIFIED:
- /Users/brian/Developer/repos/scholarchain/tests/contracts/src/test/scala/org/scholarchain/contracts/er/EvalReportRound3AdversarialSuite.scala (sha256 after edit 867b738004b6de2ddace07cf1c5d29a631c98e29cea2443ec25a89756b96a5f9)
COMMANDS RUN:
- `shasum -a 256 docs/agent-runs/phase-16/prompt.md` -> c75cc5f8...e1707dc8 (matches)
- `sbt -batch "contractTests/testOnly org.scholarchain.contracts.er.*"` (log in scratchpad step6-er.log) -> exit=0
- read-only inspection: grep, ls, git log, git status, shasum, head, cut
- Command-list deviations (all read-only, no prompt raised, recorded for honesty): every Bash call was prefixed with `cd /Users/brian/Developer/repos/scholarchain &&`; one `cat` of the background task's exit-code file; one `sed -n` and one `uniq -c` used as pipe filters on grep output; one shell `for` loop over `git log`. None wrote a file.
TEST RESULTS:
- `contractTests/testOnly org.scholarchain.contracts.er.*`: `[info] Passed: Total 231, Failed 0, Errors 0, Passed 231`; `[success] Total time: 6 s, completed Nov 21, 2026, 9:06:38 PM`; 1 Scala source recompiled. Run 231, passed 231, failed 0, skipped 0, across 12 ER suite reports (none with failures or errors above 0).
- Before this step (step 2's run, same command): Total 231, Failed 2 (the `.fail` tests F8, F9), Passed 229.
- TEST-org.scholarchain.contracts.er.EvalReportRound3AdversarialSuite.xml: tests="37" errors="0" failures="0" skipped="0" timestamp="2026-11-21T21:06:37"; it contains both renamed tests, "FINDING repair-21: no labeled membership agrees with its stratum: not design-determined" (F8) and "FINDING repair-29: a fractional outcome count is not valid, so an absent key is not 0" (F9), with no failure element.
VALIDATION RESULTS: Done-when met: the ER contract command passed with 0 failures, and I saw the output.
ASSUMPTIONS:
- "Remove the mark" means removing `.fail`, the title prefix "ACCEPTED RISK Mx (Fy), expected to fail until repaired: ", and rewording the comment above each test to give the repair history. The assertions are byte-for-byte unchanged.
- Status lines check the working tree (HEAD plus uncommitted session 2 work). "Still present" cites the current file:line.
RISKS:
- F9 now passes, but only the fractional-count part of Phase 5 M2 is fixed (step 2's report). The cache-hit mismatch part of M2 has no contract test and stays open. The comment on the test says so.
- I ran only the `contracts.er.*` suites, not every contract suite. Step 7's full `sbt check` covers the rest.
- 13 uses of the deprecated `Policy.erV1` forwarder remain in tests/contracts. They compile with deprecation warnings, and they block step 2's planned removal of the forwarder in Phase 17.
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: step 7 (service-engineer, wave closer) full `sbt check`; code-reviewer review of this step's diff.

## Decision 10: F8 and F9

| Mark | Location (after edit) | Result after removing the mark |
|---|---|---|
| F8 (Phase 5 M4) | EvalReportRound3AdversarialSuite.scala:298-324 | passes; mark removed |
| F9 (Phase 5 M2, fractional-count part) | EvalReportRound3AdversarialSuite.scala:990-1012 | passes; mark removed |

Neither test still fails, so I kept no mark.

## Decision 2 status lines (test-engineer)

- N-3 (no test of a reclassified, year-shaped number attributed to the wrong researcher): still present (modules/rag/src/test/scala/org/scholarchain/rag/runtime/ClaimVerificationSuite.scala:373-412; the R3-01 fixtures hold Alice 2034 and Bob 1999.5, but no test claims Bob has 2034 while citing Alice's item; no contract test either). File owner: rag-engineer.
- N-4 (label "er-v1-synthetic-override" now overrides er-v2): still present (tests/contracts/src/test/scala/org/scholarchain/contracts/er/LlmAdversarialSuite.scala:388).
- D-09 (`.nonEmpty` does not pin which CandidateBlock rule fired): still present (tests/contracts/src/test/scala/org/scholarchain/contracts/hadoop/ScaleAndProvenanceAdversarialSuite.scala:272-273; moved from :242-243 by the Phase 14 commit f159535, assertions unchanged).
- R2-01 (WindowProvenanceCrossRecordSuite filter-only tests, no non-vacuity check, no runId == Spark-run check): still present (tests/contracts/src/test/scala/org/scholarchain/contracts/crossrecord/WindowProvenanceCrossRecordSuite.scala:38-48, :50-63, :79-89; file unchanged since 29661e9).
- R2-02 (ConfigOmissionSuite accepts `Right(_)` for every removed key; T-08/T-09 catch only a verbatim sentinel echo): still present (tests/contracts/src/test/scala/org/scholarchain/contracts/config/ConfigOmissionSuite.scala:57-75 (`Right(_)` at :68 and :82), :110-126).
- R3-02 (test named "conflicting, not verified" accepts verified): still present (tests/contracts/src/test/scala/org/scholarchain/contracts/runtime/UnsupportedClaimEndToEndSuite.scala:58-79, `Set("verified", "conflicting")` at :77).
- R3-03 (C-02 tests carry amazonaws.com, ARN and account, so services' ResponseGuard hides any rag regression): still present (tests/contracts/src/test/scala/org/scholarchain/contracts/runtime/LoggingRedactionCrossModuleSuite.scala:16-21, :73-110).
- R3-07 (`.fail` tests pass on any throw; T-06 chunked accepts status -1): still present (tests/contracts/src/test/scala/org/scholarchain/contracts/services/ApiAdversarialSuite.scala:84-190, `status == -1` at :186).
- Test-plan item 3 rest (injection in the second chunk of a chunked profile summary): still present (not written; tests/contracts/src/test/scala/org/scholarchain/contracts/runtime/PromptInjectionCrossModuleSuite.scala:24-80 covers only the question, data-block, title/profile and fabricated-ID cases).
- Test-plan item 11 (runtime/VectorKeyAndModelVersionSuite): still present (not written; no such file under tests/contracts/src/test/scala/org/scholarchain/contracts/runtime/).
- Test-plan item 12 rest (researcher below profileMinWorks; no OpenAlex abstract reaches a rendered document): still present (not written; tests/contracts/src/test/scala/org/scholarchain/contracts/runtime/MissingMetadataSuite.scala:13-44 has only the ambiguous-name, institution and topic tests).
- Test-plan item 13 / IdentityCrossRecordSuite: still present (not written; tests/contracts/src/test/scala/org/scholarchain/contracts/crossrecord/ holds only WindowProvenanceCrossRecordSuite.scala).
- Test-plan item 15 (crossrecord/FrozenWireConsumersSuite, optional): still present (not written).
- Test-plan item 16 (runtime/EmptyGraphRuntimeSuite): still present (not written).
- T-02 `.fail`: still present (tests/contracts/src/test/scala/org/scholarchain/contracts/runtime/LlmOutputAdversarialSuite.scala:183). Defect owner: rag-engineer.
- T-03 `.fail`: still present (tests/contracts/src/test/scala/org/scholarchain/contracts/services/ApiAdversarialSuite.scala:84). Defect owner: service-engineer.
- T-04 `.fail`: still present (ApiAdversarialSuite.scala:135). service-engineer.
- T-05 `.fail`: still present (ApiAdversarialSuite.scala:151). service-engineer.
- T-06 `.fail` x2: still present (ApiAdversarialSuite.scala:103 and :174). service-engineer.
- Deprecated `Policy.erV1` forwarder used in tests/contracts: still present, 13 uses (CannotLinkAdversarialSuite.scala:152; LlmAdversarialSuite.scala:76, :338, :388 x2; ErSynth.scala:230; ErRepairRegressionSuite.scala:469; GoldAndHarnessAdversarialSuite.scala:269; IdentityAdversarialSuite.scala:239, :649, :650, :652; all under tests/contracts/src/test/scala/org/scholarchain/contracts/er/). Not migrated in this phase (scope).

## Requirement-to-test coverage

| Requirement | Test | Result |
|---|---|---|
| M4 fixed: a rate whose labeled memberships never agree with their stratum is not design-determined (F8) | EvalReportRound3AdversarialSuite "FINDING repair-21: no labeled membership agrees with its stratum: not design-determined" | pass (unmarked) |
| M2 fractional part fixed: a fractional outcome count does not make an absent key 0 (F9) | EvalReportRound3AdversarialSuite "FINDING repair-29: a fractional outcome count is not valid, so an absent key is not 0" | pass (unmarked) |
| No regression in ER contract suites after step 2 | org.scholarchain.contracts.er.* (12 suites) | 231/231 pass |

## Failures by owning agent

None in the suites run.

## Proposed later

- Migrate the 13 `Policy.erV1` uses in tests/contracts to `Policy.current`, then entity-resolution-engineer removes the forwarder (Phase 17). It is out of scope this phase.
- Rename the N-4 label (for example "er-v2-synthetic-override"). It can go with the migration above in the same file.
- Strengthen R2-01, R2-02, R3-02, R3-03, R3-07 and D-09, and write test-plan items 3 rest, 11, 12 rest, 13, 15, 16 (Phase 11 deferrals, not built in Phase 16 per decision 2).
- A contract test for M2's cache-hit mismatch part (outcome counts include cache hits; pairs sent exclude them) once entity-resolution-engineer fixes it.

## INTERFACE HANDOFF

- Files changed: tests/contracts/src/test/scala/org/scholarchain/contracts/er/EvalReportRound3AdversarialSuite.scala (two test titles lose the "ACCEPTED RISK … expected to fail until repaired: " prefix and `.fail`; comments reworded; assertions unchanged).
- Public signatures: none (test code only).
- Fixtures: none added or changed.
- Thread-safety: not applicable.
- Limits: the test names changed, so anything that matches the old "ACCEPTED RISK M4 (F8)" or "ACCEPTED RISK M2 (F9)" titles (for example evidence scripts or reports) will no longer find them. The repository now has six intentional `.fail` marks: T-02, T-03, T-04, T-05, T-06 x2.

## VALIDATION EVIDENCE

- `sbt -batch "contractTests/testOnly org.scholarchain.contracts.er.*"` -> exit=0; `[info] Passed: Total 231, Failed 0, Errors 0, Passed 231`; test report EvalReportRound3AdversarialSuite tests=37 failures=0 errors=0, timestamp 2026-11-21T21:06:37, later than the edited source's mtime 21:06:18.
- `git status --short tests/` -> only ` M tests/contracts/src/test/scala/org/scholarchain/contracts/er/EvalReportRound3AdversarialSuite.scala`.
- files changed after these runs: none
