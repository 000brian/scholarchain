package org.scholarchain.storage.emulator

import java.io.ByteArrayInputStream
import java.net.ConnectException
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import scala.collection.immutable.ListMap
import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.jdk.CollectionConverters._
import scala.jdk.OptionConverters._

import org.scholarchain.core.config.ConfigLoader
import org.scholarchain.storage.{FailureMasking, MaskedFailures, StorageConfig, TestFixtures}
import org.scholarchain.storage.aws.{AwsClientSettings, AwsSdkClients, AwsStorageEnvironment}
import org.scholarchain.storage.ports.ObjectRef
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider
import software.amazon.awssdk.core.SdkClient
import software.amazon.awssdk.core.client.config.SdkAdvancedClientOption
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.http.{
  AbortableInputStream,
  ExecutableHttpRequest,
  HttpExecuteRequest,
  HttpExecuteResponse,
  SdkHttpClient,
  SdkHttpResponse
}
import software.amazon.awssdk.services.dynamodb.model.{AttributeValue, GetItemRequest}
import software.amazon.awssdk.services.s3.model.{HeadObjectRequest, S3Exception}
import software.amazon.awssdk.services.s3vectors.model.GetIndexRequest
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest

/** Phase 16 decision 6.6, offline (runs in `Test`, so in `sbt check`): the emulator safety contract
  * (every refusal, with fake environment maps), its identity with `scripts/emulator/lib.sh`, the
  * emulator client builder (loopback endpoint, host-prefix injection off, path-style S3, static
  * credentials from the emulator file only), production clients unchanged, result lines and outcome
  * classes, the `config/emulator.json` loader test, and L-10 masking. No network: real SDK clients
  * over a stub HTTP transport.
  */
class EmulatorWiringSuite extends munit.FunSuite {

  // ---- fixtures --------------------------------------------------------------------------

  private val home = "/Users/emu-test"
  private val dir = s"$home/.aws/emulator/9297"
  private val good: Map[String, String] = Map(
    "HOME" -> home,
    "SCHOLARCHAIN_EMULATOR_ENDPOINT" -> "https://127.0.0.1:9297",
    "AWS_ENDPOINT_URL" -> "https://127.0.0.1:9297",
    "AWS_SHARED_CREDENTIALS_FILE" -> s"$dir/emulator_credentials",
    "AWS_CONFIG_FILE" -> s"$dir/config",
    "SCHOLARCHAIN_CONFIG_FILE" -> "/repo/config/emulator.json"
  )
  private val exists: Path => Boolean = _.toString == s"$dir/emulator_credentials"
  private def checkOf(env: Map[String, String], required: Boolean = false) =
    EmulatorSafety.check(env, exists, required, () => "generated-run")

