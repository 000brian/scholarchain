# Phase 11 repair round 2, step 2: R2-03 (er-v2), entity-resolution-engineer

STATUS: COMPLETE
OWNER: entity-resolution-engineer
WORK ORDER: Phase 11 repair round 2, step 2, R2-03 (er-v2). Base commit 29661e9. Human decision: the ER policy version becomes `er-v2`, because the merge behaviour changed in Phase 11 (finding A-01) under the label er-v1.
FILES CREATED:
- /Users/brian/Developer/repos/scholarchain/modules/entity-resolution/src/main/resources/er/policy/er-v2.json
FILES MODIFIED:
- /Users/brian/Developer/repos/scholarchain/modules/entity-resolution/src/main/resources/er/policy/er-v1.json (reverted to its frozen Phase 5 bytes, see below)
- /Users/brian/Developer/repos/scholarchain/modules/entity-resolution/src/main/scala/org/scholarchain/er/policy/Policy.scala
- /Users/brian/Developer/repos/scholarchain/modules/entity-resolution/src/main/scala/org/scholarchain/er/run/ErMain.scala
- /Users/brian/Developer/repos/scholarchain/modules/entity-resolution/src/main/scala/org/scholarchain/er/policy/Decide.scala (doc comment only)
- /Users/brian/Developer/repos/scholarchain/modules/entity-resolution/src/main/scala/org/scholarchain/er/run/Report.scala (two description strings: "er-v1" -> "the match policy")
- /Users/brian/Developer/repos/scholarchain/modules/entity-resolution/src/main/scala/org/scholarchain/er/eval/Harness.scala (doc comment and one description string: "er-v1" -> "the match policy")
- /Users/brian/Developer/repos/scholarchain/modules/entity-resolution/src/test/scala/org/scholarchain/er/PolicySuite.scala
- /Users/brian/Developer/repos/scholarchain/modules/entity-resolution/src/test/scala/org/scholarchain/er/BoundarySuite.scala
- /Users/brian/Developer/repos/scholarchain/modules/entity-resolution/src/test/scala/org/scholarchain/er/EvalReportSuite.scala
- /Users/brian/Developer/repos/scholarchain/modules/entity-resolution/src/test/scala/org/scholarchain/er/RunSuite.scala
COMMANDS RUN:
- read-only grep / sed -n / git log / git show / git diff --no-index / shasum (search, history of the policy file, byte check of er-v1.json)
- sbt -batch "entityResolution/testOnly <17 ER suites>" > .../phase11-reports/round2-er-targeted.log (one invocation, foreground)
TEST RESULTS: entityResolution, 17 suites, 139 tests: Passed 139, Failed 0, Errors 0 (EXIT=0). The new test "R2-03: the A-01 merge behaviour runs under er-v2; er-v1 stays the frozen Phase 5 file" ran and passed (PolicySuite report, timestamp 2026-11-16T23:59:28).
VALIDATION RESULTS:
- `git diff --no-index er-v1.json(HEAD content) er-v2.json`: 2 lines differ (policyVersion, description); weights, thresholds, rules and all other text byte-identical.
- `shasum -a 256 er-v1.json` = 4946b3504759d5f9a61f60646e6ca9d6cd27b0dd6ef26e57a1729c2fb32807ac = `git show 2297990:.../er-v1.json | shasum -a 256` = the policy sha256 recorded in the Phase 5 manifests and reports.
- Golden files: none changed (the ER module has no test resources; no wire fixture holds the ER policy version). Test files changed: 4 (under the stop-rule limit).
- Gold-set evaluation: not run; this is a label change with no change to scoring, thresholds or merge code, and the work order does not ask for it.
ASSUMPTIONS:
- "Change the version label where the ER policy defines it" means the policy file, since `policyVersion` is read from it. A new file `er-v2.json` is the definition; the default resource now points to it.
- I reverted `er-v1.json` to its Phase 5 bytes instead of leaving it with Phase 11 content. That two-line revert keeps the label honest. Under its HEAD content, `er-v1.json` was the R2-03 defect itself: a file labelled er-v1 with er-v2 behaviour. It is now byte-identical to the policy that every er-v1 run manifest records. So old outputs, including the Phase 7 export, still match their recorded policy sha256, and `EvalMain --policy er-v1.json` reproduces their run context. No run loads it. `Policy.parse` refuses it because the code implements A-01's rule, and a test pins that refusal.
- The accessor `Policy.erV1` is kept, as an alias of `Policy.current`, so that `tests/contracts` (owner test-engineer) compiles unchanged. Renaming it would touch 6 contract test files outside my territory.
RISKS:
- `Policy.erV1` now returns the er-v2 policy, so the name is misleading. Its doc comment says so. Renaming it is on the proposed-later list for test-engineer.
- Contract tests were not run in this step (not my project). By reading them, none asserts the literal "er-v1" against ER output: `IdentityAdversarialSuite:239` compares with `Policy.erV1.policyVersion`, and `LlmAdversarialSuite:388` overrides to "er-v1-synthetic-override", which still differs from "er-v2". The other "er-v1" literals are self-consistent fixture data. The wave closer's `sbt check` covers them.
- The run ID of a new ER run changes, because the config fingerprint holds the policy version and sha256. The sha256 had already changed in Phase 11, so a new run ID was coming anyway. LLM cache entries made under er-v1 no longer hit, by design (law 3).
MANUAL ACTION REQUIRED: none required. Optional: the human decides whether the routed comment and docs lines below are worth a work order.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: wave closer `sbt check` (includes contractTests); code-reviewer review of this repair; human ratification of the er-v1.json revert (see ASSUMPTIONS).

