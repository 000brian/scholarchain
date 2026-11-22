package org.scholarchain.contracts.er

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.security.MessageDigest

import scala.jdk.CollectionConverters.*
import scala.util.Try

import org.scholarchain.er.eval.{
  EvalInputs,
  EvalMain,
  EvalUnit,
  GoldLabel,
  GoldSet,
  Harness,
  IntervalReliability,
  KeyRow
}

/** Adversarial tests of round 3 of the evaluation-report repair (repairs 20 to 30): zero-event
  * detection and the Clopper-Pearson zero-event bound at their boundaries, the design-determined
  * label only where it is true, the UNSURE weight rule, replaced-output records and the stale
  * label, all-or-nothing output with the renderer seam, the non-array policy 'rules' stop, the
  * off-grid threshold note, the upstream-report format note, the repair-29 count statement edge
  * cases, the forbidden phrases, and the withdrawn `--llm-attestation` argument.
  *
  * Every label, key row, queue row and run artifact is synthetic and built in the test; nothing
  * reads docs/agent-runs or the human gold file.
  */
class EvalReportRound3AdversarialSuite extends munit.FunSuite {

  // ---------------------------------------------------------------------------------------------
  // Synthetic builders

  private def m(i: Int) = f"mention:v1:$i%032x"
  private def c(i: Int) = f"match-candidate:v1:$i%032x"

  private def st(path: String, outcome: String, family: String = "SYNTH") =
    s"path=$path;outcome=$outcome;family=$family;band=far"

  private def label(i: Int, lab: String) = GoldLabel(
    m(2 * i - 1),
    m(2 * i),
    c(i),
    lab,
    if (lab == "UNSURE") Nil else List("orcid"),
    "SYNTHETIC",
    "SYNTHETIC-LABELER",
    "2026-11-14"
  )

  /** A key row with one membership; `stratumOutcome` names the sampling stratum's outcome when it
    * differs from the membership's own decision (an m3 membership).
    */
  private def keyRow(
      i: Int,
      outcome: String,
      path: String = "probabilistic",
      reasons: List[String] = Nil,
      stratumOutcome: Option[String] = None,
      population: Double = 50.0,
      sameCluster: Option[Boolean] = None
  ): KeyRow = KeyRow(
    m(2 * i - 1),
    m(2 * i),
    c(i),
    List((st(path, stratumOutcome.getOrElse(outcome)), population)),
    outcome,
    path,
    "SYNTH",
    Some(0.5),
    0.5,
    List("name_exact_match" -> 1.0),
    Nil,
    reasons,
    List((outcome, path)),
    sameFinalCluster = sameCluster
  )

  private def eval(rows: List[(GoldLabel, KeyRow)], inputs: EvalInputs = EvalInputs()): ujson.Obj =
    Harness.evaluate(
      GoldSet(rows.map(_._1), 0),
      rows.map(r => (r._2.left, r._2.right) -> r._2).toMap,
      None,
      7L,
      20,
      Some("SYNTHETIC"),
      inputs
    )

  private def md(met: ujson.Obj): String = Harness.markdown(met)

  private def occurrences(text: String, s: String): Int =
    text.sliding(s.length).count(_ == s)

  // ---------------------------------------------------------------------------------------------
  // repair-20: zero-event detection boundaries (IntervalReliability directly)

  private def u(outcome: String, lab: String, w: Double, s: String) = EvalUnit(outcome, lab, w, s)

  private val SA = st("probabilistic", "accepted", "A")
  private val SB = st("probabilistic", "accepted", "B")
  private val SR = st("probabilistic", "rejected", "R")

  private def spec(name: String) = IntervalReliability.PairSpecs.find(_.name == name).get

  private def evalSpec(units: List[EvalUnit], name: String, fixed: Boolean = true) =
    IntervalReliability.evaluate(units, spec(name), outcomeFixedByStratum = fixed)

  private def acc(s: String, w: Double, labels: String*) = labels.toList.map(u("accepted", _, w, s))

  test("repair-20: zero-event strata holding exactly 50% are not flagged; just above 50% are") {
    // A: no NON_MATCH among its accepted MATCH/NON_MATCH rows (zero event); B: mixed
    val a = acc(SA, 1.0, "MATCH", "MATCH", "UNSURE")
    val b = acc(SB, 1.0, "MATCH", "NON_MATCH", "UNSURE")
    val r = evalSpec(a ++ b, "precision")
    assertEquals(r.zeroEventStrata.map(_.stratum), List(SA))
    assertEqualsDouble(r.shareOfRelevantPopulation, 0.5, 1e-12)
    assertEqualsDouble(r.shareOfDenominatorWeight, 0.5, 1e-12)
    assert(!r.unreliable, "exactly 50% must not exceed the 50% flag threshold")
    val j = IntervalReliability.json(a ++ b, IntervalReliability.PairSpecs, true)
    assertEquals(j("flagThreshold").num, 0.5)
    assertEquals(j("metrics")("precision")("unreliable"), ujson.Bool(false))

    val r2 = evalSpec(a.map(_.copy(weight = 1.25)) ++ b, "precision")
    assert(r2.shareOfRelevantPopulation > 0.5, r2.toString)
    assert(r2.unreliable, "a zero-event share just above 50% must flag the interval")
  }

  test("repair-20: either share above 50% flags the interval (denominator only, population only)") {
    // denominator only: population shares 4/8 = 50%, denominator weight shares 3/5 = 60%
    val dA = acc(SA, 1.0, "MATCH", "MATCH", "MATCH", "UNSURE")
    val dB = acc(SB, 1.0, "MATCH", "NON_MATCH", "UNSURE", "UNSURE")
    val rd = evalSpec(dA ++ dB, "precision")
    assertEqualsDouble(rd.shareOfRelevantPopulation, 0.5, 1e-12)
    assertEqualsDouble(rd.shareOfDenominatorWeight, 0.6, 1e-12)
    assert(rd.unreliable, "denominator share 60% must flag")
    // population only: population shares 5/8, denominator weight shares 1/3
    val pA = acc(SA, 1.0, "MATCH", "UNSURE", "UNSURE", "UNSURE", "UNSURE")
    val pB = acc(SB, 1.0, "MATCH", "NON_MATCH", "UNSURE")
    val rp = evalSpec(pA ++ pB, "precision")
    assertEqualsDouble(rp.shareOfRelevantPopulation, 0.625, 1e-12)
    assertEqualsDouble(rp.shareOfDenominatorWeight, 1.0 / 3, 1e-12)
    assert(rp.unreliable, "population share 62.5% must flag")
    // neither: 2/8 and 1/5
    val nA = acc(SA, 1.0, "MATCH", "UNSURE")
    val nB = acc(SB, 1.0, "MATCH", "NON_MATCH", "UNSURE", "MATCH", "NON_MATCH", "UNSURE")
    val rn = evalSpec(nA ++ nB, "precision")
    assertEqualsDouble(rn.shareOfRelevantPopulation, 0.25, 1e-12)
    assertEqualsDouble(rn.shareOfDenominatorWeight, 0.2, 1e-12)
    assert(!rn.unreliable)
    assertEquals(rn.zeroEventStrata.map(_.stratum), List(SA))
  }

