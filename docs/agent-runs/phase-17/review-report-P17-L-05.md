## Review report P17-L-05: Phase 17 session 3, step E1, focused documentation re-check

I only read files. Scope: the diff at /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/73f10a2e-9b01-4d44-898e-9399e20392db/scratchpad/phase17-reports/diff-E1-docs.txt, read with the text around each change in the seven files. I checked every claim against the working-tree code: ClaimVerification.scala and AbsenceRuleSuite.scala, plus repair-E1-rag.md, review-report-P17-L-01.md, runbook-log.md and docs/agent-runs/phase-17/review-report-docs-02.md.

**Result:** K-10, K-11 and K-12 are resolved. Four minor findings (P17-L-06 to L-09), nothing critical or major. VERDICT: APPROVE.

### 1. P17-K-10, K-11, K-12

- **P17-K-10: RESOLVED.**
  - docs/runbook.md:184 now says `planned for replay (E5)`, the same as README.md:317.
  - A new bullet at docs/runbook.md:189 explains why. It cites the E-03 change, which matches scripts/archive-evidence.sh:580-586 (the string is at :584). It also says the Phase 13 run came before that change; that run's record is docs/agent-runs/phase-13/human-gate.md:66, and the link target exists.
- **P17-K-11: RESOLVED.** reports/final-report.md:153 now quotes each gate exactly:
  - Phase 7: "I made no code changes." (phase-07/human-gate.md:135)
  - Phases 9 and 10: "No code changes" (phase-09:82, phase-10:100)
  - Phase 12: "No code changes. All code is agent-written", with step F and `.gitignore` accepted as written (phase-12:110)
- **P17-K-12: RESOLVED.** reports/final-report.md:606 now splits the rows correctly against docs/service-limits.md:
  - IL-02, IL-04 and IL-14 have runtime evidence only (:32, :34, :44).
  - IL-06 and IL-11 cite no runtime evidence, because the account value needs Service Quotas (:36, :41).
  - SL-29 is listed separately.

### 2. L2 statements against the code and repair-E1-rag.md

Everything I checked matches:

- **Eight accepted forms:** `AcceptedForms` at :1543-1567. Five absence forms, two connection forms (the controls), one uncertainty form.
- **Gate:** :797-841. `namesTwo && hasNegationOrAbsence(stripped)` at :802; a claim that is not a form gets `AbsenceAmbiguous` at :804. Absence-form reasons are at :820-824, `ConnectionNotFound` at :825-831, and the uncertainty form's `AbsenceNotSearched` at :838.
- **Lists:** "zero" in `Negations` at :1065. `AbsenceWords` adds missing, nonexistent, non-existent, strangers and stranger at :1118-1122.
- **Reason codes:** `ConnectionNotFound` text at :135-136 and in `Reasons.all` at :168. That list has 32 entries (:138-169), so 25 + 7 is correct.
- **Status function:** :981-989.
- **Other line references remapped in api.md:** I checked all of them by grep (:1034, :1047, :1072, :1101, :1105, :1126, :1185, :1227, :1296, :1327, :1387, :1457, :1500, :1504, :1510, :1520, :1579, :1595; ranges :565-603, :606-735, :691-724, :737-767, :768-795). All correct.
- **Tests:** the names at AbsenceRuleSuite :132, :147, :159, :522, :535, :547, :562, :580, :595, :620, :661 and :670 exist. The suite has 41 tests, so the 3-of-41 and 9-of-41 switched-off counts and 208/208, 17/17 and 2,352 match repair-E1-rag.md.
- **Remaining limitations are all named in every changed L2 passage:**
  - wording with no list word ("vanished", "unacquainted"), marked as reasoning over the code, not run
  - P17-L-02, P17-L-03, P17-L-04
  - pronoun claims
  - P17-K-01
  - P17-A-02 (in api.md)
- **Overclaim:** none material. Small attribution and citation issues are P17-L-07 and P17-L-08.

### 3. The human's decisions

- **P17-F-01** is described as accepted as a known limitation, with no amendment and the human checking for symbolic links at the gate. Places: threat-model.md:128, :181; README.md:420; final-report.md:397, :673, :693.
- **The control-slice verdict** stays REQUEST_CHANGES, and "Final reviewer returns APPROVE" is an accepted deviation, not met (same places). This matches prompt.md:63.
- **Verified limits** are accepted as an unplanned deviation, with the 6 rows staying unverified: service-limits.md:8, README.md:421, final-report.md:606.
- **No overall APPROVE claim:** I grepped all seven files for APPROVE. Every APPROVE is scoped to the E1 rag slice or to earlier phases.
- **One mismatch with the recorded decisions:** P17-L-06 below.

### 4. PENDING marks, replay lines, links, identifiers

