# agent-report-docs-run4 (Phase 17 session 3, after the fourth autograder run; docs-only pass)

STATUS: COMPLETE
OWNER: documentation-engineer
WORK ORDER: Phase 17 session 3, docs-only pass after the fourth autograder run (instructor-approved; HEAD b049e9e = origin/main; release candidate 4feef0ff117e9b7b0c1f411e875950e0bde06ba4). Tasks 1 (B3 conditions in docs/hadoop-job-contracts.md 13.2), 2 (response drafts i to v), 3 (diff, link check, identifier scan).
FILES CREATED: none in the repository. Scratchpad: phase17-reports/diff-docs-run4.txt (46 lines, sha256 ac86bbcdfd297e90007c8a4bba1fb5371902d2f33a205adc0cff96e0270dae15) and this report.
FILES MODIFIED: docs/hadoop-job-contracts.md (section 13.2 only; 20 lines added, 2 changed)
COMMANDS RUN: read-only git -C (show, log, diff, status), grep, head, tail -n, wc, ls, shasum; a bash link-check loop; `git -C … diff -- docs/hadoop-job-contracts.md > …/diff-docs-run4.txt`. Deviations from the allowed list, all read-only, reported for completeness: one `cat` of docs/agent-runs/phase-17/commit.txt (failed: the file does not exist yet), one `cd` inside a first link-check attempt that failed in zsh before running, and `paste`, `tr`, `sort -u` and `cut` in pipes that only formatted output.
TEST RESULTS: not applicable (Markdown only; no sbt run, none allowed)
VALIDATION RESULTS: link check on docs/hadoop-job-contracts.md: 13 relative file links, 0 missing; 17 distinct in-file anchors, all match the file's unchanged headings. Identifier scan on the 20 added diff lines: twelve-digit runs 0, twelve zeros 0, arn:aws 0, amazonaws 0, s3:// 0, AKIA 0, IPv4 0, N/N ratios 0.
ASSUMPTIONS: the instructor's N1 and B3 wording is as the work order quotes it; the human is adding it to docs/agent-runs/instructor-approvals.md (at the time of this pass that file has no N1 or B3 entry yet; its last entries are dated 2026-11-24).
RISKS: (1) docs/hadoop-job-contracts.md:289 points to the instructor's decision in docs/agent-runs/instructor-approvals.md before that entry exists; the link resolves (the file exists), but the text is true only once the human adds the entry. (2) This report and the diff are named with phase17-reports/ scratchpad paths; if their committed copies keep that wording, V1 E03 will list them as "never existed" on the next run, as with X5 (same category (e)). (3) The diff was saved with a path filter (`-- docs/hadoop-job-contracts.md`), not as the plain `git diff` the work order wrote: the working tree also holds the human's uncommitted change to docs/agent-runs/phase-17/runbook-log.md, which is not mine and would have been attributed to this pass.
MANUAL ACTION REQUIRED: the human adds the N1 and B3 decisions to docs/agent-runs/instructor-approvals.md; the orchestrator merges drafts (i) to (v) into the response; the human copies this report and the diff with `cp -n`.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer reviews the 13.2 change and drafts (i) to (v); human merge and commit.

## 1. Repository changes (docs/hadoop-job-contracts.md, section 13.2)

All lines are in the current file (now 325 lines).

