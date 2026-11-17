STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 11, step R-A (decision 6, slice A: modules/hadoop/**, modules/entity-resolution/**, datasets/**). Work order docs/agent-runs/phase-11/prompt.md, sha256 a0b59faeaeaae620ad87ce8b5dd1272efc1078d273e9a84baa426a9cd47e9ed1 (taken from the orchestrator, not recomputed). Repo at HEAD 2582a50.
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools: Read, Grep, Glob)
TEST RESULTS: not applicable (reviewer runs no tests)
VALIDATION RESULTS: I checked every finding below against the cited source lines. I grepped docs/agent-runs/ for each candidate and dropped the ones already recorded: Phase 4 finding 12 (inputSha256 covers only the preview), Phase 5 uncovered-member diagnostics not exported by design, Phase 5 M1 (cannot-links), m5, and Phase 4 finding 2 (reuse vs code version, which is a P1 this phase).
ASSUMPTIONS: (1) Step 3a makes `initials-surname` a default blocking family (P1, Phase 5 m5). The fallback families and `secondaryKeys=coauthor` keep their defaults. (2) No live ER adjudicator exists. `ErMain` offers only `always-abstain`, so A-02 and A-05 cannot be reached today.
RISKS: A-01 matters more after step 3a, because the P1 default change makes the oversize `initials-surname` fallback path the normal path. Most test files in the slice were not read (see coverage), so area 10 is only partly reviewed.
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: step 2 triage. A-01 (major) goes to entity-resolution-engineer in step 3b. A-02, A-03 and A-05 are minor and A-04 is advisory: record only.

VERDICT: REQUEST_CHANGES (slice A; one verified major finding: A-01)

## Findings

| ID | Severity | file:line | Concrete failure input | Impact | Owner | Shared root cause |
|---|---|---|---|---|---|---|
| A-01 | major | `modules/entity-resolution/src/main/scala/org/scholarchain/er/cluster/Clustering.scala:176-204` (merge guards); `modules/entity-resolution/src/main/scala/org/scholarchain/er/run/ErRun.scala:326-332` (cannot-links only from decided candidates); `modules/hadoop/src/main/scala/org/scholarchain/hadoop/blocking/FallbackJobs.scala:183-191` (fallback keys = primary key + one shared coauthor) | `initials-surname` block `j smith` is oversize (Phase 5 run 2 reported 43,466 uncovered members, worst keys all in this family). Its members are re-blocked only by shared coauthor. Take "John Smith" (work with X), "J. Smith" (work with X and Y) and "Jane Smith" (work with Y), on different works, with no other shared coauthor. Fallback blocks `["j smith","X"]` = {John, J.} and `["j smith","Y"]` = {J., Jane}. Under er-v1, John~J. scores −1 +1 (given compatible) +2.0 (1 shared coauthor) +0.5 (overlap ratio) +0.2/+0.4/+0.3 (year, venue, area) ≈ 3.4, p≈0.97, accepted; J.~Jane likewise. John and Jane share no block, so there is no MatchCandidate, no REJECTED decision and no cannot-link (`CannotLinks.Builder.add` is called only per candidate). The orcid, same-work, homonym, disambiguation and cannot-link guards all pass, so `Clustering.run` merges all three into one ResearcherId. | Over-merge: John Smith and Jane Smith (given names in conflict, given_name_relation 0) become one researcher. The identity assignments say `accepted`/`probabilistic` with no flag. `rejectedOrConflictPairsInsideOneCluster` stays 0 because no decision exists. The review queue samples decided candidates, so it never shows the pair. This breaks "identity uncertainty is reported, not hidden" (CLAUDE.md) and spec area 6, and it skews collaboration-breadth metrics downstream (Phases 12–16). The er-v1 rule text for `cluster.guard.rejected-pair` (er-v1.json:146) claims this exact chain is prevented; that holds only when Jane/John were compared. Existing tests (for example `tests/contracts/.../er/CannotLinkAdversarialSuite.scala:15-114`) always supply a Jane–John candidate. | entity-resolution-engineer | No |
| A-02 | minor (major once a live ER adjudicator is wired) | `modules/entity-resolution/src/main/scala/org/scholarchain/er/llm/Adjudication.scala:234, 239, 242, 277-279, 286`; written by `.../er/run/ErRun.scala:592-605` | A model reply that passes the key, outcome, cited, reasons and confidence checks but has `"chosenCandidateId": "<10 KB of arbitrary model text>"`. It fails with `fail("unoffered-candidate", s)`, and `s` is stored verbatim with no length cap in the `detail` field of `llm/rejected-replies.jsonl`. Model-chosen key names (`schema: unknown keys ...`, `source-field-mutation`), duplicate keys (up to 64 chars) and unknown cited paths are also echoed. The reply length is not recorded. | Breaks the CLAUDE.md rule: "Model output that fails validation is stored only as its sha256, length, and error." Unreachable today (only `AlwaysAbstainAdjudicator`), but any live adapter would persist untrusted model text in a run output. | entity-resolution-engineer | A-05 (same LLM-stage hardening) |
| A-03 | minor | `modules/hadoop/src/main/scala/org/scholarchain/hadoop/teams/PublicationTeamJob.scala:81` (`new String(rec.payload, UTF_8)`, a replacing decoder), used by `.../sources/NormalizeJob.scala:60`; `.../sources/SourceNormalizers.scala:131` (`rawSha256 = sha256Hex(line)`); `.../io/LineScanner.scala:101` (strips `\r`) | A raw OpenAlex or Crossref JSONL line with an invalid UTF-8 byte inside a name or title. J02 accepts it with U+FFFD in place: no reject, no issue. `NormalizedSource.rawSha256` (documented at SourceNormalizers.scala:51-52 as "sha256 of the raw JSON line") is the hash of the decoded, CR-stripped string, not of the raw bytes. | Silent alteration of enrichment text, and a provenance hash that does not match the raw record. J01 reports `invalid_encoding` for the same condition (DblpExtractJob.scala:162-181), so behaviour differs between stages. | hadoop-engineer | No |
| A-04 | advisory | `datasets/scripts/fetch_enrichment.py:32, 227` | The OpenAlex key comes from the hard-coded variable name `SCHOLARCHAIN_OPENALEX_API_KEY`, not from a configuration-supplied name. | Differs from the CLAUDE.md wording "Keys are read only from an environment variable whose NAME comes from configuration." The key is never logged and is sent only as a header. Accepted in Phase 3 (agent-report-dataset.md:72,80). Recorded for Phase 12/17 consistency. | dataset-engineer | No |
| A-05 | minor | `modules/entity-resolution/src/main/scala/org/scholarchain/er/run/ErMain.scala:108-111`; `.../er/llm/Adjudication.scala:331-349` (cache key without adjudicator); `.../er/run/ErRun.scala:438-439` | `--adjudicator always-abstain --llm-model-id <a real model ID> --llm-cache <file>`. Every stub abstention is written to `llm/adjudications.jsonl` and recorded on MatchDecisions as `modelVersion = <real model ID>`. A later run with a live adapter, the same model ID and that file as `--llm-cache` takes the stub's abstentions as cache hits for the real model and never calls it. | Decision provenance names a model that never ran, and stub answers can poison the cache across adjudicators. The adjudicator name is only in the manifest config, not in `LlmAdjudicationId` or the decision. Phase 5 runs used `none/always-abstain`, so this is a procedural mitigation only. Latent until a live adapter exists. | entity-resolution-engineer | A-02 |

Suggested direction for A-01 (owner decides): add a cluster-level given-name guard. Refuse a merge, or at least flag the cluster and its assignments as `conflict` with a reason code, when the merged cluster would hold two dblp names with the same folded surname and `NameParts.givenNameRelation == 0`. Then add a module test with the chain above and no Jane–John candidate.

## Notes checked and not reported (verified correct, or known)

- Blocking secondary sort and grouping (`BlockingSort`, `TwoFieldPrefix*`): block keys hold no tab or control character (`AuthorNameKey` plus `DblpAuthorName.normalize`), so groups are contiguous. J05c streaming per oversize block relies on the same property; correct.
- `BlockLimit.maxMembers`, `CannotLinks` bit packing (lo<<32 | hi<<1 | kind, names < 2^30) and `IntOpenSet` are correct. Cannot-link checks scan the smaller set; symmetry holds after merges.
- `DblpRecordScanner`: split ownership by `<` position, comment/CDATA skipping, quoted `>`, self-closing tags, truncated and oversize units are correct. The phantom-record limitation is documented. Parser: DTD and external entities are disabled, and entity expansion is single-pass and bounded.
- `LineScanner` split rule matches Hadoop's LineRecordReader. The decompression guard is applied to every input.
- `PairExpansion`/`PairIndex`: unrank and row-major shards are correct; teamSize == authorNames.size is enforced by the core Validator (Validator.scala:247).
- `JobRunner` output-path segments are validated before `fs.delete`; speculative execution is off.
- Known, not re-reported: Phase 4 finding 12 (inputSha256 of preview), J05c/J06 uncovered members not exported (Phase 5 design), ER input fingerprint by size (Phase 5 m5, mitigated by re-hash on reuse), Phase 4 finding 2 (P1 3a).

## Coverage

modules/hadoop/src/main:
- read in full: blocking/BlockingJob.scala, blocking/FallbackJobs.scala, pairs/PairExpansion.scala, pairs/PairJobs.scala, io/DblpRecordScanner.scala, dblp/DblpRecordParser.scala, dblp/DblpRecordBuilder.scala, dblp/DblpEntities.scala, dblp/DblpExtractJob.scala, candidates/CandidateFeatureJob.scala, common/JobRunner.scala, common/TaskIO.scala, common/StableHashPartitioner.scala, common/Settings.scala, common/RunContext.scala, common/PathIds.scala, teams/PublicationTeamJob.scala, teams/TeamAssembler.scala, ids/ExactLinker.scala, ids/ExactIdJoinJob.scala, graphexport/GraphExportJob.scala, pipeline/DblpChain.scala, pipeline/Pipeline.scala, pipeline/ScholarChainHadoop.scala, io/ScholarChainInputFormats.scala, io/InputOpener.scala, io/LineScanner.scala, io/GuardedInputStream.scala, sources/NormalizeJob.scala, sources/SourceNormalizers.scala, resources/scholarchain-hadoop-defaults.xml
- read in part: common/Disposition.scala (lines 1-100; Reasons constants checked by grep)
- not read: common/Counters.scala, io/RawRecord.scala
- modules/hadoop/src/test/**: not read

modules/entity-resolution/src/main:
- read in full: run/ErRun.scala, run/ErMain.scala, run/Report.scala, cluster/Clustering.scala, cluster/CannotLinks.scala, policy/Decide.scala, policy/Policy.scala, llm/LlmStage.scala, llm/Adjudication.scala, data/Loader.scala, data/Model.scala, features/PairFeatures.scala, work/WorkResolution.scala, review/ReviewQueue.scala, io/Csv.scala, io/Files.scala, normalize/Text.scala, normalize/Identifiers.scala, eval/GoldLabels.scala, resources/er/policy/er-v1.json
- read in part: eval/Harness.scala (lines 1-330: KeyRow, Proportion/Wilson, Metrics, weighted units)
- not read: eval/EvalReport.scala, eval/EvalMain.scala, eval/IntervalReliability.scala, resources/er/llm/adjudication-prompt-v1.txt, resources/er/llm/adjudication-output-schema-v1.json
- modules/entity-resolution/src/test/**: not read (searched only)

datasets:
- read in full: scripts/lib.sh, scripts/download_dblp.sh, scripts/download_openalex_snapshot.sh, scripts/download_orcid_public_data_file.sh, scripts/fetch_enrichment.py
- read in part: dataset-manifest.yaml (lines 30-69; other lines only through grep)
- not read: scripts/dslib.py, scripts/make_samples.py, scripts/dblp_subset_stats.py, scripts/estimate_subset_openalex.py, scripts/validate_datasets.py, scripts/test_datasets.py, scripts/validate.sh, schemas/*.md, samples/** (dblp XML, OpenAlex/Crossref/ORCID JSONL, selection.json), LICENSES.md

Outside the slice, read only to verify A-01: tests/contracts/src/test/scala/org/scholarchain/contracts/er/CannotLinkAdversarialSuite.scala, read in part (grep context of lines 13-114).

The verdict covers only the files listed as read in full or in part.

VERDICT: REQUEST_CHANGES
