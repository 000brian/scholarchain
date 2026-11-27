# repair-docs-final-cut: documentation-engineer alignment of docs/demo-script.md with the final video (E12 final-cut work order 2, the human, Nov 27), Phase 17 session 3, post-release-candidate docs-only pass

(Saved by the orchestrator from documentation-engineer's final message; change table and checks in substance.)

Summary: docs/demo-script.md only (README.md unchanged). The human's order applied, with H-1, H-2 and H-3. Link check, identifier scan and grep counts pass; no narration fact contradicted by the records. One `cd` deviation.

## Changes by item

- Global :5 "final cut Nov 27"; narration "the narration of the final video (approximate)". :9 28b deleted; "441clip22b, 441clip19 and 441clip14 were recorded but are not used in the final video"; clip 21 part 4 named "a further clip 21 file (the human's working name)"; "every one of them used in the final video". :13 (H-2) "select, substitute, strip or wrap"; "other display forms". :15 blur paragraph replaced by section C word for word; the mask text gone.
- Overview: "about 10 minutes"; C 22a, 23, 24, 25; J without 19; clip 21 "part 1 and part 3; leftover counts from clip 21"; K "to the end (about 10 minutes)", "two honest-results cards and the end card". Item table: item 1 22a; item 17 07, 27, 28 with the new status; item 22 the new clip 21 wording.
- Item 1: GitHub page only; the 22b command under "Recorded, not used in the final video" (replay line unchanged); "18 repository agents"; caption removed.
- Items 2-3 (H-2): the four exact display commands as "Shown as"; listed commands and replay lines unchanged.
- Items 4, 5, 9-12, 14, 15: captions and shot seconds removed; 9-10 "(clip 27c, item 17)".
- Item 6: gate header, repair record, verdict, README; the `ls` "recorded but not shown", command kept.
- Item 7: start `HEAD is now at 4feef0f` and end (SUMMARY table, 2362 and 418, exit=0); "about 1 s" sentence gone; (H-1) "the normal prompt (username and computer name; no blur needed)".
- Item 8: the four listed things; "(lines 358 to 414)" removed; command kept.
- Item 13: new Show order; semantic stage time gone; the human's narration.
- Item 16: clip 20 run, lines 41-53, 27c lines 103-123; the single-test run lines "recorded but not shown", command and replay line kept; new narration.
- Item 17: clip 19 show and blur sentence deleted; 27c from `bounded hadoop exit=0` to `spark fixtures exit=0`, SLF4J note kept; clip 28 README step 7; 28b command deleted; (H-3) "the only clone in the video is clip 27a/27c's fresh clone"; narration insertion.
- Item 18: J4 last lines, WARN cut; clip 14 "recorded, not used in the final video".
- Items 19-20: dashboard, SQS widget, ECS widget; ECS Events then the plot; terminal activities and samples recorded but not shown, file links kept; the two "Captions (final video)"; one narration for 19-20.
- Item 21: queues, alarm, events.csv; incident-F.json not shown; new narration.
- Item 22: part 1 archive line with sha256, part 3 "175 destroyed", leftover counts from clip 21; typed confirmation and bucket-emptying lines removed.
- Blur: exactly section C (console clips 08-13 crop; 07 account line blurred; 11 History Server rules; 12 legend rule; everything else nothing to blur).
- 25 narration labels "Narration (approximate)".

## Checks

1. Links: 51 unique targets exist; all anchors resolve (four checked by hand: api.md 5.5, 5.6, 5.8 and the instructor-approvals deadline entry); link set identical to HEAD; all 25 headings identical, so README :149, :172 (and :401, :456) resolve.
2. Identifier scan of the whole file: 10+ digit runs 0; asterisks followed by digits 0; arn:, amazonaws, AKIA, @, IPv4, home paths 0; `s3://` 1 (the existing `example-bucket` placeholder in item 4's probe).
3. Grep: "Say (planned)", "Planned caption", "28b", "9:45" 0 each; "Narration (approximate" 25; "Captions (final video)" 1; "not used in the final video" 5; "Replay:" 34, exactly one per command bullet.
4. Narration facts: none contradicted (19,200 tasks; 5,220 visible in CloudWatch, 5,370 in the samples; scale-outs 23:01:25 and 23:04:25; scale-in after five empty minutes, worker_scale_in_minutes 5; Sent = Received = Deleted = 19,200; samples about 18-19 s apart, so "about twenty" is right and the old "fifteen" was not; maxReceiveCount 5; replay 0.177 s; 99 coauthors at suite :41, conflicting :52, removal :53; 7 tests; 18 role files; caption times match; the plot has the markers and the worker-task line).

Diff: diff-demo-script-8.txt (441 lines; 151 insertions, 140 deletions; sha256 217a411b2392ff63c9781ee878c08f0f13995bf604bc21de0ef196202c38786a). No other file changed.

STATUS: COMPLETE
OWNER: documentation-engineer
WORK ORDER: docs/agent-runs/phase-17/prompt.md; Phase 17 session 3; E12 final-cut work order 2 (human, Nov 27)
FILES CREATED: scratchpad diff-demo-script-8.txt; scratch helpers links-head.txt, links-now.txt, h-head.txt in the scratchpad root
FILES MODIFIED: docs/demo-script.md
COMMANDS RUN: read-only grep, ls, wc, head, tail -n, shasum, cut, tr, sort, comm, diff, date -u -r, read-only git (show HEAD:, diff, status, diff --stat); the final diff to the literal scratchpad path. Deviations: one `cd` into the repository root (read-only); cut, tr, sort, comm, diff and date were outside the listed commands (read-only).
TEST RESULTS: none (documentation only)
VALIDATION RESULTS: as above
ASSUMPTIONS: "section D" in the Blur default means section C; console clips are 08-13; clip 18 has no console record, so "nothing to blur"; clip-level inventory lengths (366 s, 187 s, 135 s, 421 s) and evidence times kept; segment start times A-J still the plan's (only K changed, as ordered).
RISKS: overview segment start times 0:00-9:10 come from the plan and may not match the final cut; clip 18 console crop unknown; the cd deviation.
MANUAL ACTION REQUIRED: none beyond the order
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer re-check of diff-demo-script-8.txt; then the scan and the gate commit before the tag (Nov 29, 8 PM CST)
Unverified: final-video contents are from the human's order; "HEAD is now at 4feef0f" named by the order, not a log row; clip 20 lines 41-53 from the order (Phase 15 log says "first test's source shown").
INTERFACE HANDOFF: docs/demo-script.md only; headings unchanged; new labels "Narration (approximate)", "Captions (final video)", "Recorded, not used in the final video"; no command added; no README change.
