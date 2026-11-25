# code-reviewer: Phase 17 session 3, E11 repeat after repair round R3, final documentation check

(Saved verbatim by the orchestrator from code-reviewer's final message.)

VERDICT: APPROVE

No critical or major findings. I found two minor wording findings, both in README.md:9. Neither changes a value, a link or a gate result.

## Findings

| # | Severity | File:line | Failure input | Impact | Owner |
|---|---|---|---|---|---|
| 1 | minor | README.md:9 | The unchanged sentence "The human ran every Phase 17 replay (steps E3 to E6, E10 and E12) on Nov 23 and 24, 2026." now sits in the same paragraph as the new sentence "the human repeated E2 to E5 and E10 on it on Nov 25, 2026". The log rows "E2 (repeat, R3)" (recorded 2026-11-25T17:23:26Z) to "E10b (repeat, R3)" date the repeats to Nov 25. | A reader sees two dates in one paragraph that disagree. The "Nov 23 and 24" claim is now stale for the runs the replay lines cite. Suggested fix: "on Nov 23 and 24, 2026, and repeated E2 to E5 and E10 on Nov 25". | documentation-engineer |
| 2 | minor | README.md:9 (repeat sentence) | "...every E5 line exit 0 and E10 exit 0, in a new fresh clone (`/tmp/p17-clone-20261125T1725Z`) and a new replay clone (...)". Log rows :187 and :188 say E5.40 (the dry run) and E10 ran "from this working tree" / "(human, this working tree)" with `--clone` pointing at the clone. The first-run sentence just before it does make this distinction. | The text says E10 ran inside the clone, which is not quite right. The per-line evidence at README:324-325 ("from the main checkout's root") is correct, so nothing is misreplayed. | documentation-engineer |

Not findings (checked and acceptable):
- reports/final-report.md:355 still cites only the first-run rows for `release-manifest.sh`. It is true as history, and :615 adds the repeat.
- docs/demo-script.md:197 (the plan.sh E6 line) does not name `b4d8f12` on the line itself. Its intro (:9) says E6 lines cite the first runs.

## Check 1: changed values against the run log and the repository

I read every changed line in the diff (624 lines, 7 files) and compared each with its row.
- **RC SHA and time:** `4feef0f…ba4`, recorded 2026-11-25T17:23:26Z. Matches row :181.
- **Clone paths:** `/tmp/p17-clone-20261125T1725Z`, HEAD = RC (matches :182); replay clone `/tmp/p17-replay-20261125T1725Z` (matches :187); Hadoop output `/tmp/p17-hadoop-20261125T1725Z` (matches :185).
- **E3b:** 1205 s (0:20:05.0), 0 error lines; 151 suites, 2362 passed, 5 skipped; 35 suites, 418 of 418; marker 2026-11-25T17:46:00.163680Z. All match :183.
- **E4:** 9 of 9 gates, `module_tests=2362 contract_tests=418`, 8 release-manifest probes. Matches :184.
- **E5.01-E5.13:** 600/600, 124 of 124 ("114 before R3"), 147/147, 43/43; 321/2, 360/2, 137/1, 418 of 418, 17/17; 150 s, SLF4J notice, "R3 blocking chain". All match :185.
- **E5.14-E5.39:** 72/0, 369/0, 69/0, `RESULT: PASS (0 error(s))`, fmt listed nothing, "The configuration is valid.", exports in a subshell; derive scripts and clone status empty; "30 fixture results, all pass, 0 fail"; `RESULT: package.sh ok`, `packageCheck: ok`. All match :186.
- **E5.40-E5.47:** `RESULT: release-manifest.sh ok`, "HEAD is now at 4feef0f", "Switched to a new branch 'replay-phase-02'", `684c610…` with the Phase 01 subject, 64 checks passed; the optional `claude` session was not repeated. All match :187.
- **E10 repeat:** json 42,240 bytes, sha256 `1ec1f4d0…8428`; md 20,392 bytes, sha256 `300dca4a…5d6e`; build commit `24490a5…4e50`; commit `daca689a…a5e9`, parent 4feef0f. All match :188 and :190.
- **Pre-step:** 51 MATCH, 18 MATCH-RECORD, 0 DIFF. Matches :191 and manifest-checksums-2.txt.
- **First-run history values I re-read:** row :32 (1303 s, 2352/399, marker 2026-11-23T19:15:55.402093Z); row :34 (130 s, 18,792 bytes); rows :63-:64 (c6bbe14, `e0698986…`, `8a2d634d…`, 42,239 bytes); row :77 (E12r.1: clone at b4d8f12, printed 2.1.331). All are quoted correctly and labelled as first-run or superseded.
- **Lines that still cite first runs say so:** the optional `claude` session lines (README:67, :125, :129, :135, :136, :138, :147; demo-script:61, :70: "the first E5's session, on `b4d8f12`, not repeated after R3"); E6 (README:295, runbook:46, final-state:126, final-report:349, :750); E12 (README:112, :272, final-report:754); the tag checkout stays "deferred to E14" (README:103).
- **b4d8f12 is presented as superseded history everywhere:** "first release candidate", "superseded after R3", "stays committed".
- **Threat model and Terraform statements:** threat-model:7 "R3 did not change the claim verifier" and final-state:126 "R3 changed no file under infra/terraform/" are supported by the run log: the six R3 commits (row :179) touch modules/hadoop (C1 24490a5, 11 paths, row :166), tests (e31b3bc, :168), datasets (15f77c7), scripts (dc57e5d), docs (f28cc74) and records (4feef0f); none touches rag, prompts, services or infra. I could not run `git diff b4d8f12 4feef0f`; the documentation-engineer's empty-diff claim is checked against the records only.
- **Architecture link:** docs/architecture.md:260 points to the new md; its lines 139-147 are the "Models (config/course.json)" section.

## Check 2: nothing else changed

- The diff touches exactly the 7 files, and the changes match the stated intent.
- The R3 wording "fixed in code, tested locally, not validated live on AWS; the recorded experiment results were produced by the earlier code" is kept word for word at README:276, runbook:85 and demo-script:117; final-report:588 and :621 are unchanged.
- The video marks are still open: `PENDING` at README:400, README:456 and docs/demo-script.md:242. No `planned for replay` remains.
- The removed old-row details ("exports ok", provider 6.59.0, Docker lint warning, "replay clone HEAD 684c610… afterwards", "LocalPipelineRunnerSuite passed inside the R3 sbt check") are not in the repeat rows, so removing them from the repeat citations is correct.

## Check 3: manifest against its sources

**Compared with recomputed checksums** (manifest-checksums-2.txt, the orchestrator's `shasum -a 256` at 4feef0f and in the clone): every value in release-manifest.md, and the JSON fields read, entry by entry against the MATCH lines; all equal: 26 key artifacts; 10 prompts (md:128-137; json:893-943); 8 signed experiment manifests; dataset manifest `ae312233…`; generator `6f5a172c…`; 4 jars `c26feafe…`, `35bbbe38…`, `6d632872…`, `4754f85e…`; E3 marker copy `66e8042b…`. I did not recompute any hash myself (no Bash).

**Compared only with records** (committed files, read directly):
- 10 signed plan lines: each `APPROVED-PLAN-SHA256:` value is on the exact cited line: phase-13/human-gate.md:77; phase-14/approvals.md:9, :17, :29, :37, :45; phase-15/approvals.md:9, :65, :73, :76.
- 8 experiment-manifest approval lines: phase-15/approvals.md:15-57, all equal.
- Local-only archive: `EVIDENCE-ARCHIVE-SHA256` at phase-15/approvals.md:78.
- Seven release records: md:21-27 commits, plan sha256 values and approval lines consistent with the plan lines above. Two records read in full (`20261119T193246Z-…-d539.json`: commit, plan sha256, runtime true, tag, digest, revision 2; `20261121T052512Z-ebcbc03a90bd-01a4.json`: runtime false, tag/digest null, revision 4); both equal their manifest rows; JSON:26-77 (record 1) also matches.
- Candidate SHA: json:10, json:23 and md:7, md:10 equal the RC in row :181; `head_equals_release_candidate: true`.
- Build commit: `24490a5` is the R3 C1 commit (row :166); the source rule (json:15) limits it to build inputs, so it is consistent.
- Run folders: md:92-122 (1 Spark graph run, 10 attempts, 18 evaluation runs, 1 emulator run, 1 local-only) match the summary counts in :187 and :188; folders not listed one by one.
- E3 test counts: json:968-979 and md:153: tested SHA 4feef0f, marker 2026-11-25T17:46:00.163680Z, 2362/418, marker file name `p17-E3r-marker.txt`. All match :183.
- Rule line: md:15 matches decision 7 item 1.
- Identifier masking: no ARN, AWS host, `s3://`, key pattern, IPv4, twelve zeros, `/Users/` or `/tmp/` path in either file; the clone appears as the folder name only. Twelve-digit runs appear only as the expected all-digit release-record name: json:181, :182, :1126 and md:24, :190 (3 + 2, as rows :188 and :189 say).

## Check 4: stale leftovers

Searched README.md, docs/*.md and reports/ for b4d8f12, 2352, 399, "114 of 114"/"114/114", the 1850Z clone and replay paths, p17-E03, p17-E3-marker, c6bbe14, e0698986, 8a2d634d, "1303 s", "E11 pre-step", "the release candidate", "E5 part 4", "row E3"/"row E4".
- History, fine: every b4d8f12, c6bbe14, 2352, 1303 s and 1850Z hit is labelled first, superseded or "before R3" (README:9, :75, :112, :254, :272, :276, :295, :325, :451; runbook:14, :46, :85, :187; demo-script:9, :61, :70, :100, :117; final-state:111, :126, :133; threat-model:7; final-report:10, :349, :375-:380, :390, :678, :699, :750, :754).
- Other 399 hits: older phases' counts or line numbers (demo-script:7, :96; evaluation:773; tool-versions:35; data-lineage:90; hadoop-job-contracts:107, :173; final-report:370-:374).
- `p17-E3-marker.txt` at README:324-325 and runbook:186-187 is the command template, not a result.
- "E11 pre-step" at final-report:80 and :390 is history. "the release candidate" at architecture:314 and final-report:623-624 is generic.
- No stale current claim apart from finding 1.

## Check 5: identifiers and ratios in added lines

- The diff's `+` lines scanned for ARN, amazonaws, `s3://`, AKIA/ASIA, sk-ant, execute-api, IPv4, twelve-digit runs, `/Users/` and resource-name shapes: 0 matches. The sample-application name could not be tested (the work order does not give it); the agent's own scan reports 0.
- Every N/N ratio on a `+` line (43/43, 17/17, 600/600, 147/147, and those on the README:71-81 catalog rows) is older text kept on an edited line. The ratios this pass added are in words.

STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 17 session 3, E11 repeat after repair round R3 (instructor-approved): final documentation check of the 7 changed Markdown files, replay lines against runbook-log, and the two E10 repeat manifest files against their sources and manifest-checksums-2.txt
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools)
TEST RESULTS: not applicable (Markdown and manifest review only)
VALIDATION RESULTS: Changed values all match runbook-log rows :181-:191 and first-run rows :32-:34, :63-:65, :77. Manifest: 51 values compared with recomputed checksums (all MATCH); signed plan, manifest and archive lines and two release records compared with the committed records (all equal). Identifier scan of the manifest: only the expected release-record name (3 + 2). Identifier scan of added doc lines: 0. Video marks still PENDING; R3 wording kept.
ASSUMPTIONS: The scratchpad runbook-log.md is the log the human will copy. The jar and marker recomputation in manifest-checksums-2.txt is the orchestrator's observed output. "R3 did not touch rag, prompts, services or infra/terraform" is checked against the R3 commit rows, not a git diff.
RISKS: The two README:9 minors stay if not repaired (wording only). The link-check result (909/0) is the agent's; I checked only the new manifest links.
MANUAL ACTION REQUIRED: Optional: route the two minors to documentation-engineer. The human copies the updated runbook-log.md so the cited "(repeat, R3)" rows are committed.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: orchestrator identifier scan, then the human's commit; then E12.
