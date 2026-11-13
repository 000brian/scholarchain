package org.scholarchain.core.config

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

import scala.collection.immutable.ListMap
import scala.util.control.NonFatal

import org.scholarchain.core.{ContractError, SchemaVersions, Validator}
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
    val known = keys.map(_.path).toSet + VersionKey
    val knownEnv = keys.map(_.envVar).toSet

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

    val resolved: List[(ConfigKey, Either[String, (Any, ConfigOrigin)])] = keys.map { key =>
      val fromEnv = env.get(key.envVar).map { raw =>
        val where = s"${key.envVar}"
        if (raw.trim.isEmpty) Left(s"$where: set but empty")
        else
          envValue(key, raw)
            .flatMap(decode(key, _, where))
            .map(_ -> ConfigOrigin.Environment(where))
      }
      val result = fromEnv.getOrElse {
        flat.get(key.path) match {
          case Some(v) =>
            decode(key, v, s"$fileName: ${key.path}").map(_ -> ConfigOrigin.File(fileName))
          case None =>
            Left(s"${key.path}: required; set it in $fileName or in ${key.envVar}")
        }
      }
      key -> result
    }
    val valueErrors = resolved.collect { case (_, Left(e)) => e }
    val structural = versionErrors ++ unknownFile ++ unknownEnv ++ valueErrors
    if (structural.nonEmpty) Left(ContractError(structural))
    else {
      val values = resolved.collect { case (k, Right((v, _))) => k.path -> v }.toMap
      val origins = ListMap(resolved.collect { case (k, Right((_, o))) => k.path -> o }: _*)
      val config = build(flat(VersionKey).num.toInt, values)
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

  private def build(version: Int, v: Map[String, Any]): ScholarChainConfig = {
    def s(k: String): String = v(k).asInstanceOf[String]
    def i(k: String): Int = v(k).asInstanceOf[Int]
    def d(k: String): Double = v(k).asInstanceOf[Double]
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
      check(c.bridge.topK > 0, s"bridge.topK: ${c.bridge.topK} must be > 0")
  }
}
