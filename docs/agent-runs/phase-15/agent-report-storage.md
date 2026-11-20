# Phase 15 session 2 step 2: storage-engineer report

Work order: docs/agent-runs/phase-15/prompt.md (sha256 3d806922c6dd74ada61bceca45d3a302740a4083f260f0ae3ca6b6b6b9dfa3e0), decision 6 items 1-6.
interface: no (no fixed interface of this prompt is changed; decision 7.2's message format is rag's and is not touched here).

## Five findings (decision 6.3), written before any code

1. **Where the current `textHash` and text location of a vector come from.** Both are stored in the vector's own S3 Vectors metadata as non-filterable keys `textHash` and `textS3Uri` (`VectorMetadataCodec.scala:123-124`; non-filterable in `index-spec-v1.json:23`). The ingest sets them from the content-addressed text write (`VectorIngestPipeline.scala` `metadataFor`, `textS3Uri = put.ref.uri`, `textHash = put.textHash`; `TextStore.scala:18-28`: key `<semantic-context prefix>/text/v1/index=<index>/sha256=<hex>.txt`, put-if-absent; prefix `rag` in `config/course.json:39`). The same two values are copied into the ArtifactRegistry version item (`registerWritten`), but the worker role has no ArtifactRegistry access (`iam.tf:31-39`), so the re-embed path reads them from the index (GetVectors with metadata), never from the registry. The text is read back with `TextStore.fetch`, which verifies it against `textHash` (`TextStore.scala:32-40`).
2. **Which existing table holds the completion record.** QuerySession (`TableSpecs.scala:88-133`): one of the three tables the worker role already reads and writes (`iam.tf:30-39`, `workflow_tables.write = ["EvidenceBundle", "RunLedger", "QuerySession"]`, actions GetItem, Query, PutItem at `iam.tf:155-162`), and the only one of the three with a TTL attribute (`expiresAt`, QS3 says its items are disposable). Its existing prefixes are `session#`, `job#`, `idempotency#`; every reader uses an exact partition key (`JobStore.scala:99,110,209,220`, `IdempotencyStore.scala:84,87,97,113`, `ItemBuilders.scala:210-211`) and no code scans it, so the new prefix `reembed#<sha256 hex>` with sort key `reembed` (QS6, `TableSpecs.scala:125`, `:263`) cannot collide with any key or reader. RunLedger (append-only audit, no TTL) and EvidenceBundle (answers, no TTL) were not chosen. No new table, no new IAM action for the record: the worker already has GetItem and PutItem on QuerySession.
3. **Which fields later ingest and retrieval read from the vector metadata.** Retrieval decodes the whole record with `VectorMetadataCodec.decode` (`SemanticRetriever.scala:84`, `Stages.scala:518`), which requires `documentType`, `sourceType`, `documentId`, `runId`, `textHash`, `sourceRecordIds`, `datasetId`, `snapshotId`, `jobName`, `policyVersion`, `sourceDataset`, `textS3Uri`, `embeddingModel`, `embeddingDimensions`, `schemaVersion` (`VectorMetadataCodec.scala:232-262`) and reads the optional IDs (`researcherId` and others, `:269-285`); then it fetches the text through `textS3Uri` checked against `textHash` (`Stages.scala:922`, `RagIngestJob.scala:547`), checks `embeddingModel` compatibility (`SemanticRetriever.scala:90-91`) and filters on filterable IDs such as `researcherId`. Later ingest (after this step) reads `textHash`, `embeddingModel`, `embeddingDimensions` from the index metadata for its skip check (`VectorIngestPipeline.scala:685`); before this step it read the same three from ArtifactRegistry.
4. **A delivery after the text changed.** The message carries no text and no hash. A delivery reads the vector's metadata as it is now, so it embeds the text now at `textS3Uri` (verified against the current `textHash`) and keys its completion record by (`taskId`, current `textHash`): a text that changed after the message was sent is processed again under the new hash, never replayed from the old one (test `ReembedSuite` "hash A, then hash B ..."). Not guaranteed: a text that changes while a delivery is in progress (an ingest writes the vector between this delivery's GetVectors and its PutVectors) can be overwritten with the older metadata and the older text's embedding; the guarantee holds only when the text does not change while a task is in progress.
5. **The skip check still skips a vector after a re-embed.** The skip check compares only `textHash`, `embeddingModel`, `embeddingDimensions` (`VectorIngestPipeline.scala:685-690`). The re-embed path rewrites exactly the metadata it read (all three unchanged), refuses a vector whose stored `embeddingModel` or `embeddingDimensions` differ from the configured embedder (`model-mismatch`, so rewritten metadata never lies about the model), and writes nothing to ArtifactRegistry. So the check still skips it (test `ReembedSuite` "the ingest skip check still skips every vector after a re-embed": skip ratio 1.0, written 0, embed calls 0).

