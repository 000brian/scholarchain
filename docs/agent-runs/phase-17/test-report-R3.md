STATUS: FAILED
OWNER: test-engineer
WORK ORDER: Phase 17, session 3, repair round R3 (instructor-approved, human-authorized). Covers B1 (V2 HADOOP-04): counting-pass coverage in the contract harness, plus adversarial and cross-module tests. Covers B3: a test of the hard-limit safety bound, with no production change. I was the wave closer.

FILES CREATED:
- /Users/brian/Developer/repos/scholarchain/tests/contracts/src/test/scala/org/scholarchain/contracts/hadoop/OversizeBlockAdversarialSuite.scala (18 tests)

FILES MODIFIED:
- /Users/brian/Developer/repos/scholarchain/tests/contracts/src/test/scala/org/scholarchain/contracts/hadoop/JobHarness.scala
- /Users/brian/Developer/repos/scholarchain/tests/contracts/src/test/scala/org/scholarchain/contracts/hadoop/JobLevelTeamScaleSuite.scala (one new test for B3)

COMMANDS RUN:
1. sbt -batch "contractTests/Test/compile": success.
2. sbt -batch "contractTests/testOnly ...OversizeBlockAdversarialSuite ...JobLevelTeamScaleSuite": Total 23, Failed 3, Passed 20.
   - Failure 1: my own model miscount (19 still-oversize groups, not 18). Fixed.
   - Failure 2: a determinism mismatch in stats/. It has the same root cause as the finding below. I split the test: records and size stats in one test, the largestReduceGroup stat in its own test.
   - Failure 3: the finding itself (largestReduceGroup above the cap).
   - JobLevelTeamScaleSuite: 5/5 passed, including the new B3 test.
3. sbt -batch "contractTests/testOnly ...OversizeBlockAdversarialSuite ...JobLevelBlockingSuite ...PairLimitAdversarialSuite ...JobLevelDblpAdversarialSuite": Total 41, Failed 1, Errors 0, Passed 40.
   - OversizeBlockAdversarialSuite: 18 tests, 1 failure (the finding).
   - JobLevelBlockingSuite: 7/7.
   - PairLimitAdversarialSuite: 10/10.
   - JobLevelDblpAdversarialSuite: 6/6.
   - The last three now run through the harness's pre-pass path.
4. sbt check (full gate, wave closer), run once, exit 1:
   - scalafmt check: passed. Ledger check: passed. Compile: passed.
   - Module tests: suites=151 executed=2361 passed=2361 failed=0 errors=0 skipped=5.
   - Contract tests (no AWS): suites=35 executed=418 passed=417 failed=1 errors=0 skipped=0.
   - The one failure is OversizeBlockAdversarialSuite, "largestReduceGroup stays within the cap and does not depend on the map-task layout".
   - Marker: ".claude/.last-test-run was not updated" (it is still dated Nov 22 23:40).
