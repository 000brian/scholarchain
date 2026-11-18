interface: no (no option name, entry point, subcommand, exit code or report field a script reads changed; the new registry states are read by no script: `grep -rn 'loadState|nodes-started|nodes-failed|edges-|bulkLoad|registryVersion|neptune-load|ArtifactRegistry' scripts infra/terraform/modules/runtime` finds only the table-name variable at infra/terraform/modules/runtime/main.tf:52; run-bounded-pipeline.sh:1316-1330 copies `[storage-load]` lines generically, and both "started nodes load ID" and "started edges load ID (depends on ID)" lines are unchanged). Behavior change an operator sees: after an UNKNOWN edges start failure a retry is now refused (before: it resumed and could start a second edges load; that is C-06's fix).

# Phase 14 session 2 step 4: storage-engineer (wave closer)

Work order: docs/agent-runs/phase-14/prompt.md sha256 b1f951683cc708c297d3e74d6b21332f5218324e4db0086d33307ec8bf8b88a2, HEAD c4fbf62, decision 6 items 1-7.

## Items (one status line each)

- item 1 C-07: done. Port: `BulkLoadStartRejected` modules/storage/src/main/scala/org/scholarchain/storage/ports/GraphPorts.scala:37 and the `start` contract :43. Adapter: modules/storage/src/main/scala/org/scholarchain/storage/aws/NeptuneAdapters.scala:58-70 (definite -> BulkLoadStartRejected, else AwsErrors.wrap), classifier `NeptuneBulkLoader.isDefiniteRejection` :94-120. Loader: StorageLoadMain.scala:644-681 (`startStage`: appends `nodes-failed` with masked `startError` class and `startHttpStatus`, report field `bulkLoadFailure`, exit 1), retry from `nodes-failed` :705-710 (new `starting` claim, nodes start again). Unknown outcome unchanged (claim stays, retry refused) with new text :682-685: says what to check (Neptune's loader list; `neptune-bulk-status --load-id ID` when a load ID is known), that the registry is append-only and never edited by hand, and that a new export or configuration is the only other way on. No "runbook" in any refusal text (test :293 asserts it).
- item 2 C-06: done. `edges-starting` claim written before the edges start StorageLoadMain.scala:722, start :723-724, retry from `edges-starting` refused :716-717 (names the registered nodes load ID), definite edges refusal -> `edges-failed` (with nodesLoadId) via `startStage`, retry from `edges-failed` starts edges only :711-715. Protocol comment :608-621; state constants :742-747.
- item 3 tests: done. StorageLoadMainLocalSuite.scala: :262 definite nodes refusal then successful retry (registry states starting, nodes-failed, starting, nodes-started, edges-starting, started); :293 unknown nodes outcome then refused retry (text checks, no "runbook"); :307 C-06's case, a KV store that throws on the final `started` write after a successful edges start, retry refused, still 2 jobs; :322 definite edges refusal then retry starts edges only; :349 unknown edges outcome then refused retry; :361 registry failure before the edges claim then resume (keeps the old C-04 resume coverage). NeptuneBulkLoaderStartSuite.scala:89 the 12 definite classes, :100 six unknown cases (408, 429, ThrottlingException, 500, a definite class with 503, ApiCallTimeoutException), :120 success. "Fails without the fix": by reading, not by running the old code: on the pre-fix code :262 gets Refused (expects Completed), :293 finds "runbook" and no "outcome is unknown", :307 and :349 get Completed with 3 jobs / a resumed edges start (expect Refused), :322 finds no "records edges-failed", NeptuneBulkLoaderStartSuite:89 gets StoreException for every class. Two old tests were replaced: "an edges start failure, then a retry starts edges only (C-04)" (its expectation, resume after an unknown edges failure, is exactly what C-06 forbids; now :349 and :361) and "a failed nodes start is never retried blindly (C-04)" (its text assertion pointed to the old message; now :293).
- item 4 Phase 7 m3, no lock: done (no code). Accepted risk for a one-person environment: one `ops` task at a time is the script's check-then-start plus the human running one load at a time. The registry's conditional put (version n+1) still makes two racing `neptune-bulk-start` runs of the same graph run fail on the second writer (StorageLoadMain.scala `register`, Change.Conflict -> failure), but nothing locks `dynamodb-load` or two different graph runs.
- item 5 storage retries and cleanup (Phase 9 deferral): phase-16 (no code). Becomes a session 3 repair only if a session 3 run fails on a throttle or timeout. Note: a 429 or throttling answer to StartLoaderJob is classified unknown (conservative), so it leaves a claim that blocks a retry of that graph run; see RISKS.
- item 6 live-suite command: done, see INTERFACE HANDOFF.
- item 7 gates: done. Targeted 61/61, sbt check passed (2075 module, 398 contract), packageCheck ok; see VALIDATION EVIDENCE.

## Definite-error list (StartLoaderJob)

Definite = the SDK exception is one of these neptunedata model classes AND its HTTP status is 4xx other than 408 and 429 (NeptuneAdapters.scala:106-120).

| SDK class (software.amazon.awssdk.services.neptunedata.model) | HTTP | Source |
|---|---|---|
| BadRequestException | 400 | StartLoaderJob Errors |
| BulkLoadIdNotFoundException | 404 | StartLoaderJob Errors |
| ConstraintViolationException | 400 | StartLoaderJob Errors |
| IllegalArgumentException | 400 | StartLoaderJob Errors |
| InvalidArgumentException | 400 | StartLoaderJob Errors |
| InvalidParameterException | 400 | StartLoaderJob Errors |
| LoadUrlAccessDeniedException | 400 | StartLoaderJob Errors |
| MissingParameterException | 400 | StartLoaderJob Errors |
| PreconditionsFailedException | 400 | StartLoaderJob Errors |
| S3Exception | 400 | StartLoaderJob Errors |
| UnsupportedOperationException | 400 | StartLoaderJob Errors |
| AccessDeniedException | 403 | Common Error Types |

Not definite (unknown outcome): ClientTimeoutException 408, TooManyRequestsException 429, InternalFailureException 500 (StartLoaderJob Errors); ThrottlingException (Common Error Types, listed with HTTP 400, hence the class list and not a plain 4xx rule); every other service error; every SdkClientException (timeouts, I/O, interrupted); a 200 response without `loadId`.
Sources (aws-docs, read this run): https://docs.aws.amazon.com/neptune/latest/data-api/API_StartLoaderJob.html ("Errors"), https://docs.aws.amazon.com/neptune/latest/data-api/CommonErrors.html. The class names were checked to exist in the pinned SDK jar neptunedata-2.59.1 (zip entry names). Unverified: which of these Neptune actually returns for "loader role not associated with the cluster" (the likely first-run error); whatever class it is, it is definite only if it is on this list.

## Per-store role and key design (unchanged this phase)

- ArtifactRegistry (DynamoDB): append-only versions of artifact `neptune-load` keyed by the graph run's S3 key; each version a conditional put of version n+1. New states this phase: `nodes-failed`, `edges-starting`, `edges-failed`; new attributes on failed versions: `startError` (masked class name), `startHttpStatus`.
- Neptune bulk loader: unchanged requests (BulkLoadRequests).

## INTERFACE HANDOFF

Files changed (all modules/storage/**):
- src/main/scala/org/scholarchain/storage/ports/GraphPorts.scala: new `final class BulkLoadStartRejected(val errorClass: String, val httpStatus: Int, message: String) extends RuntimeException`; `GraphBulkLoader.start` contract documented (throws it only for a definite refusal).
- src/main/scala/org/scholarchain/storage/aws/NeptuneAdapters.scala: `NeptuneBulkLoader.start` maps definite refusals; new `object NeptuneBulkLoader { def isDefiniteRejection(e: SdkServiceException): Boolean }`.
- src/main/scala/org/scholarchain/storage/load/StorageLoadMain.scala: registry protocol above; new public vals `StateNodesFailed = "nodes-failed"`, `StateEdgesStarting = "edges-starting"`, `StateEdgesFailed = "edges-failed"`; new report field `bulkLoadFailure {stage, errorClass, httpStatus, registryState}` on a definite refusal (no script reads it). Subcommands, options and exit codes unchanged (a definite refusal exits 1, as any start failure did).
- src/test/scala/org/scholarchain/storage/load/StorageLoadMainLocalSuite.scala (6 tests replace 2), src/test/scala/org/scholarchain/storage/NeptuneBulkLoaderStartSuite.scala (new, 3 tests).
Fixtures: none new (placeholders `example-*`, `123456789012`). Thread-safety: unchanged; `neptune-bulk-start` is single-threaded, the registry's conditional put is the only concurrency guard. Limits: unchanged.

### Live suite: AwsAdaptersAwsSuite [the human runs it after the deploy, D16]

Command (one zsh line, from the repository root, after the exports below):
`sbt -batch "storage/AwsTest/testOnly org.scholarchain.storage.AwsAdaptersAwsSuite"`
(`AwsTest` runs only tests tagged `RequiresAws`, build.sbt:93-107; it never writes .claude/.last-test-run.)

Environment variables, by NAME (no value here). Set each with command substitution so no value is printed, for example `export NAME="$(terraform -chdir=infra/terraform/environments/course output -raw OUTPUT)"`; for a map output `export NAME="$(terraform -chdir=infra/terraform/environments/course output -json dynamodb_table_names | jq -r .KEY)"`. The set is the ops task's `config_environment` (infra/terraform/modules/runtime/main.tf:37-54):

| Variable | Value from |
|---|---|
| SCHOLARCHAIN_CONFIG_FILE | the path `config/course.json` (not a terraform output) |
| SCHOLARCHAIN_CFG_AWS_ACCOUNT_ID | no terraform output exposes it (module.foundation.account_id is internal); the human's own read, set by command substitution and never echoed |
| SCHOLARCHAIN_CFG_STORAGE_RAW_BUCKET | output `data_bucket_name` |
| SCHOLARCHAIN_CFG_STORAGE_CURATED_BUCKET | output `data_bucket_name` |
| SCHOLARCHAIN_CFG_STORAGE_GRAPH_BUCKET | output `data_bucket_name` |
| SCHOLARCHAIN_CFG_OBJECT_STORE_SEMANTIC_CONTEXT_BUCKET | output `data_bucket_name` |
| SCHOLARCHAIN_CFG_OBJECT_STORE_REPORTS_BUCKET | output `data_bucket_name` |
| SCHOLARCHAIN_CFG_OBJECT_STORE_LOGS_BUCKET | output `data_bucket_name` |
| SCHOLARCHAIN_CFG_STORAGE_VECTOR_BUCKET | output `vector_bucket_name` |
| SCHOLARCHAIN_CFG_STORAGE_NEPTUNE_ENDPOINT | output `neptune_endpoint` |
| SCHOLARCHAIN_CFG_STORAGE_DYNAMO_DB_TABLE | output `dynamodb_table_names`, key `RunLedger` |
| SCHOLARCHAIN_CFG_DYNAMO_DB_RUN_LEDGER_TABLE | output `dynamodb_table_names`, key `RunLedger` |
| SCHOLARCHAIN_CFG_DYNAMO_DB_ENTITY_LINK_CACHE_TABLE | output `dynamodb_table_names`, key `EntityLinkCache` |
| SCHOLARCHAIN_CFG_DYNAMO_DB_QUERY_SESSION_TABLE | output `dynamodb_table_names`, key `QuerySession` |
| SCHOLARCHAIN_CFG_DYNAMO_DB_EVIDENCE_BUNDLE_TABLE | output `dynamodb_table_names`, key `EvidenceBundle` |
| SCHOLARCHAIN_CFG_DYNAMO_DB_ARTIFACT_REGISTRY_TABLE | output `dynamodb_table_names`, key `ArtifactRegistry` |
| SCHOLARCHAIN_CFG_DYNAMO_DB_RESEARCHER_METRIC_CACHE_TABLE | output `dynamodb_table_names`, key `ResearcherMetricCache` |
| AWS_PROFILE | `default` (credentials from the default provider chain; planned deviation 2) |
| SCHOLARCHAIN_STORAGE_ITEST_QUEUE_URL | leave UNSET (the SQS test is then skipped by `assume`). The only queues are the job and embedding queues (outputs `job_queue_url`, `embedding_queue_url`); with the runtime on, the worker consumes the job queue, so the test's `itest:` message would reach the worker. |

What it does in AWS (6 tests, AwsAdaptersAwsSuite.scala; 5-s API timeouts, one SDK attempt per call, 60 s per test):
- :35 S3: writes at most 1 object, `probe.json`, in the data bucket's logs zone under `logs/dataset=itest/snapshot=itest/run=HEX/storage-itest/` (put-if-absent; a rerun finds it present; deterministic run ID). Not removed.
- :54 DynamoDB: writes 1 RunLedger item (pk `run#itest#run:v1:HEX`, sk `itest`), conditional; rerun writes nothing. Not removed.
- :68 S3 Vectors: reads every index's definition, then writes 2 vectors `itest:a`, `itest:b` (stub embeddings, metadata documentType `researcher_profile`) into `researcher-profile-v1` (the spec's first index); a rerun overwrites the same 2 keys. Not removed.
- :100 Neptune and :109 AwsStorageEnvironment: read-only (one openCypher read; 1 GetItem, 1 HeadObject, 1 GetIndex). The Neptune endpoint is reachable only inside the VPC (outputs.tf:127 description), so from a laptop both tests are expected to FAIL on a timeout; that is not a code defect. No in-VPC way to run test classes exists (the ops task runs the app jar only).
- :132 SQS: skipped (variable unset).
- Cleanup: the suite cleans up nothing (storage has no removal path, law 4). The `itest` names exist so a cleanup can find them, but scripts/cleanup.sh has no `itest` rule today (`grep -n itest scripts/cleanup.sh` finds nothing). See RISKS for the 2 vectors.

## Idempotency evidence

- A start is never sent twice for the same stage unless the previous attempt is recorded as refused for certain (tests :262, :307, :322, :349). The registry is never edited; recovery is always a new version.

## Bounded load results

- Not run (no AWS in session 2). Local end-to-end coverage: StorageLoadMainLocalSuite, 17 tests passed.

```text
STATUS: COMPLETE
OWNER: storage-engineer
WORK ORDER: docs/agent-runs/phase-14/prompt.md (sha256 b1f951683cc708c297d3e74d6b21332f5218324e4db0086d33307ec8bf8b88a2), decision 6 items 1-7, session 2 step 4, wave closer
FILES CREATED: modules/storage/src/test/scala/org/scholarchain/storage/NeptuneBulkLoaderStartSuite.scala
FILES MODIFIED: modules/storage/src/main/scala/org/scholarchain/storage/ports/GraphPorts.scala; modules/storage/src/main/scala/org/scholarchain/storage/aws/NeptuneAdapters.scala; modules/storage/src/main/scala/org/scholarchain/storage/load/StorageLoadMain.scala; modules/storage/src/test/scala/org/scholarchain/storage/load/StorageLoadMainLocalSuite.scala
COMMANDS RUN: sbt -batch "storage/testOnly org.scholarchain.storage.load.StorageLoadMainLocalSuite org.scholarchain.storage.NeptuneBulkLoaderStartSuite org.scholarchain.storage.load.StorageLoadMainArgsSuite org.scholarchain.storage.AwsEnvironmentSuite org.scholarchain.storage.AwsErrorMappingSuite"; sbt check (background, output to the scratchpad log, read with tail -n and grep); sbt -batch packageCheck; read-only git (rev-parse, status); shasum, wc, ls, grep, head, tail -n, cut. aws-docs: read_documentation on API_StartLoaderJob.html and CommonErrors.html. Off-list: one `grep -a -o ... | ... | tr -d ''` on the pinned neptunedata SDK jar in the local Coursier cache (read-only listing of class names; `tr` is on the never-list; a no-op, reported here). Formatter hook ran on every Scala edit.
TEST RESULTS: targeted: Passed: Total 61, Failed 0, Errors 0, Passed 61 (StorageLoadMainLocalSuite 17, NeptuneBulkLoaderStartSuite 3, StorageLoadMainArgsSuite 9, AwsEnvironmentSuite 15, AwsErrorMappingSuite 17). sbt check: Module tests suites=127 executed=2075 passed=2075 failed=0 errors=0 skipped=3; Contract tests (no AWS) suites=34 executed=398 passed=398 failed=0; 0 "[error]" lines; hadoop's ScholarChainHadoopSuite 0 failed, 3 total (hadoop-engineer's change is closed by this check).
VALIDATION RESULTS: packageCheck: ok (four assemblies built). .claude/.last-test-run head: module_tests=2075, contract_tests=398.
ASSUMPTIONS: the 12 classes listed are definite refusals per the AWS Neptune Data API pages (documented HTTP 4xx, the request was refused); the 5xx/408/429/throttling classes and all client-side errors are unknown. AwsAdaptersAwsSuite is run by the human with the admin profile `default` from a laptop.
RISKS: (1) A 429 or throttling answer to StartLoaderJob counts as unknown (conservative), so it leaves a claim that refuses later starts of that graph run; the way on is then a check of Neptune's loader list and, if no load exists, a new export or config (phase-16 retries item). (2) If recording `nodes-failed`/`edges-failed` itself fails, the claim stays and retries are refused (safe direction; the report says so). (3) AwsAdaptersAwsSuite leaves 2 stub vectors (`itest:a`, `itest:b`, documentType researcher_profile) in `researcher-profile-v1`; they could appear in runtime retrieval. Run the suite after D14/D15 or accept that; cleanup.sh has no itest rule. (4) Its two Neptune tests fail from outside the VPC (expected). (5) Phase 7 m3: no lock (accepted risk, item 4). (6) "fails without the fix" was shown by reading, not by running the old code.
MANUAL ACTION REQUIRED: none for session 2. At D16 the human sets the variables above and runs the suite command.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: R-1 code-reviewer (steps 1, 3, 4).
```

Proposed later: a `cleanup.sh` rule for `itest` objects, items and vectors (infra-engineer), so live-suite data can be removed with human confirmation; a read-only `neptune-bulk-list` subcommand (ListLoaderJobs) so the unknown-outcome check needs no hand-written call.

VALIDATION EVIDENCE
- targeted: sbt -batch "storage/testOnly org.scholarchain.storage.load.StorageLoadMainLocalSuite org.scholarchain.storage.NeptuneBulkLoaderStartSuite org.scholarchain.storage.load.StorageLoadMainArgsSuite org.scholarchain.storage.AwsEnvironmentSuite org.scholarchain.storage.AwsErrorMappingSuite" -> Passed: Total 61, Failed 0, Errors 0, Passed 61 (0 ignored)
- full: sbt check -> "Updated /Users/brian/Developer/repos/scholarchain/.claude/.last-test-run (module_tests=2075, contract_tests=398)"
- package (wave closer): sbt -batch packageCheck -> "packageCheck: ok"
- finished: after the last command above
- files changed after these runs: no
