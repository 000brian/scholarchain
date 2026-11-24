# review-report-final-docs-2.md: Phase 17 session 3, step E11, focused re-check after the documentation repair pass

Reviewer: code-reviewer (read-only). Scope: HEAD c6bbe148cc8aad0f65018c6cada3e6543dcada5b plus the uncommitted Markdown changes in 8 files. Release candidate: b4d8f1229fc145e1492e1a8cbb8a44a368d026af.

Inputs I read:
- review-report-final-docs.md
- repair-E11-docs.md
- diff-E11-docs-2.txt, the hunks around the repaired lines, plus a pattern grep over all `+` lines
- runbook-log.md: the "E11 repair decisions (human)", "E11 repair pass" and "E8 local profile removal (human)" rows, plus the rows the repaired lines cite (sitting-1 note at line 15, E1 decisions, E5 part 4, E5.40, E5 optional recorded `claude` session, E5 human decision for E11, E10)
- the repaired lines in the working tree of README.md, docs/runbook.md, docs/threat-model.md, docs/demo-script.md, docs/final-state.md and reports/final-report.md
- docs/agent-runs/phase-17/tool-versions-p17.txt:11-12
- a Glob of docs/agent-runs/phase-17/

I did not open any forbidden path.

## Result

The repair pass fixed both majors and all nine of the minors it was assigned. Every human decision is reflected as ordered. I found one new minor finding, which existed before this pass. **VERDICT: APPROVE.**

## Per-finding table

| ID | Status | File:line (working tree) | Check |
|---|---|---|---|
| P17-M-01 (major) | fixed | README.md:67 | Now says `ls .claude/agents \| wc -l` (prints `18`) and points to step 5's table and the RL row "E5 optional recorded `claude` session". No `/agents` instruction remains. |
| P17-M-02 (major) | fixed | docs/runbook.md:189 | Now says the row was not replayed in Phase 17 ("no E5 row runs `archive-evidence.sh`") and that the current code of this mode is untested at runtime. This matches runbook:184 and README:319. |
| P17-M-03 | fixed | reports/final-report.md:612; docs/runbook.md:191; README.md:325 | Each says `runtime: tested in phase 17 (E10, exit 0)` and cites the E5.40 dry run and E10, both exit 0 (RL :38 and :63). Each names scripts/README.md:59 as open item P17-M-04. This agrees with final-report:354. |
| P17-M-04 | not changed, by human decision 1 | scripts/README.md:59 | Stays as it is; open item at E12. Not a finding now. |
| P17-M-05 | fixed | docs/threat-model.md:7 | Now says "E1 in commit `b4d8f12` (`b4d8f1229fc145e1492e1a8cbb8a44a368d026af`, the release candidate)". This matches RL :29. |
| P17-M-06 | kept, by human decision 2 | README.md:127, :133, :134, :145; docs/demo-script.md:61, :70 | Wording unchanged. The orchestrator lists the six lines at E12. Not a finding now. |
| P17-M-07 | fixed, as human decision 3 orders | README.md:110, :112 | :110 is `not replayed in phase 17 (…tool-versions-p17.txt:12)`, and line 12 of that file is `2.1.330 (Claude Code)`. :112 records 2.1.331 as "the human's report, not a README replay" and cites RL "E11 repair decisions (human)". The recorded session's 2.1.330 matches RL :40. |
| P17-M-08 | fixed | reports/final-report.md:79 | Adds the two read-only sitting-1 `cd` uses. This matches RL :15 and the RL :22 acceptance. |
| P17-M-09 | fixed | README.md:9; docs/runbook.md:14 | Clone and main tree are now split correctly: E3, E4 and most of E5 ran in the clone; E5.40, E6 and E10 ran in the main tree. README also names the E5.42 second clone, which matches RL :39. |
| P17-M-10 | fixed | docs/final-state.md:129; reports/final-report.md:498 | Both keep "not confirmed on the Bills page". |
| P17-M-11 | fixed | docs/demo-script.md:59, :174 | :59 now says "a count of 18, not a list of names". The :174 "Say" text now states the full-name condition and the surname-only case as recorded limitations. |
| P17-M-12 | fixed | README.md:121, :123, :129, :134 | Step 4 is the single approval step. :121 points to step 4, :129 no longer repeats the approval, and :134 says "disabled in step 4". |
| P17-M-13 | fixed | reports/final-report.md:312, :321, :348, :372, :373, :604, :377 | No "to be copied" is left in the 8 files. All six named reports exist in docs/agent-runs/phase-17/ (Glob). :377 marks agent-report-test-final.md "(copied with the E11 commit)", which is correct because it is not there yet. |

## Section 1.2 move and links

- `### 1.2 Phase 17 deviations accepted by the human (not instructor approvals)` is at final-report:70, with items 1 to 6 at :74-79.
- Section 1.1 (:55-68) now holds only the instructor approvals and the "Not recorded there" note.
- Every `final-report.md#1…` link outside docs/agent-runs/ resolves:
  - final-state:127 goes to `#12-phase-17-deviations-accepted-by-the-human-not-instructor-approvals`. That is the GitHub slug of the new heading, and no other heading has the same slug.
  - README:252 goes to `#15-…`; README:421 and demo-script:176 go to `#122-claim-verification`. None of these is affected by the move.
- The remaining "section 1.1" references (final-report:99, :348, :389, :481, :608, :744) are about the teardown and the budget, which are instructor approvals, so they are still correct.
- No other document cites "section 1.1" for the session 3 deviations.

## Required checks

