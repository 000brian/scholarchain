package org.scholarchain.storage.emulator

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.util.control.NonFatal

import org.scholarchain.storage.StorageConfig
import org.scholarchain.storage.aws.{AwsClientSettings, AwsSdkClients}
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.core.client.config.{
  ClientOverrideConfiguration,
  SdkAdvancedClientOption
}
import software.amazon.awssdk.http.SdkHttpClient
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3vectors.S3VectorsClient
import software.amazon.awssdk.services.sqs.SqsClient

/** SDK clients for the AWS Emulator only (Phase 16, decision 6.6), kept apart from the production
  * builder [[AwsSdkClients.build]], which is unchanged.
  *
  * Every client gets:
  *   - an explicit `endpointOverride` with the loopback URL of a validated
  *     [[EmulatorSafety.Target]] (the only way to call [[forEmulator]]; nothing here reads
  *     `AWS_ENDPOINT_URL` to choose the endpoint, and the explicit override wins over the SDK's own
  *     environment lookup);
  *   - `SdkAdvancedClientOption.DISABLE_HOST_PREFIX_INJECTION` = true;
  *   - an explicit `StaticCredentialsProvider` from the two keys of the validated emulator
  *     credentials file (never the default chain, a profile, system properties, the container or
  *     instance endpoints, or a role);
  *   - the production timeouts and SDK `maxAttempts = 1` ([[AwsClientSettings]]).
  *
  * S3 uses path-style addressing, set explicitly (the documentation: an IP endpoint puts the bucket
  * in the path; the emulator refuses a bucket in the host name,
  * https://docs.aws.amazon.com/emulator/latest/userguide/point-sdk-to-emulator.html). Neptune is
  * never built: the emulator suites leave Neptune out (see `EmulatorAdaptersEmulatorSuite`).
  *
  * Building makes no network call. Thread-safe (stateless).
  */
object EmulatorClients {

  /** The production overrides plus host-prefix injection off. */
  def overrides(settings: AwsClientSettings): ClientOverrideConfiguration =
    settings
      .overrides()
      .toBuilder()
      .putAdvancedOption(
        SdkAdvancedClientOption.DISABLE_HOST_PREFIX_INJECTION,
        java.lang.Boolean.TRUE
      )
      .build()

  /** The S3, DynamoDB, S3 Vectors and SQS clients for `target`; `neptune` is always None.
    *
    * @param httpClient
    *   default: a new url-connection client per SDK client (the forked EmulatorTest JVM trusts the
    *   emulator certificate through its trust store, build.sbt); unit tests pass a stub transport
    */
  def forEmulator(
      target: EmulatorSafety.Target,
      settings: AwsClientSettings,
      config: StorageConfig,
      credentials: StaticCredentialsProvider,
      httpClient: Option[SdkHttpClient] = None
  ): AwsSdkClients = {
    require(target != null, "a validated emulator target is required")
    require(credentials != null, "emulator credentials are required")
    require(settings.region == config.region, "client Region must be the configured aws.region")
    require(target.endpoint.toString == EmulatorSafety.Endpoint, "not the emulator endpoint")
    def http(): SdkHttpClient = httpClient.getOrElse(settings.httpClient())
    val o = overrides(settings)
    val s3 = S3Client
      .builder()
      .region(settings.awsRegion)
      .endpointOverride(target.endpoint)
      .forcePathStyle(java.lang.Boolean.TRUE)
      .credentialsProvider(credentials)
      .httpClient(http())
      .overrideConfiguration(o)
      .build()
    val dynamo = DynamoDbClient
      .builder()
      .region(settings.awsRegion)
      .endpointOverride(target.endpoint)
      .credentialsProvider(credentials)
      .httpClient(http())
      .overrideConfiguration(o)
      .build()
    val vectors = S3VectorsClient
      .builder()
      .region(settings.awsRegion)
      .endpointOverride(target.endpoint)
      .credentialsProvider(credentials)
      .httpClient(http())
      .overrideConfiguration(o)
      .build()
    val sqs = SqsClient
      .builder()
      .region(settings.awsRegion)
      .endpointOverride(target.endpoint)
      .credentialsProvider(credentials)
      .httpClient(http())
      .overrideConfiguration(o)
      .build()
    AwsSdkClients(s3, dynamo, vectors, None, Some(sqs))
  }

  /** The access key ID and secret of the validated emulator credentials file (AWS shared
    * credentials format): the `[default]` profile, or the only profile when there is one. Left
    * names the problem without any value. The keys are never logged or written.
    */
  def credentials(target: EmulatorSafety.Target): Either[String, StaticCredentialsProvider] =
    readCredentials(target.credentialsFile)

  private[emulator] def readCredentials(file: Path): Either[String, StaticCredentialsProvider] = {
    val text =
      try Right(new String(Files.readAllBytes(file), StandardCharsets.UTF_8))
      catch {
        case NonFatal(e) =>
          Left(s"cannot read the emulator credentials file (${e.getClass.getSimpleName})")
      }
    text.flatMap(parseCredentials)
  }

  private[emulator] def parseCredentials(
      text: String
  ): Either[String, StaticCredentialsProvider] = {
    val Section = """\s*\[\s*([^\]]+?)\s*\]\s*""".r
    val KeyValue = """\s*([A-Za-z0-9_]+)\s*=\s*(\S+)\s*""".r
    var current: Option[String] = None
    val profiles = scala.collection.mutable.LinkedHashMap.empty[String, Map[String, String]]
    text.linesIterator.foreach {
      case l if l.trim.isEmpty || l.trim.startsWith("#") || l.trim.startsWith(";") => ()
      case Section(name)                                                           =>
        current = Some(name); profiles.getOrElseUpdate(name, Map.empty); ()
      case KeyValue(k, v) =>
        current.foreach(p => profiles.update(p, profiles(p) + (k.toLowerCase -> v)))
      case _ => ()
    }
    val chosen =
      profiles.get("default").orElse(if (profiles.size == 1) profiles.values.headOption else None)
    for {
      p <- chosen.toRight("the emulator credentials file has no [default] profile")
      id <- p
        .get("aws_access_key_id")
        .toRight("the emulator credentials file has no aws_access_key_id")
      secret <- p
        .get("aws_secret_access_key")
        .toRight("the emulator credentials file has no aws_secret_access_key")
    } yield StaticCredentialsProvider.create(AwsBasicCredentials.create(id, secret))
  }
}
