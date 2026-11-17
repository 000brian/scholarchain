# Phase 11 step 1a: test inventory, step-1b plan and Spark case sheet (test-engineer)

STATUS: COMPLETE
OWNER: test-engineer
WORK ORDER: docs/agent-runs/phase-11/prompt.md (sha256 a0b59faeaeaae620ad87ce8b5dd1272efc1078d273e9a84baa426a9cd47e9ed1, not recomputed), step 1a, decision 4
FILES CREATED: /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/858dada8-c8dd-43c7-b05d-961e323b855c/scratchpad/phase11-reports/test-inventory.md (this report; nothing in the repository)
FILES MODIFIED: none
COMMANDS RUN: read-only only: `ls -R`, `grep -n/-rn/-rl/-A1`, `sed -n`, `cut`, `sort`, `tr`, `head`, one `find … | xargs wc -l` and one `cat` of modules/spark/conf/phase06.json (both read-only; see ASSUMPTIONS). No sbt, no git, no writes outside this report.
TEST RESULTS: n/a, read-only step (no test was run)
VALIDATION RESULTS: n/a. TEST EVIDENCE: n/a, read-only step.
ASSUMPTIONS:
- This session had no Grep or Glob tool (only Read, Write, Edit, Bash). To list and search files I used read-only shell commands from the standing-rules allow list (`ls`, `grep`, `sed -n`, `head`, `cut`, `sort`), plus one `find|xargs wc -l` and one `cat`, and `cd` inside commands. These differ from the letter of "Read, Grep, Glob only / no Bash" and from "read with Read, never cd". None of them wrote anything; the orchestrator should record this as a process note.
- "Covered" means a test exists that fails if the behaviour breaks, at module level or in tests/contracts. Cross-module gaps can leave a category "partly" even when module tests are strong.
- The step-1b plan assumes step 0 gives contractTests the rag, storage and services test fixtures (`compile->compile;test->test`). I checked the test-resource folders: tests/contracts (er-leakage, hadoop-adversarial, logback-test.xml), rag (benchmark/, config/rag-*.json), services (api/, config/services-local-v1.json), storage (config/storage-local-v1.json). No relative path appears twice.
- Spark case-sheet letters A, B, C … stand for researchers whose ResearcherIds sort in letter order (code points). Only component IDs depend on that order: componentId always means "the smallest member ID". Two cases differ:
  - Algorithm-level and Analytics-level cases can use free ID strings in letter order.
  - Pipeline-level cases (I1–I4, F3a–F3e, or any case run through Pipeline) must use ResearcherId.fromAnchorMention(earliest mention); otherwise researcher_anchor_mismatch fires and F3e fails. There, read componentId as "min ID of the members", and assign letters after sorting the derived IDs.
  - P, C, T, A, M, W, coreness, hops, the bridge values and the K1–K3 community answers do not depend on ID order. I checked K1 by hand for an arbitrary order.
RISKS:
- contractTests cannot reach sparkAnalytics, so the Spark known-graph values (Part 3) are checked only by spark-engineer's suite (a recorded deviation).
- I mapped tests by suite and test names plus symbol greps. A suite that is named for a behaviour but only checks it weakly would still show as "covered" here; step 5 judges test strength.
- No cases about the core frozen wire examples are planned in tests/contracts: `core` is wired compile-only, so its test resources (`wire/`) are not on the contractTests classpath. They can be read by file path if needed (plan item 15).
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: step 1b (test-engineer) follows Part 1's numbered plan; step 3c (spark-engineer) encodes Part 3.

---

## Part 1. Gap list for the 21 spec categories

