## Summary

I added `notes: List[String] = Nil` as the last field of `JobRequest`, so a worker can rebuild the notes the sync path returns. Notes are validated on create, stored on the QS4 job item only when non-empty (a missing attribute reads as `Nil`), and counted in the existing item-size check. That works for both the in-memory table store and the DynamoDB adapter. No transition, result limit or other behaviour changed. Final `sbt check` passed (exit 0, 790 s).

STATUS: COMPLETE
OWNER: storage-engineer
WORK ORDER: docs/agent-runs/phase-10/prompt.md (sha256 c032785a6cbef13124c196fb3c42be52b43cfd6e10e712a1861553d1cde5f49a, verified), step 8 repair pass, finding S-1 (storage side only)
FILES CREATED: none
FILES MODIFIED:
- /Users/brian/Developer/repos/scholarchain/modules/storage/src/main/scala/org/scholarchain/storage/jobs/JobModel.scala
  - :48-59: `JobRequest.notes` field and its scaladoc.
  - :169-171: size note on `MaxResultBytes`.
  - :181-183: `JobLimits.MaxNotes = 32` and `MaxNoteChars = 500`.
- /Users/brian/Developer/repos/scholarchain/modules/storage/src/main/scala/org/scholarchain/storage/jobs/JobStore.scala
  - :227-228: `JobItems.Notes = "notes"` attribute name.
  - :267: `checkRequest` now calls `checkNotes`.
  - :270-291: new `checkNotes` method.
  - :332: `encode` writes the `notes` list of strings only when non-empty.
  - :379-385: `decode` reads it (absent means Nil; any other shape is a malformed-item `Left`).
  - :406: `decode` passes the notes into the `JobRequest` it builds.
- /Users/brian/Developer/repos/scholarchain/modules/storage/src/test/scala/org/scholarchain/storage/jobs/JobStoreSuite.scala
  - New tests in `JobStoreContract` at :288, :307, :319 and :326, so each runs in both `InMemoryJobStoreSuite` and `DynamoJobStoreSuite` (the second uses `ConditionalDynamoStub` behind the retry wrapper).
  - :346: the largest-item test now also carries the maximum notes.
COMMANDS RUN:
- `sbt -batch "storage/testOnly org.scholarchain.storage.jobs.* org.scholarchain.storage.DynamoSuite"`: exit 0, 92 passed, 0 failed.
- `sbt -batch "storage/testOnly org.scholarchain.storage.jobs.*JobStoreSuite"` with a temporary mutation: exit 1, 48 total, 4 failed (details below).
- The same targeted storage run plus `services/testOnly org.scholarchain.services.*` after restoring the source: storage 92 passed / 0 failed; services 88 passed / 0 failed.
- `sbt -batch check` in the background: exit 0, `[success] Total time: 790 s`. `markTestRun` updated `.claude/.last-test-run` (module_tests=1873, contract_tests=336).
- Read-only shell commands only: grep, sed -n, cat, wc, tail, shasum, plus bounded wait loops on the background output file. No background job is left running.
TEST RESULTS:
- Targeted storage tests: 92/92 passed.
  - DynamoJobStoreSuite 25 (was 21), InMemoryJobStoreSuite 23 (was 19).
  - DynamoSuite 13, InMemoryIdempotencyStoreSuite 8, DynamoIdempotencyStoreSuite 10, InMemoryWorkQueueSuite 6, ServiceStoresSuite 7.
