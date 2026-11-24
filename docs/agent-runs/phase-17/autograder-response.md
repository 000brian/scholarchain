# Phase 17: response to the TA autograder (V1 1.0.3, V2 2.3.0, V3 3.0.0) against d8b49fa

The TA autograder ran against commit d8b49fa on 2026-11-24. On that day the human asked the instructor in person after class whether the fixes for the failed checks could be committed as part of Phase 17, and the instructor said yes (as the human reported; runbook-log row "E12 autograder work, instructor approval"). The repository has been public since 2026-11-24, with the instructor's permission (runbook-log row "Repository visibility").

This file answers each FAIL or WARN row of V1, each partial or deduction row of V2 `hadoop_mapreduce`, and the partial row of V3 `factory_reproducibility`. The reports were read as data. No key text is quoted here.

Results in short: V1 had 8 FAIL and 9 WARN rows (17 A-items). V2 `hadoop_mapreduce` had 2 partial rows and 1 minor deduction (B-items); `dataset_engineering` and `spark_analytics` were all met. V3 `factory_reproducibility` had 1 partial row (C1).

## Mapping

| ID | Version | Check | Name | Result |
|---|---|---|---|---|
| A1 | V1 | P01 | Phase evidence folders docs/agent-runs/phase-NN/ | FAIL |
| A2 | V1 | G02 | Each phase's commit.txt names a commit that exists | FAIL |
| A3 | V1 | G03 | Commit messages identify the phase | FAIL |
| A4 | V1 | K01 | No secrets in the current files (tracked by git) | FAIL |
| A5 | V1 | K02 | No secrets anywhere in git history | FAIL |
| A6 | V1 | K03 | No .env, Terraform state or key files committed (tfvars: review) | WARN |
| A7 | V1 | E01 | Agent reports have every required field and a valid STATUS | FAIL |
| A8 | V1 | E03 | Files a report claims were really changed by that phase's commits | FAIL |
| A9 | V1 | E04 | Files the phase changed but its report doesn't list | WARN |
| A10 | V1 | E05 | Files changed in a phase belong to its OWNER | WARN |
| A11 | V1 | C03 | No TaskForge leftovers in the control plane or code | WARN |
| A12 | V1 | O02 | One writer per artifact | WARN |
| A13 | V1 | O03 | Writers have write tools | WARN |
| A14 | V1 | R03 | guard-dangerous.sh covers every destructive category ScholarChain names | FAIL |
| A15 | V1 | V01 | No hard-coded buckets, ARNs, endpoints, account IDs or model IDs in source code | WARN |
| A16 | V1 | A01 | Spark code doesn't collect data to one machine | WARN |
| A17 | V1 | U01 | Scripts under scripts/ use bash strict mode | WARN |
| B1 | V2 | HADOOP-04 | Bounded blocking and match candidates | partial |
| B2 | V2 | HADOOP-11 | Job contracts documented | partial |
| B3 | V2 | HADOOP-PAIRS-WRONG | Coauthor-pair preparation for large author teams (deduction) | applies, minor |
| C1 | V3 | FACTORY-07 | Phase records | partial |

## A1 (V1 P01): phase evidence folders

Found: "docs/agent-runs/phase-08/: missing prompt, agent-report, human-gate, commit.txt" and "docs/agent-runs/phase-17/: missing human-gate, commit.txt".

Phase 08:
- Phases 7 and 8 ran in one session. All Phase 8 evidence is in `docs/agent-runs/phase-07/`: docs/agent-runs/phase-07/prompt.md:3 ("This one session covers spec Phases 7 and 8"), and the Phase 8 work order is quoted verbatim at docs/agent-runs/phase-07/prompt.md:291.
- `docs/agent-runs/phase-08/README.md:1` says "Merged into phase-07; see docs/agent-runs/phase-07/."
- The human gate covers both phases: docs/agent-runs/phase-07/human-gate.md:5, :43 ("Phase 8 work order"), :134 (the human added the phase-08 README), and :167 ("I accept Phase 7 and 8 as of commit 0cfa8875997fc9b40dc6468f8d8d5b257d7edf62").
- The Phase 17 work order records this exception: docs/agent-runs/phase-17/prompt.md:31.

