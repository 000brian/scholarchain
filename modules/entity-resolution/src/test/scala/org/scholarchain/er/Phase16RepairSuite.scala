package org.scholarchain.er

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import scala.jdk.CollectionConverters.*

import org.scholarchain.er.eval.*
import org.scholarchain.er.io.ErError
import org.scholarchain.er.policy.{Policy, Thresholds}
import org.scholarchain.er.run.ErMain

/** Phase 16, step 2: N-1 and N-2 (Phase 11 gate), M3 and M4 with F8 (Phase 5 gate), F9 (the
  * fractional-count part of Phase 5 reviewer M2), and G-18 (Phase 14 gate). Synthetic labels, key
  * rows and run artifacts only; nothing reads docs/agent-runs or the human gold file.
  */
class Phase16RepairSuite extends munit.FunSuite {

  // ---------------------------------------------------------------------------------------------
  // N-1: a threshold override may not take the label of a frozen policy

  private val baseArgs: Map[String, String] = Map(
    "input" -> "SYNTHETIC-input",
    "output-root" -> "SYNTHETIC-output",
    "decision-time" -> "2026-11-14T00:00:00Z",
    "seed" -> "7",
    "code-version" -> "test-code",
    "llm-model-id" -> "fake-model/1",
    "llm-max-per-run" -> "0",
    "adjudicator" -> "always-abstain"
  )

  test("N-1: --policy-version er-v1 is refused while the code applies er-v2") {
    val t = Policy.current.thresholds
    val direct = Policy.current.withThresholds("er-v1", t.copy(accept = 0.91))
    assertEquals(direct, Left(Policy.frozenVersionRefusal("er-v1", "er-v2")))
    assert(direct.left.toOption.get.contains("this code applies er-v2"), direct.toString)
    val e = intercept[ErError](
      ErMain.config(baseArgs ++ Map("policy-version" -> "er-v1", "accept-threshold" -> "0.91"))
    )
    assertEquals(e.getMessage, Policy.frozenVersionRefusal("er-v1", "er-v2"))
    // without an override, --policy-version is refused as before
    intercept[ErError](ErMain.config(baseArgs + ("policy-version" -> "er-v1")))
    // a new label is still accepted, and records where it came from
    val ok = Policy.current.withThresholds("er-v1-synthetic-override", t)
    assertEquals(
      ok.map(p => (p.policyVersion, p.overriddenFrom)),
      Right(("er-v1-synthetic-override", Some("er-v2")))
    )
    assertEquals(Policy.FrozenVersions, Set("er-v1", "er-v2"))
  }

  // ---------------------------------------------------------------------------------------------
  // N-2: no user in this module calls the misleading accessor

  private def moduleDir: Path =
    List(Paths.get("modules/entity-resolution"), Paths.get("."))
      .find(p => Files.isDirectory(p.resolve("src/main/scala/org/scholarchain/er")))
      .getOrElse(fail("module sources not found"))

  test("N-2: the module's sources use Policy.current, never the old er-v1 accessor name") {
    val needle = "Policy." + "erV1" // built so this file does not match itself
    val s = Files.walk(moduleDir.resolve("src"))
    val files =
      try s.iterator.asScala.filter(_.toString.endsWith(".scala")).toList
      finally s.close()
    assert(files.size >= 30, s"found only ${files.size} sources")
    val users =
      files.filter(p => new String(Files.readAllBytes(p), StandardCharsets.UTF_8).contains(needle))
    assertEquals(users, Nil)
    assertEquals(Policy.current.policyVersion, "er-v2")
  }

  // ---------------------------------------------------------------------------------------------
  // M3 and M4: Harness rows

  private def m(i: Int) = f"mention:v1:$i%032x"
  private def c(i: Int) = f"match-candidate:v1:$i%032x"
  private def st(path: String, outcome: String, family: String) =
    s"path=$path;outcome=$outcome;family=$family;band=far"

  /** A labeled row whose own decision is `outcome`, sampled in `stratum` (which may name another
    * outcome: an m3 membership).
    */
  private def row(
      i: Int,
      label: String,
      outcome: String,
      stratum: String,
      population: Double
  ): (GoldLabel, KeyRow) = {
    val path = Harness.fieldOf(stratum, "path")
    val r = f"researcher:v1:$i%032x"
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
        Some(r),
        Some(r),
        Some(true)
      )
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

  private def ci(v: ujson.Value) =
    f"[${v.arr(0).num * 100}%.1f%%, ${v.arr(1).num * 100}%.1f%%]"

