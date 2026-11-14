STATUS: COMPLETE
OWNER: hadoop-engineer
WORK ORDER: docs/agent-runs/phase-04/prompt.md, the Phase 4 rerun. (a) Repair modules/hadoop against core WorkRecord v2, CoauthorPairCandidate v2, MatchCandidate v1 and OpenAlexId.Topic. (b) Use the core topic rule and entity list. (c) Implement J06. (d) Write tests. (e) Run sbt fmt, sbt check and LocalBoundedPipeline.

FILES CREATED:
- /Users/brian/Developer/repos/scholarchain/modules/hadoop/src/main/scala/org/scholarchain/hadoop/candidates/CandidateFeatureJob.scala (J06)
- /Users/brian/Developer/repos/scholarchain/modules/hadoop/src/test/scala/org/scholarchain/hadoop/CandidateSuite.scala

FILES MODIFIED (all under /Users/brian/Developer/repos/scholarchain/modules/hadoop/src/; the module is still untracked in git):
- main/scala/org/scholarchain/hadoop/dblp/DblpRecordBuilder.scala: J01 eeLinks and urls, and the DOI is now taken only from the kept eeLinks.
- main/scala/org/scholarchain/hadoop/pairs/PairExpansion.scala and pairs/PairJobs.scala: J08a builds positions with PairPosition.forNames, and J08b takes the union of positions.
- main/scala/org/scholarchain/hadoop/sources/SourceNormalizers.scala: uses core OpenAlexId.Topic and OpenAlexId.entities, and keeps topic IDs that have no name.
- main/scala/org/scholarchain/hadoop/blocking/BlockingJob.scala: the CandidateBlock and BlockMember job contract is now v2, and the `blocks/` directory always exists.
- main/scala/org/scholarchain/hadoop/common/JobRunner.scala: new JobSpec.alwaysPresentDirs option.
- main/scala/org/scholarchain/hadoop/common/Counters.scala, common/Settings.scala, common/Disposition.scala (new reasons), resources/scholarchain-hadoop-defaults.xml (J06 keys).
- main/scala/org/scholarchain/hadoop/graphexport/GraphExportJob.scala: new match_candidate entity.
- main/scala/org/scholarchain/hadoop/pipeline/Pipeline.scala: J06 wired into the pipeline and its result.
- test/scala/org/scholarchain/hadoop/{DblpChainSuite,DblpParsingSuite,NormalizerSuite,PairsAndTeamsSuite,PipelineSuite,LocalBoundedPipeline}.scala

COMMANDS RUN:
- `sbt hadoop/compile` and `sbt hadoop/Test/compile`: success.
- `sbt hadoop/test`: 58 passed, 1 failed (PipelineSuite). J05 wrote no `blocks/` directory for the real sample, so J06 failed on a missing input path. Fixed, see ASSUMPTIONS 4.
- `sbt fmt "hadoop/testOnly ...PipelineSuite ...CandidateSuite"`: passed, 12 tests.
- `sbt check`: exit 0, "[success] Total time: 503 s". markTestRun reported "Updated .claude/.last-test-run (module_tests=779, contract_tests=0)". It also printed "[warn] No contract tests executed (tests/contracts/src/test/scala is empty)". Ledger and scalafmt checks passed.
- `sbt "hadoop/Test/runMain org.scholarchain.hadoop.LocalBoundedPipeline"`: exit 0, success in 106 s. Report: modules/hadoop/target/local-bounded-run/local-run-report.json

TEST RESULTS: sbt check passed with 779 module tests, 0 failures, and 0 contract tests (the tests/contracts tree is empty). New and updated hadoop tests:
- DblpParsingSuite:
  - eeLinks and urls are decoded, trimmed, kept in source order, with the first occurrence kept.
  - Blank or control-character links are dropped with issues, and the DOI comes only from a kept link.
  - sourceLinks reports positions.
  - The accepted record is v2 with eeLinks.
