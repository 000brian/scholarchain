package org.scholarchain.core.config

import java.nio.file.Paths

import scala.io.Source

import org.scholarchain.core.{ContractError, SemanticDocumentType, YearWindow}

/** Configuration contract: versioned file plus environment overrides, no defaults. Values in the
  * fixture file are test placeholders, not deployment configuration.
  */
class ConfigLoaderSuite extends munit.FunSuite {

  val FileName = "valid-v1.json"

  lazy val fileText: String = {
    val src = Source.fromInputStream(getClass.getResourceAsStream(s"/config/$FileName"), "UTF-8")
    try src.mkString
    finally src.close()
  }

  def edit(f: ujson.Value => Unit): String = {
    val tree = ujson.read(fileText)
    f(tree)
    tree.render()
  }

  def load(text: String = fileText, env: Map[String, String] = Map.empty) =
    ConfigLoader.load(text, FileName, env)

  def loaded(text: String = fileText, env: Map[String, String] = Map.empty): LoadedConfig =
    load(text, env).fold(e => fail(e.message), identity)

  def assertError(result: Either[ContractError, LoadedConfig], fragment: String)(implicit
      loc: munit.Location
  ): Unit =
    assert(
      result.left.exists(_.messages.exists(_.contains(fragment))),
      s"expected an error containing '$fragment', got $result"
    )

  test("a complete versioned file loads; every value comes from the file") {
    val c = loaded()
    assertEquals(c.config.configVersion, 1)
    assertEquals(c.config.storage.rawBucket, "example-raw-bucket")
    assertEquals(c.config.entityResolution.matchThreshold, 0.9)
    assertEquals(
      c.config.graph.windows.map(_.window),
      List(YearWindow(2015, 2019), YearWindow(2020, 2025), YearWindow(2015, 2025))
    )
    assertEquals(c.config.bridge.sampleSize, 100)
    assertEquals(c.origins.keySet, ConfigLoader.keys.map(_.path).toSet)
    assert(c.origins.values.forall(_ == ConfigOrigin.File(FileName)))
  }

  test("environment variables take precedence over the file") {
    val env = Map(
      "SCHOLARCHAIN_CFG_STORAGE_RAW_BUCKET" -> "override-raw-bucket",
      "SCHOLARCHAIN_CFG_ENTITY_RESOLUTION_MATCH_THRESHOLD" -> "0.95",
      "SCHOLARCHAIN_CFG_BRIDGE_TOP_K" -> "25",
      "SCHOLARCHAIN_CFG_GRAPH_WINDOWS" -> """[{"label":"all","startYear":2015,"endYear":2025}]"""
    )
    val c = loaded(env = env)
    assertEquals(c.config.storage.rawBucket, "override-raw-bucket")
    assertEquals(c.config.entityResolution.matchThreshold, 0.95)
    assertEquals(c.config.bridge.topK, 25)
    assertEquals(c.config.graph.windows, List(NamedWindow("all", 2015, 2025)))
    assertEquals(
      c.origins("storage.rawBucket"),
      ConfigOrigin.Environment("SCHOLARCHAIN_CFG_STORAGE_RAW_BUCKET")
    )
    assertEquals(c.origins("storage.curatedBucket"), ConfigOrigin.File(FileName))
  }

  test("a required key missing from file and environment fails loudly (no default)") {
    val text = edit(_("storage").obj.remove("rawBucket"))
    assertError(
      load(text),
      "storage.rawBucket: required; set it in valid-v1.json or in SCHOLARCHAIN_CFG_STORAGE_RAW_BUCKET"
    )
  }

  test("a key missing from the file can be supplied by the environment") {
    val text = edit(_("bridge").obj.remove("randomSeed"))
    assertEquals(
      loaded(text, Map("SCHOLARCHAIN_CFG_BRIDGE_RANDOM_SEED" -> "11")).config.bridge.randomSeed,
      11
    )
  }

