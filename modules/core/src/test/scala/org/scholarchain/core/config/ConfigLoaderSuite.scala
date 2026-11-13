package org.scholarchain.core.config

import java.nio.file.Paths

import scala.io.Source

import org.scholarchain.core.{ContractError, YearWindow}

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
}