```text
STATUS: COMPLETE
OWNER: storage-engineer
WORK ORDER: Phase 15 session 2 step 2, decision 6 items 1-6 (prompt sha256 3d806922c6dd74ada61bceca45d3a302740a4083f260f0ae3ca6b6b6b9dfa3e0); not the wave closer
FILES CREATED:
  /Users/brian/Developer/repos/scholarchain/modules/storage/src/main/scala/org/scholarchain/storage/ingest/BoundedPool.scala
  /Users/brian/Developer/repos/scholarchain/modules/storage/src/main/scala/org/scholarchain/storage/ingest/VectorReembedder.scala
  /Users/brian/Developer/repos/scholarchain/modules/storage/src/test/scala/org/scholarchain/storage/IngestFakes.scala
  /Users/brian/Developer/repos/scholarchain/modules/storage/src/test/scala/org/scholarchain/storage/IngestBatchingSuite.scala
  /Users/brian/Developer/repos/scholarchain/modules/storage/src/test/scala/org/scholarchain/storage/ReembedSuite.scala
FILES MODIFIED:
  /Users/brian/Developer/repos/scholarchain/modules/storage/src/main/scala/org/scholarchain/storage/ingest/VectorIngestPipeline.scala
  /Users/brian/Developer/repos/scholarchain/modules/storage/src/main/scala/org/scholarchain/storage/dynamo/TableSpecs.scala
  /Users/brian/Developer/repos/scholarchain/modules/storage/src/main/scala/org/scholarchain/storage/aws/S3VectorsIndex.scala
  /Users/brian/Developer/repos/scholarchain/modules/storage/src/main/scala/org/scholarchain/storage/aws/AwsStorageEnvironment.scala
  /Users/brian/Developer/repos/scholarchain/modules/storage/src/main/scala/org/scholarchain/storage/local/LocalStorageEnvironment.scala
  /Users/brian/Developer/repos/scholarchain/modules/storage/src/main/scala/org/scholarchain/storage/local/StubEmbedder.scala
  /Users/brian/Developer/repos/scholarchain/modules/storage/src/test/scala/org/scholarchain/storage/IngestPipelineSuite.scala
  /Users/brian/Developer/repos/scholarchain/modules/storage/src/test/scala/org/scholarchain/storage/DynamoSuite.scala
COMMANDS RUN: see VALIDATION EVIDENCE (sbt Test/compile and testOnly only; read-only git, grep, ls, wc, shasum; aws-docs MCP reads)
TEST RESULTS: see VALIDATION EVIDENCE (final run: every storage suite, plus rag's three ingest users)
VALIDATION RESULTS: storage compiles; rag compiles against the changed storage API and its ingest suites pass; no sbt check (not the wave closer)
ASSUMPTIONS: see ASSUMPTIONS section below
RISKS: see RISKS section below
MANUAL ACTION REQUIRED: none from the human in session 2. infra-engineer (5b) adds s3vectors:PutVectors for the worker role (matrix below).
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: step 3 (rag-engineer) consumes INTERFACE HANDOFF; step 4 full sbt check; R-1 review
```

