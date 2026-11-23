## Focused code re-check: E1 rag repair (P17-L-01 / P17-A-01, P17-K-02)

This is a read-only review of the uncommitted diff on HEAD 95a8d9f. It covers two files:
- /Users/brian/Developer/repos/scholarchain/modules/rag/src/main/scala/org/scholarchain/rag/runtime/ClaimVerification.scala
- /Users/brian/Developer/repos/scholarchain/modules/rag/src/test/scala/org/scholarchain/rag/runtime/AbsenceRuleSuite.scala

I traced every result below by hand through the code. I ran nothing, because I have no Bash.

### 1. P17-A-01 (P17-L-01): RESOLVED against the human's rule as written

**The gate.** It is at ClaimVerification.scala:802-841. When `namesTwo && hasNegationOrAbsence(stripped)` is true, `matchForm(text, pairNames)` runs:
- If it returns None, the gate adds `AbsenceAmbiguous` as an uncovered reason. The claim is then at most PARTIALLY_SUPPORTED.
- If it returns Some, the gate runs that form's evidence checks.

**The accepted forms.** They are in one place, `AbsenceRule.AcceptedForms` at :1543-1567, eight templates in total. `matchForm` (:1595) matches the whole claim text against them. Only these differences are allowed:
- letter case
- spacing (whitespace runs are collapsed)
- curly versus straight apostrophes
- one final period

The `{A}` and `{B}` holes take only the quoted names of the resolved pair, so a form cannot match unless `claimPairNamed` resolved exactly two IDs. Any claim that names two or more researchers but does not resolve to a pair gets `pairNames = Nil`. `matchForm` then returns None and the claim gets AbsenceAmbiguous, so it fails closed.

**The three reviewer inputs.** All three now contain a list word: "missing" and "nonexistent" were added to the absence list (:1118-1120), and "zero" to the negation list (:1065). None of them matches an accepted form, so each gets AbsenceAmbiguous and is not VERIFIED. Test: AbsenceRuleSuite.scala:522.

**New inputs I tried that contain a list word.** All are caught (not VERIFIED):
- "... have a path that never existed ..."
- "... have a path, none in 2015-2025"
- "... coauthored without a path ..."
- "... isn’t" with a curly apostrophe (normalized at :1340)
- "NOT" in upper case (tokens are lower-cased at :1346)
- "Neither ... nor ..."
- "... lacking ..."
- "... in different components" (phrase match at :1582)
- every list word added as a suffix to an affirmed claim (test :562)

**Inputs with no list word, such as "vanished" or "unacquainted".** These are outside the human's rule as written, because the rule is triggered by list words. This residual matches the repairer's disclosed gap and the "proposed later" note in repair-E1-rag.md (lines 84-93). The docs must still state it as a known limitation, and whether it is acceptable under decision 6 is the human's call.

**Hyphenated compounds.** This is a partial edge case inside the rule's wording; see P17-L-02.

### 2. P17-K-02: RESOLVED for both inputs

"strangers" is now an absence word (:1121). Both inputs are read as absence clauses and fail the earlier step-1 rules:
- The first input fails with AbsenceThirdName ("King" is capitalized and not in the allowlist) and AbsenceNoDepth.
- The second input fails with AbsenceNoDepth.

The gate adds AbsenceAmbiguous to both. Test: :535. The broader class (absence wording with no list word and no found path) stays open, as disclosed.

### 3. Monotonic behaviour, positive controls, evidence checks

- **Monotonic.** The gate only appends to `uncovered` or `unbacked` (:804, :820-824, :831, :838). It never touches `checked` or `conflicts`, and it runs only on the Phase 17 path (`absenceRule = true`, :610). Every `both` call goes through `VerifierOracle.compare` (test :34-39), so each test also checks for monotonicity.
- **Positive controls.** Both are intact. The existing tests at :299-302 and :422-424 pass, and the new test at :620 confirms that each control is VERIFIED only with a cited found path or edge in its window. Without one, the claim becomes UNSUPPORTED with the new reason ConnectionNotFound.
- **Evidence checks for each form kind:**
  - **Absence forms** (:814-824) need all of the following:
    - a cited `path=none` item for exactly the pair's ID set
    - with a window equal to the form's window
    - marked complete
    - with `maxDepth` at least the stated depth
    - and no found path or edge between the pair anywhere in the bundle.
  - **Connection forms** (:828-831) need a cited found path (both researchers anywhere on it) or a cited edge, whose window lies inside the form's window.
  - **The uncertainty form** (:834-838) needs a cited, incomplete search for the pair whose `depthSearched` equals the stated depth.
- The earlier step-1 rules (the absence checks (a)-(g), check (h) and the hop checks) still run on accepted forms.
- Test :595 covers a deeper depth than searched, a different window and a found path; test :661 covers a complete search against the uncertainty form.
- Checked, none wrong.

### 4. Tests fail without the fix

