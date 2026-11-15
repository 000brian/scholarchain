# Code review: Phase 7+8 (storage adapters and loading, embeddings and S3 Vectors)

Reviewer: code-reviewer (read-only: Read, Grep, Glob). Work order: `docs/agent-runs/phase-07/prompt.md`, decision 9, covering decisions 3 to 7. Baseline: commit c12142b.

## VERDICT: REQUEST_CHANGES

There is one verified **major** finding (M1). The AWS SDK adapters wrap every SDK exception in `StoreException` before their own `catch` clauses run. As a result, DynamoDB conditional-check failures, S3 `NoSuchKey` and S3 Vectors `NotFound` are never mapped to their intended outcomes. On real AWS this breaks conditional-write idempotency, which is the Phase 7 gate "Loaders are idempotent" and the failure-handling part of "reviewer approves schema and failure-handling behavior". The local fakes behave correctly, so `sbt check` passes and the real-data runs are valid. No test outside the `RequiresAws` suite exercises the adapter's error mapping.

Everything else conforms. The other findings are minor or advisory: they are listed for the human and not routed for repair.

---

## Spec gates, one by one

| Gate | Ruling | Evidence |
|---|---|---|
| **P7** Long text is not stuffed into vector metadata | PASS | `VectorMetadataCodec.scala:162-201`: per-value limit of 1024 characters, plus a no-text check against the document text. Core `VectorMetadata` has no text field. `VectorMetadataSuite` "document text in metadata is detected and rejected". `IngestPipelineSuite:231-234`. |
| **P7** Neptune graph identifiers match Spark output identifiers | PASS | `NeptuneCsvBuilder.scala`: IDs are written verbatim, with input, written and re-read digests (`TypeStats.idsMatch`). `NeptuneCsvSuite:50-77` compares against the fixture core IDs, including ChunkIds. The real run report has no `"idsMatch": false`. |
| **P7** COAUTHORED_WITH edges can be traced to supporting work evidence | PASS | `NeptuneCsvBuilder.scala:379-442`: join on edgeId, rejects on endpoint, window or weight disagreement, and requires supportingWorkIds count == sharedWorkCount. Edges carry `supportingWorkIds` (`;`-joined), the window and `evidenceKey`. `NeptuneCsvSuite:79-131`. |
| **P7** DynamoDB keys support stated access patterns | PASS | `TableSpecs.scala`: explicit pk and sk with access patterns RL1-4, EL1-3, QS1-3, EB1-3, AR1-3 and RM1-3. The implementation queries match them (`ArtifactRegistry.latest` uses begins_with `v#`, descending, Limit 1). EvidenceBundle is keyed by (evidenceId, window). |
| **P7** Loaders are idempotent | PASS locally; **FAIL on the AWS adapter (M1)** | Fakes: DynamoDB pass 2 `dataChanges: 0` (run-report line 513), vector run 2 `skipRatio: 1` (line 705), rag run 2 `skipRatio: 1` (line 274). AWS: `DynamoDbKeyValueStore.scala:42-74` and `AwsSupport.scala:74-76` (see M1). |
| **P7** AWS integration tests are bounded and separately enabled | PASS | `build.sbt:95-103,118`: `--exclude-tags=RequiresAws` in Test, and an `AwsTest` config that includes only that tag. `AwsAdaptersAwsSuite`: 60 s per test, 5 s API timeout, 3 attempts, at most 2 items. `LiveProvidersAwsSuite`: 120 s, at most 9 requests. |
| **P7** Reviewer approves schema and failure-handling behaviour | Schemas approved; **failure handling not approved until M1 is repaired** | See M1. |
| **P8** The same vector key cannot silently refer to different text | PASS (logic); M1 affects the AWS race path | `VectorIngestPipeline.scala:224-249,408-459`: registry check, re-embed, registry version n+1 holding the previous hash, and a `vector-overwrite` RunLedger event. A duplicate key with different text in one run is rejected. `IngestPipelineSuite:83-135`. |
| **P8** The embedding model version is stored | PASS | `embeddingModel` and `embeddingDimensions` are filterable metadata, and the registry stores them. `EmbeddingModelSpec.id` = provider/model/dims/inputType/emb-v1. A model change re-embeds everything (`IngestPipelineSuite:112-120`, `EmbeddingProvidersSuite:70`). |
| **P8** Source text can be fetched from the vector result through its ordinary S3 pointer | PASS (M1 affects a missing-object case on AWS) | `TextStore.fetch` verifies the hash. `IngestPipelineSuite:211-235`. Real run: 100 of 100 top-1 pointers resolved with a matching hash. |
| **P8** Retrieval smoke tests pass | PASS (fake, lexical; the real model is deferred by decision 1) | `RetrievalSmokeSuite` (9 tests). Real-data smoke: 25/25, 23/25 (25/25 within window), 25/25 and 25/25. The Voyage smoke test is written and tagged `RequiresAws`. |
| **P8** Exact counts, paths, components and bridge metrics come from graph or metric stores, not vector similarity | PASS for this phase | Metadata holds no metric keys (`VectorMetadataSuite:232-267`). The semantic-context entries carry pointers only and include the `neverASourceOf` flag (`SemanticContext.scala:79-107`). Enforcement in synthesis is Phase 9. |
| **P8** Cost and object counts are reported | PASS | Storage report: "Cost and object counts". Rag report: per-provider costs (Voyage, Bedrock and Anthropic, each $0 expected) plus AWS line items. |
| **P8** Bounded AWS ingestion of at least 10,000 vectors | DEFERRED by decision 1 | See advisory A1: no AWS store wiring exists yet for this run. |

