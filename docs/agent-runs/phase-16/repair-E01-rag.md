# repair-E01-rag (Phase 16 session 3, findings P16-L-03, P16-L-04, P16-L-05, P16-L-06; follows P16-L-01)

STATUS: COMPLETE
OWNER: rag-engineer
WORK ORDER: docs/agent-runs/phase-16/prompt.md (sha256 c75cc5f895faed13880a69ec285231c5498fd50924c7b875fe7e02cce1707dc8, checked with shasum), HEAD 66470343c9b80ae0c514232a8c533db4e90242df, repair-E01-rag, findings P16-L-03, P16-L-04, P16-L-05, P16-L-06. The human approved this repair, the last L2 repair round before E1.
FILES CREATED: none in the repository
FILES MODIFIED:
- /Users/brian/Developer/repos/scholarchain/modules/rag/src/main/scala/org/scholarchain/rag/runtime/ClaimVerification.scala
- /Users/brian/Developer/repos/scholarchain/modules/rag/src/test/scala/org/scholarchain/rag/runtime/PathAbsenceSuite.scala
COMMANDS RUN:
- git rev-parse HEAD; shasum -a 256 on the work order; git diff/status (read-only)
- cp of the HEAD and fixed ClaimVerification.scala to the scratchpad and back, for the temporary revert (own territory only)
- sbt -batch "rag/testOnly org.scholarchain.rag.runtime.PathAbsenceSuite" (fixed, then reverted, then fixed again)
- sbt -batch "rag/testOnly <9 suites, FQCN>": PathAbsence, ClaimVerification, Grounding, Stages, QueryWorkflow, RuntimeRepairs, RuntimeService, BenchmarkRunner, AbstentionCodes
- sbt check (wave closer; run in the background, log at /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/15acd480-beb2-4248-b3ae-3f168fefe386/scratchpad/sbt-check-E01.log)
- sbt -batch packageCheck
TEST RESULTS:
- PathAbsenceSuite: 34/34 passed (was 25 before this repair; 9 new tests, and 3 existing tests changed as the rule requires, listed below).
- Targeted run, 9 suites: Passed: Total 144, Failed 0, Errors 0.
- Temporary revert (HEAD ClaimVerification.scala with the final tests): Failed: Total 34, Failed 11, Passed 23. Every new finding test failed, and so did the 3 rule-changed tests. The fixed file was restored afterwards (grep "P16-L-03" finds 3 lines; git diff shows the fix).
- sbt check: exit 0, [success] 984 s, 182 "finished: 0 failed" lines and no failing suite line; module tests 2300 passed, 0 failed, 5 skipped; contract_tests=399; marker timestamp 2026-11-22T14:39:55Z.
- packageCheck: "packageCheck: ok", [success].
VALIDATION RESULTS: every P16-L-03..L-06 input is now not Verified, and none of them is kept. Groundedness evaluation (group 4) runs on frozen data and was not rerun; this work order does not name it.
ASSUMPTIONS:
- "The sentence" is the whole claim text (one DraftClaim). This is stricter than splitting into sentences and needs no heuristic for "Dr."-style abbreviations. A claim that names a third researcher anywhere is not Verified for its absence clause.
- How names are found: capitalized name runs (RulePlanner.nameRuns, homonym number kept), plus any cited full name that appears as a word and is not already part of a run. A name resolves only when it maps to exactly one researcher ID across the cited items (name/researcherId, nameA/researcherA, nameB/researcherB, pathNames/pathIds). An unknown name, a name with two IDs, or two names with the same ID means not Verified.
- The claim's window: the one range the clause states, otherwise the one range the claim states, otherwise none. Two ranges give no window and fail. So does any year-like token (19xx, 20xx, "2010s", "'19") that RangeRe or BetweenRe does not parse, anywhere in the claim. That covers "from 2015 to 2025", "since 2019", "2015 through 2025", "in 2021", and a fronted "From 2015 to 2025, ...".
- "Or neither has one": path=none items always carry a window, so an absence claim that states no window is never Verified. This answers the reviewer's open policy observation as fail closed.
- "Any cited found path between the pair": I also count a cited coauthor edge between the pair (found, not edge=none) as a found path of 1 hop. A found path or edge inside the claim's window (or with no usable window), within the stated depth (or with no depth stated), is a contradiction: HopsConflict, status Conflicting. Any other cited found path for the pair gives AbsenceNotSearched, status Unsupported.
- A missing depth stays AbsenceNoDepth (uncovered, so PartiallySupported; never Verified). This is unchanged Phase 16 L2 behaviour. Now that a claim with no window always also gets AbsenceNotSearched, every no-depth claim I can construct is also missing a window or has another failure, so it is Unsupported.
- The reviewer's exact L-04 input 1 ("Unlike Bob Jones, ...") was already not kept on HEAD, because "Unlike Bob Jones" is a name run (NameNotCited). On HEAD the test failed only on the reason assertion. I added the variant "Carol King, unlike Bob Jones, is not connected to Alice Smith within 6 hops in 2021-2023." By my hand trace it is Verified on HEAD. That is a trace, not an observation: the revert loop stopped at the first input.
RISKS:
- Behaviour changes forced by the human rule (fail closed wins). Each was Verified before and is now not kept:
  1. P16-A-09 combined claim "Alice Smith and Bob Jones are connected by 2 hops in 2015-2025, not within 6 hops in 2021-2023." citing found + noneW1: now Unsupported (AbsenceNotSearched). Reason: the absence clause's pair (Alice/Bob) has a cited found path (Alice–Carol–Bob). The rule says "any cited found path between the pair ... is not Verified". The found path is outside the 2021-2023 window, so it is not a contradiction and the status is Unsupported, not Conflicting. Each half alone, cited with its own evidence, is still Verified (asserted in the test). The 3-hop version is still Conflicting.
  2. K-01 positive texts citing found + noneW1K: now Unsupported (AbsenceNotSearched), for the same found-path reason. Citing noneW1K alone they are still Verified.
  3. L2 absence claims that state no window ("... within 6 hops." / "... within 4 hops." citing completeNone): now Unsupported (AbsenceNotSearched), because the item has a window and the claim has none. The windowed versions ("... in 2015-2025.") are Verified.
