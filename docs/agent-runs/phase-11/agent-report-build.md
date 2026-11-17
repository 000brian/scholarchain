STATUS: COMPLETE
OWNER: build-engineer
WORK ORDER: Phase 11 step 0, decision 3 (docs/agent-runs/phase-11/prompt.md, sha256 a0b59faeaeaae620ad87ce8b5dd1272efc1078d273e9a84baa426a9cd47e9ed1, not recomputed). contractTests now also gets the test classpath (fixtures) of storage, rag and services.
FILES CREATED: none in the repository (report only: /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/858dada8-c8dd-43c7-b05d-961e323b855c/scratchpad/phase11-reports/agent-report-build.md)
FILES MODIFIED: /Users/brian/Developer/repos/scholarchain/build.sbt (contractTests .dependsOn only)
COMMANDS RUN:
- sbt -batch "contractTests/Test/compile" (foreground) -> [success] Total time: 2 s, completed Nov 16, 2026, 6:20:38 PM
- git diff build.sbt; git status --short (read-only)
- ls -R on the four test-resource trees (read-only duplicate check)
- sbt check NOT run (phase exception, decision 3; the wave-1 closer's full check covers this change)
TEST RESULTS: n/a (compile only). contractTests/Test/compile green.
VALIDATION RESULTS:
- Compile observed green. It completed in 2 s because the upstream Test classes of storage, rag and services were already compiled and up to date (modules/rag/target/scala-*/test-classes exists); the new test->test edges resolved without error.
- Exact `git diff build.sbt`:
```
diff --git a/build.sbt b/build.sbt
index 69e1d99..7e25ab6 100644
--- a/build.sbt
+++ b/build.sbt
@@ -382,7 +382,14 @@ lazy val services = (project in file("modules/services"))
 // Tests that need live AWS carry the MUnit tag "RequiresAws" and are excluded by check.
 lazy val contractTests = (project in file("tests/contracts"))
   .configs(AwsTest)
-  .dependsOn(coreScala3, hadoop, entityResolution, storage, rag, services)
+  .dependsOn(
+    coreScala3,
+    hadoop,
+    entityResolution,
+    storage % "compile->compile;test->test",
+    rag % "compile->compile;test->test",
+    services % "compile->compile;test->test"
+  )
   .settings(
     name := "scholarchain-contract-tests",
     commonSettings,
```
- Duplicate test-resource check: NONE. Relative paths per tree:
  - tests/contracts: logback-test.xml, er-leakage/clean/*.scala.txt (1), er-leakage/planted/*.scala.txt (10), hadoop-adversarial/{bom-utf8.xml, job-corpus.xml, split-corpus.xml}
  - modules/rag: benchmark/benchmark-v1.json, config/{rag-bedrock-example-v1, rag-local-v1, rag-runtime-anthropic-v1, rag-runtime-anthropic-v2, rag-runtime-local-v1, rag-runtime-local-v2, rag-voyage-example-v1}.json
  - modules/storage: config/storage-local-v1.json
  - modules/services: api/v1/*.json (16 files), config/services-local-v1.json
  - The directory `config/` is shared by rag, storage and services, but every file name in it is distinct, so no resource path appears twice. logback-test.xml exists only in tests/contracts.
ASSUMPTIONS: Multi-line formatting of the dependsOn call is the formatter's/house style for a list this long; semantics are exactly the requested change. hadoop, entityResolution and coreScala3 stay compile-only.
RISKS:
- contractTests' test classpath now also carries storage/rag/services test dependencies and test classes; a future same-named resource or class added in those trees could shadow one in tests/contracts (none today).
- Upstream module test changes now trigger recompilation of contractTests tests (longer incremental builds; no correctness risk).
- No Scala 3 / 2.13 impact: all three upstream projects and contractTests are Scala 3; sparkAnalytics and core2_13 are untouched.
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Step 1b (test-engineer, wave-1 closer) runs the full `sbt check`, which covers this build.sbt change.

VERSION LEDGER: unchanged (project/build.properties not touched; no library added).
MODULE DEPENDENCY GRAPH: unchanged for production modules. contractTests -> coreScala3, hadoop, entityResolution (compile); storage, rag, services (compile->compile;test->test). contractTests is a test project, so the services edge stays within the CLAUDE.md rules; no cycle.
ENVIRONMENT VARIABLES READ BY THE BUILD: unchanged.
PACKAGING CHOICES: unchanged.

INTERFACE HANDOFF
- Files changed: build.sbt (contractTests .dependsOn).
- Public types and signatures: none.
- Test fixtures others can use: every class and resource under modules/storage/src/test/**, modules/rag/src/test/** and modules/services/src/test/** is now on the contractTests Test classpath (packages as declared in those trees; resources e.g. config/rag-runtime-local-v2.json, config/storage-local-v1.json, api/v1/*.json, benchmark/benchmark-v1.json).
- Thread-safety: n/a.
- Known limits: contractTests cannot reach sparkAnalytics (Scala 2.13); unchanged.

TEST EVIDENCE
- command: sbt -batch "contractTests/Test/compile"
- finished: 2026-11-16 18:20:38 CST
- per suite: n/a (compile only)
- result line: [success] Total time: 2 s, completed Nov 16, 2026, 6:20:38 PM
- expected-failure flips: none
- source changed after this run: no
