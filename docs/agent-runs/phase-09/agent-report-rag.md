## Summary

I implemented decision 5 (5a to 5o) inside `modules/rag/**` and `prompts/**` only; no core or storage change was needed. All ten stages are in package `org.scholarchain.rag.runtime`, and one `QueryWorkflow` runs them in order: QueryPlanner, ResearcherResolver, CollaborationMetrics, PathFinder, CommunityAnalyst, SemanticContext, EvidenceBuilder, AnswerSynthesis, ClaimVerification, Audit. Stores are reached only through storage's `RuntimeStores` readers, writers and graph templates.

`sbt check` passed with exit 0, observed once. After it, the single `BenchmarkRunner --llm fake` run completed and met every target:
- Plan accuracy: 30/30.
- Identity resolution: 30/30, with no silent choice on the ambiguous name.
- Deterministic graph retrieval: 110/110 exact matches; path lengths 4/4 against the key.
- Expected abstentions: 3/3, with no unexpected ones.
- Planted errors: 89/89 caught.
- Injection question: plan as expected and 10/10 values.
- Claims: 175 verified, and none partially supported, unsupported or conflicting.

I wrote nothing under `docs/agent-runs/`.

```text
STATUS: COMPLETE
OWNER: rag-engineer
WORK ORDER: docs/agent-runs/phase-09/prompt.md, decision 5 (5a-5o): the spec Phase 9 runtime collaboration-analysis workflow, including A6/A7 and the rag side of A1
FILES CREATED:
  /Users/brian/Developer/repos/scholarchain/modules/rag/src/main/scala/org/scholarchain/rag/runtime/RuntimeModel.scala      (QueryRequest, PlanDraft, PlanBounds, RuntimeContext, ModelAttempt, typed facts)
  /Users/brian/Developer/repos/scholarchain/modules/rag/src/main/scala/org/scholarchain/rag/runtime/Schemas.scala           (PlanDraftSchema, AnswerClaimsSchema, SemanticContextSchema, SchemaFiles agreement check)
  /Users/brian/Developer/repos/scholarchain/modules/rag/src/main/scala/org/scholarchain/rag/runtime/Prompts.scala           (load by path, hash, one-pass render, asData)
  /Users/brian/Developer/repos/scholarchain/modules/rag/src/main/scala/org/scholarchain/rag/runtime/Llm.scala               (LanguageModel, FakeModel, AnthropicClient, BedrockClaudeClient, LlmFactory)
  /Users/brian/Developer/repos/scholarchain/modules/rag/src/main/scala/org/scholarchain/rag/runtime/RulePlanner.scala
  /Users/brian/Developer/repos/scholarchain/modules/rag/src/main/scala/org/scholarchain/rag/runtime/ResearcherResolver.scala (plus GraphRows)
  /Users/brian/Developer/repos/scholarchain/modules/rag/src/main/scala/org/scholarchain/rag/runtime/Stages.scala            (CollaborationMetrics, PathFinder, CommunityAnalyst, SemanticContextStage)
  /Users/brian/Developer/repos/scholarchain/modules/rag/src/main/scala/org/scholarchain/rag/runtime/EvidenceBuilder.scala   (plus EvidenceValues)
  /Users/brian/Developer/repos/scholarchain/modules/rag/src/main/scala/org/scholarchain/rag/runtime/Synthesis.scala         (QueryPlanner, AnswerSynthesis, FakeSynthesis)
  /Users/brian/Developer/repos/scholarchain/modules/rag/src/main/scala/org/scholarchain/rag/runtime/ClaimVerification.scala
  /Users/brian/Developer/repos/scholarchain/modules/rag/src/main/scala/org/scholarchain/rag/runtime/QueryWorkflow.scala     (plus AuditStage and plan assembly)
  /Users/brian/Developer/repos/scholarchain/modules/rag/src/main/scala/org/scholarchain/rag/runtime/BenchmarkRunner.scala   (plus BenchmarkEvaluation)
  /Users/brian/Developer/repos/scholarchain/prompts/runtime/query-planner-v1.md
  /Users/brian/Developer/repos/scholarchain/prompts/runtime/answer-synthesis-v1.md
  /Users/brian/Developer/repos/scholarchain/prompts/schemas/query-plan-draft-v1.schema.json
  /Users/brian/Developer/repos/scholarchain/prompts/schemas/answer-claims-v1.schema.json
  /Users/brian/Developer/repos/scholarchain/prompts/schemas/semantic-context-v1.schema.json
  /Users/brian/Developer/repos/scholarchain/modules/rag/src/test/resources/config/rag-runtime-local-v1.json
  /Users/brian/Developer/repos/scholarchain/modules/rag/src/test/resources/config/rag-runtime-anthropic-v1.json
  /Users/brian/Developer/repos/scholarchain/modules/rag/src/test/resources/benchmark/benchmark-v1.json   (frozen, sha256 below)
  /Users/brian/Developer/repos/scholarchain/modules/rag/src/test/scala/org/scholarchain/rag/benchmark/AnswerKeyGenerator.scala
  /Users/brian/Developer/repos/scholarchain/modules/rag/src/test/scala/org/scholarchain/rag/runtime/RuntimeFixtures.scala
  /Users/brian/Developer/repos/scholarchain/modules/rag/src/test/scala/org/scholarchain/rag/runtime/QueryWorkflowSuite.scala      (18 tests)
  /Users/brian/Developer/repos/scholarchain/modules/rag/src/test/scala/org/scholarchain/rag/runtime/ClaimVerificationSuite.scala  (9)
  /Users/brian/Developer/repos/scholarchain/modules/rag/src/test/scala/org/scholarchain/rag/runtime/PlannerAndSchemaSuite.scala   (15)
  /Users/brian/Developer/repos/scholarchain/modules/rag/src/test/scala/org/scholarchain/rag/runtime/LlmClientsSuite.scala         (10)
  /Users/brian/Developer/repos/scholarchain/modules/rag/src/test/scala/org/scholarchain/rag/runtime/StagesSuite.scala             (12)
  /Users/brian/Developer/repos/scholarchain/modules/rag/src/test/scala/org/scholarchain/rag/runtime/BenchmarkRunnerSuite.scala    (4)
  /Users/brian/Developer/repos/scholarchain/modules/rag/src/test/scala/org/scholarchain/rag/runtime/LiveLlmAwsSuite.scala         (2, RequiresAws, never run)
FILES MODIFIED:
  /Users/brian/Developer/repos/scholarchain/modules/rag/src/main/scala/org/scholarchain/rag/retrieval/SemanticRetriever.scala  (refuses a windowed query without a window filter; windowFilter, isWindowFilter)
  /Users/brian/Developer/repos/scholarchain/modules/rag/src/main/scala/org/scholarchain/rag/semantic/ExportDocuments.scala     (renderSparkSummaries: profiles, communities and paths only)
  /Users/brian/Developer/repos/scholarchain/modules/rag/src/main/scala/org/scholarchain/rag/ingest/RagIngestJob.scala          (A1: --stores aws through AwsStorageEnvironment, voyage only; injectable stores and wiring; smoke queries within their window)
  /Users/brian/Developer/repos/scholarchain/modules/rag/src/test/scala/org/scholarchain/rag/RetrievalSmokeSuite.scala          (window filters; a new refusal test)
  /Users/brian/Developer/repos/scholarchain/modules/rag/src/test/scala/org/scholarchain/rag/RagIngestJobSuite.scala            (voyage+aws allowed; --stores aws with stub stores; fake+aws refused before any store is built)
  /Users/brian/Developer/repos/scholarchain/modules/rag/src/test/scala/org/scholarchain/rag/LiveProvidersAwsSuite.scala        (RequiresAws only: passes a window filter)
COMMANDS RUN:
  read-only inspection: cat, sed -n, grep, ls, wc, shasum, find (no -delete/-exec), git status. Edits were made only with Write/Edit.
  sbt -batch "rag/compile" (several times); sbt -batch "rag/Test/compile"
  targeted sbt -batch "rag/testOnly ..." runs for each new suite and for the updated suites; sbt -batch "rag/test" -> Passed: Total 135, Failed 0, Ignored 2
  sbt -batch -J-Xmx12g "rag/Test/runMain org.scholarchain.rag.benchmark.AnswerKeyGenerator --export <phase-07 export> --out modules/rag/src/test/resources/benchmark/benchmark-v1.json"  (background, 1:47, exit 0, 30 questions, nothing skipped)
  sbt -batch check  (background, once, exit=0)
  sbt -batch -J-Xmx12g "rag/runMain org.scholarchain.rag.runtime.BenchmarkRunner ... --out modules/rag/target/phase-09-real/attempt-01 --llm fake"  (background, once, 3:10 wall, exit 0)
  No AWS, no network, no python/java/curl, never `sbt awsTest`, never `--llm anthropic`.
TEST RESULTS:
  sbt check, exact lines:
    [info] Ledger check passed (scalafmt 3.11.5).
    [info] Module tests: suites=90 executed=1629 passed=1629 failed=0 errors=0 skipped=3
    [info] Contract tests (no AWS): suites=22 executed=336 passed=336 failed=0 errors=0 skipped=0
    [info] Updated /Users/brian/Developer/repos/scholarchain/.claude/.last-test-run (module_tests=1629, contract_tests=336)
    [success] Total time: 926 s (0:15:26.0), completed Nov 15, 2026, 6:18:25 PM
    exit=0
  rag module: 135 passed, 2 ignored (the RequiresAws tests are excluded).
VALIDATION RESULTS: benchmark run results are below ("Benchmark and evaluation"). Every target with the fake LLM was met; there were no misses to report.
ASSUMPTIONS:
  - planSource is `rule` for the fake provider (its plan is RulePlanner output) and `llm` only when a live model's draft passes validation.
  - QueryAudit has no fallback field. A fallback is recorded in three places:
    - planSource `rule`;
    - modelId `fake-rule-template-v1`;
    - extra stageMillis keys `plannerFallback` / `synthesisFallback`, plus a note in the answer.
  - A plan step that cannot run because a name is unresolved is recorded in the core QueryPlan as a not-executed step (parameters plannedOperation and status=not-executed), never with a chosen researcher:
    - `researcher_lookup` over the looked-up candidates, when there are candidates;
    - otherwise a placeholder `semantic_context` step with no researchers.
    QueryPlan v1 requires at least one step and IDs for most operations.
  - Venue, institution and unconfigured-year filters make the whole answer abstain. Giving an unfiltered answer to a filtered question would mislead.
  - "Full window" for resolver context means the widest configured window (full-2015-2025).
  - Benchmark topics are venue names (WSDM, SIGIR). Researcher profiles contain no topic words, only venue names. The key pool is every researcher whose profile names the venue.
  - Precomputed paths are found by the path document's deterministic vector key (`pa:<window>:<a>:<b>`, either direction). Its `sourceEvidenceId` points to the EB1 item in EvidenceBundle. pathLength in the vector metadata is never read.
  - Config files keep the existing placeholder account ID 123456789012 (the same as the Phase 7 rag configs) and example-* names.
RISKS:
  - The Anthropic request and response shape is unverified live. Verify it in the live benchmark run.
  - The live model must copy 32-hex evidence IDs and bind numbers to fields. Expect more partially_supported claims, removed claims or fallbacks with the real model than with the fake.
  - RulePlanner reads names only as capitalized runs. Lowercase-particle-initial, all-caps and non-Latin names are not extracted. The LLM planner covers them; the fallback does not.
  - The name check in the verifier is conservative. A capitalized phrase that is not a cited name downgrades a claim to partially_supported.
  - Locally the graph holds only full-2015-2025 edges. Neighbour and path questions in other windows abstain with "not loaded in this environment". On Neptune every window is present.
MANUAL ACTION REQUIRED: none for this phase's gate. The human runs the live benchmark (command below) when ready.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer (decision 6), then the human's live benchmark run and gate.
```

