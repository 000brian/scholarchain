# repair-E1-rag (Phase 17 session 3, step E1, code repair round; P17-A-01 = P17-L-01)

STATUS: COMPLETE
OWNER: rag-engineer
WORK ORDER: docs/agent-runs/phase-17/prompt.md (sha256 c22adfc57c7fde0cae63c8520393c2c81cd12280df7f5df773e70c2e2ec8ac24, checked with shasum), decisions 6 and 15, standing rules; E1 repair of P17-A-01 (P17-L-01, residual of P16-L-10) by the human's allow-list rule; start HEAD d8b23f24ef55fdd0fc602dc3fe17d07ecacd8eb2 (checked). Wave closer.
FILES CREATED: none
FILES MODIFIED:
- /Users/brian/Developer/repos/scholarchain/modules/rag/src/main/scala/org/scholarchain/rag/runtime/ClaimVerification.scala
- /Users/brian/Developer/repos/scholarchain/modules/rag/src/test/scala/org/scholarchain/rag/runtime/AbsenceRuleSuite.scala
- No change to prompts/**, golden files, limits, thresholds, defaults or benchmark labels.
COMMANDS RUN:
- shasum -a 256 (work order); git rev-parse HEAD; git status --short; git diff --stat; ls; grep; wc; head; tail -n; cut
- sbt -batch "rag/testOnly ..." 5 times: a first run with a compile error in my test (missing import), a development run (1 failure, a bug in my test), switched-off run 1 (gate off), switched-off run 2 (gate and list additions off), the final run
- sbt -batch "services/testOnly org.scholarchain.services.GoldenExamplesSuite"
- sbt check (in the background, output to a scratchpad log; read with tail -n and grep after the completion notice)
- sbt -batch packageCheck
- No sbt fmt run (the PostToolUse formatter ran on each edit; sbt check's format gate passed). No sbt clean, runMain, AwsTest, EmulatorTest, network or model call.
TEST RESULTS:
- Final targeted rag run, 13 suites (AbsenceRuleSuite, VerifierMonotonicSuite, PathAbsenceSuite, ClaimVerificationSuite, GroundingSuite, StagesSuite, QueryWorkflowSuite, RuntimeRepairsSuite, RuntimeServiceSuite, BenchmarkRunnerSuite (the fake benchmark), AbstentionCodesSuite, eval.RetrievalEvalSetSuite, eval.RetrievalEvalSuite): Passed: Total 208, Failed 0, Errors 0; "[monotonic corpus] claims=59 absence=0".
- services GoldenExamplesSuite: Passed: Total 17, Failed 0, Errors 0. No golden expected status changed, so no BLOCKED-ON service-engineer.
- Switched-off run 1 (the E1 gate disabled with `false &&`, lists kept): AbsenceRuleSuite Failed 3 of 41 ("E1 P17-K-02 ...", "E1: the gate itself catches ...", "E1: each accepted connection form needs a cited found path ...").
- Switched-off run 2 (gate disabled and the added list words renamed to non-words): AbsenceRuleSuite Failed 9 of 41, including "E1 P17-A-01: the reviewer's three residual inputs are not verified", "E1 P17-K-02 ...", "E1: the gate itself ...", "E1: the extended lists ...", "E1: each accepted connection form ...". The 3 list-iterating step 1 tests also failed, a side effect of the renamed words (mixed case). Everything was restored with Edit; I checked with grep that no "OFF" word and no `false &&` was left before the final runs.
- sbt check: [success], Total time 1082 s, exit=0; module tests suites=150 executed=2352 passed=2352 failed=0; marker module_tests=2352, contract_tests=399.
- sbt -batch packageCheck: "packageCheck: ok", [success], exit=0.
VALIDATION RESULTS:
- Groundedness evaluation: the work order names none for this round. Group 4 runs on frozen data and was not rerun.
- Monotonic: VerifierOracle (no status rose against the Phase 16 baseline, every Phase 16 reason kept, `checked` unchanged) passes in every verifier suite. VerifierMonotonicSuite: 59 corpus claims (fake benchmark plus the golden question), 0 falls, `expectedFalls` still empty.
- Positive controls: "Alice Smith and Bob Jones are not disconnected in 2015-2025." and "... are not only connected but frequent coauthors in 2015-2025." (cited found path) stay verified with the same reasons (existing tests :299 and :412 pass). They are now verified only because they match an accepted connection form and a cited found path connects the pair.
ASSUMPTIONS:
- "Names both researchers of a pair" means that the claim names two or more researchers (`claimNamed.size >= 2`: name runs plus cited full names). This is the broadest reading, so it fails closed. A form can match only when the two names resolve to exactly two researcher IDs (`claimPairNamed`).
- "Any word from the negation or absence lists" means any token in `Negations` (or ending in n't), any token in `AbsenceWords`, or any `AbsencePhrases` sequence anywhere in the claim text. Quoted titles, stable IDs and dblp keys are blanked first (the verifier's `stripped` text). Uncertainty phrases are NOT blanked, so "did not complete" counts, which is why the uncertainty wording needed its own form.
- "Whole claim matches" means the form matches after curly apostrophes are made straight, whitespace is collapsed, case is ignored and one final period is optional. Commas, semicolons and parentheses must match exactly.
- The reason code for "not an accepted form" is the existing `AbsenceAmbiguous` (no API change for that case). The one new code `ConnectionNotFound` is for an accepted connection form with no cited found path.
RISKS:
- The gate also lowers true claims that are not forms, for example "Alice Smith and Bob Jones are not in different components in 2015-2025." and "... are not disconnected within 2 hops in 2015-2025." (now partially_supported). Fail closed, as the rule requires.
- The new words in `Negations` ("zero") and `AbsenceWords` ("missing", "nonexistent", "non-existent", "strangers", "stranger") also feed the step 1 recognizer. A claim naming ONE researcher with such a word (for example "Alice Smith has 3 publications missing venue data") may now be read as an absence clause and become unsupported (AbsenceNotSearched). The fixtures, golden examples and fake benchmark have no such claim (0 falls), but live answers might.
- The rule is tied to the lists (the human's design). See "Known grounding gaps".
MANUAL ACTION REQUIRED: none. docs/api.md needs the new reason code `ConnectionNotFound` (interface: yes), routed by the orchestrator to documentation-engineer.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: the focused code re-check of P17-A-01/P17-L-01 and P17-K-02 (code-reviewer); docs/api.md gets the new reason code.

Fix status: fixed in code, tested offline, not validated live.

## P17-A-01 (P17-L-01): the three reviewer inputs

| Input (evidence) | Phase 16 | Before E1 | Now | Reasons added by Phase 17 (run output) |
|---|---|---|---|---|
| "Alice Smith and Bob Jones have a path that is missing in 2015-2025." (cited found path) | verified | verified | conflicting | HopsConflict, AbsenceAmbiguous, ConnectionUnconfirmed |
| "Alice Smith and Bob Jones collaborated zero times in 2015-2025." (edge, 3 joint publications) | verified | verified | partially_supported | AbsenceAmbiguous, ConnectionUnconfirmed |
| "A path linking Alice Smith and Bob Jones is nonexistent in 2015-2025." (cited found path) | verified | verified | conflicting | HopsConflict, AbsenceAmbiguous, ConnectionUnconfirmed |

Trace: each claim names two researchers and contains a list word ("missing", "zero", "nonexistent", all added in E1). It matches no accepted form, so gate (i) adds AbsenceAmbiguous (at most partially_supported). Inputs 1 and 3 also have an absence predicate next to the connection predicate "path" in one clause. The step 1 rule reads such a clause as Unclear with `mayStateAbsence`, and no clause affirms a connection, so the P17-A-04 check finds the cited 2-hop path inside 2015-2025 and adds HopsConflict. Input 2: "zero" after "collaborated" is a dangling negation, so the clause is Unclear (not affirmed); (h) adds ConnectionUnconfirmed. Test: AbsenceRuleSuite.scala:522. It fails without the fix (switched-off run 2). With only the gate switched off, the list additions alone still catch these three inputs (run 1). The gate is the general part (see the :547 test, which fails in run 1).

## P17-K-02: closed for both inputs (the class stays open)

| Input (cites noneW1K only) | Phase 16 | Before E1 | Now | Reasons added (run output) |
|---|---|---|---|---|
| "Alice Smith, Bob Jones and King are strangers in 2021-2023." | verified | verified | unsupported | AbsenceThirdName, AbsenceNoDepth, AbsenceAmbiguous |
| "Alice Smith and Bob Jones are strangers in 2021-2023." | verified | verified | unsupported | AbsenceNoDepth, AbsenceAmbiguous |

Trace: "strangers" is now an absence predicate. The clause names someone, so it reads as Absence and becomes an absence span. Rules (a) to (g) then run: no stated depth (AbsenceNoDepth, unbacked), and "King" is capitalised and outside the allowlist, so it is a possible third name (AbsenceThirdName). The gate adds AbsenceAmbiguous. Test: AbsenceRuleSuite.scala:535 (fails in both switched-off runs). Both inputs are closed. The class is not: wording with no word from any list (for example "unacquainted") and no found path in the bundle is still not recognised (see Known grounding gaps).

## Fixture claims whose status fell in this round (compared with the code before E1)

1. "Alice Smith and Bob Jones are strangers in 2015-2025." (cited found path): partially_supported -> conflicting. "strangers" is now an absence predicate (item 1a ii), so the found path contradicts it (item 1c, HopsConflict). Test assertion updated at AbsenceRuleSuite.scala:375.
2. The same claim citing the alice-bob edge: partially_supported -> conflicting (same reason).
3. "Alice Smith, Carol King and Bob Jones are strangers in 2015-2025." (found): partially_supported -> unsupported. It is now an absence span with three named researchers, so AbsenceNotSearched is added, plus AbsenceAmbiguous from the gate. The test asserts only "not verified".
- No fixture claim pinned as verified fell (every such assertion passes). The 59 corpus claims (fake benchmark plus golden question) did not fall. The golden files are unchanged.
- Not compared separately: claims pinned only as "not verified" may have moved between non-verified statuses.
- New E1 test inputs that fall from their Phase 16 verified status, by design: the three gate inputs (:547, now partially_supported, reasons exactly [AbsenceAmbiguous]); the list-word suffix inputs (:562); the connection forms with no found path (:620, now unsupported with ConnectionNotFound); the near misses (:670).

## Prompt and model versions

Unchanged: no prompts/** change, no model ID change.

## Evaluation metrics

- Fake benchmark (BenchmarkRunnerSuite): passes; its assertions are unchanged.
- Monotonic corpus: 59 claims, 0 rose, 0 fell.

## Known grounding gaps (known limitations after this last code round)

- The rule depends on the lists (the human's design). An absence meaning with no list word is not gated:
  - With a found path in the bundle, (h) still checks only the words before the predicate. Example: "Alice Smith and Bob Jones have a path that vanished in 2015-2025." citing `found` stays verified (the same class as P17-A-01, with an unlisted word after the predicate).
  - With no found path, the claim is not recognised at all (the P17-K-02 class). Example: "... are unacquainted in 2021-2023."
  - These follow from reasoning over the code; I did not run them.
- A claim that refers to the pair by pronoun ("they are not connected ...") is not gated: it does not name two researchers.
- Edge absence (edge=none) is still not a checked class (P17-A-02); the Synthesis edge=none wording stays partially_supported.

## Proposed later

- Apply the closed-vocabulary check to every word of an affirmed clause, as the reviewer suggested in review-report-02.md:77. That would cover the "vanished" example. It is not built because it is not in this work order.

## INTERFACE HANDOFF

- Files changed: ClaimVerification.scala, AbsenceRuleSuite.scala.
- The rule, gate (i): ClaimVerification.scala:797-841. If `namesTwo && AbsenceRule.hasNegationOrAbsence(stripped)` (:802), then `AbsenceRule.matchForm(text, pairNames)`:
  - None: `uncovered += AbsenceAmbiguous` (:804), so the claim is at most partially_supported.
  - AbsenceForm: needs a cited `path=none` item for exactly the pair ID set, with window == the form's window. Missing: AbsenceNotSearched. Not complete: AbsenceBoundedSearch. maxDepth below the form's depth: AbsenceBeyondDepth. Any found path (pair anywhere on it) or edge in the bundle: AbsenceFoundPath. All of these are unbacked (unsupported).
  - ConnectionForm: needs a cited found path (pair anywhere on it) or edge whose window lies inside the form's window. Otherwise ConnectionNotFound (unbacked, unsupported).
  - UncertainForm: needs a cited `path=none` item for the pair that is not complete and has depthSearched == the form's depth. Otherwise AbsenceNotSearched (unbacked).
  - The gate only adds reasons. `checked` is never changed.
- The accepted forms, in ONE place: `AbsenceRule.AcceptedForms`, ClaimVerification.scala:1543 (8 forms; `{A}`/`{B}` = the pair's names in either order, `{N}` = a depth of 1-3 digits, `{W}` = YYYY-YYYY or YYYY–YYYY, optionally prefixed `w` or `full-`):
  1. Absence: "{A} and {B} are not connected within {N} hops in {W}."
  2. Absence: "{A} is not connected to {B} within {N} hops in {W}."
  3. Absence: "No coauthorship path between {A} and {B} exists within {N} hops in {W}."
  4. Absence: "No path between {A} and {B} exists within depth {N} in {W}."
  5. Absence: "In {W}, no coauthorship path between {A} and {B} exists within depth {N} (the bounded search completed)." (Synthesis.scala:623)
  6. Connection: "{A} and {B} are not disconnected in {W}." (decision 6.2 control)
  7. Connection: "{A} and {B} are not only connected but frequent coauthors in {W}." (decision 6.2 control)
  8. Uncertain: "The path search between {A} and {B} did not complete after depth {N}; whether a path exists is unknown."
- Lists:
  - `Negations` (:1047) adds "zero". "lack", "lacks", "lacked", "lacking", "nothing" and "nobody" were already there.
  - `AbsenceWords` (:1105) adds "missing", "nonexistent", "non-existent", "strangers", "stranger". "absent" was already there.
  - `hasNegationOrAbsence` (:1579) also matches every `AbsencePhrases` sequence. "distinct" or "different" alone is not a trigger.
- New public API in `private[runtime] object AbsenceRule`: `sealed trait FormKind` (:1520), with `AbsenceForm`, `ConnectionForm`, `UncertainForm`; `final case class AcceptedForm(kind, template)`; `val AcceptedForms`; `final case class FormMatch(form, depth: Option[Double], window: Option[(Int, Int)])`; `def hasNegationOrAbsence(text: String): Boolean`; `def matchForm(text: String, pairNames: List[String]): Option[FormMatch]` (:1595). `verify` and `verifyClaim` are unchanged.
- Reason codes: one new additive code, `Reasons.ConnectionNotFound` = "the claim states that researchers are connected, but no cited found path or edge connects them in its window" (:135, in `Reasons.all` :168; unsupported). Interface: yes, so docs/api.md needs it. The not-a-form case reuses `AbsenceAmbiguous`.
- Tests: AbsenceRuleSuite.scala :522, :535, :547, :562, :580, :595, :620, :661, :670 (new), and :375 (updated: the "strangers" falls). Fixtures added: `edgeAB` (alice-bob, Full, 3 joint publications) and `stoppedFull` (path=none, Full, not complete, depthSearched 2, ResourceLimit).
- Thread-safety: pure functions over immutable values. Each matchForm call builds and compiles at most 16 small regexes (8 forms times 2 name orders), and only for gated claims.
- Limits: none added or changed.

## VALIDATION EVIDENCE

- 23:20:37 switched off (gate only): sbt -batch "rag/testOnly org.scholarchain.rag.runtime.AbsenceRuleSuite": Failed: Total 41, Failed 3
- 23:21:08 switched off (gate and list additions): same command: Failed: Total 41, Failed 9 (includes the P17-A-01 and P17-K-02 input tests); restored with Edit
- 23:22:05 FINAL sbt -batch "rag/testOnly <13 suites listed under TEST RESULTS>": Passed: Total 208, Failed 0, Errors 0; [success]; "[monotonic corpus] claims=59 absence=0"
- 23:22:26 FINAL sbt -batch "services/testOnly org.scholarchain.services.GoldenExamplesSuite": Passed: Total 17, Failed 0, Errors 0; [success]
- 23:40:39 sbt check: [success] Total time: 1082 s; exit=0; module tests executed=2352 passed=2352 failed=0
- 23:41:00 sbt -batch packageCheck: packageCheck: ok; [success]; exit=0
- head -n 5 .claude/.last-test-run: timestamp=2026-11-23T05:40:38.848826Z, module_tests=2352, contract_tests=399
- git status --short after the runs: only the two files above are modified
files changed after these runs: none (my last source edit was the reason-code assertion added to AbsenceRuleSuite, before the 23:22:05 run)
