Summary: I made the one change from decision 4. In `build.sbt`, `services` now depends on `rag` and `storage` with `compile->compile;test->test`, so services tests can use the rag and storage test fixtures. One `sbt check` passed: exit 0, 1634 module tests and 336 contract tests, matching the counts expected before this phase. Nothing else changed.

STATUS: COMPLETE
OWNER: build-engineer
WORK ORDER: docs/agent-runs/phase-10/prompt.md (I checked the sha256 and it matches: c032785a6cbef13124c196fb3c42be52b43cfd6e10e712a1861553d1cde5f49a), step 1, decision 4
FILES CREATED: none in the repository. My scratchpad log is /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/aaf86ae1-6986-4770-9813-b764b8dca793/scratchpad/sbt-check.log.
FILES MODIFIED: /Users/brian/Developer/repos/scholarchain/build.sbt
COMMANDS RUN:
- shasum -a 256 docs/agent-runs/phase-10/prompt.md; grep on build.sbt; git diff build.sbt; ls/find on modules/services, modules/rag/src/test, modules/storage/src/test (read-only)
- sbt -batch check, run once in the background with output logged to the scratchpad file. It finished with exit 0.
- tail and grep on the log, used to wait for the result and read it (read-only)
TEST RESULTS:
- `[info] Module tests: suites=90 executed=1634 passed=1634 failed=0 errors=0 skipped=3`
- `[info] Contract tests (no AWS): suites=22 executed=336 passed=336 failed=0 errors=0 skipped=0`
- `[success] Total time: 919 s (0:15:19.0), completed Nov 15, 2026, 11:05:56 PM`, with EXIT=0
VALIDATION RESULTS: `markTestRun` refreshed .claude/.last-test-run at 23:05; build.sbt was last changed at 22:50. Format check, compilation, module tests and contract tests all passed. I did not rerun `sbt check`.
ASSUMPTIONS: `coreScala3` is the existing core Scala 3 project reference (`core.jvm(scala3)`), so the core dependency did not change. The PostToolUse scalafmt hook split the new `dependsOn` call over several lines. That is formatting only.
RISKS:
- Test classes and test resources from rag and storage now sit on the services test classpath. If services adds a test resource or test class under the same path or name as one in rag or storage, they will clash on the classpath. Using distinct packages and the `api/v1/` and `config/` resource prefixes avoids this.
- Test-scope dependencies of rag and storage (MUnit and their test resources) come along too. They are already pinned in the ledger, so nothing new is added.
- No production code depends on services, and there are no cycles (rag -> storage -> core, services -> rag, storage, core).
MANUAL ACTION REQUIRED: none. No commit was made.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: step 2, domain-engineer (decision 5a). code-reviewer covers this change in the upstream review (8a).

Exact `git diff build.sbt`:
```
diff --git a/build.sbt b/build.sbt
index edd42e8..69e1d99 100644
--- a/build.sbt
+++ b/build.sbt
@@ -338,7 +338,11 @@ lazy val dockerBuildLocal =
 
 lazy val services = (project in file("modules/services"))
   .configs(AwsTest)
-  .dependsOn(coreScala3, rag, storage)
+  .dependsOn(
+    coreScala3,
+    rag % "compile->compile;test->test",
+    storage % "compile->compile;test->test"
+  )
   .settings(
     name := "scholarchain-services",
     commonSettings,
```

Version ledger: unchanged. No version value or library was added.
Module dependency graph: unchanged except that services now also uses the test configs of rag and storage.
- core: Scala 3, with core2_13 built from the same sources for Scala 2.13
- hadoop, entityResolution and storage depend on core
- sparkAnalytics depends on core2_13
- rag depends on core and storage
- services depends on core, rag (compile and test) and storage (compile and test)
- contractTests depends on core, hadoop, entityResolution, storage, rag and services
Environment variables read by the build: unchanged.
Packaging choices: unchanged. As decision 4 says, the assembly main class waits for Phase 13.

INTERFACE HANDOFF
- Files changed: /Users/brian/Developer/repos/scholarchain/build.sbt (the `dependsOn` of the `services` project only).
- Public types and signatures: none created or changed. This is a build change only.
- Test fixtures services can now use: everything compiled from modules/rag/src/test/** and modules/storage/src/test/**, plus their test resources (33 Scala test files between the two trees). Their main classes and test-scope libraries are included as well. The fixture class names and packages are listed in the rag and storage handoffs, not here.
- Thread-safety: not applicable.
- Known limitations: only services gets the test-to-test wiring. contractTests still depends on the compile config of rag and storage only. If test resources in services and in rag or storage share a path, the classpath order decides which one loads. The `AwsTest` config of services is not wired to the test configs of rag or storage, so live (`RequiresAws`) tests in services cannot use rag or storage test fixtures through `AwsTest`.
