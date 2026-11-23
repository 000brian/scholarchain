[Saved verbatim by the orchestrator from code-reviewer's final message (R-1).]

# review-report-code.md (Phase 17 session 2, R-1)

Scope: steps 1 to 4, I1a, I1b and X1. Inputs: diff-R-1.txt (every hunk), files-R-1.txt, the four untracked files (each read in full), the owner reports listed under Coverage, and the work order prompt.md (decisions 6, 7, 8.2, 9 and 15, plus the standing rules).

## Summary

The decision 6 rule is built as specified (items 1a to 1g). Every P16-L-10..L-15 failure input, traced by hand, is no longer `verified`. The rule cannot raise a status: it only adds reasons, and the status function is monotone in its reason sets. The oracle and the positive controls are real. The release-manifest script is read-only, refuses a dirty tree, and runs its identifier check before it writes anything. env.sh uses no G-12 construct. No file under `infra/terraform/**` changed, and no identifier appears in the diff.

One major finding remains: the P16-L-10 class is still open for absence wordings outside the fixed lists, so a contradicted claim can still be `verified` (P17-A-01). There are also six minor findings.

## Findings

| ID | Severity | File:line | Failure input | Impact | Owner | Interface |
|---|---|---|---|---|---|---|
| P17-A-01 | major | modules/rag/src/main/scala/org/scholarchain/rag/runtime/ClaimVerification.scala:905-906 (Negations), :945-964 (absence predicates), :1226 and :1251 (`classify` returns Neutral or Connection), :606-613 (every ambiguity gate needs an absence span, Unclear or NegationOnly) | "Alice Smith and Bob Jones lack any coauthorship path in 2015-2025." citing AbsenceRuleSuite's `found` (Alice–Carol–Bob, 2 hops, 2015-2025, AbsenceRuleSuite.scala:25-26). "lack" is not a negation and "path" is not negated, so `classify` returns Connection. No Phase 16 phrase matches, so `allSpans` is empty and no ambiguity flag is set. The names are checked (2) and the range is `found`'s window, so the status is **Verified**, the same as in Phase 16. "Alice Smith and Bob Jones are in distinct components in 2015-2025." is also **Verified**: no listed predicate and no negation, so the clause is Neutral. | This is the same false Verified as P16-L-10: the claim says there is no connection, and the cited path contradicts it. The reviewer's four P16-L-10 inputs are fixed. The class is not: the rule still depends on listing the phrasings (decision 6 asks for a rule that "does not depend on listing every phrasing"). Repair options: add lack/lacks/lacking/absent and similar to Negations, and distinct/unrelated/"distinct component(s)" to the absence predicates; or, structurally, stop verifying a claim that names both people of a pair with a found path between them unless an affirmative connection predicate is read. At E1 the human may instead accept the fixed-list design as a known limitation. Either way this decides whether P16-L-10 counts as open for the final gate. | rag-engineer | no |
| P17-A-02 | minor | modules/rag/src/main/scala/org/scholarchain/rag/runtime/Synthesis.scala:653, with ClaimVerification.scala:608-610 and QueryWorkflow.scala:302-306 | The deterministic edge-absence claim "In 2021-2023, Alice Smith and Bob Jones have no direct coauthor edge." citing its EdgeAbsentFact. The negation sits beside the pair and the clause has no listed predicate ("coauthor" in the singular is not one), so the claim is NegationOnly and gets AbsenceAmbiguous: **partially_supported** (Phase 16: verified). AbsenceRuleSuite.scala:335-351 pins this. | Every edge=none answer, a Spark-stored deterministic fact, now carries the user-visible note "Partially supported: the claim may state that researchers are not connected, in wording the verifier cannot check." The decision 6 rule (item 1f) requires this outcome, and the rag report lists it, but docs/api.md and the final report must state it. Proposed later: an edge=none check. | rag-engineer (docs consumers W4/W5) | yes |
| P17-A-03 | minor | ClaimVerification.scala:608-610 (NegationOnly), :1249-1250 (Unclear); AbsenceRuleSuite.scala:293-304 (the only positive controls) | Affirmative connection claims with a negated side clause, each citing a found 2-hop path: "Alice Smith is not a direct coauthor of Bob Jones; they are connected within 2 hops in 2015-2025." (clause 1 is NegationOnly and the claim names two people) and "Alice Smith and Bob Jones are connected within 2 hops in 2015-2025, although they are not direct coauthors." (the clause "they are not direct coauthors" is an absence that names nobody, so Unclear). Phase 16: **verified**. Phase 17: **partially_supported**. The first wording is the code's own P16-A-09 example of a non-absence statement (:162-166). | The rule requires these falls (1a "both kinds of predicate in one claim", 1f). But the rag report says "No existing affirmative connection claim fell", which is true only for the fixtures, and its RISKS and INTERFACE HANDOFF list only absence wordings. No test pins these falls. Documentation built from the handoff would understate the live impact on natural affirmative answers. Add tests and a disclosure line. | rag-engineer | no |
| P17-A-04 | minor | ClaimVerification.scala:641 (items c and f run only over `allSpans`), :606 (Unclear only adds AbsenceAmbiguous) | "Alice Smith and Bob Jones, in 2015-2025, are disconnected." citing `found`. The comma split leaves "are disconnected" in its own clause; that clause names nobody, so it reads Unclear and is not an absence span. The found-path contradiction is never checked. Result: **partially_supported, kept** (Phase 16: verified). The same happens with a colon ("Alice Smith and Bob Jones: disconnected in 2015-2025."). | This meets "at most partially supported" (1a). But a claim whose own cited path contradicts it is still shown. Item 1c, "a found path ... contradicts an absence claim", is not applied to claims that may be absence claims. | rag-engineer | no |
| P17-A-05 | minor | ClaimVerification.scala:112-113 (reason text); reused at :617, :674, :681-690, :699 | The P16-L-12 bundled-path case (AbsenceRuleSuite.scala:171-187). A complete cited search for exactly the pair and the window exists, yet the reason reads "an absence claim states a researcher pair or window that no cited path search covered". The same text is given for a third name, a time qualifier and unnamed people. | The reason text, which is user-visible in the API and the explain output, names the wrong cause. The rag report acknowledges this under known gaps, and docs/api.md would carry the inaccurate meaning. Proposed: dedicated codes, or a widened text. | rag-engineer | yes |
| P17-A-06 | minor | scripts/README.md:9-24 (Prerequisites table has no `unzip`); scripts/release-manifest.sh:251; scripts/check.sh:1548-1556 | Run check.sh on a host without `unzip`. The release-manifest refusal probes for missing marker, clone not a Git tree, HEAD differs and jar missing all exit 3 with "required tool not found: unzip", their regexes do not match, and gate 9 fails. check.sh's own tool preflight does not name unzip. | A misleading gate 9 failure. Low likelihood (macOS ships unzip). package.sh already needs unzip and is not listed either. I1a's report states this itself (its line 34). | infra-engineer | no |
| P17-A-07 | minor | scripts/emulator/env.sh:88-106 (the SCHOLARCHAIN_CFG_* sweep); scripts/emulator/probes.sh:198-202 (P16-B-03); scripts/archive-evidence.sh:583-585 (E-03) | Grep of scripts/check.sh for `emulator/env.sh`, `emulator/probes.sh` or the "account only" value: none. No probe or test exercises any of the three changes. | CLAUDE.md definition of done, item 3 (a test that fails without the change) is not met for these three changes. env.sh's sweep will ship never executed, because the Phase 16 emulator run is not repeated (prompt.md:272). The step 8 expected output in scripts/README.md is therefore unverified. All three are labelled `runtime: untested`. | infra-engineer | no |

Not repeated: release-manifest marker staleness (P17-F-04), the 12-digit clone folder name (P17-F-05) and the hook shellcheck item. F-1 already reports them (review-report-final-control.md).

## Checklist (R-1)

**Decision 6, items 1a to 1g (each P16-L input traced by hand through ClaimVerification.scala):**
- 1a: Negations :905-906 (`n't` :1186, curly apostrophes :1166); connection predicates :912-942; absence predicates :945-964; scope rule `classify` :1200-1253 (`not only` excluded :1223); a negated absence predicate reads as a connection :1246; unclear means at most partial :604-614. One test per list class: AbsenceRuleSuite:46-100. Residual: P17-A-01, P17-A-04.
- 1b: `isThirdName` :1256-1259; Allowlist :1053-1119; bundle name parts other than the pair's :653; shared first name allowed (test :321-333).
- 1c: found paths of the whole bundle :619-625; position anywhere on the path :627-640; contradiction :659-666 and :698-699.
- 1d: TimeQualifierRe :1153-1155; window known and every range equal to it :681-682; qualifier :683-687.
- 1e: no stated depth is now unbacked (unsupported) :678; unnamed people :616-617 and UnnamedWords :1122-1150.
- 1f: eligibility is a conjunction :650-710. Nothing is verified just because no blocker matched: every condition that fails adds a reason.
- 1g: FunctionWords :1011-1048; `inVocabulary` :1262-1267; unknown word means ambiguous :611-612; the correct fixture claims stay verified (test :306-319).
- Traces: P16-L-10 (all four forms) is conflicting (HopsConflict through :698). P16-L-11 is unsupported ("King" is a third name; "or" is outside the vocabulary). P16-L-12 is unsupported (:699), or conflicting when the path lies in W1. P16-L-13 is unsupported ("before 2021" qualifier), and the "decade" form is partially supported ("or" and "decade" outside the vocabulary). P16-L-14 is unsupported (:678). P16-L-15 is unsupported ("his" and "common" are unnamed-people words; the "coauthors" scope is unclear). All match the rag report's table.

**Monotonic:**
- Structural: the rule block :586-712 only appends to conflicts, unbacked and uncovered. `checked` is never touched, and the status function :851-859 is monotone in those sets.
- Oracle: VerifierOracle.scala:51-53 fails a test when the status rose, a Phase 16 reason is missing, or `checked` changed. It is used in PathAbsence, ClaimVerification, Grounding and AbsenceRule (Grep confirmed).
- Positive controls are kept with identical reasons (AbsenceRuleSuite:293-304).
- Orchestrator note "claims=59 absence=0": the corpus test (VerifierMonotonicSuite) covers no absence claim, because the fake benchmark has no path=none answer. Absence coverage comes from the oracle in PathAbsenceSuite (34 tests) and AbsenceRuleSuite. That is adequate.
- The baseline is the same code with the rule switched off (:336-340), not the committed Phase 16 binary. The Phase 16 hunks are refactor-only (diff hunks at diff lines 238-306), and the rule-off run passed the unchanged Phase 16 assertions (rag report). Checked.

**No production limit, threshold, prompt, default or golden file changed to improve a number:** checked, none.
- No change under `prompts/**` and no file under services test resources.
- The H-03 change (RetrievalEvalSet.scala:90 and the packaged JSON line 8) is wording only. The default of 10 matches the code at :132, and the targets are unchanged.
- The packaged eval-set JSON differs from the old rule text in the committed Phase 15 raw report (experiments/raw/p15-e-retrieval-modes/.../retrieval-eval-report.json:44). evaluate.py compares only the benchmark sha256 and the universe size (:611-617), and the file is not in frozen-files-sha256-E12.txt. No impact.

**Each behaviour change has a test that fails without it:**
- rag: 14 of 26 AbsenceRuleSuite tests fail with the rule off (rag report).
- storage P16-A-03: GraphQuerySuite (diff +182-197) against InMemoryGraph.scala:262-292. Sound.
- storage P16-A-05: mutation test (EmulatorWiringSuite, diff +1223-1252).
- services P16-A-12: `built.indexes == configured` fails if Main.scala:109 is dropped, and ServiceServer.scala:298 uses `e.indexes`. Sound.
- hadoop: comment only.
- scripts: P17-A-07.

**release-manifest.sh:**
- Read-only: it writes only `OUT_DIR/RC_SHA/` (through `.incomplete-…`) and TMPDIR. No network, AWS or sbt.
- Refuses a dirty tree: :336-339 (main :1029), before the out-dir check and before any write.
- Identifier check before writing: `identifier_check` (:1045) runs before `write_and_publish` (:1046), and OUT_DIR is first created at :970. It runs again on the written files at :982-985.
- Writes no identifier: the JSON holds no absolute path (the clone appears only as a basename, see P17-F-05). Every release record's fields and the 12-digit byte counts in the dataset manifest are either masked or not emitted. Checked.
- Exit codes, mandatory jars (they match package.sh:433-444 and BuildIdentity), marker format (build.sbt:676-679) and refusal order are as specified.
- check.sh probes :1510-1564 use temporary folders only.

**env.sh (G-12):**
- No associative arrays, no `${var,,}`, no mapfile.
- Sweep :88-106: an awk ENVIRON command substitution, then a here-document into a while-read loop (current shell in both zsh and bash 3.2), quoted `unset`, and arithmetic expansion. Sourced in zsh it behaves the same.
- Observation, not a finding: both shells implement here-documents with a short-lived temporary file, so the header's "writes no file" (:13) is not strictly true. Harmless: the file holds names only.

**infra/terraform/\*\*:** checked, none changed (files-R-1.txt).

**Secrets and identifiers:** checked, none.
- The diff holds only the placeholders 123456789012 and 210987654321, the commit prefix 167196849087, regex patterns, `example.invalid`, and the emulator port.

**Orchestrator notes:**
- Synthesis.scala:653: confirmed (P17-A-02).
- AbsenceNoDepth is now unsupported, AbsenceNotSearched is widened (P17-A-05), and AbsenceAmbiguous is the one new additive code.
- `unzip`: not a listed prerequisite (P17-A-06).
- X1: experiments/results-summary.md:378 already says "no policy at max = min", and :250 says "maximum 2 (= minimum)". Neither contradicts the new template wording. No finding.

## Verdict

VERDICT: REQUEST_CHANGES (P17-A-01 major; P17-A-02..A-07 minor)

## Coverage

- **Read in full:** ClaimVerification.scala (1-1269), AbsenceRuleSuite.scala, VerifierOracle.scala, VerifierMonotonicSuite.scala, scripts/release-manifest.sh, scripts/emulator/env.sh, diff-R-1.txt (every hunk), files-R-1.txt, agent-report-rag.md, agent-report-experiment.md.
- **Read in part (sections named above):**
  - Code: PathAbsenceSuite.scala (160-240 plus diff), Synthesis.scala (600-670), Stages.scala (700-740), QueryWorkflow.scala (285-320), Main.scala (80-115), ServiceServer.scala (285-305), InMemoryGraph.scala (250-300), RetrievalEvalSet.scala (84-143), build.sbt (668-683).
  - Scripts: archive-evidence.sh (560-620 plus diff), check.sh (probe functions plus diff), scripts/README.md (1-38 plus diff), scripts/lib/identity.sh (Grep of the functions used).
  - Records and reports: one release record (releases/course/20261120T213452Z-94ec6b01a4ca-44da.json) plus a Grep of all seven, review-report-P16-L-round2.md (55-64), review-report-final-control.md (findings), prompt.md (decisions 6, 7, 8, 9, 15 and the standing rules), routing-p17.md and the I1a/I1b reports (Grep).
- **Not read:** agent-report-storage.md, agent-report-build.md, agent-report-hadoop.md, agent-report-service.md. Beyond their diff hunks: GroundingSuite, ClaimVerificationSuite, RetrievalEvalSetSuite, CompositionSuite, GraphQuerySuite, EmulatorWiringSuite, probes.sh, deploy.sh, scripts/emulator/lib.sh.

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: docs/agent-runs/phase-17/prompt.md (sha256 c22adfc57c7fde0cae63c8520393c2c81cd12280df7f5df773e70c2e2ec8ac24 as given by the orchestrator; not recomputed, no Bash), Phase 17 session 2, R-1
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools)
TEST RESULTS: none run (read-only); relied on owner-reported runs only where noted (rag rule-off run)
VALIDATION RESULTS: decision 6 items 1a-1g present and the P16-L-10..L-15 inputs no longer verified; monotonic by construction and by oracle; 1 major (P17-A-01), 6 minor (P17-A-02..A-07)
ASSUMPTIONS: AbsenceRuleSuite's `found` fixture yields a verified names-plus-range check for my P17-A-01 inputs, as it does for the suite's own Phase 16 P16-L-10 inputs (the same evidence and window, only the predicate words differ)
RISKS: P17-A-01 decides whether P16-L-10 counts as open for the final gate; extending the lists cannot close the class, so the human may accept the fixed-list design at E1
MANUAL ACTION REQUIRED: human triage of P17-A-01 (repair round for rag-engineer, or accept as a known limitation)
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: step 5 code triage (merge with F-1 and F-2); repair-01-rag for P17-A-01 if routed; minors recorded
```
