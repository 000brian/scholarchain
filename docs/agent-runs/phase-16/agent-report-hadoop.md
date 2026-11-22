STATUS: COMPLETE
OWNER: hadoop-engineer
WORK ORDER: docs/agent-runs/phase-16/prompt.md (sha256 c75cc5f895faed13880a69ec285231c5498fd50924c7b875fe7e02cce1707dc8, verified with shasum), HEAD 9da45ab616a764e449f74f25feca85ed77d6dfe4 plus uncommitted session 2 work; session 2 step 5 second half; decision 11 hadoop part: G-19 (tier 2); decision 2 status line A-03.
FILES CREATED: none in the repository (this report only, in the scratchpad)
FILES MODIFIED:
- /Users/brian/Developer/repos/scholarchain/modules/hadoop/src/test/scala/org/scholarchain/hadoop/LocalPipelineRunner.scala
- /Users/brian/Developer/repos/scholarchain/modules/hadoop/src/test/scala/org/scholarchain/hadoop/LocalPipelineRunnerSuite.scala
COMMANDS RUN:
- shasum -a 256 docs/agent-runs/phase-16/prompt.md (matched)
- grep -n / grep -rn with literal words (G-19, A-03, run-report, settings, withDefaults, maxBlockSize, ...) over docs/agent-runs/phase-14, phase-11, phase-16/prompt.md, modules/**, scripts, experiments (reading only)
- sbt -batch "hadoop/testOnly org.scholarchain.hadoop.LocalPipelineRunnerSuite" three times: (1) with the fix: 4 passed; (2) with the fix temporarily reverted (settings = -D only): 1 failed, 3 passed, as intended; (3) fix restored: 4 passed
- git status --short modules/hadoop; git diff --stat modules/hadoop
TEST RESULTS:
- Run 1 (fix): "Passed: Total 4, Failed 0, Errors 0, Passed 4" (21:01:41)
- Run 2 (fix reverted): "Failed: Total 4, Failed 1" - "a full run on the synthetic sample ..." failed with java.util.NoSuchElementException: key not found: scholarchain.blocking.fallback.maxBlockSize (LocalPipelineRunnerSuite.scala:145). This is the test that fails without the change.
- Run 3 (fix restored, final): "Passed: Total 4, Failed 0, Errors 0, Passed 4" (21:03:59)
- Direct users of the changed files: LocalPipelineRunner is used only by LocalPipelineRunnerSuite (grep over modules/hadoop/src); it is test scope, so no other project depends on it.
VALIDATION RESULTS: targeted suite passed (above). No full sbt check (not the wave closer; work order says do not run it). No sbt fmt run; the format hook formatted the written files; git status shows only the two hadoop files changed in modules/hadoop.
ASSUMPTIONS:
- "Effective settings" means what every job reads: the runner's Configuration plus the defaults resource, added exactly as JobRunner.run adds it (Settings.withDefaults), restricted to scholarchain.* keys outside the per-run prefixes scholarchain.run.* and scholarchain.path.* (set per job by drivers), plus every -D key (so Hadoop keys given with -D are recorded too). Other Hadoop keys (fs.defaultFS, LocalJobRunner settings from TestSupport.localConf) are left out.
- The existing report field "settings" now holds the effective values (it was the -D list), and a new field "overrides" holds the -D arguments as given. I kept the name "settings" for the effective values because the only reader, ER's EvalReport (UpstreamFieldsRead: settings.scholarchain.blocking.maxBlockSize and settings.scholarchain.blocking.fallback.maxBlockSize), then gets the real caps without any entity-resolution change; this is what the G-19 evidence (EvalReport blockingCoverage null instead of 200) asks for.
RISKS:
- Meaning change of run-report.json "settings": a reader that took "settings" to be the -D list would now see more keys. grep found no other reader (experiments/collectors and scripts mention ER's run-report.json, not this one). The format is undeclared (not a core contract), as EvalReport's UpstreamFormatNote already says.
- Values come from Configuration.get, which expands ${var} references; this is the value the jobs see, but a -D value containing ${...} would be recorded expanded in "settings" and verbatim in "overrides".
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer review of the wave; full sbt check by the wave closer (step 7).

## G-19

fix-status: G-19 fixed in code, tested offline, not validated live.
- Source: docs/agent-runs/phase-14/review-report-G-17.md:14 and human-gate.md:130.
- Change: LocalPipelineRunner.effectiveSettings (new) and run(): report "settings" = effective values; new "overrides" = -D as given; scaladoc header updated.
- Location check: the local pipeline runner lives entirely in modules/hadoop (test scope); nothing in scripts/** is involved, so no infra-engineer part.
- Tests: new test "effective settings: defaults plus -D (override wins), -D Hadoop keys, no per-run keys" (override 3 wins over default 200; fallback cap 200 and maxPairsPerBlock 125000 recorded from defaults; -D Hadoop key recorded; no scholarchain.run.* / scholarchain.path.* / fs.defaultFS; caller's conf unchanged). Full-run test extended: saved run-report settings has fallback maxBlockSize "200" (not given with -D), maxBlockSize "3", maxPairsPerBlock "125000"; overrides has exactly the four -D keys. The fallback cap check no longer falls back to a guessed 500.

E5 impact (Phase 15 clip 19c local Hadoop pipeline replay): Hadoop job outputs are unchanged (records, dispositions, run IDs, counters, _job.json, summary): the Configuration given to Pipeline.run is the same object as before, and effectiveSettings reads a copy (tested). run-report.json changes: "settings" gains keys (every defaulted scholarchain.* key); keys given with -D keep their values; new field "overrides". Downstream, ER's evaluation report reads the two caps from "settings": where clip 19c did not pass a cap with -D, blockingCoverage maxBlockSize / fallbackMaxBlockSize change from null to the effective value ("200" with today's defaults); where it did pass them, they are unchanged. So: new fields plus newly filled values, no changed Hadoop numbers.

## A-03 status line (decision 2)

A-03: still present (modules/hadoop/src/main/scala/org/scholarchain/hadoop/teams/PublicationTeamJob.scala:81 replacing UTF-8 decode, used by modules/hadoop/src/main/scala/org/scholarchain/hadoop/sources/NormalizeJob.scala:71; modules/hadoop/src/main/scala/org/scholarchain/hadoop/sources/SourceNormalizers.scala:131 rawSha256 over the decoded line; modules/hadoop/src/main/scala/org/scholarchain/hadoop/io/LineScanner.scala:101 strips the trailing CR). Not built in Phase 16 (decision 2); last change to these files 4d8598f (Phase 4).

## Runtime

runtime: about 10 minutes of agent work (estimate, from sbt completion stamps 21:01:41 to 21:03:59 plus reading); three sbt runs of about 56 s each (sbt "Total time").

## Proposed later

- Record Settings.fingerprint of the effective configuration in run-report.json (ties the report to the run IDs); not in decision 11's scope.

## INTERFACE HANDOFF

- Files changed: modules/hadoop/src/test/scala/org/scholarchain/hadoop/LocalPipelineRunner.scala, modules/hadoop/src/test/scala/org/scholarchain/hadoop/LocalPipelineRunnerSuite.scala (both test scope).
- Public signature added: `LocalPipelineRunner.effectiveSettings(conf: org.apache.hadoop.conf.Configuration, overrides: List[(String, String)]): List[(String, String)]` (sorted by key; absent value recorded as "").
- run-report.json (undeclared format, written by modules/hadoop): "settings" = effective values (object, string values, sorted keys); "overrides" (new) = -D arguments (object, sorted keys). All other fields unchanged. Reader: entity-resolution EvalReport (no change needed there).
- Fixtures: none added; uses datasets/samples/dblp/dblp-synthetic-edge-cases.xml as before.
- Thread-safety: effectiveSettings is pure over a copied Configuration; called once before the watchdog starts.
- Limits: covers the local runner only; the EMR path has no run-report.json (job _job.json records are unchanged).

## VALIDATION EVIDENCE

- sbt -batch "hadoop/testOnly org.scholarchain.hadoop.LocalPipelineRunnerSuite" (fix): Passed: Total 4, Failed 0, Errors 0, Passed 4 - completed Nov 21, 2026, 9:01:41 PM
- same, fix reverted: Failed: Total 4, Failed 1 (key not found: scholarchain.blocking.fallback.maxBlockSize, LocalPipelineRunnerSuite.scala:145) - completed 9:02:51 PM
- same, fix restored (final): Passed: Total 4, Failed 0, Errors 0, Passed 4 - completed 9:03:59 PM
- files changed after these runs: none
