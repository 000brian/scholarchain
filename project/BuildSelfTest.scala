import java.io.{File, FileOutputStream}
import java.nio.charset.StandardCharsets
import java.util.jar.{Attributes, JarEntry, JarOutputStream, Manifest}

import scala.util.matching.Regex

import sbt._

/** Self-tests of the build's own checks, run by `sbt check` (markTestRun stages 1 and 2). Each
  * function returns one line per problem; an empty result means the check passed.
  *
  *   - L-09: every org.scholarchain class named by the shell scripts directly in scripts and
  *     scripts/lib or by the state machine (infra/terraform/modules/workflow/sfn.tf) is in a
  *     packageCheck class list, as scripts/check.sh gate 8 (b) requires, and every listed class has
  *     its source file in a module that reaches its jar.
  *   - B-02: packageCheck reports bad jars (missing class, forbidden prefix, Main-Class in the
  *     Hadoop jar, stale or missing build info), using small jars generated in a temporary folder.
  *   - Decision 5.2: test-tag and fork policy of Test, AwsTest and EmulatorTest (tagProblems and
  *     emulatorProblems, fed from build.sbt's buildPolicyCheck).
  *   - P16-A-02: the registered emulatorTest alias is the sequential form, never `all`
  *     (aliasProblems in buildPolicyCheck; aliasCaseProblems in buildSelfTest).
  *
  * Reads files only; writes only under a temporary folder; no network, no Docker.
  */
object BuildSelfTest {

  // ---- L-09: script contracts (mirror of scripts/check.sh gate 8 (b)) -------------------------

  /** The same pattern as gate 8 (b)'s `grep -ohE`. */
  val ScriptClassPattern: Regex = """org\.scholarchain(\.[a-z0-9_]+)+\.[A-Z][A-Za-z0-9_]*""".r

  /** Gate 8's files: the .sh files in scripts and scripts/lib (contract_script_files) and SFN_TF.
    */
  def contractFiles(base: File): Seq[File] = {
    def shells(dir: File): Seq[File] =
      Option(dir.listFiles).toSeq.flatten
        .filter(f => f.isFile && f.getName.endsWith(".sh"))
        .sortBy(_.getName)
    shells(base / "scripts") ++ shells(base / "scripts" / "lib") :+
      (base / "infra" / "terraform" / "modules" / "workflow" / "sfn.tf")
  }

  private def rel(base: File, f: File): String =
    IO.relativize(base, f).getOrElse(f.getPath).replace(File.separatorChar, '/')

  def scriptClassProblems(base: File): Seq[String] = {
    val files = contractFiles(base)
    val missing = files.filterNot(_.isFile).map(f => s"script contracts: missing ${rel(base, f)}")
    val listed = PackageCheck.ClassListsByJar.flatMap(_._2).toSet
    val named: Map[String, Seq[String]] = files
      .filter(_.isFile)
      .flatMap(f => ScriptClassPattern.findAllIn(IO.read(f)).map(c => c -> rel(base, f)).toList)
      .groupBy(_._1)
      .map { case (c, hits) => c -> hits.map(_._2).distinct.sorted }
    val unlisted = named.toSeq.sortBy(_._1).collect {
      case (c, where) if !listed(c) =>
        s"script contracts: class $c is named by ${where.mkString(", ")} but is in no packageCheck " +
          "class list (project/PackageCheck.scala); scripts/check.sh gate 8 fails"
    }
    missing ++ unlisted
  }

  def classSourceProblems(base: File): Seq[String] =
    PackageCheck.ClassListsByJar.flatMap { case (label, classes, modules) =>
      classes.flatMap { c =>
        val path = c.replace('.', '/') + ".scala"
        val found =
          modules.exists(m => (base / "modules" / m / "src" / "main" / "scala" / path).isFile)
        if (found) Nil
        else
          Seq(
            s"$label: listed class $c has no source $path under modules/{${modules.mkString(",")}}/src/main/scala"
          )
      }
    }

  // ---- B-02: packageCheck catches bad jars -----------------------------------------------------

  final case class JarSpec(entries: Map[String, Array[Byte]], mainClass: Option[String])

  val Commit: String = "0123456789abcdef0123456789abcdef01234567"
  val OtherCommit: String = "fedcba9876543210fedcba9876543210fedcba98"
  val Version: String = "0.0.0-selftest"
  val CurrentIdentity: BuildIdentity.Identity = BuildIdentity.Identity(Commit, dirty = false)

