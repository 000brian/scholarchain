package org.scholarchain.storage.emulator

import java.net.URI
import java.nio.file.{Files, Path, Paths}

/** The one emulator safety contract (Phase 16, decision 6.6), the same rules, names and order as
  * `scripts/emulator/lib.sh` `emu_safety_check` (infra-engineer). Every emulator client and test
  * (storage and services, `RequiresEmulator`) calls [[check]] before any client is built; a refusal
  * names the first failing rule only, never a value.
  *
  * Rules, in order:
  *   - `endpoint`: `SCHOLARCHAIN_EMULATOR_ENDPOINT` is exactly `https://127.0.0.1:9297` (no
  *     `localhost`, no other host or port)
  *   - `aws-endpoint-url`: `AWS_ENDPOINT_URL` equals it
  *   - `credentials-file`: `HOME` is absolute and `AWS_SHARED_CREDENTIALS_FILE` is exactly
  *     `$HOME/.aws/emulator/9297/emulator_credentials`
  *   - `credentials-file-exists`: that file exists (a regular file, like `[ -f ]`)
  *   - `config-file`: `AWS_CONFIG_FILE` is exactly `$HOME/.aws/emulator/9297/config`
  *   - `unset:NAME`: each of the eight variables below is unset; **set to an empty value counts as
  *     set** (lib.sh `${!v+x}`)
  *   - `config-file-path`: `SCHOLARCHAIN_CONFIG_FILE` is set and absolute (starts with `/`)
  *   - `results-dir-path`: `SCHOLARCHAIN_EMULATOR_RESULTS_DIR` is absolute when it is set (even to
  *     an empty value); when `resultsDirRequired` it must be set
  *
  * `HOME` comes from the environment map, as in the scripts (not the JVM's `user.home`). Pure: the
  * file check is a parameter, so unit tests use fake environment maps and no network or file.
  */
object EmulatorSafety {

  val Endpoint: String = "https://127.0.0.1:9297"
  val Port: Int = 9297

  val EndpointVar = "SCHOLARCHAIN_EMULATOR_ENDPOINT"
  val AwsEndpointUrlVar = "AWS_ENDPOINT_URL"
  val CredentialsFileVar = "AWS_SHARED_CREDENTIALS_FILE"
  val ConfigFileVar = "AWS_CONFIG_FILE"
  val ScholarchainConfigVar = "SCHOLARCHAIN_CONFIG_FILE"
  val ResultsDirVar = "SCHOLARCHAIN_EMULATOR_RESULTS_DIR"
  val RunIdVar = "SCHOLARCHAIN_EMULATOR_RUN_ID"

  /** Must be unset (lib.sh `EMU_UNSET_VARS`, same order). */
  val UnsetVariables: List[String] = List(
    "AWS_PROFILE",
    "AWS_ACCESS_KEY_ID",
    "AWS_SECRET_ACCESS_KEY",
    "AWS_SESSION_TOKEN",
    "AWS_ROLE_ARN",
    "AWS_WEB_IDENTITY_TOKEN_FILE",
    "AWS_CONTAINER_CREDENTIALS_RELATIVE_URI",
    "AWS_CONTAINER_CREDENTIALS_FULL_URI"
  )

  /** Rule names (lib.sh `EMU_FAILED_RULE` values). */
  object Rules {
    val Endpoint = "endpoint"
    val AwsEndpointUrl = "aws-endpoint-url"
    val CredentialsFile = "credentials-file"
    val CredentialsFileExists = "credentials-file-exists"
    val ConfigFile = "config-file"
    def unset(name: String): String = s"unset:$name"
    val ConfigFilePath = "config-file-path"
    val ResultsDirPath = "results-dir-path"

    /** Every rule name, in check order (the eight `unset:` rules in [[UnsetVariables]] order). */
    val All: List[String] =
      List(Endpoint, AwsEndpointUrl, CredentialsFile, CredentialsFileExists, ConfigFile) ++
        UnsetVariables.map(unset) ++ List(ConfigFilePath, ResultsDirPath)
  }

