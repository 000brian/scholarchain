package org.scholarchain.core.config

import scala.io.Source

import org.scholarchain.core.ContractError

/** Phase 10 optional section `service`: absent unless a key is set, then every required key is set,
  * no defaults. Values in the frozen example are test placeholders, not deployment configuration;
  * the API-key fields are variable NAMES and no key value appears anywhere.
  */
class ServiceConfigSuite extends munit.FunSuite {

  val BaseFile = "valid-v1.json"
  val ServiceFile = "valid-v1-service.json"

  def resource(name: String): String = {
    val src = Source.fromInputStream(getClass.getResourceAsStream(s"/config/$name"), "UTF-8")
    try src.mkString
    finally src.close()
  }

  lazy val baseText: String = resource(BaseFile)
  lazy val serviceText: String = resource(ServiceFile)

  def edit(f: ujson.Value => Unit): String = {
    val tree = ujson.read(serviceText)
    f(tree)
    tree.render()
  }

  def editService(key: String, value: ujson.Value): String = edit(_("service")(key) = value)

  def load(text: String = serviceText, env: Map[String, String] = Map.empty) =
    ConfigLoader.load(text, ServiceFile, env)

  def loaded(text: String = serviceText, env: Map[String, String] = Map.empty): LoadedConfig =
    load(text, env).fold(e => fail(e.message), identity)

  def assertError(result: Either[ContractError, LoadedConfig], fragment: String)(implicit
      loc: munit.Location
  ): Unit =
    assert(
      result.left.exists(_.messages.exists(_.contains(fragment))),
      s"expected an error containing '$fragment', got $result"
    )

  val Expected: ServiceSettings = ServiceSettings(
    port = 8080,
    apiKeyEnvVar = "SCHOLARCHAIN_EXAMPLE_API_KEY",
    readOnlyApiKeyEnvVar = "SCHOLARCHAIN_EXAMPLE_READ_ONLY_API_KEY",
    maxRequestBytes = 65536,
    syncDeadlineMillis = 20000,
    maxConcurrentQueries = 8,
    workerConcurrency = 4,
    jobMaxAttempts = 3,
    jobLeaseMillis = 120000,
    jobTtlSeconds = 86400,
    circuitFailureThreshold = 5,
    circuitOpenMillis = 30000,
    requestsPerMinute = 120,
    jobQueueUrl = Some("https://queue.example.invalid/example-jobs")
  )

  test("without service the config is None and serializes and fingerprints as before") {
    val c = ConfigLoader.load(baseText, BaseFile, Map.empty).fold(e => fail(e.message), identity)
    assertEquals(c.config.service, None)
    val keys = ujson.read(upickle.default.write(c.config)).obj.keys.toList
    assert(!keys.contains("service"), keys)
    assertEquals(c.config.fingerprint, c.config.copy(service = None).fingerprint)
    assert(!c.origins.keys.exists(_.startsWith("service.")), c.origins.keys)
  }

  test("the frozen example loads every service value from the file") {
    val c = loaded()
    assertEquals(c.config.service, Some(Expected))
    val paths = ConfigLoader.allKeys.map(_.path).filter(_.startsWith("service."))
    assertEquals(paths.size, 14)
    paths.foreach(p => assertEquals(c.origins.get(p), Some(ConfigOrigin.File(ServiceFile)), p))
    // The other optional sections stay absent.
    val cfg = c.config
    assertEquals(
      List(cfg.objectStore, cfg.dynamoDb, cfg.neptune, cfg.vectors, cfg.embedding, cfg.llm)
        .forall(_.isEmpty) && cfg.runtime.isEmpty,
      true
    )
  }

