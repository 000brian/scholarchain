## Summary

STATUS: COMPLETE. The Spark collaboration-analysis pipeline is built in `modules/spark/**`. Its 28 known-graph tests pass, and the local real run finished in 7m59s. A second run of the final code gave the same run ID and identical rows in all 65 tables. `sbt check` passed in 12m10s with 1157 module tests and 336 contract tests, all passing.

Things you should know before the review:
1. **Label propagation adds a self vote.** Each vertex votes for its own current label with weight `labelPropagationSelfWeight` (configured as 1.0) times its largest edge weight. Without it, synchronous label propagation oscillates forever on any isolated pair with joint works (a test shows this). Ties still go to the smallest label and the cap is still 10. This goes beyond decision 7's wording, so the reviewer or you need to accept it. Setting the weight to 0 gives plain label propagation.
2. **Full-window k-core did not converge at the cap of 30.** The last iterations still changed 3, 3, 2 values. The h-index values only ever decrease, so the few unsettled values are upper bounds on coreness. I kept the cap you decided rather than raising it.
3. **CONFLICT confidence is carried, but it is not lower in the real data.** Entity resolution gave all 35,934 CONFLICT assignments a confidence of 0.95, the same as accepted ones. The `identityOutcome=conflict` property on researcher nodes is what flags them (1,982 researchers). Changing the confidence is entity-resolution-engineer's call.
4. **The bounded EMR Spark run is deferred until after deployment** (decision 10). Only the local run was done.

---

STATUS: COMPLETE
OWNER: spark-engineer
WORK ORDER: docs/agent-runs/phase-06/prompt.md (Standing rules; human decisions 1, 3, 5–11; verbatim spec work order, with the human decisions winning)

FILES CREATED:
- /Users/brian/Developer/repos/scholarchain/modules/spark/conf/phase06.json
- /Users/brian/Developer/repos/scholarchain/modules/spark/src/main/scala/org/scholarchain/spark/{JobConfig,Rows,Inputs,GraphBuild,Analytics,Algorithms,Paths,Summaries,Output,Pipeline,GraphAnalyticsJob}.scala
- /Users/brian/Developer/repos/scholarchain/modules/spark/src/test/scala/org/scholarchain/spark/{SparkTestSupport,FixtureData,PipelineFixtureSuite,AlgorithmsSuite,JobConfigSuite,LocalRealRun,CompareRuns}.scala
- /Users/brian/Developer/repos/scholarchain/modules/spark/src/test/resources/log4j2.properties

FILES MODIFIED: none outside modules/spark. No build, core or dependency changes; no GraphX, no GraphFrames.

COMMANDS RUN (sbt one at a time; read-only shell otherwise):
- `sbt sparkAnalytics/compile` and `sparkAnalytics/Test/compile`
- `sbt sparkAnalytics/test`, three times. The first two failures were real bugs, both fixed: a null in the change table for an empty window, and a hard-coded column name in the vertex index.
- Two mutation runs of `sbt "sparkAnalytics/testOnly ...PipelineFixtureSuite"`, each reverted:
  - keeping ER self-pairs: the fixture run fails;
  - counting C as occurrences instead of distinct coauthors: 3 tests fail.
- `sbt "sparkAnalytics/Test/runMain org.scholarchain.spark.LocalRealRun ..."`:
  - attempt-01;
  - attempt-02, after a whitespace-only scalafmt change, with the same code version;
  - attempt-01 again, to show verified reuse.
- `sbt "sparkAnalytics/Test/runMain org.scholarchain.spark.CompareRuns <attempt-01 run> <attempt-02 run>"`
- `sbt sparkAnalytics/scalafmtAll sparkAnalytics/scalafmtCheckAll`
- `sbt check`, once at the end, in the background.
- `df`, `ls`, `grep`, `sed -n`, `shasum`, `cmp`, `od`, `git status`, `git rev-parse`

