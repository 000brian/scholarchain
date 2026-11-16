package org.scholarchain.services

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

import org.scholarchain.core.config.ServiceSettings

/** Who called (decision 7i). `full` may call every route; `read-only` only GET routes. */
sealed abstract class CallerClass(val wire: String)
object CallerClass {
  case object Full extends CallerClass("full")
  case object ReadOnly extends CallerClass("read-only")
  case object Anonymous extends CallerClass("anonymous")
}

/** The two API keys, read once at startup from the environment variables NAMED by
  * `service.apiKeyEnvVar` and `service.readOnlyApiKeyEnvVar`. Holds only SHA-256 digests of the
  * values; `toString` shows nothing.
  */
final class ApiKeys private (fullDigest: Array[Byte], readOnlyDigest: Array[Byte]) {

  /** Compares the presented key with both keys in constant time (both comparisons always run, on
    * fixed-length digests). Missing or wrong: Anonymous.
    */
  def classify(presented: Option[String]): CallerClass =
    presented match {
      case None    => CallerClass.Anonymous
      case Some(p) =>
        val d = ApiKeys.digest(p)
        val isFull = MessageDigest.isEqual(d, fullDigest)
        val isReadOnly = MessageDigest.isEqual(d, readOnlyDigest)
        if (isFull) CallerClass.Full
        else if (isReadOnly) CallerClass.ReadOnly
        else CallerClass.Anonymous
    }

  override def toString: String = "ApiKeys(<hidden>)"
}

object ApiKeys {
  private def digest(s: String): Array[Byte] =
    MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))

  /** The keys, or why the process must not start: a variable unset or blank, or both holding the
    * same value. The message names the variable, never a value.
    */
  def load(settings: ServiceSettings, env: String => Option[String]): Either[String, ApiKeys] = {
    def read(name: String): Either[String, String] =
      env(name) match {
        case None                      => Left(s"environment variable $name is not set")
        case Some(v) if v.trim.isEmpty => Left(s"environment variable $name is blank")
        case Some(v)                   => Right(v)
      }
    for {
      full <- read(settings.apiKeyEnvVar)
      ro <- read(settings.readOnlyApiKeyEnvVar)
      _ <- Either.cond(
        full != ro,
        (),
        s"environment variables ${settings.apiKeyEnvVar} and ${settings.readOnlyApiKeyEnvVar} hold the same value"
      )
    } yield new ApiKeys(digest(full), digest(ro))
  }

  /** The raw values, for the log redactor only (never logged or returned). */
  def valuesForRedaction(settings: ServiceSettings, env: String => Option[String]): List[String] =
    List(settings.apiKeyEnvVar, settings.readOnlyApiKeyEnvVar).flatMap(env(_)).filter(_.nonEmpty)
}
