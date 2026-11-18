STATUS: COMPLETE
OWNER: build-engineer
WORK ORDER: docs/agent-runs/phase-13/prompt.md (sha256 60c943dccc86a6dc58ec074b95bbd63f04e2adca2f7a1c9a9abad266f28d36a7), session 2 step 1, decision 5 items 1-12; wave A closer.
FILES CREATED: project/BuildIdentity.scala, project/PromptResources.scala, project/PackageCheck.scala, project/DockerContext.scala
FILES MODIFIED: build.sbt, docker/services/Dockerfile, .gitignore
COMMANDS RUN: sbt -batch packageJobs; sbt -batch services/assembly; sbt -batch services/dockerStage; ls -la modules/services/target/docker-context; shasum -a 256 (context files vs sources); sbt -batch packageCheck; sbt check; git status --short --ignored; git check-ignore -v (read-only)
TEST RESULTS: sbt check exit 0: Module tests suites=119 executed=2005 passed=2005 failed=0 errors=0 skipped=3; Contract tests (no AWS) suites=34 executed=398 passed=398 failed=0 errors=0
VALIDATION RESULTS: packageCheck fails on exactly one line, "services jar: missing class org.scholarchain.storage.load.StorageLoadMain" (expected until step 3); every other check passes.
ASSUMPTIONS:
- Build info is generated only in the four assembled projects (hadoop, entityResolution, sparkAnalytics, services), so services' app.jar holds exactly one copy. storage and rag on their own (in module tests) have no such resource on the classpath; their run reports must treat a missing resource as "unknown".
- `module` key = sbt project id (hadoop, entityResolution, sparkAnalytics, services).
- D-07 is implemented (contractTests -> coreScala3 test->test). It is one line, the Phase 11 gate placed it "with the assembly work", and there are no package collisions (core tests use org.scholarchain.core, contract tests use org.scholarchain.contracts.*).
- The prompt schemas are not mapped: the code reads them only from the file system (Prompts.scala:187-189), not from the classpath.
RISKS:
- Runtime digest platforms are unverified (no docker, no network). Check `docker buildx imagetools inspect` in package.sh.
- Nothing has run the negative paths of packageCheck (a Main-Class in the hadoop jar, a forbidden prefix, a differing prompt copy). The positive run shows that every check found its target. The hadoop Main-Class filter is at build.sbt:322-323.
- Test classpath of contractTests holds three build-info files (hadoop, ER, services); the first one wins on lookup there. No assembly is affected.
- Spark jar check forbids `scala/`; if a future 2.13 dependency ships classes under `scala/` (for example scala-collection-compat), packageCheck will flag it.
MANUAL ACTION REQUIRED: none in this step (the human checks the runtime digest's linux/arm64 entry when running package.sh).
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: R-1 (code-reviewer, review-report-a.md); step 3 adds StorageLoadMain; the wave B closer (step 5) runs packageCheck, which must then pass.

## Requirement status (decision 5)
1. .gitignore evidence/: done (.gitignore:18)
2. config in image: done (docker/services/Dockerfile:21-27; root:root 0444 at /opt/scholarchain/config/course.json)
3. image build split / dockerStage / dockerLocal: done (build.sbt:412-436; project/DockerContext.scala)
4. ARM64 explicit: done (project/DockerContext.scala Platform; dockerBuildLocal passes --platform linux/arm64)
5. no HEALTHCHECK: deviation (planned) (Dockerfile:11-12 comment states why)
6. build identity: done (project/BuildIdentity.scala; build.sbt:213-216, 243-250)
7. A9/D-07 merge rules: done (build.sbt:219-241; D-07 build.sbt:448-450)
8. Hadoop jar without Main-Class: done (build.sbt:322-323; verified by packageCheck)
9. R3-06 prompt mapping: done (build.sbt:375-385; project/PromptResources.scala)
10. packageCheck: done (build.sbt:506-530; project/PackageCheck.scala); currently 1 expected failure line (StorageLoadMain, until step 3)
11. commands: done (results above)
12. .gitignore review: done. One fix: `*.log` ignored experiments/raw/*.log, so `!experiments/**/*.log` and `!releases/**/*.log` were added (.gitignore:13-15). Ignored as required: target/ (so modules/services/target/docker-context/), evidence/, infra/terraform/environments/course/plans/, .terraform/, *.tfvars (course.tfvars is re-included and stays tracked). Not ignored: experiments/raw/*.json and *.log, releases/**.

## Packaging choices
- Four fat jars from the `packageJobs` alias and services/assembly; Provided Hadoop/Spark not assembled; spark jar without the Scala library (assemblyPackageScala false).
- services `assembly / mainClass` set explicitly to org.scholarchain.services.Main (build.sbt:409).
- dockerStage deletes and re-stages target/docker-context with exactly app.jar, config/course.json and context.sha256; it runs no Docker.
- Dockerfile: pinned runtime by ARG only (no default), uid/gid 10001 non-root, percentage memory, root-owned 0444 files.

## Merge rules (assemblySettings, build.sbt:219-241)
- module-info.class (root and META-INF/versions/N/): discard. A fat jar is not a module.
- META-INF/services/**: filterDistinctLines (concatenated, distinct lines; never first-wins). The SdkHttpService registration lives here.
- **/execution.interceptors: filterDistinctLines, the SDK interceptor lists.
- everything else: sbt-assembly default (identical files dedup; license files renamed; signatures/manifests discarded; differing bytes fail).
- No Apache/Apache5/Netty: excluded per SDK dependency (build.sbt awsSdkHttpExclusions); packageCheck asserts no classes under org/apache/http/, org/apache/hc/, io/netty/, software/amazon/awssdk/http/{apache,apache5,nio/netty}/.
- Observed: services/assembly merged 6 files with FilterDistinctLines; packageCheck confirmed that every line of every input list file is in the merged file and that UrlConnectionSdkHttpService is registered.

## Environment variables read by the build
build.sbt/project read none themselves. The input-surface comment (build.sbt:6-47) now adds git (PATH resolves git; GIT_DIR, GIT_WORK_TREE, GIT_INDEX_FILE, GIT_CEILING_DIRECTORIES, GIT_CONFIG_GLOBAL, GIT_CONFIG_SYSTEM, GIT_CONFIG_NOSYSTEM change the identity). DOCKER_DEFAULT_PLATFORM is marked unused (the platform is explicit).

## docker buildx build argument list (package.sh)
docker buildx build --platform linux/arm64 --load --file docker/services/Dockerfile --build-arg JAVA_RUNTIME_IMAGE=<value of image.java-runtime in project/build.properties> --tag scholarchain-services:SHA40 modules/services/target/docker-context
(Same list in project/DockerContext.scala buildxArgs; dockerBuildLocal uses tag scholarchain-services:local.)
Runtime digest multi-platform incl. linux/arm64: unverified.

## Build identity freshness
- `ThisBuild / buildIdentity` is a plain task, evaluated once in every sbt invocation that builds resources. It is never cached across invocations, so a new commit or a change in the working tree reaches the next assembly.
- `BuildIdentity.generate` rewrites the file only when the content changed, so the same state causes no rewrite and no rebuild.
- packageCheck also compares each jar's resource with this invocation's identity and reports `stale` on a mismatch, so a stale jar cannot pass.
- No timestamp. `git --no-optional-locks status` never writes the index.

## INTERFACE HANDOFF
- Resource: classpath root `/scholarchain-build-info.properties` (jar entry `scholarchain-build-info.properties`). Keys in order: buildCommit (40 hex or `unknown`), buildDirty (`true`|`false`; `true` when git is unavailable), module (sbt project id), version. No timestamp. Present in the hadoop, ER, spark and services jars only. Path list: project/BuildIdentity.scala:24 (InputPaths), which must equal scripts/lib/identity.sh.
- packageCheck class lists: project/PackageCheck.scala:18-34 (HadoopClasses, ErClasses, SparkClasses, ServicesMainClass, ServicesClasses). Output: one `[error] packageCheck: <problem>` line per problem, then `packageCheck failed: N problem(s).`, exit 1; on success `[info] packageCheck: ok`. Root task, not aggregated; it depends on the four assemblies.
- dockerStage outputs: modules/services/target/docker-context/{app.jar, config/course.json, context.sha256}; context.sha256 lines are `<sha256>  app.jar` and `<sha256>  config/course.json` (shasum -a 256 -c format). The task returns the context directory.
- Jars (version 0.1.0-SNAPSHOT):
  modules/hadoop/target/scala-3.3.9/scholarchain-hadoop-assembly-0.1.0-SNAPSHOT.jar
  modules/entity-resolution/target/scala-3.3.9/scholarchain-entity-resolution-assembly-0.1.0-SNAPSHOT.jar
  modules/spark/target/scala-2.13/scholarchain-spark-analytics-assembly-0.1.0-SNAPSHOT.jar
  modules/services/target/scala-3.3.9/scholarchain-services-assembly-0.1.0-SNAPSHOT.jar
- Prompts: rag resources at org/scholarchain/rag/prompts/runtime/ are generated from prompts/runtime. The copies in modules/rag/src/main/resources at that path are excluded from the resources (build.sbt:381-384), so they no longer ship. rag-engineer (step 4) may delete them; the filter is then a no-op. packageCheck reports a copy that differs or has no source, and a jar entry that has no source.
- Thread-safety: n/a (build code). Limits: packageCheck reads whole jar entries into memory (largest jar about 32 MB).

## Proposed later
- A small sbt scripted or unit test of PackageCheck's negative paths (it would prove each detector fails on a bad jar).

VALIDATION EVIDENCE
- targeted: not applicable (build-only change; the full check below covers it)
- full: sbt check -> "Updated /Users/brian/Developer/repos/scholarchain/.claude/.last-test-run (module_tests=2005, contract_tests=398)"
- package (step 1, wave closers): sbt -batch packageCheck -> "packageCheck: services jar: missing class org.scholarchain.storage.load.StorageLoadMain" (only failing line; expected until step 3)
- finished: after the last command above
- files changed after these runs: no (.gitignore was edited during the sbt check run; it is not a build input or sbt source)
