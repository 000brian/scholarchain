# review-report-a.md (Phase 15, session 2, review R-1)

(Saved verbatim by the orchestrator from code-reviewer's final message.)

Work order: docs/agent-runs/phase-15/prompt.md (sha256 3d806922c6dd74ada61bceca45d3a302740a4083f260f0ae3ca6b6b6b9dfa3e0), decisions 4–8, 12 and the Standing rules. Base: HEAD 2cbfbc72c704c40085e2876479d510b90062a12a. Slice: steps 1–4 plus repair-00-domain and repair-00-storage (everything under `modules/**`).

**VERDICT: REQUEST_CHANGES.** One major finding is open and marked interface: yes (H-01). The other six findings are minor.

## Findings

| ID | Severity | Interface | Owner | file:line | Evidence | Failure scenario |
|---|---|---|---|---|---|---|
| H-01 | **major** | **yes** | Orchestrator routes. Smallest fix: infra-engineer (5b). Regression test: service-engineer. Alternative fix: domain-engineer or rag-engineer | `infra/terraform/modules/runtime/main.tf:65-71` (embedding URL on the worker only); `modules/core/.../config/ScholarChainConfig.scala:454` (fingerprint is the sha256 of the whole resolved config, the `service` section included); `modules/rag/.../runtime/RuntimeModel.scala:182` (`configFingerprint = config.fingerprint`); `QueryWorkflow.scala:98-104` (the query run ID includes it); `modules/services/.../QueryService.scala:229` (the query role computes the ack `queryId`); `QueryService.scala:188` and `Worker.scala:150` (the worker stores the result under its own `r.queryRunId`) | `service.embeddingQueueUrl` comes in through `SCHOLARCHAIN_CFG_SERVICE_EMBEDDING_QUEUE_URL`, and Terraform sets that only on the worker. So the worker's resolved config, and its fingerprint, differ from the query role's. The query run ID is `RunId.of(..., ctx.configFingerprint)`. `QueryApiSuite.scala:64` asserts that the async `queryId` equals the sync one, but it runs in one process with one config, so it passes. | After D4, every async `POST /v1/query` returns an ack and job record with `queryId` = Q (query role). The worker writes the audit, bundle and result under W ≠ Q. `GET /v1/explain/{Q}` returns 404, `/v1/jobs/{id}/result` reports `queryId` W, and the worker's stage logs say Q. Async provenance breaks and logs no longer correlate. D-query and F part 3 use sync mode and are not affected. Possible fixes: (a) pass the variable to all four service roles. Only worker and all build the loop (`Main.scala` checks the role first), so this changes nothing else and no Phase 14 run ID. (b) Leave the queue URLs out of the run-ID fingerprint (this changes every query run ID). Either way, add a test that query run IDs do not depend on `embeddingQueueUrl`. |
| H-02 | minor | no | rag-engineer | `modules/rag/.../eval/RetrievalEval.scala:162-170`, `:239-253` | `--max-seconds` is checked only when a question starts. The drift check runs `inGraph` once per target (up to 10), each bounded only by the per-question timeout. | Worst case per question: 60 s graph + 60 s vector + 10 × 60 s drift (in practice graph reads stop at `graphTimeoutMillis` 20 s). The run can go about 12 minutes past `--max-seconds` 1200. E's collector wait must cover max-seconds plus that overrun, or it ends as exit 7 / timed-out. |
| H-03 | minor | no | rag-engineer | `RetrievalEvalSet.scala:90` (rule text), `:126-134`; compare `BenchmarkRunner.scala:437-454` | The rule calls "top `limit` (default 10) of the pool by value" for q25/q26 "the benchmark's own ranking rule". The benchmark scorer only checks that ranked answer items are in the pool and their values match; it never defines a top-10 target set. q25 has no `expected.limit`, so 10 is a default. | The E tables could present q25/q26 targets as benchmark labels. They are a rag derivation from the pool (1,045 entries for q25), which the production topic filter (top 30 semantic candidates) cannot reach by design. The report text should say this. It is a documentation claim, not a scoring error. |
| H-04 | minor | no | service-engineer (with rag-engineer if a parse-first API is needed) | `modules/services/.../EmbeddingWorker.scala:89-107`; `modules/rag/.../reembed/ReembedHandler.scala:216-232` | Poison is found before any store or Voyage call, but the release (`queue.release`) only happens after `handler.handle` returns for the whole receive (up to 10 messages), including the Voyage call and its retries for the valid tasks. | A poison message received together with valid tasks stays invisible for the batch's Voyage call and backoff, possibly tens of seconds, instead of being released at once (decision 7.2, 8.1). F's single poison message is unaffected; C plus a stray poison would delay redrive to the DLQ. |
| H-05 | minor | no | service-engineer | `modules/services/.../ServiceServer.scala:99-` (`close()`, overlapping waits) | Pools are now shut down together and their waits overlap: 60 s for embedding, 25 s for the others. No test would fail if the waits added up again (4 × 25 s + 60 s = 160 s, more than the 120 s `stopTimeout` at `infra/terraform/modules/runtime/variables.tf:138-147`). | A later change that makes the waits sequential again would pass the tests and get the worker SIGKILLed mid-drain. "Each behaviour change has a test that fails without it" is not met for this change. |
| H-06 | minor | no | service-engineer | `modules/services/.../Support.scala:276-286` (`ServiceLog.external`) | rag's `reembed-delivery` line is logged with no redaction. Its `taskId` comes from the message producer; the pattern `[A-Za-z0-9][A-Za-z0-9._:-]{0,127}` admits a 12-digit number or a bucket-like name. | A task ID such as `123456789012` or a bucket name sent to the queue is written verbatim to CloudWatch, bypassing the redactor that exists for account IDs. Producers today are only the ops role and the human's collectors, so the risk is low. Option: redact `taskId` but keep the pattern check, or narrow the pattern. |
| H-07 | minor | no | storage-engineer (record as deviation; no code change required) | `modules/storage/.../ingest/VectorIngestPipeline.scala:336-389`, `:685-690` | The skip check now reads index metadata (GetVectors) instead of ArtifactRegistry. | A vector that is in the index without a registry version (for example a crash between PutVectors and the registry put) is skipped, and its registry entry stays missing. D7's skip ratio measures index state, not registry state. Phase 14 run 1 completed, so this is low risk; it should be stated in the D7 result. |

## Rulings on the seven questions

1. **Skip check through batched GetVectors index metadata: accepted as a deviation.**
   - GetVectors with 100 keys per call is a batched read with no per-document call: `VectorIngestPipeline.scala:336-389`; `IngestBatchingSuite` checks 250 unchanged documents → 3 GetVectors, 0 S3/registry/put/embed.
   - It changes what D7 measures (see H-07). It is not slower: about 2,511 calls either way. GetVectors latency is an unmeasured estimate.
   - The ops role has `s3vectors:GetVectors` (`infra/terraform/modules/runtime/iam.tf:297-305`, statement VectorIngest). The worker has it too (`iam.tf:169-170`).
   - Phase 14 vectors skip correctly: the metadata holds `textHash`, `embeddingModel` and `embeddingDimensions` (`VectorMetadataCodec.scala:116-124`), and the AWS decode reads numbers as long (`S3VectorsIndex.scala:46`), so the comparison with `Num(dims)` matches.
2. **Registry writes as parallel conditional puts: accepted.** The registry write is a conditional version write that BatchWriteItem cannot express, which decision 6.1 explicitly allows. It is bounded by `writeParallelism` 16 (1..64, `VectorIngestPipeline.scala:53-69`, `:587-605`) and happens only after PutVectors succeeds. Test: "a failed PutVectors registers nothing".
3. **Derived eval set instead of a byte copy: accepted as a deviation, to be recorded.** The packaged resource records the test file's sha256 (`benchmark-v1-eval-set.json:5`), and `RetrievalEvalSetSuite.scala:69-75` asserts that the sha256 is the test file's and that resource == derive(test file). This ties the jar's data to the exact benchmark bytes as firmly as a byte copy would. The difference: the jar does not carry the raw benchmark.
4. **"Codes non-empty exactly when abstained" and the `unclassified` fallback: accepted.**
   - Enforced by `Validator.scala` `queryAuditV3`.
   - The workflow's coded list is never empty when abstained (fallback `no-substantive-evidence`, `QueryWorkflow.scala:381-395`). `forAudit` falls back to `unclassified` (`Abstentions.scala:94-99`), which no workflow path can reach.
   - Every abstention site carries a code because `StageOutput.abstentions` and `Assembled.abstentions`/`abstainAll` are now `List[Abstention]`.
   - Tested in `AbstentionCodesSuite`, `QueryAuditV3Suite.scala:150-167` and `ReadRoutesSuite` (explain with and without reasons, unknown code → unclassified text).
   - Note: "Not answered" steps inside a non-abstained answer carry no codes. That is consistent with decision 7.5.
5. **Worker concurrency as a server option: accepted.** Default 2, refused outside 1..8 (`ServiceServer.scala` start check; `EmbeddingWorkerSuite` "start is refused…"), justified in `ServiceLimits.scala` against 300 RPM and the 1 vCPU worker. Changing it needs a build, which Phase 15 does not need. In `close()` all pools now shut down at once:
   - For role worker the job pool still gets 25 s; the other pools are idle, so job-loop drain is effectively unchanged.
   - For role all, a job running longer than 25 s is interrupted up to 25 s earlier than before. Its lease expires and the job is retried.
   - Total close time is at most about 60 s + 1 s + 2 s plus the runtime close, under the 120 s stopTimeout. See H-05 for the missing test.
6. **E universe: as implemented.**
   - 11 questions, 100 labels, 19 not-comparable, marked exploratory (`RetrievalEvalSetSuite.scala:17-43`).
   - The exploratory label appears in the report and in every summary line (`RetrievalEvalJob.scala:261-265`, `RetrievalEval.scala:379`).
   - Errors, timeouts and empty lists stay in the denominator (mean over n, `RetrievalEval.scala:305-306`).
   - q25/q26 take the top 10 of the pool; see H-03 for the wording.
7. **Fused mode is experiment-only: verified.** `RetrievalModes.fuse` is called only at `RetrievalEval.scala:215`. `QueryWorkflow` never references `RetrievalModes`, which only `RetrievalEvalJob.scala:292` constructs. The label "experiment only; the production workflow has no fused ranking", the version `fusion-exp-v1` and k = 60 are in the report and the fused summary.

## Checklist (decision 12)

**Decision 5 (domain)**
- Key `service.embeddingQueueUrl`: `ConfigLoader.scala:237-240`.
- Environment variable: asserted at `ServiceConfigSuite.scala:408-423`.
- https validation without echoing the value: `ConfigLoader.scala:894-896`; `ConfigRefusalSuite.scala:118-123`.
- Absent → None: default at `ScholarChainConfig.scala:407`.
- Side effect of the key on the fingerprint: H-01.

**Decision 6 (storage)**
- 6.1 batching and bounds: `IngestSettings` at `VectorIngestPipeline.scala:48-95` (16/4/500, bounded, named).
- Skip path makes no per-document call: `:336-389`.
- Text puts in parallel: `:392-416`.
- PutVectors is still batched (500 per call).
- 6.2 estimate is labelled; first-pass speed is called unverified (storage report "ESTIMATE"; `RagIngestJob.scala` MaxStepMillis comment).
- 6.3 re-embed:
  - GetVectors with `returnMetadata=true`: `S3VectorsIndex.scala:159-172`; test `ReembedSuite.scala:316-321`.
  - Refuses a vector with missing metadata fields: `VectorReembedder.scala:335-355`.
  - Writes back exactly the metadata it read: `:283-291`; test `ReembedSuite.scala:56-90`.
  - No new key, text object or registry version: same test.
  - Completion record keyed by taskId + textHash, create-only, written after the vector write and checked before embedding: `:221-235`, `:358-378`; `TableSpecs` QS6.
  - The record lives in QuerySession, a table the worker already writes, with TTL.
- 6.4 counters exact (atomic): `VectorReembedder.scala:88-110`; the session thread updates only from results.
- 6.5 tests: `IngestBatchingSuite` and `ReembedSuite` cover every listed case, including A→B→replay of B, concurrent duplicates and a crash before the completion record.

**Decision 7 (rag)**
- 7.1 G-27:
  - `MaxStepMillis` 120 min, sized against about 2× the high estimate (`RagIngestJob.scala:714-720`).
  - Waits: 1800 s for skip-only and the probe; 9000 s for a full first pass (rag report).
  - `--max-documents` bounded, with test: `RagIngestG27Suite.scala:148-186`.
  - A Phase 14 build's data is skipped with 0 writes: `RagIngestG27Suite.scala:81-146`.
- 7.2 handler and message:
  - Format: `ReembedHandler.scala:17-103`.
  - Poison is never acknowledged and is released: `:302-306`. Tests: `ReembedHandlerSuite` and `EmbeddingWorkerSuite.scala:36-64`. H-04 for the "at once" timing.
  - One log line per delivery, with no body, key or URL: `ReembedHandler.scala:309-327`; test `:246-276`.
- 7.3 U-11 counts exact under concurrency, with tests: `RateLimiter.scala` `EmbeddingUsage` (AtomicLong); `EmbeddingUsageSuite`.
- 7.4 E harness: see rulings 3, 6 and 7. Total-limit overrun: H-02.
- 7.5 G-28:
  - Codes recorded in the v3 audit: `QueryWorkflow.scala:381-395`, `:516-518`.
  - Exposed on explain as `abstentionReasons`, codes plus fixed texts and no prose: `Models.scala` ExplainView; `explain.json` updated.
  - Every abstention path produces a code: compiler-enforced, plus the fallback.
- No prompt or answer-prose change: the texts are unchanged (`AbstentionCodesSuite` "prose is unchanged").

**Decision 8 (service)**
- Second loop only for roles worker and all with the URL: `Main.scala` embedding block; `ServiceServer.scala:179-196`.
- Acknowledge only processed or replayed; release permanent failures; leave transient failures: `EmbeddingWorker.scala:95-107`.
- A replay is acknowledged once: `EmbeddingWorkerSuite.scala:66-83`.
- Shutdown drains within the ECS stop timeout: `ServiceLimits` `EmbeddingDrainMillis` 60 s, under the 120 s stopTimeout; test `EmbeddingWorkerSuite.scala:187-220`. H-05 for the missing time-bound test.
- Job loop code unchanged. Its run-ID behaviour changes through H-01.
- Explain with and without a reason: `ReadRoutesSuite` diff.

**Cross-cutting items**
- Production algorithm, threshold, prompt or default changed for an experiment: checked, none. Every changed default (ingest parallelism, MaxStepMillis) is the G-27 sizing the work order asks for.
- Secrets and identifiers (no URL or host in logs or errors):
  - The queue URL never appears in a Left or a log: `EmbeddingQueueSuite.scala:148-171`, `CompositionSuite` diff.
  - The worker logs only exception class names: `EmbeddingWorker.scala:116-126`.
  - Eval and ingest reports are masked.
  - Residual: H-06.
- Bounds on every loop and pool:
  - `BoundedPool` 1..64.
  - Embedding loop 1..8 threads; receive at most 10; long poll 1 s; idle 200 ms.
  - Eval: the cached pool's submissions are bounded by the question set (11 × (2 + up to 10)); limits apply per question and in total (H-02).
- Each behaviour change has a test that fails without it: checked, except H-05.
- Weak test noted, not a finding: `RetrievalEvalSuite.scala:291` uses a disjunctive assert, but it still fails if the copy were overwritten (outcome "created").

## Files read in full

- Storage main: `modules/storage/src/main/scala/org/scholarchain/storage/ingest/{BoundedPool,VectorReembedder,VectorIngestPipeline,TextStore}.scala`; `.../storage/aws/{SqsRawReceiveQueue,S3VectorsIndex}.scala`.
- Rag main: `modules/rag/src/main/scala/org/scholarchain/rag/reembed/ReembedHandler.scala`; `.../rag/runtime/{Abstentions,RetrievalModes}.scala`; `.../rag/eval/{RetrievalEval,RetrievalEvalJob,RetrievalEvalSet}.scala`.
- Services main: `modules/services/src/main/scala/org/scholarchain/services/EmbeddingWorker.scala`.
- Storage tests: `modules/storage/src/test/scala/org/scholarchain/storage/{IngestBatchingSuite,IngestFakes,ReembedSuite}.scala`, `.../storage/jobs/EmbeddingQueueSuite.scala`.
- Rag tests: `modules/rag/src/test/scala/org/scholarchain/rag/{RagIngestG27Suite,EmbeddingUsageSuite,ReembedHandlerSuite}.scala`, `.../rag/eval/{RetrievalEvalSetSuite,RetrievalEvalSuite}.scala`, `.../rag/runtime/AbstentionCodesSuite.scala`.
- Services tests: `modules/services/src/test/scala/org/scholarchain/services/EmbeddingWorkerSuite.scala`.
- Core tests: `modules/core/src/test/scala/org/scholarchain/core/QueryAuditV3Suite.scala`.
- Work order lines 1–325; the INTERFACE HANDOFF sections of the six named reports, plus storage's five findings and ESTIMATE, and rag's status and G-27 sections.

## Read through the diff hunks only (not in full)

- Core main: `Records.scala`, `SchemaVersions.scala`, `Validator.scala`, `ConfigLoader.scala`, `ScholarChainConfig.scala`.
- Rag main: `BedrockTitanEmbedder.scala`, `FakeLexicalEmbedder.scala`, `RateLimiter.scala`, `VoyageEmbedder.scala`, `RagIngestJob.scala`, `QueryWorkflow.scala` (plus lines 85–214 in full), `Stages.scala`.
- Services main: `Main.scala`, `Models.scala`, `ServiceLimits.scala`, `ServiceServer.scala`, `Support.scala` (plus `Pools.shutdown`).
- Storage main: `AwsStorageEnvironment.scala`, `TableSpecs.scala`, `ServiceStores.scala`, `LocalStorageEnvironment.scala`, `StubEmbedder.scala`, `WorkQueue.scala`.
- Changed test files: `CompositionSuite`, `ReadRoutesSuite`, `ServiceTestKit`, `DynamoSuite`, `IngestPipelineSuite`, `ServiceStoreFixtures`, `RagIngestJobSuite`.
- Core tests: `ConfigRefusalSuite`/`ServiceConfigSuite`, read through targeted searches only.

## Not read

- The core test diffs for `Fixtures`, `QueryAuditV2Suite`, `SchemaVersionsSuite`, `WireContractSuite`, `ConfigLoaderSuite`. They are covered by the domain handoff and `QueryAuditV3Suite`.
- The frozen `wire/v3/QueryAudit*.json` resources. `QueryAuditV3Suite` round-trips both.
- `RuntimeRepairsSuite` diff.
- `benchmark-v1-eval-set.json`: searched for ids, reasons and sha only.

No new main-source file and no new test suite in the slice was left unread.

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 15 session 2, review R-1 (prompt sha256 3d806922c6dd74ada61bceca45d3a302740a4083f260f0ae3ca6b6b6b9dfa3e0), steps 1-4 plus repair-00-domain and repair-00-storage
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools)
TEST RESULTS: not run (reviewer); relied on the wave closer's reported full sbt check (2192 module tests, 0 failed) and packageCheck ok, not re-verified
VALIDATION RESULTS: findings verified against the files at the file:line given above; H-01 verified across core (fingerprint), rag (RuntimeContext and queryRunId), services (ack vs worker result) and infra (worker-only env)
ASSUMPTIONS: course runtime env as in infra/terraform/modules/runtime/main.tf:56-71 (job URL on every service role, embedding URL on the worker only); stopTimeout 120 s (variables.tf:138-147)
RISKS: H-01 goes live at D4 for every async query if not repaired; H-02 can push E past a collector wait sized only on --max-seconds
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: step 8 triage; H-01 (major, interface: yes) needs repair before session 2 can be READY (fix route: infra-engineer env on all service roles plus a service-engineer test, or domain/rag fingerprint change); minors to the repair round or the open-items list
```

**VERDICT: REQUEST_CHANGES**

| Severity | file:line | Failure input | Impact | Owner |
|---|---|---|---|---|
| major (interface: yes) | infra/terraform/modules/runtime/main.tf:65-71; ScholarChainConfig.scala:454; RuntimeModel.scala:182; QueryService.scala:229 vs :188 | any async POST /v1/query after D4 | ack queryId ≠ stored audit and result ID; explain on the ack ID returns 404; provenance and log correlation break | infra-engineer (fix) + service-engineer (test), or domain/rag (orchestrator routes) |
| minor | RetrievalEval.scala:162-170, 239-253 | slow Neptune during E | run overruns --max-seconds by up to about 12 min | rag-engineer |
| minor | RetrievalEvalSet.scala:90 | E report on q25/q26 | top-10 targets called "the benchmark's own rule" | rag-engineer |
| minor | EmbeddingWorker.scala:89-107 | poison in one receive with valid tasks | release delayed by the Voyage call and its retries | service-engineer |
| minor | ServiceServer.scala:99- | future change making the waits sequential | no test guards the 120 s stop bound | service-engineer |
| minor | Support.scala:276-286 | taskId "123456789012" | account-like value logged unredacted | service-engineer |
| minor | VectorIngestPipeline.scala:336-389 | vector present without a registry version | skipped; registry gap persists; D7 measures index state | storage-engineer (record deviation) |
