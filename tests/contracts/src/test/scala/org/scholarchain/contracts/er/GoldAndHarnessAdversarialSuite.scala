package org.scholarchain.contracts.er

import org.scholarchain.er.eval.{GoldLabel, GoldLabels, GoldSet, Harness, KeyRow}

/** Attacks on the strict gold-label reader (decision 14) and hand-checked harness metrics on a
  * clearly synthetic labeled set (decision 15). No real labels; nothing reads docs/agent-runs.
  */
class GoldAndHarnessAdversarialSuite extends munit.FunSuite {

  private def m(i: Int) = f"mention:v1:$i%032x"
  private def c(i: Int) = f"match-candidate:v1:$i%032x"

  /** Queue rows 1..4: pair (m(2i-1), m(2i)), candidate c(i). */
  private val queue: Map[(String, String), Map[String, String]] =
    (1 to 4).map { i =>
      (m(2 * i - 1), m(2 * i)) -> Map(
        "rowNumber" -> i.toString,
        "leftMentionId" -> m(2 * i - 1),
        "rightMentionId" -> m(2 * i),
        "candidateId" -> c(i),
        "left_name" -> s"SYNTHETIC Left $i"
      )
    }.toMap

  private val Header =
    "leftMentionId,rightMentionId,candidateId,label,evidence,note,labeler,labeledAt"
  private def row(
      i: Int,
      label: String,
      evidence: String,
      labeler: String = "SYNTHETIC-LABELER",
      at: String = "2026-11-14"
  ) =
    s"${m(2 * i - 1)},${m(2 * i)},${c(i)},$label,$evidence,SYNTHETIC,$labeler,$at"

  private def parse(lines: String*) = GoldLabels.parse(lines.mkString("\n") + "\n", queue)

  private def rejects(fragment: String, lines: String*)(implicit loc: munit.Location): Unit =
    parse(lines*) match {
      case Right(g)   => fail(s"accepted a bad file (expected '$fragment'): $g")
      case Left(errs) =>
        assert(errs.exists(_.contains(fragment)), s"expected '$fragment' in $errs")
    }

  test("a valid synthetic file parses, CRLF line endings and swapped mention order included") {
    val swapped =
      s"${m(4)},${m(3)},${c(2)},NON_MATCH,research_area,SYNTHETIC,SYNTHETIC-LABELER,2026-11-14T10:00:00Z"
    val text = List(
      Header,
      row(1, "MATCH", "orcid;shared_coauthors"),
      swapped,
      row(3, "UNSURE", ""),
      s"${m(7)},${m(8)},${c(4)},,,,,"
    )
      .mkString("\r\n") + "\r\n"
    val g = GoldLabels.parse(text, queue).fold(e => fail(e.mkString("; ")), identity)
    assertEquals(g.labels.size, 3)
    assertEquals(g.unlabeledRows, 1)
    assertEquals(
      g.labels.find(_.candidateId == c(2)).map(l => (l.leftMentionId, l.rightMentionId)),
      Some((m(3), m(4)))
    )
    assertEquals(
      g.labels.find(_.candidateId == c(1)).get.evidence,
      List("orcid", "shared_coauthors")
    )
  }

  test("unknown, repeated and missing columns are errors") {
    rejects("unknown columns", Header + ",score", row(1, "MATCH", "orcid") + ",0.9")
    rejects("repeated columns", Header + ",label", row(1, "MATCH", "orcid") + ",MATCH")
    rejects(
      "missing columns",
      Header.replace(",labeledAt", ""),
      row(1, "MATCH", "orcid").split(',').init.mkString(",")
    )
    // a spreadsheet byte-order mark is stripped, and the row behind it is still read
    val bom = GoldLabels.parse(
      String.valueOf(0xfeff.toChar) + Header + "\n" + row(1, "MATCH", "orcid") + "\n",
      queue
    )
    assertEquals(bom.map(_.labels.size), Right(1))
  }

  test("unknown label and evidence values are errors (case-sensitive)") {
    rejects("unknown label", Header, row(1, "match", "orcid"))
    rejects("unknown label", Header, row(1, "MAYBE", "orcid"))
    rejects("unknown evidence", Header, row(1, "MATCH", "orcid;gut_feeling"))
    rejects("repeated evidence", Header, row(1, "MATCH", "orcid;orcid"))
    rejects("needs at least one evidence", Header, row(1, "NON_MATCH", ""))
  }