- No sbt clean, no runMain, no AwsTest or EmulatorTest, no AWS, no network. Nothing was run under scripts/** or datasets/scripts/**.

TEST RESULTS: see COMMANDS RUN. Final state: 1 failing test, which shows a production defect (below).

VALIDATION RESULTS:
- Contract harness now covers the counting pass (task 1).
  - `candidateStage` runs `BlockingChain.run` (J05p -> J05 -> J05bp -> J05b -> J05c) and then J06.
  - `blockingAndCandidates` runs J05p -> J05 with `oversizeKeys`, then J06.
  - Both check `assertPrePassEffective` on every run: REDUCER_CAPPED_BLOCKS is 0, and OVERSIZE_KEYS_FOUND of the pre-pass equals OVERSIZE_BLOCKS of the job.
  - J05 also checks that OVERSIZE_MEMBERS_REKEYED equals OVERSIZE_BLOCK_MEMBERS.
  - So every existing user of the harness now runs the pre-pass path, and all of them passed unchanged: JobLevelBlockingSuite, PairLimitAdversarialSuite (pair limits 1, 6, Int.MaxValue member cap, seeded random limits), and JobLevelDblpAdversarialSuite.
  - `CandidateStage` gains `j05p` and `j05bp`, and `all` includes them, so the map-accounting identity, rerun reuse and byte-determinism checks in PairLimitAdversarialSuite now also cover the pre-passes.
  - `legacyCandidateStage` keeps the 4-argument path (reducer-side cap only). It is used only to show that a test fails on the old path.

## Finding (BLOCKED-ON hadoop-engineer): largestReduceGroup exceeds the cap with many map tasks, and depends on the map layout

Severity: minor to major (the owner and the human decide). The records are correct. The stat that is the evidence for HADOOP-04, and the invariant documented for it, are not.

- Claim: the production scaladoc at BlockingJob.scala, `LargestReduceGroupStat`, says "With the pre-pass it never exceeds the job's effective cap (size marker groups hold one value per map task)". The hadoop R3 report also lists "J05 and J05b reduce groups hold at most the effective cap" under Limits.
- Cause:
  - J05 size-marker groups (`#size\t<family>\t<key>`, one value per map task; J05 has no combiner) are offered to the same MaxTracker (BlockingReducer.reduce: `groups.offer(counted.count, ...)`).
  - In J05b, the marker values `#<n>` of a still-oversize group are counted as reduce values (FallbackBlockingReducer: `reduceValues`).
  - So a reduce group holds one value per map task. That is more than the cap whenever more map tasks than the cap carry the key, and it changes with the split layout.
- Evidence: OversizeBlockAdversarialSuite, same input and configuration (J05 cap 5, J05b cap 3), with 8 KiB splits. Observed output from both the targeted run and sbt check:

```text
small-splits: J05 largestReduceGroup Stat(largestReduceGroup,23,#size	dblp-base-name	Skew Hot) > cap 5
small-splits: J05b largestReduceGroup Stat(largestReduceGroup,9,dblp-base-name.venue	Skew Hot	conf/sigir) > fallback cap 3
J05 stat by layout: List((base,5), (reversed,5), (five-files,5), (small-splits,23))
J05b stat by layout: List((base,3), (reversed,3), (five-files,3), (small-splits,9))
```

- The J05 stats/ part files also differ byte-wise between the J07 four-part layout and one reversed file. For example, `largestReduceGroup 4 "#size ... Cap Plus"` versus `3 "... Skew Mate Aa"`.
  - This contradicts the BlockingSort scaladoc: "every J05 output file is the same whatever the map task layout".
  - Every other reduce-side file is byte-identical across the four layouts: blocks, oversize listings, oversize members, oversize keys, fallback blocks, coverage, uncovered, summary, and every other stat.
- Practical impact today is small. Marker values are decimal counts, not members, and they are bounded by the map-task count, not by the data's skew. On EMR at the default cap of 200 this needs more than 200 map tasks carrying one key.
  - But at the full dblp scale the stat could report more than 200 and look like a HADOOP-04 regression. And the documented invariant is false.
- Possible repairs (owner's choice):
  - Count only member values in largestReduceGroup, and report marker groups separately.
  - Pre-aggregate markers, for example with a combiner (this does not fully bound them across tasks).
  - Or correct the documentation to state the bound max(cap, map tasks carrying the key) and accept that stats/ depends on the layout.
- Owner: hadoop-engineer (modules/hadoop/**). I made no production change. The test stays failing until the owner repairs the code or the human accepts a documented bound, in which case the test is re-specified.

## Requirement-to-test coverage

| Requirement | Test | Fails without the change? How checked |
|---|---|---|
| Harness exercises the counting pass | JobHarness `candidateStage` / `blockingAndCandidates` + `assertPrePassEffective`; users JobLevelBlockingSuite, PairLimitAdversarialSuite, JobLevelDblpAdversarialSuite | Yes: on the 4-argument path, REDUCER_CAPPED_BLOCKS > 0 for every oversize key (shown in the legacy assertions of the new suite: 16, and 19 in J05b) |
| Heavily skewed key far above the cap (Skew Hot, 120 = 24x cap) | "J05: a key 24x the cap and 15 other oversize keys at once..." | Yes: the same test runs `legacyCandidateStage`, which reads 120 values in one reduce call (largestReduceGroup 120, REDUCER_CAPPED_BLOCKS 16) |
| Keys exactly at the cap and at cap + 1 | "J05p lists exactly the oversize keys..." (Cap Exact 5 not listed and emitted; Cap Plus 6 listed); J05bp test (Edge Three 3 emitted; Edge Four 4 still oversize) | J05p/J05bp do not exist on the old path; boundary values match the independent model |
| Many oversize keys at once (16 primary, 19 fallback) | J05p, J05, J05bp, J05b tests; dual-family test (>= 30 oversize) | as above |
| Fallback group still over the cap: counted, listed, never formed, members covered or reported uncovered | "J05b still-oversize groups...", "J05c: still-oversize members are covered...or listed uncovered" | Yes: the legacy J05b has STILL_OVERSIZE_BLOCKS 0, REDUCER_CAPPED_BLOCKS 19 and largestReduceGroup 120 (asserted in the test) |
| Records unchanged vs reducer-side cap | "pre-pass and reducer-side cap write the same records on the adversarial corpus" | Guards the policy (er-v2 candidate set) |
| Determinism across input order and split layouts | "deterministic: reversed input, shuffled files and small splits..." (all reduce-side part files, run IDs, counters) | passes except largestReduceGroup, which is tested separately (finding) |
| No reduce group above the cap (largestReduceGroup) | J05 and J05b tests (default layout: pass); "largestReduceGroup stays within the cap and does not depend on the map-task layout" (FAILS: finding) | legacy gives 120 |
| Pre-pass for a different cap refused | member cap 4 and 6 vs 5; same member cap but pair limit 6 (effective 4 vs 5); J05bp fallback cap 2 vs 3; corrupt list; keyVersion 2 | all JobFailed. I cannot mutate production; the hadoop report's mutation check covers removal of the pre-pass |
| Stale pre-pass missing keys is counted, not silent | "a pre-pass that misses an oversize key: the guard counts it" (REDUCER_CAPPED_BLOCKS 15, same records) | documents the guard |
| Counters add up | `countersAddUp` on one family (caps 5/3) and two families with three kinds (caps 4/2): keys counted = emitted + singletons + oversize; found = OVERSIZE_BLOCKS; re-keyed = members = listed sizes; largestKeyGroup = largestBlock; J05bp counted = REKEYED + STILL; REKEYED = emitted + singletons; still = OVERSIZE_BLOCKS; STILL_OVERSIZE_MEMBERS = listed sizes; FALLBACK_KEYS_EMITTED = block members + singletons + still members; covered + uncovered = oversize members; J06 = pairs of emitted blocks; map identity of all six jobs | the legacy J05b violates the J05bp identity (asserted) |
| Run IDs: pre-pass in input runs; cap change changes all IDs; rerun reuses | "BlockingChain records the pre-pass runs as inputs..." | |
| B3: team above a small hard limit counted and written to rejects; teams at or below expand to every pair exactly once | existing: JobLevelTeamScaleSuite "hard limit (assemble path)" and "(overflow path)"; new: "B3 safety bound: at the hard limit every pair once; above it counted and rejected" (limit 12: teams 12 / 13 / 25 / 2 / 4) | New coverage: the exact boundary (12 expands to 66 pairs, every position pair once; 13 rejected "team of 13 > 12"; 25 rejected "25 mentions > 12"), reject reason counter 2, REJECTED_REDUCE 2, OVERSIZE_AUTHOR_TEAMS 3, contexts only for kept teams, no pairs for rejected works, sharded expansion (9 shards), consolidation, map identity. Passed. |

## Failures by owning agent

- hadoop-engineer: 1 (the finding above). No dataset-engineer finding: the B1 tests do not read datasets/**.

ASSUMPTIONS:
- `mapreduce.input.fileinputformat.split.maxsize` does not enter the run ID (observed: equal run IDs across layouts), so the layouts compare byte-for-byte.
- The model uses core `AuthorNameKey.baseNameKey` and `fullNameKey` (a core contract) for key strings. All grouping, sizes, coverage and caps are computed independently.

RISKS:
- Contract test runtime grew: OversizeBlockAdversarialSuite takes about 4 to 5 minutes locally (about 45 local jobs), and the harness adds two pre-pass jobs per stage.
- JobHarness is now a stricter shared check. A future regression of the pre-pass fails every harness user with an `assertPrePassEffective` message, not only the new suite.

MANUAL ACTION REQUIRED: Route the finding to hadoop-engineer, or have the human decide to accept a documented bound. After the repair, a wave closer must rerun sbt check, because the marker was not refreshed.

BLOCKED-ON OWNER: hadoop-engineer
BLOCKED-ON EVIDENCE: OversizeBlockAdversarialSuite.scala, test "largestReduceGroup stays within the cap and does not depend on the map-task layout". Output: "small-splits: J05 largestReduceGroup Stat(largestReduceGroup,23,#size\tdblp-base-name\tSkew Hot) > cap 5"; "J05b ... 9 ... > fallback cap 3". Cause: BlockingJob.scala BlockingReducer.reduce `groups.offer(counted.count, ...)` includes size-marker groups; FallbackJobs.scala FallbackBlockingReducer counts marker values in `reduceValues`. sbt check: contract tests executed=418 passed=417 failed=1; module tests 2361/2361 (5 skipped); marker not updated.
NEXT GATE: hadoop-engineer repair (or a human decision on the bound), then targeted rerun of OversizeBlockAdversarialSuite and a full sbt check by the wave closer, then code-reviewer.

## INTERFACE HANDOFF

- Files changed: JobHarness.scala, JobLevelTeamScaleSuite.scala (tests only); OversizeBlockAdversarialSuite.scala (new).
- Public test-harness signatures (JobHarness):
  - `effectiveCap(c: Configuration, capKey: String): Int` (from c plus DefaultsXml, via BlockLimit)
  - `assertPrePassEffective(prePass: JobResult, blocking: JobResult, clue: String): Unit`
  - `primaryBlocking(c, ch: DblpChainResult, out: JPath, snapshot): (JobResult, JobResult)`, which returns (J05p, J05)
  - `blockingAndCandidates(...)`: same signature; now runs J05p -> J05 -> J06
  - `CandidateStage(j05p, j05, j05bp, j05b, j05c, j06)`, with `all` in run order. Changed from 4 to 6 fields; callers use names only.
  - `candidateStage(...)`: same signature; now runs `BlockingChain.run` then J06
  - `LegacyStage(j05, j05b, j05c, j06)` and `legacyCandidateStage(...)`: the 4-argument specs, without pre-passes
- Fixtures: inline synthetic dblp XML (names tagged Synthetic/Skew/Many etc.; no real identifiers). No resource files.
- Thread-safety: munit suites run sequentially. Lazy vals are per suite. Work directories are fresh temp directories under tests/contracts/target/job-work/.
- Limits: about 45 local Hadoop jobs in the new suite (1800 s timeout); 8 KiB splits produce about 23 map tasks for J05.
