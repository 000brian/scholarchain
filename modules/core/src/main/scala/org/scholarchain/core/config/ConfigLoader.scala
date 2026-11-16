package org.scholarchain.core.config

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

import scala.collection.immutable.ListMap
import scala.util.control.NonFatal

import org.scholarchain.core.{
  ContractError,
  SchemaVersions,
  Validator,
  VectorIndexName,
  VectorMetadata
}
import upickle.default.read

sealed trait ValueKind
object ValueKind {
  case object Text extends ValueKind
  case object WholeNumber extends ValueKind
  case object Decimal extends ValueKind

  /** JSON array of `{"label": ..., "startYear": ..., "endYear": ...}` objects. */
  case object WindowList extends ValueKind
}

/** One configuration key: its dotted file path, value kind, and environment variable. */
final case class ConfigKey(path: String, kind: ValueKind) {
  def envVar: String = ConfigKey.envVarFor(path)
}
object ConfigKey {

  /** `SCHOLARCHAIN_CFG_` + path with dots as `_` and camelCase as UPPER_SNAKE. Example:
    * `entityResolution.matchThreshold -> SCHOLARCHAIN_CFG_ENTITY_RESOLUTION_MATCH_THRESHOLD`.
    */
  def envVarFor(path: String): String =
    ConfigLoader.EnvPrefix + path
      .split('.')
      .map(_.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase(java.util.Locale.ROOT))
      .mkString("_")
}

sealed trait ConfigOrigin
object ConfigOrigin {
  final case class File(fileName: String) extends ConfigOrigin
  final case class Environment(variable: String) extends ConfigOrigin
}

/** An optional configuration section (Phase 7). The section is absent unless at least one of its
  * keys is set (file or environment); when present, every key in `required` must be set, and keys
  * in `optional` may be left unset (they resolve to `None`).
  */
final case class ConfigSection(name: String, required: List[ConfigKey], optional: List[ConfigKey]) {
  def keys: List[ConfigKey] = required ++ optional
}

/** A resolved configuration and where each key's value came from. */
final case class LoadedConfig(config: ScholarChainConfig, origins: ListMap[String, ConfigOrigin])

/** Loads [[ScholarChainConfig]] from a versioned JSON configuration file plus environment
  * variables.
  *
  * File format: a JSON object (JSON is also valid HOCON and YAML 1.2, so the files stay readable by
  * those tools) with a top-level integer `configVersion` and nested objects matching the dotted key
  * paths in [[ConfigLoader.keys]].
  *
  * Precedence, highest first:
  *   1. environment variable `SCHOLARCHAIN_CFG_<PATH>` (see [[ConfigKey.envVarFor]]); text keys
  *      take the raw value, other kinds take a JSON literal (number or array);
  *   2. the configuration file named by `SCHOLARCHAIN_CONFIG_FILE`;
  *   3. nothing: a key absent from both is an error. There are no defaults.
  *
  * Optional sections ([[ConfigLoader.sections]], Phase 7 and Phase 9): a section none of whose keys
  * is set resolves to `None`; a section with any key set must have all its required keys set (same
  * precedence per key), otherwise every missing key is reported.
  *
  * `configVersion` comes only from the file. Unknown file keys, unknown `SCHOLARCHAIN_CFG_*`
  * variables, empty values, wrong types, and semantically invalid values are all rejected, and
  * every problem is reported at once.
  */
object ConfigLoader {

  val ConfigFileEnvVar = "SCHOLARCHAIN_CONFIG_FILE"
  val EnvPrefix = "SCHOLARCHAIN_CFG_"
  val SchemaName = "ScholarChainConfig"
  val VersionKey = "configVersion"

  import ValueKind._

  val keys: List[ConfigKey] = List(
    ConfigKey("environment", Text),
    ConfigKey("aws.region", Text),
    ConfigKey("aws.accountId", Text),
    ConfigKey("storage.rawBucket", Text),
    ConfigKey("storage.curatedBucket", Text),
    ConfigKey("storage.graphBucket", Text),
    ConfigKey("storage.vectorBucket", Text),
    ConfigKey("storage.vectorIndex", Text),
    ConfigKey("storage.neptuneEndpoint", Text),
    ConfigKey("storage.dynamoDbTable", Text),
    ConfigKey("entityResolution.policyVersion", Text),
    ConfigKey("entityResolution.matchThreshold", Decimal),
    ConfigKey("entityResolution.reviewThreshold", Decimal),
    ConfigKey("models.embeddingModelId", Text),
    ConfigKey("models.answerModelId", Text),
    ConfigKey("graph.windows", WindowList),
    ConfigKey("graph.minEdgeWeight", Decimal),
    ConfigKey("bridge.algorithm", Text),
    ConfigKey("bridge.sampleSize", WholeNumber),
    ConfigKey("bridge.randomSeed", WholeNumber),
    ConfigKey("bridge.topK", WholeNumber)
  )

