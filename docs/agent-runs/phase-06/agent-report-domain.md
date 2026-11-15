The core additions from decision 4 are in, and `sbt check` passed: exit 0, 1129 module tests and 336 contract tests, 15 min 39 s wall time.

- **Test counts:** 1129 is the baseline 1065 plus 64 new tests. The new core tests are 22 in the new suite plus 10 generated for the new frozen wire example, and they run under both `core` and `core2_13` (32 × 2 = 64). The contract count is unchanged at 336.
- **Step 0 second confirmation:** no test failed, so there is no sign of parallel interference. The log has zero suites with a non-zero failed count.
- **Wall time:** this run took 15:39, against 11.5 min for build-engineer's first run. That is still within the 25-minute limit. It may be load: the `time` line shows 35% CPU, and my waiter processes were also running (see MANUAL ACTION REQUIRED).
- **One thing for you to decide:** `EvidenceItem` accepts both Spark shapes unchanged, but its ID ignores the window, so the same path in two windows gets the same `evidenceId`. Details are in RISKS and SPARK-RELEVANT NOTES. I did not change it.

STATUS: COMPLETE
OWNER: domain-engineer
WORK ORDER: docs/agent-runs/phase-06/prompt.md, human architect decision 4 (core contract additions before spark-engineer)

FILES CREATED:
- /Users/brian/Developer/repos/scholarchain/modules/core/src/test/scala/org/scholarchain/core/GraphAnalyticsContractSuite.scala
- /Users/brian/Developer/repos/scholarchain/modules/core/src/test/resources/wire/v1/ResearcherGroupMembership.json (frozen wire example)

FILES MODIFIED:
- /Users/brian/Developer/repos/scholarchain/modules/core/src/main/scala/org/scholarchain/core/Enums.scala (9 new CollaborationMetric values appended; new GroupKind enum)
- /Users/brian/Developer/repos/scholarchain/modules/core/src/main/scala/org/scholarchain/core/Records.scala (new ResearcherGroupMembership record; scaladoc on EvidenceItem only, schema unchanged)
- /Users/brian/Developer/repos/scholarchain/modules/core/src/main/scala/org/scholarchain/core/SchemaVersions.scala ("ResearcherGroupMembership" -> 1 plus a history line)
- /Users/brian/Developer/repos/scholarchain/modules/core/src/main/scala/org/scholarchain/core/Validator.scala (researcherGroupMembership validator)
- /Users/brian/Developer/repos/scholarchain/modules/core/src/test/scala/org/scholarchain/core/Fixtures.scala (researcherGroupMembership fixture)
- /Users/brian/Developer/repos/scholarchain/modules/core/src/test/scala/org/scholarchain/core/SchemaVersionsSuite.scala (frozen tables extended: the 9 new metric names after the 7 unchanged ones; GroupKind names; new schema row)
- /Users/brian/Developer/repos/scholarchain/modules/core/src/test/scala/org/scholarchain/core/WireContractSuite.scala (one Example line added)
- Nothing outside modules/core. build.sbt was already modified in the tree by build-engineer and I did not touch it.

COMMANDS RUN:
1. Read-only inspection: cat, sed -n, grep, find, wc, git status, git diff --stat.
2. `sbt -batch "core/testOnly GraphAnalyticsContractSuite SchemaVersionsSuite WireContractSuite" "core2_13/testOnly ..."`: Passed 286/286 under core and 286/286 under core2_13.
3. `sbt -batch check` (background; log at /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/2614013c-933e-4dcb-99ca-fc3105c6c7c1/scratchpad/sbt-check.log): exit=0, started 00:45:40, ended 01:01:19.
4. Read-only tail/grep waiters on that log (bur778gg2, bhv31z51w, bjh8mrjgj, bz4f9107t, b5v2ao5ma). They read only; some may still be running (see MANUAL ACTION REQUIRED).

