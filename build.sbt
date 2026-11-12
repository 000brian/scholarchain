// ScholarChain sbt multi-project build (owner: build-engineer).
//
// Every version comes from the ledger, project/build.properties (see project/Ledger.scala).
// It contains no secrets, cloud endpoints, account IDs, or deployment procedures.
//
// Environment input surface. build.sbt and project/** read no environment variable themselves
// (no sys.env, no envVars). The tools that run the build do read the following, and they change
// its behavior. [supply chain] marks inputs that decide which compiler, artifacts, or images
// enter the build or what code runs inside the build JVMs; review them in any environment that
// produces release artifacts.
//   JAVA_HOME            [supply chain] JDK that runs sbt, compiles, and forks tests. onLoad
//                        rejects any Java feature release other than version.java.
//   PATH                 [supply chain] resolves the sbt, java, and docker executables.
//   HOME                 [supply chain] locates ~/.sbt (global settings, plugins, repositories),
//                        ~/.ivy2, the default Coursier cache, and ~/.docker.
//   SBT_OPTS, JAVA_OPTS  [supply chain] JVM options of the sbt launcher; can set
//                        -Dsbt.repository.config, -Dsbt.override.build.repos, proxies, memory.
//   SBT_CREDENTIALS      [supply chain] credentials file read by the sbt launcher.
//   JAVA_TOOL_OPTIONS    [supply chain] read by HotSpot in the sbt JVM and in every forked test
//                        JVM; can inject -javaagent and arbitrary system properties.
//   JDK_JAVA_OPTIONS     [supply chain] read by the java launcher (JDK 9+) for every JVM it
//                        starts, including forked test JVMs; same reach as JAVA_TOOL_OPTIONS.
//   _JAVA_OPTIONS        [supply chain] HotSpot override options; same reach as above.
//   COURSIER_REPOSITORIES [supply chain] replaces the repositories artifacts resolve from.
//   COURSIER_MIRRORS     [supply chain] mirror file that redirects repository URLs.
//   COURSIER_CREDENTIALS [supply chain] repository credentials (inline or file).
//   COURSIER_CONFIG_DIR  [supply chain] Coursier config directory (mirrors, credentials).
//   COURSIER_CACHE       [supply chain] artifact cache location; a pre-seeded cache is trusted.
//   COURSIER_TTL, COURSIER_MODE  cache freshness and offline/update mode for resolution.
//   DOCKER_HOST          [supply chain] daemon used by dockerLocal; it pulls the base image.
//   DOCKER_CONTEXT       [supply chain] selects the Docker context (and therefore the daemon).
//   DOCKER_TLS_VERIFY, DOCKER_CERT_PATH  [supply chain] TLS trust for the selected daemon.
//   DOCKER_CONFIG        [supply chain] Docker client config (registry mirrors, credentials)
//                        used by dockerLocal when pulling the pinned base image.
//   DOCKER_BUILDKIT      builder selection for dockerLocal (the Dockerfile needs BuildKit for
//                        COPY --chmod).
//   DOCKER_DEFAULT_PLATFORM  target platform of the dockerLocal image when not set explicitly.
// Besides environment variables, these tools also read files: .sbtopts and .jvmopts in the
// repository, the sbt launcher's global sbtopts files, ~/.sbt/**, Coursier config files, and
// ~/.docker/config.json. This list covers the inputs known to change this build's behavior for
// the sbt launcher, Coursier, the JVM, and the docker CLI. It is not an exhaustive inventory of
// every variable those tools accept; consult their documentation for the full set, and add any
// newly relied-upon input here before using it.
//
// Scala policy: Scala 3 everywhere, except for the instructor-approved deviation:
//   - sparkAnalytics compiles with the Scala 2.13 version the EMR Spark runtime is built with;
//   - core is cross-built for Scala 3 and that Scala 2.13 version so Spark jobs share contracts.

import scala.sys.process.{Process, ProcessLogger}

// Validates every ledger entry when the build loads (fails on missing or floating versions).
val scala3 = { Ledger.validateAll(); Ledger.scala3 }
val scala213 = Ledger.scala213

// ---------------------------------------------------------------------------------------------
// Build-wide settings
// ---------------------------------------------------------------------------------------------