Phase 17:
- At d8b49fa, Phase 17 was still in progress. Its `human-gate.md` and `commit.txt` are written by the human at step E12, and the gate commit follows (docs/agent-runs/phase-17/prompt.md:353, `commit.txt` rule at :373). Neither file was tracked at d8b49fa (checked with `git ls-files`). The gate commit adds both files.

## A2 (V1 G02): commit.txt names commits that exist

Found: 15 messages "… is not a commit in this repo" for 10 distinct strings: phase-12 `5f249df5`; phase-14 `6d4a629c`, `a865a94d`, `5c77365f`, `61957631`, `5656145e`; phase-15 `d636c648`, `71fe9071`, `235b56f5`, `934d78c3`.

Answer:
- Every 40-character hash in the three files resolves to a commit that is an ancestor of HEAD. The orchestrator and the research agent checked 50 of 50 hashes with `git merge-base --is-ancestor` (phase-12: 3, phase-14: 20, phase-15: 27).
- The flagged strings are not commit hashes. Each one is the 8-character prefix of a signed Terraform plan sha256, quoted inside a commit subject. Each prefix matches a signed plan sha256: an `APPROVED-PLAN-SHA256:` line, or for phase 12 the plan sha256 line in the phase-12 human gate:

| String | commit.txt line(s) | Signed plan record |
|---|---|---|
| `5f249df5` | phase-12:3 | docs/agent-runs/phase-12/human-gate.md:60 (plan sha256) |
| `6d4a629c` | phase-14:1 | docs/agent-runs/phase-14/approvals.md:9 |
| `a865a94d` | phase-14:5, :6 | docs/agent-runs/phase-14/approvals.md:17 |
| `5c77365f` | phase-14:11, :12 | docs/agent-runs/phase-14/approvals.md:29 |
| `61957631` | phase-14:15, :16 | docs/agent-runs/phase-14/approvals.md:37 |
| `5656145e` | phase-14:17, :18 | docs/agent-runs/phase-14/approvals.md:45 |
| `d636c648` | phase-15:4 | docs/agent-runs/phase-15/approvals.md:76 |
| `71fe9071` | phase-15:9, :10 | docs/agent-runs/phase-15/approvals.md:73 |
| `235b56f5` | phase-15:18 | docs/agent-runs/phase-15/approvals.md:65 |
| `934d78c3` | phase-15:24 | docs/agent-runs/phase-15/approvals.md:9 |

- The orchestrator's investigation also noted a possible false positive: "succeeded" contains the all-hex word `cceeded` (phase-15 lines 11 and 12). V1 1.0.3 did not report it.
- The three commit.txt files were not changed (human decision; runbook-log row "E12 autograder decisions 2").

## A3 (V1 G03): commit messages identify the phase

Found: "100 of 106 commit messages name a phase | phase-08: no commit message mentions it".

Answer: Phase 8 was merged into Phase 7 (see A1). Its work commit is `0cfa887 phase 07+08: storage adapters and loading, embeddings and S3 Vectors`, and its record commit is `d4ea5c9 phase 07+08 human gate and reports` (checked with `git log`). The checker does not read "07+08" as naming phase 08. History cannot be rewritten because force pushes are denied (docs/agents.md, hooks table, guard row), so these subjects stay as they are.

## A4 (V1 K01) and A5 (V1 K02): secrets in current files and in history

Found (K01): "AWS access key id" at docs/agent-runs/phase-16/orchestrator-summary-s2.md:225, docs/agent-runs/phase-16/review-report-a1.md:17, docs/agent-runs/phase-16/runbook-log.md:10, and modules/storage/src/test/scala/org/scholarchain/storage/emulator/EmulatorWiringSuite.scala:482 and :535. "AWS secret access key" at EmulatorWiringSuite.scala:113. "possible hard-coded Secret" at tests/contracts/src/test/scala/org/scholarchain/contracts/er/ErRepairRegressionSuite.scala:454.
Found (K02): 5 entries in 4 files, in commits 6647034 (docs/agent-runs/phase-16/runbook-log.md) and d37389b (the other three files) (V1 report, check K02). K02 does not list ErRepairRegressionSuite.scala.

