PHASE RESULT: READY_TO_TAG_WITH_OPEN_ITEMS

Rule 3 applies. Rule 1 does not: the run reached E12 again. Rule 2 does not: every final-gate item and every tier 1 requirement is met or `deviation` on the new release candidate; no critical or major finding is open; every final check passed again on the new release candidate (fresh clone, `check.sh`, README replay, release manifest, documentation check, post-RC path check, history scan). **The `deviation` lines in orchestrator-summary.md, orchestrator-summary-2.md and below count as accepted only once the human accepts each by name in `human-gate.md`** (the E13 precondition). Open items: D19 (video link, blocked-on-human), the human's autograder re-run before the gate (planned, R3 plan), accepted deviations, known limitations, recorded minor findings, and E13/E14.

# Phase 17 session 3 summary 3 (orchestrator, E12 repeat after repair round R3)

- **Why a repeat:** after orchestrator-summary-2.md, the instructor approved code and documentation fixes in writing on four binding conditions, and the human authorized repair round R3. R3 changed code, tests, the dataset manifest and two scripts, so a new release candidate was cut and E2 to E5, E10 and E11 were repeated on it (the repeat rule, prompt.md:332).
- **Scope:** this file records only what changed since orchestrator-summary-2.md. Every table and line in the two earlier summaries still holds unless this file says otherwise.
- **Work order sha256:** c22adfc57c7fde0cae63c8520393c2c81cd12280df7f5df773e70c2e2ec8ac24 (unchanged).
- **Release candidate:** 4feef0ff117e9b7b0c1f411e875950e0bde06ba4, recorded 2026-11-25T17:23:26Z. **Superseded:** b4d8f1229fc145e1492e1a8cbb8a44a368d026af (tested at the first E3-E5 and E10; its manifest folder stays committed as history).
- **HEAD at this summary:** b049e9e6cd10d8272bbbed53574600ad3eceaa54 (= origin/main). The gate commit (this summary, `human-gate.md`, `commit.txt`, the log) follows; it is the commit to tag.
- **Tag deadline:** the human's new deadline is Nov 30, 8 pm; with the human's rule (tag at least 24 hours earlier) the tag is due by **Nov 29, 8 pm, America/Chicago (CST)** (confirmed by the human; course deadline Nov 30, 8 pm).
- **Detail:** `runbook-log.md`, rows from "E12 plan change: repair round R3 (human)" on.

## Steps since summary 2