  test("the service section serializes under 'service' and round-trips") {
    val c = loaded().config
    val json = ujson.read(upickle.default.write(c))
    assertEquals(json("service")("port").num, 8080.0)
    assertEquals(json("service")("apiKeyEnvVar").str, "SCHOLARCHAIN_EXAMPLE_API_KEY")
    assertEquals(upickle.default.read[ScholarChainConfig](upickle.default.write(c)), c)
    assertEquals(upickle.default.read[ServiceSettings](upickle.default.write(Expected)), Expected)
    val base = ConfigLoader.load(baseText, BaseFile, Map.empty).fold(e => fail(e.message), identity)
    assertNotEquals(c.fingerprint, base.config.fingerprint)
  }

  test("jobQueueUrl is optional: absent resolves to None and does not fail") {
    val c = loaded(edit(_("service").obj.remove("jobQueueUrl")))
    assertEquals(c.config.service, Some(Expected.copy(jobQueueUrl = None)))
    assert(!c.origins.contains("service.jobQueueUrl"))
  }

  test("a partially configured service section reports every missing required key") {
    val result = load(edit { j =>
      j("service").obj.remove("port")
      j("service").obj.remove("jobTtlSeconds")
    })
    assertError(
      result,
      "service.port: required because section service is configured; set it in " +
        "valid-v1-service.json or in SCHOLARCHAIN_CFG_SERVICE_PORT"
    )
    assertError(result, "service.jobTtlSeconds: required because section service is configured")
    assertEquals(result.left.map(_.messages.size), Left(2))
    // One optional key alone activates the section: all 13 required keys are reported.
    val onlyUrl = ConfigLoader.load(
      baseText,
      BaseFile,
      Map("SCHOLARCHAIN_CFG_SERVICE_JOB_QUEUE_URL" -> "https://queue.example.invalid/q")
    )
    assertEquals(onlyUrl.left.map(_.messages.size), Left(13))
    val onlyPort =
      ConfigLoader.load(baseText, BaseFile, Map("SCHOLARCHAIN_CFG_SERVICE_PORT" -> "8080"))
    assertError(onlyPort, "service.apiKeyEnvVar: required because section service is configured")
    assertEquals(onlyPort.left.map(_.messages.size), Left(12))
  }

  test("service keys come from the environment with precedence over the file") {
    val c = loaded(env =
      Map(
        "SCHOLARCHAIN_CFG_SERVICE_PORT" -> "9090",
        "SCHOLARCHAIN_CFG_SERVICE_READ_ONLY_API_KEY_ENV_VAR" -> "SCHOLARCHAIN_EXAMPLE_RO_KEY",
        "SCHOLARCHAIN_CFG_SERVICE_JOB_QUEUE_URL" -> "https://queue.example.invalid/other"
      )
    )
    assertEquals(
      c.config.service,
      Some(
        Expected.copy(
          port = 9090,
          readOnlyApiKeyEnvVar = "SCHOLARCHAIN_EXAMPLE_RO_KEY",
          jobQueueUrl = Some("https://queue.example.invalid/other")
        )
      )
    )
    assertEquals(
      c.origins("service.port"),
      ConfigOrigin.Environment("SCHOLARCHAIN_CFG_SERVICE_PORT")
    )
    assertEquals(c.origins("service.maxRequestBytes"), ConfigOrigin.File(ServiceFile))
  }

  test("the whole service section can come from the environment alone") {
    val env = Map(
      "port" -> "8080",
      "apiKeyEnvVar" -> "SCHOLARCHAIN_EXAMPLE_API_KEY",
      "readOnlyApiKeyEnvVar" -> "SCHOLARCHAIN_EXAMPLE_READ_ONLY_API_KEY",
      "maxRequestBytes" -> "65536",
      "syncDeadlineMillis" -> "20000",
      "maxConcurrentQueries" -> "8",
      "workerConcurrency" -> "4",
      "jobMaxAttempts" -> "3",
      "jobLeaseMillis" -> "120000",
      "jobTtlSeconds" -> "86400",
      "circuitFailureThreshold" -> "5",
      "circuitOpenMillis" -> "30000",
      "requestsPerMinute" -> "120"
    ).map { case (k, v) => ConfigKey.envVarFor(s"service.$k") -> v }
    val c = ConfigLoader.load(baseText, BaseFile, env).fold(e => fail(e.message), identity)
    assertEquals(c.config.service, Some(Expected.copy(jobQueueUrl = None)))
  }

