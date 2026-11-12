VERDICT: APPROVE

I found no critical or major problems. There are 10 minor findings: 6 for build-engineer to fix now and 4 for factory-engineer or a human decision. I read every file in the diff and checked it against the Phase 2 prompt (preamble included), CLAUDE.md, docs/agents.md, .claude/agents/build-engineer.md, and the three hooks. As you instructed, I did not raise "sbt check fails because there are no tests". I also confirmed that `.claude/.last-test-run` does not exist in the repository, so nothing wrote the marker by hand or by mistake.

## Findings: build-engineer (fix now)

| # | Severity | file:line | Failure input / evidence | Impact | Owner |
|---|---|---|---|---|---|
| B1 | minor | /Users/brian/Developer/repos/scholarchain/build.sbt:152 (also 71-94, 96-99) | `forkedJava17Tests` is applied to `hadoop` as well as `sparkAnalytics`. It adds `-Dio.netty.handler.ssl.defaultEndpointVerificationAlgorithm=NONE` (line 92), which turns off Netty TLS hostname verification. | Any forked hadoop test that uses a Netty TLS client, such as a future `RequiresAws` S3A or SDK test run on purpose, would skip hostname verification. The comment says these are Spark's launcher options, so they are not needed for Hadoop. Apply the Spark options only to `sparkAnalytics`, or give Hadoop its own list without the Netty TLS and allocator properties. | build-engineer |
| B2 | minor | /Users/brian/Developer/repos/scholarchain/.gitignore:37-42 | No ignore rule for Terraform plan files (`*.tfplan`, `tfplan`, `*.plan`), `*.tfvars` (except `*.tfvars.example`), `override.tf`, or `credentials*.json`, `.netrc`, `id_rsa*`, `id_ed25519*`. The guard's `is_secret_path` (guard-dangerous.sh:119) treats several of these as secrets. | A plan file from `scripts/plan.sh`, or a tfvars file with real values, could be committed by `git add -A`. Plans contain resolved sensitive values. CLAUDE.md forbids committing credentials and state. | build-engineer |
| B3 | minor | /Users/brian/Developer/repos/scholarchain/.gitignore:27 | `!.env.example` re-includes a file that the guard treats as secret (`.env.*` at guard-dangerous.sh:119), so agents cannot write it. CLAUDE.md names the placeholder `env.example`, with no leading dot. | The policies contradict each other and the line is dead in practice. Remove it, or align it with `env.example` (`!*.example` already covers that). | build-engineer |
| B4 | minor | /Users/brian/Developer/repos/scholarchain/build.sbt:4-5 (claim "reads no environment variables") | The build itself reads no environment variables, but its behavior still depends on tool-level ones: `JAVA_HOME`, `SBT_OPTS`/`JAVA_OPTS`, `COURSIER_REPOSITORIES`/`COURSIER_CACHE` (these change where artifacts resolve from), and `PATH`/`DOCKER_HOST`/`DOCKER_BUILDKIT` for `dockerBuildLocal` (line 228). | The work order allows only explicitly documented environment variables. The report's environment input surface leaves these out, and `COURSIER_REPOSITORIES` is a supply-chain input. List them in the report and the build comment. | build-engineer |
| B5 | minor | /Users/brian/Developer/repos/scholarchain/build.sbt:58 | `--exclude-tags=RequiresAws` is added to every `Test / testOptions` unconditionally. `testOnly -- --include-tags=RequiresAws` cannot override it, because MUnit applies the exclude anyway. | There is no way to run the live-AWS tests from sbt without editing the build. Add a separate configuration or a setting-driven toggle that is documented and is not an environment variable. | build-engineer |
| B6 | minor | /Users/brian/Developer/repos/scholarchain/build.sbt:290-313 and alias line 330 | `markTestRun` is a standalone task. `sbt markTestRun` runs tests and writes the marker without `ledgerCheck` or the scalafmt checks. | The stop hook (verify-tests-ran.sh) is satisfied without a format check, so its stated meaning ("sbt check passed") is weaker than CLAUDE.md's definition of done. Write-only-after-tests-pass still holds. Consider making the format and ledger checks dependencies of `markTestRun`, or document the limitation. | build-engineer |

## Findings: other owners (route through the orchestrator)