  test("every missing key is reported at once") {
    val result = load("""{"configVersion": 1}""")
    assertEquals(result.left.map(_.messages.size), Left(ConfigLoader.keys.size))
  }

  test("configVersion is required, from the file only, and must be supported") {
    assertError(load(edit(_.obj.remove("configVersion"))), "configVersion: required")
    assertError(load(edit(_("configVersion") = 2)), "configVersion: unsupported value 2")
    assertError(
      load(env = Map("SCHOLARCHAIN_CFG_CONFIG_VERSION" -> "1")),
      "SCHOLARCHAIN_CFG_CONFIG_VERSION: unknown configuration variable"
    )
  }

  test("unknown file keys and unknown SCHOLARCHAIN_CFG_ variables are rejected") {
    assertError(
      load(edit(_("storage")("rawBuckett") = "x")),
      "storage.rawBuckett: unknown configuration key"
    )
    assertError(
      load(env = Map("SCHOLARCHAIN_CFG_STORAGE_RAW_BUCKETT" -> "x")),
      "SCHOLARCHAIN_CFG_STORAGE_RAW_BUCKETT: unknown configuration variable"
    )
  }

  test("unrelated environment variables are ignored") {
    assert(load(env = Map("SCHOLARCHAIN_OPENALEX_API_KEY" -> "ignored", "PATH" -> "/bin")).isRight)
  }

  test("empty values and wrong types are rejected") {
    assertError(
      load(env = Map("SCHOLARCHAIN_CFG_AWS_REGION" -> " ")),
      "SCHOLARCHAIN_CFG_AWS_REGION: set but empty"
    )
    assertError(load(edit(_("aws")("region") = "")), "aws.region: must not be blank")
    assertError(load(edit(_("bridge")("topK") = "10")), "bridge.topK: expected an integer")
    assertError(load(edit(_("bridge")("topK") = 1.5)), "bridge.topK: 1.5 is not a 32-bit integer")
    assertError(load(env = Map("SCHOLARCHAIN_CFG_BRIDGE_TOP_K" -> "ten")), "is not a JSON literal")
    assertError(load(edit(_("graph")("windows") = "2015-2025")), "graph.windows: expected an array")
    assertError(load("[1]"), "top level must be a JSON object")
    assertError(load("{"), "not valid JSON")
  }

  test("thresholds must be confidences with review <= match") {
    assertError(
      load(edit(_("entityResolution")("matchThreshold") = 1.5)),
      "entityResolution.matchThreshold: confidence 1.5"
    )
    assertError(
      load(edit(_("entityResolution")("reviewThreshold") = 0.95)),
      "entityResolution.reviewThreshold: 0.95 exceeds matchThreshold 0.9"
    )
  }

  test("graph windows whose start year exceeds the end year are rejected") {
    val text = edit(_("graph")("windows") =
      ujson.Arr(ujson.Obj("label" -> "bad", "startYear" -> 2025, "endYear" -> 2015))
    )
    assertError(load(text), "graph.windows[0]: startYear 2025 exceeds endYear 2015")
  }

  test("graph windows must be non-empty with distinct labels") {
    assertError(load(edit(_("graph")("windows") = ujson.Arr())), "graph.windows: must not be empty")
    val w = ujson.Obj("label" -> "a", "startYear" -> 2015, "endYear" -> 2016)
    assertError(load(edit(_("graph")("windows") = ujson.Arr(w, w))), "labels must be distinct")
  }

  test("storage, AWS, and bridge values are checked") {
    assertError(
      load(edit(_("storage")("rawBucket") = "Bad_Bucket")),
      "storage.rawBucket: 'Bad_Bucket'"
    )
    assertError(
      load(edit(_("storage")("neptuneEndpoint") = "https://h:8182")),
      "storage.neptuneEndpoint"
    )
    assertError(load(edit(_("aws")("accountId") = "1234")), "aws.accountId: must be 12 digits")
    assertError(load(edit(_("aws")("region") = "useast1")), "aws.region")
    assertError(load(edit(_("bridge")("sampleSize") = 0)), "bridge.sampleSize: 0 must be > 0")
    assertError(load(edit(_("graph")("minEdgeWeight") = -1)), "graph.minEdgeWeight")
  }

