# Amendment 01 — factory-engineer agent report (original run)

> Orchestrator note: the report below is factory-engineer's final report, copied verbatim. Two transport artifacts are noted rather than altered in meaning: the harness escaped `->` as `-&gt;` in one probe line (restored here to `->`), and section 7 says the diff is "above" although the agent did not include it in its returned report. The pre-repair working-tree diff, captured by the orchestrator with `git diff` immediately after this run, is appended as Appendix A and is labelled as orchestrator-captured.

---

I've made Amendment 01 as a proposal. It isn't committed and doesn't take effect until you ratify it and restart the session. All checks pass: validator 64/0, guard probes 51/0, JSON, `bash -n` and exec bits.

```text
STATUS: COMPLETE
OWNER: factory-engineer
WORK ORDER: Amendment 01, /Users/brian/Developer/repos/scholarchain/docs/agent-runs/amendment-01/prompt.md (authoritative). The pasted authority matches it word for word. The pasted copy also gives the commit hash, which matches docs/agent-runs/phase-02/commit.txt (41eaea2956f1608ba7f0631a5e9eba6b87f957e8), and spells out more run rules. These are additions, not conflicts.
FILES CREATED: none in the repository. One temporary probe script in the scratchpad: /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/be5db9c6-e07b-43c1-8baf-557dd8ba5b3e/scratchpad/guard-probes.sh
FILES MODIFIED: CLAUDE.md, docs/agents.md, .claude/hooks/guard-dangerous.sh, .claude/agents/dependency-updater.md, .claude/agents/build-engineer.md, .claude/agents/domain-engineer.md, .claude/agents/spark-engineer.md
COMMANDS RUN: cat/sed/grep/ls on the work order, phase-02 prompt.md, human-gate.md, commit.txt, project/build.properties, .scalafmt.conf, .gitignore, build.sbt (read-only); python3 -I -m json.tool and jq empty on both JSON files; bash -n and /bin/bash 3.2 -n on every hook; ls -l and the exec-bit check; frontmatter scan; .claude/hooks/validate-agent-files.sh; the guard probe script; the ownership audit greps; git status --short; git diff --stat; git diff
TEST RESULTS: sbt not run. No Scala source or build file changed, and the build is not mine to run. Guard probes: 51 passed, 0 failed.
VALIDATION RESULTS: JSON OK (both files, both tools); bash -n OK on all 4 hooks under bash and /bin/bash 3.2.57; all 4 hooks executable and referenced in settings.json; 18/18 agent files have name, description and an explicit tools field; validate-agent-files.sh: "64 checks passed, 0 failed." exit 0
ASSUMPTIONS: (1) The human-gate's "Scala 2.13.17 / 3.3.9" is the patch level; I wrote "Scala 2.13" and "Scala 3" as the work order says. (2) "Scala 2.13 dialect" in item 5 means the `scala213source3` dialect actually set in .scalafmt.conf; I named it exactly. (3) The ID `core2_13` comes from the build.sbt comment on line 203 (projectmatrix row). (4) For contractTests I recorded the wiring as it is in the accepted Phase 2 build, not as a new rule.
RISKS: (1) The guard can only check paths. It lets dependency-updater edit any line of project/build.properties. The "values only, never version.scalafmt" rule is in the role file and docs/agents.md, and a scalafmt-only bump would fail `sbt ledgerCheck` (per the comments in the ledger and .scalafmt.conf; I didn't run it). (2) Already true before this change: Bash file writes such as `sed -i` are not checked against territory for any agent with Bash, dependency-updater included. Only constitutional, evidence and marker paths are guarded in Bash.
MANUAL ACTION REQUIRED: Review the diff below, ratify (or reject parts), restart the session, record the gate and commit under docs/agent-runs/amendment-01/.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Human constitutional ratification of the diff, then a fresh session. Optionally a code-reviewer pass first.
```

