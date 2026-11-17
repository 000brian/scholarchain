STATUS: COMPLETE
OWNER: storage-engineer
WORK ORDER: docs/agent-runs/phase-11/prompt.md, decision 8, step 3e (targeted tests only, not a wave closer)
FILES CREATED:
- /Users/brian/Developer/repos/scholarchain/modules/storage/src/test/resources/wire/v1/QueryAudit.json (verbatim copy of domain's frozen v1 example)
- /Users/brian/Developer/repos/scholarchain/modules/storage/src/test/resources/wire/v2/QueryAudit.json (verbatim copy of domain's frozen v2 example)
FILES MODIFIED:
- /Users/brian/Developer/repos/scholarchain/modules/storage/src/main/scala/org/scholarchain/storage/aws/AwsSupport.scala (B-01)
- /Users/brian/Developer/repos/scholarchain/modules/storage/src/main/scala/org/scholarchain/storage/aws/DynamoDbKeyValueStore.scala (B-11, doc only)
- /Users/brian/Developer/repos/scholarchain/modules/storage/src/main/scala/org/scholarchain/storage/ports/KeyValueStore.scala (B-11, doc only)
- /Users/brian/Developer/repos/scholarchain/modules/storage/src/test/scala/org/scholarchain/storage/AwsErrorMappingSuite.scala (B-01, r1, r2 tests)
- /Users/brian/Developer/repos/scholarchain/modules/storage/src/test/scala/org/scholarchain/storage/RuntimeReadersSuite.scala (QueryAudit v2 round trip)
COMMANDS RUN:
- read-only grep/ls/git status/sed -n for inspection
- run 1 (background, log step3e-targeted.log): same command as below but with a wrong rag suite name (`org.scholarchain.rag.EmbeddingProvidersSuite`, "No tests to run"); storage 43/43 passed, contract suites same as run 2. Superseded; not counted as evidence.
- run 2 (background, log /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/858dada8-c8dd-43c7-b05d-961e323b855c/scratchpad/step3e-targeted-2.log): see TEST EVIDENCE. No source change between run 1 and run 2.
- grep -l -E 'failures="[1-9]|errors="[1-9]' modules/storage/target/test-reports/TEST-*.xml modules/rag/target/test-reports/TEST-*.xml -> empty (grep exit 1)
TEST RESULTS: storage 43/43 passed (AwsErrorMappingSuite 17, RuntimeReadersSuite 11, AwsEnvironmentSuite 15); rag BedrockTitanEmbedderSuite 2/2; contract suites 11/12 passed, the 1 "failure" is the expected B-01 flip (".fail" test passed).
VALIDATION RESULTS: storage and rag test-report grep clean. Contract reports carry the expected flip only.
ASSUMPTIONS:
- AWS SDK v2: SdkClientException.retryable() is false for ApiCallTimeoutException and for "Unable to execute HTTP request" errors built without the SDK retry layer (B-01 reviewer assumption); confirmed indirectly: the contract test observed transient=false before the fix and passes after it.
- AbortedException (thread interrupted) is deliberately kept permanent: retrying after an interrupt would fight cancellation.
- aws-docs MCP failed to connect this session; no AWS documentation was consulted. The SDK classes were used only through their public builders.
RISKS:
- B-01 changes retry behaviour for every caller of AwsErrors.wrap/guard/recover: DynamoDB, S3, S3 Vectors, SQS, Neptune adapters and rag's SdkBedrockInvoker. Client timeouts and I/O errors are now retried by Retry.run (bounded by each policy's maxRetries). A non-idempotent call wrapped by a retrying decorator could be re-sent after a timeout that the server applied; storage's writes are conditional (DynamoDB putIfAbsent/putIfVersion, S3 If-None-Match) so a replay resolves as ConditionFailed/AlreadyPresent -> Unchanged, as RetryingStores already documents. rag's Bedrock calls (InvokeModel) are read-like; rag-engineer may want to note that a timeout now consumes its retry budget.
- Neptune: NeptuneGraphRead catches timeouts itself (isTimeout) and returns "stopped", so the path-search behaviour is unchanged (AwsEnvironmentSuite and NeptuneTimeoutStubSuite non-.fail tests still pass).
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: step 3f (rag-engineer); test-engineer step 4 removes the `.fail` mark on NeptuneTimeoutStubSuite "B-01 a client-side API call timeout is classified transient"; wave closer's sbt check.

## Per-item status

### B-01 (major) — FIXED
- Cause: AwsSupport.scala `case c: SdkClientException => ... c.retryable()`; with SDK maxAttempts = 1 nothing set retryable, so client timeouts and I/O errors were permanent and no layer retried them.
- Fix: new `AwsErrors.clientErrorIsTransient(c: SdkClientException): Boolean`, used by `wrap` for SdkClientException. Transient when `c.retryable()`, or when the cause chain (bounded to 8) contains ApiCallTimeoutException, ApiCallAttemptTimeoutException or any java.io.IOException (SocketTimeoutException, SocketException, ConnectException, UncheckedIOException's cause, ...). AbortedException stays permanent; a client error without such a cause (credentials, marshalling) stays permanent. The Left/exception message text is unchanged ("SdkClientException: ...").
- Tests (AwsErrorMappingSuite):
  - "B-01 AwsErrors.wrap: client timeouts and I/O causes are transient, other client errors not" (6 transient cases, 3 permanent cases);
  - "B-01 a DynamoDB timeout is retried by RetryingKeyValueStore up to maxRetries" (stub sees 3 PutItem calls, counters.retries = 2, final error transient);
  - "B-01 an S3 I/O failure is retried by RetryingObjectStore, then succeeds" (1 retry, value returned).
- How checked it fails without the fix: not by reverting. The old code returned `c.retryable()`; the contract `.fail` test "B-01 a client-side API call timeout is classified transient" observed transient=false on the same input type before the fix (step 1b) and flips now. Under the old code the first new test's timeout/I/O cases assert transient=true on the same kind of input (false before), the Dynamo test would see 1 PutItem call (not 3), and the S3 test would throw instead of returning the value.
- Contract flip: NeptuneTimeoutStubSuite "B-01 ..." expected failure but passed. No other test in the three contract suites changed result.

### B-11 (advisory, same root as B-01) — FIXED (doc only)
- DynamoDbKeyValueStore.scala class doc and ports/KeyValueStore.scala trait doc no longer claim the SDK retries throttling; they say the SDK makes one attempt and storage's bounded Retry/RetryingKeyValueStore retries transient failures. No test (doc only).

### P1 QueryAudit v2 round trip — DONE (QueryAuditWriter unchanged)
- RuntimeReadersSuite "QueryAuditWriter: the frozen v2 record is appended and read back unchanged": decodes domain's frozen v2 JSON, appends it, checks ledger `schemaVersion` = 2, the stored `record` JSON equals the frozen JSON (ujson structural equality), and `read` returns the identical QueryAudit.
- RuntimeReadersSuite "QueryAuditWriter: a stored v1 record still reads, next to a v2 record of the same run": puts the raw frozen v1 JSON into a ledger item exactly as a Phase 10 writer stored it; `read` returns the v1 record; a v1 record appended today keeps the v1 wire shape (no v2 keys); a v1, v1, v2 sequence of one run reads back in order.
- Both passed on the first run, so per the work order QueryAuditWriter was not changed. This is a P1 coverage item, not a fix: there is no "fails without the fix" step.

### P2 r1 (docs/agent-runs/phase-07/review-report-02.md line 81) — DONE
- The test at AwsErrorMappingSuite (old :153-156) now asserts the `transient` flag of a cause-less SdkClientException (permanent), and the B-01 tests cover the transient side. Shares the root with B-01.

### P2 r2 (review-report-02.md line 82) — DONE
- AwsErrorMappingSuite "r2 S3 openStream and head: missing object is None, other errors keep their retry class": NoSuchKey -> None for openStream and head; bare S3 404 -> None for head; 403 permanent and 503 transient for both. Coverage only: the code was already right (S3ObjectStore.scala:55-76), so no fail-without-fix step applies.

### P2 Phase 7 m3 (VectorIngestPipeline registry-conflict race) — NOT DONE, proposed later
- Reason: not small. Healing needs reading the vector's actual stored metadata (getVectors) after a registry Conflict and re-registering on top of the latest registry version, with its own race and version semantics, plus a concurrent-writer test harness. It is still recorded as `registry-conflict` (not silent).

### P2 Typed GraphRow decoder — NOT DONE, proposed later
- Reason: rag already decodes rows with its own `GraphRows` helper (modules/rag/.../runtime/ResearcherResolver.scala:13-33). A storage decoder is useful only if rag migrates to it in the same wave, which is not in rag's 3f list; adding it alone would be unused API.

### Spark handoff (3c): `policyVersions` / `controlFiles` in load and run manifests — no change needed
- storage does not read `_load_manifests/*.json` or Spark's `_run_manifest.json`. It reads only `_export_manifest.json` (ExportSource.scala:43-55), whose parser takes named keys and ignores unknown keys. S3KeyLayout only builds keys. Nothing breaks.

### Record only (not fixed, per work order)
- B-03 (minor) VectorIngestPipeline.scala:242-247 stale vector provenance on unchanged text across Spark runs.
- B-04 (minor) RuntimeReaders.scala:95-100 RM2 limit with stale ranking items.
- B-05 (minor) SqsWorkQueue.scala:71-77 malformed envelope aborts a batch receive.
- B-06 (minor) DynamoLoader.scala:161-171 parallel failures counted as conflicts; awaitTermination ignored.
- B-07 (minor) TableReader/ExportSource: sha256 checked after records were already emitted.
- B-09 (minor) NeptuneCsvBuilder.scala: works outside the full window reject researcher nodes and edges.
- B-12 (advisory) Neptune exact-depth path has no ORDER BY; InMemoryGraph picks smallest ID.
- B-13 (advisory) raw SDK text flows into Left strings.

## Proposed later
- Phase 7 m3 heal (see above).
- Typed GraphRow decoder in storage, together with rag migrating off its GraphRows helper.
- B-13: return fixed cause codes from storage instead of raw SDK text (pairs with services S-4).

## Per-store role and key design, idempotency, bounded load
- Unchanged in this step. Idempotency evidence relevant to B-01: retried writes stay conditional (DynamoDB putIfAbsent/putIfVersion; S3 If-None-Match), so a retry after a timeout that the server applied resolves to Unchanged/AlreadyPresent. No bounded load test was run (not requested in this step; no AWS).

## INTERFACE HANDOFF
- Files changed: listed above.
- Public API added: `org.scholarchain.storage.aws.AwsErrors.clientErrorIsTransient(c: software.amazon.awssdk.core.exception.SdkClientException): Boolean`.
- Behaviour changed: `AwsErrors.wrap` (and so `guard`, `recover`) now returns `StoreException(transient = true)` for client API-call/attempt timeouts and SdkClientExceptions with an IOException in the cause chain. Message format unchanged. Every Retry.run-based decorator (RetryingKeyValueStore, RetryingObjectStore, RetryingVectorIndex reads, PutVectorsBatcher, rag's BedrockTitanEmbedder) now retries them within its policy.
- Unchanged: QueryAuditWriter (`append(audit: QueryAudit): Either[String, AuditWrite]`, `read(queryRunId: RunId, limit: Int): Either[String, List[QueryAudit]]`) handles v1 and v2 records.
- Fixtures others can use: test resources `wire/v1/QueryAudit.json`, `wire/v2/QueryAudit.json` on the storage test classpath (copies of domain's frozen files; if domain changes them, recopy).
- Thread-safety: `clientErrorIsTransient` is pure.
- Limits: cause chain inspected to depth 8; AbortedException always permanent.

TEST EVIDENCE
- command: sbt -batch "storage/testOnly org.scholarchain.storage.AwsErrorMappingSuite org.scholarchain.storage.RuntimeReadersSuite org.scholarchain.storage.AwsEnvironmentSuite" "rag/testOnly org.scholarchain.rag.BedrockTitanEmbedderSuite" "contractTests/testOnly org.scholarchain.contracts.runtime.NeptuneTimeoutStubSuite org.scholarchain.contracts.runtime.S3VectorsThrottlingStubSuite org.scholarchain.contracts.runtime.DynamoConditionalConflictSuite"
- finished: Nov 16, 2026, 9:07:18 PM (local)
- per suite:
  - org.scholarchain.storage.AwsErrorMappingSuite run=17 passed=17 failed=0 skipped=0
  - org.scholarchain.storage.RuntimeReadersSuite run=11 passed=11 failed=0 skipped=0
  - org.scholarchain.storage.AwsEnvironmentSuite run=15 passed=15 failed=0 skipped=0
  - org.scholarchain.rag.BedrockTitanEmbedderSuite run=2 passed=2 failed=0 skipped=0
  - org.scholarchain.contracts.runtime.NeptuneTimeoutStubSuite run=4 passed=3 failed=1 skipped=0 (the 1 is the expected flip)
  - org.scholarchain.contracts.runtime.S3VectorsThrottlingStubSuite run=4 passed=4 failed=0 skipped=0
  - org.scholarchain.contracts.runtime.DynamoConditionalConflictSuite run=4 passed=4 failed=0 skipped=0
- result line: storage "[info] Passed: Total 43, Failed 0, Errors 0, Passed 43"; rag "[info] Passed: Total 2, Failed 0, Errors 0, Passed 2"; contractTests "[error] Failed: Total 12, Failed 1, Errors 0, Passed 11"; EXIT=1 (from the contract flip only)
- expected-failure flips: B-01 (NeptuneTimeoutStubSuite "B-01 a client-side API call timeout is classified transient"): "munit.FailException: tests/contracts/src/test/scala/org/scholarchain/contracts/runtime/NeptuneTimeoutStubSuite.scala:101 expected failure but test passed"
- source changed after this run: no
