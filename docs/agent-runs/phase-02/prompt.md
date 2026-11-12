# Phase 2 work order

## Human architect decisions (preamble)

1. Instructor-approved Scala deviation. The course instructor confirmed (Q&A, Nov 2026) that the project may remain Scala 3 while Spark jobs are compiled with the Scala 2 version that the target Spark runtime is built for, and that `core` may be cross-built for both. This overrides "Scala 3 everywhere" in the work order below for the `sparkAnalytics` project only:
- `sparkAnalytics` uses the Scala 2 version that matches the selected Spark runtime
  (2.13 preferred; 2.12 only if the selected runtime requires it).
- `core` is cross-built for Scala 3 and that Scala 2 version, so Spark jobs can use
  the shared contracts. Keep `core` source compatible with both.
- All other projects stay Scala 3.
Record this as an instructor-approved deviation in your report.

2. Target runtime. Before pinning Hadoop and Spark versions, evaluate the current Amazon EMR 7.x release and emr-spark-8.1.0 using official AWS release documentation: Spark version, the Scala version Spark is built with, Hadoop version, and default Java. Recommend one, pin Hadoop and Spark Provided dependencies to exactly match it, and cite the sources in your report. Hadoop MapReduce jobs must run on the same target. Do not invent versions; if you cannot verify a version, report BLOCKED-ON with what is missing.

3. Java 17 for all projects and container images.

4. JSON library: uPickle, for every Scala version in the build.
Test framework: MUnit, used consistently across all projects.

5. Dependency ledger. Put every version in one ledger file and state its exact path in your report, so factory-engineer can assign dependency-updater ownership of it.

6. Do not commit, push, or install software. Do not create application logic. If a needed change falls outside your owned artifacts (for example CLAUDE.md), report it with the correct owner instead of making it.

## Work order (verbatim from course specification, Phase 2)

Use the build-engineer agent to create the ScholarChain sbt multi-project build and repository skeleton from scratch.

Required projects:
- core
- hadoop
- entityResolution
- sparkAnalytics
- storage
- rag
- services

Required principles:
- Scala 3 everywhere.
- Use Hadoop and Spark Java or DataFrame APIs where that avoids unsupported Scala binary assumptions.
- Pin every dependency and plugin version in a single version ledger.
- No floating or latest versions.
- Select one JSON library for the entire Scala codebase. Prefer uPickle unless a documented compatibility problem requires another choice.
- Select ScalaTest or MUnit and use it consistently.
- Keep Hadoop and Spark dependencies Provided when the runtime supplies them.
- Keep test-only libraries in Test scope.
- Keep logging implementation in Runtime scope.
- Use scalafmt with the Scala 3 dialect.
- Create a markTestRun task that updates .claude/.last-test-run only after tests pass.
- Define aliases: fmt, check, packageJobs, and dockerLocal.
- check must run format checking, compilation, tests, contract tests that do not need AWS, and markTestRun.
- Container images must use a pinned Java runtime, a non-root user, and percentage-based memory limits.
- Build behavior may depend only on explicitly documented environment variables.
- No secrets, cloud endpoints, or deployment procedures in the build definition.

Create:
- build.sbt
- project/build.properties
- project/plugins.sbt
- .scalafmt.conf
- .gitignore
- local compose file for emulators or test dependencies only when justified
- empty module source roots
- empty required top-level directories from the approved ownership map

Verify on a clean repository state with sbt Test/compile and then sbt check once the marker test exists.
Report the complete version ledger, module dependency graph, environment input surface, packaging choices, and any compatibility risk involving Scala 3, Hadoop, or Spark.
