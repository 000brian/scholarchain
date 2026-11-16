All three major findings (R-1, R-2, R-3) are repaired inside `modules/rag/**` only, and `sbt check` passed (exit 0, observed). I added four tests. With the old behaviour put back for a moment, each of them failed; with the fixes in place they pass. No core, storage or prompt change was needed, and I did not touch m-1..m-4 or a-1..a-3.

```text
STATUS: COMPLETE
OWNER: rag-engineer
WORK ORDER: docs/agent-runs/phase-09/prompt.md, repair round 1: review-report.md findings R-1, R-2, R-3 only (Standing rules, "Repair limits"; decision 5h)
FILES CREATED: none
FILES MODIFIED:
  /Users/brian/Developer/repos/scholarchain/modules/rag/src/main/scala/org/scholarchain/rag/runtime/RuntimeModel.scala
  /Users/brian/Developer/repos/scholarchain/modules/rag/src/main/scala/org/scholarchain/rag/runtime/ResearcherResolver.scala
  /Users/brian/Developer/repos/scholarchain/modules/rag/src/main/scala/org/scholarchain/rag/runtime/QueryWorkflow.scala
  /Users/brian/Developer/repos/scholarchain/modules/rag/src/main/scala/org/scholarchain/rag/runtime/Synthesis.scala
  /Users/brian/Developer/repos/scholarchain/modules/rag/src/main/scala/org/scholarchain/rag/runtime/ClaimVerification.scala
  /Users/brian/Developer/repos/scholarchain/modules/rag/src/test/scala/org/scholarchain/rag/runtime/QueryWorkflowSuite.scala
  /Users/brian/Developer/repos/scholarchain/modules/rag/src/test/scala/org/scholarchain/rag/runtime/ClaimVerificationSuite.scala
  /Users/brian/Developer/repos/scholarchain/modules/rag/src/test/scala/org/scholarchain/rag/runtime/StagesSuite.scala
  (all of these files were already untracked from the Phase 9 implementation; this round changed them in place)
COMMANDS RUN:
  read-only: cat, sed -n, grep, wc, ls, shasum, git status/diff (read-only); edits only with Edit/Write
  sbt -batch "rag/testOnly ...QueryWorkflowSuite ...ClaimVerificationSuite ...StagesSuite"  (4 times: first run 2 failures from test-side mistakes, fixed in the tests; then passed 43/43; then with the fixes put back to the old behaviour, 4 expected failures; one more run hit a compile error in my temporary revert edit, fixed)
  sbt -batch "rag/test"  -> Passed: Total 140, Failed 0, Errors 0, Passed 140, Ignored 2
  sbt -batch check  (background, once, log at /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/d0bd1d5f-4b03-4f4b-b62b-dd21020bf58a/scratchpad/phase-09/repair01-check.log) -> exit=0
  No benchmark rerun, no AWS, no network, no RequiresAws tests, no --llm anthropic.
TEST RESULTS:
  [info] Ledger check passed (scalafmt 3.11.5).
  [info] Contract tests (no AWS): suites=22 executed=336 passed=336 failed=0 errors=0 skipped=0
  [info] Module tests: suites=90 executed=1633 passed=1633 failed=0 errors=0 skipped=3
  [info] Updated /Users/brian/Developer/repos/scholarchain/.claude/.last-test-run (module_tests=1633, contract_tests=336)
  [success] Total time: 871 s (0:14:31.0), completed Nov 15, 2026, 7:01:39 PM
  exit=0
  (module tests rose from 1629 to 1633: the 4 new tests)
VALIDATION RESULTS:
  - Each new test was run against the old behaviour. I temporarily put back the old stripping in the verifier, the old match-based summary filter, and resolution.uncertain in the workflow. Exactly the 4 new tests failed (output below); everything else passed. Then I put the fixes back.
  - benchmark-v1.json sha256 is still b58c5e1a6cb29e192035ce77308c20e2597d2d1dbaf8d136761ae5c3316ff3ac (not modified, not rerun).
  - Prompt files are unchanged: answer-synthesis-v1.md sha256 b319a476…565d and query-planner-v1.md sha256 444a10a8…9ea5. No new prompt version was needed.
ASSUMPTIONS:
  - R-1: "uncertain" means any identity outcome other than `accepted`. A failed ResearcherById read counts as `unknown` (as the resolver already does), and a researcher beyond the per-query lookup bound (250) counts as `unchecked`. Both cases get a note.
  - R-1: a match_decision item of researcher X only lists Y as one of X's top coauthors. That is not a fact about Y, so the claim does not get Y's note. A note goes only on facts about Y: Y as a metric or ranking subject, an edge endpoint, a path hop, a work owner, or Y's own identity item.
  - R-2: "real dblp keys" means a key of a cited item, or a token shaped like a dblp key: a root (conf|journals|books|series|reference|phd|tr|ms), then a lowercase segment starting with a letter, then optionally a third segment containing a letter. `conf/99` and `99/100` do not match.
  - R-2: a "venue name of a cited item" is the last segment of a cited work's venueKey, case-insensitive (`conf/acl` gives `ACL`). The bundle stores no other venue name.
  - R-3: any summary that survives the checks is shown with an explicit "not verified" label. A summary is replaced if it contains a digit, a stable ID, or a capitalized name run that is not a name in the bundle's graph items.
RISKS:
  - The answer text layout changed for the human's live run: a kept model summary now starts with "Overview (not verified; only the claims below are checked against evidence):", and the header "Missing evidence:" is now "Missing evidence (model-written, not verified):". The attempt-01 fake-run outputs on disk were made before this repair and were not regenerated (no rerun allowed).
  - The R-3 name check is conservative. A capitalized non-person phrase in a model summary (for example "Graph Evidence Overview") is also replaced by the default summary. This is safe, but live answers may show the default summary more often.
  - R-1 adds up to 250 ResearcherById reads per query, only for researchers in bundled facts whose outcome is not already known from the resolver. On Neptune these are single-vertex parameterized reads under the existing graph bounds.
  - Some FakeSynthesis claims now carry two identity notes: the template's generic one and the workflow's named one. This is redundant but correct.
MANUAL ACTION REQUIRED: none. The human's live benchmark run should come after the re-review, as the review advised.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer re-check of R-1, R-2, R-3 only (review-report-02.md), then the human gate.
```

