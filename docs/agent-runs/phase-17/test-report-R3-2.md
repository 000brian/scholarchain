STATUS: COMPLETE
OWNER: test-engineer
WORK ORDER: Phase 17, session 3, repair round R3, wave closer re-run: re-check of "largestReduceGroup stays within the cap and does not depend on the map-task layout" against the hadoop-engineer repair, then the full `sbt check`.
FILES CREATED: none in the repository. Scratchpad only: /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/73f10a2e-9b01-4d44-898e-9399e20392db/scratchpad/phase17-reports/sbt-check-R3-2.log (full sbt log) and this report.
FILES MODIFIED: none (tests/** unchanged).
COMMANDS RUN:
1. Read-only: cat of repair-hadoop-R3.md, grep, git diff of FallbackJobs.scala, sed -n of BlockingJob.scala (reducer), Read of OversizeBlockAdversarialSuite.scala lines 520-610.
2. `sbt -batch check` (one sbt process, run in the background, log at the scratchpad path above). Last lines: "[success] Total time: 1459 s (0:24:19.0), completed Nov 24, 2026, 11:40:17 PM", then "EXIT=0".
3. Read-only counting of the fresh TEST-*.xml reports (find, grep, awk, ls). Note: my `find . -path '*/target/*test-reports'` also listed three directory names under evidence/archives/; I did not open or read any file there (they were marked STALE and skipped).
4. stat of .claude/.last-test-run, before and after.
TEST RESULTS:
- sbt check exit code: 0.
- Format: scalafmt check passed (no [error] lines). Ledger: "Ledger check passed (scalafmt 3.11.5)." Compile: passed (no [error] lines in the log).
- Module tests (every report in each folder is newer than the run start; failures and errors are 0 in all of them):
  - core (Scala 3): 15 suites, 600 tests, 0 failed, 0 skipped
  - core2_13: 15 suites, 600 tests, 0 failed, 0 skipped
  - entityResolution: 18 suites, 147 tests, 0 failed, 0 skipped
  - hadoop: 17 suites, 124 tests, 0 failed, 0 skipped
  - sparkAnalytics: 6 suites, 73 tests, 0 failed, 0 skipped
  - storage: 32 suites, 323 tests, 0 failed, 2 skipped
  - rag: 33 suites, 362 tests, 0 failed, 2 skipped
  - services: 15 suites, 138 tests, 0 failed, 1 skipped
  - module total: 151 suites, 2367 tests, 0 failed, 0 errors, 5 skipped. These are the RequiresAws/live tests, which are compiled but never run.
- Contract tests (contractTests): 35 suites, 418 tests run, 418 passed, 0 failed, 0 errors, 0 skipped.
  - OversizeBlockAdversarialSuite 18/18 (it includes "largestReduceGroup stays within the cap and does not depend on the map-task layout", which passed). JobLevelBlockingSuite 7/7. JobLevelTeamScaleSuite 5/5.
  - The JobFailed stack traces in the log come from the expected refusal tests: limit 5 vs 4/6, fallback limit 3 vs 2, "{not json}", keyVersion 2. Those tests pass.
- .claude/.last-test-run was refreshed: 2026-11-22 23:40:38 before, 2026-11-24 23:40:16 after (this run).
VALIDATION RESULTS:
Re-check of the largestReduceGroup test against the repair. The test still asserts what I intended, so I did not change it.
- What the test checks, for all four layouts (base, reversed, shuffled files, 8 KiB splits):
  - J05 stat <= Cap and J05b stat <= FallbackCap;
  - the J05 value and the J05b value are each identical across the layouts.
- What the repair does:
  - J05 `BlockingReducer.reduce` no longer offers the `#size` marker groups to the stat. It still offers block groups and re-keyed member groups with their value counts.
  - J05b `FallbackBlockingReducer` offers only `members` (marker values excluded), and only when members > 0.
- So the stat now measures data, not the number of map tasks. That is exactly the property the test attacks: the cap bound and layout independence. The test reads the stat with `stats(...)`, so it would also fail if a run wrote no stat line at all. It passed with 18/18, so every layout writes the stat for both jobs.
- The test is still a real check. Before the repair it failed on the same code paths: the marker group counts went above the cap and changed with the split layout.
ASSUMPTIONS:
- The module totals come from sbt's own TEST-*.xml reports. sbt printed no aggregate "Passed: Total" line in this log, so these reports are the source. All reports in the nine folders are newer than the run start: for each folder, the count of fresh reports equals the count of all reports.
RISKS:
- The deterministic-layout test in OversizeBlockAdversarialSuite still removes the `largestReduceGroup` line before it compares stats/ files byte for byte. The stat is now compared by value in the dedicated test, but its subject text is not compared across layouts. Proposed later (not done, out of scope for this round): drop that exclusion so the stats/ files are compared byte for byte. The hadoop-engineer module test already claims byte-identical stats/ for its fixture.
MANUAL ACTION REQUIRED: none.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer reviews the R3 wave (sbt check passed after the last source change; marker refreshed 2026-11-24 23:40:16).

## Requirement-to-test coverage (this round)

| Requirement | Test | Result |
|---|---|---|
| HADOOP-04 / B1: largestReduceGroup <= effective cap with the pre-pass | OversizeBlockAdversarialSuite "largestReduceGroup stays within the cap and does not depend on the map-task layout" | pass |
| largestReduceGroup independent of map-task layout | same test (4 layouts, 8 KiB splits) | pass |
| Records, listings, counters, other stats layout-independent | OversizeBlockAdversarialSuite "deterministic: reversed input, shuffled files and small splits ..." | pass |
| Pre-pass refusals (cap mismatch, corrupt list, keyVersion) | OversizeBlockAdversarialSuite refusal tests | pass |
| Full regression | sbt check (all module and contract suites) | pass, exit 0 |

## Failures by owning agent

None.

No INTERFACE HANDOFF: no file was changed.
