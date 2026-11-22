# review-report-a2 (Phase 16, session 2, R-1b, code-reviewer)

VERDICT: REQUEST_CHANGES

One major finding is open: P16-A-09, in rag's new L2 absence check. Under it the verifier marks as `verified` a positive path claim that contradicts its cited complete `path=none` evidence, as long as the sentence contains any negation word. Every other item in the slice checked out, apart from four minors.

## Findings

| ID | Severity | interface | Owner | File:line | Failure input and impact |
|---|---|---|---|---|---|
| P16-A-09 | major | no | rag-engineer | modules/rag/src/main/scala/org/scholarchain/rag/runtime/ClaimVerification.scala:144-147 (`statesAbsence`), :322-335, :341 (`if (!absence)` skips the hop check), :368-373 (stated hops blanked as free numbers) | **What goes wrong:** `statesAbsence` returns true when the text has any negation word anywhere and any path word anywhere. When it does, every "N hops" in the claim is accepted if it is at most the complete search's `maxDepth`, and the found-path hop check is skipped.<br>**Input (traced through the code):** a claim citing a complete `path=none` item with maxDepth 6: "Alice Smith is not a direct coauthor of Bob Jones; they are connected within 3 hops." (or "Although they never coauthored, Alice Smith and Bob Jones are linked within 3 hops.").<br>**Trace:** NegationRe matches `not`/`never`, PathWordRe matches `connected`/`linked`/`hops`, so `absence` is true. completeDepths = [6], stated = [3], 6 >= 3, so `checked += 1`. Both names are checked. `3 hops` is blanked from the free numbers. Status: **Verified**.<br>**Before this step:** HopsNoPath, so `partially_supported`.<br>**Second case, same cause:** a claim citing a found PathFact (2 hops) and a `path=none` item that contains a negation ("connected by 3 hops in 2015-2025, not within 6 hops in 2000-2010") no longer gets HopsConflict, so a wrong hop count is verified.<br>**Impact:** a claim the cited evidence contradicts is kept and shown as verified. That breaks the grounding invariant: the LLM becomes the source of a path/number. PathAbsenceSuite has no positive-claim-with-negation case, so the suite passes.<br>**Repair direction (owner decides):** detect absence only when the negation governs the path phrase (for example "no … path", "not connected/linked/reachable", "no connection"), and keep the found-path hop check, or a positive-hops check against `path=none`, for hop counts outside the absence phrase. Add a test with the inputs above that fails today. |
| P16-A-10 | minor | no | rag-engineer | scratchpad agent-report-rag.md:118 (D column, "Other per-row fields") | **What is wrong:** the list of load-explain.csv columns runs `query_id, http_status, latency_ms, …`. The real header (load-explain.csv:1) has `start_epoch_s`, `curl_exit`, `time_total_s` and `error_code` between those columns.<br>**Impact:** small. All 40 rows have http_status 200 (Grep count 40), so nothing measurable is lost. But X2's scorer, if built from this table, might not know that `error_code` exists. |
| P16-A-11 | minor | no | hadoop-engineer | modules/hadoop/src/test/scala/org/scholarchain/hadoop/LocalPipelineRunner.scala:240 | **What is wrong:** the `run` scaladoc ("Runs the pipeline; returns the final report …") now sits directly above the new `effectiveSettings` doc comment (:241-246). The doc for `run` (:267) is orphaned and attaches to the wrong member.<br>**Impact:** cosmetic, in test scope only. |
| P16-A-12 | minor | no | service-engineer | modules/services/src/main/scala/org/scholarchain/services/Main.scala:107-108 | **What is wrong:** Composition now passes `config.vectors…indexNames` into `EmbeddingWork.indexes` for the H-04 pre-check, but no test asserts it. The H-04 test passes `known` directly into `EmbeddingWork`, and CompositionSuite is unchanged. Reverting this line leaves every test green, which breaks the "test that fails without it" rule.<br>**Impact:** only ordering. Unknown-index poison would wait for the second handler call, and the release would still happen. |
| P16-A-13 | minor | no | rag-engineer, service-engineer, test-engineer (process; the orchestrator lists them) | agent-report-rag.md:20; agent-report-service.md:33; agent-report-test.md:13 | Commands outside the list were used and disclosed. All were read-only:<br>- rag: `cd`, one `sed -n`<br>- service: `cd`, `find … \| xargs wc -l`, `sed -n`<br>- test: `cd`, `cat`, `sed -n`, `uniq -c`, a `for` loop<br>They belong in the s2 summary's "commands not on the list", like P16-A-07. |

