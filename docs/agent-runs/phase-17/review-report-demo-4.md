(Saved verbatim by the orchestrator from code-reviewer's final message.)

VERDICT: REQUEST_CHANGES

Summary: The diff (F/diff-demo-script-6.txt) is clean. All four changed lines are correct, nothing else changed, and the added lines contain no identifiers. One finding outside the diff blocks the verdict. `reports/final-report.md:9` still says the video link is still to come, which breaks the binding plan that no line may suggest a fill-in after the tag. If the human treats hits outside README.md and docs/demo-script.md as report-only, as the work order allows, the diff itself would be APPROVE.

## Checks

(1) The four changed lines. All verified against the current files.
- README.md:400 says "The video link is in the course submission, not in this repository: the video was not published at tag time; it is due Nov 30, 8 PM CST ...".
- README.md:456 (D19) says "The video link is in the course submission, not in this repository (the video was not published at tag time; it is due Nov 30, 8 PM CST ...".
- docs/demo-script.md:284 uses the same wording, with the relative link `agent-runs/instructor-approvals.md#...`.
- In all three lines, "not published at tag time", "Nov 30, 8 PM CST", the link target, the anchor `#2026-11-24-deadline-extension-demonstration-video-due-nov-30-8-pm` and the quoted heading "2026-11-24: Deadline extension; demonstration video due Nov 30, 8 PM" are kept. The heading matches instructor-approvals.md:122 exactly.
- docs/demo-script.md:286 now says clip statuses change "only after the human reports each one recorded, in the gate commit, before the tag". This matches the human's plan.

(2) Grep for post-tag, fill-in or video-link wording. Patterns, case-insensitive: after the tag, fill-in, fill in, added later, VIDEO LINK, video link, later commit, after tagging, post-tag, still to come, will be added, be filled, and every "video" mention in the three other files.
- README.md: no hit implies a post-tag commit or link. :11 "values you fill in" is about command placeholders. :106 "E14 runs the exact tag checkout after tagging" is a read-only check, not a commit. :400 and :456 are the new wording.
- docs/demo-script.md: no hit (:284 is the new wording).
- reports/final-report.md:9 is a hit: "The demonstration video link is still to come from the human." See finding 1.
- reports/final-report.md:349 "no later commit changes `infra/terraform/`" is about the Terraform freeze. Not relevant.
- docs/final-state.md and docs/runbook.md: no hits.

(3) Matches instructor-approvals.md:138. That line says README (D19) and docs/demo-script.md state the video was not published at tag time and is due Nov 30, 8 PM CST, and that the video link goes with the course submission. Both files now say exactly this.

(4) Nothing else changed. The diff has 4 removed and 4 added lines (README 400 and 456, demo-script 284 and 286), and the context lines are unchanged.

(5) No identifiers in the added lines: no account ID, ARN, s3://, bucket, host, key, email or absolute user path. The only links are relative repository paths.

## Findings

| Severity | File:line | Failure input | Impact | Owner |
|---|---|---|---|---|
| major | reports/final-report.md:9 | Exact text: "The demonstration video link is still to come from the human." | The tagged final report (D18) says the link will come later. Because nothing is committed after the tag, this sentence stays wrong in the graded commit. It goes against the binding plan ("no line may suggest a later commit or fill-in after the tag") and does not match instructor-approvals.md:138 or the new README and demo-script wording. Suggested direction, for the owner to decide: say the link is in the course submission, not in this repository. | documentation-engineer |

STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 17 session 3, post-release-candidate docs-only fix (video-link wording plus the line 286 follow-up), quick read-only check of F/diff-demo-script-6.txt
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools)
TEST RESULTS: not applicable (docs only)
VALIDATION RESULTS: the four changed lines are correct, and the kept phrases, link, anchor and heading are byte-identical; the wording matches instructor-approvals.md:138; the diff has no other changes and no identifiers; the grep found one hit outside the diff: reports/final-report.md:9.
ASSUMPTIONS: the staged index holds the content approved in review-report-demo-3.md, so diff-demo-script-6.txt shows only the new changes; README.md:106 "after tagging" is a read-only checkout check, not a post-tag commit.
RISKS: if reports/final-report.md:9 is not fixed in the gate commit, the tagged report contradicts the README, the demo script and the plan; README.md:438 contains the public repository URL (outside the diff, pre-existing).
MANUAL ACTION REQUIRED: the human decides whether the final-report line is fixed before the tag or accepted as report-only; the human copies this report with cp -n.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: documentation-engineer repair of reports/final-report.md:9 (one round), re-check by code-reviewer, then the human gate commit and the tag v1.0-submission by Nov 29, 8 PM CST