## Status per item (decision 6)

- 6.1 batched and parallel writes: **done**.
  - Skip check: batched GetVectors, metadata only, at most 100 keys per call, per window of `windowDocuments` (`VectorIngestPipeline.scala:336-389`, read at `:346`). A skipped document makes no per-document call: no text put, no registry read, no write (the text put used to happen before the registry check; it now happens only for documents to write, `:392-415`).
  - Why GetVectors and not BatchGetItem: (a) BatchGetItem needs the exact sort key, but the registry's latest version is a `v#NNNNNNNN` key found only by a descending Query; (b) `dynamodb:BatchGetItem` is in neither the ops role (`iam.tf:288-292`, GetItem/Query/PutItem) nor the permissions boundary (`identity/boundary.tf:63`), so it would need a boundary change (a security change); GetVectors is already granted to ops (`iam.tf:296-305`) and allowed by the boundary (`boundary.tf:82`); (c) the vector metadata is written in the same PutVectors call as the data, so it describes exactly the stored vector. The skip rule is unchanged (same three fields, `:685-690`); only its source moved from the registry to the index.
  - Registry writes: **bounded parallel conditional puts, not BatchWriteItem** (`:587-598`, `writePool`). Reason: the registry is append-only and race-safe only because each version is a conditional `attribute_not_exists(pk)` PutItem (`LedgerAndRegistry.scala:114-128`, AR3); BatchWriteItem accepts no condition expression, and the KeyValueStore port has no unconditional or batch write by design (`KeyValueStore.scala:63-71`). So the "25 and 26 items" edge case does not apply; the equivalent edge cases are tested on GetVectors (100 and 101 keys).
  - Text objects: bounded parallel put-if-absent with the latest-registry read in the same task (`:392-415`).
  - PutVectors: kept at 500 per call, serial (`:543`); not parallelized: the estimated slowest step is the embedding rate limit, not PutVectors (estimate below), and the per-index limit is 2,500 vectors/s (S3 Vectors limits page, read Nov 20).
  - Embedding: `embedConcurrency` calls in flight (`:418-423`); rag's RateLimiter (synchronized) still enforces RPM and TPM.
  - Bounds (named constants, `VectorIngestPipeline.scala:48-98`): `writeParallelism` 16 (max 64), `embedConcurrency` 4 (max 16), `windowDocuments` 500 (max 5000); `BoundedPool.MaxThreads` 64 (`BoundedPool.scala`). Justification of 16 in the scaladoc at `:73-80` (S3 3,500 PUT/s per prefix, DynamoDB on-demand about 1,000 WCU/s per partition, about 1,600 calls/s at most from 16 threads).
- 6.2 labelled estimate: **done** (section ESTIMATE below).
- 6.3 re-embed write path: **done** (`VectorReembedder.scala:180-325`; five findings above).
- 6.3 GetVectors with `returnMetadata=true` and refusal on missing metadata: **done** (`S3VectorsIndex.scala:159-172`, request built with `.returnMetadata(true)` at `:171`; refusal `VectorReembedder.scala:335-356`).
- 6.4 U-11 store side: **done** for the counters that live here: ingest counters are updated only on the session thread from the results of the parallel calls (`VectorIngestPipeline.scala` IngestSession; test "parallel writes keep counts exact"); `ReembedCounters` are AtomicLong (`VectorReembedder.scala:88-110`; test "counters are exact under concurrent callers"); `StubEmbedder` counters made atomic (`StubEmbedder.scala`). rag's `EmbeddingUsage` (`RateLimiter.scala:75-81`, `@volatile +=`) stays rag's part of U-11 (decision 7.3): with `embedConcurrency` 4 the ingest now calls the embedder concurrently, so rag's fix matters for the ingest report's `embedRequestsThisRun` too.
- 6.5 tests: **done**, every one listed (mapping under TEST RESULTS detail).
- 6.6 INTERFACE HANDOFF: **done** (below).