  test("environment variable names follow SCHOLARCHAIN_CFG_<UPPER_SNAKE path> and are unique") {
    assertEquals(
      ConfigKey.envVarFor("entityResolution.matchThreshold"),
      "SCHOLARCHAIN_CFG_ENTITY_RESOLUTION_MATCH_THRESHOLD"
    )
    assertEquals(
      ConfigKey.envVarFor("storage.dynamoDbTable"),
      "SCHOLARCHAIN_CFG_STORAGE_DYNAMO_DB_TABLE"
    )
    val names = ConfigLoader.keys.map(_.envVar)
    assertEquals(names.distinct.size, names.size)
  }

  test("loadFromEnvironment requires SCHOLARCHAIN_CONFIG_FILE and reads that file") {
    assertEquals(
      ConfigLoader.loadFromEnvironment(Map.empty).left.map(_.messages),
      Left(List("SCHOLARCHAIN_CONFIG_FILE: required, names the configuration file"))
    )
    val path = Paths.get(getClass.getResource(s"/config/$FileName").toURI).toString
    val c = ConfigLoader
      .loadFromEnvironment(
        Map("SCHOLARCHAIN_CONFIG_FILE" -> path, "SCHOLARCHAIN_CFG_ENVIRONMENT" -> "dev")
      )
      .fold(e => fail(e.message), identity)
    assertEquals(c.config.environment, "dev")
    assertEquals(c.origins("storage.rawBucket"), ConfigOrigin.File(path))
    assert(
      ConfigLoader
        .loadFromEnvironment(Map("SCHOLARCHAIN_CONFIG_FILE" -> "/nonexistent/x.json"))
        .isLeft
    )
  }

  test("dataset manifest S3 templates resolve bucket placeholders from configuration") {
    val storage = loaded().config.storage
    assertEquals(
      storage.resolveTemplate("s3://{raw_bucket}/raw/dblp/release=2026-11-01/"),
      Right("s3://example-raw-bucket/raw/dblp/release=2026-11-01/")
    )
    assertEquals(
      storage.resolveTemplate("s3://{raw_bucket}/raw/openalex/retrieved={yyyy-mm-dd}/"),
      Right("s3://example-raw-bucket/raw/openalex/retrieved={yyyy-mm-dd}/")
    )
    assert(storage.resolveTemplate("s3://{audit_bucket}/x").isLeft)
  }

  test("the configuration fingerprint is deterministic and value-sensitive") {
    val a = loaded().config
    assertEquals(a.fingerprint, loaded().config.fingerprint)
    assert(a.fingerprint.startsWith("sha256:"))
    assertNotEquals(
      a.fingerprint,
      loaded(env = Map("SCHOLARCHAIN_CFG_BRIDGE_TOP_K" -> "11")).config.fingerprint
    )
  }

  // ---- Phase 7 optional sections ---------------------------------------------------------

  val SectionsFile = "valid-v1-sections.json"

  lazy val sectionsText: String = {
    val src =
      Source.fromInputStream(getClass.getResourceAsStream(s"/config/$SectionsFile"), "UTF-8")
    try src.mkString
    finally src.close()
  }

  def editSections(f: ujson.Value => Unit): String = {
    val tree = ujson.read(sectionsText)
    f(tree)
    tree.render()
  }

  def loadSections(text: String = sectionsText, env: Map[String, String] = Map.empty) =
    ConfigLoader.load(text, SectionsFile, env)

  def loadedSections(text: String = sectionsText, env: Map[String, String] = Map.empty) =
    loadSections(text, env).fold(e => fail(e.message), identity)

