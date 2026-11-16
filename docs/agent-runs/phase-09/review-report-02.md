# Phase 9 code review, re-check after repair round 1 (code-reviewer, read-only)

## Summary

**VERDICT: APPROVE**

All three repaired major findings (R-1, R-2, R-3) are **CLOSED**. No new blocking or major defect was found in the repaired code. Four new minor items (n-1 to n-4) are recorded for the human. Under "Repair limits" they are not sent back for repair.

Per the work order, this re-check covers only R-1, R-2 and R-3. The m-1..m-4 and a-1..a-3 items from review-report.md are unchanged and still with the human.

**sbt check log confirmed.** The tail of `/private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/d0bd1d5f-4b03-4f4b-b62b-dd21020bf58a/scratchpad/phase-09/repair01-check.log` matches the repair report:
- line 6: `Ledger check passed (scalafmt 3.11.5)`
- line 9505: `Contract tests (no AWS): suites=22 executed=336 passed=336 failed=0 errors=0 skipped=0`
- line 9745: `Module tests: suites=90 executed=1633 passed=1633 failed=0 errors=0 skipped=3`
- line 9746: marker updated
- line 9747: `[success] Total time: 871 s`
- line 9748: `exit=0`

The module test count rose from 1629 to 1633, matching the 4 new tests. I did not run any command myself.

## Rulings per finding

### R-1: identity uncertainty for researchers not looked up by name. CLOSED

**The fix**
- `modules/rag/src/main/scala/org/scholarchain/rag/runtime/RuntimeModel.scala:345-366`: `Fact.researchers` / `Fact.researcherRefs` cover all 8 subtypes of the sealed `Fact` trait (8 `extends Fact` at :236-330, 8 cases each).
  - Metric and ranking subjects, both edge endpoints (present or absent edge), every path hop, path-absent endpoints, the work owner and the identity candidate are all included.
  - Rankings, bridge rankings and neighbours all enter as `MetricFact` or `EdgeFact`, so they are covered.
- `ResearcherResolver.scala:256-289` (`IdentityCheck.run`):
  - It reuses outcomes the resolver already knows (resolved candidates, ambiguous candidates, IdentityFacts).
  - Every other researcher in a bundled fact is read with `GraphQuery.ResearcherById`, which is a typed template, so no user text reaches query text.
  - A failed read or a missing `identityOutcome` becomes `"unknown"` (:277). A researcher past `MaxLookups = 250` becomes `"unchecked"` (:270, :281-283).
  - `uncertain` is every outcome other than `accepted` (:247), so failures and unchecked researchers are flagged, not hidden.
- `QueryWorkflow.scala:118-127`: the check runs over `facts.filter(built.idOf.contains)`, which is exactly the facts a claim can cite.
- `QueryWorkflow.scala:189-211`: each claim's note covers every flagged researcher of every cited fact (up to 5 named, then "and N more"). Before, it used only name-resolved researchers and only the first one.
- `ResearcherResolver.scala:299-317`: the answer notes list flagged researchers, plus a separate line counting unchecked ones.

**The test**
- `QueryWorkflowSuite.scala:158-195` asks three questions: a ranking, a bridge ranking, and Carol King's neighbours.
- For each it asserts that conflict-flagged Greta Olsen was never resolved by name (:169).
- Every kept claim citing a non-match_decision fact about her has an uncertainty note containing "conflict" and her ID (:181-184).
- Claims about accepted researchers have no identity note (:186-189), and an answer note names her (:190-193).
- This is the review's failure input in fixture form: a conflict researcher reached through a ranking or bridge, not through a name.
- With the old `resolution.uncertain` map, the note would be absent and :184 fails. The repair report shows exactly that failure.

**New defects:** none blocking or major. See n-3.

### R-2: verifier exclusions no longer hide a wrong number. CLOSED

**The fix** (`modules/rag/src/main/scala/org/scholarchain/rag/runtime/ClaimVerification.scala`)
- `VenueNumRe` (:51) now captures the word before ` (n)`. That `(n)` is removed only when the word, ignoring case, is the last venueKey segment of a cited graph item (:152-156, :161-167). Otherwise the text is left as it was and the number is checked.
- `SlashTokenRe` (:54) no longer spans parentheses, commas, semicolons, brackets or quotes. A slash token is removed only when it is a dblpKey or venueKey of a cited item, or fully matches `DblpKeyRe` (:55-56, :168-174). `DblpKeyRe` needs a known root, a segment starting with a lowercase letter, and an optional third segment containing a letter.
- `99/100`, `conf/99` and `Smith (99)` are now checked like any other number. `NumberRe` (:57-58) has no `/` or `(` in its look-behind set, so `99` and `100` are both found.
- Stable IDs, claim IDs, homonym suffixes and quoted titles are handled as before (:124, :159-160), which is within the spec.