- Services (direct user): 88/88 passed.
- `sbt check`: passed; 1873 module tests and 336 contract tests counted by markTestRun.
VALIDATION RESULTS: `sbt check` covers format checking and compilation of all projects; both passed.
ASSUMPTIONS:
- The bounds are 32 notes of at most 500 characters each. Services today produces at most one note per retrieval or analysis field (a handful), each about 85 characters.
- "Control character" means `Character.isISOControl` (U+0000-001F and U+007F-009F), so tab and newline are rejected too.
- Notes are stored in the order services gives them; storage does not sort or deduplicate them.
RISKS:
- A worst-case job item is about 355 KB, under the 400 KB DynamoDB limit: question 6 KB + notes 32 x 500 x 3 bytes = 48 KB + 300,000-byte result + small fields. The largest-item test checks this with 3-byte characters, and `encode` still refuses any item over the limit.
- Nothing is persisted for notes until service-engineer fills the field in `toJobRequest`, so S-1 stays open until that pass.
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE:
- service-engineer repair pass: fill `notes` in `ValidatedQuery.toJobRequest` and rebuild notes from `job.request.notes` in `ofJob`.
- Then code-reviewer re-checks S-1.

## Tests that fail without the change

I confirmed this by temporary mutation, then restored the source and re-ran (all green, listed above).
- Mutation: I turned off the `encode` notes line and the `checkNotes(r.notes)` call.
- Result: `storage/testOnly org.scholarchain.storage.jobs.*JobStoreSuite` gave `Failed: Total 48, Failed 4`.
  - "notes round-trip through create, get and every later write, in order" failed in both DynamoJobStoreSuite and InMemoryJobStoreSuite (ComparisonFailException).
  - "notes bounds: count, length, empty, null and control characters are Left" failed in both suites (FailException).
- Two tests still passed under that mutation, because it left `decode` untouched:
  - "no notes: the attribute is absent and an absent attribute reads as Nil";
  - "a stored notes attribute that is not a list of strings is a malformed item".
  Without the field itself, neither compiles.

## Idempotency evidence

The `notes` attribute is written in the same conditional put as the rest of the item, so the write-token and version idempotency is unchanged. Because every transition is a `copy` of the record, notes survive claim and succeed; the round-trip test checks this.

INTERFACE HANDOFF
- Files changed: the three listed under FILES MODIFIED. modules/services is not touched.
- Changed public type (package `org.scholarchain.storage.jobs`):
  ```scala
  final case class JobRequest(
      question: String,
      windowLabel: Option[String],
      topic: Option[String],
      includeEvidence: Boolean,
      notes: List[String] = Nil
  )
  ```
  Existing calls with four arguments, positional or named, still compile. The services tests passed without edits.
- New bounds: `JobLimits.MaxNotes: Int = 32` and `JobLimits.MaxNoteChars: Int = 500`.
- Validation on create: `TableJobStore.create` returns `Left(String)` and writes nothing when:
  - `notes` is null;
  - there are more than 32 notes;
  - any note is null, empty or over 500 characters;
  - any note contains an ISO control character.

  Error texts:
  - "notes must be a list (Nil when there are none)"
  - "at most 32 notes"
  - "each note must have 1..500 characters"
  - "a note must not contain a control character"
- Item format:
  - Attribute `notes` is a DynamoDB L of S, in the given order, written only when non-empty.
  - Absent means `Nil`, so older items and the `ConditionalDynamoStub` stay compatible.
  - An attribute of any other shape makes `get` return `Left("malformed QuerySession job item ...")`.
- Thread-safety: unchanged. `JobRequest` is immutable. `TableJobStore` is thread-safe when its `KeyValueStore` is; the in-memory and DynamoDB adapters both are.
- Test fixtures: unchanged. `ServiceStoreFixtures.request` still has `notes = Nil`; use `request.copy(notes = ...)` for a notes case.
- Known limitations:
  - Storage does not check note content against request fields; services owns the note text.
  - Storage keeps notes exactly as given, so services must pass them in the order the sync path returns (today sorted by field name).
  - Jobs created before this change read back with `Nil` notes.
  - Nothing changed in the QS5 idempotency fingerprint.

Logs are in /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/aaf86ae1-6986-4770-9813-b764b8dca793/scratchpad:
- /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/aaf86ae1-6986-4770-9813-b764b8dca793/scratchpad/storage-check.log
- /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/aaf86ae1-6986-4770-9813-b764b8dca793/scratchpad/storage-mutation.log
- /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/aaf86ae1-6986-4770-9813-b764b8dca793/scratchpad/storage-targeted2.log
