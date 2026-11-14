# Repair 07: hadoop-engineer, test-engineer Finding 1 (J05 oversizeMembers determinism)

Phase 5, run 1 of 2. Agent reports reproduced verbatim from the orchestrator session, in the order they were returned.

---

## Report 1: hadoop-engineer

I fixed Finding 1 and `sbt check` passes, which I watched finish. PairLimitAdversarialSuite now passes too: 10 tests, 0 failures, including the determinism-across-roots test at line 460 that failed before. I changed only files under `modules/hadoop/**`.

## The fix
- **Cause:** J05 used `<family>\t<blockKey>` as its whole map key. The reducer therefore saw a block's members in whatever order Hadoop merged the map outputs, and wrote the oversize members to `oversizeMembers/` in that order.
- **Fix:** J05 now uses a secondary sort, a composite key plus a grouping comparator.
  - The mapper's key is `<family>\t<blockKey>\t<mentionId>\t<sha256 hex of the member JSON>` (`BlockingSort.key`).
  - The job uses `TwoFieldPrefixPartitioner` and a new `TwoFieldPrefixGroupingComparator`. The comparator puts every key with the same `<family>\t<blockKey>` into one `reduce` call, comparing raw UTF-8 bytes up to the second tab.
  - So each block's members now arrive sorted by mention ID, then by content hash. Only byte-identical copies can tie, and their order does not change the output.
  - Every J05 output file is therefore byte-identical however the map tasks are split or merged. That covers `oversizeMembers/`, `blocks/` (already sorted by `normalise`) and `oversizeBlocks/`.
- **Why the grouping is safe:** neither family names nor block keys contain a tab. Families are validated, and `AuthorNameKey` turns all whitespace into a plain space. When the first two fields contain no tab, the keys of one group always sit next to each other in the sorted reduce input.
- **Partition files stay the same:** the partitioner hashes `<family>\t<blockKey>`, the same string as before, so each block lands in the same `part-r-NNNNN` file as in Phase 4.
- The reducer now takes the family and block key from the group key (`BlockingSort.block`) and keeps the `largestBlock` stat subject as `<family>\t<blockKey>`, as before.

## Other streams I checked (no change needed)
- **J05b:** the emitted blocks and `coverage/` come from the sorted member list, and oversize fallback blocks emit nothing per member.
- **J05c:** each key is one member; duplicate copies and fallback families are sorted, and the summaries are written in sorted key order.
- **J06 `unexpandedMembers/`:** written on the map side, in member order taken from deterministic J05/J05b block files.

## Memory bound
- The J05 reducer holds at most the effective cap of member JSON strings, about 500 with the defaults. Once a block passes the cap it streams every member to `oversizeMembers/` and holds nothing more.
- Nothing is sorted in memory. Hadoop's shuffle does the ordering, spilling to disk under `mapreduce.task.io.sort.mb`.
- The cost is about 100 extra key bytes per map record (mention ID plus 64 hex characters), which only affects shuffle size.

## Run ID / config hash
- **They do not change.** `Settings.fingerprint` covers only `scholarchain.*` keys, and I added none. The partitioner and grouping comparator are Hadoop job classes, not part of the fingerprint.
- Because of that, the same `scholarchain.code.version` string gives the same run ID as before the fix.
- For the real run: use a new attempt number in the code version and a new empty output root, as decision 17 already requires. Otherwise J05 output written by the old code could be reused (Finding 2 is still open).

## Files changed by this repair
- New: `/Users/brian/Developer/repos/scholarchain/modules/hadoop/src/test/scala/org/scholarchain/hadoop/BlockingDeterminismSuite.scala`
- Modified: `/Users/brian/Developer/repos/scholarchain/modules/hadoop/src/main/scala/org/scholarchain/hadoop/blocking/BlockingJob.scala` (new `BlockingSort` object, mapper key, partitioner and comparator wiring, reducer key parsing, scaladoc)
- Modified: `/Users/brian/Developer/repos/scholarchain/modules/hadoop/src/main/scala/org/scholarchain/hadoop/common/StableHashPartitioner.scala` (new `TwoFieldPrefixGroupingComparator`)

The other changed or untracked files that `git status` shows under `modules/hadoop` are from earlier Phase 5 work, not this repair.

