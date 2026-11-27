# Phase 17 human gate: documentation, final review and release

## Gate

**ACCEPT, with open items. Phase result: READY_TO_TAG_WITH_OPEN_ITEMS** (orchestrator-summary.md, -2.md, -3.md, -4.md and -5.md). Every `deviation` line in those summaries is accepted by name below, which is the E13 precondition.

Phase 17 turned ScholarChain into the course submission:
- session 2 (unattended) wrote the L2 fail-closed rule and its repair, the release-manifest script, the script and test minors, the incident report and every documentation draft, with their reviews and one repair round (d8b23f2);
- session 3 (attended) ran E0 to E12: my decisions on session 2's findings and the E1 repair, the first release candidate b4d8f12 (fresh-clone `sbt clean check`, `check.sh`, the README replay, the release manifest), the read-only AWS checks, the cost reconciliation, the removal of the AWS leftovers, the final documentation check, and the E12 readiness checks;
- after the first E12 summary I ran the TA autograder four times. With my instructor's approval (in person, then in writing) I answered every finding in docs/agent-runs/phase-17/autograder-response.md and authorized repair round R3, which fixed B1 in code and N4 in the dataset records. R3 changed `check.sh` inputs, so a new release candidate 4feef0f was cut and E2 to E5, E10, E11 and E12 were repeated on it;
- I recorded the demonstration video, including clips 22 to 28 on Nov 26, and docs/demo-script.md was aligned with the final cut.

**Accepted release candidate: 4feef0ff117e9b7b0c1f411e875950e0bde06ba4** (recorded 2026-11-25T17:23:26Z). The first candidate, b4d8f1229fc145e1492e1a8cbb8a44a368d026af, was tested and is superseded; it and its manifest folder stay committed as history.

## Final gate (spec Phase 17)

| Item | Status | Source |
|---|---|---|
| `sbt clean check` succeeds from a clean checkout | Met (planned deviation 6: a fresh clone of the GitHub remote): 4feef0f, exit 0, 1205 s; module tests 2362 (151 suites; 5 live-AWS tests skipped), contract tests 418 of 418 | runbook-log "E3a/E3b (repeat, R3)" |
| Terraform validates; the archived plan matches the documented final state | Met (planned deviation 3): `check.sh` Terraform gate; fmt, init and validate exit 0; state count 0; `plan.sh` 175 create / 0 / 0 / 0, equal to Phase 13 and Phase 15 at count level; never signed | E4, E5.18-E5.20, E6; docs/final-state.md |
| README commands have been replayed | Met, with deviations: the README replay repeated on 4feef0f (E5, 47 lines, exit 0); the six lines first missed were replayed on b4d8f12 (E12r.1-E12r.6, exit 0), and the optional `claude` session ran at the first E5; the clip 27a `check.sh` run and the 27c chain also ran on camera on 4feef0f (exit 0); the tag-checkout line is deferred to E14; the six `exit n/a: interactive session` lines and the Phase 02 agent-session lines are accepted below (unplanned 1, 5) | E5 repeat; E12r rows |
| The bounded pipeline can be rerun | Met (planned deviation 2): local Hadoop pipeline E5.13 (150 s, with the R3 blocking chain); Spark fixture suites 43 of 43 (E5.07); also run on camera in clip 27c | E5 repeat; clips row |
| Query and evidence examples work | Met (planned deviation 2): on 4feef0f, services 137 passed and GoldenExamplesSuite 17 of 17; QueryApiSuite 11 of 11 on b4d8f12 | E5.10, E5.12 (repeat); E12r.2 |
| Final reviewer returns APPROVE | **Deviation, not met.** The final repository review's control slice (F-1) stays REQUEST_CHANGES for P17-F-01, which I accepted as a known limitation at E1. Every documentation check ended APPROVE | orchestrator-summary.md gate item 6 |
| Cleanup script rehearsed in a safe environment | Met (planned deviation 4): the Phase 15 real cleanup records and the `check.sh` refusal probes; `cleanup.sh --list` not run (it exits 3 at `terraform output` with no environment) | Phase 15 cleanup records; E4 |
| Release manifest is complete | Met (planned deviation 7): releases/manifest/4feef0f…/ (daca689); every checksum it lists recomputed: 51 MATCH, 18 MATCH-RECORD, 0 DIFF | E10 repeat; manifest-checksums-2.txt |