  test("duplicate pairs (in either mention order) and pairs outside the queue are errors") {
    rejects("duplicate pair", Header, row(1, "MATCH", "orcid"), row(1, "NON_MATCH", "orcid"))
    rejects(
      "duplicate pair",
      Header,
      row(1, "MATCH", "orcid"),
      s"${m(2)},${m(1)},${c(1)},MATCH,orcid,SYNTHETIC,SYNTHETIC-LABELER,2026-11-14"
    )
    rejects(
      "not in the review queue",
      Header,
      s"${m(91)},${m(92)},${c(1)},MATCH,orcid,SYNTHETIC,SYNTHETIC-LABELER,2026-11-14"
    )
  }

  test("candidate ID or display value that differs from the queue is an error") {
    rejects(
      "differs from the queue",
      Header,
      s"${m(1)},${m(2)},${c(3)},MATCH,orcid,SYNTHETIC,SYNTHETIC-LABELER,2026-11-14"
    )
    rejects(
      "display column left_name",
      Header + ",left_name",
      row(1, "MATCH", "orcid") + ",SYNTHETIC Edited"
    )
  }

  test("labeler, date, field count and half-filled rows are checked") {
    rejects("labeler is empty", Header, row(1, "MATCH", "orcid", labeler = ""))
    rejects("labeledAt", Header, row(1, "MATCH", "orcid", at = "14/11/2026"))
    rejects("labeledAt", Header, row(1, "MATCH", "orcid", at = "2026-02-30"))
    rejects("fields", Header, row(1, "MATCH", "orcid") + ",extra")
    rejects(
      "label is empty but other label columns are filled",
      Header,
      s"${m(1)},${m(2)},${c(1)},,orcid,,,"
    )
    rejects(
      "malformed CSV",
      Header,
      s"""${m(1)},${m(2)},${c(1)},MATCH,orcid,"unterminated,X,2026-11-14"""
    )
    assert(GoldLabels.parse("", queue).isLeft)
  }

  test("every error is reported, not only the first") {
    parse(Header, row(1, "maybe", "orcid"), row(2, "MATCH", "nonsense")) match {
      case Right(_)   => fail("accepted")
      case Left(errs) => assert(errs.size >= 2, errs.toString)
    }
  }

  // ------------------------------------------------------------------------------------------
  // Harness metrics, checked by hand
  // ------------------------------------------------------------------------------------------

  private val S1 = "path=probabilistic;outcome=accepted;family=f1;band=far"
  private val S2 = "path=probabilistic;outcome=rejected;family=f2;band=far"

  /** (outcome, label, path, p, stratum, evidence) */
  private val units: List[(String, String, String, Option[Double], String, List[String])] = List(
    ("accepted", "MATCH", "probabilistic", Some(0.95), S1, List("shared_coauthors")),
    ("accepted", "MATCH", "probabilistic", Some(0.95), S1, List("dblp_numbered_name")),
    ("accepted", "MATCH", "probabilistic", Some(0.95), S1, List("orcid")),
    ("accepted", "NON_MATCH", "deterministic", None, S1, List("institution")),
    ("rejected", "NON_MATCH", "probabilistic", Some(0.1), S1, List("research_area")),
    ("rejected", "MATCH", "probabilistic", Some(0.2), S2, List("dblp_numbered_name")),
    ("rejected", "NON_MATCH", "probabilistic", Some(0.2), S2, List("other")),
    ("unresolved", "MATCH", "probabilistic", Some(0.5), S2, List("shared_coauthors")),
    ("unresolved", "UNSURE", "probabilistic", Some(0.5), S2, Nil),
    ("conflict", "NON_MATCH", "exact_id", None, S2, List("orcid"))
  )

