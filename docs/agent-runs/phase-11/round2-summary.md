PHASE RESULT: COMPLETE_WITH_OPEN_ITEMS (1. ratify the unrequested er-v1.json revert; 2. minor findings N-1, N-2, N-3 and nit N-4, recorded and not repaired; no critical or major finding remains)

# Phase 11 repair round 2: orchestrator summary

- Base commit (Phase 11 work commit, HEAD at start): `29661e9`. Diff base for every step: `git diff 29661e9` (docs/agent-runs/ excluded).
- Tree at start: clean.
- Gate: "No critical or major verified finding remains" is **met**. R3-01 (the only open major) is RESOLVED, and the re-check found no new critical or major finding (code-reviewer verdict APPROVE).
- Nothing committed. Reports are in /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/a8a39918-edf3-4540-902c-e41a9d1554c2/scratchpad/phase11-reports/.

## Steps

| Step | Agent | Result | Report |
|---|---|---|---|
| 1 | rag-engineer | COMPLETE. R3-01 fixed in ClaimVerification.scala:351-354. Targeted run: 7 suites, 98/98 passed. Fake benchmark attempt-02-fake: planted errors 89/89, claims 173/2/0/0 (same as attempt-01-fake), no claim changed status. | round2-repair-rag.md |
| 2 | entity-resolution-engineer | COMPLETE. Policy is now er-v2 (new er/policy/er-v2.json, `Policy.current`). 17 suites, 139/139 passed. No golden files changed; 4 test files changed. | round2-repair-er.md |
| 2b | hadoop-engineer | COMPLETE. One description line, scholarchain-hadoop-defaults.xml:273. 3 suites, 23/23 passed. | round2-repair-hadoop.md |
| 3 | test-engineer (wave closer) | COMPLETE. Its 2 routed test-name renames were made first, then a targeted contract run (13 suites, 228/228), then a full `sbt check` with EXIT=0 in 932 s (module_tests=2005, contract_tests=398). The `.fail` marks T-02..T-06, F8 and F9 are unchanged, and no expected-failure test passed. | round2-test-report.md, round2-check.log |
| 4 | code-reviewer | APPROVE. R3-01 RESOLVED; R2-03 RESOLVED; the renames and the hadoop line are text only. 3 new minor findings and 1 nit. | round2-review-report.md |

Orchestrator checks: each source-changing report has a complete TEST EVIDENCE block with run > 0 for every suite. A `failures|errors` grep found no failing report in modules/rag, modules/entity-resolution or modules/hadoop. test-engineer found none in any project after `sbt check`.

## Rulings

- **R3-01 (major, rag): RESOLVED.**
  - Change: a year-shaped number is checked as a year only if no cited non-year value matches it under literalMatches. C-04 attribution still applies.
  - The 2034 test fails without the fix (the owner saw it fail first).
  - Deviation from the work order: the 1999.5 decimal test passed even before the fix, because decimals were never treated as years. It is kept as a regression guard, and the reviewer accepted it.
  - Known limit (accepted): a wrong year that equals a cited non-year value passes. The realistic exposure is metric items whose value or rank falls between 1900 and 2100 (weighted degree, occurrences, component or community size). Exposure for edge weight, team size, hops and depth is about zero. Details are in round2-repair-rag.md.
- **R2-03 (er-v2): RESOLVED.**
  - Default policy: er-v2.
  - Old outputs: untouched and still labelled er-v1 (the Phase 7 export run=51b50d…).
  - Stable IDs: unchanged. The policy version is an input to the ER run ID (through the config fingerprint) and to `LlmAdjudicationId` (the cache key). Both are by design, so er-v1 LLM cache entries no longer hit.

## Open items for the human

1. **Ratify an unrequested change:** entity-resolution-engineer reverted `er-v1.json` to its frozen Phase 5 bytes (sha256 4946b350…07ac, which matches the Phase 5 manifests). `Policy.parse` now refuses that file under the current code, and a test pins the refusal. The reviewer judged this correct and an improvement in provenance. The cost is that er-v1 behaviour can only be reproduced from the Phase 5-10 code. `Policy.erV1` stays as an alias that returns er-v2, so the contract tests compile unchanged.
2. **New minor findings** (recorded, not repaired; no new repair round was allowed):
   - N-1 (entity-resolution-engineer): `--policy-version er-v1` with a threshold override is accepted and relabels er-v2 output as er-v1. Proposed fix: refuse retired labels.
   - N-2 (entity-resolution-engineer and test-engineer): the misleading `Policy.erV1` alias and the test names that still say er-v1. Proposed fix: rename it to `Policy.current` at the call sites, then drop the alias.
   - N-3 (rag-engineer): no test pins NumberMisattributed for a number that the R3-01 rule reclassifies as a metric.
   - N-4 nit (test-engineer): the "er-v1-synthetic-override" label at LlmAdversarialSuite.scala:388.

## Proposed later (from the owners)

- rag: compare the number against the item the clause ties it to, or use year words ("in", "since", "from") to tell years apart.
- test-engineer and entity-resolution-engineer: rename `Policy.erV1` to `Policy.current`, then drop the alias.
- Two old generated reports with no source behind them can be cleared by a clean build: core `TmpRegenSuite`, contracts `BuildEngineerHadoopProbeSuite`.

## Files changed since 29661e9 (uncommitted)

- rag: ClaimVerification.scala, ClaimVerificationSuite.scala
- entity-resolution: er-v1.json (reverted), er-v2.json (new, untracked), Policy.scala, ErMain.scala, Decide.scala, Report.scala, Harness.scala, PolicySuite.scala, BoundarySuite.scala, EvalReportSuite.scala, RunSuite.scala
- hadoop: scholarchain-hadoop-defaults.xml
- tests/contracts: LlmAdversarialSuite.scala, GoldAndHarnessAdversarialSuite.scala (test names only)

## Process notes

- The main-session stop hook fired twice while agents were still working; the marker was stale until the wave closer's check. I did not run `sbt check` myself. The step 3 check refreshed the marker.
- I started step 2b after step 2's report file was final and no sbt process was running, slightly before its completion notice arrived.
- code-reviewer has no Bash, so I saved the diff to /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/a8a39918-edf3-4540-902c-e41a9d1554c2/scratchpad/round2-diff.patch for it, and I wrote round2-review-report.md from the text it returned.
