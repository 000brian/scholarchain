package org.scholarchain.core.config

import scala.io.Source

import org.scholarchain.core.ContractError

/** Phase 11 (T-08, T-09, U-6, D-03, U-8, D-08): a configuration refusal names the key and the rule,
  * never the refused string value (start-up errors reach logs; a refused value may be a bucket, a
  * host, or a key pasted where a variable NAME belongs); service key variables differ from the
  * model-provider key variables; well-known environment variables never name an API key.
  */
class ConfigRefusalSuite extends munit.FunSuite {

  /** Looks like a key pasted where a name belongs; invalid for every name rule of the loader. */
  val Sentinel = "Sk-Live PHASE11/SENTINEL+value"

  def resource(name: String): String = {
    val src = Source.fromInputStream(getClass.getResourceAsStream(s"/config/$name"), "UTF-8")
    try src.mkString
    finally src.close()
  }

  /** Every optional section at once: the Phase 7 sections, llm, runtime and service. */
  lazy val fullText: String = {
    val tree = ujson.read(resource("valid-v1-sections.json"))
    val runtime = ujson.read(resource("valid-v1-runtime.json"))
    val service = ujson.read(resource("valid-v1-service.json"))
    tree("llm") = runtime("llm")
    tree("runtime") = runtime("runtime")
    tree("service") = service("service")
    tree.render()
  }

  val FileName = "full.json"

  def load(text: String = fullText, env: Map[String, String] = Map.empty) =
    ConfigLoader.load(text, FileName, env)

  def edit(f: ujson.Value => Unit): String = {
    val tree = ujson.read(fullText)
    f(tree)
    tree.render()
  }

  def messages(r: Either[ContractError, LoadedConfig]): List[String] =
    r.left.toOption.map(_.messages).getOrElse(Nil)

  /** Dotted paths of every string leaf inside nested objects. */
  def stringLeaves(prefix: List[String], v: ujson.Value): List[List[String]] = v match {
    case o: ujson.Obj => o.value.toList.flatMap { case (k, x) => stringLeaves(prefix :+ k, x) }
    case _: ujson.Str => List(prefix)
    case _            => Nil
  }

  test("the combined fixture with every optional section loads") {
    val c = load().fold(e => fail(e.message), identity).config
    assert(c.service.nonEmpty && c.llm.nonEmpty && c.embedding.nonEmpty, c)
  }

  test("T-08/T-09/U-6: no refused string value is repeated; the refusal names its key") {
    val paths = stringLeaves(Nil, ujson.read(fullText))
    assert(paths.size > 40, paths) // non-vacuous: every name rule of the loader is reached
    val refused = paths.flatMap { p =>
      val dotted = p.mkString(".")
      messages(load(edit(t => p.init.foldLeft(t)((v, k) => v(k))(p.last) = Sentinel))) match {
        case Nil => None
        case ms  =>
          val m = ms.mkString("; ")
          assert(!m.contains(Sentinel), s"$dotted: the refusal repeats the value: $m")
          assert(!m.contains("SENTINEL"), s"$dotted: the refusal repeats part of the value: $m")
          assert(m.contains(dotted), s"$dotted: the refusal does not name the key: $m")
          Some(dotted)
      }
    }
    // the fields named by T-08, U-6 and T-09 are all refused (and so were checked above)
    List(
      "service.apiKeyEnvVar",
      "service.readOnlyApiKeyEnvVar",
      "llm.apiKeyEnvVar",
      "embedding.apiKeyEnvVar",
      "service.jobQueueUrl",
      "service.embeddingQueueUrl",
      "environment",
      "aws.region",
      "storage.rawBucket",
      "storage.neptuneEndpoint",
      "storage.dynamoDbTable",
      "objectStore.logsPrefix",
      "objectStore.logsBucket",
      "dynamoDb.runLedgerTable",
      "vectors.paperAbstractIndex",
      "embedding.provider",
      "llm.provider",
      "runtime.defaultWindowLabel",
      "bridge.algorithm"
    ).foreach(k => assert(refused.contains(k), s"$k was not refused: $refused"))
  }

  test("T-08: a key pasted into service.apiKeyEnvVar is refused by field and rule only") {
    assertEquals(
      messages(load(edit(_("service")("apiKeyEnvVar") = Sentinel))),
      List("service.apiKeyEnvVar: must match [A-Z][A-Z0-9_]*")
    )
  }

  test("U-6: an invalid service.jobQueueUrl is refused by field and rule only") {
    assertEquals(
      messages(load(edit(_("service")("jobQueueUrl") = Sentinel))),
      List("service.jobQueueUrl: must be an https:// URL with a host")
    )
  }

