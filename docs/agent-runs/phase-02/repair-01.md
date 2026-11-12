# Phase 2 repair round 1

## 1. Work order given to build-engineer

Phase 2 repair round 1: work order for build-engineer.

Context: you created the Phase 2 build (work order `docs/agent-runs/phase-02/prompt.md`; your report is `docs/agent-runs/phase-02/agent-report.md`). code-reviewer returned APPROVE with minor findings (full report: `docs/agent-runs/phase-02/review-report.md`). The human architect has routed findings B1–B6 to you as repair round 1. Findings F1–F4 are deferred by the human (F1/F2 to a later factory-engineer amendment; F3 and F4 accepted for now): do not act on them.

Human decision still in force: Phase 2 is verified with `sbt Test/compile`. `sbt check` cannot pass until Phase 3 adds tests; markTestRun's "No tests executed" failure is expected and is not a defect. Do not add tests outside your territory. Do not commit or push.

Repair these findings, only within your owned artifacts:

- B1 (build.sbt `forkedJava17Tests`): the Spark launcher JVM options, including `-Dio.netty.handler.ssl.defaultEndpointVerificationAlgorithm=NONE`, are applied to `hadoop` as well as `sparkAnalytics`. Apply the Spark list only to `sparkAnalytics`. Give `hadoop` only the options it needs, and none of the Netty TLS or allocator properties.
- B2 (.gitignore): add ignore rules for Terraform plan files (`*.tfplan`, `tfplan`, `*.plan`), `*.tfvars` (keeping `*.tfvars.example` tracked), `override.tf` / `*_override.tf`, `credentials*.json`, `.netrc`, `id_rsa*`, `id_ed25519*`, consistent with the guard's `is_secret_path` (`.claude/hooks/guard-dangerous.sh` around line 119).
- B3 (.gitignore): remove `!.env.example`. CLAUDE.md names placeholders `env.example` / `*.example`, which `!*.example` already covers. Make sure no rule re-includes a `.env*` file.
- B4 (environment input surface): the build reads no environment variables itself, but its behavior depends on tool-level ones (`JAVA_HOME`, `SBT_OPTS`/`JAVA_OPTS`, `COURSIER_REPOSITORIES`/`COURSIER_CACHE`, and `PATH`/`DOCKER_HOST`/`DOCKER_BUILDKIT` for dockerLocal). Document them explicitly in the build.sbt header comment and in your report, and say which are supply-chain inputs. Do not make the build read new environment variables.
- B5 (build.sbt `--exclude-tags=RequiresAws`): add a documented way to run `RequiresAws` tests from sbt that is not an environment variable (for example a separate sbt configuration or a setting/command toggle), while `check` and `markTestRun` keep excluding them. Prove both behaviors with a scratchpad probe, not with repository test files.
- B6 (markTestRun standalone): make `markTestRun` unable to write the marker unless `ledgerCheck`, `scalafmtSbtCheck`, and `scalafmtCheckAll` have passed in the same invocation, or document precisely why that is not feasible. The marker must still be written only after tests pass.

Also run `sbt dockerLocal` once (the human confirmed Docker Desktop is running; pulling the pinned base image is approved for this run). Report the exact outcome: exit code, image ID or digest, and, if it succeeds, `docker image inspect` evidence for the user (`10001:10001`), `JAVA_TOOL_OPTIONS`, and the base image digest. Do not push the image anywhere or run any registry login. If it fails, report the observed error. Do not work around the failure outside your territory.

Verify: `sbt Test/compile` (must pass) and `sbt check` (expected to fail only at markTestRun with "No tests executed", and to show the ledger and format steps passing). Report observed output only, and confirm `.claude/.last-test-run` was not created in the repository. Probe behaviors (B5, B6 fail paths) in a scratchpad copy of the build only.

End with the standard report contract. Add a per-finding section (B1–B6) giving file:line and what changed, and an updated environment input surface.

## 2. build-engineer report

All six findings (B1–B6) are repaired in `build.sbt` and `.gitignore`. `sbt Test/compile` passes. `sbt check` fails only at markTestRun with "No tests executed", as expected for Phase 2. `sbt dockerLocal` built the image (exit 0).