| # | Severity | file:line | Evidence | Impact | Owner |
|---|---|---|---|---|---|
| F1 | minor | /Users/brian/Developer/repos/scholarchain/.claude/hooks/guard-dangerous.sh:74; docs/agents.md ownership rule 3; .claude/agents/dependency-updater.md:13,18 | `DEPENDENCY_LEDGER_PATHS=""`. The ledger path `project/build.properties` is not recorded yet. The scalafmt version is also pinned in `/Users/brian/Developer/repos/scholarchain/.scalafmt.conf:4`, and `ledgerCheck` requires the two to match. dependency-updater.md:18 forbids editing any file other than the ledger. | dependency-updater cannot edit anything until this is ratified. After that it still cannot bump scalafmt without failing `ledgerCheck`. Decide whether to also designate the `.scalafmt.conf` `version` line, or have build-engineer make scalafmt bumps. | factory-engineer (human ratification) |
| F2 | minor | /Users/brian/Developer/repos/scholarchain/.claude/agents/build-engineer.md:21; domain-engineer.md:3; CLAUDE.md module table | build-engineer law 1 says "Scala 3 everywhere". The instructor-approved deviation (sparkAnalytics on 2.13; `core` cross-built, with an extra sbt project `core2_13` and a `contractTests` project) exists only in the human phase prompt. | A future build-engineer or domain-engineer working from a fresh context would read the deviation as a law violation, or write Scala-3-only syntax in `modules/core` that breaks the `core2_13` build. Record the deviation, the 2.13-compatible source rule for core, and the extra sbt project IDs. | factory-engineer |
| F3 | minor | Empty directories (modules/*/src/{main,test}/scala, tests/**, datasets/, infra/terraform/, prompts/**, experiments/, load-tests/, reports/) | Git does not track empty directories. Adding `.gitkeep` files would put build-engineer in other owners' territory, and the guard's `owner_of` blocks that. | The "empty module source roots / required directories" deliverable will not survive the human's commit or a fresh clone. sbt still works without them. Decide whether each owner adds a `.gitkeep` or the gap is accepted. | Human / orchestrator routing to each territory owner |
| F4 | minor (risk) | /Users/brian/Developer/repos/scholarchain/build.sbt:240-245 | `contractTests` does not depend on `sparkAnalytics` (Scala 2.13), and it does not get hadoop's `Provided` Hadoop jars, because those are not passed on through `dependsOn`. | Cross-module contract tests cannot use Spark or Hadoop classes. `tests/integration`, `tests/end-to-end` and `tests/adversarial` have no sbt projects. test-engineer will need build-engineer to wire these later. | build-engineer (future work order) / test-engineer |

## What I verified as sound

- **Module boundaries** (build.sbt:134-232) match the CLAUDE.md table. There are no cycles and nothing depends on `services`.
- **`core`** has only uPickle, with no Hadoop, Spark, AWS or HTTP dependency.
- **Scopes:**
  - Hadoop client api/runtime and spark-sql are `Provided` (119-122).
  - MUnit is `Test` (57).
  - logback is `Runtime` (124).
  - sbt-assembly packages the Runtime classpath, so `Provided` dependencies are left out.
  - `assemblyPackageScala / assembleArtifact := false` keeps scala-library out of the Spark jar (176).
- **`markTestRun` ordering:** the `taskDyn` runs module tests, then contract tests, then the write.
  - `TestGate` throws on failures or errors and on a non-Passed overall result, which covers crashed forks.
  - A `Test/compile` failure stops the task before anything is written.
  - The zero-test guard prevents an empty marker.
  - Once tests exist, I expect this to behave correctly.
- **Aliases:** all four exist. `check` stops at the first failing step in its sequence.
- **Ledger:** `Ledger.validateAll` rejects missing, floating or range versions at build load. That also covers plugin keys, because plugins.sbt resolves first and the build load then fails. The image must be pinned by tag and sha256 digest.
- **`project/build.properties` as the ledger:** a sound choice. The launcher reads only `sbt.version`, and verify-tests-ran.sh already treats `project/*.properties` as source.
- **Dockerfile:**
  - There is no default base image.
  - The ledger pins the image by digest.
  - It runs as non-root (`USER 10001:10001`).
  - Heap is set as a percentage (`MaxRAMPercentage=75`).
  - The build context contains only app.jar.
- **Secrets:** the build contains no secrets, endpoints, account IDs or deployment steps.
- **Formatter hook:** `scalafmtOnly / aggregate := false` plus fileOverride dialects for `*.sbt`, `project/*.scala`, core and spark should work with format-scala.sh.
- **Ownership:** no writes outside build-engineer territory apart from the empty directories, which the work order authorized.

STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 2 read-only review of the build-engineer diff (build.sbt, project/{build.properties,plugins.sbt,Ledger.scala,TestGate.scala}, .scalafmt.conf, .gitignore, docker/services/Dockerfile) against docs/agent-runs/phase-02/prompt.md, CLAUDE.md, docs/agents.md.
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools)
TEST RESULTS: Not run (no Bash). Under the human decision, "sbt check fails because no tests exist" is not a finding.
VALIDATION RESULTS: I read the diff files and confirmed they match the diff. I read guard-dangerous.sh, format-scala.sh, verify-tests-ran.sh, build-engineer.md, and the relevant lines of dependency-updater.md and domain-engineer.md. `.claude/.last-test-run` does not exist.
ASSUMPTIONS:
- sbt-scalafmt `scalafmtOnly` resolves relative paths against the root base directory.
- MUnit honors `--exclude-tags` passed through `Tests.Argument`.
- sbt-assembly leaves out `Provided` dependencies and, with `assemblyPackageScala` off, the Scala instance jars.
These match how the tools are documented to work. I did not run them.
RISKS (unverified claims I could not check):
1. EMR emr-spark-8.1.0 component versions (Spark 4.1.1-amzn-0, Scala 2.13.17, hadoop-mapred 3.4.2-amzn-3, Java 17), and whether that release line runs Hadoop MapReduce jobs as the preamble requires. I could not fetch the AWS page.
2. The eclipse-temurin 17.0.21_9-jre-noble digest. `dockerLocal` was never run, so the Dockerfile is unproven. `COPY --chmod` also needs BuildKit.
3. `Test/compile` with empty source trees does not show that Scala 3.3.9 can read the upickle 4.4.3 or munit 1.3.6 TASTy files. Ask for the scratchpad probe evidence covering a uPickle import in both a Scala 3 project and `core2_13`.
4. The `dependencyOverrides` pin of scala-library 2.13.17 could cause linkage errors if a 2.13 dependency was built against a newer 2.13 patch. That is intended, because it matches the cluster, but it is untested.
5. The java17ModuleOpens list is claimed to be an exact copy of Spark 4.1.1 JavaModuleOptions. Unverified.
MANUAL ACTION REQUIRED:
- The human ratifies the ledger path (F1) and decides how to persist the empty directories (F3).
- Optionally, require `sbt dockerLocal` evidence before Phase 2 is ratified.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Orchestrator routes B1-B6 to build-engineer (optional before ratification, as all are minor) and F1-F2 to factory-engineer for constitutional amendment. Human ratifies Phase 2 with `sbt Test/compile` evidence.
