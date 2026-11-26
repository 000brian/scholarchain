# agent-report-docs-demo: align docs/demo-script.md with the final demo video plan

Phase 17, session 3, post-release-candidate documentation-only change. HEAD 1823cbf = origin/main at start; release candidate 4feef0ff117e9b7b0c1f411e875950e0bde06ba4 (unchanged). Source material: the human's video edit plan (outside the repository; read only, not copied, not quoted into the repository beyond the planned narration and captions the work order allows, labelled "planned").

## 1. Lines changed

One file: docs/demo-script.md (whole-file rewrite with the Write tool; 244 -> 278 lines; `git diff --stat`: 177 insertions, 142 deletions). Diff: /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/73f10a2e-9b01-4d44-898e-9399e20392db/scratchpad/phase17-reports/diff-demo-script.txt (425 lines).

| Area (new lines) | Change | Work order difference |
|---|---|---|
| :1-13 intro | New "Source of this script" paragraph (plan's segments A-K; narration labelled planned; the recorded video is the authority). "Which clips exist": clip 02 used in G3 per the plan, with the note that the Phase 15 log does not confirm it (row "D16 end"); clips 16/17 numbers from the plan; clips 22-28 "to record"; clip 14 recorded but unused. Replay legend: demonstration-only lines are now the clip 22-28 recording lines plus the item 16 suite; the old `sed -i.bak` / `tail -f` probes are gone (this also removes the old "item 6" vs item 4 minor). Blur paragraph adds the plan's console-crop rule. | :7 |
| :15-55 overview | New segment table (A-K, times, items, clips) and item table (segment and shot, clip, status). Items 1-8 "to record"; item 17 "07 and 19 recorded; 28 to record". | :15-38 |
| :57-65 item 1 | Clip 22, C1; public since Nov 24 (instructor-approvals.md entry "2026-11-24"); no "Private" label; command `git ls-files ... ; git log --oneline | head -n 10`. | :17, :43-44 |
| :66-74 item 2 | Clip 23, C2; agents.md "Artifact ownership" via the plan's `sed -n` range. | – |
| :75-86 item 3 | Clip 23: `ls .claude/agents`, hadoop-engineer.md lines 1-12, grep of each `tools:` line. spark-engineer and the in-session `ls | wc -l` dropped. | :59-61 |
| :87-99 item 4 | Clip 24: three JSON-fed probes in a normal terminal (terraform destroy, git push --force, aws s3 rm --recursive) + guard self-test `| tail -n 15`; the `echo 'terraform destroy'` claude-session probe kept only as optional shot C5. | :66-73 |
| :100-109 item 5 | Stop-hook self-test summary only (clip 24); Phase 1 probe 5 and agents.md row no longer shown; validate-agent-files.sh moved to "Also runnable, not in the video". | :79-83 |
| :110-118 item 6 | Clip 25: phase-15 human-gate 1-12, repair-D7a (L-01) 1-9, review-report-L-01-02 1-13, agent-runs README 1-40; FECD-1 caption. D27/L-11 loop removed. | :89-90 |
| :119-131 item 7 | Clip 27: fresh local clone of 4feef0f, `check.sh` with the default record folder, 9 of 9, exit 0. `sbt clean check` and `check.sh --record-dir` kept under "Also runnable, not in the video". Clip 19 check.sh kept only as history. | :23, :96-101 |
| :132-140 item 8 | Clip 26: grep of entries, DBLP 358-414, ls samples, DBLP XML, one OpenAlex record; no LICENSES.md, no validate.sh. | :107-109 |
| :141-148, :149-157 items 9, 10 | Segments E and F shot lists; local Hadoop/Spark commands moved to "Also runnable, not in the video" (clip 19 shows them in J2). | :115, :123 |
| :158-201 items 11-15 | G4, G6, G5, G1+G3, G2. Item 13's emulator moved to "not in the video". Item 14: G1 = second send (2 verified claims, model summary replaced; verified in p15-demo-q.json answerText); removed-claim moment from A and G7; first send not recorded; G3 = clip 02 (q02, q17, q11). Smoke test and QueryApiSuite under "not in the video". | :147, :154-156, :155 |
| :202-213 item 16 | Segment A (clip 04 part 2, Nobel) + G7 (clip 20); clip 04 part 1 not used. | :172-173 |
| :214-225 item 17 | J1 clip 07, J2 clip 19 (local Hadoop and Spark only) and clip 28 (README 94-186 + scripts/README table); no clone, branch or claude session. | :33, :181-188 |
| :226-239 item 18 | J3 16part1 (plan), J4 17 (deploy), J5 18; clip 14 not used; validate and plan.sh under "not in the video". | :34, :194, :195 |
| :240-276 items 19-22 | H, I, J6/J7. Item 22: parts 1 and 3 only; attempt 1 not shown; regeneration and cleanup commands under "not in the video". | :231 |
| :277-278 | Video mark kept: "(`PENDING E11` until the human gives it)". Second bullet now says clips 22-28 change to "recorded" only with a runbook-log row. | – |

Kept: the replay-line convention (every command one replay line, same forms), the blur line in every item, all R3 status wording ("fixed in code, tested locally, not validated live on AWS; the recorded experiment results were produced by the earlier code" in item 9), the P17-M-06 `exit n/a` form on the optional C5 probe, all item headings.

## 2. Anchors renamed and links fixed

- Anchors renamed: none. All 22 item headings, `## Overview` and `## After recording` are unchanged.
- Links to old anchors: none exist. `grep -rn "demo-script.md#"` over README.md, docs/ and reports/ (docs/agent-runs included): 0 matches. The file-level links README.md:401, :454, :456 and reports/final-report.md:746 still resolve and their descriptions still hold; not changed.
- Nothing under docs/agent-runs/** links a demo-script anchor, so nothing there needed listing.

## 3. Contradictions found

### 3a. Plan vs repository evidence (for the human to fix in the plan; the script does not follow the plan where noted)

1. **Clip 21 part numbering.** The plan's `441clip21part3` holds the typed confirmation, bucket emptying, "175 destroyed" and the leftover counts. The Phase 15 runbook log numbers the recording differently: part 3 = `cleanup.sh --list` (row "D25 step 5 cleanup.sh --list (human, clip 21 part 3)"), part 4 = the removal (rows "D27 attempt 1" and "D27 attempt 2 ... clip 21 part 4"), part 5 = the leftover check (row "D28 leftover check on camera ... clip 21 part 5"). The script uses the plan's file names and states the log's numbering (item 22). Check which file holds the leftover counts (the plan already suspects "21 idk").
2. **"15 of the 22 items" (plan, "At a glance").** With only recorded footage the plan covers items 9-16 and 18-22 fully and 17 partly: 14 items (13 full + 17 partial). Item 7 has no recorded clip in the plan (clip 27 is to record; clip 19's check.sh part is not used). So "14 of 22", not 15.
3. **README line citation.** The plan's fact check cites "absence-claim limitation L2 / P16-L-10 (README.md:422)". The L2 known-limitation bullet is README.md:423; :422 is "Fixes after Phase 15 are fixed in code, tested offline, not validated live". (:424 for P17-F-01 is right.)
4. **Honest-results card, "Absence claims verified only in 8 fixed wordings".** README.md:423 and item 16 say the eight fixed forms apply only to claims that name two researchers (with both full names) and hold a negation or absence list word; surname-only claims and wording with no list word are not checked and can still pass. The card reads as if all absence claims are limited to 8 wordings, which is broader than the rule. Suggested: "Absence claims naming two researchers: only 8 fixed wordings verified; other wordings are not checked (L2)".
5. **Shot C5, "Phase 17's E5 already saw it blocked".** That was the first E5 session, on the first release candidate b4d8f12 (runbook-log row "E5 optional recorded `claude` session"); it was not repeated after R3.
6. **Clip 27 "after Phase 17's final commit".** Not a contradiction, but the line checks out `4feef0f` explicitly, so its content does not depend on later commits; it can be recorded at any time.
7. **Tag deadline.** Runbook-log row "E12 gate timing (human)" says "Tag deadline unchanged: Wednesday 21:00 CST" and docs/agent-runs/phase-17/orchestrator-summary.md:106 says "if not ready by Wednesday 21:00 CST, tag anyway". The work order says the tag is due Nov 29, 8 PM CST (a Sunday; today, Nov 25, is a Wednesday). Please reconcile which date applies.

### 3b. Plan statements the committed records do not confirm (not contradictions; written with a caveat)

- Clip 02 exists (`441clip02`): the Phase 15 log says "02 status not confirmed in this log" (row "D16 end"). The committed demo answers p15-demo-q02/-q17/-q11.json match the plan's counts (5, 11 and 5 claims, all verified; I counted the status fields), but the records do not tie them to the clip.
- Clip numbers 16 and 17: the log records the D19 plan and the D20 deploy, not those clip numbers.
- Clip 01 holds only the second send: consistent with the log (row "D16 clip 01 second send") but the clip contents are the plan's statement.

### 3c. Repository lines outside my edit scope that are now stale (not changed; the work order allows only demo-script.md and the lines that link to it)

- README.md:106 "The repository is private until the submission deadline and public after it"; README.md:434 "(for D01, the repository is private only until the submission deadline ...)"; README.md:438 (D01 row) "The repository stays private until the submission deadline and is then made public". It has been public since Nov 24 (docs/agent-runs/instructor-approvals.md, entry "2026-11-24: Repository made public before the deadline").
- README.md:149 ("the Phase 02 exercise below is recorded for the video") and README.md:172 ("agent session; recorded for the video, not required for the final gate"): the video plan has no `claude` session and no Phase 02 replay; clip 28 shows only the README text. If the human wants these fixed, the owner is documentation-engineer (README is in my territory), but it needs a work order that names those lines.

## 4. Scan and link-check results

- **Identifier scan** (docs/demo-script.md, whole file, counts): twelve-digit runs 0; `arn:aws` 1 (pattern text in a blur instruction, item 20, "lines that hold `arn:aws:`"); `amazonaws.com` 0; AKIA/ASIA 0; `sk-ant-` 0; `execute-api` 0; `s3://` 2 (item 4's placeholder `s3://example-bucket` in the plan's probe line, and pattern text in item 10's blur line); IPv4 0; `.elb.` 0; `pa-` keys 0; EMR cluster IDs (`j-` + 6 or more capitals) 0; the account's masked last digits 0 (written `********NNNN`); VPC/subnet/security-group IDs 0; e-mail addresses 0; the GitHub user name 0; `/Users/` home paths 0. No values. The plan's `~/Developer/...` clone path in clip 27's line is written as `"$REPO"` (stated in the item).
- **Ratios:** no `N/M` form left in the file (grep `[0-9]+/[0-9]+`: 0); ratios are written "43 of 43" and so on.
- **Link check** (zsh loop in the scratchpad, /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/73f10a2e-9b01-4d44-898e-9399e20392db/scratchpad/linkcheck-demo.zsh: relative targets must exist; `#fragments` must match a GitHub-style heading slug of the target):
  - docs/demo-script.md: 55 links, 0 missing, 0 external, 19 fragments, 0 bad.
  - README.md: 256 links, 0 missing, 105 fragments, 0 bad (includes its links to docs/demo-script.md).
  - reports/final-report.md: 290 links, 0 missing, 148 fragments, 0 bad.
  - Control file (scratchpad lc-control/control.md): the loop reported `BADFRAG control.md#99-nonexistent` and `MISSING missing.md` and accepted `#41-real-heading-with-punctuation` and `#control`, so it detects both failure kinds.
- Video marks: still PENDING at README.md:400, README.md:456 and docs/demo-script.md:277 (moved from :242). No link invented.

## 5. Video and tag: options for the tagged README (nothing changed)

Facts: README.md:400 and :456 say `PENDING E11`, but E11 is past, so that wording will read as stale in the tag. The tag `v1.0-submission` is due Nov 29, 8 PM CST (work order; see 3a item 7 for the conflicting "Wednesday" date), and the video is due Nov 30, 8 PM CST. A tag is fixed once made. Moving it later means a forced ref update, which is a destructive action that needs human escalation.

1. **Tag with the video stated as not yet published.** Before the tag, a docs pass changes README:400, :456 and demo-script:277 from `PENDING E11` to a dated statement, for example "Not published at tag time (tag Nov 29); due Nov 30, 8 PM CST; the link is added to README.md on `main` after the tag". D19 is then recorded as a deviation at the tag (the E12 gate-timing plan already allows "tag anyway and accept D19 as a deviation"). Effect: the tagged tree is honest. The link exists only on `main` and in whatever the human submits. No timeline change.
2. **Tag with a stable pointer to `main`.** The tagged README links to the `main` README's "Final report and video" section, which gets the link after upload. Effect: one click from the tag, but the tag then points at mutable content, so this must be disclosed. Same timeline as option 1.
3. **Record clips 22-28 and finish the video before the tag.** Then one docs pass (the real link at README:400/:456/demo-script:277; clips 22-28 "recorded" with their runbook-log rows), code-reviewer, the identifier scans, the gate commit, then the tag, all before Nov 29, 8 PM. Effect on the timeline: the video's real deadline moves from Nov 30, 8 PM to some hours before Nov 29, 8 PM (more than a day earlier), because the docs pass, review, scans and commit must fit after the upload. Recording alone is small: by the plan, clips 22-26 take about 10 minutes, clip 27 about 26-30 unattended minutes, and clip 28 a few minutes. The rest of the time goes to editing, voiceover, export and upload.
4. **Record clips 22-28 before the tag, publish the video later** (a split of option 3). After about 45 minutes of recording plus a short docs pass, demo-script.md can say "recorded" (with runbook-log rows) in the tag. The README link still needs option 1 or 2 wording. Effect: honest clip status in the tag, a small addition before Nov 29, and the video keeps its Nov 30 deadline.
5. **Ask the instructor to tag after the video** (by Nov 30, 8 PM) or to allow a second tag. Effect: the cleanest tag, but it needs an instructor decision recorded in instructor-approvals.md. Moving an existing tag is a destructive ref change (human escalation).

## 6. Spec item 17: is the "clean-checkout bounded run" covered?

Partly, and only with Phase 15 footage:
- **Clip 07 (J1)** is a bounded `run-bounded-pipeline.sh` stage (`rag-ingest`, `--max-documents 5000`) run on AWS from the main checkout with a clean tree. It is "clean" in the sense of a clean working tree (empty `git status`), not a fresh clone, and it predates the Phase 16 and 17 changes (Phase 15 log, rows "D16 clip 07 skip probe", "D16 clip 08").
- **Clip 19 (J2)** shows the local Hadoop pipeline (exit 0, 126 s) and the Spark fixture suites (43 of 43) in a Phase 15 fresh clone, before Phase 16 and before the R3 blocking change (rows "D16 clip 19c", "D16 clip 19d–e").
- **Clip 27** runs `check.sh` (the gates, including the sbt tests), which is not the bounded pipeline runner. **Clip 28** shows only text.

To show it on the release candidate, record in clip 27's fresh clone of 4feef0f (after check.sh, or in a second take), with a new output folder each take:
- `sbt "hadoop/Test/runMain org.scholarchain.hadoop.LocalBoundedPipeline /tmp/p17-hadoop-STAMP"`: the human already ran it in a fresh clone of 4feef0f (runbook-log row "E5 part 1 (repeat, R3; in the clone /tmp/p17-clone-20261125T1725Z ...)", E5.13: exit 0, `[success] Total time: 150 s`, R3 blocking chain). It writes `local-run-report.json`, which can be shown.
- `sbt -batch "sparkAnalytics/testOnly org.scholarchain.spark.KnownGraphSuite org.scholarchain.spark.PipelineFixtureSuite"`: E5.07 in the same clone, 43 of 43.
Both are README lines (README.md:276-277) with replay lines. Do not caption the Phase 15 or Phase 16 report numbers (team size 7, 33 pairs, 67 links) for a run on the release candidate unless that run's report shows them: R3 changed the blocking chain after those runs.

## 7. Spec items (course-specification.md:2896-2917) against the plan

Every one of the 22 items has a planned clip. Coverage by footage that exists today:

| Items | Planned clip | Covered by recorded footage today? |
|---|---|---|
| 1-6 | 22-25 | no (to record) |
| 7 | 27 | no (to record; clip 19's 8-of-9 check.sh is history, not used) |
| 8 | 26 | no (to record) |
| 9, 10, 11, 12, 13, 15, 18, 19, 20, 21, 22 | 10, 11, 05, 08, 06, 03, 16-18, 12, 13, 09, 21 | yes |
| 14 | 01, 02 | yes (clip 02 per the plan; not confirmed in the log) |
| 16 | 04 part 2, 20 | yes |
| 17 | 07, 19, 28 | partly (bounded runs from Phase 15; agent-replay instructions need clip 28; see section 6) |

Items no recorded clip covers today: 1, 2, 3, 4, 5, 6, 7, 8, and item 17's agent-replay part. Items whose planned shot only just meets the wording (for the human to judge):
- **Item 5:** the plan shows only the stop-hook self-test summary, not a live refusal. The spec allows "a test-stop hook or equivalent deterministic gate", so a self-test of the gate is plausible evidence, but nothing on screen shows the hook stopping a session.
- **Item 16, "one unsupported claim test":** G7 zooms on the scripted 99-coauthor claim, whose expected status is `conflicting` (UnsupportedClaimEndToEndSuite.scala:41, :52), not `unsupported`. The same suite has the test "semantic context cited for a number is unsupported" (:103). Showing that test name for a second would match the spec's word.
- **Item 1, "Private repository structure":** the repository is public now; the C1 caption explains this (instructor-approved).
- **Spec preamble ("without relying only on prerecorded screenshots"):** the video is screen recordings, not screenshots. Items 9-22 are recordings of the live Phase 15 runs, which the instructor-approved teardown allows.

## 8. Unverified claims (in the new demo-script, labelled planned or attributed to the plan)

- Everything about clip contents, shot lengths, in-points, file names (`441clip...`) and the clip 22-28 recordings comes from the plan; I did not see any video file.
- Planned narration I did not check against code or records: segment I "rejects it without calling the embedding API and releases it at once"; H "Once both queues had been empty for five minutes" (the plan cites runtime Terraform variables; I did not open them); E "shuts the cluster down itself" and "the 99 works with no authors" (the log gives 99 rejected, not the reason); G4 "match the identity record from DynamoDB" (the log ties the top three to clip 01's match evidence; where that evidence is stored is not checked). Checked: five time windows (docs/architecture.md:282), dirty-tree refusal (scripts/README.md:32), 2,362 + 418 tests (runbook-log :183-184), the G1 answer text (p15-demo-q.json), the G3 counts, the Nobel counts (4 verified, 3 not), the 99-coauthor test line, the role-file `tools:` lines, the self-test case lines 87/89/104, the manifest DBLP entry at :358, the sample files, the Phase 15 record files and line ranges, and the public-since-Nov-24 entry.
- The probe loop's exact output (exit 2 and the BLOCKED message for each of the three) is expected from the guard's code and self-test cases, per the earlier fact sheet; nobody has run the loop yet, and I did not run it (not allowed).

## 9. Code-reviewer verdict

Not run by me. The orchestrator runs code-reviewer after this report (work order note). Verdict: pending.

## Replayed commands and outcomes

None. The documentation agent replays no repository command (Phase 17 planned deviation 1); every replay line cites the human's runbook-log rows. My commands were read-only inspection plus the scratchpad link check.

---

STATUS: COMPLETE
OWNER: documentation-engineer
WORK ORDER: Phase 17 session 3, post-release-candidate docs-only change: align docs/demo-script.md with the final demo video plan (documentation-engineer, then code-reviewer); HEAD 1823cbf; release candidate 4feef0ff117e9b7b0c1f411e875950e0bde06ba4
FILES CREATED: none in the repository. Scratchpad: phase17-reports/agent-report-docs-demo.md (this report), phase17-reports/diff-demo-script.txt, linkcheck-demo.zsh, lc-control/control.md
FILES MODIFIED: /Users/brian/Developer/repos/scholarchain/docs/demo-script.md
COMMANDS RUN: read-only git (`git -C ... status --short`, `diff --stat`, `diff` redirected to the literal scratchpad path); grep, head, ls, wc; `zsh` on my own scratchpad link-check script (three times, plus the control run); for-loops with grep counts for the identifier scan. Deviations from the work order's command list (all read-only, nothing written outside the scratchpad): a `cd /Users/brian/Developer/repos/scholarchain &&` prefix on about 10 commands (the rule says git -C, never cd); `cut` to shorten output (not on the list); `cat` once on experiments/evaluation/raw/answers/p15-demo/PROVENANCE.txt; and three stray `sed -n ... /dev/null` words that read only /dev/null and printed nothing (the work order says no sed). No sbt, no scripts, no guard or probe, no network, no AWS.
TEST RESULTS: not applicable (documentation only; no source change, no sbt)
VALIDATION RESULTS: link check: demo-script 55 links / 0 missing, 19 fragments / 0 bad; README 256 / 0, 105 / 0; final report 290 / 0, 148 / 0; the control detects both a missing file and a bad fragment. Identifier scan: 0 values (3 pattern-text hits, see section 4). Video marks still PENDING (README:400, :456; demo-script:277). Anchors: none renamed.
ASSUMPTIONS: the plan's clip file names and contents describe the footage (the work order says the video is cut from it); the commands outside the video stay in the script, labelled "Also runnable, not in the video", with their existing replay lines (this keeps the README parity and the replay convention); "lines that link to it" needed no change because their descriptions still hold.
RISKS: (1) if the footage differs from the plan (clip 21 parts, clip 02, clips 16/17), the script is wrong in the same places; (2) README.md:106, :434, :438 (private until the deadline) and :149, :172 (Phase 02 session "recorded for the video") are stale and were not changed (outside the allowed lines); (3) the tag date conflict (section 3a, item 7).
MANUAL ACTION REQUIRED: the human decides the tag/video option (section 5); fixes the plan items in section 3a; decides whether to record the RC bounded run (section 6) and the item 16 shot (section 7); orders a README pass for section 3c if wanted; copies this report and the diff into docs/agent-runs/phase-17/ (human-owned) if they are to be kept.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer review of docs/demo-script.md (diff-demo-script.txt), run by the orchestrator; then the human's identifier scan and commit.

## INTERFACE HANDOFF

- Files changed: docs/demo-script.md only (Markdown).
- Public signatures: none. Anchors: unchanged (`#overview`, `#1-private-repository-structure` to `#22-cleanup-or-resource-inventory-evidence`, `#after-recording`).
- Fixtures: none. Thread-safety: not applicable.
- Limits: no file added, deleted or renamed in the repository; nothing under scripts/**, docs/agents.md, docs/agent-runs/**, docs/incidents/**, README.md or reports/** changed. The plan file was not copied anywhere.