## 1. Matrix basis
- Item 1 (ledger) → guard `DEPENDENCY_LEDGER_PATHS`, docs/agents.md (dependency-updater row, build-engineer row, rule 3), dependency-updater.md.
- Item 2 (Scala deviation) → CLAUDE.md stack and module table, build-engineer.md law 1, domain-engineer.md new law 2, spark-engineer.md new law 1. All of them cite docs/agent-runs/phase-02/prompt.md.
- Item 3 (runtimes) → CLAUDE.md "Target runtimes" line.
- Item 4 (`.sbt/**`) → docs/agents.md build-engineer row, guard `owner_of`.
- Item 5 (formatter) → CLAUDE.md Commands bullet.

## 2. Changes
- **CLAUDE.md**: deviation paragraph with citation; runtime targets; module table gains a Scala column plus `core2_13` and `contractTests` rows; formatter-dialect note.
- **docs/agents.md**: ledger path and the scalafmt exception (rule 3 and both rows); `.sbt/**` added to build-engineer.
- **guard-dangerous.sh**: `DEPENDENCY_LEDGER_PATHS="project/build.properties"`; `.sbt/*` maps to build-engineer.
- **dependency-updater.md**: ledger path; new law 2 forbidding scalafmt bumps; route scalafmt bumps to build-engineer; later laws renumbered.
- **build-engineer.md**: "Scala 3 everywhere" replaced with the deviation rule.
- **domain-engineer.md**: new law 2 (core must compile under Scala 3 and 2.13); later laws renumbered.
- **spark-engineer.md**: new law 1 (Scala 2.13, uses core through `core2_13`); later laws renumbered.

I checked that no file refers to these law numbers, so renumbering breaks nothing.

## 3. Privilege delta
- **dependency-updater: widened, as ratified.** Its write territory goes from empty to exactly `project/build.properties` (guard Edit path). Its tools are unchanged (still no Write).
- **build-engineer: widened, as ratified.** It now also owns `.sbt/**`; tools unchanged.
- **All other agents:** unchanged. No permissions, MCP or settings changes.

## 4. Floor invariants
All preserved, checked by probes: dangerous-command guards (`rm -rf /`, `rm -rf project`, dd to a disk, `git reset --hard` are blocked); test-stop hook untouched; formatter hook untouched; secret reads denied (`cat .env`, Read `.env`, `~/.aws/credentials`, tfstate; `env.example` still allowed); `terraform apply`, `destroy` and `state rm` blocked; destructive AWS calls blocked (`s3 rm --recursive`, `delete-table`, `terminate-clusters`, `schedule-key-deletion`); force pushes blocked (`--force`, `--force-with-lease`, `+refspec`). Subagent commits are blocked and orchestrator commits ask. Writes to the test marker, to constitutional files by non-factory agents, and to `docs/agent-runs/**` are all blocked.

## 5. Audits
- **Collision:** no duplicate agent names, descriptions or commands (validator). The one new guard pattern, `.sbt/*`, overlaps no other `owner_of` pattern.
- **Orphan:** the roster matches the docs/agents.md table (validator); every hook is referenced and present.
- **One writer:** `project/build.properties` has build-engineer as structural owner plus dependency-updater for values. This is the ratified split in rule 3, and the guard allows only these two (domain-engineer probe blocked). `.sbt/**` has one writer, build-engineer (the other three agents probed are blocked or asked).
- **Agreement between docs/agents.md, guard and role files:** the table and the guard agree. The role files show two gaps I didn't fix because they're outside the ratified list (open items 1 and 2).

