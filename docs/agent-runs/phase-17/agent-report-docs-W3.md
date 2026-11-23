# agent-report-docs-W3 (documentation-engineer, Phase 17 session 2, step W3)

```text
STATUS: COMPLETE
OWNER: documentation-engineer
WORK ORDER: docs/agent-runs/phase-17/prompt.md (sha256 c22adfc57c7fde0cae63c8520393c2c81cd12280df7f5df773e70c2e2ec8ac24, verified with shasum at start and end), step W3, decision 13; HEAD 50ba087ecd33178eaa8ccc69286b9de85e8a8146 (verified) plus uncommitted Phase 17 work
FILES CREATED:
  /Users/brian/Developer/repos/scholarchain/README.md (445 lines, sha256 698986cba564eb34c15bb3c9f1ba8b1888dbf58643388b02435806e1e99eec81)
  (scratchpad) phase17-reports/agent-report-docs-W3.md (this report)
FILES MODIFIED: none in the repository (W1 and W2 documents not touched)
COMMANDS RUN: read-only only: shasum, git rev-parse, git status --short, git log -1, git show (--stat and FILE at commit), git diff --stat (between two commits), git merge-base --is-ancestor, git ls-tree, git ls-files, git check-ignore -v, ls, wc, head, tail -n, cut, grep. Read tool for files (including .git/config for the remote URL). No sbt, no script (not even --help), no python, no terraform, no network, no AWS, no MCP call. Commands outside the Phase 17 list: see RISKS.
TEST RESULTS: none run (documentation step; W steps never run sbt)
VALIDATION RESULTS: interface check passed (I1a, I1b, W2, W1; below). Link check: 222 link occurrences, 176 of them to repository files or folders (fragments included); every unique target (table below) exists and none is ignored; all tracked except 9 created in Phase 17 (W1, W2, N). 55 unique fragment links (21 README-internal, 34 into other files), all match a heading. External link: 0 markdown links (the repository URL is plain text). PENDING entries: 6. Identifier scan of README.md: no match.
ASSUMPTIONS:
  - Group 6 data covers phases 00 to 15 and both amendments only (totals.csv "phases_in_population 18"; docs/evaluation.md 10.6), so the catalog's "Phases it ran in" column gives the group 6 phases first and then "records also" from report file names in the phase folders (Phase 16 files; Phase 17 reports present in the session scratchpad). deploy-engineer, documentation-engineer and incident-responder have no group 6 execution row; their runs are taken from report files (phase-14 and phase-15 agent-report-deploy.md; phase-16 agent-report-docs-*.md; Phase 17 step N).
  - Example instructions are verbatim from phase work orders, except incident-responder (decision 10 text put into the "Use the ... agent to" form, said so in the cell) and dependency-updater (labelled illustrative: no phase invoked it).
  - The per-agent verification commands are runnable forms of each agent's gate in docs/agents.md (module tests `sbt -batch "PROJECT/test"`, the validator, `datasets/scripts/validate.sh`, terraform fmt and validate, the evaluate.py reproduce fixture); code-reviewer, documentation-engineer and incident-responder have no deterministic command, and the cells say so instead of inventing one.
  - Replay lines: the release-manifest real run uses `planned for replay (E10)`, as W2 did in docs/runbook.md (the results rule names E3 to E6; E10 is the run itself). `claude --version` uses `replayed earlier (phase 17, start check 6, docs/agent-runs/phase-17/tool-versions-p17.txt)` (the orchestrator printed it at the start of session 2; the file reaches the repository with the human's copy).
  - Tag-checkout line written as `deferred to E14 (tag checkout; the release-candidate SHA checkout planned for replay (E5))`: the results rule's exact form ends "replayed at E5, exit CODE", which cannot be true before E5; E11 rewrites it to the exact form with the code.
  - Clone folders are written `/tmp/p17-clone-STAMP` and `/tmp/p17-replay-STAMP`, with STAMP defined in the README as a UTC stamp such as `20261123T1700Z` (contains T and Z, review F-1 note on P17-F-05).
  - The repository URL comes from .git/config (remote origin), written as the HTTPS form; D01 states private until the deadline, then public, citing the Phase 17 work order (decision 18, E12), because docs/agent-runs/instructor-approvals.md does not record that request (see RISKS).
  - Not-yet-existing documents (reports/final-report.md, docs/demo-script.md, docs/agent-runs/phase-17/*) are written as plain text paths with the step that creates them, not as links, so the link check stays clean; W5 turns them into links.
RISKS:
  - Unplanned deviations (all read-only, listed for the gate): `cd <repo> &&` once (the first command); one `cat` of docs/agent-runs/phase-08/README.md (one line); `tr`, `uniq` and `awk` in the read-only link-check and fragment-list pipelines; a `for`/`while` loop over grep output; the Read tool on .git/config (not on the list, not a forbidden file). No file was written by any of them.
  - Record discrepancy: the Phase 17 work order says the instructor asked for the repository to be made public at the deadline "(in docs/agent-runs/instructor-approvals.md, if recorded there)"; that file (40 lines, read in full) has no such entry. D01 cites the work order. The human may add the entry.
  - Consistency with W2 for W5: docs/runbook.md marks `cleanup.sh --list` as `planned for replay (E6)`; the README marks it `not replayable after teardown`, following I1b item 5 ("safe and useful for E6: no", exit 3 at terraform-outputs before any listing). docs/runbook.md section 11 and the env.sh note still describe env.sh at 50ba087 (I1b changed it); the README describes the scripts as they are now. W5 reconciles both documents.
  - The E5 replay list grows: the catalog adds one `sbt -batch "PROJECT/test"` line per module (core and core2_13 together, hadoop, entityResolution, storage, rag, services, contractTests), `datasets/scripts/validate.sh`, the eight evaluate.py fixture subcommands, terraform init/validate and the Phase 02 exercise git lines. The orchestrator may merge or trim them for E5; a line not replayed becomes `not replayed in phase 17 (REASON)` at E11.
  - The query-and-evidence suite named in the README is `GoldenExamplesSuite` (docs/api.md: every frozen example checked against the running server); T1 names the official services end-to-end fixture suite; W5 aligns the README with T1.
  - The dangerous-command probe outcome ("the guard blocks it") is the work order's statement, not observed by this agent.
MANUAL ACTION REQUIRED: none for W3. At E11: fill every `planned for replay (ENN)` line (replay table below), the tag-checkout line's exit code, the 6 PENDING entries (read-only IAM user PENDING E8, video PENDING E11 twice, release manifest PENDING E10, retained-item removal PENDING E8 twice).
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: W4a (final report sections 1 to 12); W5 (demo script, link the final report and demo script from README, reconcile with I1b, T1 and step 1, full link check); R-D2 reviews README.md
```