TEST RESULTS:
- sparkAnalytics: 28 passed, 0 failed (PipelineFixtureSuite 19, AlgorithmsSuite 6, JobConfigSuite 3). The fixture suite takes about 2 minutes.
- `sbt check`: Module tests suites=51 executed=1157 passed=1157 failed=0. Contract tests suites=22 executed=336 passed=336 failed=0. Wall time 12m10s (728 s). `.claude/.last-test-run` was updated by the build. No parallel-interference failures.

VALIDATION RESULTS:
- Every exported table (65) was read back after writing, every row was validated through core `Contract.validate`, and 20 sample rows per table were round-tripped through `Contract.encode`/`Contract.decode`. Result: 0 invalid rows, 0 round-trip failures.
- Real-data determinism: attempt-01 and attempt-02 have the same run ID and identical rows in all 65 tables (exceptAll both ways is 0). The Parquet bytes differ only in footer metadata: the order of each column's encodings list, which Parquet keeps in a set whose order changes between JVMs.
- Reuse: rerunning into attempt-01 printed "reusing verified output" in 19 s. The fixture tests show that a tampered file or a run directory with no manifest is refused (`RunRefusedException`).

ASSUMPTIONS:
- `institution` and `external_id_link` do not exist in the J09 export. They decode to 0 rows and are recorded as present=false in the manifest. The topic node table is empty because there is no enrichment.
- `cross_community_degree` counts edges: the number of coauthors in another community, not their weight sum.
- `communities_connected` counts distinct other communities among the researcher's coauthors, excluding its own.
- Participation coefficient is weighted and is not emitted when W_r = 0. A_r is not emitted when P_r = 0.
- Metric identityConfidence = the minimum of the researcher's own authorship confidences and its edges' minIdentityConfidence in that window. So a coauthor's CONFLICT lowers the C_r/W_r record that counts it.
- AUTHORED and PUBLISHED_IN edges use the single-year window [year, year] of the work.
- Semantic summaries: researcher profiles for the full window only; community summaries for every window; path summaries for the full window.
- Fewer than 40 members in the largest component would give fewer random pairs. In the real run there were 20.

RISKS:
- **EvidenceId limitation (core):** `EvidenceId.of` ignores window and metric. Evidence is written per window, and load manifests state that the unique key is (evidenceId, window), never deduplicated or bundled across windows. A path summary has the same evidenceId as the path it describes, and the summary table's key is (summaryKind, evidenceId, window).
- Label propagation did not converge in 4 of 5 windows. At iteration 10 the label changes were still 240 / 5 / 124 / 82 (full / w2018-2020 / w2021-2023 / w2024-2025).
- Full-window coreness is not converged (see summary item 2).
- W_r equals T_r for every researcher in the real data. That is mathematically expected: the weights count distinct works, and the pair-versus-team cross-check found 0 differences. It is not a bug.

MANUAL ACTION REQUIRED: None for the run. The human should decide whether to accept the self-vote variant of label propagation and the unconverged full-window k-core at cap 30.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer (APPROVE required), then the human gate. Reviewer focus: decision 12 (collects and broadcasts, test meaningfulness, metric definitions), the self-vote item and the k-core cap.

---

## REAL RUN REPORT

**Run and output paths**
- Run ID: run:v1:d6740be5d294d21a5d8291e24853b80f; code version phase06-7070f61-01-dirty; config fingerprint sha256:212ae3c9…
- Inputs: Hadoop J09 run 32bda243… and entity-resolution run 9f96c70b…, both recorded in `_run_manifest.json`.
- attempt-01: /Users/brian/Developer/repos/scholarchain/modules/spark/target/phase-06-real/attempt-01/run=d6740be5d294d21a5d8291e24853b80f/ (1.6 GB)
- attempt-02: /Users/brian/Developer/repos/scholarchain/modules/spark/target/phase-06-real/attempt-02/run=d6740be5d294d21a5d8291e24853b80f/ (final code; same rows)
- In each run directory:
  - run report: `_run_report.json`
  - run manifest: `_run_manifest.json`
  - load manifests: `_load_manifests/{neptune,dynamodb,s3vectors,s3}.json`
