STATUS: BLOCKED-ON
OWNER: hadoop-engineer
WORK ORDER: Phase 4 (docs/agent-runs/phase-04/prompt.md): ScholarChain Hadoop MapReduce pipeline. No AWS; built and tested in the required order J01, J07, J08, then J02, J03/J04, J05, J09. J06 (candidate features) is blocked because core has no MatchCandidate contract. Every other job is implemented, tested, and passes `sbt check`.
FILES CREATED: (all under /Users/brian/Developer/repos/scholarchain/modules/hadoop/src, nothing outside modules/hadoop)
- main/resources/scholarchain-hadoop-defaults.xml (every threshold and tunable, each with a documented default and description)
- main/scala/org/scholarchain/hadoop/common/{Settings,Counters,Disposition,RunContext,StableHashPartitioner,TaskIO,JobRunner}.scala
- main/scala/org/scholarchain/hadoop/io/{RawRecord,GuardedInputStream,InputOpener,DblpRecordScanner,LineScanner,ScholarChainInputFormats}.scala
- main/scala/org/scholarchain/hadoop/dblp/{DblpEntities,DblpRecordParser,DblpRecordBuilder,DblpExtractJob}.scala
- main/scala/org/scholarchain/hadoop/teams/{TeamAssembler,PublicationTeamJob}.scala
- main/scala/org/scholarchain/hadoop/pairs/{PairExpansion,PairJobs}.scala
- main/scala/org/scholarchain/hadoop/sources/{SourceNormalizers,NormalizeJob}.scala
- main/scala/org/scholarchain/hadoop/ids/{ExactLinker,ExactIdJoinJob}.scala
- main/scala/org/scholarchain/hadoop/blocking/BlockingJob.scala
- main/scala/org/scholarchain/hadoop/graphexport/GraphExportJob.scala
- main/scala/org/scholarchain/hadoop/pipeline/{DblpChain,Pipeline,ScholarChainHadoop}.scala (ScholarChainHadoop is a Hadoop Tool CLI; it never creates clusters or submits EMR steps)
- test/scala/org/scholarchain/hadoop/{TestSupport,ScannerSuite,DblpParsingSuite,DblpExtractJobSuite,DblpChainSuite,PairsAndTeamsSuite,SettingsSuite,NormalizerSuite,LinkAndBlockSuite,PipelineSuite,LocalBoundedPipeline}.scala
FILES MODIFIED: none (build.sbt, modules/core, datasets/**, and docs/agent-runs/** are untouched; `git status` shows only modules/hadoop/ as new)
COMMANDS RUN:
- `sbt -batch "hadoop/compile"`, `"hadoop/Test/compile"`, `"hadoop/testOnly ..."`, and `"export hadoop/Test/fullClasspath"` (repeatedly while developing)
- Debug runs: a JUnitCore run of the suites on the exported test classpath plus a log4j2 binding from the local Coursier cache, so task exceptions were visible (the build has no SLF4J binding). Scratch only; nothing changed in the build.
- `sbt -batch fmt`, then `sbt -batch check` (final run 15:25:40 to 15:30:40, exit 0)
- `sbt -batch "hadoop/Test/runMain org.scholarchain.hadoop.LocalBoundedPipeline"` (exit 0, 94 s): this is the local bounded pipeline run.
TEST RESULTS: Final `sbt check` output (observed): "Module tests: suites=23 executed=684 passed=684 failed=0 errors=0 skipped=0"; "Contract tests (no AWS): suites=0 executed=0" (warn: tests/contracts is empty, owned by test-engineer); "Updated .claude/.last-test-run (module_tests=684, contract_tests=0)"; "[success] Total time: 297 s". The hadoop module has 9 suites and 46 tests, all passing. An earlier milestone check, after J01, J07 and J08 only, passed with 670 tests.
VALIDATION RESULTS:
- The local bounded pipeline ran over all Phase 3 samples. It wrote modules/hadoop/target/local-bounded-run/local-run-report.json (gitignored).
- Every job satisfies the accounting identity INPUT = ACCEPTED + EXCLUDED + REJECTED at the map stage (asserted in PipelineSuite).
- The J01 results match the manifest expectations exactly:
  - Real sample: in-subset keys are Sousa0J23, HofstatterC0Z23, 0003SM23 and LernerFGBB0L22. Exclusions are KemmererGMAE14=year, conf/sigir/2023=element, ShenZWLDWCG23=key_prefix, X14bd=element, neverpublished=key_prefix.
  - Synthetic sample: 3 in-subset records, distinct_pairs=7, invalid_orcid_attrs=1.
- Determinism: the full pipeline run into two different output roots gives identical run IDs and byte-identical part files for every job (PipelineSuite, DblpChainSuite). A rerun reuses completed outputs (`reused=true`).
- Record-boundary safety: the scanner gives each record exactly once at every 7th byte split point of the real sample. J01 with split.maxsize=257 gives the same works as a single split.
ASSUMPTIONS:
1. Snapshot IDs for the API samples are their retrieval date, 2026-11-12, following the Provenance doc rule. The synthetic dblp file runs as its own snapshot, "synthetic-2026-11-12", so it is not labelled as release 2026-11-01.
2. J01 applies dblp-subset-v1 from configuration (`scholarchain.dblp.subset.*`, can be switched off). Its status in the manifest is "proposed".
   - Subset-check order and reason names follow dblp_subset_stats.py: element, key_prefix, year, publtype.
   - A missing year excludes the record (reason year). A year that is present but not 4 digits is rejected (invalid_year), which is stricter than the Python script.
3. Every dblp record element is read and accounted for. Elements other than the selected ones go to excluded/ with reason element.
4. dblp entities come from the file's internal DTD subset plus an optional `scholarchain.dblp.dtdPath`. For the real release this must be set to the raw-prefix dblp.dtd. An undeclared entity rejects the record.
5. Venue.name is the most frequent booktitle/journal label in the stream, with ties broken by code point.
6. ExternalIdLink exact rules: DOI equality, plus ORCID equality between exactly one dblp mention and exactly one source authorship on the same work. No fuzzy matching.
7. Blocking uses only the `dblp-base-name` family: core DblpAuthorName.split with no new normalization. It is labelled provisional (policy "blocking/1-provisional").
8. Configuration defaults live in modules/hadoop's own resources (human decision 6). `scholarchain.code.version` deliberately has no default; every run must state it.
RISKS:
1. Positions on pairs: CoauthorPairCandidate has no position fields. Author positions are carried as MentionId.of(workId, position) in provenance.sourceRecordIds, in the order [dblpKey, leftMention, rightMention]. This is recoverable and tested, but implicit until core adds explicit fields.
2. Intermediate formats: J02, J04, J05 and J07 use Hadoop-internal job contracts between jobs:
   - NormalizedSource v1, MentionContext v1, CandidateBlock v1, and the Disposition v1 reject format.
   - None of these leave the Hadoop stage, and the J09 export contains only core records.
   - Reviewers should confirm this is not "redefining shared records".
3. J04 venue links: J04's links/ can hold the same venue link once per DOI group (67 lines, 64 link IDs in the sample). J09 merges them by linkId.
4. Hot keys: oversize blocks are bounded in reducer memory (max block and group size), but one hot key's shuffle still goes to one reducer. Giant teams are sharded (pairsPerShard).
5. The byte-level splitter assumes ASCII-compatible encodings, which are enforced. A `<article` inside an XML comment would be misread as a record start; dblp does not do this.
6. Without an SLF4J binding, LocalJobRunner task exceptions are invisible in `sbt test`. JobRunner reports what diagnostics Hadoop provides, which locally is often "NA".
7. Test runtime: the hadoop suites take about 4 to 5 minutes inside `sbt check`.
MANUAL ACTION REQUIRED: None for this phase. For a real-release run, set `-D scholarchain.dblp.dtdPath=<raw prefix>/dblp.dtd` and `-D scholarchain.code.version=<version>`. Cloud runs go through infra-engineer's future bounded EMR script.
BLOCKED-ON OWNER: domain-engineer (primary). Blocking-key and match-feature definitions also need entity-resolution-engineer, published through core.
BLOCKED-ON EVIDENCE:
1. J06 cannot run. `grep -rn MatchCandidate modules/core/src` returns nothing. Records.scala:284 has only MatchDecision, which is a final decision record. Needed: a MatchCandidate contract with candidate ID, the two mention IDs (or mention plus researcher), block family and key, `features: List[MatchFeature]`, provenance, and a validator.
2. Records.scala:172, CoauthorPairCandidate has no author positions or mention IDs, but the work order requires "author position ... on every pair record". Request leftPosition/rightPosition (or leftMentionId/rightMentionId).
3. Records.scala:64, WorkRecord has no fields for `<ee>` links, `<url>`, venue label, mdate or publtype. The work order asks J01 to extract "ee links, URLs". They are parsed (DblpRawFields) but have nowhere to go.
4. There is no shared contract for a non-dblp work, author or person (OpenAlex, Crossref, ORCID). That is why J02 keeps NormalizedSource internal. Abstracts, topics, affiliations and author IDs are preserved there and in Institution, Topic and ExternalIdLink records.
5. Enums.scala:200, ExternalIdType has no arXiv or Semantic Scholar type. arXiv is linkable only as a 10.48550 DOI.
6. ExternalIds.scala:134, OpenAlexEntity is sealed, so the topic key rule is duplicated locally (SourceNormalizers.OpenAlexTopic). Request OpenAlexId.Topic.
7. Blocking families named in the work order (initials, coauthor context, title, year, venue, affiliation, source) are entity-resolution-engineer policy. Hadoop cannot depend on modules/entity-resolution, so they must be published as core key functions.
NEXT GATE: The orchestrator routes items 1 to 6 to domain-engineer and item 7 to entity-resolution-engineer, then reruns hadoop-engineer for J06. In parallel, per human decision 7, test-engineer adds the adversarial cases, then code-reviewer reviews the full modules/hadoop diff.

--- JOB GRAPH ---
dblp XML -J01-> works, mentions, venues
works + mentions -J07-> teams, authorPapers, mentionContexts, oversizeTeams
teams -J08a-> pairInstances -J08b-> pairs
mentionContexts -J05-> blocks, oversizeBlocks
enrichment JSONL -J02 (openalex-work | openalex-author | crossref-work | orcid-record)-> normalized, institutions, topics
mentionContexts + normalized -J03/J04-> links, conflicts
works, venues, authorPapers, teams, pairs, links, institutions, topics -J09-> export
Orchestrated by pipeline/Pipeline.run; the dblp chain alone is pipeline/DblpChain.run.

--- MAPPER / REDUCER KEY-VALUE CONTRACTS ---
All shuffle keys and values are Text. All outputs are JSON lines with a NullWritable key.
- J01 hadoop.j01-dblp-extract
  - Input: DblpXmlInputFormat gives (LongWritable offset, RawRecord: one complete record element, or an oversize/truncated/decompression-limit marker).
  - Map out: "W|<dblpKey>" to DblpParsed{WorkRecord, AuthorMention list}; "V|<venueKey>" to the venue label.
  - Reduce out: works/ (WorkRecord), mentions/ (AuthorMention), venues/ (Venue). Map side also writes rejects/v1, excluded/v1, issues/v1.
- J07 hadoop.j07-publication-teams
  - Map: works/ or mentions/ lines (decoded via Contract.decode) to "<workId>" with "W"+WorkRecord or "M"+AuthorMention.
  - Reduce: teams/ (PublicationTeam), authorPapers/ (AuthorMention ordered by position), mentionContexts/ (MentionContext v1), oversizeTeams/, stats.
- J08a hadoop.j08a-pair-expand
  - Map: PublicationTeam to "<workId>\t<shard%09d>" with PairShard{team, shard, from, until}, one per ceil(n(n-1)/2 / pairsPerShard) shards.
  - Reduce: pairInstances/ (CoauthorPairCandidate per position pair; provenance = [dblpKey, leftMentionId, rightMentionId]); self pairs go to issues/v1.
- J08b hadoop.j08b-pair-consolidate
  - Map: "<workId>\t<left>\t<right>" to the pair.
  - Reduce: pairs/ (one CoauthorPairCandidate per work and name pair; sourceRecordIds = dblpKey plus every supporting mention, sorted).
- J02 hadoop.j02-normalize-<kind>
  - Input: BoundedLineInputFormat.
  - Map: "R|<sourceKey>" to NormalizedSource v1; "I|<Iid>" to Institution; "T|<Tid>" to Topic.
  - Reduce: normalized/, institutions/, topics/; identical copies are de-duplicated, conflicting copies rejected.
- J03/J04 hadoop.j04-exact-id-join
  - Map: "doi\t<canonical doi>" to "C"+MentionContext or "S"+NormalizedSource. Records with no key count as MISSING_KEY.
  - Reduce: ExactLinker per DOI group writes links/ (ExternalIdLink) and conflicts/v1.
- J05 hadoop.j05-blocking
  - Map: "<family>\t<key>" to BlockMember.
  - Reduce: blocks/ (CandidateBlock v1, at most maxBlockSize members); oversizeBlocks/ (key and size only).
- J09 hadoop.j09-graph-export
  - Map (one mapper class per entity, Contract.decode): "<entity>\t<year or empty>\t<recordId>" to JSON.
  - Reduce: <entity>/year=YYYY/ for work, publication_team and coauthor_pair_candidate; <entity>/ otherwise.
  - Links, institutions and topics merge copies that differ only in provenance run/job/sourceRecordIds; any other disagreement is rejected.

--- PARTITION STRATEGY AND COUNTS ---
- StableHashPartitioner: the first 4 bytes of SHA-256 of the UTF-8 key, modulo the reducer count. Reducer counts come from configuration.
- Reducers per job: J01 4 (dblp.reducers), J07 4, J08a 4 (shards of one team spread across reducers; verified with a 120-author team), J08b 4, J02 2, J04 4, J05 4, J09 4 (plus year directories).
- Map splits: gzip input is a single split. Uncompressed input splits at HDFS blocks with record-boundary-safe readers.
- Intermediate compression: mapreduce.map.output.compress=true with DefaultCodec (configurable).
- Final output compression: configurable, off by default.

--- COUNTERS (actual values from the local bounded run) ---
Real samples (dblp 2026-11-01 plus OpenAlex/Crossref/ORCID retrieved 2026-11-12):
- J01: INPUT 9, ACCEPTED 4, EXCLUDED 5 (element 2, key_prefix 2, year 1), REJECTED 0. Output: works 4, mentions 17, venues 1.
- J07: INPUT 21, ACCEPTED 21. Output: teams 4, authorPapers 17, mentionContexts 17. OVERSIZE_AUTHOR_TEAMS 0.
- J08a: INPUT 4, PAIR_SHARDS 4, PAIR_INSTANCES_GENERATED 33 (equals 3+6+3+21), EMITTED_PAIRS 33.
- J08b: INPUT 33, EMITTED_PAIRS 33, DUPLICATE 0.
- J02:
  - openalex-work: INPUT 4, ACCEPTED 4, institutions 13.
  - openalex-author: INPUT 3, ACCEPTED 3, institutions 5.
  - crossref-work: INPUT 4, ACCEPTED 4.
  - orcid-record: INPUT 1, ACCEPTED 1.
- J04: INPUT 29, ACCEPTED 29, MISSING_KEY 3 (OpenAlex authors have no DOI), UNMATCHED_KEYS 1 (the arXiv DOI on the ORCID record), LINKS_EMITTED 67, CONFLICT 0.
  - work: openalex_work 4, doi 9 (4 OpenAlex, 4 Crossref, 1 ORCID)
  - venue: openalex_source 4
  - mention: orcid 23 (12 OpenAlex, 10 Crossref, 1 ORCID), openalex_author 11 (one OpenAlex author ID is null, matching the known W4384652670 case), openalex_institution 16
- J05: INPUT 17, SINGLETON_BLOCKS 17, BLOCKS_EMITTED 0, OVERSIZE_BLOCKS 0.
- J09: INPUT 144, ACCEPTED 144, DUPLICATE 6 (3 venue-link copies and 3 institutions merged). Exported: work 4, author_mention 17, publication_team 4, coauthor_pair_candidate 33, external_id_link 64, institution 15, venue 1. REJECTED 0.
Synthetic dblp:
- J01: INPUT 5, ACCEPTED 3, EXCLUDED 2 (year 1, publtype 1), FIELD_ISSUES 1 (invalid_orcid_attr).
- J07: teams 3.
- J08a: PAIR_INSTANCES_GENERATED 11, EMITTED_PAIRS 10, SELF_PAIRS_SKIPPED 1, SINGLE_AUTHOR_TEAMS 1, PAIR_SHARDS 2.
- J08b: EMITTED_PAIRS 7, DUPLICATE 3.
- J04: INPUT 8, MISSING_KEY 1, links 0 (no enrichment).
- J05: BLOCKS_EMITTED 1, SINGLETON_BLOCKS 4.
- J09: INPUT 23.

--- REJECTED RECORDS (local run) ---
- No input record was rejected in either run.
- Dispositions written:
  - Real: excluded element 2, key_prefix 2, year 1.
  - Synthetic: excluded publtype 1, year 1; field_issue invalid_orcid_attr 1; field_issue self_pair 1.
- Reject reasons covered by tests, written to versioned rejects/v1 in format scholarchain.hadoop.disposition v1:
  - Input reading: malformed_xml, undefined_entity, oversize_record, truncated_record, decompression_limit_exceeded, unsupported_encoding.
  - Record rules: missing_key, invalid_key, invalid_year, conflicting_field, missing_title, no_authors, invalid_author_name.
  - Reduce side: duplicate_key_conflict, team_exceeds_hard_limit.
  - JSON lines: malformed_json, not_a_json_object, unsupported_record, oversize_line, blank_line.
  - Conflicts: doi_maps_to_multiple_dblp_works, doi_maps_to_multiple_openalex_works, orcid_on_multiple_mentions_of_work.

--- OUTPUT PATHS ---
- Layout: <root>/<dataset>/snapshot=<id>/<job>/run=<runId hex>/ for single-dataset jobs; <root>/linked/<dblp-..+crossref-..+openalex-..+orcid-..>/<job>/run=<hex>/ for J04 and J09.
- Each directory holds _SUCCESS plus _job.json (run ID, fingerprints, counters, stats). A complete directory is reused; an incomplete one fails unless scholarchain.output.replaceIncomplete=true.
- Local run root: /Users/brian/Developer/repos/scholarchain/modules/hadoop/target/local-bounded-run/{real,synthetic}/. Examples:
  - real/dblp/snapshot=2026-11-01/j01-dblp-extract/run=630a0df739cff601a04bc3a552dcee61
  - real/linked/dblp-2026-11-01+crossref-2026-11-12+openalex-2026-11-12+orcid-2026-11-12/j09-graph-export/run=f608ce18cc4b68c51ea08ce6d06aaac3

--- MAXIMA ---
- Maximum observed team size:
  - Real: 7 (conf/sigir/LernerFGBB0L22, work:v1:946445e4da1b44a594ba6eca84fe5676).
  - Synthetic: 5.
  - Test fixture: 120 (oversize threshold 100, counted and listed in oversizeTeams/; with hardLimit 100 it is rejected).
- Largest pair expansion:
  - Real: 21 (same work).
  - Synthetic: 10.
  - Test fixture: 7,140 pairs over 15 shards on several reducers.
- Largest observed blocking group:
  - Real: 1 (all 17 blocks are singletons).
  - Synthetic: 4 ("Synthetic Author", which joins homonyms 0001 and 0002).
  - Test fixture: 120 ("Giant Member", reported as an oversize block with limit 2 and not emitted).
- Largest identifier group: 9 (DOI 10.1145/3477495.3531753).

--- JOBS NOT COMPLETED ---
- J06 candidate feature construction (MatchCandidate): not implemented. Blocked on a core MatchCandidate contract and on entity-resolution-owned feature definitions. J05 already emits the bounded CandidateBlock input that J06 would read.
- J05 key families other than dblp-base-name (initials, coauthor context, title, year, venue, affiliation, source): need key definitions from entity-resolution-engineer published through core.
- The bounded EMR smoke-run script: out of scope for this agent (infra-engineer, later phase).
