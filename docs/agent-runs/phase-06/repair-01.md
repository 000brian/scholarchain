## Summary

F1 is fixed. A run now writes no load manifests and no `_run_manifest.json` when any exported table has rows that fail core validation (`invalid > 0`) or failed sample round trips (`roundTripFailures > 0`). It stops with a new `OutputVerificationException`. The run report is still written, with the failing counts. A new test plants invalid rows. It fails without the fix and passes with it. The attempt-03 real run finished and passed verification. After masking the run ID and the evidence IDs derived from it, its rows match attempt-02 in all 65 tables. `sbt check` passed in 15:12 (1158 module tests, 336 contract tests).

STATUS: COMPLETE
OWNER: spark-engineer
WORK ORDER: docs/agent-runs/phase-06/prompt.md, repair round for code-review finding F1 (major) only. F2–F10 were not touched. conf/phase06.json caps were not changed.

FILES CREATED: none

FILES MODIFIED:
- /Users/brian/Developer/repos/scholarchain/modules/spark/src/main/scala/org/scholarchain/spark/Output.scala
- /Users/brian/Developer/repos/scholarchain/modules/spark/src/main/scala/org/scholarchain/spark/Pipeline.scala
- /Users/brian/Developer/repos/scholarchain/modules/spark/src/test/scala/org/scholarchain/spark/PipelineFixtureSuite.scala
- /Users/brian/Developer/repos/scholarchain/modules/spark/src/test/scala/org/scholarchain/spark/CompareRuns.scala (test-scope evidence tool; new opt-in `--ignore-run-id` flag, explained below)

COMMANDS RUN (all output observed; logs are in the scratchpad /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/2614013c-933e-4dcb-99ca-fc3105c6c7c1/scratchpad/):
1. `sbt -batch "sparkAnalytics/testOnly org.scholarchain.spark.PipelineFixtureSuite"`: the first attempt hit a test compile error (an `ArrayBuffer` vs `Seq` compare), which I fixed. Rerun: Passed 20/20, 96 s (targeted-2.log).
2. The same suite with the gate turned off temporarily: Failed 1 of 20, the new test (targeted-reverted.log). The gate was then restored.
3. `sbt -batch "sparkAnalytics/Test/runMain org.scholarchain.spark.LocalRealRun --master local[6] --shuffle-partitions 32 --hadoop-export <J09 run=32bda243...> --identity-assignments <ER run=9f96c70b.../decisions/identity_assignment> --out-root .../attempt-03 --config modules/spark/conf/phase06.json --code-version phase06-7070f61-02-dirty --min-free-disk-gb 15 --max-stage-minutes 90"` (background): success, 558 s (real03.log).
4. `CompareRuns attempt-02 attempt-03` with raw rows: 60 of 65 tables differ, all because of the run ID (compare03.log).
5. `CompareRuns attempt-02 attempt-03 --ignore-run-id`, run ID masked only: 13 evidence and summary tables differ, because their evidence IDs are derived from the run ID (compare03-norm.log).
6. `CompareRuns attempt-02 attempt-03 --ignore-run-id`, also masking evidence IDs that equal `EvidenceId.of(type, ownRunId, subjectIds)`: 0 of 65 tables differ (compare03-norm2.log).
7. `sbt -batch check` (background): exit 0, `[success] Total time: 912 s (0:15:12.0)`, `Updated .claude/.last-test-run (module_tests=1158, contract_tests=336)` (check-f1.log).

TEST RESULTS:
- `sbt check` passed with 1158 module tests (1157 before plus the new test) and 336 contract tests, with no `==> X` lines. Wall time was 15:12 (912 s).
- The targeted PipelineFixtureSuite passed 20/20.
- With the gate disabled it failed 1 of 20 (the new test).

VALIDATION RESULTS:
- The attempt-03 `_run_report.json` has `"verification": {"passed": true, "failingTables": []}`.
- All 65 tables have `"invalid": 0` and `"roundTripFailures": 0`.
- All four load manifests (neptune 12, dynamodb 42, s3 65, s3vectors 7 table entries) now carry `"invalidRecords": 0`.
- `_run_manifest.json` is present.
- Free disk was about 316 GB afterwards. No stage came near 90 min (the longest was 97.5 s). There was no OOM, so the local[4] retry was not needed.

ASSUMPTIONS:
- The test calls the exact function the pipeline calls after verification (`Manifests.writeVerified`). It feeds it `TableOutput`s produced by the real `OutputWriter.write`/`verifyAll` on a table with planted invalid rows. It does not inject a failure into a full `Pipeline.run`: `CoreMirror` is sealed and there is no injection hook, and I chose not to add a test-only hook to production code.
- I also put `invalidRecords` back into the load manifests (the finding listed its absence). On a run that passes, the value is always 0.

RISKS:
- The `CompareRuns` header doc only mentions masking the run ID. The edit adding the evidence-ID sentence failed after the formatter reflowed the comment, and I did not retry it while `sbt check` was running. An inline comment at the masking code explains it. Cosmetic only.
- Tables written before a refusal stay on disk in the refused run directory, with no manifest. Later runs refuse to reuse that directory (the test checks this), and loaders that need a load manifest will never load it.