  /** Optional sections: `objectStore`, `dynamoDb`, `neptune`, `vectors`, `embedding` (Phase 7,
    * additive) and `llm`, `runtime` (Phase 9, additive).
    */
  val sections: List[ConfigSection] = List(
    ConfigSection(
      "objectStore",
      List(
        ConfigKey("objectStore.rawPrefix", Text),
        ConfigKey("objectStore.curatedPrefix", Text),
        ConfigKey("objectStore.graphPrefix", Text),
        ConfigKey("objectStore.semanticContextPrefix", Text),
        ConfigKey("objectStore.reportsPrefix", Text),
        ConfigKey("objectStore.logsPrefix", Text),
        ConfigKey("objectStore.semanticContextBucket", Text),
        ConfigKey("objectStore.reportsBucket", Text),
        ConfigKey("objectStore.logsBucket", Text)
      ),
      Nil
    ),
    ConfigSection(
      "dynamoDb",
      List(
        ConfigKey("dynamoDb.runLedgerTable", Text),
        ConfigKey("dynamoDb.entityLinkCacheTable", Text),
        ConfigKey("dynamoDb.querySessionTable", Text),
        ConfigKey("dynamoDb.evidenceBundleTable", Text),
        ConfigKey("dynamoDb.artifactRegistryTable", Text),
        ConfigKey("dynamoDb.researcherMetricCacheTable", Text)
      ),
      Nil
    ),
    ConfigSection(
      "neptune",
      List(
        ConfigKey("neptune.port", WholeNumber),
        ConfigKey("neptune.queryTimeoutMillis", WholeNumber),
        ConfigKey("neptune.mergeRowCap", WholeNumber)
      ),
      Nil
    ),
    ConfigSection(
      "vectors",
      List(
        ConfigKey("vectors.researcherProfileIndex", Text),
        ConfigKey("vectors.collaborationCommunityIndex", Text),
        ConfigKey("vectors.paperAbstractIndex", Text),
        ConfigKey("vectors.collaborationPathIndex", Text),
        ConfigKey("vectors.topicInstitutionSummaryIndex", Text),
        ConfigKey("vectors.putBatchSize", WholeNumber),
        ConfigKey("vectors.maxRetries", WholeNumber),
        ConfigKey("vectors.retryBaseDelayMillis", WholeNumber),
        ConfigKey("vectors.retryMaxDelayMillis", WholeNumber),
        ConfigKey("vectors.queryTopK", WholeNumber)
      ),
      Nil
    ),
    ConfigSection(
      "embedding",
      List(
        ConfigKey("embedding.provider", Text),
        ConfigKey("embedding.dimensions", WholeNumber),
        ConfigKey("embedding.batchSize", WholeNumber),
        ConfigKey("embedding.maxRetries", WholeNumber),
        ConfigKey("embedding.retryBaseDelayMillis", WholeNumber),
        ConfigKey("embedding.retryMaxDelayMillis", WholeNumber),
        ConfigKey("embedding.requestsPerMinute", WholeNumber),
        ConfigKey("embedding.tokensPerMinute", WholeNumber)
      ),
      List(ConfigKey("embedding.apiKeyEnvVar", Text))
    ),
    // Phase 9 (additive).
    ConfigSection(
      "llm",
      List(
        ConfigKey("llm.provider", Text),
        ConfigKey("llm.maxOutputTokens", WholeNumber),
        ConfigKey("llm.requestTimeoutMillis", WholeNumber),
        ConfigKey("llm.maxRetries", WholeNumber),
        ConfigKey("llm.retryBaseDelayMillis", WholeNumber),
        ConfigKey("llm.retryMaxDelayMillis", WholeNumber),
        ConfigKey("llm.requestsPerMinute", WholeNumber),
        ConfigKey("llm.maxStructuredRetries", WholeNumber),
        ConfigKey("llm.contextTokenBudget", WholeNumber)
      ),
      List(ConfigKey("llm.apiKeyEnvVar", Text))
    ),
    ConfigSection(
      "runtime",
      List(
        ConfigKey("runtime.defaultWindowLabel", Text),
        ConfigKey("runtime.rankingCacheTopK", WholeNumber),
        ConfigKey("runtime.maxBundleItems", WholeNumber),
        ConfigKey("runtime.maxPathDepth", WholeNumber),
        ConfigKey("runtime.graphResultLimit", WholeNumber),
        ConfigKey("runtime.graphTimeoutMillis", WholeNumber),
        ConfigKey("runtime.maxResearcherNames", WholeNumber)
      ),
      Nil
    )
  )