Marks: COVERED, PARTLY, NOT. Suites are module suites unless prefixed `contracts/` (tests/contracts/src/test/scala/org/scholarchain/contracts/**).

| # | Category | Existing suites | Mark | Gap (what step 1b or 3c adds) |
|---|---|---|---|---|
| 1 | Malformed and truncated DBLP XML | contracts/hadoop XmlBoundaryAdversarialSuite, JobLevelDblpAdversarialSuite, RecordFieldAdversarialSuite; hadoop ScannerSuite, DblpExtractJobSuite, DblpParsingSuite | COVERED | none |
| 2 | Unicode normalization and confusables | contracts/hadoop UnicodeAdversarialSuite; contracts/er NameKeyAdversarialSuite, IdentityAdversarialSuite (NFC/NFD, Cyrillic); core AuthorNameKeySuite, PairOrderingSuite; ER NormalizeSuite, PriorSpellingSuite, ClusteringSuite | COVERED | none |
| 3 | Duplicate and conflicting DOI records | contracts/hadoop RecordFieldAdversarialSuite (duplicate-doi x4, duplicate-key); hadoop LinkAndBlockSuite, DblpParsingSuite; ER WorkResolutionSuite; core ExternalIdsSuite | COVERED | none |
| 4 | Common author names and reordered author lists | contracts/hadoop ScaleAndProvenanceAdversarialSuite (common-name, reordered list), FallbackPipelineEndToEndSuite; contracts/er IdentityAdversarialSuite, CannotLinkAdversarialSuite, NameKeyAdversarialSuite; hadoop FallbackBlockingSuite; rag QueryWorkflowSuite (ambiguous name, homonym suffix) | COVERED | none |
| 5 | Very large blocking groups | contracts/hadoop JobLevelBlockingSuite, PairLimitAdversarialSuite, FallbackPipelineEndToEndSuite; hadoop FallbackBlockingSuite, CandidateSuite | COVERED | none |
| 6 | Coauthor edges with unresolved or conflicting researcher endpoints | spark PipelineFixtureSuite (self pairs dropped; CONFLICT confidence carried; one planted pair_mention_unassigned and assignment_mention_missing); rag QueryWorkflowSuite (R-1 conflict uncertainty), StagesSuite (resolver conflict flag); storage NeptuneCsvSuite (endpoint without node rejected) | PARTLY | Phase 6 F3: coauthor_edge_endpoint_missing/inactive, team_pair_without_candidate, pair_not_in_team, team_size_mismatch, pair_year_mismatch are never planted. Spark case sheet I3, I4, F3a–F3e (step 3c). |
| 7 | Empty and disconnected graphs | spark AlgorithmsSuite (vertices with no edges; chain plus pair plus isolated), PipelineFixtureSuite (singletons); rag QueryWorkflowSuite (another component: no path) | PARTLY | A whole run with zero works, or zero works in a window, is never run through the Spark pipeline (case sheet S1a/S1b). A runtime over an empty export is never queried (plan item 16). |
| 8 | Distinct-degree, component, shortest-path, bridge, k-core known-graph cases | spark AlgorithmsSuite, PipelineFixtureSuite | PARTLY | No independent hand-derived sheet. Participation is never checked on a node whose edges all cross (PC 0 with cross > 0). Cross count by edges versus weights is never checked on a weight-2 bridge. Case sheet S2–S7, S7w (step 3c). |
| 9 | Large-team pair expansion and common-name researcher resolution | contracts/hadoop JobLevelTeamScaleSuite, ScaleAndProvenanceAdversarialSuite (120-author team, 7140 pairs); hadoop PairsAndTeamsSuite, DblpChainSuite; rag QueryWorkflowSuite and StagesSuite (ambiguous candidates) | PARTLY | No Spark check that a 30-author team gives 435 edges and C = T = M = 29 (case sheet T1–T30). No end-to-end ambiguous-name request over HTTP (plan item 12b). |
| 10 | Missing topic, institution, abstract or researcher-profile metadata | rag TemplateRegistrySuite (topic-institution template empty; "no authors"; missing inputs reject), RagIngestJobSuite (work without venue), QueryWorkflowSuite (institution filter unsupported); storage VectorMetadataSuite (missing metadata rejected); hadoop NormalizerSuite (abstracts/topics when present) | PARTLY | Never tested: a researcher below profileMinWorks (no profile document) asked about at runtime; a topic question with zero topic nodes; an OpenAlex abstract in the input never reaching a rendered paper document (plan item 12). |
| 11 | Duplicate vector keys and stale model versions | storage IngestPipelineSuite (same key with different text, new model re-embeds, duplicate keys in one run); rag RetrievalSmokeSuite (vectors of another model dropped and counted), EmbeddingProvidersSuite (spec ID changes) | PARTLY | Module level only. Never tested: a runtime query whose retriever spec differs from the index's stored model, through RuntimeService or the HTTP answer notes; Phase 7 m3 racing writers (known, P2 for storage, not re-reported) (plan item 11). |
| 12 | Prompt injection in title, abstract and full-text chunks | rag TemplateRegistrySuite (instruction-like title), QueryWorkflowSuite (injected title and profile, Cypher in the question), PlannerAndSchemaSuite (question injection, data block cannot be closed), RetrievalSmokeSuite (query not echoed), StagesSuite (excerpt marked untrusted) | PARTLY | No abstract and no full text is stored (paper-abstract-v1 writes a fixed sentence), so those two are covered only by absence, and nothing checks the absence. Never tested: an injection in the second chunk of a chunked summary; an injection that names a fabricated evidence ID; an injection through HTTP (plan item 3). |
| 13 | LLM malformed JSON and fabricated identifiers | rag PlannerAndSchemaSuite, StagesSuite (bounded retry then fallback; hash and length only), ClaimVerificationSuite (fabricated IDs), ModelCallsSuite, LlmClientsSuite; contracts/er LlmAdversarialSuite, DecisionContractAdversarialSuite; ER LlmSuite; core WireContractSuite (fabricated chosen candidate) | PARTLY | Never tested: a duplicate JSON key in the rag answer or plan reply (ER rejects these; rag is untested); end to end over HTTP with a scripted model (truncated JSON with stop_reason max_tokens, unknown evidence ID, researcher ID outside the bundle) with checks on response, audit counts and stored text (plan item 1). |
| 14 | Unsupported answer claims | rag ClaimVerificationSuite, StagesSuite (wrong claims removed; none verified: abstain), QueryWorkflowSuite | PARTLY | Strong in-module. Never tested end to end: an unsupported claim must never appear with status verified in the HTTP response, and must be counted in explain. L-1, L-2 and L-3 are known (rag P1) and are not re-reported (plan item 2). |
| 15 | Neptune timeout | storage AwsEnvironmentSuite (path search: call timeout stops the search; time limit is not complete); storage AwsAdaptersAwsSuite (RequiresAws) | PARTLY | Only path search over a stub client. Never tested: a timeout on neighbours, WorkById or a template read; the timeout through RuntimeService to the HTTP status (502/504, fixed message, no SDK text) and the circuit (plan item 4). |
| 16 | DynamoDB conditional-write conflict | storage AwsErrorMappingSuite, DynamoSuite, JobStoreSuite, IdempotencyStoreSuite (DynamoDB stubs); services WorkerSuite, IdempotencySuite (in-memory kv) | PARTLY | Services are tested only over the in-memory kv. Never tested: services over DynamoDbKeyValueStore(ConditionalDynamoStub); EvidenceStore.putBundle and QueryAuditWriter.append conflicts through the DynamoDB adapter (plan item 5). |
| 17 | S3 Vectors throttling or transient failure | storage IngestPipelineSuite (throttled PutVectors retried, bounded; permanent not retried; uses a fake port), AwsErrorMappingSuite (describeIndex only) | PARTLY | RetryingVectorIndex (query/get/describe retries) has no test. No stub S3VectorsClient test of S3VectorsIndex throttling. Never tested: the runtime behaviour when queries stay throttled ("Semantic context unavailable: $e", Stages.scala:745, puts store text into answer notes) (plan item 6). |
| 18 | Replayed workflow and idempotency | services IdempotencySuite, WorkerSuite; storage IdempotencyStoreSuite, JobStoreSuite, InMemoryWorkQueueSuite, WorkQueueSuite, ObjectStoreSuite, DynamoSuite; rag QueryWorkflowSuite (same query run ID; U-1 retries), RagIngestJobSuite (second run skips 100%); hadoop and ER rerun suites | COVERED (module); cross-module PARTLY | Never tested: a replay after a crash between the bundle write and the audit write; the same question under two idempotency keys gives one queryRunId and explain counts the attempts; key scope per caller class end to end (plan item 7). |
| 19 | API authentication, authorization, malformed bodies, timeouts | services HttpBehaviorSuite (401, 403, role 404, non-UTF-8, rate limit), QueryApiSuite (invalid JSON, unknown fields, oversized, media type, depth), ReadRoutesSuite (malformed IDs, cursors), CapacityAndFailureSuite (504 deadline), ComponentsSuite | PARTLY | Never tested: key variants (whitespace, case, a duplicate header, a key in the query string); a declared Content-Length longer than the body sent; a JSON lone-surrogate escape; a huge number literal; duplicate JSON keys in a request; a read-only key on every POST (plan item 8). |
| 20 | Logging redaction | services HttpBehaviorSuite (keys, arn:, 12-digit numbers, s3:// removed; no bodies, headers or paths); rag LlmClientsSuite and EmbeddingProvidersSuite (key never echoed); core ServiceConfigSuite (no values) | PARTLY | Downstream store and SDK messages reach logs (S-4, a known services P1) and possibly response notes (Stages.scala:745). Never tested across modules (plan item 9). |
| 21 | Configuration omissions | core ConfigLoaderSuite, ServiceConfigSuite; hadoop SettingsSuite; spark JobConfigSuite; ER PolicySuite; services CompositionSuite (jobQueueUrl); rag RuntimeServiceSuite (missing key variable), EmbeddingProvidersSuite (no embedding section); storage AwsEnvironmentSuite (no runtime or neptune section) | PARTLY | Never tested: each required key removed from each shipped test config with a check on the start-up path that consumes it (service, RuntimeService.local, storage environment), and the error names the path, never the value (plan item 10). U-6 is known (domain P1). |

Required test inventory (spec) beyond the 21: the "Spark known-graph" item goes to step 3c (Part 3). Cross-record contract tests deferred from Phase 3 are plan items 13–15.

### Step-1b plan (numbered, in the decided priority order)

All suites go under `tests/contracts/src/test/scala/org/scholarchain/contracts/<area>/` as new files. They are deterministic: ManualClock, GatedModel, scripted models, no sleeps for ordering. Fixtures: `ServiceTestKit.harness(cfg, model)`, `RuntimeServiceFixtures.fixture`/`FlakyStores`/`GatedModel`/`FailingModel`, `ServiceStoreFixtures`, `ConditionalDynamoStub`, `StubSqsClient`, `ManualClock`. The stub Neptune and S3 Vectors SDK clients are written in tests/contracts.

1. `runtime/LlmOutputAdversarialSuite` (cat. 13): a scripted `LanguageModel` returns (a) non-JSON, (b) JSON truncated with stop_reason max_tokens, (c) answer claims citing an unknown evidence ID, (d) claims naming a researcher ID outside the bundle, (e) a reply with a duplicate JSON key (planner and synthesis). Checks: HTTP 200; no fabricated ID in the response claims or evidence; template fallback or abstention; explain/audit invalid-output and fallback counters; stored invalid output is sha256 plus length only (no model text in the store or logs).
2. `runtime/UnsupportedClaimEndToEndSuite` (cat. 14): scripted claims with a number absent from the evidence, a name not in the cited evidence, semantic_context cited for a number, and a hop count different from the path evidence. Checks: never `verified` in the HTTP response; counts in the response verification and audit; no claim verified: abstain. L-1, L-2 and L-3 cases are excluded (known).
3. `runtime/PromptInjectionCrossModuleSuite` (cat. 12): an injection in the second chunk of a chunked profile summary; an injection in a title asking to cite a fabricated evidence ID; an injection in the HTTP question. Checks: plan equals the clean plan; facts unchanged; injection text never in answerText; no unknown evidence ID cited. Also an OpenAlex work with an abstract carrying an injection: the rendered paper-abstract-v1 document holds no abstract text.
4. `runtime/NeptuneTimeoutStubSuite` (cat. 15): a stub `NeptunedataClient` throws ApiCallTimeoutException on neighbours, WorkById and path at depth 2. Checks: NeptuneGraphRead returns Left or incomplete (never a partial answer presented as complete); RuntimeService answer or Left; HTTP 502/504 with a fixed message and no SDK text; circuit opens, then recovers.
5. `runtime/DynamoConditionalConflictSuite` (cat. 16): services harness built over `DynamoDbKeyValueStore(new ConditionalDynamoStub)` and `StubSqsClient`. Two claims of one job version: exactly one wins; an idempotency first-claim race; EvidenceStore.putBundle with other content: Conflict surfaced as an answer note, never overwritten; QueryAuditWriter append of other content: Conflict. S-3 is excluded (known).
6. `runtime/S3VectorsThrottlingStubSuite` (cat. 17): a stub `S3VectorsClient` throws ThrottlingException twice on QueryVectors, then succeeds. Checks: identical results and retry counters = 2 (RetryingVectorIndex). Always throttled: retriever Left; the answer's graph facts unchanged; the note carries no SDK message or ARN. PutVectors throttled through `S3VectorsIndex` inside VectorIngestPipeline: retried and recorded in RunLedger.
7. `runtime/ReplayIdempotencyCrossModuleSuite` (cat. 18): the same question under two keys gives one queryRunId and explain counts the attempts; FlakyStores fails the audit after the bundle write, then a retry gives the same bundle ID and no duplicate audit attempt; the same key under the full and the read-only caller gives separate records.
8. `services/ApiAdversarialSuite` (cat. 19): key with surrounding whitespace, case-changed key, two auth headers, key in the query string → 401; the read-only key on POST /v1/query → 403; a declared Content-Length longer than the body sent (client half-closes) → 400 without hanging (bounded client read timeout, no sleep); JSON `"\ud800"` escape, `1e400`, duplicate request keys → 400 INVALID_JSON or 422 with a fixed message.
9. `runtime/LoggingRedactionCrossModuleSuite` (cat. 20): a store failure whose message holds a host name, table name, ARN and 12-digit number. No log line (MemoryLogSink) and no response body or note shows them. Covers worker lines and the Stages.scala:745 note. S-4 is known: if it still fails, mark `.fail` and cite S-4.
10. `config/ConfigOmissionSuite` (cat. 21): for services-local-v1.json, rag-runtime-local-v2.json and storage-local-v1.json, remove each required key one at a time (and blank it through SCHOLARCHAIN_CFG_ overrides). The loader or consumer refuses and names the key path; the message never contains the value; the server does not start.
11. `runtime/VectorKeyAndModelVersionSuite` (cat. 11): ingest two documents mapping to one vector key with different text (rejected and recorded); a runtime retriever whose spec differs from the index model gets the vectors dropped, counted and noted in the answer, never mixed.
12. `runtime/MissingMetadataSuite` (cat. 10, 9b): a researcher below profileMinWorks (no profile document) is answered from graph facts only, with no topic claim; a topic question with zero topic nodes says unsupported or abstains; (12b) an ambiguous common name over HTTP lists candidates and abstains.
13. `crossrecord/IdentityCrossRecordSuite` (Phase 3 deferral): on a hadoop JobHarness plus ER ErSynth run, every pair mention ID is MentionId.of(workId, position) and exists in author_mention; identity assignments partition the subset mentions (each exactly once); ResearcherId = fromAnchorMention(earliest by year, workId, position); pair year = work year.
14. `crossrecord/WindowProvenanceCrossRecordSuite`: on the storage fixture export plus a rag query, every EvidenceItem window equals its edge or metric window; metric-cache window labels are config windows; Neptune CSV edge IDs = EdgeId.coauthor(l, r, window); audit graphRunId = export run ID; runtime provenance runId = queryRunId; GraphNode external-ID properties trace to a record in provenance.sourceRecordIds (Phase 3 N2).
15. `crossrecord/FrozenWireConsumersSuite` (optional, if time allows): core frozen wire examples read by path decode in the storage and rag consumers.
16. `runtime/EmptyGraphRuntimeSuite` (cat. 7, pipeline gap): a runtime over an export with zero researchers. Every operation abstains with a reason; no 500; readiness stays up.

Pipeline categories 1–5 are COVERED and get no new suites. The Spark parts of 6–9 are step 3c (Part 3).

---

## Part 2. Production-behavior inventory (public entry point → suites, or NONE)

Suite names without a prefix are in the module's own test tree. `c/` = tests/contracts.

### hadoop
| Entry point | Suites |
|---|---|
| J01 DblpExtractJob | DblpExtractJobSuite, c/JobLevelDblpAdversarialSuite, c/JobLevelRerunSuite, RunRecoverySuite |
| J02 NormalizeJob (OpenAlex, Crossref, ORCID) | PipelineSuite, NormalizerSuite |
| J04 ExactIdJoinJob | PipelineSuite (indirect), LinkAndBlockSuite (ExactLinker) |
| J05 BlockingJob | BlockingDeterminismSuite, FallbackBlockingSuite, CandidateSuite, c/JobLevelBlockingSuite, c/PairLimitAdversarialSuite, c/FallbackPipelineEndToEndSuite |
| J05b FallbackBlockingJob | FallbackBlockingSuite, c/FallbackPipelineEndToEndSuite, c/PairLimitAdversarialSuite |
| J05c FallbackCoverageJob | FallbackBlockingSuite, c/FallbackPipelineEndToEndSuite |
| J06 CandidateFeatureJob | CandidateSuite, FallbackBlockingSuite, c/JobLevelBlockingSuite, c/ScaleAndProvenanceAdversarialSuite |
| J07 PublicationTeamJob | DblpChainSuite, c/JobLevelTeamScaleSuite |
| J08a PairExpandJob | DblpChainSuite, c/JobLevelTeamScaleSuite |
| J08b PairConsolidateJob | DblpChainSuite, PairsAndTeamsSuite, c/ScaleAndProvenanceAdversarialSuite |
| J09 GraphExportJob | LinkAndBlockSuite, CandidateSuite (J09 export) |
| ScholarChainHadoop driver / chain | PipelineSuite, LocalPipelineRunnerSuite |
| JobRunner reuse and replaceIncomplete | c/JobLevelRerunSuite, RunRecoverySuite, DblpChainSuite (no code-version validation on reuse: Phase 4 finding 2, hadoop P1) |
| Settings / defaults resource | SettingsSuite, c/ScaleAndProvenanceAdversarialSuite |
| Scanners (record, line) | ScannerSuite, c/XmlBoundaryAdversarialSuite |
| InputOpener / GuardedInputStream (gzip guard) | DblpExtractJobSuite (indirect) |
| Rejects / Disposition records | c/JobLevelDblpAdversarialSuite, c/ScaleAndProvenanceAdversarialSuite |
| PathIds (path-safe identifiers) | PipelineSuite, DblpChainSuite (indirect) |

### entity-resolution
| Entry point | Suites |
|---|---|
| ErMain (arguments, policy override) | PolicySuite |
| ErRun (whole run, reuse, determinism) | RunSuite, c/ErDeterminismAndQueueSuite, c/IdentityAdversarialSuite |
| Loader (inputs) | WorkResolutionSuite, PriorSpellingSuite, c/ErDeterminismAndQueueSuite (contract refusal) |
| WorkResolution | WorkResolutionSuite, RunSuite |
| PairFeatures / Decide / Policy | DecisionSuite, PolicySuite, GuardPathSuite, c/DecisionContractAdversarialSuite |
| LlmStage / Adjudication | LlmSuite, c/LlmAdversarialSuite |
| Clustering / CannotLinks | ClusteringSuite, CannotLinkRunSuite, c/CannotLinkAdversarialSuite |
| ReviewQueue / Report | RunSuite, ReviewKeyRepairSuite, c/ErMembershipAndClusterRegressionSuite |
| GoldLabels / Harness / IntervalReliability | EvalSuite, HarnessDesignSuite, c/GoldAndHarnessAdversarialSuite, c/EvalReportRound3AdversarialSuite |
| EvalMain / EvalReport | EvalReportSuite, EvalRepairRound2Suite, c/EvalReport*AdversarialSuite (2 `.fail`: repair-21, repair-29 at EvalReportRound3AdversarialSuite.scala:305 and :998) |
| Matching/eval boundary | BoundarySuite, c/GoldAndHarnessAdversarialSuite |

### spark (sparkAnalytics)
| Entry point | Suites |
|---|---|
| GraphAnalyticsJob (EMR main) | JobConfigSuite (arguments only); broadcast-threshold setting: NONE (F9 part 1, spark P1) |
| JobConfig (phase06.json) | JobConfigSuite |
| Inputs (decode, rejects) | PipelineFixtureSuite (rejects) |
| GraphBuild (authorship, pairs, violations, nodes, edges) | PipelineFixtureSuite (only 2 of 11 violation checks planted: F3) |
| Analytics (P, C, T, A, M, W, bridges) | PipelineFixtureSuite |
| Algorithms (components, LPA, coreness, BFS) | AlgorithmsSuite, PipelineFixtureSuite |
| Paths (evidence paths, diameter) | PipelineFixtureSuite |
| Summaries (semantic summaries) | PipelineFixtureSuite |
| Output (manifests, reuse check) | PipelineFixtureSuite (reuse must match manifest); `_`-files and code version not verified (F6, spark P1) |
| Pipeline over non-local FS | NONE (F9 part 2, spark P1) |
| JsonlExport | JsonlExportSuite |

### core
| Entry point | Suites |
|---|---|
| StableIds / Hashing | StableIdsSuite |
| Contract encode/decode, SchemaVersions | WireContractSuite, SchemaVersionsSuite, GraphAnalyticsContractSuite, RuntimeContractSuite, SemanticContractSuite |
| Validator | ValidationSuite, RuntimeContractSuite, SemanticContractSuite |
| AuthorNameKey / ExternalIds / PairOrdering | AuthorNameKeySuite, ExternalIdsSuite, PairOrderingSuite, c/NameKeyAdversarialSuite |
| Config: base (graph, ER thresholds, storage, aws, bridge) | ConfigLoaderSuite |
| Config: embedding, vectors, objectStore, dynamoDb, neptune | ConfigLoaderSuite |
| Config: llm, runtime | ConfigLoaderSuite |
| Config: service | ServiceConfigSuite (U-6 value leak: domain P1) |

### storage
| Entry point | Suites |
|---|---|
| ObjectStore: InMemory / LocalFile / S3ObjectStore | ObjectStoreSuite, AwsErrorMappingSuite, AwsAdaptersAwsSuite (RequiresAws) |
| RetryingObjectStore | NONE |
| KeyValueStore: InMemory / DynamoDbKeyValueStore | DynamoSuite, AwsErrorMappingSuite, AwsEnvironmentSuite |
| RetryingKeyValueStore | AwsEnvironmentSuite, JobStoreSuite, IdempotencyStoreSuite |
| VectorIndexPort: InMemoryVectorIndex / S3VectorsIndex | IngestPipelineSuite, RetrievalSmokeSuite (rag), AwsErrorMappingSuite (describeIndex only) |
| RetryingVectorIndex | NONE |
| VectorIngestPipeline / PutVectorsBatcher / VectorWriter | IngestPipelineSuite |
| VectorKeys / VectorMetadataCodec / IndexSpec | VectorKeysSuite, VectorMetadataSuite |
| GraphReadPort: InMemoryGraph / NeptuneGraphRead | GraphQuerySuite, AwsEnvironmentSuite |
| GraphBulkLoader: NeptuneBulkLoader / BulkLoadRequests | NeptuneCsvSuite, AwsEnvironmentSuite |
| NeptuneCsvBuilder / OpenCypherCsv | NeptuneCsvSuite, LocalRuntimeEnvironmentSuite |
| CypherTemplates (read-only, parameterised) | GraphQuerySuite |
| DynamoLoader / ItemBuilders / TableSpecs | DynamoSuite |
| RunLedger / ArtifactRegistry | DynamoSuite, IngestPipelineSuite |
| MetricCacheReader / EntityLinkReader / EvidenceStore / QueryAuditWriter / TextStore | RuntimeReadersSuite, AwsErrorMappingSuite (TextStore) |
| ExportSource / TableReader | ExportSourceSuite |
| WorkQueue: InMemory / Manifest / SqsWorkQueue | InMemoryWorkQueueSuite, WorkQueueSuite, ServiceStoresSuite (StubSqsClient) |
| IngestWorkPlanner | WorkQueueSuite |
| JobStore (TableJobStore) | JobStoreSuite |
| IdempotencyStore (TableIdempotencyStore) | IdempotencyStoreSuite |
| ServiceStores (over, local, aws) | ServiceStoresSuite |
| LocalStorageEnvironment / LocalRuntimeEnvironment | LocalRuntimeEnvironmentSuite, LocalRealRunSuite, IngestPipelineSuite |
| AwsStorageEnvironment / AwsClientSettings | AwsEnvironmentSuite, ServiceStoresSuite |
| StubEmbedder / EmbeddingPort | IngestPipelineSuite |

### rag (with prompts/**)
| Entry point | Suites |
|---|---|
| ExportDocuments / TemplateRegistry / ChunkingPolicy / PaperSummaryRenderer | TemplateRegistrySuite, RagIngestJobSuite |
| RagIngestJob (local and aws) | RagIngestJobSuite |
| Embedders: Voyage / Bedrock Titan / FakeLexical; EmbeddingProviders; RateLimiter | EmbeddingProvidersSuite, LiveProvidersAwsSuite (RequiresAws) |
| SemanticRetriever / SemanticContext / SmokeQueries | RetrievalSmokeSuite, StagesSuite |
| Prompts (versioned load) / Schemas / AnswerClaimsSchema | PlannerAndSchemaSuite |
| Planner: RulePlanner / LLM planner | PlannerAndSchemaSuite |
| LLM clients: Anthropic / Bedrock / Fake; ModelCalls counters | LlmClientsSuite, ModelCallsSuite, LiveLlmAwsSuite (RequiresAws) |
| ResearcherResolver | StagesSuite, QueryWorkflowSuite |
| Stages (metrics, paths, collaborators, community analyst, semantic) | StagesSuite, QueryWorkflowSuite |
| EvidenceBuilder | StagesSuite, ClaimVerificationSuite |
| Synthesis (retry, fallback, summary sanitizer) | StagesSuite, ModelCallsSuite |
| ClaimVerification | ClaimVerificationSuite |
| QueryWorkflow (fingerprint, run ID, bundle, audit) | QueryWorkflowSuite |
| RuntimeService (local, aws, fromStores, close) | RuntimeServiceSuite, CompositionSuite (services) |
| BenchmarkRunner / AnswerKey | BenchmarkRunnerSuite |

### services
| Entry point | Suites |
|---|---|
| GET /healthz, GET /readyz (HealthService) | HttpBehaviorSuite, CompositionSuite, GoldenExamplesSuite |
| POST /v1/query sync and async (QueryService, QueryRequestParser) | QueryApiSuite, IdempotencySuite, CapacityAndFailureSuite, GoldenExamplesSuite |
| GET /v1/jobs/{jobId} and /result (JobService) | QueryApiSuite, WorkerSuite, CapacityAndFailureSuite |
| GET /v1/evidence/{evidenceId} | ReadRoutesSuite, GoldenExamplesSuite |
| GET /v1/explain/{queryId} | ReadRoutesSuite, QueryApiSuite |
| GET /v1/graph/nodes/{nodeId} and /neighbors | ReadRoutesSuite, CapacityAndFailureSuite |
| Auth (roles, keys) | HttpBehaviorSuite |
| Rate limiter, circuit breaker, errors | ComponentsSuite, HttpBehaviorSuite, CapacityAndFailureSuite |
| HttpAdapter (body limits, media type, IDs) | QueryApiSuite, HttpBehaviorSuite, ComponentsSuite |
| Logging and redaction | HttpBehaviorSuite (S-4 open: services P1) |
| Worker (claim, lease, attempts, results) | WorkerSuite |
| Main / ServiceServer (args, config, shutdown) | CompositionSuite, HttpBehaviorSuite |

**NONE rows (gate item "every production behavior has a relevant test"):** storage RetryingObjectStore; storage RetryingVectorIndex; spark GraphAnalyticsJob broadcast setting (F9 part 1); spark Pipeline on a Hadoop FileSystem (F9 part 2). Plan item 6 covers RetryingVectorIndex. RetryingObjectStore stays NONE unless storage adds a test (proposed: storage, P2 or later). The two spark rows are spark P1 items in step 3c.

---

## Part 3. Spark known-graph case sheet (hand-derived; for spark-engineer, step 3c)

### Definitions used (from modules/spark/src/main/** only; values derived by hand)
- Vertices of a window are the researchers with at least one authorship whose work year lies in the window (Pipeline `active`). A researcher with no in-window work has no row in that window.
- P = distinct in-window works. T = sum over those works of (n − 1), where n = distinct researchers on the work. M = max of (n − 1). A = T / P (absent when P = 0). C = number of coauthor edges (distinct coauthors). W = sum of edge weights. Edge weight = number of distinct in-window works shared (jointPublicationCount); minEdgeWeight 1.0.
- Coreness is the unweighted k-core number (h-index iteration); an isolated vertex has 0.
- Component ID = smallest member ResearcherId; size = members.
- Bridges under a stated community map: PC = 1 − Σ_s (k_s / k)², with k_s = summed weight into community s (own included) and k = W; absent when W = 0. cross = coauthors in other communities, counted by edges (not weight). connected = distinct other communities.
- Hops = BFS distance; `none` = not reachable.
- identityConfidence = min(own authorship confidences, minIdentityConfidence of the researcher's edges).
- Self pairs (both mentions resolve to one researcher) are dropped and counted in `erSelfPairs`. Pair rows for one (left, right, work) collapse to one supporting work.
- Window default for every case: `full-2015-2025` (2015–2025). Algorithm caps as phase06.json (components 30, LPA 10, self weight 1.0, k-core 30, path depth 6).

### Inputs (papers: id(year, [authors in position order]))

| case | papers | window | note |
|---|---|---|---|
| S1a | none | 2015-2025 | empty run |
| S1b | w1(2014,[A,B]) | 2015-2025 | all works outside the window |
| S2 | w1(2016,[A]); w2(2018,[A]) | 2015-2025 | single researcher |
| S3 | w1(2016,[A,B]); w2(2019,[A,B]); w3(2021,[C,D]) | 2015-2025 | two disconnected pairs |
| S4 | w1(2016,[A,B]); w2(2017,[A,C]); w3(2018,[A,D]); w4(2019,[A,E]) | 2015-2025 | star, centre A |
| S5 | w1(2016,[A,B]); w2(2017,[B,C]); w3(2018,[C,D]); w4(2019,[D,E]) | 2015-2025 | path A-B-C-D-E |
| S6 | w1(2016,[A,B,C,D]); w2(2018,[A,B]) | 2015-2025 | 4-clique, AB weight 2 |
| S7 | w1(2016,[A,B]); w2(2016,[A,C]); w3(2017,[B,C]); w4(2018,[D,E]); w5(2018,[D,F]); w6(2019,[E,F]); w7(2020,[C,D]) | 2015-2025, also w2015-2017 and w2018-2020 | two triangles + bridge C-D |
| S7w | S7 + w8(2021,[C,D]) | 2015-2025 | bridge weight 2 |

### Expected per-researcher values (machine-readable)

`case|window|researcher|P|C|T|A|M|W|coreness|componentId|componentSize`

```
S1a|2015-2025|-|no rows|0 edges|0 components
S1b|2015-2025|-|no metric rows|0 coauthor edges in the window|researcher nodes A,B still exist (all-time)
S2|2015-2025|A|2|0|0|0.0|0|0.0|0|A|1
S3|2015-2025|A|2|1|2|1.0|1|2.0|1|A|2
S3|2015-2025|B|2|1|2|1.0|1|2.0|1|A|2
S3|2015-2025|C|1|1|1|1.0|1|1.0|1|C|2
S3|2015-2025|D|1|1|1|1.0|1|1.0|1|C|2
S4|2015-2025|A|4|4|4|1.0|1|4.0|1|A|5
S4|2015-2025|B|1|1|1|1.0|1|1.0|1|A|5
S4|2015-2025|C|1|1|1|1.0|1|1.0|1|A|5
S4|2015-2025|D|1|1|1|1.0|1|1.0|1|A|5
S4|2015-2025|E|1|1|1|1.0|1|1.0|1|A|5
S5|2015-2025|A|1|1|1|1.0|1|1.0|1|A|5
S5|2015-2025|B|2|2|2|1.0|1|2.0|1|A|5
S5|2015-2025|C|2|2|2|1.0|1|2.0|1|A|5
S5|2015-2025|D|2|2|2|1.0|1|2.0|1|A|5
S5|2015-2025|E|1|1|1|1.0|1|1.0|1|A|5
S6|2015-2025|A|2|3|4|2.0|3|4.0|3|A|4
S6|2015-2025|B|2|3|4|2.0|3|4.0|3|A|4
S6|2015-2025|C|1|3|3|3.0|3|3.0|3|A|4
S6|2015-2025|D|1|3|3|3.0|3|3.0|3|A|4
S7|2015-2025|A|2|2|2|1.0|1|2.0|2|A|6
S7|2015-2025|B|2|2|2|1.0|1|2.0|2|A|6
S7|2015-2025|C|3|3|3|1.0|1|3.0|2|A|6
S7|2015-2025|D|3|3|3|1.0|1|3.0|2|A|6
S7|2015-2025|E|2|2|2|1.0|1|2.0|2|A|6
S7|2015-2025|F|2|2|2|1.0|1|2.0|2|A|6
S7|w2015-2017|A|2|2|2|1.0|1|2.0|2|A|3
S7|w2015-2017|B|2|2|2|1.0|1|2.0|2|A|3
S7|w2015-2017|C|2|2|2|1.0|1|2.0|2|A|3
S7|w2015-2017|D,E,F|no rows
S7|w2018-2020|C|1|1|1|1.0|1|1.0|1|C|4
S7|w2018-2020|D|3|3|3|1.0|1|3.0|2|C|4
S7|w2018-2020|E|2|2|2|1.0|1|2.0|2|C|4
S7|w2018-2020|F|2|2|2|1.0|1|2.0|2|C|4
S7|w2018-2020|A,B|no rows
S7w|2015-2025|C|4|3|4|1.0|1|4.0|2|A|6
S7w|2015-2025|D|4|3|4|1.0|1|4.0|2|A|6
S7w|2015-2025|A,B,E,F|as S7
```

One-line derivations:
- S1a: no authorship, so no vertex, edge, metric row or component. Expected outcome: the run completes with empty tables. If the module refuses empty input, it must refuse with a reason and write no partial manifest. spark-engineer reports which happens; a silent empty success with missing tables is a defect.
- S1b: the year 2014 is outside 2015–2025, so `active` is empty in that window. Nodes and AUTHORED edges are all-time, so A and B nodes exist.
- S2: two solo works; n = 1 on each, so T = M = 0; no edges, so C = W = 0, coreness 0; singleton component.
- S3: A and B share w1 and w2 (weight 2); each work has n = 2, so T = 1 + 1 = 2; C and D share w3. Two components, IDs A and C.
- S4: the centre has 4 two-author works and 4 edges of weight 1. Coreness: the leaves have degree 1, so the h-index of the centre's neighbours [1,1,1,1] is 1.
- S5: inner nodes have 2 works and 2 edges; the path is a 1-core.
- S6: A has w1 (n = 4, others 3) and w2 (others 1): T = 4, M = 3, A = 2.0. Edges AB = 2, AC = 1, AD = 1, so W = 4. C has only w1: T = M = 3, W = 3. The clique gives coreness 3.
- S7: all two-author works, so T = P and M = 1. C's neighbours have degrees [3, 2, 2]; the h-index is 2, so the bridge does not raise coreness. Window 2018–2020 drops w1–w3, so C keeps only w7 (degree 1, coreness 1).
- S7w: w8 adds a second C-D work: edge C-D weight 2 (firstYear 2020, lastYear 2021, works [w7, w8]). P_C = 4, W_C = 4, C_C still 3.

### Shortest-path hops (window 2015-2025 unless stated)

`case|pair|hops`
```
S3|A-B|1
S3|A-C|none
S3|C-D|1
S4|A-E|1
S4|B-C|2
S4|B-E|2
S5|A-C|2
S5|B-D|2
S5|A-E|4
S6|A-D|1
S6|C-D|1
S7|A-B|1
S7|C-D|1
S7|A-D|2
S7|A-F|3
S7|B-E|3
S7(w2018-2020)|C-F|2
S7(w2018-2020)|A-F|none (A not a vertex)
S2|A-A|0 (source only)
```
Derivation: BFS levels by hand. In S7, A-C-D-F is the only 3-hop route; A-C is the unique first hop because A-B-C would be longer.

### Bridges under a stated community map (feed the map directly to `Analytics.bridges`; not from LPA)

`case|communities|researcher|PC|cross|connected`
```
S4|X={A,B,C} Y={D,E}|A|0.5|2|1
S4|X={A,B,C} Y={D,E}|B|0.0|0|0
S4|X={A,B,C} Y={D,E}|D|0.0|1|1
S4|X={A,B,C} Y={D,E}|E|0.0|1|1
S5|X={A,B,C} Y={D,E}|A|0.0|0|0
S5|X={A,B,C} Y={D,E}|B|0.0|0|0
S5|X={A,B,C} Y={D,E}|C|0.5|1|1
S5|X={A,B,C} Y={D,E}|D|0.5|1|1
S5|X={A,B,C} Y={D,E}|E|0.0|0|0
S6|X={A,B} Y={C,D}|A|0.5|2|1
S6|X={A,B} Y={C,D}|B|0.5|2|1
S6|X={A,B} Y={C,D}|C|0.4444444444|2|1
S6|X={A,B} Y={C,D}|D|0.4444444444|2|1
S7|X={A,B,C} Y={D,E,F}|A|0.0|0|0
S7|X={A,B,C} Y={D,E,F}|C|0.4444444444|1|1
S7|X={A,B,C} Y={D,E,F}|D|0.4444444444|1|1
S7|X={A,B,C} Y={D,E,F}|F|0.0|0|0
S7w|X={A,B,C} Y={D,E,F}|C|0.5|1|1
S7w|X={A,B,C} Y={D,E,F}|D|0.5|1|1
S2|X={A}|A|absent (W=0)|0|0
```
Derivations:
- S4 A: k = 4, k_X = 2 (B, C), k_Y = 2 → 1 − (0.25 + 0.25) = 0.5; D and E are other-community edges.
- S4 D: its only edge goes to X, so k_X/k = 1 → PC = 0 although cross = 1. This checks that PC measures spread, not crossing.
- S5 C: one edge into its own X, one into Y → 0.5.
- S6 C: k = 3, k_X = 2 (A, B), k_Y = 1 (D) → 1 − (4/9 + 1/9) = 4/9.
- S6 A: k_X = 2 (AB weight 2), k_Y = 2 → 0.5.
- S7 C: k_X = 2, k_Y = 1 → 4/9.
- S7w C: k_X = 2, k_Y = 2 (C-D weight 2) → 0.5, but cross stays 1. This checks that cross counts edges, not weight.
- S2: W = 0, so PC is absent; cross = connected = 0 (Pipeline's coalesce).

### Identity and team cases

`case|input|expected`
```
I1-self-pair|w1(2016) mentions m0->A, m1->A, m2->B; dblp teamSize 3; pairs (m0,m1),(m0,m2),(m1,m2)|erSelfPairs=1; one edge A-B weight 1 works [w1]; A: P1 C1 T1 A1.0 M1 W1.0 coreness1; M evidence for A: [w1] distinctResearchers 2 dblpTeamSize 3; one AUTHORED edge A-w1 carrying mentionIds [m0,m1]; no team_size_mismatch, no pair_not_in_team, no team_pair_without_candidate
I2-repeat-pair|w1(2016,[A,B]); w2(2016,[A,B]); w3(2019,[A,B]); the J08b pair row for w1 supplied twice|edge A-B weight 3 jointPublicationCount 3 firstYear 2016 lastYear 2019 works [w1,w2,w3] (duplicate row collapses); A: P3 C1 T3 A1.0 M1 W3.0 coreness1; window w2018-2020: weight 1, firstYear=lastYear=2019, works [w3]
T1|w1(2016,[A])|0 pairs, 0 edges; A: P1 C0 T0 A0.0 M0 W0.0 coreness0; component A size 1
T2|w1(2016,[A,B])|1 edge; each P1 C1 T1 A1.0 M1 W1.0 coreness1; component A size 2
T3|w1(2016,[A,B,C])|3 edges (n(n-1)/2=3); each P1 C2 T2 A2.0 M2 W2.0 coreness2; component A size 3; all hops 1
T30|w1(2016,[R01..R30]) 30 distinct researchers|435 edges (30*29/2), each weight 1 works [w1]; each P1 C29 T29 A29.0 M29 W29.0 coreness29; component R01 size 30; all pair hops 1; M evidence [w1] distinctResearchers 30 dblpTeamSize 30; no oversize flag (default threshold 100)
I3-unresolved|w1(2016) m0->A, m1->B, m2 no identity assignment; teamSize 3; pairs (m0,m1),(m0,m2),(m1,m2)|exactly 2 pair_mention_unassigned violations; m2 in the unresolved-mentions output, never merged; one edge A-B weight 1; A,B: P1 C1 T1 M1 W1.0; M evidence distinctResearchers 2 dblpTeamSize 3; 2 researchers in total; no team_size_mismatch
I4-conflict|w1(2016,[A,B]); w2(2017,[B,C]); A assigned CONFLICT conf 0.5, B ACCEPTED 1.0, C ACCEPTED 0.9|edges A-B minIdentityConfidence 0.5, B-C 0.9 (CONFLICT endpoint kept, not dropped); node A identityOutcome conflict, B and C accepted; identityConfidence A 0.5, B 0.5 (via edge A-B), C 0.9; A: P1 C1 W1.0; B: P2 C2 T2 W2.0; C: P1 C1 W1.0
```
Derivations:
- I1: authorship is distinct per (researcher, work), so n on w1 is 2. The pair (m0,m1) maps to (A,A) and is dropped; (m0,m2) and (m1,m2) both map to (A,B,w1) and collapse to one supporting work.
- I2: weight counts distinct works; a repeated pair row for one work cannot add weight.
- T-n: one work with n distinct researchers gives C = T = M = n − 1 and a clique, so coreness = n − 1.
- I3: an unassigned mention gives no researcher; both of its pairs fail the assignment join.
- I4: confidence is the minimum along the authorship and the edge; B inherits 0.5 through its edge with A.

### Phase 6 F3 planted integrity checks (one row each; base S3 plus one plant per case)

`case|plant|expected violations (check -> count)`
```
F3a|add identity assignment m9->Z where m9 is not in author_mention, and pair (m0 of w1, m9) on w1 year 2016|assignment_mention_missing->1; pair_not_in_team->1 (A|Z work w1); coauthor_edge_endpoint_missing->1 per window containing 2016 (subject Z); coauthor_edge_endpoint_inactive->1 per same window (Z); with phase06 windows that is full-2015-2025 and w2015-2017; A's C becomes 2 and W 3.0 (degree reads edges) while P2 T2 stay
F3b|teams row for w3 says teamSize 3 while w3 has 2 mentions|team_size_mismatch->1 (w3, "teamSize 3 vs mentions 2")
F3c|pair row on w1 with year 2017 (work w1 year 2016)|pair_year_mismatch->1; the edge still uses year 2016 (weight and firstYear unchanged)
F3d|remove the J08b pair row of w3 (C,D) while authorships stay|team_pair_without_candidate->1 (C|D work w3); no C-D edge; C and D become singletons (P1 C0 T1 M1 W0.0)
F3e|base S3 with no plant|no violation of any check (all 11 checks plus the 2 window checks report 0)
```
Derivations:
- F3a: Z has no authorship, so Z is not in the vertex index: the edge A-Z exists but its endpoint is missing and inactive. In the base S3, A's pairs are only on w1 and w2 with B, so the added A-Z pair is not a team pair.
- F3d: edges come only from pairs; T and M come from authorship, so T = M = 1 while C = 0. This mismatch is the visible symptom.

### Community cases (same/different only; kept separate)

`case|input|expected`
```
K1|w1(2016,[A,B,C,D,E]); w2(2016,[F,G,H,I,J]); w3(2017,[E,F])|A,B,C,D,E same community; F,G,H,I,J same community; E and F different communities
K2|w1(2016,[A,B,C,D]); w2(2016,[E,F,G,H]) (no shared work)|A..D same community; E..H same; any member of the first and any of the second different
K3|w1(2016,[A,B]); w2(2017,[C]) (C solo)|C alone in its own community (community size 1); C different from A and B
```
- K1: each 5-clique has 10 internal edges; the single bridge edge E-F cannot outvote 4 internal edges plus the self vote.
- K2: labels propagate only along edges, so disconnected components can never share a label.
- K3: an isolated vertex receives no vote, so it keeps its own label.

Cases left out on purpose: two triangles joined by a bridge, a weighted path, and pendant ties. Their LPA outcome depends on the self vote and the smallest-ID tie rule, so they are not structure-determined.