ThisBuild / organization := "org.scholarchain"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := scala3
ThisBuild / publish / skip := true
ThisBuild / javacOptions ++= Seq("--release", Ledger.java)

Global / onChangedBuildSource := ReloadOnSourceChanges

// Java 17 everywhere: fail fast when sbt runs on a different Java feature release.
Global / onLoad := {
  val previous = (Global / onLoad).value
  (state: State) => {
    val running = sys.props.getOrElse("java.specification.version", "unknown")
    if (running != Ledger.java)
      sys.error(
        s"ScholarChain requires Java ${Ledger.java} (version.java in ${Ledger.path}); sbt is running on Java $running."
      )
    previous(state)
  }
}

// MUnit tag for tests that need live AWS. `Test` (and therefore `sbt check` and markTestRun)
// excludes them. The `AwsTest` configuration runs only them, from the same test sources:
//   sbt awsTest                       every project
//   sbt <project>/AwsTest/test        one project (also AwsTest/testOnly)
// AwsTest never writes .claude/.last-test-run. Credentials and endpoints come from the test
// code's own configuration contracts, never from this build.
val requiresAwsTag = "RequiresAws"
lazy val AwsTest = config("awstest").extend(Test).describedAs("Tests tagged RequiresAws only.")

lazy val awsTestSettings: Seq[Setting[_]] =
  inConfig(AwsTest)(Defaults.testTasks) ++ Seq(
    AwsTest / testOptions := Seq(
      Tests.Argument(TestFrameworks.MUnit, s"--include-tags=$requiresAwsTag")
    )
  )

def scalacOptionsFor(scalaV: String): Seq[String] =
  CrossVersion.partialVersion(scalaV) match {
    case Some((3, _)) =>
      Seq("-release", Ledger.java, "-deprecation", "-feature", "-unchecked")
    case Some((2, 13)) =>
      Seq("-release", Ledger.java, "-deprecation", "-feature", "-unchecked", "-Xsource:3")
    case other =>
      sys.error(s"Unsupported Scala version $scalaV ($other); see ${Ledger.path}.")
  }

lazy val commonSettings: Seq[Setting[_]] = Seq(
  scalacOptions ++= scalacOptionsFor(scalaVersion.value),
  libraryDependencies += "org.scalameta" %% "munit" % Ledger.munit % Test,
  Test / testOptions += Tests.Argument(TestFrameworks.MUnit, s"--exclude-tags=$requiresAwsTag"),
  // Scala 2 projects run on the EMR Spark runtime's exact Scala library. Without this, test
  // libraries built against a newer 2.13 patch would evict it on the Test classpath.
  dependencyOverrides ++= {
    if (scalaBinaryVersion.value.startsWith("2."))
      Seq("org.scala-lang" % "scala-library" % scalaVersion.value)
    else Nil
  }
) ++ awsTestSettings

// Spark on Java 17 needs these options in forked test JVMs (sparkAnalytics only).
// Exact copy of org.apache.spark.launcher.JavaModuleOptions in spark-launcher_2.13 4.1.1
// (the version in the ledger); update it together with version.spark. It includes Netty TLS
// and allocator properties that must never reach any other module.
lazy val sparkJava17Options: Seq[String] = Seq(
  "-XX:+IgnoreUnrecognizedVMOptions",
  "--add-modules=jdk.incubator.vector",
  "--add-opens=java.base/java.lang=ALL-UNNAMED",
  "--add-opens=java.base/java.lang.invoke=ALL-UNNAMED",
  "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
  "--add-opens=java.base/java.io=ALL-UNNAMED",
  "--add-opens=java.base/java.net=ALL-UNNAMED",
  "--add-opens=java.base/java.nio=ALL-UNNAMED",
  "--add-opens=java.base/java.util=ALL-UNNAMED",
  "--add-opens=java.base/java.util.concurrent=ALL-UNNAMED",
  "--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED",
  "--add-opens=java.base/jdk.internal.ref=ALL-UNNAMED",
  "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
  "--add-opens=java.base/sun.nio.cs=ALL-UNNAMED",
  "--add-opens=java.base/sun.security.action=ALL-UNNAMED",
  "--add-opens=java.base/sun.util.calendar=ALL-UNNAMED",
  "--add-opens=java.security.jgss/sun.security.krb5=ALL-UNNAMED",
  "-Djdk.reflect.useDirectMethodHandle=false",
  "-Dio.netty.tryReflectionSetAccessible=true",
  "-Dio.netty.allocator.type=pooled",
  "-Dio.netty.handler.ssl.defaultEndpointVerificationAlgorithm=NONE",
  "--enable-native-access=ALL-UNNAMED"
)

