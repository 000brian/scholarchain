# repair-03-spark (Phase 11 step 3c, spark-engineer)

STATUS: COMPLETE
OWNER: spark-engineer
WORK ORDER: docs/agent-runs/phase-11/prompt.md (sha256 a0b59faeaeaae620ad87ce8b5dd1272efc1078d273e9a84baa426a9cd47e9ed1, not recomputed), step 3c, decision 8. Targeted tests only; not the wave closer.
FILES CREATED:
- /Users/brian/Developer/repos/scholarchain/modules/spark/src/main/scala/org/scholarchain/spark/RunFs.scala
- /Users/brian/Developer/repos/scholarchain/modules/spark/src/test/scala/org/scholarchain/spark/KnownGraphInputs.scala
- /Users/brian/Developer/repos/scholarchain/modules/spark/src/test/scala/org/scholarchain/spark/KnownGraphSuite.scala
- this report
FILES MODIFIED:
- /Users/brian/Developer/repos/scholarchain/modules/spark/conf/phase06.json (kcoreMaxIterations 30 -> 50)
- /Users/brian/Developer/repos/scholarchain/modules/spark/src/main/scala/org/scholarchain/spark/Pipeline.scala (B-02, F6 call, F9 part 2)
- /Users/brian/Developer/repos/scholarchain/modules/spark/src/main/scala/org/scholarchain/spark/Output.scala (F6, B-02 load manifests, F9 part 2, InputRefusedException)
- /Users/brian/Developer/repos/scholarchain/modules/spark/src/main/scala/org/scholarchain/spark/Inputs.scala (decodeDir on Hadoop paths)
- /Users/brian/Developer/repos/scholarchain/modules/spark/src/main/scala/org/scholarchain/spark/GraphAnalyticsJob.scala (F9 part 1; Hadoop paths in parseArgs)
- /Users/brian/Developer/repos/scholarchain/modules/spark/src/test/scala/org/scholarchain/spark/FixtureData.scala (complete ER run: manifest with outputs, then _SUCCESS)
- /Users/brian/Developer/repos/scholarchain/modules/spark/src/test/scala/org/scholarchain/spark/PipelineFixtureSuite.scala (8 new tests; r2 now runs through the EMR argument path with file:// URIs)
- /Users/brian/Developer/repos/scholarchain/modules/spark/src/test/scala/org/scholarchain/spark/JobConfigSuite.scala (expects the k-core cap of 50)
COMMANDS RUN: `sbt -batch "sparkAnalytics/Test/compile"` (2 times, both successful); development runs of `sbt -batch "sparkAnalytics/testOnly ..."` (dev1: compile error in my test, fixed; dev2: 1 failure that was a bug in my test, fixed; dev3: 1 failure, JobConfigSuite still expected the old k-core cap of 30, updated to the decided 50); final verification: ONE `sbt -batch "sparkAnalytics/testOnly <5 suites>"` (see TEST EVIDENCE). Read-only: `ls -R`, `grep`, `sed -n`, `wc -l`, `git status --short`, `git diff --stat`. The formatter hook ran `scalafmtOnly` after each write. No AWS or network.
TEST RESULTS: final run: Passed: Total 61, Failed 0, Errors 0, Passed 61 (KnownGraphSuite 15, PipelineFixtureSuite 28, JsonlExportSuite 9, JobConfigSuite 3, AlgorithmsSuite 6). `grep -l -E 'failures="[1-9]|errors="[1-9]' modules/spark/target/test-reports/TEST-*.xml` printed nothing.
VALIDATION RESULTS: every value on the case sheet matched the module, so there are no SP-nn cases and no `.fail` marks. The empty run (S1a) completes with empty tables and a run manifest. r1 (local File arguments) and r2 (file:// URIs through GraphAnalyticsJob.runWith) give the same run ID and byte-identical table contents (determinism test).
ASSUMPTIONS:
- B-02's required ER layout and files are taken from ErRun.scala:698-714 and Report.scala:179-227: `_manifest.json` (format `scholarchain.er.run-manifest`, `outputs[]` with path/bytes/sha256) is written, then `_SUCCESS`. Only outputs under `decisions/identity_assignment/` are checked against the files Spark reads.
- I read "refuse reuse when the recorded --code-version differs" for the Pipeline reuse path only. JsonlExport keeps its earlier check, without control files (see RISKS and Proposed later).
- `--config` stays a local driver file. Only the three data locations moved to Hadoop paths.
RISKS:
- Behaviour change: Pipeline now refuses (InputRefusedException, no output root created) any `--identity-assignments` that is not `<erRun>/decisions/identity_assignment` of a complete ER run. Real ER runs meet this. Hand-copied folders do not.
- Run directories written before this change have no `controlFiles`, so Pipeline will not reuse them. JsonlExport still accepts them, so the Phase 7 export and the attempt-07 command are not affected.
- The k-core cap change changes the config fingerprint, so new real runs get new run IDs.
- The load manifests have a new field `policyVersions`. JsonlExport ignores unknown fields. storage-engineer should confirm that its readers do too (S3KeyLayout mentions load manifests).
- F9 part 2 is tested with file:// only, as ordered. s3:// on EMR is not exercised. The free-disk guard is meaningful only for file: roots (object stores report a nominal capacity).
- The "fails without the fix" proofs for B-02, F6 and F9 are argued from the pre-fix code (I did not re-run old code). Only the k-core proof was observed: dev3 showed the old expectation of 30 failing against the new config.
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: wave closer's full `sbt check`; step 4 (test-engineer rerun); step 5 (code-reviewer re-check of B-02, the P1 items and the case-sheet result).

## Per-item status and proof

| ID | Status | Fix | Test (and why it fails without the fix) |
|---|---|---|---|
| B-02 (major) | fixed | `Pipeline.checkErRun` (called before the run ID is computed, so nothing is written on refusal): the path must be `<erRun>/decisions/identity_assignment`. The ER run must have `_SUCCESS` and `_manifest.json` with format `scholarchain.er.run-manifest`, a well-formed `runId`, and non-blank `jobName`, `codeVersion` and `policyVersion`. The manifest `outputs` under `decisions/identity_assignment/` must equal the files read: no missing, changed or unlisted file, sizes and sha256 compared. On failure it throws `InputRefusedException`. The run manifest's `inputs.identityAssignments` fields are now required strings, plus a `verified` note. A new `policyVersions {graph, entityResolution}` object is written to the run manifest, the run report and every load manifest. Both upstream inputs are now handled alike (Hadoop `_job.json` was already required). | PipelineFixtureSuite "B-02: identity assignments outside …", "B-02: an ER run without _manifest.json or without _SUCCESS …", "B-02: ER manifest fields are required and its output hashes must match …" (no policyVersion, wrong format, empty outputs, changed part file, extra unlisted file), "B-02: the ER run and policy version are recorded …". Before the fix, erManifest was optional and nothing was checked, so all refusal cases ran to success and `intercept` failed. There was no `policyVersions` key anywhere. |
| P1 known-graph suite | done | New `KnownGraphSuite` and `KnownGraphInputs`. All cases except S1a run as disjoint subgraphs of one pipeline run. S1a is its own run. | 15 tests, all green. See the case-sheet table. |
| P1 Phase 6 F6 | fixed | `Manifests.writeVerified` hashes `_run_report.json` and `_load_manifests/{neptune,dynamodb,s3vectors,s3}.json` into the run manifest as `controlFiles` (path, bytes, sha256), written last as before. `Manifests.verifyExisting(..., expected, requireControlFiles)`: Pipeline reuse requires `jobName`, `codeVersion`, `inputFingerprint` and `configFingerprint` to equal the current values. It also requires `controlFiles` to be present, complete and matching, and no unlisted file in `_load_manifests/`. Otherwise it throws `RunRefusedException`. | "F6: the run manifest hashes …" (an unchanged copy is still reused), "F6: reuse is refused when the run report or a load manifest changed" (report, s3.json, extra opensearch.json), "F6: reuse is refused when the recorded code version differs or control hashes are absent". Before the fix, `_`-files and codeVersion were never read on reuse, so `Pipeline.run` returned reused=true and `intercept` failed. `controlFiles` did not exist. |
| P1 k-core cap 50 | done | `modules/spark/conf/phase06.json` kcoreMaxIterations 50 | JobConfigSuite "the shipped Phase 6 configuration …" now expects 50. Observed in dev3: with the old expectation (30) it failed. |
| P1 Phase 6 F9 part 1 | fixed | `GraphAnalyticsJob.RequiredSqlConf` (`spark.sql.autoBroadcastJoinThreshold=-1`, `spark.sql.adaptive.autoBroadcastJoinThreshold=-1`). `main` sets them on the builder. `runWith` (main's path) applies them with `configure(spark)` before parsing. | "F9 part 1: the EMR entry point turns automatic broadcast joins off": it sets both values to 10485760, calls `runWith(spark, Nil)` and asserts -1. Before the fix, runWith set nothing, so the assertion failed (and RequiredSqlConf did not exist). |
| P1 Phase 6 F9 part 2 | done (green; not reverted) | New `RunFs` (Hadoop FileSystem; raw local FS for file:, so no .crc files). `PipelineArgs` holds Hadoop `Path`s, with a companion `apply(File, File, File, …)` for local callers. Pipeline handles the input listing and hashes, the ER checks, `_job.json`, the output root, the run-dir existence and mkdirs, the checkpoint and staging dirs, decode, the OutputWriter writes and verify, the integrity read, the manifests and the reuse check, all through `RunFs`. `PipelineResult(runId, runPath: Path, …)` has `def runDir: File` for local runs only. `GraphAnalyticsJob.parseArgs` builds Hadoop `Path`s. | PipelineFixtureSuite r2 now runs through `GraphAnalyticsJob.runWith` with `file://` URIs. The determinism test checks the same run ID and identical tables as r1 (File arguments), and the reuse tests read r2. Before the fix, parseArgs made `new File("file:/…")`, a relative path that does not exist, so `require(work isDirectory)` failed and beforeAll failed. |
| B-08 (minor) | recorded, not fixed | Checkpoint and staging dirs are still never removed. They are now Hadoop Paths, so a cleanup after success would be one `fs.delete` per dir. | – |
| B-10 (minor) | recorded, not fixed | `GraphAnalyticsJob.parseArgs` has no unknown-argument check, and `--min-free-disk-gb` and `--max-stage-minutes` throw NumberFormatException. | – |

## Case-sheet result table (KnownGraphSuite)

| Case | Result |
|---|---|
| S1a empty run | pass: the run completes; every window's coauthor_edge, breadth metrics, components and communities table exists with 0 rows; canonical_researcher has 0 rows; the run manifest is written |
| S1b works outside the window | pass: nodes A and B exist; no metric rows and no coauthor edge in any window |
| S2 single researcher (P,C,T,A,M,W,coreness,component) | pass |
| S3 two disconnected pairs | pass (component IDs = smallest member ID; the two components differ) |
| S4 star | pass |
| S5 path | pass |
| S6 4-clique (AB weight 2) | pass |
| S7 two triangles + bridge, full / w2015-2017 / w2018-2020 (incl. "no rows") | pass |
| S7w bridge weight 2 (and "as S7" rows) | pass |
| Hops (19 rows, incl. none and 0) | pass (Paths.shortestPaths on the run's window edges) |
| Bridges under the stated map (20 rows, incl. S2 absent) | pass (Analytics.bridges fed the map; for S2 the pipeline's bridge row has no PC and cross = connected = 0) |
| I1 self pair | pass: erSelfPairsDropped = 1; one A-B edge [w1]; A P1 C1 T1 A1 M1 W1 core 1; M evidence (w1, 2, 3); AUTHORED A-w1 carries mentions [m0, m1] |
| I2 repeated pair and duplicate pair row | pass: weight 3, count 3, 2016-2019, works [w1, w2, w3]; w2018-2020: weight 1, 2019, [w3] |
| T1 / T2 / T3 / T30 | pass: 0 / 1 / 3 / 435 edges; C = T = M = n−1; coreness n−1; T30 component = min ID, size 30; M evidence (w1, 30, 30) |
| T30 "no oversize flag (default threshold 100)" | sheet dispute: sparkAnalytics has no oversize flag or threshold (no match in modules/spark/src/main), so there is nothing to assert. The flag is probably a Hadoop or ER concept. Step 5 may rule. |
| I3 unresolved mention | pass: exactly 2 pair_mention_unassigned (global set check); "I3 Unknown" in unresolved_mention; one edge; A and B P1 C1 T1 M1 W1; M evidence (w1, 2, 3); 2 researcher nodes on w1 |
| I4 CONFLICT endpoint (Phase 6 F3) | pass: edges kept, minIdentityConfidence 0.5 and 0.9; outcomes conflict/accepted/accepted; identityConfidence 0.5/0.5/0.9; A P1 C1 W1, B P2 C2 T2 W2, C P1 C1 W1 |
| F3a orphan assignment + pair | pass: assignment_mention_missing 1; pair_not_in_team 1 (A\|Z, work w1); endpoint_missing and endpoint_inactive 1 each in full-2015-2025 and w2015-2017 (subject Z); A P2 C2 T2 W3 |
| F3b team size | pass: team_size_mismatch 1, detail "teamSize 3 vs mentions 2" |
| F3c pair year | pass: pair_year_mismatch 1; the edge keeps 2016 (weight 2, 2016-2019) |
| F3d missing pair row | pass: team_pair_without_candidate 1 (C\|D, work w3); no C-D edge; C and D P1 C0 T1 M1 W0, coreness 0, singleton |
| F3e base S3 with no plant | pass: the run's whole violation set is exactly the planted set above, so S3 and every other case add none |
| K1 / K2 / K3 communities | pass (same/different as stated; K3 C is alone, size 1, label = own ID) |

SP-nn: none.

## Metric definitions, approximations and limits (unchanged by this pass)
P, C, T, A, M, W, coreness, components, LPA communities and bridges are as written in the case sheet's "Definitions used". These are still separate metrics, each with its window. The k-core iteration cap is now 50 (it is still a cap: convergence is reported per window in the run report).

## Output invariants checked
- Every planted integrity violation appears once, and there are no others.
- Edge endpoint and weight rules: weight counts distinct works, duplicate pair rows add nothing, self pairs are dropped and counted.
- CONFLICT confidence is carried along edges.
- The run manifest records the ER run ID, code version and policy version as strings (never null).
- Control files are hashed into the run manifest.
- File and file:// URI arguments give the same run ID and identical tables.

## Proposed later
- JsonlExport: also check `controlFiles` and code version when the source run has them (today only Pipeline reuse does).
- Supply the git commit as `--code-version` from the Phase 13 scripts (infra-engineer).
- Read `--config` through the Hadoop FS for cluster-mode drivers. JsonlExport still uses java.io.File (not S3-capable).
- B-08: delete `_checkpoint/run=…` and `_staging/run=…` after a verified run.
- B-10: unknown-argument and number checks in GraphAnalyticsJob.parseArgs.
- An s3:// smoke run on EMR (Phase 13/14).
- Make the free-disk guard scheme-aware.

## INTERFACE HANDOFF
- Files changed: listed above.
- Public types and signatures (package org.scholarchain.spark):
  - `final case class PipelineArgs(hadoopExport: org.apache.hadoop.fs.Path, identityAssignments: Path, outRoot: Path, config: SparkJobConfig, codeVersion: String, minFreeDiskBytes: Long, maxStageMillis: Long)`; `object PipelineArgs { def apply(hadoopExport: java.io.File, identityAssignments: File, outRoot: File, config, codeVersion, minFreeDiskBytes, maxStageMillis): PipelineArgs }`
  - `final case class PipelineResult(runId: String, runPath: Path, reused: Boolean, report: ujson.Value) { def runDir: java.io.File /* file: only */ }`
  - `Pipeline.inputListing(io: RunFs, hadoopExport: Path, identityAssignments: Path)`; `Pipeline.checkErRun(assignmentsLeaf, assignmentsParentLeaf, describe, readErFile: String => Option[String], assignmentFiles: Seq[FileEntry]): ujson.Value`; constants `ErAssignmentsRel`, `ErManifestName`, `ErManifestFormat`, `SuccessMarker`
  - `final class InputRefusedException(msg) extends IllegalArgumentException`
  - `final class RunFs(conf: org.apache.hadoop.conf.Configuration)` (`qualify`, `exists`, `isFile`, `isDirectory`, `length`, `mkdirs`, `readString`, `readIfFile`, `writeString`, `sha256`, `rel`, `listFiles`, `listData`, `entries`, `usableSpace`); `object RunFs { def local; def pathOf(f: File): Path; def display(p: Path): String }`
  - `OutputWriter(spark, io: RunFs, runDir: Path, defaultPartitions)` plus the old `(spark, runDir: File, defaultPartitions)` constructor
  - `Manifests.LoadTargets`, `Manifests.ControlFiles`; `loadManifests(runId, tables, policyVersions: Option[ujson.Value] = None)`; `writeVerified(io, runDir: Path, …)` plus the old File overload; `verifyExisting(io, runDir: Path, runId, expected: Seq[(String, String)], requireControlFiles: Boolean)` plus File overloads `(runDir, runId)` and `(runDir, runId, expected, requireControlFiles)`
  - `Inputs.decodeDir(spark, io: RunFs, dir: Path, decode)`
  - `GraphAnalyticsJob.RequiredSqlConf`, `GraphAnalyticsJob.configure(spark)`. `runWith` now applies them.
- Output format additions: the run manifest gains `policyVersions`, `controlFiles` and `inputs.identityAssignments.verified`. The run report gains `policyVersions`. Load manifests gain `policyVersions`. Run manifest `inputs.*.path` is still the plain absolute path for local runs (URI otherwise).
- Fixtures: `FixtureData.completeErRun(er: File, runId: RunId = erRun, policyVersion = "er-v1")`; `KnownGraphInputs.{KWork, Identity, OrphanPlant, Spec, write(root, spec)}` (test scope, package org.scholarchain.spark).
- Thread-safety: RunFs and the Pipeline are driver-side and single-threaded, as before.
- Known limits: s3:// is not exercised locally. The free-disk guard is meaningful for file: only. `PipelineResult.runDir` throws for non-file runs.

## TEST EVIDENCE
- command: `sbt -batch "sparkAnalytics/testOnly org.scholarchain.spark.KnownGraphSuite org.scholarchain.spark.PipelineFixtureSuite org.scholarchain.spark.JsonlExportSuite org.scholarchain.spark.JobConfigSuite org.scholarchain.spark.AlgorithmsSuite"` (log: /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/858dada8-c8dd-43c7-b05d-961e323b855c/scratchpad/step3c-targeted.log)
- finished: Nov 16, 2026, 8:38:55 PM (local)
- per suite:
  - org.scholarchain.spark.KnownGraphSuite run=15 passed=15 failed=0 skipped=0
  - org.scholarchain.spark.PipelineFixtureSuite run=28 passed=28 failed=0 skipped=0
  - org.scholarchain.spark.JsonlExportSuite run=9 passed=9 failed=0 skipped=0
  - org.scholarchain.spark.JobConfigSuite run=3 passed=3 failed=0 skipped=0
  - org.scholarchain.spark.AlgorithmsSuite run=6 passed=6 failed=0 skipped=0
- result line: `[info] Passed: Total 61, Failed 0, Errors 0, Passed 61` / `[success] Total time: 248 s (0:04:08.0), completed Nov 16, 2026, 8:38:55 PM`
- expected-failure flips: none
- source changed after this run: no