  test(
    "repair-20: a single zero-event stratum gives the exact Clopper-Pearson limit as its bound"
  ) {
    // n = 2 accepted MATCH rows, no NON_MATCH: lower limit 0.05^(1/2)
    val r = evalSpec(acc(SA, 1.0, "MATCH", "MATCH", "UNSURE"), "precision")
    assertEqualsDouble(r.shareOfRelevantPopulation, 1.0, 1e-12)
    assertEqualsDouble(r.shareOfDenominatorWeight, 1.0, 1e-12)
    assert(r.unreliable)
    assertEquals(r.estimate, Some(1.0))
    val (lo, hi) = r.bound.getOrElse(fail("no bound"))
    assertEqualsDouble(lo, math.sqrt(0.05), 1e-9)
    assertEqualsDouble(hi, 1.0, 1e-12)
    val z = r.zeroEventStrata.head
    assertEquals((z.denominatorUnits, z.numeratorUnits, z.observed), (2, 2, Some(1.0)))

    // n = 20: lower limit 0.05^(1/20)
    val r20 = evalSpec(acc(SA, 3.0, (List.fill(20)("MATCH") :+ "UNSURE")*), "precision")
    assertEqualsDouble(r20.bound.get._1, math.pow(0.05, 1.0 / 20), 1e-9)
    assertEqualsDouble(r20.bound.get._2, 1.0, 1e-12)

    // the other boundary: 3 accepted NON_MATCH rows, no MATCH: upper limit 1 - 0.05^(1/3)
    val r0 = evalSpec(acc(SA, 1.0, "NON_MATCH", "NON_MATCH", "NON_MATCH", "UNSURE"), "precision")
    assertEquals(r0.estimate, Some(0.0))
    assertEqualsDouble(r0.bound.get._1, 0.0, 1e-12)
    assertEqualsDouble(r0.bound.get._2, 1 - math.pow(0.05, 1.0 / 3), 1e-9)
    assert(r0.unreliable)
  }

  test("repair-20: zeroEventLimit is 1 - 0.05^(1/m), close to the rule of three") {
    assertEqualsDouble(IntervalReliability.zeroEventLimit(1), 0.95, 1e-12)
    assertEqualsDouble(IntervalReliability.zeroEventLimit(3), 0.6315968501359612, 1e-12)
    assertEqualsDouble(IntervalReliability.zeroEventLimit(30), 0.09503, 1e-4)
    for (n <- List(100, 300, 1000)) {
      val l = IntervalReliability.zeroEventLimit(n)
      assert(l < 3.0 / n && 3.0 / n - l < 0.15 * 3.0 / n, s"n=$n limit $l vs 3/n ${3.0 / n}")
    }
  }

  test("repair-20: all strata mixed: nothing flagged and the bound collapses to the estimate") {
    val units = acc(SA, 1.0, "MATCH", "NON_MATCH", "UNSURE") ++
      acc(SB, 1.0, "MATCH", "MATCH", "NON_MATCH", "UNSURE")
    for (k <- List("precision", "recall", "coverage")) {
      val r = evalSpec(units, k)
      assertEquals(r.zeroEventStrata, Nil, k)
      assertEquals(r.shareOfRelevantPopulation, 0.0, k)
      assertEquals(r.shareOfDenominatorWeight, 0.0, k)
      assert(!r.unreliable, k)
      val e = r.estimate.get
      assertEqualsDouble(r.bound.get._1, e, 1e-9)
      assertEqualsDouble(r.bound.get._2, e, 1e-9)
    }
    assertEqualsDouble(evalSpec(units, "precision").estimate.get, 0.6, 1e-12)
    val j = IntervalReliability.json(units, IntervalReliability.PairSpecs, true)
    assertEquals(j("metrics")("f1")("unreliable"), ujson.Bool(false))
  }

  test("repair-20: a zero-event stratum with no denominator row widens the recall lower bound") {
    // R: rejected, no MATCH among 3 labeled rows (no denominator row); A: one MATCH accepted
    val units = acc(SA, 1.0, "MATCH", "NON_MATCH", "UNSURE") ++
      List("NON_MATCH", "NON_MATCH", "UNSURE").map(u("rejected", _, 1.0, SR))
    val r = evalSpec(units, "recall")
    assertEquals(r.estimate, Some(1.0))
    assertEquals(r.zeroEventStrata.map(_.stratum), List(SR))
    val z = r.zeroEventStrata.head
    assertEquals((z.denominatorUnits, z.observed), (0, None))
    assertEqualsDouble(r.shareOfRelevantPopulation, 0.5, 1e-12)
    assertEqualsDouble(r.shareOfDenominatorWeight, 0.0, 1e-12)
    assert(!r.unreliable)
    // R could hold up to 1 - 0.05^(1/3) MATCH, none accepted: recall >= 1 / (1 + 3 * limit)
    val limit = 1 - math.pow(0.05, 1.0 / 3)
    assertEqualsDouble(r.bound.get._1, 1.0 / (1.0 + 3 * limit), 1e-9)
    assertEqualsDouble(r.bound.get._2, 1.0, 1e-12)
  }

