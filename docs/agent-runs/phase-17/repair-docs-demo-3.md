# Repair docs-demo 3: video-link wording (documentation-engineer)

## Changes (old / new per line)

README.md:400
- Old: `- **Demonstration video:** `VIDEO LINK: added after the tag`. The video was not published at tag time; it is due Nov 30, 8 PM CST (instructor-approved deadline; [link], "2026-11-24: Deadline extension; demonstration video due Nov 30, 8 PM").`
- New: `- **Demonstration video:** The video link is in the course submission, not in this repository: the video was not published at tag time; it is due Nov 30, 8 PM CST (instructor-approved deadline; [link], "2026-11-24: Deadline extension; demonstration video due Nov 30, 8 PM").`

README.md:456 (D19 row, evidence cell)
- Old: `` `VIDEO LINK: added after the tag` (the video was not published at tag time; ...``
- New: `The video link is in the course submission, not in this repository (the video was not published at tag time; ...` (rest of the row unchanged).

docs/demo-script.md:284
- Old: `- The video was not published at tag time; it is due Nov 30, 8 PM CST (the instructor's deadline extension: [link], "..."). The link goes into the README's "Final report and video" section and deliverable D19 after the tag; both carry the marker `VIDEO LINK: added after the tag` until then. No link is written here before the human gives it.`
- New: `- The video link is in the course submission, not in this repository: the video was not published at tag time; it is due Nov 30, 8 PM CST (the instructor's deadline extension: [link], "2026-11-24: Deadline extension; demonstration video due Nov 30, 8 PM").`

Link targets, anchors and the quoted heading are unchanged byte for byte in all three lines. "not published at tag time" and "Nov 30, 8 PM CST" kept. docs/demo-script.md:286 (clip status rule) left unchanged.

Diff: /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/73f10a2e-9b01-4d44-898e-9399e20392db/scratchpad/phase17-reports/diff-demo-script-5.txt (32 lines, 3 removed and 3 added lines).

## Comparison with docs/agent-runs/instructor-approvals.md:138 (human-owned, not edited)

Line 138: "the repository is tagged v1.0-submission before the video is published; the README (D19) and docs/demo-script.md say the video was not published at tag time and is due Nov 30, 8 PM CST, and the video link goes with the course submission."
Result: matches. README D19 (456) and demo-script (284) both say "not published at tag time" and "due Nov 30, 8 PM CST", and both now say the link is in the course submission. No mismatch.

## Checks

- Wording grep (`after the tag|fill-in|added later|VIDEO LINK:`, case-insensitive) on README.md and docs/demo-script.md: 0 matches in each.
- Link check: `ls -d` found docs/agent-runs/instructor-approvals.md and docs/demo-script.md; the heading `## 2026-11-24: Deadline extension; demonstration video due Nov 30, 8 PM` exists at instructor-approvals.md:122, which gives the GitHub anchor `#2026-11-24-deadline-extension-demonstration-video-due-nov-30-8-pm`. 3 links in changed lines (README 400, README 456 twice counting demo-script link, demo-script 284): all resolve.
- Identifier scan of added lines (12-digit numbers, arn:, s3://, amazonaws, AKIA, email, http(s) URLs, /Users/): 0 hits.

## Unverified / flagged
- docs/demo-script.md:286 reads "When clips 22 to 28 are recorded, their status here changes from "to record" to "recorded", with the runbook-log row of each recording". It does not say "in the gate commit (before the tag)", as the work order describes the rule. Left unchanged per "change nothing else"; if the human wants that phrase written explicitly, it needs a further work order.

STATUS: COMPLETE
OWNER: documentation-engineer
WORK ORDER: Phase 17 session 3, post-release-candidate docs-only fix (video-link wording)
FILES CREATED: none in repository (scratchpad: diff-demo-script-5.txt, repair-docs-demo-3.md)
FILES MODIFIED: README.md (lines 400, 456), docs/demo-script.md (line 284)
COMMANDS RUN: grep (wording, heading, identifier scan), git -C diff (saved to scratchpad), ls -d, wc -l
TEST RESULTS: not applicable (docs only; no sbt)
VALIDATION RESULTS: wording grep 0/0; links 3/3 resolve; identifier hits 0
ASSUMPTIONS: "VIDEO LINK" in the grep meant the marker; plain "video link" in the new wording is intended
RISKS: line 286 rule wording (see above)
MANUAL ACTION REQUIRED: human copies the reports with cp -n; files stay staged with the older version until the human runs git add
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer review of diff-demo-script-5.txt; human gate commit before tag

## INTERFACE HANDOFF
Files changed: README.md, docs/demo-script.md (docs only). No public signatures, fixtures, thread-safety or limits involved.

## Follow-up: line 286

Orchestrator follow-up (human's plan "nothing is committed after the tag"; human decision that clip statuses change only after the human reports each one recorded, in the gate commit). This resolves the line-286 flag above.

docs/demo-script.md:286
- Old: `- When clips 22 to 28 are recorded, their status here changes from "to record" to "recorded", with the runbook-log row of each recording; until then nothing in this script claims that they exist.`
- New: `- The status of clips 22 to 28 changes here from "to record" to "recorded" only after the human reports each one recorded, in the gate commit, before the tag, with the runbook-log row of each recording; until then nothing in this script claims that they exist.`

New diff: /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/73f10a2e-9b01-4d44-898e-9399e20392db/scratchpad/phase17-reports/diff-demo-script-6.txt (33 lines, 4 removed and 4 added lines; git diff exit 0). diff-demo-script-5.txt is kept as it was.
Wording grep (`after the tag|fill-in|added later|VIDEO LINK:`, case-insensitive): README.md 0, docs/demo-script.md 0.
Identifier scan of added lines in diff-demo-script-6.txt: 0 hits. Line 286 has no links.
FILES MODIFIED (updated): README.md (400, 456), docs/demo-script.md (284, 286).
