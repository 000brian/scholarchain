STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 11 repair round 2, step 4. Read-only re-check of the diff since 29661e9 (scratchpad/round2-diff.patch, docs/agent-runs/ excluded) plus the untracked er-v2.json
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools)
TEST RESULTS: I ran nothing. The orchestrator says the wave closer's sbt check passed (EXIT=0). I did not observe that run myself.
VALIDATION RESULTS: I read every hunk of the diff. I compared er-v1.json and er-v2.json line by line (thresholds, weights, bins, source reliability and rules). I traced how ClaimVerification classifies numbers. I checked every user of Policy.erV1, er-v1.json and "er-v1" in the modules and tests. I also checked how the run ID, the stable IDs and the LLM cache key use policyVersion.
ASSUMPTIONS: Some facts come from documents, not from computing a hash. The constant 4946b3504759…07ac in Policy.scala:119 is the Phase 5 frozen sha256: it appears in docs/agent-runs/phase-05/interim-freeze.md:6 and repair-06.md:39. PolicySuite.scala:176-178 asserts that the file's bytes hash to that constant, and that check passed inside the reported sbt check.
RISKS: The --policy-version override can reuse a retired label (finding N-1). The misleading `erV1` alias stays (finding N-2).
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Human ratification of the Phase 11 repair round 2. The minor findings below can go on the "proposed later" list or into a later repair round, as the human decides.

VERDICT: APPROVE

