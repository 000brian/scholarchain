# Repair 03: entity-resolution-engineer, code-reviewer findings M1, M2, m1-m7

Phase 5, run 1 of 2. Agent reports reproduced verbatim from the orchestrator session, in the order they were returned.

---

## Report 1: entity-resolution-engineer

All nine findings (M1, M2, m1 to m7) are fixed inside `modules/entity-resolution/**`, each with a module test, but `sbt check` failed on one `tests/**` contract test that pins the old guard list. So the test marker was not refreshed, and this has to go to test-engineer before the fresh review.

The failing test is `tests/contracts/src/test/scala/org/scholarchain/contracts/er/IdentityAdversarialSuite.scala:541-551`, "every guard-refused decision carries a concrete path and confidence of its evidence rule". It asserts er-v1's cluster-guard rules are exactly four (orcid, same-work, dblp-distinct-homonym, dblp-disambiguation-profile). The M1 fix deliberately adds `cluster.guard.conflict-pair` and `cluster.guard.rejected-pair`, so that equality now fails. I did not edit `tests/**`.

The fix: add the two IDs to the expected set. Both new guards keep the inherited-path and null-confidence pattern, so the per-guard checks at lines 552-555 should pass. The assertions after line 551 (lines 556 onward) never ran, because the test stops at the first failure.