## ESTIMATE (labelled; no full first pass runs in Phase 15)

Basis (measured, Phase 14 D13f, runbook-log.md:80): 251,094 documents in 12,982,514 ms in ingest-run-1 = about 19.3 documents/s = about 51.7 ms per document with one writer; render-run-1 45,435 ms. No per-call breakdown was measured (the report's `millis` split stays in the S3 report copy; not read).

Phase 14 calls per document (old code): 1 S3 conditional PUT, 1 DynamoDB Query (registry latest), 1 DynamoDB PutItem (registry), 1/64 Voyage call, 1/500 PutVectors, all serial.

Derived per-call assumptions (estimate): Voyage about 1.0 s per 64-text call (unmeasured; its share of 51.7 ms is then 15.6 ms); PutVectors about 0.5 to 1 s per 500 vectors (unmeasured); the remaining about 35 ms per document over 3 store calls gives about 12 ms per store call (estimate). Voyage limits from `config/course.json:64-69`: 300 requests/min, 1,000,000 tokens/min, batch 64. Tokens per document about 86 (Phase 14 "about 0.1 M tokens/min at about 19 documents/s"; estimate), so the token limit allows about 194 documents/s and the request limit about 320 documents/s: **Voyage's token limit caps the first pass at about 194 documents/s** (estimate).

**Full first pass, new settings (estimate):** per window of 500 documents of one index: 5 GetVectors in parallel (about 0.1 s) + 1,000 store calls (text PUT and registry Query) at 16 in flight (about 0.75 s; up to 1.5 s if connections beyond the HTTP client's keep-alive pool need new TLS handshakes) + 8 embedding calls, 4 in flight, bounded by the token limit (about 2.6 s) + 1 PutVectors (0.5 to 1 s) + 500 registry PutItems at 16 in flight (0.4 to 0.8 s) = about 4.4 s (central) to 6 s (high), low about 3.7 s.
- Calls per document: 0.01 GetVectors + 1 S3 PUT + 1 Query + 1 PutItem + 1/64 Voyage + 1/500 PutVectors (the same store calls as Phase 14, now 16 in flight; plus the GetVectors read).
- Duration of the ingest step: **low about 31 min, central about 36 min, high about 50 to 54 min** (high: Voyage 1.5 s per call and every extra connection paying a handshake), plus render about 45 s (measured). Slowest step: the Voyage token limit (about 2.6 s of each 4.4 s window), then the text and registry calls. First-pass speed stays `unverified` (decision 7.1 part 2).

**Full skip-only pass (estimate):** per document 0.01 GetVectors calls and nothing else (no S3, no DynamoDB, no Voyage, no PutVectors); RunLedger 3 calls per run. Calls: about 2,511 GetVectors (251,094 / 100) if each 500-document window holds one index, at most about 4,500 if windows mix all five indexes. 503 windows, 5 calls in parallel per window, about 0.05 to 0.2 s per call (estimate: no GetVectors latency was measured; the S3 Vectors limits page lists no GetVectors rate limit): **about 25 s to 100 s for the ingest step**, about 2 to 4 min with retries on throttling; plus render about 45 s (measured). rag sizes MaxStepMillis and the script wait from this (decision 7.1).

**Re-embed path per message (estimate, for rag and C's N):** per call of up to 100 tasks of one index: 1 GetVectors + per task 1 GetItem + 1 S3 GetObject + 1 PutItem (serial in one call) + ceil(n/64) Voyage calls + 1 PutVectors. For 10 tasks (one SQS receive): about 33 store calls x 12 ms + 1 Voyage call (about 1 s) = about 1.4 s, about 7 tasks/s per caller thread (low 4, high 12; estimate). Concurrency comes from the worker loop (service-engineer, decision 8.1).

## TEST RESULTS detail (decision 6.5 mapping; each fails without its change)

IngestBatchingSuite (new, 10 tests):
- "the skip pass makes no per-document call ..." (250 unchanged documents, another run ID and code version: 3 GetVectors calls, 0 S3, 0 registry, 0 PutVectors, 0 embeddings; RunLedger 3 calls). Without the change: 250 text puts and 250 registry Queries, 0 GetVectors calls.
- "GetVectors calls hold at most 100 keys: 100 documents take 1 call, 101 take 2" (the 25/26-item case of BatchWriteItem does not apply, see 6.1). Without the change: 0 GetVectors calls.
- "the skip check reads metadata only, in windows of windowDocuments" (30/30/10). Without: no windows, no calls.
- "keys missing from a GetVectors response are written again ..." (the unprocessed-items case of a batched read). Without: the registry said unchanged, nothing written.
- "a throttled GetVectors batch is retried with bounded backoff and the counts stay exact" (one retry, delay 200 ms). Without: no skip read.
- "a skip read that keeps failing rejects its documents, is recorded, and the next run writes them" (`skip-read-failure` in RunLedger). Without: no skip read.
- "parallel writes keep counts exact: 300 documents, 16 store calls and 4 embeddings in flight at most" (counts exact; max in flight > 1 and <= 16). Without: max in flight is 1 (shown by the next test).
- "writeParallelism 1 keeps every store call on one thread" (max in flight 1).
- "every bound is checked" (defaults 16, 4, 500; out-of-range refused). Without: no such settings.
- "the registry claims only written vectors: a failed PutVectors registers nothing".

ReembedSuite (new, 13 tests; the class did not exist before, so each fails without it):
- metadata unchanged after a re-embed (realistic metadata from a real pipeline run, `researcherId` present; data changed; no text object, no registry item, no new key);
- a second delivery after completion: replayed, no embed call, no write;
- the same taskId with a changed textHash processed again, and hash A -> hash B -> replay of B (last delivery replayed: no embed, no write);
- two concurrent deliveries of one task (latch: both inside the embed call at once): both processed, one completion record, final state correct;
- a failure between the vector write and the completion record: first delivery `Processed(completionRecorded=false)`, redelivery re-embeds, final state correct, then replayed;
- refusal on missing metadata (`textS3Uri`; `researcherId` in the profile index): permanent, no embed, no write;
- unknown index, unknown key, bad taskId, empty key, model mismatch: permanent, no embed, no write;
- transient failures (provider, throttled read, failed write) write nothing and leave no completion record;
- the ingest skip check still skips every vector after a re-embed;
- completion record in QuerySession, prefix `reembed#` + 64 hex, sort key `reembed`, `expiresAt` = now + 7 days; an expired record is not a replay;
- counters exact under 8 concurrent callers (801 tasks, 400 replayed, 400 permanent, 1 processed);
- GetVectors request sets `returnMetadata` true and `returnData` false;
- more than 100 tasks per call refused.

Changed tests: IngestPipelineSuite first test now expects `textAlreadyPresent` 0 on the skip pass (was 6: the old code re-put every text); DynamoSuite expects QS6.

## ASSUMPTIONS

- The corpus arrives grouped by index (RagIngestJob renders per template); if not, skip reads cost up to about 9 calls per 500-document window instead of 5 (still batched).
- S3 Vectors GetVectors returns non-filterable metadata keys (`textHash`, `textS3Uri`) when `returnMetadata` is true (the API page says "metadata", no split; the Phase 14 retrieval path relies on the same for QueryVectors).
- The embedding port implementations are thread-safe apart from rag's usage counters (VoyageEmbedder: JDK HttpClient plus a synchronized RateLimiter; read, not changed).
- The worker passes at most 100 tasks per `reembed` call; rag batches up to `embedding.batchSize` (64).

## RISKS

- Phases inside a window are sequential (skip read, text and registry reads, embedding, put, registry writes); windows are not pipelined, so the first-pass estimate adds the Voyage and store times. If D7 or a later first pass shows writes as the slowest step, overlapping windows is the next step (proposed later).
- The SDK's URL-connection HTTP client keeps few idle connections per host (JVM keep-alive default 5), so 16 parallel calls may open new TLS connections; covered in the high estimate; unverified.
- S3 Vectors `RequestTimeoutException` (HTTP 408) is classified permanent by `AwsErrors.wrap` (not 429/5xx): a 408 on a skip read rejects that call's documents for this run (retried next run); a 408 in the re-embed path is a permanent failure (DLQ). Storage retries belong to Phase 16 (out of scope here); noted.
- Concurrent duplicates of one re-embed task are not observable in counters (both count `processed`), by design (decision 6.3).
- A text change during an in-flight re-embed can leave older metadata and embedding (finding 4); not guaranteed, reported.
- With `embedConcurrency` 4, rag's `EmbeddingUsage` counts in the ingest report are approximate until rag's U-11 fix (step 3).

## INTERFACE HANDOFF

New settings and defaults (`IngestSettings`, `VectorIngestPipeline.scala:48-98`; named constants, not config keys, so `config/course.json` and core do not change):
- `writeParallelism: Int = 16` (1..64): text puts, registry latest reads, conditional registry puts, and skip-read calls in flight.
- `embedConcurrency: Int = 4` (1..16): embedding calls in flight.
- `windowDocuments: Int = 500` (1..5000): skip-check window.
- `AwsStorageEnvironment.pipeline` and `LocalStorageEnvironment.pipeline` use the defaults (signatures unchanged).

Ingest report additions (`IngestReport`, `toJson`): `skipReadCalls`, `registryReads`, `registryWrites`; `millis` keys now `skipRead`, `text` (text puts plus registry reads), `embed`, `put`, `registry` (registry writes), `total`. Skip semantics for rag's decision 7.1 test: the skip check reads `textHash`, `embeddingModel`, `embeddingDimensions` from the **index metadata** (GetVectors), not from ArtifactRegistry; a vector written by the Phase 14 code (older run ID and code version, same text and model) is skipped with zero text and vector writes (shown here under another run ID; rag's own test should seed state by running the pipeline, not by writing a registry item alone, because a registry item without a vector is re-written).

Re-embed operation (`org.scholarchain.storage.ingest`):
- `final case class ReembedTask(taskId: String, index: String, vectorKey: String)`
- `final class VectorReembedder(embedder: EmbeddingPort, vectors: VectorIndexPort, textStore: TextStore, kv: KeyValueStore, completionTable: String, spec: IndexSpecCatalog, retry: RetryPolicy, sleeper: Sleeper, nowSeconds: () => Long = <system clock>, completionTtlSeconds: Long = 604800)`
- `def reembed(tasks: Seq[ReembedTask]): Vector[ReembedOutcome]` (at most `VectorReembedder.MaxTasksPerCall` = 100 tasks; outcomes in input order; thread-safe).
- Factories: `AwsStorageEnvironment.reembedder(embedder: EmbeddingPort, sleeper: Sleeper = <env sleeper>)` (`AwsStorageEnvironment.scala:206`), `LocalStorageEnvironment.reembedder(embedder, sleeper = Sleeper.none, nowSeconds = <system clock>)` (`LocalStorageEnvironment.scala:67`). Both use the QuerySession table from configuration.
- Outcomes (`ReembedOutcome`): `Processed(task, textHash, completionRecorded: Boolean)` code `processed`; `Replayed(task, textHash)` code `replayed`; `PermanentFailure(task, code, detail)`; `TransientFailure(task, code, detail)`. `detail` holds field names or an exception class name only (never text, URL, ARN or store message).
- Permanent codes: `invalid-task`, `unknown-index`, `unknown-vector-key`, `metadata-missing`, `metadata-invalid`, `model-mismatch`, `text-missing`, `text-hash-mismatch`, `vector-read-refused`, `completion-read-refused`, `text-read-refused`, `embed-invalid`, `vector-write-refused`. Transient codes: `vector-read-failed`, `completion-read-failed`, `text-read-failed`, `embed-failed`, `vector-write-failed` (`ReembedOutcome.PermanentCodes`, `TransientCodes`).
- Suggested handler mapping (rag's decision): acknowledge `Processed` (also with `completionRecorded=false`: the vector is written; a redelivery would only re-embed) and `Replayed`; release a permanent failure at once; leave a transient failure to the visibility timeout.
- Idempotency rule: completion record key = (`taskId`, the vector's current `textHash`); checked before the embedding call, created put-if-absent after the vector write; a present, unexpired record means `Replayed` (no Voyage call, no write); a changed text has no record and is processed again. Final state: the vector holds the embedding of its current text with its metadata unchanged, whatever the number of deliveries, provided the text does not change while a task is in progress. Not guaranteed: exactly-once (concurrent duplicates and a crash between the vector write and the record embed twice; both count `processed`; concurrent duplicates are not observable).
- Metadata rule: GetVectors with `returnMetadata=true`, no data; refuse (permanent, nothing written) unless every key in `VectorReembedder.RequiredKeys` is present (plus `researcherId` in the researcher-profile index), the record decodes, and the stored `embeddingModel` and `embeddingDimensions` equal the embedder's; PutVectors then writes the new data with exactly that metadata.
- `taskId` pattern: `[A-Za-z0-9][A-Za-z0-9._:-]{0,127}` (`VectorReembedder.TaskIdPattern`); `index` must be one of the five spec index names; `vectorKey` must satisfy core `VectorKey.isValid`.

Completion record (QS6): table QuerySession (`config.dynamoDb.querySessionTable`); `pk = reembed#<sha256 hex of canonical (taskId, textHash)>`, `sk = reembed`; attributes `taskId`, `textHash`, `indexName`, `vectorKey`, `embeddingModel`, `completedAt` (epoch s), `expiresAt` (epoch s, now + 7 days, the table's existing TTL attribute); never updated.

Counters (`ReembedCounters`, AtomicLong, `reembedder.counters`, `toJson`): `tasks`, `processed`, `replayed`, `permanentFailures`, `transientFailures`, `embedCalls`, `putCalls`, `completionWrites`, `completionMissing`. `received` (messages, including bodies that never become a task) is rag's counter.

Every AWS call of the re-embed path (for infra-engineer's permission matrix, worker role):

| Call | Code (file:line) | IAM action | Resource | Worker today |
|---|---|---|---|---|
| GetVectors (metadata, no data) | `VectorReembedder.scala:199` -> `S3VectorsIndex.scala:86` | `s3vectors:GetVectors` | the five index ARNs | yes (`iam.tf:166-173`) |
| GetItem (completion record) | `VectorReembedder.scala:225` -> `DynamoDbKeyValueStore.scala:84` | `dynamodb:GetItem` | QuerySession table | yes (`iam.tf:155-162`) |
| GetObject (text) | `VectorReembedder.scala:241` -> `TextStore.scala:32-40` -> `S3ObjectStore.scala:50` | `s3:GetObject` (+ `s3:ListBucket` for a clean 404) | `rag/*` in the data bucket | yes (`iam.tf:175-190`) |
| PutVectors | `VectorReembedder.scala:293-296` -> `PutVectorsBatcher.scala:58-65` -> `S3VectorsIndex.scala:70` | `s3vectors:PutVectors` | the five index ARNs | **no: add** (boundary allows, `boundary.tf:81`) |
| PutItem `attribute_not_exists(pk)` (completion record) | `VectorReembedder.scala:372` -> `DynamoDbKeyValueStore.scala:54` | `dynamodb:PutItem` | QuerySession table | yes (`iam.tf:155-162`) |

The embedding call is rag's (Voyage over HTTPS, not AWS). If the vector bucket uses a customer-managed KMS key, PutVectors may also need a KMS encrypt permission: unverified, infra-engineer to check. No ArtifactRegistry, RunLedger or SQS call is made by storage on this path.

Ingest (ops role) calls changed by G-27: the new skip read is `s3vectors:GetVectors` (`VectorIngestPipeline.scala:346`), already granted (`iam.tf:296-305`). No BatchGetItem or BatchWriteItem is used, so no IAM or boundary change for the ops role.

Thread-safety: `VectorReembedder` thread-safe (atomic counters only); `IngestSession` used from one thread (internal pools are bounded and closed in `finish()`); `BoundedPool` daemon threads.

Fixtures: `IngestFakes.profileDoc(i, suffix)`, counting wrappers `CountingObjectStore`, `CountingKv` (`failPutsOn`), `CountingVectors` (`drop`, `failNextGets`, `failNextPuts`), `FlipEmbedder` (test scope, storage).

Limits: 100 tasks per `reembed` call; 100 keys per GetVectors; 500 vectors per PutVectors; completion TTL 7 days.

## Proposed later (not built)

- Overlap windows (embed window n+1 while window n writes) if a measured first pass shows writes as the slowest step.
- An HTTP client with a connection pool sized to `writeParallelism` (build-engineer: dependency) to avoid extra TLS handshakes.
- Classify S3 Vectors HTTP 408 as transient (Phase 16 storage retries).

## VALIDATION EVIDENCE

```text
work order sha256 (shasum -a 256 docs/agent-runs/phase-15/prompt.md): 3d806922c6dd74ada61bceca45d3a302740a4083f260f0ae3ca6b6b6b9dfa3e0 (matches)
INTERFACE CHECK: ServiceSettings.embeddingQueueUrl: Option[String] = None at ScholarChainConfig.scala:408 (exists; not used by storage)
aws-docs read: S3 Vectors GetVectors API (returnMetadata and returnData default false; at most 100 keys), PutVectors API, S3 Vectors limitations page (500 per PutVectors, 100 per GetVectors, 1,000 put requests/s and 2,500 vectors/s per index; no GetVectors rate listed)
sbt -batch "storage/Test/compile": [success] (main 8 sources, test 29 sources compiled)
sbt -batch "storage/testOnly ...IngestBatchingSuite ...ReembedSuite ...IngestPipelineSuite": Passed: Total 35, Failed 0
sbt -batch "storage/testOnly org.scholarchain.storage.*" (+ rag users), first full run: Failed 1 (DynamoSuite: QS6 not in the expected access-pattern list) -> DynamoSuite updated
sbt -batch "storage/testOnly org.scholarchain.storage.DynamoSuite" "rag/testOnly ...": storage Passed 13/13; rag Passed 44/44
source edit after that run: VectorIngestPipeline.scala (embed calls no longer catch an embedder exception: a throwing embedder fails the run, as before) at 12:05:31
FINAL: sbt -batch "storage/testOnly org.scholarchain.storage.*" "rag/testOnly org.scholarchain.rag.RagIngestJobSuite org.scholarchain.rag.RetrievalSmokeSuite org.scholarchain.rag.runtime.RuntimeRepairsSuite"
  storage: [info] Passed: Total 265, Failed 0, Errors 0, Passed 265, Ignored 1 (AwsAdaptersAwsSuite, RequiresAws, excluded) -- [success] 12:08:18
  rag:     [info] Passed: Total 44, Failed 0, Errors 0, Passed 44 -- [success] 12:08:19
  storage test reports written 12:08:16-12:08:18, newest storage source 12:05:31
sbt check: not run (not the wave closer)
git status --short modules/storage: 8 modified, 5 new (listed under FILES CREATED / FILES MODIFIED); nothing outside modules/storage written
files changed after these runs: none
```
