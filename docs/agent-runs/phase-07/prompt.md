# Phase 7 + 8 work order: storage adapters and loading, embeddings and S3 Vectors

This one session covers spec Phases 7 and 8 (merged to save time). All evidence goes in docs/agent-runs/phase-07/.

## Standing rules (same as Phase 6, with the exceptions marked NEW)

- **Routing:** hub and spoke; agents never call each other. Findings go to the owning agent, one repair round per finding. If a finding is still open after its round, or a second round would be needed, stop and ask me. A BLOCKED-ON goes to the named owner, then the blocked agent reruns in a fresh context. For an unowned path, stop with BLOCKED-ON factory-engineer.
- **Tests:** every source-changing agent runs `sbt check` before reporting COMPLETE (targeted tests while working are fine). sbt runs one at a time, never in parallel.
- **Commands** (I may be asleep; an approval prompt stalls the run):
  - Use only sbt, read-only git, and read-only shell commands (ls, cat, head, tail, wc, grep, du, df, shasum, sort, uniq, cut, `sed -n`, and find without -delete or -exec). No python, java, curl, installs, docker, aws or terraform.
  - Keep these words off command lines: rm, delete, drop, truncate, destroy, apply, force, reset, printenv, secret, credential, curl.
  - Run long jobs with the Bash tool's background option. Read input data only by passing paths to sbt.
  - If something would need my approval, don't retry variants: skip it if it's optional, or finish with BLOCKED-ON.
- **No AWS, no network.** NEW exception: sbt may download the new AWS SDK artifacts from Maven Central (decision 3). Nothing else goes to the network. No live call to AWS, Voyage or Anthropic.
- **NEW: live tests.** Every test that needs live AWS or a live external API (Voyage, Bedrock) is tagged `RequiresAws` (the existing MUnit tag; for this project it means "needs live cloud credentials"). Write and compile them; never run them. Never run `sbt awsTest`.
- **Scope:** build only what this work order lists. Anything else that seems useful goes on a "proposed later" list in the report, not into code. Run reporting is plain counts and timings in a run-report JSON; no statistics engine, no report generator.
- **Repair limits (speed):**
  - Only blocking or major findings get a repair round. Minor and advisory findings are listed for me, never repaired automatically.
  - After a repair, the owner adds a test that fails without the fix and reruns `sbt check`.
  - code-reviewer's re-check covers only the repaired findings. New minor findings found there are recorded, not repaired.
- **Secrets:** no API key, token, account ID or bucket name is written in code, config files, tests, logs or reports. Code reads a key only from an environment variable whose NAME comes from configuration. Tests use fakes.
- **Evidence:** write nothing under docs/agent-runs/ until all the work is done. Then:
  - print the summary first;
  - write each report verbatim to your scratchpad (not to docs/agent-runs/; the write there is blocked);
  - give me one `cp -n` command per file into docs/agent-runs/phase-07/, plus its sha256.

  File names: agent-report-build.md, agent-report-domain.md, agent-report-spark.md, agent-report-storage.md, agent-report-rag.md, review-report.md, repair-NN.md, review-report-02.md. Do not commit.

## Human architect decisions

### 1. Environment and what "done" means in this run

- Everything runs on this laptop. No AWS resources exist yet; Terraform comes in Phase 12. Do not create any cloud resource by any means.
- AWS adapters are written against the AWS SDK and covered by `RequiresAws` tests that are compiled, not run. Local behaviour is proven with in-memory or local-file fakes behind the same ports, and with local runs on the real Phase 6 data.
- Deferred to after the first deploy (Phase 14/15), recorded as such in the reports:
  - the bounded AWS storage integration suite;
  - the bounded AWS ingestion of at least 10,000 vectors;
  - the real-model retrieval smoke test, real throughput, real latency and real cost.
- OpenSearch is not implemented (optional tier). Say so in the storage report.

### 2. Agents and order

1. build-engineer: decision 3.
2. domain-engineer: decision 4.
3. spark-engineer: decision 5.
4. storage-engineer: decision 6 (all Phase 7 storage work and the Phase 8 storage work).
5. rag-engineer: decision 7 (the Phase 8 rag work).
6. code-reviewer: decision 9. APPROVE required.

**No test-engineer pass in this phase.** The Phase 7 and 8 gates don't require one. The spec's adversarial storage and vector cases (duplicate vector keys, stale model versions, DynamoDB conditional-write conflict, S3 Vectors throttling) belong to Phase 11. In this phase the owners write the spec's required tests, and code-reviewer judges whether they would catch a broken behaviour.