| Step | Status | Evidence (runbook-log row; report) |
|---|---|---|
| Instructor approval of R3, four conditions | recorded | RL "E12 plan change: repair round R3 (human)"; docs/agent-runs/instructor-approvals.md (the human's entry) |
| Autograder run 3 (on 62de5e5, working tree with the then-uncommitted N1-N5 text) | recorded | RL same row; V4 8.78 of 9.0 gained, 1.00 deducted (N1, B3), 7.78; pending X1-X3; autograder-response.md "Third run" |
| Classification (condition 4) | recorded | genuine defects B1, B3, N4; not defects A1-A3, A7-A17, X2, X3 (no change); autograder-response.md "Repair round R3" |
| Part A: X1, X4, Third run | done: APPROVE | agent-report-docs-R3-partA-2.md (redacted copy); review-report-R3-partA.md |
| Part B: B1 code (hadoop-engineer), N4 records (dataset-engineer), adversarial tests (test-engineer) | done: APPROVE | agent-report-hadoop-R3.md, agent-report-dataset-R3.md, test-report-R3.md (production defect found), repair-hadoop-R3.md, test-report-R3-2.md; review-report-R3-partB.md |
| B3 decision | recorded | human option D: the 5,000-author hard limit is a deliberate safety bound (RL "E12 R3 decisions 2 (human)"); numbers verified by code-reviewer |
| Docs pass, repair, minor pass, citation fixes | done: APPROVE | agent-report-docs-R3.md, repair-docs-R3.md to repair-docs-R3-4.md; review-report-R3-docs.md (REQUEST_CHANGES, 1 major, fixed), -docs-2.md, -minor.md, -citations.md (REQUEST_CHANGES, 1 major, fixed), -citations-2.md |
| Full `sbt check` after the last source change | exit 0 | test-report-R3-3.md: module tests 151 suites, 2362 passed, 5 skipped; contract tests 35 suites, 418 of 418 |
| Pre-RC decisions | recorded | RL "E12 R3 human decisions on the pre-RC list": validate.sh run by the human (PASS); P17-M-04 fixed; :107 polish; items 3-7 unchanged |
| P17-M-04 and follow-ups | done: APPROVE | repair-infra-R3.md, repair-infra-R3-2.md (scripts/README.md:5, :59; release-manifest.sh comment and help text only, shellcheck clean); repair-docs-R3-5.md to -7.md; review-report-R3-final.md, -final-2.md, -final-3.md |
| R3 commits (six), push | done | 24490a5 hadoop-engineer, e31b3bc test-engineer, 15f77c7 dataset-engineer, dc57e5d infra-engineer, f28cc74 documentation-engineer, 4feef0f records; each after its own clean scan step; push cdd36c8..4feef0f |
| E2 repeat (new RC) | done | RL "E2 (repeat, R3)" |
| E3 repeat: fresh clone of the GitHub remote, `sbt clean check` | done (final gate item 1 met) | RL "E3a/E3b (repeat, R3)": exit 0, 1205 s, 2362 / 418, marker 2026-11-25T17:46:00.163680Z |
| E4 repeat: `check.sh` in the clone | done (final gate item 2 met, Terraform included) | RL "E4 (repeat, R3)": 9 of 9 gates, 8 release-manifest probes; check-20261125T181722Z-4feef0ff117e-b642.json |
| E5 repeat: README replay | done (all 47 lines exit 0) | RL "E5 part 1", "E5 parts 2 and 3a", "E5.40 to E5.47 (repeat, R3)"; optional `claude` session not repeated (first E5 session cited) |
| E10 repeat: release manifest | done | RL "E10 (repeat, R3)", "E10b": releases/manifest/4feef0ff117e9b7b0c1f411e875950e0bde06ba4/ (commit daca689); identical to the dry run except the generated time |
| E11 repeat | done: APPROVE | manifest-checksums-2.txt (51 MATCH, 18 MATCH-RECORD, 0 DIFF); agent-report-docs-E11-R3.md, repair-docs-E11-R3.md; review-report-final-docs-R3.md (APPROVE, 2 minors, fixed), review-report-final-docs-R3-2.md (APPROVE); identifier-scan-R3.md (no unexpected match); commit b049e9e, pushed |
| E12 repeat: post-RC path check | done (pass) | below |
| E12 repeat: history scan | done (expected matches only) | below |
| E12 repeat summary | this file | – |
| Autograder re-run (the human) | pending | planned in the R3 plan, before the gate |
| Identifier scan 3, human gate, `commit.txt`, gate commit | pending (the human) | – |
| E13 tag | pending | precondition: the final summary's result line, `human-gate.md` accepting the phase and every listed deviation by name; due by Nov 29, 8 pm CST |
| E14 publication check | pending | records `VISIBILITY: public since 2026-11-24` |

## E12 repeat checks (orchestrator, read-only)

- **Post-RC path check** (`git log --name-status --format='%H %s' 4feef0f..HEAD`): two commits.
  - daca689: `A` releases/manifest/4feef0ff117e9b7b0c1f411e875950e0bde06ba4/release-manifest.json and release-manifest.md (the release candidate's own folder): allowed.
  - b049e9e: `M` README.md; `M` docs/architecture.md, docs/demo-script.md, docs/final-state.md, docs/runbook.md, docs/threat-model.md; `M` reports/final-report.md; `A` eight files and `M` two files under docs/agent-runs/phase-17/: all allowed.
  - No `D` or `R`, no `M` under releases/manifest/, no docs/agents.md, docs/incidents/**, other phases' records or releases/course/. **Pass, no exception needed.**
- **History scan** (the E12 pattern, with `{20}` as accepted, over cdd36c8..HEAD): hits in b049e9e (identifier-scan-R3.md, review-report-final-docs-R3.md, runbook-log.md) and 4feef0f (runbook-log.md). Classified by masked shape: every hit is the text `execute-api` (or other pattern names) in a description of an identifier search; key-shaped values 0, ARNs with an account 0, `sk-ant-` values 0, `pa-` values 0, AWS hosts other than the bare suffix, `ecs.amazonaws.com` and the masked shape `xxxxxxxx.s3xxxxxxx.amazonaws.com` 0. Older history: unchanged from the earlier history scans (P16-A-01 the only key-shaped hit).

## Changes to the earlier summaries' tables

| Table, item | Was | Now |
|---|---|---|
| Final gate item 1 (fresh clone, `sbt clean check`) | met on b4d8f12 | met on 4feef0f (E3 repeat); 2362 / 418 |
| Final gate item 2 (every gate, Terraform validates) | met on b4d8f12 | met on 4feef0f (E4 repeat) |
| Final gate, README commands replayed | met, tag-checkout line deferred to E14 | unchanged on 4feef0f (E5 repeat); the optional `claude` session lines cite the first E5 session |
| Final gate, release manifest | releases/manifest/b4d8f12…/ | releases/manifest/4feef0ff117e9b7b0c1f411e875950e0bde06ba4/ (first folder kept as history) |
| D06 Hadoop package | met | met; B1 fixed in code (counting pre-passes J05p/J05bp, oversize re-keying), "fixed in code, tested locally, not validated live on AWS; the recorded experiment results were produced by the earlier code"; hadoop tests 124 of 124 (was 114) |
| D05 Dataset package | met | met; N4 records added (Crossref snapshot date, observed sample size, projection schema versions, ORCID types, "not recorded" reasons); validate.sh PASS (the human) |
| D17 Documentation package | met | met; docs/hadoop-job-contracts.md 5.1, 6.1, 13.1 (fixed), 13.2 (deliberate bound); all docs on 4feef0f |
| D19 Demonstration video | not met yet (blocked-on-human) | unchanged: the three `PENDING` marks at README.md:400, :456 and docs/demo-script.md:242 |
| Identifier scans, decision 16 | scan 3 pending | identifier-scan-R3.md done (no unexpected match); scan 3 pending (gate files); every R3 commit had its own section-20 scan step first, run by the human |
| Open minor P17-M-04 | open (not repairable after the RC) | closed before the new RC (dc57e5d) |

## Autograder results (data from the human's runs; no key text)

| Run | Commit | Result |
|---|---|---|
| 3 | 62de5e5 (working tree held the then-uncommitted N1-N5 text; inferred from the judges' citations) | V1 1.0.8: K01 FAIL -> warn, K02 -> pass, E01 -> warn (phase-00 only); V2 pre-scan cleared, SPARK-09 met; V4 4.0.0: 8.78 of 9.0 gained, 1.00 deducted (N1 0.50, B3 0.50), 7.78; pending X1 (E03 claims), X2, X3 |

R3 answers B1 (fixed), B3 (kept as a deliberate safety bound, with the analysis), N4 (fixed in records), X1 (53 rows: a 1, b 33, c 0, d 0, e 19), X4, and the N1 disclosure correction (59 Bash, 2 Read, 1 Write, checked by the human), all in autograder-response.md. A fourth run, on the new release candidate, is the human's before the gate.

## Finding table additions (R3)

| ID | Severity | Owner | Status |
|---|---|---|---|
| test-report-R3: `largestReduceGroup` over the cap with many map tasks | production defect | hadoop-engineer | RESOLVED (repair-hadoop-R3.md; 418 of 418) |
| Part B minors 1, 4, 5, 6 | minor | hadoop-, dataset-, test-engineer | RESOLVED (minor repair pass, the human's authorization) |
| Part B minors 2, 3 | minor | hadoop-engineer (its own report) | open, accepted: the report is a record; the docs use the corrected wording |
| Docs review 1 (old cap at architecture.md:204, README.md:443) | major | documentation-engineer | RESOLVED |
| Docs review 2-5 | minor | documentation-engineer | RESOLVED |
| Docs re-check grammar (architecture.md:204) | minor | documentation-engineer | RESOLVED |
| Minor re-check: citation drift (+2 lines) | minor | documentation-engineer | RESOLVED |
| Citation re-check :284 | major | documentation-engineer | RESOLVED |
| Citation re-check defaults.xml ranges | minor | documentation-engineer | RESOLVED |
| :107 bare citation | polish | documentation-engineer | RESOLVED (the human's decision) |
| P17-M-04 and its docs follow-up | minor | infra-engineer, documentation-engineer | RESOLVED |
| Final-2 minors (a) docs clause, (b) scripts/README.md:5, (c) release-manifest.sh header and help | minor | documentation-engineer, infra-engineer | RESOLVED (text only; shellcheck clean; no probe depends on the text) |
| E11 repeat minors 1, 2 (README.md:9) | minor | documentation-engineer | RESOLVED |

## Known limitations (changes)

- **B1 (HADOOP-04):** no longer a known limitation: fixed in code (docs/hadoop-job-contracts.md 13.1). Stated everywhere with the status line "fixed in code, tested locally, not validated live on AWS; the recorded experiment results were produced by the earlier code".
- **B3 (HADOOP-PAIRS-WRONG):** a deliberate safety bound, not a defect (human decision D, condition 4): one record within the 1 MiB limit could list about 30,000 to 52,000 authors (about 0.45 to 1.35 billion pairs); the 5,000 limit bounds one record to about 12.5 million pairs; oversize teams are counted and reported; largest real team 137; volumes are labelled estimates (docs/hadoop-job-contracts.md 13.2).
- **N4 (DATA-02, DATA-05):** records added; values with no committed source stay "not recorded" with the reason (reports/final-report.md:615).
- **A16, N5, P17-L-02 to L-04, P17-K-01, P17-F-01, hook shellcheck findings:** unchanged, accepted (the human, pre-RC list items 3 to 7).

## Deviations added (unplanned unless stated, for the human to accept by name in `human-gate.md`)

19. **Repair round R3 after the release candidate** (planned by the human's plan change, instructor-approved in writing on four conditions): a code change after b4d8f12, an exception to the session-3 code freeze (prompt.md:125); new release candidate 4feef0f; E2 to E5, E10, E11 and E12 repeated. Each condition: (1) owning agents, tested and reviewed: met; (2) each change documented with rationale and finding: autograder-response.md "Repair round R3"; (3) relevant tests re-run and the whole project verified from a fresh clone: full `sbt check` and the E3-E5 repeat; (4) defects kept apart from false positives: the classification above.
20. **The first release candidate superseded:** b4d8f12 and its manifest folder (c6bbe14) stay committed as history; the documents present them as superseded.
21. **Agent commands outside their briefs** (all read-only, nothing written outside the scratchpad): dataset-engineer `python3 -I` readers and `ruby -ryaml` on its own scratchpad scripts; hadoop-engineer one `python3 --version` and `kill` on two of its own wait loops; test-engineer a read-only `find` that printed three directory names under evidence/archives/ (no file opened), `awk`, `sed -n`; documentation-engineer `cd` prefixes, `sed -n`, `awk`, `cat`; guard blocks (not retried as variants): a variable `sed -n` range and two shell-variable write targets (documentation-engineer once, the orchestrator once), each rerun with a literal path where needed.
22. **Orchestrator commands outside its list** (read-only, nothing written outside the scratchpad): command notes 2 to 6 (`sort`, `uniq -c`, `cd` twice, `comm`, `cat`, `sed`, `perl -pe`, `xargs echo`, `sed -n` once on the spec); one dry-run `grep -no` printed the all-digit release-record short commit in the orchestrator's own tool output (a hash, written nowhere).
23. **Run of twelve zeros in a draft:** the orchestrator's merged X1 row 30 copied a fixture folder name; caught before any copy; the original Part A report is not committed; the redacted copy is.
24. **Subagent count correction:** the N1 disclosure first said 60/3/1; the human's check found 59 Bash, 2 Read, 1 Write (duplicate transcript entries); corrected in autograder-response.md and the log.
25. **Ordering slip:** the orchestrator ran a docs pass before a test edit, so citations drifted by two lines; repaired and re-checked.
26. **Commit steps:** one step-1b attempt ran with nothing staged (no effect; the human then ran 1a and 1b); the human once pasted masked scan output (pattern names only); the orchestrator's own forbidden-path filter matched review-report-R3-partA.md by mistake (a false positive; the unredacted report was not staged); the orchestrator's E4 probe-count pattern did not fit the log format (it read the PROBE lines itself: 8 of 8 passed).
27. **E5 repeat:** the optional recorded `claude` session was not repeated on 4feef0f; its README lines cite the first E5 session, and the documents say so.

**Approval prompts and guard blocks since summary 2:** the guard blocks in items 21 and 22 (expected; no retry of variants). No AWS, model-provider or network command by any agent.

## Costs

Unchanged from orchestrator-summary.md: project total $21.46 of the $25 bound. No AWS or model-provider call in R3 or the repeats; the autograder runs are the human's, outside the project budget.

## Manual actions for the human (in order)

1. Copy this summary and the log: the `cp` lines in the orchestrator's message.
2. Re-run the autograder (in progress: a fresh clone at b049e9e). Send the result paths; the orchestrator reads them as data only and reports any new finding before the gate.
3. D19 (decided: the video link goes in the README): send the link once uploaded; then E11's fill-in for the three `PENDING` marks, code-reviewer's check, a commit, and E12 repeats as orchestrator-summary-4.md.
4. Write `docs/agent-runs/phase-17/human-gate.md` (what you reviewed, accepted or rejected, every manual change, every deviation accepted by name from all three summaries, the instructor approvals, remaining risks, the accepted release candidate SHA 4feef0ff117e9b7b0c1f411e875950e0bde06ba4) and `commit.txt` (`git log --reverse --format='%H %s' 50ba087..HEAD`).
5. The orchestrator runs identifier-scan-3.md over every file created or changed after identifier-scan-R3.md; an unexpected match blocks the commit.
6. The section 20 scans, then the gate commit as a separate step (no Co-Authored-By line). Then E13 (tag `v1.0-submission` on the gate commit, by Nov 29, 8 pm) and E14.
