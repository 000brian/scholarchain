STATUS: COMPLETE
OWNER: storage-engineer
WORK ORDER: Phase 7+8 repair round 1, finding M1: AWS adapter exception mapping (docs/agent-runs/phase-07/prompt.md standing rules apply)
FILES CREATED:
- /Users/brian/Developer/repos/scholarchain/modules/storage/src/test/scala/org/scholarchain/storage/AwsErrorMappingSuite.scala
FILES MODIFIED:
- /Users/brian/Developer/repos/scholarchain/modules/storage/src/main/scala/org/scholarchain/storage/aws/AwsSupport.scala
- /Users/brian/Developer/repos/scholarchain/modules/storage/src/main/scala/org/scholarchain/storage/aws/DynamoDbKeyValueStore.scala
- /Users/brian/Developer/repos/scholarchain/modules/storage/src/main/scala/org/scholarchain/storage/aws/S3ObjectStore.scala
- /Users/brian/Developer/repos/scholarchain/modules/storage/src/main/scala/org/scholarchain/storage/aws/S3VectorsIndex.scala
COMMANDS RUN:
- Read-only inspection: cat -n, sed -n, grep, ls, wc, tail, ps, git status --short.
- `sbt -batch "storage/testOnly org.scholarchain.storage.AwsErrorMappingSuite"` before the fix: exit=1. Failed 8 of 12, passed 4.
- The same command after the fix: exit=0. "Passed: Total 13, Failed 0, Errors 0, Passed 13".
- `sbt -batch check` (in the background, logged to the scratchpad file m1-check.log): exit=0, "[success] Total time: 792 s". markTestRun printed "Updated .../.claude/.last-test-run (module_tests=1431, contract_tests=336)".
TEST RESULTS:
- AwsErrorMappingSuite: 13 of 13 pass after the fix. Before the fix, 8 of 12 failed. The 13th test was added after the fix because it calls the new `AwsErrors.recover` (see TESTS ADDED).
- Full `sbt check`: all module and contract tests passed (1431 module, 336 contract). No RequiresAws test was run.
VALIDATION RESULTS: The scalafmt check passed inside `sbt check`, the ledger check passed, and the test marker was refreshed by markTestRun, not by hand.
ASSUMPTIONS:
- The stubs throw the same exception classes the real SDK v2 synchronous clients raise:
  - DynamoDB PutItem with a failed ConditionExpression: ConditionalCheckFailedException (HTTP 400).
  - S3 GetObject on a missing key: NoSuchKeyException (HTTP 404).
  - S3 Vectors GetIndex on a missing index: NotFoundException (HTTP 404).
  - S3 PutObject with If-None-Match: * on an existing key: S3Exception with HTTP 412.
- I could not re-check these against AWS documentation because the aws-docs MCP server failed to connect (uvx is not installed). They match the SDK model classes on the classpath and the earlier implementation's intent.
RISKS:
- S3 conditional PutObject can also return HTTP 409 (ConditionalRequestConflict) during a concurrent write to the same key. That still surfaces as a non-transient StoreException, as before. It is a separate behavior, outside M1, and not changed here.
- The fix is proven against stubs only. The RequiresAws suite `AwsAdaptersAwsSuite` is still the live check and was not run, per the standing rules.
MANUAL ACTION REQUIRED: None.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: The code-reviewer re-verifies M1, then the human gate for Phase 7+8.

## FINDING (M1 restated)
`AwsErrors.guard` catches every SdkException and rethrows it as StoreException. The adapters placed `catch` clauses for expected service outcomes outside `guard`, so those clauses could never match:
- ConditionalCheckFailedException in DynamoDbKeyValueStore.putIfAbsent and putIfVersion;
- NoSuchKeyException in S3ObjectStore.get;
- NotFoundException in S3VectorsIndex.describeIndex.

The effect on real AWS:
- conditional-write idempotency broke (ConditionalWriter threw instead of returning Unchanged or Conflict);
- RunLedger and ArtifactRegistry race paths broke;
- TextStore.fetch threw instead of returning Left;
- describeIndex threw instead of returning None.

