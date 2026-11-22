# agent-report-spark (Phase 16, session 2, step 5 first half)

STATUS: COMPLETE
OWNER: spark-engineer
WORK ORDER: docs/agent-runs/phase-16/prompt.md (sha256 c75cc5f895faed13880a69ec285231c5498fd50924c7b875fe7e02cce1707dc8), HEAD 9da45ab616a764e449f74f25feca85ed77d6dfe4 plus uncommitted session 2 work. Decision 11, spark part (tier 1 suite table and invariant-count mapping; tier 2 C-05); decision 2 status lines B-08, B-10.
FILES CREATED: none
FILES MODIFIED:
- /Users/brian/Developer/repos/scholarchain/modules/spark/src/main/scala/org/scholarchain/spark/JsonlExport.scala (C-05: `main` body moved to `mainWith`; catch-and-mask moved to `masked`)
- /Users/brian/Developer/repos/scholarchain/modules/spark/src/test/scala/org/scholarchain/spark/JsonlExportArgsSuite.scala (C-05 test added)
COMMANDS RUN:
- read-only: `grep -n` (literal words), `ls`, `wc -l`, `shasum -a 256` on both spark-outputs.csv files, `git status --short modules/spark`, `git diff --stat modules/spark`, `ls -la` on the changed files and the test reports. Files were read with the Read tool.
- `sbt -batch "sparkAnalytics/testOnly org.scholarchain.spark.JsonlExportArgsSuite"`, run with a temporary mutation (masking removed). This is the fails-without-the-change demonstration.
- `sbt -batch "sparkAnalytics/testOnly org.scholarchain.spark.JsonlExportArgsSuite org.scholarchain.spark.JsonlExportSuite"`, run after the mutation was undone. This is the final run.
TEST RESULTS:
- Mutation run (`masked` changed to `case NonFatal(e) => throw e`): `Failed: Total 7, Failed 1, Errors 0, Passed 6`. The failing test was `C-05: the entry point rethrows any failure masked, with no cause`, with `intercept failed, exception 'java.io.IOException' is not a subtype of 'org.scholarchain.spark.JsonlExportFailedException'`. The raw message with the example bucket and the 12-digit example account was printed in the cause chain, which shows the leak the test guards against.
- Final run: JsonlExportArgsSuite 7 tests, 0 failed. JsonlExportSuite 14 tests, 0 failed (124.6 s). `Passed: Total 21, Failed 0, Errors 0, Passed 21`, `[success] Total time: 129 s`.
VALIDATION RESULTS:
- I checked that the mutation was undone: a `grep -n "C05-MUTATION"` on JsonlExport.scala returned nothing before the final run.
- Fresh test reports: `modules/spark/target/test-reports/TEST-org.scholarchain.spark.JsonlExportSuite.xml` (20:54) is newer than the last source change (JsonlExport.scala, 20:52).
- The two `spark-outputs.csv` files are byte-identical. Both have sha256 ad70f64017cd4a071d1ab1a8dd8b7b121149e342059e8e1a856da3a695dedc6d, 66 lines, and run_id `run:v1:0f02b5f22d1187932df0fc1decd7e6a5`.
- I spot-checked the hand-written expected values against the inputs myself: KnownGraphSuite S6.A (P=2 C=3 T=4 A=2 M=3 W=4 core=3 size=4), the S5 A-E path (4 hops), and the S6.C participation coefficient (1-(2^2+1^2)/3^2 = 0.444…, cross-community degree 2, communities connected 1); PipelineFixtureSuite s (P=5 C=4 T=11 M=3 W=11) and the edge count of 17 (5 in fixture A, 12 in fixture B). All of them match.
ASSUMPTIONS:
- "Independent" means the expected value is a literal in the test, or is derived inside the test from the input specification by a rule that does not call the Spark code under test. Researcher, work, edge and evidence IDs come from `core` functions (`ResearcherId.forCluster`, `WorkId.fromDblpKey`, `EdgeId.coauthor`, `EvidenceId.of`), which are not the Spark code under test. That the KnownGraphSuite sheet values were worked out by hand is the suite's own claim (KnownGraphSuite.scala:12-13, "Expected values are the sheet's hand-derived values", Phase 11 test-inventory.md Part 3). I verified two things: no expected value in a row marked `independent` is read from the pipeline output, and a sample of the values is correct (above).
- I did not run KnownGraphSuite or PipelineFixtureSuite. Tier 1 needed no code change, and neither suite uses JsonlExport. E5 runs them at session 3.
RISKS:
- C-05 changes behavior slightly. The masking block now also covers starting the Spark session and `spark.stop()`, so a session-start failure (for example one that names an S3 event-log location) is now masked too. Before, it escaped raw. An argument error is still an `IllegalArgumentException` raised before any session starts, and it echoes no value.
- The test calls `mainWith`, which is all of `main` except the one-line `SparkSession.builder()...getOrCreate()` factory. A regression that bypasses `mainWith` inside `main` itself would not be caught. Running `main` in the shared test JVM would stop the other suites' session, so the test does not do that.
- Phase 15 A and B: the two spark-outputs.csv files describe the same graph run. That is one observation, not two independent runs.
MANUAL ACTION REQUIRED: none. E5 (session 3) reruns the Spark suites. modules/spark changed, so the E5 repeat rule applies if this lands after E5.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer (session 2 review). The wave closer (step 7, service-engineer) runs the full `sbt check`. experiment-engineer X2 uses the table below for `evaluate.py graph`.