  /** Every known key: the required base keys, then the keys of every optional section. */
  val allKeys: List[ConfigKey] = keys ++ sections.flatMap(_.keys)

  /** Reads the file named by `SCHOLARCHAIN_CONFIG_FILE` and applies `env` overrides. */
  def loadFromEnvironment(env: Map[String, String]): Either[ContractError, LoadedConfig] =
    env.get(ConfigFileEnvVar).map(_.trim).filter(_.nonEmpty) match {
      case None =>
        Left(ContractError(List(s"$ConfigFileEnvVar: required, names the configuration file")))
      case Some(path) =>
        val text =
          try Right(new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8))
          catch {
            case NonFatal(e) =>
              Left(ContractError(List(s"$ConfigFileEnvVar: cannot read '$path': ${e.getMessage}")))
          }
        text.flatMap(load(_, path, env))
    }

  /** Pure loader: file contents, a name for messages, and the environment. */
  def load(
      fileText: String,
      fileName: String,
      env: Map[String, String]
  ): Either[ContractError, LoadedConfig] = {
    val parsed =
      try Right(ujson.read(fileText))
      catch { case NonFatal(e) => Left(s"$fileName: not valid JSON: ${e.getMessage}") }
    parsed match {
      case Left(msg)              => Left(ContractError(List(msg)))
      case Right(root: ujson.Obj) => resolve(root, fileName, env)
      case Right(_) => Left(ContractError(List(s"$fileName: top level must be a JSON object")))
    }
  }

  private def flatten(prefix: String, v: ujson.Value): List[(String, ujson.Value)] = v match {
    case o: ujson.Obj =>
      o.value.toList.flatMap { case (k, x) =>
        flatten(if (prefix.isEmpty) k else s"$prefix.$k", x)
      }
    case other => List(prefix -> other)
  }

  private def resolve(
      root: ujson.Obj,
      fileName: String,
      env: Map[String, String]
  ): Either[ContractError, LoadedConfig] = {
    val flat = ListMap(flatten("", root): _*)
    val known = allKeys.map(_.path).toSet + VersionKey
    val knownEnv = allKeys.map(_.envVar).toSet

    val versionErrors = flat.get(VersionKey) match {
      case None => List(s"$fileName: $VersionKey: required")
      case Some(ujson.Num(n)) if n.isWhole && SchemaVersions.isSupported(SchemaName, n.toInt) =>
        Nil
      case Some(other) =>
        List(
          s"$fileName: $VersionKey: unsupported value ${other.render()} (supported: " +
            s"${SchemaVersions.supported(SchemaName).toList.sorted.mkString(", ")})"
        )
    }
    val unknownFile =
      flat.keys.filterNot(known).toList.map(k => s"$fileName: $k: unknown configuration key")
    val unknownEnv = env.keys.toList.sorted
      .filter(k => k.startsWith(EnvPrefix) && !knownEnv(k))
      .map(k => s"$k: unknown configuration variable")

    // None: the key is set neither in the environment nor in the file.
    def lookup(key: ConfigKey): Option[Either[String, (Any, ConfigOrigin)]] = {
      val fromEnv = env.get(key.envVar).map { raw =>
        val where = s"${key.envVar}"
        if (raw.trim.isEmpty) Left(s"$where: set but empty")
        else
          envValue(key, raw)
            .flatMap(decode(key, _, where))
            .map(_ -> ConfigOrigin.Environment(where))
      }
      fromEnv.orElse(
        flat
          .get(key.path)
          .map(v => decode(key, v, s"$fileName: ${key.path}").map(_ -> ConfigOrigin.File(fileName)))
      )
    }
    val base: List[(ConfigKey, Either[String, (Any, ConfigOrigin)])] = keys.map { key =>
      key -> lookup(key).getOrElse(
        Left(s"${key.path}: required; set it in $fileName or in ${key.envVar}")
      )
    }
    val sectionResults = sections.map { section =>
      val found = section.keys.map(k => k -> lookup(k))
      val active = found.exists(_._2.nonEmpty)
      val results = found.flatMap {
        case (k, Some(r))                                        => List(k -> r)
        case (k, None) if active && section.required.contains(k) =>
          List(
            k -> Left(
              s"${k.path}: required because section ${section.name} is configured; " +
                s"set it in $fileName or in ${k.envVar}"
            )
          )
        case _ => Nil
      }
      (section.name, active, results)
    }
    val activeSections = sectionResults.collect { case (name, true, _) => name }.toSet
    val resolved = base ++ sectionResults.flatMap(_._3)
    val valueErrors = resolved.collect { case (_, Left(e)) => e }
    val structural = versionErrors ++ unknownFile ++ unknownEnv ++ valueErrors
    if (structural.nonEmpty) Left(ContractError(structural))
    else {
      val values = resolved.collect { case (k, Right((v, _))) => k.path -> v }.toMap
      val origins = ListMap(resolved.collect { case (k, Right((_, o))) => k.path -> o }: _*)
      val config = build(flat(VersionKey).num.toInt, values, activeSections)
      ConfigValidator.validate(config) match {
        case Nil    => Right(LoadedConfig(config, origins))
        case errors => Left(ContractError(errors))
      }
    }
  }

  private def envValue(key: ConfigKey, raw: String): Either[String, ujson.Value] =
    key.kind match {
      case Text => Right(ujson.Str(raw))
      case _    =>
        try Right(ujson.read(raw))
        catch { case NonFatal(_) => Left(s"${key.envVar}: '$raw' is not a JSON literal") }
    }

  private def decode(key: ConfigKey, v: ujson.Value, where: String): Either[String, Any] =
    (key.kind, v) match {
      case (Text, ujson.Str(s)) =>
        if (s.trim.nonEmpty) Right(s) else Left(s"$where: must not be blank")
      case (WholeNumber, ujson.Num(n)) =>
        if (n.isWhole && n >= Int.MinValue && n <= Int.MaxValue) Right(n.toInt)
        else Left(s"$where: $n is not a 32-bit integer")
      case (Decimal, ujson.Num(n)) =>
        if (!n.isNaN && !n.isInfinite) Right(n) else Left(s"$where: must be a finite number")
      case (WindowList, arr: ujson.Arr) =>
        try Right(read[List[NamedWindow]](arr))
        catch {
          case NonFatal(e) =>
            Left(s"$where: expected [{label, startYear, endYear}, ...]: ${e.getMessage}")
        }
      case (kind, other) =>
        Left(s"$where: expected ${kindName(kind)}, found ${other.render()}")
    }

  private def kindName(kind: ValueKind): String = kind match {
    case Text        => "a string"
    case WholeNumber => "an integer"
    case Decimal     => "a number"
    case WindowList  => "an array of windows"
  }

  private def build(
      version: Int,
      v: Map[String, Any],
      activeSections: Set[String]
  ): ScholarChainConfig = {
    def s(k: String): String = v(k).asInstanceOf[String]
    def i(k: String): Int = v(k).asInstanceOf[Int]
    def d(k: String): Double = v(k).asInstanceOf[Double]
    def section[T](name: String)(f: => T): Option[T] =
      if (activeSections.contains(name)) Some(f) else None
    ScholarChainConfig(
      configVersion = version,
      environment = s("environment"),
      aws = AwsSettings(s("aws.region"), s("aws.accountId")),
      storage = StorageSettings(
        rawBucket = s("storage.rawBucket"),
        curatedBucket = s("storage.curatedBucket"),
        graphBucket = s("storage.graphBucket"),
        vectorBucket = s("storage.vectorBucket"),
        vectorIndex = s("storage.vectorIndex"),
        neptuneEndpoint = s("storage.neptuneEndpoint"),
        dynamoDbTable = s("storage.dynamoDbTable")
      ),
      entityResolution = EntityResolutionSettings(
        policyVersion = s("entityResolution.policyVersion"),
        matchThreshold = d("entityResolution.matchThreshold"),
        reviewThreshold = d("entityResolution.reviewThreshold")
      ),
      models = ModelSettings(s("models.embeddingModelId"), s("models.answerModelId")),
      graph = GraphSettings(
        v("graph.windows").asInstanceOf[List[NamedWindow]],
        d("graph.minEdgeWeight")
      ),
      bridge = BridgeSettings(
        algorithm = s("bridge.algorithm"),
        sampleSize = i("bridge.sampleSize"),
        randomSeed = i("bridge.randomSeed"),
        topK = i("bridge.topK")
      ),
      objectStore = section("objectStore")(
        ObjectStoreSettings(
          rawPrefix = s("objectStore.rawPrefix"),
          curatedPrefix = s("objectStore.curatedPrefix"),
          graphPrefix = s("objectStore.graphPrefix"),
          semanticContextPrefix = s("objectStore.semanticContextPrefix"),
          reportsPrefix = s("objectStore.reportsPrefix"),
          logsPrefix = s("objectStore.logsPrefix"),
          semanticContextBucket = s("objectStore.semanticContextBucket"),
          reportsBucket = s("objectStore.reportsBucket"),
          logsBucket = s("objectStore.logsBucket")
        )
      ),
      dynamoDb = section("dynamoDb")(
        DynamoDbSettings(
          runLedgerTable = s("dynamoDb.runLedgerTable"),
          entityLinkCacheTable = s("dynamoDb.entityLinkCacheTable"),
          querySessionTable = s("dynamoDb.querySessionTable"),
          evidenceBundleTable = s("dynamoDb.evidenceBundleTable"),
          artifactRegistryTable = s("dynamoDb.artifactRegistryTable"),
          researcherMetricCacheTable = s("dynamoDb.researcherMetricCacheTable")
        )
      ),
      neptune = section("neptune")(
        NeptuneSettings(
          port = i("neptune.port"),
          queryTimeoutMillis = i("neptune.queryTimeoutMillis"),
          mergeRowCap = i("neptune.mergeRowCap")
        )
      ),
      vectors = section("vectors")(
        VectorSettings(
          researcherProfileIndex = s("vectors.researcherProfileIndex"),
          collaborationCommunityIndex = s("vectors.collaborationCommunityIndex"),
          paperAbstractIndex = s("vectors.paperAbstractIndex"),
          collaborationPathIndex = s("vectors.collaborationPathIndex"),
          topicInstitutionSummaryIndex = s("vectors.topicInstitutionSummaryIndex"),
          putBatchSize = i("vectors.putBatchSize"),
          maxRetries = i("vectors.maxRetries"),
          retryBaseDelayMillis = i("vectors.retryBaseDelayMillis"),
          retryMaxDelayMillis = i("vectors.retryMaxDelayMillis"),
          queryTopK = i("vectors.queryTopK")
        )
      ),
      embedding = section("embedding")(
        EmbeddingSettings(
          provider = s("embedding.provider"),
          dimensions = i("embedding.dimensions"),
          apiKeyEnvVar = v.get("embedding.apiKeyEnvVar").map(_.asInstanceOf[String]),
          batchSize = i("embedding.batchSize"),
          maxRetries = i("embedding.maxRetries"),
          retryBaseDelayMillis = i("embedding.retryBaseDelayMillis"),
          retryMaxDelayMillis = i("embedding.retryMaxDelayMillis"),
          requestsPerMinute = i("embedding.requestsPerMinute"),
          tokensPerMinute = i("embedding.tokensPerMinute")
        )
      ),
      llm = section("llm")(
        LlmSettings(
          provider = s("llm.provider"),
          maxOutputTokens = i("llm.maxOutputTokens"),
          requestTimeoutMillis = i("llm.requestTimeoutMillis"),
          maxRetries = i("llm.maxRetries"),
          retryBaseDelayMillis = i("llm.retryBaseDelayMillis"),
          retryMaxDelayMillis = i("llm.retryMaxDelayMillis"),
          requestsPerMinute = i("llm.requestsPerMinute"),
          maxStructuredRetries = i("llm.maxStructuredRetries"),
          contextTokenBudget = i("llm.contextTokenBudget"),
          apiKeyEnvVar = v.get("llm.apiKeyEnvVar").map(_.asInstanceOf[String])
        )
      ),
      runtime = section("runtime")(
        RuntimeSettings(
          defaultWindowLabel = s("runtime.defaultWindowLabel"),
          rankingCacheTopK = i("runtime.rankingCacheTopK"),
          maxBundleItems = i("runtime.maxBundleItems"),
          maxPathDepth = i("runtime.maxPathDepth"),
          graphResultLimit = i("runtime.graphResultLimit"),
          graphTimeoutMillis = i("runtime.graphTimeoutMillis"),
          maxResearcherNames = i("runtime.maxResearcherNames")
        )
      )
    )
  }
}

