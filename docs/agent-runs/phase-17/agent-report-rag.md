# agent-report-rag (Phase 17 session 2, step 1)

STATUS: COMPLETE
OWNER: rag-engineer
WORK ORDER: docs/agent-runs/phase-17/prompt.md (sha256 c22adfc57c7fde0cae63c8520393c2c81cd12280df7f5df773e70c2e2ec8ac24, checked with shasum), HEAD 50ba087ecd33178eaa8ccc69286b9de85e8a8146, step 1: decision 6 (tier 1, P16-L-10..L-15, items 1a-1g, 2, 3, 4), then tier 2 H-03 (wording only). Not the wave closer.
FILES CREATED:
- /Users/brian/Developer/repos/scholarchain/modules/rag/src/test/scala/org/scholarchain/rag/runtime/AbsenceRuleSuite.scala
- /Users/brian/Developer/repos/scholarchain/modules/rag/src/test/scala/org/scholarchain/rag/runtime/VerifierMonotonicSuite.scala
- /Users/brian/Developer/repos/scholarchain/modules/rag/src/test/scala/org/scholarchain/rag/runtime/VerifierOracle.scala
FILES MODIFIED:
- /Users/brian/Developer/repos/scholarchain/modules/rag/src/main/scala/org/scholarchain/rag/runtime/ClaimVerification.scala
- /Users/brian/Developer/repos/scholarchain/modules/rag/src/test/scala/org/scholarchain/rag/runtime/PathAbsenceSuite.scala (2 call sites now go through VerifierOracle; no assertion changed)
- /Users/brian/Developer/repos/scholarchain/modules/rag/src/test/scala/org/scholarchain/rag/runtime/ClaimVerificationSuite.scala (2 call sites through VerifierOracle; no assertion changed)
- /Users/brian/Developer/repos/scholarchain/modules/rag/src/test/scala/org/scholarchain/rag/runtime/GroundingSuite.scala (7 call sites through VerifierOracle; no assertion changed)
- /Users/brian/Developer/repos/scholarchain/modules/rag/src/main/scala/org/scholarchain/rag/eval/RetrievalEvalSet.scala (H-03: rule text only)
- /Users/brian/Developer/repos/scholarchain/modules/rag/src/main/resources/org/scholarchain/rag/eval/benchmark-v1-eval-set.json (H-03: the same rule text; the suite requires the packaged set to equal the derivation)
- /Users/brian/Developer/repos/scholarchain/modules/rag/src/test/scala/org/scholarchain/rag/eval/RetrievalEvalSetSuite.scala (H-03: test name and a wording assertion)
- No prompts/** change. No golden file change (services territory untouched).
COMMANDS RUN:
- shasum -a 256 on the work order; git status --short; git diff --stat; git diff (read-only); wc, ls, grep, head, tail -n, cut
- sbt -batch "rag/Test/compile"
- sbt -batch "rag/testOnly ..." (5 runs, listed under VALIDATION EVIDENCE), one of them with the rule temporarily switched off (Edit, then restored with Edit)
- sbt -batch "services/testOnly org.scholarchain.services.GoldenExamplesSuite" (2 runs)
- One slip: a `date '+%H:%M:%S'` appended to one test command (read-only; `date` is on the work order's never-run list). It changed nothing. Times below come from sbt's own "completed" lines and `ls -lT`.
- No sbt fmt (the PostToolUse formatter formatted each written file), no sbt check, no sbt clean, no runMain, no AwsTest/EmulatorTest, no network, no model call.
TEST RESULTS:
- Final targeted rag run, 13 suites: Passed: Total 193, Failed 0, Errors 0 (AbsenceRuleSuite 26, VerifierMonotonicSuite 1, PathAbsenceSuite 34, ClaimVerificationSuite 15, GroundingSuite 12, StagesSuite 14, QueryWorkflowSuite 25, RuntimeRepairsSuite 14, RuntimeServiceSuite 14, BenchmarkRunnerSuite 9, AbstentionCodesSuite 8, RetrievalEvalSetSuite 5, RetrievalEvalSuite 16).
- services GoldenExamplesSuite: Passed: Total 17, Failed 0 (every golden example byte-equal; no golden expected output changed, so no BLOCKED-ON service-engineer).
- Rule switched off (verifyClaim temporarily calling the Phase 16 path): AbsenceRuleSuite 14 of 26 failed (every finding and item-f regression test, plus the synthesis-wording test), while PathAbsenceSuite 34/34, ClaimVerificationSuite 15/15 and GroundingSuite 12/12 passed: the switched-off path reproduces the Phase 16 assertions, and each finding test fails without the fix. Restored before the final runs.
VALIDATION RESULTS:
- Groundedness evaluation: the work order names none for this step; group 4 runs on frozen data and was not rerun.
- Monotonic: no status rose anywhere. The verifier's own suites (61 claims' checks), the fake benchmark corpus and the golden question (59 claims, 0 of them absence claims) have zero falls. Falls occur only in the new AbsenceRuleSuite inputs, each intended (table below).
- Every P16-L-10..L-15 failure input is now not verified (table below).
ASSUMPTIONS:
- "Lower" means verified > partially_supported > unsupported > conflicting (both of the last two are removed claims; a change from unsupported to conflicting counts as a fall).
- Monotonicity is built in, not only tested: the Phase 17 rule runs after the Phase 16 checks, never changes them, never raises `checked`, and only adds reasons (the status function is monotone in its reason sets). VerifierOracle also asserts that every Phase 16 reason is kept and `checked` is equal.
- The Phase 16 phrase list (AbsencePhraseRe) is kept as a second recogniser, so nothing it recognised stops being recognised. For the same reason, a hop count inside a clause that only the new rule recognises is still checked as a positive hop count too (as in Phase 16).
- "Names both researchers of a pair": the claim names at least two researchers (name runs plus cited names). Ambiguity from an unclear clause applies when the claim names two researchers or holds an absence span; a negation beside the pair with no listed predicate applies when it names two.
- Item b (ii) "in the evidence bundle" uses every bundle item (cited or not); item c "cited or bundled" likewise.
- Item e P16-L-14: the finding asks no status; it says the claim is "kept as partially_supported" against a depth-limited search. The weakest status the verifier uses for an over-claim is unsupported (AbsenceBeyondDepth is unbacked), so AbsenceNoDepth now also counts as unbacked (unsupported, not kept). Same reason code.
- Item e P16-L-15: unsupported with AbsenceNotSearched (an absence about researchers no search covered), the weakest over-claim status; no status was asked.
- Item d: "between YEAR and YEAR" now blocks verified even when it equals the window (the work order lists "between" as a qualifier). No fixture claim used it.
RISKS:
- Known fall in live use, not in any fixture: the deterministic edge-absence claim (Synthesis.scala:653, "In W, A and B have no direct coauthor edge.") has a negation beside the pair and no listed predicate, so it falls from verified to partially_supported (still kept, reason AbsenceAmbiguous; clause a / f). Asserted in AbsenceRuleSuite. Proposed later: recognise and check edge absence against `edge=none` items.
- Over-strict, fail closed: an absence synonym with a bounded hop count ("A and B are disconnected within 6 hops in W") stays conflicting against a complete search, because the Phase 16 positive hop check still sees "6 hops" (monotonicity forbids removing it). "within depth 6" wording is verified when the evidence supports it.
- The lists are English, fixed, and will miss unlisted wordings; such a wording next to a negation and the pair is ambiguous (never verified), but an absence stated with no negation word and no listed absence predicate is not recognised (as in Phase 16).
- Live impact: model absence claims that use unlisted words, "or", pronouns, a time qualifier, or no depth are no longer verified. The deterministic path-absence claim (Synthesis.scala:623 wording) stays verified (asserted).
- statesAbsence/absenceSpans now also return clauses the new rule recognises (used only by tests and this file).
MANUAL ACTION REQUIRED: none.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: R-1 (code-reviewer) and F-2 for this step; consumers W4a, W4b, W5 read INTERFACE HANDOFF (W5 reconciles docs/api.md reason codes: one new code, AbsenceAmbiguous).

Fix status (every item): fixed in code, tested offline, not validated live.

## Items (decision 6 and tier 2)

| Item | Finding | Status | Where | Test |
|---|---|---|---|---|
| 1a absence recognition by predicate and negation scope; unclear scope is ambiguous | P16-L-10 | done | ClaimVerification.scala:892-1268 (AbsenceRule lists, classify :1200), :586-614 (spans, ambiguity) | AbsenceRuleSuite "item a" tests (one per list class), "P16-L-10", "item a: an unclear negation scope" |
| 1b third names (capitalised, sentence-initial, lowercase bundle name parts; shared first name of the pair allowed) | P16-L-11 | done | :1053 Allowlist, :1256 isThirdName, :673 | "P16-L-11", "item f: sentence-initial", "item b: lowercase", "item b ii: shared first name" |
| 1c found path anywhere on it, cited or bundled | P16-L-12 | done | :618-640 (foundSet :625, hopsBetween :627), :698-699 | "P16-L-12" (2 tests) |
| 1d time qualifiers and differing ranges | P16-L-13 | done | :1153 TimeQualifierRe, :679-686 | "P16-L-13" |
| 1g closed vocabulary | (all) | done | :1011 FunctionWords, :1262 inVocabulary, :611-612 | "item g" (vocabulary, unknown lowercase participant "carlos", fixture correct claims stay verified) |
| 1f positive eligibility (conjunction of established conditions) | (all) | done | :641-710 (`established` at :650) | "item f" tests, "deterministic synthesis wording" |
| 1e no stated depth; unnamed people | P16-L-14, P16-L-15 | done | :678; :616-617 with :1122 UnnamedWords | "P16-L-14", "P16-L-15" |
| 2 monotonic with positive controls | - | done | VerifierOracle (all verifier suites), VerifierMonotonicSuite (fake benchmark + golden question) | "positive controls" test; oracle in 4 suites |
| 3 a failing-without-fix test per finding | - | done | rule-off run: 14 of 26 AbsenceRuleSuite tests fail | see TEST RESULTS |
| 4 INTERFACE HANDOFF | - | done | below | - |
| H-03 rule wording (tier 2) | H-03 | done | RetrievalEvalSet.scala:90 and the packaged JSON line 8; no behaviour change (targets identical, RetrievalEvalSetSuite and RetrievalEvalSuite pass) | RetrievalEvalSetSuite wording assertion |

## Finding inputs: Phase 16 status -> Phase 17 status (each asserted)

| Finding | Input (citing) | Phase 16 | Phase 17 | Reason added |
|---|---|---|---|---|
| P16-L-10 | "Alice Smith and Bob Jones are disconnected in 2015-2025." (found) and the "not in the same connected component", "unconnected", "in different components" forms | verified | conflicting | HopsConflict (+ AbsenceNoDepth, AbsenceNotSearched) |
| P16-L-11 | "Alice Smith is not connected to Bob Jones or to King within 6 hops in 2021-2023." (noneW1K) | verified | unsupported | AbsenceNotSearched, AbsenceAmbiguous |
| P16-L-12 | "Alice Smith and Bob Jones are not connected within 6 hops in 2021-2023." (noneW1K + path Alice-Bob-Carol in 2015-2025; also bundled, not cited) | verified | unsupported | AbsenceNotSearched (same path in 2021-2023: conflicting, HopsConflict) |
| P16-L-13 | "... not connected within 6 hops before 2021-2023." (noneW1K) | verified | unsupported | AbsenceNotSearched |
| P16-L-13 | "... in 2021-2023 or in the decade before." | verified | partially_supported | AbsenceAmbiguous |
| P16-L-14 | "No path connects Alice Smith and Bob Jones in 2021-2023." (noneW1K) | partially_supported | unsupported | (AbsenceNoDepth now unbacked) |
| P16-L-15 | "... or to any of his coauthors within 6 hops in 2021-2023." / "... have no common coauthors in 2021-2023." (noneW1K) | verified | unsupported | AbsenceNotSearched, AbsenceAmbiguous |

## Every status that fell, with its clause (from the `[status fell]` lines of the final run; 21 lines, all in AbsenceRuleSuite)

1-4. The four P16-L-10 inputs: verified -> conflicting (1a ii and 1a i "not in the same connected component"; 1c contradiction).
5. P16-L-11 input: verified -> unsupported (1b i; 1g "or").
6. P16-L-12 cited path: verified -> unsupported (1c).
7. P16-L-12 path in 2021-2023: verified -> conflicting (1c).
8. P16-L-12 bundled, uncited path: verified -> unsupported (1c "bundled").
9. P16-L-13 "before 2021-2023": verified -> unsupported (1d).
10. P16-L-13 "or in the decade before": verified -> partially_supported (1g).
11. P16-L-14: partially_supported -> unsupported (1e).
12-13. P16-L-15 inputs: verified -> unsupported (1e unnamed; 1a unclear scope; 1g).
14. "King, Alice Smith and Bob Jones are not connected ...": verified -> unsupported (1b i, sentence-initial).
15. "Alice Smith, Bob Jones and carol are not connected ...": verified -> unsupported (1b ii).
16. "Alice Smith, Bob Jones and carlos are not connected ...": verified -> partially_supported (1g).
17. "Alice Smith and Bob Jones have never met in 2021-2023.": verified -> partially_supported (1a/1f negation beside the pair, no listed predicate).
18-19. "Alice Smith and Bob Jones haven't collaborated in 2015-2025." (straight and curly apostrophe, found): verified -> conflicting (1a i contraction; 1c).
20. "There is no evidence that Alice Smith and Bob Jones are disconnected in 2021-2023.": verified -> partially_supported (1a unclear scope).
21. "In 2021-2023, Alice Smith and Bob Jones have no direct coauthor edge." (Synthesis.scala:653 wording, edge=none): verified -> partially_supported (1a/1f; see RISKS).

Existing fixtures: no fall. PathAbsenceSuite, ClaimVerificationSuite and GroundingSuite (through VerifierOracle), the fake benchmark's 30 questions and the golden question (59 claims, VerifierMonotonicSuite asserts the fall set is empty) keep their statuses. No existing affirmative connection claim fell. Positive controls ("Alice Smith and Bob Jones are not disconnected in 2015-2025." and "... are not only connected but frequent coauthors in 2015-2025.", each with a cited found path) keep verified with identical reasons.

No production limit, threshold, prompt, model ID or benchmark label changed.

## Prompt and model versions

Unchanged: no prompts/** change, no model ID change (fake model `fake-rule-template-v1` in the tests).

## Evaluation metrics

- Fake benchmark (BenchmarkRunnerSuite): completed; planted-error catch rate still asserted 1.0; exact-match rate 1.0 (suite passes).
- Monotonic corpus: 59 claims, 0 rose, 0 fell.
- Groundedness evaluation (group 4): not rerun (frozen data; not named for this step).

## Known grounding gaps

- Edge absence ("no direct coauthor edge") is not a checked class; it is ambiguous (partially_supported).
- Absence synonyms with "within N hops" stay conflicting against a complete search (Phase 16 positive hop check retained).
- AbsenceNotSearched is reused for third names, unnamed researchers, time qualifiers and non-contradicting found paths (exact for pair and window, loose for the others); a dedicated code is a proposed-later item.

## Proposed later

- An `edge=none` check for edge-absence claims, so the deterministic edge-absence wording can be verified again (needs its own rule and tests).
- Dedicated reason codes for third names and time qualifiers (API reference change).

## INTERFACE HANDOFF

- Interface change: one new reason code, additive: `Reasons.AbsenceAmbiguous` = "the claim may state that researchers are not connected, in wording the verifier cannot check" (ClaimVerification.scala:117; in `Reasons.all` at :144). It is an uncovered reason: at most partially_supported. For docs/api.md.
- Changed meaning, same code: `Reasons.AbsenceNoDepth` (:110) now makes the claim unsupported (unbacked), not partially_supported (P16-L-14). `Reasons.AbsenceNotSearched` (:112) is also given for a possible third name, unnamed researchers, a time qualifier, a range that differs from the window, and a found path in the bundle (not only cited) with both researchers anywhere on it. `Reasons.HopsConflict` (:88) is also given when such a path lies inside the claim's window and depth.
- Statuses: verified, partially_supported, unsupported, conflicting (unchanged enum).
- The rule as built (all in one place, `private[runtime] object AbsenceRule`, ClaimVerification.scala:892-1268):
  - Negations :905 (no, not, never, none, without, cannot, neither, nor, nothing, nobody; every word ending n't; curly apostrophes normalised :1166); NotEmphatic :909 (only, just, merely, simply after "not").
  - ConnectionWords :912 (connected, connects, connect, connection(s), path(s), linked, link(s), reachable, reach, reached, collaborated, collaborate(s), coauthored, co-authored, coauthors, co-authors, route(s), chain(s), joined); ConnectionPhrases :941 (same connected component, same component).
  - AbsenceWords :945 (disconnected, unconnected, unreachable, unlinked, isolated, separate, separated, apart); AbsencePhrases :957 (different [connected] component(s), another [connected] component).
  - ScopeWords :967 (words allowed between a negation and its predicate); FunctionWords :1011 (closed vocabulary, item g); Allowlist :1053 (never third names: sentence starters, months, metric and status words); UnnamedWords :1122; TimeQualifierRe :1153 (before, after, since, until, till, prior [to], between + year).
  - classify :1200 (scope rule), namesSomeone :1193, isThirdName :1256, inVocabulary :1262.
  - The eligibility conjunction: ClaimVerification.scala:582-711 (`if (absenceRule)` block; `established` list at :650).
- Signatures: `verify` and `verifyClaim` unchanged. New `private[runtime] def verifyClaimPhase16` (:336, the Phase 16 verdict, for tests only) and `private[runtime] def absenceClauses` (:213); `absenceSpans`/`statesAbsence` keep their signatures and now include the new rule's clauses. The Phase 16 phrase spans are `private def phraseSpans` (:196).
- Claims whose status fell: listed above (21, all new test inputs; none in existing fixtures, golden examples or the fake benchmark corpus).
- Test fixtures: VerifierOracle (test helper: `compare`, `verifyClaim`, `verify`, `rank`, `recorded`); AbsenceRuleSuite local facts alice/bob/carol, `found`, `noneW1K`, `carolW1`, `pathABC`, John Smith/John White.
- Thread-safety: the verifier stays a pure function; AbsenceRule holds immutable sets only. VerifierOracle's fall log is a ConcurrentLinkedQueue (tests only).
- Limits: none added. Cost per claim: one tokenisation, one classification per clause, one pass over the bundle items.
- H-03: `RetrievalEvalSet.Rule` text (RetrievalEvalSet.scala:90) and the packaged eval set's `rule` line now say the q25/q26 top-limit cut (default 10) is this set's derivation from the pool, not a benchmark label; targets unchanged.

## VALIDATION EVIDENCE

- 17:36:46 sbt -batch "rag/Test/compile": [success]
- 17:38:14 rag/testOnly PathAbsence, ClaimVerification, Grounding (through the oracle): Passed: Total 61, Failed 0; no `[status fell]` line
- rag/testOnly AbsenceRuleSuite, VerifierMonotonicSuite (first version): Passed: Total 26, Failed 0
- rag/testOnly 13 suites: Passed: Total 193, Failed 0
- services/testOnly GoldenExamplesSuite: Passed: Total 17, Failed 0
- rule switched off (temporary): Failed: Total 87, Failed 14, Passed 73 (the 14 are AbsenceRuleSuite finding and regression tests; PathAbsence 34, ClaimVerification 15, Grounding 12 all pass); restored at 17:43:24
- 17:44:08 FINAL sbt -batch "rag/testOnly AbsenceRuleSuite VerifierMonotonicSuite PathAbsenceSuite ClaimVerificationSuite GroundingSuite StagesSuite QueryWorkflowSuite RuntimeRepairsSuite RuntimeServiceSuite BenchmarkRunnerSuite AbstentionCodesSuite RetrievalEvalSetSuite RetrievalEvalSuite" (FQCNs): Passed: Total 193, Failed 0, Errors 0; [success]; "[monotonic corpus] claims=59 absence=0"; 21 `[status fell]` lines, all AbsenceRuleSuite inputs
- 17:44:26 FINAL sbt -batch "services/testOnly org.scholarchain.services.GoldenExamplesSuite": Passed: Total 17, Failed 0, Errors 0; [success]
files changed after these runs: none (last write ClaimVerification.scala 17:43:24 per ls -lT; every other file of mine earlier; the experiments/**, scripts/** and docs/** working-tree changes belong to other agents)