  test("the loader stores only the API-key variable NAMES and never reads their values") {
    val placeholderFull = "placeholder-full-access-not-a-key"
    val placeholderRo = "placeholder-read-only-not-a-key"
    val withValues = loaded(env =
      Map(
        "SCHOLARCHAIN_EXAMPLE_API_KEY" -> placeholderFull,
        "SCHOLARCHAIN_EXAMPLE_READ_ONLY_API_KEY" -> placeholderRo
      )
    )
    val withoutValues = loaded()
    // The value of the named variables changes nothing: same config, same fingerprint.
    assertEquals(withValues.config, withoutValues.config)
    assertEquals(withValues.config.fingerprint, withoutValues.config.fingerprint)
    val json = upickle.default.write(withValues.config)
    assert(!json.contains(placeholderFull) && !json.contains(placeholderRo), json)
    assert(!withValues.origins.toString.contains("placeholder"), withValues.origins)
    assertEquals(withValues.config.service.map(_.apiKeyEnvVar), Some(Expected.apiKeyEnvVar))
    // An empty value of a named variable is not the loader's concern either.
    loaded(env = Map("SCHOLARCHAIN_EXAMPLE_API_KEY" -> ""))
  }

  test("API-key variable names follow the llm.apiKeyEnvVar rules and differ from each other") {
    assertError(
      load(editService("apiKeyEnvVar", "service_key")),
      "service.apiKeyEnvVar: must match [A-Z][A-Z0-9_]*"
    )
    assertError(
      load(editService("apiKeyEnvVar", "SCHOLARCHAIN_CFG_SERVICE_KEY")),
      "service.apiKeyEnvVar: must not start with SCHOLARCHAIN_CFG_"
    )
    assertError(
      load(editService("readOnlyApiKeyEnvVar", "1READ_ONLY")),
      "service.readOnlyApiKeyEnvVar: must match [A-Z][A-Z0-9_]*"
    )
    assertError(
      load(editService("readOnlyApiKeyEnvVar", "SCHOLARCHAIN_CFG_RO")),
      "service.readOnlyApiKeyEnvVar: must not start with SCHOLARCHAIN_CFG_"
    )
    assertError(
      load(editService("readOnlyApiKeyEnvVar", "SCHOLARCHAIN_EXAMPLE_API_KEY")),
      "service.readOnlyApiKeyEnvVar: must differ from service.apiKeyEnvVar"
    )
    assertError(load(editService("apiKeyEnvVar", " ")), "service.apiKeyEnvVar: must not be blank")
  }