- PairsAndTeamsSuite:
  - Each instance carries one PairPosition equal to forNames, and provenance holds only the dblp key.
  - Consolidation takes the union of positions, sorted and distinct, as `[(0,1),(2,1)]`.
  - Instances whose provenance differs are not merged.
- DblpChainSuite:
  - On the real sample, every instance's positions match the team.
  - On the synthetic sample, the homonym pair SyntheticA24 (0001, 0002) carries `[(0,1),(4,1)]`.
  - All consolidated pairs validate.
- NormalizerSuite: topic keys follow core OpenAlexId.Topic, every non-work core entity key gives unsupported_record, and a valid topic with no name keeps its ID and reports `topic_without_name`.
- CandidateSuite (new):
  - Feature values, sorting and symmetry. Omitted features are left out, never defaulted.
  - Candidate ID, canonical order, provenance and validity.
  - CandidateBlock v2 structural checks and BlockMember.of.
  - J06 local run: 8 candidates, counters, per-feature omission counters.
  - Sharding with pairsPerShard=1 gives the same candidate set spread over more than one reducer file.
  - Outputs are byte-identical across output roots.
  - Oversize blocks are counted and listed.
  - An unknown feature set throws SettingsError.
  - Malformed block lines are rejected with reasons, and duplicate copies collapse.
  - J09 exports match_candidate.
- PipelineSuite: on the real sample J05 leaves an empty `blocks/` and J06 reads 0 inputs. The map accounting identity holds for every job, J06 included.

VALIDATION RESULTS:
- In the local bounded run every job satisfies INPUT = ACCEPTED + EXCLUDED + REJECTED (asserted in PipelineSuite).
- J09 had 0 rejects in both runs.
- Determinism: run IDs and part files are byte-identical across output roots (PipelineSuite, DblpChainSuite, CandidateSuite).
- Sample output:
  - J01 work `conf/sigir/HofstatterC0Z23` has `eeLinks:["https://doi.org/10.1145/3539618.3591687","https://www.wikidata.org/entity/Q131012499"]` and `urls:["db/conf/sigir/sigir2023.html#HofstatterC0Z23"]`.
  - J08b synthetic pair positions are `[(0,1),(4,1)]` with sourceRecordIds `["conf/sigir/SyntheticA24"]`.

ASSUMPTIONS:
1. Pair positions are no longer encoded in provenance. sourceRecordIds now holds only the team's source record (the dblp key). Positions and mention IDs live in the typed `positions` field. Keeping mention IDs in provenance as well would duplicate `positions`, and the two copies could drift apart. Consolidated provenance must be identical across instances; otherwise the conflict is rejected.
2. J01 link rule:
   - A link that is blank after trimming is dropped with field issue `blank_source_link`.
   - A link that still contains a control character after trimming (for example an embedded newline) is dropped with `invalid_source_link`. It is not repaired.
   - Repeated values are de-duplicated as the contract says, with no issue raised.
   - `conflicting_dois` behaviour is unchanged.
3. J06 data and features:
   - J05 BlockMember was widened (job contract `candidate-block` v1 -> v2) to carry dblpKey, orcid, venueKey, coauthorNames and coauthorsTruncated from the J07 mention context. J06 joins pairs inside each block without another shuffle join.
   - Feature set `candidate-features/1-provisional`: coauthor_shared_count, homonym_suffix_match, name_exact_match, orcid_exact_match, same_venue, same_work, year_abs_diff.
   - coauthor_shared_count is omitted when either coauthor context is truncated. homonym_suffix_match, orcid_exact_match and same_venue are omitted unless both sides have the value.
   - Candidate provenance:
     - sourceType dblp.
     - sourceRecordIds: the two works' dblp keys, left first, without repeats.
     - policyVersion: `<featureSet>;<blocking policy>`.
     - contentHash: null, because the record carries no text.
