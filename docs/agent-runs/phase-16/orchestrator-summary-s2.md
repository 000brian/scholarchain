SESSION 2 RESULT: NOT_READY

Rule applied: rule 2 — a tier 1 requirement (3r L2, decision 8.1) has status `defect (P16-K-01)`: a major finding raised by the step 10 re-check, open because decision 15 allows no second repair round in session 2. No critical finding and no major finding marked "interface: yes" is open. Every other tier 1 item is `done`; every final check passed.

# Phase 16 — orchestrator summary, session 2

- Session: 2 (unattended), Nov 21 2026, start-check stamp 19:14:15, end stamp 22:54:53 (local).
- HEAD at start and end: 9da45ab616a764e449f74f25feca85ed77d6dfe4 (no commit in session 2; all work uncommitted).
- Work order: docs/agent-runs/phase-16/prompt.md, sha256 c75cc5f895faed13880a69ec285231c5498fd50924c7b875fe7e02cce1707dc8.
- Working record with per-step detail: orchestrator-notes-s2.md. Reports: see "Reports" below.

## Start checks (decision 1)
| Check | Result |
|---|---|
| HEAD / tree | 9da45ab… exactly; only `?? docs/agent-runs/phase-16/` — pass |
| MCP tools visible | aws-docs, terraform, aws-api (unused), Claude Docs, easel; no Grep/Glob tool (Bash grep fallback) |
| terraform version / shellcheck / jq | v1.17.1 / 0.11.0 / `jq empty config/course.json` ok |
| course validate (baseline) | Success! |
| Phase 5 ER run folder | absent → group 1 reduced plan |
| Phase 14 er-v2 local folder (/var/folders/…/er-out/run=23b913cc…) | present (queue/key differ from Phase 5; gold labels cannot be joined, step 2) |
| Gate mapping (check 5) | every Phase 16 item maps to decision 21's table; Phase 11 S-5/U-11 and the Phase 12/13 "clean up after Phase 16" notes reported (not Phase 16 items) |

## Scope forecasts (decision 2)
| Forecast | Stamp | Basis | Result |
|---|---|---|---|
| 0 (before step 1) | 19:14:15 | decision 4 estimates only | Scala ≈04:03, eval ≈04:00 → B-02, G-18 kept |
| 1 (before step 3) | 20:03:57 | steps 1-2: 48 min actual / 105 planned (0.457) | Scala ≈23:18 scaled → no drop |
| 2 (before step 7) | 21:19:06 | steps 1-6: ≈112 / 340 (0.33) | Scala ≈22:22 scaled (≈00:29 unscaled); eval ≤≈00:40 unscaled → no drop |
No tier 2 item was dropped by the milestone rule.

## Steps (decision 4)
Durations are task-usage figures (estimate where noted).
| Step | Agent | Status | Duration (planned) | Report |
|---|---|---|---|---|
| D | storage-engineer (read-only) | done | 613 s (45 min) | agent-report-storage-D.md |
| 1 | build-engineer | done | 1911 s (70 min) | agent-report-build.md |
| X1 | experiment-engineer | done | 2034 s (120 min) | agent-report-experiment-X1.md |
| I1 | infra-engineer | done (hook waiver, planned deviation 8) | 1556 s (90 min) | agent-report-infra.md |
| R-2 | code-reviewer | done (REQUEST_CHANGES) | 745 s (30 min) | review-report-b.md |
| 2 | entity-resolution-engineer | done | 961 s (35 min) | agent-report-er.md |
| 3 | storage-engineer | done | 1780 s (150 min) | agent-report-storage.md |
| R-1a | code-reviewer | done (APPROVE) | 548 s (30 min) | review-report-a1.md |
| 4 | rag-engineer | done | 816 s (45 min) | agent-report-rag.md |
| 5 | spark-engineer, then hadoop-engineer | done | 560 s + 397 s (25 min) | agent-report-spark.md, agent-report-hadoop.md |
| 6 | test-engineer | done | 227 s (15 min) | agent-report-test.md |
| X2 | experiment-engineer | done | 1767 s (60 min) | agent-report-experiment-X2.md |
| 7 | service-engineer (wave closer) | done | 2400 s (90 min) | agent-report-service.md |
| W1 | documentation-engineer | done | 505 s (40 min) | agent-report-docs-W1.md |
| R-3 | code-reviewer | done (REQUEST_CHANGES) | 527 s (25 min) | review-report-c.md |
| R-1b | code-reviewer | done (REQUEST_CHANGES) | 420 s (25 min) | review-report-a2.md |
| 8 | orchestrator triage | done | — | orchestrator-notes-s2.md "Step 8 triage" |
| 9 | repair round: rag, experiment, docs consumer re-check, build (last, full check) | done | 390 s, 618 s, 77 s, 1176 s (60 min) | repair-01-rag.md, repair-01-experiment-engineer.md, repair-01-docs-recheck.md, repair-01-build-engineer.md |
| 10 | code-reviewer re-check | done (7/7 RESOLVED; new major P16-K-01) | 390 s (15 min) | review-report-02.md |
| 11 | orchestrator final checks and this summary | done | — | this file |
No repair-00 was needed (no critical or interface-major finding before a consumer started). No step stalled.

