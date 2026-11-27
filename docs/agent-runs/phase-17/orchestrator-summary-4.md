PHASE RESULT: READY_TO_TAG_WITH_OPEN_ITEMS

Rule 3 applies. Rule 1 does not: the run reached E12 again. Rule 2 does not: every final-gate item and every tier 1 requirement is met or `deviation` on release candidate 4feef0f; no critical or major finding is open; the post-RC path check and the history scan passed again over every commit since summary 3. **The `deviation` lines in orchestrator-summary.md, orchestrator-summary-2.md, orchestrator-summary-3.md and below count as accepted only once the human accepts each by name in `human-gate.md`** (the E13 precondition). Open items: accepted deviations, known limitations, recorded minor findings, the unconfirmed 421 s attribution (neutral wording), identifier scan 3, the human gate, and E13/E14.

# Phase 17 session 3 summary 4 (orchestrator, final E12 repeat)

- **Why a repeat:** after orchestrator-summary-3.md, the fourth autograder run was answered (instructor decisions on N1 and B3), the human set the final video plan, recorded clips 22 to 28, and the demo script, README and final report were aligned with the final video. These are documentation and record commits only, after the release candidate.
- **Scope:** this file records only what changed since orchestrator-summary-3.md. Every table and line in the three earlier summaries still holds unless this file says otherwise.
- **Work order sha256:** c22adfc57c7fde0cae63c8520393c2c81cd12280df7f5df773e70c2e2ec8ac24 (unchanged).
- **Release candidate:** 4feef0ff117e9b7b0c1f411e875950e0bde06ba4 (unchanged; no build input changed since).
- **HEAD at this summary:** 0c4ec52eec9e671cb6a7686e527331e5a3e259bd (= origin/main). The gate commit (this summary, `human-gate.md`, `commit.txt`, identifier-scan-3.md, the log) follows; it is the commit to tag.
- **The human's binding video plan:** tag `v1.0-submission` by **Nov 29, 8 PM CST** (America/Chicago); nothing is committed after the tag; the video link goes in the course submission, not in this repository; the video is due Nov 30, 8 PM CST (instructor-approved deadline extension, docs/agent-runs/instructor-approvals.md, entry "2026-11-24: Deadline extension; demonstration video due Nov 30, 8 PM").
- **Detail:** `runbook-log.md`, rows from "E12 demo-video fact sheet" on.

## Steps since summary 3

