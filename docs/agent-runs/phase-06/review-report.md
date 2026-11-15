VERDICT: REQUEST_CHANGES

One major finding is open (F1). The fix is small and does not change any output values. Everything else is minor or advisory.

STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 6 review, read-only (docs/agent-runs/phase-06/prompt.md: decisions 1, 4-12 and the spec gate). Scope: the uncommitted working tree against HEAD 7070f61 for build.sbt, modules/core/** and modules/spark/**.
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools)
TEST RESULTS: Not run; I have no Bash. The marker /Users/brian/Developer/repos/scholarchain/.claude/.last-test-run reads timestamp=2026-11-15T08:20:56Z, module_tests=1157, contract_tests=336. The 1157 is 1065 plus the new core and spark tests. I did not see the sbt check output myself.
VALIDATION RESULTS:
- File lists checked against the git status snapshot and Glob. Modified: build.sbt; core Enums, Records, SchemaVersions and Validator; core test Fixtures, SchemaVersionsSuite and WireContractSuite. Untracked: core GraphAnalyticsContractSuite.scala, wire/v1/ResearcherGroupMembership.json, modules/spark/ (11 main files, 7 test files, log4j2.properties, conf/phase06.json), and docs/agent-runs/phase-06/ (prompt.md only).
- These match the agents' self-reported lists exactly. Nothing changed under project/**, tests/**, infra/** or .claude/** apart from .DS_Store files.
- Real-run evidence read from attempt-02/run=d6740be5d294d21a5d8291e24853b80f: _run_report.json and _run_manifest.json.
ASSUMPTIONS:
- I cannot diff build.sbt against HEAD. Phase 2 and 4 reports show the earlier `forkedTests(options)` with no heap argument. The current file differs only in the heap settings, the fork-group limit, forkGroups(2) on hadoop and contractTests, and the concurrent stage 3. I treat those as the whole step-0 diff.
- I could not observe the step-0 timing (25 minutes or less).
- I do not have spark-engineer's agent report, so I could not check documentation that exists only there (see F7).
RISKS: I did not see the step-0 timing or the sbt check output. The EMR path is untested (see F9).
MANUAL ACTION REQUIRED: The human rules on the two flagged deviations (decision 12.4 a and b, recommendations below) and on whether F4 and F5 are accepted as documented risks.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: spark-engineer repairs F1, adds a test that fails without the fix, and reruns sbt check. code-reviewer then re-checks F1 only. A final real run is needed only if the code-version string changes (F1 changes no output value).

## Findings

**F1. Major. Owner: spark-engineer.**
- Where:
  - Pipeline.scala:767-880 (verify-outputs, then the manifest writes)
  - Output.scala:126-135 (verify)
  - Output.scala:255-258 (loadManifests drops invalidRecords)
  - Pipeline.scala:876 (comment)
- Evidence:
  - `out.verifyAll()` counts rows that fail `Contract.validate` (`invalid`) and sample round-trip failures. Nothing acts on those counts: the run still writes the load manifests and the run manifest.
  - The comment at Pipeline.scala:876 says the run manifest's "presence marks a complete, verified run", and `verifyExisting` will later reuse such a run.
  - The load manifests remove `invalidRecords`, so a loader never learns that some rows failed validation.
- Failure input: any bug that emits a row failing core validation, for example a provenance field nulled or a confidence outside [0, 1]. The run completes, and every load manifest lists the table as loadable.
- Why it matters: this breaks the CLAUDE.md rule that "a record ... without provenance is rejected, not defaulted". The real run had invalid=0, so no output is wrong today. This is a missing guard.
- Required fix:
  - If any table has invalid > 0 or roundTripFailures > 0, write no load manifests and no _run_manifest.json (the run report may still be written) and fail with a clear exception.
  - Add a test that plants an invalid row, or uses a mirror that always fails, and asserts the run refuses and writes no manifest.

**F2. Minor. Owner: spark-engineer.**
- Where: Summaries.scala:255-264 and Rows.scala:378-386.
- Evidence: each path summary reuses the subjectIds of its path, and therefore its evidenceId. So two different EvidenceItems share the same (evidenceId, window): the path_evidence row, and the semantic_summary row, which has a snippet and different provenance.
- Why it matters: the orchestrator's unique key (evidenceId, window) holds only per table. The rows live in different tables and stores, which limits the impact, but a citation by evidenceId is ambiguous.
- Suggested fix (later): give summaries their own evidence identity, or document the key as (table, evidenceId, window) in the load manifests.

**F3. Minor. Owner: spark-engineer.**
- Where: PipelineFixtureSuite.scala:388-402, Pipeline.scala:463-484 and GraphBuild.scala:314-339.
- Evidence: "every edge endpoint exists" is only checked on a fixture that has no missing endpoint. If any of these checks always returned nothing, every test would still pass:
  - coauthor_edge_endpoint_missing and coauthor_edge_endpoint_inactive;
  - team_pair_without_candidate and pair_not_in_team;
  - team_size_mismatch and pair_year_mismatch.
- Suggested fix (Phase 11 or later): plant one case for each check.

**F4. Minor. Owner: spark-engineer. Human decision (12.4b).**
- Where: _run_report.json lines 266-302.
- Evidence:
  - The full-window k-core run stopped at the cap of 30 without converging; the last iterations changed 3, 3 and 2 values.
  - The h-index iteration only ever lowers values, so the unconverged values are upper bounds.
  - kcore/window_label=full-2015-2025 is still listed in the DynamoDB load manifest as coreness, with no convergence flag on the records or in the manifest.
- Assessment: this follows decision 7 (a cap plus a convergence counter).
- Recommendation: raise kcoreMaxIterations to 50 in conf/phase06.json (a config-only change; each iteration takes seconds), and put each window's algorithm convergence into the load manifests.

**F5. Minor. Owner: spark-engineer. Human decision (12.4a).**
- Where: Algorithms.scala:111-182, conf/phase06.json:20, and the convergence records in the run report.
- Evidence:
  - Even with the self vote, label propagation did not converge in 4 of the 5 windows; the full window still had 240 changes at iteration 10.
  - The self vote makes a pendant researcher's community depend on the hashed ID order: a pendant whose own ID is smaller keeps its label. This plausibly explains much of the 2,784 singleton communities against 1,063 singleton researchers.
- Recommendation: accept the self vote as a documented deviation.
  - It is configurable (0 restores plain synchronous label propagation).
  - It is tested (AlgorithmsSuite:89-102).
  - It is a standard fix for two-colour oscillation.
  - The ID-order bias for pendants and the non-convergence need to be stated as limitations.

**F6. Minor. Owner: spark-engineer.**
- Where: Output.scala:269-296.
- Evidence: the reuse check hashes only data files. `listData` skips `_`-prefixed paths, so _run_report.json and _load_manifests/*.json are neither listed nor verified; an edited load manifest would be reused.
- Evidence: the run ID also trusts the hand-supplied `--code-version` (here "phase06-7070f61-01-dirty"). Changed code run under the same string would reuse old outputs. This is the design risk from Phase 4 finding 2. It is mitigated by file-hash verification and a new attempt root per attempt, but not closed.
- Assessment: decision 10's literal requirement ("must match its manifest or the run refuses") is met and tested (PipelineFixtureSuite:512-524).
- Suggested fix: hash the load manifests and the report into the run manifest.

**F7. Minor. Owner: spark-engineer (report); later documentation-engineer.**
- Evidence: nothing in modules/spark or the run report mentions betweenness, the label-propagation resolution limit, or the limitations of the bridge measures; a search for these returned no matches.
- The participation coefficient is defined only in a scaladoc (Analytics.scala:137-140).
- Why it matters: the spec gate requires "every bridge measure is defined and its limitations are stated", and decision 7 requires the no-betweenness limitation to be stated.
- Required: confirm this is in the spark-engineer report. If it is not, add it there; this needs no code change.

**F8. Advisory. Owner: spark-engineer.**
- Where: Rows.scala:388-404.
- Evidence: RejectRow and IntegrityViolationRow carry no runId or provenance. Their run ID comes only from the run=… path and the manifest. unresolved_mention keeps the Hadoop input's provenance, as designed.

**F9. Advisory. Owner: spark-engineer.**
- Where: GraphAnalyticsJob.scala:56-62 and Pipeline.scala:37-39.
- Evidence:
  - The EMR entry point does not set spark.sql.autoBroadcastJoinThreshold=-1, so "no edge table is ever broadcast" holds only for LocalRealRun and the tests. On EMR the default 10 MB limit could still broadcast small tables, though never the full graph.
  - Pipeline uses java.io.File throughout (output root, listing, manifests, checkpoint directory), so it cannot run against S3 as written.
- Fix this before the deferred EMR run.

**F10. Advisory. Owner: spark-engineer (report).**
- Evidence: W_r equals T_r by construction when every coauthor is resolved and minEdgeWeight is 1. The real-run distributions are identical (mean 22.2653 for both).
- Why it matters: this should be stated, so the two are not presented as independent evidence. A config with minEdgeWeight > 1 would also turn C_r into something other than "distinct coauthors".

## Decision 12 rulings

1. **No full graph on the driver: PASS.** Every collect is bounded:
   - the top-10 IDs and the largest component (limit);
   - seeded IDs (limit 40 and 30);
   - idOf and compOf (isin over at most about 80 IDs);
   - path chain rows (at most 65 × 7) and the hop edges (joined on hop keys);
   - the BFS distance histogram (at most 12 rows) and the component-size histogram (distinct sizes);
   - the top 15 coreness values;
   - integrity counts per (check, window);
   - verification samples (limit 5 and 20).

   vertexIndex uses a distributed zipWithIndex, not a collect. No toLocalIterator, broadcast or collectAsList is used, and auto-broadcast is disabled in the local runner (see F9 for EMR).
2. **Test meaningfulness: PASS for the decided metrics.** I hand-checked the main fixture and it matches the expected values:
   - Full window: a P5 C3 T5 M2 W5; b P4 C3 T5 M2 W5; c P1 C2 T2 M2 W2; s P5 C4 T11 M3 W11; x P2 C1 T2 M1 W2.
   - w2015-2017: a P2 C2 T3 W3. w2018-2020: a P3 C2 T2 M1 (works W3 and W6).
   - 17 full-window edges; components of size 8, 5, 2, 1 and 1.
   - Coreness 3 for the 4-clique (p, q, r, s), 2 for the triangle (t, u, v), 1 for x, y and z, 0 for g, and 2 for a.
   - s participation = 1 − (81 + 4)/121 = 36/121; t = 0.32.
   - Shortest paths p-s-t-v-x and q-s-t-u.

   Each of these would fail on the matching error:
   - occurrence counting (W6's homonym slots would give weight 4, and T or M would go wrong on W3);
   - self pairs kept (the d edges and erSelfPairsDropped = 1);
   - non-canonical order or duplicates not aggregated;
   - window misapplied;
   - conflict confidence not carried (b's 0.5);
   - components, coreness (degree instead of coreness fails for s and t), or cross-degree counted by weight instead of edges;
   - participation computed from counts instead of weights (0.375 against 36/121).

   Gap: missing-endpoint and team/pair cross-check detection are not exercised (F3).
3. **Metrics match decision 6: PASS.**
   - P counts distinct works, single-author works included.
   - C is distinct coauthors (edge degree). T is the sum of distinct other researchers per work. A = T/P.
   - M comes with its work IDs as `metric` evidence, plus dblp teamSize per work.
   - W is the sum of edge weights. Component and community each have an ID and a size. The three bridge measures and coreness are present.
   - Every ResearcherMetrics row carries its window, this run's runId and identityConfidence (the minimum of the researcher's own and edge confidences).
4. **Algorithms: PASS with two flagged deviations.**
   - Caps are 30 for components, 10 for label propagation and 30 for k-core, all configurable.
   - Every iteration is checkpointed.
   - Ties are broken deterministically: smallest index, which equals the smallest ResearcherId because the index preserves order.
   - Convergence counters exist; components converged in 5-6 rounds.
   - Paths use one batched multi-source BFS over all 65 pairs with depth 6: not per pair, not all-pairs.
   - The diameter uses one batched BFS from 30 seeded sources with cap 12.
   - (a) Self vote: recommend ACCEPT as a documented deviation, stating the pendant ID-order bias and the non-convergence (F5).
   - (b) Unconverged k-core: recommend raising the cap to 50 in config and adding convergence to the load manifests; otherwise accept as documented upper bounds (F4).
5. **Determinism: PASS.**
   - Order-preserving index; seeded hash order for random choices; sorted collect_list and sort_array everywhere.
   - Integer-valued double sums; Locale.ROOT formatting; fixed repartition plus sort before each write.
   - Tested by identical tables across two runs (PipelineFixtureSuite:499-510), and the real attempt-01 and attempt-02 runs produced the same run ID (with CompareRuns available).
6. **Windows explicit: PASS.** Every windowed table has a `window` or `evidence.window` field and a window_label partition; this is tested for all windowed tables (PipelineFixtureSuite:162-180). The last window is stated to be shorter in the change table.
7. **Provenance and run IDs: PASS for every core record.**
   - Rejects and integrity rows rely on the path and manifest for their run ID (F8).
   - A reused output must match its manifest's file hashes, or the run refuses; an interrupted run directory is also refused. Both are tested.
   - The Phase 4 finding 2 design risk (a hand-supplied code version) remains, as noted in F6.
8. **Step 0: PASS as far as I can verify.**
   - The marker is written only inside the task that depends on both moduleTests and contractTestsNoAws, so any failure stops it from being written.
   - forkGroups deals every defined suite exactly once.
   - Heap: -Xmx2g for the hadoop and contractTests forks, -Xmx6g for sparkAnalytics; at most 3 forked JVMs at once.
   - Per-test Hadoop temporary directories already exist (TestSupport:33-35, JobHarness:42-44), and Spark tests use a fresh temporary root per session.
   - I could not diff against HEAD or observe the timing.
9. **Ownership: PASS.** The changes stay inside build.sbt, modules/core/** and modules/spark/**. Nothing changed outside the three owners' territories.

**Also checked:**
- All 8 input tables are decoded with core2_13 `Contract.decode`, and identity assignments must be mention_researcher with no candidateId.
- Rejects go to the versioned rejects/v1 output and are counted per table; this is tested.
- EvidenceId across windows is handled as the orchestrator decided:
  - core is unchanged apart from scaladoc;
  - evidence is partitioned per window, with (evidenceId, window) as the documented key;
  - the load-manifest note says never to bundle across windows;
  - Spark builds no cross-window bundles.

  The one same-window evidenceId collision is F2.

Relevant files:
- /Users/brian/Developer/repos/scholarchain/modules/spark/src/main/scala/org/scholarchain/spark/Pipeline.scala
- /Users/brian/Developer/repos/scholarchain/modules/spark/src/main/scala/org/scholarchain/spark/Output.scala
- /Users/brian/Developer/repos/scholarchain/modules/spark/src/main/scala/org/scholarchain/spark/Algorithms.scala
- /Users/brian/Developer/repos/scholarchain/modules/spark/src/main/scala/org/scholarchain/spark/Summaries.scala
- /Users/brian/Developer/repos/scholarchain/modules/spark/src/main/scala/org/scholarchain/spark/Rows.scala
- /Users/brian/Developer/repos/scholarchain/modules/spark/src/test/scala/org/scholarchain/spark/PipelineFixtureSuite.scala
- /Users/brian/Developer/repos/scholarchain/modules/spark/conf/phase06.json
- /Users/brian/Developer/repos/scholarchain/build.sbt
- /Users/brian/Developer/repos/scholarchain/modules/spark/target/phase-06-real/attempt-02/run=d6740be5d294d21a5d8291e24853b80f/_run_report.json