## Prompt and model versions

| Item | Version / hash |
|---|---|
| Planner prompt | `query-planner-v1@sha256:444a10a8e4366de8cd62608d2a5af587a7449d4e772a96bdc01cec1ef2979ea5` |
| Synthesis prompt | `answer-synthesis-v1@sha256:b319a4766ef2ee75dccec2b9f9f0b3268db795e622e4ed411e799a35f1a0565d` |
| query-plan-draft-v1.schema.json | sha256 2f163a896429b9422b810e60df99a9c489a99b35ec3377113d9bd51a4f1ce028 |
| answer-claims-v1.schema.json | sha256 96a7b82fa767fc45b7ce7d1a53d75a817833787f21f7ab7312001d49303658ba |
| semantic-context-v1.schema.json | sha256 8489b483c4b1f8b786289440a2f4544e1a1ca655dd281e336b67e1bd4f5b3250 |
| Models | `fake-rule-template-v1` (fake); `claude-haiku-5-5` (anthropic, `models.answerModelId`); `anthropic.claude-haiku-5-5` (bedrock switch) |
| Code version / query run ID | `rag-runtime/1`; `RunId.of("rag.query", "rag-runtime/1", sha256(canonical question + window constraint), config fingerprint)`, so the same question and config give the same ID (tested) |