  test("a file without the Phase 7 sections loads them as None and serializes as before") {
    val c = loaded().config
    assertEquals(
      (c.objectStore, c.dynamoDb, c.neptune, c.vectors, c.embedding),
      (None, None, None, None, None)
    )
    assertEquals(c.s3Zones, None)
    // The fingerprint input is unchanged: no new key appears when the sections are absent.
    assertEquals(
      ujson.read(upickle.default.write(c)).obj.keys.toList,
      List(
        "configVersion",
        "environment",
        "aws",
        "storage",
        "entityResolution",
        "models",
        "graph",
        "bridge"
      )
    )
  }

  test("a file with every Phase 7 section loads every value from the file") {
    val c = loadedSections()
    val cfg = c.config
    assertEquals(
      cfg.s3Zones.map(_.toList),
      Some(
        List(
          "raw" -> S3Location("example-raw-bucket", "raw"),
          "curated" -> S3Location("example-curated-bucket", "curated"),
          "graph" -> S3Location("example-graph-bucket", "graph"),
          "semantic-context" -> S3Location("example-artifact-bucket", "semantic-context"),
          "reports" -> S3Location("example-artifact-bucket", "reports"),
          "logs" -> S3Location("example-artifact-bucket", "logs")
        )
      )
    )
    assertEquals(
      cfg.s3Zones.get("semantic-context").uri,
      "s3://example-artifact-bucket/semantic-context/"
    )
    assertEquals(cfg.dynamoDb.map(_.runLedgerTable), Some("example-run-ledger"))
    assertEquals(
      cfg.dynamoDb.map(_.researcherMetricCacheTable),
      Some("example-researcher-metric-cache")
    )
    assertEquals(cfg.neptune, Some(NeptuneSettings(8182, 30000, 1000)))
    val v = cfg.vectors.get
    assertEquals(
      v.indexNames,
      List(
        "researcher-profile-v1",
        "collaboration-community-v1",
        "collaboration-path-v1",
        "paper-abstract-v1",
        "topic-institution-summary-v1"
      )
    )
    assertEquals(v.indexFor(SemanticDocumentType.CommunitySummary), "collaboration-community-v1")
    assertEquals(v.indexFor(SemanticDocumentType.PaperSummary), "paper-abstract-v1")
    assertEquals((v.putBatchSize, v.maxRetries, v.queryTopK), (500, 5, 30))
    val e = cfg.embedding.get
    assertEquals(e.providerKind, Right(EmbeddingProvider.Voyage))
    assertEquals(e.dimensions, 1024)
    assertEquals(e.apiKeyEnvVar, Some("SCHOLARCHAIN_VOYAGE_API_KEY"))
    assertEquals(cfg.models.embeddingModelId, "example-embedding-model")
    assertEquals(cfg.storage.vectorBucket, "example-vector-bucket")
    assertEquals(cfg.storage.neptuneEndpoint, "neptune.example.invalid")
    assertEquals(c.origins.keySet, ConfigLoader.allKeys.map(_.path).toSet)
    assert(c.origins.values.forall(_ == ConfigOrigin.File(SectionsFile)))
    assertNotEquals(cfg.fingerprint, loaded().config.fingerprint)
  }

  test("the config stores the NAME of the API-key variable; the loader never reads its value") {
    val c = loadedSections(env = Map("SCHOLARCHAIN_VOYAGE_API_KEY" -> "placeholder-not-a-key"))
    assertEquals(c.config.embedding.flatMap(_.apiKeyEnvVar), Some("SCHOLARCHAIN_VOYAGE_API_KEY"))
    assert(!upickle.default.write(c.config).contains("placeholder-not-a-key"))
  }

  test("a partially configured section reports every missing required key (no defaults)") {
    val text = editSections { j =>
      j("neptune").obj.remove("port")
      j("neptune").obj.remove("mergeRowCap")
    }
    val result = loadSections(text)
    assertError(
      result,
      "neptune.port: required because section neptune is configured; set it in " +
        "valid-v1-sections.json or in SCHOLARCHAIN_CFG_NEPTUNE_PORT"
    )
    assertError(result, "neptune.mergeRowCap: required because section neptune is configured")
  }

