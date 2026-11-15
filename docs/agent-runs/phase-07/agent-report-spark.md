STATUS: COMPLETE
OWNER: spark-engineer
WORK ORDER: docs/agent-runs/phase-07/prompt.md, "Human architect decisions -> 5. JSON Lines export of the Phase 6 run (spark-engineer)" only.

Summary: I added an export entry point. It exported all 65 tables in the four load manifests, 12,571,066 records in total. Every count matches its load-manifest count, and the run ID stays the same. Every written line was decoded again with `Contract.decode` and re-encoded to the same text, with 0 failures. A 9-test round-trip suite passes, and the final `sbt check` passed (output observed). attempt-03 was only read; no file under it is newer than the start of this work.

FILES CREATED:
- /Users/brian/Developer/repos/scholarchain/modules/spark/src/main/scala/org/scholarchain/spark/JsonlExport.scala. It holds `JsonlCodec` (one line codec per table kind), `JsonlExport.run(spark, sourceRunDir, outRoot, recordsPerFile = 250000, maxFiles = 32)`, the spark-submit entry point `JsonlExportJob.main` (`--source-run DIR --out-root DIR [--records-per-file N]`), and three error types: ExportValidationException, ExportCountMismatchException and ExportRefusedException.
- /Users/brian/Developer/repos/scholarchain/modules/spark/src/test/scala/org/scholarchain/spark/LocalJsonlExport.scala. This is the test-scope local runner, like LocalRealRun, because Spark is a Provided dependency. It also prints the report figures (property keys, text lengths, sample lines), computed from the exported JSONL.
- /Users/brian/Developer/repos/scholarchain/modules/spark/src/test/scala/org/scholarchain/spark/JsonlExportSuite.scala. The round-trip tests (9).
- Generated output, untracked, under target/: /Users/brian/Developer/repos/scholarchain/modules/spark/target/phase-07-export/run=51b50d578d5b609842c1c21b8266a00b/ (93 `part-NNNNN.jsonl` files, `_export_manifest.json`, `_export_report.json`). `phase-07-export/_spark-local` is also there, empty (0 B).

FILES MODIFIED: none. No existing file in modules/spark or anywhere else was changed. build.sbt, core and attempt-03 are untouched.

COMMANDS RUN (each sbt run in the background, one at a time):
- Read-only inspection: cat, sed -n, grep, ls, du, df, find (no -delete or -exec), wc, shasum, git status, git diff --stat, git log. I read only small metadata files: the work order, the domain report, the 4 load manifests, `_run_manifest.json` and the export manifest and report. I did not cat any data file.
- `sbt -batch "sparkAnalytics/Test/compile"`, 3 runs:
  - run 1 failed: two Scala 3 migration errors (inferred `Right` return type);
  - run 2 failed: wildcard-typed dispatch in exportAny;
  - run 3 passed (`[success]`).
- `sbt -batch "sparkAnalytics/testOnly org.scholarchain.spark.JsonlExportSuite"`: "Passed: Total 9, Failed 0".
- `sbt -batch "sparkAnalytics/Test/runMain org.scholarchain.spark.LocalJsonlExport --source-run <repo>/modules/spark/target/phase-06-real/attempt-03/run=51b50d578d5b609842c1c21b8266a00b --out-root <repo>/modules/spark/target/phase-07-export --master local[6] --shuffle-partitions 32"` (wrapped in /usr/bin/time -p): exit 0.
- `sbt -batch check` (first run): FAILED at `sparkAnalytics / Compile / scalafmtCheck` ("1 files must be formatted"). JsonlExport.scala had been changed with sed after the format hook ran.
- `sbt -batch "sparkAnalytics/scalafmt; sparkAnalytics/Test/scalafmt"`: "Reformatted 1 Scala sources". This was whitespace only, in my own file.
- `sbt -batch check` (second run): exit 0.

