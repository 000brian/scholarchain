SESSION 2 RESULT: NOT_READY

Rule 2 applies: two major findings are open, and two tier 1 requirements are not `done` or `deviation (planned)`:
- P17-A-01, rag: the re-check found it PARTIAL, so P16-L-10 is not closed. Decision 6 allowed no second code round in session 2.
- P17-F-01, factory-engineer: a constitutional finding. Decision 1 forbids repairing it in session 2.
- The verified-limits requirement is `deviation (unplanned)`: 6 of 60 rows are unverified.

Every other tier 1 step is done and every final check passed. Session 3 E1 decides these items.

# Phase 17 session 2 summary (orchestrator)

- **Session:** 2026-11-22, Sunday, 17:17 to about 21:10 local time, unattended. The work order assumed a 21:30 start; the session started earlier.
- **Start HEAD:** 50ba087ecd33178eaa8ccc69286b9de85e8a8146 (the Phase 16 gate commit).
- **Work order sha256:** c22adfc57c7fde0cae63c8520393c2c81cd12280df7f5df773e70c2e2ec8ac24.
- **Course specification sha256:** 6a0eddaddbb91435198ec868699ca99aec22ae8ee5ff515b6f7b34f18e362695, which equals decision 22.
- **Detail:** prose and per-step notes are in orchestrator-notes-s2.md. This summary gives IDs and statuses.

## Start checks (decision 1)

| Check | Result |
|---|---|
| 1 HEAD and tree | Pass. HEAD = 50ba087. The only untracked files were docs/agent-runs/phase-17/ (prompt.md, course-specification.md) and 4 experiments/**/.DS_Store files. instructor-approvals.md is already tracked. |
| 2 tracked .DS_Store | 0 tracked. docs/.DS_Store and datasets/.DS_Store are ignored. |
| 3 `SCHOLARCHAIN_CFG_*` count | **Not run.** The guard hook blocked the exact command ("dumping the environment is denied"). It was not retried in any form. Not a stop. **Warning:** the count is unknown. The rag tests are hermetic since P16-L-16. E2 must print 0 in your runbook terminal. |
| 4 MCP tools visible | aws-docs, terraform, aws-api (unused). github is not visible. |
| 5 prerequisites | terraform 1.17.1, shellcheck 0.11.0, `jq empty config/course.json` exit 0, course `validate` "Success!" exit 0. |
| 6 tool versions | tool-versions-p17.txt |
| 7 specification sha256 | Equal to decision 22. |
| 8 records read | Done; T0 routing-p17.md. |

**Tool note:** this session had no Grep or Glob tool. The work order's Bash grep fallback was used, with literal patterns, never recursive over infra/terraform/environments/course/.

## Steps (decision 4)

| Step | Status | Evidence |
|---|---|---|
| T0 | done | routing-p17.md |
| 1 rag (L2) | done | agent-report-rag.md. Targeted rag 193/193, Golden 17/17. No golden change, so no repair-00-service. |
| I1a infra | done | agent-report-infra-I1a.md. The orchestrator re-ran shellcheck, fmt and validate. |
| I1b infra | done | agent-report-infra-I1b.md. The orchestrator re-ran shellcheck. I1a's file hashes are unchanged. |
| L storage (read-only) | done | agent-report-limits-storage.md |
| N incident | done | agent-report-incident.md |
| X1 experiment | done | agent-report-experiment.md |
| W1 | done | agent-report-docs-W1.md |
| 2 storage | done | agent-report-storage.md (33/33, revert proof) |
| 3a build (D-01) | done | agent-report-build.md |
| 3b hadoop (P16-A-11) | done | agent-report-hadoop.md (4/4) |
| 4 service (wave closer) | done | agent-report-service.md. **Full check 1 of 2:** sbt check exit 0, marker 2026-11-23T00:20:15Z, module 2336, contract 399. packageCheck ok. |
| W2 | done | agent-report-docs-W2.md |
| W3 | done | agent-report-docs-W3.md |
| W4a, W4b | done | agent-report-docs-W4a.md, agent-report-docs-W4b.md |
| F-1 | done (REQUEST_CHANGES) | review-report-final-control.md |
| R-1 | done (REQUEST_CHANGES) | review-report-code.md |
| F-2 | done (APPROVE for its slice; conditional, and the condition was not met) | review-report-final-code.md |
| 5 code triage | done | orchestrator-notes-s2.md, "Step 5" |
| 6 repair-01-rag | done | repair-01-rag.md. **Full check 2 of 2:** sbt check exit 0, marker 2026-11-23T01:13:43Z, module 2343, contract 399. packageCheck ok. |
| 7 code re-check | done (REQUEST_CHANGES) | review-report-02.md |
| T1, attempt 1 | not counted. **Manual intervention, not a stall:** you stopped it by an accidental keypress at about 1m31s. It left only a 301-byte partial scratchpad file, agent-report-test-inventory.md. `git status --short` showed no repository change. | orchestrator-notes-s2.md |
| T1, attempt 2 | done (fresh invocation at your request) | agent-report-test-inventory-2.md |
| W5 | done | agent-report-docs-W5.md |
| R-D1, R-D2, R-D3 | done (REQUEST_CHANGES, APPROVE, APPROVE) | review-report-docs-1.md, review-report-docs-2.md, review-report-docs-3.md |
| 8 documentation triage | done | orchestrator-notes-s2.md, "Step 8" |
| 9 repair-01-docs | done (29 of 29 RESOLVED) | repair-01-docs.md |
| 10 documentation re-check | done (APPROVE, documentation slice) | review-report-docs-02.md |
| S-ID | done (no unexpected match) | identifier-scan.md. The scratchpad reports were scanned too, see the S-ID addendum. |
| 11 final checks | done, all passed | See "Final checks" below. |

