STATUS: BLOCKED-ON
OWNER: hadoop-engineer
WORK ORDER: Phase 17, session 3, repair round R3 (instructor-approved, human-authorized): B1 (V2 HADOOP-04) oversize-block strategy, done; B3 (V2 HADOOP-PAIRS-WRONG) pairs for teams above the hard limit, stopped as BLOCKED-ON human (the work order's stop condition: full expansion judged unsafe at the output size it allows).
FILES CREATED:
- /Users/brian/Developer/repos/scholarchain/modules/hadoop/src/main/scala/org/scholarchain/hadoop/blocking/BlockSizeJobs.scala
- /Users/brian/Developer/repos/scholarchain/modules/hadoop/src/main/scala/org/scholarchain/hadoop/pipeline/BlockingChain.scala
- /Users/brian/Developer/repos/scholarchain/modules/hadoop/src/test/scala/org/scholarchain/hadoop/OversizeBlockStrategySuite.scala
FILES MODIFIED:
- /Users/brian/Developer/repos/scholarchain/modules/hadoop/src/main/scala/org/scholarchain/hadoop/blocking/BlockingJob.scala
- /Users/brian/Developer/repos/scholarchain/modules/hadoop/src/main/scala/org/scholarchain/hadoop/blocking/FallbackJobs.scala
- /Users/brian/Developer/repos/scholarchain/modules/hadoop/src/main/scala/org/scholarchain/hadoop/common/Counters.scala
- /Users/brian/Developer/repos/scholarchain/modules/hadoop/src/main/scala/org/scholarchain/hadoop/common/Settings.scala
- /Users/brian/Developer/repos/scholarchain/modules/hadoop/src/main/scala/org/scholarchain/hadoop/pipeline/Pipeline.scala
- /Users/brian/Developer/repos/scholarchain/modules/hadoop/src/main/resources/scholarchain-hadoop-defaults.xml (descriptions of the two maxBlockSize keys only; no values changed)
- /Users/brian/Developer/repos/scholarchain/modules/hadoop/src/test/scala/org/scholarchain/hadoop/FallbackBlockingSuite.scala
- /Users/brian/Developer/repos/scholarchain/modules/hadoop/src/test/scala/org/scholarchain/hadoop/LocalPipelineRunnerSuite.scala
COMMANDS RUN:
- sbt -batch "hadoop/Test/compile": success
- sbt -batch "contractTests/Test/compile": success (compile only, to confirm the test-engineer suites still compile against the new signatures; no contract test was run)
- sbt -batch "hadoop/testOnly org.scholarchain.hadoop.OversizeBlockStrategySuite": 9/9 passed
- Mutation check: temporarily made OversizeKeySet.configured return an empty set (pre-pass ignored) and reran the suite: 2 failed, 7 passed (the J05 and J05b pre-pass tests failed as expected). The mutation was reverted, and grep confirms it is gone.
- sbt -batch "hadoop/testOnly org.scholarchain.hadoop.*" (every hadoop module suite): 123 total, 122 passed, 1 failed (LocalPipelineRunnerSuite: its job-list expectation was missing the two new pre-pass jobs, an intended change)
- I updated that expectation, then ran sbt -batch "hadoop/testOnly org.scholarchain.hadoop.LocalPipelineRunnerSuite org.scholarchain.hadoop.OversizeBlockStrategySuite": 13/13 passed
- Read-only git, grep, sed -n and wc for inspection. No sbt check, no clean, no runMain, no AwsTest or EmulatorTest, nothing under scripts/**.
TEST RESULTS:
- OversizeBlockStrategySuite (new): 9/9 passed
- FallbackBlockingSuite (changed): 17/17 passed
- LocalPipelineRunnerSuite (changed): 4/4 passed (after the update)
- Direct users, all passed in the full module run: BlockingDeterminismSuite 3/3, CandidateSuite 11/11, PipelineSuite 4/4, ScholarChainHadoopSuite 3/3, SettingsSuite 6/6, ReuseVerificationSuite 6/6, RunRecoverySuite 1/1, DblpChainSuite 7/7, LinkAndBlockSuite 6/6, plus the other module suites (DblpExtractJob 7, DblpParsing 11, Normalizer 6, PairsAndTeams 8, Scanner 14)
VALIDATION RESULTS:
- Deterministic output: the new suite runs the whole chain three times (input reversed, input split over 3 files). Every reduce-side output of J05p, J05, J05bp, J05b and J05c is byte-identical, and the run IDs are equal.
- Same records as before: with the pre-pass and with the old reducer-side cap, the same fixture gives identical blocks/, oversizeBlocks/, oversizeMembers/, fallback blocks/, fallbackOversizeBlocks/ and coverage/, and identical OVERSIZE_BLOCKS, OVERSIZE_BLOCK_MEMBERS, BLOCKS_EMITTED, SINGLETON_BLOCKS, DUPLICATE_RECORDS and FALLBACK_KEYS_EMITTED. So the er-v2 calibrated candidate set is unchanged. The blocking policyVersion was deliberately not bumped.
- Sample run: the synthetic local pipeline run (LocalPipelineRunnerSuite) completed with the pre-passes in the job list. No EMR run (not allowed).
ASSUMPTIONS:
- The existing keys scholarchain.blocking.maxBlockSize (200) and scholarchain.blocking.fallback.maxBlockSize (200) are the "configurable maximum block size". No new key was added; their descriptions now document the pre-pass and the still-oversize policy.
- The still-oversize policy keeps today's semantics: the group is not formed, and its members are covered by their other fallback blocks or listed as uncovered. A policy that splits such groups further (salting or sub-keys) would change the candidate set, which is entity-resolution-engineer policy. Listed under proposed later.
- The 4-argument spec signatures of BlockingJob and FallbackBlockingJob still work (new last parameter oversizeKeys: Option[Path] = None), so tests/contracts compiles unchanged. Without the pre-pass the reducer-side cap remains as a guard and is counted as REDUCER_CAPPED_BLOCKS.
RISKS:
- The test-engineer harness (tests/contracts/.../JobHarness.scala:138, :143) still calls J05/J05b without the pre-pass, so those contract tests exercise the guard path. Their outputs and asserted counters are unchanged by design, but they do not cover the pre-pass. Recommend switching the harness to pipeline.BlockingChain.run.
- Two extra jobs per pipeline run (J05p and J05bp, both cheap: combiner, one count per key and map task). Every J05, J05b, J05c, J06 and J09 run ID changes, because J05 and J05b now have the pre-pass runs in their input runs. Earlier output directories are not reused.
- J05p and J05bp re-read the same input as J05 and J05b. Rejects of malformed lines are therefore recorded by both jobs (each job accounts for its own input).
- The memory of the oversize key set in each map task is bounded by values/(cap+1) keys: about 30,000 for 3 million mentions in two families at cap 200.
- stats/ now has one more stat per reduce task (largestReduceGroup), which changes the scholarchain.output-records:stats counter of J05 and J05b.
MANUAL ACTION REQUIRED:
- Human decision on B3 (see the B3 section): choose an option, then route the work.
BLOCKED-ON OWNER: human
BLOCKED-ON EVIDENCE: B3 only. The numbers are in the B3 section: one 1 MiB dblp record (scholarchain.dblp.maxRecordBytes = 1048576, defaults.xml:141-142) can hold about 30,000 realistic or about 52,000 minimal author elements. Fully expanded, that is 0.45 to 1.35 billion pair instances, about 310 to 940 GB per pair stage (measured about 693 bytes per pair instance). Today's hard limit of 5,000 gives 12.5 million pairs (about 8.7 GB).
NEXT GATE: code-reviewer review of B1. test-engineer closes the wave with sbt check (and may switch JobHarness to BlockingChain). After that, the human decides B3, and documentation-engineer updates the docs listed below.

## Job list (after B1)

J01, J07, J08a, J08b (DblpChain, unchanged); J02 per source; J04; then the blocking chain, now run by `pipeline/BlockingChain.run` (called from Pipeline.scala:146):

- J05p `hadoop.j05p-block-sizes` (new): mentionContexts -> oversizeKeys/
- J05 `hadoop.j05-blocking`: mentionContexts + J05p oversizeKeys/ -> blocks/, oversizeBlocks/, oversizeMembers/
- J05bp `hadoop.j05bp-fallback-block-sizes` (new): oversizeMembers/ -> oversizeKeys/ (still-oversize fallback keys)
- J05b `hadoop.j05b-fallback-blocking`: oversizeMembers/ + J05bp oversizeKeys/ -> blocks/, fallbackOversizeBlocks/, coverage/
- J05c `hadoop.j05c-fallback-coverage`: unchanged

Then J06 and J09 as before.

## Counters

New, in group `scholarchain`:

| Counter | Job | Meaning |
|---|---|---|
| BLOCK_KEYS_COUNTED | J05p, J05bp | distinct keys counted |
| OVERSIZE_KEYS_FOUND | J05p, J05bp | keys over the effective cap |
| OVERSIZE_MEMBERS_REKEYED | J05 | member values re-keyed by mention ID; equals OVERSIZE_BLOCK_MEMBERS |
| REKEYED_BLOCKS | J05b | fallback groups within the cap |
| STILL_OVERSIZE_BLOCKS | J05b | groups still over the cap after fallback; a subset of OVERSIZE_BLOCKS |
| STILL_OVERSIZE_MEMBERS | J05b | values of still-oversize groups, counted and never shuffled |
| REDUCER_CAPPED_BLOCKS | J05, J05b | groups that reached one reduce call over the cap; 0 with the pre-pass |

New stats: `largestKeyGroup` (J05p, J05bp) and `largestReduceGroup` (J05, J05b; at most the cap with the pre-pass).

Unchanged in value: OVERSIZE_BLOCKS, PAIR_LIMIT_OVERSIZE_BLOCKS, OVERSIZE_BLOCK_MEMBERS, FALLBACK_KEYS_EMITTED, per-family groups, largestBlock, largestFallbackBlock, largestEmittedFallbackBlock.

## Reject reasons

No new reasons. J05p and J05bp reject malformed input lines as schema_failure, blank_line, oversize_line or truncated_input through ContractLineMapper, like J05 and J05b.

## Sample-run evidence

- Fixture run (OversizeBlockStrategySuite, local job runner):
  - J05p: BLOCK_KEYS_COUNTED 3, OVERSIZE_KEYS_FOUND 1, oversizeKeys = [{family dblp-base-name, key "Wei Wang", size 5, limit 3}].
  - J05: OVERSIZE_MEMBERS_REKEYED 5, OVERSIZE_BLOCK_MEMBERS 5, OVERSIZE_BLOCKS 1, BLOCKS_EMITTED 1, SINGLETON_BLOCKS 1, REDUCER_CAPPED_BLOCKS 0, largestReduceGroup at most 3 (it is 5 without the pre-pass), largestBlock 5.
  - J05bp: OVERSIZE_KEYS_FOUND 1 (["Wei Wang","Ann Lee"], size 3, limit 2).
  - J05b: FALLBACK_KEYS_EMITTED 6, STILL_OVERSIZE_MEMBERS 3, STILL_OVERSIZE_BLOCKS 1, OVERSIZE_BLOCKS 1, REKEYED_BLOCKS 2, BLOCKS_EMITTED 1, SINGLETON_BLOCKS 1, REDUCER_CAPPED_BLOCKS 0, largestReduceGroup at most 2 (it is 3 without the pre-pass).
  - J05c: 2 covered (W4, W5), 3 uncovered (W1, W2, W3).
- Pipeline corpus (FallbackBlockingSuite): J05b STILL_OVERSIZE_BLOCKS 4, STILL_OVERSIZE_MEMBERS 12, REKEYED_BLOCKS 6, REDUCER_CAPPED_BLOCKS 0 in J05 and J05b, OVERSIZE_MEMBERS_REKEYED 12. Every previously asserted record and counter is unchanged.
- The synthetic local pipeline (LocalPipelineRunnerSuite) completed, with 12 jobs in the report.

## B1 (V2 HADOOP-04): oversize-block strategy

**Finding.** The J05 cap was applied only in the reducer (old BlockingJob.scala:405 and :449). The mapper emitted every member, so a whole oversize group, recorded at up to 5,127 members, was shuffled to one reduce call. J05b had the same issue: an oversize fallback group was buffered and dropped in one reduce call.

**Why it is a genuine defect.** The course specification (docs/agent-runs/phase-17/course-specification.md, "A giant reducer group is a correctness and availability risk ... maximum block size, fallback keys, counters, and a policy for oversize groups. Simply allocating a larger reducer is not a strategy.") requires that the group never forms. The old code bounded memory but not the group.

**Change.**

1. A counting pre-pass before each blocking job:
   - J05p (BlockSizeJobs.scala:169) and J05bp (:240): mapper, then CountSumCombiner, then a reducer.
   - The reducer lists the keys over the effective BlockLimit cap as OversizeKey v1 records in oversizeKeys/.
2. J05 mapper (BlockingJob.scala:564) loads that list (OversizeKeySet, :52 of BlockSizeJobs.scala):
   - A member of an oversize key is re-keyed by its mention ID: `#member\t<family>\t<key>\t<mention>\t<hash>`, grouped on 4 fields.
   - BlockingPartitioner spreads these groups over all reducers by family, key and mention.
   - Each map task adds one size marker `#size\t<family>\t<key>` per oversize key.
   - BlockingReducer streams re-keyed members to oversizeMembers/. It sums the markers to list the block in oversizeBlocks/ with the same counters and stats as before.
   - Normal block groups keep the Phase 4 partition and grouping.
3. The documented fallback keys (FallbackKeys: coauthor, venue, coauthor-venue; FallbackKeys.reduceKey and reduceKeys, BlockingJob.scala:290 and :296) then re-key the oversize members in J05b.
4. Still-oversize policy (documented at FallbackJobs.scala FallbackBlockingJob scaladoc, and in defaults.xml for the fallback maxBlockSize):
   - A fallback group listed by J05bp is not formed. Its values are counted in the mapper and never shuffled.
   - A per-map-task marker `#<count>` lets the reducer count the group (OVERSIZE_BLOCKS, STILL_OVERSIZE_BLOCKS) and list it in fallbackOversizeBlocks/.
   - Its members are covered by their other fallback blocks, or else counted and listed as uncovered by J05c.
   - The policy is deterministic: it depends only on group sizes.
5. A pre-pass computed for another cap fails the job (JobFailed).
6. Wiring: BlockingChain.run, used by Pipeline.run. The pre-pass runs are recorded in input runs, so reruns reuse every directory. PipelineResult gains blockSizes and fallbackBlockSizes (appended last), and `all` lists the jobs in run order.

**Tests.**
- OversizeBlockStrategySuite, 9/9 passed:
  - J05p listing
  - J05 re-keying with largestReduceGroup at most the cap
  - the defect reproduced without the pre-pass (largestReduceGroup 5 > 3, REDUCER_CAPPED_BLOCKS 1)
  - records equal with and without the pre-pass
  - J05bp listing
  - J05b still-oversize policy, plus J05c coverage
  - byte determinism over three input layouts
  - limit-mismatch refusal
  - unit test of partitioner and grouping comparator (raw and object comparison agree, group order consistent with the sort)
- The mutation check above shows the pre-pass tests fail without the change.
- FallbackBlockingSuite 17/17 (new assertions on input runs and the new counters).
- LocalPipelineRunnerSuite 4/4.

**Files.** BlockSizeJobs.scala (new), BlockingChain.scala (new), BlockingJob.scala, FallbackJobs.scala, Counters.scala, Settings.scala, Pipeline.scala, scholarchain-hadoop-defaults.xml, and the three test files.

## B3 (V2 HADOOP-PAIRS-WRONG): stopped, BLOCKED-ON human, no change made

**Finding.** J07 rejects teams above scholarchain.teams.hardLimit (5000, defaults.xml:188-193) as team_exceeds_hard_limit (TeamAssembler.scala:75-76; PublicationTeamJob.scala:148-151, :159-165), so they get no team and no pairs. This is a genuine gap: the spec says to "count and report unusually large author teams", not drop their pairs, and my role law 5 says large teams expand to n(n-1)/2 pairs.

**Why full expansion is unsafe (numbers).**
- Pair expansion itself already streams: J08a shards of 100,000 pairs (PairJobs.scala:55-88, :96-138), so memory is not the issue. The issue is output volume, which only the dblp record limit bounds.
- Team bound. scholarchain.dblp.maxRecordBytes = 1,048,576 bytes:
  - minimal distinct `<author>XY</author>` elements are 20 bytes, so about 52,000 authors;
  - realistic elements (about 35 bytes) give about 30,000 authors.
- Pairs:
  - 52,000 authors give 1,351,974,000 pairs;
  - 30,000 authors give 449,985,000 pairs;
  - the current limit of 5,000 gives 12,497,500.
- Bytes. Measured on the local synthetic run (6,930 bytes for 10 pair instances), about 693 bytes per pair record:
  - about 937 GB (minimal) or about 312 GB (realistic) for J08a pairInstances/;
  - about the same again for J08b pairs/ and for the J09 export;
  - plus about the same in the J08b shuffle.
  - So one adversarial 1 MiB record can drive about 1 to 3.7 TB of writes, an amplification of about 10^6. That is the decompression-bomb class my law 4 guards against.
- The J08a shuffle copies the full team JSON into every shard: 13,520 shards of about 0.5 to 1 MB, about 7 to 13 GB.
- J07 builds each mention's coauthor list in O(n) (TeamAssembler.scala:104-124), so O(n^2), about 2.7 x 10^9 element operations at 52,000 authors.
- Downstream, Spark would receive up to 1.35 billion coauthored_with edges from a single work.
- Recorded data: max team 137 and 0 teams at the hard limit (Phase 15 experiment A). Real runs are not affected today; the risk is the unbounded worst case.

**Proposed alternatives (for the human), each giving correct pair information.**
- (A) Recommended: implicit clique.
  - Teams above the hard limit are kept, not rejected: J07 writes the team, authorPapers and mentionContexts, counts the team and lists it in oversizeTeams/.
  - Instead of materializing n(n-1)/2 records, J08 emits one large-team pair record per team (work ID, year, n, pair count, provenance). It states that every pair of its members is a coauthor pair, with positions derivable from the team.
  - Spark derives exact per-researcher coauthor counts and weights from team membership (each member gains n-1 coauthors from the work) and uses a work-hub representation for connectivity and bridge analytics.
  - Needs: a core contract (domain-engineer), Spark consumption (spark-engineer), and the hadoop change (me).
- (B) Full expansion with an approved cost bound. Remove the rejection, so expansion is bounded only by maxRecordBytes. This is about 0.3 to 0.94 TB per pair stage per worst-case record. It is an expensive scale change that needs human approval of the cost.
- (C) Full expansion up to a raised hard limit (for example 20,000 authors: 199,990,000 pairs, about 139 GB per stage), keeping the rejection above it. This still drops pairs above the limit, so it does not fully close the finding.

## Docs now outdated (documentation-engineer; I did not edit them)

docs/hadoop-job-contracts.md:
- :5 status note
- :30 run order (add J05p before J05, J05bp before J05b; Pipeline.scala line range changed; blocking now in pipeline/BlockingChain.scala)
- :42 partitioner (J05 now uses BlockingPartitioner and BlockingGroupingComparator, not TwoFieldPrefix)
- :44 "Combiner none" (J05p and J05bp set CountSumCombiner)
- :102-110 J05 section (input also J05p oversizeKeys/, map output tagged keys, reduce row :106, classes and line refs, configuration, tests: add OversizeBlockStrategySuite)
- :116-123 J05b section (input also J05bp oversizeKeys/, still-oversize policy, new counters, line refs)
- :129-135 J05c line refs shifted
- :143 J06 Pipeline.scala line refs
- new sections needed for J05p and J05bp
- :204-206 section 13 intro ("No code was changed")
- :208-214 section 13.1 fully outdated (the cap is now enforced before the reduce step)
- :216 onward, section 13.2 is still accurate, because B3 is unchanged

docs/data-lineage.md:
- :28-29 mermaid graph (add pre-passes)
- :76-78 job table (add J05p and J05bp; line refs)
- :86 "Every job uses StableHashPartitioner" (J05 partitioner)

docs/service-limits.md:
- :120 "blocking cap is applied in the reducer" is no longer true

Also, any doc citing Pipeline.scala:99-222, :57-59, :175-179 or :187-208 has shifted line numbers.

## Proposed later

- Switch the test-engineer JobHarness to BlockingChain (test-engineer).
- A finer still-oversize policy, such as a second fallback level or venue sub-keys for still-oversize groups (entity-resolution-engineer policy decision, because it changes the candidate set).
- Make J07's per-mention coauthor-context build O(maxCoauthorContext) instead of O(n) (only matters for B3 options A and B).

## INTERFACE HANDOFF

- Files changed: as listed above.
- Public signatures (new or changed):
  - `BlockingJob.spec(contexts: Path, outputRoot: Path, snapshotId: String, inputFingerprint: String, oversizeKeys: Option[Path] = None): JobSpec`
  - `FallbackBlockingJob.spec(oversizeMembers: Path, outputRoot: Path, snapshotId: String, inputFingerprint: String, oversizeKeys: Option[Path] = None): JobSpec`
  - `BlockSizeJob.spec(contexts, outputRoot, snapshotId, inputFingerprint)`, JobName "hadoop.j05p-block-sizes", OversizeKeys = "oversizeKeys", LargestKeyGroupStat = "largestKeyGroup"
  - `FallbackBlockSizeJob.spec(oversizeMembers, outputRoot, snapshotId, inputFingerprint)`, JobName "hadoop.j05bp-fallback-block-sizes"
  - `final case class OversizeKey(keyVersion: Int, family: String, keyFields: List[String], blockKey: String, size: Long, limit: Int)`, job contract scholarchain.hadoop.oversize-key v1
  - `OversizeKeySet.load(conf, dir, limit): Set[String]` and `OversizeKeySet.configured(conf, limit): Set[String]`
  - `FallbackKeys.reduceKey(fallbackFamily, primaryKey, parts): String` and `FallbackKeys.reduceKeys(m: OversizeMember, families: Set[String], kinds: List[String]): List[String]`
  - `BlockingSort.memberKey`, `sizeKey`, `groupPrefix`, `partitionPrefix`, MemberTag "#member", SizeTag "#size"
  - `BlockingPartitioner`, `BlockingGroupingComparator`, `CountSumCombiner`, `CountingValues`
  - `BlockingJob.LargestReduceGroupStat = "largestReduceGroup"`, `FallbackBlockingJob.SizeMarker = "#"`
  - `BlockingChain.run(conf, contexts: Path, teamsRun: RunId, outputRoot: Path, snapshotId: String, observer = Silent): BlockingChainResult(blockSizes, blocking, fallbackBlockSizes, fallbackBlocking, fallbackCoverage)` with `.all`
  - `PipelineResult` gains fields `blockSizes` and `fallbackBlockSizes` (appended last); `all` includes them in run order.
- New config keys:
  - only the path key `scholarchain.path.oversizeKeys` (set by the specs, not fingerprinted, no default, like the other path keys);
  - no new fingerprinted keys and no default changed (descriptions of maxBlockSize and fallback.maxBlockSize updated).
- Counters: see the Counters table.
- Fixtures: inline in OversizeBlockStrategySuite (8 mention contexts; expected groups and counters listed in its scaladoc). No resource files.
- Thread-safety: per-task state only (TaskIO, DivertedSizes, MaxTracker, oversize-key set loaded in setup); nothing is shared across tasks.
- Limits:
  - the oversize-key set per map task holds at most values/(cap+1) entries;
  - a size-marker group holds one value per map task;
  - a re-keyed member group holds the copies of one mention;
  - J05 and J05b reduce groups hold at most the effective cap (200 with the defaults).