## Decision 9 rulings

1. **Long text is never in vector metadata, and the limits are enforced by tests.** Conforms. The limit tests (filterable over 2 KB, total over 40 KB, more than 50 keys, more than 10 non-filterable keys, a value over 1024 characters, text present) are in `VectorMetadataSuite:169-216`. The pipeline runs `checkLimits` with the document text before every put (`VectorIngestPipeline.scala:308`).
2. **Neptune IDs equal Spark IDs, and coauthored_with edges carry their evidence.** Conforms (gates above).
3. **DynamoDB keys, conditional writes and TTL.** Keys match the access patterns. Conditional writes are correct in `ConditionalWriter` and the fakes, but **broken in the AWS adapter (M1)**. TTL is only on QuerySession `expiresAt` (`TableSpecs.scala:92`, test `DynamoSuite:101`). RunLedger and ArtifactRegistry are append-only.
4. **Loaders and ingest are idempotent.** The second local runs changed nothing (DynamoDB 0 of 758,598; vectors 95,116 of 95,116 skipped; rag 251,094 of 251,094 skipped, with 0 embed and 0 put calls). The AWS path fails (M1).
5. **The same key cannot silently point to different text, and the model is stored.** Conforms. Minor race note m3.
6. **No number, path, component, community or bridge score can come from vector text.** Conforms for this phase. The only numeric metadata are windowStartYear, windowEndYear, year, pathLength, embeddingDimensions and schemaVersion. pathLength comes from Spark subjectIds, not from vector text, and the semantic-context format does not pass it on (advisory A6).
7. **No secret, key, account ID or bucket name in code, config, tests or reports.** Conforms:
   - main sources contain no bucket, account, region or ARN literal (grep);
   - test configs use `example-*` and `.invalid` names;
   - the Voyage key is read only from the env var named in config, scrubbed from errors, and covered by tests;
   - the reports contain only the placeholder ID and placeholder buckets (see ruling c).
8. **RequiresAws tests are excluded from `sbt check` and bounded.** Conforms (gate above).
9. **Tests would fail if the behaviour were broken.** Conforms for every fake-backed behaviour: overwrite recording, duplicate keys, model change, the retry bound with exact delays (200 and 400 ms), pass-2 zero changes, ID equality, and evidence columns. **Gap:** no test outside `RequiresAws` covers the AWS adapters' exception mapping, which is why M1 passed `sbt check`. The `RequiresAws` test `AwsAdaptersAwsSuite:63` would catch it, but it is never run.
10. **Ownership.** Conforms on all evidence available without git. Every modified or untracked path falls in one owner's territory and matches that owner's report:
    - build-engineer: `build.sbt`, `project/build.properties`, `project/Ledger.scala`;
    - domain-engineer: `modules/core/**`;
    - spark-engineer: 3 new files under `modules/spark/src/**`, none modified;
    - storage-engineer: `modules/storage/**`;
    - rag-engineer: `modules/rag/**`.

    `tests/**`, `modules/services/**`, `prompts/**` and `infra/**` are untouched. `docs/agent-runs/instructor-approvals.md` and `docs/agent-runs/phase-07/` are human-owned. Tracked `.DS_Store` files show as modified (Finder metadata, not agent work; advisory A4).

## Rulings on the points the agents raised