  private lazy val repoRoot: Path =
    Iterator
      .iterate(Paths.get(sys.props("user.dir")).toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .take(4)
      .find(p => Files.isRegularFile(p.resolve("config/emulator.json")))
      .getOrElse(fail("config/emulator.json not found above the working directory"))

  private lazy val emuConfig: StorageConfig = {
    val f = repoRoot.resolve("config/emulator.json")
    val text = new String(Files.readAllBytes(f), StandardCharsets.UTF_8)
    ConfigLoader
      .load(text, "config/emulator.json", Map.empty)
      .left
      .map(_.message)
      .flatMap(l => StorageConfig.from(l.config).left.map(_.mkString("; ")))
      .fold(e => fail(e), identity)
  }

  private val settings = AwsClientSettings("us-east-1", 1000, 1000, 5000, 1)

  /** Records every request; answers `status` with an empty body. */
  private final class StubHttp(status: Int = 404) extends SdkHttpClient {
    val requests = mutable.ListBuffer.empty[(String, Int, String, Option[String])]
    override def prepareRequest(r: HttpExecuteRequest): ExecutableHttpRequest =
      new ExecutableHttpRequest {
        def call(): HttpExecuteResponse = {
          val h = r.httpRequest()
          requests.synchronized {
            requests += ((
              h.host(),
              h.port(),
              h.encodedPath(),
              h.firstMatchingHeader("Authorization").toScala
            ))
          }
          HttpExecuteResponse
            .builder()
            .response(
              SdkHttpResponse.builder().statusCode(status).putHeader("Content-Length", "0").build()
            )
            .responseBody(
              AbortableInputStream.create(new ByteArrayInputStream(Array.emptyByteArray))
            )
            .build()
        }
        def abort(): Unit = ()
      }
    def close(): Unit = ()
  }

  private val credentialsText =
    "[default]\naws_access_key_id = EMULATOR0123456789AB\naws_secret_access_key = 0123456789ABCDEF0123456789ABCDEF01234567\n"

  private def target: EmulatorSafety.Target = checkOf(good).fold(r => fail(r), identity)

  private def emulatorClients(http: StubHttp): AwsSdkClients =
    EmulatorClients.forEmulator(
      target,
      settings,
      emuConfig,
      EmulatorClients.parseCredentials(credentialsText).fold(e => fail(e), identity),
      Some(http)
    )

  private def quietly(f: => Any): Unit =
    try { f; () }
    catch { case scala.util.control.NonFatal(_) => () }

  // ---- the safety contract -----------------------------------------------------------------

  test(
    "contract: a correct environment passes, with the run ID from the environment or generated"
  ) {
    val t = checkOf(good).fold(r => fail(r), identity)
    assertEquals(t.endpoint.toString, "https://127.0.0.1:9297")
    assertEquals((t.resultsDir, t.runId), (None, "generated-run"))
    val withRun =
      checkOf(good + ("SCHOLARCHAIN_EMULATOR_RUN_ID" -> "20261122T100000Z-abc1234-9f0e"))
    assertEquals(withRun.map(_.runId), Right("20261122T100000Z-abc1234-9f0e"))
    val badRun = checkOf(good + ("SCHOLARCHAIN_EMULATOR_RUN_ID" -> "has space"))
    assertEquals(badRun.map(_.runId), Right("generated-run"))
    val withDir =
      checkOf(good + ("SCHOLARCHAIN_EMULATOR_RESULTS_DIR" -> "/repo/experiments/emulator/raw/R1"))
    assertEquals(
      withDir.map(_.resultsDir.map(_.toString)),
      Right(Some("/repo/experiments/emulator/raw/R1"))
    )
  }

  test(
    "contract: endpoint must be exactly https://127.0.0.1:9297 (localhost, other hosts and ports refused)"
  ) {
    List(
      "https://localhost:9297",
      "https://127.0.0.1:9298",
      "http://127.0.0.1:9297",
      "https://127.0.0.1:9297/",
      "https://10.0.0.1:9297",
      ""
    ).foreach { e =>
      assertEquals(
        checkOf(good + ("SCHOLARCHAIN_EMULATOR_ENDPOINT" -> e)).left.toOption,
        Some("endpoint"),
        e
      )
    }
    assertEquals(checkOf(good - "SCHOLARCHAIN_EMULATOR_ENDPOINT").left.toOption, Some("endpoint"))
  }

  test("contract: AWS_ENDPOINT_URL must equal the endpoint") {
    assertEquals(
      checkOf(good + ("AWS_ENDPOINT_URL" -> "https://localhost:9297")).left.toOption,
      Some("aws-endpoint-url")
    )
    assertEquals(checkOf(good - "AWS_ENDPOINT_URL").left.toOption, Some("aws-endpoint-url"))
  }

  test(
    "contract: the credentials file must be exactly the emulator's, under an absolute HOME, and exist"
  ) {
    assertEquals(
      checkOf(good + ("HOME" -> "relative/home")).left.toOption,
      Some("credentials-file")
    )
    assertEquals(checkOf(good - "HOME").left.toOption, Some("credentials-file"))
    assertEquals(
      checkOf(good + ("AWS_SHARED_CREDENTIALS_FILE" -> s"$home/.aws/credentials")).left.toOption,
      Some("credentials-file")
    )
    assertEquals(
      EmulatorSafety.check(good, _ => false, false, () => "r").left.toOption,
      Some("credentials-file-exists")
    )
  }

  test("contract: AWS_CONFIG_FILE must be exactly the emulator's") {
    assertEquals(
      checkOf(good + ("AWS_CONFIG_FILE" -> s"$home/.aws/config")).left.toOption,
      Some("config-file")
    )
    assertEquals(checkOf(good - "AWS_CONFIG_FILE").left.toOption, Some("config-file"))
  }

  test(
    "contract: each of the eight variables must be unset; an empty value counts as set (as lib.sh)"
  ) {
    assertEquals(EmulatorSafety.UnsetVariables.size, 8)
    EmulatorSafety.UnsetVariables.foreach { v =>
      assertEquals(checkOf(good + (v -> "x")).left.toOption, Some(s"unset:$v"), v)
      assertEquals(checkOf(good + (v -> "")).left.toOption, Some(s"unset:$v"), s"$v empty")
    }
    // the first one in lib.sh order is named
    assertEquals(
      checkOf(good + ("AWS_SESSION_TOKEN" -> "") + ("AWS_PROFILE" -> "p")).left.toOption,
      Some("unset:AWS_PROFILE")
    )
  }

  test(
    "contract: SCHOLARCHAIN_CONFIG_FILE and SCHOLARCHAIN_EMULATOR_RESULTS_DIR must be absolute"
  ) {
    assertEquals(
      checkOf(good + ("SCHOLARCHAIN_CONFIG_FILE" -> "config/emulator.json")).left.toOption,
      Some("config-file-path")
    )
    assertEquals(checkOf(good - "SCHOLARCHAIN_CONFIG_FILE").left.toOption, Some("config-file-path"))
    assertEquals(
      checkOf(
        good + ("SCHOLARCHAIN_EMULATOR_RESULTS_DIR" -> "experiments/emulator/raw/R1")
      ).left.toOption,
      Some("results-dir-path")
    )
    assertEquals(
      checkOf(good + ("SCHOLARCHAIN_EMULATOR_RESULTS_DIR" -> "")).left.toOption,
      Some("results-dir-path")
    )
    assert(checkOf(good).isRight, "optional when unset")
    assertEquals(checkOf(good, required = true).left.toOption, Some("results-dir-path"))
  }

  test("contract: the rules are checked in lib.sh's order (the first failing rule is named)") {
    val allBad = Map(
      "HOME" -> "x",
      "SCHOLARCHAIN_EMULATOR_ENDPOINT" -> "https://localhost:9297",
      "AWS_PROFILE" -> "p",
      "SCHOLARCHAIN_CONFIG_FILE" -> "rel"
    )
    assertEquals(checkOf(allBad).left.toOption, Some("endpoint"))
    assertEquals(
      EmulatorSafety.Rules.All,
      List(
        "endpoint",
        "aws-endpoint-url",
        "credentials-file",
        "credentials-file-exists",
        "config-file"
      ) ++
        EmulatorSafety.UnsetVariables.map("unset:" + _) ++ List(
          "config-file-path",
          "results-dir-path"
        )
    )
  }

  test("contract: identical to scripts/emulator/lib.sh (endpoint, port, unset list, rule names)") {
    val lib = new String(
      Files.readAllBytes(repoRoot.resolve("scripts/emulator/lib.sh")),
      StandardCharsets.UTF_8
    )
    val UnsetLine = """(?m)^EMU_UNSET_VARS="([^"]+)"""".r
    val unset = UnsetLine
      .findFirstMatchIn(lib)
      .map(_.group(1).split(" ").toList)
      .getOrElse(fail("EMU_UNSET_VARS"))
    assertEquals(unset, EmulatorSafety.UnsetVariables)
    assert(lib.contains("""EMU_ENDPOINT_EXPECTED="https://127.0.0.1:9297""""))
    assert(lib.contains("""EMU_PORT="9297""""))
    val RuleSet = """EMU_FAILED_RULE="([^"$]+)"""".r
    val scriptRules = RuleSet.findAllMatchIn(lib).map(_.group(1)).toList.distinct
    assertEquals(
      scriptRules,
      List(
        "endpoint",
        "aws-endpoint-url",
        "credentials-file",
        "credentials-file-exists",
        "config-file",
        "config-file-path",
        "results-dir-path"
      )
    )
    assert(lib.contains("""EMU_FAILED_RULE="unset:${v}""""))
    assert(lib.contains("""if [ -n "${!v+x}" ]; then"""), "lib.sh treats an empty value as set")
    assertEquals(
      EmulatorSafety.Rules.All.filterNot(_.startsWith("unset:")),
      scriptRules
    )
    assertEquals(libSafetyMismatches(lib), Nil)
  }

  // P16-A-05: the parts the first comparison did not cover. Each lib.sh rule is read from the
  // script text and compared with what EmulatorSafety.check does on the same inputs.

  private lazy val libText: String = new String(
    Files.readAllBytes(repoRoot.resolve("scripts/emulator/lib.sh")),
    StandardCharsets.UTF_8
  )

  /** A shell `case` pattern (`*`, `?`, literal characters) as a whole-string regex. */
  private def globMatches(glob: String, v: String): Boolean =
    v.matches(glob.map {
      case '*' => ".*"
      case '?' => "."
      case c   => java.util.regex.Pattern.quote(c.toString)
    }.mkString)

  /** The pattern of lib.sh's `case "${VAR:-}" in PATTERN) ;; *) EMU_FAILED_RULE="RULE"` block. */
  private def libCasePattern(lib: String, variable: String, rule: String): Option[String] =
    ("""case "\$\{""" + java.util.regex.Pattern.quote(variable) +
      """:-\}" in\s+(\S+)\) ;;\s+\*\)\s+EMU_FAILED_RULE="""" +
      java.util.regex.Pattern.quote(rule) + "\"").r.findFirstMatchIn(lib).map(_.group(1))

  /** Every difference between lib.sh's results-dir set test, absolute-path (slash-star) rules and
    * credentials/config path suffixes and EmulatorSafety.check; Nil when they are identical.
    */
  private def libSafetyMismatches(lib: String): List[String] = {
    val out = mutable.ListBuffer.empty[String]
    val samples = List("/", "/a/b", "a", "", "./a", "a/", " /a")
    def refusedWith(env: Map[String, String], rule: String): Boolean =
      EmulatorSafety.check(env, _ => true, false, () => "r").left.toOption.contains(rule)

    // 1. lib.sh:140, the results-dir set test: `+x` (set, even empty) or `:+x` (non-empty only)
    val SetTest = """\[ -n "\$\{SCHOLARCHAIN_EMULATOR_RESULTS_DIR(:?\+)x\}" \]""".r
    val setOp = SetTest.findAllMatchIn(lib).map(_.group(1)).toList match {
      case List(op) => Some(op)
      case other    => out += s"results-dir set test: found $other"; None
    }
    setOp.foreach { op =>
      val libEmptyIsSet = op == "+"
      val scalaEmptyIsSet =
        refusedWith(good + (EmulatorSafety.ResultsDirVar -> ""), "results-dir-path")
      if (libEmptyIsSet != scalaEmptyIsSet)
        out += s"results-dir set test: lib.sh empty-is-set=$libEmptyIsSet, Scala=$scalaEmptyIsSet"
    }

    // 2. lib.sh:107-113, :133-139, :141-147, the absolute-path `case` rules
    def homeEnv(h: String) = good ++ Map(
      "HOME" -> h,
      EmulatorSafety.CredentialsFileVar -> s"$h/.aws/emulator/9297/emulator_credentials",
      EmulatorSafety.ConfigFileVar -> s"$h/.aws/emulator/9297/config"
    )
    List[(String, String, String => Map[String, String])](
      ("HOME", "credentials-file", homeEnv),
      (
        "SCHOLARCHAIN_CONFIG_FILE",
        "config-file-path",
        v => good + ("SCHOLARCHAIN_CONFIG_FILE" -> v)
      ),
      (
        EmulatorSafety.ResultsDirVar,
        "results-dir-path",
        v => good + (EmulatorSafety.ResultsDirVar -> v)
      )
    ).foreach { case (variable, rule, envOf) =>
      libCasePattern(lib, variable, rule) match {
        case None          => out += s"$rule: no case block for $variable"
        case Some(pattern) =>
          samples.foreach { v =>
            // the results-dir block runs only when the set test triggers (optional mode)
            val checked =
              variable != EmulatorSafety.ResultsDirVar || setOp.forall(_ == "+") || v.nonEmpty
            val libAccepts = !checked || globMatches(pattern, v)
            val scalaAccepts = !refusedWith(envOf(v), rule)
            if (libAccepts != scalaAccepts)
              out += s"$rule: pattern '$pattern' on '$v': lib.sh=$libAccepts, Scala=$scalaAccepts"
          }
      }
    }

    // 3. lib.sh:91, :115, :123, the credentials and config file paths under $HOME
    val EmuDir = """emu_dir\(\) \{ printf '%s([^']*)/%s' "\$\{HOME:-\}" "\$\{EMU_PORT\}"; \}""".r
    val Port = """(?m)^EMU_PORT="([0-9]+)"""".r
    def pathRule(variable: String, rule: String) =
      ("""if \[ "\$\{""" + variable + """:-\}" != "\$\{dir\}([^"]*)" \]; then\s+EMU_FAILED_RULE="""" +
        rule + "\"").r.findFirstMatchIn(lib).map(_.group(1))
    (
      EmuDir.findFirstMatchIn(lib).map(_.group(1)),
      Port.findFirstMatchIn(lib).map(_.group(1)),
      pathRule("AWS_SHARED_CREDENTIALS_FILE", "credentials-file"),
      pathRule("AWS_CONFIG_FILE", "config-file")
    ) match {
      case (Some(base), Some(port), Some(cred), Some(conf)) =>
        if (!lib.contains("""dir="$(emu_dir)""""))
          out += "credentials/config paths: dir is not $(emu_dir)"
        val dirPath = s"$home$base/$port"
        val credPath = s"$dirPath$cred"
        val libEnv = good ++ Map(
          EmulatorSafety.CredentialsFileVar -> credPath,
          EmulatorSafety.ConfigFileVar -> s"$dirPath$conf"
        )
        EmulatorSafety.check(libEnv, _.toString == credPath, false, () => "r") match {
          case Left(r)  => out += s"credentials/config paths: lib.sh's paths refused by Scala ($r)"
          case Right(_) => ()
        }
      case parts => out += s"credentials/config paths: not found in lib.sh $parts"
    }
    out.toList
  }

  test("P16-A-05: the lib.sh comparison fails when lib.sh's set test, path rules or paths change") {
    def mutate(from: String, to: String): String = {
      val m = libText.replaceFirst(from, to)
      assertNotEquals(m, libText, s"mutation $from did not apply")
      m
    }
    val Q = java.util.regex.Matcher.quoteReplacement _
    List(
      // empty counts as unset (the finding's own example)
      mutate(
        """\$\{SCHOLARCHAIN_EMULATOR_RESULTS_DIR\+x\}""",
        Q("${SCHOLARCHAIN_EMULATOR_RESULTS_DIR:+x}")
      ) ->
        "results-dir set test",
      mutate("""(case "\$\{HOME:-\}" in\s+)/\*""", "$1*") -> "credentials-file:",
      mutate(
        """(case "\$\{SCHOLARCHAIN_CONFIG_FILE:-\}" in\s+)/\*""",
        "$1?*"
      ) -> "config-file-path:",
      mutate("""(case "\$\{SCHOLARCHAIN_EMULATOR_RESULTS_DIR:-\}" in\s+)/\*""", "$1*") ->
        "results-dir-path:",
      mutate("""printf '%s/\.aws/emulator/%s'""", Q("printf '%s/.aws/emu/%s'")) ->
        "credentials/config paths",
      mutate("""\$\{dir\}/emulator_credentials" \]""", Q("${dir}/credentials\" ]")) ->
        "credentials/config paths",
      mutate("""\$\{dir\}/config" \]""", Q("${dir}/config.ini\" ]")) -> "credentials/config paths"
    ).foreach { case (mutated, label) =>
      val found = libSafetyMismatches(mutated)
      assert(found.exists(_.startsWith(label)), s"expected a '$label' mismatch, got $found")
    }
  }

  // ---- credentials ----------------------------------------------------------------------

  test(
    "credentials: the [default] profile, or the only profile; a missing key is named without values"
  ) {
    val c = EmulatorClients.parseCredentials(credentialsText).fold(e => fail(e), identity)
    assertEquals(c.resolveCredentials().accessKeyId(), "EMULATOR0123456789AB")
    val only = EmulatorClients.parseCredentials(
      "[emu]\naws_access_key_id=EMULATORAAAAAAAAAAAA\naws_secret_access_key=BBBB\n"
    )
    assertEquals(only.map(_.resolveCredentials().accessKeyId()), Right("EMULATORAAAAAAAAAAAA"))
    val noSecret =
      EmulatorClients.parseCredentials("[default]\naws_access_key_id=EMULATORAAAAAAAAAAAA\n")
    assertEquals(
      noSecret.left.toOption,
      Some("the emulator credentials file has no aws_secret_access_key")
    )
    val two =
      EmulatorClients.parseCredentials("[a]\naws_access_key_id=X\n[b]\naws_access_key_id=Y\n")
    assertEquals(two.left.toOption, Some("the emulator credentials file has no [default] profile"))
    val tmp = Files.createTempDirectory("emu-cred")
    val missing = EmulatorClients.readCredentials(tmp.resolve("absent"))
    assert(
      missing.left.exists(m => m.startsWith("cannot read") && !m.contains(tmp.toString)),
      missing.toString
    )
  }

  // ---- the emulator client builder ----------------------------------------------------------

  test(
    "forEmulator: every client sends to 127.0.0.1:9297; S3 is path-style; signed with the file's key only"
  ) {
    val http = new StubHttp()
    val c = emulatorClients(http)
    val keyProps = List("aws.accessKeyId", "aws.secretAccessKey")
    val before = keyProps.map(k => k -> sys.props.get(k))
    try {
      // real-looking keys as JVM system properties (AWS's documented example keys) must not reach it
      sys.props("aws.accessKeyId") = "AKIAIOSFODNN7EXAMPLE"
      sys.props("aws.secretAccessKey") = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
      quietly(
        c.s3.headObject(
          HeadObjectRequest.builder().bucket("scholarchain-emu-data").key("k/x.json").build()
        )
      )
      quietly(
        c.dynamoDb.getItem(
          GetItemRequest
            .builder()
            .tableName("scholarchain-emu-run-ledger")
            .key(Map("pk" -> AttributeValue.fromS("p"), "sk" -> AttributeValue.fromS("s")).asJava)
            .build()
        )
      )
      quietly(
        c.s3Vectors.getIndex(
          GetIndexRequest
            .builder()
            .vectorBucketName("scholarchain-emu-vectors")
            .indexName("researcher-profile-v1")
            .build()
        )
      )
      quietly(
        c.sqs.get.getQueueUrl(
          GetQueueUrlRequest.builder().queueName("scholarchain-emu-jobs").build()
        )
      )
    } finally
      before.foreach {
        case (k, Some(v)) => sys.props(k) = v
        case (k, None)    => sys.props -= k
      }
    val reqs = http.requests.toList
    assertEquals(reqs.size, 4, reqs.map(r => (r._1, r._2, r._3)).toString)
    assert(
      reqs.forall(r => r._1 == "127.0.0.1" && r._2 == 9297),
      reqs.map(r => (r._1, r._2)).toString
    )
    assertEquals(
      reqs.head._3,
      "/scholarchain-emu-data/k/x.json",
      "S3 path-style: the bucket is in the path"
    )
    reqs.foreach { r =>
      val auth = r._4.getOrElse(fail("unsigned request"))
      assert(
        auth.contains("Credential=EMULATOR0123456789AB/"),
        "signed with the emulator file's key"
      )
      assert(
        !auth.contains("AKIAIOSFODNN7EXAMPLE"),
        "system-property keys never reach an emulator client"
      )
    }
    assertEquals(c.neptune, None, "no Neptune client for the emulator")
  }

  test(
    "forEmulator: host-prefix injection is off and the production timeouts and one SDK attempt are kept"
  ) {
    val c = emulatorClients(new StubHttp())
    val all: List[SdkClient] = List(c.s3, c.dynamoDb, c.s3Vectors, c.sqs.get)
    all.foreach { cl =>
      val o = cl.serviceClientConfiguration().overrideConfiguration()
      assertEquals(
        o.advancedOption(SdkAdvancedClientOption.DISABLE_HOST_PREFIX_INJECTION).toScala,
        Some(java.lang.Boolean.TRUE),
        cl.serviceName()
      )
      assertEquals(o.apiCallTimeout().toScala.map(_.toMillis), Some(5000L), cl.serviceName())
      assertEquals(
        cl.serviceClientConfiguration().endpointOverride().toScala.map(_.toString),
        Some("https://127.0.0.1:9297")
      )
    }
  }

  test(
    "production clients are unchanged: no endpoint override, no host-prefix option, virtual-host S3, default chain"
  ) {
    val http = new StubHttp()
    val cfg = TestFixtures.runtimeStorageConfig
    val p =
      AwsSdkClients.build(settings, cfg, Some(http), Some(AnonymousCredentialsProvider.create()))
    val rendered = List[SdkClient](p.s3, p.dynamoDb, p.s3Vectors, p.sqs.get, p.neptune.get).map {
      cl =>
        val sc = cl.serviceClientConfiguration()
        val o = sc.overrideConfiguration()
        List(
          cl.serviceName(),
          sc match {
            case a: software.amazon.awssdk.awscore.AwsServiceClientConfiguration => a.region().id()
            case _                                                               => "no-region"
          },
          sc.endpointOverride()
            .toScala
            .map(u => s"${u.getScheme}://<host>:${u.getPort}")
            .getOrElse("none"),
          o.apiCallTimeout().toScala.map(_.toMillis.toString).getOrElse("none"),
          o.advancedOption(SdkAdvancedClientOption.DISABLE_HOST_PREFIX_INJECTION)
            .toScala
            .map(_.toString)
            .getOrElse("none")
        ).mkString("|")
    }
    assertEquals(
      rendered,
      List(
        "s3|us-east-1|none|5000|false",
        "dynamodb|us-east-1|none|5000|false",
        "s3vectors|us-east-1|none|5000|false",
        "sqs|us-east-1|none|5000|false",
        s"neptune-db|us-east-1|https://<host>:${cfg.neptune.get.port}|5000|false"
      )
    )
    quietly(p.s3.headObject(HeadObjectRequest.builder().bucket("example-bucket").key("k").build()))
    val host = http.requests.head._1
    assert(host.startsWith("example-bucket.s3") && host.endsWith(".amazonaws.com"), host)
    assertEquals(http.requests.head._3, "/k", "production S3 keeps virtual-host addressing")
  }

  // ---- result lines and outcome classes ---------------------------------------------------

  test(
    "result lines: fixed field order, null status for an answered call, only allowlisted extra fields"
  ) {
    val l = EmulatorResultLine(
      "s3",
      "PutObject",
      EmulatorOutcome.ImplementedPassed,
      None,
      12L,
      ListMap("step" -> "s3-put")
    )
    assertEquals(
      l.render,
      """{"service":"s3","operation":"PutObject","class":"implemented-passed","httpStatus":null,"latencyMillis":12,"step":"s3-put"}"""
    )
    val n =
      EmulatorResultLine("sqs", "ReceiveMessage", EmulatorOutcome.NotImplemented, Some(501), 3L)
    assertEquals(
      n.render,
      """{"service":"sqs","operation":"ReceiveMessage","class":"not-implemented","httpStatus":501,"latencyMillis":3}"""
    )
    intercept[IllegalArgumentException](EmulatorResultLine("s3", "X", "passed", None, 0L))
    intercept[IllegalArgumentException](
      EmulatorResultLine("s3", "X", EmulatorOutcome.NotExercised, None, 0L, ListMap("host" -> "h"))
    )
    assertEquals(
      EmulatorResultLine.preflight("unset:AWS_PROFILE").render,
      """{"service":"preflight","operation":"preflight","class":"infrastructure-failure","httpStatus":null,"latencyMillis":0,"rule":"unset:AWS_PROFILE"}"""
    )
    assertEquals(EmulatorResultLine.safeWord("https://h/x?y"), "other")
    assertEquals(
      EmulatorOutcome.Classes,
      List(
        "implemented-passed",
        "implemented-failed",
        "not-implemented",
        "not-exercised",
        "infrastructure-failure"
      )
    )
  }

  test(
    "outcome classes: 501 is not-implemented, an answered error implemented-failed, no answer infrastructure-failure"
  ) {
    def s3Err(status: Int, code: String) =
      S3Exception
        .builder()
        .statusCode(status)
        .awsErrorDetails(
          software.amazon.awssdk.awscore.exception.AwsErrorDetails
            .builder()
            .errorCode(code)
            .errorMessage("arn:aws:s3:::secret-bucket")
            .build()
        )
        .build()
    assertEquals(
      EmulatorFailure.classify(s3Err(501, "NotImplemented")),
      (EmulatorOutcome.NotImplemented, Some(501), "NotImplemented")
    )
    assertEquals(
      EmulatorFailure.classify(s3Err(400, "NotImplemented"))._1,
      EmulatorOutcome.NotImplemented
    )
    assertEquals(
      EmulatorFailure.classify(s3Err(403, "AccessDenied")),
      (EmulatorOutcome.ImplementedFailed, Some(403), "AccessDenied")
    )
    val wrapped =
      new org.scholarchain.storage.StoreException("x", true, s3Err(501, "NotImplemented"))
    assertEquals(EmulatorFailure.classify(wrapped)._1, EmulatorOutcome.NotImplemented)
    val tls =
      SdkClientException.builder().cause(new javax.net.ssl.SSLHandshakeException("cert")).build()
    assertEquals(
      EmulatorFailure.classify(tls),
      (EmulatorOutcome.InfrastructureFailure, None, "tls")
    )
    val refused = SdkClientException.builder().cause(new ConnectException("refused")).build()
    assertEquals(
      EmulatorFailure.classify(refused),
      (EmulatorOutcome.InfrastructureFailure, None, "connection")
    )
    assertEquals(
      EmulatorFailure.classify(new AssertionError("x")),
      (EmulatorOutcome.ImplementedFailed, None, "AssertionError")
    )
  }

  test(
    "recorder and chain: a failed step makes the later steps not-exercised; failures fail, gaps do not"
  ) {
    val rec = new EmulatorRecorder(EmulatorResultWriter.none, "t")
    val chain = new EmulatorChain(rec)
    assertEquals(chain("s3", "PutObject")(1)(), Some(1))
    assertEquals(
      chain("s3", "GetObject")(throw S3Exception.builder().statusCode(501).build())(),
      None
    )
    assertEquals(chain("s3", "HeadObject")(3)(), None)
    assertEquals(
      rec.recorded.map(_.outcome),
      List("implemented-passed", "not-implemented", "not-exercised")
    )
    assertEquals(rec.recorded.last.extra.get("observation"), Some("needs s3.GetObject"))
    assertEquals(rec.failures, Nil, "not-implemented and not-exercised are gaps, not failures")
    assert(!rec.allPassed)
    rec.call("s3", "ListObjectsV2")(0)(_ > 0)
    assertEquals(rec.failures.map(_.extra.get("errorCode")), List(Some("unexpected-result")))
    assert(rec.recorded.forall(_.render.contains("\"step\":\"t\"")))
  }

  test(
    "writer: results-SUITE.jsonl only when a folder is set, created new, never appended to an old run"
  ) {
    val d = Files.createTempDirectory("emu-results")
    val w = EmulatorResultWriter.open(Some(d), "StorageEmu").fold(e => fail(e), identity)
    w.write(EmulatorResultLine("s3", "PutObject", EmulatorOutcome.ImplementedPassed, None, 1L))
    w.write(EmulatorResultLine("s3", "GetObject", EmulatorOutcome.NotExercised, None, 0L))
    w.demonstration(
      "demonstration re-embed: not demonstrated (s3vectors.PutVectors not-implemented)"
    )
    val lines = Files.readAllLines(d.resolve("results-StorageEmu.jsonl")).asScala.toList
    assertEquals(lines.size, 2)
    assert(
      lines.forall(l =>
        ujson.read(l).obj.keys.toList.take(5) == List(
          "service",
          "operation",
          "class",
          "httpStatus",
          "latencyMillis"
        )
      )
    )
    assertEquals(
      Files.readAllLines(d.resolve("demonstration-StorageEmu.txt")).asScala.toList,
      List("demonstration re-embed: not demonstrated (s3vectors.PutVectors not-implemented)")
    )
    assertEquals(
      EmulatorResultWriter.open(Some(d), "StorageEmu").left.toOption,
      Some("results-file-exists")
    )
    assertEquals(EmulatorResultWriter.open(None, "StorageEmu").map(_.path), Right(None))
    intercept[IllegalArgumentException](w.demonstration("demonstration re-embed: maybe"))
  }

  test(
    "preflight: a refused contract writes one preflight line (usable folder only) and builds no client"
  ) {
    val d = Files.createTempDirectory("emu-preflight")
    val env = good + ("AWS_PROFILE" -> "") + ("SCHOLARCHAIN_EMULATOR_RESULTS_DIR" -> d.toString)
    val rule = checkOf(env).left.toOption.getOrElse(fail("expected a refusal"))
    assertEquals(rule, "unset:AWS_PROFILE")
    assert(
      EmulatorResultWriter.writePreflight(EmulatorSafety.usableResultsDir(env), "StorageEmu", rule)
    )
    assertEquals(
      Files.readAllLines(d.resolve("results-StorageEmu.jsonl")).asScala.toList,
      List(
        """{"service":"preflight","operation":"preflight","class":"infrastructure-failure","httpStatus":null,"latencyMillis":0,"rule":"unset:AWS_PROFILE"}"""
      )
    )
    // a relative or missing folder: no line anywhere
    assertEquals(
      EmulatorSafety.usableResultsDir(good + ("SCHOLARCHAIN_EMULATOR_RESULTS_DIR" -> "rel")),
      None
    )
    assertEquals(
      EmulatorSafety.usableResultsDir(
        good + ("SCHOLARCHAIN_EMULATOR_RESULTS_DIR" -> d.resolve("absent").toString)
      ),
      None
    )
  }

  // ---- config/emulator.json ------------------------------------------------------------------

  test(
    "config/emulator.json loads through ConfigLoader into the storage configuration the emulator suites use"
  ) {
    val c = emuConfig
    assertEquals(c.region, "us-east-1")
    val names = c.zones.values.map(_.bucket).toSet ++ c.tableNames + c.vectorBucket
    assert(names.forall(_.startsWith("scholarchain-emu-")), names.toString)
    assertEquals(c.neptune, None, "no Neptune section")
    assert(c.runtime.nonEmpty)
    assertEquals(c.embedding.dimensions, 1024)
    // the environment over emulator clients builds with no request; the graph is not configured
    val http = new StubHttp()
    val env = AwsStorageEnvironment.create(c, emulatorClients(http), None)
    try {
      assertEquals(env.graph.left.toOption, Some("neptune section is not configured"))
      assert(env.runtimeStores.isLeft)
      assertEquals(env.graphReadReport, None)
      assertEquals(
        env.layout.textObject("researcher-profile-v1", "sha256:" + "a" * 64).bucket,
        "scholarchain-emu-data"
      )
    } finally env.close()
    assertEquals(http.requests.size, 0)
    assert(ObjectRef.parse("s3://scholarchain-emu-data/x").isRight)
  }

  // ---- L-10: masking ------------------------------------------------------------------------

  test("L-10: failure messages lose account IDs, ARNs, bucket names, hosts and IPs") {
    val raw = List(
      "AccessDenied for arn:aws:iam::123456789012:role/example on s3://example-data-bucket/key",
      "queue https://sqs.us-east-1.amazonaws.com/123456789012/example-queue failed",
      "host example.cluster-abc.us-east-1.neptune.amazonaws.com:8182 and 10.1.2.3",
      "configured bucket my-configured-bucket in message; account 210987654321."
    ).mkString(" | ")
    val m = FailureMasking.mask(raw, List("my-configured-bucket"))
    List(
      "123456789012",
      "210987654321",
      "arn:aws",
      "example-data-bucket",
      "sqs.us-east-1",
      "neptune.amazonaws.com",
      "10.1.2.3",
      "my-configured-bucket"
    )
      .foreach(s => assert(!m.contains(s), s"$s in $m"))
    assert(
      m.contains("<arn>") && m.contains("s3://<bucket>") && m.contains("https://<host>") && m
        .contains("<account>"),
      m
    )
  }

  test(
    "L-10: a MaskedFailures suite reports a failing test body with the masked message and no cause"
  ) {
    val suite = new MaskedFailures {
      override def maskedNames: Iterable[String] = List("my-configured-bucket")
    }
    val failing = new munit.Test(
      "t",
      () =>
        Future.failed(
          new RuntimeException(
            "wrapper",
            new IllegalStateException("bucket my-configured-bucket in 123456789012")
          )
        ),
      Set.empty,
      munit.Location.empty
    )
    val transformed = suite.munitTestTransforms.foldLeft(failing)((t, tr) => tr(t))
    val e = intercept[Throwable](Await.result(transformed.body(), 10.seconds))
    assert(e.isInstanceOf[org.scholarchain.storage.MaskedTestFailure], e.getClass.getName)
    assertEquals(e.getCause, null)
    assert(
      !e.getMessage.contains("123456789012") && !e.getMessage.contains("my-configured-bucket"),
      e.getMessage
    )
    assert(e.getMessage.contains("IllegalStateException"), e.getMessage)
  }
}
