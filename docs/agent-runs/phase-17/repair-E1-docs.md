# repair-E1-docs (documentation-engineer, Phase 17 session 3, step E1 documentation pass)

```text
STATUS: COMPLETE
OWNER: documentation-engineer
WORK ORDER: docs/agent-runs/phase-17/prompt.md (sha256 c22adfc57c7fde0cae63c8520393c2c81cd12280df7f5df773e70c2e2ec8ac24, checked with shasum at start and end), decisions 12 to 15, results rules, standing rules; session 3 step E1 documentation repair pass (Markdown only). HEAD 95a8d9fead71db252af1d7ca833e368a63e14e23 (checked) plus the uncommitted, reviewed rag repair (ClaimVerification.scala, AbsenceRuleSuite.scala; not touched).
FILES CREATED: none in the repository (this report only, in the scratchpad)
FILES MODIFIED:
  /Users/brian/Developer/repos/scholarchain/README.md (sha256 9a321cde8e834ba61721d7c1e406ceb58ce78080f53222105001ebe7c8fe55b6)
  /Users/brian/Developer/repos/scholarchain/docs/api.md (sha256 6776414bd567d6010e23677293a404380e579215f6b5f9a6b4ec8437211d0afd)
  /Users/brian/Developer/repos/scholarchain/docs/threat-model.md (sha256 a18c905d4f176c35dd76814092c86e5605bbe9873334c046ef8f61394557fe89)
  /Users/brian/Developer/repos/scholarchain/docs/runbook.md (sha256 d0101c4e27ea6961d53c241c6576a3238b182a88a13260d11412602dedc8963c)
  /Users/brian/Developer/repos/scholarchain/docs/service-limits.md (sha256 56edf8f03e36cf0076e901f1184a210d342164b108ed6faee678af3f5b1c1eda)
  /Users/brian/Developer/repos/scholarchain/docs/demo-script.md (sha256 2e2682655b440b3e5486c2d8260484b81bea352300c91e92de8e3e85c59c2f8d)
  /Users/brian/Developer/repos/scholarchain/reports/final-report.md (sha256 397c19c3eb66936db087af19242dd65714f514570ee977a53e4618395f1ac7c7)
  Not changed: docs/evaluation.md, docs/emulator.md (frozen; no number touched), docs/agents.md, docs/incidents/**, docs/agent-runs/**, every file outside documentation territory.
COMMANDS RUN: read-only only: shasum, git rev-parse, git status --short, git diff (stat, -U0 hunk headers, added-line identifier scan), git show (HEAD:FILE and --stat d8b23f2), git ls-files, git check-ignore -q, grep, wc, ls, head, cut, dirname, printf, echo; shell loops (while read, case, process substitution) for the link and anchor check, printing to stdout only. Read, Write (this report only), Edit tools. No sbt, no script, no python, no terraform, no network, no AWS, no MCP call. Unplanned deviations: see RISKS.
TEST RESULTS: none run (documentation step; the work order forbids sbt here). Test numbers cited in documents are taken from repair-E1-rag.md (rag 208/208 in 13 suites, Golden 17/17, switched-off runs 3 of 41 and 9 of 41 failing, sbt check 2352 module tests); I did not read .claude/.last-test-run.
VALIDATION RESULTS: INTERFACE CHECK passed (every cited name, reason code and file:line read in ClaimVerification.scala; the old file:line references in docs/api.md and the final report were remapped to the working tree via git diff hunk headers and spot-checked). Link check over the 7 changed files: 754 link occurrences, 0 missing, 0 ignored, 0 untracked; 324 fragment links, 0 bad; negative control 2 of 2 fake anchors reported bad; 0 external links in these files. PENDING marks unchanged (README 4, demo-script 1, final report 9); `planned for replay` unchanged except docs/runbook.md 11 -> 12 (the P17-K-10 fix). Identifier scan of the 99 added documentation lines: 0 matches.
ASSUMPTIONS:
  - The human's E1 decisions on the verified-limits requirement (unplanned deviation, 6 unverified rows) and on the other session 2 minors (known limitations) are taken from the invocation; runbook-log.md row "E1 decisions" records them only as "(3-10) agreed as recommended". Documents cite that row.
  - "The other session 2 minors" = every session 2 minor finding in final report 23.4 that was still open (P17-A-06, A-07, K-01, F-02 to F-11, F-13 to F-16); I marked each "accepted (known limitation)". The hook shellcheck row keeps its constitutional known-limitation wording. P17-L-02 to L-04 are marked accepted per runbook-log.md row "E1 code re-check" (the human's stopping rule).
  - Session 3 reports are cited as plain-text paths `docs/agent-runs/phase-17/FILE`, not as links (not yet copied, so a link would fail the check).
  - The final report 23.4 heading (and its anchor `#234-phase-17-review-findings-status-after-the-code-re-check`, linked from README) is kept unchanged to avoid breaking links; its content now includes the E1 re-check.