4. To fix the failing test, J05 now always creates `blocks/`, empty when there are no blocks, through the new JobSpec option `alwaysPresentDirs`. That way J06 sees an empty input instead of a missing path.
5. MatchCandidate is exported by J09 as `match_candidate/`, with no year partition, keyed by candidateId. This gives entity resolution one validated, partitioned, run-addressed copy next to the graph files.
6. Out-of-scope fix: OpenAlex topics that had a valid ID but no display_name were silently dropped from `topicIds`. They are now kept and reported (`topic_without_name`), with a test.

RISKS:
- In J08b the consolidated pair keeps the J08a instance provenance (runId and jobName of j08a), as in the first run. Should the J08b run stamp its own run ID? That is a provenance-policy question for review; I did not change it.
- The real sample produces 0 match candidates because every name block is a singleton. J06 has only been exercised on synthetic data and test fixtures.
- J06 holds one block in memory per shard: at most maxBlockSize (1000) members, each with at most 50 coauthor names. Pairs are streamed, never collected.
- Feature names and semantics are provisional until entity-resolution-engineer sets policy in Phase 5.

MANUAL ACTION REQUIRED: None. Per decision 7, test-engineer adversarial cases and the code-reviewer review of the full diff come next.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: test-engineer adversarial cases, then code-reviewer.

## Job graph
```
dblp XML --J01--> works (WorkRecord v2), mentions, venues
  works + mentions --J07--> teams, authorPapers, mentionContexts, oversizeTeams
  teams --J08a--> pairInstances (1 position each) --J08b--> pairs (union of positions)
  mentionContexts --J05--> blocks (CandidateBlock v2) --J06--> candidates/family=<f>/ (MatchCandidate)
enrichment JSONL --J02 (per kind)--> normalized, institutions, topics
mentionContexts + normalized --J03/J04--> links
works, venues, authorPapers, teams, pairs, links, institutions, topics, candidates --J09--> export
```

## Mapper and reducer key-value contracts
All map outputs are Text keys and Text values. Every job uses StableHashPartitioner (SHA-256 of the key, first 4 bytes, modulo the reducer count).
- **J01:** record-boundary-safe XML input -> `<workId>` -> work, mention or venue JSON. Reduce emits one record per ID; identical copies collapse, and conflicts are rejected (unchanged).
- **J07:** `<workId>` -> `W`+WorkRecord or `M`+AuthorMention. Reduce outputs teams, authorPapers, mentionContexts and oversizeTeams.
- **J08a:** `<workId>\t<shard%09d>` -> PairShard(team, shard, from, until). Reduce streams the pairs into pairInstances. Each instance is a CoauthorPairCandidate v2 with exactly 1 PairPosition.
- **J08b:** `<workId>\t<left>\t<right>` -> instance JSON. Reduce emits one pair with positions merged (sorted by PairPosition.ordering, distinct); disagreeing facts are rejected as a conflict.
- **J02:** per kind, `N|`, `I|` or `T|<id>` -> normalized, institution or topic (unchanged).
- **J03/J04:** `<identifier key>` -> mention context or normalized record. Reduce emits ExternalIdLink (unchanged).
- **J05:** `<family>\t<key>` -> BlockMember v2. Reduce emits CandidateBlock v2 or an oversize listing.
- **J06 (new):** map reads a CandidateBlock and emits `<family>\t<blockKey>\t<shard%09d>` -> BlockShard(block, shard, from, until), one per ceil(m(m-1)/2 / pairsPerShard) shards. Reduce streams the member pairs and writes MatchCandidate records to `candidates/family=<family>/`.
- **J09:** `<entity>\t<year or empty>\t<id>` -> record JSON. Reduce emits one record per ID; links, institutions and topics merge; anything else that disagrees is rejected. match_candidate is a new entity.

