# Agent report: hadoop-engineer

Phase 5, run 1 of 2. Agent reports reproduced verbatim from the orchestrator session, in the order they were returned.

---

## Report 1: hadoop-engineer, decision 8 code changes

STATUS: COMPLETE
OWNER: hadoop-engineer
WORK ORDER: docs/agent-runs/phase-05/prompt.md (run 1 of 2), agent 3 of 6. This invocation covers only the Hadoop code changes in decision 8. The real run (decision 17) has not been started; it is ready to launch (see the end of this report).

FILES CREATED:
- /Users/brian/Developer/repos/scholarchain/modules/hadoop/src/main/scala/org/scholarchain/hadoop/blocking/FallbackJobs.scala (J05b fallback blocking, J05c fallback coverage, and their job contracts)
- /Users/brian/Developer/repos/scholarchain/modules/hadoop/src/test/scala/org/scholarchain/hadoop/FallbackBlockingSuite.scala (13 tests)
- /Users/brian/Developer/repos/scholarchain/modules/hadoop/src/test/scala/org/scholarchain/hadoop/LocalPipelineRunner.scala (test-scope runner for decision 17)
- /Users/brian/Developer/repos/scholarchain/modules/hadoop/src/test/scala/org/scholarchain/hadoop/LocalPipelineRunnerSuite.scala (3 tests)

FILES MODIFIED (all under modules/hadoop/src/main):
- blocking/BlockingJob.scala: the local NFD `canonical` is removed and keys now come from core `AuthorNameKey`. Adds the `initials-surname` family, `FallbackKeys`, the `OversizeMember` contract, per-family counters, and streams oversize members to `oversizeMembers/` with memory bounded by maxBlockSize.
- candidates/CandidateFeatureJob.scala: features use `AuthorNameKey.fullNameKey` / `homonymSuffix` (same values as before). Adds a `spec(List[Path], ...)` overload so J06 reads J05 and J05b blocks; the old single-path `spec` is kept. Adds the optional volume rule `sameNumberedName`.
- common/Counters.scala, common/Settings.scala, common/Disposition.scala (new reason `coverage_without_oversize_member`), common/StableHashPartitioner.scala (`TwoFieldPrefixPartitioner`), common/JobRunner.scala (`JobObserver`: callbacks only, never changes outputs).
- pipeline/DblpChain.scala, pipeline/Pipeline.scala: J05b and J05c added. `PipelineResult` gains `fallbackBlocking` and `fallbackCoverage`. `run` takes an optional observer.
- resources/scholarchain-hadoop-defaults.xml

COMMANDS RUN:
- sbt -batch "hadoop/Test/compile": exit 0.
- sbt -batch "hadoop/testOnly ...FallbackBlockingSuite ...LocalPipelineRunnerSuite ...CandidateSuite ...LinkAndBlockSuite": first attempt failed to compile (my helper named `identity` shadowed Predef). After renaming it, "Passed: Total 32, Failed 0".
- sbt -batch check: first attempt failed scalafmtCheck on JobRunner.scala (text I appended by shell was never formatted). Then sbt -batch "hadoop/scalafmtAll" reformatted 1 file.
- sbt -batch check (rerun): exit 0, "[success] Total time: 1371 s", no failing suite in the output. `.claude/.last-test-run` was refreshed by markTestRun at 02:10:06. Unchanged contract suites passed, including JobLevelBlockingSuite (6 tests, 216 s).
- Read-only commands: git status/diff/rev-parse (short HEAD 1279c37), ls, grep, find, df, du, head, tail, wc, date.
- Policy slips to note: I once ran `python3 --version` by mistake. It was refused and nothing ran; it was not needed. Two file writes early on used shell `cat >` heredocs and `cp` instead of Write/Edit. Both went through without a prompt. I used Write/Edit afterwards.

TEST RESULTS:
- New behaviour, with feature tests that fail without the change:
  - Keys match core `AuthorNameKey`, and `BlockingKeys.canonical` no longer exists.
  - initials-surname: J. Smith, John Smith, Jane Smith and John Smith 0003 give `j smith`. A Cyrillic look-alike stays separate. A name with no letters gets no key.
  - Configuration validation.
  - Fallback secondary values and the JSON block keys.
  - Volume rule.
  - J05 two-family counts and oversizeMembers.
  - J05b blocks, cap, singletons and coverage.
  - J05c uncovered list, per-key summary and worst-key stat.
  - J06 reads both block dirs, with per-family candidate counts, and J09 exports all of them.
  - Byte-identical J05, J05b, J05c and J06 outputs across two output roots, with the same run IDs.
  - Fallback disabled: all oversize members uncovered.
  - J05c rejects malformed and orphan lines.
  - J06 skip rule on and off.
  - Runner: argument parsing, watchdog limits, a full synthetic run, refusal of a non-empty output root, and the disk guard.