  private val bigAccepted = st("deterministic", "accepted", "F")
  private val fpAlone = st("probabilistic", "accepted", "F")
  private val unresolvedS = st("probabilistic", "unresolved", "F")

  /** Reviewer R1's shape: a heavy all-MATCH accepted stratum and one false positive alone. */
  private val zeroEventRows: List[(GoldLabel, KeyRow)] =
    (1 to 5).toList.map(i => row(i, "MATCH", "accepted", bigAccepted, 10000)) ++ List(
      row(6, "NON_MATCH", "accepted", fpAlone, 20),
      row(7, "MATCH", "unresolved", unresolvedS, 500),
      row(8, "NON_MATCH", "unresolved", unresolvedS, 500)
    )

  test("M3: the weighted row and the bootstrap line show the zero-event bound next to the flag") {
    val met = eval(zeroEventRows)
    val p = rel(met)("precision")
    assertEquals(p("unreliable"), ujson.Bool(true))
    val bound = ci(p("zeroEventBound95"))
    val md = Harness.markdown(met)
    val wrow = md.linesIterator.find(_.startsWith("| Weighted estimate (bootstrap)")).get
    assert(wrow.contains(s"(unreliable; zero-event bound $bound) |"), wrow)
    val bline = md.linesIterator.find(_.startsWith("Weighted bootstrap 95%:")).get
    assert(bline.contains(s"(unreliable; zero-event bound $bound)"), bline)
    // the F1 entry carries its own bound (F1 of the precision and recall bounds)
    val f1Bound = ci(rel(met)("f1")("zeroEventBound95"))
    assert(bline.contains(s"(unreliable; zero-event bound $f1Bound)"), bline)
    // the collapsed interval is never shown with the bare flag any more
    assert(!md.contains("] (unreliable)"), md)
    // the cluster-level row follows the same rule
    val crow =
      md.linesIterator.find(_.startsWith("| Cluster level, weighted estimate (bootstrap)")).get
    if (crow.contains("unreliable")) assert(crow.contains("(unreliable; zero-event bound ["), crow)
  }

  private def u(outcome: String, label: String, w: Double, stratum: String, id: String) =
    EvalUnit(outcome, label, w, stratum, id)

  private val unresolvedRate =
    IntervalReliability.PairSpecs.find(_.name == "unresolvedRate").get

  test("M4: a rate with some m3 memberships is partly design-fixed, not design-determined") {
    val sA = st("probabilistic", "accepted", "A")
    val sB = st("probabilistic", "accepted", "B")
    // A: every membership agrees with its stratum (fixed); B: one membership is unresolved (m3)
    val units =
      List(u("accepted", "MATCH", 3.0, sA, "1"), u("accepted", "NON_MATCH", 3.0, sA, "2")) ++
        List(u("accepted", "MATCH", 1.0, sB, "3"), u("unresolved", "MATCH", 1.0, sB, "4"))
    val r = IntervalReliability.evaluate(units, unresolvedRate, outcomeFixedByStratum = true)
    assert(!r.designDetermined, r.toString)
    assert(r.partlyDesignFixed, r.toString)
    assertEqualsDouble(r.designFixedShareOfPopulation, 6.0 / 8.0, 1e-12)
    // every membership agrees: still wholly design-determined, never "partly"
    val agreeing = units.filterNot(_.row == "4")
    val all = IntervalReliability.evaluate(agreeing, unresolvedRate, outcomeFixedByStratum = true)
    assert(all.designDetermined && !all.partlyDesignFixed, all.toString)
    // F8's extreme case: no membership agrees, so nothing is fixed and nothing is labeled
    val none =
      List(u("unresolved", "MATCH", 1.0, sA, "5"), u("rejected", "NON_MATCH", 2.0, sB, "6"))
    val n = IntervalReliability.evaluate(none, unresolvedRate, outcomeFixedByStratum = true)
    assert(!n.designDetermined && !n.partlyDesignFixed, n.toString)
    assertEquals(n.designFixedShareOfPopulation, 0.0)
    // a label-dependent metric is never design-fixed in any part
    val precision = IntervalReliability.PairSpecs.find(_.name == "precision").get
    val pr = IntervalReliability.evaluate(units, precision, outcomeFixedByStratum = true)
    assert(!pr.designDetermined && !pr.partlyDesignFixed, pr.toString)
  }