**P16-A-08 re-check: confirmed, not hit. Stays minor, needs no repair.**
- rag has no `runClassified` caller (Grep over modules/**: only GraphReads, tests and storage). QueryTrace.wrap (Stages.scala:106-114) overrides only `run`, and rag's per-query traced copy is made at QueryWorkflow.scala:171.
- services calls `runClassified` through `GraphReads.classified` (GraphReads.scala:38) on `app.runtime.stores.graph` (ReadServices.scala:209) and `runtime.stores.graph` (ServiceServer readiness check). That is `env.runtimeStores` (RuntimeService.scala:393), whose graph is the bare port (RuntimeStores.scala:42). The only production ports are NeptuneGraphRead (overrides it, NeptuneAdapters.scala:285) and InMemoryGraph (overrides it, :178). No class is lost.
- It is still a latent trap if a traced store is ever handed to services. rag already lists it under "proposed later".

## Checklist (decision 15)

**Decision 7 (service):**
- **7.1a, FECD-1 breaker side:** done.
  - Generations: Support.scala:104-191. A stale slot changes nothing; `cancel` frees only the trial of the current generation (:139-142).
  - Single half-open trial: :113-123.
  - Only availability errors count: GraphReads.scala:27-34. Permanent and invalid errors cancel the slot (:67) and still answer 502.
  - Concurrency tests: GraphBreakerSuite.scala:62-113 (10 callers, 7 stale), :115-130, :132-140, and B5 :244-265 against storage's retried Neptune port.
  - Threshold and open period unchanged: Breakers still uses `settings.circuitFailureThreshold` and `circuitOpenMillis`, QueryService.scala:30-41; config/course.json is not in this slice.
  - Workflow breaker: the request's own slot (QueryService.scala:388, :428-436). The timeout path records nothing, so there is no double count.
- **7.1b, EmbeddingWorkerEmulatorSuite:**
  - storage's safety contract runs first (`EmulatorSafety.check`, :97), then the credentials file, then config, then the writer, and only then clients are built (:115-116).
  - preflight line on refusal: :91-96.
  - `new ReembedHandler(...)` public constructor at :195-200; `fromConfig` is never called.
  - RefusingEmbedder throws and counts calls (:296-305); the test asserts 0 calls (:274).
  - Outcome classes from storage (`EmulatorOutcome`, `EmulatorFailure.classify`).
  - Per-run ID `emu-${runTag}-poison` (:161). `isOwn` matches against the raw body, which is correct because SqsRawReceiveQueue passes the body unchanged (ServiceStores.scala:108-128, SqsWorkQueue.scala:45). Foreign DLQ messages are released.
  - Own results file `results-EmbeddingWorkerEmulatorSuite.jsonl`.
  - Demonstration line exact (:269-273).
  - Bounded: one message, at most maxReceive+6 polls, 40 s, at most 5 DLQ polls, 60 s per test.
  - Tagged RequiresEmulator; the tag name matches build.sbt:115. `services/EmulatorTest/compile` succeeded per the report.
- **FECD-2:** reasoning correct. The permit is released in `finally` when the workflow ends (QueryService.scala:387-389).
- **H-04:** EmbeddingWorker.scala:101-126; delivery IDs keep their batch positions (:136). Tested; see P16-A-12.
- **H-05:** `embeddingDrainMillis` is capped at the default (ServiceServer.scala:984-989); tested.
- **H-06:** ServiceLog.external redacts every string value (Support.scala:366-372); tested.
- **K-01:** routed to infra-engineer, confirmed at phase-15 orchestrator-summary-s2.md:117.
- **G-23:** reason classes in HealthService; no store text; no retry, with reasons given; tested in HttpBehaviorSuite.
- **Route time limits:** see "Limits" below.

**Decision 8 (rag):**
- **8.1 L2:** defect, P16-A-09. The bounded-search part is correct: an incomplete search always gives AbsenceBoundedSearch (:328), tested for both the time limit and the resource limit. The problem is how absence is detected.
- **8.2 `path-query-bounded`:** in `Table`, and `All` is built from Table (Abstentions.scala:80, :91). Valid audit code. Context code follows `GraphStop` (QueryWorkflow.scala:323-334). Tested in PathAbsenceSuite and in services ReadRoutesSuite.
- **8.3 G-28 statement:** in the form "cause narrowed to: …". It matches step D's evidence (storage-D.md:92-133: 7 requests and 1 server error at 23:51; `path-query-failed` at D6).
- **8.4 field table:** citations correct apart from P16-A-10 (spot-check below). The "contradicted" status is `conflicting` (Enums.scala:328, ClaimVerification.scala:475).

**Decision 10:** F8 and F9: `.fail` and title prefixes removed. The diff (EvalReportRound3AdversarialSuite.scala:298-305, :989-997) changes only comments and titles; assertions are unchanged. They pass per the step 6 run and the full check (contract_tests=399).

**Decision 11:**
- **Spark suite table:** 43 tests (15 + 28) match the `test(` counts. Rows spot-checked against the code:
  - KnownGraphSuite :413 (independent sheet; isolated S2.A read from pipeline :452-454)
  - PipelineFixtureSuite :187 (mixed: manifest table list), :207 (17-edge literal; weights and years literal), :285 (k-core literals), :294 (test-side tie rule), :338 (literal paths), :368 (mixed: hop facts against the pipeline's own coauthor_edge, found count against its own report)
  - All accurate, including the independence claims.
- **C-05:** `mainWith` and `masked` (JsonlExport.scala:1651-1686). Session-start failures are now masked too. The test asserts no cause and no raw names; the mutation was seen failing.
- **G-19:** `effectiveSettings` uses the same `Settings.withDefaults` as JobRunner.scala:99, on a copy (tested). `settings` now holds the effective values and `overrides` holds the `-D` arguments; the revert was seen failing. Doc nit: P16-A-11.

**Limits and defaults:**
- **New limit, not a loosened one:** before this step the evidence and explain routes had no route deadline. Now `ReadRouteDeadlineMillis` = 10000 (ServiceLimits.scala:906), accepted in 1..60000. A read past it gets 504 READ_TIMEOUT (Errors.scala:84-86), counts as a failure of the reads breaker for its own slot, and a late result is never recorded (QueryService.scala:746-784). Tests: RouteTimeLimitSuite.
- `embeddingDrainMillis` cannot exceed the existing 60 s.
- No prompt, threshold or other default changed (rag: none; prompts/: none).

**Other checks:**
- **Secrets and identifiers:** none new. Tests use the placeholder `123456789012`, `example-*` and `*.example` hosts, and `scholarchain-emu-*` names. No run of twelve zeros in the slice (Grep).
- **Logging redaction:** `graph-read-failed`, `circuit-state`, `read-deadline-passed` and `readiness-check-failed` carry allowlisted values only, and the tests assert that no store text appears.
- **Status lines present:**
  - rag: n-1, n-2, n-4, C-06, C-07, C-08, C-10, C-11, R3-04, R3-05, R3-08
  - service: S-8, C-09 (spot-checked: correct, QueryService.scala:387-389), C-12, C-13, custom metrics
  - spark: B-08, B-10
  - hadoop: A-03
  - test: N-3, N-4, D-09, R2-01, R2-02, R3-02, R3-03, R3-07, test-plan items, T-02 to T-06
- **Each behavior change has a test that fails without it:** yes, except P16-A-12. Mutation runs were shown by services, spark and hadoop. rag argued its case from the old code paths and did not revert.
- **Full check after the last source change:** `.claude/.last-test-run` timestamp is 2026-11-22T03:57:30Z (module_tests 2282, contract_tests 399). This matches service's report; every slice file comes from earlier steps.

**Answer-report citations spot-checked (10):**
- attempt-06: claimCounts :384-389 (111/19/1/5); abstentions :390-393; codeVersion :288; workflowErrors :292; deterministicGraphRetrieval :323-327.
- load-explain.csv: header :1 (see P16-A-10); row :2 values; 40 data rows; 0 rows with `,true,`.
- clip 01: verificationCounts and empty abstentionCodes at :64 and :131.

## Files read in full
- All 5 untracked files: PathAbsenceSuite.scala, GraphReads.scala, EmbeddingWorkerEmulatorSuite.scala, GraphBreakerSuite.scala, RouteTimeLimitSuite.scala.
- S/diff-R-1b.txt (all 1792 lines), S/files-R-1b.txt, S/files-R-1b-status.txt.
- Producer reports in full: agent-report-rag.md, agent-report-spark.md, agent-report-hadoop.md, agent-report-test.md, agent-report-service.md.
- Work order sections read: decisions 1–23, including 2, 4, 6.6, 7, 8, 10, 11, 15, 16.

## Read in part (diff hunks plus context)
- ClaimVerification.scala:150-489
- EvidenceBuilder.scala:300-344
- EmbeddingWorker.scala:80-169
- QueryService.scala:295-419
- Stages.scala:90-129
- Abstentions.scala (Grep)
- ServiceStores.scala:95-140
- SqsWorkQueue.scala (Grep)
- RuntimeStores and RuntimeService (Grep)
- Settings.scala and JobRunner.scala (Grep)
- KnownGraphSuite.scala:405-462
- PipelineFixtureSuite.scala:185-414
- FlakyStores (rag test fixture)
- review-report-a1.md :14-25
- agent-report-storage-D.md (Grep: G-28 section)
- the three frozen answer sources (citations above)

## Not read
- The other tracked slice files beyond their full diff hunks: Synthesis, RuntimeModel, Errors, HealthService, Main, ReadServices, ServiceLimits, ServiceServer, Support, the services tests, JsonlExport, and the rest of LocalPipelineRunner.
- agent-report-storage.md (upstream; R-1a covered it).

No slice file is missing.

STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: docs/agent-runs/phase-16/prompt.md (sha256 c75cc5f895faed13880a69ec285231c5498fd50924c7b875fe7e02cce1707dc8, as given), R-1b: steps 4–7 (modules/rag/**, prompts/**, modules/spark/**, modules/hadoop/**, tests/**, modules/services/**)
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools)
TEST RESULTS: not run (reviewer). Producers report the targeted runs and the wave closer's full `sbt check` (exit 0, 988 s); the marker file agrees.
VALIDATION RESULTS: 1 major (P16-A-09), 4 minor (P16-A-10 to P16-A-13). P16-A-08 confirmed, not hit.
ASSUMPTIONS: the P16-A-09 traces were done by hand from the code, not by running it. They are verified against ClaimVerification.scala :239-489, as shown above.
RISKS: P16-A-09 lets contradicted path claims through as `verified` in any future live answer. The groundedness evaluation (group 4) runs on frozen Phase 10/15 data and is not affected by this code.
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: step 8 triage. P16-A-09 goes to rag-engineer for repair-01-rag. modules/rag changes, so the repair round's last repairer runs the full `sbt check` and `packageCheck`. Step 10 re-checks.
