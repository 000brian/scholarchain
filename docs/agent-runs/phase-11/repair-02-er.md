STATUS: COMPLETE
OWNER: entity-resolution-engineer
WORK ORDER: Phase 11 step 3b (docs/agent-runs/phase-11/prompt.md, sha256 a0b59fae…e9ed1 as given; not recomputed). Decision 8 rules; repair A-01 (major); record A-02 and A-05 (minor) only.
FILES CREATED:
- /Users/brian/Developer/repos/scholarchain/modules/entity-resolution/src/test/scala/org/scholarchain/er/GivenNameGuardSuite.scala
FILES MODIFIED:
- /Users/brian/Developer/repos/scholarchain/modules/entity-resolution/src/main/scala/org/scholarchain/er/cluster/Clustering.scala
- /Users/brian/Developer/repos/scholarchain/modules/entity-resolution/src/main/scala/org/scholarchain/er/policy/Policy.scala
- /Users/brian/Developer/repos/scholarchain/modules/entity-resolution/src/main/scala/org/scholarchain/er/run/ErRun.scala
- /Users/brian/Developer/repos/scholarchain/modules/entity-resolution/src/main/resources/er/policy/er-v1.json
- /Users/brian/Developer/repos/scholarchain/modules/entity-resolution/src/test/scala/org/scholarchain/er/ClusteringSuite.scala
COMMANDS RUN:
1. Before the fix (to show the test fails without it): sbt -batch "entityResolution/testOnly org.scholarchain.er.GivenNameGuardSuite" (log: scratchpad/step3b-prefix.log). EXIT=1, "Failed: Total 3, Failed 2, Errors 0, Passed 1".
2. sbt -batch "entityResolution/testOnly org.scholarchain.er.*" "contractTests/testOnly org.scholarchain.contracts.er.*" (step3b-targeted.log): ClusteringSuite:157 failed. That line asserted the old behavior (without a cannot-link the chain merges). I updated my own test.
3. Same command (step3b-targeted-2.log): module 137/137 passed. Contract: 2 failures in CannotLinkAdversarialSuite (:482 and :521). This led to the design change described under A-01.
4. Same command, final run (step3b-targeted-3.log): module 138/138 passed. Contract: 231 run, 1 failed (CannotLinkAdversarialSuite:482, explained below).
Read-only checks: grep, sed -n, ls, git diff.
TEST RESULTS: entityResolution: Total 138, Failed 0 (17 suites). contractTests org.scholarchain.contracts.er.*: Total 231, Failed 1, Errors 0, Passed 230. The one failure is CannotLinkAdversarialSuite "Clustering: without cannot-links the chain merges (the guard is what separates)" at line 482. That test asserts the A-01 defect itself: Jane ~ J. ~ John with no cannot-link must give mergesApplied == 2 and one root. After the fix it gets 1. It is an expected flip, the same kind as hadoop's JobLevelRerunSuite. I cannot edit tests/**. test-engineer must invert it in step 4 (assert mergesApplied == 1, Jane and John apart, refused == {J.~John or Jane~J. -> merge-refused:given-name-conflict}). EvalReportRound3AdversarialSuite has failures="0", so the Phase 5 F8/F9 `.fail` tests still fail as expected.
VALIDATION RESULTS: The A-01 tests fail without the fix and pass with it (commands 1 and 4). Module test reports in modules/entity-resolution/target/test-reports all show failures="0" errors="0". No gold-set labels exist for this change, so no gold evaluation was run (law 4: no labels invented).
ASSUMPTIONS: (1) The er-v1 policy rationale states that a given-name conflict with no shared coauthors is always rejected. That makes an unjudged same-surname given-name conflict an implied cannot-link. (2) Policy version stays `er-v1`, following the repair-M1 precedent of adding guard text and rules to er-v1. The policy file's sha256 changes, so the ER config fingerprint and run IDs change for unchanged inputs.
RISKS: (1) IdentityAdversarialSuite pins exactly six cluster-guard rules. So the refusal is recorded under the existing rule `cluster.guard.rejected-pair` with its own reason code `merge-refused:given-name-conflict`, not under a new guard rule. The rule text in er-v1.json now describes both triggers. (2) The refusal depends on merge order, like the M1 cannot-link: the first link in merge order wins. (3) The John/Jane pair itself still has no candidate, so it never appears in the review queue. The refused link does appear, as UNRESOLVED with the reason code. (4) The behavior changed while the policy version stayed er-v1 (see proposed later). (5) No format check was run in this step. The formatter hook ran on every edit. Step 4's `sbt check` covers it.
MANUAL ACTION REQUIRED: none.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: step 3c (spark-engineer). In step 4, test-engineer inverts CannotLinkAdversarialSuite:480-491 (the first assertion block only; the self-cannot-link part of that test still holds). Step 5 rechecks A-01.

## Per-ID status

### A-01 (major): FIXED
Root cause: given-name compatibility is not transitive. J. ~ John and J. ~ Jane do not imply John ~ Jane. The merge guards only knew cannot-links from decided candidates, and the coauthor-keyed fallback never offers John/Jane as a candidate.