- Live impact: model absence claims that leave out the window or cite a found path will be removed. The deterministic synthesis claims (Synthesis.scala:623 "In <window>, no coauthorship path between X and Y exists within depth N (the bounded search completed).") state the window and cite only the path=none item, so they stay Verified (QueryWorkflowSuite passes).
- Reason text: AbsenceNotSearched ("states a researcher pair or window that no cited path search covered") is reused for four cases: name count or ambiguity, an unparsed time phrase, a missing window, and a non-contradicting found path. It is exact for the first three. For the found-path case it is loose. Proposed later: a dedicated reason if the human wants exact wording. No new reason code was added.
- Absence wording now counts whatever is cited. A claim with absence wording ("not connected", "no path") that cites only metrics is unsupported. This is intended (L-03). Edge-absence wording ("have no direct coauthor edge") is not absence wording under AbsencePhraseRe, so it is unchanged.
- An unrelated year-like number in an absence claim (for example "2000 coauthors") makes its absence clause not Verified. This fails closed.
MANUAL ACTION REQUIRED: none. Record: "AbsenceNotSearched accepted by the human (additive, no other user of Reasons.all)".
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer re-check of P16-L-03..L-06 and of the three rule-forced behaviour changes; human gate; then E1.

Fix status: fixed in code, tested offline, not validated live.

## Cases kept and their expected status (PathAbsenceSuite, all passing)
- L2 "No coauthorship path between Alice Smith and Bob Jones exists within 6 hops in 2015-2025." completeNone: Verified (window added; the no-window form is case R3 below)
- L2 "... not connected within 4 hops in 2015-2025." completeNone: Verified
- L2 "within 8 hops": not kept, AbsenceBeyondDepth
- L2 "No path connects Alice Smith and Bob Jones." completeNone: not Verified, AbsenceNoDepth (plus AbsenceNotSearched)
- L2 time-limited, 3 texts: not kept, AbsenceBoundedSearch
- L2 resource-limited "not connected within 6 hops": not kept, AbsenceBoundedSearch
- L2 "did not complete, but no path exists ...": not kept, AbsenceBoundedSearch
- L2 honest "whether a path exists is unknown", 2 texts: Verified
- L2 found-path 2 hops: Verified; 3 hops: Conflicting
- P16-A-09 unrelated negation, 2 texts: Conflicting, HopsConflict, statesAbsence false
- P16-A-09 combined 3 hops: Conflicting, HopsConflict
- P16-A-09 combined 2 hops: Unsupported, AbsenceNotSearched (R1); each half alone Verified
- P16-A-09 governed phrasings: statesAbsence true for 6 texts, false for 2
- K-01 window mismatch (noneW1K + metricFull, 2015-2025): not kept, AbsenceNotSearched
- K-01 found path, same pair and window: Conflicting, HopsConflict
- K-01 "No other path is shorter than the 2 hops ...": not kept, HopsConflict
- K-01 other pair (Alice/Carol): not kept, AbsenceNotSearched
- K-01 no window, two windows: not kept
- K-01 positive 3 texts: alone Verified; with found, not kept, AbsenceNotSearched (R2)
- L1 stop reasons, codes and the 3 workflow tests: unchanged, pass
- Reasons-list test: pass

