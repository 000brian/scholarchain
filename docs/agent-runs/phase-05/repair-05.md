# Repair 05: hadoop-engineer, code-reviewer finding M-new-1

Phase 5, run 1 of 2. Agent reports reproduced verbatim from the orchestrator session, in the order they were returned.

---

## Report 1: hadoop-engineer

STATUS: BLOCKED-ON
OWNER: hadoop-engineer
WORK ORDER: Phase 5 run 1 (docs/agent-runs/phase-05/prompt.md, decisions 4, 8, 17, 21). Repair of code-reviewer finding M-new-1 (major). J05 could emit a primary block of 501–1000 members (within maxBlockSize 1000) that J06 then refused to expand (over maxPairsPerBlock 125000). Those members got no candidates and were not counted as uncovered or listed.

FILES CREATED: none

FILES MODIFIED:
- /Users/brian/Developer/repos/scholarchain/modules/hadoop/src/main/scala/org/scholarchain/hadoop/blocking/BlockingJob.scala (new `BlockLimit`; J05 reducer uses the effective cap; config-time validation; docs)
- /Users/brian/Developer/repos/scholarchain/modules/hadoop/src/main/scala/org/scholarchain/hadoop/blocking/FallbackJobs.scala (J05b reducer uses `BlockLimit` with fallback.maxBlockSize; config-time validation; docs)
- /Users/brian/Developer/repos/scholarchain/modules/hadoop/src/main/scala/org/scholarchain/hadoop/candidates/CandidateFeatureJob.scala (J06 lists and counts the members of any block it skips; new output dir `unexpandedMembers/`)
- /Users/brian/Developer/repos/scholarchain/modules/hadoop/src/main/scala/org/scholarchain/hadoop/common/Counters.scala (3 new counters, listed below)
- /Users/brian/Developer/repos/scholarchain/modules/hadoop/src/main/resources/scholarchain-hadoop-defaults.xml (descriptions of blocking.maxBlockSize, blocking.fallback.maxBlockSize, candidates.maxPairsPerBlock; no value changed)
- /Users/brian/Developer/repos/scholarchain/modules/hadoop/src/test/scala/org/scholarchain/hadoop/FallbackBlockingSuite.scala (4 new tests)
- /Users/brian/Developer/repos/scholarchain/modules/hadoop/src/test/scala/org/scholarchain/hadoop/CandidateSuite.scala (1 new test, 1 test reworked)
- /Users/brian/Developer/repos/scholarchain/modules/hadoop/src/test/scala/org/scholarchain/hadoop/LocalPipelineRunner.scala (run report adds `blocking.pairLimitOversizeBlocks`, `fallback.pairLimitOversizeBlocks`, `candidates.unexpandedMembers`)

COMMANDS RUN:
- `sbt -batch "hadoop/Test/compile"`: success.
- `sbt -batch "hadoop/testOnly org.scholarchain.hadoop.FallbackBlockingSuite org.scholarchain.hadoop.CandidateSuite"` (background): "Passed: Total 28, Failed 0, Errors 0, Passed 28".
- `sbt -batch check`, run alone in the background, 27 min: format check, compile and module tests passed. The contract tests failed one test (below). `.claude/.last-test-run` was not updated.
- Read-only commands only: ls, cat, grep, find, du, git diff.

