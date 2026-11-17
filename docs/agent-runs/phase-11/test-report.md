# Phase 11 step 1b: adversarial tests (test-engineer, wave-1 closer)

STATUS: COMPLETE
OWNER: test-engineer
WORK ORDER: docs/agent-runs/phase-11/prompt.md (sha256 a0b59faeaeaae620ad87ce8b5dd1272efc1078d273e9a84baa426a9cd47e9ed1, not recomputed), step 1b, decision 5 (wave-1 closer)
FILES CREATED (all new, all under tests/contracts/src/test/scala/org/scholarchain/contracts/; no tracked file edited):
- runtime/RuntimeAdversarialSupport.scala (shared: ScriptModel, helpers)
- runtime/CrossModuleHarness.scala (shared: runtime over wrapped ports + services server)
- runtime/LlmOutputAdversarialSuite.scala (8 tests; T-01, T-02, T-07)
- runtime/UnsupportedClaimEndToEndSuite.scala (7 tests; T-10)
- runtime/PromptInjectionCrossModuleSuite.scala (4 tests)
- runtime/NeptuneTimeoutStubSuite.scala (4 tests; B-01 mark)
- runtime/DynamoConditionalConflictSuite.scala (4 tests)
- runtime/S3VectorsThrottlingStubSuite.scala (4 tests)
- runtime/ReplayIdempotencyCrossModuleSuite.scala (4 tests)
- runtime/LoggingRedactionCrossModuleSuite.scala (5 tests; S-4 mark, two C-02 marks)
- runtime/MissingMetadataSuite.scala (3 tests)
- services/ApiAdversarialSuite.scala (8 tests; T-03, T-04, T-05, T-06 x2)
- config/ConfigOmissionSuite.scala (6 tests; T-08, T-09, U-6 mark)
- crossrecord/WindowProvenanceCrossRecordSuite.scala (5 tests)
- (report) /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/858dada8-c8dd-43c7-b05d-961e323b855c/scratchpad/phase11-reports/test-report.md
FILES MODIFIED: none in the repository (only my own new files above, while writing them)
COMMANDS RUN (all observed):
- `sbt -batch "contractTests/Test/compile"` x2 (first: compile errors in my new files, fixed; second: EXIT=0)
- `sbt -batch "contractTests/testOnly org.scholarchain.contracts.runtime.* org.scholarchain.contracts.services.*"` (development run 1)
- `sbt -batch "contractTests/testOnly org.scholarchain.contracts.runtime.* org.scholarchain.contracts.services.* org.scholarchain.contracts.config.* org.scholarchain.contracts.crossrecord.*"` (development run 2)
- `sbt -batch "contractTests/testOnly org.scholarchain.contracts.config.ConfigOmissionSuite"` x2 (probe; found my own vacuous edit helper)
- the same four-package invocation once with every `.fail` mark temporarily removed (probe: records the failure message of each marked test; marks restored, 16 counted by grep)
- the targeted closing invocation (TEST EVIDENCE below)
- `sbt -batch check` (background, log step1b-check.log, bounded polling) (TEST EVIDENCE below)
- read-only: ls, grep, sed -n, cut, wc, sort, date
TEST RESULTS:
- targeted (12 new suites): Passed: Total 62, Failed 0, Errors 0, Passed 62 (16 of them are `.fail` tests that failed as expected).
- `sbt check` EXIT=0, `[success] Total time: 801 s`. Contract tests (no AWS): suites=34 executed=398 passed=398 failed=0 errors=0 skipped=0. Module tests: suites=109 executed=1877 passed=1877 failed=0 errors=0 skipped=3. Marker updated: `.claude/.last-test-run (module_tests=1877, contract_tests=398)`.
- Compared with the start (decision 1: module 1877, contract 336, 3 skipped module tests): module unchanged; contract 336 + 62 new = 398; skipped unchanged (3, the RequiresAws suites AwsAdaptersAwsSuite, LiveLlmAwsSuite, LiveProvidersAwsSuite, each "1 ignored").
- `.fail` marks in contract tests now: Phase 5 F8, F9 (EvalReportRound3AdversarialSuite, unchanged) + 16 new: T-01, T-02, T-03, T-04, T-05, T-06 (x2), T-07, T-08, T-09, T-10, S-4, C-02 (x2), B-01, U-6.
VALIDATION RESULTS: every new suite ran with run > 0; the 16 `.fail` tests were each observed failing with the specific wrong value (probe run, messages below); the 46 other new tests pass.
ASSUMPTIONS:
- Step 0 was COMPLETE (pasted handoff): rag, storage and services test fixtures are on the contractTests test classpath; I used ServiceTestKit.harness, RuntimeServiceFixtures, RuntimeFixtures, ConditionalDynamoStub, ServiceStoreFixtures, ManualClock. No fallback fakes were needed.
- This session also had no Grep/Glob tools; I used read-only `grep`, `ls`, `sed -n`, `cut`, `wc`, `date` and `cd <repo> && …` inside single Bash calls (as in step 1a). One `grep -a -o` read class names from the cached s3vectors SDK jar. No file was written by shell except sbt logs in the scratchpad.
- Severities are my proposals; triage (step 2) decides. T-01 and T-10 may share a root cause (ClaimVerification's "uncovered -> partially_supported" rule); I kept them separate because they cite different checks (identifiers vs names).
- Test IDs T-01..T-10 follow the order the defects were confirmed; all were confirmed in the probe run of 18:52.
RISKS: see "Residual risks".
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: step 2 triage (orchestrator): route T-01, T-02, T-05 (rag part), T-07, T-10 to rag-engineer; T-03, T-04, T-05 (services part), T-06 to service-engineer; T-08, T-09 to domain-engineer. Step 4 (test-engineer) removes marks after flips.

---

## Findings (T-IDs), proposed severity, owner, file:line, input, observed vs expected, observed failure message

| ID | Sev. | Owner | Production file:line | Failing input | Observed | Expected |
|---|---|---|---|---|---|---|
| T-01 | major | rag-engineer | modules/rag/.../runtime/ClaimVerification.scala:135-142 (an unknown stable ID is only "uncovered") and :246-248 (uncovered + one passed check = partially_supported, kept); QueryWorkflow.scala:291-292 (kept text shown verbatim) | model claim-1 = the template identity claim plus "; see researcher:v1:deadbeef… and evidence:v1:deadbeef…." citing the real identity item | claim kept, status partially_supported, both fabricated IDs in the response claim text | a claim whose text holds an identifier that the cited evidence does not hold is not kept (unsupported or conflicting), or the identifier is never shown; spec: LLM output is never the source of an identifier |
| T-02 | minor | rag-engineer | modules/rag/.../runtime/Schemas.scala:64-82 (JsonSupport.parseObject: ujson.read keeps the last duplicate key) | answer-claims `{"summary":"First…","summary":"Second…",…}`; plan draft with `"operations"` twice | both validate Right: AnswerDraft(Second summary.,…), PlanDraft(List(ShortestPath),…) | Left (ambiguous model object fails closed, as ER's LLM stage already does) |
| T-03 | minor | service-engineer | modules/services/.../HttpAdapter.scala:220 (`getFirst("X-Api-Key")`) | GET /v1/jobs/<unknown> with `X-Api-Key: <full key>` and `X-Api-Key: not-a-key` | HTTP 404 JOB_NOT_FOUND (authenticated as full) | 401 UNAUTHENTICATED (two different credentials are ambiguous; a proxy that reads the last value disagrees with the service) |
| T-04 | minor | service-engineer | modules/services/.../QueryRequestParser.scala:110-112 (ujson.read, last key wins) | POST /v1/query `{"question":"Who is Hana Ito?","question":"<answered question>",…}` | HTTP 200 with the answer to the second question | 400 INVALID_REQUEST or INVALID_JSON (the parser rejects unknown fields; a repeated field is equally ambiguous) |
| T-05 | minor | rag-engineer + service-engineer | modules/rag/.../runtime/RuntimeModel.scala:45-56 (QueryRequest.fingerprint), modules/services/.../QueryRequestParser.scala:54-76 (ValidatedQuery.fingerprint) and :150-156 (question accepted with a lone surrogate); both hash `String.getBytes(UTF_8)` (core Hashing), which turns a lone surrogate into `?` | sync POST of the answered question ending "?", key `phase11-surrogate`; then the same question with the final "?" replaced by the JSON escape `\ud800`, same key | HTTP 200, `Idempotent-Replayed: true`, queryId run:v1:bbee9fc0b28acc2fcc708857f332798e equal to the clean question's: two different questions share one query run ID and one idempotency fingerprint | 400 (a lone surrogate is not text) or 409 IDEMPOTENCY_CONFLICT |
| T-06 | minor | service-engineer | modules/services/.../HttpAdapter.scala:264-267 (readBody: readNBytes throws IOException on a body shorter than declared / an unterminated chunk) and :110-112 (handle maps it to 500 INTERNAL plus an error log line) | (a) POST /v1/query `Content-Length: <body+200>` then half-close; (b) `Transfer-Encoding: chunked` with an unterminated chunk then half-close | HTTP 500 INTERNAL in both | 400 (a client's malformed framing is a client error; 500 pages the on-call and counts against the 5xx alarm) |
| T-07 | minor | rag-engineer | modules/rag/.../runtime/QueryWorkflow.scala:291-294 (removed claim text = "Removed (<status>): <first reason>") with ClaimVerification.scala:122 (reason quotes the cited ID) | model reply with one claim citing only evidence:v1:deadbeef… | claim text "Removed (unsupported): cites evidence:v1:deadbeefdeadbeefdeadbeefdeadbeef, which is not in the evidence bundle" | the removed claim's text names no model-made identifier (fixed reason text) |
| T-08 | major | domain-engineer | modules/core/.../config/ConfigLoader.scala:714-718 (`$field: '$n' must match $EnvVarName`), also :790 | services-local-v1.json with `service.apiKeyEnvVar` (and `readOnlyApiKeyEnvVar`) set to a key-like value `Sk-Live PHASE11/SENTINEL+value` (an operator pasting the key where its variable NAME belongs) | refusal message repeats the value: paths service.apiKeyEnvVar, service.readOnlyApiKeyEnvVar | the refusal names the field and the rule only; a pasted key never reaches the start-up log. Same class as U-6, which covers only service.jobQueueUrl |
| T-09 | minor | domain-engineer | modules/core/.../config/ConfigLoader.scala:546-678 and :751 (`'$value'` in the name-rule messages: environment, aws.region, bucket, prefix, table, endpoint, index, provider, window label) | each string value of the three shipped test configs replaced by the sentinel, one at a time | the refused value is repeated for 30+ paths per file (environment, aws.region, storage.*Bucket, storage.neptuneEndpoint, storage.dynamoDbTable, objectStore.*, dynamoDb.*Table, vectors.*Index, embedding.provider, llm.provider, runtime.defaultWindowLabel, bridge.algorithm …) | the refusal names field and rule, never the value (CLAUDE.md: no bucket name in logs) |
| T-10 | major | rag-engineer | modules/rag/.../runtime/ClaimVerification.scala:150-153 (a name not in cited evidence is only "uncovered") and :246-248 (status rule) | model claim "Carol King collaborated with Igor Petrov." citing Carol King's metric item (they are in different components) | HTTP 200, claim kept, status partially_supported, text shown | not kept (unsupported/conflicting): the answer states a coauthor relation the graph does not have. Possibly the same root cause as T-01 |

Observed failure messages (probe run with the marks removed, 2026-11-16 18:52; each is the assertion the test makes):
- T-01: `LlmOutputAdversarialSuite.scala:175 claim-1 status=partially_supported kept text=Carol King (researcher:v1:61ad71f7aee0afa1185ca185ab9e3042) is the researcher matched for this question (identity outcome accepted, confidence 0.9900); see researcher:v1:deadbeefdeadbeefdeadbeefdeadbeef and evidence:v1:deadbeefdeadbeefdeadbeefdeadbeef.`
- T-02: `LlmOutputAdversarialSuite.scala:189 answer-claims: Right(AnswerDraft(Second summary.,List(),List())); plan draft: Right(PlanDraft(List(ShortestPath),List(Carol King),List(),None,List(),None,None,None,None,List()))`
- T-03: `ApiAdversarialSuite.scala:96 values are not the same … HTTP/1.1 404 Not Found` (expected 401)
- T-04: `ApiAdversarialSuite.scala:143 values are not the same` obtained 200, body `{"answer": {"abstained": false, … "answerText": "… Carol King (researcher:v1:61ad…` (expected 400)
- T-05: `ApiAdversarialSuite.scala:169 status 200 replayed=Some(true) queryId=run:v1:bbee9fc0b28acc2fcc708857f332798e cleanQueryId=run:v1:bbee9fc0b28acc2fcc708857f332798e`
- T-06 (Content-Length): `ApiAdversarialSuite.scala:116 status 500: HTTP/1.1 500 Internal Server Error … "error": "INTERNAL"`
- T-06 (chunked): `ApiAdversarialSuite.scala:186 status 500: HTTP/1.1 500 Internal Server Error`
- T-07: `LlmOutputAdversarialSuite.scala:149 Removed (unsupported): cites evidence:v1:deadbeefdeadbeefdeadbeefdeadbeef, which is not in the evidence bundle`
- T-08: `ConfigOmissionSuite.scala:112 values are not the same => Obtained List("/config/services-local-v1.json service.apiKeyEnvVar", "/config/services-local-v1.json service.readOnlyApiKeyEnvVar")`
- T-09: `ConfigOmissionSuite.scala:120 values are not the same` obtained a list starting `services-local-v1.json environment, aws.region, storage.rawBucket, storage.curatedBucket, … dynamoDb.researcherMetricCacheTable, vectors.*Index, embedding.provider, llm.provider, runtime.defaultWindowLabel`, repeated for rag-runtime-local-v2.json and storage-local-v1.json
- T-10: `UnsupportedClaimEndToEndSuite.scala:192 partially_supported: Carol King collaborated with Igor Petrov.`

Known items hit by my tests (marked `.fail`, reported as "same as", no new ID):
- S-4 (services P1): `LoggingRedactionCrossModuleSuite` "S-4 no log line carries the host or table name of a store failure". Observed: the `workflow-failed` line's `detail` = `workflow failed: StoreException: connect timed out to db-prod-7.cluster-ro-q1w2e3.us-east-1.neptune.amazonaws.com while reading scholarchain-prod-run-ledger ([redacted] for account [redacted]` (ARN and account are redacted; host and table are not).
- C-02 (reviewer C), same as C-02, two paths: (1) graph-port Left (what NeptuneGraphRead returns for any StoreException) becomes `No answer: neighbor lookup failed: connect timed out to <host> while reading <table> (arn:aws:dynamodb:…:123456789012:…) for account 123456789012.` in a 200 answerText (Stages.scala:538 `neighbor lookup failed: $e`; also :477, :510); ARN and account are NOT redacted on this path. (2) vector query failure: `Semantic context unavailable: …` (Stages.scala:745). Both `LoggingRedactionCrossModuleSuite` "C-02 …".
- B-01 (reviewer B), same as B-01: `NeptuneTimeoutStubSuite` "B-01 a client-side API call timeout is classified transient": `AwsErrors.wrap(ApiCallTimeoutException)` obtained transient=false.
- U-6 (domain P1): `ConfigOmissionSuite` "U-6 …": `service.jobQueueUrl: 'Sk-Live PHASE11/SENTINEL+value' must be an https:// URL with a host`.

## Failures by owning agent

| Owner | IDs (new) | Known items also exercised |
|---|---|---|
| rag-engineer | T-01 (major), T-10 (major), T-02, T-05 (rag part), T-07 | C-02 (two paths) |
| service-engineer | T-03, T-04, T-05 (services part), T-06 | S-4 |
| domain-engineer | T-08 (major), T-09 | U-6 |
| storage-engineer | none new | B-01 |

No new finding for hadoop, entity-resolution or spark (no pipeline suite added: categories 1-5 COVERED; Spark goes to step 3c).

## Coverage by requirement (21 categories + spec "Required test inventory")

Marks after this step. "new" = added in step 1b (tests/contracts/src/test/scala/org/scholarchain/contracts/…).

| # | Category | Suites (existing + new) | Mark now |
|---|---|---|---|
| 1 | Malformed and truncated DBLP XML | contracts/hadoop XmlBoundary, JobLevelDblp, RecordField; hadoop module suites | COVERED (unchanged) |
| 2 | Unicode normalization and confusables | contracts/hadoop Unicode; contracts/er NameKey, Identity; module suites. New: ApiAdversarialSuite T-05 (lone surrogate at the HTTP/fingerprint boundary) | COVERED (+1 finding) |
| 3 | Duplicate and conflicting DOI records | contracts/hadoop RecordField; module suites | COVERED (unchanged) |
| 4 | Common author names, reordered lists | contracts/hadoop ScaleAndProvenance, FallbackPipelineEndToEnd; contracts/er; new MissingMetadataSuite (ambiguous "Wei Wang" over HTTP abstains, lists both) | COVERED |
| 5 | Very large blocking groups | contracts/hadoop JobLevelBlocking, PairLimit, FallbackPipelineEndToEnd | COVERED (unchanged) |
| 6 | Unresolved or conflicting edge endpoints | spark PipelineFixtureSuite; rag; storage. Spark F3 cases are step 3c | PARTLY (Spark part: step 3c) |
| 7 | Empty and disconnected graphs | spark AlgorithmsSuite; rag. New: NeptuneTimeoutStubSuite (path search unknown vs. "no path") | PARTLY (empty-export runtime, plan item 16: not done; Spark S1a/S1b: step 3c) |
| 8 | Known-graph cases (degree, component, path, bridge, k-core) | spark AlgorithmsSuite, PipelineFixtureSuite; case sheet in test-inventory.md | PARTLY (step 3c encodes the sheet) |
| 9 | Large-team pair expansion; common-name resolution | contracts/hadoop JobLevelTeamScale, ScaleAndProvenance; new MissingMetadataSuite (9b over HTTP) | PARTLY (Spark T30: step 3c) |
| 10 | Missing topic, institution, abstract, profile metadata | rag TemplateRegistry, RagIngestJob, QueryWorkflow; storage VectorMetadata. New: MissingMetadataSuite (institution constraint 422; unmatched topic gives no semantic-only verified claim) | PARTLY (below-profileMinWorks researcher and OpenAlex-abstract absence not tested) |
| 11 | Duplicate vector keys, stale model versions | storage IngestPipelineSuite; rag RetrievalSmoke, EmbeddingProviders | PARTLY (plan item 11 not done: runtime with a mismatched retriever spec) |
| 12 | Prompt injection (title, abstract, full text) | rag suites. New: PromptInjectionCrossModuleSuite (question injection over HTTP: same plan and claims; planner sees it only as data; injected title/profile never in the answer; obeying model cannot cite a fabricated ID) | PARTLY (no abstract/full text stored; chunk-2 injection not tested) |
| 13 | LLM malformed JSON, fabricated identifiers | rag suites; contracts/er Llm, DecisionContract. New: LlmOutputAdversarialSuite (planner prose -> rule fallback, hash+length only, counters; truncated at max_tokens -> fallback, truncation counted; fabricated evidence ID dropped from citations; only-fabricated claim unsupported + abstain; summary sanitizer; T-01, T-02, T-07) | COVERED (3 findings) |
| 14 | Unsupported answer claims | rag ClaimVerification, Stages, QueryWorkflow. New: UnsupportedClaimEndToEndSuite (absent number conflicting + abstain; bound number; hop mismatch conflicting; semantic-only number unsupported; audit counts equal shown statuses; async result never verified; T-10) | COVERED (1 finding; L-1..L-3 excluded) |
| 15 | Neptune timeout | storage AwsEnvironmentSuite. New: NeptuneTimeoutStubSuite (real NeptuneGraphRead over a stub NeptunedataClient: path timeout -> "unknown", never "no path"; neighbour timeout never an empty list; workflow circuit opens at the threshold, no graph call while open, recovers after circuitOpenMillis on the ManualClock; B-01 mark) | COVERED |
| 16 | DynamoDB conditional-write conflict | storage suites; services (in-memory). New: DynamoConditionalConflictSuite (DynamoDbKeyValueStore over ConditionalDynamoStub: bundle Conflict never overwritten, Unchanged on repeat; lost response retried -> no conflict; two claims of one job version -> exactly one wins; services jobs+idempotency over DynamoDB: replay 200 without re-running, other body 409) | COVERED |
| 17 | S3 Vectors throttling / transient | storage IngestPipelineSuite (fake port). New: S3VectorsThrottlingStubSuite (real S3VectorsIndex + RetryingVectorIndex over a stub S3VectorsClient: 2x429 retried with 100,200 ms; always 429 -> transient after maxRetries with 100,200,400; 400 never retried; runtime: 200, no semantic citation, says why) | COVERED (RetryingVectorIndex NONE row closed) |
| 18 | Replayed workflow and idempotency | services, storage, rag module suites. New: ReplayIdempotencyCrossModuleSuite (two keys: one queryId, one bundle, two audits; audit write fails after bundle write: 502, no audit, retry same IDs, one audit; sync then async under one key: 409; replays never rerun) | COVERED |
| 19 | API auth, authorization, malformed bodies, timeouts | services HttpBehavior, QueryApi, ReadRoutes, CapacityAndFailure. New: ApiAdversarialSuite (case-changed key, key in query string, no key -> 401; read-only POST -> 403 and no model call; 1e400 -> 400 fixed message; T-03, T-04, T-05, T-06) | COVERED (4 findings) |
| 20 | Logging redaction | services HttpBehavior. New: LoggingRedactionCrossModuleSuite (502 body fixed; ARN/account redacted in logs; evidence-write failure 502 without store text; S-4 and C-02 marks) | COVERED (known S-4, C-02 open) |
| 21 | Configuration omissions | core ConfigLoader/ServiceConfig and module suites. New: ConfigOmissionSuite (each shipped test config loads; every key removed one at a time is refused naming its path or is optional; every section removed likewise; T-08, T-09, U-6 mark) | COVERED (2 findings) |
| R | Spec "Required test inventory": Spark known-graph cases | case sheet (test-inventory.md Part 3) | step 3c (spark-engineer), recorded deviation |
| R | Cross-record contract tests (Phase 3 deferral) | New: WindowProvenanceCrossRecordSuite (graph evidence windows are configured windows or one work year; claim window = a cited item's window; cited ⊆ bundle ⊆ audit evidence IDs; audit bundleId/graphRunId/queryRunId/provenance.runId agree; graph evidence keeps Spark provenance; EB3/EB1 read back equal) | PARTLY (plan item 13 identity cross-record over hadoop+ER and item 15 frozen wire consumers: not done) |

## Production-behavior inventory (step 1a Part 2, updated; only changed rows shown, all other rows as in test-inventory.md)

| Module | Entry point | Suites (new ones prefixed c/) |
|---|---|---|
| storage | RetryingVectorIndex | c/S3VectorsThrottlingStubSuite (was NONE) |
| storage | VectorIndexPort: S3VectorsIndex | + c/S3VectorsThrottlingStubSuite (query throttling, permanent 400) |
| storage | GraphReadPort: NeptuneGraphRead | + c/NeptuneTimeoutStubSuite (path and neighbour timeouts over a stub client) |
| storage | KeyValueStore: DynamoDbKeyValueStore; RetryingKeyValueStore | + c/DynamoConditionalConflictSuite |
| storage | EvidenceStore / QueryAuditWriter | + c/DynamoConditionalConflictSuite, c/ReplayIdempotencyCrossModuleSuite, c/WindowProvenanceCrossRecordSuite |
| storage | JobStore / IdempotencyStore over DynamoDB | + c/DynamoConditionalConflictSuite |
| storage | AwsErrors.wrap | + c/NeptuneTimeoutStubSuite (B-01 mark) |
| rag | Planner (LLM) fallback; Schemas | + c/LlmOutputAdversarialSuite |
| rag | Synthesis (retry, fallback, sanitizer) | + c/LlmOutputAdversarialSuite |
| rag | ClaimVerification | + c/UnsupportedClaimEndToEndSuite, c/LlmOutputAdversarialSuite |
| rag | QueryWorkflow (run ID, bundle, audit) | + c/ReplayIdempotencyCrossModuleSuite, c/WindowProvenanceCrossRecordSuite |
| rag | Stages (paths, collaborators, semantic) | + c/NeptuneTimeoutStubSuite, c/S3VectorsThrottlingStubSuite, c/LoggingRedactionCrossModuleSuite |
| services | POST /v1/query sync and async | + c/ApiAdversarialSuite, c/UnsupportedClaimEndToEndSuite, c/ReplayIdempotencyCrossModuleSuite, c/PromptInjectionCrossModuleSuite, c/MissingMetadataSuite |
| services | GET /v1/jobs/{id}/result | + c/UnsupportedClaimEndToEndSuite (async) |
| services | Auth | + c/ApiAdversarialSuite |
| services | HttpAdapter (framing, bodies) | + c/ApiAdversarialSuite |
| services | Circuit breaker (workflow) | + c/NeptuneTimeoutStubSuite |
| services | Logging and redaction | + c/LoggingRedactionCrossModuleSuite (S-4 open) |
| core | Config: all sections (omission and value echo) | + c/ConfigOmissionSuite |

NONE rows remaining (gate item "every production behavior has a relevant test"): storage RetryingObjectStore; spark GraphAnalyticsJob broadcast setting (F9 part 1, spark P1); spark Pipeline on a Hadoop FileSystem (F9 part 2, spark P1). RetryingVectorIndex is no longer NONE.

## Plan items not done (step-1a plan, Part 1)
- 11 VectorKeyAndModelVersionSuite (runtime retriever spec differs from the index model): not written; module coverage only.
- 12 partly: below-profileMinWorks researcher; OpenAlex abstract never reaching paper-abstract-v1: not written.
- 13 IdentityCrossRecordSuite (hadoop JobHarness + ER ErSynth): not written.
- 15 FrozenWireConsumersSuite (optional): not written.
- 16 EmptyGraphRuntimeSuite (zero-researcher export): not written (needs an empty-export writer; RuntimeFixtures.writeExport is fixed to its world).
- 3 partly: injection in the second chunk of a chunked summary: not written.
Proposed later: the items above, in that order.

## Residual risks
- T-01/T-10 show the verifier keeps claims with unverifiable identifiers or names as partially_supported; with a live model this is the main path by which model-made facts reach users. Severity is my proposal.
- C-02 path (1) is wider than the reviewer's example: on the graph-Left path the ARN and the 12-digit account are NOT redacted in the 200 answer (services' redactor runs only on logs).
- No abstract or full text is stored in this phase, so "prompt injection in abstract and full-text chunks" is covered only by absence; nothing tests that absence end to end.
- The Neptune and S3 Vectors stubs model the SDK exceptions I chose (ApiCallTimeoutException, TooManyRequestsException 429, ValidationException 400); live behavior is Phase 14 (RequiresAws).
- ConditionalDynamoStub has no Query; the audit trail over DynamoDB (RunLedger) is therefore not exercised through the DynamoDB adapter.
- Raw-socket tests (T-03, T-06) depend on the JDK HttpServer's handling of duplicate headers and short bodies; a JDK upgrade could change the observed status.
- `.fail` tests pass whenever their assertion fails for any reason; the observed messages above are the baseline for step 4.

## INTERFACE HANDOFF
- Files changed: only the new files listed under FILES CREATED (tests/contracts). No production, module-test, build or tracked test file touched.
- Public test fixtures others may use (package org.scholarchain.contracts.runtime):
  - `RuntimeAdversarialSupport.ScriptModel(rule: RulePlanner, planner: Option[Int => Scripted] = None, synthesis: Option[(Int, SynthesisInput) => Scripted] = None) extends LanguageModel` (provider Anthropic, records requests; default planner = rule planner, default synthesis = FakeSynthesis); `Scripted(text, stopReason)`; helpers `firstClaimEdited`, `withExtraClaim`, `responseText`, `auditText`, `fabricated(kind)`, `claim`.
  - `CrossModuleHarness.runtime(graph, kv, vectors, model, cfg): Runtime` (fixture world with each runtime port replaceable; retriever rebuilt over the wrapped vector index); `serve(rt, jobKv, cfg, clock, startWorker): Server` (role all, port 0, MemoryLogSink, ManualClock); `call`, `query`.
  - Stubs inside suites: `NeptuneTimeoutStubSuite#TimingOutNeptune` (NeptunedataClient), `S3VectorsThrottlingStubSuite#StubVectors` (S3VectorsClient), `#RecordingSleeper`.
- Thread-safety: ScriptModel is synchronized; harness servers are per test and closed in `finally`.
- Limits: deterministic (ManualClock, no sleeps for ordering; raw sockets use a 20 s read timeout as a bound only). Each suite builds fresh fixture worlds (temp directories under the JVM temp dir, as rag's fixtures do).
- `.fail` marks by ID: T-01, T-02, T-07 (LlmOutputAdversarialSuite); T-03, T-04, T-05, T-06 x2 (ApiAdversarialSuite); T-08, T-09, U-6 (ConfigOmissionSuite); T-10 (UnsupportedClaimEndToEndSuite); S-4, C-02 x2 (LoggingRedactionCrossModuleSuite); B-01 (NeptuneTimeoutStubSuite). 16 in total.

## TEST EVIDENCE

Targeted run:
```
TEST EVIDENCE
- command: sbt -batch "contractTests/testOnly org.scholarchain.contracts.runtime.LlmOutputAdversarialSuite org.scholarchain.contracts.runtime.UnsupportedClaimEndToEndSuite org.scholarchain.contracts.runtime.PromptInjectionCrossModuleSuite org.scholarchain.contracts.runtime.NeptuneTimeoutStubSuite org.scholarchain.contracts.runtime.DynamoConditionalConflictSuite org.scholarchain.contracts.runtime.S3VectorsThrottlingStubSuite org.scholarchain.contracts.runtime.ReplayIdempotencyCrossModuleSuite org.scholarchain.contracts.runtime.LoggingRedactionCrossModuleSuite org.scholarchain.contracts.runtime.MissingMetadataSuite org.scholarchain.contracts.services.ApiAdversarialSuite org.scholarchain.contracts.config.ConfigOmissionSuite org.scholarchain.contracts.crossrecord.WindowProvenanceCrossRecordSuite"
- finished: 2026-11-16 18:54:48 local (CST)
- per suite:
  LlmOutputAdversarialSuite run=8 passed=8 failed=0 skipped=0
  UnsupportedClaimEndToEndSuite run=7 passed=7 failed=0 skipped=0
  PromptInjectionCrossModuleSuite run=4 passed=4 failed=0 skipped=0
  NeptuneTimeoutStubSuite run=4 passed=4 failed=0 skipped=0
  DynamoConditionalConflictSuite run=4 passed=4 failed=0 skipped=0
  S3VectorsThrottlingStubSuite run=4 passed=4 failed=0 skipped=0
  ReplayIdempotencyCrossModuleSuite run=4 passed=4 failed=0 skipped=0
  LoggingRedactionCrossModuleSuite run=5 passed=5 failed=0 skipped=0
  MissingMetadataSuite run=3 passed=3 failed=0 skipped=0
  ApiAdversarialSuite run=8 passed=8 failed=0 skipped=0
  ConfigOmissionSuite run=6 passed=6 failed=0 skipped=0
  WindowProvenanceCrossRecordSuite run=5 passed=5 failed=0 skipped=0
- result line: [info] Passed: Total 62, Failed 0, Errors 0, Passed 62
- expected-failure flips: none (all 16 `.fail` tests failed as expected; their messages are listed under Findings)
- source changed after this run: no
```

Full check (wave-1 closer):
```
TEST EVIDENCE
- command: sbt -batch check > /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/858dada8-c8dd-43c7-b05d-961e323b855c/scratchpad/step1b-check.log 2>&1; echo "EXIT=$?" >> (same log)   [background, bounded polling]
- finished: 2026-11-16 19:08:16 local (CST)
- per suite: contract tests (no AWS) suites=34 executed=398 passed=398 failed=0 errors=0 skipped=0; module tests suites=109 executed=1877 passed=1877 failed=0 errors=0 skipped=3
- result line: [success] Total time: 801 s (0:13:21.0), completed Nov 16, 2026, 7:08:16 PM ; EXIT=0
- marker: [info] Updated /Users/brian/Developer/repos/scholarchain/.claude/.last-test-run (module_tests=1877, contract_tests=398)
- expected-failure flips: none
- source changed after this run: no
```
Note for the orchestrator: tests/contracts/target/test-reports will hold the 16 marked tests as passed (MUnit `.fail`); per the standing rules, the failure grep is not run there.