/** Semantic rules for a resolved configuration. */
object ConfigValidator {
  import Validator.check

  private val Bucket = "[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]"
  private val Token = "\\S+"

  def validate(c: ScholarChainConfig): List[String] = {
    val st = c.storage
    val er = c.entityResolution
    val labels = c.graph.windows.map(_.label)
    check(
      c.environment.matches("[a-z0-9][a-z0-9-]*"),
      s"environment: '${c.environment}' must match [a-z0-9][a-z0-9-]*"
    ) ++
      check(
        c.aws.region.matches("[a-z]{2}(-[a-z]+)+-[0-9]+"),
        s"aws.region: '${c.aws.region}' is not an AWS Region name"
      ) ++
      check(c.aws.accountId.matches("[0-9]{12}"), "aws.accountId: must be 12 digits") ++
      List(
        "storage.rawBucket" -> st.rawBucket,
        "storage.curatedBucket" -> st.curatedBucket,
        "storage.graphBucket" -> st.graphBucket,
        "storage.vectorBucket" -> st.vectorBucket
      ).flatMap { case (k, b) =>
        check(b.matches(Bucket), s"$k: '$b' is not a valid bucket name")
      } ++
      check(st.vectorIndex.matches(Token), "storage.vectorIndex: must not contain whitespace") ++
      check(
        st.neptuneEndpoint.matches("[A-Za-z0-9.-]+"),
        s"storage.neptuneEndpoint: '${st.neptuneEndpoint}' must be a host name (no scheme or port)"
      ) ++
      check(
        st.dynamoDbTable.matches("[A-Za-z0-9_.-]{3,255}"),
        s"storage.dynamoDbTable: '${st.dynamoDbTable}' is not a valid table name"
      ) ++
      Validator.confidence("entityResolution.matchThreshold", er.matchThreshold) ++
      Validator.confidence("entityResolution.reviewThreshold", er.reviewThreshold) ++
      check(
        er.reviewThreshold <= er.matchThreshold,
        s"entityResolution.reviewThreshold: ${er.reviewThreshold} exceeds matchThreshold ${er.matchThreshold}"
      ) ++
      check(c.models.embeddingModelId.matches(Token), "models.embeddingModelId: no whitespace") ++
      check(c.models.answerModelId.matches(Token), "models.answerModelId: no whitespace") ++
      check(c.graph.windows.nonEmpty, "graph.windows: must not be empty") ++
      check(labels.distinct.size == labels.size, "graph.windows: labels must be distinct") ++
      c.graph.windows.zipWithIndex.flatMap { case (w, i) =>
        check(
          w.label.matches("[a-z0-9][a-z0-9-]*"),
          s"graph.windows[$i].label: '${w.label}' must match [a-z0-9][a-z0-9-]*"
        ) ++ Validator.window(s"graph.windows[$i]", w.window)
      } ++
      Validator.finiteNonNegative("graph.minEdgeWeight", c.graph.minEdgeWeight) ++
      check(
        c.bridge.algorithm.matches("[a-z0-9][a-z0-9_-]*"),
        s"bridge.algorithm: '${c.bridge.algorithm}' must match [a-z0-9][a-z0-9_-]*"
      ) ++
      check(c.bridge.sampleSize > 0, s"bridge.sampleSize: ${c.bridge.sampleSize} must be > 0") ++
      check(c.bridge.topK > 0, s"bridge.topK: ${c.bridge.topK} must be > 0") ++
      c.objectStore.toList.flatMap(objectStore(_, st)) ++
      c.dynamoDb.toList.flatMap(dynamoDb) ++
      c.neptune.toList.flatMap(neptune) ++
      c.vectors.toList.flatMap(vectors) ++
      c.embedding.toList.flatMap(embedding) ++
      c.llm.toList.flatMap(llm) ++
      c.runtime.toList.flatMap(runtime(_, labels))
  }