  test("repair-20: the bound always brackets the estimate and stays in [0, 1]") {
    val fixtures = List(
      acc(SA, 2.0, "MATCH", "MATCH", "UNSURE") ++ acc(SB, 7.0, "MATCH", "NON_MATCH"),
      acc(SA, 1.0, "NON_MATCH") ++ acc(SB, 1.0, "MATCH", "NON_MATCH", "UNSURE") ++
        List("MATCH", "UNSURE").map(u("rejected", _, 4.0, SR)),
      List("MATCH", "NON_MATCH").map(u("unresolved", _, 5.0, st("probabilistic", "unresolved")))
    )
    for ((units, i) <- fixtures.zipWithIndex; s <- IntervalReliability.PairSpecs) {
      val r = IntervalReliability.evaluate(units, s, outcomeFixedByStratum = true)
      (r.estimate, r.bound) match {
        case (Some(e), Some((lo, hi))) =>
          assert(0.0 <= lo && lo <= e + 1e-12 && e <= hi + 1e-12 && hi <= 1.0, s"$i/${s.name}")
        case (None, None) => ()
        case other        => fail(s"$i/${s.name}: estimate and bound disagree: $other")
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // repair-21: design-determined only where it is true

  test("repair-21: only the pair-level unresolved rate with outcome-defined strata is labeled") {
    val units = acc(SA, 1.0, "MATCH", "NON_MATCH", "UNSURE") ++
      List("MATCH", "UNSURE").map(u("rejected", _, 3.0, SR))
    val dd = evalSpec(units, "unresolvedRate")
    assert(dd.designDetermined)
    assert(!dd.unreliable)
    List("precision", "recall", "coverage").foreach(k =>
      assert(!evalSpec(units, k).designDetermined, s"$k labeled design-determined")
    )
    // cluster level: no share is fixed by the design
    assert(!evalSpec(units, "unresolvedRate", fixed = false).designDetermined)
    // strata whose names carry no outcome are not defined by the decision
    val noOutcome = units.map(x => x.copy(stratum = x.stratum.replace(";outcome=accepted", "")))
    assert(!evalSpec(noOutcome, "unresolvedRate").designDetermined)
    val j = IntervalReliability.json(noOutcome, IntervalReliability.PairSpecs, true)
    assertEquals(j("metrics")("unresolvedRate")("designDeterminedNote"), ujson.Null)
  }

  private val consistentRows = List(
    label(1, "MATCH") -> keyRow(1, "accepted", sameCluster = Some(true)),
    label(2, "NON_MATCH") -> keyRow(2, "accepted", sameCluster = Some(true)),
    label(3, "UNSURE") -> keyRow(3, "accepted", sameCluster = Some(false)),
    label(4, "MATCH") -> keyRow(4, "rejected", sameCluster = Some(false)),
    label(5, "NON_MATCH") -> keyRow(5, "rejected", sameCluster = Some(false)),
    label(6, "MATCH") -> keyRow(6, "unresolved", sameCluster = Some(true)),
    label(7, "NON_MATCH") -> keyRow(7, "unresolved", sameCluster = Some(false))
  )

  test("repair-21: the Markdown labels the unresolved rate alone and never the cluster level") {
    val met = eval(consistentRows)
    val pair = met("researcherResolution")("weighted")("intervalReliability")("metrics")
    assertEquals(pair("unresolvedRate")("designDetermined"), ujson.Bool(true))
    List("precision", "recall", "coverage").foreach(k =>
      assertEquals(pair(k)("designDetermined"), ujson.Bool(false), k)
    )
    val cl = met("researcherClusterAssignment")("weighted")("intervalReliability")
    assertEquals(cl("outcomeSharesFixedByStratum"), ujson.Bool(false))
    assertEquals(cl("metrics").obj.keySet.toSet, Set("precision", "recall", "f1"))
    List("precision", "recall").foreach(k =>
      assertEquals(cl("metrics")(k)("designDetermined"), ujson.Bool(false), k)
    )
    val text = md(met)
    assertEquals(occurrences(text, "(design-determined)"), 1, text)
    val boot = text.linesIterator.find(_.startsWith("Weighted bootstrap 95%:")).get
    assert(boot.matches(".*unresolvedRate \\[[^\\]]*\\] \\(design-determined\\).*"), boot)
  }

  // M4 (finding F8). Accepted risk in Phase 5 run 2 (docs/agent-runs/phase-05/prompt-02.md);
  // repaired in Phase 16 step 2 (entity-resolution-engineer, IntervalReliability: a rate is
  // design-determined only when every stratum's labeled memberships share its outcome). The
  // expected-failure mark (.fail) and its title prefix were removed in Phase 16 step 6 (decision
  // 10). Assertions are unchanged from Phase 5.
  test(
    "FINDING repair-21: no labeled membership agrees with its stratum: not design-determined"
  ) {
    // every labeled membership's own decision (unresolved) differs from its sampling stratum's
    // outcome (accepted): no share is fixed by the design, the unresolved share of each stratum is
    // estimated from the labeled rows, and those strata are zero events (all unresolved)
    val units = List("MATCH", "NON_MATCH", "UNSURE").map(u("unresolved", _, 1.0, SA)) ++
      List("MATCH", "NON_MATCH").map(u("rejected", _, 2.0, SB))
    val r = evalSpec(units, "unresolvedRate")
    assert(
      !r.designDetermined,
      s"labeled design-determined (and so never flagged: unreliable=${r.unreliable}) although no " +
        s"stratum's outcome share is fixed by the design; zero-event share of the relevant " +
        s"population ${r.shareOfRelevantPopulation}"
    )
    val rows = List(
      label(1, "MATCH") -> keyRow(1, "unresolved", stratumOutcome = Some("accepted")),
      label(2, "NON_MATCH") -> keyRow(2, "unresolved", stratumOutcome = Some("accepted"))
    )
    val text = md(eval(rows))
    assert(!text.contains("(design-determined)"), text)
  }

  // ---------------------------------------------------------------------------------------------
  // repair-22: UNSURE counts in labeled_h

  test("repair-22: UNSURE rows count in labeled_h, so a MATCH weighs population / all labeled") {
    val rows = List(
      label(1, "MATCH") -> keyRow(1, "accepted", population = 100.0),
      label(2, "UNSURE") -> keyRow(2, "accepted", population = 100.0),
      label(3, "UNSURE") -> keyRow(3, "accepted", population = 100.0),
      label(4, "UNSURE") -> keyRow(4, "accepted", population = 100.0)
    )
    val met = eval(rows)
    val w = met("researcherResolution")("weighted")
    val s = w("design")("strata")(st("probabilistic", "accepted"))
    assertEquals(s("labeledMemberships").num, 4.0)
    assertEquals(s("weight").num, 25.0, "UNSURE rows were left out of labeled_h")
    assertEquals(w("metrics")("precision")("denominator").num, 25.0)
    val rule = w("design")("weightRule").str
    assert(rule.contains("population_h / labeled_h"), rule)
    assert(rule.contains("UNSURE rows included"), rule)
    assert(rule.contains("not as missing at random"), rule)
    val line = md(met).linesIterator.find(_.startsWith("Weighted sample:")).get
    assert(line.contains(rule), line)
  }

  // ---------------------------------------------------------------------------------------------
  // EvalMain fixture (repairs 23, 25, 27, 30 and the withdrawn attestation argument)

  private final class Fixture {
    val dir: Path = Files.createTempDirectory("er-eval-round3-")
    def write(name: String, text: String): Path = {
      val p = dir.resolve(name)
      Files.write(p, text.getBytes(StandardCharsets.UTF_8))
      p
    }
    val gold: Path = write(
      "gold.csv",
      ("leftMentionId,rightMentionId,candidateId,label,evidence,note,labeler,labeledAt" ::
        List(
          s"${m(1)},${m(2)},${c(1)},MATCH,orcid,SYNTHETIC,SYNTHETIC-LABELER,2026-11-14",
          s"${m(3)},${m(4)},${c(2)},NON_MATCH,orcid,SYNTHETIC,SYNTHETIC-LABELER,2026-11-14"
        )).mkString("", "\r\n", "\r\n")
    )
    val queue: Path = write(
      "queue.jsonl",
      List(1, 2)
        .map(i =>
          ujson.write(
            ujson.Obj(
              "rowNumber" -> i.toString,
              "leftMentionId" -> m(2 * i - 1),
              "rightMentionId" -> m(2 * i),
              "candidateId" -> c(i),
              "left_name" -> s"SYNTHETIC Left $i",
              "right_name" -> s"SYNTHETIC Right $i"
            )
          )
        )
        .mkString("", "\n", "\n")
    )
    private def keyJson(i: Int, outcome: String) = ujson.Obj(
      "leftMentionId" -> m(2 * i - 1),
      "rightMentionId" -> m(2 * i),
      "candidateId" -> c(i),
      "memberships" -> ujson.Arr(
        ujson.Obj(
          "stratum" -> st("probabilistic", outcome),
          "population" -> 30,
          "outcome" -> outcome,
          "matchPath" -> "probabilistic"
        )
      ),
      "leftResearcherId" -> f"researcher:v1:$i%032x",
      "rightResearcherId" -> f"researcher:v1:${i + 100}%032x",
      "sameFinalCluster" -> false,
      "outcome" -> outcome,
      "matchPath" -> "probabilistic",
      "family" -> "SYNTH",
      "matchProbability" -> 0.5,
      "confidence" -> 0.5,
      "features" -> ujson.Obj("name_exact_match" -> 1),
      "rulesFired" -> ujson.Arr(),
      "reasonCodes" -> ujson.Arr()
    )
    val key: Path = write(
      "review-key.jsonl",
      List(keyJson(1, "accepted"), keyJson(2, "rejected"))
        .map(ujson.write(_))
        .mkString("", "\n", "\n")
    )
    val out: Path = dir.resolve("out")
    val required: List[String] = List(
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
    )
    def metricsPath: Path = out.resolve("metrics.json")
    def summaryPath: Path = out.resolve("summary.md")
    def run(extra: String*): Unit = EvalMain.run((required ++ extra).toArray, Harness.markdown)
    def metrics: ujson.Value = ujson.read(read(metricsPath))
    def summary: String = read(summaryPath)
    def listing: List[String] =
      if (!Files.exists(out)) Nil
      else Files.list(out).iterator().asScala.map(_.getFileName.toString).toList.sorted
    def snapshot: Map[String, String] = listing.map(n => n -> sha(out.resolve(n))).toMap
  }

  private def read(p: Path) = new String(Files.readAllBytes(p), StandardCharsets.UTF_8)

  private def sha(p: Path): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(Files.readAllBytes(p))
      .map(b => f"${b & 0xff}%02x")
      .mkString

  private val StaleLabel =
    "These replaced files are stale outputs of an earlier run into this directory; they are not a source of any number in this report."

  private val NoneReplacedLine =
    "- Replaced earlier outputs (sha256 before this run): none (no earlier metrics.json or summary.md in the directory)."

  private def assertNothingReplaced(f: Fixture, what: String): Unit = {
    val o = f.metrics("outputs")
    assertEquals(o("directory").str, f.out.toString, what)
    assertEquals(o("replaced"), ujson.Arr(): ujson.Value, what)
    assertEquals(o("replacedStatus"), ujson.Null, what)
    assert(f.summary.linesIterator.contains(NoneReplacedLine), s"$what:\n${f.summary}")
    assert(!f.summary.contains("stale"), s"$what: stale label without a replaced file")
    assert(!ujson.write(f.metrics).contains("stale"), s"$what: stale label in metrics.json")
  }

  // ---------------------------------------------------------------------------------------------
  // repairs 23 and 30: replaced outputs

  test("repairs 23/30: a new output directory records nothing replaced and no stale label") {
    val f = new Fixture
    assert(!Files.exists(f.out))
    f.run()
    assertNothingReplaced(f, "new directory")
    assertEquals(f.listing, List("metrics.json", "summary.md"))
  }

  test("repairs 23/30: an existing empty directory, or one with other files, records nothing") {
    val f = new Fixture
    Files.createDirectories(f.out)
    f.run()
    assertNothingReplaced(f, "empty directory")

    val g = new Fixture
    Files.createDirectories(g.out)
    Files.write(g.out.resolve("notes.txt"), "SYNTHETIC notes".getBytes(StandardCharsets.UTF_8))
    Files.write(g.out.resolve("metrics.json.old"), "{}".getBytes(StandardCharsets.UTF_8))
    val before = g.snapshot
    g.run()
    assertNothingReplaced(g, "directory with other files")
    assertEquals(g.listing, List("metrics.json", "metrics.json.old", "notes.txt", "summary.md"))
    List("notes.txt", "metrics.json.old").foreach(n =>
      assertEquals(g.snapshot(n), before(n), s"$n was touched")
    )
  }

  test("repairs 23/30: a reused directory records both previous sha256 values, labeled stale") {
    val f = new Fixture
    f.run()
    val met1 = f.metrics
    val (m1, s1) = (sha(f.metricsPath), sha(f.summaryPath))
    f.run()
    val met2 = f.metrics
    val o = met2("outputs")
    assertEquals(
      o("replaced"),
      ujson.Arr(
        ujson.Obj("file" -> "metrics.json", "previousSha256" -> m1),
        ujson.Obj("file" -> "summary.md", "previousSha256" -> s1)
      ): ujson.Value
    )
    assertEquals(o("replacedStatus"), ujson.Str(StaleLabel): ujson.Value)
    assert(
      f.summary.linesIterator.contains(
        s"- Replaced earlier outputs (sha256 before this run): metrics.json sha256 $m1; summary.md sha256 $s1. $StaleLabel"
      ),
      f.summary
    )
    // stale outputs are never a source: every number and every input record is unchanged
    assertEquals(met2("researcherResolution"), met1("researcherResolution"))
    assertEquals(met2("researcherClusterAssignment"), met1("researcherClusterAssignment"))
    assertEquals(met2("provenance"), met1("provenance"))
    val provShas = met2("provenance").arr.map(_("sha256").str).toSet
    assert(!provShas.contains(m1) && !provShas.contains(s1), "a stale output listed as an input")
  }

  test("repairs 23/30: only an earlier metrics.json (not valid JSON) is listed and never read") {
    val f = new Fixture
    Files.createDirectories(f.out)
    Files.write(f.metricsPath, "SYNTHETIC not json {".getBytes(StandardCharsets.UTF_8))
    val old = sha(f.metricsPath)
    f.run()
    val o = f.metrics("outputs")
    assertEquals(
      o("replaced"),
      ujson.Arr(ujson.Obj("file" -> "metrics.json", "previousSha256" -> old)): ujson.Value
    )
    assertEquals(o("replacedStatus"), ujson.Str(StaleLabel): ujson.Value)
    assert(f.summary.contains(s"metrics.json sha256 $old. $StaleLabel"), f.summary)
  }

  // ---------------------------------------------------------------------------------------------
  // repair-27: all or nothing with the renderer seam

  private object RendererFailure extends RuntimeException("SYNTHETIC renderer failure")

  test("repair-27: a renderer failure into a new directory writes nothing and creates no folder") {
    val f = new Fixture
    val e = intercept[RuntimeException](
      EvalMain.run(f.required.toArray, _ => throw RendererFailure)
    )
    assert(e eq RendererFailure, e.toString)
    assert(!Files.exists(f.out), "output directory created although rendering failed")
  }

  test("repair-27: a renderer failure into a reused directory leaves earlier outputs untouched") {
    val f = new Fixture
    f.run()
    val before = f.snapshot
    intercept[RuntimeException](EvalMain.run(f.required.toArray, _ => throw RendererFailure))
    assertEquals(f.snapshot, before, "earlier outputs were replaced or half-replaced")
  }

  test("repair-27: summary.md is the renderer's text and metrics.json is the object it rendered") {
    val f = new Fixture
    var seen: Option[ujson.Obj] = None
    EvalMain.run(
      f.required.toArray,
      o => { seen = Some(o); "SYNTHETIC SUMMARY TEXT\n" }
    )
    assertEquals(f.summary, "SYNTHETIC SUMMARY TEXT\n")
    assertEquals(f.metrics, seen.getOrElse(fail("renderer not called")): ujson.Value)
    assert(f.metrics.obj.contains("outputs"), "the rendered object lacks the outputs record")
  }

  /** A run either writes both outputs or, when it fails, neither, and never creates the folder. */
  private def assertAllOrNothing(f: Fixture, run: Try[Unit], what: String): Unit = {
    val metrics = Files.exists(f.metricsPath)
    val summary = Files.exists(f.summaryPath)
    if (run.isFailure)
      assert(
        !metrics && !summary && !Files.exists(f.out),
        s"$what: the run failed (${run.failed.get}) but left metrics.json=$metrics, summary.md=$summary"
      )
    else assert(metrics && summary, s"$what: run succeeded with metrics=$metrics summary=$summary")
  }

  private def manifestText(cfg: ujson.Value, extra: (String, ujson.Value)*): String =
    ujson.Obj
      .from(
        List[(String, ujson.Value)](
          "runId" -> "run:v1:SYNTHETIC",
          "config" -> cfg
        ) ++ extra
      )
      .render()

  test("repair-27: inputs of unexpected types never leave a partial output (real renderer)") {
    val variants: List[(String, String, String)] = List(
      (
        "manifest",
        "manifest values of other types",
        manifestText(
          ujson.Obj(
            "adjudicator" -> 7,
            "llmModelId" -> ujson.Obj("id" -> 1),
            "promptVersion" -> ujson.Arr(1),
            "policySha256" -> false,
            "thresholds" -> ujson.Obj("accept" -> ujson.Null, "reject" -> ujson.Obj())
          ),
          "inputRun" -> ujson.Obj("runId" -> 5, "jobName" -> ujson.Arr()),
          "codeVersion" -> ujson.Arr(1)
        )
      ),
      ("manifest", "config is a string", manifestText(ujson.Str("SYNTHETIC"))),
      (
        "run-report",
        "run report of other types",
        ujson
          .Obj(
            "inputs" -> "SYNTHETIC",
            "knownRecallGaps" -> ujson.Arr(1, ujson.Null, ujson.Arr("x"), ujson.Obj("id" -> 3)),
            "llm" -> ujson.Obj(
              "pairsSentToAdjudicator" -> "5",
              "reasons" -> ujson.Arr(1),
              "outcomes" -> ujson.Obj("accepted" -> true)
            )
          )
          .render()
      ),
      (
        "upstream-report",
        "upstream report of other types",
        ujson
          .Obj("summary" -> ujson.Obj("exportDir" -> 9, "fallback" -> "x"), "settings" -> 3)
          .render()
      ),
      (
        "policy",
        "policy of other types",
        ujson
          .Obj(
            "policyVersion" -> 1,
            "thresholds" -> "high",
            "weights" -> ujson.Arr(ujson.Obj("feature" -> 1, "bins" -> "x"), 2),
            "rules" -> ujson.Arr(1, "x", ujson.Obj("id" -> ujson.Arr()))
          )
          .render()
      )
    )
    for ((role, name, body) <- variants) {
      val f = new Fixture
      val p = f.write(s"$role.json", body)
      assertAllOrNothing(f, Try(f.run(s"--$role", p.toString)), name)
    }
  }

  test("repair-27: the named inputs give text, not a stop (recall gaps, thresholds, manifest)") {
    val f = new Fixture
    val rr = f.write(
      "run-report.json",
      ujson.Obj("knownRecallGaps" -> ujson.Arr(1, ujson.Null, ujson.Arr("x"))).render()
    )
    val man = f.write(
      "manifest.json",
      manifestText(
        ujson.Obj(
          "adjudicator" -> 7,
          "llmModelId" -> ujson.Obj("id" -> 1),
          "thresholds" -> ujson.Obj("accept" -> true, "reject" -> "0.3")
        )
      )
    )
    f.run("--run-report", rr.toString, "--manifest", man.toString)
    val text = f.summary
    assertEquals(
      occurrences(text, "- Recall gap (run report): not available (a 'knownRecallGaps' element"),
      3,
      text
    )
    assert(text.contains("The run manifest's thresholds are not numbers"), text)
    assert(!text.linesIterator.exists(_.contains("(run)")), "a row marked as the run's")
  }

  // ---------------------------------------------------------------------------------------------
  // repair-25: policy 'rules' that is not an array

  test("repair-25: non-array 'rules' stops naming role and field; nothing written, reuse intact") {
    val bad: List[(ujson.Value, String)] = List(
      ujson.Obj("id" -> "SYNTH") -> "object",
      ujson.Str("SYNTH") -> "string",
      ujson.Num(3) -> "number",
      ujson.Bool(true) -> "boolean"
    )
    for ((v, kind) <- bad) {
      val f = new Fixture
      val pol =
        f.write("policy.json", ujson.Obj("policyVersion" -> "SYNTHETIC", "rules" -> v).render())
      val e = intercept[IllegalArgumentException](f.run("--policy", pol.toString))
      assert(
        e.getMessage.startsWith(
          s"--policy: the policy file's 'rules' field is present but is not an array (found $kind"
        ),
        s"$kind: ${e.getMessage}"
      )
      assert(e.getMessage.endsWith("the evaluation stops and writes nothing"), e.getMessage)
      assert(!Files.exists(f.out), s"$kind: output directory created")
    }
    // a reused directory keeps its earlier outputs byte-identical
    val g = new Fixture
    g.run()
    val before = g.snapshot
    val pol = g.write("policy.json", ujson.Obj("rules" -> ujson.Obj()).render())
    intercept[IllegalArgumentException](g.run("--policy", pol.toString))
    assertEquals(g.snapshot, before)
  }

  test("repair-25: an array 'rules' (empty or not) and an absent 'rules' still run") {
    for (
      body <- List(
        ujson.Obj("policyVersion" -> "SYNTHETIC", "rules" -> ujson.Arr()),
        ujson.Obj(
          "policyVersion" -> "SYNTHETIC",
          "rules" -> ujson.Arr(ujson.Obj("id" -> "SYNTH.rule", "kind" -> "score"))
        ),
        ujson.Obj("policyVersion" -> "SYNTHETIC")
      )
    ) {
      val f = new Fixture
      val pol = f.write("policy.json", body.render())
      f.run("--policy", pol.toString)
      assertEquals(f.listing, List("metrics.json", "summary.md"), body.render())
    }
  }

  // ---------------------------------------------------------------------------------------------
  // the withdrawn --llm-attestation argument

  test("withdrawn: --llm-attestation is an unknown argument; nothing is written") {
    val f = new Fixture
    val e = intercept[IllegalArgumentException](
      f.run("--llm-attestation", "SYNTHETIC human attestation")
    )
    assertEquals(e.getMessage, "unknown arguments: llm-attestation")
    assert(!Files.exists(f.out))
    for (name <- List("--attestation", "--human-attestation", "--llm-attested")) {
      val g = new Fixture
      val e2 = intercept[IllegalArgumentException](g.run(name, "yes"))
      assert(e2.getMessage.startsWith("unknown arguments:"), s"$name: ${e2.getMessage}")
      assert(!Files.exists(g.out), name)
    }
    // the flag form without a value breaks the name/value pairing and is rejected too
    val h = new Fixture
    intercept[IllegalArgumentException](h.run("--llm-attestation"))
    assert(!Files.exists(h.out))
  }

  private def keysDeep(v: ujson.Value): List[String] = v match {
    case o: ujson.Obj => o.value.toList.flatMap { case (k, x) => k :: keysDeep(x) }
    case a: ujson.Arr => a.value.toList.flatMap(keysDeep)
    case _            => Nil
  }

  test(
    "withdrawn: a successful run carries no attestation field or text, and no forbidden phrase"
  ) {
    val f = new Fixture
    val rr = f.write(
      "run-report.json",
      ujson
        .Obj(
          "llm" -> ujson.Obj(
            "pairsSentToAdjudicator" -> 4,
            "reasons" -> ujson.Obj("llm-abstain" -> 4),
            "outcomes" -> ujson.Obj("unresolved" -> 4)
          )
        )
        .render()
    )
    val man = f.write(
      "manifest.json",
      manifestText(
        ujson.Obj("adjudicator" -> "always-abstain", "llmModelId" -> "none/always-abstain")
      )
    )
    f.run("--run-report", rr.toString, "--manifest", man.toString)
    val keys = keysDeep(f.metrics)
    assert(
      !keys.exists(_.toLowerCase.contains("attest")),
      keys.filter(_.toLowerCase.contains("attest"))
    )
    val all = (read(f.metricsPath) + "\n" + f.summary).toLowerCase
    assert(!all.contains("attest"), "attestation text in the outputs")
    assert(!all.contains("no live model"), "forbidden phrase in the outputs")
    assert(
      f.summary.contains(
        "Run report LLM stage counts: 4 band pairs were sent to the adjudicator; all 4 abstained (reason llm-abstain 4)"
      ),
      f.summary
    )
  }

  // ---------------------------------------------------------------------------------------------
  // repair-29: the count statement

  private val abstainRows = List(
    label(1, "MATCH") -> keyRow(1, "unresolved", "llm", reasons = List("llm-abstain")),
    label(2, "NON_MATCH") -> keyRow(2, "unresolved", "llm", reasons = List("llm-abstain")),
    label(3, "MATCH") -> keyRow(3, "accepted", "deterministic")
  )

  private def llm(
      sent: Option[ujson.Value],
      abstain: Option[ujson.Value],
      outcomes: Option[ujson.Value]
  ): ujson.Obj = ujson.Obj.from(
    sent.map("pairsSentToAdjudicator" -> _).toList ++
      abstain.map(a => "reasons" -> (ujson.Obj("llm-abstain" -> a): ujson.Value)).toList ++
      outcomes.map("outcomes" -> _).toList :+ ("bandCandidates" -> ujson.Num(729))
  )

  private def runReport(l: ujson.Value): ujson.Obj =
    ujson.Obj("inputs" -> ujson.Obj("works" -> 10), "knownRecallGaps" -> ujson.Arr(), "llm" -> l)

  private def manifestObj(adjudicator: String, model: String): ujson.Obj = ujson.Obj(
    "runId" -> "run:v1:SYNTHETIC",
    "config" -> ujson.Obj("adjudicator" -> adjudicator, "llmModelId" -> model)
  )

  private def evalLlm(l: ujson.Value, man: Option[ujson.Value] = None): ujson.Obj =
    eval(abstainRows, EvalInputs(manifest = man, runReport = Some(runReport(l))))

  private def statement(met: ujson.Value): String =
    met("researcherResolution")("llmPath")("statement").str

  private def runCounts(met: ujson.Value): ujson.Value =
    met("researcherResolution")("llmPath")("runCounts")

  /** The count sentence alone, from its opening words to its full stop. */
  private def countSentence(met: ujson.Value): String = {
    val s = statement(met)
    val i = s.indexOf("Run report LLM stage counts: ")
    assert(i >= 0, s"no count sentence: $s")
    s.substring(i).split(" The path llm row", 2)(0)
  }

  private def num(n: Double): ujson.Value = ujson.Num(n)

  private def assertCounts(
      met: ujson.Value,
      sent: ujson.Value,
      abstain: ujson.Value,
      accepted: ujson.Value,
      rejected: ujson.Value
  ): Unit = {
    val rc = runCounts(met)
    assertEquals(
      (rc("pairsSentToAdjudicator"), rc("llmAbstain"), rc("accepted"), rc("rejected")),
      (sent, abstain, accepted, rejected)
    )
  }

  private val Prefix = "Run report LLM stage counts: "
  private def absentNote(n: Int) =
    s" (an outcome absent from the recorded outcome counts, which account for all $n pairs sent, is 0)"

  test("repair-29: recorded counts, all abstained, absent keys covered by the outcomes") {
    val met = evalLlm(llm(Some(1000), Some(1000), Some(ujson.Obj("unresolved" -> 1000))))
    assertEquals(
      countSentence(met),
      s"${Prefix}1000 band pairs were sent to the adjudicator; all 1000 abstained (reason llm-abstain 1000); 0 were accepted or rejected${absentNote(1000)}, so LLM-adjudicated metrics are empty."
    )
    assertCounts(met, num(1000), num(1000), num(0), num(0))
    assert(md(met).contains(countSentence(met)), "the Markdown lacks the count sentence")
  }

  test("repair-29: pairs sent missing or not a whole non-negative number is not available") {
    val bad: List[Option[ujson.Value]] =
      List(None, Some("5"), Some(-5), Some(5.5), Some(ujson.Null), Some(ujson.Obj("n" -> 5)))
    for (s <- bad) {
      val met = evalLlm(llm(s, Some(5), Some(ujson.Obj("unresolved" -> 5))))
      assertEquals(
        countSentence(met),
        s"${Prefix}the number of band pairs sent to the adjudicator is not available; 5 abstained (reason llm-abstain); the accepted count is not available and the rejected count is not available.",
        s"pairs sent $s"
      )
      assertCounts(met, ujson.Null, num(5), ujson.Null, ujson.Null)
      assert(!countSentence(met).contains("all 5"), s"pairs sent $s")
      assert(!countSentence(met).contains("metrics are empty"), s"pairs sent $s")
    }
  }

  test("repair-29: an abstain count above pairs sent is stated as recorded, never as 'all'") {
    val met = evalLlm(llm(Some(5), Some(7), Some(ujson.Obj("unresolved" -> 5))))
    val cs = countSentence(met)
    assert(
      cs.startsWith(
        s"${Prefix}5 band pairs were sent to the adjudicator; 7 abstained (reason llm-abstain);"
      ),
      cs
    )
    assert(!cs.contains("all 5 abstained") && !cs.contains("all 7 abstained"), cs)
    assert(!cs.contains("all abstained"), cs)
    assertCounts(met, num(5), num(7), num(0), num(0))
  }

  test("repair-29: outcome counts that do not add up to pairs sent leave absent keys unavailable") {
    for (u <- List(3, 9)) {
      val met = evalLlm(llm(Some(5), Some(5), Some(ujson.Obj("unresolved" -> u))))
      assertEquals(
        countSentence(met),
        s"${Prefix}5 band pairs were sent to the adjudicator; all 5 abstained (reason llm-abstain 5); the accepted count is not available and the rejected count is not available.",
        s"unresolved $u"
      )
      assertCounts(met, num(5), num(5), ujson.Null, ujson.Null)
    }
  }

  test("repair-29: an absent accepted key is 0 with full coverage and not available otherwise") {
    val full = evalLlm(llm(Some(5), Some(3), Some(ujson.Obj("rejected" -> 2, "unresolved" -> 3))))
    assertEquals(
      countSentence(full),
      s"${Prefix}5 band pairs were sent to the adjudicator; 3 abstained (reason llm-abstain); 0 were accepted and 2 were rejected${absentNote(5)}."
    )
    assertCounts(full, num(5), num(3), num(0), num(2))

    val partial =
      evalLlm(llm(Some(5), Some(1), Some(ujson.Obj("rejected" -> 2, "unresolved" -> 1))))
    assertEquals(
      countSentence(partial),
      s"${Prefix}5 band pairs were sent to the adjudicator; 1 abstained (reason llm-abstain); the accepted count is not available and the rejected count is 2."
    )
    assertCounts(partial, num(5), num(1), ujson.Null, num(2))
    assert(!countSentence(partial).contains("is 0)"))
  }

  test("repair-29: explicit zero counts give no absent-key note") {
    val met = evalLlm(
      llm(Some(5), Some(5), Some(ujson.Obj("accepted" -> 0, "rejected" -> 0, "unresolved" -> 5)))
    )
    assertEquals(
      countSentence(met),
      s"${Prefix}5 band pairs were sent to the adjudicator; all 5 abstained (reason llm-abstain 5); 0 were accepted or rejected, so LLM-adjudicated metrics are empty."
    )
  }

  test(
    "repair-29: 'abstained' only from a valid llm-abstain count, whatever the adjudicator name"
  ) {
    val bad: List[Option[ujson.Value]] = List(None, Some("5"), Some(-1), Some(2.5), Some(true))
    for (a <- bad) {
      val met = evalLlm(
        llm(Some(5), a, Some(ujson.Obj("unresolved" -> 5))),
        Some(manifestObj("always-abstain", "none/always-abstain"))
      )
      assertEquals(
        countSentence(met),
        s"${Prefix}5 band pairs were sent to the adjudicator; the llm-abstain count is not available; 0 were accepted or rejected${absentNote(5)}, so LLM-adjudicated metrics are empty.",
        s"abstain $a"
      )
      assert(!statement(met).contains("abstained"), s"abstain $a: ${statement(met)}")
      assertEquals(runCounts(met)("llmAbstain"), ujson.Null, s"abstain $a")
    }
    // other reasons only: the abstain count is still not available
    val other = evalLlm(
      ujson.Obj(
        "pairsSentToAdjudicator" -> 5,
        "reasons" -> ujson.Obj("llm-error" -> 5),
        "outcomes" -> ujson.Obj("unresolved" -> 5)
      )
    )
    assert(countSentence(other).contains("the llm-abstain count is not available"))
  }

  test("repair-29: malformed outcome counts make accepted and rejected not available") {
    val met = evalLlm(llm(Some(5), Some(5), Some(ujson.Obj("unresolved" -> 5, "accepted" -> -1))))
    assert(
      countSentence(met).endsWith(
        "the accepted count is not available and the rejected count is not available."
      ),
      countSentence(met)
    )
    assertCounts(met, num(5), num(5), ujson.Null, ujson.Null)
    // an llm stage with no counts at all
    val empty = evalLlm(ujson.Obj())
    assertEquals(
      countSentence(empty),
      s"${Prefix}the number of band pairs sent to the adjudicator is not available; the llm-abstain count is not available; the accepted count is not available and the rejected count is not available."
    )
  }

  // M2 (finding F9). Accepted risk in Phase 5 run 2 (docs/agent-runs/phase-05/prompt-02.md).
  // Only the part of M2 this test asserts (a fractional outcome count is not a whole count, so it
  // cannot make an absent key 0) was repaired in Phase 16 step 2 (entity-resolution-engineer,
  // EvalReport allSentAccounted). M2's other part (outcome counts include cache hits while the
  // pairs sent exclude them) is still open and must be fixed before any adjudication run with
  // cache hits and before a live LLM is connected. The expected-failure mark (.fail) and its
  // title prefix were removed in Phase 16 step 6 (decision 10). Assertions are unchanged.
  test(
    "FINDING repair-29: a fractional outcome count is not valid, so an absent key is not 0"
  ) {
    // RunCountsRule: "A count that is missing or not a whole non-negative number is not available;
    // an outcome absent from valid outcome counts is 0 only when those counts add up to the pairs
    // sent." accepted 2.5 is not a whole number, so the outcome counts are not all valid and the
    // absent 'rejected' key must not be reported as 0
    val met =
      evalLlm(llm(Some(5), Some(2), Some(ujson.Obj("accepted" -> 2.5, "unresolved" -> 2.5))))
    assertEquals(runCounts(met)("accepted"), ujson.Null)
    assertEquals(
      runCounts(met)("rejected"),
      ujson.Null,
      s"an absent key was counted as 0 from outcome counts that include a fractional count: ${countSentence(met)}"
    )
  }

  test("repair-29: no count is read from the adjudicator's name or model id") {
    val names = List("always-abstain", "none", "SYNTHETIC-live-model", "always-accept")
    val models = List("none/always-abstain", "SYNTHETIC/live-1", "none")
    val stages = List(
      llm(Some(5), Some(5), Some(ujson.Obj("unresolved" -> 5))),
      llm(Some(5), None, None),
      llm(None, None, Some(ujson.Obj("unresolved" -> 5))),
      ujson.Obj()
    )
    for (stage <- stages) {
      val seen = (for (n <- names; mo <- models) yield {
        val met = evalLlm(stage, Some(manifestObj(n, mo)))
        (countSentence(met), runCounts(met))
      }).distinct
      assertEquals(seen.size, 1, s"stage $stage: counts differ by adjudicator name or model id")
    }
    // without any llm stage every count is null and nothing is said about abstaining
    for (n <- names) {
      val met =
        eval(abstainRows, EvalInputs(manifest = Some(manifestObj(n, "none/always-abstain"))))
      assertCounts(met, ujson.Null, ujson.Null, ujson.Null, ujson.Null)
      assert(!statement(met).contains("abstained"), statement(met))
      assert(!statement(met).contains("Run report LLM stage counts"), statement(met))
    }
  }

  test("repair-29: 'no live model' never appears in metrics.json or summary.md for any variant") {
    val stages: List[Option[ujson.Value]] = List(
      None,
      Some(llm(Some(1000), Some(1000), Some(ujson.Obj("unresolved" -> 1000)))),
      Some(llm(Some(5), Some(7), Some(ujson.Obj("unresolved" -> 5)))),
      Some(llm(None, None, None)),
      Some(llm(Some(5), Some(0), Some(ujson.Obj("accepted" -> 3, "rejected" -> 2))))
    )
    for (
      stage <- stages;
      rows <- List(abstainRows, abstainRows.takeRight(1));
      n <- List("always-abstain", "none")
    ) {
      val met = eval(
        rows,
        EvalInputs(
          manifest = Some(manifestObj(n, s"none/$n")),
          runReport = stage.map(runReport)
        )
      )
      val all = (ujson.write(met) + "\n" + md(met)).toLowerCase
      assert(!all.contains("no live model"), s"stage $stage, adjudicator $n")
      assert(!all.contains("live model"), s"stage $stage, adjudicator $n")
    }
  }

  // ---------------------------------------------------------------------------------------------
  // repair-28: off-grid thresholds

  private val sweepRows = List(
    label(1, "MATCH") -> keyRow(1, "accepted"),
    label(2, "NON_MATCH") -> keyRow(2, "unresolved"),
    label(3, "MATCH") -> keyRow(3, "rejected")
  )

  private def withThresholds(t: ujson.Value): String = md(
    eval(
      sweepRows,
      EvalInputs(manifest =
        Some(ujson.Obj("runId" -> "run:v1:SYNTHETIC", "config" -> ujson.Obj("thresholds" -> t)))
      )
    )
  )

  private def runRows(text: String) = text.linesIterator.filter(_.contains("(run)")).toList

  test("repair-28: off-grid thresholds are stated; on-grid thresholds mark one row and no note") {
    val off = withThresholds(ujson.Obj("accept" -> 0.873, "reject" -> 0.3))
    assert(
      off.contains(
        " The run's thresholds (accept 0.873, reject 0.3) are not on the sweep grid, so no row is marked as the run's."
      ),
      off
    )
    assertEquals(runRows(off), Nil)
    // both values are grid values, but reject > accept is never a grid row
    val inverted = withThresholds(ujson.Obj("accept" -> 0.3, "reject" -> 0.5))
    assert(inverted.contains("(accept 0.3, reject 0.5) are not on the sweep grid"), inverted)
    assertEquals(runRows(inverted), Nil)

    val on = withThresholds(ujson.Obj("accept" -> 0.9, "reject" -> 0.3))
    assert(!on.contains("not on the sweep grid"), on)
    assert(!on.contains("are not numbers"), on)
    assertEquals(runRows(on).size, 1, on)
    assert(runRows(on).head.startsWith("| 0.9 (run) | 0.3 |"), runRows(on).head)

    val partial = withThresholds(ujson.Obj("accept" -> 0.9, "reject" -> "0.3"))
    assert(
      partial.contains(
        " The run manifest's thresholds are not numbers (accept 0.9, reject \"0.3\"), so no row is marked as the run's."
      ),
      partial
    )
    assertEquals(runRows(partial), Nil)

    val acceptOnly = withThresholds(ujson.Obj("accept" -> 0.873))
    assert(!acceptOnly.contains("not on the sweep grid"), acceptOnly)
    assertEquals(runRows(acceptOnly), Nil)
  }

  // ---------------------------------------------------------------------------------------------
  // repair-24: the upstream report format

  test("repair-24: the upstream report is named non-core with the exact fields read") {
    val met = eval(
      sweepRows,
      EvalInputs(
        upstreamReport = Some(ujson.Obj("summary" -> ujson.Obj("exportDir" -> 9))),
        provenance = List(("upstream-report", "/SYNTHETIC/run-report.json", "0" * 64))
      )
    )
    val b = met("knownLimitations")("blockingCoverage")
    assertEquals(
      b("upstreamReportFieldsRead").arr.map(_.str).toList,
      List(
        "summary.exportDir",
        "settings.scholarchain.blocking.fallback.maxBlockSize",
        "settings.scholarchain.blocking.maxBlockSize",
        "summary.fallback.uncoveredMembers",
        "summary.fallback.uncoveredPerFamily"
      )
    )
    val note = b("upstreamReportFormat").str
    assert(note.contains("not a core contract") && note.contains("best-effort"), note)
    // a non-string exportDir is not available, never thrown on or compared
    assertEquals(b("upstreamRunMatchesErInputRun"), ujson.Null)
    val text = md(met)
    val line = text.linesIterator.find(_.startsWith("- Blocking coverage")).get
    assert(line.endsWith(note), line)
    assert(
      text.contains("upstream-report: the Hadoop pipeline's run report, an undeclared format"),
      text
    )
    // without the upstream-report role among the inputs the provenance note is not added
    val bare =
      md(eval(sweepRows, EvalInputs(provenance = List(("gold", "/SYNTHETIC/g.csv", "0" * 64)))))
    assert(!bare.contains("upstream-report: the Hadoop pipeline's run report"), bare)
  }
}