## Results

- **Release candidate 4feef0f:** E3 and E4 as above; `check.sh` 9 of 9 gates, all 8 release-manifest probes passed; every README replay line exit 0; release manifest identical to its dry run except the generated time.
- **Documentation:** README.md, docs/** and reports/final-report.md complete; final documentation check on 4feef0f APPROVE after one repair (review-report-final-docs-R3.md, -R3-2.md); links 909 checked, 0 broken.
- **AWS final state (E8):** the 20 task-definition revisions deregistered (20 of 20 INACTIVE, listed by AWS at no cost); the budget `scholarchain-25` removed 2026-11-24T01:05Z; the IAM user `scholarchain-readonly` removed 01:09Z; after: ACTIVE 0, INACTIVE 20, no IAM user, no budget. I removed the local `scholarchain-readonly` profile; `aws configure list-profiles` prints only `default`.
- **Autograder (V1 1.0.8, V2 2.3.5, V3 3.0.5, V4 4.0.0, fresh clone at b049e9e):** HADOOP-04, DATA-02 and DATA-05 moved to met after R3; V4 score 8.01 of 9 (9.01 gained, 1.00 deducted for N1 and B3, both minor). My instructor addressed both deductions in writing (below). Pending instructor decisions X1 (55 "never existed" claims: 0 real mismatches), X2 and X3 are answered in autograder-response.md.
- **Symbolic links (my check for P17-F-01):** `find . -type l -not -path './.git/*' -not -path '*/target/*'` printed nothing, and `git ls-files -s | grep -c '^120000'` printed 0, at HEAD 0c4ec52 with a clean tree; and two home-path lines in repair-docs-run4.md (1823cbf), recorded by identifier scan 3.

## What I reviewed

- The work order and the specification (sha256 recorded in the runbook log); orchestrator-summary-s2.md, orchestrator-summary.md, -2.md, -3.md, -4.md and -5.md; the runbook log.
- Every finding and recommendation the orchestrator brought to me, with its report or review, before deciding.
- autograder-response.md, and the autograder's own output: the K01/K02 hits (AWS's documented example key), EmulatorWiringSuite.scala:113 and ErRepairRegressionSuite.scala:454 (made-up test strings), and infra/terraform/environments/course/course.tfvars (no secrets).
- The built-in subagent's transcript (subagents/agent-ad5d56796221bda14.jsonl): 59 Bash, 2 Read, 1 Write; every Bash call read-only; its one write was the scratchpad draft; and, at identifier scan 3, one read-only cd into the phase-17 folder and xargs, head and printf to show short match prefixes.
- The two history-scan hits I had to inspect locally: an AWS service principal quoted from AWS documentation, and a made-up test host.
- The staged file list and the identifier scan before every commit, and the scan output itself where a filter could hide a line.
- README.md, reports/final-report.md (lessons learned, authorship, costs, instructor-approved deviations) and docs/demo-script.md.
- Every AWS console clip of the video, including panel contents, for identifiers.

## What happened

- **No session 1:** no constitutional file changed in Phase 17.
- **Session 2** (unattended) ended NOT_READY with its findings; I decided each at E1 (P17-A-01 repaired with an allow-list rule; P17-F-01 accepted as a known limitation; items 3 to 10 as recommended).
- **Session 3, first candidate (Nov 22-24):** E0 to E12 on b4d8f12. At E8 the orchestrator's account-check line used `--family-prefix scholarchain`, which matches only full family names; it found nothing, so its 0 counts and the "already absent" reading were wrong. I checked CloudTrail and the family list: nothing had been deleted. The reading was withdrawn, and the step was redone with exact names before any removal. The E6 account check was re-established through the plan record's own account fingerprint.
- **Autograder rounds (Nov 24-25):** documentation fixes and the response file (62de5e5, cdd36c8). The repository was made public on Nov 24, with my instructor's permission, after the history scan.
- **Repair round R3 (Nov 24-25):** with my instructor's written approval and its four conditions: B1 fixed in code (counting pre-passes J05p and J05bp; no reduce group exceeds the cap), N4 fixed in the dataset records, B3 kept as a deliberate safety bound. test-engineer's adversarial tests found one production defect (`largestReduceGroup` counted per-map-task markers), which hadoop-engineer repaired. I authorized one minor repair pass before the new candidate, so that no code-only minor stayed open. Six commits, one per owner, then the new release candidate and the repeated E2 to E12.
- **Video (Nov 25-27):** docs/demo-script.md was first aligned with my video plan (5b911d9, Nov 25); I recorded clips 22 to 28 on Nov 26; the script was then aligned with the final cut (0c4ec52, Nov 27).
- **Approval prompts:** the MCP server approval at the recorded session's first start (I disabled github). The guard blocked a few commands (listed in the summaries); each was redone in a permitted form or not retried.
- **Other Claude Code sessions:** besides this phase's orchestrator session, I used separate read-only Claude Code sessions to look up facts for the video script. They made no edits, no commits and no subagent calls (one wrote command output to a scratch folder under /tmp). The other recent sessions in this project folder are the TA autograder's judge runs.

## Decisions

1. **E1:** P17-A-01 repaired with an allow-list rule for absence claims; P17-F-01 accepted as a known limitation, documented in the threat model and the final report, no amendment; items 3 to 10 as recommended.
2. **Autograder:** every finding answered in autograder-response.md; correct code is never changed to satisfy an incorrect check (my instructor's condition 4). A2: the three commit.txt files stay unchanged (the flagged strings are plan-checksum prefixes in commit subjects). X1 category (e) kept. Not defects, no change: A1-A3, A7-A17, X2, X3.
3. **R3:** B1 and N4 genuine defects, fixed. B3 kept as a deliberate safety bound (option D): the cap bounds one record to about 12.5 million pairs, oversized teams are detected, counted and reported, and the largest real team was 137.
4. **Pre-release-candidate list:** I ran datasets/scripts/validate.sh (RESULT: PASS); P17-M-04 and its follow-up minors fixed (scripts/README.md, the release-manifest.sh comment and help text, the docs clause); items 3 to 7 unchanged as accepted.
5. **D19, the video: met.** The video was published (unlisted) before the tag, and its link is in README.md (Final report and video; D19), docs/demo-script.md and reports/final-report.md, and in my course submission. This replaces my earlier plan to keep the link out of the repository. I tag `v1.0-submission` by Nov 29, 8 PM CST, before the Nov 30, 8 PM CST deadline; nothing is committed after the tag.
6. **Commits:** a scan step before every commit, as its own step; no Co-Authored-By line in any commit.
7. **The baseline item "DBLP plus at least three additional datasets … in the manifest and actual bounded pipeline"** (left to this gate by reports/final-report.md, section 23.1): met through the manifest and the local bounded pipeline, which runs the real DBLP sample and the OpenAlex, Crossref and ORCID projections; the Phase 14 and 15 EMR runs read DBLP only, and the topic and institution tables are empty, as section 23.1 states.
8. **Phase 15's $9 bound** (left to this gate by reports/final-report.md, section 20): not determinable from the per-day figures. AWS usage was $9.83 on Nov 20 and $2.73 on Nov 21, $12.56 together, but those days also include the environment's hours outside Phase 15 work, and Phase 14's gate read $9.92 for the earlier days. If all of Nov 20 and 21 is counted as Phase 15, it exceeded its bound; the project as a whole stayed within the $25 ceiling.

## Deviations accepted

**Planned (decision 17), used:** 1, 2, 3, 4, 5, 6 and 7, as the summaries' gate table states.

**Unplanned, accepted by name** (numbers as in the summaries):
1. Gate item 3 forms: the six `exit n/a: interactive session` lines (P17-M-06) and the Phase 02 agent-session README lines recorded as `not replayed in phase 17`.
2. E6 step 1: the account-check line with `--family-prefix` printed the fingerprint of "None"; reclassified unverified and re-established through the plan record's account fingerprint; the comparison source is the Phase 15 cleanup record, not a release record.
3. E6: my hashed `aws sts get-caller-identity` line (shell history 1117); its output went only into the hash, and the account was never printed.
4. E8: the "already absent" reading withdrawn; the work order text at prompt.md:346 left unchanged by my decision. That work order line (E8 item 3) itself carried the `--family-prefix scholarchain` error; the runbook log records the correction.
5. E11: the six `exit n/a` lines (as item 1); P17-M-04 left open then (closed later in R3).
6. E12 history scan run with `pa-[A-Za-z0-9_-]{20}` instead of `{20,}` (the guard blocked the comma); same matches.
7. The narrower E11 step-2 scan line, and identifier-scan-2.md's count of "1" for the record prefix (2 with its own row).
8. Commands outside the lists (read-only, nothing written outside the scratchpad), as listed in orchestrator-summary.md.
9. Verified limits: 6 of 60 rows unverified (IL-02, IL-04, IL-06, IL-11, IL-14, SL-29).
10. Session 2's unplanned deviations (orchestrator-summary-s2.md).
11. The post-RC autograder documentation commits 62de5e5 and cdd36c8, approved by my instructor and by me.
12. My `M` changes to docs/agent-runs/instructor-approvals.md, a human-owned record outside docs/agent-runs/phase-17/: my approved exception to the rule line, for every commit that changed it, including the edited "Earlier approvals" heading (intended).
13. Claude Code's built-in general-purpose subagent, used once to draft autograder-response.md: read-only in practice (59 Bash, 2 Read, 1 Write, the scratchpad draft), under the guard, reviewed by code-reviewer, disclosed in README.md and autograder-response.md. My instructor called it acceptable, with no further action needed. Only the 18 repository agents were used afterwards.
14. The step line that joined a scan and a commit with `;`: I split it, and scans and commits have been separate steps since.
15. The two A14 guard probes (`echo` lines with destructive AWS words), run at my instruction; both blocked, nothing ran.
16. Commands outside the lists (read-only), as listed in orchestrator-summary-2.md.
17. The misnamed sfn.tf folder in the F7/F8 brief; the agent kept the correct path and did not read the course environment folder.
18. Recording the instructor approvals in instructor-approvals.md instead of only in this gate.
19. Repair round R3 after the first release candidate (a code change, an exception to the session-3 code freeze), on my instructor's four written conditions, each met as orchestrator-summary-3.md shows; new release candidate 4feef0f, with E2 to E5, E10, E11 and E12 repeated.
20. The first release candidate b4d8f12 and its manifest folder kept as superseded history.
21. Agent commands outside their briefs (read-only), including test-engineer's `find` that printed three folder names under evidence/archives/ (no file opened).
22. Orchestrator commands outside its list (read-only).
23. The run of twelve zeros in a draft, caught before any copy; only the redacted Part A report is committed.
24. The subagent count correction (60/3/1 to 59/2/1, after my check).
25. The citation drift caused by the docs pass running before a test edit; repaired and re-checked.
26. The commit-step slips: one commit attempt with nothing staged (no effect), one pasted scan output with pattern names only, the orchestrator's own forbidden-path false positive, and its E4 probe-count pattern.
27. The optional recorded `claude` session not repeated on 4feef0f; its README lines cite the first E5 session.
28. My further edits of instructor-approvals.md (the N1 and B3 decisions in 1823cbf; the deadline-extension entry in 5b911d9), the same approved exception as item 12.
29. The orchestrator's withdrawn suggestions to fill the video link in after the tag; withdrawn by my video plan, and every document reworded.
30. The video wording that rested on my earlier plan ("not published at tag time"); superseded: the video was published before the tag and its link is in the documents (orchestrator-summary-5.md).
31. The 421 s attribution for clip 21 was left unconfirmed in the records; I confirm it is clip 21 part 3 alone (docs/demo-script.md keeps its neutral wording).
32. Agent commands outside their briefs since summary 3 (read-only, nothing written outside the scratchpad), as listed in orchestrator-summary-4.md.
33. Orchestrator slips since summary 3 (read-only, nothing written in the repository): a failed scratchpad Edit fixed seconds later; one long transcript grep stopped; its own tool output showing my home-path prefix and e-mail domain (already public text, written nowhere).
34. Home-path text in three committed agent reports (5b911d9), the same class as earlier committed records; the deliverables (README.md, reports/**, docs/**) have none.
35. No report file for the slide fact check, at my instruction; the answer was logged as a runbook row.
36. video-facts.md and my video edit plan not copied into the repository, at my instruction.
37. 38b5c84's commit message names the instructor-approvals.md corrections, but that file was not staged; the corrections are in the gate commit.
38. My edits of instructor-approvals.md lines 48 and 138 (the R3 supersession note and the video wording), the same approved exception as items 12 and 28.
39. My changed video plan: the video was published (unlisted) before the tag and its link is in the documents, which makes item 30 moot.
40. Agent commands outside their briefs since summary 4 (read-only, nothing written outside the scratchpad), as listed in orchestrator-summary-5.md.
41. Orchestrator slips since summary 4: one read-only `sed -n` and one guard block, nothing written in the repository.
42. diff-autograder-run5.txt not copied into the repository, because its header names the scratchpad folder.

## Instructor approvals

All in docs/agent-runs/instructor-approvals.md:
- 2026-11-09: Anthropic API synthesis and Voyage AI embeddings instead of Bedrock (Bedrock quota 0), with external charges counted toward the $25 limit.
- 2026-11-24: fixes for autograder findings may be committed as part of Phase 17 (in person; that entry's "How it is applied" line describes the first, documentation-only round, and the written confirmation below supersedes it for the R3 code changes).
- 2026-11-24: the repository may be made public at any time (made public on Nov 24).
- 2026-11-24: written confirmation of code and documentation fixes in Phase 17, on four conditions.
- 2026-11-24: the deadline extension: the ScholarChain submission deadline is Nov 30, 2026, 8 PM CST; only the last commit before it is graded; the repository link is submitted through the Teams assignment. My tag deadline of Nov 29, 8 PM CST is my own buffer.
- 2026-11-25: the built-in subagent use is acceptable (N1).
- 2026-11-25: the 5,000-author cap may be kept, with the conditions documented (B3).
- Earlier: Scala 2.13 for sparkAnalytics; the $25 ceiling; bounded enrichment fetch; the Phase 15 teardown approval (Phase 15 gate decision 6).

## Known limitations and recorded minors

- **L2:** the absence rule applies only when a claim gives both researchers' full names and contains a listed word; hyphenated list words (P17-L-02), single-name false negatives (P17-L-03), surname-only references (P17-L-04) and pronouns are not caught; P17-K-01. Fixed in code, tested offline, not validated live.
- **P17-F-01** (constitutional; guard symlink gap): accepted; my symlink check above found none in the repository.
- The hook shellcheck findings; the archive E-03 branch has not run (it needs a known account).
- **A16:** Spark `sizeBuckets` (Pipeline.scala:1116) grows with the input (at most about √(2N) rows); 6 other collects are capped only by config values without an upper check.
- **N5:** Spark-precomputed path evidence and the effective-diameter estimate cover the full window only; live path questions work in every window.
- **N4:** values with no committed source stay "not recorded", with the reason.
- **B3:** the 5,000-author cap (a deliberate bound; see Decisions).
- Open minors I accepted, as listed in the summaries' finding tables: among them F-7 (demo-script headings kept for stable anchors), E12 m-1, the final-report:621 pronoun, old README line numbers in some log row titles, hadoop-engineer's own report wording (Part B minors 2, 3), and experiments/results-summary.md:446, which says 15 s where the raw samples are about 19 s apart (file unchanged; noted in demo-script item 20); and F1 and F2 of the final docs pass (reports/final-report.md section 6.1: the deadline-extension sentence cites no source; the built-in subagent sentence does not say the orchestrator fixed the draft after review).
- Claude Code updated itself to 2.1.331 after session 2; the recorded Phase 17 version stays 2.1.330.

## Manual changes

No code or documentation changes by hand. All code and documents are agent-written and reviewed. By hand, I:
- wrote my human-owned records: approvals.md (E0 cost bound), my entries in instructor-approvals.md, this gate and commit.txt;
- ran every step command: the fresh clones, `sbt clean check`, `check.sh`, the README replays, `plan.sh`, `release-manifest.sh`, `datasets/scripts/validate.sh`, the history-scan inspections and the autograder runs;
- made the E8 AWS changes: deregistered the 20 approved task-definition revisions (one CLI call per approved name), and removed the budget and the IAM user (access key, policy attachment, user) in the console;
- removed the local `scholarchain-readonly` profile from my AWS configuration files;
- copied the reports and logs into the repository, ran the scans and made every commit;
- recorded and edited the demonstration video.

## Costs

| Provider | Amount |
|---|---|
| AWS, November (Nov 18 $3.05, Nov 19 $5.61, Nov 20 $9.83, Nov 21 $2.73; Cost Explorer by charge type as of 2026-11-24T00:36Z, subject to billing lag) | $21.22, covered by AWS promotional credits |
| Anthropic | $0.24 |
| Voyage | $0.00 |
| **Project total** | **$21.46 of $25** (usage before credits) |
| Phase 17 | $0: no AWS call, no model-provider call from ScholarChain code; the E6 to E8 reads and removals were my own commands |

Claude Code usage, including the autograder's judge runs, is billed to my Claude Code account and is outside the project budget. The budget's alerts (10, 15, 20) never fired, because actual cost after credits stayed near $0, and their email address was verified only during E8, so alert delivery was never tested.

## Deferred

Nothing is deferred to a later phase: Phase 17 is the last phase. The proposed-later items in the summaries and reports stay unscheduled.

## Remaining risks

- No Phase 16 or Phase 17 code fix is validated live: FECD-1, L2 and B1 are fixed in code and tested locally only. The recorded experiment results were produced by the code before R3.
- A live answer could still mark an absence claim Verified in the L2 cases listed above.
- P17-F-01 stays open in the guard; the repository holds no symbolic link today.
- Six service limits are unverified.
- The autograder's pending instructor decisions (X1, X2, X3) and the two minor deductions (N1, B3) rest on my instructor's review.
- The repository is public. Its history holds AWS's documented example key (P16-A-01) and cannot be rewritten; every scan found no real identifier.
- The local evidence (the /tmp logs, ~/scholarchain-evidence-p15, -p16 and -p17) holds identifiers and stays private and outside Git.

## Acceptance

No constitutional change in Phase 17, so there is nothing to ratify.

I accept Phase 17 as of the 20 commits listed in `commit.txt`, from `d8b23f24ef55fdd0fc602dc3fe17d07ecacd8eb2` to `38b5c84f027d140e92c07a1dce0e28bfd0f4b4d8`, with release candidate `4feef0ff117e9b7b0c1f411e875950e0bde06ba4`.