  // ---- Phase 7 optional sections -------------------------------------------------------------

  private val Prefix = "[a-z0-9][a-z0-9._=-]*(/[a-z0-9][a-z0-9._=-]*)*"
  private val TableName = "[A-Za-z0-9_.-]{3,255}"
  private val EnvVarName = "[A-Z][A-Z0-9_]*"

  private def positive(field: String, n: Int): List[String] =
    check(n > 0, s"$field: $n must be > 0")

  private def nonNegative(field: String, n: Int): List[String] =
    check(n >= 0, s"$field: $n must be >= 0")

  private def retries(section: String, max: Int, base: Int, cap: Int): List[String] =
    nonNegative(s"$section.maxRetries", max) ++
      positive(s"$section.retryBaseDelayMillis", base) ++
      check(
        cap >= base,
        s"$section.retryMaxDelayMillis: $cap is less than retryBaseDelayMillis $base"
      )

  private def objectStore(o: ObjectStoreSettings, st: StorageSettings): List[String] = {
    val locations = o.zones(st).values.toList
    List(
      "objectStore.rawPrefix" -> o.rawPrefix,
      "objectStore.curatedPrefix" -> o.curatedPrefix,
      "objectStore.graphPrefix" -> o.graphPrefix,
      "objectStore.semanticContextPrefix" -> o.semanticContextPrefix,
      "objectStore.reportsPrefix" -> o.reportsPrefix,
      "objectStore.logsPrefix" -> o.logsPrefix
    ).flatMap { case (k, p) => check(p.matches(Prefix), s"$k: '$p' must match $Prefix") } ++
      List(
        "objectStore.semanticContextBucket" -> o.semanticContextBucket,
        "objectStore.reportsBucket" -> o.reportsBucket,
        "objectStore.logsBucket" -> o.logsBucket
      ).flatMap { case (k, b) =>
        check(b.matches(Bucket), s"$k: '$b' is not a valid bucket name")
      } ++
      check(
        locations.distinct.size == locations.size,
        "objectStore: the six zone locations (bucket, prefix) must be distinct"
      )
  }

