# Phase 6 work order: temporal coauthorship graph and Spark analytics

## Standing rules (same as Phase 5)

- **Routing:** hub and spoke; agents never call each other. Findings go to the owning agent, one repair round per finding. If a finding is still open after its round, or a second round would be needed, stop and ask me. A BLOCKED-ON goes to the named owner, then the blocked agent reruns in a fresh context. For an unowned path, stop with BLOCKED-ON factory-engineer.
- **Tests:** every source-changing agent runs `sbt check` before reporting COMPLETE (targeted tests while working are fine). sbt runs one at a time, never in parallel.
- **Commands** (I may be asleep; an approval prompt stalls the run):
  - Use only sbt, read-only git, and read-only shell commands (ls, cat, head, tail, wc, grep, du, df, shasum, sort, uniq, cut, `sed -n`, and find without -delete or -exec). No python, java, curl, installs, docker, aws or terraform.
  - Keep these words off command lines: rm, delete, drop, truncate, destroy, apply, force, reset, printenv, secret, credential, curl.
  - Run long jobs with the Bash tool's background option. Read input data only by passing paths to sbt.
  - If something would need my approval, don't retry variants: skip it if it's optional, or finish with BLOCKED-ON.
- **No AWS, no network.**
- **Scope:** build only what this work order lists. Anything else that seems useful goes on a "proposed later" list in the report, not into code. Run reporting is plain counts and timings in a run-report JSON; no statistics engine, no report generator.
- **Repair limits (speed):**
  - Only blocking or major findings get a repair round. Minor and advisory findings are listed for me, never repaired automatically.
  - After a repair, the owner adds a test that fails without the fix and reruns `sbt check`.
  - code-reviewer's re-check covers only the repaired findings. New minor findings found there are recorded, not repaired.
- **Evidence:** write nothing under docs/agent-runs/ until all the work is done. Then:
  - print the summary first;
  - write each report verbatim to your scratchpad (not to docs/agent-runs/; last time the write there was blocked);
  - give me one `cp -n` command per file into docs/agent-runs/phase-06/, plus its sha256.

  Do not commit.

## Human architect decisions

1. **Step 0: faster `sbt check`** (build-engineer, with a guard). This laptop has 10 cores.
   - Baseline: the last full `sbt check` in Phase 5 took about 33 minutes, with 1065 module tests and 336 contract tests (two of them expected-fail). Don't spend a run measuring it again.
   - Change the build so independent test work runs in parallel. For example:
     - run the module-test stage and the contract-test stage together, instead of one after the other;
     - and/or split slow forked suites into parallel fork groups.
   - Keep the gate's meaning exactly: `.claude/.last-test-run` is written only after every stage has passed. Same tests, same counts.
   - Accept the change if one `sbt check` passes with exactly 1065 + 336 tests and takes 25 minutes or less.
   - The next agent's `sbt check` is the second confirmation. If any later `sbt check` fails in a way that looks like parallel interference (a test that passes alone fails in the full run), revert step 0 completely and carry on without it.
   - Otherwise revert it completely, report the timing, and carry on without it.
   - Watch for shared temporary directories (Hadoop local runner, Spark) that could make parallel tests interfere.
   - **Memory: this Mac has 16 GB of RAM**, and macOS, Claude Code and the sbt JVM already use several GB. A forked test JVM defaults to a quarter of RAM (4 GB) as its maximum heap, so parallel forks can push the machine into swap and make things slower.
     - Run at most 2–3 forked test JVMs at once.
     - Give each forked test JVM an explicit maximum heap (about 2 GB), except sparkAnalytics.
     - sparkAnalytics's forked JVM gets about 6 GB, because the Phase 6 real run also runs in it (decision 10).
   - The only other build change allowed in this step is these heap settings.

2. **Agents and order:**
   1. build-engineer: step 0.
   2. domain-engineer: the core additions in decision 4, done up front so spark-engineer isn't blocked later.
   3. spark-engineer builds the pipeline and its tests, then does the local real run (decision 10) in the same invocation.
   4. code-reviewer: APPROVE required.
   5. If code that affects outputs changed after approval, a final real run from the approved code.

   **No test-engineer pass in this phase.** The Phase 6 spec gate doesn't require one, and test-engineer's tree (tests/contracts, Scala 3) can't reach the Scala 2.13 Spark module without a new build project. The spec's adversarial Spark cases (known-graph degree, component, path, bridge and k-core cases, empty and disconnected graphs) belong to Phase 11, which will add that test project. In this phase, spark-engineer writes the hand-checkable tests and code-reviewer judges whether they would catch a wrong metric.

