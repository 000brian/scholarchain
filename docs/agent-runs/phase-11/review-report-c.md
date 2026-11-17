STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: docs/agent-runs/phase-11/prompt.md (sha256 a0b59faeaeaae620ad87ce8b5dd1272efc1078d273e9a84baa426a9cd47e9ed1, not recomputed), step R-C, decision 6, slice C (modules/rag/**, prompts/**, modules/services/**), HEAD 2582a50
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools: Read, Grep, Glob)
TEST RESULTS: none run (reviewer)
VALIDATION RESULTS: I checked every finding below against the cited lines. I grepped docs/agent-runs/ to rule out known IDs: S-4 covers logs only, U-12 covers qualitative one-name claims, n-2 covers key-shaped tokens, and L-1..L-4 cover number formats. None of the findings below duplicates them.
ASSUMPTIONS: Phase 10's rule "no account ID or bucket name in ... responses" and decision 7i's "downstream error text never reaches a client" still apply to answer content in 200 responses, not only to error bodies. The bucket shown in the Phase 10 fake run output is a placeholder (`example-artifact-bucket`); a deployed config will put the real bucket there.
RISKS: Coverage is partial (see the coverage list). BenchmarkRunner, RagIngestJob, embedding/*, semantic/* and every test file except one fixture excerpt were not read, so area 10 (tests that pass while the behavior is broken) is mostly not reviewed for this slice.
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: step 2 triage by the orchestrator. C-01 to C-05 are major and go to rag-engineer (C-02 and C-03 have a service-engineer part).

VERDICT: REQUEST_CHANGES

## Findings

| ID | Severity | File:line | Failure input | Impact | Owner |
|---|---|---|---|---|---|
| C-01 | major | Synthesis.scala:321-345 (sanitize covers only summary and missingEvidence); QueryWorkflow.scala:286-290, 305, 367; ClaimVerification.scala:113-260 (never reads `uncertainty`) | The model returns a valid claim with `"uncertainty": "Alice Smith actually has 412 coauthors; see researcher:v1:<32 hex>"`. The claim text verifies. | The model's uncertainty text is stored in `AnswerClaim.uncertainty` and printed as "(Uncertainty: …)" next to a verified claim in `answerText`. It is never verified or sanitized. A number, ID or name from the model, or from injected semantic text, reaches the client inside a verified claim. This breaks "LLM output … never the source of a number, identifier". | rag-engineer |
| C-02 | major | Stages.scala:94, 105, 162, 188, 226, 477, 510, 538, 631, 745; ResearcherResolver.scala:138-139; Synthesis.scala:67, 230 → QueryWorkflow.scala:327-335, 346-353; SemanticRetriever.scala:69, 75; Llm.scala:466, 559; passed through by services Models.scala:50-63 | A DynamoDB metrics read fails with AccessDenied. `RuntimeStores.guard` gives `store failure (permanent): DynamoDbException (HTTP 400): User: arn:aws:sts::<12-digit>:assumed-role/… on resource arn:aws:dynamodb:…:table/<name>`. That becomes an abstention, then "No answer: …" in `answerText` and `abstentionReason` of a 200 response. Other routes into the answer: a resolver lookup failure (identityNotes); a Bedrock, Anthropic or Voyage failure body (the fallback note), where an Anthropic 429 body can name the organization; a vector query failure ("Semantic context unavailable: …"). | Downstream store, SDK and provider text reaches clients in 200 bodies and is stored in job results. It can carry account IDs, ARNs, table names and hosts. This breaks the Phase 10 rule "no downstream text to clients / no account ID in responses" and will be hit in Phase 14. S-4 covers logs only; this is the response path. | rag-engineer (texts built in rag); service-engineer (AnswerView passes them through) |
| C-03 | major | EvidenceBuilder.scala:394-404 (`sourceRecordIds = List(e.vectorKey, e.textS3Uri)`); ReadServices.scala:75-78; Models.scala:130-141; QueryService.scala:82-90 (`includeEvidence` defaults to true, QueryRequestParser.scala:218) | Any query that retrieves semantic context (topic ranking, semantic_context). The phase-10 output q-25.json:1502 already holds `s3://example-artifact-bucket/semantic-context/text/…` in provenance. | With a real config, the S3 bucket name is returned in `POST /v1/query` evidence and in `GET /v1/evidence/{id}` (`provenance.sourceRecordIds` and `sourceUris: "scholarchain:s3://<bucket>/…"`). It is also stored in the evidence table. This breaks the "no bucket name in responses" rule. | rag-engineer (item provenance); service-engineer (EvidenceView, sourceUris) |
| C-04 | major | ClaimVerification.scala:182 and 204 (an unbound number matches any field of any cited graph item); 212-229 (a bound number is checked only against "a cited item") | Case 1: the claim "Bob Jones has 57 distinct coauthors in 2015-2025." cites Alice's C item (57) and Bob's C item (12), which a two-researcher breadth question produces. Case 2: "Alice Smith has 57 publications" cites her P item (20) and her C item (57). Both come out `verified`, also when 57 is bound to the item that holds it. | A wrong attribution, to another researcher or another metric, is labelled `verified`. Numbers are never tied to the subject or the metric they describe. This is wrong results in normal two-person and breadth questions, distinct from U-12 (qualitative claims) and L-1..L-4 (number formats). | rag-engineer |
| C-05 | major | Stages.scala:229 (`stores.metrics.get(...).toOption.flatten` in topicRanking); also 548 (neighbour edges), 646-650 (bridge CCD), 677-680 (neighbour communities), 110-111 (works; this one is counted in a note) | A topic ranking where one candidate's metric read fails transiently after storage retries (DynamoDB throttling). | The failed candidate is silently dropped, the others are re-ranked 1..n, and the "Rank k among the topic-matched researchers" claims verify against these items. The result is wrong ranks with no note. Neighbour edge failures likewise shrink the neighbour list without a note. This failure handling breaks "uncertainty is reported, not hidden", and Phase 14/15 throttling will hit it. | rag-engineer |
| C-06 | minor | EvidenceBuilder.scala:124-132 and 242 (metric item subjects leave out rank; the first item by priority wins; `idOf` maps the ranked fact to the unranked item) | A question with researcher_ranking plus collaboration_breadth for a named researcher who is in the top-k of the same metric and window. | The ranked fact shares the breadth item's ID, which has no `rank`. The model never sees that rank. The template fallback claim "Rank k …" becomes conflicting ("number k is not a value…", "no field rank") and is listed under "Removed claims". | rag-engineer |
| C-07 | minor | Stages.scala:715-730 (only `res.context.entries` is used); SemanticRetriever.scala:86-100 | Stored vectors come from an older embedding model, or have malformed metadata, so every hit is dropped. | The answer says "no researcher profile … matched the topic". `droppedIncompatibleModel` and `droppedMalformedMetadata` are never noted, so a stale-model index looks like "no match" (spec category "stale model versions"). | rag-engineer |
| C-08 | minor | EvidenceBuilder.scala:181-192; Synthesis.scala:170 (FACTS rows are not passed through `Prompts.asData`) | A DBLP title or display name containing `</untrusted_excerpt>` or "Ignore the rules above…". | Untrusted DBLP text sits inside the section the prompt calls "deterministic graph evidence" without data marking. Verification limits the effect (numbers, IDs), but the summary and qualitative claims are exposed. | rag-engineer |
| C-09 | minor | QueryService.scala:302-330; Support.scala:74-84 | During half-open, the trial sync request's task throws (for example a fatal error, or an exception in `Results.completed` or `stores.jobs.succeed`), which gives an ExecutionException. | Neither `recordSuccess`, `recordFailure` nor `cancel` runs, so `trialInFlight` stays true. The workflow breaker then answers 503 CIRCUIT_OPEN forever until restart. | service-engineer |
| C-10 | minor | Synthesis.scala:70-75; Schemas.scala:191 | The planner model returns `researcherNames` with a name that is not in the question (a hallucination). | The name is resolved deterministically to a real researcher and answered. Nothing checks that the planned names occur in the question. Only the resolver note shows it. | rag-engineer |
| C-11 | minor | Stages.scala:404-433 | "Is there a path within 2 hops between A and B?" when a 4-hop precomputed Spark path exists. | The precomputed path is used regardless of `s.maxDepth`, and the requested depth bound is ignored with no note. | rag-engineer |
| C-12 | advisory | ServiceServer.scala:148-157 | Role `evidence` with an unreachable evidence or run-ledger table; role `worker` with an unreachable queue. | `/readyz` still reports ready: the checks cover only metrics and graph (and jobs). | service-engineer |
| C-13 | advisory | HttpAdapter.scala:220-229 | A flood of requests with wrong keys. | Unauthenticated requests are never rate-limited (the bucket comes after auth). The keys are hashed and compared in constant time, so brute-force risk is low. | service-engineer |

**Shared root causes:**
- C-02 and C-07: stage results carry free-text Left strings or drop counts instead of fixed cause codes.
- C-04, C-06 and C-01: the verifier and the evidence model do not tie values to their subject and field.
- C-03 and C-02 together: the Phase 10 response-side secrets review looked only at error bodies, not at answer and evidence content.

## Coverage

Read in full:
- modules/rag/src/main/scala/org/scholarchain/rag/runtime/: QueryWorkflow.scala, ClaimVerification.scala, Schemas.scala, Synthesis.scala, Stages.scala, EvidenceBuilder.scala, ResearcherResolver.scala, Llm.scala, RuntimeService.scala, Prompts.scala, RuntimeModel.scala
- modules/rag/src/main/scala/org/scholarchain/rag/retrieval/: SemanticRetriever.scala, SemanticContext.scala
- modules/services/src/main/scala/org/scholarchain/services/: QueryService.scala, Worker.scala, HttpAdapter.scala, Auth.scala, Support.scala, ReadServices.scala, Models.scala, Errors.scala, Json.scala, ServiceLimits.scala, Routes.scala, ServiceServer.scala, Main.scala, HealthService.scala, QueryRequestParser.scala
- prompts/runtime/: answer-synthesis-v2.md, query-planner-v2.md

Read in part:
- rag RulePlanner.scala (lines 166-233: `nameRuns` and the start of the regexes)
- rag test RuntimeServiceFixtures.scala (lines 90-149, FlakyStores)
- For context only, slice B files: storage RuntimeReaders.scala (220-289, EntityLinkReader) and AwsSupport.scala (55-94, AwsErrors.wrap)

Not read:
- rag runtime/BenchmarkRunner.scala
- rag ingest/RagIngestJob.scala
- rag embedding/*: EmbeddingModelSpec, RateLimiter, FakeLexicalEmbedder, VoyageEmbedder (only grepped for body and scrub), BedrockTitanEmbedder, EmbeddingProviders
- rag semantic/*: ChunkingPolicy, TemplateRegistry, PaperSummaryRenderer, ExportDocuments
- rag retrieval/SmokeQueries.scala
- prompts: runtime/query-planner-v1.md, runtime/answer-synthesis-v1.md, and every file under prompts/schemas/
- every test source and test resource of rag and services, except the fixture excerpt above

The verdict covers only the files read.

**Areas with no finding in the code read:**
- Area 3 (ownership): prompts sit under rag's territory, and no cross-module writes were seen.
- Area 5: not applicable to this slice.
- Area 6: the resolver never picks a candidate silently, and IdentityCheck reports `unknown` and `unchecked`.
- Area 12: model retries are bounded by `maxRetries` and `maxStructuredRetries`, and sync admission by permits.