| Line | Old | New |
|---|---|---|
| 289 | (none) | New bullet "Instructor decision": quotes the B3 condition, names the three documentation requirements, points to [`docs/agent-runs/instructor-approvals.md`](agent-runs/instructor-approvals.md) (human-owned) and to the conditions table. |
| 298 | (none) | New bullet "Where the count is reported": `_job.json` per job (`hadoop/common/JobRunner.scala:171-189`), the run report (`hadoop/pipeline/ScholarChainHadoop.scala:94-108`), the collector's `hadoop-job-counters.csv` (`experiments/collectors/ab-pipeline.sh:419-439`). |
| 299 | (none) | New bullet "What happens to the record (not silently discarded)": reject record with reason, detail (size and limit; the two detail forms from `TeamAssembler.scala:76` and `PublicationTeamJob.scala:163`), DBLP key (`PublicationTeamJob.scala:159-177`; `TaskIO.scala:39-61`); the work record stays in the J09 export (`Pipeline.scala:175`); team, mentions and pairs absent (`Pipeline.scala:177-179`). |
| 307 | "the largest team in the recorded run had 137 authors (next item)." | "the largest team in the recorded runs had 137 authors ("Recorded effect on real data" below)." (the next item is now option A) |
| 309-313 | "Recorded effect. None in the recorded run. … lines 74-83 … No team reached the hard limit." | "Recorded effect on real data: none." with two sub-items: Phase 15 experiment A attempt 2 (CSV lines 74-83, 137 at line 83, no reject counter, zero counters are absent rather than written as 0, J01 rejects at lines 8, 11, 15, derived check teams 155,978 = works 155,978 at lines 78 and 10, author mentions 758,328 = mentions at lines 74 and 7) and the Phase 5 local real run attempt 2 (docs/agent-runs/phase-05/agent-report-hadoop.md:316-320, :375-378 "Nothing else was rejected."); hardLimit default 5000 at both runs' code (1279c37, 684636e); what is not recorded (records outside the subset rule; no unfiltered full-release run through J07). |
| 315-325 | (none) | New bullet "The instructor's conditions and where they are met": a seven-row table (detected, counted, reported, not silently discarded, rationale, computational implications, real data not affected). |

Checks behind the new text (all read in this pass):
- `hardLimit` 5000 at 1279c37 (`git show 1279c37:…defaults.xml`, line 183) and at 684636ef (line 190). No `hardLimit` setting in experiments/results-summary.md:254 (experiment A arguments) or in docs/agent-runs/phase-05/agent-report-hadoop.md (grep: no match).
- experiments/raw/p15-a-hadoop/20261120T225632Z-eb786c5275a3-5c75/hadoop-job-counters.csv: 144 lines, no line ending in ",0"; J07 rows 74-83 hold no reject-reason, REJECTED_REDUCE_RECORDS or rejects/v1 counter.
- The contract test at tests/contracts/…/hadoop/JobLevelTeamScaleSuite.scala:148-175 asserts both detail forms ("team of 13 > 12", "25 mentions > 12") with reason and source record ID.

## 2. Drafts for the response (Markdown, ready to paste)

### (i-a) Under "### N1 (V3 FACTORY-AGENTS-NOT-SUBMITTED, minor)", append

```markdown
Instructor decision (after the fourth run): the use of the built-in subagent is "acceptable ... No additional corrective action is necessary." Recorded by the human in docs/agent-runs/instructor-approvals.md. No change follows: the disclosure above and README.md:90 stay as they are.
```

### (i-b) Under "### R3-B3 (V2 HADOOP-PAIRS-WRONG) …" (or under "## B3"), append