// Hadoop 3.4 (local MapReduce job runner and LocalFileSystem) on Java 17: only the module
// opens its reflective access needs. No Netty, TLS, allocator, or incubator-module options.
//   java.lang, java.lang.reflect  ReflectionUtils, ShutdownHookManager, UGI
//   java.io, java.nio, sun.nio.ch NativeIO fallbacks, direct buffer cleanup, local FS
//   java.net                      NetUtils socket factories
//   java.util, java.util.concurrent  Configuration and job-runner internals
//   sun.security.krb5 (jgss)      KerberosUtil reads the default realm reflectively
lazy val hadoopJava17Options: Seq[String] = Seq(
  "--add-opens=java.base/java.lang=ALL-UNNAMED",
  "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
  "--add-opens=java.base/java.io=ALL-UNNAMED",
  "--add-opens=java.base/java.net=ALL-UNNAMED",
  "--add-opens=java.base/java.nio=ALL-UNNAMED",
  "--add-opens=java.base/java.util=ALL-UNNAMED",
  "--add-opens=java.base/java.util.concurrent=ALL-UNNAMED",
  "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
  "--add-opens=java.security.jgss/sun.security.krb5=ALL-UNNAMED"
)

def forkedTests(options: Seq[String]): Seq[Setting[_]] = Seq(
  Test / fork := true,
  Test / javaOptions ++= options
)

// Fat-jar packaging for jobs and services. Provided dependencies are never included.
lazy val assemblySettings: Seq[Setting[_]] = Seq(
  assembly / assemblyJarName := s"${name.value}-assembly-${version.value}.jar",
  assembly / assemblyMergeStrategy := {
    val fallback = (assembly / assemblyMergeStrategy).value
    locally {
      case PathList("module-info.class")                            => MergeStrategy.discard
      case PathList("META-INF", "versions", _, "module-info.class") => MergeStrategy.discard
      case other                                                    => fallback(other)
    }
  }
)

// ---------------------------------------------------------------------------------------------
// Dependencies (versions from the ledger)
// ---------------------------------------------------------------------------------------------

lazy val upickle = "com.lihaoyi" %% "upickle" % Ledger.upickle
lazy val hadoopClientApi = "org.apache.hadoop" % "hadoop-client-api" % Ledger.hadoop % Provided
lazy val hadoopClientRuntime =
  "org.apache.hadoop" % "hadoop-client-runtime" % Ledger.hadoop % Provided
lazy val sparkSql = "org.apache.spark" %% "spark-sql" % Ledger.spark % Provided
lazy val slf4jApi = "org.slf4j" % "slf4j-api" % Ledger.slf4j
lazy val logbackClassic = "ch.qos.logback" % "logback-classic" % Ledger.logback % Runtime

// ---------------------------------------------------------------------------------------------
// Modules (boundaries from CLAUDE.md "Module dependency boundaries")
// ---------------------------------------------------------------------------------------------

// Shared domain records, identifiers, serialization and configuration contracts.
// No Hadoop, Spark, AWS SDK, or HTTP dependency.
// Cross-built (deviation): sbt project `core` is Scala 3; `core2_13` is the Scala 2.13 row
// used only by sparkAnalytics. Both compile the same sources in modules/core/src.
lazy val core = (projectMatrix in file("modules/core"))
  .configs(AwsTest)
  .defaultAxes(VirtualAxis.jvm, VirtualAxis.scalaABIVersion(scala3))
  .settings(
    name := "scholarchain-core",
    commonSettings,
    libraryDependencies += upickle
  )
  .jvmPlatform(scalaVersions = Seq(scala3, scala213))

lazy val coreScala3 = core.jvm(scala3)
lazy val coreScala213 = core.jvm(scala213)

