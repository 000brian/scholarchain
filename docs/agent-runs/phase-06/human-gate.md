# Phase 6 human gate

Phase 6 built the Spark pipeline. It turns the Phase 5 results into a coauthor graph and computes the collaboration measures for 5 time windows. It ran once on my laptop on the full 16-venue data. The EMR run waits until after deployment.

## Gate
- Spark does real distributed work and graph analysis: met on my laptop (`local[6]`, 32 shuffle partitions). It ran on the full data, with every algorithm written as Spark DataFrame steps (agent-report-spark.md). The EMR run is deferred (see Deferred).
- The whole graph is never collected to the driver: met. The reviewer checked every collect. Each one is small and has a fixed limit (review-report.md, decision 12 ruling 1).
- Distinct coauthor counts and pair weights pass small known-graph tests: met. The reviewer checked the test graph by hand. The tests fail if coauthors are counted twice or self-pairs are kept (review-report.md, ruling 2).
- Components, communities, paths and k-core are shown on hand-checkable test graphs: met (review-report.md, ruling 2; 28 Spark tests, later 29).
- Every bridge measure is defined and its limits are stated: met in agent-report-spark.md ("ALGORITHM NOTES": participation coefficient, cross-community degree, communities connected, and no betweenness). The reviewer did not have that report when he raised F7. The code itself has only short comments.
- Time windows are explicit in the outputs: met. Every windowed table has a window field and a window folder, and a test checks this (review-report.md, ruling 6).
- Partitioning and shuffles are documented: met (agent-report-spark.md, "Spark settings, partitioning, shuffles, checkpoints").
- Code review returns APPROVE: met (review-report-02.md, after one repair round for F1).
- sbt check passes: 1158 module tests and 336 contract tests (repair-01.md). The two contract tests marked "expected to fail" in Phase 5 are still marked that way.
(All met.)

## Spec checklist
| Item | Status | Source |
|---|---|---|
| 1. Researcher, work, venue and authorship tables | Done: 200,484 researchers, 758,328 authorships, 0 missing links | agent-report-spark.md |
| 1. Topic and institution tables | Empty: no enrichment data yet, and the institution input is empty | agent-report-spark.md |
| 1. Unresolved mentions kept, not forced to merge | Done, 0 in this run (every mention got a researcher in Phase 5) | agent-report-spark.md |
| 1. Every authorship points to existing nodes | Done: 0 violations | agent-report-spark.md |
| 2. Pairs mapped to researchers, self-pairs dropped, order fixed, duplicates combined | Done; tested | agent-report-spark.md, review-report.md |
| 2. Joint paper count, first and last year, paper IDs | Done | agent-report-spark.md |
| 2. Configurable year windows | Done: 2015–2025, plus 2015–17, 2018–20, 2021–23, 2024–25 | prompt.md, conf/phase06.json |
| 3. P, C, T, A, M (with its paper IDs) and W, each kept separate | Done; no combined score | agent-report-spark.md, review-report.md ruling 3 |
| 4. Components, sizes, largest share, singletons | Done: largest component 94.17%, 1,063 singletons (full window) | agent-report-spark.md |
| 5. Shortest paths between chosen pairs | Done: 64 of 65 found within 6 steps | agent-report-spark.md |
| 5. Path length estimate | Done: about 6 steps (90th percentile), mean 4.72, sampled from 30 starting points | agent-report-spark.md |
| 5. No all-pairs paths on the driver | Done | review-report.md ruling 1 |
| 6. Communities, sizes, cross-community edges | Done: 29,962 communities (full window); label propagation | agent-report-spark.md |
| 7. Bridge measures with written limits | Done: three measures; no betweenness | agent-report-spark.md |
| 7. Number of communities each researcher connects | Done | agent-report-spark.md |
| 8. k-core, tested on small graphs | Done; full window stopped at the cap (decision 2) | agent-report-spark.md, review-report.md F4 |
| 9. Change across windows | Done: change table; the last window is shorter (2 years), and this is stated | agent-report-spark.md |
| 10. Researcher, community and path summaries | Done: 64,090 / 30,962 / 64 | agent-report-spark.md |
| 10. Paper or topic summaries | Not done: no abstracts and no topics yet | agent-report-spark.md, "PROPOSED LATER" |
| 11. Parquet tables and load manifests (Neptune, DynamoDB, S3 Vectors, S3) | Done: 65 tables; manifests list 12 / 42 / 7 / 65 tables | agent-report-spark.md |
| Explain partitions and shuffles; no full graph on the driver; checkpoints | Done | agent-report-spark.md |
| Counts and failed checks reported | Done: 0 rejected records, 0 failed integrity checks | agent-report-spark.md |
| Run can be repeated from a run manifest | Done: two runs gave identical rows | agent-report-spark.md, repair-01.md |
| sbt check | Done | repair-01.md |
| Local Spark run | Done: final run attempt-03 in 9 min 12 s | repair-01.md |
| Bounded EMR run | Deferred until after deployment (my decision 10) | prompt.md |
| Stage DAGs, partition counts, shuffle sizes | Stage list with times and shuffle sizes; no DAG pictures | agent-report-spark.md |

