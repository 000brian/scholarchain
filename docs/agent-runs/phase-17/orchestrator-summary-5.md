PHASE RESULT: READY_TO_TAG_WITH_OPEN_ITEMS

Rule 3 applies. Rule 1 does not: the run reached E12 again. Rule 2 does not: every final-gate item and every tier 1 requirement is met or `deviation` on release candidate 4feef0f; no critical or major finding is open; the post-RC path check and the history scan passed for the commit since summary 4; the fifth autograder run changed no verdict. **The `deviation` lines in orchestrator-summary.md to orchestrator-summary-4.md and below count as accepted only once the human accepts each by name in `human-gate.md`** (the E13 precondition). Open items: accepted deviations, known limitations, recorded minor findings (F1, F2 added), the unconfirmed 421 s attribution (neutral wording), identifier scan 3, the human gate, and E13/E14.

# Phase 17 session 3 summary 5 (orchestrator, short E12 repeat)

- **Why a repeat:** after orchestrator-summary-4.md the human published the demonstration video before the tag and ordered one final docs pass (nine decisions), then ran the autograder a fifth time on the result. Documentation and record changes only, after the release candidate.
- **Scope:** this file records only what changed since orchestrator-summary-4.md. Every table and line in the four earlier summaries still holds unless this file says otherwise.
- **Work order sha256:** c22adfc57c7fde0cae63c8520393c2c81cd12280df7f5df773e70c2e2ec8ac24 (unchanged).
- **Release candidate:** 4feef0ff117e9b7b0c1f411e875950e0bde06ba4 (unchanged; no build input changed since).
- **HEAD at this summary:** 38b5c84f027d140e92c07a1dce0e28bfd0f4b4d8 (= origin/main). The gate commit (this summary, `human-gate.md`, `commit.txt`, identifier-scan-3.md, the updated autograder-response.md, instructor-approvals.md lines 48 and 138, the reports and the log) follows; it is the commit to tag.
- **The human's video plan, as changed on Nov 27:** the video is published before the tag (link in README.md, docs/demo-script.md and reports/final-report.md; D19 met). This replaces the earlier plan (link only in the course submission). Unchanged: tag `v1.0-submission` by **Nov 29, 8 PM CST** (America/Chicago); nothing is committed after the tag.
- **Detail:** `runbook-log.md`, rows from "E12 G1 copy and gate files (human)" on.

## Steps since summary 4

| Step | Status | Evidence (runbook-log row; report) |
|---|---|---|
| G1 copy and gate files (the human) | done | RL "E12 G1 copy and gate files (human)" |
| Final docs pass order (nine decisions) | recorded | RL "E12 final docs pass order (human)" |
| Final docs pass | done: APPROVE (2 minors, open, accepted) | agent-report-docs-final-pass.md; review-report-docs-final-pass.md; diff-docs-final-pass.txt |
| instructor-approvals.md lines 48 and 138 (the human) | done (approved exception); uncommitted until the gate commit | RL "E12 final docs pass decisions (human)", "E12 final docs pass commit (human)" |
| Scan and commit | done | F2: 9 staged, 0 forbidden, 1 expected scan match; F3: 38b5c84 (8 files), pushed |
| Short E12 repeat: path check and history scan of 38b5c84 | done (pass; expected match only) | below |
| Fifth autograder run (on 38b5c84, fresh clone) | done: no verdict changed; V4 8.01 | RL "E12 full autograder on a fresh clone of 38b5c84 (human)" |
| Fifth-run response (X8, "Fifth run (38b5c84)") | done: APPROVE | agent-report-docs-run5.md, draft-autograder-run5.md, review-report-run5.md; scratchpad autograder-response.md |
| This summary | this file | – |
| Human gate update, identifier scan 3, gate commit | pending (the human; scan 3 by the orchestrator) | – |
| E13 tag | pending | precondition: this summary's result line, `human-gate.md` accepting the phase and every listed deviation by name (1 to 42); due by Nov 29, 8 PM CST |
| E14 publication check | pending | records `VISIBILITY: public since 2026-11-24` |

## E12 repeat checks (orchestrator, read-only)

