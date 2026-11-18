import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

import sbt._

/** Stages the services image build context (no Docker here).
  *
  * Layout of modules/services/target/docker-context after `services/dockerStage`: app.jar copy of
  * the services assembly that packageCheck opens config/course.json copy of config/course.json
  * context.sha256 `<sha256>  <path>` for both files (shasum -a 256 -c format)
  */
object DockerContext {
  val AppJar: String = "app.jar"
  val ConfigFile: String = "config/course.json"
  val ChecksumFile: String = "context.sha256"

  /** The image platform; explicit, never the Docker default (Fargate ARM64). */
  val Platform: String = "linux/arm64"

  def sha256(file: File): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(Files.readAllBytes(file.toPath))
      .map(b => f"${b & 0xff}%02x")
      .mkString

  /** Deletes `context`, then stages exactly the two files and their checksums. */
  def stage(context: File, appJar: File, config: File): File = {
    if (!config.isFile) sys.error(s"Missing image configuration file $config.")
    IO.delete(context)
    IO.createDirectory(context)
    IO.copyFile(appJar, context / AppJar)
    IO.copyFile(config, context / ConfigFile)
    val lines = Seq(AppJar, ConfigFile).map(p => s"${sha256(context / p)}  $p")
    IO.write(context / ChecksumFile, lines.mkString("", "\n", "\n"))
    context
  }

  /** `docker buildx build` arguments for the staged context (scripts/package.sh uses the same). */
  def buildxArgs(dockerfile: File, runtimeImage: String, tag: String, context: File): Seq[String] =
    Seq(
      "docker",
      "buildx",
      "build",
      "--platform",
      Platform,
      "--load",
      "--file",
      dockerfile.getAbsolutePath,
      "--build-arg",
      s"JAVA_RUNTIME_IMAGE=$runtimeImage",
      "--tag",
      tag,
      context.getAbsolutePath
    )
}
