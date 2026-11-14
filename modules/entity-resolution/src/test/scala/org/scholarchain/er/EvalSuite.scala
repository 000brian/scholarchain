package org.scholarchain.er

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import org.scholarchain.er.eval.*
import org.scholarchain.er.io.Csv
import org.scholarchain.er.review.ReviewQueue

/** Gold-label reader and harness tests over clearly synthetic labels (test resources only). */
class EvalSuite extends munit.FunSuite {

  private def resource(name: String): Path =
    Paths.get(getClass.getClassLoader.getResource(s"synthetic-gold/$name").toURI)
  private val goldText =
    new String(Files.readAllBytes(resource("gold-valid.csv")), StandardCharsets.UTF_8)
  private def hex(i: Int) = f"$i%032x"

  /** Synthetic queue rows matching the synthetic gold file (row i: mentions 2i-1, 2i; candidate i).
    */
  private val queue: Map[(String, String), Map[String, String]] = (1 to 9).map { i =>
    val l = s"mention:v1:${hex(2 * i - 1)}"
    val r = s"mention:v1:${hex(2 * i)}"
    val values = ReviewQueue.Columns.map {
      case "rowNumber"      => "rowNumber" -> i.toString
      case "leftMentionId"  => "leftMentionId" -> l
      case "rightMentionId" => "rightMentionId" -> r
      case "candidateId"    => "candidateId" -> s"match-candidate:v1:${hex(i)}"
      case c if ReviewQueue.LabelColumns.contains(c) => c -> ""
      case c                                         => c -> s"SYNTHETIC $c $i"
    }.toMap
    (l, r) -> values
  }.toMap

  private def parse(text: String) = GoldLabels.parse(text, queue)
  private def errors(text: String): List[String] =
    parse(text).left.toOption.getOrElse(fail("expected errors"))

  test("the synthetic gold file is read; unlabeled rows are counted, not dropped silently") {
    val g = parse(goldText).fold(e => fail(e.mkString("\n")), identity)
    assertEquals(g.labels.size, 8)
    assertEquals(g.unlabeledRows, 1)
    assertEquals(g.labels.map(_.label).distinct.sorted, List("MATCH", "NON_MATCH", "UNSURE"))
    assertEquals(g.labels(1).evidence, List("orcid", "shared_coauthors"))
    assertEquals(g.labels(2).note, "SYNTHETIC, with a comma")
  }

  test("strict reader: unknown or repeated or missing columns") {
    assert(
      errors(goldText.replaceFirst("labeledAt", "labeledAt,score"))
        .exists(_.contains("unknown columns score"))
    )
    assert(
      errors(goldText.replaceFirst("note,", "note,note,")).exists(_.contains("repeated columns"))
    )
    assert(errors(goldText.replaceFirst(",labeler,", ",")).nonEmpty)
  }

  test("strict reader: unknown label or evidence, repeated evidence, MATCH without evidence") {
    assert(
      errors(goldText.replaceFirst(",MATCH,dblp_numbered_name,", ",YES,dblp_numbered_name,"))
        .exists(_.contains("unknown label 'YES'"))
    )
    assert(
      errors(goldText.replaceFirst("research_area", "vibes"))
        .exists(_.contains("unknown evidence 'vibes'"))
    )
    assert(
      errors(goldText.replaceFirst("shared_coauthors;orcid", "orcid;orcid"))
        .exists(_.contains("repeated evidence"))
    )
    assert(
      errors(goldText.replaceFirst(",MATCH,dblp_numbered_name,", ",MATCH,,"))
        .exists(_.contains("needs at least one evidence"))
    )
    assert(
      errors(goldText.replaceFirst("2026-11-14T10:00:00Z", "yesterday"))
        .exists(_.contains("labeledAt"))
    )
    assert(
      errors(
        goldText.replaceFirst("SYNTHETIC-TEST-LABELER,2026-11-14T10", ",2026-11-14T10")
      ).nonEmpty
    )
  }