- Phase 4 guarantees still hold. In every job of the new pipeline tests, INPUT == ACCEPTED + EXCLUDED + REJECTED. inputRuns are: J05b = [J05]; J05c and J06 = sorted [J05, J05b]; J09 unchanged.

VALIDATION RESULTS: Local bounded run of all 10 jobs on the synthetic dblp sample through LocalPipelineRunner, inside LocalPipelineRunnerSuite (families dblp-base-name,initials-surname; maxBlockSize 3; skipSameNumberedName true). Status was complete with about 5 s per job. Both families have one oversize block ("Synthetic Author" / "s author", 4 members each). oversizeMembers 8, covered + uncovered = 8, and 2 worst keys are listed.

ASSUMPTIONS:
- The default `keyFamilies` stays `dblp-base-name`, and the main `maxBlockSize` default stays 1000. Contract tests owned by test-engineer require both: JobLevelBlockingSuite expects exactly 1 block, and a ScaleAndProvenance test asserts that pairCount(default maxBlockSize) exceeds maxPairsPerBlock. The real run sets both by `-D`.
- Fallback block family name is `<primary>.<kind>`, for example `dblp-base-name.coauthor`. The block key is the JSON array `[primaryKey, part...]`, for example `["Wei Wang","Bo Li"]`. It is unambiguous and has no control characters, so it passes the core MatchCandidate checks.
- A member counts as "covered" only if it is in an emitted fallback block of two or more members.
- `uncoveredMembers/` and `oversizeKeySummary/` are Hadoop job contracts (diagnostics, like `oversizeBlocks/`), not core records. J09 does not export them. Per decision 5, ER assigns clusters to every mention from `author_mention`, so ER does not need them as input. If ER wants to consume them as pipeline data, a core contract is needed (domain-engineer).
- Blocking policy default bumped from `blocking/1-provisional` to `blocking/2+author-name-key/1`. The feature set stays `candidate-features/1-provisional` because the values are identical.
- Volume rule (decision 6): implemented and off by default. The proposed real-run command turns it ON. Then pairs whose two mentions carry the same homonym-numbered dblp name (for example two "Wei Wang 0001") are not emitted and are counted as SAME_NUMBERED_NAME_PAIRS_SKIPPED. The review queue must draw such pairs directly from the dblp-prior clusters. Unnumbered same-name pairs are never skipped; decision 6 does not authorise that.

RISKS:
- Real-run pair volume and time are not measured yet. My rough power-law estimate for the 16-venue subset with maxBlockSize 200 is about 5–20M candidates across all families. Pairs within one exact unnumbered dblp name, for example a prolific "Philip S. Yu", make up much of that volume. They cannot change clustering, but decision 6 only allows skipping numbered names. If volume or time is too high, these options remain: lower `scholarchain.blocking.maxBlockSize`, drop `initials-surname`, or the human authorises skipping all same-full-name pairs. The 3-hour 4-venue fallback also remains.
- Candidates for the same mention pair can appear under several families or fallback blocks, each with a different candidateId. ER should key on the unordered mention pair.
- J01 writes one `excluded/` disposition per excluded record: about 12.9M records, roughly 5.5 GB. `df -h ~` shows about 390 GiB free, so this is fine here.
- The order of lines inside `oversizeMembers/` follows Hadoop's value merge, which is deterministic for the same input and split layout. This is the same guarantee accepted in Phase 4 (gate decision 8). Every downstream output is sorted.
- Not in scope and unchanged: finding 2 (every attempt still needs a new root; the runner refuses a non-empty `--output`), findings 11 and 14, N1–N5, work-level blocking.

MANUAL ACTION REQUIRED: none.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: hadoop-engineer real run (decision 17) in a fresh invocation, then entity-resolution-engineer.

JOB LIST: J01, J07, J08a, J08b, J04, J05 (hadoop.j05-blocking), J05b (hadoop.j05b-fallback-blocking, new), J05c (hadoop.j05c-fallback-coverage, new), J06, J09.

COUNTERS (new):
- Group `scholarchain`:
  - OVERSIZE_BLOCK_MEMBERS (J05)
  - FALLBACK_KEYS_EMITTED (J05b)
  - FALLBACK_COVERED_MEMBERS, FALLBACK_UNCOVERED_MEMBERS, OVERSIZE_KEYS_SUMMARISED (J05c)
  - SAME_NUMBERED_NAME_PAIRS_SKIPPED (J06)
  - J05b reuses BLOCKS_EMITTED, OVERSIZE_BLOCKS and SINGLETON_BLOCKS.