3. **Inputs** (read-only, local, from Phase 5 attempt-02):
   - Hadoop J09 export: the single `run=` folder under `modules/hadoop/target/phase-05-real/attempt-02/linked/dblp-2026-11-01/j09-graph-export/`. Use work, author_mention, publication_team, coauthor_pair_candidate, venue, institution and external_id_link. **Not** match_candidate.
   - Entity-resolution identity assignments: `modules/entity-resolution/target/phase-05-real/attempt-02/run=9f96c70b5115294dcc9d3b2d1d4438b9/decisions/identity_assignment/`. Each is a MatchDecision v2 `mention_researcher` with no candidateId: mention to ResearcherId, ACCEPTED or CONFLICT, with a confidence.
   - Spark reads these Hadoop and entity-resolution outputs and does not reparse raw dblp (spec, "Detailed Spark requirements").
   - Every input record is decoded and validated with the core contracts (core2_13 `Contract.decode`). Invalid records are counted and written to a versioned reject output, never dropped silently.

4. **Core contracts** (domain-engineer, before spark-engineer starts). Spark uses the existing records: CanonicalResearcher, AuthorshipRecord, CoauthorEdge, ResearcherMetrics, GraphNode, GraphEdge, YearWindow and EvidenceItem.
   - **Already present (no change):** every GraphNodeLabel and GraphEdgeLabel Spark needs, and the EvidenceType values `graph_path`, `metric` and `coauthor_edge`.
   - **Add these CollaborationMetric values** (additive; existing wire names unchanged):
     - `publication_count` (P);
     - `total_coauthor_occurrences` (T);
     - `average_coauthors_per_paper` (A);
     - `max_coauthors_on_one_paper` (M);
     - `community_size`;
     - `participation_coefficient`;
     - `cross_community_degree`;
     - `communities_connected`;
     - `coreness`.

     The existing `distinct_coauthors` is C, `weighted_degree` is W and `component_size` stays as it is. Do not reuse `coauthored_works` as P (P counts every work, single-author ones included) or `bridge_score` as the participation coefficient. `betweenness_centrality` and `clustering_coefficient` stay unused in this phase.
   - **Add one record, ResearcherGroupMembership** (schema version 1): researcherId, window, groupKind (a new enum: `component` or `community`), groupId, groupSize, provenance.
     - For a component, groupId is the component's smallest member ResearcherId.
     - For a community, groupId is the ResearcherId of its final label.
     - Both are deterministic for the same inputs.
   - **Reuse EvidenceItem (no new record):**
     - A materialized path is a `graph_path` EvidenceItem whose subjectIds are the researcher IDs in path order, with the path's window. Each hop's weight, years and supporting work IDs come from the CoauthorEdge record for that pair and window.
     - The work IDs behind M_r are a `metric` EvidenceItem: subjectIds are the researcher ID followed by the work IDs, and the metric is the M_r value, with its window.
   - Nothing else changes in core.

5. **Identity and edges.**
   - Each mention's researcher is its identity assignment. A CONFLICT-flagged cluster is kept as it is, and its lower identity confidence travels on every authorship, edge and metric that uses it.
   - Coauthor edges: map each consolidated coauthor pair (its positions give the mention IDs) to the two ResearcherIds, then drop self-pairs created by entity resolution.
   - Canonicalize pair order (`left < right`) and aggregate per window, so each logical pair has exactly one edge per window:
     - `weight` = jointPublicationCount (distinct works);
     - firstYear and lastYear;
     - supportingWorkIds (all of them, sorted);
     - minIdentityConfidence.
   - Every authorship and edge endpoint must exist as a node; violations are counted and reported.

6. **Metrics** (per researcher and window; keep each one visible, no combined score):
   - P_r: distinct works.
   - C_r: distinct coauthor researchers.
   - T_r: the sum over the researcher's works of the number of distinct other researchers on that work.
   - A_r = T_r / P_r.
   - M_r: the maximum, over the researcher's works, of the distinct other researchers on one work, plus the work IDs that reach it. dblp teamSize is also reported next to it.
   - W_r: the sum of edge weights.
   - Component ID and component size.
   - Community ID and community size.
   - Bridge measures: participation coefficient, cross-community degree, and the number of distinct communities directly connected.
   - Coreness.
   - Every metric record carries its window, its run ID and its identity confidence.