  /** Rules the Scala suites add after the contract passed, before any client is built (the scripts
    * have no counterpart: the AWS CLI reads these files itself). Never part of [[Rules.All]].
    */
  object SuiteRules {
    val CredentialsFileRead = "credentials-file-read"
    val ConfigFileLoad = "config-file-load"
    val ResultsFileExists = "results-file-exists"
  }

  /** What a passed contract gives. Only [[check]] builds one, so no emulator client can be built
    * without the validated loopback endpoint.
    */
  final case class Target private[emulator] (
      endpoint: URI,
      credentialsFile: Path,
      configFile: Path,
      scholarchainConfigFile: Path,
      resultsDir: Option[Path],
      runId: String
  )

  /** Run IDs from `SCHOLARCHAIN_EMULATOR_RUN_ID`: probes.sh's pattern. */
  val RunIdPattern: String = "[A-Za-z0-9._-]{1,64}"

  private def absolute(v: String): Boolean = v.startsWith("/")

  /** The contract over `env`; Left is the first failing rule's name. */
  def check(
      env: Map[String, String],
      fileExists: Path => Boolean = p => Files.isRegularFile(p),
      resultsDirRequired: Boolean = false,
      newRunId: () => String = () => generatedRunId()
  ): Either[String, Target] = {
    def get(k: String): String = env.getOrElse(k, "")
    val home = get("HOME")
    val dir = s"$home/.aws/emulator/$Port"
    if (get(EndpointVar) != Endpoint) Left(Rules.Endpoint)
    else if (get(AwsEndpointUrlVar) != get(EndpointVar)) Left(Rules.AwsEndpointUrl)
    else if (!absolute(home)) Left(Rules.CredentialsFile)
    else if (get(CredentialsFileVar) != s"$dir/emulator_credentials") Left(Rules.CredentialsFile)
    else if (!fileExists(Paths.get(get(CredentialsFileVar)))) Left(Rules.CredentialsFileExists)
    else if (get(ConfigFileVar) != s"$dir/config") Left(Rules.ConfigFile)
    else
      UnsetVariables.find(env.contains) match {
        case Some(v) => Left(Rules.unset(v))
        case None    =>
          if (!absolute(get(ScholarchainConfigVar))) Left(Rules.ConfigFilePath)
          else if (
            (resultsDirRequired || env.contains(ResultsDirVar)) && !absolute(get(ResultsDirVar))
          ) Left(Rules.ResultsDirPath)
          else {
            val runId = env.get(RunIdVar).filter(_.matches(RunIdPattern)).getOrElse(newRunId())
            Right(
              Target(
                URI.create(Endpoint),
                Paths.get(get(CredentialsFileVar)),
                Paths.get(get(ConfigFileVar)),
                Paths.get(get(ScholarchainConfigVar)),
                env.get(ResultsDirVar).map(Paths.get(_)),
                runId
              )
            )
          }
      }
  }

  /** `storage-emu-<UTC stamp>-<4 hex>` (used when `SCHOLARCHAIN_EMULATOR_RUN_ID` is unset or does
    * not match [[RunIdPattern]], as probes.sh does).
    */
  def generatedRunId(): String = {
    val stamp = java.time.format.DateTimeFormatter
      .ofPattern("yyyyMMdd'T'HHmmss'Z'")
      .withZone(java.time.ZoneOffset.UTC)
      .format(java.time.Instant.now())
    f"storage-emu-$stamp-${java.util.concurrent.ThreadLocalRandom.current().nextInt(0x10000)}%04x"
  }

  /** The results folder for a preflight line: set, absolute and an existing folder, as lib.sh
    * writes its preflight line only when the results folder itself is usable.
    */
  def usableResultsDir(env: Map[String, String]): Option[Path] =
    env
      .get(ResultsDirVar)
      .filter(absolute)
      .map(Paths.get(_))
      .filter(p => Files.isDirectory(p))
}