How prompts are handled:
- Prompts are loaded by path from `prompts/runtime/<id>.md` and hashed over the whole file. The audit carries both versions.
- Each file has a SYSTEM section and a USER section. Placeholders are filled in one pass, so user text cannot inject a placeholder.
- Both prompts state "no reasoning", and none asks for it (tested).
- The question and the excerpts are placed between delimiter tags. Their own tags are neutralized, so they cannot close their data block.

## Schemas

Validation is hand-written Scala. Unknown keys are rejected, and every key is required (null or [] when absent). `SchemaFiles.differences` checks that each file and its validator agree on object fields, required fields and enums. A mutation test proves that check is not ceremonial.
- **query-plan-draft-v1** fields: operations, researcherNames, orcids, windowLabel, comparisonWindowLabels, rankingMetric, limit, maxPathDepth, topic, unsupportedConstraints[{kind: venue|institution|window|other, text}].
- **answer-claims-v1** fields: summary, claims[{claimId, text, evidenceIds, numbers[{value, evidenceId, field}], uncertainty}], missingEvidence. The field enum is: value, rank, weight, jointPublicationCount, firstYear, lastYear, hops, maxDepth, depthSearched, year, teamSize, confidence, publicationCount, distinctCoauthors, windowStartYear, windowEndYear.
- **semantic-context-v1**: the existing SemanticContext block. The SemanticContext stage validates every retrieval result against it before use.
- Model output is accepted only as one JSON object, optionally inside a single Markdown fence.
- Validation errors never quote model text. Unknown keys are reported by count only.

