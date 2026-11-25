STATUS: COMPLETE
OWNER: documentation-engineer
WORK ORDER: Phase 17, session 3, repair round R3, citation fix (code-reviewer finding in review-report-R3-minor.md): stale line citations into OversizeBlockAdversarialSuite.scala in docs/hadoop-job-contracts.md
FILES CREATED: none in the repository (this report, and the scratchpad diff diff-contracts-R3-4.txt)
FILES MODIFIED: /Users/brian/Developer/repos/scholarchain/docs/hadoop-job-contracts.md (citation line numbers only)
COMMANDS RUN:
- grep -n "Suite.scala" docs/hadoop-job-contracts.md (all suite citations)
- grep -n "test(" on OversizeBlockAdversarialSuite.scala, OversizeBlockStrategySuite.scala, JobLevelTeamScaleSuite.scala (test start lines); grep -n -A2 "^  test($" for the multi-line test names; grep -n "^  }$" / "^}$" for the end lines; wc -l
- git -C <repo> diff -U1 -- docs/hadoop-job-contracts.md > .../phase17-reports/diff-contracts-R3-4.txt (exit 0, 237 lines)
- grep of the edited citations afterwards (output matches the table below)
TEST RESULTS: not applicable (Markdown only; sbt not run, per work order)
VALIDATION RESULTS: every OversizeBlockAdversarialSuite citation in the doc now lands on the `test(` line of the test its sentence describes (re-checked by grep after editing)
ASSUMPTIONS: the suite range in the "Tests added" list (old `:128-686`) is meant to span all 18 tests, from the first `test(` line to the closing brace of the last test, the same rule the OversizeBlockStrategySuite range `:133-484` uses (its last test closes at :484). The last test (BlockingChain, :688) now closes at :708, so the range is `:128-708`. Old :686 is now the closing brace of the "misses an oversize key" test, so it no longer covered the last test.
RISKS: the saved diff is against HEAD, so it also contains the earlier uncommitted R3 edits to this file, not only this round's six edited lines
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer focused re-check of the citations; human copies this report with cp -n

## Citation changes (contracts/OversizeBlockAdversarialSuite.scala)

| Doc line | Old citation | New citation | Test at the new line |
|---|---|---|---|
| 112 (J05 tests) | :533 | :530 | "deterministic: reversed input, shuffled files and small splits give identical records, listings, counters and size stats" |
| 112 (J05 tests) | :578 | :580 | "largestReduceGroup stays within the cap and does not depend on the map-task layout" |
| 130 (J05p tests) | :620 | :622 | "a J05p pre-pass computed for another member cap is refused (4 vs 5 and 6 vs 5)" |
| 130 (J05p tests) | :629 | :631 | "a pre-pass for the same member cap but another pair limit (effective cap 4) is refused" |
| 130 (J05p tests) | :653 | :655 | "a corrupt or wrong-version oversize-key list is refused, never read as empty" |
| 130 (J05p tests) | :666 | :668 | "a pre-pass that misses an oversize key: the guard counts it, records stay the same" |
| 144 (J05b tests) | :578 | :580 | "largestReduceGroup stays within the cap and does not depend on the map-task layout" |
| 161 (J05bp tests) | :637 | :639 | "a J05bp pre-pass computed for another fallback cap is refused" |
| 277 (Refusals, stale list) | :666 | :668 | "a pre-pass that misses an oversize key: the guard counts it, records stay the same" |
| 281 (Tests added) | :128-686 | :128-708 | range: first test (:128) to the closing brace of the 18th test (:708; class closes at :709) |
| 281 (Tests added) | :578 | :580 | "largestReduceGroup stays within the cap and does not depend on the map-task layout" |

## Citations checked and left unchanged (already correct)

- OversizeBlockAdversarialSuite: :191 (J05p lists exactly the oversize keys), :211 (J05: key 24x the cap...), :228 (J05 records equal the model), :256 (J05bp lists the still-oversize fallback groups), :276 (J05b still-oversize groups...), :308 (J05c coverage), :331 (pre-pass and reducer-side cap write the same records; lines 112 and 262), :435 and :442 (counters add up).
- OversizeBlockStrategySuite (lines 112, 130, 144, 161, 174, 262, 280): :133, :148, :178, :187, :227, :249, :286, :366, :423, :442 are all `test(` lines of the tests described; range :133-484 matches (last test closes at :484).
- JobLevelTeamScaleSuite (lines 200, 307): :48, :114, :129, :148 are all `test(` lines of the tests described.

## Replayed commands and outcomes
Only read-only grep/wc/tail and the git diff above; all observed.

## Link-check results
No links changed; not rerun.

## Unverified claims
None introduced; only line numbers changed.
