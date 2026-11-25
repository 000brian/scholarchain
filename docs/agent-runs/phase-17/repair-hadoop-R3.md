STATUS: COMPLETE
OWNER: hadoop-engineer
WORK ORDER: Phase 17, session 3, repair round R3: repair of the test-engineer finding "largestReduceGroup exceeds the cap with many map tasks, and depends on the map layout" (B1, HADOOP-04). One repair round.

FILES CREATED: none

FILES MODIFIED:
- /Users/brian/Developer/repos/scholarchain/modules/hadoop/src/main/scala/org/scholarchain/hadoop/blocking/BlockingJob.scala
- /Users/brian/Developer/repos/scholarchain/modules/hadoop/src/main/scala/org/scholarchain/hadoop/blocking/FallbackJobs.scala
- /Users/brian/Developer/repos/scholarchain/modules/hadoop/src/test/scala/org/scholarchain/hadoop/OversizeBlockStrategySuite.scala

COMMANDS RUN:
1. sbt -batch "hadoop/testOnly org.scholarchain.hadoop.OversizeBlockStrategySuite org.scholarchain.hadoop.FallbackBlockingSuite org.scholarchain.hadoop.BlockingDeterminismSuite org.scholarchain.hadoop.CandidateSuite org.scholarchain.hadoop.DblpChainSuite org.scholarchain.hadoop.LocalPipelineRunnerSuite"
   Result: exit 0, "Passed: Total 52, Failed 0, Errors 0, Passed 52", [success] 779 s.