  test("M4: the report labels a partly design-fixed unresolved rate so, with its bound") {
    // stratum unresolvedS holds one membership decided rejected (m3)
    val rows = zeroEventRows :+ row(9, "NON_MATCH", "rejected", unresolvedS, 500)
    val met = eval(rows)
    val ur = rel(met)("unresolvedRate")
    assertEquals(ur("designDetermined"), ujson.Bool(false))
    assertEquals(ur("partlyDesignFixed"), ujson.Bool(true))
    assertEquals(ur("partlyDesignFixedNote").str, IntervalReliability.PartlyDesignFixedNote)
    assertEquals(ur("designDeterminedNote"), ujson.Null)
    val share = ur("designFixedShareOfPopulation").num
    assert(share > 0 && share < 1, share.toString)
    val md = Harness.markdown(met)
    assert(!md.contains("(design-determined)"), md)
    val bline = md.linesIterator.find(_.startsWith("Weighted bootstrap 95%:")).get
    assert(
      bline.matches(
        ".*unresolvedRate \\[[^]]*\\] \\(partly design-fixed[^)]*zero-event bound \\[.*"
      ),
      bline
    )
    val ul = md.linesIterator.find(_.startsWith("- unresolvedRate: bootstrap ")).get
    assert(ul.contains(IntervalReliability.PartlyDesignFixedNote), ul)
    assert(ul.contains("Zero-event bound ["), ul)
    // the consistent sample keeps the wholly design-determined label
    val whole = rel(eval(zeroEventRows))("unresolvedRate")
    assertEquals(whole("designDetermined"), ujson.Bool(true))
    assertEquals(whole("partlyDesignFixed"), ujson.Bool(false))
  }

  // ---------------------------------------------------------------------------------------------
  // F9 (fractional-count part of reviewer M2)

  test("F9: a fractional outcome count is not valid, so an absent outcome key is not 0") {
    def counts(outcomes: ujson.Obj) = {
      val runReport = ujson.Obj(
        "llm" -> ujson.Obj(
          "pairsSentToAdjudicator" -> 5,
          "reasons" -> ujson.Obj("llm-abstain" -> 2),
          "outcomes" -> outcomes
        )
      )
      eval(zeroEventRows, EvalInputs(runReport = Some(runReport)))("researcherResolution")(
        "llmPath"
      )("runCounts")
    }
    val frac = counts(ujson.Obj("accepted" -> 2.5, "unresolved" -> 2.5))
    assertEquals(frac("accepted"), ujson.Null)
    assertEquals(frac("rejected"), ujson.Null)
    // whole counts that add up to the pairs sent still make the absent key 0
    val whole = counts(ujson.Obj("accepted" -> 2, "unresolved" -> 3))
    assertEquals(whole("accepted"), ujson.Num(2))
    assertEquals(whole("rejected"), ujson.Num(0))
  }

  // ---------------------------------------------------------------------------------------------
  // G-18: the policy record names the blocking settings it was evaluated with

  private val phase5Blocking = ujson.Obj(
    "scholarchain.blocking.maxBlockSize" -> 200,
    "scholarchain.blocking.fallback.maxBlockSize" -> 200,
    "scholarchain.candidates.skipSameNumberedName" -> true
  )

  test("G-18: the policy record names Phase 5's blocking caps; an override names none") {
    val ev = Policy.evaluatedWith(Policy.current)
    assertEquals(ev("blocking"), phase5Blocking)
    assertEquals(ev("goldEvaluatedPolicyVersion"), ujson.Str("er-v1"))
    val over = Policy.current
      .withThresholds("er-v2-synthetic", Thresholds(0.91, 0.3, 0.6, 0.8, 0.5))
      .toOption
      .get
    val oe = Policy.evaluatedWith(over)
    assertEquals(oe("blocking"), ujson.Null)
    assertEquals(oe("goldEvaluatedPolicyVersion"), ujson.Null)
    assert(oe("note").str.contains("never evaluated"), oe.render())
  }

  test("G-18: run report and manifest carry the record; the configuration fingerprint does not") {
    val input = Fixture.writeExport(RunFixture.works, maxBlock = 6)
    val out = RunFixture.run(input, Files.createTempDirectory("er-out-g18"))
    def read(rel: String) =
      ujson.read(new String(Files.readAllBytes(out.runDir.resolve(rel)), StandardCharsets.UTF_8))
    val report = read("run-report.json")
    assertEquals(report("policyEvaluatedWith")("blocking"), phase5Blocking)
    val manifest = read("_manifest.json")
    assertEquals(manifest("policyEvaluatedWith")("blocking"), phase5Blocking)
    assert(!manifest("config").obj.contains("policyEvaluatedWith"))
  }
}