  test("one section key in the environment activates the section and requires the rest") {
    val result = load(env = Map("SCHOLARCHAIN_CFG_VECTORS_PUT_BATCH_SIZE" -> "100"))
    assertError(result, "vectors.researcherProfileIndex: required because section vectors")
    assertEquals(result.left.map(_.messages.size), Left(9))
  }

  test("section keys can come from the environment, which takes precedence over the file") {
    val c = loadedSections(env =
      Map(
        "SCHOLARCHAIN_CFG_EMBEDDING_PROVIDER" -> "fake",
        "SCHOLARCHAIN_CFG_VECTORS_QUERY_TOP_K" -> "12",
        "SCHOLARCHAIN_CFG_NEPTUNE_PORT" -> "8183"
      )
    )
    assertEquals(c.config.embedding.flatMap(_.providerKind.toOption), Some(EmbeddingProvider.Fake))
    assertEquals(c.config.vectors.map(_.queryTopK), Some(12))
    assertEquals(c.config.neptune.map(_.port), Some(8183))
    assertEquals(
      c.origins("vectors.queryTopK"),
      ConfigOrigin.Environment("SCHOLARCHAIN_CFG_VECTORS_QUERY_TOP_K")
    )
  }

  test("embedding.apiKeyEnvVar is optional except for provider voyage") {
    val noKey = editSections(_("embedding").obj.remove("apiKeyEnvVar"))
    assertError(loadSections(noKey), "embedding.apiKeyEnvVar: required for provider voyage")
    List("fake", "bedrock").foreach { p =>
      val c = loadedSections(noKey, Map("SCHOLARCHAIN_CFG_EMBEDDING_PROVIDER" -> p))
      assertEquals(c.config.embedding.flatMap(_.apiKeyEnvVar), None, p)
      assert(!c.origins.contains("embedding.apiKeyEnvVar"), p)
    }
  }

  test("embedding values are checked") {
    assertError(
      loadSections(editSections(_("embedding")("provider") = "openai")),
      "embedding.provider: unknown embedding provider 'openai'"
    )
    assertError(
      loadSections(editSections(_("embedding")("dimensions") = 5000)),
      "embedding.dimensions: 5000 is outside 1..4096"
    )
    assertError(
      loadSections(editSections(_("embedding")("apiKeyEnvVar") = "voyage_key")),
      "embedding.apiKeyEnvVar: 'voyage_key' must match"
    )
    assertError(
      loadSections(editSections(_("embedding")("apiKeyEnvVar") = "SCHOLARCHAIN_CFG_KEY")),
      "must not start with SCHOLARCHAIN_CFG_"
    )
    assertError(
      loadSections(editSections(_("embedding")("batchSize") = 0)),
      "embedding.batchSize: 0 must be > 0"
    )
    assertError(
      loadSections(editSections(_("embedding")("maxRetries") = -1)),
      "embedding.maxRetries: -1 must be >= 0"
    )
    assertError(
      loadSections(editSections(_("embedding")("retryMaxDelayMillis") = 100)),
      "embedding.retryMaxDelayMillis: 100 is less than retryBaseDelayMillis 500"
    )
    assertError(
      loadSections(editSections(_("embedding")("requestsPerMinute") = 0)),
      "embedding.requestsPerMinute"
    )
    assertError(
      loadSections(editSections(_("embedding")("tokensPerMinute") = 0)),
      "embedding.tokensPerMinute"
    )
  }