## Results (final run attempt-03, run:v1:51b50d578d5b609842c1c21b8266a00b)
| Window | Researchers | Coauthor links | Largest component | Singletons | Communities |
|---|---|---|---|---|---|
| 2015–2025 | 200,484 | 1,612,421 | 94.17% | 1,063 | 29,962 |
| 2015–2017 | 33,768 | 104,067 | 73.84% | 431 | 7,693 |
| 2018–2020 | 58,729 | 248,445 | 81.34% | 457 | 11,525 |
| 2021–2023 | 94,166 | 569,486 | 88.87% | 429 | 15,849 |
| 2024–2025 | 116,301 | 826,665 | 89.84% | 393 | 17,967 |

Full window: the median researcher has 1 paper and 7 coauthors; the average is 3.78 papers and 16.09 coauthors.

## What I reviewed
prompt.md, agent-report-build.md, agent-report-domain.md, agent-report-spark.md, review-report.md, repair-01.md and review-report-02.md.

## What happened
1. build-engineer made `sbt check` faster by running test groups side by side, with memory limits for each test JVM. It went from about 33 minutes to 11.5 minutes, with the same tests and the same counts. Three later runs took 12 to 16 minutes, and none failed, so the change was kept.
2. domain-engineer added the new shared records: 9 new metric names and ResearcherGroupMembership (who is in which component or community). The changes only add things; nothing existing changed.
3. spark-engineer built the pipeline and its tests, then ran it on the real data (attempt-01). It made a second run (attempt-02) to show the results repeat exactly.
4. code-reviewer asked for one fix (F1): the run wrote its "ready to load" files even when some rows had failed the checks. The real data had no bad rows, so no output was wrong.
5. Repair-01 fixed F1 and added a test that fails without the fix. The final run (attempt-03) gave the same rows as attempt-02. The only differences are the run ID and the IDs built from it.
6. The reviewer re-checked F1 and approved.
7. The agents wrote their reports to a scratch folder. I copied them into docs/agent-runs/phase-06/ myself, without changes.

## Decisions
1. Faster `sbt check` (step 0): kept. Four full runs passed (11:32, 15:39, 12:10, 15:12), and none showed the tests getting in each other's way.
2. F4, k-core in the full window did not finish settling at the 30-step cap: accepted for now. Only 2 values were still changing, and they can only go down, so they are upper limits. The cap goes up to 50 (a settings change only) the next time Spark runs, before the EMR run. I am not paying for an extra run now.
3. F5, the "self vote" added to label propagation: accepted as a known difference from my prompt. Without it, the method flips back and forth forever on a pair of researchers. It can be turned off with a setting (weight 0), and it is tested. Known limits:
   - a researcher with one coauthor may keep its own label because of ID order;
   - the method did not settle in 4 of 5 windows at the 10-step cap (82 to 240 labels still changing out of 30k–200k researchers).
