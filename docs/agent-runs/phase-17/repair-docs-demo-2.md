# repair-docs-demo-2: G-1, G-2, G-3 and the video-deadline pointers (demo-script.md and README.md)

Phase 17, session 3, post-release-candidate docs-only pass. Inputs: review-report-demo-2.md (Findings G-1 to G-3), repair-docs-demo.md, docs/agent-runs/instructor-approvals.md:122 (read, not edited). Line numbers are of the new files (docs/demo-script.md 290 -> 286 lines; README.md 457 lines, unchanged count).

## 1. Changes

| # | File:line (new) | Old | New |
|---|---|---|---|
| G-1 | docs/demo-script.md:223 (old :223-227) | Chain command ending "`... || echo "chain stopped: exit=$?"`. Replay, step by step:" followed by four sub-bullets: Hadoop step "replayed in phase 17 (E5, exit 0) ... E5.13 ..."; Spark step "replayed in phase 17 (E5, exit 0) ... E5.07 ..."; G7 step "replayed in phase 17 (E12, exit 0) ..."; "The chained line as a whole: not replayed in phase 17 (demonstration-only line for clip 27, to record; not a README line)." | Same command, then one line: "Replay: not replayed in phase 17 (demonstration-only line for clip 27, to record; not a README line; its steps were replayed separately: Hadoop, runbook-log E5.13 in row "E5 part 1 (repeat, R3; in the clone …)" (150 s; fresh clone of `4feef0f`; only the output folder differs); Spark, runbook-log E5.07 (repeat, R3; 43 of 43; 222 s, from the orchestrator's reading of the E5 repeat log, runbook-log row "E12 G7 replay (test-engineer)"); G7, runbook-log row "E12 G7 replay (test-engineer)" (1 of 1; the suite alone 7 of 7; test-engineer, at HEAD `1823cbf`, same code as `4feef0f`, in the main checkout))." The success lines and times stay as descriptive text in the "Show (J2, clip 27, to record)" bullet at :222 (unchanged). |
| G-2 | docs/demo-script.md:11 | "... or `replayed in phase 17 (ENN, exit CODE)` for a command the human replayed in Phase 17 steps E3 to E6 and E12 (filled in ...; E12 ran in the fresh clone of the first release candidate `b4d8f12`)." | adds after that parenthesis: ", or that test-engineer replayed, runbook-log row "E12 G7 replay (test-engineer)", in the main checkout at HEAD `1823cbf` (the lines that cite this row say "in the main checkout")." Every remaining use of the row says "in the main checkout": :210, :212 (unchanged, already did), :223 (new G-1 line); the old :226 is gone with G-1. `grep "E12 G7 replay" | grep -v "main checkout"`: 0 lines. |
| G-3 | docs/demo-script.md:273 (old :277) | "**(J7, part 3):** the typed confirmation ..." | "**(J7, part 3, and part 4 if it holds the leftover counts):** the typed confirmation ..." (matches :272 and :275 and the "part 3 or part 4" sentence). |
| 4 | docs/demo-script.md:284 (old :288) | "(the instructor moved the video deadline; the human records that decision in [`docs/agent-runs/instructor-approvals.md`](agent-runs/instructor-approvals.md))." | "(the instructor's deadline extension: [`docs/agent-runs/instructor-approvals.md`](agent-runs/instructor-approvals.md#2026-11-24-deadline-extension-demonstration-video-due-nov-30-8-pm), "2026-11-24: Deadline extension; demonstration video due Nov 30, 8 PM")." |
| 4 | README.md:400 | "(instructor-approved deadline; [`docs/agent-runs/instructor-approvals.md`](docs/agent-runs/instructor-approvals.md))." | "(instructor-approved deadline; [`docs/agent-runs/instructor-approvals.md`](docs/agent-runs/instructor-approvals.md#2026-11-24-deadline-extension-demonstration-video-due-nov-30-8-pm), "2026-11-24: Deadline extension; demonstration video due Nov 30, 8 PM")." |
| 4 | README.md:456 (D19) | "instructor-approved deadline, [`docs/agent-runs/instructor-approvals.md`](docs/agent-runs/instructor-approvals.md));" | "instructor-approved deadline, [`docs/agent-runs/instructor-approvals.md`](docs/agent-runs/instructor-approvals.md#2026-11-24-deadline-extension-demonstration-video-due-nov-30-8-pm), "2026-11-24: Deadline extension; demonstration video due Nov 30, 8 PM");" |
| 5 | docs/demo-script.md:9 | "421 s" wording | unchanged, as ordered. |

