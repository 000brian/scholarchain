STATUS: COMPLETE
OWNER: service-engineer
WORK ORDER: docs/agent-runs/phase-11/prompt.md (sha256 a0b59faeaeaae620ad87ce8b5dd1272efc1078d273e9a84baa426a9cd47e9ed1, given, not recomputed), step 3g, decision 8. Targeted tests only; not the wave closer.
FILES CREATED:
- /Users/brian/Developer/repos/scholarchain/modules/services/src/test/scala/org/scholarchain/services/ResponseGuardSuite.scala
FILES MODIFIED:
- main: modules/services/src/main/scala/org/scholarchain/services/{Main,Models,QueryService,ReadServices,Support,Worker}.scala
- tests: modules/services/src/test/scala/org/scholarchain/services/{CapacityAndFailureSuite,ComponentsSuite,CompositionSuite,HttpBehaviorSuite,QueryApiSuite,ReadRoutesSuite,ServiceTestKit}.scala
- frozen examples: modules/services/src/test/resources/api/v1/{async-acknowledgment,completed-result,evidence-item,explain,job-status}.json
(all under /Users/brian/Developer/repos/scholarchain/; nothing outside modules/services/** was edited)
COMMANDS RUN:
- sbt -batch "services/Test/compile" (log scratchpad/step3g-compile-1.log): success
- sbt -batch "services/Test/runMain org.scholarchain.services.GoldenExampleWriter <scratchpad>/golden" (log step3g-golden.log): success, 15 files
- shasum -a 256 generated vs frozen (twice: before and after freezing)
- run 1: sbt -batch "services/testOnly org.scholarchain.services.*" "contractTests/testOnly org.scholarchain.contracts.runtime.* org.scholarchain.contracts.services.*" (log step3g-targeted.log): services 4 failures in my new or changed tests (fixed below); contract suites did not run (sbt stops after the failing first command)
- run 2: the same command (log step3g-targeted-2.log): services 105/105 passed; contracts 51 run, 7 failed, every one of them an "expected failure but test passed" flip (listed in TEST EVIDENCE)
- grep -l -E 'failures="[1-9]|errors="[1-9]' modules/services/target/test-reports/TEST-*.xml: no output
- read-only git status, git diff --stat, git diff --no-index (golden comparison, stdout only)
TEST RESULTS: services 10 suites, 105 tests, 0 failed, 0 ignored. Contract runtime.* and services.*: 10 suites, 51 tests, 44 passed, 7 expected-failure flips (S-4 is mine; T-01, T-07, T-10, C-02 x2, B-01 are earlier owners' flips). T-02, T-03, T-04, T-05, T-06 x2 still fail as expected: their `.fail` tests pass.
VALIDATION RESULTS: 15 golden files: sha256 of frozen equals generated (table below). Report grep for services: empty.
ASSUMPTIONS:
- C-02 guard: an answer text, identity note, abstention reason or claim uncertainty holding `arn:aws`, `s3://`, `amazonaws.com` or a stand-alone 12-digit number is replaced by a fixed text. rag produces only fixed texts there now, so no normal answer changes (checked: the golden completed-result is unchanged apart from rag's v3 IDs).
- C-03: any source record reference with a `<scheme>://` prefix is withheld. Stable IDs, dblp keys, `doi:` refs and `Table:pk/sk` references are not URIs and stay.
- S-3 "this request's lease" means the same `leaseUntilMillis` and a live lease on the re-read record.
- Explain: `auditSchemaVersion` was added so a client can tell "v1, not recorded" from "v2, empty". A second note is always sent: the v1 note for a v1 record, else a note that HTTP attempts are per process.
RISKS:
- The C-02 guard is a pattern filter: it catches the AWS shapes listed, not every downstream text. The real fix is rag's fixed texts (3f).
- Main.start still lets an exception from RuntimeService.local escape (for example ExportSourceException for a missing export, which carries a path); my S-7 test tolerates it. Proposed later (services wrap; rag could also return Left).
- The JDK HttpServer behaviour for T-03 and T-06 is unchanged (not in my repair list).
MANUAL ACTION REQUIRED: none.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: step 4 (test-engineer): remove the `.fail` mark of "S-4 no log line carries the host or table name of a store failure" (LoggingRedactionCrossModuleSuite.scala:59), together with the earlier owners' flips; full `sbt check` as the repair-wave closer. Then step 5 re-check.

## Per-item status

| ID | Priority | Status | Fix (file) | Test (fails without the fix because ...) |
|---|---|---|---|---|
| C-02 (services part) | major, repair-now | FIXED | `ResponseGuard.answer` in Models.scala, called from `AnswerView.of` (fresh results, before storing) and from `Results.withEvidence` (results stored earlier); services adds no downstream text of its own | ResponseGuardSuite "C-02, C-03 a fresh workflow response with store text is guarded ..." and "... a result stored earlier with store text ...": the old `AnswerView.of` copied answerText, identityNotes, abstentionReason and uncertainty unchanged, so `leaks(...)` was non-empty and the WithheldAnswerText checks failed. Contract C-02 x2 flips come from rag's fix (3f); my guard is defense in depth |
| C-03 (services part) | major, repair-now | FIXED | `ResponseGuard.item/provenance` withhold `<scheme>://` refs in `EvidenceView.of` (GET /v1/evidence), in the POST/job result evidence and in claim/answer provenance; `EvidenceService.sourceUris` also skips them | ResponseGuardSuite "C-03 GET /v1/evidence of an item stored with an s3:// source reference hides it" (a pre-Phase-11-shape item stored in its own bundle): the old view returned the stored provenance and `scholarchain:s3://<bucket>/...` in sourceUris. The stored-result test covers POST-style results read back through `GET /v1/jobs/{id}/result` with a stored bundle |
| Golden examples | P1 | DONE | 5 files regenerated and frozen with Write; 15/15 hashes equal | GoldenExamplesSuite 17/17 (it failed before: rag's expected side effect) |
| Explain on QueryAudit v2 | P1 | DONE | ExplainView + `auditSchemaVersion`, `queryTemplateIds`, `indexesQueried`, `metadataFilterKeys`, `modelCalls`, `cacheHits`; `scoreComponents: []` and ScoreNote kept; V1Note for v1 records, AttemptsNote for v2; explain.json refrozen; ReadRoutesSuite:116 now pins `answer-synthesis-v3@sha256:` | ReadRoutesSuite "explain shows the QueryAudit v2 fields ..." (the old view had no `auditSchemaVersion`/`modelCalls`: `ex(...)` throws) and "explain of a schema version 1 audit ..." (a v1 record appended through storage's QueryAuditWriter; old view had no note or version) |
| S-4 | P1 | FIXED | `ServiceLog.event` has no `detail` parameter any more; `failure` logs `cause=unexpected-exception` and the exception class name (identifier-checked) only; `LogCause` codes: store results (`store-failure`, `condition-failed`, `refused`, `result-too-large`) and rag workflow Lefts classified by rag's fixed prefixes (`workflow-exception` + simple class name, `evidence-bundle-write-failed`, `query-audit-write-failed`, contract failures, `workflow-failed`). Every call site that passed text (QueryService workflow-failed, stage-write-ignored, job-fail-ignored, result-write-lost; Worker worker-store-failure, job-result-discarded; HttpAdapter/Worker failure) changed. 502 bodies stay fixed | CapacityAndFailureSuite "S-4 a store failure naming a host and a table never shows them in any log line" (sync, keyed sync with failing stage writes, async worker, worker job read): the old `workflow-failed` and `stage-write-ignored` lines carried the message in `detail` (contract evidence: test-report.md, observed `detail` with host and table). HttpBehaviorSuite "S-4 an exception is logged as a fixed cause and its class name only" and "S-4 workflow failures become fixed cause codes ...". Contract flip: LoggingRedactionCrossModuleSuite S-4 |
| S-3 | P2 | DONE | `QueryService.succeedAgain`: on ConditionFailed/Refused/Left of the final `succeed`, re-read; running under this request's lease -> retry once; already succeeded with this result -> 200; else 502 DOWNSTREAM_FAILURE and a `result-write-lost` line with a cause code | CapacityAndFailureSuite "S-3 ... fails once is written again; twice gives 502": old code returned 200 and left the job running, so the replay was 409 IDEMPOTENCY_IN_PROGRESS (not 200 replayed) and the twice case was 200, not 502 |
| S-6 | P2 | DONE | ComponentsSuite thin-route test finds the sources from the repository root or the module folder and fails (never `assume`) when neither exists | Not a fail-without-fix test: from the repository root the old test also passed; the change removes the silent skip (services skipped count is now 0) |
| S-7 | P2 | DONE | Main.start checks `ApiKeys.load` right after the config, before building any runtime | CompositionSuite "S-7 Main.start refuses missing or shared API keys before building any runtime": with the old order the runtime build is reached first and throws ExportSourceException (observed in run 1 for the valid-key case), so the missing-key assertion never holds |
| S-8 | P2 (advisory) | NOT DONE, proposed later | | The JDK HttpServer gives no 503 path from a rejecting executor; needs an accept-side design (larger than "small") |
| S-9 | P2 (advisory) | DONE | `CircuitBreaker.acquire(): Option[Slot]`, `cancel(slot)` frees the trial only for the trial holder; QueryService passes its slot through admit/unadmit/runSync | ComponentsSuite "S-9 circuit breaker: a slot taken while closed never frees another request's trial": old `cancel()` cleared `trialInFlight`, so a second trial was admitted |
| S-10 | P2 (advisory) | DONE | POST /v1/query answers 400 INVALID_REQUEST for any query-string parameter (same rule and details as GET routes) | QueryApiSuite "S-10 ...": old code ignored the query string and answered 200 |
| R-1 | | NOT CHANGED (decision) | | |
| C-09, C-12, C-13, T-03, T-04, T-05 (services part), T-06 | minor/advisory | RECORDED, not fixed (work order) | | T-03..T-06 `.fail` tests still fail as expected |

How "fails without the fix" was checked: by tracing each new assertion against the old code path named in the table (I did not revert and rerun, to keep to one targeted verification point). Observed evidence where it exists: S-4 failed before in the contract suite (test-report.md, `detail` held host and table); GoldenExamplesSuite and ReadRoutesSuite:116 failed before (rag 3f handoff); for S-7, run 1 showed that the runtime build throws once it is reached, which is what the old order did before the key check.

One fix, two IDs: none shared a root cause, except that S-9's slot plumbing touches the same code as C-09 (recorded, not fixed: a throwing trial task still leaves `trialInFlight` set).

## Golden files (sha256, generated in scratchpad/golden vs frozen in modules/services/src/test/resources/api/v1)

| File | Generated = frozen | Changed | Why |
|---|---|---|---|
| async-acknowledgment.json | 2f3513ebc266ff5b4676a94310614e6e335d909524e3c2cea7f0e874564dcf47 | yes | queryId: rag 3f hashes prompt versions into the query run ID (U-3, rag-runtime/3) |
| completed-result.json | 008357343f39813b3296cdc175d61020e57e104343987e142155768b11f3b54a | yes | new query run ID, so every evidence/bundle ID and runId changes; policyVersion answer-synthesis-v2 -> v3 (48 lines) |
| evidence-item.json | 6981b49611c75de01b3f896ca19dbbbf3e1adef99b8a14264c5d9c99659c702d | yes | evidenceId and provenance.runId (new query run ID) |
| explain.json | e4d6d029c95d6ddde8e4da8d8829a4787c5c233fbfdf564269d68a1d1fa0ff3a | yes | new IDs; promptVersions answer-synthesis-v3@sha256:becccf47...; v2 fields (auditSchemaVersion 2, queryTemplateIds [researcher_by_id, coauthor_neighbors], indexesQueried [], metadataFilterKeys [], modelCalls planner/synthesis, cacheHits {}); second note |
| job-status.json | ec4800d22edded06a0600379f1dfd02be0fd36117c6d73f9acea4af64bc72de3 | yes | queryId |
| error-400/401/404/409/422/504.json | 735ff84f.../38d77ef9.../6dfb746f.../e4ed9bad.../c4e4787b.../a6d92dff... | no | |
| neighbors-page.json, node.json, readiness-not-ready.json, sync-query-request.json | 10d5d12e.../4e416f10.../46da549a.../b0b69311... | no | |

No file needed a second rewrite; none is open.

## Route table (changes only)

| Route | Change |
|---|---|
| POST /v1/query | 400 INVALID_REQUEST for any query-string parameter (S-10); keyed sync: 502 DOWNSTREAM_FAILURE when the final result write is lost twice (S-3); answer texts and provenance guarded (C-02, C-03) |
| GET /v1/jobs/{jobId}/result | guarded on read (results and bundles stored earlier) |
| GET /v1/evidence/{evidenceId} | URI source references withheld from provenance and sourceUris (C-03) |
| GET /v1/explain/{queryId} | new fields: auditSchemaVersion, queryTemplateIds, indexesQueried, metadataFilterKeys, modelCalls, cacheHits; notes [ScoreNote, V1Note or AttemptsNote] |

## Error model coverage
No new error code. New paths: S-10 uses INVALID_REQUEST with `details.<param> = "unknown query parameter"`; S-3 uses DOWNSTREAM_FAILURE (502, retryable). Bodies hold fixed messages only.

## Limits
Unchanged. Log lines carry only fixed fields; the exception class name is accepted only when it matches a Java class-name shape (else `unknown`).

## Proposed later
- S-8: answer 503 instead of caller-runs when every HTTP thread is busy (accept-side design).
- Main.start: catch an exception from the runtime build and return a Left with the class name only (today ExportSourceException escapes with a path); or rag returns Left (rag-engineer).
- C-09 (record only): a throwing half-open trial task leaves the workflow breaker stuck; the new Slot type makes a `finally cancel(slot)` fix small.
- C-12: readiness checks for the evidence/run-ledger tables (role evidence) and the queue (role worker).
- C-13: rate-limit unauthenticated requests before auth.

## INTERFACE HANDOFF
- Files changed: listed above.
- Public types and signatures:
  - `ServiceLog.event(name: String, traceId: Option[String], fields: List[(String, ujson.Value)]): Unit` (the `detail` parameter is removed); `ServiceLog.failure` unchanged signature, now logs `cause` and `exception` only.
  - new `object LogCause` (Support.scala): codes `UnexpectedException`, `StoreFailure`, `ConditionFailed`, `Refused`, `ResultTooLarge`, `Written`, `WorkflowException`, `BundleWriteFailed`, `AuditWriteFailed`, `ResponseContractFailed`, `AuditContractFailed`, `WorkflowFailed`; `className(e: Throwable): String`; `ofChange(Either[String, JobChange]): String`; `ofWorkflow(e: String): List[(String, ujson.Value)]`.
  - `CircuitBreaker.acquire(): Option[CircuitBreaker.Slot]`, `CircuitBreaker.cancel(slot: CircuitBreaker.Slot)` (replaces `cancel()`), `final case class CircuitBreaker.Slot(trial: Boolean)`; `tryAcquire()` kept.
  - new `object ResponseGuard` (Models.scala): `isUri`, `hasDownstreamText`, `provenance`, `item`, `answer(AnswerView)`, `result(CompletedResult)`, texts `WithheldAnswerText`, `WithheldNote`.
  - `EvidenceView.of(stored: EvidenceItem): EvidenceView` (was `of(item, sourceUris)`).
  - `ExplainView`: new fields `auditSchemaVersion: Int`, `queryTemplateIds`, `indexesQueried`, `metadataFilterKeys: List[String]`, `modelCalls: Map[String, ModelCallCounts]`, `cacheHits: Map[String, Long]`; `ExplainView.V1Note`, `ExplainView.AttemptsNote`.
- Test fixtures (package org.scholarchain.services, test scope): `ServiceTestKit.SlowGraphStores.graphThrows: AtomicReference[Option[String]]` (graph port throws StoreException(message)); `ServiceTestKit.ScriptedJobStores(base, message)` with `succeedFailures`, `stageFails`, `getFails`, `succeedCalls`.
- Thread-safety: `CircuitBreaker` and `ServiceLog` stay thread-safe (synchronized / stateless); `ResponseGuard` and `LogCause` are pure.
- Known limits: see RISKS and Proposed later.

## TEST EVIDENCE
- command: sbt -batch "services/testOnly org.scholarchain.services.*" "contractTests/testOnly org.scholarchain.contracts.runtime.* org.scholarchain.contracts.services.*"
- finished: 2026-11-16 22:13:37 local (services part 22:13:32)
- per suite:
  - org.scholarchain.services.ComponentsSuite run=9 passed=9 failed=0 skipped=0
  - org.scholarchain.services.ResponseGuardSuite run=4 passed=4 failed=0 skipped=0
  - org.scholarchain.services.GoldenExamplesSuite run=17 passed=17 failed=0 skipped=0
  - org.scholarchain.services.CompositionSuite run=6 passed=6 failed=0 skipped=0
  - org.scholarchain.services.ReadRoutesSuite run=11 passed=11 failed=0 skipped=0
  - org.scholarchain.services.CapacityAndFailureSuite run=9 passed=9 failed=0 skipped=0
  - org.scholarchain.services.QueryApiSuite run=11 passed=11 failed=0 skipped=0
  - org.scholarchain.services.WorkerSuite run=11 passed=11 failed=0 skipped=0
  - org.scholarchain.services.IdempotencySuite run=11 passed=11 failed=0 skipped=0
  - org.scholarchain.services.HttpBehaviorSuite run=16 passed=16 failed=0 skipped=0
  - org.scholarchain.contracts.runtime.DynamoConditionalConflictSuite run=4 passed=4 failed=0 skipped=0
  - org.scholarchain.contracts.runtime.LlmOutputAdversarialSuite run=8 passed=6 failed=2 skipped=0 (both are expected-failure flips: T-07, T-01)
  - org.scholarchain.contracts.runtime.LoggingRedactionCrossModuleSuite run=5 passed=2 failed=3 skipped=0 (flips: S-4, C-02 x2)
  - org.scholarchain.contracts.runtime.NeptuneTimeoutStubSuite run=4 passed=3 failed=1 skipped=0 (flip: B-01)
  - org.scholarchain.contracts.runtime.MissingMetadataSuite run=3 passed=3 failed=0 skipped=0
  - org.scholarchain.contracts.runtime.ReplayIdempotencyCrossModuleSuite run=4 passed=4 failed=0 skipped=0
  - org.scholarchain.contracts.runtime.PromptInjectionCrossModuleSuite run=4 passed=4 failed=0 skipped=0
  - org.scholarchain.contracts.runtime.S3VectorsThrottlingStubSuite run=4 passed=4 failed=0 skipped=0
  - org.scholarchain.contracts.runtime.UnsupportedClaimEndToEndSuite run=7 passed=6 failed=1 skipped=0 (flip: T-10)
  - org.scholarchain.contracts.services.ApiAdversarialSuite run=8 passed=8 failed=0 skipped=0 (T-03, T-04, T-05, T-06 x2 still fail inside their `.fail` marks, as expected)
- result line: services `[info] Passed: Total 105, Failed 0, Errors 0, Passed 105`; contracts `[error] Failed: Total 51, Failed 7, Errors 0, Passed 44`
- expected-failure flips:
  - mine: S-4: LoggingRedactionCrossModuleSuite.scala:68 "S-4 no log line carries the host or table name of a store failure" expected failure but test passed
  - earlier owners' flips: T-07 (LlmOutputAdversarialSuite.scala:151), T-01 (LlmOutputAdversarialSuite.scala:177), C-02 graph-port Left (LoggingRedactionCrossModuleSuite.scala:80), C-02 vector-index (LoggingRedactionCrossModuleSuite.scala:108), B-01 (NeptuneTimeoutStubSuite.scala:101), T-10 (UnsupportedClaimEndToEndSuite.scala:194)
- source changed after this run: no