## Partition strategy and counts
Default reducers come from configuration:
- dblp 4, teams 4, pairs.expand 4, pairs.consolidate 4
- normalize 2, idjoin 4, blocking 4
- candidates 4 (new), export 4

Further partitioning:
- J09 writes year=YYYY directories for work, publication_team and coauthor_pair_candidate.
- J06 writes family=<family> directories.
- J08a and J06 spread the shards of one team or block across reducers.

New J06 defaults: maxPairsPerBlock 125000, pairsPerShard 50000, featureSet candidate-features/1-provisional.

## Counters from the local run
**Real samples (dblp 2026-11-01 plus OpenAlex, Crossref and ORCID retrieved 2026-11-12):**

| Job | Counters |
|---|---|
| J01 | INPUT 9, ACCEPTED 4, EXCLUDED 5 (element 2, key_prefix 2, year 1). Outputs: works 4, mentions 17, venues 1 |
| J07 | INPUT 21, ACCEPTED 21. Outputs: teams 4, authorPapers 17, mentionContexts 17 |
| J08a | INPUT 4, ACCEPTED 4, PAIR_SHARDS 4, PAIR_INSTANCES_GENERATED 33, EMITTED_PAIRS 33 |
| J08b | INPUT 33, EMITTED_PAIRS 33 |
| J02 openalex-work | 4/4 accepted, institutions 13 |
| J02 openalex-author | 3/3 accepted, institutions 5 |
| J02 crossref | 4/4 accepted |
| J02 orcid | 1/1 accepted |
| J04 | INPUT 29, ACCEPTED 29, LINKS_EMITTED 67, MISSING_KEY 3, UNMATCHED_KEYS 1 |
| J05 | INPUT 17, ACCEPTED 17, SINGLETON_BLOCKS 17, BLOCKS_EMITTED 0 |
| J06 | INPUT 0 (empty blocks/), 0 candidates |
| J09 | INPUT 144, ACCEPTED 144, DUPLICATE 6, 0 rejects |

J09 exported: work 4 (2022: 1, 2023: 3), author_mention 17, publication_team 4, coauthor_pair_candidate 33 (2022: 21, 2023: 12), external_id_link 64, institution 15, venue 1.

**Synthetic dblp (snapshot synthetic-2026-11-12):**

| Job | Counters |
|---|---|
| J01 | INPUT 5, ACCEPTED 3, EXCLUDED 2 (publtype 1, year 1), FIELD_ISSUES 1 (invalid_orcid_attr) |
| J07 | INPUT 11, ACCEPTED 11. Outputs: teams 3 |
| J08a | INPUT 3, PAIR_SHARDS 2, PAIR_INSTANCES_GENERATED 11, EMITTED_PAIRS 10, SELF_PAIRS_SKIPPED 1, SINGLE_AUTHOR_TEAMS 1 |
| J08b | INPUT 10, DUPLICATE 3, EMITTED_PAIRS 7 |
| J04 | INPUT 8, MISSING_KEY 1, 0 links |
| J05 | INPUT 8, BLOCKS_EMITTED 1, SINGLETON_BLOCKS 4 |
| J06 | INPUT 1, ACCEPTED 1, CANDIDATE_SHARDS 1, MATCH_CANDIDATES_EMITTED 6, feature-omitted orcid_exact_match 6 |
| J09 | INPUT 29, ACCEPTED 29. Exported: match_candidate 6, coauthor_pair_candidate 7, work 3, author_mention 8, publication_team 3, venue 2 |

## Rejected records
- No rejects in either local run.
- Dispositions:
  - Real J01: excluded/v1 has 5 entries.
  - Synthetic J01: excluded/v1 has 2 entries and issues/v1 has 1 (invalid_orcid_attr).
  - Synthetic J08a: issues/v1 has 1 (self_pair).
- New reject and issue reasons:
  - blank_source_link
  - invalid_source_link
  - topic_without_name
  - J06 uses the existing reasons: schema_failure for a malformed block, duplicate_key_conflict for different blocks under one shard key, and blank_line.
