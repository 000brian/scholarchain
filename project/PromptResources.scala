import java.io.File
import java.nio.file.Files

import sbt._

/** R3-06: the folder `prompts/runtime` is the one source of the runtime prompts. The rag project
  * gets them as generated resources under [[ClasspathDir]], the folder rag's `Prompts.ResourceRoot`
  * reads.
  *
  * Copies that still sit at the same path in modules/rag/src/main/resources are removed from the
  * rag resources by the build, so exactly one file exists per classpath path and it comes from
  * prompts/runtime. packageCheck reports every copy that differs from its source.
  */
object PromptResources {

  /** Must equal `org.scholarchain.rag.runtime.Prompts.ResourceRoot` without the leading slash. */
  val ClasspathDir: String = "org/scholarchain/rag/prompts/runtime"

  /** Source files below `sourceDir`, keyed by '/'-separated relative path, sorted. */
  def sourceFiles(sourceDir: File): Seq[(String, File)] =
    if (!sourceDir.isDirectory) Nil
    else
      (sourceDir ** AllPassFilter).get
        .filter(_.isFile)
        .flatMap(f => IO.relativize(sourceDir, f).map(r => r.replace(File.separatorChar, '/') -> f))
        .sortBy(_._1)

  /** Mirrors `sourceDir` into `outDir`: writes a file only when its bytes changed and removes files
    * that no longer have a source. Returns the generated files.
    */
  def sync(sourceDir: File, outDir: File): Seq[File] = {
    val sources = sourceFiles(sourceDir)
    val wanted = sources.map { case (rel, _) => outDir / rel }.toSet
    if (outDir.isDirectory)
      (outDir ** AllPassFilter).get.filter(f => f.isFile && !wanted(f)).foreach(IO.delete)
    sources.map { case (rel, src) =>
      val out = outDir / rel
      val bytes = Files.readAllBytes(src.toPath)
      if (!out.isFile || !java.util.Arrays.equals(Files.readAllBytes(out.toPath), bytes))
        IO.write(out, bytes)
      out
    }
  }
}