## New tests (each failed on HEAD in the revert run, 11 of 34 failing in total)
- R3 "an absence claim that states no window is not verified" (2 texts): AbsenceNotSearched, not kept
- P16-L-03 "No path connects Alice Smith and Bob Jones." and "... are not connected in 2015-2025." citing only found: Conflicting, HopsConflict (Verified on HEAD)
- P16-L-03 absence wording citing only metrics (Alice and Bob): not kept, AbsenceNotSearched (PartiallySupported, kept, on HEAD)
- P16-L-04 input 1 (exact text plus a variant), each with found or with a Carol metric: not kept, AbsenceNotSearched
- P16-L-04 input 2 "Alice Smith, Bob Jones and Carol King ...", with found or with a Carol metric: not kept, AbsenceNotSearched (Verified on HEAD)
- P16-L-05 "Alice Smith 0001 and Bob Jones ..." with a search for 0002/Bob plus a 0001 metric: not kept, AbsenceNotSearched (Verified on HEAD). With a search for 0001/Bob: Verified.
- P16-L-05 ambiguous "Alice Smith" (two IDs cited): not kept, AbsenceNotSearched (Verified on HEAD)
- P16-L-06 "from 2015 to 2025", "since 2015", "in 2015 through 2025", citing noneW1K + an Alice–Carol edge 2015/2025: not kept, AbsenceNotSearched (Verified on HEAD)
- Coauthor edge between the pair in W1 + noneW1K: Conflicting, HopsConflict (Verified on HEAD)

## Prompt and model versions
Unchanged. No prompts/** change, no model ID change, and no threshold, limit or default changed.

## INTERFACE HANDOFF
- Interface change: no. No new reason code and no signature change (`verify`, `verifyClaim`, `VerifiedClaim`, `absenceSpans`, `statesAbsence` unchanged). Reasons.all is unchanged since repair-E00; AbsenceNotSearched was accepted by the human (additive, no other user of Reasons.all).
- Behaviour: absence spans are detected on every claim. Absence clauses follow the human's fail-closed rule (see ASSUMPTIONS). A cited coauthor edge counts as a 1-hop found path for the absence check only.
- New private regex YearLikeRe; local helpers idPair, idsByName, claimPair, unparsedYear. The old base-name helpers (baseName, mentions, endpoints) are removed.
- Fixtures added in PathAbsenceSuite: carolW1 (Carol metric, W1), alice1/alice2 (homonyms "Alice Smith 0001"/"0002" on rid D/E), edgeAC (Alice–Carol edge, 2015/2025), plus local bobFull, otherAlice and edgeAB.
- Thread-safety: pure function with no shared mutable state. Limits: none added. The cost per claim is one extra nameRuns pass and one map over the cited items.

## VALIDATION EVIDENCE
- 08:21:43 sbt -batch "rag/testOnly ...PathAbsenceSuite" (fix, before the 2 test refinements): Passed: Total 34, Failed 0
- 08:22:56 temporary revert (HEAD verifier, final tests): Failed: Total 34, Failed 11, Passed 23; fix restored at 08:23:03
- 08:23:13 targeted 9 suites (fix, final tests): Passed: Total 144, Failed 0, Errors 0
- 08:23:29 to 08:39:56 sbt check: exit=0, [success] Total time 984 s; Module tests executed=2300 passed=2300 failed=0; .last-test-run updated 2026-11-22T14:39:55Z (module_tests=2300, contract_tests=399)
- 08:40:15 sbt -batch packageCheck: packageCheck: ok, [success]
files changed after these runs: none (ClaimVerification.scala last written 08:23:03, PathAbsenceSuite.scala 08:22:45, both before the targeted run and sbt check; the experiments/** working-tree changes belong to another agent and are not mine)