- The CandidateSuite fixture shows the rejects being counted: 5 inputs = 2 accepted + 3 rejected (schema_failure 2, blank_line 1), plus 1 duplicate collapsed.

## Output paths
Pattern: `<root>/<scope>/<job>/run=<run hex>/`. Scope is `dblp/snapshot=<id>` or `linked/<snapshot label>`.

New in this run: `.../dblp/snapshot=<id>/j06-candidate-features/run=<hex>/{candidates/family=dblp-base-name/, oversizeBlocks/, rejects/v1/, stats/}` and J09 `match_candidate/`.

Local run example: `modules/hadoop/target/local-bounded-run/synthetic/dblp/snapshot=synthetic-2026-11-12/j06-candidate-features/run=248c2158cbf9b8b5294c00b0050e3413/`

## Maximum observed team size
- Real: 7, at work:v1:946445e4da1b44a594ba6eca84fe5676.
- Synthetic: 5, at work:v1:91cf027cf2e9fa334fbf695a9583d27a.

## Largest pair expansion
- Real: 21, same work.
- Synthetic: 10.
- Test fixture: 7140 for a 120-author team, sharded at 500 pairs per shard.

## Largest observed blocking group
- Real: 1 ("dblp-base-name\tAlípio Mário Jorge"; all blocks are singletons).
- Synthetic: 4 ("dblp-base-name\tSynthetic Author").
- Test fixture: 120 ("Giant Member"), oversize at maxBlockSize 2.

## J06 candidate counts and largest candidate group
- Real: 0 candidates (no blocks with 2 or more members).
- Synthetic:
  - 6 candidates, all from 1 block.
  - largestCandidateGroup: 6 pairs, "dblp-base-name\tSynthetic Author".
  - largestBlockSeen: 4.
  - Oversize blocks: 0.
- CandidateSuite fixture:
  - 8 candidates from 3 blocks; the largest group has 6 ("Wei Wang").
  - With maxPairsPerBlock=5: 1 oversize block, 6 pairs skipped, 2 candidates emitted.

## Changes since the first run's report
- **Contracts:**
  - WorkRecord is now v2 with eeLinks and urls (previously not carried).
  - CoauthorPairCandidate is now v2 with `positions`. Provenance sourceRecordIds was `[dblpKey, mention IDs...]` and is now `[dblpKey]`.
  - J08b consolidation now takes the union of positions instead of mention IDs in provenance.
- **Topic rule:** the local SourceNormalizers.OpenAlexTopic rule and the local OpenAlex key list are removed in favour of core OpenAlexId.Topic and OpenAlexId.entities. Topics without a name are now kept and reported instead of being silently dropped.
- **J06:** previously not implemented (blocked); now implemented with its counters (CANDIDATE_SHARDS, MATCH_CANDIDATES_EMITTED, OVERSIZE_BLOCK_PAIRS_SKIPPED, the scholarchain.feature-omitted group) and the stats largestCandidateGroup and largestBlockSeen.
- **Job graph and J09:** J09 now exports match_candidate, and the pipeline result and job graph include J06.
- **J05:**
  - The block contract went from v1 to v2 with richer members.
  - `blocks/` now always exists.
- **Counts and run IDs:**
  - Test count went from 684 to 779 (this includes the domain-engineer core test changes).
  - All run IDs changed, because code and configuration changed.
  - The counts for J01, J07, J08, J02, J04 and J09 on the real sample are what this run observed; I did not have the first run's report to compare them line by line.

## Still not done
- Blocking families beyond dblp-base-name and the MatchCandidate `mention_researcher` kind are deferred to Phase 5 (entity-resolution policy).
- The bounded EMR smoke-run script belongs to infra-engineer.
- tests/contracts is empty, so 0 contract tests ran.
- The J08b provenance run-stamping question is open (see RISKS).