  test("vector index names, batch size and retries are checked") {
    assertError(
      loadSections(editSections(_("vectors")("paperAbstractIndex") = "Paper_Abstract")),
      "vectors.paperAbstractIndex: 'Paper_Abstract' must match"
    )
    assertError(
      loadSections(editSections(_("vectors")("paperAbstractIndex") = "researcher-profile-v1")),
      "vectors: index names must be distinct"
    )
    assertError(
      loadSections(editSections(_("vectors")("putBatchSize") = 501)),
      "vectors.putBatchSize: 501 is outside 1..500"
    )
    assertError(
      loadSections(editSections(_("vectors")("retryBaseDelayMillis") = 0)),
      "vectors.retryBaseDelayMillis: 0 must be > 0"
    )
    assertError(
      loadSections(editSections(_("vectors")("queryTopK") = 0)),
      "vectors.queryTopK: 0 must be > 0"
    )
  }

  test("object-store, DynamoDB and Neptune values are checked") {
    assertError(
      loadSections(editSections(_("objectStore")("rawPrefix") = "/raw")),
      "objectStore.rawPrefix: '/raw' must match"
    )
    assertError(
      loadSections(editSections(_("objectStore")("logsPrefix") = "logs/")),
      "objectStore.logsPrefix: 'logs/' must match"
    )
    assertError(
      loadSections(editSections(_("objectStore")("reportsBucket") = "Bad_Bucket")),
      "objectStore.reportsBucket: 'Bad_Bucket' is not a valid bucket name"
    )
    assertError(
      loadSections(editSections(_("objectStore")("logsPrefix") = "reports")),
      "objectStore: the six zone locations (bucket, prefix) must be distinct"
    )
    assertEquals(
      loadedSections(
        editSections(_("objectStore")("logsBucket") = "example-logs-bucket")
      ).config.objectStore.map(_.logsBucket),
      Some("example-logs-bucket")
    )
    assertError(
      loadSections(editSections(_("dynamoDb")("querySessionTable") = "qs")),
      "dynamoDb.querySessionTable: 'qs' is not a valid table name"
    )
    assertError(
      loadSections(editSections(_("dynamoDb")("querySessionTable") = "example-run-ledger")),
      "dynamoDb: table names must be distinct"
    )
    assertError(
      loadSections(editSections(_("neptune")("port") = 0)),
      "neptune.port: 0 is outside 1..65535"
    )
    assertError(
      loadSections(editSections(_("neptune")("queryTimeoutMillis") = 0)),
      "neptune.queryTimeoutMillis: 0 must be > 0"
    )
    assertError(
      loadSections(editSections(_("neptune")("mergeRowCap") = 0)),
      "neptune.mergeRowCap: 0 must be > 0"
    )
  }

  test("unknown section keys and wrong types in sections are rejected") {
    assertError(
      loadSections(editSections(_("vectors")("bucket") = "example-vector-bucket")),
      "vectors.bucket: unknown configuration key"
    )
    assertError(
      loadSections(editSections(_("neptune")("port") = "8182")),
      "neptune.port: expected an integer"
    )
    assertError(
      load(env = Map("SCHOLARCHAIN_CFG_EMBEDDING_API_KEY" -> "x")),
      "SCHOLARCHAIN_CFG_EMBEDDING_API_KEY: unknown configuration variable"
    )
  }

  test("section environment variable names follow the rule and every name is unique") {
    assertEquals(
      ConfigKey.envVarFor("embedding.apiKeyEnvVar"),
      "SCHOLARCHAIN_CFG_EMBEDDING_API_KEY_ENV_VAR"
    )
    assertEquals(
      ConfigKey.envVarFor("vectors.topicInstitutionSummaryIndex"),
      "SCHOLARCHAIN_CFG_VECTORS_TOPIC_INSTITUTION_SUMMARY_INDEX"
    )
    assertEquals(
      ConfigKey.envVarFor("dynamoDb.runLedgerTable"),
      "SCHOLARCHAIN_CFG_DYNAMO_DB_RUN_LEDGER_TABLE"
    )
    val names = ConfigLoader.allKeys.map(_.envVar)
    assertEquals(names.distinct.size, names.size)
    assertEquals(ConfigLoader.allKeys.size, ConfigLoader.keys.size + 37)
  }
}