7. **Algorithms** (plain Spark DataFrame/Dataset code; no GraphFrames or GraphX):
   - GraphX is deprecated in Spark 4, and GraphFrames would add a dependency and an EMR packaging question. At about 200k researchers and 1M edges, simple iterative DataFrame algorithms are enough.
   - Each algorithm has a configurable iteration cap, checkpointing where it iterates, deterministic tie-breaking, and a counter showing whether it converged:
     - Connected components: large-star/small-star (preferred; it needs far fewer iterations than plain minimum-label propagation on long chains). Report the largest component's share of researchers and the number of singletons.
     - Communities: label propagation, using the edge weights, with ties broken by the smallest label and a fixed maximum number of iterations. Document its known limits (oscillation, resolution).
     - k-core: iterative degree peeling or the distributed h-index method. Coreness for every researcher.
     - Paths: one **batched multi-source** breadth-first search (every source tagged and advanced together, so the whole set costs at most 6 iterations), never one BFS per pair, never all-pairs, and never collecting the graph to the driver. Materialize bounded path evidence (maximum depth 6, configurable) for:
       - every pair among the top 10 researchers by C_r in the full window (45 pairs);
       - 20 seeded random pairs from the largest component.

       For every hop, record the researcher IDs, the edge weight, the supporting work IDs and the years.
     - Effective diameter: one batched BFS from 30 seeded sources in the largest component (full window, depth cap 12). Report the hop-distance distribution and its 90th percentile, and document it as an estimate.
     - Iteration caps (configurable): components 30, label propagation 10, k-core 30.
     - No betweenness centrality in this phase; state that limitation.

8. **Time windows** (from configuration):
   - the full window 2015–2025;
   - four windows: 2015–2017, 2018–2020, 2021–2023, 2024–2025.

   Compute every metric for every window. Add a compact change table across the four windows: distinct coauthors, team sizes, component membership, community membership and bridge measures. State that the last window is shorter.

9. **Semantic summaries** (for Phase 8 embeddings). Deterministic templates, filled only from computed data and source records. No invented facts, no abstracts (they are not collected), no topics (no enrichment in this run). Each summary has a contentHash.
   - Researcher profiles for researchers with at least 3 works in the full window.
   - Community summaries for communities with at least 5 members.
   - Path summaries for every materialized path.

   Report the counts.

