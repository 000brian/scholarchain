interface: yes (consumer-visible, none of this prompt's fixed interfaces changed: QueryAudit written as v3 with `abstentionCodes`; `StageOutput.abstentions` is now `List[Abstention]`; new explain-field source `AbstentionCodes`; new RagIngestJob option `--max-documents`. The decision 7.2 message format `embedding-task-v1` is built exactly as fixed.)

```text
STATUS: COMPLETE
OWNER: rag-engineer
WORK ORDER: docs/agent-runs/phase-15/prompt.md (sha256 3d806922c6dd74ada61bceca45d3a302740a4083f260f0ae3ca6b6b6b9dfa3e0, verified with shasum), session 2 step 3, decision 7 (7.1-7.7); orchestrator decisions (a)-(e); not the wave closer
FILES CREATED:
  /Users/brian/Developer/repos/scholarchain/modules/rag/src/main/scala/org/scholarchain/rag/runtime/Abstentions.scala
  /Users/brian/Developer/repos/scholarchain/modules/rag/src/main/scala/org/scholarchain/rag/runtime/RetrievalModes.scala
  /Users/brian/Developer/repos/scholarchain/modules/rag/src/main/scala/org/scholarchain/rag/reembed/ReembedHandler.scala
  /Users/brian/Developer/repos/scholarchain/modules/rag/src/main/scala/org/scholarchain/rag/eval/RetrievalEvalSet.scala
  /Users/brian/Developer/repos/scholarchain/modules/rag/src/main/scala/org/scholarchain/rag/eval/RetrievalEval.scala
  /Users/brian/Developer/repos/scholarchain/modules/rag/src/main/scala/org/scholarchain/rag/eval/RetrievalEvalJob.scala
  /Users/brian/Developer/repos/scholarchain/modules/rag/src/main/resources/org/scholarchain/rag/eval/benchmark-v1-eval-set.json
  /Users/brian/Developer/repos/scholarchain/modules/rag/src/test/scala/org/scholarchain/rag/EmbeddingUsageSuite.scala
  /Users/brian/Developer/repos/scholarchain/modules/rag/src/test/scala/org/scholarchain/rag/RagIngestG27Suite.scala
  /Users/brian/Developer/repos/scholarchain/modules/rag/src/test/scala/org/scholarchain/rag/ReembedHandlerSuite.scala
  /Users/brian/Developer/repos/scholarchain/modules/rag/src/test/scala/org/scholarchain/rag/runtime/AbstentionCodesSuite.scala
  /Users/brian/Developer/repos/scholarchain/modules/rag/src/test/scala/org/scholarchain/rag/eval/RetrievalEvalSetSuite.scala
  /Users/brian/Developer/repos/scholarchain/modules/rag/src/test/scala/org/scholarchain/rag/eval/RetrievalEvalSuite.scala
FILES MODIFIED:
  /Users/brian/Developer/repos/scholarchain/modules/rag/src/main/scala/org/scholarchain/rag/embedding/RateLimiter.scala (EmbeddingUsage atomic, U-11)
  /Users/brian/Developer/repos/scholarchain/modules/rag/src/main/scala/org/scholarchain/rag/embedding/VoyageEmbedder.scala, FakeLexicalEmbedder.scala, BedrockTitanEmbedder.scala (add* calls)
  /Users/brian/Developer/repos/scholarchain/modules/rag/src/main/scala/org/scholarchain/rag/ingest/RagIngestJob.scala (MaxStepMillis 120 min, --max-documents, report limits)
  /Users/brian/Developer/repos/scholarchain/modules/rag/src/main/scala/org/scholarchain/rag/runtime/Stages.scala (coded abstentions)
  /Users/brian/Developer/repos/scholarchain/modules/rag/src/main/scala/org/scholarchain/rag/runtime/QueryWorkflow.scala (audit v3 + codes; PlanAssembly extracted unchanged)
  /Users/brian/Developer/repos/scholarchain/modules/rag/src/test/scala/org/scholarchain/rag/RagIngestJobSuite.scala (Optional list + --max-documents in the contract test)
  /Users/brian/Developer/repos/scholarchain/modules/rag/src/test/scala/org/scholarchain/rag/runtime/RuntimeRepairsSuite.scala (audit schemaVersion 3)
  prompts/**: none (no prompt or schema change; no prompt version bump needed)
COMMANDS RUN: sbt -batch rag/Test/compile (x3); sbt -batch rag/testOnly (per suite while building, then all rag suites); sbt -batch services/Test/compile; sbt -batch services/testOnly ReadRoutesSuite (read-only evidence for step 4); read-only git status/diff; grep, ls, wc, head, shasum; aws-docs (2 calls: search, QueryVectors API page). No scripts, no AWS CLI, no aws-api, no cat/sed/find/sort/uniq/tr/cd beyond the noted `cd` prefix (see VALIDATION EVIDENCE note).
TEST RESULTS: rag (all suites, final run after the last source edit): Passed: Total 277, Failed 0, Errors 0, Passed 277, Ignored 2 (LiveProvidersAwsSuite, LiveLlmAwsSuite: RequiresAws). services/Test/compile: [success].
VALIDATION RESULTS: E universe derived by code from benchmark-v1 = 3r design exactly (11 questions, 100 labels, 19 not-comparable); packaged resource equal to the derivation (test). Expected services impact: ReadRoutesSuite.scala:126 expects auditSchemaVersion 2 and now gets 3 (service-engineer, step 4, decision 8.2).
ASSUMPTIONS: listed under ASSUMPTIONS section below
RISKS: listed under RISKS section below
MANUAL ACTION REQUIRED: none from the human in session 2. infra-engineer (5b): add `--max-documents` to the rag-ingest loader and the new loader `rag-retrieval-eval`; add neptune-db:ReadDataViaQuery to the ops role (matrix below). service-engineer (4): explain field, ReadRoutesSuite/explain.json to v3, worker loop calling the handler.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: step 4 (service-engineer, wave closer: full sbt check + packageCheck); 5b and 6c consume INTERFACE HANDOFF; R-1 review
```

## INTERFACE CHECK (done first, all match)

- step 1: `ServiceSettings.embeddingQueueUrl: Option[String] = None` modules/core/src/main/scala/org/scholarchain/core/config/ScholarChainConfig.scala:408; key `service.embeddingQueueUrl` ConfigLoader.scala:240. Match (rag does not read it; the worker does).
- repair-00-domain: `QueryAudit.abstentionCodes: List[String] = Nil` Records.scala:752; `V3Fields` :761; `AbstentionCodePattern` :767; `MaxAbstentionCodeLength` 64 :771; `MaxAbstentionCodes` 8 :774; `isValidAbstentionCode` :776; SchemaVersions.scala:75 (`"QueryAudit" -> 3`), :79 (stillReadable {1,2}). Rule "non-empty exactly when abstained" honoured by `AbstentionCodes.forAudit`. Match.
- step 2: `ReembedTask(taskId, index, vectorKey)` VectorReembedder.scala:23; `ReembedOutcome` :28-85 (Processed :39, Replayed :45, PermanentFailure :53, TransientFailure :57, PermanentCodes :61, TransientCodes :78); `ReembedCounters` :88-110; `reembed(tasks: Seq[ReembedTask]): Vector[ReembedOutcome]` :180; `MaxTasksPerCall` (=100) :386; `TaskIdPattern` :396; completion key :417-418 (QuerySession, QS6); `AwsStorageEnvironment.reembedder(embedder, sleeper)` AwsStorageEnvironment.scala:206; `LocalStorageEnvironment.reembedder(embedder, sleeper, nowSeconds)` LocalStorageEnvironment.scala:67; `IngestSettings.writeParallelism/embedConcurrency/windowDocuments` VectorIngestPipeline.scala:53-55 (defaults 16/4/500, used unchanged by the job through `env.pipeline`). Match.
- own 3r report: G-28 code table and E design used; orchestrator decisions (a) answer-target universe and (b) v3 + fixed codes + `unclassified` fallback applied.

## Status per item (decision 7)

| Item | Status | Where |
|---|---|---|
| 7.1 MaxStepMillis sized | done | RagIngestJob.scala `MaxStepMillis` = 120 min (search `val MaxStepMillis`), checked after the step as before; recorded in report `limits.maxStepMillis` |
| 7.1 skip-only call-count model + wait | done (estimate) | section G-27 below |
| 7.1 `--max-documents N` | done (cheap: a `take(N)` of the rendered corpus) | RagIngestJob.scala `Optional`, `Options.maxDocuments`, `selected(...)`; RagIngestG27Suite |
| 7.1 Phase-14-state skip test (seeded by running the pipeline) | done | RagIngestG27Suite "data ingested by the Phase 14 build is skipped ... zero text and vector writes" and "another job run ID" |
| 7.1 D7 loader arguments | done | section D7 below |
| 7.2 re-embed handler | done | reembed/ReembedHandler.scala; ReembedHandlerSuite (11) |
| 7.3 U-11 rag side | done | RateLimiter.scala `EmbeddingUsage` (AtomicLong, add*); EmbeddingUsageSuite (3) |
| 7.4 rag-retrieval-eval (graph-only, vector-only, fused) | done (fused built: orchestrator 12:16 kept it) | eval/RetrievalEvalJob.scala, eval/RetrievalEval.scala, eval/RetrievalEvalSet.scala, runtime/RetrievalModes.scala; RetrievalEvalSetSuite (5), RetrievalEvalSuite (14) |
| 7.4 benchmark main-resource copy with sha256 test | deviation (planned-type, needs orchestrator acceptance) | a byte copy (381,646 bytes) would need `cp` (forbidden) or re-emitting the whole file; instead the packaged resource is the code-derived eval set (12 KB) that pins the test file's sha256 b58c5e1a...f3ac; RetrievalEvalSetSuite proves resource == derive(test benchmark) and sha256 equality |
| 7.5 G-28 record the reason | done | runtime/Abstentions.scala (table), Stages.scala (every StageOutput abstention coded), QueryWorkflow.scala (audit v3, `abstentionCodes`); AbstentionCodesSuite (8) |
| 7.5 G-28 diagnose | done in 3r (no code defect proven); L1 and L2 not fixed, per orchestrator (b) | agent-report-rag-3r.md |
| 7.6 tests | done | 6 new suites (46 tests) + 2 changed suites; all 277 rag tests pass |
| 7.7 INTERFACE HANDOFF | done | below |

Each behaviour change fails without it: G-28 tests need codes and schemaVersion 3 (before: Nil and 2); U-11 tests call the new atomic API and count exactly (the old `@volatile +=` loses updates); MaxStepMillis/--max-documents tests assert the new constant and option; the Phase-14 skip test fails without storage's step-2 skip path (old code re-put 15 texts: textAlreadyPresent 15); handler and eval suites test classes that did not exist.

## G-27: four parts

1. **Batched and parallel writes implemented and tested: done (session 2)** — storage step 2 (VectorIngestPipeline, BoundedPool); the job uses them unchanged through `env.pipeline` with the defaults 16/4/500; rag's U-11 makes `embedRequestsThisRun` exact under `embedConcurrency` 4.
2. **First-pass duration estimate: done, estimate (unverified; no full first pass runs in Phase 15)** — storage: ingest step low 31, central 36, high 50-54 min + render 45 s (measured Phase 14). Job total for a first pass with the default `--runs 2`: run 1 (up to about 55 min high) + run 2 skip (about 2 min) + retrieval smoke (about 1-2 min) + task start (1-2 min) = about 60 min high (estimate). `MaxStepMillis` 120 min = about 2x the high ingest estimate. Script wait for a first pass: `--wait-timeout 9000` (covers a run-1 ingest up to the 120-min cap plus run 2, smoke and start; estimate).
3. **Live skip-only pass inside its own limits: phase-15 D7 (session 3), not run.**
4. **Live S3 Vectors search returned results: phase-15 D7 retrieval smoke and E, not run.**

### Skip-only call-count model (per 251,094 documents, per run; estimate)

- Render: 1 per run, 45,435 ms (measured, Phase 14 D13f).
- Skip reads (storage, VectorIngestPipeline.scala:336-389): GetVectors with metadata, at most 100 keys per call, per window of 500 documents: 2,511 calls if each window holds one index (the corpus is rendered per template), up to about 4,500 if windows mix indexes. 503 windows, up to 5 calls per window in parallel (writeParallelism 16 >= 5), so about 1 round trip per window. Assumed GetVectors latency 50-200 ms (not measured; S3 Vectors lists no GetVectors rate limit): 503 x 0.05-0.2 s = **25-100 s per run**.
- Nothing else per document: no S3 text put, no DynamoDB registry read or write, no Voyage call, no PutVectors (storage test: 0 calls; my RagIngestG27Suite: textCreated 0, textAlreadyPresent 0, putCalls 0, embedCalls 0). RunLedger 3 calls per run.
- Per run 70-145 s; the loader runs the default `--runs 2`, so 140-290 s for both runs.
- Retrieval smoke (once): at most 25 samples per index with documents (4 indexes hold documents in Phase 14's render; the topic index renders 0; estimate) = at most 100 queries (at most 125 if all 5 had documents); each 1 Voyage query embedding + 1 QueryVectors + 1 GetObject, serial, about 0.35-0.85 s each = 35-85 s; Voyage limiter 300 RPM is not reached.
- ECS task start and image pull about 1-2 min (Phase 14 ops tasks).
- **Total skip-only D7: low about 5 min, central about 8 min, high about 12 min (estimate). `--wait-timeout 1800`** (more than 2x the high estimate, far below 14,400). `MaxStepMillis` does not bind.
- 5,000-document probe (`--max-documents 5000`): 10 windows, about 50 GetVectors calls (< 10 s), render 45 s x 2, smoke on at most 25 samples (the first 5,000 rendered documents are probably one template, so one index): **about 4-6 min, `--wait-timeout 1800`** (estimate). Its per-run `millis.skipRead` and `skipReadCalls` give the measured skip rate to scale the full pass.

## D7 (session 3): exact loader arguments and expected report

Through `run-bounded-pipeline.sh --stage load --loader rag-ingest` (infra 5b adds the optional `--max-documents` pass-through), the ops task runs exactly:

- probe: `org.scholarchain.rag.ingest.RagIngestJob --config <OPS_CONFIG> --export <Phase 14 export run=HEX, from the D13f record> --out /tmp/scholarchain-rag-ingest-<ATTEMPT> --code-version <BUILD_SHA> --stores aws --max-documents 5000`
- full skip pass: the same without `--max-documents`.
- No `--runs` and no `--samples-per-index` (defaults 2 and 25; the loader passes neither today, run-bounded-pipeline.sh:1701).
- Script wait: `--wait-timeout 1800` for both.

Expected report fields (`<out>/run-report.json` and the copy under `rag/ingest-reports/run=<hex>/`): `status` "completed"; `limits.maxStepMillis` 7200000, `limits.maxDocuments` 5000 or null; per run `runs[i].documents` (rendered, 251,094 expected on the same export, estimate from Phase 14), `runs[i].documentsIngested` (5000 or 251,094), `runs[i].ingest.input`, `skippedUnchanged` (= input), `skipRatio` 1.0, `written` 0, `textCreated` 0, `textAlreadyPresent` 0, `embedCalls` 0, `putCalls` 0, `skipReadCalls`, `millis.skipRead`, `millis.total`, `embedRequestsThisRun` 0; `secondRunSkipRatio` 1.0; `renderDeterministic` true; `retrievalSmoke.<index>`: `samples`, `top1IsSelf`, `selfInTop10`, `top1IsSelfWithinOwnWindow`, `top1TextFetchedByPointerMatchesHash`, `queryMillisLocalFakeNotRepresentative` (name kept; with Voyage these are live latencies), `misses`; `embedderUsage.query.requests` (= smoke queries, at most about 100-125). The first live S3 Vectors search is the retrieval smoke (G-27 part 4). Any `written` > 0 on the full pass means the skip rule did not hold and is a finding.

## E: `rag-retrieval-eval` design as built (decision 7.4, orchestrator (a))

- Universe (EXPLORATORY in every table: 11 < 15): q01, q02, q03, q07, q08, q19, q20, q21, q25, q26, q30; 100 labels (10,5,10,10,10,10,5,10,10,10,10); 19 not-comparable listed with reasons in the report. Derived by code (`RetrievalEvalSet.derive`, rule text in the report). **Assumption (for R-1): q25/q26 targets are the top `limit` (10) of the labelled pool by the benchmark's own rule (value descending, then researcher ID by code point; benchmark-v1.json:13).** No graded labels: nDCG "not computed: benchmark-v1 has no graded relevance labels".
- graph-only: RulePlanner (no LLM) + ResearcherResolver + PlanAssembly (the workflow's own step rules, extracted unchanged) + CollaborationMetrics, PathFinder, CommunityAnalyst on Neptune and DynamoDB; no semantic retrieval (q25/q26 abstain with `topic-candidates-failed`, counted as abstentions, by-design-weak). List: ranked metric facts by rank, then other facts' researchers in stage order, minus the step's own input researchers; length = the plan limit (10), not padded.
- vector-only: the question (canonical, at most 2,000 chars) embedded with the configured Voyage query model; top-K = min(30, vectors.queryTopK) on the researcher-profile index with the plan window's filter; ID = metadata `researcherId`; duplicates collapse to the best rank.
- fused: RRF k = 60 over the union, `fusion-exp-v1`, label "experiment only; the production workflow has no fused ranking"; latency = graph + vector + fusion compute.
- Metrics per mode: recall@1/5/10/30 (mean over all 11) with ceilings min(K,|T|)/|T|, MRR, errors (exception, timeout, time-limit, or a stage failure code), empty, abstentions, by-design-weak count, latency and list-length stats (n, min, median, p95, max, mean); per question: target ranks, recall@K, RR, top 30 with scores, abstention codes, drift (`labelsNotInGraph` via Neptune researcher_by_id, kept in the denominator; `labelsUnchecked`).
- Bounds: `--max-seconds` (default 1200, 60..3600) total; `--question-timeout-seconds` (default 60, 5..300) per mode call; a timed-out call is abandoned on a daemon thread and scored 0.
- Estimate (labelled): Voyage query calls 11 (vector-only; fused reuses), about 11 x 30 tokens, inside the free allowance; Neptune reads about 130 (100 drift lookups + about 15 each for q07/q08 resolver/neighbours/edges; rankings read DynamoDB), DynamoDB about 11 Query + 40 GetItem, S3 Vectors 11 QueryVectors, 1 S3 PutObject. Duration about 3-5 min including task start (estimate); `--wait-timeout 1800` for the load stage.

## Handler one-task rate (orchestrator (e), for C's N; estimate)

Per handler call = one SQS receive of up to 10 messages (one loop iteration): 1 GetVectors (50-200 ms) + per task GetItem + GetObject + PutItem in series (3 x 10-30 ms; 0.3-0.9 s for 10) + 1 Voyage call for the 10 texts (0.3-1.5 s; about 86 tokens each, Phase 14 estimate) + 1 PutVectors (0.2-1 s) + the worker's receive and 10 acknowledgements (about 0.15-0.5 s; service-engineer's design). Per concurrent receive loop (c = the worker's embedding-loop concurrency, step 4):

- **low 2.4 tasks/s x c** (10 tasks in about 4.1 s: slow Voyage 1.5 s, PutVectors 1 s, store calls 30 ms)
- **central 5.4 tasks/s x c** (about 1.85 s per 10)
- **high 10 tasks/s x c** (about 1.0 s per 10)
- Ceilings per worker task (one embedder and one limiter per process): Voyage 300 RPM x 10 texts = 50 tasks/s; 1 M TPM / 86 tokens = about 194 tasks/s. Basis: config/course.json:60-69, storage's per-call estimates (agent-report-storage.md "Re-embed path per message"), Phase 14 measured 19 docs/s single-writer ingest. None measured; C reports the measured rate of the re-embed path only.

## ASSUMPTIONS

- A1. The E universe and q25/q26 rule as above (orchestrator (a)); the benchmark labels come from source run run:v1:51b50d578d5b609842c1c21b8266a00b (not the Phase 14 graph): drift is reported per question.
- A2. Graph-only excludes semantic retrieval (purity); the production workflow's topic ranking uses it, so q25/q26 graph-only are by design weak.
- A3. Vector-only queries only the researcher-profile index (the one index whose metadata carries `researcherId` for every document).
- A4. Lists are not filtered by labels; graph-only drops only its own step-input researchers.
- A5. D7 skip-only numbers assume the Phase 14 vectors' metadata carries `textHash`, `embeddingModel` `voyage/voyage-4/1024/document/emb-v1` and 1024 dimensions as written by Phase 14 (same config).
- A6. The worker calls `ReembedHandler.handle` with at most 10 deliveries per call (SQS maximum); larger calls are split by `batchLimit` (64 for course).

## RISKS

- R1. Interface: `StageOutput.abstentions` changed type (List[String] -> List[Abstention]); only rag uses it (grep). `WorkflowResult.abstentions` stays List[String] (texts). Answer prose (`abstentionReason`, answer text) is byte-for-byte unchanged.
- R2. services: ReadRoutesSuite.scala:126 asserts auditSchemaVersion 2; with rag writing v3 it fails (observed: "Obtained 3", 1 of 11 failed). Owner service-engineer (step 4), with the explain field and `api/v1/explain.json`.
- R3. A pre-Phase-15 reader refuses v3 audits (domain RISKS); the image ships reader and writer together.
- R4. Benchmark resource: a code-derived set, not a byte copy (deviation above). If the orchestrator requires a byte copy, it needs a build-engineer resource mapping (build.sbt) or a human copy.
- R5. E graph-only on the live graph: 3r R2/R3 (label drift; graph-only partly circular with the labels). E is exploratory.
- R6. The handler's transient rule relies on the queue's visibility timeout (300 s) as backoff; a long Voyage outage keeps messages cycling until maxReceiveCount 5 sends them to the DLQ (expected SQS behaviour).
- R7. Concurrent duplicate deliveries are not observable (both `processed`), by design (decision 6.3).
- R8. 3r latent issues L1 (storage, NeptuneAdapters error classing) and L2 (rag, ClaimVerification hop phrase vs path=none) not fixed (orchestrator (b)); the new codes make them visible in D6 (`path-query-failed`, or `no-path-within-depth` + `no-verified-claim`).

## INTERFACE HANDOFF

**Message format `embedding-task-v1`** (reembed/ReembedHandler.scala `object EmbeddingTask`): a JSON object with exactly the string fields `schema` (= "embedding-task-v1"), `taskId` (pattern `[A-Za-z0-9][A-Za-z0-9._:-]{0,127}`, storage's TaskIdPattern), `index` (one of the five configured index names), `vectorKey` (non-blank, at most 1024 UTF-8 bytes; must exist in that index). Nothing else (no text, no URL). Body at most 4,096 characters. Builder: `EmbeddingTask.render(taskId, index, vectorKey)`. Example: `{"schema":"embedding-task-v1","taskId":"p15-c-00001","index":"researcher-profile-v1","vectorKey":"<key>"}`.

**Handler entry point** (package `org.scholarchain.rag.reembed`):
- `ReembedHandler.fromConfig(config: ScholarChainConfig, reembedderOf: EmbeddingPort => VectorReembedder, wiring: EmbeddingProviders.Wiring = Wiring(), log: String => Unit = println, allowFake: Boolean = false): Either[String, ReembedHandler.Built]`; `Built(handler, usage: EmbeddingUsage, embedder: EmbeddingPort)`. For the worker: `ReembedHandler.fromConfig(config, awsEnv.reembedder(_), log = <service logger>)`. Refuses the fake embedder unless `allowFake`.
- `handler.handle(deliveries: Seq[Delivery]): Vector[HandledDelivery]` with `Delivery(deliveryId: String, body: String)`; results in input order; never throws for a delivery; thread-safe.
- `HandledDelivery(deliveryId, taskId: Option[String], index: Option[String], outcome: String, code: String, action: DeliveryAction, latencyMillis: Long, logLine: String)`.
- `DeliveryAction`: `Acknowledge` (wire "acknowledge": delete the message), `ReleaseNow` ("release": ChangeMessageVisibility 0 at once, never delete), `LeaveForRetry` ("leave": do nothing; visibility timeout).
- Rules: processed, replayed -> Acknowledge. **Poison** (codes `invalid-json`, `not-an-object`, `body-too-large`, `unknown-schema`, `missing-field`, `unknown-field`, `field-not-string`, `invalid-task-id`, `unknown-index`, `invalid-vector-key`) -> permanent, decided before any store or Voyage call: no Voyage call, no write, ReleaseNow, never acknowledged. A key that does not exist -> storage `unknown-vector-key` after one metadata read: no Voyage call, no write, ReleaseNow. Every storage permanent code -> ReleaseNow. Storage transient codes and `handler-error` (a reembed call that threw or returned the wrong count) -> LeaveForRetry (queue backoff).
- Batching: tasks of one call are sent to `VectorReembedder.reembed` in chunks of `batchLimit` = min(embedding.batchSize, 100) (64 for course); the embedder batches its texts (one Voyage call per chunk of at most 64).
- Counters (`handler.counters`, AtomicLong, `toJson`): `received`, `processed`, `replayed`, `permanentFailures` (includes poison), `poison`, `transientFailures`. Storage's `ReembedCounters` stay on the reembedder; embedding usage on `Built.usage` (exact, U-11).

**Log line (one per delivery, written through `log`)**: a compact JSON object with exactly these keys in this order: `event` ("reembed-delivery"), `taskId` (string, or null when the body has no well-formed taskId), `index` (configured index name or null), `outcome` (one of `processed`, `replayed`, `permanent-failure`, `transient-failure`), `code` (`processed`, `replayed`, a storage failure code, a poison code, or `handler-error`), `action` (`acknowledge`, `release`, `leave`), `latencyMillis` (number, handler time for the call). Never the body, a vector key, a text, a URL or a store message. Experiment F filters on `taskId` and `outcome` = `processed` / `replayed`; the poison delivery has `outcome` `permanent-failure`, `code` `unknown-index`, `action` `release`, and its `taskId` (p15-f-poison-...) when well-formed. service-engineer may wrap it in its own logger prefix; the JSON keys are the contract.

**rag-retrieval-eval** (class `org.scholarchain.rag.eval.RetrievalEvalJob`, same jar as RagIngestJob; contract constants `RetrievalEvalJob.Required` / `Optional`):
- Required: `--config FILE`, `--out DIR` (must not exist), `--code-version SHA40` (build commit), `--attempt-id ID` (`[A-Za-z0-9][A-Za-z0-9._-]{0,99}`), `--graph-run RUNID` (run:v1:<32 hex>; Phase 14: run:v1:f2625d504b12749389cf4f5b1a26aed4).
- Optional: `--modes graph-only,vector-only,fused` (default all three; fused needs both), `--max-seconds N` (60..3600, default 1200), `--question-timeout-seconds N` (5..300, default 60). Unknown, repeated or out-of-range options are refused.
- Stores: AWS only (no `--stores` option). Needs the `neptune`, `runtime`, `vectors`, `embedding` (voyage) sections and the Voyage key env var named by `embedding.apiKeyEnvVar` (SCHOLARCHAIN_VOYAGE_API_KEY); no LLM section is used, no Anthropic key needed.
- Exit codes: 0 completed and the copy created or already present; 1 failed, stopped by the total time limit, or the copy failed/conflict (local report still written); 2 refused arguments.
- Report: `<out>/report.json`, copy put-if-absent at `<semantic-context prefix>/retrieval-eval/<attempt-id>/report.json` = logical `s3://{data-bucket}/rag/retrieval-eval/ATTEMPT/report.json`; copy outcome in `<out>/report-copy.json` and printed as `[rag-retrieval-eval] report copy: <outcome> <logical object>`; final line `[rag-retrieval-eval] <status>; report: <path>`. Per mode one line: `[rag-retrieval-eval] summary mode=<m> questions=11 exploratory=true recall@1=x recall@5=x recall@10=x recall@30=x mrr=x errors=n empty=n abstentions=n latencyMedianMillis=n`.
- Report fields: `format` "scholarchain.rag.retrieval-eval-report", `formatVersion` 1, `job`, `codeVersion`, `build`, `attemptId`, `graphRunId`, `llmCalls` 0, `limits` {maxSeconds, questionTimeoutSeconds, topK, ks}, `modesRequested`, `fusion` {version, k, label}, `label` ("EXPLORATORY: 11 questions ..."), `exploratory`, `evalSet` {benchmarkId, benchmarkSha256, rule, questions, labels, notComparableCount, notComparable[]}, `embedding.queryModelSpec`, `stores`, `questions[]` {id, category, windowLabel, targets, byDesignWeak, drift {checked, labelsNotInGraph[], labelsUnchecked}, modes.<m> {listLength, latencyMillis, error, empty, abstentionCodes, byDesignWeak, recallAt{1,5,10,30}, recallCeilingAt{...}, reciprocalRank, targetRanks[], top[{researcherId, rank, score}], detail}}, `modes.<m>` {mode, questions, scored, exploratory, recallAt, recallCeilingAt, mrr, ndcg (note), errors, empty, abstentions, byDesignWeakQuestions, latencyMillis{n,min,median,p95,max,mean}, listLength{...}; fused also version, k, label}, `evaluationMillis`, `embedderUsage`, `status` ("completed" | "stopped: ..." | "failed: ..."), `totalMillis`. Masked like the ingest report (buckets, account).

**RagIngestJob** new option: `--max-documents N` (1..10,000,000; optional; `RagIngestJob.Optional` = stores, runs, samples-per-index, max-documents). New report fields: `limits` {maxStepMillis, maxDocuments, runs, samplesPerIndex}, `runs[i].documentsIngested`. `MaxStepMillis` 120 min. Ingest settings unchanged from storage (writeParallelism 16, embedConcurrency 4, windowDocuments 500; constants, not options). Script waits: skip-only D7 and the probe `--wait-timeout 1800`; a full first pass (not in Phase 15) `--wait-timeout 9000`.

**Abstention field (G-28)**: stored in `QueryAudit.abstentionCodes` (schema version 3, written by `QueryWorkflow.AuditSchemaVersion` = 3). Explain field name (final): **`abstentionReasons`**: a JSON array of `{"code": String, "text": String}`, built by services from the stored codes with `org.scholarchain.rag.runtime.AbstentionCodes.reasons(codes): List[(String, String)]` (or `AbstentionCodes.text(code)`); `[]` when not abstained; an unknown code reads as the `unclassified` text. Code table (`AbstentionCodes.Table`, 26 codes): unsupported-venue-filter, unsupported-institution-filter, window-not-configured, researcher-not-resolved, path-needs-two-researchers, researcher-name-missing, metrics-read-failed, metrics-missing, ranking-read-failed, ranking-missing, topic-candidates-failed, topic-metrics-failed, topic-no-match, window-not-loaded, path-same-researcher, path-query-failed, path-result-invalid, no-path-within-depth, path-query-timeout, edge-lookup-failed, neighbor-lookup-failed, collaboration-needs-researchers, semantic-needs-topic-or-name, no-verified-claim, no-substantive-evidence, unclassified. Order in the audit: whole-question refusals, path context codes, step and verification codes; distinct; at most 8.

**Every AWS call (for infra 5b's permission matrix)**

Re-embed handler (worker role): rag makes no AWS call itself; it calls `VectorReembedder.reembed` at ReembedHandler.scala:225, whose calls are storage's matrix (agent-report-storage.md: GetVectors, GetItem, GetObject, PutVectors [missing today], PutItem on QuerySession). Voyage over HTTPS (not AWS): VoyageEmbedder.scala:133, key from env var SCHOLARCHAIN_VOYAGE_API_KEY (already injected into service tasks). SQS Receive/Delete/ChangeMessageVisibility are the worker's (service-engineer).

rag-retrieval-eval (ops role):

| Call | Code (file:line) | IAM action | Resource | ops today |
|---|---|---|---|---|
| openCypher reads (researcher_by_id, coauthor_neighbors, coauthor_edge_between, work_by_id, path templates) | RetrievalModes.scala:142 (drift), graph-only stages Stages.scala:617, :728, :889 and ResearcherResolver -> NeptuneAdapters.scala:146 / :247 `executeOpenCypherQuery` | `neptune-db:connect`, `neptune-db:ReadDataViaQuery` | the Neptune cluster resource | connect yes; **ReadDataViaQuery no: add** (iam.tf:307-315 has only connect, StartLoaderJob, GetLoaderJobStatus) |
| DynamoDB GetItem / Query: researcher metric cache, rankings (RM4), entity-link cache; EvidenceBundle only for shortest-path plans (none in the universe) | Stages.scala:207, :300, :335, :387, :786, :798, :825, :846, :900; ResearcherResolver.scala:98-99, :192 -> RuntimeReaders.scala:83, :95, :124, :274, :337, :348 -> DynamoDbKeyValueStore.scala:84, :120 | `dynamodb:GetItem`, `dynamodb:Query` | ResearcherMetricCache, EntityLinkCache (EvidenceBundle) tables | yes (`TablesLoad`, iam.tf:288-292: write_actions = GetItem, Query, PutItem (iam.tf:30) on all table ARNs) |
| S3 Vectors QueryVectors with a metadata filter and returnMetadata on researcher-profile-v1 | RetrievalModes.scala:106 -> SemanticRetriever.scala:76 -> S3VectorsIndex.scala:144 | `s3vectors:QueryVectors` **and** `s3vectors:GetVectors` (API page: a filter or returnMetadata=true needs both, https://docs.aws.amazon.com/AmazonS3/latest/API/API_S3VectorBuckets_QueryVectors.html) | index ARNs | yes (`VectorIngest`, iam.tf:296-305) |
| S3 Vectors GetVectors on the path index (shortest-path plans only) | Stages.scala:566 -> S3VectorsIndex.scala:86 | `s3vectors:GetVectors` | index ARNs | yes |
| S3 PutObject (If-None-Match) of the report; HeadObject only after a 412 | RetrievalEvalJob.scala:356 -> S3ObjectStore.scala:36, :40/:64 | `s3:PutObject`; `s3:GetObject` (HeadObject) | `rag/*` in the data bucket | yes (`RagWrite` iam.tf:271-275, `GraphAndRagRead` :262-269) |
| Voyage query embeddings (HTTPS, not AWS) | VoyageEmbedder.scala:133 | none (secret env var SCHOLARCHAIN_VOYAGE_API_KEY) | - | already used by rag-ingest's smoke in the ops task |

D7 rag-ingest (ops role) adds nothing new: its skip read is GetVectors (granted), its smoke QueryVectors+GetVectors (granted).

**Thread-safety**: ReembedHandler thread-safe (atomic counters, no shared state); EmbeddingUsage exact under concurrency; RetrievalModes builds per-call stage objects; RetrievalEval.run uses a private daemon pool shut down at the end; QueryWorkflow unchanged in this respect.

**Fixtures (test scope)**: ReembedHandlerSuite `Setup` (local env seeded with the fixture corpus, counting embedder); RetrievalEvalSuite `localStores`, `slowGraph`; RagIngestG27Suite `Shared` (one in-memory env across job executions).

**Limits**: handler body 4,096 chars, batchLimit 1..100; eval max-seconds 60..3600, question timeout 5..300 s, top-K 30; ingest step cap 120 min, max-documents 1..10,000,000; audit codes at most 8 x 64 chars.

## Prompt and model versions; evaluation metrics; known grounding gaps

- Prompts: unchanged (planner v2, synthesis v3 current; no file under prompts/ touched). Query run ID CodeVersion stays `rag-runtime/3` (no run-ID input changed). Embedding model spec voyage/voyage-4/1024 (document and query), unchanged.
- Groundedness: no claim, verification or synthesis rule changed; the codes are fixed vocabulary, never model output. AbstentionCodesSuite confirms the answer prose is unchanged (`abstentionReason` text) and that store text never enters the audit.
- E metrics: none measured yet (session 3). Grounding gaps carried: L1, L2 (3r), q13 unexplained until D6.

## Proposed later (not built)

- A byte-identical benchmark resource through a build mapping (build-engineer), if R-1 prefers it to the derived set.
- Pipelining the eval's per-question modes in parallel (not needed at 11 questions).

## VALIDATION EVIDENCE

```text
work order sha256: 3d806922c6dd74ada61bceca45d3a302740a4083f260f0ae3ca6b6b6b9dfa3e0 (matches)
benchmark-v1.json sha256: b58c5e1a6cb29e192035ce77308c20e2597d2d1dbaf8d136761ae5c3316ff3ac (= resource benchmarkSha256)
sbt -batch "rag/testOnly org.scholarchain.rag.runtime.AbstentionCodesSuite": Passed: Total 8, Failed 0
sbt -batch "rag/testOnly ...EmbeddingUsageSuite ...VoyageEmbedderSuite ...FakeLexicalEmbedderSuite": Passed: Total 17, Failed 0
sbt -batch "rag/testOnly ...RagIngestG27Suite ...RagIngestJobSuite": Passed: Total 25, Failed 0
sbt -batch "rag/testOnly ...ReembedHandlerSuite": first run Failed 1 (poison order: missing-field before unknown-schema) -> parse checks schema first -> Passed: Total 11, Failed 0
sbt -batch "rag/testOnly org.scholarchain.rag.eval.*": first run 1 failed (resource missing; printed the derivation) -> resource written; second run 4 failed (test fixes: shadowed kind, no-graph question for the timeout, topic-candidates-failed classed as abstention) -> Passed: Total 19, Failed 0
FINAL: sbt -batch "rag/testOnly org.scholarchain.rag.*" "services/Test/compile"
  rag: [info] Passed: Total 277, Failed 0, Errors 0, Passed 277, Ignored 2 -- [success] 12:46:21
  services/Test/compile: [success] 12:46:21
services/testOnly ReadRoutesSuite (evidence for step 4, no services file changed): Failed 1 of 11 at ReadRoutesSuite.scala:126 (auditSchemaVersion: obtained 3, expected 2)
aws-docs: QueryVectors API page read (filter/returnMetadata need s3vectors:GetVectors too)
sbt check: not run (not the wave closer)
note: Bash commands were prefixed with `cd /Users/brian/Developer/repos/scholarchain &&` (the work order lists `cd` as not allowed; read-only effect, no file written by it); reported as an unplanned deviation
git status --short modules/rag prompts: 9 modified, 11 new paths (listed above); nothing outside modules/rag written (scratchpad only)
finished: after the last command above
files changed after these runs: none
```