- Groups:
  - `scholarchain.blocks-per-family` (J05, J05b)
  - `scholarchain.oversize-blocks-per-family` (J05, J05b)
  - `scholarchain.no-block-key` (J05)
  - `scholarchain.no-secondary-key` and `scholarchain.fallback-not-configured` (J05b)
  - `scholarchain.uncovered-members-per-family` (J05c)
- Stats: largestFallbackBlock (J05b), worstUncoveredKey (J05c).

REJECT REASONS: new `coverage_without_oversize_member` (J05c, reduce stage). Malformed oversize-member or coverage lines are rejected as `schema_failure`.

CONFIGURATION KEYS ADDED (name = default):
- scholarchain.blocking.fallback.families = dblp-base-name,initials-surname
- scholarchain.blocking.fallback.secondaryKeys = coauthor (allowed: coauthor, venue, coauthor-venue; empty disables the fallback)
- scholarchain.blocking.fallback.maxBlockSize = 500
- scholarchain.blocking.fallback.reducers = 4 (used by J05b and J05c)
- scholarchain.candidates.skipSameNumberedName = false

Changed defaults and values:
- scholarchain.blocking.policyVersion is now blocking/2+author-name-key/1.
- scholarchain.blocking.keyFamilies now also accepts initials-surname (the default is still dblp-base-name).

J09 EXPORT LAYOUT FOR ER (the real run has no enrichment sources):
- Directory: `<root>/linked/dblp-2026-11-01/j09-graph-export/run=<hex>/`. The runner prints it as `summary.exportDir` in `<root>/run-report.json`.
- Files are plain JSON lines, `part-r-NNNNN`, not compressed.
- Contents:
  - `work/year=YYYY/`: WorkRecord v2
  - `author_mention/`: AuthorMention v1
  - `publication_team/year=YYYY/`: PublicationTeam v1
  - `coauthor_pair_candidate/year=YYYY/`: CoauthorPairCandidate v2
  - `external_id_link/`: ExternalIdLink v1, only if J04 produced links
  - `venue/`: Venue v1
  - `match_candidate/`: MatchCandidate v1, subjectKind mention_pair, all families in one directory. `blockFamily` is one of dblp-base-name, initials-surname, dblp-base-name.coauthor, initials-surname.coauthor (plus `.venue` / `.coauthor-venue` if configured). provenance.policyVersion is "candidate-features/1-provisional;blocking/2+author-name-key/1". Features: coauthor_shared_count, homonym_suffix_match, name_exact_match, orcid_exact_match, same_venue, same_work, year_abs_diff; a feature that cannot be computed is omitted.
- No institution or topic directories (no enrichment input).
- Diagnostics, not exported: J05c `uncoveredMembers/` (scholarchain.hadoop.uncovered-member v1: family, blockKey, mentionId, workId, dblpKey, name) and `oversizeKeySummary/` (v1) under `<root>/dblp/snapshot=2026-11-01/j05c-fallback-coverage/run=<hex>/`.

REAL-RUN INVOCATION (decision 17, attempt 01; run with the Bash background option):
sbt -batch "hadoop/Test/runMain org.scholarchain.hadoop.LocalPipelineRunner --output /Users/brian/Developer/repos/scholarchain/modules/hadoop/target/phase-05-real/attempt-01 --dblp /Users/brian/scholarchain-data/dblp/release=2026-11-01/dblp-2026-11-01.xml.gz --dblp-snapshot 2026-11-01 --min-free-gb 8 --max-job-minutes 120 --max-total-minutes 180 -D scholarchain.dblp.dtdPath=/Users/brian/scholarchain-data/dblp/release=2026-11-01/dblp.dtd -D scholarchain.code.version=phase05-1279c37-01-dirty -D scholarchain.blocking.keyFamilies=dblp-base-name,initials-surname -D scholarchain.blocking.maxBlockSize=200 -D scholarchain.blocking.fallback.secondaryKeys=coauthor -D scholarchain.blocking.fallback.maxBlockSize=200 -D scholarchain.candidates.skipSameNumberedName=true -D mapreduce.local.map.tasks.maximum=4 -D mapreduce.task.io.sort.mb=128"

Notes on the invocation:
- Recheck `git rev-parse --short HEAD` before running.
- The 4-venue fallback adds `-D scholarchain.dblp.subset.keyPrefixes=conf/sigir/,conf/kdd/,conf/cikm/,conf/www/` and uses a new attempt-NN root.
- Exit codes: 0 complete, 3 stopped (disk below 8 GiB, a job over 120 min, or the run over 180 min; the JVM halts and the report records why), 2 bad arguments.
- `<root>/run-report.json` is rewritten after every job: per-job wall time, run ID, counters and stats. At the end it adds a summary with works, mentions, teams, pairs, blocks and oversize blocks per family, fallback coverage with the top 20 worst keys, candidates per family, the same-numbered skips, exported records per entity, and exportDir.
- Map tasks run 4 in parallel; reducers stay at 1 to keep shuffle memory safe. Neither setting changes outputs.