  test(
    "strict reader: duplicate pairs, pairs outside the queue, candidate or display changes, half-filled rows"
  ) {
    val lines = goldText.split("\n").toList
    assert(errors((lines :+ lines(1)).mkString("\n") + "\n").exists(_.contains("duplicate pair")))
    val outside = lines(1).replace(s"mention:v1:${hex(2)}", s"mention:v1:${hex(99)}")
    assert(errors((lines :+ outside).mkString("\n")).exists(_.contains("not in the review queue")))
    assert(
      errors(
        goldText.replaceFirst(s"match-candidate:v1:${hex(1)}", s"match-candidate:v1:${hex(42)}")
      ).exists(_.contains("candidateId"))
    )
    assert(
      errors(goldText.replace(",,,,,", ",,,a note,,"))
        .exists(_.contains("label is empty but other label columns are filled"))
    )
    // Labeling directly in the queue CSV: display columns are allowed but must match the queue.
    val q = queue.toList.sortBy(_._2("rowNumber").toInt).map(_._2)
    val header = Csv.row(ReviewQueue.Columns)
    val row = q.head ++ Map(
      "label" -> "MATCH",
      "evidence" -> "orcid",
      "labeler" -> "SYNTHETIC-TEST-LABELER",
      "labeledAt" -> "2026-11-14"
    )
    val ok = header + "\n" + Csv.row(ReviewQueue.Columns.map(row)) + "\n"
    assertEquals(parse(ok).map(_.labels.size), Right(1))
    val edited = header + "\n" + Csv.row(
      ReviewQueue.Columns.map((row + ("left_name" -> "Someone Else")).apply)
    ) + "\n"
    assert(errors(edited).exists(_.contains("display column left_name")))
  }

  test("harness metrics on the synthetic set match hand computation") {
    val gold = parse(goldText).toOption.get
    val key = KeyRow.readAll(resource("review-key.jsonl"))
    val m = Harness.evaluate(gold, key, None, 1L, 200, Some("SYNTHETIC test"))
    val u = m("researcherResolution")("unweighted")
    assertEqualsDouble(u("precision")("value").num, 2.0 / 3, 1e-12)
    assertEqualsDouble(u("recall")("value").num, 0.5, 1e-12)
    assertEqualsDouble(u("f1").num, 4.0 / 7, 1e-12)
    assertEqualsDouble(u("coverage")("value").num, 5.0 / 7, 1e-12)
    assertEqualsDouble(u("unresolvedRate")("value").num, 0.25, 1e-12)
    val conf = u("confusion")
    assertEquals(conf("accepted")("MATCH").num, 2.0)
    assertEquals(conf("accepted")("NON_MATCH").num, 1.0)
    assertEquals(conf("rejected")("MATCH").num, 1.0)
    assertEquals(conf("unresolved")("UNSURE").num, 1.0)
    assertEquals(conf("conflict")("NON_MATCH").num, 1.0)
    val w = m("researcherResolution")("weighted")("metrics")
    assertEqualsDouble(w("precision")("value").num, 2.0 / 3, 1e-9)
    assertEqualsDouble(w("recall")("value").num, (200.0 / 3) / (200.0 / 3 + 5 + 10), 1e-9)
    val byPath = m("researcherResolution")("byMatchPath")
    assertEquals(byPath("deterministic")("units").num, 1.0)
    val ev = m("researcherResolution")("evidence")
    assertEquals(ev("dblpNumberedNameOnly")("units").num, 1.0)
    assertEquals(ev("independentEvidence")("units").num, 6.0)
    assertEquals(ev("noEvidence").num, 1.0)
    val errs = m("researcherResolution")("errorAnalysis")
    assertEquals(errs("falsePositivesTotal").num, 1.0)
    assertEquals(errs("falseNegativesTotal").num, 2.0)
    assert(errs("falsePositives").arr.head("rulesFired").arr.nonEmpty)
    assert(m("researcherResolution")("thresholdSensitivity")("grid").arr.nonEmpty)
    assert(Harness.markdown(m).contains("| All rows (unweighted) | 8 |"))
    assertEquals(m("workResolution")("summary"), ujson.Null)
  }

