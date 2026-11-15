STATUS: COMPLETE
OWNER: storage-engineer
WORK ORDER: docs/agent-runs/phase-07/prompt.md, "Human architect decisions -> 6. Storage (storage-engineer)": all of the spec Phase 7 storage work and the spec Phase 8 storage-engineer work. I also read decisions 1, 8, 9 and 10, both verbatim spec work orders and the spec gates, under the file's Standing rules. Inputs were agent-report-build.md, agent-report-domain.md and agent-report-spark.md.

Summary: storage now has typed ports for object storage, graph bulk loading, read-only graph queries, bounded MERGE upserts, DynamoDB tables, vector indexes, the embedding work queue, the text store and embedding. Each port has an AWS SDK adapter and an in-memory or local-file fake.
- AWS adapters: S3, DynamoDB, S3 Vectors, Neptune data API and SQS, all synchronous clients on url-connection-client. They are covered only by tests tagged `RequiresAws`, which compile but were never run.
- Also built: the Neptune openCypher CSV builder and bulk-load request builder, the DynamoDB load plan with conditional writes, the S3 Vectors index spec file, and the embedding ingest pipeline with a manifest-driven queue.
- Local real-data run (attempt-01) on the full Phase 7 export: 0 rejects. Neptune CSV IDs equal the core IDs for every type. DynamoDB pass 2 made 0 data changes. Vector ingest run 2 skipped 100% (95,116 of 95,116).
- The final `sbt check` passed and I saw its output. OpenSearch is not implemented (optional tier, decision 1).

FILES CREATED (all under /Users/brian/Developer/repos/scholarchain/modules/storage/):
- Main sources, src/main/scala/org/scholarchain/storage/:
  - StorageLimits.scala, Retry.scala, StorageConfig.scala
  - ports/ObjectStore.scala, ports/KeyValueStore.scala, ports/VectorIndex.scala, ports/GraphPorts.scala, ports/WorkQueue.scala, ports/EmbeddingPort.scala
  - source/ExportSource.scala, source/TableReader.scala
  - s3/S3KeyLayout.scala
  - neptune/NeptuneModel.scala, neptune/OpenCypherCsv.scala, neptune/NeptuneCsvBuilder.scala, neptune/BulkLoadRequests.scala
  - dynamo/TableSpecs.scala, dynamo/ItemSupport.scala, dynamo/ItemCodec.scala, dynamo/ItemBuilders.scala, dynamo/LedgerAndRegistry.scala, dynamo/DynamoLoader.scala
  - vectors/VectorKeys.scala, vectors/IndexSpec.scala, vectors/VectorMetadataCodec.scala, vectors/PutVectorsBatcher.scala
  - ingest/TextStore.scala, ingest/VectorIngestPipeline.scala, ingest/IngestWork.scala
  - local/InMemoryStores.scala, local/InMemoryVectorIndex.scala, local/InMemoryGraph.scala, local/ManifestWorkQueue.scala, local/StubEmbedder.scala, local/LocalStorageEnvironment.scala
  - aws/AwsSupport.scala, aws/S3ObjectStore.scala, aws/DynamoDbKeyValueStore.scala, aws/S3VectorsIndex.scala, aws/NeptuneAdapters.scala, aws/SqsWorkQueue.scala
- Index spec for Terraform: src/main/resources/s3vectors/index-spec-v1.json (sha256 d18c0ad3165b387b99785f3bdc534309d69a1c489831e1949c3ab9d99a4eab52)
- Test config: src/test/resources/config/storage-local-v1.json (placeholder names only)
- Tests, src/test/scala/org/scholarchain/storage/:
  - TestFixtures.scala, SummaryDocuments.scala
  - ExportSourceSuite, NeptuneCsvSuite, GraphQuerySuite, DynamoSuite, VectorMetadataSuite, VectorKeysSuite, IngestPipelineSuite, WorkQueueSuite, ObjectStoreSuite, LocalRealRunSuite
  - AwsAdaptersAwsSuite (`RequiresAws`)
  - LocalRealRun.scala (test-scope runner)
- Generated, untracked: target/phase-07-real/attempt-01/
  - run-report.json (sha256 dcca2751c83adea6ef77df7c414d300f2c4e727c2b425160165d7732f843c15d)
  - neptune/ (21 .csv.gz files), rejects.jsonl (0 lines), vector-queue/run-1.jsonl and run-2.jsonl, local-s3/ (the graph run manifest)
  - 475 MB in total

