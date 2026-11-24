# repair-E11-docs.md: Phase 17 session 3, step E11, documentation repair pass (one pass)

Writer: documentation-engineer. Scope: the findings of `review-report-final-docs.md`, P17-M-01 to P17-M-13 except P17-M-04, which belongs to infra-engineer and was not repaired; the human's decisions in runbook-log row "E11 repair decisions (human)"; and row "E8 local profile removal (human)". The changes are in the same 8 Markdown files E11 already changed. No file was added, deleted or renamed. Nothing under `scripts/**` or `docs/agent-runs/**` was touched.

```text
STATUS: COMPLETE
OWNER: documentation-engineer
WORK ORDER: docs/agent-runs/phase-17/prompt.md, step E11 (line 352), results rules (lines 37-45), release-manifest rule line (line 213); the orchestrator's E11 repair order (HEAD c6bbe148cc8aad0f65018c6cada3e6543dcada5b, release candidate b4d8f1229fc145e1492e1a8cbb8a44a368d026af)
FILES CREATED: none in the repository. In the scratchpad: phase17-reports/repair-E11-docs.md (this report), phase17-reports/diff-E11-docs-2.txt, linkcheck.sh, added-lines.txt, control.md and edit-fs.py (the last was not used; no repository write came from it)
FILES MODIFIED: README.md, docs/demo-script.md, docs/final-state.md, docs/runbook.md, docs/threat-model.md, reports/final-report.md. docs/api.md and docs/service-limits.md keep their earlier E11 changes and were not changed in this pass
COMMANDS RUN: read-only only: grep, awk (line reads, heading slugs), sed -n 'N,Mp' (literal line reads), wc, ls, cut, git diff, git diff --stat, git status --short, git ls-files (in the link check). Also `bash` on my own scratchpad script linkcheck.sh, which only reads files and calls git ls-files. Writes went only to the scratchpad: `git diff > diff-E11-docs-2.txt` and `grep > added-lines.txt`, both with literal paths. `cd` to the repository root was used as a command prefix, read-only. The guard blocked two of my commands and neither ran: a sed loop that used a shell variable, and a redirect to a path containing '..'. I replaced both with literal forms. No replay, no sbt, no script under scripts/**, no AWS call, no network.
TEST RESULTS: none run (Markdown only; no sbt)
VALIDATION RESULTS: link check over the 8 files: 812 repository-relative links, 0 missing, 0 untracked, 0 external; 348 #fragment links, 0 bad. Control: the slug function finds the new `#12-phase-17-deviations-accepted-by-the-human-not-instructor-approvals` and `#11-instructor-approved-deviations`, and does not find `#99-nonexistent`. Identifier scan of all 188 added lines of the full E11 diff: 0 twelve-digit runs, 0 twelve-zero runs, 0 ARN/amazonaws/AKIA/ASIA/sk-ant-/execute-api/queue-URL patterns, 0 account fingerprint values, 0 IPv4 addresses, 0 pa- key patterns. Video marks unchanged: README.md:398, README.md:454, docs/demo-script.md:242. 0 `planned for replay` left.
ASSUMPTIONS: the scratchpad runbook-log.md is the human's authoritative record. `tool-versions-p17.txt` line 12 is the `2.1.330 (Claude Code)` output (grep -n: line 11 is the command, line 12 is the output). The reports the reviewer named for P17-M-13 are present in docs/agent-runs/phase-17/ (ls: agent-report-rag.md, repair-01-rag.md, agent-report-service.md, agent-report-infra-I1a.md, agent-report-infra-I1b.md, routing-p17.md). agent-report-test-final.md is not there yet, so its wording is "(copied with the E11 commit)".
RISKS: (1) scripts/README.md:59 still reads `runtime: untested` for release-manifest.sh (P17-M-04, infra-engineer, not changed after the release candidate). The docs now state `runtime: tested in phase 17 (E10, exit 0)` and name that line as a recorded open item, so the two files differ on purpose until E12 lists it. (2) Every runbook-log citation of a session 3 row dangles until the human copies the session 3 runbook-log.md into docs/agent-runs/phase-17/. (3) The six `exit n/a: interactive session` lines are kept, by the human's decision, outside the closed replay-line form list. The orchestrator lists them at E12. (4) The link counter counts link occurrences differently from my earlier E11 script (812 here, 699 there); both found 0 missing.
MANUAL ACTION REQUIRED: copy into docs/agent-runs/phase-17/ with the E11 commit: the session 3 runbook-log.md, agent-report-test-final.md, manifest-checksums.txt, agent-report-docs-E11.md, review-report-final-docs.md and this report (repair-E11-docs.md). Optional: diff-E11-docs-2.txt.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: focused re-check of this pass by code-reviewer, then the orchestrator's identifier-scan-2.md and the human's E11 commit
```

## Per-finding table

| Finding | Status | File:line (after this pass) | Change |
|---|---|---|---|
| P17-M-01 (major) | fixed | README.md:67 | `/agents` replaced by `ls .claude/agents \| wc -l` (prints `18`). Points to step 5's table and its replay row "E5 optional recorded `claude` session". |
| P17-M-02 (major) | fixed | docs/runbook.md:189 | Now says the `--local-only` row was not replayed in Phase 17 (no E5 row runs `archive-evidence.sh`), so the current code of this mode is untested at runtime. |
| P17-M-03 | fixed | reports/final-report.md:612; docs/runbook.md:191; README.md:325 (for consistency) | `release-manifest.sh` is `runtime: tested in phase 17 (E10, exit 0)`. The E5.40 dry run and E10 each exited 0 (RL rows). Each passage notes that `scripts/README.md:59` still reads `runtime: untested`, open item P17-M-04. The three other script changes stay `runtime: untested`. |
| P17-M-04 | not repaired (not mine; human decision 1) | scripts/README.md:59 | Only mentioned in the docs lines above, as a recorded open item for E12. |
| P17-M-05 | fixed | docs/threat-model.md:7 | "E1 commit pending" became "E1 in commit `b4d8f12` (`b4d8f1229fc145e1492e1a8cbb8a44a368d026af`, the release candidate)". |
| P17-M-06 | kept by human decision 2 | README.md:127, :133, :134, :145; docs/demo-script.md:61, :70 | `exit n/a: interactive session` wording unchanged; not added to the legends. At README:134 only the left cell's cross-reference changed ("disabled in step 4", P17-M-12). |
| P17-M-07 | fixed (human decision 3) | README.md:110, :112 | :110 is `not replayed in phase 17 (no E5 row runs it; the orchestrator's session 2 start check printed 2.1.330, docs/agent-runs/phase-17/tool-versions-p17.txt:12)`. :112 now says the check was not a README replay and that the recorded session used 2.1.330. It adds the human's E11 report that `claude --version` printed `2.1.331 (Claude Code)` (Claude Code updated itself after session 2, 2.1.330 to 2.1.331), labelled as the human's report, not a replay (RL "E11 repair decisions (human)"). Mentions of 2.1.330 for the recorded sessions are unchanged. |
| P17-M-08 | fixed | reports/final-report.md:79 (section 1.2, item 6) | Adds the two read-only `cd` uses of session 3 sitting 1 (one `cd docs/agent-runs/phase-17` inside a grep line, one `cd` back), accepted at E1 as an unplanned deviation (RL "Resumption ...", "Orchestrator command note"; row "E1 decisions"). |
| P17-M-09 | fixed | README.md:9; docs/runbook.md:14 | The legends now say that E3, E4 and most of E5 ran in the fresh clone. The E5.40 dry run, E6 (state count, `plan.sh`) and E10 ran in the main working tree at the release-candidate commit. README:9 also says E5.42 made a second clone for the Phase 02 replay lines. |
| P17-M-10 | fixed | docs/final-state.md:129; reports/final-report.md:498 | Both now keep "usage covered by credits, not confirmed on the Bills page". The reviewer's :496 is now :498 because of the new 1.2 heading. |
| P17-M-11 | fixed | docs/demo-script.md:59, :174 | :59 says "the role-file count ... (a count of 18, not a list of names)". :174's "Say" text adds the full-name condition: the rule applies only when a claim gives both researchers' full names, so a claim that names the pair by surname only is not checked by it. Both that case and wording with no listed word are recorded known limitations. |
| P17-M-12 | fixed | README.md:121, :123, :129, :134 | Step 4 (:123) is the one MCP approval step: approve `aws-api`, `aws-docs` and `terraform`, disable `github`. `aws-api` connects only with step 3's profile. It is labelled "the only MCP approval step of this section", and keeps the note that the human disabled `github` at the start prompt. :121 now points to step 4. :129 no longer repeats the instruction and refers to step 4. :134 says "disabled in step 4". |
| P17-M-13 | fixed | reports/final-report.md:312, :321, :348, :372, :373, :604; :377 | "to be copied" became "in `docs/agent-runs/phase-17/`" for the six reports already there. At :377, `agent-report-test-final.md`, which is not there yet, reads "in `docs/agent-runs/phase-17/` (copied with the E11 commit)". |
| Human decision 4 | done | reports/final-report.md:70 (new `### 1.2 Phase 17 deviations accepted by the human (not instructor approvals)`), :72-79; docs/final-state.md:127 | The session 3 block moved out of 1.1 into its own heading. 1.1 now holds only the instructor approvals and the "Not recorded there" note. The one link that pointed into the old block, final-state.md:127, now points to `#12-phase-17-deviations-accepted-by-the-human-not-instructor-approvals`. I grepped every tracked Markdown file outside docs/agent-runs/: no other link to `final-report.md#11...` and no other reference to the old block label. The remaining "section 1.1" references (final-report.md:348, :389, :481, :608, :744) are about the teardown or the budget, which are instructor approvals, so they stay correct. |
| Local profile removal | done | docs/final-state.md:130; reports/final-report.md:742 | The human removed the local `scholarchain-readonly` profile, and `aws configure list-profiles` prints only `default` (the human's report at E11; RL "E11 repair decisions (human)", "E8 local profile removal (human)"). The admin user is not named. |
| Video marks | unchanged | README.md:398, README.md:454, docs/demo-script.md:242 | No link yet. |

## Verification the human asked for, (a) to (d)

| Item | Result | Where |
|---|---|---|
| (a) P17-L-06 to L-09 wording | done | L-06: final-report.md:670, :684, :685, :690 ("recorded minor"), :691 (F-05, T/Z clone stamp named), :474. L-07: README.md:421, threat-model.md:7 and :59, api.md:316, final-report.md:273, :674 and :706, demo-script.md:176. L-08: api.md:315 and :317 cite repair-E1-rag.md:47-51 and :57-60. L-09: service-limits.md:8, threat-model.md:59 and :183, final-report.md:474 and :619 cite row "E1 decisions 3-10, spelled out". Line numbers in final-report.md are 2 higher than the reviewer's because of the new 1.2 heading. |
| (b) Full-name condition stated as a known limitation in every L2 passage | done | README.md:421; api.md:316 (with P17-L-03 at :311 and P17-L-04 at :322); threat-model.md:7, :59; final-report.md:273, :674; demo-script.md:174 (added in this pass), :176 |
| (c) README uses `ls .claude/agents \| wc -l`; no `/agents` instruction left | done | README.md:67 and :133 use the count. The only remaining `/agents` mentions in my files are "not replayable / wizard has been removed" notes: README.md:136, demo-script.md:61 and final-report.md:78 (section 1.2, item 5) |
| (d) The human disabled the GitHub MCP server at the start prompt | done | README.md:123 ("the human disabled `github` at it"), README.md:127 (replay line: "`github` was disabled"), README.md:121 (disable it at step 4's prompt), README.md:134 ("disabled in step 4") |

## Link check and identifier scan

- Script: scratchpad `linkcheck.sh`, read-only. It reads each file's `](...)` targets and resolves them relative to the file. Each target must exist and be tracked (`git ls-files`). Each fragment must match a GitHub-style heading anchor of its target: lowercase, punctuation dropped, spaces to hyphens, fenced code skipped.
- Result over the 8 files: `links=812 missing=0 untracked=0 external=0 fragments=348 badfrag=0`.
- Control: `#12-phase-17-deviations-accepted-by-the-human-not-instructor-approvals` and `#11-instructor-approved-deviations` are found, `#99-nonexistent` is not.
- Identifier scan, over the 188 `+` lines of the full diff (E11 plus this pass): 0 for every pattern (twelve-digit runs, twelve zeros, ARN, amazonaws.com, AKIA/ASIA keys, sk-ant-, pa- keys, execute-api, queue URLs, the three known fingerprint values, IPv4).

## Unverified claims

- The 2.1.331 version comes only from the human's report (RL "E11 repair decisions (human)"). The README labels it as such.
- The local profile removal comes only from the human's report. The docs label it as such.

## Proposed later (not edited)

- README.md:216 (optional `aws-api` profile bullet) could also mention the local profile removal. It was not in the order's list of E8 removal passages (final-state.md, final-report.md).
- docs/tool-versions.md records only 2.1.330 (session 2 start check). If the human wants it there, a note on the human's E11 `2.1.331` report could be added.

## INTERFACE HANDOFF

- Files changed in this pass: README.md (:9, :67, :110, :112, :121, :123, :129, :134, :325), docs/runbook.md (:14, :189, :191), docs/threat-model.md (:7), docs/demo-script.md (:59, :174), docs/final-state.md (:127, :129, :130), reports/final-report.md (:70-79 new section 1.2, :312, :321, :348, :372, :373, :377, :498, :604, :612, :742).
- Public anchors: new `reports/final-report.md#12-phase-17-deviations-accepted-by-the-human-not-instructor-approvals`. The anchor `#11-instructor-approved-deviations` is unchanged and now covers only the instructor approvals.
- Full diff of the 8 files (E11 fill-ins plus this pass): scratchpad phase17-reports/diff-E11-docs-2.txt (905 lines; `git diff --stat`: 8 files, 188 insertions, 173 deletions).
- No code, test, script, configuration or constitutional file changed. Thread-safety and limits do not apply.
