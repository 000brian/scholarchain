# Hadoop job contracts

One section per Hadoop MapReduce job of [`modules/hadoop/`](../modules/hadoop/): input and output key and value types, input and output locations, mapper, reducer, partitioner and combiner classes, output codec, the configuration keys with their defaults, and the tests and fixtures that exercise the job. The job graph and the recorded counts are in [`docs/data-lineage.md`](data-lineage.md#3-hadoop-jobs-j01-to-j09); this document does not repeat them.

**Status.** Documentation of existing code at commit `d8b49fa` (Phase 17 session 3, step E12 autograder follow-up, check HADOOP-11). No code, test or configuration was changed. Every item cites the file and line it comes from.

**Path abbreviations** used in the tables:

- `hadoop/…` is `modules/hadoop/src/main/scala/org/scholarchain/hadoop/…`
- `defaults.xml` is [`modules/hadoop/src/main/resources/scholarchain-hadoop-defaults.xml`](../modules/hadoop/src/main/resources/scholarchain-hadoop-defaults.xml)
- `test/…` is `modules/hadoop/src/test/scala/org/scholarchain/hadoop/…`
- `contracts/…` is `tests/contracts/src/test/scala/org/scholarchain/contracts/hadoop/…`

Contents:

- [1. What every job shares](#1-what-every-job-shares)
- [2. J01 DBLP extract](#2-j01-dblp-extract)
- [3. J02 source normalizers](#3-j02-source-normalizers)
- [4. J03 and J04 exact identifier join](#4-j03-and-j04-exact-identifier-join)
- [5. J05 blocking](#5-j05-blocking)
- [6. J05b fallback blocking](#6-j05b-fallback-blocking)
- [7. J05c fallback coverage](#7-j05c-fallback-coverage)
- [8. J06 candidate features](#8-j06-candidate-features)
- [9. J07 publication teams](#9-j07-publication-teams)
- [10. J08a pair expansion](#10-j08a-pair-expansion)
- [11. J08b pair consolidation](#11-j08b-pair-consolidation)
- [12. J09 graph export](#12-j09-graph-export)
- [13. Known limitations and their rationale](#13-known-limitations-and-their-rationale)

Run order (not the numbering): J01, J07, J08a, J08b (`hadoop/pipeline/DblpChain.scala:43-75`), then J02 per source, J04, J05, J05b, J05c, J06, J09 (`hadoop/pipeline/Pipeline.scala:99-222`).

## 1. What every job shares

Every job is described by a `JobSpec` and run by `JobRunner.run` (`hadoop/common/JobRunner.scala:29-50`, `:98-191`). The items below hold for every job unless its own section says otherwise.

| Item | Contract | Source |
|---|---|---|
| Map input key and value | `LongWritable` (byte offset) and `RawRecord` (one complete unit with a status: ok, oversize, truncated, decompression limit). Two input formats: `DblpXmlInputFormat` (J01 only) and `BoundedLineInputFormat` (every other job; one JSON line, capped by the key named in `scholarchain.run.maxLineBytesKey`) | `hadoop/io/ScholarChainInputFormats.scala:59`, `:86`, `:112` |
| Map output key and value | `Text`, `Text` | `hadoop/common/JobRunner.scala:136-137` |
| Final output key and value | `NullWritable`, `Text` (one JSON line per record) through `TextOutputFormat`, wrapped in `LazyOutputFormat`; each named output is a `MultipleOutputs` output written to `<dir>/part-…` | `hadoop/common/JobRunner.scala:138-151`; `hadoop/common/TaskIO.scala:33-37` |
| Accounting outputs (every job) | `rejects/v1/`, `excluded/v1/`, `issues/v1/`, `conflicts/v1/`, `stats/` | `hadoop/common/TaskIO.scala:124-135`; added to every job at `hadoop/common/JobRunner.scala:143` |
| Partitioner | `StableHashPartitioner` (first 4 bytes of SHA-256 of the map-output key, modulo the reducer count); J05 and J05c override it with `TwoFieldPrefixPartitioner` | `hadoop/common/JobRunner.scala:140`; `hadoop/common/StableHashPartitioner.scala:14`, `:24` |
| Number of reducers | the job's own `*.reducers` key (each section) | `hadoop/common/JobRunner.scala:141` |
| Combiner | none: no job calls `setCombinerClass` (a search of `modules/hadoop/src/main` for `setCombinerClass` finds no match) | repository search |
| Speculative execution | off for maps and reduces | `hadoop/common/JobRunner.scala:131-132` |
| Intermediate (shuffle) codec | on by default, `org.apache.hadoop.io.compress.DefaultCodec` (operational keys, not fingerprinted) | `defaults.xml:43-55`; `hadoop/common/JobRunner.scala:123-129` |
| Final output codec | off by default (`scholarchain.output.compress` false); when on, `org.apache.hadoop.io.compress.GzipCodec`. The EMR step passes `-D` only for the code version (`infra/terraform/modules/workflow/sfn.tf:358`), and `-D` options after the application options are not read (`hadoop/pipeline/ScholarChainHadoop.scala:19-21`), so the recorded EMR runs used the default (inferred from those two lines and from the bounded-pipeline run record note "built-in defaults (-D scholarchain.code.version only)", `scripts/run-bounded-pipeline.sh:1439-1440`; no EMR output listing was checked) | `defaults.xml:56-66`; `hadoop/common/JobRunner.scala:153-161` |
| Output folder | `<output root>/<scope>/<job name without "hadoop.">/run=<run hex>/`, holding the named outputs, `_SUCCESS` and `_job.json` (identity, counters, stats, sha256 of every output file) | `hadoop/common/JobRunner.scala:83`, `:92-96`, `:176-189` |
| Scope | `dblp/snapshot=<id>` for the DBLP jobs; `<datasetId>/snapshot=<id>` for J02; `linked/<label>` for J04 and J09, where the label joins the DBLP and source snapshots | each section; `hadoop/pipeline/Pipeline.scala:73-77`, `:114-115` |
| Output root on EMR | default `s3://{data-bucket}/curated/hadoop` (`{data-bucket}` is resolved by the script from the Terraform outputs); DBLP input under `s3://{data-bucket}/raw/…` | `scripts/run-bounded-pipeline.sh:131-133`, `:1397` |
| Output root locally | `<output dir>/real` and `<output dir>/synthetic` of the local runner; default `<output dir>` is `modules/hadoop/target/local-bounded-run` | `test/LocalBoundedPipeline.scala:57`, `:64`, `:94` |
| Shared configuration keys | output behaviour, reject preview, decompression guard, internal line limit (`scholarchain.internal.maxLineBytes`, 64 MiB) | `defaults.xml:31-88` |
| Code version | `scholarchain.code.version` has no default; every run must set it | `defaults.xml:25-26`; `hadoop/pipeline/ScholarChainHadoop.scala:17` |
| Driver | `ScholarChainHadoop` (`pipeline --output … --dblp … --dblp-snapshot …`) | `hadoop/pipeline/ScholarChainHadoop.scala:8-24` |
| Settings tests | every key used by the jobs has a default; missing or malformed values fail | `test/SettingsSuite.scala:11`, `:46` |

## 2. J01 DBLP extract

| Item | Contract | Source |
|---|---|---|
| Job name | `hadoop.j01-dblp-extract`, dataset `dblp`, scope `dblp/snapshot=<id>` | `hadoop/dblp/DblpExtractJob.scala:41-42`, `:71` |
| Input | DBLP XML files (recursive), through `DblpXmlInputFormat`; map in `LongWritable`, `RawRecord` (one record element) | `hadoop/dblp/DblpExtractJob.scala:75-77`, `:84` |
| Map output | `W\|<dblp key>` → `DblpParsed` JSON (work plus mentions); `V\|<venue key>` → venue label | `hadoop/dblp/DblpExtractJob.scala:28`, `:214-221` |
| Reduce | identical copies of a work collapse (DUPLICATE); differing copies are rejected (CONFLICT); one `Venue` per key, most frequent label | `hadoop/dblp/DblpExtractJob.scala:243-285` |
| Outputs | `works/`, `mentions/`, `venues/` plus the accounting outputs | `hadoop/dblp/DblpExtractJob.scala:46-48`, `:73` |
| Classes | mapper `DblpExtractMapper`, reducer `DblpExtractReducer` | `hadoop/dblp/DblpExtractJob.scala:78-79`, `:84`, `:230` |
| Configuration | policy key `scholarchain.dblp.parsePolicyVersion`; reducers `scholarchain.dblp.reducers` (4); record elements, subset rule, encodings, record and prolog limits, DTD path, expansion limit | `hadoop/dblp/DblpExtractJob.scala:68`, `:72`; `defaults.xml:91-179` |
| Tests and fixtures | `DblpExtractJobSuite` on [`datasets/samples/dblp/dblp-sample.xml`](../datasets/samples/dblp/dblp-sample.xml) (`test/DblpExtractJobSuite.scala:22-23`) and on generated inputs (entity blow-up, truncation, gzip, split safety, `:127`, `:164`, `:177`, `:196`); `DblpParsingSuite` (`test/DblpParsingSuite.scala:10`); `ScannerSuite` (`test/ScannerSuite.scala:9`); contract suites `JobLevelDblpAdversarialSuite` (`contracts/JobLevelDblpAdversarialSuite.scala:14`) and `XmlBoundaryAdversarialSuite` (`contracts/XmlBoundaryAdversarialSuite.scala:13`) with the fixtures under [`tests/contracts/src/test/resources/hadoop-adversarial/`](../tests/contracts/src/test/resources/hadoop-adversarial/) | as cited |

## 3. J02 source normalizers

| Item | Contract | Source |
|---|---|---|
| Job name | `hadoop.j02-normalize-<kind>`, one run per dataset and kind (`openalex-work`, `openalex-author`, `crossref-work`, `orcid-record`); scope `<datasetId>/snapshot=<id>` | `hadoop/sources/NormalizeJob.scala:23`, `:36-38`, `:44`; `hadoop/sources/SourceNormalizers.scala:106` |
| Input | raw JSON Lines of one kind, `BoundedLineInputFormat` capped by `scholarchain.normalize.maxLineBytes` (4 MiB) | `hadoop/sources/NormalizeJob.scala:49-52`; `defaults.xml:231-236` |
| Map output | `R\|<source key>` → `NormalizedSource` JSON; `I\|<institution ID>` → Institution JSON; `T\|<topic ID>` → Topic JSON | `hadoop/sources/NormalizeJob.scala:79-81` |
| Reduce | identical copies collapse; differing copies of one key are all rejected | `hadoop/sources/NormalizeJob.scala:91-116` |
| Outputs | `normalized/`, `institutions/`, `topics/` | `hadoop/sources/NormalizeJob.scala:24-26`, `:46` |
| Classes | mapper `NormalizeMapper`, reducer `NormalizeReducer` | `hadoop/sources/NormalizeJob.scala:53-54`, `:60`, `:85` |
| Configuration | policy `scholarchain.normalize.policyVersion`; reducers `scholarchain.normalize.reducers` (2); `scholarchain.normalize.keepCrossrefAbstracts` | `hadoop/sources/NormalizeJob.scala:41`, `:45`; `defaults.xml:226-247` |
| Tests and fixtures | `NormalizerSuite` (`test/NormalizerSuite.scala:11`); `PipelineSuite` J02 rejects (`test/PipelineSuite.scala:82`); the bounded pipeline over [`datasets/samples/`](../datasets/samples/) OpenAlex, Crossref and ORCID files (`test/LocalBoundedPipeline.scala:31-56`) | as cited |

J02 did not run on EMR: the Phase 14 and 15 runs read DBLP only ([`docs/data-lineage.md`](data-lineage.md#2-sources)).

## 4. J03 and J04 exact identifier join

| Item | Contract | Source |
|---|---|---|
| Job name | `hadoop.j04-exact-id-join` (J03's keyed indexes and J04's join are one job); dataset `scholarchain-hadoop`; scope `linked/<label>` | `hadoop/ids/ExactIdJoinJob.scala:15`, `:25`, `:38`, `:43`; `hadoop/pipeline/Pipeline.scala:115` |
| Input | J07 `mentionContexts/` and J02 `normalized/`, both `BoundedLineInputFormat` under the internal line limit | `hadoop/ids/ExactIdJoinJob.scala:47-64`; `hadoop/pipeline/Pipeline.scala:119-126` |
| Map output | `doi\t<doi>` → `C` + MentionContext JSON or `S` + NormalizedSource JSON; records without a key count as MISSING_KEY_RECORDS | `hadoop/ids/ExactIdJoinJob.scala:17-19`, `:79`, `:92` |
| Reduce | `ExactLinker` per DOI group, bounded by `scholarchain.idjoin.maxGroupSize` (20000) | `hadoop/ids/ExactIdJoinJob.scala:20-21`, `:104`; `defaults.xml:256-261` |
| Outputs | `links/` (ExternalIdLink), `conflicts/v1/` | `hadoop/ids/ExactIdJoinJob.scala:20-22`, `:26` |
| Classes | mappers `ContextJoinMapper`, `SourceJoinMapper` (via `MultipleInputs`); reducer `ExactIdJoinReducer` | `hadoop/ids/ExactIdJoinJob.scala:54`, `:62`, `:65`, `:70`, `:83`, `:96` |
| Configuration | policy `scholarchain.idjoin.policyVersion`; reducers `scholarchain.idjoin.reducers` (4) | `hadoop/ids/ExactIdJoinJob.scala:40`, `:44`; `defaults.xml:250-266` |
| Tests and fixtures | `LinkAndBlockSuite` (`test/LinkAndBlockSuite.scala:104`, `:112`, `:150`); `PipelineSuite` over the samples (67 links, `test/PipelineSuite.scala:14`, `:33`) | as cited |

## 5. J05 blocking

| Item | Contract | Source |
|---|---|---|
| Job name | `hadoop.j05-blocking`, scope `dblp/snapshot=<id>` | `hadoop/blocking/BlockingJob.scala:348`, `:366` |
| Input | J07 `mentionContexts/`, `BoundedLineInputFormat` | `hadoop/blocking/BlockingJob.scala:372-375` |
| Map output | key `<family>\t<key>` plus mention ID and content hash as secondary sort fields → `BlockMember` JSON, one per configured family and key | `hadoop/blocking/BlockingJob.scala:329-333`, `:401-406` |
| Partition and grouping | `TwoFieldPrefixPartitioner` and `TwoFieldPrefixGroupingComparator`: one reduce call per block, values in a fixed order | `hadoop/blocking/BlockingJob.scala:377-379` |
| Reduce | buffers at most the effective cap of members; larger blocks are not emitted and their members are streamed to `oversizeMembers/` (see [section 13.1](#131-j05-applies-the-block-cap-in-the-reducer-check-hadoop-04)) | `hadoop/blocking/BlockingJob.scala:437-497` |
| Outputs | `blocks/` (CandidateBlock), `oversizeBlocks/`, `oversizeMembers/`; `blocks/` and `oversizeMembers/` always exist | `hadoop/blocking/BlockingJob.scala:349-351`, `:368`, `:382` |
| Classes | mapper `BlockingMapper`, reducer `BlockingReducer` | `hadoop/blocking/BlockingJob.scala:376`, `:380`, `:386`, `:411` |
| Configuration | policy `scholarchain.blocking.policyVersion`; reducers `scholarchain.blocking.reducers` (4); `keyFamilies`, `maxBlockSize` (200), `emitSingletons`; also reads `scholarchain.candidates.maxPairsPerBlock` (125000) | `hadoop/blocking/BlockingJob.scala:363`, `:367`; `defaults.xml:269-297`, `:338-354`, `:366-380` |
| Tests and fixtures | `FallbackBlockingSuite` J05 (`test/FallbackBlockingSuite.scala:269`) on a generated corpus (`:244`); `BlockingDeterminismSuite` (`test/BlockingDeterminismSuite.scala:101`, `:127`); contract `JobLevelBlockingSuite` (`contracts/JobLevelBlockingSuite.scala:116`, `:132`) | as cited |

## 6. J05b fallback blocking

| Item | Contract | Source |
|---|---|---|
| Job name | `hadoop.j05b-fallback-blocking`, scope `dblp/snapshot=<id>` | `hadoop/blocking/FallbackJobs.scala:107`, `:131` |
| Input | J05 `oversizeMembers/` (OversizeMember), `BoundedLineInputFormat` | `hadoop/blocking/FallbackJobs.scala:76-77`, `:138-141` |
| Map output | `<family>.<kind>\t<primary key>\t<value parts>` → `BlockMember` JSON, per configured secondary kind | `hadoop/blocking/FallbackJobs.scala:77-82` |
| Reduce | like J05, capped by the effective limit of `scholarchain.blocking.fallback.maxBlockSize` and `maxPairsPerBlock` | `hadoop/blocking/FallbackJobs.scala:84-93` |
| Outputs | `blocks/`, `fallbackOversizeBlocks/`, `coverage/`; `blocks/` and `coverage/` always exist | `hadoop/blocking/FallbackJobs.scala:108-110`, `:133`, `:145` |
| Classes | mapper `FallbackBlockingMapper`, reducer `FallbackBlockingReducer` | `hadoop/blocking/FallbackJobs.scala:142-143`, `:163`, `:198` |
| Configuration | policy `scholarchain.blocking.policyVersion`; reducers `scholarchain.blocking.fallback.reducers` (4); `fallback.families`, `fallback.secondaryKeys`, `fallback.maxBlockSize` (200) | `hadoop/blocking/FallbackJobs.scala:128`, `:132`; `defaults.xml:298-337` |
| Tests and fixtures | `FallbackBlockingSuite` J05b (`test/FallbackBlockingSuite.scala:319`, `:515`, `:594`); contract `FallbackPipelineEndToEndSuite` (`contracts/FallbackPipelineEndToEndSuite.scala:31`) | as cited |

## 7. J05c fallback coverage

| Item | Contract | Source |
|---|---|---|
| Job name | `hadoop.j05c-fallback-coverage`, scope `dblp/snapshot=<id>` | `hadoop/blocking/FallbackJobs.scala:317`, `:335` |
| Input | J05 `oversizeMembers/` and J05b `coverage/`, through `MultipleInputs` | `hadoop/blocking/FallbackJobs.scala:303-304`, `:341-352` |
| Map output | `<family>\t<primary key>\t<mention ID>` → `M` + UncoveredMember JSON or `C` + fallback family | `hadoop/blocking/FallbackJobs.scala:304-306` |
| Partitioner | `TwoFieldPrefixPartitioner` (all members of one oversize block on one reduce task) | `hadoop/blocking/FallbackJobs.scala:305-306`, `:353` |
| Outputs | `uncoveredMembers/`, `oversizeKeySummary/` | `hadoop/blocking/FallbackJobs.scala:318-319`, `:337` |
| Classes | mappers `CoverageMemberMapper`, `CoverageRecordMapper`; reducer `FallbackCoverageReducer` | `hadoop/blocking/FallbackJobs.scala:345`, `:351`, `:354`, `:362`, `:382`, `:416` |
| Configuration | policy `scholarchain.blocking.policyVersion`; reducers `scholarchain.blocking.fallback.reducers` (4) | `hadoop/blocking/FallbackJobs.scala:332`, `:336`; `defaults.xml:333-337` |
| Tests and fixtures | `FallbackBlockingSuite` J05c (`test/FallbackBlockingSuite.scala:370`, `:716`) | as cited |

## 8. J06 candidate features

| Item | Contract | Source |
|---|---|---|
| Job name | `hadoop.j06-candidate-features`, scope `dblp/snapshot=<id>` | `hadoop/candidates/CandidateFeatureJob.scala:193`, `:225` |
| Input | J05 and J05b `blocks/` (CandidateBlock v2), `BoundedLineInputFormat` | `hadoop/candidates/CandidateFeatureJob.scala:165`, `:211-235`; `hadoop/pipeline/Pipeline.scala:175-179` |
| Map output | `<family>\t<blockKey>\t<shard>` → `BlockShard` for each of ceil(pairs / pairsPerShard) shards | `hadoop/candidates/CandidateFeatureJob.scala:160`, `:173-176` |
| Reduce | streams the shard's member pairs into `candidates/family=<family>/` as MatchCandidate records | `hadoop/candidates/CandidateFeatureJob.scala:178-184`, `:202` |
| Outputs | `candidates/`, `oversizeBlocks/`, `unexpandedMembers/` | `hadoop/candidates/CandidateFeatureJob.scala:194-200`, `:227` |
| Classes | mapper `BlockShardMapper`, reducer `CandidateFeatureReducer` | `hadoop/candidates/CandidateFeatureJob.scala:236-237`, `:252`, `:343` |
| Configuration | policy key `scholarchain.candidates.featureSet` (must equal the implemented set); reducers `scholarchain.candidates.reducers` (4); `maxPairsPerBlock` (125000), `pairsPerShard` (50000), `skipSameNumberedName` (true) | `hadoop/candidates/CandidateFeatureJob.scala:222`, `:226`, `:242-249`; `defaults.xml:357-403` |
| Tests and fixtures | `CandidateSuite` (`test/CandidateSuite.scala:298`, `:379`, `:456`); `FallbackBlockingSuite` (`test/FallbackBlockingSuite.scala:433`, `:764`); contract `JobLevelBlockingSuite` (`contracts/JobLevelBlockingSuite.scala:287`, `:308`) and `PairLimitAdversarialSuite` (`contracts/PairLimitAdversarialSuite.scala:32`) | as cited |

## 9. J07 publication teams

| Item | Contract | Source |
|---|---|---|
| Job name | `hadoop.j07-publication-teams`, scope `dblp/snapshot=<id>` | `hadoop/teams/PublicationTeamJob.scala:23`, `:43` |
| Input | J01 `works/` and `mentions/`, through `MultipleInputs` and `BoundedLineInputFormat` | `hadoop/teams/PublicationTeamJob.scala:47-60` |
| Map output | `<work ID>` → `W` + WorkRecord JSON or `M` + AuthorMention JSON | `hadoop/teams/PublicationTeamJob.scala:16-17`, `:107`, `:117` |
| Reduce | one team per work; teams above `hardLimit` are rejected (see [section 13.2](#132-teams-over-the-hard-limit-are-rejected-check-hadoop-pairs-wrong)) | `hadoop/teams/PublicationTeamJob.scala:138-201` |
| Outputs | `teams/` (PublicationTeam), `authorPapers/`, `mentionContexts/`, `oversizeTeams/` | `hadoop/teams/PublicationTeamJob.scala:24-27`, `:45` |
| Classes | mappers `TeamWorkMapper`, `TeamMentionMapper`; reducer `PublicationTeamReducer` | `hadoop/teams/PublicationTeamJob.scala:53`, `:59`, `:61`, `:101`, `:111`, `:121` |
| Configuration | policy `scholarchain.dblp.parsePolicyVersion`; reducers `scholarchain.teams.reducers` (4); `oversizeThreshold` (100), `hardLimit` (5000), `maxCoauthorContext` (50) | `hadoop/teams/PublicationTeamJob.scala:40`, `:44`; `defaults.xml:182-204` |
| Tests and fixtures | `DblpChainSuite` on [`datasets/samples/dblp/dblp-sample.xml`](../datasets/samples/dblp/dblp-sample.xml) (`test/DblpChainSuite.scala:26`, `:31`) and a generated giant team (`:202`); `PairsAndTeamsSuite` (`test/PairsAndTeamsSuite.scala:133`); contract `JobLevelTeamScaleSuite` (`contracts/JobLevelTeamScaleSuite.scala:48`, `:114`, `:129`) | as cited |

## 10. J08a pair expansion

| Item | Contract | Source |
|---|---|---|
| Job name | `hadoop.j08a-pair-expand`, scope `dblp/snapshot=<id>` | `hadoop/pairs/PairJobs.scala:30`, `:41` |
| Input | J07 `teams/` (PublicationTeam), `BoundedLineInputFormat` | `hadoop/pairs/PairJobs.scala:45-48` |
| Map output | `<work ID>\t<shard, 9 digits>` → `PairShard` for each of ceil(n(n-1)/2 / pairsPerShard) shards; teams under two authors yield none | `hadoop/pairs/PairJobs.scala:16`, `:69-80` |
| Reduce | streams the shard's position pairs; self pairs are skipped and listed in `issues/v1/` | `hadoop/pairs/PairJobs.scala:96-138` |
| Outputs | `pairInstances/` (CoauthorPairCandidate, before consolidation) | `hadoop/pairs/PairJobs.scala:31`, `:43` |
| Classes | mapper `PairShardMapper`, reducer `PairExpandReducer` | `hadoop/pairs/PairJobs.scala:49-50`, `:55`, `:90` |
| Configuration | policy `scholarchain.dblp.parsePolicyVersion`; reducers `scholarchain.pairs.expandReducers` (4); `scholarchain.pairs.pairsPerShard` (100000) | `hadoop/pairs/PairJobs.scala:38`, `:42`; `defaults.xml:207-218` |
| Tests and fixtures | `DblpChainSuite` (`test/DblpChainSuite.scala:26`, `:202`); `PairsAndTeamsSuite` (`test/PairsAndTeamsSuite.scala:23`, `:37`, `:54`) | as cited |

## 11. J08b pair consolidation

| Item | Contract | Source |
|---|---|---|
| Job name | `hadoop.j08b-pair-consolidate`, scope `dblp/snapshot=<id>` | `hadoop/pairs/PairJobs.scala:155`, `:171` |
| Input | J08a `pairInstances/`, `BoundedLineInputFormat` | `hadoop/pairs/PairJobs.scala:175-178` |
| Map output | `<work ID>\t<left>\t<right>` → pair JSON | `hadoop/pairs/PairJobs.scala:226` |
| Reduce | one CoauthorPairCandidate per work and name pair, positions unioned; re-stamped with the J08b provenance | `hadoop/pairs/PairJobs.scala:143-153`, `:236-252` |
| Outputs | `pairs/` | `hadoop/pairs/PairJobs.scala:156`, `:173` |
| Classes | mapper `PairInstanceMapper`, reducer `PairConsolidateReducer` | `hadoop/pairs/PairJobs.scala:179-180`, `:220`, `:230` |
| Configuration | policy `scholarchain.dblp.parsePolicyVersion`; reducers `scholarchain.pairs.consolidateReducers` (4) | `hadoop/pairs/PairJobs.scala:168`, `:172`; `defaults.xml:219-223` |
| Tests and fixtures | `DblpChainSuite` (`test/DblpChainSuite.scala:26`, `:185`); `PairsAndTeamsSuite` (`test/PairsAndTeamsSuite.scala:74`) | as cited |

## 12. J09 graph export

| Item | Contract | Source |
|---|---|---|
| Job name | `hadoop.j09-graph-export`, dataset `scholarchain-hadoop`, scope `linked/<label>` | `hadoop/graphexport/GraphExportJob.scala:83`, `:96`, `:101`; `hadoop/pipeline/Pipeline.scala:115`, `:220` |
| Input | works, venues (J01), authorPapers, teams (J07), pairs (J08b), links (J04), candidates (J06, `family=*`), institutions, topics (J02), each through its own mapper and `BoundedLineInputFormat`; missing inputs are left out | `hadoop/graphexport/GraphExportJob.scala:71-73`, `:107-109`; `hadoop/pipeline/Pipeline.scala:187-208` |
| Map output | `<entity>\t<year or empty>\t<record ID>` → record JSON | `hadoop/graphexport/GraphExportJob.scala:74`, `:174` |
| Reduce | one record per ID; identical copies collapse; links, institutions and topics merge when they agree on every fact; other disagreement is rejected | `hadoop/graphexport/GraphExportJob.scala:74-76`, `:129-130`, `:194-214` |
| Outputs | named output `exported`, written to `<entity>/year=<YYYY>/` for work, publication_team and coauthor_pair_candidate, `<entity>/` otherwise | `hadoop/graphexport/GraphExportJob.scala:78-80`, `:84`, `:196` |
| Entities | `work`, `author_mention`, `publication_team`, `coauthor_pair_candidate`, `external_id_link`, `institution`, `topic`, `venue`, `match_candidate` | `hadoop/graphexport/GraphExportJob.scala:25-64` |
| Classes | mappers `WorkExportMapper` … `CandidateExportMapper` (one per entity); reducer `GraphExportReducer` | `hadoop/graphexport/GraphExportJob.scala:114-124`, `:178-186`, `:188` |
| Configuration | policy `scholarchain.export.policyVersion`; reducers `scholarchain.export.reducers` (4) | `hadoop/graphexport/GraphExportJob.scala:98`, `:102`; `defaults.xml:406-416` |
| Tests and fixtures | `PipelineSuite` (`test/PipelineSuite.scala:14`); `CandidateSuite` (`test/CandidateSuite.scala:489`); `FallbackBlockingSuite` (`test/FallbackBlockingSuite.scala:433`); `LinkAndBlockSuite` export merge (`test/LinkAndBlockSuite.scala:194`) | as cited |

## 13. Known limitations and their rationale

Both items describe existing code. No code was changed; a change would belong to hadoop-engineer and is out of scope after the release candidate.

### 13.1 J05 applies the block cap in the reducer (check HADOOP-04)

- **What the code does.** `BlockingMapper` emits every member for every key of every configured family (`hadoop/blocking/BlockingJob.scala:401-406`, emit at `:405`). `BlockingReducer` counts the values of one block. Past the effective cap (200 with the defaults, `defaults.xml:281-297`) it stops buffering and streams every member to `oversizeMembers/` (`hadoop/blocking/BlockingJob.scala:444-456`; comment "The block is oversize: stream its members out instead of buffering them." at `:449`). The block is counted (OVERSIZE_BLOCKS) and listed in `oversizeBlocks/` (`:458-475`).
- **The limitation.** Memory per reduce call stays bounded by the cap. But the whole oversize group is still shuffled to one reducer and read in one reduce call. There is no pre-pass that counts key sizes, no key salting and no sampling.
- **Recorded size.** In Phase 15 experiment A, attempt 2, the largest J05 group had 5,127 members (stat `largestBlock`). The same run counted 590 oversize blocks with 323,113 oversize members ([`hadoop-job-counters.csv`](../experiments/raw/p15-a-hadoop/20261120T225632Z-eb786c5275a3-5c75/hadoop-job-counters.csv), lines 27, 28 and 30). The run completed.
- **Consequence.** The reduce task that holds the largest key does more work than the others (skew). Oversize members go on to the J05b fallback and the J05c coverage accounting (sections 6 and 7), so they are counted, not lost.
- **Tests.** `test/FallbackBlockingSuite.scala:269`; `contracts/JobLevelBlockingSuite.scala:116`.

### 13.2 Teams over the hard limit are rejected (check HADOOP-PAIRS-WRONG)

- **What the code does.** J07 rejects a team with more than `scholarchain.teams.hardLimit` authors (default 5000, `defaults.xml:188-193`) with reason `team_exceeds_hard_limit` (`hadoop/common/Disposition.scala:112`). It writes no team and therefore J08 makes no pairs (`hadoop/teams/TeamAssembler.scala:75-76`). The reducer also stops buffering mentions past `hardLimit + 1` and rejects the team (`hadoop/teams/PublicationTeamJob.scala:148-151`, `:159-165`).
- **How it is counted.** The rejection is deliberate and visible in three places:
  - the counter OVERSIZE_AUTHOR_TEAMS (`hadoop/teams/PublicationTeamJob.scala:169`; `hadoop/common/Counters.scala:47`);
  - the per-reason counter `scholarchain.reject-reason:team_exceeds_hard_limit` and REJECTED_REDUCE_RECORDS (`hadoop/common/TaskIO.scala:46-47`);
  - one reject record in `rejects/v1/` (`hadoop/common/TaskIO.scala:60`, `:125`).

  OVERSIZE_AUTHOR_TEAMS also counts teams above the oversize threshold (100) that are still expanded (`hadoop/teams/PublicationTeamJob.scala:189-190`). The per-reason counter is therefore the exact count of rejected teams.
- **Why.** The defaults file gives the reason: 5,000 authors would expand to 12,497,500 pairs (`defaults.xml:191-192`). Teams of 101 to 5,000 authors are expanded in full, split into shards over the reducers (`defaults.xml:207-213`).
- **Recorded effect.** None in the recorded run. In Phase 15 experiment A, attempt 2, the largest team had 137 authors (stat `maxTeamSize`) and OVERSIZE_AUTHOR_TEAMS was 15. The J07 rows of that file have no reject counter ([`hadoop-job-counters.csv`](../experiments/raw/p15-a-hadoop/20261120T225632Z-eb786c5275a3-5c75/hadoop-job-counters.csv), lines 74-83). No team reached the hard limit.
- **Tests.** `contracts/JobLevelTeamScaleSuite.scala:114`, `:129`; `test/DblpChainSuite.scala:202`; `test/PairsAndTeamsSuite.scala:133`.
