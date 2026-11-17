# repair-01-hadoop (Phase 11 step 3a, hadoop-engineer)

STATUS: BLOCKED-ON
OWNER: hadoop-engineer
WORK ORDER: docs/agent-runs/phase-11/prompt.md (sha256 a0b59fae...e9ed1), step 3a, decision 8: Phase4-F2 (P1), Phase5-m5 (P1); record-only A-03. Targeted tests only, not the wave closer.
FILES CREATED:
- /Users/brian/Developer/repos/scholarchain/modules/hadoop/src/test/scala/org/scholarchain/hadoop/ReuseVerificationSuite.scala
FILES MODIFIED:
- /Users/brian/Developer/repos/scholarchain/modules/hadoop/src/main/scala/org/scholarchain/hadoop/common/JobRunner.scala
- /Users/brian/Developer/repos/scholarchain/modules/hadoop/src/main/resources/scholarchain-hadoop-defaults.xml
- /Users/brian/Developer/repos/scholarchain/modules/hadoop/src/test/scala/org/scholarchain/hadoop/FallbackBlockingSuite.scala
- /Users/brian/Developer/repos/scholarchain/modules/hadoop/src/test/scala/org/scholarchain/hadoop/CandidateSuite.scala
- /Users/brian/Developer/repos/scholarchain/modules/hadoop/src/test/scala/org/scholarchain/hadoop/DblpChainSuite.scala
COMMANDS RUN:
- Run 1, background: sbt -batch "hadoop/testOnly org.scholarchain.hadoop.*" "contractTests/testOnly org.scholarchain.contracts.hadoop.* org.scholarchain.contracts.er.NameKeyAdversarialSuite" (log .../scratchpad/step3a-targeted.log). hadoop had 4 failures from the default change, so the contract suites did not run.
- Run 2 (the evidence run), same command after the hadoop test fixes (log .../scratchpad/step3a-targeted-2.log), EXIT=1.
- Read-only: grep, sed -n, ls, git status; and the orchestrator's report check on modules/hadoop/target/test-reports, which returned no output.
TEST RESULTS:
- hadoop: Passed: Total 109, Failed 0, Errors 0, Passed 109.
- contractTests: Failed: Total 115, Failed 7, Errors 0, Passed 108.
  - 1 is the expected JobLevelRerunSuite flip (F2).
  - 6 are in JobLevelBlockingSuite, caused by the decided m5 default change. That file is under tests/**, which I may not edit.
VALIDATION RESULTS:
- `grep -l -E 'failures="[1-9]|errors="[1-9]' modules/hadoop/target/test-reports/TEST-*.xml` printed nothing (exit 1).
- Determinism: JobLevelDblpAdversarialSuite "two output roots ... byte-identical part files" also compares `_job.json` byte for byte across roots. It still passes with the new `outputs` list, because the list uses relative paths.
ASSUMPTIONS:
- "Refuse reuse" means the run fails with JobFailed and nothing is deleted or rewritten, even when replaceIncomplete=true. That setting stays limited to incomplete directories.
- Checking the inputs alone cannot catch the advisory-13 case (same code version, output changed later). Following the review's F2 routing, I also verify each output file's length and sha256 against a list in `_job.json`.
- A `_job.json` written before this change has no `outputs` list. It is refused (fail closed).
RISKS:
- On reuse, the driver re-reads and hashes every output file of the run. On EMR with S3 this costs one full read of the job's outputs per reused job. Writing the list after a fresh run costs one more read too.
- Output roots made before Phase 11 can no longer be reused. A rerun over them fails with "has no outputs list" and needs a new output root.
- A code change run under an unchanged `scholarchain.code.version` is still not detected when its outputs come out byte-identical. That case is harmless. A build-embedded identity (build-engineer) and scripts that pass the commit (infra-engineer, Phase 13) are still open, as the work order says.
- m5 (as reviewer A-01 notes): with both families on by default, large initials-surname blocks usually go through the J05b oversize fallback, which is now the normal path. A-01 is routed to entity-resolution-engineer.
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: test-engineer
BLOCKED-ON EVIDENCE:
- The done-when says the targeted run must be green apart from the JobLevelRerunSuite flip. It is not: tests/contracts/src/test/scala/org/scholarchain/contracts/hadoop/JobLevelBlockingSuite.scala fails 6 of 7 tests.
- Each one asserts single-family block counts under the default configuration. With the decided m5 default (dblp-base-name,initials-surname), the second family emits the same hot block again.
- Log lines in step3a-targeted-2.log:
  - "default limits: one hot block of 12 members ..." (line 74, BlocksEmitted expected 1, obtained 2)
  - "J05 maxBlockSize below the hot block ..." (line 112, OversizeBlock 1 vs 2)
  - "J05 maxBlockSize equal to the hot block size ..." (line 129, BlocksEmitted 1 vs 2)
  - "maxPairsPerBlock 65 under one configuration ..." (line 166, OversizeBlock 1 vs 2)
  - "J06 guard: J05 under a permissive pair limit ..." (line 232, BlocksEmitted 1 vs 2)
  - "J06 pairsPerShard 10: 7 shards ..." (line 283, CandidateShards 7 vs 14)
- The suggested fix, test-engineer's call: pin `Settings.Keys.BlockingFamilies -> "dblp-base-name"` in that suite's configurations. I did the same for the equivalent module tests (CandidateSuite, DblpChainSuite).
- No hadoop production change is needed. Both P1 items are done and the hadoop module is green.
NEXT GATE: step 4 (test-engineer): invert JobLevelRerunSuite "documented gap" (advisory 13), and pin or update JobLevelBlockingSuite for the m5 default. Then the wave closer runs sbt check, then step 5 re-checks both P1 items.

## Per-item status

### Phase4-F2 (P1): DONE
- Change, in JobRunner.scala:
  - `_job.json` now ends with an `outputs` list: every non-hidden file under the run directory, as {path relative to the run directory, length, sha256}, sorted by code point. Hidden means a path segment starting with `_` or `.`, which excludes `_SUCCESS`, `_job.json` and .crc files.
  - On reuse, `load` reads the earlier `_job.json` and checks that every identity field equals the current run's: jobName, runId, datasetId, snapshotId, codeVersion (`scholarchain.code.version`), inputFingerprint, inputRuns, configFingerprint. It also checks that the directory's files are exactly the recorded list: no missing file, no unrecorded file, same length, same sha256.
  - Anything else throws `JobFailed("<job>: refusing to reuse <dir>: <first difference>")`, and nothing is removed.
  - An unreadable or malformed record, or a missing field, is also refused.
  - Default-file docs (replaceIncomplete) and the JobRunner scaladoc were updated to match.
- Test: ReuseVerificationSuite, 6 tests, all pass:
  - the outputs list matches what is on disk, and an untouched rerun is reused;
  - a changed length is refused and the file left in place, also with replaceIncomplete=true;
  - same length but different bytes is refused on sha256;
  - an extra file, and a missing file, are refused;
  - a record with a different codeVersion, inputFingerprint or configFingerprint is refused;
  - a record with no `outputs`, no `inputRuns`, or invalid JSON is refused.
- How I know these tests fail without the fix: I did not run them against the old code, because restoring it would need a non-read-only git command.
  - By construction: the old `load` reused any directory with `_SUCCESS` and `_job.json`, so every `intercept[JobFailed]` in the suite would fail with "expected exception", and the first test would fail because `outputs` is missing.
  - Empirical: JobLevelRerunSuite "documented gap" used to pass against the old code (it was written to pass while the gap exists, advisory 13) and now fails with the new refusal. That is the flip below.

### Phase5-m5 (P1): DONE
- Change: the `scholarchain.blocking.keyFamilies` default in scholarchain-hadoop-defaults.xml changed from `dblp-base-name` to `dblp-base-name,initials-surname`, and its description was updated.
- Test: FallbackBlockingSuite "configured families and fallback keys are validated" now asserts that `BlockingKeys.configured(defaults) == List("dblp-base-name", "initials-surname")`, and that an explicit `dblp-base-name` still gives one family.
  - Under the old default this assertion fails, because `configured` returned `List("dblp-base-name")`.
  - Empirical evidence that the default really changes job behaviour: run 1 failed 4 single-family-count module tests, with blocks emitted 6 instead of 3 and oversize blocks 2 instead of 1.
- Follow-on module test edits: those 4 tests (CandidateSuite x3, DblpChainSuite "giant teams") now pin `keyFamilies=dblp-base-name`. They test J06 and limit counts, not the family default; their assertions are unchanged.

### A-03 (minor, record only, not fixed)
- PublicationTeamJob.scala:81: the UTF-8 decoder replaces malformed input, and rawSha256 is computed over the decoded line. Recorded as reported; no change.

## Expected-failure flips
- Phase4-F2 / advisory 13: contractTests JobLevelRerunSuite "documented gap: same code.version string reuses a completed output without validation" now FAILS with:
  `org.scholarchain.hadoop.common.JobFailed: hadoop.j01-dblp-extract: refusing to reuse <repo>/tests/contracts/target/job-work/rerun.../out-stale/dblp/snapshot=rerun-2026-11-13/j01-dblp-extract/run=d63e67dd0da400c7324e48e7596137fa: output works/part-r-00000 has length 32, recorded 1270`
- That test is a plain test, not `.fail`, so MUnit reports an ordinary failure. test-engineer inverts it in step 4.
- The other 7 tests in JobLevelRerunSuite pass. That includes "same inputs and configuration: every job is reused ... files untouched", which shows that verified reuse keeps the `_job.json` mtimes.

## Proposed later
- Optional cheaper verification on S3 (lengths only, or the S3 ETag), behind a fingerprinted setting, if re-hashing on reuse turns out costly at full DBLP scale.
- Reject a `scholarchain.code.version` that disagrees with a build-embedded identity, once build-engineer provides one (the F2 routing).
- Reconsider whether the initials-surname oversize fallback should be the normal path (A-01, entity-resolution-engineer).

## Job list / counters / reject reasons
- No job, counter or reject reason was added or changed.
- The reuse refusal is a driver failure (JobFailed), not a record reject.
- `_job.json` schema: the new trailing field `outputs: [{path: string, length: number, sha256: hex string}]`.

## Sample-run evidence
- The local LocalJobRunner runs in the suites above:
  - PipelineSuite, DblpChainSuite, LocalPipelineRunnerSuite, FallbackPipelineEndToEndSuite and JobLevelDblpAdversarialSuite produce deterministic outputs, with byte-identical part files and `_job.json` across output roots;
  - verified reuse works (JobLevelRerunSuite test 1, RunRecoverySuite).

## INTERFACE HANDOFF
- Files changed: listed above.
- Public API added, in `org.scholarchain.hadoop.common.JobRunner`:
  - `val OutputsField: String = "outputs"`
  - `final case class OutputFile(path: String, length: Long, sha256: String)`
  - `def outputManifest(fs: FileSystem, out: Path): List[OutputFile]`
- Unchanged signatures: `JobRunner.run`, `JobSpec` and `JobResult`.
- Changed behaviour of `JobRunner.run`: a completed directory is reused only after verification; otherwise it throws `JobFailed` with the message prefix "<jobName>: refusing to reuse".
- Default config: `scholarchain.blocking.keyFamilies=dblp-base-name,initials-surname`. This changes the config fingerprint, so every run ID changes compared with earlier output roots.
- Fixtures: none new. ReuseVerificationSuite uses TestSupport and datasets/samples/dblp/dblp-sample.xml.
- Thread-safety: only the driver runs it (single-threaded); there is no shared mutable state.
- Known limits: hashing on reuse reads all outputs; pre-Phase-11 output roots are refused; a hidden-path convention decides what is listed (segments starting with `_` or `.`).

TEST EVIDENCE
- command: sbt -batch "hadoop/testOnly org.scholarchain.hadoop.*" "contractTests/testOnly org.scholarchain.contracts.hadoop.* org.scholarchain.contracts.er.NameKeyAdversarialSuite"
- finished: 2026-11-16 19:48:04 local (hadoop part 19:38:05)
- per suite:
  - org.scholarchain.hadoop.BlockingDeterminismSuite run=3 passed=3 failed=0 skipped=0
  - org.scholarchain.hadoop.CandidateSuite run=11 passed=11 failed=0 skipped=0
  - org.scholarchain.hadoop.DblpChainSuite run=7 passed=7 failed=0 skipped=0
  - org.scholarchain.hadoop.DblpParsingSuite run=11 passed=11 failed=0 skipped=0
  - org.scholarchain.hadoop.LinkAndBlockSuite run=6 passed=6 failed=0 skipped=0
  - org.scholarchain.hadoop.NormalizerSuite run=6 passed=6 failed=0 skipped=0
  - org.scholarchain.hadoop.DblpExtractJobSuite run=7 passed=7 failed=0 skipped=0
  - org.scholarchain.hadoop.PipelineSuite run=4 passed=4 failed=0 skipped=0
  - org.scholarchain.hadoop.RunRecoverySuite run=1 passed=1 failed=0 skipped=0
  - org.scholarchain.hadoop.SettingsSuite run=5 passed=5 failed=0 skipped=0
  - org.scholarchain.hadoop.FallbackBlockingSuite run=17 passed=17 failed=0 skipped=0
  - org.scholarchain.hadoop.LocalPipelineRunnerSuite run=3 passed=3 failed=0 skipped=0
  - org.scholarchain.hadoop.PairsAndTeamsSuite run=8 passed=8 failed=0 skipped=0
  - org.scholarchain.hadoop.ReuseVerificationSuite run=6 passed=6 failed=0 skipped=0
  - org.scholarchain.hadoop.ScannerSuite run=14 passed=14 failed=0 skipped=0
  - org.scholarchain.contracts.hadoop.FallbackPipelineEndToEndSuite run=7 passed=7 failed=0 skipped=0
  - org.scholarchain.contracts.hadoop.JobLevelDblpAdversarialSuite run=6 passed=6 failed=0 skipped=0
  - org.scholarchain.contracts.hadoop.JobLevelBlockingSuite run=7 passed=1 failed=6 skipped=0 (m5 default; BLOCKED-ON test-engineer)
  - org.scholarchain.contracts.hadoop.JobLevelTeamScaleSuite run=4 passed=4 failed=0 skipped=0
  - org.scholarchain.contracts.hadoop.RecordFieldAdversarialSuite run=15 passed=15 failed=0 skipped=0
  - org.scholarchain.contracts.hadoop.UnicodeAdversarialSuite run=14 passed=14 failed=0 skipped=0
  - org.scholarchain.contracts.hadoop.JobLevelRerunSuite run=8 passed=7 failed=1 skipped=0 (the expected F2 flip)
  - org.scholarchain.contracts.hadoop.PairLimitAdversarialSuite run=10 passed=10 failed=0 skipped=0
  - org.scholarchain.contracts.hadoop.ScaleAndProvenanceAdversarialSuite run=20 passed=20 failed=0 skipped=0
  - org.scholarchain.contracts.hadoop.XmlBoundaryAdversarialSuite run=14 passed=14 failed=0 skipped=0
  - org.scholarchain.contracts.er.NameKeyAdversarialSuite run=10 passed=10 failed=0 skipped=0
- result line:
  - hadoop: `[info] Passed: Total 109, Failed 0, Errors 0, Passed 109`
  - contractTests: `[error] Failed: Total 115, Failed 7, Errors 0, Passed 108`
- expected-failure flips: Phase4-F2 (advisory 13). JobLevelRerunSuite "documented gap: same code.version string reuses a completed output without validation" now fails with "JobFailed: hadoop.j01-dblp-extract: refusing to reuse ...: output works/part-r-00000 has length 32, recorded 1270". It is a plain test, not `.fail`, so MUnit reports an ordinary failure.
- source changed after this run: no