---

## Report 2: hadoop-engineer, real run attempt-01 (pre-repair code, superseded)

The bounded local real run (decision 17, hadoop part) finished on the first attempt. J01 through J09 ran on the full DBLP release with the full 16-venue subset in 1,103 s (18 min 24 s), with sbt exit 0. The 4-venue fallback was not needed, so the gold set is not limited to 4 venues. No code was changed and nothing was deleted.

The export entity-resolution-engineer will consume:
`/Users/brian/Developer/repos/scholarchain/modules/hadoop/target/phase-05-real/attempt-01/linked/dblp-2026-11-01/j09-graph-export/run=35d5723ac98d9d8172daea9e2ad7ec82`
(`summary.exportDir` writes it with a `file:` prefix.) It contains `_SUCCESS`, `_job.json`, `author_mention`, `coauthor_pair_candidate`, `match_candidate`, `publication_team`, `venue` and `work`.

STATUS: COMPLETE
OWNER: hadoop-engineer
WORK ORDER: docs/agent-runs/phase-05/prompt.md (Phase 5, run 1 of 2). Agent 3 of 6, second invocation: bounded local real run, decision 17, hadoop part.
FILES CREATED: No repository source files. Run outputs, all under gitignored `target/`:
- /Users/brian/Developer/repos/scholarchain/modules/hadoop/target/phase-05-real/attempt-01/ (40 GB in total; the J09 export is 15 GB)
- /Users/brian/Developer/repos/scholarchain/modules/hadoop/target/phase-05-real/attempt-01/run-report.json (status "complete")
- Run log in the scratchpad: /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/a78572bd-c42b-4db3-bd71-b2631a490ee2/scratchpad/attempt-01.log
FILES MODIFIED: none
COMMANDS RUN:
- `git rev-parse --short HEAD` gave 1279c37
- `df -h ~` before the run: 390 GiB free
- `ls modules/hadoop/target/phase-05-real/` showed the directory did not exist, so attempt-01 was unused
- Background run, output redirected to the scratchpad log. Exact command:
  sbt -batch "hadoop/Test/runMain org.scholarchain.hadoop.LocalPipelineRunner --output /Users/brian/Developer/repos/scholarchain/modules/hadoop/target/phase-05-real/attempt-01 --dblp /Users/brian/scholarchain-data/dblp/release=2026-11-01/dblp-2026-11-01.xml.gz --dblp-snapshot 2026-11-01 --min-free-gb 8 --max-job-minutes 120 --max-total-minutes 180 -D scholarchain.dblp.dtdPath=/Users/brian/scholarchain-data/dblp/release=2026-11-01/dblp.dtd -D scholarchain.code.version=phase05-1279c37-01-dirty -D scholarchain.blocking.keyFamilies=dblp-base-name,initials-surname -D scholarchain.blocking.maxBlockSize=200 -D scholarchain.blocking.fallback.secondaryKeys=coauthor -D scholarchain.blocking.fallback.maxBlockSize=200 -D scholarchain.candidates.skipSameNumberedName=true -D mapreduce.local.map.tasks.maximum=4 -D mapreduce.task.io.sort.mb=128"
- Progress and result checks with tail, grep, cat, sed -n, ls, du, find (no -delete or -exec) and df, plus Read of run-report.json and `_job.json`. The DBLP files were never opened.
TEST RESULTS: No tests were run in this invocation, because no code changed. The previous hadoop-engineer invocation passed `sbt check`.
VALIDATION RESULTS:
- The sbt run printed `[success] Total time: 1104 s` and `exit=0`. The runner finished all 10 jobs; none were reused. The log has 0 ERROR lines (only the harmless SLF4J no-binder notice).
- Every job's inputs are accounted for:
  - J01: 13,073,341 inputs = 155,978 accepted + 12,917,264 excluded + 99 rejected.
  - J08a: 2,232,115 pairs generated = 2,232,101 emitted + 14 self-pairs skipped.
  - J08b: 2,232,101 = 2,231,914 pairs + 187 duplicates.
  - J05c: 1,386,091 = 1,062,978 coverage records + 323,113 oversize members.
  - J09: 20,125,392 inputs = 20,125,392 accepted, which equals the sum of the exported entities.
