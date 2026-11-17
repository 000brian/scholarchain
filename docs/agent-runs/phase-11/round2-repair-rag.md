STATUS: COMPLETE
OWNER: rag-engineer
WORK ORDER: Phase 11 repair round 2, step 1: R3-01 (major). Base 29661e9. A year-shaped number (1900-2100) is checked as a year only when it matches no cited non-year value under literalMatches.
FILES CREATED: none
FILES MODIFIED:
- /Users/brian/Developer/repos/scholarchain/modules/rag/src/main/scala/org/scholarchain/rag/runtime/ClaimVerification.scala (isYear condition at about line 350-354, plus the class doc line on years)
- /Users/brian/Developer/repos/scholarchain/modules/rag/src/test/scala/org/scholarchain/rag/runtime/ClaimVerificationSuite.scala (3 new tests and a small R3-01 fixture)
COMMANDS RUN:
- sbt -batch "rag/testOnly org.scholarchain.rag.runtime.ClaimVerificationSuite" (before the fix, to show the new test fails), log round2-rag-prefix.log
- sbt -batch "rag/testOnly <7 suites>" (after the fix), log round2-rag-targeted.log
- the fake benchmark (command below), in the background, waited for with the bounded loop, log round2-rag-fake-benchmark.log
- read-only: grep, ls, sed -n, shasum, sort, uniq, tail -n, git status/diff
TEST RESULTS:
- Before the fix: EXIT=1, "Failed: Total 15, Failed 1, Errors 0, Passed 14". The failing test was "R3-01: a whole metric value of 2034 that the cited metric holds is supported" (ClaimVerificationSuite.scala:390).
- After the fix: EXIT=0, "Passed: Total 98, Failed 0, Errors 0, Passed 98". The L-2 planted wrong-year test in GroundingSuite passed, and so did every existing ClaimVerificationSuite test.
VALIDATION RESULTS:
- grep -l -E 'failures="[1-9]|errors="[1-9]' modules/rag/target/test-reports/TEST-*.xml printed nothing (exit 1).
- Fake benchmark attempt-02-fake: EXIT=0, "[benchmark] completed", 135 s. Results are in the evaluation section below.
ASSUMPTIONS:
- "Cited non-year value" means any numeric field of a cited graph (non-semantic) item that is not in EvidenceValues.YearFields: value, rank, weight, jointPublicationCount, hops, maxDepth, depthSearched, teamSize, confidence, publicationCount, distinctCoauthors. Matching uses the existing literalMatches rule. This set is the existing `values` list minus the year fields.
- A number that becomes a non-year this way then goes through the existing C-04 researcher and metric attribution. So a matching value on the wrong researcher or metric is still NumberMisattributed (a conflict). The fix does not mark it checked by itself.
- The work order said both new tests must fail without the fix. The decimal test (1999.5) passed before the fix, because a literal containing '.' was never treated as a year (old line 349, `!plain.contains('.')`). I kept it as a guard test. Only the whole-number test (2034) fails without the fix, and I saw it fail.
RISKS:
- Known limit (accepted by the human decision): a wrong year that equals a cited non-year value passes as that value. Likelihood is analysed below.
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer review of R3-01; the wave closer's sbt check (I am not the wave closer; I did not run sbt check).

## Prompt and model versions
No change. No prompt, schema or model ID was touched. The verifier has no version. The run report still shows codeVersion rag-runtime/3 from the unchanged code.

## Evaluation: fake benchmark (modules/rag/target/phase-11-real/attempt-02-fake)
Command (fake LLM, no AWS, no network):
sbt -batch -J-Xmx12g "rag/runMain org.scholarchain.rag.runtime.BenchmarkRunner --config modules/rag/src/test/resources/config/rag-runtime-local-v2.json --export modules/spark/target/phase-07-export/run=51b50d578d5b609842c1c21b8266a00b --neptune-csv modules/storage/target/phase-07-real/attempt-01/neptune --questions modules/rag/src/test/resources/benchmark/benchmark-v1.json --out modules/rag/target/phase-11-real/attempt-02-fake --llm fake"

| Target | attempt-01-fake | attempt-02-fake |
|---|---|---|
| Planted errors caught | 89/89 | 89/89 (fabricated-evidence-id 30, semantic-cited-for-number 2, wrong-hop-count 3, wrong-number 27, wrong-window-year 27) |
| Claims verified / partial / unsupported / conflicting | 173 / 2 / 0 / 0 | 173 / 2 / 0 / 0 |
| Plan fields (operations, window, metric, limit, unsupported, allFieldsCorrect) | 30/30 each | 30/30 each |
| droppedBoundNumbers / boundNumberErrors | 0 / 0 | 0 / 0 |