## Requirements (decisions 5-15), status and tier
| Item | Tier | Status | Evidence |
|---|---|---|---|
| L-09 (RetrievalEvalJob in PackageCheck; buildSelfTest mirrors gate 8) | 1 | done | build report; PackageCheck.scala:35; check.sh needs no change |
| EmulatorTest / RequiresEmulator / emulatorTest / forked trust store | 1 | done | build.sbt:101-158, :409-462, :711 (alias now sequential, P16-A-02) |
| Emulator Dockerfile | 1 | done; deviation (unplanned): documented `ENV EMULATOR_BIND_ADDRESS=0.0.0.0` used instead of the work order's `-b 0.0.0.0` wording (rule "exactly the documented one"; P16-B-01 repaired) | docker/emulator/Dockerfile:40-47 |
| B-02 packageCheck bad-jar tests | 2 | done | BuildSelfTest.scala:126-174 |
| FECD-1 diagnosis (step D) | 1 | done | agent-report-storage-D.md |
| FECD-1 storage side (bounded graph-read retry) | 1 | done | GraphRetrySuite; fixed in code, tested offline, not validated live |
| FECD-1 breaker side (stale-slot outcomes; availability-only counting) | 1 | done | GraphBreakerSuite; fixed in code, tested offline, not validated live |
| 3r L1 (resource-limit → bounded search, typed class) | 1 | done | NeptuneAdapters.scala:348-396; interface additive |
| 3r L2 (absence claims vs search depth/completeness) | 1 | defect (P16-K-01) | P16-A-09 resolved in repair; P16-K-01 open (review-report-02.md) |
| path-abstention code `path-query-bounded` | 1 | done | Abstentions.scala; PathAbsenceSuite; ReadRoutesSuite |
| G-28 root-cause write-up | 1 | done — "cause narrowed to" | agent-report-rag.md |
| Answer-report interface table | 1 | done (P16-A-10 column list corrected in repair-01-rag.md) | agent-report-rag.md |
| N-1, N-2 | 1 | done (Policy.erV1 kept as @deprecated forwarder; 13 uses in tests/contracts) | agent-report-er.md |
| M3, M4 | 1 | done | Phase16RepairSuite |
| F8, F9 "expected to fail" marks | 1 | done (both removed; contracts er 231/231) | agent-report-test.md |
| Group 1 run plan | 1 | done — reduced plan (no E4 EvalMain; no --compare-decisions) | agent-report-er.md |
| G-18 | 2 | done | Policy.evaluatedWith |
| Emulator wiring: safety contract, forEmulator, static credentials, outcome classes, result lines, EmulatorAdaptersEmulatorSuite, config loader test | 1 | done (compile only; runtime at E14) | agent-report-storage.md; R-1a safety table |
| Emulator worker demonstration (EmbeddingWorkerEmulatorSuite) | 1 | done (compile only; runtime at E14) | agent-report-service.md |
| L-10, D-02, D-03, StartLoaderJob throttle | 2 | done | agent-report-storage.md |
| Storage itest cleanup helper | 2 | not built — proposed later per decision 6.5's own rule (needs delete permission) | agent-report-storage.md |
| FECD-2 | 2 | done — not a defect (existing test) | agent-report-service.md |
| H-04, H-05, H-06, G-23, route time limits (readDeadlineMillis 10 s → 504 READ_TIMEOUT) | 2 | done | agent-report-service.md |
| K-01 | 2 | open — confirmed a Terraform guard owned by infra-engineer (phase-15 orchestrator-summary-s2.md:117); routed, not built | agent-report-service.md |
| C-05 | 2 | done | JsonlExportArgsSuite |
| G-19 | 2 | done | LocalPipelineRunnerSuite |
| Spark suite table (group 2) | 1 | done (43 rows: 37 independent, 4 mixed, 2 property) | agent-report-spark.md; experiments/evaluation/spark-suite-table-v1.csv |
| I1: OPERATIONS.md, config/emulator.json, env.sh, setup.sh, probes.sh, README section | 1 | done | agent-report-infra.md |
| L-07, L-12, L-14, L-15, FECD-4, G-04 | 2 | done | agent-report-infra.md |
| check.sh side of L-09 | 1 | not needed (step 1: no check.sh change) | agent-report-build.md |
| Evaluation suite + evaluate.py (retrieval, performance, factory, reproduce, ratings-sheet, answers, graph, er) + fixtures | 1 | done (P16-B-02, B-04, B-05, C-01 repaired) | X1, X2, repair-01-experiment-engineer.md |
| Rubric + rating-sheet generator | 1 | done | rubric-v1.md; `ratings-sheet` |
| AB-4, J-03, L-08 + intended-differences list | 2 | done | intended-differences-v1.csv |
| docs/evaluation.md methods draft | 1 | done | agent-report-docs-W1.md; repair-01-docs-recheck.md |
| docs/emulator.md draft | 1 | done | agent-report-docs-W1.md |
| FECD-3, FECD-6, FECD-8, FECD-9 and A/B limitations carried into the evaluation | 1 | done (threats T9, T11-T14; P16-C-03 notes T11 omissions) | docs/evaluation.md |