RISKS:
  - Unplanned deviations (commands outside the standing list, all read-only): two Bash calls used `sed -n` to read the work order (my first calls, before I re-read the "never sed" rule; afterwards the Read tool); one call piped grep into `awk` (it printed nothing usable; the line numbers came from grep); several calls began with `cd REPO &&` (read-only). One Bash call was blocked by the guard (a shell write to a non-literal path `$OUT` in the scratchpad); not retried in that form, the check was rewritten to print to stdout. Two link-check calls were refused by Claude Code's built-in removal check (zsh parameter expansions; nothing was deleted or meant to be); not retried in that form, rewritten in plain bash/zsh syntax.
  - The fragment check compares anchors case-insensitively (grep -ixF), so a fragment that differs from GitHub's lowercase anchor only by case would pass.
  - reports/final-report.md still says "to be copied into docs/agent-runs/phase-17/" for some session 2 reports at lines I did not change (for example around :337, :362, :591); those reports are now in that folder. Outside this pass's item list; for E11.
  - docs/evaluation.md:824-827 (Phase 16, frozen) still describes P16-L-10 to L-13 as open at the Phase 16 gate; not changed (decision 12: frozen, never a number). It is a Phase 16 statement.
MANUAL ACTION REQUIRED: the human copies repair-E1-rag.md, review-report-P17-L-01.md, runbook-log.md and this report into docs/agent-runs/phase-17/ (the documents cite them there). E11 fills every PENDING mark and `planned for replay` line (including the new docs/runbook.md:184 line, E5).
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: documentation re-check of the changed lines (code-reviewer), then E11.
```

## INTERFACE CHECK

Producer: repair-E1-rag.md (rag-engineer, E1), judged by review-report-P17-L-01.md (APPROVE). Every name I cite was read in /Users/brian/Developer/repos/scholarchain/modules/rag/src/main/scala/org/scholarchain/rag/runtime/ClaimVerification.scala (working tree).

| Name or claim cited | Exists as handed over? | Where checked |
|---|---|---|
| `Reasons.ConnectionNotFound` text "the claim states that researchers are connected, but no cited found path or edge connects them in its window" | yes | :135-136 |
| `Reasons.all` holds it (32 entries, :137-170) | yes | :168 |
| `Reasons.AbsenceAmbiguous` text unchanged | yes | :117-118 |
| gate (i): `if (namesTwo && AbsenceRule.hasNegationOrAbsence(stripped))`, None -> AbsenceAmbiguous | yes | :797-841, :802, :804 |
| AbsenceForm checks: AbsenceNotSearched, AbsenceBoundedSearch, AbsenceBeyondDepth, AbsenceFoundPath (unbacked) | yes | :811-824 (:820-824) |
| ConnectionForm: ConnectionNotFound when no cited found path or edge in window | yes | :825-831 |
| UncertainForm: AbsenceNotSearched when no stopped search at that depth | yes | :832-838 |
| unbacked -> unsupported; uncovered -> at most partially_supported (status function) | yes | :981-989 |
| `namesTwo = claimNamed.size >= 2`; `pairNames` | yes | :623, :625 |
| `object AbsenceRule` | yes | :1034 |
| `Negations` adds "zero" | yes | :1047, :1064-1065 |
| `AbsenceWords` adds missing, nonexistent, non-existent, strangers, stranger | yes | :1105, :1117-1122 |
| `AbsencePhrases` | yes | :1126 |
| `sealed trait FormKind`, AbsenceForm, ConnectionForm, UncertainForm | yes | :1520, :1524, :1527, :1530 |
| `AcceptedForm`, `AcceptedForms` (8 forms, texts as in the handoff) | yes | :1535, :1543-1567 |
| `FormMatch`, `hasNegationOrAbsence`, `matchForm` | yes | :1570, :1579, :1595 |
| AbsenceRuleSuite E1 tests :522 (P17-A-01 inputs), :535 (P17-K-02), :547, :562, :580, :595, :620, :661, :670 | yes (grep of test names) | AbsenceRuleSuite.scala |
| Remapped older references (diff hunks: +4 after :132, +5 after :163, +51 after :790, +56 after :976, +58 after :1007, +64 after :1058): ConnectionWords :1072, ConnectionPhrases :1101, FunctionWords :1185, Allowlist :1227, UnnamedWords :1296, TimeQualifierRe :1327, affirms :1387, AffirmWords :1457, inAffirmVocabulary :1500, isThirdName :1504, inVocabulary :1510; Phase 16 checks :565-603 (AbsenceNotSearched :570, AbsenceNoDepth :583); Phase 17 rule :606-735 (:691-724, :702, :728-733); (h) :737-767; P17-A-04 :768-795 | yes (grep of each name; numbered lines spot-checked) | ClaimVerification.scala |
| AbsenceRuleSuite older tests shifted +1 (import at :4): P16-L-10 :132, P16-L-11 :147, P16-L-12 :159 | yes | grep of test names |
| E-03 string in archive-evidence.sh (K-10) | yes | scripts/archive-evidence.sh:584 (within :580-586) |
| Phase 13 run "Archive (--local-only): passed, 13 files" | yes | docs/agent-runs/phase-13/human-gate.md:66 |
| Gate wording for K-11: Phase 7 "I made no code changes."; Phase 9 and 10 "No code changes"; Phase 12 "No code changes. All code is agent-written: …" | yes | phase-07/human-gate.md:135; phase-09:82; phase-10:100; phase-12:110 |
| Session 2 commit d8b23f2 holds the session 2 ClaimVerification change | yes | git show --stat d8b23f2 |

No mismatch.

## Items (file:line after the change)

| # | Item | Status | Where |
|---|---|---|---|
| 1a | P17-K-10: runbook archive-evidence `--local-only` row | done | docs/runbook.md:184 now `planned for replay (E5)` (same as README.md:317); new bullet docs/runbook.md:189 discloses E-03 (archive-evidence.sh:580-586, runtime: untested) and that the Phase 13 run predates it |
| 1b | P17-K-11: gate wording misquote | done | reports/final-report.md:153: Phase 7 "I made no code changes."; Phases 9 and 10 "No code changes"; Phase 12 "No code changes. All code is agent-written" (step F and .gitignore accepted as written); Phases 13 to 16 unchanged |
| 1c | P17-K-12: "each with runtime evidence only" | done | reports/final-report.md:606: IL-02, IL-04, IL-14 runtime evidence only; IL-06, IL-11 cite no runtime evidence (account value needs Service Quotas, not read); SL-29 |
| 2 | L2 statements to the final state | done | docs/api.md:3 (header), :267 (reasons range), :269 (code state), :271 (32 reasons, 7 new), :277-279 (line remaps; AbsenceNotSearched and AbsenceAmbiguous also from the gate), :285 (new `ConnectionNotFound` row), :288 (sources), :290-297 (rule items 1-5 remapped; new item 6, the allow-list gate with its 8 forms), :302-311 (disclosed behaviour, incl. P17-L-03), :313-325 (status of L2 after E1: P17-A-01 RESOLVED, P16-L-10 closed for list-word wording, P17-K-02 resolved for its two inputs, remaining known limitations), :327 (P17-K-01 accepted). docs/threat-model.md:7, :38, :58 (E1 control and tests :132/:147/:159/:522/:535/:620), :59 (residual risk). README.md:418-419. docs/demo-script.md:174, :176. reports/final-report.md:8 (status bullet), :251, :253, :257 (seven codes, 32 texts), :258 (E1 test numbers), :259-262 (effects, new point 8, "Status of L2 after session 3, step E1"), :384 (16, prompt injection), :446 (18), :461 (19, new E1 bullet), :555 (21), :598 (23.1), :657 (23.4 intro), :661 (P17-A-01 row), :667 (P17-K-02 row), :668-670 (new P17-L-02, L-03, L-04 rows), :693 (verdicts); final report :260 is point 8 and :262 the L2 status paragraph |
| 3 | Human decisions replacing "the human decides at session 3" | done | P17-F-01 accepted, no amendment, human checks symlinks at their gate, control-slice verdict stays REQUEST_CHANGES, "Final reviewer returns APPROVE" an accepted deviation, not met: docs/threat-model.md:128, :181; README.md:420; reports/final-report.md:397, :673 (23.4 row), :693. Verified limits accepted as unplanned deviation (6 unverified rows): docs/service-limits.md:8; README.md:421; reports/final-report.md:606, :461. Other session 2 minors accepted as known limitations: reports/final-report.md:399, :666 (K-01), :671-689 rows (P17-F-01 at :673 and the RESOLVED P17-F-12 excepted); docs/threat-model.md:134, :182, :183 (F-03, F-02); docs/api.md:327 (K-01) |
| 4 | PENDING and planned-for-replay lines untouched; no number in evaluation.md or emulator.md changed; no identifiers; session 3 reports cited as docs/agent-runs/phase-17/FILE | done | PENDING counts equal HEAD in all 7 files; evaluation.md and emulator.md not modified (git status); identifier scan 0 |
| 5 | Link check over every changed file (decision 12 rule) | done | table below |

## Link-check table (decision 12 method)

Method: `grep -o` of every `](target)` (count equal to the `](` count in each file); per path target resolved against the file's folder: `ls -d`, `git check-ignore -q` (must not match), `git ls-files` (tracked). Fragments: each `#anchor` compared with the target file's `#` headings turned into GitHub anchors (characters other than letters, digits, space, `_`, `-` dropped, spaces to hyphens, compared case-insensitively). Negative control: two fake anchors, both reported bad.

| File | Link occurrences | Missing | Ignored | Untracked | Fragment links | Fragment bad | External |
|---|---|---|---|---|---|---|---|
| README.md | 245 | 0 | 0 | 0 | 99 | 0 | 0 |
| docs/api.md | 24 | 0 | 0 | 0 | 10 | 0 | 0 |
| docs/threat-model.md | 70 | 0 | 0 | 0 | 34 | 0 | 0 |
| docs/runbook.md | 80 | 0 | 0 | 0 | 19 | 0 | 0 |
| docs/service-limits.md | 11 | 0 | 0 | 0 | 6 | 0 | 0 |
| docs/demo-script.md | 47 | 0 | 0 | 0 | 16 | 0 | 0 |
| reports/final-report.md | 277 | 0 | 0 | 0 | 140 | 0 | 0 |
| Total | 754 | 0 | 0 | 0 | 324 | 0 | 0 |

Not links (plain text, listed separately): the session 3 reports `docs/agent-runs/phase-17/repair-E1-rag.md`, `review-report-P17-L-01.md`, `runbook-log.md` (not yet copied). PENDING entries (not links): README 4, demo-script 1, final report 9 (unchanged).

## Replayed commands and outcomes

None (planned deviation 1: documentation-engineer does not replay commands). The one changed command line (docs/runbook.md:184) now carries `planned for replay (E5)`.

## Unverified claims

- The two "no list word" examples ("vanished", "unacquainted") are rag-engineer's reasoning over the code, not runs; the documents say so.
- The human's decisions on the verified-limits deviation and on the session 2 minors rest on the invocation text; runbook-log.md records them as "(3-10) agreed as recommended".
- Test counts are from repair-E1-rag.md; the marker was not read.

## VALIDATION EVIDENCE

- `shasum -a 256 docs/agent-runs/phase-17/prompt.md` -> c22adfc57c7fde0cae63c8520393c2c81cd12280df7f5df773e70c2e2ec8ac24 (start and end)
- `git rev-parse HEAD` -> 95a8d9fead71db252af1d7ca833e368a63e14e23
- interface check: grep -n of the reason codes, gate, lists, forms, matchForm and test names in ClaimVerification.scala and AbsenceRuleSuite.scala; `git diff -U0` hunk headers for the line remap; Read of :110-171, :795-844, :974-990, :1044-1068, :1515-1636
- link check: 754 occurrences, missing 0, ignored 0, untracked 0; 324 fragments, bad 0; negative control 2 of 2 bad; external 0
- PENDING/replay-line counts vs `git show HEAD:FILE`: PENDING equal in all 7 files; `planned for replay` equal except docs/runbook.md 11 -> 12; `replayed earlier` docs/runbook.md 10 -> 9 (K-10)
- identifier scan of added lines (`git diff -U0 -- README.md docs reports`, 99 added lines; 12-digit runs, arn:aws, amazonaws.com, execute-api, AKIA/ASIA keys, sk-ant-, .elb.) -> 0 matches
- `git status --short`: M README.md, docs/api.md, docs/demo-script.md, docs/runbook.md, docs/service-limits.md, docs/threat-model.md, reports/final-report.md (mine); M ClaimVerification.scala, AbsenceRuleSuite.scala (rag-engineer's E1 repair, untouched)
- sha256 after the last edit: as listed under FILES MODIFIED
files changed after these runs: none