## Module test (fails without the fix)
`BlockingDeterminismSuite` has three tests:
1. It runs J05 with 2 reducers over the same mention contexts in three layouts: one file, the same file reversed, and three files split and partly reversed. The input includes a byte-identical duplicate line and a second, different record for one mention ID. It checks that every `blocks/`, `oversizeBlocks/` and `oversizeMembers/` part file is byte-identical, and that the counters match.
2. It checks that `oversizeMembers/` lines are sorted by family, block key, mention ID, then content hash.
3. It checks that the comparator's raw-byte and object comparisons both match a comparison of the two-field prefixes.

Before I wired in the fix, tests 1 and 2 failed and test 3 passed. Test 1 reported `oversizeMembers/part-r-00001 differs`; test 2 showed the members out of order. After the fix all 3 pass.

```text
STATUS: COMPLETE
OWNER: hadoop-engineer
WORK ORDER: docs/agent-runs/phase-05/prompt.md (Phase 5, run 1 of 2), repair round for test-engineer Finding 1 (J05 oversizeMembers/ not byte-deterministic across output roots); decisions 8, 17, 21
FILES CREATED: /Users/brian/Developer/repos/scholarchain/modules/hadoop/src/test/scala/org/scholarchain/hadoop/BlockingDeterminismSuite.scala
FILES MODIFIED: /Users/brian/Developer/repos/scholarchain/modules/hadoop/src/main/scala/org/scholarchain/hadoop/blocking/BlockingJob.scala; /Users/brian/Developer/repos/scholarchain/modules/hadoop/src/main/scala/org/scholarchain/hadoop/common/StableHashPartitioner.scala
COMMANDS RUN: read-only ls/grep/find/cat/wc/date and git status/diff --stat; sbt -batch "hadoop/testOnly org.scholarchain.hadoop.BlockingDeterminismSuite" (background, before the fix: 2 failed, 1 passed, as intended); sbt -batch check (background, after the fix: success)
TEST RESULTS: sbt check succeeded (1955 s): module tests suites=44 executed=1035 passed=1035 failed=0 errors=0; contract tests (no AWS) suites=19 executed=253 passed=253 failed=0 errors=0. PairLimitAdversarialSuite: tests=10 failures=0 errors=0. BlockingDeterminismSuite: tests=3 failures=0. .claude/.last-test-run refreshed by markTestRun at 11:58.
VALIDATION RESULTS: The new module test failed before the fix (oversizeMembers/part-r-00001 differed when only input order changed) and passes after it. The test-engineer determinism test (PairLimitAdversarialSuite.scala:460) passes, so J05, J05b, J05c and J06 bytes match across output roots. No real DBLP run was needed or done.
ASSUMPTIONS: J05 block keys and family names never contain a tab (AuthorNameKey turns all whitespace into a space; families are validated); the grouping depends on this. The order of byte-identical duplicate values does not change the output.
RISKS: About 100 extra bytes per J05 map record in the shuffle (mention ID plus a 64-hex hash). In theory, if a future block-key family allowed tabs inside keys, groups could split; the current families cannot produce tabs. Run IDs do not change with this code change, so the real run must use a new attempt in scholarchain.code.version and a new empty output root (Finding 2 is still open).
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: test-engineer re-verification of Finding 1 (PairLimitAdversarialSuite), then code-reviewer review; then the decision 17 real run with a new attempt number and output root
```

## Agent-specific sections
- **Jobs touched:** J05 (`hadoop.j05-blocking`) only. Its map key format and its partitioner/grouping comparator changed; the output schemas did not.
- **Counters:** names and meanings are unchanged. In the test, the counters were identical across all three input layouts, including OVERSIZE_BLOCK_MEMBERS = 28.
- **Reject reasons:** unchanged; this fix adds no new reject paths.
- **Output schemas:** unchanged: `scholarchain.hadoop.candidate-block` v2, `scholarchain.hadoop.oversize-member` v1, and the oversize-block listing. Only the line order inside `oversizeMembers/` part files changed: it is now sorted by mention ID, then content hash, within each block.
- **Sample-run evidence:** local LocalJobRunner runs in BlockingDeterminismSuite (three input layouts, 2 reducers, byte-identical part files) and the test-engineer's two-root comparison in PairLimitAdversarialSuite. No DBLP data was read.

