package org.scholarchain.er

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import scala.jdk.CollectionConverters.*

/** Leakage boundary (decision 14): no matching code (normalization, features, scoring, rules,
  * clustering, LLM, review-queue generation, the run) imports the evaluation package or names the
  * gold-label location; the policy file holds no label-derived values.
  */
class BoundarySuite extends munit.FunSuite {

  private def moduleDir: Path = {
    val candidates = List(Paths.get("modules/entity-resolution"), Paths.get("."))
    candidates
      .find(p => Files.isDirectory(p.resolve("src/main/scala/org/scholarchain/er")))
      .getOrElse(fail("module sources not found"))
  }

  private def sources(dir: Path): List[(Path, String)] = {
    val s = Files.walk(dir)
    try
      s.iterator.asScala
        .filter(_.toString.endsWith(".scala"))
        .toList
        .map(p => p -> new String(Files.readAllBytes(p), StandardCharsets.UTF_8))
    finally s.close()
  }

  test("matching packages never reference the evaluation package or gold-label paths") {
    val root = moduleDir.resolve("src/main/scala/org/scholarchain/er")
    val matching = sources(root).filterNot { case (p, _) =>
      root.relativize(p).toString.startsWith("eval")
    }
    assert(matching.size >= 15, s"found only ${matching.size} matching sources")
    val forbidden = List(
      "er.eval",
      "import org.scholarchain.er.eval",
      "agent-runs",
      "/gold",
      "GoldLabel",
      "KeyRow"
    )
    matching.foreach { case (p, text) =>
      forbidden.foreach(f => assert(!text.contains(f), s"$p mentions '$f'"))
    }
    val eval = sources(root.resolve("eval"))
    assert(eval.nonEmpty)
  }

  /** Repair m6: references to the eval package that a search for `er.eval` misses, e.g. a
    * package-relative `import eval.Harness` inside `package org.scholarchain.er`, a renaming
    * selector `import org.scholarchain.er.{eval => e}`, or a bare use of an eval type name.
    */
  private val evalReference: List[(String, scala.util.matching.Regex)] = List(
    "a qualified or package-relative eval reference" -> """\beval\s*\.""".r,
    "an eval import selector" -> """\{[^}]*\beval\b[^}]*\}""".r,
    "an eval type name" ->
      """\b(Harness|GoldLabels|GoldLabel|GoldSet|KeyRow|EvalMain|EvalUnit|Metrics|Proportion)\b""".r
  )

  private def evalReferences(text: String): List[String] =
    evalReference.collect { case (what, re) if re.findFirstIn(text).isDefined => what }

  test("the eval-reference detector catches package-relative and renamed references") {
    val planted = List(
      "package org.scholarchain.er\nimport eval.Harness\n",
      "package org.scholarchain.er\nobject X { val h = eval.Harness }\n",
      "package org.scholarchain.er.run\nimport org.scholarchain.er.{eval => e}\n",
      "package org.scholarchain.er\nimport org.scholarchain.er.{ eval }\n",
      "package org.scholarchain.er.run\nimport _root_.org.scholarchain.er.eval.*\n",
      "package org.scholarchain.er.run\nobject Y { def k: KeyRow = ??? }\n"
    )
    planted.foreach(src => assert(evalReferences(src).nonEmpty, src))
    assertEquals(evalReferences("package org.scholarchain.er\n// evaluation. later\n"), Nil)
  }

  test("no non-eval source references the eval package in any form") {
    val root = moduleDir.resolve("src/main/scala/org/scholarchain/er")
    val matching = sources(root).filterNot { case (p, _) =>
      root.relativize(p).toString.startsWith("eval")
    }
    assert(matching.size >= 15)
    matching.foreach { case (p, text) =>
      assertEquals(evalReferences(text), Nil, s"$p")
    }
  }

  test("no compiled non-eval class refers to an eval class") {
    val url = getClass.getClassLoader.getResource("org/scholarchain/er/run/ErRun.class")
    assert(url != null && url.getProtocol == "file", s"compiled classes not found: $url")
    val erDir = Paths.get(url.toURI).getParent.getParent
    val s = Files.walk(erDir)
    val classes =
      try s.iterator.asScala.filter(_.toString.endsWith(".class")).toList
      finally s.close()
    val nonEval = classes.filterNot(p => erDir.relativize(p).toString.startsWith("eval"))
    assert(nonEval.size >= 30, s"found only ${nonEval.size} non-eval classes")
    assert(classes.exists(p => erDir.relativize(p).toString.startsWith("eval")))
    nonEval.foreach { p =>
      // class-file constant pools name referenced classes as org/scholarchain/er/eval/...
      val text = new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1)
      assert(!text.contains("org/scholarchain/er/eval/"), s"$p refers to the eval package")
    }
  }

  test("the policy file contains no label-derived fields") {
    val text = new String(
      Files.readAllBytes(moduleDir.resolve("src/main/resources/er/policy/er-v1.json")),
      StandardCharsets.UTF_8
    )
    List("gold", "MATCH", "NON_MATCH", "precision", "recall").foreach(w =>
      assert(!text.contains(w), s"policy mentions '$w'")
    )
  }
}
