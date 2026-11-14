package org.scholarchain.hadoop

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path as JPath, Paths}

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.scholarchain.hadoop.common.{JobResult, JobRunner, Settings}

/** Local (LocalJobRunner, local file system) Hadoop configuration and repository paths. */
object TestSupport {

  /** Repository root: the nearest ancestor holding the dataset manifest. */
  lazy val repoRoot: JPath = {
    var p = Paths.get("").toAbsolutePath
    while (p != null && !Files.exists(p.resolve("datasets/dataset-manifest.yaml"))) p = p.getParent
    require(p != null, "repository root (datasets/dataset-manifest.yaml) not found")
    p
  }

  def sample(rel: String): Path = new Path(repoRoot.resolve("datasets/samples").resolve(rel).toUri)

  def tempDir(prefix: String): JPath = {
    val base = repoRoot.resolve("modules/hadoop/target/test-work")
    Files.createDirectories(base)
    Files.createTempDirectory(base, prefix)
  }

  def localConf(work: JPath, overrides: (String, String)*): Configuration = {
    val conf = new Configuration(false)
    conf.set("fs.defaultFS", "file:///")
    conf.set("mapreduce.framework.name", "local")
    conf.set("hadoop.tmp.dir", work.resolve("hadoop-tmp").toString)
    conf.set("mapreduce.jobtracker.staging.root.dir", work.resolve("staging").toString)
    conf.set("mapreduce.cluster.local.dir", work.resolve("local").toString)
    conf.set("mapreduce.local.map.tasks.maximum", "1")
    conf.set("mapreduce.local.reduce.tasks.maximum", "1")
    conf.set("mapreduce.task.io.sort.mb", "16")
    conf.set(Settings.Keys.CodeVersion, "test")
    overrides.foreach { case (k, v) => conf.set(k, v) }
    conf
  }

  def writeFile(dir: JPath, name: String, content: String): Path = {
    val f = dir.resolve(name)
    Files.createDirectories(f.getParent)
    Files.write(f, content.getBytes(StandardCharsets.UTF_8))
    new Path(f.toUri)
  }

  def lines(conf: Configuration, r: JobResult, sub: String): List[String] =
    JobRunner.readPartLines(conf, r.dir(sub))
}
