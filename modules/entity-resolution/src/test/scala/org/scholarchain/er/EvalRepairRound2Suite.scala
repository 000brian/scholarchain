package org.scholarchain.er

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import org.scholarchain.er.eval.*
import org.scholarchain.er.io.DataFiles

/** Phase 5 run 2, second repair round (repairs 20 to 28): code-reviewer findings R1 to R5 and R7,
  * test-engineer findings F6 and F7, and the optional off-grid threshold note. Synthetic labels,
  * key rows and run artifacts only; nothing reads docs/agent-runs or the human gold file.
  */
class EvalRepairRound2Suite extends munit.FunSuite {

  private def m(i: Int) = f"mention:v1:$i%032x"
  private def c(i: Int) = f"match-candidate:v1:$i%032x"

  private def st(path: String, outcome: String, family: String) =
    s"path=$path;outcome=$outcome;family=$family;band=far"

  private val bigAccepted = st("deterministic", "accepted", "F")
  private val fpAlone = st("probabilistic", "accepted", "F")
  private val tpAlone = st("probabilistic", "accepted", "G")
  private val unresolved = st("probabilistic", "unresolved", "F")

  private def row(
      i: Int,
      label: String,
      outcome: String,
      stratum: String,
      population: Double,
      sameCluster: Boolean
  ): (GoldLabel, KeyRow) = {
    val path = Harness.fieldOf(stratum, "path")
    val lr = f"researcher:v1:$i%032x"
    val rr = if (sameCluster) lr else f"researcher:v1:${i + 1000}%032x"
    (
      GoldLabel(
        m(2 * i - 1),
        m(2 * i),
        c(i),
        label,
        if (label == "UNSURE") Nil else List("orcid"),
        "SYNTHETIC",
        "SYNTHETIC-LABELER",
        "2026-11-14"
      ),
      KeyRow(
        m(2 * i - 1),
        m(2 * i),
        c(i),
        List((stratum, population)),
        outcome,
        path,
        "F",
        Some(0.5),
        0.5,
        List("name_exact_match" -> 1.0),
        Nil,
        Nil,
        List((outcome, path)),
        Some(lr),
        Some(rr),
        Some(sameCluster)
      )
    )
  }

  /** The R1 shape: a heavy accepted stratum with only MATCH rows and the single false positive
    * alone in a small stratum, so the bootstrap shows (almost) no precision variance.
    */
  private val zeroEventRows: List[(GoldLabel, KeyRow)] =
    (1 to 5).toList.map(i => row(i, "MATCH", "accepted", bigAccepted, 10000, sameCluster = true)) ++
      List(
        row(6, "NON_MATCH", "accepted", fpAlone, 20, sameCluster = true),
        row(7, "MATCH", "accepted", tpAlone, 20, sameCluster = true),
        row(8, "MATCH", "unresolved", unresolved, 500, sameCluster = false),
        row(9, "NON_MATCH", "unresolved", unresolved, 500, sameCluster = false),
        row(10, "NON_MATCH", "unresolved", unresolved, 500, sameCluster = false)
      )

  /** Every stratum mixed (MATCH, NON_MATCH and UNSURE rows): no zero-event share anywhere. */
  private val mixedRows: List[(GoldLabel, KeyRow)] = {
    val a = st("probabilistic", "accepted", "M")
    val b = st("probabilistic", "accepted", "N")
    List(
      row(1, "MATCH", "accepted", a, 100, sameCluster = true),
      row(2, "MATCH", "accepted", a, 100, sameCluster = false),
      row(3, "NON_MATCH", "accepted", a, 100, sameCluster = true),
      row(4, "UNSURE", "accepted", a, 100, sameCluster = false),
      row(5, "MATCH", "accepted", b, 50, sameCluster = true),
      row(6, "NON_MATCH", "accepted", b, 50, sameCluster = false),
      row(7, "NON_MATCH", "accepted", b, 50, sameCluster = true),
      row(8, "UNSURE", "accepted", b, 50, sameCluster = false)
    )
  }