  test("M2: weighted intervals come from the stratified bootstrap, never from n = summed weight") {
    val gold = parse(goldText).toOption.get
    val key = KeyRow.readAll(resource("review-key.jsonl"))
    val m = Harness.evaluate(gold, key, None, 1L, 400, Some("SYNTHETIC test"))
    val rr = m("researcherResolution")
    val u = rr("unweighted")("precision")
    val w = rr("weighted")("metrics")("precision")
    // unweighted: Wilson with n = labeled units (3 accepted rows labeled MATCH or NON_MATCH)
    assertEquals(u("labeledUnits").num, 3.0)
    assertEquals(u("denominator").num, 3.0)
    assertEquals(u("intervalMethod").str, Harness.WilsonMethod)
    assertEquals(u("interval95"), u("wilson95"))
    // weighted: the denominator is an estimated population count (100), not a sample size
    assertEqualsDouble(w("denominator").num, 100.0, 1e-9)
    assertEquals(w("labeledUnits").num, 3.0)
    assertEqualsDouble(w("kishEffectiveN").num, 3.0, 1e-9) // equal weights within the stratum
    assertEquals(w("wilson95"), ujson.Null)
    assertEquals(w("intervalMethod").str, Harness.BootstrapMethod)
    val wi = w("interval95").arr.map(_.num)
    val ui = u("interval95").arr.map(_.num)
    assertEquals(wi.toList, rr("weighted")("bootstrap95")("precision").arr.map(_.num).toList)
    assert(wi(1) - wi(0) >= ui(1) - ui(0), s"weighted $wi narrower than unweighted $ui")
    // the old interval (Wilson with n = 100) is falsely narrow
    val (nl, nh) = Proportion(w("numerator").num, w("denominator").num).wilson.get
    assert(nh - nl < ui(1) - ui(0))
    assert(nh - nl < wi(1) - wi(0))
    // the Markdown weighted row prints the bootstrap interval
    val md = Harness.markdown(m)
    val row = md.linesIterator.find(_.startsWith("| Weighted estimate")).get
    assert(row.contains(f"[${wi(0) * 100}%.1f%%, ${wi(1) * 100}%.1f%%]"), row)
    assert(!row.contains(f"[${nl * 100}%.1f%%, ${nh * 100}%.1f%%]"), row)
    assert(!md.contains("Precision (Wilson 95%)"))
  }

  test("m2: formula-trigger cells are neutralised on write and restored on read") {
    val vals = List(
      "=HYPERLINK(\"x\")",
      "+1",
      "-2",
      "@SUM(A1)",
      "\tTab",
      "\rCR",
      "'quoted",
      "'=already",
      "plain",
      ""
    )
    val text = Csv.row(vals) + "\r\n"
    assert(
      text.startsWith(
        "\"'=HYPERLINK(\"\"x\"\")\",'+1,'-2,'@SUM(A1),'\tTab,\"'\rCR\",''quoted,''=already,plain,"
      ),
      text
    )
    assertEquals(Csv.parse(text).map(_.head.toList), Right(vals))
    // a hand-typed apostrophe that is not followed by a trigger is kept
    assertEquals(Csv.parse("'hello,x\n").map(_.head.toList), Right(List("'hello", "x")))
  }

  test("m2: the strict gold reader still matches neutralised display values against the queue") {
    val (k, q) = queue.toList.sortBy(_._2("rowNumber").toInt).head
    val injected = q ++ Map("left_title" -> "=SYNTHETIC(1)", "right_name" -> "@SYNTHETIC")
    val qs = queue + (k -> injected)
    val row = injected ++ Map(
      "label" -> "MATCH",
      "evidence" -> "orcid",
      "labeler" -> "SYNTHETIC-TEST-LABELER",
      "labeledAt" -> "2026-11-14"
    )
    val text = Csv.row(ReviewQueue.Columns) + "\n" + Csv.row(ReviewQueue.Columns.map(row)) + "\n"
    assert(text.contains(",'=SYNTHETIC(1),") && text.contains(",'@SYNTHETIC,"), text)
    assertEquals(GoldLabels.parse(text, qs).map(_.labels.size), Right(1))
  }

