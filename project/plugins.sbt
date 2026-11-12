// sbt plugins. Every plugin version is read from the ledger, project/build.properties.
// sbt always loads the build from the repository root, so the relative path is stable.

val pluginLedger: java.util.Properties = {
  val ledgerFile = new java.io.File("project/build.properties")
  if (!ledgerFile.isFile)
    sys.error(
      s"Version ledger not found at ${ledgerFile.getAbsolutePath}; run sbt from the repository root."
    )
  val props = new java.util.Properties()
  val in = new java.io.FileInputStream(ledgerFile)
  try props.load(in)
  finally in.close()
  props
}

def pluginVersion(key: String): String =
  Option(pluginLedger.getProperty(key)).map(_.trim).filter(_.nonEmpty).getOrElse {
    sys.error(s"project/build.properties is missing the plugin version key '$key'.")
  }

addSbtPlugin("org.scalameta" % "sbt-scalafmt" % pluginVersion("version.sbt-scalafmt"))
addSbtPlugin("com.eed3si9n" % "sbt-assembly" % pluginVersion("version.sbt-assembly"))
addSbtPlugin("com.eed3si9n" % "sbt-projectmatrix" % pluginVersion("version.sbt-projectmatrix"))