## Allowed plan operations

- Operations are the QueryOperation allowlist only: researcher_lookup, collaboration_breadth, coauthor_neighborhood, shortest_path, connected_component, bridge_researchers, researcher_ranking, max_team_size, community_analysis, temporal_comparison, semantic_context.
- Windows: configured `graph.windows` labels only.
- Ranking metrics: the 4g list only.
- limit 1..rankingCacheTopK (100); maxPathDepth 1..maxPathDepth (6); at most maxResearcherNames (5) names of up to 100 characters; topic up to 200 characters.
- A failed draft gets at most `maxStructuredRetries` retries (1), then RulePlanner is used.
- No user text becomes query text. The only output is a typed plan, and storage compiles the templates.
- If the question names no window, the default (full-2015-2025) is used and the answer says so.

## Identity-resolution behaviour

- Names are looked up with `EntityLinkReader.byName` (AuthorNameKey folding, limit 100); ORCID iDs with `byOrcid`.
- For up to 10 listed candidates the resolver returns researcherId, displayName, identityOutcome, identityConfidence, full-window P and C, and the top 3 coauthors.
- Outcomes:
  - one candidate: resolved;
  - a dblp homonym suffix that matches exactly one candidate's name: resolved (real run: `Liang Wang 0015`);
  - several candidates otherwise: not resolved. Candidates are listed with their context as match_decision evidence (resolved=false) and the answer abstains for that name (real run: `Yang Liu`, 76 candidates);
  - no candidate: abstain for that name.
