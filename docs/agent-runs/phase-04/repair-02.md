STATUS: COMPLETE
OWNER: build-engineer
WORK ORDER: Phase 4 supporting work order, routed from a test-engineer BLOCKED-ON (docs/agent-runs/phase-04/prompt.md). The `contractTests` project now has the Hadoop client API and runtime in Test scope only, plus the forked Java 17 test settings the hadoop module uses, so LocalJobRunner job-level black-box tests can run from tests/contracts.

FILES CREATED: none
FILES MODIFIED:
- /Users/brian/Developer/repos/scholarchain/build.sbt

Exact diff:
```diff
@@ -190,6 +190,10 @@ lazy val upickle = "com.lihaoyi" %% "upickle" % Ledger.upickle
 lazy val hadoopClientApi = "org.apache.hadoop" % "hadoop-client-api" % Ledger.hadoop % Provided
 lazy val hadoopClientRuntime =
   "org.apache.hadoop" % "hadoop-client-runtime" % Ledger.hadoop % Provided
+// The same Hadoop client artifacts in Test scope only, for test projects that run MapReduce jobs
+// with the local job runner (Provided does not pass to dependents). Never for production modules.
+lazy val hadoopClientTestOnly: Seq[ModuleID] =
+  Seq(hadoopClientApi, hadoopClientRuntime).map(_.withConfigurations(Some(Test.name)))
 lazy val sparkSql = "org.apache.spark" %% "spark-sql" % Ledger.spark % Provided
@@ -320,7 +324,10 @@ lazy val contractTests = (project in file("tests/contracts"))
   .dependsOn(coreScala3, hadoop, entityResolution, storage, rag, services)
   .settings(
     name := "scholarchain-contract-tests",
-    commonSettings
+    commonSettings,
+    // Job-level black-box tests of the hadoop module's MapReduce jobs (local job runner).
+    forkedTests(hadoopJava17Options),
+    libraryDependencies ++= hadoopClientTestOnly
   )
```

COMMANDS RUN:
- `sbt -batch 'show contractTests/Test/fork' 'show contractTests/Test/javaOptions' 'export contractTests/Test/fullClasspath' 'export hadoop/Runtime/fullClasspath' 'export services/Runtime/fullClasspath' 'show hadoop/Test/fork'`
- `sbt -batch -error "export <cp>"` for each of these, counting hadoop-client jars: contractTests/Test, contractTests/Compile, hadoop/Runtime, services/Runtime, rag/Test
- A temporary probe suite was run with `sbt -batch 'contractTests/testOnly org.scholarchain.contracts.BuildEngineerHadoopProbeSuite'` and then deleted (details below)
- `sbt -batch check` (output saved to /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/183f6251-40b4-4bce-9aa1-4ee577720ebd/scratchpad/check.log)

TEST RESULTS (`sbt check`, exit code 0, observed):
- `[info] Ledger check passed (scalafmt 3.11.5).`
- `[info] Module tests: suites=24 executed=785 passed=785 failed=0 errors=0 skipped=0`
- `[info] Contract tests (no AWS): suites=4 executed=63 passed=63 failed=0 errors=0 skipped=0`
- `[info] Updated /Users/brian/Developer/repos/scholarchain/.claude/.last-test-run (module_tests=785, contract_tests=63)`
- `[success] Total time: 504 s (0:08:24.0)`

VALIDATION RESULTS:
- `show contractTests/Test/fork` gives `true`. `contractTests/Test/javaOptions` lists exactly the 9 `hadoopJava17Options` (`--add-opens ...`, including `java.security.jgss/sun.security.krb5`).
- `export contractTests/Test/fullClasspath` lists hadoop-client-api-3.4.2.jar and hadoop-client-runtime-3.4.2.jar from the Coursier cache (`Ledger.hadoop`, unchanged).
- Number of hadoop-client jars on each classpath: contractTests/Test=2, contractTests/Compile=0, hadoop/Runtime=0 (still Provided), services/Runtime=0, rag/Test=0. No production module gained Hadoop.
- `hadoop/Test/fork` is still `true`, so the hadoop module is unchanged.
- Probe: tests/** is the only tree `contractTests` compiles, so the probe had to live there briefly. I created tests/contracts/src/test/scala/org/scholarchain/contracts/BuildEngineerHadoopProbeSuite.scala, ran it, and deleted it with `rm -f` in the same command. I then confirmed the directory holds only `hadoop/`. The probe built a `org.apache.hadoop.conf.Configuration`, ran `FileSystem.getLocal` (which resolved to LocalFileSystem from hadoop-client-api-3.4.2.jar), and ran `mapreduce.Job.getInstance`. Result: `Passed: Total 1, Failed 0`. No files were left in tests/**.

ASSUMPTIONS:
- Test scope is enough for test-engineer. The Hadoop jars are only on contractTests' Test classpath, not its Compile classpath, and contractTests has no main sources.
- Contract tests now run in a forked JVM with working directory tests/contracts instead of the repository root. The existing `AdversarialSupport.repoRoot` walks up to `datasets/dataset-manifest.yaml`, so it still works, and all 63 tests passed. New tests should find the repository root the same way, not rely on the working directory.

RISKS:
- Log noise: `services` brings logback-classic (Runtime) onto the contractTests Test classpath, and there is no logback config in that test tree. During the probe, Hadoop classes logged at DEBUG to stdout. The full `sbt check` log had no DEBUG lines today, but LocalJobRunner job tests will probably be verbose. If that matters, test-engineer can add a `logback-test.xml` under tests/contracts/src/test/resources. That file is test-engineer's, so I did not create it.
- Forking adds JVM startup cost to contract tests. Fork setup was not measured separately; the whole run was 504 s, and the hadoop module's PipelineSuite alone took about 130 s.
- Scala 3 with Hadoop compatibility is unchanged: these are the same Java-only shaded client jars the hadoop module already uses.

MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Rerun test-engineer in a fresh context to write the LocalJobRunner job-level tests under tests/contracts, then code-reviewer reviews the build.sbt diff, then the human ratifies the phase.

Version ledger: unchanged. No versions or libraries added. Hadoop comes from `Ledger.hadoop` (3.4.2).

Module dependency graph: unchanged. `contractTests` dependsOn(coreScala3, hadoop, entityResolution, storage, rag, services), and now also has hadoop-client-api and hadoop-client-runtime in Test scope only. `hadoop` keeps Hadoop as Provided, so its assembly is unaffected.

Environment variables read by the build: unchanged. build.sbt reads none. Forked contract-test JVMs now also pick up JAVA_TOOL_OPTIONS, JDK_JAVA_OPTIONS and _JAVA_OPTIONS, which the build.sbt header already lists for forked test JVMs.

Packaging choices: unchanged. contractTests is not assembled, and `packageJobs` / `dockerLocal` are not affected.
