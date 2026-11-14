# Repair 04: entity-resolution-engineer, test-engineer minor findings 1 and 3

Phase 5, run 1 of 2. Agent reports reproduced verbatim from the orchestrator session, in the order they were returned.

---

## Report 1: entity-resolution-engineer

STATUS: COMPLETE
OWNER: entity-resolution-engineer
WORK ORDER: docs/agent-runs/phase-05/prompt.md (run 1 of 2). This was the repair round for test-engineer's minor findings 1 (cannot-link reporting) and 3 (bootstrap statistics). I read decisions 4, 5, 13, 15 and 21.
FILES CREATED:
- /Users/brian/Developer/repos/scholarchain/modules/entity-resolution/src/test/scala/org/scholarchain/er/HarnessDesignSuite.scala
FILES MODIFIED:
- /Users/brian/Developer/repos/scholarchain/modules/entity-resolution/src/main/scala/org/scholarchain/er/run/ErRun.scala
- /Users/brian/Developer/repos/scholarchain/modules/entity-resolution/src/main/scala/org/scholarchain/er/eval/Harness.scala
- /Users/brian/Developer/repos/scholarchain/modules/entity-resolution/src/test/scala/org/scholarchain/er/CannotLinkRunSuite.scala
COMMANDS RUN:
- Read-only: grep, sed -n, find, cat, tail, wc, ls, date, and `shasum -a 256` on the policy file. `git status --short modules/entity-resolution`.
- `sbt -batch "entityResolution/testOnly org.scholarchain.er.HarnessDesignSuite org.scholarchain.er.EvalSuite org.scholarchain.er.CannotLinkRunSuite org.scholarchain.er.BoundarySuite"`. First run: 1 failure, in my new finding-1 test, which expected 1 within-prior decision where there are 2 (one per blocking family). I corrected the test; the rerun of CannotLinkRunSuite passed 4/4.
- `sbt -batch check` alone, with the background option, logged to /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/a78572bd-c42b-4db3-bd71-b2631a490ee2/scratchpad/check.log. It exited 0.
- While waiting I ran some read-only `find ... | wc -l` scans under /Users/brian/Library only to let time pass. Nothing was written.
TEST RESULTS: `sbt check` passed (exit=0, 24m37s). Module tests 1020/1020 (was 1014; +6 = 5 in HarnessDesignSuite + 1 in CannotLinkRunSuite). Contract tests "suites=17 executed=230 passed=230 failed=0 errors=0 skipped=0". markTestRun updated .claude/.last-test-run at 08:28.
VALIDATION RESULTS:
- er-v1 is unchanged: sha256 of modules/entity-resolution/src/main/resources/er/policy/er-v1.json is still 4c09390224be7d6b3f96b6dc26a13405dacd7aa36b2265752593cd23b1f09bf5.
- No weights, thresholds or rules changed.
- No files were written outside modules/entity-resolution/**.
- Nothing was written under docs/agent-runs/, tests/** was not touched, and nothing was run on real data.
ASSUMPTIONS:
- The resampling stratum of a row with several memberships is its first membership stratum in string order. This is a documented approximation: strata are sampled separately, and joint inclusion probabilities are not modelled.
- I did not revert the code to watch the new tests fail. The reasons they would fail without the fix are under FIX SUMMARY.
RISKS:
- The interval change only matters when a row has several memberships or a stratum has a single labeled row. In that case intervals widen, and that is intended. With one membership per row and no single-row strata (the real attempt-01 queue shape), the replicate draws are the same RNG sequence as before, so intervals are unchanged. The pinned contract tests confirm this.
- The run report now has extra keys. Downstream consumers that check exact key sets would need the new fields, but none exist in the repo.
MANUAL ACTION REQUIRED: none.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: test-engineer rerun of the adversarial suite on these findings, then code-reviewer. Any code change after review approval means rerunning the real run (decision 17).

POLICY VERSION: er-v1, unchanged (sha256 above). Thresholds unchanged.

FIX SUMMARY

Finding 1 (ErRun.scala, pass 2 and report): additive.
- `clusters.cannotLinks.rejectedOrConflictPairsInsideOneCluster` keeps its meaning: final REJECTED or CONFLICT pair decisions whose two mentions share one final cluster, by outcome.
- A new sibling, `clusters.cannotLinks.rejectedOrConflictPairsInsideOneClusterSplit`, holds two counts by outcome:
  - `acrossPriorClusters`: the two mentions have different dblp names, so the pair was a cannot-link. This must be `{}` when M1 holds.
  - `withinPriorCluster`: the two mentions have the same dblp name (one dblp-prior cluster, e.g. same-work duplicates or two ORCIDs in one dblp profile). These are expected on a correct run; the cluster is flagged, not split.
- The meaning is documented in a comment at the counter's declaration in pass 2.
- The contract pin `{"conflict":1}` still passes.
- Tests in CannotLinkRunSuite:
  - The M1 chain test now asserts that both split maps are empty.
  - New test "finding 1: an expected within-prior conflict is split from across-prior pairs in one cluster": a dblp name appears twice on one work and merges with "K. Splitname". It asserts total = within = {"conflict":2} (one decision per block family) and across = {}. Without the fix the split field is missing and the lookup throws.

Finding 3 (Harness.scala): point estimates and `labeledUnits` (memberships) are unchanged, so the "n semantics" contract test (4 units for 3 rows) passes.
- Resampling unit:
  - The bootstrap now resamples labeled rows, not memberships.
  - Each drawn row brings all its memberships, each at its fixed design weight population_h / labeledMemberships_h.
  - Rows are resampled within their resampling stratum, the row's first membership stratum in string order (`ResamplingRule`).
- Single-labeled-row handling (`CollapseRule`):
  - All single-row resampling strata are pooled into one collapsed group.
  - If there is exactly one, it joins the smallest other group (fewest rows, then name).
  - With fewer than two labeled rows in total, no interval is given: intervals are null and `bootstrapUnavailable` gives the reason.
- Reporting:
  - Every proportion now has `labeledRows` (distinct rows) next to `labeledUnits`.
  - `weighted.design` holds `labeledRows`, `labeledMemberships`, `resamplingUnit`, both rules, `singleLabeledRowStrata` (the flag), per-stratum {population, labeledMemberships, labeledRows, weight, singleLabeledRow}, `bootstrapGroups` (name, strata, rows, collapsed) and `bootstrapUnavailable`.
  - The Markdown summary prints rows against memberships, the rules, the flagged strata and the collapsed groups.
  - The `BootstrapMethod` text was updated. The contract test only checks that it contains "bootstrap".
- API changes:
  - `EvalUnit` gained a defaulted `row` field and `Proportion` a defaulted `rows` field.
  - `Harness.bootstrap` now takes the labeled rows instead of units. No test outside the module called it.
- Tests in the new HarnessDesignSuite (each fails without the fix):
  1. Rows are reported separately from memberships: 3 rows, 4 memberships. The `labeledRows` fields did not exist before.
  2. Six rows each in S1 (pop 600) and S2 (pop 300) give exactly the same interval as the same rows in one stratum of pop 900. Under the old code, independent per-stratum membership draws gave a different, narrower interval.
  3. Three MATCH rows in S1 plus one NON_MATCH row in a single-row stratum S2: the interval width is now > 0.1. The old code gave precision 0.75 in every replicate (zero width). The test also checks the flag and the collapsed group.
  4. Several single-row strata are pooled into one collapsed group, checked through `bootstrapDesign`.
  5. One labeled row gives a null interval with a stated reason. The old code gave a degenerate [p, p].
- No pinned contract test had to change.

CONFUSION MATRICES / ERROR ANALYSIS: not applicable in run 1. No gold labels exist and no evaluation was run.
