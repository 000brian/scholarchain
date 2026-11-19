# Repair G-17, test-engineer report (wave closer)

(Saved by the orchestrator from test-engineer's final message.)

STATUS: COMPLETE
OWNER: test-engineer
WORK ORDER: docs/agent-runs/phase-14/prompt-s3.md (sha256 c0bd687e92015df11475ce159307b1c78f8955175cbcf9b86a9e0fe6540fe83e), session 3, repair G-17, wave closer

FILES MODIFIED:
- tests/contracts/src/test/scala/org/scholarchain/contracts/hadoop/JobLevelBlockingSuite.scala (:183 now reads the cap from DefaultsXml, asserts 200 and that the listing's maxBlockSize equals it)
- tests/contracts/src/test/scala/org/scholarchain/contracts/hadoop/ScaleAndProvenanceAdversarialSuite.scala (old default-dependent hot-block test rewritten with explicit maxBlockSize 1000: pairCount(1000) > 125000, effective 500, pairBound, reasons for 501 and 1001; new test: both default member caps 200, 19,900 pairs within 125000, not pair-bound; import BlockLimit)
- tests/contracts/src/test/scala/org/scholarchain/contracts/hadoop/FallbackPipelineEndToEndSuite.scala (base settings pin CandidateSkipSameNumberedName -> "false"; base run skipped 0 pairs asserted; "on" config replaces the key and is asserted "true"; :176 meaningful again)

COMMANDS RUN / TEST RESULTS:
- sbt -batch "contractTests/testOnly org.scholarchain.contracts.hadoop.*": Passed: Total 106, Failed 0, Errors 0 (was 105 with 3 failed; one test added)
- sbt -batch check (exit 0): Module tests suites=127 executed=2076 passed=2076 failed=0 errors=0 skipped=3; Contract tests (no AWS) suites=34 executed=399 passed=399 failed=0; .claude/.last-test-run updated; Total time 1202 s. Orchestrator confirmed lines 9886, 10537, 10539 of scratchpad/g17-check.log.
- sbt -batch packageCheck (exit 0): "packageCheck: ok" (orchestrator confirmed scratchpad/g17-package.log:24-25); built spark, hadoop, entity-resolution, services jars.

Other tests checked for old defaults: PairLimitAdversarialSuite (explicit values, :161-170), ScaleAndProvenance config test (:256+, positivity only), EvalReportAdversarialSuite:124-125, EvalReportRound3AdversarialSuite:1136-1137, ErSynth and er/* suites (no Settings), JobLevelBlockingSuite:54 and PairLimitAdversarialSuite:135 (one numbered mention only): unaffected. Other hadoop contract suites (JobLevelRerun, JobLevelTeamScale, JobLevelDblpAdversarial, PairLimitAdversarial) pass; no same-numbered pair in their fixtures.

RISKS: FallbackPipelineEndToEndSuite run IDs change (no test pins them).
BLOCKED-ON OWNER: none
NEXT GATE: code-reviewer review of the G-17 wave, then the human gate.

files changed after these runs: none