TEST RESULTS:
- Module tests: no failure in any module test report. The hadoop reports show 0 failures and FallbackBlockingSuite 17/17. The new and changed module tests passed in the targeted run (28/28).
- Contract tests: 231 run, 1 failed. The failing test is `org.scholarchain.contracts.hadoop.JobLevelBlockingSuite` "J06 maxPairsPerBlock below 66: block not expanded, pairs counted as skipped", at tests/contracts/src/test/scala/org/scholarchain/contracts/hadoop/JobLevelBlockingSuite.scala:125:
  - `assertEquals(j05.main(Counters.BlocksEmitted), 1L)` got 0, expected 1.
  - Why: the test sets only `scholarchain.candidates.maxPairsPerBlock=65` on the configuration shared by J05 and J06, then expects J05 to emit the 12-member, 66-pair block and J06 to skip it.
  - That is exactly the gap M-new-1 closes. J05 now treats the block as oversize (reason max_pairs_per_block, effective cap 11), routes its members to the fallback, and J06 skips nothing.
  - The test pins the old behaviour. I did not edit tests/**.
- Other contract tests that touch these limits still pass, because their caps are member-bound: ScaleAndProvenanceAdversarialSuite (default-arithmetic check), JobLevelBlockingSuite max11/max12, FallbackPipelineEndToEndSuite.

VALIDATION RESULTS:
- The default values are unchanged: maxBlockSize 1000, fallback 500, maxPairsPerBlock 125000.
- With the defaults, the effective primary cap is 500 and the fallback cap is 500.
- A bounded local pipeline sample (module test, synthetic corpus, maxBlockSize 1000 and maxPairsPerBlock 3) produced exactly the same blocks, oversize members, fallback blocks, coverage and candidate count as the maxBlockSize-3 run.
- Runs into two output roots under pair-bound caps gave identical run IDs and byte-identical J05, J05b, J05c and J06 files.
- inputRuns checked: J05b = [J05]; J05c and J06 = sorted [J05, J05b].
- No real data was touched.

ASSUMPTIONS:
- The blocking policy version stays `blocking/2+author-name-key/1`. Phase 5 is uncommitted, and run IDs already change through the config fingerprint and code version. If you want a policy bump, that is a decision for the orchestrator or human, and it would affect ErSynth and Fixture strings in other owners' tests.
- J05 and J05b read `scholarchain.candidates.maxPairsPerBlock`, a key in J06's namespace. I did this on purpose so the three jobs cannot disagree within one configuration.

RISKS:
- J06's `unexpandedMembers/` is written on the map side. If a skipped block line appears twice in the input, its members would be listed twice. This path can only be reached when blocks come from another configuration.
- The test-engineer contract test above must be updated before `sbt check` can pass.

MANUAL ACTION REQUIRED: Route the contract-test update to test-engineer (evidence below).

BLOCKED-ON OWNER: test-engineer

BLOCKED-ON EVIDENCE:
- File: tests/contracts/src/test/scala/org/scholarchain/contracts/hadoop/JobLevelBlockingSuite.scala, lines 123–137, test "J06 maxPairsPerBlock below 66…".
- Failure: line 125, BlocksEmitted obtained 0, expected 1 (from `sbt check`, report TEST-org.scholarchain.contracts.hadoop.JobLevelBlockingSuite.xml).
- Suggested new expectations under one configuration with maxPairsPerBlock 65:
  - J05: OVERSIZE_BLOCKS 1, PAIR_LIMIT_OVERSIZE_BLOCKS 1, OVERSIZE_BLOCK_MEMBERS 12, BLOCKS_EMITTED 0.
  - J05 `oversizeBlocks/` line: limit 11, maxBlockSize 1000, maxPairsPerBlock 65, pairs 66, reason "max_pairs_per_block".
  - J06: OVERSIZE_BLOCKS 0, UNEXPANDED_BLOCK_MEMBERS 0.
- To still test J06's own skip path, run J06 with a different configuration from J05. The module test CandidateSuite "J06 oversize blocks are counted, listed and their members written out" shows the pattern.

NEXT GATE: test-engineer updates the contract test. Then `sbt check` must pass in full, and code-reviewer re-reviews M-new-1.

Chosen design:
- J05 and J05b treat a block as oversize when n > maxBlockSize OR n(n−1)/2 > maxPairsPerBlock. The effective cap is the largest m ≤ memberCap whose pairs fit; it is computed by binary search with no overflow.
- Such blocks follow the usual route: J05 sends members to `oversizeMembers/`, then J05b fallback, then J05c covered/uncovered accounting and the worst-key list. J05b sends them to `fallbackOversizeBlocks/` and J05c uncovered.
- As a result, no configuration can emit a primary or fallback block that J06 refuses to expand.
- J06 keeps its own check as a guard for blocks made under another configuration. That path is never silent: OVERSIZE_BLOCKS, OVERSIZE_BLOCK_PAIRS_SKIPPED, the `oversizeBlocks/` listing, and every member written to `unexpandedMembers/` as UncoveredMember v1 (family and blockKey name the skipped block).
- Settings are checked when each job is configured (positive member cap and positive pair limit).

New counters:
- PAIR_LIMIT_OVERSIZE_BLOCKS (J05, J05b): the subset of OVERSIZE_BLOCKS that fit the member cap but exceed the pair limit.
- UNEXPANDED_BLOCK_MEMBERS (J06), with per-family group `scholarchain.unexpanded-members-per-family`.
- The existing counter identity INPUT = ACCEPTED + EXCLUDED + REJECTED is unchanged and asserted in the new tests.

Listing schema: J05 `oversizeBlocks/` and J05b `fallbackOversizeBlocks/` now also carry `maxBlockSize`, `maxPairsPerBlock`, `pairs` and `reason` (`max_block_size` | `max_pairs_per_block`). `limit` is now the effective cap.

Config semantics: the defaults now give an effective primary cap of 500. Blocks of 501–1000 members are no longer emitted; they go to the fallback.

Real-run command: unchanged. With the planned maxBlockSize 200 and fallback cap 200, the effective caps stay 200 (19,900 pairs ≤ 125,000), so attempt behaviour matches. The code changed, so the code version, run IDs and listing JSON lines differ, and a new attempt root is required as already planned.

New or changed module tests:
- FallbackBlockingSuite "BlockLimit: effective cap…" (unit, includes the defaults composing to 500)
- FallbackBlockingSuite "block within maxBlockSize but over maxPairsPerBlock: oversize in J05, fallback, no loss" (fails on the old code: J05 emitted the 6-member blocks)
- FallbackBlockingSuite "any limits: no emitted block exceeds maxPairsPerBlock; every oversize member accounted" (maxPairsPerBlock 1)
- FallbackBlockingSuite "determinism across output roots under the pair-bound caps"
- CandidateSuite "one configuration: J05 routes a block over maxPairsPerBlock to oversize, J06 skips none" (fails on the old code)
- CandidateSuite "J06 oversize blocks are counted, listed and their members written out" (reworked to use a configuration mismatch)
