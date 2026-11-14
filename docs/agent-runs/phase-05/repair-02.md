# Repair 02: hadoop-engineer, test-engineer finding F5

Phase 5, run 1 of 2. Agent reports reproduced verbatim from the orchestrator session, in the order they were returned.

---

## Report 1: hadoop-engineer

F5 is fixed. `sbt check` passed (995/995 module tests, 204/204 contract tests) and refreshed `.claude/.last-test-run`. The change only adds things: `largestFallbackBlock` still measures what it did, now documented as the size before the cap. A new stat, `largestEmittedFallbackBlock`, gives the largest fallback block actually written to `blocks/`, and it shows up in the run-report summary next to the old one.

STATUS: COMPLETE
OWNER: hadoop-engineer
WORK ORDER: docs/agent-runs/phase-05/prompt.md (run 1 of 2), repair round for test-engineer finding F5 (info level): `largestFallbackBlock` can exceed `scholarchain.blocking.fallback.maxBlockSize`. Decisions 4, 8, 17 and 21 read.
FILES CREATED: none
FILES MODIFIED:
- /Users/brian/Developer/repos/scholarchain/modules/hadoop/src/main/scala/org/scholarchain/hadoop/blocking/FallbackJobs.scala
  - The J05b scaladoc defines both stats.
  - New constants: `FallbackBlockingJob.LargestFallbackBlockStat = "largestFallbackBlock"` and `FallbackBlockingJob.LargestEmittedFallbackBlockStat = "largestEmittedFallbackBlock"`.
  - The reducer has a second tracker (`largestEmitted`). It is offered `ms.size` only in the branch that writes to `blocks/`, and is flushed in cleanup.
  - The existing tracker is unchanged.
- /Users/brian/Developer/repos/scholarchain/modules/hadoop/src/test/scala/org/scholarchain/hadoop/FallbackBlockingSuite.scala: the J05b test now also checks the new stat (see TEST RESULTS).
- /Users/brian/Developer/repos/scholarchain/modules/hadoop/src/test/scala/org/scholarchain/hadoop/LocalPipelineRunner.scala
  - `summary.fallback` gains `largestEmittedFallbackBlock` (null when no fallback block was emitted).
  - Comments there and in the object scaladoc say `largestFallbackBlock` is the size before the cap, includes groups that were not emitted, and can exceed the cap.
- /Users/brian/Developer/repos/scholarchain/modules/hadoop/src/test/scala/org/scholarchain/hadoop/LocalPipelineRunnerSuite.scala: checks that the summary has both keys and that the emitted value, when present, is ≤ the fallback cap and ≤ `largestFallbackBlock`.
COMMANDS RUN:
- `sbt "hadoop/testOnly org.scholarchain.hadoop.FallbackBlockingSuite org.scholarchain.hadoop.LocalPipelineRunnerSuite"` (background): exit 0, Passed: Total 16, Failed 0.
- `sbt check` (background, alone): exit 0, `[success] Total time: 1477 s`.
- Read-only checks: grep, sed -n, ls, cat, date, git diff --stat.
- One `du -sh /Users/brian/Library`, used only to pass time while waiting. It moved to the background (booeojcmw) and I didn't read its output. It writes nothing.
TEST RESULTS:
- `sbt check`: Module tests suites=40 executed=995 passed=995 failed=0. Contract tests (no AWS) suites=15 executed=204 passed=204 failed=0. The counts match the earlier baseline because I added assertions to existing tests, not new tests.
- New assertions in the "J05b: fallback blocks by coauthor and venue, capped, ..." test (fixture: fallback cap 2, largest group before the cap 3):
  - `largestEmittedFallbackBlock` is 2 and equals the largest `size` in `blocks/`.
  - Its subject is the smallest `<family>\t<blockKey>` by code point among the tied blocks.
  - It is less than `largestFallbackBlock` (3).
  - These fail without the change, because the stat lookup throws when the stat is missing.
- The fixture is cap 2 with a group of 3 rather than the cap 3 / group 5 example. It tests the same thing: the size before the cap is above the cap, and the emitted size is at or below it.
- The contract test FallbackPipelineEndToEndSuite (`largestFallbackBlock` = 5 with cap 3) still passes, so the old meaning is unchanged.
VALIDATION RESULTS: `.claude/.last-test-run` was refreshed by markTestRun (06:22). The real run was not rerun, as instructed.
ASSUMPTIONS:
- "Emitted" means written to `blocks/`. That includes singletons when `scholarchain.blocking.emitSingletons=true`.
- The emitted value is the CandidateBlock `size`, counted after duplicate members are merged. The value before the cap counts raw map values.
- The tie-break and the merge across reducers use the existing MaxTracker / JobRunner.readStats rule (largest value; on a tie, smallest subject by code point), so the result is deterministic.
RISKS:
- Low. Outputs, counters, provenance and inputRuns are unchanged.
- The only output difference is one extra line per reducer in the job's `stats/` and one extra key in `_job.json` stats.
- J05b's run ID could change only if the code version is part of it, which is already expected because the real run must be redone from approved code.
MANUAL ACTION REQUIRED: The Hadoop real run (decision 17) must be rerun later from approved code in a new attempt root, e.g. `modules/hadoop/target/phase-05-real/attempt-NN/`. Nothing to commit from this agent.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Orchestrator routes back to test-engineer / code-reviewer to confirm F5 is closed; the Hadoop real run is rerun after approval.

Stat names and where they appear:
- `largestFallbackBlock` (J05b): the largest fallback group before the cap; it can exceed `scholarchain.blocking.fallback.maxBlockSize`. Subject `<family>.<kind>\t<fallback block key>`. It appears in:
  - J05b `stats/` and `_job.json` stats
  - `JobResult.stats`
  - `run-report.json` → `summary.fallback.largestFallbackBlock`
- `largestEmittedFallbackBlock` (J05b, new): the largest fallback block written to `blocks/`; never above the cap; same subject form; absent from stats (null in the summary) when no fallback block was emitted. It appears in:
  - J05b `stats/` and `_job.json` stats
  - `JobResult.stats`
  - `run-report.json` → `summary.fallback.largestEmittedFallbackBlock`

Job list: J05b (hadoop.j05b-fallback-blocking) changed; no other job touched.

Counters: unchanged.

Reject reasons: unchanged.

Sample-run evidence:
- The LocalPipelineRunnerSuite synthetic run went through J01–J09 and passed with the new summary key.
- The FallbackBlockingSuite pipeline (cap 2) gave `largestFallbackBlock` = 3 and `largestEmittedFallbackBlock` = 2.
