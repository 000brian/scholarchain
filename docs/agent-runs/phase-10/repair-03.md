Both major findings are repaired. S-1 needed a fix in `ValidatedQuery`. S-2 needed only tests: the new deterministic tests passed on the existing code, so they found no defect. Each new test fails when I temporarily break the fix it covers. The final `sbt check` passed (EXIT 0, `[success] Total time: 1042 s`), and markTestRun reported module_tests=1877 and contract_tests=336. Services now has 92 tests, all passing (88 before this pass, 4 added). No golden example changed.

STATUS: COMPLETE
OWNER: service-engineer
WORK ORDER: docs/agent-runs/phase-10/prompt.md, sha256 c032785a6cbef13124c196fb3c42be52b43cfd6e10e712a1861553d1cde5f49a (checked; matches). Step 8 repair pass for S-1 and S-2 from review-report-02.md. Decisions 7c and 7e. I did not repair any minor or advisory finding (S-3 to S-11).
FILES CREATED: none
FILES MODIFIED:
- /Users/brian/Developer/repos/scholarchain/modules/services/src/main/scala/org/scholarchain/services/QueryRequestParser.scala
- /Users/brian/Developer/repos/scholarchain/modules/services/src/test/scala/org/scholarchain/services/QueryApiSuite.scala
- /Users/brian/Developer/repos/scholarchain/modules/services/src/test/scala/org/scholarchain/services/IdempotencySuite.scala
- QueryService.scala was edited only for the temporary mutations. It is back to its original text: I checked lines 276, 408 and 409 with grep. The services tree is untracked, so git diff cannot show this.
COMMANDS RUN:
- `shasum -a 256` on the work order.
- Read-only grep, sed -n, find, tail and ls.
- `sbt -batch "services/testOnly org.scholarchain.services.IdempotencySuite org.scholarchain.services.QueryApiSuite"`, four times:
  - Run 1: 1 failure, a bug in my own new test (it read through the wrapper while `get` was still forced to miss). I fixed the test.
  - Run 2: 21/21 passed.
  - Run 3 (mutation set 1): 4 of 21 failed, as expected.
- `sbt -batch "services/testOnly org.scholarchain.services.IdempotencySuite"` (mutation set 2): 3 of 11 failed, as expected.
- `sbt -batch check` once, in the background, logged to /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/aaf86ae1-6986-4770-9813-b764b8dca793/scratchpad/sbt-check-s8.log, followed with bounded wait loops that end on their own. No background job is left running.
TEST RESULTS:
- Targeted run after the repairs: QueryApiSuite 10/10, IdempotencySuite 11/11 (21 total, 0 failed).
- Services suites inside `sbt check`, all 0 failed and 0 ignored:

| Suite | Tests |
|---|---|
| GoldenExamplesSuite | 17 |
| ComponentsSuite | 8 |
| CompositionSuite | 5 |
| CapacityAndFailureSuite | 7 |
| ReadRoutesSuite | 9 |
| QueryApiSuite | 10 |
| WorkerSuite | 11 |
| IdempotencySuite | 11 |
| HttpBehaviorSuite | 14 |
| **Total** | **92** |