**The test** (`ClaimVerificationSuite.scala:302-332`)
- It uses the review's exact inputs: (a) `99/100` at :304-306 and (b) `Alice Smith (99)` at :308-311. Each asserts not `Verified` and a "number 99" reason.
- It adds `KDD (7)` with a cited `conf/acl` work (:313-316). `KDD` is all caps, so `nameRuns` ignores it (`RulePlanner.scala:220`), and only the number check can fail this claim. The test therefore exercises the venue rule.
- It adds `(see conf/99)` (:318-321).
- It also checks that an uncited key-shaped token stays excluded (:323-331).
- The positive cases in the earlier test (:276-300) still pass.
- With the old `\S*/\S*` stripping, (a) is `Verified`, so the test fails. The repair report shows the failure at :305.

**Could the new exclusions hide a wrong number?** Only in the narrow case recorded as n-2 (a made-up key-shaped token). A free-standing number cannot be absorbed, because a slash token or a venue `(n)` is removed only if it is a cited key, has the exact dblp-key shape, or follows a cited venue. Not major.

### R-3: summary and missing-evidence text. CLOSED

**The fix**
- `modules/rag/src/main/scala/org/scholarchain/rag/runtime/Synthesis.scala:327-328, 355`: the digit check (`\p{Nd}`) and the stable-ID check now search with `findFirstIn`, so a newline no longer hides a digit or an ID.
- `Synthesis.scala:356-360`: any capitalized name run (`RulePlanner.nameRuns`, with newlines turned into sentence breaks) that is not a graph-item name of the bundle (`graphNames`, :339-344, semantic items excluded) rejects the text.
  - A rejected summary is replaced by `DefaultSummary` (:375).
  - A rejected missing-evidence line is dropped (:362), with a note (:363-373).
- `QueryWorkflow.scala:284-289`: any summary other than `DefaultSummary` is printed after `SummaryLabel` ("Overview (not verified; only the claims below are checked against evidence):").
- `QueryWorkflow.scala:310-313`: missing-evidence lines go under `MissingEvidenceLabel` ("model-written, not verified").
- The summary appears only in `answerText`. `AnswerResponse` has no separate unlabelled summary field (`QueryWorkflow.scala:322-344`).

**Gate status:** the spec gate "Unsupported claims are removed, weakened, or explicitly labeled" is now met for the summary. Text with digits, IDs or unknown names is removed. Any other kept summary is explicitly labelled as not verified.

**The tests** (`StagesSuite.scala:310-344`)
- The review's exact newline summary (:311-316).
- A missing-evidence line `"No venue data.\nBob Jones has 99 coauthors."` (:313, :317). It names only an in-bundle researcher, so only the digit search can catch it.
- A summary with a newline before a stable ID (:321-322). It has no multi-word name run, so only the ID search can catch it.
- The review's `Luc Van Gool` summary and missing-evidence line (:328-335).
- A kept summary naming in-bundle Bob Jones, which must begin with `SummaryLabel` (:337-343).
- The old `.*…*` `matches` filter without a name check fails both tests (report shows :316 and :333).

**Can the name check be bypassed?** Yes, in edge cases (n-1). Those summaries are still shown under the "not verified" label, which the review's fix direction accepted ("or mark it explicitly as an unverified overview"). Not major.

## Ownership check

- **Changed files:** the repair report lists only files under `modules/rag/**`.
- **Grep for leaks:** I searched `modules/core`, `modules/storage`, `modules/services`, `tests` and `prompts` for every new symbol (`IdentityCheck`, `SummaryLabel`, `MissingEvidenceLabel`, `DblpKeyRe`, `researcherRefs`). None was found.
- **Unchanged files:** the report states the benchmark-v1.json sha256 (`b58c5e1a…3ac`) and the prompt sha256s are unchanged. I cannot hash files or run git, so I accept these as the agent's claim. Nothing I read contradicts them.

## Findings