Two things you should know first:
- **`check` alias changed.** To fix B6 it is now `addCommandAlias("check", "markTestRun")`, and markTestRun runs every step itself in order. The steps are no longer listed as separate commands.
- **The `.env*` ignore rules (B3) were not checked with a command.** The guard blocks any shell command that names secret-pattern files (reported under "BLOCKED-ON evidence" below). That part rests on git's rule that a later matching line wins.

```text
STATUS: COMPLETE
OWNER: build-engineer
WORK ORDER: Phase 2 repair round 1 (B1-B6; F1-F4 deferred by the human), docs/agent-runs/phase-02/review-report.md
FILES CREATED: none in the repository. Scratchpad probe copies only:
  /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/2ebbd3e1-3f75-4b45-9a42-76083650c53d/scratchpad/probe1
  /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/2ebbd3e1-3f75-4b45-9a42-76083650c53d/scratchpad/probe6a ... probe6d
FILES MODIFIED:
  /Users/brian/Developer/repos/scholarchain/build.sbt
  /Users/brian/Developer/repos/scholarchain/.gitignore
COMMANDS RUN (all observed):
  sbt -batch scalafmtSbt Test/compile -> 1st try: build load error (scalafmtSbtCheck needs the Compile scope); fixed; 2nd try [success]
  sbt -batch Test/compile -> EXIT=0
  sbt -batch check -> EXIT=1 (expected; output under TEST RESULTS)
  sbt -batch "show {hadoop,sparkAnalytics,entityResolution}/Test/javaOptions" "show hadoop/Test/fork" "show core/AwsTest/testOptions" "show core/Test/testOptions" "show sparkAnalytics/AwsTest/fork" "show sparkAnalytics/AwsTest/javaOptions"
  git check-ignore -v --no-index <non-secret sample paths>
  Scratchpad: sbt check, sbt awsTest, sbt "core/AwsTest/testOnly probe.ProbeSuite -- *passes", sbt markTestRun (x4 fail cases)
  sbt -batch dockerLocal -> EXIT=0; docker image inspect; docker history
TEST RESULTS:
  Repository `sbt check` output: "Ledger check passed (scalafmt 3.11.5)."; "scalafmt: Checking 2 Scala sources" (twice); "Module tests: suites=0 executed=0 ..."; "Contract tests (no AWS): suites=0 executed=0 ..."; "[error] (markTestRun) No tests executed; .claude/.last-test-run was not updated. A passing test must exist."
  `ls -a .claude` shows no .last-test-run in the repository.
VALIDATION RESULTS:
  B5 probe (probe1; one untagged test that passes, one tagged test that passes, one tagged test that always fails):
    sbt check -> EXIT=0. "Module tests: suites=2 executed=2 passed=2" (1 untagged test per core row: Scala 3 and 2.13). The tagged test that always fails did not run. The scratchpad marker was written.
    sbt awsTest -> EXIT=1. Only the 2 tagged tests ran per row ("Total 2, Failed 1, Passed 1"); the failure was the always-failing tagged test. The marker's modification time did not change.
    sbt "core/AwsTest/testOnly probe.ProbeSuite -- *passes" -> EXIT=0, "Passed: Total 1".
  B6 probes (sbt markTestRun run alone, fresh copies with no marker; .claude was absent afterwards in every case):
    a) version.scalafmt=3.11.4 in the ledger -> EXIT=1 "(ledgerCheck) .scalafmt.conf version 3.11.5 does not match ..."; no compile or tests ran
    b) unformatted test source -> EXIT=1 "(core / Test / scalafmtCheck) scalafmt: 1 files must be formatted" (and core2_13)
    c) unformatted build.sbt -> EXIT=1 "(Compile / scalafmtSbtCheck) scalafmt: 1 files must be formatted"
    d) an untagged test that fails -> EXIT=1 "Module tests: suites=4 executed=4 passed=2 failed=2 ..."; "(moduleTests) Module tests failed"
  B1 check: hadoop/Test/javaOptions lists only 9 --add-opens entries. sparkAnalytics keeps the full Spark list. entityResolution has none.
  B2 check (git check-ignore): *.tfvars, *.tfplan, tfplan, *.plan, override.tf(.json), *_override.tf are ignored. prod.tfvars.example, env.example and config.example are re-included. main.tf and build.sbt are not ignored.
ASSUMPTIONS:
  - The Hadoop module-opens list is minimal on reasoning, not tested; module tests do not exist yet.
  - The `check` alias change described above is acceptable. The order is still: format checks, compilation, tests, contract tests, marker.
RISKS:
  - Hadoop 3.4 on Java 17 may need more opens once real MapReduce local-runner tests exist. Add them to hadoopJava17Options only, never the Spark list.
  - AwsTest extends Test, so hadoop and sparkAnalytics AwsTest runs inherit the fork setting and that module's own JVM options (confirmed for sparkAnalytics).
  - Scala 3 / Spark: unchanged from the original report (Spark stays on Scala 2.13; core is cross-built).
  - dockerLocal: Docker prints "InvalidDefaultArgInFrom". This is intentional: there is no default base image, so a build without the ledger value fails.
  - The services jar has no main class yet, so the image builds but will not start a service.
  - The image was built for linux/arm64 (host architecture), not for the Fargate target architecture.
MANUAL ACTION REQUIRED:
  - Optionally remove the local image scholarchain-services:local and the scratchpad probe directories. I did not, because the guard blocks recursive deletes there.
  - Re-run `sbt check` after Phase 3 adds tests.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none. For the record, guard-dangerous.sh blocked 3 commands, and I worked within each block:
  (1) a shell write or check-ignore naming secret-pattern files -> used the Edit tool, and checked only non-secret paths;
  (2) rm -rf in the scratchpad -> used new directory names;
  (3) a command naming the marker path -> used ls/stat on the directory instead.
NEXT GATE: code-reviewer re-review of B1-B6; human ratification; Phase 3 tests, then `sbt check` passing end to end.
```