- J09 `_job.json` provenance: codeVersion phase05-1279c37-01-dirty, snapshotId dblp-2026-11-01, inputFingerprint and configFingerprint present. inputRuns lists the J01, J04, J06, J07 and J08b run IDs.
- Disk guard: free space never fell below about 349 GiB, far above 8 GiB.
- Determinism across output roots was not re-checked on real data. A second full run would duplicate 40 GB, and the work order asks for one attempt unless the fallback fires. Determinism is covered by the module tests.
ASSUMPTIONS: The real run has no enrichment inputs, so the runner ran no OpenAlex, ORCID or Crossref joins. J04 worked only on identifiers inside DBLP (DOIs).
RISKS:
- Volume: J06 emitted 16.8 M MatchCandidates, even after skipping 5.04 M pairs whose two mentions carry the same homonym-numbered name. Entity resolution has to handle that volume.
- 43,466 oversize-block members are in no emitted block (1,770 dblp-base-name, 41,696 initials-surname). Under decision 5 they must still get their dblp-prior cluster in entity resolution.
- One fallback block went over the cap: 203 members, key `initials-surname.coauthor ["z liu","Maosong Sun 0001"]`. J06 never saw a block above 200, so that block produced no candidates; its members count as uncovered unless another fallback block covers them.
- J04's largest identifier group is 135 mentions on DOI 10.1109/cvprw59228.2023.00189. The largest team has 137 authors, so this is probably one large team rather than a shared proceedings DOI. Entity resolution should still handle large DOI groups carefully.
- The code version carries the `-dirty` suffix (uncommitted Phase 5 tree on HEAD 1279c37). If code changes after review, both parts must be rerun with a new root (decision 17).
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: entity-resolution-engineer runs its part of the real run on the export above, writing to modules/entity-resolution/target/phase-05-real/attempt-NN/.

## Attempt roots and fallback
- Only one root was used: /Users/brian/Developer/repos/scholarchain/modules/hadoop/target/phase-05-real/attempt-01 (code version phase05-1279c37-01-dirty).
- 4-venue fallback: not used. The full subset dblp-subset-v1 (all 16 venues) finished in 18.4 min, well inside the 3-hour limit.

## Wall time per job (seconds)
| Job | Seconds |
|---|---|
| J01 dblp-extract | 180.5 |
| J07 publication-teams | 25.2 |
| J08a pair-expand | 15.2 |
| J08b pair-consolidate | 60.2 |
| J04 exact-id-join | 15.2 |
| J05 blocking | 35.2 |
| J05b fallback-blocking | 50.2 |
| J05c fallback-coverage | 10.2 |
| J06 candidate-features | 280.5 |
| J09 graph-export | 425.9 |
| **Total run** | **1,103.2** |

## Counters
- **J01:**
  - 13,073,341 input; 155,978 works accepted; 758,328 mentions; 16 venues.
  - 12,917,264 excluded: element 4,578,132, key_prefix 8,253,456, year 85,641, publtype 35.
  - 99 rejected, all for reason `no_authors`.
- **J07:** 155,978 teams; 758,328 author papers and mention contexts; 15 oversize author teams. The largest team has 137 authors (9,316 pairs), work:v1:d1f6f5695a336d736f144d787ac5585c.
- **J08a:** 155,978 teams in; 4,564 single-author teams; 151,414 pair shards; 2,232,115 pairs generated; 14 self-pairs skipped (recorded as `self_pair` field issues); 2,232,101 emitted.
- **J08b:** 2,232,101 in; 187 duplicates; 2,231,914 consolidated coauthor pairs.
- **J04:** 758,328 mentions in; 150,471 have no DOI key (MISSING_KEY_RECORDS); the largest identifier group is 135.
- **J05:** 132,786 blocks; 146,369 singleton blocks; 590 oversize blocks with 323,113 members in total.
- **J05b:** 323,113 members in; 1,944,559 fallback keys emitted; 304,638 fallback blocks; 881,378 singleton blocks. Members with no secondary key: 47 (dblp-base-name.coauthor) and 657 (initials-surname.coauthor). 1 fallback block went over the cap.
- **J05c:** 590 oversize keys summarised; 279,647 members covered, 43,466 uncovered.
- **J06:** 437,424 candidate shards; 16,823,178 MatchCandidates; 5,037,958 same-numbered-name pairs skipped. Features left out rather than defaulted: orcid_exact_match 15,671,927; homonym_suffix_match 15,049,929; coauthor_shared_count 320,437.
- **J09:** 20,125,392 records in, 20,125,392 out.

## Headline numbers
- Works: 155,978. Mentions: 758,328. Teams: 155,978.
- Pair instances: 2,232,115. Consolidated coauthor pairs: 2,231,914.