TEST RESULTS:
- Final `sbt check`:
  - "Module tests: suites=54 executed=1289 passed=1289 failed=0 errors=0 skipped=0";
  - "Contract tests (no AWS): suites=22 executed=336 passed=336 failed=0";
  - "Updated .claude/.last-test-run (module_tests=1289, contract_tests=336)";
  - "[success] Total time: 816 s".

  Module tests went from 1280 to 1289, which is the 9 new tests.
- JsonlExportSuite, 9 of 9 passed. It runs the fixture pipeline, then exports with recordsPerFile=7 so tables span several files. The tests:
  1. Every load-manifest table is exported. The record count, the run ID, the directory name `run=<hex>`, the file names `part-NNNNN.jsonl`, and the bytes and sha256 in the manifest all match the files.
  2. Each line decodes with Contract.decode to exactly the stored row's core record (`toCore`). This is checked for nodes:researcher, nodes:work, coauthor_edge, bridges, researcher-profile summaries and path_evidence. The line equals `Contract.encode(decoded)`. Every summary's contentHash equals ContentHash.of(snippet). Lines are sorted within each file.
  3. sourceColumnsNotExported is recorded for path_evidence and is empty for nodes.
  4. Determinism: a second export gives a byte-identical manifest, so every file sha256 matches.
  5. An existing export directory is refused (never overwritten).
  6. A load-manifest count raised by 1 (authorship) fails with ExportCountMismatchException, and no `_export_manifest.json` is written.
  7. Load manifests that disagree on a shared table are refused.
  8. A row failing core validation (bad nodeId, unknown label) is not encoded, and a malformed line fails `check`.
  9. Argument parsing.

VALIDATION RESULTS (real export, run `run:v1:51b50d578d5b609842c1c21b8266a00b`):
- Source checks:
  - Before export, the source was verified against its `_run_manifest.json`: every file's size and sha256 matched, with no unlisted files (7.2 s).
  - `_run_manifest.json` sha256: b7319503ba8aa2e28b3a7301653340c73c392ef32c28fad96408a77053f1b024.
  - Load-manifest sha256s are recorded in `_export_manifest.json`.
- Counts and checks:
  - 65 distinct tables, all exported. Every exported count equals its load-manifest count (table below).
  - Every written line was re-read by Spark, decoded and re-encoded identically: 0 failures. A Spark line count and an independent LF count (taken while computing sha256) were both checked against the load-manifest counts.
- Output:
  - 93 `.jsonl` files and 0 `_SUCCESS` files. There are 93 hidden `.part-NNNNN.jsonl.crc` files, which the Hadoop local file system wrote and which were renamed together with their data files.
  - `_export_manifest.json` sha256: f6ebe62de6bd5aac13784ac74a5da22be0a65c02603a2d7138a2c012dd173021.
- attempt-03: `find phase-06-real/attempt-03 -newer <suite log>` returned nothing.
- The run ID is unchanged: the export manifest has `sourceRunId == runId == run:v1:51b50d578d5b609842c1c21b8266a00b`.

ASSUMPTIONS:
1. "One Contract.encode record per line" is taken strictly. Three table kinds store extra columns next to the core EvidenceItem, and only the core `evidence` record is exported for them. The dropped columns are listed per table in the manifest as `sourceColumnsNotExported`:
   - `evidence:metric:*` drops researcherId and works (work team sizes). The researcher ID and the M_r work IDs are still in subjectIds.
   - `path_evidence` drops pairKind, pairIndex, sourceId, targetId, hopCount and hops. The path order is still in subjectIds, and each hop's edge facts can be joined from coauthor_edge for the same window.
   - `semantic_summary` drops summaryKind, which is still in the table path `summary_kind=...`.
