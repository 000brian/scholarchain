[Saved verbatim by the orchestrator from code-reviewer's final message (step 7 code re-check).]

# review-report-02.md (Phase 17 session 2, step 7: code re-check after repair-01-rag)

Reviewer: code-reviewer. I used only the Read, Grep and Glob tools.

Work order: docs/agent-runs/phase-17/prompt.md, decisions 6 and 15. The sha256 c22adfc5…ec8ac24 is as the orchestrator gave it; I could not recompute it because I have no Bash.

Inputs read: repair-01-rag.md, review-report-code.md (R-1), review-report-final-code.md (F-2), diff-step7-rag.txt (file list, the RagIngestJob hunks and every removed line), files-step7.txt, and the current working-tree files listed under Coverage.

## VERDICT: REQUEST_CHANGES (code slice)

- **P17-A-01 is PARTIAL and still major.**
  - The structural rule (h) checks only the words *before* the first affirmative predicate of a clause.
  - So an absence meaning carried by unlisted words *after* a connection predicate is still `verified` when a cited found path or edge contradicts it.
  - Under decision 6's fail-closed rule, **P16-L-10 is not closed**.
- **P16-L-11 is closed** for absence claims the rule recognises. There is a residual for wording the rule does not recognise (P17-K-02, minor).
- **F-2's slice APPROVE no longer stands.** It was conditional on R-1 finding P16-L-10 and P16-L-11 closed, and P16-L-10 is still open.
- **The overall final review stays REQUEST_CHANGES regardless**, because F-1's constitutional major P17-F-01 is open and is not repaired in this phase (decision 1).

## Per-finding re-check

| Finding | Re-check | Evidence |
|---|---|---|
| P17-A-01 (major) | **PARTIAL** (major remains) | See the traces below. |
| P17-A-02 (minor, doc only) | RESOLVED for the code slice; documentation pending W5 | No code change, by design. AbsenceRuleSuite.scala:340-356 pins the edge=none fall (partially_supported, reasons exactly [AbsenceAmbiguous], kept). repair-01-rag.md:40 and :113 disclose it for docs/api.md. |
| P17-A-03 (minor) | RESOLVED | AbsenceRuleSuite.scala:440-456 pins both R-1 inputs: Phase 16 verified, now partially_supported, kept, reasons exactly [AbsenceAmbiguous]. Traced input 1: the ";" clause "they are connected within 2 hops in 2015-2025" passes `affirms`, because the prefix words "they" and "are" are in Allowlist/FunctionWords. So (h) adds no ConnectionUnconfirmed and the reasons stay exactly [AbsenceAmbiguous]. Disclosed in repair-01-rag.md:36 and :113. |
| P17-A-04 (minor) | RESOLVED | ClaimVerification.scala:768-789. See the trace below. Test: AbsenceRuleSuite.scala:458-474 (the owner's switched-off run reported that this test fails). |
| P17-A-05 (minor) | RESOLVED; additive | See the notes below. |
| P17-F-12 (minor) | RESOLVED for the named lines | See the notes below. The :340 path is not in the finding's lines; I record it as P17-K-01 (minor). |

### P17-A-01: hand traces

**R-1 input 1, "Alice Smith and Bob Jones lack any coauthorship path in 2015-2025." (citing `found`):**
- "lack" is now a negation (:1004).
- "any" (:1081) and "coauthorship" (:1105) are scope words, so the negation governs "path" tightly (:1362).
- The clause reads as Absence (:1383) and becomes an absence span (:616).
- `hopsBetween` gives 2 (:654-657), `found`'s window lies inside 2015-2025, and no depth is stated.
- Result: HopsConflict (:717), **conflicting**.

**R-1 input 2, "... are in distinct components in 2015-2025.":**
- "distinct components" is now an AbsencePhrase (:1072), so the clause reads as Absence.
- The same contradiction follows: **conflicting**.
- Test: AbsenceRuleSuite.scala:360-372.

**Three wordings of my own that (h) catches:**

| Claim (citing `found`) | Why it is caught | Status |
|---|---|---|
| "Alice Smith and Bob Jones have zero paths in 2015-2025." | "zero" comes before the predicate "paths" and is outside the vocabulary, so `affirms` is false (:1325). The clause is Connection, not Neutral, so `quantity` is false (:761). ConnectionUnconfirmed (:762). | partially_supported |
| "Alice Smith and Bob Jones are connected by a path that never existed in 2015-2025." | "never" follows every predicate (dangling, :1376), so the clause is Unclear and not affirmed. AbsenceAmbiguous and ConnectionUnconfirmed. | partially_supported |
| "Alice Smith and Bob Jones are devoid of any coauthorship path in 2015-2025." | The prefix holds "devoid". | partially_supported (the suite's own input, :378) |

**Three wordings of my own that still come out verified (the open class):**

1. **"Alice Smith and Bob Jones have a path that is missing in 2015-2025."**, citing `found` (AbsenceRuleSuite.scala:25-26).
   - Phase 16 part: no AbsencePhraseRe match (:186-194), so `spans` is empty. The two name runs are checked (:404-407). 2015-2025 is `found`'s window (:833). checked = 3.
   - Phase 17 part: there is one clause (:232-238). Its only predicate is "path" (token 7), with no negation, so the clause is Connection (:1384). `allSpans` is empty, so no rule (a)–(g) fires (:625-631, :660).
   - Rule (h): `connectedPairs` = {A,B} (:744-748). `affirms` checks only `tokens(clause).take(7)` = alice smith and bob jones have a. These are name parts, FunctionWords "and" and "have" (:1138, :1151), and ScopeWord "a" (:1078), so `affirmed` = true (:758). Nothing is added.
   - Result: **verified** (:935), although the claim says the path does not exist and the cited path contradicts it.
2. **"Alice Smith and Bob Jones collaborated zero times in 2015-2025."**, citing the suite's alice–bob `EdgeFact` (AbsenceRuleSuite.scala:394-395: 3 joint publications, window FullW).
   - "collaborated" is a ConnectionWord at token 5, and the prefix is only name parts and "and", so the clause is affirmed. "zero" is not a number literal (NumberFormats.scala:36-37).
   - The names and the range check out. Result: **verified** against the evidence of 3 joint publications.
   - This is the same class as the suite's own contracted-negation case (:277-285), which the rule makes conflicting.
3. **"A path linking Alice Smith and Bob Jones is nonexistent in 2015-2025."**, citing `found`.
   - The predicate "path" is token 1, and the prefix is "a" (ScopeWords). "A" is not a name run, because nameRuns needs at least two capitalised tokens (RulePlanner.scala:189-208).
   - Result: **verified**.

**Cause:** `AbsenceRule.affirms` (ClaimVerification.scala:1323-1326):
```scala
r.kind == Connection && r.affirmAt.exists(k => tokens(clause).take(k).forall(known))
```
Words after `affirmAt` are never checked against the closed vocabulary, so the rule still depends on the lists (Negations and AbsenceWords) for any wording placed after the predicate.

- The repair's disclosed residual (repair-01-rag.md:38) covers only a contradiction in *another* clause. This same-clause case is not disclosed.
- Decision 6 item 1 asks for a rule that "does not depend on listing every phrasing". It is not met for this class.
- Possible direction (owner's choice): apply `inAffirmVocabulary` to every word of the affirmed clause, not only the prefix. The listed positive controls appear to survive this, because all words of the Synthesis path template and of "connected within 2 hops in 2015-2025" are in the vocabulary. I have not verified this by running anything.
- Severity: major, the same false `verified` as P16-L-10 (cited evidence contradicts a claim shown as verified).
- Owner: rag-engineer. Interface: no.

### P17-A-04: hand trace

Input: "Alice Smith and Bob Jones, in 2015-2025, are disconnected."
- The commas split it into three clauses. The third, "are disconnected", names no one, so `read` gives Unclear with `mayStateAbsence` = true (:1378, :1383).
- No clause is affirmed, so the check at :768-789 runs. The window comes from the claim's one range (:551-557), `found` lies inside it, and no depth is stated.
- Result: HopsConflict, **conflicting**, not kept.
- The colon input gives the same result.
- The path=none-only control stays partially_supported.

### P17-A-05: notes

- Five new fixed-text codes are defined at :121-132 and listed in `Reasons.all` at :159-163. None contains a digit, and no code is duplicated (test :476-494).
- **Additive against Phase 16:**
  - The Phase 16 code paths (:560-598) still give AbsenceNotSearched unchanged.
  - The new codes appear only in the Phase 17 block: :636, :693, :700-706, :718 and :762.
  - VerifierOracle.scala:52 fails any test in which a Phase 16 reason goes missing.
- Status classes are unchanged: the four Absence* codes go to `unbacked`, which means unsupported, as AbsenceNotSearched did for those causes. ConnectionUnconfirmed goes to `uncovered`, which means at most partially_supported.
- No consumer enumerates reason codes besides tests (Grep of modules/**).
- Interface: yes, so docs/api.md needs these codes (W5).

### P17-F-12: notes

- `failureText` (:316-319) keeps the message only for an exact `RuntimeException` or an `ExportSourceException`. It is used for the run status at :657.
- The copy outcome is now the class name only (:722).
- An AWS SDK or store exception (`StoreException`, `SdkException`) is a subclass, not an exact `RuntimeException`, so only its class name is recorded.
- ExportSourceException messages are validation text only (ExportSource.scala:31-255; Grep shows no getMessage wrapping).
- Test: RagIngestJobSuite.scala:504-592 asserts both outputs and that the host string is absent from run-report.json, run-report-copy.json and the printed lines. The owner's switched-off run reported that it fails without the fix.

## New findings

| ID | Sev | File:line | Failure input | Impact | Owner | Interface |
|---|---|---|---|---|---|---|
| P17-K-01 | minor | modules/rag/src/main/scala/org/scholarchain/rag/ingest/RagIngestJob.scala:340, which reaches the status through :481-482 (`sys.error(e)`, an exact RuntimeException) and is kept by `failureText` :317-318 | `--stores aws`, where `AwsStorageEnvironment.fromConfig` throws while it builds the SDK clients | The text "failed: RuntimeException: AWS stores could not be built: <SDK exception message>" goes to run-report.json and stdout, so P17-F-12's class-only rule is not applied on this path. Scope judgement: outside P17-F-12 as written (the finding cites :642-660), but the same class. Exposure is low: client building makes no network call (AwsStorageEnvironment.scala:262-267; RagIngestJob.scala:321-322), so endpoint hosts are unlikely, and bucket names and account IDs are masked. The owner already lists it as proposed later (repair-01-rag.md:95). Acceptable to record without repair. | rag-engineer | no |
| P17-K-02 | minor | ClaimVerification.scala:625-636 and :660 (rules a–g run only when `allSpans` is non-empty); :749 ((h) runs only when a found path or edge exists) | (1) "Alice Smith, Bob Jones and King are strangers in 2021-2023." citing `noneW1K` only: every clause is Neutral, `allSpans` is empty, and there is no found item, so the result is **verified** (Phase 16: also verified). "King" is never checked as a third name, because nameRuns needs two or more tokens. (2) "Alice Smith and Bob Jones are strangers in 2021-2023." citing `noneW1K`: **verified**, an absence claim with no stated depth checked against a search limited to depth 6 (the P16-L-14 class). | Unrecognised absence wording with no found path skips items b, d, e and g. The owner discloses this under known grounding gaps (repair-01-rag.md:89), and it depends on the recognition gap of P17-A-01. Not a regression: no status rose. Recorded as minor because input (1) also hits the verifier's general single-word-name limit outside absence claims. The human may weigh it with P17-A-01 at E1. | rag-engineer | no |

## Decision 6 fail-closed statement

- **P16-L-10: NOT closed.** The four R-1 inputs and the two P17-A-01 inputs are fixed. The class "absence wording outside the lists, contradicted by a found path" is still open for wording after an affirmative predicate (P17-A-01 PARTIAL, three traced inputs above).
- **P16-L-11: closed** for absence claims the rule recognises.
  - Failure input: "Alice Smith is not connected to Bob Jones or to King within 6 hops in 2021-2023." gives **unsupported** with AbsenceThirdName (:692-693). "or" is outside the vocabulary, so AbsenceAmbiguous is added too. Test: AbsenceRuleSuite.scala:146-153.
  - The lowercase, sentence-initial and other-bundle-name variants are covered at :241-267.
  - Residual for unrecognised wording: P17-K-02 (minor).
- **Consequence:** under decision 6, the final reviewer cannot return APPROVE while P16-L-10 is open.

## Checklist

- **Monotonic:** checked, none.
  - Rule (h) only appends to `uncovered` (:762) and `conflicts` (:787). The (a)–(g) block only appends (:723-729). `checked` is never touched. The status function (:930-938) is monotone in those sets.
  - VerifierOracle.scala:51-53 enforces this, and the oracle is used in AbsenceRuleSuite and the other verifier suites.
  - VerifierMonotonicSuite (`expectedFalls` = empty, :23 and :53) passed with 59 claims, as reported by the owner.
  - The positive controls (the two step 1 controls, the 2-hop connection claim, and the Synthesis path and edge templates) stay verified with identical reasons (AbsenceRuleSuite.scala:411-438). I traced the path template: clause 1 is affirmed ("by", "a", "shortest", "coauthorship", "path", "of", "2", "hops" are all in the vocabulary).
- **No prompt, limit, threshold, default or golden change:** checked, none.
  - The diff touches only the rag files in files-step7.txt. There is no prompts/** or services resource change.
  - A Grep of the diff's changed lines for Max*, Threshold, Limit, MinFree and timeout finds no match.
  - The eval-set JSON and RetrievalEvalSet.scala change only the rule wording (diff lines 1-26, H-03, already reviewed in R-1).
  - The removed lines in ClaimVerification are refactor only (the window and claimPair logic moved into `windowOf` and `claimPairNamed`).
  - The owner reports GoldenExamplesSuite 17/17 with no expected status changed. Not rerun by me.
- **Each fix has a test that fails without it:**
  - A-01 (h): :374. The owner's switched-off run reported that it fails.
  - A-01 lists: :360 and :402. I traced that :360 would come out partially_supported, not conflicting, without "lack" in Negations.
  - A-04: :458.
  - A-05: :152, :164-165, :201-202 and :476-494 assert the new codes.
  - F-12: RagIngestJobSuite :504-592.
  - A-03 and A-02: pinning tests only; no code change was required.
  - **Gap:** no test covers a same-clause absence wording after the predicate (P17-A-01 residual).
- **Secrets and identifiers in the diff:** checked, none. The test host is `probe-host-leak.invalid`.

## Coverage

- **Read in full:**
  - AbsenceRuleSuite.scala (1-496), VerifierOracle.scala (1-77), VerifierMonotonicSuite.scala (1-57).
  - ClaimVerification.scala 80-1453 (Reasons, evaluate, the absence block, rule (h), the status function, the whole AbsenceRule object).
  - repair-01-rag.md, review-report-code.md, review-report-final-code.md, files-step7.txt.
- **Read in part:**
  - RagIngestJob.scala:295-344, :455-554 and :630-725.
  - RagIngestJobSuite.scala:500-597.
  - diff-step7-rag.txt: lines 1-90, plus a Grep of every removed line and every file header.
  - RulePlanner.scala:189-218 (nameRuns); NumberFormats.scala:36-37.
  - ExportSource.scala:205-224 and a Grep of its ExportSourceException constructions.
  - AwsStorageEnvironment.scala:262-267; a Grep of storage getMessage uses.
  - prompt.md:194-205 and :300-308.
- **Not read:**
  - The ClaimVerification diff hunks individually (I read the final file instead).
  - The other test-file diff hunks: ClaimVerificationSuite, GroundingSuite, PathAbsenceSuite, RetrievalEvalSetSuite.
  - review-report-final-control.md (for context only; P17-F-01 taken as stated).
  - diff-R-1.txt (not needed once the final file was read).
- **Not run:** every test result above is as the owner reported it. I have no Bash.

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: docs/agent-runs/phase-17/prompt.md (sha256 c22adfc57c7fde0cae63c8520393c2c81cd12280df7f5df773e70c2e2ec8ac24 as given, not recomputed), Phase 17 session 2 step 7, decisions 6 and 15 (code re-check, review-report-02.md)
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools)
TEST RESULTS: none run; owner-reported runs relied on where noted (rag 228/228, GoldenExamplesSuite 17/17, sbt check success, switched-off run 3/56 failed)
VALIDATION RESULTS: P17-A-01 PARTIAL (major remains); P17-A-02 RESOLVED (docs pending W5); P17-A-03, A-04, A-05, P17-F-12 RESOLVED; new P17-K-01 (minor), P17-K-02 (minor); P16-L-10 NOT closed; P16-L-11 closed for recognised absence claims
ASSUMPTIONS: AbsenceRuleSuite's `found` and EdgeFact fixtures give a verified names-plus-range check for my inputs, as they do for the suite's own inputs with the same evidence and window (:383-384, :396-397); only the predicate words differ
RISKS: P16-L-10 stays open for the final gate unless the human accepts the residual at E1; the overall final review stays REQUEST_CHANGES while P17-F-01 is open
MANUAL ACTION REQUIRED: human decision at E1 on P17-A-01 (session 2 allows no second repair round): repair in session 3, or accept as a known limitation (accepting it does not change the verdict, per the work order)
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: step 8 documentation triage; P17-A-01 residual and P17-K-01/K-02 go to E1; W5 adds the five new reason codes and the "failed: CLASS" status text to docs/api.md
```

VERDICT: REQUEST_CHANGES (code slice: P17-A-01 PARTIAL, major remains. Overall final review: REQUEST_CHANGES, P17-F-01 also open.)

| Severity | File:line | Failure input | Impact | Owner |
|---|---|---|---|---|
| major (P17-A-01, PARTIAL) | modules/rag/src/main/scala/org/scholarchain/rag/runtime/ClaimVerification.scala:1323-1326 (`affirms` checks only the prefix), :758-762 | "Alice Smith and Bob Jones have a path that is missing in 2015-2025." (found); "Alice Smith and Bob Jones collaborated zero times in 2015-2025." (edge, 3 joint publications); "A path linking Alice Smith and Bob Jones is nonexistent in 2015-2025." (found) | A false verified on an absence claim the cited evidence contradicts; P16-L-10 stays open | rag-engineer |
| minor (P17-K-01) | modules/rag/src/main/scala/org/scholarchain/rag/ingest/RagIngestJob.scala:340, :481-482, :317-318 | `--stores aws`, where the SDK client build throws | The SDK message goes to the run status, against the P17-F-12 rule; low exposure | rag-engineer |
| minor (P17-K-02) | modules/rag/src/main/scala/org/scholarchain/rag/runtime/ClaimVerification.scala:625-636, :660, :749 | "Alice Smith, Bob Jones and King are strangers in 2021-2023." (noneW1K only); "Alice Smith and Bob Jones are strangers in 2021-2023." (noneW1K only) | Unrecognised absence wording with no found path skips the third-name and depth checks; verified (as in Phase 16, so no regression); disclosed known gap | rag-engineer |