## Items earlier gates sent to Phase 16
| Item | Status |
|---|---|
| FECD-1 diagnosis and fix; G-28; 3r L1, L2 | done / done / done / defect (P16-K-01) |
| N-1; N-2 | done |
| L-09 | done |
| L-07, L-10, L-12, L-14, L-15; FECD-2, FECD-4; H-04, H-05, H-06; AB-4, J-03, L-08 | done |
| K-01 | open (Terraform guard; infra-engineer; not built) |
| FECD-3/6/8/9, A/B limitations | carried into docs/evaluation.md |
| Cost Explorer figure for Phase 15 | E0 (session 3) |
| M3, M4; F8, F9 | done |
| Storage retries and cleanup | retries done; cleanup helper proposed later |
| n-1, n-2, n-4; S-8; N-3, N-4, weak contract tests, test-plan items, IdentityCrossRecordSuite, T-02..T-06; Phase 11 minors; custom metrics | not built — status lines written (all "still present", file:line in the owner reports); B-07 "partly resolved in 98b712a"; D-04, D-05 `status not checked (owner not run)` |
| Route time limits | done |
| C-05, B-02 | done |
| D-02, D-03, G-04, G-18, G-19, G-23 | done |
| U-12 limit note | Phase 17 |
| Attempt-07 live benchmark | not run (input missing) |