- Checkpoints and staging are under `<attempt>/_checkpoint` and `<attempt>/_staging`.
- Tables: `nodes/node_label=*`, `canonical_researcher`, `authorship`, `unresolved_mention`, `edges/edge_label=*`, `coauthor_edge/window_label=*`, `metrics/metric_type={breadth,group_size}/window_label=*`, `bridges/`, `kcore/`, `components/`, `communities/`, `evidence/evidence_type=metric/`, `path_evidence/`, `semantic_summary/summary_kind=*/`, `rejects/v1`, `integrity_violations/v1`.

**Spark settings, partitioning, shuffles, checkpoints**
- Master local[6], shuffle partitions 32, AQE on, auto-broadcast set to -1 (no broadcast joins at all), 6 GB heap.
- Inputs are split by file (up to 165 decode tasks). Decoded rows are staged as Parquet once.
- Shuffle boundaries:
  - assignment de-duplication (window by mentionId);
  - the mention/work and mention/assignment joins;
  - the pair-to-researcher joins, then grouping by (left, right, workId);
  - grouping edges by (left, right) per window;
  - per-researcher aggregations;
  - every iteration of the iterative algorithms;
  - a hash repartition by table key, with a sort, before each write.
- No in-memory caching. Reliable checkpoints are taken after the build (authorship, pairs, researcher aggregates, vertex index), per window (edges, indexed edges, facts), and at every algorithm and BFS iteration.
- Output files: 4 partitions per table; 1 for small tables.
- The driver only ever collects bounded things: the top-10 list, 40 path seeds, 30 diameter seeds, path chains (at most 65 × 7 rows), and small histograms.

**Stage timings (s), with shuffle read / write (GB)**

| Stage | Time | Shuffle read | Shuffle write |
|---|---|---|---|
| decode-inputs | 16.8 | ~0 | ~0 |
| canonical-graph | 29.2 | 2.88 | 2.63 |
| vertex-index | 7.7 | | |
| window full-2015-2025 | 94.4 | 6.06 | 5.48 |
| window w2015-2017 | 24.5 | | |
| window w2018-2020 | 30.6 | | |
| window w2021-2023 | 48.9 | | |
| window w2024-2025 | 59.5 | | |
| paths-diameter | 66.1 | 3.62 | |
| semantic-summaries | 9.1 | | |
| integrity | 41.8 | 5.42 | |
| temporal-change | 0.9 | | |
| verify-outputs | 31.1 | | |

- Total 473 s. No stage came near 90 minutes.
- Free disk never dropped below about 340 GB (floor 15 GB). No out-of-memory errors.

**Inputs and rejects**
- Input lines: work 155,978; author_mention 758,328; publication_team 155,978; coauthor_pair_candidate 2,231,914 (2,232,101 position pairs); venue 16; identity_assignment 758,328; institution 0; external_id_link 0.
- Rejects: 0 in every table.

**Graph build**
- Researchers: 200,484, of which 1,982 are CONFLICT clusters.
- Authorships: 758,328 (35,934 CONFLICT); unresolved mentions 0.
- AUTHORED edges: 758,314. Fourteen researcher-work pairs had two mentions of the same researcher and were merged into one edge each.
- ER self-pairs dropped: 0 in the real data (the fixture proves the drop works).
- Distinct (pair, work) rows: 2,231,914.
- Integrity violations: 0 for every check, including the pair-candidate versus shared-team cross-check, team size, anchor ID, missing or inactive edge endpoints, venues, and duplicate assignments.

**Per-window counts**

| Window (years) | Researchers | Edges | Components | Largest component (share) | Singletons | Communities | Largest community |
|---|---|---|---|---|---|---|---|
| full-2015-2025 (11) | 200,484 | 1,612,421 | 3,885 | 188,797 (94.17%) | 1,063 | 29,962 | 1,329 |
| w2015-2017 (3) | 33,768 | 104,067 | 2,415 | 24,935 (73.84%) | 431 | 7,693 | 140 |
| w2018-2020 (3) | 58,729 | 248,445 | 2,940 | 47,770 (81.34%) | 457 | 11,525 | 210 |
| w2021-2023 (3) | 94,166 | 569,486 | 2,850 | 83,688 (88.87%) | 429 | 15,849 | 713 |
| w2024-2025 (2, shorter) | 116,301 | 826,665 | 3,011 | 104,490 (89.84%) | 393 | 17,967 | 1,025 |

