# repair-docs-demo: post-release-candidate docs-only pass (demo-script.md and README.md)

Phase 17, session 3. HEAD 1823cbf; release candidate 4feef0ff117e9b7b0c1f411e875950e0bde06ba4 (unchanged). Inputs: review-report-demo.md (F-1 to F-8, section 7), agent-report-docs-demo.md, test-report-demo-g7.md, the scratchpad runbook-log (row "E12 G7 replay (test-engineer)"), the committed records cited below. The video plan was not opened in this pass (the work order's decisions were enough) and nothing from it was copied.

## 1. Changes (line numbers are of the new file)

### docs/demo-script.md (278 -> 290 lines)

| Line | Old | New | Work order |
|---|---|---|---|
| :9 | Decision 10 summary without item 7; clip names "the plan's"; clip 02 "not confirm[ed]" caveat; clips 16 and 17 "come from the plan" | Decision 10 cited at `human-gate.md` line 110, now including item 7 (clip 19: the sbt gate passed in a fresh clone, 2,195 module and 399 contract tests; `check.sh` failed only on L-09). File names "the human's". New sentence on "the human's clip inventory (Nov 25; the human's report, not a log row)": clip 02 exists (`441clip02.mov`, 366 s; the Phase 15 log does not record it, row "D16 end"); clip 16 part 1 (`441clip16part1.mov`, 187 s) and clip 17 (`441clip17.mov`, 135 s) exist; clip 21 files parts 1, 2, 3 (421 s) and part 4 (74 s, renamed from a working name), no part 5; names do not follow the Phase 15 log's part numbers (item 22) | F-3, A2 |
| :11 | "...where the same command without the display pipe ran at E4 or E5, cites that run. The unsupported-claim suite of item 16 is also demonstration-only." | "...(without the display pipe, or with only a new output folder) ran at E4, E5 or E12, cites that run." plus: test-engineer replayed the suite and its single-test form at HEAD 1823cbf (same code as 4feef0f), runbook-log row "E12 G7 replay (test-engineer)", report `test-report-demo-g7.md`, in the main checkout, not a fresh clone | A3, A4 |
| :27 (segment G row) | clips "01, 03, 02, 05, 06, 08, 20" | adds "27 (to record; the G7 test, item 16)" | A4 |
| :30 (segment J row) | "21 (parts 1 and 3)" | "21 (parts 1 and 3, and part 4 if it holds the leftover counts; the human's file names, see item 22)" | F-2, A2 |
| :48 (item 14 row) | "recorded (Phase 15; clip 02 per the plan, see above)" | "recorded (Phase 15)" | A2 |
| :50 (item 16 row) | "04 (part 2), 20 / recorded (Phase 15)" | "04 (part 2), 20, 27 / 04 and 20 recorded (Phase 15); 27 to record" | A4 |
| :51 (item 17 row) | "07, 19, 28 / 07 and 19 recorded (Phase 15); 28 to record" | "07, 19, 27, 28 / 07 and 19 recorded (Phase 15); 27 and 28 to record" | A3 |
| :56 (item 22 row) | "21 (parts 1 and 3)" | same pointer as :30 | F-2, A2 |
| :122 (item 7, clip 27 line) | "Replay: replayed in phase 17 (E4, exit 0); evidence: ..." | "Replay: not replayed in phase 17 (demonstration-only line for clip 27, to record; the equivalent `check.sh` run passed at E4 (repeat, R3): ... ; E4 cloned the GitHub remote and passed `--record-dir` ..., while this line makes a local clone and uses the default record folder)" | F-1 |
| :123 (new) | – | "Clip 27 continues:" pointer to item 17's chain and item 16's G7 test | A3, A4 |
| :143, :152 (items 9, 10 Screen) | "segment J (clip 19, item 17)" | "segment J (clips 19 and 27, item 17)" | consistency with A3 |
| :183 (item 14 G1) | clip 02 "(`441clip02`; see ...)" | "(`441clip02`, 366 s, the human's clip inventory; see ...)" | A2 |
| :184 (item 14 G1) | "Only the second send is on the clip (plan; Phase 15 log, row "D16 clip 01 second send")" | "(source: the human's video edit plan)" | F-8 |
| :204 (item 16 Clip line) | "...shot G7 (15 s). Clip 04's part 1..." | adds "and, added to G7, the suite's `unsupported` test from clip 27 (to record, in clip 27's clone)" | A4 |
| :207 (new) | – | "Show (G7, from clip 27, to record)": the single test run alone in clip 27's clone (`+ semantic context cited for a number is unsupported`, `Passed: Total 1, ...`), then test name :103 and assertions :118-121 (HTTP 200, status `unsupported`, `evidenceIds` empty) | A4 |
| :210 (item 16 full-suite command) | "Replay: not replayed in phase 17 (demonstration-only line ...)" | "Replay: replayed in phase 17 (E12, exit 0); evidence: runbook-log row "E12 G7 replay (test-engineer)" (7 of 7; at HEAD 1823cbf, same code as 4feef0f, in the main checkout; ...)" (not asked explicitly; the old line became wrong once the replay existed) | A4 (consistency) |
| :211-213 (new) | – | Commands (clip 27): the G7 single-test command, Replay "replayed in phase 17 (E12, exit 0)" (1 of 1, main checkout, not a fresh clone); `sed -n '103,123p' tests/contracts/.../UnsupportedClaimEndToEndSuite.scala`, Replay "not replayed in phase 17 (display only; demonstration-only line for clip 27, to record; not a README line)" | A4 |
| :219 (item 17 Clips line) | "...clip 28 (to record; 9 s). There is no clone, branch or `claude` session in the video." | adds clip 27 (to record; the clean-checkout bounded run, after item 7's `check.sh`); "The Phase 02 replay exercise of the README (its clone, branch and `claude` session) is not in the video; the only clone in the video is clip 27's fresh clone of `4feef0f`." | A3 |
| :222 (new) | – | "Show (J2, clip 27, to record)": the chained line; success lines Hadoop `[success] Total time: …` after the job JSON (about 150 s once compiled), Spark `Passed: Total 43, Failed 0, Errors 0, Passed 43` (about 220 s), G7 `+ semantic context cited for a number is unsupported` and `Passed: Total 1, ...` (a few seconds once compiled; longer if sbt compiles first); times from the E5 repeat and the test-engineer replay; `check.sh` has already compiled in the clone; do not caption clip 19's Phase 15 counts for this run | A3 |
| :223-227 (new) | – | The verbatim chain (runs inside the clone after item 7's `cd "$D"`), with per-step replay lines: Hadoop = E5.13 in row "E5 part 1 (repeat, R3; in the clone …)" (`[success] Total time: 150 s`, fresh clone of 4feef0f), only the output folder differs; Spark = E5.07 (repeat, R3; 43 of 43; 222 s, from the orchestrator's reading of the E5 repeat log, row "E12 G7 replay (test-engineer)"); G7 = E12 G7 replay (1 of 1; suite alone 7 of 7; HEAD 1823cbf); the chained line as a whole "not replayed in phase 17 (demonstration-only line for clip 27, to record; not a README line)" | A3 |
| :231 (item 17 Also runnable) | "the local bounded run of items 9 and 10 (their "Also runnable" lines)" | "... as separate README lines (...; clip 27 shows the same commands as one chained line)" | consistency |
| :276 (item 22 Clip line) | "clip 21, parts 1 and 3 only (`441clip21part1`, `441clip21part3`) ... Part 2, the file "21 idk" and the refused first removal attempt (L-11) are not shown." | "clip 21, parts 1 and 3 (...), and part 4 (`441clip21part4`, 74 s, renamed from a working name) if it holds the leftover counts ... Part 2 and the refused first removal attempt (L-11) are not shown. There is no part 5 file (the human's clip inventory)." | A2 |
| :277 (item 22 Show) | "The plan names these file parts; the Phase 15 log numbers the recording differently (...), and the plan notes that the leftover counts may be in the file "21 idk" instead." | "Part 3 shows "175 destroyed"; the leftover counts are in part 3 or part 4 (the human's clip inventory). The human's file names do not follow the Phase 15 log's part numbers: the log numbers the recording part 3 = `cleanup.sh --list`, part 4 = the removal, part 5 = the leftover check, while the files are parts 1 to 4 with no part 5." | A2 |
| :279 (item 22 Screen) | "clip 21, parts 1 and 3." | "... (and part 4 if it holds the leftover counts)." | A2 |
| :288 (After recording) | "(`PENDING E11` until the human gives it)" | "The video was not published at tag time; it is due Nov 30, 8 PM (the instructor moved the video deadline; the human records that decision in docs/agent-runs/instructor-approvals.md). The link goes into ... after the tag; both carry the marker `VIDEO LINK: added after the tag` until then. No link is written here before the human gives it." | A5, B8 |
| :289 (new) | – | "Clip 27 holds, in one fresh clone of 4feef0f: item 7's `check.sh` run, item 17's chained bounded run and item 16's G7 test and display line." | A3, A4 |

Unchanged on purpose: F-7 (headings and overview wording), every heading and anchor, clips 22-28 "to record" everywhere (A5), the item 17 clip 28 caption and README line ranges (still true, see section 2).

### README.md (457 lines, unchanged count)

| Line | Old | New | Work order |
|---|---|---|---|
| :106 | "The repository is private until the submission deadline and public after it ([Deliverable index](#deliverable-index), D01)." | "The repository has been public since Nov 24, 2026, with the instructor's permission ([`docs/agent-runs/instructor-approvals.md`](...), "2026-11-24: Repository made public before the deadline"; [Deliverable index](#deliverable-index), D01)." | B6 |
| :149 | "...the Phase 02 exercise below is recorded for the video and is not required for the final gate)." | "...is not recorded for the video and is not required for the final gate; what the video shows for reproduction is in [`docs/demo-script.md`, item 17](docs/demo-script.md#17-reproduction-scripts-agent-replay-instructions-and-a-clean-checkout-bounded-run))." | B7 |
| :172 | "not replayed in phase 17 (agent session; recorded for the video, not required for the final gate)" | "not replayed in phase 17 (agent session; not recorded for the video, see [`docs/demo-script.md`, item 17](...#17-...); not required for the final gate)" | B7 |
| :400 | "**Demonstration video:** PENDING E11." | "**Demonstration video:** `VIDEO LINK: added after the tag`. The video was not published at tag time; it is due Nov 30, 8 PM (instructor-approved deadline; [`docs/agent-runs/instructor-approvals.md`](...))." | B8 |
| :434 | "(for D01, the repository is private only until the submission deadline, as stated there)" | "(for D01, the specification asks for a private repository; this one has been public since Nov 24, 2026, with the instructor's permission, as stated there)" | B6 |
| :438 | "The repository stays private until the submission deadline and is then made public, as the instructor asked (Phase 17 work order, decision 18, E12), which gives ..." | "The repository was private while it was built and has been public since Nov 24, 2026, with the instructor's permission ([instructor-approvals.md], "2026-11-24: ..."; this replaces the earlier plan to make it public after the deadline, Phase 17 work order, decision 18, E12), which gives ..." | B6 |
| :456 (D19) | "PENDING E11 (the script of the narration, ...: [`docs/demo-script.md`](...))" | "`VIDEO LINK: added after the tag` (the video was not published at tag time; it is due Nov 30, 8 PM, instructor-approved deadline, [instructor-approvals.md]); the script of the narration, the 22 demonstration items and which clips exist: [`docs/demo-script.md`](...)" | B8 |

Fill-in marker for the later link: the literal string `VIDEO LINK: added after the tag` (README.md:400, :456; docs/demo-script.md:288). `PENDING E11` no longer appears in either file.

## 2. README line ranges (B9, after the edits)

- "### Reproducing the agentic workflow": README.md lines 94 to 158 (heading :94; next same-level heading :159).
- "### Bounded reproduction exercise: replay Phase 02": README.md lines 159 to 187 (heading :159; next higher-level heading "## Required local software" :188).
- docs/demo-script.md item 17 still cites "lines 94 to 157" and "lines 159 to 186" and `sed -n 94,186p`: these are the last non-blank lines (158 and 187 are blank), so they remain correct; not changed.

## 3. Contradictions and claims I could not verify

1. **Video deadline record.** docs/agent-runs/instructor-approvals.md has no entry about the video yet (`grep -i video`: 0 lines). The new text says "instructor-approved deadline" and links that file, per the work order ("the human records that decision in docs/agent-runs/instructor-approvals.md"). Until the human adds the entry, those three links point at a file that does not yet hold the claim. Time zone: the work order gives "Nov 30, 8 PM" with no zone; I wrote no zone.
2. **"222 s" for the Spark step.** The committed docs/agent-runs/phase-17/runbook-log.md has no 222 s value (its E5 repeat row gives 43/43 only). The value is in the scratchpad runbook-log row "E12 G7 replay (test-engineer)" (the orchestrator's reading of the raw E5 repeat log). The line cites that row; it holds only once the human copies the updated log.
3. **Test-engineer replay as "E12".** The replay-line convention allows `replayed in phase 17 (ENN, exit CODE)`. I used "E12" because the scratchpad log row is named "E12 G7 replay (test-engineer)"; the intro (:11) still says E12 ran in the fresh clone of b4d8f12, so each of these lines states that this replay ran at HEAD 1823cbf in the main checkout. The report `test-report-demo-g7.md` is in the scratchpad; it is cited by name and needs the human's copy into docs/agent-runs/phase-17/.
4. **Clip 21 "421 s".** The work order lists "21part1, 21part2, 21part3 (421 s)"; it is not clear whether 421 s is part 3 alone or the three together. The script repeats it "as the inventory lists it after these three". Please confirm.
5. **Clip 21 part 4 "renamed from a working name".** I did not name the working name; the earlier text's "21 idk" file reference was removed because the inventory now lists four part files and no other file.
6. **One command, several replay lines.** The item 17 chain is one command line but carries four replay lines (one per step plus one for the whole line), because the work order gave per-step replay lines; the convention says one replay line per command. The whole-line line is "not replayed".
7. **Item 16 full-suite command replay changed** from "not replayed" to "replayed (E12, exit 0)": not named in the work order, but the old line was no longer true after the test-engineer replay. Flag for the human.
8. No place where the plan contradicts the repository was followed (the plan was not used in this pass).

## 4. Checks

- **Link check** (grep lists each `](target)`, `ls -d` tests each target relative to the file's folder; no script file):
  - README.md: 215 file links, 0 missing, 0 external; 107 links with a fragment (105 before + the 2 new `docs/demo-script.md#17-...` links).
  - docs/demo-script.md: 57 file links, 0 missing, 0 external; 19 links with a fragment (unchanged).
  - reports/final-report.md: 264 file links, 0 missing, 0 external; 148 links with a fragment (unchanged).
  - Fragments: no heading line changed in the diff (`git diff -U0 | grep -c '^[+-]#'`: 0), so the earlier fragment check (agent-report-docs-demo.md section 4, 0 bad) still applies to the unchanged fragments. The two new fragments were checked by hand: `#17-reproduction-scripts-agent-replay-instructions-and-a-clean-checkout-bounded-run` matches the heading at docs/demo-script.md:217; `#deliverable-index` (README:106) matches README:432. I did not re-run a full slug check (it needs a text transform outside my command list).
- **Identifier scan of added lines** (`git diff -U0` `+` lines of both files, 197 lines, including the earlier uncommitted demo-script rewrite): twelve-digit runs 0; twelve zeros 0; `arn:aws` 1 (pattern text in item 20's blur line, earlier rewrite); `amazonaws` 0; `s3://` 2 (item 4's `example-bucket` placeholder and item 10's blur text, earlier rewrite); AKIA 0; IPv4 0; EMR cluster ID pattern 0; `@` 0; N/N ratios 0; GitHub user name 1, in README:438, the existing D01 repository URL on a line I modified (not added by me; unchanged text, public). The tutorial's sample-application name: its value is not in my inputs, so I could not grep it by value; my added text names no application other than ScholarChain.
- **Diff saved:** /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/73f10a2e-9b01-4d44-898e-9399e20392db/scratchpad/phase17-reports/diff-demo-script-2.txt (499 lines; against HEAD, so it includes the earlier uncommitted demo-script rewrite plus this pass; `git diff --stat`: README.md 14 lines changed, docs/demo-script.md 333).

## Replayed commands and outcomes

None. The documentation agent replays no repository command (Phase 17 planned deviation 1; no sbt allowed). Every new replay line cites the human's or test-engineer's recorded runs.

---

STATUS: COMPLETE
OWNER: documentation-engineer
WORK ORDER: Phase 17, session 3, post-release-candidate docs-only pass (HEAD 1823cbf; release candidate 4feef0ff117e9b7b0c1f411e875950e0bde06ba4): F-1, F-2, F-3, F-8; the human's clip inventory; item 17 clip 27 chain; item 16 G7 unsupported test; video/tag wording; README :106, :149, :172, :400, :434, :438, :456
FILES CREATED: none in the repository. Scratchpad: phase17-reports/repair-docs-demo.md (this report), phase17-reports/diff-demo-script-2.txt
FILES MODIFIED: /Users/brian/Developer/repos/scholarchain/docs/demo-script.md, /Users/brian/Developer/repos/scholarchain/README.md
COMMANDS RUN: read-only git (`git -C ... diff`, `diff --stat`, `diff -U0`; `diff >` the literal scratchpad path); grep, ls, wc, head, tail -n; inline while-loop with `ls -d` for the link check (no script file). Unplanned deviation: `cut -c` on several grep outputs to shorten lines (read-only, stdout only; `cut` is not on the list). No sed/awk/find/cat/cd/sbt/python/zsh scripts, no network, no AWS.
TEST RESULTS: not applicable (documentation only; no sbt)
VALIDATION RESULTS: link check 0 missing in all three files; 2 new fragments verified by hand; no heading changed; identifier scan 0 values (pattern-text hits and the existing public repository URL only); `PENDING E11` gone from both files; marker `VIDEO LINK: added after the tag` at README.md:400, :456 and docs/demo-script.md:288.
ASSUMPTIONS: the human's clip inventory is as the work order states; HEAD 1823cbf has the same code as 4feef0f (test-engineer report); the scratchpad runbook-log rows cited ("E12 G7 replay (test-engineer)") will be copied into docs/agent-runs/phase-17/runbook-log.md with the gate commit; "Nov 30, 8 PM" written without a time zone as given.
RISKS: instructor-approvals.md lacks the video-deadline entry until the human adds it (section 3, item 1); the 222 s value and the E12 G7 row exist only in the scratchpad log until copied (items 2, 3); the "421 s" attribution (item 4); the item 17 chain and the G7 display line have not been run as written (only their parts); the full fragment slug check was not re-run.
MANUAL ACTION REQUIRED: add the video-deadline decision to docs/agent-runs/instructor-approvals.md; copy the updated runbook-log and test-report-demo-g7.md into docs/agent-runs/phase-17/; confirm item 4 (421 s); review section 3 items 6 and 7; run the identifier scan and commit; after upload, replace `VIDEO LINK: added after the tag` with the link (README.md:400, :456; demo-script.md:288) and change clips 22-28 to "recorded" as each is reported.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer review of diff-demo-script-2.txt (orchestrator), then the human's identifier scan and gate commit; then the tag.

## INTERFACE HANDOFF

- Files changed: docs/demo-script.md, README.md (Markdown only).
- Public signatures: none. Anchors: none renamed; new inbound links README.md:149 and :172 -> `docs/demo-script.md#17-reproduction-scripts-agent-replay-instructions-and-a-clean-checkout-bounded-run`.
- Fill-in marker: `VIDEO LINK: added after the tag` (3 places).
- Fixtures: none. Thread-safety: not applicable.
- Limits: nothing else changed; nothing under docs/agent-runs/**, scripts/**, or any code, test or configuration path.