- `sbt check`: EXIT 0, `[success] Total time: 1042 s`. Its log line `Updated .claude/.last-test-run (module_tests=1877, contract_tests=336)` was observed; the marker file is timestamped 06:10.
VALIDATION RESULTS: The scalafmt check passed inside `sbt check`. The GoldenExamplesSuite byte-for-byte comparison passed without regenerating anything. The golden requests carry no `retrieval` or `analysis` fields, so their `notes` stay `[]` and no frozen file changes.
ASSUMPTIONS:
- The job stores the rendered notes (storage's `JobRequest.notes`), not the raw retrieval and analysis values. The worker reuses them as they are.
- The fingerprint is computed only from parsed requests. `ofJob` queries never feed a fingerprint, so the empty maps there do not matter.
- The most notes services produces is 8, each about 85 characters, which is within storage's limits of 32 notes and 500 characters.
RISKS:
- A job stored before this change (only possible in memory today) reads `notes` as `Nil`, so its result would carry no notes. No persistent jobs exist yet.
- The old race test "two concurrent first requests with one key" is still timing-dependent. The new tests are the deterministic cover.
MANUAL ACTION REQUIRED: Ratify the S-1 routing at the gate: storage repair-02 plus this services repair.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Step 9: code-reviewer re-checks S-1 and S-2 only.

## S-1: async completed result now equals the sync result

**What changed** (QueryRequestParser.scala):
- `ValidatedQuery` has a new last parameter, `storedNotes: Option[List[String]] = None` (line 35, scaladoc lines 23-25).
- `notes` (lines 47-51) returns `storedNotes` when present. Otherwise it derives the notes from `retrieval` and `analysis` as before: sorted, same text.
- `toJobRequest` (line 44) is now `JobRequest(question, windowLabel, topic, includeEvidence, notes)`, so the job holds exactly the notes the sync path returns, in the same order.
- `ofJob` (lines 85-95) passes `Some(r.notes)`, so the worker rebuilds the notes from `job.request.notes`.
- No other code changed. `WorkflowRunner` already builds the result from `query.notes`.

**Test:** QueryApiSuite:214, "the spec's example request sent async: the completed result equals the sync one (notes)".
- It loads `api/v1/spec-example-request.json` with the existing config copy (`graphResultLimit` 100) and adds `"mode": "async"`.
- It POSTs and expects 202, then expects `worker.processOne() == Succeeded`, then GETs `/v1/jobs/{id}/result`.
- It sends the same request sync and asserts: the sync result has 8 notes, the async notes equal the sync notes, and the two bodies are byte-identical.

**How I confirmed it fails without the fix:** I temporarily changed `toJobRequest` back to `JobRequest(question, windowLabel, topic, includeEvidence)`. The test failed at QueryApiSuite:233, the async-versus-sync notes comparison. I then restored the fix and the test passed.

**Corrected deviation 2 text** (replaces item 2 under "Deviations" in agent-report-service.md):
> 2. The completed result has a fourth field, `notes`. It lists every accepted but unapplied field (`retrieval.*`, `analysis.*`), sorted, as decision 7c asks. The sync path derives the notes from the validated request. `toJobRequest` stores those same notes, in the same order, in storage's `JobRequest.notes` (storage repair-02), and the worker rebuilds them from `job.request.notes` (`ValidatedQuery.ofJob`). So an async completed result carries the same notes as the sync result for the same request. QueryApiSuite tests this with the spec example request, comparing the bodies byte for byte. (The 6a text claimed this before it was true: until this repair, async results always had `notes: []`.)

## S-2: deterministic tests of the 7e lost-claim branch

**Code defect found:** none. The tests passed on the existing QueryService.scala (lost-claim handling at lines 275-276 and 284 for sync, 405-412 for async).

**Fixture:** `LostClaimStores` (IdempotencySuite.scala:321, package `org.scholarchain.services`, test scope).
- It wraps a `ServiceStores`, installed with `harness(wrapStores = lost.over)`.
- `blindGet` (an AtomicBoolean) makes `idempotency.get` return `None`.
- `claim` delegates to the real store. When the record already exists, the real store returns `Existing(rec)`, and the wrapper adds the losing job ID to `losers`.
- `beforeExisting` is a hook that runs just before a lost claim returns.

**Tests added** (IdempotencySuite.scala):
1. Line 206, "sync, lost claim: own job SUPERSEDED, response follows the winner, no extra workflow run".
   - Setup: the winner POSTs and gets 200 (audit count 1), then `blindGet` is turned on and the same request is sent again.
   - Asserts that exactly one lost claim happened.
   - Asserts that the losing job is a different sync job, `Failed` with `SUPERSEDED`.
   - Asserts that the response is 200 with `Idempotent-Replayed: true` and a body byte-equal to the winner's.
   - Asserts that the audit count is still 1 (0 extra workflow runs) and that `freePermits == maxConcurrentQueries`, so the permit and breaker slot were returned.
   - Asserts that the key still points at the winner's record.
2. Line 241, "async, lost claim: queued job SUPERSEDED, its message acked unrun, 202 follows the winner".
   - Setup: the winner POSTs async and gets 202, the worker runs it to Succeeded, then `blindGet` is turned on and the same request is sent again.
   - Asserts that the losing job is `Failed` with `SUPERSEDED` and 0 attempts.
   - Asserts that the response is 202 with the winner's jobId, `status` SUCCEEDED and `Idempotent-Replayed: true`.
   - Asserts that the queue holds 1 message, that the next `processOne()` returns `AckedWithoutRun`, that `ackedCount` went up by 1, and that the queue is then empty.
   - Asserts that the losing job is still SUPERSEDED and the audit count is still 1.
3. Line 278, "async, lost claim: a losing job a worker already claimed is not superseded". This covers the review's other regression case.
   - Setup: the `beforeExisting` hook claims the losing job to running, as a worker would between the enqueue and the lost claim.
   - Asserts that the losing job stays `Running` with no error.
   - Asserts that the response is 409 IDEMPOTENCY_IN_PROGRESS, because the winner is still queued.

**How I confirmed the tests fail without the behaviour** (temporary edits to QueryService.scala, then restored and checked with grep):
- Mutation set 1, run together with the S-1 revert (the tests do not overlap):
  - Sync `Existing` returns `Right(Right(running))` without superseding, so a second workflow runs. Test 1 failed at :223 (state is Running, not Failed).
  - Async never supersedes (the `Queued` case does nothing). Test 2 failed at :259 (state is Queued, not Failed).
- Mutation set 2:
  - Sync supersedes but still runs the workflow (`failJob(...); Right(Right(running))`). Test 1 failed at :227: no `Idempotent-Replayed` header, because a fresh run answered.
  - Async supersedes in any state (the `j.state == Queued` guard removed). Test 3 failed at :294 (state is Failed, not Running).
- The old timing-dependent race test (:196) also failed under both mutation sets, because the race happened in those runs.
- With everything restored: 21/21 targeted, then 92/92 services inside `sbt check`.

## Golden examples
Unchanged. I did not run GoldenExampleWriter. GoldenExamplesSuite passed 17/17 byte for byte.

## INTERFACE HANDOFF
- **Files changed:** QueryRequestParser.scala (main), QueryApiSuite.scala and IdempotencySuite.scala (test), all under /Users/brian/Developer/repos/scholarchain/modules/services/src/.
- **Changed public signature:** `final case class ValidatedQuery(question, windowLabel, topic, mode: JobMode, includeEvidence: Boolean, retrieval: Map[String, Int], analysis: Map[String, ujson.Value], storedNotes: Option[List[String]] = None)`.
  - The new parameter is last and has a default, so existing positional calls still compile.
  - `notes` returns `storedNotes`, or the derived notes when there are none.
  - `toJobRequest` now fills `JobRequest.notes`.
  - `ValidatedQuery.ofJob(r, mode)` sets `storedNotes = Some(r.notes)`.
  - `fingerprint` is unchanged and ignores `storedNotes`.
- **New test fixture:** `org.scholarchain.services.LostClaimStores` (services test scope). It has `blindGet: AtomicBoolean`, `losers: CopyOnWriteArrayList[String]`, `beforeExisting: AtomicReference[String => Unit]`, and `over(base: ServiceStores): ServiceStores`.
- **Thread-safety:** `ValidatedQuery` is immutable. `LostClaimStores` uses only atomics and a copy-on-write list and delegates to the thread-safe storage stores.
- **Known limitations:**
  - Jobs written before storage repair-02 read `notes = Nil`.
  - The notes are stored in rendered form. If the note wording changes, results for jobs already queued keep the old wording until they finish.
  - S-3 to S-11 remain open for the human, as instructed.