2. `rejects` and `integrity_violations` have no core record (coreRecord null in the manifests, 0 rows each). They are exported in their versioned JSON object form (`scholarchain.spark.reject` v1 and `scholarchain.spark.integrity-violation` v1). Each table produced one empty file.
3. Empty tables (institution, topic, unresolved_mention, rejects, integrity_violations) produce one 0-byte `part-00000.jsonl`.
4. Files per table = ceil(records / 250,000), at most 32. Lines are hash-partitioned by the full line text and sorted within each file, so the bytes are deterministic. `spark.sql.shuffle.partitions` = 32 as ordered, but the per-table repartition count above sets the file count.
5. The real export ran with the code as it was just before the whitespace-only scalafmt pass. Logic is identical; the suite and `sbt check` ran after formatting.

RISKS:
- The exported evidence tables lose the extra columns listed in assumption 1. If storage's EvidenceBundle needs path hops with per-hop edge IDs, it must join coauthor_edge, or ask for a sidecar export (PROPOSED LATER 1).
- Spark summaryKind values versus SemanticDocumentType wire names: the files carry summaryKind only in the path segment, as `researcher_profile`, `community` and `path`. SemanticDocumentType uses `researcher_profile`, `community_summary` and `collaboration_path`. rag must map them explicitly; nothing in the export renames them.
- In community summaries, subjectIds repeats the group ID when the group ID is also a top member (visible in the sample below).
- The researcher-profile venue list renders a venue named "ACL (1)" with its count as "ACL (1) (2)". This is a Phase 6 template issue, reported here, not changed.
- Renaming the part files uses Hadoop `FileSystem.rename`. That is cheap locally but a copy on S3. The exporter is meant for local runs, and storage reads the local directory now.
- About 7.5 GB of JSONL now sits under modules/spark/target/ (316 GiB free before the run).

MANUAL ACTION REQUIRED: None. Nothing was committed and nothing was written under docs/agent-runs/.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: storage-engineer (decision 6) consumes `modules/spark/target/phase-07-export/run=51b50d578d5b609842c1c21b8266a00b/` through `_export_manifest.json`. code-reviewer (decision 9) reviews afterwards.

## Export entry point and output format

- Cluster: `JsonlExportJob.main --source-run <run dir> --out-root <root>`.
- Local: `sbt "sparkAnalytics/Test/runMain org.scholarchain.spark.LocalJsonlExport --source-run DIR --out-root DIR --master local[6] --shuffle-partitions 32"`.
- Output: `<out-root>/run=<hex>/<same table path as the source>/part-NNNNN.jsonl`.
  - One record per line, UTF-8, LF.
  - Core tables: the line is exactly the `Contract.encode` output, with validation applied on the way out.
- Manifest and report:
  - `_export_manifest.json` (format `scholarchain.spark.jsonl-export` v1) is written last. It holds sourceRunId, runId, the source run-manifest and load-manifest sha256s, encoding, lineOrder and recordsPerFile. Per table: table, path, targets, coreRecord, schemaVersion, windowLabel, records, loadManifestRecords, sourceColumnsNotExported, and files (path, bytes, sha256, records). It also has totals.
  - `_export_report.json` holds the timings.
- Fail conditions:
  - count differs from the load manifest: ExportCountMismatchException;
  - load manifests disagree on a shared table: ExportCountMismatchException;
  - a row fails validation, or a line fails to decode and re-encode identically: ExportValidationException;
  - the source does not match its run manifest, the output directory already exists, the manifest's core record differs from the path's, or the schema version is not current: ExportRefusedException.

  In every fail case `_export_manifest.json` is not written.

## Per-table counts (exported vs load manifest), files and bytes

Every row matched (exported = load manifest).