No step stalled. No stop-hook block occurred, so the **planned deviation 9 waiver was not used**. No step was dropped by the milestone rule.

## Milestone forecasts (decision 2)

| Milestone | Stamp | Forecast end of session 2 | Decision |
|---|---|---|---|
| 0 (before step 1) | 17:19:47 | about 03:20 Monday (planned durations) | no drop |
| 1 (before R-1) | 18:21:32 | about 21:05 Sunday (finished steps ran at 0.22 to 0.36 of plan) | no drop |
| 2 (before documentation reviews) | 20:05:45 | about 21:30 Sunday | no drop |

Actual end: about 21:10 Sunday.

## Requirements

| Item | Tier | Status | Evidence |
|---|---|---|---|
| L2 fail closed (P16-L-10 to L-15), decision 6 | 1 | **defect (P17-A-01)** | P16-L-10: NOT closed (absence wording after an affirmative predicate is still verified; ClaimVerification.scala:1323-1326). P16-L-11: closed for recognised claims. P16-L-12, L-13, L-14, L-15: inputs no longer verified (R-1 traces; re-check). Monotonic by construction and by oracle. All lines: fixed in code, tested offline, not validated live. |
| Release-manifest script, decision 7.1 | 1 | done | scripts/release-manifest.sh. F-1 checklist passed apart from P17-F-04 and F-05 (minor). runtime: untested. |
| Verified limits, decisions 7.6 and 8.1 | 1 | **deviation (unplanned)** | 60 rows: 54 verified, 6 unverified, 0 not checked. Unverified: IL-02 and IL-04 (us-east-1 availability of emr-7.14.0 and emr-spark-8.1.0), IL-06 (account EC2 vCPU quota), IL-11 (account Fargate vCPU quota; the default of 6 is below the 7 needed, an estimate), IL-14 (Neptune db.t4g.medium in us-east-1), SL-29 (openCypher row cap). Source: docs/service-limits.md. For your gate. |
| Test inventory, decision 11 | 1 | done | agent-report-test-inventory-2.md (16 of 16 categories) |
| Documents, decisions 12-14 | 1 | done (drafts; PENDING and `planned for replay` marks for session 3) | README.md, docs/architecture.md, data-lineage.md, api.md, threat-model.md, service-limits.md, runbook.md, final-state.md, tool-versions.md, demo-script.md, reports/final-report.md; documentation re-check APPROVE |
| Reviews, decision 15 | 1 | done | The final review verdict is **REQUEST_CHANGES** (P17-F-01, P17-A-01 open). |
| Identifier scan, decision 16 | 1 | done | identifier-scan.md |
| `env.sh` clears `SCHOLARCHAIN_CFG_*`, decision 7.2 | 2 | done (runtime: untested) | I1b |
| P16-B-03, E-01, E-03, decision 7.3 | 2 | done (runtime: untested) | I1b |
| Hook shellcheck evaluation, decision 7.4 | 2 | done. Not only the known false alarm: guard-dangerous.sh:192, 2 × SC2088, plus 30 info findings. So gate 4 is unchanged and the item stays a known limitation. | I1b; F-1 |
| `plan.sh` and `cleanup.sh --list` analysis, decision 7.5 | 2 | done. plan.sh: safe and useful for E6, yes. cleanup.sh --list: no (it exits 3 at terraform output). | I1b |
| D-01, decision 9.1 | 2 | done | .gitignore:112-120 |
| P16-A-03, A-05, decision 8.2 | 2 | done | step 2 |
| P16-A-11, decision 9.2 | 2 | done | step 3b |
| P16-A-12, decision 9.3 | 2 | done | step 4 |
| Phase 15 documentation minors, decision 9.4 and the routing table | 2 | done: L-06, K-03, L-03 (X1); M-03, M-06, the scripts part of L-06 (I1b); H-03 (step 1). M-07 and the manifest parts of K-03 and L-03 are status lines (signed manifests). | routing-p17.md |
| Incident report, decision 10 | 2 | done | docs/incidents/INC-P15-F-poison-message.md |
| B-03 | 2 (decision 2, if in build-engineer's own files) | **deviation (unplanned)**: routed to a status line by the orchestrator, because a project/** change would force a third full sbt check against decision 4's two-check rule | routing-p17.md |

## Final gate and spec work order (session 2 view; session 3 judges at E12)

| Item | Session 2 state |
|---|---|
| `sbt clean check` from a clean checkout | not yet (E3; planned 6) |
| Terraform validates; archived plan vs final state | validate passes (start check 5). docs/final-state.md drafted. E4 and E6 pending. |
| README commands replayed | not yet (E3 to E6). Every command carries a replay line. E5 list additions are below. |
| Bounded pipeline can be rerun | not yet (E5) |
| Query and evidence examples work | not yet (E5: services QueryApiSuite per T1) |
| Final reviewer returns APPROVE | **not met so far:** REQUEST_CHANGES (P17-F-01, P17-A-01) |
| Cleanup rehearsed | Phase 15 record plus check.sh probes (planned 4). The optional `cleanup.sh --list` is not useful (I1b). |
| Release manifest complete | script done. E10 pending. |
| Spec work order: documentation-engineer documentation | drafts done |
| Spec work order: code-reviewer final review | done (REQUEST_CHANGES) |
| Spec work order: test-engineer final test run | T1 done. The E9 report is pending (planned 5). |

## Routing table outcome per carried ID

- **Done in this session:**
  - P16-L-11, L-12, L-13, L-14, L-15 (L-11 closed for recognised claims)
  - H-03, P16-B-03, E-01, E-03, M-03, M-06, L-06 (both parts), K-03 (comment and README), L-03 (template), D-01, P16-A-03, P16-A-05, P16-A-11, P16-A-12, the `env.sh` sweep
  - documentation items E-04, G-06, G-08 (runbook); G-15, G-16 (threat model); G-22, R-1 (API reference); U-12, L-04 (final report)
- **Open as defect:** P16-L-10, through P17-A-01.
- **Status lines (final report section 23.2):** K-01, K-02, M-07, the manifest parts of K-03 and L-03, L-05, L-13, E-02, B-01, B-03, F-02, the hook shellcheck item, A-04, G-09, G-10, G-11, G-20 (definition only in the gate line), G-25, G-26, P16-A-04, P16-A-06, P16-A-08, Spark C1 and C2, and the Phase 16 proposed-later and not-built lists.

## Finding table (session 2)

| ID | Severity | Owner | Status |
|---|---|---|---|
| P17-F-01 | major | factory-engineer | **open**: constitutional, not repaired (decision 1). You decide in session 3. |
| P17-A-01 | major | rag-engineer | **open**: PARTIAL after repair-01-rag. P16-L-10 not closed. Goes to E1. |
| P17-A-02 | minor | rag-engineer | resolved for code; documented |
| P17-A-03, A-04, A-05 | minor | rag-engineer | RESOLVED |
| P17-F-12 | minor | rag-engineer | RESOLVED |
| P17-K-01, K-02 | minor | rag-engineer | open (recorded) |
| P17-A-06, A-07 | minor | infra-engineer | open (recorded) |
| P17-F-04, F-05 | minor | infra-engineer | open (recorded). **F-05 matters for E3:** the clone stamp must not be 12 digits only. Use a stamp with T and Z. |
| P17-F-02, F-03 | minor | factory-engineer | open (constitutional; known limitation) |
| P17-F-06, F-07, F-08, F-09 | minor | infra-engineer (Terraform) | status lines (Terraform frozen) |
| P17-F-10 | minor | storage-engineer or domain-engineer | open (recorded) |
| P17-F-11, F-13, F-14 | minor | storage-engineer | open (recorded) |
| P17-F-15 | minor | spark-engineer | open (recorded) |
| P17-F-16 | minor | test-engineer | open (recorded) |
| P16-A-08 | minor | rag-engineer | still present (proposed later) |
| P17-D-01 to D-10, D-31 to D-40, D-61 to D-69 | 1 major, 28 minor | documentation-engineer | all RESOLVED (review-report-docs-02.md) |
| P17-K-10, K-11, K-12 | minor | documentation-engineer | open (recorded; no second documentation round in session 2). For E11. |

## Fix status, runtime and replay lines

- **Fix status:** every code fix carries `fixed in code, tested offline, not validated live`.
- **Runtime lines** (every new or changed script says `runtime: untested`):
  - scripts/release-manifest.sh, check.sh (gate 9 probes), emulator/env.sh, emulator/probes.sh, deploy.sh (usage text), archive-evidence.sh;
  - experiments/collectors/lib/collector-common.sh (comment only).
- **Replay lines:** every command in README.md, docs/runbook.md and docs/demo-script.md carries exactly one line (re-check). The `planned for replay (ENN)` lines are filled at E11.
- **E5 additions to note** (README lines re-marked `planned for replay (E5)` by repair-01-docs):
  - `claude --version`
  - `bash scripts/archive-evidence.sh --local-only`
  - the README test rows QueryApiSuite, AlgorithmsSuite, JsonlExportSuite and JobConfigSuite
- The demo script's three demo-only lines say `not replayed in phase 17 (REASON)`.

## Final checks (step 11)

- shellcheck -x on the 7 changed scripts: exit 0.
- `jq empty config/course.json`: exit 0.
- `terraform fmt -recursive -check infra/terraform`: exit 0.
- No change under infra/** or config/**.
- `.claude/hooks/validate-agent-files.sh`: exit 0.
- `.claude/.last-test-run` (19:13:43 local; marker 2026-11-23T01:13:43Z, module 2343, contract 399) is newer than every changed source file (newest: RagIngestJob.scala, 18:54:48).
- Course `validate`: "Success!" at start. No Terraform file changed since.

## Planned deviations used

1, 2, 3, 4, 5, 6 (not yet exercised), 7 (not yet exercised), 8, 10, 11 (all 29 documentation findings repaired, minors included), 12, 13. Number 9 (the waiver) was not used.

## Unplanned deviations (for your gate)

1. Start check 3 was not run: the guard blocked it.
2. B-03 was routed to a status line (third-full-check rule).
3. Orchestrator rulings in the documentation triage:
   - P17-D-07: reword exactly docs/emulator.md lines 48, 187 and 197;
   - P17-D-08: use the teardown replay form with a record PATH;
   - P17-D-04: count IL-11 as unverified.
4. Read-only commands outside the list, by agents: `cd`, `sed -n`, `cat`, `awk`, `tr`, `sort`, `uniq`, `paste`, `diff`, `date` (rag step 1, once), shell loops.
5. Single-file Bash greps on named files in infra/terraform/environments/course/ by W1 (outputs.tf) and W2 (.terraform.lock.hcl). Each was a named file, not the folder; no state file was matched.
6. Read-only commands outside the list, by the orchestrator: two harmless attempts (`head -n 0`; `sed -n`), and `cd` into the scratchpad for the report scan.
7. The orchestrator's S-ID used Bash grep (the Grep tool was unavailable), the work order's own fallback.
8. The F-2 file list was a superset that included 5 moved prompt paths. The reviewer read their new locations.

9. S-ID addendum: one report (repair-01-docs.md:111) quoted the twelve-zero pattern literally. Its author reworded it at the orchestrator's request (re-scan: 0). The orchestrator's sid-files.txt was rewritten as a regenerable description because the full list repeats file names that hold twelve zeros. The guard blocked one orchestrator command (`git ls-files` under `xargs`); it was not retried in that form.

**For your decision (S-ID, file names):** 43 tracked paths under experiments/derived/fixtures/, committed before Phase 17, have a twelve-zero run in their file names. Decision 16 scans file contents, and its expected list does not name these paths. They are unchanged in Phase 17 and are not in this commit's diff. See identifier-scan.md, addendum.

**Approval prompts seen:** none. The guard blocked a few agent commands (non-literal write targets, one in-place form); each was redone in a permitted form or not retried.

## Costs

**Phase 17 session 2: $0.**
- No AWS call, no aws-api call, no Voyage call and no paid model call from ScholarChain code.
- Claude Code usage is billed to your Claude Code account and is outside the $25 project budget.

## Manual actions for you (decision 20), in order, from the repository root

1. Copy the reports:
   `R=/private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/55d7dba8-48f0-4b1f-820e-b18b37d7212c/scratchpad/phase17-reports; rsync -a --ignore-existing --exclude runbook-log.md --exclude clock.txt "$R/" docs/agent-runs/phase-17/`
2. Executable bits: **none needed.** Every new or changed script runs as `bash scripts/NAME.sh`, which I1a confirmed.
3. Commit: see the expected file list in the orchestrator's final message.