### 3. Build: AWS SDK (build-engineer)

- Add one ledger key `version.awssdk=2.59.1` to project/build.properties (checked by me on Maven Central on Nov 15: bom 2.59.1, and s3, dynamodb, s3vectors, neptunedata, sqs, bedrockruntime all exist at 2.59.1). Wire it through Ledger.scala like the other keys.
- `storage` gets: `software.amazon.awssdk` s3, dynamodb, s3vectors, neptunedata, sqs.
- `rag` gets: `software.amazon.awssdk` bedrockruntime.
- Use synchronous clients and one lightweight HTTP client implementation (your choice; prefer one that does not add Netty). No other dependency, no other build change.
- Report the resolved artifacts and any eviction warnings. Run `sbt check`.

### 4. Core contracts (domain-engineer, additive only)

Existing wire names, fields and ID formulas must not change.

a. **Graph labels** (spec "Research graph model"):
   - GraphNodeLabel: add `author_mention` (nodeId is a MentionId), `dataset_snapshot` (nodeId non-blank, no whitespace, e.g. `dblp:2026-11-01`), `text_chunk` (nodeId is a ChunkId, below).
   - GraphEdgeLabel: add `resolves_to` (author_mention -> researcher, directed), `derived_from` (work -> dataset_snapshot, directed), `supported_by` (researcher -> text_chunk, directed).
b. **ChunkId** stable ID (spec stable-ID principle "chunk or profile ID"): `chunk:v1:` + digest32("chunk/v1", documentType, subjectKey, windowLabel or "-", ordinal, textHash).
c. **SemanticDocumentType** enum: `researcher_profile`, `community_summary`, `collaboration_path`, `paper_summary`, `topic_institution_summary`.
d. **SemanticDocument** record, schema version 1: documentId (ChunkId), documentType, indexName, vectorKey, subjectIds, window (optional), text, sourceEvidenceId (optional), provenance. Rules: provenance.contentHash == ContentHash.of(text); vectorKey non-blank and at most 1024 UTF-8 bytes; indexName matches `[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]`.
e. **VectorMetadata** record, schema version 1 (the compact metadata stored with each vector; stored in core because storage writes it and rag reads it in Phase 9): vectorKey, indexName, documentId, documentType, subject fields (researcherId, communityId, startResearcherId, endResearcherId, workId, topicId, institutionId: all optional), windowStartYear, windowEndYear (optional), year, venueKey, pathLength (optional), sourceDataset, textS3Uri, textHash, embeddingModel, embeddingDimensions, runId, provenance. Rule: no field longer than 1024 characters (long text never goes in metadata).
f. **Configuration contract:** if `ScholarChainConfig` has no sections for these, add them as new optional sections (additive, no breaking change): S3 bucket and prefix names; DynamoDB table names; Neptune endpoint, port and query timeout; S3 Vectors bucket and the five index names; embedding provider (`voyage`, `bedrock` or `fake`), model ID, dimensions, the NAME of the environment variable holding the API key, batch sizes, retry limits and rate limits. No default values for bucket names, endpoints or account IDs.
g. Serialization, schema-version and validation tests for everything new. Nothing else changes in core. Run `sbt check`.

### 5. JSON Lines export of the Phase 6 run (spark-engineer)

- Input, read-only: `modules/spark/target/phase-06-real/attempt-03/run=51b50d578d5b609842c1c21b8266a00b/` (run `run:v1:51b50d578d5b609842c1c21b8266a00b`).
- Add an export entry point that reads every table listed in the four load manifests (`_load_manifests/{neptune,dynamodb,s3vectors,s3}.json`) and writes each one as JSON Lines: one `Contract.encode` record per line, validated on the way out.
- Output: `modules/spark/target/phase-07-export/run=51b50d578d5b609842c1c21b8266a00b/<same table path>/part-*.jsonl`, plus `_export_manifest.json` (source run ID, and per table: core record, schema version, window label, record count, files with bytes and sha256).
- It recomputes nothing and changes nothing in attempt-03. The run ID stays the same. If any exported count differs from the load manifest's count, the export fails.
- Run it locally through the test-scope runner (`sparkAnalytics/Test/runMain`, `local[6]`, about 32 shuffle partitions).
- In the report, also give, without reading anything private:
  - one sample line of each semantic-summary kind (researcher_profile, community, path), and the min, median and max text length of each kind;
  - the property keys present on `nodes:work` and `nodes:researcher` GraphNode rows.