| table | exported | load manifest | files | bytes |
|---|---|---|---|---|
| nodes:researcher | 200,484 | 200,484 | 1 | 132,046,432 |
| nodes:work | 155,978 | 155,978 | 1 | 109,272,398 |
| nodes:venue | 16 | 16 | 1 | 6,021 |
| nodes:institution | 0 | 0 | 1 | 0 |
| nodes:topic | 0 | 0 | 1 | 0 |
| edges:authored | 758,314 | 758,314 | 4 | 428,448,054 |
| edges:published_in | 155,978 | 155,978 | 1 | 79,495,196 |
| edges:coauthored_with:full-2015-2025 | 1,612,421 | 1,612,421 | 7 | 948,950,945 |
| edges:coauthored_with:w2015-2017 | 104,067 | 104,067 | 1 | 60,785,596 |
| edges:coauthored_with:w2018-2020 | 248,445 | 248,445 | 1 | 145,184,706 |
| edges:coauthored_with:w2021-2023 | 569,486 | 569,486 | 3 | 333,116,883 |
| edges:coauthored_with:w2024-2025 | 826,665 | 826,665 | 4 | 481,931,274 |
| canonical_researcher | 200,484 | 200,484 | 1 | 118,338,557 |
| authorship | 758,328 | 758,328 | 4 | 438,762,777 |
| unresolved_mention | 0 | 0 | 1 | 0 |
| coauthor_edge:full-2015-2025 | 1,612,421 | 1,612,421 | 7 | 1,180,414,683 |
| coauthor_edge:w2015-2017 | 104,067 | 104,067 | 1 | 75,264,079 |
| coauthor_edge:w2018-2020 | 248,445 | 248,445 | 1 | 179,818,765 |
| coauthor_edge:w2021-2023 | 569,486 | 569,486 | 3 | 412,827,882 |
| coauthor_edge:w2024-2025 | 826,665 | 826,665 | 4 | 596,013,913 |
| metrics:breadth / group_size, bridges, kcore, components, communities, evidence:metric (full-2015-2025) | 200,484 each | 200,484 each | 1 each | 153,333,316 / 113,784,606 / 125,628,666 / 101,543,321 / 110,409,231 / 109,591,925 / 131,276,361 |
| the same 7 tables (w2015-2017) | 33,768 each | 33,768 each | 1 each | 25,750,116 / 19,091,249 / 21,000,911 / 17,091,183 / 18,538,529 / 18,446,638 / 22,162,793 |
| the same 7 tables (w2018-2020) | 58,729 each | 58,729 each | 1 each | 44,815,146 / 33,228,947 / 36,621,422 / 29,730,504 / 32,258,683 / 32,088,458 / 38,480,797 |
| the same 7 tables (w2021-2023) | 94,166 each | 94,166 each | 1 each | 71,928,437 / 53,319,855 / 58,889,591 / 47,682,530 / 51,750,707 / 51,460,930 / 61,584,665 |
| the same 7 tables (w2024-2025) | 116,301 each | 116,301 each | 1 each | 88,853,937 / 65,965,954 / 72,811,710 / 58,897,064 / 64,024,643 / 63,563,383 / 75,820,343 |
| path_evidence:full-2015-2025 | 64 | 64 | 1 | 49,492 |
| semantic_summary:researcher_profile:full-2015-2025 | 64,090 | 64,090 | 1 | 79,896,027 |
| semantic_summary:community:full-2015-2025 / w2015-2017 / w2018-2020 / w2021-2023 / w2024-2025 | 11,123 / 2,304 / 3,953 / 5,986 / 7,596 (total 30,962) | same | 1 each | 15,353,957 / 3,180,751 / 5,452,976 / 8,256,580 / 10,471,570 |
| semantic_summary:path:full-2015-2025 | 64 | 64 | 1 | 90,625 |
| rejects | 0 | 0 | 1 | 0 |
| integrity_violations | 0 | 0 | 1 | 0 |

Against the expected inputs in decision 10, everything matches:
- 200,484 researchers, 155,978 works, 16 venues, 758,328 authorships;
- 3,361,084 coauthored_with edges (1,612,421 + 104,067 + 248,445 + 569,486 + 826,665);
- 758,314 authored and 155,978 published_in edges;
- 503,448 (researcher, window) rows per metric family (200,484 + 33,768 + 58,729 + 94,166 + 116,301);
- 64,090 profiles, 30,962 community summaries and 64 path summaries.