  private def eval(rows: List[(GoldLabel, KeyRow)], inputs: EvalInputs = EvalInputs()) =
    Harness.evaluate(
      GoldSet(rows.map(_._1), 0),
      rows.map(r => (r._2.left, r._2.right) -> r._2).toMap,
      None,
      20261114L,
      200,
      Some("SYNTHETIC"),
      inputs
    )

  private def rel(met: ujson.Value) =
    met("researcherResolution")("weighted")("intervalReliability")("metrics")
  private def clusterRel(met: ujson.Value) =
    met("researcherClusterAssignment")("weighted")("intervalReliability")("metrics")

  // ---------------------------------------------------------------------------------------------
  // repair-20 (reviewer R1)

  test("repair-20: zero-event strata flag the weighted precision interval and give a real bound") {
    val met = eval(zeroEventRows)
    val boot = met("researcherResolution")("weighted")("bootstrap95")("precision").arr.map(_.num)
    // the R1 symptom: the bootstrap is (nearly) degenerate although only 7 rows are accepted
    assert(boot(1) - boot(0) < 0.01, boot.toString)
    val p = rel(met)("precision")
    assertEquals(p("unreliable"), ujson.Bool(true))
    assert(p("zeroEventShareOfRelevantPopulation").num > 0.99, p.render())
    val names = p("zeroEventStrata").arr.map(_("stratum").str).toList
    assertEquals(names.head, bigAccepted, "the heaviest zero-event stratum is named first")
    assert(p("zeroEventStrata").arr.head("shareOfRelevantPopulation").num > 0.99)
    assertEquals(p("zeroEventStrata").arr.head("observedProportion").num, 1.0)
    // the conservative bound reaches far below the bootstrap's lower end
    val bound = p("zeroEventBound95").arr.map(_.num)
    assert(bound(0) < 0.9 && bound(0) < boot(0), s"bound $bound vs bootstrap $boot")
    assert(bound(1) >= p("estimate").num)
    // the Markdown marks the interval wherever it appears and names the stratum
    val md = Harness.markdown(met)
    val wrow = md.linesIterator.find(_.startsWith("| Weighted estimate (bootstrap)")).get
    // Phase 16 M3: the flag carries the zero-event bound in the same cell
    assert(wrow.contains("] (unreliable; zero-event bound ["), wrow)
    val bline = md.linesIterator.find(_.startsWith("Weighted bootstrap 95%:")).get
    assert(
      bline.contains("precision [") && bline.contains("(unreliable; zero-event bound ["),
      bline
    )
    assert(md.contains("Weighted interval reliability (zero-event strata)."), md)
    val pl = md.linesIterator.find(_.startsWith("- precision: bootstrap ")).get
    assert(pl.contains("unreliable: zero-event strata hold"), pl)
    assert(pl.contains(bigAccepted), pl)
    assert(pl.contains("Zero-event bound ["), pl)
  }

  test("repair-20: the same detection applies to recall, F1 and the cluster level") {
    val met = eval(zeroEventRows)
    val r = rel(met)
    List("precision", "recall", "coverage", "unresolvedRate").foreach { k =>
      assert(r(k).obj.contains("zeroEventStrata"), k)
      assert(r(k)("zeroEventBound95").arr.size == 2, k)
    }
    assertEquals(r("f1")("unreliable"), ujson.Bool(true))
    val cp = clusterRel(met)("precision")
    assertEquals(cp("unreliable"), ujson.Bool(true))
    assert(cp("zeroEventBound95").arr(0).num < 0.9, cp.render())
    assert(!clusterRel(met).obj.contains("unresolvedRate"))
    val md = Harness.markdown(met)
    val crow =
      md.linesIterator.find(_.startsWith("| Cluster level, weighted estimate (bootstrap)")).get
    assert(crow.contains("(unreliable; zero-event bound ["), crow)
    assert(md.contains("Cluster-level weighted interval reliability"), md)
  }

