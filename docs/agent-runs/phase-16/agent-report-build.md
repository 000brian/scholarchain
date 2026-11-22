# Phase 16 step 1: build-engineer report

STATUS: COMPLETE
OWNER: build-engineer
WORK ORDER: docs/agent-runs/phase-16/prompt.md (sha256 c75cc5f895faed13880a69ec285231c5498fd50924c7b875fe7e02cce1707dc8), session 2 step 1, decision 5 (L-09, EmulatorTest, emulator Dockerfile, B-02), HEAD 9da45ab616a764e449f74f25feca85ed77d6dfe4. Not the wave closer, but the full sbt check is required for a build change.
FILES CREATED:
- /Users/brian/Developer/repos/scholarchain/project/BuildSelfTest.scala
- /Users/brian/Developer/repos/scholarchain/docker/emulator/Dockerfile
FILES MODIFIED:
- /Users/brian/Developer/repos/scholarchain/build.sbt
- /Users/brian/Developer/repos/scholarchain/project/PackageCheck.scala
COMMANDS RUN:
- sbt -batch "storage/EmulatorTest/compile" (19:24, success); sbt -batch "storage/EmulatorTest/compile" "services/EmulatorTest/compile" (19:26, both success)
- sbt check (19:27 to 19:45, exit 0, log: scratchpad/sbt-check-build.log)
- sbt -batch packageCheck (19:45, exit 0, "packageCheck: ok")
- sbt -batch "storage/EmulatorTest/compile" and sbt -batch "services/EmulatorTest/compile", separately, after the check (19:46, both success, no warnings)
- the format hook ran sbt scalafmtOnly after each edit (several intermediate edits failed to compile; all fixed before the check)
- read-only: shasum, git rev-parse, git status --short, grep, ls, head, tail -n, wc, ps
TEST RESULTS:
- sbt check: Ledger check passed; buildSelfTest: ok; buildPolicyCheck: ok; Module tests: suites=140 executed=2195 passed=2195 failed=0 errors=0 skipped=3; Contract tests (no AWS): suites=34 executed=399 passed=399 failed=0 errors=0 skipped=0; marker updated (timestamp=2026-11-22T01:45:28.125935Z, module_tests=2195, contract_tests=399).
- The counts match Phase 15's fresh-clone run (2,195 + 399), so no RequiresAws test started running in Test. `modules/storage/target/test-reports/TEST-org.scholarchain.storage.AwsAdaptersAwsSuite.xml` now shows tests="1" failures="0" skipped="1". Its last AwsTest run had tests="6". This shows MUnit splits the comma-separated `--exclude-tags=RequiresAws,RequiresEmulator`, so RequiresEmulator is excluded the same way.
- packageCheck: ok (services jar now must contain RetrievalEvalJob, and it does).
VALIDATION RESULTS: see VALIDATION EVIDENCE.
ASSUMPTIONS:
- MUnit 1.3.6 reads a comma-separated tag list. I could not read its source. The evidence is the unchanged test counts and the RequiresAws suite report above.
- I could not read the AWS Emulator Docker documentation: I have no aws-docs tool and no network access. The guard also blocked a grep of infra-engineer's env.sh for the trust-store and CA file names, and I did not retry. Every line I could not confirm is marked `unverified` in the Dockerfile.
RISKS:
- Dockerfile lines marked `unverified`: the download URL and archive format, the user name/UID/HOME, the binary name and location, and the entrypoint binary name. code-reviewer (R-2) has no aws-docs tool either, so the reviewer cannot check them alone. infra-engineer (which has aws-docs) or the human must compare them with getting-started-docker.html before E14. A wrong URL fails loudly at `curl --fail` during `docker build`.
- The base image uses the documented tag (`amazonlinux:2023-minimal`), not a digest. The image ID is recorded at E14. Pinning it by digest is proposed for later.
- buildSelfTest reads scripts/*.sh, scripts/lib/*.sh and infra/terraform/modules/workflow/sfn.tf. If a script names a new org.scholarchain class that is not in a packageCheck list, `sbt check` now fails, the same contract `check.sh` gate 8 enforces. That is intended, but it ties infra-engineer's edits to the sbt gate.
- Tests that fail without the change: I argued these from the code but did not observe them failing. Phase 16 allows only one full sbt check for this step, so I did not run a revert. Without L-09, buildSelfTest reports "script contracts: class org.scholarchain.rag.eval.RetrievalEvalJob is named by scripts/run-bounded-pipeline.sh but is in no packageCheck class list". Without the RequiresEmulator exclusion, buildPolicyCheck reports "N of 9 tested projects: Test/testOptions lack --exclude-tags=RequiresAws,RequiresEmulator" (AwsTest likewise).
MANUAL ACTION REQUIRED:
- Before E14: compare docker/emulator/Dockerfile's `unverified` lines with the documentation page (human or infra-engineer).
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: R-2 (Dockerfile) and R-1a (build.sbt, project/**); orchestrator fixed-interface comparison (RequiresEmulator, EmulatorTest, emulatorTest).

## Item status

- 5.1 L-09 (tier 1): done. fixed in code, tested offline, not validated live.
- 5.2 EmulatorTest (tier 1, fixed interface): done.
- 5.3 Emulator Dockerfile (tier 1): done, with `unverified` lines (see RISKS).
- 5.4 B-02 (tier 2): done.
- 5.5 sbt check and packageCheck: done (both exit 0).
- 5.6 INTERFACE HANDOFF: below.

## L-09 detail

- check.sh gate 8 (`gate_script_contracts`, scripts/check.sh:648-729):
  - (b) scripts/check.sh:676-686 greps every `org.scholarchain…Class` in scripts/*.sh, scripts/lib/*.sh (contract_script_files, :564-571) and SFN_TF (infra/terraform/modules/workflow/sfn.tf, :17). It requires each one to appear quoted in project/PackageCheck.scala. RetrievalEvalJob is named at scripts/run-bounded-pipeline.sh:1831 and was missing. It is now listed.
  - (c) The class-to-source mapping is `constant_options` (scripts/check.sh:601-623). It maps only ErMain, JsonlExport, RagIngestJob and StorageLoadMain to source files. The marker loop at :706 uses the same four keys.
- RetrievalEvalJob's lines at run-bounded-pipeline.sh:1830 and :1832 are written `# contract: RetrievalEvalJob.Required (…)`. That form does not match the marker regex at check.sh:689, so gate 8 checks none of RetrievalEvalJob's options. Gate 8 passes without a check.sh change. The option check is tier 2 at most (see the proposed-later list).

## INTERFACE HANDOFF

- Tag: `RequiresEmulator` (MUnit tag name; build.sbt `requiresEmulatorTag`). Test code declares it with `new munit.Tag("RequiresEmulator")`, like RequiresAws.
- Configuration: `EmulatorTest` (`config("emulatortest").extend(Test)`). It runs only tests tagged RequiresEmulator from the same test sources. MUnit args: `--include-tags=RequiresEmulator`, `--exclude-tags=RequiresAws`. It is on `storage` and `services` only.
- Test: `--exclude-tags=RequiresAws,RequiresEmulator` in every project (commonSettings). AwsTest: `--include-tags=RequiresAws`, `--exclude-tags=RequiresEmulator`. Neither runs RequiresEmulator tests. EmulatorTest is not in markTestRun and never writes .claude/.last-test-run.
- Alias: `sbt emulatorTest` = `all storage/EmulatorTest/test services/EmulatorTest/test` (both run; fails if either fails). Per project: `sbt storage/EmulatorTest/test`, `sbt services/EmulatorTest/test`, and `EmulatorTest/testOnly`.
- Fork: `EmulatorTest / testGrouping` defines the fork. It is one group named `emulator`, `Tests.SubProcess`, with these settings:
  - working directory: the project folder (modules/storage, modules/services);
  - Java home and environment: Test's (the forked JVM inherits the shell's environment; the build adds no variable);
  - JVM options exactly `-Xmx2g`, `-Djavax.net.ssl.trustStore=<user.home>/.aws/emulator/9297/truststore.p12`, `-Djavax.net.ssl.trustStoreType=PKCS12`.
  - user.home is the sbt JVM's system property. build.sbt reads no environment variable and uses no JAVA_TOOL_OPTIONS.
  - Consequence for storage-engineer and service-engineer: SCHOLARCHAIN_CONFIG_FILE and SCHOLARCHAIN_EMULATOR_RESULTS_DIR must be absolute (decision 6.6), because the working directory is the module folder.
- Policy test: `buildPolicyCheck` runs in markTestRun stage 2 (BuildSelfTest.tagProblems and emulatorProblems). It asserts the tag arguments of Test, AwsTest and EmulatorTest. It also asserts that no Test JVM sets a trust store, and checks EmulatorTest's forked group, JVM options, working directory and empty envVars.
- Build self-test: `buildSelfTest` runs in markTestRun stage 1. It checks:
  - every class named by scripts or the state machine is in a packageCheck list (L-09);
  - every listed class has its source in a module that reaches its jar;
  - seven packageCheck cases on generated jars (B-02): a clean baseline, a missing class, a forbidden prefix in the spark jar (scala/) and in the services jar (io/netty/), Main-Class in the hadoop jar, stale build info, and missing build info. Each case compares the exact problem lines.
- Class lists after the change (project/PackageCheck.scala):
  - Hadoop: org.scholarchain.hadoop.pipeline.ScholarChainHadoop
  - ER: org.scholarchain.er.run.ErMain
  - Spark: org.scholarchain.spark.GraphAnalyticsJob, org.scholarchain.spark.JsonlExport
  - Services: org.scholarchain.services.Main (Main-Class), org.scholarchain.storage.load.StorageLoadMain, org.scholarchain.rag.ingest.RagIngestJob, org.scholarchain.rag.eval.RetrievalEvalJob (new)
  - New: `PackageCheck.ClassListsByJar` (label, classes, module folders).
- Dockerfile: /Users/brian/Developer/repos/scholarchain/docker/emulator/Dockerfile.
  - Build command: `docker build -t scholarchain-emulator docker/emulator` (no build-arg needed; overridable ARGs: EMULATOR_PACKAGE_URL, EMULATOR_USER, EMULATOR_UID).
  - Base `public.ecr.aws/amazonlinux/amazonlinux:2023-minimal`, `--platform=linux/arm64`; the build fails unless `uname -m` is aarch64.
  - Non-root user; EXPOSE 9297; ENTRYPOINT `["aws-emulator", "-b", "0.0.0.0"]` (binary name unverified), with `-p 9297 --telemetry off` given on docker run.
  - The header covers LTS/latest (record the version at E14), never pushed, not part of the services build, and the BuildIdentity answer below.
- BuildIdentity: `docker` is in BuildIdentity.InputPaths (project/BuildIdentity.scala:24-34) and in SC_BUILD_INPUT_PATHS (scripts/lib/identity.sh:36). So:
  - while the Dockerfile is uncommitted, every jar records buildDirty=true;
  - the commit that adds it becomes buildCommit.
  - This is harmless now: there is no release this phase, and the same commit changes build.sbt and project/** anyway.
- check.sh change for infra-engineer: none required for gate 8 to pass. Optional, proposed later: give RetrievalEvalJob a `constant_options` case and real `# contract: RetrievalEvalJob` markers so its options are checked.
- Thread-safety: not applicable (build code). Limits: jar cases write only under an sbt temporary folder.

## Runtime lines

- `sbt emulatorTest`, `storage/EmulatorTest/test`, `services/EmulatorTest/test`: runtime: untested (compile only; first run E14).
- docker/emulator/Dockerfile: runtime: untested (never built by an agent; first build E14).
- buildSelfTest, buildPolicyCheck: runtime: tested in phase 16 (step 1 sbt check, exit 0).

## Agent-specific sections

- Version ledger: unchanged (project/build.properties untouched; munit 1.3.6, scalafmt 3.11.5, java 17, image.java-runtime unchanged). The emulator base image is a documented tag outside the ledger.
- Module dependency graph: unchanged.
- Environment variables read by the build: unchanged. No new variable. The new input is the sbt JVM's user.home system property (trust-store path).
- Packaging choices: services jar gains a required class (RetrievalEvalJob, already inside it through rag). The emulator image is separate and local-only.

## Proposed later

- Pin the emulator base image by digest after E14 records the image (reproducibility).
- check.sh option contract for RetrievalEvalJob (infra-engineer; gate 8 does not check its options today).

## VALIDATION EVIDENCE

- sbt check: exit=0, 19:45:28, "[success] Total time: 1083 s"; buildSelfTest: ok; buildPolicyCheck: ok; module 2195/2195 passed, contract 399/399 passed; marker timestamp=2026-11-22T01:45:28.125935Z.
- sbt -batch packageCheck: exit=0, "packageCheck: ok", 19:45:56.
- sbt -batch "storage/EmulatorTest/compile": success 19:46:07; sbt -batch "services/EmulatorTest/compile": success 19:46:11.
- files changed after these runs: none (build.sbt, project/PackageCheck.scala, project/BuildSelfTest.scala, docker/emulator/Dockerfile were last written before the sbt check started at 19:27)