Fix (`Clustering.run`):
- Each cluster state keeps its dblp names grouped by folded surname (`bySurname`, merged small-into-large).
- New last guard, checked after the cannot-link guards so existing reasons are unchanged. A merge is refused with `merge-refused:given-name-conflict` when all three hold:
  - the merged cluster would hold two names with the same folded surname and `NameParts.givenNameRelation == 0`;
  - no accepted decision joins those two names directly (`EdgeCollector.contains`);
  - the merge edge's own two names are not themselves such a conflict.
- `ErRun.finalDecision` maps the refusal to rule `cluster.guard.rejected-pair` with outcome UNRESOLVED. The path, confidence and evidence rule are inherited, exactly as for M1.
- Residual case: the policy accepted a given-name conflict on direct evidence (for example orcid.exact, Pat ~ Pete) and that edge brought in a third conflicting name. The merge is applied. The final cluster is flagged `cluster-given-name-conflict` (new rule `cluster.flag.given-name-conflict`, kind assignment, outcome conflict), so every identity assignment in it is CONFLICT and the uncertainty is reported, not hidden.
- Why this split: refusing on conflict edges as well would override the policy's direct verdicts. It also broke CannotLinkAdversarialSuite:503-529, whose synthetic names (Pa…Pe Pathsynth) are all given-name conflicts linked directly. With the split, that suite passes.

Proof:
- GivenNameGuardSuite "A-01: a chain John ~ J. ~ Jane with no John / Jane decision never forms one cluster". Unit test with the reviewer's exact chain and no cannot-link. It failed before the fix at GivenNameGuardSuite.scala:50 (`assert(!same(John, Jane))`) and passes after.
- GivenNameGuardSuite "A-01: through the run, the refused link is unresolved, recorded and counted". Full ErRun on the export with every John–Jane candidate removed (the fallback blocks ["j smith","X"] and ["j smith","Y"]). Before the fix it failed at :144 with "researcher:v1:b59c0359… is equal to researcher:v1:b59c0359…": one ResearcherId for John and Jane, which is the finding. After the fix:
  - John and Jane get different ResearcherIds;
  - one link is ACCEPTED and one is UNRESOLVED, with rules [cluster.guard.rejected-pair, score.accept], reason [merge-refused:given-name-conflict], path probabilistic and confidence > 0.9;
  - the run report counts mergeRefusalsByReason = 1 and mergesRefusedByReason = 1.
- GivenNameGuardSuite "compatible given names and a direct accepted decision still merge" (no regression): Ann Bell ~ A. Bell ~ Ann M. Bell merges; a direct John~Jane acceptance is respected; different surnames are not checked.
- GivenNameGuardSuite "a chain of direct name-conflict acceptances merges and is flagged conflict". Unit test, plus a full run where one ORCID iD links Pat ~ Pete ~ Paul with the Pat–Paul candidate removed. All three assignments are CONFLICT with rule cluster.flag.given-name-conflict and reason cluster-given-name-conflict, and conflictFlagged = 1. Added after the design change, so it has no pre-fix run. Without the flag code, the flag assertions cannot hold.
- ClusteringSuite M1 test, updated: the old "unguarded" assertion (all three merge without the cannot-link) now asserts that the given-name guard keeps them apart.

### A-02 (minor): RECORDED, not fixed
Adjudication.scala:234-286 echoes model-chosen text (chosenCandidateId, key names, cited paths) into the `detail` field of llm/rejected-replies.jsonl with no length cap. This breaks the rule that failed model output is stored only as sha256, length and error. It cannot be reached today because only AlwaysAbstainAdjudicator exists.

### A-05 (minor): RECORDED, not fixed
ErMain.scala:108-111: `--adjudicator always-abstain --llm-model-id <real id>` records stub abstentions under a real model ID. The cache key (Adjudication.scala:331-349) leaves out the adjudicator, so a later live run can take the stub answers as cache hits. Latent until a live adapter exists. Shares the LLM-stage hardening root with A-02.

## Policy version, thresholds, metrics
- Policy version: er-v1. Thresholds are unchanged (accept 0.90, reject 0.30, LLM band [0.60, 0.90), llmMinConfidence 0.8, nearThresholdLogit 0.5). The rule list gains cluster.flag.given-name-conflict. The cluster.guard.rejected-pair description is extended. The policy file's sha256 changes.
- Gold-set metrics: none. There are no human labels for this change, and none were invented. Unresolved-rate impact on real data was not measured (no real run in this step). Expect more UNRESOLVED decisions in oversize initials-surname fallback blocks, where an initial-only name bridges two conflicting full names.
- Representative errors handled: John Smith ~ J. Smith ~ Jane Smith (refused; the later link in merge order becomes UNRESOLVED). Pat ~ Pete ~ Paul Synthorc on one ORCID iD (merged and flagged CONFLICT).