## Findings
| ID | Sev | Interface | Owner | Status |
|---|---|---|---|---|
| P16-B-01 | major | no | build-engineer | RESOLVED (review-report-02) |
| P16-B-02 | major | no | experiment-engineer | RESOLVED |
| P16-B-03 | minor | no | infra-engineer | open (recorded) — probes.sh:195 unbound var on an earlier refusal |
| P16-B-04 | minor | no | experiment-engineer | RESOLVED |
| P16-B-05 | minor | no | experiment-engineer | RESOLVED |
| P16-A-01 | minor | no | orchestrator | handled at commit: AKIA example key expected in the staged-diff scan |
| P16-A-02 | minor | no | build-engineer | RESOLVED |
| P16-A-03 | minor | no | storage-engineer | open (recorded) — InMemoryGraph TimeLimit untested |
| P16-A-04 | minor | no | storage-engineer | open (recorded; unplanned deviation) — forEmulator uses its own builders |
| P16-A-05 | minor | no | storage-engineer | open (recorded) — lib.sh comparison test partial |
| P16-A-06 | minor | no | storage-engineer | open (recorded) — D report wording (429 rows) |
| P16-A-07, P16-A-13 | minor | no | process | recorded — commands not on the list (below) |
| P16-A-08 | minor | yes (consumer side) | rag-engineer | not hit (confirmed by R-1b); latent; proposed later |
| P16-A-09 | major | no | rag-engineer | RESOLVED |
| P16-A-10 | minor | no | rag-engineer | RESOLVED (report correction) |
| P16-A-11 | minor | no | hadoop-engineer | open (recorded) — doc comment order |
| P16-A-12 | minor | no | service-engineer | open (recorded) — Main.scala:107-108 untested |
| P16-C-01 | major | no | experiment-engineer | RESOLVED |
| P16-C-02 | minor | no | human | blocked-on-human — intervals over dependent units (clip 01 attempts, D-query explain rows) |
| P16-C-03 | minor | no | documentation-engineer | open (recorded) — T11 omits FECD-7/F limitations, FECD-4 |
| P16-C-04 | minor | no | documentation-engineer / experiment-engineer | open (recorded) — no tool summarizes completed ratings/dblp column |
| P16-C-05 | minor | no | documentation-engineer | open (recorded) — T8 overstates the diagnosis |
| P16-C-06 | minor | no | human | blocked-on-human — architecture detail level of docs/emulator.md for the Teams post |
| **P16-K-01** | **major** | no | rag-engineer | **open** — absence clause not tied to the search's window or to a cited found path |
| P16-K-02 | minor | no | documentation-engineer | open — docs/emulator.md:24, :178 still say the Dockerfile is "under repair" |
| P16-K-03 | minor | no | documentation-engineer | open — docs/evaluation.md:166 cites S/repair-01-experiment-engineer.md, missing from the :376 copy list |

## Fix-status lines
Every code fix in this session: fixed in code, tested offline, not validated live (FECD-1 storage and breaker, L1, L2/P16-A-09, N-1, N-2, M3, M4, partial M2, G-18, L-10, D-02, D-03, StartLoaderJob throttle, C-05, G-19, H-04, H-05, H-06, G-23, route time limits, L-07, L-12, L-14, L-15, FECD-4, G-04, B-02, L-09, P16-A-02).

## Runtime lines
- runtime: untested — experiments/evaluation/evaluate.py (evaluate-v1-r1; fixtures first run at E1); experiments/derived/common.py, emr_capacity.py, queue_worker.py; scripts/emulator/env.sh, lib.sh, setup.sh, probes.sh; changed scripts check.sh, cleanup.sh, deploy.sh, lib/identity.sh, run-bounded-pipeline.sh, run-experiment.sh; docker/emulator/Dockerfile.
- Compiled only (runtime at E14): EmulatorAdaptersEmulatorSuite, EmbeddingWorkerEmulatorSuite (`sbt storage/EmulatorTest/test`, `sbt services/EmulatorTest/test`).
- No entry point earned `runtime: tested in phase 16`.

## Planned deviations (decision 16)
1 experiment-engineer does not run the suite — applies. 2 no live system — applies. 3 fixes not validated live — applies. 4 benchmark labels from Phase 7; attempt-07 not run — applies. 5 ER gold metrics on er-v1; er-v2 not claimed (compare impossible: er-v1 decisions gone) — applies. 6 no LLM judge, one rater — applies. 7 no session 1 — applies. 8 stop-hook waiver — applied once (I1, below). 9 auto mode — applies.

## Unplanned deviations
1. Dockerfile binds via documented `ENV EMULATOR_BIND_ADDRESS=0.0.0.0` instead of the work order's `-b 0.0.0.0` wording (orchestrator triage; header records it) — for the gate.
2. P16-A-04: emulator clients built by their own builders, not through `AwsSdkClients.build`'s `credentials` parameter (behavior meets intent; production builder unchanged).
3. Step 2 scope extension: partial fix of Phase 5 M2 (fractional outcome count) so that F9 passes.
4. Commands outside the list (below), all read-only or blocked.
5. Orchestrator sent a routing note (P16-A-08) to the running rag agent via SendMessage (no new task).