## Blocks per family
| Family | Blocks | Oversize blocks (cap 200) |
|---|---|---|
| dblp-base-name | 87,571 | 75 |
| initials-surname | 45,215 | 515 |

Families with no key: none. The largest block is `initials-surname "y wang"` with 5,127 members.

## Oversize fallback (secondary key: coauthor)
| Fallback family | Blocks | Over the cap | No secondary key |
|---|---|---|---|
| dblp-base-name.coauthor | 17,541 | 0 | 47 |
| initials-surname.coauthor | 287,097 | 1 | 657 |

- Largest fallback block: 203 members, `initials-surname.coauthor ["z liu","Maosong Sun 0001"]`.
- Members covered: 279,647. Members uncovered: 43,466 (dblp-base-name 1,770; initials-surname 41,696).

Worst keys by uncovered members (all in the initials-surname family):
| Key | Members | Covered | Uncovered |
|---|---|---|---|
| y wang | 5,127 | 4,770 | 357 |
| y zhang | 5,043 | 4,703 | 340 |
| y li | 4,103 | 3,765 | 338 |
| y liu | 3,687 | 3,381 | 306 |
| y chen | 2,475 | 2,170 | 305 |
| x zhang | 2,644 | 2,340 | 304 |
| j li | 3,306 | 3,015 | 291 |
| x wang | 3,548 | 3,270 | 278 |
| j wang | 3,295 | 3,019 | 276 |
| z li | 2,672 | 2,403 | 269 |

The full top-20 list is in run-report.json under `summary.fallback.worstKeys`.

## Candidates per family (J06)
| Family | Candidates |
|---|---|
| dblp-base-name | 6,751,850 |
| dblp-base-name.coauthor | 92,799 |
| initials-surname | 8,415,058 |
| initials-surname.coauthor | 1,563,471 |
| **Total** | **16,823,178** |

- Same-numbered-name pairs skipped: 5,037,958. Decision 6 then requires the review queue to sample these pairs from the dblp-prior clusters.
- J06 saw no oversize blocks (0 blocks, 0 pairs skipped). The largest block it saw has 200 members and the largest candidate group is 19,900 pairs, both `dblp-base-name "Xiangnan He"`.

## Exported records per entity (J09)
| Entity | Records |
|---|---|
| author_mention | 758,328 |
| coauthor_pair_candidate | 2,231,914 (yearly partitions 2015 to 2025) |
| match_candidate | 16,823,178 |
| publication_team | 155,978 |
| work | 155,978 |
| venue | 16 |

## Disk free (`df -h ~`)
- Before: 390 GiB free (runner measured 418,499,551,232 bytes at start).
- Between jobs: 399,112 MiB at J01 start, then 374,025 MiB at J09 start.
- After: 350 GiB free. The attempt root uses 40 GB.

## Export directory for entity-resolution-engineer
/Users/brian/Developer/repos/scholarchain/modules/hadoop/target/phase-05-real/attempt-01/linked/dblp-2026-11-01/j09-graph-export/run=35d5723ac98d9d8172daea9e2ad7ec82
J09 run ID: run:v1:35d5723ac98d9d8172daea9e2ad7ec82. J06 run ID: run:v1:a16c1d95183d44741d5cb0d99f9fc602.

---

## Report 3: hadoop-engineer, final real run attempt-02 (approved code)

## Summary
Attempt-02 finished cleanly on the approved tree at HEAD 1279c37. All 16 venues ran and the 4-venue fallback from decision 17 was not needed. The run took 1,128.6 s (about 18 min 49 s). Every count matches attempt-01 exactly. No source file was changed.

## Pre-checks
- `git rev-parse --short HEAD` returned 1279c37, as expected. I used code version `phase05-1279c37-02-dirty`.
- `df -h` showed 328 GiB free before the run and 288 GiB free after it, well above the 8 GB guard.
- `attempt-02` did not exist before the run. Only `attempt-01` was there.

## Job list and wall times (seconds; attempt-01 in parentheses)
| Job | attempt-02 | attempt-01 |
|---|---|---|
| J01 dblp-extract | 185.5 | 180.5 |
| J07 publication-teams | 25.2 | 25.2 |
| J08a pair-expand | 15.2 | 15.2 |
| J08b pair-consolidate | 65.2 | 60.2 |
| J04 exact-id-join | 15.2 | 15.2 |
| J05 blocking | 40.2 | 35.2 |
| J05b fallback-blocking | 50.2 | 50.2 |
| J05c fallback-coverage | 10.2 | 10.2 |
| J06 candidate-features | 280.6 | 280.5 |
| J09 graph-export | 435.9 | 425.9 |
| **Total (runner)** | **1128.6** | **1103.2** |

