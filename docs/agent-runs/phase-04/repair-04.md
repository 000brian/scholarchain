## Phase 4 repair (round 1): hadoop-engineer

All required and minor findings (1, 3-9) are fixed inside `modules/hadoop/**`. `sbt fmt` and then `sbt check` passed (exit 0, 1177 s): 799 module tests (720 core across both cross-builds, 79 hadoop, up from 787 = 12 new hadoop tests) and 87 contract tests, all passing. `.claude/.last-test-run` was refreshed by `markTestRun` at 21:08.

No contract test asserts the old J08a stamping, so nothing is blocked on test-engineer. I also kept every public API the contract tests call compiling with its old signature (details in RISKS).

### Finding 1 (major): J08b provenance
- **Root cause:** `PairConsolidateJob.consolidate` returned `instances.head.copy(positions = …)`. The new merged record therefore kept J08a's `runId` and `jobName`.
- **Fix:**
  - New overload `consolidate(instances, run: RunContext)`. It runs the existing facts check and merge, then re-stamps with `run.provenance(...)`, keeping sourceType, sourceRecordIds, contentHash, datasetId and snapshotId from the instances.
  - `PairConsolidateReducer` now calls it with `io.run`.
  - The one-argument `consolidate` is kept as the pure merge, because the contract tests call it. Its scaladoc now says it still carries J08a provenance.
  - Scaledoc for J08b and for `consolidate` updated.
- **Lineage:**
  - `JobSpec` has a new field `inputRuns: List[RunId]`. The constructor requires `inputFingerprint == fingerprintRuns(inputRuns)` when it is non-empty.
  - `JobRunner` writes `"inputRuns"` (sorted, in plain form) to `_job.json`.
  - New helper `JobRunner.fromRuns(runs)(mk)`. It is applied to **every** job that reads upstream outputs: J07, J08a, J08b in `DblpChain`, and J04, J05, J06, J09 in `Pipeline`. Raw-input jobs (J01, J02) record `[]`.
- **Tests:**
  - `DblpChainSuite` "synthetic edge cases…": every consolidated pair has `runId == r.consolidate.runId` and `jobName == "hadoop.j08b-pair-consolidate"`. The SyntheticA24 pair's runId is not J08a's, and its source facts equal the instance's. This fails without the fix.
  - New `DblpChainSuite` "_job.json records the upstream run IDs in plain form (inputRuns)".
  - New `PairsAndTeamsSuite` "consolidation with a run context re-stamps provenance…".
- **Local run:** all 33 real-sample and 7 synthetic consolidated pairs carry `hadoop.j08b-pair-consolidate` and their own J08b run ID. The synthetic J08b `_job.json` has `inputRuns = ["run:v1:fbe8c1eb41503bf538597360653ec21a"]`, which is the J08a run.

### Finding 3: quadratic entity expansion
- **Root cause:** single-pass `DblpEntities.expand` had no output bound.
- **Fix:**
  - New `expand(xml, entities, maxChars): Either[ExpandError, String]` with `ExpandError.Undefined(name)` and `ExpandError.TooLarge(atLeast, max)`.
  - The bound is checked before each append and before the final append, using a true lower bound, so no more than `maxChars` characters are ever built.
  - New key `scholarchain.dblp.maxExpandedRecordChars`, default 2097152, documented in `scholarchain-hadoop-defaults.xml`.
  - New reject reason `expanded_record_oversize`. The J01 mapper rejects with this reason and the configured limit in the detail.
  - The old two-argument `expand` is kept unbounded for API compatibility and is not used by jobs.
- **Tests:**
  - `DblpParsingSuite` "bounded expansion refuses quadratic blow-up…": a 1 MiB entity referenced 300,000 times returns TooLarge after building less than 4 Mi characters.
  - `DblpExtractJobSuite` "entity blow-up beyond maxExpandedRecordChars is rejected…": the record is rejected as `expanded_record_oversize`, counted, and the accounting identity holds. This fails without the fix.