**Size distributions (full window)**
- Community sizes: 1: 2,784; 2: 7,249; 3–4: 8,806; 5–9: 6,724; 10–49: 3,992; 50–99: 315; 100–999: 91; 1000+: 1.
- Component sizes: 1: 1,063; 2: 901; 3–4: 1,242; 5–9: 601; 10–49: 77; 10000+: 1.

**Metric distributions, full window (min / P50 / P90 / P99 / max / mean; exact percentiles)**

| Metric | min | P50 | P90 | P99 | max | mean |
|---|---|---|---|---|---|---|
| P | 1 | 1 | 8 | 38 | 363 | 3.78 |
| C | 0 | 7 | 34 | 148 | 3290 | 16.09 |
| T | 0 | 8 | 48 | 230 | 5883 | 22.27 |
| A | 0 | 4.76 | 9.5 | 45.75 | 136 | 6.29 |
| M | 0 | 6 | 13 | 89 | 136 | 8.62 |
| W | equal to T | | | | | |
| coreness | 0 | 6 | 19 | 89 | 136 | 9.82 |
| participation (n = 199,421) | 0 | 0.5 | 0.819 | 0.924 | 0.989 | 0.470 |
| cross-community degree | 0 | 3 | 20 | 99 | 2392 | 8.57 |
| communities connected | 0 | 2 | 11 | 45 | 298 | 4.84 |
| identity confidence | 0.900 | | | | 0.95 | |

The other windows are in the run report.

**Path tests (full window, depth cap 6)**
- 65 queries: 45 pairs among the top-10 researchers by C_r, and 20 seeded random pairs from the largest component.
- 64 found. Hop counts: 1: 39, 2: 6, 3: 1, 4: 7, 5: 10, 6: 1.
- 1 seeded pair is in the same component but not reachable within 6 hops (64ffd8b4… to 2a54cfa7…). 0 pairs fell in different components.

**Effective diameter (estimate)**
- One batched BFS from 30 seeded sources in the largest component, depth cap 12. 5,663,879 (source, node) pairs reached; 1 unreached within the cap.
- Hop distribution: 1: 622, 2: 23,642, 3: 442,956, 4: 2,027,998, 5: 2,092,911, 6: 824,177, 7: 209,658, 8: 34,941, 9: 5,761, 10: 1,008, 11: 187, 12: 18.
- **Estimated effective diameter (P90): 6 hops.** Mean distance 4.72.

**Change table (consecutive windows; the last window has 2 years, so it is not directly comparable)**

| Window | Mean C | Mean A | Mean M | Largest-component share |
|---|---|---|---|---|
| w2015-2017 | 6.16 | 3.78 | 4.35 | 0.738 |
| w2018-2020 | 8.46 | 4.75 | 5.68 | 0.813 |
| w2021-2023 | 12.10 | 6.07 | 7.71 | 0.889 |
| w2024-2025 | 14.22 | 7.10 | 9.00 | 0.898 |

| Transition | Active in both | C up / down / same | Joined / left largest component | Participation gained / lost |
|---|---|---|---|---|
| 2015-17 → 2018-20 | 16,273 | 9,990 / 4,705 / 1,578 | 1,887 / 1,042 | 3,575 / 1,830 |
| 2018-20 → 2021-23 | 30,750 | 19,735 / 8,589 / 2,426 | 2,490 / 1,207 | 5,822 / 2,627 |
| 2021-23 → 2024-25 | 47,094 | 26,668 / 17,189 / 3,237 | 1,934 / 1,826 | 6,122 / 4,406 |