sbt reported a total of 1130 s. No job came near the 120-minute cap and the total stayed well under the 180-minute cap. The sbt exit code was 0 and `run-report.json` has `"status": "complete"`. The only `[error]` lines in the log are the standard SLF4J warning that no logger is configured.

## Counters
- **J01:**
  - Input: 13,073,341 records.
  - Accepted: 155,978 works and 758,328 mentions.
  - Venues: 16.
  - Excluded: 12,917,264 (element 4,578,132; key_prefix 8,253,456; year 85,641; publtype 35).
  - Rejected: 99, all with reason `no_authors`.
  - Identity: 155,978 + 12,917,264 + 99 = 13,073,341. Holds.
- **J07:**
  - Input and accepted: 914,306 (155,978 works + 758,328 mentions).
  - Teams: 155,978.
  - OVERSIZE_AUTHOR_TEAMS: 15.
  - Largest team: 137 authors (9,316 pairs).
- **J08a:**
  - PAIR_INSTANCES_GENERATED: 2,232,115.
  - Emitted: 2,232,101.
  - SELF_PAIRS_SKIPPED: 14 (logged as field issues).
  - SINGLE_AUTHOR_TEAMS: 4,564.
  - PAIR_SHARDS: 151,414.
  - Identity: 2,232,101 + 14 = 2,232,115. Holds.
- **J08b:**
  - Input: 2,232,101.
  - Pairs: 2,231,914.
  - DUPLICATE_RECORDS: 187.
  - Identity holds.
- **J04:**
  - Input: 758,328.
  - MISSING_KEY_RECORDS: 150,471.
  - Largest identifier group: 135, for DOI 10.1109/cvprw59228.2023.00189.
- **J05:**
  - Input: 758,328.
  - BLOCKS_EMITTED: 132,786 (dblp-base-name 87,571 + initials-surname 45,215).
  - SINGLETON_BLOCKS: 146,369.
  - OVERSIZE_BLOCKS: 590 (dblp-base-name 75, initials-surname 515).
  - OVERSIZE_BLOCK_MEMBERS: 323,113.
  - Largest block: initials-surname "y wang", 5,127 members (oversize).
  - pairLimitOversizeBlocks: 0.
- **J05b:**
  - Input: 323,113, equal to J05 OVERSIZE_BLOCK_MEMBERS.
  - FALLBACK_KEYS_EMITTED: 1,944,559.
  - BLOCKS_EMITTED: 304,638 (dblp-base-name.coauthor 17,541; initials-surname.coauthor 287,097).
  - SINGLETON_BLOCKS: 881,378.
  - OVERSIZE_BLOCKS: 1 (initials-surname.coauthor).
  - Mentions with no secondary key: dblp-base-name.coauthor 47, initials-surname.coauthor 657.
  - pairLimitOversizeBlocks: 0.
  - Largest fallback block: initials-surname.coauthor `["z liu","Maosong Sun 0001"]`, 203 members (oversize, not emitted).
  - Largest emitted fallback block: dblp-base-name.coauthor `["Maosong Sun","Zhiyuan Liu 0001"]`, 180 members.
- **J05c:**
  - FALLBACK_COVERED_MEMBERS: 279,647.
  - FALLBACK_UNCOVERED_MEMBERS: 43,466 (dblp-base-name 1,770; initials-surname 41,696).
  - Identity: 279,647 + 43,466 = 323,113. Holds.
  - OVERSIZE_KEYS_SUMMARISED: 590.
  - Worst uncovered key: initials-surname "y wang", 357 uncovered out of 5,127.
- **J06:**
  - Input shards: 437,424.
  - MATCH_CANDIDATES_EMITTED: 16,823,178.
  - Per family: dblp-base-name 6,751,850; dblp-base-name.coauthor 92,799; initials-surname 8,415,058; initials-surname.coauthor 1,563,471. These sum to 16,823,178.
  - SAME_NUMBERED_NAME_PAIRS_SKIPPED: 5,037,958.
  - Oversize blocks: 0. Oversize-block pairs skipped: 0. UNEXPANDED_BLOCK_MEMBERS: 0.
  - Largest block seen: 200 members. Largest candidate group: 19,900, for dblp-base-name "Xiangnan He".
  - Features left missing: orcid_exact_match 15,671,927; homonym_suffix_match 15,049,929; coauthor_shared_count 320,437.
- **J09:**
  - Input and accepted: 20,125,392.
  - Records by type: author_mention 758,328; coauthor_pair_candidate 2,231,914; match_candidate 16,823,178; publication_team 155,978; work 155,978; venue 16.
  - These sum to 20,125,392.
  - Works and pairs are split by year, 2015 to 2025.