  private val PromptRel = "selftest-prompt.txt"
  private val PromptText = "self-test prompt\n"

  private def utf8(s: String): Array[Byte] = s.getBytes(StandardCharsets.UTF_8)

  private def classEntries(cs: Seq[String]): Map[String, Array[Byte]] =
    cs.map(c => PackageCheck.classEntry(c) -> utf8("self-test placeholder")).toMap

  private def info(
      module: String,
      identity: BuildIdentity.Identity = CurrentIdentity
  ): Map[String, Array[Byte]] =
    Map(BuildIdentity.ResourceName -> utf8(BuildIdentity.render(identity, module, Version)))

  /** Four jars that packageCheck accepts. */
  val GoodJars: Map[String, JarSpec] = Map(
    "hadoop" -> JarSpec(classEntries(PackageCheck.HadoopClasses) ++ info("hadoop"), None),
    "er" -> JarSpec(classEntries(PackageCheck.ErClasses) ++ info("entityResolution"), None),
    "spark" -> JarSpec(classEntries(PackageCheck.SparkClasses) ++ info("sparkAnalytics"), None),
    "services" -> JarSpec(
      classEntries(PackageCheck.ServicesClasses) ++ info("services") ++ Map(
        "logback.xml" -> utf8("<configuration/>\n"),
        s"${PromptResources.ClasspathDir}/$PromptRel" -> utf8(PromptText),
        PackageCheck.SdkHttpServiceFile -> utf8(PackageCheck.UrlConnectionService + "\n")
      ),
      Some(PackageCheck.ServicesMainClass)
    )
  )

  final case class JarCase(
      name: String,
      mutate: Map[String, JarSpec] => Map[String, JarSpec],
      expected: Seq[String]
  )

  private def edit(jar: String)(
      f: JarSpec => JarSpec
  ): Map[String, JarSpec] => Map[String, JarSpec] =
    jars => jars.updated(jar, f(jars(jar)))

  private val RetrievalEvalJob = "org.scholarchain.rag.eval.RetrievalEvalJob"

  val JarCases: Seq[JarCase] = Seq(
    JarCase("baseline: well-formed jars pass", identity, Nil),
    JarCase(
      "missing class (services jar without RetrievalEvalJob)",
      edit("services")(s =>
        s.copy(entries = s.entries - PackageCheck.classEntry(RetrievalEvalJob))
      ),
      Seq(s"services jar: missing class $RetrievalEvalJob")
    ),
    JarCase(
      "forbidden prefix (scala/ in the spark jar)",
      edit("spark")(s => s.copy(entries = s.entries + ("scala/Predef.class" -> utf8("x")))),
      Seq(
        "spark jar: contains runtime-provided classes under scala/ (1 entries, e.g. scala/Predef.class)"
      )
    ),
    JarCase(
      "forbidden prefix (io/netty/ in the services jar)",
      edit("services")(s => s.copy(entries = s.entries + ("io/netty/Channel.class" -> utf8("x")))),
      Seq(
        "services jar: contains non-url-connection HTTP client classes under io/netty/ " +
          "(1 entries, e.g. io/netty/Channel.class)"
      )
    ),
    JarCase(
      "manifest Main-Class in the hadoop jar",
      edit("hadoop")(_.copy(mainClass = Some(PackageCheck.HadoopClasses.head))),
      Seq(
        s"hadoop jar: manifest has Main-Class ${PackageCheck.HadoopClasses.head} (must have none)"
      )
    ),
    JarCase(
      "stale build-info file (older commit)",
      edit("hadoop")(s =>
        s.copy(entries =
          s.entries ++ info("hadoop", BuildIdentity.Identity(OtherCommit, dirty = false))
        )
      ),
      Seq(
        s"hadoop jar: ${BuildIdentity.ResourceName}: stale: records $OtherCommit/dirty=false; " +
          s"this build is $Commit/dirty=false"
      )
    ),
    JarCase(
      "missing build-info file",
      edit("er")(s => s.copy(entries = s.entries - BuildIdentity.ResourceName)),
      Seq(s"entity-resolution jar: missing ${BuildIdentity.ResourceName}")
    )
  )