### Finding 4: DTD content not fingerprinted
- **Fix:** new `DblpExtractJob.inputFingerprint(conf, rawFp)`. When `scholarchain.dblp.dtdPath` is set, the result is `sha256` over the raw fingerprint plus `fingerprintFiles` of the DTD. `DblpChain` applies it both to the computed fingerprint and to an explicitly passed `--dblp-fingerprint`.
- **Test:** `DblpChainSuite` "a changed DTD file content (same path) changes the J01 input fingerprint and run ID". Previously the second run reused the old J01 output.

### Finding 5: "run ID identifies byte-identical output" overstated
- **Chose to weaken the claim.** Split layout depends on non-`scholarchain.*` Hadoop settings and the file system block size, so fingerprinting the split keys would still not make the claim true.
- **Doc changes:**
  - `Settings.OperationalKeys` scaladoc now states what a run ID fixes and what it does not: split layout (part-m files, output-records counters) and input location (absolute paths in `location` versus base names in the fingerprint).
  - The defaults XML header was reworded to match.
  - The `JobRunner` object doc and the `fingerprintFiles` doc now note base names and refer to those conditions.
- No code or test change.

### Finding 6: path traversal through snapshot IDs
- **Fix:** new `common/PathIds.scala`.
  - `require` uses `[A-Za-z0-9._-]+` and also rejects `.` and `..`.
  - `requireSegment` and `requireScope` use `[A-Za-z0-9._=+-]+` per segment, not `.` or `..`. This is needed because cross-dataset snapshot labels contain `+`.
- **Applied to:**
  - `PipelineSpec.dblpSnapshot`
  - `SourceInput.datasetId` and `snapshotId`
  - `DblpChain.run(snapshotId)`
  - every `JobSpec` (datasetId, snapshotId, scope), so every job validates before any file system call, including the `fs.delete`.
- **Tests:**
  - `PipelineSuite` "every path-forming identifier is validated…": includes the CLI `ScholarChainHadoop.run` with `--dblp-snapshot ../../x` and checks that nothing is created.
  - `DblpChainSuite` "path-forming snapshot IDs are validated before anything is written".

### Finding 7: O(n) list indexing in pair expansion
- **Fix:** `PairExpansion.IndexedTeam` copies `authorNames.toVector` once. `PairExpandReducer` builds one per shard and calls the new `pairAt(indexed, i, j, run)`. The old `pairAt(team, …)` delegates to it and is kept for the contract tests.
- **Test:** `PairsAndTeamsSuite` "an indexed team has O(1) name access and expands exactly like the team itself".

### Finding 8: hardcoded preview cap
- **Fix:** derived from configuration.
  - New `Settings.rejectPreviewBytes` = `previewChars × 4`. Four bytes is the UTF-8 maximum, so the preview holds at least `previewChars` characters in every supported encoding.
  - A value whose byte size is over `Int.MaxValue − 8` throws `SettingsError`. `JobRunner.run` checks it at driver time.
  - `DblpRecordScanner` and `LineScanner` take a `previewBytes` parameter, and both input formats pass the configured value.
  - `RawRecord.PreviewBytes` (4096) remains only for the scanners' configuration-free auxiliary constructors (tools and tests; the contract tests use it) and is documented that way. No job path uses it.
  - The defaults XML documents the rule.
- **Tests:**
  - `DblpExtractJobSuite` "reject previews of raw units follow scholarchain.reject.previewChars": `previewChars = 6000` gives a 6000-character preview, which was capped at 4096 before.
  - `SettingsSuite` "raw-unit preview bytes derive from previewChars…".
  - `ScannerSuite` "raw-unit previews use the configured preview size".

### Finding 9: phantom records
- **Doc fix:** the `DblpRecordScanner` scaladoc no longer claims "exactly once" without qualification. It describes how a split starting inside a comment or CDATA section produces a phantom unit.
- **Correction to the work order wording:** a phantom unit is counted (`INPUT_RECORDS`) and disposed of like any record, so it is never silently lost. But "rejected" is not always true. Malformed or incomplete markup is rejected, while a complete, well-formed in-subset record inside a comment can be **accepted**, or collapse into or conflict with the real record of the same key. The doc says this, and notes that the Phase 3 samples have no record markup inside comments (I checked).
- **Test:** `ScannerSuite` "documented limitation: a split starting inside a comment yields a counted phantom unit". It pins down the current behaviour; it is not a fix.