```markdown
Instructor decision (after the fourth run): keeping the 5,000-author bound is acceptable "provided that oversized records are explicitly detected, counted, and reported rather than silently discarded", with the rationale, the computational implications and evidence that the limit did not affect real-data results documented. Recorded by the human in docs/agent-runs/instructor-approvals.md; the documentation is docs/hadoop-job-contracts.md section 13.2 (conditions table at the end of that section).

| Condition | Where it is met (file:line) |
|---|---|
| Detected | modules/hadoop/src/main/scala/org/scholarchain/hadoop/teams/TeamAssembler.scala:75-76 (more than `hardLimit` authors); PublicationTeamJob.scala:148-151, :159-165 (the reducer stops buffering past `hardLimit + 1` mentions and rejects); limit scholarchain-hadoop-defaults.xml:188-193 |
| Counted | per-reason counter `scholarchain.reject-reason:team_exceeds_hard_limit` and REJECTED_REDUCE_RECORDS (common/TaskIO.scala:46-47; group name common/Counters.scala:13); OVERSIZE_AUTHOR_TEAMS (PublicationTeamJob.scala:169; Counters.scala:47) |
| Reported | counters written to each job's `_job.json` (common/JobRunner.scala:171-189) and to the run report (pipeline/ScholarChainHadoop.scala:94-108); one record per team in `rejects/v1/` (TaskIO.scala:50-60, :124-125) |
| Not silently discarded | reject record with reason, size, limit and DBLP key (PublicationTeamJob.scala:159-177); the work record stays in the J09 export (pipeline/Pipeline.scala:175); contract test tests/contracts/src/test/scala/org/scholarchain/contracts/hadoop/JobLevelTeamScaleSuite.scala:148 |
| Rationale | docs/hadoop-job-contracts.md:300-307 ("Why", "Why the bound stays"); specification docs/agent-runs/phase-17/course-specification.md:1155, :2718, :2729 |
| Computational implications | docs/hadoop-job-contracts.md:302-305: pair counts from the limits; byte volumes labelled as estimates (the 693-byte figure has no committed record) |
| Real data not affected | experiments/raw/p15-a-hadoop/20261120T225632Z-eb786c5275a3-5c75/hadoop-job-counters.csv:83 (maxTeamSize 137), :74-83 (no reject counter in J07), :10 and :78 (155,978 works, 155,978 teams); docs/agent-runs/phase-05/agent-report-hadoop.md:320 (largest team 137), :375-378 ("Nothing else was rejected."); summary at docs/hadoop-job-contracts.md:309-313 |

What is not recorded: team sizes of DBLP records outside the subset rule `dblp-subset-v1` (excluded in J01, before J07); no committed run of the unfiltered full release reaches J07. A zero counter is not written as 0 in the experiment file (it has no row with the value 0); the zero is shown by absence and by the derived check teams = works.
```

### (ii) X5 (V1 E03): two new "never existed" entries

