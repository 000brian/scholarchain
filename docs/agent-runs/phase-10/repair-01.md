STATUS: COMPLETE
OWNER: rag-engineer
WORK ORDER: docs/agent-runs/phase-10/prompt.md (sha256 c032785a6cbef13124c196fb3c42be52b43cfd6e10e712a1861553d1cde5f49a, confirmed with shasum). Phase 10 step 8 repair pass, findings U-1 and U-2 only.
FILES CREATED: none
FILES MODIFIED:
- /Users/brian/Developer/repos/scholarchain/modules/rag/src/main/scala/org/scholarchain/rag/runtime/QueryWorkflow.scala
- /Users/brian/Developer/repos/scholarchain/modules/rag/src/test/scala/org/scholarchain/rag/runtime/QueryWorkflowSuite.scala
- /Users/brian/Developer/repos/scholarchain/modules/rag/src/test/scala/org/scholarchain/rag/runtime/RuntimeServiceSuite.scala
- /Users/brian/Developer/repos/scholarchain/modules/rag/src/test/scala/org/scholarchain/rag/runtime/RuntimeServiceFixtures.scala (additive test-fixture switches only)
- RuntimeService.scala: I changed it for the without-fix check and then put it back by hand, byte for byte. It ends up unchanged. I checked this by reading the close() body at lines 74-80; there is no diff because the file is untracked.
- No changes to prompts/**, modules/services/** or docs/agent-runs/**.
COMMANDS RUN:
1. sbt -batch "rag/testOnly org.scholarchain.rag.runtime.QueryWorkflowSuite org.scholarchain.rag.runtime.RuntimeServiceSuite": the first run failed to compile because `aw` was the wrong type. I fixed that and reran it: exit 0, "Passed: Total 39, Failed 0".
2. The same command with both fixes temporarily reverted: exit 1, "Failed: Total 39, Failed 4, Passed 35". The 4 failures were exactly the 4 new tests. The fixes were then restored.
3. sbt -batch "services/testOnly org.scholarchain.services.*": exit 0, "Passed: Total 88, Failed 0". This includes GoldenExamplesSuite, 17/17.
4. sbt -batch check (background, log in /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/aaf86ae1-6986-4770-9813-b764b8dca793/scratchpad/check.log): exit 0, "[success] Total time: 787 s", and "Updated .claude/.last-test-run (module_tests=1865, contract_tests=336)". No suite reported a non-zero failed count.
5. Read-only commands: shasum, grep, sed -n, tail, cat, git status/diff, pgrep.
TEST RESULTS:
- rag targeted suites: QueryWorkflowSuite 25/25 (was 23) and RuntimeServiceSuite 14/14 (was 12).
- services: 88/88.
- sbt check: passed, with module_tests=1865 and contract_tests=336, and QueryWorkflowSuite 25/25 and RuntimeServiceSuite 14/14 inside it.
- Without the fixes: 4 failed out of 39.
VALIDATION RESULTS:
- The fake benchmark was not rerun, as the work order says.
- The prompts and the fake model's output are unchanged. services' GoldenExamplesSuite passes 17/17, which pins the v2 prompt sha256 values and the fake model's output.
ASSUMPTIONS:
- The WorkflowResult.bundleWrite and auditWrite field types are kept unchanged so services still compiles. In a returned Right they are never Left.
- A Left now has two new message prefixes. services already maps any Left from run to 502 / retryable.
RISKS:
- A failed bundle write may be partial: some EB1 items stored, but not EB3. The retry stays safe because both writes are put-if-absent and the content is deterministic, so the stored items come back as Unchanged.
- If the audit append fails after the bundle was stored, the bundle stays stored with no audit until a retry. The review accepted this as harmless.
- Process note: pgrep shows two old `tail -n +1 -f .../scratchpad/sbt-check.log | grep -m1 "EXIT="` watchers, PIDs 11634 / 11633 and 15401. I did not start them: they watch sbt-check.log, and my log is check.log. I left them alone. The orchestrator may want to stop them.
- None of my sbt processes or background jobs remain.
MANUAL ACTION REQUIRED: none. Optional: stop the stray tail -f watchers above.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Step 9: code-reviewer re-checks U-1 and U-2 only.

## Per finding

U-1 (MAJOR): a bundle or audit write that returns Left is now a workflow Left
- What changed in QueryWorkflow.scala:
  - :469 is now the first step of the final for-comprehension in `finish`: `_ <- QueryWorkflow.bundleWriteFailure(bundleWrite).toLeft(())`. The bundle is still written (put-if-absent) before the answer text is final, at about :321-322, so the m-4 conflict note still works.
  - :480: `aw <- auditStage.append(audit).left.map(e => s"$AuditWriteFailedPrefix ${e.take(500)}")`. The append now happens inside the for-comprehension after the two contract checks. Before, it was in the yield and its result was ignored. :497 passes `Right(aw)` into the result.
  - New helpers in the companion object:
    - :713 BundleWriteFailedPrefix = "evidence bundle write failed:"
    - :716 AuditWriteFailedPrefix = "query audit write failed:"
    - :721 bundleWriteFailure(write: Option[Either[String, BundleWrite]]): Option[String]. It returns None for no bundle or for any successful write. A Conflict and itemConflicts count as success, so they still become storeConflicts and the answer note.
  - Doc comments now state the contract: run (:122-132) and AuditStage (:44-46).
- Tests (QueryWorkflowSuite):
  - :231 "U-1: a putBundle Left is a workflow Left (retryable), and a retry then succeeds". It checks the Left prefix and the injected cause, that no audit was written, that a retry gets the same run ID and Right with the bundle readable back, and the helper's behaviour.
  - :259 "U-1: an audit append Left is a workflow Left (retryable), and a retry then succeeds". It checks the Left prefix, that there are 0 audits, and that a retry gets the same run ID, storeConflicts == Nil (the bundle is unchanged) and exactly 1 audit.
  - Both run through RuntimeServiceFixtures.FlakyStores, which has two new switches: failEvidenceWrites and failAuditWrites. They throw a transient StoreException only on putIfAbsent/putIfVersion for the evidence bundle table or the run ledger table.
- Confirmed failing without the fix: I temporarily replaced :469 with Right(()) and made :480 swallow the Left. Both tests then failed with munit.FailException at QueryWorkflowSuite.scala:237 and :265 (the assert(l.isLeft) lines).

U-2 (MAJOR): a test now fails if RuntimeService.close() closes nothing
- What changed: test code only. The production close() was already correct.
- Tests (RuntimeServiceSuite):
  - :301 "U-2: fromStores closes each owned closeable exactly once over two close() calls". It passes two CountingCloseables through `owned`; the first one throws on close. After two close() calls it asserts (1, 1), which also shows that a closeable that throws does not stop the others.
  - :327 "U-2: aws closes every SDK client it was given exactly once over two close() calls". It passes counting stub S3Client, DynamoDbClient, S3VectorsClient, NeptunedataClient and SqsClient through AwsOptions.clients. It asserts each is closed exactly once after two close() calls, and that no LLM or embedder transport call happened.
- Confirmed failing without the fix: I temporarily changed close() to iterate over `owned.take(0)`. Both tests then failed with ComparisonFailException at :320 and :375, then I restored close().

## Prompt and model versions
No change: v2 prompt set (answer-synthesis-v2 and the others), the fake model, and QueryWorkflow.CodeVersion = "rag-runtime/2".

## Evaluation metrics
The fake benchmark was not rerun, as the work order says. Groundedness behaviour is unchanged: no prompt, verification or synthesis code changed.

## Known grounding gaps
Unchanged from the earlier rag report.

INTERFACE HANDOFF
- Files changed: listed above.
- QueryWorkflow.run(request, onStage): Either[String, WorkflowResult]: the signature is unchanged, but the contract is now enforced. A Left for a request that passed validate is an internal or store failure, and it is safe to retry because the IDs are deterministic and the writes are put-if-absent or append. There are two new Left cases:
  - "evidence bundle write failed: <storage error>", which starts with QueryWorkflow.BundleWriteFailedPrefix. It covers EvidenceStore.putBundle returning Left: an oversized bundle, a contract failure, or a store failure after retries.
  - "query audit write failed: <storage error>", which starts with QueryWorkflow.AuditWriteFailedPrefix. It covers QueryAuditWriter.append returning Left.
  - Order: bundle write, then the response and audit contract checks, then the audit append. On Right, the bundle is stored (EB3 plus EB1) and the audit is appended. A bundle Conflict or EB1 item conflicts are still a Right, with storeConflicts and an answer note.
- WorkflowResult: the field types are unchanged. In a returned result, bundleWrite is None (no bundle) or Some(Right(_)), and auditWrite is always Right(_).
- New public members of object QueryWorkflow: BundleWriteFailedPrefix: String, AuditWriteFailedPrefix: String, bundleWriteFailure(Option[Either[String, BundleWrite]]): Option[String].
- Test fixtures:
  - org.scholarchain.rag.runtime.RuntimeServiceFixtures.FlakyStores has new failEvidenceWrites: AtomicBoolean and failAuditWrites: AtomicBoolean (default off). They make only writes to the evidence bundle table or the run ledger table throw a transient StoreException; reads keep working. services can use them through rag % "test->test", for example fx.flaky.failEvidenceWrites.set(true) gives a Left on the 502 / retry path.
  - The existing switches failGraph and failKeyValue are unchanged.
- Thread-safety: unchanged. QueryWorkflow is safe across threads when its stores, model and retriever are. The FlakyStores switches are atomic.
- Known limitations:
  - A failed bundle write can leave some EB1 items stored. A retry handles this.
  - If the audit fails after the bundle was stored, the bundle stays stored with no audit until a retry.
  - Minor and advisory findings U-3, U-5 and U-10 to U-13 were not repaired, as instructed.