  test("repair-20: mixed strata are not flagged and the bound equals the point estimate") {
    val met = eval(mixedRows)
    val w = met("researcherResolution")("weighted")("metrics")
    for (k <- List("precision", "coverage")) {
      val x = rel(met)(k)
      assertEquals(x("unreliable"), ujson.Bool(false), k)
      assertEquals(x("zeroEventStrata").arr.size, 0, k)
      assertEqualsDouble(x("estimate").num, w(k)("value").num, 1e-12, k)
      assertEqualsDouble(x("zeroEventBound95").arr(0).num, w(k)("value").num, 1e-12, k)
      assertEqualsDouble(x("zeroEventBound95").arr(1).num, w(k)("value").num, 1e-12, k)
    }
    assert(
      !Harness
        .markdown(met)
        .linesIterator
        .exists(l => l.startsWith("| Weighted estimate") && l.contains("unreliable"))
    )
  }

  test("repair-20: one zero-event stratum of n rows gives the exact one-sided limit 0.05^(1/n)") {
    val s = st("probabilistic", "accepted", "Z")
    val units = (1 to 5).toList.map(i => EvalUnit("accepted", "MATCH", 20.0, s, i.toString))
    val spec = IntervalReliability.PairSpecs.find(_.name == "precision").get
    val r = IntervalReliability.evaluate(units, spec, outcomeFixedByStratum = true)
    assertEquals(r.estimate, Some(1.0))
    assertEqualsDouble(r.bound.get._1, math.pow(0.05, 1.0 / 5), 1e-9)
    assertEqualsDouble(r.bound.get._2, 1.0, 1e-12)
    assertEqualsDouble(IntervalReliability.zeroEventLimit(3), 1 - math.pow(0.05, 1.0 / 3), 1e-12)
  }

  // ---------------------------------------------------------------------------------------------
  // repair-21 (reviewer R2)

  test("repair-21: the weighted unresolved rate is labeled design-determined") {
    val met = eval(zeroEventRows)
    val u = rel(met)("unresolvedRate")
    assertEquals(u("designDetermined"), ujson.Bool(true))
    assertEquals(u("unreliable"), ujson.Bool(false))
    assertEquals(u("designDeterminedNote").str, IntervalReliability.DesignDeterminedNote)
    assertEquals(rel(met)("precision")("designDetermined"), ujson.Bool(false))
    val md = Harness.markdown(met)
    val bline = md.linesIterator.find(_.startsWith("Weighted bootstrap 95%:")).get
    assert(bline.matches(".*unresolvedRate \\[[^]]*\\] \\(design-determined\\).*"), bline)
    val ul = md.linesIterator.find(_.startsWith("- unresolvedRate: bootstrap ")).get
    assert(ul.contains(IntervalReliability.DesignDeterminedNote), ul)
  }

  // ---------------------------------------------------------------------------------------------
  // repair-22 (reviewer R3)

  test("repair-22: the weight rule states that UNSURE rows count in labeled_h") {
    val met = eval(mixedRows)
    val design = met("researcherResolution")("weighted")("design")
    assertEquals(design("weightRule").str, Harness.WeightRule)
    assert(Harness.WeightRule.contains("population_h / labeled_h"))
    assert(Harness.WeightRule.contains("UNSURE rows included"))
    assert(Harness.WeightRule.contains("not as missing at random"))
    val md = Harness.markdown(met)
    val ws = md.linesIterator.find(_.startsWith("Weighted sample:")).get
    assert(ws.contains(Harness.WeightRule), ws)
    // the rule is what the weights do: an UNSURE row lowers the weight of its stratum's rows
    val unit = Harness
      .weightedUnits(
        Harness.join(
          GoldSet(mixedRows.map(_._1), 0),
          mixedRows.map(r => (r._2.left, r._2.right) -> r._2).toMap
        )
      )
      .head
    assertEqualsDouble(unit.weight, 100.0 / 4, 1e-12)
  }

  // ---------------------------------------------------------------------------------------------
  // EvalMain fixture (repairs 23 and 27)

