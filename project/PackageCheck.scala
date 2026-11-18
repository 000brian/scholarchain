import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.jar.{Attributes, JarFile}

import scala.collection.JavaConverters._

import sbt._

/** `sbt packageCheck`: opens the assembled jars and returns one line per problem.
  *
  * The class lists are fixed by the Phase 13 work order (decision 5 item 10). Each class is looked
  * for in the jar that runs it. The task runs no Docker and no network.
  */
object PackageCheck {

  // Hadoop jar (EMR step names the main class; the manifest must not).
  val HadoopClasses: Seq[String] = Seq("org.scholarchain.hadoop.pipeline.ScholarChainHadoop")

  // Entity-resolution jar.
  val ErClasses: Seq[String] = Seq("org.scholarchain.er.run.ErMain")

  // Spark jar (spark-submit --class).
  val SparkClasses: Seq[String] =
    Seq("org.scholarchain.spark.GraphAnalyticsJob", "org.scholarchain.spark.JsonlExport")

  // Services jar, the image's app.jar (four services and the ops task).
  val ServicesMainClass: String = "org.scholarchain.services.Main"
  val ServicesClasses: Seq[String] = Seq(
    ServicesMainClass,
    "org.scholarchain.storage.load.StorageLoadMain",
    "org.scholarchain.rag.ingest.RagIngestJob"
  )

  /** Provided by the EMR Spark runtime; never inside the spark jar. */
  val SparkForbiddenPrefixes: Seq[String] = Seq("org/apache/spark/", "org/apache/hadoop/", "scala/")

  /** HTTP clients other than url-connection-client; never inside the services jar. */
  val HttpClientForbiddenPrefixes: Seq[String] = Seq(
    "org/apache/http/",
    "org/apache/hc/",
    "io/netty/",
    "software/amazon/awssdk/http/apache/",
    "software/amazon/awssdk/http/apache5/",
    "software/amazon/awssdk/http/nio/netty/"
  )

  val SdkHttpServiceFile: String = "META-INF/services/software.amazon.awssdk.http.SdkHttpService"
  val UrlConnectionService: String =
    "software.amazon.awssdk.http.urlconnection.UrlConnectionSdkHttpService"

  def classEntry(className: String): String = className.replace('.', '/') + ".class"

  /** A service-loader or SDK interceptor file whose lines must be merged, never first-wins. */
  def isMergedListFile(path: String): Boolean =
    (path.startsWith("META-INF/services/") && path.length > "META-INF/services/".length &&
      !path.endsWith("/")) || path.split('/').lastOption.contains("execution.interceptors")

  /** Non-empty lines without comments, as a service loader reads them. */
  def listLines(text: String): Set[String] =
    text.split("\n").map(_.takeWhile(_ != '#').trim).filter(_.nonEmpty).toSet

  final class Jar(val label: String, val file: File) {
    private val jar = new JarFile(file)
    val names: Set[String] = jar.entries().asScala.map(_.getName).toSet
    val mainClass: Option[String] =
      Option(jar.getManifest).flatMap(m =>
        Option(m.getMainAttributes.getValue(Attributes.Name.MAIN_CLASS))
      )
    def bytes(name: String): Option[Array[Byte]] =
      Option(jar.getJarEntry(name)).map { e =>
        val in = jar.getInputStream(e)
        try in.readAllBytes()
        finally in.close()
      }
    def text(name: String): Option[String] = bytes(name).map(new String(_, StandardCharsets.UTF_8))
    def close(): Unit = jar.close()
  }

  def requireClasses(jar: Jar, classes: Seq[String]): Seq[String] =
    classes.filterNot(c => jar.names(classEntry(c))).map(c => s"${jar.label}: missing class $c")

  def forbidPrefixes(jar: Jar, prefixes: Seq[String], what: String): Seq[String] =
    prefixes.flatMap { p =>
      val hits = jar.names.filter(n => n.startsWith(p) && !n.endsWith("/"))
      if (hits.isEmpty) Nil
      else Seq(s"${jar.label}: contains $what under $p (${hits.size} entries, e.g. ${hits.min})")
    }

  def buildInfo(
      jar: Jar,
      module: String,
      version: String,
      current: BuildIdentity.Identity
  ): Seq[String] =
    jar.text(BuildIdentity.ResourceName) match {
      case None          => Seq(s"${jar.label}: missing ${BuildIdentity.ResourceName}")
      case Some(content) =>
        BuildIdentity.problems(content, module, version, current).map { p =>
          s"${jar.label}: ${BuildIdentity.ResourceName}: $p"
        }
    }

