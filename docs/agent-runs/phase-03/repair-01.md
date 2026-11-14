STATUS: COMPLETE
OWNER: domain-engineer
WORK ORDER: ScholarChain Phase 3, repair round (single round) for domain-engineer, limited to modules/core/**. Fix review findings 1 (BLOCKING), 2 (BLOCKING) and 11 (minor). Finding 9 needs no code change (it is the human's JSON-vs-HOCON decision); the loader is unchanged. No commit made.

FILES CREATED:
- /Users/brian/Developer/repos/scholarchain/modules/core/src/test/resources/wire/v1/ExternalIdLink.json

FILES MODIFIED:
- /Users/brian/Developer/repos/scholarchain/modules/core/src/main/scala/org/scholarchain/core/Enums.scala: new ExternalIdType and LinkSubjectKind
- /Users/brian/Developer/repos/scholarchain/modules/core/src/main/scala/org/scholarchain/core/StableIds.scala: ResearcherId formula, AnchorCandidate, LinkId
- /Users/brian/Developer/repos/scholarchain/modules/core/src/main/scala/org/scholarchain/core/Records.scala: WorkRecord, AuthorMention, Venue, CanonicalResearcher, new SourcedExternalId and ExternalIdLink, Topic and Institution docs, file-header "one record, one source" rule
- /Users/brian/Developer/repos/scholarchain/modules/core/src/main/scala/org/scholarchain/core/Validator.scala
- /Users/brian/Developer/repos/scholarchain/modules/core/src/main/scala/org/scholarchain/core/SchemaVersions.scala: adds "ExternalIdLink" -> 1
- /Users/brian/Developer/repos/scholarchain/modules/core/src/test/scala/org/scholarchain/core/{Fixtures, StableIdsSuite, ValidationSuite, WireContractSuite, SchemaVersionsSuite}.scala
- Frozen files regenerated under /Users/brian/Developer/repos/scholarchain/modules/core/src/test/resources/wire/v1/:
  - AnswerClaim, AnswerResponse, AuthorMention, AuthorshipRecord, CanonicalResearcher, CoauthorEdge
  - EvidenceBundle, GraphEdge, GraphNode, MatchDecision, QueryPlan, ResearcherMetrics
  - Topic, Venue, WorkRecord
  - I diffed every regenerated file against the old one before installing it. The only differences were the intended ones: removed foreign-ID fields, new researcher/edge/evidence IDs, the new CanonicalResearcher shape, and the Topic name.

COMMANDS RUN:
- Temporary test-only generator `core/testOnly ...TmpRegenSuite` (wrote to the scratchpad). The file was deleted afterwards. The first attempt used env vars and was blocked by the guard hook; I switched to a fixed scratchpad path.
- Python cross-check of the golden IDs (independent sha256 of the length-prefixed encoding).
- `sbt -batch fmt`
- `sbt -batch check`, run twice.
- Two mutation runs of `core/testOnly` (StableIdsSuite, ValidationSuite). The originals were restored from backups, checked by grep.

TEST RESULTS:
- `sbt -batch check` passed: Module tests suites=14 executed=638 passed=638 failed=0. That is 319 core tests, each run under Scala 3 (`core`) and Scala 2.13 (`core2_13`).
- Contract tests: 0 (tests/contracts is empty; that is finding 10, owned by test-engineer).
- `.claude/.last-test-run` was refreshed by markTestRun.

VALIDATION RESULTS:
- Mutation 1: ResearcherId was made to ignore its anchor (simulating the old name-anchor collision). Five StableIdsSuite tests failed, including "review finding 1: two clusters sharing a dblp name get different researcher IDs" (StableIdsSuite.scala:167) and the stability test.
- Mutation 2: the source checks were disabled (assertedBy, fromDblp, and the no-dblp-link rule). Five ValidationSuite tests failed: lines 296, 320, 366, 375 and 382.
- Both originals were restored and the final `sbt check` passed.
- Python independently reproduced every new golden: researcher, mention(pos 1), edge and link.

ASSUMPTIONS:
- v1 shapes and goldens were changed in place, not versioned as v2. This was explicitly allowed because nothing is committed or consumed yet. All schema versions stay at 1; ExternalIdLink is a new v1 schema.
- The OpenAlex fixture snapshotId "2026-11-12" is the API retrieval date of datasets/samples/openalex. The manifest's bounded path is API lookups that record retrieved_at.
- Every fixture OpenAlex fact (W4366851189 → A5080716895, I182534213, I4210166615, S4363608773, ORCID 0000-0003-3226-9189) was re-checked against datasets/samples/openalex/works.jsonl. The dblp ORCID was checked against dblp-sample.xml:25.

RISKS:
- ResearcherId correctness depends on two cross-record invariants that no single-record validator can check: (a) mentions are partitioned among researchers, and (b) the anchor is the earliest of the researcher's authorships. These need contract or output tests: test-engineer and spark-engineer, via the orchestrator.
- The ID moves on merges, on splits that take the anchor away, on back-filled older papers, and on dblp key/year/author-order corrections of the anchor work. This is documented in the ResearcherId scaladoc.
- GraphNode.properties is still a free-form map. The fixture's `orcid` property carries pipeline provenance and is traceable only through the CanonicalResearcher's SourcedExternalId. Producers should not put foreign IDs there without that chain.
- uPickle ignores unknown JSON keys, so an old producer that still writes `openAlexWorkId` would be read with that key silently dropped, not rejected.
- The old report text in docs/agent-runs/phase-03/agent-report-domain.md (the researcher anchor, "OpenAlex IDs carried only as attributes") is now outdated. That file is human-owned.

MANUAL ACTION REQUIRED: The human should ratify two contract choices:
- the researcher anchor: the earliest mention by (year, workId, position) instead of the code-point-minimum MentionId (justified below);
- the link-record and sourced-claim design for foreign IDs.

Finding 9 (JSON vs HOCON) remains a human decision.

BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer re-review of modules/core/**. After that, test-engineer adds tests/contracts that load wire/v1 (finding 10) and check the ResearcherId cross-record invariants.

## Contract changes and schema versions (all v1, changed in place, nothing consumed yet)

| Schema | Change |
|---|---|
| WorkRecord | removed `openAlexWorkId`; `provenance.sourceType` must be `dblp` |
| AuthorMention | removed `openAlexAuthorId`; `orcid` (dblp's `<author orcid>`) kept; sourceType must be `dblp` |
| Venue | removed `openAlexSourceId`; sourceType must be `dblp` |
| CanonicalResearcher | added `anchorMentionId`; replaced `orcid`/`openAlexAuthorIds`/`institutionIds` with `externalIds: List[SourcedExternalId]` |
| SourcedExternalId (embedded value) | new: idType, value, sourceType, datasetId, snapshotId, sourceRecordId |
| ExternalIdLink | new schema, v1 |
| Institution | sourceType must be `openalex` |
| Topic | doc and fixture only (no shape change) |
| Enums | new ExternalIdType (doi, orcid, openalex_work, openalex_author, openalex_source, openalex_institution); new LinkSubjectKind (work, mention, venue) |

Why links plus sourced claims, rather than per-source entries inside Provenance:
- Every dblp-sourced record stays single-source. The Hadoop dblp parse writes only dblp facts.
- Each enrichment job (for example hadoop.link-openalex) writes ExternalIdLink rows with exactly one Provenance, naming the OpenAlex dataset, the snapshot or retrieval date, and the asserting OpenAlex work.
- A new OpenAlex snapshot adds new link rows (the LinkId includes the snapshot) without rewriting dblp records. This matters because OpenAlex author IDs are unstable across snapshots.
- Spark and entity resolution consume links as an ordinary keyed table joined on subjectId.
- Changing Provenance itself would have altered all 21 schemas and every consumer.
- CanonicalResearcher is a genuine multi-source aggregate, so it carries each foreign ID inline with its own source triple. That keeps snapshot traceability without forcing a multi-hop join.

## Per-finding resolution

| # | Change made | file:line | Tests that fail without it |
|---|---|---|---|
| 1 | ResearcherId is now anchored on the cluster's earliest mention: digest("researcher/v1", "anchor-mention", anchorMentionId), with the anchor = min by (year, workId code point, position). Mentions are partitioned among researchers, so anchors are cluster-unique. I chose earliest-mention over the code-point-minimum MentionId as the cluster-unique anchor (option (a)): a random-hash minimum would change a researcher's ID with probability 1/(n+1) on every new publication, while earliest-mention is stable under normal growth and can be checked from AuthorshipRecord fields alone. Added `AnchorCandidate`, `anchorOf`, `forCluster`. CanonicalResearcher carries `anchorMentionId`; the validator checks the formula. The stability semantics are documented in the scaladoc. | StableIds.scala:61-120 (fromAnchorMention :91, anchorOf :106, AnchorCandidate :117); Records.scala:136; Validator.scala:175-182 | StableIdsSuite:167 (two "Wei Wang" clusters get different IDs; {m1,m2} vs {m3}), :181 (anchor order), :205 (stability), :43 (golden); ValidationSuite:266 (two valid split researchers with distinct IDs), :282, and the updated formula-mismatch test (~:88) |
| 2 | Foreign IDs were removed from dblp records. Added ExternalIdLink (one source per link, LinkId bound to the source snapshot) and SourcedExternalId on CanonicalResearcher. ExternalIdType.sources: OpenAlex keys only from openalex; DOI and ORCID from dblp/openalex/crossref/orcid. Links may not use dblp provenance, because dblp-published DOIs and ORCIDs live on WorkRecord.doi and AuthorMention.orcid. That is the dblp-ORCID distinction: a dblp-sourced ORCID claim on a researcher is valid. Also enforced: subject kind/ID-type fit, at most one distinct ORCID per researcher, externalIds sorted and distinct, WorkRecord/AuthorMention/Venue must have dblp provenance, Institution must have openalex provenance. | Enums.scala:173-245; Records.scala:15-21, 64, 83, 102-130, 136, 224, 266; StableIds.scala:204-225; Validator.scala:96-117, 148, 163, 167-205, 295, 300, 332-360; SchemaVersions.scala:18 | ValidationSuite:290, :296 (OpenAlex ID with dblp/crossref/orcid/pipeline provenance rejected), :320 (ORCID link from enrichment accepted, dblp link rejected), :347 (snapshot bound into LinkId), :354, :366 (dblp records need dblp provenance; dblp ORCID valid), :375, :382 (researcher claim with dblp/pipeline source, blank snapshot/record/dataset rejected), :420, :426; StableIdsSuite:71, :88; WireContractSuite:167, :175 and the ExternalIdLink frozen tests; SchemaVersionsSuite (table, enum wire names, sources) |
| 9 | No change (human decision) | ConfigLoader.scala unchanged | n/a |
| 11 | Fixture topic name is now "fixture topic name (not from a source dataset)". Topic doc now says topicId is the source's key when the provenance names a source dataset, and a job-assigned key when sourceType is pipeline. | Records.scala:244-247; Fixtures.scala (topic); wire/v1/Topic.json | Doc and fixture only. Frozen Topic.json is pinned by the WireContractSuite encode/decode tests. |

## Stable ID formulas (digest32 = first 32 hex of SHA-256 over length-prefixed UTF-8 components)

| ID | Formula | Golden |
|---|---|---|
| WorkId | work:v1: + digest32("work/v1","dblp",dblpKey) | conf/sigir/Sousa0J23 → work:v1:9908f02cfc6bda3f54556ffdc400f581 (unchanged) |
| MentionId | mention:v1: + digest32("mention/v1",workId,position) | pos 0 → mention:v1:34f70b4567c43fb2e857c8765d12d7b3 (unchanged); pos 1 → mention:v1:75a3435346b593e5427df42c1d1f3f53 |
| ResearcherId (CHANGED) | researcher:v1: + digest32("researcher/v1","anchor-mention",anchorMentionId); anchor = min of the cluster's mentions by (year, workId by code point, position) | Sousa (anchor pos 0) → researcher:v1:6d39fb31017461bdf6bbe118864d1809; Campos (anchor pos 1) → researcher:v1:b09b464e25f55dd98a87bd4f0c20577c |
| EdgeId | edge:v1: + digest32("edge/v1",label,from,to,startYear,endYear), with undirected endpoints canonicalized | Sousa–Campos 2015-2025 → edge:v1:4bb65089a2a14df5a532c14ba4a10cb4 (was 0241a32a..., because the researcher IDs changed) |
| LinkId (NEW) | link:v1: + digest32("link/v1",subjectKind,subjectId,idType,externalId,sourceType,datasetId,snapshotId) | Sousa mention → openalex_author A5080716895 @ openalex/2026-11-12 → link:v1:7756f92c370bb8a558652c009132dfe9 |
| RunId, EvidenceId | unchanged formulas | run:v1:044e0b57adf2607d9573368a2117394d unchanged; metric evidence is now evidence:v1:956b66b5f8244d130969ed13b9ae909e and the bundle evidence:v1:bb849f2bb61c5c6d0833a00f96fcd1e8, because the subject researcher ID changed |

ResearcherId stability (documented in StableIds.scala):
- **Unchanged by:** later publications, new ORCID/OpenAlex/institution IDs, new name variants.
- **Changed by:** the anchor mention leaving the cluster, an earlier mention joining (a merge, or dblp back-filling an older paper), or dblp changing the anchor work's key, year or author order.
- **On a merge:** the merged researcher keeps the earlier anchor's ID; the other ID vanishes and must never be reused.

Pair canonicalization and configuration precedence are unchanged.

## Sample serialized records (frozen v1)

WorkRecord:
{"schemaVersion":1,"workId":"work:v1:9908f02cfc6bda3f54556ffdc400f581","dblpKey":"conf/sigir/Sousa0J23","publicationType":"inproceedings","title":"tieval: An Evaluation Framework for Temporal Information Extraction Systems.","year":2023,"venueKey":"conf/sigir","doi":"10.1145/3539618.3591892","authorNames":["Hugo O. Sousa","Ricardo Campos 0001","Alípio Mário Jorge"],"provenance":{"schemaVersion":1,"sourceType":"dblp","datasetId":"dblp","snapshotId":"2026-11-01","sourceRecordIds":["conf/sigir/Sousa0J23"],"runId":"run:v1:044e0b57adf2607d9573368a2117394d","jobName":"hadoop.parse-dblp","policyVersion":"parse-policy/1","contentHash":null}}

AuthorMention:
{"schemaVersion":1,"mentionId":"mention:v1:34f70b4567c43fb2e857c8765d12d7b3","workId":"work:v1:9908f02cfc6bda3f54556ffdc400f581","position":0,"name":"Hugo O. Sousa","orcid":"0000-0003-3226-9189","provenance":{"schemaVersion":1,"sourceType":"dblp","datasetId":"dblp","snapshotId":"2026-11-01","sourceRecordIds":["conf/sigir/Sousa0J23"],"runId":"run:v1:044e0b57adf2607d9573368a2117394d","jobName":"hadoop.parse-dblp","policyVersion":"parse-policy/1","contentHash":null}}

ExternalIdLink (new):
{"schemaVersion":1,"linkId":"link:v1:7756f92c370bb8a558652c009132dfe9","subjectKind":"mention","subjectId":"mention:v1:34f70b4567c43fb2e857c8765d12d7b3","idType":"openalex_author","externalId":"A5080716895","provenance":{"schemaVersion":1,"sourceType":"openalex","datasetId":"openalex","snapshotId":"2026-11-12","sourceRecordIds":["W4366851189"],"runId":"run:v1:2203a5e44aceb63c892a8234950d2286","jobName":"hadoop.link-openalex","policyVersion":"link-policy/1","contentHash":null}}

CanonicalResearcher:
{"schemaVersion":1,"researcherId":"researcher:v1:6d39fb31017461bdf6bbe118864d1809","anchorMentionId":"mention:v1:34f70b4567c43fb2e857c8765d12d7b3","displayName":"Hugo O. Sousa","dblpNames":["Hugo O. Sousa"],"externalIds":[{"idType":"openalex_author","value":"A5080716895","sourceType":"openalex","datasetId":"openalex","snapshotId":"2026-11-12","sourceRecordId":"W4366851189"},{"idType":"openalex_institution","value":"I182534213","sourceType":"openalex","datasetId":"openalex","snapshotId":"2026-11-12","sourceRecordId":"W4366851189"},{"idType":"openalex_institution","value":"I4210166615","sourceType":"openalex","datasetId":"openalex","snapshotId":"2026-11-12","sourceRecordId":"W4366851189"},{"idType":"orcid","value":"0000-0003-3226-9189","sourceType":"dblp","datasetId":"dblp","snapshotId":"2026-11-01","sourceRecordId":"conf/sigir/Sousa0J23"},{"idType":"orcid","value":"0000-0003-3226-9189","sourceType":"openalex","datasetId":"openalex","snapshotId":"2026-11-12","sourceRecordId":"W4366851189"}],"provenance":{"schemaVersion":1,"sourceType":"pipeline","datasetId":"scholarchain-graph","snapshotId":"run:v1:fc416a4c7c92627cfd9e8edf99f08fe7","sourceRecordIds":["mention:v1:34f70b4567c43fb2e857c8765d12d7b3","link:v1:7756f92c370bb8a558652c009132dfe9"],"runId":"run:v1:7717327787dbcf997ecc871eb5a4dbde","jobName":"entity-resolution.resolve","policyVersion":"match-policy/1","contentHash":null}}

Venue:
{"schemaVersion":1,"venueKey":"conf/sigir","name":"SIGIR","provenance":{"schemaVersion":1,"sourceType":"dblp","datasetId":"dblp","snapshotId":"2026-11-01","sourceRecordIds":["conf/sigir/2023"],"runId":"run:v1:044e0b57adf2607d9573368a2117394d","jobName":"hadoop.parse-dblp","policyVersion":"parse-policy/1","contentHash":null}}

Topic:
{"schemaVersion":1,"topicId":"fixture-topic-temporal-information-extraction","name":"fixture topic name (not from a source dataset)","provenance":{"schemaVersion":1,"sourceType":"pipeline","datasetId":"scholarchain-graph","snapshotId":"run:v1:fc416a4c7c92627cfd9e8edf99f08fe7","sourceRecordIds":["conf/sigir/Sousa0J23"],"runId":"run:v1:fc416a4c7c92627cfd9e8edf99f08fe7","jobName":"spark.topics","policyVersion":"topic-policy/1","contentHash":null}}

## Tail of observed `sbt -batch check` output (final run)

[info] Ledger check passed (scalafmt 3.11.5).
[info] scalafmt: Checking 2 Scala sources (/Users/brian/Developer/repos/scholarchain/.sbt/matrix/core)...
[info] scalafmt: Checking 2 Scala sources (/Users/brian/Developer/repos/scholarchain/.sbt/matrix/core2_13)...
Test run org.scholarchain.core.WireContractSuite finished: 0 failed, 0 ignored, 215 total 0.148s
[info] Module tests: suites=14 executed=638 passed=638 failed=0 errors=0 skipped=0
[info] Contract tests (no AWS): suites=0 executed=0 passed=0 failed=0 errors=0 skipped=0
[warn] No contract tests executed (tests/contracts/src/test/scala is empty).
[info] Updated /Users/brian/Developer/repos/scholarchain/.claude/.last-test-run (module_tests=638, contract_tests=0)
[success] Total time: 6 s, completed Nov 12, 2026, 9:32:24 PM
