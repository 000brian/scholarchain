# Review report: Phase 17 session 3, step E12, replay-line fill-in (focused read-only check)

I found no critical or major problems. The six replays are filled in correctly, each matches its runbook-log row, and nothing else regressed. There are two minor findings, both owned by documentation-engineer; under the repair limits they get no repair round.

Inputs read: `diff-E12-docs.txt` (whole diff: 19 lines changed in 4 files), `agent-report-docs-E12.md`, `runbook-log.md` (rows 63-83), `docs/agent-runs/phase-17/prompt.md` (lines 37-45, 213, 353), the current README.md, docs/runbook.md, docs/demo-script.md and reports/final-report.md at the lines named below, and `scripts/archive-evidence.sh:530-619` plus a grep of `SC_ACCOUNT_ID` / `LOCAL_ACCOUNT` in `scripts/`. I ran nothing and read nothing from the forbidden paths.

## Per-check results

| # | Check | File:line | Result |
|---|---|---|---|
| 1a | `claude --version` | README.md:110 | Pass. Replay line is `replayed in phase 17 (E12, exit 0); evidence: runbook-log row "E12r.1 ..."`, printed `2.1.331 (Claude Code)`. Matches log row 77 (exit=0, output 2.1.331, clone at RC). One replay line. |
| 1b | QueryApiSuite | README.md:276 | Pass. Shows 11/11. Matches log row 78 (Total 11, Failed 0, exit=0). One replay line. |
| 1c | QueryApiSuite (demo copy) | docs/demo-script.md:159 | Pass. Same text as README:276, as the legend at demo-script:9 requires. |
| 1d | AlgorithmsSuite | README.md:278 | Pass. Shows 6/6. Matches log row 79. |
| 1e | JsonlExportSuite | README.md:279 | Pass. Shows 14/14. Matches log row 80. |
| 1f | JobConfigSuite | README.md:280 | Pass. Shows 3/3. Matches log row 81. |
| 1g | `archive-evidence.sh --local-only` | README.md:319, docs/runbook.md:184 | Pass. The two lines are identical. Each says `RESULT: archive-evidence.sh ok`, 170 files, and one WARN for "account unknown". Matches log row 82 (170 files, exit=0, WARN text). |
| 1h | Form and count | all of the above | Pass. Each of the six commands appears once per file (README, plus the demo-script and runbook copies), and every row has exactly one closed-form line `replayed in phase 17 (E12, exit 0); evidence: …`. The 19-line count in the writer's report matches the diff (README 11, demo 2, runbook 3, final-report 3). |
| 1i | Legends | README.md:9, docs/runbook.md:14, docs/demo-script.md:9, reports/final-report.md:389, :746 | Pass. E12 added and the counts match the log. See finding m-2 about the clone path. |
| 2 | Claude Code version | README.md:106, :110, :112 | Pass. 2.1.330 stays the recorded Phase 17 version (:106, :112 "the recorded Phase 17 version stays 2.1.330"). 2.1.331 is given only as what E11 (the human's report) and the E12 replay printed. No line says 2.1.331 was used for a recorded session; :112 keeps "the recorded session 3 `claude` session (step 4) also used 2.1.330", and README:127 and :192 still say 2.1.330. The old "must print `2.1.330`" text is gone, so there is no contradiction. final-report.md:746 agrees. docs/tool-versions.md:27 (2.1.330) is unchanged, which is correct. |
| 3 | Which code path the WARN came from | scripts/archive-evidence.sh:575-594 | Pass; the writer's claim is supported. The WARN text in log row 82 ("byte copies were not checked for identifiers: account unknown (use S3 mode to check them)") is exactly the `warn` at :577. That line runs only when `SC_ACCOUNT_ID` is empty (:575). The E-03 assignment (:583-585) sits in the `else` branch (:578-594), which needs a non-empty `SC_ACCOUNT_ID`. `SC_ACCOUNT_ID` starts empty (scripts/lib/identity.sh:54) and is set only at identity.sh:239. In local-only mode the only route there is `register_identifiers` at :568-569, which needs `LOCAL_ACCOUNT`. That variable is set only from a legacy plan record (:393, :406), and log row 82 shows "plans (0 plan records summarized)". So "the E-03 branch has not run" at README:325, runbook:189 and final-report:612 is correct, and so is the account-unknown WARN wording at README:319 and runbook:184. Note: final-report:746 has no E-03 wording, only the command in the E12 list. |
| 4a | Video marks | README.md:398, :454; docs/demo-script.md:242 | Pass. All three `PENDING E11` marks are unchanged. |
| 4b | P17-M-06 `exit n/a` lines | README.md:127, :133, :134, :145; docs/demo-script.md:61, :70 | Pass. Unchanged (not in the diff). |
| 4c | Phase 02 agent-session lines and demo-only lines | README.md:147, :170-173; docs/demo-script.md:71, :72, :175, :188 | Pass. Unchanged, and they still read `not replayed in phase 17 (...)`, as intended. |
| 4d | No line still says one of the six is not replayed | grep of "not replayed", "E5 runbook", "2.1.330", "archive-evidence", "not run as a separate", "must print" over README.md, docs/*.md, reports/*.md | Pass. "E5 runbook" has 0 hits. Every remaining "not replayed" hit is one of the lines in 4c or a legend that names the form (README:9, runbook:14, demo:9). |
| 4e | No new "all final-gate items met" claim | grep of "final-gate items", "README commands have been replayed", "every final-gate" | Pass. 0 hits. |
| 5 | Identifiers | diff-E12-docs.txt (all lines); README.md, docs/*.md, reports/*.md for the archive values | Pass. 0 hits for twelve-digit runs, `arn:`, `amazonaws`, `execute-api`, AKIA/ASIA/`sk-ant-`, `s3://` or dotted IPv4. The archive sha256 (`f862e29…`), archive name (`…153121Z…`/`…-6f42`) and `evidence/archives` do not appear in any of the four documents. The only hashes in added lines are the RC SHA and earlier committed manifest and commit SHAs, all already in the context lines. |

## Findings

| Severity | File:line | Failure input | Impact | Owner |
|---|---|---|---|---|
| minor (m-1) | README.md:325; docs/runbook.md:189 | The E-03 clause lost its explicit `runtime: untested` tag. runbook:189 now reads "the current code of this mode is runtime tested once, in local-only mode". The E-03 branch (archive-evidence.sh:580-586) did not run (check 3). | A reader skimming for the `runtime:` tags could take E-03 as tested. The same sentences do say "the E-03 branch itself, which needs a known account, has still not run", and final-report:612 keeps "all `runtime: untested`, except …". So this is wording drift, not a false claim. Proposed later: keep "E-03 branch: `runtime: untested`" next to the mode-level statement. | documentation-engineer |
| minor (m-2) | README.md:9 ("E3, E4, most of E5 and E12 ran in a fresh clone … (`/tmp/p17-clone-20261123T1850Z`)"); docs/runbook.md:14; README.md:270 and reports/final-report.md:746 ("in the same clone") | Runbook-log rows 76-82 (E12 decision and E12r.1-r.6) say only "in the clone" with HEAD = RC SHA. None names `/tmp/p17-clone-20261123T1850Z` or rules out the second clone `/tmp/p17-replay-20261123T1850Z`. The writer's report lists this as assumption (2). | The clone path given for E12 comes from inference, not from the authoritative log. It is probably right (E12r.2 recompiled only 17 test sources, which fits the already-built E3 clone), but the log does not prove it. The human can confirm it in the log, or the docs can say "a fresh clone at the release candidate". | documentation-engineer (the human confirms the fact) |

Carried open items, unchanged here and not new findings:
- demo-script:9 still says "item 6" for the `sed -i.bak`/`tail -f` probes, which are in item 4 (the E11 re-check minor).
- P17-M-04: scripts/README.md:59 runtime line, owned by infra-engineer, not changed after the RC. It also does not record E12r.6, which README:325 and runbook:189 state openly.
- P17-M-06: the `exit n/a` form, listed at E12 as an unplanned deviation.
- The writer's own command deviations, reported by the writer and not verified by me: `sed -n` once and a `cd` prefix.

VERDICT: APPROVE

STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: docs/agent-runs/phase-17/prompt.md (results rules lines 37-45, replay-line forms line 45, rule line 213, E12 line 353); E12 invocation (focused read-only check of the replay-line fill-in). HEAD ade85aad5bab4535fc8431d82c574b3a9b70aa1a; RC b4d8f1229fc145e1492e1a8cbb8a44a368d026af.
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools: Read, Grep)
TEST RESULTS: n/a (Markdown-only change; no source change)
VALIDATION RESULTS: checks 1-5 pass (table above). 7 replay lines (6 README, plus demo-script:159 and runbook:184 copies) match runbook-log rows E12r.1-E12r.6 for command, exit and counts. The script reading at archive-evidence.sh:575-594 supports "E-03 branch has not run". 0 identifier hits in the diff. Video marks, `exit n/a` lines, Phase 02 lines and demo-only lines unchanged. No new final-gate claim.
ASSUMPTIONS: The scratchpad runbook-log.md is the authoritative copy, as the work order states. "Closed form" means `replayed in phase 17 (ENN, exit CODE)` followed by `; evidence: …`, the same pattern as the E5/E10 lines approved at E11.
RISKS: m-2 (the E12 clone path is inferred, not in the log). m-1 (E-03 tag wording). The README's line-number row labels (README:110, :276, :278-280, :319) match the current line positions, but they would go stale if lines move before the tag.
MANUAL ACTION REQUIRED: The human confirms which clone E12 ran in (m-2) and decides whether m-1 and m-2 go on the proposed-later list. The human commits the four Markdown files.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: orchestrator E12 readiness summary (orchestrator-summary.md); identifier scan 3 before the gate commit; human gate.