  def writeJar(file: File, spec: JarSpec): File = {
    val manifest = new Manifest
    manifest.getMainAttributes.put(Attributes.Name.MANIFEST_VERSION, "1.0")
    spec.mainClass.foreach(m => manifest.getMainAttributes.put(Attributes.Name.MAIN_CLASS, m))
    IO.createDirectory(file.getParentFile)
    val out = new JarOutputStream(new FileOutputStream(file), manifest)
    try
      spec.entries.toSeq.sortBy(_._1).foreach { case (name, bytes) =>
        out.putNextEntry(new JarEntry(name))
        out.write(bytes)
        out.closeEntry()
      }
    finally out.close()
    file
  }

  /** Runs packageCheck over generated jars in `dir`. */
  def runPackageCheck(dir: File, jars: Map[String, JarSpec]): Seq[String] = {
    val prompts = dir / "prompts"
    IO.write(prompts / PromptRel, PromptText)
    def jar(k: String): File = writeJar(dir / s"$k.jar", jars(k))
    PackageCheck.run(
      PackageCheck.Inputs(
        hadoop = jar("hadoop"),
        er = jar("er"),
        spark = jar("spark"),
        services = jar("services"),
        servicesClasspath = Nil,
        promptSource = prompts,
        promptCopies = dir / "no-copies",
        version = Version,
        identity = CurrentIdentity
      )
    )
  }

  def jarCaseProblems(): Seq[String] =
    IO.withTemporaryDirectory { tmp =>
      JarCases.zipWithIndex.flatMap { case (c, i) =>
        val got = runPackageCheck(tmp / s"case-$i", c.mutate(GoodJars))
        if (got == c.expected) Nil
        else
          Seq(
            s"packageCheck self-test '${c.name}': expected [${c.expected.mkString(" | ")}] " +
              s"but got [${got.mkString(" | ")}]"
          )
      }
    }

  /** Everything buildSelfTest runs (stage 1 of markTestRun). */
  def run(base: File): Seq[String] =
    scriptClassProblems(base) ++ classSourceProblems(base) ++ jarCaseProblems() ++
      aliasCaseProblems()

  // ---- Decision 5.2: test-tag and fork policy ------------------------------------------------

  val ExcludeBoth: String = "--exclude-tags=RequiresAws,RequiresEmulator"
  val AwsArgs: Seq[String] = Seq("--include-tags=RequiresAws", "--exclude-tags=RequiresEmulator")
  val EmulatorArgs: Seq[String] =
    Seq("--include-tags=RequiresEmulator", "--exclude-tags=RequiresAws")

  /** Independent of build.sbt's value: user.home of the sbt JVM and the fixed port 9297. */
  val EmulatorTrustStore: String =
    new File(new File(sys.props("user.home")), ".aws/emulator/9297/truststore.p12").getPath

  def frameworkArgs(options: Seq[TestOption]): Seq[String] =
    options.collect { case Tests.Argument(_, args) => args }.flatten

  /** `testOptions` of Test and AwsTest, and `javaOptions` of Test, for every tested project. */
  def tagProblems(
      testOptions: Seq[Seq[TestOption]],
      awsOptions: Seq[Seq[TestOption]],
      testJavaOptions: Seq[Seq[String]]
  ): Seq[String] = {
    val n = testOptions.size
    def count(label: String, bad: Int): Seq[String] =
      if (bad == 0) Nil else Seq(s"$bad of $n tested projects: $label")
    val t = testOptions.map(frameworkArgs)
    val a = awsOptions.map(frameworkArgs)
    (if (n == 0) Seq("no tested project found") else Nil) ++
      count(s"Test/testOptions lack $ExcludeBoth", t.count(!_.contains(ExcludeBoth))) ++
      count(
        "Test/testOptions include tags (Test must run untagged tests only)",
        t.count(_.exists(_.startsWith("--include-tags")))
      ) ++
      count(s"AwsTest/testOptions are not ${AwsArgs.mkString(" ")}", a.count(_ != AwsArgs)) ++
      count(
        "Test/javaOptions set a trust store (only EmulatorTest's forked JVM may)",
        testJavaOptions.count(_.exists(_.contains("javax.net.ssl.trustStore")))
      )
  }