  private lazy val gold = GoldSet(
    units.zipWithIndex.map { case ((_, label, _, _, _, ev), i) =>
      GoldLabel(
        m(2 * i + 1),
        m(2 * i + 2),
        c(i + 1),
        label,
        ev,
        "SYNTHETIC",
        "SYNTHETIC-LABELER",
        "2026-11-14"
      )
    },
    unlabeledRows = 3
  )
  private lazy val key: Map[(String, String), KeyRow] =
    units.zipWithIndex.map { case ((outcome, _, path, p, s, _), i) =>
      (m(2 * i + 1), m(2 * i + 2)) -> KeyRow(
        m(2 * i + 1),
        m(2 * i + 2),
        c(i + 1),
        List(s -> (if (s == S1) 100.0 else 10.0)),
        outcome,
        path,
        Harness.fieldOf(s, "family"),
        p,
        p.getOrElse(1.0),
        Nil,
        Nil,
        Nil
      )
    }.toMap
  private lazy val report = Harness.evaluate(gold, key, None, 7L, 200, Some("SYNTHETIC"))
  private def rr = report("researcherResolution")
  private def close(a: Double, b: Double)(implicit loc: munit.Location) =
    assertEqualsDouble(a, b, 1e-9)

  test("unweighted precision, recall, F1, coverage and unresolved rate match the hand count") {
    val u = rr("unweighted")
    close(u("precision")("value").num, 3.0 / 4) // tp 3, fp 1
    close(u("recall")("value").num, 3.0 / 5) // MATCH 5
    close(u("f1").num, 2 * 0.75 * 0.6 / 1.35)
    close(u("coverage")("value").num, 7.0 / 9) // decided 7 of 9 MATCH/NON_MATCH
    close(u("unresolvedRate")("value").num, 2.0 / 10)
    assertEquals(report("labeledRows").num, 10.0)
    assertEquals(report("unlabeledRows").num, 3.0)
  }

  test("confusion matrix is 4 decisions x 3 labels with the hand counts") {
    val cm = rr("unweighted")("confusion")
    val expected = Map(
      "accepted" -> (3, 1, 0),
      "rejected" -> (1, 2, 0),
      "unresolved" -> (1, 0, 1),
      "conflict" -> (0, 1, 0)
    )
    assertEquals(cm.obj.keySet.toSet, expected.keySet)
    expected.foreach { case (o, (ma, nm, un)) =>
      assertEquals(
        (cm(o)("MATCH").num.toInt, cm(o)("NON_MATCH").num.toInt, cm(o)("UNSURE").num.toInt),
        (ma, nm, un),
        o
      )
    }
  }

  test("Wilson 95% interval of precision 3/4 is about [0.301, 0.954]") {
    val w = rr("unweighted")("precision")("wilson95").arr
    assertEqualsDouble(w(0).num, 0.3006, 1e-3)
    assertEqualsDouble(w(1).num, 0.9544, 1e-3)
  }

  test("weighted estimates use population / labeled per stratum (S1: 100/5, S2: 10/5)") {
    val w = rr("weighted")("metrics")
    close(w("precision")("value").num, 60.0 / 80) // tp 3x20, fp 1x20
    close(w("recall")("value").num, 60.0 / 64) // MATCH: 3x20 + 2 + 2
    close(w("coverage")("value").num, (100.0 + 4) / (100 + 8)) // S1 all decided; S2 decided 2 of 4
  }

  test("breakdowns, evidence split and error analysis") {
    assertEquals(
      rr("byMatchPath").obj.keySet.toSet,
      Set("probabilistic", "deterministic", "exact_id")
    )
    assertEquals(rr("byFamily").obj.keySet.toSet, Set("f1", "f2"))
    assertEquals(rr("evidence")("dblpNumberedNameOnly")("units").num, 2.0)
    assertEquals(rr("evidence")("independentEvidence")("units").num, 7.0)
    assertEquals(rr("evidence")("noEvidence").num, 1.0)
    assertEquals(rr("errorAnalysis")("falsePositivesTotal").num, 1.0)
    assertEquals(rr("errorAnalysis")("falseNegativesTotal").num, 2.0)
    assertEquals(rr("errorAnalysis")("falseNegatives").arr.size, 2)
  }

  test("threshold sweep re-thresholds scored rows only and never changes the policy") {
    val grid = rr("thresholdSensitivity")("grid").arr
    assertEquals(grid.size, 110)
    val strict = grid.find(g => g("accept").num == 0.99 && g("reject").num == 0.05).get
    // the three p=0.95 accepted rows become unresolved; the deterministic false positive stays
    close(strict("precision").num, 0.0)
    close(strict("recall").num, 0.0)
    val base = grid.find(g => g("accept").num == 0.9 && g("reject").num == 0.3).get
    close(base("precision").num, 0.75)
    assertEqualsDouble(org.scholarchain.er.policy.Policy.erV1.thresholds.accept, 0.9, 0.0)
  }

