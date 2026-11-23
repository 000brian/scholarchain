# Phase 17 session 2 orchestrator notes

## Start (2026-11-22, Sunday)
- HEAD at start: 50ba087ecd33178eaa8ccc69286b9de85e8a8146 (= 50ba087, the Phase 16 gate commit). `git diff --stat 50ba087 HEAD`: empty.
- Work order sha256 (docs/agent-runs/phase-17/prompt.md): c22adfc57c7fde0cae63c8520393c2c81cd12280df7f5df773e70c2e2ec8ac24.
- Session started 17:17 local (clock.txt stamp, read with ls -lT), earlier than the 21:30 the work order assumed.

## Start checks (decision 1)
1. HEAD = 50ba087; tree clean except untracked docs/agent-runs/phase-17/ (prompt.md, course-specification.md) and 4 untracked .DS_Store files under experiments/. instructor-approvals.md is already tracked. PASS.
2. `git ls-files | grep -c DS_Store`: 0 tracked. docs/.DS_Store and datasets/.DS_Store are ignored (`!!`).
3. `env | cut -d= -f1 | grep -c '^SCHOLARCHAIN_CFG_' || true`: NOT RUN. The guard hook blocked the command ("reading secrets ... or dumping the environment is denied"). Not retried in any other form (standing rule). Not a stop by decision 1; recorded as a warning for the summary (count unknown; the rag tests are hermetic since P16-L-16).
4. MCP tools visible to the orchestrator: aws-docs (search/read/read_sections/recommend), terraform (registry docs), aws-api (not used). github: not visible (switched off, as recorded).
5. terraform version 1.17.1; shellcheck 0.11.0; `jq empty config/course.json` exit 0; course `validate`: "Success! The configuration is valid." exit 0. PASS.
6. tool-versions-p17.txt written.
7. course-specification.md sha256 6a0eddaddbb91435198ec868699ca99aec22ae8ee5ff515b6f7b34f18e362695 = decision 22. PASS.
8. Read phase-16 human-gate.md; Phase 13/14/15 gates read for the routing rows. T0 done: routing-p17.md.

## Tool note
- The Grep and Glob tools are not available in this orchestrator session: the work order's grep fallback (Bash `grep -n` with literal words and relative paths, never over infra/terraform/environments/course/) is used.

