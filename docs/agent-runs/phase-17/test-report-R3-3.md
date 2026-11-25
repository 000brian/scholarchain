STATUS: COMPLETE
OWNER: test-engineer
WORK ORDER: Phase 17, session 3, repair round R3, minor repair (code-reviewer Part B finding 6); test-engineer is the wave closer
FILES CREATED: none
FILES MODIFIED: /Users/brian/Developer/repos/scholarchain/tests/contracts/src/test/scala/org/scholarchain/contracts/hadoop/OversizeBlockAdversarialSuite.scala
COMMANDS RUN:
- sbt -batch "contractTests/testOnly org.scholarchain.contracts.hadoop.OversizeBlockAdversarialSuite" (log: /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/73f10a2e-9b01-4d44-898e-9399e20392db/scratchpad/phase17-reports/targeted-R3-3.log) -> exit 0
- sbt check (log: /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/73f10a2e-9b01-4d44-898e-9399e20392db/scratchpad/phase17-reports/sbt-check-R3-3.log) -> exit 0
- read-only grep, ls, cat, date, ps; bounded wait loops (grep + sleep 5, capped iterations) on the log files
TEST RESULTS:
- Targeted: "[info] Passed: Total 18, Failed 0, Errors 0, Passed 18"; "[success] Total time: 283 s", exit 0.
- sbt check: exit 0, "[success] Total time: 1450 s (0:24:10.0), completed Nov 25, 2026, 12:54:49 AM".
  - Ledger: "Ledger check passed (scalafmt 3.11.5)."; buildSelfTest ok; buildPolicyCheck ok.
  - Format: scalafmt check of all source groups passed (no format error).
  - Compile: no [error] lines.
  - Module tests: suites=151 executed=2362 passed=2362 failed=0 errors=0 skipped=5.
  - Contract tests (no AWS): suites=35 executed=418 passed=418 failed=0 errors=0 skipped=0.
  - OversizeBlockAdversarialSuite in the check: 0 failed, 0 ignored, 18 total.
  - New marker: .claude/.last-test-run mtime 2026-11-25 00:54:49 local; contents timestamp=2026-11-25T06:54:49.678796Z, module_tests=2362, contract_tests=418.
VALIDATION RESULTS: The full stats/ byte comparison (largestReduceGroup line included) passes for all three variant layouts (reversed, five-files, small-splits) against base, for all five jobs (J05p, J05, J05bp, J05b, J05c). hadoop-engineer's R3 repair claim (every J05/J05b output file, stats/ included, byte-identical across layouts) holds under this test.
ASSUMPTIONS: The stats/ part files are named part-r-* (reduceBytes filters on that prefix); the new non-vacuity assertion confirms that J05 and J05b each have a stats/ part file carrying "largestReduceGroup", so the line is really compared.
RISKS: Low. The comparison remains limited to these four layouts on the contract fixture. One GC warning (1 GiB sbt heap) appeared in the check log at line 6336; it did not affect results.
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer (optional focused check of finding 6), then human ratification.

## Exact change (finding 6)

File: tests/contracts/src/test/scala/org/scholarchain/contracts/hadoop/OversizeBlockAdversarialSuite.scala
- Removed the private helper `withoutReduceGroupStat` (formerly :527-531), which filtered the `largestReduceGroup` line out of stats/ before comparing.
- :527-529 scaladoc now states every reduce output file, stats/ included with its largestReduceGroup line, is compared byte for byte (after run-ID substitution).
- :536-545 new non-vacuity check: for base.blocking (J05) and base.fallbackBlocking (J05b), at least one stats/ part file contains `"largestReduceGroup"`; otherwise the test fails.
- :565-569 the per-file comparison is now a single `Option.when(by(rel) != s)(...)` for every file; the special stats/ case is gone.
- The dedicated test "largestReduceGroup stays within the cap and does not depend on the map-task layout" (now :580) is unchanged.

## Requirement-to-test coverage

| Requirement | Test | Result |
|---|---|---|
| J05..J05c outputs byte-identical across input layouts, stats/ incl. largestReduceGroup | deterministic test (:530) | pass (3 layouts x 5 jobs) |
| largestReduceGroup within cap and layout-independent | :580 | pass |

## Failures by owning agent

none

## INTERFACE HANDOFF

- Files changed: tests/contracts/src/test/scala/org/scholarchain/contracts/hadoop/OversizeBlockAdversarialSuite.scala only (test code).
- Public signatures: none (private helper removed; no production API touched).
- Fixtures: unchanged.
- Thread-safety: n/a (test code; shared lazy chain results as before).
- Limits: the stricter comparison will now fail if any future change makes the largestReduceGroup stat (or its subject text) depend on the map-task layout.
