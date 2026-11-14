STATUS: COMPLETE
OWNER: domain-engineer
WORK ORDER: ScholarChain Phase 3, domain-engineer part (modules/core/** only). Covers immutable domain records, stable IDs, enums, configuration loader, serialization and schema-version tests, frozen JSON wire examples, and validation rules. Source: docs/agent-runs/phase-03/prompt.md and the orchestrator's invocation.

FILES CREATED:
Main, under /Users/brian/Developer/repos/scholarchain/modules/core/src/main/scala/org/scholarchain/core/:
- Hashing.scala: SHA-256, the canonical length-prefixed encoding, ContentHash, CodePointOrder, UndirectedPair
- ExternalIds.scala: Doi, Orcid, OpenAlexId (W/A/S/I), DblpKey, VenueKey, DblpAuthorName
- Enums.scala: WireEnum base plus SourceType, MatchOutcome, EvidenceType, CollaborationMetric, QueryOperation, VerificationStatus, GraphNodeLabel, GraphEdgeLabel
- StableIds.scala: WorkId, MentionId, ResearcherId, EdgeId, EvidenceId, RunId
- Records.scala: all 20 records plus the value objects YearWindow, MetricValue, MatchFeature, QueryStep
- SchemaVersions.scala
- Validator.scala
- Contract.scala: Contract.decode/encode/validate, ContractError, NullCheck
- config/ScholarChainConfig.scala
- config/ConfigLoader.scala: ConfigLoader, ConfigKey, ConfigOrigin, LoadedConfig, ConfigValidator

Tests, under /Users/brian/Developer/repos/scholarchain/modules/core/src/test/:
- scala/org/scholarchain/core/Fixtures.scala
- scala/org/scholarchain/core/WireContractSuite.scala
- scala/org/scholarchain/core/ValidationSuite.scala
- scala/org/scholarchain/core/StableIdsSuite.scala
- scala/org/scholarchain/core/PairOrderingSuite.scala
- scala/org/scholarchain/core/ExternalIdsSuite.scala
- scala/org/scholarchain/core/SchemaVersionsSuite.scala
- scala/org/scholarchain/core/config/ConfigLoaderSuite.scala
- resources/wire/v1/*.json: 20 frozen wire examples (AnswerClaim, AnswerResponse, AuthorMention, AuthorshipRecord, CanonicalResearcher, CoauthorEdge, CoauthorPairCandidate, EvidenceBundle, EvidenceItem, GraphEdge, GraphNode, Institution, MatchDecision, Provenance, PublicationTeam, QueryPlan, ResearcherMetrics, Topic, Venue, WorkRecord)
- resources/config/valid-v1.json: test-only config fixture with placeholder values

FILES MODIFIED: none. Nothing was changed outside modules/core/**. A temporary generator, test/scala/.../TmpGenerateWire.scala, was created to emit the frozen JSON and then deleted. No commit was made.

COMMANDS RUN:
- `sbt -batch "core/compile" "core2_13/compile"`, twice: success on both.
- `sbt -batch "core/Test/runMain org.scholarchain.core.TmpGenerateWire <wire dir>"`: generated the frozen files and printed the golden IDs.
- Python and `shasum` cross-check of the ID formulas and a content hash: all matched (see TEST RESULTS).
- `sbt -batch "core/test" "core2_13/test"`, several times. The first runs failed on a test-helper bug and a Scala 2.13 warning, both fixed. Final: 290/290 passing on each Scala version.
- Mutation checks with `sbt -batch core/test`. Each rule was disabled in turn (`schema`, `confidence`, `window`, `stableId`, `ordered`, `NullCheck.paths`), then the originals were restored from the scratchpad backups and checked byte-for-byte with `cmp`.
- `sbt -batch "scalafmtOnly <all 18 core .scala files>"`: success.
- `sbt -batch check`: exit 0.

TEST RESULTS:
- `sbt check` tail (observed):
```
Test run org.scholarchain.core.WireContractSuite finished: 0 failed, 0 ignored, 203 total 0.178s
[info] Module tests: suites=14 executed=580 passed=580 failed=0 errors=0 skipped=0
[info] Contract tests (no AWS): suites=0 executed=0 passed=0 failed=0 errors=0 skipped=0
[warn] No contract tests executed (tests/contracts/src/test/scala is empty).
[info] Updated /Users/brian/Developer/repos/scholarchain/.claude/.last-test-run (module_tests=580, contract_tests=0)
[success] Total time: 6 s, completed Nov 12, 2026, 9:11:01 PM
```
- Marker `.claude/.last-test-run` (read after the run): `timestamp=2026-11-13T03:11:01.020283Z`, `module_tests=580`, `contract_tests=0`.
- The 580 tests are the 290 core tests run once under Scala 3.3.9 (`core`) and once under Scala 2.13.17 (`core2_13`). Both produce the same wire JSON.
- Mutation checks: every validation behaviour has tests that fail without it.

| Rule disabled | Tests failing (of 290) |
|---|---|
| schema version | 41 |
| confidence range | 2 |
| window start ≤ end | 2 |
| malformed stable ID | 2 |
| pair ordering | 5 |
| null / missing provenance | 22 |

- Independent cross-check: Python `sha256("len:component"...)[:32]` reproduced the work, researcher, mention and edge golden IDs. `shasum -a 256` reproduced the title content hash `097a8f03...afe33`.

VALIDATION RESULTS: Contract.decode, Contract.encode and Contract.validate reject:
- **Missing provenance:** an absent key fails decoding; a JSON null, or a null in memory, fails NullCheck with "provenance: missing (null)". Provenance also needs non-empty sourceRecordIds, a datasetId matching [a-z0-9][a-z0-9-]*, non-blank snapshotId/jobName/policyVersion, a well-formed runId, and a contentHash of the form sha256:<64 hex> when present.
- **Content hash of text:** EvidenceItem.snippet, AnswerClaim.claimText, AnswerResponse.answerText and QueryPlan.question require provenance.contentHash == ContentHash.of(text).
- **Confidence:** values outside [0,1], or NaN or infinite, are rejected. This covers matchConfidence, confidence, minIdentityConfidence, identityConfidence, and the config thresholds.
- **Schema versions:** any schemaVersion not in SchemaVersions.supported is rejected, top level and nested; a missing schemaVersion fails decoding.
- **Malformed stable IDs:** rejected both on the wire and in memory. Well-formed IDs that don't match their formula are also rejected (for example workId vs dblpKey, mentionId vs (workId, position), edgeId, evidenceId, bundleId, researcherId vs dblpNames).
- **Undirected pair ordering:** CoauthorPairCandidate (left/right), CoauthorEdge (left/right) and GraphEdge with coauthored_with (from/to) must have left < right by code point. Self pairs are rejected.
- **Windows:** startYear > endYear is rejected in every record window, query step, claim and config window. Graph evidence (coauthor_edge, graph_path, metric) and every query operation except researcher_lookup must state a window. Edge firstYear/lastYear must fall inside the window.
- **Other rules:**
  - canonical external IDs (DOI, ORCID with check digit, OpenAlex, dblp key, dblp name);
  - MatchDecision: unresolved has no candidate, every other outcome needs one;
  - claims must cite evidence that is in the bundle; only `unsupported` claims may have no evidence;
  - count/list consistency (teamSize, sharedWorkCount);
  - no duplicate metrics;
  - unknown enum wire names are rejected;
  - absent Option keys are rejected (Options are written as null, never omitted).

ASSUMPTIONS:
1. **Configuration format.** I took the no-new-dependency route the work order allowed, so this is not BLOCKED-ON build-engineer. The versioned config file is strict JSON parsed with uJSON, which is already pinned. Valid JSON is also valid HOCON and YAML 1.2, so the files stay HOCON/YAML-compatible and could later be read by Typesafe Config unchanged. Typesafe Config would have needed a new dependency and a build change for one feature. If the human prefers full HOCON (comments, substitutions), route build-engineer to add com.typesafe:config to the ledger for core and core2_13; only ConfigLoader.load's parsing step would change.
2. **Researcher identity anchor.** The ResearcherId anchor is the minimum dblp name by code point, not ORCID. As a result, adding an ORCID or OpenAlex ID never changes a researcher's ID. Which mentions form a cluster is entity-resolution policy.
3. **dblp key validation is looser than observed.** DblpKey.isValid uses a structural rule (`[a-z]+(/[^/\s]+)+`), which is a superset of the observed alphabet regex; the dataset doc says that regex "is not a dblp guarantee", and the strict rule would silently lose valid records. `usesObservedAlphabet` is available for counting.
4. **ASCII digits only.** ORCID and DOI digits are ASCII only, and DOI `\S` uses the Unicode-aware (?U) flag to match Python.
5. **Topic IDs.** Topic.topicId is a free source key (non-empty, no whitespace), because the dataset contract defines no topic identifier.
6. **Fixture values are labelled as fixtures.** All code versions and input fingerprints in the wire examples are named fixture values. The record data itself (dblp key, title, names, ORCIDs, DOI, OpenAlex W/A/S/I IDs, country code PT) comes from datasets/samples/.

RISKS:
- **IDs are now frozen.** Stable IDs are truncated to 128 bits of SHA-256; collisions are negligible at about 10^7 IDs, but the length is now part of the contract. Changing any formula, wire name or field breaks the frozen files and needs a version bump.
- **Wire size.** Every embedded runtime record (claims, bundle items, plan) carries full provenance, so AnswerResponse JSON is verbose (about 5 KB for one claim).
- **Monolithic configuration.** ScholarChainConfig is one environment-wide contract: every deployment must supply every key, including model IDs for Hadoop-only runs. Splitting it per stage would be a breaking config change.
- **dblp key stability.** Keys are not verified stable across dblp releases (open question from dataset-engineer). WorkId depends on the key, so a re-keyed record would get a new WorkId.

MANUAL ACTION REQUIRED: none for this gate. The human should ratify the four choices above: JSON-as-HOCON config, the researcher anchor rule, the structural dblp key rule, and the 128-bit ID length.

BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none

NEXT GATE: code-reviewer reviews the modules/core diff (contract tests, `sbt check`, code review), then human ratification. tests/contracts/** is still empty: test-engineer can add cross-module contract tests that read modules/core frozen examples.

## Contract changes and schema versions
- This is a new contract; everything is at v1, so there are no breaking changes from a prior version.
- SchemaVersions.current is frozen by SchemaVersionsSuite. Each schema below is version 1, and readers accept exactly {1}:
  - Provenance, WorkRecord, AuthorMention, CanonicalResearcher, AuthorshipRecord
  - PublicationTeam, CoauthorPairCandidate, CoauthorEdge, ResearcherMetrics
  - Venue, Institution, Topic, MatchDecision, GraphNode, GraphEdge
  - EvidenceItem, EvidenceBundle, QueryPlan, AnswerClaim, AnswerResponse
  - ScholarChainConfig (`configVersion`)
- Enum wire names are frozen:
  - **SourceType:** dblp, openalex, crossref, orcid, pipeline, runtime
  - **MatchOutcome:** match, possible_match, non_match, unresolved
  - **EvidenceType:** work, authorship, coauthor_edge, graph_path, metric, match_decision, external_record
  - **CollaborationMetric:** distinct_coauthors, coauthored_works, weighted_degree, betweenness_centrality, bridge_score, component_size, clustering_coefficient
  - **QueryOperation:** researcher_lookup, collaboration_breadth, coauthor_neighborhood, shortest_path, connected_component, bridge_researchers
  - **VerificationStatus:** verified, partially_verified, unsupported, contradicted
  - **GraphNodeLabel:** researcher, work, venue, institution, topic
  - **GraphEdgeLabel:** coauthored_with (the only undirected label), authored, published_in, affiliated_with, has_topic
- Wire conventions:
  - camelCase field names; every field is required;
  - Option is written as null, never omitted;
  - IDs and enums are JSON strings;
  - unknown JSON keys are ignored (tolerant reader).

## Stable ID formulas
`digest32(c...)` is the first 32 lowercase hex characters of SHA-256(UTF-8 of the concatenation of `<utf8-byte-length>:<component>` for each component). Example: `7:work/v14:dblp20:conf/sigir/Sousa0J23`. The first component is the domain tag `<kind>/v1`. Every ID matches `^<kind>:v1:[0-9a-f]{32}$`.

| ID | Formula | Golden value |
|---|---|---|
| WorkId | `"work:v1:" + digest32("work/v1", "dblp", dblpKey verbatim)` | conf/sigir/Sousa0J23 → work:v1:9908f02cfc6bda3f54556ffdc400f581 |
| MentionId | `"mention:v1:" + digest32("mention/v1", workId, position as decimal)` | → mention:v1:34f70b4567c43fb2e857c8765d12d7b3 |
| ResearcherId | `"researcher:v1:" + digest32("researcher/v1", "dblp-name", anchor)`, where anchor is the code-point minimum of the researcher's canonical dblp names | Hugo O. Sousa → researcher:v1:ab3ac8eb9f387835bfeea872e5a38b3d |
| EdgeId | `"edge:v1:" + digest32("edge/v1", label, from, to, startYear, endYear)`; endpoints are canonicalized first for undirected labels | → edge:v1:0241a32a708f883ae022c9b3034d08c6 |
| EvidenceId (item) | `"evidence:v1:" + digest32("evidence/v1", evidenceType, runId, subjectIds...)`; subject order is kept | |
| EvidenceId (bundle) | `"evidence:v1:" + digest32("evidence/v1", "bundle", runId, sorted distinct itemIds...)` | |
| RunId | `"run:v1:" + digest32("run/v1", jobName, codeVersion, inputFingerprint, configFingerprint)`; all inputs non-blank | |

- RunId never uses time or randomness. ScholarChainConfig.fingerprint (`sha256:` of the compact config JSON) is the intended configFingerprint.
- ContentHash is `"sha256:" + hex(SHA-256(UTF-8 text))`.

## Pair canonicalization rule
- The canonical form of the unordered pair {x, y} is (min, max), comparing strings lexicographically by Unicode code point. This is the same as Python str comparison and dslib.canonical_pair.
- It is not Java String.compareTo, which compares UTF-16 code units. The two disagree for code points above U+FFFF compared with U+E000..U+FFFF; a test pins U+FF21 < U+1F600.
- Stored records must already satisfy left < right strictly; self pairs (x == y) have no canonical form and are rejected.
- This applies to CoauthorPairCandidate (dblp names), CoauthorEdge (researcher IDs) and GraphEdge with label coauthored_with (from/to).

## Configuration precedence
From highest to lowest:
1. **Environment variable** `SCHOLARCHAIN_CFG_<PATH>`, where the dotted path is mapped camelCase → UPPER_SNAKE and dots → `_`. Example: `entityResolution.matchThreshold` → `SCHOLARCHAIN_CFG_ENTITY_RESOLUTION_MATCH_THRESHOLD`. Text keys take the raw value; numbers and `graph.windows` take a JSON literal.
2. **Versioned JSON file** named by `SCHOLARCHAIN_CONFIG_FILE`. It requires a top-level `configVersion`, which can only come from the file.
3. **Nothing.** A key missing from both is an error, and there are no defaults.

All errors are reported together. Errors cover missing keys, unknown file keys, unknown `SCHOLARCHAIN_CFG_*` variables, empty values, wrong types, and semantic violations:
- bucket names, AWS Region, 12-digit account ID, Neptune host with no scheme, DynamoDB table name;
- 0 ≤ reviewThreshold ≤ matchThreshold ≤ 1;
- windows non-empty, with distinct labels and start ≤ end;
- minEdgeWeight ≥ 0; sampleSize > 0; topK > 0.

Other `SCHOLARCHAIN_*` variables are ignored.

Keys:
- environment
- aws.region, aws.accountId
- storage.rawBucket, storage.curatedBucket, storage.graphBucket, storage.vectorBucket, storage.vectorIndex, storage.neptuneEndpoint, storage.dynamoDbTable
- entityResolution.policyVersion, entityResolution.matchThreshold, entityResolution.reviewThreshold
- models.embeddingModelId, models.answerModelId
- graph.windows, graph.minEdgeWeight
- bridge.algorithm, bridge.sampleSize, bridge.randomSeed, bridge.topK

StorageSettings.resolveTemplate fills in the manifest placeholders {raw_bucket}, {curated_bucket}, {graph_bucket} and {vector_bucket}. It leaves runtime placeholders such as {yyyy-mm-dd} alone and rejects unknown `{*_bucket}` placeholders. No production config file exists yet (see Unresolved questions).

## Sample serialized records (from the frozen files, compacted)
WorkRecord.json:
```
{"schemaVersion":1,"workId":"work:v1:9908f02cfc6bda3f54556ffdc400f581","dblpKey":"conf/sigir/Sousa0J23","publicationType":"inproceedings","title":"tieval: An Evaluation Framework for Temporal Information Extraction Systems.","year":2023,"venueKey":"conf/sigir","doi":"10.1145/3539618.3591892","openAlexWorkId":"W4366851189","authorNames":["Hugo O. Sousa","Ricardo Campos 0001","Alípio Mário Jorge"],"provenance":{"schemaVersion":1,"sourceType":"dblp","datasetId":"dblp","snapshotId":"2026-11-01","sourceRecordIds":["conf/sigir/Sousa0J23"],"runId":"run:v1:044e0b57adf2607d9573368a2117394d","jobName":"hadoop.parse-dblp","policyVersion":"parse-policy/1","contentHash":null}}
```
CoauthorPairCandidate.json:
```
{"schemaVersion":1,"workId":"work:v1:9908f02cfc6bda3f54556ffdc400f581","year":2023,"left":"Alípio Mário Jorge","right":"Hugo O. Sousa","teamSize":3,"provenance":{"schemaVersion":1,"sourceType":"dblp","datasetId":"dblp","snapshotId":"2026-11-01","sourceRecordIds":["conf/sigir/Sousa0J23"],"runId":"run:v1:044e0b57adf2607d9573368a2117394d","jobName":"hadoop.parse-dblp","policyVersion":"parse-policy/1","contentHash":null}}
```
CoauthorEdge.json:
```
{"schemaVersion":1,"edgeId":"edge:v1:0241a32a708f883ae022c9b3034d08c6","left":"researcher:v1:ab3ac8eb9f387835bfeea872e5a38b3d","right":"researcher:v1:f6fb57aa344a13707902b5ee9e48806c","window":{"startYear":2015,"endYear":2025},"weight":0.5,"sharedWorkCount":1,"firstYear":2023,"lastYear":2023,"supportingWorkIds":["work:v1:9908f02cfc6bda3f54556ffdc400f581"],"minIdentityConfidence":0.97,"provenance":{"schemaVersion":1,"sourceType":"pipeline","datasetId":"scholarchain-graph","snapshotId":"run:v1:fc416a4c7c92627cfd9e8edf99f08fe7","sourceRecordIds":["conf/sigir/Sousa0J23"],"runId":"run:v1:fc416a4c7c92627cfd9e8edf99f08fe7","jobName":"spark.build-graph","policyVersion":"graph-policy/1","contentHash":null}}
```
MatchDecision.json:
```
{"schemaVersion":1,"mentionId":"mention:v1:34f70b4567c43fb2e857c8765d12d7b3","candidateResearcherId":"researcher:v1:ab3ac8eb9f387835bfeea872e5a38b3d","outcome":"match","confidence":0.97,"features":[{"name":"orcid_equal","value":1},{"name":"name_similarity","value":1}],"provenance":{"schemaVersion":1,"sourceType":"pipeline","datasetId":"scholarchain-graph","snapshotId":"run:v1:fc416a4c7c92627cfd9e8edf99f08fe7","sourceRecordIds":["conf/sigir/Sousa0J23"],"runId":"run:v1:7717327787dbcf997ecc871eb5a4dbde","jobName":"entity-resolution.resolve","policyVersion":"match-policy/1","contentHash":null}}
```
EvidenceItem.json:
```
{"schemaVersion":1,"evidenceId":"evidence:v1:d8c6bb9bae6ed5cee33a778a8dd6e743","evidenceType":"work","subjectIds":["work:v1:9908f02cfc6bda3f54556ffdc400f581"],"window":null,"metric":null,"snippet":"tieval: An Evaluation Framework for Temporal Information Extraction Systems.","provenance":{"schemaVersion":1,"sourceType":"runtime","datasetId":"scholarchain-graph","snapshotId":"run:v1:fc416a4c7c92627cfd9e8edf99f08fe7","sourceRecordIds":["conf/sigir/Sousa0J23"],"runId":"run:v1:d5b4e52fdf1171ad6d9e1f1251db9278","jobName":"rag.answer","policyVersion":"prompt-policy/1","contentHash":"sha256:097a8f0395a0f0d7b24339481fe72249a3325ae0b0e33a60d6f56208713afe33"}}
```
The full AnswerResponse (plan, two-item bundle, verified metric claim, identity note) is in /Users/brian/Developer/repos/scholarchain/modules/core/src/test/resources/wire/v1/AnswerResponse.json.

## Unresolved questions
1. **No owner for production config files.** Real versioned config files (for example `config/<environment>.json`) are in no agent's territory in docs/agents.md, so I created none; only the test fixture exists. factory-engineer needs to designate an owner before environments are configured; infra-engineer is a likely candidate since values mirror Terraform outputs.
2. **dslib.py digit rule (for dataset-engineer).** dslib.py ORCID_RE uses Python's Unicode `\d` with `int(ch)`, so it accepts non-ASCII decimal digits (for example Arabic-Indic), and the DOI `\d`-free regex is unaffected. Scala rejects them. Recommend dslib use `[0-9]`; this is a doc/impl disagreement to route to dataset-engineer.
3. **dblp key stability.** Whether dblp keys are stable across releases is unverified; it affects WorkId stability (dataset-engineer's open question).
4. **OpenAlex author ID stability.** OpenAlex author IDs are unstable across snapshots (merges in 2026-04 and 2026-05). Records carry them only as attributes with snapshot provenance and never as stable-ID inputs. entity-resolution-engineer should confirm this policy.
5. **Preprint and version-of-record merge.** Merging a preprint with its version of record (two DOIs) is left to entity resolution. WorkRecord holds one optional canonical DOI from the dblp `<ee>`.
6. **Open questions inherited from the dataset manifest.** Subset rule ratification, sample licensing for ORCID and Crossref, and the DTD equivalence question are not affected by this work.
