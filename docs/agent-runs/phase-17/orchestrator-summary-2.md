PHASE RESULT: READY_TO_TAG_WITH_OPEN_ITEMS

Rule 3 applies. Rule 1 does not: the run reached E12. Rule 2 does not: every final-gate item and every tier 1 requirement is met or `deviation`; no critical finding is open; every final check passed, including this repeat's post-RC path check (with the human's approved exception) and history scan. **The `deviation` lines in orchestrator-summary.md and below count as accepted only once the human accepts each by name in `human-gate.md`** (the E13 precondition). Open items: D19 (video link, blocked-on-human), accepted deviations (planned and unplanned), known limitations, recorded minor findings, and E13/E14.

# Phase 17 session 3 summary 2 (orchestrator, E12 repeat)

- **Why a repeat:** after the first E12 summary, the instructor-approved autograder follow-up added two documentation-only commits, and the repository was made public. The video link is not in yet, so D19 stays open. This repeat runs without it, as the human allowed.
- **Scope:** this file records only what changed since orchestrator-summary.md. Every table and line there still holds unless this file says otherwise.
- **Work order sha256:** c22adfc57c7fde0cae63c8520393c2c81cd12280df7f5df773e70c2e2ec8ac24 (unchanged).
- **Release candidate:** b4d8f1229fc145e1492e1a8cbb8a44a368d026af (unchanged; no code, script, test, hook or Terraform change after it).
- **HEAD at this summary:** cdd36c818b1d5f99ddbcda2ae78b6a663bc616d3. The gate commit (this summary, `human-gate.md`, `commit.txt`, the log) follows; it is the commit to tag.
- **Detail:** `runbook-log.md`, rows from "E12 gate timing" on.

## Steps since the first summary

| Step | Status | Evidence (runbook-log row; report) |
|---|---|---|
| Instructor approval for autograder fixes | recorded | RL "E12 autograder work, instructor approval"; docs/agent-runs/instructor-approvals.md (the human's two entries and the instructor's written reply) |
| Autograder run 1 (on d8b49fa) | recorded | RL "E12 autograder results"; V1 32 pass, 8 fail, 9 warn; V2 hadoop_mapreduce 10 met, 2 partial, 1 minor deduction; V3 factory_reproducibility 9 met, 1 partial |
| A2 investigation, A16/A17 fact check, A14 guard probes | done | RL "E12 A2 investigation", "E12 A16/A17 fact check", "E12 A14 guard probes" (both blocked); review-report-A16-A17.md |
| Repository visibility | recorded | RL "Repository visibility": public since 2026-11-24, with the instructor's permission |
| Autograder Markdown pass and response draft | done | agent-report-docs-autograder.md; autograder-response.md; review-report-autograder.md (REQUEST_CHANGES, fixed); F7/F8 pass agent-report-docs-autograder-2.md; review-report-autograder-2.md (**APPROVE**) |
| Commit 1 | done | 62de5e5942137510a21587693600e6008ffa3354 |
| Autograder run 2 (on 62de5e5) | recorded | RL "E12 autograder second run (human)"; improved HADOOP-11 and FACTORY-07 to met; new N1-N5 |
| Second-run pass | done | agent-report-docs-autograder-3.md; review-report-autograder-3.md (REQUEST_CHANGES: 3 major, 2 minor); repair-autograder-docs-3.md; review-report-autograder-4.md (**APPROVE**) |
| Built-in subagent disclosure | done | RL "E12 built-in subagent disclosure"; autograder-response.md "Built-in subagent disclosure"; README.md:90 |
| Commit 2 | done | cdd36c818b1d5f99ddbcda2ae78b6a663bc616d3 |
| E12 repeat: post-RC path check | done (pass, one approved exception) | RL "E12 repeat: post-RC path check": c6bbe14, ade85aa, d8b49fa, 62de5e5, cdd36c8; only `A`/`M`; the two `M` on docs/agent-runs/instructor-approvals.md are the human's approved exception |
| E12 repeat: history scan | done (expected matches only) | RL "E12 repeat: history scan": four report lines that name search patterns; no key-shaped text |
| E12 repeat summary | this file | – |
| Identifier scan 3, human gate, `commit.txt`, gate commit | pending (the human) | – |
| E13 tag | pending | precondition: this result line, `human-gate.md` accepting the phase and every listed deviation by name; deadline Wednesday 21:00 CST |
| E14 publication check | pending | records `VISIBILITY: public since 2026-11-24` (not a change at the deadline) |

