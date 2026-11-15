Step 0 is accepted. One `sbt check` passed in 11 min 32 s wall time, against the 25-minute limit and the ~33-minute Phase 5 baseline. It ran exactly 1065 module tests and 336 contract tests, with no failures or errors.

STATUS: COMPLETE
OWNER: build-engineer
WORK ORDER: Phase 6, step 0 (human architect decision 1): make `sbt check` faster with parallel test work, keep the gate's meaning, and set explicit forked-test heaps (about 2 GB each, 6 GB for sparkAnalytics).
FILES CREATED: none
FILES MODIFIED: /Users/brian/Developer/repos/scholarchain/build.sbt (50 insertions, 11 deletions; no other file changed)
COMMANDS RUN:
- Read-only inspection: grep, ls, cat, sed -n, and the existing JUnit XML reports (to get suite timings without re-measuring the baseline).
- `sbt "show Global/concurrentRestrictions" "show hadoop/Test/javaOptions" "show sparkAnalytics/Test/run/fork" "show sparkAnalytics/Test/runMain/javaOptions" "show sparkAnalytics/Test/run/javaOptions" "show hadoop/Test/testGrouping" "show contractTests/Test/testGrouping"`. Output showed: fork limit 3, -Xmx2g for hadoop and contracts, -Xmx6g for sparkAnalytics `Test/run` and `Test/runMain`, fork=true, and 2 fork groups each for hadoop and contractTests.
- `(time sbt check)`, one run in the background, logged to /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/2614013c-933e-4dcb-99ca-fc3105c6c7c1/scratchpad/check1.log. Exit 0.
- The formatter hook (`sbt scalafmtOnly build.sbt`) failed during three intermediate edits, because the call sites briefly still used the old `forkedTests` signature. The final file loads and formats cleanly; `sbt check` ran the format checks and passed.
TEST RESULTS: `sbt check` passed (exit 0).
- Log lines: `[info] Module tests: suites=46 executed=1065 passed=1065 failed=0 errors=0 skipped=0` and `[info] Contract tests (no AWS): suites=22 executed=336 passed=336 failed=0 errors=0 skipped=0`, then `Updated .../.claude/.last-test-run (module_tests=1065, contract_tests=336)`, then `[success] Total time: 689 s (0:11:29.0)`.
- Shell `time`: `11:32.16 total`. Start and end epoch stamps differ by 692 s.
- The log has 0 `[error]` lines and no suite line with a non-zero failed count.
VALIDATION RESULTS:
- `.claude/.last-test-run` was written by `markTestRun` only: timestamp=2026-11-15T06:38:01Z, module_tests=1065, contract_tests=336.
- I saw no sign of parallel interference: every suite passed, and the long Hadoop suites took about as long as in Phase 5 (for example PairLimitAdversarialSuite 208 s vs 207 s, PipelineSuite 150.5 s vs 149.7 s).
ASSUMPTIONS:
- The two expected-fail contract tests are counted as passed, as in Phase 5. The 336 total matches the baseline.
- The sbt server JVM heap is unchanged (launcher default). Only forked-test heaps were allowed to change.
RISKS:
- Peak memory once Phase 6 adds Spark tests: up to 3 forked JVMs at once could be sparkAnalytics (6 GB) plus two others at 2 GB each, plus the sbt JVM, about 11-12 GB of heap ceiling on a 16 GB machine. If swapping shows up, lower `maxForkedTestJvms` to 2.
- Fork groups are assigned by sorted suite name, round-robin, not by duration. New slow suites could unbalance the groups; this affects speed only, not correctness.
- Interference check: tests that run the Hadoop local job runner already isolate `hadoop.tmp.dir`, staging and local dirs in a fresh `createTempDirectory` per test (modules/hadoop/.../TestSupport.scala lines 24-35; tests/contracts/.../JobHarness.scala lines 28-44). The entity-resolution tests use unique temp dirs. sparkAnalytics has no tests yet, so there are no shared Spark warehouse or checkpoint paths. Spark tests that spark-engineer adds later should use per-test temp dirs too.
- Per decision 1, if any later `sbt check` fails in a way that looks like parallel interference, step 0 must be reverted completely. The revert is the build.sbt hunks listed below.
MANUAL ACTION REQUIRED: none. Do not commit (the human commits).
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: The next agent's `sbt check` (domain-engineer) is the second confirmation required by decision 1. After that, code review.

STEP 0 RESULT
- Result: ACCEPTED.
- Wall time: 11 min 32 s (shell `time` 11:32.16; sbt reports 689 s).
- Module tests: 1065. Counted from the `markTestRun` log line `Module tests: suites=46 executed=1065 passed=1065 ...`, which sums `Tests.Output` events across core, core2_13, hadoop, entityResolution, sparkAnalytics, storage, rag and services.
- Contract tests: 336. Counted from `Contract tests (no AWS): suites=22 executed=336 passed=336 ...`. Both numbers also appear in the rewritten `.claude/.last-test-run`.
- Exact diff summary (build.sbt only):
  1. New `Global / concurrentRestrictions`: sbt's defaults (limit all to the number of processors, which is 10; Clean is exclusive), except the forked test group limit goes from sbt's default of 1 to `maxForkedTestJvms = 3`.
  2. `forkedTests(options)` became `forkedTests(heap, options)`, which puts the heap first in `Test / javaOptions`. New values: `forkedTestHeap = "-Xmx2g"` for hadoop and contractTests, `sparkForkedTestHeap = "-Xmx6g"` for sparkAnalytics. The spark heap also reaches `sparkAnalytics/Test/run` and `Test/runMain` (verified with `show`).
  3. New `forkGroups(n)`: sets `Test / testGrouping` by sorting suites by name and dealing them round-robin into n forked JVMs, using the project's own `Test / forkOptions`. Each suite runs exactly once. Applied with n=2 to `hadoop` and `contractTests`.
  4. `markTestRun`: stages 3 and 4 used to be nested `Def.taskDyn`s (module tests, then contract tests). They are now one `Def.task` that depends on both `moduleTests` and `contractTestsNoAws`, so the two start together. The body that writes the marker runs only after both have succeeded, and either failure throws before the write. The stage comments were updated to match.
- How parallelism works: after formatting, ledger and `Test/compile` (unchanged order), the module and contract test stages run at the same time. Inside them, the non-forked module suites run in the sbt JVM, and 4 forked groups (hadoop ×2, contractTests ×2) share at most 3 concurrent forked JVMs. In this run the slowest group finished in about 11 minutes.

Proposed later (not done): nothing else needed now. If the Phase 6 Spark tests become long, consider assigning fork groups by suite duration.
