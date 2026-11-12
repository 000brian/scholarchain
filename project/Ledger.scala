import java.io.{File, FileInputStream}
import java.util.Properties

/** Reads the dependency version ledger, project/build.properties.
  *
  * Every version used by build.sbt comes from here. Missing keys and floating versions fail the
  * build load instead of falling back to a default.
  */
object Ledger {
  val path: String = "project/build.properties"

  private lazy val props: Properties = {
    val ledgerFile = new File(path)
    if (!ledgerFile.isFile)
      sys.error(
        s"Version ledger not found at ${ledgerFile.getAbsolutePath}; run sbt from the repository root."
      )
    val p = new Properties()
    val in = new FileInputStream(ledgerFile)
    try p.load(in)
    finally in.close()
    p
  }

  private val exactVersion = """[0-9][0-9A-Za-z._-]*""".r
  private val pinnedImage = """[a-z0-9][a-z0-9._/-]*:[0-9A-Za-z._-]+@sha256:[0-9a-f]{64}""".r
  private val floating = """(?i).*(latest|snapshot|release|\+|\[|\]|\(|\)|,).*""".r

  private def raw(key: String): String =
    Option(props.getProperty(key)).map(_.trim).filter(_.nonEmpty).getOrElse {
      sys.error(s"$path is missing required key '$key'.")
    }

  /** An exact, non-floating version value. */
  def version(key: String): String = {
    val v = raw(key)
    if (floating.pattern.matcher(v).matches() || !exactVersion.pattern.matcher(v).matches())
      sys.error(s"$path key '$key' has a floating or malformed version '$v'; pin an exact version.")
    v
  }

  /** A container image pinned by tag and sha256 digest. */
  def image(key: String): String = {
    val v = raw(key)
    if (!pinnedImage.pattern.matcher(v).matches() || v.toLowerCase.contains(":latest"))
      sys.error(s"$path key '$key' must be an image pinned by tag and sha256 digest; found '$v'.")
    v
  }

  lazy val java: String = version("version.java")
  lazy val scala3: String = version("version.scala3")
  lazy val scala213: String = version("version.scala213")
  lazy val spark: String = version("version.spark")
  lazy val hadoop: String = version("version.hadoop")
  lazy val upickle: String = version("version.upickle")
  lazy val munit: String = version("version.munit")
  lazy val slf4j: String = version("version.slf4j")
  lazy val logback: String = version("version.logback")
  lazy val scalafmt: String = version("version.scalafmt")
  lazy val javaRuntimeImage: String = image("image.java-runtime")

  /** Validates every key eagerly so a bad ledger fails at load time. */
  def validateAll(): Unit = {
    val _ = Seq(
      version("sbt.version"),
      version("version.sbt-scalafmt"),
      version("version.sbt-assembly"),
      version("version.sbt-projectmatrix"),
      java,
      scala3,
      scala213,
      spark,
      hadoop,
      upickle,
      munit,
      slf4j,
      logback,
      scalafmt,
      javaRuntimeImage
    )
  }
}
