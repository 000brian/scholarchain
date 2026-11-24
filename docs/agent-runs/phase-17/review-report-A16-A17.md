## Review report: A16 and A17 fact check (Phase 17 session 3, E12 autograder follow-up)

VERDICT: n/a (fact check)

HEAD reviewed: d8b49fa4eede72e7a7742bb49115143e4593719a. I only read files. I did not open any forbidden path.

### A16: `.collect` and other driver-side Spark actions in modules/spark/src/main/**

**Summary.** There are exactly 18 Spark `.collect()` actions in modules/spark/src/main, which matches the autograder's count of 18. The code has no `collectAsList`, `collectAsMap`, `toLocalIterator`, `takeOrdered`, `top`, `first()`, or Dataset/RDD `.take(`.

- 17 are CONFIRMED-BOUNDED.
- 1 is NOT-CONFIRMED: Pipeline.scala:1116, `sizeBuckets`. Its result grows with input, but only sublinearly (at most √(2N) rows).

Config caps cited below are from modules/spark/src/main/scala/org/scholarchain/spark/JobConfig.scala:
- :122 `pathTopK` 0..100
- :123 `pathRandomPairs` 0..100
- :124 `diameterSources` 0..100
- :121 `pathMaxDepth` >= 1, with no upper cap
- :125 `diameterMaxDepth` >= 1, with no upper cap

The only checked-in job config is modules/spark/conf/phase06.json, which sets `pathMaxDepth` 6 (:22) and `diameterMaxDepth` 12 (:26).

Derived bounds:
- At most 4,950 + 100 = 5,050 path pairs (C(100,2) top-K pairs plus up to 100 random pairs; Paths.scala:103-109).
- At most 400 "wanted" IDs (Paths.scala:111).

All paths below are under modules/spark/src/main/scala/org/scholarchain/spark/.

