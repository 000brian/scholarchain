STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: docs/agent-runs/phase-10/prompt.md, step 9: re-check of the repaired findings only. U-1 and U-2 come from review-report.md (repair: repair-01.md, rag-engineer). S-1 and S-2 come from review-report-02.md (repairs: repair-02.md by storage-engineer, then repair-03.md by service-engineer). I read the "Standing rules" at prompt.md:5-33.
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools: Read, Grep, Glob)
TEST RESULTS: I ran no tests. These are the `sbt check` results I read in the logs:
- After the last repair, /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/aaf86ae1-6986-4770-9813-b764b8dca793/scratchpad/sbt-check-s8.log shows a pass:
  - :9367 `Module tests: suites=109 executed=1877 passed=1877 failed=0 errors=0 skipped=3`
  - :9929 `Contract tests (no AWS): suites=22 executed=336 passed=336 failed=0 errors=0 skipped=0`
  - :9930 `Updated .../.claude/.last-test-run (module_tests=1877, contract_tests=336)`
  - :9931 `[success] Total time: 1042 s`
  - :9932 `EXIT 0`
  - The log has no `[error]` line.
- Suites inside that run, each with 0 failed and 0 ignored:

| Suite | Tests | Log line |
|---|---|---|
| GoldenExamplesSuite | 17 | :28 |
| QueryApiSuite | 10 | :73 |
| IdempotencySuite | 11 | :88 |
| InMemoryJobStoreSuite | 23 | :2014 |
| DynamoJobStoreSuite | 25 | :2150 |
| QueryWorkflowSuite | 25 | :2060 |
| RuntimeServiceSuite | 14 | :2172 |

- Earlier passes also passed:
  - check.log (rag repair): 1865/1865 module tests, 336/336 contract tests, `[success] Total time: 787 s` (:9926-9928).
  - storage-check.log (storage repair): 1873/1873 module tests, 336/336 contract tests, `[success] 790 s` (:9926-9928).
- The counts add up: 1865 + 8 storage tests (4 new tests × 2 contract suites) = 1873, + 4 services tests = 1877.
VALIDATION RESULTS: I checked each repair against the current source (rulings below). Formatting was checked inside the final `sbt check` (sbt-check-s8.log:6-10), and the ledger check passed.
ASSUMPTIONS:
- I can't run git, and the changed trees are untracked. So I judged territory from the repair reports' file lists and from reading the changed code.
- I took the "fails without the fix" mutations as described in the reports, and checked from the code that each test really runs the repaired path.
RISKS: None blocking. R-1 below is advisory.
MANUAL ACTION REQUIRED:
- Ratify the two-pass S-1 routing (storage repair-02, then services repair-03), as repair-03 asks.
- Note R-1 (advisory).
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Human phase gate for Phase 10. The minor and advisory findings U-3 to U-13, S-3 to S-11 and R-1 stay listed for you.

VERDICT: APPROVE

All four repaired major findings are RESOLVED. No critical or major finding remains.

## Rulings

### U-1 (MAJOR, rag-engineer): RESOLVED
- **The fix does what the finding asked.**
  - QueryWorkflow.scala:469: `_ <- QueryWorkflow.bundleWriteFailure(bundleWrite).toLeft(())` is the first step of the final for-comprehension. A `Left` from `putBundle` now makes `run` return Left with the prefix "evidence bundle write failed:" (:713, :721-722).
  - QueryWorkflow.scala:480: `aw <- auditStage.append(audit).left.map(e => s"$AuditWriteFailedPrefix ${e.take(500)}")` sits inside the for-comprehension, so a failed audit append is now a Left. Before the repair its result was discarded.
  - On the Right path, :497 puts `Right(aw)` in the result.
  - Both failure texts are cut to 500 characters.
- **Contracts it touches are intact.**
  - A Conflict and `itemConflicts` are still a Right. `conflictsOf` (:706-710) is unchanged and still feeds the note at :324-325. `bundleWriteFailure` looks only at `_.left`, so a successful write that carries a Conflict never fails.
  - The m-4 test (QueryWorkflowSuite:172-218) is still present and passes.
  - Services still maps any Left from `run` to a failure: QueryService.scala:173-181 gives `Outcome.Failed`, then :339-342 records a breaker failure, marks the job WORKFLOW_FAILED and returns `ApiError.downstream`, which is 502 retryable (Errors.scala:77). The worker still releases on Left, which I did not change and did not re-read beyond the review-02 ruling.
  - The doc comments at :42-47 and :124-133 match the behaviour.
- **The tests are meaningful.**
  - QueryWorkflowSuite:231-257 uses `FlakyStores.failEvidenceWrites`. The fixture throws only on writes to `evidenceBundleTable` (RuntimeServiceFixtures.scala:134-140). The test asserts:
    - the result is a Left that starts with BundleWriteFailedPrefix and contains the injected cause, which proves the Left came from `putBundle`, not from the generic `workflow failed:` catch;
    - no audit was written;
    - a retry gets the same run ID, a Right, and a bundle that can be read back;
    - the helper returns None for a None write and for a successful write.
  - QueryWorkflowSuite:259-276 does the same for the audit append. It asserts the prefix, 0 audits, then after a retry `storeConflicts == Nil` (the bundle was stored unchanged) and exactly 1 audit.
  - Without :469 or :480, both `assert(l.isLeft)` lines (:237, :265) would fail.