```text
STATUS: BLOCKED-ON
OWNER: entity-resolution-engineer
WORK ORDER: docs/agent-runs/phase-05/prompt.md (run 1 of 2), code-reviewer REQUEST_CHANGES repair round: M1, M2, m1-m7 (decisions 4, 5, 11, 13, 15, 17, 21)
FILES CREATED:
  /Users/brian/Developer/repos/scholarchain/modules/entity-resolution/src/main/scala/org/scholarchain/er/cluster/CannotLinks.scala
  /Users/brian/Developer/repos/scholarchain/modules/entity-resolution/src/test/scala/org/scholarchain/er/CannotLinkRunSuite.scala
FILES MODIFIED (all under /Users/brian/Developer/repos/scholarchain/modules/entity-resolution/src/):
  main/resources/er/policy/er-v1.json
  main/scala/org/scholarchain/er/cluster/Clustering.scala
  main/scala/org/scholarchain/er/policy/Policy.scala
  main/scala/org/scholarchain/er/run/ErRun.scala
  main/scala/org/scholarchain/er/run/Report.scala
  main/scala/org/scholarchain/er/eval/Harness.scala
  main/scala/org/scholarchain/er/review/ReviewQueue.scala
  main/scala/org/scholarchain/er/io/Csv.scala
  main/scala/org/scholarchain/er/work/WorkResolution.scala
  main/scala/org/scholarchain/er/llm/LlmStage.scala
  test/scala/org/scholarchain/er/ClusteringSuite.scala
  test/scala/org/scholarchain/er/GuardPathSuite.scala
  test/scala/org/scholarchain/er/LlmSuite.scala
  test/scala/org/scholarchain/er/EvalSuite.scala
  test/scala/org/scholarchain/er/RunSuite.scala
  test/scala/org/scholarchain/er/WorkResolutionSuite.scala
  test/scala/org/scholarchain/er/PolicySuite.scala
  test/scala/org/scholarchain/er/BoundarySuite.scala
COMMANDS RUN:
  sbt -batch "entityResolution/test" (1 failure: my own determinism assertion used two different temporary input directories, so _manifest.json inputDir differed; test fixed)
  sbt -batch "entityResolution/testOnly org.scholarchain.er.CannotLinkRunSuite" (3/3 passed)
  sbt -batch check (run alone, background, 1476 s): FAILED in contractTestsNoAws
  read-only: grep, sed -n, cat, ls, find -mmin, shasum, git status
  Mistake: one inspection command used `find -exec grep`, which decision 21 forbids. The auto-mode classifier refused it and it did not run. I switched to grep -r. No variant of the refused action was attempted.
TEST RESULTS:
  Module tests: fresh test reports under modules/*/target total 1015 tests, 0 failures. Entity-resolution is 91/91. The module stage of sbt check passed, since check went on to the contract stage. I could not confirm 1015 = 995 + new tests from sbt's own summary line, because my output filter hid it.
  Contract tests (no AWS): 15 suites in this run, 204 tests, 203 passed, 1 failed (the IdentityAdversarialSuite test named above).
  sbt check output: "[error] Contract tests (no AWS) failed suite: org.scholarchain.contracts.er.IdentityAdversarialSuite ... .claude/.last-test-run was not updated." "Total time: 1476 s".
  The other contract tests that touch changed behaviour passed: ErDeterminismAndQueueSuite (queue columns), GoldAndHarnessAdversarialSuite (wilson95 unweighted), LlmAdversarialSuite (`._1(key)` on the stage result; adjudicator-error leaves rejected-replies empty).
VALIDATION RESULTS: no gold labels exist, so no gold-set evaluation was run. No real-data run (as instructed).
ASSUMPTIONS:
  - er-v1 stays er-v1. The work order says er-v1 must be final when run 1 ends, and run 1 is not over, so new rules and text change the file, not the version. Weights, thresholds and prior are unchanged.
  - A rejected pair blocks the whole merge of the two clusters, not just the two mentions. So when one name pair has both an accepted and a rejected mention-pair decision, the accepted link between those two clusters is refused (whole clusters cannot be split).
  - The new guard gives UNRESOLVED for a rejected-pair refusal and CONFLICT for a conflict-pair refusal.
RISKS:
  - The guard refuses more merges than before. Expect fewer merges beyond the dblp prior and more UNRESOLVED decisions with merge-refused:rejected-pair in the real run. Both are counted in the run report.
  - Memory and time at real scale (about 7.2M rejected or conflict decisions) are reasoned, not measured. The builder holds one long per decision (about 58 MB, briefly about 1.5x while growing). The stored constraints are two ints per distinct name pair. Each merge check scans the smaller cluster's constraint set.
  - The conflict-pair guard is in practice a backstop. Every CONFLICT pair rule found so far is also caught earlier by the orcid, same-work or distinct-homonym guard, so it is tested at clustering level only.
  - Reuse now re-hashes every input file once (one extra read of the export).
  - IdentityAdversarialSuite lines 556 onward were not reached. If the new guard changes which guards fire on that fixture, the "seen" subset check could need adjusting too.
MANUAL ACTION REQUIRED:
  - Orchestrator: route the contract-test update to test-engineer, then rerun sbt check.
  - Human: m7 weights and thresholds are your decision. Only the rationale text changed.
BLOCKED-ON OWNER: test-engineer
BLOCKED-ON EVIDENCE: tests/contracts/src/test/scala/org/scholarchain/contracts/er/IdentityAdversarialSuite.scala:541-551 asserts `guards == Set("cluster.guard.orcid","cluster.guard.same-work","cluster.guard.dblp-distinct-homonym","cluster.guard.dblp-disambiguation-profile")`. sbt check: "munit.ComparisonFailException: ...IdentityAdversarialSuite.scala:551", "Contract tests (no AWS) failed suite: org.scholarchain.contracts.er.IdentityAdversarialSuite". The failure is the M1 fix working as intended (two new cluster-guard rules in er-v1.json). Required change: add "cluster.guard.conflict-pair" and "cluster.guard.rejected-pair" to the expected set.
NEXT GATE: test-engineer updates the pinned guard set (and adds the m8 cluster-level adversarial test) -> sbt check passes -> fresh code-reviewer review -> APPROVE -> final real run (decision 17).
```

## Policy version, thresholds, er-v1 sha256
- Policy version: er-v1. Thresholds unchanged: accept 0.9, reject 0.3, llmBandLower 0.6, llmMinConfidence 0.8, nearThresholdLogit 0.5, prior -1.0.
- New er-v1.json sha256: `4c09390224be7d6b3f96b6dc26a13405dacd7aa36b2265752593cd23b1f09bf5`

## Fixes by finding