## Tier 1: suite table (group 2)

Vocabularies are stable, and no cell contains a comma or a pipe.
- `group2_checks`: semicolon-separated values from {degree, weighted_degree, components, paths, communities, bridges, kcore, integrity, duplicates, orphans, none}. `degree` covers the breadth family C_r (distinct coauthors) and, where asserted, P_r T_r A_r M_r. `weighted_degree` is W_r or the edge weight. `none` means the test asserts provenance or run control, not a group 2 graph check (see `note`).
- `expectation_source`: one of
  - `independent`: values worked out by hand.
  - `property`: a relation on the code's own output, with no expected number.
  - `mixed`: some expected values come from the code's own output; the evidence column cites them.

| suite | test_ref | test_title | group2_checks | expectation_source | evidence | note |
|---|---|---|---|---|---|---|
| KnownGraphSuite | KnownGraphSuite.scala:275 | case sheet P C T A M W coreness component size | degree;weighted_degree;kcore;components | independent | literal sheet KnownGraphSuite.scala:224-261 (S2 S3 S4 star S5 path S6 clique S7 bridge S7w; windows) | asserted at :205-220 |
| KnownGraphSuite | KnownGraphSuite.scala:301 | case sheet component IDs are smallest member ID | components | independent | expected = min of core-derived member IDs KnownGraphSuite.scala:307 and :188 | rule applied by the test |
| KnownGraphSuite | KnownGraphSuite.scala:316 | S1b works outside every window give nodes but no rows or edges | integrity | independent | literal absence checks KnownGraphSuite.scala:318-324 | window filtering |
| KnownGraphSuite | KnownGraphSuite.scala:350 | case sheet shortest-path hops | paths | independent | literal hop sheet KnownGraphSuite.scala:329-348 | calls Paths.shortestPaths directly on the pipeline's coauthor_edge; not the materialized path_evidence |
| KnownGraphSuite | KnownGraphSuite.scala:413 | case sheet participation and cross-community degree | bridges | independent | literal sheet KnownGraphSuite.scala:391-411 with a hand-stated community map fed to Analytics.bridges :430-437 | isolated S2.A read from the pipeline bridges table :452-454 |
| KnownGraphSuite | KnownGraphSuite.scala:466 | I1 ER self pair dropped and dblp team size kept | duplicates;degree;weighted_degree;kcore;components | independent | literals KnownGraphSuite.scala:467-486 | also authored-edge provenance mention IDs :482-486 |
| KnownGraphSuite | KnownGraphSuite.scala:489 | I2 same pair on several works counts distinct works and a duplicate pair row adds none | duplicates;weighted_degree;degree;kcore;components | independent | literals KnownGraphSuite.scala:490-500 | duplicate plant at :122 |
| KnownGraphSuite | KnownGraphSuite.scala:503 | T1 T2 T3 T30 n(n-1)/2 edges and C=T=M=n-1 | degree;weighted_degree;kcore;components | independent | literals KnownGraphSuite.scala:508-531 (435 edges at :519) | team-size clique cases |
| KnownGraphSuite | KnownGraphSuite.scala:534 | I3 unassigned mention reported and never merged | integrity;orphans;degree | independent | literals KnownGraphSuite.scala:536-550 | no researcher and no dangling edge |
| KnownGraphSuite | KnownGraphSuite.scala:553 | I4 CONFLICT endpoint kept and confidence carried | integrity;degree;weighted_degree | independent | literals KnownGraphSuite.scala:554-573 | identity uncertainty is reported |
| KnownGraphSuite | KnownGraphSuite.scala:578 | F3a-F3e each planted integrity violation reported once | integrity;orphans | independent | literal expected set KnownGraphSuite.scala:588-600 and details :602-614 | plants at :118-122; endpoint_missing and assignment_mention_missing are orphan checks |
| KnownGraphSuite | KnownGraphSuite.scala:617 | F3a edge to endpoint without authorship counted in C and W not P and T | orphans;degree;weighted_degree | independent | literals KnownGraphSuite.scala:619-628 | |
| KnownGraphSuite | KnownGraphSuite.scala:631 | F3c wrong pair year keeps work year and F3d missing pair row gives no edge | integrity;weighted_degree;degree;kcore;components | independent | literals KnownGraphSuite.scala:632-637 | |
| KnownGraphSuite | KnownGraphSuite.scala:642 | K1-K3 structure-determined community answers | communities | independent | same/different literals KnownGraphSuite.scala:648-660 | same or different only; no exact label except singleton K3.C |
| KnownGraphSuite | KnownGraphSuite.scala:665 | S1a empty input gives empty tables and a manifest | components;communities;degree;weighted_degree | independent | literal zero counts KnownGraphSuite.scala:670-682 | empty-graph case |
| PipelineFixtureSuite | PipelineFixtureSuite.scala:137 | P C T A M W in full window | degree;weighted_degree | independent | literals PipelineFixtureSuite.scala:140-147 (A checked as t/p :132) | fixture doc FixtureData.scala:7-27 |
| PipelineFixtureSuite | PipelineFixtureSuite.scala:150 | M_r evidence lists works reaching M_r | degree | independent | literals PipelineFixtureSuite.scala:155-172 | evidenceId recomputed with core EvidenceId.of over this run's ID :158-161 |
| PipelineFixtureSuite | PipelineFixtureSuite.scala:176 | windows applied per window | degree;weighted_degree | independent | literals PipelineFixtureSuite.scala:178-184 | time-window correctness |
| PipelineFixtureSuite | PipelineFixtureSuite.scala:187 | every windowed output row states its partition window | integrity | mixed | windows from config :188 (independent); table list taken from the code's own run manifest PipelineFixtureSuite.scala:190-191; lower bound 50 tables literal :192 | |
| PipelineFixtureSuite | PipelineFixtureSuite.scala:207 | coauthor edges canonical order and duplicates aggregated with years and sorted works | weighted_degree;duplicates | independent | 17 edges literal :211; distinct pairs property :212; order property :213; weights and years literals :220-235; edgeId via core EdgeId.coauthor :222-229 | |
| PipelineFixtureSuite | PipelineFixtureSuite.scala:238 | ER self pairs dropped | duplicates | independent | literals PipelineFixtureSuite.scala:241-242 | |
| PipelineFixtureSuite | PipelineFixtureSuite.scala:245 | CONFLICT confidence carried on authorships edges metrics | integrity | independent | literals PipelineFixtureSuite.scala:246-264 | identity uncertainty |
| PipelineFixtureSuite | PipelineFixtureSuite.scala:267 | connected components smallest member ID sizes singletons | components | independent | literals and min-ID rule PipelineFixtureSuite.scala:271-282 (share 8/17 :282) | |
| PipelineFixtureSuite | PipelineFixtureSuite.scala:285 | k-core on clique triangle pendant pair isolated | kcore | independent | literals PipelineFixtureSuite.scala:287-291 | |
| PipelineFixtureSuite | PipelineFixtureSuite.scala:294 | label propagation weighted communities and smallest-label ties | communities | independent | literals and the test's own tie rule from core IDs PipelineFixtureSuite.scala:298-313 | convergence flag read from the code's report :314-316 (a flag check not an expected value) |
| PipelineFixtureSuite | PipelineFixtureSuite.scala:319 | bridges participation coefficient cross-community degree communities connected | bridges | independent | hand formula literals PipelineFixtureSuite.scala:323-335 (36/121 :324) | v depends on the tie rule :330-332 |
| PipelineFixtureSuite | PipelineFixtureSuite.scala:338 | shortest paths from one batched BFS | paths | independent | literal paths PipelineFixtureSuite.scala:361-365 | calls Paths.shortestPaths directly |
| PipelineFixtureSuite | PipelineFixtureSuite.scala:368 | materialized path evidence shortest with hop facts and stable IDs | paths | mixed | hop count vs test-only BFS :376-382 :395 (independent); queries 49 literal :387; found count compared with the code's own report :389; hop facts compared with the pipeline's own coauthor_edge rows :371-372 :404-408 | |
| PipelineFixtureSuite | PipelineFixtureSuite.scala:413 | every edge endpoint exists as a node and only planted violations reported | orphans;integrity | independent | endpoint property :416-423; literal violation map :426 | |
| PipelineFixtureSuite | PipelineFixtureSuite.scala:429 | invalid input records counted and rejected | integrity | independent | literals PipelineFixtureSuite.scala:433-452 | |
| PipelineFixtureSuite | PipelineFixtureSuite.scala:455 | every exported table validates through core and round-trips | integrity | mixed | invalid and roundTripFailures read from the code's own report :458-463; extra round trip :465-482 goes through CoreMirror (Spark code Rows.scala:416 wrapping core Contract); runId property :492-494 | schema validity and provenance runId |
| PipelineFixtureSuite | PipelineFixtureSuite.scala:497 | semantic summaries filled from computed data with content hash | none | mixed | profile subject set and snippet literals :502-510; community list expected from the pipeline's own communities output :514-520 | evidence text; not a group 2 check |
| PipelineFixtureSuite | PipelineFixtureSuite.scala:524 | determinism same inputs give same run ID and identical tables | none | property | run 2 compared with run 1 PipelineFixtureSuite.scala:525-534 | determinism (group 7) |
| PipelineFixtureSuite | PipelineFixtureSuite.scala:537 | row failing core validation refuses the run | integrity | independent | planted 2 invalid rows; literal invalid=2 :560 :565 :571 | output verification refusal |
| PipelineFixtureSuite | PipelineFixtureSuite.scala:594 | reused output must match its manifest | none | independent | planted tamper and refusal :595-605 | run control |
| PipelineFixtureSuite | PipelineFixtureSuite.scala:640 | B-02 identity assignments outside decisions layout refused | none | independent | planted layout :641-643 | input provenance |
| PipelineFixtureSuite | PipelineFixtureSuite.scala:646 | B-02 ER run without _manifest.json or _SUCCESS refused | none | independent | planted removals :647-651 | input provenance |
| PipelineFixtureSuite | PipelineFixtureSuite.scala:654 | B-02 ER manifest fields required and hashes match | none | independent | planted edits :661-686 | input provenance |
| PipelineFixtureSuite | PipelineFixtureSuite.scala:689 | B-02 ER run and policy version recorded | none | independent | literals from fixture constants :692-702 | provenance |
| PipelineFixtureSuite | PipelineFixtureSuite.scala:717 | F6 run manifest hashes run report and load manifests | none | property | sha256 recomputed by the test :721-723 | run control |
| PipelineFixtureSuite | PipelineFixtureSuite.scala:729 | F6 reuse refused when report or load manifest changed | none | independent | planted edits :730-748 | run control |
| PipelineFixtureSuite | PipelineFixtureSuite.scala:751 | F6 reuse refused on code version change or missing control hashes | none | independent | planted edits :758-761 | run control |
| PipelineFixtureSuite | PipelineFixtureSuite.scala:764 | F9 part 1 EMR entry point turns broadcast joins off | none | independent | literal -1 settings :772-777 | Spark configuration |

