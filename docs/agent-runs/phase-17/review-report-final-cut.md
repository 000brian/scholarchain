# review-report-final-cut: code-reviewer review of the docs/demo-script.md final-cut alignment (E12 final-cut work order 2), Phase 17 session 3, post-release-candidate docs-only pass

(Saved by the orchestrator from code-reviewer's final message; verdict, checks and findings in substance.)

VERDICT: APPROVE

No critical or major problem in the working-tree change to docs/demo-script.md against HEAD 5b911d9, checked against diff-demo-script-8.txt, repair-docs-final-cut.md, review-report-clips.md and scratchpad runbook-log rows 237, 238, 243, 244, 246. Every order point applied; H-1 to H-3 closed. Three minor findings (documentation-engineer); none blocks.

## Checks

1. Order points applied: global (:5, :9, overview C/J/K, item table items 1, 17, 22); items 1-22 each match their point (item 1 "18 repository agents" and the 22b command under "Recorded, not used in the final video"; item 7 H-1 and the "about 1 s" sentence removed; item 8 range removed; items 9-10 "(clip 27c, item 17)"; narration of items 13, 16, 17, 21 and the two item 20 captions exactly the human's). 25 "Narration (approximate)" labels; no "Say", "Planned caption", "28b", "9:45", "to record" or per-shot seconds left. Blur section :15-21 and each item's Blur line follow section C. Every other change belongs to the approved clip edit.
2. No command added; "Shown as" forms are display variants with the listed replay line; 34 "Replay:" lines, one per command bullet; "video recording" kind only at :133 (27a) and :233 (27c chain), per row 238; 28b gone; 22b keeps its "not replayed" line.
3. Recorded claims backed: 22b and the 27c single test (row 237); clip 19 (Phase 15 rows 110-113); clip 14 (row "D16 clip 14"); incident-F.json on clip 09 (row 100); terminal activities and samples on clip 13 (row 105). "HEAD is now at 4feef0f" from the order, consistent with `git switch --detach 4feef0f`. Clip 20 lines 41-53 from the order; Phase 15 row 114 "first test's source shown", first test spans :36-:56, so no stronger than the records.
4. Narration facts hold: 19,200 tasks (results-summary.md:127, :152, :156); 5,220 visible in CloudWatch, 5,370 in samples (:153); scale-outs 23:01:25 and 23:04:25, 180 s apart (scaling-activities.csv); scale-in 23:24:39 after five empty minutes (autoscaling.tf:166-167; variables.tf:132-135, default 5); samples 18-19 s apart (service-samples.csv); maxReceiveCount 5 (workflow/variables.tf:138-141); replay 0.177 s (results-summary.md:74); suite :41, :52, :53, :103, :118-121, 7 tests; plot markers (SVG :133).
5. The author's two risks are real (M-2, M-3).
6. 25 headings unchanged; no new links; sampled targets exist; README :149 (step 7) and :172 anchor to item 17 resolves; README.md unchanged.
7. Whole-file identifier scan clean (no 10+ digit runs, no masked digits, arn:, amazonaws, @, home paths, IPs, URLs, AKIA); only `s3://` is the example-bucket placeholder at :104; "username and computer name" at :131 are words.
8. No fill-in or post-tag wording (:11 "filled in" refers to earlier steps; :295, :297 "before the tag", "not published at tag time").
9. H-1 closed (:131); H-2 closed (:82, :92, :94, :95, :236; :13 "select, substitute, strip or wrap"); H-3 closed (:230).

## Findings

| # | Severity | File:line | Failure input | Impact | Owner |
|---|---|---|---|---|---|
| M-1 | minor | docs/demo-script.md:71 | Item 1 Show names "the Public label"; neither the order ("GitHub page only") nor row 237 names it. | Small unsupported claim about the clip (probably true). Drop it or source it from the human. | documentation-engineer |
| M-2 | minor | docs/demo-script.md:27, :31-41 | "(plan)" dropped from the overview sentence, but the Time column (A 0:00 to J 9:10) still holds the plan's start times. | A reader may take them as the final cut's. Label the column as plan or approximate times. | documentation-engineer |
| M-3 | minor | docs/demo-script.md:17, :249 | The blur rule says "every console clip (08 to 13)"; the human's rule has no numbers; clip 18 (ECS services table) gets "nothing to blur". | If clip 18 has a console page, the top-bar crop is missing for it. The human confirms; if so, add the crop to item 18's Blur line. | documentation-engineer (with the human's confirmation) |

RISKS noted by the reviewer: (a) experiments/results-summary.md:446 says "15 s describe-services samples" while the raw samples are about 19 s apart (outside the diff; experiment-engineer). (b) Section C no longer lists earlier precautions: the GitHub profile menu (e-mail address) in clip 22a, EMR Summary and Instances panels in clips 10 and 11, ARNs in ECS events in clip 13, queue and alarm detail panels in clip 09; the human's decision, but the human should confirm the final frames show none of them. (c) Item 13 :186 adds "explained" after `indexesQueried` (harmless). (d) :297 "recorded (Phase 17)" for clips 22-28 while 22b is "recorded, not used" (consistent in substance).

STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: docs/agent-runs/phase-17/prompt.md; Phase 17 session 3; review of the final-cut alignment (diff-demo-script-8.txt)
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools)
TEST RESULTS: none (documentation only)
VALIDATION RESULTS: as in Checks
ASSUMPTIONS: rows 237, 238, 244 are the human's and will be copied unchanged in the gate commit; "section D" means section C; clips not seen.
RISKS: as above
MANUAL ACTION REQUIRED: the human confirms clip 18 (M-3) and checks the final frames for risk (b); optionally route M-1 to M-3.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: identifier scan and the gate commit before tag v1.0-submission (by Nov 29, 8 PM CST), with the cited rows copied in the same commit; nothing committed after the tag.