  private def dynamoDb(d: DynamoDbSettings): List[String] = {
    val tables = List(
      "dynamoDb.runLedgerTable" -> d.runLedgerTable,
      "dynamoDb.entityLinkCacheTable" -> d.entityLinkCacheTable,
      "dynamoDb.querySessionTable" -> d.querySessionTable,
      "dynamoDb.evidenceBundleTable" -> d.evidenceBundleTable,
      "dynamoDb.artifactRegistryTable" -> d.artifactRegistryTable,
      "dynamoDb.researcherMetricCacheTable" -> d.researcherMetricCacheTable
    )
    tables.flatMap { case (k, t) =>
      check(t.matches(TableName), s"$k: '$t' is not a valid table name")
    } ++
      check(
        tables.map(_._2).distinct.size == tables.size,
        "dynamoDb: table names must be distinct"
      )
  }

  private def neptune(n: NeptuneSettings): List[String] =
    check(n.port >= 1 && n.port <= 65535, s"neptune.port: ${n.port} is outside 1..65535") ++
      positive("neptune.queryTimeoutMillis", n.queryTimeoutMillis) ++
      positive("neptune.mergeRowCap", n.mergeRowCap)

  private def vectors(v: VectorSettings): List[String] = {
    val names = List(
      "vectors.researcherProfileIndex" -> v.researcherProfileIndex,
      "vectors.collaborationCommunityIndex" -> v.collaborationCommunityIndex,
      "vectors.paperAbstractIndex" -> v.paperAbstractIndex,
      "vectors.collaborationPathIndex" -> v.collaborationPathIndex,
      "vectors.topicInstitutionSummaryIndex" -> v.topicInstitutionSummaryIndex
    )
    names.flatMap { case (k, n) =>
      check(VectorIndexName.isValid(n), s"$k: '$n' must match ${VectorIndexName.Pattern}")
    } ++
      check(names.map(_._2).distinct.size == names.size, "vectors: index names must be distinct") ++
      check(
        v.putBatchSize >= 1 && v.putBatchSize <= MaxPutVectorsBatch,
        s"vectors.putBatchSize: ${v.putBatchSize} is outside 1..$MaxPutVectorsBatch"
      ) ++
      retries("vectors", v.maxRetries, v.retryBaseDelayMillis, v.retryMaxDelayMillis) ++
      positive("vectors.queryTopK", v.queryTopK)
  }