- An outcome of `conflict` (or anything other than `accepted`) always adds an uncertainty note to every kept claim citing that researcher, whatever the confidence (tested).
- Affiliations: none, because no institution data exists yet.

## Graph query templates used

All go through storage's `GraphReadPort` with its bounds (graphResultLimit 50, maxPathDepth 6, graphTimeoutMillis 20000):
- `ResearcherById`: names, identity outcome.
- `CoauthorNeighbors`: direct collaborators, with the truncated flag stated in the answer; top-3 resolver context; neighbour communities.
- `CoauthorEdgeBetween`: every path hop, and each direct pair. It gives weight, jointPublicationCount, first and last year, at most 5 supporting work IDs, and the edge ID.
- `PathBetween`: used only when no precomputed path matches. "No path within depth d" and "stopped at the time limit" give different answers.
- `WorkById`: title, year, venue and team size of the M_r works (at most 5).

Store readers used:
- `MetricCacheReader.get`, `.allWindows` and `.ranking` (RM1/RM2/RM4);
- `EvidenceStore.get` (path evidence) and `.putBundle` (EB3);
- `QueryAuditWriter.append`;
- `VectorIndexPort.getVectors` (path-key lookup only);
- the `SemanticRetriever` query, always with a window filter on windowed indexes (tested).

**A6/A7**:
- Precomputed Spark path evidence is used when the pair and window match.
- Hops come only from the path result or the path evidence item.
- A test tampers `pathLength` to 99 in the vector metadata, and the hop count stays 3.

## Verification rules (deterministic code, never a model)

Checks on each claim:
- Every cited evidence ID must be in the bundle; any other is a fabricated identifier.
- Every number in the text must equal a value of a cited graph item: integers exactly, other values rounded to the stated precision with at least 2 decimals.
- Every year must be a window, edge or work year of a cited item.
- These digits are not numbers: stable IDs, claim IDs, homonym suffixes, venue "(n)", tokens containing "/", and quoted titles.
- Each bound number (`numbers[]`) must equal the named field of the named cited item.
- "N hops" must equal the hop count of a cited path item.
- A quoted title must equal the title of a cited work item.
- Every researcher ID and every capitalized name must belong to cited items.
- semantic_context never supports a number, edge, path, component, community or bridge claim.

Statuses:
- conflicting: a contradiction is found;
- unsupported: there is no valid evidence;
- verified: every check passes;
- partially_supported: some checks pass and others are not covered.

The answer text keeps verified and partially supported claims (the latter with their uncertainty). Unsupported and conflicting claims are listed under "Removed claims" by ID and reason only; their claimText in the record becomes "Removed (status): reason".

The summary and missing-evidence notes may contain no digits or identifiers; offending text is replaced.

## Abstention behaviour

The answer abstains when:
- no kept claim cites anything beyond match_decision items;
- a venue, institution or unconfigured-window filter is present;
- a step cannot run because a name is ambiguous or unknown;
- the graph window is not loaded.

The reason is always stated.

Malformed model output:
- It gets a bounded retry, then the deterministic fallback (rule planner or template synthesis).
- Rejected text is kept only as sha256, length and the validation error. A test asserts that the text appears nowhere in the attempts, audit or response.

## Benchmark and evaluation (attempt-01, `modules/rag/target/phase-09-real/attempt-01/`, labelled "local stores, fake embeddings")