// Hadoop MapReduce jobs (Java MapReduce API). Hadoop is supplied by the EMR runtime.
lazy val hadoop = (project in file("modules/hadoop"))
  .configs(AwsTest)
  .dependsOn(coreScala3)
  .settings(
    name := "scholarchain-hadoop",
    commonSettings,
    forkedTests(hadoopJava17Options),
    assemblySettings,
    libraryDependencies ++= Seq(hadoopClientApi, hadoopClientRuntime)
  )

// Entity resolution: blocking keys, match features, thresholds, evaluation harness.
lazy val entityResolution = (project in file("modules/entity-resolution"))
  .configs(AwsTest)
  .dependsOn(coreScala3)
  .settings(
    name := "scholarchain-entity-resolution",
    commonSettings,
    assemblySettings
  )

// Spark graph construction and analytics. Scala 2.13 to match the Spark runtime (deviation).
// Spark and the Scala 2.13 library are supplied by the EMR runtime and are not assembled.
lazy val sparkAnalytics = (project in file("modules/spark"))
  .configs(AwsTest)
  .dependsOn(coreScala213)
  .settings(
    name := "scholarchain-spark-analytics",
    scalaVersion := scala213,
    commonSettings,
    forkedTests(sparkJava17Options),
    assemblySettings,
    assemblyPackageScala / assembleArtifact := false,
    libraryDependencies += sparkSql
  )

// Loaders and adapters for S3, Neptune, DynamoDB, S3 Vectors. The only module that talks to
// AWS data stores.
lazy val storage = (project in file("modules/storage"))
  .configs(AwsTest)
  .dependsOn(coreScala3)
  .settings(
    name := "scholarchain-storage",
    commonSettings
  )

// Retrieval, evidence bundles, claim verification. Reaches stores only through storage ports.
lazy val rag = (project in file("modules/rag"))
  .configs(AwsTest)
  .dependsOn(coreScala3, storage)
  .settings(
    name := "scholarchain-rag",
    commonSettings
  )

// The only HTTP layer. Nothing depends on services.
lazy val dockerBuildLocal =
  taskKey[Unit]("Builds the local services image from docker/services/Dockerfile.")

lazy val services = (project in file("modules/services"))
  .configs(AwsTest)
  .dependsOn(coreScala3, rag, storage)
  .settings(
    name := "scholarchain-services",
    commonSettings,
    assemblySettings,
    libraryDependencies ++= Seq(slf4jApi, logbackClassic),
    dockerBuildLocal := {
      val log = streams.value.log
      val jar = assembly.value
      val context = target.value / "docker-context"
      val dockerfile = (ThisBuild / baseDirectory).value / "docker" / "services" / "Dockerfile"
      IO.delete(context)
      IO.createDirectory(context)
      IO.copyFile(jar, context / "app.jar")
      val command = Seq(
        "docker",
        "build",
        "--file",
        dockerfile.getAbsolutePath,
        "--build-arg",
        s"JAVA_RUNTIME_IMAGE=${Ledger.javaRuntimeImage}",
        "--tag",
        "scholarchain-services:local",
        context.getAbsolutePath
      )
      log.info(s"Running: ${command.mkString(" ")}")
      val exit = Process(command).!(ProcessLogger(out => log.info(out), err => log.info(err)))
      if (exit != 0)
        throw new MessageOnlyException(s"docker build failed with exit code $exit")
    }
  )

// ---------------------------------------------------------------------------------------------
// Test trees owned by test-engineer
// ---------------------------------------------------------------------------------------------

// Cross-module contract tests that do not need AWS (tests/contracts/src/test/scala).
// Tests that need live AWS carry the MUnit tag "RequiresAws" and are excluded by check.
lazy val contractTests = (project in file("tests/contracts"))
  .configs(AwsTest)
  .dependsOn(coreScala3, hadoop, entityResolution, storage, rag, services)
  .settings(
    name := "scholarchain-contract-tests",
    commonSettings
  )

// ---------------------------------------------------------------------------------------------
// Root aggregate, test gate, and aliases
// ---------------------------------------------------------------------------------------------

lazy val moduleProjects: Seq[ProjectReference] = Seq(
  coreScala3,
  coreScala213,
  hadoop,
  entityResolution,
  sparkAnalytics,
  storage,
  rag,
  services
).map(p => p: ProjectReference)

lazy val moduleTests =
  taskKey[Int]("Runs every module test suite and fails on any failure. Returns executed tests.")