TEST RESULTS:
- `sbt check`: `[info] Updated /Users/brian/Developer/repos/scholarchain/.claude/.last-test-run (module_tests=1129, contract_tests=336)`, then `[success] Total time: 936 s (0:15:36.0)`. Shell `time`: 15:39.32 total.
- Module tests: 1129 = 1065 + 64.
- Contract tests: 336, unchanged.
- No suite reported a non-zero failed count (grep count 0), so no failure looks like parallel interference.

VALIDATION RESULTS:
- Format check (scalafmt, including the core and core2_13 matrices) passed as part of `sbt check`.
- Compiles under Scala 3 and Scala 2.13. Only sealed traits with case objects and plain case classes are used, nothing Scala-3-only.
- No new dependencies.

ASSUMPTIONS:
- "Smallest member ResearcherId" means smallest by Unicode code point (`CodePointOrder`). Stable IDs are lowercase ASCII hex, so this is the same as `String.compareTo` in Spark.
- New metric values are appended at the end of `CollaborationMetric.values`, so earlier list positions and wire names stay unchanged.
- Beyond what decision 4 asked for, I added two consistency rules for components that follow from "groupId is the smallest member" (listed in SPARK-RELEVANT NOTES). Communities get no ID-ordering rule.
- The provenance source type of the new record is not restricted, matching CoauthorEdge and ResearcherMetrics. Spark should use `pipeline`.

RISKS:
1. EvidenceId collision across windows (existing behaviour, not changed). `EvidenceId.of(evidenceType, runId, subjectIds)` does not include `window` or `metric`. So a `graph_path` with the same researcher sequence in two windows of one run, or the same M_r work list in the full window and a sub-window, gets the same `evidenceId`. Each item still validates on its own. They collide only when used as a key: `EvidenceBundle` rejects duplicate item IDs, and a store keyed by evidenceId would overwrite one with the other. A test pins this (`EvidenceItem: the evidence ID ignores window and metric (documented limitation)`). Options for the orchestrator:
   - (a) Spark keeps such items in per-window outputs and never bundles or keys them across windows.
   - (b) A future versioned change adds the window to the EvidenceId formula. That is breaking: the `evidence:v1` formula and EvidenceItem/EvidenceBundle schema versions would bump.
   I made no silent change.
2. EvidenceItem `subjectIds` are checked only for non-blank strings, not for ID kind. Both requested shapes therefore validate today, including the mixed researcher and work IDs for M_r. Nothing was rejected, so nothing had to change. The other side is that core does not catch a wrong-kind ID in these shapes; Spark must produce them correctly.
3. `cross_community_degree` has no unit fixed in core (edge count or weight sum). Spark must document the unit it uses.
4. Wall time rose from 11.5 to 15.6 min. The run still passed, but this is a timing data point for the step-0 decision.

MANUAL ACTION REQUIRED:
- Possibly still running: read-only `tail -f | grep` waiter processes from tasks bur778gg2, bhv31z51w, bjh8mrjgj, bz4f9107t and b5v2ao5ma. They only read the scratchpad log. I have no TaskStop tool. The orchestrator can stop them, or they can be left to end with the session.
- A decision on RISK 1 before spark-engineer bundles path or metric evidence across windows.

BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer review of the core diff (contract tests and `sbt check` are green); then spark-engineer starts.

CONTRACT CHANGES AND SCHEMA VERSIONS:
- Additive only; no breaking change and no version bumps.
- CollaborationMetric: 9 new values. The 7 existing wire names are unchanged.
- GroupKind: new enum.
- ResearcherGroupMembership: new, schema version 1.
- EvidenceItem: still version 1, scaladoc only.
- All other schema versions are unchanged.

STABLE ID FORMULAS: unchanged. The new record has no ID of its own. It is keyed naturally by (researcherId, window, groupKind). `groupId` is a ResearcherId chosen by the rule documented on the record.

SPARK-RELEVANT NOTES (for spark-engineer; package org.scholarchain.core, use the core2_13 build):