Totals: 43 tests (KnownGraphSuite 15, PipelineFixtureSuite 28). By expectation_source: independent 37 (KnownGraphSuite 15, PipelineFixtureSuite 22), mixed 4 (:187, :368, :455, :497 of PipelineFixtureSuite), property 2 (:524, :717 of PipelineFixtureSuite), code_under_test 0. I counted these from the table; experiment-engineer should recount from the table, not copy these numbers.

Coverage by hand-worked (independent) expectations: degree, weighted_degree, components, paths, communities, bridges, kcore, integrity, duplicates and orphans each have at least one `independent` test. Limits:
- paths: the hand-checked hops exercise `Paths.shortestPaths` directly. The materialized `path_evidence` test is `mixed`.
- communities: hand expectations cover only structure-determined cases (K1-K3, the fixture's K4, triangle and pair). Exact labels in ambiguous graphs depend on the smallest-label tie rule, and the tests handle that with conditionals.

## Tier 1: invariant counts in the Phase 15 spark-outputs.csv files

Files:
- /Users/brian/Developer/repos/scholarchain/experiments/raw/p15-a-spark/20261120T225850Z-eb786c5275a3-4f7e/spark-outputs.csv
- /Users/brian/Developer/repos/scholarchain/experiments/raw/p15-b-spark-burst/20261120T225913Z-eb786c5275a3-fac6/spark-outputs.csv

The two files are byte-identical (sha256 above) and both name run `run:v1:0f02b5f22d1187932df0fc1decd7e6a5`, so they are one observation.

Columns `run_id,table,window_label,records,invalid_records` are copied by experiments/collectors/ab-pipeline.sh:526-531 from `_run_manifest.json` `outputs[]`. `records` is the row count of the written table (Output.scala:151). `invalid_records` is the number of rows that fail the core contract check through the table's CoreMirror (Output.scala:152-156). For the two tables without a mirror, `invalid_records` is 0 by construction, not a measurement: `rejects` (Pipeline.scala:438-443) and `integrity_violations` (Pipeline.scala:923-926).

| csv_rows | value in file | integrity_property | how |
|---|---|---|---|
| invalid_records on the 63 mirrored tables | 0 on every row | schema validity (core contract fields and ranges) of every output row | Output.scala:155-156 counts rows with any validation error |
| integrity_violations records | 0 | sum of 13 graph integrity checks. Referential or orphan: mention_work_missing, assignment_mention_missing, work_venue_missing, pair_mention_unassigned, pair_work_missing, coauthor_edge_endpoint_missing. Duplicate: duplicate_identity_assignment. Consistency: team_size_mismatch, researcher_anchor_mismatch, pair_year_mismatch, pair_not_in_team, team_pair_without_candidate, coauthor_edge_endpoint_inactive | GraphBuild.scala:115-336 and Pipeline.scala:642-659. The CSV holds only the total, but a total of 0 means each check is 0. Per-check counts are in `_run_report.json` `integrityViolations`, which the CSV does not carry |
| rejects records | 0 | invalid input records (decode or contract failure) | Pipeline.scala:434-443 |
| unresolved_mention records | 0 | mentions without an identity assignment | Pipeline.scala:550-553 |
| nodes:researcher vs canonical_researcher | 200484 = 200484 | every canonical researcher is a node | equal counts |
| nodes:work vs edges:published_in | 155978 = 155978 | every work has one venue edge (complements work_venue_missing = 0) | equal counts |
| coauthor_edge:W vs edges:coauthored_with:W for the 5 windows | equal in each window (1612421; 104067; 248445; 569486; 826665) | every CoauthorEdge has its graph edge | equal counts per window |
| breadth group_size bridges kcore components communities evidence:metric per window | equal in each window (200484; 33768; 58729; 94166; 116301) | one row per active researcher in every metric table (no missing or extra metric row) | equal counts per window; the full window equals canonical_researcher |
| authorship vs edges:authored | 758328 vs 758314 | authored edges are one per (researcher work); the 14 extra authorship rows fit researchers with two mentions on one work | GraphBuild.scala:488-494. Unverified for this run beyond the construction rule (the CSV cannot show it) |
| path_evidence vs semantic_summary:path | 64 = 64 | one path summary per path evidence row | equal counts |

Not measurable from the CSV, because no export exists:
- duplicate coauthor-edge rows. Uniqueness comes from the groupBy pair construction and is tested at PipelineFixtureSuite.scala:212, but the CSV has no count for it.
- edge-support rate (supporting works per edge).
- any per-check integrity breakdown beyond the zero total.
- provenance field presence beyond what core validation enforces.

## Tier 2: C-05

- Finding (Phase 13 review-report-b.md:92, human-gate.md:121): JsonlExport.main's catch and rethrow was untested. Only `show` and `maskText` had tests.
- Fix, following the reviewer's suggestion:
  - `main` delegates to `private[spark] def mainWith(argv: Seq[String], session: () => SparkSession): Unit` (all of main's body).
  - The catch-and-mask is `private[spark] def masked[A](known: Seq[String])(body: => A): A`. It throws `JsonlExportFailedException(maskText("SimpleName: message", known))` with no cause.
  - The `known` authorities are the authorities of `--source-run` and `--out-root`.
- Test: `JsonlExportArgsSuite` "C-05: the entry point rethrows any failure masked, with no cause".
  - It drives `mainWith` with a session factory that throws an IOException. The message carries `s3a://example-out/…`, bare `example-out` and `example-bucket`, and a 12-digit example account in an ARN; the inner cause names `s3://example-bucket/…`.
  - It asserts:
    - the exception type
    - the `IOException: ` prefix
    - `s3a://{data-bucket}/exports`
    - `arn:aws:sts::{account}:`
    - bare names masked through the argument-derived authorities
    - none of the raw names, the account, or the inner text
    - `getCause == null` and no suppressed exceptions
    - `masked` around a post-session failure and the pass-through of a normal value
    - an argument error raised before any session starts
- Fails without the masking: shown. See TEST RESULTS, mutation run, 1 failed of 7.

## Decision 2 status lines

- B-08: still present (modules/spark/src/main/scala/org/scholarchain/spark/Pipeline.scala:306-308). `_checkpoint/run=…` and `_staging/run=…` are created and never removed; there is no delete anywhere under modules/spark/src/main.
- B-10: still present (modules/spark/src/main/scala/org/scholarchain/spark/GraphAnalyticsJob.scala:29-51). There is no unknown-argument check (:31 silently drops unknown or odd pairs), and `--min-free-disk-gb` and `--max-stage-minutes` use `.toLong` (:49-50), which throws NumberFormatException instead of returning Left.

## Proposed later

- B-08 and B-10 fixes (one `fs.delete` per directory after a verified run; parseArgs checks matching JsonlExport.parseArgs). Not in this work order's tiers.
- Carry per-check integrity counts in a collector CSV (experiment-engineer's collector, not mine), so that the integrity breakdown is visible without the run report.

## INTERFACE HANDOFF

- Files changed: JsonlExport.scala and JsonlExportArgsSuite.scala (paths above).
- Public signatures:
  - `JsonlExport.main(argv: Array[String]): Unit` is unchanged.
  - new `private[spark] def mainWith(argv: Seq[String], session: () => SparkSession): Unit`
  - new `private[spark] def masked[A](known: Seq[String])(body: => A): A`
  - No other signature changed. `JsonlExportJob.main` still delegates to `JsonlExport.main`.
- Behavior: session-start and stop failures are now masked too.
- C-05 test name: `org.scholarchain.spark.JsonlExportArgsSuite` / "C-05: the entry point rethrows any failure masked, with no cause".
- Table columns for X2 (stable): `suite | test_ref | test_title | group2_checks | expectation_source | evidence | note`.
  - `group2_checks` values: {degree, weighted_degree, components, paths, communities, bridges, kcore, integrity, duplicates, orphans, none}, separated by `;`.
  - `expectation_source` values: {independent, mixed, property}.
  - Invariant table columns: `csv_rows | value in file | integrity_property | how`.
- Fixtures: none added. The test uses synthetic `example-*` names and a synthetic 12-digit account string, the same one the existing maskText test already uses.
- Thread safety: `masked` and `mainWith` are pure apart from the session they are given.
- Limits: `main`'s one-line session factory is not exercised by the test.

## VALIDATION EVIDENCE

- `sbt -batch "sparkAnalytics/testOnly org.scholarchain.spark.JsonlExportArgsSuite"` with the temporary mutation `throw e` in `masked`: `Failed: Total 7, Failed 1, Errors 0, Passed 6`. The failing test was the C-05 test (`IOException` is not a subtype of `JsonlExportFailedException`).
- I undid the mutation and confirmed it with `grep -n "C05-MUTATION"` (no output).
- `sbt -batch "sparkAnalytics/testOnly org.scholarchain.spark.JsonlExportArgsSuite org.scholarchain.spark.JsonlExportSuite"`: `Passed: Total 21, Failed 0, Errors 0, Passed 21`, `[success] Total time: 129 s`, completed Nov 21, 2026, 8:54:43 PM.
- `shasum -a 256` of both spark-outputs.csv files: identical, ad70f64017cd4a071d1ab1a8dd8b7b121149e342059e8e1a856da3a695dedc6d.
- `git status --short modules/spark`: only JsonlExport.scala and JsonlExportArgsSuite.scala modified.
- files changed after these runs: none (only this report, in the scratchpad)