  // ------------------------------------------------------------------------------------------
  // Leakage (decision 14, repair m6): token-level source scan, class-file constant pools, TASTy
  // ------------------------------------------------------------------------------------------

  private val repo = org.scholarchain.contracts.hadoop.AdversarialSupport.repoRoot
  private val mainSrc = repo.resolve("modules/entity-resolution/src/main")

  private def walk(dir: java.nio.file.Path): List[java.nio.file.Path] = {
    val s = java.nio.file.Files.walk(dir)
    try
      scala.jdk.CollectionConverters
        .IteratorHasAsScala(s.iterator)
        .asScala
        .filter(java.nio.file.Files.isRegularFile(_))
        .toList
        .sortBy(_.toString)
    finally s.close()
  }
  private def text(p: java.nio.file.Path) =
    new String(java.nio.file.Files.readAllBytes(p), java.nio.charset.StandardCharsets.UTF_8)

  /** True for paths whose segments contain `org/scholarchain/er/eval` (not `evaluation` etc.). */
  private def inEvalPackage(rel: java.nio.file.Path): Boolean = {
    val segs =
      scala.jdk.CollectionConverters.IteratorHasAsScala(rel.iterator).asScala.map(_.toString).toList
    segs.sliding(4).exists(_ == List("org", "scholarchain", "er", "eval"))
  }

  private lazy val sources: List[java.nio.file.Path] = walk(mainSrc.resolve("scala"))
  private lazy val evalSources = sources.filter(p => inEvalPackage(mainSrc.relativize(p)))
  private lazy val matchingSources = sources.filterNot(p => inEvalPackage(mainSrc.relativize(p)))
  private lazy val evalNames: Set[String] =
    evalSources.flatMap(p => LeakageDetector.topLevelNames(text(p))).toSet

  private def resourceTexts(kind: String): List[(String, String)] =
    walk(repo.resolve(s"tests/contracts/src/test/resources/er-leakage/$kind"))
      .map(p => p.getFileName.toString -> text(p))

  test("leakage detector self-check: every planted form is caught, clean controls are not") {
    // the eval package declares the names the detector guards
    assert(
      Set("Harness", "GoldLabels", "GoldLabel", "GoldSet", "KeyRow", "EvalMain").subsetOf(
        evalNames
      ),
      evalNames
    )
    // the top-level name extraction never yields keywords (it would then flag every file)
    val keywords = Set(
      "import",
      "package",
      "object",
      "class",
      "trait",
      "val",
      "def",
      "type",
      "case",
      "final",
      "private",
      "new",
      "extends",
      "with",
      "if",
      "else",
      "match",
      "given"
    )
    assertEquals(evalNames.intersect(keywords), Set.empty[String])
    resourceTexts("clean").foreach { case (name, src) =>
      assertEquals(
        LeakageDetector.topLevelNames(src),
        Set("CleanControl", "CleanAfterInterpolation"),
        s"top-level names of $name (brace depth after nested interpolation)"
      )
    }
    val planted = resourceTexts("planted")
    assertEquals(planted.size, 10, planted.map(_._1))
    planted.foreach { case (name, src) =>
      assert(LeakageDetector.sourceFindings(src, evalNames).nonEmpty, s"planted $name not caught")
    }
    // the reviewer's form is caught by the identifier rule itself, not only by a type name
    val reviewer = planted.find(_._1.startsWith("01-")).get._2
    assert(
      LeakageDetector.sourceFindings(reviewer, Set.empty).exists(_.contains("`eval`")),
      "package-relative import eval.Harness must be caught without the type-name rule"
    )
    // and the old text search would have missed it (why the stronger detector exists)
    assert(!reviewer.contains("er.eval"))
    resourceTexts("clean").foreach { case (name, src) =>
      assertEquals(LeakageDetector.sourceFindings(src, evalNames), Nil, s"clean control $name")
    }
  }