(Saved by the orchestrator from the reviewer's returned text; the reviewer has no write tools.)

## Rulings

### R3-01 (major, rag): RESOLVED
- Fix: in modules/rag/src/main/scala/org/scholarchain/rag/runtime/ClaimVerification.scala:351-354, a number counts as a year only if it is a plain integer from 1900 to 2100 AND no cited non-year value (field not in `EvidenceValues.YearFields`, modules/rag/src/main/scala/org/scholarchain/rag/runtime/EvidenceBuilder.scala:35-36) matches it under `literalMatches`. That is the human decision as written.
  - Because `isYear` already requires a literal without a decimal point, the new clause only applies to whole integers. Those must match exactly (NumberFormats.scala:64), so no rounding tolerance gets in.
- C-04 attribution still applies to a reclassified number. It goes to the `else` branch (ClaimVerification.scala:360-377), which filters candidates by the researcher and metric named in the clause.
  - If another researcher's item holds the value, the result is still `NumberMisattributed`.
  - If no value matches, the result is `NumberConflict`.
  - No other path changed: ranges (lines 311-320), hops, bound numbers and the semantic-only rules are untouched.
- Tests: modules/rag/src/test/scala/org/scholarchain/rag/runtime/ClaimVerificationSuite.scala.
  - 388-391: the value 2034 is Verified. Without the fix this test fails: 2034 would be read as a year with no event years, giving YearConflict.
  - 393-396: the decimal 1999.5 is Verified.
  - 398-411: a wrong year (2033), a lone window year (2015) and edge years each still behave as before.
  - The original planted wrong-year test (line 184) is unchanged.
- The 1999.5 test passed even before the fix. I accept that: decimals were never treated as years (`!plain.contains('.')`), so the test guards against a future regression, not this one. The "fails without the change" rule is met by the 2034 test.
- Accepted limit, confirmed in the code: a wrong year, or a lone window year, that equals a cited non-year value is checked as a number and passes.
- Minor gap: no test covers a reclassified number being misattributed (finding N-3).

### R2-03 (ER policy label): RESOLVED
- The new er-v2.json (untracked) has the same thresholds, weights, bins, pair rules and source reliability as er-v1.json (I compared lines 10-165 of both). The only differences are:
  - `policyVersion` "er-v2" and the new description (line 4)
  - the A-01 text of cluster.guard.rejected-pair (line 146)
  - the new rule cluster.flag.given-name-conflict (line 151)
- `Policy.DefaultResource` is now er-v2 (Policy.scala:111). Runs go through `Policy.current` (ErMain.scala:83).
- Tests pin the label to the new behaviour:
  - PolicySuite.scala:166-186: er-v2 is the default and declares FlagGivenNameConflict and MergeGivenNameConflict.
  - PolicySuite.scala:234-235: a plain `ErMain.config` gives er-v2.
  - RunSuite.scala:296, 306-307: decisions and the manifest record er-v2 and the er-v2 sha.
- Stable IDs do not include the version. ResearcherId comes from the anchor mention (Clustering.scala:323).
- The run ID includes the version through the config fingerprint (policyVersion and policySha256, ErRun.scala:131-132, 176-181). So new runs get new `run=` directories, and old er-v1 run directories are never reused or overwritten (`Reuse.verify` needs matching fingerprints).
- `LlmAdjudicationId` and the LLM cache key include policyVersion by design (LlmStage.scala:167). Cached er-v1 adjudications therefore do not carry over to er-v2. That is correct, but rerunning with a live LLM costs new calls.
- Production code outside entity-resolution does not depend on "er-v1". Spark and core tests use it only as fixture text.

Unrequested step 1, reverting er-v1.json to its Phase 5 bytes: correct and low risk. The diff removes exactly the two Phase 11 A-01 text edits. The resulting bytes hash to the Phase 5 frozen sha256 (PolicySuite.scala:176-178 against Policy.scala:119, which matches docs/agent-runs/phase-05/interim-freeze.md:6). This improves provenance: the file labelled er-v1 again matches the sha that er-v1 manifests record. EvalMain reads `--policy` as raw JSON (EvalMain.scala:91-98), not through `Policy.parse`, so evaluating old er-v1 runs against er-v1.json still works.

Unrequested step 2, `Policy.parse` refusing er-v1.json: this is not new logic. The existing rule-set check (Policy.scala:266-270) refuses any file whose rules differ from `Rules.All`, which now includes FlagGivenNameConflict. The refusal is correct: loading er-v1 would run er-v2 code under the er-v1 label. The cost is that the er-v1 behaviour cannot be reproduced from the current code. That is inherent in the change and acceptable.

`Policy.erV1` kept as an alias that returns er-v2 (Policy.scala:143): correct at runtime (pinned by `Policy.erV1 eq Policy.current`, PolicySuite.scala:175) and documented, but the name is misleading (finding N-2). No production code calls it; only tests do. Nothing that loads er-v1 now fails silently or quietly gets er-v2: the only er-v1.json reader is the test at PolicySuite.scala:177-185.

### Contract test renames and Hadoop description: RESOLVED (text only)
- GoldAndHarnessAdversarialSuite.scala:260 and LlmAdversarialSuite.scala:70: only the test-name strings changed.
- scholarchain-hadoop-defaults.xml:273: only text inside `<description>` changed.
- The Harness.scala and Report.scala hunks are also description text only (a scaladoc line and the description strings in the report JSON). No logic changed.

## New findings

| ID | Severity | File:line | Failure input | Impact | Owner |
|---|---|---|---|---|---|
| N-1 | minor | modules/entity-resolution/src/main/scala/org/scholarchain/er/policy/Policy.scala:91-103 (reached from ErMain.scala:93-106) | `--accept-threshold 0.9 --policy-version er-v1` (even with unchanged thresholds) | `withThresholds` only checks that the new label differs from "er-v2", so this is accepted. Every decision then records policyVersion "er-v1" for output made with er-v2 merge behaviour. That reopens the ambiguity R2-03 closed. The LLM cache key would also match er-v1 cache entries. Only the run manifest (policyOverriddenFrom, policySha256) tells them apart. This needs a deliberate operator action, hence minor. | entity-resolution-engineer |
| N-2 | minor | modules/entity-resolution/src/main/scala/org/scholarchain/er/policy/Policy.scala:143 | A new caller writes `Policy.erV1` expecting the Phase 5 policy | The caller silently gets er-v2. Only the scaladoc warns. 12+ test call sites keep the misleading name (for example GuardPathSuite.scala:18, whose test name still says "er-v1 declares…" while it tests er-v2). | entity-resolution-engineer (module tests); test-engineer (tests/contracts call sites) |
| N-3 | minor | modules/rag/src/test/scala/org/scholarchain/rag/runtime/ClaimVerificationSuite.scala:388-411 | A claim "Bob Jones has weighted degree 2034 (W)" that cites Alice's 2034 item and Bob's item | Correct by reading the code (lines 360-375 give NumberMisattributed), but no test pins C-04 attribution for a number that R3-01 reclassifies from year to metric. A later refactor could break it without any test failing. | rag-engineer |
| N-4 | nit | tests/contracts/src/test/scala/org/scholarchain/contracts/er/LlmAdversarialSuite.scala:388 | — | The synthetic override is named "er-v1-synthetic-override" but now overrides er-v2. Misleading label in a test only. | test-engineer |

No critical or major finding remains, so the verdict is APPROVE.