- A small round-trip test of the exporter. Run `sbt check`.

### 6. Storage (storage-engineer): spec Phase 7 work order, plus the spec Phase 8 storage-engineer work

**Inputs:** the JSON Lines export from decision 5, read through a source abstraction (local directory now, S3 prefix later). Decode every line with `Contract.decode`; count and record rejects, never drop silently.

**Ports and adapters.** Ports for object storage, graph loading, read-only graph queries, DynamoDB tables, vector indexes, the embedding work queue and the text store. Each port has a real AWS SDK adapter (`RequiresAws` tests only) and an in-memory or local-file fake. The read-only graph port exposes only allowlisted, parameterized, bounded read templates (depth at most 6, result limit, timeout); runtime services will get only this port. IAM for read-only credentials is Phase 12 (infra-engineer).

**S3.** The key layout for the raw, curated, graph, semantic-context, reports and logs prefixes, with dataset, snapshot and run ID in the key, run manifests, and content hashes. No bucket names in code.

**Neptune.**
- Bulk-load files in the openCypher CSV format, gzip allowed. Node and edge IDs are the core IDs verbatim (nodeId, edgeId, MentionId, ChunkId).
- Nodes: researcher (full-window P, C, T, A, M, W, componentId, communityId, coreness, participation coefficient, identityOutcome, identityConfidence as properties; per-window metrics stay in DynamoDB), work, venue, institution and topic (0 rows; write empty files or skip, and report which), author_mention, dataset_snapshot, text_chunk.
- Edges:
  - authored, published_in;
  - coauthored_with for every window, from `edges:coauthored_with` joined with `coauthor_edge` on edgeId (same ID). Properties: windowLabel, windowStartYear, windowEndYear, weight, jointPublicationCount (= sharedWorkCount), firstCollaborationYear, lastCollaborationYear, minIdentityConfidence, supportingWorkIds (one `;`-joined string, because Neptune edge properties are single-valued), evidenceKey (= edgeId), runId;
  - resolves_to (from authorship: mention -> researcher, with matchConfidence);
  - derived_from (work -> dataset_snapshot);
  - supported_by (researcher -> text_chunk, for researcher-profile documents).
- A bulk-loader job request builder (format opencypher). Bulk loading is the only path for large loads. A parameterized MERGE upsert is allowed only for small, bounded writes (configurable row cap), because MERGE at scale is slow and expensive on Neptune.

**DynamoDB.**
- Tables RunLedger, EntityLinkCache, QuerySession, EvidenceBundle, ArtifactRegistry and ResearcherMetricCache, each with explicit partition and sort keys and a written access-pattern table.
- ResearcherMetricCache: one item per (researcherId, window) that merges the breadth, group-size, bridges and k-core metrics, component and community IDs, the M_r work IDs, identityConfidence and runId. Report the item size distribution and the number of items over 1 KB.
- EvidenceBundle is keyed by (evidenceId, window), because EvidenceId ignores the window. Load the 64 path evidence items there.
- Coauthor edges are NOT loaded into DynamoDB; Neptune and S3 hold them.
- Conditional writes for idempotency (put-if-absent, or a version check). RunLedger and ArtifactRegistry are append-only. TTL only on QuerySession.

**S3 Vectors.**
- The five indexes. Write an index spec file under `modules/storage/src/main/resources/` that Terraform will read in Phase 12. Per index: name, dimension (from config; 1024 for both providers), distance metric `cosine`, the filterable keys, and the non-filterable keys. Non-filterable keys are fixed when an index is created, so get them right: textS3Uri, textHash, documentId, sourceEvidenceId, runId and similar.
- Vector keys depend on the subject, never on the run, so unchanged text is skipped after a Spark rerun:
  - researcher profile `rp:<windowLabel>:<researcherId>`;
  - community `cm:<windowLabel>:<communityGroupId>`;
  - path `pa:<windowLabel>:<sourceResearcherId>:<targetResearcherId>`;
  - paper `pp:<workId>`.

  Derive them from the summary rows' subjectIds; document the rule.
- Metadata is a VectorMetadata record. Tests check: filterable metadata at most 2 KB, total metadata at most 40 KB, at most 50 keys, at most 10 non-filterable keys, and no document text in metadata.
- PutVectors batches of at most 500 vectors and under 20 MiB. Bounded retries with backoff on throttling and 5xx. Rejected vectors are recorded in a file and in RunLedger.