  test("no matching source references the eval package in any token form or reads gold paths") {
    assert(matchingSources.size >= 15, s"only ${matchingSources.size} matching sources found")
    assert(evalSources.size >= 3, s"only ${evalSources.size} eval sources found")
    // only Scala sources: a Java file would bypass the Scala-token scan
    assertEquals(sources.filterNot(_.toString.endsWith(".scala")).map(_.toString), Nil)
    // every eval source declares the eval package, and only eval sources do
    evalSources.foreach { p =>
      assert(
        "(?m)^\\s*package\\s+org\\.scholarchain\\.er\\.eval\\s*$".r.findFirstIn(text(p)).isDefined,
        s"$p is not in package org.scholarchain.er.eval"
      )
    }
    // no eval name collides with a matching-code declaration (else the name rule is blind there)
    val matchingNames = matchingSources.flatMap(p => LeakageDetector.topLevelNames(text(p))).toSet
    assertEquals(evalNames.intersect(matchingNames), Set.empty[String])
    val offenders = matchingSources.flatMap { p =>
      LeakageDetector.sourceFindings(text(p), evalNames).map(f => s"${mainSrc.relativize(p)}: $f")
    }
    assertEquals(offenders, Nil)
  }

  private lazy val classesDir: java.nio.file.Path = {
    val url = getClass.getClassLoader.getResource("org/scholarchain/er/run/ErRun.class")
    assert(url != null, "entity-resolution classes not on the test classpath")
    assertEquals(url.getProtocol, "file", s"expected a class directory, got $url")
    // .../org/scholarchain/er/run/ErRun.class -> the class root
    java.nio.file.Paths.get(url.toURI).getParent.getParent.getParent.getParent.getParent
  }

  test("class-file and TASTy detectors self-check on real eval classes and a synthetic class") {
    val compiled = walk(classesDir.resolve("org/scholarchain/er"))
    val evalClasses = compiled.filter(p => inEvalPackage(classesDir.relativize(p)))
    assert(evalClasses.exists(_.toString.endsWith(".class")), "no compiled eval classes")
    // EvalMain calls GoldLabels and Harness: its constant pool must name the eval package
    val evalMain = evalClasses.filter(_.getFileName.toString.startsWith("EvalMain"))
    assert(
      evalMain.exists(p =>
        p.toString.endsWith(".class") &&
          LeakageDetector.classFindings(java.nio.file.Files.readAllBytes(p)).nonEmpty
      ),
      evalMain
    )
    // every eval TASTy file names its own package segment `eval`
    val evalTasty = evalClasses.filter(_.toString.endsWith(".tasty"))
    assert(evalTasty.nonEmpty, "no eval TASTy files")
    evalTasty.foreach(p =>
      assert(LeakageDetector.tastyNamesEval(java.nio.file.Files.readAllBytes(p)), s"$p")
    )
    // the constant-pool parser handles two-slot constants and finds a planted reference
    val planted = LeakageDetector.syntheticClass(
      List("java/lang/Object", "Lorg/scholarchain/er/eval/KeyRow;", "evaluation")
    )
    assertEquals(LeakageDetector.classFindings(planted), List("Lorg/scholarchain/er/eval/KeyRow;"))
    val clean = LeakageDetector.syntheticClass(List("org/scholarchain/er/evaluation/X", "eval"))
    assertEquals(LeakageDetector.classFindings(clean), Nil)
  }

  test("no compiled matching class or TASTy file refers to the eval package") {
    val compiled = walk(classesDir.resolve("org/scholarchain/er"))
    val matching = compiled.filterNot(p => inEvalPackage(classesDir.relativize(p)))
    val classes = matching.filter(_.toString.endsWith(".class"))
    val tasty = matching.filter(_.toString.endsWith(".tasty"))
    assert(classes.size >= 30, s"only ${classes.size} matching classes")
    assert(tasty.size >= 15, s"only ${tasty.size} matching TASTy files")
    val classHits = classes.flatMap { p =>
      LeakageDetector
        .classFindings(java.nio.file.Files.readAllBytes(p))
        .map(u => s"${classesDir.relativize(p)}: $u")
    }
    assertEquals(classHits, Nil)
    val tastyHits = tasty
      .filter(p => LeakageDetector.tastyNamesEval(java.nio.file.Files.readAllBytes(p)))
      .map(p => classesDir.relativize(p).toString)
    assertEquals(tastyHits, Nil)
  }
}