Claims whose status changed: none.
- I listed the claim statuses of q-01..q-30 for each run in order and hashed the lists. The hashes are identical (1300ee9f...).
- The planted-error status lists also have identical hashes (fadff2fd...).
- No fake-run claim states a metric value from 1900 to 2100 as a bare integer, so the fix changes nothing in this run.

## Known limit: a wrong year equal to a cited non-year value passes
The check is made across all cited graph items. A year-shaped number then passes only if it also clears C-04 attribution: a cited item tied to the clause's or the claim's researcher, and to its metric words, must hold the value. Fields that sit next to year fields in one cited evidence item:
- Metric items (window years only): value and rank.
  - The value can reach 1900-2100 for weighted degree, total coauthor occurrences, component size and community size. Distinct coauthors can too, for very prolific authors. The fake run has values of 1895 and 3290.
  - Rank can reach that range in large rankings.
  - This is the realistic exposure. A claim that cites a metric item and states a lone year ("since 2019") was already a conflict before (L-2: a window year never supports a single year). It now passes only if 2019 equals that item's value or rank and the attribution words fit.
- Edge items (firstYear, lastYear, window): weight and jointPublicationCount. Both are small in DBLP (typically 1-50; a pair with about 1900 joint papers does not occur). Risk is about zero.
- Work items (year, window): teamSize. It is typically under 100 in DBLP; hyper-authored records with about 2000 authors are very rare. Risk is very low.
- Path items (window): hops, which is at most the search depth (single digits). Path-absent items: maxDepth and depthSearched (single digits). Risk is zero.
- Match-decision items: no year fields of their own. When cited next to an edge or work item, publicationCount or distinctCoauthors could equal a stated year for a very prolific author. Risk is low.

Overall: a slip needs the wrong year to equal exactly an integer value of a cited item for the same researcher and metric. Wrong years cluster in roughly 1990-2026, so per claim the chance is small. It is not zero for metric claims on high-value metrics such as weighted degree, occurrences, and component or community size.
Proposed later (not done): compare against the item the clause ties the number to, rather than all cited values, or treat a number as a year when a year word ("in", "since", "from") comes before it.

## Grounding gaps
None new beyond the known limit above.

TEST EVIDENCE
- command: sbt -batch "rag/testOnly org.scholarchain.rag.runtime.ClaimVerificationSuite org.scholarchain.rag.runtime.GroundingSuite org.scholarchain.rag.runtime.BenchmarkRunnerSuite org.scholarchain.rag.runtime.QueryWorkflowSuite org.scholarchain.rag.runtime.RuntimeRepairsSuite org.scholarchain.rag.runtime.RuntimeServiceSuite org.scholarchain.rag.runtime.ModelCallsSuite"
- finished: Nov 16, 2026, 11:46:00 PM (local)
- per suite (from the TEST-*.xml reports, timestamp 2026-11-16T23:45:57):
  - ClaimVerificationSuite run=15 passed=15 failed=0 skipped=0
  - GroundingSuite run=12 passed=12 failed=0 skipped=0
  - BenchmarkRunnerSuite run=8 passed=8 failed=0 skipped=0
  - QueryWorkflowSuite run=25 passed=25 failed=0 skipped=0
  - RuntimeRepairsSuite run=14 passed=14 failed=0 skipped=0
  - RuntimeServiceSuite run=14 passed=14 failed=0 skipped=0
  - ModelCallsSuite run=10 passed=10 failed=0 skipped=0
- result line: [info] Passed: Total 98, Failed 0, Errors 0, Passed 98
- expected-failure flips: none
- source changed after this run: no

INTERFACE HANDOFF
- Files changed:
  - ClaimVerification.scala: one condition in verifyClaim's number loop, plus a doc line.
  - ClaimVerificationSuite.scala: tests "R3-01: a whole metric value of 2034 ...", "R3-01: a decimal metric value in the year range ...", "R3-01: year-shaped numbers no cited non-year value holds are still years (L-2)".
- Public types and signatures: unchanged (ClaimVerification.verify, verifyClaim, literalMatches, Reasons, VerifiedClaim).
- Fixtures: private to ClaimVerificationSuite (w2034 and w1999 WeightedDegree MetricFacts for Alice and Bob); none shared.
- Thread-safety: unchanged. The verifier is a pure function.
- Known limits:
  - A wrong year equal to a cited non-year value (with matching attribution) passes, as analysed above.
  - A year written with group commas (`2,034`) was already never a year; unchanged.