**Embedding ingest pipeline (spec Phase 8 storage work).**
- A manifest-driven work queue for local runs and an SQS adapter (compiled, `RequiresAws`).
- Embeddings come through an embedding port defined in storage (texts in, vectors out, plus model ID and dimensions). rag-engineer implements it; storage tests use a trivial stub.
- For each SemanticDocument:
  1. write the text to the text store (S3 key containing the text hash);
  2. look up the vector key in ArtifactRegistry;
  3. if it holds the same textHash, embeddingModel and dimensions, skip it;
  4. if the text or model differs, re-embed and overwrite, and record the old and new hash in RunLedger. Never overwrite silently;
  5. put the vector with its metadata.
- Failures and retry counts go to RunLedger.

**Local real-data runs** (under `modules/storage/target/phase-07-real/attempt-NN/`; every attempt uses a new root):
- Neptune CSV files from the full export: report rows, files and bytes per node and edge type.
- DynamoDB load plan: items per table, applied to the in-memory fake twice. The second pass makes 0 changes.
- Vector ingest of every semantic summary through the in-memory vector index with the stub embedder, run twice. The second run skips 100%.
- Report counts and timings in a run-report JSON. Stop and report if any step runs longer than 60 minutes or free disk drops below 15 GB.

**Tests:** every test in the spec's "Required tests" list, plus:
- Neptune IDs equal the exported core IDs;
- every coauthored_with edge carries its supporting work IDs and window;
- the same vector key with different text is never written silently;
- metadata size limits;
- conditional-write idempotency.

Run `sbt check`.

**Report:** everything the spec's Phase 7 report line asks for. Use the service limits in decision 8 and cite them as "captured by the human architect on Nov 15". Cleanup procedures are described for infra-engineer's `scripts/cleanup.sh`; no deletion code in loaders.

### 7. Rag (rag-engineer): spec Phase 8 rag-engineer work

**Rendering templates.**
- Researcher profiles, community summaries and collaboration paths: reuse Spark's summary text verbatim. Do not re-render, so one subject never has two texts. rag owns a template registry that records each Spark template (summary kind, graph policy version) and builds the SemanticDocument. A test checks that every document's text hash equals Spark's contentHash.
- `paper-abstract-v1`: abstracts are not collected. Render compact, deterministic paper summaries from dblp data only: title, venue, year and up to 10 author display names, ending with the sentence "No abstract is stored for this paper." Use the exported work nodes, published_in edges, authorship and canonical researchers. If the work nodes lack title, year or venue (see spark-engineer's report), leave this index empty, record it under "proposed later", and do not stop.
- `topic-institution-summary-v1`: no input yet (no enrichment data). Define it, create 0 documents, and say why.

**Chunking policy** (versioned): each summary is one chunk. Text over the model's input limit is split deterministically by paragraph with an ordinal. Abstracts: not applicable yet.

**Embedding model versioning.** An embedding model spec (provider, model ID, dimensions, input type, version string) is stored in every vector's metadata. Changing the model changes the spec, so every key is re-embedded and the index rebuilt (the professor's condition).

**Providers.** This is an instructor-approved fallback: this account's Bedrock on-demand quota for Titan Text Embeddings V2 (and for Claude) is 0 and not adjustable. The professor approved Voyage AI for embeddings and the Anthropic API for synthesis, with the Bedrock switch kept; the approval text is in `docs/agent-runs/instructor-approvals.md` (human-owned, read-only for agents). Its conditions apply:
- record the providers, exact model IDs and dimensions;
- if the embedding model changes, regenerate the stored embeddings and rebuild the affected index;
- count external API charges in the $25 total, reported per provider.

One config switch `embedding.provider`:
- `voyage` (default): model `voyage-4`, output dimension 1024, input type `document` for indexing and `query` for queries. Plain JDK `java.net.http` and uPickle, no new dependency. The API key comes from the environment variable named in config and is never logged. Batch size, retries (429 and 5xx, bounded) and rate limit come from config. Conservative defaults; exact Voyage request limits are verified before the first live run.
- `bedrock`: `amazon.titan-embed-text-v2:0`, 1024 dimensions, normalized, one text per call. Kept as the switch the professor asked for.
- `fake`: a deterministic lexical embedder (token feature hashing, L2-normalized, configurable dimensions) for local tests only. Never used for a stored index outside tests.

- Environment variable names (names only, never values): `SCHOLARCHAIN_VOYAGE_API_KEY` for Voyage; `SCHOLARCHAIN_ANTHROPIC_API_KEY` is reserved for Phase 9. Config stores these names.

  Live provider tests are `RequiresAws` (compiled, not run).

