# Phase 7+8 code review, re-check 02 (limited to finding M1)

## VERDICT: APPROVE

M1 is resolved. Every M1 call site now returns its intended outcome, I found no other instance of the pattern, and the new non-AWS suite runs in `sbt check`. The logs show it failing before the fix and passing after. No critical or major finding remains in scope.

Scope: per docs/agent-runs/phase-07/prompt.md, "Repair limits", this re-check covers M1 only. New minor findings are recorded below but are not routed for repair.

## M1 status: RESOLVED

**1. `AwsErrors.recover` maps only the listed exceptions and still wraps the rest with the right retry class.**
- `modules/storage/src/main/scala/org/scholarchain/storage/aws/AwsSupport.scala:87-92` has one try:
  - `case e: SdkException if expected.isDefinedAt(e) => expected(e)`
  - `case e: SdkException => throw wrap(e)`
- Every unlisted SdkException goes through `wrap` (lines 57-72), which is unchanged:
  - SdkServiceException: transient when it is a throttling error, HTTP 429, or HTTP 5xx; otherwise permanent.
  - SdkClientException: uses `retryable()`.
  - Any other SdkException: uses `retryable()`.
- Non-SDK throwables, including a StoreException raised inside the block, are not caught and pass through unchanged.
- `guard` (lines 78-80) is unchanged. Its doc comment now points to `recover`.

**2. The four defective methods now reach their intended outcomes.**
- `DynamoDbKeyValueStore.scala:46-49` defines the `conditionFailed` partial function, which maps only `ConditionalCheckFailedException` to `ConditionFailed`.
  - `putIfAbsent` (lines 51-62) and `putIfVersion` (lines 64-79) wrap the `putItem` call and the `Written` result in `AwsErrors.recover(conditionFailed)`.
  - As a result, `ConditionalWriter.upsertIfChanged` and `append` (`dynamo/ItemSupport.scala:90-122`) now reach their `ConditionFailed` branches on the AWS adapter.
- `S3ObjectStore.scala:46-53`: `get` maps `NoSuchKeyException` to `None`. Other errors are wrapped.
- `S3VectorsIndex.scala:21-30`: `describeIndex` maps `NotFoundException` to `None`. Other errors are wrapped.

**3. No other instance of the dead-catch pattern exists.** I checked every `catch` and every `AwsErrors.guard`/`recover` call site in `modules/storage/src/main` and `modules/rag/src/main`.
- `S3ObjectStore.putIfAbsent` (lines 35-43), `openStream` (lines 55-60) and `head` (lines 62-76) catch the specific exception first and call `wrap` in the same try. They are correct.
- `NeptuneAdapters.scala`:
  - lines 54 and 61: `guard` with no inner catch;
  - line 104: deliberately catches `StoreException` and returns `Left`, which is correct;
  - line 124: `guard` with no inner catch.
- `SqsWorkQueue.scala` (lines 40, 55, 77, 86): `guard` only.
- `DynamoDbKeyValueStore.get` and `query` (lines 82 and 119): `guard` only, with no expected-outcome catch.
- In `S3VectorsIndex.scala`, `putVectors`, `getVectors` and `query` (lines 69, 85 and 153) use `guard` only.
- rag `SdkBedrockInvoker.invoke` (`modules/rag/src/main/scala/org/scholarchain/rag/embedding/BedrockTitanEmbedder.scala:27-41`) uses `guard` with no inner catch, so it is not affected.
- The remaining catches (Retry.scala:51, DynamoLoader.scala:149, IngestWork, ExportSource, IndexSpec, VoyageEmbedder, RagIngestJob, SemanticRetriever, and the Titan `parse` method) are NonFatal or JSON parsing catches, not SDK exception mapping.

**4. The new test suite runs in `sbt check`, and its assertions catch the defect.**
- `modules/storage/src/test/scala/org/scholarchain/storage/AwsErrorMappingSuite.scala` has no `RequiresAws` tag. Within the storage tests, only `AwsAdaptersAwsSuite.scala` carries it.
- It uses stub `DynamoDbClient`, `S3Client` and `S3VectorsClient` instances that throw the real SDK exception classes. It covers:
  - `putIfAbsent` and `putIfVersion` returning `ConditionFailed` (lines 99-110);
  - `ConditionalWriter.upsertIfChanged` returning `Unchanged` with exactly 1 put (lines 112-118);
  - `upsertIfChanged` returning `Conflict` with 2 puts (lines 120-126);
  - `ConditionalWriter.append` returning `Unchanged` on a rerun and `Conflict` for different content (lines 128-135);
  - wrapping of other errors: 400 permanent, 503 transient, SdkClientException wrapped (lines 137-157);
  - S3 `get` returning `None` (line 200);
  - `TextStore.fetch` returning `Left` (lines 204-212);
  - S3 403 permanent (lines 214-218);
  - the S3 412 path regression (lines 220-242);
  - `describeIndex` returning `None` and 403 permanent (lines 252-263);
  - `recover` and `guard` semantics, including the `StoreException` passthrough (lines 267-280).