| # | Severity | File:line | Failure input | Impact | Owner |
|---|---|---|---|---|---|
| R-1 | was major, **CLOSED** | `ResearcherResolver.scala:256-317`; `QueryWorkflow.scala:118-127, 189-211`; `RuntimeModel.scala:345-366` | Conflict researcher reached through ranking, bridge or neighbours | Now noted on every citing claim and in the answer notes; test `QueryWorkflowSuite.scala:158-195` | rag-engineer |
| R-2 | was major, **CLOSED** | `ClaimVerification.scala:51-56, 149-174` | `99/100`, `Alice Smith (99)` | Now checked as numbers, so not `Verified`; test `ClaimVerificationSuite.scala:302-332` | rag-engineer |
| R-3 | was major, **CLOSED** | `Synthesis.scala:327-378`; `QueryWorkflow.scala:284-289, 310-313` | Newline before a digit or ID; unknown name in the summary | Summary replaced, or labelled not verified; test `StagesSuite.scala:310-344` | rag-engineer |
| n-1 | minor (new) | `Synthesis.scala:356-360` with `RulePlanner.scala:189-229` (≥2 tokens :208, single-space adjacency :218, all-caps excluded :220) | Summary `"Gool is the strongest bridge between all communities."`, `"LUC VAN GOOL is the strongest bridge…"`, `"Luc  Van"`-style double spacing, a lowercase name, or a spelled-out number (`"ninety-nine coauthors"`) | Passes the sanitizer. It is still printed under `SummaryLabel` ("not verified"), so it is labelled, not silent. Optional later hardening: also reject single capitalized or all-caps tokens that are not in bundle names, and number words. | rag-engineer |
| n-2 | minor (new) | `ClaimVerification.scala:55-56, 172` | Claim citing Alice's C=37 item with text `"In 2015-2025, Alice Smith has journals/x/y-99 coauthors."` | A made-up key-shaped token (not a cited key) is stripped, so the embedded 99 is not checked. Without the strip, `NumberRe` would find it after `-`. This is within the review's own fix direction ("or match conf/journals-style keys"), and the text is not a readable count. Optional later hardening: exempt uncited key-shaped tokens only when they also appear in the bundle. | rag-engineer |
| n-3 | minor (new) | `ResearcherResolver.scala:270-283, 312-316`; `QueryWorkflowSuite.scala:158-195` | (a) A ResearcherById read fails. (b) More than 250 researchers in the bundled facts. | (a) and (b) are handled correctly in code (`unknown` / `unchecked`, both flagged), but no test covers them. The lookups also run one after another, up to 250 Neptune reads per query, which is a latency risk for large bundles in the live run. | rag-engineer |
| n-4 | minor (new) | `QueryWorkflow.scala:127` (`alreadyNoted = resolution.uncertain.keySet`) | Ambiguous name whose candidates include a conflict-flagged one | The candidate is described in the resolver note and listed again in the IdentityCheck note. Redundant only; no information is lost. | rag-engineer |

## Report contract

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: docs/agent-runs/phase-09/prompt.md, Standing rules "Repair limits", decisions 5h and 6; re-check of review-report.md R-1, R-2, R-3 after rag-engineer repair round 1 (repair-01.md)
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools)
TEST RESULTS: not run by the reviewer. The cited log /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/d0bd1d5f-4b03-4f4b-b62b-dd21020bf58a/scratchpad/phase-09/repair01-check.log shows: ledger check passed (line 6), contract tests 336/336 passed (line 9505), module tests 1633/1633 passed with 3 skipped (line 9745), marker updated (line 9746), [success] (line 9747), exit=0 (line 9748). These match repair-01.md. The would-fail-without-fix runs are the agent's claim; my reading of each test against the old code agrees with them.
VALIDATION RESULTS: R-1 CLOSED, R-2 CLOSED, R-3 CLOSED; no new blocking or major finding; 4 new minor findings (n-1..n-4) recorded, not to be repaired under "Repair limits"
ASSUMPTIONS: (1) The repair stayed within modules/rag/**. This rests on the repair report's file list plus my grep: none of the new symbols appears in core, storage, services, tests or prompts. I cannot run git or shasum, so the unchanged benchmark-v1.json and prompt sha256s are the agent's claim. (2) Labelling a kept summary "not verified" meets the spec gate's "explicitly labeled" option for the summary.
RISKS: (1) Live answer text now carries the "Overview (not verified …)" and "Missing evidence (model-written, not verified)" labels, and the attempt-01 fake-run outputs predate the repair (not regenerated). (2) Up to 250 extra ResearcherById reads per query (n-3). (3) The summary name filter has edge-case gaps that are mitigated by the label (n-1).
MANUAL ACTION REQUIRED: human accepts or defers n-1..n-4 together with the earlier m-1..m-4 and a-1..a-3 (m-4 still needs the human's fingerprint decision); then the human's live benchmark run and the phase gate
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: human gate for Phase 9 (live run per the work order, then ratification)
```

VERDICT: APPROVE
