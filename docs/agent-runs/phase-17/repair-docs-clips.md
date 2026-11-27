# repair-docs-clips: documentation-engineer edit of docs/demo-script.md, clips 22-28 recorded, Phase 17 session 3, post-release-candidate docs-only pass

(Saved by the orchestrator from documentation-engineer's final message; change table in short form, report contract in substance.)

Summary: docs/demo-script.md only. Clips 22-28 marked "recorded (Phase 17)" with the human's file names (441clip22a, 22b, 23-26, 27a, 27c, 28, plus 28b); human decisions 1-8 applied. README.md unchanged (ranges 94-157 and 159-186 do not move). Both evidence rows in the scratchpad runbook-log were read before writing ("E12 video recording, clips 22-28 (human)", "E12 clip decisions (human)").

## Changes (line numbers before the edit)

- :5 adds "What clips 22 to 28 show is the human's report (row ...)".
- :9 recorded by the human on Nov 26, 2026, before the tag; file list; no 27b.
- :11 adds the replay kind `replayed in phase 17 (video recording, Nov 26, exit 0)` (run end to end on camera, exit seen, evidence the row); the clip sentence now says two lines (27a check.sh, 27c chain) carry it, all others display-only.
- New paragraph after :11, "Display on camera (clips 22 to 28)": one command per screen after clear, fold -s -w 120 or less; "Shown as" forms; display variants carry the listed command's replay line.
- :23, :24, :27, :30, :35-:42, :50, :51, :60, :63 (22b), :69, :78, :89, :100, :110, :113, :119, :131, :134, :204, :207, :211, :213, :219, :222, :223, :227: "to record" removed; statuses "recorded (Phase 17)"; clip names 22a/22b, 27a, 27c, 28/28b.
- :72 "first command" plus "Shown as" (in words); :81 "after the ownership map"; new bullet after :82 `ls .claude/agents | wc -l` (18 on clip 23) with the README line 135 replay line; :83 "Shown as: sed -n 1,12p ... | fold -s -w 120"; :84 "Shown as" (in words).
- :93, :94, :103 add the recorded values (three BLOCKED with exit=2; 369 passed, 0 failed; 69 passed, 0 failed); :101 N -> 69.
- :95 C5 not recorded for the video; j-echo-terraform-destroy (guard-selftest.sh line 504, linked), one of the cases behind 369; JSON feed, the guard's decision, not Claude Code's on-screen message; replay line unchanged.
- :120 one continuous take, no 27b, recorded values (GATE: sbt passed, 9 SUMMARY rows passed, module_tests 2362, contract_tests 418, RESULT: check.sh ok, exit=0); caption kept.
- :122 clip 27a; tilde-form clone source noted as a display difference; replay line now the video-recording kind (E4 history dropped here; :126 still cites E4).
- :123 clip 27c, git log -1 showed 4feef0f first. :127 no blur needed (the human's decision), no identifiers.
- :222 recorded exit lines; chain did not stop; SLF4J note (three [error] lines during the Hadoop step, no-binder startup notice, not a failure, as at E5.13; README line 276); the Hadoop "[success] Total time" line not captioned as seen.
- :223 replay line now the video-recording kind; pointer to the steps' separate lines.
- :224 shot 28b; clips 28 and 28b recorded in the terminal from the main checkout at 5b911d9 ("(plan)" sentence dropped). :226 "Shown as: sed -n 94,186p README.md | fold -s -w 120 | less"; GitHub alternative dropped. New bullet clip 28b `sed -n 44,53p scripts/README.md` (filters in words), one demonstration-only replay line.
- :285 441clip27a check.sh one continuous take; 441clip27c chain, G7 test and display line; no 27b. :286 recorded Nov 26, before the tag; statuses from the two rows.
- 421 s wording and all headings unchanged.

## Results

- Diff: diff-demo-script-7.txt, 231 lines; 58 insertions, 54 deletions; file now 290 lines.
- grep "to record": 0. Video-recording replay line: 2 (new :125, :226). No bullet with two replay lines. Post-tag/fill-in wording: 0. Identifier scan of added lines: 0.
- Links: guard-selftest.sh (case at line 504), README.md, scripts/README.md exist; item 17 heading unchanged (README :149, :172 anchors valid).

Unverified: the clip contents are the human's report; the clip 23 ownership-map and tools "Shown as" forms and the 28b filters are written in words, not as exact commands (the exact text was not in the agent's inputs).

STATUS: COMPLETE
OWNER: documentation-engineer
WORK ORDER: docs/agent-runs/phase-17/prompt.md; Phase 17 session 3, post-release-candidate docs-only pass; clips 22-28 recorded, demo-script update
FILES CREATED: scratchpad diff-demo-script-7.txt
FILES MODIFIED: docs/demo-script.md
COMMANDS RUN: Read of inputs; read-only grep, ls, wc -l; git -C status --short, git -C diff --stat, git diff -- docs/demo-script.md into the literal scratchpad path. Slips: one leading cd (read-only) and git -C / --stat beyond the listed form (read-only).
TEST RESULTS: none (documentation only)
VALIDATION RESULTS: as above
ASSUMPTIONS: the human's report is accurate; display filters in words acceptable where the exact command was not given
RISKS: the reviewer may want exact display commands for clip 23 and 28b; :120's "Before recording (plan)" sentence still lists "a neutral prompt" while the blur line records the normal prompt used
MANUAL ACTION REQUIRED: copy the two runbook-log rows with the gate commit; the orchestrator confirms the 28b name
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer re-check of diff-demo-script-7.txt, then the scans and the gate commit before the tag by Nov 29, 8 PM CST
INTERFACE HANDOFF: docs/demo-script.md only; anchors unchanged; new replay-line kind used twice; new bullets `ls .claude/agents | wc -l` and clip 28b; the cited row must exist in the committed runbook-log at the gate commit.