## INTERFACE CHECK (producers' files read, file:line)

| Producer item used in README | Exists as handed over? | Where checked |
|---|---|---|
| I1a: `bash scripts/release-manifest.sh --clone PATH --marker PATH --out-dir PATH` | yes | scripts/release-manifest.sh:58 (usage line); options parsed :212-217; required check :204 |
| I1a: inside the repository only `--out-dir releases/manifest` | yes | release-manifest.sh:26 |
| I1a: planned tag `v1.0-submission` | yes | release-manifest.sh:20 |
| I1a: exit 8 for an identifier; exit-code list | yes | release-manifest.sh:137-139 (codes text); scripts/README.md:40 |
| I1a: mandatory jars; `packageJobs` builds only hadoop, entityResolution, sparkAnalytics; services jar needs `sbt -batch services/assembly` | yes | build.sbt:712-715 (alias), build.sbt:463-471 (services assemblySettings, assembly task), build.sbt:586-589 (packageCheck depends on the four assemblies) |
| I1a: `unzip` needed | yes (as I1a states; README lists unzip for release-manifest.sh) | I1a report RISKS; scripts/README.md:66 ("tools git jq unzip") |
| I1b: env.sh clears exported `SCHOLARCHAIN_CFG_*`, prints a count only | yes | scripts/emulator/env.sh:27-30 (header), :90 (awk name sweep), :102 (count line), :104 (awk-missing warning) |
| I1b: deploy.sh `--help` points to scripts/README.md "Full reproduction, in order" | yes | scripts/deploy.sh:113-117 region (lines "Order of a full reproduction ... scripts/README.md, "Full reproduction, in order"") |
| I1b item 5: plan.sh safe and useful for E6 (yes); cleanup.sh --list not useful (exit 3 at terraform-outputs) | as handed over (analysis, not re-derived) | I1b report lines 78-103 |
| W2: anchors used (runbook #21, #41, #42, #2, #3, #7, #10, #11; final-state #2, #3, #5, #7, #9; service-limits #3; threat-model #5; tool-versions #1, #6) | yes | grep of headings in each file (runbook.md:40, 55, 65, 79, 90, 161, 192, 216; final-state.md:32, 50, 74, 106, 118; service-limits.md:99; threat-model.md:174; tool-versions.md:18, 94) |
| W2: counts 60 rows, 55 verified, 5 unverified | yes | docs/service-limits.md:3 |
| W2: Claude Code 2.1.330; model statement | yes | docs/tool-versions.md:27, :96 |
| W2: runbook command forms copied (local Hadoop runner, Spark suites, smoke tests, deploy, cleanup, state count, archive, rollback) | yes, copied verbatim | docs/runbook.md:34-36, 46-51, 71-73, 85-86, 184-187, 199-213 |
| W1: anchors used (architecture #1, #2, #6; data-lineage #3, #9; api #1, #9, #10) | yes | architecture.md:18, 77, 230; data-lineage.md:67, 179; api.md:20, 256, 280 |
| W1: fixture example (sync-query-request.json body, completed-result.json three verified claims) | yes | docs/api.md:284-300; file modules/services/src/test/resources/api/v1/sync-query-request.json exists (ls) |

No mismatch.

## README requirement checklist (spec "README requirements", course-specification.md:2978-3000, in order)

| Spec item | README heading |
|---|---|
| Project title | `# ScholarChain` (line 1) |
| Student or team identities | identity line (line 3): "Student: Brian Wilk, bwilk26@uic.edu. Individual project" |
| One-paragraph problem statement | `## Problem statement` |
| Architecture summary | `## Architecture summary` (links docs/architecture.md) |
| Agentic workflow summary | `## Agentic workflow summary` (with `### Model configuration`, `### Agent catalog`, `### Reproducing the agentic workflow`, `### Bounded reproduction exercise: replay Phase 02`) |
| Required local software | `## Required local software` |
| AWS prerequisites | `## AWS prerequisites` (read-only IAM user PENDING E8; grader's own ViewOnlyAccess profile) |
| Environment variable names without secret values | `## Environment variables (names only)` (plus `## Model endpoints and how to select them`, the instructor's README condition) |
| Exact commands for formatting, compiling, testing, packaging | `## Formatting, compiling, testing and packaging` |
| Exact command for the bounded local or cloud sample | `## Bounded local sample` |
| Exact Terraform format, validate and plan commands | `## Terraform: format, validate and plan` |
| Exact source-controlled automation commands and env-var names | `## Source-controlled automation scripts` |
| Deployment and smoke-test commands | `## Deployment and smoke test` |
| API query examples | `## API query examples` (archived, labelled) |
| Experiment reproduction commands | `## Experiment reproduction` |
| Final report and video links | `## Final report and video` (report path pending W4/W5; video PENDING E11) |
| Cleanup commands | `## Cleanup` |
| Known limitations | `## Known limitations` (final report section 23 named; link added by W5) |
| Deliverable index | `## Deliverable index` (D01 to D20) |

Spec "Agent submission and reproducibility requirements" (course-specification.md:818-840): catalog with one row per agent (18) and all eight required columns plus "phases it ran in"; subsection named exactly `Reproducing the agentic workflow` with the 11 steps numbered 1 to 11; bounded reproduction exercise (Phase 02); external tools, MCP servers, env vars and model endpoints named with how to verify (`/agents`, `/mcp`, `claude --version`, check.sh exit 3); ownership, invocation, allowed changes, gate and report preservation answered in the catalog's common paragraph and the table. Decision 13 extras: auto mode, sessions 2 and 3 with the human running every script, report copying (`cp -n` Phases 10 to 14, `rsync -a --ignore-existing` Phases 15 to 17); D01 to D20 with the three planned PENDINGs; deployed application "not available: removed Nov 21 under the instructor-approved teardown"; model configuration statement.

## Link-check results (decision 12 method)

Method: every `](target)` in README.md; for repository targets `ls -d`, `git check-ignore -v` (must print nothing), `git ls-files` (tracked, or created in Phase 17 by a step's FILES CREATED); directory targets need at least one tracked file; fragments compared with the target's headings (GitHub rule).

| Target | ls | ignored | tracked files |
|---|---|---|---|
| CLAUDE.md, .mcp.json, .claude/settings.json, .scalafmt.conf, build.sbt, project/build.properties, config/course.json | ok | no | 1 each |
| .claude/agents/ (18), .claude/hooks/ (4), .claude/hook-tests/ (2), .claude/commands/ (5), project/ (9) | ok | no | as shown |
| docs/agents.md, docs/evaluation.md, docs/emulator.md, scripts/README.md, experiments/README.md, experiments/results-summary.md, infra/terraform/STATE.md, experiments/evaluation/evaluate.py | ok | no | 1 each |
| docs/agent-runs/ (388), docs/agent-runs/README.md, instructor-approvals.md, phase-01/commit.txt, phase-02/ (7), phase-02/commit.txt, phase-02/human-gate.md, phase-05/gold/gold-labels.csv, phase-08/README.md, phase-12/human-gate.md, phase-14/prompt.md, phase-15/human-gate.md, phase-15/prompt.md, phase-15/cleanup/ (1), phase-15/dynamodb-sample/ (5), phase-16/human-gate.md, phase-16/prompt.md | ok | no | as shown / 1 each |
| datasets/dataset-manifest.yaml, datasets/LICENSES.md, datasets/schemas/ (5), datasets/samples/ (7), datasets/scripts/ (12) | ok | no | as shown |
| modules/hadoop/ (53), modules/entity-resolution/ (49), er-v2.json, adjudication-output-schema-v1.json, modules/spark/ (29), modules/storage/ (105), modules/rag/ (71), modules/services/ (52), modules/services/src/test/resources/api/v1/ (16), sync-query-request.json, completed-result.json, prompts/ (10), prompts/runtime/ (5), prompts/schemas/ (5) | ok | no | as shown |
| infra/terraform/ (67), scripts/ (17), releases/course/ (7), load-tests/ (3) | ok | no | as shown |
| experiments/manifests/ (9), experiments/raw/ (145), experiments/raw/p15-a-hadoop/ (34), p15-a-spark/ (19), p15-f-poison-replay/ (13), experiments/derived/ (109), experiments/evaluation/ (277), experiments/evaluation/raw/answers/p15-demo/ (7), factory .../totals.csv, .../events.csv, repro .../run.txt, .../local-run-report.json, graph .../spark-suites.log | ok | no | as shown / 1 each |
| docs/architecture.md, docs/data-lineage.md, docs/api.md (W1); docs/threat-model.md, docs/service-limits.md, docs/runbook.md, docs/final-state.md, docs/tool-versions.md (W2); docs/incidents/INC-P15-F-poison-message.md (N) | ok | no | 0 (created in Phase 17, named in W1, W2 and N FILES CREATED) |

Fragment links (55 unique), all match a heading: README-internal 21 (#deliverable-index, #problem-statement, #architecture-summary, #agentic-workflow-summary, #agent-catalog, #reproducing-the-agentic-workflow, #bounded-reproduction-exercise-replay-phase-02, #required-local-software, #aws-prerequisites, #environment-variables-names-only, #model-endpoints-and-how-to-select-them, #formatting-compiling-testing-and-packaging, #bounded-local-sample, #terraform-format-validate-and-plan, #source-controlled-automation-scripts, #deployment-and-smoke-test, #api-query-examples, #experiment-reproduction, #final-report-and-video, #cleanup, #known-limitations); docs/architecture.md #1, #2, #6; docs/data-lineage.md #3, #9; docs/api.md #1-base-address-and-the-trailing-slash-g-22, #9-claim-verification-statuses-and-reasons, #10-examples-and-their-sources; docs/agents.md #report-contract, #mcp-servers-and-trust-boundaries, #blocked-on-protocol; docs/evaluation.md #101, #102, #104, #106, #107, #7-threats-to-validity; docs/tool-versions.md #1-operator-machine-phase-17, #6-model-configuration; docs/runbook.md #2, #21, #3, #41, #42, #7, #10, #11; docs/service-limits.md #3; docs/final-state.md #2, #3, #5, #7, #9; docs/threat-model.md #5.

External and pending (not links): repository URL `https://github.com/000brian/scholarchain` (plain text, from .git/config; repository private until the deadline, not fetched). PENDING: read-only IAM user (E8), video (E11, two places), release manifest (E10), retained items after Phase 17 (E8, two places). Plain-text future paths: reports/final-report.md (W4a/W4b), docs/demo-script.md (W5), docs/agent-runs/phase-17/prompt.md, docs/agent-runs/phase-17/course-specification.md, docs/agent-runs/phase-17/tool-versions-p17.txt (human copy), releases/manifest/RC_SHA/.

## Replay table (every command in README.md, in order; input for E5)

| # | Section | Command | Replay line |
|---|---|---|---|
| 1 | catalog, factory-engineer | `.claude/hooks/validate-agent-files.sh` | planned for replay (E5) |
| 2 | catalog, build-engineer | `sbt clean check` | planned for replay (E3) |
| 3 | catalog, domain-engineer | `sbt -batch "core/test" "core2_13/test"` | planned for replay (E5) |
| 4 | catalog, dataset-engineer | `bash datasets/scripts/validate.sh` | planned for replay (E5) |
| 5 | catalog, hadoop-engineer | `sbt -batch "hadoop/test"` | planned for replay (E5) |
| 6 | catalog, entity-resolution-engineer | `sbt -batch "entityResolution/test"` | planned for replay (E5) |
| 7 | catalog, spark-engineer | `sbt -batch "sparkAnalytics/testOnly org.scholarchain.spark.KnownGraphSuite org.scholarchain.spark.PipelineFixtureSuite"` | planned for replay (E5) |
| 8 | catalog, storage-engineer | `sbt -batch "storage/test"` | planned for replay (E5) |
| 9 | catalog, rag-engineer | `sbt -batch "rag/test"` | planned for replay (E5) |
| 10 | catalog, service-engineer | `sbt -batch "services/test"` | planned for replay (E5) |
| 11 | catalog, test-engineer | `sbt -batch "contractTests/test"` | planned for replay (E5) |
| 12 | catalog, infra-engineer | `terraform fmt -recursive -check infra/terraform` (+ validate line, #55) | planned for replay (E5) |
| 13 | catalog, deploy-engineer | smoke test question A (#68) | not replayable after teardown (archived evidence: docs/agent-runs/phase-14/runbook-log.md, row D14) |
| 14 | catalog, experiment-engineer | `python3 -I experiments/evaluation/evaluate.py reproduce --fixtures` | planned for replay (E5) |
| 15 | catalog, dependency-updater | `sbt clean check` | planned for replay (E3) |
| 16 | step 1 | `git clone https://github.com/000brian/scholarchain.git /tmp/p17-clone-STAMP` | planned for replay (E5) |
| 17 | step 1 | `git -C /tmp/p17-clone-STAMP switch --detach v1.0-submission` | deferred to E14 (tag checkout; the release-candidate SHA checkout planned for replay (E5)) |
| 18 | step 1 | `cd /tmp/p17-clone-STAMP` | planned for replay (E5) |
| 19 | step 2 | `claude --version` | replayed earlier (phase 17, start check 6, docs/agent-runs/phase-17/tool-versions-p17.txt) |
| 20 | step 3 | `export SCHOLARCHAIN_AWS_READONLY_PROFILE=YOUR-VIEWONLY-PROFILE` | planned for replay (E5) |
| 21 | step 3 | `export SCHOLARCHAIN_AWS_REGION=us-east-1` | planned for replay (E5) |
| 22 | step 4 | `claude` | planned for replay (E5) (optional recorded session) |
| 23 | step 5 | `/agents` (in session) | planned for replay (E5) (same session) |
| 24 | step 5 | `/mcp` (in session) | planned for replay (E5) (same session) |
| 25 | step 6 | `.claude/hooks/validate-agent-files.sh` | planned for replay (E5) |
| 26 | step 6 | `bash .claude/hook-tests/guard-selftest.sh` | planned for replay (E5) |
| 27 | step 6 | `bash .claude/hook-tests/stop-hook-selftest.sh` | planned for replay (E5) |
| 28 | step 6 | probe: ask the orchestrator to run `echo 'terraform destroy'` | planned for replay (E5) (same session) |
| 29 | step 7 | fresh session, paste a phase prompt | not replayed in phase 17 (agent session; recorded for the video, not required for the final gate) |
| 30 | exercise 1 | `git clone https://github.com/000brian/scholarchain.git /tmp/p17-replay-STAMP` | planned for replay (E5) |
| 31 | exercise 2 | `cd /tmp/p17-replay-STAMP` | planned for replay (E5) |
| 32 | exercise 3 | `git switch -c replay-phase-02 684c610bbcb2d314e4177677a08fdf2be0302ba6` | planned for replay (E5) |
| 33 | exercise 4 | `git log -1 --format='%H %s'` | planned for replay (E5) |
| 34 | exercise 5 | `.claude/hooks/validate-agent-files.sh` (Phase 01 version) | planned for replay (E5) |
| 35 | exercise 6 | `claude` + Phase 02 prompt verbatim | not replayed in phase 17 (agent session; video) |
| 36 | exercise 7 | `sbt Test/compile` | not replayed in phase 17 (builds the session's output) |
| 37 | exercise 8 | `sbt check` | not replayed in phase 17 (checks the session's output) |
| 38 | exercise 9 | `git diff --stat 41eaea2956f1608ba7f0631a5e9eba6b87f957e8 -- .gitignore .scalafmt.conf build.sbt docker project` | not replayed in phase 17 (compares the session's output) |
| 39 | exercise 10 | `git show 41eaea2956f1608ba7f0631a5e9eba6b87f957e8:build.sbt` | planned for replay (E5) |
| 40 | exercise 11 | `git show 41eaea2956f1608ba7f0631a5e9eba6b87f957e8:project/build.properties` | planned for replay (E5) |
| 41 | formatting | `sbt fmt` | planned for replay (E5) |
| 42 | formatting | `sbt Test/compile` | planned for replay (E5) |
| 43 | formatting | `sbt clean check` | planned for replay (E3) |
| 44 | formatting | `sbt -batch "sparkAnalytics/testOnly org.scholarchain.spark.KnownGraphSuite org.scholarchain.spark.PipelineFixtureSuite"` | planned for replay (E5) |
| 45 | formatting | `sbt -batch packageJobs` | planned for replay (E5) |
| 46 | formatting | `sbt -batch services/assembly` | planned for replay (E5) |
| 47 | formatting | `sbt -batch packageCheck` | planned for replay (E5) |
| 48 | formatting | `bash scripts/package.sh` | planned for replay (E5) |
| 49 | formatting | `bash scripts/check.sh --record-dir "$REPO/evidence/checks"` | planned for replay (E4) |
| 50 | bounded sample | `sbt "hadoop/Test/runMain org.scholarchain.hadoop.LocalBoundedPipeline /tmp/p17-hadoop-STAMP"` | planned for replay (E5) |
| 51 | bounded sample | Spark fixture suites (same as #44) | planned for replay (E5) |
| 52 | bounded sample | `sbt -batch "services/testOnly org.scholarchain.services.GoldenExamplesSuite"` | planned for replay (E5) |
| 53 | terraform | `terraform fmt -recursive -check infra/terraform` | planned for replay (E5) |
| 54 | terraform | `terraform -chdir=infra/terraform/environments/course init -backend=false -input=false -lockfile=readonly` | planned for replay (E5) |
| 55 | terraform | `terraform -chdir=infra/terraform/environments/course validate` | planned for replay (E5) |
| 56 | terraform | `bash scripts/plan.sh` | planned for replay (E6) (optional; human decides) |
| 57 | terraform | `bash scripts/plan.sh --runtime-enabled true --image-tag BUILD --graph-run-id run:v1:RUN_HEX` | not replayable after teardown (archived evidence: docs/agent-runs/phase-15/approvals.md, plan D3) |
| 58 | scripts | `bash scripts/archive-evidence.sh --local-only` | replayed earlier (phase 13, docs/agent-runs/phase-13/human-gate.md, "Archive (`--local-only`)") |
| 59 | scripts | `bash scripts/archive-evidence.sh` | not replayable after teardown (archived evidence: phase-15 runbook-log row "D25 step 3"; approvals.md:78) |
| 60 | scripts | `bash scripts/rollback.sh --release releases/course/RECORD.json --role ROLE` | not replayable after teardown (archived evidence: none; never run) |
| 61 | scripts | `bash scripts/release-manifest.sh --clone /tmp/p17-clone-STAMP --marker /tmp/p17-E3-marker.txt --out-dir /tmp/p17-manifest-dryrun-STAMP` | planned for replay (E5) |
| 62 | scripts | same with `--out-dir releases/manifest` | planned for replay (E10) |
| 63 | deployment | `bash scripts/package.sh --publish` | not replayable after teardown (archived evidence: phase-14 runbook-log rows D4) |
| 64 | deployment | runtime-on plan (as #57) | not replayable after teardown (archived evidence: phase-14 approvals.md, plan D10) |
| 65 | deployment | sign `APPROVED-PLAN-SHA256: HEX64` (human) | not replayable after teardown (archived evidence: docs/final-state.md section 2) |
| 66 | deployment | `bash scripts/deploy.sh --plan-sha256 HEX64` | not replayable after teardown (archived evidence: releases/course/) |
| 67 | deployment | `read -rs "SCHOLARCHAIN_SERVICE_API_KEY?Service API key: "; export SCHOLARCHAIN_SERVICE_API_KEY` | not replayable after teardown (archived evidence: phase-14 runbook-log row D14) |
| 68 | deployment | smoke test question A (Wanli Ouyang) | not replayable after teardown (archived evidence: phase-14 row D14; phase-15 row D5) |
| 69 | deployment | smoke test question B (Tommaso Green, Yiannis Demiris) | not replayable after teardown (archived evidence: phase-14 row D15; phase-15 row "D6 G-28 q13 re-run") |
| 70 | experiments | `bash scripts/run-experiment.sh --manifest experiments/manifests/ID.json --manifest-sha256 HEX64 --check` | not replayable after teardown (archived evidence: phase-15 runbook-log rows D9a) |
| 71 | experiments | `caffeinate -i bash scripts/run-experiment.sh --manifest experiments/manifests/ID.json --manifest-sha256 HEX64` | not replayable after teardown (archived evidence: experiments/raw/) |
| 72-76 | experiments | `python3 -I experiments/derived/{emr_capacity,failure_timeline,load_levels,queue_worker,retrieval_modes}.py` (five lines) | planned for replay (E5) each |
| 77 | experiments | `git status --short` (must print nothing) | planned for replay (E5) |
| 78-85 | experiments | `python3 -I experiments/evaluation/evaluate.py SUB --fixtures` for er, graph, retrieval, answers, ratings-sheet, performance, factory, reproduce (eight lines) | planned for replay (E5) each |
| 86 | cleanup | `bash scripts/cleanup.sh --evidence-archive PATH_TO_ARCHIVE --list` | not replayable after teardown (archived evidence: phase-15 runbook-log row "D25 step 5 cleanup.sh --list"; I1b: exit 3 before any listing today) |
| 87 | cleanup | sign the removal plan (human) | not replayable after teardown (archived evidence: phase-15 approvals.md:75-76) |
| 88 | cleanup | `bash scripts/plan.sh --runtime-enabled false --cleanup-mode true` | not replayable after teardown (archived evidence: phase-15 approvals.md:67-73; release record R7) |
| 89 | cleanup | `bash scripts/cleanup.sh --evidence-archive PATH_TO_ARCHIVE --remove --plan-sha256 HEX64` | not replayable after teardown (archived evidence: docs/agent-runs/phase-15/cleanup/; rows "D27 attempt 1" and "D27 attempt 2") |
| 90 | cleanup | `terraform -chdir=infra/terraform/environments/course state list > /tmp/p17-E6-state.txt 2> /tmp/p17-E6-state.err; echo "exit=$?" \| tee /tmp/p17-E6-state.exit; wc -l < /tmp/p17-E6-state.txt` | planned for replay (E6) |

Distinct commands for E5 (duplicates merged): validator; hook self-tests (2); `sbt fmt`; `sbt Test/compile`; module tests (core+core2_13, hadoop, entityResolution, storage, rag, services, contractTests); Spark fixture suites; GoldenExamplesSuite; local Hadoop runner; `datasets/scripts/validate.sh`; terraform fmt, init, validate; packageJobs, services/assembly, packageCheck, package.sh; five derive scripts + `git status --short`; eight evaluate.py fixtures; the clone/cd/switch/export lines; the Phase 02 exercise lines 1-5, 10, 11; the release-manifest dry run; the optional `claude` session (/agents, /mcp, probe). E3: `sbt clean check`. E4: check.sh. E6: plan.sh (optional), state count. E10: release manifest.

## Replayed commands and outcomes

None (planned deviation 1).

## Unverified claims

- "The guard blocks `echo 'terraform destroy'`": the work order's statement (decision 13); not observed.
- `/mcp` showing `aws-api` unable to connect without a profile: inferred from `.mcp.json`'s profile default and docs/agents.md, not observed.
- The Bedrock switch values (`anthropic.claude-haiku-5-5`, `amazon.titan-embed-text-v2:0`) are from instructor-approvals.md "How it is applied"; not re-read from the Bedrock client code.
- Each catalog verification command's runtime behaviour (module test runs exclude `RequiresAws` and `RequiresEmulator`: from build.sbt:93-105 comments, not run).
- I1a's jar facts and I1b's item 5 analysis are taken from their reports (file lines re-checked where listed in INTERFACE CHECK).

## INTERFACE HANDOFF

File: README.md (new; document only; no other owner's file changed).

Anchors other documents may link: #problem-statement, #architecture-summary, #agentic-workflow-summary, #model-configuration, #agent-catalog, #reproducing-the-agentic-workflow, #bounded-reproduction-exercise-replay-phase-02, #required-local-software, #aws-prerequisites, #environment-variables-names-only, #model-endpoints-and-how-to-select-them, #formatting-compiling-testing-and-packaging, #bounded-local-sample, #terraform-format-validate-and-plan, #source-controlled-automation-scripts, #deployment-and-smoke-test, #api-query-examples, #experiment-reproduction, #final-report-and-video, #cleanup, #known-limitations, #deliverable-index.

For W5: (1) link `reports/final-report.md` in "Final report and video", "Known limitations" and D18, and `docs/demo-script.md` in "Final report and video" and D17; (2) align the query-and-evidence suite (#52) with T1's named services end-to-end fixture suite; (3) reconcile docs/runbook.md (cleanup --list replay line; env.sh note) with the README and I1b; (4) reconcile the "Known limitations" L2 bullet with step 1's final handoff. For E11: the replay table above, the six PENDING entries, the tag-checkout line's exact form.

Thread-safety and limits: not applicable (document). Every statement describes HEAD 50ba087 plus the producers' handed-over changes; no live check was possible.

## VALIDATION EVIDENCE

- `shasum -a 256 docs/agent-runs/phase-17/prompt.md` -> c22adfc57c7fde0cae63c8520393c2c81cd12280df7f5df773e70c2e2ec8ac24 (start and end)
- `git rev-parse HEAD` -> 50ba087ecd33178eaa8ccc69286b9de85e8a8146
- interface check: grep and head reads of scripts/release-manifest.sh (:20, :26, :58, :204, :212-217), build.sbt (:463-471, :586-589, :712-715), scripts/emulator/env.sh (:27-30, :90, :102, :104), scripts/deploy.sh (help text), W1/W2 headings -> all match
- `git log -1` on 684c610bbcb2 and 41eaea2956f1; `git merge-base --is-ancestor` -> ancestor; `git diff --stat 684c610bbcb2 12d97f0` -> only docs/agent-runs/phase-01 files (no constitution change between the Phase 01 work commit and its gate commit); `git ls-tree 684c610bbcb2 .claude` -> no .claude/hook-tests/; `git show --stat 41eaea29` -> 8 files
- link check loop (ls -d, git check-ignore -v, git ls-files) over every README target -> all ls=ok, ignored=no; 9 untracked targets, all created in Phase 17 (W1, W2, N)
- fragment check: 55 unique fragment links against `grep -n '^#'` of README.md and the target files -> all match
- identifier scan of README.md (AKIA, ASIA, sk-ant-, pa-..., arn:aws, amazonaws.com, execute-api, .elb., neptune.amazonaws, scholarchain-course, scholarchain-25, scholarchain-readonly, twelve zeros, any 12-or-more digit run, IPv4) -> no match
- `shasum -a 256 README.md` -> 698986cba564eb34c15bb3c9f1ba8b1888dbf58643388b02435806e1e99eec81 (445 lines)
- `git status --short` -> my file: `?? README.md`; every other entry belongs to other agents (rag, storage, hadoop, services, build, experiment, infra, incident, documentation W1/W2) or the human (docs/agent-runs/phase-17/)

files changed after these runs: none