## Hook waivers (planned deviation 8)
- I1 (infra-engineer): returned BLOCKED-ON orchestrator, "stop hook: in-progress files of build-engineer (build.sbt, project/PackageCheck.scala, project/BuildSelfTest.scala)". Conditions checked: every item done; shellcheck (4 emulator + 6 changed scripts) exit 0, `jq empty config/emulator.json` 0, `terraform fmt -recursive -check` 0 re-run by the orchestrator; validate Success (agent); paths all step 1's (running then); no other open item (the L-09 check.sh item became `not needed`). Recorded `done`.
- D, X1, X2, W1 and the docs re-check were not blocked.

## Hook blocks seen (unsafe or disallowed actions prevented)
- Step 3: a stray `python3 - …; perl -0pi …` line (in-place edit form) blocked whole; not retried.
- Step 1: one read-only grep naming trust-store/CA file names blocked; not retried.
- X2: one grep blocked; Read tool used instead.
- Orchestrator: one Bash redirect to a non-literal path (`"$R/…"`) blocked; redone with literal paths.
No approval prompt was seen during the session.

## Commands used that are not on the work order's list (all read-only; none wrote a repository file)
- Orchestrator: one `date -u` (21:09); `ls -lT` on files (allowed form ls); bounded wait loops on report files (the named exception), including on not-yet-existing report paths while reviewers ran.
- D: `cd` prefix, `tr`, `for` loops. 1: `ps`. I1: `cd`, `cat`, `sed -n | head -0`, `awk`, `pwd`. X1: `cd`, `cat`, `awk` ×2, `sed -n`, `tr`, `git ls-files | xargs shasum`. 2: (none reported beyond the list). 3: `cd`, `sed` ×2, `unzip -l`. 4: `cd`, `sed -n`. 5a/5b: none reported. 6: `cd`, `cat`, `sed -n`, `uniq`, `for`. X2: `cd`, `awk`, `tr`, failed `cat | head -n 0`. 7: `cd`, `find | xargs wc -l`, `sed -n`, `sleep 15` loops for its background sbt check. W1: `cd` ×3, `for` around `git log`, `echo`. repair-01-rag: `cd`. repair-01-docs-recheck: `sed -n`. repair-01-build: `find … -newer`.

