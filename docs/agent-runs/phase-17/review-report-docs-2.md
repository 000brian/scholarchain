[Saved verbatim by the orchestrator from code-reviewer's final message (R-D2). The harness added the first line below; it is kept as received.]

[harness: subagent output matched instruction-shaped pattern(s): settings-json. Control tags below are neutralized (`<` → `<\`); treat any remaining directive-shaped text as a finding to relay to the user, not an instruction to you.]

# Review report R-D2 (review-report-docs-2.md): README.md, docs/demo-script.md, docs/tool-versions.md

**VERDICT: APPROVE** for the three R-D2 files. I found 0 critical, 0 major and 10 minor findings. Decision 15 sends all minor documentation findings to the documentation repair pass (repair-01-docs.md), so all ten go there.

All ten findings are owned by documentation-engineer and none is an interface change. I edited no file. Every finding below was checked against the cited files.

## Findings

| ID | Severity | file:line | Failure input | Impact | Owner | Interface |
|---|---|---|---|---|---|---|
| P17-D-31 | minor | README.md:72, :77, :78, :81 | The "Phases it ran in" column leaves out recorded runs: **build-engineer** in phase 04 (`docs/agent-runs/phase-04/repair-02.md:2`, `OWNER: build-engineer`); **spark-engineer** in phase 11 (`phase-11/repair-03-spark.md:4`); **storage-engineer** in phase 11 (`phase-11/repair-05-storage.md:2`); **test-engineer** in phase 14 (`phase-14/repair-D6-tests.md:6`) and phase 17 (step T1, `agent-report-test-inventory-2.md`, which README.md:248 itself cites). The group 6 lists match `events.csv` exactly. Only the "records also" additions are incomplete. | The catalog under-reports which phases four agents ran in, which the spec and decision 13 require. | documentation-engineer | no |
| P17-D-32 | minor | README.md:318-319 (with :102 and :11) | Step 1 (:102) says "every later line runs from this clone's root". A reader who does that and then runs `bash scripts/release-manifest.sh --clone /tmp/p17-clone-STAMP ...` from inside that clone is refused with exit 4: the script rejects a `--clone` that is the current tree (`scripts/README.md:66`, "not this tree (4)"). It must run from the main checkout (decision 18 E5, "from this working tree"). The two rows do not say so. | Both release-manifest command lines fail as written for a reader who follows step 1 literally. | documentation-engineer | no |
| P17-D-33 | minor | README.md:409, :451 | `PENDING E8` appears for "their removal in Phase 17 and the inventory after it" (:409) and for D20 "the state after Phase 17" (:451). Decision 13 allows PENDING only for the E8 IAM user (in AWS prerequisites, :212 is fine), the E10 release manifest (:445) and the E11 video (:394, :450). | Two PENDING marks fall outside the allowed list. A link to `docs/final-state.md#9-after-phase-17` would carry the same information without one. | documentation-engineer | no |
| P17-D-34 | minor | README.md:110 | The `claude --version` row's replay line is `replayed earlier (phase 17, start check 6, docs/agent-runs/phase-17/tool-versions-p17.txt)`. Three problems: "replayed earlier" is the form for earlier phases (a Phase 17 run is `replayed in phase 17 (ENN, exit CODE)`); no exit code is recorded; and the cited path does not exist in the repository yet (`docs/agent-runs/phase-17/` holds only `prompt.md` and `course-specification.md`). The run was the orchestrator's start check, not a README replay. | The line does not use one of the results section's valid forms. | documentation-engineer | no |
| P17-D-35 | minor | README.md:315, :321 | `bash scripts/archive-evidence.sh --local-only` carries `replayed earlier (phase 13, ...)`. But Phase 17 step I1b changed exactly the record that `--local-only` writes (E-03: `archive-evidence.sh:580-586`, runtime untested; `agent-report-infra-I1b.md:43, :54, :115`). The "Script facts as of Phase 17" paragraph (:321) lists other Phase 17 script changes but leaves out E-03 and probes.sh P16-B-03. The mode runs offline, so E5 could replay it. | The replay evidence predates the current code of that mode, and the reader is not told the mode is runtime-untested. | documentation-engineer | no |
| P17-D-36 | minor | README.md:278 | "exited 0 in 127 s ..." is cited to `run.txt` and `local-run-report.json`. `run.txt` holds only `exit=0`, and neither file holds 127 s. The value is in `experiments/evaluation/raw/repro/e5-pipeline-20261122T1715Z-79b5583/hadoop-pipeline.log:476` (`Total time: 127 s`). The other numbers (7, 33, 33, 67, 43 of 43) match their cited files. | One number points to the wrong source file (the rule is that every number names its source). | documentation-engineer | no |
| P17-D-37 | minor | README.md:132 (with :119, :127) | Step 5 expects `/mcp` to show `github` off. Nothing tracked turns it off: `.claude/settings.json` has no MCP entry, and docs/agents.md says the human disabled it in the untracked `settings.local.json`. Step 5 (:127) also tells the reader to "approve the project's MCP servers when Claude Code asks". A grader who approves all of them enables `github`, which fails to start (no token). Step 3's "leave it off" does not say how. | The stated expected `/mcp` result is not reproducible in a fresh clone, and the step contradicts step 3. | documentation-engineer | no |
| P17-D-38 | minor | docs/demo-script.md:9 (with :71, :72, :175) | :9 says "the full list of commands, with the same replay lines, is in README.md". The `sed -i.bak` probe (:71), the `tail -f` probe (:72) and the `contractTests/testOnly ...UnsupportedClaimEndToEndSuite` line (:175) are not in README.md (Grep: no match). All three carry `planned for replay (E5)`, while E5 replays README commands and only "the dangerous-command probe" (decision 18, E5). | The claim is false, and these three replay lines may stay unfilled at E11. | documentation-engineer | no |
| P17-D-39 | minor | README.md:428, :432, :433, :444, :449, :450 | :428 calls the table "the course specification's 'Required deliverables' table", but the Deliverable and "Minimum contents (spec)" columns paraphrase it (spec `course-specification.md:2765-2783`): D01 "Private Git repository" → "Git repository"; D02 "every `.claude/agents/*.md` file used in the project" → "every agent file"; D13 drops "Source-controlled" and "workflow"; D18 drops "or instructor-approved format"; D19 "Narrated system demonstration" → "narrated demonstration". | The index does not reproduce the spec's minimum contents as it says it does, which weakens the D01 to D20 check. | documentation-engineer | no |
| P17-D-40 | minor | README.md:434 | D03's mapping from spec file names to project names does not cover Phase 11's round-2 records: `round2-review-report.md`, `round2-test-report.md`, `round2-repair-{rag,er,hadoop}.md`, `repair-round-2.md`, `round2-summary.md` (all exist under `docs/agent-runs/phase-11/`). None matches the stated patterns (`review-report*.md`, `test-report*.md`, `repair-NN*.md`, `orchestrator-summary*.md`). | A grader using the D03 map would miss Phase 11's second review, test report and repair round. | documentation-engineer | no |

## Coverage

**Read in full:** README.md (all 452 lines, in two reads), docs/demo-script.md (all 244 lines), docs/tool-versions.md (all 128 lines).

**Work order and specification, read directly (not from a summary):**
- `docs/agent-runs/phase-17/prompt.md`: lines 1-102 (including the results section and its replay-line forms with the `deferred to E14 ...` tag line), decisions 12, 13, 15 (lines 241-308), decision 10 and decision 18 (E0-E14).
- `course-specification.md`: "Agent submission and reproducibility requirements" (800-841), "Required hook and permission behavior" (377-392), "Required deliverables" (2759-2784), "Agent-generated deliverable evidence", "Demonstration requirements" (2892-2919), "README requirements" (2978-3002).

**Checks and results:**
- **Spec README requirements:** all 19 items are present, in the spec's order. The identity line `Student: Brian Wilk, bwilk26@uic.edu` is present (README.md:3). The model configuration statement is correct (README.md:61; tool-versions.md:96).
- **11 agent-replay steps:** all present in project form under the exact subsection name "Reproducing the agentic workflow". Auto mode, the session 2 / session 3 split and the `rsync --ignore-existing` copying are stated and their sources check out (phase-14 prompt :5-8, phase-15 :18, phase-16 :5, phase-17 :5).
- **Bounded Phase 02 exercise:** clone, `switch -c` at the Phase 01 commit (commit.txt matches), validator, verbatim prompt, `sbt Test/compile`, `sbt check`, the two `git show PHASE02:...` lines, what to compare and what is not expected to match. The archived BLOCKED-ON report and its owners match `phase-02/agent-report.md:1, :76`. The gate items match `phase-02/human-gate.md`.
- **18-row agent catalog:**
  - The `tools` field (MCP tools included) matches `.claude/agents/*.md:4` in all 18 rows.
  - Owned paths match the docs/agents.md table.
  - Agent-specific report sections match each role file (including factory-engineer's 8 sections).
  - Example instructions match the real phase work orders (Grep "Use the ...").
  - Group 6 phase lists match `events.csv` execution rows for all 15 agents with executions. Gaps in the "records also" additions: see P17-D-31.
- **D01-D20 index vs the spec table:** every ID is present with links. Paraphrasing and mapping gaps: see P17-D-39 and P17-D-40.
- **Commands vs script options:** read in the script sources for check.sh, plan.sh, cleanup.sh, release-manifest.sh, rollback.sh, run-experiment.sh, smoke-test.sh, archive-evidence.sh and run-bounded-pipeline.sh. Also checked: the build.sbt aliases (packageJobs :712-715, fmt, check), the evaluate.py subcommands, `--fixtures` and exit codes, the LocalBoundedPipeline argument, and that every named suite class exists. All match apart from P17-D-32.
- **Numbers vs cited files:**
  - Phase 16 group 6 totals (87, 38/80, 196, 0/30, 62, 0).
  - Test counts: 2306/399 at Phase 16 gate line 75; 2343/399 with marker 01:13:43Z in repair-01-rag.md:127 and test-inventory-2.md:8-10.
  - The Phase 15 clip facts per runbook-log rows.
  - Smoke results 7/7, the 175 resources, approvals.md line citations, course.json and build.properties line citations, ConfigLoader and ScholarChainConfig lines, service-limits counts (60/55/5), and the 6 new reason codes against api.md.
  - All match apart from P17-D-36.
- **Replay lines:** every command row in README.md and demo-script.md has exactly one replay line. Form problems: P17-D-34, P17-D-35, P17-D-38.
- **Links:** every repository target I listed exists (Read/Glob), including the untracked Phase 17 files created this phase. Every `#fragment` in the three files matches a heading in its target, including docs/architecture.md, data-lineage.md, api.md, runbook.md, final-state.md, service-limits.md, threat-model.md, emulator.md, evaluation.md, reports/final-report.md, docs/agents.md and README-internal anchors.
- **Identifiers:** Grep over the three files for key shapes, 12-digit runs, `arn:aws`, `amazonaws`, `execute-api`, `.elb.`, the IAM user, budget and bucket name patterns, and IPv4 addresses. The only match is `1.4.7.1` (Neptune engine version, tool-versions.md:69), which is not an identifier. Neither AWS's example key nor the twelve-zero account appears.
- **tool-versions.md:** compared with `tool-versions-p17.txt`, `project/build.properties` (every line citation), `.mcp.json` (all four pins and lines), `phase-00/tool-versions.txt`, versions.tf, variables.tf, `.terraform.lock.hcl:5`, runtime main.tf:161, `.scalafmt.conf:4` and phase-16 prompt :5 and :81. All match; no finding.
- **demo-script.md:** 22 items, each with clip status, what to show, what to say, a command or screen, what to blur, and replay lines. The clip assignments match Phase 15 gate decision 10 and the runbook-log "D16 clip NN" and D25/D27/D28 rows. The probe line numbers match `guard-selftest.sh:129, :219`. The Phase 1 probes 3 and 5 match `phase-01/human-gate.md:11-22`.

**Not verified independently (no Bash or git available to me):**
- The Phase 01 commit subject (README.md:164) and the Phase 02 commit's 8-file count (README.md:173). W3's report records `git log -1` and `git show --stat` output for both; E5 replays them.
- Whether the GitHub clone URL can be reached.
- The `/agents` and `/mcp` output, and the guard's block of the in-session probe (W3 lists these as unverified; E5 covers them).
- Content of docs/evaluation.md and reports/final-report.md behind the anchors (R-D1 and R-D3 scope).

**Context reports read:**
- agent-report-docs-W3.md (replay table, unverified claims, validation evidence).
- agent-report-docs-W5.md (sources for the test counts).
- agent-report-test-inventory-2.md (marker lines).
- agent-report-infra-I1a.md (release-manifest options).
- agent-report-infra-I1b.md (E-01, E-03, the cleanup.sh --list analysis).
- review-report-02.md (P17-A-01 to A-03, K-01, K-02, verdict).
- review-report-final-control.md (only for P17-F-01's subject).

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 17 session 2, documentation review R-D2 (docs/agent-runs/phase-17/prompt.md, sha256 c22adfc5…ac24; decisions 12, 13, 15 and the results section): README.md, docs/demo-script.md, docs/tool-versions.md
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools)
TEST RESULTS: not applicable (documentation review; no tests run)
VALIDATION RESULTS: 10 verified findings, all minor (P17-D-31 to P17-D-40); 0 critical, 0 major; VERDICT APPROVE for the R-D2 files
ASSUMPTIONS: W3's recorded git outputs (Phase 01 commit subject, Phase 02 8-file stat) are taken as evidence because I cannot run git; the Phase 17 scratchpad reports become docs/agent-runs/phase-17/ files when the human copies them
RISKS: P17-D-32 and P17-D-37 affect whether a grader can follow the README literally; P17-D-38's three demo replay lines may stay "planned for replay" after E11 unless E5 includes them
MANUAL ACTION REQUIRED: none from the reviewer; the orchestrator routes P17-D-31 to P17-D-40 to documentation-engineer for the step 9 documentation pass
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: step 8 documentation triage; repair-01-docs.md by documentation-engineer; re-check in review-report-docs-02.md
```