## Changes to the first summary's tables

| Table, item | Was | Now |
|---|---|---|
| Final gate, every item | as listed | unchanged; the autograder commits changed documentation and phase records only |
| D01 Git repository | tag and public access pending | met for source and history; **public since 2026-11-24** (instructor's permission); tag pending (E13) |
| D06 Hadoop package | met | met; per-job contracts added in docs/hadoop-job-contracts.md (autograder B2; HADOOP-11 met on run 2) |
| D17 Documentation package | met | met; autograder rationale notes in docs/architecture.md section 7, docs/emulator.md:50-56, docs/service-limits.md:120-121, docs/evaluation.md:566 and :754, README.md:90 |
| D18 Final report | met | met; section 23.1 adds the autograder known limitations (below) |
| D19 Demonstration video | not met yet (blocked-on-human) | unchanged: the three video marks are now at README.md:400 and :456 (moved two lines by the README.md:90-91 insert) and docs/demo-script.md:242 |
| Logistics, Repository URL | private until the deadline | public since 2026-11-24; E14 records it |
| Logistics, Instructor-approved deviations | instructor-approvals.md; final report 1.1 | also the autograder-fix approval and the visibility approval, in instructor-approvals.md (the human's entries) |
| Identifier scans, decision 16 | scan 3 pending | scan 3 pending; every commit since the first summary had the section 20 scans first, run by the human (RL "E12 autograder commit scans", "E12 second-run commit scans") |

## Autograder results (data from the human's runs; no key text)

| Run | Commit | V1 | V2 | V3 |
|---|---|---|---|---|
| 1 | d8b49fa | 32 pass, 8 fail, 9 warn | hadoop_mapreduce 10 met, 2 partial, 1 minor deduction; dataset_engineering and spark_analytics all met | factory_reproducibility 9 met, 1 partial (FACTORY-07) |
| 2 | 62de5e5 | unchanged, as expected | HADOOP-11 partial -> met; DATA-02, DATA-05 met -> partial; SPARK-09 met -> partial; HADOOP-04 still partial; HADOOP-PAIRS-WRONG still minor | FACTORY-07 partial -> met; new minor deduction FACTORY-AGENTS-NOT-SUBMITTED |

Every row has an answer in docs/agent-runs/phase-17/autograder-response.md (A1-A17, B1-B3, C1, N1-N5). The V1 rows that remain are answered there and left unchanged by human decision (other phases' records, history, constitution and code are frozen after the RC).

## Finding table additions

| ID | Severity | Owner | Status |
|---|---|---|---|
| Autograder review F1 | major | orchestrator (draft) | RESOLVED (A6 human review recorded) |
| Autograder review F2-F5 | minor | orchestrator (draft) | RESOLVED |
| Autograder review F7, F8 | minor | documentation-engineer | RESOLVED (human asked for the fix) |
| Re-check N1, N2 | minor | orchestrator (draft) | RESOLVED |
| Second-run review 1 | major | documentation-engineer | RESOLVED (final-report:621 scoped to Spark-precomputed path evidence) |
| Second-run review 2, 3 | major | orchestrator (draft) | RESOLVED (guard matcher stated; line numbers updated) |
| Second-run review 4 | minor | orchestrator (log) | RESOLVED |
| Second-run review 5 | minor | documentation-engineer | open, accepted by the human: log row titles quoted in docs keep old README line numbers |
| Second-run re-check minor | minor | documentation-engineer | open, accepted by the human: final-report:621 "them" could be read two ways |

## Known limitations added (accepted by the human in the autograder decisions)

- B1 (HADOOP-04): the block-size cap is applied in the reducer; an oversize block still reaches one reducer (streamed). docs/hadoop-job-contracts.md 13.1; reports/final-report.md:618; docs/service-limits.md:120.
- B3 (HADOOP-PAIRS-WRONG): teams over the hard limit (default 5000) are rejected with no pairs (counted). docs/hadoop-job-contracts.md 13.2; reports/final-report.md:619; docs/service-limits.md:121.
- A16: Spark `sizeBuckets` (Pipeline.scala:1116) collects at most about √(2N) rows and grows with the input; 6 other collects are capped only by config values without an upper check. reports/final-report.md:620; docs/architecture.md 7.2.
- N4 (DATA-02, DATA-05): dataset records incomplete for the enrichment sources (Crossref schema version, snapshot date, observed size; OpenAlex and Crossref schema versions; ORCID types). datasets/** unchanged. reports/final-report.md:615.
- N5 (SPARK-09): Spark-precomputed path evidence and the effective-diameter estimate cover the full window only; runtime path questions in other windows use the bounded live path search. reports/final-report.md:621.
- A2, A3, A7, A8, A9, A10, A12, A13, A14: checker readings of frozen records, history or the constitution; answered in autograder-response.md, no change.

## Deviations added (unplanned, for the human to accept by name in `human-gate.md`)

11. Post-RC autograder follow-up: two documentation-only commits (62de5e5, cdd36c8) after the first E12 summary, approved by the instructor (in person, then in writing) and by the human; every path within the rule line except item 12.
12. The two `M` changes to docs/agent-runs/instructor-approvals.md (a human-owned record outside docs/agent-runs/phase-17/), the human's approved exception to the rule line; the first also edited one existing heading (intended, the human's confirmation).
13. Claude Code's built-in general-purpose subagent used once (E12, to draft autograder-response.md): full default tool set; instructed read-only; wrote only the scratchpad draft; its 60 Bash calls were read-only (no write, git write, network or AWS command; RL "E12 built-in subagent disclosure"); every one of its calls went through the guard; the stop hook does not exempt it; reviewed by code-reviewer. Disclosed in autograder-response.md and README.md:90. Only the 18 repository agents since.
14. The orchestrator's step-3 line for commit 62de5e5 joined the scan and the commit with `;`; the human split it and ran the commit only after a clean scan. Since then, scans and commits are separate steps.
15. The A14 guard probes: two `echo` lines with destructive AWS words, run at the human's explicit instruction; both blocked, nothing ran (RL "E12 A14 guard probes").
16. Commands outside the lists (all read-only, nothing written outside the scratchpad): orchestrator `sort`/`uniq` (command note 2) and one `cd` (command note 3); documentation-engineer `sed -n` in the F7/F8 pass and once in the second-run pass, against the brief.
17. The orchestrator's F7/F8 brief misnamed the folder of sfn.tf (environments/course); the agent kept the path already cited (modules/workflow) and did not read the course environment folder.
18. The first plan to record the instructor approval only in `human-gate.md` was replaced by the human's entries in instructor-approvals.md.

**Approval prompts and guard blocks since the first summary:** none new, other than the two A14 probes (expected blocks).

## Costs

Unchanged from orchestrator-summary.md: project total $21.46 of the $25 bound. No AWS or model-provider call in this repeat; the autograder runs were the human's, outside the project budget.

## Manual actions for the human (in order)

1. Copy this summary and the log: the `cp` lines in the orchestrator's message.
2. Write `docs/agent-runs/phase-17/human-gate.md` (what you reviewed, accepted or rejected, every manual change, every deviation accepted by name, from both summaries, the instructor approvals, remaining risks, the accepted release candidate SHA) and `commit.txt` (`git log --reverse --format='%H %s' 50ba087..HEAD`).
3. The orchestrator runs identifier-scan-3.md over every file created or changed after E11's scan; an unexpected match blocks the commit.
4. The section 20 scans, then the gate commit as a separate step (no Co-Authored-By line). Then E13 (tag `v1.0-submission` on the gate commit, by Wednesday 21:00 CST) and E14.
5. If the video link exists before the tag: E11's fill-in for the three `PENDING` marks, code-reviewer's check, a commit, and E12 repeats (orchestrator-summary-3.md). If not, D19 is accepted as a deviation in `human-gate.md`.