  test("each numeric service rule rejects out-of-range values and accepts its boundaries") {
    def bad(key: String, value: ujson.Value, fragment: String)(implicit
        loc: munit.Location
    ): Unit = assertError(load(editService(key, value)), fragment)
    bad("port", 0, "service.port: 0 is outside 1..65535")
    bad("port", 65536, "service.port: 65536 is outside 1..65535")
    bad("maxRequestBytes", 1023, "service.maxRequestBytes: 1023 is outside 1024..1048576")
    bad("maxRequestBytes", 1048577, "service.maxRequestBytes: 1048577 is outside 1024..1048576")
    bad("syncDeadlineMillis", 0, "service.syncDeadlineMillis: 0 is outside 1..120000")
    bad("maxConcurrentQueries", 0, "service.maxConcurrentQueries: 0 is outside 1..64")
    bad("maxConcurrentQueries", 65, "service.maxConcurrentQueries: 65 is outside 1..64")
    bad("workerConcurrency", 0, "service.workerConcurrency: 0 is outside 1..64")
    bad("workerConcurrency", 65, "service.workerConcurrency: 65 is outside 1..64")
    bad("jobMaxAttempts", 0, "service.jobMaxAttempts: 0 is outside 1..5")
    bad("jobMaxAttempts", 6, "service.jobMaxAttempts: 6 is outside 1..5")
    bad("jobLeaseMillis", 9999, "service.jobLeaseMillis: 9999 is outside 10000..900000")
    bad("jobLeaseMillis", 900001, "service.jobLeaseMillis: 900001 is outside 10000..900000")
    bad("jobTtlSeconds", 3599, "service.jobTtlSeconds: 3599 is outside 3600..604800")
    bad("jobTtlSeconds", 604801, "service.jobTtlSeconds: 604801 is outside 3600..604800")
    bad("circuitFailureThreshold", 0, "service.circuitFailureThreshold: 0 is outside 1..100")
    bad("circuitFailureThreshold", 101, "service.circuitFailureThreshold: 101 is outside 1..100")
    bad("circuitOpenMillis", 999, "service.circuitOpenMillis: 999 is outside 1000..600000")
    bad("circuitOpenMillis", 600001, "service.circuitOpenMillis: 600001 is outside 1000..600000")
    bad("requestsPerMinute", 0, "service.requestsPerMinute: 0 must be > 0")
    bad("requestsPerMinute", -1, "service.requestsPerMinute: -1 must be > 0")
    bad("port", "8080", "service.port: expected an integer")
    bad("jobQueueUrl", 1, "service.jobQueueUrl: expected a string")
    bad("unknownKey", 1, "service.unknownKey: unknown configuration key")
    // syncDeadlineMillis above 120000 is rejected by its own range rule (lease raised so the
    // cross-key rule stays satisfied).
    assertError(
      load(edit { j =>
        j("service")("syncDeadlineMillis") = 120001
        j("service")("jobLeaseMillis") = 200000
      }),
      "service.syncDeadlineMillis: 120001 is outside 1..120000"
    )
    // Boundaries are accepted.
    List(
      "port" -> 1,
      "port" -> 65535,
      "maxRequestBytes" -> 1024,
      "maxRequestBytes" -> 1048576,
      "syncDeadlineMillis" -> 1,
      "maxConcurrentQueries" -> 1,
      "maxConcurrentQueries" -> 64,
      "workerConcurrency" -> 1,
      "workerConcurrency" -> 64,
      "jobMaxAttempts" -> 1,
      "jobMaxAttempts" -> 5,
      "jobLeaseMillis" -> 900000,
      "jobTtlSeconds" -> 604800,
      "circuitFailureThreshold" -> 1,
      "circuitFailureThreshold" -> 100,
      "circuitOpenMillis" -> 1000,
      "circuitOpenMillis" -> 600000,
      "requestsPerMinute" -> 1
    ).foreach { case (k, v) => loaded(editService(k, v)) }
    loaded(edit { j =>
      j("service")("syncDeadlineMillis") = 120000
      j("service")("jobLeaseMillis") = 120001
    })
    loaded(edit { j =>
      j("service")("syncDeadlineMillis") = 9999
      j("service")("jobLeaseMillis") = 10000
    })
  }

  test("cross-key rule: jobLeaseMillis must be greater than syncDeadlineMillis") {
    val equal = load(edit { j =>
      j("service")("syncDeadlineMillis") = 60000
      j("service")("jobLeaseMillis") = 60000
    })
    assertError(
      equal,
      "service.jobLeaseMillis: 60000 must be greater than syncDeadlineMillis 60000"
    )
    assertEquals(equal.left.map(_.messages.size), Left(1))
    val below = load(edit { j =>
      j("service")("syncDeadlineMillis") = 100000
      j("service")("jobLeaseMillis") = 90000
    })
    assertError(below, "service.jobLeaseMillis: 90000 must be greater than syncDeadlineMillis")
    // One millisecond above is accepted.
    loaded(edit { j =>
      j("service")("syncDeadlineMillis") = 60000
      j("service")("jobLeaseMillis") = 60001
    })
  }