Add after row 53 of the X1 table (appended, so the existing row numbers stay; in the checker's own order they sit between rows 45 and 46):

```markdown
| 54 | 17 | phase17-reports/agent-report-docs-E11-R3.md | e | The session-scratchpad path of the report itself | docs/agent-runs/phase-17/agent-report-docs-E11-R3.md:97 ("FILES CREATED: none in the repository; scratchpad: …"); committed copy docs/agent-runs/phase-17/agent-report-docs-E11-R3.md (b049e9e) |
| 55 | 17 | phase17-reports/diff-E11-docs-R3.txt | e | The session-scratchpad path of a diff file | docs/agent-runs/phase-17/agent-report-docs-E11-R3.md:92, :97; committed copy docs/agent-runs/phase-17/diff-E11-docs-R3.txt (b049e9e) |
```

Section text to add under X1:

```markdown
### X5 (V1 E03, fourth run on b049e9e)

V1 1.0.8 on b049e9e lists 116 items under E03 (third run: 120), and 55 of them add "(this path never existed in the repo)" (third run: 53). The 53 earlier items are unchanged. The two new ones are the scratchpad paths of the E11-repeat report and its diff, named in that report's FILES CREATED line; the human committed both copies in b049e9e (rows 54 and 55 above). Real mismatches: still none.

| Category | Count | Phases |
|---|---|---|
| (a) spec-required probe, created and removed in the phase | 1 | 01 |
| (b) shortened, module-relative or template path that resolves to a file changed by the phase's commits | 33 | 09 (13), 11 (2), 12 (1), 15 (4), 16 (11), 17 (2) |
| (c) planned file never created | 0 | none |
| (d) real mismatch | 0 | none |
| (e) the report names the path, but not as a claim that a committed file changed (generated `target/` output 10, scratchpad path 9, deleted temporary helper 1, field template 1) | 21 | 03 (1), 05 (9), 07 (1), 17 (10) |
| Total | 55 | |
```

Checked: E03 entries counted with grep on the V1 CSV row (55 "never existed", 116 items in all); the 55 paths listed one by one match the 53 X1 rows plus these two.

### (iii) X6 (V1 E04): 13 phases

```markdown
### X6 (V1 E04, fourth run on b049e9e)

V1 E04 now lists 13 phases: 00, 01, 02, 03, 05, 07, 09, 10, 11, 12, 15, 16, 17. Against the first run (A9: 00, 01, 03, 04, 05, 07, 09, 10, 11, 14, 15, 16, 17), phases 02 and 12 are new and 04 and 14 are gone; phases 05 and 17 now show other files. The third run's E04 details were not kept, so "new since the third run" is the human's comparison. The changed files of these four phases are listed in that phase's reports, mostly as absolute local paths or module-relative paths, which the checker does not match (inferred from the reports, not from the checker's code).

| Phase | Flagged files (as V1 shows them) | Owner and work order | Commit | Listed in |
|---|---|---|---|---|
| 02 | .gitignore, .scalafmt.conf, build.sbt, docker/services/Dockerfile, project/Ledger.scala, project/TestGate.scala, project/build.properties, project/plugins.sbt (all 8 shown) | build-engineer; docs/agent-runs/phase-02/prompt.md:26 ("Use the build-engineer agent to create the ScholarChain sbt multi-project build and repository skeleton"), deliverables :55-60 | 41eaea2 (phase-02/commit.txt:1; its 8 files are exactly these); gate phase-02/human-gate.md:60 | phase-02/agent-report.md:7-15 (FILES CREATED, absolute paths) |
| 05 | datasets/dataset-manifest.yaml | dataset-engineer; phase-05/prompt.md:24 (record the subset counts in the manifest) | 2297990 ("phase-05 part 1") | phase-05/agent-report-dataset.md:25 (FILES MODIFIED, absolute path) |
| 05 | er/llm/adjudication-output-schema-v1.json, er/llm/adjudication-prompt-v1.txt | entity-resolution-engineer; phase-05/prompt.md:27, :98 (the adjudication prompt and schema live in the module's resources, owned by entity-resolution-engineer) | 2297990 | phase-05/agent-report-er.md:15, :16 (module-relative) |
| 05 | er/cluster/CannotLinks.scala | entity-resolution-engineer; phase-05/prompt.md:27 | 2297990 | phase-05/repair-03.md:20 |
| 05 | er/data/Loader.scala, er/data/Model.scala | entity-resolution-engineer; phase-05/prompt.md:27 | 2297990 | phase-05/agent-report-er.md:21; repair-01.md:27, :28 |
| 05 | er/eval/EvalMain.scala, er/eval/EvalReport.scala | entity-resolution-engineer; phase-05/prompt-02.md:24 (run 2 gold-set evaluation) | 2297990 (EvalMain), a5322d4 (both; phase-05/commit.txt:1); gate phase-05/human-gate.md:106 | phase-05/agent-report-er.md:31; agent-report-er-eval.md:19, :24 |
| 12 | .claude/agents/dataset-engineer.md, .claude/agents/infra-engineer.md, docs/agents.md (all 3 shown) | factory-engineer, step F; phase-12/prompt.md:118 (session 1, step F), :122 (docs/agents.md), :125-126 (the `bash -n` wording and the infra-engineer procedure) | 4ad139e (phase-12/commit.txt:1; these 3 files, 5 lines each way) | phase-12/agent-report-factory.md:8-10, :36-38; review phase-12/review-report-factory.md:28 (APPROVE); ratification phase-12/human-gate.md:151 ("I ratify the step F changes to `.mcp.json`, `.claude/settings.json`, `docs/agents.md` and the dataset-engineer and infra-engineer files (commit 4ad139e…)"), also :41 |
| 17 | rag/eval/benchmark-v1-eval-set.json, rag/eval/RetrievalEvalSet.scala, test …/eval/RetrievalEvalSetSuite.scala, test …/runtime/ClaimVerificationSuite.scala | rag-engineer; phase-17/prompt.md:152 (step 1 row), :190 (step 1) | d8b23f2 | phase-17/agent-report-rag.md:16, :15, :17, :13 |
| 17 | rag/ingest/RagIngestJob.scala, test …/RagIngestJobSuite.scala | rag-engineer; repair step 6 (phase-17/repair-01-rag.md:5) | d8b23f2 | phase-17/repair-01-rag.md:9, :11 |
| 17 | rag/runtime/ClaimVerification.scala, test …/runtime/AbsenceRuleSuite.scala | rag-engineer; step 1 and the E1 repair (phase-17/repair-E1-rag.md:5; runbook-log.md:22-23) | d8b23f2, b4d8f12 | phase-17/agent-report-rag.md:7; repair-01-rag.md:10; repair-E1-rag.md:8, :9 |

Phase 17 has no commit.txt yet (the gate commit adds it); the commits above are those whose subject starts with "phase-17".

Unexplained: none of the files shown. Not checked: the hidden "+21 more" files of phase 05 and "+6 more" of phase 17 (V1 does not print them, in the CSV or the JSON). The other nine phases are as in A9.
```

### (iv) X7 (V1 E05): releases/manifest/ attributed to build-engineer

```markdown
### X7 (V1 E05, fourth run on b049e9e)

Found: "phase-17: build-engineer changed releases/manifest/<RC>/release-manifest.json, owned by deploy-engineer" and the same for release-manifest.md, for both release candidates 4feef0ff117e9b7b0c1f411e875950e0bde06ba4 (new in this run) and b4d8f1229fc145e1492e1a8cbb8a44a368d026af (already in A10).

Answer: no agent wrote these files.
- They are written only by scripts/release-manifest.sh, infra-engineer's script (docs/agent-runs/phase-17/prompt.md:153, I1a row; :213, "A read-only script I run at E10 (again only if E10 repeats)"); its fixed output folder inside the repository is `releases/manifest` (scripts/release-manifest.sh:26).
- The human ran it at E10 and at the E10 repeat (docs/agent-runs/phase-17/prompt.md:145, "infra-engineer (script), me (run)"; runbook-log.md:63 and :188) and committed the output: c6bbe14 (b4d8f12 manifest; runbook-log.md:64) and daca689 (4feef0f manifest; runbook-log.md:190). Each commit adds only the two manifest files (`git show --name-status`).
- docs/agents.md:36 (deploy-engineer row): release records under `releases/**` are "written only by approved scripts it executes (nobody hand-edits them; the guard blocks Write, Edit, and shell writes there for every agent)"; docs/agents.md:103 (lifecycle row "Release records (`releases/**`)": "written only by the approved scripts it runs"); docs/agents.md:139 (guard: "blocks every write to `releases/**` (records come only from approved scripts)").
- The checker attributes every file of a phase to one agent (A10). Phase 17's chosen agent is build-engineer, which never touched `releases/**`. Known limitation of the checker; no change.
```

### (v) Fourth run section

```markdown
## Fourth run (b049e9e)

The human ran V1 1.0.8, V2 2.3.5, V3 3.0.5 and V4 4.0.0 against commit b049e9e6cd10d8272bbbed53574600ad3eceaa54 (V1 `run_date` 2026-11-25; result folders V2 p17-autograde-3-20261125-163948, V3 p17-autograde-3-20261125-164636, V4 20261125-164947). This is the first run on a commit that contains the R3 repairs (release candidate 4feef0f plus the documentation commits after it).

| Result | Checks |
|---|---|
| Improved (since the third run) | V2 HADOOP-04 partial -> met (B1, R3-B1). V2 DATA-02 and DATA-05 partial -> met (N4, R3-N4). V1 C03: only the two copies of the course specification are left (2 mentions each); this file is no longer counted (N3). V1 E03 items 120 -> 116. V4 score 7.78 -> 8.01. |
| Unchanged | V1 FAIL: P01, G02, G03, E03, R03. V1 WARN: K01, K03, E01, E04, E05, C03, O02, O03, V01, A01, U01. V2: every other row as in the third run, including HADOOP-PAIRS-WRONG yes/minor (B3) and HADOOP-12 met with CHECK. V3: all 14 rows as in the third run, including FACTORY-07 met and FACTORY-AGENTS-NOT-SUBMITTED yes/minor (N1). V4: pending rows CLAIMS_NOT_DONE (X1), SECRETS (X2) and HARDCODED (X3); CHECK on FACTORY-05 and HADOOP-12. |
| New | X5: two more V1 E03 "never existed" items (53 -> 55), both scratchpad paths. X6: V1 E04 lists phases 02 and 12, and other files for 05 and 17. X7: V1 E05 attributes the 4feef0f release manifest to build-engineer. |

V4 score (bwilk26.md): "Points gained: 9.01 of 9.0 (automatic categories only)", "Points deducted: 1.00", "Score (gained - deducted): 8.01" (as V4 prints it; gained is 0.01 over the stated maximum). "pending human: 3 row(s), 0.00 gain points at stake; marked CHECK: 2". The 1.00 deducted is the same two minor deductions as before, 0.50 each: N1 (FACTORY-AGENTS-NOT-SUBMITTED) and B3 (HADOOP-PAIRS-WRONG).

Judge notes worth recording (read as data): V2 HADOOP-12 says the EMR run "predates the J05p/J05bp pre-passes, which the docs say never ran on EMR" (true; R3 status line). V3 FACTORY-07 says "No Phase 17 human gate or commit.txt was found; that phase appears still open" (true; the gate commit adds both, A1).

Instructor decisions (recorded by the human in docs/agent-runs/instructor-approvals.md):
- N1: the built-in subagent use is "acceptable ... No additional corrective action is necessary."
- B3: retaining the 5,000-author cap is acceptable "provided that oversized records are explicitly detected, counted, and reported rather than silently discarded", with the rationale, computational implications and evidence that the limit did not affect real-data results documented. Each condition and its evidence: B3 table above and docs/hadoop-job-contracts.md section 13.2.
```

## 3. Unexplained items

- None among the E04 files V1 prints. The hidden remainder (phase 05 "+21 more", phase 17 "+6 more") was not checked because V1 prints neither list.

## 4. Unverified claims

- "New since the third run" for X6 rests on the human's comparison: the third run's V1 CSV and JSON were overwritten by the fourth run (same file names), and no saved copy of the third-run E04 details was found in the scratchpad reports. I compared only against the first run (A9).
- The statement that the checker does not match absolute or module-relative paths is inferred from the reports and the flagged items, not from V1's code.
- The N1 and B3 instructor quotations are taken from the work order; they are not yet in docs/agent-runs/instructor-approvals.md.
- In the experiment A file, "zero counters are absent" is shown by the file (no row has the value 0) and by `JobRunner.scala:171-174` (only counters Hadoop returns are written); that Hadoop returns no counter that was never incremented is general Hadoop behaviour, not checked in this repository.

## 5. Link and identifier counts

- Link check, docs/hadoop-job-contracts.md: 13 relative file links checked, 0 missing; 17 distinct in-file anchors, all matching headings.
- Identifier scan of the 20 added lines in diff-docs-run4.txt: 0 twelve-digit runs, 0 twelve zeros, 0 arn:aws, 0 amazonaws, 0 s3://, 0 AKIA, 0 IPv4, 0 N/N ratios. The drafts in section 2 use no ratios in N/N form ("53 -> 55" and "120 -> 116" are before/after values). They contain commit hashes, the experiment run folder name (already in the repository) and no account IDs, bucket names, ARNs, keys, endpoints, IPs or the tutorial's sample-application name. Two release-record file names in V1 E05 for phase 14 contain a twelve-digit run, so they are not quoted here.

## INTERFACE HANDOFF

- Files changed: docs/hadoop-job-contracts.md, lines 289, 298-299, 307, 309-313 and 315-325 (section 13.2 only). The headings did not change, so every existing anchor `#132-teams-over-the-hard-limit-are-rejected-check-hadoop-pairs-wrong` (reports/final-report.md:622 and others) still resolves.
- Public signatures: none (Markdown).
- Fixtures: none.
- Thread-safety: not applicable.
- Limits: line citations are for the current working tree (HEAD b049e9e plus this edit). The response drafts cite docs/hadoop-job-contracts.md line numbers after this edit (300-313).
