package org.scholarchain.storage.aws

import java.time.Duration

import org.scholarchain.storage.StoreException
import software.amazon.awssdk.awscore.retry.AwsRetryStrategy
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.core.exception.{SdkClientException, SdkException, SdkServiceException}
import software.amazon.awssdk.http.SdkHttpClient
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region

/** Client settings shared by the synchronous AWS adapters. Credentials always come from the default
  * provider chain (the runtime IAM role); nothing here reads a key. The Region comes from
  * configuration.
  *
  * @param maxAttempts
  *   SDK attempts per call (standard retry strategy: throttling and 5xx, exponential backoff)
  */
final case class AwsClientSettings(
    region: String,
    connectTimeoutMillis: Int,
    socketTimeoutMillis: Int,
    apiCallTimeoutMillis: Int,
    maxAttempts: Int
) {
  require(maxAttempts >= 1 && maxAttempts <= 10, "maxAttempts must be in 1..10")
  require(
    apiCallTimeoutMillis > 0 && apiCallTimeoutMillis <= 120000,
    "apiCallTimeoutMillis must be in 1..120000"
  )

  def awsRegion: Region = Region.of(region)

  def httpClient(): SdkHttpClient =
    UrlConnectionHttpClient
      .builder()
      .connectionTimeout(Duration.ofMillis(connectTimeoutMillis.toLong))
      .socketTimeout(Duration.ofMillis(socketTimeoutMillis.toLong))
      .build()

  def overrides(): ClientOverrideConfiguration =
    ClientOverrideConfiguration
      .builder()
      .apiCallTimeout(Duration.ofMillis(apiCallTimeoutMillis.toLong))
      .retryStrategy(
        AwsRetryStrategy.standardRetryStrategy().toBuilder().maxAttempts(maxAttempts).build()
      )
      .build()
}

object AwsErrors {

  /** Throttling, HTTP 5xx and client-side timeouts or I/O errors are transient; everything else
    * (validation, access denied, not found) is permanent.
    */
  def wrap(e: Throwable): StoreException = e match {
    case s: StoreException      => s
    case s: SdkServiceException =>
      val transient = s.isThrottlingException || s.statusCode == 429 || s.statusCode >= 500
      new StoreException(
        s"${s.getClass.getSimpleName} (HTTP ${s.statusCode}): ${s.getMessage}",
        transient,
        s
      )
    case c: SdkClientException =>
      new StoreException(s"SdkClientException: ${c.getMessage}", c.retryable(), c)
    case s: SdkException =>
      new StoreException(s"${s.getClass.getSimpleName}: ${s.getMessage}", s.retryable(), s)
    case other =>
      new StoreException(s"${other.getClass.getSimpleName}: ${other.getMessage}", false, other)
  }

  /** Runs `f`, turning every SDK exception into a [[StoreException]]. Use [[recover]] instead when
    * some service exceptions are expected outcomes: a `catch` around `guard` never sees them,
    * because they are already wrapped.
    */
  def guard[A](f: => A): A =
    try f
    catch { case e: SdkException => throw wrap(e) }

  /** Like [[guard]], but the SDK exceptions matched by `expected` (for example a DynamoDB
    * conditional-check failure, S3 `NoSuchKey`, S3 Vectors `NotFound`) become ordinary results.
    * Every other SDK exception is still wrapped as a [[StoreException]] with the [[wrap]] retry
    * class.
    */
  def recover[A](expected: PartialFunction[SdkException, A])(f: => A): A =
    try f
    catch {
      case e: SdkException if expected.isDefinedAt(e) => expected(e)
      case e: SdkException                            => throw wrap(e)
    }
}
