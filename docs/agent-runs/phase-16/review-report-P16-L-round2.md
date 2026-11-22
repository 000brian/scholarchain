# Re-check of round 2: repair-E01-rag (P16-L-03..L-06) and repair-E01-experiment (P16-L-07, L-08)

(Saved verbatim by the orchestrator from code-reviewer's final message.)

All six round-2 findings are resolved, but two new major findings break the human rule as written, so the verdict is REQUEST_CHANGES. Both majors are in rag. The experiment repair is clean: all five answers fixtures match the code, and the pins are consistent.

Work order docs/agent-runs/phase-16/prompt.md (sha256 c75cc5f8…07dc8, as given). Base HEAD 66470343. Read-only review. All traces were done by hand; I ran no code.

## Verdict per finding

| ID | Verdict | Evidence (hand trace, ClaimVerification.scala) |
|---|---|---|
| P16-L-03 (rag) | **RESOLVED** | Absence clauses are now found in every claim (:376). "No path connects Alice Smith and Bob Jones." citing only `found`: the pair is {A,B} (pathIds), the pair has no `path=none` item (AbsenceNotSearched), and `found` connects {A,B} in 2 hops with no depth or window limit, so the result is HopsConflict and **Conflicting**. The "…not connected in 2015-2025." form gives the same result (FullW lies inside 2015-2025). Absence wording citing only metrics gives AbsenceNotSearched, **Unsupported**. |
| P16-L-04 (rag) | **RESOLVED** for the reviewer's inputs and the variant. Residual: P16-L-11 (new). | "Unlike Bob Jones, Carol King…" finds three name runs ("unlike bob jones", "carol king", "alice smith"), so named=3 and the pair is None: AbsenceNotSearched. "Carol King, unlike Bob Jones, …" also gives 3, so not Verified. "Alice Smith, Bob Jones and Carol King…" gives 3, so not Verified. |
| P16-L-05 (rag) | **RESOLVED** | Names are matched with their homonym numbers to IDs: "alice smith 0001" maps to {D} and Bob to {B}. `none2` has the pair {E,B}, so pairNone is empty and the claim is not Verified. With `none1` ({D,B}) it is Verified. An ambiguous "Alice Smith" maps to {A,D}, which fails the size==1 check, so the pair is None and the claim is not Verified. |
| P16-L-06 (rag) | **RESOLVED** for year phrases with digits. Residual: P16-L-13 (new, minor). | "from 2015 to 2025", "since 2015" and "in 2015 through 2025": YearLikeRe still finds a year after the ranges are blanked (:468-473). The window is then unknown, nothing matches it, and the result is AbsenceNotSearched. |
| P16-L-07 (experiment) | **RESOLVED** | The audit note cells in happy/sources.csv:4 and collapse-independent/sources.csv:2 are now unquoted. The text has no comma, `"` or line break, so csv.writer's QUOTE_MINIMAL (evaluate.py:395) writes it bare. one-with-claims/sources.csv:2 follows the same rule. Every quoted cell I checked in the five fixtures contains a comma. |
| P16-L-08 (experiment) | **RESOLVED** | `claim_rows` (evaluate.py:1799-1806): exactly 1 answer with claims gives the descriptive note. 0 answers give NO_INTERVAL and "not measurable" rows. 2 or more run the bootstrap. A total above 0 always means at least 1 answer has claims, so no interval can be drawn from fewer than 2 answers. |

## Work-order checks

**(1) and (2) Rule clauses against the code (:475-522).**
- Two names, resolving to distinct IDs: :451-466 (`named.size == 2`, a single ID per name, the IDs distinct).
- A `path=none` item for exactly that pair: :490, matched on the ID set.
- Searched depth at least the stated depth, on the same item that matches the window: :494-507 (`maxDepth` of in-window complete items).
- Complete: :499-500.
- Same window, or neither has one: :480-486 and :494. A claim with no window never matches, because the items always carry one.
- A cited found path between the pair blocks the claim (:510-520): Conflicting when it falls inside the window and depth, otherwise Unsupported.
- A year left unparsed blocks the claim: :468.
- A third or ambiguous name blocks the claim: :465.
- Remaining false-Verified paths: P16-L-10 and P16-L-11 (major), P16-L-12 and P16-L-13 (minor).

**(3) The three rule-forced behaviour changes are what the rule requires.**
- P16-A-09 combined claim: `found` connects {A,B} outside W1, so it is not a contradiction, but it is "a cited found path between the pair". The claim becomes Unsupported. Each half on its own is still Verified (PathAbsenceSuite:208-217).
- K-01 positive texts cited with `found`: same reason, now Unsupported. Cited alone they are still Verified (:311-312).
- L2 claims that state no window: the item has a window and the claim has none, so "neither has one" fails. Required.

**(4) The deterministic synthesis claim is still Verified.**
- Synthesis.scala:623 is "In 2015-2025, no coauthorship path between X and Y exists within depth 6 (the bounded search completed)." It cites only the `path=none` item. The clause has no range of its own, so the claim's single range is used. The two names resolve to the pair, the item matches, and `depth 6` is within maxDepth. Result: Verified (the same text is asserted at PathAbsenceSuite:302 and :311-312).
- Stages.scala:666 is a stage note, not a DraftClaim. Its wording ("…within depth N in full-2015-2025") is still Verified if checked as a claim: RangeRe matches after the "-", the same pattern as test :303.

**(5) Scope.**
- The diff touches only ClaimVerification.scala and PathAbsenceSuite.scala. It adds one private regex, YearLikeRe. There is no change to thresholds, limits, prompts, defaults, model IDs, `Reasons.all` or any signature.
- The revert result (11 of 34 failing) agrees with my traces: the 9 new tests plus the 2 rule-changed assertions (the A-09 combined claim and the K-01 positives with `found`) fail on HEAD.

**(6) Experiment.**
- **one-with-claims:** sources, rates (lines 2-5 descriptive; Wilson(0,2) gives 0 and 0.65762, since z²/(2+z²) = 0.6576198 rounds to that; codes row "(none)"), per-answer and checks all match.
- **collapse-independent:** the audit claims bootstrap over 2 answers with equal ratios. In demo, "all claims" bootstraps (2 answers), the coauthor-edge and number sets are descriptive (1 answer: demo-a; demo-b has no evidence list, so its units are None), and graph-path and supporting-publication are "not measurable", with the note unchanged. All files match.
- **happy:** no row changes. The audit and demo sets were already descriptive, so `descriptive` is not empty and is not overwritten, and the run report's `units` is None. sources.csv:4 now matches.
- **malformed:** exit 5 in answers_run_report before any output. **missing:** exit 3 in require_file. Both match.
- I took the sha256 cells as given; I cannot compute hashes.
- No other subcommand changed. TOOL_VERSION appears only in run.json (never compared) and in a STEP line.
- The pins are consistent: suite :14 is evaluate-v1-r3, :41 is "evaluate-v1-r3 ratings-sheet", :15 and :42 both hold 8e7f3e74…5e96b3, and evaluate.py:70 matches. I relied on the orchestrator's sha256 check of evaluate.py and of the suite (0f7f7d33…9970).

## New findings

| ID | Severity | Interface | Owner | File:line | Failure input and impact | Rule class |
|---|---|---|---|---|---|---|
| P16-L-10 | major | no | rag-engineer | ClaimVerification.scala:156-164 (AbsencePhraseRe vocabulary), :376 (absence clauses come only from that regex) | **Input:** "Alice Smith and Bob Jones are disconnected in 2015-2025." citing only PathAbsenceSuite's `found` (Alice–Carol–Bob, 2 hops, 2015-2025). "…are not in the same connected component in 2015-2025." behaves the same, as do "unconnected" and "in different components".<br>**Trace:** no AbsencePhraseRe branch matches ("disconnected" has no negation word before it, and "in" is not an allowed filler), so the claim has no absence clauses. No hop count is stated. Both names are in pathNames (checked 2) and 2015-2025 is `found`'s window (checked). Status: **Verified**.<br>**Impact:** the L-03 problem again under a common synonym. An absence claim that the cited path contradicts is shown as Verified. | within rule. The rule governs any absence claim and lists phrases as examples; "anything else … is not Verified". The human may judge vocabulary breadth as Phase 17. |
| P16-L-11 | major | no | rag-engineer | ClaimVerification.scala:451-466 (`claimPair` counts only RulePlanner.nameRuns, which needs 2 or more capitalized tokens (RulePlanner.scala:207-208), plus exact cited full names) | **Input:** "Alice Smith is not connected to Bob Jones or to King within 6 hops in 2021-2023." citing only `noneW1K`.<br>**Trace:** "King" is a single token, so it is not a name run and not a cited full name. named = 2, giving {A,B}. The search matches the pair, W1, completeness and depth 6. The T-10 name check also uses runs only. Status: **Verified**.<br>**Impact:** a surname-only or first-name-only third researcher, common in prose, gets an absence Verified for a pair (King/Alice) that no search covered. This is the L-04 impact class.<br>**Repair direction:** fail closed on any capitalized token inside the absence clause that is not part of the two resolved names and not a sentence-initial or stop word. | within rule ("a third named researcher") |
| P16-L-12 | minor | no | rag-engineer | ClaimVerification.scala:412-421 (`idPair` uses only the first and last pathIds), :491 | **Input:** "Alice Smith and Bob Jones are not connected within 6 hops in 2021-2023." citing `noneW1K` and PathFact(Alice, Bob, Carol) in Full.<br>**Trace:** the path's pair is {A,C}, so pairFound is empty. Status: **Verified**, although the cited path contains Alice–Bob. In W1 the cited evidence would contradict the claim outright, but only with inconsistent data. | within rule ("any cited found path between the pair") |
| P16-L-13 | minor | no | rag-engineer | ClaimVerification.scala:200-205, :468-486 (only year-like tokens make the window unknown) | **Input:** "Alice Smith and Bob Jones were not connected within 6 hops before 2021-2023." (or "…in 2021-2023 or in the decade before.") citing `noneW1K`.<br>**Trace:** the range is parsed, no other year-like token remains, so the window is W1. Status: **Verified**, although the claim's time window is not W1. Unnatural phrasing. | within rule ("an unparsed time phrase") |
| P16-L-14 | minor | no | rag-engineer | ClaimVerification.scala:501 (AbsenceNoDepth is uncovered); repair-E01-rag.md RISKS and ASSUMPTIONS text | **Input:** "No path connects Alice Smith and Bob Jones in 2021-2023." citing `noneW1K`.<br>**Trace:** the window, pair and completeness all match, and no depth is stated, so the result is AbsenceNoDepth (uncovered). Names and range are checked. Status: **PartiallySupported, kept**.<br>The agent's statement "every no-depth claim I can construct is … Unsupported" is wrong. Not Verified, so the rule holds, but an unbounded absence is kept against a depth-limited search. | outside rule (Phase 17) |
| P16-L-15 | minor | no | rag-engineer | ClaimVerification.scala:451-466 | **Input:** "Alice Smith is not connected to Bob Jones or to any of his coauthors within 6 hops in 2021-2023." citing `noneW1K`. Status: **Verified**. The claim is absent for unnamed researchers that no search covered; exactly two named researchers holds. | outside rule (Phase 17) |

No new experiment findings. P16-L-09 (docs/evaluation.md, documentation-engineer) stays open and was not part of this scope. Its line references should now include the fewer-than-2-answers-with-claims rule and `wilson` at evaluate.py:201.

## Files read

**In full:**
- docs/agent-runs/phase-16/review-report-P16-L-01.md and review-report-P16-L-02.md
- In the scratchpad /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/15acd480-beb2-4248-b3ae-3f168fefe386/scratchpad/phase16-reports/: repair-E01-rag.md, diff-repair-E01-rag.txt, repair-E01-experiment.md, diff-repair-E01-experiment.txt
- /Users/brian/Developer/repos/scholarchain/modules/rag/src/main/scala/org/scholarchain/rag/runtime/ClaimVerification.scala
- /Users/brian/Developer/repos/scholarchain/modules/rag/src/test/scala/org/scholarchain/rag/runtime/PathAbsenceSuite.scala
- every file under experiments/evaluation/fixtures/answers/one-with-claims/
- collapse-independent: case.json, input/audit.json, input/demo-a.json, input/demo-b.json, and all four expected files
- happy: case.json, input/audit.json, demo-1.json, demo-2.json, and all four expected files
- malformed: case.json and input/run-report.json
- missing: case.json

**In part:**
- evaluate.py: :100-240, :320-435, :1705-2306 (the whole answers section and its helpers), :2784-2883
- suite-v1.json: :1-48, plus the diff hunks at :5 and :170
- EvidenceBuilder.scala: :1-400
- RulePlanner.scala: nameRuns :189-229, TokenRe, Stop and Particles (grep)
- Synthesis.scala: :608-631 and :446
- Stages.scala: :640-689
- RuntimeModel.scala: :441
- prompts/runtime/answer-synthesis-v3.md (grep for "name")

**Not read:**
- the happy fixture's run-report.json, benchmark.json and explain.csv. Those code paths are unchanged, and the previous reviewer traced all 71 rates lines.
- RuntimeFixtures.scala (FullW = 2015-2025 and W1W = 2021-2023 are taken from the suite's comments)
- QueryWorkflow.scala
- docs/evaluation.md

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: docs/agent-runs/phase-16/prompt.md (sha256 c75cc5f895faed13880a69ec285231c5498fd50924c7b875fe7e02cce1707dc8), base HEAD 66470343c9b80ae0c514232a8c533db4e90242df, Phase 16 session 3 re-check of round 2: repair-E01-rag (P16-L-03..L-06) and repair-E01-experiment (P16-L-07, L-08)
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools)
TEST RESULTS: none run. Accepted from the orchestrator and the agent: PathAbsenceSuite 34/34; targeted 9 suites 144/144; revert 11 of 34 failing (consistent with my traces); full sbt check 08:39:56, marker 2026-11-22T14:39:55Z, module_tests=2300, contract_tests=399; packageCheck ok. The answers fixtures were traced by hand (5 of 5 match); the human runs E1.
VALIDATION RESULTS: P16-L-03, L-04, L-05, L-06, L-07, L-08 RESOLVED. The rule-forced changes (A-09 combined claim, K-01 positives with a found path, no-window L2 claims) are what the rule requires. Synthesis.scala:623 is still Verified. Pins consistent (text). New: P16-L-10 and P16-L-11 major (within rule); P16-L-12 and P16-L-13 minor (within rule); P16-L-14 and P16-L-15 minor (outside rule, Phase 17).
ASSUMPTIONS: traces done by hand; fixture windows taken from suite comments; sha256 values of evaluate.py, the suite and the fixture inputs taken from the orchestrator and the agent; "within rule" reads the rule's parenthesised phrases as examples of absence claims, not a closed list.
RISKS: until L-10 and L-11 are fixed, a live answer can show an absence claim as Verified when it uses a synonym ("disconnected", "not in the same component") that a cited found path contradicts, or when it names a third researcher with one word. L-14: unbounded absence claims stay kept as partially_supported.
MANUAL ACTION REQUIRED: the human decides whether P16-L-10 and P16-L-11 get a repair round under the stopping rule (both classified within rule), or accepts them as documented risk; routes P16-L-14 and P16-L-15 to Phase 17; P16-L-09 (documentation-engineer) is still pending.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: human triage of P16-L-10 and P16-L-11 (rag-engineer, major, within rule); then E1 (answers --fixtures, 5 cases) and E3 suite signing.
```

| Severity | file:line | Failure input | Impact | Owner |
|---|---|---|---|---|
| major | ClaimVerification.scala:156-164, :376 | "Alice Smith and Bob Jones are disconnected in 2015-2025." citing only `found` | Verified, although the cited path contradicts it | rag-engineer |
| major | ClaimVerification.scala:451-466 | "Alice Smith is not connected to Bob Jones or to King within 6 hops in 2021-2023." citing `noneW1K` | Verified for a pair no search covered | rag-engineer |
| minor | ClaimVerification.scala:412-421, :491 | absence claim citing `noneW1K` plus a path Alice–Bob–Carol | a found path through both researchers is ignored; Verified | rag-engineer |
| minor | ClaimVerification.scala:468-486 | "…not connected within 6 hops before 2021-2023." citing `noneW1K` | time qualifier ignored; Verified | rag-engineer |
| minor | ClaimVerification.scala:501 | "No path connects Alice Smith and Bob Jones in 2021-2023." citing `noneW1K` | kept as partially_supported; the report says Unsupported | rag-engineer |
| minor | ClaimVerification.scala:451-466 | "…not connected to Bob Jones or to any of his coauthors within 6 hops in 2021-2023." | absence for unnamed researchers Verified | rag-engineer |

VERDICT: REQUEST_CHANGES