1. New CollaborationMetric case objects and their wire names:
   - PublicationCount: "publication_count" (P_r)
   - TotalCoauthorOccurrences: "total_coauthor_occurrences" (T_r)
   - AverageCoauthorsPerPaper: "average_coauthors_per_paper" (A_r)
   - MaxCoauthorsOnOnePaper: "max_coauthors_on_one_paper" (M_r)
   - CommunitySize: "community_size"
   - ParticipationCoefficient: "participation_coefficient"
   - CrossCommunityDegree: "cross_community_degree"
   - CommunitiesConnected: "communities_connected"
   - Coreness: "coreness"

   Existing values to reuse: DistinctCoauthors "distinct_coauthors" (C_r), WeightedDegree "weighted_degree" (W_r), ComponentSize "component_size".

   Do not use CoauthoredWorks or BridgeScore for P or the participation coefficient. BetweennessCentrality and ClusteringCoefficient stay unused.

   Values travel as `MetricValue(metric, value: Double)` inside `ResearcherMetrics(schemaVersion = 1, researcherId, window, values, identityConfidence, provenance)`. Rules:
   - `values` is non-empty, with each metric at most once.
   - Every value is finite and >= 0 (so A_r must not be NaN; emit no A_r when P_r = 0).
   - `identityConfidence` is in [0, 1].

2. GroupKind: `GroupKind.Component` ("component") and `GroupKind.Community` ("community"). An unknown name fails decode with "unknown group kind '<x>'".

3. ResearcherGroupMembership (schema version 1). Fields in wire order:
   - schemaVersion: Int (=1)
   - researcherId: ResearcherId
   - window: YearWindow
   - groupKind: GroupKind
   - groupId: ResearcherId
   - groupSize: Int
   - provenance: Provenance

   Validation (`Contract.decode`/`encode` apply it):
   - schemaVersion must be 1.
   - researcherId and groupId are well-formed `researcher:v1:<32 hex>`. A work ID or malformed ID fails decode.
   - window has startYear <= endYear and years in 1..9999.
   - groupSize >= 1.
   - Component only: `groupId <= researcherId` by code point (the smallest member), and `groupSize == 1` implies `groupId == researcherId`.
   - Community: no ordering rule; groupId is the ResearcherId of the final label.
   - Provenance is required and validated. A null or missing provenance is rejected.

   Use one row per (researcher, window, kind). groupSize should equal that researcher's component_size or community_size metric in the same window. Use `SchemaVersions.currentOf("ResearcherGroupMembership")` for the version.

4. EvidenceItem shapes (schema unchanged: schemaVersion 1, evidenceId, evidenceType, subjectIds, window, metric, snippet, provenance):
   - Path: `evidenceType = EvidenceType.GraphPath`, `subjectIds` = researcher ID strings in path order (source first), `window = Some(pathWindow)` (required), `metric = None`, `snippet = None`, `evidenceId = EvidenceId.of(EvidenceType.GraphPath, provenance.runId, subjectIds)`. Path order changes the ID. Hop weight, years and supporting works come from the CoauthorEdge for that pair and window, which you materialize next to it in the path-evidence table; they are not EvidenceItem fields.
   - M_r works: `evidenceType = EvidenceType.Metric`, `subjectIds = researcherId.value :: workIds.map(_.value)`, `metric = Some(MetricValue(CollaborationMetric.MaxCoauthorsOnOnePaper, mr))` (required for metric evidence), `window = Some(w)` (required), `evidenceId = EvidenceId.of(EvidenceType.Metric, provenance.runId, subjectIds)`. Sort the work IDs, for example by code point, for determinism, because order changes the ID.
   - Both shapes validate today, and tests confirm they round-trip.
   - Caveat (RISK 1): the evidenceId does not include window or metric, so identical subjectIds in two windows of one run share an evidenceId. Keep these items per window and never put both into one EvidenceBundle, or wait for the orchestrator's decision.
   - "No path within depth 6" cannot be expressed as a graph_path item; report it in the run report instead.

PROPOSED LATER (not built):
- Include the window (and, for metric evidence, the metric) in the EvidenceId formula. This is a breaking, versioned change.
- An optional kind check on EvidenceItem subjectIds for graph_path and metric evidence.