## ROOT CAUSE
The exception was wrapped too early. In the old code, `try { AwsErrors.guard(client.call(...)) } catch { case _: ConditionalCheckFailedException => ... }` let guard turn the exception into StoreException(cause = CCFE) before the outer catch ran. StoreException is not a subtype of CCFE, so the outer clause was dead code. I confirmed this before the fix: the stub-thrown CCFE surfaced as `org.scholarchain.storage.StoreException: ConditionalCheckFailedException (HTTP 400): The conditional request failed`.

## FIX (file:line)
- `modules/storage/src/main/scala/org/scholarchain/storage/aws/AwsSupport.scala:87`: new `AwsErrors.recover[A](expected: PartialFunction[SdkException, A])(f: => A): A`.
  - It maps the listed SDK exceptions to results inside the same try.
  - Every other SdkException still goes through `wrap`, so the retry class is unchanged: throttling, 429 and 5xx are transient; client exceptions use `retryable()`; everything else is permanent.
  - `guard` (line 78) is unchanged and now carries a doc note pointing to `recover`.
  ```scala
  def recover[A](expected: PartialFunction[SdkException, A])(f: => A): A =
    try f
    catch {
      case e: SdkException if expected.isDefinedAt(e) => expected(e)
      case e: SdkException                            => throw wrap(e)
    }
  ```
- `aws/DynamoDbKeyValueStore.scala:46`: `conditionFailed` partial function (CCFE -> ConditionFailed).
  - putIfAbsent (line 51) and putIfVersion (line 64) now use `AwsErrors.recover(conditionFailed) { client.putItem(...); Written }`.
- `aws/S3ObjectStore.scala:46-47`: get uses `AwsErrors.recover { case _: NoSuchKeyException => None }`.
- `aws/S3VectorsIndex.scala:21-22`: describeIndex uses `AwsErrors.recover { case _: NotFoundException => None }`.

## TESTS ADDED
File: `modules/storage/src/test/scala/org/scholarchain/storage/AwsErrorMappingSuite.scala`. It has no RequiresAws tag, so `sbt check` runs it. It uses stub implementations of DynamoDbClient, S3Client and S3VectorsClient (Java interfaces with default methods) that throw the exact SDK exceptions.

1. DynamoDB putIfAbsent returns ConditionFailed on ConditionalCheckFailedException. Failed before the fix.
2. DynamoDB putIfVersion returns ConditionFailed on ConditionalCheckFailedException. Failed before the fix.
3. ConditionalWriter.upsertIfChanged on the AWS adapter: same hash is Unchanged (and only 1 put). Failed before the fix.
4. ConditionalWriter.upsertIfChanged on the AWS adapter: a lost version race is Conflict (putIfAbsent, then one putIfVersion). Failed before the fix.
5. ConditionalWriter.append on the AWS adapter: a rerun is Unchanged, other content is Conflict. Failed before the fix.
6. DynamoDB: other service errors still become StoreException with the right retry class (400 permanent, 503 transient, SdkClientException wrapped). Passed before and after.
7. S3 get returns None on NoSuchKeyException. Failed before the fix.
8. TextStore.fetch returns Left (not a throw) when the object is missing. Failed before the fix.
9. S3 get: other errors still become StoreException (403, permanent). Passed before and after.
10. S3 putIfAbsent maps HTTP 412 to AlreadyPresent or Conflict, and other errors (503, transient) to StoreException. Passed before and after; this is regression coverage, since this path was not defective.
11. S3 Vectors describeIndex returns None on NotFoundException. Failed before the fix.
12. S3 Vectors describeIndex: other errors still become StoreException (403, permanent). Passed before and after.
13. AwsErrors.guard wraps every SdkException; recover maps only the listed outcomes. It checks that other SdkExceptions are wrapped with the cause kept, and that a StoreException raised inside the block passes through unchanged. This test was added after the fix because it calls the new API, so it would not compile before the fix.