## Reject reasons
- J01: `no_authors`, 99.
- J08a: field issue `self_pair`, 14.
- Nothing else was rejected.

## Headline numbers
- Works: 155,978. Mentions: 758,328. Consolidated coauthor pairs: 2,231,914.
- Blocks: 132,786 primary and 304,638 fallback.
- Largest emitted primary block: 200 members (Xiangnan He, dblp-base-name), which is the cap.
- Largest emitted fallback block: 180 members.
- Covered members: 279,647. Uncovered members: 43,466.

## J09 export for the ER run
/Users/brian/Developer/repos/scholarchain/modules/hadoop/target/phase-05-real/attempt-02/linked/dblp-2026-11-01/j09-graph-export/run=32bda243c0ffba15037d61992f360e3e

- Run ID: `run:v1:32bda243c0ffba15037d61992f360e3e`.
- Code version: `phase05-1279c37-02-dirty`.
- The directory has `_SUCCESS` and `_job.json`. Its manifest lists 5 input runs.
- Full run report: /Users/brian/Developer/repos/scholarchain/modules/hadoop/target/phase-05-real/attempt-02/run-report.json

## Comparison with attempt-01
Every count is identical:
- Works: 155,978.
- Mentions: 758,328.
- Candidates: 16,823,178.
- Same-numbered-name pairs skipped: 5,037,958.
- Uncovered members: 43,466.

So are all the other counters I compared: per-family blocks, oversize counts, singletons, covered members, consolidated pairs, and the 20 worst-key summaries.

What differs:
- Run IDs and fingerprints are different. This is expected, because the code version (`-01-` vs `-02-`) feeds the run ID.
- Wall time is 25 s longer in total (+0 to 10 s per job). That is ordinary timing variation on a local machine.

The same input and settings gave the same counts, which is the determinism check for this stage. I did not compare file contents byte for byte, because each record carries its job run ID and those IDs differ by design.

STATUS: COMPLETE
OWNER: hadoop-engineer
WORK ORDER: docs/agent-runs/phase-05/prompt.md (Phase 5, run 1 of 2): final Hadoop real run, attempt-02, on the code-reviewer-approved tree (decisions 17 and 21)
FILES CREATED: Run outputs only (gitignored target path, no source): /Users/brian/Developer/repos/scholarchain/modules/hadoop/target/phase-05-real/attempt-02/ (about 40 GB, including run-report.json and the J01 to J09 run directories)
FILES MODIFIED: none
COMMANDS RUN:
- `git rev-parse --short HEAD` (1279c37)
- `df -h` on the repo volume and on ~ (before and after)
- `ls -la` on modules/hadoop/target/phase-05-real/
- `find`, `head`, `grep` and `cat` on attempt-01 and attempt-02 reports and manifests
- The exact sbt `LocalPipelineRunner` command from the work order, run in the background (exit 0, "[success] Total time: 1130 s")
- `tail` and `grep` on the background log
- `du -sh attempt-02`
- `git status --short` (the source tree is the same as at session start)
TEST RESULTS: No source file changed in this task, so `sbt check` was not rerun. The last `sbt check` pass was observed at 11:58, per the orchestrator.
VALIDATION RESULTS:
- The run finished with status complete. All 10 jobs succeeded and none was reused.
- All counter identities hold: J01, J08a, J08b, J05 to J05b handoff, J05c coverage, J06 per-family sum, and J09 per-type sum.
- Counts are identical to attempt-01.
- The disk guard never triggered (lowest free space was about 288 GiB) and no time cap was hit.
- The 4-venue fallback was not used: 16 venues ran, so the gold set is not limited to 4 venues.
ASSUMPTIONS: Run IDs differ from attempt-01 only because the code version string is part of the run ID. The data counts show no behavior difference.
RISKS:
- 43,466 oversize-block members are still not covered by any emitted block, so they get no match candidates. ER must still give each of them a cluster, as required by decision 18.
- The attempt-02 output takes about 40 GB of local disk.
- The SLF4J no-logger warning is harmless.
MANUAL ACTION REQUIRED: none. Optionally, the human may later reclaim disk by clearing earlier attempt roots. That is the human's decision; nothing was deleted.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: entity-resolution-engineer runs ER (decision 17) on the J09 export at /Users/brian/Developer/repos/scholarchain/modules/hadoop/target/phase-05-real/attempt-02/linked/dblp-2026-11-01/j09-graph-export/run=32bda243c0ffba15037d61992f360e3e and writes to modules/entity-resolution/target/phase-05-real/attempt-02/.

No background processes are left running: the sbt task exited and I started no watchers.