### Local bounded pipeline
- Stale outputs were **moved** to `/private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/183f6251-40b4-4bce-9aa1-4ee577720ebd/scratchpad/local-bounded-run-prev`. The old report is also at `/private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/183f6251-40b4-4bce-9aa1-4ee577720ebd/scratchpad/old-local-run-report.json`.
- Rerun: `sbt -batch "hadoop/Test/runMain org.scholarchain.hadoop.LocalBoundedPipeline"`, exit 0, 107 s. Report: `/Users/brian/Developer/repos/scholarchain/modules/hadoop/target/local-bounded-run/local-run-report.json`.
- **Run IDs:** all 20 job run IDs changed and all ran fresh. The cause is the new fingerprinted key `scholarchain.dblp.maxExpandedRecordChars`, which changes the configuration fingerprint of every job. For example:
  - real J01: 8fa16a76… → `run:v1:81883ef42399aa26719b49e51fc553d8`
  - real J08b: 9f087a2a… → `run:v1:2f4fa49f69baf437923727627160472e`
  - synthetic J08a: da804858… → `run:v1:fbe8c1eb41503bf538597360653ec21a`
  - synthetic J08b: 1bb414a3… → `run:v1:4c1bb7184f40b8095f3a0bd8cb2a7a8f`
- **Counters and stats:** identical to the previous run for every job (compared key by key).
- **Maxima, unchanged:**
  - Real samples: max team 7; largest pair expansion 21; largest blocking group 1; largest identifier group 9 (10.1145/3477495.3531753); 33 pair instances, 33 consolidated pairs; 67 links; 0 match candidates.
  - Synthetic: max team 5; largest expansion 10; largest blocking group 4 ("Synthetic Author"); largest candidate group 6; 11 pair instances, 7 consolidated pairs; 6 match candidates.
- **Reject counts by reason:** unchanged, since all counters are identical.

