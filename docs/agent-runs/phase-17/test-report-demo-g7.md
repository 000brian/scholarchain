STATUS: COMPLETE
OWNER: test-engineer
WORK ORDER: Phase 17, session 3, post-release-candidate: replay one targeted contract suite for the demo video (UnsupportedClaimEndToEndSuite); no repository change.
FILES CREATED: none in the repository. Scratchpad only: this report; logs g7-run1.log and g7-run2.log in the same scratchpad folder.
FILES MODIFIED: none
COMMANDS RUN:
1. sbt -batch "contractTests/testOnly org.scholarchain.contracts.runtime.UnsupportedClaimEndToEndSuite"   (foreground, repository root, output captured to scratchpad g7-run1.log)
2. (cheap filter check, run once because run 1 passed) sbt -batch "contractTests/testOnly org.scholarchain.contracts.runtime.UnsupportedClaimEndToEndSuite -- -v *semantic*"   (foreground, output to scratchpad g7-run2.log)
Read-only: cat/grep/ls/wc of the scratchpad logs and of the suite's sbt XML report; grep of docs/ and README.md for "testOnly" with "--". One sbt process at a time; no clean, no runMain, no tagged live tests, no network or AWS.
TEST RESULTS:
Run 1: exit code 0.
  Console:
    Test run org.scholarchain.contracts.runtime.UnsupportedClaimEndToEndSuite started
    org.scholarchain.contracts.runtime.UnsupportedClaimEndToEndSuite: finished 1.75s
    Test run org.scholarchain.contracts.runtime.UnsupportedClaimEndToEndSuite finished: 0 failed, 0 ignored, 7 total 1.757s
    [info] Passed: Total 7, Failed 0, Errors 0, Passed 7
    [success] Total time: 4 s, completed Nov 25, 2026, 6:51:04 PM
  The console does not print per-test lines without -v. Per-test results from sbt's report
  tests/contracts/target/test-reports/TEST-org.scholarchain.contracts.runtime.UnsupportedClaimEndToEndSuite.xml
  (tests="7" errors="0" failures="0" skipped="0", 0 failure/error elements), all PASS:
    - a number the evidence does not hold is never verified; the answer abstains   PASS 0.832s
    - a bound number that differs from its cited field is conflicting, not verified   PASS 0.18s
    - a hop count that differs from the cited path is conflicting   PASS 0.149s
    - semantic context cited for a number is unsupported   PASS 0.148s   (line 103)
    - the audit counts every status the response shows   PASS 0.148s
    - T-10 a claim naming a collaborator the cited evidence lacks is not kept   PASS 0.14s
    - an unsupported claim never appears with status verified in an async result   PASS 0.144s
Run 2 (single test filter): exit code 0.
    org.scholarchain.contracts.runtime.UnsupportedClaimEndToEndSuite.semantic context cited for a number is unsupported started
      + semantic context cited for a number is unsupported 0.782s
    Test run org.scholarchain.contracts.runtime.UnsupportedClaimEndToEndSuite finished: 0 failed, 0 ignored, 1 total 0.798s
    [info] Passed: Total 1, Failed 0, Errors 0, Passed 1
    [success] Total time: 2 s, completed Nov 25, 2026, 6:51:29 PM
  Totals: run 8 test executions (7 + 1), passed 8, failed 0, skipped 0.
VALIDATION RESULTS:
- Line range for the demo: sed -n '103,123p' tests/contracts/src/test/scala/org/scholarchain/contracts/runtime/UnsupportedClaimEndToEndSuite.scala
  Line 103: test("semantic context cited for a number is unsupported") {
  Lines 104-110: the scripted model cites only a SemanticContext evidence item for the numeric claim "Dan Brown has 2 publications."
  Line 118: assertEquals(r.status, 200, r.body.take(300))
  Line 120: assertEquals(c("status").str, "unsupported")
  Line 121: assertEquals(c("evidenceIds").arr.toList, Nil)
  Tighter range showing name plus assertions only: name at 103, assertions at 118-121 (sed -n '118,121p').
- Expected claim status value: "unsupported" (HTTP status 200; the claim's evidenceIds list is empty).
- Single-test filter: yes. MUnit takes a test-name glob after `--`. Existing records use this form: docs/agent-runs/phase-05/agent-report-domain.md:26 (`sbt "core/testOnly org.scholarchain.core.WireContractSuite -- *reproduces*"`), docs/agent-runs/phase-02/repair-01.md:59 (`-- *passes`, "Passed: Total 1"), and docs/agent-runs/phase-05/test-report-02.md:315 (`-- -v`). The form I checked, which runs only that test and prints its name, is:
    sbt -batch "contractTests/testOnly org.scholarchain.contracts.runtime.UnsupportedClaimEndToEndSuite -- -v *semantic*"
  Only one test name in the suite contains "semantic", so the glob picks exactly that test. I did not try `--tests=`; the glob form is the one proven in this repository.
ASSUMPTIONS: HEAD 1823cbf has the same code as release candidate 4feef0f (from the work order; not checked again). The incremental build had nothing to recompile (no compile step appeared in the output).
RISKS: The 4 s and 2 s durations are for a warm incremental build. A fresh clone or a run after `sbt clean` compiles first and takes much longer. Without -v the console shows no per-test lines; use the -v form if the video must show the test name.
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: orchestrator / human: use these commands for the demo video recording.

Requirement-to-test coverage: category 14 (unsupported answer claims, end to end): 7/7 tests pass; the semantic-context-cited-for-a-number case passes in both the full-suite run and the single-test run.
Failures by owning agent: none.
