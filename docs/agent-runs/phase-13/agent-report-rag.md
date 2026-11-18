# agent-report-rag (phase 13, session 2, step 4)

STATUS: COMPLETE
OWNER: rag-engineer
WORK ORDER: docs/agent-runs/phase-13/prompt.md (sha256 60c943dccc86a6dc58ec074b95bbd63f04e2adca2f7a1c9a9abad266f28d36a7), decisions 7 and 7b (step 4, wave B, not the wave closer)
FILES CREATED:
- modules/rag/src/test/scala/org/scholarchain/rag/runtime/PromptResourcesSuite.scala
FILES MODIFIED:
- modules/rag/src/main/scala/org/scholarchain/rag/ingest/RagIngestJob.scala
- modules/rag/src/main/scala/org/scholarchain/rag/runtime/Prompts.scala
- modules/rag/src/test/scala/org/scholarchain/rag/RagIngestJobSuite.scala
- REMOVED (R3-06): modules/rag/src/main/resources/org/scholarchain/rag/prompts/runtime/{answer-synthesis-v1,answer-synthesis-v2,answer-synthesis-v3,query-planner-v1,query-planner-v2}.md (the whole `modules/rag/src/main/resources` folder; it held only these five). Before removal `shasum -a 256` showed each byte-equal to its `prompts/runtime` source. Removed with one `mv` of the folder into the session scratchpad (`.../scratchpad/rag-removed-prompt-copies`), not deleted; the files are tracked, so git shows them as ` D`.
COMMANDS RUN:
- shasum -a 256 docs/agent-runs/phase-13/prompt.md (matches); read-only git status/ls-files; ls, grep, head, cut on repository files
- sbt -batch "rag/Test/compile" -> [success]
- shasum -a 256 prompts/runtime/*.md modules/rag/src/main/resources/org/scholarchain/rag/prompts/runtime/*.md -> all five pairs equal
- mv modules/rag/src/main/resources -> scratchpad (not on the work order's command list; no approval prompt appeared; noted here)
- sbt -batch "rag/testOnly org.scholarchain.rag.*" -> first run: Failed 1 (my PromptResourcesSuite origin check expected a `resource_managed` URL; sbt copies the generated resources into `classes/`); test corrected
- sbt -batch "rag/testOnly org.scholarchain.rag.*" -> Passed: Total 231, Failed 0, Errors 0, Passed 231, Ignored 2
- `sbt fmt` not run: the PostToolUse formatter formatted every Scala file I wrote (one comment syntax error, a `/**` inside a doc comment, fixed); a repository-wide `sbt fmt` could touch other owners' files.
TEST RESULTS: rag 231 passed, 0 failed, 2 ignored (RequiresAws). RagIngestJobSuite 20 (11 new), PromptResourcesSuite 4 (new). Direct users of the changed APIs are rag-internal (services and tests/contracts do not reference RagIngestJob or Prompts.loadResource/loadPackagedSet; the new parameters have defaults).
VALIDATION RESULTS: no live call, no runMain, no network; RequiresAws suites unrun; nothing outside modules/rag/** changed. Retrieval and groundedness evaluation: not named by this work order beyond the existing suites (GroundingSuite, ClaimVerificationSuite, RetrievalSmokeSuite all pass in the run above); prompts unchanged, so no prompt version changed.
ASSUMPTIONS:
- "The data lake's `rag/` prefix" is the configured `semantic-context` zone (`objectStore.semanticContextPrefix = "rag"` in config/course.json), bucket from configuration.
- `--code-version` is required (as in StorageLoadMain and ErMain); the scripts always pass the build commit.
- An `s3://` export needs `--stores aws` (the local in-memory stores hold no data lake); a local export path still works with both store kinds.
- The report copy key is content-addressed (sha256 of the report bytes), so put-if-absent never meets other bytes under one key; a failed attempt before the run ID exists uses `no-run`.
RISKS:
- IAM for infra-engineer (step 6): NO NEW ACTION for the report copy: `RagWrite` (s3:PutObject on `rag/*`) covers the conditional put; on a 412 storage's S3ObjectStore calls `head` (s3:GetObject on `rag/*`, granted by `GraphAndRagRead`); the data lake uses SSE-S3 (AES256), so no KMS action. Reading an `s3://` export under `graph/` is covered (`GraphAndRagRead`, `DataLakeList` on graph). CONDITIONAL: if step 5 writes exports outside `graph/`, the ops role needs `s3:GetObject` on `${data_bucket_arn}/<that prefix>/*` and `s3:ListBucket` with an `s3:prefix` condition for it (same as storage's note).
- Disk bound (see below): met on paper; actual usable space on Fargate is `runtime: untested`.
- The report copy is the last write of an attempt; if the JVM is killed before it, only the local `--out` report exists (the ops task's ephemeral disk is lost with the task). The stdout lines in CloudWatch remain.
- Masking is built from the configuration; an error raised before the configuration loads is not bucket-masked (it can hold no configured bucket, since none is known yet; ARN accounts are still masked).
- runtime: untested (RagIngestJob on AWS has never run; first s3:// run is phase-14).
MANUAL ACTION REQUIRED: none. (Optional: the human may discard the moved copies under the scratchpad; they are byte-equal to prompts/runtime and in git history.)
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: step 5 (spark-engineer, wave closer: sbt check and packageCheck; packageCheck's "copy that differs" check is now a no-op because no copy exists); R-2 review; step 6 IAM (no new action from rag); step 7b scripts call RagIngestJob from the constant below.

## Requirements (one line each)
- 7 `--code-version` recorded with the build identity: done, RagIngestJob.scala:374-376 (`codeVersion`, `jobCodeVersion`, `build` from storage's BuildIdentity; missing resource = `unknown`/`missing`); test RagIngestJobSuite.scala:337. runtime: untested.
- 7 no secret, bucket or account printed or written: done, RagIngestJob.scala:380 and :409 (Masking over zone buckets, vector bucket, account ID), :394 (every report write masked), every printed line through `say`; S3 export described as `s3://{data-bucket}/KEY`; test RagIngestJobSuite.scala:433.
- 7 option names in one public constant + exact-contents test + unknown refused: done, RagIngestJob.scala:90-91 (`Required`, `Optional`), :119 (parse: unknown, repeated, positional, missing, bad values refused); tests RagIngestJobSuite.scala:293 (exact contents), :298 (accepts exactly the names, refuses the rest).
- 7b `--export s3://` through storage's export source, local path still works: done, RagIngestJob.scala:425 (`ExportSources.open` with the stores' ObjectStore), :430 (bucket must be a configured zone bucket), checkPresence before ingest; tests :370 (s3 vs local: same run ID, manifest sha256 and corpus digest), :448 (missing manifest), :464 (changed file), :474 (missing file), :481 (foreign bucket); existing local tests unchanged in behavior.
- 7b run report also to the `rag/` prefix with aws stores (local `--out` stays): done, RagIngestJob.scala:331 (`reportCopyKey`), :621 (`copyReport`, put-if-absent, outcome recorded in `<out>/run-report-copy.json` and stdout); byte-identical to `<out>/run-report.json` (test :370); local stores make no copy (test :337).
- 7b CodeVersion constant keeps its meaning: done, `CodeVersion = "rag/1"` unchanged and still the run-ID input; test :337 (two different `--code-version` values give the same run ID).
- 7b free-disk bound vs ops ephemeral storage: done (result below); bound exposed as `MinFreeBytes` RagIngestJob.scala:84; test RagIngestJobSuite.scala:491.
- R3-06 prompt loading from the resources mapped from prompts/**: done, Prompts.scala:135 (ResourceRoot = build mapping path `org/scholarchain/rag/prompts/runtime`), :143 (`resourceBytes`), :162 (`loadResource`), :172 (`loadPackagedSet`); copies removed (FILES MODIFIED).
- R3-06 byte-equality test: done, PromptResourcesSuite.scala:30 (every loadable prompt's classpath bytes sha256-equal to prompts/runtime; ID sets equal), :46 (no copy folder exists; exactly one classpath entry per prompt).
- R3-06 clear failure when missing: done, Prompts.scala:143 (message names the prompt, `/org/scholarchain/rag/prompts/runtime/<id>.md` and `prompts/runtime/<id>.md`); tests PromptResourcesSuite.scala:58, :74.

## Disk-bound result
- Job bound: 15 GiB free under `--out` at the start of every step (`MinFreeBytes` = 15 x 1024^3 bytes; the old message said "15 GB", now "15 GiB").
- ops task: `ephemeral_storage.size_in_gib = var.ops_ephemeral_storage_gib`, default 30 (infra/terraform/modules/runtime/main.tf:209-211, variables.tf:93-101; no override in the environment). On Fargate the ephemeral storage also holds the image layers (Java runtime plus app.jar, well under 1 GiB), so about 29 GiB stays usable under `/tmp`.
- The job writes little locally: the report, the copy record and the reject logs. It streams `s3://` exports through the ObjectStore and downloads nothing.
- Result: the bound can be met (about 29 GiB usable against 15 GiB needed, about 14 GiB of margin). infra-engineer needs no change. Actual usable space on Fargate: unverified (runtime untested).

## Prompt and model versions
- No prompt changed: prompts/runtime/*.md are byte-identical (sha256 above). The prompt version (`<id>@sha256:<file hash>`) is the same from the classpath and from the files (PromptResourcesSuite.scala:30). Prompt set Current = v3 (query-planner-v2, answer-synthesis-v3). Model IDs: unchanged.

## Evaluation metrics
- Not re-run as a separate evaluation (no prompt, retrieval or synthesis change). The groundedness and retrieval suites pass in the full rag run (231 passed).

## Known grounding gaps
- None introduced. This step only changed ingest I/O and where prompts are loaded from.

## Proposed later
- A `--attempt-id` option would let the report copy key carry the script's attempt ID. Today the key carries the run and the report sha256, and the script finds it from the stdout line or `run-report-copy.json`.

## INTERFACE HANDOFF
- Class: `org.scholarchain.rag.ingest.RagIngestJob` (object with `main`), run as `java -cp /opt/scholarchain/app.jar org.scholarchain.rag.ingest.RagIngestJob --name value ...`.
- Option constant: `RagIngestJob.Required` and `RagIngestJob.Optional` at modules/rag/src/main/scala/org/scholarchain/rag/ingest/RagIngestJob.scala:90-91 (names without `--`). Values: `--stores local|aws` (default local); `--runs 1..3` (default 2); `--samples-per-index 1..500` (default 25); `--code-version` matches `[A-Za-z0-9][A-Za-z0-9._-]{0,99}`; `--export` is a local directory or `s3://BUCKET/PREFIX/run=<32 hex>` (aws stores only; the bucket must be a configured zone bucket); `--out` must not exist. Scripts are written from the constant, not from this report.
- Exit codes (main): 0 completed (and with aws stores the copy `created` or `already present`), 1 failed, stopped or copy not written, 2 arguments refused.
- Example command lines (placeholders: {data-bucket} resolved by the script, SHA40 the build commit, HEX the export run hex, ATTEMPT the attempt ID):
  - local: `java -cp app.jar org.scholarchain.rag.ingest.RagIngestJob --config config/rag-local.json --export /data/phase-07-export/run=HEX --out /tmp/rag-ingest-ATTEMPT --code-version SHA40 --stores local --runs 2`
  - s3 export with aws stores (ops task): `java -cp /opt/scholarchain/app.jar org.scholarchain.rag.ingest.RagIngestJob --config /opt/scholarchain/config/course.json --export s3://{data-bucket}/graph/exports/run=HEX --out /tmp/rag-ingest-ATTEMPT --code-version SHA40 --stores aws --runs 2 --samples-per-index 25`
- Outputs: `<out>/run-report.json` (format `scholarchain.rag.ingest-run-report` v1; new top-level keys `codeVersion` (the `--code-version` value), `jobCodeVersion` (`rag/1`, the run-ID input), `build` {buildCommit, buildDirty, module, version, status}; `input` now has `export` (masked describe), `exportKind` (`s3`|`local`), `exportManifestSha256`, `sourceRunId`, `files`, `bytes`, `checks`, `configFile`, `configFingerprint` (the old `input.exportDir` key is replaced by `input.export`); `environment` adds `minFreeDiskBytes`, `storesDescription`; `runId`, `status`, `runs`, ... as before). With aws stores also `<out>/run-report-copy.json` (format `scholarchain.rag.ingest-report-copy` v1: `object` = `s3://{data-bucket}/<rag prefix>/ingest-reports/run=<32 hex>|no-run/run-report-sha256=<hex>.json`, `sha256`, `bytes`, `outcome` = `created` | `already present` | `conflict: ...` | `failed: ...` | `not attempted: ...`), and the stdout lines `[rag-ingest] report copy: OUTCOME s3://{data-bucket}/KEY` and `[rag-ingest] STATUS; report: <out>/run-report.json`. Everything printed or written is masked.
- Public API changes: `Options` (`exportDir: Path` becomes `exportLocation: String`; adds `codeVersion`); `IngestStores.objects: ObjectStore` (new abstract member); new `Hooks`, `JobResult`, `execute(...)`, `reportCopyKey(...)`, `MinFreeBytes` (now public), `Required`, `Optional`, `ReportFormat`, `ReportCopyFormat`, `ReportCopyFolder`; `run(...)` keeps its result type (adds an optional `hooks` parameter). Prompts: `resourceName(id)`, `resourceBytes(id, loader)`, `loadResource(id, loader = default)`, `loadPackagedSet(spec, loader = default)`.
- Build (build-engineer): no build.sbt change needed. The filter at build.sbt:381-384 is now a no-op, since `modules/rag/src/main/resources` no longer exists. packageCheck's copy check finds nothing to compare.
- IAM needs (ops task role): none new (see RISKS); conditional read grant only if exports live outside `graph/`.
- Fixtures: tests reuse RagFixtures (placeholder buckets `example-*`, account 123456789012, key `test-key-not-real`); the S3 export is served by storage's in-memory ObjectStore.
- Thread-safety: one invocation is single-threaded apart from the existing pipeline. `Prompts` loading is stateless.
- Limits: unchanged (runs <= 3, samples <= 500, a step <= 60 minutes, free disk >= 15 GiB).

VALIDATION EVIDENCE
- targeted: sbt -batch "rag/testOnly org.scholarchain.rag.*" -> Passed: Total 231, Failed 0, Errors 0, Passed 231, Ignored 2
- full: not run: not the wave closer
- package: not run: not the wave closer
- finished: after the last command above
- files changed after these runs: no
