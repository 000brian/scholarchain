# agent-report-er (Phase 16, session 2, step 2, entity-resolution-engineer)

STATUS: COMPLETE
OWNER: entity-resolution-engineer
WORK ORDER: docs/agent-runs/phase-16/prompt.md (sha256 c75cc5f895faed13880a69ec285231c5498fd50924c7b875fe7e02cce1707dc8, re-checked with shasum), decision 9 (items N-1, N-2, M3, M4 with F8/F9, group 1 run plan; tier 2 G-18), decision 2 status lines A-02 and A-05. HEAD 9da45ab616a764e449f74f25feca85ed77d6dfe4 plus uncommitted session 2 work. Wave closer: no.

FILES CREATED:
- /Users/brian/Developer/repos/scholarchain/modules/entity-resolution/src/test/scala/org/scholarchain/er/Phase16RepairSuite.scala (8 feature tests: N-1, N-2, M3, M4 x2, F9, G-18 x2)

FILES MODIFIED (all in modules/entity-resolution/**):
- src/main/scala/org/scholarchain/er/policy/Policy.scala (N-1 refusal, N-2 deprecated forwarder, G-18 record)
- src/main/scala/org/scholarchain/er/eval/IntervalReliability.scala (M4)
- src/main/scala/org/scholarchain/er/eval/Harness.scala (M3 cell/line text, M4 label and paragraph)
- src/main/scala/org/scholarchain/er/eval/EvalReport.scala (F9: whole-count rule in allSentAccounted)
- src/main/scala/org/scholarchain/er/run/Report.scala (G-18: run report and manifest carry policyEvaluatedWith)
- src/test/scala/org/scholarchain/er/{DecisionSuite,GivenNameGuardSuite,GuardPathSuite,LlmSuite,PriorSpellingSuite,RunSuite}.scala (Policy.erV1 -> Policy.current)
- src/test/scala/org/scholarchain/er/PolicySuite.scala (removed the assertion that used the old accessor)
- src/test/scala/org/scholarchain/er/EvalRepairRound2Suite.scala (3 assertions updated to M3's new text "(unreliable; zero-event bound [")
- Unchanged: er-v1.json (sha256 4946b350…07ac, = Phase 5) and er-v2.json (58fd7f31…e917; not in the diff).

COMMANDS RUN:
- shasum -a 256 (prompt, gold file, policy files, Phase 14 queue/key/run-report/manifest); ls -la / ls -d of the Phase 14 ER folder; head -n 1 and wc -l of the gold CSV; read-only git (status, diff --stat, log -1); grep over repository paths.
- sbt -batch "entityResolution/testOnly <18 suites>" (run 1: 2 failures, old assertions on "(unreliable)" text; fixed the assertions).
- Mutation check: production changes reverted by hand (Edit), `sbt -batch "entityResolution/testOnly org.scholarchain.er.Phase16RepairSuite"`: 6 failed of 8; mutations then restored and confirmed absent (grep MUTATION: no match).
- sbt -batch "entityResolution/testOnly <18 suites>" (run 2, final): exit 0.
- sbt -batch "contractTests/testOnly org.scholarchain.contracts.er.*" (direct users; run, not edited): exit 1, exactly the 2 expected .fail tests F8 and F9 (see TEST RESULTS).
- No runMain, no sbt clean, no full sbt check (not wave closer), no network, no script.

TEST RESULTS:
- Final module run: `Passed: Total 147, Failed 0, Errors 0, Passed 147` (PolicySuite 9, WorkResolutionSuite 3, DecisionSuite 10, BoundarySuite 5, GuardPathSuite 3, ReviewKeyRepairSuite 4, EvalSuite 11, GivenNameGuardSuite 4, LlmSuite 12, PriorSpellingSuite 5, HarnessDesignSuite 5, Phase16RepairSuite 8, CannotLinkRunSuite 4, EvalReportSuite 14, EvalRepairRound2Suite 16, RunSuite 14, ClusteringSuite 13, NormalizeSuite 7).
- Mutation run (each fix reverted): Phase16RepairSuite 6 failed of 8: N-1, M3, M4 (both), F9, G-18 run/manifest. The 2 that pass under mutation fail at HEAD by construction: N-2 (the source scan; at HEAD 6 module test files contained `Policy.erV1`, grep at start) and the G-18 policy-record test (`Policy.evaluatedWith` does not exist at HEAD, so it does not compile).
- Contract ER suites (test-engineer's tree, only run): `Failed: Total 231, Failed 2, Errors 0, Passed 229`. The 2 failures are the MUnit expected-failure tests in EvalReportRound3AdversarialSuite: "ACCEPTED RISK M4 (F8) ..." (fails at :324-325, i.e. its assertions now pass) and "ACCEPTED RISK M2 (F9) ..." (fails at :1011-1012, assertions now pass). Every other ER contract suite: 0 failed. Compiling contracts printed 12 deprecation warnings for `Policy.erV1` (expected; not fatal, build has no -Werror).

VALIDATION RESULTS:
- No gold label read for matching, invented or changed; gold sha256 fc39c15f…a71fa9 (= Phase 5). No decision compared by hand. No label leakage: BoundarySuite 5/5.
- No threshold, weight, rule or policy file changed. Policy version in force: er-v2 (thresholds accept 0.9, reject 0.3, LLM band [0.6, 0.9), llmMinConfidence 0.8, nearThresholdLogit 0.5: PolicySuite asserts them).
- No metric was computed in this step (no EvalMain run: no runMain, and the inputs are gone; see group 1 plan).

ASSUMPTIONS:
- F9 is Phase 5 reviewer M2 (test-report-02.md:8 "F9 is the same as reviewer M2"), not M3. The work order asks that F8 and F9 pass, so I fixed only the part of M2 that F9 asserts (a fractional outcome count is not a whole count, so it cannot make an absent key 0; EvalReport.scala:148-158). M2's other part (outcome counts include cache hits, pairsSentToAdjudicator excludes them) is NOT changed and stays a Phase 17 / pre-live-LLM item. The orchestrator may treat this narrow F9 fix as an unplanned scope extension; it is one condition, revertable, and its test is "F9: ..." in Phase16RepairSuite.
- M4 rule chosen: "design-determined" only when every stratum's labeled memberships all share the stratum's outcome; "partly design-fixed" when some but not all do; neither when none do (F8's extreme case). Only wholly design-determined suppresses the unreliable flag (as before); a partly fixed rate keeps the zero-event check and flag.
- N-2: renamed to the existing `Policy.current`; `Policy.erV1` stays as a `@deprecated` forwarder so tests/contracts compiles (removing it would break `contractTests` compilation before test-engineer's step 6).
- G-18: the record is outside the manifest's `config` object, so the configuration fingerprint and run IDs are unchanged.

RISKS:
1. **`sbt check` is red until step 6:** F8 and F9 now fail as MUnit `.fail` tests (their assertions pass). test-engineer must remove `.fail` and the "ACCEPTED RISK … expected to fail until repaired: " prefixes in step 6 (decision 10) before step 7's full check. Steps 3 to 5 do not run contract tests, so they are not affected.
2. M2's cached-pairs mismatch remains (see ASSUMPTIONS); still latent (only always-abstain exists).
3. Group 1 cannot be re-rendered: the M3/M4 text changes are "fixed in code, tested offline, not validated live" and the Phase 5 summary.md stays in its old wording.
4. The real Phase 5 unresolved rate (16.9% of weight estimated, review-report-02 M4) would now be labeled "partly design-fixed"; this is an inference from the code, not a re-run.

MANUAL ACTION REQUIRED: none for this step. Session 3 E4 uses the reduced plan (below), not an EvalMain run.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: test-engineer step 6 (remove the F8 and F9 `.fail` marks; optionally move the contract users of `Policy.erV1` to `Policy.current`), then step 7 full `sbt check`; code-reviewer R-1a reviews this step.

---

## Fix status (each)

- N-1: fixed in code, tested offline, not validated live.
- N-2: fixed in code, tested offline, not validated live (module users renamed; deprecated forwarder kept for tests/contracts).
- M3: fixed in code, tested offline, not validated live.
- M4 (closes F8's assertion): fixed in code, tested offline, not validated live.
- F9 (the fractional-count part of M2): fixed in code, tested offline, not validated live.
- G-18 (tier 2): fixed in code, tested offline, not validated live.
- Runtime lines: ErMain (N-1 refusal) `runtime: untested`; EvalMain (M3, M4, F9 output text) `runtime: untested` (no run possible, inputs gone); ErRun run report and manifest (G-18) `runtime: untested` (fixture runs in RunFixture only).

## Items

### N-1 (tier 1): done
- Policy.scala `withThresholds` refuses any version in `Policy.FrozenVersions = Set("er-v1", "er-v2")` (Policy.scala:128), before the pattern check; ErMain passes the error on as `ErError` (ErMain.scala:106, unchanged).
- Refusal message (Policy.scala:167-169), exact text for `--policy-version er-v1 --accept-threshold …`:
  `--policy-version 'er-v1' names a frozen policy file; this code applies er-v2, so its output must not carry the label 'er-v1'; choose a new version`
- `--policy-version` without a threshold override is still refused as before ("--policy-version is only allowed together with a threshold override", ErMain.scala:91). New labels (for example `er-v1-synthetic-override`, used by tests/contracts LlmAdversarialSuite:388) are still accepted.
- Test: Phase16RepairSuite "N-1: --policy-version er-v1 is refused while the code applies er-v2" (fails under mutation).

### N-2 (tier 1): done
- `Policy.erV1` renamed to the existing `Policy.current` (er-v2). Every user in modules/entity-resolution moved (DecisionSuite:9, RunSuite:86, GivenNameGuardSuite:167, GuardPathSuite (6 uses), LlmSuite:19, PriorSpellingSuite:63; PolicySuite's `erV1 eq current` assertion removed). No main-source user existed (ErMain uses `Policy.current`, ErMain.scala:83).
- `Policy.erV1` is kept only as `@deprecated("returns er-v2, not er-v1; use Policy.current", "phase-16")` (Policy.scala:189-196) so tests/contracts compiles.
- **Users outside my territory, for test-engineer (owner of tests/**):** tests/contracts/src/test/scala/org/scholarchain/contracts/er/CannotLinkAdversarialSuite.scala:152; LlmAdversarialSuite.scala:76, :338, :388 (twice); ErSynth.scala:230; ErRepairRegressionSuite.scala:469; GoldAndHarnessAdversarialSuite.scala:269; IdentityAdversarialSuite.scala:239, :649, :650, :652. Replace with `Policy.current`; then the forwarder can be removed (Phase 17, entity-resolution-engineer). No user outside modules/entity-resolution and tests/contracts.
- Test: Phase16RepairSuite "N-2: the module's sources use Policy.current, never the old er-v1 accessor name" (scans modules/entity-resolution/src; fails at HEAD).

### M3 (tier 1): done
- Location at HEAD: Harness.scala `mark` (was :715-722, row text :725; re-checked, same code as Phase 5's :725). Now Harness.scala:715-738: an unreliable (or partly design-fixed) interval prints `(unreliable; zero-event bound [lo%, hi%])` in the table cell of "Weighted estimate (bootstrap)" and "Cluster level, weighted estimate (bootstrap)", and after each entry of the "Weighted bootstrap 95%" line (precision, recall, f1, coverage when flagged). The bare "(unreliable)" no longer appears. Phase 5's real row would read `100.0% [100.0%, 100.0%] (unreliable; zero-event bound [82.9%, 100.0%])` (inference from repair-09.md:690-695; not re-rendered).
- Test: Phase16RepairSuite "M3: the weighted row and the bootstrap line show the zero-event bound next to the flag" (fails under mutation). EvalRepairRound2Suite :136, :138, :161 updated to the new text.

### M4 (tier 1): done (F8's assertions now pass)
- Location at HEAD: IntervalReliability.scala:227-228 (same as Phase 5). Now IntervalReliability.scala:243-251: `designDetermined` requires every stratum's share to be fixed (`StratumResult.fixedByDesign`, set from the existing per-stratum check, :161); new `partlyDesignFixed` and `designFixedShareOfPopulation`; `PartlyDesignFixedNote` (:92-104). `unreliable` is suppressed only when wholly design-determined.
- Markdown: "(design-determined)" only when wholly; otherwise "(partly design-fixed[, unreliable]; zero-event bound [..])" on the bootstrap line, and the reliability paragraph starts with the partly-fixed note and the fixed share.
- Tests: Phase16RepairSuite "M4: a rate with some m3 memberships is partly design-fixed, not design-determined" and "M4: the report labels a partly design-fixed unresolved rate so, with its bound" (both fail under mutation).

### F8 and F9 for test-engineer (tier 1)
- Contract tests that should now pass, so their `.fail` marks and "ACCEPTED RISK … expected to fail until repaired: " prefixes come off (observed: both now fail as expected-failure tests, i.e. their assertions pass):
  - F8: tests/contracts/src/test/scala/org/scholarchain/contracts/er/EvalReportRound3AdversarialSuite.scala:303-325, "ACCEPTED RISK M4 (F8), expected to fail until repaired: FINDING repair-21: no labeled membership agrees with its stratum: not design-determined".
  - F9: same file :996-1012, "ACCEPTED RISK M2 (F9), expected to fail until repaired: FINDING repair-29: a fractional outcome count is not valid, so an absent key is not 0".
- Note: F9 is reviewer M2, not M3; only its fractional-count part is fixed (EvalReport.scala:148-158).

### G-18 (tier 2, kept by the forecast): done
- `Policy.EvaluatedBlocking` (Policy.scala:136-140): `scholarchain.blocking.maxBlockSize` 200, `scholarchain.blocking.fallback.maxBlockSize` 200, `scholarchain.candidates.skipSameNumberedName` true (Phase 5's settings, the Hadoop defaults since Phase 14 G-17). `Policy.evaluatedWith(policy)` (Policy.scala:145-163) gives `{goldEvaluatedPolicyVersion: "er-v1", blocking: {…}, note}` for er-v1/er-v2, and `blocking: null` with a "never evaluated" note for a threshold override.
- Written as `policyEvaluatedWith` in run-report.json (Report.scala:58) and at the top level of _manifest.json (Report.scala:203; not inside `config`, so fingerprints and run IDs are unchanged). The note says ER does not check the input's blocking settings (that input guard remains proposed later).
- Tests: Phase16RepairSuite "G-18: the policy record names Phase 5's blocking caps; an override names none" and "G-18: run report and manifest carry the record; the configuration fingerprint does not".

### Group 1 run plan (tier 1): done, REDUCED PLAN
Start-check facts (orchestrator) plus my checks:
- Phase 5 ER run folder `modules/entity-resolution/target/phase-05-real/attempt-02/run=9f96c70b5115294dcc9d3b2d1d4438b9` is gone, so Phase 5's review-queue.jsonl (sha256 25a43e0e…84e9), review-key.jsonl (89a1da5d…9e04) and every er-v1 decision file are gone. Phase 5's review queue and key do NOT exist locally.
- The Phase 14 er-v2 folder exists: `/var/folders/wl/53f4z40d0qg430w25fh2xv900000gn/T/scholarchain-er-20261119T020635Z-f159535e498a-5bd1/er-out/run=23b913cc4dab1155d7a75c505eff5d56` (ls). Its queue sha256 3277b051…c802 and key d757818d…2e11 (shasum, = evidence/runs/20261119T020635Z-f159535e498a-5bd1-er.json:153-172) differ from Phase 5's.

Can Phase 5's gold labels be joined to the Phase 14 queue/key? **No (invalid):**
1. The gold CSV has only `leftMentionId,rightMentionId,candidateId,label,evidence,note,labeler,labeledAt` (head -n 1). EvalMain's strict reader `GoldLabels.read` keys by unordered mention pair and rejects the whole file if any gold pair is not in the given queue or its candidateId differs (GoldLabels.scala:107-117; EvalMain.scala:64-70: "gold labels rejected").
2. The queue is a stratified sample whose strata include the decision outcome (`path=…;outcome=…;family=…;band=…`, ReviewQueue.scala:86, :140), drawn within each stratum by SHA-256(seed, candidate ID) (ReviewQueue.scala:89-103). er-v2 changed cluster outcomes (finding A-01), so pairs move between strata; the different queue sha256 shows the sample differs. Nothing guarantees that all 300 gold pairs are in the Phase 14 queue (I did not compare the files, as instructed).
3. Even for pairs present in both, the weights come from the key's design (population_h / labeled_h, Harness.scala:279, :340): the Phase 14 key describes a different sample than the one the human labeled, so weighted precision/recall/coverage would be invalid, and the stratum allocation of the gold rows would be mis-stated.

Can they be joined to the Phase 14 mention_pair decisions by pair identity? Technically the IDs could join for candidate pairs (gold rows carry stable `mention:v1:` and `match-candidate:v1:` IDs; Phase 14's Hadoop counters matched Phase 5's, runbook-log D6 attempt 4), and the 30 dblp-prior gold rows would need the identity_assignment file instead. But no code does this join for evaluation (EvalMain reads only queue + key), and decision 3 claims er-v2 accuracy only if the gold pairs' er-v1 and er-v2 decisions are compared: **the er-v1 decision set is gone, so `--compare-decisions` cannot run** (only one of the two sets exists). Under deviation 5 the gold metrics stay Phase 5's labels on er-v1 decisions.

**E4 plan (reduced, decision 3):** no EvalMain command is given. Group 1 uses the committed Phase 5 report, docs/agent-runs/phase-05/agent-report-er-eval.md (final summary.md, sha256 9109798d…78f7, and metrics.json sha256 ee15b523…29b7 named in it, Part 2), read by `evaluate.py er` in its reduced path, labeled: "Phase 5 run 9f96c70b…, policy er-v1, gold labels fc39c15f…; M3 and M4 fixed in code (Phase 16) but not re-rendered; er-v2 accuracy not measurable from the frozen evidence (er-v1 decisions and the Phase 5 queue/key are gone; equal counts are not equal decisions)".
- Decision-file paths and formats for both sets: **not given, because the er-v1 set does not exist**. For the record only (er-v2 exists): `decisions/mention_pair/part-0000{0..3}.jsonl` (one MatchDecision v2 per MatchCandidate, ErRun.scala:78, written at :408) and `decisions/identity_assignment/part-00000.jsonl` (one mention_researcher MatchDecision per mention, ErRun.scala:80, :480).
- Proposed later (not built, scope): an EvalMain mode that evaluates a run's decisions against gold pairs by pair identity (unweighted only, design weights from the labeled sample's own key), so that a future er-v1/er-v2 comparison does not depend on a lost run folder; and an ER input guard that compares the J09 export's blocking settings with `Policy.EvaluatedBlocking` (G-18 review, ErRun.scala:289).

### Decision 2 status lines (not built in Phase 16)
- A-02: still present (Adjudication.scala:239, :242, :277, :279): model-chosen text (mutated key names, unknown key names, an unoffered chosenCandidateId) is echoed uncapped into the `detail` of llm/rejected-replies.jsonl (only the duplicate key is capped, :234). Latent: only AlwaysAbstainAdjudicator exists (ErMain.scala:108-111).
- A-05: still present (ErMain.scala:109; Adjudication.scala:337-346, cache key = LlmAdjudicationId.of(inputHash, promptVersion, modelVersion, policyVersion) without the adjudicator name): `--adjudicator always-abstain --llm-model-id <real id>` records stub abstentions under a real model ID that a later live run could take as cache hits. Latent until a live adapter exists.

## Policy version, thresholds, metrics, unresolved rate, errors
- Policy version in force: er-v2 (unchanged; sha256 58fd7f31…e917). er-v1.json unchanged (4946b350…07ac).
- Thresholds: accept 0.9, reject 0.3, LLM band [0.6, 0.9), llmMinConfidence 0.8, nearThresholdLogit 0.5, prior log-odds -1 (unchanged).
- Confusion matrices and metrics: none new (no evaluation run possible). The committed Phase 5 figures (er-v1) stand; source docs/agent-runs/phase-05/agent-report-er-eval.md.
- Error analysis: unchanged from Phase 5 (1 FP, 19 FN; same report).

## INTERFACE HANDOFF
- Files changed: listed above.
- Renamed symbol: `Policy.erV1` -> `Policy.current` (er-v2). `Policy.erV1` remains as a deprecated forwarder (Policy.scala:189-196); remove after test-engineer moves the 13 contract uses.
- New public signatures (Policy object): `val FrozenVersions: Set[String]`; `def frozenVersionRefusal(requested: String, applied: String): String`; `val EvaluatedBlocking: List[(String, ujson.Value)]`; `def evaluatedWith(p: Policy): ujson.Obj`. IntervalReliability: `val PartlyDesignFixedNote: String`; `StratumResult.fixedByDesign: Boolean = false`; `Result.partlyDesignFixed: Boolean = false`, `Result.designFixedShareOfPopulation: Double = 0.0` (defaults keep existing constructors compiling).
- Refusal message (N-1): `--policy-version 'er-v1' names a frozen policy file; this code applies er-v2, so its output must not carry the label 'er-v1'; choose a new version`.
- E4 command: none (reduced plan; see Group 1).
- EvalMain output files (unchanged names): `metrics.json` and `summary.md` in `--output`. Fields `evaluate.py er` may read (unchanged unless noted): top level `format` ("scholarchain.er.evaluation"), `formatVersion` 1, `labeledRows`, `unlabeledRows`, `provenance`, `goldSet`, `runContext`; `researcherResolution.unweighted` and `.weighted.metrics` (each: `units`, `precision|recall|coverage|unresolvedRate` {`value`, `numerator`, `denominator`, `interval95`, `intervalMethod`, …}, `f1`, `confusion`), `.weighted.bootstrap95`, `.weighted.design`, `.byMatchPath`, `.byFamily`, `.byStratum`, `.evidence`, `.llmPath` (incl. `runCounts`), `.errorAnalysis`, `.thresholdSensitivity`; `researcherClusterAssignment`; `workResolution`. **New (M4)** in `researcherResolution.weighted.intervalReliability.metrics.<metric>` and the cluster-level equivalent: `partlyDesignFixed` (bool), `partlyDesignFixedNote` (string or null), `designFixedShareOfPopulation` (number). `designDetermined` is now true only when every stratum is fixed. Changed text (M3): flagged intervals read `(unreliable; zero-event bound [x%, y%])`.
- Run report / manifest (G-18): new key `policyEvaluatedWith` = {`goldEvaluatedPolicyVersion`, `blocking` {3 settings} or null, `note`} at run-report top level and manifest top level (not in `config`).
- Decision-file paths and formats for both er-v1 and er-v2: not applicable (er-v1 set gone); er-v2 paths above.
- Fixtures: synthetic only (Phase16RepairSuite builds its own rows; G-18 uses RunFixture.works).
- Thread-safety: no shared mutable state added; `Policy.evaluatedWith` is pure.
- Limits: F9 fix covers fractional counts only, not M2's cache-hit mismatch; G-18 records but does not enforce the blocking settings.

## Proposed later
- Remove the `Policy.erV1` forwarder once tests/contracts uses `Policy.current` (Phase 17).
- M2's cache-hit part (outcome counts vs pairs sent) before any cache-hit adjudication or live LLM.
- EvalMain pair-identity mode and the ER blocking-settings input guard (see Group 1).

## VALIDATION EVIDENCE
- `shasum -a 256 docs/agent-runs/phase-16/prompt.md` -> c75cc5f895faed13880a69ec285231c5498fd50924c7b875fe7e02cce1707dc8 (matches the work order).
- `sbt -batch "entityResolution/testOnly org.scholarchain.er.Phase16RepairSuite org.scholarchain.er.EvalRepairRound2Suite org.scholarchain.er.PolicySuite org.scholarchain.er.GuardPathSuite org.scholarchain.er.DecisionSuite org.scholarchain.er.LlmSuite org.scholarchain.er.RunSuite org.scholarchain.er.GivenNameGuardSuite org.scholarchain.er.PriorSpellingSuite org.scholarchain.er.EvalReportSuite org.scholarchain.er.EvalSuite org.scholarchain.er.HarnessDesignSuite org.scholarchain.er.BoundarySuite org.scholarchain.er.CannotLinkRunSuite org.scholarchain.er.ReviewKeyRepairSuite org.scholarchain.er.WorkResolutionSuite org.scholarchain.er.ClusteringSuite org.scholarchain.er.NormalizeSuite"` -> exit=0, `[info] Passed: Total 147, Failed 0, Errors 0, Passed 147`.
- Mutation: `sbt -batch "entityResolution/testOnly org.scholarchain.er.Phase16RepairSuite"` with the five production fixes reverted -> exit=1, `Failed: Total 8, Failed 6, Errors 0, Passed 2`; fixes restored (grep for MUTATION and `&& false` in modules/entity-resolution/src: no match) before the final run.
- `sbt -batch "contractTests/testOnly org.scholarchain.contracts.er.*"` -> exit=1, `Failed: Total 231, Failed 2, Errors 0, Passed 229`; the 2 are the `.fail` tests F8 and F9 of EvalReportRound3AdversarialSuite (expected: their assertions now pass); 12 deprecation warnings for `Policy.erV1`.
- `git status --short`: my changes are only under modules/entity-resolution/** (13 modified, 1 new); the other changed paths belong to other session 2 steps.
- files changed after these runs: none