2. sbt -batch "contractTests/testOnly org.scholarchain.contracts.hadoop.OversizeBlockAdversarialSuite org.scholarchain.contracts.hadoop.JobLevelBlockingSuite org.scholarchain.contracts.hadoop.JobLevelTeamScaleSuite" (test-engineer's suites, run read-only)
   Result: exit 0, "Passed: Total 30, Failed 0, Errors 0, Passed 30", [success] 609 s.
- Read-only shell: grep, sed -n, cat, git diff, ps, pgrep. I ran `python3 --version` once by mistake (it only printed the version; no script ran). I stopped two of my own wait loops with `kill`; they never ended because their pgrep matched their own command line. No sbt clean, no runMain, no AwsTest or EmulatorTest, no AWS, no network, no full sbt check (test-engineer closes the wave).

TEST RESULTS:
- hadoop: OversizeBlockStrategySuite 10/10 (includes the new test), FallbackBlockingSuite 17/17, BlockingDeterminismSuite 3/3, CandidateSuite 11/11, DblpChainSuite 7/7, LocalPipelineRunnerSuite 4/4. Total 52/52.
- contractTests: OversizeBlockAdversarialSuite 18/18 (this includes "largestReduceGroup stays within the cap and does not depend on the map-task layout", which failed before the repair, and the deterministic-layout test). JobLevelBlockingSuite 7/7. JobLevelTeamScaleSuite 5/5. Total 30/30.
- The JobFailed stack traces in the contract log come from the expected refusal tests (different cap, corrupt list, keyVersion 2). Those tests pass.

VALIDATION RESULTS:
- No record or counter changed. Only the `largestReduceGroup` stat changed. The new module test checks this: every record directory, the listed counters, every stat and every stats/ part file are byte-identical between an 8-map-task layout and the one-file layout. The contract suite's records-versus-reducer-side-cap and determinism tests also pass unchanged.
- tests/** was not edited. The contract test passes unchanged.

ASSUMPTIONS:
- A J05b group holds either member values or size markers, never both. The still-oversize list is the same in every map task, so a listed key gets only markers. Marker-only groups are skipped. If such a mix ever happened, the stat would count only its members, which is still bounded and layout-independent.

RISKS:
- The size of a marker group (number of map tasks that saw a key) is no longer reported anywhere. It is bounded by the map-task count and carries no data meaning. The block size itself is still reported in largestBlock / largestFallbackBlock and in the oversize listings.
- I did not run the new module test against the code before the repair (that would have needed a temporary revert). The reason it fails without the repair: with one file per line, J05 runs 8 map tasks and 5 of them carry "Wei Wang". The old code offered that marker group's 5 values, and 5 is more than the cap of 3. So `assert(g.value <= cap)` fails, and so does the stats/ byte comparison with the one-file layout (marker group 1 there).

MANUAL ACTION REQUIRED: none. The wave closer (test-engineer) must run the full sbt check, because .claude/.last-test-run is still stale.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: test-engineer reruns the full sbt check as wave closer, then code-reviewer.

## Repair

- Cause:
  - In J05, `BlockingReducer.reduce` offered every reduce group to the `largestReduceGroup` MaxTracker, size-marker groups (`#size\t<family>\t<key>`) included. A marker group holds one decimal count per map task that saw the key.
  - In J05b, `FallbackBlockingReducer` offered `reduceValues`, which counted the `#<n>` marker values of still-oversize groups.
  - So the stat followed the number of map tasks (23 / 9 at 8 KiB splits), not the data. It could go above the cap, and the J05 and J05b stats/ files changed with the split layout.
- Change (smallest that keeps records and counters):
  - BlockingJob.scala `BlockingReducer.reduce`: size-marker groups now go to `sizeMarkers(k, values)` without being offered to `groups`. Block groups and re-keyed member groups are still offered with their value count.
  - FallbackJobs.scala `FallbackBlockingReducer.reduce`: the stat now gets `members` (member values only), and only when `members > 0`. The `reduceValues` variable is removed.
  - Scaladoc now states the true bound:
    - `BlockingJob.LargestReduceGroupStat`: counts member values only, and marker groups are left out because their value count depends on the layout. With the pre-pass it never exceeds the effective cap and is the same for every map-task layout. Without the pre-pass it is the size of the largest group.
    - The BlockingJob oversize-strategy bullet on marker groups: one value per map task that saw the key, not counted in the stat.
    - The FallbackBlockingJob still-oversize policy: markers are not counted in the stat.
    - The BlockingSort claim ("every J05 output file is the same whatever the map task layout") is now true and was left as it is.
  - I chose this over a combiner because a combiner does not bound marker values across map tasks, so the stat would still depend on the layout.
- Test that fails without the repair:
  - Where: OversizeBlockStrategySuite, "largestReduceGroup counts member values only: within the cap and identical stats/ when many map tasks carry one oversize key".
  - Input: the hand-checkable fixture, one input file per line (8 J05 map tasks, 5 of them carry the oversize key "Wei Wang", cap 3), with `mapreduce.input.fileinputformat.split.maxsize=128`. That gives J05b one map task per oversize member line, so the still-oversize group "Wei Wang / Ann Lee" gets 3 markers for a cap of 2.
  - It asserts:
    - equal run IDs;
    - J05 stat <= 3 and J05b stat <= 2;
    - for every job of the chain: stats/ part files byte-identical to the one-file run, and the `stats` map equal;
    - the J05 and J05b counters (Accepted, OVERSIZE_BLOCKS, OVERSIZE_MEMBERS, OVERSIZE_MEMBERS_REKEYED, STILL_OVERSIZE_BLOCKS, STILL_OVERSIZE_MEMBERS, BLOCKS_EMITTED, SINGLETON_BLOCKS, REDUCER_CAPPED_BLOCKS) equal;
    - all record part files of J05 and J05b byte-identical.
  - Without the repair, J05's marker group reads 5 values, so the stat is 5 > 3 (see RISKS: reasoned, not observed by a reverted run).
- Exact pass counts: hadoop 52/52 (OversizeBlockStrategySuite 10/10), contractTests 30/30 (OversizeBlockAdversarialSuite 18/18, JobLevelBlockingSuite 7/7, JobLevelTeamScaleSuite 5/5).

## Job list, counters, reject reasons

- Jobs touched: J05 (hadoop.j05-blocking) and J05b (hadoop.j05b-fallback-blocking), reducers only.
- No counter or reject reason was added, removed or changed. The only output that changed is the `largestReduceGroup` stat line in stats/.

## Sample-run evidence

- Local bounded runs inside the tests:
  - The module fixture: 8 contexts, in an 8-file / tiny-split layout against a one-file layout.
  - The contract adversarial corpus: 4 layouts, including 8 KiB splits.
- All reduce-side files are byte-identical across layouts, stats/ included.

## INTERFACE HANDOFF

- Files changed: BlockingJob.scala, FallbackJobs.scala (reducers and scaladoc), OversizeBlockStrategySuite.scala (new test; `chain` gains an optional `extra` settings parameter; new private helper `partBytes`).
- Public signatures: unchanged. `BlockingJob.LargestReduceGroupStat = "largestReduceGroup"` has the same name and a new meaning: the most member values one reduce call read, size markers excluded. `CountingValues` is unchanged and is now used only for non-marker J05 groups.
- Fixtures: inline synthetic contexts only.
- Thread-safety: unchanged (per-task MaxTracker).
- Limits:
  - With the pre-pass, `largestReduceGroup` is at most the job's effective cap and does not depend on the map-task layout.
  - A J05 or J05b reduce task that reads only marker groups writes no `largestReduceGroup` line. That is deterministic, because the marker groups exist in every layout.
  - Without the pre-pass (reducer-side cap only), the stat is the largest group size and can exceed the cap, as the legacy assertions (120) expect.
