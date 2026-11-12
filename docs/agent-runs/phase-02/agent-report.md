STATUS: BLOCKED-ON
OWNER: build-engineer
WORK ORDER: `docs/agent-runs/phase-02/prompt.md`: create the sbt multi-project build and repository skeleton, with the human architect preamble applied

Summary: the build is in place and `sbt Test/compile` passes on a clean state. `sbt check` fails at its last step, on purpose: there are no tests in the repository yet, so `markTestRun` refuses to write the marker. It will pass once the owning agents add one real test. In a scratchpad copy of the build, the full `check` passed and every failure path was confirmed.

FILES CREATED:
- /Users/brian/Developer/repos/scholarchain/build.sbt
- /Users/brian/Developer/repos/scholarchain/project/build.properties (the single version ledger)
- /Users/brian/Developer/repos/scholarchain/project/plugins.sbt
- /Users/brian/Developer/repos/scholarchain/project/Ledger.scala (reads the ledger; rejects missing keys and floating versions when the build loads)
- /Users/brian/Developer/repos/scholarchain/project/TestGate.scala (pass/fail logic used by `markTestRun`)
- /Users/brian/Developer/repos/scholarchain/.scalafmt.conf
- /Users/brian/Developer/repos/scholarchain/.gitignore
- /Users/brian/Developer/repos/scholarchain/docker/services/Dockerfile
- Empty directories:
  - `modules/{core,hadoop,entity-resolution,spark,storage,rag,services}/src/{main,test}/scala`
  - `tests/contracts/src/test/scala`, `tests/{integration,end-to-end,adversarial}`
  - `datasets/`, `infra/terraform/`, `scripts/`, `prompts/{runtime,schemas}/`, `experiments/`, `load-tests/`, `reports/`
- No compose file: no emulator or test dependency needs one yet.

FILES MODIFIED: none

COMMANDS RUN (results as observed):
- Read-only HTTP fetches of AWS EMR docs, Maven Central metadata and POMs, and the Docker Hub tag API, all into the scratchpad.
- `sbt projects`: exactly `core`, `core2_13`, `hadoop`, `entityResolution`, `sparkAnalytics`, `storage`, `rag`, `services`, `contractTests`, `root`.
- `sbt Test/compile`: success. It resolved every pinned library, and passed again after `sbt clean` (exit 0).
- Classpath exports confirmed the scopes:
  - Spark and Hadoop are on Compile/Test classpaths but absent from Runtime.
  - logback is only on the services Runtime classpath.
  - MUnit is only on Test classpaths.
  - Scala 2 projects use exactly scala-library 2.13.17. I added an override because `munit_2.13` 1.3.6 had pulled 2.13.18 onto the Spark test classpath.
- `sbt fmt`: exit 0. `sbt check`: exit 1. The ledger check, both format checks and `Test/compile` all passed, then: `[error] No tests executed; .claude/.last-test-run was not updated. A passing test must exist.` The marker is absent.
- Scratchpad copy of the build (probe tests written only there):
  - `sbt check` exit 0, marker written. `core` tests ran under both Scala 3 and 2.13.
  - A failing test tagged `RequiresAws` was excluded.
  - A local `SparkSession` test on Java 17 ran and asserted `SPARK_VERSION == "4.1.1"` and Scala `2.13.17`.
  - Contract tests ran after module tests.
- Probe fail paths, each leaving the marker unchanged:
  - A failing contract test: exit 1.
  - A failing module test: exit 1, and contract tests never started.
  - `sbt markTestRun` run on its own: exit 1.
- Probe `packageJobs` plus `services/assembly`: exit 0.
  - Spark job jar: no Spark, Hadoop or Scala stdlib classes.
  - Hadoop, entity-resolution and services jars: no Spark or Hadoop classes; they include the Scala 3 runtime.
  - logback is only in the services jar; no jar contains MUnit.