authored (758,314) is 14 lower than authorship (758,328) because of the Phase 6 rule of one AUTHORED edge per (researcher, work).

## Output bytes and wall time

- Totals: 65 tables, 12,571,066 records, 93 files, 7,964,856,690 bytes. `du` shows 7.5G for the run directory.
- Wall time:
  - exporter 123.3 s (`wallMillis` 123,277), of which 7.2 s was source verification;
  - the runner's own clock: 123.29 s;
  - the whole sbt invocation including sbt start-up: real 131.19 s (user 393.25 s, sys 25.82 s).
- local[6], shuffle partitions 32, heapMax 6,144 MB.
- Largest tables: coauthor_edge full window 14.9 s, edges:coauthored_with full window 12.4 s.

## Semantic summaries: text length (characters of `snippet`, Spark `length`) and samples

| summary kind | n | min | median | max |
|---|---|---|---|---|
| researcher_profile | 64,090 | 544 | 581 | 627 |
| community (all 5 windows) | 30,962 | 400 | 422 | 455 |
| path | 64 | 370 | 391 | 1,197 |

Community per window (min/median/max): full 402/422/455, w2015-2017 400/423/454, w2018-2020 401/422/455, w2021-2023 402/421/454, w2024-2025 401/421/455.

Sample lines (the first line of the first file of each kind, verbatim):

researcher_profile:
{"schemaVersion":1,"evidenceId":"evidence:v1:000018e035a7d79fed98a163ee745972","evidenceType":"metric","subjectIds":["researcher:v1:248eaf5ca120ada893c47d79509189eb"],"window":{"startYear":2015,"endYear":2025},"metric":{"metric":"publication_count","value":4},"snippet":"Researcher Mohammed Khalilia (researcher:v1:248eaf5ca120ada893c47d79509189eb), window 2015-2025. Publications P=4; distinct coauthors C=6; total coauthor occurrences T=8; average coauthors per paper A=2.0000; maximum coauthors on one paper M=2; weighted degree W=8.0000. Connected component size 188797; community researcher:v1:248eaf5ca120ada893c47d79509189eb with 4 members; coreness 3; participation coefficient 0.6563; coauthors in other communities 3; other communities connected 2. Most frequent venues: ACL (1) (2), EMNLP (2). Identity: accepted, confidence 0.9500.","provenance":{"schemaVersion":1,"sourceType":"pipeline","datasetId":"scholarchain-graph","snapshotId":"dblp-2026-11-01","sourceRecordIds":["researcher:v1:248eaf5ca120ada893c47d79509189eb"],"runId":"run:v1:51b50d578d5b609842c1c21b8266a00b","jobName":"spark.semantic-summaries","policyVersion":"graph-v1","contentHash":"sha256:dcc727e5d090c5296f6360ea64b3e7012d9c2216e44628eca944294a9e4d75c2"}}