**Per-finding changes**

- **B1** (`build.sbt`):
  - Lines 98-125: `sparkJava17Options` is the Spark launcher copy, still including the Netty TLS and allocator properties.
  - Lines 127-144: `hadoopJava17Options` holds 9 `--add-opens` entries, each with a stated reason. It has no Netty, TLS, allocator, `jdk.incubator.vector` or `-XX` options.
  - Line 146: `def forkedTests(options)`.
  - Line 204: hadoop uses `forkedTests(hadoopJava17Options)`.
  - Line 228: sparkAnalytics uses `forkedTests(sparkJava17Options)`.
  - The old `forkedJava17Tests` is removed.
- **B2** (`.gitignore`):
  - Lines 38-45: `id_rsa*`, `id_ed25519*`, `id_ecdsa*`, `id_dsa*`, `.netrc`, `*credentials*.json`, `secrets/`, `.secrets/`. These follow the guard's `is_secret_path`, which uses `*credentials*.json`, a superset of `credentials*.json`.
  - Lines 51-59: `*.tfplan`, `tfplan`, `*.plan`, `*.tfvars`, `!*.tfvars.example`, `override.tf`, `override.tf.json`, `*_override.tf`, `*_override.tf.json`.
- **B3** (`.gitignore`): `!.env.example` is removed. `!*.example` is now line 26, before every secret rule (lines 29-45). Because later rules win, `.env`/`.env.*` (lines 29-30) apply after it, so no rule re-includes a `.env*` file.
- **B4** (`build.sbt` lines 6-22): new header block listing the environment variables (table below). No new environment reads were added; there is still no `sys.env` or `envVars`.
- **B5** (`build.sbt`):
  - Lines 59-75: a new sbt configuration `AwsTest` (`config("awstest").extend(Test)`) that shares the Test sources. It passes MUnit `--include-tags=RequiresAws`.
  - Line 96: wired into `commonSettings`. `.configs(AwsTest)` is added to every project, including root (root also gets the settings at line 349).
  - Line 408: alias `awsTest` = `AwsTest/test`. Per project: `<project>/AwsTest/test` or `AwsTest/testOnly`.
  - `Test` still uses `--exclude-tags=RequiresAws`, and markTestRun reads only `Test / executeTests`. AwsTest never writes the marker.
