package org.scholarchain.rag.runtime

import java.nio.file.{Files, Path}

import scala.jdk.CollectionConverters._

import org.scholarchain.core.Hashing

/** R3-06: `prompts/runtime` is the one source of the runtime prompts. Every prompt the code can
  * load from the classpath equals its file under `prompts/runtime` byte for byte, the classpath
  * copy is the build-mapped resource (no copy in rag's own resources), and loading fails clearly
  * when a prompt is missing.
  */
class PromptResourcesSuite extends munit.FunSuite {

  private val root: Path = Prompts.locate().fold(e => fail(e), identity)
  private val runtimeDir = root.resolve("runtime")
  private val repo = root.getParent

  private def sourceIds: Set[String] =
    Files
      .list(runtimeDir)
      .iterator()
      .asScala
      .map(_.getFileName.toString)
      .filter(_.endsWith(".md"))
      .map(_.stripSuffix(".md"))
      .toSet

  test("every prompt the code can load equals its prompts/runtime file byte for byte") {
    assertEquals(Prompts.PromptIds.toSet, sourceIds)
    Prompts.PromptIds.foreach { id =>
      val onClasspath = Prompts.resourceBytes(id).fold(e => fail(e), identity)
      val source = Files.readAllBytes(runtimeDir.resolve(s"$id.md"))
      assertEquals(Hashing.sha256Hex(onClasspath), Hashing.sha256Hex(source), id)
      assertEquals(onClasspath.length, source.length, id)
      // and the version the runtime records is the version of the source file
      assertEquals(
        Prompts.loadResource(id).map(_.version),
        Prompts.load(root, id).map(_.version),
        id
      )
    }
  }

  test("the classpath prompts come from the build mapping of prompts/runtime, not from a copy") {
    // R3-06: rag keeps no copy under its own resources
    val copies = repo.resolve("modules/rag/src/main/resources/org/scholarchain/rag/prompts")
    assert(!Files.exists(copies), s"a copy of the prompts still exists at $copies")
    // each prompt resolves to exactly one classpath entry (sbt copies the generated resources of
    // the prompts/runtime mapping into the classes folder; no second copy competes with it)
    Prompts.PromptIds.foreach { id =>
      val urls = getClass.getClassLoader.getResources(Prompts.resourceName(id)).asScala.toList
      assertEquals(urls.size, 1, s"$id: $urls")
    }
  }

  test("a missing prompt fails clearly, naming the prompt, its classpath path and its source") {
    val empty = new ClassLoader(null) {}
    val id = Prompts.Current.synthesisId
    val err = Prompts.loadResource(id, empty).left.getOrElse(fail("expected a Left"))
    assert(err.contains(id), err)
    assert(err.contains(s"/org/scholarchain/rag/prompts/runtime/$id.md"), err)
    assert(err.contains(s"prompts/runtime/$id.md"), err)
    assert(err.contains("missing"), err)
    // a whole prompt set fails closed too, never a partial or empty template
    val set = Prompts.loadPackagedSet(Prompts.Current, empty)
    assert(set.left.exists(_.contains(Prompts.Current.plannerId)), set.toString)
    // an unknown prompt ID on the real classpath
    assert(Prompts.loadResource("no-such-prompt").left.exists(_.contains("missing")))
    assert(Prompts.loadResource("../escape").left.exists(_.contains("bad prompt ID")))
  }

  test("a classpath with one prompt missing fails for that prompt only") {
    val missing = Prompts.Current.plannerId
    val real = getClass.getClassLoader
    val partial = new ClassLoader(null) {
      override def getResourceAsStream(name: String): java.io.InputStream =
        if (name == Prompts.resourceName(missing)) null else real.getResourceAsStream(name)
    }
    assert(Prompts.loadResource(Prompts.Current.synthesisId, partial).isRight)
    val err =
      Prompts.loadPackagedSet(Prompts.Current, partial).left.getOrElse(fail("expected Left"))
    assert(err.contains(missing) && err.contains("missing"), err)
  }
}