community:
{"schemaVersion":1,"evidenceId":"evidence:v1:0008797919c1e64892f9e9d29351c863","evidenceType":"metric","subjectIds":["researcher:v1:49f78087a3bed3ba7d1370fdd6ae3493","researcher:v1:9bcede3d7ba5e36d837f4dbae5b9bc6d","researcher:v1:49f78087a3bed3ba7d1370fdd6ae3493","researcher:v1:636f9f3b7465c30248de17606652d93e"],"window":{"startYear":2015,"endYear":2025},"metric":{"metric":"community_size","value":8},"snippet":"Community researcher:v1:49f78087a3bed3ba7d1370fdd6ae3493, window 2015-2025: 8 researchers, 28 coauthor edges inside the community, 66 coauthor edges to other communities. Members with the most distinct coauthors: Marti A. Hearst (researcher:v1:9bcede3d7ba5e36d837f4dbae5b9bc6d, C=50); Jerry Song (researcher:v1:49f78087a3bed3ba7d1370fdd6ae3493, C=12); Micah Yong (researcher:v1:636f9f3b7465c30248de17606652d93e, C=10).","provenance":{"schemaVersion":1,"sourceType":"pipeline","datasetId":"scholarchain-graph","snapshotId":"dblp-2026-11-01","sourceRecordIds":["researcher:v1:49f78087a3bed3ba7d1370fdd6ae3493","researcher:v1:9bcede3d7ba5e36d837f4dbae5b9bc6d","researcher:v1:49f78087a3bed3ba7d1370fdd6ae3493","researcher:v1:636f9f3b7465c30248de17606652d93e"],"runId":"run:v1:51b50d578d5b609842c1c21b8266a00b","jobName":"spark.semantic-summaries","policyVersion":"graph-v1","contentHash":"sha256:e88fdd16f985a2214b8b041686042ea917152e991916c31ff4b2d0c440c802ba"}}

path:
{"schemaVersion":1,"evidenceId":"evidence:v1:02efecce87019df973c7a68a6727fb36","evidenceType":"graph_path","subjectIds":["researcher:v1:4fc0d2e6fcd1968992095c0a7103298b","researcher:v1:b4a28f471385a8510612177f02c53511","researcher:v1:d8a620f3a185f0e98d43416247433b25","researcher:v1:bfbcabd0f011731a3f367019e14a1187","researcher:v1:d7762ba30acd5b294e1a1599a12cbad6","researcher:v1:c4c4d999ebf6b15b63913d24bcfc4971"],"window":{"startYear":2015,"endYear":2025},"metric":null,"snippet":"Collaboration path in window 2015-2025 from Ioannis Papoutsis (researcher:v1:4fc0d2e6fcd1968992095c0a7103298b) to Mengxia Ren (researcher:v1:c4c4d999ebf6b15b63913d24bcfc4971) with 5 hops (shortest within the depth cap). Hops: Ioannis Papoutsis (researcher:v1:4fc0d2e6fcd1968992095c0a7103298b) to Laura Leal-Taixé (researcher:v1:b4a28f471385a8510612177f02c53511): 1 joint publications, 2025-2025; Laura Leal-Taixé (researcher:v1:b4a28f471385a8510612177f02c53511) to Christoph Feichtenhofer (researcher:v1:d8a620f3a185f0e98d43416247433b25): 1 joint publications, 2022-2022; Christoph Feichtenhofer (researcher:v1:d8a620f3a185f0e98d43416247433b25) to Chao Li (researcher:v1:bfbcabd0f011731a3f367019e14a1187): 1 joint publications, 2022-2022; Chao Li (researcher:v1:bfbcabd0f011731a3f367019e14a1187) to Fan Zhang 0010 (researcher:v1:d7762ba30acd5b294e1a1599a12cbad6): 1 joint publications, 2022-2022; Fan Zhang 0010 (researcher:v1:d7762ba30acd5b294e1a1599a12cbad6) to Mengxia Ren (researcher:v1:c4c4d999ebf6b15b63913d24bcfc4971): 1 joint publications, 2025-2025.","provenance":{"schemaVersion":1,"sourceType":"pipeline","datasetId":"scholarchain-graph","snapshotId":"dblp-2026-11-01","sourceRecordIds":["researcher:v1:4fc0d2e6fcd1968992095c0a7103298b","researcher:v1:b4a28f471385a8510612177f02c53511","researcher:v1:d8a620f3a185f0e98d43416247433b25","researcher:v1:bfbcabd0f011731a3f367019e14a1187","researcher:v1:d7762ba30acd5b294e1a1599a12cbad6","researcher:v1:c4c4d999ebf6b15b63913d24bcfc4971"],"runId":"run:v1:51b50d578d5b609842c1c21b8266a00b","jobName":"spark.semantic-summaries","policyVersion":"graph-v1","contentHash":"sha256:39670e6fc2dcec580efd2fbb612bc2aa8338d2e713b546de221dd5a7b082f6cd"}}

