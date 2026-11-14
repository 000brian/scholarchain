STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 4 review of the full uncommitted diff against HEAD 6788952 (docs/agent-runs/phase-04/prompt.md), plus the seven questions the orchestrator asked me to rule on
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools)
TEST RESULTS: Not run, because I have no Bash. I did not observe the claimed `sbt check` (exit 0, module tests 787/787, contract tests 87/87). I read the tests themselves (see the weak-test review below).
VALIDATION RESULTS:
- Method: I read the tracked diff at /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/183f6251-40b4-4bce-9aa1-4ee577720ebd/scratchpad/phase-04/tracked.diff in full (1081 lines). I also read every main source file under modules/hadoop/src/main, the defaults XML, the key hadoop and contract test suites, and the local bounded-run artifacts under modules/hadoop/target/local-bounded-run/.
- Ownership: every changed file is inside its writer's territory.
  - build.sbt: build-engineer.
  - modules/core/**: domain-engineer.
  - modules/hadoop/**: hadoop-engineer.
  - tests/contracts/**: test-engineer.
  - docs/agent-runs/phase-04/ contains only prompt.md, which is human-owned.
  - No production module depends on `services`. `hadoop` depends only on coreScala3.
  - Hadoop dependencies are Provided in `hadoop`, and Test-only in contractTests (version.hadoop=3.4.2).
- Core: the changes stay within the four approved items (detail under Q7). Breaking bumps are correct: WorkRecord 1->2, CoauthorPairCandidate 1->2, MatchCandidate new at 1. The superseded wire/v1 files are unchanged and new wire/v2 files were added. The code uses no Scala-3-only syntax.
ASSUMPTIONS: The diff file reflects the current working tree. The report under modules/hadoop/target/local-bounded-run/ shows all 20 jobs with reused=false. Because the config fingerprint changed when the operational keys were excluded, it was most likely produced after that repair, but I cannot confirm that.
RISKS: I could not check runtime behaviour directly (no command execution). Findings 2-14 are accepted risks, to be routed to later phases.
MANUAL ACTION REQUIRED: Route finding 1 to hadoop-engineer, then re-review. The human decides whether to accept the minor findings as documented risks.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: hadoop-engineer repairs finding 1, then `sbt check` (output observed), then code-reviewer re-review (review-report-02).

VERDICT: REQUEST_CHANGES

## Rulings on the specific questions

**Q1. Code-version stale reuse.** Not blocking for Phase 4. Accept it as a risk, but close it before the first EMR or bounded cloud run (finding 2).
- Phase 4 is local only, and `scholarchain.code.version` has no default (verified by test).
- The local evidence is fresh: every job shows reused=false.
- Routing: hadoop-engineer (check `_job.json` on reuse), build-engineer (embed a build identity), infra-engineer (scripts supply the commit), test-engineer (invert the "documented gap" test).

**Q2. J08b provenance.** Not acceptable. This is the one blocking finding (finding 1).
- J08b creates a new record (the union of positions) that J08a never wrote.
- Its provenance still names the J08a run and job.

**Q3. Hadoop-internal job contracts.** Acceptable. This is not "redefining shared records".
- NormalizedSource, MentionContext, CandidateBlock and Disposition are passed only between Hadoop jobs.
- docs/agents.md gives hadoop-engineer ownership of "rejected-record formats" (Disposition).
- The J09 export contains only core records. `ExportEntity.all` (GraphExportJob.scala:63-64) lists Work, Mention, Team, Pair, Link, Inst, TopicE, VenueE and Candidate, each decoded and checked through core `Contract.decode` (lines 168-176).
- Side effect: enrichment abstracts, authorships and affiliations never reach the export (finding 11).

**Q4. NFD folding.** Acceptable for Phase 4. It should move to core in Phase 5 (finding 10).
- Entity resolution and Spark both need the same canonical-equivalence rule.
- Today CoauthorPairCandidate names are verbatim, so NFC and NFD spellings of one name would produce separate pairs and edges downstream.

**Q5. J06 features.** Confirmed policy-free (CandidateFeatureJob.scala:27-102).
- The features are 0/1 equality flags, a shared-coauthor count and an absolute year difference.
- There are no thresholds, weights, scores or decisions.
- A feature that cannot be computed is left out, never filled with a default.
- The MatchCandidate wire test confirms there is no outcome, label or confidence field.
- The configured feature set is checked against the implemented one (lines 202-209).

**Q6. Fingerprint exclusions.** The three excluded keys are correct.
- `replaceIncomplete` only controls recovery.
- `intermediate.compress` and `intermediate.codec` only affect the shuffle; every job has reducers, so reducer input is the same either way.
- No key that changes output is excluded.
- Fingerprinting the reducer counts and shard sizes is a defensible choice (they change the part-file layout and counters).
- Gaps remain:
  - the DTD file's content is not fingerprinted (finding 4);
  - Hadoop split size and input directory location are not fingerprinted, so the "byte-identical output" claim is overstated (finding 5).

**Q7. Scope widening.** Confirmed within scope.
- Supporting types were added only as parts of the approved items: MatchSubjectKind, MatchCandidateId, PairPosition with `forNames`, and `OpenAlexId.entities` (a small helper that comes with Topic).
- No arXiv or Semantic Scholar types and no extra blocking families were added.
- The hadoop changes stay within the work order: J01-J09, no AWS, no EMR script, no new downloads, Provided Hadoop dependencies.

## Blocking findings

| # | Severity | file:line | Failure input / evidence | Impact | Owner |
|---|---|---|---|---|---|
| 1 | major | modules/hadoop/src/main/scala/org/scholarchain/hadoop/pairs/PairJobs.scala:184-191 (`instances.head.copy(positions = ...)`), 216-221; doc at 142-149 | Synthetic homonym work conf/sigir/SyntheticA24 (names 0001, 0002, 0001). Observed output: `.../j08b-pair-consolidate/run=1bb414a3d86e82ef4677bdefb6fea83b/pairs/part-r-00002` contains `"runId":"run:v1:da8048589e31255386369af29ddc8032","jobName":"hadoop.j08a-pair-expand"`, which is the J08a run. The consolidated record with positions `[(0,1),(4,1)]` does not exist in J08a's pairInstances. | Breaks the CLAUDE.md provenance rule ("job or run ID" for every data transformation): the record names a producing run that never wrote it. The same wrong provenance flows into the J09 export and on to Spark. It is also inconsistent with J07, J08a and J06, which all stamp their own run. | hadoop-engineer |

**Required change for finding 1:**
1. After the facts check in `consolidate`, re-stamp the record with `io.run.provenance(sourceType, sourceRecordIds, contentHash, datasetId, snapshotId)`, keeping the source fields from the instances.
2. Record the upstream run IDs in plain form in `_job.json` (for example `inputRuns`). `fingerprintRuns` stores only a hash, so without this the J08b-to-J08a lineage is lost.
3. Update the scaladoc.
4. Add a feature test in DblpChainSuite asserting that consolidated pairs carry `r.consolidate.runId` and `hadoop.j08b-pair-consolidate`.

## Non-blocking findings (minor; accepted risks to route)

| # | Severity | file:line | Failure input / evidence | Impact | Owner |
|---|---|---|---|---|---|
| 2 | minor (gate before the first cloud run) | hadoop/common/JobRunner.scala:66-71, 84-86 | Code changes but the `-D scholarchain.code.version` string stays the same, so the completed directory is reused unchecked. Shown by JobLevelRerunSuite.scala:95-117 (a part file was overwritten and the rerun reused it). | Stale outputs can be presented as current. | hadoop-engineer: on reuse, check that `_job.json` jobName, runId, codeVersion, inputFingerprint and configFingerprint match, and record per-file sizes/sha256 so they can be verified. build-engineer: embed the build identity (commit plus dirty flag, or assembly sha256) in the assembly. hadoop-engineer: reject a code.version that disagrees with it. infra-engineer: scripts pass the commit. |
| 3 | minor | hadoop/dblp/DblpEntities.scala:81-101; DblpExtractJob.scala:165 | An internal subset `<!ENTITY big "...~1 MiB...">` plus about 300k `&big;` references in a 1 MiB record expands to hundreds of GB. Single-pass expansion prevents exponential blow-up, but quadratic blow-up is still possible. The DTD is sha256-pinned in the manifest, so an attacker would need to tamper with the raw zone. | Task runs out of memory and the J01 job fails (denial of service). There is no silent corruption. | hadoop-engineer: cap the expanded size with a configurable key and reject with a reason. test-engineer: add the adversarial case. |
| 4 | minor | DblpExtractJob.scala:105-117; pipeline/DblpChain.scala:34 | `scholarchain.dblp.dtdPath` is fingerprinted only as a path string. If the DTD file content changes at the same path, the run ID stays the same and the directory is reused. | Different entity decoding under the same run ID. | hadoop-engineer: include the DTD file in `fingerprintFiles` when the key is set. |
| 5 | minor (claim) | common/Settings.scala:83-90; scholarchain-hadoop-defaults.xml:20-21; JobRunner.scala:249-259 | (a) Changing Hadoop split size or block size changes the number of map tasks. That changes the map-side `part-m-*` reject/issue/stat files and the `output-records` counters in `_job.json`, but the run ID stays the same. (b) The input fingerprint uses basenames only, while disposition `location` strings contain the absolute input path. | "A run ID always identifies byte-identical output" is not true as written. | hadoop-engineer: weaken the claim to "identical records", or fingerprint the split keys and relative paths. |
| 6 | minor | pipeline/Pipeline.scala:24-30; ScholarChainHadoop.scala:24; JobRunner.scala:93 | `--dblp-snapshot ../../../../x` is not validated (SourceInput validates its snapshot, line 18-21). The value becomes part of the output path that `fs.delete(out, true)` can remove when replaceIncomplete=true. | Path escape from the output root. Low likelihood because the value is operator-supplied. | hadoop-engineer: apply the same `[A-Za-z0-9._-]+` check. |
| 7 | minor (performance) | pairs/PairExpansion.scala:85-86 | `team.authorNames(i)` on a `List` (core Records.scala:176) is O(n). A team at the 5000 hard limit gives about 12.5M pairs times about 2500 steps, roughly 6e10 traversals. | Very slow J08a reducers on giant teams. | hadoop-engineer: convert to an IndexedSeq once per shard. |
| 8 | minor | hadoop/io/RawRecord.scala:79 | `PreviewBytes = 4096` is hardcoded. A `scholarchain.reject.previewChars` above 4096 is silently capped. | Goes against decision 4 ("no hardcoded thresholds"). | hadoop-engineer: derive it from configuration or document the cap. |
| 9 | minor (claim) | io/DblpRecordScanner.scala:15-23 | A split that starts inside a comment or CDATA section containing a literal `<article key=...>...</article>` makes the next reader capture a phantom record. If it shares the real key, J01 rejects both as a duplicate-key conflict. | The doc claims every record is read by exactly one reader, which has an undocumented exception. Real dblp has no such comments. | hadoop-engineer: document the limitation (or detect it). test-engineer: optional mid-comment split case. |
| 10 | advisory | blocking/BlockingJob.scala:104-112; CandidateFeatureJob.scala:31-34 | NFD canonicalization is defined only in hadoop. | Entity resolution and Spark may drift from it; NFC and NFD names form different pairs. | domain-engineer (core text-key helper, Phase 5), then hadoop-engineer switches to it. |
| 11 | advisory | sources/SourceNormalizers.scala:36-81; GraphExportJob.scala:63-64 | Abstracts, authorships and affiliations survive only in hadoop-internal NormalizedSource, not in the J09 export (work order job 7, "other selected enrichment files"). | RAG and Spark cannot use them. | domain-engineer (core contract for non-dblp works and authors, Phase 5), then hadoop-engineer exports it. |
| 12 | advisory | teams/TeamAssembler.scala:18-35; SourceNormalizers.scala:54-77 | MentionContext and NormalizedSource carry no runId, jobName or policyVersion. They can be attributed only by their directory path. | Weak provenance if a file is copied out of its run directory. | hadoop-engineer |
| 13 | advisory (weak test) | tests/contracts/.../JobLevelRerunSuite.scala:95-117 | The test passes only while the gap in finding 2 exists. | It will fail when finding 2 is fixed; it must be inverted then. | test-engineer |
| 14 | advisory (scale) | io/ScholarChainInputFormats.scala:56-61 | The real input dblp-2026-11-01.xml.gz is gzip, which cannot be split, so all of J01 runs in one mapper. | A J01 bottleneck on EMR. | hadoop-engineer / infra-engineer (staging for a splittable input), when the EMR phase starts |

## Checklist results (no further findings)
- **Record-boundary safety:**
  - A record starts at the `<` inside its own split, and the split reader finishes any record it started.
  - The end tag allows whitespace before `>`.
  - Quotes in attributes are honoured.
  - Truncation inside a start-tag name and records inside comments are both handled (F1-F3 repairs verified in code).
  - LineScanner follows LineRecordReader's split rule and reports over-long lines instead of skipping them.
- **XML security:** StAX has SUPPORT_DTD=false and IS_SUPPORTING_EXTERNAL_ENTITIES=false. SYSTEM entities are not collected, and expansion is single-pass. The decompression guard is applied per file.
- **Determinism:**
  - Every reducer sorts or dedups values (`distinct.sorted`) before writing.
  - Buffering is cut off only on paths where the group is rejected anyway.
  - SHA-256 stable partitioner; speculative execution is off.
  - MaxTracker ties are broken by code point.
  - J04 links are sorted by linkId.
- **No mutable shared state:** TaskIO, MaxTracker and parsers are created per task, and RunContext is read-only from the configuration.
- **Counters:** all ten counters the work order requires are present (Counters.scala:21-44).
- **Intermediate compression:** on by default with DefaultCodec.
- **Output paths:** `<root>/<dataset>/snapshot=<id>/<job>/run=<hex>/`, which is idempotent.
- **Thresholds:** every threshold is in scholarchain-hadoop-defaults.xml with a description (SettingsSuite checks this). The one exception is finding 8.
- **Entity-resolution over-merging:** J04 links only on exact one-to-one ORCID or DOI matches; ambiguous groups go to conflicts/v1 and are not linked. Blocks and candidates carry no decision.
- **Pair math:** `PairIndex` gives n(n-1)/2 pairs with sharding that leaves no gaps or overlaps (PairsAndTeamsSuite). The counters are checked against real samples (33 pairs) and synthetic samples (11 instances, 1 self pair, 7 consolidated pairs).
- **Tests:** no tautological tests found apart from finding 13. The XXE test is meaningful on macOS (/etc/passwd contains "root:").
