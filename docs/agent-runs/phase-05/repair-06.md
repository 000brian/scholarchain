# Repair 06: entity-resolution-engineer, code-reviewer findings m-new-1..3

Phase 5, run 1 of 2. Agent reports reproduced verbatim from the orchestrator session, in the order they were returned.

---

## Report 1: entity-resolution-engineer

All three findings are fixed, but `sbt check` did not pass, so the status is BLOCKED-ON test-engineer. The only failure is the expected one: test-engineer's contract test `tests/contracts/src/test/scala/org/scholarchain/contracts/hadoop/JobLevelBlockingSuite.scala:125` ("J06 maxPairsPerBlock below 66 ...") still expects the old J05 behavior (expected `BlocksEmitted` 1, got 0). None of my changes requires an edit to any tests/** file. er-v1 weights, thresholds and rules are unchanged; only the rationale text changed, so the file's sha256 is new (below).

```text
STATUS: BLOCKED-ON
OWNER: entity-resolution-engineer
WORK ORDER: docs/agent-runs/phase-05/prompt.md (run 1 of 2), repair round for code-reviewer findings m-new-1, m-new-2, m-new-3 (decisions 4, 5, 11, 13, 15, 21)
FILES CREATED:
  /Users/brian/Developer/repos/scholarchain/modules/entity-resolution/src/test/scala/org/scholarchain/er/ReviewKeyRepairSuite.scala
FILES MODIFIED:
  /Users/brian/Developer/repos/scholarchain/modules/entity-resolution/src/main/scala/org/scholarchain/er/review/ReviewQueue.scala
  /Users/brian/Developer/repos/scholarchain/modules/entity-resolution/src/main/scala/org/scholarchain/er/eval/Harness.scala
  /Users/brian/Developer/repos/scholarchain/modules/entity-resolution/src/main/scala/org/scholarchain/er/run/ErRun.scala
  /Users/brian/Developer/repos/scholarchain/modules/entity-resolution/src/main/scala/org/scholarchain/er/run/Report.scala
  /Users/brian/Developer/repos/scholarchain/modules/entity-resolution/src/main/resources/er/policy/er-v1.json (rationale text only)
  /Users/brian/Developer/repos/scholarchain/modules/entity-resolution/src/test/resources/synthetic-gold/review-key.jsonl
  /Users/brian/Developer/repos/scholarchain/modules/entity-resolution/src/test/scala/org/scholarchain/er/EvalSuite.scala
  /Users/brian/Developer/repos/scholarchain/modules/entity-resolution/src/test/scala/org/scholarchain/er/PolicySuite.scala
COMMANDS RUN:
  - Read-only inspection (grep, find, cat, head, tail, ls, date, shasum; read-only git diff and status). Deviation from decision 21: I also used `sed -n` twice and `touch` once, by mistake. `sed -n` only printed lines of a tests/** file and of my own scratch log. `touch` created an empty log file in the session scratchpad so a wait command could follow it. Nothing in the repository changed and nothing was refused.
  - `sbt "entityResolution/testOnly ..."` (targeted suites): ReviewKeyRepairSuite, EvalSuite, PolicySuite, RunSuite, HarnessDesignSuite, BoundarySuite. Final runs passed.
  - `sbt check`, run 1 (background): failed in module tests. My new rationale text used the word "recall", which `BoundarySuite.scala:114` forbids in the policy file (leakage guard). I reworded it and BoundarySuite and PolicySuite then passed (13 of 13).
  - `sbt check`, run 2 (background, alone): EXIT=1. Every module test passed, and so did every contract suite except JobLevelBlockingSuite (1 of 6 failed, see evidence below).
  - Blocking `tail -f | grep -m1` on the scratch log to wait for sbt to finish.
TEST RESULTS:
  - sbt check run 2: all core, hadoop and entity-resolution module suites: 0 failed. Contract ER suites, all 0 failed: ErRepairRegressionSuite 11, LlmAdversarialSuite 39, DecisionContractAdversarialSuite 16, ErDeterminismAndQueueSuite 8, GoldAndHarnessAdversarialSuite 17, NameKeyAdversarialSuite 10, IdentityAdversarialSuite 23, CannotLinkAdversarialSuite 12.
  - The only failure: contracts.hadoop.JobLevelBlockingSuite, "J06 maxPairsPerBlock below 66: block not expanded, pairs counted as skipped" (1 failed, 6 total).
  - `.claude/.last-test-run` was therefore not refreshed.
VALIDATION RESULTS:
  - No real data was used; every test uses synthetic fixtures. The queue CSV and JSONL stay label-blind: a test checks that they contain no `researcher:v1:` and no `sameFinalCluster`.
  - er-v1: policyVersion, weights, rules and thresholds are unchanged (accept 0.9, reject 0.3, LLM band 0.6, LLM min confidence 0.8, near-threshold 0.5, asserted in PolicySuite).
  - er-v1.json sha256: old 4c09390224be7d6b3f96b6dc26a13405dacd7aa36b2265752593cd23b1f09bf5, new 4946b3504759d5f9a61f60646e6ca9d6cd27b0dd6ef26e57a1729c2fb32807ac.
  - No gold-set evaluation was run: no human labels exist yet (it runs in run 2).
ASSUMPTIONS:
  - KeyRow keeps its 12-field positional constructor and gains new fields with defaults, so the tests/** suites that build KeyRow in memory still compile without edits. For such in-memory rows, an empty `membershipDecisions` means every membership carries the row decision. The file reader (`KeyRow.readAll`) requires the new fields, so a real review key cannot rely on that fallback.
  - The row-level decision of a queue row is now the decision of the candidate shown in its `candidateId`. Before, a row created by a dblp-prior pair kept the prior decision even when it displayed a real candidate.
RISKS:
  - The breakdowns (byMatchPath, byFamily, byStratum) now score each row with the decision of its membership in that group. A row sampled in two paths counts in both groups.
  - Cluster-level weighted estimates reuse the pair-level stratum weights: every membership of a row carries the row's cluster outcome.
  - knownRecallGaps measurement: it counts groups of dblp names that share a first initial and surname but hold two or more different initials-surname keys. It approximates exposure to the gap and does not count missed pairs.
  - Review keys written before this repair cannot be read by the new reader. No real run has used this code yet; the final real run regenerates the key.
MANUAL ACTION REQUIRED:
  - Human decision: whether to add a blocking family (for example first initial plus surname) to close the middle-name gap. I did not add one.
  - After test-engineer's fix, sbt check must be rerun so `.last-test-run` is refreshed.
BLOCKED-ON OWNER: test-engineer
BLOCKED-ON EVIDENCE:
  tests/contracts/src/test/scala/org/scholarchain/contracts/hadoop/JobLevelBlockingSuite.scala:125
    124: val (j05, j06, c) = run("out-pairs65", Settings.Keys.CandidateMaxPairsPerBlock -> "65")
    125: assertEquals(j05.main(Counters.BlocksEmitted), 1L)   -> Obtained 0
  Cause: hadoop-engineer changed J05 so blocks over maxPairsPerBlock go to the fallback; the test pins the old behavior. Required change (test-engineer): update the J06 maxPairsPerBlock < 66 case to expect the new J05 fallback behavior.
  Pins from my change in tests/**: none. All tests/contracts ER suites passed unchanged.
NEXT GATE: test-engineer updates JobLevelBlockingSuite; then rerun sbt check (all other gates passed in run 2); then code-reviewer re-review of m-new-1..3; then the final real run (decision 17) from approved code.
```