| # | file:line | Collected value | Classification | Bounding evidence |
|---|---|---|---|---|
| 1 | JsonlExport.scala:537 | Up to 5 decode-failure strings from the JSONL lines just written | CONFIRMED-BOUNDED | `.limit(5)` at :537. Each string holds the error plus at most 200 chars of the line (`l.take(200)`, :534). |
| 2 | Output.scala:157 | Up to 5 core-contract validation error strings of one exported table | CONFIRMED-BOUNDED | `.limit(5)` at :157 |
| 3 | Output.scala:158 | Up to 20 records of one exported table (round-trip sample) | CONFIRMED-BOUNDED (row count) | `.limit(20)` at :158. Caveat: the width of one record depends on its array fields (for example `supportingWorkIds`). The row count is fixed. |
| 4 | Paths.scala:60 | Seeded "random" researcher IDs (`seeded`) | CONFIRMED-BOUNDED | `.limit(n)` at :59. n = 2*pathRandomPairs ≤ 200 or diameterSources ≤ 100 (Paths:100-101; JobConfig:123-124) |
| 5 | Paths.scala:85 | Top-K researcher IDs by distinct coauthors | CONFIRMED-BOUNDED | `.limit(cfg.pathTopK)` at :83, ≤ 100 (JobConfig:122) |
| 6 | Paths.scala:93 | Largest component (id, size) | CONFIRMED-BOUNDED | `.limit(1)` at :92 |
| 7 | Paths.scala:118 | (researcherId, vertex index) for the wanted IDs | CONFIRMED-BOUNDED | `isin(wanted)` at :116 with ≤ 400 IDs (:111). The index has one row per researcherId (Analytics.scala:61 `distinct`, :65 `zipWithIndex`). |
| 8 | Paths.scala:126 | (researcherId, componentId) for the wanted IDs | CONFIRMED-BOUNDED | `isin(wanted)` at :124, ≤ 400 keys. Facts are keyed by researcherId: Pipeline.scala:712-734 joins per-researcher aggregates (Analytics.scala:95, :127). |
| 9 | Paths.scala:211 | BFS parent chains (pairIndex, pos, node) | CONFIRMED-BOUNDED (by config) | Comment at :208. At most \|pairs\| × (pathMaxDepth+1) rows, because `visited` is unique per (source, node) (Algorithms.scala:276-278: left_anti then groupBy min parent) and the loop is capped at :193. That is ≤ 5,050 × 7 = 35,350 small rows with phase06.json. `pathMaxDepth` has no upper cap in JobConfig:121. |
| 10 | Paths.scala:220 | (vertex index, researcherId) for the chain nodes | CONFIRMED-BOUNDED (by config) | `isin(nodeIdx)` at :218. nodeIdx is the distinct nodes of #9 (:213). One index row per id. |
| 11 | Paths.scala:265 | CoauthorEdgeRow for each path hop | CONFIRMED-BOUNDED (row count, by config) | Join to `hopKeys` (:251-257, ≤ 5,050 × pathMaxDepth). Edges are unique per (left, right) (GraphBuild.scala:555 `groupBy("left","right")`). Caveat: a row's `supportingWorkIds` grows with the pair's joint publications. |
| 12 | Paths.scala:306 | Distance histogram (dist, count) for the effective diameter | CONFIRMED-BOUNDED (by config) | `groupBy("dist")` at :303. dist is in 1..diameterMaxDepth (Algorithms.scala:270 loop `depth < maxDepth`), so ≤ 12 rows with phase06.json:26. No upper cap in JobConfig:125. |
| 13 | Pipeline.scala:900 | (nodeId, displayName) for researchers on evidence paths | CONFIRMED-BOUNDED (by config) | `isin(pathIds)` at :898. pathIds are the distinct path subject IDs (:892, built from #9). researcherNodes has one row per researcher (GraphBuild.scala:384-386 maps researcherAgg, which is keyed by researcherId at :211-228). |
| 14 | Pipeline.scala:948 | Integrity-violation counts per (check, windowLabel) | CONFIRMED-BOUNDED (by config) | `groupBy("check","windowLabel")` at :945. `check` is always a literal: 11 checks in GraphBuild.scala:93/:341-352, plus Pipeline.scala:645 and :654. windowLabel is "" (GraphBuild:94) or a configured window label (Pipeline:646, :655). So rows ≤ 11 + 2 × \|cfg.windows\|. |
| 15 | Pipeline.scala:1116 | (size, count) for every distinct component or community size (`sizeBuckets`, called at :1158 and :1162) | NOT-CONFIRMED | `groupBy("size")` at :1114 is keyed on data values, not on a fixed set. The 9 buckets (:1099-1109) are applied on the driver after collect (:1118-1121). The distinct sizes sum to at most N researchers, so k(k+1)/2 ≤ N, which gives k < √(2N): about 2,650 rows of two longs at N = 3.5M. The result is small in practice but grows with the input. Bucketing on executors (for example `when`-based bucket expressions before groupBy) would cap it at 9 rows. |
| 16 | Pipeline.scala:1133 | Largest component (id, size) for the window report | CONFIRMED-BOUNDED | `.limit(1)` at :1133 |
| 17 | Pipeline.scala:1136 | Largest community (id, size) | CONFIRMED-BOUNDED | `.limit(1)` at :1136 |
| 18 | Pipeline.scala:1142 | Top 15 coreness values with counts | CONFIRMED-BOUNDED | `.limit(15)` at :1141 |

**Counts per class (Spark `.collect()`, main):**
- CONFIRMED-BOUNDED: 17
  - Fixed by a literal or hard-capped config: #1-8, #16-18 (11 rows)
  - Bounded by config values that have no upper validation cap (`pathMaxDepth`, `diameterMaxDepth`, window count): #9-14 (6 rows)
- NOT-CONFIRMED: 1 (#15)

**Other driver-side Spark actions (not `.collect`, listed separately):** each of these is a single row from a global `.agg(...)` with no groupBy, so each is bounded.

| file:line | Action | Value | Bound |
|---|---|---|---|
| Pipeline.scala:1082 | `.head()` | count/min/percentiles/max/avg of one column | global agg at :1074-1081, 1 row |
| Pipeline.scala:1152 | `.head()` | edge weight sum | global agg, 1 row |
| Pipeline.scala:1247 | `.head()` | per-window means | global agg at :1240-1246, 1 row |
| Pipeline.scala:1306 | `.head()` | transition counters | global agg at :1272-1305, 1 row |
| Paths.scala:193 | `Dataset.isEmpty` | boolean | scalar |

`.count()` calls are scalars and are not listed.

**Not a Spark action (Scala collection `.collect { case … }` or local `.take`):**

| file:line | Expression | Why it is local |
|---|---|---|
| GraphAnalyticsJob.scala:31 | `argv.grouped(2).collect { case Seq(k, v) … }` | partial function on CLI args |
| Paths.scala:107 | `pairSeeds.grouped(2).collect { case Seq(a, b) … }` | `pairSeeds` is a local `Seq[String]` (from #4) |
| Pipeline.scala:280 | `listing.collect { case ("entity-resolution", _, fs) => fs }` | local input file listing (`inputListing`, :275-276) |
| JsonlExport.scala:534 | `l.take(200)` | `String.take` inside a map lambda |
| Output.scala:348 | `failing.take(5)` | local `Seq[TableOutput]` |
| Output.scala:452 | `all.take(5)` | local `Seq` of problems |
| Pipeline.scala:229 | `problems.take(5)` | local `Seq[String]` (:217-225) |

**modules/spark/src/test (counts only, not classified):**
- Spark `.collect()`: 14
  - AlgorithmsSuite 2 (:41, :131)
  - JsonlExportSuite 1 (:50)
  - KnownGraphSuite 4 (:156, :358, :423, :438)
  - LocalJsonlExport 3 (:72, :90, :112)
  - PipelineFixtureSuite 4 (:80, :200, :350, :532)
- Scala `.collect { case … }`: 7
  - FixtureData :106, :305
  - KnownGraphInputs :54, :243
  - KnownGraphSuite :302
  - LocalJsonlExport :20
  - LocalRealRun :22
- Local `.take(`: 1 (CompareRuns :24, argv)
- `collectAsList` / `collectAsMap` / `.head()` / `.first()`: 0

### A17: scripts/emulator/env.sh is only sourced

**Findings:**
- **Nothing in the repository executes env.sh.**
  - No script `source`s or `.`s it either. Its only "sourcer" is the human's interactive terminal, as documented.
  - My grep for execution forms (`bash|sh|zsh|exec|./` followed by `…emulator/env.sh`) matched only `diff --git a/… b/scripts/emulator/env.sh` header lines in docs/agent-runs/phase-17/diff-F-1.txt:390 and diff-R-1.txt:1616. These are false positives.
  - Every invocation found anywhere is `source scripts/emulator/env.sh`.
- **No strict mode in env.sh, by design.**
  - The file has no `set -e`/`-u`/`-o pipefail` (grep of scripts/emulator for `set -` matches only setup.sh:10 and probes.sh:13).
  - Its header says "It changes no shell option" (env.sh:13).
  - This is correct for a file sourced into an interactive shell: `set -e` there would close the terminal on the first failing command.
  - It has no shebang. Line 1 is `# shellcheck shell=bash`.
- **Guard against execution:** env.sh:60-63 makes direct execution under bash exit 2 with "source this file … running it changes nothing". Under zsh execution the guard does not fire, but the file would only change a child shell and it writes no file (:12-13), so the parent shell is unaffected.
- **Scripts used after sourcing set strict mode themselves.** setup.sh and probes.sh run as separate bash processes and read only exported variables. They set `set -Eeuo pipefail` at setup.sh:10 and probes.sh:13, and both have a `#!/usr/bin/env bash` shebang at :1.
- **Executable bit: not verified by me.** I have no Bash tool. Earlier reports say env.sh has no executable bit (phase-16/agent-report-infra.md:37; phase-16/orchestrator-summary-s2.md:219). The human can confirm with `git ls-files -s scripts/emulator/env.sh` (mode 100644 means no bit).

**References** (I read every live reference; docs/agent-runs entries are historical records and are summarised in the last row):

| file:line | How env.sh is used or described |
|---|---|
| scripts/emulator/env.sh:4-6 | Header: "SOURCE it from the repository root … `source scripts/emulator/env.sh`" |
| scripts/emulator/env.sh:13 | "It changes no shell option" (no strict mode, by design) |
| scripts/emulator/env.sh:52-53 | Return codes: "2 executed instead of sourced (bash only); 4 refused" |
| scripts/emulator/env.sh:60-63 | Bash execution guard: `[ "${BASH_SOURCE[0]:-}" = "$0" ]` leads to an error and `exit 2` |
| scripts/emulator/env.sh:65 | Root check tests that the file exists (`-f`). Not an execution. |
| scripts/emulator/setup.sh:4-5, :38-39 | Comment and usage text: run in the terminal "after `source scripts/emulator/env.sh`". Does not source it. Strict mode at :10. |
| scripts/emulator/probes.sh:40-41 | Usage text: "in the terminal where you sourced scripts/emulator/env.sh". Does not source it. Strict mode at :13. |
| scripts/README.md:5, :241 | File lists and runtime status. Descriptive. |
| scripts/README.md:256 | Step 8: `source scripts/emulator/env.sh` |
| scripts/README.md:269 | Lists the variables env.sh sets and unsets. Descriptive. |
| README.md:315 | Script table row (`emulator/env.sh`, …). Descriptive. |
| README.md:325 | "`emulator/env.sh` now unsets … when sourced" |
| docs/runbook.md:225 | `source scripts/emulator/env.sh` |
| docs/runbook.md:231 | "`env.sh`, when sourced, also unsets …" |
| docs/emulator.md:44, :52 | "sources `scripts/emulator/env.sh`"; "`env.sh` is sourced" |
| docs/emulator.md:48, :187, :197 | Status and limitation text. Descriptive. |
| reports/final-report.md:612, :643, :685 | Status and findings text. Descriptive. |
| modules/services/src/test/scala/org/scholarchain/services/EmbeddingWorkerEmulatorSuite.scala:43 | Scaladoc: "after `source scripts/emulator/env.sh`" (outside the requested folders, reported for completeness) |
| modules/storage/src/test/scala/org/scholarchain/storage/emulator/EmulatorAdaptersEmulatorSuite.scala:28 | Same scaladoc form |
| experiments/**, tests/**, .claude/**, build.sbt, docker/** | 0 references |
| docs/agent-runs/** (historical) | Every invocation is `source …`. Examples: phase-16/prompt.md:250 ("a file I `source`"), :253, :327; phase-16/runbook-log.md:58 ("env.sh sourced"); phase-16/agent-report-service.md:55; phase-16/agent-report-infra.md:9, :37 ("sourced"); phase-17/agent-report-docs-W2.md:180. No execution form. |

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: docs/agent-runs/phase-17/prompt.md, session 3 step E12 autograder follow-up, A16 and A17 read-only fact check (HEAD d8b49fa)
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools: Read, Grep, Glob)
TEST RESULTS: none run (read-only)
VALIDATION RESULTS: A16: 18 Spark .collect() calls in modules/spark/src/main (matches the autograder's 18); 17 CONFIRMED-BOUNDED, 1 NOT-CONFIRMED (Pipeline.scala:1116 sizeBuckets, ≤ √(2N) rows, grows with input); 4 single-row .head() aggregates and 1 isEmpty bounded; 3 Scala .collect{case} and 4 local .take are not Spark actions. Test tree: 14 Spark .collect(), 7 Scala .collect{}, 1 local .take. A17: env.sh is only ever sourced; no script, doc, test or experiment executes it; it sets no strict mode by design (env.sh:13) and guards bash execution (env.sh:60-63, exit 2); no script sources it; setup.sh:10 and probes.sh:13 set `set -Eeuo pipefail` themselves.
ASSUMPTIONS: Per-researcher keying of window facts and researcherNodes is inferred from the groupBy("researcherId") aggregates cited. Window edges are the GraphBuild.coauthorEdges output (GraphBuild.scala:555). "Bounded by config" counts as bounded because the bound does not depend on input size.
RISKS: pathMaxDepth and diameterMaxDepth have no upper validation cap (JobConfig.scala:121, :125), so bounds #9-#13 hold only for sane configs. Rows #3 and #11 are bounded in count, not in per-row width. #15 is the one collect whose size depends on the data. The executable bit of env.sh was not verified by me (no Bash).
MANUAL ACTION REQUIRED: optional: the human may confirm env.sh's file mode with `git ls-files -s scripts/emulator/env.sh`. Rationales written by documentation-engineer should call Pipeline.scala:1116 sublinear and input-dependent, not fixed-size. Changing it is spark-engineer's code and outside a Markdown-only Phase 17.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: documentation-engineer writes the A16/A17 rationales from this report; orchestrator routes.
```