  /** Merged list files: every line any input carries must be in the assembled file. */
  def mergedLists(jar: Jar, inputs: Seq[File]): Seq[String] = {
    val expected = scala.collection.mutable.Map.empty[String, Set[String]]
    def add(path: String, text: String): Unit =
      expected(path) = expected.getOrElse(path, Set.empty) ++ listLines(text)
    inputs.foreach { in =>
      if (in.isDirectory)
        (in ** AllPassFilter).get.filter(_.isFile).foreach { f =>
          IO.relativize(in, f)
            .map(_.replace(File.separatorChar, '/'))
            .filter(isMergedListFile)
            .foreach { rel =>
              add(rel, IO.read(f))
            }
        }
      else if (in.isFile && in.getName.endsWith(".jar")) {
        val jf = new JarFile(in)
        try
          jf.entries().asScala.filter(e => !e.isDirectory && isMergedListFile(e.getName)).foreach {
            e =>
              val is = jf.getInputStream(e)
              try add(e.getName, new String(is.readAllBytes(), StandardCharsets.UTF_8))
              finally is.close()
          }
        finally jf.close()
      }
    }
    expected.toSeq.sortBy(_._1).flatMap { case (path, lines) =>
      jar.text(path) match {
        case None    => Seq(s"${jar.label}: missing merged file $path")
        case Some(t) =>
          val missing = lines -- listLines(t)
          if (missing.isEmpty) Nil
          else Seq(s"${jar.label}: $path lacks ${missing.toSeq.sorted.mkString(", ")} (not merged)")
      }
    }
  }

  /** Every prompts/runtime file at its classpath path, byte-equal; no extra entries; every leftover
    * copy in rag's src/main/resources equal to its source.
    */
  def prompts(jar: Jar, sourceDir: File, copiesDir: File): Seq[String] = {
    val sources = PromptResources.sourceFiles(sourceDir)
    val prefix = PromptResources.ClasspathDir + "/"
    val inJar = sources.flatMap { case (rel, src) =>
      val entry = prefix + rel
      jar.bytes(entry) match {
        case None => Seq(s"${jar.label}: missing prompt $entry")
        case Some(b) if !java.util.Arrays.equals(b, Files.readAllBytes(src.toPath)) =>
          Seq(s"${jar.label}: prompt $entry differs from prompts/runtime/$rel")
        case _ => Nil
      }
    }
    val wanted = sources.map(prefix + _._1).toSet
    val extra = jar.names.toSeq.sorted
      .filter(n => n.startsWith(prefix) && !n.endsWith("/") && !wanted(n))
      .map(n => s"${jar.label}: prompt $n has no source under prompts/runtime")
    val sourceMap = sources.toMap
    val copies = PromptResources.sourceFiles(copiesDir).flatMap { case (rel, copy) =>
      sourceMap.get(rel) match {
        case None => Seq(s"rag resources: copy $rel has no source under prompts/runtime")
        case Some(src)
            if !java.util.Arrays.equals(
              Files.readAllBytes(copy.toPath),
              Files.readAllBytes(src.toPath)
            ) =>
          Seq(s"rag resources: copy $rel differs from prompts/runtime/$rel")
        case _ => Nil
      }
    }
    val none = if (sources.isEmpty) Seq(s"prompts/runtime has no files") else Nil
    none ++ inJar ++ extra ++ copies
  }

  final case class Inputs(
      hadoop: File,
      er: File,
      spark: File,
      services: File,
      servicesClasspath: Seq[File],
      promptSource: File,
      promptCopies: File,
      version: String,
      identity: BuildIdentity.Identity
  )

  /** All problems, in a fixed order. Empty means `packageCheck: ok`. */
  def run(in: Inputs): Seq[String] = {
    val jars = Seq(
      new Jar("hadoop jar", in.hadoop),
      new Jar("entity-resolution jar", in.er),
      new Jar("spark jar", in.spark),
      new Jar("services jar", in.services)
    )
    try {
      val Seq(hadoop, er, spark, services) = jars
      val hadoopProblems = requireClasses(hadoop, HadoopClasses) ++
        hadoop.mainClass.map(m => s"hadoop jar: manifest has Main-Class $m (must have none)").toSeq
      val erProblems = requireClasses(er, ErClasses)
      val sparkProblems = requireClasses(spark, SparkClasses) ++
        forbidPrefixes(spark, SparkForbiddenPrefixes, "runtime-provided classes")
      val servicesProblems =
        (if (services.mainClass.contains(ServicesMainClass)) Nil
         else
           Seq(
             s"services jar: Main-Class is ${services.mainClass.getOrElse("absent")}; expected $ServicesMainClass"
           )) ++
          requireClasses(services, ServicesClasses) ++
          (if (services.names("logback.xml")) Nil else Seq("services jar: missing logback.xml")) ++
          prompts(services, in.promptSource, in.promptCopies) ++
          mergedLists(services, in.servicesClasspath) ++
          (if (services.text(SdkHttpServiceFile).exists(t => listLines(t)(UrlConnectionService)))
             Nil
           else
             Seq(s"services jar: $SdkHttpServiceFile does not register $UrlConnectionService")) ++
          forbidPrefixes(
            services,
            HttpClientForbiddenPrefixes,
            "non-url-connection HTTP client classes"
          )
      val infoProblems = Seq(
        hadoop -> "hadoop",
        er -> "entityResolution",
        spark -> "sparkAnalytics",
        services -> "services"
      ).flatMap { case (j, module) => buildInfo(j, module, in.version, in.identity) }
      hadoopProblems ++ erProblems ++ sparkProblems ++ servicesProblems ++ infoProblems
    } finally jars.foreach(_.close())
  }
}