  /** S3 Vectors PutVectors limit (vectors per call). */
  val MaxPutVectorsBatch: Int = 500

  private def embedding(e: EmbeddingSettings): List[String] = {
    val providerRule = e.providerKind match {
      case Left(msg)                       => List(s"embedding.provider: $msg")
      case Right(EmbeddingProvider.Voyage) =>
        check(e.apiKeyEnvVar.nonEmpty, "embedding.apiKeyEnvVar: required for provider voyage")
      case Right(_) => Nil
    }
    providerRule ++
      e.apiKeyEnvVar.toList.flatMap(apiKeyEnvVarName("embedding.apiKeyEnvVar", _)) ++
      check(
        e.dimensions >= VectorMetadata.MinDimensions &&
          e.dimensions <= VectorMetadata.MaxDimensions,
        s"embedding.dimensions: ${e.dimensions} is outside " +
          s"${VectorMetadata.MinDimensions}..${VectorMetadata.MaxDimensions}"
      ) ++
      positive("embedding.batchSize", e.batchSize) ++
      retries("embedding", e.maxRetries, e.retryBaseDelayMillis, e.retryMaxDelayMillis) ++
      positive("embedding.requestsPerMinute", e.requestsPerMinute) ++
      positive("embedding.tokensPerMinute", e.tokensPerMinute)
  }