**(a) `SqsWorkQueue.ack` calls SQS DeleteMessage.** Permitted. It does not violate "no deletion code in loaders":
- DeleteMessage on a receipt handle is how SQS acknowledges that a message was consumed. It is not data removal.
- Queue messages are not a system of record. Work items are derived deterministically from the export manifest (`IngestWorkPlanner`), and the authoritative data is in S3, DynamoDB and S3 Vectors.
- Without the ack, every message would be redelivered until it reached the DLQ.
- No loader deletes from S3, DynamoDB, S3 Vectors or Neptune (grep of storage and rag main sources: the only `delete` is `SqsWorkQueue.scala:78`).

Advisory: infra-engineer must grant `sqs:DeleteMessage` on the work queue only (Phase 12), and the runbook should name this exception.

**(b) voyage-4-lite at 256 dimensions (in the approval request) versus voyage-4 at 1024 (decision 7).** The implementation conforms to the human's decision. In `instructor-approvals.md`, the 256-dimension voyage-4-lite appears only in the student's question. The instructor's verbatim answer approves "Voyage AI for embeddings" without naming a model. Its conditions are: document providers, model IDs and dimensions; regenerate embeddings and rebuild the index if the model changes; count charges per provider. The human's "How it is applied" line (line 33) and decision 7 set `voyage-4` at 1024. The code matches:
- `EmbeddingModelSpec.VoyageDefaultModel = "voyage-4"`;
- the voyage example config uses `voyage-4` at 1024;
- the index spec dimension is 1024, checked against `embedding.dimensions` (`IndexSpec.scala:80-91`);
- a model change alters the spec ID, which forces a re-embed (the instructor's condition).

Advisory: when the README is written, documentation-engineer should state the difference from the model named in the request.

**(c) Placeholder account ID 123456789012.** It does not break the rule:
- it is AWS's reserved documentation example ID, not a real account;
- it already existed in the baseline (`modules/core/src/test/resources/config/valid-v1.json:6`);
- it appears only in test resources and one test role ARN (`NeptuneCsvSuite:180`);
- core's required `aws.accountId` key needs a 12-digit value to load.

No main source contains it.

**(d) Spark export drops non-core columns (path hops, summaryKind, metric team sizes).** Nothing the gates need is lost:
- Dropped columns are recorded per table in `_export_manifest.json` as `sourceColumnsNotExported` (13 tables).
- Path order survives in `subjectIds`. Each hop's edge is recomputable with `EdgeId.of(coauthored_with, a, b, window)`, and that edge carries supporting work IDs in `coauthor_edge` and in Neptune.
- `summaryKind` survives in the table path and is mapped explicitly by rag's `TemplateRegistry`.
- The M_r work IDs survive in metric-evidence `subjectIds`, and storage loads them as `mrWorkIds`.

Advisory A7: Phase 9 evidence for paths needs a join to show per-hop edge IDs.

---

## Findings

### Major (repair round required)

| # | Severity | File:line | Failure input | Impact | Owner |
|---|---|---|---|---|---|
| M1 | major | `modules/storage/src/main/scala/org/scholarchain/storage/aws/DynamoDbKeyValueStore.scala:42-55` (putIfAbsent), `:57-74` (putIfVersion); root cause `aws/AwsSupport.scala:74-76` (`guard`); same pattern in `aws/S3ObjectStore.scala:46-55` (`get`) and `aws/S3VectorsIndex.scala:21-34` (`describeIndex`) | A second `DynamoLoader.load` pass against real DynamoDB. Also any `RunLedger.open` or `ArtifactRegistry.register` that hits an existing key: two SQS workers on one run, or a duplicate delivery. Also `TextStore.fetch` on a missing S3 text object, and `describeIndex` on a missing index. | See below. | storage-engineer |

**Mechanism.** `AwsErrors.guard` catches every `SdkException` and rethrows it as `StoreException`. `ConditionalCheckFailedException`, `NoSuchKeyException` and the s3vectors `NotFoundException` are all `SdkException` subclasses, so the surrounding `catch { case _: ConditionalCheckFailedException => ConditionFailed }` (and the `NoSuchKey` and `NotFound` clauses) can never match.

**Effects on AWS:**
- `ConditionalWriter.upsertIfChanged` and `append` (`dynamo/ItemSupport.scala:90-122`) throw on the first existing item. A reload that should make 0 changes aborts instead (sequential mode, `DynamoLoader.scala:143-145`), or counts every existing item as a conflict and reject (parallel mode).
- `RunLedger.open` cannot advance to n+1 on a claim race.
- `ArtifactRegistry.register` throws after PutVectors has already overwritten the vector. The `vector-overwrite` ledger event (`VectorIngestPipeline.scala:444-459`) is then skipped, so a racing overwrite goes unrecorded. The `SqsWorkQueue` doc's claim that "duplicated deliveries are harmless" becomes false.
- `TextStore.fetch` throws instead of returning `Left("no object ...")`.
- `describeIndex` throws instead of returning `None`.

**Repair proof.** Add a non-AWS unit test in storage's test tree, run by `sbt check`. Implement `DynamoDbClient` (a Java interface) as a stub whose `putItem` throws `ConditionalCheckFailedException.builder().message("x").build()`. Assert that `putIfAbsent` and `putIfVersion` return `ConditionFailed`, and that `ConditionalWriter.upsertIfChanged` returns `Unchanged` or `Conflict` rather than throwing. Add equivalent stubs:
- `S3Client.getObjectAsBytes` throwing `NoSuchKeyException`, so `get` returns `None` and `TextStore.fetch` returns Left;
- `S3VectorsClient.getIndex` throwing `NotFoundException`, so the result is `None`.

These tests fail on the current code. The existing `RequiresAws` assertion `AwsAdaptersAwsSuite:63` covers the same behaviour live.

### Minor (listed for the human; no automatic repair)

| # | File:line | Failure input | Impact | Owner |
|---|---|---|---|---|
| m1 | `aws/NeptuneAdapters.scala:103` with `neptune/NeptuneModel.scala:41,62` | `CoauthorNeighbors(r, w, limit=10)` for a researcher with 50 coauthors on real Neptune | The query sends `LIMIT $limit`, so `rows.size > c.limit` is never true. `truncated` is always false on AWS, while the fake (`InMemoryGraph.scala:121`) reports true, so truncation is hidden at runtime. Proof test: the compiled parameters use `limit + 1`. | storage-engineer |
| m2 | `aws/AwsSupport.scala:46-48` with `Retry.scala:45-64` | A throttled PutVectors call | The SDK standard retry (up to 3 attempts) runs inside storage's own retry (up to 5 retries), allowing up to 18 HTTP attempts per batch. The retry count in RunLedger undercounts SDK-level attempts. | storage-engineer |
| m3 | `ingest/VectorIngestPipeline.scala:419-443` | Two writers race on one vector key with different text | The vector is overwritten first. The registry write then conflicts, and the registry holds the other writer's hash. Later runs skip by the registry, so the vector keeps the mismatched text. It is recorded as `registry-conflict`, so it is not silent, but it is not self-healing. | storage-engineer |

### Advisory

- **A1.** The deferred bounded AWS ingestion (at least 10,000 vectors) has no store wiring. `RagIngestJob` refuses non-local stores (`RagIngestJob.scala:99-112`), and storage has no AWS environment factory. This is needed before Phases 14/15 (rag proposed-later 1; owners storage-engineer and rag-engineer).
- **A2.** Neptune openCypher CSV typed columns are left blank for optional numerics (for example `averageCoauthorsPerPaper` and `participationCoefficient`), with `failOnError TRUE`. Blank handling, relationship-ID reload idempotency and `LIMIT $param` support were not verified against current docs (storage RISKS; the aws-docs MCP was unavailable). Verify before the first load.
- **A3.** "voyage (default)" holds at the configuration level only. Core deliberately has no code defaults (domain report), and the example voyage config sets it.
- **A4.** `.DS_Store` files are tracked and modified. `.gitignore` belongs to build-engineer; this is the human's call.
- **A5.** Run reports and agent reports contain absolute local paths with the OS username. They are not secrets.
- **A6.** `pathLength` in vector metadata is a graph-derived number. Phase 9 must take hop counts from Neptune or EvidenceBundle, never from metadata.
- **A7.** EvidenceBundle path items are the core EvidenceItem only. Per-hop edge IDs need a join (spark proposed-later 1).
- **A8.** A chunk-count shrink would leave stale `#n` vectors pointing to old text. Storage's cleanup procedure covers stale keys.
- **A9.** `services/assembly` merge rules for SDK resource files have not been checked (build-engineer proposed-later).
- **A10.** The dataset_snapshot nodeId is `scholarchain-graph:dblp-2026-11-01`, not the raw `dblp:2026-11-01`. This is reported by storage (assumption 3).

## Evidence checked (read directly)

- **Work order and approvals:** `docs/agent-runs/phase-07/prompt.md` (full), `docs/agent-runs/instructor-approvals.md`.
- **Agent reports:** all five, in the scratchpad `phase-07/`.
- **Build:** `build.sbt:80-139,230-339`, `project/build.properties:36-37`, `project/Ledger.scala:59,79`.
- **Core:** `SchemaVersions.scala`.
- **Storage main sources:**
  - `ingest/VectorIngestPipeline.scala`, `ingest/TextStore.scala`
  - `vectors/VectorMetadataCodec.scala`, `vectors/IndexSpec.scala`, `vectors/PutVectorsBatcher.scala`, `vectors/VectorKeys.scala`
  - `neptune/NeptuneCsvBuilder.scala`, `neptune/NeptuneModel.scala`
  - `dynamo/LedgerAndRegistry.scala`, `dynamo/TableSpecs.scala`, `dynamo/ItemSupport.scala`, `dynamo/DynamoLoader.scala:110-323`
  - `Retry.scala`, `s3/S3KeyLayout.scala`
  - `aws/AwsSupport.scala`, `aws/DynamoDbKeyValueStore.scala`, `aws/S3ObjectStore.scala`, `aws/S3VectorsIndex.scala`, `aws/SqsWorkQueue.scala`, `aws/NeptuneAdapters.scala`
  - `src/main/resources/s3vectors/index-spec-v1.json`
- **Storage tests:** `IngestPipelineSuite`, `VectorMetadataSuite`, `NeptuneCsvSuite`, `AwsAdaptersAwsSuite`; test names of `DynamoSuite`, `GraphQuerySuite`, `ObjectStoreSuite`, `WorkQueueSuite`, `ExportSourceSuite`, `VectorKeysSuite` and `LocalRealRunSuite`.
- **Rag:** `RagIngestJob.scala`, `ExportDocuments.scala`, `EmbeddingModelSpec.scala`, `EmbeddingProviders.scala`, `VoyageEmbedder.scala`, `SemanticContext.scala`, `SemanticRetriever.scala`, the header of `LiveProvidersAwsSuite`, `rag-voyage-example-v1.json`, and the test names of every rag suite.
- **Spark:** `JsonlExport.scala` (grep of the refusal, rename and run paths).
- **Run artifacts:**
  - `modules/spark/target/phase-07-export/run=51b50d578d5b609842c1c21b8266a00b/_export_manifest.json`: sourceRunId equals runId; totals of 65 tables and 12,571,066 records; 13 tables with `sourceColumnsNotExported`.
  - `modules/storage/target/phase-07-real/attempt-01/run-report.json`: rejects 0; DynamoDB pass 2 `dataChanges` 0; vector run 2 `skipRatio` 1; no `idsMatch: false`; no bucket or account literals.
  - `modules/rag/target/phase-08-real/attempt-02/run-report.json`: 251,094 documents; run 2 `skipRatio` 1; `renderDeterministic` true; status completed; only `example-artifact-bucket` URIs.
- **Greps over `modules/{storage,rag,core}/src`:** 12-digit numbers, ARNs, `s3://` literals, regions, deletion calls, `expiresAt` and TTL.

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: docs/agent-runs/phase-07/prompt.md, decision 9 (review of decisions 3-7), plus rulings (a)-(d) from the orchestrator
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools)
TEST RESULTS: not run by reviewer (no Bash). Agent-reported sbt check results were not re-observed; run-report JSONs were read and agree with the reports.
VALIDATION RESULTS: 12 of 13 gate rows pass (the bounded AWS ingestion is deferred by decision 1). "Loaders are idempotent" and "reviewer approves failure handling" fail on the AWS adapter path (M1). 1 major, 3 minor, 10 advisory.
ASSUMPTIONS: Ownership judged by path territory plus the reports' file lists and the session-start git status, because git history is not available to this reviewer. SDK exception hierarchy (ConditionalCheckFailedException, NoSuchKeyException and NotFoundException are SdkException subclasses) is from AWS SDK for Java 2.x knowledge, consistent with the imports used.
RISKS: AWS adapters are compile-only in this phase; other adapter defects can surface only in the deferred RequiresAws run. Neptune loader details (A2) are unverified.
MANUAL ACTION REQUIRED: Human decides the repair routing for M1 (storage-engineer) and reviews the minor and advisory lists.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: storage-engineer repair round for M1 (with the non-AWS stub-client tests described above and a fresh sbt check), then a code-reviewer re-check limited to M1, then the human gate.
```