## R-1: identity uncertainty for researchers not looked up by name

**Change**
- `RuntimeModel.scala:345-366`: added `Fact.researchers` and `Fact.researcherRefs`. These are the researchers a fact is about: metric and ranking subject, edge endpoints, every path hop, a work's owner, an identity candidate. Group IDs are excluded. `Synthesis.scala` `FakeSynthesis.researchersOf` now uses them.
- `ResearcherResolver.scala:241-321`: new `IdentityOutcomes` and `IdentityCheck`.
  - Outcomes already known from the resolver are reused, both resolved and ambiguous candidates and IdentityFact outcomes.
  - Every other researcher in a bundled fact is read with storage's existing `GraphQuery.ResearcherById` template, reading the `identityOutcome` property. No DynamoDB key is built.
  - At most `IdentityCheck.MaxLookups = 250` reads per query; beyond that a researcher is `unchecked`.
  - `IdentityCheck.notes` adds one answer note naming the flagged researchers that the resolver has not already described.
- `QueryWorkflow.scala:118-127`: after EvidenceBuilder, the workflow runs `IdentityCheck` over the bundled facts (stage key `identity`) and builds `peopleOf` (evidence ID to researcher IDs). The identity-outcome map is passed to synthesis.
- `QueryWorkflow.scala:183-207`: in `finish`, each claim's identity note now covers every researcher whose outcome is not accepted, across every cited fact (up to 5 named, then "and N more"). Before, it used only name-resolved researchers and only the first one.

**Test**
- `QueryWorkflowSuite`: "R-1: a conflict-flagged researcher that enters through a ranking, bridge ranking or neighbours carries an uncertainty note on every kept claim citing it". It runs three questions: a ranking, a bridge ranking, and Carol King's direct collaborators.
- For each, it asserts:
  - Greta Olsen (conflict) was never resolved by name;
  - every kept claim citing a fact about her has an uncertainty note containing "conflict" and her researcher ID;
  - claims about accepted researchers get no identity note;
  - an answer note names her.

**Evidence it fails without the fix**
With `uncertain = resolution.uncertain` put back in both places, the test failed on the first (ranking) question at the uncertainty assertion:
```
==> X ...QueryWorkflowSuite.R-1: a conflict-flagged researcher that enters through a ranking, bridge ranking or neighbours carries an uncertainty note on every kept claim citing it ... munit.FailException: .../QueryWorkflowSuite.scala:184
```
With the fix it passes. The existing test "identity outcome conflict always adds an uncertainty note" (the name-resolved case) still passes.

## R-2: verifier exclusions no longer hide a wrong number

**Change** (`ClaimVerification.scala`)
- `:51-56`: `VenueNumRe` now captures the word before the ` (n)`. `SlashTokenRe` is now `[^\s,;()\[\]"]*/[^\s,;()\[\]"]*` (it used to be `\S*/\S*`). New `DblpKeyRe`.
- `:149-174`: stable IDs, claim IDs and homonym suffixes are still always removed.
  - A `(n)` is removed only when the word before it is the venue of a cited work item (the last venueKey segment).
  - A slash token is removed only when it is a dblpKey or venueKey of a cited item, or is shaped like a real dblp key. Otherwise its digits are checked like any other number.
- `:23-32`: doc comment updated.