  test(
    "m-new-2: cluster-level precision, recall and confusion on the synthetic set, apart from pair level"
  ) {
    val gold = parse(goldText).toOption.get
    val key = KeyRow.readAll(resource("review-key.jsonl"))
    val m = Harness.evaluate(gold, key, None, 1L, 200, Some("SYNTHETIC test"))
    val ca = m("researcherClusterAssignment")
    assertEquals(ca("labeledRowsWithClusterInfo").num, 8.0)
    assertEquals(ca("labeledRowsWithoutClusterInfo").num, 0.0)
    // same final cluster: rows 1 (MATCH), 2 (MATCH), 3 (NON_MATCH), 6 (MATCH, pair unresolved)
    val u = ca("unweighted")
    assertEqualsDouble(u("precision")("value").num, 3.0 / 4, 1e-12)
    assertEqualsDouble(u("recall")("value").num, 3.0 / 4, 1e-12) // MATCH rows 1, 2, 5, 6
    assertEqualsDouble(u("f1").num, 3.0 / 4, 1e-12)
    val conf = u("confusion")
    assertEquals(conf.obj.keySet.toSet, Set(Harness.SameCluster, Harness.DifferentClusters))
    assertEquals(
      Harness.LabelOrder.map(l => conf(Harness.SameCluster)(l).num),
      List(3.0, 1.0, 0.0)
    )
    assertEquals(
      Harness.LabelOrder.map(l => conf(Harness.DifferentClusters)(l).num),
      List(1.0, 2.0, 1.0)
    )
    assert(!u.obj.contains("coverage") && !u.obj.contains("unresolvedRate"))
    // weighted: stratum weights 100/3 (rows 1-3), 5 (rows 4-5), 10 (rows 6-8)
    val w = ca("weighted")("metrics")
    assertEqualsDouble(w("precision")("value").num, 23.0 / 33, 1e-9)
    assertEqualsDouble(w("recall")("value").num, 46.0 / 49, 1e-9)
    assertEquals(w("precision")("intervalMethod").str, Harness.BootstrapMethod)
    assertEquals(w("precision")("wilson95"), ujson.Null)
    // pair decisions against final clusters
    val by = ca("pairOutcomeByFinalCluster")
    assertEquals(by("unresolved")(Harness.SameCluster).num, 1.0)
    assertEquals(by("unresolved")(Harness.DifferentClusters).num, 1.0)
    assertEquals(by("accepted")(Harness.SameCluster).num, 3.0)
    assertEquals(by("rejected")(Harness.DifferentClusters).num, 2.0)
    // pair-level metrics are unchanged and reported apart
    assertEqualsDouble(
      m("researcherResolution")("unweighted")("recall")("value").num,
      0.5,
      1e-12
    )
    val md = Harness.markdown(m)
    assert(md.contains("## Researcher resolution: final cluster assignment"), md)
    assert(md.contains("| Cluster level, all rows (unweighted) | 8 | 75.0%"), md)
    assert(md.contains("| same final cluster | 3 | 1 | 0 |"), md)
    assert(md.contains("| different final clusters | 1 | 2 | 1 |"), md)
    assert(md.contains("| unresolved | 1 | 1 |"), md)
  }

  test(
    "m-new-1 and m-new-2: the review-key reader requires membership decisions and cluster fields"
  ) {
    val text = new String(Files.readAllBytes(resource("review-key.jsonl")), StandardCharsets.UTF_8)
    def refused(t: String, msg: String) = {
      val f = Files.createTempFile("er-key-", ".jsonl")
      Files.write(f, t.getBytes(StandardCharsets.UTF_8))
      val e = intercept[IllegalArgumentException](KeyRow.readAll(f))
      assert(e.getMessage.contains(msg), e.getMessage)
    }
    refused(
      text.replace(""","outcome":"accepted","matchPath":"deterministic"}""", "}"),
      "missing field 'outcome'"
    )
    refused(
      text.replace(""""sameFinalCluster":true,""", ""),
      "missing field 'sameFinalCluster'"
    )
    refused(
      text.replace(""""sameFinalCluster":true""", """"sameFinalCluster":false"""),
      "disagrees with the ResearcherIds"
    )
    refused(text.replaceFirst(""""leftResearcherId":"[^"]*",""", ""), "leftResearcherId")
    val k = KeyRow.readAll(resource("review-key.jsonl"))
    assert(k.values.forall(r => r.membershipDecisions.size == r.memberships.size))
  }

  test("Wilson interval and threshold sweep are hand-checkable") {
    val (lo, hi) = Proportion(2, 3).wilson.get
    assertEqualsDouble(lo, 0.2077, 1e-3)
    assertEqualsDouble(hi, 0.9385, 1e-3)
    val k = KeyRow(
      "a",
      "b",
      "",
      Nil,
      "unresolved",
      "probabilistic",
      "f",
      Some(0.7),
      0.7,
      Nil,
      List("score.llm-band"),
      Nil
    )
    assertEquals(Harness.sweptOutcome(k, 0.65, 0.3), "accepted")
    assertEquals(Harness.sweptOutcome(k, 0.9, 0.75), "rejected")
    assertEquals(
      Harness.sweptOutcome(k.copy(rulesFired = List("cluster.guard.orcid")), 0.5, 0.1),
      "unresolved"
    )
    assertEquals(Harness.sweptOutcome(k.copy(path = "deterministic"), 0.5, 0.1), "unresolved")
  }
}
