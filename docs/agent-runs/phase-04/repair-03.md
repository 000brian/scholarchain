The failing contract test now passes. Rerunning a failed run with `scholarchain.output.replaceIncomplete=true` keeps the same run ID and replaces that run's incomplete directory. `sbt check` passed: 787 of 787 module tests and 87 of 87 contract tests. I wrote only under `modules/hadoop/**`. Nothing under `tests/**`, `modules/core` or `docs/agent-runs/` was touched.

**Root cause:** `Settings.fingerprint` hashed every `scholarchain.*` key except `scholarchain.run.*` and `scholarchain.path.*`. That included `scholarchain.output.replaceIncomplete`. So the recovery rerun got a different run ID, which means a different directory, and the failed run's directory was never replaced. Every later default rerun kept failing with `JobFailed`.

**Fix** (in `common/Settings.scala`):
- A new explicit set `Settings.OperationalKeys` lists the keys left out of the fingerprint.
- A new `Settings.fingerprinted(key)` decides membership: a `scholarchain.*` key that is not under `run.*` or `path.*` and is not operational.
- `fingerprint` now uses that check.
- The key name `scholarchain.output.replaceIncomplete` is unchanged.
- The scaladoc in `JobRunner` and the header and descriptions in `scholarchain-hadoop-defaults.xml` now say the same thing; each operational key is marked "Operational (not fingerprinted)".

**Keys excluded from the fingerprint:**
1. `scholarchain.output.replaceIncomplete` only decides what to do with an incomplete directory of the same run. The recovery rerun has to reach that same directory. It never changes what the job writes.
2. `scholarchain.intermediate.compress` only affects shuffle (map output) compression. Reducers see the same sorted key groups either way, so the run directory's files and `_job.json` are unchanged. Only Hadoop's own counters move, and those are not recorded.
3. `scholarchain.intermediate.codec` is the same case as item 2.

**Keys I reviewed and kept in the fingerprint:**
- **Reducer counts** (all nine `*.reducers`, `expandReducers` and `consolidateReducers` keys). I checked this rather than assuming it. `MaxTracker.flush` writes one stat line per task, so the counter `scholarchain.output-records:stats` stored in `_job.json` depends on the number of tasks, and so does the split into `part-r-NNNNN` files. Excluding them would let one run ID stand for different bytes. That breaks the existing check that the same run ID gives byte-identical output (`DblpChainSuite`, "byte-identical across output roots").
- **Shard sizes** (`scholarchain.pairs.pairsPerShard`, `scholarchain.candidates.pairsPerShard`): they change the `PAIR_SHARDS` and `CANDIDATE_SHARDS` counters.
- **Output compression** (`scholarchain.output.compress`, `scholarchain.output.codec`): they change the output file bytes and names.
- **Everything else**: thresholds, subset rules, feature set, policy versions, size limits, reject preview length and the DTD path all change records or rejects. `scholarchain.code.version` also stays in.

**Proving tests:**
- `RunRecoverySuite` runs J01 on the dblp sample with the same recovery steps as the contract test:
  - a default run leaves a partial directory and fails with `JobFailed`;
  - the rerun with `replaceIncomplete=true` gets the same run ID and directory, the partial file is gone, and `_SUCCESS`, `_job.json` and the 4 works are present;
  - a later default rerun reuses that output.
- A new `SettingsSuite` test checks that the three operational keys leave the fingerprint unchanged and that 13 output-affecting keys change it, including code version, output compression, shard sizes and reducer counts.
- Both tests fail without the fix. I temporarily replaced the exclusion with `true`: `RunRecoverySuite` failed at line 37 with a different run ID (`run:v1:2b7ae957978271c515b236d0f78b918f`), and the `SettingsSuite` test failed at line 57. Then I restored the fix.

**Local bounded pipeline:** I moved the old outputs to `/private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/183f6251-40b4-4bce-9aa1-4ee577720ebd/scratchpad/local-bounded-run.before` (moved, not deleted) and reran. It exited 0.
- **Run IDs:** all 20 changed, as expected, because the fingerprint input changed. Example: real J01 went from `run:v1:e899f79a209e651f86385278b64cad70` to `run:v1:8fa16a76129dbe83d09523c9b415d7ac`.
- **Counters and maxima:** identical in all 20 jobs, so reject counts by reason are unchanged. The two run reports match once run IDs and hashes are masked.
- **Records:** 15 jobs differ byte-for-byte, only because records carry run IDs in their provenance. With run IDs and sha256 values masked, all 135 part files are identical.