## Proposed later
1. Bump the policy to er-v2, or record the er-v1 behavior change in its provenance (human decision; many cross-module tests pin "er-v1").
2. A dedicated `cluster.guard.given-name-conflict` rule, once test-engineer allows a seventh guard in IdentityAdversarialSuite:544-554.
3. Put the unjudged John/Jane pair itself into the review queue as a prior-style row, so a human can label it directly.
4. A-02 and A-05 together: cap and hash the rejected-reply detail, and put the adjudicator name into the cache key and the decision provenance.

## INTERFACE HANDOFF
- Files changed: listed above. Only modules/entity-resolution/** was touched.
- Public signatures added:
  - `org.scholarchain.er.cluster.EdgeCollector#contains(nameA: Int, nameB: Int): Boolean`
  - `Reasons.MergeGivenNameConflict = "merge-refused:given-name-conflict"`
  - `Reasons.ClusterGivenNameConflict = "cluster-given-name-conflict"`
  - `Rules.FlagGivenNameConflict = "cluster.flag.given-name-conflict"` (in `Rules.All`, so er-v1.json must list it)
  - `Clustering.run` signatures unchanged; `ClusterInfo.flags` may now hold `cluster-given-name-conflict`.
- Output contract: there is a new reason code on mention_pair decisions (UNRESOLVED, rule cluster.guard.rejected-pair). Identity assignments and cluster summaries carry a new flag reason (CONFLICT). Spark main code does not enumerate ER reason codes (grep: only Spark test fixtures name them).
- Fixtures: `GivenNameGuardSuite.fallbackExport(works, x, mid, y)` (private) writes a Fixture export with one candidate pair removed. That is the pattern for coauthor-fallback chains.
- Thread-safety: Clustering is single-threaded per run, as before.
- Limits: the pairwise given-name check per shared surname is quadratic in the names one cluster holds under that surname, which is small in practice. Results depend on merge order, like M1.

TEST EVIDENCE
- command: sbt -batch "entityResolution/testOnly org.scholarchain.er.*" "contractTests/testOnly org.scholarchain.contracts.er.*"
- finished: 2026-11-16 20:01:39 (local)
- per suite (from sbt test reports):
  entityResolution:
  - BoundarySuite run=5 passed=5 failed=0 skipped=0
  - CannotLinkRunSuite run=4 passed=4 failed=0 skipped=0
  - ClusteringSuite run=13 passed=13 failed=0 skipped=0
  - DecisionSuite run=10 passed=10 failed=0 skipped=0
  - EvalRepairRound2Suite run=16 passed=16 failed=0 skipped=0
  - EvalReportSuite run=14 passed=14 failed=0 skipped=0
  - EvalSuite run=11 passed=11 failed=0 skipped=0
  - GivenNameGuardSuite run=4 passed=4 failed=0 skipped=0
  - GuardPathSuite run=3 passed=3 failed=0 skipped=0
  - HarnessDesignSuite run=5 passed=5 failed=0 skipped=0
  - LlmSuite run=12 passed=12 failed=0 skipped=0
  - NormalizeSuite run=7 passed=7 failed=0 skipped=0
  - PolicySuite run=8 passed=8 failed=0 skipped=0
  - PriorSpellingSuite run=5 passed=5 failed=0 skipped=0
  - ReviewKeyRepairSuite run=4 passed=4 failed=0 skipped=0
  - RunSuite run=14 passed=14 failed=0 skipped=0
  - WorkResolutionSuite run=3 passed=3 failed=0 skipped=0
  contractTests (org.scholarchain.contracts.er):
  - CannotLinkAdversarialSuite run=12 passed=11 failed=1 skipped=0 (expected flip, line 482, see TEST RESULTS)
  - DecisionContractAdversarialSuite run=16 passed=16 failed=0 skipped=0
  - ErDeterminismAndQueueSuite run=8 passed=8 failed=0 skipped=0
  - ErMembershipAndClusterRegressionSuite run=12 passed=12 failed=0 skipped=0
  - ErRepairRegressionSuite run=11 passed=11 failed=0 skipped=0
  - EvalReportAdversarialSuite run=20 passed=20 failed=0 skipped=0
  - EvalReportRepairRoundAdversarialSuite run=26 passed=26 failed=0 skipped=0
  - EvalReportRound3AdversarialSuite run=37 passed=37 failed=0 skipped=0 (F8/F9 `.fail` still failing as expected)
  - GoldAndHarnessAdversarialSuite run=17 passed=17 failed=0 skipped=0
  - IdentityAdversarialSuite run=23 passed=23 failed=0 skipped=0
  - LlmAdversarialSuite run=39 passed=39 failed=0 skipped=0
  - NameKeyAdversarialSuite run=10 passed=10 failed=0 skipped=0
- result line: entityResolution "[info] Passed: Total 138, Failed 0, Errors 0, Passed 138"; contractTests "[error] Failed: Total 231, Failed 1, Errors 0, Passed 230"
- expected-failure flips: none (no T-ID targets ER). Expected break, not a `.fail` flip: CannotLinkAdversarialSuite "Clustering: without cannot-links the chain merges (the guard is what separates)", line 482, "values are not the same, Obtained 1" (expected 2). It asserts the A-01 defect; test-engineer inverts it in step 4.
- source changed after this run: no