I judged this from the test code against the two switched-off runs in the report:
- **Test :522.** With the gate off but the list words kept, step 1 still adds AbsenceAmbiguous to all three inputs: inputs 1 and 3 read as Unclear (a connection word plus an absence word), and input 2 has a dangling "zero". So the test passes in run 1 and fails in run 2. It does fail without the whole fix.
- **The gate on its own.** This is covered by :547 (negated absence predicates that read as a connection, e.g. "not unconnected"). That test is VERIFIED in Phase 16, has assertions that pin reasons exactly `[AbsenceAmbiguous]`, and fails in run 1.
- **Test :535.** Its second input contains only in-vocabulary words, so without the gate no AbsenceAmbiguous is added and the assertion fails. This is consistent with run 1.
- **Test :620.** ConnectionNotFound exists only in the gate, so this test fails in run 1.

The report's claims are consistent with the code.

### 5. No production limits, thresholds, prompts, defaults or golden files changed; no identifiers or secrets

Checked, none. The diff has only the two files. The only test data is the fixture names Alice Smith, Bob Jones and King. There is one new additive reason code, `Reasons.ConnectionNotFound` (:135-136, :168). It is an interface change, so docs/api.md needs it; that is documentation-engineer's work and is not a finding here.

### 6. New regressions

These are P17-L-03, plus one older issue, P17-L-04. Details are in the findings table.

### Findings

| ID | Severity | File:line | Failure input | Impact | Owner | Interface |
|---|---|---|---|---|---|---|
| P17-L-02 | minor | ClaimVerification.scala:1334 (TokRe joins hyphenated parts into one token), :1579-1583 (`hasNegationOrAbsence` compares whole tokens) | "Alice Smith and Bob Jones have a path that is never-established in 2015-2025." citing `found` (other examples: "not-present", "no-longer-existing") | The token "never-established" is not in the negation list, so the gate does not fire. Step 1 reads the clause as Connection, and (h) affirms it because "path" comes before the hyphenated word. The claim stays VERIFIED: same shape as reviewer input 1, which was VERIFIED before E1. A list word joined by hyphens escapes "any word ... anywhere". Low likelihood in synthesis output. Fix: also test hyphen-split parts in `hasNegationOrAbsence` ("non-existent" stays covered; "co-authored" has no list part). Whether a hyphen-joined part counts as a "word" is a human call; if the human reads it that way, raise this to major. | rag-engineer | no |
| P17-L-03 | minor | ClaimVerification.scala:1065, :1118-1122 (new words also feed `read` at :1409-1410 and step 1 at :621, :675) | "Alice Smith has zero coauthors in 2015-2025." or "Alice Smith has 3 publications missing venue data." (one researcher named) | The new words are absence or negation predicates for every claim, not only for the gate. A true claim about one researcher becomes an absence span with no pair, gets AbsenceNotSearched (unbacked), and becomes UNSUPPORTED. It was VERIFIED before E1. This fails closed and is disclosed (repair report RISKS); the corpus shows 0 falls. A false negative, not a grounding hole. | rag-engineer | no |
| P17-L-04 | minor (existed before E1) | ClaimVerification.scala:529-538, :623, :802; RulePlanner.scala:207-208 | "Smith and Jones have a path that is missing in 2015-2025." citing `found` | Surname-only references are not name runs (a run needs two or more capitalized tokens), so `namesTwo` is false. The gate, (h) and the `unclear` flag (:630) all skip the claim, which stays VERIFIED. Not caused by E1; it sits outside the code's definition of "names both researchers". Proposed for later. | rag-engineer | no |

No critical or major finding.

VERDICT: APPROVE (E1 rag slice: P17-A-01/P17-L-01 RESOLVED against the human's rule as written; P17-K-02 RESOLVED for both inputs; three minor findings).

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: docs/agent-runs/phase-17/prompt.md, session 3 step E1 focused re-check (decisions 6 and 15); start HEAD 95a8d9f, uncommitted diff (diff-E1-rag.txt)
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools)
TEST RESULTS: not run (reviewer has no Bash); the repairer's reported runs (rag 208/208, GoldenExamplesSuite 17/17, sbt check 2352/2352, switched-off runs failing 3 and then 9) are consistent with the test code I read
VALIDATION RESULTS: P17-A-01/P17-L-01 RESOLVED (rule as written); P17-K-02 RESOLVED (two inputs); monotonic and positive controls intact; no limits, prompts, goldens or secrets changed; new minors P17-L-02, P17-L-03, P17-L-04
ASSUMPTIONS: the human's rule is as quoted in the invocation; "word" means a token as the verifier tokenizes it (hyphenated compounds are one token), which is why P17-L-02 is rated minor and not major
RISKS: absence wording with no list word ("vanished", "unacquainted") stays unchecked by design of the rule; it must be documented as a known limitation. P17-L-02 could be raised to major if the human counts hyphen-joined parts as words.
MANUAL ACTION REQUIRED: human decides whether to accept P17-L-02..L-04 as known limitations or to route a repair; documentation-engineer adds Reasons.ConnectionNotFound to docs/api.md and updates the L2 statements listed in agent-report-docs-W5.md:140
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: human E1 decision on the minors; documentation update for the new reason code; final review
```