All change counts, including team-size and cross-degree changes, are in the run report. Community labels are not aligned across windows, so the count of unchanged community labels is only indicative.

**Semantic summaries:** researcher profiles 64,090; community summaries 30,962 (full 11,123; 2,304 / 3,953 / 5,986 / 7,596 for the four windows); path summaries 64.

**Load manifests (table entries):** neptune 12, dynamodb 42, s3vectors 7, s3 65. Each entry lists the path, core record, schema version, record count, run ID and unique key.

**EMR:** the bounded EMR Spark run is deferred until after deployment (human decision 10).

## ALGORITHM NOTES

- **Connected components:** large-star/small-star over an (hi, lo) edge set, cap 30. Converged when a full round leaves the edge set unchanged. The component ID is the smallest member ResearcherId. Real run: converged in 5–6 iterations in every window (full window changes per iteration: 2,042,637 / 692,997 / 242,206 / 1,128 / 0).
- **Communities:** weighted synchronous label propagation, cap 10, ties to the smallest label, plus a self vote (selfWeight × the vertex's largest edge weight, configured as 1.0).
  - Known limits: oscillation without the self vote (tested); with the self vote, a pendant joins its neighbour only when the neighbour's label is smaller.
  - Smallest-label tie-breaking biases large flat regions toward low IDs, and the resolution limit favours large communities.
  - A synchronous label set need not be connected, and labels are not aligned across windows.
  - Converged only in w2015-2017 (at iteration 10). The other windows hit the cap with 82–240 labels still changing.
- **k-core:** distributed h-index iteration (Lü et al. 2016), starting from unweighted degree, cap 30. Values only decrease toward coreness. Converged in w2015-2017 (19 iterations), w2018-2020 (20), w2021-2023 (30) and w2024-2025 (25). Not converged in the full window (2 values still changing at iteration 30; the affected values are upper bounds).
- **Paths:** one batched multi-source BFS (all sources advance together), depth cap 6. A newly reached node's parent is its smallest-index neighbour on the previous level, so paths are deterministic. Hop facts are copied from the CoauthorEdge of that pair and window. No all-pairs computation, no per-pair BFS. "converged=false" for a BFS means it reached the depth cap with nodes still unvisited.
- **Diameter:** one batched BFS from 30 seeded sources, cap 12. P90 of reached hop distances, explicitly an estimate (sampled sources; pairs beyond the cap are excluded).
- **Seeded choices:** ordered by sha256(seed | salt | researcherId).
- **Bridge measures (no betweenness centrality in this phase; it is not computed or approximated):**
  - Participation coefficient = 1 − Σ_s (k_s / k)², using edge weights. Limit: it depends on the label-propagation communities and their non-convergence.
  - Cross-community degree = the number of coauthors in another community (edge count). Limit: it ignores tie strength.
  - Communities connected = distinct other communities among the coauthors. Limit: it inherits the community resolution limits.
- **Metric definitions:**
  - P = distinct works.
  - C = distinct coauthor researchers (edge degree).
  - T = Σ over works of the distinct other researchers on the work.
  - A = T / P.
  - M = max over works of the distinct other researchers, with the works that reach it as `metric` evidence carrying both distinct-researcher counts and the dblp teamSize.
  - W = Σ edge weights, where weight = joint distinct works.

## PROPOSED LATER

- Paper/topic summaries (in the spec, not in decision 9) and topic nodes once enrichment exists.
- Attaching ExternalIdLink ORCID/OpenAlex IDs to CanonicalResearcher (the input is empty this run).
- A higher configurable k-core cap, or a convergence tail on a reduced active set, so full-window coreness converges.
- Community alignment across windows (Jaccard matching) for the change table.
- Approximate betweenness from sampled sources, using the batched BFS.
- A core change so `EvidenceId` includes the window, through domain-engineer.
- Reusing the checkpointed researcher aggregates for the vertex index and the integrity stage (performance only).
- Pinning Parquet footer metadata order if byte-identical files are ever required.
- A Phase 11 Scala 2.13 adversarial test project for Spark.