### Fix per finding

**m-new-1: each sampled membership keeps its own decision**
- **Queue:** in `ReviewQueue.scala`, each `Membership` now has its own `outcome` and `path`, taken from its own sampled unit. A dblp-prior membership uses the prior identity decision; a candidate membership uses the candidate decision.
- **Hidden review key:** writes `outcome` and `matchPath` on every membership. The label file is unchanged.
- **Row-level decision:** the decision of the shown candidate.
- **Harness:** `Harness.weightedUnits` scores each membership with its own decision. The byMatchPath, byFamily and byStratum breakdowns use membership decisions too. `KeyRow.readAll` rejects a key without them.
- **Tests:** `ReviewKeyRepairSuite` builds a real queue in which a dblp-prior pair (accepted, deterministic) is also sampled in a candidate stratum (unresolved, probabilistic). It asserts both decisions survive in the key and the weighted units, which failed before the fix because both carried "accepted". A second test checks weighted recall of 100/110 against 1.0 before, plus per-stratum recall. `EvalSuite` checks the reader rejects missing fields.

**m-new-2: the review key and harness measure the final cluster assignment**
- **Review key:** now records `leftResearcherId`, `rightResearcherId` and `sameFinalCluster`, taken from `clusters.clusterOfMention` in `ErRun`. The reader rejects a `sameFinalCluster` value that disagrees with the two IDs.
- **Harness:** a new top-level `researcherClusterAssignment` section, separate from the pair-level metrics. It has:
  - unweighted precision, recall and F1 with Wilson intervals;
  - weighted estimates with the stratified bootstrap;
  - a 2x3 confusion matrix (same / different final cluster against MATCH / NON_MATCH / UNSURE);
  - a table of pair decision against final cluster.
- **Markdown:** gains a matching section.
- **Tests on the synthetic gold set:**
  - Cluster level: precision 3/4, recall 3/4, weighted precision 23/33 and weighted recall 46/49. The confusion rows are same 3/1/0 and different 1/2/1.
  - Pair-level recall stays 0.5.
  - A run-level test checks every key row against the identity-assignment decisions, and that dblp-prior rows are always in one cluster.

**m-new-3: middle-name blocking gap (text and reporting only)**
- **er-v1 rationale (3):** no longer cites "only a middle initial missing". It now says this case cannot happen: John A. Smith and John Smith get different initials-surname keys (j a smith, j smith) and different base names, so blocking never pairs them. It calls this "a known blind spot of the blocking (pairs it can never offer), not of these weights". The word "recall" is avoided because of the BoundarySuite leakage guard.
- **Run report:** `run-report.json` gains `knownRecallGaps` with id `blocking.middle-name-variants`, a description, and the counts `firstInitialSurnameGroupsSplitByMiddleInitials` and `dblpNamesInThoseGroups`.
- **Tests:** `PolicySuite` checks the new text, the key difference, and that the thresholds are unchanged. `ReviewKeyRepairSuite` runs on a fixture that includes "John A. Smith" and checks there is no candidate pairing it with John Smith or J. Smith, and that the report counts are 1 group and 4 names. A hand-checkable unit test expects (2, 7).
- **Not done:** no blocking family was added.

### Policy version and thresholds
- **Policy version:** `er-v1`, at `/Users/brian/Developer/repos/scholarchain/modules/entity-resolution/src/main/resources/er/policy/er-v1.json`.
- **Thresholds:** accept 0.90, reject 0.30, LLM band lower 0.60, LLM min confidence 0.80, near-threshold 0.5 logit. All unchanged.
- **New er-v1 sha256:** `4946b3504759d5f9a61f60646e6ca9d6cd27b0dd6ef26e57a1729c2fb32807ac`

### Confusion matrices and error analysis
None on real labels: no gold labels exist yet (run 2). The synthetic hand-checked values are listed under m-new-2 above.