  test("cross-key rule: jobTtlSeconds x 1000 must be greater than jobLeaseMillis") {
    // Both values are inside their own ranges (lease up to 900000 ms = 900 s, TTL >= 3600 s), so
    // only an equal-or-larger lease can break the rule; the rule is still checked when a range
    // rule already failed.
    val equal = load(edit { j =>
      j("service")("jobTtlSeconds") = 3600
      j("service")("jobLeaseMillis") = 3600000
    })
    assertError(
      equal,
      "service.jobTtlSeconds: 3600 s must be longer than jobLeaseMillis 3600000 ms"
    )
    assertError(equal, "service.jobLeaseMillis: 3600000 is outside 10000..900000")
    val above = load(edit { j =>
      j("service")("jobTtlSeconds") = 3600
      j("service")("jobLeaseMillis") = Int.MaxValue
    })
    assertError(above, "service.jobTtlSeconds: 3600 s must be longer than jobLeaseMillis")
    // No integer overflow: the largest Int TTL is compared as a long.
    val bigTtl = load(edit(_("service")("jobTtlSeconds") = Int.MaxValue))
    assertError(bigTtl, s"service.jobTtlSeconds: ${Int.MaxValue} is outside 3600..604800")
    assert(
      !bigTtl.left.exists(_.messages.exists(_.contains("must be longer than"))),
      bigTtl
    )
    // Smallest TTL with the largest lease is accepted (3600000 > 900000).
    loaded(edit { j =>
      j("service")("jobTtlSeconds") = 3600
      j("service")("jobLeaseMillis") = 900000
    })
  }

  test("jobQueueUrl must be an https:// URL with a host") {
    List(
      "http://queue.example.invalid/jobs",
      "queue.example.invalid/jobs",
      "https://",
      "https:///jobs",
      "https://queue.example.invalid/my jobs",
      "https://user:pass@queue.example.invalid/jobs",
      "HTTPS://queue.example.invalid/jobs",
      "ftp://queue.example.invalid/jobs"
    ).foreach { u =>
      assertError(
        load(editService("jobQueueUrl", u)),
        "service.jobQueueUrl: must be an https:// URL with a host"
      )
    }
    List(
      "https://queue.example.invalid",
      "https://queue.example.invalid:8443/a/b",
      "https://sqs.us-east-1.example.invalid/000000000000/example-jobs"
    ).foreach(u => loaded(editService("jobQueueUrl", u)))
  }

  test("service environment variable names follow the rule") {
    assertEquals(ConfigKey.envVarFor("service.port"), "SCHOLARCHAIN_CFG_SERVICE_PORT")
    assertEquals(
      ConfigKey.envVarFor("service.readOnlyApiKeyEnvVar"),
      "SCHOLARCHAIN_CFG_SERVICE_READ_ONLY_API_KEY_ENV_VAR"
    )
    assertEquals(
      ConfigKey.envVarFor("service.jobTtlSeconds"),
      "SCHOLARCHAIN_CFG_SERVICE_JOB_TTL_SECONDS"
    )
    assertEquals(
      ConfigKey.envVarFor("service.jobQueueUrl"),
      "SCHOLARCHAIN_CFG_SERVICE_JOB_QUEUE_URL"
    )
    val names = ConfigLoader.allKeys.map(_.envVar)
    assertEquals(names.distinct.size, names.size)
    assertError(
      load(env = Map("SCHOLARCHAIN_CFG_SERVICE_API_KEY" -> "x")),
      "SCHOLARCHAIN_CFG_SERVICE_API_KEY: unknown configuration variable"
    )
  }
}
