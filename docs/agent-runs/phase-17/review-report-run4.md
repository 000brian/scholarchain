# review-report-run4 (Phase 17 session 3, docs-only pass after the fourth autograder run)

(Saved verbatim by the orchestrator from code-reviewer's final message.)

VERDICT: APPROVE

I found no critical or major issues. There are five minor findings below, and one risk in the rule line that needs the human's decision before the next commit.

## Scope read

- The documentation-engineer report and diff: F/agent-report-docs-run4.md and F/diff-docs-run4.txt.
- The current docs/hadoop-job-contracts.md, lines 280-325.
- The merged draft F/autograder-response.md (all 619 lines) compared with the committed docs/agent-runs/phase-17/autograder-response.md.
- Every code and record line cited in the work order.
- The autograder results, read as data: the V1 CSV, and the V4 bwilk26.md and bwilk26.csv. V2 and V3 rows were checked through V4's copies of them.
- docs/agent-runs/instructor-approvals.md. Its 2026-11-25 entries for N1 (lines 80-99) and B3 (lines 101-119) are now present. The quoted wording in the response and in 13.2 matches the instructor's text at lines 93 and 114.

## 1. B3 conditions against code and records

| Condition | Verified at | Result |
|---|---|---|
| Detected | TeamAssembler.scala:74-76 (`n > limits.hardLimit` gives Left(TeamExceedsHardLimit, "team of $n > limit")); PublicationTeamJob.scala:148-151 (buffering stops past hardLimit+1), :159-165 (overflow rejection "$mentionCount mentions > limit"); defaults XML :188-193 (value 5000) | Holds |
| Counted | TaskIO.scala:46 (REJECTED_REDUCE_RECORDS when reduceStage) and :47 (per-reason group); Counters.scala:13 (`scholarchain.reject-reason`), :47 (OVERSIZE_AUTHOR_TEAMS); PublicationTeamJob.scala:169 | Holds |
| Reported | JobRunner.scala:171-174 keeps every group whose name starts with `scholarchain`, so the per-reason group is included; :176-189 writes `_job.json`; ScholarChainHadoop.scala:94-108 writes the run report; TaskIO.scala:50-60 and :124-125 write the `rejects/v1` record; ab-pipeline.sh:419-439 copies the counters into the CSV | Holds |
| Not silently discarded | PublicationTeamJob.scala:171-177 gives reason, detail and `ws.headOption.map(_.dblpKey)`; Pipeline.scala:175 exports works from J01, and :177-179 takes mentions and teams from J07 and pairs from J08b; JobLevelTeamScaleSuite.scala:148-182 asserts both detail forms, the source IDs and the counters | Holds |
| Rationale and computational implications | hadoop-job-contracts.md:300-307 (unchanged text; the pair counts and the 693-byte estimate label were checked in R3) | Holds |
| Real data not affected | CSV line 83 (maxTeamSize 137), 82 (9316), 81 (OVERSIZE 15), 76 (oversizeTeams 15), 78 (teams 155978), 10 (works 155978), 74 (authorPapers 758328), 7 (mentions 758328), 8/11/15 (99 no_authors); J07 rows 74-83 have no reject counter; phase-05 agent-report-hadoop.md:316-320 and :375-378 match the quoted text exactly | Holds |

- The "teams = works" check is sound: J07 writes at most one team per key that has exactly one distinct work, and a rejection for any reason removes that team, so teams equal to works (155,978) means no work key was rejected.
- A second check agrees: OVERSIZE_AUTHOR_TEAMS (15) equals the oversizeTeams records (15); rejected teams raise that counter but write no oversize record, so no hard-limit rejection happened.
- The "not recorded" text is honest: records outside the subset, no unfiltered run reached J07, a zero counter shows up as a missing row.
- The two tables agree (13.2 and the response's R3-B3 table), including their "not recorded" texts.
- The hardLimit value at both commits: not checkable with read-only tools (no `git show`); the Phase 5 run arguments (agent-report-hadoop.md:119 and :151) and the experiment A arguments (results-summary.md:254) set no `teams.hardLimit`. See m1.

## 2. X5 (V1 E03)

- Line 13 of the V1 CSV has 55 "this path never existed in the repo" items and 116 "report claims" items in total (55 + 61).
- The two new items are `phase17-reports/agent-report-docs-E11-R3.md` and `phase17-reports/diff-E11-docs-R3.txt`, both named at agent-report-docs-E11-R3.md:92 and :97; the committed copy of the diff exists.
- The 53 earlier items are all still present.
- Category counts (a) 1, (b) 33, (e) 21 (scratchpad 9, phase 17 has 10), total 55: correct.

## 3. X6 (V1 E04)

- E04 lists 13 phases: 00, 01, 02, 03, 05, 07, 09, 10, 11, 12, 15, 16, 17. Matches.
- The visible files for phases 02, 05, 12 and 17, and the "+21 more" and "+6 more" counts, match the CSV.
- Phase 02: prompt.md:26 and :55-60, commit.txt:1 (41eaea2), human-gate.md:60, agent-report.md:7-15 (8 absolute paths, exactly these files).
- Phase 05: prompt.md:24, :27, :98; prompt-02.md:24; agent-report-dataset.md:25; agent-report-er.md:15, :16, :21, :31; repair-03.md:20; repair-01.md:27-28; agent-report-er-eval.md:19, :24; commit.txt:1 (a5322d4); human-gate.md:105-106.
- Phase 12: prompt.md:118, :122, :125-126; commit.txt:1 (4ad139e); agent-report-factory.md:8-10, :36-38; review-report-factory.md:28 (APPROVE); human-gate.md:151 (the ratification). The ":41" citation is the exception, see m3.
- Phase 17: prompt.md:152, :190; agent-report-rag.md:13, :15, :16, :17, :7; repair-01-rag.md:9, :10, :11; repair-E1-rag.md:8, :9; runbook-log.md:22-23.

## 4. X7 (V1 E05)

- V1 line 15 has the four "phase-17: build-engineer changed releases/manifest/…" items, for both release candidates.
- Cited lines check out: release-manifest.sh:26 (`INSIDE_REPO_OUT_DIR="releases/manifest"`); prompt.md:145, :153, :213; docs/agents.md:36, :103, :139.
- Runbook rows: runbook-log.md:63 and :188 (the human ran the script), :64 (c6bbe14, 2 files), :190 (daca689, 2 files), :201 (the post-RC path check shows only the two manifest files in daca689).

## 5. Fourth-run section

- V4 figures match bwilk26.md:3-6: 9.01 of 9.0, 1.00 deducted, 8.01, 3 rows pending a human, 0.00 points at stake, 2 rows marked CHECK.
- V4 rows match bwilk26.csv: HADOOP-04, DATA-02 and DATA-05 met; FACTORY-AGENTS-NOT-SUBMITTED and HADOOP-PAIRS-WRONG yes/minor at 0.5 each; HADOOP-12 and FACTORY-05 CHECK; CLAIMS_NOT_DONE, SECRETS and HARDCODED PENDING; 14 V3 rows.
- Judge quotes at csv lines 34 and 8 are exact.
- V1 statuses: FAIL P01, G02, G03, E03, R03; WARN K01, K03, E01, E04, E05, C03, O02, O03, V01, A01, U01. C03 lists only the two course-specification copies, 2 mentions each.
- "On a fresh clone" is supported by runbook-log.md:204.
- The improved / unchanged / new split is consistent with the third-run section, lines 345-350.

## 6. Diff scope

- hadoop-job-contracts.md: three hunks, all inside 13.2 (lines 286-325, to the end of the file); headings unchanged, anchors resolve; the four links in the changed text resolve.
- Response merge points versus the committed file: the N1 paragraph (315); X1 rows 54-55 and the note and count heading (433-438); the R3-B3 decision, table and "not recorded" text (506-518); the new section (556-619). Headings and positions identical up to line 311; lines 275-286, 300-319, 425-450 and 488-536 compared; lines 1-274 not byte-compared.

## 7. Identifiers

- Merged response: 0 matches for twelve-digit runs, arn:aws, amazonaws, s3://, AKIA, IPv4, N/N ratios or the sample-application name.
- hadoop-job-contracts.md: the only match is s3:// at line 52, outside 13.2 and already there before.
- Quoted here: commit SHAs and the experiment folder name only. Left out on purpose: a release-record file name with a twelve-digit run, an EMR cluster ID inside a phase-16 file name and the sample-application name, all in the V1 CSV.

## Findings

| Severity | File:line | Failure input | Impact | Owner |
|---|---|---|---|---|
| minor (m1) | docs/hadoop-job-contracts.md:309 | "the default at the code of both runs, `1279c37` and `684636e`". The Phase 5 run used code version `phase05-1279c37-02-dirty`: 1279c37 plus uncommitted Phase 5 changes that included the defaults XML (phase-05/agent-report-hadoop.md:24, :171, :287). | The value 5000 is almost certainly right (no `-D` override at :119/:151; the Phase 5 defaults changes were blocking settings only), but 1279c37 is not the exact code that ran. Fix: cite the committed Phase 5 code (2297990, per phase-05/human-gate.md:105), or say "1279c37 plus the Phase 5 changes, which did not touch teams.hardLimit". | documentation-engineer |
| minor (m2) | F/autograder-response.md:283 | "section 13.2 … is at :286-307 (the file now has 307 lines)" | Stale: the file has 325 lines and 13.2 runs :286-325. | human architect (orchestrator prepares the text) |
| minor (m3) | F/autograder-response.md:601 | "ratification phase-12/human-gate.md:151 …, also :41" | :41 is a status row ("Done in step F"); :70 is the second ratification statement ("I ratified and committed it (`4ad139e`)"). | human architect |
| minor (m4) | F/autograder-response.md:591 | "Against the first run (A9 …) … phases 05 and 17 now show other files" | A9 kept only one example per phase (CannotLinks.scala, AbsenceRuleSuite.scala, RetrievalEvalSet.scala), still flagged; the records can show neither "other files" nor no change. Label it as the human's comparison. | human architect |
| minor (m5) | docs/agent-runs/instructor-approvals.md:80-119 (human edit, uncommitted), against scripts/release-manifest.sh:29 (rule line) | The rule line allows post-RC changes under docs/agent-runs/ only in docs/agent-runs/phase-17/; instructor-approvals.md is outside it. | The next commit would fail the E12 post-RC path check unless an explicit exception is recorded (as for the earlier instructor-approvals entries), or the record is kept under phase-17. | human architect |

STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 17 session 3, docs-only pass after the fourth autograder run (instructor-approved; post-RC, docs only): read-only review of agent-report-docs-run4.md, diff-docs-run4.txt (13.2) and the orchestrator's merges into autograder-response.md.
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools)
TEST RESULTS: not applicable (Markdown only)
VALIDATION RESULTS: all B3 code and record citations verified; V1 E03 116 items, 55 "never existed", the two new ones as named; V1 E04 13 phases, files match; V1 E05 the four release-manifest items; V4 9.01, 1.00, 8.01 and the row statuses match; 13.2 links resolve; identifier scan found nothing new.
ASSUMPTIONS: diff base HEAD b049e9e (no git diff run); hardLimit at 1279c37 and 684636e from documentation-engineer's `git show`, supported by run arguments with no override; "each commit adds only the two manifest files" rests on runbook-log.md:64, :190, :201; response lines 1-274 checked by heading and position only.
RISKS: m5 (rule line); this pass's reports name scratchpad paths in FILES CREATED (response line 587 says so); runbook-log citations point at the working tree (rows 22-23, 63-64, 188-190, 200-204 read correctly now).
MANUAL ACTION REQUIRED: the human decides m5 and whether m1 to m4 are fixed (minor; no repair round required); the human copies this report with `cp -n`.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: merge, decisions on m1-m5, commit; then the E12 post-RC path check (accounting for m5).

## Orchestrator note (after the review)

m2, m3 and m4 are in the orchestrator's merged text and were fixed by the orchestrator in the scratchpad response right after this review (13.2 line range updated with "then"/"after the fourth-run pass"; :70 cited as the second ratification statement, :41 as the status row; the 05/17 "other files" claim labelled as the human's comparison). m1 (documentation-engineer) and m5 (the human) were left for the human's decision.
