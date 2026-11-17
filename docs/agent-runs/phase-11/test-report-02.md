# Phase 11 step 4 test report (test-engineer, wave-2 / repair-wave closer)

STATUS: COMPLETE
OWNER: test-engineer
WORK ORDER: docs/agent-runs/phase-11/prompt.md (sha256 a0b59faeaeaae620ad87ce8b5dd1272efc1078d273e9a84baa426a9cd47e9ed1, not recomputed), step 4, decision 9 (with Standing rules, decisions 2, 11, 12)
FILES CREATED: none in the repository; this report (/private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/858dada8-c8dd-43c7-b05d-961e323b855c/scratchpad/phase11-reports/test-report-02.md)
FILES MODIFIED (all under tests/contracts/src/test/scala/org/scholarchain/contracts/):
- runtime/LlmOutputAdversarialSuite.scala (.fail removed: T-07, T-01)
- runtime/UnsupportedClaimEndToEndSuite.scala (.fail removed: T-10)
- runtime/LoggingRedactionCrossModuleSuite.scala (.fail removed: S-4, C-02 graph-port Left, C-02 vector-index)
- runtime/NeptuneTimeoutStubSuite.scala (.fail removed: B-01)
- config/ConfigOmissionSuite.scala (.fail removed: T-08, T-09, U-6)
- hadoop/JobLevelRerunSuite.scala (tracked; stale-output test inverted, scaladoc updated)
- hadoop/JobLevelBlockingSuite.scala (tracked; family pinned to dblp-base-name)
- er/CannotLinkAdversarialSuite.scala (tracked; "without cannot-links" test inverted for A-01)
Only a mark removal plus a one-line "Repaired in Phase 11 step 3x; .fail removed in step 4" comment was made in the unmarked tests; no assertion changed.
COMMANDS RUN:
1. `sbt -batch "contractTests/testOnly org.scholarchain.contracts.runtime.* org.scholarchain.contracts.services.* org.scholarchain.contracts.config.* org.scholarchain.contracts.crossrecord.* org.scholarchain.contracts.hadoop.* org.scholarchain.contracts.er.*" > .../scratchpad/step4-targeted.log 2>&1; echo "EXIT=$?" >> .../scratchpad/step4-targeted.log` (background + bounded polling)
2. `sbt -batch check > .../scratchpad/step4-check.log 2>&1; echo "EXIT=$?" >> .../scratchpad/step4-check.log` (background + bounded polling)
Plus read-only grep/ls/sed -n/git status.
TEST RESULTS:
- Targeted: `[info] Passed: Total 398, Failed 0, Errors 0, Passed 398`; `[success] Total time: 653 s (0:10:53.0), completed Nov 16, 2026, 10:32:44 PM`; EXIT=0. 34 suites (the six package wildcards cover every contract suite).
- sbt check: `[info] Module tests: suites=119 executed=2001 passed=2001 failed=0 errors=0 skipped=3`; `[info] Contract tests (no AWS): suites=34 executed=398 passed=398 failed=0 errors=0 skipped=0`; `[info] Updated /Users/brian/Developer/repos/scholarchain/.claude/.last-test-run (module_tests=2001, contract_tests=398)`; `[success] Total time: 939 s (0:15:39.0), completed Nov 16, 2026, 10:49:02 PM`; EXIT=0. No `[error]` line in the log. Format check, ledger check (scalafmt 3.11.5) and compile passed as part of check.
- Compared with start: module 1877 run / 3 skipped -> 2001 run / 3 skipped (skipped unchanged; +124 module tests added by the step-3 owners); contract 336 at start, 398 after 1b -> 398 now, 0 skipped (unchanged count; this step added no tests, it only unmarked/inverted).
- The 8 kept `.fail` tests counted as passed (MUnit expected failures still failing as expected).
VALIDATION RESULTS: `.claude/.last-test-run` refreshed 22:49 by markTestRun. No expected-failure flip in either run (no "expected failure but test passed").
ASSUMPTIONS:
- "The self-cannot-link part still holds" (ER) is read as: a self cannot-link is dropped (Builder size 0) and the run equals the run without cannot-links; I assert equality with the no-cannot-link run instead of the old hard-coded 2 merges / empty refusals.
- In JobLevelBlockingSuite, pinning `scholarchain.blocking.keyFamilies=dblp-base-name` is correct because the suite is single-family by construction (`Family = "dblp-base-name"`, all per-family outputs read from that family); the two-family default itself is covered by hadoop's module tests (not re-verified by me).
RISKS:
- JobLevelBlockingSuite no longer exercises the new two-family default at job level in tests/**; a job-level two-family hot-name test is on the proposed-later list.
- The inverted rerun test covers the length-mismatch refusal path only; a same-length tamper (sha256 path) and a missing `outputs` record are not exercised in tests/** (proposed later; may exist in hadoop module tests).
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: step 5 code-reviewer re-check (decision 10); then human ratification.

## Per-ID status

| ID | Suite | Owner / step | Status |
|---|---|---|---|
| T-01 | runtime.LlmOutputAdversarialSuite | rag 3f | fixed (mark removed, passes) |
| T-07 | runtime.LlmOutputAdversarialSuite | rag 3f (side effect of T-01 root cause) | fixed (mark removed, passes) |
| T-10 | runtime.UnsupportedClaimEndToEndSuite | rag 3f | fixed |
| C-02 (graph-port Left) | runtime.LoggingRedactionCrossModuleSuite | rag 3f + services 3g | fixed |
| C-02 (vector-index) | runtime.LoggingRedactionCrossModuleSuite | rag 3f | fixed |
| S-4 | runtime.LoggingRedactionCrossModuleSuite | services 3g | fixed |
| B-01 | runtime.NeptuneTimeoutStubSuite | storage 3e | fixed |
| T-08 | config.ConfigOmissionSuite | domain 3d | fixed |
| T-09 | config.ConfigOmissionSuite | domain 3d | fixed |
| U-6 | config.ConfigOmissionSuite | domain 3d | fixed |
| T-02 | runtime.LlmOutputAdversarialSuite:183 | rag | not repaired (mark kept) |
| T-03 | services.ApiAdversarialSuite:84 | services | not repaired (mark kept) |
| T-04 | services.ApiAdversarialSuite:135 | services | not repaired (mark kept) |
| T-05 | services.ApiAdversarialSuite:151 | services | not repaired (mark kept) |
| T-06 (Content-Length) | services.ApiAdversarialSuite:103 | services | not repaired (mark kept) |
| T-06 (chunked) | services.ApiAdversarialSuite:174 | services | not repaired (mark kept) |
| Phase 5 F8 (repair-21) | er.EvalReportRound3AdversarialSuite:305 | entity-resolution | not repaired (mark kept) |
| Phase 5 F9 (repair-29) | er.EvalReportRound3AdversarialSuite:998 | entity-resolution | not repaired (mark kept) |
| Phase 4 finding 2 / advisory 13 | hadoop.JobLevelRerunSuite | hadoop 3a | fixed; test inverted, passes |
| A-01 | er.CannotLinkAdversarialSuite | ER 3b | fixed; first block inverted, passes |
| Phase 5 m5 (default families) | hadoop.JobLevelBlockingSuite | hadoop 3a | decided default; suite pinned to one family, 7/7 pass |

Still open (re-marked): none. `.fail` marks left (8): T-02, T-03, T-04, T-05, T-06 x2, F8, F9.

## Edited tracked tests and why
- hadoop/JobLevelRerunSuite.scala: test "documented gap: same code.version string reuses a completed output without validation" renamed to "same code.version string: a completed output whose files changed is refused, not reused". It now intercepts `JobFailed` from the rerun and asserts the message contains `hadoop.j01-dblp-extract`, `refusing to reuse` and the tampered part file name, and that the stale file is left as is (nothing rewritten). Reason: Phase 4 finding 2 repaired in 3a (decision 9.1). Fails if reuse is silently accepted again (no exception).
- hadoop/JobLevelBlockingSuite.scala: new private helper `pinned(overrides*)` = `conf(work, (BlockingFamilies -> "dblp-base-name") +: overrides)`; all four configurations use it. Reason: decided default changed to two families (decision 8 / 9.5); assertions unchanged.
- er/CannotLinkAdversarialSuite.scala: "Clustering: without cannot-links the chain merges" renamed to "Clustering: without cannot-links the given-name guard still separates Jane and John"; asserts mergesApplied 1, Jane and J. together, John apart, refused == {key(J., John) -> merge-refused:given-name-conflict}; the self-cannot-link part asserts Builder size 0 and the same merges/refusals as the run without cannot-links. Added constant `RefusedGivenName`. Reason: A-01 repaired in 3b.

## Requirement-to-test coverage (this step)
- Decision 9.1 marks removed for every reported flip: 10 tests, all pass.
- Decision 9.1 rerun inversion: JobLevelRerunSuite (8/8 pass).
- Decision 9.2 targeted invocation: all 34 contract suites, 398/398.
- Decision 9.4 kept marks: 8 listed above.
- Decision 9.5 decided-behaviour updates: JobLevelBlockingSuite, CannotLinkAdversarialSuite.
- Decision 9.6 full sbt check: EXIT=0.

## Failures by owning agent
none.

## Proposed later
- Job-level two-family hot-name test (J05/J06 under the default `dblp-base-name,initials-surname`).
- Rerun refusal for a same-length tampered output (sha256 path) and for a `_job.json` without `outputs`.

## INTERFACE HANDOFF
- Files changed: the 8 test files above (tests/** only).
- Public types/signatures: none (only private suite members: `JobLevelBlockingSuite.pinned`, `CannotLinkAdversarialSuite.RefusedGivenName`).
- Fixtures: none new.
- Thread-safety: n/a (test code; Hadoop suites run local-mode jobs sequentially within a suite).
- Limits: see RISKS.

## TEST EVIDENCE
Targeted
- command: sbt -batch "contractTests/testOnly org.scholarchain.contracts.runtime.* org.scholarchain.contracts.services.* org.scholarchain.contracts.config.* org.scholarchain.contracts.crossrecord.* org.scholarchain.contracts.hadoop.* org.scholarchain.contracts.er.*"
- finished: 2026-11-16 22:32:44 local
- per suite (edited/unmarked, from tests/contracts/target/test-reports): runtime.LlmOutputAdversarialSuite run=8 passed=8 failed=0 skipped=0; runtime.UnsupportedClaimEndToEndSuite run=7 passed=7 failed=0 skipped=0; runtime.LoggingRedactionCrossModuleSuite run=5 passed=5 failed=0 skipped=0; runtime.NeptuneTimeoutStubSuite run=4 passed=4 failed=0 skipped=0; config.ConfigOmissionSuite run=6 passed=6 failed=0 skipped=0; hadoop.JobLevelRerunSuite run=8 passed=8 failed=0 skipped=0; hadoop.JobLevelBlockingSuite run=7 passed=7 failed=0 skipped=0; er.CannotLinkAdversarialSuite run=12 passed=12 failed=0 skipped=0; er.EvalReportRound3AdversarialSuite run=37 passed=37 failed=0 skipped=0; services.ApiAdversarialSuite run=8 passed=8 failed=0 skipped=0; all 34 suites run>0, total 398.
- result line: [info] Passed: Total 398, Failed 0, Errors 0, Passed 398
- expected-failure flips: none
- source changed after this run: no
Full check
- command: sbt -batch check
- finished: 2026-11-16 22:49:02 local
- per project: module suites=119 executed=2001 passed=2001 failed=0 errors=0 skipped=3; contract suites=34 executed=398 passed=398 failed=0 errors=0 skipped=0
- result line: [success] Total time: 939 s (0:15:39.0), completed Nov 16, 2026, 10:49:02 PM (EXIT=0)
- expected-failure flips: none
- source changed after this run: no
- logs: /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/858dada8-c8dd-43c7-b05d-961e323b855c/scratchpad/step4-targeted.log, /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/858dada8-c8dd-43c7-b05d-961e323b855c/scratchpad/step4-check.log