**M1 (cannot-links).** Rejected and conflict pair decisions now act as cannot-links.
- Pass 1 records every REJECTED or CONFLICT pair decision whose mentions are in different dblp-prior clusters. LLM rejections are recorded after adjudication.
- `CannotLinks.scala` deduplicates them by name pair (conflict wins over rejected) into compact int arrays.
- `Clustering.run` keeps one primitive int set of constraints per cluster root, merged small-into-large. The guard is checked after the four existing guards. Merge order is unchanged, so output stays deterministic.
- New rules: `cluster.guard.rejected-pair` (outcome unresolved, reason `merge-refused:rejected-pair`) and `cluster.guard.conflict-pair` (outcome conflict, reason `merge-refused:conflict-pair`). Both inherit path and confidence and are counted per reason.
- The run report gains `clusters.cannotLinks` with `cannotLinkDecisions`, `cannotLinkClusterPairs` and `rejectedOrConflictPairsInsideOneCluster` (by outcome).
- Tests:
  - ClusteringSuite: the reviewer's chain at clustering level (without the guard all three names end in one cluster), a conflict pair, a longer chain, and order independence.
  - CannotLinkRunSuite: the reviewer's chain through a full run, the refused decision's fields, report counts, identical bytes across output roots, an LLM-rejection cannot-link, and a conflict-pair chain.
- The fallback (flag clusters instead of refusing merges) was not needed.

**M2 (weighted intervals).**
- Weighted metrics now give `wilson95: null` and take `interval95` from the seeded stratified bootstrap.
- Every proportion now also reports `labeledUnits` (n) and `kishEffectiveN`; `intervalMethod` says which interval was used.
- Unweighted metrics keep `wilson95`, now also copied to `interval95`.
- The bootstrap also covers unresolvedRate, and weighted metrics include `f1Interval95`.
- The Markdown summary prints `interval95` under "Precision (95%)", and the weighted row is labelled "(bootstrap)".
- Test (EvalSuite): on the synthetic set the weighted interval [0, 1] is at least as wide as the unweighted Wilson interval. n is 3 while the summed weight is 100. The old Wilson interval with n = 100 is shown to be narrower.

**m1 (prior rows recognisable by empty candidateId).**
- A dblp-prior row's `candidateId` is now `ReviewQueue.priorReference`: `MatchCandidateId.of(mention_pair, left, right, "dblp-prior.numbered-name", <fullNameKey>)`. It has the same `match-candidate:v1:<32 hex>` form as real IDs, is deterministic and unique per pair, and is carried by no decision.
- Rows whose pair has a real candidate show the real ID.
- The review key adds `candidateIdKind` (`match-candidate` or `dblp-prior-reference`), and prior memberships carry the reference.
- The gold reader and harness are unchanged: they join on the unordered pair and require `candidateId` to equal the queue's.
- Test (RunSuite): every CSV candidateId has the same form, and a label file built from the queue joins all 10 rows (2 of them prior) through `GoldLabels.read` and `Harness.join`. Blanking a prior row's reference is refused.

**m2 (CSV formula injection).**
- The writer prefixes `'` when a cell starts with `=`, `+`, `-`, `@`, tab, CR or `'`.
- The reader removes exactly one leading `'` when it is followed by one of those characters. That makes the mapping a bijection, so the strict reader still matches against the raw queue JSONL. A hand-typed `'hello` is kept.
- Tests (EvalSuite): writer output, reader round trip, and the gold reader matching injected display values.

**m3 (count written as a string).** `workLinksOutsideSubset` is now `ujson.Num`; ujson writes a Long as a string. Test (WorkResolutionSuite): the value is 2 as a number, and the whole summary has no numeric strings.

**m4 (adjudicator exceptions not recorded).**
- `LlmStage.run` now returns a third element, a list of `AdjudicatorError` (exception class plus SHA-256 of the message, never the text).
- The run writes them to a sibling file `llm/adjudicator-errors.jsonl` and reports `llm.adjudicatorErrors`.
- `rejected-replies.jsonl` is left as it was, because a contract test pins it as empty for adjudicator errors.
- Tests (LlmSuite): the stage-level error record, and a run-level file and count check with no marker text stored.

**m5 (reuse trusted paths and sizes only).**
- On reuse, every manifest `inputFiles` entry is checked for existence, size and SHA-256, and an unrecorded input file is refused.
- Documented in ErRun: the run ID trusts `--code-version`, so a new, empty output root per attempt stays the procedure.
- Test (RunSuite): a same-size, in-place change to an input file is refused with "(content)", and the previous output is left intact.