**Ingest job main.** Composes the renderer, the embedder and storage's pipeline. Run it locally on the full export with the `fake` provider and storage's fakes, twice. The second run skips 100%. Report document counts per index, rejects, duplicates, throughput and query latency, labelled "local fake, not representative".

**Retrieval smoke tests** with known expected neighbours, using the `fake` provider (lexical, labelled as not semantic): a query built from a profile's own distinctive words returns that profile first; the same for a community and a path. Also write the real-model smoke test (Voyage, `RequiresAws`) with its expected neighbours, for the AWS run later.

**Compact semantic-context format** for the Phase 9 synthesis agent: vector key, index, score, document type, subject IDs, window, text S3 pointer, text hash and embedding model. It is flagged as descriptive context that is never a source of counts, paths, components, communities or bridge scores.

No prompts or LLM calls in this phase. Run `sbt check`.

### 8. Service limits and costs (captured by me on Nov 15, 2026; the agents cite this table and use no network)

| Item | Value | Source |
|---|---|---|
| S3 Vectors dimension per vector | 1 to 4,096 | AWS docs, S3 Vectors "Limitations and restrictions" |
| S3 Vectors metadata per vector | total up to 40 KB; filterable up to 2 KB; up to 50 keys | same |
| S3 Vectors non-filterable keys | up to 10 per index; fixed at index creation | same |
| S3 Vectors PutVectors | up to 500 vectors per call; request payload up to 20 MiB | same |
| S3 Vectors GetVectors | up to 100 vectors per call | same |
| S3 Vectors write rate | up to 1,000 PutVectors+DeleteVectors requests/s and 2,500 vectors/s per index | same |
| S3 Vectors top-K per query | not verified (sources disagree); make it configurable, default 30 | — |
| S3 Vectors price (us-east-1 example) | $0.06 per GB-month storage, $0.20 per GB uploaded | AWS S3 pricing example |
| Neptune bulk loader | openCypher CSV format; edge properties are single-valued (no lists) | Neptune docs, load formats |
| Neptune plan | provisioned db.t4g.medium on the 30-day free trial (first-time user), created no earlier than Nov 20; bulk loader only | Neptune pricing page |
| DynamoDB on-demand writes | about $0.625 per million write units (1 KB each); some sources say $1.25 | pricing summaries |
| Voyage `voyage-4` | 1024 dimensions by default (2048/1024/512/256 allowed); 32,000-token input; $0.06 per million tokens; first 200M tokens free | Voyage pricing and model pages |
| Bedrock Titan Text Embeddings V2 | input up to 8,192 tokens or 50,000 characters; 256/512/1024 dimensions; this account's on-demand quota is 0 (not adjustable) | AWS docs; my Service Quotas check |

### 9. Review (code-reviewer, read-only)

Besides the usual order, rule on:
- the Phase 7 and Phase 8 spec gates below, one by one;
- long text never in vector metadata; metadata limits enforced by tests;
- Neptune IDs equal Spark IDs; coauthored_with edges carry their evidence;
- DynamoDB keys match the written access patterns; conditional writes; TTL only where disposable;
- loaders and ingest are idempotent (the second local runs changed nothing);
- the same vector key cannot silently point to different text; the embedding model is stored;
- no number, path, component, community or bridge score can come from vector text;
- no secret, key, account ID or bucket name in code, config, tests or reports;
- `RequiresAws` tests are excluded from `sbt check` and bounded (row caps, timeouts);
- tests would fail if the behaviour were broken;
- ownership: build, core, spark, storage and rag were each changed only by their owner.

### 10. Expected inputs (from the Phase 6 load manifests, not re-counted)

- 200,484 researchers, 155,978 works, 16 venues, 758,328 authorships.
- 3,361,084 coauthored_with edges across the 5 windows, 758,314 authored and 155,978 published_in edges.
- 503,448 (researcher, window) metric rows.
- Semantic summaries: 64,090 researcher profiles, 30,962 community summaries, 64 path summaries.

Report what you actually find. If a count differs, report it; don't force it.

## Spec work order, Phase 7 (verbatim)

