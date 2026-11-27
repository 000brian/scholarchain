# agent-report-docs-clips: documentation-engineer read-only check of the human's clip 22-28 report against docs/demo-script.md, Phase 17 session 3, post-release-candidate docs-only pass

(Saved by the orchestrator from documentation-engineer's final message; findings, proposals and report contract kept in substance. HEAD 5b911d9.)

Summary: about 40 line-level edits are needed for clips 22 to 28, all in docs/demo-script.md. No README line is wrong. The human's values match the records; some points need the human's confirmation. Nothing was written.

## 1. Clip names, labels and statuses

- No reference to 27b, 22a, 22b, 27a, 27c or 28b exists in docs/demo-script.md, README.md, reports/final-report.md or docs/*.md.
- Wording that implies a different recording: :120 "only the start and the end are recorded" (it was one continuous take); :72 and :81 clip 23 "one recorded line" (the human split commands one per screen); :122, :222, :223, :227 clip 27 "one line" / "one chained line".
- To confirm: the "441" prefix (reported only for 22a; :9 says every file is named with that prefix and the clip number).
- Status lines to change (proposals use bare names until the prefix is confirmed): :9, :11, :23, :24, :27, :30, :35-:42 (status "to record" to "recorded (Phase 17)"; :35 clip 22 as 22a and 22b; :38 C5 not recorded; :41 clip 27a), :50, :51, :60, replay reasons at :63, :72, :82-:84, :93, :113, :134, :213, :223, :226 (drop ", to record"), :69, :78, :100, :110, :131, :72, :81, :89, :119, :120, :122, :123, :204, :207, :211, :219, :222, :223, :224 (clips 28 and 28b; drop the "(plan)" sentence or state where clip 28 was recorded), :226 (terminal only), :227, :285 (27a holds check.sh, one continuous take; 27c holds the bounded run and G7; no 27b), :286 (statuses set in the gate commit, before the tag; no fill-in wording).
- Missing evidence: :286 requires a runbook-log row for the recordings; none exists yet. A human-approved row must exist for the edits to cite (placeholder ROW).
- :5 stays true; add "what clips 22 to 28 show is the human's report".
- README cited ranges still hold at 5b911d9 (headings at :94, :159, next at :188; lines 102 and 167). Keep README.md unchanged in the gate commit so the ranges do not move.
- To confirm: whether clip 23 showed `ls .claude/agents` and the role-file head (:82, :83); whether clip 27c ran as the one && chain of :223 and which echo lines were shown; where clip 28 was recorded.
- New blur point: :122's clone line clones from the main checkout's absolute path, which holds the home folder name; :127 says "nothing expected" for 27a. The human should check 27a's clone line.

## 2. Display differences

Display-only: one command per screen after clear; output wrapped with fold -s -w 120 or paged with less (applies to :63, :72, :82-:84, :93, :94, :103, :113, :122, :134, :212, :213, :223, :226).

| Line | Listed form | Shown form | Class |
|---|---|---|---|
| :72 | sed range of docs/agents.md, cut -c1-150 | same sed range, grep table rows, columns 2 and 3, backticks stripped, fold, less | display variant; write out the shown form |
| :84 | grep -H tools lines, cut -c1-120 | grep tools lines, sed shortens file names, fold | display variant |
| :226 | sed -n 94,186p README.md, fold -s -w 150, or GitHub | same lines, fold -s -w 120, less, terminal | display variant; drop the GitHub alternative |
| (none) | script table described at :224, no command | sed -n 44,53p scripts/README.md, escaped pipes to "or", columns 2 and 3, fold | new command; needs its own replay line |

scripts/README.md 44-53 are exactly the ten script rows (header :42, separator :43).

Proposed handling (keeps "exactly one replay line per command", demo-script :11 and prompt.md:45):
1. One note after :11: on camera each command was typed one per screen after clear, long output wrapped with fold -s -w 120 or paged with less; clips 23 and 28 used shorter display forms written in those items; these read the same files and lines and only select, strip or wrap output; they are display variants and carry the listed command's replay line.
2. At :72, :84, :226: "Shown as: FORM." inside the existing bullet (no new bullet, no second replay line).
3. At :224 (clip 28b): one command bullet with the script-table form and one replay line, "not replayed in phase 17 (demonstration-only line for clip 28b; not a README line)".
4. Replay-line kind: recommend keeping "not replayed in phase 17 (demonstration-only ...)". Only 27a's check.sh line (:122) and 27c's chain (:223, if confirmed) could become "replayed in phase 17 (E12, exit 0)", and only if the human's recording row records those exit lines. Human's choice; one line per command either way.

## 3. Expected values against what the human saw

- Clip 24 probes: :93 expects BLOCKED and exit 2 (guard-dangerous.sh:7, :63). Seen: three BLOCKED, exit=2. Match.
- Guard self-test: :94 expects 369 passed, 0 failed; runbook-log E5.15 (rows "E5 part 2" and "E5 parts 2 and 3a (repeat, R3)"). Seen 369, 0. Match.
- Stop-hook self-test: :103 expects 69, 0; :101 still says "N"; E5.16 69, 0. Seen 69, 0. Match; :101 "N" can become 69.
- Clip 27a: GATE: sbt passed (check.sh:422 format), RESULT: check.sh ok (check.sh:213), exit=0. Match. check.sh prints no literal "9 of 9" line; it prints one SUMMARY row per gate (check.sh:1050-1057).
- SUMMARY expected (not filled in): module_tests 2362, contract_tests 418 (rows "E4 (repeat, R3)", "E3b (repeat, R3)", "E10 (repeat, R3)", all on 4feef0f). The first candidate b4d8f12 gave 2352 and 399; those values would mean the clone was not 4feef0f. Use :120's caption only if the clip shows 2362 and 418.
- Clip 27c: bounded hadoop exit=0, Spark Total 43 Failed 0, G7 Total 1 Failed 0: match E5.07 (repeat, R3) and row "E12 G7 replay (test-engineer)". Not reported: the Hadoop "[success] Total time" line and the "spark fixtures exit=0" and "G7 ... exit=0" echoes; do not caption them unless confirmed. UnsupportedClaimEndToEndSuite.scala:103 and :118-:121 as :207 says.

## 4. SLF4J lines labelled [error]

- Records: phase-17 rows "E5 part 1" and "E5 part 1 (repeat, R3)": three [error] lines are the SLF4J no-binder startup notice, not a failure; phase-16 runbook-log :39 the same; README.md:276 already says so. E3 and E3b logs had 0 error lines, so the records tie the notice to the LocalBoundedPipeline runMain step (E5.13).
- The demo script does not mention them. A note is warranted. Proposed at :222: sbt prints three SLF4J lines labelled [error] (starting "Failed to load class ... StaticLoggerBinder", the human's report); they are SLF4J's no-binder startup notice, logging setup, not a job or test failure; the chain continued and each step's success line followed, as in E5.13 (rows "E5 part 1" and "E5 part 1 (repeat, R3)"; README.md:276).
- Do not name the printing step unless confirmed; do not state a cause (the ledger pins SLF4J 2.0.19, docs/tool-versions.md:53; no record says which jar brings the older binding).

## 5. C5 optional probe

- :89 "optional shot C5 (5 s)", :38 "C3 (optional C5)", :95 the Claude Code prompt with its replay line (row "E5 optional recorded claude session"), which stays true.
- guard-selftest.sh:504 is the BLOCK case j-echo-terraform-destroy, empty agent (main session); found by grep, not run.
- Proposed at :95: "Optional (shot C5, not recorded for the video)", plus: clip 24 covers the guard decision it would show; the case j-echo-terraform-destroy (guard-selftest.sh:504) is one of the cases behind clip 24's TOTAL 369 passed, 0 failed; the self-test feeds the command to the guard as JSON, so it shows the guard's decision, not Claude Code's on-screen hook message.

STATUS: COMPLETE
OWNER: documentation-engineer
WORK ORDER: docs/agent-runs/phase-17/prompt.md; Phase 17 session 3, post-release-candidate documentation pass, clips 22-28 analysis (read-only)
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: read-only only: Read of docs/demo-script.md, prompt.md 1-222, the scratchpad runbook-log, scripts/check.sh 540-563 and 1040-1069, UnsupportedClaimEndToEndSuite.scala 100-125; grep over docs, README, the final report, phase records, both runbook-log copies, guard-dangerous.sh, both self-tests and check.sh; ls of the scratchpad reports folder; wc -l and shasum -a 256 of both runbook-log copies; diff of the two copies (only difference: row "E12 demo-script step R5a again (human)"); one sed -n 40,55p scripts/README.md. Slips against the work order's command list: that one sed -n and a leading cd in some commands; both read-only, nothing written.
TEST RESULTS: none run
VALIDATION RESULTS: 369 and 69 match E5.15 and E5.16; expected SUMMARY module_tests 2362, contract_tests 418 (row "E4 (repeat, R3)"); case j-echo-terraform-destroy at guard-selftest.sh:504; README ranges 94-157, 159-186 and lines 102, 167 valid at 5b911d9; scripts/README.md 44-53 is the ten script rows; no "27b" anywhere; the demo script does not mention SLF4J.
ASSUMPTIONS: the human's clip report as relayed is accurate; file names other than the 22a one are as given, pending the prefix confirmation.
RISKS: no runbook-log row records the recordings yet (:286 needs one); 27c chain form, clip 23 extra commands and the SUMMARY values unconfirmed; 27a's clone line may show the home folder name; any README edit in the gate commit would shift the cited ranges.
MANUAL ACTION REQUIRED: the human confirms the prefix, the SUMMARY values, the 27c form and echo lines, clip 23's extra commands, where clip 28 was recorded, and the 27a clone line; the orchestrator prepares the recording row(s) with the human's approval.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: the human's confirmations, then a documentation-engineer edit of docs/demo-script.md only, a code-reviewer re-check, the gate commit and the tag v1.0-submission by Nov 29, 8 PM CST