- **Post-RC path check** (`git show --name-status 38b5c84`): `M` README.md, docs/demo-script.md, reports/final-report.md; under docs/agent-runs/phase-17/ 4 `A` (agent-report-docs-final-pass.md, diff-docs-final-pass.txt, orchestrator-summary-4.md, review-report-docs-final-pass.md) and 1 `M` (runbook-log.md). No `D` or `R`; nothing under releases/, docs/agents.md, docs/incidents/** or other phases' records; no build input. **Pass.**
- **History scan** (added lines of 38b5c84; same pattern as summary 4): 1 match, expected: the "Student:" context line from reports/final-report.md:3 (the human's own e-mail, already public) inside diff-docs-final-pass.txt. Key-shaped 0, twelve-digit 0, ARN 0, AWS or API-gateway host 0, model-provider key 0, home path 0, masked digits 0. The uncommitted instructor-approvals.md change: 0.
- **Fifth autograder run** (V1 1.0.8, V2 2.3.5, V3 3.0.5, V4 4.0.0; result files read as data only): no verdict changed; V4 "Points gained: 9.01 of 9.0", "Points deducted: 1.00" (N1 and B3, 0.50 each, minor; their reasons now cite the recorded instructor decisions), "Score (gained - deducted): 8.01". One new item, X8: V1 E03 "never existed" 55 -> 63, all scratchpad paths, category (e) (X1 rows 56-63); `lc-control/control.md` is a scratchpad link-check control file, never committed (checked with `git log --all`). Integrity notes cite instructor-approvals.md :76, :93, :116; none cites lines 48 or 138 or the video. Real mismatches: none.

## Changes to the earlier summaries' tables

| Table, item | Was (summary 4) | Now |
|---|---|---|
| D19 Demonstration video | decided: link in the course submission, not in this repository; not published at tag time | **met:** the video is published before the tag; the link is in README.md:400 and :457 (D19 row), docs/demo-script.md:296 and reports/final-report.md:9 |
| Summary 4, deviation 30 ("not published at tag time" depends on tagging first) | open | superseded by the human's new plan; no document says "not published at tag time" any more |
| Summary 4, manual action 2 (human-gate.md: "D19: the video link is in the course submission") | planned | replaced: D19 met, link published before the tag (the human updated human-gate.md) |
| Autograder | run 4 recorded | run 5 recorded (V4 8.01, no verdict changed), answered in autograder-response.md "Fifth run (38b5c84)" |
| reports/final-report.md | as in 0c4ec52 | 1.1 lists nine instructor-approval items (five Phase 17); 6.1 adds the Phase 17 human actions and the built-in subagent use; lesson 9; validator RESULT: PASS by the human; heading "How this report was made" |
| README.md | as in 0c4ec52 | autograder-response.md linked from Known limitations and the D03 row |

## Finding table additions (since summary 4)

| ID | Severity | Owner | Status |
|---|---|---|---|
| F1 final-report.md:169, deadline-extension entry has no cited row | minor | documentation-engineer | open, accepted (the human) |
| F2 final-report.md:166, does not say the orchestrator fixed the draft | minor | documentation-engineer | open, accepted (the human) |
| run5 response check | – | code-reviewer | APPROVE, no findings |

## Known limitations (changes)

- None added.

## Deviations added (unplanned unless stated, for the human to accept by name in `human-gate.md`)

37. **38b5c84 commit message names a file it does not contain:** the message lists "instructor-approvals entries 48 and 138 corrected (approved exception)", but docs/agent-runs/instructor-approvals.md was not in the commit (8 files; the scan step counted 9 staged). The corrections stayed in the working tree and go into the gate commit. The fifth autograder run therefore read the old lines 48 and 138; no note cites them.
38. **instructor-approvals.md edited by the human again** (approved exception, as before): line 138 (video published before the tag, link in the documents) and line 48 (superseded for R3 by the written confirmation).
39. **Video plan changed after summary 4** (the human's decision): the video is published before the tag and linked in the documents, replacing the binding plan recorded in summary 4; deviation 30 is moot.
40. **Agent commands outside their briefs** (read-only, nothing written outside the scratchpad): documentation-engineer `cd` (four calls in two runs), a zsh `for`/`while` loop with `echo`, `for` loops with `tr` and `printf`, `cut -c`, `ls -l`, `ls -la`, `git -C`.
41. **Orchestrator slips** (nothing written in the repository): one read-only `sed -n` on a committed agent report; one Bash call blocked by the guard (a write target given as a shell variable), repeated with the literal scratchpad path.
42. **diff-autograder-run5.txt not copied:** the review diff of autograder-response.md names the session scratchpad folder in its header; the reviewer read it in the scratchpad; the change is visible in the gate commit itself.

**Approval prompts and guard blocks since summary 4:** the one guard block in item 41. No AWS, model-provider or network command by any agent or the orchestrator.

## Costs

Unchanged: project total $21.46 of the $25 bound (AWS usage covered by promotional credits per the human's Cost Explorer reading). No AWS or model-provider call since summary 4.

## Manual actions for the human (in order)

1. Copy this summary, the updated autograder-response.md, the run-5 reports and the log: the `cp` lines in the orchestrator's message.
2. Update `human-gate.md` so it accepts deviations 37 to 42 by name (in addition to 1 to 36), F1 and F2 as open minors, and D19 met.
3. The orchestrator runs identifier-scan-3.md over every file created or changed after identifier-scan-R3.md; an unexpected match blocks the commit.
4. The section 20 scans, then the gate commit as a separate step (with instructor-approvals.md). Then E13 (tag `v1.0-submission` on the gate commit, by Nov 29, 8 PM CST) and E14. Nothing is committed after the tag.