**m6 (leakage test was a text search).** BoundarySuite now:
- forbids `eval.`-relative references, eval import selectors and eval type names in non-eval sources;
- scans every compiled non-eval `.class` file for `org/scholarchain/er/eval/`;
- checks that the detector catches six planted forms, including `import eval.Harness` inside `package org.scholarchain.er`.

**m7 (rationale text; weights not changed).** Test (PolicySuite): computes each case in the text from er-v1's weights and checks that the file states them.

Old text:
"accept: merge only when the evidence is about nine to one (log-odds 2.20): equal given names plus one more agreeing signal, or compatible initials plus a shared coauthor. reject: below 0.30 (log-odds -0.85) the evidence leans clearly to different, e.g. any given-name conflict without shared coauthors. Between them the pair is unresolved; its upper part [0.60, 0.90) is the LLM band, where one more piece of evidence would decide. llmMinConfidence: an LLM accept or reject below 0.8 stays unresolved. nearThresholdLogit: pairs within 0.5 log-odds of the accept or reject threshold are near-threshold for the review queue."

New text:
"accept: merge when the summed log-odds (prior -1.0 plus the feature weights) reach logit(0.90) = 2.20, about nine to one. What the weights actually do: (1) equal folded given names (+3.0) always co-fire with name_similarity (+0.3), because the folded full names are then identical (Jaro-Winkler 1.0), so two different dblp strings with equal folded names reach 2.3 (p 0.909) and are accepted on the name alone, without any independent evidence, whenever coauthor_shared_count is omitted and no other feature subtracts (different venue area -0.3, six or more years apart -0.4, different OpenAlex author IDs, disjoint institutions). (2) With zero shared coauthors (-0.5) equal folded names stand at 1.8 (p 0.858, LLM band), and the same venue alone (+0.4) lifts them to 2.2 (p 0.900, accepted): the same venue outweighs the absence of shared coauthors (a mapped same venue also adds venue_area_match +0.3). (3) Compatible given names (initial vs full name, +1.0) with one shared coauthor (+2.0) reach 2.0 (p 0.881) and stay unresolved unless one more signal agrees (when the two spellings are near-identical, e.g. only a middle initial missing, name_similarity >= 0.95 adds +0.3 and 2.3 is already accepted), e.g. coauthor_overlap_ratio >= 0.5 (+0.5), the same venue (+0.4), or title similarity >= 0.3 (+1.0); with two shared coauthors (+3.5) they reach 3.5 (p 0.97) and are accepted. reject: below 0.30 (log-odds -0.85) the evidence leans clearly to different; any given-name conflict (-4.0) without shared coauthors (-0.5) is rejected even when every other feature agrees. Between them the pair is unresolved; its upper part [0.60, 0.90) is the LLM band, where one more piece of evidence would decide. llmMinConfidence: an LLM accept or reject below 0.8 stays unresolved. nearThresholdLogit: pairs within 0.5 log-odds of the accept or reject threshold are near-threshold for the review queue. These weights and thresholds were set from feature meaning, not from labels; this text describes them and does not change them."

The old text was also wrong on its second clause: compatible initials plus one shared coauthor reach only 2.0 and are not accepted.

Optional item done: the `name.canonical-equivalent` description now says fullNameKey is NFD after Unicode whitespace runs are collapsed and trimmed, so it also covers whitespace variants (which J01 already normalises), and that case and punctuation are kept.

## Changes to the real-run command and outputs
- The command line and flags are unchanged.
- New output: `llm/adjudicator-errors.jsonl`.
- New report fields: `llm.adjudicatorErrors`, `clusters.cannotLinks`, and new reasons under `pairDecisions.mergeRefusalsByReason` and `clusters.mergesRefusedByReason`.
- New review-key field: `candidateIdKind`. The queue CSV columns are unchanged.
- Reusing a completed output now re-hashes the inputs.
- New metrics fields: `labeledUnits`, `kishEffectiveN`, `interval95`, `intervalMethod`, `f1Interval95`.

## Confusion matrices and error analysis
None: no gold labels exist yet (run 1).