Answer:
- The access-key matches are AWS's documented example key (P16-A-01). It is a deliberate input in the emulator test, and the three phase-16 reports quote it as an expected scan match. Records:
  - docs/agent-runs/phase-16/human-gate.md:129: "AWS's published documentation example access key in EmulatorWiringSuite.scala (P16-A-01): accepted as a deliberate test input; it is an expected match of the staged-diff scan."
  - docs/agent-runs/phase-16/review-report-a1.md:17, :134, :211; docs/agent-runs/phase-16/orchestrator-summary.md:95 ("accepted (AWS example key)"); docs/agent-runs/phase-16/orchestrator-summary-s2.md:124; docs/agent-runs/phase-16/orchestrator-notes-s2.md:32, :64.
  - The review records the key at EmulatorWiringSuite.scala:340 and :393. At d8b49fa the same key is at :482 and :535 (the file grew later; not verified line by line against d37389b).
- EmulatorWiringSuite.scala:113 (the "secret access key" match) is a synthetic emulator credentials fixture made of placeholder characters (`EMULATOR…`, digit runs), used by `parseCredentials` at :122 and :448. It is not an AWS key.
- ErRepairRegressionSuite.scala:454 is a Scala constant named `Secret` that holds a synthetic marker string. The test uses it to check that an exception message does not leak (:459). It is not a credential.
- The human read the autograder output and recorded the access-key hits as AWS's documented example key, checked by the human (runbook-log row "E12 autograder decisions 2"). The two other K01 hits, EmulatorWiringSuite.scala:113 and ErRepairRegressionSuite.scala:454, are made-up test strings, checked by the human (runbook-log row "E12 autograder decisions 3"). No real key is committed. History cannot be rewritten (force pushes are denied), so the commits stay.

## A6 (V1 K03): tfvars review

Found: "infra/terraform/environments/course/course.tfvars: Terraform variables file: make sure it holds no secrets".