## Policy version

- Current: `er-v2` (file `er/policy/er-v2.json`, `Policy.DefaultResource`, `Policy.current`). The weights, thresholds, scoring and pair rules are those of er-v1. The merge behaviour has two changes, both from finding A-01: the implied cannot-link in `cluster.guard.rejected-pair` (reason `merge-refused:given-name-conflict`) and the assignment rule `cluster.flag.given-name-conflict`.
- Historical: `er-v1` (file `er/policy/er-v1.json`, `Policy.ErV1Resource`), frozen Phase 5 bytes, sha256 `Policy.ErV1Sha256` = 4946b350…07ac. Outputs produced under er-v1, including the Phase 7 export run=51b50d…, keep the label er-v1 and were not touched.

## Thresholds

Unchanged: accept 0.9, reject 0.3, llmBandLower 0.6, llmMinConfidence 0.8, nearThresholdLogit 0.5, priorLogOdds -1.0.

## Confusion matrices and error analysis

Not recomputed. Scoring and thresholds did not change, and the merge code is the code already in 29661e9; only the label changed. Unresolved rate and metrics therefore match the Phase 11 state of the code.

## Is the policy version an input to any stable ID or hash?

Yes, to two, both by design. No stable-ID rule was changed.
- `LlmAdjudicationId` (core, `StableIds.scala:261-284`): digest of (inputHash, promptVersion, modelVersion, policyVersion). This is the law-3 cache key, so a new policy version means a new adjudication ID and a cache miss.
- ER `RunId` (`ErRun.scala:179-181`): `RunId.of(JobName, codeVersion, inputFingerprint, configFingerprint)`. The config JSON holds `policyVersion` and `policySha256`.
- Not an input to `ResearcherId` (anchor mention only), `MentionId`, `MatchCandidateId` (doc comment: "The run, policy, and feature values are not inputs"), `WorkId`, `EdgeId`, `LinkId`, `EvidenceId` or `ChunkId`. Researcher IDs and candidate IDs (gold-set references) are therefore stable across the bump.

## Routed lines