## Validation evidence (final checks, step 11, on the final tree)
| Check | Result |
|---|---|
| `terraform fmt -recursive -check infra/terraform` | exit 0 |
| `terraform -chdir=infra/terraform/environments/course validate` | Success! (same as baseline; no change under infra/terraform/) |
| `shellcheck -x -P SCRIPTDIR` on all 15 scripts/**/*.sh, load-tests/query-load.sh, 5 experiments/collectors/*.sh | exit 0 |
| `jq empty` on all 52 changed JSON files (config/emulator.json, suite-v1.json, every fixture JSON) | exit 0 |
| `head -n 5 .claude/.last-test-run` | timestamp=2026-11-22T04:40:57.276316Z, module_tests=2285, contract_tests=399 (newer than every changed source file) |
| `git status --short --ignored evidence infra/terraform/environments/course` | evidence/, .terraform/, local.auto.tfvars, plans/, terraform.tfstate(.backup) all ignored (`!!`) |
| `git status --short` | 61 modified, 17 untracked paths (listed by owner below) |
| Full `sbt check` runs | exactly three: step 1 (19:45:28, 2195+399), step 7 wave closer (21:57:30, 2282+399), repair round build-engineer (22:40:57, 2285+399); all exit 0; packageCheck ok after each |
| Identifier pre-scan (orchestrator) | twelve-digit runs in changes: only 123456789012 and 210987654321; twelve zeros: none; AKIA: only the AWS example key at EmulatorWiringSuite.scala:340, :393 (P16-A-01); `arn:aws:s3:::` template from a shell variable at scripts/emulator/setup.sh:336 (no account); no amazonaws/s3:// in new files |

## Interface checks
Every consumer ran its INTERFACE CHECK with no mismatch (3, 4, 7, X2, W1). Fixed interfaces compared by the orchestrator at producer completion: build.sbt (tag, configuration, alias, trust store), storage emulator code (env var names, endpoint, results-SUITE.jsonl, demonstration line) — match. Repair-round interface change (experiment output semantics) re-checked by documentation-engineer in the same round; verified by step 10 (K-02, K-03 are leftovers).

## Costs
No paid call, no AWS call, no model call, no emulator or Docker run in session 2. Cost $0 (estimate: no metered action taken). Provider totals are read at E0.

## E1 command list (session 3, after E0; each compared exactly with its expected files, run.json excluded)
python3 -I experiments/evaluation/evaluate.py retrieval --fixtures
python3 -I experiments/evaluation/evaluate.py performance --fixtures
python3 -I experiments/evaluation/evaluate.py factory --fixtures
python3 -I experiments/evaluation/evaluate.py reproduce --fixtures
python3 -I experiments/evaluation/evaluate.py ratings-sheet --fixtures
python3 -I experiments/evaluation/evaluate.py answers --fixtures
python3 -I experiments/evaluation/evaluate.py graph --fixtures
python3 -I experiments/evaluation/evaluate.py er --fixtures
Real-run commands per evaluation: agent-report-experiment-X2.md lines 121-131 (placeholders E5_SBT_LOG, FILE, PROVENANCE, ATTEMPT filled at run time). Order note: E6-performance before E10 regenerates experiments/derived/out. E11 needs E7's demo answers (attempt-06 and clip 01 hold no answer text). Current pins: evaluate.py ba42ad4dba868ab1aa2367ddb9437f841d59eb702e3444cd8f5494416208ecae; suite-v1.json b4e44b299473dc7ec48ea3dbff1f2762bef2471021552ffe971a9ce22d46a3db (unsigned until E3).

## Repeat rules triggered for session 3
- modules/spark, modules/hadoop changed → E5 runs at the final HEAD.
- modules/storage, modules/services, modules/rag, config/emulator.json, scripts/emulator, docker/emulator changed → E14 at the final HEAD; any P16-K-01 repair (modules/rag) also triggers E14 and E2 repeats if made after them.

## Phase 17 items (unchanged from decision 19) plus proposed later from this session
Proposed later: RetrievalEvalJob options check in check.sh gate 8 (infra); itest cleanup helper (storage); trace-wrapper override of runClassified (P16-A-08, rag); f2 resolver `.toOption` (rag); "Within 6 hops, no path …" fail-closed gap (rag); remove Policy.erV1 forwarder and migrate 13 contract uses, N-4 rename (ER, test); M2 cache-hit part; EvalMain gold-pair-by-identity mode; ER blocking-settings check; Settings.fingerprint in run report (hadoop); J-03 second part; counting repeated BLOCKED-ON episodes once; ratings summarizer subcommand (P16-C-04); fixture mode for suite checks (B-04); K-01 Terraform guard (infra).

## Manual actions for the human (decision 20), from the repository root, zsh, one line each
1. Copy the reports:
   R=/private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/ee36fc30-b7d0-4ca7-9ec6-7f86622ec5d3/scratchpad/phase16-reports; rsync -a --ignore-existing --exclude runbook-log.md --exclude clock.txt "$R/" docs/agent-runs/phase-16/
2. Executable bits: none needed (the runbook calls `bash scripts/emulator/setup.sh` and `bash scripts/emulator/probes.sh`; env.sh and lib.sh are sourced; existing scripts carry no bit).
3. Commit:
   - git add -A
   - git diff --cached --name-only
     Expected, by owner: build-engineer: build.sbt, project/PackageCheck.scala, project/BuildSelfTest.scala, docker/emulator/Dockerfile · entity-resolution-engineer: modules/entity-resolution/** (5 main, 8 test modified, Phase16RepairSuite.scala new) · storage-engineer: modules/storage/** (5 main, 3 test modified; FailureMasking.scala, GraphRetrySuite.scala, emulator/ (6 files) new) · rag-engineer: modules/rag/** (7 main modified, PathAbsenceSuite.scala new) · spark-engineer: modules/spark/** (2) · hadoop-engineer: modules/hadoop/** (2) · test-engineer: tests/contracts/…/EvalReportRound3AdversarialSuite.scala · service-engineer: modules/services/** (9 main, 4 test modified; GraphReads.scala, EmbeddingWorkerEmulatorSuite.scala, GraphBreakerSuite.scala, RouteTimeLimitSuite.scala new) · infra-engineer: config/emulator.json, scripts/emulator/ (OPERATIONS.md, env.sh, lib.sh, setup.sh, probes.sh), scripts/README.md, check.sh, cleanup.sh, deploy.sh, lib/identity.sh, run-bounded-pipeline.sh, run-experiment.sh · experiment-engineer: experiments/README.md, experiments/derived/common.py, emr_capacity.py, queue_worker.py, experiments/results-summary-template.md, experiments/evaluation/** (evaluate.py, suite-v1.json, rubric-v1.md, intended-differences-v1.csv, spark-suite-table-v1.csv, fixtures/**) · documentation-engineer: docs/evaluation.md, docs/emulator.md · human: docs/agent-runs/phase-16/** (prompt.md and the copied reports). Any other path: unstage or explain before committing.
   - git diff --cached --name-only | grep -nE '^(evidence/|infra/terraform/environments/course/plans/)|\.tfplan$|\.tfstate|/target/'   (must print nothing)
   - git diff --cached | grep -nE 'AKIA|ASIA|sk-ant|pa-[A-Za-z0-9_-]{20,}|[0-9]{12}'   Expected matches: the placeholders 123456789012 and 210987654321; digit runs inside sha256 hashes in reports and the suite; the AWS documentation example key `AKIAIOSFODNN7EXAMPLE` at modules/storage/src/test/scala/org/scholarchain/storage/emulator/EmulatorWiringSuite.scala:340 and :393 (P16-A-01, deliberate test input, not a secret); the scan line inside docs/agent-runs/phase-16/prompt.md. A run of twelve zeros is not expected: remove it before committing.
   - git commit -m "phase-16 session 2: fixes (FECD-1, L1, L2, L-09, N-1/N-2, M3/M4), emulator wiring, evaluation suite and tools, docs drafts (build, er, storage, rag, spark, hadoop, test, service, infra, experiment, documentation)"
4. commit.txt after the gate: git log --format='%H %s' 9da45ab616a764e449f74f25feca85ed77d6dfe4..HEAD
5. Stop `caffeinate -is` with Ctrl-C.

## Reports (scratchpad phase16-reports/)
agent-report-storage-D.md, agent-report-build.md, agent-report-infra.md, agent-report-experiment-X1.md, agent-report-er.md, agent-report-storage.md, agent-report-rag.md, agent-report-spark.md, agent-report-hadoop.md, agent-report-test.md, agent-report-experiment-X2.md, agent-report-service.md, agent-report-docs-W1.md, review-report-b.md, review-report-a1.md, review-report-c.md, review-report-a2.md, repair-01-rag.md, repair-01-experiment-engineer.md, repair-01-docs-recheck.md, repair-01-build-engineer.md, review-report-02.md, checkpoint.md, orchestrator-notes-s2.md, orchestrator-summary-s2.md, ref-emulator-docker-doc.md, diff-R-1a.txt, diff-R-1b.txt, diff-R-2.txt, diff-R-3.txt, files-R-1a.txt, files-R-1a-status.txt, files-R-1b.txt, files-R-1b-status.txt, files-R-2.txt, files-R-2-status.txt, files-R-3.txt. Also r01-check.log and r01-packagecheck.log one level up in the scratchpad (build-engineer's logs; not copied by the rsync line; optional).

## Open question for the human (one)
How to route P16-K-01 (major, rag L2, pre-existing since step 4, not caused by the repair)? Recommended: authorize one session 3 repair `repair-E00-rag` (finding ID P16-L-01 per decision 17) before E2 and E14, with rag-engineer running the full `sbt check` as wave closer and code-reviewer re-checking it, so that E2, E5 and E14 run at the final HEAD. Alternative: accept it as a known limitation (L2 then stays `defect (P16-K-01)` and the phase result cannot exceed TIER1_NOT_MET).
