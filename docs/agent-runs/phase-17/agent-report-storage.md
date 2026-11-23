# Phase 17 session 2, step 2: storage-engineer report

STATUS: COMPLETE
OWNER: storage-engineer
WORK ORDER: docs/agent-runs/phase-17/prompt.md (sha256 c22adfc57c7fde0cae63c8520393c2c81cd12280df7f5df773e70c2e2ec8ac24, verified with shasum), decision 8.2: P16-A-03 and P16-A-05 (tier 2); routing-p17.md rows 28-29. Not the wave closer.
FILES CREATED: none
FILES MODIFIED:
- /Users/brian/Developer/repos/scholarchain/modules/storage/src/test/scala/org/scholarchain/storage/GraphQuerySuite.scala (P16-A-03: one new test, `GraphStop` import)
- /Users/brian/Developer/repos/scholarchain/modules/storage/src/test/scala/org/scholarchain/storage/emulator/EmulatorWiringSuite.scala (P16-A-05: the lib.sh comparison test now also asserts `libSafetyMismatches(lib) == Nil`; new private helpers `libText`, `globMatches`, `libCasePattern`, `libSafetyMismatches`; one new negative test)
- Production code: unchanged. InMemoryGraph.scala and EmulatorSafety.scala were edited for a temporary revert and restored (git diff empty for both; their mtimes changed).
COMMANDS RUN:
- shasum -a 256 on the work order; grep over the review report, work order and routing table; ls; git status --short; git diff --stat; ls -lT | cut
- sbt -batch "storage/testOnly org.scholarchain.storage.GraphQuerySuite org.scholarchain.storage.emulator.EmulatorWiringSuite" three times (green, revert, green)
- The PostToolUse formatter hook ran scalafmtOnly on the edited files (no manual `sbt fmt`)
- Disclosed: at the start I used `sed -n` twice (with `grep -n ""` and on the work order) to read lines, before I read the command list. Both were read-only. Not on the work order's list.
TEST RESULTS:
- Run 1 (new tests): GraphQuerySuite 11/11, EmulatorWiringSuite 22/22, Passed 33, Failed 0
- Run 2 (temporary reverts; see below): Failed 4 of 33, as expected
- Run 3 (after restore): GraphQuerySuite 11/11, EmulatorWiringSuite 22/22, Passed 33, Failed 0, [success]
VALIDATION RESULTS: targeted tests only (law 6, work order). No full sbt check (not the wave closer). No EmulatorTest or AwsTest runs: both suites are plain `Test` suites. The lib.sh comparison reads `scripts/emulator/lib.sh` from disk and runs without the emulator.
ASSUMPTIONS:
- "complete the comparison" is met by reading each listed rule from lib.sh's text and comparing it with what `EmulatorSafety.check` does on the same sample inputs, not only by string-contains checks. That way a change on either side fails the test.
- The negative test runs mutated copies of lib.sh's text in memory, so it shows that the comparison catches a lib.sh change without editing infra-engineer's file.
RISKS:
- The lib.sh extraction regexes depend on lib.sh's current layout (for example `case "${VAR:-}" in /*) ;; *) EMU_FAILED_RULE="..."`). If infra-engineer reformats lib.sh, the test fails with "no case block" or "not found" and does not pass silently. That is fail-closed, but it may need a test update.
- lib.sh is being modified by other steps of this session? It is not: `git status` shows env.sh and probes.sh modified, lib.sh not modified.
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: step 3 (build-engineer, then hadoop-engineer); the full sbt check by the wave closer (step 4, service-engineer); R-1 and F-2 review of this diff.

## P16-A-03 (InMemoryGraph time limit)

Test `GraphQuerySuite` "P16-A-03: a timed-out path search says stoppedBy = TimeLimit; found and no-path say None":
- found path (depth 4) and no path within depth 2: `stoppedBy == None`
- a clock that advances 1000 ms per reading (budget `timeoutMillis` = 1000 from the runtime test config) stops the BFS at the first check: `stoppedBy == Some(GraphStop.TimeLimit)`, rows Nil, complete false, truncated false, depthSearched Some(0)
- Shown to fail without the behaviour: I removed `Some(GraphStop.TimeLimit)` at InMemoryGraph.scala:291 temporarily, and the test failed at GraphQuerySuite.scala:190 (ComparisonFailException). Then I restored the line.

## P16-A-05 (lib.sh comparison)

`libSafetyMismatches(lib)` returns every difference for the three parts the finding lists:
1. The results-dir set test (lib.sh:140): extracts the `+x` / `:+x` operator and compares "empty counts as set" with `check(good + (RESULTS_DIR -> ""))`.
2. The absolute-path `case` rules for HOME / credentials-file (lib.sh:107-113), SCHOLARCHAIN_CONFIG_FILE / config-file-path (:133-139) and SCHOLARCHAIN_EMULATOR_RESULTS_DIR / results-dir-path (:141-147): it extracts each glob and compares acceptance by lib.sh and by Scala on the samples "/", "/a/b", "a", "", "./a", "a/", " /a".
3. The credentials and config path suffixes (lib.sh:91 emu_dir, :115, :123, plus `dir="$(emu_dir)"`): builds lib.sh's paths and requires `EmulatorSafety.check` to accept them.
- The existing test asserts the result is Nil against the real lib.sh (passes today).
- New test "P16-A-05: the lib.sh comparison fails when …": seven in-memory mutations of lib.sh (`+x` to `:+x`, which is the finding's own example; the three globs changed; the emu_dir base, the credentials suffix and the config suffix changed). Each must produce a mismatch with the matching label. All pass.
- Shown to fail on the Scala side: I temporarily changed EmulatorSafety.scala:118 so that an empty results dir counts as unset (`get(ResultsDirVar).nonEmpty`). The comparison test failed at EmulatorWiringSuite.scala:299 (the `libSafetyMismatches(lib) == Nil` line). Then I restored it. In the same run, the existing absolute-path test (:237) and the negative test (:439) also failed. This was expected, because with that revert Scala matched the `:+x` mutation.

## Idempotency / store notes

Not applicable: these are tests only. No store adapter, key design or load path changed.

## INTERFACE HANDOFF

- Files changed: the two test files above (modules/storage/src/test/**)
- Public signatures: none (the new members are private to EmulatorWiringSuite; no production change)
- Fixtures: none new; reads scripts/emulator/lib.sh (unchanged) and config/emulator.json as before
- Thread-safety: n/a (single-threaded test code)
- Limits: the extraction regexes are bound to lib.sh's current text layout (see RISKS)

## VALIDATION EVIDENCE

- `sbt -batch "storage/testOnly org.scholarchain.storage.GraphQuerySuite org.scholarchain.storage.emulator.EmulatorWiringSuite"` (final run, 17:51:25): GraphQuerySuite 0 failed / 11 total; EmulatorWiringSuite 0 failed / 22 total; `Passed: Total 33, Failed 0, Errors 0, Passed 33`; `[success]`
- Revert run (17:50:52): `Failed: Total 33, Failed 4` (GraphQuerySuite.scala:190; EmulatorWiringSuite.scala:237, :299, :439), then both reverts restored (git diff of InMemoryGraph.scala and EmulatorSafety.scala empty)
- Reports: modules/storage/target/test-reports/TEST-org.scholarchain.storage.GraphQuerySuite.xml (17:51:24), TEST-org.scholarchain.storage.emulator.EmulatorWiringSuite.xml (17:51:25). Both are newer than every changed storage file (newest: EmulatorSafety.scala mtime 17:51:05, content unchanged)
- `git status --short` after the runs: storage changes only in the two test files; other entries belong to other steps
files changed after these runs: none