**Test**
- `ClaimVerificationSuite`: "R-2: a ratio, 'Name (n)' or a non-key slash token never hides a wrong number". It asserts these are not `Verified`, each with a "number …" reason:
  - (a) the review's exact input `"In 2015-2025, Alice Smith has 99/100 distinct coauthors."`;
  - (b) the review's exact input `"In 2015-2025, the distinct coauthors of Alice Smith (99)."`;
  - `KDD (7)` when the cited work's venue is `conf/acl`;
  - `(see conf/99)`.
- It also asserts that an uncited real dblp key (`journals/corr/abs-2101-00001`) stays excluded and the claim is `Verified`.
- The existing positive cases (`Wei Wang 0003`, `(2016, conf/acl)`, `ACL (1)`) still pass.

**Evidence it fails without the fix**
With the old `\S*/\S*` and look-behind venue stripping put back, the test failed at input (a):
```
==> X ...ClaimVerificationSuite.R-2: a ratio, 'Name (n)' or a non-key slash token never hides a wrong number ... munit.ComparisonFailException: .../ClaimVerificationSuite.scala:305 List(), expected 2 different values: Verified is equal to Verified
305:    assertNotEquals(a.status, Verified, a.reasons.toString)
```
The empty `List()` is the old reasons list: nothing was checked against 99/100. Only the first assertion's failure was observed, because MUnit stops at the first failure. Input (b) fails the same way by the review's trace (the old `VenueNumRe` removed `(99)`), and its assertion passes now.

## R-3: summary and missing-evidence text

**Change**
- `Synthesis.scala:327-328`: the digit and stable-ID checks now use `findFirstIn` (search) instead of `.*…*` `matches`, so a newline can no longer hide a digit or an ID.
- `Synthesis.scala:333-381`: `SummaryLabel` and `MissingEvidenceLabel`, plus `graphNames(BuiltEvidence)`: researcher names of the bundle's non-semantic items, the same source the verifier uses.
  - `sanitize(d, names)` now also rejects any capitalized name run (`RulePlanner.nameRuns`, with newlines turned into sentence breaks) that is not in those names.
  - A rejected summary is replaced by `DefaultSummary`, and a rejected missing-evidence line is left out, each with a note.
- `Synthesis.scala:287`: the call site passes `graphNames(reduced.evidence)`.
- `QueryWorkflow.scala:283-289`: any summary other than `DefaultSummary` is printed after the label "Overview (not verified; only the claims below are checked against evidence):".
- `QueryWorkflow.scala:311`: the missing-evidence header now reads "Missing evidence (model-written, not verified):".

**Tests** (`StagesSuite`)
- "R-3: a newline does not hide a number or identifier in the summary or missing evidence". It uses the review's exact summary `"Overview.\nAlice Smith has 99 coauthors and bridges every community."`, a missing-evidence line with a newline before a number, and a summary with a newline before a stable ID.
- "R-3: a summary naming a researcher outside the bundle's graph items is replaced; any kept summary is labelled unverified". It uses the review's `"Luc Van Gool is the strongest bridge between all communities."` and a missing-evidence line naming him. A summary naming only Bob Jones (in the bundle) is kept, but must start with `SummaryLabel`.

**Evidence it fails without the fix**
With the old match-based check put back (no name check), both tests failed:
```
==> X ...StagesSuite.R-3: a newline does not hide a number or identifier in the summary or missing evidence ... .../StagesSuite.scala:316
316:    assert(t.startsWith(AnswerSynthesis.DefaultSummary), t)
==> X ...StagesSuite.R-3: a summary naming a researcher outside the bundle's graph items is replaced; any kept summary is labelled unverified ... .../StagesSuite.scala:333
333:    assert(t.startsWith(AnswerSynthesis.DefaultSummary), t)
```
The existing test "a summary with digits or identifiers is replaced" still passes.

## Prompt and model versions

Unchanged: `query-planner-v1@sha256:444a10a8e4366de8cd62608d2a5af587a7449d4e772a96bdc01cec1ef2979ea5` and `answer-synthesis-v1@sha256:b319a4766ef2ee75dccec2b9f9f0b3268db795e622e4ed411e799a35f1a0565d`. The models are `fake-rule-template-v1` and `claude-haiku-5-5`, also unchanged. The schemas are unchanged.

## Evaluation metrics

The benchmark was not rerun (rule). Attempt-01 results predate this repair. The repair can only add uncertainty notes, replace or label summaries, and downgrade claims that cite digits it no longer skips. None of the 110 benchmark graph values come from the summary, and the fake template's claims use only cited venue and dblp keys. The new behaviour will first show up in the human's live run.

## Known grounding gaps

- Not fixed here, per the work order: m-1 (an unbound wrong number becomes partially_supported, and qualitative claims are verified with nothing checked), m-2, m-3, m-4, a-1..a-3.
- A summary may still state an unverified qualitative point about a researcher who is in the bundle. It is now explicitly labelled as not verified rather than removed.
