package org.scholarchain.storage.load

import java.io.StringReader
import java.nio.charset.StandardCharsets
import java.util.Properties

import scala.util.control.NonFatal

/** The build identity of the running jar: the classpath resource [[BuildIdentity.ResourceName]]
  * that the build generates only into the assembled jars (keys `buildCommit`, `buildDirty`,
  * `module`, `version`). In module tests the resource is absent and every field is `unknown`; a
  * malformed resource is also `unknown`, with the reason in `status`.
  *
  * @param status
  *   `resource` (read and well formed), `missing` or `malformed: <reason>`
  */
final case class BuildIdentity(
    buildCommit: String,
    buildDirty: String,
    module: String,
    version: String,
    status: String
) {
  def toJson: ujson.Obj = ujson.Obj(
    "buildCommit" -> buildCommit,
    "buildDirty" -> buildDirty,
    "module" -> module,
    "version" -> version,
    "status" -> status
  )
}

object BuildIdentity {
  val ResourceName = "scholarchain-build-info.properties"
  private val Commit = "[0-9a-f]{40}|unknown".r
  private val Text = "[A-Za-z0-9][A-Za-z0-9._+-]{0,99}".r

  def unknown(status: String): BuildIdentity =
    BuildIdentity("unknown", "unknown", "unknown", "unknown", status)

  /** Parses the resource text; Left names the first rule a value breaks (values are not echoed). */
  def parse(text: String): Either[String, BuildIdentity] =
    try {
      val p = new Properties()
      p.load(new StringReader(text))
      def get(k: String) = Option(p.getProperty(k)).map(_.trim).toRight(s"$k is missing")
      for {
        c <- get("buildCommit").filterOrElse(Commit.matches, "buildCommit is not 40 hex or unknown")
        d <- get("buildDirty").filterOrElse(Set("true", "false"), "buildDirty is not true or false")
        m <- get("module").filterOrElse(Text.matches, "module is not a plain name")
        v <- get("version").filterOrElse(Text.matches, "version is not a plain version")
      } yield BuildIdentity(c, d, m, v, "resource")
    } catch { case NonFatal(e) => Left(s"unreadable: ${e.getClass.getSimpleName}") }

  /** The identity on the class path of `loader` (default: this class's loader). */
  def load(loader: ClassLoader = getClass.getClassLoader): BuildIdentity =
    Option(loader.getResourceAsStream(ResourceName)) match {
      case None     => unknown("missing")
      case Some(in) =>
        val text =
          try new String(in.readAllBytes(), StandardCharsets.UTF_8)
          finally in.close()
        parse(text).fold(e => unknown(s"malformed: $e"), identity)
    }
}