10. **Outputs and the real run.**
    - Write partitioned Parquet tables in the spec's layout (nodes, edges, metrics by type and window, components, communities, bridges, kcore, path evidence, semantic summaries) under `modules/spark/target/phase-06-real/attempt-NN/`, with the run ID in the path. Every attempt uses a new root.
    - Each exported table maps to a core record and schema version. A test round-trips sample rows through core2_13 `Contract.decode`.
    - Write load manifests for Neptune, DynamoDB, S3 Vectors and ordinary S3, listing table paths, record counts, schema versions and run IDs.
    - Write a run manifest recording input run IDs and the code version. A reused output must match its manifest, or the run refuses (do not repeat Phase 4's finding 2).
    - Run locally through a test-scope runner (Spark is Provided), for example `sparkAnalytics/Test/runMain`, with `local[6]` (16 GB of RAM; the forked JVM has about 6 GB of heap from step 0), `spark.sql.shuffle.partitions` about 32 (the default 200 is slow locally), and the checkpoint directory under the attempt root. Stop and report if any stage runs longer than 90 minutes, if free disk drops below 15 GB, or if the run fails with out-of-memory. On out-of-memory, retry once with `local[4]` before reporting.
    - Expected input size (Phase 5): about 156k works, 758k mentions and identity assignments, about 200k researchers, and roughly 1.6M distinct coauthor pairs before resolution.
    - The bounded EMR Spark run is deferred until after deployment. Say so in the report.
    - Report:
      - partitioning, shuffle boundaries, caching and checkpoint choices;
      - stage timings;
      - graph counts and component counts;
      - community counts and size distribution;
      - metric distributions;
      - path-test results and the diameter estimate;
      - per-window counts;
      - integrity violations;
      - output paths.

11. **Tests** (spark-engineer, in modules/spark/src/test):
    - Small graphs that can be checked by hand for:
      - P, C, T, A, M (with its work IDs) and W;
      - components, community behavior, k-core and shortest paths;
      - participation coefficient and cross-community degree.
    - Self-pairs from entity resolution are dropped.
    - Pair order is canonical and duplicates are aggregated.
    - Windows are applied correctly.
    - A CONFLICT cluster's confidence is carried through.
    - Every edge endpoint exists.
    - Determinism across two runs.
    - Invalid input records are counted and rejected.
    - The real run's counts are reported, not asserted.
    - Prefer a few compact fixture graphs that each cover several metrics over many tiny suites.

12. **Review** (code-reviewer, read-only). Besides the usual order, rule on:
    - No full graph is collected to the driver (check every collect, toLocalIterator and broadcast of edges).
    - The tests are meaningful: each hand-checkable fixture would fail if its metric were computed wrongly (for example, counting coauthor occurrences instead of distinct coauthors, or not dropping self-pairs).
    - Each metric matches decision 6.
    - Algorithm convergence and iteration caps.
    - Determinism.
    - Windows are explicit in every output.
    - Provenance and run IDs are on every record.
    - Step 0 kept the gate's meaning.
    - Ownership: build, core and spark were each changed only by their owner.

## Spec work order (verbatim)

```text
Use the spark-engineer agent to implement the ScholarChain Spark collaboration-analysis pipeline.

Required jobs:

1. Canonical researcher and authorship builder
- Read accepted researcher and work entity links plus Hadoop author-paper outputs.
- Construct canonical Researcher, Work, AUTHORED, Topic, Institution, and Venue tables.
- Preserve unresolved author mentions without forcing a merge.
- Validate that every canonical authorship edge points to existing nodes.

2. Temporal weighted coauthorship graph builder
- Map Hadoop pair candidates onto canonical researcher IDs.
- Drop self-pairs created by identity resolution.
- Canonicalize pair order so each logical undirected pair has one identity.
- Aggregate duplicate pairs.
- Store jointPublicationCount, firstCollaborationYear, lastCollaborationYear, and supporting work IDs or a stable pointer to them.
- Support configurable year windows.

3. Researcher collaboration breadth and team-size statistics
For each researcher and time window compute:
- publicationCount P_r
- distinctCoauthorCount C_r
- totalCoauthorOccurrences T_r
- averageCoauthorsPerPaper A_r
- maximumCoauthorsOnOnePaper M_r
- weightedDegree W_r
- the work IDs responsible for M_r
Keep every component visible. Do not replace these measures with one opaque collaboration score.

4. Connected components
- Compute connected components of the researcher coauthorship graph.
- Store component ID and component size.
- Report the fraction of researchers in the largest component and the number of singleton researchers when applicable.

5. Researcher connectivity and path support
- Provide a scalable method for shortest paths between selected researcher pairs.
- Materialize or cache bounded path evidence for benchmark queries.
- Where feasible, estimate component-level average path length or effective diameter using sampling or another documented scalable method.
- Never compute all-pairs shortest paths by collecting the graph on the driver.

6. Collaboration communities
- Compute a documented community method appropriate to the selected Spark stack, such as label propagation or another justified algorithm.
- Store community membership, community size, and cross-community edges.

7. Bridge analysis
- Compute at least one documented bridge measure such as exact or approximate betweenness centrality, cross-community degree, or participation coefficient.
- Record algorithm parameters and approximation strategy when exact computation is impractical.
- Report the number of distinct communities directly connected by each selected researcher when derivable.

8. K-core or equivalent dense-subgraph measure
- Compute coreness or a justified alternative that captures whether a researcher is embedded in a dense collaboration structure.
- Test against small hand-checkable graphs.

9. Temporal collaboration analysis
- Recompute selected metrics over configured time windows or yearly snapshots.
- Measure changes in distinct coauthors, team sizes, component membership, community membership, and bridge behavior.
- Do not compare windows with incompatible inclusion rules without stating the limitation.

10. Semantic-context preparation
- Produce researcher-profile summaries, community summaries, collaboration-path summaries, and selected paper or topic summaries for later embedding.
- Summaries must be generated from computed data and source records, not invented metrics.

11. Export
- Write partitioned Parquet node, edge, metric, community, component, path-evidence, and semantic-summary tables.
- Produce load manifests for Neptune, DynamoDB, S3 Vectors, and ordinary S3.

Operational requirements:
- Explain partitioning and shuffle boundaries.
- Avoid collecting the full graph on the driver.
- Configure checkpointing where iterative algorithms require it.
- Emit counts and invariant failures.
- Make every run reproducible from a run manifest.

Run sbt check, a local or small-cluster Spark integration test, and a bounded EMR Spark run.
Report stage DAGs, partition counts, shuffle metrics, graph counts, component counts, community counts, metric distributions, path-test results, temporal windows, rejected integrity violations, and output paths.
```

## Spec gate (checked at my gate)

- Spark performs real distributed transformations and graph analytics.
- No complete graph is collected to the driver.
- Distinct coauthor counts and pair weights pass small known-graph tests.
- Connected components, community assignment behavior, path queries, and k-core or equivalent tests are demonstrated on hand-checkable fixtures.
- Every bridge measure is defined and its limitations are stated.
- Time windows are explicit in outputs.
- Partition and shuffle behavior are documented.
- Code review returns APPROVE.