Citation style: README:106, :438 and demo-script:61 cite instructor-approvals entries as a file link followed by the quoted heading text; the three new pointers follow that style and add the heading anchor.

## 2. Anchor and its check

- Heading: instructor-approvals.md:122 `## 2026-11-24: Deadline extension; demonstration video due Nov 30, 8 PM` (`grep -nx` of the exact heading text: 1 match, line 122; no other heading starts "## 2026-11-24: Deadline extension").
- GitHub slug rule (lowercase; drop characters other than letters, digits, spaces, hyphens and underscores; spaces to hyphens): remove `:`, `;`, `,`; result `2026-11-24-deadline-extension-demonstration-video-due-nov-30-8-pm` (single spaces only, so no double hyphens). The slug is unique in the file (no other heading gives it), so no `-1` suffix.
- Target exists: `ls -d docs/agent-runs/instructor-approvals.md` from the repository root succeeded; README uses `docs/agent-runs/...` (root-relative to README), demo-script uses `agent-runs/...` (relative to docs/). The fragment appears at README.md:400, :456 and docs/demo-script.md:284.
- Done by hand (slug computed by reading the heading); no rendered-page check (no network).

## 3. Checks

- Link check (grep `](target)` + `ls -d` of each target relative to the file's folder, inline loop, no script file):
  - README.md: 215 file links, 0 missing, 0 external, 109 with a fragment (107 before + the 2 new).
  - docs/demo-script.md: 57 file links, 0 missing, 0 external, 20 with a fragment (19 before + the 1 new).
  - No heading changed in either file, so earlier fragment checks still hold.
- Replay lines: `replayed in phase 17 (E12` now occurs 3 times (definition :11, :210, :212); the chain command has exactly one Replay line (:223); no step-level replay sub-bullets remain.
- Diff saved: /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/73f10a2e-9b01-4d44-898e-9399e20392db/scratchpad/phase17-reports/diff-demo-script-3.txt (495 lines; `git diff -- README.md docs/demo-script.md` against HEAD, so it includes the earlier uncommitted rewrite). Note: `git diff --stat` also lists docs/agent-runs/instructor-approvals.md (19 lines added, the human's edit); it is not in the saved diff and was not touched by me.
- Identifier scan of the `+` lines of diff-demo-script-3.txt (190 lines): 12-digit runs 0; 10-or-more-digit runs 0; `arn:` 1 and `s3://` 2 (pattern/placeholder text from the earlier rewrite, as in review-report-demo-2.md section 6); `amazonaws` 0; AKIA 0; IPv4 0; EMR cluster ID pattern 0; `@` 0; N/M ratios 0; GitHub user name 1 (README:438, pre-existing public D01 URL, not changed in this pass). The text added in this pass holds no identifier and no application name other than ScholarChain; the sample-application name could not be grepped by value (not in my inputs).

## 4. Unverified claims and notes

1. Time zone: the instructor-approvals entry (line 129) says "8:00 PM CST" and its "How it is applied" (line 138) says the README (D19) and demo-script say "due Nov 30, 8 PM CST"; the three places say "Nov 30, 8 PM" without "CST" (the heading also has no zone). Not changed (outside this work order); proposed later: add "CST" at README.md:400, :456 and docs/demo-script.md:284, or adjust line 138 (human-owned).
2. The entry is a class-wide extension of the project submission deadline to Nov 30; that the video may be published after the tag is the human's application of it (line 138). The README's "instructor-approved deadline" wording rests on that entry.
3. 421 s attribution still unconfirmed (left unchanged as ordered).
4. 222 s and the "E12 G7 replay (test-engineer)" row exist only in the scratchpad runbook-log until the human copies it.

## Replayed commands and outcomes

None (documentation agent runs no repository command in Phase 17; no sbt).

---

STATUS: COMPLETE
OWNER: documentation-engineer
WORK ORDER: Phase 17, session 3, post-release-candidate docs-only pass: repair of review-report-demo-2.md G-1, G-2, G-3 and the video-deadline pointers (README.md:400, :456; docs/demo-script.md:288 old)
FILES CREATED: none in the repository. Scratchpad: phase17-reports/repair-docs-demo-2.md (this report), phase17-reports/diff-demo-script-3.txt
FILES MODIFIED: /Users/brian/Developer/repos/scholarchain/docs/demo-script.md, /Users/brian/Developer/repos/scholarchain/README.md
COMMANDS RUN: `git -C ... diff -- README.md docs/demo-script.md >` the literal scratchpad path (exit 0); `git -C ... diff --stat`; grep, wc, ls -d (link check loop); one no-op probe that piped through `tr` (produced no output, changed nothing; `tr` is not on the list, noted as a deviation). No sed/awk/find/cat/cd/sbt/python, no network, no AWS.
TEST RESULTS: not applicable (documentation only)
VALIDATION RESULTS: link check 0 missing in both files; new anchor matches instructor-approvals.md:122 by the GitHub slug rule; one Replay line on the chain command; every "E12 G7 replay" citation says "in the main checkout"; identifier scan 0 values in added text.
ASSUMPTIONS: GitHub's heading slug rule as stated in section 2; the human's entry at line 122 stays as is.
RISKS: "CST" mismatch between the entry's line 138 and the three pointers (section 4, item 1); 421 s unconfirmed; scratchpad-only log rows until copied.
MANUAL ACTION REQUIRED: decide on "CST" (section 4, item 1); confirm 421 s; copy the runbook-log and test-report-demo-g7.md; identifier scan and commit.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer check of diff-demo-script-3.txt (orchestrator), then the human's scan and commit.

## INTERFACE HANDOFF

- Files changed: docs/demo-script.md (:11, :223, :273, :284), README.md (:400, :456). Markdown only.
- Public signatures: none. Anchors: none renamed; new outbound fragment `#2026-11-24-deadline-extension-demonstration-video-due-nov-30-8-pm` into docs/agent-runs/instructor-approvals.md (3 links). The demo-script "After recording" line moved from :288 to :284 (chain sub-bullets removed).
- Fill-in marker unchanged: `VIDEO LINK: added after the tag` (README.md:400, :456; docs/demo-script.md:284).
- Fixtures: none. Thread-safety: not applicable.
- Limits: nothing under docs/agent-runs/**, scripts/** or any code, test or configuration path changed.

## Follow-up: CST

Orchestrator follow-up in the same pass: add the time zone to match instructor-approvals.md:129 ("8:00 PM CST") and :138.

| File:line | Old | New |
|---|---|---|
| README.md:400 | "it is due Nov 30, 8 PM (instructor-approved deadline; ..." | "it is due Nov 30, 8 PM CST (instructor-approved deadline; ..." |
| README.md:456 (D19) | "it is due Nov 30, 8 PM, instructor-approved deadline, ..." | "it is due Nov 30, 8 PM CST, instructor-approved deadline, ..." |
| docs/demo-script.md:284 | "it is due Nov 30, 8 PM (the instructor's deadline extension: ..." | "it is due Nov 30, 8 PM CST (the instructor's deadline extension: ..." |

- Search for other "Nov 30, 8 PM" in both files: the only other occurrences (one on each of the three lines) are the quoted heading text "2026-11-24: Deadline extension; demonstration video due Nov 30, 8 PM", left unchanged on purpose because it must equal the entry's heading at line 122 (no zone there); the anchor fragment is unchanged (2 links in README.md, 1 in docs/demo-script.md).
- Section 4, item 1 above is resolved by this follow-up.
- Diff re-saved: /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/73f10a2e-9b01-4d44-898e-9399e20392db/scratchpad/phase17-reports/diff-demo-script-4.txt (495 lines, exit 0).
- Commands: `git -C ... diff -- README.md docs/demo-script.md >` the literal path; grep; wc. Nothing else changed.