  private final class Fixture {
    val dir: Path = Files.createTempDirectory("er-eval-round2-")
    def write(name: String, text: String): Path = {
      val p = dir.resolve(name)
      Files.write(p, text.getBytes(StandardCharsets.UTF_8))
      p
    }
    private val rows = List(
      row(1, "MATCH", "accepted", fpAlone, 30, sameCluster = true),
      row(2, "NON_MATCH", "rejected", st("probabilistic", "rejected", "F"), 30, sameCluster = false)
    )
    val gold: Path = write(
      "gold.csv",
      ("leftMentionId,rightMentionId,candidateId,label,evidence,note,labeler,labeledAt" ::
        rows.map { case (g, _) =>
          s"${g.leftMentionId},${g.rightMentionId},${g.candidateId},${g.label},orcid,SYNTHETIC,SYNTHETIC-LABELER,2026-11-14"
        }).mkString("", "\r\n", "\r\n")
    )
    val queue: Path = write(
      "queue.jsonl",
      rows.zipWithIndex
        .map { case ((g, _), i) =>
          ujson.write(
            ujson.Obj(
              "rowNumber" -> (i + 1).toString,
              "leftMentionId" -> g.leftMentionId,
              "rightMentionId" -> g.rightMentionId,
              "candidateId" -> g.candidateId
            )
          )
        }
        .mkString("", "\n", "\n")
    )
    val key: Path = write(
      "review-key.jsonl",
      rows
        .map { case (_, k) =>
          ujson.write(
            ujson.Obj(
              "leftMentionId" -> k.left,
              "rightMentionId" -> k.right,
              "candidateId" -> k.candidateId,
              "memberships" -> ujson.Arr.from(k.memberships.map { case (s, p) =>
                ujson.Obj(
                  "stratum" -> s,
                  "population" -> p,
                  "outcome" -> k.outcome,
                  "matchPath" -> k.path
                )
              }),
              "leftResearcherId" -> k.leftResearcherId.get,
              "rightResearcherId" -> k.rightResearcherId.get,
              "sameFinalCluster" -> k.sameFinalCluster.get,
              "outcome" -> k.outcome,
              "matchPath" -> k.path,
              "family" -> k.family,
              "matchProbability" -> 0.5,
              "confidence" -> 0.5,
              "features" -> ujson.Obj("name_exact_match" -> 1),
              "rulesFired" -> ujson.Arr(),
              "reasonCodes" -> ujson.Arr()
            )
          )
        }
        .mkString("", "\n", "\n")
    )
    val out: Path = dir.resolve("out")
    def args(extra: String*): Array[String] = (List(
      "--gold",
      gold.toString,
      "--queue",
      queue.toString,
      "--key",
      key.toString,
      "--output",
      out.toString,
      "--bootstrap-reps",
      "20"
    ) ++ extra).toArray
    def metricsFile: Path = out.resolve("metrics.json")
    def summaryFile: Path = out.resolve("summary.md")
  }

  // ---------------------------------------------------------------------------------------------
  // repair-23 (reviewer R4)

  test(
    "repair-23: a re-run into the same directory records the sha256 of the outputs it replaces"
  ) {
    val f = new Fixture
    EvalMain.main(f.args())
    val first = ujson.read(DataFiles.readUtf8(f.metricsFile))
    assertEquals(first("outputs")("replaced"), ujson.Arr())
    assertEquals(first("outputs")("directory").str, f.out.toString)
    assert(
      DataFiles
        .readUtf8(f.summaryFile)
        .contains("- Replaced earlier outputs (sha256 before this run): none")
    )
    val (m1, s1) = (DataFiles.sha256OfFile(f.metricsFile), DataFiles.sha256OfFile(f.summaryFile))
    EvalMain.main(f.args("--note", "SYNTHETIC second run"))
    val second = ujson.read(DataFiles.readUtf8(f.metricsFile))
    val replaced =
      second("outputs")("replaced").arr.map(r => r("file").str -> r("previousSha256").str).toMap
    assertEquals(replaced, Map("metrics.json" -> m1, "summary.md" -> s1))
    val md = DataFiles.readUtf8(f.summaryFile)
    assert(md.contains(s"metrics.json sha256 $m1; summary.md sha256 $s1."), md)
    assert(md.contains(EvalMain.OverwriteRule), md)
  }

  // ---------------------------------------------------------------------------------------------
  // repair-24 (reviewer R5)