FILES MODIFIED: none outside modules/storage/**. Nothing was committed and nothing was written under docs/agent-runs/.

COMMANDS RUN:
- Read-only commands only: cat, sed -n, grep, ls, find, wc, du, df, shasum, ps, tail, git status. I also grepped the class names in the AWS SDK jar central directories.
- Commands that wrote files: sed -i on my own storage files, plus one background `until grep ...; do sleep 15; done` wait loop.
- sbt, one at a time:
  - `storage/compile` twice: the first failed (2 errors, fixed), the second passed.
  - `storage/test` three times: the first had 1 test failure, then passed with 62 tests, then passed with 64.
  - `storage/Test/compile`
  - `storage/testOnly ...LocalRealRunSuite`: passed.
  - `sbt -batch -J-Xmx10g "storage/Test/runMain org.scholarchain.storage.LocalRealRun --export <repo>/modules/spark/target/phase-07-export/run=51b50d578d5b609842c1c21b8266a00b --out <repo>/modules/storage/target/phase-07-real/attempt-01"`: exit 0, real 325.72 s.
  - `storage/scalafmt; storage/Test/scalafmt`
  - `sbt check` three times. Run 1 failed at scalafmtCheck (files I had edited with sed). Run 2 failed on 1 test: a bug in my new test's filter (`"year"` does not end in `"Year"`). Run 3 passed.
- Note: the PostToolUse formatter hook started a short `sbt scalafmtOnly` while the real-data run was in progress. It only formats files and did not affect the run.
- No python, java, curl, docker, aws or terraform. No network apart from the Maven artifacts already resolved. No `awsTest` run.

TEST RESULTS (final `sbt check`):
- `Ledger check passed (scalafmt 3.11.5).`
- `Contract tests (no AWS): suites=22 executed=336 passed=336 failed=0 errors=0 skipped=0`
- `Module tests: suites=65 executed=1353 passed=1353 failed=0 errors=0 skipped=1`
- `Updated .claude/.last-test-run (module_tests=1353, contract_tests=336)`, timestamp 2026-11-15T17:59:47Z
- `[success] Total time: 796 s`
- Storage alone: 64 passed. AwsAdaptersAwsSuite has 5 tests, all excluded by tag, so the suite shows as "1 ignored".

VALIDATION RESULTS:
- Real run: status completed, 0 rejects at every stage.
- Neptune: `idsMatch` is true for every type. The order-independent digest of the input IDs equals the digest of the written IDs and of the IDs re-read from the CSV files.
- DynamoDB pass 2: dataChanges 0. The fake store's write counters on the five data tables did not change between pass 1 and pass 2. RunLedger went from 2 to 4 writes: the append-only claim and summary of each pass.
- Vector run 2: skipRatio 1.0, 0 embed calls, 0 put calls, 0 new text objects.
- 7 verification samples (the first document of each summary table): querying a document's own vector returns that document first, and its text is fetched through the `textS3Uri` pointer and matches `textHash`.

ASSUMPTIONS:
1. Graph-query bounds are storage code constants, because core has no keys for them: MaxDepth 6, MaxResultLimit 500, DefaultResultLimit 100, MaxTimeoutMillis 30,000.
2. Window choices for the new edges:
   - resolves_to and derived_from: the publication year, `[year, year]`, as Spark does for published_in;
   - supported_by: the document window (full-2015-2025).
3. The dataset_snapshot nodeId is `<provenance.datasetId>:<provenance.snapshotId>` of the work nodes. That gives one node, `scholarchain-graph:dblp-2026-11-01`, because the exported work nodes carry pipeline provenance, not raw dblp provenance.
4. VectorMetadata.provenance is the ingest provenance: runId is the ingest run, jobName is `storage.vector-ingest`, and dataset, snapshot, policy and sourceRecordIds come from the document. The Spark run stays reachable through sourceEvidenceId.
5. EntityLinkCache holds name links (author-name-key/1 folded dblp names) and ORCID links to researchers. These serve the runtime "resolve this name or ORCID" access pattern.
6. The test config uses placeholder names (`example-*`, `.invalid`) and AWS's documentation account ID 123456789012, as core's required `aws.accountId` key demands. The bulk-load test uses a placeholder role ARN with that account.
7. The run report was written before two test-only edits: the year filter fix, and a whitespace-only scalafmt pass on one test file. The runner's logic is unchanged.

RISKS:
- The aws-docs MCP server failed to connect (`uvx` is not installed), so I could not check these API details against current documentation. All should be verified before the first live run:
  - Neptune openCypher: whether `LIMIT $limit` may be a parameter; whether `id(n) = $p` and `properties()` are supported; how variable-length paths perform within the timeout;
  - Neptune bulk loader: `updateSingleCardinalityProperties` with the opencypher format, and whether reloading the same relationship IDs is idempotent;
  - S3 Vectors: the filter document syntax (`$eq`, `$in`, `$gte`, `$lte`, `$and`), number types in metadata, and how the service counts metadata bytes. My byte counts are a conservative estimate: key bytes plus value bytes, 8 bytes per number;
  - S3: PutObject `If-None-Match: *` returning 412.
- SqsWorkQueue.ack calls SQS DeleteMessage. This is how a consumer acknowledges a message, not data removal, but the reviewer should rule on it under the no-deletion rule.
- 64 ResearcherMetricCache items exceed 1 KB (max 1,655 bytes) and cost 2 write units each.
- Spark's community summaries repeat the group ID in subjectIds. I left them unchanged: the vector key uses only the first ID, and the ChunkId uses the full list.
- The local vector query latency (0.5 to 221 ms) is a brute-force in-memory fake and is not representative.
- InMemoryKeyValueStore keeps items as JSON bytes. The full local load needed a 10 GB heap (`-J-Xmx10g`).

MANUAL ACTION REQUIRED: none for this phase. Before the AWS runs (Phase 12 onwards):
- infra-engineer must create the indexes from index-spec-v1.json and the six tables with string `pk`/`sk` and TTL on QuerySession `expiresAt`;
- supply the Neptune loader role ARN and the SQS queue URL through the deploy environment;
- give runtime services read-only Neptune data access.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: rag-engineer (decision 7) consumes the interface below. Then code-reviewer (decision 9), then the human gate.

## Per-store role and key design

### Ordinary S3 (authoritative text, Neptune load files, run manifests, reports, logs)

Buckets and zone prefixes come from configuration (`ScholarChainConfig.s3Zones`). Code contains no bucket names.

| zone | key |
|---|---|
| raw | `<raw>/dataset=<d>/snapshot=<s>/<file>` |
| curated | `<curated>/dataset=<d>/snapshot=<s>/run=<hex>/<table path>/<file>` |
| graph, export copy | `<graph>/dataset=<d>/snapshot=<s>/run=<hex>/export/<table path>/<file>` |
| graph, Neptune load | `<graph>/dataset=<d>/snapshot=<s>/run=<hex>/neptune/{nodes,edges}/<Type>/<file>.csv.gz` |
| semantic-context | `<semantic-context>/text/v1/index=<index>/sha256=<hex>.txt` (content-addressed, no run ID) |
| reports | `<reports>/dataset=<d>/snapshot=<s>/run=<hex>/<name>` |
| logs | `<logs>/dataset=<d>/snapshot=<s>/run=<hex>/<component>/<name>` |
| run manifest | `<run-scoped prefix>/_run_manifest.json` |

- Run manifests (`scholarchain.storage.run-manifest` v1) list each object's key, bytes, sha256 and source references.
- Writes are put-if-absent only (`If-None-Match: *`). A rerun reports AlreadyPresent; different bytes under the same key report Conflict and are never overwritten.
- Key segments are validated: no `..`, no `/` inside a segment.

### Neptune (graph facts for bounded traversal)

- The openCypher CSV loader format, gzip, at most 500,000 rows per file.
- Node files have `:ID,:LABEL,...`; edge files have `:ID,:START_ID,:END_ID,:TYPE,...`. All properties are single-valued (no array columns).
- IDs are the core IDs verbatim.
- Labels: Researcher, Work, Venue, Institution, Topic, AuthorMention, DatasetSnapshot, TextChunk. Types: AUTHORED, PUBLISHED_IN, COAUTHORED_WITH, RESOLVES_TO, DERIVED_FROM, SUPPORTED_BY, AFFILIATED_WITH, HAS_TOPIC.
- Every row carries runId, datasetId, snapshotId and policyVersion.
- Researcher properties:
  - displayName and the node's own keys: anchorMentionId, distinctDblpOrcids, identityOutcome, mentionCount, minIdentityConfidence, reasonCodes, workCount;
  - identityConfidence and the metrics window (label, start and end year);
  - full-window publicationCount (P), distinctCoauthors (C), totalCoauthorOccurrences (T), averageCoauthorsPerPaper (A), maxCoauthorsOnOnePaper (M), weightedDegree (W);
  - componentId, componentSize, communityId, communitySize, coreness, participationCoefficient.
- Per-window metrics stay in DynamoDB.
- COAUTHORED_WITH, built for every window by joining `edges:coauthored_with` with `coauthor_edge` on edgeId:
  - properties: windowLabel, windowStartYear, windowEndYear, weight, jointPublicationCount (= sharedWorkCount), firstCollaborationYear, lastCollaborationYear, minIdentityConfidence, supportingWorkIds (`;`-joined), evidenceKey (= edgeId);
  - endpoints, window and weight must agree between the two tables, and supporting IDs must be non-empty and equal in number to sharedWorkCount. Otherwise the edge is a reject.
- RESOLVES_TO carries matchConfidence.
- TextChunk nodes (ChunkId) carry documentType, indexName, vectorKey, textKey (no bucket), textHash, windowLabel and sourceEvidenceId.
- Bulk-load requests: two jobs, nodes first, then edges with the nodes load ID as a dependency. Settings: format opencypher, failOnError TRUE, parallelism MEDIUM, queueRequest TRUE, updateSingleCardinalityProperties TRUE.
- Parameterized MERGE (allowlisted label, `UNWIND $rows`) is capped at `neptune.mergeRowCap`.

Read-only port (runtime services get only this one): typed queries ResearcherById, CoauthorNeighbors, CoauthorEdgeBetween, PathBetween (depth 1..6) and WorkAuthors.
- There is no way to pass query text. IDs are validated, and every value is a `$parameter`. Depth selects one of 6 precomputed path templates.
- Results are truncated at the limit and say so. The client API-call timeout is at most 30 s.

### DynamoDB (keyed lookups, ledgers, caches)

Every table uses string `pk` and `sk` and has no GSIs.

| table | pk | sk | access patterns | writes |
|---|---|---|---|---|
| RunLedger | `run#<runId>` | `attempt#<NNNNNN>#<seq NNNNNNNN>#<eventType>` (claim: `...#00000000#claim`) | RL1 Query all events of a run; RL2 Query one attempt (begins_with); RL3 claim the next attempt (conditional put, n+1 on conflict); RL4 append an event | append-only, put-if-absent |
| EntityLinkCache | `name#<folded name>` or `orcid#<iD>` | `researcher#<researcherId>` | EL1 Query a name to candidates; EL2 Query an ORCID; EL3 GetItem one link | upsert-if-changed |
| QuerySession | `session#<id>` | `turn#<NNNNNN>` | QS1 Query turns; QS2 conditional append; QS3 TTL on `expiresAt` | runtime only, 0 items loaded; the only TTL table |
| EvidenceBundle | `evidence#<evidenceId>` | `window#<start>-<end>`, `window#none`, or `bundle` | EB1 GetItem (evidenceId, window); EB2 Query all windows; EB3 GetItem an answer bundle (Phase 9) | upsert-if-changed; `record` holds the full Contract-encoded EvidenceItem |
| ArtifactRegistry | `artifact#<kind>#<key>` (vector: `artifact#vector#<index>/<vectorKey>`) | `v#<NNNNNNNN>` | AR1 latest version (Query descending, Limit 1); AR2 history; AR3 register version n+1 (conditional) | append-only |
| ResearcherMetricCache | `researcher#<researcherId>` | `window#<windowLabel>` | RM1 GetItem (researcher, window); RM2 Query all windows; RM3 idempotent reload | upsert-if-changed |

- ResearcherMetricCache attributes: researcherId, windowLabel, windowStartYear, windowEndYear, P, C, T, A (optional), M, W, componentSize, communitySize, coreness, crossCommunityDegree, communitiesConnected, participationCoefficient (optional), componentId, communityId, mrWorkIds (a list), identityConfidence, runId, snapshotId, policyVersion, itemHash, version.
- An item missing any of the seven families is rejected, never defaulted. The families are breadth, group_size, bridges, kcore, components, communities, and the metric evidence carrying the M_r work IDs.
- EvidenceBundle holds the 64 path evidence items.
- Coauthor edges are not loaded into DynamoDB (a test checks this).

### S3 Vectors (semantic retrieval only)

- Five indexes: researcher-profile-v1, collaboration-community-v1, paper-abstract-v1, collaboration-path-v1, topic-institution-summary-v1. Dimension 1024 (must equal `embedding.dimensions`), float32, cosine. The configured index names must equal the spec names, or storage refuses to ingest.
- Filterable keys (21): documentType, researcherId, communityId, startResearcherId, endResearcherId, workId, topicId, institutionId, windowStartYear, windowEndYear, year, venueKey, pathLength, sourceDataset, embeddingModel, embeddingDimensions, sourceType, datasetId, snapshotId, jobName, policyVersion.
- Non-filterable keys (7 of the 10 allowed): textS3Uri, textHash, documentId, sourceEvidenceId, runId, sourceRecordIds, schemaVersion.
- Metadata is the core VectorMetadata record, flattened:
  - vectorKey and indexName are the vector's own key and index, so they are not stored;
  - provenance is flattened into separate keys; its runId and contentHash equal runId and textHash, so they are not repeated;
  - absent optional fields are absent keys.
- Real-run metadata stays well inside the limits. Tests enforce: filterable at most 2 KB, total at most 40 KB, at most 50 keys, at most 10 non-filterable keys, no value over 1,024 characters, no document text, and no graph metric keys.
- Vector key rule, derived from `subjectIds` and the window label (never from the run or the text):
  - `rp:<windowLabel>:<researcherId>`
  - `cm:<windowLabel>:<communityGroupId>` (the first subject ID)
  - `pa:<windowLabel>:<sourceResearcherId>:<targetResearcherId>` (the first and last subject IDs)
  - `pp:<workId>`
  - `ti:<windowLabel or ->:<subjectKey>`
  - Chunk ordinals of 1 or more append `#<n>`.
- Indexes are created by Terraform. The adapter only describes, writes, reads and queries them.

### Batch sizes and retry policy

- PutVectors: at most `vectors.putBatchSize` (500 in config) and an estimated payload under 19 MiB (the 20 MiB limit minus a 1 MiB margin). GetVectors: at most 100 keys.
- Embedding: at most `EmbeddingPort.maxBatchSize` texts per call (local run: 128).
- Retries:
  - storage's own: bounded exponential backoff on transient errors only (throttling, HTTP 429, 5xx, retryable client errors), using `vectors.maxRetries`, `retryBaseDelayMillis` and `retryMaxDelayMillis` (5, 200 ms, 10 s), with no jitter;
  - SDK clients: at most 3 attempts with the standard strategy, plus connect, socket and API-call timeouts.
- DynamoDB writes are single conditional PutItems, because BatchWriteItem cannot carry conditions. DynamoLoader accepts a parallelism of 1 to 64 (local run: 1).
- SQS: batches of 10, long poll of at most 20 s, receive limit 10.
- Failed batches: every vector in the batch goes to the reject file and to RunLedger as `vector-reject`, plus one `put-failure` event with its retry count. A successful batch that needed retries records `put-retried`.

## Idempotency evidence

- DynamoDB:
  - Mutable tables use upsert-if-changed: put-if-absent at version 1; the same itemHash means no write; a different itemHash is replaced with version + 1 under a version check; a lost race is reported as Conflict.
  - Append-only tables are put-if-absent; different content under an existing key is a Conflict and is never written.
  - Real run: pass 2 changed 0 of 758,598 data items; the store's write counters did not move.
- Vectors:
  - The ArtifactRegistry decides: the same textHash, embeddingModel and dimensions means skip.
  - Changed text or model triggers a re-embed, an overwrite, a registry version n+1 holding the previous hash, model and dimensions, and a `vector-overwrite` RunLedger event with the old and new hash and model.
  - The registry entry is written only after a successful put.
  - Within one run, a repeated key with the same text is counted as a duplicate; with different text it is rejected.
  - Real run 2: 100% skipped.
- Neptune: the IDs are the core stable IDs, and output is deterministic (identical sha256 on rebuild, tested).
- S3: content-addressed text keys; run-scoped keys elsewhere; put-if-absent.
- Work queue: deterministic item IDs (digest of source run, table, file and range) and an append-only journal, so enqueuing the same plan twice adds nothing.

Tests that would fail if the behaviour broke:
- conditional writes, stale version, append conflict;
- pass 2 makes 0 changes;
- run 2 skips 100%;
- the same key with different text is recorded, and registry version 2 holds the previous hash;
- a model change re-embeds everything;
- duplicate keys;
- Neptune IDs equal core IDs;
- COAUTHORED_WITH carries supporting work IDs and window;
- endpoint, disagreement and orphan rejects;
- metadata limits, missing or malformed metadata, and no text in metadata;
- throttling is retried with 200 and 400 ms delays; retries stop at 5; permanent failures are not retried;
- the text pointer resolves, and filters work only on filterable keys;
- injection attempts on query bounds are rejected.

## Bounded load results (local real data, attempt-01, run-report.json)

Input: export manifest sha256 f6ebe62de6bd5aac13784ac74a5da22be0a65c02603a2d7138a2c012dd173021, source run `run:v1:51b50d578d5b609842c1c21b8266a00b`, 65 tables, 12,571,066 records. Heap 10 GiB, 10 processors, 331 GB free disk at start. Total 322 s.

Step timings:

| step | time |
|---|---|
| Neptune CSV | 160.7 s |
| DynamoDB pass 1 | 52.2 s |
| DynamoDB pass 2 | 50.1 s |
| vector plan | 0.6 s |
| vector run 1 | 38.4 s |
| vector run 2 | 19.3 s |

No step came close to 60 minutes, and free disk stayed far above 15 GB.

Neptune CSV (gzip):

| stage | type | rows | files | bytes (gz) | uncompressed |
|---|---|---|---|---|---|
| nodes | Researcher | 200,484 | 1 | 17,643,524 | 77,475,753 |
| nodes | Work | 155,978 | 1 | 11,799,842 | 43,648,270 |
| nodes | Venue | 16 | 1 | 357 | 1,827 |
| nodes | Institution | 0 | 0 | 0 | skipped (0 rows in the export) |
| nodes | Topic | 0 | 0 | 0 | skipped (0 rows in the export) |
| nodes | AuthorMention | 758,328 | 2 | 33,096,148 | 144,104,508 |
| nodes | DatasetSnapshot | 1 | 1 | 184 | 351 |
| nodes | TextChunk | 64,090 | 1 | 6,960,117 | 32,237,478 |
| edges | AUTHORED | 758,314 | 2 | 47,598,330 | 176,687,460 |
| edges | PUBLISHED_IN | 155,978 | 1 | 6,819,759 | 31,205,689 |
| edges | COAUTHORED_WITH (5 windows) | 3,361,084 | 7 | 320,777,342 | 1,251,677,045 |
| edges | RESOLVES_TO | 758,328 | 2 | 41,483,278 | 184,283,064 |
| edges | DERIVED_FROM | 155,978 | 1 | 6,625,473 | 34,939,207 |
| edges | SUPPORTED_BY | 64,090 | 1 | 4,066,190 | 15,189,465 |
| edges | AFFILIATED_WITH | 0 | 0 | 0 | skipped (no enrichment data) |
| edges | HAS_TOPIC | 0 | 0 | 0 | skipped (no enrichment data) |
| total | | 6,432,669 | 21 | 496,870,544 | 1,991,450,117 |

Every count matches decision 10: 200,484 researchers, 155,978 works, 16 venues, 758,328 mentions (authorship), 3,361,084 coauthored_with, 758,314 authored, 155,978 published_in. No rejects; `idsMatch` true everywhere.

DynamoDB items per table:

| table | items | pass 1 inserted | pass 2 changes | write units if written |
|---|---|---|---|---|
| ResearcherMetricCache | 503,448 | 503,448 | 0 (503,448 unchanged) | 503,512 |
| EntityLinkCache | 255,065 | 255,065 | 0 | 255,065 |
| EvidenceBundle | 64 | 64 | 0 | 128 |
| ArtifactRegistry (Neptune CSV artifacts) | 21 | 21 | 0 | not computed (small items) |
| QuerySession | 0 | 0 | 0 | 0 |
| RunLedger | 4 (2 claims, 2 summaries) | | append-only audit | |

- 0 names skipped and 0 rejects.
- ResearcherMetricCache item sizes: min 659, p50 695, p90 728, p99 785, max 1,655 bytes. Buckets: 513 to 768 bytes: 492,480; 769 to 1,024: 10,904; 1,025 to 2,048: 64. **64 items are over 1 KB.**

Vector ingest (stub embedder `storage-stub-sha256-v1`, 1024 dimensions, batch 128; 99 work items of 1,000 lines):

| | run 1 | run 2 |
|---|---|---|
| input | 95,116 | 95,116 |
| researcher-profile-v1 | 64,090 written | 64,090 skipped |
| collaboration-community-v1 | 30,962 written | 30,962 skipped |
| collaboration-path-v1 | 64 written | 64 skipped |
| paper-abstract-v1, topic-institution-summary-v1 | 0 (no storage input; rag renders these) | 0 |
| rejected / duplicates | 0 / 0 | 0 / 0 |
| text objects created / already present | 95,116 / 0 | 0 / 95,116 |
| embed calls / put calls / retries | 763 / 335 / 0 | 0 / 0 / 0 |
| total time | 38,418 ms (embed 16,577; put 585; registry 1,174; text 860) | 19,329 ms |
| throughput | 2,476 docs/s (local fake, not representative) | 100% skip |

- Index counts after both runs: 64,090 / 30,962 / 0 / 64 / 0.
- 95,116 text objects (50,307,131 bytes) and 95,116 registry items.
- 0 work items failed and 0 poison items.
- The 7 samples all returned their own document first and resolved their text pointer with a matching hash. Local query times: 0.5 to 221 ms (fake, not representative).

## Cost and object counts (estimates from decision 8 prices, captured by the human architect on Nov 15)

- **DynamoDB:** about 758,700 write units for the first load, about $0.47 at $0.625 per million (about $0.95 at $1.25 per million). Reloads cost reads only.
- **S3 Vectors:** about 95,116 vectors at about 4.9 KB each (4,096 data bytes plus about 0.7 KB metadata), about 0.47 GB. Storage about $0.03 per month at $0.06 per GB-month; upload about $0.09 at $0.20 per GB.
- **Ordinary S3:** 21 CSV objects (0.50 GB), 95,116 text objects (50 MB), plus run manifests.
- **Voyage:** about 50.3 MB of text, roughly 12.6M tokens, inside the 200M free tokens (about $0.75 at list price).
- **Neptune:** free trial (provisioned db.t4g.medium), bulk loader only.

## Service limits cited (decision 8, captured by the human architect on Nov 15)

- S3 Vectors: dimension 1 to 4,096; metadata total up to 40 KB, filterable up to 2 KB, up to 50 keys; up to 10 non-filterable keys, fixed at index creation; PutVectors up to 500 vectors and 20 MiB; GetVectors up to 100; up to 1,000 PutVectors+DeleteVectors requests/s and 2,500 vectors/s per index; top-K not verified, so it is configurable (`vectors.queryTopK`, 30).
- Neptune: openCypher CSV loader format with single-valued edge properties; db.t4g.medium free trial, bulk loader only.
- DynamoDB on-demand: about $0.625 per million write units (some sources say $1.25).
- Not in decision 8, documented AWS values, not re-verified: DynamoDB 400 KB item limit and 1 KB per write unit.

## Cleanup procedures (for infra-engineer's scripts/cleanup.sh; the loaders contain no deletion code)

1. **S3:**
   - Remove a superseded run by its run-scoped prefix `<zone>/dataset=<d>/snapshot=<s>/run=<hex>/`, only after the newer run is loaded and verified.
   - Text objects are shared across runs. Remove only orphans: keys not referenced by the latest ArtifactRegistry version of any vector. When an index is retired, remove its `text/v1/index=<index>/` prefix.
   - Integration-test leftovers: `logs/dataset=itest/...`.
2. **DynamoDB:**
   - QuerySession expires through TTL, so no script is needed.
   - RunLedger and ArtifactRegistry are append-only; apply a retention policy by partition (`run#<runId>`, old `v#` versions) with human approval.
   - Data items of a superseded graph run carry `runId`; after a verified reload, remove items whose runId is not current with a bounded scan and filter.
   - Integration-test items: `run#itest#...`.
3. **S3 Vectors:**
   - Stale keys (subjects no longer produced, for example researcher IDs that vanished after an entity-resolution rerun): diff the registry against the current document set and remove them in bounded batches.
   - Integration-test keys: `itest:` prefix.
   - Model change: create the `-v2` indexes through Terraform, re-ingest, switch config, then retire the old indexes through an approved plan.
4. **Neptune:** after a verified reload, remove nodes and edges whose runId is not current with approved bounded batches, or recreate the cluster through Terraform with human approval.
5. **SQS:** inspect dead-letter messages, then remove them through the approved script.
6. **Local:** `modules/storage/target/phase-07-real/attempt-NN` is untracked build output.

## OpenSearch statement

OpenSearch is not implemented. It is the optional tier (decision 1; spec: "only when the team implements hybrid lexical and vector retrieval or a measured hot tier"). S3 Vectors plus ordinary S3 are the only semantic stores, and DynamoDB and Neptune hold the facts, so there is no second source of truth.

## Deferred items (decision 1, to Phases 14/15)

- The bounded AWS storage integration suite (AwsAdaptersAwsSuite: compiled, tagged `RequiresAws`, never run). Bounds: at most 2 objects, items or vectors and 1 message per test; 5 s API timeouts; at most 3 attempts; 60 s per test.
- The bounded AWS ingestion of at least 10,000 vectors.
- Real-model retrieval smoke tests, real throughput, real latency and real cost.

## Interface for rag-engineer

**Embedding port** (`org.scholarchain.storage.ports`):

```scala
final case class EmbeddingBatch(vectors: IndexedSeq[Array[Float]], retries: Int)
final case class EmbeddingFailure(message: String, retries: Int)
trait EmbeddingPort {
  def modelId: String        // stored verbatim as VectorMetadata.embeddingModel; \S+, <= 1024 chars;
                             // must change whenever vectors for the same text would change
                             // (provider/model/dims/input type/version)
  def dimensions: Int        // must equal the index spec dimension (1024), else the pipeline refuses
  def maxBatchSize: Int      // > 0; the pipeline never sends more texts per call
  def embed(texts: IndexedSeq[String]): Either[EmbeddingFailure, EmbeddingBatch]
  // exactly one finite vector of length `dimensions` per text, in order;
  // provider 429/5xx retries happen inside embed and are reported in `retries` (recorded in RunLedger)
}
```

**Pipeline entry point** (`org.scholarchain.storage.ingest.VectorIngestPipeline`):

```scala
new VectorIngestPipeline(embedder, textStore, vectors: VectorIndexPort, kv: KeyValueStore,
  tables: DynamoDbSettings, spec: IndexSpecCatalog, windows: List[NamedWindow],
  settings: IngestSettings(putBatchSize, retry: RetryPolicy, sourceDataset, jobName = "storage.vector-ingest"),
  rejects: RejectLog, sleeper: Sleeper, extrasOf: SemanticDocument => DocumentExtras = _ => DocumentExtras.none)
def run(runId: RunId, documents: Iterator[SemanticDocument]): IngestReport   // one RunLedger attempt
def open(runId: RunId, label: String): IngestSession                         // ingest(it) many times, then finish(): IngestReport
```

- `DocumentExtras(year: Option[Int], venueKey: Option[String])` lets rag supply a paper's year and venue as filterable metadata.
- `IngestReport` has input, skipped, skipRatio, written, rejected, perIndex (IndexStats: input, skippedUnchanged, embeddedNew, reembeddedChanged, written, rejected, duplicatesSameText), textCreated, textAlreadyPresent, embedCalls, embedRetries, putCalls, putRetries, registryConflicts and millis, plus `toJson`.

**Local wiring for rag's ingest main** (in-memory fakes; keep one environment for both runs):

```scala
val cfg = StorageConfig.from(scholarChainConfig).fold(e => sys.error(e.mkString("; ")), identity)
val env = org.scholarchain.storage.local.LocalStorageEnvironment.create(cfg, Some(rejectFile))
val pipeline = env.pipeline(embedder, sourceDataset = "dblp", extrasOf = ...)
val r1 = pipeline.run(runId, docs()); val r2 = pipeline.run(runId, docs())   // r2.skipRatio == 1.0
env.vectors.query(index, vector, topK, filter: Option[MetadataFilter], returnMetadata = true)
VectorMetadataCodec.decode(hit.key, index, hit.metadata.get)                   // core VectorMetadata
env.textStore.fetch(meta.textS3Uri, meta.textHash)                            // text through the S3 pointer
env.close()
```

Each document must satisfy all of these, or it is rejected and recorded:
- it passes core validation;
- `indexName == cfg.vectors.indexFor(documentType)`;
- its window is one of `graph.windows`;
- `vectorKey` follows `VectorKeys.derive(type, Some(windowLabel), subjectIds, ordinal)`;
- for Spark summaries, `documentId = ChunkId.forSubjects(type, subjectIds, Some(windowLabel), ordinal, ContentHash.of(text))`, with summary kinds mapped researcher_profile→researcher_profile, community→community_summary, path→collaboration_path.

Work queue, if wanted:
- `IngestWorkPlanner.plan(source, manifest, tables, linesPerItem)`, `IngestWorkPlanner.readLines(source, manifest, LineRange)`;
- `new ManifestWorkQueue(journalPath, maxReceives)`;
- `new IngestWorker(queue, receiveBatch, maxItems).drain(session, payload => Either[String, Iterator[SemanticDocument]])`.

Metadata filters: `MetadataFilter.Eq`, `In`, `Gte`, `Lte` and `And`, on filterable keys only. The test-scope `SummaryDocuments` is storage's stand-in and is not for rag to reuse.

## PROPOSED LATER

1. Core config keys: `neptune.maxDepth`, `neptune.resultLimit`, `neptune.loaderRoleArn` (or its env-var name), `queue.url` (or its env-var name). Today these are code constants or supplied by the deploy environment.
2. A `sourceRunId` (Spark run) field on VectorMetadata (core v2), so vectors name the graph run directly.
3. Jitter in the backoff, once live throttling behaviour is measured.
4. A GSI for "top-K bridges per window" if runtime needs it; today it is served by Neptune or the S3 exports.
5. A dataset_snapshot node keyed by the raw dblp snapshot (`dblp:2026-11-01`), once Spark exports carry raw-source provenance on work nodes.
6. A DynamoDB table spec resource file for Terraform, like the index spec.
7. A gzip-aware export source, if Spark adds `.jsonl.gz` output.
8. A bounded parallel registry lookup (BatchGetItem) for faster AWS ingest.
9. Verify the API details listed under RISKS with the aws-docs MCP once `uvx` is installed.