- The diff removes or adds no PENDING mark and no replay line except the K-10 line (diff lines 143-144).
- No new command was added. The demo-script command keeps its replay line.
- The new link (runbook → `agent-runs/phase-13/human-gate.md`) resolves. The fragments I checked exist as headings: api.md:256 "9.", final-report.md:245 "12.2", :655 "23.4", threat-model.md:175 "5.", service-limits.md:99 "3.". The section 2.3 that threat-model.md:7 refers to is at :52.
- Session 3 reports are cited as plain text, not links. They are not yet in docs/agent-runs/phase-17/; the writer disclosed this as a manual action.
- The added lines contain no identifiers. The student name and email on final-report.md:5 were already there; this diff did not add them.

### Findings

| ID | Severity | File:line | Failure input | Impact | Owner | Interface |
|---|---|---|---|---|---|---|
| P17-L-06 | minor | reports/final-report.md:657 (intro: "the human accepted every other session 2 minor as a known limitation"), :671 (P17-A-06), :672 (P17-A-07), :677 (P17-F-04), :678 (P17-F-05) | runbook-log.md, row "E1 decisions 3-10, spelled out", item (7): "P17-A-06, A-07, F-04 recorded minors, P17-F-05 avoided by a T/Z clone stamp". Item (8) is the known-limitation list, and it does not contain these four. | Four rows are labelled "accepted (known limitation)" when the human called them recorded minors. The F-05 mitigation (the T/Z clone stamp) is left out. The decisions are not stated as recorded. | documentation-engineer | no |
| P17-L-07 | minor | README.md:419; docs/threat-model.md:7, :59; docs/api.md:313-316; reports/final-report.md:693 | These lines say review-report-P17-L-01.md "records ... P16-L-10 closed for wording that contains a list word". That report never mentions P16-L-10 (grep: 0 hits). Also, its own P17-L-04 input "Smith and Jones have a path that is missing in 2015-2025." holds a list word and stays `verified`. | The re-check is given as the source of a statement it does not make, and the headline is wider than the code (the gate needs two full names). This is not hidden, because L-02 and L-04 are listed right after it. The headline should say "for claims that name both researchers in full" and name the real source (the orchestrator's or human's reading). | documentation-engineer | no |
| P17-L-08 | minor | docs/api.md:315, :317 | api.md gives the statuses "now `conflicting`, `partially_supported` and `conflicting`" and "now `unsupported`" with AbsenceRuleSuite.scala:522 and :535 as the source. Those tests only assert "not Verified" plus `AbsenceAmbiguous` (:528-531, :540-543). | The exact statuses are pinned by no test. Their source is the run-output tables in repair-E1-rag.md:47-60, which should be cited instead of, or as well as, the test lines. | documentation-engineer | no |
| P17-L-09 | minor | docs/service-limits.md:8; reports/final-report.md:606, :461; docs/threat-model.md:59, :183 | These lines cite runbook-log.md row "E1 decisions" for the verified-limits acceptance, the minors and P17-L-02 to L-04. That row says only "(3-10) agreed as recommended". The content is in row "E1 decisions 3-10, spelled out" (items 4, 6, 8) and, for L-02 to L-04, in row "E1 code re-check". | A reader who follows the citation does not find the decision text. | documentation-engineer | no |

VERDICT: APPROVE (E1 documentation slice: K-10, K-11, K-12 RESOLVED; the L2 statements match the code and repair-E1-rag.md; no critical or major finding; 4 minors)

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: docs/agent-runs/phase-17/prompt.md (decisions 12 to 15, results rules); Phase 17 session 3 step E1 focused documentation re-check (review-report-P17-L-05.md); HEAD 95a8d9fead71db252af1d7ca833e368a63e14e23 plus uncommitted changes
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools)
TEST RESULTS: none run. Test numbers checked against repair-E1-rag.md and the AbsenceRuleSuite test list (41 tests)
VALIDATION RESULTS: P17-K-10, K-11, K-12 RESOLVED; every L2 file:line reference, reason code, list word and form checked in ClaimVerification.scala (working tree); no PENDING or replay line changed except the K-10 line; changed links and fragments resolve; no identifiers added; no overall-APPROVE claim
ASSUMPTIONS: runbook-log.md in the scratchpad is the human's record of the E1 decisions; the session 3 reports are cited as plain text until the human copies them
RISKS: the plain-text paths docs/agent-runs/phase-17/{repair-E1-rag.md, review-report-P17-L-01.md, runbook-log.md} do not exist until they are copied; L-06 to L-09 stay open unless E11 handles them
MANUAL ACTION REQUIRED: the human copies the session 3 reports into docs/agent-runs/phase-17/; the orchestrator records P17-L-06 to L-09 (minor) for E11 or the summary
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: E11 (PENDING and replay fills), with P17-L-06 to L-09 as optional documentation touch-ups
```