- Probe `sbt "scalafmtOnly modules/core/src/main/scala/Probe.scala"` (the formatter hook's exact command form): exit 0.

TEST RESULTS: the repository has no tests yet. Probe results are listed above.

VALIDATION RESULTS:
- `sbt Test/compile` passed on a clean state.
- `sbt check` will not pass until one passing test exists. This is by design: the marker is never written when zero tests ran.
- `dockerLocal` was not run, because building the image would pull the base image (the work order says no installs). It is unverified.

ASSUMPTIONS:
- A marker should only mean "tests passed" if at least one test actually ran. If there are zero contract tests, `check` only warns.
- The contract-test project may depend on `services`, because it is a test tree rather than a module.
- `entityResolution` stays on Scala 3, so it cannot use Spark.

RISKS:
- **Hadoop on the Spark runtime.** emr-spark-8.1.0 does not list Hadoop among its applications. It does install `hadoop-mapred 3.4.2-amzn-3`, YARN components and the `mapred-site` setting. MapReduce submission on this runtime needs an EMR sample run.
- **Vendor builds.** The pinned versions are the upstream Apache releases (Spark 4.1.1, Hadoop 3.4.2), not EMR's `-amzn` builds. EMR's artifact repository is a cloud endpoint, which the build must not contain.
- **Scala 3 versus Spark.** Spark code must stay on Scala 2.13. `core` must compile under both Scala 3 and 2.13 (`-Xsource:3`), so domain-engineer cannot use Scala-3-only syntax there.
- **Netty test setting.** The Spark-mirrored test JVM options include `-Dio.netty.handler.ssl.defaultEndpointVerificationAlgorithm=NONE`. This applies only to forked local test JVMs.
- **Image digest.** The Docker Hub tag was updated today; the digest pin freezes the image fetched at that time.
- **Scalafmt version mirror.** sbt-scalafmt needs the formatter version inside `.scalafmt.conf`, which belongs to build-engineer. So `version.scalafmt` is mirrored there, and `ledgerCheck` fails `check` if the two differ. When dependency-updater bumps scalafmt, it needs a matching build-engineer edit.
- **`.sbt/matrix/` directory.** sbt-projectmatrix creates it at the repository root; I gitignored it, but it has no owner.
- **Guard false positive.** The guard blocked one of my scratchpad probe commands: it mentioned the marker path in the same command as `mv`, which was renaming a test file, not the marker. I reran the steps as separate commands.

MANUAL ACTION REQUIRED:
1. Ratify `project/build.properties` as the dependency ledger.
2. Run `sbt dockerLocal` yourself if you want the image built (it pulls the pinned base image).
3. Empty directories are not tracked by git until their owners add files.

BLOCKED-ON OWNER: `domain-engineer` (primary). Also `test-engineer` and `factory-engineer`, as described below.

BLOCKED-ON EVIDENCE:
- **Marker test.** `sbt check` fails with `(markTestRun) No tests executed`. One passing MUnit test is needed in a module test tree; I suggest `modules/core/src/test/scala` (domain-engineer). I may not write there: the guard's `owner_of` maps `modules/core/*` to domain-engineer (`.claude/hooks/guard-dangerous.sh` lines 90 and 341–348).
- **Contract tests.** Contract tests go in `tests/contracts/src/test/scala/` (test-engineer). Tests that need AWS must carry `munit.Tag("RequiresAws")`.
- **Constitutional changes for factory-engineer:**
  - Set `DEPENDENCY_LEDGER_PATHS="project/build.properties"` (guard line 75) and record that path in `docs/agents.md`.
  - Record the Scala deviation in `CLAUDE.md` (it currently says "Scala 3 everywhere").
  - Assign an owner for `.sbt/**`.
  - Document `scalafmtOnly / aggregate := false`, the build setting that makes the formatter hook work in this multi-project build.

NEXT GATE: domain-engineer adds a passing test, then build-engineer reruns `sbt check` and confirms the marker is written, then code-reviewer.

**Version ledger** (`/Users/brian/Developer/repos/scholarchain/project/build.properties`). Every entry was resolved, downloaded or verified as listed:

| Key | Value |
|---|---|
| `sbt.version` | 1.13.1 |
| `version.java` | 17 |
| `version.scala3` | 3.3.9 |
| `version.scala213` | 2.13.17 |
| `version.spark` | 4.1.1 |
| `version.hadoop` | 3.4.2 |
| `version.upickle` | 4.4.3 |
| `version.munit` | 1.3.6 |
| `version.slf4j` | 2.0.19 |
| `version.logback` | 1.6.6 |
| `version.scalafmt` | 3.11.5 |
| `version.sbt-scalafmt` | 2.6.2 |
| `version.sbt-assembly` | 2.5.0 |
| `version.sbt-projectmatrix` | 0.11.0 |
| `image.java-runtime` | `eclipse-temurin:17.0.21_9-jre-noble@sha256:042c05a77eb915684fc8c92cfbbef4fd6f8c24ca7d934d1a7db3543453aaa077` (tag and digest from the Docker Hub API; not pulled) |

**Module dependency graph** (no cycles; nothing depends on `services`; only `core` is cross-built):
- `core` (Scala 3) and `core2_13` (Scala 2.13): same source tree, depends on uPickle only.
- `hadoop` → `core`; Hadoop client API and runtime are Provided.
- `entityResolution` → `core`.
- `sparkAnalytics` (Scala 2.13) → `core2_13`; Spark SQL is Provided.
- `storage` → `core`.
- `rag` → `core`, `storage`.
- `services` → `core`, `rag`, `storage`; uses slf4j, with logback at Runtime scope.
- `contractTests` → `core`, `hadoop`, `entityResolution`, `storage`, `rag`, `services`.
- `root` aggregates everything.

**Environment input surface:** the build reads no environment variables. Implicit inputs are:
- sbt run from the repository root on Java 17 (the build refuses to load on any other Java release).
- Network access to Maven Central.
- `docker` on PATH, for `dockerLocal` only.

**Packaging choices:**
- `packageJobs` builds fat jars for `hadoop`, `entityResolution` and `sparkAnalytics` with sbt-assembly. Provided dependencies are excluded, and the Spark jar also leaves out the Scala library, which the runtime supplies.
- `dockerLocal` assembles `services` and builds `docker/services/Dockerfile` as `scholarchain-services:local`. The Dockerfile has no default base image; sbt passes the pinned image from the ledger.
- The image runs as non-root user `10001:10001`, with memory set as percentages through `JAVA_TOOL_OPTIONS` (`InitialRAMPercentage=50`, `MaxRAMPercentage=75`, `ExitOnOutOfMemoryError`).

**EMR runtime recommendation: emr-spark-8.1.0.**
- Source: https://docs.aws.amazon.com/emr/latest/ReleaseGuide/emr-spark810-release.md (and `.html`)
  - "| Scala | 2.13.17 |" and "| Spark | 4.1.1-amzn-0 |"
  - "| hadoop-mapred | 3.4.2-amzn-3 | MapReduce execution engine libraries…"
  - The HTML default-Java table shows `Spark` → `&lt;b&gt;17&lt;/b&gt;, 21` and `Hadoop` → `&lt;b&gt;17&lt;/b&gt;, 11, 8`.
  - It is a Long Term Support release, supported until September 7, 2029.
- The alternative, emr-7.14.0, is the latest 7.x release (https://docs.aws.amazon.com/emr/latest/ReleaseGuide/emr-7140-release.md):
  - "| Scala | 2.12.18 |", "| Spark | 3.5.8-amzn-0 |", "| Hadoop | 3.4.2-amzn-2 |"
  - "ships with Amazon Corretto 17 … by default"
- I rejected 7.14.0 because the preamble prefers Scala 2.13.
- Cross-check: the Spark 4.1.1 parent POM declares `hadoop.version` 3.4.2, `scala.version` 2.13.17 and `java.version` 17, matching the pinned values.

**Deviation record (instructor-approved, Q&amp;A Nov 2026, preamble item 1):**
- `sparkAnalytics` compiles with Scala 2.13.17, matching emr-spark-8.1.0.
- `core` is cross-built for Scala 3.3.9 and 2.13.17, formatted with the `scala213source3` dialect.
- Every other project is Scala 3.3.9.
