STATUS: COMPLETE
OWNER: domain-engineer
WORK ORDER: docs/agent-runs/phase-07/prompt.md, "Human architect decisions -> 4. Core contracts (domain-engineer, additive only)", items a to g. Decisions 5 to 7 were read only to make the contracts fit how spark, storage and rag will use them.

FILES CREATED:
- /Users/brian/Developer/repos/scholarchain/modules/core/src/test/scala/org/scholarchain/core/SemanticContractSuite.scala
- /Users/brian/Developer/repos/scholarchain/modules/core/src/test/resources/wire/v1/SemanticDocument.json (frozen wire example)
- /Users/brian/Developer/repos/scholarchain/modules/core/src/test/resources/wire/v1/VectorMetadata.json (frozen wire example)
- /Users/brian/Developer/repos/scholarchain/modules/core/src/test/resources/config/valid-v1-sections.json (test config with every new section; placeholder values only)

FILES MODIFIED:
- /Users/brian/Developer/repos/scholarchain/modules/core/src/main/scala/org/scholarchain/core/Enums.scala (3 node labels, 3 edge labels, new enum SemanticDocumentType)
- /Users/brian/Developer/repos/scholarchain/modules/core/src/main/scala/org/scholarchain/core/StableIds.scala (ChunkId)
- /Users/brian/Developer/repos/scholarchain/modules/core/src/main/scala/org/scholarchain/core/Records.scala (VectorIndexName, VectorKey, SemanticDocument, VectorMetadata)
- /Users/brian/Developer/repos/scholarchain/modules/core/src/main/scala/org/scholarchain/core/Validator.scala (nodeId rules for the new labels; validators for the 2 new records)
- /Users/brian/Developer/repos/scholarchain/modules/core/src/main/scala/org/scholarchain/core/SchemaVersions.scala (SemanticDocument 1, VectorMetadata 1)
- /Users/brian/Developer/repos/scholarchain/modules/core/src/main/scala/org/scholarchain/core/config/ScholarChainConfig.scala (5 optional sections, S3Location, EmbeddingProvider)
- /Users/brian/Developer/repos/scholarchain/modules/core/src/main/scala/org/scholarchain/core/config/ConfigLoader.scala (ConfigSection, ConfigLoader.sections and allKeys, section resolution, section validation)
- /Users/brian/Developer/repos/scholarchain/modules/core/src/test/scala/org/scholarchain/core/Fixtures.scala, SchemaVersionsSuite.scala, WireContractSuite.scala, config/ConfigLoaderSuite.scala
No file outside modules/core/** was changed by me. `sbt fmt` reformatted only the two core files I had appended through the shell.

COMMANDS RUN (all sbt runs in the background, one at a time):
- Read-only inspection: cat, sed -n, grep, ls, head, tail, git status, git diff.
- `printf '%s' '<canonical encoding>' | shasum -a 256 | cut -c1-32`: computed the ChunkId golden values independently.
- `sbt -batch "core/Test/compile; core2_13/Test/compile"`: exit 0. Both Scala 3 and Scala 2.13 compiled with no warnings.
- `sbt -batch "core/testOnly org.scholarchain.core.SemanticContractSuite"`: a one-time probe that printed the fixture JSON. That file was then overwritten with the real suite.
- `sbt -batch "core/test; core2_13/test"`: exit 0. "Passed: Total 507, Failed 0" for each of core and core2_13.
- `sbt -batch check` (first run): FAILED at scalafmtCheck. StableIds.scala and Records.scala were not formatted.
- `sbt -batch fmt`: exit 0. It reformatted those 2 files.
- `sbt -batch check` (second run): exit 0. "[success] Total time: 1026 s".

TEST RESULTS:
- Final `sbt check` output: "Module tests: suites=53 executed=1280 passed=1280 failed=0 errors=0 skipped=0" and "Updated .claude/.last-test-run (module_tests=1280, contract_tests=336)". No suite reported a failure (75 "finished: 0 failed" lines, 0 non-zero ones).
- New or extended core suites, run under Scala 3 and 2.13: SemanticContractSuite has 29 tests. ConfigLoaderSuite grew to 29 tests (12 new). WireContractSuite has 279 tests; it now covers SemanticDocument and VectorMetadata in both directions, plus rejection of unsupported, missing and null schemaVersion/provenance. SchemaVersionsSuite freezes the new version table, the new wire names and the edge endpoints.
- The required rejection cases are all covered and pass:
  - bad indexName;
  - vectorKey of 1025 ASCII bytes, and 342 euro signs (342 characters but 1026 UTF-8 bytes);
  - contentHash that does not match the text, through validate and through decode;
  - metadata field over 1024 characters (top-level fields, provenance.jobName, provenance.sourceRecordIds[0], and venueKey on decode);
  - malformed ChunkId, and malformed author_mention and text_chunk node IDs;
  - dataset_snapshot node IDs containing whitespace;
  - wrong endpoints and wrong edge IDs on the new edge labels.

VALIDATION RESULTS:
- Additive only. No existing wire name, field, ID formula or schema version changed. The only removed lines in the main sources are lines that were replaced by longer versions (lists of values, imports, the resolve and build functions in the loader).
- A config without the new sections serializes with exactly the 8 old top-level keys (tested), so `ScholarChainConfig.fingerprint` is unchanged for existing configs. Nothing outside core uses ScholarChainConfig.
- core still depends only on uPickle and MUnit. No AWS, Hadoop, Spark or HTTP dependency.
- No bucket name, endpoint, account ID or API key has a default value. Test files use only `example-*` and `.invalid` placeholders and the existing fixture account ID `123456789012`.

ASSUMPTIONS:
1. To avoid two sources of truth I reused existing config keys instead of repeating them: S3 Vectors bucket is `storage.vectorBucket`; Neptune endpoint is `storage.neptuneEndpoint`; embedding model ID is `models.embeddingModelId`; raw, curated and graph buckets are `storage.rawBucket`, `storage.curatedBucket`, `storage.graphBucket`.
2. Two config keys go beyond the literal list in item f, because the human made them "configurable" in decisions 6 and 8:
   - `neptune.mergeRowCap` (the "configurable row cap" in decision 6);
   - `vectors.queryTopK` (decision 8: "configurable, default 30"). The 30 lives in the config file, not in code.

   For the same reason I read "batch sizes, retry limits" as covering S3 Vectors as well as embedding.
3. I added `sourceEvidenceId: Option[EvidenceId]` to VectorMetadata. Item e does not list it, but decision 6 names it as a non-filterable metadata key.
4. In VectorMetadata, `year` and `venueKey` are optional (`Option`), because profiles, communities and paths have neither.
5. I added type-specific rules to both new records (required subject fields, required windows for the three graph types). They follow the existing EvidenceType.requiresWindow pattern.
6. VectorMetadata.runId must equal provenance.runId: the run that wrote the vector.
7. "1024 characters" is measured as `String.length`. That counts UTF-16 code units, so it is never looser than counting code points.

RISKS:
- SemanticDocument does not carry the window label or the ordinal, so the validator only checks that documentId is well formed. It does not recompute the ChunkId formula. Producer (rag) tests must assert `documentId == ChunkId.forSubjects(...)`. Proposed later item 1 would make this verifiable.
- The new GraphEdge labels go through the existing EdgeId formula, which needs a window. Spark and storage must choose one for resolves_to, derived_from and supported_by edges (suggested: the full graph window). This is not enforced.
- Spark's `summaryKind` values (`researcher_profile`, `community`, `path`) differ from the SemanticDocumentType wire names (`researcher_profile`, `community_summary`, `collaboration_path`). rag must map them explicitly.
- A Spark community summary's subjectIds is the group ID followed by the top members, and the group ID can repeat. SemanticDocument therefore does not require distinct subjectIds.
- Any code outside core that matches exhaustively on GraphNodeLabel or GraphEdgeLabel would get non-exhaustive warnings. grep found no such match today.

MANUAL ACTION REQUIRED: None. Nothing was committed. Nothing was written under docs/agent-runs/.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Review of core contracts by code-reviewer (decision 9). spark-engineer (decision 5), storage-engineer (decision 6) and rag-engineer (decision 7) consume the section below.

## CONTRACTS ADDED

Schema versions: SemanticDocument = 1 (new) and VectorMetadata = 1 (new), appended to `SchemaVersions.current` after `ScholarChainConfig -> 1`. All other versions are unchanged (Provenance 1, GraphNode 1, GraphEdge 1, ScholarChainConfig 1, ...). No breaking change, no version bump.

### a. Graph labels (package org.scholarchain.core, Enums.scala)

GraphNodeLabel, appended. `values` order is researcher, work, venue, institution, topic, author_mention, dataset_snapshot, text_chunk.

| Scala | wire | nodeId rule (GraphNode validator, also GraphEdge endpoints) | error text |
|---|---|---|---|
| `GraphNodeLabel.AuthorMention` | `author_mention` | `MentionId.isWellFormed` | `<field>: '<id>' is not a mention ID` |
| `GraphNodeLabel.DatasetSnapshot` | `dataset_snapshot` | matches `\S+` (e.g. `dblp:2026-11-01`) | `<field>: '<id>' must be non-empty, no whitespace` |
| `GraphNodeLabel.TextChunk` | `text_chunk` | `ChunkId.isWellFormed` | `<field>: '<id>' is not a chunk ID` |

GraphEdgeLabel, appended. All are directed (`undirected = false`). `values` order is coauthored_with, authored, published_in, affiliated_with, has_topic, resolves_to, derived_from, supported_by.

| Scala | wire | fromLabel -> toLabel |
|---|---|---|
| `GraphEdgeLabel.ResolvesTo` | `resolves_to` | author_mention -> researcher |
| `GraphEdgeLabel.DerivedFrom` | `derived_from` | work -> dataset_snapshot |
| `GraphEdgeLabel.SupportedBy` | `supported_by` | researcher -> text_chunk |

The edge ID is the existing formula, unchanged: `EdgeId.of(label, from, to, window)` = `edge:v1:` + digest32("edge/v1", label.wire, from, to, startYear, endYear). For these directed labels the endpoints are not reordered.

### b. ChunkId (StableIds.scala)

`final case class ChunkId(value: String) extends StableId`. Companion kind is `chunk`. Format is `chunk:v1:<32 lowercase hex>`. On the wire it is a JSON string; a malformed value fails decode with `malformed chunk ID '<v>' ...`.

Formula: `chunk:v1:` + digest32("chunk/v1", documentType.wire, subjectKey, windowLabel or "-", ordinal decimal, textHash). digest32 is the existing length-prefixed SHA-256 truncated to 32 hex characters.

API:
- `ChunkId.of(documentType: SemanticDocumentType, subjectKey: String, windowLabel: Option[String], ordinal: Int, textHash: String): Either[String, ChunkId]`. Returns Left when:
  - subjectKey is blank;
  - windowLabel is Some but does not match `[a-z0-9][a-z0-9-]*` (so `-` is impossible);
  - ordinal < 0;
  - textHash is not `sha256:<64 lowercase hex>`.
- `ChunkId.subjectKeyOf(subjectIds: Seq[String]): Either[String, String]` = the IDs joined by `,` in their stated order. Left when the list is empty or an ID is blank or contains `,`.
- `ChunkId.forSubjects(documentType, subjectIds, windowLabel, ordinal, textHash)` = `of(documentType, subjectKeyOf(subjectIds), ...)`. Producers use this, with textHash = `ContentHash.of(chunk text)` and windowLabel = the configured graph window label (e.g. `full-2015-2025`).
- `ChunkId.NoWindow = "-"`.

Golden values (checked independently with shasum):
- `of(researcher_profile, "researcher:v1:6d39fb31017461bdf6bbe118864d1809", Some("2015-2025"), 0, "sha256:ab554d00fa490bb4c222d4e70aad364bd6defba0d7722e950fc73a356c594bed")` = `chunk:v1:794b0bd5791f0c269f919eae558f2cef`
- same subject key and hash with `paper_summary`, None, 3 = `chunk:v1:648f18f4129a967fa69d7eda636b5c01`

### c. SemanticDocumentType (Enums.scala)

`sealed trait SemanticDocumentType extends WireEnum`. Unknown names are rejected with `unknown semantic document type '<x>'`.

| Scala | wire |
|---|---|
| `SemanticDocumentType.ResearcherProfile` | `researcher_profile` |
| `SemanticDocumentType.CommunitySummary` | `community_summary` |
| `SemanticDocumentType.CollaborationPath` | `collaboration_path` |
| `SemanticDocumentType.PaperSummary` | `paper_summary` |
| `SemanticDocumentType.TopicInstitutionSummary` | `topic_institution_summary` |

`SemanticDocumentType.requiresWindow` = {researcher_profile, community_summary, collaboration_path}.

Spark summaryKind mapping for rag: researcher_profile -> researcher_profile; community -> community_summary; path -> collaboration_path.

### Helpers (Records.scala)
- `VectorIndexName.Pattern = "[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]"` (3 to 63 characters); `VectorIndexName.isValid(s)`.
- `VectorKey.MaxUtf8Bytes = 1024`; `VectorKey.utf8Length(s)`; `VectorKey.isValid(s)` (non-blank and at most 1024 UTF-8 bytes).
- `VectorMetadata.MaxFieldChars = 1024`, `VectorMetadata.MinDimensions = 1`, `VectorMetadata.MaxDimensions = 4096`.

### d. SemanticDocument, schema version 1 (wire: frozen wire/v1/SemanticDocument.json)

Fields, in wire order:
`schemaVersion: Int, documentId: ChunkId, documentType: SemanticDocumentType, indexName: String, vectorKey: String, subjectIds: List[String], window: Option[YearWindow], text: String, sourceEvidenceId: Option[EvidenceId], provenance: Provenance`

Option fields are written as JSON null and must be present.

Validation (Validator.semanticDocument; Contract.encode and Contract.decode both apply it):
- schemaVersion is supported; documentId is a well-formed ChunkId (the formula is the producer's obligation, see Risks).
- `indexName` matches the pattern. Error: `indexName: '<v>' must match [a-z0-9][a-z0-9.-]{1,61}[a-z0-9]`.
- `vectorKey` is non-blank and at most 1024 UTF-8 bytes. Error: `vectorKey: <n> UTF-8 bytes exceeds 1024`.
- `subjectIds` is non-empty and every entry is non-blank. By type:

  | documentType | subjectIds rule |
  |---|---|
  | researcher_profile | exactly 1 ResearcherId |
  | community_summary | all ResearcherIds; the first is the group ID; duplicates allowed |
  | collaboration_path | at least 2 ResearcherIds, in path order |
  | paper_summary | the first is a WorkId |
  | topic_institution_summary | no further constraint |

- `window` is required for the requiresWindow types. When present it must be valid (start <= end, years in 1..9999).
- `text` is non-blank, and `provenance.contentHash == Some(ContentHash.of(text))`. Errors: `provenance.contentHash: does not match text`, or `...: required because the record carries text`.
- `sourceEvidenceId`, when present, is a well-formed EvidenceId (for Spark summaries: the summary's EvidenceItem ID).
- `provenance` is required and valid.

### e. VectorMetadata, schema version 1 (wire: frozen wire/v1/VectorMetadata.json)

Fields, in wire order:
`schemaVersion: Int, vectorKey: String, indexName: String, documentId: ChunkId, documentType: SemanticDocumentType, researcherId: Option[ResearcherId], communityId: Option[ResearcherId], startResearcherId: Option[ResearcherId], endResearcherId: Option[ResearcherId], workId: Option[WorkId], topicId: Option[String], institutionId: Option[String], windowStartYear: Option[Int], windowEndYear: Option[Int], year: Option[Int], venueKey: Option[String], pathLength: Option[Int], sourceDataset: String, textS3Uri: String, textHash: String, sourceEvidenceId: Option[EvidenceId], embeddingModel: String, embeddingDimensions: Int, runId: RunId, provenance: Provenance`

The record has no text field.

Validation (Validator.vectorMetadata):
- No string value anywhere in the record is longer than 1024 characters (String.length). This includes stable IDs and every provenance string and list element. Error: `<path>: <n> characters exceeds the 1024-character limit` (for example `provenance.sourceRecordIds[0]: ...`).
- vectorKey and indexName follow the SemanticDocument rules. documentId and every present ID are well formed for their kind.
- `topicId` matches `\S+`. `institutionId` is a canonical OpenAlex institution ID.
- Required fields by type (error: `<field>: required for <type> vectors`):

  | documentType | required fields |
  |---|---|
  | researcher_profile | researcherId |
  | community_summary | communityId (the community group ResearcherId) |
  | collaboration_path | startResearcherId, endResearcherId (must differ), pathLength |
  | paper_summary | workId |
  | topic_institution_summary | topicId or institutionId |

- windowStartYear and windowEndYear are both present or both absent, form a valid window, and are required for the requiresWindow types.
- `year` is in 1..9999. `venueKey` is a dblp venue key. `pathLength` (coauthor edges on the path) is >= 1.
- `sourceDataset` matches `[a-z0-9][a-z0-9-]*`. `textS3Uri` matches `s3://[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]/\S+`.
- `textHash` is `sha256:<64 lowercase hex>` and equals `provenance.contentHash`. Error: `provenance.contentHash: must equal textHash`.
- `embeddingModel` matches `\S+`. `embeddingDimensions` is in 1..4096.
- `runId == provenance.runId`.
- Service limits that depend on the index (filterable at most 2 KB, total at most 40 KB, at most 50 keys, at most 10 non-filterable keys) are not checked here. storage checks them.

### f. Configuration (package org.scholarchain.core.config)

New fields on `ScholarChainConfig`, each defaulting to None (omitted from the JSON and the fingerprint when None):
`objectStore: Option[ObjectStoreSettings], dynamoDb: Option[DynamoDbSettings], neptune: Option[NeptuneSettings], vectors: Option[VectorSettings], embedding: Option[EmbeddingSettings]`

Plus `def s3Zones: Option[ListMap[String, S3Location]]`.

Loader rules (`ConfigLoader.sections: List[ConfigSection]`, `ConfigLoader.allKeys`):
- A section resolves to None when none of its keys is set in the file or the environment.
- If any key of a section is set, every required key of that section must be set. Error: `<path>: required because section <name> is configured; set it in <file> or in <ENV>`.
- No key has a default value.
- Precedence per key: `SCHOLARCHAIN_CFG_<UPPER_SNAKE path>` environment variable, then the file (named by `SCHOLARCHAIN_CONFIG_FILE`).
- Unknown keys and unknown variables are rejected.

Keys, with env var names derived by the existing rule (e.g. `embedding.apiKeyEnvVar` -> `SCHOLARCHAIN_CFG_EMBEDDING_API_KEY_ENV_VAR`):

- objectStore (text, all required): `rawPrefix, curatedPrefix, graphPrefix, semanticContextPrefix, reportsPrefix, logsPrefix, semanticContextBucket, reportsBucket, logsBucket`.
  - Prefixes match `[a-z0-9][a-z0-9._=-]*(/[a-z0-9][a-z0-9._=-]*)*` (no leading or trailing `/`).
  - Buckets match `[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]`.
  - `ObjectStoreSettings.zones(storage)` returns a ListMap in this order: raw -> (storage.rawBucket, rawPrefix), curated -> (storage.curatedBucket, curatedPrefix), graph -> (storage.graphBucket, graphPrefix), semantic-context -> (semanticContextBucket, semanticContextPrefix), reports -> (reportsBucket, reportsPrefix), logs -> (logsBucket, logsPrefix).
  - The six (bucket, prefix) pairs must be distinct.
  - `S3Location(bucket, prefix).uri` = `s3://<bucket>/<prefix>/`.
- dynamoDb (text, all required): `runLedgerTable, entityLinkCacheTable, querySessionTable, evidenceBundleTable, artifactRegistryTable, researcherMetricCacheTable`. Each matches `[A-Za-z0-9_.-]{3,255}`; all distinct.
- neptune (integer, all required): `port` (1..65535), `queryTimeoutMillis` (> 0), `mergeRowCap` (> 0). The host is `storage.neptuneEndpoint`.
- vectors (all required):
  - text: `researcherProfileIndex, collaborationCommunityIndex, paperAbstractIndex, collaborationPathIndex, topicInstitutionSummaryIndex`. Each matches VectorIndexName.Pattern; all distinct.
  - integer: `putBatchSize` (1..500, `ConfigValidator.MaxPutVectorsBatch`), `maxRetries` (>= 0), `retryBaseDelayMillis` (> 0), `retryMaxDelayMillis` (>= base), `queryTopK` (> 0).
  - The bucket is `storage.vectorBucket`.
  - `VectorSettings.indexFor(t)`: researcher_profile -> researcherProfileIndex, community_summary -> collaborationCommunityIndex, collaboration_path -> collaborationPathIndex, paper_summary -> paperAbstractIndex, topic_institution_summary -> topicInstitutionSummaryIndex.
  - `indexNames` lists the five names in SemanticDocumentType.values order.
- embedding:
  - required: `provider` (text: `voyage`, `bedrock` or `fake`), `dimensions` (1..4096), `batchSize` (> 0), `maxRetries` (>= 0), `retryBaseDelayMillis` (> 0), `retryMaxDelayMillis` (>= base), `requestsPerMinute` (> 0), `tokensPerMinute` (> 0).
  - optional: `apiKeyEnvVar` (text). This is the NAME of the environment variable, never its value. It must match `[A-Z][A-Z0-9_]*`, must not start with `SCHOLARCHAIN_CFG_`, and is required when provider = `voyage`. The loader never reads that variable (tested).
  - The model ID is `models.embeddingModelId`.
  - Scala: `EmbeddingSettings.provider: String` and `providerKind: Either[String, EmbeddingProvider]`. `EmbeddingProvider` is a WireEnum with Voyage `voyage`, Bedrock `bedrock`, Fake `fake`.

The test config `valid-v1-sections.json` uses the index names researcher-profile-v1, collaboration-community-v1, paper-abstract-v1, collaboration-path-v1, topic-institution-summary-v1; `apiKeyEnvVar` `SCHOLARCHAIN_VOYAGE_API_KEY`; dimensions 1024; putBatchSize 500; queryTopK 30. These values come from the test file, not from code defaults.

### g. Tests
- SemanticContractSuite: ChunkId golden values, input sensitivity, rejections and parsing; the 3 node and 3 edge labels (valid, invalid, round trip, unknown label); every SemanticDocument and VectorMetadata rule above, both direct and through decode; frozen field order.
- WireContractSuite: frozen examples for both records, both directions, plus the generic schemaVersion and provenance rejections.
- SchemaVersionsSuite: frozen version table, wire names, edge endpoints, requiresWindow, provider names.
- ConfigLoaderSuite: sections absent means None and an unchanged serialization; full load; partial-section errors; activation from the environment; precedence; the voyage key-name rule; every range, pattern and distinctness check; env var names unique (37 new keys).

## PROPOSED LATER
1. SemanticDocument v2: add `windowLabel: Option[String]` and `ordinal: Int` (and optionally `subjectKey`) so the validator can recompute the ChunkId formula. This is a breaking change (v2).
2. Add `embedding.maxInputTokens` (or maxInputChars) to config for rag's chunking split threshold, and embedding input-type keys (`document` / `query`) if they should be configurable rather than protocol constants.
3. Add config keys for the read-only graph query port: `neptune.maxDepth` (at most 6) and `neptune.resultLimit`. Decision 6 bounds these; storage may hold them in its own config until then.
4. A core helper for S3 Vectors metadata size accounting (filterable bytes and key counts per index spec), if storage and rag both need it in Phase 9.
5. A typed `EmbeddingSettings.provider: EmbeddingProvider` (needs a new ValueKind in the loader). It is a String today, checked by the validator.
6. A rule that `fake` is rejected outside local or test environments (would need an agreed environment-name convention).