## GraphNode property keys (counted from the exported JSONL)

- nodes:researcher (200,484 rows). Every row has all 7 keys: anchorMentionId, distinctDblpOrcids, identityOutcome, mentionCount, minIdentityConfidence, reasonCodes, workCount (200,484 each). The display name is `displayName`.
- nodes:work (155,978 rows):

  | key | rows with the key |
  |---|---|
  | dblpKey | 155,978 |
  | publicationType | 155,978 |
  | teamSize | 155,978 |
  | venueKey | 155,978 |
  | year | 155,978 |
  | doi | 124,661 |

- nodes:venue (16 rows): no property keys. The venue name is `displayName` and nodeId = the dblp venue key.

## Do work nodes carry title, year and venue? (for rag paper summaries)

Yes, all three are present on all 155,978 work nodes:
- Title: GraphNode `displayName`. Its provenance.contentHash = ContentHash.of(title).
- Year: `properties.year` (string), on 155,978 of 155,978 rows.
- Venue: `properties.venueKey` (the dblp venue key), on 155,978 of 155,978 rows.

Links:
- Work to venue:
  - table `edges:published_in` (`edges/edge_label=published_in/`), GraphEdge `from` = work nodeId (WorkId), `to` = venue nodeId (dblp venue key); 155,978 edges, one per work. The window is the publication year.
  - The same key is in `nodes:work` `properties.venueKey`. The venue display name comes from `nodes:venue` (`nodeId` = venueKey, `displayName` = name).
- Work to authors:
  - Table `authorship` (AuthorshipRecord): fields `workId`, `researcherId`, `position` (author order), `mentionId`, `matchConfidence` and `year`; 758,328 rows.
  - Also table `edges:authored`: GraphEdge `from` = researcherId, `to` = workId; 758,314 edges, one per (researcher, work).
  - Author display names come from `canonical_researcher.displayName` (or `nodes:researcher.displayName`).

## Metric definitions, approximations and limits

None changed. The export recomputes nothing: every metric, window, algorithm parameter and approximation note is exactly as recorded in the Phase 6 run manifest and run report for run:v1:51b50d578d5b609842c1c21b8266a00b. Each exported record keeps its window and provenance (run ID, job name, policy version graph-v1, snapshot dblp-2026-11-01).

## Output invariants checked

- Exported count equals the load-manifest count for all 65 tables, checked twice: by an LF count during hashing and by a Spark line count.
- Every line decodes with Contract.decode and re-encodes to the identical text (0 failures across 12,571,066 lines).
- Every encoded record passed Contract validation on the way out.
- The run ID is unchanged, and the output directory `run=<hex>` matches the source.
- The source matched its run manifest (sizes and sha256s) before export, and nothing under attempt-03 was written.
- Determinism: on the fixture, two exports produced identical manifests (all file sha256s equal).

## PROPOSED LATER

1. A sidecar JSONL for the stored columns that are not part of the core records: `path_evidence` hops (per-hop edgeId, weight, years and supporting work IDs), pairKind and pairIndex; and the `evidence:metric` work team sizes. This only matters if storage or rag need them without a join.
2. Optional gzip output (`part-NNNNN.jsonl.gz`) to cut the 7.96 GB footprint, if storage's source abstraction accepts it.
3. An S3-capable variant, writing directly with deterministic names instead of renaming, for when the export runs on EMR.
4. Fix the researcher-profile venue rendering "ACL (1) (2)", for example as "ACL (1) x2". That is a Phase 6 template change and would change content hashes, so it needs a new run.
5. Deduplicate the repeated group ID in community-summary subjectIds, a Phase 6 change.
6. A `summaryKind` to SemanticDocumentType mapping constant in a shared place, if more than rag needs it.