  test(
    "U-6 (Phase 15 decision 5): an invalid service.embeddingQueueUrl is refused by field and rule only"
  ) {
    assertEquals(
      messages(load(edit(_("service")("embeddingQueueUrl") = Sentinel))),
      List("service.embeddingQueueUrl: must be an https:// URL with a host")
    )
    val viaEnv =
      messages(load(env = Map("SCHOLARCHAIN_CFG_SERVICE_EMBEDDING_QUEUE_URL" -> Sentinel)))
    assert(viaEnv.forall(!_.contains("SENTINEL")), viaEnv)
    assert(viaEnv.exists(_.startsWith("service.embeddingQueueUrl")), viaEnv)
  }

  test("D-03: a graph window label and a configVersion are refused without their value") {
    val label = messages(load(edit(_("graph")("windows")(0)("label") = Sentinel)))
    assert(label.exists(_.startsWith("graph.windows[0].label: must match")), label)
    assert(!label.mkString.contains("SENTINEL"), label)
    val version = messages(load(edit(_("configVersion") = Sentinel)))
    assertEquals(
      version,
      List(s"$FileName: configVersion: unsupported value a string (supported: 1)")
    )
  }

  test("D-03: environment and type-mismatch refusals never repeat the raw value") {
    val cases = List(
      load(env = Map("SCHOLARCHAIN_CFG_BRIDGE_TOP_K" -> Sentinel)) ->
        "SCHOLARCHAIN_CFG_BRIDGE_TOP_K: is not a JSON literal",
      load(env = Map("SCHOLARCHAIN_CFG_STORAGE_RAW_BUCKET" -> Sentinel)) ->
        "storage.rawBucket: is not a valid bucket name",
      load(env = Map("SCHOLARCHAIN_CFG_SERVICE_API_KEY_ENV_VAR" -> Sentinel)) ->
        "service.apiKeyEnvVar: must match",
      load(edit(_("bridge")("topK") = Sentinel)) ->
        s"$FileName: bridge.topK: expected an integer, found a string",
      load(edit(_("storage")("rawBucket") = ujson.Arr(Sentinel))) ->
        s"$FileName: storage.rawBucket: expected a string, found an array"
    )
    cases.foreach { case (r, fragment) =>
      val ms = messages(r)
      assert(ms.exists(_.contains(fragment)), s"expected '$fragment' in $ms")
      assert(!ms.mkString.contains("SENTINEL"), ms)
    }
  }

  test("U-8: service key variables differ from the llm and embedding key variables") {
    val llmKey = "SCHOLARCHAIN_EXAMPLE_LLM_API_KEY"
    val embeddingKey = "SCHOLARCHAIN_VOYAGE_API_KEY"
    assertEquals(
      messages(load(edit(_("service")("apiKeyEnvVar") = llmKey))),
      List("service.apiKeyEnvVar: must differ from llm.apiKeyEnvVar")
    )
    assertEquals(
      messages(load(edit(_("service")("readOnlyApiKeyEnvVar") = embeddingKey))),
      List("service.readOnlyApiKeyEnvVar: must differ from embedding.apiKeyEnvVar")
    )
    assertEquals(
      messages(load(edit { t =>
        t("service")("apiKeyEnvVar") = embeddingKey
        t("service")("readOnlyApiKeyEnvVar") = llmKey
      })),
      List(
        "service.apiKeyEnvVar: must differ from embedding.apiKeyEnvVar",
        "service.readOnlyApiKeyEnvVar: must differ from llm.apiKeyEnvVar"
      )
    )
    // the same rule through the environment
    assert(
      messages(
        load(env = Map("SCHOLARCHAIN_CFG_LLM_API_KEY_ENV_VAR" -> "SCHOLARCHAIN_EXAMPLE_API_KEY"))
      )
        .contains("service.apiKeyEnvVar: must differ from llm.apiKeyEnvVar")
    )
    // without the provider sections the service names are unconstrained by them
    val serviceOnly = ConfigLoader.load(resource("valid-v1-service.json"), "s.json", Map.empty)
    assert(serviceOnly.isRight, serviceOnly)
  }

  test("D-08: a well-known environment variable never names an API key") {
    List("HOSTNAME", "PATH", "HOME", "AWS_REGION", "SCHOLARCHAIN_CONFIG_FILE").foreach { n =>
      List(
        "service.apiKeyEnvVar" -> edit(_("service")("apiKeyEnvVar") = n),
        "service.readOnlyApiKeyEnvVar" -> edit(_("service")("readOnlyApiKeyEnvVar") = n),
        "llm.apiKeyEnvVar" -> edit(_("llm")("apiKeyEnvVar") = n),
        "embedding.apiKeyEnvVar" -> edit(_("embedding")("apiKeyEnvVar") = n)
      ).foreach { case (field, text) =>
        assert(
          messages(load(text)).contains(
            s"$field: must not name a well-known environment variable " +
              "(see ConfigValidator.ReservedEnvVarNames)"
          ),
          s"$field = $n: ${messages(load(text))}"
        )
      }
    }
    assert(ConfigValidator.ReservedEnvVarNames.contains(ConfigLoader.ConfigFileEnvVar))
  }
}