## 6. Validation output (verbatim)
```
OK json.tool .claude/settings.json / OK jq .claude/settings.json
OK json.tool .mcp.json / OK jq .mcp.json
OK bash -n and OK /bin/bash(3.2.57(1)-release) -n for format-scala.sh, guard-dangerous.sh, validate-agent-files.sh, verify-tests-ran.sh
OK executable+referenced .claude/hooks/{format-scala,guard-dangerous,validate-agent-files,verify-tests-ran}.sh
frontmatter: 18 files, fields=3 each, explicit tools on every file
validate-agent-files.sh: 64 checks passed, 0 failed.  exit=0
```
Guard probes, new behaviour:
```
PASS  ALLOW  dependency-updater Edit project/build.properties   (absolute and relative)
PASS  BLOCK  dependency-updater Edit project/build.properties.bak  (allowance is exact-path only; the probe label in the script misnames this case)
PASS  BLOCK  dependency-updater Edit project/plugins.sbt | project/Ledger.scala | .scalafmt.conf | build.sbt | project/../… | CLAUDE.md
PASS  ALLOW  build-engineer Edit project/build.properties | .scalafmt.conf
PASS  BLOCK  domain-engineer Edit project/build.properties
PASS  ALLOW  build-engineer Write .sbt/matrix/x
PASS  BLOCK  domain-engineer / dependency-updater / factory-engineer write .sbt/matrix/x
PASS  ASK    orchestrator Write .sbt/matrix/x
  .sbt/matrix/x -> build-engineer        (owner_of extracted from the guard)
PASS  docs/agents.md, dependency-updater.md and the guard contain the scalafmt ledger rule
TOTAL: 51 passed, 0 failed
```
The 29 probes of existing denials all passed, as listed in section 4.

## 7. Diff
The complete `git diff` is above. That's 7 files, 48 insertions and 35 deletions; `git status` shows no new or deleted owned files.

## 8. Open items (need your decision; I made no change for these)
1. **build-engineer.md contradicts item 1.** Its refusal line "bump a version value only → `dependency-updater`" and its description "Not for … routine version bumps (dependency-updater)" don't make the scalafmt exception. The docs/agents.md build-engineer row also still says "Must not do: version-only maintenance." Item 1 doesn't list build-engineer.md, so I left it alone. Proposed fix: add "except the scalafmt version, which you bump in both `project/build.properties` and `.scalafmt.conf`".
2. **`.sbt/**` is missing from build-engineer.md's Owned artifacts.** Item 4 named only docs/agents.md and the guard, so the role file and the table now disagree.
3. **domain-engineer's routing description still says "immutable Scala 3 domain records".** That's now misleading given the cross-build. Approve a wording change?
4. **CLAUDE.md "Stack" still says "one test framework (ScalaTest or MUnit, chosen once)" and an unnamed JSON library.** Phase 2 chose MUnit and uPickle, but recording that wasn't ratified here.
5. **The `contractTests` wiring vs. CLAUDE.md's "No module depends on `services`".** I recorded contractTests as a test project, not a module, to keep it consistent. Please confirm that reading, or ratify an explicit rule for it.
6. **Is `image.java-runtime` (tag and digest) a "version value" dependency-updater may change?** The current wording allows it; say if you want it excluded like scalafmt.
7. **There is no committed guard self-test script.** The probes live only in the scratchpad. I can propose adding one under `.claude/hooks/` (it would need ratification and a settings or validator reference to avoid the orphan check).
8. **Bash writes still bypass territory checks** (risk 2, already true before this change). I can propose hardening this if you want.

---

## Appendix A — pre-repair working-tree diff (orchestrator-captured, not part of the agent's returned text)

Captured with `git diff` after the original run and before repair round 1 (7 files changed, 48 insertions(+), 35 deletions(-)).