- **B6** (`build.sbt`):
  - Lines 350-357 (comment) and 358-396: markTestRun is now a staged task. Each stage starts only after the previous one succeeds in the same run: (1) `ledgerCheck`, `Compile / scalafmtSbtCheck` and `scalafmtCheckAll` in every project; (2) `Test / compile` in every project; (3) module tests; (4) contract tests; (5) write the marker only if at least one test ran.
  - Line 406: `check` alias = `markTestRun`, as described at the top.

**Environment input surface (updated)**

build.sbt and project/** read no environment variables. The tools that run the build read these. Supply-chain inputs are those that decide which compiler, artifacts or images enter the build.

| Variable | Supply-chain input | What it controls |
|---|---|---|
| `JAVA_HOME` | yes | Which JDK runs sbt (onLoad rejects any Java other than 17) |
| `PATH` | yes | Which sbt, java and docker executables run |
| `SBT_OPTS` / `JAVA_OPTS` | yes | Launcher options; can redirect repositories or set proxies |
| `COURSIER_REPOSITORIES` | yes | Where artifacts are downloaded from |
| `COURSIER_CACHE` | yes | The artifact cache, which is trusted |
| `DOCKER_HOST` | yes | Which Docker daemon builds the image and pulls the base image |
| `DOCKER_CONFIG` | yes | Registry mirrors and credentials used for the base image pull |
| `DOCKER_BUILDKIT` | no | Builder selection; BuildKit is needed for `COPY --chmod` |

**dockerLocal outcome**
- Exit code 0. The command passed `--build-arg JAVA_RUNTIME_IMAGE=eclipse-temurin:17.0.21_9-jre-noble@sha256:042c05a77eb915684fc8c92cfbbef4fd6f8c24ca7d934d1a7db3543453aaa077`.
- Base image: the build log shows `FROM docker.io/library/eclipse-temurin:17.0.21_9-jre-noble@sha256:042c05a77eb915684fc8c92cfbbef4fd6f8c24ca7d934d1a7db3543453aaa077`. The base is held in BuildKit's cache, not under that name in the local image store.
- Image ID: `sha256:06ef026456d08798f862706d6573973c4ef101f328bbfc0de1f58400309ad5ef`. RepoDigests: `scholarchain-services@sha256:06ef0264...` (local only; nothing was pushed and there was no registry login). linux/arm64.
- `docker image inspect` shows:
  - `User=10001:10001`
  - `JAVA_TOOL_OPTIONS=-XX:InitialRAMPercentage=50.0 -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError`
  - Entrypoint `["java","-jar","/opt/scholarchain/app.jar"]`
  - WorkingDir `/opt/scholarchain`

**Version ledger and module graph:** unchanged. Every version is still in `/Users/brian/Developer/repos/scholarchain/project/build.properties`. Modules: core (Scala 3 plus a 2.13 row) <- hadoop, entityResolution, storage; core 2.13 row <- sparkAnalytics; storage <- rag; core, rag, storage <- services; contractTests depends on everything except sparkAnalytics. There are no cycles and nothing depends on services.

**Packaging:** unchanged. sbt-assembly fat jars; Provided dependencies are excluded and the Scala library is not bundled for sparkAnalytics. The image uses the pinned Temurin 17 JRE (tag plus digest), user 10001, percentage-based memory, and has no default base image.

## 3. code-reviewer re-review report

VERDICT: APPROVE

This is the re-review of build-engineer's Phase 2 repair round 1. I read the current files in full: `/Users/brian/Developer/repos/scholarchain/build.sbt`, `/Users/brian/Developer/repos/scholarchain/.gitignore`, `/Users/brian/Developer/repos/scholarchain/.claude/hooks/verify-tests-ran.sh`, `/Users/brian/Developer/repos/scholarchain/.claude/hooks/format-scala.sh`, and `is_secret_path` in `/Users/brian/Developer/repos/scholarchain/.claude/hooks/guard-dangerous.sh` (lines 113-125). As instructed, I did not re-raise F1-F4 or "No tests executed". Five of the six findings are resolved and one is partly resolved. The repair introduces one new defect, and it is minor. No critical or major finding remains.

### Status of B1-B6

| # | Status | Evidence |
|---|---|---|
| B1 | Resolved | `build.sbt:102-125`: the Spark list, including `-Dio.netty.handler.ssl.defaultEndpointVerificationAlgorithm=NONE` at line 123, is applied only to `sparkAnalytics` (line 228). `hadoop` (line 204) gets `hadoopJava17Options` (lines 134-144), which contains only `--add-opens` entries: no Netty, TLS, allocator or `--add-modules`. An `--add-opens` for an absent module only produces a JVM warning, so the jgss entry is safe. |
| B2 | Resolved | `.gitignore:38-45` adds `id_*`, `.netrc`, `*credentials*.json`, `secrets/`, `.secrets/`. Lines 51-59 add plan files, `*.tfvars` (lines 54-55), and the override files. |
| B3 | Resolved | `!.env.example` is removed. `!*.example` is now at line 26, before every secret rule, and git applies the last matching pattern. So `.env.example` is ignored again by `.env.*` (line 30), which matches the guard (`guard-dangerous.sh:119`). `env.example` (no leading dot) is not matched by any rule and stays tracked. `!*.example` effectively does nothing, which is harmless. `!*.tfvars.example` (line 55) is redundant, because `*.tfvars` never matches `x.tfvars.example`, but also harmless. |
| B4 | Partially resolved (minor) | `build.sbt:6-22` now lists tool-level inputs and marks the supply-chain ones. But line 22 says "No other input is documented", and the list leaves out inputs that do change the build: `JAVA_TOOL_OPTIONS` (HotSpot reads it in the sbt JVM and in every forked test JVM, so it can inject `-javaagent`), `JDK_JAVA_OPTIONS` (read by the `java` launcher on JDK 9+), and the Coursier mirror and credential inputs (`COURSIER_MIRRORS`, `COURSIER_CREDENTIALS`, `COURSIER_CONFIG_DIR`). The `JAVA_TOOL_OPTIONS` gap stands out because the services Dockerfile itself uses that variable. See N1. |
| B5 | Resolved | `build.sbt:66-73`: `AwsTest` extends `Test` with `inConfig(AwsTest)(Defaults.testTasks)`, the documented sbt pattern for sharing test sources. `AwsTest / testOptions :=` (line 70) replaces the options rather than adding to them, so the `--exclude-tags` that `Test` adds (line 88) does not apply. `AwsTest / test / testOptions` builds on that replaced value. `fork` and `javaOptions` are not set in AwsTest, so they fall back to `Test / fork` and `Test / javaOptions`: hadoop and spark keep their own fork options and the other modules stay unforked. Every aggregated project, root included (`.configs(AwsTest)` at lines 185, 199, 211, 222, 237, 246, 258, 298, 332; root gets `awsTestSettings` at line 349), defines the configuration, so the `awsTest` alias (line 408) aggregates cleanly. AwsTest cannot write the marker: `moduleTests` and `contractTestsNoAws` read only `Test / executeTests` (lines 341, 346), and nothing in the `AwsTest` scope refers to `markTestRun`. |
| B6 | Resolved | `build.sbt:358-389`. In the outer `Def.taskDyn`, `ledgerCheck` and the two scalafmt checks across all projects are dependencies of the outer body, so they finish before the body returns the stage-2 task. Stage 2 (`Test/compile`, line 363) is created only after stage 1 succeeds. Stage 3 (`moduleTests`, line 365) is created only after stage 2. `contractTestsNoAws` (line 367) is inside the innermost `Def.task`, which is created only after `moduleTests` has returned a value. Each inner `Def.task`/`Def.taskDyn` macro expands its own `.value` calls, so nothing is pulled up into an earlier stage. A failure at any stage throws before `IO.write` (line 376). `Compile / scalafmtSbtCheck` is the right scope, because sbt-scalafmt defines the sbt-file check per configuration. `inAnyProject` covers root, which holds `build.sbt` and `project/*.scala`. For AwsTest, `scalafmtCheck.?` resolves to the same task as `Test / scalafmtCheck`, and sbt runs that task once. |

### Other points you asked about

- **`check` = `markTestRun` (line 406):** not a defect. `markTestRun` is defined only on root, and it still covers everything CLAUDE.md lists: format, compile, tests, contract tests, then the marker. The guidance text in `verify-tests-ran.sh:121` is still accurate.
- **Hooks:**
  - `format-scala.sh` is unaffected (`scalafmtOnly / aggregate := false` is still at line 338).
  - `verify-tests-ran.sh` still depends only on the marker path, which is unchanged at line 375.
  - The guard's `is_secret_path` is unchanged and agrees with the new ignore rules (it also blocks `*/secrets/*`).

### New findings

| # | Severity | file:line | Failure input / evidence | Impact | Owner |
|---|---|---|---|---|---|
| N1 | minor | `/Users/brian/Developer/repos/scholarchain/build.sbt:22` (list at 10-21) | `JAVA_TOOL_OPTIONS=-javaagent:/x.jar sbt check` changes the sbt JVM and every forked test JVM. The same goes for `JDK_JAVA_OPTIONS` and for `COURSIER_MIRRORS` or `COURSIER_CREDENTIALS`, which redirect or authenticate artifact resolution. None is listed, yet line 22 says the list is complete. | This is the rest of B4. The documented environment surface understates supply-chain inputs. Add these, or change "No other input" to a statement that does not claim completeness. | build-engineer |
| N2 | minor | `/Users/brian/Developer/repos/scholarchain/.gitignore:28` | The comment says the rules "mirror is_secret_path", but `guard-dangerous.sh:122` also treats `*/.aws/*`, `*/.ssh/*`, `*/.gnupg/*`, `*/.config/gh/*` and `.docker/config.json` as secrets, and `.gitignore` has no rule for them. Example: a repo-local `.aws/credentials` file (no `.json` extension, so not matched by line 43) would be committed by `git add -A`. | The documentation claim is not true, and a credential file could be committed. Add `.aws/`, `.ssh/`, `.gnupg/`, `.config/gh/`, `.docker/config.json`, or reword the comment. | build-engineer |

STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 2 repair round 1 re-review (read-only) of build-engineer's changes to build.sbt and .gitignore responding to B1-B6 in docs/agent-runs/phase-02/review-report.md; F1/F2 deferred and F3/F4 accepted by the human (not re-raised); "No tests executed" expected.
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools)
TEST RESULTS: Not run (no Bash). The claimed `sbt Test/compile` exit 0, `sbt check` failing only at "No tests executed", and the scratchpad probes are build-engineer's claims and I could not observe them. The repository has no `.claude/.last-test-run`, which the orchestrator confirmed. I found nothing in build.sbt that writes it outside line 376.
VALIDATION RESULTS: I matched the diff against the current build.sbt (lines 1-414) and .gitignore (lines 1-62). I checked the stage ordering, AwsTest scoping and delegation, the scope filters, and the gitignore last-match-wins ordering by reading them. I confirmed the hook interactions.
ASSUMPTIONS:
- sbt-scalafmt defines `scalafmtSbtCheck` per configuration (Compile and Test) and fails by throwing.
- MUnit `--include-tags` runs only tagged tests.
- sbt scope delegation sends `AwsTest` to `Test` for `fork` and `javaOptions`.
All three match the documented behaviour, and build-engineer's probes are consistent with them.
RISKS:
- The Hadoop add-opens list (build.sbt:134-144) and its per-entry rationale (lines 129-133) are not verified against Hadoop 3.4. No Hadoop test exists yet, so a missing open, for example `java.util.concurrent.atomic` or `sun.nio.cs`, would only show up at Phase 3.
- `.gitignore` `*.plan` (line 53) and `*.tfvars` (line 54) could silently drop legitimate future files, such as a committed non-secret per-environment `.tfvars` from infra-engineer. `secrets/` (line 44) also matches any package directory named `secrets` (for example a Secrets Manager adapter package), as the guard already does. infra-engineer and storage-engineer should know this.
- The `dockerLocal` and image-digest evidence is claimed, not observed.
MANUAL ACTION REQUIRED: none required for approval. Optionally route N1 and N2 to build-engineer.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Orchestrator presents this verdict to the human architect, with N1 and N2 as optional minor repairs for build-engineer. The human ratifies Phase 2, with `sbt check` expected to fail only at "No tests executed" until Phase 3.