| file:line | owner | current text | proposed text | one-line? |
|---|---|---|---|---|
| modules/hadoop/src/main/resources/scholarchain-hadoop-defaults.xml:273 | hadoop-engineer | `the ones the er-v1 match policy reasons about (Phase 11, Phase 5 m5); set dblp-base-name to` | `the ones the ER match policy (er-v2) reasons about (Phase 11, Phase 5 m5); set dblp-base-name to` | yes (description text only, no behaviour) |
| tests/contracts/src/test/scala/org/scholarchain/contracts/er/LlmAdversarialSuite.scala:70 | test-engineer | `test("precondition: every synthetic pair is in the LLM band of er-v1") {` | `test("precondition: every synthetic pair is in the LLM band of the current policy") {` | yes (test name only; optional) |
| tests/contracts/src/test/scala/org/scholarchain/contracts/er/GoldAndHarnessAdversarialSuite.scala:260 | test-engineer | `test("threshold sweep re-thresholds scored rows only and never changes er-v1") {` | `test("threshold sweep re-thresholds scored rows only and never changes the policy") {` | yes (test name only; optional) |
| tests/contracts/.../er/*.scala (LlmAdversarialSuite 76, 338, 388; ErRepairRegressionSuite 469; ErSynth 230; CannotLinkAdversarialSuite 152; IdentityAdversarialSuite 239, 649, 650, 652) | test-engineer | `Policy.erV1` | `Policy.current` | yes per line, but 6 files; optional (the alias keeps them compiling) |

No change is needed in core, spark, storage, rag, services or config. Their main code holds no ER policy label. Their "er-v1" strings are self-contained test fixture data (`StableIdsSuite`; spark `FixtureData`, `KnownGraphInputs`, `PipelineFixtureSuite`), so they are not pins of the ER policy and stay valid. Docs outside `docs/agent-runs/` hold no "er-v1".

## Proposed later

- test-engineer: rename `Policy.erV1` to `Policy.current` in tests/contracts, then entity-resolution-engineer can delete the `erV1` alias.

## TEST EVIDENCE

- command: `sbt -batch "entityResolution/testOnly org.scholarchain.er.PolicySuite org.scholarchain.er.BoundarySuite org.scholarchain.er.EvalReportSuite org.scholarchain.er.RunSuite org.scholarchain.er.DecisionSuite org.scholarchain.er.GuardPathSuite org.scholarchain.er.GivenNameGuardSuite org.scholarchain.er.PriorSpellingSuite org.scholarchain.er.LlmSuite org.scholarchain.er.CannotLinkRunSuite org.scholarchain.er.ClusteringSuite org.scholarchain.er.EvalRepairRound2Suite org.scholarchain.er.EvalSuite org.scholarchain.er.HarnessDesignSuite org.scholarchain.er.NormalizeSuite org.scholarchain.er.ReviewKeyRepairSuite org.scholarchain.er.WorkResolutionSuite"` (log: /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/a8a39918-edf3-4540-902c-e41a9d1554c2/scratchpad/phase11-reports/round2-er-targeted.log)
- finished: 2026-11-16 23:59:29 CST
- per suite:
  - PolicySuite run=9 passed=9 failed=0 skipped=0
  - BoundarySuite run=5 passed=5 failed=0 skipped=0
  - EvalReportSuite run=14 passed=14 failed=0 skipped=0
  - RunSuite run=14 passed=14 failed=0 skipped=0
  - DecisionSuite run=10 passed=10 failed=0 skipped=0
  - GuardPathSuite run=3 passed=3 failed=0 skipped=0
  - GivenNameGuardSuite run=4 passed=4 failed=0 skipped=0
  - PriorSpellingSuite run=5 passed=5 failed=0 skipped=0
  - LlmSuite run=12 passed=12 failed=0 skipped=0
  - CannotLinkRunSuite run=4 passed=4 failed=0 skipped=0
  - ClusteringSuite run=13 passed=13 failed=0 skipped=0
  - EvalRepairRound2Suite run=16 passed=16 failed=0 skipped=0
  - EvalSuite run=11 passed=11 failed=0 skipped=0
  - HarnessDesignSuite run=5 passed=5 failed=0 skipped=0
  - NormalizeSuite run=7 passed=7 failed=0 skipped=0
  - ReviewKeyRepairSuite run=4 passed=4 failed=0 skipped=0
  - WorkResolutionSuite run=3 passed=3 failed=0 skipped=0
- result line: `[info] Passed: Total 139, Failed 0, Errors 0, Passed 139`
- expected-failure flips: none
- source changed after this run: no

## INTERFACE HANDOFF

- Files changed: as listed in FILES CREATED and FILES MODIFIED (all under modules/entity-resolution/**).
- Public signatures (object `org.scholarchain.er.policy.Policy`):
  - `val DefaultResource = "er/policy/er-v2.json"` (was er-v1.json)
  - new `lazy val current: Policy` (the policy every run uses; policyVersion "er-v2")
  - `def erV1: Policy = current` (was `lazy val erV1`, loading er-v1.json; now a legacy alias, source compatible)
  - new `val ErV1Resource = "er/policy/er-v1.json"`, `val ErV1Sha256 = "4946b3504759d5f9a61f60646e6ca9d6cd27b0dd6ef26e57a1729c2fb32807ac"`
  - `ErMain.config` uses `Policy.current`. With no override, decisions, manifests and eval run contexts now carry `policyVersion = "er-v2"`.
- Fixtures: none new. Tests that want the current policy should use `Policy.current`.
- Thread-safety: unchanged (`current` is an immutable lazy val; `erV1` returns the same instance).
- Limits: `er-v1.json` is kept as a record and does not load under the current code (`Policy.parse` returns Left: rules do not match the implemented rules). Re-running er-v1 is not supported; checking out the Phase 5-10 code is the way to reproduce it.