- **Territory:** only modules/rag/** was changed: QueryWorkflow.scala, QueryWorkflowSuite, RuntimeServiceSuite and RuntimeServiceFixtures. The fixture changes are additive switches.

### U-2 (MAJOR, rag-engineer): RESOLVED
- **The production `close()` is unchanged and correct.** RuntimeService.scala:75-80 uses `compareAndSet`, then calls `owned.foreach` and swallows NonFatal exceptions.
- **RuntimeServiceSuite:301-325 (`fromStores`)** passes two counting closeables; the first one throws.
  - Before any close: (0,0). After the first `close()`: (1,1). After the second: (1,1).
  - This test fails if the body is reduced to `closed.set(true)` (0,0), if the idempotency guard is removed (2,2), or if one throwing closeable stops the loop (1,0).
- **RuntimeServiceSuite:327-377 (`aws`)** passes counting stub S3, DynamoDB, S3 Vectors, Neptune data and SQS clients through `AwsOptions.clients`.
  - It asserts that each is closed exactly once after two `close()` calls, and that the LLM and embedder transports made 0 calls.
  - This exercises the ownership chain from `RuntimeService` through `AwsStorageEnvironment` to the SDK clients.
- Both tests passed in the final check (RuntimeServiceSuite 14/14).

### S-1 (MAJOR, service-engineer, with a storage-engineer prerequisite): RESOLVED
- **Storage side** (modules/storage/** only):
  - **The field:** `JobRequest.notes: List[String] = Nil` is the last field, with a default (JobModel.scala:54-60). The bounds are `MaxNotes = 32` and `MaxNoteChars = 500` (:182-183).
  - **Validation on create:** `checkNotes` (JobStore.scala:273-291) rejects a null list, more than 32 notes, a null, empty or over-long note, and any `isISOControl` character. `checkRequest` calls it (:267), and so does `validateNew` (:303).
  - **Encoding:** `encode` writes an `L` of `S` values in the given order, only when the list is non-empty (:332). The item-size guard is still applied after it (:343-347).
  - **Decoding:** an absent attribute reads as Nil, and any other shape is a malformed-item Left (:379-385, :406).
  - **DynamoDB support:** `AttrValue.L` is already handled by the DynamoDB adapter (DynamoDbKeyValueStore.scala:21, 29), by ItemCodec and by ItemSize.
  - **Tests** (JobStoreSuite:288-344, in the shared contract, so they run in both the in-memory and the DynamoDB-stub suites):
    - the round-trip keeps order through create, claim and succeed;
    - an empty list writes no attribute, and a legacy item reads as Nil;
    - a malformed attribute is rejected;
    - every bound is tested, and nothing is written for a rejected job.
  - **Item size:** the largest-item test (:346-360) now carries 32 × 500 three-byte characters and stays under 400 KB.
- **Services side** (modules/services/** only):
  - **The fix:** `toJobRequest` passes `notes` (QueryRequestParser.scala:44). `ofJob` sets `storedNotes = Some(r.notes)` (:85-95). `notes` prefers `storedNotes`, otherwise it derives the notes sorted as before (:47-51). Worker.scala:133 still builds the query with `ofJob`, so the worker's result now carries the stored notes.
  - **Order:** the sync path and the stored path use the same `notes` list in the same sorted order, and storage keeps that order.
  - **Bounds:** services produces at most 8 notes (3 `RetrievalFields` + 5 `AnalysisFields`, QueryRequestParser.scala:299-306; unknown fields are 400). Each is under 100 characters with no control characters, well inside 32 × 500.
  - **Fingerprint unaffected:** `fingerprint` (:54-76) does not read `storedNotes`. Grep shows `fingerprint` is computed only on parsed `vq` values (QueryService.scala:270, 401, 441) and never on an `ofJob` result.
  - **Golden examples unchanged:** GoldenExamplesSuite passed 17/17 byte for byte in the final check (sbt-check-s8.log:28).
  - **The test** is QueryApiSuite:214-236. It sends the spec example request async, lets the worker run it to `Succeeded`, then sends the same request sync. It asserts that the sync result has 8 notes, that the async notes equal the sync notes, and that the two bodies are byte-identical.
    - Without `notes` in `toJobRequest`, the stored notes are Nil, the worker's result has `notes: []`, and :233 fails.
    - The test runs the exact path the finding described.

### S-2 (MAJOR, service-engineer): RESOLVED
- **The wrapper** is `LostClaimStores` (IdempotencySuite.scala:321-362), installed through the existing `harness(wrapStores = ...)` hook (ServiceTestKit.scala:152, 162). It forces the lost-claim branch deterministically:
  - `blindGet` makes `idempotency.get` return `None`, so QueryService.scala:251 (sync) and :381 (async) take the `None` path.
  - The real put-if-absent `claim` then finds the winner's record and returns `Existing`, which is the branch at :275-276 and :284 (sync) and at :405-412 (async). Nothing depends on timing.
  - `losers.size == 1` in each test proves the branch was taken.
- **Sync test** (:206-239). It asserts:
  - the losing job is a different job with mode Sync, `Failed` with `SUPERSEDED`;
  - the response is 200 with `Idempotent-Replayed: true` and a body byte-equal to the winner's;
  - the audit count stays at 1 (no second workflow run);
  - `freePermits == maxConcurrentQueries` (`unadmit` happened);
  - the key still points at the winner.

  A regression that returns `Right(Right(running))` would leave the loser `Running` and add an audit, so :223 and :230 would fail. A regression that supersedes but still runs the workflow would fail at :227 and :230.
- **Async test** (:241-276). It asserts:
  - the loser is SUPERSEDED with 0 attempts;
  - the 202 has the winner's jobId, status SUCCEEDED, and is marked replayed;
  - the worker then returns `AckedWithoutRun`, `ackedCount` goes up by 1 and the queue is empty;
  - the audit count stays at 1.
- **Async test, worker-claimed loser** (:278-300). The `beforeExisting` hook claims the losing job to `Running` before the `Existing` result returns. The test asserts that the job stays `Running` with no error, and that the response is 409 IDEMPOTENCY_IN_PROGRESS. This pins the `j.state == JobState.Queued` guard at QueryService.scala:408.
- **Production code is unchanged:** QueryService.scala:275-276, 284 and 405-412 read as reviewed in review-report-02. The mutations were temporary and have been reverted.
- **Territory:** only modules/services/** was changed: QueryRequestParser.scala, QueryApiSuite, IdempotencySuite.

## New findings (recorded only, not repaired in this phase)

**R-1. ADVISORY. Owner: human decision, then service-engineer. A 502 marked "retryable" after U-1 leaves a keyed sync request permanently failed for its key.**
- **Evidence:**
  - On `Outcome.Failed`, QueryService.scala:339-342 marks the sync job `WORKFLOW_FAILED` and returns `ApiError.downstream`, which Errors.scala:77 marks retryable (`ErrorCode(502, true, ...)`).
  - A retry with the same `Idempotency-Key` goes to `follow`, and a `Failed` job gives 409 JOB_FAILED (QueryService.scala:458) until the job's TTL ends.
- **Scenario:** A transient evidence-store throttle during a keyed sync query now gives a Left because of U-1, so the client gets 502 retryable. The client retries with the same key, as the retryable flag suggests, and gets 409 JOB_FAILED. Only a new key reaches the now-safe deterministic retry.
- **Why advisory:** This follows the human's decision 7e ("failed gives 409 JOB_FAILED") and is not a defect of any repair. U-1 only makes this path reachable through store-write failures as well. The async path is not affected, because the worker releases the job and retries it through the queue.
- **Possible later options:** treat `WORKFLOW_FAILED` with a retryable cause as re-runnable for the same key, or document that clients must use a new key after a 502.

No other new findings. I found no contract drift, ownership violation, or test that would pass with its fix removed in the four repaired areas.

Relevant files:
- /Users/brian/Developer/repos/scholarchain/modules/rag/src/main/scala/org/scholarchain/rag/runtime/QueryWorkflow.scala
- /Users/brian/Developer/repos/scholarchain/modules/rag/src/main/scala/org/scholarchain/rag/runtime/RuntimeService.scala
- /Users/brian/Developer/repos/scholarchain/modules/rag/src/test/scala/org/scholarchain/rag/runtime/QueryWorkflowSuite.scala
- /Users/brian/Developer/repos/scholarchain/modules/rag/src/test/scala/org/scholarchain/rag/runtime/RuntimeServiceSuite.scala
- /Users/brian/Developer/repos/scholarchain/modules/rag/src/test/scala/org/scholarchain/rag/runtime/RuntimeServiceFixtures.scala
- /Users/brian/Developer/repos/scholarchain/modules/storage/src/main/scala/org/scholarchain/storage/jobs/JobModel.scala
- /Users/brian/Developer/repos/scholarchain/modules/storage/src/main/scala/org/scholarchain/storage/jobs/JobStore.scala
- /Users/brian/Developer/repos/scholarchain/modules/storage/src/test/scala/org/scholarchain/storage/jobs/JobStoreSuite.scala
- /Users/brian/Developer/repos/scholarchain/modules/services/src/main/scala/org/scholarchain/services/QueryRequestParser.scala
- /Users/brian/Developer/repos/scholarchain/modules/services/src/main/scala/org/scholarchain/services/QueryService.scala
- /Users/brian/Developer/repos/scholarchain/modules/services/src/test/scala/org/scholarchain/services/QueryApiSuite.scala
- /Users/brian/Developer/repos/scholarchain/modules/services/src/test/scala/org/scholarchain/services/IdempotencySuite.scala
- /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/aaf86ae1-6986-4770-9813-b764b8dca793/scratchpad/sbt-check-s8.log