Answer: The human reviewed course.tfvars and found no secrets (the human's E12 instruction, runbook-log row "E12 A6 tfvars review (human)"). A read-only check of course.tfvars:1-31 agrees: it holds only non-secret settings: Region, recovery window, deletion protection, PITR, final snapshot, runtime and cleanup switches, and expiry. Terraform state, plans and `.env` files are not committed (CLAUDE.md "Security invariants").

## A7 (V1 E01): report fields

Found: "phase-02: missing field(s) COMMANDS RUN | phase-00: missing field(s) STATUS, OWNER, … NEXT GATE (only 0 of 14 labels recognised …)".

Answer:
- The 14-field report contract was adopted in Phase 1, as human amendment 3: docs/agent-runs/phase-01/prompt.md:13. docs/agents.md:5 names it among the "Phase 1 human amendments", and the contract itself is at docs/agents.md:159.
- Phase 00 comes before the contract. Its report is a retroactive record of the seed step (commit 9cc3cfb "phase-00 human: add retroactive agent report and human gate"). It is written in prose and names the one file created (docs/agent-runs/phase-00/agent-report.md:1).
- Phase 02 has the field. docs/agent-runs/phase-02/agent-report.md:24 reads "COMMANDS RUN (results as observed):". The checker did not recognise the label because of its parenthetical suffix. The other 13 fields are present (lines 1-87).
- No change: other phases' records are frozen in Phase 17.

## A8 (V1 E03): files a report claims were changed

Found: 352 items "phase-NN: report claims PATH, but no phase-NN commit changed it". 295 of them add "(this path never existed in the repo)".

Method:
- The research agent took a systematic sample of 32 of the 352 items: every 11th item in report order, starting with item 1.
- For each item, it listed the files changed by that phase's commits. Those are the commits whose subject starts with `phase-NN` (`phase 07+08` for phase 07), or the commits in that phase's commit.txt. It used `git show --name-only` and `git log`, then matched the reported path as a path suffix.

Result:

| Class | Count |
|---|---|
| Short module-relative path that resolves to a file changed by that phase's commits | 27 |
| Not a claim that the phase changed the file (directory layout, template path, fixture "used as before", directory mention, a scratchpad report path) | 5 |
| Real mismatch | 0 |

Item 331 ("phase17-reports/agent-report-docs-W4a.md") is the scratchpad path of a report; its committed copy is docs/agent-runs/phase-17/agent-report-docs-W4a.md, so it is counted under "not a claim" (code-reviewer, review-report-autograder.md, F2). Items written with "…" were matched by their visible prefix and suffix.

Examples that resolve:
- phase-04 "common/Disposition.scala" -> modules/hadoop/src/main/scala/org/scholarchain/hadoop/common/Disposition.scala (commit 4d8598f).
- phase-09 "main/.../storage/dynamo/ItemBuilders.scala" -> modules/storage/src/main/scala/org/scholarchain/storage/dynamo/ItemBuilders.scala (commit 9a9174d).
- phase-16 "src/main/scala/org/scholarchain/er/eval/Harness.scala" -> modules/entity-resolution/src/main/scala/org/scholarchain/er/eval/Harness.scala (phase-16 commits).
- phase-05 "pipeline/DblpChain.scala" and "candidates/CandidateFeatureJob.scala" -> changed by 2297990 "phase-05 part 1 …". phase-05/commit.txt names only a5322d4.
- phase-17 "docs/agent-runs/phase-17/runbook-log.md" (the checker itself says "changed in phase-17"), "rag/runtime/GroundingSuite.scala" -> phase-17 commits. All 42 phase-17 items are affected by the same gap: at d8b49fa there was no phase-17/commit.txt yet (see A1).

The other 4 that are not claims (item 331 is above):
- phase-02 "datasets": docs/agent-runs/phase-02/agent-report.md:19 lists the empty skeleton folders. Git does not track empty folders.
- phase-13 "config": docs/agent-runs/phase-13/agent-report-build.md:26 ("config in image") refers to the Dockerfile, not a change under config/.
- phase-15 "evidence/runs/ATTEMPT_ID-load.json": a template name for a gitignored run record (docs/agent-runs/phase-15/agent-report-experiment-6c.md:43).
- phase-16 "datasets/samples/dblp/dblp-synthetic-edge-cases.xml": docs/agent-runs/phase-16/agent-report-hadoop.md:57 says "uses … as before".

Outside the sample, items of the same kind include phase-14 "target/r.json", phase-17 "OUT_DIR/RC_SHA/release-manifest.json" and "modules/hadoop/target/test-reports/…". These are build outputs or placeholders, not claims (not counted).

## A9 (V1 E04): files changed but not listed in the report

Found: 13 phases (00, 01, 03, 04, 05, 07, 09, 10, 11, 14, 15, 16, 17), each with "changed but not in the report: …".

Answer: Most phases ran several agents, and each agent wrote its own report (`agent-report-<agent>.md`, `repair-*.md`). The checker reads one report per phase. For each phase, a representative flagged file is listed in another report of the same phase:

| Phase | Flagged example | Listed in |
|---|---|---|
| 00 | .claude/agents/factory-engineer.md | phase-00/agent-report.md:1 (pre-contract prose, see A7) |
| 01 | .claude/agents/*.md | phase-01/agent-report.md:11 (".claude/agents/: 17 new files") |
| 01 | .DS_Store, .claude/.DS_Store | not recorded in the report. Committed by mistake in 684c610 and untracked later in 32fd398 "stop tracking .DS_Store files" (follow-up noted at phase-05/human-gate.md:95) |
| 03 | core/Hashing.scala | phase-03/agent-report-domain.md |
| 04 | contracts/hadoop/JobHarness.scala | phase-04/test-report-02.md |
| 05 | er/cluster/CannotLinks.scala | phase-05/repair-03.md, review-report.md |
| 07 | core/SemanticContractSuite.scala | phase-07/agent-report-domain.md |
| 09 | core/RuntimeContractSuite.scala | phase-09/agent-report-domain.md |
| 10 | services/HealthService.scala | phase-10/agent-report-service.md |
| 11 | core/QueryAuditV2Suite.scala | phase-11/repair-04-domain.md |
| 14 | contracts/hadoop/ScaleAndProvenanceAdversarialSuite.scala | phase-14/repair-D6-tests.md |
| 15 | storage/ingest/BoundedPool.scala, aws/SqsRawReceiveQueue.scala | phase-15/agent-report-storage.md, repair-00-storage.md |
| 16 | experiments/derived/out/… | phase-16/agent-report-experiment-X1.md, X2.md |
| 17 | rag/runtime/AbsenceRuleSuite.scala, rag/eval/RetrievalEvalSet.scala | phase-17/agent-report-rag.md, repair-E1-rag.md |

The research agent checked one representative file per phase with grep, not every file in each "+N more" list.

## A10 (V1 E05): files changed in a phase belong to its OWNER

Found: 640 crossings. The checker names one agent per phase: domain-engineer for phase 09, deploy-engineer for phase 15, and build-engineer for phases 10-14, 16 and 17. Every flagged file belongs to rag-engineer (`prompts/**`), infra-engineer (`scripts/**`, `config/**`), experiment-engineer (`experiments/**`) or deploy-engineer (`releases/**`).

Answer: Each phase ran several agents, and each file was written by its owner, or by the human running an approved script. The checker attributes every file of a phase to one agent.

| Phase | Files (count) | Owner | Record |
|---|---|---|---|
| 09 | prompts/** (5) | rag-engineer | phase-09/agent-report-rag.md |
| 10 | prompts/** v2 (3) | rag-engineer | phase-10/agent-report-rag.md |
| 11 | prompts/** v3 (2) | rag-engineer | phase-11/repair-06-rag.md |
| 12 | config/course.json, scripts/* (7) | infra-engineer | phase-12/agent-report-infra-2a.md, -2b.md, -2c.md, repair-01-infra.md |
| 13 | scripts/* (12) | infra-engineer | phase-13/agent-report-infra-scripts-a.md, -b.md |
| 13 | experiments/manifests/example-not-approved.json (3 experiments files) | experiment-engineer | phase-13/agent-report-experiment.md |
| 14 | releases/course/*.json (4) | deploy-engineer, through deploy.sh | phase-14/agent-report-deploy.md (docs/agents.md:36: records written only by approved scripts) |
| 14 | scripts/* (5) | infra-engineer | phase-14/agent-report-infra.md, repair-D5-infra.md, repair-D6-infra.md, repair-DER-infra.md |
| 15 | experiments/** (271): collectors, derived, manifests | experiment-engineer | phase-15/agent-report-experiment-6a/6b/6c/AB.md, repair-01-experiment-engineer.md |
| 15 | experiments/raw/** | human ran scripts/run-experiment.sh | phase-15/human-gate.md:17 (deviation: "I ran every experiment through scripts/run-experiment.sh"); phase-15/prompt.md:3 ("No agent runs any script") |
| 15 | scripts/* (7) | infra-engineer | phase-15/agent-report-infra-5a.md, -5b.md |
| 16 | experiments/** (296) | experiment-engineer | phase-16/agent-report-experiment-X1.md, X2.md |
| 16 | config/emulator.json, scripts/emulator/*, scripts/* (13) | infra-engineer | phase-16/agent-report-infra.md |
| 17 | releases/manifest/<RC>/* (2) | human ran scripts/release-manifest.sh | phase-17/prompt.md:145 ("infra-engineer (script), me (run)"); committed runbook-log.md:63 (E10) |
| 17 | experiments/collectors/lib (3) | experiment-engineer | phase-17/agent-report-experiment.md |
| 17 | scripts/* (7) | infra-engineer | phase-17/agent-report-infra-I1a.md, -I1b.md |

No crossing is unrecorded at this level. The research agent checked by grep that each owner's report names the file or folder, not file by file for the large experiments lists.

## A11 (V1 C03): TaskForge leftovers

Found: "docs/agent-runs/phase-01/course-specification.md: 2 mention(s) | docs/agent-runs/phase-17/course-specification.md: 2 mention(s)".

Answer: `git grep -i taskforge d8b49fa` finds only these two files, at lines 129 and 3051 of each. Both files are the course specification's own text, kept as human-owned phase records. The control plane and the code contain no mention.

## A12 (V1 O02): `evidence/**` has two writers

Found: "`evidence/**` has 2 writers: infra-engineer (owner; docs/agents.md:48), deploy-engineer (owner; docs/agents.md:48)".

Answer: docs/agents.md:48 does not give `evidence/**` an agent writer. It is generated by `scripts/check.sh`, `package.sh`, `run-bounded-pipeline.sh`, `run-experiment.sh` and `archive-evidence.sh` (authored by infra-engineer) when the human or deploy-engineer runs them. It is gitignored and never committed. No agent writes it by hand: the guard's `owner_of` maps it to unowned. The checker read the script author and the script runner as two writers. This is a known limitation of the wording. The constitutional files are frozen in Phase 17, so nothing was changed after the release candidate.

## A13 (V1 O03): deploy-engineer writes without Write/Edit

Found: "deploy-engineer is said to write files but has no Write/Edit (only via Bash)".

Answer: By design. deploy-engineer's tools are Read, Grep, Glob, Bash and read-only aws-api (docs/agents.md:78: "No Write or Edit; executes approved scripts only"). It authors no files. Release records under `releases/**` are written only by the approved scripts it runs, and the guard blocks Write, Edit and shell writes there for every agent (docs/agents.md:36). The V3 judge read it the same way (result.json FACTORY-03: "deploy-engineer has no Write/Edit"). Known limitation of the checker's model, with no change after the release candidate.

## A14 (V1 R03): guard categories

Found: "no pattern for: DynamoDB | no pattern for: vector index | 6 of 8 categories present (static; not run)".

Answer:
- The guard's destructive-AWS rule is service-generic. It matches delete, terminate, remove, purge, deregister and similar verbs for every service (.claude/hooks/guard-dangerous.sh:1123), with the message at :1124: "… denied for S3, DynamoDB, Neptune, S3 Vectors, ECR, KMS, EMR, and every other service". DynamoDB and S3 Vectors are also named in the header at :12. So there is no DynamoDB- or vector-index-specific pattern.
- Probe (runbook-log row "E12 A14 guard probes"): an `echo` of a DynamoDB delete-table command and an `echo` of an S3 Vectors delete-index command were both BLOCKED with that message. Nothing ran.
- The spec requirements hold: docs/agent-runs/phase-17/course-specification.md:363 (guard blocks "DynamoDB, and vector-index commands") and :384 ("Block destructive AWS CLI operations such as deleting … tables, … vector buckets, vector indexes …").
- V3 flagged the same contradiction and judged FACTORY-05 met ("generic pattern covers DynamoDB, S3 Vectors").
- This is a static false positive, with no change after the release candidate.

## A15 (V1 V01): model IDs in source

Found: "modules/rag/src/main/scala/org/scholarchain/rag/embedding/EmbeddingModelSpec.scala:55: model ID | modules/rag/src/main/scala/org/scholarchain/rag/runtime/Llm.scala:638: model ID".

Answer: answered in docs (Phase 17 E12 autograder pass): model-ID rationale. :55 is the Bedrock Titan embedding model constant. :638 adds the `anthropic.` provider prefix to a configured answer-model ID. Doc location: docs/architecture.md:256-263 (section 7.1).

## A16 (V1 A01): Spark `.collect`

Found: 18 `.collect` sites in JsonlExport.scala, Output.scala, Paths.scala and Pipeline.scala.

Answer: answered in docs (Phase 17 E12 autograder pass). Fact basis (review-report-A16-A17.md):
- There are 18 `.collect()` calls in modules/spark/src/main, matching the autograder's count.
- 17 are bounded: 11 by a literal limit or a hard-capped config value, and 6 by config values without an upper validation cap (pathMaxDepth, diameterMaxDepth, window count).
- 1 is recorded as a known limitation: Pipeline.scala:1116 `sizeBuckets`. It groups by data-valued size, so it returns at most about √(2N) rows and grows with input.

Doc location: docs/architecture.md:265-314 (section 7.2, all 18 calls), pointer at :206; known limitation in reports/final-report.md:619.

## A17 (V1 U01): bash strict mode

Found: "not in strict mode (set -euo pipefail), directly or via a sourced library: scripts/emulator/env.sh".

Answer: answered in docs (Phase 17 E12 autograder pass). Fact basis:
- env.sh is only ever sourced, by the human's terminal. Nothing in the repository executes or sources it.
- It does not set strict mode by design: env.sh:13 ("It changes no shell option"), because setting options would change the human's interactive shell.
- If run instead of sourced, it exits 2 (env.sh:60-63).
- Its git mode is 100644, with no executable bit (`git ls-files -s`).
- setup.sh:10 and probes.sh:13 set strict mode themselves (review-report-A16-A17.md; confirmed again in review-report-autograder.md).

Doc location: docs/emulator.md:50-56.

## B1 (V2 HADOOP-04): bounded blocking

Found: "Block-size cap, oversize counters, fallback families and MatchCandidate output exist. But the cap is applied in the reducer: the mapper emits all members and the whole oversize group still reaches one reducer (streamed, not buffered). No pre-pass, salting or sampling."

Answer: answered in docs (Phase 17 E12 autograder pass) as a known limitation. Target: docs/hadoop-job-contracts.md:208-214 (section 13.1); reports/final-report.md:617; docs/service-limits.md:120.

## B2 (V2 HADOOP-11): job contracts

Found: "Job contracts are in Scaladoc, the defaults XML and agent reports … but no standalone per-job contract document with types, S3 prefixes, reducers, codec and fixture for each job was found."

Answer: answered in docs (Phase 17 E12 autograder pass): a new per-job contract document, docs/hadoop-job-contracts.md, linked from the README index and docs/architecture.md (human decision, runbook-log row "E12 autograder results"). Target: docs/hadoop-job-contracts.md (227 lines, sections per job J01 to J09), linked from README.md:441 (D06 row) and docs/architecture.md:204.

## B3 (V2 HADOOP-PAIRS-WRONG, minor)

Found: "Teams over the hard limit (default 5000) are rejected with no pairs; teams of 100-5000 are expanded. The drop is deliberate, counted and rejected, so only minor." Cited: modules/hadoop/src/main/resources/scholarchain-hadoop-defaults.xml:191-192.

Answer: answered in docs (Phase 17 E12 autograder pass) as a known limitation. Target: docs/hadoop-job-contracts.md:216-227 (section 13.2); reports/final-report.md:618; docs/service-limits.md:121.

## C1 (V3 FACTORY-07): phase records

Found (result.json and run-1.answer.json:290): "Sampled phases have work orders with ordering and gates, agent/repair/review/test reports, and human gates with accepted commits. But the deterministic check finds some commit.txt hashes (phases 12, 14) are not in this repo, and early reports lack some contract fields." Category summary (run-1.answer.json:488): "some phase commit records (phases 12 and 14) name hashes the repo doesn't contain, so FACTORY-07 is partial."

Answer:
- The judge's stated reasons are G02 and E01. Both are answered above. The "hashes" are signed-plan sha256 prefixes in commit subjects, and every real hash in those files resolves (A2). The early reports predate the Phase 1 report contract, and phase-02 has the field (A7).
- The judge's reason does not mention the missing Phase 17 gate files. The gate commit adds `human-gate.md` and `commit.txt` for Phase 17, which clears V1 P01 for phase-17 (A1).
- That the gate commit alone clears FACTORY-07 is (not verified): the judge's text ties the partial to A2 and A7, which stay unchanged by human decision.

## Other rows

- V3 FACTORY-05: met, but queued for a human (`v1_contradiction`: V1 R03). Answered in A14.
- V2 HADOOP-12: met, `needs_human: always_human` (EMR run evidence cannot be confirmed from the repository alone). No action.
- No other V1 FAIL or WARN rows. V2 `dataset_engineering` and `spark_analytics` were all met. The other V3 deductions do not apply.