lazy val contractTestsNoAws =
  taskKey[Int]("Runs contract tests that do not need AWS. Returns executed tests.")
lazy val markTestRun =
  taskKey[Unit](
    "Ledger and format checks, Test/compile, module tests, contract tests (no AWS), then writes .claude/.last-test-run."
  )
lazy val ledgerCheck =
  taskKey[Unit]("Checks that .scalafmt.conf pins the scalafmt version from the ledger.")

lazy val root = (project in file("."))
  .configs(AwsTest)
  .aggregate((moduleProjects :+ (contractTests: ProjectReference)): _*)
  .settings(
    name := "scholarchain",
    // The formatter hook runs `sbt "scalafmtOnly <repo-relative path>"`; resolve it only
    // against the root, not against every aggregated project's base directory.
    scalafmtOnly / aggregate := false,
    moduleTests := TestGate.evaluate(
      "Module tests",
      (Test / executeTests).all(ScopeFilter(inProjects(moduleProjects: _*))).value,
      streams.value.log
    ),
    contractTestsNoAws := TestGate.evaluate(
      "Contract tests (no AWS)",
      (Test / executeTests).all(ScopeFilter(inProjects(contractTests))).value,
      streams.value.log
    ),
    awsTestSettings,
    // markTestRun is the whole gate, in strict stages; each stage starts only after the previous
    // one succeeded in the same invocation, so running `sbt markTestRun` alone cannot skip a
    // check:
    //   1. ledgerCheck, scalafmtSbtCheck and scalafmtCheckAll in every project
    //   2. Test/compile in every project
    //   3. module tests            4. contract tests (no AWS)
    //   5. write the marker, only if at least one test executed
    // Any failure throws before stage 5, so the marker is never touched.
    markTestRun := Def.taskDyn {
      ledgerCheck.value
      (Compile / scalafmtSbtCheck).all(ScopeFilter(inAnyProject)).value
      scalafmtCheckAll.all(ScopeFilter(inAnyProject)).value
      Def.taskDyn {
        (Test / compile).all(ScopeFilter(inAnyProject)).value
        Def.taskDyn {
          val moduleCount = moduleTests.value
          Def.task {
            val contractCount = contractTestsNoAws.value
            val log = streams.value.log
            if (moduleCount + contractCount == 0)
              throw new MessageOnlyException(
                "No tests executed; .claude/.last-test-run was not updated. A passing test must exist."
              )
            if (contractCount == 0)
              log.warn("No contract tests executed (tests/contracts/src/test/scala is empty).")
            val marker = (ThisBuild / baseDirectory).value / ".claude" / ".last-test-run"
            IO.write(
              marker,
              s"""timestamp=${java.time.Instant.now()}
                 |module_tests=$moduleCount
                 |contract_tests=$contractCount
                 |""".stripMargin
            )
            log.info(
              s"Updated ${marker.getPath} (module_tests=$moduleCount, contract_tests=$contractCount)"
            )
          }
        }
      }
    }.value,
    ledgerCheck := {
      val conf = (ThisBuild / baseDirectory).value / ".scalafmt.conf"
      val pattern = """^\s*version\s*=\s*"?([^"\s]+)"?\s*$""".r
      val pinned = IO.readLines(conf).collectFirst { case pattern(v) => v }
      if (!pinned.contains(Ledger.scalafmt))
        throw new MessageOnlyException(
          s".scalafmt.conf version ${pinned.getOrElse("<missing>")} does not match " +
            s"version.scalafmt=${Ledger.scalafmt} in ${Ledger.path}."
        )
      streams.value.log.info(s"Ledger check passed (scalafmt ${Ledger.scalafmt}).")
    }
  )

addCommandAlias("fmt", "; scalafmtSbt ; scalafmtAll")
// `check` is markTestRun, which runs every step of the definition of done in order (see the
// stage list above). The steps are not repeated as separate commands so that they run once.
addCommandAlias("check", "markTestRun")
// Runs only tests tagged RequiresAws (needs live AWS; never part of check or markTestRun).
addCommandAlias("awsTest", "AwsTest/test")
addCommandAlias(
  "packageJobs",
  "; hadoop/assembly ; entityResolution/assembly ; sparkAnalytics/assembly"
)
addCommandAlias("dockerLocal", "services/dockerBuildLocal")