## T0 routing decision outside the letter of decision 2
- B-03 routed as status line, deviation (unplanned) for the gate: a project/** change needs the full sbt check marker (stop hook), which would be a third full check against decision 4's two-check rule.
- H-03 (Phase 15 minor, rag file) added to step 1 as tier 2 (routed to its file's owner, as decision 21's table says for Phase 15 minors).

## Milestone forecasts
- Milestone 0 (17:19:47, launch of 1, I1a, L, N, W1, X1): end of code chain to step 7 about 23:25; W4b about 23:20; W5 +45, two waves of documentation reviews +90, documentation repair +60, re-check +25, S-ID +15: forecast end about 03:20 Monday (estimate, decision 4's plan, no actuals yet). Before 13:00: no tier 2 drop.

## Step log (launch / completion, from clock.txt ls -lT)
| Step | Launched | Completed | Status |
|---|---|---|---|
| T0 | 17:17 | 17:19 | done |
| 1 rag | 17:19:47 | about 17:45:45 (agent duration 1558 s) | done (orchestrator check: changed files only under modules/rag (7 M, 3 new test files); modules/services unchanged (golden files byte-equal, no repair-00-service); targeted rag 193/193 and services GoldenExamplesSuite 17/17 per report; packaged eval-set JSON changed one line (H-03 rule text), not pinned by experiments/**, scripts/** or docs/evaluation.md (grep)). |

Step 1 items for R-1: (a) the monotonic corpus test reports `claims=59 absence=0` (no absence claim in the fake benchmark/golden corpus; the oracle covers the four verifier suites); (b) product fall outside fixtures: Synthesis.scala:653 "have no direct coauthor edge" verified → partially_supported; (c) AbsenceNoDepth now unsupported (was partially_supported); AbsenceNotSearched widened; new code AbsenceAmbiguous; (d) rag-engineer ran `date` once (on the never-run list; read-only) → unplanned deviation.
| I1a infra | 17:19:47 | about 17:40 (agent duration 1203 s); checked 17:46 | done (orchestrator re-run: shellcheck -x exit 0, terraform fmt check exit 0, files = scripts/README.md M, scripts/check.sh M, scripts/release-manifest.sh new; no infra/config change). runtime: untested. Limits 21 rows: 17 verified, 4 unverified (us-east-1 availability of emr-7.14.0, emr-spark-8.1.0, Neptune db.t4g.medium; account vCPU quota). Notes: default Fargate quota 6 vCPU vs 7 needed at full scale (estimate); Fargate ARM64 not in use1-az3, network/main.tf:22 does not exclude it (Terraform frozen → known limitation). Mandatory jars: hadoop, entity-resolution, spark-analytics, services; services jar needs `sbt -batch services/assembly` (not in packageJobs). Probes need unzip (not a listed check.sh prerequisite) → R-1. No executable bit needed (scripts run as `bash scripts/NAME.sh`). |
| 2 storage | about 17:48 | about 17:53 (agent duration 278 s) | done (orchestrator check: 2 test files M under modules/storage only; reports GraphQuerySuite 17:51:24 failures=0, EmulatorWiringSuite 17:51:25; revert runs shown in report). Unplanned deviation: `sed -n` twice (read-only). |
| 3a build | about 17:54 | about 17:56 (agent duration 73 s) | done (orchestrator check: .gitignore only, +13 −1; experiments/.DS_Store now ignored by .gitignore:113; 0 tracked .DS_Store). |
| 3b hadoop | about 17:56 | about 17:58 (agent duration 130 s) | done (orchestrator check: one test file M, +1 −1; LocalPipelineRunnerSuite report 17:57:11, 4/4). |
| I1b infra | 17:46:18 | about 17:55 (agent duration 533 s) | done (orchestrator re-run: shellcheck -x on env.sh, probes.sh, deploy.sh, archive-evidence.sh exit 0; check.sh and release-manifest.sh sha256 prefixes unchanged (a892d0652af2, 09f6106277d5); scripts diff only). Items 2, 3, M-03, M-06, L-06 done, runtime: untested. Item 4: not only the false alarm (2 SC2088 at guard-dangerous.sh:192 + 30 info) → gate 4 unchanged, hook shellcheck stays known limitation. Item 5: plan.sh safe and useful for E6: yes; cleanup.sh --list: no (exit 3 at terraform output, local state has no outputs) → E6 cleanup --list not proposed. |
| 4 service (wave closer) | about 18:00 | | running |
| F-1 review | about 18:00 | about 18:13 (agent duration 779 s) | done: REQUEST_CHANGES. P17-F-01 major (constitutional, symlink bypass of the guard; decision 1: not repaired in session 2, human decides in session 3); minors P17-F-02..F-09 and the widened hook shellcheck item. Saved verbatim: review-report-final-control.md. |
| W2 docs | 17:46:18 | about 18:09 (agent duration 1351 s) | done (orchestrator check: 5 files created, identifier grep 0 each; limits 60 rows: 55 verified, 5 unverified → deviation (unplanned)). Unplanned deviations (read-only): cd, tr, paste, one Bash grep on the named file .terraform.lock.hcl. Record discrepancies: G-06/G-08 note not in scripts/README.md at HEAD (Phase 14 gate says it was); the sts ban first appears in the Phase 14 work order. |
| W3 docs | about 18:15 | | running |
| 4 service (wave closer) | about 18:00 | about 18:23 (agent duration 1372 s) | done: targeted 22/22; revert proof; FULL CHECK 1 of 2: sbt check exit 0 (1151 s), marker 2026-11-23T00:20:15Z module 2336 contract 399; packageCheck ok. One test file changed (CompositionSuite). Unplanned deviation: sed -n twice, cd (read-only). |
| R-1 review | 18:22 | about 18:35 (789 s) | done: REQUEST_CHANGES. P17-A-01 major (rag); P17-A-02..A-07 minor. Saved verbatim: review-report-code.md |
| F-2 review | 18:22 | about 18:32 (604 s) | done: APPROVE for its slice (conditional on R-1 closing P16-L-10/L-11, which R-1 did not: P17-A-01). P17-F-10..F-16 minor; P16-A-08 still present. 5 listed prompt paths were moves (files-F-2 list was a superset incl. deletions). Saved verbatim: review-report-final-code.md |
| W3 docs | about 18:15 | about 18:34 (1108 s) | done (orchestrator check: README.md only, 445 lines, identifier grep 0). Unplanned deviations (read-only): cd, cat, tr, uniq, awk; read .git/config for the remote URL. Tag-checkout line form to be corrected at E11. Record gap: instructor-approvals.md has no "public at deadline" entry. For W5: runbook marks cleanup --list planned for E6 (I1b: not useful), runbook env.sh note stale, query-to-evidence suite naming vs T1, L2 bullet reconcile. |

## Step 5 code triage (about 18:38)
| Finding | Severity | Owner | Route |
|---|---|---|---|
| P17-F-01 | major | factory-engineer | NOT repaired (decision 1, constitutional): to the human in session 3 (amendment or known limitation; verdict stays REQUEST_CHANGES either way) |
| P17-A-01 | major | rag-engineer | repair-01-rag |
| P17-A-02, A-03, A-04, A-05, P17-F-12 | minor | rag-engineer | same pass (CLAUDE.md: all findings of one owner in one pass) |
| P17-A-06, A-07, F-04, F-05 | minor | infra-engineer | recorded (minor-only owner, no round) |
| P17-F-02, F-03, hook shellcheck item | minor | factory-engineer | recorded; constitutional, known limitation (decision 1) |
| P17-F-06, F-07, F-08, F-09 | minor | infra-engineer (Terraform) | status lines (Terraform frozen) |
| P17-F-10 | minor | storage-engineer / domain-engineer | recorded |
| P17-F-11, F-13, F-14 | minor | storage-engineer | recorded |
| P17-F-15 | minor | spark-engineer | recorded |
| P17-F-16 | minor | test-engineer | recorded |
| P16-A-08 | minor | rag-engineer | still present, proposed later (status line) |
Full checks: repair-01-rag is the only code repairer → it runs full check 2 of 2 and packageCheck.

| repair-01-rag | about 18:38 | | running |
| W4a docs | about 18:38 | about 18:50 (690 s) | done (orchestrator check: reports/final-report.md only; identifier grep 0; 14 `PENDING W4b` marks). Unplanned deviations (read-only): cd, tr, awk. Gate-relevant observations from the report: enrichment datasets reached only the manifest and local sample pipeline, never the AWS runs; two cache tables lack part of required provenance in Phase 16 samples; Phase 5 "8 conflict" vs Phase 14 459,149 conflict decisions listed as unverified. |
| W4b docs | about 18:52 | about 19:04 (742 s) | done (orchestrator check: reports/final-report.md, 0 PENDING W4b left per report, identifier grep 0). Unplanned deviations (read-only): cd, shell loops in link checks. |
| repair-01-rag | about 18:38 | about 19:14 (2116 s) | done: P17-A-01, A-03, A-04, A-05, F-12 RESOLVED per owner; A-02 doc only. Targeted rag 228/228, Golden 17/17; FULL CHECK 2 of 2: sbt check exit 0 (1069 s), marker 2026-11-23T01:13:43Z module 2343 contract 399; packageCheck ok. New additive codes: ConnectionUnconfirmed, AbsenceThirdName, AbsenceUnnamed, AbsenceTimeQualifier, AbsenceFoundPath. Unplanned deviation: one sed -n. Proposed later: edge=none check; RagIngestJob.scala:325 same message pattern. |
| 7 code re-check | about 19:16 | about 19:22 (366 s) | done: REQUEST_CHANGES. P17-A-01 PARTIAL (major remains: absence wording after an affirmative predicate, e.g. "have a path that is missing", still verified; `affirms` checks only the prefix, ClaimVerification.scala:1323-1326) → P16-L-10 NOT closed; P16-L-11 closed for recognised absence claims; A-02 (docs pending W5), A-03, A-04, A-05, F-12 RESOLVED; new P17-K-01, K-02 minor. F-2's conditional APPROVE no longer stands. No second code round in session 2 (decision 6.5): P17-A-01 to E1. Saved verbatim: review-report-02.md |
| T1 test inventory, attempt 1 | about 19:24 | about 19:26 (stopped) | MANUAL INTERVENTION (not a stall): the human stopped the agent by an accidental keypress at about 1m31s. It wrote only a 301-byte partial scratchpad file agent-report-test-inventory.md (kept, not used). `git status --short` after the stop: same file list as before T1 (no repository change). |
| T1 test inventory, attempt 2 | about 19:29 | about 19:34 (260 s) | done (fresh invocation at the human's request; report agent-report-test-inventory-2.md; no sbt run; source file count unchanged at 16, tests/ untouched). 16 of 16 spec categories covered; E5 suite: services QueryApiSuite (QueryApiSuite.scala:87); Spark: KnownGraphSuite, PipelineFixtureSuite. VerifierMonotonicSuite rated moderate (does not assert absence count > 0). Six `.fail` tests stand for open defects T-02..T-06. |
| W5 docs | about 19:36 | about 19:56 (1153 s) | done (orchestrator check: docs/emulator.md 3 lines changed, docs/evaluation.md unchanged, demo-script identifier grep 0; link check 834 path links 0 missing/ignored, 364 fragments ok). Unplanned deviations (read-only): sed -n, cd, zsh loops; one guard block and one built-in block, not retried. E5 additions proposed: UnsupportedClaimEndToEndSuite line, two guard probes, QueryApiSuite/AlgorithmsSuite/JsonlExportSuite/JobConfigSuite rows. Clip 02 not used (log does not confirm it). |

## Milestone 2 (20:05:45, before the documentation reviews)
Forecast: R-D1 and R-D2 (wave 1, about 15 min at observed pace), R-D3 (wave 2), doc repair (about 25), re-check (about 10), S-ID (about 15): end about 21:30 Sunday (estimate). Before 13:00: no tier 2 drop.

| R-D1 review | 20:06 | about 20:17 (658 s) | done: REQUEST_CHANGES. P17-D-01 major (threat model claims no agent read state; Phase 14 D-ER grep matched terraform.tfstate); P17-D-02..D-10 minor. Saved verbatim: review-report-docs-1.md |
| R-D2 review | 20:06 | about 20:19 (779 s) | done: APPROVE (files), P17-D-31..D-40 minor. Saved verbatim: review-report-docs-2.md |
| R-D3 review | about 20:20 | about 20:30 (558 s) | done: APPROVE (final report), P17-D-61..D-69 minor. Saved verbatim: review-report-docs-3.md |

## Step 8 documentation triage (about 20:32)
29 findings, all documentation-engineer, all Markdown, none needs a code change: P17-D-01 (major), D-02..D-10, D-31..D-40, D-61..D-69 (minor) → repair-01-docs (one pass, decision 15). Rulings: D-07 reword emulator.md lines 48, 187, 197 only; D-08 teardown form with a record PATH; D-04 counts 60/54/6.

| 9 repair-01-docs | about 20:33 | about 20:47 (817 s) | done: 29 of 29 RESOLVED per owner, Markdown only (orchestrator check: non-.md changes unchanged; emulator.md 3 lines). Same-defect fixes outside cited lines: README.md:319 replay form, runbook.md:88 source of 127 s. Unplanned deviations (read-only): sed -n, awk, diff, sort, uniq, tr; guard blocks redone in permitted forms. For E5 (session 3): `claude --version` and `bash scripts/archive-evidence.sh --local-only` are now `planned for replay (E5)` README lines. |
| 10 docs re-check | about 20:49 | | running |

Orchestrator routing for the two reviewer questions (R-D1 MANUAL ACTION), inside the work order, not asking the human: P17-D-07: decision 15 step 9 repairs every documentation finding; the "nothing else" limit applied to W5's touch-up, so the repair may reword exactly lines 48, 187, 197 of docs/emulator.md (no numbers). P17-D-08: use the existing form `not replayable after teardown (archived evidence: PATH)` with PATH = the committed record that shows the command was never needed or run (for example the runbook log or release record); never `none`. Recorded for the gate as orchestrator interpretations.

## Milestone 1 (18:21:32, before R-1)
Actual vs plan so far: code chain T0..4 64 min vs 230 planned (0.28); W1+W2 49 vs 135 (0.36); F-1 13 vs 60 (0.22). Scaled remaining: step 7 ≈ 19:10; W4b ≈ 19:35; T1 ≈ 19:20; W5 ≈ 19:51; doc reviews (two waves) ≈ 20:20; doc repair ≈ 20:42; re-check ≈ 20:48; S-ID → forecast end ≈ 21:05 Sunday (estimate). Before 13:00: no tier 2 drop.
| W2 docs | 17:46:18 | | running |
| L storage | 17:19:47 | about 17:27:40 (agent duration 473 s) | done (orchestrator check: `git status --short` shows no change by L; 39 rows: 38 verified, 1 unverified (SL-29 neptune.mergeRowCap, no AWS bound for openCypher), 0 not checked → verified-limits requirement at most deviation (unplanned)) |

L notes outside this work order (not started; to F-2 and proposed later): R1 QueryVectors reads only the first page (max 100 per page; config checks only queryTopK > 0; today 30); R2 Neptune 20 s is client-side only, server default 120 s (neptune_query_timeout not set, Terraform frozen); R3 comment BulkLoadRequests.scala:10 claims all-or-nothing loads (docs must not claim that); R5 unused S3 Vectors write-rate constants.
| N incident | 17:19:47 | 17:23:08 | done (orchestrator check: docs/incidents/INC-P15-F-poison-message.md only; identifier grep 0; no stop-hook block) |
| W1 docs | 17:19:47 | about 17:37 (agent duration 1038 s) | done (orchestrator check: 3 files created, identifier grep 0 each; link check 23 targets ok, 35 fragments ok per report). Unplanned deviations (read-only): one Bash grep on the named file infra/terraform/environments/course/outputs.tf (a named file, not the folder; no state file matched), and cd, sed -n, cat, awk, sort -u, tr. G-22 strip now at scripts/smoke-test.sh:333. W1 wrote 9 L2 statements in docs/api.md section 9 at 50ba087 → W5 reconciles with step 1. |
| X1 experiment | 17:19:47 | about 17:24 (notice after the 17:23:08 stamp) | done (orchestrator check: 3 files M under experiments/, none under manifests/raw/derived/evaluation; shellcheck exit 0) |

X1 risk noted for R-1: experiments/results-summary.md may still use the template's old wording for A (out of X1's scope); goes to R-1 and the proposed-later list.

## Approval prompts seen
- none so far.

## Commands outside the list (orchestrator)
- `head -n 0` attempt (failed harmlessly, illegal count); `sed -n` attempt piped to `head -n 0` (no output used). Both read-only.
