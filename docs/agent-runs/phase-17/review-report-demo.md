# review-report-demo: code-reviewer verdict on the docs/demo-script.md rewrite (Phase 17, session 3, post-release-candidate docs-only change)

(Saved by the orchestrator from code-reviewer's final message. The long "What I checked" record list and the per-item fact list are kept in short form; the verdict, the analysis tables, the findings and the report contract are verbatim.)

VERDICT: APPROVE

I found no critical or major problem. There are 7 minor findings and 1 nit. None of them blocks the human's identifier scan or the commit. F-1 is a one-line fix and is worth doing before the commit. F-6 sits outside this change's scope and needs its own work order before the tag.

## What I checked (short form)

The agent report, the diff (426 lines), the current docs/demo-script.md (278 lines), the human's plan (source material only; not quoted or copied), course-specification.md:2896-2917; against the records: the Phase 15 runbook log rows 48-131, Phase 15 human-gate.md 1-12 and decision 10 (:110), repair-D7a-infra-engineer.md 1-9, review-report-L-01-02.md 1-13, Phase 16 human-gate.md :40, :175, instructor-approvals.md:50-60, the Phase 17 runbook-log rows for E3, E4, E5 (first run and repeat), the optional `claude` session, E6, E12r.2, E12 gate timing, the R3 deadline and decisions rows, the scratchpad log through row 225, orchestrator-summary.md:106, -2.md:32, :103, -3.md:12, :41, :125, the README lines named, final-report.md:746, scripts/check.sh, guard-dangerous.sh:1025, guard-selftest.sh:86-105, both self-test TOTAL formats, the `tools:` line of all 18 role files, hadoop-engineer.md 1-12, dataset-manifest.yaml:358 and the sample files, UnsupportedClaimEndToEndSuite.scala:36-53, :103, :120, the p15-demo answers, results-summary.md :74, :149-153, :280, final-state.md sections 2, 5, 6, 9, the api.md headings, scripts/README.md:32, architecture.md:282.

## 1. The 22 items against the plan and the repository evidence

Every item names its clip, segment and shot, what is shown and what is said; narration labelled "planned"; line 5 says the recorded video wins. Clips 22-28 are "to record" everywhere (:23, :24, :30, :35-42, :51, each item body, :278); none is called "recorded". The facts of items 9-22 and 1-8 were each confirmed against the records listed above (e.g. item 9's 1:21:14 and J01 counts, item 10's 1,488 s / 1,416 s, item 14's second send with 2 verified claims, item 16's 1 conflicting / 2 partially_supported / 4 verified claims and clip 20's 7 of 7, item 17's clips 07 and 19, item 18's plan counts, item 22's 175 destroyed and leftover counts, items 1-8's role files, self-test totals 369 / 69, validator 72, clip 27's default record folder and E4 (repeat, R3) 9 of 9 with 2362 / 418, manifest :358 `id: dblp`).

The 13 known differences (rebuilt from the plan's notes and the report's table, since the work order's list was not among my inputs) are all applied: clip 01 second send only; item 4 JSON-fed probes; item 6 L-01 records; hadoop-engineer as the item 3 role file; item 1 public with no "Private" label; item 7 on clip 27; item 8 commands without license file or validator; item 5 self-test summary only; item 16 clip 04 part 2 plus clip 20; item 17 clip 28 without clone or `claude` session; item 18 clips 16, 17, 18 (not 14); item 22 parts 1 and 3; G3 uses clip 02.

Where the plan is followed only with a caveat (clip 02 not confirmed in the log; the clip 16 and 17 numbers; the clip 21 part names), the script says so (:9, :266). I found no place where the script follows the plan against the repository evidence.

## 2. The spec's 22 items (course-specification.md:2896-2917)

Numbering and order match one to one, all 22 present. Headings unchanged, anchors stable. Item 1 keeps the spec's word "Private"; the body and caption explain the repository is now public. Wording nits: F-7.

## 3. The agent's contradictions, item-17 analysis, "not covered" list and options

| # | Agent's claim | My check |
|---|---|---|
| 1 | Clip 21 part numbering differs | Accurate: Phase 15 log :122 (part 3 = `--list`), :125 and :129 (part 4 = the removal), :131 (part 5 = the leftover check) |
| 2 | 14 of 22 items, not 15 | Accurate for the plan's selection. Footage of item 7 does exist (clip 19b, the sbt gate passed; Phase 15 gate decision 10), but the plan does not use it |
| 3 | README :423, not :422 | Accurate (:422 is the "fixed in code, tested offline" bullet; :423 is L2) |
| 4 | The honest-results card states the absence rule too broadly | Accurate against README:423 |
| 5 | "E5 already saw it blocked" happened in the first E5 on b4d8f12 | Accurate (log :40; not repeated, :187) |
| 6 | Clip 27 does not depend on later commits | Accurate (the line checks out 4feef0f) |
| 7 | Tag deadline conflict | Not a real contradiction (F-4). The committed log rows :121 and :204 and orchestrator-summary-3.md:12, :41 and :125 record the human's later decision (Nov 29, 8 pm CST; course deadline Nov 30, 8 pm), which supersedes the older "Wednesday 21:00 CST" rows (log :89; orchestrator-summary.md:106; orchestrator-summary-2.md:32, :103). Those older rows are history and correctly stay unchanged. |

Item-17 analysis: accurate (clip 07 ran from a clean working tree, not a fresh clone, Phase 15 log :69; clip 19 predates Phase 16 and R3; the suggested local commands and their E5.13 (150 s) and E5.07 (43 of 43) repeat results match log :185; the warning not to caption Phase 15 counts for a run on the release candidate is sound).

"Not covered" list: accurate (items 1-8 and the agent-replay part of item 17). The flags on item 5 (self-test only) and item 16 (G7 shows a `conflicting` test while the suite's `unsupported` test is at :103) are correct; carried as risks.

Tag and video options: factually sound. Nuance: log :204 still plans "E11's fill-in for the three `PENDING` marks" once the link comes, so `PENDING E11` is not stale by process, only opaque to a reader of the tag. Moving a tag is correctly called a destructive change needing human escalation.

## 4. "Also runnable, not in the video"

Every such command is a README line with the same replay result (README :144, :260, :266, :276, :277, :278, :294, :295, :340, :377-382, :409, :412, :413); only ratio wording differs. Nothing in these blocks is claimed as shown in the video, except clip 27's replay line (F-1).

## 5. Links and scope

New file links and spot-checked anchors resolve (api.md 5.5, 5.6, 5.8, 9; final-state #2, #6, #9; README #architecture-summary, #agent-catalog, #deliverable-index, #reproducing-the-agentic-workflow, #bounded-reproduction-exercise-replay-phase-02; the p15-demo files, PROVENANCE.txt, the C plot SVG). No `demo-script.md#` links anywhere. File-level links at README :401, :454, :456 and final-report :746 still fit. The diff touches only docs/demo-script.md; the old clone line with the GitHub user name is removed.

## 6. Identifiers

10-or-more-digit runs 0; EMR cluster ID pattern 0; `amazonaws` 0; IPv4 0; `@` 0; user name 0; `/Users/` 0; `N/M` ratios 0; the tutorial's sample-application name 0. Only pattern text: `s3://` at :93 (`example-bucket` placeholder) and :153 (blur text), `arn:` at :252 (blur text). The account appears masked only. New ratios in words.

## 7. Stale README lines (report only; confirmed)

| Line | Text | Classification |
|---|---|---|
| README.md:106 | "private until the submission deadline and public after it" | Stale fact: public since 2026-11-24 (instructor-approvals.md:50-60; Phase 17 log row "Repository visibility (human)") |
| README.md:434 | "(for D01, the repository is private only until the submission deadline ...)" | Same, stale |
| README.md:438 | D01: "stays private until the submission deadline and is then made public" | Same, stale |
| README.md:149 | "the Phase 02 exercise below is recorded for the video" | Stale, and now contradicts docs/demo-script.md:214 ("There is no clone, branch or `claude` session in the video") |
| README.md:172 | step 6 replay: "recorded for the video, not required for the final gate" | Same as :149 |

All five minor; they need a documentation-engineer work order that names these lines, before the tag (F-6).

## Findings

| # | Severity | File:line | Failure input | Impact | Owner |
|---|---|---|---|---|---|
| F-1 | minor | docs/demo-script.md:122 | The clip 27 line (a local clone of the main checkout, default record folder, chained `git` checks) carries `Replay: replayed in phase 17 (E4, exit 0)`. E4 ran a different form: a GitHub clone from E3a and `--record-dir` pointing at the main checkout (log :184). Line 11 allows citing E4/E5 only for "the same command without the display pipe". | The replay line claims this exact line ran, though the same sentence says it is still to record. Suggested: `not replayed in phase 17 (demonstration-only line for clip 27, to record; the equivalent check.sh run passed at E4 (repeat, R3))`. | documentation-engineer |
| F-2 | minor | docs/demo-script.md:30, :56 | The overview rows say "21 (parts 1 and 3)" with no pointer to the numbering conflict (Phase 15 log part 3 = `cleanup.sh --list`, :122). | The caveat appears only at :266. A short "(plan's file names; see item 22)" would fix it. | documentation-engineer |
| F-3 | minor | docs/demo-script.md:9 | The summary of Phase 15 gate decision 10 leaves out "item 7 (clip 19: the sbt gate passed ...)" (human-gate.md:110). | Incomplete summary of a cited decision; item 7 (:120) mentions it as history. | documentation-engineer |
| F-4 | minor | agent-report-docs-demo.md §3a item 7 and §5 (report, not a repository file) | Calls the tag date a contradiction and cites only the older rows (log :89; orchestrator-summary.md:106). | The superseding committed records (log :121, :204; orchestrator-summary-3.md:12, :41, :125) settle it: Nov 29, 8 pm CST. | documentation-engineer |
| F-5 | minor (process) | agent-report-docs-demo.md, COMMANDS RUN | `cd` prefixes, `zsh` on its own scratchpad script, `cut`, `cat`, stray `sed -n` words, outside the command list. | Disclosed, read-only, no artifact effect; record as an unplanned deviation. | documentation-engineer (orchestrator records) |
| F-6 | minor (out of scope) | README.md:106, :434, :438, :149, :172 | See section 7. | The tagged README would state a wrong visibility fact and contradict demo-script.md:214. Needs its own work order before the tag. | documentation-engineer |
| F-7 | nit | docs/demo-script.md:50, :51, :201 | The overview rows for items 16 and 17 drop "behaviour" and "and" from the spec's wording; heading 16 spells "behaviour" where the spec has "behavior". | Cosmetic; changing headings would change anchors. | documentation-engineer |
| F-8 | minor | docs/demo-script.md:183 | "Only the second send is on the clip (plan; Phase 15 log, row 'D16 clip 01 second send')": that log row (:78) says clip 01 was sent twice, not which send is on the file. | The log is cited for more than it says; the plan is the real source. | documentation-engineer |

STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: ScholarChain Phase 17, session 3, post-release-candidate docs-only change: review documentation-engineer's rewrite of docs/demo-script.md against the human's final video edit plan (read-only; APPROVE or REQUEST_CHANGES)
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools: Read, Grep, Glob)
TEST RESULTS: not applicable (documentation only)
VALIDATION RESULTS: all 22 items checked against the plan and the records; the 13 known differences applied; the spec's 22-item list matches one to one; new links and spot-checked anchors resolve; no `demo-script.md#` links exist; identifier grep found no values (3 hits, pattern text); new ratios in words; the diff touches only docs/demo-script.md.
ASSUMPTIONS: the work order's 13-item list matches the differences rebuilt from the plan and the report table; the clip files are as the plan describes (nobody in this review saw the footage); scope judged from the diff, the report and the session-start git status.
RISKS: item 5 shows only a self-test summary; item 16's G7 shows a `conflicting` test (the suite's `unsupported` test is at UnsupportedClaimEndToEndSuite.scala:103); if the footage differs from the plan, the script is wrong in the same places; README:106, :434, :438, :149, :172 stale until a separate work order; the clip 24 probe loop has not been run yet (the plan's off-camera dry run is the check).
MANUAL ACTION REQUIRED: the human decides whether F-1 to F-3, F-7 and F-8 get a quick repair before the commit; orders a README pass for F-6 before the tag if wanted; runs the identifier scan and commits; copies this report into docs/agent-runs/phase-17/ to keep it.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: the human's identifier scan and commit of docs/demo-script.md; then the video link fill-in and E12 repeat (orchestrator-summary-4.md); then the human gate and the tag by Nov 29, 8 pm CST.