  test("repair-24: the upstream report is named as a non-core format read best-effort") {
    val up = ujson.Obj(
      "summary" -> ujson.Obj("exportDir" -> 42, "fallback" -> ujson.Obj("uncoveredMembers" -> 3))
    )
    val man = ujson.Obj("inputRun" -> ujson.Obj("runId" -> 7))
    val met = eval(mixedRows, EvalInputs(upstreamReport = Some(up), manifest = Some(man)))
    val b = met("knownLimitations")("blockingCoverage")
    assertEquals(b("upstreamReportFormat").str, EvalReport.UpstreamFormatNote)
    assertEquals(b("upstreamReportFieldsRead").arr.map(_.str).toList, EvalReport.UpstreamFieldsRead)
    // a field of another type is not available, never thrown on
    assertEquals(b("upstreamRunMatchesErInputRun"), ujson.Null)
    assertEquals(b("uncoveredMembers").num, 3.0)
    val md = Harness.markdown(met)
    val line = md.linesIterator.find(_.startsWith("- Blocking coverage")).get
    assert(line.contains("not a core contract"), line)
    EvalReport.UpstreamFieldsRead.foreach(fld => assert(line.contains(fld), s"$fld: $line"))
    assert(line.contains("Upstream run is the ER input run: not available."), line)
  }

  // ---------------------------------------------------------------------------------------------
  // repair-25 (reviewer R7)

  test(
    "repair-25: a policy 'rules' field that is not an array stops with the role and field named"
  ) {
    val pol = ujson.Obj("policyVersion" -> "SYNTHETIC", "rules" -> ujson.Obj())
    val e = intercept[IllegalArgumentException](eval(mixedRows, EvalInputs(policy = Some(pol))))
    assert(e.getMessage.startsWith("--policy: the policy file's 'rules' field"), e.getMessage)
    assert(e.getMessage.contains("is not an array (found object"), e.getMessage)
  }

  // ---------------------------------------------------------------------------------------------
  // repair-26 (test-engineer F6)

  test("repair-26: a non-numeric or negative LLM outcome count is not available, never zero") {
    val llmRows = List(
      row(1, "MATCH", "unresolved", st("llm", "unresolved", "F"), 10, sameCluster = false),
      row(2, "NON_MATCH", "unresolved", st("llm", "unresolved", "F"), 10, sameCluster = false)
    )
    for (bad <- List[ujson.Value](ujson.Str("3"), ujson.Num(-1), ujson.Null, ujson.Arr(3))) {
      val rr = ujson.Obj("llm" -> ujson.Obj("outcomes" -> ujson.Obj("accepted" -> bad)))
      val s = eval(llmRows, EvalInputs(runReport = Some(rr)))("researcherResolution")("llmPath")(
        "statement"
      ).str
      assert(!s.contains("records no accepted or rejected pair"), s"$bad: $s")
      assert(!s.contains("records accepted or rejected pairs"), s"$bad: $s")
      assert(
        s.contains(
          "is not available (the run report's LLM stage outcome counts are not all non-negative numbers"
        ),
        s"$bad: $s"
      )
    }
    // a malformed count under another key also makes the counts unusable
    val other = ujson.Obj("llm" -> ujson.Obj("outcomes" -> ujson.Obj("unresolved" -> "4")))
    val s2 = eval(llmRows, EvalInputs(runReport = Some(other)))("researcherResolution")("llmPath")(
      "statement"
    ).str
    assert(!s2.contains("records no accepted or rejected pair"), s2)
  }

  // ---------------------------------------------------------------------------------------------
  // repair-27 (test-engineer F7)

  test("repair-27: a Markdown failure leaves no partial output (both outputs computed first)") {
    val f = new Fixture
    val e = intercept[IllegalStateException](
      EvalMain.run(f.args(), _ => throw new IllegalStateException("SYNTHETIC render failure"))
    )
    assertEquals(e.getMessage, "SYNTHETIC render failure")
    assert(!Files.exists(f.metricsFile), "metrics.json written although summary.md failed")
    assert(!Files.exists(f.summaryFile))
  }