  /** The NAME of an API-key environment variable (never its value). */
  private def apiKeyEnvVarName(field: String, n: String): List[String] =
    check(n.matches(EnvVarName), s"$field: '$n' must match $EnvVarName") ++
      check(
        !n.startsWith(ConfigLoader.EnvPrefix),
        s"$field: '$n' must not start with ${ConfigLoader.EnvPrefix}"
      )

  private def inRange(field: String, n: Int, min: Int, max: Int): List[String] =
    check(n >= min && n <= max, s"$field: $n is outside $min..$max")

  // ---- Phase 9 optional sections -------------------------------------------------------------

  private def llm(l: LlmSettings): List[String] = {
    val providerRule = l.providerKind match {
      case Left(msg)                    => List(s"llm.provider: $msg")
      case Right(LlmProvider.Anthropic) =>
        check(l.apiKeyEnvVar.nonEmpty, "llm.apiKeyEnvVar: required for provider anthropic")
      case Right(_) => Nil
    }
    providerRule ++
      l.apiKeyEnvVar.toList.flatMap(apiKeyEnvVarName("llm.apiKeyEnvVar", _)) ++
      inRange("llm.maxOutputTokens", l.maxOutputTokens, 1, LlmSettings.MaxOutputTokens) ++
      positive("llm.requestTimeoutMillis", l.requestTimeoutMillis) ++
      retries("llm", l.maxRetries, l.retryBaseDelayMillis, l.retryMaxDelayMillis) ++
      positive("llm.requestsPerMinute", l.requestsPerMinute) ++
      inRange(
        "llm.maxStructuredRetries",
        l.maxStructuredRetries,
        0,
        LlmSettings.MaxStructuredRetries
      ) ++
      positive("llm.contextTokenBudget", l.contextTokenBudget)
  }

  private def runtime(r: RuntimeSettings, windowLabels: List[String]): List[String] =
    check(
      windowLabels.contains(r.defaultWindowLabel),
      s"runtime.defaultWindowLabel: '${r.defaultWindowLabel}' is not a graph.windows label " +
        s"(expected one of: ${windowLabels.mkString(", ")})"
    ) ++
      inRange(
        "runtime.rankingCacheTopK",
        r.rankingCacheTopK,
        1,
        RuntimeSettings.MaxRankingCacheTopK
      ) ++
      inRange("runtime.maxBundleItems", r.maxBundleItems, 1, RuntimeSettings.MaxBundleItems) ++
      inRange("runtime.maxPathDepth", r.maxPathDepth, 1, RuntimeSettings.MaxPathDepth) ++
      inRange(
        "runtime.graphResultLimit",
        r.graphResultLimit,
        1,
        RuntimeSettings.MaxGraphResultLimit
      ) ++
      inRange(
        "runtime.graphTimeoutMillis",
        r.graphTimeoutMillis,
        1,
        RuntimeSettings.MaxGraphTimeoutMillis
      ) ++
      inRange(
        "runtime.maxResearcherNames",
        r.maxResearcherNames,
        1,
        RuntimeSettings.MaxResearcherNames
      )
}
