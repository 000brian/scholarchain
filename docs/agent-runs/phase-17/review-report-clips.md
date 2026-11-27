# review-report-clips: code-reviewer review of the docs/demo-script.md clip 22-28 edit, Phase 17 session 3, post-release-candidate docs-only pass

(Saved by the orchestrator from code-reviewer's final message; verdict, checks and findings verbatim in substance.)

VERDICT: APPROVE

No critical or major issue in the working-tree change to docs/demo-script.md (HEAD 5b911d9), checked against diff-demo-script-7.txt, repair-docs-clips.md, scratchpad runbook-log rows "E12 video recording, clips 22-28 (human)" (line 237), "E12 clip decisions (human)" (line 238) and "E5 optional recorded `claude` session" (line 40), and the cited files. Three minor findings, all documentation-engineer; none blocks.

## Checks

1. Every recorded value matches the human's report and row 237 (clip 24: three BLOCKED exit=2, 369 and 69 passed, 0 failed; 27a: GATE: sbt passed, all 9 gate SUMMARY rows passed, 2362 module and 418 contract tests, RESULT: check.sh ok, exit=0; 27c: the five exit and Passed lines, chain did not stop, three SLF4J [error] lines, git log -1 showed 4feef0f). The Hadoop `[success] Total time` line is explicitly not claimed as seen; planned sbt lines only under "Expected success lines" or "sbt prints". The SLF4J note is supported by README.md:276 and rows "E5 part 1" (line 34) and "E5 part 1 (repeat, R3)" (line 185).
2. No "to record" left; statuses agree across the segment table, item table, clip bullets and "After recording". Item 4's "not to record it" became "to skip the recording" (same meaning).
3. The new replay kind is defined only at :11 and used only at :125 (27a check.sh) and :226 (27c chain). Every command bullet has exactly one "Replay:" line. The "Shown as" forms are described in the new :20 paragraph as display variants carrying the listed command's replay line.
4. The `ls .claude/agents | wc -l` bullet's replay line is legitimate: row line 40 records "substitute `ls .claude/agents | wc -l` printed 18"; the bullet matches README.md:135 word for word.
5. No fill-in, after-the-tag or post-tag-link wording added; :9 and :231 say "before the tag"; the old conditional sentence removed; "later" hits at :256 and :277 are outside the changed hunks; the :11 "filled in ... at steps E11 and E12" refers to earlier steps.
6. The "neutral prompt" risk is real but small (finding 1).
7. Headings unchanged (24 checked); the README :149/:172 anchor resolves; guard-selftest.sh line 504 is j-echo-terraform-destroy and lines 87, 89, 104 the three a01 cases; scripts/README.md 44-53 the ten script rows; README.md not modified (ranges 94-186 at 5b911d9 hold).
8. No identifiers in added lines (home-relative clone source in words; 441clip names, short hashes and /tmp placeholders only; no ratios).
9. The 421 s wording unchanged (:14).
10. Everything changed is clip work (removed: the GitHub-page alternative and the "(plan)" re-check sentence for clip 28; the E4 history on the 27a replay line, still cited at :129).

## Findings

| # | Severity | File:line | Failure input | Impact | Owner |
|---|---|---|---|---|---|
| H-1 | minor | docs/demo-script.md:123 | "Before recording (plan): a fresh folder for every take, a neutral prompt, and `env \| grep SCHOLARCHAIN_CFG_` printing nothing" unchanged; row 237 and :131 record the normal prompt was used. | A reader may assume a neutral prompt in 27a; labelled "(plan)" and :131 states what happened, so not false. Suggest "(the normal prompt was used; see Blur)". | documentation-engineer |
| H-2 | minor | docs/demo-script.md:20, :94, :112, :219 | The human gave exact display commands; the script describes them in words, incompletely (28b omits the backtick and leading-space strips; :94 omits `sed 's/^ //'`); :20 says "only select, strip or wrap" but 28b also substitutes text. | Not false about files and lines read, but the screen cannot be replayed exactly. Write the exact forms as "Shown as" (as :111 and :218 do); :20 "select, substitute, strip or wrap". | documentation-engineer |
| H-3 | minor (existing text) | docs/demo-script.md:221 | Unchanged sentence "the only clone in the video is clip 27's fresh clone of `4feef0f`"; two bullets below, Phase 15's clip 19 is also shown in a fresh clone. | Contradiction; probably means "the only clone of the release candidate". Optional wording repair. | documentation-engineer |

STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: docs/agent-runs/phase-17/prompt.md; Phase 17 session 3, post-release-candidate docs-only pass; review of the docs/demo-script.md clip 22-28 change (diff-demo-script-7.txt)
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools)
TEST RESULTS: none (documentation only)
VALIDATION RESULTS: "to record" 0; "video recording, Nov 26" at :11, :125, :226 only; headings unchanged, anchor resolves; guard-selftest.sh 87, 89, 104, 504 match; scripts/README.md 44-53 the ten rows; README.md:135 and :276 support the wc -l replay line and the SLF4J note; rows 40, 237, 238 support every recorded value; no identifiers in added lines.
ASSUMPTIONS: rows 237 and 238 are the human's report and decisions and will be copied unchanged; the clips themselves were not seen.
RISKS: the cited rows must land in docs/agent-runs/phase-17/runbook-log.md in the same gate commit, before the tag (by Nov 29, 8 PM CST).
MANUAL ACTION REQUIRED: the human copies the rows with the gate commit; optionally route H-1 to H-3 to documentation-engineer.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: identifier scans and the gate commit before tag v1.0-submission (by Nov 29, 8 PM CST); nothing committed after the tag.