  test("repair-27: a non-object recall-gap element and string thresholds give text, not a stop") {
    val f = new Fixture
    val rr = f.write("run-report.json", "{\"knownRecallGaps\": [\"SYNTHETIC gap as a string\"]}")
    val man = f.write(
      "manifest.json",
      ujson
        .Obj(
          "inputRun" -> ujson.Obj("runId" -> ujson.Arr()),
          "config" -> ujson.Obj(
            "thresholds" -> ujson.Obj("accept" -> "0.9", "reject" -> "0.3"),
            "adjudicator" -> ujson.Obj("name" -> "SYNTHETIC"),
            "llmModelId" -> 5
          )
        )
        .render()
    )
    EvalMain.main(f.args("--run-report", rr.toString, "--manifest", man.toString))
    val md = DataFiles.readUtf8(f.summaryFile)
    assert(Files.exists(f.metricsFile))
    assert(
      md.contains(
        "- Recall gap (run report): not available (a 'knownRecallGaps' element is a string, not an object: \"SYNTHETIC gap as a string\")."
      ),
      md
    )
    assert(
      md.contains(
        "The run manifest's thresholds are not numbers (accept \"0.9\", reject \"0.3\"), so no row is marked as the run's."
      ),
      md
    )
    assert(!md.contains(" (run) |"), md)
  }

  // ---------------------------------------------------------------------------------------------
  // repair-29 (reviewer finding 6, human ruling): the LLM statement states the run report's counts

  private val llmRows = List(
    row(1, "MATCH", "unresolved", st("llm", "unresolved", "F"), 10, sameCluster = false),
    row(2, "NON_MATCH", "unresolved", st("llm", "unresolved", "F"), 10, sameCluster = false)
  )

  private def llmStatement(llm: ujson.Value): (String, ujson.Value) = {
    val met = eval(llmRows, EvalInputs(runReport = Some(ujson.Obj("llm" -> llm))))
    val l = met("researcherResolution")("llmPath")
    (l("statement").str, l("runCounts"))
  }

  test("repair-29: the LLM statement gives the recorded sent, abstain and decision counts") {
    // the real run's shape: no accepted or rejected key; the outcome counts cover all sent pairs
    val (s, c) = llmStatement(
      ujson.Obj(
        "pairsSentToAdjudicator" -> 1000,
        "outcomes" -> ujson.Obj("unresolved" -> 1000),
        "reasons" -> ujson.Obj("llm-abstain" -> 1000)
      )
    )
    assert(
      s.contains(
        "Run report LLM stage counts: 1000 band pairs were sent to the adjudicator; all 1000 abstained (reason llm-abstain 1000); 0 were accepted or rejected (an outcome absent from the recorded outcome counts, which account for all 1000 pairs sent, is 0), so LLM-adjudicated metrics are empty."
      ),
      s
    )
    assertEquals(c("pairsSentToAdjudicator").num, 1000.0)
    assertEquals(c("llmAbstain").num, 1000.0)
    assertEquals(c("accepted").num, 0.0)
    assertEquals(c("rejected").num, 0.0)
    // the Markdown carries the same statement
    assert(
      Harness
        .markdown(
          eval(
            llmRows,
            EvalInputs(runReport =
              Some(
                ujson.Obj(
                  "llm" -> ujson.Obj(
                    "pairsSentToAdjudicator" -> 1000,
                    "outcomes" -> ujson.Obj("unresolved" -> 1000),
                    "reasons" -> ujson.Obj("llm-abstain" -> 1000)
                  )
                )
              )
            )
          )
        )
        .contains("all 1000 abstained (reason llm-abstain 1000)")
    )
  }