| Step | Status | Evidence (runbook-log row; report) |
|---|---|---|
| Fourth autograder run (on b049e9e), follow-up | done: APPROVE; commit 1823cbf | V4 8.01: 9.01 gained, 1.00 deducted (N1, B3); HADOOP-04, DATA-02, DATA-05 now met; X5-X7 answered; agent-report-docs-run4.md, repair-docs-run4.md, review-report-run4.md, -run4-2.md; autograder-response.md "Fourth run" |
| Instructor decisions N1 and B3 | recorded (the human's entries, approved exception) | instructor-approvals.md: N1 "acceptable ... No additional corrective action is necessary"; B3 acceptable "provided that oversized records are explicitly detected, counted, and reported rather than silently discarded"; conditions mapped to code in docs/hadoop-job-contracts.md 13.2 |
| Demo-video fact sheet (read-only) | done | video-facts.md, scratchpad only, not copied (the human's instruction) |
| Demo script aligned with the video plan; README and final-report wording | done: APPROVE | agent-report-docs-demo.md; repair-docs-demo.md to -3.md; repair-docs-final-report-video.md; review-report-demo.md to -5.md; test-report-demo-g7.md (G7 test 1 of 1); commit 5b911d9, pushed |
| The human's binding video plan | recorded | RL "E12 demo-script ... (human)" rows; the earlier post-tag video-link suggestions withdrawn |
| Clips 22 to 28 recorded (the human, Nov 26) | recorded | RL "E12 video recording, clips 22-28 (human)", "E12 clip decisions (human)"; agent-report-docs-clips.md (read-only analysis) |
| Clip statuses and display variants | done: APPROVE | repair-docs-clips.md; review-report-clips.md (3 minors, H-1 to H-3) |
| Slide fact check (read-only) | done | RL "Slide fact check"; no report file (the human's instruction); no slide line inaccurate |
| Final-cut alignment (two work orders, the human, Nov 27) | done: APPROVE | repair-docs-final-cut.md, repair-docs-final-cut-2.md; review-report-final-cut.md (3 minors, M-1 to M-3), review-report-final-cut-2.md (no findings); H-1 to H-3 and M-1 to M-3 closed; RL "E12 final-cut decisions (human)" |
| Scan and commit steps | done | R5a (23 paths, scan count 7, expected only), R5b 5b911d9; R6a (12 paths, forbidden 0, scan count 0), R6b 0c4ec52; both pushed |
| E12 repeat: post-RC path check | done (pass) | below |
| E12 repeat: history scan | done (expected matches only) | below |
| E12 repeat summary | this file | – |
| Identifier scan 3, human gate, `commit.txt`, gate commit | pending (the human; scan 3 by the orchestrator) | – |
| E13 tag | pending | precondition: this summary's result line, `human-gate.md` accepting the phase and every listed deviation by name; due by Nov 29, 8 PM CST |
| E14 publication check | pending | records `VISIBILITY: public since 2026-11-24` |

## E12 repeat checks (orchestrator, read-only)

- **Post-RC path check** (`git log --name-status 1823cbf..HEAD`): two commits.
  - 5b911d9: `M` README.md, docs/demo-script.md, reports/final-report.md; `M` docs/agent-runs/instructor-approvals.md (the human's entry, approved exception, as before); `A` and `M` under docs/agent-runs/phase-17/.
  - 0c4ec52: `M` docs/demo-script.md; `A` and `M` under docs/agent-runs/phase-17/.
  - Under docs/agent-runs/phase-17/: 28 `A` and 2 `M` in all. No `D` or `R`; nothing under releases/, docs/agents.md, docs/incidents/** or other phases' records; no build input. **Pass; the only exception is instructor-approvals.md, the approved exception.**
- **History scan** (added lines of each commit since summary 3; key-shaped, twelve-digit, ARN-with-account, AWS host, API-gateway host, model-provider key, e-mail, home-path and masked-digit forms):
  - 0c4ec52: 0.
  - 5b911d9: expected matches only. (a) Home-path text in three agent reports (agent-report-docs-demo.md, repair-docs-demo.md, repair-docs-demo-2.md): the main checkout's path in report-contract lines, the same class as in earlier committed records under docs/agent-runs/; README.md, reports/** and docs/demo-script.md have none. (b) One e-mail: the human's own address in a diff hunk header in diff-final-report-video.txt, copied from reports/final-report.md:3 (pre-existing public text). (c) Pattern names in descriptions of identifier searches (review-report-demo.md, repair-docs-final-report-video.md, repair-docs-demo-3.md). Key-shaped values 0, twelve-digit runs 0, ARNs with an account 0, model-provider keys 0, masked digits 0.
  - Older history: unchanged from the earlier history scans (P16-A-01 the only key-shaped hit).

## Changes to the earlier summaries' tables

| Table, item | Was | Now |
|---|---|---|
| D19 Demonstration video | not met yet (blocked-on-human); three `PENDING` marks | decided by the human's binding plan: README.md:400 and :456 (D19), docs/demo-script.md and reports/final-report.md:9 say the video link is in the course submission, not in this repository; the video was not published at tag time; due Nov 30, 8 PM CST (instructor-approved). No `PENDING` mark remains. |
| D18 Demo script | met | met; aligned with the final video (final cut Nov 27): clips used and not used, narration labelled approximate, two captions, blur column, display variants, replay lines (two "video recording, Nov 26, exit 0" lines: 27a `check.sh` and the 27c chain) |
| Autograder | run 3 recorded; run 4 pending | run 4 recorded (V4 8.01), answered in 1823cbf |
| N1, B3 | open to the instructor | instructor decisions recorded (approved exception); no further action |
| Summary 3, manual action 3 (video link in the README, fill-in after upload) | planned | withdrawn: the link goes in the course submission; nothing is committed after the tag |
| README public-repository wording | — | README.md:106, :434, :438: public since Nov 24 (instructor-approvals.md entry "2026-11-24: Repository made public before the deadline") |

## Finding table additions (since summary 3)

| ID | Severity | Owner | Status |
|---|---|---|---|
| run4 docs findings (m1-m6) | minor | documentation-engineer | RESOLVED (1823cbf) |
| F-1, F-2, F-3, F-8 (demo script) | minor | documentation-engineer | RESOLVED |
| F-7 (headings) | minor | documentation-engineer | open, accepted (the human: leave; anchors stable) |
| G-1 (one replay line), G-2, G-3 | minor, nit | documentation-engineer | RESOLVED |
| demo-4 final-report.md:9 video wording | major | documentation-engineer | RESOLVED (review-report-demo-5.md) |
| H-1, H-2, H-3 (clip edit) | minor | documentation-engineer | RESOLVED (final-cut pass) |
| M-1, M-2, M-3 (final cut) | minor | documentation-engineer | RESOLVED (M-1 kept with the human's confirmation; M-2 "Planned start"; M-3 clip 18 not a console page) |
| experiments/results-summary.md:446 "15 s" samples vs about 19 s raw spacing | minor (outside the post-RC paths) | experiment-engineer | open, accepted (the human: file unchanged; noted in demo-script item 20) |

## Known limitations (changes)

- None added. The slide fact check found no slide line inaccurate; P17-L-02 to L-04 and P17-F-01 remain the human's acceptances at E1 (not the instructor's); the 5,000-author bound is instructor-accepted on condition; the largest team 137 is measured on the DBLP subset.

## Deviations added (unplanned unless stated, for the human to accept by name in `human-gate.md`)

28. **instructor-approvals.md edited by the human again** (approved exception, as before): the N1 and B3 decisions (1823cbf) and the deadline-extension entry (5b911d9).
29. **Withdrawn suggestions:** the orchestrator earlier suggested filling the video link in after the tag (summary 3 manual action 3, review notes); withdrawn by the human's binding plan; all documents reworded (no line suggests a fill-in or a commit after the tag).
30. **Video wording rests on the plan:** "not published at tag time" holds only if the human tags before publishing the video, as the plan says.
31. **421 s attribution unconfirmed:** whether 421 s is clip 21 part 3 alone or parts 1 to 3 was not answered; docs/demo-script.md keeps the human's clip inventory wording, neutral.
32. **Agent commands outside their briefs** (read-only, nothing written outside the scratchpad): documentation-engineer `cd` prefixes, `sed -n` once, `git -C` and `--stat`, `cut`, `tr`, `sort`, `comm`, `diff`, `date -u -r`; scratch helper files in the scratchpad root.
33. **Orchestrator slips** (read-only, nothing written in the repository): one Edit of the scratchpad log failed (anchor not unique) after documentation-engineer was launched, fixed seconds later and the agent told; one long read-only grep over the session transcript, moved to the background and stopped; while classifying history-scan hits, its own tool output showed a home-path prefix and the domain part of the human's e-mail (both already public text; written nowhere).
34. **Home-path text in committed agent reports** (5b911d9, three reports): the same class as earlier committed records; deliverables (README.md, reports/**, docs/**) have none.
35. **No report file for the slide fact check** (the human's instruction); the answer was given in the conversation and logged as a row.
36. **video-facts.md not copied** (the human's instruction); the demo plan stays outside the repository (source material only).

**Approval prompts and guard blocks since summary 3:** none beyond the slips in items 32 and 33. No AWS, model-provider or network command by any agent or the orchestrator.

## Costs

Unchanged: project total $21.46 of the $25 bound (AWS usage covered by promotional credits per the human's Cost Explorer reading, reports/final-report.md:497, :501). No AWS or model-provider call since summary 3.

## Manual actions for the human (in order)

1. Copy this summary and the log: the `cp` lines in the orchestrator's message.
2. Write `docs/agent-runs/phase-17/human-gate.md` (what you reviewed, accepted or rejected, every manual change, every deviation accepted by name from all four summaries, the instructor approvals, remaining risks, the accepted release candidate SHA 4feef0ff117e9b7b0c1f411e875950e0bde06ba4, D19: the video link is in the course submission) and `commit.txt` (`git log --reverse --format='%H %s' 50ba087..HEAD`).
3. The orchestrator runs identifier-scan-3.md over every file created or changed after identifier-scan-R3.md; an unexpected match blocks the commit.
4. The section 20 scans, then the gate commit as a separate step. Then E13 (tag `v1.0-submission` on the gate commit, by Nov 29, 8 PM CST) and E14. Nothing is committed after the tag.
