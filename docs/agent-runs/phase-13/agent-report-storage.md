# agent-report-storage (phase 13, session 2, step 3)

STATUS: COMPLETE
OWNER: storage-engineer
WORK ORDER: docs/agent-runs/phase-13/prompt.md (sha256 60c943dccc86a6dc58ec074b95bbd63f04e2adca2f7a1c9a9abad266f28d36a7), decisions 7 and 7a (step 3, wave B, not the wave closer)
FILES CREATED:
- modules/storage/src/main/scala/org/scholarchain/storage/load/StorageLoadMain.scala
- modules/storage/src/main/scala/org/scholarchain/storage/load/LoadStores.scala
- modules/storage/src/main/scala/org/scholarchain/storage/load/GraphRun.scala
- modules/storage/src/main/scala/org/scholarchain/storage/load/BuildIdentity.scala
- modules/storage/src/main/scala/org/scholarchain/storage/load/Masking.scala
- modules/storage/src/test/scala/org/scholarchain/storage/load/LoadFixtures.scala
- modules/storage/src/test/scala/org/scholarchain/storage/load/S3ExportSourceSuite.scala
- modules/storage/src/test/scala/org/scholarchain/storage/load/StorageLoadMainArgsSuite.scala
- modules/storage/src/test/scala/org/scholarchain/storage/load/StorageLoadMainLocalSuite.scala
- modules/storage/src/test/scala/org/scholarchain/storage/load/BuildIdentitySuite.scala
FILES MODIFIED:
- modules/storage/src/main/scala/org/scholarchain/storage/source/ExportSource.scala (S3ExportSource replaces the unused, untested ObjectStoreSource; shared presence/sha256 checks; ExportSources.open)
- modules/storage/src/main/scala/org/scholarchain/storage/aws/AwsStorageEnvironment.scala (additive: `bulkLoader`)
COMMANDS RUN:
- shasum -a 256 docs/agent-runs/phase-13/prompt.md (matches the work order); read-only git status/ls-files; grep/head/wc/ls on repository files
- sbt -batch "storage/Test/compile" -> success
- sbt -batch "storage/testOnly org.scholarchain.storage.load.* org.scholarchain.storage.ExportSourceSuite" -> 38 passed (one compile error fixed before: anonymous subclass of a final class in a test)
- sbt -batch "storage/testOnly org.scholarchain.storage.*" -> Passed: Total 232, Failed 0, Errors 0, Passed 232, Ignored 1 (AwsAdaptersAwsSuite, RequiresAws, unrun)
- sbt -batch "rag/testOnly org.scholarchain.rag.RagIngestJobSuite org.scholarchain.rag.RetrievalSmokeSuite org.scholarchain.rag.runtime.RuntimeServiceSuite org.scholarchain.rag.runtime.QueryWorkflowSuite" -> Passed: Total 59, Failed 0, Errors 0, Passed 59 (direct users of ExportSource, LocalDirectorySource, AwsStorageEnvironment)
- sbt -batch "services/Test/compile" -> success
- aws-docs MCP: Neptune loader feed codes page, Neptune data-plane IAM actions page (read only)
TEST RESULTS: storage 232 passed, 0 failed, 1 ignored (RequiresAws); rag 59 passed, 0 failed. New: BuildIdentitySuite 3, StorageLoadMainArgsSuite 9, S3ExportSourceSuite 10, StorageLoadMainLocalSuite 10.
VALIDATION RESULTS: no live call; no runMain; RequiresAws suites unrun; no file outside modules/storage/** changed. `sbt fmt` not run as a separate command: the PostToolUse formatter formatted every written Scala file (it reported two syntax errors, `export` is a Scala 3 keyword, both fixed and reformatted).
ASSUMPTIONS:
- The Spark export (step 5) is written under the data lake's graph/ prefix (for example `s3://{data-bucket}/graph/exports/run=<hex>`), where the ops role already reads; see IAM below if not.
- The loader IAM role ARN (`iamRoleArn` of a load request) is passed as `--loader-role-arn` from the Terraform output "Neptune bulk-loader role" (infra/terraform/environments/course/outputs.tf:127); no config key holds it (core config is domain-engineer's).
- Storage run IDs keep `StorageVersion.Code` (storage/1) as their code input, so keys stay stable across build commits; `--code-version` is recorded, not used in keys (same choice as rag's CodeVersion constant).
RISKS:
- IAM for infra-engineer (step 6): EXTRA ACTION NEEDED: `s3:PutObject` on `${data_bucket_arn}/graph/*` for the ops task role (neptune-csv-build uploads CSVs and `_run_manifest.json`; conditional `If-None-Match: *` puts need only PutObject). CONDITIONAL: if the export root is not under graph/, add `s3:GetObject` on `${data_bucket_arn}/<that prefix>/*` and `s3:ListBucket` with an `s3:prefix` condition for it (without ListBucket a missing object is a 403, reported as a store error instead of "missing export file"). NOT NEEDED: `dynamodb:BatchWriteItem` (the loader uses GetItem, conditional PutItem and Query only, already in TablesLoad); Neptune `connect`, `StartLoaderJob`, `GetLoaderJobStatus` (already in NeptuneBulkLoad). `iam:PassRole` for StartLoaderJob: not listed on the Neptune data-plane actions page I read; unverified.
- Network (infra, unverified): ops task security group -> Neptune port 8182; Neptune loader role reads graph/ through the S3 gateway endpoint (RK1, decision 8 item 4).
- One writer (Phase 7 m3, phase-14): AWS mode refuses `--parallelism` > 1 inside one process; two ops tasks started at once are not prevented by the code (the script's one-task rule is the guard). neptune-bulk-start refuses a graph run already registered, but has a check-then-start window; a lost race is reported as a failure after both starts.
- dynamodb-load does not register Neptune CSV artifacts in ArtifactRegistry (passes none); proposed later.
- Run reports go to `--out/run-report.json` and one stdout line (CloudWatch logs of the ops task); no S3 copy (would need a reports/ write grant); proposed later.
- neptune-csv-build holds one CSV file in memory per upload (bounded by `--rows-per-file`, default 500000 rows, gzip) and needs >= 2 GiB free under `--out` (ops ephemeral storage is 30 GiB).
MANUAL ACTION REQUIRED: none in this step.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: step 4 (rag-engineer) consumes the export source; R-2 review; step 6 (infra) IAM above; step 5 is the wave closer (sbt check, packageCheck now finds StorageLoadMain).

## Requirements (one line each)
- 7 `--code-version` recorded with the build identity: done, StorageLoadMain.scala:310-311, BuildIdentity.scala:33 (missing resource = `unknown`/`missing`, :56). runtime: untested.
- 7 no secret, bucket or account printed/written: done, Masking.scala:9 applied to report and every printed line (StorageLoadMain.scala:326 and the end of runChecked); tests StorageLoadMainLocalSuite assertMasked (csv-build, bulk-start, dynamodb-load, aws mode with an env bucket).
- 7 option names in one public constant + exact-contents test + unknown refused: done, StorageLoadMain.scala:67 (Options), :87 (Exit), :121 (parse); StorageLoadMainArgsSuite.scala:15, :78.
- 7a S3 export source through ObjectStore, same checks as LocalDirectorySource: done, ExportSource.scala:285 (S3ExportSource), :306 (fromUri), :219 (checkPresence), :238 (verifyAll), :196 (missing manifest), :323 (ExportSources.open); S3ExportSourceSuite.scala:57 (missing manifest), :65 (sha256 mismatch), :104 (missing file), :88 (size).
- 7a StorageLoadMain (exact name) subcommands: done, dynamodb-load :411, neptune-csv-build :434 (to graph/ prefix), neptune-bulk-start :538, neptune-bulk-status :629. runtime: untested.
- 7a `--stores aws` from file + SCHOLARCHAIN_CFG_*: done, :260; StorageLoadMainLocalSuite.scala:339.
- 7a local in-memory mode: done, LoadStores.scala:38; end-to-end tests StorageLoadMainLocalSuite.scala:84-304.
- 7a AWS mode refuses more than one writer and says why: done, StorageLoadMain.scala:207; StorageLoadMainArgsSuite.scala:103.
- 7a IAM needs listed: done (RISKS, INTERFACE HANDOFF).

## Per-store role and key design
- S3 graph zone: Neptune CSVs at `<graph>/dataset=<d>/snapshot=<s>/run=<hex>/neptune/{nodes,edges}/<Type>/<file>.csv.gz`, run manifest `_run_manifest.json` last. run = StorageVersion.runId("storage.neptune-csv", "sha256:" + export manifest sha256, config fingerprint).
- DynamoDB: DynamoLoader items (unchanged design); run = runId("storage.dynamodb-load", same inputs); RunLedger claim + summary per pass; ArtifactRegistry `neptune-load` artifact keyed by the graph run key holds nodesLoadId/edgesLoadId.
- Neptune: bulk loader only (nodes job, then edges job depending on it; failOnError, queueRequest, MEDIUM parallelism, from BulkLoadRequests).

## Idempotency evidence
- neptune-csv-build rerun: 0 created, all objects and the run manifest already present (StorageLoadMainLocalSuite.scala:84).
- dynamodb-load `--passes 2`: pass 2 dataChanges 0 (StorageLoadMainLocalSuite.scala:264); s3:// and local-directory exports give the same run ID and item counts (:288).
- neptune-bulk-start twice on one graph run: second refused with the registered load IDs, no third job started (:116).

## Bounded load results (local fixture only; no AWS)
- Synthetic export (4 researchers, 3 works): all four subcommands complete in local mode; whole StorageLoadMainLocalSuite 0.82 s. Bounds: status wait <= 3600 s (poll 15 s), passes <= 2, parallelism 1 on AWS (<= 64 local), rows-per-file 1000..5000000, ranking-top-k 1..500, free disk >= 2 GiB for csv-build.

## Proposed later
- dynamodb-load `--graph-run` to register the Neptune CSV objects in ArtifactRegistry (today none are registered by this entry point).
- Copy each run report to the reports/ zone (needs an ops grant on reports/).
- A DynamoDB-based single-writer lock across processes (Phase 7 m3, phase-14).

## INTERFACE HANDOFF
- Class: `org.scholarchain.storage.load.StorageLoadMain` (object with `main`), run as `java -cp /opt/scholarchain/app.jar org.scholarchain.storage.load.StorageLoadMain SUBCOMMAND --name value ...`.
- Option constant: `StorageLoadMain.Options` at modules/storage/src/main/scala/org/scholarchain/storage/load/StorageLoadMain.scala:67 (names without `--`; required then optional per subcommand); exit codes `StorageLoadMain.Exit` at :87: 0 completed, 1 failed, 2 refused, 3 load not finished (status only). Scripts are written from the constant, not from this report.
- Output: `<out>/run-report.json` (format `scholarchain.storage.load-report` v1: subcommand, codeVersion, build{buildCommit,buildDirty,module,version,status}, stores, configFile, configFingerprint, status, exitCode, plus `graphRun`, `bulkLoad`, `bulkLoadStatus` or `dynamodb`); stdout lines `[storage-load] SUBCOMMAND STATUS` and `[storage-load] report {json}`, all masked (`s3://{data-bucket}/KEY`, `{account}`). neptune-csv-build's `graphRun.graphRun` is the logical `--graph-run` of the next step.
- Example command lines (placeholders: {data-bucket} resolved by the script, SHA40 the build commit, HEX the run hex):
  - `... StorageLoadMain dynamodb-load --config /opt/scholarchain/config/course.json --stores aws --export s3://{data-bucket}/graph/exports/run=HEX --out /tmp/storage-load-ATTEMPT --code-version SHA40`
  - `... StorageLoadMain neptune-csv-build --config /opt/scholarchain/config/course.json --stores aws --export s3://{data-bucket}/graph/exports/run=HEX --dataset-id scholarchain-graph --snapshot-id dblp-2026-11-01 --out /tmp/storage-csv-ATTEMPT --code-version SHA40`
  - `... StorageLoadMain neptune-bulk-start --config /opt/scholarchain/config/course.json --stores aws --graph-run s3://{data-bucket}/graph/dataset=scholarchain-graph/snapshot=dblp-2026-11-01/run=HEX --loader-role-arn NEPTUNE_LOADER_ROLE_ARN --out /tmp/storage-bulk-ATTEMPT --code-version SHA40`
  - `... StorageLoadMain neptune-bulk-status --config /opt/scholarchain/config/course.json --stores aws --load-id LOAD_ID --wait-seconds 600 --out /tmp/storage-status-ATTEMPT --code-version SHA40`
  - Local mode (tests): `--stores local --local-root DIR` (file-system S3 under DIR/<bucket>/<key>); `--local-root` is refused with aws.
- AWS mode needs the full storage config: the course.json sections plus the bucket, table and Neptune endpoint values from `SCHOLARCHAIN_CFG_*` in the ops task environment (as services); export and graph-run buckets must be configured zone buckets.
- Export-source API for rag (step 4), package `org.scholarchain.storage.source`: `ExportSources.open(location: String, objects: Option[ObjectStore]): Either[String, ExportSource]` (`s3://` -> `S3ExportSource`, else `LocalDirectorySource`); `S3ExportSource.fromUri(store: ObjectStore, uri: String): Either[String, S3ExportSource]` (`s3://BUCKET/PREFIX/run=<32 hex>`, trailing `/` allowed); on `ExportSource`: `manifest()`, `manifestBytes()`, `manifestWithSha256(): (ExportManifest, String)`, `checkPresence(m): ExportCheck`, `verifyAll(m): ExportCheck`, `lines(file)`, `describe` (bucket masked as `{data-bucket}`). Use `AwsStorageEnvironment.objects` as the store. Also reusable: `org.scholarchain.storage.load.BuildIdentity.load()` and `Masking`.
- Other public additions: `AwsStorageEnvironment.bulkLoader: Either[String, NeptuneBulkLoader]` (AwsStorageEnvironment.scala:174); `LoadStores` (Local, Aws, default); `GraphRun`.
- Removed: `ObjectStoreSource` (unused, untested; replaced by S3ExportSource).
- IAM needs (ops task role): add `s3:PutObject` on `${data_bucket_arn}/graph/*`; reads of the export need the export prefix readable (graph/* already is); DynamoDB and Neptune actions already granted; no BatchWriteItem.
- Fixtures: test-only `LoadFixtures` (placeholder buckets `example-*`, account 123456789012, role `arn:aws:iam::123456789012:role/example-neptune-loader`).
- Thread-safety: one invocation is single-threaded except DynamoLoader writes with `--parallelism` > 1 (local only); S3ExportSource is stateless and as thread-safe as its store.
- Limits: see "Bounded load results".

VALIDATION EVIDENCE
- targeted: sbt -batch "storage/testOnly org.scholarchain.storage.*" -> Passed: Total 232, Failed 0, Errors 0, Passed 232, Ignored 1; sbt -batch "rag/testOnly org.scholarchain.rag.RagIngestJobSuite org.scholarchain.rag.RetrievalSmokeSuite org.scholarchain.rag.runtime.RuntimeServiceSuite org.scholarchain.rag.runtime.QueryWorkflowSuite" -> Passed: Total 59, Failed 0, Errors 0, Passed 59
- full: not run: not the wave closer
- package: not run: not the wave closer
- finished: after the last command above
- files changed after these runs: no
