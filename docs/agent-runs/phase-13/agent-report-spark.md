# Phase 13 step 5 report: spark-engineer (decision 7c and the decision 7 entry-point rules; wave B closer)

STATUS: COMPLETE
OWNER: spark-engineer
WORK ORDER: docs/agent-runs/phase-13/prompt.md (sha256 60c943dccc86a6dc58ec074b95bbd63f04e2adca2f7a1c9a9abad266f28d36a7), session 2 step 5, decision 7 (shared rules) and 7c; wave B closer.
FILES CREATED:
- modules/spark/src/main/scala/org/scholarchain/spark/BuildIdentity.scala
- modules/spark/src/test/scala/org/scholarchain/spark/JsonlExportArgsSuite.scala
- modules/spark/src/test/scala/org/scholarchain/spark/MockStoreFileSystem.scala
FILES MODIFIED:
- modules/spark/src/main/scala/org/scholarchain/spark/JsonlExport.scala
- modules/spark/src/main/scala/org/scholarchain/spark/RunFs.scala (new `writeNewString`, :58)
- modules/spark/src/test/scala/org/scholarchain/spark/JsonlExportSuite.scala
COMMANDS RUN:
- sbt -batch "sparkAnalytics/Test/compile" (success)
- sbt -batch "sparkAnalytics/testOnly org.scholarchain.spark.JsonlExportArgsSuite org.scholarchain.spark.JsonlExportSuite" (run 1: 2 failures, my tests wrongly assumed the build-info resource is absent in module tests; fixed; run 2 and run 3 after `sbt fmt`: passed)
- sbt fmt
- sbt check (passed)
- sbt -batch packageCheck (passed)
- read-only: git status, ls, wc, grep (words not on the guard list), shasum
TEST RESULTS:
- targeted (final, after fmt): Passed: Total 20, Failed 0, Errors 0, Passed 20
- full sbt check: Updated .claude/.last-test-run (module_tests=2062, contract_tests=398)
VALIDATION RESULTS: packageCheck: ok (full class list, incl. org.scholarchain.spark.JsonlExport and GraphAnalyticsJob in the spark jar, StorageLoadMain and RagIngestJob in the services jar)
ASSUMPTIONS:
- `--code-version` stays optional (work order 7c); when absent, `codeVersion` is JSON null in the report.
- The export manifest (`_export_manifest.json`) is unchanged (storage's S3ExportSource contract); code version and build identity go only into `_export_report.json`.
- Plain paths resolve against the default file system (same rule as GraphAnalyticsJob): local in tests, HDFS on EMR. Scripts and the state machine should pass full `s3://` URIs.
- The build-info resource is present on the module test class path too (the build generator feeds it); the tests accept `resource` or `missing` and require the report to equal `BuildIdentity.load()`.
RISKS:
- Real `s3://` I/O is untested until Phase 14 (decision 7). Covered locally by a test-only file system with its own scheme (`mockstore://`), which fails on any java.io.File or `file:` shortcut.
- On S3, `rename` of part files is copy plus delete (EMRFS); export cost grows with file count (at most 32 per table).
- Masking covers what the job prints and the entry point's failure message (bucket names, ARN account IDs). Spark/EMRFS's own INFO logs and executor stack traces may still name the bucket; they are outside the job's code.
- GraphAnalyticsJob (unchanged in this step) still accepts unknown options silently and has no option constant; it is not a "new or changed" entry point here. Listed under Proposed later.
- `sbt fmt` reported formatting passes over storage and rag sources (other owners); `sbt check`'s format check passed afterwards. I did not edit those files.
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: R-2 (code-reviewer, steps 2-5); step 6 infra-engineer reads INTERFACE HANDOFF for the EMR export step and IAM.

## Requirements (one line each)
- 7c output through Hadoop FileSystem (no java.io.File on the output or source path; scan and sha256 via FileSystem; manifest last): done, JsonlExport.scala:623-730 (run), :418 (scan via `io.fs(f).open`), :501-523 (finalize/list via RunFs), :726-728 (report, then manifest, both create-new).
- Local paths keep working: done, File overloads JsonlExport.scala:595-613; all pre-existing JsonlExportSuite tests pass.
- Test: file:// URI vs plain path identical bytes and manifests: done, JsonlExportSuite.scala:247.
- Test: existing target refused (full export and an empty run dir, left untouched; also through mockstore): done, JsonlExportSuite.scala:163, :371, :294.
- Test: nothing written when validation fails (stale schema version, changed source file, invalid code version, unknown option, output inside the source run): done, JsonlExportSuite.scala:391. Fix behind it: codec checks now run before the first mkdirs (JsonlExport.scala:656 vs :670); the stale-schema case failed before this change.
- Extra proof of FileSystem-only I/O: done, JsonlExportSuite.scala:294 with MockStoreFileSystem.scala.
- 7 `--code-version` (new, optional) and build identity in `_export_report.json`: done, JsonlExport.scala:237, :711-712; test JsonlExportSuite.scala:338.
- 7 options in one public constant + exact-contents test; unknown option refused: done, JsonlExport.scala:234 (Required), :237 (Optional), parser :255; tests JsonlExportArgsSuite.scala:17, :37.
- 7 no secret, bucket or account printed: done, `show` JsonlExport.scala:302, `maskText` :314, entry point rethrows masked without cause :744-760 (JsonlExportFailedException :30); tests JsonlExportArgsSuite.scala:54, :65.
- spark-submit class `org.scholarchain.spark.JsonlExport` now has `main` (static forwarder) :744; `JsonlExportJob` kept as alias :763-771.
- Wave closer: full sbt check passed, then packageCheck ok.

## Metric definitions / output invariants checked
- No metric changed. Export invariants checked by tests: per-table line count equals load-manifest count; every line decodes and re-encodes identically; manifest lists every data file with bytes and sha256 read through the FileSystem; manifest bytes identical across plain path, file:// URI and mockstore; a target `run=<hex>` that exists is never written into; no output directory is created when any check fails.

## Approximations and limits
- None new. Export stays at most 32 files per table, hash-partitioned and sorted by line.

## Proposed later
- `--source-attempt-root DIR` on JsonlExport: resolve the single `run=<hex>` with a `_run_manifest.json` under the graph job's `--out-root`, so the state machine need not know the hex (see handoff).
- Option constant and unknown-option refusal for GraphAnalyticsJob (same rule as JsonlExport).

## INTERFACE HANDOFF
- Class: `org.scholarchain.spark.JsonlExport` (spark assembly `modules/spark/target/scala-2.13/scholarchain-spark-analytics-assembly-0.1.0-SNAPSHOT.jar`).
- Option constant: modules/spark/src/main/scala/org/scholarchain/spark/JsonlExport.scala:234 `Required = List("source-run", "out-root")`, :237 `Optional = List("records-per-file", "code-version")`. Names without `--`; on the command line each is `--name value`. Odd counts, repeats, unknown names and a `--code-version` not matching `[A-Za-z0-9][A-Za-z0-9._+-]{0,99}` are refused before a Spark session starts.
- Example (EMR step, export after a graph run; placeholders only):
  `spark-submit --deploy-mode cluster --class org.scholarchain.spark.JsonlExport s3://{data-bucket}/artifacts/SHA40/scholarchain-spark-analytics-assembly-0.1.0-SNAPSHOT.jar --source-run s3://{data-bucket}/graph/ATTEMPT/run=HEX --out-root s3://{data-bucket}/exports/ATTEMPT --code-version SHA40`
  Output: `s3://{data-bucket}/exports/ATTEMPT/run=HEX/` (Phase 7 layout, unchanged: table folders with `part-NNNNN.jsonl`, `_export_report.json`, then `_export_manifest.json` last). Use a new `--out-root` per attempt: an existing `run=HEX` under it is refused.
- How the source `run=<hex>` is known: GraphAnalyticsJob writes to `<its --out-root>/run=<hex>` (Pipeline.scala:285-286), where hex comes from the run ID computed from the input content, config and code version during the run; the state machine cannot know it beforehand. The job prints `[scholarchain] run <runId> at <runPath>` to the step's stdout. So, per decision 8.3's fallback, the state-machine field should be the full source-run path (or see Proposed later, `--source-attempt-root`). `--source-run` must end in `run=<hex>` matching the manifests' run ID, or the export is refused.
- `_export_report.json` keys: `format` ("scholarchain.spark.jsonl-export-report"), `formatVersion` (1), `runId`, `codeVersion` (string or null), `buildIdentity` {`buildCommit`, `buildDirty`, `module`, `version`, `status` = resource | missing | malformed: ...}, `wallMillis`, `sourceVerifyMillis`, `tables` [{`table`, `records`, `files`, `bytes`, `wallMillis`}], `totals`. No paths, bucket names or account IDs.
- IAM needs of the EMR instance role (EMRFS): source graph-run prefix: `s3:GetObject`, `s3:ListBucket` (prefix-scoped). Export prefix: `s3:PutObject`, `s3:GetObject`, `s3:DeleteObject` (part-file rename = copy + delete; committer `_temporary` cleanup), `s3:ListBucket` (prefix-scoped), `s3:AbortMultipartUpload`, `s3:ListMultipartUploadParts`. If the bucket uses SSE-KMS: `kms:GenerateDataKey` and `kms:Decrypt` on the data key. The jar read from `artifacts/`: `s3:GetObject`.
- Build identity: resource `scholarchain-build-info.properties`, read by `org.scholarchain.spark.BuildIdentity.load()` (BuildIdentity.scala), same rules as storage's.
- Thread-safety: driver-only file access (RunFs); one export per session at a time. Limits: unchanged (32 files per table, 250000 records per file default).

VALIDATION EVIDENCE
- targeted: sbt -batch "sparkAnalytics/testOnly org.scholarchain.spark.JsonlExportArgsSuite org.scholarchain.spark.JsonlExportSuite" -> Passed: Total 20, Failed 0, Errors 0, Passed 20
- full: sbt check -> "Updated /Users/brian/Developer/repos/scholarchain/.claude/.last-test-run (module_tests=2062, contract_tests=398)"
- package (step 1, wave closers): sbt -batch packageCheck -> "packageCheck: ok"
- finished: after the last command above
- files changed after these runs: no