Failing-before output (pre-fix run, tests 1-12; ANSI codes removed, stack frames trimmed):
```
==> X org.scholarchain.storage.AwsErrorMappingSuite.DynamoDB putIfAbsent returns ConditionFailed on ConditionalCheckFailedException 0.025s org.scholarchain.storage.StoreException: ConditionalCheckFailedException (HTTP 400): The conditional request failed
Caused by: software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException: The conditional request failed
==> X org.scholarchain.storage.AwsErrorMappingSuite.DynamoDB putIfVersion returns ConditionFailed on ConditionalCheckFailedException 0.005s org.scholarchain.storage.StoreException: ConditionalCheckFailedException (HTTP 400): The conditional request failed
==> X org.scholarchain.storage.AwsErrorMappingSuite.ConditionalWriter.upsertIfChanged on the AWS adapter: same hash is Unchanged 0.001s org.scholarchain.storage.StoreException: ConditionalCheckFailedException (HTTP 400): The conditional request failed
==> X org.scholarchain.storage.AwsErrorMappingSuite.ConditionalWriter.upsertIfChanged on the AWS adapter: lost version race is Conflict 0.0s org.scholarchain.storage.StoreException: ConditionalCheckFailedException (HTTP 400): The conditional request failed
==> X org.scholarchain.storage.AwsErrorMappingSuite.ConditionalWriter.append on the AWS adapter: rerun is Unchanged, other content Conflict 0.001s org.scholarchain.storage.StoreException: ConditionalCheckFailedException (HTTP 400): The conditional request failed
==> X org.scholarchain.storage.AwsErrorMappingSuite.S3 get returns None on NoSuchKeyException 0.006s org.scholarchain.storage.StoreException: NoSuchKeyException (HTTP 404): missing
==> X org.scholarchain.storage.AwsErrorMappingSuite.TextStore.fetch returns Left (not a throw) when the object is missing 0.086s org.scholarchain.storage.StoreException: NoSuchKeyException (HTTP 404): missing
==> X org.scholarchain.storage.AwsErrorMappingSuite.S3 Vectors describeIndex returns None on NotFoundException 0.004s org.scholarchain.storage.StoreException: NotFoundException (HTTP 404): no index
[error] Failed: Total 12, Failed 8, Errors 0, Passed 4
```
After the fix: `[info] Passed: Total 13, Failed 0, Errors 0, Passed 13`. Inside `sbt check`: `Test run org.scholarchain.storage.AwsErrorMappingSuite finished: 0 failed, 0 ignored, 13 total`.

## OTHER INSTANCES FOUND
I reviewed every `AwsErrors.guard` call site and every `catch` in storage's AWS adapters, and the one call site in rag.
- There are no other instances of this defect class beyond the four methods in M1 (putIfAbsent, putIfVersion, S3 get, describeIndex), and all four are fixed.
- S3ObjectStore.putIfAbsent (the If-None-Match 412 mapping), openStream and head do not use `guard`. They catch the specific exception first and then call `AwsErrors.wrap` in the same try, so they were already correct. The 412 path now has a regression test (test 10).
- NeptuneGraphRead.run catches StoreException on purpose to return Left, which is correct. NeptuneBulkLoader, NeptuneMergeUpsert and SqsWorkQueue have no inner catches for specific exceptions.
- rag's `SdkBedrockInvoker.invoke` (modules/rag/.../BedrockTitanEmbedder.scala:28) uses `guard` with no inner catch, so it is not affected. It is outside my territory and was not touched.

## Scratchpad evidence (not repository files)
- Pre-fix log: /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/b5367363-771f-4e88-a146-71d68c534a31/scratchpad/phase-07/m1-before.log
- Post-fix log: /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/b5367363-771f-4e88-a146-71d68c534a31/scratchpad/phase-07/m1-after.log
- sbt check log: /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/b5367363-771f-4e88-a146-71d68c534a31/scratchpad/phase-07/m1-check.log