- **benchmark-v1.json sha256: b58c5e1a6cb29e192035ce77308c20e2597d2d1dbaf8d136761ae5c3316ff3ac.** It was frozen before the run, and the run report records the same hash.
- The file holds 30 questions plus 164 known-neighbour semantic queries.
- The generator reads only export tables (core records through storage's file reader), never the runtime code.

| Category | Questions |
|---|---|
| breadth ranking | q01-q03 |
| max team | q04-q06 |
| direct collaboration | q07-q09 |
| paths | q10-q11 precomputed, q12 no path within 6, q13 computed 2-hop |
| components | q14-q15 (q15 homonym suffix) |
| communities | q16-q18 |
| bridges | q19-q21 |
| temporal | q22-q24 |
| topic | q25 WSDM, q26 SIGIR |
| institution | q27 |
| ambiguous | q28 |
| unknown | q29 |
| injection | q30 |

Results, reported separately:
- **Plan accuracy:** operations 30/30, window 30/30, metric 30/30, limit 30/30, unsupported constraints 30/30; all fields correct 30/30.
- **Identity resolution:** 30/30 correct:
  - resolved 18/18;
  - ambiguous 1/1;
  - unknown 1/1;
  - none 10/10;
  - silent choices 0.
- **Deterministic graph retrieval:** 110/110 expected values matched exactly (rate 1.0).
  - Path lengths against the key: 4/4.
  - q10 has 5 hops and q11 has 2 hops, both `precomputed-spark-path` and the same researchers as Spark's path evidence.
  - q12: no path within depth 6, search complete.
  - q13: 2 hops via graph-path-search.
- **Semantic retrieval (fake lexical embedder, not semantic):** recall@10 within each document's own window:
  - profiles 49/50 = 0.98;
  - communities 49/50 = 0.98;
  - paths 64/64 = 1.0.
- **Topic rankings (semantic candidates, not checked against the key):**
  - q25: 10 ranked, of which 8 are in the key pool and 2 are outside it.
  - q26: 10 ranked, of which 4 are in the key pool and 6 are outside it.
  - Every value in the pool matched. Researchers outside the pool come from lexical matching on other profile words.
- **Answers:**
  - Claims: verified 175, partially_supported 0, unsupported 0, conflicting 0.
  - Abstentions: 3 expected (q27, q28, q29), 3 happened, 0 unexpected, 0 missed.
  - Planted errors: 89/89 caught:
    - wrong number 27/27, conflicting;
    - wrong window year 27/27, conflicting;
    - wrong hop count 3/3, conflicting;
    - semantic evidence cited for a number 2/2, unsupported;
    - fabricated evidence ID 30/30, unsupported.
  - Injection (q30): the plan matched the clean plan and 10/10 values matched.
- **Stage latencies** (ms, local, not representative; median / p95 / max):

  | Stage | Median | p95 | Max |
  |---|---|---|---|
  | planner | 0 | 1 | 33 |
  | resolver | 1 | 34 | 43 |
  | metrics | 0 | 651 | 925 (topic retrieval over 64,090 profiles) |
  | paths | 0 | 5 | 13 |
  | community | 0 | 4 | 5 |
  | semantic | 0 | 0 | 1 |
  | evidence | 0 | 1 | 11 |
  | synthesis | 0 | 3 | 19 |
  | verification | 2 | 8 | 20 |
  | total per question | 11 | 659 | 934 |

- **Runtime environment:**
  - Graph rows kept: Researcher 200,484; Work 155,978; Venue 16; AUTHORED 758,314; PUBLISHED_IN 155,978; COAUTHORED_WITH 1,612,421 of 3,361,084 read (full-2015-2025 only).
  - Tables:
    - ResearcherMetricCache 508,448 items: 503,448 RM1 plus 5,000 RM4 (5 windows × 10 metrics × top 100);
    - EntityLinkCache 255,065;
    - EvidenceBundle 64.
  - Rejects: 0 in the load and 0 in rendering.
  - Heap used 3,927,835,072 bytes of 12,884,901,888.
  - Load time 114.3 s (graph 23.6 s, tables 85.7 s). Summary render and ingest: 95,116 documents (64,090 profiles, 30,962 communities, 64 paths) in 26.7 s.
  - The whole sbt run took 3:10. No step came near 30 minutes, and there was no out-of-memory error.
- Every count matches decision 8's expected inputs.
- Each answered question wrote its bundle (EB3 plus EB1, 0 conflicts) and appended its audit.

## A6/A7 and A1 (rag side) tests

- **A6/A7:**
  - `StagesSuite` "path hops come from the Spark path evidence, never from VectorMetadata.pathLength";
  - `QueryWorkflowSuite` "shortest path uses the precomputed Spark path evidence (A6/A7) with one edge per hop";
  - `QueryWorkflowSuite` "a pair without precomputed evidence uses PathBetween; another component gives no path within depth".
- **A1:**
  - `RagIngestJobSuite` "A1: --stores aws runs the voyage provider through the AWS stores factory (stub stores, stub transport)";
  - "A1: --stores aws with the fake provider is refused before any store is built";
  - updated provider/stores matrix test (voyage+aws allowed, bedrock+aws and fake+aws refused).
  - The default `aws` factory builds `AwsStorageEnvironment.fromConfig` with SDK `maxAttempts = 1`; it has never been run.

## Request/response shape (verify in the live benchmark run)

- Request: POST `https://api.anthropic.com/v1/messages`, headers `x-api-key: <value of $SCHOLARCHAIN_ANTHROPIC_API_KEY>` and `anthropic-version: 2023-06-01`, content type JSON.
- Body: `{"model":"claude-haiku-5-5","max_tokens":<=2048,"system":...,"messages":[{"role":"user","content":...}],"temperature":0}`.
- The client reads `content[].text` (text blocks joined) and `usage.input_tokens` / `usage.output_tokens`.
- 429, 529 and 5xx are retried (maxRetries 3, backoff 1 s to 20 s, honouring Retry-After); other statuses fail at once.
- The limiter allows 50 requests per minute. The key is scrubbed from error text (tested).
- Bedrock sends the same body with `anthropic_version: bedrock-2023-05-31` and no `model` field, through InvokeModel.

## Live benchmark command (5k; run by the human from the repository root, never by agents)

```
SCHOLARCHAIN_ANTHROPIC_API_KEY=... sbt -batch -J-Xmx12g "rag/runMain org.scholarchain.rag.runtime.BenchmarkRunner --config modules/rag/src/test/resources/config/rag-runtime-anthropic-v1.json --export modules/spark/target/phase-07-export/run=51b50d578d5b609842c1c21b8266a00b --neptune-csv modules/storage/target/phase-07-real/attempt-01/neptune --questions modules/rag/src/test/resources/benchmark/benchmark-v1.json --out modules/rag/target/phase-09-real/attempt-02-anthropic --llm anthropic"
```

Notes on the command:
- Export the key in your own shell first. The leading assignment above only shows where it goes.
- The runner refuses to start if the variable is unset, and runs questions one at a time.
- Model calls are bounded at 30 × (1+1) × 2 = 120 requests, and at most about 14k input tokens each, well inside the $5 budget at Haiku 5.5 prices.

## Known failure modes and grounding gaps

1. The live model may cite malformed or absent evidence IDs, or bind numbers to the wrong field. Those claims become unsupported or conflicting and are removed. After 1 retry the template fallback is used, and it is recorded.
2. RulePlanner misses names that are not capitalized runs, and topics phrased outside its patterns.
3. Topic filtering locally is lexical over venue words, not semantic. Ranks are positions among the top-30 profile candidates.
4. No neighbour-community facts outside loaded graph windows (local graph: full window only).
5. Communities are approximate and no betweenness is computed. Every community or bridge answer states this.
6. The path-key lookup depends on the path summary documents being ingested. Without them it falls back to `PathBetween`, which gives the same hop count but no Spark evidence ID.

## Proposed later (not built)

1. QueryAudit fields for planner/synthesis fallback and attempt counts (domain-engineer).
2. A QueryPlan step form for "not executed, unresolved name" without placeholder operations (domain-engineer).
3. A storage reader for path evidence by endpoint pair, instead of the vector-key lookup (storage-engineer).
4. Community and path summaries as semantic context for community and path questions.
5. Load all windows into the local graph when memory allows (about 3.9 GB heap with one window).
6. A typed GraphRow decoder in storage (also proposed by storage-engineer).