  /** EmulatorTest of one project: tag arguments, forked groups with exactly `jvm` options. */
  def emulatorProblems(
      project: String,
      options: Seq[TestOption],
      groups: Seq[Tests.Group],
      jvm: Seq[String],
      workDir: File
  ): Seq[String] = {
    val args = frameworkArgs(options)
    val argProblems =
      if (args == EmulatorArgs) Nil
      else
        Seq(
          s"$project/EmulatorTest/testOptions are ${args.mkString(" ")}; expected ${EmulatorArgs.mkString(" ")}"
        )
    val groupProblems =
      if (groups.isEmpty) Seq(s"$project/EmulatorTest/testGrouping has no group")
      else
        groups.flatMap { g =>
          g.runPolicy match {
            case Tests.SubProcess(o) =>
              val name = s"$project/EmulatorTest group ${g.name}"
              Seq(
                if (o.runJVMOptions.toList == jvm.toList) None
                else
                  Some(
                    s"$name: JVM options ${o.runJVMOptions.mkString(" ")}; expected ${jvm.mkString(" ")}"
                  ),
                if (o.workingDirectory.contains(workDir)) None
                else Some(s"$name: working directory ${o.workingDirectory}; expected $workDir"),
                // The forked JVM inherits the shell's environment; the build adds nothing.
                if (o.envVars.isEmpty) None
                else Some(s"$name: the build sets ${o.envVars.keys.toSeq.sorted.mkString(",")}")
              ).flatten
            case other => Seq(s"$project/EmulatorTest group ${g.name} is not forked ($other)")
          }
        }
    val jvmProblems = Seq(
      if (jvm.contains(s"-Djavax.net.ssl.trustStore=$EmulatorTrustStore")) None
      else Some(s"emulator JVM options lack -Djavax.net.ssl.trustStore=$EmulatorTrustStore"),
      if (jvm.contains("-Djavax.net.ssl.trustStoreType=PKCS12")) None
      else Some("emulator JVM options lack -Djavax.net.ssl.trustStoreType=PKCS12"),
      if (jvm.count(_.startsWith("-Djavax.net.ssl.")) == 2) None
      else Some("emulator JVM options set javax.net.ssl properties other than the two expected")
    ).flatten.map(p => s"$project: $p")
    argProblems ++ groupProblems ++ jvmProblems
  }

  // ---- P16-A-02: the emulatorTest alias runs the two emulator suites in sequence --------------

  val EmulatorTestAliasName: String = "emulatorTest"

  /** Storage first, then services; never together against the one emulator (run book E14). */
  val EmulatorTestAlias: String = "; storage/EmulatorTest/test ; services/EmulatorTest/test"

  /** `aliases` are (name, command) pairs, as BasicCommands.allAliases returns them. */
  def aliasProblems(aliases: Seq[(String, String)]): Seq[String] =
    aliases.filter(_._1 == EmulatorTestAliasName).map(_._2.trim) match {
      case Seq()    => Seq(s"alias $EmulatorTestAliasName is not defined")
      case Seq(cmd) =>
        val words = cmd.split("\\s+").toSeq
        Seq(
          if (words.contains("all"))
            Some(
              s"alias $EmulatorTestAliasName uses `all`, which runs both emulator suites at once " +
                "against one emulator"
            )
          else None,
          if (cmd == EmulatorTestAlias) None
          else Some(s"alias $EmulatorTestAliasName is '$cmd'; expected '$EmulatorTestAlias'")
        ).flatten
      case more => Seq(s"alias $EmulatorTestAliasName is defined ${more.size} times")
    }

  /** Self-test of aliasProblems: the sequential form passes, the old `all` form fails. */
  def aliasCaseProblems(): Seq[String] = {
    val old = "all storage/EmulatorTest/test services/EmulatorTest/test"
    Seq(
      if (aliasProblems(Seq(EmulatorTestAliasName -> EmulatorTestAlias)).isEmpty) None
      else Some("alias self-test: the sequential emulatorTest alias is reported as a problem"),
      if (aliasProblems(Seq(EmulatorTestAliasName -> old)).exists(_.contains("uses `all`"))) None
      else Some("alias self-test: the `all` form of emulatorTest is not reported"),
      if (aliasProblems(Nil).nonEmpty) None
      else Some("alias self-test: a missing emulatorTest alias is not reported")
    ).flatten
  }
}