- **New replay claims.** I checked each repaired line against the RL rows it cites, and each is supported: E5.42 (RL :39), E5.40 (:38), E10 (:63), and the recorded session (:40). `not replayed` is used where no E5 row exists.
- **Final-gate claims.** No document says all final-gate items are met. README:422 and final-report:706 still say "Final reviewer returns APPROVE" is an accepted deviation, not met.
- **Identifiers.** A grep of all `+` lines in diff-E11-docs-2.txt found 0 hits for: twelve-digit runs, `arn:`, amazonaws, AKIA/ASIA, `sk-ant-`, execute-api, `sqs.`, `s3://`, IPv4, and the known fingerprint value.
  - In the 8 working-tree files, the only twelve-digit run is the documented release-record file name, at final-state:59 and runbook:63, on lines this pass did not change.
  - The other IPv4-shaped hits are pre-existing: runbook:224 `127.0.0.1` (loopback) and version strings in service-limits and final-report.
  - The admin user is not named. The local profile name `scholarchain-readonly` and `default` are not identifiers in the sense of the rule.
- **Human reports.** The local profile removal is labelled as the human's report at final-state:130 and final-report:742, citing both RL rows.
- **Video marks.** All three are still open: README:398, README:454 and demo-script:242.

## The human's four E1-carried items

| Item | Result | Where |
|---|---|---|
| (a) L-06 to L-09 wording | holds | L-06: final-report:670, :684, :685, :690 ("recorded minor") and :691 (T/Z stamp named). L-07: README:421, threat-model:7 and :59, final-report:674 and :706, demo-script:176; api.md:316 is unchanged since my first check. L-08: api.md:315 and :317, unchanged. L-09: final-report:474 and :619, threat-model:59, and service-limits:8 (unchanged), all citing row "E1 decisions 3-10, spelled out". |
| (b) "applies only when both full names" stated as a known limitation in every L2 passage | holds | README:421; threat-model:7 and :59 (:38 names surname-only, P17-L-04); api.md:316; final-report:273, :674 and :706; demo-script:174 (now with the condition) and :176. |
| (c) no `/agents` instruction left | holds | A grep for `` `/agents `` and ` /agents` across README, docs/*.md and reports/*.md finds only "wizard has been removed" or "not replayable" notes: README:136, demo-script:61 and final-report:78. |
| (d) note that the human disabled `github` at the start prompt | holds | README:123 ("the human disabled `github` at it"), README:127 (replay line), and README:121 and :134. This matches RL :40. |

## New findings

| Severity | File:line | Failure input | Impact | Owner |
|---|---|---|---|---|
| minor | docs/demo-script.md:9 | The legend says the demonstration-only probes are "the `sed -i.bak` and `tail -f` probes of item 6". Those probes are in item 4 (demo-script:71-72). Item 6 starts at :86 and has no `sed -i.bak` line (grep). This existed before the repair; E11 rewrote line 9 but kept the stale item number. | A cross-reference in the video script points to the wrong item. There is no evidence or replay impact. It does not block. If it is fixed, change "item 6" to "item 4". | documentation-engineer |

Observation, not a finding: final-report:746 says "every E5 line exit 0" in the sentence about the fresh clone. That line was not changed in this pass, and its subject ("these", the listed local items) did run in the clone. E5.40 is described separately at final-report:354.

VERDICT: APPROVE

(Scope: the E11 documentation re-check only. The final-gate item "Final reviewer returns APPROVE" stays an accepted deviation, not met, because F-1's REQUEST_CHANGES for P17-F-01 stands.)

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: docs/agent-runs/phase-17/prompt.md E11 (line 352), results rules (lines 37-45), rule line (line 213); orchestrator's E11 focused re-check order (HEAD c6bbe148cc8aad0f65018c6cada3e6543dcada5b plus uncommitted Markdown changes in 8 files; RC b4d8f1229fc145e1492e1a8cbb8a44a368d026af)
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools)
TEST RESULTS: none run (Markdown-only re-check)
VALIDATION RESULTS: P17-M-01, M-02, M-03, M-05 and M-07 to M-13 are fixed at the cited lines, with no new contradiction. M-04 and M-06 are left as the human decided. The 1.2 heading exists, its slug resolves from final-state:127, and no other link targets the old block. Items (a) to (d) hold. Identifier grep of the diff's added lines: 0 hits. Video marks still open (3). No unsupported replay claim and no "all final-gate items met" claim. 1 new minor finding (demo-script:9 "item 6" should be "item 4"), which existed before this pass.
ASSUMPTIONS: the scratchpad runbook-log.md is the human's authoritative record. diff-E11-docs-2.txt reflects the working tree; I confirmed this by reading each repaired line in the working tree, but I did not run git. api.md and service-limits.md are unchanged since my first E11 check, as the repair report says.
RISKS: (1) Citations of session 3 RL rows dangle until the human copies the session 3 runbook-log.md into docs/agent-runs/phase-17/. (2) agent-report-test-final.md is cited as "copied with the E11 commit" and is not in the folder yet. (3) scripts/README.md:59 and the docs differ on purpose until E12 lists P17-M-04. (4) The six `exit n/a` lines depend on the orchestrator listing them at E12.
MANUAL ACTION REQUIRED: copy the session 3 runbook-log.md, agent-report-test-final.md, manifest-checksums.txt, agent-report-docs-E11.md, review-report-final-docs.md, repair-E11-docs.md and this report into docs/agent-runs/phase-17/ with the E11 commit. At E12, list P17-M-04, the six P17-M-06 lines, and the not-replayed lines. Optionally decide whether to fix the demo-script:9 item number (minor; no repair round required).
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: orchestrator's identifier-scan-2.md, then the human's E11 commit
```
