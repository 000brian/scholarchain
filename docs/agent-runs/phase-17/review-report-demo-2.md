# review-report-demo-2: code-reviewer re-check of documentation-engineer's repair (docs/demo-script.md and README.md), Phase 17 session 3, post-release-candidate docs-only pass

(Saved verbatim by the orchestrator from code-reviewer's final message.)

VERDICT: APPROVE

All of the human's decisions are applied and hold up against the records. The README part of the diff changes only the seven lines that were named. I found no critical or major problem. There is 1 minor finding (G-1) and 2 nits (G-2, G-3). None of them blocks the scan or the commit. G-1 is a short wording repair and is worth doing before the commit if the human wants the "exactly one replay line" rule to hold literally.

## 1. Decisions applied, checked against the records, nothing else changed

| Decision | Where | Check |
|---|---|---|
| F-1 | demo-script.md:122 | Now reads "not replayed in phase 17 (demonstration-only line for clip 27, to record; the equivalent `check.sh` run passed at E4 (repeat, R3) ...)". It names the two differences: the GitHub clone and `--record-dir` at E4, against a local clone and the default record folder here. This matches log row "E4 (repeat, R3)" (scratchpad runbook-log:184). Fixed. |
| F-2 | :30, :56 | Both point to item 22 and the human's file names. Fixed. |
| F-3 | :9 | Item 7 is now in the decision 10 summary. The wording equals phase-15/human-gate.md:110 ("clip 19: the sbt gate passed in a fresh clone, 2,195 module and 399 contract tests; `check.sh` failed only on L-09"). Fixed. |
| F-8 | :184 | Now "(source: the human's video edit plan)"; the claim no longer rests on the log row. Fixed. |
| F-7 | headings :202, :217 | Left unchanged, as decided. No heading changed, so all anchors are stable. |
| Clip inventory | :9, :183, :276-279 | Labelled "the human's clip inventory (Nov 25; ... not a log row)". Clip 02 366 s, clip 16part1 187 s, clip 17 135 s; clip 21 parts 1 to 4 (part 3 421 s, part 4 74 s, renamed), no part 5; part 3 shows "175 destroyed"; the leftover counts are in part 3 or part 4; the file names do not follow the log's part numbers. The supporting log claims also hold: phase-15/runbook-log.md:84 says clip 02 is "not confirmed"; rows "D19 runtime-off + cleanup-mode plan" (:85) and "D20 deploy" (:86) exist; no row names clip 16 or 17. The "not confirmed" caveat wording has been removed. |
| Item 17 chain | :219, :222-227, :123, :289 | Present, with exit lines, times, success lines and per-step replay lines (section 2). The Phase 02 replay exercise is stated as not in the video (:219). |
| Item 16 G7 | :204, :207, :211-213 | The single-test command and `sed -n '103,123p'` are present. Line numbers verified in UnsupportedClaimEndToEndSuite.scala: test name at :103; assertions at :118 (200), :120 (`unsupported`) and :121 (`evidenceIds` Nil). Exactly one test name contains "semantic". |
| README:106, :434, :438 | | Each says public since Nov 24, 2026 with the instructor's permission. The cited heading "2026-11-24: Repository made public before the deadline" is instructor-approvals.md:50, and :60 confirms the date. The D01 sentence keeps the history of the old plan ("this replaces the earlier plan ..."). |
| README:149, :172 | | Each says "not recorded for the video" and links to demo-script item 17. |
| README:400, :456 and demo-script :288 | | Each has the marker `VIDEO LINK: added after the tag`, "not published at tag time; due Nov 30, 8 PM". `PENDING E11` is gone. The instructor-approvals.md entry for the video deadline is not there yet (grep "video" finds no match in that file). Noted, not a finding, as the work order says. |
| Clips 22-28 | :23-24, :30, :35-42, :50-51, item bodies, :290 | All still "to record"; none is called recorded. |
| Scope | | The README diff has 7 hunks, and each changes exactly one named line (diff-demo-script-2.txt:9-60). The demo-script changes beyond the earlier reviewed rewrite match the agent's change table. No other file was changed (session-start git status: only README.md and docs/demo-script.md). |

## 2. The chain line (:223)

- zsh syntax: `*semantic*` sits inside double quotes, so zsh does not glob it and sbt receives it literally. `$(date -u +%H%M%S)` inside the quoted argument expands before sbt runs. In `a && echo && b && echo && c && echo || echo "chain stopped: exit=$?"` the `||` binds to the whole AND list, so `$?` is the status of the step that failed. The literal "exit=0" echoes run only on success. The line is sound. It depends on item 7's line having already done `cd "$D"`, and :223 says so.
- Step forms against what was replayed: Hadoop equals README:276 `sbt "hadoop/Test/runMain org.scholarchain.hadoop.LocalBoundedPipeline /tmp/p17-hadoop-STAMP"` (the E5.13 form), with only the output folder changed (neither form has `-batch`); Spark equals README:261/:277 (E5.07); G7 equals test-report-demo-g7.md command 2 character for character.
- Success lines and times: Hadoop `[success] Total time: 150 s` matches E5.13 (log:185), "after the job JSON" holds (LocalBoundedPipeline.scala:103-106 prints the report JSON); Spark 43 of 43, "about 220 s", from the 222 s in log row :223; G7 `+ semantic context cited for a number is unsupported` and `Passed: Total 1, Failed 0, Errors 0, Passed 1` match the test report (lines 30, 32), "a few seconds once compiled" matches 2 s warm.
- Honesty: the chain as a whole is marked "not replayed in phase 17". That is honest, but it is the subject of G-1.

## 3. The agent's points

- "E12" for the test-engineer replay (:210, :212, :226): acceptable, because the log row is named "E12 G7 replay (test-engineer)", and :210 and :212 each say "in the main checkout" (:212 adds "not a fresh clone"). Two small gaps remain: :11 still defines the ENN form as "a command the human replayed ... E12 ran in the fresh clone of ... `b4d8f12`"; :226 (the G7 step of a chain that runs in a fresh clone) leaves out "in the main checkout". Both are G-2 (nit).
- Updating the item 16 full-suite replay line (:210): correct and needed; the line says honestly who ran it and where.
- "421 s": passed on faithfully (:9); the human should confirm.
- Four replay lines on one command: G-1.

## 4. README line ranges

"### Reproducing the agentic workflow" runs from :94 to :158 (:158 is blank; the next same-level heading is at :159). "### Bounded reproduction exercise: replay Phase 02" runs from :159 to :187 (:187 is blank; "## Required local software" is at :188). The demo-script :228 ranges "94 to 157" and "159 to 186" and `sed -n 94,186p` (:230) end on the last non-blank lines and stay correct. The :232 note about README lines 102 and 167 showing the public GitHub user name is still true.

## 5. Links

The fragment `#17-reproduction-scripts-agent-replay-instructions-and-a-clean-checkout-bounded-run` (README:149, :172) matches the GitHub slug of the heading at demo-script.md:217. New file links resolve (`docs/agent-runs/instructor-approvals.md` from README, `agent-runs/instructor-approvals.md` from docs/, `agent-runs/phase-15/human-gate.md`, `#deliverable-index` at README:432). No heading changed in either file.

## 6. Identifiers in added lines

Grep of the `+` lines of diff-demo-script-2.txt and of the current demo-script.md, for 10-or-more-digit runs, `arn:`, `amazonaws`, `s3://`, IPv4, EMR cluster IDs, AKIA, `@` and N/M ratios: the only hits are pattern text in the earlier rewrite (`s3://` at :93 `example-bucket` placeholder and at :154 blur text; `arn:` at :263 blur text). New ratios are in words. The GitHub user name at README:438 is pre-existing public text. The all-digit release-record short commit does not appear. The sample-application name could not be checked by value; the added text names no application other than ScholarChain.

## Findings

| # | Severity | File:line | Failure input | Impact | Owner |
|---|---|---|---|---|---|
| G-1 | minor | docs/demo-script.md:223-227 | The one chained command carries four replay lines: three "replayed in phase 17 (E5/E12, exit 0)" step lines plus one "not replayed" line for the whole chain. demo-script.md:11 says "Every command below carries exactly one replay line", and the Phase 17 work order (docs/agent-runs/phase-17/prompt.md:45) requires exactly one per command. | The document contradicts its own rule. Suggested fix: one line, `not replayed in phase 17 (demonstration-only line for clip 27, to record; not a README line; its steps were replayed separately: Hadoop E5.13 (repeat, R3; 150 s; fresh clone of 4feef0f; only the output folder differs), Spark E5.07 (repeat, R3; 43 of 43; 222 s), G7 row "E12 G7 replay (test-engineer)" (1 of 1; HEAD 1823cbf, main checkout))`, following the F-1 fix at :122. | documentation-engineer |
| G-2 | nit | docs/demo-script.md:11, :226 | :11 defines `replayed in phase 17 (ENN, exit CODE)` as "a command the human replayed ... E12 ran in the fresh clone of ... b4d8f12", but :210, :212 and :226 use "(E12, exit 0)" for test-engineer's run at 1823cbf in the main checkout; :226 also leaves out "in the main checkout". | Small tension, disclosed elsewhere. Widen the definition and add "in the main checkout" to :226; if G-1 is applied, :226 disappears. | documentation-engineer |
| G-3 | nit | docs/demo-script.md:277 | The shot label "(J7, part 3)" lists the leftover counts as part 3 content; the next sentence says "in part 3 or part 4". | Wording such as "(J7, part 3, and part 4 if it holds the leftover counts)" would be consistent with :276 and :279. | documentation-engineer |

Notes for the human (not findings): the instructor-approvals.md entry for the video deadline is still to be added; "Nov 30, 8 PM" is written without a time zone (log row :204 places the deadlines in America/Chicago); the 222 s value and the "E12 G7 replay" row exist only in the scratchpad runbook-log until copied; please confirm the 421 s attribution.

STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: ScholarChain Phase 17, session 3, post-release-candidate docs-only pass: read-only re-check of documentation-engineer's repair after review-report-demo.md (APPROVE or REQUEST_CHANGES)
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools)
TEST RESULTS: not applicable (documentation only)
VALIDATION RESULTS: every decision applied and true against phase-15/human-gate.md:110; phase-15/runbook-log.md:78, :84-88, :113-114; instructor-approvals.md:50-60; scratchpad runbook-log :184-185, :204, :222-223; test-report-demo-g7.md; README:261, :276, :277; LocalBoundedPipeline.scala:103-106; UnsupportedClaimEndToEndSuite.scala:103-123. README diff: exactly the 7 named lines. Chain line sound for zsh; each step equals its replayed form except the Hadoop output folder. README ranges 94-158 and 159-187 confirmed. New fragment links resolve; no heading changed. Identifiers in added lines: no values found.
ASSUMPTIONS: the human's clip inventory is as the work order states; HEAD 1823cbf has the same code as 4feef0f; the scratchpad runbook-log rows will be copied with the gate commit.
RISKS: the instructor-approvals.md video entry is still missing; 421 s attribution unconfirmed; the chain and the `sed` display line have not been run as written, only their parts; until G-1 is fixed the chain does not follow the "exactly one replay line" rule.
MANUAL ACTION REQUIRED: decide G-1 to G-3; add the video-deadline entry; confirm 421 s; copy the updated runbook-log and test-report-demo-g7.md; run the identifier scan, then commit.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: the human's decision on G-1 to G-3; then the identifier scan and commit; recording of clips 22 to 28 before the tag (Nov 29, 8 pm CST); video link fill-in after the tag.