MANUAL ACTION REQUIRED: none. Nothing was committed and nothing was written under docs/agent-runs/.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer re-checks F1 only, then the human gate.

## Metric definitions
Unchanged. This repair only gates manifest writing.

## Approximations and limits
Unchanged.

## Output invariants checked
- No load manifest and no run manifest when any table has invalid > 0 or roundTripFailures > 0. The run report is still written and records `verification.failingTables` (table, path, invalid, invalidSamples, roundTripSampled, roundTripFailures).
- The run manifest is still written last.
- The real-run tables match attempt-02 row for row (see CompareRuns below).

## REPAIR F1

Exact change:
- `Output.scala:49-52`: new `final class OutputVerificationException`.
- `Output.scala:255-262`: `loadManifests` no longer removes `invalidRecords`.
- `Output.scala:269-317`: new `Manifests.failingTables(tables)` and `Manifests.writeVerified(runDir, runId, tables, runManifest, runReport): ujson.Obj`. It works in this order:
  1. Adds a `verification` block to the run report and writes the report.
  2. Throws at line 303 `if (failing.nonEmpty) throw new OutputVerificationException("run <id> refused: N exported table(s) failed core verification (<table>: invalid=…, roundTripFailures=…); no load manifests and no _run_manifest.json were written, see _run_report.json")`.
  3. Only if nothing failed, writes the load manifests and then, last, `_run_manifest.json`.
- `Pipeline.scala:866-869`: the inline load-manifest, report and run-manifest writes (old lines 835-840 and 872-880) are replaced by `val written = Manifests.writeVerified(runDir, runId, tables, manifest, fullReport)`. `PipelineResult` now carries the report as written.

New test: `PipelineFixtureSuite`, "a table with a row failing core validation refuses the run: no load or run manifest is written" (PipelineFixtureSuite.scala:512). What it does:
1. Takes the fixture run's breadth-metrics rows. It plants one row with `identityConfidence = 2.0` and one with `provenance.runId = ""`, then writes them through the real `OutputWriter` with `CoreMirror.MetricsM`.
2. Asserts `verifyAll` reports invalid = 2.
3. Asserts `writeVerified` throws `OutputVerificationException` mentioning `invalid=2`.
4. Asserts there is no `_load_manifests/` and no `_run_manifest.json`, and that `_run_report.json` has `verification.passed = false` with a failing table showing invalid 2 and 2 samples.
5. Asserts a later `Pipeline.run` into that root throws `RunRefusedException`, so the refused output is never reused.
6. Asserts a `roundTripFailures = 1` failure on its own also refuses with no manifests.
7. Asserts the passing run's neptune load manifest lists `invalidRecords = 0` on every table and its report has `verification.passed = true`.

Evidence it fails without the fix: I changed `Output.scala:303` to `if (false && failing.nonEmpty)`, which brings back the old behavior of always writing the manifests. The suite then failed: `==> X ... a table with a row failing core validation refuses the run ... PipelineFixtureSuite.scala:537 expected exception of type 'org.scholarchain.spark.OutputVerificationException' but body evaluated successfully`, `Failed: Total 20, Failed 1, Passed 19`. I restored the line in the same command (confirmed by `sed -n 303p`) before anything else ran, and the full `sbt check` passed afterwards.

Attempt-03 real run:
- Run ID: `run:v1:51b50d578d5b609842c1c21b8266a00b`
- Path: /Users/brian/Developer/repos/scholarchain/modules/spark/target/phase-06-real/attempt-03/run=51b50d578d5b609842c1c21b8266a00b
- Settings: local[6], shuffle partitions 32, checkpoints under the attempt root, code version `phase06-7070f61-02-dirty`.
- Wall time 551.9 s (sbt total 558 s), heapMax 6144 MB. Attempt-02 took 458 s.
- Stage times (s): decode-inputs 16.5, canonical-graph 30.2, vertex-index 8.1, window:full-2015-2025 97.5, w2015-2017 27.5, w2018-2020 36.9, w2021-2023 58.6, w2024-2025 69.5, paths-diameter 86.5, semantic-summaries 12.8, integrity 57.3, temporal-change 1.2, verify-outputs 36.7.

CompareRuns against attempt-02 (`run:v1:d6740be5d294d21a5d8291e24853b80f`):
- The run IDs differ because the code version is part of the run ID, which every provenance row carries.
- Raw row comparison: 60 of 65 tables differ only for that reason.
- I added an opt-in `--ignore-run-id` flag to the test-scope `CompareRuns`. It replaces each run's own run ID with a placeholder in the row JSON. It replaces `evidence.evidenceId` only where the ID exactly equals core's `EvidenceId.of(type, thatRunId, subjectIds)`, so any other difference still shows up.
- Result: `tables A=65 B=65 sameList=true`, all 65 tables `onlyA=0 onlyB=0` with equal row counts, `tables with different rows: 0`. Apart from the run ID and the evidence IDs derived from it, the attempt-03 rows match attempt-02.
