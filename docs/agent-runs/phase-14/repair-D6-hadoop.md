# Repair G-17, hadoop-engineer report

(Saved verbatim in substance by the orchestrator from hadoop-engineer's final message.)

STATUS: BLOCKED-ON (test-engineer: three contract tests assert the old defaults)
OWNER: hadoop-engineer
WORK ORDER: docs/agent-runs/phase-14/prompt-s3.md (sha256 c0bd687e92015df11475ce159307b1c78f8955175cbcf9b86a9e0fe6540fe83e), session 3, repair G-17

FILES MODIFIED:
- modules/hadoop/src/main/resources/scholarchain-hadoop-defaults.xml (maxBlockSize 1000 -> 200, fallback.maxBlockSize 500 -> 200, skipSameNumberedName false -> true; descriptions cite docs/agent-runs/phase-05/agent-report-hadoop.md:119 and er-v2; stale numbers fixed)
- modules/hadoop/src/main/scala/org/scholarchain/hadoop/blocking/BlockingJob.scala (doc comment only)
- modules/hadoop/src/main/scala/org/scholarchain/hadoop/candidates/CandidateFeatureJob.scala (doc comment only)
- modules/hadoop/src/test/scala/org/scholarchain/hadoop/SettingsSuite.scala (new G-17 feature test: 200, 200, true, maxPairsPerBlock 125000, keyFamilies, secondaryKeys, BlockLimit effective 200 without pairBound)
- modules/hadoop/src/test/scala/org/scholarchain/hadoop/FallbackBlockingSuite.scala (:188-196 defaults compose to 200)
- modules/hadoop/src/test/scala/org/scholarchain/hadoop/CandidateSuite.scala (:429 listing 200; "J06 local run" test pinned to skip=false via SkipOff)

COMMANDS RUN / TEST RESULTS:
- sbt -batch "hadoop/testOnly org.scholarchain.hadoop.*": Total 113, Failed 1 (CandidateSuite:305, then fixed)
- sbt -batch "hadoop/testOnly org.scholarchain.hadoop.CandidateSuite org.scholarchain.hadoop.SettingsSuite org.scholarchain.hadoop.FallbackBlockingSuite": Total 34, Failed 0, Passed 34
- sbt -batch "contractTests/testOnly org.scholarchain.contracts.hadoop.*": Total 105, Failed 3, Passed 102
- Not run: sbt check, packageCheck (blocked on the three contract tests; packageCheck exists, build.sbt:491-527)

Contract tests (test-engineer) failing with the new defaults:
1. tests/contracts/src/test/scala/org/scholarchain/contracts/hadoop/JobLevelBlockingSuite.scala:183, `assertEquals(o("maxBlockSize").num.toLong, 1000L)`: got 200. Fix: expect 200 (effective limit 11 at :182 still holds).
2. tests/contracts/src/test/scala/org/scholarchain/contracts/hadoop/ScaleAndProvenanceAdversarialSuite.scala:219, `assert(PairIndex.pairCount(maxBlock) > maxPairs)`: 19,900 is not > 125,000; premise at :214 ("Default maxBlockSize 1000 gives 499500 pairs") now false. :220-223 do not depend on the default.
3. tests/contracts/src/test/scala/org/scholarchain/contracts/hadoop/FallbackPipelineEndToEndSuite.scala:158, `assertEquals(emittedOff.map(_.blockFamily).sorted, List("dblp-base-name","initials-surname"))`: got Nil, since "off" run now uses the default (on); :176 `assertNotEquals(j06.runId, p.candidates.runId)` would also fail. Fix: add CandidateSkipSameNumberedName -> "false" to the base settings at :58-64.

VALIDATION RESULTS:
- maxPairsPerBlock stays 125000: BlockLimit.of(s, key) (BlockingJob.scala:168-184) returns memberCap when pairs(memberCap) <= maxPairs; J05 at BlockingJob.scala:422, J05b at FallbackJobs.scala:213; pairs(200) = 19,900 <= 125,000, so the effective cap is 200 for both; Phase 5 also left it at default.
- Dependencies outside modules/hadoop: scripts/README.md:80, :106 (no blocking defaults mentioned; no change). EvalReport.scala:39-45, :283-286, EvalReportSuite.scala:81-82 (entity-resolution-engineer) read blocking settings from LocalPipelineRunner run-report.json, which records only -D overrides (LocalPipelineRunner.scala:256): local runs without -D now show null (minor; EMR unaffected). ER contract tests (EvalReportAdversarialSuite:124-125, EvalReportRound3AdversarialSuite:1136-1137) set the keys themselves: unaffected. spark, storage, rag, services, core, README.md, docs/*.md: no hits.
- Reuse: Settings.fingerprint covers all scholarchain.* keys except run.*, path.* and three operational keys, across the whole configuration (Settings.scala:139-156), and enters every job's run ID (JobRunner.scala:85-90); new build commit also changes code.version. So every job (J01-J09) gets a new run ID: full Hadoop re-run, nothing reused.

RISKS: reproducing 16,823,178 depends on all other inputs equal (likely; works, mentions, teams, pairs matched). EvalReport nulls for local runs without -D (minor).

INTERFACE HANDOFF: new defaults maxBlockSize 200, fallback.maxBlockSize 200, skipSameNumberedName true; maxPairsPerBlock 125000 unchanged; no API, record or option name changed; run IDs and output folders change; old behaviour needs explicit -D (1000, 500, false).

Proposed later: LocalPipelineRunner records effective blocking settings, not only -D overrides.

files changed after these runs: none