```diff
diff --git a/.claude/agents/build-engineer.md b/.claude/agents/build-engineer.md
index 8336a07..99755e3 100644
--- a/.claude/agents/build-engineer.md
+++ b/.claude/agents/build-engineer.md
@@ -18,7 +18,7 @@ You own the build as executable policy: language versions, module graph, depende
 
 ## Non-negotiable laws
 
-1. Scala 3 everywhere. Every dependency and plugin version is pinned in one ledger. No floating or latest versions.
+1. Scala versions follow the instructor-approved deviation recorded in `CLAUDE.md` (source: `docs/agent-runs/phase-02/prompt.md`): `sparkAnalytics` is Scala 2.13, `core` is cross-built for Scala 3 and Scala 2.13 (`core` and `core2_13`), and every other project is Scala 3. Every dependency and plugin version is pinned in one ledger. No floating or latest versions.
 2. Enforce the module dependency boundaries in `CLAUDE.md`. No cycles. No module depends on `services`.
 3. Hadoop and Spark are `Provided` when the runtime supplies them; test libraries are `Test`; logging implementations are `Runtime`.
 4. `sbt check` runs format checking, compilation, tests, contract tests that do not need AWS, and `markTestRun`, in that order. `markTestRun` must never run when tests fail.
diff --git a/.claude/agents/dependency-updater.md b/.claude/agents/dependency-updater.md
index 746f00c..70b969f 100644
--- a/.claude/agents/dependency-updater.md
+++ b/.claude/agents/dependency-updater.md
@@ -10,16 +10,17 @@ You change version values and nothing else.
 
 ## Owned artifacts
 
-- Version string values in the approved dependency ledger. The ledger file is created by `build-engineer`; its path is recorded in `docs/agents.md` after human ratification. Until then, your write territory is empty and the guard hook blocks your edits.
+- Version string values in the dependency ledger `project/build.properties` (path ratified in `docs/agents.md`, ownership resolution rule 3), except the scalafmt version. `build-engineer` owns the ledger's structure.
 - Dependency update reports, returned to the orchestrator.
 
 ## Non-negotiable laws
 
-1. Edit only version values in the ledger. Never add or remove dependencies, change scopes, change module wiring, or edit any other file.
-2. No floating or latest versions; pin exact versions.
-3. Major upgrades, and any upgrade of Scala, Hadoop, Spark, or the JSON or test library, require explicit human approval in the work order.
-4. Check release notes and known vulnerabilities for each change; record sources.
-5. Run `sbt check` after the change. If it fails because code must change, revert nothing yourself beyond your own edit and return `BLOCKED-ON` the owning agent.
+1. Edit only version values in `project/build.properties`. Never add or remove keys, comments, or dependencies, change scopes, change module wiring, or edit any other file.
+2. Never change the scalafmt version (`version.scalafmt`). It is pinned in both `project/build.properties` and `.scalafmt.conf`, which must match; scalafmt bumps belong to `build-engineer`.
+3. No floating or latest versions; pin exact versions.
+4. Major upgrades, and any upgrade of Scala, Hadoop, Spark, or the JSON or test library, require explicit human approval in the work order.
+5. Check release notes and known vulnerabilities for each change; record sources.
+6. Run `sbt check` after the change. If it fails because code must change, revert nothing yourself beyond your own edit and return `BLOCKED-ON` the owning agent.
 
 ## Ordered procedure
 
@@ -34,6 +35,7 @@ You change version values and nothing else.
 Decline and route, making no change, when asked to:
 
 - add dependencies, plugins, or change build structure -> `build-engineer`
+- bump the scalafmt version -> `build-engineer`
 - change code to fit a new version -> owning module agent
 - perform a major upgrade without approval -> human architect
 - edit constitutional files -> `factory-engineer`
diff --git a/.claude/agents/domain-engineer.md b/.claude/agents/domain-engineer.md
index 5e71d04..428dacb 100644
--- a/.claude/agents/domain-engineer.md
+++ b/.claude/agents/domain-engineer.md
@@ -15,11 +15,12 @@ You freeze the shared data and wire contracts that every producer and consumer d
 ## Non-negotiable laws
 
 1. `core` depends on no internal module and on no Hadoop, Spark, AWS SDK, or HTTP library.
-2. Every record that represents data carries `Provenance`. Validation rejects missing provenance, invalid confidence ranges, unsupported schema versions, malformed stable IDs, invalid undirected pair ordering, and windows whose start year exceeds the end year.
-3. Stable IDs are deterministic functions documented in code and tests; never derived from wall-clock time or randomness.
-4. Configuration comes from environment variables plus versioned files. Never hardcode bucket names, endpoints, thresholds, model IDs, account IDs, graph windows, or algorithm parameters.
-5. Contract changes are versioned. A breaking change bumps the schema version and is called out in the report.
-6. Every behavior change has a feature test, including frozen JSON wire examples.
+2. Code in `modules/core/**` must compile under both Scala 3 and Scala 2.13 (`core` is cross-built as `core` and `core2_13`; instructor-approved deviation, source: `docs/agent-runs/phase-02/prompt.md`). Use no Scala-3-only syntax or libraries.
+3. Every record that represents data carries `Provenance`. Validation rejects missing provenance, invalid confidence ranges, unsupported schema versions, malformed stable IDs, invalid undirected pair ordering, and windows whose start year exceeds the end year.
+4. Stable IDs are deterministic functions documented in code and tests; never derived from wall-clock time or randomness.
+5. Configuration comes from environment variables plus versioned files. Never hardcode bucket names, endpoints, thresholds, model IDs, account IDs, graph windows, or algorithm parameters.
+6. Contract changes are versioned. A breaking change bumps the schema version and is called out in the report.
+7. Every behavior change has a feature test, including frozen JSON wire examples.
 
 ## Ordered procedure
 
diff --git a/.claude/agents/spark-engineer.md b/.claude/agents/spark-engineer.md
index 8a8a103..997b1b2 100644
--- a/.claude/agents/spark-engineer.md
+++ b/.claude/agents/spark-engineer.md
@@ -14,13 +14,14 @@ You compute the deterministic graph facts that every runtime answer depends on.
 
 ## Non-negotiable laws
 
-1. Use the `core` contracts. Edges record joint publication count, first and last collaboration year, and representative work IDs.
-2. Keep P_r, C_r, T_r, A_r, M_r, and W_r separate; never collapse them into one unexplained score.
-3. Every metric is time-aware and records its window, algorithm, parameters, and approximation limits.
-4. Never collect the full graph on the driver. Use scalable methods for paths, diameter, and centrality, and document approximations.
-5. Outputs are deterministic for fixed inputs and seeds, and carry provenance and run IDs.
-6. Every behavior change has a small known-graph test. Run `sbt check` before reporting COMPLETE.
-7. Do not submit EMR steps or change cluster capacity directly; cloud runs go through approved scripts.
+1. `modules/spark` (sbt project `sparkAnalytics`) is Scala 2.13 to match the Spark runtime and uses `core` through `core2_13`, the Scala 2.13 build of `core` (instructor-approved deviation, source: `docs/agent-runs/phase-02/prompt.md`).
+2. Use the `core` contracts. Edges record joint publication count, first and last collaboration year, and representative work IDs.
+3. Keep P_r, C_r, T_r, A_r, M_r, and W_r separate; never collapse them into one unexplained score.
+4. Every metric is time-aware and records its window, algorithm, parameters, and approximation limits.
+5. Never collect the full graph on the driver. Use scalable methods for paths, diameter, and centrality, and document approximations.
+6. Outputs are deterministic for fixed inputs and seeds, and carry provenance and run IDs.
+7. Every behavior change has a small known-graph test. Run `sbt check` before reporting COMPLETE.
+8. Do not submit EMR steps or change cluster capacity directly; cloud runs go through approved scripts.
 
 ## Ordered procedure
 
diff --git a/.claude/hooks/guard-dangerous.sh b/.claude/hooks/guard-dangerous.sh
index 8fe0d9e..60d7264 100755
--- a/.claude/hooks/guard-dangerous.sh
+++ b/.claude/hooks/guard-dangerous.sh
@@ -70,8 +70,10 @@ PROJECT_DIR="${CLAUDE_PROJECT_DIR:-$(jget cwd)}"
 PROJECT_DIR="${PROJECT_DIR%/}"
 
 # Dependency-ledger file(s) that dependency-updater may edit (version values only).
-# Empty until the human ratifies the ledger path created by build-engineer (Phase 2).
-DEPENDENCY_LEDGER_PATHS=""
+# Ratified in Amendment 01. The guard enforces the path only; the value-level rules
+# (version values only, never version.scalafmt, which build-engineer bumps together with
+# .scalafmt.conf) live in docs/agents.md rule 3 and are checked by sbt ledgerCheck.
+DEPENDENCY_LEDGER_PATHS="project/build.properties"
 
 matches() { printf '%s' "$1" | grep -Eiq -- "$2"; }
 
@@ -86,7 +88,7 @@ owner_of() {
     docs/agent-runs/*) echo "human" ;;
     docs/incidents/*) echo "incident-responder" ;;
     README.md|docs/*|reports/*) echo "documentation-engineer" ;;
-    build.sbt|project/*|.scalafmt.conf|.gitignore|.jvmopts|.sbtopts|docker/*|compose.yml|compose.yaml|docker-compose.yml|docker-compose.yaml|docker-compose.*.yml|docker-compose.*.yaml) echo "build-engineer" ;;
+    build.sbt|project/*|.scalafmt.conf|.sbt/*|.gitignore|.jvmopts|.sbtopts|docker/*|compose.yml|compose.yaml|docker-compose.yml|docker-compose.yaml|docker-compose.*.yml|docker-compose.*.yaml) echo "build-engineer" ;;
     modules/core/*) echo "domain-engineer" ;;
     datasets/*) echo "dataset-engineer" ;;
     modules/hadoop/*) echo "hadoop-engineer" ;;
diff --git a/CLAUDE.md b/CLAUDE.md
index 34d7b13..c7973fd 100644
--- a/CLAUDE.md
+++ b/CLAUDE.md
@@ -8,19 +8,25 @@ ScholarChain is a Scala 3, Hadoop, Spark, AWS, Graph-RAG project. It builds an e
 
 Pipeline: S3 raw zone -> Hadoop MapReduce on EMR (parse, normalize, author mentions, blocking, coauthor-pair candidates) -> S3 curated zone -> entity resolution -> Spark on EMR (graph construction and analytics) -> Neptune, DynamoDB, ordinary S3, S3 Vectors (optional OpenSearch) -> runtime collaboration-analysis agents -> REST microservices on ECS Fargate.
 
-Stack: Scala 3, sbt multi-project build, one pinned JSON library, one test framework (ScalaTest or MUnit, chosen once), Terraform, Amazon EMR, S3, Neptune, DynamoDB, S3 Vectors, Bedrock, ECS Fargate, Step Functions, SQS, ECR, Secrets Manager, KMS, CloudWatch.
+Stack: Scala 3 (with the instructor-approved Scala 2.13 deviation below), sbt multi-project build, one pinned JSON library, one test framework (ScalaTest or MUnit, chosen once), Terraform, Amazon EMR, S3, Neptune, DynamoDB, S3 Vectors, Bedrock, ECS Fargate, Step Functions, SQS, ECR, Secrets Manager, KMS, CloudWatch.
+
+Scala versions (instructor-approved deviation from "Scala 3 everywhere"; source: `docs/agent-runs/phase-02/prompt.md`, human architect decision 1, recorded in `docs/agent-runs/phase-02/human-gate.md`): `sparkAnalytics` compiles with Scala 2.13 to match the Spark runtime; `core` is cross-built for Scala 3 and Scala 2.13 (sbt projects `core` and `core2_13`, same sources); every other project is Scala 3.
+
+Target runtimes: Spark jobs target Amazon EMR release `emr-spark-8.1.0`; Hadoop MapReduce jobs target Amazon EMR 7.x; Java 17 for all projects and container images.
 
 ## Module dependency boundaries
 
-| sbt project | Directory | May depend on |
-|---|---|---|
-| `core` | `modules/core` | no internal module |
-| `hadoop` | `modules/hadoop` | `core` |
-| `entityResolution` | `modules/entity-resolution` | `core` |
-| `sparkAnalytics` | `modules/spark` | `core` |
-| `storage` | `modules/storage` | `core` |
-| `rag` | `modules/rag` | `core`, `storage` |
-| `services` | `modules/services` | `core`, `rag`, `storage` |
+| sbt project | Directory | Scala | May depend on |
+|---|---|---|---|
+| `core` | `modules/core` | 3 (cross-built with `core2_13`) | no internal module |
+| `core2_13` | `modules/core` (same sources as `core`) | 2.13 | no internal module; used only by `sparkAnalytics` |
+| `hadoop` | `modules/hadoop` | 3 | `core` |
+| `entityResolution` | `modules/entity-resolution` | 3 | `core` |
+| `sparkAnalytics` | `modules/spark` | 2.13 | `core2_13` (the Scala 2.13 build of `core`) |
+| `storage` | `modules/storage` | 3 | `core` |
+| `rag` | `modules/rag` | 3 | `core`, `storage` |
+| `services` | `modules/services` | 3 | `core`, `rag`, `storage` |
+| `contractTests` | `tests/contracts` | 3 | test project, not a module; Phase 2 build wires `core`, `hadoop`, `entityResolution`, `storage`, `rag`, `services` |
 
 Rules:
 
@@ -34,6 +40,7 @@ Rules:
 
 - `sbt check` is the repository definition of done. It runs format checking, compilation, tests, contract tests that do not need AWS, and `markTestRun`, which refreshes `.claude/.last-test-run` only when tests pass.
 - `sbt fmt` formats sources. `sbt Test/compile` compiles tests.
+- Formatter dialects (`.scalafmt.conf`, owned by `build-engineer`): Scala 3 by default; `core` (`modules/core/src/**`) and `sparkAnalytics` (`modules/spark/src/**`) are formatted with the Scala 2.13 dialect (`scala213source3`), a consequence of the Scala deviation above.
 - `.claude/hooks/validate-agent-files.sh` validates the agent system.
 - Build aliases are defined by `build-engineer` in `build.sbt`; until `build.sbt` exists, no Scala gate can pass and source-changing agents report `BLOCKED-ON` with owner `build-engineer`.
 
diff --git a/docs/agents.md b/docs/agents.md
index 0cbc385..c010c19 100644
--- a/docs/agents.md
+++ b/docs/agents.md
@@ -19,7 +19,7 @@ Exactly one writer per artifact. Paths are relative to the repository root. `**`
 | Agent | Creates and owns (exact paths) | Must not do | Primary gate |
 |---|---|---|---|
 | `factory-engineer` | `CLAUDE.md`, `docs/agents.md`, `.claude/agents/**`, `.claude/hooks/**`, `.claude/commands/**`, `.claude/settings.json`, `.mcp.json`, `docs/agent-runs/README.md` | Application, data, build, infrastructure, experiment, or deployment work; any other file under `docs/agent-runs/**` | Human constitutional ratification and code review |
-| `build-engineer` | `build.sbt`, `project/**` (structure of the dependency ledger included), `.scalafmt.conf`, `.gitignore`, `.jvmopts`, `.sbtopts`, local compose files (`compose.y*ml`, `docker-compose*.y*ml`), packaging under `docker/**` and packaging settings in `build.sbt`, the `markTestRun` task that writes `.claude/.last-test-run` | Application source, version-only maintenance, Terraform, deployment | `sbt check` and code review |
+| `build-engineer` | `build.sbt`, `project/**` (structure of the dependency ledger and the scalafmt version value included), `.scalafmt.conf`, `.sbt/**` (generated by sbt, gitignored), `.gitignore`, `.jvmopts`, `.sbtopts`, local compose files (`compose.y*ml`, `docker-compose*.y*ml`), packaging under `docker/**` and packaging settings in `build.sbt`, the `markTestRun` task that writes `.claude/.last-test-run` | Application source, version-only maintenance, Terraform, deployment | `sbt check` and code review |
 | `domain-engineer` | `modules/core/**`: shared domain records, stable identifiers, serialization contracts, configuration contracts, and their tests | Dataset retrieval, distributed jobs, storage adapters, service routes, infrastructure | Contract tests, `sbt check`, and code review |
 | `dataset-engineer` | `datasets/**`: `datasets/dataset-manifest.yaml`, `datasets/scripts/**` (bounded download and sampling), `datasets/schemas/**`, `datasets/samples/**`, license records | Hadoop analytics, Spark analytics, entity-match policy | Dataset validation tests and manifest review |
 | `hadoop-engineer` | `modules/hadoop/**`: MapReduce jobs, Hadoop integration tests, counters, rejected-record formats | Spark jobs, storage clients, service APIs | Local or EMR sample run and deterministic output checks |
@@ -35,7 +35,7 @@ Exactly one writer per artifact. Paths are relative to the repository root. `**`
 | `experiment-engineer` | `experiments/**` (experiment manifests, raw result collectors, raw results, derived-plot scripts), `load-tests/**` | Altering production algorithms to improve reported results; editing raw results after collection | Reproducible run and immutable raw results |
 | `documentation-engineer` | `README.md`, `docs/**` except `docs/agents.md`, `docs/agent-runs/**`, and `docs/incidents/**`; `reports/**` (final report), diagrams, demo script | Inventing results or changing implementation | Link check, command replay, evidence review |
 | `incident-responder` | `docs/incidents/**`, diagnosis reports, execution of approved rollback and bounded recovery actions through `scripts/rollback.sh` | Schema changes, destructive data repair, infrastructure authorship | Human escalation for data, security, or destructive actions |
-| `dependency-updater` | Version values in the approved dependency ledger (file designated by `build-engineer` and ratified by the human) and dependency update reports returned to the orchestrator | Build structure, application features, major upgrades without approval | `sbt check`, vulnerability review, code review |
+| `dependency-updater` | Version values in the dependency ledger `project/build.properties`, except the scalafmt version (`version.scalafmt`), and dependency update reports returned to the orchestrator | Build structure, application features, major upgrades without approval | `sbt check`, vulnerability review, code review |
 
 ### Human-owned and generated artifacts
 
@@ -50,7 +50,7 @@ Exactly one writer per artifact. Paths are relative to the repository root. `**`
 
 1. A path not covered above has no owner. The agent that needs it stops and returns `BLOCKED-ON factory-engineer`; the human ratifies an owner before work proceeds.
 2. Module feature tests live in the module's own test tree and belong to the module owner. Cross-module, end-to-end, contract, and adversarial tests live under `tests/**` and belong to `test-engineer`. Any further test tree must be designated here before `test-engineer` writes it.
-3. The dependency ledger is one file with a split: `build-engineer` owns its structure, `dependency-updater` may change only version string values. Its path is recorded here after the human ratifies it.
+3. The dependency ledger is one file, `project/build.properties` (ratified in Amendment 01), with a split: `build-engineer` owns its structure, `dependency-updater` may change only version string values. Exception: the scalafmt version is pinned in both `project/build.properties` (`version.scalafmt`) and `.scalafmt.conf` (`version`), which must match (`sbt ledgerCheck`); scalafmt version bumps stay with `build-engineer`, which changes both files together.
 4. Dockerfiles live under `docker/**` (build-engineer), never under `modules/**`.
 5. Write territories are enforced by `.claude/hooks/guard-dangerous.sh` (`owner_of`), which must mirror this table.
 
```
