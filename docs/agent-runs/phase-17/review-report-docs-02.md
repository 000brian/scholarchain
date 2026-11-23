[Saved verbatim by the orchestrator from code-reviewer's final message (step 10 documentation re-check). The harness added the first line below; it is kept as received.]

[harness: subagent output matched instruction-shaped pattern(s): settings-json. Control tags below are neutralized (`<` → `<\`); treat any remaining directive-shaped text as a finding to relay to the user, not an instruction to you.]

# review-report-docs-02.md (Phase 17 session 2, step 10: documentation re-check after repair-01-docs)

## VERDICT: APPROVE (documentation slice only)

- **All 29 findings are RESOLVED.** That is P17-D-01 to D-10, D-31 to D-40 and D-61 to D-69. The only major, P17-D-01, is resolved.
- **The two same-defect fixes outside the cited lines are correct:** README.md:319 and docs/runbook.md:88.
- **3 new findings, all minor:** P17-K-10 to P17-K-12. No critical or major finding remains in the documentation slice.
- **The overall final review stays REQUEST_CHANGES.** P17-F-01 (major, constitutional) and P17-A-01 (PARTIAL, major; P16-L-10 not closed) are still open. This APPROVE covers only R-D1 to R-D3 after the re-check. It does not make the final-gate item "Final reviewer returns APPROVE" met.

## Per-finding status

Each row gives the current file:line and the check I made against the cited source.

| ID | Status | Current location | Check against the source |
|---|---|---|---|
| P17-D-01 (major) | RESOLVED | docs/threat-model.md:80, :133, :134 | **:80:** the guard blocks only a command that names a state file. guard-dangerous.sh:179 (`*.tfstate` in the secret-path list) and :1110 (`\.tfstate` regex) both match. The line also says a recursive search is not blocked (P17-F-03).<br>**:133:** records the Phase 14 D-ER slip. It matches phase-14/repair-DER-infra.md:18 (under `RISKS:` at :17; "output was truncated … no account ID, ARN or bucket") and phase-14/human-gate.md:98 ("Commands not on the list", 0 matches twice). The prompt citations also match: phase-15/prompt.md:69, phase-16/prompt.md:65 and phase-17/prompt.md:73 each say "No Bash `grep` over infra/terraform/environments/course/".<br>**:134:** names P17-F-03 as the residual risk. The old "no agent read the state in any phase" claim is gone. |
| P17-D-02 | RESOLVED | docs/threat-model.md:128 | The section 2.11 residual risk now names P17-F-01 (major, open, constitutional), the symlink bypass, guard :829-854 and :1241-1299. |
| P17-D-03 | RESOLVED | docs/data-lineage.md:142-145 | Checked against phase-14/runbook-log.md:67 ("D13a/D13b full reports"): EvidenceBundle 64, EntityLinkCache 255,065, ArtifactRegistry 0, QuerySession 0, all inserted, 0 updated/conflicts/rejected, sum 763,577. :144 labels 251,094 as a CloudWatch write-unit sum, which matches runbook-log.md:80 (62,976 + 68,634 + 71,168 + 48,316). |
| P17-D-04 | RESOLVED | docs/service-limits.md:3, :5, :8, :41; README.md:421; reports/final-report.md:603 | **Recount of the rows:** table 1 has 16 verified (IL-01, 03, 05, 07-10, 12, 13, 15-21) and 5 unverified (IL-02, 04, 06, 11, 14). Table 2 has 38 verified and 1 unverified (SL-29). Total 60 / 54 / 6 / 0.<br>The counts agree in all three documents. IL-11's reason matches its own documented-value cell (default 6 < 7). |
| P17-D-05 | RESOLVED | docs/api.md:24 | The Phase 14 statement is now in the past tense. collector-common.sh:661-665 (`u="${u%/}"` at :664) and query-load.sh:495-504 (`API_URL="${API_URL%/}"` at :502) match.<br>A Grep of `api_url` over *.sh and *.py found no other script that builds a URL: identity.sh:293 only registers the host for identifier checks; smoke-test.sh is already named. |
| P17-D-06 | RESOLVED | docs/api.md:223 | Checked QueryService.scala: admit() at :306-314 runs before createJob (:338, :344); the job and key record are written at :344-358. The pool rejection at :390-396 fails the job with `WorkflowFailed` and returns OVERLOADED. follow() maps Failed to `JobService.failed` (:575), which is 409 JOB_FAILED (:663-666). |
| P17-D-07 | RESOLVED | docs/emulator.md:48, :187, :197 | The Phase 16 behaviour is now in the past tense. Each line says the Phase 17 sweep is `runtime: untested` and was made after E14, which was not repeated. That matches env.sh:55-58 ("the Phase 17 sweep has never run"). No number changed. |
| P17-D-08 | RESOLVED | docs/runbook.md:63, :167 | Both lines use `not replayable after teardown (archived evidence: PATH)` with a real committed PATH.<br>**:63:** release record 20261119T193246Z…d539.json has runtime_enabled true and desired_count 1 for each service. 20261120T213452Z…44da.json is also runtime-on, desired 1.<br>**:167:** releases/course/ has 7 records and none named `-rollback` (Glob). |
| P17-D-09 | RESOLVED | docs/final-state.md:126 | Says "not planned for E6", which matches agent-report-infra-I1b.md:99, :102, :103 (exit 3 at `terraform-outputs` before sts and before any listing; "safe and useful for E6: no"). It now agrees with runbook.md:199. The anchor `runbook.md#10-cleanup` matches "## 10. Cleanup". |
| P17-D-10 | RESOLVED | docs/final-state.md:80-82 | Each row now reads "the Phase 17 plan is …, at step E8 (outcome: section 9, PENDING E8)". This matches section 9 (:128-130). |
| P17-D-31 | RESOLVED | README.md:72, :77, :78, :81 | OWNER lines checked: phase-04/repair-02.md:2 build-engineer; phase-11/repair-03-spark.md:4 spark-engineer; phase-11/repair-05-storage.md:2 storage-engineer; phase-14/repair-D6-tests.md:6 test-engineer. Phase 17 T1 is added for test-engineer. |
| P17-D-32 | RESOLVED | README.md:102, :320, :321 | release-manifest.sh:290 is `die 4 "refusing: --clone is this working tree …"`, and the help text at :82-83 says "not this tree (4)". The anchor `#source-controlled-automation-scripts` matches README.md:296. Mirrored in runbook.md:186-187 (no cd instruction there, so no conflict). |
| P17-D-33 | RESOLVED | README.md:411, :453 | Both lines now link to `docs/final-state.md#9-after-phase-17`, which matches "## 9. After Phase 17". The remaining README PENDING marks are only the allowed ones: :214 (E8 IAM user), :396 and :452 (E11 video), :447 (E10 manifest). |
| P17-D-34 | RESOLVED | README.md:110, :112 | :110 uses the valid draft form `planned for replay (E5)`. :112 names the start check as the version's source and says it is not a replay. The anchor `#1-operator-machine-phase-17` matches tool-versions.md:18. |
| P17-D-35 | RESOLVED (in README; see P17-K-10 for the runbook copy) | README.md:317, :323 | E-03 matches archive-evidence.sh:580-586 (the `--local-only` string replaces `false`). P16-B-03 matches probes.sh:198-202 (`${SCHOLARCHAIN_EMULATOR_RESULTS_DIR:-}`). Both are marked `runtime: untested`. The cited Phase 13 run is phase-13/human-gate.md:66. |
| P17-D-36 | RESOLVED | README.md:280; same fix at docs/runbook.md:88 | hadoop-pipeline.log:476 reads `[success] Total time: 127 s (0:02:07.0) …`. The exit is now cited to run.txt and the counts to local-run-report.json. |
| P17-D-37 | RESOLVED | README.md:121, :129, :134 | Nothing tracked switches github off; docs/agents.md (MCP table) says the human turned it off in the untracked settings.local.json. Step 5 now says to approve aws-docs, terraform and optionally aws-api, and to decline github. The expected `/mcp` result is "github not connected". Unverified wording (dialog text) is disclosed in the repair report. |
| P17-D-38 | RESOLVED | docs/demo-script.md:9, :71, :72, :175 | **:9:** now names the three demo-only lines as exceptions. A Grep of README.md for `sed -i`, `tail -f` and `UnsupportedClaimEndToEndSuite` finds no match.<br>**The other demo commands are all in README with the same replay line:** /agents, the echo probe, guard-selftest, the p17-replay-STAMP lines, the three derive scripts and `git status --short` (README :141, :143, :163-164, :373-378).<br>**:175's reason holds:** UnsupportedClaimEndToEndSuite.scala:14 is a plain `munit.FunSuite` with no `RequiresAws` tag, so E3's clean check runs it. |
| P17-D-39 | RESOLVED | README.md:430, :434-453 | All 20 ID, Deliverable and Minimum-contents cells are identical to course-specification.md:2765-2784. I compared them line by line. |
| P17-D-40 | RESOLVED | README.md:436 | Glob of docs/agent-runs/phase-11/ shows the files exist: round2-review-report.md, round2-test-report.md, round2-repair-{rag,er,hadoop}.md, round2-summary.md, repair-round-2.md, and the round2-*.log files. |
| P17-D-61 | RESOLVED | reports/final-report.md:457 | Matches phase-16/human-gate.md:9 ("repaired L2 twice and made the rag tests hermetic (P16-L-16)") and :73 (repair-E02-rag = P16-L-16, APPROVE). |
| P17-D-62 | RESOLVED | reports/final-report.md:130, :179, :181 | **:130:** "27 rows unclassified" is now in Measured; evaluation.md:727 has `unclassified rows | 27`.<br>**:179:** a new Measured paragraph with phase-05/human-gate.md:62 (97,681), :63 (43,466) and :98 (88,663).<br>**:181:** the Interpretation paragraph now holds no new number. |
| P17-D-63 | RESOLVED | reports/final-report.md:116 | phase-01/human-gate.md:14-17 shows the orchestrator declining and citing `Read(**/.env*)` and the guard. The report now says no deterministic denial was recorded. |
| P17-D-64 | RESOLVED (residual wording: P17-K-11) | reports/final-report.md:150, :152 | **:150:** category 1 now lists the p15-demo answers and the evaluation inputs as exceptions.<br>**:152:** adds the Phase 15 S3/DynamoDB copies (phase-15/human-gate.md:133) and the Phase 16 demo-answer copy (phase-16/human-gate.md:150; PROVENANCE.txt exists). It gives per-phase gate wording. Phases 0, 1, 2, 3, 4, 5, 6, 9, 10, 11 and 13-16 match; Phases 7 and 12 do not, see K-11. |
| P17-D-65 | RESOLVED | reports/final-report.md:140, :152 | Checked events.csv: E00311 (:312, wrong session), E00312 (:313, gate :92, D-read accident) and E00316 (:317, summary :207, D-read accident) are the three counted rows (dedup column empty). E00315 (:316) is marked as a duplicate of E00312, as stated. |
| P17-D-66 | RESOLVED | reports/final-report.md:56 | instructor-approvals.md (read in full) has the 2026-11-09 model-endpoint entry and "Earlier approvals" (:38 Scala 2.13, :39 budget/account/datasets, :40 enrichment). It has no teardown entry, as the line now says. |
| P17-D-67 | RESOLVED | reports/final-report.md:224 | The list of 21 keys equals filterableKeys at index-spec-v1.json:22, :29, :36, :43 and :50, which are identical in all five indexes. |
| P17-D-68 | RESOLVED | reports/final-report.md:257, :444 | `[monotonic corpus] claims=59 absence=0` appears at repair-01-rag.md:21 and :123. Both lines now say the corpus never exercises the absence rule. |
| P17-D-69 | RESOLVED | reports/final-report.md:625 | The status column reads `still present`, which equals routing-p17.md:61. "proposed later, unscheduled" is now in the description. |

**Same-defect fixes outside the cited lines:**
- **README.md:319** uses `not replayable after teardown (archived evidence: releases/course/)`, the same form as P17-D-08. The PATH is valid and the folder has no rollback record. Correct.
- **docs/runbook.md:88** cites 127 s to hadoop-pipeline.log line 476, and the exit to run.txt. Correct.

## New findings

| ID | Severity | File:line | Failure input | Impact | Owner | Interface |
|---|---|---|---|---|---|---|
| P17-K-10 | minor | docs/runbook.md:184 (section 9) | The `bash scripts/archive-evidence.sh --local-only` row still reads `replayed earlier (phase 13, … "Archive (--local-only): passed, 13 files")`. The repair changed the same command at README.md:317 to `planned for replay (E5)`, because the Phase 13 run predates E-03 (archive-evidence.sh:580-586, the field the mode writes, `runtime: untested`). The runbook's section 9 bullets (:189-190) do not mention E-03. Its emulator rows do disclose their stale replays (:230, :231). | The same command now has two different replay lines in two documents. The runbook presents a pre-change run as replay evidence for the current code without saying so, which is the P17-D-35 defect left in a second file. E5/E11 may fill only the README line. | documentation-engineer | no |
| P17-K-11 | minor | reports/final-report.md:152 (category 3) | The repaired sentence says it gives each gate's own wording: "Phases 7, 9, 10 and 12 say only "No code changes"". But phase-07/human-gate.md:135 says "I made no code changes." And phase-12/human-gate.md:110 says "No code changes. All code is agent-written: …", which is the stronger form the sentence gives to Phases 13 to 16. | Two of the four quoted phases are misquoted. One is understated, in a sentence written to fix overstated gate wording (P17-D-64). The facts are not affected. | documentation-engineer | no |
| P17-K-12 | minor | reports/final-report.md:603 | The repair added IL-11 to the unverified list, and the list ends "…, IL-14 (…), each with runtime evidence only". The two account-quota rows cite no runtime evidence: IL-06 (service-limits.md:36, "the account value needs Service Quotas") and IL-11 (:41, the documented default is below the need; account value not checked). Only IL-02, IL-04 and IL-14 cite runtime evidence (:32, :34, :44). | The final report says 5 rows have runtime evidence when 3 do. The error predates the repair for IL-06; the repair extended it to IL-11. | documentation-engineer | no |

## Checks of the lines the repair changed (no further finding)

- **Numbers against sources:** all of the above match their sources. The service-limits counts 60/54/6 agree in service-limits.md:3, :5, :6, :8, README.md:421 and final-report.md:603. The table recount matches.
- **Replay-line forms:** every changed line uses an allowed form:
  - README.md:110 and :317 use `planned for replay (E5)`.
  - README.md:319 and runbook.md:63 and :167 use `not replayable after teardown (archived evidence: PATH)` with a real PATH.
  - demo-script.md:71, :72 and :175 use `not replayed in phase 17 (REASON)`.

  A Grep over README.md, docs/*.md and reports/*.md finds no `archived evidence: none`, no `replayed earlier (phase 17` and no `replayed in phase 17` (no false "replayed" claim).
- **Links and #fragments of changed lines:**
  - Targets confirmed: phase-14/repair-DER-infra.md, phase-14/human-gate.md, phase-15/prompt.md, phase-13/human-gate.md, phase-05/human-gate.md, phase-15 and phase-16 human-gate.md, releases/course/, hadoop-pipeline.log, run.txt, local-run-report.json, p15-demo/PROVENANCE.txt.
  - Anchors confirmed against headings: `#10-cleanup`, `#9-after-phase-17`, `#source-controlled-automation-scripts`, `#1-operator-machine-phase-17`, `#107-group-7-reproducibility` (cited, not re-read), `#3-findings-from-the-limits-check`, and the final-report anchors in README (`#23-limitations`, `#122-claim-verification`, `#234-phase-17-review-findings-status-after-the-code-re-check`).
  - I checked tracked status only for existence (no git, see Assumptions).
- **Identifiers:** a Grep over the 10 changed files for 12-digit runs, `arn:aws`, `amazonaws.com`, `execute-api`, `AKIA` and `sk-ant-` found only expected matches:
  - the release-record name's commit prefix `167196849087` (final-state.md:59, runbook.md:63);
  - a digit run inside a plan sha256 (final-state.md:46);
  - a digit run inside the emulator image sha256 (emulator.md:28, :98, unchanged lines).
- **No claim that L2 or the final review is closed:**
  - Every P16-L-10 mention says "not closed": README.md:419, api.md:304, demo-script.md:176, threat-model.md:7, :38, :59, final-report.md:260, :458, :658, :687.
  - README.md:420 and final-report.md:458 keep "The final review is REQUEST_CHANGES".
  - threat-model.md:128 keeps P17-F-01 open until session 3.
- **docs/emulator.md:** lines 48, 187 and 197 read as expected; no number changed in them. The neighbouring lines I read (44-50, 183-198) are unchanged in content.

## Coverage

- **Inputs read in full:** repair-01-docs.md, review-report-docs-1.md, review-report-docs-2.md, review-report-docs-3.md, orchestrator-notes-s2.md (including "Step 8 documentation triage" and the D-07/D-08 rulings).
- **Work order** docs/agent-runs/phase-17/prompt.md (sha256 taken as given; I have no Bash):
  - lines 35-66 (results section and replay-line forms);
  - lines 241-309 (decisions 12, 13, 14, 15);
  - lines 68-73 (standing rules);
  - Grep for E12 and public.
- **Repaired files, read at every cited line and its context:**
  - docs/threat-model.md:74-139;
  - docs/data-lineage.md:134-149;
  - docs/service-limits.md (read in full);
  - docs/api.md:20-26 and :216-227;
  - docs/emulator.md:44-50 and :183-198;
  - docs/runbook.md:60-89 and :160-231, plus a Grep of every replay line;
  - docs/final-state.md:74-135;
  - docs/demo-script.md:1-14, plus a Grep of every replay line;
  - README.md:60-139, :268-327 and :395-454, plus a Grep of PENDING and replay forms;
  - reports/final-report.md:52-69, :112-183, :224, :257, :442-458, :603, :611-627, plus the heading list.
- **Sources checked:**
  - Guard and permissions: guard-dangerous.sh (Grep `tfstate`), .claude/settings.json (Grep).
  - Phase 14: repair-DER-infra.md (headings and :18), human-gate.md:98, runbook-log.md:60-81.
  - Work orders: phase-15/prompt.md:68-69, phase-16/prompt.md:64-65.
  - Gates: phase-01/human-gate.md:3-30; phase-05/human-gate.md (Grep); phase-07/human-gate.md:130-136; phase-12/human-gate.md:110; every gate's "Manual changes" section (Grep); phase-13/human-gate.md (headings); phase-16/human-gate.md (Grep).
  - Approvals and records: instructor-approvals.md (read in full); releases/course/*.json (Glob and Grep); events.csv rows :312-:318.
  - Scripts: collector-common.sh:655-668, query-load.sh:490-507, release-manifest.sh (Grep), archive-evidence.sh:574-589, probes.sh:192-205, env.sh:45-69, scripts/README.md (headings).
  - Code and data: QueryService.scala:300-399 and :560-669, index-spec-v1.json (filterableKeys), UnsupportedClaimEndToEndSuite.scala (Grep), hadoop-pipeline.log (Grep), evaluation.md (Grep "unclassified").
  - Specification and other docs: course-specification.md:2765-2784, tool-versions.md (headings).
  - Scratchpad reports: agent-report-infra-I1b.md (Grep), routing-p17.md (Grep), repair-01-rag.md and agent-report-rag.md (Grep `claims=59`).
- **Not read:** unchanged parts of the 10 files beyond the ranges above; docs/architecture.md, docs/evaluation.md, docs/tool-versions.md body, and docs/incidents/** (not changed by the repair).
- **Not verified independently (no Bash):** `git diff` (that docs/emulator.md changed only at lines 48, 187 and 197; taken from the repair report's `git diff --stat`, 3+/3-, and the orchestrator's W5 check); `git ls-files` and `git check-ignore` for link targets (I checked existence with Read, Grep and Glob); the work-order sha256.

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 17 session 2, step 10, documentation re-check (review-report-docs-02.md); docs/agent-runs/phase-17/prompt.md sha256 c22adfc57c7fde0cae63c8520393c2c81cd12280df7f5df773e70c2e2ec8ac24 (as given, not recomputed); decisions 12, 13, 14, 15 and the results section
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools)
TEST RESULTS: not applicable (documentation re-check)
VALIDATION RESULTS: 29 of 29 findings RESOLVED (P17-D-01 major included); both same-defect fixes (README.md:319, docs/runbook.md:88) correct; 3 new minor findings P17-K-10, K-11, K-12; service-limits counts 60/54/6 consistent in three documents; no identifier; no L2 or final-review closure claim; replay forms valid on all changed lines
ASSUMPTIONS: docs/emulator.md changed only at lines 48, 187, 197 (repair report's git diff --stat and the orchestrator's W5 check; I cannot run git); link targets that exist and are not listed as untracked in the session-start snapshot are tracked; scratchpad reports become docs/agent-runs/phase-17/ files when the human copies them
RISKS: the overall final review stays REQUEST_CHANGES while P17-F-01 (major, constitutional) and P17-A-01 (PARTIAL, major; P16-L-10 not closed) are open; P17-K-10 leaves two different replay lines for the same command, which E11 must reconcile; the planned-for-replay lines added at README.md:110 and :317 must be on the human's E5 list
MANUAL ACTION REQUIRED: orchestrator: record P17-K-10..K-12 (minor; decision 15 allows no second documentation round in session 2), for E11 or the summary
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: step S-ID (identifier scan, orchestrator), then the session 2 summary; E11 repeats the documentation check on the filled-in documents
```

VERDICT: APPROVE (documentation slice: R-D1 to R-D3 after the re-check). The overall final review stays REQUEST_CHANGES while P17-F-01 and P17-A-01 are open.

| Severity | File:line | Failure input | Impact | Owner |
|---|---|---|---|---|
| minor (P17-K-10) | docs/runbook.md:184 | `archive-evidence.sh --local-only` still `replayed earlier (phase 13)`, but README.md:317 now says `planned for replay (E5)` because of E-03 (archive-evidence.sh:580-586) | two replay lines for one command; pre-change run offered as evidence without a note | documentation-engineer |
| minor (P17-K-11) | reports/final-report.md:152 | "Phases 7, 9, 10 and 12 say only 'No code changes'"; Phase 7 says "I made no code changes." (:135), Phase 12 also says "All code is agent-written" (:110) | misquoted gate wording in the sentence meant to fix P17-D-64 | documentation-engineer |
| minor (P17-K-12) | reports/final-report.md:603 | "each with runtime evidence only" covers IL-06 and IL-11, whose rows (service-limits.md:36, :41) cite no runtime evidence | overstates the evidence for 2 of 5 rows | documentation-engineer |