```text
Use the storage-engineer agent to implement ports, adapters, loaders, and contract tests for the ScholarChain storage layer.

Required adapters:

1. Ordinary S3
- Raw, curated, graph, semantic-context, reports, and logs prefixes.
- Versioned object keys and run manifests.
- Content hashes and source references.

2. Neptune
- Bulk loader manifests for Researcher, Work, Venue, Institution, Topic, and selected context nodes.
- Load AUTHORED, COAUTHORED_WITH, PUBLISHED_IN, AFFILIATED_WITH, HAS_TOPIC, RESOLVES_TO, DERIVED_FROM, and SUPPORTED_BY relationships as required by the implemented graph model.
- Store coauthor-edge properties including jointPublicationCount, firstCollaborationYear, lastCollaborationYear, selected time window, and evidence pointer.
- Store researcher collaboration metrics needed for bounded runtime lookup when appropriate.
- Use idempotent identifiers and parameterized openCypher queries.
- Give runtime services read-only graph credentials.

3. DynamoDB
- RunLedger table.
- EntityLinkCache table.
- QuerySession table.
- EvidenceBundle table.
- ArtifactRegistry table.
- Optional ResearcherMetricCache table when justified by access patterns.
- Explicit partition and sort keys.
- Conditional writes for idempotency.
- TTL only where data is genuinely disposable.

4. S3 Vectors
- Create and query vector-bucket and index abstractions.
- Store embeddings with stable vector keys and compact filterable metadata.
- Store long text or generated profiles in ordinary S3 and keep only references in vector metadata.
- Support metadata filters for researcher, year window, source, topic, institution, community, and document type when permitted by service limits.
- Batch writes, retry transient failures, and record rejected vectors.

5. Optional OpenSearch
- Only when the team implements hybrid lexical and vector retrieval or a measured hot tier.
- Define synchronization from durable S3 and S3 Vectors data.
- Do not make OpenSearch an undocumented second source of truth.

Required vector indexes:
- researcher-profile-v1
- collaboration-community-v1
- paper-abstract-v1
- collaboration-path-v1
- topic-institution-summary-v1

Required tests:
- Serialization and deserialization.
- Idempotent reload.
- Missing and malformed metadata.
- Duplicate vector keys.
- Neptune coauthor-edge endpoint and evidence validation.
- DynamoDB conditional-write behavior.
- Local fakes or test containers where possible.
- Bounded AWS integration tests behind an explicit profile.

Run sbt check and the bounded storage integration suite.
Report schemas, keys, indexes, metadata fields, batch sizes, retry policy, idempotency strategy, service limits captured from current documentation, and cleanup procedures.
```

## Spec work order, Phase 8 (verbatim)

```text
Use the storage-engineer and rag-engineer in separate delegated work orders to build the embedding and S3 Vectors ingestion path.

Storage-engineer work:
- Implement a bounded embedding work queue using SQS or a manifest-driven batch design.
- Implement vector write batches through the S3 Vectors API.
- Store authoritative source text and generated profile documents in ordinary S3.
- Store vector key, researcher or community ID, relevant time window, topic or institution IDs, document type, source dataset, model version, text hash, and run ID as compact metadata.
- Detect and skip unchanged text by content hash and embedding model version.
- Record failures and retry counts in RunLedger.

Rag-engineer work:
- Define deterministic rendering templates for researcher profiles, community profiles, collaboration paths, and representative-paper summaries.
- Define chunking policies for permitted abstracts or longer text when used.
- Define embedding model versioning.
- Create retrieval smoke tests with known expected researcher, topic, or community neighbors.
- Create a compact semantic-context format for the synthesis agent.

Required indexes:
- researcher-profile-v1
- collaboration-community-v1
- paper-abstract-v1
- collaboration-path-v1
- topic-institution-summary-v1

Run the local contract tests and a bounded AWS ingestion of at least 10,000 vectors or an instructor-approved smaller corpus when the selected dataset subset does not produce 10,000 meaningful units.
Report throughput, rejected records, duplicate handling, query latency samples, index counts, metadata schema, and embedding model version.
```

## Spec gates (checked at my gate)

Phase 7:

- Long text is not stuffed into vector metadata.
- Neptune graph identifiers match Spark output identifiers.
- COAUTHORED_WITH edges can be traced to supporting work evidence.
- DynamoDB keys support stated access patterns.
- Loaders are idempotent.
- AWS integration tests are bounded and separately enabled.
- The reviewer approves schema and failure-handling behavior.

Phase 8:

- The same vector key cannot silently refer to different text.
- The embedding model version is stored.
- Source text or generated profile content can be fetched from the vector result through its ordinary S3 pointer.
- Retrieval smoke tests pass.
- Exact counts, shortest paths, components, and bridge metrics come from graph or metric stores, not vector similarity.
- Cost and object counts are reported.