**Recommendation on code version (not changed):** derive `scholarchain.code.version` from the build (for example the git commit plus the assembly jar hash) instead of trusting the caller's string. Optionally, check `_job.json`'s `configFingerprint` and `inputFingerprint` before reusing a completed directory.

STATUS: COMPLETE
OWNER: hadoop-engineer
WORK ORDER: Phase 4 repair (`docs/agent-runs/phase-04/prompt.md`, human decision 7): `JobLevelRerunSuite` recovery test (`JobLevelRerunSuite.scala:157-163`), operational keys in the configuration fingerprint.
FILES CREATED:
- /Users/brian/Developer/repos/scholarchain/modules/hadoop/src/test/scala/org/scholarchain/hadoop/RunRecoverySuite.scala
FILES MODIFIED (the module is still uncommitted):
- /Users/brian/Developer/repos/scholarchain/modules/hadoop/src/main/scala/org/scholarchain/hadoop/common/Settings.scala
- /Users/brian/Developer/repos/scholarchain/modules/hadoop/src/main/scala/org/scholarchain/hadoop/common/JobRunner.scala (scaladoc only)
- /Users/brian/Developer/repos/scholarchain/modules/hadoop/src/main/resources/scholarchain-hadoop-defaults.xml (documentation only, no values changed)
- /Users/brian/Developer/repos/scholarchain/modules/hadoop/src/test/scala/org/scholarchain/hadoop/SettingsSuite.scala
COMMANDS RUN:
- `sbt -batch "hadoop/testOnly org.scholarchain.hadoop.RunRecoverySuite org.scholarchain.hadoop.SettingsSuite"` with the fix disabled: 2 of 5 failed, as intended.
- `sbt -batch fmt`: success; 2 sources reformatted.
- `sbt -batch check`: exit 0, success, 1052 s.
- `sbt -batch "hadoop/Test/runMain org.scholarchain.hadoop.LocalBoundedPipeline"`: exit 0, success, 106 s.
- Python comparison scripts over `_job.json` files and part files, old run against new run.
TEST RESULTS: "Module tests: suites=25 executed=787 passed=787 failed=0 errors=0 skipped=0"; "Contract tests (no AWS): suites=8 executed=87 passed=87 failed=0 errors=0 skipped=0", including the previously failing `JobLevelRerunSuite` recovery test. The ledger check passed. `.claude/.last-test-run` was updated by `markTestRun`.
VALIDATION RESULTS: In the local bounded run, 20 of 20 jobs have the same counters and maxima as before and 135 of 135 part files are identical after masking run IDs and hashes. All 20 run IDs changed, as expected.
ASSUMPTIONS:
- Only Hadoop counter groups starting with `scholarchain` are recorded in `_job.json`, so shuffle compression cannot change it.
- A run ID should mean byte-identical output, which is why reducer counts stay fingerprinted.
RISKS:
- Changing a reducer count still gives a new run ID, so outputs are recomputed when reducers are tuned on EMR. This is deliberate. Excluding reducers would need the per-task stats counter made independent of task count; that is a separate decision.
- `scholarchain.dblp.dtdPath` is fingerprinted by its path string only. Changing the DTD contents at the same path would reuse stale output. This is not in this work order.
- Existing run directories made before this fix have old run IDs and will not be reused. Downstream or cloud consumers must rerun.
MANUAL ACTION REQUIRED: none. The old local outputs can be discarded from the scratchpad copy at your discretion.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: `test-engineer` reruns the `JobLevelRerunSuite` contract suite; then `code-reviewer` reviews; then the human gate.

## Job list
J01–J09 unchanged. Only how the run ID is computed changed, in `JobRunner` and `Settings`.

## Counters
Unchanged in all 20 local bounded jobs.

## Reject reasons
No change in reasons or counts.

## Sample-run evidence
Full log: `/private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/183f6251-40b4-4bce-9aa1-4ee577720ebd/scratchpad/lbp-fp.log`. New outputs are under `/Users/brian/Developer/repos/scholarchain/modules/hadoop/target/local-bounded-run`.