  test(
    "repair-29: 'abstained' needs the llm-abstain count; missing or bad counts are not available"
  ) {
    // fewer abstentions than pairs sent: no "all"
    val (partial, _) = llmStatement(
      ujson.Obj(
        "pairsSentToAdjudicator" -> 10,
        "outcomes" -> ujson.Obj("unresolved" -> 7, "accepted" -> 2, "rejected" -> 1),
        "reasons" -> ujson.Obj("llm-abstain" -> 7)
      )
    )
    assert(partial.contains("10 band pairs were sent to the adjudicator; 7 abstained"), partial)
    assert(partial.contains("2 were accepted and 1 were rejected."), partial)
    assert(!partial.contains("all 10 abstained"), partial)
    // no llm-abstain reason: abstention is never stated
    val (noReason, c1) = llmStatement(
      ujson.Obj("pairsSentToAdjudicator" -> 5, "outcomes" -> ujson.Obj("unresolved" -> 5))
    )
    assert(noReason.contains("the llm-abstain count is not available"), noReason)
    assert(!noReason.contains("abstained"), noReason)
    assertEquals(c1("llmAbstain"), ujson.Null)
    // non-numeric counts and outcomes that do not cover the sent pairs: not available, never 0
    val (bad, c2) = llmStatement(
      ujson.Obj(
        "pairsSentToAdjudicator" -> "1000",
        "outcomes" -> ujson.Obj("unresolved" -> 1000),
        "reasons" -> ujson.Obj("llm-abstain" -> -1)
      )
    )
    assert(
      bad.contains(
        "Run report LLM stage counts: the number of band pairs sent to the adjudicator is not available; the llm-abstain count is not available; the accepted count is not available and the rejected count is not available."
      ),
      bad
    )
    assertEquals(c2("pairsSentToAdjudicator"), ujson.Null)
    assertEquals(c2("accepted"), ujson.Null)
    val (short, _) = llmStatement(
      ujson.Obj("pairsSentToAdjudicator" -> 10, "outcomes" -> ujson.Obj("unresolved" -> 4))
    )
    assert(short.contains("the accepted count is not available"), short)
    // no llm section: no count sentence
    val bare = eval(llmRows)("researcherResolution")("llmPath")("statement").str
    assert(!bare.contains("Run report LLM stage counts"), bare)
  }

  // ---------------------------------------------------------------------------------------------
  // repair-30 (human ruling on stale outputs): replaced outputs are labeled stale, never sources

  test("repair-30: replaced earlier outputs are labeled stale and not a source of any number") {
    val f = new Fixture
    EvalMain.main(f.args())
    val first = ujson.read(DataFiles.readUtf8(f.metricsFile))
    assertEquals(first("outputs")("replacedStatus"), ujson.Null)
    assert(!DataFiles.readUtf8(f.summaryFile).contains(EvalReport.ReplacedLabel))
    EvalMain.main(f.args())
    val second = ujson.read(DataFiles.readUtf8(f.metricsFile))
    assertEquals(second("outputs")("replacedStatus").str, EvalReport.ReplacedLabel)
    val line = DataFiles
      .readUtf8(f.summaryFile)
      .linesIterator
      .find(_.startsWith("- Replaced earlier outputs"))
      .get
    assert(line.endsWith(EvalReport.ReplacedLabel), line)
    assert(EvalReport.ReplacedLabel.contains("stale"))
    assert(EvalReport.ReplacedLabel.contains("not a source of any number"))
  }

  // ---------------------------------------------------------------------------------------------
  // repair-28 (optional note: run thresholds off the sweep grid)

  test("repair-28: run thresholds off the sweep grid are stated; no row is marked") {
    val man = ujson.Obj(
      "config" -> ujson.Obj("thresholds" -> ujson.Obj("accept" -> 0.873, "reject" -> 0.3))
    )
    val md = Harness.markdown(eval(mixedRows, EvalInputs(manifest = Some(man))))
    assert(
      md.contains(
        "The run's thresholds (accept 0.873, reject 0.3) are not on the sweep grid, so no row is marked as the run's."
      ),
      md
    )
    assert(!md.linesIterator.exists(_.contains("(run)")), md)
    val on =
      ujson.Obj("config" -> ujson.Obj("thresholds" -> ujson.Obj("accept" -> 0.9, "reject" -> 0.3)))
    val md2 = Harness.markdown(eval(mixedRows, EvalInputs(manifest = Some(on))))
    assert(!md2.contains("not on the sweep grid"), md2)
    assert(md2.contains("| 0.9 (run) | 0.3 |"), md2)
  }
}