```text
STATUS: COMPLETE
OWNER: hadoop-engineer
WORK ORDER: Phase 4 repair round 1 (code-reviewer REQUEST_CHANGES findings 1, 3-9); human work order docs/agent-runs/phase-04/prompt.md
FILES CREATED:
  /Users/brian/Developer/repos/scholarchain/modules/hadoop/src/main/scala/org/scholarchain/hadoop/common/PathIds.scala
FILES MODIFIED:
  /Users/brian/Developer/repos/scholarchain/modules/hadoop/src/main/scala/org/scholarchain/hadoop/pairs/PairJobs.scala
  /Users/brian/Developer/repos/scholarchain/modules/hadoop/src/main/scala/org/scholarchain/hadoop/pairs/PairExpansion.scala
  /Users/brian/Developer/repos/scholarchain/modules/hadoop/src/main/scala/org/scholarchain/hadoop/common/JobRunner.scala
  /Users/brian/Developer/repos/scholarchain/modules/hadoop/src/main/scala/org/scholarchain/hadoop/common/Settings.scala
  /Users/brian/Developer/repos/scholarchain/modules/hadoop/src/main/scala/org/scholarchain/hadoop/common/Disposition.scala
  /Users/brian/Developer/repos/scholarchain/modules/hadoop/src/main/scala/org/scholarchain/hadoop/dblp/DblpEntities.scala
  /Users/brian/Developer/repos/scholarchain/modules/hadoop/src/main/scala/org/scholarchain/hadoop/dblp/DblpExtractJob.scala
  /Users/brian/Developer/repos/scholarchain/modules/hadoop/src/main/scala/org/scholarchain/hadoop/io/RawRecord.scala
  /Users/brian/Developer/repos/scholarchain/modules/hadoop/src/main/scala/org/scholarchain/hadoop/io/DblpRecordScanner.scala
  /Users/brian/Developer/repos/scholarchain/modules/hadoop/src/main/scala/org/scholarchain/hadoop/io/LineScanner.scala
  /Users/brian/Developer/repos/scholarchain/modules/hadoop/src/main/scala/org/scholarchain/hadoop/io/ScholarChainInputFormats.scala
  /Users/brian/Developer/repos/scholarchain/modules/hadoop/src/main/scala/org/scholarchain/hadoop/pipeline/DblpChain.scala
  /Users/brian/Developer/repos/scholarchain/modules/hadoop/src/main/scala/org/scholarchain/hadoop/pipeline/Pipeline.scala
  /Users/brian/Developer/repos/scholarchain/modules/hadoop/src/main/resources/scholarchain-hadoop-defaults.xml
  Tests in /Users/brian/Developer/repos/scholarchain/modules/hadoop/src/test/scala/org/scholarchain/hadoop/: DblpChainSuite.scala, PairsAndTeamsSuite.scala, DblpParsingSuite.scala, DblpExtractJobSuite.scala, ScannerSuite.scala, SettingsSuite.scala, PipelineSuite.scala
COMMANDS RUN:
  sbt -batch fmt "hadoop/test" (first attempt; stopped by me because it had compiled before a JobSpec fix; the suites that completed all passed)
  sbt -batch fmt  -> exit 0
  sbt -batch check -> exit 0, [success] Total time: 1177 s
  mv modules/hadoop/target/local-bounded-run -> scratchpad/local-bounded-run-prev
  sbt -batch "hadoop/Test/runMain org.scholarchain.hadoop.LocalBoundedPipeline" -> exit 0
TEST RESULTS: sbt check passed: core 720/720 (both builds), hadoop 79/79, contract tests 87/87, 0 failures. markTestRun refreshed .claude/.last-test-run (21:08).
VALIDATION RESULTS: local bounded pipeline ran 20 jobs, all fresh. Counters and stats identical to the previous run; only run IDs changed (new fingerprinted key). All 40 consolidated pairs carry jobName hadoop.j08b-pair-consolidate and their own J08b runId. _job.json inputRuns links J08b to J08a.
ASSUMPTIONS:
  - Finding 5: weakened the claim rather than fingerprinting split keys, since the block size and non-scholarchain split keys cannot be fixed by scholarchain.* keys.
  - maxExpandedRecordChars default 2,097,152 (twice maxRecordBytes); counted in UTF-16 characters.
  - Preview bytes = previewChars x 4 (UTF-8 maximum per character).
  - JobSpec identifiers use the segment pattern (allows '+' and '='), because cross-dataset snapshot labels contain '+'. User-supplied IDs use the strict [A-Za-z0-9._-]+ and also reject '.' and '..'.
RISKS:
  - API compatibility shims were kept for tests/** (owned by test-engineer): two-argument DblpEntities.expand (unbounded), one-argument PairConsolidateJob.consolidate (no re-stamp), PairExpansion.pairAt(team, ...), the 5-argument DblpRecordScanner and 4-argument LineScanner constructors, and RawRecord.PreviewBytes. No job path uses them; test-engineer may move to the bounded and configured variants.
  - In the bounded expand, an undefined entity after the bound is reached is reported as expanded_record_oversize, not undefined_entity.
  - Phantom records (Finding 9) are documented but not prevented; a well-formed record inside a comment that crosses a split can be accepted.
  - Every run ID changes compared with earlier outputs (new key), so older outputs are not reused.
  - Finding 2 (code-version stale reuse) is still open and must be resolved before the first cloud run.
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none (no contract test encodes J08a stamping on consolidated pairs; all 87 pass)
NEXT GATE: code-reviewer re-review of the repaired diff (modules/hadoop/**), then the human gate.
```

### Recommendations (not implemented)
- test-engineer: point the contract tests at `expand(…, maxChars)`, `consolidate(…, run)` and the configured scanner constructors, so the compatibility shims can be removed later.
- Finding 9 hardening, for later: a mapper-side check that a unit's offset is not inside a comment or CDATA section already seen by the previous split would need cross-split state. A cheaper option is to read uncompressed dblp files whole (`isSplitable = false`), which ties in with deferred Finding 14.
- Finding 12, for later: the Disposition scaladoc says `inputSha256` covers "the complete offending input", but for oversize or truncated units the mapper hashes only the preview.
