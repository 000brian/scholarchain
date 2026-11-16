STATUS: COMPLETE
OWNER: storage-engineer
WORK ORDER: docs/agent-runs/phase-09/prompt.md, decision 4 (4a to 4j). That covers Phase 7+8 fixes A1, m1, m2 and A2, the path search, WorkById, the RM4 ranking cache, the typed runtime readers and writers, and LocalRuntimeEnvironment. I changed nothing outside modules/storage/**.

FILES CREATED:
- /Users/brian/Developer/repos/scholarchain/modules/storage/src/main/scala/org/scholarchain/storage/aws/AwsStorageEnvironment.scala (AwsSdkClients, AwsStorageEnvironment)
- /Users/brian/Developer/repos/scholarchain/modules/storage/src/main/scala/org/scholarchain/storage/RetryingStores.scala (RetryingKeyValueStore, RetryingObjectStore, RetryingVectorIndex, RetryCounters)
- /Users/brian/Developer/repos/scholarchain/modules/storage/src/main/scala/org/scholarchain/storage/dynamo/Rankings.scala (RankingMetrics, RankingOrder, RankingAccumulator, RankingItems)
- /Users/brian/Developer/repos/scholarchain/modules/storage/src/main/scala/org/scholarchain/storage/runtime/RuntimeReaders.scala (MetricCacheReader, EntityLinkReader, EvidenceStore, QueryAuditWriter and their result types)
- /Users/brian/Developer/repos/scholarchain/modules/storage/src/main/scala/org/scholarchain/storage/runtime/RuntimeStores.scala
- /Users/brian/Developer/repos/scholarchain/modules/storage/src/main/scala/org/scholarchain/storage/local/LocalRuntimeEnvironment.scala
- /Users/brian/Developer/repos/scholarchain/modules/storage/src/test/scala/org/scholarchain/storage/AwsEnvironmentSuite.scala
- /Users/brian/Developer/repos/scholarchain/modules/storage/src/test/scala/org/scholarchain/storage/RuntimeReadersSuite.scala
- /Users/brian/Developer/repos/scholarchain/modules/storage/src/test/scala/org/scholarchain/storage/LocalRuntimeEnvironmentSuite.scala

FILES MODIFIED (all under /Users/brian/Developer/repos/scholarchain/modules/storage/src/):
- main/.../storage/StorageConfig.scala: new `runtime: Option[RuntimeSettings]` (default None), `windowLabels`, `requireRuntime`.
- main/.../storage/aws/AwsSupport.scala: `maxAttempts` must be 1 (`AwsClientSettings.SdkMaxAttempts`).
- main/.../storage/aws/NeptuneAdapters.scala: the bulk loader sends `userProvidedEdgeIds`; NeptuneGraphRead now does m1, iterative-deepening path search and the timeout handling.
- main/.../storage/ports/GraphPorts.scala: GraphReadSettings, GraphQuery.WorkById, `validate(q, settings)`, the GraphResult `complete` and `depthSearched` fields, the BulkLoadJob change, `GraphReadPort.settings`.
- main/.../storage/neptune/NeptuneModel.scala: CypherTemplates rewritten.
- main/.../storage/neptune/BulkLoadRequests.scala
- main/.../storage/local/InMemoryGraph.scala: GraphReadSettings, CsvRowFilter, WorkById, breadth-first search with the same result fields, value interning.
- main/.../storage/dynamo/DynamoLoader.scala: new `rankingTopK: Option[Int]` parameter, RM4 items.
- main/.../storage/dynamo/ItemBuilders.scala: `familyProvenance` on MetricCacheAccumulator.
- main/.../storage/dynamo/TableSpecs.scala: RM4 and key helpers.
- main/.../storage/dynamo/LedgerAndRegistry.scala: `RunLedger.lastKey`.
- test/.../AwsAdaptersAwsSuite.scala (RequiresAws only: maxAttempts 1, new NeptuneGraphRead constructor, a new AwsStorageEnvironment smoke test)
- test/.../DynamoSuite.scala, GraphQuerySuite.scala, NeptuneCsvSuite.scala, TestFixtures.scala, LocalRealRun.scala

COMMANDS RUN: `sbt -batch storage/compile`; `sbt -batch storage/Test/compile`; several targeted `sbt -batch "storage/testOnly ..."` runs; `sbt -batch check` twice (the second after two small final edits). Plus read-only git, ls, du, grep, sed -n, tail and wc. I ran no awsTest, no AWS, no network, and never read the Phase 7 export or Neptune CSV folders.

TEST RESULTS:
- Final targeted run: `Passed: Total 41, Failed 0, Errors 0, Passed 41`. An earlier run over the six new or changed suites passed 61 of 61.
- Storage suites in the final `sbt check`, all 0 failed:
  - AwsEnvironmentSuite 15, NeptuneCsvSuite 12, RuntimeReadersSuite 9, LocalRuntimeEnvironmentSuite 4, GraphQuerySuite 10, DynamoSuite 12
  - IngestPipelineSuite 12, AwsErrorMappingSuite 13, VectorMetadataSuite 9, ExportSourceSuite 6, ObjectStoreSuite 5, WorkQueueSuite 3, VectorKeysSuite 3, LocalRealRunSuite 1
  - AwsAdaptersAwsSuite: RequiresAws, excluded.

VALIDATION RESULTS: exact lines from the final `sbt check` (exit=0):
```
[info] Ledger check passed (scalafmt 3.11.5).
[info] Updated /Users/brian/Developer/repos/scholarchain/.claude/.last-test-run (module_tests=1558, contract_tests=336)
[success] Total time: 918 s (0:15:18.0), completed Nov 15, 2026, 5:01:30 PM
```
The first `sbt check` also passed (exit=0, same counts, `[success] Total time: 921 s`).

ASSUMPTIONS:
- `AwsClientSettings.maxAttempts` stays as a parameter so rag's existing call (`maxAttempts = 1`) still compiles, but any value other than 1 is now refused.
- Graph reads are not retried: the path search has a total time budget, and a failed read is returned to the caller as Left.
- Display names for RM4 come from `canonical_researcher`, captured during the scan the EntityLinkCache load already does. A ranked researcher with no canonical record is rejected, which leaves a gap in the rank numbers.
- Ranks are ordinals 1..K; ties are already broken by the stored order.
- `EvidenceStore.putBundle` writes the EB1 items first, then the EB3 bundle. An item key that already holds different content is listed in `itemConflicts` and is never overwritten; the bundle is still written.

RISKS:
- The openCypher path template (`-[:COAUTHORED_WITH*k..k {windowLabel: '<label>'}]-`, with `LIMIT 2` as literal text) still needs checking on a live Neptune. It follows the Nov 15 facts but no live call was made.
- RM4 items that fall out of the top K after a reload stay in the table, because the loader never removes data (removal belongs to the cleanup policy). Readers ask only for the first k ranks, so stale tail items are never returned when K is unchanged.
- In-memory graph heap on the real data has not been measured here; rag's benchmark run will report it.

MANUAL ACTION REQUIRED: none.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: rag-engineer (decision 5) uses the interface below, then code-reviewer (decision 6).

---

## Fixes with tests that fail without them

**A1: AWS wiring.** `AwsStorageEnvironment` is built from StorageConfig plus SDK clients.
- Stub SDK client tests check that each store reaches its own client and that the graph and runtime stores are refused when the runtime or neptune section is missing.
- A no-network test builds the environment from real SDK clients over a stub HTTP transport and asserts 0 HTTP calls.
- There is also a RequiresAws smoke test (never run).

**m1: truncation flag.** The query text asks for `LIMIT <limit+1>`; the result returns at most `limit` rows with `truncated = rows > limit`.
- A stub NeptunedataClient that honours the LIMIT in the text: 5 available rows with limit 4 gives 4 rows, truncated. With limit 5 it gives 5 rows, not truncated.
- A stub that always returns limit + 1 rows gives limit rows, truncated.

**m2: no retries inside retries.** SDK maxAttempts is 1 and storage's Retry is the only retry layer.
- A real DynamoDbClient over a stub HTTP transport that throttles twice: exactly 3 HTTP calls, `counters.retries == 2`, `calls == 3`.
- A contrast test shows the old setup (SDK maxAttempts 3) also makes 3 HTTP calls but counts 0 retries, so the retries were hidden.
- Other tests: a permanent error makes 1 call; a client that always throttles makes maxRetries + 1 calls; maxAttempts 3 or 0 is refused.

**A2: Neptune load request and query text.**
- BulkLoadJob and its JSON no longer carry `updateSingleCardinalityProperties`; the exact key set is asserted, and the SDK request field is null.
- `userProvidedEdgeIds` is sent as TRUE; parallelism stays MEDIUM.
- A full fixture build writes no `:ID` twice across all files.
- No typed cell is `null`, `NaN` or `Infinity`; each typed cell parses as its type or is empty.
- The row limit is a literal integer in the query text (there is no `$limit` parameter).
- The window label is written as a quoted literal only in the variable-length path template, and only after it matches `[a-z0-9][a-z0-9-]*` and is a configured `graph.windows` label.
- Query-text tests:
  - other IDs give identical text;
  - another window changes only the quoted literal in path queries;
  - another limit changes only the integer;
  - IDs with quotes, braces or Cypher keywords, and unconfigured or malformed windows, are refused before any text is sent (the stub records 0 requests).

## Runbook notes from 4d (facts captured by the human architect on Nov 15)
- Load each graph run into an empty cluster. Never reload changed files into the same cluster: by default the loader does not update property values of an existing node or relationship, it only adds missing labels and properties.
- If an openCypher load fails with `LOAD_DATA_DEADLOCK`, retry the job with `parallelism = LOW` (the default stays MEDIUM).
- `updateSingleCardinalityProperties` is not sent, because the AWS pages disagree on whether openCypher supports it.
- `userProvidedEdgeIds = TRUE` is required because every relationship file carries `:ID`.
- Duplicate IDs in one load would be merged or picked non-deterministically. The builder guarantees there are none, and a test checks it.

## Path-search semantics (4e)
- **Neptune:** `PathBetween(from, to, window, maxDepth)` with maxDepth ≤ `runtime.maxPathDepth`.
  - One exact-depth query per depth k = 1, 2, ... maxDepth, in that order.
  - It stops at the first depth that returns a path.
  - It also stops when the total elapsed time reaches `runtime.graphTimeoutMillis`; each call's API timeout is the remaining budget.
  - A client API timeout or Neptune's `TimeLimitExceededException` stops the search. Any other error is returned as Left.
- **In-memory graph:** a breadth-first search, level by level, with the same budget check and the same result fields.
- **GraphResult fields:** `complete: Boolean` and `depthSearched: Option[Int]`.

| Outcome | rows | complete | depthSearched | truncated |
|---|---|---|---|---|
| Path found at depth k | 1 path | true | Some(k) | true when more than one shortest path exists (Neptune returns 2 rows; in-memory counts paths, capped at 2) |
| No path within depth d | Nil | true | Some(d) | false |
| Stopped at the time limit | Nil | false | Some(last depth fully searched, 0 if none) | false |

For single-shot templates, `complete` is always true and `depthSearched` is None.

## New template (4f): WorkById
- `GraphQuery.WorkById(workId: WorkId)`, templateId `work_by_id`, limit 1. The work ID must be well formed.
- Neptune text: `MATCH (w:Work) WHERE id(w) = $workId RETURN id(w) AS workId, w.displayName AS displayName, w.year AS year, w.venueKey AS venueKey, w.dblpKey AS dblpKey, w.teamSize AS teamSize LIMIT 2`
- The in-memory graph returns the same columns in the same order. year and teamSize are numbers; displayName is the title.

## RM4 key design and counts (4g)
- **Table and keys:** ResearcherMetricCache. pk `ranking#<windowLabel>#<metric wire name>`, sk `rank#<NNNN>` (from 0001).
- **Attributes:** windowLabel, metric, rank, researcherId, displayName, value, runId, policyVersion. runId and policyVersion come from the provenance of the metric's own family: breadth, bridges, or kcore for coreness.
- **Metrics:** publication_count, distinct_coauthors, total_coauthor_occurrences, average_coauthors_per_paper, max_coauthors_on_one_paper, weighted_degree, coreness, participation_coefficient, cross_community_degree, communities_connected.
- **Who is ranked:** only researchers whose RM1 item was built and who have the value (A and the participation coefficient are optional).
- **Order:** value descending, then researcherId by code point. For communities_connected: value descending, then cross_community_degree descending, then researcherId.
- **Idempotency:** written upsert-if-changed; a rerun gives 30 unchanged and 0 store writes (tested). Load stats appear under `ResearcherMetricCacheRanking`. RM4 is added to TableSpecs.
- **Fixture counts:** with K = 3, 10 metrics × 3 = 30 ranking items, plus 4 RM1 items, so 34 ResearcherMetricCache items. With K ≥ 4 there would be 40 ranking items.
- **Real data:** expected at most 5 windows × 10 metrics × K (25,000 with K = 500). Every window has far more than 500 researchers, so fewer only if some metric has fewer than K holders. The real counts will come from rag's benchmark run (5n), which reports them.

## Interface for rag-engineer
Packages: `org.scholarchain.storage.{aws, local, runtime, ports}`. rag never builds a DynamoDB key; every reader returns `Either[String, _]`, and a store failure after retries becomes Left.

```scala
// ---- shared runtime bundle (both environments) ----
trait RuntimeStores {
  def settings: RuntimeSettings; def windowLabels: List[String]
  def graph: GraphReadPort; def metrics: MetricCacheReader; def entityLinks: EntityLinkReader
  def evidence: EvidenceStore; def audits: QueryAuditWriter
  def vectors: VectorIndexPort; def textStore: TextStore
}

// ---- AWS ----
final case class AwsSdkClients(s3: S3Client, dynamoDb: DynamoDbClient, s3Vectors: S3VectorsClient,
                               neptune: Option[NeptunedataClient]) extends AutoCloseable
object AwsSdkClients { def build(settings: AwsClientSettings, config: StorageConfig,
  httpClient: Option[SdkHttpClient] = None, credentials: Option[AwsCredentialsProvider] = None): AwsSdkClients }
object AwsStorageEnvironment {
  def create(config: StorageConfig, clients: AwsSdkClients, rejectFile: Option[Path], sleeper: Sleeper = Sleeper.real): AwsStorageEnvironment
  def fromConfig(config: StorageConfig, settings: AwsClientSettings, rejectFile: Option[Path]): AwsStorageEnvironment
}
final class AwsStorageEnvironment {
  val config: StorageConfig; val spec: IndexSpecCatalog
  val objects: RetryingObjectStore; val kv: RetryingKeyValueStore; val vectors: RetryingVectorIndex
  val layout: S3KeyLayout; val textStore: TextStore; val rejects: RejectLog
  val graph: Either[String, NeptuneGraphRead]            // needs neptune + runtime sections
  lazy val runtimeStores: Either[String, RuntimeStores]
  def pipeline(embedder: EmbeddingPort, sourceDataset: String,
               extrasOf: SemanticDocument => DocumentExtras = _ => DocumentExtras.none,
               sleeper: Sleeper = <env sleeper>): VectorIngestPipeline   // same shape as LocalStorageEnvironment.pipeline
  def retryReport: ujson.Obj; def close(): Unit
}
// AwsClientSettings(region, connectTimeoutMillis, socketTimeoutMillis, apiCallTimeoutMillis, maxAttempts = 1 only)

// ---- local ----
final case class LocalRuntimeOptions(windowLabels: Option[List[String]] = None,   // default: runtime.defaultWindowLabel
                                     loaderParallelism: Int = 1, rejectFile: Option[Path] = None)
object LocalRuntimeEnvironment {
  def load(config: StorageConfig, exportDir: Path, neptuneCsvDir: Path,
           options: LocalRuntimeOptions = LocalRuntimeOptions()): Either[String, LocalRuntimeEnvironment]
}
final class LocalRuntimeEnvironment extends RuntimeStores with AutoCloseable {
  val storage: LocalStorageEnvironment; val graph: InMemoryGraph; val report: LocalRuntimeLoadReport
  def pipeline(embedder: EmbeddingPort, sourceDataset: String): VectorIngestPipeline
}
// report: graphRows (read/kept per type), graphFiles, tableItems, dynamo (DynamoLoadReport), rejects,
// heapUsedBytes, heapMaxBytes, millis(graph, tables, total); report.toJson
// Graph types loaded: nodes Researcher, Work, Venue; edges AUTHORED, PUBLISHED_IN, COAUTHORED_WITH
// (only the option windows). Tables: DynamoLoader with Some(runtime.rankingCacheTopK) → RM1 for every
// window + RM4, EntityLinkCache, EvidenceBundle (path_evidence items). Read-only toward both folders
// (a test compares the sha256 of every input file before and after loading).

// ---- readers / writers (package runtime) ----
final class MetricCacheReader {
  def get(researcherId: ResearcherId, windowLabel: String): Either[String, Option[MetricCacheEntry]] // RM1
  def allWindows(researcherId: ResearcherId): Either[String, List[MetricCacheEntry]]               // RM2, config window order
  def ranking(windowLabel: String, metric: CollaborationMetric, topK: Int): Either[String, List[RankingEntry]] // RM4, 1..rankingCacheTopK
}
final case class MetricCacheEntry(researcherId, windowLabel, window: YearWindow,
  values: ListMap[CollaborationMetric, Double], componentId: ResearcherId, communityId: ResearcherId,
  mrWorkIds: List[WorkId], identityConfidence: Double, runId: RunId, snapshotId: String,
  policyVersion: String, key: ItemKey) { def value(m): Option[Double]; def recordRef: String }
final case class RankingEntry(rank: Int, researcherId, displayName, value: Double, windowLabel,
  metric: CollaborationMetric, runId: RunId, policyVersion: String, key: ItemKey) { def recordRef: String }

final class EntityLinkReader {
  def byName(name: String, limit: Int): Either[String, EntityLinkResult]   // folds with AuthorNameKey.foldedName (drops the homonym suffix); limit 1..100; name ≤ 200 chars
  def byOrcid(orcid: String, limit: Int): Either[String, EntityLinkResult] // bare or https://orcid.org/ form; check digit verified
}
final case class EntityLinkResult(lookupKey: String, candidates: List[EntityLinkCandidate], truncated: Boolean)
final case class EntityLinkCandidate(researcherId, displayName, matchedValue, linkKind, keyRule, runId, policyVersion, key) { def recordRef: String }

final class EvidenceStore {
  def get(id: EvidenceId, window: Option[YearWindow]): Either[String, Option[EvidenceItem]] // EB1
  def windows(id: EvidenceId, limit: Int): Either[String, List[EvidenceItem]]             // EB2, 1..100
  def getBundle(bundleId: EvidenceId): Either[String, Option[EvidenceBundle]]             // EB3
  def putBundle(bundle: EvidenceBundle): Either[String, BundleWrite]  // put-if-absent EB1 items, then EB3; Left if > maxBundleItems, > 350,000 encoded bytes, or contract-invalid
}
final case class BundleWrite(bundleId, bundle: Change, itemsInserted: Int, itemsUnchanged: Int, itemConflicts: List[String], encodedBytes: Int)

final class QueryAuditWriter {
  def append(audit: QueryAudit): Either[String, AuditWrite]   // RunLedger pk run#<queryRunId>, a new attempt per append, event "query-audit", attr "record" = Contract JSON
  def read(queryRunId: RunId, limit: Int): Either[String, List[QueryAudit]]
}
final case class AuditWrite(queryRunId: RunId, attempt: Int, key: ItemKey, change: Change, encodedBytes: Int)

// ---- graph templates (package ports); every query validated against GraphReadSettings
//      (configured windows, runtime.maxPathDepth, runtime.graphResultLimit, runtime.graphTimeoutMillis) ----
GraphQuery.ResearcherById(id)                         // researcherId, properties (identityOutcome, minIdentityConfidence, metrics…)
GraphQuery.CoauthorNeighbors(id, windowLabel, limit)  // coauthorId, displayName, weight, jointPublicationCount, evidenceKey; weight desc, coauthorId
GraphQuery.CoauthorEdgeBetween(a, b, windowLabel)     // edgeId, properties (weight, jointPublicationCount, firstCollaborationYear,
                                                      //   lastCollaborationYear, supportingWorkIds ";"-joined, evidenceKey, windowLabel…)
GraphQuery.PathBetween(from, to, windowLabel, maxDepth) // researcherIds, evidenceKeys, hops
GraphQuery.WorkAuthors(workId, limit)                 // researcherId, displayName
GraphQuery.WorkById(workId)                           // workId, displayName, year, venueKey, dblpKey, teamSize
final case class GraphResult(templateId: String, rows: List[GraphRow], truncated: Boolean,
                             complete: Boolean, depthSearched: Option[Int])
trait GraphReadPort { def settings: GraphReadSettings; def run(q: GraphQuery): Either[String, GraphResult] }
```

Usage example:
```scala
val cfg = StorageConfig.from(scholarChainConfig).fold(e => sys.error(e.mkString), identity) // needs the runtime section
val env = LocalRuntimeEnvironment.load(cfg, exportDir, neptuneCsvDir).fold(sys.error, identity)
println(env.report.toJson.render(indent = 2))
val stores: RuntimeStores = env          // on AWS: AwsStorageEnvironment.fromConfig(cfg, aws, None).runtimeStores
val w = stores.settings.defaultWindowLabel
val cands = stores.entityLinks.byName("Wei Wang 0003", 10)
val top = stores.metrics.ranking(w, CollaborationMetric.DistinctCoauthors, 10)
val path = stores.graph.run(GraphQuery.PathBetween(a, b, w, stores.settings.maxPathDepth))
path.foreach { r =>
  if (r.rows.isEmpty && r.complete) println(s"no path within depth ${r.depthSearched.get}")
  else if (r.rows.isEmpty) println("stopped at the time limit")
}
stores.evidence.putBundle(bundle); stores.audits.append(audit)
```

## Bounded load results
I did no separate real-data load run, as decision 4j says. On the fixtures, LocalRuntimeEnvironment loaded:
- graph: Researcher 4, Work 3, Venue 1, AUTHORED 6, PUBLISHED_IN 3, COAUTHORED_WITH 3;
- tables: ResearcherMetricCache 34, EntityLinkCache 5, EvidenceBundle 1, 0 rejects.

The real counts, heap and timings will come from rag's benchmark run (5n).

## Proposed later (outside decision 4)
- A typed decoder for GraphRow columns, so rag does not parse the JSON itself.
- Retries for single-shot Neptune reads.
- A cleanup-policy step for RM4 tail items after K is reduced.
- A live check of the variable-length path template on a real Neptune.
