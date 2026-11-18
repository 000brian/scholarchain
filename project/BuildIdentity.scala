import java.io.File

import scala.sys.process.{Process, ProcessLogger}
import scala.util.control.NonFatal

import sbt._

/** Build identity embedded in every assembled jar as `scholarchain-build-info.properties`.
  *
  * The build commit is the newest commit that changed a build input (Phase 13 "two identity
  * rules"). The path list below must stay equal to the one in scripts/lib/identity.sh;
  * scripts/package.sh compares the jars' buildCommit with its own value, so a drift fails there.
  *
  * Keys, one per line, in this order, no timestamp: buildCommit 40 lowercase hex, or `unknown` when
  * git is unavailable or no commit matches buildDirty `true` when
  * `git status --porcelain --untracked-files=normal -- PATHS` prints anything (gitignored files
  * never count); also `true` when git is unavailable module the sbt project id of the assembled
  * project version the build version
  */
object BuildIdentity {
  val ResourceName: String = "scholarchain-build-info.properties"

  /** Build inputs, relative to the repository root (also in scripts/lib/identity.sh). */
  val InputPaths: Seq[String] = Seq(
    "build.sbt",
    "project",
    "modules",
    "prompts",
    "docker",
    "config",
    ".scalafmt.conf",
    ".jvmopts",
    ".sbtopts"
  )

  val Keys: Seq[String] = Seq("buildCommit", "buildDirty", "module", "version")

  final case class Identity(commit: String, dirty: Boolean)

  private val Sha40 = "[0-9a-f]{40}"

  private def git(base: File, args: Seq[String]): Option[String] =
    try {
      val out = new StringBuilder
      val code = Process("git" +: args, base).!(
        ProcessLogger(line => out.append(line).append('\n'), _ => ())
      )
      if (code == 0) Some(out.toString) else None
    } catch { case NonFatal(_) => None }

  /** Reads the identity of the working tree at `base`. Runs two read-only git commands;
    * `--no-optional-locks` keeps `git status` from refreshing the index.
    */
  def read(base: File): Identity = {
    val commit = git(base, Seq("log", "-1", "--format=%H", "--") ++ InputPaths)
      .map(_.trim)
      .filter(_.matches(Sha40))
    val status = git(
      base,
      Seq("--no-optional-locks", "status", "--porcelain", "--untracked-files=normal", "--") ++
        InputPaths
    )
    commit match {
      case Some(c) => Identity(c, status.forall(_.trim.nonEmpty))
      case None    => Identity("unknown", dirty = true)
    }
  }

  def render(identity: Identity, module: String, version: String): String =
    s"buildCommit=${identity.commit}\nbuildDirty=${identity.dirty}\nmodule=$module\nversion=$version\n"

  /** Writes the resource under `dir`, only when its content changed, and returns it. */
  def generate(dir: File, content: String): Seq[File] = {
    val file = dir / ResourceName
    if (!file.isFile || IO.read(file) != content) IO.write(file, content)
    Seq(file)
  }

  /** Problems with a resource read from a jar, compared with this invocation's identity. */
  def problems(
      content: String,
      module: String,
      version: String,
      current: Identity
  ): Seq[String] = {
    val lines = content.split("\n", -1).toList match {
      case init :+ "" => init
      case all        => all
    }
    val pairs = lines.map { l =>
      val i = l.indexOf('=')
      if (i <= 0) (l, None) else (l.substring(0, i), Some(l.substring(i + 1)))
    }
    val malformed = pairs.collect { case (l, None) => s"malformed line '$l'" }
    val keys = pairs.collect { case (k, Some(_)) => k }
    val values = pairs.collect { case (k, Some(v)) => k -> v }.toMap
    val keyProblems =
      if (keys != Keys) Seq(s"keys are ${keys.mkString(",")}; expected ${Keys.mkString(",")}")
      else Nil
    val valueProblems = Seq(
      values.get("buildCommit").filterNot(v => v.matches(Sha40) || v == "unknown").map { v =>
        s"buildCommit '$v' is neither 40 hex nor unknown"
      },
      values.get("buildDirty").filterNot(v => v == "true" || v == "false").map { v =>
        s"buildDirty '$v' is neither true nor false"
      },
      values.get("module").filterNot(_ == module).map(v => s"module '$v'; expected '$module'"),
      values.get("version").filterNot(_ == version).map(v => s"version '$v'; expected '$version'"),
      if (keyProblems.isEmpty && malformed.isEmpty && content != render(current, module, version))
        Some(
          s"stale: records ${values.getOrElse("buildCommit", "?")}/dirty=${values.getOrElse("buildDirty", "?")}; " +
            s"this build is ${current.commit}/dirty=${current.dirty}"
        )
      else None
    ).flatten
    malformed ++ keyProblems ++ valueProblems
  }
}