**5. The repair touched only `modules/storage/**`.**
- The repair report lists four files under `modules/storage/.../aws/` and one test under `modules/storage/src/test`.
- Glob modification-time ordering puts exactly these 5 files after all rag, tests, build.sbt and project files.
- The scalafmt step in the `sbt check` log checked "4 Scala sources" and "1 Scala sources" under `modules/storage` (m1-check.log lines 8-9).
- Limitation: I have no git access, and `modules/storage/` is wholly untracked, so this conclusion rests on the evidence above rather than a diff.

## Test evidence (scratchpad logs, which I read; I ran nothing)

Log directory: `/private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/b5367363-771f-4e88-a146-71d68c534a31/scratchpad/phase-07/`

- **m1-before.log**: 8 tests marked `==> X`, all failing with `StoreException: ConditionalCheckFailedException (HTTP 400)`, `NoSuchKeyException (HTTP 404)` or `NotFoundException (HTTP 404)`. The 8 are putIfAbsent, putIfVersion, both upsertIfChanged tests, append, S3 get, TextStore.fetch and describeIndex. Line 128: `[error] Failed: Total 12, Failed 8, Errors 0, Passed 4`. This is the defect M1 described.
- **m1-after.log** line 13: `[info] Passed: Total 13, Failed 0, Errors 0, Passed 13`, then `[success]`.
- **m1-check.log**:
  - line 6: `Ledger check passed`;
  - lines 7-10: scalafmt check;
  - line 174: `AwsErrorMappingSuite finished: 0 failed, 0 ignored, 13 total`;
  - line 9663: `Updated .../.claude/.last-test-run (module_tests=1431, contract_tests=336)`;
  - line 9664: `[success] Total time: 792 s`.
  - A search for any non-zero `failed` count, `Failed N` or `TestsFailedException` found no matches.
  - `AwsAdaptersAwsSuite` and `LiveProvidersAwsSuite` show "1 ignored, 0 total", which confirms that RequiresAws tests are excluded.

## New minor findings (recorded only, not repaired)

| Severity | File:line | Failure input | Impact | Owner |
|---|---|---|---|---|
| minor | modules/storage/src/test/scala/org/scholarchain/storage/AwsErrorMappingSuite.scala:153-156 | An SdkClientException thrown from putItem | The test checks only that the exception is wrapped. It does not check the `transient` flag, so a regression in the client-exception retry class (`wrap`, AwsSupport.scala:66-67) would pass. | storage-engineer |
| minor | modules/storage/src/test/scala/org/scholarchain/storage/AwsErrorMappingSuite.scala (no case for `openStream` or `head`) | NoSuchKeyException or S3Exception 404 from getObject or headObject | These paths are correct today (S3ObjectStore.scala:55-76), but no non-AWS test covers them. A future refactor onto `guard` could bring back the M1 pattern unnoticed. | storage-engineer |

The repair report's own risk, an S3 conditional PutObject that gets HTTP 409 under concurrency, is outside M1 and is unchanged: it still surfaces as a permanent StoreException. It is noted, not reviewed.

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 7+8 re-check limited to finding M1 (docs/agent-runs/phase-07/prompt.md, Repair limits)
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools)
TEST RESULTS: Not run by the reviewer. From the logs I read: pre-fix "Failed: Total 12, Failed 8, Errors 0, Passed 4" (m1-before.log:128); post-fix "Passed: Total 13, Failed 0" (m1-after.log:13); sbt check "[success] Total time: 792 s", markTestRun module_tests=1431, contract_tests=336, and AwsErrorMappingSuite 13 of 13 inside check (m1-check.log:174, 9663-9664). No non-zero failure count anywhere in m1-check.log.
VALIDATION RESULTS: M1 resolved at AwsSupport.scala:87-92, DynamoDbKeyValueStore.scala:46-79, S3ObjectStore.scala:46-53 and S3VectorsIndex.scala:21-30. No remaining dead-catch instance in storage or rag. The new suite is not tagged RequiresAws and runs in sbt check. 0 critical, 0 major, 2 new minor (recorded only).
ASSUMPTIONS: The SDK exception classes thrown by the stubs (ConditionalCheckFailedException, NoSuchKeyException, NotFoundException) match what the live services raise; this is consistent with SDK v2 models. Files changed only in modules/storage/**: inferred from the repair report, Glob modification-time order and the scalafmt file counts in the log, because git is unavailable to this reviewer and modules/storage/ is untracked.
RISKS: The fix is proven against stubs only. The live RequiresAws suite (AwsAdaptersAwsSuite) remains deferred. The S3 409 ConditionalRequestConflict behavior is outside M1 and unchanged.
MANUAL ACTION REQUIRED: The human reviews the two recorded minor findings and decides whether to defer them. Phase 7+8 human gate.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Human gate for Phase 7+8.
```