4. Evidence IDs ignore the window: accepted. Evidence is kept per window, the key is (evidence ID, window), and nothing is bundled across windows. Changing the ID formula is a breaking change and goes on the later list.
5. CONFLICT clusters have the same confidence (0.95) as normal ones. They are marked by `identityOutcome=conflict` (1,982 researchers). No change now. It is entity-resolution-engineer's area, and it goes with the other "before a live LLM" items.
6. Institution and external-ID inputs are empty, and the topic table is empty: accepted. They fill in when the enrichment data is fetched.
7. Label propagation hit its cap in 4 of 5 windows: accepted (see decision 3). Community results are described as approximate.
8. Small findings (none repaired, as my prompt said):
   - F2 (a path summary shares its evidence ID with its path): accepted. They are in different tables. Fix later.
   - F3 (no test cases for the missing-endpoint and team/pair checks): moved to Phase 11.
   - F6 (the reuse check doesn't hash the report or load manifests, and the code version is typed by hand): accepted for now. It must be fixed before any cloud run, together with Phase 4 finding 2.
   - F7 (bridge limits written down): met in agent-report-spark.md. No action.
   - F8 (rejected rows carry no run ID; it comes from the folder name): accepted.
   - F9 (the EMR entry point can still broadcast small tables and reads only local files, not S3): **must be fixed before the EMR run.**
   - F10 (W equals T in this data): expected, because each link's weight counts shared papers. Stated in agent-report-spark.md. W and T must not be shown as two separate findings.
   - The reviewer's two advisory notes on the test tools: noted, no action.
9. domain-engineer's left-over read-only "waiter" processes: no action. They only read a log file, and they end with the session.
10. Agents used two read-only commands that were not on my list (`cmp` and `od`). Nothing was changed. Noted as a process slip, like in Phase 5.

## Manual changes
- My personal settings file (`.claude/settings.local.json`, never committed): I pre-approved a few more read-only commands (sort, uniq, cut, `sed -n`). The safety guard and the deny rules were not changed.
- Files I wrote: prompt.md.
- I copied the reports into the folder (see What happened).
- I did not edit any code or report the agents wrote.

## Deferred
- Before the EMR run: fix F9, raise the k-core cap to 50, and then do the bounded EMR Spark run.
- Before any cloud run: F6 and Phase 4 finding 2 (stale outputs can be reused), and the default blocking setting from Phase 5 (m5).
- Phase 11: the Scala 2.13 test project for Spark, with the adversarial cases (known graphs, empty and disconnected graphs) and F3.
- Once enrichment data exists: paper and topic summaries, topic nodes, ORCID/OpenAlex links on researchers.
- Later ideas (not promised): evidence IDs that include the window; approximate betweenness; matching communities across windows; a way for k-core to finish settling.
- Still carried from Phase 5: m1, M1 and M2 before any real LLM; M3, M4 and the "expected to fail" marks at Phase 16; the enrichment fetch; the finding 11 decision before Phase 9; the `.DS_Store` files.
- Clean-up: the attempt-01 and attempt-02 output folders (about 1.6 GB each, under target/, not committed) can be removed later. attempt-03 is the final run.

## Remaining risks
- Community results are approximate: the method did not fully settle, and the self vote adds an ID-order bias.
- 2 coreness values in the full window are upper limits, not exact.
- CONFLICT clusters look as confident as normal ones unless the conflict flag is checked.
- Spark has only been run on my laptop. The EMR path is untested and needs F9 first.
- Test results come from the agents' own output. The reviewer cannot run commands.

## Acceptance
I accept Phase 6 as of commit 2bbe7ca7ac810ad3c270c7055982f3c1a9792116.
